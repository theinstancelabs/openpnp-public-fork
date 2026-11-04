/**
 * The web side of Backend's BR-02 (safe home after a controller reset, PR #11) and BR-03 (output zones, PR #12), as
 * docs/backend-requests.md §7 asks before those PRs merge. Until they land, the new status fields are stubbed with
 * route intercepts on the simulator, and the writes this page sends are caught and answered here (nothing moves):
 * (1) a reset left the position unknown: the Machine cell says so with the engine's why; Home asks first, in a dialog,
 * with §7's text (G28 drives X then Y at the tools' height, then Z; nothing under either tool or in the way; level a low
 * tool by hand; the see-saw level, N1 and the needle both up), Cancel sends nothing, Home sends `clearBelowAttested:
 * true`; no "Home raises N1 first" then; the controls that need the position wait for that home; a MACHINE refusal's
 * Home asks the same;
 * (2) the start angle after a reset: only "measure it again" (a bare confirm is refused then), in the angle view,
 * Calibrate's card and the menu; after a reconnect without a reset the question keeps both answers; (2b) the screw motor
 * switched off after the angle was set (a STOP's M18 B, the idle timeout: an unpowered screw can turn): measure again,
 * with the engine's why, also in the strip; switched off before it was set: the angle holds;
 * (3) Recovery after a STOP reads the reset instead of asking: no reset, no question; a reset, measure again; a STOP
 * that switched the screw motor off, measure again, saying so;
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
const stub = { reset: null, motorOff: null, angleSetAgoMs: null, i12: null, ready: true, homes: [], pressures: [], angles: [], stops: 0, pressureAnswer: null, linkLost: null };
// BR-93's words as the engine sends them (Backend 11-04 15:5x): `why` carries the time, `next` is shown as given
const LOST = { why: 'the link to the controller was lost at 16:21 (the controller left USB: /dev/serial/by-id/usb-STM32 is gone)',
  next: 'once the board is back on USB: Stop, then Start (or restart il-openpnp), then Home as after a reset' };
const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);
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
  // BR-02: the screw motor's last switch-off ({atMs, why}, both null when none); it dates the step count as a reset does
  const offAt = stub.motorOff ? now - stub.motorOff.agoMs : null;
  m.screwMotorOff = { atMs: offAt, why: stub.motorOff ? stub.motorOff.why : null };
  if (offAt !== null && !(p.controllerResetAtMs > offAt)) p.controllerResetAtMs = offAt;
  if (stub.angleSetAgoMs !== null) {
    p.partialTurn = true;
    p.startAngleDeg = 226;
    p.startAngleSetAtMs = now - stub.angleSetAgoMs;
    p.startAngleNeedsConfirm = !!((stub.reset || stub.motorOff) && p.startAngleSetAtMs < p.controllerResetAtMs) || !!stub.reconnected;
    // I11 follows the profile, as the engine keeps them
    s.interlocks = (s.interlocks || []).map((i) => (i.id === 'I11' ? Object.assign({}, i, { state: p.startAngleNeedsConfirm ? 'blocked' : 'ok' }) : i));
  }
  if (stub.i12) {
    s.interlocks = (s.interlocks || []).filter((i) => i.id !== 'I12').concat([Object.assign({ id: 'I12', text: stub.i12.state === 'ok' ? 'the needle is over the waste' : 'the needle is over the board' }, stub.i12)]);
  } else if (s.interlocks) s.interlocks = s.interlocks.filter((i) => i.id !== 'I12');
  // BR-93: the link to the controller lost; OpenPnP still reads enabled, except after a failed write or a Stop
  if (stub.linkLost) {
    Object.assign(m, { connected: false, homed: false, positionKnown: false, enabled: stub.linkLost.enabled !== false,
      linkLost: { atMs: now - 60000, why: LOST.why, next: LOST.next } });
  }
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
      && /nothing is under them or in their way to that corner/.test(dlg) && /levelled it by hand if one was low/.test(dlg), dlg);
    // Process (11-04): the see-saw levelled, both tips up, before this home; the button says what the person attests
    const goTxt = await page.evaluate(() => { const b = document.querySelector('#dlg-home-unknown [data-k="home"]'); return b ? b.textContent.trim() : null; });
    check('(1) it asks for the see-saw level, N1 and the needle both up, and its Home button says so', dlg && /The see-saw is level: N1 and the needle are both up/.test(dlg)
      && /^Level and clear: Home$/.test(goTxt || ''), { dlg, goTxt });
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

    // ---- (2b) the screw motor switched off after the angle was set: an unpowered screw can turn, so measure it again -------
    const OFF_WHY = 'the screw motor was switched off at 16:40 (a STOP: its DISABLE_COMMAND sends M18 B)';
    stub.motorOff = { agoMs: 600000, why: OFF_WHY };
    await page.evaluate(() => IL.openView('angle', { keep: true }));
    await sleep(1500);
    ang = await visibleFixes(page, '#panel');
    pt = await panelText(page);
    const st2b = await page.evaluate(() => IL.angleState(IL.S.status));
    check('(2b) the screw motor switched off after the angle was set: only "measure it again"', st2b === 'measure' && ang.some((b) => /measure/i.test(b.text)) && !ang.some((b) => b.fix === 'I11-confirm'), { st2b, ang });
    check('(2b) the angle view gives the engine\'s why: the screw may have turned', pt.includes('The screw motor was switched off at 16:40 (a STOP: its DISABLE_COMMAND sends M18 B)')
      && /may have turned/.test(pt) && !/step count started again|Was the controller switched off/.test(pt), pt.slice(0, 400));
    await page.evaluate(() => IL.go('#calibrate/angle'));
    await sleep(1200);
    const cal2b = await page.evaluate(() => { const w = document.getElementById('ws-calibrate'); return { text: w.textContent.replace(/\s+/g, ' '), confirm: !!w.querySelector('button[data-fix="I11-confirm"]:not([hidden])') }; });
    check('(2b) Calibrate\'s Start angle card: no bare confirm, the engine\'s why', !cal2b.confirm && cal2b.text.includes('a STOP: its DISABLE_COMMAND sends M18 B'), { confirm: cal2b.confirm });
    // the old angle is not offered while I11 asks for a new measurement (one click on it would clear I11 unmeasured,
    // #11 review): the field waits for the measured value, and its set says when it was typed (measuredAtMs)
    const f2b = await page.evaluate(() => ({ value: document.getElementById('pf-angle').value, why: document.getElementById('btn-pf-angle-set').title }));
    check('(2b) Calibrate offers no old angle to set: the field is empty, Set waits for the measured one', f2b.value === '' && /Enter the angle/.test(f2b.why), f2b);
    const n2b = stub.angles.length;
    const t2b = Date.now();
    await page.fill('#pf-angle', '231.5');
    await page.click('#btn-pf-angle-set');
    for (let i = 0; i < 30 && stub.angles.length === n2b; i++) await sleep(100);
    const sent2b = stub.angles[n2b] || null;
    check('(2b) Set sends the typed angle with measuredAtMs, the server time it was typed', !!sent2b && sent2b.startAngleDeg === 231.5
      && typeof sent2b.measuredAtMs === 'number' && Math.abs(sent2b.measuredAtMs - t2b) < 5000, sent2b);
    stub.motorOff = { agoMs: 5400000, why: 'the screw motor was switched off at 15:30 (the controller\'s idle timeout: no screw move for 60 min)' };
    await page.waitForFunction(() => /15:30/.test((IL.S.status.machine.screwMotorOff || {}).why || ''), null, { timeout: 5000 }).catch(() => null);
    const st2c = await page.evaluate(() => IL.angleState(IL.S.status));
    check('(2b) switched off before the angle was set: the angle holds', st2c === 'ok', st2c);
    // a value typed before a switch-off is no measurement after it: the field is emptied again
    stub.motorOff = { agoMs: 0, why: 'the screw motor was switched off at 16:45 (a STOP: its DISABLE_COMMAND sends M18 B)' };
    await page.waitForFunction(() => IL.angleState(IL.S.status) === 'measure', null, { timeout: 5000 }).catch(() => null);
    await sleep(800);
    const f2c = await page.evaluate(() => document.getElementById('pf-angle').value);
    check('(2b) a value typed before the switch-off is dropped: the field is empty again', f2c === '', f2c);
    stub.motorOff = null;

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
    // a STOP whose M18 B switched the screw motor off (BR-02): measure again, saying so, not "the controller was off"
    stub.reset = { agoMs: 7200000, detected: false, known: true };
    stub.motorOff = { agoMs: 5000, why: 'the screw motor was switched off at 16:59 (a STOP: its DISABLE_COMMAND sends M18 B)' };
    await sleep(1500);
    rec = await page.evaluate(() => document.querySelector('#panel').textContent.replace(/\s+/g, ' '));
    check('(3) a STOP that switched the screw motor off: Recovery says so and offers measuring the angle again', /The screw motor was switched off/.test(rec)
      && rec.includes('a STOP: its DISABLE_COMMAND sends M18 B') && /measure/i.test(rec) && !/No, it stayed on|The controller was switched off/.test(rec), rec.slice(0, 900));
    await page.screenshot({ path: path.join(OUT, 'rhp-recovery-motor-off.png') });
    stub.motorOff = null;
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

    // ---- (5) the link to the controller lost (BR-93): the strip and Machine › Control say so in the engine's words ----------
    await page.evaluate(() => { IL.panel.close(); IL.go('#machine'); });
    stub.linkLost = { enabled: true };
    await page.waitForFunction(() => !!(IL.S.status.machine || {}).linkLost, null, { timeout: 8000 }).catch(() => null);
    await sleep(1200);
    const lostNow = () => page.evaluate(() => { const c = document.getElementById('cell-machine'), h = document.getElementById('btn-home'), st = document.getElementById('btn-start');
      const w = document.getElementById('jog-why'), pad = [...document.querySelectorAll('#jog-pad button[data-axis]')];
      return { v: c.querySelector('.cell__v').textContent.replace(/\s+/g, ' ').trim(), title: c.title, cls: c.className, home: !!h && !h.hidden, start: !!st && !st.hidden,
        why: w && !w.hidden ? w.textContent : '', padLocked: pad.length > 0 && pad.every((b) => b.disabled) }; });
    const said = (t) => t.includes(cap(LOST.why)) && t.includes(cap(LOST.next));
    const l5 = await lostNow();
    check('(5) a lost link: the Machine cell says "Controller lost" with the engine\'s why and next step, and offers no Home', /Controller lost/.test(l5.v)
      && /cell--block/.test(l5.cls) && said(l5.title) && !l5.home && !l5.start, l5);
    check('(5) Machine › Control: the jog pad waits, in the same words (not "home first")', l5.padLocked && said(l5.why) && !/home first/i.test(l5.why), l5);
    // Stop (or a failed write) leaves OpenPnP stopped: the cell still says the link was lost, and offers Start (the next step)
    stub.linkLost = { enabled: false };
    await sleep(1500);
    const l5b = await lostNow();
    check('(5) stopped too: the cell still says the link was lost, and offers Start', /Controller lost/.test(l5b.v) && said(l5b.title) && l5b.start && !l5b.home, l5b);
    await page.screenshot({ path: path.join(OUT, 'rhp-link-lost.png') });
    stub.linkLost = null;

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
