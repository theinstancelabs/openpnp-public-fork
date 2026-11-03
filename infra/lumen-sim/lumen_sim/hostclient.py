"""A host that talks to the controller the way OpenPnP's GcodeDriver does (GcodeDriver.java sendCommand / reader).

- A reader thread splits input on CR or LF and skips empty lines; each line is checked against the confirm regex
  (^ok.*) and the machine's COMMAND_ERROR_REGEX.
- send(): fail on an earlier error line ("bail on error"), clear the shared confirmation queue, write line + LF, wait
  for a confirmation up to the timeout (-1 = 60 s), else raise DriverTimeout("... timeout waiting for response to
  <line>"). A late ok stays in the queue and is cleared by the next send, or credited to it if it comes after the
  write. Two threads may send at once and share the queue, as the web STOP thread does in OpenPnP.

    c = DriverClient("/tmp/lumen/ttyLUMEN"); c.send("M115"); print(c.responses); c.close()
"""
import os
import re
import select
import termios
import threading
import time
import tty

CONFIRM_RE = re.compile(r"^ok.*")
ERROR_RE = re.compile(r"(?i).*(?:\berror\s*:|!!|\bstart\b|reset|disconnect|\bresend\s*:|\brs\s+N?\d|unknown command|"
                      r"halted|fatal|\bkilled\b).*")
INFINITY_MS = 60000


class DriverTimeout(Exception):
    pass


class ControllerError(Exception):
    pass


class DriverClient:
    def __init__(self, port, timeout_ms=10000):
        self.port = port
        self.timeout_ms = timeout_ms
        self.fd = os.open(port, os.O_RDWR | os.O_NOCTTY)
        tty.setraw(self.fd, termios.TCSANOW)
        self.lines = []          # (time.monotonic(), line) for every received line
        self.responses = []      # non-ok lines received during the last successful send
        self.error_response = None
        self.reader_error = None
        self._confirm = []
        self._cond = threading.Condition()
        self._closed = False
        self._thread = threading.Thread(target=self._read, name="lumen-sim reader", daemon=True)
        self._thread.start()

    def _read(self):
        buf = b""
        while not self._closed:
            try:
                r, _, _ = select.select([self.fd], [], [], 0.1)
                if not r:
                    continue
                data = os.read(self.fd, 4096)
            except OSError as e:
                self.reader_error = e
                break
            if not data:
                self.reader_error = EOFError("port closed")
                break
            if b"\xff" in data:  # SerialPortCommunications.read() returns 0xFF as -1: end of stream
                buf += data[:data.index(b"\xff")]
                self.reader_error = EOFError("0xFF read as end of stream")
                self._take_lines(buf + b"\n")
                break
            buf = self._take_lines(buf + data)
        with self._cond:
            self._cond.notify_all()

    def _take_lines(self, buf):
        parts = re.split(rb"[\r\n]", buf)
        rest = parts.pop()
        for raw in parts:
            if not raw:
                continue
            line = raw.decode("latin-1")
            with self._cond:
                self.lines.append((time.monotonic(), line))
                if CONFIRM_RE.match(line):
                    self._confirm.append(line)
                if ERROR_RE.match(line):
                    self.error_response = line
                self._cond.notify_all()
        return rest

    def send(self, line, timeout_ms=None):
        timeout_ms = self.timeout_ms if timeout_ms is None else timeout_ms
        if self.error_response is not None:
            err, self.error_response = self.error_response, None
            raise ControllerError("GcodeDriver:%s error response from controller: %s" % (self.port, err))
        if self.reader_error is not None:
            raise ControllerError("IO Error on reading from the controller.")
        with self._cond:
            self._confirm.clear()
            start = len(self.lines)
        os.write(self.fd, (line + "\n").encode("latin-1"))
        deadline = time.monotonic() + (INFINITY_MS if timeout_ms == -1 else timeout_ms) / 1000.0
        with self._cond:
            while not self._confirm:
                left = deadline - time.monotonic()
                if left <= 0 or self._closed or self.reader_error is not None:
                    break
                self._cond.wait(left)
            if self._confirm:
                ok = self._confirm.pop(0)
                self.responses = [l for _, l in self.lines[start:] if not CONFIRM_RE.match(l)]
                return ok
        if self.reader_error is not None or self._closed:
            raise ControllerError("IO Error on reading from the controller.")
        raise DriverTimeout("GcodeDriver:%s timeout waiting for response to %s" % (self.port, line))

    def wait_line(self, pred, after, timeout):
        """Monotonic time of the first received line after `after` matching pred, waiting up to `timeout` s."""
        end = time.monotonic() + timeout
        with self._cond:
            while True:
                for t, l in self.lines:
                    if t > after and pred(l):
                        return t
                left = end - time.monotonic()
                if left <= 0 or self._closed:
                    return None
                self._cond.wait(left)

    def close(self):
        self._closed = True
        with self._cond:
            self._cond.notify_all()
        self._thread.join(1.0)
        os.close(self.fd)
