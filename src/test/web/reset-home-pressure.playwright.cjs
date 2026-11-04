/**
 * The web side of Backend's BR-02 (safe home after a controller reset, PR #11) and BR-03 (output zones, PR #12), as
 * docs/backend-requests.md §7 asks before those PRs merge. Until they land, the new status fields are stubbed with
 * route intercepts on the simulator, and the writes this page sends are caught and answered here (nothing moves):
 * (1) a reset left the position unknown: the Machine cell says so with the engine's why; Home asks first, in a dialog,
 * with §7's text (G28 drives X then Y at the tools' height, then Z; nothing under either tool or in the way; level a low
 * tool by hand), Cancel sends nothing, Home sends `clearBelowAttested: true`; no "Home raises N1 first" then; the
 * controls that need the position wait for that home; a MACHINE refusal's Home asks the same;
 * (2) the start angle after a reset: only "measure it again" (a bare confirm is refused then), in the angle view,
 * Calibrate's card and the menu; after a reconnect without a reset the question keeps both answers;
 * (3) Recovery after a STOP reads the reset instead of asking: no reset, no question; a reset, measure again;
 * (4) Pressure on sends `park: 'waste'` unless I12 reads ok; "Pump only (read the gauge)" is offered only by a backend
 * with I12 (an older one would open the valve) and sends `mode: 'pump'`; the busy refusal is shown as given;
 * no JavaScript errors, no native dialogs. Nothing here runs a real machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (reset-home-pressure-results.json, rhp-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-rhp-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const WHY = 'The controller was reset at 16:20 (switched off, or its reset button): the head position is unknown';
// the stub: what the status says (applied to the simulator's own answer), and what this page sent
const stub = { reset: null, angleSetAgoMs: null, i12: null, ready: true, homes: [], pressures: [], angles: [], stops: 0, pressureAnswer: null };
function patch(s) {
  const now = s.server && s.server.timeMs ? s.server.timeMs : Date.now();
  const m = s.machine = s.machine || {};
  const d = s.dispense = s.dispense || {};
  const p = d.profile = d.profile || {};
  if (stub.ready) {
    m.enabled = true; m.homed = true; m.busy = false;
    d.running = true; d.available = true;
    s.pressure = Object.assign({}, s.pressure, { state: 'off' });
    if (s.run) s.run.running = false;
  }
  if (stub.reset) {
    // BR-02 fields: a reset `ago` ms ago; the position known again after the home
    const atMs = now - stub.reset.agoMs;
    m.positionKnown = !!stub.reset.known;
    m.positionUnknownWhy = stub.reset.known ? null : WHY;
    m.powerCycle = { detected: !!stub.reset.detected, atMs, reported: { x: 0, y: 0, z: 0, b: 720 } };
    m.home = Object.assign({}, m.home, { n1Low: false, lowTool: null, n1Z: null });
    p.controllerResetAtMs = atMs;
  }
  if (stub.angleSetAgoMs !== null) {
    p.partialTurn = true;
    p.startAngleDeg = 226;
    p.startAngleSetAtMs = now - stub.angleSetAgoMs;
    p.startAngleNeedsConfirm = !!(stub.reset && p.startAngleSetAtMs < p.controllerResetAtMs) || !!stub.reconnected;
    // I11 follows the profile, as the engine keeps them
    s.interlocks = (s.interlocks || []).map((i) => (i.id === 'I11' ? Object.assign({}, i, { state: p.startAngleNeedsConfirm ? 'blocked' : 'ok' }) : i));
  }
  if (stub.i12) {
    s.interlocks = (s.interlocks || []).filter((i) => i.id !== 'I12').concat([Object.assign({ id: 'I12', text: stub.i12.state === 'ok' ? 'the needle is over the waste' : 'the needle is over the board' }, stub.i12)]);
  } else if (s.interlocks) s.interlocks = s.interlocks.filter((i) => i.id !== 'I12');
  return s;
}
async function stubRoutes(context) {
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    let body = null;
    try { body = req.postDataJSON(); } catch (e) { body = null; }
    if (url.pathname === '/api/status' && method === 'GET') {
      const resp = await route.fetch();
      return route.fulfill({ response: resp, json: patch(await resp.json()) });
    }
    if (url.pathname === '/api/machine/home' && method === 'POST') {
      stub.homes.push(body || {});
      if (stub.reset && !stub.reset.known && !(body && body.clearBelowAttested)) {
        return route.fulfill({ status: 409, json: { error: 'refused', reason: 'MACHINE: ' + WHY + '. The home first drives the head to its homing corner (X, then Y) at the height the tools are at now, then homes Z there: check that nothing is under either tool (N1 or the needle) or in their way to that corner, and level N1 and the needle by hand if one is low; then home again saying so (clearBelowAttested).' } });
      }
      if (stub.reset) stub.reset.known = true;
      return route.fulfill({ json: { homed: true, n1RaisedFromZ: null } });
    }
    if (url.pathname === '/api/pressure/start' && method === 'POST') {
      stub.pressures.push(body || {});
      if (stub.pressureAnswer) return route.fulfill(stub.pressureAnswer);
      return route.fulfill({ json: { note: 'stub: pressure ' + ((body && body.mode) || 'hold') } });
    }
    if (url.pathname === '/api/screw/start-angle' && method === 'POST') { stub.angles.push(body || {}); return route.fulfill({ json: {} }); }
    if (url.pathname === '/api/stop' && method === 'POST') { stub.stops++; return route.fulfill({ json: { actions: ['M410', 'M107 P3', 'M107 P2', 'M107 P1', 'M107 P0', 'M18 B'] } }); }
    if (method !== 'GET') return route.fulfill({ status: 409, json: { error: 'refused', reason: 'test stub: ' + method + ' ' + url.pathname + ' is not sent from this test' } });
    return route.continue();
  });
}
const panelText = (page) => page.evaluate(() => document.querySelector('#panel').textContent.replace(/\s+/g, ' '));
const visibleFixes = (page, root) => page.evaluate((r) => Array.from(document.querySelectorAll(r + ' button[data-fix], ' + r + ' a, ' + r + ' button[data-href]'))
  .filter((b) => !b.hidden && b.offsetParent !== null).map((b) => ({ fix: b.dataset.fix || null, text: b.textContent.trim(), disabled: !!b.disabled })), root);

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await stubRoutes(context);
  const page = await context.newPage();
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  try {
    // ---- (1) a reset: the position is unknown until an attested home ------------------------------------------------------
    stub.reset = { agoMs: 60000, detected: true, known: false };
    stub.angleSetAgoMs = 3600000;
    await page.goto(BASE + '#run');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.S.status.machine && IL.S.status.machine.positionKnown === false, null, { timeout: 30000 });
    await sleep(1200);
    const mc = await page.evaluate(() => { const c = document.getElementById('cell-machine'); return { v: c.querySelector('.cell__v').textContent.trim(), title: c.title, cls: c.className }; });
    check('(1) the Machine cell says the position is unknown', /Position unknown/.test(mc.v) && /cell--block|cell--need/.test(mc.cls), mc);
    check('(1) with the engine\'s why in its title', mc.title.includes(WHY), mc);
    const hb = await page.evaluate(() => { const b = document.getElementById('btn-home'); return { shown: !b.hidden, text: b.textContent.trim() }; });
    check('(1) Home is offered in the strip', hb.shown, hb);
    await page.click('#btn-home');
    await page.waitForSelector('#dlg-home-unknown', { timeout: 5000 }).catch(() => null);
    const dlg = await page.evaluate(() => { const d = document.getElementById('dlg-home-unknown'); return d ? d.textContent.replace(/\s+/g, ' ') : null; });
    check('(1) Home asks first, in a dialog: no home sent yet', dlg && stub.homes.length === 0, { dlg, homes: stub.homes });
    check('(1) the dialog gives the engine\'s why', dlg && dlg.includes(WHY), dlg);
    check('(1) and §7\'s text: X then Y at the tools\' height, then Z; nothing under or in the way; level a low tool by hand',
      dlg && /homing corner \(X, then Y\) at the height the tools are at now, then homes Z there/.test(dlg)
      && /Nothing is under N1 or the needle or in their way to that corner/.test(dlg) && /levelled them by hand if one was low/.test(dlg), dlg);
    check('(1) no "Home raises N1 first" while the position is unknown', dlg && !/raises N1|lifts it first/.test(dlg), dlg);
    await page.click('#dlg-home-unknown [data-k="cancel"]');
    await sleep(300);
    check('(1) Cancel closes it and sends nothing', !(await page.$('#dlg-home-unknown')) && stub.homes.length === 0, stub.homes);
    // a control that needs the position waits for that home
    await page.evaluate(() => IL.openView('pressure', { keep: true }));
    await page.waitForSelector('#pv-pr-act', { state: 'attached', timeout: 5000 });
    await sleep(900);
    const onBtn = await page.evaluate(() => { const b = document.querySelector('#pv-pr-act button[data-fix="pressure-on"]'); return b ? { disabled: b.disabled, title: b.title } : null; });
    check('(1) Pressure on waits for the home, saying why', onBtn && onBtn.disabled && /position is unknown/i.test(onBtn.title) && /Home/.test(onBtn.title), onBtn);
    // a MACHINE refusal (a race: the reset came between two polls) offers Home, which asks the same
    const fx = await page.evaluate((why) => IL.fixesForCode('MACHINE', IL.S.status, why + ': home first (Home), after checking that nothing is under either tool'), WHY);
    check('(1) a MACHINE refusal offers Home', fx.some((f) => f.fix === 'home'), fx);
    await page.evaluate(() => IL.openView('machine', { keep: true }));
    await page.waitForSelector('#pv-machine-act button[data-fix="home"]', { state: 'attached', timeout: 5000 });
    await sleep(900);
    await page.click('#pv-machine-act button[data-fix="home"]');
    await page.waitForSelector('#dlg-home-unknown', { timeout: 5000 }).catch(() => null);
    check('(1) Machine\'s Home asks the same', !!(await page.$('#dlg-home-unknown')) && stub.homes.length === 0, stub.homes);
    await page.screenshot({ path: path.join(OUT, 'rhp-home-confirm.png') });
    await page.click('#dlg-home-unknown [data-k="home"]');
    await page.waitForFunction(() => !document.getElementById('dlg-home-unknown'), null, { timeout: 8000 }).catch(() => null);
    check('(1) Home in the dialog sends one home with clearBelowAttested: true', stub.homes.length === 1 && stub.homes[0].clearBelowAttested === true, stub.homes);
    await page.waitForFunction(() => IL.S.status.machine.positionKnown === true, null, { timeout: 5000 }).catch(() => null);
    await sleep(900);
    const mc2 = await page.evaluate(() => document.getElementById('cell-machine').querySelector('.cell__v').textContent.trim());
    check('(1) after it the Machine cell reads Homed again', /Homed/.test(mc2) && !/unknown/i.test(mc2), mc2);

    // ---- (2) the start angle after a reset: measure it again -----------------------------------------------------------
    await page.evaluate(() => IL.openView('angle', { keep: true }));
    await sleep(900);
    let ang = await visibleFixes(page, '#panel');
    let pt = await panelText(page);
    check('(2) after a reset the angle view offers only "measure it again"', ang.some((b) => /measure/i.test(b.text)) && !ang.some((b) => b.fix === 'I11-confirm'), { ang, pt: pt.slice(0, 400) });
    check('(2) and says the controller was switched off or reset', /switched off|reset/i.test(pt) && !/Was the controller switched off/.test(pt), pt.slice(0, 400));
    const nav = await page.evaluate(() => Array.from(document.querySelectorAll('#nav .nav__item')).map((a) => a.querySelector('.nav__s').textContent.trim()).join(' | '));
    check('(2) the menu says Measure angle, not Confirm angle', /Measure angle/.test(nav) && !/Confirm angle/.test(nav), nav);
    await page.evaluate(() => IL.go('#calibrate/angle'));
    await sleep(1200);
    const cal = await page.evaluate(() => { const w = document.getElementById('ws-calibrate'); return { text: w.textContent.replace(/\s+/g, ' '), confirm: !!w.querySelector('button[data-fix="I11-confirm"]:not([hidden])') }; });
    check('(2) Calibrate\'s Start angle card has no bare confirm after a reset', !cal.confirm && !/Was the controller switched off/.test(cal.text), { confirm: cal.confirm });
    // a reconnect without a reset (an unread connect): the question keeps both answers
    stub.reset = { agoMs: 7200000, detected: false, known: true };
    stub.reconnected = true;
    await page.evaluate(() => IL.openView('angle', { keep: true }));
    await sleep(1500);
    ang = await visibleFixes(page, '#panel');
    pt = await panelText(page);
    check('(2) a reconnect without a reset since the angle was set: both answers', ang.some((b) => b.fix === 'I11-confirm') && ang.some((b) => /measure/i.test(b.text)) && /Was the controller switched off/.test(pt), { ang, pt: pt.slice(0, 300) });
    stub.reconnected = false;

    // ---- (3) Recovery after a STOP reads the reset instead of asking -----------------------------------------------------
    stub.reset = { agoMs: 7200000, detected: false, known: true };
    stub.angleSetAgoMs = 3600000;
    await page.evaluate(() => IL.go('#machine'));
    await page.evaluate(() => IL.stop('test'));
    await page.waitForFunction(() => IL.recoveryActive && IL.recoveryActive(), null, { timeout: 8000 }).catch(() => null);
    await page.evaluate(() => IL.openView('recovery', { keep: true }));
    await sleep(1500);
    let rec = await page.evaluate(() => { const li = document.querySelector('#panel [data-step="angle"], #panel [data-k="angle"]'); return li ? li.textContent.replace(/\s+/g, ' ') : document.querySelector('#panel').textContent.replace(/\s+/g, ' ').slice(0, 1200); });
    const dbg3 = await page.evaluate(() => ({ st: IL.angleState(IL.S.status), pc: IL.S.status.machine.powerCycle, p: (({ partialTurn, startAngleSetAtMs, startAngleNeedsConfirm, controllerResetAtMs }) => ({ partialTurn, startAngleSetAtMs, startAngleNeedsConfirm, controllerResetAtMs }))(IL.S.status.dispense.profile) }));
    check('(3) no reset detected: Recovery does not ask whether the controller was switched off', !/Was the controller switched off\?/.test(rec) && !/No, it stayed on/.test(rec), { rec: rec.slice(0, 500), dbg3 });
    stub.reset = { agoMs: 30000, detected: true, known: true };
    await sleep(1500);
    rec = await page.evaluate(() => document.querySelector('#panel').textContent.replace(/\s+/g, ' '));
    check('(3) a reset detected: Recovery says so and offers measuring the angle again', /switched off|reset/i.test(rec) && /measure/i.test(rec) && !/No, it stayed on/.test(rec), rec.slice(0, 900));
    await page.screenshot({ path: path.join(OUT, 'rhp-recovery.png') });
    // back to a clean state: the angle measured after the reset
    stub.angleSetAgoMs = 1000;

    // ---- (4) Pressure on parks at the waste unless I12 reads ok; pump only when the backend has I12 ------------------------
    await page.evaluate(() => { IL.panel.close(); IL.openView('pressure', { keep: true }); });
    await page.waitForSelector('#pv-pr-act', { state: 'attached', timeout: 5000 });
    await sleep(1000);
    let pump = await page.evaluate(() => { const b = document.querySelector('#pv-pr-act button[data-fix="pressure-pump"]'); return b ? !b.hidden : false; });
    check('(4) without I12 (a backend before BR-03) no "Pump only" is offered', !pump, pump);
    const clickOn = async () => {
      const sel = '#pv-pr-act button[data-fix="pressure-on"]';
      await page.waitForSelector(sel + ':not([disabled])', { timeout: 5000 }).catch(() => null);
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await sleep(800);
    };
    let n0 = stub.pressures.length;
    await clickOn();
    check('(4) Pressure on sends park: waste (no I12 state ok)', stub.pressures.length === n0 + 1 && stub.pressures[n0].park === 'waste', stub.pressures.slice(n0));
    stub.i12 = { state: 'blocked', zone: 'board' };
    await sleep(1200);
    pump = await page.evaluate(() => { const b = document.querySelector('#pv-pr-act button[data-fix="pressure-pump"]'); return b ? { shown: !b.hidden, text: b.textContent.trim() } : null; });
    check('(4) with I12 the view offers "Pump only (read the gauge)"', pump && pump.shown && /Pump only \(read the gauge\)/.test(pump.text), pump);
    n0 = stub.pressures.length;
    await clickOn();
    check('(4) I12 not ok (over the board): park: waste', stub.pressures.length === n0 + 1 && stub.pressures[n0].park === 'waste', stub.pressures.slice(n0));
    stub.i12 = { state: 'ok', zone: 'waste' };
    await sleep(1200);
    n0 = stub.pressures.length;
    await clickOn();
    check('(4) I12 ok (over a sink): no park', stub.pressures.length === n0 + 1 && !('park' in stub.pressures[n0]), stub.pressures.slice(n0));
    n0 = stub.pressures.length;
    await page.click('#pv-pr-act button[data-fix="pressure-pump"]');
    await sleep(800);
    check('(4) Pump only sends mode: pump', stub.pressures.length === n0 + 1 && stub.pressures[n0].mode === 'pump', stub.pressures.slice(n0));
    // the engine's busy refusal, as given
    stub.pressureAnswer = { status: 409, json: { error: 'refused', reason: 'OpenPnP is busy (homing or moving): wait for it to finish' } };
    await clickOn();
    const ref = await page.evaluate(() => { const r = document.querySelector('#pv-pr-act .refusal'); return r ? r.textContent.replace(/\s+/g, ' ') : null; });
    check('(4) the busy refusal is shown as given', /OpenPnP is busy \(homing or moving\): wait for it to finish/.test(ref || ''), ref);
    stub.pressureAnswer = null;
    await page.screenshot({ path: path.join(OUT, 'rhp-pressure.png') });

    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'rhp-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'reset-home-pressure-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
