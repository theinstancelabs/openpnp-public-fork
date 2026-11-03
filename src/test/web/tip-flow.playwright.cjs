/**
 * The Tip flow on the simulator (docs/ui-flows/tip-setup-flow.md, mockups docs/ui-flows/mockups/, the user's OK of
 * 11-02): (1) the frame of the agreed mockups: the menu Run, Designs, Tip, Machine, Records, "LumenPnP" top left, the
 * strip with Machine, Pressure, Tip, Session and no Start angle or Purge row; today's Setup, Calibrate and pad types
 * reachable under Machine › By hand; (2) Tip › On now: the mounted tip and every setting with how and when it was
 * measured and what makes it out of date, the height naming its source; (3) Which tip: the kinds with their pictures,
 * the kind on now first as a fresh tip, the path per kind; Park for the swap switches the pressure off; (4) Swap: It's
 * on mounts the chosen kind (a new needle token); the gauge reading is kept with that needle; (5) Checks: the steps in
 * the flow's order, each done, by hand or not built yet; Start the checks takes the picture from below and runs the
 * landing check on the SIM; (6) On now shows where it lands with its time; no JavaScript errors, no native dialogs.
 * Runs after tip-setups.playwright.cjs in the same session (the tips it set up). Nothing here runs a real machine.
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
const texts = (page, sel) => page.evaluate((s) => Array.from(document.querySelectorAll(s)).map((n) => n.textContent.trim()), sel);

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
    let stopPosts = 0;
    await page.route('**/api/pressure/stop', (r) => { stopPosts++; return r.continue(); });
    await page.click('#btn-tip-park');
    await page.waitForFunction(() => location.hash.startsWith('#tip/swap'), null, { timeout: 10000 }).catch(() => null);
    await page.unroute('**/api/pressure/stop');
    const pr3 = (await status(page)).pressure || {};
    check('(3) Park for the swap: the pressure off, then Swap', (await page.evaluate(() => location.hash)).startsWith('#tip/swap') && (stopPosts > 0 || !pr3.on),
      { hash: await page.evaluate(() => location.hash), stopPosts, pressure: pr3.state || pr3.on });

    // ---- (4) Swap and the gauge ---------------------------------------------------------------------------------------
    const rail4 = await page.evaluate(() => Array.from(document.querySelectorAll('#tip-rail .step')).map((s) => s.className));
    check('(4) the rail: Which tip done, Swap now', rail4.length === 7 && /step--done/.test(rail4[0]) && /step--(now|need)/.test(rail4[1]), rail4);
    let mountUrl = null;
    await page.route('**/api/tip-setups/*/mount', (r) => { mountUrl = r.request().url(); return r.continue(); });
    await page.click('#btn-tip-on');
    await page.waitForFunction((k) => IL.api('GET', '/api/status').then((s) => s.dispense.tip && s.dispense.tip.key === k), other.key, { timeout: 30000, polling: 500 }).catch(() => null);
    await page.unroute('**/api/tip-setups/*/mount');
    const st4 = await status(page);
    check('(4) It\'s on mounts the chosen kind, under a new needle token', !!mountUrl && mountUrl.includes('/api/tip-setups/' + encodeURIComponent(other.key) + '/mount')
      && st4.dispense.tip.key === other.key && st4.dispense.needle.changedAtMs !== st0.dispense.needle.changedAtMs, { mountUrl, tip: st4.dispense.tip.key });
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
    const a0 = st0.dispense.needle.anchor;
    check('(5) the height is by hand first, with a link to the needle card', (steps5.find((x) => x.k === 'height') || {}).s === 'hand' && !!(await page.$('#tip-height-hand')), steps5);
    if (a0) await page.evaluate(([n1, n2, t]) => IL.api('POST', '/api/needle/anchor', { surfaceZ: n1, needleContactZ: n2, note: 'tip-flow test: both tips, by hand', needleChangedAtMs: t }),
      [a0.surfaceZMm, a0.needleContactZMm, st4.dispense.needle.changedAtMs]);
    await page.waitForFunction(() => { const l = document.querySelector('#tip-checks li[data-k="height"]'); return l && l.dataset.state === 'done'; }, null, { timeout: 10000 }).catch(() => null);
    check('(5) after the frame by hand, the height is done', (await page.evaluate(() => (document.querySelector('#tip-checks li[data-k="height"]') || {}).dataset.state)) === 'done', a0);
    // the checks lay dots: the paste question of the session (same barrel or new) comes first when nobody answered it
    if (await page.$('#tip-barrel-same')) await page.click('#tip-barrel-same');
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
    await page.waitForSelector('#tipnow-evidence tr[data-k="lands"]', { timeout: 10000 }).catch(() => null);
    const lands6 = await page.evaluate(() => { const r = document.querySelector('#tipnow-evidence tr[data-k="lands"]'); return r ? r.querySelector('.how').textContent.trim() : null; });
    check('(6) On now: where it lands, with the time it was checked', /\d\d:\d\d/.test(lands6 || ''), lands6);
    check('(6) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('(6) no native dialogs', dialogs.length === 0, dialogs);
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
