/*
 * Board render harness (docs/instancelabs/BOARD_IMPORT_DESIGN.md §6.6 a): the board drawing under the job overlay, the
 * path preview, the pad inspector and the Records map, on a seeded SIM session that planned its upload of ftp.kicad_pcb
 * (the server's drawing and board block), through devproxy.py --fixtures (the fixture drawings, mobo for the
 * performance gate, and the dot size while the session has none). Run by run-boardview-test.sh. Nothing here moves a
 * real machine: the session is a simulator.
 *
 * The page glue is boardflow.js's own (hooks H1–H5): the hero is driven through its stage-bar controls (#pv-picture)
 * and its path preview (IL.boardFlow.preview()), the Records map draws the plan's board by itself, IL.padInspect.open
 * opens a pad.
 *
 * Checks: (1) structure: two stacked SVGs, no clip path, pad and dot counts equal the plan's; (2) the matrix sign at
 * 30°: R1 pad 1 and every fiducial of the drawing land within 0.5 px of the overlay's pad and the plan's fiducials, on
 * drawn copper; (3) contrast of the overlay marks and casing over both themes × 8 mask palettes × Full and Quiet, legend
 * swatches on the marks' colours; (4) the preview clock (scrub, readout, 16× play, Clear); (5) picture modes; (6)
 * reference labels (size buckets, never mirrored); (7) a performance gate at 4× CPU throttling with the largest drawing
 * in the fixtures and a plan of one pad per paste opening of it (mobo: 261); (8) 20 create/destroy cycles leave no
 * ResizeObserver; plus thumbnails, the pad inspector (a dot's outer edge is its real size, path numbers that never overlap
 * on a 48-pin QFP, one pad type said once), the Records map, the preview row's layout at 1440, 1280 and 1024 px with the
 * readout never cut and #plan-svg >= 280 px tall, no reference label on a pad-less footprint, the mask as painted in Quiet, the scale bar's size, keyboard focus
 * in the inspector table and after Clear, no rebuild when a render repeats the selection, polls that never touch the
 * drawing (a re-plan only moves it), the dirty layer's casing, the path lines on the Quiet mask (one uncased line), small dots
 * that keep a light centre, unprinted references as fab text, a QFP's selection outline round its leads, no
 * JavaScript errors, no native dialogs. Screenshots (1440 and
 * 1280 px, light and dark) of the Run hero (Full and Quiet), the path preview mid-play, the pad inspector and the
 * Records map go to SHOTS_DIR.
 *
 * Env: IL_BASE (devproxy URL, trailing /), IL_FIXTURES (fixtures dir), PLAYWRIGHT_DIR, OUT_DIR, SHOTS_DIR.
 */
