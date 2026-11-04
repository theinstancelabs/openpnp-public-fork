/*
 * Tip setups on a SIM session (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §22.8, Playwright): the 22G bent taper
 * on the dispenser, a 25G straight added from Change tip with a picture and mounted, what goes stale and what the
 * screens say for it (the strip, Needle, Profile per tip, By tip, Needs a profile), its needle frame, where it lands,
 * its dots measured, a pad type calibrated for it with its "calibrated for" label and pictures, Records, the 22G
 * mounted back, a wrong tip on the head (the landing question, the control, Made with another tip…), the picture viewer
 * (remove and undo; STOP stays live), no JavaScript errors and no native dialogs. Run by run-webui-sim-test.sh after the
 * other browser tests in the same session (launched with the SIM bottom camera, TIP_CAMERA_XML_SET); it stops at once on
 * a server that is not a simulator. The machine moves (it is the SIM's): every motion is the engine's, started with the
 * UI's two presses.
 *
 * Steps (§22.8): (0) set-up as pad-profile.playwright.cjs (0), the 25G's own SIM paste (--paste-by-tip: smaller dots,
 * lands 2.4, -0.4 mm off); (1) Setup › Dispenser & tip of the 22G, the strip on one line at 1024 and 1280 px; (2) Add a
 * tip from Change tip: the 25G straight preset, a dropped picture, Add and mount, the Undo band; (3) the needle item,
 * the strip, Profile for the 25G (stand-ins, By tip, the stale plan, the editor's eyebrow); (4) the padcal board planned:
 * Needs a profile for the 25G, Measure its dots first; (5) its needle frame (K not known), the ticks again, Check where it
 * lands: saved; (6) Blank wiped, Measure its dots (readings at the SIM balance), the dot table, the upload locked while it
 * ran; (7) the SW group calibrated for the 25G: its label edited, the result photo, a picture from below and an upload;
 * (8) Records › Calibrations; (9) the 22G mounted back: its versions and K; (10) the 25G on the head with the 22G
 * mounted: the landing question, a calibration's control, Made with another tip…, the run confirm's tip tick; (11) the
 * picture viewer; (12) no JavaScript errors, no native dialogs. Screenshots (TS_SHOTS=0 skips) of every state at
 * 1024 × 768 and 1920 × 1080, light and dark, in SHOTS_DIR.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (tip-setups-results.json), SHOTS_DIR (default
 * OUT_DIR/tip-setups-shots), TS_SHOT_SIZES (default 1024x768,1920x1080), SIM_SCENE (the session's scene file: its
 * config dir is amended).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));
const { setStartAngle } = require(path.join(__dirname, 'start-angle.cjs'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-tip-setups-test-out';
const SHOTS = process.env.SHOTS_DIR || path.join(OUT, 'tip-setups-shots');
const SCENE = process.env.SIM_SCENE;
const REPO = path.join(__dirname, '..', '..', '..');
const FIX = path.join(__dirname, 'fixtures', 'tips');
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shots = [];
const SIZES = String(process.env.TS_SHOT_SIZES || '1024x768,1920x1080').split(',').map((x) => x.split('x').map(Number));
/**
 * The same moment at each size, light and dark (prep re-runs after a resize). panel: below 1100 px the context panel
 * sits under the main column: the shot starts at the panel instead.
 */
async function shoot(page, name, prep, panel) {
  if (process.env.TS_SHOTS === '0') return;
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => IL.setTheme(t), theme);
    for (const [w, h] of SIZES) {
      await page.setViewportSize({ width: w, height: h });
      await sleep(400);
      if (panel && w < 1100) await page.evaluate(() => window.scrollBy(0, document.getElementById('panel').getBoundingClientRect().top - document.getElementById('strip').getBoundingClientRect().bottom));
      else if (prep) await prep(w);
      else if (panel) await page.evaluate(() => window.scrollTo(0, 0));   // the panel beside the main column: the page from its top
      await sleep(300);
      await page.evaluate(() => { const t = document.getElementById('toast'); if (t) t.hidden = true; for (const n of document.querySelectorAll('.flash')) n.classList.remove('flash'); });
      const f = path.join(SHOTS, name + '-' + w + '-' + theme + '.png');
      await page.screenshot({ path: f });
      shots.push(f);
    }
  }
  await page.evaluate(() => IL.setTheme('light'));
  await page.setViewportSize({ width: 1440, height: 900 });
  await sleep(250);
}
/** Scrolls the element's scroll container (main or the panel) so that it starts just under the strip. */
const toTop = (page, sel) => page.evaluate((x) => {
  const n = document.querySelector(x);
  if (!n) return;
  let sc = n.parentElement;
  while (sc && sc !== document.body && !(sc.scrollHeight > sc.clientHeight + 1 && /(auto|scroll)/.test(getComputedStyle(sc).overflowY))) sc = sc.parentElement;
  const win = !sc || sc === document.body;
  const area = document.getElementById('msg-area');
  const top = win ? Math.max(document.getElementById('strip').getBoundingClientRect().bottom, area && area.offsetHeight ? area.getBoundingClientRect().bottom : 0) : sc.getBoundingClientRect().top;
  const dy = n.getBoundingClientRect().top - top - 12;
  if (win) window.scrollBy(0, dy); else sc.scrollTop += dy;
}, sel);
const api = (page, method, p, body) => page.evaluate(([m, x, b]) => IL.api(m, x, b === null ? undefined : b, { timeoutMs: 120000 }), [method, p, body === undefined ? null : body]);
const apiRaw = (page, method, p, body) => page.evaluate(async ([m, x, b]) => {
  try { return { ok: true, data: await IL.api(m, x, b === null ? undefined : b, { timeoutMs: 60000 }) }; } catch (e) { return { ok: false, status: e.status, code: e.code, message: e.message }; }
}, [method, p, body === undefined ? null : body]);
const status = (page) => page.evaluate(() => IL.api('GET', '/api/status'));
/** The two presses of a two-press button (§11): the second ≥ 300 ms after the first, once it asks for it. */
async function press2(page, sel) {
  await page.waitForSelector(sel + ':enabled', { timeout: 30000 });
  for (let i = 0; i < 2; i++) {
    await page.click(sel);
    if (await page.waitForSelector(sel + '.btn--press', { timeout: 2000 }).then(() => true, () => false)) break;
    if (i === 1) throw new Error(sel + ' did not ask for a second press');
  }
  await sleep(450);
  await page.click(sel);
}
/** A file dropped on a drop zone, as the operator drags it there from the desktop. */
async function drop(page, sel, file, type) {
  const b64 = fs.readFileSync(path.join(FIX, file)).toString('base64');
  await page.evaluate(([s, b, n, t]) => {
    const bytes = Uint8Array.from(atob(b), (c) => c.charCodeAt(0));
    const dt = new DataTransfer();
    dt.items.add(new File([bytes], n, { type: t }));
    const z = document.querySelector(s);
    for (const ev of ['dragenter', 'dragover', 'drop']) z.dispatchEvent(new DragEvent(ev, { dataTransfer: dt, bubbles: true, cancelable: true }));
  }, [sel, b64, file, type]);
}
async function closePanels(page) {
  await page.evaluate(() => { while (IL.panel.isPushed()) IL.panel.close(); const d = IL.dialog.top(); if (d) IL.dialog.closeAll(); });
}
const job = (page) => page.evaluate(() => IL.S.status && IL.S.status.padCalibration);
const ACTIVE = ['running', 'deciding', 'planning', 'awaiting-reading'];
async function waitJob(page, pred, timeoutMs, what) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    last = await job(page);
    if (last && pred(last)) return last;
    await sleep(700);
  }
  throw new Error('timed out waiting for ' + what + ': ' + JSON.stringify(last && { state: last.state, steps: last.steps }).slice(0, 600));
}
/** The operator at the SIM balance: weigh the coupon, wipe it, weigh it again (grams as the display shows them). */
async function weighCoupon(page, place) {
  await api(page, 'POST', '/api/sim/balance/load', { place });
  await sleep(400);
  const full = await api(page, 'GET', '/api/sim/balance');
  await api(page, 'POST', '/api/sim/paste/wipe', { region: place });
  await api(page, 'POST', '/api/sim/balance/load', { place });
  await sleep(400);
  const empty = await api(page, 'GET', '/api/sim/balance');
  return { full: parseFloat(full.display), empty: parseFloat(empty.display) };
}
/** Started from the pad-cal view (two presses): waits for the readings, types them, waits for the end. */
async function runCal(page, mode, maxMs) {
  const before = await job(page);
  await press2(page, '#btn-pcal-start');
  const j0 = await waitJob(page, (j) => (!before || j.id !== before.id) && (mode === 'calibrate' ? !j.measure : j.measure === mode), 30000, 'the job to start');
  let j = await waitJob(page, (j) => j.id === j0.id && (j.state === 'awaiting-reading' || !ACTIVE.includes(j.state)), maxMs, mode + ': readings or end');
  if (j.state === 'awaiting-reading') {
    const cs = j.awaiting.coupons || [];
    for (let i = 0; i < cs.length; i++) {
      const w = await weighCoupon(page, cs[i].place);
      await page.fill('#pcal-full-' + (i + 1), String(w.full));
      await page.fill('#pcal-empty-' + (i + 1), String(w.empty));
    }
    await page.click('#btn-pcal-readings');
    j = await waitJob(page, (x) => x.id === j0.id && !ACTIVE.includes(x.state), 180000, mode + ': the end');
  }
  await sleep(1500);
  return j;
}
/**
 * Blank wiped: the SIM paste on the blank wiped off (the operator's lint-free wipe), then the calibration area and the
 * purge row on it free again (every job purges on the row).
 */
