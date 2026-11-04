/**
 * The mounted tip's own values (BR-85; Backend 11-04 03:5x: read-only in the UI, Process sets them through the API).
 * Until BR-85 lands its fields are stubbed here as Backend named them: `status.dispense.tip.doseAnchor` ({massPerPadMg,
 * areaMm2, mgPerMm2, padType, source, setAtMs, fromFamily}) and a touch-off's `referenceDotMm` / `referenceFrom` (in its
 * analysis). (1) Tip › On now: no row without the field; the tip's own anchor as "Paste per pad (target)" with its
 * number, pad, source and time; the family's says this tip has no number of its own yet; (2) Calibrate › the last
 * touch-off: "Normal dot 1.35 mm, from the tip's ladder of 14:07."; a touch-off without it shows no line; (3) no
 * JavaScript errors. Nothing moves.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (tip-values-results.json, tip-values.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-tip-values-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- the stub of BR-85 ------------------------------------------------------------------------------------------------
// mid-minute: the page subtracts the server clock's skew, so a time on the minute could show the minute before
const today = (h, m) => { const d = new Date(); d.setHours(h, m, 30, 0); return Math.min(d.getTime(), Date.now()); };
const OWN = { massPerPadMg: 1.24, areaMm2: 0.73, mgPerMm2: 1.699, padType: '0603', source: 'weighed by Process', setAtMs: today(0, 10), fromFamily: false };
const FAMILY = { massPerPadMg: 1.056, areaMm2: 0.73, mgPerMm2: 1.447, padType: '0603', source: 'the 0603 bench anchor', setAtMs: null, fromFamily: true };
const ST = { anchor: undefined, touchOff: null };
function touchOffRow(ref) {
  const analysis = { contactGapMm: 0.05, gapsMm: [0.3, 0.2, 0.1, 0.05, 0], diametersMm: [1.31, 1.33, 1.36, 1.12, 0.6], dotStates: ['ok', 'ok', 'ok', 'ok', 'ok'],
    bracketHighMm: 0.1, bracketLowMm: 0.05 };
  if (ref) Object.assign(analysis, { referenceDotMm: 1.35, referenceFrom: 'the tip’s ladder of 14:07' });
  return { id: 990001 + (ref ? 1 : 0), created_at: Date.now() - 60000, method: 'dot-ladder-touch-off', z_offset_mm: -2.6, previous_offset_mm: -2.59,
    details: JSON.stringify({ analysis }) };
}
async function stubRoutes(context) {
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    if (url.pathname === '/api/status' && req.method() === 'GET') {
      const resp = await route.fetch();
      const body = await resp.json();
      const tip = (body.dispense && body.dispense.tip) || {};
      if (ST.anchor === undefined) delete tip.doseAnchor; else tip.doseAnchor = ST.anchor;
      if (body.dispense) body.dispense.tip = tip;
      return route.fulfill({ response: resp, json: body });
    }
    if (url.pathname === '/api/calibrations' && req.method() === 'GET' && ST.touchOff) {
      const resp = await route.fetch();
      const body = await resp.json();
      body.touchOffs = [ST.touchOff].concat(body.touchOffs || []);
      return route.fulfill({ response: resp, json: body });
    }
    return route.continue();
  });
}
const anchorRow = (page) => page.evaluate(() => {
  const tr = document.querySelector('#tipnow-evidence tr[data-k="anchor"]');
  return tr ? Array.from(tr.cells).map((c) => c.textContent.replace(/\s+/g, ' ').trim()) : null;
});
const waitRow = (page, re) => page.waitForFunction((src) => {
  const tr = document.querySelector('#tipnow-evidence tr[data-k="anchor"]');
  return src === null ? !tr : !!tr && new RegExp(src).test(tr.textContent);
}, re ? re.source : null, { timeout: 8000 }).catch(() => null);

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ extraHTTPHeaders: { 'X-IL-Client': 'ui' }, viewport: { width: 1440, height: 900 } });
  await stubRoutes(context);
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  try {
    await page.goto(BASE + '#tip');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && document.querySelector('#tipnow-evidence tr[data-k="dose"]'), null, { timeout: 30000 });

    // ---- (1) Tip › On now -------------------------------------------------------------------------------------------
    await waitRow(page, null);
    check('(1) before BR-85 (no doseAnchor in the status): no "Paste per pad" row', (await anchorRow(page)) === null);
    ST.anchor = OWN;
    await waitRow(page, /weighed by Process/);
    const own = await anchorRow(page);
    const at = new Date(OWN.setAtMs).toTimeString().slice(0, 5);
    check('(1) the tip\'s own anchor: "Paste per pad (target) · 1.24 mg per pad (0603, 0.73 mm²) · weighed by Process, hh:mm"',
      !!own && own[0] === 'Paste per pad (target)' && own[1] === '1.24 mg per pad (0603, 0.73 mm²)' && own[2] === 'weighed by Process, ' + at
      && own[3] === 'this tip is weighed again', { own, at });
    await page.screenshot({ path: path.join(OUT, 'tip-values.png') });
    ST.anchor = FAMILY;
    await waitRow(page, /no number of its own/);
    const fam = await anchorRow(page);
    check('(1) the family\'s anchor: its number, and that this tip has no number of its own yet',
      !!fam && fam[1] === '1.056 mg per pad (0603, 0.73 mm²)' && fam[2] === 'the 0603 bench anchor: this tip has no number of its own yet', fam);

    // ---- (2) Calibrate › the last touch-off ---------------------------------------------------------------------------
    ST.touchOff = touchOffRow(true);
    await page.evaluate(() => IL.go('#calibrate/touch-off'));
    await page.waitForSelector('#to-sum', { timeout: 15000 });
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.waitForFunction(() => document.getElementById('to-ref'), null, { timeout: 8000 }).catch(() => null);
    const ref = await page.evaluate(() => { const n = document.getElementById('to-ref'); return n ? n.textContent : null; });
    check('(2) the touch-off says what it compared the dots with: "Normal dot 1.35 mm, from the tip\'s ladder of 14:07."',
      ref === 'Normal dot 1.35 mm, from the tip’s ladder of 14:07.', ref);
    ST.touchOff = touchOffRow(false);
    await page.evaluate(() => IL.pcr.loadCalibrations());
    const gone = await page.waitForFunction(() => !document.getElementById('to-ref') && document.getElementById('to-sum'), null, { timeout: 8000 }).then(() => true, () => false);
    check('(2) a touch-off without it shows no such line', gone);

    check('(3) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
  } catch (e) {
    check('test ran to the end', false, String((e && e.stack) || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'tip-values-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    await browser.close();
    const passed = results.filter((r) => r.ok).length;
    fs.writeFileSync(path.join(OUT, 'tip-values-results.json'), JSON.stringify({ passed, failed: results.length - passed, ms: Date.now() - t0, results }, null, 2));
    console.log(passed + ' passed, ' + (results.length - passed) + ' failed (' + Math.round((Date.now() - t0) / 1000) + ' s)');
    process.exit(results.length && passed === results.length ? 0 : 1);
  }
})();
