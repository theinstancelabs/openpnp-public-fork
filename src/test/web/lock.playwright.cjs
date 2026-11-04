/**
 * Who has the machine, and Take control (P0, the user 11-03 15:42: "i should be able to control the real machine at
 * any time using the UI (even if the agent is using the firmware) … there should be an indicator on the UI if an agent
 * currently holds a mutex for it / is working. i should be able to click that button and take it for myself").
 * The API is Backend's BR-72 (docs/backend-requests.md, "BR-72 API: the machine lock, Take control and the G-code
 * passthrough"). Until it lands, this test stubs it with route intercepts on the simulator: `lock` added to every
 * GET /api/status, POST /api/lock/take and /api/lock/release answered here, 409 `locked` for a write, and the UI's
 * first guarded write making the user the holder.
 * (0) every request the page sends says it comes from the UI (X-IL-Client: ui); (1) nobody has it: the strip says so,
 * no Take control; (2) an agent has it: the strip names it, what it does and since when, working or idle, offers Take
 * control, and the machine's controls are locked with that reason; (3) one click on Take control from a working agent:
 * one POST, a confirmation that the machine is the user's and what was stopped (the way STOP does: Connect, then
 * Home), the strip reads You; from an idle agent: its outputs off, the machine still homed; (4) a second window is the
 * user too; (5) Give back (two presses) frees it; (6) a write refused with 409 locked (an agent took it between two
 * polls) offers Take control; (7) while nobody has it, the first move makes it the user's; (8) a take whose stop is not
 * confirmed (409 stop-not-confirmed): the machine is the user's, the page says so and presses STOP itself; no
 * JavaScript errors, no native dialogs. Nothing here runs a real machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (lock-results.json, lock-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-lock-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- the stub of BR-72 ----------------------------------------------------------------------------------------------
const WHAT = 'Feed step 20 -> 21.5 psi, drool check';
const ACTIONS = ['quick stop (M410)', 'valve off', 'pump off', 'N1 valve off', 'N1 pump off', 'screw off (M18 B)'];
const ACTIONS_IDLE = ['valve off', 'pump off', 'N1 valve off', 'N1 pump off'];
const stub = { holder: null, takenFrom: null, refuseWrites: false, takingOver: false, takeFails: false, takes: 0, releases: 0, stops: 0, writes: [], noClient: [] };
// BR-72: never guarded (STOP, Pressure off, a pad calibration's cancel, the lock), and data-only writes (photos, board
// import, plan, pad types and their rules, package mappings, coupon readings, tip-setup definitions but not a mount)
const FREE = /^\/api\/(stop|pressure\/stop|lock\/.*|pad-calibrations\/[^/]+\/cancel)$/;
const DATA_ONLY = /^\/api\/(photos|board-import.*|boards\/import.*|plan|pad-types.*|package-.*|coupon.*|tip-setups(\/[^/]+)?(\/photo)?)$/;
function lockView() { return { holder: stub.holder, takenFrom: stub.takenFrom }; }
function agent(minutesAgo, activeSecondsAgo) {
  const now = Date.now();
  return { kind: 'agent', who: 'process', label: 'Process', what: WHAT, thread: 'mcp:c07b84cf', sinceMs: now - minutesAgo * 60000,
    lastActiveMs: now - activeSecondsAgo * 1000, busy: false, expiresAtMs: now + 300000 };
}
function user(now) { return { kind: 'user', who: 'user', label: 'You', what: null, thread: null, sinceMs: now, lastActiveMs: now, busy: false, expiresAtMs: null }; }
async function stubRoutes(context) {
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const client = (await req.allHeaders())['x-il-client'];
    // the browser's own image loads (<img src>) cannot carry a header; everything this page sends through fetch does
    if (client !== 'ui' && !/\.(jpg|png)$/.test(url.pathname) && req.resourceType() !== 'image') stub.noClient.push(method + ' ' + url.pathname);
    if (url.pathname === '/api/status' && method === 'GET') {
      const resp = await route.fetch();
      const body = await resp.json();
      body.lock = lockView();
      return route.fulfill({ response: resp, json: body });
    }
    if (url.pathname === '/api/lock/take' && method === 'POST') {
      stub.takes++;
      const was = stub.holder;
      const now = Date.now();
      stub.holder = user(now);
      stub.takenFrom = was && was.kind === 'agent' ? { label: was.label, who: was.who, what: was.what, atMs: now, by: 'user' } : null;
      stub.refuseWrites = false;
      const working = was && was.kind === 'agent' && (was.busy || now - was.lastActiveMs < 10000);
      const stopped = !was || was.kind !== 'agent' ? null : working
        ? { label: was.label, what: was.what, mode: 'stop', actions: ACTIONS, machineOff: true }
        : { label: was.label, what: was.what, mode: 'outputs-off', actions: ACTIONS_IDLE, machineOff: false };
      const body = { lock: lockView(), stopped, atMs: now };
      if (stub.takeFails) { stub.takeFails = false; return route.fulfill({ status: 409, json: Object.assign({ error: 'stop-not-confirmed', reason: 'The STOP was not confirmed: the machine still reads started after 20 s.' }, body) }); }
      return route.fulfill({ json: body });
    }
    if (url.pathname === '/api/stop' && method === 'POST') stub.stops++;
    if (url.pathname === '/api/lock/release' && method === 'POST') {
      stub.releases++;
      stub.holder = null;
      return route.fulfill({ json: { lock: lockView() } });
    }
    if (method !== 'GET' && !FREE.test(url.pathname) && !DATA_ONLY.test(url.pathname)) {
      stub.writes.push(method + ' ' + url.pathname);
      const agentHas = stub.holder && stub.holder.kind === 'agent';
      if (stub.takingOver) return route.fulfill({ status: 409, json: { error: 'taking-over', reason: 'Take control is still stopping Process\'s work: a moment', lock: lockView() } });
      if (agentHas || stub.refuseWrites) {
        const h = stub.holder || agent(1, 1);
        return route.fulfill({ status: 409, json: { error: 'locked', reason: 'Process has the machine (feed step check, since ' + new Date(h.sinceMs).toTimeString().slice(0, 5) + '). Take control to use it.', lock: { holder: h, takenFrom: null } } });
      }
      // nobody: the UI's first guarded write makes the user the holder (no stop: nobody was working)
      if (!stub.holder && client === 'ui') stub.holder = user(Date.now());
    }
    return route.continue();
  });
}

const cellText = (page) => page.evaluate(() => {
  const c = document.getElementById('cell-lock');
  if (!c) return null;
  const v = c.querySelector('.cell__v');
  return { shown: !c.hidden && c.offsetParent !== null, k: c.querySelector('.cell__k').textContent.trim(), v: v.textContent.trim(), title: c.title, cls: c.className };
});
const takeBtn = (page) => page.evaluate(() => { const b = document.getElementById('btn-take'); return b ? { shown: !b.hidden && b.offsetParent !== null, disabled: b.disabled, text: b.textContent.trim() } : null; });
/** The first machine control in the Machine panel that can be pressed now (not Take control), waiting up to 20 s. */
const waitFix = (page) => page.waitForFunction(() => { const b = Array.from(document.querySelectorAll('#pv-machine-act button[data-fix]')).find((x) => !x.hidden && !x.disabled && x.dataset.fix !== 'take-control'); return b ? b.dataset.fix : null; },
  null, { timeout: 20000 }).then((h) => h.jsonValue(), () => null);
