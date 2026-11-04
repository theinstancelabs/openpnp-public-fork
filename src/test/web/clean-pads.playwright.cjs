/**
 * Clean pads before a run (BR-76; the flow the user agreed 11-03 23:4x: docs/ui-flows/clean-pads-flow.md). Until
 * Backend's BR-76 lands its fields are stubbed here as agreed (docs/backend-requests.md, BR-76 "What the UI reads" and
 * "Backend's changes (11-04 02:5x)"): `status.padLedgers`, GET /api/boards/{board}/pads, POST /api/boards/{board}/wiped,
 * a plan's `padLedger: {board, needed, clean}`. The stub board 'ftp' has 40 pads in two sets, R and D.
 * (1) Calibrate › Test board: "23 of 40 pads clean (R 13, D 10) · wiped hh:mm", the drawing with the used pads marked and
 * the fixed pad named; (2) an agent's whole-plan need; (3) the run checklist's line (IL.readiness item 9): enough clean
 * pads say the numbers, too few block the run ("Wipe the board first") and offer Board wiped; (4) Board wiped: two
 * presses, the camera check running, then "31 clean, 8 still have paste" with those 8 marked; a failed check says so;
 * (5) the band while an agent's run waits for clean pads; (6) no JavaScript errors. Nothing moves: the wipe and its
 * camera check are stubbed; the SIM is started and homed for the button's gate.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (clean-pads-results.json, clean-pads-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-clean-pads-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const api = (page, method, p, body) => page.evaluate(([m, x, b]) => IL.api(m, x, b === null ? undefined : b, { timeoutMs: 120000 }), [method, p, body === undefined ? null : body]);
/** The two presses of a two-press button (§11): the second ≥ 300 ms after the first, once it asks for it. */
async function press2(page, sel) {
  await page.waitForSelector(sel + ':enabled', { timeout: 30000 });
  await page.click(sel);
  await page.waitForSelector(sel + '.btn--press', { timeout: 2000 });
  await sleep(450);
  await page.click(sel);
}

// ---- the stub of BR-76 --------------------------------------------------------------------------------------------
const BOARD = 'ftp';
const SETS = ['R', 'D'];
const HOUR = 3600 * 1000;
function makePads() {
  const pads = [];
  SETS.forEach((set, si) => {
    for (let n = 1; n <= 10; n++) {
      for (const k of [1, 2]) {
        pads.push({ id: set + n + '.' + k, set, state: 'clean', atMs: null, by: null, kind: null, note: null,
          x: 60 + (n - 1) * 4 + (k - 1) * 1.6, y: 100 + si * 6, w: 0.9, h: 0.8, rotation: 0 });
      }
    }
  });
  return pads;
}
const L = { pads: makePads(), wipedAtMs: Date.now() - 2 * HOUR, check: null, need: null, waiting: null, wipes: [] };
function mark(ids, o) { for (const p of L.pads) if (ids.includes(p.id)) Object.assign(p, o); }
const ids = (set, from, to) => { const out = []; for (let n = from; n <= to; n++) out.push(set + n + '.1', set + n + '.2'); return out; };
// before: R1-R3 and D1-D5 pasted by Process's runs since the wipe, R5.1 carries old solder (survives a wipe)
mark(ids('R', 1, 3).concat(ids('D', 1, 5)), { state: 'used', atMs: Date.now() - HOUR, by: 'Process', kind: 'dot' });
mark(['R5.1'], { state: 'fixed', note: 'old solder (survives a wipe)' });
function summary() {
  const sets = SETS.map((set) => {
    const ps = L.pads.filter((p) => p.set === set);
    return { set, total: ps.length, clean: ps.filter((p) => p.state === 'clean').length };
  });
  return { board: BOARD, name: 'Opulo test board', total: L.pads.length, clean: L.pads.filter((p) => p.state === 'clean').length,
    wipedAtMs: L.wipedAtMs, sets, check: L.check, need: L.need, waiting: L.waiting };
}
const VIEW = { art: null, widthMm: 50, heightMm: 20, toMachine: [1, 0, 0, 1, 55, 95] };
async function stubRoutes(context) {
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    if (url.pathname === '/api/status' && method === 'GET') {
      const resp = await route.fetch();
      const body = await resp.json();
      body.padLedgers = [summary()];
      return route.fulfill({ response: resp, json: body });
    }
    if (url.pathname === '/api/boards/' + BOARD + '/pads' && method === 'GET') {
      return route.fulfill({ json: Object.assign(summary(), { pads: L.pads, view: VIEW }) });
    }
    if (url.pathname === '/api/boards/' + BOARD + '/wiped' && method === 'POST') {
      const now = Date.now();
      L.wipes.push({ body: req.postDataJSON(), client: (await req.allHeaders())['x-il-client'] });
      L.wipedAtMs = now;
      // the pads keep their state while the check runs; they change when it ends (Backend 11-04 02:5x)
      L.check = { state: 'running', atMs: now, clean: null, stillPaste: null };
      return route.fulfill({ json: { wipedAtMs: now, check: { state: 'running', pads: L.pads.length, etaS: 40 } } });
    }
    return route.continue();
  });
}
/** The camera check ends: every pad clean but the fixed one and `paste` (state used, kind still-paste). */
function checkEnds(paste) {
  const now = Date.now();
  for (const p of L.pads) {
    if (p.state === 'fixed') continue;
    Object.assign(p, paste.includes(p.id) ? { state: 'used', atMs: now, by: 'wipe check', kind: 'still-paste' }
      : { state: 'clean', atMs: null, by: null, kind: null });
  }
  L.check = { state: 'done', atMs: now, clean: L.pads.filter((p) => p.state === 'clean').length, stillPaste: paste.length };
}

