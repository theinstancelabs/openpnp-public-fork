/*
 * Missing paste profiles on a SIM session (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §14.4): the KiCad upload of
 * src/test/resources/padprofile/padcal.kicad_pcb (§14.3's board) with its profile column, Run › Checks' Pad types card,
 * Profile › Needs a profile and its suggestion card, Use (plans again), the pad inspector, Calibrate automatically from
 * the before-start summary to the readings prompt (also after a reload) and the result, the by-hand path (the guide's
 * ladder, the Weigh dots card), Calibrate › Pad calibration, STOP while the readings are asked for. Run by
 * run-webui-sim-test.sh after the other browser tests in the same session; it stops at once on a server that is not a
 * simulator. The machine moves (it is the SIM's): every motion is the engine's, started with the UI's two presses.
 *
 * Set-up (0): Start and home if needed, one Z check (an engine task: the simulator confirms itself, so the SIM-only
 * shortened calibration of §14.4 is accepted), the scene's padcal blocks (il_sim_scene.py amend --paste --coupons
 * --balance display --bench-blank), the purge row, blank place, blank surface and calibration area inside the scene's
 * blank, the coupon places, the regulator and paste-life ticks, chip-0603 characterized.
 * Checks: (1) upload: a profile badge per package row, one NEEDS_PROFILE warning in group 1 and no NO_PAD_TYPE; save;
 * "Needs a profile…" goes to #profile/needs/<g>; (2) the board planned: Pad types Blocked with "Needs a profile:
 * SW_SPST_3.9x2.9mm"; (3) #pt-needs: 3 rows (J, T, Q), the tip details hold the 2 WSON groups, nav "Needs 3 profiles";
 * (4) the SW card: "New pad type SW_SPST_3.9x2.9mm", 4 suggested dots on J1, the plan's dot count unchanged; (5) Use Chip
 * 0603 on TestChip_A: a new plan shown, its row gone; (6) inspector on J1.1: one profile notebox, no NO_PAD_TYPE
 * flag, no "New pad type"; (7) Calibrate automatically: the summary says it needs you once (the test dots' X and Y in
 * mm), nothing runs before the second press, the phases advance, the strip names the pad calibration and, at 1440 px,
 * its step; (8) the readings prompt: the strip "Waiting
 * for you · weigh", a reload brings it back with the coupon fields, the SIM balance read with paste and wiped (the
 * test is the operator), "= n mg of paste" echoed, saved; (9) result "Characterized …" (numbers with their units on
 * one line, the dot size in words), ≥ 3 crops load, the new plan shown, SW and TestChip_A gone from the list, Test_Strip
 * left, the profile panel's pad types in this plan clear of their counts at 1440 and 1280 px and whole names in the
 * family tables, a test run asked to stop inside a two-dot pad lays whole pads; (10) I'll do it by hand on Test_Strip:
 * the guide names
 * it, its ladder (two presses) fills table#pcal-ladder, the Weigh dots card has the quarter turn and a count;
 * (11) Calibrate › Pad calibration: "… mm left", Blank wiped (two presses) → 0 mm used; (12) the by-hand weighing,
 * STOP at its readings prompt: "Stopped … the coupons still need weighing …" and "Type the readings" (the first coupon
 * weighed, the last skipped with two presses); (13) no
 * JavaScript errors, no native dialogs. Screenshots (PP_SHOTS=0 skips): the nano upload preview, Run › Checks with the
 * Pad types card, the Needs list, the suggestion, the pad-cal view before start, the job running, the readings prompt,
 * the result and the coupons to weigh with Skip at 1440 and 1280 px, light and dark.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (pad-profile-results.json), SHOTS_DIR (default OUT_DIR),
 * PP_SHOT_SIZES (default 1440x900,1280x800; e.g. add 1024x768), SIM_SCENE (the session's scene file: its config dir is
 * amended), IL_KICAD_FIXTURES (src/test/resources/kicad).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');
const { execFileSync } = require('child_process');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-pad-profile-test-out';
const SHOTS = process.env.SHOTS_DIR || OUT;
const SCENE = process.env.SIM_SCENE;
const REPO = path.join(__dirname, '..', '..', '..');
const KICAD = process.env.IL_KICAD_FIXTURES || path.join(REPO, 'src', 'test', 'resources', 'kicad');
const PADCAL = path.join(REPO, 'src', 'test', 'resources', 'padprofile', 'padcal.kicad_pcb');
const TS = Date.now().toString(36);
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shots = [];
/** PP_SHOT_SIZES "1440x900,1280x800,1024x768" (default the first two). */
const SIZES = String(process.env.PP_SHOT_SIZES || '1440x900,1280x800').split(',').map((x) => x.split('x').map(Number));
/**
 * The same moment at each size, light and dark (prep re-runs after a resize, e.g. to scroll). panel: below 1100 px,
 * where the panel sits under the main column, the shot starts at the panel instead (the view the moment is about).
 */
async function shoot(page, name, prep, panel) {
  if (process.env.PP_SHOTS === '0') return;
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => IL.setTheme(t), theme);
    for (const [w, h] of SIZES) {
      await page.setViewportSize({ width: w, height: h });
      await sleep(350);
      if (panel && w < 1100) await page.evaluate(() => window.scrollBy(0, document.getElementById('panel').getBoundingClientRect().top - document.getElementById('strip').getBoundingClientRect().bottom));
      else if (prep) await prep();
      await sleep(250);
      // the steady screen: a toast of an earlier action and the flash of a row just scrolled to fade by themselves
      await page.evaluate(() => { const t = document.getElementById('toast'); if (t) t.hidden = true; for (const n of document.querySelectorAll('.flash')) n.classList.remove('flash'); });
      const f = path.join(SHOTS, name + '-' + w + '-' + theme + '.png');
      await page.screenshot({ path: f });
      shots.push(f);
    }
  }
  await page.evaluate(() => IL.setTheme('light'));
  await page.setViewportSize({ width: 1440, height: 900 });
  await sleep(200);
}
/**
 * Scrolls the main column (or the panel that holds sel) so that sel starts its own margin below the status strip: what
 * comes before it ends just under the strip (no line cut in half above it). When the page ends before sel can get
 * there, the section around it (fallback) starts there instead; when that cannot get there either, the card cut by the
 * top edge is shown whole (the page then ends lower in the window).
 */
const toTop = (page, sel, fallback) => page.evaluate(([x, fb]) => {
  const scroller = (n) => {
    let sc = n.parentElement;
    while (sc && sc !== document.body && !(sc.scrollHeight > sc.clientHeight + 1 && /(auto|scroll)/.test(getComputedStyle(sc).overflowY))) sc = sc.parentElement;
    const win = !sc || sc === document.body;
    return { win, sc, top: win ? document.getElementById('strip').getBoundingClientRect().bottom : sc.getBoundingClientRect().top,
      by: (dy) => { if (win) window.scrollBy(0, dy); else sc.scrollTop += dy; } };
  };
  const place = (n) => {
    const v = scroller(n);
    let a = n;
    while (a.parentElement && !a.previousElementSibling && a.parentElement !== v.sc) a = a.parentElement;
    const prev = a.previousElementSibling;
    const want = v.top + (prev ? Math.max(8, n.getBoundingClientRect().top - prev.getBoundingClientRect().bottom) : 16);
    v.by(n.getBoundingClientRect().top - want);
    return n.getBoundingClientRect().top - want;
  };
  const n = document.querySelector(x);
  if (!n) return;
  const f = fb && document.querySelector(fb);
  if (place(n) <= 2 || (f && place(f) <= 2)) return;
  const v = scroller(n);
  const r = n.getBoundingClientRect();
  const cut = document.elementsFromPoint(r.left + 24, v.top + 2).map((e) => e.closest('.card'))
    .find((c) => c && (v.win || v.sc.contains(c)) && c.getBoundingClientRect().top < v.top - 1);
  if (cut && v.top - cut.getBoundingClientRect().top < innerHeight * 0.6) v.by(cut.getBoundingClientRect().top - v.top - 8);
}, [sel, fallback || null]);
/** The filled buttons of Profile's Needs list, its "New pad type" and the side panel: one clear next action. */
const primaries = (page) => page.evaluate(() => Array.from(document.querySelectorAll('#pt-needs .btn--primary, #btn-padtype-new.btn--primary, #panel .btn--primary'))
  .filter((b) => b.getClientRects().length > 0).map((b) => b.id || b.textContent.trim()));
