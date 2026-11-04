/**
 * Machine › Control: jog and move to a place from the page (the approved mockup `machine`; the user, 11-02: run the
 * machine by hand from the UI; the P0 of 11-03: control the real machine from the UI at any time). The API is
 * Backend's BR-49 + BR-53 (docs/backend-requests.md, "BR-49 + BR-53 API"): POST /api/jog {tool: camera|n1|needle, dx,
 * dy, dz} (at most 10 mm in X/Y and 2 mm in Z a press; the camera has no Z), POST /api/move-to {tool, place} (at safe
 * Z, never lower), GET /api/cameras and /api/cameras/{id}/frame.jpg. Until they land they are answered here (nothing
 * moves):
 * (1) the jog panel: the tool, the step, the arrows, Z, the places, the head's position live; (2) an arrow sends one jog
 * of the step for the tool chosen; Z sends at most 2 mm, and never for the camera; (3) Move to a place asks twice and
 * sends the place; (4) before a home nothing moves (on the see-saw a raise lowers the other nozzle), each locked
 * control says why; with a nozzle out of its safe zone X/Y and Go wait ("Raise N1 first"), Z stays free; (5) a refusal
 * (409) is shown as given; (6) an OpenPnP without BR-49 (404) says so instead of failing; (7) the camera card: without BR-53 the top
 * camera live through GET /api/camera/top.jpg, Bottom saying what it waits for; (8) with BR-53 (stubbed) the bottom
 * camera too; no JavaScript errors, no native dialogs.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (jog-results.json, jog-*.png).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-jog-test-out';
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// the stub: the machine homed (or not), BR-49 there (or not), and what the page sent
const stub = { homed: true, low: null, missing: false, refuse: null, br53: false, jogs: [], moves: [], frames: [] };
const CAMS = [{ id: 'top', name: 'Top', looking: 'down', width: 1920, height: 1080 }, { id: 'bottom', name: 'Bottom', looking: 'up', width: 1280, height: 720 }];
(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    let body = null;
    try { body = req.postDataJSON(); } catch (e) { body = null; }
    if (url.pathname === '/api/status' && method === 'GET') {
      const resp = await route.fetch();
      const s = await resp.json();
      s.machine = Object.assign({}, s.machine, { enabled: true, homed: stub.homed, busy: false });
      s.machine.home = Object.assign({}, s.machine.home, { n1Low: !!stub.low, lowTool: stub.low });
      // places to move to, when the session has none stored (a fresh SIM session)
      const d = s.dispense = s.dispense || {};
      if (!d.locations || !Object.keys(d.locations).length) {
        d.wasteLocationName = d.wasteLocationName || 'waste';
        d.blankLocationName = d.blankLocationName || 'blank';
        d.locations = { [d.wasteLocationName]: { x: 262.0, y: 268.0, z: 10.0 }, [d.blankLocationName]: { x: 285.0, y: 262.0, z: 10.0 } };
      }
      if (s.run) s.run.running = false;
      s.pressure = Object.assign({}, s.pressure, { state: 'off' });
      return route.fulfill({ response: resp, json: s });
    }
    if ((url.pathname === '/api/jog' || url.pathname === '/api/move-to') && method === 'POST') {
      (url.pathname === '/api/jog' ? stub.jogs : stub.moves).push(body || {});
      if (stub.missing) return route.fulfill({ status: 404, json: { error: 'not-found', reason: 'no route ' + url.pathname } });
      if (stub.refuse) return route.fulfill({ status: stub.refuse.status, json: { error: 'refused', reason: stub.refuse.reason } });
      return route.fulfill({ json: { ok: true } });
    }
    if (url.pathname === '/api/cameras' && method === 'GET') return stub.br53 ? route.fulfill({ json: CAMS }) : route.fulfill({ status: 404, json: { error: 'not-found' } });
    const fm = /^\/api\/cameras\/(\w+)\/frame\.jpg$/.exec(url.pathname);
    if (fm && method === 'GET') {
      stub.frames.push(fm[1]);
      if (!stub.br53) return route.fulfill({ status: 404, json: { error: 'not-found' } });
      // the simulator's own top picture stands in for every camera's frame
      const resp = await route.fetch({ url: BASE + 'api/camera/top.jpg?w=400' });
      // BR-53's capture time: this frame 5 s old (a stalled capture must read old)
      return route.fulfill({ response: resp, headers: Object.assign({}, resp.headers(), { 'x-frame-at': String(Date.now() - 5000) }) });
    }
    if (method !== 'GET') return route.fulfill({ status: 409, json: { error: 'refused', reason: 'test stub: ' + method + ' ' + url.pathname + ' is not sent from this test' } });
    return route.continue();
  });
  const page = await context.newPage();
  const jsErrors = [], dialogs = [];
  page.on('pageerror', (e) => jsErrors.push(String((e && e.stack) || e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  const click = async (sel) => { await page.click(sel); await sleep(250); };
  try {
    await page.goto(BASE + '#machine');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && document.getElementById('jog-pad'), null, { timeout: 30000 });
    await sleep(1200);

    // ---- (1) the panel ---------------------------------------------------------------------------------------------------
    const p1 = await page.evaluate(() => ({
      tools: Array.from(document.querySelectorAll('#jog-tool button')).map((b) => [b.dataset.tool, b.textContent.trim(), b.getAttribute('aria-pressed')]),
      steps: Array.from(document.querySelectorAll('#jog-step button')).map((b) => [b.dataset.mm, b.getAttribute('aria-pressed')]),
      arrows: Array.from(document.querySelectorAll('#jog-pad button[data-axis]')).map((b) => b.dataset.axis + b.dataset.dir),
      places: Array.from(document.querySelectorAll('#jog-place option')).map((o) => o.value),
      head: Array.from(document.querySelectorAll('#jog-head tbody tr')).map((tr) => tr.textContent.replace(/\s+/g, ' ').trim()),
    }));
    check('(1) the tools: Camera, Needle, Nozzle; Camera first', JSON.stringify(p1.tools.map((t) => t[0])) === '["camera","needle","n1"]' && p1.tools[0][2] === 'true', p1.tools);
    check('(1) the step: 0.1, 1 and 10 mm; 1 mm first', JSON.stringify(p1.steps.map((t) => t[0])) === '["0.1","1","10"]' && p1.steps[1][1] === 'true', p1.steps);
    check('(1) four arrows and Z up and down', ['x+', 'x-', 'y+', 'y-', 'z+', 'z-'].every((a) => p1.arrows.includes(a)), p1.arrows);
    check('(1) the places to move to include the waste', p1.places.length > 0 && p1.places.some((p) => /waste/i.test(p)), p1.places);
    check('(1) the head\'s position: camera, needle and nozzle, live', p1.head.length === 3 && p1.head.every((r) => /\d+\.\d\d/.test(r)), p1.head);
    await page.screenshot({ path: path.join(OUT, 'jog-panel.png') });

    // ---- (2) jog -------------------------------------------------------------------------------------------------------------
    await click('#jog-pad button[data-axis="x"][data-dir="+"]');
    check('(2) X+ sends one jog of 1 mm for the camera, X alone', stub.jogs.length === 1 && stub.jogs[0].tool === 'camera' && stub.jogs[0].dx === 1
      && JSON.stringify(Object.keys(stub.jogs[0]).sort()) === '["dx","tool"]', stub.jogs);
    await click('#jog-step button[data-mm="0.1"]');
    const camZ = await page.evaluate(() => { const b = document.querySelector('#jog-pad button[data-axis="z"][data-dir="+"]'); return { disabled: b.disabled, title: b.title }; });
    check('(2) the camera has no Z: its Z buttons say so', camZ.disabled && /camera has no Z/.test(camZ.title), camZ);
    await click('#jog-tool button[data-tool="needle"]');
    await click('#jog-pad button[data-axis="y"][data-dir="-"]');
    check('(2) Needle, 0.1 mm, Y−: one jog of −0.1 mm in Y for the needle', stub.jogs.length === 2 && stub.jogs[1].tool === 'needle' && stub.jogs[1].dy === -0.1 && !stub.jogs[1].dx, stub.jogs[1]);
    await click('#jog-step button[data-mm="10"]');
    await click('#jog-pad button[data-axis="z"][data-dir="+"]');
    check('(2) Z moves at most 2 mm a press, even at the 10 mm step, Z alone', stub.jogs.length === 3 && stub.jogs[2].dz === 2
      && JSON.stringify(Object.keys(stub.jogs[2]).sort()) === '["dz","tool"]', stub.jogs[2]);
    await click('#jog-tool button[data-tool="camera"]');
    await click('#jog-step button[data-mm="1"]');

    // ---- (3) move to a place -------------------------------------------------------------------------------------------------
    const wasteName = p1.places.find((p) => /waste/i.test(p));
    await page.selectOption('#jog-place', wasteName);
    await click('#jog-go');
    check('(3) Go asks again first: nothing sent yet', stub.moves.length === 0 && /Press again/.test(await page.textContent('#jog-go')), await page.textContent('#jog-go'));
    await sleep(300);
    await click('#jog-go');
    check('(3) the second press moves the camera to the place', stub.moves.length === 1 && stub.moves[0].tool === 'camera' && stub.moves[0].place === wasteName, stub.moves);

    // ---- (4) before a home: only Z up --------------------------------------------------------------------------------------------
    await click('#jog-tool button[data-tool="needle"]');
    stub.homed = false;
    await page.waitForFunction(() => IL.S.status.machine.homed === false, null, { timeout: 5000 });
    await sleep(900);
    const p4 = await page.evaluate(() => Array.from(document.querySelectorAll('#jog-pad button[data-axis], #jog-go')).map((b) => ({ k: b.id || b.dataset.axis + b.dataset.dir, disabled: b.disabled, title: b.title })));
    check('(4) not homed: nothing moves, not even Z up (the see-saw), each saying so', p4.every((b) => b.disabled && /home/i.test(b.title)), p4);
    const why4 = await page.evaluate(() => { const w = document.getElementById('jog-why'); return w && !w.hidden ? w.textContent.trim() : null; });
    check('(4) and the panel says why in words', /home/i.test(why4 || ''), why4);
    stub.homed = true;
    await page.waitForFunction(() => IL.S.status.machine.homed === true, null, { timeout: 5000 });
    await sleep(900);
    // N1 out of its safe zone: X/Y and Go wait for it to be raised, Z stays free
    stub.low = 'N1';
    await page.waitForFunction(() => IL.S.status.machine.home.n1Low === true, null, { timeout: 5000 });
    await sleep(900);
    const p4b = await page.evaluate(() => Array.from(document.querySelectorAll('#jog-pad button[data-axis], #jog-go')).map((b) => ({ k: b.id || b.dataset.axis + b.dataset.dir, disabled: b.disabled, title: b.title })));
    check('(4) N1 low: X, Y and Go wait, "Raise N1 first"', p4b.filter((b) => !/^z/.test(b.k)).every((b) => b.disabled && /^Raise N1 first/.test(b.title)), p4b);
    check('(4) N1 low: Z stays free (raise it)', p4b.filter((b) => /^z/.test(b.k)).every((b) => !b.disabled), p4b);
    stub.low = null;
    await page.waitForFunction(() => IL.S.status.machine.home.n1Low === false, null, { timeout: 5000 });
    await sleep(900);

    // ---- (5) a refusal, as given -------------------------------------------------------------------------------------------------
    stub.refuse = { status: 409, reason: 'the needle would go below its frame floor over the board (Z 12.40): raise it first' };
    await click('#jog-pad button[data-axis="z"][data-dir="-"]');
    await page.waitForSelector('#jog-panel .refusal', { timeout: 5000 }).catch(() => null);
    const ref = await page.evaluate(() => { const r = document.querySelector('#jog-panel .refusal'); return r ? r.textContent.replace(/\s+/g, ' ') : null; });
    check('(5) a refused jog shows the engine\'s reason', /below its frame floor over the board/.test(ref || ''), ref);
    stub.refuse = null;

    // ---- (6) an OpenPnP without BR-49 ----------------------------------------------------------------------------------------------
    stub.missing = true;
    await click('#jog-pad button[data-axis="x"][data-dir="-"]');
    await page.waitForFunction(() => { const n = document.getElementById('jog-missing'); return n && !n.hidden; }, null, { timeout: 5000 }).catch(() => null);
    const miss = await page.evaluate(() => { const n = document.getElementById('jog-missing'); return n && !n.hidden ? n.textContent.trim() : null; });
    check('(6) an OpenPnP without jogging says so plainly', /not in this OpenPnP yet/i.test(miss || ''), miss);
    stub.missing = false;

    // ---- (7) the camera card without BR-53: the top camera through OpenPnP's own picture ------------------------------------
    await page.waitForFunction(() => { const i = document.getElementById('cam-img'); return !!(i && i.complete && i.naturalWidth > 0 && !i.hidden); }, null, { timeout: 15000 }).catch(() => null);
    const c7 = await page.evaluate(() => ({ img: (() => { const i = document.getElementById('cam-img'); return !!(i && i.complete && i.naturalWidth > 0 && !i.hidden); })(),
      tag: document.getElementById('cam-tag').textContent, line: document.getElementById('cam-line').textContent,
      pick: Array.from(document.querySelectorAll('#cam-pick button')).map((b) => [b.dataset.cam, b.getAttribute('aria-pressed')]) }));
    check('(7) the camera card shows the top camera live first', c7.img && /top camera · live/.test(c7.tag) && c7.pick[0][1] === 'true', c7);
    check('(7) its line says how old the picture is and whose it is', / old/.test(c7.line) && /OpenPnP.s picture of the top camera/.test(c7.line), c7.line);
    await page.screenshot({ path: path.join(OUT, 'jog-camera.png') });
    await click('#cam-pick button[data-cam="bottom"]');
    await sleep(400);
    const c7b = await page.evaluate(() => { const e = document.getElementById('cam-empty'); return e && !e.hidden ? e.textContent : null; });
    check('(7) Bottom without BR-53 says what it waits for', /Backend.s BR-53/.test(c7b || '') && /top camera works now/.test(c7b || ''), c7b);
    await click('#cam-pick button[data-cam="top"]');

    // ---- (8) with BR-53's camera service: every camera, and who holds it ------------------------------------------------------
    stub.br53 = true;
    const page8 = await context.newPage();
    page8.on('pageerror', (e) => jsErrors.push('page8: ' + String((e && e.stack) || e)));
    await page8.goto(BASE + '#machine');
    await page8.waitForFunction(() => window.IL && document.getElementById('cam-pick'), null, { timeout: 30000 });
    await page8.click('#cam-pick button[data-cam="bottom"]');
    await page8.waitForFunction(() => { const i = document.getElementById('cam-img'); return !!(i && i.complete && i.naturalWidth > 0 && !i.hidden); }, null, { timeout: 15000 }).catch(() => null);
    const c8 = await page8.evaluate(() => ({ tag: document.getElementById('cam-tag').textContent, line: document.getElementById('cam-line').textContent }));
    check('(8) with BR-53 the bottom camera shows, read from /api/cameras/bottom/frame.jpg', stub.frames.includes('bottom') && /bottom camera · live/.test(c8.tag), { frames: stub.frames.slice(-4), c8 });
    check('(8) and its line says whose picture it is', /OpenPnP.s picture of the bottom camera/.test(c8.line), c8.line);
    check('(8) the age is the frame\'s own (X-Frame-At: 5 s ago), not when it arrived', /\b[5-7](\.\d)? s old/.test(c8.line), c8.line);
    await page8.close();
    stub.br53 = false;

    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'jog-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'jog-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
