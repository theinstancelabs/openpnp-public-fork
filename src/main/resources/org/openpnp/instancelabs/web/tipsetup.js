/* tipsetup.js: tip setups in the web UI (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §22.7; API §22.6). Loaded after
   padprofile.js and before the workspace modules (index.html).

   IL.tips: the strip's tip cell (cellView, rendered by core.js), the Setup card "Dispenser & tip" (buildCard /
   renderCard on the shell setup.js makes, #card-tip), the Change tip panel view (`tip-change`: the swap, which tip is
   on, what a mount does, Mount), the Add a tip / Edit dialog (#dlg-tip-add), the picture and upload actions, the undo
   band after a mount (#btn-tip-undo), the needle token every needle value carries (token()).
   IL.photos: normalize(file) (the browser draws the picture: 2048 px at most, JPEG 0.85, the phone's orientation, no
   metadata), upload(blob, query), send(file, query), chooser(…) (a gated file button: an open OS chooser blocks STOP),
   dropZone(…) (no chooser: stays open during a run), strip(photos, …) (thumbnail buttons), view(photo, …) (#photo-view).

   Fix-its: tip-change, tip-edit, tip-add, landing-check, landing-keep (two-press), measure-tip.
   Nothing here moves the machine but "Take a picture from below" (two presses: N2 to the bottom camera at travel height).
   Mount (two presses) records the needle change and switches the setup; nothing moves. Styles: tipsetup.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const { isNum } = IL;
  const MINUS = '−';
  const DROP_HINT = 'A phone picture: copy it to this computer first (cable, cloud or mail).';
  const HEIC = 'This picture cannot be read here (iPhone HEIC?): share it as JPEG ("Most Compatible") or send a screenshot.';
  const MOUNT_KEY = 'il.tips.mount';
  const TOWARD = { '+x': '+X', '-x': MINUS + 'X', '+y': '+Y', '-y': MINUS + 'Y' };
  const TAPER_WORD = { 'tapered-plastic': 'tapered plastic', 'straight-steel': 'straight steel', unknown: 'taper not known' };
  const TURN_WORD = { 400: 'quarter turn', 800: 'half turn', 1600: 'one turn' };
  const TURN_SHORT = { 400: 'quarter', 800: 'half', 1600: 'one turn' };
  const T = { data: null, loading: null, error: null, sig: null, sel: null, again: false, previews: {}, card: null, dlg: null, undoSig: null };

  // ================================================================================== words and small helpers
  const disp = (s) => (s && s.dispense) || {};
  const tipOf = (s) => disp(s).tip || {};
  const needleOf = (s) => disp(s).needle || {};
  /** The needle token of status (dispense.needle.changedAtMs): what every needle value is taken under; null: none recorded. */
  const token = (s) => { const n = needleOf(s || S.status); return isNum(n.changedAtMs) ? Number(n.changedAtMs) : null; };
  const nameOf = (s) => tipOf(s || S.status).name || 'tip';
  const clock = (ms) => (ms ? IL.fmtTime(IL.toClient(ms)) : '—');
  const when = (ms) => (ms ? IL.fmtDateTime(IL.toClient(ms)) : '—');
  const mm2 = (v) => (isNum(v) ? IL.fmt(v, 2) + ' mm' : '—');
  const signed2 = (v) => (Number(v) < -0.005 ? MINUS : '+') + IL.fmt(Math.abs(Number(v)), 2);
  const towardWord = (t) => TOWARD[t] || null;
  /** "ADL AD22TTA-B · 22G · bent 30° · tapered plastic · 12.7 mm long" (the bore is its own row). */
  function tipWords(x) {
    const l = [];
    if (x.tipModel) l.push(x.tipModel);
    if (isNum(x.gauge)) l.push(x.gauge + 'G');
    l.push(x.form === 'bent' ? 'bent' + (isNum(x.bendDeg) ? ' ' + IL.fmt(x.bendDeg, 0) + '°' : '') : x.form === 'straight' ? 'straight' : 'form not known');
    l.push(TAPER_WORD[x.taper] || 'taper not known');
    if (isNum(x.lengthMm)) l.push(IL.fmt(x.lengthMm, 1) + ' mm long');
    return l.join(' · ');
  }
  /** "ID 0.41 mm · OD 0.72 mm (from the chart)". */
  function boreWords(x) {
    const id = isNum(x.idMm) ? 'ID ' + mm2(x.idMm) : 'ID not known';
    const od = isNum(x.odMm) ? 'OD ' + mm2(x.odMm) + (x.odSource === 'measured' ? ' (measured)' : ' (from the chart)') : 'OD not known';
    return id + ' · ' + od;
  }
  /** "Chip 0603, Chip 0805, SOT-23 · +3 more" or "no pad types calibrated yet". */
  function typesWords(x) {
    const names = (x.padTypes && x.padTypes.names) || [];
    if (!names.length) return 'no pad types calibrated yet';
    return names.slice(0, 3).join(', ') + (names.length > 3 ? ' · +' + (names.length - 3) + ' more' : '');
  }
  /** "9 pad types calibrated, 1 validated on a board" / "none calibrated yet". */
  function calibratedWords(x) {
    const p = x.padTypes || {};
    const n = (p.characterized || 0) + (p.validated || 0);
    if (!n) return 'none calibrated yet';
    return IL.plural(n, 'pad type') + ' calibrated' + (p.validated ? ', ' + p.validated + ' validated on a board' : '');
  }
  /** Dots: "from the bench tests (2026-10-27/28)" · "measured 10-29: quarter 0.71, half 0.93, one turn 1.12 mm; 1.57 mg per turn". */
  function dotsWords(x) {
    const d = x.dots || {};
    if (d.source === 'bench') return 'from the bench tests (2026-10-27/28)';
    if (d.source === 'measured' || (d.doses || []).length) {
      const sizes = (d.doses || []).filter((k) => k.diameterMm && isNum(k.diameterMm[0])).map((k) => (TURN_SHORT[k.steps] || IL.turnLabel(k.steps)) + ' ' + IL.fmt(k.diameterMm[0], 2));
      const head = d.source === 'measured' ? 'measured ' + when(d.atMs) : 'partly measured';
      return head + (sizes.length ? ': ' + sizes.join(', ') + ' mm' : '') + (isNum(d.mgPerTurn) ? '; ' + IL.fmt(d.mgPerTurn, 2) + ' mg per turn' : '; not weighed yet');
    }
    return 'not measured yet';
  }
  function setupByKey(key) { return T.data ? (T.data.setups || []).find((x) => x.key === key) || null : null; }
  const mountedSetup = () => (T.data ? (T.data.setups || []).find((x) => x.mounted) || null : null);
  const neverOn = (s) => { const n = needleOf(s); const m = mountedSetup(); return !n.anchor && !(m && m.model === 'bench-22g'); };

  // ================================================================================== the library (GET /api/tip-setups)
  function load() {
    if (T.loading) return T.loading;
    T.loading = IL.api('GET', '/api/tip-setups?retired=true').then((d) => { T.data = d; T.error = null; T.previews = {}; },
      (e) => { T.error = IL.errorText(e); })
      .then(() => { T.loading = null; IL.emit('tips', T.data); IL.render(); return T.data; });
    return T.loading;
  }
  /** Reads the library again when what it shows changed in the status (a mount, a needle change, a picture, a correction). */
  function statusSig(s) {
    const t = tipOf(s), n = needleOf(s);
    return JSON.stringify([t.key, t.setupVersion, t.mountedAtMs, t.thumbUrl, t.dots, t.undoUntilMs, n.changedAtMs, n.xyChecked, n.anchored, s.run && s.run.runId,
      s.padCalibration && s.padCalibration.state]);
  }
  IL.on('status', (s) => {
    if (!s || !s.dispense || !s.dispense.running) return;
    const sig = statusSig(s);
    if (sig !== T.sig) { T.sig = sig; load(); }
    undoBand(s);
  });
  IL.on('taskend', () => load());

  // ================================================================================== strip cell (core.js renders it)
  /**
   * The strip's Tip cell: {kind, v ([main, long, level] or text), t (title)}. The name's first word stays when the strip
   * is narrow ("22G" of "22G bent taper", 1024 px): the rest goes with the strip's other long parts (cells--t2).
   */
  function cellView(s) {
    const d = disp(s), t = tipOf(s), n = needleOf(s);
    if (!d.running) return { kind: 'off', v: '—', t: 'The dispenser engine is not running.' };
    const name = t.name || 'Tip';
    const sp = name.indexOf(' ');
    const head = sp > 0 ? name.slice(0, sp) : name, rest = sp > 0 ? name.slice(sp) : '';
    if (t.complete === false) return { kind: 'block', v: 'Incomplete', t: 'The ' + name + ' has no bore, OD, form or taper yet: fill it in (Setup › Dispenser & tip › Edit…).' };
    const todo = [];
    if (!n.anchor || !n.anchored) todo.push('frame');
    if (!n.xyChecked) todo.push('landing');
    if (todo.length) {
      const since = isNum(n.changedAtMs) ? 'put on at ' + clock(n.changedAtMs) : 'on the dispenser';
      return { kind: 'block', v: [head, rest + ' · ' + todo.join(' and ') + ' to do', 2],
        t: 'The ' + name + ' was ' + since + ': ' + (todo.length === 2 ? 'record its needle Z frame and check where it lands' : todo[0] === 'frame' ? 'record its needle Z frame' : 'check where it lands') + ' (Setup › Dispenser & tip).' };
    }
    return { kind: 'ok', v: rest ? [head, rest, 2] : name, t: 'The ' + name + ' is on the dispenser' + (t.mountedAtMs ? ' (mounted ' + when(t.mountedAtMs) + ')' : '') + '. Setup › Dispenser & tip' };
  }

  // ================================================================================== the order after a needle change (§22.3)
  /**
   * The steps after a needle change, as Setup's #tip-next lists them: [{key, label, done, fix: {fix|href|view, label}}].
   * Frame, offset (taken with the frame for a tip that was never on), regulator and prime ticks (they lapse at a needle
   * change), where it lands, the touch-off and, for a tip whose dots are not measured, Measure its dots.
   */
  function nextSteps(s) {
    const n = needleOf(s), t = tipOf(s), prof = disp(s).profile || {};
    const never = neverOn(s);
    const psi = IL.fmtPsi(prof.pressurePsi);
    const out = [];
    out.push({ key: 'frame', label: never ? 'Record its needle Z frame: Both tips touch, or touch off' : 'Record its needle Z frame', done: !!n.anchor && !!n.anchored,
      fix: { href: '#setup/needle', label: 'Needle Z frame…' } });
    if (!never) out.push({ key: 'offset', label: 'Confirm the frame’s offset (or enter a measured one)', done: !!n.touchedOff, fix: { href: '#setup/needle', label: 'Needle Z frame…' } });
    out.push({ key: 'regulator', label: 'Set the regulator to ' + psi + ' psi and read it', done: !!IL.attest.valid('regulator', s), fix: { fix: 'attest-regulator', label: 'Reads ' + psi + ' psi' } });
    out.push({ key: 'prime', label: 'Prime until paste comes out', done: !!(IL.attest.valid('prime-seen', s) || IL.attest.valid('test-dot-seen', s)), fix: { href: '#setup/prime', label: 'Prime…' } });
    out.push({ key: 'landing', label: 'Check where it lands', done: !!n.xyChecked, fix: { fix: 'landing-check', label: 'Check where it lands…' } });
    const touched = !!n.touchedOff && isNum(n.zOffsetMm) && isNum(n.anchorOffsetMm) && Math.abs(n.zOffsetMm - n.anchorOffsetMm) >= 0.0005;
    out.push({ key: 'touch-off', label: never ? 'Touch off (recommended: this tip was never on)' : 'Touch off (refines the offset)', done: touched, optional: true,
      fix: { href: '#calibrate/touch-off', label: 'Touch off…' } });
    if (t.dots === 'none') out.push({ key: 'measure', label: 'Measure its dots · ≈ 8 min', done: false, fix: { fix: 'measure-tip', label: 'Measure its dots…' } });
    return out;
  }
  /** After a needle change until frame, offset, ticks and landing are done (or while the dots are not measured). */
  function changeOpen(s) {
    const n = needleOf(s);
    const steps = nextSteps(s);
    const open = steps.filter((x) => !x.done && !x.optional);
    return { steps, open, show: (isNum(n.changedAtMs) || !n.anchored || !n.xyChecked) && open.length > 0 };
  }

  // ================================================================================== Setup › Dispenser & tip (#card-tip)
  /** Fills the card shell setup.js made (card(): root, body, foot, ref, state). */
  function buildCard(c) {
    T.card = c;
    c.root.classList.add('tip-card');
    // the picture: the main picture (a button opening #photo-view), or an outline icon
    c.img = el('img', { alt: '', width: '200', height: '150', decoding: 'async' });
    c.photo = el('button', { type: 'button', class: 'photo-thumb tip-photo', id: 'tip-photo', hidden: true }, c.img);
    c.photo.addEventListener('click', () => { const m = mountedSetup(); if (m && m.mainPhoto) view(m.mainPhoto, { list: m.photos, setup: m, from: c.photo }); });
    c.none = el('div', { class: 'tip-photo-none', id: 'tip-photo-none', hidden: true }, noPicture(), el('span', { text: 'No picture of this tip yet.' }));
    c.cap = el('div', { class: 'tip-photo-cap', id: 'tip-photo-cap' });
    c.chooser = chooser({ id: 'btn-tip-upload', inputId: 'tip-file', label: 'Add a photo…', cls: 'btn btn--sm',
      onFile: (f) => sendSetupPhoto(f, c.chooser.btn) });
    c.pic = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-tip-picture', title: 'N2 goes to the bottom camera at travel height (31.5 mm) and the camera takes one picture of the tip. About 5 s.' },
      IL.icon('i-camera'), el('span', { class: 'lbl', text: 'Take a picture from below' }));
    IL.press2(c.pic, 'move to the camera', () => takePicture([]), { ok: pictureOk });
    c.picWhy = el('div', { class: 'reason', id: 'tip-photo-why', hidden: true });
    c.drop = dropZone({ id: 'tip-drop', onFile: (f) => sendSetupPhoto(f, null) });
    c.thumbs = el('div', { class: 'photo-strip tip-thumbs', id: 'tip-thumbs' });
    const pic = el('div', { class: 'tip-pic', 'data-refusals': '' }, c.photo, c.none, c.cap,
      el('div', { class: 'btn-row tip-pic__acts' }, c.chooser.btn, c.pic, c.chooser.input), c.picWhy, c.thumbs, c.drop);
    // the facts
    c.name = el('h3', { class: 'tip-name', id: 'tip-name' });
    c.kv = el('dl', { class: 'kv kv--wrap tip-kv', id: 'tip-kv' });
    c.bend = el('div', { class: 'tip-bend', id: 'tip-bend', hidden: true, 'data-refusals': '' });
    c.next = el('ol', { class: 'tip-next', id: 'tip-next', hidden: true, 'aria-label': 'After the needle change' });
    c.nextHead = el('div', { class: 'section-k tip-next-k', hidden: true }, el('span', { text: 'After the needle change' }), el('span', { class: 'rule' }), el('span', { class: 'count', id: 'tip-next-n' }));
    const facts = el('div', { class: 'tip-facts' }, c.name, c.kv, c.bend, c.nextHead, c.next);
    c.body.append(el('div', { class: 'tip-grid' }, pic, facts));
    // foot: the first open step after a change (filled), else Change tip…; More: add, edit, retire
    c.primary = el('div', { class: 'tip-primary', id: 'tip-primary' });
    c.change = el('button', { type: 'button', class: 'btn', id: 'btn-tip-change' }, IL.icon('i-swap'), 'Change tip…');
    c.change.addEventListener('click', () => openChange());
    c.add = el('button', { type: 'button', class: 'menu__i', id: 'btn-tip-add' }, 'Add a tip…');
    c.edit = el('button', { type: 'button', class: 'menu__i', id: 'btn-tip-edit' }, 'Edit…');
    c.retire = el('button', { type: 'button', class: 'menu__i', id: 'btn-tip-retire', title: 'Retire a tip that is not on the dispenser: Change tip lists them' }, 'Retire…');
    c.more = el('details', { class: 'menu', id: 'tip-more' }, el('summary', { class: 'btn btn--quiet', title: 'Add, edit or retire a tip' }, 'More', IL.icon('i-chev')),
      el('div', { class: 'menu__list', role: 'menu' }, c.add, c.edit, c.retire));
    c.add.addEventListener('click', () => { c.more.open = false; openAdd({}); });
    c.edit.addEventListener('click', () => { c.more.open = false; const m = mountedSetup(); if (m) openEdit(m.key); });
    c.retire.addEventListener('click', () => { c.more.open = false; openChange({ retire: true }); });
    c.more.addEventListener('toggle', () => { if (c.more.open) c.moreEsc = IL.onEsc(() => { c.more.open = false; c.more.querySelector('summary').focus(); return true; }); else if (c.moreEsc) { c.moreEsc(); c.moreEsc = null; } });
    c.footWhy = el('span', { class: 'reason su-foot-reason', hidden: true });
    c.foot.append(c.primary, c.change, c.footWhy, el('span', { class: 'spacer' }), c.more);
    return c.root;
  }
  /** An outline icon: "no picture". */
  function noPicture() {
    const s = IL.svgEl('svg', { class: 'ic photo-none__ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' });
    s.append(IL.svgEl('rect', { x: 3.5, y: 5.5, width: 17, height: 13, rx: 2 }), IL.svgEl('circle', { cx: 9, cy: 10.5, r: 1.6 }),
      IL.svgEl('path', { d: 'M4.5 17l5-4.5 3.5 3 2.5-2 4 3.5' }));
    return s;
  }
  /**
   * Patches the card (every render): state, picture, facts, the steps after a change and the foot. h = setup.js's
   * {cardState(c, kind, text, title)}.
   */
  function renderCard(s, h) {
    const c = T.card;
    if (!c) return null;
    const t = tipOf(s), n = needleOf(s), m = mountedSetup();
    const name = t.name || (m && m.name) || 'tip';
    const ch = changeOpen(s);
    const todo = [];
    if (!n.anchor || !n.anchored) todo.push('frame');
    if (!n.xyChecked) todo.push('landing');
    let kind = 'ok', text = name;
    if (t.complete === false) { kind = 'block'; text = 'Incomplete'; }
    else if (todo.length) { kind = 'block'; text = (isNum(n.changedAtMs) ? 'Changed ' + clock(n.changedAtMs) + ': ' : '') + todo.join(' and ') + ' to do'; }
    else if (t.dots === 'none') { kind = 'need'; text = 'Dots not measured'; }
    h.cardState(c, kind, text, kind === 'ok' ? 'The ' + name + ' is on the dispenser' : null);
    // the picture
    const main = m && m.mainPhoto;
    IL.show(c.photo, !!main);
    IL.show(c.none, !main);
    if (main) {
      if (c.img.dataset.src !== main.thumbUrl) { c.img.dataset.src = main.thumbUrl; c.img.src = main.thumbUrl; }
      IL.setAttr(c.img, 'alt', main.caption || 'Picture of the ' + name);
      IL.setAttr(c.photo, 'title', (main.caption || 'Picture of the ' + name) + ': open');
    }
    IL.setText(c.cap, main ? (main.caption || 'Picture of the ' + name) + ' · ' + when(main.atMs) : '');
    IL.show(c.cap, !!main);
    const others = m ? (m.photos || []).filter((p) => !main || p.id !== main.id) : [];
    IL.keyed(c.thumbs, JSON.stringify(others.map((p) => p.id)), () => (others.length ? strip(others, { max: 4, setup: m, list: m.photos }) : null));
    IL.show(c.thumbs, others.length > 0);
    // the actions: a chooser is gated (it blocks STOP while open), the drop zone stays open
    IL.gate(c.chooser.btn, chooserLock(s));
    const pl = pictureLock(s);
    IL.gate(c.pic, pl, c.picWhy);
    // the facts
    IL.setText(c.name, name);
    const tw = m ? tipWords(m) : t.summary || '—';
    const land = n.landing || {};
    const landText = !n.xyChecked ? (n.headOffsetChanged ? 'N2’s head offset changed in OpenPnP since it was checked' : 'not checked since the change')
      : land.known ? signed2(land.dxMm) + ', ' + signed2(land.dyMm) + ' mm, ' + (land.jobId ? 'checked ' : 'set ') + when(land.setAtMs) + (land.note && !land.jobId ? ' · ' + land.note : '') : 'not checked';
    const prof = disp(s).profile || {};
    const rows = [
      ['Dispenser', m ? m.dispenser : t.dispenser || '—'],
      ['Tip', tw],
    ];
    if (m && m.form === 'bent') rows.push(['Bend', m.bendToward ? 'points toward ' + towardWord(m.bendToward) : 'not recorded: which way does it point?']);
    rows.push(['Bore', m ? boreWords(m) : '—'],
      ['Smallest dot', m ? (TURN_WORD[m.minDotSteps] || IL.turnLabel(m.minDotSteps)) + ' (' + m.minDotSteps + ' steps)' : '—'],
      ['Dots', m ? dotsWords(m) : '—'],
      ['Pad types', m ? calibratedWords(m) : '—'],
      ['Recipe', prof.name || '—'],
      ['Lands', landText],
      ['On since', t.mountedAtMs ? when(t.mountedAtMs) : 'before the update']);
    IL.kv(c.kv, rows);
    // which way the bend points, when not recorded: four small buttons (a plain edit)
    const askBend = !!m && m.form === 'bent' && !m.bendToward;
    IL.show(c.bend, askBend);
    IL.keyed(c.bend, askBend ? m.key + m.version : '', () => (askBend ? [el('span', { class: 'tip-bend__t', text: 'The bend points toward:' }),
      ...['+x', '-x', '+y', '-y'].map((k) => {
        const b = el('button', { type: 'button', class: 'btn btn--sm', 'data-toward': k }, towardWord(k));
        b.addEventListener('click', () => IL.act(b, async () => { const r = await IL.api('PUT', '/api/tip-setups/' + encodeURIComponent(m.key), { bendToward: k }); await load(); return r; },
          { ok: 'Noted: the ' + m.name + '’s bend points toward ' + towardWord(k) + '.' }));
        return b;
      })] : null));
    // the steps after a change
    IL.show(c.next, ch.show);
    IL.show(c.nextHead, ch.show);
    IL.setText($('tip-next-n'), ch.show ? (ch.steps.filter((x) => x.done).length + ' of ' + ch.steps.length + ' done') : '');
    const key = JSON.stringify(ch.show ? ch.steps.map((x) => [x.key, x.done, x.label]) : null);
    const first = ch.show ? ch.steps.find((x) => !x.done) : null;
    IL.keyed(c.next, key, () => (ch.show ? ch.steps.map((x, i) => el('li', { class: 'tip-step' + (x.done ? ' tip-step--done' : '') + (x === first ? ' tip-step--now' : ''), 'data-step': x.key },
      x.done ? IL.glyph('ok', 'xs') : x === first ? IL.glyph('now', 'xs', 'Now', i + 1) : IL.glyph('num', 'xs', 'Step ' + (i + 1), i + 1),
      el('span', { class: 'tip-step__t', text: x.label }),
      x.done || x === first ? null : IL.fixButton(x.fix, { cls: 'btn--sm btn--quiet' }))) : null));
    // foot: the first open step is the primary, else Change tip…
    IL.keyed(c.primary, first ? first.key + '|' + first.fix.label : '', () => (first ? IL.fixButton(Object.assign({}, first.fix, { primary: true }), {}) : null));
    IL.setClass(c.change, 'btn' + (first ? '' : ' btn--primary'));
    const lock = IL.taskRunning(s) ? IL.lockReason(s) : null;
    IL.setText(c.footWhy, lock || '');
    IL.show(c.footWhy, !!lock);
  }
  /**
   * The bring-up step "Dispenser & tip" (setup.js, pure on the status): ok the name, need dots not measured, block
   * incomplete; after a needle change, until its frame, offset and landing are done, the card's first open step (§22.3:
   * Measure its dots comes after them, so it is never the bring-up's next step ahead of the frame).
   */
  function step(s) {
    const t = tipOf(s), n = needleOf(s);
    if (!t.key) return null;
    const name = t.name || 'tip';
    if (t.complete === false) return { state: 'block', value: 'incomplete', detail: 'The ' + name + ' has no bore, OD, form or taper yet: fill it in.', fixes: [{ fix: 'tip-edit', label: 'Edit…' }] };
    if (isNum(n.changedAtMs) && (!n.anchor || !n.anchored || !n.touchedOff || !n.xyChecked)) {
      const first = changeOpen(s).open[0];
      if (first) {
        const todo = [];
        if (!n.anchor || !n.anchored) todo.push('frame');
        if (!n.xyChecked) todo.push('landing');
        return { state: 'block', value: (todo.length ? todo.join(' and ') : 'offset') + ' to do',
          detail: 'The ' + name + ' was put on at ' + clock(n.changedAtMs) + '. Next: ' + first.label + '.', fixes: [first.fix] };
      }
    }
    if (t.dots === 'none') return { state: 'need', value: 'dots not measured', detail: 'The ' + name + '’s dots are not measured: suggestions need its dot sizes and paste per turn.', fixes: [{ fix: 'measure-tip', label: 'Measure its dots…' }] };
    return { state: 'ok', value: name, detail: 'The ' + name + ' is on the dispenser' + (t.mountedAtMs ? ' since ' + when(t.mountedAtMs) : '') + '.' };
  }

  // ================================================================================== pictures: gates and actions
  /** A file chooser is modal to the browser window (F9 and STOP do nothing while it is open): locked while anything runs. */
  function chooserLock(s) {
    const r = IL.lockReason(s, { pressureOff: true });
    if (!r) return null;
    return /^Locked while /.test(r) ? r + ': add the photo when it has finished' : r;
  }
  /** Take a picture from below: homed, idle, pressure off, the bottom camera (the engine says the rest). */
  function pictureLock(s) {
    const r = IL.lockReason(s, { connected: true, homed: true, engine: true });
    if (r === 'Not homed: home first') return 'Home first: the head moves to the bottom camera.';
    if (r === 'Pressure is held: switch it off first') return 'Pressure is held: switch it off first (the head moves to the bottom camera).';
    return r;
  }
  function pictureOk(r) {
    const p = r && (r.photo || (r.result && r.result.photo));
    return p ? 'Picture taken: ' + (p.caption || 'the tip from below') + '.' : 'Picture taken.';
  }
  /** POST /api/tip-setups/{mounted}/picture {calibrationIds, wait}: the photo, or the engine's refusal. */
  async function takePicture(calibrationIds) {
    const key = tipOf(S.status).key;
    if (!key) throw new Error('No tip is mounted.');
    const r = await IL.api('POST', '/api/tip-setups/' + encodeURIComponent(key) + '/picture', { calibrationIds: calibrationIds || [], wait: true }, { timeoutMs: 90000 });
    // with wait: {kind, setupKey, result: {ok, message, run, photo}, finished}
    const res = r && r.result ? r.result : r;
    if (res && res.ok === false) throw new Error(res.message || 'No picture was stored.');
    await load();
    IL.emit('photos', res && res.photo);
    return res;
  }
  /** A picture of the mounted setup (Setup card): kind setup. */
  function sendSetupPhoto(file, btn) {
    const t = tipOf(S.status);
    return IL.act(btn, async () => {
      const p = await send(file, { kind: 'setup', setup: t.key, caption: 'Picture of the ' + (t.name || 'tip') });
      await load();
      return p;
    }, { ok: () => 'Picture added: it shows later which needle this was.', refusalIn: T.card && T.card.ref });
  }

  // ================================================================================== IL.photos
  /** The browser draws the file (orientation from the file, at most 2048 px on the long side) and sends a JPEG 0.85. */
  async function normalize(file) {
    let bmp;
    try { bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch (e) { throw new IL.ApiError(400, { error: 'not-an-image', reason: HEIC }, HEIC); }
    const k = Math.min(1, 2048 / Math.max(bmp.width, bmp.height));
    const w = Math.max(1, Math.round(bmp.width * k)), h = Math.max(1, Math.round(bmp.height * k));
    const cv = document.createElement('canvas');
    cv.width = w;
    cv.height = h;
    const g = cv.getContext('2d');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, w, h);
    g.drawImage(bmp, 0, 0, w, h);
    if (bmp.close) bmp.close();
    return new Promise((res, rej) => cv.toBlob((b) => (b ? res(b) : rej(new IL.ApiError(400, { error: 'not-an-image', reason: HEIC }, HEIC))), 'image/jpeg', 0.85));
  }
  /** POST /api/photos?{query} with the picture as the body → the photo view (201 {photo}). */
  async function upload(blob, query) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(query || {})) if (v !== null && v !== undefined && v !== '') q.set(k, Array.isArray(v) ? v.join(',') : String(v));
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 60000);
    let r, text;
    try {
      r = await fetch('/api/photos?' + q.toString(), { method: 'POST', headers: { 'Content-Type': 'image/jpeg' }, body: blob, signal: ctl.signal, cache: 'no-store' });
      text = await r.text();
    } catch (e) {
      throw new IL.ApiError(0, { error: ctl.signal.aborted ? 'timeout' : 'network' }, 'OpenPnP did not answer the upload.');
    } finally { clearTimeout(timer); }
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (r.status === 413) throw new IL.ApiError(413, data || { error: 'too-large' }, (data && data.reason) || 'The picture is larger than 8 MB: send a smaller one (phones can share a reduced size).');
    if (!r.ok) throw new IL.ApiError(r.status, data, r.status + ' ' + r.statusText);
    return data && data.photo;
  }
  /** normalize + upload; emits 'photos'. */
  async function send(file, query) {
    if (!file) throw new Error('No file.');
    const blob = await normalize(file);
    const p = await upload(blob, query);
    IL.emit('photos', p);
    return p;
  }
  const hasFiles = (e) => !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));
  /**
   * A drop zone (no chooser, so it stays open while a board runs: the engine refuses an upload then, with its own text):
   * div.photo-drop. opts {id, onFile(file), text}.
   */
  function dropZone(o) {
    const z = el('div', { class: 'photo-drop', id: o.id || null },
      el('div', { class: 'photo-drop__t', text: o.text || 'Drop a picture here' }),
      el('div', { class: 'photo-drop__s', text: DROP_HINT }));
    for (const ev of ['dragenter', 'dragover']) {
      z.addEventListener(ev, (e) => { if (!hasFiles(e)) return; e.preventDefault(); e.stopPropagation(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'; z.classList.add('photo-drop--over'); });
    }
    z.addEventListener('dragleave', (e) => { if (!z.contains(e.relatedTarget)) z.classList.remove('photo-drop--over'); });
    z.addEventListener('drop', (e) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      e.stopPropagation();   // the page-wide drop guard (boardimport.js) never sees it
      z.classList.remove('photo-drop--over');
      const f = e.dataTransfer.files && e.dataTransfer.files[0];
      if (f) o.onFile(f);
    });
    return z;
  }
  /** A file button and its hidden input[type=file][accept=image/*]: {btn, input}. Gate the button every render (chooserLock). */
  function chooser(o) {
    const input = el('input', { type: 'file', id: o.inputId, accept: 'image/*', class: 'sr', tabindex: '-1', 'aria-hidden': 'true' });
    const btn = el('button', { type: 'button', class: o.cls || 'btn btn--sm', id: o.id }, IL.icon('i-plus'), el('span', { class: 'lbl', text: o.label || 'Add a photo…' }));
    btn.addEventListener('click', () => { if (!btn.disabled) input.click(); });
    input.addEventListener('change', () => { const f = input.files && input.files[0]; input.value = ''; if (f) o.onFile(f); });
    return { btn, input };
  }
  /** Thumbnail buttons (button.photo-thumb > img[alt=caption]): the first max, then "+n". opts {max, list, setup, onChange}. */
  function strip(photos, o) {
    const opts = o || {};
    const max = opts.max || photos.length;
    const out = photos.slice(0, max).map((p) => {
      const b = el('button', { type: 'button', class: 'photo-thumb photo-thumb--sm' + (p.removedAtMs ? ' photo-thumb--gone' : ''), 'data-photo': p.id,
        title: (p.caption || 'Picture') + ' · ' + when(p.atMs) },
      p.removedAtMs ? el('span', { class: 'photo-thumb__gone', text: 'removed' }) : el('img', { src: p.thumbUrl, alt: p.caption || 'Picture ' + p.id, loading: 'lazy', width: '72', height: '54' }));
      if (!p.removedAtMs) b.addEventListener('click', () => view(p, Object.assign({}, opts, { from: b, list: opts.list || photos })));
      return b;
    });
    if (photos.length > max) {
      const more = el('button', { type: 'button', class: 'photo-thumb photo-thumb--sm photo-thumb--more', title: 'All ' + photos.length + ' pictures' }, '+' + (photos.length - max));
      more.addEventListener('click', () => view(photos[max], Object.assign({}, opts, { from: more, list: opts.list || photos })));
      out.push(more);
    }
    return el('div', { class: 'photo-strip__row' }, out);
  }
  /**
   * #photo-view: IL.dialog.open size lg (the scrim starts under the strip, STOP stays live, Esc closes, focus returns to
   * the thumbnail). The image fits the dialog. Its foot: the caption, "Use as the tip's picture", "Remove this picture"
   * (two presses; never on a result photo). opts {list, setup, from, onChange}.
   */
  function view(photo, opts) {
    const o = opts || {};
    const list = (o.list || [photo]).filter((p) => !p.removedAtMs);
    let i = Math.max(0, list.findIndex((p) => p.id === photo.id));
    const img = el('img', { class: 'photo-view__img', id: 'photo-view-img', alt: '' });
    const cap = el('div', { class: 'photo-view__cap', id: 'photo-view-cap' });
    const meta = el('div', { class: 'photo-view__meta', id: 'photo-view-meta' });
    const prev = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', id: 'btn-photo-prev', 'aria-label': 'Previous picture' }, '‹');
    const next = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', id: 'btn-photo-next', 'aria-label': 'Next picture' }, '›');
    const main = el('button', { type: 'button', class: 'btn', id: 'btn-photo-main' }, 'Use as the tip’s picture');
    const rm = el('button', { type: 'button', class: 'btn btn--danger-ghost', id: 'btn-photo-remove' }, 'Remove this picture');
    const refs = el('div', { 'data-refusals': '', class: 'photo-view__ref' });
    const cur = () => list[i] || photo;
    const show = () => {
      const p = cur();
      img.src = p.url;
      img.alt = p.caption || 'Picture ' + p.id;
      IL.setText(cap, p.caption || 'Picture ' + p.id);
      IL.setText(meta, [when(p.atMs), p.source === 'upload' ? 'uploaded' : p.source === 'bottom-camera' ? 'bottom camera' : 'top camera',
        isNum(p.widthPx) ? p.widthPx + ' × ' + p.heightPx + ' px' : null, list.length > 1 ? (i + 1) + ' of ' + list.length : null].filter(Boolean).join(' · '));
      IL.show(prev, list.length > 1);
      IL.show(next, list.length > 1);
      IL.show(main, p.kind !== 'result' && !p.removedAtMs && !!p.setupKey);
      IL.show(rm, !!p.removable);
      refs.replaceChildren();
    };
    prev.addEventListener('click', () => { i = (i - 1 + list.length) % list.length; show(); });
    next.addEventListener('click', () => { i = (i + 1) % list.length; show(); });
    main.addEventListener('click', () => IL.act(main, async () => {
      const r = await IL.api('POST', '/api/photos/' + cur().id + '/main', {});
      await load();
      if (o.onChange) o.onChange();
      return r;
    }, { ok: 'This is the tip’s picture now.', refusalIn: refs }));
    IL.press2(rm, 'remove the picture', async () => {
      const p = cur();
      const r = await IL.api('DELETE', '/api/photos/' + p.id, { why: 'removed in the web UI' });
      h.close('removed');
      await load();
      if (o.onChange) o.onChange();
      IL.emit('photos', null);
      undoRemove(r);
      return r;
    }, { ok: 'Picture removed: Undo brings it back (it is kept 7 days).', refusalIn: refs });
    const h = IL.dialog.open({ id: 'photo-view', size: 'lg', eyebrow: o.setup ? 'Picture · ' + o.setup.name : 'Picture', title: photo.caption || 'Picture',
      body: [el('figure', { class: 'photo-view' }, img)],
      foot: [el('div', { class: 'photo-view__t' }, cap, meta, refs), prev, next, main, rm,
        el('button', { type: 'button', class: 'btn', id: 'btn-photo-close', onclick: () => h.close('button') }, 'Close')],
      initialFocus: '#btn-photo-close' });
    show();
    return h;
  }
  /** The band after a removal: Undo for 20 s (POST /api/photos/{id}/restore; the file stays 7 days). */
  function undoRemove(r) {
    const id = r && r.id;
    if (!id) return;
    const b = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-photo-undo' }, 'Undo');
    b.addEventListener('click', () => IL.act(b, async () => {
      const x = await IL.api('POST', '/api/photos/' + id + '/restore', {});
      IL.msg('photo-undo', null);
      await load();
      IL.emit('photos', x);
      return x;
    }, { ok: 'Picture brought back.' }));
    IL.msg('photo-undo', { kind: 'info', alert: false, text: 'Picture removed ' + IL.fmtTime(Date.now()) + '.', sub: 'It is kept 7 days: Undo brings it back.', actions: [b] });
    clearTimeout(T.undoPhotoTimer);
    T.undoPhotoTimer = setTimeout(() => IL.msg('photo-undo', null), 20000);
  }

  // ================================================================================== Change tip (panel view `tip-change`)
  /** Opens Change tip (opts.again: the mounted tip's "again (a new needle)" row checked; opts.retire: the retire actions). */
  function openChange(opts) {
    const o = opts || {};
    T.again = !!o.again;
    T.retireMode = !!o.retire;
    T.sel = o.again ? tipOf(S.status).key : o.select || T.sel;
    load();
    if (IL.panel.isPushed('tip-change')) IL.panel.reset();
    else IL.openView('tip-change', { keep: true });
  }
  function preview(key) {
    if (!key) return null;
    const p = T.previews[key];
    if (!p || (!p.loading && Date.now() - p.at > 5000)) {
      const x = T.previews[key] = Object.assign(p || {}, { loading: true, at: Date.now() });
      IL.api('GET', '/api/tip-setups/' + encodeURIComponent(key) + '/mount-preview').then((r) => { x.p = r; x.error = null; }, (e) => { x.error = IL.errorText(e); })
        .then(() => { x.loading = false; x.at = Date.now(); IL.render(); });
    }
    return T.previews[key];
  }
  /** Why Mount is locked now, or null. */
  function mountLock(s, sel, pv) {
    if (!sel) return 'Choose the tip that is on the dispenser now';
    const lock = IL.lockReason(s, { pressureOff: false });
    if (lock) return /^Locked while /.test(lock) ? lock + ': a needle cannot come off while the head moves: press STOP first' : lock;
    const pr = s.pressure || {};
    if (pr.state && pr.state !== 'off') return 'Switch the pressure off first: a needle comes off with the pressure off.';
    if (IL.pt && IL.pt.unsaved && IL.pt.unsaved()) return 'Save or discard the ' + nameOf(s) + '’s recipe changes first.';
    if (pv && pv.p && pv.p.refusal) return pv.p.refusal;
    return null;
  }
  IL.view('tip-change', {
    title: 'Change tip',
    render(parts, s, first) {
      if (first) buildChange(parts);
      renderChange(s);
    },
    unmount() { T.again = false; T.retireMode = false; },
  });
  function buildChange(parts) {
    const off = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-tip-pressure-off' }, 'Pressure off');
    off.addEventListener('click', (ev) => IL.runFix('pressure-off', off, null, ev));
    parts.body.append(el('div', { class: 'tip-change', 'data-refusals': '' },
      el('p', { class: 'panel__lead', text: 'Swap the needle with the pressure off, then say which tip is on: Mount records the needle change.' }),
      el('ol', { class: 'tip-swap', id: 'tip-swap' },
        el('li', { 'data-step': 'pressure' }, el('span', { class: 'tip-swap__g', id: 'tip-swap-g1' }), el('div', { class: 'tip-swap__t' }, el('b', { text: 'Switch the pressure off' }),
          el('span', { class: 'tip-swap__s', id: 'tip-swap-s1' })), off),
        el('li', { 'data-step': 'swap' }, IL.glyph('num', 'sm', 'Step 2', 2), el('div', { class: 'tip-swap__t' }, el('b', { text: 'Unscrew the needle and screw on the one you are putting on' }),
          el('span', { class: 'tip-swap__s', id: 'tip-swap-s2' })), el('span', { id: 'tip-swap-pic' })),
        el('li', { 'data-step': 'mount' }, IL.glyph('num', 'sm', 'Step 3', 3), el('div', { class: 'tip-swap__t' }, el('b', { text: 'Pick it below and mount it' }),
          el('span', { class: 'tip-swap__s', text: 'Mount is the moment you say the needle is on.' })))),
      el('fieldset', { class: 'tip-list', id: 'tip-list' }, el('legend', { text: 'Which tip is on the dispenser now?' }), el('div', { id: 'tip-list-opts', class: 'tip-list__opts' })),
      el('details', { class: 'disclose tip-retired', id: 'tip-retired' }, el('summary', { id: 'tip-retired-s', text: 'Show retired tips' }), el('ul', { class: 'tip-retired__l', id: 'tip-retired-l' })),
      el('div', { class: 'notebox notebox--info tip-what', id: 'tip-change-what', role: 'status' }),
      el('div', { class: 'btn-row tip-sel', id: 'tip-sel-acts' })));
    const ref = el('div', { id: 'tip-change-ref', 'data-refusals': '' });
    const mount = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-tip-mount' }, el('span', { class: 'lbl', id: 'tip-mount-l', text: 'Mount' }));
    IL.press2(mount, 'mount', () => doMount(), { ok: (r) => (r && r.text) || 'Mounted.', refusalIn: ref });
    const add = el('button', { type: 'button', class: 'btn', id: 'btn-tip-add2' }, IL.icon('i-plus'), 'Add a tip…');
    add.addEventListener('click', () => openAdd({ fromChange: true }));
    parts.foot.append(ref, mount, el('div', { class: 'reason', id: 'tip-change-why', hidden: true }),
      el('div', { class: 'btn-row tip-change__more' }, add));
  }
  function renderChange(s) {
    const d = T.data;
    const pr = s.pressure || {};
    const isOff = !pr.state || pr.state === 'off';
    IL.setGlyph($('tip-swap-g1'), isOff ? 'ok' : 'need', 'sm', isOff ? 'Done' : 'Step 1');
    IL.setText($('tip-swap-s1'), isOff ? 'it is off' : 'it is on: switch it off before the needle comes off');
    IL.show($('btn-tip-pressure-off'), !isOff);
    const setups = d ? (d.setups || []) : [];
    const live = setups.filter((x) => !x.retired);
    const retired = setups.filter((x) => x.retired);
    if (T.sel && !live.some((x) => x.key === T.sel)) T.sel = null;
    // the list: the mounted one first ("again: a new needle"), then the others
    const ordered = live.slice().sort((a, b) => (b.mounted ? 1 : 0) - (a.mounted ? 1 : 0));
    const lkey = JSON.stringify([ordered.map((x) => [x.key, x.version, x.name, x.mounted, x.complete, x.mainPhoto && x.mainPhoto.id, x.padTypes && x.padTypes.names]), !!d, T.error]);
    IL.keyed($('tip-list-opts'), lkey, () => {
      if (!d) return el('p', { class: 'muted', text: T.error ? 'Could not read the tips: ' + T.error : 'Reading the tips…' });
      const out = ordered.map((x) => {
        const radio = el('input', { type: 'radio', name: 'tip', value: x.key, id: 'tip-opt-' + x.key });
        if (T.sel === x.key) radio.checked = true;
        radio.addEventListener('change', () => { if (radio.checked) { T.sel = x.key; preview(x.key); IL.render(); } });
        return el('label', { class: 'tip-opt' + (x.mounted ? ' tip-opt--on' : ''), 'data-key': x.key, for: 'tip-opt-' + x.key }, radio, thumb(x, 'sm'),
          el('span', { class: 'tip-opt__t' },
            el('span', { class: 'tip-opt__n' }, x.mounted ? 'The ' + x.name + ' again (a new needle)' : x.name,
              !x.complete ? el('span', { class: 'tag tag--off', text: 'incomplete' }) : null, x.mounted ? el('span', { class: 'tag tag--now', text: 'on now' }) : null),
            el('span', { class: 'tip-opt__s', text: x.summary || tipWords(x) }),
            el('span', { class: 'tip-opt__s', text: typesWords(x) })));
      });
      if (live.length <= 1) {
        const add = el('button', { type: 'button', class: 'link', text: 'Add a tip…' });
        add.addEventListener('click', () => openAdd({ fromChange: true }));
        out.push(el('p', { class: 'tip-only', id: 'tip-only' }, 'Only the ' + (live[0] ? live[0].name : 'mounted tip') + ' is set up. Add the tip you are putting on: ', add));
      }
      return out;
    });
    for (const r of document.querySelectorAll('#tip-list-opts input[name=tip]')) if (r.checked !== (r.value === T.sel)) r.checked = r.value === T.sel;
    // the retired ones, each with Bring back
    IL.show($('tip-retired'), retired.length > 0);
    IL.setText($('tip-retired-s'), 'Show retired tips (' + retired.length + ')');
    IL.keyed($('tip-retired-l'), JSON.stringify(retired.map((x) => [x.key, x.version])), () => retired.map((x) => {
      const b = el('button', { type: 'button', class: 'btn btn--sm', 'data-unretire': x.key }, 'Bring back');
      b.addEventListener('click', () => IL.act(b, async () => { const r = await IL.api('POST', '/api/tip-setups/' + encodeURIComponent(x.key) + '/unretire', {}); await load(); return r; },
        { ok: 'The ' + x.name + ' is back in the list.' }));
      return el('li', {}, thumb(x, 'xs'), el('span', { class: 'tip-retired__n', text: x.name }), b);
    }));
    if (T.retireMode && retired.length === 0) $('tip-retired').open = false;
    // what the mount does
    const sel = T.sel ? setupByKey(T.sel) : null;
    const pv = sel ? preview(sel.key) : null;
    const what = $('tip-change-what');
    IL.show(what, !!sel);
    IL.keyed(what, JSON.stringify(sel ? [sel.key, pv && pv.p, pv && pv.error] : null), () => {
      if (!sel) return null;
      if (!pv || (!pv.p && !pv.error)) return el('span', { text: 'Reading what a mount does…' });
      if (pv.error) return el('span', { text: 'Could not read what a mount does: ' + pv.error });
      return [el('div', { class: 'tip-what__t', id: 'tip-change-text', text: pv.p.text }),
        (pv.p.warnings || []).map((w) => el('div', { class: 'tip-what__w' }, IL.glyph('need', 'xs'), el('span', { text: w }))),
        pv.p.refusal ? el('div', { class: 'tip-what__w tip-what__w--block' }, IL.glyph('block', 'xs'), el('span', { text: pv.p.refusal })) : null];
    });
    // step 2 for a bent tip: point its bend as in its picture
    const bent = sel && sel.form === 'bent';
    IL.setText($('tip-swap-s2'), !sel ? 'Choose it below.' : bent ? (sel.bendToward ? 'Point its bend toward ' + towardWord(sel.bendToward) + ', as in its picture.' : 'Point its bend the way it pointed before (not recorded yet).') : 'Screw it on straight.');
    IL.keyed($('tip-swap-pic'), sel && sel.mainPhoto ? String(sel.mainPhoto.id) : '', () => (sel && sel.mainPhoto ? thumb(sel, 'sm', true) : null));
    // the selected tip's own actions (not the mounted one): Edit…, Retire (two presses)
    IL.keyed($('tip-sel-acts'), JSON.stringify(sel ? [sel.key, sel.version, sel.mounted] : null), () => {
      if (!sel) return null;
      const edit = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-tip-sel-edit' }, 'Edit the ' + sel.name + '…');
      edit.addEventListener('click', () => openEdit(sel.key));
      if (sel.mounted) return [edit];
      const ret = el('button', { type: 'button', class: 'btn btn--sm btn--danger-ghost', id: 'btn-tip-sel-retire' }, 'Retire the ' + sel.name + '…');
      IL.press2(ret, 'retire the ' + sel.name, async () => { const r = await IL.api('POST', '/api/tip-setups/' + encodeURIComponent(sel.key) + '/retire', {}); T.sel = null; await load(); return r; },
        { ok: 'Retired the ' + sel.name + ': it is kept with its records; Show retired tips brings it back.' });
      return [edit, ret];
    });
    if (T.retireMode && sel && !sel.mounted) { const b = $('btn-tip-sel-retire'); if (b && !b.dataset.flashed) { b.dataset.flashed = '1'; IL.flash(b); } }
    // Mount
    const mount = $('btn-tip-mount');
    if (!IL.isArmed(mount)) IL.setText($('tip-mount-l'), !sel ? 'Mount' : sel.mounted ? 'This is a new needle' : 'Mount the ' + sel.name);
    IL.gate(mount, mountLock(s, sel, pv), $('tip-change-why'));
  }
  /** POST /api/tip-setups/{key}/mount: the needle change (always), then the switch. */
  async function doMount() {
    const sel = setupByKey(T.sel);
    if (!sel) throw new Error('Choose the tip that is on the dispenser now.');
    const from = tipOf(S.status);
    try {
      const r = await IL.api('POST', '/api/tip-setups/' + encodeURIComponent(sel.key) + '/mount', { why: sel.mounted ? 'a new needle (web UI)' : 'mounted in the web UI' });
      afterMount(r, from);
      return r;
    } finally {
      await load();
      IL.planRefreshSoon();
    }
  }
  /** After a mount: the undo band (another tip), the regulator, prime and run ticks lapse (the token), Setup shows the steps. */
  function afterMount(r, from) {
    if (r && !r.same && r.mounted && r.from && r.mounted !== r.from) {
      IL.ssSet(MOUNT_KEY, { mounted: r.mounted, mountedName: r.mountedName, from: r.from, fromName: r.fromName || from.name, atMs: r.atMs, undoUntilMs: r.undoUntilMs });
    }
    if (IL.panel.isPushed('tip-change')) IL.panel.close();
    IL.go('#setup/tip');
    IL.pollNow();
  }
  /** The band after a mount of another tip: "{S} mounted at HH:MM. Undo" while status.dispense.tip.undoUntilMs lasts. */
  function undoBand(s) {
    const t = tipOf(s);
    const until = isNum(t.undoUntilMs) ? t.undoUntilMs : null;
    const on = !!until && IL.serverNow() < until;
    const m = IL.ssJson(MOUNT_KEY, null);
    const sig = on ? JSON.stringify([t.key, until, m && m.mounted === t.key ? m.fromName : null]) : '';
    if (sig === T.undoSig) return;
    T.undoSig = sig;
    if (!on) { IL.msg('tip-undo', null); return; }
    const old = m && m.mounted === t.key ? m.fromName : null;
    const b = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-tip-undo' }, 'Undo');
    IL.press2(b, 'undo: the ' + (old || 'tip before') + ' never left the dispenser', async () => {
      const r = await IL.api('POST', '/api/tip-setups/undo-mount', {});
      IL.ssSet(MOUNT_KEY, null);
      await load();
      IL.planRefreshSoon();
      return r;
    }, { ok: (r) => (r && r.text) || 'Mount undone.' });
    IL.msg('tip-undo', { kind: 'info', alert: false, text: t.name + ' mounted ' + clock(t.mountedAtMs) + '.',
      sub: 'Undo until ' + clock(until) + ' if the ' + (old || 'tip before') + ' never left the dispenser.', actions: [b] });
  }
  /** A setup's picture as a small image (or the outline icon): size xs | sm. */
  function thumb(x, size, big) {
    const p = x && x.mainPhoto;
    if (!p) return el('span', { class: 'tip-thumb tip-thumb--' + (size || 'sm') + ' tip-thumb--none', title: 'No picture of the ' + (x ? x.name : 'tip') + ' yet' }, noPicture(), el('span', { class: 'sr', text: 'no picture' }));
    return el('img', { class: 'tip-thumb tip-thumb--' + (size || 'sm') + (big ? ' tip-thumb--big' : ''), src: p.thumbUrl, alt: p.caption || 'Picture of the ' + x.name, loading: 'lazy' });
  }

  // ================================================================================== Add a tip / Edit (#dlg-tip-add)
  /** The needles on hand (§22.7): chart values, OD "from the chart"; a preset fills the form and the name. */
  const PRESETS = (() => {
    const out = [{ id: '22g-bent-taper', group: 'ADL', name: '22G bent taper', tipModel: 'ADL AD22TTA-B', gauge: 22, idMm: 0.41, odMm: 0.72, form: 'bent', bendDeg: 30, taper: 'tapered-plastic', lengthMm: null }];
    for (const [g, id, od] of [[14, 1.54, 1.83], [18, 0.84, 1.27], [22, 0.41, 0.72]]) {
      out.push({ id: g + 'g-straight-taper', group: 'DSKIT1 straight taper', name: g + 'G straight taper', tipModel: 'DSKIT1 ' + g + 'G taper', gauge: g, idMm: id, odMm: od, form: 'straight', taper: 'tapered-plastic', lengthMm: null });
    }
    for (const [g, id, od] of [[14, 1.54, 1.83], [18, 0.84, 1.27], [21, 0.51, 0.82], [23, 0.33, 0.64], [25, 0.25, 0.51], [27, 0.20, 0.41], [30, 0.15, 0.31]]) {
      out.push({ id: g + 'g-straight', group: 'DSKIT1 straight, 0.5 in steel', name: g + 'G straight', tipModel: 'DSKIT1 ' + g + 'G', gauge: g, idMm: id, odMm: od, form: 'straight', taper: 'straight-steel', lengthMm: 12.7 });
    }
    for (const [g, id, od] of [[14, 1.54, 1.83], [15, 1.37, 1.65], [18, 0.84, 1.27], [20, 0.60, 0.91], [21, 0.51, 0.82], [22, 0.41, 0.72], [25, 0.25, 0.51], [27, 0.20, 0.41], [30, 0.15, 0.31]]) {
      out.push({ id: g + 'g-bent45', group: 'SA7529 bent 45°, 0.5 in steel', name: g + 'G bent 45°', tipModel: 'SA7529 ' + g + 'G', gauge: g, idMm: id, odMm: od, form: 'bent', bendDeg: 45, taper: 'straight-steel', lengthMm: 12.7 });
    }
    out.push({ id: '19g-footed', group: 'ITW', name: '19G footed', tipModel: 'ITW 15898', gauge: 19, idMm: 0.69, odMm: 1.07, form: 'straight', taper: 'unknown', lengthMm: null,
      notes: 'footed: the foot touches the board, the gap is not the engine\'s' });
    return out;
  })();
  const IDENTITY = ['dispenser', 'gauge', 'form', 'taper'];
  const CORRECTION = ['tipModel', 'idMm', 'bendDeg', 'lengthMm'];
  /** Opens the dialog to add a tip (opts.fromChange: select it in Change tip afterwards). */
  function openAdd(opts) { openTipDialog(null, opts || {}); }
  function openEdit(key) {
    const x = setupByKey(key);
    if (!x) { load().then(() => { if (setupByKey(key)) openTipDialog(setupByKey(key), {}); }); return; }
    openTipDialog(x, {});
  }
  function openTipDialog(x, opts) {
    if (T.dlg && !T.dlg.closed) T.dlg.close('replace');
    const edit = !!x;
    const D = { x, opts, file: null, url: null };
    const inp = (id, o) => el('input', Object.assign({ class: 'input', id, type: 'text', autocomplete: 'off', spellcheck: 'false' }, o || {}));
    const num = (id, o) => inp(id, Object.assign({ inputmode: 'decimal' }, o || {}));
    const sel = (id, opts2) => el('select', { class: 'input', id }, opts2.map(([v, t]) => el('option', { value: v, text: t })));
    const fld = (id, label, unit, control, help, cls) => el('div', { class: 'field' + (cls ? ' ' + cls : ''), id: id + '-wrap' },
      el('label', { class: 'field__l', for: id }, el('span', { text: label }), unit ? el('span', { class: 'unit', text: unit }) : el('span')),
      control, el('div', { class: 'field__err', id: id + '-err', hidden: true, role: 'status' }), help ? el('div', { class: 'field__h', text: help }) : null);
    const groups = [];
    for (const p of PRESETS) { let g = groups.find((k) => k.label === p.group); if (!g) groups.push(g = { label: p.group, items: [] }); g.items.push(p); }
    const preset = el('select', { class: 'input', id: 'ta-preset' }, el('option', { value: '', text: edit ? '—' : 'Choose the needle you have…' }),
      groups.map((g) => el('optgroup', { label: g.label }, g.items.map((p) => el('option', { value: p.id, text: p.name + (p.tipModel ? ' (' + p.tipModel + ')' : '') })))),
      el('option', { value: 'other', text: 'Other…' }));
    const setups = (T.data ? T.data.setups || [] : []).filter((k) => !k.retired && k.profile);
    const profileFrom = sel('ta-profile-from', setups.map((k) => [k.key, 'the ' + k.name + '’s: ' + k.profile.name + (k.mounted ? ' (on now)' : '')]).concat([['default', 'the defaults (18 psi half turn)']]));
    const photoImg = el('img', { id: 'ta-photo-img', alt: 'The picture to add', hidden: true });
    const photoName = el('span', { class: 'muted', id: 'ta-photo-name' });
    const ch = chooser({ id: 'ta-photo', inputId: 'ta-photo-file', label: 'Add a photo…', cls: 'btn btn--sm', onFile: (f) => setPhoto(f) });
    const drop = dropZone({ id: 'ta-drop', onFile: (f) => setPhoto(f), text: 'Drop a picture of the tip here' });
    function setPhoto(f) {
      D.file = f;
      if (D.url) URL.revokeObjectURL(D.url);
      D.url = URL.createObjectURL(f);
      photoImg.src = D.url;
      photoImg.hidden = false;
      photoImg.onerror = () => { photoImg.hidden = true; IL.setText(photoName, HEIC); };
      IL.setText(photoName, f.name);
    }
    const note = el('div', { class: 'notebox notebox--info ta-note', id: 'ta-note', hidden: true });
    const err = el('div', { id: 'ta-error', 'data-refusals': '' });
    const body = [
      edit ? null : fld('ta-preset', 'Start from', 'fills the form and the name', preset, 'The needles on hand, with the chart’s bore and outer diameter.'),
      note,
      el('div', { class: 'grid2 ta-grid' },
        fld('ta-name', 'Name', '1 to 40 characters', inp('ta-name', { maxlength: '40', placeholder: 'e.g. 25G straight' }), 'The words every text uses: “not dispensable with the 25G straight”.'),
        fld('ta-dispenser', 'Dispenser', null, inp('ta-dispenser', { maxlength: '60', value: 'Vieweg DV-5005' })),
        fld('ta-model', 'Tip model', 'part number', inp('ta-model', { maxlength: '60', placeholder: 'e.g. DSKIT1 25G' })),
        fld('ta-gauge', 'Gauge', '10 to 34', num('ta-gauge', { inputmode: 'numeric', placeholder: 'e.g. 25' })),
        fld('ta-id', 'Bore at the end (ID)', 'mm', num('ta-id', { placeholder: '0.05 to 2.0' })),
        fld('ta-od', 'Outer diameter (OD)', 'mm', num('ta-od', { placeholder: 'above the ID, at most 3.0' })),
        fld('ta-od-src', 'OD from', null, sel('ta-od-src', [['assumed', 'the chart'], ['measured', 'a measurement']])),
        fld('ta-form', 'Form', null, sel('ta-form', [['straight', 'straight'], ['bent', 'bent']])),
        fld('ta-bend', 'Bend', '1 to 90°', num('ta-bend', { placeholder: 'e.g. 45' }), null, 'ta-bent'),
        fld('ta-toward', 'The bend points toward', 'when it is on', sel('ta-toward', [['', 'not recorded'], ['+x', '+X'], ['-x', MINUS + 'X'], ['+y', '+Y'], ['-y', MINUS + 'Y']]),
          'The swap step says it, and the landing check searches less when it is known.', 'ta-bent'),
        fld('ta-taper', 'Taper', null, sel('ta-taper', [['tapered-plastic', 'tapered plastic'], ['straight-steel', 'straight steel'], ['unknown', 'not known']])),
        fld('ta-length', 'Tube length', 'mm from the hub', num('ta-length', { placeholder: 'not recorded' })),
        fld('ta-mindot', 'Smallest dot', 'it takes reliably', sel('ta-mindot', [['400', 'quarter turn (400 steps)'], ['800', 'half turn (800)'], ['1600', 'one turn (1600)']])),
        edit ? null : fld('ta-profile-from', 'Start its dispense profile from', null, profileFrom, 'A copy: nothing measured with this tip yet.')),
      fld('ta-notes', 'Notes', 'at most 500 characters', el('textarea', { class: 'input', id: 'ta-notes', rows: '2', maxlength: '500' })),
      el('div', { class: 'ta-photo' }, el('div', { class: 'field__l' }, el('span', { text: 'Picture' }), el('span', { class: 'unit', text: 'optional' })),
        el('div', { class: 'ta-photo__row' }, photoImg, el('div', { class: 'ta-photo__acts' }, el('div', { class: 'btn-row' }, ch.btn, ch.input), photoName,
          el('div', { class: 'reason', id: 'ta-photo-why', hidden: true }))), drop),
      el('details', { class: 'disclose', id: 'ta-more' }, el('summary', { text: 'More' }),
        fld('ta-key', 'Key', 'never changes', inp('ta-key', { maxlength: '48', placeholder: 'made from the gauge and form, e.g. 25g-straight', pattern: '[a-z0-9][a-z0-9-]*' }),
          'The tip’s name on every record. Leave it empty unless you need a particular one.')),
      err,
    ];
    const save = el('button', { type: 'button', class: 'btn btn--primary', id: 'ta-save' }, el('span', { class: 'lbl', text: edit ? 'Save' : 'Add the tip' }));
    const saveMount = el('button', { type: 'button', class: 'btn', id: 'ta-save-mount' }, 'Add and mount');
    const cancel = el('button', { type: 'button', class: 'btn', id: 'ta-cancel' }, 'Cancel');
    const h = IL.dialog.open({ id: 'dlg-tip-add', eyebrow: 'Dispenser & tip', title: edit ? 'Edit the ' + x.name : 'Add a tip', body,
      foot: [el('div', { class: 'dialog__sum', id: 'ta-sum' }), cancel, edit ? null : saveMount, save], initialFocus: edit ? '#ta-name' : '#ta-preset',
      onClose: () => { if (D.url) URL.revokeObjectURL(D.url); T.dlg = null; IL.render(); } });
    T.dlg = h;
    D.h = h;
    h.el.classList.add('dialog--tip');
    h.body.setAttribute('data-enter-submits', 'ta-save');
    cancel.addEventListener('click', () => h.close('cancel'));
    const set = (id, v) => { const n = $(id); if (n) n.value = v === null || v === undefined ? '' : String(v); };
    if (edit) {
      set('ta-name', x.name); set('ta-dispenser', x.dispenser); set('ta-model', x.tipModel); set('ta-gauge', x.gauge); set('ta-id', x.idMm); set('ta-od', x.odMm);
      set('ta-od-src', x.odSource || 'assumed'); set('ta-form', x.form === 'unknown' ? 'straight' : x.form); set('ta-bend', x.bendDeg); set('ta-toward', x.bendToward || '');
      set('ta-taper', x.taper); set('ta-length', x.lengthMm); set('ta-mindot', x.minDotSteps); set('ta-notes', x.notes); set('ta-key', x.key);
      $('ta-key').readOnly = true;
      // a setup with records: another dispenser, gauge, form or taper is another needle (add a tip instead)
      if (x.editable && x.editable.identity === false) {
        for (const id of ['ta-dispenser', 'ta-gauge', 'ta-form', 'ta-taper']) { $(id).disabled = true; $(id).title = 'A different needle is a new tip: Add a tip…'; }
        note.replaceChildren(IL.glyph('info', 'xs'), el('span', { text: 'A different needle is a new tip: Add a tip…' }));
        IL.show(note, true);
      }
    } else {
      const mounted = mountedSetup();
      if (mounted) profileFrom.value = mounted.key;
      preset.addEventListener('change', () => {
        const p = PRESETS.find((k) => k.id === preset.value);
        if (!p) { if (preset.value === 'other') for (const id of ['ta-name', 'ta-model', 'ta-gauge', 'ta-id', 'ta-od', 'ta-bend', 'ta-length']) set(id, ''); formChanged(); return; }
        set('ta-name', p.name); set('ta-model', p.tipModel); set('ta-gauge', p.gauge); set('ta-id', p.idMm); set('ta-od', p.odMm); set('ta-od-src', 'assumed');
        set('ta-form', p.form); set('ta-bend', p.bendDeg); set('ta-taper', p.taper); set('ta-length', p.lengthMm); set('ta-notes', p.notes || '');
        formChanged();
      });
      IL.press2(saveMount, 'add and mount: it is on the dispenser now', () => saveTip(D, true), { ok: (r) => addedText(r, true), refusalIn: err });
    }
    function formChanged() {
      const bent = $('ta-form').value === 'bent';
      for (const n of h.el.querySelectorAll('.ta-bent')) IL.show(n, bent);
      if (!bent) { set('ta-bend', ''); set('ta-toward', ''); }
      if (edit) {
        const corr = correctionChanges(D).length > 0;
        IL.show(note, corr || (x.editable && x.editable.identity === false));
        if (corr) note.replaceChildren(IL.glyph('need', 'xs'), el('span', { text: 'Correcting the ' + x.name + '’s details? A different needle is a new tip: Add a tip…' }));
        else if (x.editable && x.editable.identity === false) note.replaceChildren(IL.glyph('info', 'xs'), el('span', { text: 'A different needle is a new tip: Add a tip…' }));
        IL.setClass(note, 'notebox ' + (corr ? 'notebox--need' : 'notebox--info') + ' ta-note');
        if (!IL.isArmed(save)) IL.setText(save.querySelector('.lbl'), corr ? 'Correct the details' : 'Save');
      }
      const v = values();
      IL.setText($('ta-sum'), v.name ? (edit ? 'The ' + x.name + ' · version ' + x.version + ' → ' + (x.version + 1) : 'Adds the ' + v.name + ': no pad profiles yet') : '');
    }
    for (const n of h.el.querySelectorAll('.input')) n.addEventListener(n.tagName === 'SELECT' ? 'change' : 'input', () => { if (n.id !== 'ta-preset') formChanged(); });
    formChanged();
    // Save: a correction of a setup with records takes a second press ("Correct the details"), else one press
    IL.press2(save, () => (edit && correctionChanges(D).length ? 'correct the details' : null), () => saveTip(D, false), { ok: (r) => (edit ? 'Saved the ' + (r.name || x.name) + '.' : addedText(r, false)), refusalIn: err });
    D.gate = () => {
      const lock = chooserLock(S.status);
      IL.gate(ch.btn, lock, $('ta-photo-why'));
    };
    D.gate();
    T.dlgGate = D.gate;
    return h;
  }
  /** The form's values in the API's words. */
  function values() {
    const str = (id) => { const n = $(id); const v = n ? String(n.value).trim() : ''; return v === '' ? null : v; };
    const num = (id) => { const v = str(id); return v === null ? null : Number(v.replace(/−/g, '-')); };
    const form = str('ta-form');
    return { name: str('ta-name'), dispenser: str('ta-dispenser'), tipModel: str('ta-model'), gauge: num('ta-gauge'), idMm: num('ta-id'), odMm: num('ta-od'),
      odSource: str('ta-od-src'), form, bendDeg: form === 'bent' ? num('ta-bend') : null, bendToward: form === 'bent' ? str('ta-toward') : null,
      taper: str('ta-taper'), lengthMm: num('ta-length'), minDotSteps: num('ta-mindot'), notes: str('ta-notes') };
  }
  /** Field checks as the engine words them (§22.1): {id: text}. */
  function problems(v) {
    const e = {};
    if (!v.name || v.name.length > 40) e['ta-name'] = 'The name must be 1 to 40 characters.';
    if (!v.dispenser) e['ta-dispenser'] = 'Name the dispenser.';
    if (v.gauge !== null && !(Number.isInteger(v.gauge) && v.gauge >= 10 && v.gauge <= 34)) e['ta-gauge'] = 'A whole number, 10 to 34.';
    if (v.idMm === null) e['ta-id'] = 'Enter the bore (ID) in mm: a tip without it cannot be mounted.';
    else if (!(v.idMm >= 0.05 && v.idMm <= 2.0)) e['ta-id'] = 'The bore (ID) must be 0.05 to 2.0 mm.';
    if (v.odMm === null) e['ta-od'] = 'Enter the outer diameter (OD) in mm.';
    else if (!(v.odMm <= 3.0 && (v.idMm === null || v.odMm > v.idMm))) e['ta-od'] = 'Larger than the ID and at most 3.0 mm.';
    if (v.form === 'bent' && v.bendDeg !== null && !(v.bendDeg >= 1 && v.bendDeg <= 90)) e['ta-bend'] = '1 to 90°.';
    if (v.lengthMm !== null && !(v.lengthMm >= 3 && v.lengthMm <= 60)) e['ta-length'] = '3 to 60 mm, or empty.';
    const key = $('ta-key') && !$('ta-key').readOnly ? String($('ta-key').value).trim() : '';
    if (key && !/^[a-z0-9][a-z0-9-]{0,47}$/.test(key)) e['ta-key'] = 'Lower-case letters, digits and - only.';
    if (key === 'undo-mount') e['ta-key'] = '“undo-mount” is taken by the web page: choose another.';
    return e;
  }
  /** The correction fields an edit changes (tip model, ID, bend angle, length): "ID 0.41 → 0.42 mm". */
  function correctionChanges(D) {
    if (!D.x || !(D.x.editable && D.x.editable.identity === false)) return [];
    const v = values();
    return CORRECTION.filter((k) => (v[k] === null ? null : v[k]) !== (D.x[k] === undefined ? null : D.x[k]) && !(isNum(v[k]) && isNum(D.x[k]) && Math.abs(v[k] - D.x[k]) < 1e-9));
  }
  function markProblems(errs) {
    for (const id of ['ta-name', 'ta-dispenser', 'ta-gauge', 'ta-id', 'ta-od', 'ta-bend', 'ta-length', 'ta-key']) {
      const n = $(id), m = $(id + '-err');
      if (n) n.classList.toggle('input--invalid', !!errs[id]);
      if (m) { IL.setText(m, errs[id] || ''); IL.show(m, !!errs[id]); }
    }
    if (errs['ta-key']) $('ta-more').open = true;
  }
  /** POST (add, add and mount) or PUT (edit; correction: true when the second press asked for it); then the picture. */
  async function saveTip(D, mount) {
    const v = values();
    const errs = problems(v);
    markProblems(errs);
    if (Object.keys(errs).length) throw new IL.ApiError(400, { error: 'bad-request', reason: 'Not saved: ' + IL.plural(Object.keys(errs).length, 'field') + ' to fix.' }, 'Not saved.');
    let r, key, name;
    if (D.x) {
      const body = {};
      for (const [k, val] of Object.entries(v)) {
        if (D.x.editable && D.x.editable.identity === false && IDENTITY.includes(k)) continue;
        const was = D.x[k] === undefined ? null : D.x[k];
        if (val !== was && !(isNum(val) && isNum(was) && Math.abs(val - was) < 1e-9)) body[k] = val;
      }
      if (correctionChanges(D).length) body.correction = true;
      r = Object.keys(body).length ? await IL.api('PUT', '/api/tip-setups/' + encodeURIComponent(D.x.key), body) : D.x;
      key = D.x.key;
      name = r.name;
    } else {
      const body = Object.assign({}, v, { profileFrom: ($('ta-profile-from') || {}).value || undefined });
      const k = String(($('ta-key') || {}).value || '').trim();
      if (k) body.key = k;
      if (mount) { body.mount = true; body.why = 'added and mounted in the web UI'; }
      const from = tipOf(S.status);
      r = await IL.api('POST', '/api/tip-setups', body);
      key = r.setup.key;
      name = r.setup.name;
      if (mount && r.mount && !r.mount.refused) afterMountQuiet(r.mount, from);
    }
    // the picture after the setup exists (kind setup); if it fails the tip stays added
    let photoErr = null;
    if (D.file) {
      try { await send(D.file, { kind: 'setup', setup: key, caption: 'Picture of the ' + name }); }
      catch (e) { photoErr = IL.errorText(e); }
    }
    await load();
    const mounted = mount && r.mount && !r.mount.refused;
    if (!photoErr) D.h.close('saved');
    if (!D.x && D.opts.fromChange && !mounted) openChange({ select: key });
    if (mounted) { IL.go('#setup/tip'); IL.planRefreshSoon(); }
    if (mount && r.mount && r.mount.refused) IL.toast('Added, not mounted: ' + r.mount.reason, 'need');
    if (photoErr) {
      // the tip is added (and mounted when asked); the dialog stays to say its picture is not (add it from the card)
      const err = $('ta-error');
      if (err) err.replaceChildren(el('div', { class: 'notebox notebox--need', role: 'alert' }, (D.x ? 'Saved the ' + name : 'The tip was added') + ' without its picture: ' + photoErr));
      for (const id of ['ta-save', 'ta-save-mount']) IL.show($(id), false);
      IL.setText($('ta-cancel'), 'Close');
    }
    return r;
  }
  /** Add and mount: the same bookkeeping as Mount, without closing a panel that is not open. */
  function afterMountQuiet(m, from) {
    if (m && m.mounted && m.from && m.mounted !== m.from) {
      IL.ssSet(MOUNT_KEY, { mounted: m.mounted, mountedName: m.mountedName, from: m.from, fromName: m.fromName || from.name, atMs: m.atMs, undoUntilMs: m.undoUntilMs });
    }
    if (IL.panel.isPushed('tip-change')) IL.panel.close();
    IL.pollNow();
  }
  function addedText(r, mount) {
    if (!r || !r.setup) return 'Added.';
    if (mount && r.mount && !r.mount.refused) return r.mount.text || r.setup.name + ' mounted.';
    return 'Added the ' + r.setup.name + '. It has no pad profiles yet.';
  }

  // ================================================================================== fix-its
  IL.fix('tip-change', { run: async () => openChange({}) });
  IL.fix('tip-again', { run: async () => openChange({ again: true }) });
  IL.fix('tip-edit', { run: async () => { await load(); const m = mountedSetup(); if (m) openEdit(m.key); } });
  IL.fix('tip-add', { run: async () => openAdd({}) });
  /** Check where it lands: the pad-cal view before start with measure "landing" (≈ 1:00). */
  IL.fix('landing-check', {
    gate: (s) => IL.lockReason(s, { connected: true, engine: true, homes: true }),
    run: async () => { if (IL.padProfile && IL.padProfile.openCal) IL.padProfile.openCal({ measure: 'landing' }); },
  });
  /** Measure its dots: the pad-cal view before start with measure "tip" (≈ 8 min, two coupons to weigh). */
  IL.fix('measure-tip', {
    gate: (s) => IL.lockReason(s, { connected: true, engine: true, homes: true }),
    run: async () => { if (IL.padProfile && IL.padProfile.openCal) IL.padProfile.openCal({ measure: 'tip' }); },
  });
  /** Keep where it lands (a straight tip, screwed on straight): only when the engine would allow it. */
  IL.fix('landing-keep', {
    press2: (s) => 'keep where the ' + nameOf(s) + ' lands',
    gate: (s) => { const l = needleOf(s).landing || {}; return IL.lockReason(s, { pressureOff: false }) || (l.keepable ? null : l.keepWhy || 'Not for this tip: check where it lands.'); },
    run: async (btn, arg, s) => {
      const r = await IL.api('POST', '/api/needle/landing', { keep: true, needleChangedAtMs: token(s) });
      IL.planRefreshSoon();
      return r;
    },
    ok: 'Kept where it lands: dispensing may go on.',
  });

  IL.tips = { token: () => token(S.status), tokenOf: token, name: nameOf, data: () => T.data, load, setup: setupByKey, mountedSetup, cellView, buildCard, renderCard,
    nextSteps, changeOpen, step, openChange, openAdd, openEdit, takePicture, pictureLock, chooserLock, pictureOk, thumb, tipWords, boreWords, dotsWords, typesWords,
    neverOn: () => neverOn(S.status), noPicture, gateDialog: () => { if (T.dlg && !T.dlg.closed && T.dlgGate) T.dlgGate(); } };
  IL.photos = { normalize, upload, send, dropZone, chooser, strip, view, HEIC, DROP_HINT };
  IL.on('status', () => { if (T.dlg && !T.dlg.closed && T.dlgGate) T.dlgGate(); });
})();
