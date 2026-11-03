/*
 * Browser test of the Instance Labs web UI served by an OpenPnP *simulator* instance
 * (launched by run-webui-sim-test.sh through openpnp-uinav: config copy, simulated controller,
 * simulated cameras, Xvfb). Headless Chromium via Playwright.
 *
 * Checks (UI of docs/instancelabs/UI_DESIGN.md, ids of its §16): every workspace renders, STOP is
 * visible on every workspace and scroll position, the Profile families, adding a pad type from the
 * UI, Connect + Home from the strip (Home is two-press), the dispense profile card (save, rpm
 * warning, start angle confirm), places, the needle frame from the bench relation (K), surfaces
 * (advanced editor; the board surface tied to the plan, then plan again), the purge row card (save,
 * two-press reset), the clean-pad check of a plan (two-press), a 20-dot board run through the
 * in-page run confirm (regulator line, three ticks; purge row + work in one pressure session),
 * prime and test dot (two-press), STOP mid-run of the pads left (bench F9 sequence, Recovery, Records ›
 * Finish offers only that run's pads), pressure on (G-code order), STOP (G-code order, machine stopped,
 * pressure off), the top-camera snapshot, the regulator tick expiring on a psi change and back (a tick at
 * the new psi stays), the board-surface editor after "new seat" (also seen by a second tab) and for a
 * moved board, the run confirm closing after a task its tab never saw running, STOP NOT CONFIRMED and
 * the offline band at 1024 px, then Register board (docs/instancelabs/AUTO_REGISTER_DESIGN.md §10.3: recovery without
 * Home, the Register step, a first registration that homes first with its G-code, the fiducial correction against the
 * SIM scene, a same seat, STOP mid-register and Home with N1 low (Register board and Probe the blank locked like Run until
 * then), the by-hand path with the engine's targets, a run that homes and checks Z by itself, the automatic blank probe,
 * the home setting, a fiducial not found (its fixes two by two in the narrow card) and a plan without fiducial
 * correction), what the engine refuses since the final round (OpenPnP busy, an upload reading its file: locked; a
 * clean-pad check refused at its start: its reason on the card; the profile's dose a quarter, half or whole turn), a Z
 * that moved at a home and its remedy under the same home (Z check blocker: the needle frame, the blank, the touch-off,
 * Register board), a board top too far under the needle frame for the probe, fix round 2's Calibrate › Needle touch-off
 * (the Pitch field and the engine's 400 at it, a run that records nothing with its message word for word, dots the
 * camera could not measure) and probe only for a board without fiducials (the primary, the REG refusal's fix-it, the
 * result), no native dialogs, no JS errors.
 *
 * Env: IL_API_URL (http://127.0.0.1:PORT/), PLAYWRIGHT_DIR (dir with node_modules/playwright),
 *      UINAV (uinav CLI), UINAV_SESSION, OUT_DIR (screenshots + results.json), SIM_SCENE (the session's SIM scene file).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = process.env.IL_API_URL;
const UINAV = process.env.UINAV;
const SESSION = process.env.UINAV_SESSION;
const OUT = process.env.OUT_DIR;
const SIM_SCENE = process.env.SIM_SCENE;
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail) : ''));
}

/** Simulated-controller G-code since sequence number `since` (all if undefined). */
function gcode(since) {
  const args = ['--session', SESSION, 'gcode', '--json'];
  if (since !== undefined) args.push('--since', String(since));
  return JSON.parse(execFileSync(UINAV, args, { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 }));
}
/** Sequence number of the next captured G-code line (without fetching the log: it grows with every probe). */
const gcodeNext = () => gcode(1e9).next;
const lines = (g) => g.entries.filter((e) => !e.dir || e.dir === '>').map((e) => e.line.trim());
/** The bench F9 Stop sequence (engine abort and machine.xml DISABLE_COMMAND). */
const F9 = ['M410', 'M107 P3', 'M107 P2', 'M107 P1', 'M107 P0', 'M18 B'];
/** Index of seq as one contiguous block in l, or -1. */
function block(l, seq) {
  for (let i = 0; i + seq.length <= l.length; i++) if (seq.every((x, k) => l[i + k] === x)) return i;
  return -1;
}
/** True if seq appears in l in this order (other lines may come in between). */
function inOrder(l, seq) {
  let i = 0;
  for (const x of l) if (i < seq.length && x === seq[i]) i++;
  return i === seq.length;
}
/** True if l holds seq twice, each copy in order (two senders whose lines may interleave). */
function twiceInOrder(l, seq) {
  const rest = l.slice();
  let from = 0;
  for (const x of seq) {
    const k = rest.indexOf(x, from);
    if (k < 0) return false;
    rest.splice(k, 1);
    from = k;
  }
  return inOrder(rest, seq);
}

async function textOf(page, id) {
  return (await page.textContent('#' + id)).trim();
}

