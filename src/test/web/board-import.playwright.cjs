/*
 * Board flow on a SIM session (docs/instancelabs/BOARD_IMPORT_DESIGN.md §6.6 b): the KiCad upload dialog, the board
 * drawing under the Run hero, the path preview, the pad inspector, "Camera is on FID1", attach, the Records map, the
 * layout at 1280 and 1024 px and the dark theme. Run by run-webui-sim-test.sh right after webui-sim.playwright.cjs in
 * the same session (its own preconditions: a pushed Recovery panel is closed, then Run › Board). Nothing here moves
 * the machine: it plans, previews and reads; the uploads go to the session's copy of the configuration (unique names,
 * so a board imported before never answers "exists"), and it stops at once on a server that is not a simulator.
 *
 * Checks: (1) 00-ftp.board.xml planned at 30°: its drawing (the fallback from the board file) drawn, every fiducial of
 * the drawing within 0.5 px of the plan's, every pad on drawn copper; (2) a pad click opens the inspector (rule
 * sentence, dots, the selected pad, the top camera, Esc back) and #btn-pad-inspect opens the first pad in path order;
 * (3) #pv-play expands #run-preview, Clear collapses it; (4) a file dropped outside the drop zone never replaces the
 * page; (5) a part outside the board outline is drawn only, with OFF_BOARD in group 3; a file the server refuses shows
 * its reason with "Choose another file", Cancel while reading goes back to the file choice; upload of ftp.kicad_pcb:
 * 80 parts get paste (160 pads), 3 fiducials, the drawing, three packages new or suffixed, nothing that blocks
 * dispensing, PACKAGE_SUFFIXED a note, the attach offer naming ftp.board.xml and FID1, a package row focused from the
 * keyboard outlines its parts;
 * (6) save under a unique name, Use this board: the Board step has it, with its picture (every picture with its size
 * and parts), and the main column draws it before planning while the plan of (1) stays; (7) the same file dropped on
 * the drop zone under the same name: "exists", a two-press replace, Esc does nothing while it saves; (8) the import
 * planned at 30°: 160 pads and dots, R pads within 0.01 mm and LED pads within 0.04 mm of (1), copper and silkscreen
 * in Full, the drawing aligned (FID1 included); (9) Camera is on FID1 sets the origin to camera − R(30°)·FID1 within
 * 0.01 mm, and the plan from it puts FID1 under the camera; (10) attach: ftp.board.xml gets the KiCad drawing in one
 * press, under a free name when another board holds its name (that board keeps its drawing), its plan's pads unchanged; (11) Records: a board run's map draws the board (skipped when the session has no board run
 * yet); (12) the stage bar and the expanded preview row fit at 1280 × 800 and 1024 × 768, #plan-svg >= 280 px tall
 * at both, the Run stepper keeps a long board name inside its step at 1440, 1280 and 1024; (13) dark theme: the marks on the board keep their contrast; (14) screenshots to SHOTS_DIR (1440 and 1280,
 * light and dark): the empty dialog, and for nano, ftp and mobo the preview, the saved state with its pad-type actions,
 * the Board step with the board chosen (the hero before planning), the board planned on Run and the pad inspector;
 * (15) no JavaScript errors, no native dialogs; (16) the hero draws a plan's dispensed dots on that plan only (another
 * plan starts with none; a stale index breaks nothing).
 *
 * Env: IL_BASE or IL_API_URL (the UI, trailing / optional), PLAYWRIGHT_DIR, OUT_DIR (results.json), SHOTS_DIR
 * (default OUT_DIR), IL_KICAD_FIXTURES (default src/test/resources/kicad), BI_SHOTS=0 skips (14).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-board-import-test-out';
const SHOTS = process.env.SHOTS_DIR || OUT;
const FIX = process.env.IL_KICAD_FIXTURES || path.join(__dirname, '..', 'resources', 'kicad');
const TS = Date.now().toString(36);
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
function skip(name, why) {
  results.push({ name, ok: true, skipped: why });
  console.log('skip ' + name + ': ' + why);
}
const shots = [];
async function shot(page, name) {
  const file = path.join(SHOTS, name + '.png');
  await page.screenshot({ path: file });
  shots.push(file);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** A KiCad fixture as setInputFiles takes it (gunzipped). */
function kicad(fixture, name) {
  const raw = fs.readFileSync(path.join(FIX, fixture));
  return { name, mimeType: 'application/octet-stream', buffer: fixture.endsWith('.gz') ? zlib.gunzipSync(raw) : raw };
}
const FTP = () => kicad('ftp.kicad_pcb.gz', 'ftp.kicad_pcb');
const NANO = () => kicad('NanoV3.3.kicad_pcb.gz', 'NanoV3.3.kicad_pcb');
const MOBO = () => kicad('mobo.kicad_pcb.gz', 'mobo.kicad_pcb');

