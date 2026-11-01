#!/usr/bin/env python3
"""devproxy.py --web DIR --api http://127.0.0.1:PORT [--port 18990]

UI development without rebuilding the jar: serves DIR/index.html at / and DIR/<name> at /static/<name> from disk
(with the same Content-Security-Policy as the embedded API server), and forwards /api/* to a running OpenPnP web API
(for example a SIM session from src/test/sim/il_ui_session.py). Listens on 127.0.0.1 only.
"""
import argparse
import os
import re
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
       "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8",
         ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png"}
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def make_handler(web, api):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _static(self):
            path = self.path.split("?")[0]
            name = "index.html" if path in ("/", "/index.html") else path[len("/static/"):] if path.startswith("/static/") else None
            if not name or not NAME.match(name) or ".." in name:
                return self._send(404, b"not found", "text/plain")
            f = os.path.join(web, name)
            if not os.path.isfile(f):
                return self._send(404, b"not found", "text/plain")
            with open(f, "rb") as fh:
                self._send(200, fh.read(), TYPES.get(os.path.splitext(name)[1], "application/octet-stream"))

        def _send(self, code, body, ctype):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Security-Policy", CSP)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _proxy(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n) if n else None
            req = urllib.request.Request(api + self.path, data=body, method=self.command,
                                         headers={"Content-Type": self.headers.get("Content-Type", "application/json")})
            try:
                with urllib.request.urlopen(req, timeout=900) as r:
                    self._send(r.status, r.read(), r.headers.get("Content-Type", "application/json"))
            except urllib.error.HTTPError as e:
                self._send(e.code, e.read(), e.headers.get("Content-Type", "application/json"))
            except Exception as e:  # API down
                self._send(502, ('{"error":"proxy","message":"%s"}' % str(e).replace('"', "'")).encode(), "application/json")

        def _any(self):
            if self.path.startswith("/api"):
                return self._proxy()
            if self.command == "GET":
                return self._static()
            return self._send(405, b"method not allowed", "text/plain")

        do_GET = do_POST = do_PUT = do_DELETE = _any

    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--web", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "main",
                                                  "resources", "org", "openpnp", "instancelabs", "web"))
    ap.add_argument("--api", required=True, help="base URL of the OpenPnP web API, e.g. http://127.0.0.1:33693")
    ap.add_argument("--port", type=int, default=18990)
    a = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(os.path.abspath(a.web), a.api.rstrip("/")))
    print("devproxy http://127.0.0.1:%d/ -> %s (web %s)" % (a.port, a.api, os.path.abspath(a.web)), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
