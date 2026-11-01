"""The control channel: a Unix socket speaking JSON lines (one request line in, one reply line out)."""
import json
import os
import socket
import stat


def _answers(path):
    """Whether something accepts connections at the socket path (anything but 'refused' counts as yes)."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        try:
            s.connect(path)
        except (ConnectionRefusedError, FileNotFoundError):
            return False
        except OSError:
            return True
        return True


class ControlServer:
    def __init__(self, path):
        """Listen at path. A stale socket (nobody listening, as a killed lumen-sim leaves it) is replaced; a live one,
        or anything that is not a socket, is refused with ValueError and left alone."""
        self.path = os.path.abspath(path)
        if os.path.lexists(self.path):
            if not stat.S_ISSOCK(os.lstat(self.path).st_mode):
                raise ValueError("%s exists and is not a socket: not removed" % self.path)
            if _answers(self.path):
                raise ValueError("%s is in use by another lumen-sim" % self.path)
            os.unlink(self.path)
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind(self.path)
        st = os.stat(self.path)
        self.ident = (st.st_dev, st.st_ino)  # close() removes the path only while it is still this socket
        self.sock.listen(8)
        self.sock.setblocking(False)
        self.clients = {}  # socket -> pending bytes

    def sockets(self):
        return [self.sock, *self.clients]

    def handle(self, sock, handler):
        """Service one readable socket; handler(request dict) -> reply dict."""
        if sock is self.sock:
            try:
                conn, _ = self.sock.accept()
            except BlockingIOError:
                return
            conn.setblocking(False)
            self.clients[conn] = b""
            return
        try:
            data = sock.recv(65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            self._drop(sock)
            return
        buf = self.clients[sock] + data
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if not line.strip():
                continue
            try:
                req = json.loads(line)
                if not isinstance(req, dict):
                    raise ValueError("request must be a JSON object")
            except ValueError as e:
                reply = {"ok": False, "error": "bad request: %s" % e}
            else:
                try:
                    reply = handler(req)
                except Exception as e:  # a bad request must never take the simulator (and its port) down
                    reply = {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}
            try:
                sock.setblocking(True)
                sock.sendall((json.dumps(reply) + "\n").encode())
                sock.setblocking(False)
            except OSError:
                self._drop(sock)
                return
        self.clients[sock] = buf

    def _drop(self, sock):
        self.clients.pop(sock, None)
        sock.close()

    def close(self):
        for c in list(self.clients):
            self._drop(c)
        if self.sock.fileno() < 0:
            return
        self.sock.close()
        try:
            st = os.stat(self.path)
        except FileNotFoundError:
            return
        if (st.st_dev, st.st_ino) == self.ident:
            os.unlink(self.path)


def request(path, req, timeout=10.0):
    """Send one request to a running simulator and return its reply."""
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
        s.settimeout(timeout)
        s.connect(path)
        s.sendall((json.dumps(req) + "\n").encode())
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    return json.loads(buf)
