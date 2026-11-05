/**
 * Designs on the simulator (docs/ui-flows/board-flow.md B; mockups `design-upload` and `design-setup`, screen flow
 * `scrDesign` d0-d3, the user's OK of 11-02): the approved screens over today's abilities. (1) Designs: a card per
 * board file with its picture and its state from its pad-profile check (ready, waits on pad groups, needs another
 * tip), a card opens its Set up, and the menu line counts them; (2) Upload a design: the dialog says design ("Upload a
 * design", "Design name", "Save the design"), the parts that get no paste under "No paste" (the Nano's bottom side
 * is not read yet: BR-25), and Save the design opens its Set up while Designs lists it, after one read of the list,
 * and leaves an operator who went elsewhere meanwhile where they are; the drawing given to a board file keeps the
 * preview's "paste the DNP parts", and its saved step's button and line name Set up (Open its Set up); (3) Set up of a design that
 * waits (padcal under a new name): the checklist B1-B6 (the top read; the bottom's parts from the upload's record,
 * GET /api/boards' `imported.skipped` (BR-88 (a)); paste per pad with the groups that wait; one row per part that needs another
 * tip, with Change the tip; missing doses; the copper check, the fine-pitch rehearsal, feeders and the holder not
 * built yet), the picture hatching the waiting parts, and the panel's Set up the rest; (4) Use the doses found maps
 * the check's `use` groups and the page follows the new check; (5) Set up the rest uses the doses found and opens the
 * pad calibration of the groups to calibrate (its own Start: nothing pressed); a doses row is done only when no dose is
 * missing (a group that waits for the tip's dots or a by-hand dose says so; stubbed: a fresh SIM tip is measured); a
 * refusal stays with its design; a file dropped on the open dialog keeps Designs' Save, even over another page; the menu
 * line follows a design saved in Run's Board step in this tab; (6) a ready design: Make a board opens Run › Start with it chosen;
 * a failed read of the list keeps the cards Start and Designs have and says so in one wording everywhere (no "look at the
 * machine" after a read), with Retry on Start, Designs and Set up (a Retry pressed from the keyboard keeps the focus);
 * nothing reads the list again by itself but OpenPnP answering again (online); (7) nothing moves (only
 * uploads, the apply and the test's own set-up writes are sent), no JavaScript errors, no native dialogs. Designs are
 * routed by their board file's path: names repeat, and every board saved from one KiCad file shows the same upload.
 * Self-contained: it marks chip-0603 characterized (SIM only, as the sim acceptance and pad-profile do), uploads its own
 * designs under new names, and maps TestChip_A to 0603 as pad-profile does (it clears that mapping first, so the `use`
 * suggestion is there on every run). Expectations come from the server's own check at run time.
 *
 * Env: IL_BASE or IL_API_URL (the UI), PLAYWRIGHT_DIR, OUT_DIR (designs-results.json).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');
const { chromium } = require(path.join(process.env.PLAYWRIGHT_DIR, 'node_modules', 'playwright'));

const BASE = String(process.env.IL_BASE || process.env.IL_API_URL || '').replace(/\/?$/, '/');
const OUT = process.env.OUT_DIR || '/tmp/il-designs-test-out';
const RES = path.join(__dirname, '..', 'resources');
const TS = Date.now().toString(36);
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail === undefined ? null : detail });
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (detail !== undefined && !cond ? '  ' + JSON.stringify(detail).slice(0, 1500) : ''));
}
const NANO = zlib.gunzipSync(fs.readFileSync(path.join(RES, 'kicad', 'NanoV3.3.kicad_pcb.gz')));
const PADCAL = fs.readFileSync(path.join(RES, 'padprofile', 'padcal.kicad_pcb'));
/** The server's check of a board file, and the state the page must show for it (groups as padprofile.js splits them). */
async function checkOf(page, file) {
  return page.evaluate(async (f) => {
    try {
      const c = await IL.api('GET', '/api/pad-profile/check?boardFile=' + encodeURIComponent(f));
      const groups = c.groups || [];
      const tip = groups.filter((g) => ['tip', 'part'].includes(g.reason) && g.blocking);
      const fix = groups.filter((g) => ['no-pad-size', 'no-type', 'below-gate', 'bridge'].includes(g.reason));
      return { c, state: c.ok ? 'ready' : tip.length ? 'tip' : 'wait', tip, fix,
        use: fix.filter((g) => g.suggestion && g.suggestion.kind === 'use'),
        cal: fix.filter((g) => g.suggestion && ['new', 'calibrate-existing'].includes(g.suggestion.kind)) };
    } catch (e) { return { state: 'error', error: e.message || String(e) }; }
  }, file);
}
const boardsOf = (page) => page.evaluate(() => IL.api('GET', '/api/boards').then((b) => b.files || []));
const routeOf = (entry) => '#designs/setup/' + encodeURIComponent(entry.file);
const stepsOf = (page) => page.evaluate(() => Array.from(document.querySelectorAll('#design-steps > li')).map((li) => ({
  k: li.dataset.k, state: li.dataset.state, text: li.textContent.replace(/\s+/g, ' ').trim() })));
const shown = (page, sel) => page.evaluate((s) => { const n = document.querySelector(s); return !!n && !n.closest('[hidden]') && n.getClientRects().length > 0; }, sel);

/** Designs › Upload a design with a file: the preview's words, then Save the design under name (onPreview first). */
async function uploadDesign(page, fileName, buffer, name, onPreview) {
  await page.evaluate(() => IL.go('#designs'));
  await page.click('#btn-design-upload');
  await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
  const previewP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/boards/import' && r.request().method() === 'POST', { timeout: 60000 });
  await page.setInputFiles('#board-import-file', { name: fileName, mimeType: 'application/octet-stream', buffer });
  const preview = await (await previewP).json().catch(() => null);
  await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
  const words = await page.evaluate(() => {
    const d = document.getElementById('dlg-board-import');
    const t = (s) => { const n = d.querySelector(s); return n ? n.textContent.replace(/\s+/g, ' ').trim() : null; };
    return { eyebrow: t('.eyebrow'), title: t('h2'), head: t('#board-import-found .bi-found__a'), noPaste: t('#board-import-found .bi-found__b'), nameLabel: t('label[for="board-import-name"]'),
      save: t('#btn-board-import-save') };
  });
  if (onPreview) onPreview(preview);
  await page.fill('#board-import-name', name);
  await page.evaluate(() => { window.__boardsMark = (window.__boardsReads || []).length; });
  const saveP = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/boards/import/save', { timeout: 60000 });
  await page.click('#btn-board-import-save');
  const saved = await (await saveP).json().catch(() => null);
  return { preview, words, saved };
}

