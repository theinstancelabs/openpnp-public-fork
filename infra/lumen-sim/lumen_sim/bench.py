"""A fake-clock host for the deterministic core: write lines, run time forward, read what came back.

    b = Bench(personality="stock")
    r = b.send("G4 P2500")       # r.lines, r.ok_at (s after the send), r.busy (s of each busy line)
    b.write("M410\n"); b.run(1.0) # raw writes and free-running time; b.rx = [(t, line)], b.raw = all bytes
"""
from .core import Simulator


class Reply:
    def __init__(self):
        self.lines = []     # every line up to and including the first ok
        self.times = []     # seconds after the send, per line
        self.busy = []      # seconds after the send of each 'echo:busy: processing'
        self.ok_at = None   # seconds after the send of the ok, or None on timeout

    def __repr__(self):
        return "Reply(ok_at=%r, lines=%r)" % (self.ok_at, self.lines)


class Bench:
    def __init__(self, **kw):
        self.sim = Simulator(**kw)
        self.t = 0.0
        self.rx = []
        self.raw = b""
        self._partial = b""

    def _collect(self):
        for t, data in self.sim.take_output(self.t):
            self.raw += data
            parts = (self._partial + data).split(b"\n")
            self._partial = parts.pop()
            self.rx.extend((t, p.decode("latin-1")) for p in parts)

    def _step(self, limit):
        """Run to the next event if it is due by `limit`; False when there is none."""
        nt = self.sim.next_event_time()
        if nt > limit:
            return False
        nt = max(nt, self.t)
        self.sim.run_until(nt)
        self.t = nt
        self._collect()
        return True

    def run_until(self, t_end):
        while self._step(t_end):
            pass
        self.sim.run_until(t_end)
        self.t = max(self.t, t_end)
        self._collect()

    def run(self, seconds):
        self.run_until(self.t + seconds)

    def write(self, data):
        if isinstance(data, str):
            data = data.encode("latin-1")
        self.sim.receive(data, self.t)
        self._collect()

    def send(self, line, timeout=60.0):
        """Write one line and run until its ok (or `timeout` seconds)."""
        t0, idx, r = self.t, len(self.rx), Reply()
        self.write(line + "\n")
        while True:
            while idx < len(self.rx):
                t, text = self.rx[idx]
                idx += 1
                r.lines.append(text)
                r.times.append(t - t0)
                if text == "echo:busy: processing":
                    r.busy.append(t - t0)
                if text.startswith("ok"):
                    r.ok_at = t - t0
                    return r
            if not self._step(t0 + timeout):
                self.run_until(t0 + timeout)
                while idx < len(self.rx):
                    t, text = self.rx[idx]
                    idx += 1
                    r.lines.append(text)
                    r.times.append(t - t0)
                return r