const api = (page, method, p, body) => page.evaluate(([m, x, b]) => IL.api(m, x, b === null ? undefined : b, { timeoutMs: 120000 }), [method, p, body === undefined ? null : body]);
const status = (page) => page.evaluate(() => IL.api('GET', '/api/status'));
/** The two presses of a two-press button (§11): the second ≥ 300 ms after the first, once it asks for it. */
async function press2(page, sel) {
  await page.waitForSelector(sel + ':enabled', { timeout: 15000 });
  for (let i = 0; i < 2; i++) {
    await page.click(sel);
    if (await page.waitForSelector(sel + '.btn--press', { timeout: 2000 }).then(() => true, () => false)) break;
    if (i === 1) throw new Error(sel + ' did not ask for a second press');
  }
  await sleep(450);
  await page.click(sel);
}
async function closePanels(page) {
  await page.evaluate(() => { while (IL.panel.isPushed()) IL.panel.close(); });
}
async function toBoardStep(page) {
  await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/board'); });
  await page.waitForSelector('#board-select', { state: 'visible', timeout: 15000 });
}
/** Plans a board file through the Board step at a seat and waits for that plan. */
async function planUi(page, file, seat) {
  await toBoardStep(page);
  await page.waitForFunction((f) => Array.from(document.querySelectorAll('#board-select option')).some((o) => o.value === 'file:' + f), file, { timeout: 15000 });
  await page.selectOption('#board-select', 'file:' + file);
  for (const [id, v] of [['board-x', seat.x], ['board-y', seat.y], ['board-z', seat.z], ['board-rot', seat.rotation]]) await page.fill('#' + id, String(v));
  const before = await page.evaluate(() => IL.S.planId);
  await page.click('#btn-plan');
  await page.waitForFunction(([b, f]) => { const p = IL.S.plan; return !!p && p.planId !== b && p.source && p.source.file === f; }, [before, file], { timeout: 60000 })
    .catch(async (e) => { throw new Error('no plan of ' + file + ': ' + (await page.evaluate(() => Array.from(document.querySelectorAll('.refusal')).map((n) => n.textContent).join(' | ') || (document.getElementById('toast') || {}).textContent)) + ' (' + e.message.split('\n')[0] + ')'); });
}
async function openUpload(page) {
  await toBoardStep(page);
  await page.click('#btn-board-upload');
  await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
}
async function upload(page, file) {
  await page.setInputFiles('#board-import-file', file);
  await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
}
const job = (page) => page.evaluate(() => IL.S.status && IL.S.status.padCalibration);
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
const stripText = (page) => page.evaluate(() => document.querySelector('#pill-engine .cell__v').textContent);

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

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) jsErrors.push(m.text()); });
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  const t0 = Date.now();
  try {
    const resp = await page.goto(BASE + '#run');
    check('UI served', resp.ok(), resp.status());
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.padProfile && IL.pt, null, { timeout: 60000 });
    const sim = await page.evaluate(() => !!(IL.S.status.server && IL.S.status.server.simulated === true));
    check('the server is an OpenPnP simulator (status.server.simulated)', sim);
    if (!sim) throw new Error('not a simulator: nothing was done');
    await page.evaluate(() => IL.setTheme('light'));
    await closePanels(page);

    // ---- (0) set-up -------------------------------------------------------------------------------------------------
    let s = await status(page);
    if (!s.machine.enabled) { await api(page, 'POST', '/api/machine/enable'); await page.waitForFunction(() => IL.S.status.machine.enabled, null, { timeout: 30000 }); }
    // the controller itself answers as OpenPnP's simulator before anything moves (a loopback serial bridge would not)
    const confirmed = await page.waitForFunction(() => IL.S.status.server.simulatorConfirmed === true, null, { timeout: 15000 }).then(() => true, () => false);
    check('the controller answered as OpenPnP\'s simulator (status.server.simulatorConfirmed)', confirmed);
    if (!confirmed) throw new Error('the controller is not the simulator: nothing moved');
    if (!s.machine.homed) await api(page, 'POST', '/api/machine/home', { wait: true });
    await api(page, 'POST', '/api/machine/z-check', { wait: true }).catch(() => null);   // an engine task: the simulator confirms itself
    const cfgDir = SCENE ? path.dirname(path.dirname(SCENE)) : null;
    if (cfgDir) {
      execFileSync('python3', ['-I', path.join(REPO, 'src', 'test', 'sim', 'il_sim_scene.py'), 'amend', '--config', cfgDir, '--paste', '--coupons',
        '--balance', 'display', '--bench-blank'], { stdio: 'pipe' });
    }
    const sc = SCENE && fs.existsSync(SCENE) ? JSON.parse(fs.readFileSync(SCENE, 'utf8')) : {};
    const blank = sc.blank || { rect: [280, 225, 365, 250], surface: { z0: 5.62, slopeX: -0.0012, slopeY: 0 } };
    const [bx0, by0, bx1, by1] = blank.rect;
    const bz = (x, y) => blank.surface.z0 + (blank.surface.slopeX || 0) * (x - bx0) + (blank.surface.slopeY || 0) * (y - by0);
    // the coupons lie on the bed like the blank (the scene's coupons carry the bench's heights): the needle's Z frame (I4)
    // is the blank's, so a coupon 4 mm lower would be refused
    if (SCENE && (sc.coupons || []).some((c) => Math.abs(c.z - blank.surface.z0) > 0.3)) {
      for (const c of sc.coupons) c.z = Number(blank.surface.z0.toFixed(2));
      fs.writeFileSync(SCENE, JSON.stringify(sc, null, 2));
    }
    // the purge row along the blank's lower edge, the calibration area above it; probe hulls stay 2 mm inside the blank
    const spots = Math.min(60, Math.floor((bx1 - bx0 - 8) / 1.6));   // a job takes about 20 (phase and flush dots)
    const area = { x0Mm: bx0 + 5, y0Mm: by0 + 10, widthMm: Math.min(28, bx1 - bx0 - 10), heightMm: Math.min(16, by1 - by0 - 14) };
    await api(page, 'PUT', '/api/config', { purgeRow: { surfaceName: 'blank', x0Mm: bx0 + 5, y0Mm: by0 + 4, pitchXMm: 1.6, pitchYMm: 0, spots },
      padCalibration: { ladderDotsPerPhase: 3, minLadderDots: 6, minHops: 10, weighTargetMg: 15 } });
    await api(page, 'POST', '/api/purge-row/reset', {});
    await api(page, 'PUT', '/api/locations/blank', { x: area.x0Mm, y: area.y0Mm, z: bz(area.x0Mm, area.y0Mm) });
    const corners = [[bx0 + 3, by0 + 3], [bx1 - 3, by0 + 3], [bx1 - 3, by1 - 3], [bx0 + 3, by1 - 3]];
    await api(page, 'PUT', '/api/surfaces/blank', { points: corners.map(([x, y]) => [x, y, Number(bz(x, y).toFixed(3))]), note: 'pad-profile test: the SIM blank' });
    await api(page, 'PUT', '/api/calibration-area', area);
    await api(page, 'POST', '/api/calibration-area/reset', {});
    for (const c of sc.coupons || [{ place: 'coupon1', rect: [330, 40, 370, 70], z: 5.8 }, { place: 'coupon2', rect: [330, 75, 370, 105], z: 5.8 }]) {
      await api(page, 'PUT', '/api/locations/' + c.place, { x: (c.rect[0] + c.rect[2]) / 2, y: (c.rect[1] + c.rect[3]) / 2, z: c.z });
    }
    // the needle's Z frame on the blank (webui-sim.playwright.cjs made it there; a config copied from a bench may carry
    // the bench's): both tips on the blank, the needle 2.59 mm under N1 as the SIM's see-saw has it (K 65.59 mm; K is
    // refused after a failed Z check of this home, which webui-sim's last step leaves), then that offset confirmed (I4)
    s = await status(page);
    const nd = s.dispense.needle;
    const bzMid = Number(bz((bx0 + bx1) / 2, (by0 + by1) / 2).toFixed(3));
    if (!nd.anchored || !nd.anchor || Math.abs(nd.anchor.surfaceZMm - bzMid) > 0.5 || !nd.touchedOff) {
      await api(page, 'POST', '/api/needle/anchor', { surfaceZ: bzMid, needleContactZ: Number((bzMid - 2.59).toFixed(3)), note: 'pad-profile test (SIM)' });
      await api(page, 'POST', '/api/needle/offset', { offsetMm: -2.59, note: 'pad-profile test (SIM)' });
    }
    await api(page, 'POST', '/api/screw/start-angle', { note: 'pad-profile test (SIM)' });
    const chip = await api(page, 'GET', '/api/pad-types/chip-0603');
    if (!['characterized', 'validated'].includes(chip.current.status)) await api(page, 'PUT', '/api/pad-types/chip-0603', { status: 'characterized' });
    await page.evaluate(() => { IL.attest.set('regulator', IL.S.status.dispense.profile.pressurePsi); IL.attest.set('paste-life', true); });
    check('(0) set-up: SIM-only shortened calibration accepted, places, blank and area set', true);

    // ---- screenshots: the nano upload preview with the profile split ---------------------------------------------------
    await openUpload(page);
    await upload(page, { name: 'NanoV3.3.kicad_pcb', mimeType: 'application/octet-stream', buffer: zlib.gunzipSync(fs.readFileSync(path.join(KICAD, 'NanoV3.3.kicad_pcb.gz'))) });
    const nano = await page.evaluate(() => ({ badges: document.querySelectorAll('#board-import-packages td.bi-pp span.pp-badge[data-reason]').length,
      rows: document.querySelectorAll('#board-import-packages tbody tr').length,
      needs: Array.from(document.querySelectorAll('li[data-code="NEEDS_PROFILE"]')).map((l) => l.textContent) }));
    check('nano preview: a profile badge on every package row and one NEEDS_PROFILE warning', nano.badges === nano.rows && nano.rows > 0 && nano.needs.length === 1, nano);
    const nanoLines = await page.evaluate(() => Array.from(document.querySelectorAll('li[data-code="NEEDS_PROFILE"] .pp-needs-w > li')).map((l) => l.textContent));
    check('nano preview: the warning names the packages one line per reason, the tip\'s last', nanoLines.length === 3 && /^(Not calibrated yet|No pad type): /.test(nanoLines[0])
      && /^Cannot be dispensed with the 22G tip: /.test(nanoLines[2]), nanoLines);
    // the line above says what the key and the warning say (the LED pads have a pad type, not calibrated yet)
    const nanoFound = await page.evaluate(() => document.querySelector('#board-import-found .bi-found__a').textContent);
    check('nano preview: "No part gets paste yet · 5 parts need a profile (10 pads)", not "need a pad type"',
      /\b5 parts need a profile \(10 pads\)/.test(nanoFound) && !/pad type/.test(nanoFound), nanoFound);
    // the profile split: the one NEEDS_PROFILE warning above the packages and their Profile column
    await shoot(page, 'nano-preview', () => page.evaluate(() => { const t = document.querySelector('#dlg-board-import .bi-wg--1') || document.getElementById('board-import-packages'); if (t) t.scrollIntoView({ block: 'start' }); }));
    await page.click('#btn-board-import-cancel').catch(() => page.keyboard.press('Escape'));
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 10000 }).catch(() => null);

    // ---- (1) upload padcal.kicad_pcb ----------------------------------------------------------------------------------
    await openUpload(page);
    await upload(page, { name: 'padcal.kicad_pcb', mimeType: 'application/octet-stream', buffer: fs.readFileSync(PADCAL) });
    const pv = await page.evaluate(() => ({
      rows: Array.from(document.querySelectorAll('#board-import-packages tbody tr')).map((tr) => [tr.dataset.package, (tr.querySelector('span.pp-badge') || {}).dataset ? tr.querySelector('span.pp-badge').dataset.reason : null]),
      needs: document.querySelectorAll('li[data-code="NEEDS_PROFILE"][data-group="1"]').length,
      noType: document.querySelectorAll('li[data-code="NO_PAD_TYPE"]').length }));
    check('(1) a profile badge with its reason on every package row', pv.rows.every((r) => !!r[1]), pv.rows);
    check('(1) exactly one NEEDS_PROFILE warning in group 1, no NO_PAD_TYPE', pv.needs === 1 && pv.noType === 0, pv);
    const name = 'padcal ' + TS;
    await page.fill('#board-import-name', name);
    await page.click('#btn-board-import-save');
    await page.waitForSelector('#dlg-board-import[data-state="saved"] #board-import-profile', { timeout: 60000 });
    const saved = await page.evaluate(() => Array.from(document.querySelectorAll('#board-import-profile button[data-action]')).map((b) => [b.dataset.action, b.dataset.group, b.textContent]));
    check('(1) after the save: Use Chip 0603 and Needs a profile… per group', saved.some((b) => b[0] === 'use' && /Chip 0603/.test(b[2])) && saved.filter((b) => b[0] === 'needs').length === 3, saved);
    const swBtn = await page.evaluateHandle(() => Array.from(document.querySelectorAll('#board-import-profile li')).find((li) => /SW_SPST/.test(li.textContent)).querySelector('button[data-action="needs"]'));
    await swBtn.click();
    await page.waitForFunction(() => /^#profile\/needs\/g[0-9a-f]{12}$/.test(location.hash), null, { timeout: 15000 });
    const gSW = await page.evaluate(() => location.hash.split('/').pop());
    check('(1) Needs a profile… goes to #profile/needs/<g> (the board file is the source)', /^g[0-9a-f]{12}$/.test(gSW), gSW);
    await page.waitForFunction(() => /^From the board /.test((document.getElementById('pt-needs-src') || {}).textContent || ''), null, { timeout: 15000 }).catch(() => null);
    check('(1) the list reads the board file before it is planned', /^From the board /.test(await page.textContent('#pt-needs-src')), await page.textContent('#pt-needs-src'));

    // ---- (2) plan it --------------------------------------------------------------------------------------------------
    const file = (await api(page, 'GET', '/api/boards')).files.find((f) => f.name && f.name.startsWith(name) || (f.file || '').includes('padcal-' + TS) || (f.file || '').includes(TS)).file;
    await planUi(page, file, { x: 200, y: 120, z: Number(bzMid.toFixed(2)), rotation: 0 });   // the board on the bed, like the blank (I4)
    await page.evaluate(() => IL.go('#run/checks'));
    await sleep(800);
    const card = await page.evaluate(() => { const it = IL.ready().items.find((i) => i.key === 'padtypes'); return { state: it.state, fixes: it.fixes.map((f) => f.label), detail: it.detail }; });
    check('(2) Run › Checks: Pad types blocked with "Needs a profile: SW_SPST_3.9x2.9mm"', card.state === 'block' && card.fixes.includes('Needs a profile: SW_SPST_3.9x2.9mm'), card);
    // the readiness head (how many of 9) when the Pad types card fits under it, else the cards from "Needs you" on
    await shoot(page, 'run-readiness', async () => {
      const fits = await page.evaluate(() => {
        const sc = document.getElementById('panel-scroll'), c = document.querySelector('.ri[data-key="padtypes"]');
        sc.scrollTop = 0;
        return !c || c.getBoundingClientRect().bottom <= Math.min(sc.getBoundingClientRect().bottom, innerHeight) - 8;
      });
      if (!fits) await toTop(page, '#chk-needs');
    });
    const dots0 = await page.evaluate(() => document.querySelectorAll('#plan-svg circle.dot').length);

    // ---- (3) Profile › Needs a profile ---------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#profile/needs'));
    await page.waitForSelector('#pt-needs:not([hidden]) #pt-needs-table tbody tr[data-group]', { timeout: 15000 });
    const list = await page.evaluate(() => ({
      rows: Array.from(document.querySelectorAll('#pt-needs-table tbody tr[data-group]')).map((tr) => [tr.dataset.reason, tr.querySelector('.pt-needs__n').textContent]),
      tip: Array.from(document.querySelectorAll('#pt-needs-tip tr[data-reason]')).map((tr) => tr.dataset.reason),
      tipOpen: document.getElementById('pt-needs-tip').open, tipText: document.getElementById('pt-needs-tip-s').textContent,
      nav: document.querySelector('#tab-btn-profile .nav__s').textContent, src: document.getElementById('pt-needs-src').textContent }));
    check('(3) three fixable rows with data-reason (J, T, Q)', list.rows.length === 3 && list.rows.every((r) => r[0] === 'no-type'), list.rows);
    check('(3) the tip details (collapsed) hold the 2 WSON groups', list.tip.length === 2 && !list.tipOpen && /22G tip/.test(list.tipText), list);
    check('(3) nav sub "Needs 3 profiles"', list.nav === 'Needs 3 profiles', list.nav);
    // the profile panel's pad-type gate counts in words: "1 of 12 pad types is allowed", "2 of 13 … are allowed"
    await page.waitForFunction(() => / pad types (is|are) allowed/.test((document.querySelector('#pt-ov-gate .pt-gate__s') || {}).textContent || ''), null, { timeout: 15000 }).catch(() => null);
    const gate3 = await page.evaluate(() => (document.querySelector('#pt-ov-gate .pt-gate__s') || {}).textContent || '');
    const gm3 = /^(\d+) of \d+ pad types (is|are) allowed on a board/.exec(gate3);
    check('(3) the pad-type gate: "is" for one type, "are" for more', !!gm3 && (gm3[1] === '1') === (gm3[2] === 'is'), gate3);
    check('(3) the source is the plan now', /^From the plan of /.test(list.src), list.src);
    await shoot(page, 'needs-list', () => toTop(page, '#pt-needs', '#pt-section'));

    // ---- (4) the SW suggestion -----------------------------------------------------------------------------------------
    await page.evaluate((g) => IL.go('#profile/needs/' + g), gSW);
    await page.waitForFunction(() => document.querySelectorAll('#pt-sugg-preview circle.pv-sugg').length > 0, null, { timeout: 15000 });
    const card4 = await page.evaluate(() => ({ text: document.getElementById('pt-sugg-text').textContent, sugg: document.querySelectorAll('#pt-sugg-preview circle.pv-sugg').length,
      dots: document.querySelectorAll('#plan-svg circle.dot').length }));
    check('(4) the suggestion: "New pad type SW_SPST_3.9x2.9mm …"', card4.text.startsWith('New pad type SW_SPST_3.9x2.9mm'), card4.text);
    check('(4) 4 suggested dots drawn on J1 (circle.pv-sugg), none as circle.dot', card4.sugg === 4, card4);
    const cap4 = await page.textContent('#pt-sugg-cap');
    check('(4) the drawing\'s caption in plain words ("4 suggested dots on J1 · dots … mm wide", no "upper size drawn")',
      /^4 suggested dots on J1 · dots [\d.–]+ mm wide/.test(cap4) && !/upper size/.test(cap4), cap4);
    check('(4) the plan\'s dot count unchanged', card4.dots === dots0 || dots0 === 0, [dots0, card4.dots]);
    const p4 = await primaries(page);
    check('(4) one filled button: the suggestion card\'s Calibrate automatically…', p4.length === 1 && p4[0] === 'btn-pt-sugg-auto', p4);
    await shoot(page, 'suggestion', () => toTop(page, '#pt-needs', '#pt-section'), true);

    // ---- (5) Use Chip 0603 on TestChip_A --------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#profile/needs'));
    await page.waitForSelector('#pt-needs-table tr[data-group] button[data-action="use"]', { timeout: 10000 });
    const p5 = await page.evaluate(() => IL.S.plan.planId);
    await page.click('#pt-needs-table tr[data-group] button[data-action="use"]');
    await page.waitForFunction((b) => IL.S.plan && IL.S.plan.planId !== b, p5, { timeout: 60000 });
    await page.waitForFunction(() => document.querySelectorAll('#pt-needs-table tbody tr[data-group]').length === 2, null, { timeout: 15000 }).catch(() => null);
    const rows5 = await page.evaluate(() => Array.from(document.querySelectorAll('#pt-needs-table tbody tr[data-group] .pt-needs__n')).map((n) => n.textContent));
    check('(5) Use Chip 0603 · plans again: a new plan is shown and the TestChip_A row is gone', !rows5.some((t) => /TestChip_A/.test(t)) && rows5.length === 2, rows5);

    // ---- (6) the pad inspector on a SW pad -------------------------------------------------------------------------------
    await page.evaluate(() => { IL.go('#run/checks'); });
    await sleep(500);
    await page.evaluate(() => IL.padInspect.open({ padId: 'J1.1' }, { bv: IL.run && IL.run.bv }));
    await page.waitForSelector('#pad-insp-profile:not([hidden]) .pp-insp', { timeout: 10000 });
    const insp = await page.evaluate(() => ({ flags: Array.from(document.querySelectorAll('#pad-insp-flags [data-flag]')).map((n) => n.dataset.flag),
      edit: document.getElementById('pad-insp-edit'), editText: document.getElementById('pad-insp-edit').textContent, editHidden: document.getElementById('pad-insp-edit').hidden }));
    check('(6) inspector: one profile notebox, no NO_PAD_TYPE flag, no "New pad type"', !insp.flags.includes('NO_PAD_TYPE') && (insp.editHidden || !/New pad type/.test(insp.editText)), insp);
    await closePanels(page);

    // ---- (7) Calibrate automatically ---------------------------------------------------------------------------------------
    await page.evaluate((g) => IL.go('#profile/needs/' + g), gSW);
    await page.waitForSelector('#btn-pt-sugg-auto:not([hidden])', { timeout: 10000 });
    await page.click('#btn-pt-sugg-auto');
    await page.waitForFunction(() => /needs you once/.test((document.getElementById('pcal-note') || {}).textContent || ''), null, { timeout: 20000 });
    const run7 = await page.evaluate(() => !!(IL.S.status.run && IL.S.status.run.running));
    check('(7) the before-start summary says it needs you once; nothing runs yet', !run7, await page.textContent('#pcal-note'));
    // the purge spots it lays (rc1 review: the row was short right after): in the note and as a row of the steps table
    const purge7 = await page.evaluate(() => ({ note: document.getElementById('pcal-note').textContent,
      row: Array.from(document.querySelectorAll('#pcal-plan tr')).map((tr) => tr.textContent).find((t) => /^Purge row/.test(t)) || null }));
    check('(7) the summary says how many purge spots it uses ("It uses n of the m free purge spots") and the steps list the purge row',
      /It\s+uses\s+\d+\s+of\s+the\s+\d+\s+free\s+purge\s+spots/.test(purge7.note) && /^Purge row\d+\s+of\s+\d+\s+free\s+spots\s+\(\d+\s+left\s+after\)/.test(purge7.row || ''), purge7);
    const xy7 = await page.evaluate(() => ({ note: document.getElementById('pcal-note').textContent,
      row: Array.from(document.querySelectorAll('#pcal-plan tr')).map((tr) => tr.textContent).find((t) => /^Test dots on the blank/.test(t)) || null }));
    check('(7) the test dots\' X and Y ranges are in mm (the summary and the steps)', /in X [\d.]+–[\d.]+\smm, Y [\d.]+–[\d.]+\smm/.test(xy7.note)
      && /in X [\d.]+–[\d.]+\smm, Y [\d.]+–[\d.]+\smm/.test(xy7.row || ''), xy7);
    await page.waitForFunction(() => !document.getElementById('btn-pcal-start').disabled, null, { timeout: 20000 }).catch(() => null);
    const why7 = await page.textContent('#pcal-why');
    check('(7) Start is not locked', !(await page.isDisabled('#btn-pcal-start')), why7);
    const p7 = await primaries(page);
    check('(7) one filled button: Calibrate', p7.length === 1 && p7[0] === 'btn-pcal-start', p7);
    await shoot(page, 'pcal-before', () => toTop(page, '#pt-needs', '#pt-section'), true);
    // the gate's reasons while the button is armed (a lock between the two presses disarms it: the second press arms again)
    await page.evaluate(() => {
      window.__ppGate = [];
      const g = IL.gate;
      IL.gate = (btn, reason, el) => { if (btn && btn.id === 'btn-pcal-start' && reason) window.__ppGate.push(reason); return g(btn, reason, el); };
    });
    const posted = page.waitForResponse((r) => /\/api\/pad-calibrations$/.test(r.url()) && r.request().method() === 'POST', { timeout: 20000 }).catch(() => null);
    await press2(page, '#btn-pcal-start');
    const r7 = await posted;
    check('(7) Start (two presses) posts the calibration: 202', !!r7 && r7.status() === 202, r7 ? [r7.status(), (await r7.text()).slice(0, 600)]
      : await page.evaluate(() => ({ btn: document.getElementById('btn-pcal-start').textContent, why: document.getElementById('pcal-why').textContent, gate: window.__ppGate.slice(-5) })));
    const j7 = await waitJob(page, (j) => j.state === 'running', 20000, 'the job to start');
    await page.waitForFunction(() => /^Pad calibration/.test(document.querySelector('#pill-engine .cell__v').textContent), null, { timeout: 60000 }).catch(() => null);
    check('(7) the strip names the pad calibration', /^Pad calibration/.test(await stripText(page)), await stripText(page));
    // at 1440 px the strip has room for the step too: the other cells' long parts go first (final gate: it read "Pad
    // calibration" alone, the step dropped with " · set 18 psi", beside 300 px of free strip)
    await page.waitForFunction(() => / · \S/.test(document.querySelector('#pill-engine .cell__v').textContent), null, { timeout: 30000 }).catch(() => null);
    const strip7 = await page.evaluate(() => ({ shown: document.querySelector('#pill-engine .cell__v').innerText, tier: document.getElementById('cells').className, w: innerWidth }));
    check('(7) 1440 px: the strip shows the step ("Pad calibration · …"), the other cells shortened first', strip7.w === 1440 && /^Pad calibration · \S/.test(strip7.shown), strip7);
    await waitJob(page, (j) => (j.steps || []).some((x) => x.key === 'ladder' && x.state !== 'todo'), 300000, 'the ladder');
    // the page draws a status a frame after it holds it: the list, not only the status, has the ladder under way
    await page.waitForFunction(() => Array.from(document.querySelectorAll('#pcal-phases li')).some((li) => li.dataset.ph === 'ladder' && li.className !== 'todo'),
      null, { timeout: 20000 }).catch(() => null);
    const ph = await page.evaluate(() => Array.from(document.querySelectorAll('#pcal-phases li')).map((li) => [li.dataset.ph, li.className]));
    check('(7) the phases advance (coupons done, ladder now or done)', ph.some((p) => p[0] === 'coupons' && p[1] === '') && ph.some((p) => p[0] === 'ladder' && p[1] !== 'todo'), ph);
    check('(7) STOP and Cancel are reachable while it runs', await page.isVisible('#btn-stop') && await page.isVisible('#btn-pcal-cancel') && await page.isEnabled('#btn-pcal-cancel'),
      { stop: await page.isVisible('#btn-stop'), cancel: await page.isVisible('#btn-pcal-cancel') });
    await shoot(page, 'job-running', () => toTop(page, '#pt-needs', '#pt-section'), true);

    // ---- (8) the readings ----------------------------------------------------------------------------------------------------
    const j8 = await waitJob(page, (j) => j.state === 'awaiting-reading' || ['failed', 'stopped', 'done'].includes(j.state), 600000, 'the readings prompt');
    check('(8) the job waits for the readings', j8.state === 'awaiting-reading', { state: j8.state, steps: j8.steps });
    await page.waitForFunction(() => /^Waiting for you · weigh/.test(document.querySelector('#pill-engine .cell__v').textContent), null, { timeout: 10000 }).catch(() => null);
    check('(8) the strip reads "Waiting for you · weigh"', /^Waiting for you · weigh/.test(await stripText(page)), await stripText(page));
    await page.reload();
    await page.waitForFunction(() => window.IL && IL.S.status && IL.padProfile, null, { timeout: 30000 });
    await page.click('#pill-engine');
    await page.waitForSelector('#pcal-ask:not([hidden]) #pcal-full-1', { timeout: 20000 });
    const cs = j8.awaiting.coupons;
    check('(8) after a reload the prompt is back with a field pair per coupon', cs.length >= 1 && (await page.$$('#pcal-coupons .pcal-coupon')).length === cs.length, cs.length);
    // its text keeps each amount with its unit on one line (final gate: "(about 0.072" ended a line, "g)" began the next)
    const ask8 = await page.textContent('#pcal-ask-text');
    check('(8) the prompt keeps "about 0.072 g" on one line (no-break space before the g)', /about [\d.]+\u00a0g\)/.test(ask8) && !/\d g(?![\w²])/.test(ask8), ask8);
    await shoot(page, 'readings-prompt', () => toTop(page, '#pt-needs', '#pt-section'), true);
    for (let i = 0; i < cs.length; i++) {
      const r = await weighCoupon(page, cs[i].place);
      await page.fill('#pcal-full-' + (i + 1), r.full.toFixed(3));
      await page.fill('#pcal-empty-' + (i + 1), r.empty.toFixed(3));
      await sleep(900);
      const echo = await page.textContent('#pcal-mg-' + (i + 1));
      check('(8) ' + cs[i].place + ': "= ' + Math.round((r.full - r.empty) * 1000) + ' mg of paste"', echo.startsWith('= ' + IL_fmt0((r.full - r.empty) * 1000) + ' mg of paste'), echo);
    }
    await page.click('#btn-pcal-readings');
    const j9 = await waitJob(page, (j) => ['done', 'failed', 'stopped', 'cancelled'].includes(j.state), 180000, 'the result');

    // ---- (9) the result ----------------------------------------------------------------------------------------------------
    await page.waitForSelector('#pcal-result:not([hidden])', { timeout: 20000 });
    const res = await page.evaluate(() => ({ t: document.getElementById('pcal-result-t').textContent, crops: Array.from(document.querySelectorAll('#pcal-crops img')).map((i) => i.complete && i.naturalWidth > 0) }));
    check('(9) the result card: "Characterized …"', /^Characterized \d\d:\d\d: /.test(res.t), res.t);
    // a number keeps its unit on its line ("9 %" was "9" at a line's end and "% more" on the next), the weighing date
    // stays whole, an amount on its target reads so (not "0 % more than"), the dot size in words (not "d95")
    const res9 = await page.evaluate(() => ({ t: document.getElementById('pcal-result-t').textContent,
      d: Array.from(document.querySelectorAll('#pcal-result .ri__d')).map((n) => n.textContent).find((x) => /^The validated half turn/.test(x)) || '',
      size: Array.from(document.querySelectorAll('#pcal-groups dt')).filter((dt) => dt.textContent === 'Dot size').map((dt) => dt.nextElementSibling.textContent) }));
    check('(9) the result: numbers keep their units (no-break space), "on the … mg target" for 0 %, the dot size in words',
      !/\d (%|mg|mm)(?![\w²])/.test(res9.t + ' ' + res9.d) && !/\b0\s%\s(more|less)/.test(res9.t) && !/\d{4}-\d\d/.test(res9.d)
        && res9.size.length > 0 && res9.size.every((x) => /^[\d.]+\smm \(95\s% of the dots under [\d.]+\smm\)$/.test(x)), res9);
    await page.waitForFunction(() => Array.from(document.querySelectorAll('#pcal-crops img')).filter((i) => i.complete && i.naturalWidth > 0).length >= 3, null, { timeout: 15000 }).catch(() => null);
    const crops = await page.evaluate(() => Array.from(document.querySelectorAll('#pcal-crops img')).filter((i) => i.complete && i.naturalWidth > 0).length);
    check('(9) at least 3 camera crops load', crops >= 3, crops);
    await page.waitForFunction(() => IL.S.plan && IL.S.status.padCalibration.result && IL.S.plan.planId === IL.S.status.padCalibration.result.newPlanId, null, { timeout: 30000 }).catch(() => null);
    check('(9) the plan made again is shown', await page.evaluate(() => IL.S.plan.planId === IL.S.status.padCalibration.result.newPlanId), j9.result && j9.result.newPlanId);
    const p9 = await primaries(page);
    check('(9) one filled button: Checks', p9.length === 1 && p9[0] === 'btn-pcal-plan', p9);
    // the purge row after the job, and the camera checks under their own title (they count the coupons' dots too)
    const r9 = await page.evaluate(() => { const row = IL.S.status.dispense.purgeRow; const pr = document.getElementById('pcal-result-purge');
      return { left: row.spotsLeft, need: IL.purgeNeed(row), purge: pr ? pr.textContent : null,
        title: (document.querySelector('#pcal-ladder-box .section-k') || document.querySelector('#pcal-ladder-box') || {}).textContent }; });
    check('(9) a purge row short for the next board: the result says to wipe and reset it, with "Wiped: reset row"; the camera checks title',
      (r9.left < r9.need ? new RegExp('^Next: wipe the purge row and reset it before the next board \\(' + r9.left + ' of \\d+ spots left, ' + r9.need + ' needed\\)\\. Wiped: reset row').test(r9.purge || '')
        : r9.purge === null) && /^Camera checks of the dots/.test(r9.title || ''), r9);
    await shoot(page, 'result', () => toTop(page, '#pt-needs', '#pt-section'), true);
    await closePanels(page);
    await page.evaluate(() => IL.go('#profile/needs'));
    await sleep(1500);
    const rows9 = await page.evaluate(() => Array.from(document.querySelectorAll('#pt-needs-table tbody tr[data-group] .pt-needs__n')).map((n) => n.textContent));
    check('(9) Needs a profile: Test_Strip left, SW and TestChip_A gone', rows9.length === 1 && /Test_Strip/.test(rows9[0]), rows9);
    // the profile panel's "Pad types in this plan" (final gate: "SW_SPST_3.9x2.9mm" ran under "2 pads", "QFN / DFN
    // perimeter pad" stood in four lines beside its count): each name clear of its count and inside its row, at 1440 and
    // 1280 px; the family tables show whole names (the new type's size was cut off: "SW_SPST_3.9x2....")
    const lay9 = [];
    await page.click('#pt-filter button[data-filter="all"]');   // every family table, the new type's among them
    await page.waitForSelector('#profiles .pt-name__t', { timeout: 15000 }).catch(() => null);
    for (const [w, h] of [[1440, 900], [1280, 800]]) {
      await page.setViewportSize({ width: w, height: h });
      await sleep(400);
      lay9.push(await page.evaluate((ww) => {
        const rows = Array.from(document.querySelectorAll('#pt-ov-plan .pt-plan__i')).map((b) => {
          const nb = b.querySelector('.pt-plan__n'), n = nb.getBoundingClientRect(), v = b.querySelector('.pt-plan__v').getBoundingClientRect(), r = b.getBoundingClientRect();
          return { name: nb.textContent, apart: n.right <= v.left + 0.5 || v.top >= n.bottom - 0.5 || v.right <= n.left + 0.5 || n.top >= v.bottom - 0.5,
            inside: n.right <= r.right + 0.5 && v.right <= r.right + 0.5, fits: nb.scrollWidth <= nb.clientWidth + 1 };
        });
        const names = Array.from(document.querySelectorAll('#profiles .pt-name__t')).filter((x) => x.getClientRects().length > 0)
          .map((x) => ({ t: x.textContent, cut: x.scrollWidth > x.clientWidth + 1 }));
        return { w: ww, rows, names };
      }, w));
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    check('(9) Profile › Pad types in this plan: every name clear of its pad count and inside its row at 1440 and 1280 px; the family tables show whole names',
      lay9.every((x) => x.rows.length > 0 && x.rows.every((r) => r.apart && r.inside && r.fits) && x.names.length > 0 && x.names.every((n) => !n.cut)), lay9);
    // a test run with fewer dots lays whole pads (the engine cuts before the pad it would split): asked to stop inside a
    // two-dot pad, Run says how many it lays (or, inside the first pad, how many to ask for at least)
    await page.evaluate(() => IL.go('#run/checks'));
    await page.waitForSelector('#btn-run-test', { state: 'visible', timeout: 15000 });
    const cut = await page.evaluate(() => { const d = IL.S.plan.dots; for (let k = 1; k < d.length; k++) if (d[k].padIndex >= 0 && d[k].padIndex === d[k - 1].padIndex) return k; return 0; });
    if (cut) {
      if (!(await page.isVisible('#run-maxdots'))) await page.click('#btn-run-test');
      await page.fill('#run-maxdots', String(cut));
      await sleep(300);
      const t9 = await page.evaluate(() => ({ scope: document.getElementById('run-scope').textContent.trim(), btn: document.getElementById('btn-run').textContent,
        err: document.getElementById('run-maxdots-err').textContent }));
      check('(9) Test run with fewer dots: ' + cut + ' asked inside a two-dot pad lays whole pads', cut > 1
        ? new RegExp('^TEST · first ' + (cut - 1) + ' of \\d+ dots \\(whole pads: ' + (cut - 1) + ' of the ' + cut + ' asked for\\)$').test(t9.scope) && /whole pads/.test(t9.btn)
        : /^A test run lays whole pads: the first pad, \S+, has \d+ dots, so ask for at least \d+$/.test(t9.err), t9);
      await page.click('#btn-run-test');   // back to the full board
    } else check('(9) Test run with fewer dots: the plan has a two-dot pad to cut inside', false, 'no two dots of one pad in a row');

    // ---- (9a) the purge row a plan of two dot sizes needs (rc1 review): the engine's I9 counts 8 to start and 5 for each
    // further dot size; the Purge row item, the strip cell and the confirm count the same (not the profile's 8). This
    // plan's own need as the engine counts it, and the review's case (10 spots, 13 needed) on a copy of it
    const pu = await page.evaluate(() => {
      const r = IL.ready(); const it = r.items.find((x) => x.key === 'purge'); const row = IL.S.status.dispense.purgeRow;
      const i9 = ((IL.S.plan.interlocks || {}).violations || []).find((v) => v.code === 'I9');
      const sizes = new Set(IL.S.plan.dots.map((d) => d.rotationDeg)).size;
      const st = JSON.parse(JSON.stringify(IL.S.status));
      Object.assign(st.dispense.purgeRow, { spots: 10, usedSpots: 0, spotsLeft: 10 });
      const two = Object.assign({}, IL.S.plan, { purgeSpotsNeeded: row.perBoard + 5 });
      const ten = IL.readiness(st, two, IL.readyCtx()).items.find((x) => x.key === 'purge');
      return { need: IL.S.plan.purgeSpotsNeeded, sizes, perBoard: row.perBoard, left: row.spotsLeft, state: it.state, detail: it.detail, i9: i9 ? i9.message : null,
        ten: { state: ten.state, detail: ten.detail } };
    });
    const extra = pu.need - pu.perBoard;
    check('(9a) the plan counts its purge spots as I9 does (' + pu.need + ' = ' + pu.perBoard + ' + ' + extra + ' for ' + pu.sizes + ' dot sizes), and the Purge row item with it',
      extra >= 0 && (pu.sizes > 1) === (extra > 0) && (pu.left < pu.need ? pu.state === 'need' && pu.detail.startsWith('Only ' + pu.left + ' unused purge spots left, ' + pu.need) && !!pu.i9
        : pu.state === 'ok' && !pu.i9), pu);
    check('(9a) a plan of two dot sizes on a 10-spot row: blocked, "this plan purges 13 (8 to start, 5 for its other dot sizes)", not "10/10 ready"',
      pu.ten.state === 'block' && pu.ten.detail.startsWith('The row has 10 spots; this plan purges ' + (pu.perBoard + 5) + ' (' + pu.perBoard + ' to start, 5 for its other dot sizes).'), pu.ten);
    await shoot(page, 'fix-purge-two-sizes-checks', () => toTop(page, '#chk-needs', null));

    // ---- (9b) Refuse flagged pads (rc1 review): "Dispense the rest" opens the switch in Setup › Settings; off, the engine
    // skips the pads it cannot dispense (WSON-8 with the 22G tip, Test_Strip with no profile yet) and runs the rest: Pad
    // types is a note that counts ready, and the run confirm names the pads skipped
    const tipOnly = await page.evaluate(() => {
      const p = JSON.parse(JSON.stringify(IL.S.plan));
      p.profileCheck.groups = p.profileCheck.groups.filter((g) => g.reason === 'tip' || g.reason === 'part' || g.reason === 'outside');
      const it = IL.readiness(IL.S.status, p, IL.readyCtx()).items.find((x) => x.key === 'padtypes');
      return { state: it.state, short: it.short, fixes: it.fixes.map((f) => [f.href || f.fix, f.label]) };
    });
    check('(9b) tip groups only: Blocked · the 22G tip, "Dispense the rest" links #setup/settings/refuse-flagged', tipOnly.state === 'block' && tipOnly.short === 'the 22G tip'
      && tipOnly.fixes[0][0] === '#setup/settings/refuse-flagged', tipOnly);
    await page.evaluate(() => IL.go('#setup/settings/refuse-flagged'));
    // focused, then scrolled into view (smoothly: the card's flash scrolls to its top first)
    await page.waitForFunction(() => { const e = document.activeElement; if (!e || e.id !== 'set-refuseflagged') return false;
      const r = e.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; }, null, { timeout: 15000 }).catch(() => null);
    const sw = await page.evaluate(() => { const e = document.getElementById('set-refuseflagged'); const r = e && e.getBoundingClientRect();
      return { focused: document.activeElement === e, checked: !!e && e.checked, inView: !!r && r.top >= 0 && r.bottom <= innerHeight,
        label: (document.querySelector('label[for="set-refuseflagged"] .tick__t') || {}).textContent }; });
    check('(9b) the link focuses Setup › Settings › Refuse flagged pads (on: "a board with pads that cannot be dispensed is refused")',
      sw.focused && sw.checked && sw.inView && sw.label === 'On: a board with pads that cannot be dispensed is refused', sw);
    await page.evaluate(() => { const e = document.getElementById('set-refuseflagged'); e.closest('.card').scrollIntoView({ block: 'center' }); });
    await shoot(page, 'fix-refuse-flagged-setting', () => page.evaluate(() => document.getElementById('set-refuseflagged').closest('.card').scrollIntoView({ block: 'center' })));
    await page.click('label[for="set-refuseflagged"]');
    await page.click('#btn-settings');
    await page.waitForFunction(() => IL.S.status.dispense.refuseFlaggedPads === false, null, { timeout: 15000 }).catch(() => null);
    await page.waitForFunction(() => !IL.S.planPending && ((IL.S.plan.interlocks || {}).notes || []).some((n) => /flagged pad\(s\) will be skipped/.test(n)), null, { timeout: 20000 }).catch(() => null);
    await api(page, 'POST', '/api/purge-row/reset', {});   // the operator wiped the purge row after the job
    await page.evaluate(() => IL.go('#run/checks'));
    await page.waitForFunction(() => { const it = IL.ready().items.find((x) => x.key === 'padtypes'); return it && it.state === 'info'; }, null, { timeout: 20000 }).catch(() => null);
    const off = await page.evaluate(() => { const r = IL.ready(); const it = r.items.find((x) => x.key === 'padtypes');
      return { state: it.state, value: it.value, detail: it.detail, verdict: r.verdict.text, canRun: r.canRun, runReason: r.runReason,
        blocked: (IL.S.plan.pads || []).filter((x) => x.blocked).length }; });
    check('(9b) off: Pad types is a note that counts ready, naming the pads skipped (WSON-8 not with the 22G tip, Test_Strip)', off.state === 'info'
      && new RegExp('^\\d+/\\d+ · ' + off.blocked + ' skipped$').test(off.value) && /^\d+ pads skipped \(Refuse flagged pads is off\): .*Test_Strip.*WSON.* not with the 22G tip\. The other \d+ are dispensed\.$/.test(off.detail)
      && !/needs a profile|22G tip/.test(off.verdict), off);
    await shoot(page, 'fix-refuse-flagged-off-checks', () => toTop(page, '#chk-needs', null));
    // the run confirm (as Records › Finish opens it: the pads left of this board) names the pads the run skips; it
    // opens also while another check waits (here the board surface), saying so
    await page.evaluate(() => { const p = IL.S.plan; IL.runFix('run-rest', null, { planId: p.planId, padIds: p.pads.filter((x) => !x.blocked).map((x) => x.padId) }); });
    await page.waitForSelector('#dlg-run #run-sensed .tick', { timeout: 10000 }).catch(() => null);
    const cf = await page.evaluate(() => ({ sensed: Array.from(document.querySelectorAll('#run-sensed .tick')).map((t) => t.textContent) }));
    check('(9b) off: the run confirm names the pads skipped by package', cf.sensed.some((t) => new RegExp('^Skips ' + off.blocked + ' pads that cannot be dispensed.*WSON').test(t)), cf);
    await shoot(page, 'fix-refuse-flagged-off-confirm', null);
    await page.click('#btn-run-cancel').catch(() => null);
    // back on for what follows
    await page.evaluate(() => IL.go('#setup/settings/refuse-flagged'));
    await page.waitForSelector('#set-refuseflagged', { state: 'visible', timeout: 10000 });
    await page.click('label[for="set-refuseflagged"]');
    await page.click('#btn-settings');
    await page.waitForFunction(() => IL.S.status.dispense.refuseFlaggedPads === true, null, { timeout: 15000 }).catch(() => null);
    await page.evaluate(() => IL.go('#profile/needs'));   // (10) starts from the Needs list
    await sleep(1500);

    // ---- (10) by hand on Test_Strip ---------------------------------------------------------------------------------------------
    await api(page, 'POST', '/api/purge-row/reset', {});   // the operator wiped the purge row after the job
    const strip = await page.evaluateHandle(() => Array.from(document.querySelectorAll('#pt-needs-table tr[data-group]')).find((tr) => /Test_Strip/.test(tr.textContent))
      .querySelector('button[data-action="manual"]'));
    await strip.click();
    await page.waitForFunction(() => location.hash.startsWith('#calibrate') && !document.getElementById('setup-guide').hidden, null, { timeout: 30000 });
    const guide = await page.textContent('#setup-guide');
    check('(10) the Calibrate guide names the saved type of the suggestion', /Test_Strip_0\.62x1\.50/.test(guide) && /quarter-turn/.test(guide), guide.slice(0, 300));
    const posted10 = page.waitForResponse((r) => /\/api\/pad-calibrations$/.test(r.url()) && r.request().method() === 'POST', { timeout: 20000 }).catch(() => null);
    await press2(page, '#btn-guide-ladder');
    const r10 = await posted10;
    check('(10) Lay the ladder (two presses) posts the by-hand ladder: 202', !!r10 && r10.status() === 202, r10 ? [r10.status(), (await r10.text()).slice(0, 600)] : 'no request');
    const j10 = await waitJob(page, (j) => j.manual === 'ladder' && ['done', 'failed', 'stopped'].includes(j.state), 300000, 'the by-hand ladder');
    // the row is there from the start ("not yet"): wait for the page to draw the ended job's (final gate: the table was
    // read a frame before that and still said "not yet" for a ladder that had passed)
    await page.waitForFunction(() => { const tr = document.querySelector('#pcal-ladder tbody tr[data-steps="400"]');
      return !!tr && !/not yet|…$/.test(tr.textContent.trim()); }, null, { timeout: 20000 }).catch(() => null);
    const lad = await page.evaluate(() => Array.from(document.querySelectorAll('#pcal-ladder tbody tr[data-steps]')).map((tr) => tr.textContent));
    // rc1 review: the job itself ended done, and the quarter turn's row has dots and a verdict (a refused or stopped
    // ladder lists its planned dose too, with no dots and "not yet")
    const q10 = (j10.ladder || []).find((x) => x.steps === 400) || {};
    check('(10) the ladder ran (' + j10.state + ') and table#pcal-ladder shows the quarter turn with its checks', j10.state === 'done' && q10.dots > 0
      && (q10.pass === true || q10.pass === false) && lad.some((t) => /^quarter/i.test(t) && !/not yet/.test(t)), { state: j10.state, q10, lad });
    await closePanels(page);
    await page.evaluate(() => IL.go('#calibrate/weigh'));
    await page.waitForFunction(() => Number(document.getElementById('wd-count').value) > 0, null, { timeout: 20000 }).catch(() => null);
    const wd = await page.evaluate(() => ({ steps: document.getElementById('wd-steps').value, count: document.getElementById('wd-count').value, type: document.getElementById('wd-type').value }));
    check('(10) Weigh dots prefilled: quarter turn, a count, the type', wd.steps === '400' && Number(wd.count) >= 30 && /test-strip/.test(wd.type), wd);

    // ---- (11) Calibrate › Pad calibration -----------------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#calibrate/padcal'));
    await page.waitForFunction(() => /mm left/.test(document.getElementById('pca-left').textContent), null, { timeout: 20000 });
    check('(11) #pca-left reads "… mm left"', /of [\d.]+ mm left/.test(await page.textContent('#pca-left')), await page.textContent('#pca-left'));
    await page.waitForFunction(() => !document.getElementById('btn-pca-wiped').disabled, null, { timeout: 10000 }).catch(() => null);
    await press2(page, '#btn-pca-wiped');
    await sleep(1200);
    const area11 = await api(page, 'GET', '/api/calibration-area');
    check('(11) Blank wiped (two presses): 0 mm used', area11.usedMm === 0, area11);

    // ---- (12) STOP at the readings prompt of the by-hand weighing ----------------------------------------------------------------
    await api(page, 'POST', '/api/purge-row/reset', {});
    await page.evaluate(() => IL.go('#calibrate/weigh'));
    await page.waitForFunction(() => !document.getElementById('btn-wd-run').disabled, null, { timeout: 20000 }).catch(() => null);
    check('(12) Lay the weigh dots is not locked', !(await page.isDisabled('#btn-wd-run')), await page.textContent('#wd-reason'));
    const posted12 = page.waitForResponse((r) => /\/api\/pad-calibrations$/.test(r.url()) && r.request().method() === 'POST', { timeout: 20000 }).catch(() => null);
    await press2(page, '#btn-wd-run');
    const r12p = await posted12;
    check('(12) Lay the weigh dots (two presses) posts the by-hand weighing: 202', !!r12p && r12p.status() === 202, r12p ? [r12p.status(), (await r12p.text()).slice(0, 600)] : 'no request');
    await waitJob(page, (j) => j.manual === 'weigh' && (j.state === 'awaiting-reading' || ['failed', 'stopped', 'done'].includes(j.state)), 300000, 'the by-hand weighing');
    await page.waitForSelector('#pcal-ask:not([hidden])', { timeout: 20000 });
    await page.keyboard.press('F9');
    const j12 = await waitJob(page, (j) => j.manual === 'weigh' && ['stopped', 'cancelled'].includes(j.state), 30000, 'the stop');
    await page.evaluate(() => IL.openView('pad-cal', { keep: true }));
    await page.waitForSelector('#pcal-result:not([hidden])', { timeout: 15000 });
    const r12 = await page.evaluate(() => ({ t: document.getElementById('pcal-result-t').textContent, weigh: !document.getElementById('btn-pcal-weigh-now').hidden }));
    check('(12) STOP at the prompt: "Stopped … the coupons still need weighing …" and Type the readings', /^Stopped \d\d:\d\d during .*the coupons still need weighing until \d\d:\d\d/.test(r12.t) && r12.weigh, [r12, j12.state]);
    // the operator connects again (a simulator's records count while it is connected) and types the readings of the
    // stopped job's coupons (POST /api/coupons/{id}/reading each)
    if (!(await status(page)).machine.enabled) await api(page, 'POST', '/api/machine/enable');
    await page.waitForSelector('#btn-pcal-weigh-now:enabled', { timeout: 30000 });
    await page.click('#btn-pcal-weigh-now');
    await page.waitForSelector('#pcal-pending:not([hidden]) .pcal-coupon--pending', { timeout: 15000 });
    const pend = await page.evaluate(() => Array.from(document.querySelectorAll('#pcal-pending button[data-record]')).map((b) => b.dataset.record));
    const places = (j12.coupons || []).filter((k) => k.recordId).map((k) => [String(k.recordId), k.place]);
    check('(12) Type the readings lists the stopped job\'s coupons', pend.length === places.length && places.every(([id]) => pend.includes(id)), { pend, places });
    // the first coupons are weighed; with two or more the last one is skipped (two presses, {skip: true}): no readings,
    // and it leaves the coupons to weigh
    const skipOne = places.length > 1 ? places[places.length - 1] : null;
    for (const [id, place] of skipOne ? places.slice(0, -1) : places) {
      const r = await weighCoupon(page, place);
      await page.fill('#pcal-pf-' + id, r.full.toFixed(3));
      await page.fill('#pcal-pe-' + id, r.empty.toFixed(3));
      const saved = page.waitForResponse((x) => x.url().endsWith('/api/coupons/' + id + '/reading'), { timeout: 20000 }).catch(() => null);
      await page.click('#pcal-pending button[data-record="' + id + '"]');
      const rr = await saved;
      check('(12) ' + place + ': its readings saved (' + Math.round((r.full - r.empty) * 1000) + ' mg)', !!rr && rr.status() === 200, rr ? [rr.status(), (await rr.text()).slice(0, 300)] : 'no request');
    }
    if (skipOne) {
      const [id, place] = skipOne;
      const sel = '#pcal-pending button[data-skip="' + id + '"]';
      // the list is drawn again once the weighed coupons leave it: press Skip on the list that stays
      const weighed = places.slice(0, -1).map(([i]) => i);
      await page.waitForFunction((ids) => ids.every((i) => !document.querySelector('#pcal-pending button[data-record="' + i + '"]')), weighed, { timeout: 15000 }).catch(() => {});
      await shoot(page, 'pending-skip', null, true);
      const skipped = page.waitForResponse((x) => x.url().endsWith('/api/coupons/' + id + '/reading'), { timeout: 20000 }).catch(() => null);
      await press2(page, sel);
      const rs = await skipped;
      const sent = rs ? rs.request().postDataJSON() : null;
      const got = rs ? await rs.json().catch(() => null) : null;
      check('(12) ' + place + ': Skip (two presses) sends {skip: true}; the answer "' + place + ': weighing skipped; wipe its … dots off before it is used again"',
        !!rs && rs.status() === 200 && !!sent && sent.skip === true && !!got && !!got.record && got.record.skipped === true
        && new RegExp('^' + place + ': weighing skipped; wipe its \\d+ \\S+ dots off before it is used again$').test(got.text), { sent, got, status: rs && rs.status() });
      await page.waitForFunction((s) => !document.querySelector(s), sel, { timeout: 15000 }).catch(() => {});
      check('(12) ' + place + ': skipped, it leaves the coupons to weigh', !(await page.$(sel)));
    }
  } catch (e) {
    check('no exception', false, String(e && e.stack || e));
  } finally {
    check('(13) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 10));
    check('(13) no native dialogs', dialogs.length === 0, dialogs);
    const failed = results.filter((r) => !r.ok);
    console.log((failed.length ? failed.length + ' FAILED of ' : 'all ') + results.length + ' checks · ' + Math.round((Date.now() - t0) / 1000) + ' s');
    fs.writeFileSync(path.join(OUT, 'pad-profile-results.json'), JSON.stringify({ results, shots, seconds: Math.round((Date.now() - t0) / 1000) }, null, 2));
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
/** Whole milligrams as the page writes them (IL.fmt with 0 decimals, a real minus). */
function IL_fmt0(n) {
  return Number(n).toFixed(0).replace(/^-/, '\u2212');
}
