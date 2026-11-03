/* boardimport.js: the upload dialog of a KiCad board (spec docs/instancelabs/BOARD_IMPORT_DESIGN.md §5.8, endpoints §3.2)
   and the page-wide drop guard. Nothing here moves the machine: the server reads the file and answers a preview (the
   board drawing, what it found, the warnings in order, the package and part ids a save would use); the operator names
   the board and saves it by sending the same bytes again (the server keeps no preview), or gives the drawing to an
   existing board file whose placements match (attach).

   States: choose (drop zone, file picker) → reading (Cancel aborts) → preview (board, found, warnings, packages, name)
   → saving (Esc and Cancel do nothing) → saved (pad types to add, Use this board, Upload another). A refused or failed
   read shows its reason with "Choose another file".

   API (IL.boardImport):
     IL.boardImport.open({file, onSaved(result)})   file: a File to read at once (a drop); onSaved: after "Use this
         board" (and before the dialog's pad-type links leave the page), with {file, name, slug, how: 'import' |
         'attach', fiducial} (the board file to select in the Board step)
     IL.boardImport.isOpen()
   The drop guard (at load): a file dropped outside the drop zone never replaces the app (and its STOP button); a
   .kicad_pcb opens the dialog with it, any other file gets a toast. */
'use strict';
(function () {
  const el = IL.el;
  const MAX_BYTES = 12 * 1024 * 1024;
  const TIMEOUT_MS = 60000;
  const UNFILL = 'A board with filled zones is much larger: in KiCad, Edit › Unfill All Zones, save, and upload that file (zones are then not drawn).';
  const GROUP_T = { 1: 'Blocks dispensing', 2: 'Registration', 3: 'Check in the preview', 4: 'Notes' };
  const RUNNING = 'Upload the board when the run has finished.';
  const IDENTITY = [1, 0, 0, 1, 0, 0];
  let M = null;   // the open dialog

  // ------------------------------------------------------------------------------------------ small helpers
  const mb = (n) => IL.fmt(n / 1048576, n < 1048576 ? 2 : 1) + ' MB';
  const isPcb = (name) => /\.kicad_pcb$/i.test(String(name || ''));
  const shortType = (name) => String(name || '').replace(/\s*\([^)]*\)\s*/g, ' ').replace(/\s+/g, ' ').trim();
  const STATUS_T = { 'not-started': 'not started', provisional: 'provisional', characterized: 'characterized', validated: 'validated' };
  const statusText = (st) => STATUS_T[st] || String(st || '').replace(/-/g, ' ');
  /** "Chip 0603": the pad type's family (without its note) and name, as Profile and the pad inspector name it. */
  function typeLabel(t) {
    const x = M && M.types && M.types.get(t.typeKey);
    if (x) return shortType((x.family || '') + ' ' + (x.name || ''));
    return shortType(t.name && /\s/.test(t.name) ? t.name : t.typeKey || t.name || '');
  }
  function loadTypes() {
    IL.api('GET', '/api/pad-types').then((r) => {
      if (!M) return;
      M.types = new Map();
      for (const f of (r && r.families) || []) for (const t of f.types || []) M.types.set(t.typeKey, { family: f.family || t.family, name: t.name });
      if (M.pv && (M.state === 'preview' || M.state === 'saving')) paintPackages(M.pv);
    }, () => { /* the type keys stand in for the names */ });
  }
  /**
   * The server's slug rule (§3.7): NFKD, accents dropped, ASCII letters and digits lower-cased, every other run → "-",
   * at most 40. Empty for a name without ASCII letters or digits (the server then makes "board-" and a digest).
   */
  function slugOf(name) {
    const s = String(name || '').normalize('NFKD').replace(/\p{Mn}/gu, '').toLowerCase()
      .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40).replace(/-+$/g, '');
    return s;
  }
  /** The server's name rule: 1 to 60 characters, at least one letter or digit of any script. */
  function nameProblem(name) {
    const n = String(name || '').trim();
    if (!n || Array.from(n).length > 60 || !/[\p{L}\p{N}]/u.test(n)) return 'Name: 1 to 60 characters, at least one letter or digit.';
    return null;
  }
  /** "H1–H4, J1": references grouped by prefix with consecutive numbers as ranges; at most max groups, then "…". */
  function refList(refs, max) {
    const list = (refs || []).map(String);
    const parsed = list.map((r) => { const m = /^(.*?)(\d+)$/.exec(r); return m ? { p: m[1], n: Number(m[2]), r } : { p: r, n: null, r }; });
    parsed.sort((a, b) => (a.p < b.p ? -1 : a.p > b.p ? 1 : (a.n === null ? 0 : a.n) - (b.n === null ? 0 : b.n)));
    const out = [];
    for (let i = 0; i < parsed.length;) {
      const a = parsed[i];
      let j = i;
      while (a.n !== null && j + 1 < parsed.length && parsed[j + 1].p === a.p && parsed[j + 1].n === parsed[j].n + 1) j++;
      out.push(j - i >= 2 ? a.r + '–' + parsed[j].r : j > i ? a.r + ', ' + parsed[j].r : a.r);
      i = j + 1;
    }
    const m = max || 4;
    return out.length > m ? out.slice(0, m).join(', ') + ' …' : out.join(', ');
  }
  /** POST the File itself (not through IL.api: JSON only, 10 s): parsed JSON, or IL.ApiError with the server's reason. */
  async function post(path, file, signal) {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
    const stop = () => ctl.abort();
    if (signal) signal.addEventListener('abort', stop);
    let r, text;
    try {
      r = await fetch(path, { method: 'POST', body: file, headers: { 'Content-Type': 'application/octet-stream' }, signal: ctl.signal, cache: 'no-store' });
      text = await r.text();
    } catch (e) {
      const why = signal && signal.aborted ? 'cancelled' : ctl.signal.aborted ? 'timeout' : 'network';
      throw new IL.ApiError(0, { error: why }, why === 'cancelled' ? 'Cancelled.' : why === 'timeout'
        ? 'No answer from OpenPnP within ' + TIMEOUT_MS / 1000 + ' s.' : 'OpenPnP did not answer (' + e.message + ').');
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', stop);
    }
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (!r.ok) {
      if (r.status === 404 && !(data && data.reason)) data = { error: 'not-found', reason: 'This OpenPnP cannot read KiCad boards yet (no import endpoint): update OpenPnP.' };
      throw new IL.ApiError(r.status, data, r.status + ' ' + r.statusText);
    }
    return data;
  }
  function query(o) {
    return Object.keys(o).filter((k) => o[k] !== undefined && o[k] !== null).map((k) => k + '=' + encodeURIComponent(o[k])).join('&');
  }
  /** The engine refuses an upload while a task runs or a pad calibration is between its engine steps. */
  const taskLock = () => (IL.taskRunning(IL.S.status) || (IL.padProfile && IL.padProfile.jobLock(IL.S.status)) ? RUNNING : null);

  // ------------------------------------------------------------------------------------------ the dialog
  function open(opts) {
    const o = opts || {};
    if (M && !M.h.closed) {
      if (o.onSaved) M.onSaved = o.onSaved;
      if (o.file) choose(o.file);
      return M;
    }
    M = { onSaved: o.onSaved || null, state: 'choose', file: null, pv: null, ctl: null, bv: null, replace: false, saved: null, types: null };
    build();
    loadTypes();
    if (o.file) choose(o.file);
    return M;
  }

  function build() {
    const n = M.n = {};
    // state 1: the drop zone
    n.input = el('input', { type: 'file', id: 'board-import-file', accept: '.kicad_pcb', class: 'sr', tabindex: '-1', 'aria-hidden': 'true' });
    n.input.addEventListener('change', () => { const f = n.input.files && n.input.files[0]; n.input.value = ''; if (f) choose(f); });
    n.choose = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-board-import-choose' }, 'Choose a file…');
    n.choose.addEventListener('click', () => n.input.click());
    n.drop = el('div', { class: 'bi-drop', id: 'board-import-drop' },
      fileIcon(),
      el('div', { class: 'bi-drop__t' }, 'Drop a KiCad board file (.kicad_pcb) here, or'),
      n.choose,
      el('div', { class: 'bi-drop__s', text: 'KiCad 6 to 10 (KiCad 5 on a best effort basis), up to 12 MB. You see the board and what gets paste before anything is saved. Nothing moves.' }),
      n.input);
    for (const ev of ['dragenter', 'dragover']) {
      n.drop.addEventListener(ev, (e) => { if (!hasFiles(e)) return; e.preventDefault(); e.stopPropagation(); n.drop.classList.add('bi-drop--over'); });
    }
    n.drop.addEventListener('dragleave', (e) => { if (!n.drop.contains(e.relatedTarget)) n.drop.classList.remove('bi-drop--over'); });
    n.drop.addEventListener('drop', (e) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      e.stopPropagation();
      n.drop.classList.remove('bi-drop--over');
      const f = e.dataTransfer.files && e.dataTransfer.files[0];
      if (f) choose(f);
    });
    // states 2 and errors
    n.status = el('div', { class: 'bi-status', id: 'board-import-status', role: 'status', hidden: true });
    // state 3: the preview
    n.preview = el('div', { class: 'bi-board', id: 'board-import-preview' });
    n.size = el('div', { class: 'bi-size', id: 'board-import-size' });
    n.key = el('div', { class: 'bi-key', 'aria-hidden': 'true' },
      el('span', { 'data-k': 'paste' }, el('i', { class: 'bi-sw' }), 'gets paste'),
      el('span', { 'data-k': 'wait' }, el('i', { class: 'bi-sw bi-sw--wait' }), 'pad type to characterize'),
      el('span', { 'data-k': 'block' }, el('i', { class: 'bi-sw bi-sw--block' }), IL.padProfile ? 'needs a profile' : 'no pad type yet'),   // H-P6
      el('span', { 'data-k': 'none' }, el('i', { class: 'bi-sw bi-sw--dim' }), 'not dispensed'));
    n.found = el('div', { class: 'bi-found', id: 'board-import-found' });
    n.attach = el('div', { class: 'notebox notebox--info bi-attach', id: 'board-import-attach', hidden: true });
    n.stale = el('div', { class: 'notebox notebox--need', id: 'board-import-stale', hidden: true },
      'OpenPnP\'s packages or parts changed: check the lists again, then save.');
    n.exists = el('div', { class: 'notebox notebox--need', id: 'board-import-exists', hidden: true });
    n.dnp = el('label', { class: 'check bi-dnp', hidden: true },
      el('input', { type: 'checkbox', id: 'board-import-paste-dnp' }), el('span', { text: 'Paste do-not-place parts too' }));
    n.dnp.firstChild.addEventListener('change', () => { if (M.file) read(M.file, { keepName: true }); });
    n.warn = el('div', { class: 'bi-warn', id: 'board-import-warn' });
    n.pkgs = el('table', { class: 'tbl bi-pkgs', id: 'board-import-packages' },
      el('thead', {}, el('tr', {}, el('th', { text: 'Package' }), el('th', { class: 'r', text: 'Pads' }), el('th', { class: 'r', text: 'Parts' }),
        el('th', { text: 'Pad type' }))), el('tbody'));
    n.pkgs.addEventListener('mouseleave', () => { if (M.bv) M.bv.select(null); });
    n.pkgs.addEventListener('focusout', (e) => { if (!n.pkgs.contains(e.relatedTarget) && M.bv) M.bv.select(null); });
    n.grid = el('div', { class: 'bi-grid', hidden: true },
      el('div', { class: 'bi-left' }, n.preview, n.key, n.size),
      el('div', { class: 'bi-right' }, n.found, n.stale, n.attach, n.exists, n.dnp, n.warn,
        IL.sectionK('Packages'), n.pkgs));
    // state 6: saved
    n.saved = el('div', { class: 'bi-saved', id: 'board-import-saved', hidden: true, tabindex: '-1' });
    // foot: name + save (preview), use + again (saved)
    n.name = el('input', { class: 'input', id: 'board-import-name', type: 'text', maxlength: '60', autocomplete: 'off', spellcheck: 'false' });
    n.name.addEventListener('input', () => { M.nameTouched = true; n.name.classList.remove('input--invalid'); IL.show(n.nameErr, false); paintName(); });
    n.nameErr = el('div', { class: 'field__err', id: 'board-import-name-err', hidden: true });
    n.slug = el('div', { class: 'field__h bi-slug', id: 'board-import-slug' });
    n.nameField = el('div', { class: 'field bi-name', hidden: true, 'data-enter-submits': 'btn-board-import-save' },
      el('label', { class: 'field__l', for: 'board-import-name' }, el('span', { text: 'Board name' })), n.name, n.nameErr, n.slug);
    n.cancel = el('button', { type: 'button', class: 'btn', id: 'btn-board-import-cancel' }, 'Cancel');
    n.cancel.addEventListener('click', () => {
      if (M.state === 'saving') return;
      if (M.state === 'reading') {
        // back to the preview it replaces (the DNP box as it was), else to the file choice
        cancelRead();
        clearInterval(M.tick);
        M.state = M.reread && M.pv ? 'preview' : 'choose';
        if (M.pv) M.n.dnp.firstChild.checked = !!M.pv.pasteDnp;
        paint();
        return;
      }
      M.h.close('cancel');
    });
    n.save = el('button', { type: 'button', class: 'btn btn--primary btn--lg', id: 'btn-board-import-save', hidden: true });
    IL.press2(n.save, () => (M && M.replace ? 'replace board' : null), () => save(), { refusalIn: null });
    n.again = el('button', { type: 'button', class: 'btn', id: 'btn-board-import-again', hidden: true }, 'Upload another');
    n.again.addEventListener('click', () => reset());
    n.use = el('button', { type: 'button', class: 'btn btn--primary btn--lg', id: 'btn-board-import-use', hidden: true }, 'Use this board');
    n.use.addEventListener('click', () => use());
    n.refusals = el('div', { class: 'bi-refusals', 'data-refusals': '' });
    n.why = el('div', { class: 'reason bi-why', hidden: true });
    const body = el('div', { class: 'bi-body' }, n.drop, n.status, n.grid, n.saved);
    M.h = IL.dialog.open({ id: 'dlg-board-import', size: 'wide', eyebrow: 'Board file', title: 'Upload a KiCad board', body,
      foot: [n.refusals, n.nameField, el('span', { class: 'spacer' }), n.why, n.again, n.cancel, n.save, n.use],
      initialFocus: n.choose,
      onClose: () => {
        cancelRead();
        if (M && M.bv) M.bv.destroy();
        if (M && M.off) M.off();
        if (M && M.escOff) M.escOff();
        if (M && M.tick) clearInterval(M.tick);
        M = null;
      } });
    M.h.foot.classList.add('bi-foot');
    // Esc while the save runs does nothing (registered after the dialog's own handler, so it is asked first)
    M.escOff = IL.onEsc(() => !!M && M.state === 'saving');
    M.off = IL.on('status', () => gateButtons());
    paint();
  }
  function fileIcon() {
    const s = IL.svgEl('svg', { class: 'ic bi-drop__ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' });
    s.append(IL.svgEl('path', { d: 'M7 3.5h7l4 4V20a.5.5 0 0 1-.5.5h-10.5a.5.5 0 0 1-.5-.5V4a.5.5 0 0 1 .5-.5z' }),
      IL.svgEl('path', { d: 'M14 3.5V8h4M12 17.5v-6M9.5 14l2.5-2.5 2.5 2.5' }));
    return s;
  }
  const hasFiles = (e) => !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));

  /** Shows the parts of the current state; every visible control says why it is greyed. */
  function paint() {
    const n = M.n, st = M.state;
    const pv = st === 'preview' || st === 'saving';
    const again = st === 'reading' && !!M.reread;   // a new preview of the same file (the DNP box): the old one stays, dimmed
    IL.show(n.drop, st === 'choose');   // after a refused file its box says why, with one way on: Choose another file (a drop still works)
    IL.show(n.status, st === 'reading' || st === 'error');
    IL.show(n.grid, pv || again);
    n.grid.classList.toggle('bi-grid--busy', again);
    IL.show(n.saved, st === 'saved');
    IL.show(n.nameField, pv || again);
    IL.show(n.save, pv || again);
    IL.show(n.cancel, st !== 'saved');
    IL.show(n.again, st === 'saved');
    IL.show(n.use, st === 'saved');
    IL.setText(n.cancel, st === 'reading' ? 'Cancel reading' : 'Cancel');
    M.h.el.classList.toggle('bi--preview', pv);
    M.h.el.dataset.state = st;
    gateButtons();
  }
  function gateButtons() {
    if (!M || !M.n) return;
    const n = M.n;
    const lock = taskLock();
    IL.gate(n.choose, lock);
    if (M.state === 'saving') {
      n.cancel.disabled = true;
      IL.setAttr(n.cancel, 'title', 'The board is being saved: wait for the answer.');
    } else {
      n.cancel.disabled = false;
      IL.setAttr(n.cancel, 'title', null);
    }
    if (M.state !== 'saving') IL.gate(n.save, lock || (M.state === 'reading' ? 'Wait for the new preview.' : null));
    if (n.attachBtn) IL.gate(n.attachBtn, M.state === 'saving' ? 'The board is being saved.' : lock);
    IL.setText(n.why, lock && (M.state === 'preview' || M.state === 'choose') ? lock : '');
    IL.show(n.why, !!lock && (M.state === 'preview' || M.state === 'choose'));
  }

  // ------------------------------------------------------------------------------------------ choose and read
  function choose(f) {
    if (!M || M.state === 'saving') { if (M) IL.toast('Wait for the save to finish.', 'need'); return; }
    const bad = !isPcb(f.name) ? f.name + ' is not a KiCad board file: choose a .kicad_pcb file (KiCad 5 to 10).'
      : !(f.size > 0) ? 'The file ' + f.name + ' is empty.'
        : f.size > MAX_BYTES ? 'The file is ' + mb(f.size) + ', larger than 12 MB. ' + UNFILL : null;
    if (bad) { showError(bad); return; }
    M.nameTouched = false;
    M.n.dnp.firstChild.checked = false;
    read(f, {});
  }
  function cancelRead() {
    if (!M || !M.ctl) return;
    M.ctl.abort();
    M.ctl = null;
  }
  async function read(f, opts) {
    const o = opts || {};
    cancelRead();
    const n = M.n;
    const keep = o.keepName && M.pv;
    M.file = f;
    M.reread = !!keep;
    M.state = 'reading';
    M.ctl = new AbortController();
    const ctl = M.ctl;
    const t0 = Date.now();
    const what = 'Reading ' + f.name + ' (' + mb(f.size) + ')…';
    const tick = () => IL.setText(n.status.firstChild, what + ' ' + Math.floor((Date.now() - t0) / 1000) + ' s');
    n.status.className = 'bi-status';
    n.status.replaceChildren(el('span', { class: 'bi-status__t' }), el('span', { class: 'bi-status__s', text: 'The board is drawn when OpenPnP has read it. Nothing moves.' }));
    tick();
    clearInterval(M.tick);
    M.tick = setInterval(tick, 500);
    paint();
    const dnp = n.dnp.firstChild.checked;
    try {
      const pv = await post('/api/boards/import?' + query({ file: f.name, pasteDnp: dnp ? 1 : undefined }), f, ctl.signal);
      if (!M || M.ctl !== ctl) return;
      M.ctl = null;
      clearInterval(M.tick);
      showPreview(pv, { keepName: keep });
    } catch (e) {
      if (!M || M.ctl !== ctl) return;   // cancelled, or a newer read took over
      M.ctl = null;
      clearInterval(M.tick);
      showError(errorLine(e));
    }
  }
  function errorLine(e) {
    const d = e && e.data;
    if (e && e.status === 0) return e.message + (d && d.error === 'cancelled' ? '' : ' Nothing was changed.');
    let t = (d && d.reason) || IL.errorText(e);
    if (d && (d.error === 'too-large' || d.error === 'too-complex') && t.indexOf('Unfill') < 0) t += ' ' + UNFILL;
    return t;
  }
  function showError(text) {
    const n = M.n;
    clearInterval(M.tick);
    M.state = 'error';
    const again = el('button', { type: 'button', class: 'btn', id: 'btn-board-import-other' }, 'Choose another file');
    again.addEventListener('click', () => n.input.click());
    n.status.className = 'bi-status bi-status--block';
    n.status.replaceChildren(el('div', { class: 'reason reason--block' }, IL.glyph('block', 'sm'), el('span', { text })), again);
    paint();
    again.focus({ preventScroll: true });
  }
  function reset() {
    if (M.bv) M.bv.select(null);
    M.state = 'choose';
    M.pv = null;
    M.saved = null;
    M.replace = false;
    M.attachReplace = false;
    M.existsAnswer = null;
    M.n.refusals.replaceChildren();
    paint();
    M.n.choose.focus({ preventScroll: true });
  }

  // ------------------------------------------------------------------------------------------ the preview
  function showPreview(pv, opts) {
    const o = opts || {};
    const n = M.n;
    M.pv = pv;
    M.state = 'preview';
    M.replace = false;
    n.refusals.replaceChildren();
    IL.show(n.stale, !!o.stale);
    paint();
    // the board: art only, in its own frame (identity seat), Full picture, origin marker; parts tinted by outcome
    if (!M.bv) {
      M.bv = IL.boardview.create(n.preview, { lite: true, aria: 'The board as KiCad draws it, with the paste openings',
        onPart: (ref) => flashRow(ref), onPad: () => {} });
      M.bv.picture('full');
    }
    M.bv.setBoard({ art: pv.art || null, toMachine: IDENTITY });
    M.out = outcomes(pv);
    tint(pv);
    // size, KiCad version, origin
    const b = pv.board || {};
    const k = pv.kicad || {};
    const origin = b.origin === 'estimated' ? 'origin estimated: no board outline'
      : b.origin === 'edges' ? 'origin at the bottom-left corner of the board edges (the outline is not closed)'
        : 'origin at the bottom-left corner of the board outline';
    IL.setText(n.size, IL.fmt(b.widthMm, 1) + '\u00a0×\u00a0' + IL.fmt(b.heightMm, 1) + '\u00a0mm' + ' · KiCad ' + (k.major || '?') + ' · ' + origin);
    n.size.classList.toggle('bi-size--need', b.origin === 'estimated');
    n.found.replaceChildren(...foundLines(pv));
    paintAttach(pv);
    // do-not-place parts: one checkbox makes them pasted, disabled placements (§13 item 1)
    const dnpRefs = (pv.skipped && pv.skipped.dnp) || [];
    const dnpOn = !!pv.pasteDnp || n.dnp.firstChild.checked;
    IL.show(n.dnp, dnpRefs.length > 0 || dnpOn);
    n.dnp.firstChild.checked = dnpOn;
    IL.setAttr(n.dnp, 'title', 'Do-not-place parts are not placed, so by default they get no paste. Ticked, they are saved as disabled parts, which the dispenser pastes.');
    paintWarnings(pv);
    paintPackages(pv);
    // the name (the file name without .kicad_pcb), its folder, and a name that is taken
    if (!o.keepName || !M.nameTouched) n.name.value = pv.suggestedName || String(pv.file && pv.file.name || '').replace(/\.kicad_pcb$/i, '');
    paintName();
    if (!o.keepName) M.h.body.scrollTop = 0;
  }
  /**
   * What each placement gets, from its package's pad types: 'paste', 'wait' (its pad type is not characterized yet:
   * no paste until it is), 'block' (no pad type) or 'none' (not dispensed: fiducials). The preview's outcome says the
   * same where it can (no-pad-type, not-dispensed); "characterized" is only in the pad types.
   */
  function outcomes(pv) {
    const pk = new Map((pv.packages || []).map((p) => [p.id, p]));
    const out = new Map();
    for (const p of pv.placements || []) {
      const k = pk.get(p.packageId);
      let o = p.type === 'Fiducial' || p.outcome === 'not-dispensed' ? 'none' : p.outcome === 'no-pad-type' || p.outcome === 'needs-profile' ? 'block' : 'paste';
      if (o === 'paste' && k && !k.fiducial) o = blocked(k) ? 'block' : waiting(k) ? 'wait' : 'paste';
      out.set(p.id, o);
    }
    return out;
  }
  /** Part hit rects tinted by outcome: no pad type (red hatch), pad type to characterize (amber hatch), not dispensed (dimmed). */
  function tint(pv) {
    const bv = M.bv;
    const out = M.out;
    const svg = bv.svg;
    let defs = svg.querySelector('defs.bi-defs');
    if (!defs) {
      defs = IL.svgEl('defs', { class: 'bi-defs' });
      svg.insertBefore(defs, svg.firstChild);
    }
    // the hatch in board mm, about 6 px apart whatever the board's size
    const size = (pv.art && pv.art.size) || [50, 50];
    const s = Math.max(0.25, Math.max(Number(size[0]) || 50, Number(size[1]) || 50) / 70);
    defs.replaceChildren();
    for (const k of ['block', 'wait']) {
      const pat = IL.svgEl('pattern', { id: 'bi-hatch-' + k, patternUnits: 'userSpaceOnUse', width: String(s), height: String(s), patternTransform: 'rotate(45)' });
      pat.append(IL.svgEl('rect', { width: String(s), height: String(s), class: 'bi-hatch__bg' }),
        IL.svgEl('line', { x1: '0', y1: '0', x2: '0', y2: String(s), class: 'bi-hatch__line', 'stroke-width': String(s * 0.32) }));
      pat.classList.add('bi-hatch--' + k);
      defs.appendChild(pat);
    }
    for (const r of svg.querySelectorAll('rect.pv-part')) {
      const o = out.get(r.dataset.ref) || 'none';   // drawn-only and bottom-side parts are not placements
      r.classList.remove('bi-part--block', 'bi-part--wait', 'bi-part--dim');
      if (o !== 'paste') r.classList.add(o === 'none' ? 'bi-part--dim' : 'bi-part--' + o);
    }
    // the key names only what this board has
    const has = new Set(out.values());
    for (const sp of M.n.key.children) IL.show(sp, sp.dataset.k === 'paste' || has.has(sp.dataset.k) || (sp.dataset.k === 'none' && svg.querySelector('rect.pv-part.bi-part--dim')));
  }
  /** Two lines: what gets paste (and what does not yet), then fiducials and what is skipped. */
  function foundLines(pv) {
    const pls = (pv.placements || []).filter((p) => p.type !== 'Fiducial');
    const by = (k) => pls.filter((p) => M.out.get(p.id) === k);
    const sum = (l) => l.reduce((a, p) => a + (Number(p.pads) || 0), 0);
    const paste = by('paste'), wait = by('wait'), block = by('block');
    const sk = pv.skipped || {};
    const fids = (pv.fiducials || []).length || (pv.counts && pv.counts.fiducials) || 0;
    const art = (sk.artOnly || []).map((x) => (typeof x === 'string' ? x : x.ref));
    const join = (items) => items.flatMap((x, i) => (i ? [el('span', { class: 'bi-dot', text: ' · ', 'aria-hidden': 'true' }), x] : [x]));
    const first = [];
    first.push(el('b', { class: paste.length ? null : 'bi-found--none', text: paste.length || !pls.length
      ? IL.plural(paste.length, 'part gets', 'parts get') + ' paste (' + IL.plural(sum(paste), 'pad') + ')' : 'No part gets paste yet' }));
    if (wait.length) first.push(el('b', { class: 'bi-found--need', text: IL.plural(wait.length, 'part waits', 'parts wait') + ' for a characterized pad type (' + IL.plural(sum(wait), 'pad') + ')' }));
    // with the profile check (H-P6) these parts need a profile: no pad type, one not calibrated yet, as the key and the
    // NEEDS_PROFILE warning say
    if (block.length) first.push(el('b', { class: 'bi-found--block', text: IL.plural(block.length, 'part needs', 'parts need') + (IL.padProfile ? ' a profile (' : ' a pad type (')
      + IL.plural(sum(block), 'pad') + ')' }));
    const second = [el('span', { text: IL.plural(fids, 'fiducial') })];
    const skip = (list, one, many, tail) => {
      if (!list || !list.length) return;
      second.push(el('span', { title: list.join(', ') }, IL.plural(list.length, one, many) + tail + ' (' + refList(list, 3) + ')'));
    };
    skip(sk.dnp, 'part', 'parts', ' not placed (DNP)' + (pv.pasteDnp ? ': pasted' : ': no paste'));
    skip(sk.bottom, 'bottom-side part', 'bottom-side parts', ' skipped');
    skip(art, 'part', 'parts', ' drawn only');
    return [el('div', { class: 'bi-found__a' }, join(first)), el('div', { class: 'bi-found__b' }, join(second))];
  }
  function paintAttach(pv) {
    const n = M.n;
    const c = (pv.attach || [])[0] || null;
    IL.show(n.attach, !!c);
    n.attachBtn = null;
    // the board file it matches is what the operator plans every day: giving it the drawing is the main action then,
    // a board of its own (new packages, no registrations yet) the other one
    n.save.classList.toggle('btn--primary', !c);
    if (!c) { n.attach.replaceChildren(); return; }
    const base = String(c.file || '').split('/').pop();
    const count = ((pv.counts && pv.counts.placements) || 0) + ((pv.counts && pv.counts.fiducials) || 0);
    const offs = (c.offsets || []).filter((x) => Number(x.mm) > 0.05);
    const away = offs.length ? ' (' + offs.slice(0, 3).map((x, i) => x.id + (i ? ' ' : ' is ') + IL.fmt(x.mm, 2) + ' mm').join(', ')
      + (offs.length > 3 ? ' and ' + (offs.length - 3) + ' more' : '') + ' away from the board file\'s position' + (offs.length > 1 ? 's' : '')
      + '; the drawing shows KiCad\'s)' : '';
    // a board file that has a KiCad drawing already: this one replaces it (two presses), as the note says
    const files = (IL.run && IL.run.boards && IL.run.boards.files) || [];
    const cur = files.find((x) => x.file === c.file);
    const had = cur && cur.imported && cur.imported.how === 'attach' ? cur.imported : null;
    if (had) M.attachReplace = true;
    const btn = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-board-import-attach', 'data-board': c.file },
      (had || M.attachReplace ? 'Replace the drawing of ' : 'Use the drawing for ') + base);
    IL.press2(btn, () => (M && M.attachReplace ? 'replace its drawing' : null), () => attach(c), { refusalIn: n.refusals });
    n.attachBtn = btn;
    n.attach.replaceChildren(
      el('div', {}, el('b', { text: 'This is the board in ' + base + ': ' }), 'all ' + count + ' parts match' + away + '.'),
      el('div', { class: 'bi-attach__s', text: had
        ? base + ' has a KiCad drawing already (added ' + IL.fmtTime(IL.toClient(had.atMs)) + (had.sourceName ? ' from ' + had.sourceName : '')
          + '): this file\'s drawing replaces it. Nothing else changes.'
        : 'Use this drawing for ' + base + ': nothing else changes (no new packages or parts, its plans stay valid).' }),
      el('div', { class: 'btn-row' }, btn));
    gateButtons();
  }
  function paintWarnings(pv) {
    const n = M.n;
    const groups = { 1: [], 2: [], 3: [], 4: [] };
    for (const w of pv.warnings || []) (groups[w.group] || groups[3]).push(w);
    const li = (w) => el('li', { 'data-code': w.code, 'data-group': String(w.group), class: 'bi-w bi-w--' + (w.level === 'info' ? 'info' : 'warn') }, w.text);
    const out = [];
    for (const g of [1, 2, 3]) {
      const l = groups[g];
      if (!l.length) continue;
      // a long group (mobo: 12 packages without a pad type) shows its first three; the rest stay one press away
      const head = l.length > 4 ? l.slice(0, 3) : l, rest = l.length > 4 ? l.slice(3) : [];
      out.push(el('section', { class: 'bi-wg bi-wg--' + g, 'data-group': String(g) },
        el('h3', { class: 'bi-wg__t' }, IL.glyph(g === 1 ? 'block' : 'need', 'sm'), GROUP_T[g], el('span', { class: 'count', text: String(l.length) })),
        el('ul', {}, head.map(li)),
        rest.length ? el('details', { class: 'disclose bi-wg__more' }, el('summary', { text: 'Show ' + rest.length + ' more' }), el('ul', {}, rest.map(li))) : null));
    }
    if (groups[4].length) {
      out.push(el('details', { class: 'disclose bi-wg bi-wg--4', 'data-group': '4' },
        el('summary', { text: GROUP_T[4] + ' (' + groups[4].length + ')' }), el('ul', {}, groups[4].map(li))));
    }
    if (!out.length) out.push(el('div', { class: 'bi-wg bi-wg--none' }, IL.glyph('ok', 'sm'), el('span', { text: 'No warnings: every part on the top side has a pad type.' })));
    n.warn.replaceChildren(...out);
  }
  /** One row per package: as saved (a ~2 copy explained), pads, parts, the pad type and why; rows highlight their parts. */
  function paintPackages(pv) {
    const n = M.n;
    const rank = (p) => (p.fiducial ? 3 : blocked(p) ? 0 : waiting(p) ? 1 : 2);
    const list = (pv.packages || []).slice().sort((a, b) => rank(a) - rank(b));
    const parts = new Map();
    for (const p of pv.placements || []) parts.set(p.packageId, (parts.get(p.packageId) || 0) + 1);
    const body = n.pkgs.tBodies[0];
    body.replaceChildren(...list.map((p) => {
      const refs = p.refs || [];
      const sub = [];
      if (p.state === 'suffixed') sub.push(el('div', { class: 'bi-pkg__s', text: (p.why || p.baseId + ' exists in OpenPnP with other pads') + ': saved as a copy.' }));
      if (p.state === 'reuse') sub.push(el('div', { class: 'bi-pkg__s', text: 'Already in OpenPnP with the same pads: used as it is.' }));
      if (p.usesPadTypesOf) sub.push(el('div', { class: 'bi-pkg__s', text: 'Uses the pad types of ' + p.usesPadTypesOf + '.' }));
      const tr = el('tr', { 'data-package': p.id, 'data-state': p.state || 'new', tabindex: '0', class: blocked(p) ? 'bi-pkg--block' : waiting(p) ? 'bi-pkg--wait' : null },
        el('td', { class: 'bi-pkg' }, el('div', { class: 'bi-pkg__id' }, breakable(p.id)), el('div', { class: 'bi-pkg__refs', text: refList(refs, 3), title: refs.join(', ') }), sub),
        el('td', { class: 'r', text: String((p.pads || []).length) }),
        el('td', { class: 'r', text: String(parts.get(p.id) || refs.length) }),
        el('td', { class: 'bi-pt' }, padTypeCell(p)));
      const hi = () => { if (M.bv) M.bv.select({ refs }); };
      tr.addEventListener('mouseenter', hi);
      tr.addEventListener('focus', hi);
      return tr;
    }));
    if (IL.padProfile) IL.padProfile.importRows(n.pkgs, pv);   // the profile column (PROFILE_CALIBRATION_DESIGN.md §13.7, H-P6)
  }
  /** No pad type for some of the package's pads; its pad types not all characterized yet. */
  const blocked = (p) => !p.fiducial && (!(p.padTypes || []).length || (p.padTypes || []).some((t) => !t.typeKey));
  const waiting = (p) => !p.fiducial && !blocked(p) && (p.padTypes || []).some((t) => t.dispensable === false);
  /** A long KiCad name with line-break chances after "_" and "-" (never inside a word or a number). */
  function breakable(id) {
    const out = [];
    String(id).split(/(?<=[_-])/).forEach((x, i) => { if (i) out.push(el('wbr')); out.push(x); });
    return out;
  }
  function padTypeCell(p) {
    if (p.fiducial) return el('span', { class: 'muted', text: 'Fiducial: no paste' });
    const types = p.padTypes || [];
    if (!types.length || types.every((t) => !t.typeKey)) {
      return [el('div', { class: 'bi-pt__none' }, IL.glyph('block', 'xs'), 'No pad type'), el('div', { class: 'bi-pt__s', text: 'Add one after saving: no paste until then.' })];
    }
    return types.map((t) => {
      const many = types.length > 1;
      if (!t.typeKey) return el('div', { class: 'bi-pt__none' }, IL.glyph('block', 'xs'), 'No pad type' + (many ? ' (' + IL.plural(t.pads, 'pad') + ')' : ''));
      const status = t.status ? ' · ' + statusText(t.status) : '';
      return el('div', { class: 'bi-pt__t' + (t.dispensable === false ? ' bi-pt__t--need' : '') },
        el('div', {}, el('b', { text: typeLabel(t) }), many ? ' (' + IL.plural(t.pads, 'pad') + ')' : '', el('span', { class: 'muted', text: status })),
        t.sentence ? el('div', { class: 'bi-pt__s', text: t.sentence }) : null,
        t.dispensable === false ? el('div', { class: 'bi-pt__s bi-pt__s--need', text: 'Not characterized yet: dispensing needs a characterized pad type.' }) : null);
    });
  }
  function flashRow(ref) {
    const tr = Array.from(M.n.pkgs.tBodies[0].rows).find((r) => r.querySelector('.bi-pkg__refs') && (M.pv.packages || []).some((p) => p.id === r.dataset.package && (p.refs || []).includes(ref)));
    if (tr) { tr.focus({ preventScroll: false }); IL.flash(tr); }
  }
  function paintName() {
    const n = M.n;
    const pv = M.pv;
    const name = n.name.value.trim();
    const slug = slugOf(name);
    IL.setText(n.slug, slug ? 'Saved as instancelabs/boards/' + slug + '/' + slug + '.board.xml, with its packages and parts in OpenPnP.'
      : nameProblem(name) ? 'The name needs a letter or a digit.' : 'Saved in a folder of its own under instancelabs/boards/, with its packages and parts in OpenPnP.');
    // a taken name: the preview's answer for its suggested name, else the board list (imported boards carry their slug)
    let ex = pv && pv.exists && pv.exists.slug === slug ? pv.exists : null;
    if (!ex && slug) {
      const files = (IL.run && IL.run.boards && IL.run.boards.files) || [];
      const b = files.find((x) => x.imported && x.imported.slug === slug);
      if (b) ex = { slug, file: b.file, name: b.name, importedAtMs: b.imported.atMs, how: b.imported.how };
    }
    if (M.existsAnswer && (M.existsAnswer.slug === slug || M.existsAnswer.forName === name)) ex = M.existsAnswer;
    M.replace = !!ex;
    IL.show(n.exists, !!ex);
    if (ex) {
      const at = ex.importedAtMs ? IL.fmtTime(IL.toClient(ex.importedAtMs)) : null;
      // the name of a drawing given to a board file (attach) is taken too, but it is not a board of its own
      IL.setText(n.exists, ex.how === 'attach'
        ? 'The name "' + slug + '" holds the KiCad drawing of ' + String(ex.file || '').split('/').pop() + (at ? ' (added ' + at + ')' : '')
          + '. Saving under it replaces that drawing: choose another name to keep it.'
        : 'A board named "' + (ex.name || slug) + '" exists' + (at ? ' (imported ' + at + ')' : '') + '. Saving replaces it: plans made with it must be planned again.');
    }
    if (!IL.isArmed(n.save)) IL.setText(n.save, ex ? 'Replace board' : 'Save board');
  }

  // ------------------------------------------------------------------------------------------ save, attach, use
  async function save() {
    const n = M.n;
    const pv = M.pv;
    const name = n.name.value.trim();
    const bad = nameProblem(name);
    if (bad) {
      n.name.classList.add('input--invalid');
      IL.setText(n.nameErr, bad);
      IL.show(n.nameErr, true);
      n.name.focus();
      return;
    }
    n.refusals.replaceChildren();
    M.state = 'saving';
    paint();
    n.save.disabled = true;
    n.save.setAttribute('aria-busy', 'true');
    const q = query({ file: M.file.name, name, replace: M.replace ? 1 : 0, sha256: pv.file && pv.file.sha256, decisions: pv.decisions,
      pasteDnp: n.dnp.firstChild.checked ? 1 : undefined });
    try {
      const r = await post('/api/boards/import/save?' + q, M.file, null);
      if (!M) { IL.toast('Saved ' + name + '.', 'ok'); return; }
      saved(r, 'import', name);
    } catch (e) {
      if (!M) { IL.refuse(e, { what: 'save board' }); return; }
      M.state = 'preview';
      const d = e.data || {};
      if (e.status === 409 && d.error === 'exists') {
        // kept for this name too: the server's slug of a name in another script is not the one slugOf can make
        M.existsAnswer = Object.assign({ slug: slugOf(name) }, d.exists || {}, { forName: name });
        paint();
        paintName();
        IL.toast(d.reason || 'A board with this name exists: press again to replace it.', 'need');
      } else if (e.status === 409 && d.error === 'preview-stale' && d.preview) {
        IL.toast('OpenPnP\'s packages or parts changed since the preview: check the new lists, then save.', 'need');
        showPreview(d.preview, { keepName: true, stale: true });
      } else if (e.status === 400 && /^Name/.test(d.reason || '')) {
        paint();
        n.name.classList.add('input--invalid');
        IL.setText(n.nameErr, d.reason);
        IL.show(n.nameErr, true);
      } else {
        paint();
        IL.refuse(e, { host: n.refusals, what: 'save board' });
      }
    } finally {
      if (n.save) { n.save.removeAttribute('aria-busy'); }
      gateButtons();
      IL.pollNow();
    }
  }
  /**
   * The name the drawing of board file c is kept under (instancelabs/boards/<slug>/): its earlier drawing's, else the
   * board's OpenPnP name (else its file name). A name another board holds (an import, the drawing of another board file;
   * taken: slugs the server answered "exists" for) is never replaced: the next free one is used ("ftp-board-xml-2").
   */
  function attachName(c, base, taken) {
    const files = (IL.run && IL.run.boards && IL.run.boards.files) || [];
    const own = files.find((x) => x.file === c.file && x.imported && x.imported.how === 'attach' && x.imported.slug);
    if (own && !taken.includes(own.imported.slug)) return own.imported.slug;
    const want = !nameProblem(c.name) ? String(c.name).trim() : base.replace(/\.board\.xml$/i, '');
    const held = new Set(files.filter((x) => x.file !== c.file && x.imported && x.imported.slug).map((x) => x.imported.slug).concat(taken));
    if (!held.has(slugOf(want))) return want;
    const stem = (slugOf(want) || 'board').slice(0, 37).replace(/-+$/, '');
    for (let k = 2; k < 100; k++) if (!held.has(stem + '-' + k)) return stem + '-' + k;
    return want;
  }
  async function attach(c) {
    const n = M.n;
    const pv = M.pv;
    const base = String(c.file || '').split('/').pop();
    n.refusals.replaceChildren();
    M.state = 'saving';
    paint();
    const taken = [];
    try {
      let r = null;
      while (!r) {
        const name = attachName(c, base, taken);
        const q = query({ file: M.file.name, board: c.file, name, replace: M.attachReplace ? 1 : 0, sha256: pv.file && pv.file.sha256 });
        try {
          r = await post('/api/boards/import/attach?' + q, M.file, null);
        } catch (e) {
          // the name belongs to another board (not in the board list: its folder only): the next free name, at most 3 times
          const d = e.data || {};
          if (!(e.status === 409 && d.error === 'exists' && d.exists && d.exists.file && d.exists.file !== c.file && taken.length < 3)) throw e;
          taken.push(slugOf(name));
        }
      }
      if (!M) { IL.toast('The KiCad drawing is now used for ' + base + '.', 'ok'); return; }
      saved(Object.assign({ file: c.file, name: c.name }, r), 'attach', c.name || base);
    } catch (e) {
      if (!M) { IL.refuse(e, { what: 'use the drawing' }); return; }
      M.state = 'preview';
      paint();
      const d = e.data || {};
      if (e.status === 409 && d.error === 'exists' && !(d.exists && d.exists.file && d.exists.file !== c.file)) {
        // this board file's own earlier drawing under that name: replacing it is the operator's second press
        M.attachReplace = true;
        IL.toast((d.reason || 'A drawing with this name exists') + ' Press again to replace it.', 'need');
        paintAttach(pv);
      } else IL.refuse(e, { host: n.refusals, what: 'use the drawing' });
    } finally {
      IL.pollNow();
    }
  }
  /** State 6: what was saved, the pad types still to add (actions now), Use this board. */
  function saved(r, how, name) {
    const n = M.n;
    const pv = M.pv;
    M.state = 'saved';
    const fid = (pv.fiducials || []).map((f) => String(f.id)).sort(new Intl.Collator('en', { numeric: true }).compare)[0] || null;
    M.saved = { file: r.board || r.file, name: r.name || name, slug: r.slug, how, fiducial: fid };
    const parts = (pv.counts && pv.counts.placements) || 0, fids = (pv.counts && pv.counts.fiducials) || 0;
    const newPk = (r.packages || []).filter((x) => x.state === 'new' || x.state === 'suffixed').map((x) => x.id);
    const base = String(M.saved.file || '').split('/').pop();
    const head = how === 'attach'
      ? ['The KiCad drawing is now used for ', el('b', { text: base }), '. Nothing else changed: no new packages or parts, and its plans stay valid.']
      : ['Saved ', el('b', { text: M.saved.name }), ': ' + IL.plural(parts, 'part') + ', ' + IL.plural(fids, 'fiducial') + '.'
        + (newPk.length ? ' ' + IL.plural(newPk.length, 'new package') + ': ' + newPk.slice(0, 3).join(', ') + (newPk.length > 3 ? ' and ' + (newPk.length - 3) + ' more' : '') + '.' : '')
        + ' OpenPnP\'s packages, parts and board list were saved too.'];
    const todo = how === 'attach' ? [] : (pv.packages || []).filter(blocked);
    const rows = todo.map((p) => {
      const b = el('button', { type: 'button', class: 'btn btn--sm board-import-newtype', 'data-package': p.id }, IL.icon('i-plus'), 'New pad type');
      b.addEventListener('click', () => leaveTo('#profile/new-pad-type/' + encodeURIComponent(p.id)));
      return el('li', {}, el('div', { class: 'bi-todo__t' }, el('b', {}, breakable(p.id)),
        el('span', { class: 'muted', text: ' · ' + refList(p.refs, 3) + ' · ' + IL.plural((p.pads || []).length, 'pad') })), b);
    });
    // pad types that exist but are not characterized yet: their parts get paste once they are
    const types = new Map();
    for (const p of how === 'attach' ? [] : (pv.packages || []).filter(waiting)) {
      for (const t of p.padTypes || []) {
        if (!t.typeKey || t.dispensable !== false) continue;
        if (!types.has(t.typeKey)) types.set(t.typeKey, { t, refs: [] });
        types.get(t.typeKey).refs.push(...(p.refs || []));
      }
    }
    const crows = Array.from(types.values()).map(({ t, refs }) => {
      const b = el('button', { type: 'button', class: 'btn btn--sm board-import-padtype', 'data-type': t.typeKey }, 'Open pad type');
      b.addEventListener('click', () => leaveTo('#profile/pad-types/' + encodeURIComponent(t.typeKey)));
      return el('li', {}, el('div', { class: 'bi-todo__t' }, el('b', { text: typeLabel(t) }),
        el('span', { class: 'muted', text: ' · ' + (t.status ? statusText(t.status) : 'not characterized') + ' · ' + refList(refs, 3) })), b);
    });
    // with the profile check: the packages that need a profile and their actions instead of "New pad type" (H-P6)
    const pp = how !== 'attach' && IL.padProfile ? IL.padProfile.importSaved(pv, { file: M.saved.file, name: M.saved.name, leaveTo }) : null;
    n.saved.replaceChildren(...[
      el('div', { class: 'bi-saved__head' }, IL.glyph('ok'), el('div', {}, head)),
      pp || null,
      !pp && todo.length ? el('div', { class: 'bi-todo' },
        el('h3', { text: IL.plural(todo.length, 'package needs', 'packages need') + ' a pad type before ' + (todo.length === 1 ? 'its' : 'their') + ' parts get paste' }),
        el('ul', {}, rows)) : null,
      !pp && crows.length ? el('div', { class: 'bi-todo' },
        el('h3', { text: crows.length === 1 ? 'Characterize this pad type before its parts get paste' : 'Characterize these pad types before their parts get paste' }),
        el('ul', {}, crows)) : null,
      el('p', { class: 'bi-saved__next', text: how === 'attach'
        ? 'Use this board, then plan it: the drawing shows under its dots.'
        : 'Use this board: put the top camera on ' + (M.saved.fiducial || 'the first fiducial') + ' and press "Camera is on ' + (M.saved.fiducial || 'FID1')
          + '" (or type the origin), then Plan. Register board then finds the fiducials.' })].filter(Boolean));
    paint();
    IL.toast(how === 'attach' ? 'The KiCad drawing is now used for ' + base + '.' : 'Saved ' + M.saved.name + ': ' + IL.plural(parts, 'part') + ', ' + IL.plural(fids, 'fiducial') + '.', 'ok');
    n.use.focus({ preventScroll: true });
  }
  function use() {
    const s = M && M.saved;
    if (!s) return;
    const cb = M.onSaved;
    M.h.close('use');
    if (cb) IL.safe(() => cb(s), 'board import');
  }
  /** A pad-type action after the save: the board is chosen in the Board step first (as Use this board), then hash opens. */
  async function leaveTo(hash) {
    const s = M && M.saved;
    const cb = M && M.onSaved;
    if (M) M.h.close('leave');
    if (s && cb) { try { await cb(s); } catch (e) { /* the board stays in the list: choose it later */ } }
    IL.go(hash);
  }

  // ------------------------------------------------------------------------------------------ page-wide drop guard
  // No other part of the page takes files: without this, a file dropped anywhere makes the browser open it in the tab,
  // replacing the app and its STOP button.
  // (a drag only names its types, not the file: every file drag may drop, and the drop decides)
  document.addEventListener('dragover', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
  });
  document.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    if (e.target && e.target.closest && e.target.closest('#board-import-drop')) return;
    const f = e.dataTransfer.files && e.dataTransfer.files[0];
    if (!f) return;
    if (isPcb(f.name)) {
      if (taskLock()) { IL.toast(RUNNING, 'need'); return; }
      open({ file: f, onSaved: IL.boardFlow ? IL.boardFlow.onImported : null });
    } else IL.toast('Drop a .kicad_pcb file on the upload box (Run › Board › Upload KiCad board).', 'need');
  });

  IL.boardImport = { open, isOpen: () => !!M, slugOf, refList };
})();
