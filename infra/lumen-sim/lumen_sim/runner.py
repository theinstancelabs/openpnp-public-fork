"""The real-time loop: wall clock -> simulator time, pty <-> simulator, control socket, transport faults."""
import math
import select
import time

from .control import ControlServer
from .iolog import IoLog
from .pty_link import PtyLink

CLOSED_POLL_S = 0.01   # how often a closed (or frozen) port is checked for the host opening it


class Runner:
    def __init__(self, sim, link, control=None, io_log=None, time_scale=1.0, clock=time.monotonic, log=None):
        self.sim = sim
        self.link = PtyLink(link)
        self.control = ControlServer(control) if control else None
        self.io = IoLog(io_log) if io_log else None
        self.scale = float(time_scale)
        self.clock = clock
        self.t0 = clock()
        self.log = log or (lambda msg: None)
        self.pending = b""
        self.frozen = False
        self.down_until = None      # link closed by a fault: reopen at this sim time (inf: at the next power cycle)
        self.running = True

    def now(self):
        return (self.clock() - self.t0) * self.scale

    # ------------------------------------------------------------------ main loop
    def run(self):
        self.link.open()
        self.log("lumen-sim %s on %s (link %s)" % (self.sim.personality.name, self.link.pts, self.link.link))
        try:
            while self.running:
                self._step()
        finally:
            self.shutdown()

    def _step(self):
        t = self.now()
        if self.down_until is not None and t >= self.down_until:
            self._reopen(None, "link back")
        if self.link.master is not None and not self.frozen:
            self._read_host()
        t = self.now()
        self.sim.run_until(t)
        self._do_actions(t)
        self._write_out(t)

        wait = self.sim.next_event_time() - self.now()
        if self.down_until is not None:
            wait = min(wait, self.down_until - self.now())
        wait = max(0.0, wait / self.scale)
        readers = []
        if self.link.master is not None and self.link.host_open and not self.frozen:
            readers.append(self.link.master)
        else:
            wait = min(wait, CLOSED_POLL_S)
        writers = [self.link.master] if self.pending and self.link.master is not None and self.link.host_open else []
        if self.control:
            readers.extend(self.control.sockets())
        r, _, _ = select.select(readers, writers, [], min(wait, 1.0))
        if self.control:
            for s in r:
                if s in self.control.sockets():
                    self.control.handle(s, self._handle_control)

    def _read_host(self):
        while True:
            data = self.link.read()
            if not data:
                return
            if self.io:
                self.io.host(data)
            self.sim.receive(data, self.now())

    def _write_out(self, t):
        for _, data in self.sim.take_output(t):
            if self.link.host_open:  # nobody listening: the bytes are lost, as on a closed USB port
                self.pending += data
        while self.pending and self.link.host_open:
            n = self.link.write(self.pending)
            if n <= 0:
                if n < 0:
                    self.pending = b""
                return
            if self.io:
                self.io.controller(self.pending[:n])
            self.pending = self.pending[n:]

    # ------------------------------------------------------------------ control and transport faults
    def _handle_control(self, req):
        t = self.now()
        reply = self.sim.control(req, t)
        if reply.get("ok"):
            self._do_actions(t)
            if req.get("cmd") == "state":
                reply["state"].update(self.transport_state())
            elif req.get("cmd") == "power_cycle":
                reply.update(self.transport_state())
        return reply

    def transport_state(self):
        return {"link": self.link.link, "pts": self.link.pts, "host_open": self.link.host_open,
                "frozen": self.frozen, "link_down": self.down_until is not None, "time_scale": self.scale}

    def _do_actions(self, t):
        for a in self.sim.take_transport_actions():
            kind = a["action"]
            if kind == "power_cycle":
                self._reopen(a.get("link"), "power cycle")
            elif kind == "disconnect":
                self.link.close()
                self.pending = b""
                self.down_until = math.inf if a.get("down_s") is None else t + a["down_s"]
                self._note("disconnect")
            elif kind == "frozen":
                self.frozen = bool(a["on"])
                self._note("frozen %s" % ("on" if self.frozen else "off"))
            elif kind == "clear":
                self.frozen = False
            elif kind == "stop":
                self.running = False

    def _reopen(self, link, why):
        self.link.close()
        self.pending = b""
        self.down_until = None
        self.link.open(link)
        self._note("%s: %s" % (why, self.link.pts))
        self.log("lumen-sim %s: %s (link %s)" % (why, self.link.pts, self.link.link))

    def _note(self, text):
        if self.io:
            self.io.note(text)

    def shutdown(self):
        self.link.close()
        self.link.remove_link()
        if self.control:
            self.control.close()
        if self.io:
            self.io.close()