(async () => {
  const t0 = Date.now();
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const jsErrors = [], dialogs = [], writes = [];
  page.on('pageerror', (e) => jsErrors.push(String(e)));
  page.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
  page.on('request', (r) => { if (r.method() !== 'GET' && new URL(r.url()).pathname.startsWith('/api/')) writes.push(r.method() + ' ' + new URL(r.url()).pathname); });
  let holdBoards = null;   // (2b): the list's answer waits for the test
  await page.route('**/api/boards', async (route) => {
    if (holdBoards && route.request().method() === 'GET') await holdBoards;
    return route.continue().catch(() => { /* the page closed meanwhile */ });
  });
  // states the SIM does not hold on demand: a check whose groups wait for the tip's dots or a by-hand dose (one board
  // file), an apply refused once
  const ST = { checkFile: null, refuseApply: false, attachOffer: null, attachDnp: true, attachUrl: null, importHeld: null, holdPreview: null, holdAttach: null, okTipFile: null };
  // the status's boardImport held ((2), (5d): "an upload is being read" off and savedAtMs as before the save, so only the
  // save's own 'boards' event may make Designs read its list; (2c): only `reading` off, savedAtMs passes)
  await page.route((u) => u.pathname === '/api/status', async (route) => {
    if (!ST.importHeld) return route.continue();
    try {
      const res = await route.fetch();
      const body = await res.json();
      if (body.boardImport) Object.assign(body.boardImport, ST.importHeld);
      await route.fulfill({ response: res, json: body });
    } catch (e) { /* unrouted while it was being answered */ }
  });
  // a preview that offers the drawing for a board file with "paste the DNP parts" on, and that attach's answer (the
  // stub's: nothing is written)
  await page.route((u) => u.pathname === '/api/boards/import' || u.pathname === '/api/boards/import/attach', async (route) => {
    const u = new URL(route.request().url());
    if (!ST.attachOffer || route.request().method() !== 'POST') return route.continue();
    if (u.pathname === '/api/boards/import/attach') {
      if (ST.holdAttach) await ST.holdAttach;
      ST.attachUrl = u.search;
      return route.fulfill({ status: 201, json: { slug: 'designs-attach-stub', name: 'stub', board: ST.attachOffer.file, art: { id: '0000000000000000', kind: 'kicad' },
        maxOffsetMm: 0, offsets: [], replaced: false } });
    }
    if (ST.holdPreview) await ST.holdPreview;
    try {
      const res = await route.fetch();
      const body = await res.json();
      body.pasteDnp = ST.attachDnp;
      body.attach = [ST.attachOffer];
      await route.fulfill({ response: res, json: body });
    } catch (e) { /* unrouted while it was being answered */ }
  });
  await page.route((u) => u.pathname === '/api/pad-profile/check', async (route) => {
    try {
      const res = await route.fetch();
      const body = await res.json();
      if (ST.checkFile && new URL(route.request().url()).searchParams.get('boardFile') === ST.checkFile) {
        const fix = (body.groups || []).filter((g) => ['no-pad-size', 'no-type', 'below-gate', 'bridge'].includes(g.reason));
        if (fix[0]) fix[0].suggestion = { kind: 'measure-tip', id: 'stub-measure', group: fix[0].group };
        if (fix[1]) fix[1].suggestion = null;
      }
      // (6b): every pad has a dose, and one tip group the engine does not block on (ok stays true: ProfileCheck's unflagged
      // tip group of kind pitch)
      if (ST.okTipFile && String(new URL(route.request().url()).searchParams.get('boardFile')).includes(ST.okTipFile)) {
        Object.assign(body, { ok: true, groups: [{ group: 'stub-tip', reason: 'tip', tipKind: 'pitch', blocking: false, fixable: false, refs: ['U1'],
          packageIds: ['QFN-stub'], pads: 4, minPitchMm: 0.4, short: 'not with this tip', reasonText: 'Stub: the dots would come within 0.20 mm.' }] });
      }
      await route.fulfill({ response: res, json: body });
    } catch (e) { /* unrouted while it was being answered */ }
  });
  await page.route((u) => u.pathname === '/api/pad-profile/apply', async (route) => {
    if (!ST.refuseApply) return route.continue();
    ST.refuseApply = false;
    return route.fulfill({ status: 409, json: { error: 'suggestion-stale', reason: 'Refused by the test: the suggestions changed.' } });
  });

  try {
    await page.goto(BASE + '#designs');
    await page.waitForFunction(() => window.IL && IL.S && IL.S.status && IL.ready, null, { timeout: 30000 });
    const sim = await page.evaluate(() => !!(IL.S.status.server && IL.S.status.server.simulated === true));
    check('the server is an OpenPnP simulator (status.server.simulated)', sim);
    if (!sim) throw new Error('not a simulator: nothing was done');
    // Designs' own reads of the list (not the test's), each with the page it was asked on: (2) counts them
    await page.evaluate(() => {
      window.__boardsReads = [];
      const api = IL.api;
      IL.api = function (method, p) {
        if (method === 'GET' && p === '/api/boards' && /designflow\.js/.test(String(new Error().stack))) window.__boardsReads.push(location.hash);
        return api.apply(this, arguments);
      };
    });

    // ---- (0) the session's 0603 pad type at the gate (characterized), as the sim acceptance and pad-profile set it: a
    // fresh session's is below it, and then padcal's TestChip_A has no `use` suggestion (the rest leg, 11-04 10:2x)
    const tipKey = await page.evaluate(() => IL.S.status.dispense.tip.key);
    const chip0 = await page.evaluate(() => IL.api('GET', '/api/pad-types/chip-0603'));
    if (!['characterized', 'validated'].includes(((chip0 || {}).current || {}).status)) {
      await page.evaluate((k) => IL.api('PUT', '/api/pad-types/chip-0603', { status: 'characterized', forSetup: k, notes: 'SIM ONLY: designs test' }), tipKey);
    }

    // ---- (2) Upload a design: the Nano (its bottom side is not read yet) --------------------------------------------
    const nanoName = 'designs-nano-' + TS;
    const holdImport = async () => { ST.importHeld = { reading: false, savedAtMs: ((await (await page.request.get(BASE + 'api/status')).json()).boardImport || {}).savedAtMs ?? null }; };
    await holdImport();
    const up = await uploadDesign(page, 'NanoV3.3.kicad_pcb', NANO, nanoName);
    const bottom = (up.preview && up.preview.skipped && up.preview.skipped.bottom) || [];
    check('(2) the upload says design: "New design", "Upload a design", "Design name", "Save the design"',
      up.words.eyebrow === 'New design' && up.words.title === 'Upload a design' && up.words.nameLabel === 'Design name' && up.words.save === 'Save the design', up.words);
    // named, not counted: the bottom's skipped footprints are parts, fiducials, through-hole headers and a logo (Backend)
    check('(2) the Nano\'s bottom side is named under "No paste": "the bottom side (C1–C9, …): not read yet"', bottom.length > 0
      && /^No paste: /.test(up.words.noPaste || '') && /the bottom side \([^)]+\): not read yet/.test(up.words.noPaste) && !/bottom-side parts/.test(up.words.noPaste),
    { bottom: bottom.length, noPaste: up.words.noPaste });
    // the headline adds up to every part: those that get paste, need a profile, or need another tip (the PM, 11-04 10:0x:
    // "5 parts need a profile (10 pads)" left out the 3 parts the tip cannot paste)
    {
      const pk = new Map(((up.preview && up.preview.packages) || []).map((k) => [k.id, k]));
      const parts = ((up.preview && up.preview.placements) || []).filter((p) => p.type !== 'Fiducial');
      const need = parts.filter((p) => p.outcome === 'needs-profile');
      const tip = parts.filter((p) => p.outcome === 'not-dispensed' && ['tip', 'part'].includes((pk.get(p.packageId) || {}).profileReason));
      const n = (re) => { const m = re.exec(up.words.head || ''); return m ? Number(m[1]) : 0; };
      const got = { paste: n(/(\d+) parts? gets? paste/), need: n(/(\d+) parts? needs? a profile/), tip: n(/(\d+) parts? needs? another tip/) };
      const pads = /needs? a profile \((\d+) pads?\)/.exec(up.words.head || '');
      check('(2) the headline adds up to every part of the side: the parts that need another tip named too', got.paste + got.need + got.tip === parts.length
        && got.need === need.length && !!pads && Number(pads[1]) === need.reduce((a, p) => a + p.pads, 0) && got.tip === tip.length && tip.length > 0
        && tip.every((p) => (up.words.head || '').includes(p.id)), { head: up.words.head, parts: parts.length, need: need.length, tip: tip.map((p) => p.id) });
    }
    const nano = up.saved && (await boardsOf(page)).find((f) => f.file === up.saved.file);
    const toNano = await page.waitForFunction(([h, n]) => location.hash === h && !document.getElementById('dlg-board-import')
      && (document.getElementById('design-name') || {}).textContent === n && !document.getElementById('design-setup').hidden,
    [nano ? routeOf(nano) : '#none', nanoName], { timeout: 15000 }).then(() => true, () => false);
    check('(2) Save the design: the dialog closes and the design\'s Set up opens', !!nano && toNano,
      await page.evaluate(() => ({ hash: location.hash, dialog: !!document.getElementById('dlg-board-import'), name: (document.getElementById('design-name') || {}).textContent || null })));
    const reads = await page.evaluate(() => window.__boardsReads.slice(window.__boardsMark));
    check('(2) Save and Designs\' own list read: one before Set up opens, not two (the save\'s, not onSaved\'s too)', reads.filter((h) => h === '#designs').length === 1, reads);
    const meta = await page.evaluate(() => (document.getElementById('design-meta') || {}).textContent || '');
    check('(2) Set up names the board\'s own upload: "Uploaded <when> from NanoV3.3.kicad_pcb" (BR-88: per board, #42)',
      /^Uploaded .+ from NanoV3\.3\.kicad_pcb/.test(meta), meta);
    ST.importHeld = null;

    // ---- (2c) a design saved in another tab (here: the API, not this page) shows in Designs by status.boardImport.savedAtMs
    // alone: `reading` held off, as a poll misses most saves (Backend's #42)
    await page.evaluate(() => IL.go('#designs'));
    await page.waitForSelector('#designs-list', { state: 'visible', timeout: 10000 }).catch(() => null);
    ST.importHeld = { reading: false };
    const otherName = 'designs-other-' + TS;
    const octet = { 'Content-Type': 'application/octet-stream' };
    const opv = await (await page.request.post(BASE + 'api/boards/import?file=padcal.kicad_pcb', { data: PADCAL, headers: octet })).json();
    const osv = await page.request.post(BASE + 'api/boards/import/save?' + new URLSearchParams({ file: 'padcal.kicad_pcb', name: otherName, replace: '0',
      sha256: opv.file.sha256, decisions: opv.decisions }), { data: PADCAL, headers: octet });
    const listed = await page.waitForFunction((n) => [...document.querySelectorAll('#designs-list .design')].some((d) => d.dataset.name === n || d.textContent.includes(n)),
      otherName, { timeout: 15000 }).then(() => true, () => false);
    check('(2c) a design saved in another tab shows in Designs without a visit (status.boardImport.savedAtMs)', osv.status() === 201 && listed, { status: osv.status(), listed });
    ST.importHeld = null;
    const sidesRe = 'Bottom: not read yet \\([^)]+\\)';
    await page.waitForFunction((re) => new RegExp(re).test((document.querySelector('#design-steps li[data-k="sides"]') || {}).textContent || ''), sidesRe, { timeout: 10000 })
      .catch(() => null);
    const sides = (await stepsOf(page)).find((x) => x.k === 'sides');
    const bottomRefs = await page.evaluate((b) => IL.boardImport.refList(b, 3), bottom);
    check('(3) the sides: the top read, the bottom\'s parts not read yet (from the upload\'s record, the preview\'s refs)', !!sides && sides.state === 'done'
      && sides.text.includes('Bottom: not read yet (' + bottomRefs + ')'), { sides, bottomRefs });

    // ---- (2b) Save, then the operator goes elsewhere before the saved design is read (its answer held): Set up does not
    // pull them back
    const awayName = 'designs-away-' + TS;
    let release = null;
    const away = await uploadDesign(page, 'padcal.kicad_pcb', PADCAL, awayName, () => { holdBoards = new Promise((r) => { release = r; }); });
    const closed = await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 15000 }).then(() => true, () => false);
    await page.evaluate(() => IL.go('#machine'));
    holdBoards = null;
    if (release) release();
    const pulled = await page.waitForFunction(() => location.hash !== '#machine', null, { timeout: 4000 }).then(() => true, () => false);
    check('(2b) saved and closed, then gone elsewhere while the design is read: the page stays there (no pull back to Set up)',
      !!release && !!(away.saved && away.saved.file) && closed && !pulled, { hash: await page.evaluate(() => location.hash), saved: !!(away.saved && away.saved.file), closed });

    // ---- (2e) the drawing given to a board file keeps the preview's "paste the DNP parts" (Backend's #40 review: the
    // attach re-reads the file, and without the same flag it no longer matches the preview: 409), off and on
    // the Nano saved in (2) as the board file the drawing goes to: its Set up is where Open its Set up must land
    ST.attachOffer = { file: nano ? nano.file : '/stub/designs-attach.board.xml', name: 'designs-attach', maxOffsetMm: 0, offsets: [] };
    const attachOnce = async (dnp, open) => {
      ST.attachDnp = dnp;
      ST.attachUrl = null;
      await page.evaluate(() => IL.go('#designs'));
      await page.click('#btn-design-upload');
      await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
      await page.setInputFiles('#board-import-file', { name: 'padcal.kicad_pcb', mimeType: 'application/octet-stream', buffer: PADCAL });
      await page.waitForSelector('#btn-board-import-attach', { state: 'visible', timeout: 60000 });
      const on = await page.evaluate(() => { const c = document.getElementById('board-import-paste-dnp'); return !!c && c.checked; });
      await page.click('#btn-board-import-attach');
      await page.waitForFunction(() => document.getElementById('dlg-board-import').dataset.state === 'saved', null, { timeout: 15000 }).catch(() => null);
      const words = await page.evaluate(() => { const u = document.getElementById('btn-board-import-use'), x = document.querySelector('#board-import-saved .bi-saved__next');
        return { use: u ? u.textContent.trim() : null, next: x ? x.textContent : null }; });
      if (open) await page.click('#btn-board-import-use', { timeout: 5000 }).catch(() => null);   // a failed attach: this check fails, the rest run
      else await page.keyboard.press('Escape');
      await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 10000 }).catch(() => null);
      const at = open ? await page.waitForFunction((h) => location.hash === h && !document.getElementById('design-setup').hidden && !document.getElementById('dlg-board-import'),
        routeOf(nano), { timeout: 15000 })
        .then(() => true, () => false) : null;
      return { on, attach: ST.attachUrl, words, at };
    };
    const dnpOff = await attachOnce(false, !!nano);
    check('(2e) Use the drawing for a board file, DNP parts not pasted: the attach sends no pasteDnp', !dnpOff.on && dnpOff.attach !== null && !/pasteDnp/.test(dnpOff.attach), dnpOff);
    // its button opens the design's Set up (Designs' onSaved), so it and its line say so, not Run's "then plan it"
    check('(2e) after an attach in Designs the button and its line name Set up, and the button opens the design\'s Set up: "Open its Set up"',
      dnpOff.words.use === 'Open its Set up' && /Set up/.test(dnpOff.words.next || '') && !/plan it|Use this/.test(dnpOff.words.next || '') && dnpOff.at === true,
      { words: dnpOff.words, at: dnpOff.at, hash: await page.evaluate(() => location.hash) });
    const dnpOn = await attachOnce(true);
    check('(2e) Use the drawing for a board file, DNP parts pasted: the attach sends pasteDnp=1, as the save does', dnpOn.on && /(^|[?&])pasteDnp=1(&|$)/.test(dnpOn.attach || ''), dnpOn);

    // ---- (2f) the DNP box changed: the attach waits for the file read again; while the attach saves the box is locked;
    // the attach sends the new preview's flag (the box ticked by the first preview, then unticked)
    ST.attachDnp = true;
    ST.attachUrl = null;
    await page.evaluate(() => IL.go('#designs'));
    await page.click('#btn-design-upload');
    await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
    await page.setInputFiles('#board-import-file', { name: 'padcal.kicad_pcb', mimeType: 'application/octet-stream', buffer: PADCAL });
    await page.waitForSelector('#btn-board-import-attach', { state: 'visible', timeout: 60000 });
    let releasePv = null;
    ST.holdPreview = new Promise((r) => { releasePv = r; });
    ST.attachDnp = false;
    await page.click('#board-import-paste-dnp');
    await page.waitForFunction(() => document.getElementById('dlg-board-import').dataset.state === 'reading', null, { timeout: 10000 }).catch(() => null);
    const waits = await page.evaluate(() => ({ state: document.getElementById('dlg-board-import').dataset.state, attach: document.getElementById('btn-board-import-attach').disabled }));
    ST.holdPreview = null;
    releasePv();
    await page.waitForFunction(() => document.getElementById('dlg-board-import').dataset.state === 'preview' && !document.getElementById('btn-board-import-attach').disabled, null, { timeout: 60000 }).catch(() => null);
    let releaseAt = null;
    ST.holdAttach = new Promise((r) => { releaseAt = r; });
    await page.click('#btn-board-import-attach');
    await page.waitForFunction(() => document.getElementById('dlg-board-import').dataset.state === 'saving', null, { timeout: 10000 }).catch(() => null);
    const locked = await page.evaluate(() => document.getElementById('board-import-paste-dnp').disabled);
    ST.holdAttach = null;
    releaseAt();
    await page.waitForFunction(() => document.getElementById('dlg-board-import').dataset.state === 'saved', null, { timeout: 15000 }).catch(() => null);
    check('(2f) the DNP box changed: the attach waits for the new preview, the box is locked while the attach saves, the attach sends the new preview\'s flag (none)',
      waits.state === 'reading' && waits.attach && locked && ST.attachUrl !== null && !/pasteDnp/.test(ST.attachUrl), { waits, locked, attach: ST.attachUrl });
    await page.keyboard.press('Escape');
    await page.waitForSelector('#dlg-board-import', { state: 'detached', timeout: 10000 }).catch(() => null);
    ST.attachOffer = null;

    // ---- (3) Set up of a design that waits: padcal under a new name ---------------------------------------------
    // its `use` suggestion (TestChip_A → 0603) only shows while TestChip_A has no mapping: cleared first (no motion)
    await page.evaluate(() => IL.api('POST', '/api/package-mappings', { packageId: 'TestChip_A', typeKey: null }).catch(() => null));
    const pcName = 'designs-padcal-' + TS;
    const up2 = await uploadDesign(page, 'padcal.kicad_pcb', PADCAL, pcName);
    const pc = up2.saved && (await boardsOf(page)).find((f) => f.file === up2.saved.file);
    await page.waitForFunction((h) => location.hash === h && !document.getElementById('design-setup').hidden && document.querySelectorAll('#design-steps > li').length >= 7,
      pc ? routeOf(pc) : '#none', { timeout: 15000 }).catch(() => null);
    let ck = pc ? await checkOf(page, pc.file) : null;
    check('(3) padcal\'s check has groups that wait (a `use`, groups to calibrate) and a part that needs another tip', !!ck && ck.use.length > 0 && ck.cal.length > 0 && ck.tip.length > 0,
      ck && { state: ck.state, use: ck.use.length, cal: ck.cal.length, tip: ck.tip.length, error: ck.error });
    let steps = await stepsOf(page);
    const byK = (k) => steps.find((x) => x.k === k) || {};
    const tipParts = ck ? [...new Set(ck.tip.map((g) => g.packageIds[0]))] : [];
    const tipRows = steps.filter((x) => /^tip:/.test(x.k || ''));
    check('(3) the checklist B1-B6 in order, each with its state', JSON.stringify(steps.filter((x) => !/^tip:/.test(x.k)).map((x) => x.k + '=' + x.state))
      === JSON.stringify(['sides=done', 'copper=notyet', 'paste=need', 'doses=need', 'rehearsal=notyet', 'feeders=notyet', 'holder=notyet']), steps.map((x) => x.k + '=' + x.state));
    const pasteD = await page.evaluate(() => (document.querySelector('#design-steps li[data-k="paste"] .rstep__d') || {}).textContent || '');
    check('(3) paste per pad: how many pad groups wait, and the button that uses the doses found', new RegExp('^' + ck.fix.length + ' pad groups wait').test(pasteD)
      && await shown(page, '#design-steps li[data-k="paste"] #btn-design-use'), byK('paste'));
    check('(3) one row per part that needs another tip, with the server\'s reason and Change the tip', tipRows.length === tipParts.length
      && tipRows.every((r) => r.state === 'block' && /Change the tip/.test(r.text)) && ck.tip.every((g) => tipRows.some((r) => r.text.includes(g.refs[0]))),
    { tipRows, tipParts });
    const dosesD = await page.evaluate(() => (document.querySelector('#design-steps li[data-k="doses"] .rstep__d') || {}).textContent || '');
    check('(3) missing doses: the groups to calibrate, with one weighing', new RegExp('^' + ck.cal.length + ' pad groups? to calibrate').test(dosesD) && /weigh/.test(dosesD), dosesD);
    check('(3) not built yet: the copper check, the rehearsal, feeders and the holder say so', ['copper', 'rehearsal', 'feeders', 'holder'].every((k) => /not built yet/.test(byK(k).text)),
      ['copper', 'rehearsal', 'feeders', 'holder'].map(byK));
    const waitRefs = ck ? [...new Set(ck.fix.concat(ck.tip).flatMap((g) => g.refs))].sort() : [];
    const hatched = () => [...new Set(Array.from(document.querySelectorAll('#design-pic rect.pv-part.bi-part--block, #design-pic rect.pv-part.bi-part--wait'))
      .map((r) => r.dataset.ref))].sort();
    // the drawing loads after the checklist: wait for it (the page allows no eval: the function is written out here)
    await page.waitForFunction((want) => JSON.stringify([...new Set(Array.from(document.querySelectorAll('#design-pic rect.pv-part.bi-part--block, #design-pic rect.pv-part.bi-part--wait'))
      .map((r) => r.dataset.ref))].sort()) === JSON.stringify(want), waitRefs, { timeout: 10000 }).catch(() => null);
    const hatch = await page.evaluate(hatched);
    check('(3) the picture hatches the parts whose pads wait, and only those', JSON.stringify([...new Set(hatch)].sort()) === JSON.stringify(waitRefs), { hatch, waitRefs });
    const panel = await page.evaluate(() => ({ title: (document.querySelector('#panel .panel__title h2') || {}).textContent || null,
      block: (document.getElementById('design-rest-block') || {}).textContent || null }));
    const btns = { rest: await shown(page, '#btn-design-rest'), make: await shown(page, '#btn-design-make'), next: await shown(page, '#btn-design-next') };
    check('(3) the panel: Set up the rest, a box for the part that needs another tip, one primary button (Set up the rest)', panel.title === 'Set up the rest'
      && btns.rest && !btns.make && !btns.next && tipParts.length > 0 && !!panel.block && /another tip/.test(panel.block), { panel, btns });

    // ---- (3b) a refusal stays with its design ---------------------------------------------------------------------
    ST.refuseApply = true;
    await page.click('#btn-design-use');
    const refused = await page.waitForFunction(() => /Refused by the test/.test((document.querySelector('#design-setup [data-refusals]') || {}).textContent || ''), null, { timeout: 10000 })
      .then(() => true, () => false);
    await page.evaluate((h) => IL.go(h), routeOf(nano));
    const carried = await page.waitForFunction((n) => (document.getElementById('design-name') || {}).textContent === n, nanoName, { timeout: 10000 })
      .then(() => page.evaluate(() => (document.querySelector('#design-setup [data-refusals]') || {}).textContent || ''), () => 'did not open');
    check('(3b) a refusal shows on its design, and not on the next one opened', refused && carried === '', { refused, carried });
    await page.evaluate((h) => IL.go(h), routeOf(pc));
    await page.waitForFunction(() => !!document.getElementById('btn-design-use'), null, { timeout: 10000 }).catch(() => null);

    // ---- (4) Use the doses found ---------------------------------------------------------------------------------
    await page.click('#btn-design-use');
    const used = await page.waitForFunction((n) => new RegExp('^' + n + ' pad groups? waits?').test((document.querySelector('#design-steps li[data-k="paste"] .rstep__d') || {}).textContent || ''),
      ck.fix.length - ck.use.length, { timeout: 15000 }).then(() => true, () => false);
    const maps = await page.evaluate(() => IL.api('GET', '/api/package-mappings'));
    check('(4) Use the doses found: TestChip_A mapped to its suggested type, the page follows the new check', used
      && (maps || []).some((m) => m.packageId === 'TestChip_A' && m.typeKey === ck.use[0].suggestion.typeKey), { used, steps: await stepsOf(page) });

    // ---- (5) Set up the rest: the doses found, then the pad calibration's own panel (nothing pressed) --------------
    // a mapping changed outside the page (here: the test's reset): the next visit reads the check again
    await page.evaluate(() => IL.api('POST', '/api/package-mappings', { packageId: 'TestChip_A', typeKey: null }));
    ck = await checkOf(page, pc.file);
    await page.evaluate(() => IL.go('#designs'));
    await page.evaluate((h) => IL.go(h), routeOf(pc));
    check('(5) a visit reads the design\'s check again (a mapping changed elsewhere shows)', await page.waitForFunction((n) => new RegExp('^' + n + ' pad groups wait')
      .test((document.querySelector('#design-steps li[data-k="paste"] .rstep__d') || {}).textContent || ''), ck.fix.length, { timeout: 15000 }).then(() => true, () => false),
    { want: ck.fix.length, steps: await stepsOf(page) });
    await page.click('#btn-design-rest');
    // the pad calibration's own list: one entry (its label in bold) per group it calibrates, then its notes
    const calLabels = ck.cal.map((g) => g.label).sort();
    const cal = await page.waitForFunction((want) => document.getElementById('panel').dataset.view === 'pad-cal' && !!document.getElementById('btn-pcal-start')
      && JSON.stringify(Array.from(document.querySelectorAll('#pcal-groups-list > li > b')).map((b) => b.textContent).sort()) === JSON.stringify(want), calLabels, { timeout: 20000 })
      .then(() => true, () => false);
    const maps2 = await page.evaluate(() => IL.api('GET', '/api/package-mappings'));
    check('(5) Set up the rest: the doses found used, then the pad calibration of the groups to calibrate, its Start not pressed', cal
      && (maps2 || []).some((m) => m.packageId === 'TestChip_A' && m.typeKey), await page.evaluate((want) => ({ view: document.getElementById('panel').dataset.view, want,
      groups: Array.from(document.querySelectorAll('#pcal-groups-list > li > b')).map((b) => b.textContent) }), calLabels));
    await page.keyboard.press('Escape');

    // ---- (5b) missing doses are done only when none is missing: groups that wait for the tip's dots, or for a dose by
    // hand, say so (stubbed on the Nano: a fresh SIM tip is measured, so the server suggests a calibration instead)
    ST.checkFile = nano.file;
    await page.evaluate(() => IL.go('#designs'));
    await page.evaluate((h) => IL.go(h), routeOf(nano));
    const doses5 = await page.waitForFunction(() => { const li = document.querySelector('#design-steps li[data-k="doses"]'); return li && li.dataset.state === 'need' && /measured/.test(li.textContent) && li.textContent; },
      null, { timeout: 10000 }).then((h) => h.jsonValue(), () => page.evaluate(() => { const li = document.querySelector('#design-steps li[data-k="doses"]'); return li ? li.dataset.state + ': ' + li.textContent : null; }));
    check('(5b) groups that wait for the tip\'s dots or a by-hand dose: Missing doses is not done, and says why', /tip’s dots are measured/.test(doses5 || '') && /by hand/.test(doses5 || '')
      && !(await shown(page, '#btn-design-use')) && await shown(page, '#btn-design-next') && !(await shown(page, '#btn-design-rest')), doses5);
    ST.checkFile = null;

    // ---- (5c) a file dropped on the open dialog (not on its drop box) keeps the options it was opened with: Designs'
    // Save opens the design's Set up, even with another page under it (whose drop would otherwise be Run's upload) -----
    const dropName = 'designs-drop-' + TS;
    await page.evaluate(() => IL.go('#designs'));
    await page.click('#btn-design-upload');
    await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
    await page.evaluate(() => IL.go('#machine'));
    await page.waitForFunction(() => IL.active() === 'machine', null, { timeout: 5000 }).catch(() => null);
    await page.evaluate(async ({ b64, name }) => {
      const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const dt = new DataTransfer();
      dt.items.add(new File([bin], name, { type: 'application/octet-stream' }));
      const head = document.querySelector('#dlg-board-import h2');
      for (const t of ['dragenter', 'dragover', 'drop']) head.dispatchEvent(new DragEvent(t, { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, { b64: PADCAL.toString('base64'), name: 'padcal.kicad_pcb' });
    await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
    await page.fill('#board-import-name', dropName);
    await page.click('#btn-board-import-save');
    const dropped = await page.waitForFunction((n) => location.hash.startsWith('#designs/setup/') && (document.getElementById('design-name') || {}).textContent === n, dropName,
      { timeout: 30000 }).then(() => true, () => false);
    check('(5c) a file dropped on Designs\' open dialog over another page: Save the design still opens its Set up (not Run)', dropped, await page.evaluate(() => location.hash));

    // ---- (5c2) a file dropped on the Designs page (no dialog open) opens Designs' upload: Save opens its Set up ------
    const drop2Name = 'designs-drop2-' + TS;
    await page.evaluate(() => IL.go('#designs'));
    await page.waitForSelector('#designs-list a.design', { timeout: 10000 }).catch(() => null);
    await page.evaluate(async ({ b64, name }) => {
      const bin = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const dt = new DataTransfer();
      dt.items.add(new File([bin], name, { type: 'application/octet-stream' }));
      const list = document.getElementById('designs-list');
      for (const t of ['dragenter', 'dragover', 'drop']) list.dispatchEvent(new DragEvent(t, { dataTransfer: dt, bubbles: true, cancelable: true }));
    }, { b64: PADCAL.toString('base64'), name: 'padcal.kicad_pcb' });
    await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 }).catch(() => null);
    await page.fill('#board-import-name', drop2Name);
    await page.click('#btn-board-import-save');
    const dropped2 = await page.waitForFunction((n) => location.hash.startsWith('#designs/setup/') && (document.getElementById('design-name') || {}).textContent === n, drop2Name,
      { timeout: 30000 }).then(() => true, () => false);
    check('(5c2) a file dropped on the Designs page: Save the design opens its Set up (not Run)', dropped2, await page.evaluate(() => location.hash));

    // ---- (5d) the menu line follows a design saved in Run's Board step in this tab ('boards'; another tab's: (2c)): the
    // dialog with Run's options, saved, then closed without Use this design
    await holdImport();
    await page.evaluate(() => IL.go('#machine'));
    const elseName = 'designs-else-' + TS;
    const subNow = () => page.evaluate(() => (document.querySelector('#tab-btn-designs .nav__s') || {}).textContent || '');
    const before5 = await subNow();
    await page.evaluate(() => IL.boardImport.open({ onSaved: IL.boardFlow.onImported }));
    await page.waitForSelector('#dlg-board-import #board-import-drop', { state: 'visible', timeout: 10000 });
    await page.setInputFiles('#board-import-file', { name: 'padcal.kicad_pcb', mimeType: 'application/octet-stream', buffer: PADCAL });
    await page.waitForSelector('#dlg-board-import[data-state="preview"]', { timeout: 60000 });
    await page.fill('#board-import-name', elseName);
    await page.click('#btn-board-import-save');
    await page.waitForSelector('#dlg-board-import[data-state="saved"]', { timeout: 60000 }).catch(() => null);
    await page.keyboard.press('Escape');
    const n5 = (t) => { const m = /(\d+) to set up/.exec(t); return m ? Number(m[1]) : 0; };
    const after5 = await page.waitForFunction((b) => { const t = (document.querySelector('#tab-btn-designs .nav__s') || {}).textContent || ''; const m = /(\d+) to set up/.exec(t);
      return !!m && Number(m[1]) > b && t; }, n5(before5), { timeout: 15000 }).then((h) => h.jsonValue(), () => subNow());
    check('(5d) the menu line counts a design saved in Run\'s Board step, without a visit to Designs', n5(after5) === n5(before5) + 1, { before5, after5 });
    ST.importHeld = null;

    // ---- (1) Designs: every design's card and state, the menu line --------------------------------------------------
    await page.evaluate(() => IL.go('#designs'));
    const files = await boardsOf(page);
    const want = [];
    for (const f of files) want.push({ name: f.name, file: f.file, imported: f.imported, state: (await checkOf(page, f.file)).state, art: !!(f.art && f.art.id) });
    await page.waitForFunction((n) => document.querySelectorAll('#designs-list a.design[data-state]:not([data-state="reading"])').length === n
      && Array.from(document.querySelectorAll('#designs-list a.design[data-art]')).every((a) => a.querySelector('.design__img img')), files.length, { timeout: 15000 }).catch(() => null);
    const cards = await page.evaluate(() => Array.from(document.querySelectorAll('#designs-list a.design')).map((a) => ({ name: a.dataset.name, file: a.dataset.file,
      state: a.dataset.state, href: a.getAttribute('href'), pic: !!a.querySelector('.design__img img'), s: (a.querySelector('.design__s') || {}).textContent || '' })));
    const same = want.every((w) => cards.some((c) => c.file === w.file && c.state === w.state && c.href === routeOf(w) && (c.pic || !w.art)));
    check('(1) Designs: a card per design with its picture, its state from its check, and a link to its Set up', cards.length === want.length && same, { want, cards });
    const words = { ready: /^Ready · top side$/, wait: /^Waits on \d+ pad groups?$/, tip: /needs? another tip/ };
    check('(1) the state in words ("Ready · top side", "Waits on N pad groups", "… needs another tip")', cards.every((c) => words[c.state] && words[c.state].test(c.s.trim())),
      cards.map((c) => [c.state, c.s]));
    const ready = want.filter((w) => w.state === 'ready').length;
    const sub = await page.evaluate(() => (document.querySelector('#tab-btn-designs .nav__s') || {}).textContent || '');
    const subWant = [ready ? ready + ' ready' : null, want.length - ready ? (want.length - ready) + ' to set up' : null].filter(Boolean).join(' · ');
    check('(1) the menu line counts them ("2 ready · 1 to set up")', sub === subWant, { sub, subWant });
    await page.click('#designs-list a.design[data-file="' + pc.file.replace(/"/g, '\\"') + '"]');
    check('(1) a card opens its design\'s Set up', await page.waitForFunction((h) => location.hash === h && !document.getElementById('design-setup').hidden, routeOf(pc),
      { timeout: 10000 }).then(() => true, () => false));

    // ---- (6) a ready design: Make a board ----------------------------------------------------------------------------
    const rd = want.find((w) => w.state === 'ready');
    check('(6) a ready design in this session (ftp)', !!rd, want);
    if (rd) {
      await page.evaluate((h) => IL.go(h), routeOf(rd));
      await page.waitForSelector('#btn-design-make', { state: 'visible', timeout: 15000 }).catch(() => null);
      steps = await stepsOf(page);
      check('(6) ready: the steps that are built are done, the others say not built yet', steps.every((x) => x.state === 'done' || x.state === 'notyet')
        && byK('paste').state === 'done', steps);
      await page.click('#btn-design-make');
      const run = await page.waitForFunction((f) => { const b = document.querySelector('#rs-designs .design[aria-pressed="true"]');
        return location.hash === '#run' && !!b && b.dataset.board === 'file:' + f && !b.closest('[hidden]'); }, rd.file, { timeout: 15000 }).then(() => true, () => false);
      check('(6) Make a board: Run › Start opens with this design chosen', run, await page.evaluate(() => ({ hash: location.hash,
        chosen: ((document.querySelector('#rs-designs .design[aria-pressed="true"]') || {}).dataset || {}).board || null })));
    }

    // ---- (6b) a design saved after Run read its list, ready with a tip group the engine does not block on: Set up shows
    // no "Change the tip" row and no tip box, and Make a board opens Start with it chosen (Start once said "Choose a design")
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !!IL.run.boards && !IL.run.boardsLoading, null, { timeout: 15000 }).catch(() => null);
    const lateName = 'designs-late-' + TS;
    ST.okTipFile = lateName;
    const up6 = await uploadDesign(page, 'padcal.kicad_pcb', PADCAL, lateName);
    const late = up6.saved && (await boardsOf(page)).find((f) => f.file === up6.saved.file);
    await page.waitForFunction((h) => location.hash === h && !document.getElementById('design-setup').hidden && document.querySelectorAll('#design-steps > li').length >= 7,
      late ? routeOf(late) : '#none', { timeout: 15000 }).catch(() => null);
    const s6 = await stepsOf(page);
    const p6 = await page.evaluate(() => ({ title: (document.querySelector('#panel .panel__title h2') || {}).textContent || null,
      box: !(document.getElementById('design-rest-block') || {}).hidden }));
    check('(6b) a tip group the engine does not block on (ok): Ready, no "Change the tip" row, no tip box, Make a board', !!late && !s6.some((x) => /^tip:/.test(x.k || ''))
      && p6.title === 'Ready' && !p6.box && await shown(page, '#btn-design-make'), { late: late && late.file, steps: s6.map((x) => x.k + '=' + x.state), p6 });
    if (late && await shown(page, '#btn-design-make')) await page.click('#btn-design-make');
    const run6 = late ? await page.waitForFunction((f) => { const b = document.querySelector('#rs-designs .design[aria-pressed="true"]');
      return location.hash === '#run' && !!b && b.dataset.board === 'file:' + f && !b.closest('[hidden]'); }, late.file, { timeout: 15000 }).then(() => true, () => false) : false;
    check('(6b) Make a board on a design saved after Run read its list: Start opens with it chosen', run6, await page.evaluate(() => ({ hash: location.hash,
      chosen: ((document.querySelector('#rs-designs .design[aria-pressed="true"]') || {}).dataset || {}).board || null, start: (document.getElementById('rs-start-why') || {}).textContent || null })));
    ST.okTipFile = null;

    // one wording for a failed read of the list (#48's re-checks): kept cards, or none
    const KEPT = 'These are the designs from before. Could not read them again: ', NONE = 'Could not read the designs: ';
    const noAdvice = (t) => !!t && !/outcome unknown|look at the machine/i.test(t);   // a read changes nothing on the machine
    // ---- (6c) Run reads the list again on every entry: a read that fails then keeps the cards Start has (no error box
    // instead of them; the re-check of (6b)'s fix)
    await page.evaluate(() => IL.go('#machine'));
    const fail6 = (route) => (route.request().method() === 'GET' ? route.fulfill({ status: 500, json: { error: 'test', reason: 'The test failed this read.' } })
      : route.fallback());
    await page.route('**/api/boards', fail6);
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !IL.run.boardsLoading && !!IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);
    const c6 = await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r({ err: IL.run.boardsErr,
      cards: document.querySelectorAll('#rs-designs .design[data-board]').length, box: !!document.querySelector('#rs-designs .notebox--block'),
      note: ((n) => (n ? (n.querySelector(':scope > span') || n).textContent : ''))(document.querySelector('#rs-designs .rf-note')), first: !!document.querySelector('#rs-designs > .rf-note:first-child') })))));
    check('(6c) a failed read of the list on entering Run: Start keeps its design cards, no error box instead', !!c6.err && c6.cards > 0 && !c6.box, c6);
    check('(6c) and says above them that the list could not be read again, then the reason (the re-checks\' MINORs)', c6.first
      && c6.note === KEPT + c6.err, c6);
    await page.unroute('**/api/boards', fail6);
    // the next good read takes the note away (a note shown always, or never cleared, fails here)
    await page.evaluate(() => IL.go('#machine'));
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !!IL.run.boards && !IL.run.boardsLoading && !IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);
    const c6d = await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r({ err: IL.run.boardsErr,
      cards: document.querySelectorAll('#rs-designs .design[data-board]').length, note: !!document.querySelector('#rs-designs .rf-note') })))));
    check('(6c) the next good read: the cards without the note', !c6d.err && c6d.cards > 0 && !c6d.note, c6d);

    // ---- (6d) a read with no answer at all (status 0) keeps every list it had and says so in the same words: Start, Run's
    // Board step and Designs (whose error box once replaced its cards)
    const lost = (route) => (route.request().method() === 'GET' ? route.abort('failed') : route.fallback());
    await page.evaluate(() => IL.go('#machine'));
    await page.route('**/api/boards', lost);
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !IL.run.boardsLoading && !!IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);
    const d6 = await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r({ err: IL.run.boardsErr,
      cards: document.querySelectorAll('#rs-designs .design[data-board]').length, note: ((n) => (n ? (n.querySelector(':scope > span') || n).textContent : ''))(document.querySelector('#rs-designs .rf-note')),
      retry: !!document.querySelector('#rs-designs .rf-note button.list-retry') })))));
    check('(6d) no answer: Start keeps its cards under "' + KEPT + '<reason>" with Retry, and no "look at the machine"', d6.cards > 0 && d6.note === KEPT + d6.err
      && noAdvice(d6.note) && d6.retry, d6);
    await page.evaluate(() => IL.go('#run/board'));
    const hint6 = await page.waitForFunction(() => { const h = document.getElementById('board-hint'); return h && !h.closest('[hidden]') && IL.run.boardsErr ? h.textContent : null; },
      null, { timeout: 10000 }).then((h) => h.jsonValue(), () => null);
    check('(6d) and Run\'s Board step says the same', hint6 === KEPT + d6.err, { hint6, err: d6.err });
    await page.evaluate(() => IL.go('#designs'));
    const des6 = await page.waitForFunction(() => { const n = document.querySelector('#designs-list .designs-note, #designs-list .notebox--block');
      return n ? { note: (n.querySelector(':scope > span') || n).textContent, retry: !!n.querySelector('button.list-retry'), box: n.classList.contains('notebox--block'),
        first: n === document.getElementById('designs-list').firstElementChild,
        cards: document.querySelectorAll('#designs-list a.design').length } : null; }, null, { timeout: 15000 }).then((h) => h.jsonValue(), () => null);
    check('(6d) and Designs keeps its cards under the same words with Retry, first (no error box instead of them)', !!des6 && !des6.box && des6.first
      && des6.cards > 0 && des6.note === KEPT + d6.err && des6.retry, des6);
    await page.unroute('**/api/boards', lost);
    await page.evaluate(() => IL.go('#machine'));
    await page.evaluate(() => IL.go('#designs'));
    const des6b = await page.waitForFunction(() => !document.querySelector('#designs-list .designs-note, #designs-list .notebox--block')
      && document.querySelectorAll('#designs-list a.design').length > 0, null, { timeout: 15000 }).then(() => true, () => false);
    check('(6d) the next good read on Designs: its cards without the note', des6b, null);
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !!IL.run.boards && !IL.run.boardsLoading && !IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);
    // a bare HTTP error (no reason in its body) is named: "HTTP 502 Bad Gateway." (HTTP/2 brings "502 " alone)
    const bare = (route) => (route.request().method() === 'GET' ? route.fulfill({ status: 502, body: '' }) : route.fallback());
    await page.evaluate(() => IL.go('#machine'));
    await page.route('**/api/boards', bare);
    await page.evaluate(() => IL.go('#run'));
    const err502 = await page.waitForFunction(() => !IL.run.boardsLoading && IL.run.boardsErr, null, { timeout: 15000 }).then((h) => h.jsonValue(), () => null);
    check('(6d) a bare HTTP 502 is named "HTTP 502 …."', /^HTTP 502\b.*\.$/.test(err502 || '') && noAdvice(err502), err502);
    await page.unroute('**/api/boards', bare);
    await page.evaluate(() => IL.go('#machine'));
    await page.evaluate(() => IL.go('#run'));
    await page.waitForFunction(() => !!IL.run.boards && !IL.run.boardsLoading && !IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);

    // ---- (6e) a tab whose reads of the list get no answer: Start, Set up and Designs say "Could not read the designs:
    // <reason>" with Retry; nothing reads the list again by itself meanwhile (Start once read it on every render); Retry
    // reads it; OpenPnP answering again (online) reads a list whose last read failed
    const tab = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    tab.on('pageerror', (e) => jsErrors.push('tab 2: ' + String(e)));
    tab.on('dialog', (d) => { dialogs.push(d.type() + ': ' + d.message()); d.dismiss().catch(() => {}); });
    tab.on('request', (r) => { if (r.method() !== 'GET' && new URL(r.url()).pathname.startsWith('/api/')) writes.push(r.method() + ' ' + new URL(r.url()).pathname); });
    let failing = true, delay6 = 0, reads6 = 0, done6 = 0, unsettled6 = 0;
    await tab.route('**/api/boards', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      reads6++;
      try {
        if (!failing) return await route.fallback();
        if (delay6) await new Promise((r) => setTimeout(r, delay6));   // a slow "no answer": the page shows "Reading…" meanwhile
        return await route.abort('failed');
      } finally { done6++; }
    });
    // settled: every read of the list ended and none for 800 ms, so the page shows its outcome; at most ~12 s (a page that
    // reads on every render never settles: counted, and a check fails at the end, no hang)
    const settle = async () => { for (let i = 0; i < 15; i++) { const n = reads6; await tab.waitForTimeout(800); if (n === reads6 && done6 === reads6) return; } unsettled6++; };
    const shown6 = (sel) => tab.evaluate((q) => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => { const b = document.querySelector(q);
      r(b && !b.closest('[hidden]') ? { text: (b.querySelector(':scope > span') || b).textContent, retry: !!b.querySelector('button.list-retry') } : null); }))), sel);
    await tab.goto(BASE + '#run');
    // the first status answer (online) comes before the count: its own read and the menu's are not "by itself"
    await tab.waitForFunction(() => !!(window.IL && IL.S && IL.S.online === true && IL.run && IL.run.boardsErr), null, { timeout: 20000 }).catch(() => null);
    await settle();
    const reason6 = await tab.evaluate(() => IL.run.boardsErr);
    const e6 = await shown6('#rs-designs .notebox--block');
    check('(6e) a first read with no answer: Start says "' + NONE + '<reason>", no "look at the machine", with Retry', !!reason6 && !!e6
      && e6.text === NONE + reason6 && noAdvice(e6.text) && e6.retry, { e6, reason6 });
    let before6 = reads6;
    await tab.waitForTimeout(2000);
    check('(6e) and nothing reads the list again by itself (Start once read it on every render)', reads6 === before6, { before: before6, after: reads6 });
    // Retry from the keyboard, the read slow enough that the box shows "Reading…" meanwhile (the pressed button is gone)
    delay6 = 300;
    await tab.evaluate(() => { const b = document.querySelector('#rs-designs .notebox--block button.list-retry'); if (b) b.dataset.pressed = '1'; });
    before6 = reads6;
    if (e6 && e6.retry) { await tab.focus('#rs-designs .notebox--block button.list-retry'); await tab.keyboard.press('Enter'); }
    await settle();
    const e6b = await shown6('#rs-designs .notebox--block');
    check('(6e) Start\'s Retry reads the list again (no answer again: the same words)', reads6 > before6 && !!e6b && e6b.text === NONE + reason6, { reads: reads6 - before6, e6b });
    const focus6 = await tab.evaluate(() => { const a = document.activeElement;
      return a && a !== document.body ? { retry: a.matches('#rs-designs .notebox--block button.list-retry'), same: a.dataset.pressed === '1' } : null; });
    check('(6e) and a Retry pressed from the keyboard leaves the focus on the Retry shown after the read (a new one)', !!focus6 && focus6.retry && !focus6.same, focus6);
    // but never takes the focus back from where the operator moved it during a slow read
    delay6 = 1500;
    if (e6b && e6b.retry) { await tab.focus('#rs-designs .notebox--block button.list-retry'); await tab.keyboard.press('Enter'); }
    await tab.waitForTimeout(300);
    await tab.focus('#tab-btn-designs');
    await settle();
    const moved6 = await tab.evaluate(() => (document.activeElement || {}).id || null);
    check('(6e) a slow read: the focus stays where the operator moved it meanwhile (the menu), not back on Retry', moved6 === 'tab-btn-designs', moved6);
    delay6 = 0;
    await tab.evaluate((k) => IL.go('#designs/setup/' + k), late ? encodeURIComponent(late.file) : 'none');
    await settle();
    const set6 = await shown6('#design-meta');
    check('(6e) Set up of a design with no list read says the same, with Retry', !!set6 && set6.text === NONE + reason6 && set6.retry, set6);
    before6 = reads6;
    if (set6 && set6.retry) await tab.click('#design-meta button.list-retry');
    await settle();
    check('(6e) Set up\'s Retry reads the list again', reads6 > before6, { reads: reads6 - before6 });
    await tab.evaluate(() => IL.go('#designs'));
    await settle();
    const e6d = await shown6('#designs-list .notebox--block');
    check('(6e) Designs says the same, with Retry', !!e6d && e6d.text === NONE + reason6 && e6d.retry, e6d);
    failing = false;
    if (e6d && e6d.retry) await tab.click('#designs-list .notebox--block button.list-retry');
    const ok6d = await tab.waitForFunction(() => !document.querySelector('#designs-list .notebox--block') && document.querySelectorAll('#designs-list a.design').length > 0,
      null, { timeout: 15000 }).then(() => true, () => false);
    check('(6e) Designs\' Retry reads the list: its cards', !!e6d && e6d.retry && ok6d, null);
    await tab.evaluate(() => IL.go('#run'));
    await tab.waitForFunction(() => !!IL.run.boards && !IL.run.boardsLoading && !IL.run.boardsErr, null, { timeout: 15000 }).catch(() => null);
    failing = true;
    await tab.evaluate(() => IL.go('#designs'));   // its visit reads again: no answer, its note over the cards
    await settle();
    await tab.evaluate(() => IL.go('#run'));       // and Start's
    await settle();
    const n6 = await shown6('#rs-designs .rf-note');
    failing = false;
    before6 = reads6;
    await tab.evaluate(() => IL.emit('online', true));
    await settle();
    const n6b = await shown6('#rs-designs .rf-note');
    const cards6 = await tab.evaluate(() => document.querySelectorAll('#rs-designs .design[data-board]').length);
    check('(6e) OpenPnP answering again (online) reads both lists again (Start\'s and Designs\'): Start\'s note goes, its cards stay', !!n6
      && reads6 - before6 >= 2 && !n6b && cards6 > 0, { note: n6, after: n6b, reads: reads6 - before6, cards6 });
    check('(6e) every step settled: no read of the list in flight or repeating by itself', unsettled6 === 0, { unsettled6, reads6, done6 });
    await tab.close();

    // ---- (7) ----------------------------------------------------------------------------------------------------------
    const allowed = ['POST /api/boards/import', 'POST /api/boards/import/save', 'POST /api/boards/import/attach', 'POST /api/pad-profile/apply', 'POST /api/package-mappings',
      'PUT /api/pad-types/chip-0603'];
    check('(7) nothing moves: only uploads, the apply and the test\'s own set-up writes were sent', writes.every((w) => allowed.includes(w)), [...new Set(writes)]);
    check('no JavaScript errors', jsErrors.length === 0, jsErrors.slice(0, 8));
    check('no native dialogs', dialogs.length === 0, dialogs);
  } catch (e) {
    check('test ran to the end', false, String(e && e.stack || e).slice(0, 2000));
    try { await page.screenshot({ path: path.join(OUT, 'designs-failure.png') }); } catch (x) { /* the page may be gone */ }
  } finally {
    const failed = results.filter((r) => !r.ok);
    const out = { at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), passed: results.length - failed.length, failed: failed.length, results };
    fs.writeFileSync(path.join(OUT, 'designs-results.json'), JSON.stringify(out, null, 2));
    console.log('\n' + out.passed + ' passed, ' + out.failed + ' failed (' + out.seconds + ' s)');
    await browser.close();
    process.exit(failed.length ? 1 : 0);
  }
})();
