/**
 * Who has the machine and Take control against the engine's own lock (BR-72, PR #19) on the simulator: the same
 * screens as lock.playwright.cjs (which stubs the API to reach the states a simulator cannot), here with an agent that
 * really holds the lock (POST /api/lock/acquire, its token in X-IL-Lock) and a real take-over:
 * (1) nobody has it; (2) an agent acquires and sends a line: the strip, the band and the locks name it; (3) Take control
 * from an idle agent: its outputs off, the machine still started and homed; (4) from a working agent (a 20 s dwell in
 * flight): stopped the way STOP does, the machine off, the agent's batch and its next call answer lock-lost; (5) Give
 * back: while the user holds it, a click on In control asks at once ("Give the machine back to the agents?", hands
 * clear; Keep it keeps it, Give back gives it back: one confirm, no panel; the user 11-05 20:1x); while nobody holds it
 * the click opens In control's panel as before; (6) an agent that takes the machine between two polls: the page's write answers 409 locked and the refusal
 * offers Take control; (7) while nobody has it, the user's first move makes it theirs; every request carries
 * X-IL-Client: ui; no JavaScript errors, no native dialogs. Nothing here runs a real machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (lock-real-results.json, lock-real-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-lock-real-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- the API from outside the page: an agent (its token), and the user's cleanup (X-IL-Client: ui) -------------------
async function api(method, p, body, headers) {
  const r = await fetch(BASE + p.replace(/^\//, ''), { method, headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
    body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = { text }; }
  return { status: r.status, data };
}
const UI = { 'X-IL-Client': 'ui' };
const WHAT = 'Feed step 20 -> 21.5 psi, drool check';
async function acquire(what) {
  const r = await api('POST', '/api/lock/acquire', { who: 'process', label: 'Process', what: what || WHAT, thread: 'mcp:lock-real-test', ttlS: 120 });
  if (r.status !== 200 || !r.data || !r.data.token) throw new Error('acquire: ' + r.status + ' ' + JSON.stringify(r.data));
  return r.data.token;
}
const gcode = (token, lines, timeoutMs) => api('POST', '/api/gcode', { lines, timeoutMs: timeoutMs || 60000 }, { 'X-IL-Lock': token });
/** Nobody holds the lock: the user gives it back, an agent's is taken and given back. */
async function nobody() {
  for (let i = 0; i < 3; i++) {
    const lk = (await api('GET', '/api/lock')).data || {};
    if (!lk.holder) return;
    if (lk.holder.kind !== 'user') await api('POST', '/api/lock/take', {}, UI);
    await api('POST', '/api/lock/release', {}, UI);
  }
}
/** The simulated machine started and homed, by the user, then given back. */
async function machineReady() {
  const st = (await api('GET', '/api/status')).data || {};
  const m = st.machine || {};
  if (!m.enabled) { await api('POST', '/api/machine/enable', {}, UI); await sleep(3000); }
  const m2 = ((await api('GET', '/api/status')).data || {}).machine || {};
  if (!m2.homed || m2.positionKnown === false) await api('POST', '/api/machine/home', { wait: true, clearBelowAttested: true }, UI);
  await nobody();
}

const cellText = (page) => page.evaluate(() => {
  const c = document.getElementById('cell-lock');
  if (!c) return null;
  return { shown: !c.hidden && c.offsetParent !== null, k: c.querySelector('.cell__k').textContent.trim(), v: c.querySelector('.cell__v').textContent.trim(), title: c.title, cls: c.className };
});
const takeBtn = (page) => page.evaluate(() => { const b = document.getElementById('btn-take'); return b ? { shown: !b.hidden && b.offsetParent !== null, disabled: b.disabled } : null; });
const waitCell = (page, re, ms) => page.waitForFunction((src) => { const c = document.getElementById('cell-lock'); return c && new RegExp(src).test(c.querySelector('.cell__v').textContent); }, re.source, { timeout: ms || 8000 }).catch(() => null);
/** The first machine control in the Machine panel that can be pressed now (not Take control), waiting up to 20 s. */
const waitFix = (page) => page.waitForFunction(() => { const b = Array.from(document.querySelectorAll('#pv-machine-act button[data-fix]')).find((x) => !x.hidden && !x.disabled && x.dataset.fix !== 'take-control'); return b ? b.dataset.fix : null; },
  null, { timeout: 20000 }).then((h) => h.jsonValue(), () => null);