/** Polls GET /api/status from Node until pred(status) is true. */
async function waitStatus(page, pred, timeout) {
  const t0 = Date.now();
  let s = null;
  while (Date.now() - t0 < timeout) {
    s = await (await page.request.get(BASE + 'api/status')).json();
    if (pred(s)) return s;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('timeout waiting for the status condition; last: ' + JSON.stringify(s && s.run));
}

async function waitText(page, id, re, timeout) {
  await page.waitForFunction(([i, src]) => { const n = document.getElementById(i); return !!n && new RegExp(src).test(n.textContent); },
    [id, re.source], { timeout });
}

/** Opens a <details> disclosure by its summary (a click on an open one would close it). */
async function openDetails(page, id) {
  if (!(await page.evaluate((i) => document.getElementById(i).open, id))) await page.click('#' + id + ' > summary');
}

/** Opens a workspace from the left navigation. */
async function nav(page, ws) {
  await page.click('.tabs [role=tab][data-tab=' + ws + ']');
}

/**
 * A two-press button (§11): the first press arms it ("Press again: <verb>", .btn--press) and sends nothing; the second,
 * at least 300 ms later, runs it. Returns the armed label. `maybe`: a button that is single-press in some states
 * (Pressure on with the needle over the waste).
 */
async function press2(page, sel, maybe) {
  await page.waitForSelector(sel + ':enabled', { timeout: 15000 });
  await page.click(sel);
  const armed = await page.waitForSelector(sel + '.btn--press', { timeout: maybe ? 800 : 3000 }).then(() => true, () => false);
  if (!armed) {
    if (maybe) return null;
    throw new Error(sel + ' did not ask for a second press');
  }
  const label = (await page.textContent(sel)).trim();
  await page.waitForTimeout(450);
  await page.click(sel);
  return label;
}

/** Run › Checks: the first n dots through the in-page run confirm (§8.1.5). Returns what the confirm showed. */
async function runFirstDots(page, n) {
  await nav(page, 'run');
  await page.evaluate(() => IL.go('#run/checks'));
  await page.waitForSelector('#btn-run', { timeout: 15000 });
  if (!(await page.isVisible('#run-maxdots'))) await page.click('#btn-run-test');
  await page.fill('#run-maxdots', String(n));
  await page.waitForSelector('#btn-run.btn--test:enabled', { timeout: 15000 });
  await page.click('#btn-run');
  await page.waitForSelector('#dlg-run', { timeout: 10000 });
  const shown = { psi: await textOf(page, 'run-confirm-psi'), test: await textOf(page, 'run-test-line'),
    goBefore: await textOf(page, 'btn-run-go'), goDisabled: await page.isDisabled('#btn-run-go') };
  for (const t of ['#run-tick-gauge', '#run-tick-tip', '#run-tick-paste', '#run-tick-hands']) if (!(await page.isChecked(t))) await page.check(t);
  await page.waitForSelector('#btn-run-go:enabled', { timeout: 10000 });
  shown.go = await textOf(page, 'btn-run-go');
  await page.click('#btn-run-go');
  await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 15000 });
  return shown;
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    // the page never asks for media; make sure a real camera could not be opened anyway
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });
  // a context of its own: a second tab of this browser (shared localStorage) is opened in it below
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(m.text()); });
  // native confirm / alert / prompt block STOP: the UI must never open one (§1)
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  try {
    const resp = await page.goto(BASE);
    check('UI served by OpenPnP', resp.ok(), resp.status());
    // a simulator only, before anything is changed or pressed (rc1 review: this test homes, runs boards and presses STOP;
    // the runner starts its own SIM session, a hand run could point it anywhere): every driver link is loopback now
    // (status.server.simulated), and the controller answers as OpenPnP's simulator once connected (below)
    const sim0 = await page.request.get(BASE + 'api/status').then((r) => r.json()).catch(() => ({}));
    check('the server is an OpenPnP simulator (status.server.simulated)', !!sim0.server && sim0.server.simulated === true, sim0.server);
    if (!sim0.server || sim0.server.simulated !== true) throw new Error('not a simulator: nothing was done');
    await waitText(page, 'pill-engine', /Ready to run|ready|Blocked|unavailable|Stopped|No plan|of 9/, 20000);
    check('board run verdict in the strip', /Ready to run|ready|Blocked|unavailable|Stopped|No plan|of 9/.test(await textOf(page, 'pill-engine')),
      await textOf(page, 'pill-engine'));
    await page.evaluate(() => { if (document.activeElement) document.activeElement.blur(); });
    await page.keyboard.press('Tab');
    const firstTab = await page.evaluate(() => document.activeElement && document.activeElement.id);
    check('STOP is the first Tab stop', firstTab === 'btn-stop', firstTab);

    // ---- workspaces render, STOP always visible -----------------------------------------------
    const tabs = [['run', '#btn-plan'], ['setup', '#pr-state'], ['calibrate', '#cal-history-note'], ['profile', '#pf-card'],
      ['profile', '#profiles .family'], ['records', '#records-runs']];
    for (const [tab, sel] of tabs) {
      await nav(page, tab);
      await page.waitForSelector(sel, { state: 'visible', timeout: 15000 });
      check('workspace ' + tab + ' renders ' + sel, await page.isVisible(sel));
      check('STOP visible on ' + tab, await page.isVisible('[data-testid=stop]'));
      await page.screenshot({ path: path.join(OUT, 'tab-' + tab + '.png') });
    }
    // the page never scrolls: #main scrolls by itself and the strip with STOP stays put
    await page.evaluate(() => { document.getElementById('main').scrollTop = 3000; });
    await page.mouse.wheel(0, 3000);
    const box = await page.locator('[data-testid=stop]').boundingBox();
    check('STOP stays on screen after scrolling', box && box.y >= 0 && box.y < 120 && box.height >= 79, box);

    // ---- STOP keys, the offline state and STOP NOT CONFIRMED (§5, §15, §18) ------------------------
    // a second page whose /api/stop is answered by a stub (nothing reaches OpenPnP; its recovery is this tab's only)
    {
      const aux = await browser.newPage({ viewport: { width: 1440, height: 900 } });
      aux.on('pageerror', (e) => jsErrors.push('aux: ' + e));
      aux.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
      let stops = 0;
      let delays = [];   // ms per request from now on (the first slower than the page's 2 s STOP timeout)
      const answer = { stopped: true, actions: ['stub: nothing sent'] };
      await aux.route('**/api/stop', async (route) => {
        stops++;
        const ms = delays.shift() || 0;
        if (ms) await new Promise((r) => setTimeout(r, ms));
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(answer) }).catch(() => {});
      });
      await aux.goto(BASE + '#records');
      await waitText(aux, 'pill-engine', /\S/, 20000);
      await aux.focus('#btn-stop');
      await aux.keyboard.press('Space');
      await aux.keyboard.press('Escape');
      await aux.waitForTimeout(600);
      check('Space and Esc on the focused STOP send nothing', stops === 0, stops);
      await aux.keyboard.press('Enter');
      await aux.waitForTimeout(800);
      check('Enter on the focused STOP stops (one request)', stops === 1, stops);
      // STOP NOT CONFIRMED: the first answer takes longer than 2 s; the band says so, retries go out every second,
      // and "STOP confirmed … after n tries" stays up (a late answer of an older request does not hide it)
      delays = [2600, 1500];   // no answer in 2 s; the immediate retry answers after the next 1 s retry did
      const before = stops;
      await aux.click('[data-testid=stop]');
      await waitText(aux, 'stop-unconfirmed', /STOP NOT CONFIRMED/, 5000);
      const bandBox = await aux.locator('#stop-unconfirmed').boundingBox();
      const mainBox = await aux.locator('#main').boundingBox();
      check('STOP NOT CONFIRMED band under the strip pushes the page down (covers nothing)', bandBox && mainBox && mainBox.y >= bandBox.y + bandBox.height - 1,
        { bandBox, mainBox });
      await waitText(aux, 'stop-unconfirmed', /STOP confirmed \d\d:\d\d:\d\d after \d+ tries/, 6000);
      await aux.waitForTimeout(1500);
      check('"STOP confirmed after n tries" stays visible', await aux.isVisible('#stop-unconfirmed')
        && /STOP confirmed/.test(await textOf(aux, 'stop-unconfirmed')), await textOf(aux, 'stop-unconfirmed'));
      check('STOP re-sent until one answer arrived', stops - before >= 2, stops - before);
      // offline: no answer to /api/status for 3 s: band, everything but STOP inert, an open dialog too
      await aux.keyboard.press('?');
      await aux.waitForSelector('#dlg-keys', { timeout: 5000 });
      await aux.route('**/api/status', (route) => route.abort());
      await aux.waitForSelector('#msg-area [data-msg=offline]', { timeout: 8000 });
      const inert = await aux.evaluate(() => ({ ws: document.getElementById('ws-records').inert, scrim: document.getElementById('dlg-keys-scrim').inert,
        stop: document.getElementById('btn-stop').closest('[inert]') === null, cell: document.querySelector('#pill-pressure .cell__v').textContent }));
      check('offline: the band shows, workspace and open dialog are inert, STOP is not', inert.ws && inert.scrim && inert.stop, inert);
      check('offline: strip values read Unknown', /Unknown/.test(inert.cell), inert.cell);
      await aux.unroute('**/api/status');
      await aux.waitForSelector('#msg-area [data-msg=offline]', { state: 'detached', timeout: 8000 });
      check('back online: the band goes', true);
      // below 1100 px the page itself scrolls: STOP NOT CONFIRMED and the offline band both stay in view under the
      // strip, the offline band below the STOP band (not under it)
      await aux.keyboard.press('Escape');
      await aux.waitForSelector('#dlg-keys', { state: 'detached', timeout: 5000 });
      await aux.setViewportSize({ width: 1024, height: 768 });
      await aux.evaluate(() => IL.go('#setup'));
      await aux.waitForSelector('#card-surfaces', { state: 'visible', timeout: 10000 });
      // the page bands live in the main column: scrolled within it (the panel follows it below)
      await aux.evaluate(() => window.scrollTo(0, Math.min(1200, document.getElementById('main').getBoundingClientRect().bottom + window.scrollY - 900)));
      await aux.route('**/api/status', (route) => route.abort());
      await aux.waitForSelector('#msg-area [data-msg=offline]', { timeout: 8000 });
      delays = [2600, 1500];
      await aux.click('[data-testid=stop]');
      await waitText(aux, 'stop-unconfirmed', /STOP NOT CONFIRMED/, 5000);
      await aux.waitForTimeout(200);
      const bands = await aux.evaluate(() => {
        const b = document.getElementById('stop-unconfirmed').getBoundingClientRect();
        const a = document.querySelector('#msg-area [data-msg=offline]').getBoundingClientRect();
        const hit = document.elementFromPoint(a.left + a.width / 2, a.top + a.height / 2);
        return { scrollY: window.scrollY, band: [b.top, b.bottom], offline: [a.top, a.bottom], offlineOnTop: !!(hit && hit.closest('[data-msg=offline]')) };
      });
      check('1024 px, scrolled: STOP NOT CONFIRMED under the strip, the offline band below it in view',
        bands.scrollY > 0 && bands.band[0] === 80 && bands.offline[0] >= bands.band[1] - 1 && bands.offline[1] <= 768 && bands.offlineOnTop, bands);
      await aux.screenshot({ path: path.join(OUT, 'bands-1024.png') });
      await waitText(aux, 'stop-unconfirmed', /STOP confirmed/, 6000);
      await aux.unroute('**/api/status');
      await aux.close();
    }

    await nav(page, 'profile');
    await page.waitForSelector('#profiles .family', { state: 'visible', timeout: 15000 });
    const fams = await page.$$eval('#profiles .family h2', (els) => els.map((e) => e.textContent));
    const want = ['Chip (2-terminal SMD)', 'Small-outline discretes', 'Leaded ICs', 'No-lead ICs', 'Custom'];
    check('Profiles shows the five families in order', JSON.stringify(fams.slice(0, 5)) === JSON.stringify(want), fams);
    check('0603 row with status', /not started|provisional|characterized|validated/.test(await page.textContent('tr[data-key="chip-0603"]')),
      await page.textContent('tr[data-key="chip-0603"]'));

    // ---- add a pad type from the UI ---------------------------------------------------------
    await page.click('[data-testid=new-pad-type]');
    await page.waitForSelector('#dlg-padtype[open]');
    await page.selectOption('#pt-family', 'Chip (2-terminal SMD)');
    await page.fill('#pt-name', 'PW 0201 test');
    await page.fill('#pt-w', '0.30');
    await page.fill('#pt-h', '0.35');
    await page.selectOption('#pt-layout', 'two-terminal');
    await page.fill('#pt-pitch', '0.60');
    await page.fill('#pt-notes', 'added by the Playwright sim test');
    await page.click('#pt-save');
    await page.waitForSelector('tr[data-key="chip-pw-0201-test"]', { timeout: 15000 });
    const row = await page.textContent('tr[data-key="chip-pw-0201-test"]');
    check('new pad type appears in Chip family', row.includes('PW 0201 test') && row.includes('not started'), row);
    const api = await page.request.get(BASE + 'api/pad-types/chip-pw-0201-test');
    const pt = await api.json();
    check('new pad type stored as version 1', api.ok() && pt.current.version === 1 && pt.current.padWMm === 0.3, pt.current);
    await page.screenshot({ path: path.join(OUT, 'profiles-after-add.png'), fullPage: true });

    // ---- Connect + Home from the strip ---------------------------------------------------------
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    check('Connect connects (sim)', true);
    const confirmed = await waitStatus(page, (x) => x.server && x.server.simulatorConfirmed === true, 15000).then(() => true, () => false);
    check('the controller answered as OpenPnP\'s simulator (status.server.simulatorConfirmed) before the first motion', confirmed);
    if (!confirmed) throw new Error('the controller is not the simulator: stopped before Home');
    const homeLabel = await press2(page, '#btn-home');
    check('Home asks for a second press', /Press again: home/i.test(homeLabel), homeLabel);
    await waitText(page, 'pill-homed', /^Homed$/, 120000);
    check('Home homes (sim)', true);

    // ---- dispense profile card: save, rpm warning, start angle for this controller connection ---
    await nav(page, 'profile');
    await page.waitForFunction(() => document.getElementById('pf-turn').options.length > 0, null, { timeout: 10000 });
    const turns = await page.$$eval('#pf-turn option', (os) => os.map((o) => o.value));
    check('profile: screw turn offers a quarter, half or whole turn only (400, 800, 1600 steps)', turns.join(',') === '400,800,1600', turns);
    // a profile stored before that rule (two turns) shows its value with the engine's reason at the field; Save waits
    await page.evaluate(() => {
      const sel = document.getElementById('pf-turn');
      sel.appendChild(Object.assign(document.createElement('option'), { value: '3200', textContent: '2 turns · 3200 steps' }));
      sel.value = '3200';
      for (const t of ['input', 'change']) sel.dispatchEvent(new Event(t, { bubbles: true }));
    });
    await page.waitForFunction(() => !document.getElementById('pf-turn-err').hidden, null, { timeout: 5000 }).catch(() => {});
    const twoTurns = await page.evaluate(() => ({ err: document.getElementById('pf-turn-err').textContent, save: document.getElementById('btn-pf-save').disabled }));
    check('profile: a two-turn dose is refused at the field, as the engine refuses it (Save locked)',
      /^Must be 400, 800 or 1600 steps \(a quarter, half or whole turn\)/.test(twoTurns.err) && twoTurns.save, twoTurns);
    await page.fill('#pf-rpm', '200');
    check('profile: rpm above 180 warns', await page.isVisible('#pf-rpm-warn'));
    await page.fill('#pf-rpm', '180');
    check('profile: rpm 180 does not warn', !(await page.isVisible('#pf-rpm-warn')));
    await page.fill('#pf-psi', '18');
    await page.selectOption('#pf-turn', '800');
    await page.fill('#pf-suck', '10');
    await page.fill('#pf-dwell', '260');
    await page.fill('#pf-gap', '0.10');
    await page.fill('#pf-accel', '20000');
    await page.fill('#pf-purge', '8');
    await page.waitForSelector('#btn-pf-save:enabled', { timeout: 5000 }).catch(() => {});
    if (await page.isEnabled('#btn-pf-save')) await page.click('#btn-pf-save');   // nothing to save when it already matched
    const sp = await waitStatus(page, (s) => s.dispense.profile.dwellMs === 260, 10000);
    check('profile card saves a partial profile (dwell 260 ms, the rest kept)', sp.dispense.profile.netStepsPerDot === 800
      && sp.dispense.profile.purgeDots === 8 && sp.dispense.profile.screwRpm === 180 && sp.dispense.profile.problems.length === 0,
      sp.dispense.profile);
    await waitText(page, 'pf-turn-info', /net 180\.18° per dot/, 10000);
    check('profile: forward/net turn shown', /forward 190\.09°/.test(await textOf(page, 'pf-turn-info')), await textOf(page, 'pf-turn-info'));
    // I11: "Controller stayed on" when an angle was set before; an angle never set is measured and set in Calibrate
    if (await page.isVisible('#btn-pf-angle-confirm')) {
      await page.click('#btn-pf-angle-confirm');
    } else {
      check('start angle never set: Profile offers only "Measure the angle"', /Measure the angle/.test(await textOf(page, 'btn-pf-angle-measure')));
      await page.click('#btn-pf-angle-measure');
      await page.waitForSelector('#pf-angle', { state: 'visible', timeout: 10000 });
      await page.fill('#pf-angle', '298');
      await page.click('#btn-pf-angle-set');
      await nav(page, 'profile');
    }
    const sa = await waitStatus(page, (s) => !s.dispense.profile.startAngleNeedsConfirm && s.dispense.profile.startAngleSetAtMs, 10000);
    check('start angle confirmed after Start (I11)', sa.dispense.profile.startAngleSetAtMs >= sa.dispense.profile.controllerConnectedAtMs,
      sa.dispense.profile);
    // the page shows it at its next status poll
    check('start angle warning hidden once confirmed',
      await page.waitForSelector('#pf-angle-warn', { state: 'hidden', timeout: 5000 }).then(() => true, () => false));
    await page.screenshot({ path: path.join(OUT, 'profiles-dispense-profile.png'), fullPage: true });

    // ---- regulator attestation (§9.4): a psi change expires it for good, also when the psi is set back --------
    {
      const regState = () => page.evaluate(() => IL.ready().items.find((x) => x.key === 'regulator').state);
      const psi0 = await page.evaluate(() => IL.S.status.dispense.profile.pressurePsi);
      // a profile write names the mounted tip setup it was made for (PROFILE_CALIBRATION_DESIGN.md §22.2)
      const tipKey = () => page.evaluate(() => IL.S.status.dispense.tip.key);
      await page.evaluate(() => IL.attest.set('regulator', IL.S.status.dispense.profile.pressurePsi));
      check('regulator: the gauge tick counts (you)', (await regState()) === 'you', await regState());
      for (const v of [psi0 - 1, psi0]) {
        const r = await page.request.put(BASE + 'api/config', { data: { profile: { pressurePsi: v }, forTip: await tipKey() } });
        check('regulator: profile saved at ' + v + ' psi', r.ok(), r.status());
        await page.waitForFunction((x) => IL.S.status.dispense.profile.pressurePsi === x, v, { timeout: 10000 });
      }
      check('regulator: a psi change and back expires the gauge tick (read the gauge again)', (await regState()) === 'need'
        && !(await page.evaluate(() => IL.attest.get('regulator'))), await regState());
      // a tick already at the new psi (another tab read the gauge before this one saw the change) stays
      for (const v of [psi0 - 1, psi0]) {
        await page.evaluate((x) => IL.attest.set('regulator', x), v);
        await page.request.put(BASE + 'api/config', { data: { profile: { pressurePsi: v }, forTip: await tipKey() } });
        await page.waitForFunction((x) => IL.S.status.dispense.profile.pressurePsi === x, v, { timeout: 10000 });
        check('regulator: a tick made at the new ' + v + ' psi before this tab saw the change stays', (await regState()) === 'you', await regState());
      }
      await page.evaluate(() => IL.attest.clear('regulator'));
    }

    // ---- set up a board run: waste place, needle frame, blank surface, purge row, settings, 0603 --
    await nav(page, 'setup');
    await page.waitForSelector('tr[data-place="waste"] input[data-axis=x]');
    const waste = page.locator('tr[data-place="waste"]');
    await waste.locator('input[data-axis=x]').fill('100');
    await waste.locator('input[data-axis=y]').fill('120');
    await waste.locator('input[data-axis=z]').fill('10');
    await waste.locator('button[data-action=save]').click();
    await waitStatus(page, (s) => s.dispense.locations.waste && s.dispense.locations.waste.x === 100, 10000);
    check('waste place saved from the Places table', true);
    // I4 needle Z frame from the bench relation: needle contact raw Z = K - N1 contact Z
    await page.fill('#anchor-n1', '10');
    await page.fill('#anchor-k', '65.59');
    await page.click('#btn-anchor-k');
    const an = await waitStatus(page, (s) => s.dispense.needle.anchored && s.dispense.needle.anchor.rawSumMm === 65.59, 10000);
    check('needle Z frame from K: offset -2.59 mm', Math.abs(an.dispense.needle.anchorOffsetMm + 2.59) < 1e-6, an.dispense.needle);
    await waitText(page, 'btn-needle-offset', /Confirm\s+−2\.590 mm/, 5000).catch(() => {});
    check('needle confirm names the frame offset', /Confirm\s+−2\.590 mm/.test(await textOf(page, 'btn-needle-offset')),
      await textOf(page, 'btn-needle-offset'));
    // the manual offset sits behind "Other offset…" (§8.2)
    await openDetails(page, 'needle-other');
    await page.fill('#needle-offset', '-2.59');
    await page.click('#btn-needle-offset');
    // the offset typed equals the frame offset: the card says "Offset confirmed" (a measured one: "Touched off")
    await waitText(page, 'needle-state', /^(Touched off|Offset confirmed)/, 10000);
    check('manual needle offset (after the frame) clears I4', true);
    // the blank carries the purge row (advanced editor: paste points)
    await openDetails(page, 'surf-advanced');
    await page.selectOption('#surf-name', 'blank');
    await page.fill('#surf-points', '[[60,30,10],[220,30,10],[60,60,10],[220,60,10.01]]');
    await page.click('#btn-surf-save');
    await waitStatus(page, (s) => s.dispense.surfaces.blank && s.dispense.surfaces.blank.plane, 10000);
    check('blank surface saved from the Surfaces card', true);
    // purge row card: 48 spots at 2.5 mm on the blank, then "wiped: reset" (confirm accepted)
    await page.waitForFunction(() => [...document.getElementById('pr-surface').options].some((o) => o.value === 'blank'), null,
      { timeout: 10000 });
    await page.selectOption('#pr-surface', 'blank');
    await page.fill('#pr-x0', '70');
    await page.fill('#pr-y0', '45');
    await page.fill('#pr-pitchx', '2.5');
    await page.fill('#pr-pitchy', '0');
    await page.fill('#pr-spots', '48');
    await page.click('#btn-pr-save');
    const pr = await waitStatus(page, (s) => s.dispense.purgeRow.defined && s.dispense.purgeRow.x0Mm === 70, 10000);
    check('purge row saved from its card', pr.dispense.purgeRow.spots === 48 && pr.dispense.purgeRow.surfaceProbed, pr.dispense.purgeRow);
    // a row that moved while spots were used asks in-page whether the new row is clean
    if (await page.waitForSelector('#dlg-purge-moved', { timeout: 1500 }).then(() => true, () => false)) {
      await page.click('#dlg-purge-moved button:has-text("No, keep the count")');
    }
    const resetLabel = await press2(page, '#btn-pr-reset');
    check('purge row reset asks for a second press', /Press again: reset row/.test(resetLabel), resetLabel);
    const prr = await waitStatus(page, (s) => s.dispense.purgeRow.usedSpots === 0 && s.dispense.purgeRow.resetAtMs, 10000);
    check('purge row reset: 6 boards left', prr.dispense.purgeRow.boardsLeft === 6, prr.dispense.purgeRow);
    // simulated camera: a static image, so the run's clean-pad check (I10) would judge that image
    if (await page.isChecked('#set-clean')) {
      await page.uncheck('#set-clean');
      await page.click('#btn-settings');
      await waitText(page, 'toast', /Settings saved/, 10000);
    }
    const sc = await (await page.request.get(BASE + 'api/config')).json();
    check('clean-pad check switched off from Settings', sc.cleanPadCheck.enabled === false, sc.cleanPadCheck);
    await nav(page, 'profile');
    await page.click('tr[data-key="chip-0603"] button:text-is("Edit")');
    await page.waitForSelector('#dlg-padtype[open]');
    await page.selectOption('#pt-status', 'characterized');
    await page.click('#pt-save');
    await page.waitForSelector('tr[data-key="chip-0603"] .badge.characterized', { timeout: 10000 });
    check('0603 set to characterized from Profiles > Edit (version 2)',
      (await page.textContent('tr[data-key="chip-0603"]')).includes('v2'));

    // ---- plan the real test board (copied into the sim config) --------------------------------
    await nav(page, 'run');
    await page.evaluate(() => IL.go('#run/board'));
    await page.waitForFunction(() => [...document.querySelectorAll('#board-select option')].some((o) => /^file:/.test(o.value)), null,
      { timeout: 15000 });
    const opts = await page.$$eval('#board-select option', (os) => os.map((o) => ({ v: o.value, t: o.textContent })));
    const ftp = opts.find((o) => /\/00-ftp\.board\.xml$/.test(o.v));
    check('test board ftp.board.xml listed', !!ftp, opts);
    await page.selectOption('#board-select', ftp.v);
    await page.fill('#board-x', '120');
    await page.fill('#board-y', '120');
    await page.fill('#board-z', '10');
    await page.fill('#board-rot', '0');
    await page.click('#btn-plan');
    await page.waitForSelector('#plan-svg circle.dot', { timeout: 30000 });
    const nPads = await page.$$eval('#plan-svg rect.pad', (e) => e.length);
    const nDots = await page.$$eval('#plan-svg circle.dot', (e) => e.length);
    check('plan preview: 160 pads drawn', nPads === 160, nPads);
    check('plan preview: 160 dots drawn', nDots === 160, nDots);
    // I4 refuses an untied board surface: Run opens Probe; save the surface with the plan shown, then plan again (§16.8)
    await waitText(page, 'plan-chip', /Plan [0-9a-f]{4}/, 10000);
    const step = await page.evaluate(() => [document.getElementById('panel').dataset.view, IL.ready().items.find((x) => x.key === 'surface').ready]);
    check('a plan without this seat\'s surface opens Probe (else Checks)', step[0] === (step[1] ? 'checks' : 'probe'), step);
    const planBefore = await page.evaluate(() => IL.S.plan.planId);
    await nav(page, 'setup');
    await openDetails(page, 'surf-advanced');
    await page.waitForSelector('#surf-name', { state: 'visible' });
    await page.selectOption('#surf-name', 'board');
    await page.fill('#surf-points', '[[110,110,10],[205,110,10],[110,205,10],[205,205,10.01]]');
    await page.click('#btn-surf-save');
    const bs = await waitStatus(page, (s) => s.dispense.surfaces && s.dispense.surfaces.board && s.dispense.surfaces.board.plane
      && s.dispense.surfaces.board.boundToBoard, 10000);
    check('board surface saved with the plan shown (tied)', !!bs.dispense.surfaces.board.boundToBoard, bs.dispense.surfaces.board);
    await nav(page, 'run');
    await page.waitForSelector('#btn-plan-again:enabled', { timeout: 10000 });
    await page.click('#btn-plan-again');
    await page.waitForFunction((id) => IL.S.plan && IL.S.plan.planId !== id, planBefore, { timeout: 30000 });
    await page.waitForSelector('#plan-interlocks', { state: 'visible', timeout: 15000 });
    await waitText(page, 'plan-interlocks', /All interlocks pass|interlocks:/, 15000);
    check('plan passes the interlocks', /All interlocks pass/.test(await textOf(page, 'plan-interlocks')), await textOf(page, 'plan-interlocks'));
    check('plan again shows the Checks step', await page.evaluate(() => document.getElementById('panel').dataset.view) === 'checks');
    // a re-seated board: Setup's advanced editor never offers the old seat's board points for a one-press re-tie, and
    // the answer counts in another tab of this browser at once
    {
      const twin = await context.newPage();
      twin.on('pageerror', (e) => jsErrors.push('twin: ' + e));
      await twin.goto(BASE + '#run');
      const twinSurface = () => twin.evaluate(() => { const it = IL.S.plan && IL.ready().items.find((x) => x.key === 'surface'); return it ? it.state + ':' + (it.seat || '') : null; });
      await twin.waitForFunction(() => IL.S.plan && IL.ready().items.find((x) => x.key === 'surface').state === 'ok', null, { timeout: 20000 });
      await nav(page, 'setup');
      await openDetails(page, 'surf-advanced');
      await page.selectOption('#surf-name', 'board');
      const offered = await page.waitForFunction(() => document.getElementById('surf-points').value.length > 0
        && !document.getElementById('btn-surf-save').disabled, null, { timeout: 5000 }).then(() => true, () => false);
      check('tied board points are offered in the advanced editor', offered);
      await page.evaluate(() => IL.setNewSeat(Date.now()));
      const why = await page.waitForFunction(() => { const b = document.getElementById('btn-surf-save');
        return document.getElementById('surf-points').value === '' && b.disabled ? b.title : null; }, null, { timeout: 5000 })
        .then((h) => h.jsonValue(), () => null);
      check("new seat: the old seat's board points are not offered, Save waits for new ones", /another seat/.test(why || ''),
        await page.evaluate(() => ({ pts: document.getElementById('surf-points').value, title: document.getElementById('btn-surf-save').title })));
      const twinNeeds = await twin.waitForFunction(() => { const it = IL.ready().items.find((x) => x.key === 'surface'); return it.state === 'need' && it.seat === 'new'; },
        null, { timeout: 5000 }).then(() => true, () => false);
      check('new seat answered in one tab: the other tab of this browser needs a probe too', twinNeeds, await twinSurface());
      await page.evaluate(() => IL.setNewSeat(null));
      await twin.close();
      await nav(page, 'run');
      await page.waitForFunction(() => IL.ready().items.find((x) => x.key === 'surface').state === 'ok', null, { timeout: 5000 });
    }
    // a moved board (planned further in X, its last dots past the old seat's surface): the item blocks on the dots
    // outside and still names the other seat, the editor does not offer the old seat's points; planned back at the
    // probed seat, the surface holds
    {
      const surfItem = () => page.evaluate(() => { const it = IL.ready().items.find((x) => x.key === 'surface'); return { state: it.state, seat: it.seat || null, detail: it.detail }; });
      const planAt = async (x) => {
        const id = await page.evaluate(() => IL.S.plan.planId);
        await page.evaluate(() => IL.go('#run/board'));
        await page.waitForSelector('#board-x', { state: 'visible', timeout: 10000 });
        await page.fill('#board-x', String(x));
        await page.click('#btn-plan');
        await page.waitForFunction((pid) => IL.S.plan && IL.S.plan.planId !== pid, id, { timeout: 30000 });
      };
      const shift = await page.evaluate(() => {
        const sf = IL.S.status.dispense.surfaces.board, pts = sf.points.map((q) => (Array.isArray(q) ? q[0] : q.x));
        return Math.ceil(Math.max(...pts) + (IL.S.config && IL.S.config.surfaceMarginMm || 3.5) + 5 - Math.max(...IL.S.plan.dots.map((d) => d.x)));
      });
      await planAt(120 + Math.max(shift, 5));
      await page.waitForFunction(() => IL.ready().items.find((x) => x.key === 'surface').state === 'block', null, { timeout: 10000 })
        .catch(() => {});
      const moved = await surfItem();
      check('moved board: the surface item blocks and names the other seat', moved.state === 'block' && moved.seat === 'other'
        && /outside the probed/.test(moved.detail) && /another seat/.test(moved.detail), moved);
      await nav(page, 'setup');
      await openDetails(page, 'surf-advanced');
      await page.selectOption('#surf-name', 'board');
      const why = await page.waitForFunction(() => { const b = document.getElementById('btn-surf-save');
        return document.getElementById('surf-points').value === '' && b.disabled ? b.title : null; }, null, { timeout: 5000 })
        .then((h) => h.jsonValue(), () => null);
      check("moved board: the old seat's board points are not offered, Save waits for new ones", /another seat/.test(why || ''),
        await page.evaluate(() => ({ pts: document.getElementById('surf-points').value, title: document.getElementById('btn-surf-save').title })));
      await nav(page, 'run');
      await planAt(120);
      await page.waitForFunction(() => IL.ready().items.find((x) => x.key === 'surface').state === 'ok', null, { timeout: 10000 })
        .catch(() => {});
      check('planned back at the probed seat: the board surface holds', (await surfItem()).state === 'ok', await surfItem());
      await waitText(page, 'plan-interlocks', /All interlocks pass/, 15000).catch(() => {});
    }
    const summary = await page.textContent('#plan-summary');
    check('plan shows a DPH estimate (dots only)', /DPH estimate[\s\S]*?[0-9][0-9,]*[\s\S]*?dots only/.test(summary), summary);
    const recipe = await page.getAttribute('#ws-run .recipe', 'aria-label');
    check('plan shows the profile token', recipe === '18 psi · ½ turn · dwell 260 ms · gap 0.10 mm · purge 8', recipe);
    check('no calibration row any more', !/Calibration/.test(summary), summary);
    await page.screenshot({ path: path.join(OUT, 'run-plan.png') });

    // ---- clean-pad check of the plan (top camera only; the sim camera is a static image) ------
    let g0 = gcode().next;
    const ccLabel = await press2(page, '#btn-clean-check');
    check('clean-pad check asks for a second press', /Press again: check pads/.test(ccLabel), ccLabel);
    await waitStatus(page, (s) => s.run && s.run.label === 'clean-pad check' && !s.run.running, 600000);
    await waitText(page, 'clean-result', /pads are clean|pads clean|No pad was checked/, 20000);
    const cleanText = await textOf(page, 'clean-result');
    check('clean-pad check result shown', /pads are clean|pads clean/.test(cleanText), cleanText);
    const planNow = await (await page.request.get(BASE + 'api/plan/' + (await page.evaluate(() => IL.S.plan.planId)))).json();
    check('clean-pad check kept with the plan (160 targets)', planNow.cleanCheck && planNow.cleanCheck.targets === 160,
      planNow.cleanCheck && { targets: planNow.cleanCheck.targets, ok: planNow.cleanCheck.ok });
    const nDirty = await page.$$eval('#plan-svg rect.pad.dirty', (e) => e.length);
    // the sim camera is a static picture: no pad reads clean, which is "the board position looks wrong", not paste
    const posWrong = planNow.cleanCheck.targets > 0 && !planNow.cleanCheck.pads.some((x) => x.clean);
    const legend = await textOf(page, 'run-legend');
    if (posWrong) check('0 clean pads: no pad painted as paste, the legend says the position looks wrong', nDirty === 0 && /board position looks wrong/.test(legend), { nDirty, legend });
    else check('dirty pads marked in the preview', nDirty === planNow.cleanCheck.dirtyPadIds.length, nDirty);
    let l = lines(gcode(g0));
    check('clean-pad check: no pressure, no screw', !l.some((x) => /^M106|^G1 B/.test(x)), l.slice(0, 30));
    await page.screenshot({ path: path.join(OUT, 'run-clean-check.png') });

    // ---- run the first 20 dots through the in-page run confirm --------------------------------
    g0 = gcode().next;
    const conf = await runFirstDots(page, 20);
    check('run confirm names the regulator pressure and the profile', /^Set the regulator to 18 psi \(profile .+\)$/.test(conf.psi), conf.psi);
    check('run confirm repeats the test limit', /TEST: only the first 20 of 160 dots/.test(conf.test), conf.test);
    check('run confirm: Run is locked until every tick is set', conf.goDisabled && /Tick \d more to run/.test(conf.goBefore), conf);
    check('run confirm: Run names the dots', /^Run 20 dots/.test(conf.go), conf.go);
    // the clean-pad check before was a task too: wait for this board run to end, then for the page to show it
    await waitStatus(page, (s) => s.run && s.run.label === 'board' && !s.run.running, 240000);
    await page.waitForSelector('#run-live[data-label=board][data-status]:not([data-status=RUNNING])', { state: 'attached', timeout: 10000 });
    const liveStatus = await page.getAttribute('#run-live', 'data-status');
    check('20-dot run finished OK', liveStatus === 'OK', liveStatus);
    const doneDots = await page.$$eval('#plan-svg circle.dot.done', (e) => e.length);
    check('20 dots marked done in the preview', doneDots === 20, doneDots);
    // the Result step's tiles keep a label with its number on one line (final gate: "bench" ended a line, "5,490" began
    // the next)
    await page.waitForSelector('.result-tiles .mini__s', { timeout: 10000 }).catch(() => {});
    const subs = await page.evaluate(() => Array.from(document.querySelectorAll('.result-tiles .mini__s')).map((n) => n.textContent));
    check('Result: "estimate 5,010 · bench 5,490" keeps each label with its number (no-break space)',
      subs.some((t) => /bench\u00a0[\d,]+/.test(t) && /estimate\u00a0[\d,]+/.test(t)) && !subs.some((t) => /\b(bench|estimate) \d/.test(t)), subs);
    const st1 = await (await page.request.get(BASE + 'api/status')).json();
    check('last run: 20 of 20 dots, DPH reported', st1.lastRun.dotsDone === 20 && st1.lastRun.dotsPlanned === 20
      && st1.lastRun.dphMotion > 0 && st1.lastRun.dphOverall > 0, st1.lastRun);
    check('the test limit is cleared after the run', await page.evaluate(() => !IL.run.test && String(IL.run.maxDots) === ''),
      await page.evaluate(() => [IL.run.test, IL.run.maxDots]));
    await page.evaluate(() => IL.go('#run/checks'));
    await waitText(page, 'btn-run', /Finish \d+ pads? left/, 10000).catch(() => {});
    check('after a 20-dot test: the Checks foot offers to finish the pads left', /Finish 140 pads left/.test(await textOf(page, 'btn-run')),
      await textOf(page, 'btn-run'));
    check('after a 20-dot test: "Wipe all, run all" offered, no second test on the pasted pads', await page.isVisible('#run-all-link')
      && !(await page.isVisible('#btn-run-test')));
    l = lines(gcode(g0));
    const firstB = l.findIndex((x) => /^G1 B/.test(x));
    check('run: G92 B0 before the first auger move (I2)', l.indexOf('G92 B0') >= 0 && l.indexOf('G92 B0') < firstB, l.slice(0, 20));
    check('run: pump, then valve; valve off, then pump off',
      l.indexOf('M106 P2 S255') < l.indexOf('M106 P3 S255') && l.lastIndexOf('M107 P3') < l.lastIndexOf('M107 P2'));
    check('run: 56 auger moves = 28 dots (8 purge + 20) x (forward + suck-back)', l.filter((x) => /^G1 B/.test(x)).length === 56,
      l.filter((x) => /^G1 B/.test(x)).length);
    check('run: one pressure session (the valve opens once)', l.filter((x) => x === 'M106 P3 S255').length === 1, l.length);
    check('run: screw moves at 180 rpm (F64800) under M204 S20000', l.filter((x) => /^G1 B/.test(x)).every((x) => / F64800$/.test(x))
      && l.includes('M204 S20000'), l.filter((x) => /^G1 B|^M204/.test(x)).slice(0, 8));
    check('run: purge spots used', st1.dispense.purgeRow.usedSpots === 8, st1.dispense.purgeRow);
    await page.screenshot({ path: path.join(OUT, 'run-after-20-dots.png') });
    console.log('     sim run: ' + JSON.stringify({ dphMotion: st1.lastRun.dphMotion, dphOverall: st1.lastRun.dphOverall,
      totalSeconds: st1.lastRun.totalSeconds }) + ' (simulated controller: not a physical DPH)');

    // ---- prime and test dot from Setup › Pressure and paste flow (two-press) -------------------
    await nav(page, 'setup');
    for (const [btn, label] of [['#btn-prime', 'prime'], ['#btn-testdot', 'test dot']]) {
      const armed = await press2(page, btn);
      check(label + ' asks for a second press', /^Press again/.test(armed), armed);
      const s = await waitStatus(page, (x) => x.run && x.run.label === label && !x.run.running, 60000);
      check(label + ' from Setup: OK', s.run.status === 'OK', s.run);
    }
    await waitText(page, 'dispense-result', /\S/, 10000);
    check('prime / test dot result shown in the card', (await textOf(page, 'dispense-result')).length > 0, await textOf(page, 'dispense-result'));
    const lastTile = await textOf(page, 'run-last').catch(() => '');
    check('Last board tile keeps the board run after a prime and a test dot', !/prime|test dot/i.test(lastTile), lastTile);

    // ---- Calibrate › Needle touch-off (fix round 2: F9, F14): the pitch and its refusal at the field, a run that
    // records nothing (its message word for word), a recorded run whose dots the camera could not measure ----------
    {
      // the blank place on the blank surface, 10 mm from the purge row (the touch-off lays its dots in +X from it)
      await nav(page, 'setup');
      await page.waitForSelector('tr[data-place="blank"] input[data-axis=x]');
      const blankRow = page.locator('tr[data-place="blank"]');
      await blankRow.locator('input[data-axis=x]').fill('150');
      await blankRow.locator('input[data-axis=y]').fill('35');
      await blankRow.locator('input[data-axis=z]').fill('10');
      await blankRow.locator('button[data-action=save]').click();
      await waitStatus(page, (s) => s.dispense.locations.blank && s.dispense.locations.blank.x === 150, 10000);
      await nav(page, 'calibrate');
      await page.evaluate(() => IL.go('#calibrate/touch-off'));
      await page.waitForSelector('#to-pitch', { state: 'visible', timeout: 15000 });
      // the card chose the only place there was before (the waste): the operator picks the blank
      await page.waitForFunction(() => [...document.getElementById('to-place').options].some((o) => o.value === 'blank'), null, { timeout: 10000 });
      await page.selectOption('#to-place', 'blank');
      const pf = await page.evaluate(() => ({ ph: document.getElementById('to-pitch').placeholder, unit: document.querySelector('#to-pitch-wrap .unit').textContent,
        hint: document.querySelector('#to-pitch-wrap .field__h').textContent }));
      check('touch-off: a Pitch field (mm, placeholder 1.6, "at least the dot + 0.4 mm")', pf.ph === '1.6' && pf.unit === 'mm' && pf.hint === 'at least the dot + 0.4 mm', pf);
      // the place chosen shows at the next render (the old line already ended "1.6 mm apart")
      await waitText(page, 'to-plan', /on “blank” at gaps .* · 1\.6 mm apart$/, 10000).catch(() => {});
      check('touch-off plan line: "· 1.6 mm apart" (the engine\'s pitch for a half-turn dot)', /^6 dots on “blank” at gaps 0\.125, .* · one profile dot each · 1\.6 mm apart$/.test(await textOf(page, 'to-plan')),
        await textOf(page, 'to-plan'));
      // 1.0 mm leaves no room between half-turn dots: the engine's 400 is the field's error, and nothing moves
      await page.fill('#to-pitch', '1.0');
      await waitText(page, 'to-plan', /· 1\.0 mm apart$/, 5000).catch(() => {});
      let g2 = gcodeNext();
      await press2(page, '#btn-to-run');
      await page.waitForFunction(() => !document.getElementById('to-pitch-err').hidden, null, { timeout: 10000 }).catch(() => {});
      const pe = await page.evaluate(() => ({ err: document.getElementById('to-pitch-err').textContent, shown: !document.getElementById('to-pitch-err').hidden,
        invalid: document.getElementById('to-pitch').classList.contains('input--invalid'), reason: document.getElementById('to-reason').textContent,
        locked: document.getElementById('btn-to-run').disabled, boxes: document.querySelectorAll('#cal-touch-off .refusal').length, toast: document.getElementById('toast').textContent }));
      const pitch400 = 'the touch-off pitch 1.00 mm is too small for these dots: a half-turn dot is about 1.16 mm across, so dots 1.00 mm apart '
        + 'touch or string together and cannot be measured; use a pitch of at least 1.56 mm (the dot + 0.40 mm)';
      check('touch-off pitch 1.0: the engine\'s 400 word for word as the Pitch field\'s error; Run locked, pointing there; no refusal box',
        pe.shown && pe.err === pitch400 && pe.invalid && pe.locked && pe.reason === 'The pitch is too small for these dots: see the Pitch field.' && pe.boxes === 0
        && pe.toast === 'Not accepted: ' + pitch400, pe);
      l = lines(gcode(g2));
      check('touch-off pitch 1.0: refused before anything moves or pressurises', !l.some((x) => /^(G0|G1|M106)\b/.test(x)), l.slice(0, 12));
      await page.evaluate(() => document.getElementById('cal-touch-off').scrollIntoView({ block: 'start' }));
      await page.screenshot({ path: path.join(OUT, 'touchoff-pitch-refused.png') });
      await page.fill('#to-pitch', '');
      await page.waitForFunction(() => document.getElementById('to-pitch-err').hidden && !document.getElementById('btn-to-run').disabled, null, { timeout: 10000 }).catch(() => {});
      check('touch-off: the pitch changed, its error goes and Run unlocks', await page.evaluate(() => document.getElementById('to-pitch-err').hidden
        && !document.getElementById('btn-to-run').disabled), await textOf(page, 'to-reason'));
      // a run that records nothing: this SIM scene draws no paste, so no dot forms; the run's message word for word
      const touchOffs = async () => (await (await page.request.get(BASE + 'api/calibrations')).json()).touchOffs;
      const rows0 = (await touchOffs()).length;
      const t9 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      await press2(page, '#btn-to-run');
      await waitText(page, 'toast', /^Touch-off started, dots 1\.6 mm apart: /, 10000).catch(() => {});
      check('touch-off started: the toast names the 202\'s pitch', /^Touch-off started, dots 1\.6 mm apart: the result is shown only\.$/.test(await textOf(page, 'toast')), await textOf(page, 'toast'));
      const tr = await waitStatus(page, (x) => x.run && x.run.label === 'needle touch-off' && !x.run.running && x.run.startedAtMs > t9, 240000);
      check('touch-off run: OK without a contact (no paste in this SIM scene), nothing recorded', tr.run.status === 'OK'
        && /^no dot at the largest gap: /.test(tr.run.message || '') && (await touchOffs()).length === rows0, { status: tr.run.status, message: tr.run.message });
      await page.waitForFunction((m) => { const d = document.querySelector('#to-live .cal-live__d'); return !!d && d.textContent === m; }, tr.run.message, { timeout: 15000 }).catch(() => {});
      const nc = await page.evaluate(() => ({ head: (document.querySelector('#to-live .cal-live__h') || {}).textContent, detail: (document.querySelector('#to-live .cal-live__d') || {}).textContent,
        shown: !document.getElementById('to-live').hidden }));
      check('touch-off that recorded nothing: "Finished hh:mm · no contact found: nothing recorded" with the run\'s message word for word',
        nc.shown && /^Finished \d\d:\d\d · no contact found: nothing recorded$/.test(nc.head) && nc.detail === tr.run.message, nc);
      await page.screenshot({ path: path.join(OUT, 'touchoff-nothing-recorded.png') });
      // the result table of a recorded touch-off (a row added to the answer: this scene draws no paste): the dots after
      // the contact that the camera could not measure read "not measured" with the engine's note, "no dot" only for none
      const fake = { id: 990001, created_at: tr.run.startedAtMs - 120000, method: 'dot-ladder-touch-off (not applied)', z_offset_mm: -2.5275, previous_offset_mm: -2.59, operator: null,
        details: JSON.stringify({ analysis: { gapsMm: [0.125, 0.1, 0.075, 0.05, 0.025, 0, -0.025], diametersMm: [1.05, 1.07, 1.06, 1.42, null, null, null],
          dotStates: ['dot', 'dot', 'dot', 'dot', 'unmeasured', 'unmeasured', 'none'],
          dotNotes: [null, null, null, null, 'joined to the dot at gap 0.000 mm', 'no dot, only strings or a smear (0.10 mm²)', null],
          contactGapMm: 0.0625, bracketHighMm: 0.075, bracketLowMm: 0.05, referenceDiameterMm: 1.06, reason: 'dot squashed between 0.075 and 0.050 mm' }, pitchMm: 1.6, blank: 'blank' }) };
      await page.route('**/api/calibrations', async (route) => {
        try {
          const res = await route.fetch();
          const body = await res.json();
          body.touchOffs = [fake].concat(body.touchOffs || []);
          await route.fulfill({ response: res, json: body });
        } catch (e) { /* unrouted while it was being answered: Playwright let the request go on */ }
      });
      await page.evaluate(() => IL.pcr.loadCalibrations());
      await page.waitForFunction(() => document.querySelectorAll('#to-result .cal-to__t tbody tr').length === 7, null, { timeout: 10000 }).catch(() => {});
      const tbl = await page.evaluate(() => ({ sub: document.querySelector('#to-result .section-k').textContent,
        rows: Array.from(document.querySelectorAll('#to-result .cal-to__t tbody tr')).map((tr) => [tr.cells[0].textContent, tr.cells[1].textContent, tr.cells[2].textContent, tr.cells[1].title]) }));
      const r5 = tbl.rows;
      // the third column keeps each number with its unit (a screenshot at 1440 px broke "(0.10" / "mm²)"); the title is the note
      check('touch-off table: "not measured" with the engine\'s note (third column, numbers kept with their units, and title) for an unmeasured dot, "no dot" only for none',
        r5.length === 7 && r5[0][1] === '1.050' && r5[3][1] === '1.420' && r5[3][2] === 'bracket'
        && r5[4][1] === 'not measured' && r5[4][2] === 'joined to the dot at gap 0.000 mm' && r5[4][3] === 'joined to the dot at gap 0.000 mm'
        && r5[5][1] === 'not measured' && r5[5][2] === 'no dot, only strings or a smear (0.10 mm²)' && r5[6][1] === 'no dot' && r5[6][2] === '', r5);
      check('touch-off: after the run that recorded nothing, the row shown is "Last recorded touch-off"', /^Last recorded touch-off · /.test(tbl.sub), tbl.sub);
      await page.evaluate(() => document.getElementById('to-result').scrollIntoView({ block: 'center' }));
      await page.screenshot({ path: path.join(OUT, 'touchoff-not-measured.png') });
      await nav(page, 'records');
      await page.evaluate((id) => IL.go('#records/touch-offs/' + id), fake.id);
      await page.waitForFunction(() => /not measured/.test((document.querySelector('#panel .rec-mini-t') || {}).textContent || ''), null, { timeout: 10000 }).catch(() => {});
      const rec = await page.evaluate(() => Array.from(document.querySelectorAll('#panel .rec-mini-t tbody tr')).map((tr) => [tr.cells[1].textContent, tr.cells[1].title]));
      check('Records › Touch-offs: the same reading (not measured, its note as the title; no dot only for none)', rec.length === 7
        && rec[4][0] === 'not measured' && rec[4][1] === 'joined to the dot at gap 0.000 mm' && rec[6][0] === 'no dot' && rec[0][0] === '1.050', rec);
      await page.unroute('**/api/calibrations');
      await page.evaluate(() => { IL.pcr.loadCalibrations(); if (IL.panel.isPushed()) IL.panel.close(); });
    }

    // ---- a run confirm open in a tab that never saw a task running (offline, a throttled hidden tab) closes on its
    // next status: the new run id says a task ran meanwhile, and its "hands clear" tick was for the board before it
    {
      const twin = await context.newPage();
      twin.on('pageerror', (e) => jsErrors.push('twin: ' + e));
      await twin.goto(BASE + '#run/checks');
      await twin.waitForSelector('#btn-run:enabled', { timeout: 20000 });
      await twin.click('#btn-run');
      await twin.waitForSelector('#dlg-run', { timeout: 10000 });
      await twin.check('#run-tick-hands');
      await twin.route('**/api/status', (route) => route.abort());
      await twin.waitForTimeout(1500);
      const pr = await page.request.post(BASE + 'api/test-dot', { data: { wait: true }, timeout: 120000 });
      check('a test dot from another client while the confirm is open', pr.ok(), pr.status());
      const open = await twin.isVisible('#dlg-run');
      await twin.unroute('**/api/status');
      const closed = await twin.waitForSelector('#dlg-run', { state: 'detached', timeout: 15000 }).then(() => true, () => false);
      const toast = await textOf(twin, 'toast');
      check('run confirm closes after a task it never saw running', open && closed && /^Run confirm closed: a task started meanwhile/.test(toast),
        { open, closed, toast });
      await twin.close();
    }

    // ---- STOP while a board run is dispensing ------------------------------------------------
    // the test board has paste on 20 pads: "Wipe all, run all" is offered; the run is the 140 pads left, stopped after
    // a few dots (a run of part of the board: Records must offer only its own pads afterwards)
    await nav(page, 'run');
    await page.evaluate(() => IL.go('#run/checks'));
    await page.click('#run-all-link');
    await page.waitForSelector('#dlg-run', { timeout: 10000 });
    check('Wipe all, run all: the confirm says to wipe every pad first', /wipe every pad first/.test(await textOf(page, 'run-confirm-sum')),
      await textOf(page, 'run-confirm-sum'));
    await page.click('#btn-run-cancel');
    await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 5000 });
    await page.click('#btn-run');
    await page.waitForSelector('#dlg-run', { timeout: 10000 });
    check('Finish 140 pads left: the confirm names them', /Dispense the 140 pads left\?/.test(await textOf(page, 'dlg-run-t')), await textOf(page, 'dlg-run-t'));
    for (const t of ['#run-tick-gauge', '#run-tick-tip', '#run-tick-paste', '#run-tick-hands']) if (!(await page.isChecked(t))) await page.check(t);
    await page.waitForSelector('#btn-run-go:enabled', { timeout: 10000 });
    await page.click('#btn-run-go');
    await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 15000 });
    await waitStatus(page, (x) => x.run && x.run.label === 'board' && x.run.running && x.run.dotsDone >= 3, 300000);
    g0 = gcode().next;
    await page.click('[data-testid=stop]');
    // the engine ends the run (ABORTED) after its abort lines went out; a loaded simulator can take a while
    const stopped = await waitStatus(page, (x) => x.run && !x.run.running && !x.machine.enabled, 90000);
    check('STOP mid-run: run ABORTED before its end', stopped.run.status === 'ABORTED' && stopped.run.dotsDone < 140,
      { status: stopped.run.status, done: stopped.run.dotsDone, message: stopped.run.message });
    l = lines(gcode(g0));
    const iM410 = l.indexOf('M410'), iV = l.indexOf('M107 P3'), iP = l.indexOf('M107 P2'), iB = l.indexOf('M18 B');
    check('STOP mid-run: engine I1 sequence M410, valve off, pump off, M18 B', iM410 >= 0 && iV > iM410 && iP > iV && iB > iP, l);
    // with the F9 DISABLE_COMMAND the web STOP does not wait for the engine: its abort lines and
    // OpenPnP's DISABLE_COMMAND go out at the same time and may interleave, each copy in order
    check('STOP mid-run: the bench F9 sequence (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B) from the engine and '
      + 'from OpenPnP\'s Stop', twiceInOrder(l, F9), l);
    check('STOP mid-run: machine stopped and un-homed', !stopped.machine.enabled && !stopped.machine.homed);
    check('STOP mid-run: the engine\'s own I1 went out, then OpenPnP\'s DISABLE_COMMAND (valve off sent twice)',
      l.filter((x) => x === 'M107 P3').length >= 2, l);
    console.log('     STOP mid-run: ' + stopped.run.dotsDone + ' of ' + stopped.run.dotsPlanned + ' dots done; G-code after STOP: ' + JSON.stringify(l));
    // Recovery (§8.1.8): Run's panel, Not connected, the board decision locked until the steps are done
    await waitText(page, 'pill-conn', /^Not connected$/, 15000);
    await page.waitForFunction(() => document.getElementById('panel').dataset.view === 'recovery', null, { timeout: 10000 });
    check('after STOP: Recovery panel on Run', true);
    // the page may still hold a poll from between "machine off" and "run ABORTED": wait for the verdict
    await page.waitForFunction(() => /^Stopped \d\d:\d\d/.test(document.querySelector('#pill-engine .cell__v').textContent), null,
      { timeout: 10000 }).catch(() => {});
    check('after STOP: verdict "Stopped hh:mm"', /^Stopped \d\d:\d\d/.test(await page.textContent('#pill-engine .cell__v')),
      await page.textContent('#pill-engine .cell__v'));
    // the stopped strip is the widest (Not connected + Connect, Off · vented, Confirm, 7 of 15 · wipe): the verdict's value
    // is never cut (UI_DESIGN §4: cells never truncate); shorter forms of the other cells, then of the verdict, go first
    const cuts = [];
    for (const [w, h] of [[1440, 900], [1280, 800], [1024, 768]]) {
      await page.setViewportSize({ width: w, height: h });
      await page.waitForTimeout(500);
      cuts.push(await page.evaluate((ww) => {
        const v = document.querySelector('#pill-engine .cell__v'), cells = document.getElementById('cells');
        const r = v.getBoundingClientRect(), lim = cells.getBoundingClientRect().right;
        return { w: ww, shown: v.innerText, cut: v.scrollWidth > v.clientWidth + 0.5 || r.right > lim + 0.5, tier: cells.className };
      }, w));
      await page.screenshot({ path: path.join(OUT, 'run-stopped-strip-' + w + '.png') });
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
    check('after STOP: the verdict reads "Stopped…" in full at 1440, 1280 and 1024 px (the other cells shorten first)',
      cuts.every((c) => !c.cut && /^Stopped/.test(c.shown)), cuts);
    // the other cells' long parts go before the verdict's own: at 1440 px the time of the stop stays (final gate)
    check('after STOP: at 1440 px the verdict keeps the time of the stop ("Stopped hh:mm")', /^Stopped \d\d:\d\d/.test(cuts[0].shown), cuts[0]);
    check('after STOP: the plan never says "All interlocks pass"', !/All interlocks pass/.test(await page.textContent('#ws-run')));
    await page.waitForSelector('#btn-run-rest', { state: 'visible', timeout: 10000 }).catch(() => {});
    const locked = await page.evaluate(() => ['btn-run-rest', 'btn-run-all'].map((id) => {
      const b = document.getElementById(id);
      return b ? { id, disabled: b.disabled, title: b.title, text: b.textContent.trim() } : { id, missing: true };
    }));
    check('after STOP: board decision locked until the recovery steps are done', locked.every((b) => b.disabled), locked);
    await page.screenshot({ path: path.join(OUT, 'run-stopped.png') });
    await page.click('#btn-start');
    await waitText(page, 'pill-conn', /^Connected$/, 60000);
    await press2(page, '#btn-home');
    // the simulator only: after a run its auger axis B stands thousands of degrees out, and OpenPnP's GcodeServer models
    // G28 and the park move after it as up to 20 s each (its dwell cap), past the driver's 10 s M400 timeout. The
    // home then fails ("timeout waiting for response to M204"); the second one starts from home and works
    if (!(await waitText(page, 'pill-homed', /^Homed$/, 90000).then(() => true, () => false))) {
      console.log('     home after the STOP mid-run failed in the simulator (GcodeServer dwell cap after a run): homing again');
      await press2(page, '#btn-home');
      await waitText(page, 'pill-homed', /^Homed$/, 120000);
    }

    // ---- Records › the stopped run of the 140 pads left: finish only its own pads, never the test run's 20 ---------
    {
      const left = 140 - stopped.run.dotsDone;
      // as the operator does: Records first, then the run (a run chosen there shows instead of the pushed Recovery)
      await nav(page, 'records');
      await page.waitForSelector('#records-runs', { state: 'visible', timeout: 15000 });
      await page.evaluate((id) => IL.go('#records/boards/' + id), stopped.run.runId);
      await page.waitForSelector('#rec-finish', { timeout: 20000 });
      const fin = await textOf(page, 'rec-finish');
      check('Records: a stopped finish run offers only its own pads left', fin === 'Finish the ' + left + ' pads left', { fin, left });
      await page.click('#rec-finish');
      await page.waitForSelector('#dlg-run', { timeout: 10000 });
      const conf = await page.evaluate(() => {
        const test = new Set(IL.S.plan.dots.slice(0, 20).map((d) => d.padId));
        const ids = IL.run.dlg.mode.padIds;
        return { n: ids.length, pasted: ids.filter((id) => test.has(id)).length, title: document.getElementById('dlg-run-t').textContent };
      });
      check('Records › Finish: the run confirm holds the pads left, none of the test run\'s', conf.n === left && conf.pasted === 0
        && conf.title === 'Dispense the ' + left + ' pads left?', { conf, left });
      await page.click('#btn-run-cancel');
      await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 5000 });
    }

    // ---- interlocks view and pressure on from Setup ------------------------------------------
    await nav(page, 'setup');
    await page.evaluate(() => IL.go('#setup?panel=interlocks'));
    await page.waitForSelector('#interlock-list li');
    const i1 = await page.textContent('#interlock-list li.ok:has-text("I1"), #interlock-list li.warning:has-text("I1")');
    check('I1 shows the DISABLE_COMMAND check', /Stop/.test(i1), i1);
    check('I1 ok with the F9 DISABLE_COMMAND', await page.isVisible('#interlock-list li.ok:has-text("I1")')
      && i1.includes('M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B'), i1);
    await page.evaluate(() => IL.panel.close());
    g0 = gcode().next;
    await press2(page, '#btn-pressure-on', true);   // two presses unless the needle is over the waste
    await waitText(page, 'pressure-state', /^ON/, 30000);
    l = lines(gcode(g0));
    const pump = l.indexOf('M106 P2 S255'), valve = l.indexOf('M106 P3 S255');
    check('pressure on: pump, then valve', pump >= 0 && valve > pump, l);
    await page.waitForFunction(() => /^ON /.test(document.querySelector('#pill-pressure .cell__v').textContent), null, { timeout: 5000 })
      .catch(() => {});
    check('pressure cell shows held pressure', /^ON /.test(await page.textContent('#pill-pressure .cell__v')),
      await page.textContent('#pill-pressure .cell__v'));
    check('second STOP in the panel while energised', await page.isVisible('#panel [data-stop-copy]'));
    await page.screenshot({ path: path.join(OUT, 'setup-pressure-on.png') });

    // prime is refused while the pressure is held, with the reason in the banner
    check('Prime disabled while pressure is held', await page.isDisabled('#btn-prime'));

    // ---- STOP (F9 from a text field) ---------------------------------------------------------
    g0 = gcode().next;
    await page.focus('#pr-x0');
    await page.keyboard.press('F9');
    await waitText(page, 'toast', /STOPPED/, 10000);
    check('STOP toast', /^STOPPED \d\d:\d\d:\d\d: /.test(await textOf(page, 'toast')), await textOf(page, 'toast'));
    check('F9 kept the focus in the field', await page.evaluate(() => document.activeElement && document.activeElement.id) === 'pr-x0');
    await waitText(page, 'pill-conn', /^Not connected$/, 30000);
    await waitText(page, 'pressure-state', /^Off/, 10000);
    l = lines(gcode(g0));
    const vOff = l.indexOf('M107 P3'), pOff = l.indexOf('M107 P2');
    check('STOP: valve off, then pump off', vOff >= 0 && pOff > vOff, l);
    check('STOP: OpenPnP Stop sent the DISABLE_COMMAND (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B)', block(l, F9) >= 0, l);
    const st = await (await page.request.get(BASE + 'api/status')).json();
    check('after STOP: machine stopped, pressure off', !st.machine.enabled && st.pressure.state === 'off',
      { enabled: st.machine.enabled, pressure: st.pressure.state });
    await page.screenshot({ path: path.join(OUT, 'after-stop.png') });

    // ---- top camera snapshot (simulated ImageCamera) ------------------------------------------
    const cam = await page.request.get(BASE + 'api/camera/top.jpg?w=640');
    check('top camera JPEG', cam.ok() && cam.headers()['content-type'] === 'image/jpeg', cam.status());
    if (cam.ok()) fs.writeFileSync(path.join(OUT, 'camera.jpg'), await cam.body());
    await page.click('#btn-cam');
    await page.waitForFunction(() => document.getElementById('cam-img').naturalWidth > 0, null, { timeout: 15000 });
    check('camera snapshot shown in the UI', true);
    await page.screenshot({ path: path.join(OUT, 'setup-camera.png') });

    // ---- Register board, the automatic blank probe, home at run, Z check (AUTO_REGISTER_DESIGN.md §10.3) ---------
    // The SIM scene (run-webui-sim-test.sh) seats the board 0.60 / -0.45 mm and +0.20 deg off the plan, scaled
    // 1.002 / 0.997 with a 0.1 deg shear: Register board must find a real correction. Every expected value comes from
    // the scene. Here the machine is stopped (the F9 above), so N1 sits in the safe zone.
    {
      const scene = SIM_SCENE ? JSON.parse(fs.readFileSync(SIM_SCENE, 'utf8')) : null;
      check('SIM scene present (run-webui-sim-test.sh)', !!scene, SIM_SCENE);
      const aff = scene.board.seat.affine;
      const trueAt = (bx, by) => [aff[0][0] * bx + aff[0][1] * by + aff[0][2], aff[1][0] * bx + aff[1][1] * by + aff[1][2]];
      const status = async () => (await page.request.get(BASE + 'api/status')).json();
      const put = (p, body) => page.request.put(BASE + p, { data: body });
      const post = (p, body) => page.request.post(BASE + p, { data: body || {} });
      const surfAt = async () => { const s = await status(); const b = s.dispense.surfaces.board; return b ? b.setAtMs : null; };
      const panelView = () => page.evaluate(() => document.getElementById('panel').dataset.view);
      const recStep = (key) => page.evaluate((k) => {
        const st = IL.recoverySteps(IL.S.status, IL.S.recovery || IL.S.lastStop).find((x) => x.key === k);
        return st ? { title: st.title, done: !!st.done, detail: st.detail, state: st.state } : null;
      }, key);
      /** Waits for a Register board task to end; returns its status. */
      const regEnd = async (since, timeout) => waitStatus(page, (x) => x.run && x.run.label === 'board registration' && !x.run.running
        && x.run.startedAtMs > since, timeout || 300000);
      // the start-angle answer of the recovery panel (other workspaces carry their own copy of the fix, hidden)
      const answerI11 = async () => {
        const sel = '#panel [data-fix="I11-confirm"]';
        await page.waitForSelector(sel + ':enabled', { state: 'visible', timeout: 20000 }).catch(() => {});
        if (await page.isVisible(sel)) await page.click(sel);
        else if ((await status()).dispense.profile.startAngleNeedsConfirm) await post('api/screw/start-angle', { note: 'PW: the controller stayed on' });
        await waitStatus(page, (x) => !x.dispense.profile.startAngleNeedsConfirm, 15000).catch(() => {});
      };
      let r = await put('api/config', { vacuumProbe: { vacuumSettleMs: 300, readDelayMs: 0 } });
      check('register: fast simulator probe timings saved (FAST_TIMINGS)', r.ok(), r.status());
      await post('api/purge-row/reset');

      // 1. Recovery without Home: connect, the start-angle answer; Register board and Run home first by themselves
      await nav(page, 'run');
      await page.click('#btn-start');
      await waitText(page, 'pill-conn', /^Connected$/, 60000);
      await answerI11();
      const home0 = await recStep('home');
      check('recovery: the home step reads "home first" and is done (N1 in the safe zone)', home0 && /home first/i.test(home0.title) && home0.done, home0);
      await page.waitForFunction(() => !IL.recoveryActive(), null, { timeout: 15000 }).catch(() => {});
      const s0 = await status();
      check('recovery done without a home: machine started, not homed', !(await page.evaluate(() => IL.recoveryActive())) && s0.machine.enabled && !s0.machine.homed,
        { enabled: s0.machine.enabled, homed: s0.machine.homed });

      // 2. Register step, idle
      await page.evaluate(() => IL.go('#run/probe'));
      await page.waitForSelector('#panel[data-view=probe] #btn-register', { timeout: 15000 });
      check('Register step: panel view probe, step title Register', await panelView() === 'probe'
        && (await page.textContent('#panel .step[data-step=probe] .step__t')).trim() === 'Register');
      await waitText(page, 'register-note', /FID1, FID2 and FID3/, 15000).catch(() => {});
      const note = await textOf(page, 'register-note');
      check('#register-note: homes first, the fiducials, a time', /^Homes first/.test(note) && /FID1, FID2 and FID3/.test(note) && /≈\s\d/.test(note), note);
      // the engine's sinceMs is the later of the stop and the Start: the note says since when, never "stopped at" Start's time
      check('#register-note: "Homes first (not homed since hh:mm)"', /^Homes first \(not homed since \d\d:\d\d\)/.test(note), note);
      const byHand0 = await page.evaluate(() => { const d = document.getElementById('probe-by-hand'); return { body: !!d.closest('#panel-body'), foot: !!d.closest('.panel__foot, #panel-foot'), open: d.open }; });
      check('"Do it by hand" is in the panel body, closed', byHand0.body && !byHand0.foot && !byHand0.open, byHand0);
      await page.screenshot({ path: path.join(OUT, 'register-idle.png') });
      await page.evaluate(() => IL.go('#run/board'));
      await page.waitForSelector('#board-z', { timeout: 10000 });
      const zTitle = await page.getAttribute('#board-z', 'title');
      check('Board top Z: approximate, Register board measures it (±1 mm)', /Register board/.test(zTitle) && /±1 mm/.test(zTitle), zTitle);
      // its label wraps: the Z and Rotation inputs of the row stay in line (at 1280 px too)
      const zRot = [];
      for (const w of [1280, 1440]) {
        await page.setViewportSize({ width: w, height: 900 });
        await page.waitForTimeout(250);
        zRot.push(await page.evaluate(() => ['board-z', 'board-rot'].map((id) => Math.round(document.getElementById(id).getBoundingClientRect().top))));
      }
      await page.setViewportSize({ width: 1440, height: 1000 });
      check('Board step: the Board top Z and Rotation inputs line up at 1280 and 1440 px', zRot.every(([a, b]) => Math.abs(a - b) <= 1), zRot);
      await page.evaluate(() => IL.go('#run/probe'));
      await page.waitForSelector('#btn-register', { timeout: 10000 });

      // 3. Register (first seat): homes first, fiducials, probe, plan again
      let g1 = gcodeNext();
      const t1 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      const regLabel = await press2(page, '#btn-register');
      check('Register board asks for a second press', /Press again: register board/.test(regLabel), regLabel);
      await waitStatus(page, (x) => x.run && x.run.label === 'board registration' && x.run.running, 20000);
      const liveNow = await page.waitForSelector('#reg-phases li.now', { timeout: 20000 }).then(() => true, () => false);
      check('registering: the live phases show (#reg-phases li.now)', liveNow);
      const homeRow = await page.waitForSelector('#reg-phases li[data-ph=home]', { timeout: 10000 }).then(() => true, () => false);
      check('registering: the Home row is shown', homeRow);
      // also when the plan's registration preview lists no fiducials (it could not be made: e.g. made while the simulator was
      // off after a STOP, "REG: registration … was made on the simulator"): the row counts the plan's own fiducials
      const fidRow = await page.evaluate(async () => {
        const row = () => { const li = document.querySelector('#reg-phases li[data-ph=fiducials]'); return li ? li.textContent : null; };
        const frame = () => new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(res)));
        const p = IL.S.plan, t = p.registrationTargets, d = (IL.S.status.run && IL.S.status.run.detail) || {};
        const out = { text: row(), n: ((p.board && p.board.fiducials) || []).length, reported: (d.fiducials || []).length, stage: d.stage };
        p.registrationTargets = Object.assign({}, t, { fiducials: [], probes: [], error: 'REG: registration 0000 was made on the simulator: register again' });
        IL.render();
        await frame();
        out.noPreview = row();
        p.registrationTargets = t;
        IL.render();
        return out;
      });
      check('registering: the Fiducials row counts the plan\'s fiducials before the run reports them ("0 of 3", no "…"), also when its preview lists none',
        fidRow.n > 0 && fidRow.reported === 0 && [fidRow.text, fidRow.noPreview].every((x) => new RegExp('Fiducials · 0 of ' + fidRow.n + ' found').test(x || '')
          && !/of …/.test(x || '')), fidRow);
      await waitStatus(page, (x) => x.run && x.run.label === 'board registration' && x.run.detail && x.run.detail.stage === 'fiducials'
        && (x.run.detail.fiducials || []).some((f) => f.state === 'found'), 120000).catch(() => {});
      await page.screenshot({ path: path.join(OUT, 'register-running.png') });
      let rs = await regEnd(t1);
      await page.waitForFunction((id) => { const v = IL.S.plan && IL.S.plan.registrationRun; return v && v.runId === id; }, rs.run.runId, { timeout: 20000 }).catch(() => {});
      await waitText(page, 'reg-result', /\S/, 15000).catch(() => {});
      check('register: the task ended OK', rs.run.status === 'OK', { status: rs.run.status, message: rs.run.message });
      const homeDone = await page.evaluate(() => { const v = IL.S.plan.registrationRun; return v && v.home; });
      check('register: homed first (it was needed)', homeDone && homeDone.needed && homeDone.done, homeDone);
      // the card names the registration's time once the plan it made is shown (before that, the task's end: a minute later
      // when the fit and the end straddle one)
      await page.waitForFunction((n) => IL.S.plan && IL.S.plan.planId === n, rs.run.nextPlanId, { timeout: 20000 }).catch(() => {});
      await page.waitForTimeout(300);
      const res1 = await textOf(page, 'reg-result');
      check('#reg-result: "Registered hh:mm: 3 of 3 fiducials"', /^Registered \d\d:\d\d: 3\sof\s3 fiducials/.test(res1), res1);
      check('register: the new plan is shown (status.run.nextPlanId)', await page.evaluate((n) => IL.S.plan.planId === n, rs.run.nextPlanId), rs.run.nextPlanId);
      check('register: the board surface is tied', !!rs.dispense.surfaces.board && rs.dispense.surfaces.board.boundToBoard, rs.dispense.surfaces.board);
      check('#reg-fids lists 3 fiducials', (await page.$$eval('#reg-fids tbody tr', (e) => e.length)) === 3);
      const view1 = await page.evaluate(() => IL.S.plan.registrationRun);
      const f1 = view1.fiducials.find((f) => f.name === 'FID1');
      const want = trueAt(f1.board.x, f1.board.y);
      const nominal = [120 + f1.board.x, 120 + f1.board.y];
      const corr = [f1.found.x - f1.expected.x, f1.found.y - f1.expected.y];
      const err = Math.hypot(corr[0] - (want[0] - nominal[0]), corr[1] - (want[1] - nominal[1]));
      check('FID1 correction = the scene\'s true seat − the plan\'s (within 0.05 mm)', err <= 0.05, { corr, want: [want[0] - nominal[0], want[1] - nominal[1]], err });
      check('#reg-correction: "fiducial check hh:mm"', /^fiducial check \d\d:\d\d/.test(await textOf(page, 'reg-correction')), await textOf(page, 'reg-correction'));
      {
        // one time for one registration: the card, the page head and "Dots use" (the task ends a little after the fit)
        const tCard = (/^Registered (\d\d:\d\d)/.exec(res1) || [])[1], tCorr = (/^fiducial check (\d\d:\d\d)/.exec(await textOf(page, 'reg-correction')) || [])[1];
        check('#reg-result names the registration\'s time, as #reg-correction does', !!tCard && tCard === tCorr, { tCard, tCorr });
      }
      await page.screenshot({ path: path.join(OUT, 'register-result.png') });
      // 4. the G-code: home first, the bench probe lines, never below the floor, X/Y only at the travel Z
      let l = lines(gcode(g1));
      const window1 = view1.probe.window;
      const iG28 = l.findIndex((x) => /^G28\b/.test(x)), iValve = l.indexOf('M106 P1 S180');
      check('register G-code: G28 before the first N1 valve (M106 P1 S180)', iG28 >= 0 && iValve > iG28, { iG28, iValve });
      check('register G-code: pump, valve, settle 300 ms, a VAC1 read, valve off, pump off (in order)',
        inOrder(l, ['M106', 'M106 P1 S180', 'G4 P300', 'M261 A109 B3 S1', 'M107 P1', 'M107 P0']), l.filter((x) => /^M10[67]|^G4|^M261/.test(x)).slice(0, 12));
      // from the probe's own first raise to the travel Z (OpenPnP's home and camera moves before it use its safe Z)
      let below = [], lastZ = null, xyLow = [];
      let on = false;
      for (const x of l.slice(Math.max(0, l.indexOf('G1 Z31.5000 F600')))) {
        if (x === 'M106 P1 S180') on = true;
        if (x === 'M107 P1') on = false;
        const z = /^G1\b.*\bZ(-?[\d.]+)/.exec(x);
        if (z) { lastZ = Number(z[1]); if (on && lastZ < window1.floorZ - 1e-6) below.push(x); }
        if (/^G1\b.*\b[XY]-?[\d.]/.test(x) && !/\bZ/.test(x) && lastZ !== null && Math.abs(lastZ - 31.5) > 1e-3) xyLow.push(x + ' at Z ' + lastZ);
      }
      check('register G-code: no N1 Z below the probe floor ' + window1.floorZ + ' while the vacuum is on', below.length === 0, below.slice(0, 5));
      check('register G-code: X/Y moves only at the travel Z 31.5', xyLow.length === 0, xyLow.slice(0, 5));
      const lastZLine = l.filter((x) => /^G1\b.*\bZ/.test(x)).pop();
      check('register G-code: the last Z move is G1 Z31.5000', /^G1 Z31\.5000\b/.test(lastZLine || ''), lastZLine);

      // 5. Same seat: 3 touches, the registration and the surface stay
      const regId1 = view1.registrationId;
      const surf1 = await surfAt();
      g1 = gcodeNext();
      const t2 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      await press2(page, '#btn-reg-again');
      rs = await regEnd(t2);
      await page.waitForFunction((id) => { const v = IL.S.plan && IL.S.plan.registrationRun; return v && v.runId === id; }, rs.run.runId, { timeout: 20000 }).catch(() => {});
      await waitText(page, 'reg-result', /^Same seat/, 15000).catch(() => {});
      check('#reg-result: "Same seat hh:mm"', /^Same seat \d\d:\d\d/.test(await textOf(page, 'reg-result')), await textOf(page, 'reg-result'));
      l = lines(gcode(g1));
      check('same seat: exactly 3 N1 touches', l.filter((x) => x === 'M106 P1 S180').length === 3, l.filter((x) => x === 'M106 P1 S180').length);
      const view2 = await page.evaluate(() => IL.S.plan.registrationRun);
      check('same seat: the registration and the board surface are unchanged', view2.registrationId === regId1 && (await surfAt()) === surf1,
        { reg: [regId1, view2.registrationId], surf: [surf1, await surfAt()] });
      check('same seat: "Probe again" is offered', await page.isVisible('#btn-reg-probe'));

      // 6. STOP during a forced probe ("Probe again"): nothing moves after M410, the surface stays
      const t3 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      await press2(page, '#btn-reg-probe');
      await waitStatus(page, (x) => x.run && x.run.label === 'board registration' && x.run.running && x.run.detail && x.run.detail.stage === 'probe'
        && (x.run.detail.probes || []).some((q) => q.state === 'now'), 120000);
      await page.waitForTimeout(1200);
      g1 = gcodeNext();
      await page.click('#btn-stop');
      rs = await regEnd(t3, 90000);
      check('STOP mid-register: ABORTED', rs.run.status === 'ABORTED', { status: rs.run.status, message: rs.run.message });
      await page.waitForFunction(() => document.getElementById('panel').dataset.view === 'recovery', null, { timeout: 15000 }).catch(() => {});
      check('STOP mid-register: Recovery shows', await panelView() === 'recovery');
      l = lines(gcode(g1));
      const iStop = l.indexOf('M410');
      check('STOP mid-register: the F9 lines follow M410, no Z move after it', iStop >= 0 && inOrder(l.slice(iStop), F9) && !l.slice(iStop).some((x) => /^G1\b.*\bZ/.test(x)), l.slice(Math.max(0, iStop - 3)));
      check('STOP mid-register: the board surface was not changed', (await surfAt()) === surf1);
      await page.screenshot({ path: path.join(OUT, 'register-stopped-recovery.png') });

      // 7. Recovery with N1 low: the home step names N1's Z; Home raises N1 first (Z only), then homes
      await page.click('#btn-start');
      await waitText(page, 'pill-conn', /^Connected$/, 60000);
      await answerI11();
      const home1 = await recStep('home');
      check('recovery: N1 stopped low: the home step is needed and names its Z', home1 && !home1.done && /N1 stopped low \(Z \d/.test(home1.detail), home1);
      // what homes by itself waits as Run does (AUTO_REGISTER_DESIGN.md §8.2): Register board (its fix, the button every card
      // offers; Run's own Register step is the recovery now) and Probe the blank are locked with N1's Z, the blank with "Home now"
      const lowReg = await page.evaluate(() => { const b = IL.fixButton({ fix: 'register', label: 'Register board' }); return { disabled: b.disabled, why: b.title, canRun: IL.ready().canRun }; });
      await page.evaluate(() => IL.go('#setup/surfaces'));
      await page.waitForFunction(() => IL.S.route.ws === 'setup', null, { timeout: 10000 }).catch(() => {});
      await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.openView('probe-blank', { keep: true }); });
      await waitText(page, 'blank-why', /N1 stopped low/, 15000).catch(() => {});
      const lowBlank = await page.evaluate(() => { const b = document.getElementById('btn-blank-auto'), w = document.getElementById('blank-why');
        return { disabled: !!b && b.disabled, why: w ? w.textContent : null, home: !!document.querySelector('#blank-why [data-fix="home"]') }; });
      const lowText = /^N1 stopped low \(Z [−-]?\d+\.\d\d mm\): look at the board under it\./;
      check('N1 low: Register board and Probe the blank are locked like Run, the reason names N1\'s Z, the blank\'s with "Home now"',
        !lowReg.canRun && lowReg.disabled && lowText.test(lowReg.why) && lowBlank.disabled && lowText.test(lowBlank.why) && lowBlank.home, { lowReg, lowBlank });
      // a STOP during the board's dots (rc1 review): N1 then sits above its safe zone and the needle is low over a pad. The
      // engine says which tool (status.machine.home.lowTool); the texts name the needle, its Z and the pad, never N1's high Z
      const needleLow = await page.evaluate(() => {
        const s = JSON.parse(JSON.stringify(IL.S.status));
        const d = IL.S.plan.dots[3];
        s.machine.home = Object.assign({}, s.machine.home, { n1Low: true, lowTool: 'needle', n1Z: 60.06 });
        const n2 = s.machine.positions.find((t) => t.kind === 'nozzle' && t.name === s.dispense.nozzle);
        Object.assign(n2, { x: d.x + 0.05, y: d.y - 0.05, z: 2.94 });
        const it = IL.readiness(s, IL.S.plan, IL.readyCtx()).items.find((x) => x.key === 'machine');
        return { text: IL.n1LowText(s), pad: d.padId, value: it.value, detail: it.detail };
      });
      check('needle low after a STOP in the dots: "The needle (N2) stopped low (Z 2.94 mm) at <pad>: … Home lifts it first", not N1 at 60.06',
        needleLow.text === 'The needle (N2) stopped low (Z 2.94 mm) at ' + needleLow.pad + ': look at the board under it. Home lifts it first, then homes.'
        && needleLow.value === 'needle low' && needleLow.detail === needleLow.text, needleLow);
      await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/probe'); });
      g1 = gcodeNext();
      await press2(page, '#btn-home');
      await waitText(page, 'pill-homed', /^Homed$/, 120000);
      l = lines(gcode(g1));
      const iRaise = l.indexOf('G1 Z31.5000 F600'), iHome = l.findIndex((x) => /^G28\b/.test(x));
      check('Home with N1 low: G1 Z31.5000 F600 before G28', iRaise >= 0 && iHome > iRaise, l.slice(0, 12));
      await page.waitForFunction(() => !IL.recoveryActive(), null, { timeout: 15000 }).catch(() => {});
      await page.evaluate(() => IL.go('#run/probe'));
      await waitText(page, 'reg-result', /^Stopped/, 20000).catch(() => {});
      const stoppedText = await textOf(page, 'reg-result').catch(() => '');
      check('#reg-result after the STOP: the surface was not changed, the seat still holds',
        /^Stopped \d\d:\d\d during probe point \d\sof\s\d: the board surface was not changed; the seat checked at/.test(stoppedText), stoppedText);

      // 7b. The board moved (the scene's seat 0.40 / 0.30 mm further): Register board says re-seated and probes again
      {
        const movedScene = JSON.parse(JSON.stringify(scene));
        movedScene.board.seat.affine[0][2] += 0.40;
        movedScene.board.seat.affine[1][2] += 0.30;
        fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(movedScene));
        fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
        await page.waitForTimeout(600);
        const regBefore = await page.evaluate(() => IL.S.plan.registration && IL.S.plan.registration.id);
        const surfBefore = await surfAt();
        g1 = gcodeNext();
        const t6 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
        await press2(page, '#btn-register');
        rs = await regEnd(t6);
        await page.waitForFunction((id) => { const v = IL.S.plan && IL.S.plan.registrationRun; return v && v.runId === id; }, rs.run.runId, { timeout: 20000 }).catch(() => {});
        await waitText(page, 'reg-result', /^Re-seated/, 15000).catch(() => {});
        const rsText = await textOf(page, 'reg-result');
        check('moved seat: #reg-result "Re-seated hh:mm: the board moved 0.5 mm"', rs.run.status === 'OK' && /^Re-seated \d\d:\d\d: the board moved 0\.(4[5-9]|5\d)\smm/.test(rsText), rsText);
        l = lines(gcode(g1));
        const regAfter = await page.evaluate(() => IL.S.plan.registration && IL.S.plan.registration.id);
        check('moved seat: a new registration, every spot touched again, a new surface', regAfter && regAfter !== regBefore
          && l.filter((x) => x === 'M106 P1 S180').length >= 5 && (await surfAt()) !== surfBefore, { regBefore, regAfter, touches: l.filter((x) => x === 'M106 P1 S180').length });
      }

      // 8. Do it by hand on Run: the engine's targets, three points, save ties the surface and plans again
      await page.evaluate(() => { IL.run.byHand = true; IL.render(); });
      await page.waitForSelector('#btn-probe-add', { state: 'visible', timeout: 10000 });
      const tgt = await page.evaluate(() => ({ rows: [...document.querySelectorAll('#probe-targets tbody tr')].map((tr) => [Number(tr.cells[1].textContent), Number(tr.cells[2].textContent)]),
        engine: IL.S.plan.registrationTargets.probes.map((q) => [Math.round(q.x * 100) / 100, Math.round(q.y * 100) / 100]) }));
      check('by hand: the foot shows Add point, the targets are the engine\'s', tgt.rows.length === tgt.engine.length
        && tgt.rows.every((rw, i) => Math.abs(rw[0] - tgt.engine[i][0]) < 0.011 && Math.abs(rw[1] - tgt.engine[i][1]) < 0.011), tgt);
      // every target: a surface must cover the dots (3 points over one corner would leave most of them outside)
      const nTargets = tgt.rows.length;
      for (let i = 0; i < nTargets; i++) {
        await page.waitForSelector('#btn-probe-add:enabled', { timeout: 10000 });
        await page.click('#btn-probe-add');
        if (await page.waitForSelector('#btn-probe-add.btn--press', { timeout: 1500 }).then(() => true, () => false)) {
          await page.waitForTimeout(450);
          await page.click('#btn-probe-add');
        }
        await page.waitForFunction((n) => IL.run.probe.points.length === n, i + 1, { timeout: 5000 });
      }
      check('by hand: a point at every target added (' + nTargets + ')', await page.evaluate(() => IL.run.probe.points.length) === nTargets);
      // the simulated operator lowers N1 onto the board at each target (no jog in this test): the Z the scene gives there
      await page.evaluate((sc) => {
        const a = sc.board.seat.affine, sf = sc.board.surface;
        const det = a[0][0] * a[1][1] - a[0][1] * a[1][0];
        for (const q of IL.run.probe.points) {
          const t = IL.run.targets[q.target];
          const dx = t.x - a[0][2], dy = t.y - a[1][2];
          const bx = (a[1][1] * dx - a[0][1] * dy) / det, by = (-a[1][0] * dx + a[0][0] * dy) / det;
          Object.assign(q, { x: t.x, y: t.y, z: Math.round((sf.z0 + sf.slopeX * bx + sf.slopeY * by) * 1000) / 1000 });
        }
        IL.render();
      }, scene);
      const plan3 = await page.evaluate(() => IL.S.plan.planId);
      await page.click('#btn-probe-save');
      await page.waitForFunction((id) => IL.S.plan && IL.S.plan.planId !== id, plan3, { timeout: 30000 }).catch(() => {});
      const s3 = await status();
      check('by hand: Save ties the board surface and plans again', !!s3.dispense.surfaces.board.boundToBoard
        && await page.evaluate((id) => IL.S.plan.planId !== id, plan3), s3.dispense.surfaces.board);

      // 9. Home at run: a STOP while idle; Start; the start angle; the run homes and checks Z by itself
      await post('api/stop');
      await waitText(page, 'pill-conn', /^Not connected$/, 30000);
      await page.click('#btn-start');
      await waitText(page, 'pill-conn', /^Connected$/, 60000);
      await answerI11();
      await page.waitForFunction(() => IL.ready().items.find((x) => x.key === 'angle').ready, null, { timeout: 15000 }).catch(() => {});
      await post('api/purge-row/reset');
      await page.evaluate(() => IL.attest.set('regulator', IL.S.status.dispense.profile.pressurePsi));
      await page.waitForFunction(() => !IL.recoveryActive(), null, { timeout: 15000 }).catch(() => {});
      // the plan is read again after the reconnect ("checking"): wait for it
      await page.waitForFunction(() => IL.ready().items[0].state === 'info' && IL.ready().canRun && !IL.S.planPending, null, { timeout: 20000 }).catch(() => {});
      const mach = await page.evaluate(() => { const r = IL.ready(); const it = r.items[0]; return { state: it.state, value: it.value, canRun: r.canRun, verdict: r.verdict.text }; });
      check('Run readiness: the machine item is a note "homes first", and a board can run', mach.state === 'info' && /^homes first/.test(mach.value) && mach.canRun, mach);
      check('Run readiness: the verdict reads "Ready to run · homes first"', /^Ready to run · homes first/.test(mach.verdict), mach.verdict);
      await page.evaluate(() => IL.go('#run/checks'));
      await page.waitForSelector('#btn-run', { timeout: 15000 });
      await page.screenshot({ path: path.join(OUT, 'run-checks-home-first.png') });
      // this board has paste on part of its pads (the stopped runs above): a run of two of the pads left, through the
      // run confirm as Records › Finish opens it (a whole board would take minutes in the simulator)
      await page.evaluate(() => {
        const p = IL.S.plan;
        const left = IL.run.padsLeft && IL.run.padsLeft.length ? IL.run.padsLeft
          : Array.from(new Set(p.dots.filter((d) => !IL.run.doneSet.has(d.index)).map((d) => d.padId)));
        IL.runFix('run-rest', null, { planId: p.planId, padIds: left.slice(0, 2) });
      });
      await page.waitForSelector('#dlg-run', { timeout: 10000 });
      const hf = await textOf(page, 'run-confirm-home').catch(() => '');
      check('run confirm: #run-confirm-home says it homes first, then 2 N1 touches check Z (the board\'s Z frame and the blank\'s)',
        /^Homes first/.test(hf) && /2 N1 touches .*check Z/.test(hf), hf);
      await page.screenshot({ path: path.join(OUT, 'run-confirm-home-first.png') });
      g1 = gcodeNext();
      for (const t of ['#run-tick-gauge', '#run-tick-tip', '#run-tick-paste', '#run-tick-hands']) if (!(await page.isChecked(t))) await page.check(t);
      await page.waitForSelector('#btn-run-go:enabled', { timeout: 10000 });
      await page.click('#btn-run-go');
      await page.waitForSelector('#dlg-run', { state: 'detached', timeout: 15000 });
      // the run's phase list once N1 touched: the Z check row gives every number with its unit, kept on its line (final
      // gate: "… mm from 5.430", then "5.430" with "mm" alone on the next line)
      const zRow = await page.waitForFunction(() => {
        const li = Array.from(document.querySelectorAll('#run-phases li')).find((x) => /Z check/.test(x.textContent) && /N1 touched/.test(x.textContent));
        return li ? li.textContent : null;
      }, null, { timeout: 120000, polling: 150 }).then((h) => h.jsonValue(), () => null);
      check('run: the phase list\'s Z check reads "N1 touched Z … mm, … mm from … mm", each number on one line with its unit',
        /N1 touched Z [\d.]+\smm, [−+-]?[\d.]+\smm from [\d.]+\smm/.test(zRow || '') && !/\d mm/.test(zRow || ''), zRow);
      const rr = await waitStatus(page, (x) => x.run && x.run.label === 'board' && !x.run.running, 300000);
      l = lines(gcode(g1));
      const iH = l.findIndex((x) => /^G28\b/.test(x)), iPress = l.indexOf('M106 P2 S255');
      const touches = l.slice(Math.max(0, iH), iPress < 0 ? undefined : iPress).filter((x) => x === 'M106 P1 S180').length;
      // a touch vouches only for the Z data of its own home period (review 1, ZCheck): the board surface saved by hand
      // above is from a later home than the needle frame and the blank, so one touch on the board and one on the blank
      check('run: G28, then two Z-check touches (the board\'s Z frame and the blank\'s), before the dispenser pump (M106 P2 S255)',
        iH >= 0 && iPress > iH && touches === 2, { iH, iPress, touches });
      check('run after the home and the Z check: OK, homed', rr.run.status === 'OK' && rr.machine.homed, { status: rr.run.status, message: rr.run.message });
      await waitText(page, 'pill-homed', /^Homed$/, 15000).catch(() => {});

      // 9b. The board's paste is the server's (rc1 review: the tab alone kept it): a new browser (no tab storage) sees
      // the same pads with paste and the same board number, and a run of the whole board on it asks "I wiped this
      // board" first (Go stays locked until it is ticked; the run would tell the engine)
      {
        await page.waitForFunction(() => !IL.S.planPending && IL.S.plan && IL.S.plan.pasted, null, { timeout: 15000 }).catch(() => {});
        await page.waitForTimeout(1000);
        const mine = await page.evaluate(() => ({ done: IL.run.doneSet.size, board: IL.S.plan.pasted.board, plan: IL.S.plan.planId }));
        const fresh = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        const tab = await fresh.newPage();
        tab.on('console', (m) => { if (m.type() === 'error') jsErrors.push('new tab: ' + m.text()); });
        await tab.goto(BASE + '#run/checks');
        await tab.waitForFunction((id) => window.IL && IL.S && IL.S.plan && IL.S.plan.planId === id && IL.S.plan.pasted, mine.plan, { timeout: 30000 }).catch(() => {});
        await tab.waitForTimeout(1500);
        const theirs = await tab.evaluate(() => ({ done: IL.run.doneSet.size, board: IL.S.plan.pasted.board, eyebrow: (document.getElementById('chk-eyebrow') || {}).textContent,
          verdict: IL.ready().verdict.text, all: !document.getElementById('run-all-link').hidden }));
        check('the board\'s paste is the server\'s: a new browser sees the same pasted dots and the same board number', mine.done > 0 && theirs.done === mine.done
          && theirs.board === mine.board && theirs.eyebrow === 'Board ' + mine.board + ' · plan ' + mine.plan.slice(0, 4), { mine, theirs });
        await tab.screenshot({ path: path.join(OUT, 'new-browser-same-board.png') });
        await tab.click(theirs.all ? '#run-all-link' : '#btn-run');
        await tab.waitForSelector('#dlg-run', { timeout: 10000 }).catch(() => {});
        await tab.evaluate(() => { IL.attest.set('regulator', IL.S.status.dispense.profile.pressurePsi); IL.attest.set('paste-life', true); });
        for (const t of ['#run-tick-gauge', '#run-tick-tip', '#run-tick-paste', '#run-tick-hands']) if (!(await tab.isChecked(t).catch(() => true))) await tab.check(t).catch(() => {});
        await tab.waitForTimeout(400);
        const cf = await tab.evaluate(() => { const w = document.getElementById('run-tick-wiped'); const go = document.getElementById('btn-run-go');
          return { wipe: w ? w.closest('label').textContent : null, go: go ? go.textContent : null, locked: !!go && go.disabled }; });
        await tab.screenshot({ path: path.join(OUT, 'new-browser-run-all-wiped-tick.png') });
        check('a whole-board run on the pasted board asks "I wiped this board", Go locked until it is ticked',
          !!cf.wipe && /^I wiped this board: no paste on its \d+ pads now/.test(cf.wipe) && /already has paste on \d+ pads/.test(cf.wipe) && cf.locked && /Tick 1 more to run/.test(cf.go), cf);
        await tab.click('#btn-run-cancel').catch(() => {});
        await fresh.close();
        // a board with every dot already (UI_DESIGN §8.1.7): "Board done · Next board", Next board the primary action,
        // no test run, "Run it again" second (its confirm asks "I wiped this board", above). Here the plan's paste as
        // the server would have it after the whole board, for a moment
        // (the page reads its plan again every 10 s: a read that lands meanwhile drops the paste set here, so the moment is
        // taken again then)
        let bd = null;
        for (let k = 0; k < 4 && !(bd && bd.same); k++) {
          await page.evaluate(() => {
            const p = IL.S.plan;
            if (!p.pwDone) IL.run.keepPasted = p.pasted;
            p.pasted = Object.assign({}, p.pasted, { board: p.pasted.board || 1, pads: Object.fromEntries(p.dots.map((d) => [d.padId, 9])) });
            p.pwDone = true;
            IL.go('#run/checks');
            IL.render();
          });
          // the readiness is taken again at the next status (IL.ready keeps it per status): wait for the strip to say so too
          await page.waitForFunction(() => IL.run.doneSet.size === IL.S.plan.dots.length && !document.getElementById('btn-run-next').hidden
            && /Board done/.test(document.getElementById('pill-engine').textContent), null, { timeout: 10000 }).catch(() => {});
          await page.screenshot({ path: path.join(OUT, 'board-done-next-board.png') });
          bd = await page.evaluate(() => {
            const r = IL.ready(), nb = document.getElementById('btn-run-next'), rb = document.getElementById('btn-run');
            return { verdict: r.verdict.text, done: !!r.verdict.done, strip: document.getElementById('pill-engine').textContent, next: !nb.hidden && nb.classList.contains('btn--primary'),
              test: !document.getElementById('btn-run-test').hidden, run: rb.textContent.trim(), runPrimary: rb.classList.contains('btn--primary'), same: !!IL.S.plan.pwDone, tries: 0 };
          });
          bd.tries = k + 1;
        }
        await page.evaluate(() => { if (IL.S.plan.pwDone) { IL.S.plan.pasted = IL.run.keepPasted; delete IL.S.plan.pwDone; } delete IL.run.keepPasted; IL.render(); });
        check('a board with every dot: "Board done · Next board", Next board the primary action, no test run, "Run it again" not primary',
          bd.done && bd.verdict === 'Board done · Next board' && /Board done/.test(bd.strip) && bd.next && !bd.test && /^Run it again · \d+ dots$/.test(bd.run) && !bd.runPrimary, bd);
      }

      // 10. Setup › Probe the blank: automatic, beside the purge row, the panel stays open with the result
      await nav(page, 'setup');
      await page.evaluate(() => IL.openView('probe-blank', { keep: true }));
      await waitText(page, 'blank-note', /beside the purge row/, 20000).catch(() => {});
      check('#blank-note: beside the purge row', /beside the purge row/.test(await textOf(page, 'blank-note').catch(() => '')), await textOf(page, 'blank-note').catch(() => ''));
      const t4 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      await press2(page, '#btn-blank-auto');
      const bs1 = await waitStatus(page, (x) => x.run && x.run.label === 'blank probe' && !x.run.running && x.run.startedAtMs > t4, 300000);
      await waitText(page, 'blank-result', /^(Saved|Not saved|Stopped)/, 20000).catch(() => {});
      const bres = await textOf(page, 'blank-result').catch(() => '');
      check('#blank-result: "Saved hh:mm: n points"', bs1.run.status === 'OK' && /^Saved \d\d:\d\d: \d+\spoints/.test(bres), { status: bs1.run.status, message: bs1.run.message, bres });
      await page.screenshot({ path: path.join(OUT, 'blank-probe-result.png') });
      const pr = bs1.dispense.purgeRow;
      const used = Array.from({ length: pr.usedSpots }, (_, i) => [pr.x0Mm + i * pr.pitchXMm, pr.y0Mm + i * pr.pitchYMm]);
      const bp = bs1.dispense.surfaces.blank.points.map((q) => (Array.isArray(q) ? q : [q.x, q.y]));
      const near = bp.filter((q) => used.some((u) => Math.hypot(q[0] - u[0], q[1] - u[1]) < 1.9));
      check('blank probe: at least 4 points, none within 1.9 mm of a used purge spot', bp.length >= 4 && near.length === 0, { n: bp.length, near });
      const bh = await page.evaluate(() => { const d = document.getElementById('blank-by-hand'); return !!d && !!d.closest('#panel-body'); });
      await openDetails(page, 'blank-by-hand');
      await page.waitForSelector('#btn-blank-add', { state: 'visible', timeout: 5000 }).catch(() => {});
      const bhFoot = await page.evaluate(() => ['btn-blank-add', 'btn-blank-save'].every((id) => { const b = document.getElementById(id); return b && !!b.closest('#panel-foot') && b.offsetParent !== null; }));
      check('blank by hand: in the body; open, the foot shows Add and Save', bh && bhFoot, { bh, bhFoot });
      for (let i = 0; i < 3; i++) {
        await page.click('#btn-blank-add');
        await page.waitForFunction((n) => /Add point (\d+)/.exec(document.getElementById('btn-blank-add').textContent)[1] === String(n), i + 2, { timeout: 5000 }).catch(() => {});
      }
      check('blank by hand: three points added, Save offered', /Save 3 points/.test(await textOf(page, 'btn-blank-save')), await textOf(page, 'btn-blank-save'));
      await page.evaluate(() => IL.panel.close());

      // 11. The home setting
      await page.evaluate(() => IL.go('#setup/settings'));
      await page.waitForSelector('#set-homebefore', { timeout: 10000 });
      for (const v of ['always', 'whenNeeded']) {
        // the save before re-reads the settings: change the field once it has
        await page.waitForFunction(() => !document.getElementById('btn-settings').hasAttribute('aria-busy') && IL.S.config, null, { timeout: 30000 });
        await page.selectOption('#set-homebefore', v);
        await page.waitForSelector('#btn-settings:enabled', { timeout: 10000 });
        await page.click('#btn-settings');
        const c = await waitStatus(page, (x) => x.dispense.homeBeforeRun === v, 10000).then(() => true, () => false);
        check('Settings: "Before Register board, Probe and Run" saves ' + v, c);
      }

      // 12. A fiducial not found (FID2 hidden in the scene): the REG fixes, then a plan without fiducial correction
      const hidden = Object.assign({}, scene, { board: Object.assign({}, scene.board, { hideFiducials: ['FID2'] }) });
      fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(hidden));
      fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
      await page.waitForTimeout(600);
      await nav(page, 'run');
      await page.evaluate(() => IL.go('#run/probe'));
      await page.waitForSelector('#btn-register:enabled', { timeout: 20000 });
      const t5 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
      await press2(page, '#btn-register');
      rs = await regEnd(t5);
      await waitText(page, 'reg-result', /^Not registered/, 20000).catch(() => {});
      const nf = await textOf(page, 'reg-result');
      check('#reg-result: "Not registered: FID2 not found within 8.0 mm"', rs.run.status === 'REFUSED' && /^Not registered: FID2 not found within 8\.0\smm/.test(nf), nf);
      const fixes = await page.$$eval('#reg-result [data-fix]', (bs) => bs.map((b) => b.textContent.trim()));
      check('REG fixes in order: look with the camera, change board, plan without correction, do it by hand (Register again in the foot)',
        JSON.stringify(fixes) === JSON.stringify(['Look with the camera', 'Change board', 'Plan without fiducial correction', 'Do it by hand'])
        && /^Register again/.test(await textOf(page, 'btn-register')), { fixes, foot: await textOf(page, 'btn-register') });
      const fixRows = await page.$$eval('#reg-result .ri__act [data-fix]', (bs) => ({ vw: window.innerWidth,
        rows: new Set(bs.map((b) => Math.round(b.getBoundingClientRect().top))).size,
        inside: bs.every((b) => { const r = b.getBoundingClientRect(), c = b.closest('.ri').getBoundingClientRect(); return r.left >= c.left && r.right <= c.right; }) }));
      check('REG fixes side by side in the narrow card: two rows of two, inside the card (not one per line)', fixRows.rows <= 2 && fixRows.inside, fixRows);
      await page.screenshot({ path: path.join(OUT, 'register-fid-not-found.png') });
      fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(scene));
      fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
      const plan5 = await page.evaluate(() => IL.S.plan.planId);
      await page.click('#reg-result [data-fix="plan-unregistered"]');
      await page.waitForFunction((id) => IL.S.plan && IL.S.plan.planId !== id, plan5, { timeout: 30000 }).catch(() => {});
      await page.evaluate(() => IL.go('#run/probe'));
      await waitText(page, 'reg-correction', /no fiducial correction/, 10000).catch(() => {});
      {
        // Z after a home with no point left to check it on (a plan's I4): a blocker of its own whose fix is the needle Z
        // frame, never Register board (it measures after the home, so the run would stay refused)
        const zr = await page.evaluate(() => {
          const msg = 'Z must be checked after the home at 07:38, but no point measured before it is left to touch (no blank or board '
            + 'surface, no needle-frame X/Y): record the needle frame again';
          const p = JSON.parse(JSON.stringify(IL.S.plan));
          p.interlocks = Object.assign({}, p.interlocks, { ok: false, violations: (p.interlocks.violations || []).concat([{ code: 'I4', message: msg }]) });
          const r = IL.readiness(IL.S.status, p, IL.readyCtx());
          const x = r.extras.find((e) => e.key === 'I4-z');
          const sf = r.items.find((e) => e.key === 'surface');
          return { extra: x ? { state: x.state, fixes: x.fixes } : null, surface: sf.state, surfaceText: sf.detail, canRun: r.canRun,
            fixes: IL.fixesForCode('I4', IL.S.status, msg) };
        });
        check('I4 "no point measured before the home": a Z check blocker with "Open Needle Z frame", not on the surface item',
          zr.extra && zr.extra.state === 'block' && zr.extra.fixes.length === 1 && zr.extra.fixes[0].href === '#setup/needle'
          && !/no point measured/.test(zr.surfaceText) && !zr.canRun && zr.fixes[0].href === '#setup/needle', zr);
      }
      check('Plan without fiducial correction: no registration, #reg-correction says so',
        await page.evaluate(() => IL.S.plan.registration === null) && /^no fiducial correction/.test(await textOf(page, 'reg-correction')), await textOf(page, 'reg-correction'));

      // NEXT, the nav subline and the verdict agree (rc1 review): with an item blocked (here the purge row not set) and the
      // regulator still to read, NEXT is the blocked item the verdict names, not the regulator
      {
        const nx = await page.evaluate(() => {
          const s = JSON.parse(JSON.stringify(IL.S.status));
          s.dispense.purgeRow = Object.assign({}, s.dispense.purgeRow, { defined: false });
          const r = IL.readiness(s, IL.S.plan, Object.assign(IL.readyCtx(), { attest: {} }));
          return { next: r.next && r.next.key, short: r.next && r.next.short, verdict: r.verdict.text, regulator: r.items.find((x) => x.key === 'regulator').state };
        });
        check('NEXT is the blocked item the verdict names (purge row), not the regulator that only needs you', nx.regulator === 'need' && nx.next === 'purge'
          && nx.verdict === 'Blocked · ' + nx.short, nx);
        // a plan that purges more spots than the row has (two dot sizes: 13 of 10): the Run nav names it in a form that fits
        // its subline at 1440 px (it never wraps, and "Purge row too short" was cut)
        await page.setViewportSize({ width: 1440, height: 1000 });
        const nv = await page.evaluate(async () => {
          // the readiness and the board's done dots are taken again at the next status: wait for it, then for the render
          const polled = () => new Promise((res) => { const t0 = IL.S.statusAt;
            const tick = () => (IL.S.statusAt !== t0 ? requestAnimationFrame(() => requestAnimationFrame(res)) : setTimeout(tick, 50)); tick(); });
          const p0 = IL.S.plan, row = IL.S.status.dispense.purgeRow;
          // a clean board (no paste: else the nav offers to finish it) whose plan purges one spot more than the row has.
          // The page reads its plan again every 10 s (IL.planRefresh): one that lands between the render and the verdict
          // put the server's plan back under the readiness (final gate: the nav read "Purge row short", the verdict "8 of
          // 9 · …"), so a read the plan changed under is taken again
          let out = null;
          for (let k = 0; k < 4 && !out; k++) {
            const mine = Object.assign({}, p0, { purgeSpotsNeeded: row.spots + 1,
              pasted: Object.assign({}, p0.pasted, { board: null, sinceMs: null, dots: 0, pads: {} }) });
            IL.S.plan = mine;
            await polled();
            const sub = document.querySelector('#tab-btn-run .nav__s');
            const seen = { sub: sub.textContent, cut: sub.scrollWidth > sub.clientWidth + 0.5, verdict: IL.ready().verdict.text, tries: k + 1 };
            if (IL.S.plan === mine) out = seen;
          }
          IL.S.plan = p0;
          await polled();
          return out || { sub: null, verdict: 'the plan was read again under every try' };
        });
        check('Run nav: a purge row too short reads "Purge row short" whole (not "Purge row too sho…")', nv.sub === 'Purge row short' && !nv.cut
          && nv.verdict === 'Blocked · purge row too short', nv);
      }

      // 13. What the engine refuses, the UI locks or says (the final round's engine fixes, E1 and E2). Pure checks on the
      // status: OpenPnP homing or jogging by itself (409 "OpenPnP is busy"), an upload reading its file
      // (status.boardImport.reading, 409 on every engine endpoint); a clean-pad check refused at its start (the pressure
      // hold switched on after it was queued) shows its reason on the card, not "no pad was checked"
      {
        const locks = await page.evaluate(() => {
          const s = IL.S.status;
          const idle = { run: Object.assign({}, s.run, { running: false }), dispense: Object.assign({}, s.dispense, { busy: false }), padCalibration: null };
          const busy = Object.assign({}, s, idle, { machine: Object.assign({}, s.machine, { enabled: true, busy: true }) });
          const reading = Object.assign({}, s, idle, { boardImport: { reading: true } });
          return { busy: IL.lockReason(busy, { connected: true }), busyPlan: IL.lockReason(busy, { pressureOff: false }), reading: IL.lockReason(reading),
            readingNoIdle: IL.lockReason(reading, { idle: false, pressureOff: false }), field: s.boardImport ? s.boardImport.reading : 'missing' };
        });
        check('locks: OpenPnP busy homing or jogging locks what uses the machine with the engine\'s words (not Plan)',
          locks.busy === 'OpenPnP is busy (homing or moving): wait for it to finish' && locks.busyPlan === null, locks);
        check('locks: status.boardImport.reading (false now) locks Run, Register board, the probes and the other engine actions while true',
          locks.field === false && locks.reading === 'A board file is being read (an upload takes up to about 20 s): wait for it to finish' && locks.readingNoIdle === null, locks);
        await page.evaluate(() => IL.go('#run/checks'));
        await page.waitForSelector('#clean-result', { timeout: 15000 });
        const finished = (await status()).server.timeMs + 1000;
        await page.evaluate((t) => IL.emit('taskend', { runId: 'pw-clean-refused', label: 'clean-pad check', status: 'REFUSED', running: false,
          planId: IL.S.plan.planId, finishedAtMs: t, message: 'the pressure hold is on: Pressure off first' }), finished);
        await page.waitForFunction(() => /Refused \d\d:\d\d: the pressure hold is on: Pressure off first\./.test(document.getElementById('clean-result').textContent), null, { timeout: 5000 }).catch(() => {});
        const cr = await textOf(page, 'clean-result');
        check('clean-pad check refused at its start: "Refused hh:mm: the pressure hold is on: Pressure off first." on its card',
          /Refused \d\d:\d\d: the pressure hold is on: Pressure off first\./.test(cr) && !/No pad was checked/.test(cr), cr);
        await page.evaluate(() => { IL.run.ccRefused = null; IL.render(); });
      }

      // 13b. Probe only (F10, fix round 2): the board file without its fiducials, planned where the scene seats it. Run ›
      // Register offers "Probe only (X/Y as planned)" with its note (Do it by hand stays), so does the readiness item; a
      // Register board with probe "auto" is refused (REG) naming that way out, and its fix-it probes the board where it is
      // planned: no fiducial, no registration, the surface tied, planned again. Then the board with fiducials again, as 12
      // left it (14 registers it)
      {
        const src = await page.evaluate(() => IL.S.plan.source);
        const nofid = path.join(path.dirname(path.dirname(SIM_SCENE)), 'nofid-' + Date.now().toString(36) + '.board.xml');
        fs.writeFileSync(nofid, fs.readFileSync(src.file, 'utf8').replace(/\s*<placement [^>]*type="Fiducial"[\s\S]*?<\/placement>/g, ''));
        const a = (src.rotation || 0) * Math.PI / 180;
        const bare = JSON.parse(JSON.stringify(scene));
        Object.assign(bare.board, { file: path.basename(nofid), fiducials: [], hideFiducials: [] });
        bare.board.seat.affine = [[Math.cos(a), -Math.sin(a), src.x], [Math.sin(a), Math.cos(a), src.y]];
        fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(bare));
        fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
        await page.waitForTimeout(600);
        const p0 = await page.evaluate((b) => IL.api('POST', '/api/plan', b).then((p) => { IL.setPlan(p); return p.registrationTargets; }),
          { source: 'file', file: nofid, x: src.x, y: src.y, z: src.z, rotation: src.rotation || 0 });
        check('probe only: the board without fiducials is planned with registrationTargets.probeOnly', p0.probeOnly === true && p0.fiducialCount === 0
          && /^REG: the board has 0 fiducials/.test(p0.error || ''), { probeOnly: p0.probeOnly, error: p0.error });
        await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/probe'); });
        await waitText(page, 'btn-register', /^Probe only \(X\/Y as planned\)/, 20000).catch(() => {});
        const po = await page.evaluate(() => {
          const b = document.getElementById('btn-register');
          const it = IL.ready().items.find((x) => x.key === 'surface');
          return { btn: b.textContent.trim(), primary: b.classList.contains('btn--primary'), locked: b.disabled, note: document.getElementById('register-note').textContent,
            why: document.getElementById('register-why').hidden ? null : document.getElementById('register-why').textContent,
            byHand: !document.getElementById('probe-by-hand').hidden, surface: { state: it.state, value: it.value, short: it.short, fixes: (it.fixes || []).map((f) => f.fix + ':' + f.label) } };
        });
        check('probe only: the primary "Probe only (X/Y as planned) · ≈ m:ss" with the note "This board has no fiducials: N1 probes it where it is planned; its X/Y are not checked."; Do it by hand stays',
          /^Probe only \(X\/Y as planned\) · ≈ \d+:\d\d$/.test(po.btn) && po.primary && !po.locked && !po.why && po.byHand
          && po.note.includes('This board has no fiducials: N1 probes it where it is planned; its X/Y are not checked.'), po);
        check('probe only: the readiness item offers "Probe only (X/Y as planned)" first, then Do it by hand', po.surface.state !== 'ok'
          && po.surface.fixes[0] === 'register-probe-only:Probe only (X/Y as planned)' && po.surface.fixes[1] === 'probe-by-hand:Do it by hand', po.surface);
        // the strip and the nav name the item by its fix's action, as a board with fiducials reads "register board"
        // (board-import 8)
        check('probe only: the readiness item says "probe board" (blocked: "probe again"), as its fix Probe only, not "register board"',
          (po.surface.state === 'need' && po.surface.value === 'probe' && po.surface.short === 'probe board')
          || (po.surface.state === 'block' && po.surface.value === 'probe again'), po.surface);
        await page.screenshot({ path: path.join(OUT, 'register-probe-only.png') });
        // Register board with probe "auto": the REG refusal names the way out, and its first fix-it is Probe only
        g1 = gcodeNext();
        await page.evaluate(() => IL.act(document.getElementById('btn-register'), () => IL.api('POST', '/api/plan/' + IL.S.plan.planId + '/register', { probe: 'auto' })));
        await page.waitForSelector('#reg-foot .refusal [data-fix]', { timeout: 10000 }).catch(() => {});
        const rf = await page.evaluate(() => ({ text: (document.querySelector('#reg-foot .refusal .refusal__t') || {}).textContent,
          fixes: Array.from(document.querySelectorAll('#reg-foot .refusal [data-fix]')).map((b) => [b.dataset.fix, b.textContent.trim(), b.classList.contains('btn--primary')]) }));
        check('probe only: Register board with probe "auto" is refused (REG … Or probe it only, where it is planned: Register with probe "always".) and offers Probe only first',
          /^Refused: REG: the board has 0 fiducials; .* Or probe it only, where it is planned: Register with probe "always"\.$/.test(rf.text || '')
          && JSON.stringify(rf.fixes.map((f) => f[0])) === JSON.stringify(['register-probe-only', 'look-camera', 'board-step', 'probe-by-hand'])
          && rf.fixes[0][1] === 'Probe only (X/Y as planned)' && rf.fixes[0][2], rf);
        check('probe only: the refused Register board moved nothing', !lines(gcode(g1)).some((x) => /^(G0|G1|M106)\b/.test(x)));
        await page.screenshot({ path: path.join(OUT, 'register-probe-only-refusal.png') });
        // its fix-it: N1 probes the board where it is planned
        g1 = gcodeNext();
        const t10 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
        await press2(page, '#reg-foot .refusal [data-fix="register-probe-only"]');
        rs = await regEnd(t10);
        await page.waitForFunction((id) => { const v = IL.S.plan && IL.S.plan.registrationRun; return v && v.runId === id; }, rs.run.runId, { timeout: 20000 }).catch(() => {});
        await waitText(page, 'reg-result-t', /^Probed/, 15000).catch(() => {});
        const pv = await page.evaluate(() => ({ t: document.getElementById('reg-result-t').textContent, v: IL.S.plan.registrationRun, reg: IL.S.plan.registration,
          fixes: Array.from(document.querySelectorAll('#reg-result button')).map((b) => b.id || b.dataset.fix), cont: !document.getElementById('btn-reg-continue').hidden }));
        check('#reg-result: "Probed hh:mm: no fiducials: probed only, X/Y as planned; N points, worst X mm; planned again."', rs.run.status === 'OK'
          && /^Probed \d\d:\d\d: no fiducials: probed only, X\/Y as planned; \d+\spoints, worst\s\d\.\d{3}\smm; planned again\.$/.test(pv.t), { t: pv.t, message: rs.run.message });
        const touches = lines(gcode(g1)).filter((x) => x === 'M106 P1 S180').length;
        check('probe only: no fiducial, no registration stored; one N1 touch per point; Probe again and Checks › offered', pv.v && pv.v.probeOnly === true
          && pv.v.fiducials.length === 0 && pv.v.registrationId === null && pv.reg === null && touches === pv.v.probe.points.length
          && JSON.stringify(pv.fixes) === JSON.stringify(['btn-reg-probe']) && pv.cont, { touches, points: pv.v && pv.v.probe.points.length, fixes: pv.fixes, reg: pv.reg });
        check('probe only: the board surface is tied to the new plan', await page.waitForFunction(() => IL.ready().items.find((x) => x.key === 'surface').state === 'ok', null, { timeout: 10000 })
          .then(() => true, () => false), await page.evaluate(() => IL.ready().items.find((x) => x.key === 'surface')));
        await page.screenshot({ path: path.join(OUT, 'register-probe-only-result.png') });
        fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(scene));
        fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
        await page.evaluate((b) => IL.api('POST', '/api/plan', b).then((p) => IL.setPlan(p)), { source: 'file', file: src.file, x: src.x, y: src.y, z: src.z,
          rotation: src.rotation || 0, registration: 'none' });
        await page.evaluate(() => { IL.run.byHand = false; IL.go('#run/checks'); });
      }

      // 14. Z moved at a home (AUTO_REGISTER_DESIGN.md §2.4): the scene's board and blank seal 0.8 mm higher after a home,
      // so Check Z fails ("Z moved"). The Z check blocker leads through the remedy under the same home, one step at a time:
      // the needle frame, the blank, the touch-off (or the frame's own offset), Register board; then nothing blocks. The
      // scene stays 0.8 mm higher: every height is measured again at it, so the next files find a consistent machine
      {
        const zUp = JSON.parse(JSON.stringify(scene));
        zUp.board.surface.z0 += 0.8;
        zUp.blank.surface.z0 += 0.8;
        fs.writeFileSync(SIM_SCENE + '.tmp', JSON.stringify(zUp));
        fs.renameSync(SIM_SCENE + '.tmp', SIM_SCENE);
        await page.waitForTimeout(600);
        const hr = await page.request.post(BASE + 'api/machine/home', { data: { wait: true }, timeout: 180000 });
        check('Z moved: homed (every height now predates the home)', hr.ok(), [hr.status(), (await hr.text()).slice(0, 300)]);
        await page.request.post(BASE + 'api/machine/z-check', { data: { wait: true }, timeout: 180000 });
        const failedNow = (x) => !!(x.machine.zCheck && x.machine.zCheck.last && x.machine.zCheck.last.pass === false && x.machine.zCheck.last.homeAtMs === x.machine.zCheck.homeAtMs);
        const zf = await waitStatus(page, failedNow, 60000);
        check('Z moved: Check Z fails by about 0.8 mm', Math.abs(zf.machine.zCheck.last.deltaMm - 0.8) < 0.1, zf.machine.zCheck.last);
        await page.evaluate(() => IL.go('#run/checks'));
        /**
         * The remedy's step now (IL.zMoved) and the Z check blocker on Run › Checks with its fix-its, once the page's own
         * status poll has the step wanted and the plan is read again (the card shows until the step is done).
         */
        const zStep = async (want) => {
          await page.waitForFunction((w) => {
            const z = IL.zMoved(IL.S.status);
            const card = document.querySelector('#chk-needs .ri[data-key="I4-z"]');
            return !IL.S.planPending && !!z && z.step === w && (w === 'done' ? !card : !!card);
          }, want, { timeout: 30000 }).catch(() => {});
          await page.waitForTimeout(300);
          return page.evaluate(() => {
            const z = IL.zMoved(IL.S.status);
            const card = document.querySelector('#chk-needs .ri[data-key="I4-z"]');
            return { step: z ? z.step : null, fixes: z ? z.fixes.map((f) => f.label) : [], text: card ? card.querySelector('.ri__d').textContent : null,
              card: card ? Array.from(card.querySelectorAll('button[data-fix]')).map((b) => [b.dataset.fix, b.textContent.trim()]) : null, canRun: IL.ready().canRun };
          });
        };
        let st = await zStep('frame');
        await page.screenshot({ path: path.join(OUT, 'z-moved-1-frame.png') });
        check('Z moved 1: the blocker names the move, the remedy in its order, and offers "Open Needle Z frame"', st.step === 'frame' && !st.canRun && !!st.card
          && st.card[0] && st.card[0][0] === 'go:#setup/needle' && /^Z moved 0\.\d\d mm/.test(st.text || '')
          && /Record the needle frame again with both tips \(Setup › Needle Z frame\), probe the blank, take the places' Z again with N1 \(Setup › Places\), touch off and register the board again\./.test(st.text || ''), st);
        // the frame from K would give the old offset whatever N1 touches (K moves by twice a zero shift): the card
        // switches to Both tips, says why, and From K is locked (the engine refuses K until the next home)
        await page.click('#chk-needs .ri[data-key="I4-z"] button[data-fix="go:#setup/needle"]');
        await page.waitForSelector('#btn-anchor', { state: 'visible', timeout: 15000 });
        await page.waitForFunction(() => !document.getElementById('anchor-k-why').hidden, null, { timeout: 10000 }).catch(() => {});
        await page.screenshot({ path: path.join(OUT, 'z-moved-1-needle-both-tips.png') });
        const kw = await page.evaluate(() => {
          const seg = Array.from(document.querySelectorAll('#card-needle .seg button'));
          return { why: document.getElementById('anchor-k-why').hidden ? null : document.getElementById('anchor-k-why').textContent,
            kLocked: seg[0].disabled, both: seg[1].getAttribute('aria-pressed'), kBtn: !document.getElementById('btn-anchor-k').hidden };
        });
        check('Z moved 1: Needle Z frame switches to Both tips and locks From K, saying why (K moves by twice the shift)',
          kw.kLocked && kw.both === 'true' && !kw.kBtn && /^K was measured under another home: Z moved 0\.\d\d mm at the home of \d\d:\d\d, and K moves by about twice that\. Record the needle frame with both tips touching the same spot\.$/.test(kw.why || ''), kw);
        const kr = await page.request.post(BASE + 'api/needle/anchor', { data: { surfaceZ: 10.8, needleContactRawSum: 65.59 } });
        check('Z moved 1: the engine refuses the frame from K (409) while the check of this home failed', kr.status() === 409, [kr.status(), (await kr.text()).slice(0, 200)]);
        // the operator touches the blank with both tips: 0.8 mm higher each (the SIM's needle sits 2.59 mm under N1)
        await page.fill('#anchor-n1', '10.8');
        await page.fill('#anchor-n2', '8.21');
        await page.click('#btn-anchor');
        await waitStatus(page, (x) => x.dispense.needle.anchor && Math.abs(x.dispense.needle.anchor.surfaceZMm - 10.8) < 1e-6, 10000);
        await page.evaluate(() => IL.go('#run/checks'));
        st = await zStep('blank');
        await page.screenshot({ path: path.join(OUT, 'z-moved-2-blank.png') });
        check('Z moved 2: after the needle frame, the fix is "Probe the blank…"', st.step === 'blank' && !!st.card && st.card[0] && st.card[0][0] === 'view:probe-blank', st);
        await page.click('#chk-needs .ri[data-key="I4-z"] button[data-fix="view:probe-blank"]');
        // the view keeps "Do it by hand" open from step 10: the automatic probe is the one this step wants
        await page.waitForSelector('#blank-by-hand', { state: 'attached', timeout: 15000 });
        await page.evaluate(() => { const d = document.getElementById('blank-by-hand'); if (d.open) d.open = false; });
        await page.waitForSelector('#btn-blank-auto', { state: 'visible', timeout: 15000 });
        const t7 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
        await press2(page, '#btn-blank-auto');
        const bz = await waitStatus(page, (x) => x.run && x.run.label === 'blank probe' && !x.run.running && x.run.startedAtMs > t7, 300000);
        check('Z moved 2: the blank probed again at its new height', bz.run.status === 'OK' && bz.dispense.surfaces.blank.points.every((q) => Math.abs((Array.isArray(q) ? q[2] : q.z) - 10.8) < 0.1),
          { status: bz.run.status, message: bz.run.message });
        await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/checks'); });
        // the places moved with the surfaces: each one set before the home is taken again with N1 (the touch-off primes
        // at the waste, and the engine refuses a place set before the home while the check of this home failed)
        st = await zStep('places');
        await page.screenshot({ path: path.join(OUT, 'z-moved-3-places.png') });
        const mp = (await status()).machine.zCheck.places.map((x) => x.name);
        check('Z moved 3: after the blank, "Retake Z with N1" for each place set before the home, the waste among them',
          st.step === 'places' && mp.includes('waste') && !!st.card && st.card.length === Math.min(3, mp.length)
          && st.card.every(([f, t], i) => f === 'go:#setup/places/' + encodeURIComponent(mp[i]) && t === 'Retake Z with N1 (' + mp[i] + ')'), { st, mp });
        const locs0 = (await status()).dispense.locations;
        await page.click('#chk-needs .ri[data-key="I4-z"] button[data-fix="go:#setup/places/waste"]');
        await page.waitForSelector('tr[data-place="waste"] input[data-axis=z]', { state: 'visible', timeout: 15000 });
        await page.waitForTimeout(400);
        await page.screenshot({ path: path.join(OUT, 'z-moved-3-places-card.png') });
        const ws = await page.evaluate(() => {
          const tr = document.querySelector('tr[data-place="waste"]');
          const b = tr.querySelector('button[data-action=save]');
          const bu = document.querySelector('.bringup button[data-step="places"]');
          return { invalid: tr.querySelector('input[data-axis=z]').classList.contains('input--invalid'), save: b.textContent, locked: b.disabled, why: b.title,
            state: document.querySelector('#card-places .card__state').textContent, bu: bu ? { state: bu.parentElement.dataset.state, title: bu.title } : null };
        });
        check('Z moved 3: Places flags the waste Z, "Retake Z" locked until N1 takes it, the card says which places',
          ws.invalid && ws.save === 'Retake Z' && ws.locked && /^Z moved 0\.\d\d mm at the home of \d\d:\d\d: take this Z again with N1 touching/.test(ws.why) && /^Z moved · retake waste/.test(ws.state), ws);
        check('Z moved 3: Setup\'s bring-up step Places is blocked and names the places to retake (not ok while the engine refuses them)',
          !!ws.bu && ws.bu.state === 'block' && ws.bu.title === 'Places: Z moved at this home: take the Z of ' + mp.join(', ') + ' again with N1 touching.', ws.bu);
        // at 1024 px the page scrolls under the sticky strip: a deep link puts its card's title under the strip, not behind it
        await page.setViewportSize({ width: 1024, height: 768 });
        await page.evaluate(() => IL.go('#run/checks'));
        await page.waitForTimeout(400);
        await page.evaluate(() => IL.go('#setup/places'));
        await page.waitForTimeout(1500);
        const deep = await page.evaluate(() => {
          const strip = document.querySelector('.strip').getBoundingClientRect(), band = document.querySelector('.stop-band');
          const under = Math.max(strip.bottom, band && !band.hidden ? band.getBoundingClientRect().bottom : 0);
          const title = document.querySelector('#card-places .card__title, #card-places h2, #card-places h3').getBoundingClientRect();
          return { titleTop: Math.round(title.top), under: Math.round(under), scrollY: Math.round(scrollY) };
        });
        await page.screenshot({ path: path.join(OUT, 'z-moved-3-places-1024.png') });
        await page.setViewportSize({ width: 1440, height: 1000 });
        check('Z moved 3: at 1024 px #setup/places scrolls the card\'s title to under the strip, not behind it', deep.scrollY > 0 && deep.titleTop >= deep.under, deep);
        await page.evaluate(() => IL.go('#setup/places/waste'));
        await page.waitForSelector('tr[data-place="waste"] input[data-axis=z]', { state: 'visible', timeout: 15000 });
        // the operator takes each place's Z again with N1: 0.8 mm higher, as every surface of the scene
        for (const n of mp) {
          const row = page.locator('tr[data-place="' + n + '"]');
          await row.locator('input[data-axis=z]').fill(String(Number((locs0[n].z + 0.8).toFixed(3))));
          await row.locator('button[data-action=save]').click();
          await waitStatus(page, (x) => !(x.machine.zCheck.places || []).some((q) => q.name === n), 10000);
        }
        await page.evaluate(() => IL.go('#run/checks'));
        st = await zStep('touch-off');
        await page.screenshot({ path: path.join(OUT, 'z-moved-4-touch-off.png') });
        check('Z moved 4: after the places, "Run a touch-off" only (not the frame\'s own offset: Z moved at this home)', st.step === 'touch-off' && !!st.card
          && JSON.stringify(st.card) === JSON.stringify([['go:#calibrate/touch-off', 'Run a touch-off']]), st);
        // the SIM scene has no paste for the touch-off's dots: the operator confirms the measured both-tips frame on the
        // Needle Z frame card instead (still allowed, no longer offered by the remedy)
        await page.evaluate(() => IL.go('#setup/needle'));
        await page.waitForSelector('#btn-needle-offset', { state: 'visible', timeout: 15000 });
        await page.click('#btn-needle-offset');
        await waitStatus(page, (x) => x.dispense.needle.touchedOff, 10000);
        await page.evaluate(() => IL.go('#run/checks'));
        st = await zStep('register');
        await page.screenshot({ path: path.join(OUT, 'z-moved-5-register.png') });
        check('Z moved 5: after the touch-off, "Register board"', st.step === 'register' && !!st.card && st.card[0] && st.card[0][0] === 'register', st);
        const t8 = Date.now() + (await page.evaluate(() => IL.S.skewMs));
        await press2(page, '#chk-needs .ri[data-key="I4-z"] button[data-fix="register"]');
        const rz = await regEnd(t8);
        check('Z moved 5: Register board measures the board again', rz.run.status === 'OK', { status: rz.run.status, message: rz.run.message });
        await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/checks'); });
        st = await zStep('done');
        check('Z moved: after the remedy nothing is left to measure, no Z check blocker (the same home)', st.step === 'done' && !st.card, st);
      }

      // 15. A board top Z planned 1.7 mm under the needle frame (planning takes up to 2.0 mm, the probe goes 1.5 mm under the
      // higher of the two): Register board says why before anything moves, with the Board step, the Needle Z frame and Do
      // it by hand; planned back at its Z, the reason goes
      {
        const src = await page.evaluate(() => IL.S.plan.source);
        const anchorZ = (await status()).dispense.needle.anchor.surfaceZMm;
        const lowZ = Number((anchorZ - 1.7).toFixed(3));
        await page.evaluate((b) => IL.api('POST', '/api/plan', b).then((p) => IL.setPlan(p)), { source: 'file', file: src.file, x: src.x, y: src.y, z: lowZ, rotation: src.rotation || 0 });
        await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/probe'); });
        await page.waitForFunction(() => /cannot reach that height/.test(document.getElementById('register-why').textContent), null, { timeout: 20000 }).catch(() => {});
        await page.screenshot({ path: path.join(OUT, 'register-board-too-low.png') });
        const rw = await page.evaluate(() => ({ text: document.getElementById('register-why').textContent, shown: !document.getElementById('register-why').hidden,
          links: Array.from(document.querySelectorAll('#register-why button')).map((b) => b.textContent.trim()), locked: document.getElementById('btn-register').disabled }));
        check('Register board: a board top 1.7 mm under the needle frame is refused before anything moves, with Board step · Needle Z frame · Do it by hand',
          rw.shown && rw.locked && new RegExp('^the board top Z ' + lowZ.toFixed(3).replace('.', '\\.') + ' lies 1\\.700 mm under the needle-frame surface .*: it cannot reach that height\\. Set the board top Z to roughly the N1 contact height')
            .test(rw.text) && JSON.stringify(rw.links) === JSON.stringify(['Board step', 'Needle Z frame', 'Do it by hand']), rw);
        await page.evaluate((b) => IL.api('POST', '/api/plan', b).then((p) => IL.setPlan(p)), { source: 'file', file: src.file, x: src.x, y: src.y, z: src.z, rotation: src.rotation || 0 });
        await page.waitForFunction(() => !/cannot reach that height/.test(document.getElementById('register-why').textContent), null, { timeout: 20000 }).catch(() => {});
        check('Register board: planned back at the board\'s Z, the reason goes', !/cannot reach that height/.test(await textOf(page, 'register-why')), await textOf(page, 'register-why'));
      }
    }

    check('no native dialogs (confirm / alert / prompt)', dialogs.length === 0, dialogs);
    // refusals answer 4xx on purpose (e.g. none expected here); the browser logs those as resource errors
    const real = jsErrors.filter((e) => !/Failed to load resource: the server responded with a status of 4\d\d/.test(e));
    check('no JavaScript errors', real.length === 0, jsErrors);
  } catch (e) {
    check('scenario completed', false, String(e && e.stack || e));
    await page.screenshot({ path: path.join(OUT, 'failure.png'), fullPage: true }).catch(() => {});
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
    const failed = results.filter((r) => !r.ok).length;
    console.log(failed ? failed + ' check(s) FAILED' : 'all ' + results.length + ' checks passed');
    process.exit(failed ? 1 : 0);
  }
})();