async function boards(page) { return page.evaluate(() => IL.api('GET', '/api/boards')); }
/** The folder name the server keeps a board or a drawing under (BoardStore.slug for Latin names). */
const slugOf = (s) => String(s || '').normalize('NFKD').replace(/\p{Mn}/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  .slice(0, 40).replace(/-+$/g, '');
async function toBoardStep(page) {
  await page.evaluate(() => { if (IL.panel.isPushed()) IL.panel.close(); IL.go('#run/board'); });
  await page.waitForSelector('#board-select', { state: 'visible', timeout: 15000 });
}
/** Plans a board file through the Board step (select, seat, Plan) and waits for the plan of that seat. */
async function planUi(page, file, seat) {
  await toBoardStep(page);
  await page.waitForFunction((f) => Array.from(document.querySelectorAll('#board-select option')).some((o) => o.value === 'file:' + f), file, { timeout: 15000 });
  await page.selectOption('#board-select', 'file:' + file);
  for (const [id, v] of [['board-x', seat.x], ['board-y', seat.y], ['board-z', seat.z], ['board-rot', seat.rotation]]) await page.fill('#' + id, String(v));
  const before = await page.evaluate(() => IL.S.planId);
  await page.click('#btn-plan');
  await page.waitForFunction(([b, f, r]) => {
    const p = IL.S.plan;
    return !!p && p.planId !== b && p.source && p.source.file === f && Math.abs(Number(p.source.rotation) - r) < 1e-6;
  }, [before, file, seat.rotation], { timeout: 60000 });
  await waitArt(page);
}
/** The hero shows the plan with its drawing (built and placed). */
async function waitArt(page) {
  await page.waitForFunction(() => {
    const bv = IL.run && IL.run.bv, p = IL.S.plan;
    return !!(bv && p && bv.plan === p && p.board && p.board.art && bv.art && bv.art.id === p.board.art.id && !bv.artSvg.hidden);
  }, null, { timeout: 30000 });
}
/** Every fiducial of the drawing within px of the plan's fiducials, every pad centre on the drawing's exposed copper. */
async function alignment(page) {
  return page.evaluate(() => {
    const bv = IL.run.bv, p = IL.S.plan, art = bv.art;
    const root = bv.artSvg.querySelector('g.pv-l-art');
    const sp = (node, x, y) => { const m = node.getScreenCTM(); return { x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f }; };
    const fids = (art.fiducials || []).map((f) => {
      const pf = ((p.board && p.board.fiducials) || []).find((q) => q.id === f.name);
      if (!pf) return { name: f.name, missing: true };
      const a = sp(root, f.bx, f.by), o = sp(bv.svg, pf.x, -pf.y);
      const m = bv.svg.getScreenCTM(), pxPerMm = Math.hypot(m.a, m.b);
      const px = Math.hypot(a.x - o.x, a.y - o.y);
      return { name: f.name, px: +px.toFixed(3), mm: +(px / pxPerMm).toFixed(4) };
    });
    const exp = root.querySelector('path.pv-a-exposed');
    const inv = root.getScreenCTM().inverse();
    const off = [];
    for (const pad of p.pads || []) {
      const s = sp(bv.svg, pad.x, -pad.y);
      if (!exp || !exp.isPointInFill(new DOMPoint(s.x, s.y).matrixTransform(inv))) off.push(pad.padId);
    }
    return { fids, off: off.slice(0, 10), offCount: off.length, pads: (p.pads || []).length, kind: art.kind };
  });
}
const padMap = (page) => page.evaluate(() => Object.fromEntries((IL.S.plan.pads || []).map((x) => [x.padId, [x.x, x.y]])));
async function openDialog(page) {
  await toBoardStep(page);
  await page.click('#btn-board-upload');
  await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
}
async function upload(page, file) {
  await page.setInputFiles('#board-import-file', file);
  await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
  await page.waitForSelector('#board-import-preview svg.pv-art:not([hidden]) .pv-a-edge', { timeout: 10000 });
}
async function saveAs(page, name) {
  await page.fill('#board-import-name', name);
  await page.click('#btn-board-import-save');
  await page.waitForSelector('#dlg-board-import[data-state="saved"]', { timeout: 60000 });
}
/** The hero's picture (Full / Quiet / Off) through #pv-picture: its segment when shown, else its cycling button. */
async function setPicture(page, v) {
  for (let i = 0; i < 4; i++) {
    if (await page.evaluate((x) => IL.run.bv.pic === x, v)) return;
    const seg = await page.$('#pv-picture .bf-pic__seg button[data-pic="' + v + '"]');
    if (seg && await seg.isVisible()) await seg.click();
    else await page.click('#pv-picture .bf-pic__cycle');
    await sleep(250);
  }
  await page.waitForFunction((x) => IL.run.bv.pic === x, v, { timeout: 5000 });
}
const lum = (rgb) => {
  const m = String(rgb).match(/[\d.]+/g);
  if (!m) return null;
  const c = m.slice(0, 3).map((v) => { const s = Number(v) / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); });
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
};
const contrast = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) jsErrors.push(m.text()); });
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  try {
    const resp = await page.goto(BASE + '#run');
    check('UI served', resp.ok(), resp.status());
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.run && IL.run.bv, null, { timeout: 60000 });
    // it saves boards into the configuration and gives ftp.board.xml a drawing: a simulator's copy only
    const sim = await page.evaluate(() => !!(IL.S.status.server && IL.S.status.server.simulated === true));
    check('the server is an OpenPnP simulator (status.server.simulated)', sim);
    if (!sim) throw new Error('not a simulator: nothing was uploaded');
    await page.evaluate(() => IL.setTheme('light'));
    check('the board flow is loaded (IL.boardFlow, IL.boardImport) and Run calls it (#btn-board-upload in the Board step)',
      await page.evaluate(() => !!(IL.boardFlow && IL.boardImport)) && (await toBoardStep(page), await page.isVisible('#btn-board-upload')));
    const b0 = await boards(page);
    const ftpFile = ((b0.files || []).find((f) => /ftp\.board\.xml$/.test(f.file || '')) || {}).file;
    check('the session lists ftp.board.xml', !!ftpFile, (b0.files || []).map((f) => f.file));
    const z = await page.evaluate(() => { const p = IL.S.plan; return p && p.source && Number.isFinite(Number(p.source.z)) ? Number(p.source.z) : 5.43; });
    const SEAT = { x: 120, y: 120, z, rotation: 30 };

    // ---- (1) ftp.board.xml at 30°: the drawing from the board file, aligned --------------------------------------------
    await planUi(page, ftpFile, SEAT);
    const a1 = await alignment(page);
    check('(1) ftp.board.xml at 30°: its drawing is drawn (the fallback from the board file in a fresh session: ' + a1.kind + ')',
      a1.kind === 'fallback' || a1.kind === 'kicad', a1.kind);
    // a fresh session draws the board file itself (every fiducial exact); after an attach in an earlier run it draws
    // KiCad's, whose FID1 is 0.31 mm from the board file's (§3.10): the other two stay exact, FID1 within 0.35 mm
    const exact1 = a1.fids.filter((f) => !f.missing && f.px <= 0.5).length;
    check('(1) every fiducial of the drawing within 0.5 px of the plan\'s (an attached KiCad drawing: two, and FID1 within 0.35 mm)',
      a1.fids.length === 3 && (a1.kind === 'fallback' ? exact1 === 3 : exact1 >= 2 && a1.fids.every((f) => !f.missing && f.mm <= 0.35)), a1.fids);
    check('(1) every pad of the plan on the drawing\'s copper', a1.offCount === 0 && a1.pads === 160, a1);
    const pads1 = await padMap(page);

    // ---- (2) pad inspector ------------------------------------------------------------------------------------------
    await page.evaluate(() => IL.run.bv.setView('dots'));
    await page.click('#plan-svg rect.pad[data-pad="R1.1"]', { force: true });
    await page.waitForSelector('#panel[data-view="pad"] #pad-insp-svg svg', { timeout: 10000 });
    await page.waitForFunction(() => document.getElementById('pad-insp-rule').textContent.length > 10, null, { timeout: 10000 });
    const pi = await page.evaluate(() => {
      const pad = IL.S.plan.pads.find((x) => x.padId === 'R1.1');
      return { rule: document.getElementById('pad-insp-rule').textContent, dots: document.querySelectorAll('#pad-insp-dots tbody tr').length,
        dotCount: pad.dotCount, sel: document.querySelectorAll('#pad-insp-svg svg rect.pad.sel').length, title: document.getElementById('panel-bar-title').textContent };
    });
    check('(2) a pad click opens the inspector: "Pad R1.1", the rule in one sentence', pi.title === 'Pad R1.1'
      && /^(Chosen because the package name contains|Mapped to this package by hand)/.test(pi.rule), pi);
    check('(2) inspector: one row per dot of the pad, the pad selected in its drawing', pi.dots === pi.dotCount && pi.sel === 1, pi);
    await page.click('#pad-insp-cam');
    await page.waitForFunction(() => /Top camera/.test(document.getElementById('panel-bar-title').textContent), null, { timeout: 5000 }).catch(() => {});
    check('(2) inspector: Top camera opens the camera panel', /Top camera/.test(await page.textContent('#panel-bar-title')), await page.textContent('#panel-bar-title'));
    await page.keyboard.press('Escape');
    await sleep(300);
    const afterEsc = await page.evaluate(() => document.getElementById('panel').dataset.view);
    check('(2) Esc goes back from the camera panel', afterEsc !== 'camera', afterEsc);
    await page.evaluate(() => { while (IL.panel.isPushed()) IL.panel.close(); });
    await page.waitForFunction(() => !IL.panel.isPushed(), null, { timeout: 5000 });
    await page.click('#btn-pad-inspect');
    await page.waitForFunction(() => document.getElementById('panel-bar-title').textContent === 'Pad ' + IL.S.plan.dots[0].padId, null, { timeout: 5000 }).catch(() => {});
    const first = await page.evaluate(() => ({ title: document.getElementById('panel-bar-title').textContent, want: 'Pad ' + IL.S.plan.dots[0].padId }));
    check('(2) #btn-pad-inspect opens the first pad in path order', first.title === first.want, first);
    await page.evaluate(() => { while (IL.panel.isPushed()) IL.panel.close(); });

    // ---- (3) path preview -------------------------------------------------------------------------------------------
    check('(3) #pv-play is shown with a plan', await page.isVisible('#pv-play'));
    await page.click('#pv-play');
    check('(3) #pv-play expands #run-preview', await page.isVisible('#run-preview'));
    await page.click('#pv-reset');
    check('(3) Clear collapses it', !(await page.isVisible('#run-preview')));

    // ---- (4) drop guard ---------------------------------------------------------------------------------------------
    const href0 = await page.evaluate(() => location.href);
    const dg = await page.evaluate(() => {
      const dt = new DataTransfer();
      dt.items.add(new File(['not a board'], 'notes.txt', { type: 'text/plain' }));
      const main = document.getElementById('main');
      const over = main.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true }));
      const drop = main.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
      return { overAllowed: over, dropAllowed: drop, toast: document.getElementById('toast').textContent };
    });
    check('(4) a file dropped on the page is taken by the guard (default prevented) and named in a toast', !dg.overAllowed && !dg.dropAllowed
      && /Drop a \.kicad_pcb file/.test(dg.toast), dg);
    check('(4) the page stays (same address, STOP visible)', (await page.evaluate(() => location.href)) === href0 && (await page.isVisible('#btn-stop')));

    // ---- (5) upload ftp ---------------------------------------------------------------------------------------------
    await openDialog(page);
    // a footprint outside the board outline (padcal.kicad_pcb's R1 parked left of the board): drawn only, no paste, and
    // OFF_BOARD under "Check in the preview" (group 3)
    const offBoard = fs.readFileSync(path.join(FIX, '..', 'padprofile', 'padcal.kicad_pcb'), 'utf8').replace('(at 104 126)', '(at 90 126)');
    await page.setInputFiles('#board-import-file', { name: 'padcal-off-board.kicad_pcb', mimeType: 'application/octet-stream', buffer: Buffer.from(offBoard) });
    await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 }).catch(() => {});
    const ob = await page.evaluate(() => {
      const li = document.querySelector('#board-import-warn li[data-code="OFF_BOARD"]');
      return { group: li ? li.dataset.group : null, section: !!(li && li.closest('section[data-group="3"]')), text: li ? li.textContent : null,
        found: document.getElementById('board-import-found').textContent };
    });
    check('(5) a part outside the board outline: OFF_BOARD under "Check in the preview" (group 3), drawn only, no paste',
      ob.group === '3' && ob.section && /^R1 is not inside the board outline: it gets no paste \(its paste would land off the board\)\./.test(ob.text)
      && /1 part drawn only \(R1\)/.test(ob.found), ob);
    // first the server's refusal of a .kicad_pcb that is not a board, shown as it says it, with one way on
    await page.setInputFiles('#board-import-file', { name: 'notes.kicad_pcb', mimeType: 'application/octet-stream', buffer: Buffer.from('(kicad_sch (version 20231120) (generator "eeschema"))') });
    await page.waitForSelector('#dlg-board-import[data-state="error"]', { timeout: 30000 }).catch(() => {});
    const rf = await page.evaluate(() => ({ state: document.getElementById('dlg-board-import').dataset.state, text: document.getElementById('board-import-status').textContent,
      other: !!document.querySelector('#board-import-status #btn-board-import-other'), drop: !document.getElementById('board-import-drop').hidden }));
    check('(5) a refused file: the server\'s reason in the dialog ("This is a KiCad schematic, not a board"), "Choose another file", no second drop box',
      rf.state === 'error' && /^This is a KiCad schematic, not a board: choose the board's \.kicad_pcb file\./.test(rf.text) && rf.other && !rf.drop, rf);
    // Cancel while the server reads: back to the file choice, and the late answer is dropped
    await page.setInputFiles('#board-import-file', MOBO());
    if (await page.evaluate(() => document.getElementById('dlg-board-import').dataset.state === 'reading')) {
      await page.click('#btn-board-import-cancel');
      await sleep(3000);
      const cr = await page.evaluate(() => ({ open: !!document.getElementById('dlg-board-import'), state: (document.getElementById('dlg-board-import') || { dataset: {} }).dataset.state }));
      check('(5) Cancel while reading: the dialog stays, back to the file choice (a late answer is not shown)', cr.open && cr.state === 'choose', cr);
    } else skip('(5) Cancel while reading', 'the server answered before Cancel could be pressed');
    const ftp = FTP();
    await upload(page, ftp);
    const pv = await page.evaluate(() => ({ found: document.getElementById('board-import-found').textContent,
      rows: Array.from(document.querySelectorAll('#board-import-packages tr[data-package]')).map((r) => r.dataset.state),
      g1: document.querySelectorAll('#board-import-warn li[data-group="1"]').length,
      suffixed: Array.from(document.querySelectorAll('#board-import-warn li[data-code="PACKAGE_SUFFIXED"]')).map((li) => li.closest('details') ? 'notes' : 'open'),
      attach: document.getElementById('board-import-attach').hidden ? null : document.getElementById('board-import-attach').textContent }));
    check('(5) ftp: 80 parts get paste (160 pads), 3 fiducials', /80 parts get paste \(160 pads\)/.test(pv.found) && /3 fiducials/.test(pv.found), pv.found);
    check('(5) ftp: three packages, each new or suffixed (reuse after an import in an earlier run)', pv.rows.length === 3
      && pv.rows.every((s) => /^(new|suffixed|reuse)$/.test(s)), pv.rows);
    check('(5) ftp: nothing blocks dispensing; PACKAGE_SUFFIXED (if any) is a note', pv.g1 === 0 && pv.suffixed.every((x) => x === 'notes'), pv);
    check('(5) ftp: the attach offer names ftp.board.xml and FID1', !!pv.attach && /ftp\.board\.xml/.test(pv.attach) && /FID1/.test(pv.attach), pv.attach);
    // keyboard: a package row takes the focus and outlines its parts on the drawing
    await page.focus('#board-import-packages tbody tr[data-package]');
    await sleep(150);
    const kb = await page.evaluate(() => ({ focused: !!(document.activeElement && document.activeElement.dataset && document.activeElement.dataset.package),
      outlined: document.querySelectorAll('#board-import-preview .pv-l-sel rect').length }));
    check('(5) keyboard: a focused package row outlines its parts on the drawing', kb.focused && kb.outlined > 0, kb);

    // ---- (6) save, Use this board -------------------------------------------------------------------------------------
    const name6 = 'ftp-kicad-' + TS;
    await saveAs(page, name6);
    const sv = await page.evaluate(() => ({ saved: document.getElementById('board-import-saved').textContent, toast: document.getElementById('toast').textContent }));
    check('(6) saved: "Saved …" and a toast', /^Saved/.test(sv.saved.trim()) && /Saved/.test(sv.toast), sv);
    await page.click('#btn-board-import-use');
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 5000 });
    await page.waitForFunction((n) => new RegExp('/' + n + '/' + n + '\\.board\\.xml$').test(document.getElementById('board-select').value), name6, { timeout: 15000 }).catch(() => {});
    const sel6 = await page.evaluate(() => document.getElementById('board-select').value);
    check('(6) Use this board: the Board step has it chosen', new RegExp('/' + name6 + '/' + name6 + '\\.board\\.xml$').test(sel6), sel6);
    await page.waitForFunction(() => { const i = document.querySelector('#board-thumbs button.board-thumb[aria-pressed="true"] img'); return !!(i && i.complete && i.naturalWidth > 0); }, null, { timeout: 15000 }).catch(() => {});
    check('(6) its picture in the board row is pressed and loaded', await page.evaluate(() => {
      const i = document.querySelector('#board-thumbs button.board-thumb[aria-pressed="true"] img');
      return !!(i && i.complete && i.naturalWidth > 0);
    }));
    const file6 = sel6.slice(5);
    // every picture says the board's size and parts (§5.9 "75 × 75 mm · 80 parts"), each line inside the picture
    const th6 = await page.evaluate(() => Array.from(document.querySelectorAll('#board-thumbs button.board-thumb')).map((b) => {
      const r = b.getBoundingClientRect();
      const lines = Array.from(b.querySelectorAll('.board-thumb__t > *')).map((x) => x.getBoundingClientRect());
      return { size: (b.querySelector('.board-thumb__size') || {}).textContent || null, parts: (b.querySelector('.board-thumb__t > .board-thumb__s:last-child') || {}).textContent || null,
        inside: lines.every((x) => x.top >= r.top && x.bottom <= r.bottom) };
    }));
    // (a board file without dimensions has no size line)
    check('(6) the board pictures name the size ("75 × 75 mm") and the parts, inside the picture',
      th6.some((x) => x.size) && th6.every((x) => (x.size === null || /^\d+(\.\d)?\u00a0×\u00a0\d+(\.\d)?\u00a0mm$/.test(x.size)) && /^\d+ parts?/.test(x.parts) && x.inside), th6);
    // the plan of (1) stays until Plan; the main column shows the board just chosen before planning (§5.9), not that plan
    await page.waitForFunction(() => { const c = document.getElementById('board-preplan'); return !!c && !c.hidden && !!IL.run.bv.board; }, null, { timeout: 15000 }).catch(() => {});
    const pre6 = await page.evaluate((f) => {
      const bv = IL.run.bv, e = ((IL.run.boards || {}).files || []).find((x) => x.file === f) || {};
      return { plan: !!IL.S.plan && IL.S.plan.source.file !== f, art: bv.board && bv.art ? bv.art.id : null, want: e.art ? e.art.id : null,
        dots: document.querySelectorAll('#plan-svg circle.dot').length, cap: document.getElementById('board-preplan').textContent,
        title: document.getElementById('run-title').textContent, name: e.name, chip: document.getElementById('plan-chip').textContent };
    }, file6);
    check('(6) the board chosen is drawn before planning ("Plan to see the dots", no dots, its name, "Not planned yet") while the plan of (1) stays',
      pre6.plan && !!pre6.art && pre6.art === pre6.want && pre6.dots === 0 && /^Plan to see the dots/.test(pre6.cap) && pre6.title === pre6.name
        && /Not planned yet/.test(pre6.chip), pre6);

    // ---- (7) replace: the same bytes dropped on the drop zone, the same name ---------------------------------------
    await openDialog(page);
    await page.evaluate(async ({ b64, name }) => {
      const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const dt = new DataTransfer();
      dt.items.add(new File([bin], name, { type: 'application/octet-stream' }));
      const zone = document.getElementById('board-import-drop');
      for (const t of ['dragenter', 'dragover', 'drop']) zone.dispatchEvent(new DragEvent(t, { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, { b64: ftp.buffer.toString('base64'), name: ftp.name });
    await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
    await page.fill('#board-import-name', name6);
    await page.waitForSelector('#board-import-exists:not([hidden])', { timeout: 5000 }).catch(() => {});
    check('(7) the same name: #board-import-exists says it replaces the board', await page.isVisible('#board-import-exists')
      && /exists/.test(await page.textContent('#board-import-exists')), await page.textContent('#board-import-exists'));
    await page.click('#btn-board-import-save');
    const armed = (await page.textContent('#btn-board-import-save')).trim();
    check('(7) first press: "Press again: replace board"', armed === 'Press again: replace board', armed);
    await sleep(450);
    await page.click('#btn-board-import-save');
    const st7 = await page.evaluate(() => document.getElementById('dlg-board-import').dataset.state);   // the save runs (not a pending press)
    await page.keyboard.press('Escape');
    const during = await page.evaluate(() => ({ open: !!document.getElementById('dlg-board-import'), state: document.getElementById('dlg-board-import') && document.getElementById('dlg-board-import').dataset.state }));
    check('(7) Esc while it saves does nothing', st7 === 'saving' && during.open, Object.assign({ pressedIn: st7 }, during));
    await page.waitForSelector('#dlg-board-import[data-state="saved"]', { timeout: 60000 });
    check('(7) second press: saved', /^Saved/.test((await page.textContent('#board-import-saved')).trim()));
    await page.click('#btn-board-import-use');
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 5000 });

    // ---- (8) the import planned at 30° --------------------------------------------------------------------------------
    await planUi(page, file6, SEAT);
    const pads8 = await padMap(page);
    const ids8 = Object.keys(pads8);
    const dots8 = await page.evaluate(() => IL.S.plan.dots.length);
    const dev = (re) => Math.max(...Object.keys(pads1).filter((k) => re.test(k) && pads8[k]).map((k) => Math.hypot(pads1[k][0] - pads8[k][0], pads1[k][1] - pads8[k][1])));
    const devR = dev(/^R\d+\./), devD = dev(/^D\d+\./);
    check('(8) the import planned at 30°: 160 pads and dots', ids8.length === 160 && dots8 === 160, { pads: ids8.length, dots: dots8 });
    check('(8) R pads within 0.01 mm and LED pads within 0.04 mm of ftp.board.xml\'s plan', devR <= 0.01 && devD <= 0.04, { devR, devD });
    // a seat with no surface yet: the strip and the nav name the item's fix as its button does (final gate: "8 of 9 ·
    // probe seat" beside "Register board")
    const surf8 = await page.evaluate(() => { const it = IL.ready().items.find((x) => x.key === 'surface');
      return it && { state: it.state, short: it.short, value: it.value, fix: (it.fixes || [])[0] ? it.fixes[0].label : null }; });
    check('(8) the new seat\'s surface item says "register board" (not "probe seat"), as its fix Register board',
      !!surf8 && ((surf8.state === 'need' && surf8.short === 'register board' && surf8.value === 'register') || (surf8.state === 'block' && surf8.value === 'register again'))
        && surf8.fix === 'Register board', surf8);
    await setPicture(page, 'full');
    const full = await page.evaluate(() => {
      const shown = (sel) => Array.from(document.querySelectorAll(sel)).some((n) => getComputedStyle(n).display !== 'none' && n.getBBox().width > 0);
      return { cu: shown('#run-hero .pv-art .pv-a-cu'), silk: shown('#run-hero .pv-art .pv-a-silk, #run-hero .pv-art .pv-a-silklines path') };
    });
    check('(8) Full: copper and silkscreen drawn', full.cu && full.silk, full);
    const a8 = await alignment(page);
    check('(8) the import\'s own drawing aligned: every fiducial (FID1 too) within 0.5 px, every pad on copper', a8.kind === 'kicad'
      && a8.fids.length === 3 && a8.fids.every((f) => !f.missing && f.px <= 0.5) && a8.offCount === 0, a8);
    await setPicture(page, 'quiet');

    // ---- (9) Camera is on FID1 ----------------------------------------------------------------------------------------
    await toBoardStep(page);
    await page.fill('#board-rot', '30');
    await page.waitForSelector('#btn-board-use-fid:enabled', { timeout: 10000 }).catch(() => {});
    const want9 = await page.evaluate((f) => {
      const e = (IL.run.boards.files || []).find((x) => x.file === f);
      const fid = e && (e.fiducials || []).slice().sort((a, b) => String(a.id).localeCompare(String(b.id), 'en', { numeric: true }))[0];
      const cam = IL.positions().cam;
      if (!fid || !cam) return { fid, cam };
      const r = 30 * Math.PI / 180;
      return { fid, cam, x: cam.x - (fid.bx * Math.cos(r) - fid.by * Math.sin(r)), y: cam.y - (fid.bx * Math.sin(r) + fid.by * Math.cos(r)) };
    }, file6);
    await page.click('#btn-board-use-fid');
    await sleep(200);
    const got9 = { x: Number(await page.inputValue('#board-x')), y: Number(await page.inputValue('#board-y')) };
    check('(9) Camera is on FID1: origin = camera − R(30°)·FID1 within 0.01 mm', want9.fid && want9.fid.id === 'FID1'
      && Math.abs(got9.x - want9.x) <= 0.01 && Math.abs(got9.y - want9.y) <= 0.01, { want9, got9 });
    // the server's own board → machine transform agrees (the formula above could share a sign error with the page's):
    // planned from that origin, FID1 lies under the camera, within the 0.01 mm the origin is kept to
    const before9 = await page.evaluate(() => IL.S.planId);
    await page.click('#btn-plan');
    await page.waitForFunction((b) => !!IL.S.plan && IL.S.plan.planId !== b, before9, { timeout: 60000 }).catch(() => {});
    const fid9 = await page.evaluate(() => {
      const f = ((IL.S.plan && IL.S.plan.board && IL.S.plan.board.fiducials) || []).find((x) => x.id === 'FID1');
      return f ? { x: f.x, y: f.y } : null;
    });
    check('(9) planned from that origin, the plan puts FID1 under the top camera (within 0.01 mm)', !!fid9 && !!want9.cam
      && Math.hypot(fid9.x - want9.cam.x, fid9.y - want9.cam.y) <= 0.01, { fid9, cam: want9.cam });

    // ---- (10) attach the drawing to ftp.board.xml ---------------------------------------------------------------------
    // first a board of its own under ftp.board.xml's OpenPnP name (the name the attach keeps the drawing under): the
    // attach must not replace that board's drawing, it takes a free name
    const b10a = await boards(page);
    const bench10 = (b10a.files || []).find((f) => f.file === ftpFile) || {};
    const had10 = !!(bench10.imported && bench10.imported.how === 'attach');
    const holderOf = (list) => (list.files || []).find((f) => f.file !== ftpFile && f.imported && f.imported.slug === slugOf(bench10.name));
    if (!holderOf(b10a) && !had10 && slugOf(bench10.name) && String(bench10.name).length <= 60) {
      await openDialog(page);
      await upload(page, FTP());
      await saveAs(page, bench10.name);
      await page.keyboard.press('Escape');
      await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 5000 });
    }
    const holder10 = holderOf(await boards(page)) || null;
    await openDialog(page);
    await upload(page, FTP());
    // one press; two when ftp.board.xml has a drawing already (an earlier run: replacing it is a second press)
    let presses10 = 0;
    for (let i = 0; i < 4; i++) {
      if (await page.$('#dlg-board-import[data-state="saved"]')) break;
      await page.waitForSelector('#dlg-board-import[data-state="preview"] #btn-board-import-attach:enabled', { timeout: 60000 });
      const armed10 = await page.$('#btn-board-import-attach.btn--press');
      if (armed10) await sleep(450);
      await page.click('#btn-board-import-attach');
      presses10++;
      await page.waitForFunction(() => { const d = document.getElementById('dlg-board-import'); return !d || d.dataset.state !== 'saving'; }, null, { timeout: 60000 });
      await sleep(200);
    }
    await page.waitForSelector('#dlg-board-import[data-state="saved"]', { timeout: 60000 });
    const t10 = await page.textContent('#toast');
    check('(10) attach: a toast says the drawing is used for ftp.board.xml', /drawing is now used for .*ftp\.board\.xml/.test(t10), t10);
    await page.click('#btn-board-import-use');
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 5000 });
    const b10 = await boards(page);
    const e10 = (b10.files || []).find((f) => f.file === ftpFile) || {};
    check('(10) GET /api/boards: ftp.board.xml has the KiCad drawing now', e10.art && e10.art.kind === 'kicad', e10.art);
    const holderNow = holder10 ? (b10.files || []).find((f) => f.file === holder10.file) : null;
    check('(10) a name another board holds is not replaced: one press (two to replace its own drawing), the drawing kept under a free name, that board keeps its drawing',
      presses10 === (had10 ? 2 : 1) && (had10 || !holder10 || (!!holderNow && holderNow.art && holderNow.art.id === holder10.art.id
        && !!e10.imported && e10.imported.slug !== holder10.imported.slug)),
      { presses10, had10, holder: holder10 && { file: holder10.file, slug: holder10.imported.slug, art: holder10.art.id }, holderNow: holderNow && holderNow.art, bench: e10.imported });
    await page.waitForFunction((f) => { const b = document.querySelector('#board-thumbs button.board-thumb[data-board="file:' + CSS.escape(f) + '"]'); return !!b && /KiCad/.test(b.textContent); }, ftpFile, { timeout: 10000 }).catch(() => {});
    check('(10) its picture in the board row says KiCad', await page.evaluate((f) => {
      const b = Array.from(document.querySelectorAll('#board-thumbs button.board-thumb')).find((x) => x.dataset.board === 'file:' + f);
      return !!b && /KiCad/.test(b.textContent);
    }, ftpFile));
    await planUi(page, ftpFile, SEAT);
    const pads10 = await padMap(page);
    const same10 = Object.keys(pads1).length === Object.keys(pads10).length && Object.keys(pads1).every((k) => pads10[k]
      && Math.hypot(pads1[k][0] - pads10[k][0], pads1[k][1] - pads10[k][1]) < 1e-6);
    check('(10) ftp.board.xml\'s plan after the attach: the same pads as in (1)', same10);
    check('(10) and the plan draws the KiCad drawing', await page.evaluate(() => IL.S.plan.board.art.kind === 'kicad'));

    // ---- (11) Records ---------------------------------------------------------------------------------------------------
    const runs = await page.evaluate(() => IL.api('GET', '/api/runs?limit=50').catch(() => null));
    const run = ((runs && (runs.runs || runs)) || []).find((r) => r.kind === 'board' && r.run_uuid);
    if (!run) skip('(11) Records map draws the board', 'no board run in this session yet (it runs after webui-sim.playwright.cjs)');
    else {
      await page.evaluate((id) => IL.go('#records/boards/' + id), run.run_uuid);
      const ok11 = await page.waitForSelector('.rec-mapbox .pv-art:not([hidden]) .pv-a-edge', { timeout: 20000 }).then(() => true, () => false);
      check('(11) Records: the board run\'s map draws the board', ok11);
      // its figures keep a label with its number on one line (final gate: "bench" ended a line, "5,490" began the next)
      const subs11 = await page.evaluate(() => Array.from(document.querySelectorAll('#panel .minis .mini__s')).map((n) => n.textContent));
      check('(11) Records: "first to last dot · bench 5,490" keeps "bench 5,490" together (no-break space)',
        subs11.some((t) => /bench\u00a05,490/.test(t)) && !subs11.some((t) => /\bbench \d/.test(t)), subs11);
      await page.evaluate(() => IL.go('#run'));
    }

    // ---- (12) layout at 1280 and 1024 -----------------------------------------------------------------------------------
    await planUi(page, file6, SEAT);
    for (const [w, h] of [[1280, 800], [1024, 768]]) {
      await page.setViewportSize({ width: w, height: h });
      await sleep(500);
      await page.click('#pv-play');
      await sleep(300);
      await page.click('#pv-play');   // pause: the row stays open
      await sleep(300);
      const lay = await page.evaluate(() => {
        const bar = document.querySelector('#run-hero .stage-bar'), row = document.getElementById('run-preview');
        const lg = bar.querySelector('.legend');
        return { barSw: bar.scrollWidth, barCw: bar.clientWidth, rowSw: row.scrollWidth, rowCw: row.clientWidth, rowShown: !row.hidden,
          lgSw: lg ? lg.scrollWidth : 0, lgCw: lg ? lg.clientWidth : 0, svgH: Math.round(document.getElementById('plan-svg').getBoundingClientRect().height) };
      });
      check('(12) ' + w + ' px: the stage bar, its legend and the open preview row fit; #plan-svg >= 280 px tall',
        lay.rowShown && lay.barSw <= lay.barCw + 1 && lay.rowSw <= lay.rowCw + 1 && lay.lgSw <= lay.lgCw + 1 && lay.svgH >= 280, lay);
      await page.click('#pv-reset');
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    // the Run stepper: a long board name ends in "…" inside its own step, never under the next step's sub-label
    for (const [w, h] of [[1440, 900], [1280, 800], [1024, 768]]) {
      await page.setViewportSize({ width: w, height: h });
      await sleep(300);
      const st12 = await page.evaluate(async () => {
        const p = IL.S.plan, keep = p.boardName;
        p.boardName = 'Arduino Nano V3.3 with a long board name for the stepper.board.xml';
        IL.render();
        await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
        const out = Array.from(document.querySelectorAll('nav.steps .step')).map((b) => {
          const sub = b.querySelector('.step__s'), r = b.getBoundingClientRect(), q = sub.getBoundingClientRect(), cs = getComputedStyle(sub);
          // its overflow (the "…") clips at the line box: the box holds the descenders ("registered", not "reqistered")
          return { k: b.dataset.step, inside: q.left >= r.left - 0.5 && q.right <= r.right + 0.5, cut: sub.scrollWidth > sub.clientWidth + 1, text: sub.textContent,
            room: parseFloat(cs.lineHeight) >= 1.3 * parseFloat(cs.fontSize) };
        });
        p.boardName = keep;
        IL.render();
        return out;
      });
      const bs = st12.find((x) => x.k === 'board') || {};
      check('(12) ' + w + ' px: the Run stepper keeps a long board name inside its step, cut with "…"', st12.length === 5 && st12.every((x) => x.inside) && bs.cut, st12);
      check('(12) ' + w + ' px: the stepper\'s sublines leave room for descenders (line height >= 1.3 em; at 1.15 em "registered" lost its g\'s tail)',
        st12.every((x) => x.room), st12);
    }
    await page.setViewportSize({ width: 1440, height: 900 });

    // ---- (13) dark theme: contrast of the marks on the board -----------------------------------------------------------
    await page.evaluate(() => IL.setTheme('dark'));
    await sleep(300);
    const cc = await page.evaluate(() => {
      const bv = IL.run.bv;
      const css = (n, p) => getComputedStyle(n).getPropertyValue(p);
      const rgb = (c) => { const d = document.createElement('div'); d.style.color = c; document.body.appendChild(d); const v = getComputedStyle(d).color; d.remove(); return v; };
      const art = bv.artSvg;
      const bare = rgb(css(art, '--pv-a-bare').trim());
      const mask = getComputedStyle(art.querySelector('.pv-a-mask')).fill;
      const dot = document.querySelector('#plan-svg circle.dot');
      const pad = document.querySelector('#plan-svg rect.pad');
      const caseL = document.querySelector('#plan-svg .pv-case--l'), caseD = document.querySelector('#plan-svg .pv-case--d');
      return { bare, mask, dot: getComputedStyle(dot).stroke, pad: getComputedStyle(pad).stroke,
        caseL: caseL ? getComputedStyle(caseL).stroke : null, caseD: caseD ? getComputedStyle(caseD).stroke : null, theme: IL.theme() };
    });
    const c13 = { dot: contrast(cc.dot, cc.bare), pad: contrast(cc.pad, cc.bare), caseOnMask: Math.max(contrast(cc.caseL, cc.mask), contrast(cc.caseD, cc.mask)),
      caseOnBare: Math.max(contrast(cc.caseL, cc.bare), contrast(cc.caseD, cc.bare)) };
    check('(13) dark theme: dots and paste openings >= 3:1 on the exposed copper, the casing >= 3:1 on the mask and the copper',
      cc.theme === 'dark' && c13.dot >= 3 && c13.pad >= 3 && c13.caseOnMask >= 3 && c13.caseOnBare >= 3, Object.assign({}, cc, c13));
    await page.evaluate(() => IL.setTheme('light'));

    // ---- (14) screenshots ----------------------------------------------------------------------------------------------
    if (process.env.BI_SHOTS !== '0') await screenshots(page, SEAT);
    else skip('(14) screenshots', 'BI_SHOTS=0');

    // ---- (16) the dispensed dots belong to their plan ------------------------------------------------------------------
    // the shown plan's board with paste on every pad (as the server keeps it, plan.pasted) plus one done index past its
    // last dot, then another plan of ftp at another seat: the hero draws the first plan's dots as done and nothing on the
    // second (a stale index used to throw in render)
    await toBoardStep(page);
    const dd = await page.evaluate(async ([file, seat]) => {
      const R = IL.run, p0 = IL.S.plan;
      const frame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      const n = p0 ? p0.dots.length : 0;
      if (p0) p0.pasted = Object.assign({}, p0.pasted, { pads: Object.fromEntries(p0.dots.map((d) => [d.padId, 99])) });
      R.doneSet = new Set(Array.from({ length: n }, (_, i) => i).concat([n + 1000]));
      IL.render();
      await frame();
      const before = document.querySelectorAll('#plan-svg circle.dot.done').length;
      const q = await IL.api('POST', '/api/plan', Object.assign({ source: 'file', file }, seat));
      IL.setPlan(q);
      await frame();
      return { n, before, after: document.querySelectorAll('#plan-svg circle.dot.done').length, dots: q.dots.length, newPlan: !p0 || q.planId !== p0.planId };
    }, [ftpFile, { x: 121, y: 119, z, rotation: 15 }]);
    check('(16) another plan starts with no dispensed dots; a done index past the last dot draws nothing',
      dd.before === dd.n && dd.after === 0 && dd.dots === 160 && dd.newPlan, dd);
  } catch (e) {
    check('no exception', false, String(e && e.stack || e));
    await page.screenshot({ path: path.join(OUT, 'board-import-failure.png') }).catch(() => {});
  } finally {
    check('(15) no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 10));
    check('(15) no native dialogs', dialogs.length === 0, dialogs);
    await browser.close();
    fs.writeFileSync(path.join(OUT, 'board-import-results.json'), JSON.stringify({ results, shots }, null, 2));
    const failed = results.filter((r) => !r.ok);
    console.log((failed.length ? 'FAILED ' + failed.length : 'PASSED') + ' (' + results.length + ' checks, ' + shots.length + ' screenshots)');
    process.exit(failed.length ? 1 : 0);
  }
})();

