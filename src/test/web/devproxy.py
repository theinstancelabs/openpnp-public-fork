#!/usr/bin/env python3
"""devproxy.py --web DIR --api http://127.0.0.1:PORT [--port 18990] [--fixtures DIR] [--route-fixtures DIR [--routes FILE]]

UI development without rebuilding the jar: serves DIR/index.html at / and DIR/<name> at /static/<name> from disk
(with the same Content-Security-Policy as the embedded API server), and forwards /api/* to a running OpenPnP web API
(for example a SIM session from src/test/sim/il_ui_session.py). Listens on 127.0.0.1 only.

--fixtures DIR (board drawings, docs/instancelabs/BOARD_IMPORT_DESIGN.md §6.6):
  GET /api/board-art/<id>   answered from DIR/art/<id>.json (the art slice's generated drawings), else from
                            DIR/*.art.json whose id (the first 16 hex digits of the SHA-256 of the file's bytes) is <id>,
                            with the server's immutable cache header; any other id is the session's own drawing (an
                            import, the fallback of a board file) and goes to the session
  POST /api/plan, GET /api/plan/<id>
                            a plan of a board file listed in DIR/art/board-map-generated.json or DIR/board-map.json
                            ({"ftp.board.xml": "9626d1aefbbc2ad3"}: an art id or a file in DIR; keys starting with "_"
                            are notes; board-map.json's entries win; a uinav "00-" prefix of the file name is
                            ignored) gets the plan's "board" block: toMachine = T(x, y)·R(rotation) of the plan's seat,
                            the drawing's size and fiducials, art {id, kind}; plus synthetic per-dot times (0.6 s per dot:
                            dots[i].ta / .te) and "dot": {"diameterMm": 0.6} while the session knows no dot size. A plan
                            that already has a board block keeps it (the server makes them); everything else goes to the
                            session.

--route-fixtures DIR (development only: answers the server does not give yet, or a state that takes minutes to reach,
  docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §13; e.g. src/test/web/fixtures/padprofile): DIR/routes.json (or
  --routes FILE in DIR) maps "METHOD /api/path" (a * in the path matches any run of characters, the query is ignored)
  to the answer:
    "file.json"                              the file's JSON, 200
    {"status": 202, "file": "file.json"}     the file's JSON with that status
    {"merge": {"key": "file.json"}}          the session's own answer with those keys set from the files (e.g. the
                                             status with a padCalibration job in a given state)
  Keys starting with "_" are notes. Files are read at every request, so they can be edited while it runs.
"""
import argparse
import fnmatch
import glob
import hashlib
import json
import math
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
ART_ID = re.compile(r"^/api/board-art/([0-9a-f]{16})$")
ART_FILE = re.compile(r"^[0-9a-f]{16}\.json$")
PLAN_PATH = re.compile(r"^/api/plan(/[^/?]+)?$")
IMMUTABLE = "private, max-age=31536000, immutable"
LEAD_S, DOT_S, DOT_DWELL_S, DOT_MM = 10.0, 0.6, 0.4, 0.6


class Fixtures:
    """Board drawings and the board-file map of --fixtures DIR (read once at start)."""

    def __init__(self, root):
        self.root = os.path.abspath(root)
        self.arts = {}  # id -> path
        drawings = glob.glob(os.path.join(self.root, "*.art.json")) + [
            p for p in glob.glob(os.path.join(self.root, "art", "*.json")) if ART_FILE.match(os.path.basename(p))]
        for p in sorted(drawings):
            with open(p, "rb") as fh:
                self.arts[hashlib.sha256(fh.read()).hexdigest()[:16]] = p
        self.map = {}
        for mp in (os.path.join(self.root, "art", "board-map-generated.json"), os.path.join(self.root, "board-map.json")):
            if not os.path.isfile(mp):
                continue
            with open(mp, encoding="utf-8") as fh:
                for k, v in json.load(fh).items():
                    if k.startswith("_"):
                        continue
                    self.map[k] = v if re.match(r"^[0-9a-f]{16}$", v) else self.id_of(os.path.join(self.root, v))

    @staticmethod
    def id_of(path):
        with open(path, "rb") as fh:
            return hashlib.sha256(fh.read()).hexdigest()[:16]

    def art_bytes(self, art_id):
        p = self.arts.get(art_id)
        if not p:
            p = os.path.join(self.root, "art", art_id + ".json")
            if not os.path.isfile(p):
                return None
        with open(p, "rb") as fh:
            return fh.read()

    def art_for(self, board_file):
        name = os.path.basename(board_file or "")
        return self.map.get(name) or self.map.get(re.sub(r"^\d+-", "", name))

    def augment(self, plan):
        """The plan's board block, per-dot times and dot size (§6.6)."""
        if not isinstance(plan, dict) or "planId" not in plan:
            return plan
        dots = plan.get("dots") or []
        if dots and not all("ta" in d for d in dots):
            for i, d in enumerate(dots):
                d["ta"] = round(LEAD_S + DOT_S * i, 2)
                d["te"] = round(LEAD_S + DOT_S * i + DOT_DWELL_S, 2)
        if not (plan.get("dot") or {}).get("diameterMm"):
            plan["dot"] = {"diameterMm": DOT_MM, "source": "ladder"}
        src = plan.get("source") or {}
        if plan.get("board") or src.get("source") != "file":
            return plan
        art_id = self.art_for(src.get("file"))
        data = self.art_bytes(art_id) if art_id else None
        if not data:
            return plan
        art = json.loads(data)
        x, y, rot = float(src.get("x") or 0), float(src.get("y") or 0), math.radians(float(src.get("rotation") or 0))
        c, s = math.cos(rot), math.sin(rot)
        m = [c, s, -s, c, x, y]

        def to_m(bx, by):
            return m[0] * bx + m[2] * by + m[4], m[1] * bx + m[3] * by + m[5]
        fids = []
        for f in art.get("fiducials") or []:
            mx, my = to_m(f["bx"], f["by"])
            fids.append({"id": f["name"], "bx": f["bx"], "by": f["by"], "x": round(mx, 4), "y": round(my, 4)})
        plan["board"] = {"name": (art.get("import") or {}).get("name") or plan.get("boardName"), "file": src.get("file"),
                         "widthMm": art["size"][0], "heightMm": art["size"][1], "side": "Top", "toMachine": m,
                         "transformStatus": "NotSet", "fiducials": fids,
                         "art": {"id": art_id, "kind": art.get("kind") or "kicad", "note": None}}
        return plan


