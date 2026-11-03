#!/usr/bin/env bash
# Browser test of the board render (boardart.js, boardview.js, pathpreview.js, padinspect.js, board.css) and its glue
# (boardflow.js) against an OpenPnP *simulator* (never the real machine) (BOARD_IMPORT_DESIGN.md §6.6 a):
#   1. src/test/sim/il_ui_session.py starts a seeded SIM session from the current jar (openpnp-uinav: a COPY of the
#      config dir, the simulated controller and cameras, its own Xvfb display), uploads ftp.kicad_pcb through the web
#      API and plans that import: the server makes its drawing and the plan's board block.
#   2. devproxy.py serves the web files from disk and, with --fixtures, answers GET /api/board-art/<id> for the drawings
#      in src/test/web/fixtures (mobo for the performance gate; any other id goes to the session) and adds the dot size
#      while the session has none.
#   3. Playwright (headless Chromium) runs boardview.playwright.cjs through the proxy.
#
# Needs: target/openpnp-gui-*.jar built (mvn -q -T1 -Dmaven.test.skip=true package), openpnp-uinav built,
#        Playwright installed in $PLAYWRIGHT_DIR (npm i playwright; npx playwright install chromium).
# Env: OUT_DIR (results.json, default /tmp/il-boardview-test-out), SHOTS_DIR (screenshots, default OUT_DIR),
#      BOARDVIEW_SESSION / BOARDVIEW_PREFIX (the uinav session is PREFIX + SESSION, default il-ui-boardview-<time>),
#      IL_API_PORT (the session's web API on the first free port of N … N+8; default any free port).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
JAR="${OPENPNP_JAR:-$REPO/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar}"
NAME="${BOARDVIEW_SESSION:-boardview-$(date +%Y%m%d-%H%M%S)}"
PREFIX="${BOARDVIEW_PREFIX:-il-ui-}"
PORT_ARGS=()
[ -n "${IL_API_PORT:-}" ] && PORT_ARGS=(--api-port "$IL_API_PORT")
export PLAYWRIGHT_DIR="${PLAYWRIGHT_DIR:-/tmp/il-webui-pw}"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/tmp/il-webui-pw-browsers}"
export OUT_DIR="${OUT_DIR:-/tmp/il-boardview-test-out}"
export SHOTS_DIR="${SHOTS_DIR:-$OUT_DIR}"
mkdir -p "$OUT_DIR" "$SHOTS_DIR"

[ -f "$JAR" ] || { echo "build the jar first: $JAR" >&2; exit 2; }

PROXY_PID=""
cleanup() {
  [ -n "$PROXY_PID" ] && kill "$PROXY_PID" >/dev/null 2>&1 || true
  python3 "$REPO/src/test/sim/il_ui_session.py" stop --name "$NAME" --prefix "$PREFIX" >/dev/null 2>&1 || true
}
trap cleanup EXIT

INFO="$(python3 "$REPO/src/test/sim/il_ui_session.py" start --jar "$JAR" --name "$NAME" --prefix "$PREFIX" "${PORT_ARGS[@]}" \
  --kicad "$REPO/src/test/resources/kicad/ftp.kicad_pcb.gz" 2>"$OUT_DIR/session.log" | tail -n 1)"
API_PORT="$(printf '%s' "$INFO" | python3 -c 'import json, sys; print(json.load(sys.stdin)["port"])')"
PROXY_PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
python3 "$HERE/devproxy.py" --api "http://127.0.0.1:$API_PORT" --port "$PROXY_PORT" --fixtures "$HERE/fixtures" \
  >"$OUT_DIR/devproxy.log" 2>&1 &
PROXY_PID=$!
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PROXY_PORT/api/status" >/dev/null && break
  sleep 0.25
done
curl -sf "http://127.0.0.1:$PROXY_PORT/api/status" >/dev/null || { echo "devproxy did not come up" >&2; exit 1; }

export IL_BASE="http://127.0.0.1:$PROXY_PORT/"
export IL_FIXTURES="$HERE/fixtures"
node "$HERE/boardview.playwright.cjs"
