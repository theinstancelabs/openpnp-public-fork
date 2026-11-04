/**
 * Clean pads before a run (BR-76; the flow the user agreed 11-03 23:4x: docs/ui-flows/clean-pads-flow.md). Until
 * Backend's BR-76 lands its fields are stubbed here as agreed (docs/backend-requests.md, BR-76 "What the UI reads" and
 * "Backend's changes (11-04 02:5x)"): `status.padLedgers`, GET /api/boards/{board}/pads, POST /api/boards/{board}/wiped,
 * a plan's `padLedger: {board, needed, clean}`, the lock of an agent waiting for clean pads (Backend's BR-76 (b)). The stub
 * board 'ftp' has 40 pads in two sets, R and D; a second board 'tile' has 6 pads, no sets and no wipe yet.
 * (0) before BR-76 (no field) the card is hidden; with no ledger yet it says so; (1) Calibrate › Test board: "23 of 40
 * pads clean (R 13, D 10) · wiped hh:mm", the drawing with the used pads marked and the fixed pad named; (2) an agent's
 * whole-plan need; (3) the run checklist's line (IL.readiness item 9): enough clean pads say the numbers, too few block
 * the run ("Wipe the board first") and offer Board wiped, also on the Run page with a plan of the SIM's board; (4) Board
 * wiped: two presses, the camera check running, then "31 clean, 8 still have paste" with those 8 marked; a failed check
 * and a clean one say so; the other board: its count, and Board wiped sent for it; (5) the band while an agent's run
 * waits for clean pads, Board wiped under that agent's lock, greyed once the agent works again; (6) no JavaScript errors.
 * Nothing moves: the wipe and its camera check are stubbed; the SIM is started and homed for the button's gate.
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
/** `h` hours ago, but never before today 00:00:30: the card adds the date to an older time (a run at 00:30), and the
 *  page subtracts the server clock's skew (a time right at midnight could read as yesterday). */