class RouteFixtures:
    """Canned answers per route from --route-fixtures DIR (read at every request)."""

    def __init__(self, root, routes):
        self.root = os.path.abspath(root)
        self.file = os.path.join(self.root, routes)
        if not os.path.isfile(self.file):
            raise SystemExit("devproxy: no routes file %s" % self.file)

    def _load(self, name):
        with open(os.path.join(self.root, name), encoding="utf-8") as fh:
            return json.load(fh)

    def find(self, method, path):
        """The route's answer spec (a dict) or None."""
        with open(self.file, encoding="utf-8") as fh:
            routes = json.load(fh)
        for k, v in routes.items():
            if k.startswith("_") or " " not in k:
                continue
            m, pat = k.split(" ", 1)
            if m.upper() == method and fnmatch.fnmatchcase(path, pat):
                return {"status": 200, "file": v} if isinstance(v, str) else v
        return None

    def answer(self, spec):
        return spec.get("status", 200), json.dumps(self._load(spec["file"])).encode()

    def merge(self, spec, data):
        try:
            out = json.loads(data)
        except ValueError:
            return data
        if isinstance(out, dict):
            for k, f in spec["merge"].items():
                out[k] = self._load(f) if isinstance(f, str) else f
        return json.dumps(out).encode()


def make_handler(web, api, fx, rf=None):
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

        def _send(self, code, body, ctype, cache="no-store"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Security-Policy", CSP)
            self.send_header("Cache-Control", cache)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _proxy(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n) if n else None
            spec = rf.find(self.command, self.path.split("?")[0]) if rf else None
            if spec and "file" in spec:
                code, data = rf.answer(spec)
                return self._send(code, data, "application/json")
            req = urllib.request.Request(api + self.path, data=body, method=self.command,
                                         headers={"Content-Type": self.headers.get("Content-Type", "application/json")})
            try:
                with urllib.request.urlopen(req, timeout=900) as r:
                    code, data, ctype = r.status, r.read(), r.headers.get("Content-Type", "application/json")
            except urllib.error.HTTPError as e:
                return self._send(e.code, e.read(), e.headers.get("Content-Type", "application/json"))
            except Exception as e:  # API down
                return self._send(502, ('{"error":"proxy","message":"%s"}' % str(e).replace('"', "'")).encode(), "application/json")
            path = self.path.split("?")[0]
            if fx and code == 200 and PLAN_PATH.match(path) and (self.command == "POST") == (path == "/api/plan"):
                try:
                    data = json.dumps(fx.augment(json.loads(data))).encode()
                except ValueError:
                    pass
            if spec and "merge" in spec and code == 200:
                data = rf.merge(spec, data)
            self._send(code, data, ctype)

        def _art(self, art_id):
            data = fx.art_bytes(art_id)
            if data is None:
                return self._proxy()  # the session's own drawing (an import, a board file's fallback)
            self._send(200, data, "application/json; charset=utf-8", IMMUTABLE)

        def _any(self):
            path = self.path.split("?")[0]
            m = ART_ID.match(path)
            if fx and m and self.command == "GET":
                return self._art(m.group(1))
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
    ap.add_argument("--fixtures", help="board drawings and board-map.json (e.g. src/test/web/fixtures)")
    ap.add_argument("--route-fixtures", help="canned answers per route (e.g. src/test/web/fixtures/padprofile)")
    ap.add_argument("--routes", default="routes.json", help="the routes file in --route-fixtures (default routes.json)")
    a = ap.parse_args()
    fx = Fixtures(a.fixtures) if a.fixtures else None
    rf = RouteFixtures(a.route_fixtures, a.routes) if a.route_fixtures else None
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(os.path.abspath(a.web), a.api.rstrip("/"), fx, rf))
    print("devproxy http://127.0.0.1:%d/ -> %s (web %s%s%s)" % (a.port, a.api, os.path.abspath(a.web),
                                                                ", fixtures %s: %d drawings, %d boards" % (fx.root, len(fx.arts), len(fx.map)) if fx else "",
                                                                ", routes %s" % rf.file if rf else ""),
          flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
