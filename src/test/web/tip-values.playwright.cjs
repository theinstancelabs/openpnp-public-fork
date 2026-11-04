/**
 * The mounted tip's own values (BR-85; Backend 11-04 03:5x: read-only in the UI, Process sets them through the API).
 * Until BR-85 lands its fields are stubbed here as Backend named them: `status.dispense.tip.doseAnchor` ({massPerPadMg,
 * areaMm2, mgPerMm2, padType: a pad type's key, source, setAtMs, fromFamily}) and a touch-off's `referenceFrom` beside
 * the `referenceDiameterMm` its analysis has today (Backend 11-04 04:1x), and #37's `profile.touchOffSkipDots` and
 * `analysis.skippedDots`. (1) Tip › On now: no row without the field, with a null one or one without a number; the
 * tip's own anchor as "Paste per pad (target)" with its number, the pad type's name, source and time; the family's says
 * this tip has no number of its own yet; (2) Calibrate › the last touch-off: "Normal dot 1.35 mm, from the tip's ladder
 * of 14:07."; one from before BR-85 (no referenceFrom) says only the size; one without a reference shows no line; one
 * that left dots out after the prime says so; (2b) the touch-off's Steps follow the mounted tip's recipe: at least its
 * touchOffSkipDots + 3 (the engine refuses a shorter ladder), refused at the field with why; (3) no JavaScript errors.
 * Nothing moves.
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
// OWN's padType becomes a live pad type's key once the page has the list (its name shows); FAMILY's matches no type
const OWN = { massPerPadMg: 1.24, areaMm2: 0.73, mgPerMm2: 1.699, padType: null, source: 'weighed by Process', setAtMs: today(0, 10), fromFamily: false };
const FAMILY = { massPerPadMg: 1.056, areaMm2: 0.73, mgPerMm2: 1.447, padType: 'no-such-type', source: 'the 0603 bench anchor', setAtMs: null, fromFamily: true };
const ST = { anchor: undefined, touchOff: null, skip: undefined };
function touchOffRow(ref, from, skipped) {
  const analysis = { contactGapMm: 0.05, gapsMm: [0.3, 0.2, 0.1, 0.05, 0], diametersMm: [1.31, 1.33, 1.36, 1.12, 0.6], dotStates: ['ok', 'ok', 'ok', 'ok', 'ok'],
    bracketHighMm: 0.1, bracketLowMm: 0.05 };
  if (ref) analysis.referenceDiameterMm = 1.35;
  if (from) analysis.referenceFrom = 'the tip\u2019s ladder of 14:07';
  if (skipped !== undefined) analysis.skippedDots = skipped;
  return { id: 990001 + (ref ? 1 : 0) + (from ? 2 : 0), created_at: Date.now() - 60000, method: 'dot-ladder-touch-off', z_offset_mm: -2.6, previous_offset_mm: -2.59,
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
      // BR-85: the dots the mounted tip's recipe leaves out after the prime, before the touch-off's reference pair
      if (ST.skip !== undefined && body.dispense) body.dispense.profile = Object.assign({}, body.dispense.profile, { touchOffSkipDots: ST.skip });
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
    // each case is read after two status polls that carry it (the page polls every 0.7 s)
    const settled = async () => { const s0 = await page.evaluate(() => IL.S.statusAt); await page.waitForFunction((x) => IL.S.statusAt > x + 1000, s0, { timeout: 8000 }).catch(() => null); };
    await settled();
    check('(1) before BR-85 (no doseAnchor in the status): no "Paste per pad" row', (await anchorRow(page)) === null);
    ST.anchor = null;
    await settled();
    check('(1) a null doseAnchor: no row', (await anchorRow(page)) === null);
    ST.anchor = { massPerPadMg: null, areaMm2: 0.73, padType: '0603', source: null, setAtMs: null, fromFamily: true };
    await settled();
    check('(1) an anchor without a number: no row (never "0 mg" or "NaN mg")', (await anchorRow(page)) === null);
    // a live pad type's key: the row names the type (the page loads the pad types itself when it is the first to need them)
    const pt = await page.evaluate(() => IL.api('GET', '/api/pad-types').then((d) => {
      const t = (d.families || []).filter((f) => !f.retired).flatMap((f) => f.types).find((x) => !x.retired);
      return t ? { key: t.typeKey, name: t.name } : null;
    }));
    OWN.padType = pt ? pt.key : 'no-such-type';
    const label = pt ? pt.name : 'no-such-type';
    ST.anchor = OWN;
    await waitRow(page, /weighed by Process/);
    await page.waitForFunction((x) => { const tr = document.querySelector('#tipnow-evidence tr[data-k="anchor"]'); return tr && tr.cells[1].textContent.includes('(' + x + ','); }, label, { timeout: 8000 }).catch(() => null);
    const own = await anchorRow(page);
    const at = new Date(OWN.setAtMs).toTimeString().slice(0, 5);
    check('(1) the tip\'s own anchor: "Paste per pad (target) · 1.24 mg per pad (<the type\'s name>, 0.73 mm²) · weighed by Process, hh:mm"',
      !!own && own[0] === 'Paste per pad (target)' && own[1] === '1.24 mg per pad (' + label + ', 0.73 mm²)' && own[2] === 'weighed by Process, ' + at
      && own[3] === 'this tip is weighed again', { own, at, pt });
    await page.screenshot({ path: path.join(OUT, 'tip-values.png') });
    ST.anchor = FAMILY;
    await waitRow(page, /no number of its own/);
    const fam = await anchorRow(page);
    check('(1) the family\'s anchor: its number, and that this tip has no number of its own yet',
      !!fam && fam[1] === '1.056 mg per pad (no-such-type, 0.73 mm²)' && fam[2] === 'the 0603 bench anchor: this tip has no number of its own yet'
      && fam[3] === 'this tip is weighed, or the shared number changes', fam);

    // ---- (2) Calibrate › the last touch-off ---------------------------------------------------------------------------
    ST.touchOff = touchOffRow(true, true);
    await page.evaluate(() => IL.go('#calibrate/touch-off'));
    await page.waitForSelector('#to-sum', { timeout: 15000 });
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.waitForFunction(() => document.getElementById('to-ref'), null, { timeout: 8000 }).catch(() => null);
    const ref = await page.evaluate(() => { const n = document.getElementById('to-ref'); return n ? n.textContent : null; });
    check('(2) the touch-off says what it compared the dots with: "Normal dot 1.35 mm, from the tip\'s ladder of 14:07."',
      ref === 'Normal dot 1.35 mm, from the tip’s ladder of 14:07.', ref);
    ST.touchOff = touchOffRow(true, false);
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.waitForFunction(() => { const n = document.getElementById('to-ref'); return n && !/from/.test(n.textContent); }, null, { timeout: 8000 }).catch(() => null);
    const old = await page.evaluate(() => { const n = document.getElementById('to-ref'); return n ? n.textContent : null; });
    check('(2) a touch-off from before BR-85 (no referenceFrom) says only the size: "Normal dot 1.35 mm."', old === 'Normal dot 1.35 mm.', old);
    ST.touchOff = touchOffRow(false, false);
    await page.evaluate(() => IL.pcr.loadCalibrations());
    const gone = await page.waitForFunction(() => !document.getElementById('to-ref') && document.getElementById('to-sum'), null, { timeout: 8000 }).then(() => true, () => false);
    check('(2) a touch-off without a reference shows no such line', gone);
    ST.touchOff = touchOffRow(true, true, 2);
    await page.evaluate(() => IL.pcr.loadCalibrations());
    await page.waitForFunction(() => /left out/.test((document.getElementById('to-ref') || {}).textContent || ''), null, { timeout: 8000 }).catch(() => null);
    const skipped = await page.evaluate(() => { const n = document.getElementById('to-ref'); return n ? n.textContent : null; });
    check('(2) a touch-off that left dots out says so with its reference (analysis.skippedDots)',
      skipped === 'Normal dot 1.35 mm, from the tip’s ladder of 14:07. The first 2 dots after the prime were left out.', skipped);

    // ---- (2b) the touch-off's smallest ladder follows the mounted tip's recipe (BR-85: skip + the reference pair + one) --
    const stepsField = () => page.evaluate(() => ({ unit: (document.querySelector('#to-steps-wrap .unit') || {}).textContent || null,
      min: document.getElementById('to-steps').getAttribute('min'), err: (document.getElementById('to-steps-err') || {}).hidden ? '' : document.getElementById('to-steps-err').textContent }));
    ST.skip = 2;
    await page.waitForFunction(() => /^5 to 20$/.test((document.querySelector('#to-steps-wrap .unit') || {}).textContent || ''), null, { timeout: 8000 }).catch(() => null);
    await page.fill('#to-steps', '4');
    await page.waitForFunction(() => !document.getElementById('to-steps-err').hidden, null, { timeout: 5000 }).catch(() => null);
    const f4 = await stepsField();
    check('(2b) a recipe that leaves out 2 dots: Steps "5 to 20", and 4 is refused at the field with why', f4.unit === '5 to 20' && f4.min === '5'
      && f4.err === 'At least 5: the tip’s recipe leaves out the first 2 dots after the prime, the reference takes the next two, and one more shows the contact.', f4);
    await page.fill('#to-steps', '5');
    await page.waitForFunction(() => document.getElementById('to-steps-err').hidden, null, { timeout: 5000 }).catch(() => null);
    check('(2b) 5 steps pass', (await stepsField()).err === '', await stepsField());
    ST.skip = 0;
    await page.waitForFunction(() => /^3 to 20$/.test((document.querySelector('#to-steps-wrap .unit') || {}).textContent || ''), null, { timeout: 8000 }).catch(() => null);
    await page.fill('#to-steps', '2');
    await page.waitForFunction(() => !document.getElementById('to-steps-err').hidden, null, { timeout: 5000 }).catch(() => null);
    const f2 = await stepsField();
    check('(2b) no dots left out: Steps "3 to 20", and 2 is refused (the reference pair and one more)', f2.unit === '3 to 20' && f2.min === '3'
      && f2.err === 'At least 3: the reference takes the first two dots after the prime, and one more shows the contact.', f2);
    await page.fill('#to-steps', '6');
    ST.skip = undefined;

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