/**
 * (14) For each board: the preview, the saved state with its pad-type actions, the Board step with the board chosen
 * and the hero before planning, the board planned on Run (Quiet) and the pad inspector, each at 1440 × 900 and
 * 1280 × 800 in light and dark; plus the empty dialog. One upload per board (the states are shot in every size and
 * theme before moving on).
 */
async function screenshots(page, seat) {
  const combos = [[1440, 900, 'light'], [1440, 900, 'dark'], [1280, 800, 'light'], [1280, 800, 'dark']];
  const each = async (fn) => {
    for (const [w, h, t] of combos) {
      await page.setViewportSize({ width: w, height: h });
      await page.evaluate((x) => { IL.setTheme(x); document.getElementById('toast').hidden = true; }, t);   // a toast would cover the page title
      await sleep(450);
      await fn(w + '-' + t);
    }
  };
  await openDialog(page);
  await each((k) => shot(page, 'upload-' + k));
  // a file that is not a board: the reason and "Choose another file"
  await page.setInputFiles('#board-import-file', { name: 'NanoV3.3.kicad_sch', mimeType: 'application/octet-stream', buffer: Buffer.from('(kicad_sch (version 20211123))') });
  await page.waitForSelector('#dlg-board-import[data-state="error"] #btn-board-import-other', { timeout: 10000 });
  check('(14) a file that is not a .kicad_pcb: the reason and "Choose another file"', /not a KiCad board file/.test(await page.textContent('#board-import-status')),
    await page.textContent('#board-import-status'));
  await each((k) => shot(page, 'upload-error-' + k));
  await page.evaluate(() => IL.dialog.top().close('test'));
  for (const [key, file, part] of [['nano', NANO, 'IC3'], ['ftp', FTP, 'R12'], ['mobo', MOBO, 'U3']]) {
    await openDialog(page);
    await upload(page, file());
    await each(async (k) => { await page.evaluate(() => { document.querySelector('#dlg-board-import .dialog__body').scrollTop = 0; }); await shot(page, key + '-preview-' + k); });
    await saveAs(page, key + '-shots-' + Date.now().toString(36));
    await each((k) => shot(page, key + '-saved-' + k));
    await page.click('#btn-board-import-use');
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 5000 });
    // the hero before planning: no plan, the board at its seat with the top camera
    await page.evaluate(() => IL.clearPlan());
    await toBoardStep(page);
    for (const [id, v] of [['board-x', seat.x], ['board-y', seat.y], ['board-z', seat.z], ['board-rot', 0]]) await page.fill('#' + id, String(v));
    await page.waitForFunction(() => document.getElementById('run-hero').classList.contains('hero--board'), null, { timeout: 15000 }).catch(() => {});
    await each((k) => shot(page, key + '-applied-' + k));
    const file6 = (await page.evaluate(() => document.getElementById('board-select').value)).slice(5);
    await planUi(page, file6, Object.assign({}, seat, { rotation: 0 }));
    await sleep(800);   // Run moves on to its next step after Plan (Probe or Checks): shot as the operator sees it
    await each((k) => shot(page, key + '-run-' + k));
    const target = await page.evaluate((ref) => {
      const p = IL.S.plan;
      const pad = (p.pads || []).find((x) => x.placementId === ref && x.dotCount > 0) || (p.pads || []).find((x) => x.dotCount > 0) || (p.pads || [])[0];
      return pad ? pad.padId : null;
    }, part);
    if (target) {
      await page.evaluate((id) => IL.padInspect.open({ padId: id }, { bv: IL.run.bv }), target);
      await page.waitForSelector('#panel[data-view="pad"] #pad-insp-svg svg.pv-art:not([hidden])', { timeout: 10000 }).catch(() => {});
      await each((k) => shot(page, key + '-inspector-' + k));
      await page.evaluate(() => { while (IL.panel.isPushed()) IL.panel.close(); });
    }
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.evaluate(() => IL.setTheme('light'));
}