async function wipeBlank(page) {
  await api(page, 'POST', '/api/sim/paste/wipe', { region: 'blank' }).catch(() => null);
  await api(page, 'POST', '/api/calibration-area/reset', {});
  await api(page, 'POST', '/api/purge-row/reset', {});
}
const ticks = (page) => page.evaluate(() => { IL.attest.set('regulator', IL.S.status.dispense.profile.pressurePsi); IL.attest.set('prime-seen', true); IL.attest.set('paste-life', true); });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ extraHTTPHeaders: { 'X-IL-Client': 'ui' }, viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) jsErrors.push(m.text()); });
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  const t0 = Date.now();
  try {
    const resp = await page.goto(BASE + '#setup/tip');
    check('UI served', resp.ok(), resp.status());
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.tips && IL.photos && IL.tips.data(), null, { timeout: 60000 });
    const sim = await page.evaluate(() => !!(IL.S.status.server && IL.S.status.server.simulated === true));
    check('the server is an OpenPnP simulator (status.server.simulated)', sim);
    if (!sim) throw new Error('not a simulator: nothing was done');
    await page.evaluate(() => IL.setTheme('light'));
    await closePanels(page);

    // ---- (0) set-up ---------------------------------------------------------------------------------------------------
    let s = await status(page);
    if (!s.machine.enabled) { await api(page, 'POST', '/api/machine/enable'); await page.waitForFunction(() => IL.S.status.machine.enabled, null, { timeout: 30000 }); }
    if (!s.machine.homed) await api(page, 'POST', '/api/machine/home', { wait: true });
    await api(page, 'POST', '/api/machine/z-check', { wait: true }).catch(() => null);   // an engine task: the simulator confirms itself
    const cfgDir = SCENE ? path.dirname(path.dirname(SCENE)) : null;
    const scene = (...args) => { if (cfgDir) execFileSync('python3', ['-I', path.join(REPO, 'src', 'test', 'sim', 'il_sim_scene.py'), 'amend', '--config', cfgDir, ...args], { stdio: 'pipe' }); };
    // the padcal scene (pad-profile's), and the 25G's own SIM paste: smaller dots, and it lands 2.4, -0.4 mm off (§22.8)
    scene('--paste', '--coupons', '--balance', 'display', '--bench-blank', '--paste-by-tip', '25g-straight:mRevMg=1.6,c=0.6,landing=2.4:-0.4', '--physical-tip', 'mounted');
    let sc = SCENE && fs.existsSync(SCENE) ? JSON.parse(fs.readFileSync(SCENE, 'utf8')) : {};
    const blank = sc.blank || { rect: [280, 225, 365, 250], surface: { z0: 5.62, slopeX: -0.0012, slopeY: 0 } };
    // the landing check's band is 20 mm tall (±6 mm search): a blank of at least 40 mm, grown downward when smaller
    if (SCENE && blank.rect[3] - blank.rect[1] < 40) { blank.rect[1] = blank.rect[3] - 50; sc.blank = blank; fs.writeFileSync(SCENE, JSON.stringify(sc, null, 2)); }
    const [bx0, by0, bx1, by1] = blank.rect;
    const bz = (x, y) => blank.surface.z0 + (blank.surface.slopeX || 0) * (x - bx0) + (blank.surface.slopeY || 0) * (y - by0);
    if (SCENE && (sc.coupons || []).some((c) => Math.abs(c.z - blank.surface.z0) > 0.3)) {
      for (const c of sc.coupons) c.z = Number(blank.surface.z0.toFixed(2));
      fs.writeFileSync(SCENE, JSON.stringify(sc, null, 2));
    }
    const spots = Math.min(60, Math.floor((bx1 - bx0 - 8) / 1.6));
    const area = { x0Mm: bx0 + 5, y0Mm: by0 + 10, widthMm: Math.min(40, bx1 - bx0 - 10), heightMm: Math.min(24, by1 - by0 - 14) };
    await api(page, 'PUT', '/api/config', { purgeRow: { surfaceName: 'blank', x0Mm: bx0 + 5, y0Mm: by0 + 4, pitchXMm: 1.6, pitchYMm: 0, spots },
      // pad-profile's short SIM ladder, with 6 dots per screw angle: the half turn's block of Measure its dots then has the
      // 10 moves the thread count needs (2 angles × 3 dots give only 5)
      padCalibration: { ladderDotsPerPhase: 6, minLadderDots: 6, minHops: 10, weighTargetMg: 15 } });
    await api(page, 'POST', '/api/purge-row/reset', {});
    await api(page, 'PUT', '/api/locations/blank', { x: area.x0Mm, y: area.y0Mm, z: bz(area.x0Mm, area.y0Mm) });
    const corners = [[bx0 + 3, by0 + 3], [bx1 - 3, by0 + 3], [bx1 - 3, by1 - 3], [bx0 + 3, by1 - 3]];
    await api(page, 'PUT', '/api/surfaces/blank', { points: corners.map(([x, y]) => [x, y, Number(bz(x, y).toFixed(3))]), note: 'tip-setups test: the SIM blank' });
    await api(page, 'PUT', '/api/calibration-area', area);
    await wipeBlank(page);
    // the coupons wiped and laid back: a test before this one may leave dots on a coupon whose weighing it skipped
    await api(page, 'POST', '/api/sim/paste/wipe', { region: 'coupon' }).catch(() => null);
    for (const c of sc.coupons || [{ place: 'coupon1', rect: [330, 40, 370, 70], z: 5.8 }, { place: 'coupon2', rect: [330, 75, 370, 105], z: 5.8 }]) {
      await api(page, 'PUT', '/api/locations/' + c.place, { x: (c.rect[0] + c.rect[2]) / 2, y: (c.rect[1] + c.rect[3]) / 2, z: c.z });
    }
    // the 22G bent taper on the dispenser (an earlier run of this test may have left another tip mounted)
    s = await status(page);
    if (s.dispense.tip.key !== 'ad22tta-b') await api(page, 'POST', '/api/tip-setups/ad22tta-b/mount', { why: 'tip-setups test: start with the 22G' });
    await page.waitForFunction(() => IL.S.status.dispense.tip.key === 'ad22tta-b', null, { timeout: 15000 });
    const bzMid = Number(bz((bx0 + bx1) / 2, (by0 + by1) / 2).toFixed(3));
    s = await status(page);
    let nd = s.dispense.needle;
    const tok = (n) => (n.changedAtMs === undefined ? null : n.changedAtMs);
    if (!nd.anchored || !nd.anchor || Math.abs(nd.anchor.surfaceZMm - bzMid) > 0.5 || !nd.touchedOff) {
      // both tips on the blank, the needle 2.59 mm under N1 as the SIM's see-saw has it (K is refused after a failed Z
      // check of this home, which webui-sim's last step leaves, and after a needle change)
      await api(page, 'POST', '/api/needle/anchor', { surfaceZ: bzMid, needleContactZ: Number((bzMid - 2.59).toFixed(3)), note: 'tip-setups test: both tips (SIM)', needleChangedAtMs: tok(nd) });
      await api(page, 'POST', '/api/needle/offset', { offsetMm: -2.59, note: 'tip-setups test (SIM)', needleChangedAtMs: tok(nd) });
    }
    // the 22G bent taper's own K, which (9) finds again after the mount back: its frame recorded from K over the one it
    // has (a home first when the Z check of this home failed: the heights were measured again at the new Z)
    nd = (await status(page)).dispense.needle;
    if (nd.anchor && typeof nd.anchor.rawSumMm !== 'number' && /under another home/.test(nd.kRefusal || '')) {
      await api(page, 'POST', '/api/machine/home', { wait: true });
      await api(page, 'POST', '/api/machine/z-check', { wait: true }).catch(() => null);
      nd = (await status(page)).dispense.needle;
    }
    if (nd.anchor && typeof nd.anchor.rawSumMm !== 'number' && !nd.kRefusal) {
      await api(page, 'POST', '/api/needle/anchor', { surfaceZ: bzMid, needleContactRawSum: 65.59, note: 'tip-setups test: the 22G bent taper\'s K (SIM)', needleChangedAtMs: tok(nd) });
      await api(page, 'POST', '/api/needle/offset', { offsetMm: -2.59, note: 'tip-setups test (SIM)', needleChangedAtMs: tok(nd) });
    }
    // BR-02: after webui-sim's STOPs (M18 B) the angle is measured again, not confirmed: setStartAngle turns the screw first
    await setStartAngle(page, BASE, 'tip-setups test (SIM)');
    await ticks(page);
    // the 22G mounted again by this set-up (an earlier run): where it lands, checked again (its bend: no Keep)
    await page.waitForFunction(() => IL.S.status.dispense.needle.anchored && IL.S.status.dispense.needle.touchedOff, null, { timeout: 15000 });
    if (!(await status(page)).dispense.needle.xyChecked) {
      await page.evaluate(() => IL.padProfile.openCal({ measure: 'landing' }));
      await page.waitForFunction(() => { const b = document.getElementById('btn-pcal-start'); return !!b && /^Check where it lands/.test(b.textContent) && !b.disabled; }, null, { timeout: 30000 });
      await runCal(page, 'landing', 240000);
      // the 22G's landing of an earlier run's wrong tip: it is the 22G on the head now
      if (await page.isVisible('#btn-pcal-landing-save').catch(() => false)) {
        await page.click('#btn-pcal-landing-save');
        await page.waitForFunction(() => IL.S.status.dispense.needle.xyChecked, null, { timeout: 15000 });
      }
      await closePanels(page);
      await wipeBlank(page);
    }
    const anchor22 = (await status(page)).dispense.needle.anchor;
    const k22 = anchor22 && typeof anchor22.rawSumMm === 'number' ? anchor22.rawSumMm : null;
    const chip0 = await api(page, 'GET', '/api/pad-types/chip-0603');
    if (!['characterized', 'validated'].includes(chip0.current.status)) await api(page, 'PUT', '/api/pad-types/chip-0603', { status: 'characterized', forSetup: 'ad22tta-b' });
    const chipBefore = (await api(page, 'GET', '/api/pad-types/chip-0603')).current;
    await ticks(page);
    let boards = await api(page, 'GET', '/api/boards');
    let padcal = (boards.files || []).find((f) => /padcal/i.test(f.file || '') || /padcal/i.test(f.name || ''));
    if (!padcal) {
      // run alone (WEBUI_SCRIPTS="tip-setups", a fresh session): pad-profile's board, saved here through the same import
      const buf = fs.readFileSync(path.join(REPO, 'src', 'test', 'resources', 'padprofile', 'padcal.kicad_pcb'));
      const hdr = { 'Content-Type': 'application/octet-stream', 'X-IL-Client': 'ui' };
      const pv = await (await page.request.post(BASE + 'api/boards/import?file=padcal.kicad_pcb', { data: buf, headers: hdr, timeout: 60000 })).json();
      const q = 'file=padcal.kicad_pcb&name=' + encodeURIComponent('padcal tip-setups') + '&replace=1'
        + (pv.file && pv.file.sha256 ? '&sha256=' + encodeURIComponent(pv.file.sha256) : '') + (pv.decisions ? '&decisions=' + encodeURIComponent(pv.decisions) : '');
      const sv = await page.request.post(BASE + 'api/boards/import/save?' + q, { data: buf, headers: hdr, timeout: 60000 });
      if (!sv.ok()) throw new Error('the padcal board could not be saved: ' + sv.status() + ' ' + (await sv.text()).slice(0, 300));
      boards = await api(page, 'GET', '/api/boards');
      padcal = (boards.files || []).find((f) => /padcal/i.test(f.file || '') || /padcal/i.test(f.name || ''));
    }
    const planPadcal = async () => page.evaluate((p) => IL.setPlan(p), await api(page, 'POST', '/api/plan', { source: 'file', file: padcal.file, x: 200, y: 120, z: Number(bzMid.toFixed(2)), rotation: 0 }));
    if (padcal) await planPadcal();
    // a 25G straight from an earlier run: the dialog would refuse its name; it is used as it is
    const lib0 = await api(page, 'GET', '/api/tip-setups?retired=true');
    const old25 = (lib0.setups || []).find((x) => x.name === '25G straight' && !x.retired);
    check('(0) set-up: places, blank, calibration area ' + IL_fmt(area.heightMm) + ' mm tall, the 22G mounted with its frame', true);

    // ---- (1) Setup › Dispenser & tip --------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#setup/tip'));
    await page.waitForFunction(() => document.getElementById('tip-name') && document.getElementById('tip-name').textContent === IL.S.status.dispense.tip.name, null, { timeout: 15000 });
    const c1 = await page.evaluate(() => ({ name: document.getElementById('tip-name').textContent, kv: document.getElementById('tip-kv').textContent,
      none: !document.getElementById('tip-photo-none').hidden, photo: !document.getElementById('tip-photo').hidden,
      chip: document.querySelector('#chip-tip .cell__v').textContent, chipKind: document.getElementById('chip-tip').className }));
    check('(1) #tip-name is the 22G bent taper (the seed\'s name, read from the API)', c1.name === '22G bent taper', c1);
    check('(1) #tip-kv: Vieweg DV-5005 and "from the bench tests"', /Vieweg DV-5005/.test(c1.kv) && /from the bench tests/.test(c1.kv), c1.kv);
    const lib1 = await api(page, 'GET', '/api/tip-setups');
    const has22Pic = !!((lib1.setups || []).find((x) => x.key === 'ad22tta-b') || {}).mainPhoto;
    check('(1) #tip-photo-none when the 22G has no picture (else its picture)', has22Pic ? c1.photo : c1.none, c1);
    check('(1) the strip\'s #chip-tip reads 22G bent taper, ok', c1.chip === '22G bent taper' && /cell--ok/.test(c1.chipKind), c1);
    const oneLine = async (w) => {
      await page.setViewportSize({ width: w, height: 768 });
      await sleep(500);
      return page.evaluate(() => {
        const cells = Array.from(document.getElementById('cells').children).filter((c) => !c.hidden && c.getClientRects().length);
        const tops = cells.map((c) => c.getBoundingClientRect().top), bots = cells.map((c) => c.getBoundingClientRect().bottom);
        const tip = document.getElementById('chip-tip').getBoundingClientRect(), stop = document.getElementById('btn-stop').getBoundingClientRect();
        return { spread: Math.max(...tops) - Math.min(...tops), height: Math.max(...bots) - Math.min(...tops), tipRight: tip.right, stopLeft: stop.left };
      });
    };
    for (const w of [1024, 1280]) {
      const l = await oneLine(w);
      check('(1) the strip stays on one line at ' + w + ' px, the Tip cell left of STOP', l.spread < 2 && l.tipRight <= l.stopLeft + 1, l);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await shoot(page, 'setup-tip-22g', () => toTop(page, '#card-tip'));

    // ---- (2) Add a tip from Change tip ------------------------------------------------------------------------------
    await page.click('#btn-tip-change');
    await page.waitForSelector('#tip-list-opts .tip-opt', { timeout: 15000 });
    const c2a = await page.evaluate(() => ({ swap: document.querySelectorAll('#tip-swap li').length, legend: document.querySelector('#tip-list legend').textContent,
      again: (document.querySelector('#tip-list-opts .tip-opt--on .tip-opt__n') || {}).textContent }));
    check('(2) Change tip: the swap in 3 steps, "Which tip is on the dispenser now?", the mounted one "again (a new needle)"',
      c2a.swap === 3 && c2a.legend === 'Which tip is on the dispenser now?' && /^The 22G bent taper again \(a new needle\)/.test(c2a.again || ''), c2a);
    await shoot(page, 'change-tip', null, true);
    let k25 = '25g-straight';
    if (!old25) {
      await page.click('#btn-tip-add2');
      await page.waitForSelector('#dlg-tip-add #ta-preset', { timeout: 10000 });
      await page.selectOption('#ta-preset', '25g-straight');
      const c2 = await page.evaluate(() => ({ name: document.getElementById('ta-name').value, id: document.getElementById('ta-id').value, od: document.getElementById('ta-od').value }));
      check('(2) the 25G straight preset fills the name, ID 0.25 and OD 0.51', c2.name === '25G straight' && Number(c2.id) === 0.25 && Number(c2.od) === 0.51, c2);
      // a 25G straight's smallest reliable dot: the half turn (the SIM's quarter-turn dots of this paste are too small to find)
      await page.selectOption('#ta-mindot', '800');
      await drop(page, '#ta-drop', 'tip-25g-straight.png', 'image/png');
      const img = await page.waitForFunction(() => { const i = document.getElementById('ta-photo-img'); return i && !i.hidden && i.naturalWidth > 0; }, null, { timeout: 8000 }).then(() => true, () => false);
      check('(2) a PNG dropped on the drop zone shows #ta-photo-img', img);
      await shoot(page, 'add-tip-dialog', () => page.evaluate(() => { const b = document.querySelector('#dlg-tip-add .dialog__body'); if (b) b.scrollTop = 0; }));
      await press2(page, '#ta-save-mount');
    } else {
      k25 = old25.key;
      await page.click('#tip-opt-' + k25);
      await press2(page, '#btn-tip-mount');
    }
    await page.waitForFunction((k) => IL.S.status.dispense.tip.key === k, k25, { timeout: 30000 });
    const undo = await page.waitForSelector('#btn-tip-undo', { state: 'visible', timeout: 10000 }).then(() => true, () => false);
    check('(2) mounted: status.dispense.tip.key ' + k25 + ', the Undo band shown', undo, k25);
    // the picture is uploaded once the setup exists (after the mount): wait for it
    let lib2 = null;
    for (let i = 0; i < 30; i++) {
      lib2 = await api(page, 'GET', '/api/tip-setups');
      const x = (lib2.setups || []).find((k) => k.key === k25);
      if (x && (x.photos || []).some((p) => p.kind === 'setup')) break;
      await sleep(500);
    }
    const s25 = (lib2.setups || []).find((x) => x.key === k25);
    const pic = s25 && (s25.photos || []).find((p) => p.kind === 'setup');
    const picOk = pic ? await page.evaluate(async (u) => { const r = await fetch(u); return r.ok && /image\/jpeg/.test(r.headers.get('content-type') || ''); }, pic.url) : false;
    check('(2) GET /api/tip-setups has two, the 25G with a setup picture that loads', (lib2.setups || []).length >= 2 && !!pic && picOk, { n: (lib2.setups || []).length, pic: !!pic, picOk });
    await sleep(800);
    await shoot(page, 'setup-tip-25g-mounted', () => toTop(page, '#card-tip'));

    // ---- (3) what goes stale ----------------------------------------------------------------------------------------
    const c3 = await page.evaluate(() => { const it = IL.ready().items.find((i) => i.key === 'needle'); return { state: it.state, detail: it.detail, chip: document.getElementById('chip-tip').className,
      ticks: [!!IL.attest.valid('regulator', IL.S.status), !!IL.attest.valid('prime-seen', IL.S.status)] }; });
    check('(3) the readiness Needle item is block: "The 25G straight was put on at …"', c3.state === 'block' && /^The 25G straight was put on at \d\d:\d\d/.test(c3.detail), c3);
    check('(3) #chip-tip is block after the change', /cell--block/.test(c3.chip), c3.chip);
    check('(3) the regulator and prime ticks lapsed at the mount', !c3.ticks[0] && !c3.ticks[1], c3.ticks);
    await page.evaluate(() => IL.go('#profile'));
    await page.waitForFunction(() => /for the 25G straight/.test((document.getElementById('pf-h1-sub') || {}).textContent || ''), null, { timeout: 15000 });
    await page.waitForFunction(() => IL.pcr.padTypes() && IL.pcr.padTypes().forSetup && IL.pcr.padTypes().forSetup.name === '25G straight', null, { timeout: 15000 });
    await sleep(600);
    const c3b = await page.evaluate(() => {
      const tr = document.querySelector('#profiles tr[data-key="chip-0603"]');
      return { sub: document.getElementById('pf-h1-sub').textContent, badge: tr ? tr.querySelector('.badge').textContent : null, standin: tr ? (tr.querySelector('.pt-standin') || {}).textContent : null,
        cardSub: document.getElementById('pf-title-tip').textContent };
    });
    check('(3) Profile\'s #pf-h1-sub says "for the 25G straight" with Change tip…', /for the 25G straight/.test(c3b.sub) && /Change tip…/.test(c3b.sub), c3b);
    check('(3) chip-0603 is "not started" for the 25G: "Not calibrated for the 25G straight yet"', c3b.badge === 'not started' && c3b.standin === 'Not calibrated for the 25G straight yet', c3b);
    check('(3) the profile card: the recipe of the 25G straight', /the recipe of the 25G straight/.test(c3b.cardSub), c3b.cardSub);
    await shoot(page, 'profile-25g', () => page.evaluate(() => window.scrollTo(0, 0)));
    await shoot(page, 'profile-25g-standin', () => toTop(page, '#profiles tr[data-key="chip-0603"]'));
    await page.click('#pt-by-tip');
    await page.waitForSelector('.pt-bytip__t', { timeout: 10000 });
    const c3c = await page.evaluate(() => {
      const t = document.querySelector('.pt-bytip__t');
      const heads = Array.from(t.querySelectorAll('thead th[data-setup]')).map((h) => h.dataset.setup);
      const cell = document.querySelector('.pt-bytip__t button.pt-bt[data-setup="ad22tta-b"][data-type="chip-0603"]');
      return { heads, chip22: cell ? cell.querySelector('.badge').textContent : null, first: heads[0] };
    });
    check('(3) By tip: both columns, the mounted one first, chip-0603 characterized under the 22G', c3c.heads.includes('ad22tta-b') && c3c.first === k25 && ['characterized', 'validated'].includes(c3c.chip22), c3c);
    await shoot(page, 'profile-by-tip', () => toTop(page, '.pt-bytip'));
    // the side panel at Other tips (a By tip cell of the 22G)
    await page.click('.pt-bytip__t button.pt-bt[data-setup="ad22tta-b"][data-type="chip-0603"]');
    await page.waitForSelector('#pt-side-tips .pt-tip[data-setup="ad22tta-b"]', { timeout: 10000 });
    const c3d = await page.evaluate(() => ({ tip: document.querySelector('#pt-side-tips .pt-tip[data-setup="ad22tta-b"]').textContent, copy: !!document.querySelector('#pt-side-tips button[data-action=copy-from]') }));
    check('(3) the side panel\'s Other tips: the 22G\'s version with Copy its dots to start from', /22G bent taper/.test(c3d.tip) && c3d.copy, c3d);
    await shoot(page, 'profile-other-tips', null, true);
    await page.click('#pt-filter button[data-filter="all"]');
    // the plan shown was made for the 22G: stale
    const c3e = await page.evaluate(() => { const it = IL.ready().items.find((i) => i.key === 'plan'); return it ? { state: it.state, text: JSON.stringify(it) } : null; });
    check('(3) the plan shown is stale (made with the 22G)', !!c3e && c3e.state !== 'ok', c3e && c3e.state);
    // the editor: for the 25G straight
    await page.evaluate(() => IL.pt.openPadType(IL.pcr.padTypes().families.flatMap((f) => f.types).find((t) => t.typeKey === 'chip-0603')));
    await page.waitForSelector('#dlg-padtype[open]', { timeout: 10000 });
    const c3f = await page.evaluate(() => ({ eyebrow: document.getElementById('pt-eyebrow').textContent, ver: document.getElementById('pt-version').textContent, save: document.getElementById('pt-save').textContent }));
    check('(3) the editor\'s eyebrow: "Pad type · chip-0603 · for the 25G straight", the version note names both tips',
      c3f.eyebrow === 'Pad type · chip-0603 · for the 25G straight' && /for the 25G straight; the 22G bent taper keeps version \d+/.test(c3f.ver) && /^Save version \d+$/.test(c3f.save), c3f);
    await page.selectOption('#pt-status', 'characterized');
    await page.waitForSelector('#pt-calfor:not([hidden])', { timeout: 5000 });
    const c3g = await page.evaluate(() => ({ label: document.getElementById('pt-label').value }));
    check('(3) raising the status shows Calibrated for, the label filled in (type · tip · dispenser · psi)', /^Chip 0603 · 25G straight · Vieweg DV-5005 · \d+(\.\d+)? psi/.test(c3g.label), c3g);
    await shoot(page, 'editor-calibrated-for', () => page.evaluate(() => { const f = document.getElementById('pt-calfor'); if (f) f.scrollIntoView({ block: 'center' }); }));
    await page.click('#pt-cancel');
    await page.waitForSelector('#dlg-padtype', { state: 'hidden', timeout: 5000 }).catch(() => null);

    // ---- (4) the padcal board planned --------------------------------------------------------------------------------
    check('(4) the padcal board is listed (pad-profile\'s upload, or the session\'s)', !!padcal, (boards.files || []).map((f) => f.file));
    await planPadcal();
    await page.evaluate(() => IL.go('#profile/needs'));
    await page.waitForSelector('#pt-needs:not([hidden])', { timeout: 20000 });
    await page.waitForFunction(() => /for the 25G straight/.test(document.getElementById('pt-needs-for').textContent), null, { timeout: 15000 });
    const c4 = await page.evaluate(() => {
      const pc = IL.S.plan.profileCheck || {};
      const all = (pc.otherTips || []).filter((x) => x.covers === x.of && x.of > 0);
      // otherTips counts the typed pads only: the note names the packages without a pad type (no tip change gives them one)
      const untyped = new Set((pc.groups || []).filter((g) => g.reason === 'no-type' || g.reason === 'no-pad-size').flatMap((g) => g.packageIds || [g.group])).size;
      return { for: document.getElementById('pt-needs-for').textContent, measure: !document.getElementById('pt-needs-measure').hidden,
        measureText: document.getElementById('pt-needs-measure').textContent, other: !document.getElementById('pt-needs-other').hidden,
        otherText: document.getElementById('pt-needs-other').textContent, otherTips: all.map((x) => x.name), untyped, dots: pc.tip && pc.tip.dots };
    });
    check('(4) #pt-needs-for: "for the 25G straight"', c4.for === ' for the 25G straight', c4.for);
    // (a 25G of an earlier run is measured already: then nothing asks for its dots)
    check('(4) #pt-needs-measure: measure the 25G\'s dots first', c4.measure === (c4.dots === 'none') && (!c4.measure || /Measure the 25G straight['’]s dots first/.test(c4.measureText)) && (!!old25 || c4.dots === 'none'), c4);
    check('(4) the other-tip note follows profileCheck.otherTips (the 22G bent taper when it has every pad type of the board), with the packages that have no pad type',
      c4.other === c4.otherTips.length > 0 && (!c4.other || (/The 22G bent taper has/.test(c4.otherText)
        && (c4.untyped ? new RegExp(c4.untyped + ' packages? ha(s|ve) no pad type yet').test(c4.otherText) : !/no pad type yet/.test(c4.otherText)))), c4);
    await shoot(page, 'needs-for-25g', () => toTop(page, '#pt-needs'));

    // ---- (5) its needle frame, the ticks, where it lands --------------------------------------------------------------
    await page.evaluate(() => IL.go('#setup/needle'));
    // a tip that was never on (a 25G of an earlier run has its own frame: its caption names it)
    await page.waitForFunction((fresh) => { const c = document.getElementById('anchor-k-cap'); return !!c && (fresh ? /not known yet/ : /25G straight/).test(c.textContent); }, !old25, { timeout: 15000 });
    const c5 = await page.evaluate(() => ({ k: document.getElementById('anchor-k').value, cap: document.getElementById('anchor-k-cap').textContent, lead: !document.getElementById('anchor-lead').hidden,
      both: !document.getElementById('btn-anchor').hidden }));
    check('(5) #anchor-k is empty: "K of the 25G straight: not known yet", Both tips touch preselected, the lead shown', old25 ? /25G straight/.test(c5.cap)
      : c5.k === '' && c5.cap === 'K of the 25G straight: not known yet' && c5.lead && c5.both, c5);
    await shoot(page, 'needle-frame-never-on', () => toTop(page, '#card-needle'));
    // the frame by Both tips touch and the offset (as the bench does it: through the API, with the needle token)
    s = await status(page);
    nd = s.dispense.needle;
    await api(page, 'POST', '/api/needle/anchor', { surfaceZ: bzMid, needleContactZ: Number((bzMid - 2.59).toFixed(3)), note: 'tip-setups test: both tips (SIM)', needleChangedAtMs: nd.changedAtMs });
    await api(page, 'POST', '/api/needle/offset', { offsetMm: -2.59, note: 'tip-setups test (SIM)', needleChangedAtMs: nd.changedAtMs });
    await ticks(page);
    await page.evaluate(() => IL.go('#setup/tip'));
    await page.waitForFunction(() => { const b = document.querySelector('#tip-primary button'); return b && /Check where it lands/.test(b.textContent); }, null, { timeout: 15000 });
    await shoot(page, 'setup-tip-next-steps', () => toTop(page, '#card-tip'));
    await page.click('#tip-primary button');
    await page.waitForSelector('#btn-pcal-start', { timeout: 15000 });
    await page.waitForFunction(() => { const b = document.getElementById('btn-pcal-start'); return !!b && /^Check where it lands/.test(b.textContent) && !b.disabled; }, null, { timeout: 30000 });
    const c5b = await page.evaluate(() => ({ title: document.getElementById('panel-bar-title').textContent, start: document.getElementById('btn-pcal-start').textContent,
      line: document.getElementById('pcal-tipline').textContent, note: document.getElementById('pcal-note').textContent }));
    check('(5) the pad-cal view: "Check where the 25G straight lands", the start "Check where it lands · ≈ …", "For the 25G straight"',
      c5b.title === 'Check where the 25G straight lands' && /^Check where it lands · ≈ \d+:\d\d$/.test(c5b.start) && /^For the 25G straight/.test(c5b.line) && /saves where the needle lands/.test(c5b.note), c5b);
    await shoot(page, 'landing-before-start', null, true);
    const areaBefore = await api(page, 'GET', '/api/calibration-area');
    const jl = await runCal(page, 'landing', 240000);
    await page.waitForSelector('#pcal-landing:not([hidden])', { timeout: 15000 });
    const c5c = await page.evaluate(() => ({ landing: document.getElementById('pcal-landing').textContent, chip: document.getElementById('chip-tip').className,
      xy: IL.S.status.dispense.needle.xyChecked, l: IL.S.status.padCalibration.result.landing }));
    const areaAfter = await api(page, 'GET', '/api/calibration-area');
    check('(5) #pcal-landing says Saved (the scene\'s 2.4 mm bias found)', /^Saved/.test(c5c.landing) && c5c.l && Math.abs(c5c.l.dxMm - 2.4) < 0.3 && Math.abs(c5c.l.dyMm + 0.4) < 0.3, { landing: c5c.landing, dx: c5c.l && c5c.l.dxMm, dy: c5c.l && c5c.l.dyMm, sentence: jl.result && jl.result.sentence });
    check('(5) #chip-tip ok, where it lands checked', /cell--ok/.test(c5c.chip) && c5c.xy, c5c.chip);
    check('(5) the landing band used the calibration area', areaAfter.usedMm > areaBefore.usedMm, [areaBefore.usedMm, areaAfter.usedMm]);
    await shoot(page, 'landing-saved', null, true);

    // ---- (6) Measure its dots ---------------------------------------------------------------------------------------
    await wipeBlank(page);
    await closePanels(page);
    await page.evaluate(() => IL.go('#profile/needs'));
    if (old25) await page.evaluate(() => IL.runFix('measure-tip', null));   // measured by an earlier run: from the fix
    else {
      await page.waitForSelector('#pt-needs-measure:not([hidden]) #btn-needs-measure:enabled', { timeout: 20000 });
      await page.click('#btn-needs-measure');
    }
    await page.waitForFunction(() => { const b = document.getElementById('btn-pcal-start'); return document.getElementById('panel-bar-title').textContent === 'Measure the 25G straight’s dots'
      && !!b && /^Measure the dots/.test(b.textContent) && !b.disabled; }, null, { timeout: 30000 });
    check('(6) the pad-cal view: "Measure the 25G straight’s dots", the start "Measure the dots · ≈ …"', true);
    await shoot(page, 'measure-before-start', null, true);
    // while it runs, a chooser is locked (it would block STOP): Setup's Add a photo… in a second tab
    const pMeasure = runCal(page, 'tip', 600000);
    const p2 = await context.newPage();
    await p2.goto(BASE + '#setup/tip');
    await p2.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.S.status.padCalibration && IL.S.status.padCalibration.state === 'running', null, { timeout: 60000 }).catch(() => null);
    await sleep(1200);
    const locked = await p2.evaluate(() => { const b = document.getElementById('btn-tip-upload'); return { disabled: b.disabled, title: b.title }; });
    check('(6) while the job ran, #btn-tip-upload was disabled: "Locked while the pad calibration runs"', locked.disabled && /^Locked while the pad calibration runs/.test(locked.title), locked);
    await p2.close();
    const jm = await pMeasure;
    const c6 = await page.evaluate(() => ({ rows: document.querySelectorAll('#pcal-tipdots tbody tr').length, doses: (IL.S.status.padCalibration.result.tipDots || {}).doses,
      measured: (IL.S.status.padCalibration.result.tipDots || {}).measured, next: document.getElementById('btn-pcal-next').hidden ? null : document.getElementById('btn-pcal-next').textContent,
      primary: document.getElementById('btn-pcal-next').classList.contains('btn--primary') }));
    check('(6) table#pcal-tipdots: one row per dose measured', c6.rows > 0 && c6.rows === (c6.doses || []).length, { rows: c6.rows, sentence: jm.result && jm.result.sentence });
    check('(6) the primary is "Calibrate the … pad types of … for the 25G straight…"', c6.measured && /^Calibrate the .*pad types of .* for the 25G straight…$/.test(c6.next || '') && c6.primary, c6);
    await shoot(page, 'measure-result', null, true);
    await page.evaluate(() => IL.go('#setup/tip'));
    await page.waitForFunction(() => /measured /.test(document.getElementById('tip-kv').textContent), null, { timeout: 15000 }).catch(() => null);
    const kv6 = await page.textContent('#tip-kv');
    check('(6) #tip-kv says measured', /Dotsmeasured\b/.test(kv6), kv6);

    // ---- (7) the SW group calibrated for the 25G --------------------------------------------------------------------
    await closePanels(page);
    await page.evaluate(() => IL.planRefresh());
    await sleep(1500);
    const gSW = await page.evaluate(() => { const g = (IL.S.plan.profileCheck.groups || []).find((x) => /SW_SPST/.test(x.label) && x.suggestion && ['new', 'calibrate-existing'].includes(x.suggestion.kind)); return g ? g.group : null; });
    check('(7) the SW group has a calibration suggestion for the 25G', !!gSW, gSW);
    await page.evaluate((g) => IL.go('#profile/needs/' + g), gSW);
    await page.waitForSelector('#btn-pt-sugg-auto:not([hidden]):enabled', { timeout: 20000 });
    await page.click('#btn-pt-sugg-auto');
    await page.waitForFunction(() => { const b = document.getElementById('btn-pcal-start'); return !!b && /^Calibrate/.test(b.textContent) && !b.disabled; }, null, { timeout: 30000 });
    const jc = await runCal(page, 'calibrate', 600000);
    await page.waitForSelector('#pcal-groups li .pcal-label', { timeout: 15000 });
    const c7 = await page.evaluate(() => {
      const li = Array.from(document.querySelectorAll('#pcal-groups li')).find((x) => /SW_SPST/.test(x.textContent));
      const g = (IL.S.status.padCalibration.result.groups || []).find((x) => /SW_SPST/.test(x.label));
      return { label: li ? (li.querySelector('.pcal-label__t') || {}).textContent : null, k: li ? (li.querySelector('.pcal-label__k') || {}).textContent : null, id: g && g.calibrationId,
        outcome: g && g.outcome };
    });
    check('(7) the label in the SW li: "SW_SPST_3.9x2.9mm … · 25G straight · Vieweg DV-5005 …"', /^SW_SPST_3\.9x2\.9mm/.test(c7.label || '') && /25G straight · Vieweg DV-5005/.test(c7.label || ''),
      { c7, sentence: jc.result && jc.result.sentence });
    check('(7) the result card offers Made with another tip…', await page.isVisible('#btn-pcal-wrongtip'));
    await shoot(page, 'calibrate-result-card', () => toTop(page, '#pcal-result'), true);
    // Edit, change, Save
    await page.click('#pcal-groups .pcal-label[data-cal="' + c7.id + '"] button[data-action="label-edit"]');
    await page.waitForSelector('#pcal-label-' + c7.id, { timeout: 5000 });
    const newLabel = 'SW_SPST_3.9x2.9mm · 25G straight · Vieweg DV-5005 · test label ' + Date.now().toString(36);
    await page.fill('#pcal-label-' + c7.id, newLabel);
    await page.waitForSelector('.pcal-label[data-cal="' + c7.id + '"] .pcal-label__bar:not([hidden])', { timeout: 5000 });
    await shoot(page, 'calibrate-label-edit', () => toTop(page, '.pcal-label[data-cal="' + c7.id + '"]'), true);
    await page.click('#btn-pcal-label-save-' + c7.id);
    await page.waitForFunction((id) => !document.getElementById('pcal-label-' + id), c7.id, { timeout: 10000 });
    const recs7 = await api(page, 'GET', '/api/pad-calibrations?limit=20');
    const r7 = recs7.find((x) => x.id === c7.id);
    check('(7) GET /api/pad-calibrations has the new label, labelAuto false', r7 && r7.label === newLabel && r7.labelAuto === false, r7 && { label: r7.label, auto: r7.labelAuto });
    // the result photo of its dose (a decision from the dots of an earlier run lays none: then the card says so)
    const ownDots = (jc.result.photos || []).some((p) => p.kind === 'result');
    const photoLoaded = ownDots ? await page.waitForFunction(() => { const i = document.querySelector('figure.pcal-photo img'); return i && i.complete && i.naturalWidth > 0; }, null, { timeout: 15000 }).then(() => true, () => false)
      : await page.isVisible('#pcal-photos-none');
    check('(7) figure.pcal-photo img loaded', photoLoaded, { ownDots });
    // the record's tip pictures, as the API keeps them, and as the card shows them (#pcal-tip-pics)
    const tipPics = async () => {
      const r = (await api(page, 'GET', '/api/pad-calibrations?limit=50')).find((x) => x.id === c7.id);
      return r ? (r.photos || []).filter((p) => p.kind === 'tip' && !p.removedAtMs).length : -1;
    };
    const uiPics = () => page.evaluate(() => document.querySelectorAll('#pcal-tip-pics button.photo-thumb[data-photo]').length);
    const waitPics = async (n, ms) => { const t = Date.now(); while (Date.now() - t < ms) { if ((await tipPics()) >= n) return true; await sleep(700); } return false; };
    const nTip0 = await tipPics();
    await press2(page, '#btn-pcal-picture');
    await waitPics(nTip0 + 1, 60000);
    await page.waitForFunction((n) => document.querySelectorAll('#pcal-tip-pics button.photo-thumb[data-photo]').length >= n, nTip0 + 1, { timeout: 15000 }).catch(() => null);
    check('(7) #btn-pcal-picture twice: one tip picture (shown under The tip)', (await tipPics()) === nTip0 + 1 && (await uiPics()) >= nTip0 + 1, [await tipPics(), await uiPics()]);
    await page.setInputFiles('#pcal-file', path.join(FIX, 'tip-22g-bent.jpg'));
    await waitPics(nTip0 + 2, 30000);
    await page.waitForFunction((n) => document.querySelectorAll('#pcal-tip-pics button.photo-thumb[data-photo]').length >= n, nTip0 + 2, { timeout: 15000 }).catch(() => null);
    check('(7) a JPEG through #pcal-file: two', (await tipPics()) === nTip0 + 2 && (await uiPics()) >= nTip0 + 2, [await tipPics(), await uiPics()]);
    await sleep(800);
    await shoot(page, 'calibrate-tip-pictures', () => toTop(page, '#pcal-tip'), true);

    // ---- (8) Records › Calibrations --------------------------------------------------------------------------------
    await closePanels(page);
    await page.evaluate(() => IL.go('#records/calibrations'));
    await page.waitForSelector('#rec-pcals-t tbody tr.is-click', { timeout: 20000 });
    const c8 = await page.evaluate(() => { const tr = document.querySelector('#rec-pcals-t tbody tr'); return { label: tr.querySelector('.rec-label__t').textContent, sub: tr.querySelector('.rec-label__s').textContent,
      thumbs: tr.querySelectorAll('.rec-thumbs .rec-thumb').length, tip: tr.cells[2].textContent }; });
    check('(8) the first row: the edited label, "SW…[ · v{n}]", 25G straight, ≥ 2 thumbnails', c8.label === newLabel && /SW_SPST/.test(c8.sub) && c8.tip === '25G straight' && c8.thumbs >= 2, c8);
    await shoot(page, 'records-calibrations', () => toTop(page, '#records-cals'));
    await page.click('#rec-pcals-tips button[data-tip="' + k25 + '"]');
    await page.waitForFunction(() => document.querySelector('#rec-pcals-t tbody tr .rec-label__t'), null, { timeout: 15000 });
    const keep = await page.evaluate((l) => Array.from(document.querySelectorAll('#rec-pcals-t .rec-label__t')).some((x) => x.textContent === l), newLabel);
    await page.click('#rec-pcals-tips button[data-tip="ad22tta-b"]');
    await page.waitForFunction(() => { const x = document.getElementById('rec-pcals-sum'); return !!x && !/Loading/.test(x.textContent); }, null, { timeout: 15000 });
    await sleep(800);
    const hide = await page.evaluate((l) => !Array.from(document.querySelectorAll('#rec-pcals-t .rec-label__t')).some((x) => x.textContent === l), newLabel);
    check('(8) the 25G straight filter keeps it, the 22G bent taper hides it', keep && hide, { keep, hide });
    await page.click('#rec-pcals-tips button[data-tip="all"]');
    await page.waitForFunction((id) => !!document.querySelector('#rec-pcals-t tr button[data-focus-key="pcal:' + id + '"]'), c7.id, { timeout: 15000 });
    await page.evaluate((id) => IL.go('#records/calibrations/p' + id), c7.id);
    await page.waitForSelector('#panel #rec-label', { timeout: 15000 });
    const c8b = await page.evaluate(() => ({ photos: document.querySelectorAll('#panel figure.rec-photo img').length, up: (document.getElementById('btn-rec-upload') || {}).textContent, label: document.getElementById('rec-label').value }));
    check('(8) its panel: the label, the photos and Add a photo…', c8b.photos >= 2 && c8b.up === 'Add a photo…' && c8b.label === newLabel, c8b);
    await shoot(page, 'records-calibration-detail', null, true);

    // ---- (9) the 22G mounted back ------------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#setup/tip'));
    await page.waitForSelector('#btn-tip-change', { timeout: 10000 });
    await page.click('#btn-tip-change');
    await page.waitForSelector('#tip-opt-ad22tta-b', { timeout: 15000 });
    await page.click('#tip-opt-ad22tta-b');
    await page.waitForFunction(() => { const b = document.getElementById('btn-tip-mount'), w = document.getElementById('tip-change-what');
      return !!b && !!w && /^Mount the 22G bent taper$/.test(b.textContent) && !b.disabled && /22G bent taper/.test(w.textContent); }, null, { timeout: 15000 });
    await shoot(page, 'change-tip-mount-back', null, true);
    await press2(page, '#btn-tip-mount');
    await page.waitForFunction(() => IL.S.status.dispense.tip.key === 'ad22tta-b', null, { timeout: 30000 });
    const chipBack = (await api(page, 'GET', '/api/pad-types/chip-0603')).current;
    check('(9) chip-0603 is the 22G\'s again, with its old version', chipBack.status === chipBefore.status && chipBack.version === chipBefore.version, { before: [chipBefore.status, chipBefore.version], back: [chipBack.status, chipBack.version] });
    await page.evaluate(() => IL.go('#profile'));
    await page.waitForFunction(() => IL.pcr.padTypes() && IL.pcr.padTypes().forSetup.key === 'ad22tta-b', null, { timeout: 15000 });
    await page.click('#pt-by-tip');
    await page.waitForSelector('.pt-bytip__t', { timeout: 10000 });
    const swKey = await page.evaluate(() => { const r = (IL.pcr.padTypes().families.flatMap((f) => f.types)).find((t) => /SW_SPST/.test(t.name)); return r ? r.typeKey : null; });
    const c9 = await page.evaluate((k) => ({ s25: ((document.querySelector('.pt-bytip__t button.pt-bt[data-setup="25g-straight"][data-type="' + k + '"] .badge')) || {}).textContent || null,
      s22: (document.querySelector('.pt-bytip__t td[data-setup="ad22tta-b"] button.pt-bt[data-type="' + k + '"] .badge') || {}).textContent || 'not calibrated' }), swKey);
    check('(9) By tip: the SW type characterized under the 25G; its 22G cell is the 22G\'s own', !!swKey && ['characterized', 'provisional', 'validated'].includes(c9.s25), c9);
    await shoot(page, 'profile-by-tip-22g', () => toTop(page, '.pt-bytip'));
    await page.click('#pt-filter button[data-filter="all"]');
    await page.evaluate(() => IL.go('#setup/needle'));
    await page.waitForFunction(() => /22G bent taper/.test((document.getElementById('anchor-k-cap') || {}).textContent || '') && !document.getElementById('anchor-k-why').hidden, null, { timeout: 15000 }).catch(() => null);
    const c9b = await page.evaluate(() => ({ k: document.getElementById('anchor-k').value, cap: document.getElementById('anchor-k-cap').textContent,
      why: document.getElementById('anchor-k-why').hidden ? null : document.getElementById('anchor-k-why').textContent,
      kLocked: document.querySelector('#card-needle .seg button').disabled, both: !document.getElementById('btn-anchor').hidden }));
    check('(9) #anchor-k prefilled with the 22G\'s own K, "recorded for the 22G bent taper on …"', k22 !== null && Number(c9b.k) === k22 && /^recorded for the 22G bent taper on /.test(c9b.cap), { c9b, k22 });
    // K holds for the needle it was measured with (rc1): after the mount the engine refuses it, the card says why
    check('(9) From K is locked after the mount: Both tips, "K was measured with the needle before the change at …"',
      c9b.kLocked && c9b.both && /^K was measured with the needle before the change at \d\d:\d\d: record the needle frame with both tips touching the same spot\.$/.test(c9b.why || ''), c9b);

    // ---- (9) values of the needle before the change never reach the new one (review 10-31: setup.js:570/589, calibrate.js:537)
    // the frame's contact heights are the old needle's: the fields stay empty, and Record needle frame posts nothing
    const nd9 = (await status(page)).dispense.needle;
    const f9 = await page.evaluate(() => ({ n1: document.getElementById('anchor-n1').value, n2: document.getElementById('anchor-n2').value }));
    check('(9) after the change the needle frame fields are empty, not the old frame\'s contacts', !!nd9.anchor && !nd9.anchored && f9.n1 === '' && f9.n2 === '',
      { f9, anchor: nd9.anchor, anchored: nd9.anchored });
    let anchorPosts = 0;
    await page.route('**/api/needle/anchor', (route) => { anchorPosts++; return route.continue(); });
    await page.click('#btn-anchor');
    await sleep(1500);
    await page.unroute('**/api/needle/anchor');
    const st9 = (await status(page)).dispense.needle;
    check('(9) Record needle frame with nothing captured posts nothing: the old frame is not recorded for the new needle', anchorPosts === 0 && !st9.anchored, { anchorPosts, anchored: st9.anchored });
    // a touch-off: its era comes from the server's times, not from when this page first saw the row
    const tok9 = nd9.changedAtMs;
    const toRow = (id, at, z, method) => ({ id, created_at: at, method, z_offset_mm: z, previous_offset_mm: -2.59, operator: null,
      details: JSON.stringify({ analysis: { gapsMm: [0.1, 0.05, 0], diametersMm: [1.05, 1.4, null], dotStates: ['dot', 'dot', 'none'], contactGapMm: 0.075,
        bracketHighMm: 0.1, bracketLowMm: 0.05, referenceDiameterMm: 1.05, reason: 'dot squashed between 0.100 and 0.050 mm' }, pitchMm: 1.6, blank: 'blank' }) });
    async function withTouchOff(row) {
      await page.unroute('**/api/calibrations').catch(() => {});
      await page.route('**/api/calibrations', async (route) => {
        try {
          const res = await route.fetch();
          const body = await res.json();
          body.touchOffs = [row].concat(body.touchOffs || []);
          await route.fulfill({ response: res, json: body });
        } catch (e) { /* unrouted while it was being answered */ }
      });
      await page.evaluate(() => IL.pcr.loadCalibrations());
      // the box shows this row (its render key starts with the row id)
      await page.waitForFunction((id) => { const h = document.getElementById('to-result'); try { return !!h.dataset.key && JSON.parse(h.dataset.key)[0] === id; } catch (e) { return false; } },
        row.id, { timeout: 15000 });
      await sleep(400);
      return page.evaluate(() => ({ use: !!document.getElementById('to-use'), inUse: /In use as the needle offset/.test(document.getElementById('to-sum').textContent),
        era: document.getElementById('to-era') ? document.getElementById('to-era').textContent.trim() : null }));
    }
    await page.evaluate(() => IL.go('#calibrate/touch-off'));
    await page.waitForSelector('#to-result', { timeout: 15000 });
    const before9 = await withTouchOff(toRow(990101, tok9 - 60000, -2.61, 'dot-ladder-touch-off (not applied)'));
    check('(9) a touch-off from before the needle change: no "Use for the needle offset", "Touched off before the needle change at hh:mm: touch off again."',
      !before9.use && !before9.inUse && /^Touched off before the needle change at \d\d:\d\d: touch off again\.$/.test(before9.era || ''), before9);
    const inUse9 = await withTouchOff(toRow(990102, tok9 - 30000, nd9.zOffsetMm, 'dot-ladder-touch-off'));
    check('(9) a touch-off from before the change with today\'s offset is not "In use as the needle offset"', !inUse9.use && !inUse9.inUse && !!inUse9.era, inUse9);
    const after9 = await withTouchOff(toRow(990103, tok9 + 1000, -2.61, 'dot-ladder-touch-off (not applied)'));
    let offsetBody = null;
    await page.route('**/api/needle/offset', (route) => { offsetBody = route.request().postDataJSON(); return route.fulfill({ status: 200, json: { needle: nd9 } }); });
    if (after9.use) await page.click('#to-use');
    await sleep(1000);
    await page.unroute('**/api/needle/offset');
    check('(9) a touch-off of this needle: "Use for the needle offset" posts it with today\'s needle token', after9.use && !after9.era && !!offsetBody
      && offsetBody.needleChangedAtMs === tok9 && Math.abs(offsetBody.offsetMm - -2.61) < 1e-9, { after9, offsetBody, tok9 });
    await page.unroute('**/api/calibrations');
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.evaluate(() => IL.go('#setup/needle'));
    await page.waitForSelector('#btn-anchor', { state: 'visible', timeout: 15000 });

    // ---- (10) the 25G on the head, the 22G mounted --------------------------------------------------------------------
    scene('--physical-tip', k25);
    await page.fill('#anchor-n1', String(bzMid));
    await page.fill('#anchor-n2', String(Number((bzMid - 2.59).toFixed(3))));
    await page.click('#btn-anchor');
    await page.waitForFunction(() => IL.S.status.dispense.needle.anchored, null, { timeout: 15000 });
    await page.click('#btn-needle-offset');
    await page.waitForFunction(() => IL.S.status.dispense.needle.touchedOff, null, { timeout: 15000 });
    // the new frame: a touch-off between the needle change and this frame is the old frame's; one after it is this frame's
    const nd10 = (await status(page)).dispense.needle;
    await page.evaluate(() => IL.go('#calibrate/touch-off'));
    await page.waitForSelector('#to-result', { timeout: 15000 });
    const mid10 = nd10.anchor.setAtMs > nd10.changedAtMs + 1 ? await withTouchOff(toRow(990104, nd10.anchor.setAtMs - 1, -2.61, 'dot-ladder-touch-off (not applied)')) : null;
    check('(10) a touch-off from after the needle change but before the needle Z frame: no Use, "Touched off before the needle Z frame at hh:mm: touch off again."',
      !!mid10 && !mid10.use && !mid10.inUse && /^Touched off before the needle Z frame at \d\d:\d\d: touch off again\.$/.test(mid10.era || ''), { mid10, nd10 });
    const new10 = await withTouchOff(toRow(990105, nd10.anchor.setAtMs + 1000, -2.61, 'dot-ladder-touch-off (not applied)'));
    check('(10) a touch-off after the needle Z frame: "Use for the needle offset"', new10.use && !new10.era, new10);
    await page.unroute('**/api/calibrations');
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.evaluate(() => IL.go('#setup/needle'));
    await ticks(page);
    await wipeBlank(page);
    await page.evaluate(() => IL.runFix('landing-check', null));
    await page.waitForFunction(() => /^Check where it lands/.test((document.getElementById('btn-pcal-start') || {}).textContent || '') && !document.getElementById('btn-pcal-start').disabled, null, { timeout: 30000 });
    const jq = await runCal(page, 'landing', 240000);
    await page.waitForSelector('#pcal-landing:not([hidden])', { timeout: 15000 });
    const c10 = await page.evaluate(() => ({ text: document.getElementById('pcal-landing').textContent, save: !!document.getElementById('btn-pcal-landing-save'), xy: IL.S.status.dispense.needle.xyChecked }));
    check('(10) the landing check asks "is the 22G bent taper on the dispenser?", nothing saved', /is the 22G bent taper on the dispenser/i.test(c10.text) && c10.save && !c10.xy, { c10, sentence: jq.result && jq.result.sentence });
    await shoot(page, 'landing-question', null, true);
    // the operator says it is (the wrong answer): saved, so a calibration may run
    await page.click('#btn-pcal-landing-save');
    await page.waitForFunction(() => IL.S.status.dispense.needle.xyChecked, null, { timeout: 15000 });
    await wipeBlank(page);
    await planPadcal();   // the plan of the 25G is stale with the 22G mounted
    // a group of the board the 22G can calibrate (SW is a stand-in for it when only the 25G has it; else Test_Strip)
    const g10 = await page.evaluate(() => {
      const gs = (IL.S.plan.profileCheck.groups || []).filter((x) => x.suggestion && ['new', 'calibrate-existing'].includes(x.suggestion.kind));
      const g = gs.find((x) => /SW_SPST/.test(x.label)) || gs[0];
      return g ? g.group : null;
    });
    check('(10) a group of the board to calibrate with the 22G mounted', !!g10, g10);
    await page.evaluate((g) => IL.go('#profile/needs/' + g), g10);
    await page.waitForSelector('#btn-pt-sugg-auto:not([hidden]):enabled', { timeout: 20000 });
    await page.click('#btn-pt-sugg-auto');
    await page.waitForFunction(() => { const b = document.getElementById('btn-pcal-start'); return !!b && /^Calibrate/.test(b.textContent) && !b.disabled; }, null, { timeout: 30000 });
    const jw = await runCal(page, 'calibrate', 600000);
    const ctl = jw.result && jw.result.control ? jw.result.control.text || '' : '';
    check('(10) the calibration\'s control text names another tip (nothing saved)', /or another tip than the 22G bent taper is on/.test(ctl) || /or another tip than the 22G bent taper is on/.test(jw.result && jw.result.sentence || ''),
      { ctl, sentence: jw.result && jw.result.sentence });
    // Made with another tip… (the SW record of (7): as if it was made with another tip than the 25G): Records, two presses
    await closePanels(page);
    await page.evaluate((id) => IL.go('#records/calibrations/p' + id), c7.id);
    await page.waitForSelector('#panel #btn-rec-wrongtip', { timeout: 20000 });
    await press2(page, '#btn-rec-wrongtip');
    const opened = await page.waitForFunction(() => IL.panel.isPushed('tip-change'), null, { timeout: 15000 }).then(() => true, () => false);
    const recs10 = await api(page, 'GET', '/api/pad-calibrations?limit=50');
    const r10 = recs10.find((x) => x.id === c7.id);
    check('(10) Made with another tip… twice: the record shows wrong tip, Change tip opens', opened && r10 && (r10.flags || []).includes('wrong-tip'), { opened, flags: r10 && r10.flags });
    await closePanels(page);
    await page.evaluate(() => IL.go('#records/calibrations'));
    await page.click('#rec-pcals-tips button[data-tip="all"]').catch(() => null);
    const wrongRow = await page.waitForFunction((id) => { const b = document.querySelector('#rec-pcals-t button[data-focus-key="pcal:' + id + '"]'); return !!b && /wrong tip/.test(b.closest('tr').textContent); }, c7.id, { timeout: 20000 }).then(() => true, () => false);
    check('(10) Records shows "wrong tip" on that row', wrongRow);
    await shoot(page, 'records-wrong-tip', () => toTop(page, '#records-cals'));
    // the run confirm's tip tick lapsed at the mount
    const tickGone = await page.evaluate(() => !IL.attest.valid('tip-on', IL.S.status));
    await page.evaluate(() => IL.go('#run/checks'));
    await sleep(1000);
    // the confirm as a runnable board opens it (this plan is blocked: it opens anyway to show the tick, and says why
    // it cannot run)
    await page.waitForSelector('#btn-run', { timeout: 15000 });
    await page.evaluate(() => { const b = document.getElementById('btn-run'); b.disabled = false; b.click(); });
    const confirmShown = await page.waitForSelector('#dlg-run #run-confirm-tip', { timeout: 8000 }).then(() => true, () => false);
    const unticked = confirmShown ? !(await page.isChecked('#run-tick-tip')) : tickGone;
    check('(10) the run confirm\'s #run-confirm-tip is unticked after the mount', tickGone && unticked, { tickGone, confirmShown });
    if (confirmShown) {
      await shoot(page, 'run-confirm-tip', () => page.evaluate(() => { const t = document.getElementById('run-confirm-tip'); if (t) t.scrollIntoView({ block: 'center' }); }));
      await page.click('#btn-run-cancel');
    }

    // ---- (11) the picture viewer -------------------------------------------------------------------------------------
    await closePanels(page);
    const lib11 = await api(page, 'GET', '/api/tip-setups');
    const up = ((lib11.setups || []).find((x) => x.key === k25) || {}).photos.find((p) => p.kind === 'setup' && p.removable);
    // a result photo: no remove button. The record of (7) has one when its job laid dots of its own, else the newest
    // record that has one (a calibration decided from fresh evidence links none), else the newest result photo of all
    // (the landing check's or the measure's) in the same viewer
    const recs11 = await api(page, 'GET', '/api/pad-calibrations?limit=100');
    const hasResult = (r) => (r.photos || []).some((p) => p.kind === 'result' && !p.removedAtMs);
    const r11 = recs11.find((x) => x.id === c7.id && hasResult(x)) || recs11.find(hasResult);
    if (r11) {
      await page.evaluate(() => IL.go('#records/calibrations'));
      await page.evaluate((id) => IL.go('#records/calibrations/p' + id), r11.id);
      await page.waitForSelector('#panel figure.rec-photo button', { timeout: 15000 }).catch(() => null);
      const resultBtn = await page.evaluateHandle(() => Array.from(document.querySelectorAll('#panel figure.rec-photo button')).find((b) => /Top camera/.test(b.title)));
      if (resultBtn && (await resultBtn.evaluate((b) => !!b))) await resultBtn.click();
    } else {
      const rp = ((await api(page, 'GET', '/api/photos?kind=result&limit=1')) || [])[0];
      if (rp) await page.evaluate((p) => IL.photos.view(p, {}), rp);
    }
    if (await page.waitForSelector('#photo-view', { timeout: 5000 }).then(() => true, () => false)) {
      check('(11) a result photo has no remove button', await page.isHidden('#btn-photo-remove'), { record: r11 ? r11.id : null });
      await page.keyboard.press('Escape');
      await page.waitForSelector('#photo-view', { state: 'detached', timeout: 5000 });
    } else check('(11) a result photo has no remove button', false, 'no result photo');
    // the uploaded setup picture: STOP stays clickable while #photo-view is open, Esc closes it
    await page.evaluate(() => IL.go('#setup/tip'));
    await page.evaluate(() => IL.tips.openChange({ select: '25g-straight' }));
    await page.evaluate((p) => IL.photos.view(p, {}), up);
    await page.waitForSelector('#photo-view #photo-view-img', { timeout: 5000 });
    await page.waitForFunction(() => { const i = document.getElementById('photo-view-img'); return i.complete && i.naturalWidth > 0; }, null, { timeout: 10000 });
    const stopLive = await page.evaluate(() => { const b = document.getElementById('btn-stop').getBoundingClientRect(); const at = document.elementFromPoint(b.left + b.width / 2, b.top + b.height / 2); return !!at && !!at.closest('#btn-stop'); });
    check('(11) STOP stays clickable while #photo-view is open', stopLive);
    await shoot(page, 'photo-view');
    await page.keyboard.press('Escape');
    const escClosed = await page.waitForSelector('#photo-view', { state: 'detached', timeout: 5000 }).then(() => true, () => false);
    check('(11) #photo-view closes with Esc', escClosed);
    await page.evaluate((p) => IL.photos.view(p, {}), up);
    await page.waitForSelector('#btn-photo-remove:visible', { timeout: 5000 });
    await press2(page, '#btn-photo-remove');
    await page.waitForSelector('#btn-photo-undo', { state: 'visible', timeout: 10000 });
    const gone = await page.evaluate(async (u) => (await fetch(u)).status, up.url);
    check('(11) removed (two presses): 410 from its URL', gone === 410, gone);
    await shoot(page, 'photo-removed-undo');
    await page.click('#btn-photo-undo');
    await page.waitForFunction(async (u) => (await fetch(u)).status === 200, up.url, { timeout: 10000 }).catch(() => null);
    const back = await page.evaluate(async (u) => (await fetch(u)).status, up.url);
    check('(11) the Undo brings it back', back === 200, back);

    // ---- screenshots of the remaining states --------------------------------------------------------------------------
    await closePanels(page);
    await page.evaluate(() => IL.go('#profile'));
    await page.waitForFunction(() => IL.pcr.padTypes(), null, { timeout: 10000 });
    await page.evaluate(() => IL.pt.openPadType(IL.pcr.padTypes().families.flatMap((f) => f.types).find((t) => t.typeKey === 'chip-0603')));
    await page.waitForSelector('#dlg-padtype[open]', { timeout: 10000 });
    await page.click('#pt-cancel');
    await page.evaluate(() => IL.go('#profile/pad-types/chip-0603'));
    await page.waitForSelector('#pt-side-history', { timeout: 10000 });
    await page.click('#pt-side-history');
    await page.waitForSelector('#dlg-history[open]', { timeout: 10000 });
    await page.click('#history-all');
    const hist = await page.evaluate(() => ({ head: Array.from(document.querySelectorAll('#history-body thead th')).map((t) => t.textContent), sum: document.getElementById('history-sum').textContent }));
    check('(11b) History: a Tip column under All tips, the versions count across tips', hist.head.includes('Tip') && /Version numbers count across tips/.test(hist.sum), hist);
    await shoot(page, 'pad-type-history');
    await page.click('#history-close');
    await page.evaluate(() => IL.tips.openAdd({}));
    await page.waitForSelector('#dlg-tip-add', { timeout: 5000 });
    await page.evaluate(() => { const d = IL.dialog.top(); if (d) d.close('test'); });
    await page.evaluate(() => IL.tips.openEdit('25g-straight'));
    await page.waitForSelector('#dlg-tip-add #ta-name', { timeout: 5000 });
    await page.fill('#ta-id', '0.26');
    await page.dispatchEvent('#ta-id', 'input');
    const corr = await page.evaluate(() => ({ save: document.getElementById('ta-save').textContent, note: document.getElementById('ta-note').textContent, gauge: document.getElementById('ta-gauge').disabled }));
    check('(11c) Edit a tip with records: identity locked, a bore correction asks "Correct the details"', corr.save === 'Correct the details' && /Correcting the 25G straight’s details\?/.test(corr.note) && corr.gauge, corr);
    await shoot(page, 'edit-tip-correction');
    await page.evaluate(() => { const d = IL.dialog.top(); if (d) d.close('test'); });

    // ---- (11d-f) a value typed before a needle change keeps that needle's token (PR #5 reviews, setup.js:53/683/685) --
    // Each case: a frame recorded for this needle A (by the API, as another browser would), a value typed for A, needle B
    // recorded meanwhile in another browser, the other contact typed after the change (within 20 mm of it, so the engine
    // gets as far as the token): Record needle frame posts A's token, the engine refuses it (409 needle-changed), the
    // card shows the refusal, and no frame is recorded for B.
    async function staleCase(name, typeOld, afterChange, freshId, freshVal) {
      await closePanels(page);
      await page.reload();
      await page.waitForFunction(() => window.IL && IL.api && IL.tips && IL.tips.data(), null, { timeout: 30000 });
      await page.evaluate(() => IL.go('#setup'));
      await page.waitForSelector('#anchor-n2', { timeout: 10000 });
      const tokA = (await status(page)).dispense.needle.changedAtMs;
      await page.evaluate((t) => IL.api('POST', '/api/needle/anchor', { surfaceZ: 5.5, needleContactZ: 2.91, needleChangedAtMs: t }), tokA);
      await page.waitForFunction(() => document.getElementById('anchor-n2').value === '2.910' && document.getElementById('anchor-n1').value === '5.500', null, { timeout: 8000 });
      await typeOld();
      await page.evaluate(() => fetch('/api/needle/changed', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ why: 'test (11d-f): another browser' }) }));
      await page.waitForFunction((t) => IL.tips.token() !== t, tokA, { timeout: 5000 });
      await sleep(1600);   // two status polls
      if (afterChange) await afterChange();
      await page.click('#' + freshId, { clickCount: 3 });
      await page.keyboard.type(freshVal);
      let body = null;
      await page.route('**/api/needle/anchor', (route) => { body = route.request().postDataJSON(); return route.continue(); });
      const resP = page.waitForResponse((r) => r.url().includes('/api/needle/anchor') && r.request().method() === 'POST', { timeout: 10000 }).catch(() => null);
      await page.click('#btn-anchor');
      const res = await resP;
      await sleep(800);
      await page.unroute('**/api/needle/anchor');
      const st = (await status(page)).dispense.needle;
      const box = await page.evaluate(() => { const r = document.querySelector('#card-needle .su-ref .refusal'); return r ? r.textContent : null; });
      check(name, !!body && body.needleChangedAtMs === tokA && !!res && res.status() === 409 && !st.anchored
        && /The needle was changed at \d\d:\d\d after these values were taken/.test(box || ''),
        { body, status: res && res.status(), anchored: st.anchored, box: box && box.slice(0, 200), tokA, now: st.changedAtMs });
    }
    await staleCase('(11d) N2 retyped to the shown value and kept in focus through a needle change: posted with the old needle\'s token, refused',
      async () => { await page.click('#anchor-n2', { clickCount: 3 }); await page.keyboard.type('2.910'); }, null, 'anchor-n1', '5.5');
    await staleCase('(11e) N2 typed for the old needle, then a key and Backspace after the change: still the old needle\'s token, refused',
      async () => { await page.click('#anchor-n2', { clickCount: 3 }); await page.keyboard.type('2.95'); await page.keyboard.press('Tab'); },
      async () => { await page.click('#anchor-n2'); await page.keyboard.press('End'); await page.keyboard.type('7'); await page.keyboard.press('Backspace'); },
      'anchor-n1', '5.5');
    await staleCase('(11f) N1 typed for the old needle, N2 typed after the change: the old needle\'s token, refused',
      async () => { await page.click('#anchor-n1', { clickCount: 3 }); await page.keyboard.type('5.6'); await page.keyboard.press('Tab'); }, null, 'anchor-n2', '2.91');

    // ---- (12) the page ------------------------------------------------------------------------------------------------
    check('(12) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('(12) no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'tip-setups-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results, shots };
    fs.writeFileSync(path.join(OUT, 'tip-setups-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s); ' + shots.length + ' screenshots in ' + SHOTS);
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
/** "24" (a number in a check's name). */
function IL_fmt(n) { return String(Math.round(n * 10) / 10); }
