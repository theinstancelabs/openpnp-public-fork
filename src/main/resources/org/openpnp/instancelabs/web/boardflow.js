/* boardflow.js: the board drawing's glue to Run and Records (spec docs/instancelabs/BOARD_IMPORT_DESIGN.md §5.1, §5.8–5.10,
   §5.13). run.js and records.js call it through five hooks (§5.1.1), each a no-op while this file is absent:
     H1 Board step body: mountBoardStep        H2 hero view: onPad, onPart        H3 hero render: hero
     H4 Records map: recordsMap                H5 Records rebuild: the replaced map view is destroyed (records.js)
   Nothing here moves the machine.

   API (IL.boardFlow):
     mountBoardStep(body, {select(value), form() → {sel, x, y, z, rot}, setXY(x, y), loadBoards() → Promise})
         H1: the Board step gets #btn-board-upload (the upload dialog), #board-thumbs (one row of board pictures, a click
         chooses that board), #board-imported (where the drawing comes from) and #btn-board-use-fid ("Camera is on FID1":
         the board origin from the top camera centred on the board's first fiducial)
     hero(bv, {plan, sel, form, mode, s, hero, stageBar, row}) → {shown}
         H3, on every render of the Run hero (cheap: patches only). With a plan: its drawing under the dots (Quiet unless
         the operator chose otherwise: localStorage il.pv.picture), the picture control #pv-picture, the path preview
         (#pv-play, #run-preview) and #btn-pad-inspect in the stage bar. Without a plan: the chosen board's drawing at the
         form's seat, Full, with the live top camera and a 5 mm scale bar (shown: true while it is drawn).
     onPad(padId, ev), onPart(ref, ev)    H2: a click on a pad or a part opens the pad inspector (padinspect.js)
     recordsMap(bv, plan)                 H4: the plan's drawing under Records' done-dot map, Quiet
     onImported(saved)                    "Use this board" in the upload dialog: the board list again, the board chosen
     preview() → the hero's path preview (IL.pathPreview: play, pause, seek, state), null before the hero is drawn
   Board drawings come from IL.boardart.get (the browser's HTTP cache keeps them: the server marks them immutable). */
