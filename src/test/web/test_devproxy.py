"""devproxy answers only its own names and --allow-host's, exactly: any other Host is a 403 before anything is
forwarded (a DNS-rebinding page names its own site in Host; #35's review). Standard library only.

Run: python3 -m unittest discover -v -s src/test/web -p 'test_*.py'
"""
import http.client
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import devproxy

TAILNET = "lumen-thinkpad.tail3e978a.ts.net:8443"


class FakeApi(BaseHTTPRequestHandler):
    """The OpenPnP API: answers 200 and records every request that reached it."""
    hits = []

    def log_message(self, *a):
        pass

    def _any(self):
        FakeApi.hits.append((self.command, self.path))
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = _any


class HostCheck(unittest.TestCase):
    def serve(self, handler):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return srv.server_port

    def setUp(self):
        FakeApi.hits = []
        web = tempfile.TemporaryDirectory()
        self.addCleanup(web.cleanup)
        with open(os.path.join(web.name, "index.html"), "w", encoding="utf-8") as fh:
            fh.write("<p>ui</p>")
        self.web, self.api = web.name, "http://127.0.0.1:%d" % self.serve(FakeApi)
        self.port = self.serve(devproxy.make_handler(self.web, self.api, None))

    def ask(self, port, host, method="GET", path="/api/status"):
        """The status code for a request with this Host header (None: no Host header at all)."""
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        self.addCleanup(c.close)
        c.putrequest(method, path, skip_host=True)
        if host is not None:
            c.putheader("Host", host)
        c.putheader("Content-Length", "2" if method == "POST" else "0")
        c.endheaders(b"{}" if method == "POST" else None)
        r = c.getresponse()
        r.read()
        return r.status

    def test_own_names_are_served_and_forwarded(self):
        for host in ("127.0.0.1:%d" % self.port, "localhost:%d" % self.port):
            self.assertEqual(self.ask(self.port, host), 200, host)
            self.assertEqual(self.ask(self.port, host, path="/"), 200, host)
        self.assertEqual(FakeApi.hits, [("GET", "/api/status")] * 2)

    def test_other_hosts_are_refused_before_forwarding(self):
        p = self.port
        for host in ("evil.example:%d" % p, "evil.example", "127.0.0.1", "localhost", "127.0.0.1:%d" % (p + 1),
                     "127.0.0.1:%d.evil.example" % p, "", None, TAILNET):
            for method, path in (("GET", "/api/status"), ("POST", "/api/stop"), ("GET", "/")):
                self.assertEqual(self.ask(p, host, method, path), 403, (host, method, path))
        self.assertEqual(FakeApi.hits, [])

    def test_allow_host_adds_exactly_that_name(self):
        t = self.serve(devproxy.make_handler(self.web, self.api, None, allow_host=TAILNET))
        for host in (TAILNET, TAILNET.upper(), "127.0.0.1:%d" % t, "localhost:%d" % t):
            self.assertEqual(self.ask(t, host), 200, host)
        self.assertEqual(len(FakeApi.hits), 4)
        for host in ("lumen-thinkpad.tail3e978a.ts.net", "lumen-thinkpad.tail3e978a.ts.net:443", TAILNET + ".evil.example",
                     "evil." + TAILNET, "", None):
            self.assertEqual(self.ask(t, host), 403, host)
        self.assertEqual(len(FakeApi.hits), 4)
        # the flag's case does not matter either (the review of #59: a lower-case TAILNET let a dropped .lower() pass)
        u = self.serve(devproxy.make_handler(self.web, self.api, None, allow_host=TAILNET.upper()))
        self.assertEqual(self.ask(u, TAILNET), 200, "an upper-case --allow-host allows the name in lower case")


if __name__ == "__main__":
    unittest.main()
