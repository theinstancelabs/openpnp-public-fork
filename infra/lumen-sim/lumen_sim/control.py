"""The control channel: a Unix socket speaking JSON lines (one request line in, one reply line out)."""
import json
import os
import socket
import stat

MAX_REQUEST = 1 << 20  # a request line longer than this (with no newline yet) drops its client


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
        self.clients = {}  # socket -> (request bytes not yet answered, reply bytes not yet sent)

    def readers(self):
        """Sockets to wait on for reading: the listener, and every client with nothing left to answer or send. A
        client that does not read its replies is not read either, so it cannot pile up requests or replies here."""
        return [self.sock] + [c for c, (inbuf, out) in self.clients.items() if not out and b"\n" not in inbuf]

    def writers(self):
        """Clients with replies waiting for room in their socket."""
        return [c for c, (_, out) in self.clients.items() if out]

    def service(self, readable, writable, handler):
        """Everything select() found ready among readers() and writers()."""
        for sock in writable:
            if sock in self.clients:
                self.flush(sock, handler)
        for sock in readable:
            if sock is self.sock or sock in self.clients:
                self.handle(sock, handler)

    def handle(self, sock, handler):
        """Service one readable socket; handler(request dict) -> reply dict. Never blocks."""
        if sock is self.sock:
            try:
                conn, _ = self.sock.accept()
            except BlockingIOError:
                return
            conn.setblocking(False)
            self.clients[conn] = (bytearray(), bytearray())
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
        inbuf = self.clients[sock][0]
        inbuf += data
        if b"\n" not in inbuf and len(inbuf) > MAX_REQUEST:
            self._drop(sock)  # no request is this long
            return
        self._answer(sock, handler)

    def flush(self, sock, handler):
        """A writable client: send what waits, then answer the requests it held back."""
        if self._send(sock):
            self._answer(sock, handler)

    def _answer(self, sock, handler):
        """Answer request lines in order until none is left or a reply cannot be sent at once."""
        inbuf, out = self.clients[sock]
        while not out and b"\n" in inbuf:
            i = inbuf.index(b"\n")
            line = bytes(inbuf[:i])
            del inbuf[:i + 1]
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
            out += (json.dumps(reply) + "\n").encode()
            if not self._send(sock):
                return

    def _send(self, sock):
        """Send what the socket takes now; False if the client is gone (dropped)."""
        out = self.clients[sock][1]
        try:
            while out:
                n = sock.send(out)
                del out[:n]
        except BlockingIOError:
            pass
        except OSError:
            self._drop(sock)
            return False
        return True

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