const ago = (h) => { const m = new Date(); m.setHours(0, 0, 30, 0); return Math.max(Date.now() - h * HOUR, m.getTime()); };
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
const L = { pads: makePads(), wipedAtMs: ago(2), check: null, need: null, waiting: null, wipes: [] };
function mark(ids, o) { for (const p of L.pads) if (ids.includes(p.id)) Object.assign(p, o); }
const ids = (set, from, to) => { const out = []; for (let n = from; n <= to; n++) out.push(set + n + '.1', set + n + '.2'); return out; };
// before: R1-R3 and D1-D5 pasted by Process's runs since the wipe, R5.1 carries old solder (survives a wipe)
mark(ids('R', 1, 3).concat(ids('D', 1, 5)), { state: 'used', atMs: ago(1), by: 'Process', kind: 'dot' });
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
// the second board: 6 pads, no sets, never wiped
const T = { wipes: 0, pads: [1, 2, 3, 4, 5, 6].map((n) => ({ id: 'J2.' + n, set: null, state: 'clean', atMs: null, by: null, kind: null, note: null, x: 150 + n * 2.54, y: 60, w: 1.7, h: 1.7, rotation: 0 })) };
const tileSummary = () => ({ board: 'tile', name: 'Nano tile', total: T.pads.length, clean: T.pads.filter((p) => p.state === 'clean').length, wipedAtMs: null, sets: [], check: null, need: null, waiting: null });
// mode 'none': an OpenPnP before BR-76 (no field); 'empty': no ledger yet; 'two': ftp and the tile
const ST = { mode: 'none', lock: null, planNeeded: null, plans: 0 };
const AGENT = () => ({ kind: 'agent', who: 'process', label: 'Process', what: 'pad calibration', thread: 'mcp:test', sinceMs: Date.now() - 60000, lastActiveMs: Date.now() - 5000, busy: false });
async function stubRoutes(context) {
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    if (url.pathname === '/api/status' && method === 'GET') {
      const resp = await route.fetch();
      const body = await resp.json();
      if (ST.mode === 'none') delete body.padLedgers;
      else if (ST.mode === 'empty') body.padLedgers = [];
      else if (ST.mode === 'two') body.padLedgers = [summary(), tileSummary()];
      if (ST.lock) body.lock = { holder: ST.lock, takenFrom: null };
      return route.fulfill({ response: resp, json: body });
    }
    // a plan of the SIM's board carries the need of a ledger board (BR-76's preview field)
    if (ST.planNeeded !== null && (url.pathname === '/api/plan' && method === 'POST' || /^\/api\/plan\/[A-Za-z0-9-]+$/.test(url.pathname) && method === 'GET')) {
      const resp = await route.fetch();
      const body = await resp.json();
      if (body && body.planId) { body.padLedger = { board: BOARD, needed: ST.planNeeded, clean: summary().clean }; ST.plans++; }
      return route.fulfill({ response: resp, json: body });
    }
    if (url.pathname === '/api/boards/tile/pads' && method === 'GET') {
      return route.fulfill({ json: Object.assign(tileSummary(), { pads: T.pads, view: { art: null, widthMm: 20, heightMm: 6, toMachine: [1, 0, 0, 1, 150, 57] } }) });
    }
    if (url.pathname === '/api/boards/tile/wiped' && method === 'POST') {
      T.wipes++;
      return route.fulfill({ json: { wipedAtMs: Date.now(), check: { state: 'running', pads: T.pads.length, etaS: 10 } } });
    }
    if (url.pathname === '/api/boards/' + BOARD + '/pads' && method === 'GET') {
      return route.fulfill({ json: Object.assign(summary(), { pads: L.pads, view: VIEW }) });
    }
    if (url.pathname === '/api/boards/' + BOARD + '/wiped' && method === 'POST') {
      const now = Date.now();
      L.wipes.push({ body: req.postDataJSON() });
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

/** A picture for the user: the card (or the band) in view, after the last toast has gone. */
async function shot(page, name, cardInView) {
  if (cardInView) await page.evaluate(() => document.getElementById('cal-testboard').scrollIntoView({ block: 'start' }));
  await page.waitForFunction(() => { const t = document.getElementById('toast'); return !t || t.hidden; }, null, { timeout: 15000 }).catch(() => null);
  await sleep(300);
  await page.screenshot({ path: path.join(OUT, name) });
}
const card = (page) => page.evaluate(() => {
  const t = (id) => { const n = document.getElementById(id); return n && !n.hidden ? n.textContent.replace(/\s+/g, ' ').trim() : null; };
  const map = document.getElementById('tb-map');
  const q = (sel) => (map ? map.querySelectorAll(sel).length : -1);
  const b = document.getElementById('btn-tb-wiped');
  const c = document.getElementById('cal-testboard');
  const pick = document.getElementById('tb-pick');
  return { shown: !!c && !c.hidden, pick: pick && !pick.hidden ? Array.from(document.querySelectorAll('#tb-board option')).map((o) => o.value) : null,
    btnShown: !!b && !b.hidden, sum: t('tb-sum'), need: t('tb-need'), check: t('tb-check'), fixed: t('tb-fixed'), pads: q('rect.pad'), used: q('rect.pad.dirty'),
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
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && document.getElementById('cal-testboard'), null, { timeout: 30000 });

    // ---- (0) an OpenPnP before BR-76, then one without a ledger yet ------------------------------------------------------
    await page.waitForFunction(() => document.getElementById('cal-testboard').hidden, null, { timeout: 8000 }).catch(() => null);
    const c0 = await card(page);
    check('(0) before BR-76 (no padLedgers in the status) the Test board card is hidden', !c0.shown, c0);
    ST.mode = 'empty';
    await page.waitForFunction(() => { const c = document.getElementById('cal-testboard'); return c && !c.hidden; }, null, { timeout: 8000 }).catch(() => null);
    const c0b = await card(page);
    check('(0) no ledger yet: "No board keeps a pad count yet…", no Board wiped', c0b.shown && /^No board keeps a pad count yet/.test(c0b.sum || '') && !c0b.btnShown, c0b);
    ST.mode = 'two';
    await page.waitForFunction(() => IL.S.status.padLedgers && IL.S.status.padLedgers.length === 2, null, { timeout: 8000 });

    // ---- (1) Calibrate › Test board ----------------------------------------------------------------------------------
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad').length > 0; }, null, { timeout: 15000 }).catch(() => null);
    const c1 = await card(page);
    const wiped = new Date(L.wipedAtMs).toTimeString().slice(0, 5);
    check('(1) Test board: "23 of 40 pads clean (R 13, D 10) · wiped hh:mm"', c1.sum === '23 of 40 pads clean (R 13, D 10) · wiped ' + wiped, { sum: c1.sum, wiped });
    check('(1) the drawing: 40 pads, the 16 used marked, the fixed one apart', c1.pads === 40 && c1.used === 16 && c1.fixedPads === 1, c1);
    check('(1) the fixed pad is named with its reason', /R5\.1/.test(c1.fixed || '') && /old solder/.test(c1.fixed || ''), c1.fixed);
    check('(1) two boards: a labelled picker, the first board shown', JSON.stringify(c1.pick) === '["ftp","tile"]'
      && await page.evaluate(() => !!document.querySelector('label[for="tb-board"]')), c1.pick);
    const tip = await page.evaluate(() => { const r = document.querySelector('#tb-map rect.pad[data-pad="R1.1"] title'); return r ? r.textContent : null; });
    check('(1) a used pad\'s tooltip says when and by whom', /^R1\.1 · used \d\d:\d\d by Process \(a dot\)$/.test(tip || ''), tip);
    check('(1) no need line and no check line yet', c1.need === null && c1.check === null, c1);
    // the camera check moves the head: Board wiped waits for a started, homed machine (this session's may be either)
    const m1 = await page.evaluate(() => ({ enabled: IL.S.status.machine.enabled, homed: IL.S.status.machine.homed }));
    check('(1) Board wiped waits for a started, homed machine', !!c1.btn && c1.btn.fix === 'board-wiped'
      && (m1.enabled && m1.homed ? !c1.btn.disabled : c1.btn.disabled && /connect|home/i.test(c1.btn.title)), { btn: c1.btn, machine: m1 });
    await shot(page, 'clean-pads-card.png', true);

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
        blocked: r.blocked.map((x) => x.key),
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
    check('(3) short: the clean pads block the run, and the reason says to wipe the board', short3.verdict === 'block' && !short3.canRun
      && short3.blocked.includes('clean') && /wipe the board/.test(short3.runReason), short3);
    // the Run page with a plan of the SIM's board whose need is more than the clean pads
    ST.planNeeded = 24;
    const files = (await api(page, 'GET', '/api/boards')).files || [];
    const file = (files.find((f) => /ftp\.board\.xml$/.test(f.file || '')) || files[0] || {}).file;
    await page.evaluate((b) => IL.api('POST', '/api/plan', b).then((p) => IL.setPlan(p)), { source: 'file', file, x: 121, y: 119, z: 5.43, rotation: 0 });
    await page.evaluate(() => IL.go('#run/checks'));
    await page.waitForFunction(() => { const c = document.querySelector('[data-key="clean"]'); return c && /Wipe the board first/.test(c.textContent); }, null, { timeout: 15000 }).catch(() => null);
    const run3 = await page.evaluate(() => {
      const c = document.querySelector('[data-key="clean"]');
      return c ? { text: c.textContent.replace(/\s+/g, ' ').trim(), fixes: Array.from(c.querySelectorAll('button[data-fix]')).map((b) => b.dataset.fix) } : null;
    });
    check('(3) the Run page: the clean-pads line says both numbers and offers Board wiped and Test board', !!file && ST.plans > 0 && !!run3
      && /Wipe the board first: 23 clean, this run needs 24\./.test(run3.text) && run3.fixes.includes('board-wiped') && run3.fixes.includes('go:#calibrate/test-board'), { file, plans: ST.plans, run3 });
    await page.screenshot({ path: path.join(OUT, 'clean-pads-run.png') });
    await page.evaluate(() => IL.go('#calibrate/test-board'));

    // ---- (4) Board wiped: two presses, the camera check, its result ---------------------------------------------------
    const s0 = await page.evaluate(() => IL.S.status.machine);
    if (!s0.enabled) { await api(page, 'POST', '/api/machine/enable'); await page.waitForFunction(() => IL.S.status.machine.enabled, null, { timeout: 30000 }); }
    if (!s0.homed) await api(page, 'POST', '/api/machine/home', { wait: true });
    await page.waitForFunction(() => IL.S.status.machine.homed, null, { timeout: 60000 });
    await press2(page, '#btn-tb-wiped');
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && !n.hidden && /Checking/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const c4 = await card(page);
    check('(4) Board wiped: one POST for this board, with an empty body', L.wipes.length === 1 && JSON.stringify(L.wipes[0].body) === '{}', L.wipes);
    check('(4) while the camera checks: "Checking every pad with the camera…", the pads as they were', /^Checking every pad with the camera/.test(c4.check || '') && c4.used === 16, c4);
    const still = ['R1.1', 'R1.2', 'R2.1', 'R2.2', 'D1.1', 'D1.2', 'D2.1', 'D2.2'];
    checkEnds(still);
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && /still have paste/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad.dirty').length === 8; }, null, { timeout: 8000 }).catch(() => null);
    const c4b = await card(page);
    const wiped4 = new Date(L.wipedAtMs).toTimeString().slice(0, 5);
    check('(4) the check\'s result: "31 clean, 8 still have paste", those 8 marked', /^31 clean, 8 still have paste\b/.test(c4b.check || '') && c4b.used === 8, c4b);
    check('(4) the count after the wipe: "31 of 40 pads clean (R 15, D 16) · wiped hh:mm"', c4b.sum === '31 of 40 pads clean (R 15, D 16) · wiped ' + wiped4, c4b.sum);
    await shot(page, 'clean-pads-checked.png', true);
    L.check = { state: 'failed', atMs: Date.now(), why: 'the top camera did not answer', clean: null, stillPaste: null };
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && /failed/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const c4c = await card(page);
    check('(4) a failed check says so and why, and that the pads it did not reach count as before', /^The camera check failed: the top camera did not answer\. The pads it did not reach count as before/.test(c4c.check || ''), c4c.check);
    checkEnds([]);
    await page.waitForFunction(() => { const n = document.getElementById('tb-check'); return n && /no paste left/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad.dirty').length === 0; }, null, { timeout: 8000 }).catch(() => null);
    const c4d = await card(page);
    check('(4) a check that finds no paste: "39 clean: no paste left (checked hh:mm)."', /^39 clean: no paste left \(checked \d\d:\d\d\)\.$/.test(c4d.check || '') && c4d.used === 0, c4d);
    // the other board: its own count (no sets, never wiped), and Board wiped sent for it
    await page.selectOption('#tb-board', 'tile');
    await page.waitForFunction(() => { const m = document.getElementById('tb-map'); return m && m.querySelectorAll('rect.pad').length === 6; }, null, { timeout: 8000 }).catch(() => null);
    const c4e = await card(page);
    check('(4) the tile: "6 of 6 pads clean", no sets, no wipe time, 6 pads drawn', c4e.sum === '6 of 6 pads clean' && c4e.pads === 6, c4e);
    await press2(page, '#btn-tb-wiped');
    for (let i = 0; i < 50 && T.wipes < 1; i++) await sleep(100);
    check('(4) Board wiped on the tile goes to the tile, not to the first board', T.wipes === 1 && L.wipes.length === 1, { tile: T.wipes, ftp: L.wipes.length });
    await page.selectOption('#tb-board', 'ftp');

    // ---- (5) the band while an agent's run waits for clean pads --------------------------------------------------------
    L.waiting = { by: 'Process', what: 'pad calibration', clean: 10, needed: 24, atMs: Date.now() };
    ST.lock = AGENT();   // the agent keeps the machine while its run waits (Backend, BR-76 (b))
    await page.evaluate(() => IL.go('#run'));
    await page.waitForSelector('#msg-area [data-msg="pads-wait"]', { timeout: 8000 }).catch(() => null);
    const band = await page.evaluate(() => {
      const b = document.querySelector('#msg-area [data-msg="pads-wait"]');
      const w = b && b.querySelector('button[data-fix="board-wiped"]');
      return b ? { text: b.querySelector('.msg__t').textContent.replace(/\s+/g, ' ').trim(), fix: Array.from(b.querySelectorAll('button[data-fix]')).map((x) => x.dataset.fix),
        wipeEnabled: !!w && !w.disabled, wipeTitle: w ? w.title : null } : null;
    });
    check('(5) the band: "Process\'s pad calibration waits for clean pads: 10 clean, it needs 24." and Board wiped',
      !!band && band.text.startsWith('Process’s pad calibration waits for clean pads: 10 clean, it needs 24.') && /Wipe the board \(Opulo test board\), then press Board wiped\./.test(band.text)
      && band.fix.includes('board-wiped'), band);
    check('(5) Board wiped can be pressed under the waiting agent\'s lock (no Take control, no STOP)', !!band && band.wipeEnabled, band);
    const held = await page.evaluate(() => {
      const b = document.querySelector('#msg-area [data-msg="held"] small');
      const v = document.querySelector('#cell-lock .cell__v');
      return { sub: b ? b.textContent : null, cell: v ? v.textContent.replace(/\s+/g, ' ').trim() : null };
    });
    check('(5) the agent\'s band and the strip say it waits, and that Board wiped needs no Take control',
      /^It waits for clean pads: Board wiped \(below\) works without taking control\./.test(held.sub || '') && /^Process · waiting/.test(held.cell || ''), held);
    // a stale waiting record while the agent runs something again: it is working, and the band says nothing about the wipe
    ST.lock = Object.assign(AGENT(), { busy: true });
    await page.waitForFunction(() => { const v = document.querySelector('#cell-lock .cell__v'); return v && /working/.test(v.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const busy = await page.evaluate(() => {
      const b = document.querySelector('#msg-area [data-msg="held"] small');
      const v = document.querySelector('#cell-lock .cell__v');
      return { sub: b ? b.textContent : null, cell: v ? v.textContent.replace(/\s+/g, ' ').trim() : null };
    });
    check('(5) an agent busy again despite an old waiting record reads "working", without the wipe sentence',
      /^Process · working/.test(busy.cell || '') && !/Board wiped/.test(busy.sub || ''), busy);
    ST.lock = AGENT();
    await shot(page, 'clean-pads-band.png', false);
    L.waiting = null;
    const gone = await page.waitForFunction(() => !document.querySelector('#msg-area [data-msg="pads-wait"]'), null, { timeout: 8000 }).then(() => true, () => false);
    check('(5) the band goes when the run no longer waits', gone);
    await page.evaluate(() => IL.go('#calibrate/test-board'));
    await page.waitForFunction(() => { const b = document.getElementById('btn-tb-wiped'); return b && b.disabled; }, null, { timeout: 8000 }).catch(() => null);
    const c5 = await card(page);
    check('(5) once the agent works again, Board wiped is greyed: "Process is working: Take control to wipe now (stops its work)"',
      !!c5.btn && c5.btn.disabled && c5.btn.title === 'Process is working: Take control to wipe now (stops its work)', c5.btn);
    ST.lock = null;

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