'use strict';
(function () {
  const el = IL.el;
  const PIC_KEY = 'il.pv.picture';
  const PICS = [['full', 'Full'], ['quiet', 'Quiet'], ['off', 'Off']];
  const PIC_TITLE = 'Board picture: Full (the whole board), Quiet (pads, holes and the outline, so the dots stand out) or Off (no drawing)';
  const RUNNING = 'Upload the board when the run has finished.';
  const THUMBS_MAX = 12;
  const ARTS_KEPT = 8;
  const M = {
    bv: null,                    // the Run hero's view
    stage: null,                 // stage-bar controls of one hero: {bar, pic, inspect, pp, cap}
    arts: new Map(),             // art id → art, null (gone), or a Promise (loading); the newest few are kept
    thumbs: new Map(),           // art id → blob URL of its thumbnail (the art itself is not kept for it)
    step: null,                  // the Board step additions: {body, o, upload, thumbs, imported, fid}
    just: null,                  // the board an upload just saved: {file, fiducial} (its next-step hint)
    pic: (() => { const v = IL.lsGet(PIC_KEY); return v === 'full' || v === 'off' ? v : 'quiet'; })(),
    seatKey: null,
  };

  // ------------------------------------------------------------------------------------------ board drawings
  /** The art of an id: the art, null (none or gone), or undefined while it loads (IL.render follows its arrival). */
  function art(id) {
    if (!id) return null;
    if (M.arts.has(id)) {
      const a = M.arts.get(id);
      return a instanceof Promise ? undefined : a;
    }
    artP(id);
    return undefined;
  }
  function artP(id) {
    if (M.arts.has(id)) { const a = M.arts.get(id); return a instanceof Promise ? a : Promise.resolve(a); }
    const pr = IL.boardart.get(id).then((a) => a || null, () => {
      // a failed read (OpenPnP restarting), not a drawing that is gone: asked again on a render 5 s later
      setTimeout(() => { if (M.arts.get(id) === null) { M.arts.delete(id); IL.render(); } }, 5000);
      return null;
    }).then((a) => {
      M.arts.set(id, a);
      prune();
      IL.render();
      return a;
    });
    M.arts.set(id, pr);
    return pr;
  }
  /** Keeps the newest few drawings (each 100–250 kB of JSON); the views hold their own. */
  function prune() {
    const keep = new Set([M.bv && M.bv.art && M.bv.art.id]);
    for (const id of Array.from(M.arts.keys())) {
      if (M.arts.size <= ARTS_KEPT) break;
      if (!keep.has(id) && !(M.arts.get(id) instanceof Promise)) M.arts.delete(id);
    }
  }
  const isNum = IL.isNum;
  /** Board → machine affine of a seat (top side: T(x, y)·R(rotation), O/util/Utils2D.java:105-119). */
  function seatMatrix(x, y, rotDeg) {
    const r = (Number(rotDeg) || 0) * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
    return [c, s, -s, c, Number(x), Number(y)];
  }
  /** KiCad's major version from the file's version date (§2.5). */
  function kicadMajor(v) {
    const n = Number(v) || 0;
    return n >= 20260206 ? 10 : n >= 20241229 ? 9 : n >= 20240108 ? 8 : n >= 20221018 ? 7 : n >= 20211014 ? 6 : 5;
  }
  const when = (ms) => {
    const t = IL.toClient(ms);
    return new Date(t).toDateString() === new Date().toDateString() ? IL.fmtTime(t) : IL.fmtDateTime(t);
  };

  // ------------------------------------------------------------------------------------------ the board list
  /** GET /api/boards as the Run page holds it (one list for the select, the thumbnails and the dialog). */
  const boards = () => (IL.run && IL.run.boards) || null;
  function entryOf(value) {
    const b = boards();
    const v = String(value || '');
    if (!b || !v) return null;
    if (v.startsWith('file:')) return (b.files || []).find((x) => x.file === v.slice(5)) || null;
    if (v.startsWith('job:')) return (b.job || []).find((x) => String(x.boardLocationId) === v.slice(4)) || null;
    return null;
  }
  const entryKey = (e, job) => (job ? 'job:' + e.boardLocationId : 'file:' + e.file);
  const partsOf = (e) => Math.max(0, (Number(e.placements) || 0) - ((e.fiducials || []).length));
  /** "75 × 75 mm", "51.6 × 89.0 mm" (both to the same tenth when either needs it), or '' when the list has no size. */
  function sizeText(e) {
    if (!isNum(e.widthMm) || !isNum(e.heightMm) || !(e.widthMm > 0) || !(e.heightMm > 0)) return '';
    const d = [e.widthMm, e.heightMm].some((v) => Math.abs(v - Math.round(v)) >= 0.05) ? 1 : 0;
    return IL.fmt(e.widthMm, d) + '\u00a0×\u00a0' + IL.fmt(e.heightMm, d) + '\u00a0mm';
  }
  /** The board's first fiducial by name (FID1 before FID2 and FID10): the one "Camera is on FID1" names. */
  const NAT = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });
  const firstFid = (e) => ((e && e.fiducials) || []).slice().sort((a, b) => NAT.compare(String(a.id), String(b.id)))[0] || null;

  // ------------------------------------------------------------------------------------------ H1: the Board step
  function mountBoardStep(body, o) {
    const row = body.querySelector('.board-sel-row');
    if (!row) return;
    const upload = el('button', { type: 'button', class: 'btn bf-upload', id: 'btn-board-upload',
      title: 'Read a KiCad board file (.kicad_pcb) and save it as a board here. Nothing moves.' },
    IL.icon('i-plus'), el('span', { text: 'Upload KiCad board…' }));
    upload.addEventListener('click', () => IL.boardImport.open({ onSaved: onImported }));
    const thumbs = el('div', { class: 'board-thumbs', id: 'board-thumbs', role: 'group', 'aria-label': 'Boards with a drawing: press one to choose it', hidden: true });
    const imported = el('div', { class: 'reason bf-imported', id: 'board-imported', hidden: true });
    thumbs.addEventListener('scroll', () => edges(thumbs), { passive: true });
    // the upload button first (always in view), then one row of pictures of every board with a drawing, two whole
    // pictures in view at a time
    row.after(el('div', { class: 'bf-boards' }, upload, thumbs), imported);
    const fid = el('button', { type: 'button', class: 'capture', id: 'btn-board-use-fid' }, IL.icon('i-capture'), el('span', { text: 'Camera is on FID1' }));
    fid.addEventListener('click', () => useFid());
    const cam = body.querySelector('#btn-board-use-cam');
    if (cam) cam.after(fid);
    else { const caps = body.querySelector('#board-captures'); if (caps) caps.prepend(fid); }
    if (M.step && M.step.io) M.step.io.disconnect();
    M.step = { body, o: o || {}, upload, thumbs, imported, fid, io: null };
    patchStep(IL.S.status);
  }
  function stepForm() { const st = M.step; return (st && st.o.form && st.o.form()) || {}; }
  /** The Board step additions, patched on every Run render. */
  function patchStep(s) {
    const st = M.step;
    if (!st || !st.body.isConnected) return;
    IL.gate(st.upload, IL.taskRunning(s) || (IL.padProfile && IL.padProfile.jobLock(s)) ? RUNNING : null);
    const form = stepForm();
    const sel = form.sel || '';
    const e = entryOf(sel);
    patchThumbs(st, sel);
    // where the drawing comes from
    let text = null;
    if (e && e.art) {
      const im = e.imported;
      const k = im && im.kicadVersion ? ' (KiCad ' + kicadMajor(im.kicadVersion) + ')' : '';
      if (e.art.kind === 'kicad' && im && im.how === 'attach') text = 'KiCad drawing from ' + (im.sourceName || 'a .kicad_pcb') + k + ', added ' + when(im.atMs) + '.';
      else if (e.art.kind === 'kicad' && im) {
        const size = isNum(e.widthMm) && isNum(e.heightMm) ? IL.fmt(e.widthMm, 1) + '\u00a0×\u00a0' + IL.fmt(e.heightMm, 1) + '\u00a0mm' + ', ' : '';
        text = 'Imported ' + when(im.atMs) + ' from ' + (im.sourceName || 'a .kicad_pcb') + k + ': ' + size + IL.plural(partsOf(e), 'part') + ', '
          + IL.plural((e.fiducials || []).length, 'fiducial') + '.';
      } else if (e.art.kind === 'fallback') text = 'Drawn from the board file (no KiCad drawing).' + (e.art.note ? ' ' + e.art.note : '');
      if (text && e.origin === 'estimated') text += ' No board outline: place it with "Camera is on ' + (firstFid(e) || { id: 'FID1' }).id + '".';
    }
    const pl = IL.S.plan;
    const just = !!M.just && !!e && M.just.file === e.file && !(pl && pl.source && pl.source.file === e.file);
    IL.keyed(st.imported, JSON.stringify([text, just]), () => (text ? [el('span', { text }), just
      ? el('span', { class: 'bf-next', text: 'Put the top camera on ' + (M.just.fiducial || 'FID1') + ' and press "Camera is on ' + (M.just.fiducial || 'FID1')
        + '" (or type the origin), then Plan. Register board then finds the fiducials.' }) : null] : null));
    IL.show(st.imported, !!text);
    // a board just imported: its next step replaces the Board step's general hint (one instruction, not two)
    const hint = st.body.querySelector('#board-hint');
    if (hint && !hint.classList.contains('reason--block')) IL.show(hint, !(text && just));
    // Camera is on FID1: origin = camera − R(rotation)·(bx, by) of the board's first fiducial
    const f = firstFid(e);
    const cam = IL.positions(s).cam;
    const job = sel.startsWith('job:');
    const name = f ? f.id : 'FID1';
    const lab = st.fid.lastChild;
    if (lab.textContent !== 'Camera is on ' + name) lab.textContent = 'Camera is on ' + name;
    const why = job ? 'Job boards take their position from OpenPnP'
      : !e ? 'Choose a board file first'
        : !f ? (e.fiducials ? 'This board has no fiducials: use the camera X/Y on its origin, or type it' : 'The board list has no fiducial positions for this board')
          : IL.lockReason(s, { connected: true, pressureOff: false }) || (!cam ? 'No top camera position' : null);
    IL.gate(st.fid, why);
    if (!why) IL.setAttr(st.fid, 'title', 'Board origin X/Y from the top camera centred on ' + name + ' (board ' + IL.fmt(f.bx, 2) + ', ' + IL.fmt(f.by, 2)
      + ' mm) and the rotation entered: jog the camera onto ' + name + ' in OpenPnP first');
  }
  function patchThumbs(st, sel) {
    const b = boards();
    const list = [];
    if (b) {
      for (const x of b.job || []) if (x.art && x.art.id) list.push({ key: entryKey(x, true), e: x });
      for (const x of b.files || []) if (x.file && x.art && x.art.id) list.push({ key: entryKey(x, false), e: x });
    }
    IL.show(st.thumbs, list.length > 0);
    const shown = list.slice(0, THUMBS_MAX);
    const key = JSON.stringify(shown.map((t) => [t.key, t.e.art.id, t.e.name, t.e.placements, t.e.widthMm, t.e.heightMm]).concat([list.length]));
    if (IL.keyed(st.thumbs, key, () => {
      if (st.io) st.io.disconnect();
      st.io = window.IntersectionObserver ? new IntersectionObserver((ents) => {
        for (const en of ents) if (en.isIntersecting) { st.io.unobserve(en.target); loadThumb(en.target); }
      }, { root: st.thumbs }) : null;
      const out = shown.map((t) => {
        const e = t.e;
        const kicad = e.art.kind === 'kicad';
        const img = el('img', { class: 'board-thumb__img', alt: '', width: '56', height: '48', 'data-art': e.art.id });
        const size = sizeText(e);
        const btn = el('button', { type: 'button', class: 'board-thumb', 'data-board': t.key, 'aria-pressed': String(t.key === sel),
          title: e.name + '\n' + (size ? size + ' · ' : '') + IL.plural(partsOf(e), 'part') + (e.file ? '\n' + e.file : '') + (kicad ? '\nKiCad drawing' : '\nDrawn from the board file') },
        img, el('span', { class: 'board-thumb__t' }, el('span', { class: 'board-thumb__n', text: e.name }),
          size ? el('span', { class: 'board-thumb__s board-thumb__size', text: size }) : null,
          el('span', { class: 'board-thumb__s' }, IL.plural(partsOf(e), 'part'), kicad ? el('span', { class: 'board-thumb__tag', text: 'KiCad' }) : null)));
        btn.addEventListener('click', () => { if (st.o.select && !btn.disabled) st.o.select(t.key); });
        return btn;
      });
      if (list.length > THUMBS_MAX) out.push(el('span', { class: 'board-thumbs__more', text: '+' + (list.length - THUMBS_MAX) + ' more in the list' }));
      return out;
    })) {
      for (const img of st.thumbs.querySelectorAll('img[data-art]')) {
        if (M.thumbs.has(img.dataset.art)) img.src = M.thumbs.get(img.dataset.art);
        else if (st.io) st.io.observe(img);
        else loadThumb(img);
      }
    }
    const lock = IL.taskRunning(IL.S.status) ? 'Locked while the ' + ((IL.S.status.run && IL.S.status.run.label) || 'task') + ' runs' : null;
    for (const btn of st.thumbs.querySelectorAll('button.board-thumb')) {
      IL.setAttr(btn, 'aria-pressed', String(btn.dataset.board === sel));
      if (btn.disabled !== !!lock) btn.disabled = !!lock;
    }
    if (st.selShown !== sel + '|' + key) {
      st.selShown = sel + '|' + key;
      // the chosen board's picture in view, at the start of the row (never a half-cut card)
      const b = Array.from(st.thumbs.children).find((x) => x.dataset && x.dataset.board === sel);
      const r = b && b.getBoundingClientRect(), rr = st.thumbs.getBoundingClientRect();
      if (b && rr.width && (r.left < rr.left - 1 || r.right > rr.right + 1)) st.thumbs.scrollLeft += r.left - rr.left;
      edges(st.thumbs);
    }
  }
  /** Fades the row's edge where more pictures lie beyond it (a cut card then reads as "scroll for more"). */
  function edges(row) {
    const l = row.scrollLeft > 1, r = row.scrollLeft + row.clientWidth < row.scrollWidth - 1;
    row.classList.toggle('board-thumbs--l', l);
    row.classList.toggle('board-thumbs--r', r);
  }
  function loadThumb(img) {
    const id = img.dataset.art;
    artP(id).then((a) => {
      if (!a) return;
      const url = IL.boardart.thumbUrl(a, { w: 96, h: 64, id });
      M.thumbs.set(id, url);
      img.src = url;
    });
  }
  function useFid() {
    const st = M.step;
    const form = stepForm();
    const e = entryOf(form.sel);
    const f = firstFid(e);
    const cam = IL.positions().cam;
    if (!st || !f || !cam || !st.o.setXY) return;
    const r = (Number(form.rot) || 0) * Math.PI / 180;
    const x = cam.x - (f.bx * Math.cos(r) - f.by * Math.sin(r));
    const y = cam.y - (f.bx * Math.sin(r) + f.by * Math.cos(r));
    st.o.setXY(x, y);
    IL.toast('Board origin X ' + IL.fmt(x, 2) + ' · Y ' + IL.fmt(y, 2) + ' mm: ' + f.id + ' under the top camera, rotation ' + IL.fmtDeg(Number(form.rot) || 0) + '.', 'ok');
  }
  /** "Use this board" after an upload: the Board step, the list read again, the board chosen. */
  async function onImported(saved) {
    if (!saved || !saved.file) return;
    M.just = { file: saved.file, fiducial: saved.fiducial || null };
    if (!M.step || !M.step.body.isConnected) IL.go('#run/board');
    for (let i = 0; i < 40 && !(M.step && M.step.body.isConnected); i++) await new Promise((r) => setTimeout(r, 50));
    const st = M.step;
    if (!st) return;
    const value = 'file:' + saved.file;
    // the list again (a new board, or a board file that has its drawing now), until it names the board
    for (let i = 0; i < 3; i++) {
      if (st.o.loadBoards) await st.o.loadBoards();
      if (entryOf(value)) break;
      await new Promise((r) => setTimeout(r, 300));
    }
    if (st.o.select) st.o.select(value);
    IL.render();
  }

  // ------------------------------------------------------------------------------------------ H3: the Run hero
  function svgIcon(d) {
    const s = IL.svgEl('svg', { class: 'ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' });
    for (const x of d) s.append(IL.svgEl('path', { d: x }));
    return s;
  }
  const ICON_LAYERS = ['M12 4.5l8 4.2-8 4.2-8-4.2z', 'M4 12.6l8 4.2 8-4.2', 'M4 16.4l8 4.2 8-4.2'];
  const ICON_INSPECT = ['M10.5 5a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11z', 'M14.6 14.6L19.5 19.5'];
  function setPic(v) {
    M.pic = v;
    IL.lsSet(PIC_KEY, v);
    IL.render();
  }
  /** The stage-bar controls of a hero, made once: picture, path preview, pad inspector; the caption before planning. */
  function mountStage(bv, c) {
    const bar = c.stageBar;
    if (!bar) return null;
    if (M.stage && M.stage.bar === bar) return M.stage;
    if (M.stage) { M.stage.pp.destroy(); for (const n of [M.stage.pic, M.stage.inspect, M.stage.cap]) n.remove(); }
    const seg = el('div', { class: 'seg bf-pic__seg', role: 'group', 'aria-label': 'Board picture' },
      PICS.map(([v, t]) => el('button', { type: 'button', 'data-pic': v, 'aria-pressed': 'false', onclick: () => setPic(v) }, t)));
    const cycle = el('button', { type: 'button', class: 'btn bf-pic__cycle' }, svgIcon(ICON_LAYERS), el('span', { class: 'bf-pic__t' }));
    cycle.addEventListener('click', () => { const i = PICS.findIndex((x) => x[0] === M.pic); setPic(PICS[(i + 1) % PICS.length][0]); });
    const pic = el('div', { id: 'pv-picture', class: 'bf-pic', title: PIC_TITLE }, seg, cycle);
    const inspect = el('button', { type: 'button', class: 'btn btn--icon bf-inspect', id: 'btn-pad-inspect', 'aria-label': 'Inspect pads',
      title: 'Inspect pads: the first pad in path order, then the next part. Clicking a pad on the board does the same.' }, svgIcon(ICON_INSPECT));
    inspect.addEventListener('click', () => {
      const p = IL.S.plan;
      const first = p && ((p.dots || [])[0] || (p.pads || [])[0]);
      if (first) IL.padInspect.open({ padId: first.padId }, { bv: M.bv });
    });
    const host = c.row && c.row.parentNode ? c.row.parentNode : (c.hero && c.hero.querySelector('.hero__stage'));
    const pp = IL.pathPreview.create({ host, before: c.row, bv, onChange: () => IL.render() });
    const view = bar.querySelector(':scope > .seg') || bar.querySelector(':scope > .zoom');
    bar.insertBefore(pic, view);
    bar.append(pp.button, inspect);
    const cap = el('div', { class: 'bf-cap', id: 'board-preplan', hidden: true },
      el('b', { text: 'Plan to see the dots' }), el('span', { class: 'bf-cap__seat' }),
      el('span', { class: 'bf-cap__cam' }, el('i', { class: 'bf-cap__ring', 'aria-hidden': 'true' }), 'top camera now'));
    bar.prepend(cap);
    M.stage = { bar, pic, seg, cycle, inspect, pp, cap, level: 0, fitKey: null };
    if (window.ResizeObserver) {
      M.stage.ro = new ResizeObserver(() => { M.stage.fitKey = null; fitSoon(); });
      M.stage.ro.observe(bar);
    }
    return M.stage;
  }
  /**
   * Fits the stage bar (§5.13): run.js drops legend items while the legend overflows; when even then a legend item
   * would be cut, these controls give way one step at a time: the picture segment becomes one button "Picture: Quiet",
   * then its icon only, then "Preview path" its icon only (both keep their name as aria-label and title), then the
   * bar's gaps narrow.
   */
  const LEVELS = ['', 'bf-bar--l1', 'bf-bar--l2', 'bf-bar--l3', 'bf-bar--l4'];
  function fitSoon() {
    if (M.fitRaf) return;
    M.fitRaf = requestAnimationFrame(() => { M.fitRaf = null; fit(); });
  }
  function fit() {
    const st = M.stage;
    if (!st || !st.bar.isConnected || !st.bar.clientWidth) return;
    const legend = st.bar.querySelector(':scope > .legend');
    const key = [st.bar.clientWidth, IL.S.planId, st.pic.hidden, st.pp.button.hidden, st.inspect.hidden, legend ? legend.childElementCount : 0].join('|');
    if (key === st.fitKey) return;
    st.fitKey = key;
    const cut = () => st.bar.scrollWidth > st.bar.clientWidth + 1 || (!!legend && legend.scrollWidth > legend.clientWidth + 1);
    let lv = 0;
    for (;;) {
      for (let i = 1; i < LEVELS.length; i++) st.bar.classList.toggle(LEVELS[i], i === lv);
      if (lv >= LEVELS.length - 1 || !cut()) break;
      lv++;
    }
    st.level = lv;
  }
  function paintPic(st, on) {
    IL.show(st.pic, on);
    for (const b of st.seg.children) IL.setAttr(b, 'aria-pressed', String(b.dataset.pic === M.pic));
    const t = (PICS.find((x) => x[0] === M.pic) || PICS[1])[1];
    const next = PICS[(PICS.findIndex((x) => x[0] === M.pic) + 1) % PICS.length][1];
    IL.setText(st.cycle.lastChild, 'Picture: ' + t);
    IL.setAttr(st.cycle, 'aria-label', 'Board picture: ' + t + '. Press for ' + next + '.');
  }

  function hero(bv, c) {
    M.bv = bv;
    const s = c.s || IL.S.status;
    const st = mountStage(bv, c);
    patchStep(s);
    const h = c.hero;
    const p = c.plan;
    if (p) {
      if (bv.board) bv.setBoard(null);
      const id = p.board && p.board.art && p.board.art.id;
      const a = id ? art(id) : null;
      const want = a || null;   // loading: no drawing (never another board's) until it arrives
      if ((bv.art || null) !== want) bv.setArt(want);
      if (bv.pic !== M.pic) bv.picture(M.pic);
      const drawn = !!want && M.pic !== 'off';
      if (h) { h.classList.toggle('hero--art', drawn); h.classList.remove('hero--board'); }
      if (st) {
        IL.show(st.cap, false);
        paintPic(st, !!want);
        st.pp.setPlan(p);
        st.pp.allow((c.mode === 'idle' || c.mode === 'result') && !IL.taskRunning(s));
        IL.show(st.inspect, (p.pads || []).length > 0 && c.mode !== 'live' && c.mode !== 'stopped');
        if (h) h.classList.toggle('hero--preview', st.pp.state.active);
        fitSoon();
      }
      M.seatKey = null;
      return { shown: true };
    }
    // no plan: the chosen board at the form's seat (the hero before planning, §5.9)
    if (st) { st.pp.setPlan(null); st.pp.allow(false); IL.show(st.inspect, false); paintPic(st, false); }
    if (bv.plan) bv.setPlan(null);
    const form = c.form || {};
    const e = entryOf(form.sel);
    const id = e && e.art && e.art.id;
    const a = id ? art(id) : null;
    if (!a) {
      if (bv.board || bv.art) { bv.setBoard(null); bv.setArt(null); }
      bv.update({ cam: null });
      if (h) h.classList.remove('hero--art', 'hero--board', 'hero--preview');
      if (st) IL.show(st.cap, false);
      return { shown: false };
    }
    const known = !form.sel.startsWith('job:') ? isNum(form.x) && isNum(form.y) : isNum(e.x) && isNum(e.y);
    const sx = form.sel.startsWith('job:') ? e.x : form.x, sy = form.sel.startsWith('job:') ? e.y : form.y;
    const rot = form.sel.startsWith('job:') ? e.rotation : form.rot;
    const m = known ? seatMatrix(sx, sy, rot) : [1, 0, 0, 1, 0, 0];
    const key = m.map((v) => Number(v).toFixed(4)).join(',');
    if (!bv.board || bv.art !== a) bv.setBoard({ art: a, toMachine: m });
    else if (key !== M.seatKey) bv.setAffine(m);
    M.seatKey = key;
    if (bv.pic !== 'full') bv.picture('full');
    // the live top camera, when it is near the board (jogging it onto a fiducial shows on the drawing); a camera far
    // away would shrink the board to a stamp: the caption says how far instead. Not connected, OpenPnP reports 0, 0.
    const cam = s && s.machine && s.machine.enabled ? IL.positions(s).cam : null;
    const box = IL.boardart.outlineBox(a, m);
    const away = known && cam ? Math.hypot(Math.max(box.x0 - cam.x, 0, cam.x - box.x1), Math.max(box.y0 - cam.y, 0, cam.y - box.y1)) : null;
    const near = away !== null && away <= Math.max(10, 0.5 * Math.max(box.x1 - box.x0, box.y1 - box.y0));
    bv.update({ cam: near ? { x: cam.x, y: cam.y } : null });
    if (h) { h.classList.add('hero--art', 'hero--board'); h.classList.remove('hero--preview'); }
    if (st) {
      IL.show(st.cap, true);
      IL.setText(st.cap.querySelector('.bf-cap__seat'), known
        ? IL.nb('Board origin X ' + IL.fmt(sx, 2) + ' · Y ' + IL.fmt(sy, 2) + ' mm · ' + IL.fmtDeg(Number(rot) || 0))
        : 'Enter where the board sits, then Plan.');
      const cc = st.cap.querySelector('.bf-cap__cam');
      IL.show(cc, away !== null);
      IL.setText(cc.lastChild, near ? 'top camera now' : away !== null ? IL.nb('top camera ' + IL.fmt(away, 0) + ' mm from the board') : '');
      cc.classList.toggle('bf-cap__cam--far', !near);
    }
    return { shown: true };
  }

  // ------------------------------------------------------------------------------------------ H2 and H4
  // the inspector shows the plan's parts: only while the hero draws that plan (before planning, or with another board
  // chosen in the Board step, it draws a board that has no plan yet)
  const heroPlan = () => (IL.S.plan && (!M.bv || M.bv.plan) ? IL.S.plan : null);
  function onPad(padId) {
    if (heroPlan()) IL.padInspect.open({ padId }, { bv: M.bv || (IL.run && IL.run.bv) || null });
  }
  function onPart(ref) {
    if (heroPlan()) { IL.padInspect.open({ ref }, { bv: M.bv || (IL.run && IL.run.bv) || null }); return; }
    IL.toast(ref + ': plan the board to inspect its pads and dots.', 'info');
  }
  function recordsMap(bv, plan) {
    const id = plan && plan.board && plan.board.art && plan.board.art.id;
    if (!bv || !id) return;
    artP(id).then((a) => {
      if (!a || bv.destroyed) return;
      bv.setArt(a);
      bv.picture('quiet');
    });
  }

  IL.boardFlow = { mountBoardStep, hero, onPad, onPart, recordsMap, onImported, kicadMajor, preview: () => (M.stage ? M.stage.pp : null) };
})();
