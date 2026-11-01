"""Start and stop a lumen-sim process for the pty tests (one process per test class)."""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import termios
import time
import tty

PKG_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class SimProcess:
    def __init__(self, *args):
        self.dir = tempfile.mkdtemp(prefix="lumen-sim-test-")
        self.link = os.path.join(self.dir, "ttyLUMEN")
        self.sock = os.path.join(self.dir, "ctl.sock")
        self.io_log = os.path.join(self.dir, "io.log")
        cmd = [sys.executable, "-m", "lumen_sim", "--link", self.link, "--control", self.sock,
               "--io-log", self.io_log, *args]
        self.proc = subprocess.Popen(cmd, cwd=PKG_ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        end = time.monotonic() + 10
        while not (os.path.islink(self.link) and os.path.exists(self.sock)):
            if self.proc.poll() is not None or time.monotonic() > end:
                self.proc.kill()
                self.proc.wait()
                err = self.proc.stderr.read().decode()
                self.proc.stderr.close()
                shutil.rmtree(self.dir, ignore_errors=True)
                raise RuntimeError("lumen-sim did not start: " + err)
            time.sleep(0.02)

    def ctl(self, request):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(5)
            s.connect(self.sock)
            s.sendall((json.dumps(request) + "\n").encode())
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        return json.loads(buf)

    def stop(self):
        try:
            if self.proc.poll() is None:
                try:
                    self.ctl({"cmd": "stop"})
                except OSError:
                    pass
                try:
                    self.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.proc.terminate()
                    self.proc.wait(timeout=5)
            self.proc.stderr.close()
        finally:
            shutil.rmtree(self.dir, ignore_errors=True)


def open_raw(path):
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY)
    tty.setraw(fd, termios.TCSANOW)
    return fd


def read_until_ok(fd, timeout=5.0):
    """Lines read from fd until a line starting with 'ok' (inclusive) or the timeout."""
    import select
    buf, lines, end = b"", [], time.monotonic() + timeout
    while time.monotonic() < end:
        r, _, _ = select.select([fd], [], [], max(0.0, end - time.monotonic()))
        if not r:
            break
        chunk = os.read(fd, 4096)
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            lines.append(line.decode("latin-1"))
            if line.startswith(b"ok"):
                return lines
    return lines