const card = (page) => page.evaluate(() => {
  const t = (id) => { const n = document.getElementById(id); return n && !n.hidden ? n.textContent.replace(/\s+/g, ' ').trim() : null; };
  const map = document.getElementById('tb-map');
  const q = (sel) => (map ? map.querySelectorAll(sel).length : -1);
  const b = document.getElementById('btn-tb-wiped');
  return { sum: t('tb-sum'), need: t('tb-need'), check: t('tb-check'), fixed: t('tb-fixed'), pads: q('rect.pad'), used: q('rect.pad.dirty'),
    fixedPads: q('rect.pad.blocked'), btn: b ? { disabled: b.disabled, text: b.textContent.trim(), fix: b.dataset.fix, title: b.title } : null };
});

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ extraHTTPHeaders: { 'X-IL-Client': 'ui' }, viewport: { width: 1440, height: 900 } });
  await stubRoutes(context);
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  try {
    await page.goto(BASE + '#calibrate/test-board');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.S.status.padLedgers, null, { timeout: 30000 });

    // ---- (1) Calibrate › Test board ----------------------------------------------------------------------------------
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad').length > 0; }, null, { timeout: 15000 }).catch(() => null);
    const c1 = await card(page);
    const wiped = new Date(L.wipedAtMs).toTimeString().slice(0, 5);
    check('(1) Test board: "23 of 40 pads clean (R 13, D 10) · wiped hh:mm"', c1.sum === '23 of 40 pads clean (R 13, D 10) · wiped ' + wiped, { sum: c1.sum, wiped });
    check('(1) the drawing: 40 pads, the 16 used marked, the fixed one apart', c1.pads === 40 && c1.used === 16 && c1.fixedPads === 1, c1);
    check('(1) the fixed pad is named with its reason', /R5\.1/.test(c1.fixed || '') && /old solder/.test(c1.fixed || ''), c1.fixed);
    check('(1) no need line and no check line yet', c1.need === null && c1.check === null, c1);
    // the camera check moves the head: Board wiped waits for a started, homed machine (this session's may be either)
    const m1 = await page.evaluate(() => ({ enabled: IL.S.status.machine.enabled, homed: IL.S.status.machine.homed }));
    check('(1) Board wiped waits for a started, homed machine', !!c1.btn && c1.btn.fix === 'board-wiped'
      && (m1.enabled && m1.homed ? !c1.btn.disabled : c1.btn.disabled && /connect|home/i.test(c1.btn.title)), { btn: c1.btn, machine: m1 });
    await page.screenshot({ path: path.join(OUT, 'clean-pads-card.png') });

    // ---- (2) an agent's whole-plan need (stated before its first run) -----------------------------------------------
    L.need = { pads: 30, by: 'Process', what: 'tonight’s tests', atMs: Date.now() };
    await page.waitForFunction(() => { const n = document.getElementById('tb-need'); return n && !n.hidden; }, null, { timeout: 8000 }).catch(() => null);
    const c2 = await card(page);
    check('(2) the plan\'s need: "Process needs 30 clean pads for tonight’s tests: 23 clean. Wipe the board before its first run."',
      c2.need === 'Process needs 30 clean pads for tonight’s tests: 23 clean. Wipe the board before its first run.', c2.need);
    L.need = null;

    // ---- (3) the run checklist's line (IL.readiness item 9) -----------------------------------------------------------
    const ready = (needed, clean) => page.evaluate(([n, c]) => {
      const plan = { planId: 'p-clean-pads', createdAtMs: Date.now(), dots: [], pads: [], interlocks: { violations: [] }, padLedger: { board: 'ftp', needed: n, clean: c } };
      const r = IL.readiness(IL.S.status, plan, IL.readyCtx());
      const it = r.items[8];
      return { n: r.items.length, total: r.total, key: it.key, title: it.title, state: it.state, value: it.value, short: it.short, detail: it.detail,
        fixes: it.fixes.map((f) => ({ fix: f.fix || null, arg: f.arg || null, href: f.href || null, label: f.label })), verdict: r.verdict.state, canRun: r.canRun, runReason: r.runReason };
    }, [needed, clean]);
    const none3 = await page.evaluate(() => {
      const plan = { planId: 'p-no-ledger', createdAtMs: Date.now(), dots: [], pads: [], interlocks: { violations: [] }, padLedger: null };
      const it = IL.readiness(IL.S.status, plan, IL.readyCtx()).items[8];
      return { state: it.state, value: it.value, detail: it.detail };
    });
    check('(3) a run that pastes no ledger board: item 9 as before, no clean-pad numbers', !/Clean pads ·|Wipe the board first/.test(none3.detail || ''), none3);
    const ok3 = await ready(20, 23);
    // with the camera check off (the SIM's default) the line is the ledger's "23 clean"; with it on, the camera's line
    check('(3) enough: item 9 leads with the numbers, still 9 items', ok3.n === 9 && ok3.total === 9 && ok3.key === 'clean'
      && /^Clean pads · 23, this run needs 20\. /.test(ok3.detail)
      && (none3.state === 'off' ? ok3.state === 'ok' && ok3.value === '23 clean' : ok3.state === none3.state && ok3.value === none3.value), { ok3, none3 });
    const short3 = await ready(24, 23);
    check('(3) short: item 9 blocks with both numbers and offers Board wiped for that board', short3.state === 'block' && short3.value === '23 of 24'
      && /^Wipe the board first: 23 clean, this run needs 24\./.test(short3.detail) && short3.fixes.some((f) => f.fix === 'board-wiped' && f.arg === 'ftp')
      && short3.fixes.some((f) => f.href === '#calibrate/test-board'), short3);
    check('(3) short: the verdict is blocked and the run cannot start', short3.verdict === 'block' && !short3.canRun, short3);

    // ---- (4) Board wiped: two presses, the camera check, its result ---------------------------------------------------
    const s0 = await page.evaluate(() => IL.S.status.machine);
    if (!s0.enabled) { await api(page, 'POST', '/api/machine/enable'); await page.waitForFunction(() => IL.S.status.machine.enabled, null, { timeout: 30000 }); }
    if (!s0.homed) await api(page, 'POST', '/api/machine/home', { wait: true });
    await page.waitForFunction(() => IL.S.status.machine.homed, null, { timeout: 60000 });
    await press2(page, '#btn-tb-wiped');
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && !n.hidden && /Checking/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const c4 = await card(page);
    check('(4) Board wiped: one POST with an empty body, sent as the UI', L.wipes.length === 1 && JSON.stringify(L.wipes[0].body) === '{}' && L.wipes[0].client === 'ui', L.wipes);
    check('(4) while the camera checks: "Checking every pad with the camera…", the pads as they were', /^Checking every pad with the camera/.test(c4.check || '') && c4.used === 16, c4);
    const still = ['R1.1', 'R1.2', 'R2.1', 'R2.2', 'D1.1', 'D1.2', 'D2.1', 'D2.2'];
    checkEnds(still);
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && /still have paste/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad.dirty').length === 8; }, null, { timeout: 8000 }).catch(() => null);
    const c4b = await card(page);
    const wiped4 = new Date(L.wipedAtMs).toTimeString().slice(0, 5);
    check('(4) the check\'s result: "31 clean, 8 still have paste", those 8 marked', /^31 clean, 8 still have paste\b/.test(c4b.check || '') && c4b.used === 8, c4b);
    check('(4) the count after the wipe: "31 of 40 pads clean (R 15, D 16) · wiped hh:mm"', c4b.sum === '31 of 40 pads clean (R 15, D 16) · wiped ' + wiped4, c4b.sum);
    await page.screenshot({ path: path.join(OUT, 'clean-pads-checked.png') });
    L.check = { state: 'failed', atMs: Date.now(), why: 'the top camera did not answer', clean: null, stillPaste: null };
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && /failed/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const c4c = await card(page);
    check('(4) a failed check says so and why, and that the pads it did not reach count as before', /^The camera check failed: the top camera did not answer\. The pads it did not reach count as before/.test(c4c.check || ''), c4c.check);

    // ---- (5) the band while an agent's run waits for clean pads --------------------------------------------------------
    L.waiting = { by: 'Process', what: 'pad calibration', clean: 10, needed: 24, atMs: Date.now() };
    await page.evaluate(() => IL.go('#run'));
    await page.waitForSelector('#msg-area [data-msg="pads-wait"]', { timeout: 8000 }).catch(() => null);
    const band = await page.evaluate(() => {
      const b = document.querySelector('#msg-area [data-msg="pads-wait"]');
      return b ? { text: b.querySelector('.msg__t').textContent.replace(/\s+/g, ' ').trim(), fix: Array.from(b.querySelectorAll('button[data-fix]')).map((x) => x.dataset.fix) } : null;
    });
    check('(5) the band: "Process\'s pad calibration waits for clean pads: 10 clean, it needs 24." and Board wiped',
      !!band && band.text.startsWith('Process’s pad calibration waits for clean pads: 10 clean, it needs 24.') && /Wipe the board, then press Board wiped\./.test(band.text)
      && band.fix.includes('board-wiped'), band);
    await page.screenshot({ path: path.join(OUT, 'clean-pads-band.png') });
    L.waiting = null;
    const gone = await page.waitForFunction(() => !document.querySelector('#msg-area [data-msg="pads-wait"]'), null, { timeout: 8000 }).then(() => true, () => false);
    check('(5) the band goes when the run no longer waits', gone);

    check('(6) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
  } catch (e) {
    check('test ran to the end', false, String((e && e.stack) || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'clean-pads-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    await browser.close();
    const passed = results.filter((r) => r.ok).length;
    fs.writeFileSync(path.join(OUT, 'clean-pads-results.json'), JSON.stringify({ passed, failed: results.length - passed, ms: Date.now() - t0, results }, null, 2));
    console.log(passed + ' passed, ' + (results.length - passed) + ' failed (' + Math.round((Date.now() - t0) / 1000) + ' s)');
    process.exit(results.length && passed === results.length ? 0 : 1);
  }
})();
