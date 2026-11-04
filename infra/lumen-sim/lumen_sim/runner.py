"""The real-time loop: wall clock -> simulator time, pty <-> simulator, control socket, transport faults."""
import json
import math
import os
import select
import time

from .control import ControlServer
from .iolog import IoLog
from .pty_link import PtyLink

CLOSED_POLL_S = 0.01   # how often a closed (or frozen) port is checked for the host opening (or closing) it
RETRY_S = 1.0          # a port that could not come back anywhere is tried again this much later (simulator s)
POSE_SUFFIX = ".pose.json"  # <link>.pose.json: the head's physical - logical offset, for the SIM cameras (INF-05)


class Runner:
    def __init__(self, sim, link, control=None, io_log=None, time_scale=1.0, clock=time.monotonic, log=None,
                 guard=None):
        scale = float(time_scale)
        if not (math.isfinite(scale) and scale > 0):  # NaN or inf: now() is NaN and the loop never sleeps
            raise ValueError("time_scale must be a finite number > 0, not %r" % (time_scale,))
        self.sim = sim
        self.guard = guard  # pty_link.LinkGuard: removes the links if this process is killed
        self.link = PtyLink(link, guard)
        try:
            self.control = ControlServer(control) if control else None
            self.io = IoLog(io_log) if io_log else None
        except BaseException:
            self.link.release()
            raise
        self.scale = scale
        self.clock = clock
        self.t0 = clock()
        self.log = log or (lambda msg: None)
        self.pending = b""
        self.frozen = False
        self.down_until = None      # link closed by a fault: reopen at this sim time (inf: at the next power cycle)
        self.next_link = None       # a power cycle's new link path, published when the link comes back
        self.running = True
        self.pose = None            # (path, offset) last written to the pose file
        self.pose_error = None      # its last write's error, noted once
        sim.on_error = self._sim_error

    def now(self):
        return (self.clock() - self.t0) * self.scale

    # ------------------------------------------------------------------ main loop
    def run(self):
        self.link.open()
        self.log("lumen-sim %s on %s (link %s)" % (self.sim.personality.name, self.link.pts, self.link.link))
        try:
            while self.running:
                self._service()
                self._wait()
        finally:
            self.shutdown()

    def _service(self):
        """Everything due now: link back, host input, host open/close, simulator time, actions, output."""
        t = self.now()
        if self.down_until is not None and t >= self.down_until:
            try:
                self._reopen(self.next_link, "link back")
            except (OSError, ValueError) as e:  # as in _do_actions: noted, and the loop goes on
                self.log("lumen-sim link back: %s: %s" % (type(e).__name__, e))
                self._note("link back: %s: %s" % (type(e).__name__, e))
        if self.link.master is not None:
            if not self.frozen:
                self._read_host()
            self._watch_host()
        t = self.now()
        self.sim.run_until(t)
        self._do_actions(t)
        self._publish_pose(t)  # before the output: a G28's ok never reaches the host ahead of where it landed
        self._write_out(t)

    def _wait(self):
        """Sleep until the next simulator event, host input, a control request, or the next check of the port."""
        wait = self.sim.next_event_time() - self.now()
        if self.down_until is not None:
            wait = min(wait, self.down_until - self.now())
        wait = max(0.0, wait / self.scale)
        readers = []
        if self.link.master is not None and self.link.host_open and not self.frozen:
            readers.append(self.link.master)  # readable on input and on the host's close (POLLHUP)
        else:
            wait = min(wait, CLOSED_POLL_S)
        writers = [self.link.master] if self.pending and self.link.master is not None and self.link.host_open else []
        if self.control:
            readers.extend(self.control.readers())
            writers.extend(self.control.writers())
        r, w, _ = select.select(readers, writers, [], min(wait, 1.0))
        if self.control:
            self.control.service(r, w, self._handle_control)

    def _read_host(self):
        while True:
            data = self.link.read()
            if not data:
                return
            if self.io:
                self.io.host(data)
            self.sim.receive(data, self.now())

    def _watch_host(self):
        """On the host's close, drop what it did not read: the next host starts clean, as on a USB port."""
        was, self.link.host_open = self.link.host_open, self.link.host_present()
        if was and not self.link.host_open:
            unread = self.link.flush_unread()
            self.pending = b""
            if unread is None:
                self._note("host closed (its unread bytes could not be dropped)")
            else:
                self._note("host closed" + (", %d unread bytes dropped" % unread if unread else ""))

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

    def _publish_pose(self, t):
        """The head's physical - logical offset at <link>.pose.json (INF-05: the SIM top camera draws OpenPnP's location
        plus it), rewritten atomically when it or the link path changes. Not inside a G28: each homing phase zeroes its
        axis, which is no head error, and OpenPnP believes the old place until the ok. It stays while the port is down
        (the head is still there). A failed write is noted once and tried again at the next step."""
        if self.sim.fw.in_g28:
            return
        path = self.link.link + POSE_SUFFIX
        offset = self.sim.head(t)["offset"]
        if self.pose == (path, offset):
            return
        if self.pose and self.pose[0] != path:
            _remove(self.pose[0])
        tmp = "%s.tmp%d" % (path, os.getpid())
        try:
            if os.path.lexists(tmp):
                os.unlink(tmp)
            # O_EXCL: never written through a symlink someone left at tmp
            with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o644), "w") as f:
                json.dump({"offset": offset}, f)
            os.replace(tmp, path)
        except OSError as e:
            _remove(tmp)
            error = "pose file: %s: %s" % (type(e).__name__, e)
            if error != self.pose_error:
                self.pose_error = error
                self.log("lumen-sim " + error)
                self._note(error)
            return
        self.pose, self.pose_error = (path, offset), None

    # ------------------------------------------------------------------ control and transport faults
    def _handle_control(self, req):
        t = self.now()
        link = req.get("link") if req.get("cmd") == "power_cycle" else None
        try:
            if isinstance(link, str):
                try:  # a bad path, or one another lumen-sim holds, is refused before the controller is reset
                    req = dict(req, link=self.link.reserve(link))
                except (OSError, ValueError) as e:
                    return {"ok": False, "error": str(e)}
            reply = self.sim.control(req, t)
            if reply.get("ok"):
                errors = self._do_actions(t)
                self._publish_pose(t)  # a client that injects a fault, then grabs a frame, sees it
                if req.get("cmd") == "state":
                    reply["state"].update(self.transport_state())
                elif req.get("cmd") == "power_cycle" or errors:
                    reply.update(self.transport_state())
                if errors:  # carried out (a power cycle has reset the board), but the port could not follow
                    reply["link_error"] = "; ".join(errors)
            return reply
        finally:
            self.link.let_go(keep=self.next_link)  # a refused or replaced power cycle's new path

    def transport_state(self):
        return {"link": self.link.link, "pts": self.link.pts, "host_open": self.link.host_open,
                "frozen": self.frozen, "link_down": self.down_until is not None, "time_scale": self.scale,
                "guard_pid": self.guard.pid if self.guard else None}

    def _do_actions(self, t):
        """Carry out the simulator's transport actions; the errors, as text (a failure never stops the loop)."""
        errors = []
        for a in self.sim.take_transport_actions():
            try:
                self._do_action(a, t)
            except (OSError, ValueError) as e:
                errors.append("%s: %s: %s" % (a["action"], type(e).__name__, e))
                self.log("lumen-sim %s" % errors[-1])
                self._note(errors[-1])
        return errors

    def _do_action(self, a, t):
        kind = a["action"]
        if kind == "power_cycle":
            self.frozen = False  # the reset MCU reads its port again
            if a.get("down_s"):
                self._go_down(t, a["down_s"], "power cycle")
                self.next_link = a.get("link") or self.next_link
            else:
                self._reopen(a.get("link") or self.next_link, "power cycle")
        elif kind == "disconnect":
            self._go_down(t, a.get("down_s"), "disconnect")
        elif kind == "frozen":
            self.frozen = bool(a["on"])
            self._note("frozen %s" % ("on" if self.frozen else "off"))
        elif kind == "clear":
            self.frozen = False
        elif kind == "stop":
            self.running = False

    def _go_down(self, t, down_s, why):
        """The port goes away (master closed, link removed) for down_s, or until a power cycle if None."""
        self.link.close()
        self.pending = b""
        self.down_until = math.inf if down_s is None else t + down_s
        self._note("%s: port gone" % why)

    def _reopen(self, link, why):
        """The port comes back at `link` (None: where it was). If it cannot, it comes back where it was; if it cannot
        come back at all (no directory, no pty), it stays down and is tried again RETRY_S later. Raises then too."""
        old = self.link.link
        self.link.close()
        self.pending = b""
        self.down_until = None
        self.next_link = None
        try:
            self.link.open(link)
        except (OSError, ValueError):
            try:
                self.link.open()  # come back where the port was, so the controller stays reachable
            except (OSError, ValueError) as e:
                self.down_until = self.now() + RETRY_S
                self._note("%s: port still gone (%s: %s), again in %g s" % (why, type(e).__name__, e, RETRY_S))
                raise
            self._note("%s: %s (back at %s)" % (why, self.link.pts, old))
            raise
        finally:
            self.link.let_go()  # the new path's claim, if open() never took it
        self._note("%s: %s" % (why, self.link.pts))
        self.log("lumen-sim %s: %s (link %s)" % (why, self.link.pts, self.link.link))

    def _sim_error(self, rec):
        text = "sim error%s: %s" % (" in %r" % rec["line"] if rec["line"] else "", rec["error"])
        self.log("lumen-sim " + text)
        self._note(text)

    def _note(self, text):
        if self.io:
            self.io.note(text)

    def shutdown(self):
        if self.pose:
            _remove(self.pose[0])
            self.pose = None
        self.link.release()
        if self.control:
            self.control.close()
        if self.io:
            self.io.close()


def _remove(path):
    try:
        os.unlink(path)
    except OSError:  # gone already (its directory with it), or not ours to remove: never worth ending the loop
        pass
