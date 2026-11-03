"""The I/O log, in serial.log's format: '<epoch seconds> > line' (host to controller), '<epoch seconds> < line'."""
import time


def _text(raw):
    out = []
    for c in raw:
        out.append(chr(c) if 32 <= c < 127 or c == 9 else "\\x%02x" % c)
    return "".join(out)


class IoLog:
    def __init__(self, path, clock=time.time):
        # notes quote host lines (a model error's repr), which may hold any byte: escape, never fail
        self.f = open(path, "a", encoding="ascii", errors="backslashreplace", buffering=1)
        self.clock = clock
        self._rx = b""
        self._tx = b""

    def host(self, data):
        """Bytes the host wrote; CR or LF ends a line, empty lines are skipped (as the controller reads them)."""
        self._rx += data
        while True:
            cut = min((i for i in (self._rx.find(b"\n"), self._rx.find(b"\r")) if i >= 0), default=-1)
            if cut < 0:
                return
            line, self._rx = self._rx[:cut], self._rx[cut + 1:]
            if line:
                self.f.write("%.3f > %s\n" % (self.clock(), _text(line)))

    def controller(self, data):
        """Bytes the controller sent to the host; a line is logged when its LF goes out."""
        self._tx += data
        while b"\n" in self._tx:
            line, self._tx = self._tx.split(b"\n", 1)
            self.f.write("%.3f < %s\n" % (self.clock(), _text(line)))

    def note(self, text):
        self.f.write("%.3f # %s\n" % (self.clock(), text))

    def close(self):
        self.f.close()
