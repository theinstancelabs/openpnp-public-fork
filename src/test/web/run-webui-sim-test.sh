#!/usr/bin/env bash
# Browser test of the web UI against an OpenPnP *simulator* (never the real machine):
#   1. The live config dir is copied into $OUT_DIR/stage (only read; nothing is written there). The copy lists only
#      the test board, and gets the SIM scene (docs/instancelabs/AUTO_REGISTER_DESIGN.md §9.2, §9.5): the board
#      truly sits 0.60 / -0.45 mm and +0.20 deg off where the test plans it (x 120, y 120, z 10, rot 0), scaled
#      1.002 / 0.997 with a 0.1 deg shear, on a plane z0 10 with slopes 0.0005; the blank under the purge row at z 10,
#      10 mm beyond the four blank surface points the test types (60..220 x 30..60), so the camera look before a
#      Z-check touch sees bare blank all round each of them (on the blank's edge it sees the bed: not bare).
#   2. openpnp-uinav launches this branch's jar in sim mode on a COPY of the stage: the driver rewired to OpenPnP's
#      simulated G-code server (with the SIM vacuum sensor), the SIM top camera drawing the board at its true seat,
#      its own Xvfb display, device stubs first on the classpath (no /dev/tty*, no /dev/video*).
#   3. The copy gets the plan §4.4 DISABLE_COMMAND and visual homing off (the simulated camera has no homing
#      fiducial). The web API listens on $IL_API_PORT (env var read by ApiSettings).
#   4. Playwright (headless Chromium) runs webui-sim.playwright.cjs with SIM_SCENE = the session's scene file (the
#      test rewrites it, e.g. FID2 hidden: the session reloads it on change), then board-import.playwright.cjs in the
#      same session (BOARD_IMPORT_DESIGN.md §6.6 b: upload, board drawing, preview, inspector; nothing moves), then
#      pad-profile.playwright.cjs (PROFILE_CALIBRATION_DESIGN.md §14.4: missing paste profiles and a pad calibration
#      end to end with the SIM paste and balance; it moves the SIM; screenshots to PP_SHOTS_DIR, PP_SHOTS=0 skips).
#
# Needs: target/openpnp-gui-*.jar built (mvn -q -T1 -DskipTests package), openpnp-uinav built,
#        Playwright installed in $PLAYWRIGHT_DIR (npm i playwright; npx playwright install chromium).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
UINAV="${UINAV:-/home/lumen/instancelabs/openpnp-uinav/bin/uinav}"
SESSION="${UINAV_SESSION:-webui-test}"
JAR="${OPENPNP_JAR:-$REPO/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar}"
CONFIG="${OPENPNP_CONFIG:-$HOME/.openpnp2}"       # only read: copied into the stage
BOARD="${IL_TEST_BOARD:-$HOME/Desktop/ftp.board.xml}"
export IL_API_PORT="${IL_API_PORT:-18765}"
export PLAYWRIGHT_DIR="${PLAYWRIGHT_DIR:-/tmp/il-webui-pw}"
export OUT_DIR="${OUT_DIR:-/tmp/il-webui-test-out}"

[ -f "$JAR" ] || { echo "build the jar first: $JAR" >&2; exit 2; }
[ -f "$BOARD" ] || { echo "test board not found: $BOARD" >&2; exit 2; }
if ss -ltn | grep -q ":$IL_API_PORT "; then echo "port $IL_API_PORT is in use" >&2; exit 2; fi

