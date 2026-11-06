/**
 * The Tip flow on the simulator (docs/ui-flows/tip-setup-flow.md, mockups docs/ui-flows/mockups/, the user's OK of
 * 11-02): (1) the frame of the agreed mockups: the menu Run, Designs, Tip, Machine, Records, "LumenPnP" top left, the
 * strip with Machine, Pressure, Tip, Session and no Start angle or Purge row; today's Setup, Calibrate and pad types
 * reachable under Machine › By hand; (2) Tip › On now: the mounted tip and every setting with how and when it was
 * measured and what makes it out of date, the height naming its source; (3) Which tip: the kinds with their pictures,
 * the kind on now first as a fresh tip, the path per kind; Park for the swap switches the pressure off; (4) Swap: It's
 * on mounts the chosen kind (a new needle token); the gauge reading is kept with that needle; (5) Checks: the steps in
 * the flow's order, each done, by hand or not built yet; Start the checks takes the picture from below and runs the
 * landing check on the SIM; (6) On now shows where it lands with its time; (7) a direct load of a Tip page whose
 * status comes before the tips builds its panel; no JavaScript errors, no native dialogs (Designs' upload and Make a
 * board: designs.playwright.cjs). Answers the SIM does not give on demand come from Playwright routes:
 * a task running or the pressure switching (Park and It's on wait, the hands-on steps hide), a picture stopped or
 * still running, a landing check that saved nothing or asks a question (nothing more moves; the step says why) and
 * one whose job the page lost track of ("Outcome unknown: look at the machine"),
 * no boards at all. Runs after tip-setups.playwright.cjs in the same session (the tips it set up). Nothing here runs
 * a real machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (tip-flow-results.json).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-tip-flow-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const status = (page) => page.evaluate(() => IL.api('GET', '/api/status'));
const IL_num = (v) => (typeof v === 'number' && isFinite(v) ? v : null);
const texts = (page, sel) => page.evaluate((s) => Array.from(document.querySelectorAll(s)).map((n) => n.textContent.trim()), sel);
/** Answers the page's GET /api/status with the session's own status changed by fn (a state the SIM does not hold). */
async function fakeStatus(page, fn) {
  await page.route('**/api/status', async (route) => {
    try {
      const res = await route.fetch();
      const body = await res.json();
      fn(body);
      await route.fulfill({ response: res, json: body });
    } catch (e) { /* unrouted while it was being answered */ }
  });
}
/** Starts the checks and waits until they have ended (Start the checks free again). */
async function runChecks(page, timeout) {
  await page.waitForSelector('#btn-tip-checks-start:enabled', { timeout: 10000 }).catch(() => null);
  await page.click('#btn-tip-checks-start');
  await page.waitForSelector('#btn-tip-checks-start:disabled', { timeout: 3000 }).catch(() => null);
  await page.waitForSelector('#btn-tip-checks-start:enabled', { timeout }).catch(() => null);
}
const stepOf = (page, k) => page.evaluate((key) => { const l = document.querySelector('#tip-checks li[data-k="' + key + '"]');
  return l ? { s: l.dataset.state, d: (l.querySelector('.ph__d') || {}).textContent || '' } : null; }, k);

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  try {
    await page.goto(BASE + '#run');
    await page.waitForFunction(() => window.IL && IL.api && document.querySelector('#nav .nav__item'), null, { timeout: 30000 });
    await sleep(1500);
    // the test's own tips: a second kind when the simulator knows only one (so it also runs alone, on a fresh session)
    const live = (r) => (r.setups || r || []).filter((x) => !x.retired && !x.retiredAtMs);
    if (live(await page.evaluate(() => IL.api('GET', '/api/tip-setups'))).length < 2) {
      await page.evaluate(() => IL.tips.openAdd({}));
      await page.waitForSelector('#dlg-tip-add #ta-preset', { timeout: 10000 });
      await page.selectOption('#ta-preset', '25g-straight');
      await page.click('#ta-save');
      await page.waitForFunction(() => IL.api('GET', '/api/tip-setups').then((r) => (r.setups || r).filter((x) => !x.retired && !x.retiredAtMs).length >= 2),
        null, { timeout: 15000, polling: 500 });
      await page.evaluate(() => { const d = IL.dialog.top(); if (d) d.close('test'); });
    }

    // ---- (1) the frame of the agreed mockups ---------------------------------------------------------------------------
    const nav = await page.evaluate(() => Array.from(document.querySelectorAll('#nav .nav__item')).map((a) => ({ tab: a.dataset.tab, t: a.querySelector('.nav__t').textContent.trim() })));
    check('(1) the menu reads Run, Designs, Tip, Machine, Records', JSON.stringify(nav.map((n) => n.t)) === JSON.stringify(['Run', 'Designs', 'Tip', 'Machine', 'Records'])
      && JSON.stringify(nav.map((n) => n.tab)) === JSON.stringify(['run', 'designs', 'tip', 'machine', 'records']), nav);
    const brand = await page.evaluate(() => document.querySelector('.brand__name').textContent.trim());
    check('(1) "LumenPnP" top left', brand === 'LumenPnP', brand);
    const keys = (await texts(page, '#cells .cell .cell__k')).map((k) => k.toLowerCase());
    check('(1) the strip: Machine, Pressure, Tip, Session; no Start angle, no Purge row',
      ['machine', 'tip', 'session'].every((k) => keys.includes(k)) && keys.some((k) => k.startsWith('pressure')) && !keys.some((k) => /start angle|purge row/.test(k)), keys);
    const sess = await page.evaluate(() => { const c = document.getElementById('cell-session'); return c ? c.querySelector('.cell__v').textContent.trim() : null; });
    check('(1) the Session cell says it is not tracked yet (BR-51)', /^Not tracked yet/.test(sess || ''), sess);
    await page.keyboard.press('Alt+3');
    await page.waitForFunction(() => location.hash.startsWith('#tip'), null, { timeout: 5000 }).catch(() => null);
    check('(1) Alt+3 opens Tip', (await page.evaluate(() => location.hash)).startsWith('#tip'), await page.evaluate(() => location.hash));
    await page.evaluate(() => IL.go('#machine'));
    await page.waitForSelector('#ws-machine a[href="#setup"]', { timeout: 5000 }).catch(() => null);
    const byHand = await page.evaluate(() => ['#setup', '#calibrate', '#profile'].map((h) => !!document.querySelector('#ws-machine a[href="' + h + '"]')));
    check('(1) Machine › By hand links today\'s Setup, Calibrate and pad types', byHand.every(Boolean), byHand);
    await page.click('#ws-machine a[href="#setup"]');
    await page.waitForFunction(() => location.hash.startsWith('#setup'), null, { timeout: 5000 }).catch(() => null);
    const sel = await page.evaluate(() => { const a = document.querySelector('#nav .nav__item[aria-selected="true"]'); return a && a.dataset.tab; });
    check('(1) Setup opens with Machine selected in the menu', sel === 'machine', sel);
    await page.evaluate(() => IL.go('#designs'));
    await page.waitForSelector('#designs-list .design', { timeout: 10000 }).catch(() => null);
    const dz = await page.evaluate(() => ({ n: document.querySelectorAll('#designs-list .design').length, up: !!document.getElementById('btn-design-upload') }));
    check('(1) Designs lists the boards and offers Upload a design', dz.n >= 1 && dz.up, dz);
    // a fresh installation has no boards: its first answer is an empty list (a page of its own, so it is the first)
    {
      const p2 = await browser.newPage({ viewport: { width: 1440, height: 900 } });
      p2.on('pageerror', (e) => jsErrors.push('no boards: ' + e));
      await p2.route('**/api/boards', (r) => r.fulfill({ json: { job: [], files: [] } }));
      await p2.goto(BASE + '#designs');
      await p2.waitForFunction(() => /No designs yet/.test((document.getElementById('designs-list') || {}).textContent || ''), null, { timeout: 10000 }).catch(() => null);
      const empty = await p2.evaluate(() => (document.getElementById('designs-list') || {}).textContent || null);
      check('(1) no boards yet: Designs says "No designs yet: upload one."', /^No designs yet: upload one\.$/.test((empty || '').trim()), empty);
      await p2.close();
    }

    // ---- (2) Tip › On now ---------------------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#tip'));
    await page.waitForSelector('#tipnow-name', { timeout: 10000 }).catch(() => null);
    const st0 = await status(page);
    const tip0 = st0.dispense.tip || {};
    const now2 = await page.evaluate(() => ({
      name: (document.getElementById('tipnow-name') || {}).textContent,
      photo: !!document.getElementById('tipnow-photo'),
      rows: Array.from(document.querySelectorAll('#tipnow-evidence tr[data-k]')).map((r) => ({ k: r.dataset.k, v: r.querySelector('.v').textContent.trim(),
        how: r.querySelector('.how').textContent.trim(), until: r.querySelector('.until').textContent.trim() })) }));
    check('(2) On now names the tip on the dispenser', !!tip0.name && (now2.name || '').trim() === tip0.name, { ui: now2.name, tip: tip0.name });
    check('(2) On now shows its picture (or the empty frame)', now2.photo, now2.photo);
    const ks = now2.rows.map((r) => r.k);
    check('(2) every setting: where it lands, height, pressure, dose, pad sizes; each with how and when, and what makes it out of date',
      ['lands', 'height', 'pressure', 'dose', 'padsizes'].every((k) => ks.includes(k)) && now2.rows.every((r) => r.v && r.how && r.until), now2.rows);
    const h = now2.rows.find((r) => r.k === 'height');
    check('(2) the height names its source (by hand, the side camera, or the bed switch once fitted)', !!h && /by hand|side camera|bed switch|both tips|from K|not measured/i.test(h.how), h);

    // ---- (3) Which tip ------------------------------------------------------------------------------------------------
    await page.click('#btn-tipnow-change');
    await page.waitForSelector('.kind[data-key]', { timeout: 10000 }).catch(() => null);
    const kinds = await page.evaluate(() => Array.from(document.querySelectorAll('.kind[data-key]')).map((k) => ({ key: k.dataset.key, tag: (k.querySelector('.tag') || {}).textContent || '',
      img: !!k.querySelector('img, svg'), path: (k.querySelector('.kind__path') || {}).textContent || '' })));
    check('(3) the kinds with pictures; the one on now first, as a fresh tip of its kind', kinds.length >= 2 && kinds[0].key === tip0.key && /on now/i.test(kinds[0].tag)
      && /fresh tip/i.test(kinds[0].path) && kinds.every((k) => k.img), kinds);
    const other = kinds.find((k) => k.key !== tip0.key);
    await page.click('.kind[data-key="' + other.key + '"]');
    await sleep(300);
    const to3 = await page.evaluate(() => (document.getElementById('tipwhich-to') || {}).textContent || '');
    const otherName = await page.evaluate((k) => document.querySelector('.kind[data-key="' + k + '"] .kind__t').textContent.trim(), other.key);
    check('(3) choosing a kind says what happens to it', to3.includes(otherName), { to3, otherName });
    // the head may move while a task runs or OpenPnP homes by itself: Park waits, and says why
    const parkLock = async (patch) => {
      await fakeStatus(page, patch);
      await page.waitForSelector('#btn-tip-park:disabled', { timeout: 5000 }).catch(() => null);
      const b = await page.evaluate(() => { const x = document.getElementById('btn-tip-park'); return x ? { disabled: x.disabled, title: x.title } : null; });
      await page.unroute('**/api/status');
      await page.waitForSelector('#btn-tip-park:enabled', { timeout: 5000 }).catch(() => null);
      return b;
    };
    const lockTask = await parkLock((s) => { s.dispense.busy = true; });
    const lockMove = await parkLock((s) => { s.machine.enabled = true; s.machine.busy = true; });
    check('(3) Park for the swap waits while a task runs or OpenPnP moves, and says why', !!lockTask && lockTask.disabled && /^Locked while/.test(lockTask.title)
      && !!lockMove && lockMove.disabled && /OpenPnP is busy/.test(lockMove.title), { lockTask, lockMove });
    // an agent holding the machine (BR-72) could move the head under the hands: Park waits for Take control; so it does
    // while a take-over still stops the agent, and while OpenPnP homes even with the machine reading off (#16 review)
    const agentLock = () => ({ holder: { kind: 'agent', who: 'process', label: 'Process', what: 'a 27G map', sinceMs: Date.now() - 60000, lastActiveMs: Date.now() - 2000, busy: false },
      takenFrom: null, handover: false });
    const lockAgent = await parkLock((s) => { s.lock = agentLock(); });
    const lockHand = await parkLock((s) => { s.lock = { holder: null, takenFrom: { label: 'Process', who: 'process', atMs: Date.now() }, handover: true }; });
    const lockHoming = await parkLock((s) => { s.machine.enabled = false; s.machine.homing = true; });
    check('(3) Park waits while an agent holds the machine ("Take control first") or a take-over still stops it', !!lockAgent && lockAgent.disabled
      && /^Process has the machine: Take control first/.test(lockAgent.title) && !!lockHand && lockHand.disabled && /still stopping Process.s work/.test(lockHand.title), { lockAgent, lockHand });
    check('(3) and while OpenPnP homes, even with the machine reading off', !!lockHoming && lockHoming.disabled && /OpenPnP is busy/.test(lockHoming.title), lockHoming);
    // the pressure switching on is not "on": Park still asks for it off, and goes on only when OpenPnP answers off
    let stopAsks = 0;
    await fakeStatus(page, (s) => { s.pressure.state = 'starting'; s.pressure.on = false; });
    await page.route('**/api/pressure/stop', (r) => { stopAsks++; return r.fulfill({ json: { state: 'vent_failed', on: false, lastError: 'the valve did not confirm' } }); });
    await page.waitForFunction(() => IL.S.status.pressure.state === 'starting', null, { timeout: 5000 }).catch(() => null);
    await page.click('#btn-tip-park');
    await page.waitForFunction(() => /NOT off/.test((document.getElementById('toast') || {}).textContent || ''), null, { timeout: 5000 }).catch(() => null);
    const vent3 = { asks: stopAsks, hash: await page.evaluate(() => location.hash), toast: await page.evaluate(() => (document.getElementById('toast') || {}).textContent || '') };
    check('(3) Park while the pressure switches on: it asks for it off, and stays when the answer is not off', vent3.asks === 1 && vent3.hash.startsWith('#tip/which')
      && /NOT off/.test(vent3.toast), vent3);
    await page.unroute('**/api/pressure/stop');
    await page.unroute('**/api/status');
    await page.waitForFunction(() => IL.S.status.pressure.state === 'off', null, { timeout: 5000 }).catch(() => null);
    await page.evaluate(() => { if (!location.hash.startsWith('#tip/which')) IL.go('#tip/which'); });   // the next steps start from Which tip
    await page.waitForSelector('#btn-tip-park:enabled', { timeout: 5000 }).catch(() => null);
    let stopPosts = 0;
    const order3 = [];
    await page.route('**/api/lock/take', (r) => { order3.push('take'); return r.continue(); });
    await page.route('**/api/pressure/stop', (r) => { stopPosts++; order3.push('stop'); return r.continue(); });
    await page.click('#btn-tip-park');
    await page.waitForFunction(() => location.hash.startsWith('#tip/swap'), null, { timeout: 10000 }).catch(() => null);
    await page.unroute('**/api/pressure/stop');
    await page.unroute('**/api/lock/take');
    const hasLock = await page.evaluate(() => !!IL.S.status.lock);
    check('(3) Park takes the machine first (no agent can take it during the swap), then switches the air off',
      !hasLock || (order3[0] === 'take' && order3.includes('stop') && (await status(page)).lock.holder.kind === 'user'), { order3, hasLock });
    const pr3 = (await status(page)).pressure || {};
    check('(3) Park for the swap: the pressure off, then Swap', (await page.evaluate(() => location.hash)).startsWith('#tip/swap') && (stopPosts > 0 || !pr3.on),
      { hash: await page.evaluate(() => location.hash), stopPosts, pressure: pr3.state || pr3.on });

    // ---- (4) Swap and the gauge ---------------------------------------------------------------------------------------
    const rail4 = await page.evaluate(() => Array.from(document.querySelectorAll('#tip-rail .step')).map((s) => s.className));
    check('(4) the rail: Which tip done, Swap now', rail4.length === 7 && /step--done/.test(rail4[0]) && /step--(now|need)/.test(rail4[1]), rail4);
    // a task starting meanwhile (from another tab, say): It's on waits, and the hands-on steps give way to why
    await fakeStatus(page, (s) => { s.dispense.busy = true; });
    await page.waitForSelector('#tip-swap-wait:not([hidden])', { timeout: 5000 }).catch(() => null);
    const busy4 = await page.evaluate(() => {
      const b = document.getElementById('btn-tip-on'), h = document.querySelector('#ws-tip [data-view="swap"] .howto'), w = document.getElementById('tip-swap-wait');
      return { on: b ? { disabled: b.disabled, title: b.title } : null, steps: !!h && !h.hidden, wait: w && !w.hidden ? w.textContent.trim() : null };
    });
    await page.unroute('**/api/status');
    await page.waitForSelector('#btn-tip-on:enabled', { timeout: 5000 }).catch(() => null);
    const back4 = await page.evaluate(() => { const h = document.querySelector('#ws-tip [data-view="swap"] .howto'); return !!h && !h.hidden; });
    check('(4) while a task runs, It\'s on waits and the hands-on steps give way to why; they come back after', !!busy4.on && busy4.on.disabled && !busy4.steps
      && /Locked while/.test(busy4.wait || '') && back4, { busy4, back4 });
    // an agent holding the machine meanwhile (BR-72): the same, the reason says Take control first
    await fakeStatus(page, (s) => { s.lock = agentLock(); });
    await page.waitForSelector('#tip-swap-wait:not([hidden])', { timeout: 5000 }).catch(() => null);
    const agent4 = await page.evaluate(() => {
      const b = document.getElementById('btn-tip-on'), h = document.querySelector('#ws-tip [data-view="swap"] .howto'), w = document.getElementById('tip-swap-wait');
      return { on: b ? { disabled: b.disabled, title: b.title } : null, steps: !!h && !h.hidden, wait: w && !w.hidden ? w.textContent.trim() : null };
    });
    await page.unroute('**/api/status');
    await page.waitForSelector('#btn-tip-on:enabled', { timeout: 5000 }).catch(() => null);
    check('(4) while an agent holds the machine, It\'s on waits and the hands-on steps give way to "Take control first"', !!agent4.on && agent4.on.disabled && !agent4.steps
      && /Process has the machine: Take control first/.test(agent4.wait || ''), agent4);
    let mountUrl = null;
    await page.route('**/api/tip-setups/*/mount', (r) => { mountUrl = r.request().url(); return r.continue(); });
    const mountResP = page.waitForResponse((r) => /\/api\/tip-setups\/[^/]+\/mount$/.test(r.url()), { timeout: 20000 }).catch(() => null);
    await page.click('#btn-tip-on');
    const mountRes = await mountResP;
    const mountBody = mountRes ? await mountRes.json().catch(() => null) : null;
    await page.waitForFunction((k) => IL.api('GET', '/api/status').then((s) => s.dispense.tip && s.dispense.tip.key === k), other.key, { timeout: 30000, polling: 500 }).catch(() => null);
    await page.unroute('**/api/tip-setups/*/mount');
    const st4 = await status(page);
    check('(4) It\'s on mounts the chosen kind, under a new needle token', !!mountUrl && mountUrl.includes('/api/tip-setups/' + encodeURIComponent(other.key) + '/mount')
      && st4.dispense.tip.key === other.key && st4.dispense.needle.changedAtMs !== st0.dispense.needle.changedAtMs,
      { mountUrl, status: mountRes && mountRes.status(), answer: mountBody && JSON.stringify(mountBody).slice(0, 600), tip: st4.dispense.tip.key });
    await page.waitForSelector('#tip-gauge:enabled', { timeout: 5000 }).catch(() => null);
    await page.fill('#tip-gauge', '18');
    await page.click('#btn-tip-gauge');
    await page.waitForFunction(() => location.hash.startsWith('#tip/checks'), null, { timeout: 5000 }).catch(() => null);
    const reg = await page.evaluate(() => IL.api('GET', '/api/status').then((s) => IL.attest.valid('regulator', s)));
    check('(4) the gauge reading is kept with the new needle, then Checks', !!reg && Number(reg.value) === 18 && reg.tok === st4.dispense.needle.changedAtMs
      && (await page.evaluate(() => location.hash)).startsWith('#tip/checks'), reg);

    // ---- (5) Checks ---------------------------------------------------------------------------------------------------
    const order = ['below', 'height', 'prime', 'drip', 'lands', 'exact', 'short', 'accept'];
    const steps5 = await page.evaluate(() => Array.from(document.querySelectorAll('#tip-checks li[data-k]')).map((l) => ({ k: l.dataset.k, s: l.dataset.state })));
    check('(5) the checks in the flow\'s order, each done, now, to do, by hand or not built yet', JSON.stringify(steps5.map((x) => x.k)) === JSON.stringify(order)
      && steps5.every((x) => ['done', 'now', 'todo', 'hand', 'notyet'].includes(x.s)), steps5);
    check('(5) the drip test is by hand until the machine has it; exact height, short dot check and accept are not built yet',
      (steps5.find((x) => x.k === 'drip') || {}).s === 'hand' && ['exact', 'short', 'accept'].every((k) => (steps5.find((x) => x.k === k) || {}).s === 'notyet'), steps5);
    const pics0 = (await page.evaluate((k) => IL.api('GET', '/api/tip-setups').then((r) => (r.setups || r).find((x) => x.key === k)), other.key)) || {};
    // the height is a by-hand step today (Machine › By hand › Setup › Needle Z frame): done here as the operator would,
    // both tips touching the blank, with the heights the session's frame had before the mount
    // both tips touching the blank at its place (the N1 contact Z the session stores for "blank"), then the frame's
    // offset confirmed: the two by-hand steps of Setup's needle card
    const locs = await page.evaluate(() => IL.api('GET', '/api/locations'));
    const bz = locs && locs.blank && IL_num(locs.blank.z);
    check('(5) the height is by hand first, with a link to the needle card', (steps5.find((x) => x.k === 'height') || {}).s === 'hand' && !!(await page.$('#tip-height-hand')), steps5);
    if (bz !== null) {
      const fr = await page.evaluate(([z, t]) => IL.api('POST', '/api/needle/anchor', { surfaceZ: z, needleContactZ: Number((z - 2.59).toFixed(3)), note: 'tip-flow test: both tips, by hand', needleChangedAtMs: t }),
        [bz, st4.dispense.needle.changedAtMs]);
      await page.evaluate(([off, t]) => IL.api('POST', '/api/needle/offset', { offsetMm: off, note: 'tip-flow test: the frame offset confirmed', needleChangedAtMs: t }), [fr.offsetMm, st4.dispense.needle.changedAtMs]);
    }
    await page.waitForFunction(() => { const l = document.querySelector('#tip-checks li[data-k="height"]'); return l && l.dataset.state === 'done'; }, null, { timeout: 10000 }).catch(() => null);
    check('(5) after the frame and its offset by hand, the height is done', (await page.evaluate(() => (document.querySelector('#tip-checks li[data-k="height"]') || {}).dataset.state)) === 'done', { bz });
    // the checks lay dots: the paste question of the session (same barrel or new) comes first when nobody answered it
    if (await page.$('#tip-barrel-same')) await page.click('#tip-barrel-same');
    // a picture that did not finish ends the checks there: nothing more moves, no landing check starts (a STOP answers
    // 200 with ok false; a picture still being taken, 202 with finished false; a refusal, 409)
    let landPosts = 0;
    await page.route('**/api/pad-calibrations', (r) => { if (r.request().method() !== 'POST') return r.continue(); landPosts++; return r.abort(); });
    const pic5 = [];
    for (const a of [{ status: 200, json: { kind: 'tip picture', setupKey: other.key, finished: true, result: { ok: false, message: 'Stopped: no picture was stored.' } } },
      { status: 202, json: { kind: 'tip picture', setupKey: other.key, finished: false, note: 'still running after 600 s; follow /api/status' } },
      { status: 409, json: { error: 'refused', reason: 'Home first: the head moves to the bottom camera.' } }]) {
      await page.route('**/api/tip-setups/*/picture', (r) => r.fulfill(a));
      await runChecks(page, 15000);
      await page.unroute('**/api/tip-setups/*/picture');
      pic5.push({ status: a.status, below: await stepOf(page, 'below') });
    }
    await page.unroute('**/api/pad-calibrations');
    check('(5) a picture stopped, still being taken or refused ends the checks: no landing check, the step says why', landPosts === 0
      && pic5.every((x) => !!x.below && x.below.s === 'todo' && x.below.d.length > 0) && /^Stopped: no picture/.test(pic5[0].below ? pic5[0].below.d : ''), { landPosts, pic5 });
    // a landing check that saved nothing is not done: no dot found (the step and the result say why), or a question (is
    // this tip on?) that the pad calibration's own view of the job answers
    const NF = 'No locator dot was found within ±6 mm of where the ' + otherName + ' should land: nothing was saved.';
    const Q = 'The ' + otherName + ' lands 1.5 mm from where it landed on 10-29: is the ' + otherName + ' on the dispenser?';
    const job = (id, outcome, landing) => ({ id, kind: 'pad calibration', measure: 'landing', state: 'done', steps: [], forTip: other.key, tipName: otherName,
      result: { outcome, sentence: landing.text, landing: Object.assign({ jobId: id, setupKey: other.key, saved: false }, landing) } });
    let jobNow = null;
    await page.route('**/api/tip-setups/*/picture', (r) => r.fulfill({ json: { kind: 'tip picture', setupKey: other.key, finished: true, result: { ok: true, message: 'Stored.' } } }));
    await page.route('**/api/pad-calibrations', (r) => (r.request().method() === 'POST' ? r.fulfill({ status: 202, json: { kind: 'pad calibration', id: jobNow.id, started: true } }) : r.continue()));
    await page.route('**/api/pad-calibrations/tf-*', (r) => r.fulfill({ json: jobNow }));
    jobNow = job('tf-not-found', 'not-found', { found: false, searchMm: 6, text: NF });
    await runChecks(page, 20000);
    const nf5 = { lands: await stepOf(page, 'lands'), result: await page.evaluate(() => { const r = document.getElementById('tip-checks-result');
      return r ? { need: r.classList.contains('notebox--need'), text: r.textContent.trim() } : null; }) };
    check('(5) a landing check that saved nothing is not done: the step and the result say why', !!nf5.lands && nf5.lands.s !== 'done' && nf5.lands.d === NF
      && !!nf5.result && nf5.result.need && nf5.result.text.includes(NF), nf5);
    jobNow = job('tf-question', 'question', { found: true, n: 8, dxMm: 1.2, dyMm: 0.9, question: Q, text: Q });
    await runChecks(page, 20000);
    const q5 = { lands: await stepOf(page, 'lands'), answer: await page.evaluate(() => (document.getElementById('tip-landing-answer') || {}).textContent || null) };
    if (q5.answer) await page.click('#tip-landing-answer');
    await page.waitForSelector('#btn-pcal-landing-save', { state: 'visible', timeout: 10000 }).catch(() => null);
    q5.view = await page.evaluate(() => ({ view: document.getElementById('panel').dataset.view, hash: location.hash, save: !!document.getElementById('btn-pcal-landing-save') }));
    check('(5) a landing check that asks whether the tip is on is not done; Answer it opens that job\'s view with Save it', !!q5.lands && q5.lands.s !== 'done'
      && !!q5.answer && q5.view.view === 'pad-cal' && /job=tf-question/.test(q5.view.hash) && q5.view.save, q5);
    await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); });
    // a landing check whose job the page lost track of (ten reads in a row with no answer) may still be laying dots: the
    // step says its outcome is unknown and to look at the machine (a read's own failure never does, #68's follow-up)
    jobNow = { id: 'tf-lost' };
    await page.unroute('**/api/pad-calibrations/tf-*');
    let lostReads = 0;
    await page.route('**/api/pad-calibrations/tf-*', (r) => { if (/\/tf-lost$/.test(new URL(r.request().url()).pathname)) lostReads++; return r.abort('failed'); });
    await runChecks(page, 30000);
    const lost5 = Object.assign({ reads: lostReads }, await stepOf(page, 'lands'));
    check('(5) a landing check it lost track of (ten reads with no answer) is not done: "… Outcome unknown: look at the machine."', lost5.reads === 10 && lost5.s !== 'done'
      && /^OpenPnP did not answer .*\. Outcome unknown: look at the machine\.$/.test(lost5.d || ''), lost5);
    for (const u of ['**/api/tip-setups/*/picture', '**/api/pad-calibrations', '**/api/pad-calibrations/tf-*']) await page.unroute(u);
    await page.waitForSelector('#btn-tip-checks-start:enabled', { timeout: 10000 }).catch(() => null);
    await page.click('#btn-tip-checks-start');
    await page.waitForFunction(() => { const l = document.querySelector('#tip-checks li[data-k="lands"]'); return l && /done|hand/.test(l.dataset.state); }, null, { timeout: 240000, polling: 1000 }).catch(() => null);
    const steps5b = await page.evaluate(() => Array.from(document.querySelectorAll('#tip-checks li[data-k]')).map((l) => ({ k: l.dataset.k, s: l.dataset.state, d: (l.querySelector('.ph__d') || {}).textContent || '' })));
    const pics1 = (await page.evaluate((k) => IL.api('GET', '/api/tip-setups').then((r) => (r.setups || r).find((x) => x.key === k)), other.key)) || {};
    check('(5) Start the checks: the picture from below is taken', (pics1.photos || []).length > (pics0.photos || []).length || (steps5b.find((x) => x.k === 'below') || {}).s === 'done',
      { before: (pics0.photos || []).length, after: (pics1.photos || []).length, steps5b });
    check('(5) and where it lands is checked on the SIM', (steps5b.find((x) => x.k === 'lands') || {}).s === 'done', steps5b);

    // ---- (6) On now after the checks ----------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#tip'));
    // On now reads the engine's needle state, which takes the saved landing on its next status (the Checks step shows
    // the job's own result at once; CI read this row before the status caught up, #24's gate 11-04)
    await page.waitForFunction(() => { const r = document.querySelector('#tipnow-evidence tr[data-k="lands"] .how'); return !!r && /\d\d:\d\d/.test(r.textContent); },
      null, { timeout: 15000 }).catch(() => null);
    const lands6 = await page.evaluate(() => { const r = document.querySelector('#tipnow-evidence tr[data-k="lands"]'); return r ? r.querySelector('.how').textContent.trim() : null; });
    check('(6) On now: where it lands, with the time it was checked', /\d\d:\d\d/.test(lands6 || ''), lands6);

    // ---- (7) a direct load of a Tip page: its status can come before the tips; the panel is built once they are read --
    {
      const p3 = await browser.newPage({ viewport: { width: 1440, height: 900 } });
      const errs = [];
      p3.on('pageerror', (e) => jsErrors.push('direct load: ' + e));
      p3.on('console', (m) => { if (m.type() === 'error' && /^\[IL\]/.test(m.text())) errs.push(m.text()); });
      await p3.route('**/api/tip-setups**', async (r) => { await sleep(2500); await r.continue().catch(() => {}); });
      await p3.goto(BASE + '#tip/swap');
      const built = await p3.waitForSelector('#btn-tip-on', { timeout: 15000 }).then(() => true, () => false);
      const parts7 = await p3.evaluate(() => ({ gauge: !!document.getElementById('tip-gauge'), title: (document.querySelector('#panel .panel__title h2') || {}).textContent || null }));
      check('(7) a direct load of Tip › Swap whose status comes first: the panel is built once the tips are read', built && parts7.gauge && parts7.title === 'Your turn'
        && errs.length === 0, { built, parts7, errs: errs.slice(0, 4) });
      await p3.close();
    }

    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'tip-flow-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'tip-flow-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