const waitCell = (page, re, ms) => page.waitForFunction((src) => { const c = document.getElementById('cell-lock'); return c && new RegExp(src).test(c.querySelector('.cell__v').textContent); }, re.source, { timeout: ms || 8000 }).catch(() => null);

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
    await page.goto(BASE + '#run');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status, null, { timeout: 30000 });
    await sleep(1500);

    // ---- (1) nobody has it ----------------------------------------------------------------------------------------------
    let c = await cellText(page);
    check('(1) the strip has an In control cell', c && c.shown && /in control/i.test(c.k), c);
    check('(1) nobody has the machine: the strip says so', c && /^Nobody/.test(c.v), c);
    check('(1) no Take control while nobody has it', !(await takeBtn(page)).shown, await takeBtn(page));

    // ---- (2) an agent has it ------------------------------------------------------------------------------------------
    stub.holder = agent(12, 2);
    await waitCell(page, /^Process/);
    c = await cellText(page);
    const since = await page.evaluate((ms) => IL.fmtTime(ms - IL.S.skewMs), stub.holder.sinceMs);
    check('(2) the strip names the agent', c && /^Process/.test(c.v), c);
    check('(2) and says what it is doing', c && (c.v.includes('Feed step') || c.title.includes(WHAT)) && c.title.includes(WHAT), c);
    check('(2) and since when', c && (c.k + ' ' + c.v + ' ' + c.title).includes(since), { c, since });
    check('(2) and that it is working (its last command 2 s ago)', c && /cell--live/.test(c.cls) && /working/i.test(c.title), c);
    const tb = await takeBtn(page);
    check('(2) Take control is offered, one click', tb && tb.shown && !tb.disabled && /^Take control$/.test(tb.text), tb);
    const held = await page.evaluate(() => { const b = document.querySelector('#msg-area [data-msg="held"]'); return b ? { text: b.textContent.replace(/\s+/g, ' ').trim(), take: !!b.querySelector('button[data-fix="take-control"]') } : null; });
    check('(2) a band says it in a sentence: who, since when, what, working; with Take control', held && held.text.includes('Process has the machine since ' + since)
      && held.text.includes(WHAT) && /working now/.test(held.text) && held.take, held);
    await page.click('#cell-machine');
    await page.waitForSelector('#pv-machine-act button[data-fix]', { state: 'attached', timeout: 5000 });
    await sleep(900);
    const gated = await page.evaluate(() => Array.from(document.querySelectorAll('#pv-machine-act button[data-fix]')).filter((b) => !b.hidden).map((b) => ({ fix: b.dataset.fix, disabled: b.disabled, title: b.title })));
    check('(2) the machine\'s controls are locked, saying the agent has it', gated.length > 0 && gated.every((b) => b.disabled && /Process has the machine/.test(b.title) && /Take control/.test(b.title)), gated);
    const why = await page.evaluate(() => { const w = document.querySelector('#pv-machine-act .fix-why'); return w && !w.hidden ? w.textContent.trim() : null; });
    check('(2) the reason is shown under them, not only in a tooltip', /Process has the machine/.test(why || ''), why);
    // data-only work (BR-72: board import, pad types, the plan, tip definitions) stays the user's while an agent works
    await page.evaluate(() => IL.go('#run/board'));
    await page.waitForSelector('#btn-board-upload', { state: 'attached', timeout: 5000 }).catch(() => null);
    const up = await page.evaluate(() => { const b = document.getElementById('btn-board-upload'); return b ? { disabled: b.disabled, title: b.title } : null; });
    check('(2) data-only work stays free: Upload KiCad board is not locked', up && !up.disabled && !/has the machine/.test(up.title), up);
    await page.evaluate(() => IL.go('#run'));
    // idle: no command for 5 minutes
    stub.holder = agent(12, 300);
    await page.waitForFunction(() => { const c = document.getElementById('cell-lock'); return c && !/cell--live/.test(c.className); }, null, { timeout: 5000 }).catch(() => null);
    c = await cellText(page);
    check('(2) an agent that sent nothing for 5 min reads idle, not working', c && !/cell--live/.test(c.cls) && /idle/i.test(c.title), c);
    stub.holder = agent(12, 2);
    await waitCell(page, /^Process · working/);
    await sleep(400);   // the cell's colour fades in 120 ms
    await page.screenshot({ path: path.join(OUT, 'lock-agent.png') });

    // ---- (3) one click on Take control --------------------------------------------------------------------------------
    const takes0 = stub.takes;
    await page.click('#btn-take');
    await page.waitForSelector('#msg-area [data-msg="lock"]', { timeout: 8000 }).catch(() => null);
    const band = await page.evaluate(() => { const b = document.querySelector('#msg-area [data-msg="lock"]'); return b ? { text: b.textContent.replace(/\s+/g, ' ').trim(), cls: b.className } : null; });
    check('(3) one click sends one take-over', stub.takes === takes0 + 1, { takes: stub.takes - takes0 });
    check('(3) the confirmation says the machine is the user\'s', band && /The machine is yours/.test(band.text), band);
    check('(3) and names what was stopped, with its actions', band && band.text.includes('Process') && band.text.includes(WHAT) && ACTIONS.every((a) => band.text.includes(a)), band);
    check('(3) a working agent is stopped the way STOP does: then Connect and Home', band && /the way STOP does/.test(band.text) && /Connect, then Home/.test(band.text), band);
    await waitCell(page, /^You/);
    c = await cellText(page);
    check('(3) the strip reads You', c && /^You/.test(c.v) && /cell--ok/.test(c.cls), c);
    check('(3) Take control is gone, and the band with it', !(await takeBtn(page)).shown && !(await page.$('#msg-area [data-msg="held"]')), await takeBtn(page));
    await sleep(900);
    const ungated = await page.evaluate(() => Array.from(document.querySelectorAll('#pv-machine-act button[data-fix]')).map((b) => b.title));
    check('(3) the machine\'s controls no longer name the agent', ungated.every((t) => !/has the machine/.test(t)), ungated);
    await page.screenshot({ path: path.join(OUT, 'lock-taken.png') });
    await page.click('#msg-area [data-msg="lock"] .msg__act button');
    check('(3) the confirmation closes with its button', !(await page.$('#msg-area [data-msg="lock"]')), null);

    // ---- (3b) Take control from an idle agent: its outputs off, the machine stays started and homed -------------------------
    stub.holder = agent(40, 600);
    stub.takenFrom = null;
    await waitCell(page, /^Process/);
    await page.waitForSelector('#msg-area [data-msg="held"] button[data-fix="take-control"]', { timeout: 5000 });
    const held3b = await page.evaluate(() => document.querySelector('#msg-area [data-msg="held"]').textContent);
    check('(3b) the band says an idle agent is idle', /is idle/.test(held3b) && !/working now/.test(held3b), held3b);
    await page.click('#msg-area [data-msg="held"] button[data-fix="take-control"]');   // the band's own button this time
    await page.waitForSelector('#msg-area [data-msg="lock"]', { timeout: 8000 }).catch(() => null);
    const band3b = await page.evaluate(() => { const b = document.querySelector('#msg-area [data-msg="lock"]'); return b ? b.textContent.replace(/\s+/g, ' ').trim() : null; });
    check('(3b) from an idle agent: its outputs off, the machine still connected and homed', /was idle/.test(band3b || '') && ACTIONS_IDLE.every((a) => band3b.includes(a))
      && /still connected and homed/.test(band3b) && !/Connect, then Home/.test(band3b), band3b);
    await page.click('#msg-area [data-msg="lock"] .msg__act button');

    // ---- (4) a second window is the user too ----------------------------------------------------------------------------
    const page2 = await context.newPage();
    page2.on('pageerror', (e) => jsErrors.push('page2: ' + String(e)));
    await page2.goto(BASE + '#tip');
    await page2.waitForFunction(() => window.IL && IL.S && IL.S.status, null, { timeout: 30000 });
    await waitCell(page2, /^You/);
    const c2 = await cellText(page2);
    check('(4) a second window also reads You, without Take control', c2 && /^You/.test(c2.v) && !(await takeBtn(page2)).shown, c2);
    await page2.close();

    // ---- (5) Give back ----------------------------------------------------------------------------------------------------
    await page.click('#cell-lock');
    await page.waitForSelector('#pv-lock-act button', { state: 'attached', timeout: 5000 });
    const panel = await page.evaluate(() => document.querySelector('#panel').textContent.replace(/\s+/g, ' '));
    await page.screenshot({ path: path.join(OUT, 'lock-panel.png') });
    check('(5) the In control panel says the user took it from Process', /You took it from Process/.test(panel) && panel.includes(WHAT), panel.slice(0, 600));
    const give = await page.$('#pv-lock-act button[data-k="release"]');
    check('(5) the panel offers Give back', !!give, null);
    if (give) {
      await give.click();
      await sleep(400);
      check('(5) Give back asks for a second press (agents may move it right after)', stub.releases === 0 && /Press again/.test(await give.textContent()), await give.textContent());
      await give.click();
      await waitCell(page, /^Nobody/);
      check('(5) the second press gives it back: one release, the strip reads Nobody', stub.releases === 1 && /^Nobody/.test((await cellText(page)).v), { releases: stub.releases, c: await cellText(page) });
    }

    // ---- (6) a write refused because an agent took it between two polls ---------------------------------------------------
    stub.refuseWrites = true;
    await page.click('#cell-machine');
    await page.waitForSelector('#pv-machine-act button[data-fix]', { state: 'attached', timeout: 5000 });
    await sleep(900);
    const btn = await waitFix(page);
    check('(6) a machine control is free while nobody has it', !!btn, btn);
    if (btn) {
      const sel = '#pv-machine-act button[data-fix="' + btn + '"]';
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await page.waitForSelector('#pv-machine-act .refusal', { timeout: 8000 }).catch(() => null);
      const ref = await page.evaluate(() => { const r = document.querySelector('#pv-machine-act .refusal'); return r ? { text: r.textContent.replace(/\s+/g, ' ').trim(), take: !!r.querySelector('button[data-fix="take-control"]') } : null; });
      check('(6) the refusal says an agent has the machine and offers Take control', ref && /Process has the machine/.test(ref.text) && ref.take, ref);
      if (ref && ref.take) {
        const t1 = stub.takes;
        await page.click('#pv-machine-act .refusal button[data-fix="take-control"]');
        await waitCell(page, /^You/);
        check('(6) its Take control takes the machine', stub.takes === t1 + 1 && /^You/.test((await cellText(page)).v), { takes: stub.takes - t1, c: await cellText(page) });
      }
    }
    stub.refuseWrites = false;

    // ---- (7) while nobody has it, the user's first move makes it theirs -----------------------------------------------------
    await page.evaluate(() => IL.giveBack());
    await waitCell(page, /^Nobody/);
    await page.evaluate(() => IL.openView('machine', { keep: true }));   // a second click on its cell would close it
    await page.waitForSelector('#pv-machine-act button[data-fix]', { state: 'attached', timeout: 5000 });
    await sleep(900);
    const btn7 = await waitFix(page);
    if (btn7) {
      const sel = '#pv-machine-act button[data-fix="' + btn7 + '"]';
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await waitCell(page, /^You/, 15000);
      const toast7 = await page.evaluate(() => IL.S.activity.slice(0, 12).map((a) => a.text).find((t) => /The machine is yours now/.test(t)) || null);
      check('(7) nobody had it: the first move (' + btn7 + ') makes it the user\'s, and the page says why', /^You/.test((await cellText(page)).v) && !!toast7, { c: await cellText(page), toast7 });
    } else check('(7) a machine control to press', false, btn7);

    // ---- (8) a take whose stop is not confirmed: the machine is the user's, and STOP goes out again --------------------------
    await page.evaluate(() => IL.giveBack());
    stub.holder = agent(5, 1);
    await waitCell(page, /^Process/);
    const stops0 = stub.stops;
    stub.takeFails = true;
    await page.click('#btn-take');
    await page.waitForSelector('#msg-area [data-msg="lock"].msg--block', { timeout: 8000 }).catch(() => null);
    const band8 = await page.evaluate(() => { const b = document.querySelector('#msg-area [data-msg="lock"]'); return b ? { text: b.textContent.replace(/\s+/g, ' ').trim(), cls: b.className } : null; });
    await sleep(1500);
    check('(8) stop not confirmed: the band says the machine is the user\'s and the stop was NOT confirmed', band8 && /msg--block/.test(band8.cls) && /The machine is yours/.test(band8.text) && /NOT confirmed/.test(band8.text), band8);
    check('(8) and the page sends STOP itself', stub.stops > stops0, { stops: stub.stops - stops0 });
    await waitCell(page, /^You/);
    check('(8) the strip reads You', /^You/.test((await cellText(page)).v), await cellText(page));
    await page.screenshot({ path: path.join(OUT, 'lock-unconfirmed.png') });

    // ---- (8b) the strip after that STOP, its widest state: the verdict never cut, its time kept at 1440 px (In control
    // gives its room first while it reads You or Nobody); an agent's cell never leaves the strip ---------------------------
    await page.evaluate(() => IL.msg('lock', null));
    await page.waitForFunction(() => /^Stopped \d\d:\d\d/.test(document.querySelector('#pill-engine .cell__v').textContent), null, { timeout: 10000 }).catch(() => null);
    const fit = async (w, h) => {
      await page.setViewportSize({ width: w, height: h });
      await sleep(500);
      return page.evaluate((ww) => {
        const v = document.querySelector('#pill-engine .cell__v'), cells = document.getElementById('cells'), lc = document.getElementById('cell-lock');
        const r = v.getBoundingClientRect(), lim = cells.getBoundingClientRect().right;
        return { w: ww, shown: v.innerText, cut: v.scrollWidth > v.clientWidth + 0.5 || r.right > lim + 0.5, tier: cells.className, lock: lc.offsetParent !== null };
      }, w);
    };
    const cuts = [];
    for (const [w, h] of [[1440, 900], [1280, 800], [1024, 768]]) cuts.push(await fit(w, h));
    check('(8b) after a STOP, with You in control: the verdict reads "Stopped…" in full at 1440, 1280 and 1024 px', cuts.every((c) => !c.cut && /^Stopped/.test(c.shown)), cuts);
    check('(8b) and keeps the time of the stop at 1440 px', /^Stopped \d\d:\d\d/.test(cuts[0].shown), cuts[0]);
    stub.holder = agent(3, 1);
    await waitCell(page, /^Process/);
    const a1024 = await fit(1024, 768);
    check('(8b) an agent holding the machine stays in the strip at 1024 px', a1024.lock, a1024);
    await page.setViewportSize({ width: 1440, height: 900 });
    stub.holder = user(Date.now());
    await waitCell(page, /^You/);

    // ---- (9) a write while a take still stops the agent (409 taking-over): the reason, no second Take control -------------------
    await page.evaluate(() => { IL.msg('lock', null); IL.openView('machine', { keep: true }); });
    stub.holder = user(Date.now());
    await waitCell(page, /^You/);
    await sleep(900);
    stub.takingOver = true;
    // (8)'s STOP may still be switching the machine off on a slower runner: wait for a control to press
    const fix9 = await waitFix(page);
    if (fix9) {
      const sel = '#pv-machine-act button[data-fix="' + fix9 + '"]';
      await page.click(sel);
      if (await page.evaluate((s) => /Press again/.test(document.querySelector(s).textContent), sel)) { await sleep(450); await page.click(sel); }
      await page.waitForSelector('#pv-machine-act .refusal', { timeout: 8000 }).catch(() => null);
      const r9 = await page.evaluate(() => { const r = Array.from(document.querySelectorAll('#pv-machine-act .refusal')).pop(); return r ? { text: r.textContent.replace(/\s+/g, ' ').trim(), take: !!r.querySelector('button[data-fix="take-control"]') } : null; });
      check('(9) a take still stopping the agent: the refusal says so, without another Take control', r9 && /still stopping Process's work/.test(r9.text) && !r9.take, r9);
    } else check('(9) a machine control to press', false, fix9);
    stub.takingOver = false;

    // ---- pictures for the people: dark theme, and a phone ---------------------------------------------------------------------
    await page.evaluate(() => { IL.msg('lock', null); IL.panel.close(); });
    stub.holder = agent(12, 2);
    await waitCell(page, /^Process · working/);
    await page.evaluate(() => IL.setTheme('dark'));
    await sleep(500);
    await page.screenshot({ path: path.join(OUT, 'lock-agent-dark.png') });
    await page.evaluate(() => IL.setTheme('light'));
    await page.setViewportSize({ width: 390, height: 844 });
    await sleep(800);
    await page.screenshot({ path: path.join(OUT, 'lock-phone.png') });
    const phone = await page.evaluate(() => { const b = document.querySelector('#msg-area [data-msg="held"] button[data-fix="take-control"]'); const r = b && b.getBoundingClientRect(); return r ? { w: r.width, h: r.height, inView: r.top >= 0 && r.bottom <= innerHeight } : null; });
    check('(phone) on a phone the band and its Take control are on screen', phone && phone.inView && phone.h >= 32, phone);
    await page.setViewportSize({ width: 1440, height: 900 });

    // ---- every request said it is the UI's ---------------------------------------------------------------------------------
    check('(0) every API request this page sent carries X-IL-Client: ui', stub.noClient.length === 0, Array.from(new Set(stub.noClient)).slice(0, 12));
    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'lock-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'lock-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