cleanup() { "$UINAV" --session "$SESSION" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

# 1. the stage: a copy of the config with only the test board listed, and the SIM scene
STAGE="$OUT_DIR/stage"
mkdir -p "$OUT_DIR"
rm -rf "$STAGE"
mkdir -p "$STAGE"
( cd "$CONFIG" && tar --exclude=./backups --exclude=./log -cf - . ) | ( cd "$STAGE" && tar -xf - )
printf '<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n' "$BOARD" > "$STAGE/boards.xml"
python3 -I "$REPO/src/test/sim/il_sim_scene.py" write --config "$STAGE" --board "$BOARD" \
  --x 120 --y 120 --rot 0 --z 10 --dx 0.60 --dy -0.45 --drot 0.20 --sx 1.002 --sy 0.997 --shear 0.1 \
  --slope-x 0.0005 --slope-y 0.0005 --blank 50,20,230,70 --blank-z 10 --blank-slope-x 0 --fast

# 2. the session (the SIM top camera: AUTO_REGISTER_DESIGN.md §9.4 "Installation")
LAUNCH="$(OPENPNP_IL_API_PORT="$IL_API_PORT" "$UINAV" --session "$SESSION" launch --mode sim \
  --config "$STAGE" --openpnp-jar "$JAR" \
  --driver-command 'DISABLE_COMMAND=M410\nM107 P3\nM107 P2\nM107 P1\nM107 P0\nM18 B' \
  --xml-set ".//head@visual-homing-method=None" \
  --xml-set ".//camera[@name='Top']@class=org.openpnp.instancelabs.sim.SimBoardCamera" \
  --xml-set ".//camera[@name='Top']@width=1920" \
  --xml-set ".//camera[@looking='Up']@class=org.openpnp.instancelabs.sim.SimTipCamera" \
  --xml-set ".//camera[@name='Top']@height=1080")"
SESSION_CONFIG="$(printf '%s' "$LAUNCH" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["config_dir"])')"

for _ in $(seq 1 120); do
  curl -sf "http://127.0.0.1:$IL_API_PORT/api/status" >/dev/null && break
  sleep 0.5
done
curl -sf "http://127.0.0.1:$IL_API_PORT/api/status" >/dev/null || { echo "web API did not come up" >&2; exit 1; }

export IL_API_URL="http://127.0.0.1:$IL_API_PORT/"
export UINAV UINAV_SESSION="$SESSION"
export SIM_SCENE="$SESSION_CONFIG/instancelabs/sim-scene.json"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-/tmp/il-webui-pw-browsers}"
# The scripts in their order. WEBUI_SCRIPTS="pad-profile tip-setups" runs only the ones named (in this order, whatever
# the order given): CI runs them as parallel jobs, each in a fresh session, and each passes alone with the same counts as
# in the full run (Infra's ask, 10-31). A script that needs another's state makes it itself.
# tip-flow: the Tip flow of the agreed mockups (docs/ui-flows/tip-setup-flow.md), after tip-setups, on the tips it set up
# reset-home-pressure: the home after a controller reset, the start angle measured again, Pressure on's park (BR-02,
# BR-03: §7 of docs/backend-requests.md; the new status fields stubbed, the writes caught: nothing moves)
# lock and lock-real: who has the machine and Take control (P0, BR-72), the stubbed states, then the engine's own lock
# (lock presses STOP on the SIM at its end)
ALL_SCRIPTS="webui-sim board-import pad-profile tip-setups tip-flow reset-home-pressure lock lock-real"
WEBUI_SCRIPTS="${WEBUI_SCRIPTS:-$ALL_SCRIPTS}"
for name in $WEBUI_SCRIPTS; do
  case " $ALL_SCRIPTS " in *" $name "*) ;; *) echo "WEBUI_SCRIPTS: unknown script '$name' (known: $ALL_SCRIPTS)" >&2; exit 2 ;; esac
done
run_script() {
  case "$1" in
    # missing paste profiles and the pad calibration (PROFILE_CALIBRATION_DESIGN.md §14.4): moves the SIM, ≈ 6 min
    pad-profile) SHOTS_DIR="${PP_SHOTS_DIR:-$OUT_DIR/pad-profile-shots}" node "$HERE/pad-profile.playwright.cjs" ;;
    tip-setups) SHOTS_DIR="${TS_SHOTS_DIR:-$OUT_DIR/tip-setups-shots}" node "$HERE/tip-setups.playwright.cjs" ;;
    *) node "$HERE/$1.playwright.cjs" ;;
  esac
}
for name in $ALL_SCRIPTS; do
  case " $WEBUI_SCRIPTS " in *" $name "*) run_script "$name" ;; esac
done
