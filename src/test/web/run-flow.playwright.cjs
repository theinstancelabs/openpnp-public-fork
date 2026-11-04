/**
 * Run › Start on the simulator (docs/ui-flows/board-flow.md C1-C3; mockup `run-start`, the user's OK of 11-02): the
 * approved Start screen over today's run abilities. (1) Run, while nothing runs, opens Start: the main column and the
 * panel are Start's, today's pages stay one link away (Step by step); (2) Design: a card per board with its picture,
 * the planned board chosen, New design opens Designs; (3) Boards this run: One board, the tile not built yet (BR-39);
 * (4) Load side 1: the side, its pads, where it sits and the time; (5) Start waits for the three things only a person
 * can say (the tip on the dispenser, the paste within its life, the gauge), each kept 8 hours as today; (6) a need of
 * the readiness checks shows as a Needs-you card with its fix and keeps Start locked; (7) a refusal on the way stops
 * the chain where it is, its words in the panel, nothing after it sent; (8) one Start: plan when needed, register,
 * then the run of the registered plan, in that order (the test cuts the run to its first pads); (10) Live while it
 * pastes: the rail, "You can leave now", the board picture in its frame, four tiles, STOP and the steps (the ones the
 * machine cannot do yet say so); (11) Done after it, part of the board: the pads left first, the record one click away;
 * (12) back to Run later, the board still part done: Needs you, one card, one primary action; (9) Step by step opens
 * today's Checks; no JavaScript errors, no native dialogs.
 * States the SIM does not hold on demand (a need, a refusal) come from Playwright routes. Runs after tip-flow in the
 * main leg (the board, the waste, the needle frame and the purge row that webui-sim set up). Nothing here runs a real
 * machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (run-flow-results.json).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-run-flow-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
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
const shown = (page, sel) => page.evaluate((s) => { const n = document.querySelector(s); return !!n && !n.closest('[hidden]') && n.getClientRects().length > 0; }, sel);
const startState = (page) => page.evaluate(() => {
  const b = document.getElementById('btn-rs-start');
  const w = document.getElementById('rs-start-why');
  return b ? { disabled: b.disabled, label: b.textContent.trim(), why: w && !w.hidden ? w.textContent.trim() : '' } : null;
});
const tick = (page, id, on) => page.evaluate(([i, v]) => { const c = document.getElementById(i); if (c && c.checked !== v) c.click(); return !!c && c.checked; }, [id, on]);

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  try {
    await page.goto(BASE + '#run');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.runFlow && IL.ready, null, { timeout: 30000 });
    await page.waitForFunction(() => !!document.getElementById('btn-rs-start'), null, { timeout: 15000 }).catch(() => null);

    // ---- (1) Run opens Start while nothing runs ---------------------------------------------------------------------
    const s1 = await page.evaluate(() => ({ run: IL.S.status.run, hash: location.hash }));
    check('(1) Run, while nothing runs, opens Start: its own main column and panel, not today\'s board picture', await shown(page, '#run-start')
      && !(await shown(page, '#run-hero')) && await shown(page, '#btn-rs-start') && (await page.textContent('#run-start .eyebrow')).trim() === 'Make a board', s1);
    check('(1) today\'s pages are one link away: Step by step', await shown(page, '#rs-step-by-step')
      && (await page.getAttribute('#rs-step-by-step', 'href')) === '#run/checks');

    // ---- (2) Design ---------------------------------------------------------------------------------------------------
    const boards = await page.evaluate(() => IL.api('GET', '/api/boards'));
    const want = [].concat((boards.job || []).map((b) => 'job:' + b.boardLocationId), (boards.files || []).filter((b) => b.file).map((b) => 'file:' + b.file));
    await page.waitForFunction((n) => document.querySelectorAll('#rs-designs .design[data-board]').length === n, want.length, { timeout: 15000 }).catch(() => null);
    const cards = await page.evaluate(() => Array.from(document.querySelectorAll('#rs-designs .design[data-board]')).map((d) => ({ board: d.dataset.board,
      pressed: d.getAttribute('aria-pressed'), pic: !!d.querySelector('.design__img img, .design__img .design__none'), t: (d.querySelector('.design__t') || {}).textContent || '' })));
    check('(2) a design card per board, each with its picture or the empty frame', cards.length === want.length && want.every((w) => cards.some((c) => c.board === w)) && cards.every((c) => c.pic && c.t),
      { want, cards });
    const planned = await page.evaluate(() => { const s = IL.S.plan && IL.S.plan.source; return s ? (s.source === 'job' ? 'job:' + s.boardLocationId : 'file:' + s.file) : null; });
    check('(2) the planned board is the chosen one', !!planned && cards.filter((c) => c.pressed === 'true').map((c) => c.board).join() === planned, { planned, cards });
    check('(2) New design opens Designs', (await page.getAttribute('#rs-new-design', 'href')) === '#designs');

    // ---- (3) Boards this run -----------------------------------------------------------------------------------------
    const b3 = await page.evaluate(() => ({ one: document.getElementById('rs-one').getAttribute('aria-pressed'), tile: document.getElementById('rs-tile').disabled,
      cap: document.getElementById('rs-boards-cap').textContent }));
    check('(3) Boards this run: One board; Boards on the tile is not built yet (BR-39)', b3.one === 'true' && b3.tile && /not built yet/i.test(b3.cap), b3);

    // ---- (4) Load side 1 ---------------------------------------------------------------------------------------------
    const pads = await page.evaluate(() => IL.S.plan && IL.S.plan.summary && IL.S.plan.summary.pads);
    const load = await page.evaluate(() => ({ t: document.querySelector('#rs-load-card .card__title').textContent, kv: document.getElementById('rs-load-kv').textContent,
      cap: document.getElementById('rs-load-cap').textContent, pic: !!document.querySelector('#rs-load-pic img, #rs-load-pic .design__none') }));
    check('(4) Load side 1: the top, its pads, where it sits and the time', /Load side 1: the top/.test(load.t) && load.pic && new RegExp('\\b' + pads + ' pads').test(load.kv)
      && /X -?[\d.]+.*Y -?[\d.]+/.test(load.kv) && /≈ \d+/.test(load.kv) && /top side up/.test(load.cap), { pads, load });

    // ---- (5) the three answers only a person can give -----------------------------------------------------------------
    await page.evaluate(() => { for (const k of ['tip-on', 'paste-life', 'regulator']) IL.attest.clear(k); });
    await page.waitForFunction(() => { const b = document.getElementById('btn-rs-start'); return b && b.disabled; }, null, { timeout: 5000 }).catch(() => null);
    const st5a = await startState(page);
    check('(5) Start waits for the tip, the paste and the gauge, and says so', !!st5a && st5a.disabled && /tip/.test(st5a.why) && /paste/.test(st5a.why) && /gauge/.test(st5a.why), st5a);
    for (const id of ['rs-tick-tip', 'rs-tick-paste', 'rs-tick-gauge']) await tick(page, id, true);
    const kept = await page.evaluate(() => ['tip-on', 'paste-life', 'regulator'].map((k) => !!IL.attest.valid(k, IL.S.status)));
    await page.waitForFunction(() => { const w = document.getElementById('rs-start-why'); return !w || w.hidden || !/tip|paste|gauge/.test(w.textContent); }, null, { timeout: 5000 }).catch(() => null);
    const st5b = await startState(page);
    check('(5) the three ticks are kept as today\'s answers (8 h) and free Start of them', kept.every(Boolean) && !/tip|paste|gauge/.test(st5b.why), { kept, st5b });
    check('(5) Start says it is also "hands and tools clear", and when you can leave', /hands and tools are clear/.test(await page.textContent('#rs-start-cap'))
      && /you can leave/.test(await page.textContent('#rs-start-cap')));
    await page.screenshot({ path: path.join(OUT, 'run-start.png') });

    // ---- (6) a need of the readiness checks ---------------------------------------------------------------------------
    await fakeStatus(page, (s) => { if (s.dispense && s.dispense.purgeRow) Object.assign(s.dispense.purgeRow, { defined: false, spotsLeft: 0, boardsLeft: 0 }); });
    await page.waitForFunction(() => !!document.querySelector('#rs-needs .ri[data-key]'), null, { timeout: 8000 }).catch(() => null);
    const n6 = await page.evaluate(() => ({ cards: Array.from(document.querySelectorAll('#rs-needs .ri[data-key]')).map((r) => ({ key: r.dataset.key, t: r.textContent.slice(0, 140), fix: !!r.querySelector('[data-fix]') })),
      start: document.getElementById('btn-rs-start').disabled }));
    check('(6) a readiness need (no purge row) is a Needs-you card with its fix, and Start stays locked', n6.start && n6.cards.some((c) => /purge/i.test(c.key + ' ' + c.t) && c.fix), n6);
    await page.unroute('**/api/status');
    await page.waitForFunction(() => !document.querySelector('#rs-needs .ri[data-key*="purge" i]'), null, { timeout: 8000 }).catch(() => null);

    // ---- (7) a refusal on the way stops the chain ---------------------------------------------------------------------
    const sent7 = [];
    await page.route('**/api/plan/*/register', (r) => { sent7.push('register'); return r.fulfill({ status: 409, json: { error: 'refused', reason: 'Register board refused by the interlocks: MACHINE: machine is not enabled' } }); });
    await page.route('**/api/run', (r) => { sent7.push('run'); return r.abort(); });
    await page.waitForSelector('#btn-rs-start:enabled', { timeout: 10000 }).catch(() => null);
    await page.click('#btn-rs-start');
    await page.waitForFunction(() => /machine is not enabled/.test((document.getElementById('rs-needs') || {}).textContent || ''), null, { timeout: 10000 }).catch(() => null);
    await sleep(800);
    const r7 = await page.evaluate(() => ({ needs: document.getElementById('rs-needs').textContent, find: (document.querySelector('#rs-then li[data-k="find"]') || {}).className }));
    check('(7) a refused registration stops the chain there: its words in the panel, no run sent', sent7.join() === 'register' && /machine is not enabled/.test(r7.needs), { sent7, r7 });
    await page.unroute('**/api/plan/*/register');
    await page.unroute('**/api/run');

    // ---- (8) one Start on the SIM: plan when needed, register, then the registered plan's run --------------------------
    const order = [];
    page.on('request', (q) => {
      const u = q.url();
      if (q.method() !== 'POST') return;
      if (/\/api\/plan$/.test(u)) order.push({ k: 'plan' });
      else if (/\/api\/plan\/[^/]+\/register$/.test(u)) order.push({ k: 'register', planId: decodeURIComponent(u.split('/api/plan/')[1].split('/')[0]) });
      else if (/\/api\/run$/.test(u)) order.push({ k: 'run', planId: JSON.parse(q.postData() || '{}').planId });
    });
    // the test lays the first pads only (a board's worth of dots is webui-sim's): whole pads, as a test run
    await page.route('**/api/run', async (r) => {
      const b = JSON.parse(r.request().postData() || '{}');
      b.maxDots = 4;
      await r.continue({ postData: JSON.stringify(b) });
    });
    await page.waitForSelector('#btn-rs-start:enabled', { timeout: 15000 }).catch(() => null);
    const pre8 = await startState(page);
    const runId0 = await page.evaluate(() => (IL.S.status.run && IL.S.status.run.runId) || null);
    await page.click('#btn-rs-start');
    // a board run other than the one before (webui-sim's last run is still the status's until this one starts)
    const ran = await page.waitForFunction((id0) => { const r = IL.S.status.run; return !!r && r.label === 'board' && !!r.runId && r.runId !== id0; }, runId0,
      { timeout: 300000, polling: 500 }).then(() => true, () => false);
    // ---- (10) Live while it pastes (the cut run lasts some seconds: read at once) -------------------------------------
    const live = await page.waitForFunction(() => { const t = document.getElementById('rv-title'); return !!t && /Pasting side 1/.test(t.textContent) && !document.getElementById('run-view').hidden; },
      null, { timeout: 15000, polling: 200 }).then(() => page.evaluate(() => ({
      leave: (document.getElementById('rv-leave-box') || {}).textContent || '',
      rail: Array.from(document.querySelectorAll('#rv-rail .step')).map((x) => [x.dataset.k, x.className.replace(/^step ?/, '')]),
      heroHere: !!document.querySelector('#rv-slot > #run-hero'), tiles: Array.from(document.querySelectorAll('#rv-tiles .tile')).map((x) => x.dataset.k),
      parts: ((document.querySelector('#rv-tiles .tile[data-k="Parts"] .tile__s') || {}).textContent || ''),
      stop: !!document.getElementById('rv-stop'), check: ((document.querySelector('#rv-steps li[data-k="check"]') || {}).textContent || '') })), () => null);
    check('(10) Live: "You can leave now", the rail with Paste live, the board picture in its frame, four tiles, STOP in the panel', !!live && /You can leave now/.test(live.leave)
      && live.rail.some(([k, c]) => k === 'Paste' && /live/.test(c)) && live.heroHere && ['Pads', 'Parts', 'Side 1 left', 'Paste on since'].every((k) => live.tiles.includes(k)) && live.stop, live);
    check('(10) Live: what the machine cannot do yet says so (parts, check the paste)', !!live && /not built yet/.test(live.parts) && /not built yet/.test(live.check), live);
    await page.screenshot({ path: path.join(OUT, 'run-live.png') });
    const reg = order.find((x) => x.k === 'register'), run = order.find((x) => x.k === 'run');
    const regRun = await page.evaluate(() => { const r = IL.S.status.lastRun; return r; });
    check('(8) one Start: register, then the run, in that order (a plan first only when needed)', ran && !!reg && !!run && order.indexOf(reg) < order.indexOf(run)
      && order.filter((x) => x.k === 'plan').every((x) => order.indexOf(x) < order.indexOf(reg)), { order, pre8 });
    check('(8) the run is the registered plan\'s (the plan Register made), not the plan before', !!reg && !!run && run.planId !== reg.planId
      && String(run.planId).length > 4, { order, regRun });
    await page.waitForFunction((id0) => { const r = IL.S.status.run; return !!r && r.label === 'board' && r.runId !== id0 && !r.running && !!r.finishedAtMs; }, runId0,
      { timeout: 300000, polling: 1000 }).catch(() => null);
    await page.unroute('**/api/run');
    const end8 = await page.evaluate(() => ({ run: IL.S.status.run && { status: IL.S.status.run.status, dotsDone: IL.S.status.run.dotsDone, runId: IL.S.status.run.runId } }));
    check('(8) the paste ran to its end on the SIM', !!end8.run && end8.run.status === 'OK' && end8.run.dotsDone > 0, end8);

    // ---- (11) Done: part of the board (the test's cut run) --------------------------------------------------------------
    await page.waitForFunction(() => /^Part done: board/.test((document.getElementById('rv-title') || {}).textContent || ''), null, { timeout: 15000 }).catch(() => null);
    const done = await page.evaluate(() => ({ title: document.getElementById('rv-title').textContent, go: (document.getElementById('rv-done-go') || {}).textContent || '',
      other: (document.getElementById('rv-done-other') || {}).textContent || '', open: (document.getElementById('rv-done-open') || { getAttribute: () => null }).getAttribute('href'),
      heroHere: !!document.querySelector('#rv-slot > #run-hero'), tiles: Array.from(document.querySelectorAll('#rv-tiles .tile')).map((x) => x.dataset.k) }));
    check('(11) Done, part of the board: the pads left first, the next board as it is, the record one click away', /^Part done: board/.test(done.title)
      && /^Finish the \d+ pads? left$/.test(done.go) && /Next board/.test(done.other) && done.open === '#records/boards/' + (end8.run && end8.run.runId) && done.heroHere
      && ['Pads', 'Parts', 'Hand fixes', 'Time'].every((k) => done.tiles.includes(k)), done);
    await page.click('#rv-done-go');
    const dlg11 = await page.waitForSelector('#dlg-run', { state: 'visible', timeout: 8000 }).then(() => true, () => false);
    check('(11) Finish the pads left opens the run confirm for the pads left (today\'s)', dlg11 && /left/i.test(await page.textContent('#dlg-run')));
    if (dlg11) await page.click('#btn-run-cancel');
    await page.screenshot({ path: path.join(OUT, 'run-done.png') });

    // ---- (12) back to Run later, the board still part done: Needs you ---------------------------------------------------
    await page.evaluate(() => IL.go('#tip'));
    await sleep(500);
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !!document.getElementById('rv-need-go'), null, { timeout: 10000 }).catch(() => null);
    const need = await page.evaluate(() => ({ title: (document.getElementById('rv-title') || {}).textContent || '', leave: (document.getElementById('rv-leave-box') || {}).textContent || '',
      go: (document.getElementById('rv-need-go') || {}).textContent || '', more: (document.getElementById('rv-need-more') || {}).textContent || '',
      cards: document.querySelectorAll('#rv-panel .ri').length, heroHere: !!document.querySelector('#rv-slot > #run-hero') }));
    check('(12) Needs you: one card with one primary action (the pads left), the other choices behind it, the board in its frame', /Part done|Stopped/.test(need.title)
      && /Needs you/.test(need.leave) && /^Finish the \d+ pads? left$/.test(need.go) && /Wipe all, run all/.test(need.more) && /Step by step/.test(need.more) && need.cards === 1 && need.heroHere, need);
    await page.screenshot({ path: path.join(OUT, 'run-need.png') });

    // ---- (9) Step by step ----------------------------------------------------------------------------------------------
    await page.evaluate(() => IL.go('#run/checks'));
    check('(9) Step by step: today\'s Checks with its board picture', await page.waitForSelector('#plan-interlocks', { state: 'visible', timeout: 10000 }).then(() => true, () => false)
      && await shown(page, '#run-hero') && !(await shown(page, '#run-start')));

    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'run-flow-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'run-flow-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
