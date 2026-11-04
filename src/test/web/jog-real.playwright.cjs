/**
 * Machine › Control against the engine's own BR-49 + BR-53 routes on the simulator (jog.playwright.cjs stubs them to
 * reach the states a simulator cannot): (0) GET /api/cameras lists the cameras; (1) the camera card shows the top
 * camera from /api/cameras/top/frame.jpg, with its age; (2) a 1 mm camera jog in X moves the camera 1 mm; (3) the
 * needle up 1 mm and back down; (4) Move to a place (two presses) brings the camera over it, both nozzles at travel
 * height; (5) a STOP leaves the machine off: every control waits, saying why; no JavaScript errors, no native dialogs.
 * Nothing here runs a real machine.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (jog-real-results.json, jog-real-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-jog-real-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const UI = { 'X-IL-Client': 'ui', 'Content-Type': 'application/json' };
async function api(method, p, body) {
  const r = await fetch(BASE + p.replace(/^\//, ''), { method, headers: UI, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = { text }; }
  return { status: r.status, data };
}
/** The user gives the machine back (BR-72), if this OpenPnP has the lock. */
const giveBack = () => api('POST', '/api/lock/release', {}).catch(() => null);
const pos = (page) => page.evaluate(() => { const p = IL.positions(IL.S.status); return { cam: p.cam, needle: p.n2, n1: p.n1 }; });

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  const click = async (sel) => { await page.click(sel); await sleep(250); };
  try {
    // ---- (0) the cameras, and the machine started and homed --------------------------------------------------------------
    const cams = await api('GET', '/api/cameras');
    if (cams.status === 404) {
      // an OpenPnP before BR-49 + BR-53 (they land together): nothing to check here; jog.playwright.cjs stubs them
      console.log('skip jog-real: this OpenPnP has no /api/cameras (BR-49 + BR-53 not in it yet)');
      await browser.close();
      process.exit(0);
    }
    const list = cams.data && (cams.data.cameras || cams.data);
    check('(0) GET /api/cameras lists the top camera', cams.status === 200 && Array.isArray(list) && list.some((c) => c.id === 'top'), cams);
    const st0 = (await api('GET', '/api/status')).data || {};
    if (!st0.machine.enabled) { await api('POST', '/api/machine/enable', {}); await sleep(3000); }
    const st1 = (await api('GET', '/api/status')).data || {};
    if (!st1.machine.homed || st1.machine.positionKnown === false) await api('POST', '/api/machine/home', { wait: true, clearBelowAttested: true });
    await giveBack();
    await page.goto(BASE + '#machine');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.S.status.machine.homed && document.getElementById('jog-pad'), null, { timeout: 30000 });
    await sleep(1500);

    // ---- (1) the top camera through BR-53 ----------------------------------------------------------------------------------
    await page.waitForFunction(() => { const i = document.getElementById('cam-img'); return !!(i && i.complete && i.naturalWidth > 0 && !i.hidden); }, null, { timeout: 15000 }).catch(() => null);
    const c1 = await page.evaluate(() => ({ line: document.getElementById('cam-line').textContent, tag: document.getElementById('cam-tag').textContent }));
    check('(1) the top camera live from /api/cameras/top/frame.jpg, with its age', /top camera · live/.test(c1.tag) && / old/.test(c1.line), c1);

    // ---- (2) a 1 mm camera jog in X ------------------------------------------------------------------------------------------
    await click('#jog-tool button[data-tool="camera"]');
    await click('#jog-step button[data-mm="1"]');
    const p0 = await pos(page);
    await click('#jog-pad button[data-axis="x"][data-dir="+"]');
    await page.waitForFunction((x) => { const c = IL.positions(IL.S.status).cam; return c && Math.abs(c.x - (x + 1)) < 0.05; }, p0.cam.x, { timeout: 15000 }).catch(() => null);
    const p1 = await pos(page);
    check('(2) X+ 1 mm moves the camera 1 mm in X', Math.abs(p1.cam.x - p0.cam.x - 1) < 0.05 && Math.abs(p1.cam.y - p0.cam.y) < 0.05, { before: p0.cam, after: p1.cam });

    // ---- (3) the needle up 1 mm, then down again ----------------------------------------------------------------------------
    await click('#jog-tool button[data-tool="needle"]');
    const z0 = (await pos(page)).needle.z;
    await click('#jog-pad button[data-axis="z"][data-dir="+"]');
    await page.waitForFunction((z) => { const n = IL.positions(IL.S.status).n2; return n && Math.abs(n.z - (z + 1)) < 0.05; }, z0, { timeout: 15000 }).catch(() => null);
    const z1 = (await pos(page)).needle.z;
    check('(3) Z+ 1 mm raises the needle 1 mm', Math.abs(z1 - z0 - 1) < 0.05, { z0, z1 });
    await click('#jog-pad button[data-axis="z"][data-dir="-"]');
    await page.waitForFunction((z) => { const n = IL.positions(IL.S.status).n2; return n && Math.abs(n.z - z) < 0.05; }, z0, { timeout: 15000 }).catch(() => null);
    const z2 = (await pos(page)).needle.z;
    const ref3 = await page.evaluate(() => { const r = Array.from(document.querySelectorAll('#jog-panel .refusal')).pop(); return r ? r.textContent.replace(/\s+/g, ' ') : null; });
    check('(3) Z− 1 mm brings it back (or the engine says why not)', Math.abs(z2 - z0) < 0.05 || /needle|floor|safe/.test(ref3 || ''), { z0, z2, ref3 });

    // ---- (4) Move to a place ------------------------------------------------------------------------------------------------
    const here = (await pos(page)).cam;
    const place = { x: Number((here.x - 10).toFixed(2)), y: Number((here.y - 10).toFixed(2)), z: 10 };
    const put = await api('PUT', '/api/locations/jog-real-test', place);
    check('(4) a place to move to (PUT /api/locations/jog-real-test)', put.status === 200, put);
    await page.waitForFunction(() => Array.from(document.querySelectorAll('#jog-place option')).some((o) => o.value === 'jog-real-test'), null, { timeout: 10000 }).catch(() => null);
    await click('#jog-tool button[data-tool="camera"]');
    await page.selectOption('#jog-place', 'jog-real-test');
    await click('#jog-go');
    await sleep(350);
    await click('#jog-go');
    await page.waitForFunction((p) => { const c = IL.positions(IL.S.status).cam; return c && Math.hypot(c.x - p.x, c.y - p.y) < 0.2; }, place, { timeout: 30000 }).catch(() => null);
    const p4 = await pos(page);
    const low4 = await page.evaluate(() => IL.S.status.machine.home.n1Low);
    check('(4) Go (two presses) brings the camera over the place', Math.hypot(p4.cam.x - place.x, p4.cam.y - place.y) < 0.2, { place, cam: p4.cam });
    check('(4) and ends with both nozzles at travel height', low4 === false, low4);
    await page.screenshot({ path: path.join(OUT, 'jog-real.png') });

    // ---- (5) after a STOP: everything waits, saying why ----------------------------------------------------------------------
    await api('POST', '/api/stop');
    await page.waitForFunction(() => !IL.S.status.machine.enabled, null, { timeout: 15000 }).catch(() => null);
    await sleep(900);
    const p5 = await page.evaluate(() => Array.from(document.querySelectorAll('#jog-pad button[data-axis], #jog-go')).map((b) => ({ k: b.id || b.dataset.axis + b.dataset.dir, disabled: b.disabled, title: b.title })));
    check('(5) after a STOP every jog and Go waits, saying why', p5.every((b) => b.disabled && b.title.length > 0), p5);

    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'jog-real-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    try { await giveBack(); } catch (e) { /* the server may be gone */ }
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'jog-real-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