const bandText = (page, key) => page.evaluate((k) => { const b = document.querySelector('#msg-area [data-msg="' + k + '"]'); return b ? b.textContent.replace(/\s+/g, ' ').trim() : null; }, key);

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  // every request the page sends; the status can be held back (6: an agent takes the machine between two polls)
  const noClient = [];
  const freeze = { on: false, last: null };
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const client = (await req.allHeaders())['x-il-client'];
    if (client !== 'ui' && !/\.(jpg|png)$/.test(url.pathname) && req.resourceType() !== 'image') noClient.push(req.method() + ' ' + url.pathname);
    if (url.pathname === '/api/status' && req.method() === 'GET') {
      if (freeze.on && freeze.last) return route.fulfill({ json: freeze.last });
      const resp = await route.fetch();
      const body = await resp.json();
      freeze.last = body;
      return route.fulfill({ response: resp, json: body });
    }
    return route.continue();
  });
  const page = await context.newPage();
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  try {
    const lk0 = await api('GET', '/api/lock');
    check('this OpenPnP has the lock (GET /api/lock)', lk0.status === 200 && lk0.data && 'holder' in lk0.data, lk0);
    await machineReady();
    await page.goto(BASE + '#run');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status, null, { timeout: 30000 });
    await sleep(1500);

    // ---- (1) nobody --------------------------------------------------------------------------------------------------------
    let c = await cellText(page);
    check('(1) nobody has the machine: the strip says so, no Take control', c && c.shown && /^Nobody/.test(c.v) && !(await takeBtn(page)).shown, c);

    // ---- (2) an agent acquires and sends a line ------------------------------------------------------------------------
    let token = await acquire();
    const m114 = await gcode(token, ['M114']);
    // (OpenPnP's simulated G-code server puts M114's position on its ok line: no reply lines before it)
    check('(2) the agent\'s line goes through the passthrough', m114.status === 200 && m114.data && m114.data.results && m114.data.results[0].line === 'M114', m114);
    await waitCell(page, /^Process · working/);
    c = await cellText(page);
    check('(2) the strip names the agent, working, and what it does', c && /^Process · working/.test(c.v) && c.title.includes(WHAT), c);
    const held = await bandText(page, 'held');
    check('(2) the band says it in a sentence, with Take control', /Process has the machine since \d\d:\d\d/.test(held || '') && (held || '').includes(WHAT), held);
    await page.evaluate(() => IL.openView('machine', { keep: true }));
    await sleep(900);
    const gated = await page.evaluate(() => Array.from(document.querySelectorAll('#pv-machine-act button[data-fix]')).filter((b) => !b.hidden).map((b) => ({ fix: b.dataset.fix, disabled: b.disabled, title: b.title })));
    check('(2) the machine\'s controls are locked, naming the agent', gated.length > 0 && gated.every((b) => b.disabled && /Process has the machine/.test(b.title)), gated);
    await page.screenshot({ path: path.join(OUT, 'lock-real-agent.png') });

    // ---- (3) Take control from an idle agent: its outputs off, the machine still started and homed -------------------------
    await page.waitForFunction(() => { const c = document.getElementById('cell-lock'); return c && /idle/.test(c.querySelector('.cell__v').textContent); }, null, { timeout: 15000 }).catch(() => null);
    c = await cellText(page);
    check('(3) after 10 s without a command the agent reads idle', c && /^Process · idle/.test(c.v), c);
    await page.click('#btn-take');
    await page.waitForSelector('#msg-area [data-msg="lock"]', { timeout: 30000 }).catch(() => null);
    let band = await bandText(page, 'lock');
    check('(3) the confirmation: the machine is the user\'s, Process was idle, its outputs off, still homed', /The machine is yours/.test(band || '') && /was idle/.test(band || '') && /still connected and homed/.test(band || ''), band);
    await waitCell(page, /^You/);
    let st = (await api('GET', '/api/status')).data || {};
    check('(3) the machine is still started and homed; the user holds the lock', st.machine && st.machine.enabled && st.machine.homed && st.lock && st.lock.holder && st.lock.holder.kind === 'user', { machine: st.machine && { enabled: st.machine.enabled, homed: st.machine.homed }, lock: st.lock });
    const lost = await gcode(token, ['M114']);
    check('(3) the agent\'s next call answers lock-lost', lost.status === 409 && lost.data && lost.data.error === 'lock-lost', lost);
    await page.click('#msg-area [data-msg="lock"] .msg__act button');

    // ---- (4) Take control from a working agent: stopped the way STOP does ------------------------------------------------
    await nobody();
    token = await acquire('a 20 s dwell (test)');
    const batch = gcode(token, ['G4 S20'], 30000);   // in flight while the user takes control
    await waitCell(page, /^Process · working/);
    await sleep(800);
    await page.click('#btn-take');
    await page.waitForSelector('#msg-area [data-msg="lock"]', { timeout: 60000 }).catch(() => null);
    band = await bandText(page, 'lock');
    check('(4) a working agent is stopped the way STOP does: then Connect and Home', /The machine is yours/.test(band || '') && /the way STOP does/.test(band || '') && /Connect, then Home/.test(band || '') && /a 20 s dwell/.test(band || ''), band);
    // the line in flight finishes (a dwell is not cut by M410); nothing after it is sent, and the agent's next call is lock-lost
    const b4 = await batch;
    const after4 = await gcode(token, ['M114']);
    check('(4) the agent\'s batch sends nothing more, and its next call answers lock-lost', (b4.status === 409 || (b4.status === 200 && b4.data.results.length === 1))
      && after4.status === 409 && after4.data && after4.data.error === 'lock-lost', { batch: b4.status, results: b4.data && b4.data.results, after: after4 });
    await sleep(1500);
    st = (await api('GET', '/api/status')).data || {};
    check('(4) the machine is off, the user holds it, takenFrom names the agent', st.machine && !st.machine.enabled && st.lock && st.lock.holder && st.lock.holder.kind === 'user'
      && st.lock.takenFrom && st.lock.takenFrom.label === 'Process', { enabled: st.machine && st.machine.enabled, lock: st.lock });
    await page.screenshot({ path: path.join(OUT, 'lock-real-taken.png') });
    await page.click('#msg-area [data-msg="lock"] .msg__act button');

    // ---- (5) Give back: a click on In control while the user holds it asks at once (the user 11-05 20:1x: "i should just be
    // able to click on The control header on the top and it should prompt me if i want to give it back") ------------------
    const giveDlg = () => page.evaluate(() => { const d = document.getElementById('dlg-give-back');
      return d ? { title: (d.querySelector('h2') || {}).textContent || '', text: d.textContent.replace(/\s+/g, ' ').trim(),
        btns: Array.from(d.querySelectorAll('.dialog__foot button')).map((b) => b.textContent.trim()) } : null; });
    const view = () => page.evaluate(() => (document.getElementById('panel') || {}).dataset.view || null);
    const view5 = await view();
    await page.click('#cell-lock');
    await page.waitForSelector('#dlg-give-back', { timeout: 5000 }).catch(() => null);
    const ask5 = await giveDlg();
    check('(5) a click on In control while the user holds it asks at once: "Give the machine back to the agents?", hands clear, Keep it / Give back, no panel',
      !!ask5 && ask5.title === 'Give the machine back to the agents?' && /hands clear/i.test(ask5.text)
        && JSON.stringify(ask5.btns) === JSON.stringify(['Keep it', 'Give back']) && (await view()) === view5, { ask5, view5, view: await view() });
    await page.click('#dlg-give-back .dialog__foot button:has-text("Keep it")');
    await page.waitForSelector('#dlg-give-back', { state: 'detached', timeout: 5000 }).catch(() => null);
    st = (await api('GET', '/api/lock')).data || {};
    const kept5 = { dlg: await giveDlg(), cell: (await cellText(page)).v };
    check('(5) Keep it: the prompt closes, the machine is still the user\'s', !kept5.dlg && !!st.holder && st.holder.kind === 'user' && /^You/.test(kept5.cell), { st, kept5 });
    await page.click('#cell-lock');
    await page.waitForSelector('#dlg-give-back', { timeout: 5000 }).catch(() => null);
    await page.click('#dlg-give-back .dialog__foot button:has-text("Give back")');
    await waitCell(page, /^Nobody/);
    st = (await api('GET', '/api/lock')).data || {};
    const after5 = { cell: (await cellText(page)).v, dlg: await giveDlg(), toast: await page.evaluate(() => document.getElementById('toast').title) };
    check('(5) Give back (one confirm): nobody holds it, the bar reads Nobody, "Given back" said, the prompt closed', !st.holder && /^Nobody/.test(after5.cell)
      && /^Given back: nobody has the machine/.test(after5.toast) && !after5.dlg, { st, after5 });
    // while nobody holds it, In control opens its panel as before (no prompt)
    await page.click('#cell-lock');
    await page.waitForFunction(() => (document.getElementById('panel') || {}).dataset.view === 'lock', null, { timeout: 5000 }).catch(() => null);
    const nobody5 = { view: await view(), dlg: await giveDlg() };
    check('(5b) while nobody holds it, a click on In control opens its panel, no prompt', nobody5.view === 'lock' && !nobody5.dlg, nobody5);
    await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); });

    // ---- (6) an agent takes it between two polls: the page's write is refused, Take control in the refusal ----------------
    await page.evaluate(() => IL.openView('machine', { keep: true }));
    await sleep(1200);
    freeze.on = true;                                  // the page keeps seeing "nobody"
    token = await acquire('took it between two polls (test)');
    // (4)'s STOP may still be switching the machine off on a slower runner: wait for a control to press
    const fix = await waitFix(page);
    if (fix) {
      const sel = '#pv-machine-act button[data-fix="' + fix + '"]';
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await page.waitForSelector('#pv-machine-act .refusal', { timeout: 10000 }).catch(() => null);
      const ref = await page.evaluate(() => { const r = document.querySelector('#pv-machine-act .refusal'); return r ? { text: r.textContent.replace(/\s+/g, ' ').trim(), take: !!r.querySelector('button[data-fix="take-control"]') } : null; });
      check('(6) the engine refuses the page\'s ' + fix + ' (409 locked), and the refusal offers Take control', ref && /Process has the machine/.test(ref.text) && ref.take, ref);
      freeze.on = false;
      if (ref && ref.take) {
        await page.click('#pv-machine-act .refusal button[data-fix="take-control"]');
        await waitCell(page, /^You/, 30000);
        check('(6) its Take control takes the machine', /^You/.test((await cellText(page)).v), await cellText(page));
      }
    } else check('(6) a machine control to press', false, fix);
    freeze.on = false;

    // ---- (7) nobody: the user's first move makes it theirs -----------------------------------------------------------------
    await nobody();
    await waitCell(page, /^Nobody/);
    await page.evaluate(() => { IL.msg('lock', null); IL.openView('machine', { keep: true }); });
    await sleep(1200);
    const fix7 = await waitFix(page);
    if (fix7) {
      const sel = '#pv-machine-act button[data-fix="' + fix7 + '"]';
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await waitCell(page, /^You/, 20000);
      st = (await api('GET', '/api/lock')).data || {};
      check('(7) the first move (' + fix7 + ') makes the user the holder', st.holder && st.holder.kind === 'user' && /^You/.test((await cellText(page)).v), st);
    } else check('(7) a machine control to press', false, fix7);
    await nobody();

    check('every API request this page sent carries X-IL-Client: ui', noClient.length === 0, Array.from(new Set(noClient)).slice(0, 12));
    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'lock-real-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    try { await nobody(); } catch (e) { /* the server may be gone */ }
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'lock-real-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