'use strict';
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = process.env.IL_BASE;
const OUT = process.env.OUT_DIR;
const SHOTS = process.env.SHOTS_DIR || OUT;
const FIX = process.env.IL_FIXTURES;
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail) : ''));
}
const shots = [];
async function shot(page, name, clip) {
  const file = path.join(SHOTS, name + '.png');
  await page.screenshot(clip ? { path: file, clip } : { path: file });
  shots.push(file);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The largest drawing among the fixtures (the performance gate draws it): {id, bytes, file}. */
function largestArt() {
  const files = fs.readdirSync(FIX).filter((f) => f.endsWith('.art.json')).map((f) => path.join(FIX, f));
  const artDir = path.join(FIX, 'art');
  if (fs.existsSync(artDir)) for (const f of fs.readdirSync(artDir)) if (/^[0-9a-f]{16}\.json$/.test(f)) files.push(path.join(artDir, f));
  return files.map((f) => {
    const b = fs.readFileSync(f);
    return { id: crypto.createHash('sha256').update(b).digest('hex').slice(0, 16), bytes: b.length, file: path.basename(f) };
  }).sort((a, b) => b.bytes - a.bytes)[0];
}

/** Waits until the hero shows a plan with a board drawing reference (a plan made through devproxy --fixtures); rot: its seat's rotation. */
async function waitPlan(page, rot) {
  await page.waitForFunction((r) => {
    const p = window.IL && IL.S && IL.S.plan;
    return !!(p && p.board && p.board.art && IL.run && IL.run.bv && IL.run.bv.plan === p
      && (r === null || Math.abs(Number(p.source && p.source.rotation) - r) < 1e-6));
  }, rot === undefined ? null : rot, { timeout: 60000 });
}

/**
 * The hero's picture (Full / Quiet / Off) through boardflow's stage-bar control #pv-picture (its segment when shown, else
 * its cycling button), then the plan's drawing drawn under the dots: {art, parts}.
 */
async function showHero(page, picture) {
  for (let i = 0; i < 4; i++) {
    const cur = await page.evaluate(() => IL.run.bv.pic);
    if (cur === picture) break;
    const seg = await page.$('#pv-picture .bf-pic__seg button[data-pic="' + picture + '"]');
    if (seg && await seg.isVisible()) await seg.click();
    else await page.click('#pv-picture .bf-pic__cycle');
    // one step at a time: the cycling button goes Full → Quiet → Off on the next render
    await page.waitForFunction((c) => IL.run.bv.pic !== c, cur, { timeout: 5000 }).catch(() => {});
  }
  // the plan shown (a poll may replace IL.S.plan by an equal copy: the same planId), drawn
  await page.waitForFunction((x) => {
    const bv = IL.run.bv, p = IL.S.plan;
    return bv.pic === x && !!(p && p.board && p.board.art && bv.plan && bv.plan.planId === p.planId && bv.art && bv.art.id === p.board.art.id)
      && (x === 'off' || !bv.artSvg.hidden);
  }, picture, { timeout: 30000 });
  return page.evaluate(() => ({ art: !!IL.run.bv.art, parts: (IL.run.bv.art.parts || []).length }));
}

/** boardflow's Records map (H4): the plan's drawing under the done-dot map, Quiet, in view. */
async function recordsArt(page) {
  const ok = await page.waitForFunction(() => {
    const host = document.querySelector('.rec-mapbox');
    const bv = host && IL.boardview.of(host);
    return !!(bv && bv.plan && bv.plan.board && bv.art && bv.pic === 'quiet' && !bv.artSvg.hidden);
  }, null, { timeout: 30000 }).then(() => true, () => false);
  await page.evaluate(() => { const h = document.querySelector('.rec-mapbox'); if (h) h.scrollIntoView({ block: 'center' }); });
  return { ok };
}

(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const jsErrors = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(m.text()); });
  const dialogs = [];
  page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  try {
    const resp = await page.goto(BASE + '#run');
    check('UI served through devproxy', resp.ok(), resp.status());
    await waitPlan(page);
    const g0 = await showHero(page, 'full');
    check('the plan names a board drawing and the drawing loads under the hero (boardflow.js)', g0.art && g0.parts > 0, g0);

    // ---- (1) structure -------------------------------------------------------------------------------------------
    const st = await page.evaluate(() => {
      const bv = IL.run.bv, p = IL.S.plan;
      const host = bv.svg.parentNode;
      const svgs = Array.from(host.children).filter((n) => n.tagName === 'svg');
      return {
        order: svgs.map((s) => s.getAttribute('class') + (s.id ? '#' + s.id : '')),
        art: !!document.querySelector('.pv-art g.pv-l-art'), edge: !!document.querySelector('.pv-art .pv-a-edge'),
        exposed: !!document.querySelector('.pv-art .pv-a-exposed'), clip: document.querySelectorAll('#run-hero clipPath').length,
        pads: document.querySelectorAll('#plan-svg rect.pad').length, dots: document.querySelectorAll('#plan-svg circle.dot').length,
        planPads: p.pads.length, planDots: p.dots.length, artPadClass: document.querySelectorAll('.pv-art .pad, .pv-art .dot').length,
        vbSame: bv.svg.getAttribute('viewBox') === bv.artSvg.getAttribute('viewBox'), overlayArt: bv.svg.classList.contains('pv--art'),
        hiddenBoard: getComputedStyle(bv.svg.querySelector('.pv-l-board')).display === 'none',
        artPointer: getComputedStyle(bv.artSvg).pointerEvents, parts: document.querySelectorAll('#plan-svg .pv-l-parts rect.pv-part').length,
      };
    });
    check('two stacked SVGs: the drawing under #plan-svg', st.order.length === 2 && /pv-art/.test(st.order[0]) && /#plan-svg$/.test(st.order[1]), st.order);
    check('drawing has g.pv-l-art, .pv-a-edge, .pv-a-exposed; no clip path', st.art && st.edge && st.exposed && st.clip === 0, st);
    check('#plan-svg rect.pad and circle.dot equal the plan (no art node uses pad or dot)', st.pads === st.planPads && st.dots === st.planDots
      && st.artPadClass === 0, st);
    check('same viewBox on both SVGs, .pv--art on the overlay, its substrate hidden, the drawing takes no pointer events',
      st.vbSame && st.overlayArt && st.hiddenBoard && st.artPointer === 'none', st);
    check('part hit rects from the drawing', st.parts > 80, st.parts);

    // ---- screenshots (bench seat of the session's plan, idle) ---------------------------------------------------------
    for (const [w, h] of [[1440, 900], [1280, 800]]) {
      await page.setViewportSize({ width: w, height: h });
      for (const theme of ['light', 'dark']) {
        await page.evaluate((t) => IL.setTheme(t), theme);
        await showHero(page, 'full');
        await page.evaluate(() => { IL.run.bv.setView('dots'); IL.render(); });
        await sleep(500);
        await shot(page, 'run-hero-full-' + w + '-' + theme);
        await showHero(page, 'quiet');
        await sleep(300);
        await shot(page, 'run-hero-quiet-' + w + '-' + theme);
        // path preview mid-play (16×, paused half way)
        await page.evaluate(() => { const pp = IL.boardFlow.preview(); pp.setSpeed(16); pp.seek(64); pp.play(); });
        await page.waitForFunction(() => IL.boardFlow.preview().state.v >= 80, null, { timeout: 15000 });
        await page.evaluate(() => IL.boardFlow.preview().pause());
        await sleep(200);
        await shot(page, 'path-preview-' + w + '-' + theme);
        await page.evaluate(() => IL.boardFlow.preview().clear());
        // pad inspector open on R1 pad 1
        await page.evaluate(() => IL.padInspect.open({ padId: 'R1.1' }, { bv: IL.run.bv }));
        await page.waitForSelector('#panel[data-view=pad] #pad-insp-svg svg.pv-art:not([hidden])', { timeout: 10000 });
        await sleep(500);
        await shot(page, 'pad-inspector-' + w + '-' + theme);
        await page.evaluate(() => IL.panel.close());
      }
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.evaluate(() => IL.setTheme('light'));
    // Full is the operator's choice from here on: the hero's renders keep it while the checks below switch the picture
    // and put it back
    await showHero(page, 'full');

    // ---- (5) picture modes ------------------------------------------------------------------------------------------
    const pm = await page.evaluate(() => {
      const bv = IL.run.bv;
      const cs = (sel) => getComputedStyle(bv.artSvg.querySelector(sel));
      bv.picture('quiet');
      const q = { quiet: bv.artSvg.classList.contains('pv-art--quiet'), silk: cs('.pv-a-silklines').display, cu: cs('.pv-a-cu').display,
        exposed: cs('.pv-a-exposed').display, bare: cs('.pv-a-exposed').fill, holes: cs('.pv-a-hole--pth').display, edge: cs('.pv-a-edge').display };
      bv.picture('off');
      const o = { hidden: bv.artSvg.hasAttribute('hidden'), display: getComputedStyle(bv.artSvg).display, art: bv.svg.classList.contains('pv--art'),
        board: getComputedStyle(bv.svg.querySelector('.pv-l-board')).display };
      bv.picture('full');
      const f = { silk: cs('.pv-a-silklines').display, art: bv.svg.classList.contains('pv--art'), shown: !bv.artSvg.hasAttribute('hidden') };
      return { q, o, f };
    });
    check('Quiet: .pv-art--quiet, silkscreen and copper under the mask hidden, finish, holes and edge kept', pm.q.quiet && pm.q.silk === 'none'
      && pm.q.cu === 'none' && pm.q.exposed !== 'none' && pm.q.bare === 'rgb(221, 213, 193)' && pm.q.holes !== 'none' && pm.q.edge !== 'none', pm.q);
    check('Off: the drawing hidden, no .pv--art, today\'s substrate back', pm.o.hidden && pm.o.display === 'none' && !pm.o.art && pm.o.board !== 'none', pm.o);
    check('Full: everything back', pm.f.silk !== 'none' && pm.f.art && pm.f.shown, pm.f);

    // ---- the whole board in Quiet: one thin path line, undone dots still rings (not solid discs like done ones) -----------
    const wq = await page.evaluate(() => {
      const bv = IL.run.bv;
      bv.picture('quiet');
      bv.setView('board');
      const cs = (n) => getComputedStyle(n);
      const out = {};
      for (const theme of ['light', 'dark']) {
        IL.setTheme(theme);
        const g = bv.svg.querySelector('.pv-l-path > .pv-cased--path');
        const core = g.querySelector('.pv-path'), l = g.querySelector('.pv-case--l'), d = g.querySelector('.pv-case--d');
        const pre = bv.svg.querySelector('.pv-path--pre');
        const dot = bv.svg.querySelector('circle.dot');
        const rPx = Number(dot.getAttribute('r')) / bv.k, ring = parseFloat(cs(dot).strokeWidth);
        out[theme] = { overlayQuiet: bv.svg.classList.contains('pv--quiet'), light: cs(l).display, dark: cs(d).display, core: cs(core).stroke,
          pre: cs(pre).stroke, preCase: cs(pre.closest('.pv-cased').querySelector('.pv-case--l')).display, thin: bv.svg.classList.contains('pv--dots-thin'),
          ring, hole: +(2 * (rPx - ring / 2)).toFixed(2), outer: +(2 * (rPx + ring / 2)).toFixed(2) };
      }
      IL.setTheme('light');
      bv.setView('dots');
      bv.picture('full');
      return out;
    });
    check('Quiet, light theme: the path one #3d4548 line without a casing, the done/preview trail #1a50b8 without the light casing',
      wq.light.overlayQuiet && wq.light.light === 'none' && wq.light.dark === 'none' && wq.light.core === 'rgb(61, 69, 72)'
      && wq.light.pre === 'rgb(26, 80, 184)' && wq.light.preCase === 'none', wq.light);
    check('Quiet, dark theme: the path a white core on the dark casing, the trail #8fb3f7 without the light casing',
      wq.dark.light === 'none' && wq.dark.dark !== 'none' && wq.dark.core === 'rgb(255, 255, 255)' && wq.dark.pre === 'rgb(143, 179, 247)'
      && wq.dark.preCase === 'none', wq.dark);
    check('whole board: small dots get a 1 px ring and keep a light centre >= 1.5 px across (an undone dot is not a solid disc)',
      ['light', 'dark'].every((t) => wq[t].thin && wq[t].ring === 1 && wq[t].hole >= 1.5), wq);

    // ---- (6) reference labels ---------------------------------------------------------------------------------------
    const lb = await page.evaluate(() => {
      const bv = IL.run.bv;
      bv.setView('board');
      const whole = { lb1: Array.from(bv.artSvg.querySelectorAll('.pv-lb1')).map((n) => getComputedStyle(n).display), k: bv.k };
      while (bv.zoomBy(1)) { /* to the maximum zoom */ }
      const r = bv.svg.getBoundingClientRect();
      const vis = Array.from(bv.artSvg.querySelectorAll('.pv-a-refs text')).filter((n) => {
        if (getComputedStyle(n).display === 'none') return false;
        const b = n.getBoundingClientRect();
        return b.width > 0 && b.left >= r.left && b.right <= r.right && b.top >= r.top && b.bottom <= r.bottom;
      });
      const m = vis.length ? vis[0].getScreenCTM() : null;
      const det = m ? m.a * m.d - m.b * m.c : null;
      const px = vis.length ? vis[0].getBoundingClientRect().height : null;
      while (bv.zoomBy(-1)) { /* back */ }
      bv.setView('dots');
      return { whole, visible: vis.length, det, px, text: vis.length ? vis[0].textContent : null };
    });
    check('whole board: the smallest labels (.pv-lb1) are hidden', lb.whole.lb1.length > 0 && lb.whole.lb1.every((d) => d === 'none'), lb.whole);
    check('maximum zoom: references show, read the right way round (positive screen determinant), >= 9 px', lb.visible > 0 && lb.det > 0 && lb.px >= 9, lb);

    // ---- (4) path preview clock -------------------------------------------------------------------------------------
    await page.click('#pv-play');
    check('#pv-play expands #run-preview', await page.isVisible('#run-preview'));
    // scrubbing while it plays pauses the play, and onChange hears of that pause (its contract: every start, pause, end)
    const was = await page.evaluate(() => {
      const pp = IL.boardFlow.preview(), own = pp.o.onChange;
      window.__ppEv = [];
      window.__ppOwn = own;
      pp.o.onChange = (st) => { window.__ppEv.push(st.playing); if (own) own(st); };
      return pp.state.playing;
    });
    await page.fill('#pv-scrub', '80');
    const sp = await page.evaluate(() => {
      const pp = IL.boardFlow.preview(), r = { events: window.__ppEv, playing: pp.state.playing };
      pp.o.onChange = window.__ppOwn;
      return r;
    });
    check('scrubbing while it plays pauses it and tells onChange', was && !sp.playing && sp.events.includes(false), Object.assign({ was }, sp));
    const c80 = await page.evaluate(() => ({ pre: document.querySelectorAll('#plan-svg circle.dot.pv-pre').length,
      text: document.getElementById('pv-readout').textContent, preview: IL.run.bv.svg.classList.contains('pv--preview'),
      ghost: document.querySelector('#plan-svg .pv-ghost').getAttribute('visibility'), n: IL.S.plan.dots.length }));
    check('scrub to 80: 80 dots .pv-pre, ghost shown', c80.pre === 80 && c80.preview && c80.ghost === 'visible', c80);
    check('readout names dot 81', new RegExp('^Dot 81 of ' + c80.n + ' · \\S+ · .+ · Z \\d+\\.\\d+ mm · \\d+:\\d\\d of \\d+:\\d\\d').test(c80.text), c80.text);
    await page.fill('#pv-scrub', String(c80.n));
    check('scrub to the end: "All N dots"', new RegExp('^All ' + c80.n + ' dots').test(await page.textContent('#pv-readout')), await page.textContent('#pv-readout'));
    await page.fill('#pv-scrub', '0');
    await page.click('#pv-speed button[data-speed="16"]');
    await page.click('#pv-play');
    const grew = await page.waitForFunction(() => document.querySelectorAll('#plan-svg circle.dot.pv-pre').length >= 5, null, { timeout: 5000 }).then(() => true, () => false);
    check('16× play: the dots appear', grew);
    await page.click('#pv-reset');
    const cl = await page.evaluate(() => ({ pre: document.querySelectorAll('#plan-svg circle.dot.pv-pre').length, row: document.getElementById('run-preview').hidden,
      preview: IL.run.bv.svg.classList.contains('pv--preview') }));
    check('Clear: no .pv-pre, the row collapsed', cl.pre === 0 && cl.row && !cl.preview, cl);
    // keyboard: Clear hides its own row, so the focus goes back to #pv-play (not to the page)
    await page.evaluate(() => IL.boardFlow.preview().seek(10));
    await page.focus('#pv-reset');
    await page.keyboard.press('Enter');
    const kf = await page.evaluate(() => ({ id: document.activeElement && document.activeElement.id, row: document.getElementById('run-preview').hidden }));
    check('keyboard: Clear collapses the row and gives the focus back to #pv-play', kf.row && kf.id === 'pv-play', kf);
    // the row fits at 1440, 1280 and 1024 px, and its readout is never cut (it takes a line of its own when narrow); the
    // drawing keeps at least 280 px of height with the row open (§5.13: at 1024 × 768 the page scrolls instead)
    for (const [w, h] of [[1440, 900], [1280, 800], [1024, 768]]) {
      await page.setViewportSize({ width: w, height: h });
      await page.evaluate(() => { IL.boardFlow.preview().seek(40); });
      await sleep(400);
      const lay = await page.evaluate(() => {
        const r = document.getElementById('run-preview'), bar = document.querySelector('#run-hero .stage-bar');
        const hh = (n) => Math.round(n.getBoundingClientRect().height);
        const ro = document.getElementById('pv-readout'), play = document.getElementById('pv-play');
        return { sw: r.scrollWidth, cw: r.clientWidth, barSw: bar.scrollWidth, barCw: bar.clientWidth, svgH: hh(document.getElementById('plan-svg')),
          stage: hh(document.querySelector('#run-hero .hero__stage')), row: hh(r), readSw: ro.scrollWidth, readCw: ro.clientWidth,
          playSw: play.scrollWidth, playCw: play.clientWidth };
      });
      if (w !== 1440) await shot(page, 'layout-preview-row-' + w);
      check('#run-preview and the stage bar fit at ' + w + ' px, #plan-svg >= 280 px tall',
        lay.sw <= lay.cw + 1 && lay.barSw <= lay.barCw + 1 && lay.svgH >= 280, lay);
      check('the readout is never cut at ' + w + ' px ("… · 0:24 of 1:36" stays), #pv-play is not squeezed',
        lay.readSw <= lay.readCw + 1 && lay.playSw <= lay.playCw + 1, lay);
      await page.evaluate(() => IL.boardFlow.preview().clear());
    }
    await page.setViewportSize({ width: 1440, height: 900 });

    // ---- pad inspector ----------------------------------------------------------------------------------------------
    await page.evaluate(() => IL.padInspect.open({ padId: 'R1.1' }, { bv: IL.run.bv }));
    await page.waitForSelector('#panel[data-view=pad] #pad-insp-svg svg.pv-art:not([hidden])', { timeout: 10000 });
    await page.waitForFunction(() => /Chip|chip/.test(document.getElementById('pad-insp-kv').textContent), null, { timeout: 10000 });
    const pi = await page.evaluate(() => {
      const pad = IL.S.plan.pads.find((x) => x.padId === 'R1.1');
      return { title: document.getElementById('panel-bar-title').textContent, rule: document.getElementById('pad-insp-rule').textContent,
        dots: document.querySelectorAll('#pad-insp-dots tbody tr').length, dotCount: pad.dotCount,
        rows: Array.from(document.querySelectorAll('#pad-insp-pads tr[data-pad]')).map((r) => r.dataset.pad + ':' + r.getAttribute('aria-selected')),
        sel: document.querySelectorAll('#pad-insp-svg svg rect.pad.sel').length, labels: document.querySelectorAll('#pad-insp-svg .pv-dotlabel').length,
        hero: document.querySelectorAll('#plan-svg .pv-l-sel .pv-sel').length, edit: document.getElementById('pad-insp-edit').textContent,
        kv: document.getElementById('pad-insp-kv').textContent, part: document.getElementById('pad-insp-part').textContent };
    });
    check('inspector: panel "Pad R1.1", pads of R1 with R1.1 selected', pi.title === 'Pad R1.1' && pi.rows.includes('R1.1:true') && pi.rows.includes('R1.2:false'), pi);
    check('inspector: one sentence on the rule', /^Chosen because the package name contains “0603” \(pad type Chip 0603, v\d+, \w+\)\./.test(pi.rule), pi.rule);
    check('inspector: dots table = the pad\'s dot count, the selected pad .sel, path numbers drawn', pi.dots === pi.dotCount && pi.sel === 1 && pi.labels >= 2, pi);
    // the amount of PROFILE_CALIBRATION_DESIGN.md §13.8: the planned dots and their mass, the target, the stencil's for comparison
    check('inspector: the amount (planned dots × mass, the target, what a 0.10 mm stencil would print) and the edit link',
      /Planned: 1 half.turn dot × \d+\.\d\d mg = \d+\.\d\d mg \(target \d+\.\d\d mg at your 0603 rate; a 0\.10 mm stencil would print \d+\.\d\d mg\)\./.test(pi.kv.replace(/\u00a0/g, ' '))
      && /^Edit pad type Chip 0603$/.test(pi.edit), { kv: pi.kv, edit: pi.edit });
    check('hero marks the part while the inspector is open', pi.hero >= 1, pi.hero);
    const pz = await page.evaluate(() => {
      const host = document.getElementById('pad-insp-svg');
      const v = IL.boardview.of(host);
      const c = host.querySelector('circle.dot');
      const outer = Number(c.getAttribute('r')) + parseFloat(getComputedStyle(c).strokeWidth) / 2 * v.k;
      const boxes = Array.from(host.querySelectorAll('.pv-dotlabel')).map((n) => n.getBoundingClientRect());
      const overlap = boxes.some((a, i) => boxes.some((b, j) => j > i && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom));
      const ty = document.getElementById('pad-insp-types');
      const sentence = IL.padInspect.ruleSentence({ typeKey: 'chip-0805', typeVersion: 1, typeStatus: 'characterized', packageId: 'R_0805_2012Metric',
        typeRule: { kind: 'pattern', detail: '(^|[^0-9])0805([^0-9]|$)', matched: '_0805_' } }, null);
      const notStarted = IL.padInspect.ruleSentence({ typeKey: 'chip-0805', typeVersion: 1, typeStatus: 'not-started', packageId: 'R_0805_2012Metric',
        typeRule: { kind: 'pattern', detail: '(^|[^0-9])0805([^0-9]|$)', matched: '0805', sentence: 'Chosen because the package name contains “0805”.' } }, null);
      // a dot's own size when the plan gives it (its pad type's turn), else the plan's dot size
      return { outer: +outer.toFixed(4), half: (Number(c.dataset.d) > 0 ? Number(c.dataset.d) : IL.S.plan.dot.diameterMm) / 2, clamped: v.svg.classList.contains('pv--dots-clamped'), overlap,
        types: ty.hidden ? null : ty.textContent, one: document.getElementById('pad-insp-pads').classList.contains('pad-insp-pads--one'),
        typeCol: getComputedStyle(document.querySelector('#pad-insp-pads th.pad-insp-type')).display, sentence, notStarted };
    });
    check('inspector: a dot\'s outer edge (circle + half its ring) is its real size (its own diameter, else the plan\'s)', !pz.clamped && Math.abs(pz.outer - pz.half) <= 0.005, pz);
    check('inspector: path numbers do not overlap', !pz.overlap, pz);
    check('inspector: one pad type for both pads is said once, not in every row', pz.types === 'Pad type of both pads: Chip 0603' && pz.one
      && pz.typeCol === 'none', pz);
    check('inspector: the rule names the text without the pattern\'s separators ("_0805_" → “0805”)', /contains “0805” \(pad type chip-0805/.test(pz.sentence), pz.sentence);
    check('inspector: the pad type\'s status in words ("not-started" → "not started")',
      /^Chosen because the package name contains “0805” \(pad type chip-0805, v1, not started\)\.$/.test(pz.notStarted), pz.notStarted);
    await page.click('#pad-insp-svg rect.pad[data-pad="R1.2"]');
    await page.waitForFunction(() => document.getElementById('panel-bar-title').textContent === 'Pad R1.2', null, { timeout: 5000 }).catch(() => {});
    check('a pad click in the inspector selects that pad', (await page.textContent('#panel-bar-title')) === 'Pad R1.2', await page.textContent('#panel-bar-title'));
    // keyboard: Enter on a pad row's button selects that pad; the table is rebuilt, the focus stays on that row
    await page.focus('#pad-insp-pads button[aria-label="Inspect pad R1.1"]');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.getElementById('panel-bar-title').textContent === 'Pad R1.1', null, { timeout: 5000 }).catch(() => {});
    const kp = await page.evaluate(() => ({ title: document.getElementById('panel-bar-title').textContent,
      focus: document.activeElement && document.activeElement.getAttribute('aria-label') }));
    check('keyboard: Enter on a pad row selects it and keeps the focus on that row', kp.title === 'Pad R1.1' && kp.focus === 'Inspect pad R1.1', kp);
    const before = await page.textContent('#pad-insp-part');
    await page.click('#pad-insp-next');
    await sleep(300);
    check('next part in path order', (await page.textContent('#pad-insp-part')) !== before, await page.textContent('#pad-insp-part'));
    await page.click('#pad-insp-cam');
    await page.waitForFunction(() => /Top camera/.test(document.getElementById('panel-bar-title').textContent), null, { timeout: 5000 }).catch(() => {});
    check('Top camera opens the camera panel', /Top camera/.test(await page.textContent('#panel-bar-title')), await page.textContent('#panel-bar-title'));
    await page.keyboard.press('Escape');
    await sleep(300);
    const back = await page.evaluate(() => ({ pushed: IL.panel.isPushed(), sel: document.querySelectorAll('#plan-svg .pv-l-sel .pv-sel').length }));
    check('Esc goes back; the hero selection is cleared', !back.pushed && back.sel === 0, back);
    // a drawn-only part (a mounting hole) opens the same view with its reason; previous goes to the last part in path order
    await page.evaluate(() => IL.padInspect.open({ ref: 'H1' }, { bv: IL.run.bv }));
    await page.waitForFunction(() => { const w = document.getElementById('pad-insp-why'); return w && !w.hidden; }, null, { timeout: 10000 }).catch(() => {});
    const hp = await page.evaluate(() => ({ title: document.getElementById('panel-bar-title').textContent, why: document.getElementById('pad-insp-why').textContent,
      table: document.getElementById('pad-insp-pads').hidden, hero: document.querySelectorAll('#plan-svg .pv-l-sel .pv-sel').length,
      last: IL.padInspect.partsOf(IL.S.plan).slice(-1)[0].ref }));
    await page.click('#pad-insp-prev');
    await sleep(300);
    hp.prev = await page.evaluate(() => IL.padInspect.current().ref);
    check('a drawn-only part: "Part H1", its reason, no pads table, outlined on the hero; previous goes to the last part',
      hp.title === 'Part H1' && /^Through-hole or drawn-only part: not dispensed\.$/.test(hp.why) && hp.table && hp.hero >= 1 && hp.prev === hp.last, hp);
    await page.keyboard.press('Escape');
    await sleep(300);

    // ---- art-only mode (the upload preview, the hero before planning) ---------------------------------------------------
    const ao = await page.evaluate(async () => {
      const host = document.createElement('div');
      host.id = 'pw-art-only';
      host.style.cssText = 'position:fixed;left:24px;top:96px;width:520px;height:360px;z-index:50;background:var(--pv-bg);border:1px solid var(--line);border-radius:12px';
      document.body.appendChild(host);
      const v = IL.boardview.create(host, { lite: true, aria: 'Upload preview' });
      const art = await IL.boardart.get(IL.S.plan.board.art.id);
      v.setBoard({ art, toMachine: [1, 0, 0, 1, 0, 0] });
      await new Promise((r) => requestAnimationFrame(() => setTimeout(r, 0)));
      const cs = (sel) => getComputedStyle(v.artSvg.querySelector(sel)).display;
      const a = { board: v.artSvg.classList.contains('pv-art--board'), origin: cs('.pv-a-origin'), paste: cs('.pv-a-paste'), art: v.svg.classList.contains('pv--art'),
        vb: v.svg.getAttribute('viewBox') };
      v.setAffine([0, 1, -1, 0, 200, 100]);   // the seat changed: 90° at (200, 100)
      const t = v.artSvg.querySelector('g.pv-l-art').getAttribute('transform');
      v.update({ cam: { x: 150, y: 140 } });
      const cam = { vis: v.svg.querySelector('.pv-cam').getAttribute('visibility'), scale: v.svg.querySelector('.pv-scale').getAttribute('visibility'), vb: v.svg.getAttribute('viewBox') };
      // the scale bar is 5 mm of the drawing, its label about 12 px on screen whatever the zoom
      const bar = v.svg.querySelector('.pv-scale path:not(.pv-case)'), lab = v.svg.querySelector('.pv-scale text');
      cam.barPx = +bar.getBoundingClientRect().width.toFixed(1);
      cam.fivePx = +(5 / v.k).toFixed(1);
      cam.labelPx = +lab.getBoundingClientRect().height.toFixed(1);
      v.setAffine([1, 0, 0, 1, 0, 0]);
      v.update({});
      return { a, t, cam };
    });
    check('art-only mode: paste openings and the origin marker shown, the view on the board', ao.a.board && ao.a.origin !== 'none' && ao.a.paste !== 'none'
      && ao.a.art && /^-3 -78 81 81$/.test(ao.a.vb), ao.a);
    check('art-only mode: a new seat changes the transform only; the camera crosshair and the 5 mm scale bar show, the view takes the camera in',
      ao.t === 'matrix(0 -1 -1 0 200 -100)' && ao.cam.vis === 'visible' && ao.cam.scale === 'visible', ao);
    check('scale bar: 5 mm long, its "5 mm" label 8 to 24 px tall on screen', Math.abs(ao.cam.barPx - ao.cam.fivePx) <= 1.5
      && ao.cam.labelPx >= 8 && ao.cam.labelPx <= 24, ao.cam);
    await sleep(200);
    await shot(page, 'art-only-preview-1440-light', await page.locator('#pw-art-only').boundingBox());
    await page.evaluate(() => { const h = document.getElementById('pw-art-only'); IL.boardview.of(h).destroy(); h.remove(); });

    // ---- thumbnails ---------------------------------------------------------------------------------------------------
    const th = await page.evaluate(async () => {
      const id = IL.S.plan.board.art.id;
      const art = await IL.boardart.get(id);
      const u1 = IL.boardart.thumbUrl(art, { w: 96, h: 64, id });
      const u2 = IL.boardart.thumbUrl(await IL.boardart.get(id), { w: 96, h: 64, id });
      const img = new Image();
      img.src = u1;
      await img.decode();
      return { blob: u1.startsWith('blob:'), same: u1 === u2, w: img.naturalWidth, h: img.naturalHeight, artId: art.id === id };
    });
    check('thumbnail: a blob: image of 96 × 64 px, rendered once per drawing', th.blob && th.same && th.w === 96 && th.h === 64, th);
    check('IL.boardart.get(id) gives the drawing its id (a view builds once per art id)', th.artId, th);

    // ---- (3) contrast: both themes × 8 mask palettes × Full and Quiet --------------------------------------------------
    const ct = await page.evaluate(async () => {
      const parse = (s) => {
        s = String(s).trim();
        let m = /^#([0-9a-f]{6})$/i.exec(s);
        if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
        m = /rgba?\(([^)]+)\)/.exec(s);
        return m ? m[1].split(/[ ,/]+/).filter(Boolean).slice(0, 3).map(Number) : null;
      };
      const lum = (c) => { const v = c.map((x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
      const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;left:0;top:0;width:400px;height:300px;opacity:0;pointer-events:none';
      document.body.appendChild(host);
      const v = IL.boardview.create(host, { lite: true });
      v.setPlan(IL.S.plan, {});
      v.setArt(await IL.boardart.get(IL.S.plan.board.art.id));
      const d0 = IL.S.plan.dots[0];
      v.update({ next: 1, check: { index: 2, label: 'x' }, probe: { targets: [{ x: d0.x, y: d0.y }], points: [{ x: d0.x, y: d0.y }, { x: d0.x + 5, y: d0.y }, { x: d0.x, y: d0.y + 5 }], margin: 3.5 } });
      v.preview({ n: 3, ghost: { x: d0.x, y: d0.y } });
      const legend = document.createElement('div');
      legend.className = 'hero--art';
      legend.innerHTML = '<i class="lg lg-dot"></i><i class="lg lg-done"></i><i class="lg lg-next"></i><i class="lg lg-dirty"></i><i class="lg lg-pad"></i>';
      host.appendChild(legend);
      const pad = v.svg.querySelector('rect.pad'), dot = v.svg.querySelector('circle.dot');
      const cs = (n) => getComputedStyle(n);
      const stateCol = (cls, prop) => { pad.classList.add(cls); const c = parse(cs(pad)[prop]); pad.classList.remove(cls); return c; };
      const cased = ['.pv-next', '.pv-check', '.pv-first', '.pv-path', '.pv-hull', '.pv-path--pre', '.pv-sel'];
      v.select({ padIds: [pad.dataset.pad], refs: [IL.S.plan.pads[0].placementId] });
      const fails = [];
      let worstIn = Infinity, worstCase = Infinity;
      const same = (a1, b1) => a1 && b1 && a1.every((x, i) => Math.abs(x - b1[i]) <= 1);
      const MASKS = ['slate', 'green', 'red', 'blue', 'purple', 'black', 'white', 'yellow'];
      const maskEl = v.artSvg.querySelector('.pv-a-mask');
      for (const theme of ['light', 'dark']) {
        IL.setTheme(theme);
        for (const mask of MASKS) {
          v.artSvg.dataset.mask = mask;
          for (const pic of ['full', 'quiet']) {
            v.picture(pic);
            const a = cs(v.artSvg);
            const bare = parse(a.getPropertyValue('--pv-a-bare')), sub = parse(a.getPropertyValue('--pv-a-sub'));
            // the mask as painted (not the token): the palette at .86 in Full, the stage-toned mask of §5.3 in Quiet
            const mk = parse(cs(maskEl).fill), op = Number(cs(maskEl).fillOpacity);
            const want = pic === 'quiet' ? parse(theme === 'dark' ? '#243034' : '#aab2b1') : parse(IL.boardart.MASKS[mask]);
            if (!same(mk, want) || Math.abs(op - (pic === 'quiet' ? 1 : 0.86)) > 0.01) fails.push([theme, mask, pic, 'mask painted', mk, op]);
            const comp = mk.map((x, i) => Math.round(x * op + sub[i] * (1 - op)));
            // rule 1: marks inside a pad, on the finish
            const inPad = { aperture: parse(cs(pad).stroke), blocked: stateCol('blocked', 'stroke'), pasted: stateCol('pasted', 'stroke'),
              check: stateCol('check', 'stroke'), dirty: stateCol('dirty', 'fill'), dot: parse(cs(dot).stroke) };
            dot.classList.add('done'); inPad.done = parse(cs(dot).fill); dot.classList.remove('done');
            for (const [k, c] of Object.entries(inPad)) {
              const r = ratio(c, bare);
              worstIn = Math.min(worstIn, r);
              if (r < 3) fails.push([theme, mask, pic, k, r.toFixed(2)]);
            }
            // rule 2: cased marks, the better casing tone against the finish and the mask composite
            for (const sel of cased) {
              const core = v.svg.querySelector(sel);
              const g = core && core.closest('.pv-cased');
              const l = g && g.querySelector('.pv-case--l'), d = g && g.querySelector('.pv-case--d');
              if (!l || !d) { fails.push([sel, 'no casing']); continue; }
              // only the tones drawn count: a white-core mark (the path) draws no light casing, its core is the light
              // tone; on the light theme's Quiet mask the path draws no casing at all, its core alone must stand out
              const tones = [l, d].filter((n) => cs(n).display !== 'none').map((n) => parse(cs(n).stroke));
              if (cs(l).display === 'none') tones.push(parse(cs(core).stroke));
              for (const bg of [bare, comp]) {
                const r = Math.max(...tones.map((t) => ratio(t, bg)));
                worstCase = Math.min(worstCase, r);
                if (r < 3) fails.push([theme, mask, pic, sel, r.toFixed(2)]);
              }
            }
            for (const sel of ['.pv-ghost', '.pv-probe', '.pv-callout']) {
              const g = v.svg.querySelector(sel);
              if (g && !(g.querySelector('.pv-case--l') && g.querySelector('.pv-case--d'))) fails.push([sel, 'no casing']);
            }
            // rule 4: legend swatches on the marks' colours
            const sw = (cls, prop) => parse(cs(legend.querySelector('.' + cls))[prop]);
            dot.classList.add('done'); const doneFill = parse(cs(dot).fill); dot.classList.remove('done');
            if (!same(sw('lg-dot', 'borderTopColor'), parse(cs(dot).stroke))) fails.push([theme, 'lg-dot']);
            if (!same(sw('lg-done', 'backgroundColor'), doneFill)) fails.push([theme, 'lg-done']);
            if (!same(sw('lg-next', 'borderTopColor'), parse(cs(v.svg.querySelector('.pv-next')).stroke))) fails.push([theme, 'lg-next']);
            if (!same(sw('lg-dirty', 'backgroundColor'), stateCol('dirty', 'fill'))) fails.push([theme, 'lg-dirty']);
          }
        }
      }
      IL.setTheme('light');
      v.destroy();
      host.remove();
      return { fails: fails.slice(0, 20), nFails: fails.length, worstIn: worstIn.toFixed(2), worstCase: worstCase.toFixed(2) };
    });
    check('contrast: in-pad marks >= 3:1 on the finish, the better casing tone >= 3:1 on the finish and every mask composite (2 themes × 8 palettes × Full/Quiet), legend swatches = marks',
      ct.nFails === 0, ct);

    // ---- dark theme: a dark board stands out from the dark stage (a black mask, any board in Quiet) -------------------
    const rim = await page.evaluate(() => {
      const rgba = (s) => { const m = /rgba?\(([^)]+)\)/.exec(String(s)); const v = m ? m[1].split(/[ ,/]+/).filter(Boolean).map(Number) : []; return v.length >= 3 ? [v[0], v[1], v[2], v.length > 3 ? v[3] : 1] : null; };
      const lum = (c) => { const v = c.slice(0, 3).map((x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }); return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]; };
      const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
      const over = (c, bg) => [0, 1, 2].map((i) => c[i] * c[3] + bg[i] * (1 - c[3]));
      const out = {};
      for (const theme of ['dark', 'light']) {
        IL.setTheme(theme);
        const sub = getComputedStyle(IL.run.bv.artSvg.querySelector('.pv-a-sub'));
        const stage = rgba(getComputedStyle(document.querySelector('#run-hero .hero__stage')).backgroundColor);
        const black = over([0x15, 0x18, 0x1a, 0.86], rgba(sub.fill));   // the black mask over the substrate
        const st = rgba(sub.stroke);
        out[theme] = { stroke: sub.stroke, width: parseFloat(sub.strokeWidth), mask: +ratio(black, stage).toFixed(2),
          rim: st ? +ratio(over(st, stage), stage).toFixed(2) : null };
      }
      IL.setTheme('light');
      return out;
    });
    check('dark theme: a 3 px light rim outside the board edge, >= 3:1 on the stage (a black mask alone is about 1.3:1); the light theme draws none',
      rim.dark.rim >= 3 && rim.dark.width >= 6 && rim.dark.mask < 3 && rim.light.stroke === 'none', rim);

    // ---- polls only toggle classes; a hidden layer hides its casing too -----------------------------------------------
    const ch = await page.evaluate(async () => {
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;left:0;top:0;width:400px;height:300px;opacity:0;pointer-events:none';
      document.body.appendChild(host);
      const v = IL.boardview.create(host, {});   // not lite: callouts and dirty rings are drawn
      const art = await IL.boardart.get(IL.S.plan.board.art.id);
      v.setPlan(IL.S.plan, {});
      v.setArt(art);
      const p0 = IL.S.plan.pads[0];
      const sel = () => ({ padIds: [p0.padId], refs: [p0.placementId] });
      v.select(sel());
      v.dotLabels([0, 1]);
      const s0 = v.svg.querySelector('.pv-l-sel').firstChild, l0 = v.svg.querySelector('.pv-l-labels').firstChild;
      for (let i = 0; i < 3; i++) { v.select(sel()); v.dotLabels([0, 1]); v.setArt(art); }   // what a render does on every poll
      const kept = !!(s0 && s0.isConnected && l0 && l0.isConnected);
      v.update({ dirty: new Map([[p0.padId, 0.4]]) });
      const rings = v.svg.querySelectorAll('.pv-cased--dirty .pv-case').length;
      v.layers(['dirty']);
      const shown = Array.from(v.svg.querySelectorAll('.pv-cased--dirty .pv-case')).filter((n) => getComputedStyle(n).visibility !== 'hidden').length;
      v.destroy();
      host.remove();
      return { kept, rings, shown };
    });
    check('the same selection, path numbers and drawing again (a poll) rebuild nothing', ch.kept, ch);
    check('dirty layer hidden: the dirty rings\' casing is hidden too', ch.rings > 0 && ch.shown === 0, ch);

    // ---- polls never touch the drawing; a re-plan of the same board moves it and rebuilds nothing of it (§5.5) ------------
    const rp = await page.evaluate(async () => {
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;left:0;top:0;width:400px;height:300px;opacity:0;pointer-events:none';
      document.body.appendChild(host);
      const v = IL.boardview.create(host, {});
      const p = IL.S.plan, n = p.dots.length;
      v.setPlan(p, {});
      v.setArt(await IL.boardart.get(p.board.art.id));
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));   // the first layout has run
      const root = v.artSvg.querySelector('g.pv-l-art'), pad0 = v.svg.querySelector('rect.pad'), t0 = root.getAttribute('transform');
      const mo = new MutationObserver(() => {});
      mo.observe(v.artSvg, { subtree: true, childList: true, attributes: true, characterData: true });
      for (let i = 0; i < 12; i++) {   // what the hero does on every poll: the same plan, then the overlay state
        v.setPlan(p, {});
        v.update({ done: new Set(Array.from({ length: (i * 7) % n }, (_, j) => j)), next: (i * 7) % n,
          dirty: new Map(i % 3 ? [] : [[p.pads[i].padId, 0.3]]), check: i % 4 ? null : { index: i, label: 'In progress' } });
      }
      const artMuts = mo.takeRecords().length;
      mo.disconnect();
      const kept = v.svg.querySelector('rect.pad') === pad0;
      const m = p.board.toMachine;
      v.setPlan(Object.assign({}, p, { planId: p.planId + '-re', board: Object.assign({}, p.board, { toMachine: [m[0], m[1], m[2], m[3], m[4] + 1, m[5] + 2] }) }), {});
      const out = { artMuts, kept, sameRoot: v.artSvg.querySelector('g.pv-l-art') === root, moved: root.getAttribute('transform') !== t0,
        shown: !v.artSvg.hasAttribute('hidden') };
      v.destroy();
      host.remove();
      return out;
    });
    check('polls (the same plan, then update()) never touch the drawing and rebuild no pad; a re-plan only moves the drawing',
      rp.artMuts === 0 && rp.kept && rp.sameRoot && rp.moved && rp.shown, rp);

    // ---- path numbers on a fine-pitch row, labels of pad-less footprints ----------------------------------------------
    const fp = await page.evaluate(() => {
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;left:0;top:0;width:360px;height:240px;opacity:0;pointer-events:none';
      document.body.appendChild(host);
      const v = IL.boardview.create(host, { lite: true });
      // a 48-pin QFP: 4 sides of 12 pads 0.3 × 1.5 mm at 0.5 mm pitch around a 7 mm body, one 0.6 mm dot each (wider than
      // the pad: the plan's real size), path order around the part
      const pads = [], dots = [];
      for (let i = 0; i < 48; i++) {
        const side = Math.floor(i / 12), t = (i % 12 - 5.5) * 0.5, a = side * Math.PI / 2;
        const x = 100 + Math.cos(a) * 4.2 - Math.sin(a) * t, y = 100 + Math.sin(a) * 4.2 + Math.cos(a) * t;
        pads.push({ padId: 'U1.' + (i + 1), placementId: 'U1', x, y, w: 1.5, h: 0.3, rotation: side * 90, dotCount: 1 });
        dots.push({ index: i, padId: 'U1.' + (i + 1), x, y, dispenseZ: 3, rotationDeg: 180 });
      }
      v.setPlan({ planId: 'pw-fine', pads, dots, dot: { diameterMm: 0.6 } }, {});
      v.fit({ x0: 93, y0: 93, x1: 107, y1: 107 });
      v.dotLabels(dots.map((d) => d.index));
      const nodes = Array.from(host.querySelectorAll('.pv-dotlabel'));
      // the ink of each number: its glyph box's width, the digits' height (0.75 em, not the line box) and the 3 px casing
      const boxes = nodes.map((n) => {
        const r = n.getBoundingClientRect(), em = parseFloat(getComputedStyle(n).fontSize) / v.k || 12, cy = (r.top + r.bottom) / 2;
        const hh = (0.75 * em + 3) / 2;
        return { left: r.left - 1.5, right: r.right + 1.5, top: cy - hh, bottom: cy + hh };
      });
      const overlap = boxes.some((a, i) => boxes.some((b, j) => j > i && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom));
      const first = nodes.map((n) => n.textContent);
      // every number outside the pad ring, next to its own dot (within 1.5 mm)
      const near = nodes.every((n) => {
        const d = dots[Number(n.textContent) - 1], x = Number(n.getAttribute('x')), y = -Number(n.getAttribute('y'));
        return Math.hypot(x - d.x, y - d.y) <= 1.5 && Math.hypot(x - 100, y - 100) > Math.hypot(d.x - 100, d.y - 100);
      });
      // the selection outline of a drawn QFP goes round its leads (its body box, KiCad's fab outline, lies inside them)
      v.setPlan({ planId: 'pw-fine-art', pads, dots, dot: { diameterMm: 0.6 }, board: { toMachine: [1, 0, 0, 1, 0, 0], widthMm: 200, heightMm: 200 } }, {});
      v.setArt({ id: 'pw-qfp', size: [200, 200], outline: { d: 'M0 0H200V200H0Z' }, layers: {}, parts: [
        { ref: 'U1', kind: 'placement', pkg: 'LQFP-48', x: 100, y: 100, a: 0, box: [-3.5, -3.5, 3.5, 3.5], pads: 48, label: null }] });
      v.fit({ x0: 93, y0: 93, x1: 107, y1: 107 });
      v.select({ padIds: [], refs: ['U1'] });
      const so = v.svg.querySelector('.pv-l-sel .pv-sel').getBoundingClientRect();
      const inside = Array.from(v.svg.querySelectorAll('rect.pad')).every((n) => {
        const b = n.getBoundingClientRect();
        return b.left >= so.left - 0.5 && b.right <= so.right + 0.5 && b.top >= so.top - 0.5 && b.bottom <= so.bottom + 0.5;
      });
      const sel = { inside, w: +so.width.toFixed(1) };
      v.destroy();
      host.remove();
      // a footprint without pads and without a KiCad label (a logo) gets no reference; one with pads gets one at its body
      const b = IL.boardart.build({ size: [10, 10], outline: { d: 'M0 0H10V10H0Z' }, layers: {}, parts: [
        { ref: 'Silkscreen_Front', kind: 'art', pads: 0, x: 5, y: 5, a: 0, box: [-4, -4, 4, 4], label: null },
        { ref: 'R9', kind: 'placement', pads: 2, x: 2, y: 2, a: 0, box: [-1, -0.5, 1, 0.5], label: null }] }, {});
      const refs = Array.from(b.root.querySelectorAll('.pv-a-refs text')).map((n) => n.textContent);
      const fab = Array.from(b.root.querySelectorAll('.pv-a-refs text.pv-a-ref--fab')).map((n) => n.textContent);
      const printed = IL.boardart.build({ size: [10, 10], outline: { d: 'M0 0H10V10H0Z' }, layers: {}, parts: [
        { ref: 'R8', kind: 'placement', pads: 2, x: 2, y: 2, a: 0, box: [-1, -0.5, 1, 0.5], label: { x: 2, y: 3.2, a: 0, h: 1 } }] }, {});
      const silk = printed.root.querySelectorAll('.pv-a-refs text.pv-a-ref--fab').length;
      return { n: boxes.length, overlap, near, first, refs, fab, silk, sel };
    });
    check('fine pitch (48-pin QFP): path numbers outside the pad ring, next to their dots, never overlapping; a number with no free place is left out, the ends kept',
      !fp.overlap && fp.near && fp.n >= 16 && fp.n < 48 && fp.first.includes('1') && fp.first.includes('48'), fp);
    check('a pad-less footprint without a KiCad label gets no reference label; a part with pads does', fp.refs.join() === 'R9', fp.refs);
    check('a reference the board does not print (no KiCad label) is drawn as fab text (.pv-a-ref--fab); a silkscreen one is not',
      fp.fab.join() === 'R9' && fp.silk === 0, { fab: fp.fab, silk: fp.silk });
    check('the selection outline of a drawn QFP goes round its leads, not between its body and its pads', fp.sel.inside, fp.sel);

    // ---- (8) destroy: no ResizeObserver left after 20 create/destroy cycles ---------------------------------------------
    const ds = await page.evaluate(async () => {
      const RO = window.ResizeObserver;
      let active = 0;
      window.ResizeObserver = class extends RO {
        observe(t, o) { if (!this.on) { this.on = true; active++; } return super.observe(t, o); }
        disconnect() { if (this.on) { this.on = false; active--; } return super.disconnect(); }
      };
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;left:-3000px;top:0;width:300px;height:200px';
      document.body.appendChild(host);
      const art = await IL.boardart.get(IL.S.plan.board.art.id);
      for (let i = 0; i < 20; i++) {
        const v = IL.boardview.create(host, { lite: true });
        v.setPlan(IL.S.plan, {});
        v.setArt(art);
        v.preview({ n: 3, ghost: null });
        v.destroy();
      }
      const out = { active, svgs: host.querySelectorAll('svg').length, of: IL.boardview.of(host) };
      window.ResizeObserver = RO;
      host.remove();
      return out;
    });
    check('20 create/destroy cycles leave no ResizeObserver and no SVG', ds.active === 0 && ds.svgs === 0 && ds.of === null, ds);

    // ---- Records map (H4): a 20-dot SIM run, then its done-dot map on the drawing ---------------------------------------
    const planId = await page.evaluate(() => IL.S.plan.planId);
    const run = await (await page.request.post(BASE + 'api/run', { data: { planId, maxDots: 20, wait: true }, timeout: 600000 })).json();
    const runId = run && run.result && run.result.runId;
    check('a 20-dot run on the simulator (for the Records map)', !!runId && run.result.status === 'OK', run && (run.result || run));
    if (runId) {
      await page.evaluate((id) => IL.go('#records/boards/' + id), runId);
      // the map is rebuilt once the run's dot timings arrive (its done dots); boardflow draws the board under the rebuilt one
      await page.waitForSelector('.rec-mapbox svg.pv:not(.pv-art) circle.dot.done', { timeout: 30000 });
      await sleep(300);
      const gr = await recordsArt(page);
      const rec = await page.evaluate(() => ({ edge: !!document.querySelector('.rec-mapbox .pv-art .pv-a-edge'),
        shown: !document.querySelector('.rec-mapbox svg.pv-art').hasAttribute('hidden'),
        quiet: document.querySelector('.rec-mapbox svg.pv-art').classList.contains('pv-art--quiet'),
        done: document.querySelectorAll('.rec-mapbox circle.dot.done').length }));
      check('Records: the map draws the plan\'s board (.rec-mapbox .pv-art .pv-a-edge, Quiet) with its done dots', gr.ok && rec.edge && rec.shown && rec.quiet && rec.done === 20, rec);
      for (const [w, h] of [[1440, 900], [1280, 800]]) {
        await page.setViewportSize({ width: w, height: h });
        for (const theme of ['light', 'dark']) {
          await page.evaluate((t) => IL.setTheme(t), theme);
          await recordsArt(page);
          await sleep(400);
          await shot(page, 'records-map-' + w + '-' + theme);
        }
      }
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.evaluate(() => IL.setTheme('light'));
    }

    // ---- (2) the matrix sign at 30° ---------------------------------------------------------------------------------
    // the session's board again, at its own origin and board top Z (within 2 mm of the needle frame), turned 30°
    const src = await page.evaluate(() => IL.S.plan.source);
    await page.evaluate(() => IL.go('#run/board'));
    await page.waitForFunction((f) => [...document.querySelectorAll('#board-select option')].some((o) => o.value === 'file:' + f), src.file, { timeout: 15000 });
    await page.selectOption('#board-select', 'file:' + src.file);
    await page.fill('#board-x', String(src.x));
    await page.fill('#board-y', String(src.y));
    await page.fill('#board-z', String(src.z));
    await page.fill('#board-rot', '30');
    await page.click('#btn-plan');
    await waitPlan(page, 30);
    await showHero(page, 'full');
    await sleep(400);
    const al = await page.evaluate(() => {
      const bv = IL.run.bv, p = IL.S.plan, art = bv.art;
      const g = bv.artSvg.querySelector('g.pv-l-art');
      const ctmA = g.getScreenCTM(), ctmO = bv.svg.getScreenCTM();
      const exposed = bv.artSvg.querySelector('.pv-a-exposed');
      const r1 = art.parts.find((x) => x.ref === 'R1');
      const a = r1.a * Math.PI / 180;
      // pad 1 of R_0603_1608Metric sits at (−0.825, 0) in the part frame (KiCad's footprint, the pad type's pitch)
      const bp = { x: r1.x - 0.825 * Math.cos(a), y: r1.y - 0.825 * Math.sin(a) };
      const sa = new DOMPoint(bp.x, bp.y).matrixTransform(ctmA);
      const rect = bv.svg.querySelector('rect.pad[data-pad="R1.1"]');
      const so = new DOMPoint(0, 0).matrixTransform(rect.getScreenCTM());
      const fids = art.fiducials.map((f) => {
        const s1 = new DOMPoint(f.bx, f.by).matrixTransform(ctmA);
        const q = p.board.fiducials.find((x) => x.id === f.name);
        const s2 = new DOMPoint(q.x, -q.y).matrixTransform(ctmO);
        return { id: f.name, px: +Math.hypot(s1.x - s2.x, s1.y - s2.y).toFixed(3), copper: exposed.isPointInFill(new DOMPoint(f.bx, f.by)) };
      });
      return { rot: p.source.rotation, pad: +Math.hypot(sa.x - so.x, sa.y - so.y).toFixed(3), padCopper: exposed.isPointInFill(new DOMPoint(bp.x, bp.y)),
        fids, pxPerMm: +(1 / bv.k).toFixed(2) };
    });
    check('30°: the drawing\'s R1 pad 1 lies within 0.5 px of rect.pad[data-pad="R1.1"], on drawn copper', al.pad <= 0.5 && al.padCopper, al);
    check('30°: every fiducial of the drawing within 0.5 px of the plan\'s board.fiducials, on drawn copper',
      al.fids.length === 3 && al.fids.every((x) => x.px <= 0.5 && x.copper), al.fids);
    await shot(page, 'run-hero-30deg-1440-light');

    // ---- (7) performance gate at 4× CPU throttling ------------------------------------------------------------------
    const big = largestArt();
    const cdp = await context.newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    // §5.5: the drawing with a plan of its own size (mobo: 261 pads), not the session's 160-pad ftp plan: one pad per paste
    // opening of the drawing (its subpaths; size and angle in the frame of the part it sits in), one dot each, nearest-
    // neighbour order, on the hero's seat. Measured in a view of the hero's size over the page (the page's own polls keep
    // re-planning the hero with the session's plan).
    const measure = () => page.evaluate(async (id) => {
      const frame = () => new Promise((r) => requestAnimationFrame(() => r(performance.now())));
      const painted = () => new Promise((r) => requestAnimationFrame(() => setTimeout(() => r(performance.now()), 0)));
      const art = await IL.boardart.get(id);
      const base = IL.S.plan, m = base.board.toMachine;
      const T = (x, y) => ({ x: m[0] * x + m[2] * y + m[4], y: m[1] * x + m[3] * y + m[5] });
      const seat = Math.atan2(m[1], m[0]) * 180 / Math.PI;
      const parts = (art.parts || []).filter((q) => q.kind !== 'bottom' && q.kind !== 'art');
      const pads = [];
      for (const sp of String((art.layers.paste || {}).fill || '').split(/(?=M)/).filter(Boolean)) {
        const pts = [];
        let x = 0, y = 0, arc = false;
        for (const [, c, a] of sp.matchAll(/([MLHVAZ])([^MLHVAZ]*)/g)) {
          const v = (a.match(/-?[\d.]+(?:e-?\d+)?/g) || []).map(Number);
          if (c === 'M' || c === 'L') for (let i = 0; i + 1 < v.length; i += 2) { x = v[i]; y = v[i + 1]; pts.push([x, y]); }
          else if (c === 'H') for (const q of v) { x = q; pts.push([x, y]); }
          else if (c === 'V') for (const q of v) { y = q; pts.push([x, y]); }
          else if (c === 'A') for (let i = 0; i + 6 < v.length; i += 7) { x = v[i + 5]; y = v[i + 6]; pts.push([x, y]); arc = true; }
        }
        if (pts.length < 2) continue;
        const cx = pts.reduce((a, q) => a + q[0], 0) / pts.length, cy = pts.reduce((a, q) => a + q[1], 0) / pts.length;
        const part = parts.reduce((b, q) => (!b || Math.hypot(q.x - cx, q.y - cy) < Math.hypot(b.x - cx, b.y - cy) ? q : b), null);
        const ang = part ? Number(part.a) || 0 : 0, r = -ang * Math.PI / 180;
        const loc = pts.map(([px, py]) => [(px - cx) * Math.cos(r) - (py - cy) * Math.sin(r), (px - cx) * Math.sin(r) + (py - cy) * Math.cos(r)]);
        let w = Math.max(...loc.map((q) => q[0])) - Math.min(...loc.map((q) => q[0]));
        let h = Math.max(...loc.map((q) => q[1])) - Math.min(...loc.map((q) => q[1]));
        if (arc && (w < 0.01 || h < 0.01)) w = h = Math.max(w, h);   // a circle: M A A Z gives its two ends only
        const c = T(cx, cy), ref = part ? part.ref : 'P';
        pads.push({ padId: ref + '.' + (pads.length + 1), placementId: ref, packageId: part ? part.pkg : '', x: c.x, y: c.y, w, h, rotation: ang + seat,
          typeKey: 'chip-0603', dotCount: 1, flags: [], blocked: false });
      }
      const left = pads.slice(), dots = [];
      let cur = T(0, 0);
      while (left.length) {
        let bi = 0;
        for (let i = 1; i < left.length; i++) if (Math.hypot(left[i].x - cur.x, left[i].y - cur.y) < Math.hypot(left[bi].x - cur.x, left[bi].y - cur.y)) bi = i;
        cur = left.splice(bi, 1)[0];
        const i = dots.length;
        dots.push({ index: i, padId: cur.padId, x: cur.x, y: cur.y, dispenseZ: 3, rotationDeg: 180, ta: 10 + 0.6 * i, te: 10.4 + 0.6 * i });
      }
      const plan = { planId: 'pw-perf-' + id, pads, dots, dot: { diameterMm: 0.6 }, profile: base.profile, summary: { estimatedSeconds: 10 + 0.6 * dots.length },
        board: Object.assign({}, base.board, { widthMm: art.size[0], heightMm: art.size[1], fiducials: [] }) };
      const hr = IL.run.bv.svg.parentNode.getBoundingClientRect();
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;z-index:60;background:var(--pv-bg);left:' + hr.left + 'px;top:' + hr.top + 'px;width:' + hr.width + 'px;height:' + hr.height + 'px';
      document.body.appendChild(host);
      const bv = IL.boardview.create(host, {});
      bv.setPlan(plan, {});
      bv.picture('quiet');
      await painted();
      const t0 = performance.now();
      bv.setArt(art);
      const artMs = (await painted()) - t0;
      // 16× preview: frame times over 5 s
      const pp = IL.pathPreview.create({ host: document.createElement('div'), bv });
      pp.setPlan(plan);
      pp.setSpeed(16);
      pp.seek(0);
      pp.play();
      const gaps = [];
      let last = await frame();
      const end = last + 5000;
      while (last < end) { const t = await frame(); gaps.push(t - last); last = t; if (!pp.state.playing) { pp.seek(0); pp.play(); } }
      pp.destroy();
      gaps.sort((a, b) => a - b);
      const p95 = gaps[Math.floor(gaps.length * 0.95)];
      // a poll: bv.update() and the paint after it (the wait for the next frame itself excluded)
      const n = dots.length;
      const polls = [];
      for (let i = 0; i < 10; i++) {
        const done = new Set(Array.from({ length: (i * 7) % n }, (_, j) => j));
        await painted();
        const a0 = performance.now();
        bv.update({ done, next: (i * 7) % n });
        const a1 = performance.now();
        const f0 = await frame();
        const f1 = await new Promise((r) => setTimeout(() => r(performance.now()), 0));
        polls.push((a1 - a0) + (f1 - f0));
      }
      bv.destroy();
      host.remove();
      polls.sort((a, b) => a - b);
      return { pads: pads.length, dots: n, artMs: +artMs.toFixed(1), p95: +p95.toFixed(1), frames: gaps.length, pollMs: +((polls[4] + polls[5]) / 2).toFixed(1) };
    }, big.id);
    let perf = await measure();
    const okPerf = (x) => x.artMs <= 1000 && x.p95 <= 33 && x.pollMs <= 16;
    if (!okPerf(perf)) perf = Object.assign(await measure(), { repeated: true });
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
    check('performance at 4× CPU throttling (' + big.file + ', ' + Math.round(big.bytes / 1024) + ' KB, a ' + perf.pads + '-pad plan of its openings): drawing <= 1,000 ms, 16× preview p95 frame <= 33 ms, poll + paint <= 16 ms',
      okPerf(perf) && perf.pads > 0, perf);
    perf.art = big;
    fs.writeFileSync(path.join(OUT, 'perf.json'), JSON.stringify(perf, null, 2));

    check('no native dialogs (confirm / alert / prompt)', dialogs.length === 0, dialogs);
    const real = jsErrors.filter((e) => !/Failed to load resource: the server responded with a status of 4\d\d/.test(e));
    check('no JavaScript errors', real.length === 0, jsErrors);
  } catch (e) {
    check('scenario completed', false, String(e && e.stack || e));
    await page.screenshot({ path: path.join(OUT, 'failure.png'), fullPage: true }).catch(() => {});
  } finally {
    await browser.close();
    fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ results, shots }, null, 2));
    const failed = results.filter((r) => !r.ok).length;
    console.log(shots.length + ' screenshots in ' + SHOTS);
    console.log(failed ? failed + ' check(s) FAILED' : 'all ' + results.length + ' checks passed');
    process.exit(failed ? 1 : 0);
  }
})();
