/* padinspect.js: the pad inspector, panel view `pad` (spec docs/instancelabs/BOARD_IMPORT_DESIGN.md §5.7). A click on a pad
   or a part (or #btn-pad-inspect) opens a zoomed drawing of the part: its pads (the paste openings), its dots at their
   real size in path order, the pad type and the rule that chose it, the stencil-equivalent amount, the flags and why a
   pad is blocked, and the way to edit the pad type. Everything is shown here: hover tooltips only repeat it.

   API:
     IL.padInspect.open({padId} | {ref}, {bv})   bv: the view to mark the part on while the inspector is open (hero)
     IL.padInspect.current()                     {ref, padId} or null
     IL.views.pad                                the panel view (IL.panel.show('pad', IL.views.pad))
   Reads the plan shown (IL.S.plan); its board drawing comes from IL.boardart.get(plan.board.art.id). */
'use strict';
(function () {
  const el = IL.el;
  const $ = (id) => document.getElementById(id);
  const M = { sel: null, bv: null, view: null, planId: null, art: null, artId: null, types: null, typesAt: 0 };
  const FLAG_TEXT = {
    NO_PAD_TYPE: (p) => 'No pad type matches the package ' + (p.packageId || '') + ': add one, or map the package to a pad type.',
    STATUS_BELOW_MIN: (p, t) => 'Pad type ' + typeName(p, t) + ' is not characterized yet: dispensing needs a characterized pad type.',
    NO_GEOMETRY: () => 'The pad type has no pad size and the package has no pads.',
  };
  const WHY = { fiducial: 'Fiducial for Register board: no paste.', art: 'Through-hole or drawn-only part: not dispensed.',
    dnp: 'Do not populate: not placed, so no paste.', pasteOnly: 'Paste only: no part is placed here.',
    bottom: 'Bottom-side part: only the top is dispensed.' };

  const plan = () => IL.S.plan;
  const short = (fam) => String(fam || '').replace(/\s*\(.*\)\s*$/, '');
  function typeOf(key) {
    if (!key || !M.types) return null;
    for (const fam of M.types.families || []) for (const t of fam.types || []) if (t.typeKey === key) return t;
    return null;
  }
  /** "Chip 0603" (the family without its note and the name), else the key. */
  function typeName(p, t) { return t ? (short(t.family) + ' ' + t.name).trim() : (p.typeKey || 'none'); }
  function loadTypes() {
    if (M.typesLoading || (M.types && Date.now() - M.typesAt < 30000)) return;
    M.typesLoading = true;
    IL.api('GET', '/api/pad-types').then((r) => { M.types = r; M.typesAt = Date.now(); },
      () => { M.typesAt = Date.now(); }).finally(() => { M.typesLoading = false; IL.render(); });
  }
  function loadArt(p) {
    const id = p && p.board && p.board.art ? p.board.art.id : null;
    if (id === M.artId) return;
    M.artId = id;
    M.art = null;
    if (!id) return;
    IL.boardart.get(id).then((a) => { if (M.artId === id) { M.art = a; IL.render(); } }, () => { /* drawn without the board */ });
  }

  /** The plan's parts in path order (by their first dot; parts without dots after them, in pad order). */
  function partsOf(p) {
    const parts = new Map();
    for (const pad of p.pads || []) {
      const ref = pad.placementId || String(pad.padId).split('.')[0];
      if (!parts.has(ref)) parts.set(ref, { ref, pads: [], first: Infinity, packageId: pad.packageId, partId: pad.partId });
      parts.get(ref).pads.push(pad);
    }
    const padRef = new Map();
    for (const [ref, part] of parts) for (const pad of part.pads) padRef.set(pad.padId, ref);
    for (const d of p.dots || []) {
      const part = parts.get(padRef.get(d.padId));
      if (part && d.index < part.first) part.first = d.index;
    }
    const list = Array.from(parts.values());
    list.forEach((x, i) => { x.order = i; });
    return list.sort((a, b) => a.first - b.first || a.order - b.order);
  }
  const artPart = (ref) => (M.art && (M.art.parts || []).find((x) => x.ref === ref)) || null;

  /** A pad type's status in words ("not-started" → "not started"), as Profile › Pad types says it. */
  function statusWord(st) {
    const known = st && IL.pcr && IL.pcr.STATUS && IL.pcr.STATUS[st];
    return known ? known.word : String(st || '').replace(/-/g, ' ');
  }
  /** One sentence: why this pad got its pad type (§5.7). */
  function ruleSentence(pad, t) {
    if (!pad || !pad.typeKey) return 'No pad type matches the package ' + ((pad && pad.packageId) || '') + ': it gets no paste until one is added.';
    const status = statusWord(t ? t.status : pad.typeStatus);
    const tail = ' (pad type ' + typeName(pad, t) + ', v' + (t ? t.version : pad.typeVersion) + (status ? ', ' + status : '') + ').';
    const r = pad.typeRule || null;
    if (!r) return 'Pad type ' + typeName(pad, t) + ', v' + (t ? t.version : pad.typeVersion) + (status ? ', ' + status : '') + '.';
    if (r.sentence) return String(r.sentence).replace(/[.\s]+$/, '') + tail;
    if (r.kind === 'pattern') return 'Chosen because the package name contains “' + found(r) + '”' + tail;
    if (r.kind === 'package-map') return 'Mapped to this package by hand in Profile › Pad types' + tail;
    if (r.kind === 'centre-pad') return 'Centre pad of a QFN/DFN: it sits at the part centre and is at least 1.0 mm²' + tail;
    return 'No pad type matches the package ' + (pad.packageId || '') + ': it gets no paste until one is added.';
  }
  /**
   * The text a package-name pattern found, without the separators the pattern takes in with it: "(^|[^0-9])0805([^0-9]|$)"
   * finds "_0805_" in R_0805_2012Metric (Matcher.group()), and the operator reads "0805".
   */
  function found(r) {
    const m = String(r.matched || '').replace(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/g, '');
    return m || r.matched || r.detail || '';
  }
  function ruleDetail(pad, t) {
    const r = pad && pad.typeRule;
    if (r && r.kind === 'pattern') return ['Package name pattern', r.detail || (t && t.packagePattern) || '—', 'Text it found', r.matched || '—'];
    if (r && r.kind === 'package-map') return ['Mapped package', r.detail || pad.packageId || '—'];
    if (r && r.kind === 'centre-pad') return ['Rule', 'centre pad of a QFN/DFN (' + (r.detail || 'qfn-dfn-perimeter') + ')'];
    if (t && t.packagePattern) return ['Package name pattern of the pad type', t.packagePattern];
    return null;
  }
  const opening = (p) => IL.fmt(p.w, 2) + ' × ' + IL.fmt(p.h, 2) + ' mm' + (Number.isFinite(p.roundness) ? ', corners ' + IL.fmt(p.roundness, 0) + ' %' : '');
  function dotsWhy(p, t) {
    const n = p.dotCount || 0;
    if (!n) return p.blocked ? 'none: the pad cannot be dispensed' : 'none';
    if (n === 1) return 'one dot';
    const lim = t && t.rule && t.rule.largestSingleDotPadMm2;
    return lim ? 'pad over ' + IL.fmt(lim, 2) + ' mm²: ' + n + ' dots in a grid' : n + ' dots in a grid';
  }

  /**
   * Text with a line-break chance after each "_": a long KiCad name (VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm) wraps between
   * its fields, not inside "3.254mm"; <wbr> adds nothing to the copied text.
   */
  function setBreakable(node, text) {
    if (!node || node.dataset.t === text) return;
    node.dataset.t = text;
    node.replaceChildren(...text.split(/(?<=_)/).flatMap((x, i) => (i ? [el('wbr'), x] : [x])));
  }

  /** Opens the inspector on a pad (or a part: its first pad in path order; drawn-only parts have none). */
  function open(target, opts) {
    const p = plan();
    const o = opts || {};
    if (!p) return;
    let ref = target && target.ref, padId = target && target.padId;
    if (padId) {
      const pad = (p.pads || []).find((x) => x.padId === padId);
      if (!pad) return;
      ref = pad.placementId || String(padId).split('.')[0];
    } else if (ref) {
      const part = partsOf(p).find((x) => x.ref === ref);
      padId = part ? firstPad(p, part) : null;
    } else return;
    if (o.bv !== undefined) M.bv = o.bv;
    M.sel = { ref, padId };
    markHero();
    if (IL.panel.isPushed('pad')) { IL.render(); return; }
    IL.panel.show('pad', IL.views.pad);
  }
  function firstPad(p, part) {
    let best = null, bi = Infinity;
    const ids = new Set(part.pads.map((x) => x.padId));
    for (const d of p.dots || []) if (ids.has(d.padId) && d.index < bi) { bi = d.index; best = d.padId; }
    return best || (part.pads[0] && part.pads[0].padId) || null;
  }
  function markHero() {
    if (M.bv && !M.bv.destroyed) M.bv.select(M.sel ? { padIds: M.sel.padId ? [M.sel.padId] : [], refs: [M.sel.ref] } : null);
  }
  function step(dir) {
    const p = plan();
    if (!p || !M.sel) return;
    const list = partsOf(p);
    const i = list.findIndex((x) => x.ref === M.sel.ref);
    // from a drawn-only part (not in the plan's list): next is the first part, previous the last
    const next = i < 0 ? list[dir > 0 ? 0 : list.length - 1] : list[(i + dir + list.length) % list.length];
    if (next) open({ ref: next.ref });
  }

  IL.view('pad', {
    title: () => (M.sel ? (M.sel.padId ? 'Pad ' + M.sel.padId : 'Part ' + M.sel.ref) : 'Pad'),
    render(parts, s, first) {
      const p = plan();
      // no plan, or a new plan without this pad (or this part), closes the inspector
      const changed = !!p && !!M.planId && M.planId !== p.planId;
      const gone = !p || !M.sel || (M.sel.padId ? !(p.pads || []).some((x) => x.padId === M.sel.padId)
        : changed && !(p.pads || []).some((x) => x.placementId === M.sel.ref) && !artPart(M.sel.ref));
      if (gone) {
        IL.panel.close();
        return;
      }
      loadTypes();
      loadArt(p);
      if (first) build(parts);
      if (M.planId !== p.planId) {
        M.planId = p.planId;
        M.view.setPlan(p, {});
      }
      M.view.setArt(M.art);
      fill(p);
    },
    unmount() {
      if (M.view) M.view.destroy();
      M.view = null;
      M.planId = null;
      const bv = M.bv;
      M.sel = null;
      if (bv && !bv.destroyed) bv.select(null);
      M.bv = null;
    },
  });

  function build(parts) {
    const prev = el('button', { type: 'button', class: 'btn btn--sm btn--icon', id: 'pad-insp-prev', 'aria-label': 'Previous part in path order', title: 'Previous part in path order' },
      IL.icon('i-back'));
    const next = el('button', { type: 'button', class: 'btn btn--sm btn--icon', id: 'pad-insp-next', 'aria-label': 'Next part in path order', title: 'Next part in path order' },
      IL.icon('i-chev'));
    prev.addEventListener('click', () => step(-1));
    next.addEventListener('click', () => step(1));
    const host = el('div', { id: 'pad-insp-svg' });
    const cam = el('button', { type: 'button', class: 'btn btn--sm', id: 'pad-insp-cam' }, IL.icon('i-camera'), 'Top camera');
    cam.addEventListener('click', () => {
      const id = M.sel && (M.sel.padId || M.sel.ref);
      IL.panel.show('camera', IL.views.camera, { title: 'Top camera · pad ' + id });
    });
    const edit = el('button', { type: 'button', class: 'btn btn--sm', id: 'pad-insp-edit' });
    edit.addEventListener('click', () => { if (edit.dataset.href) IL.go(edit.dataset.href); });
    parts.body.append(
      el('div', { class: 'pad-insp-head' },
        el('div', { class: 'pad-insp-head__t', id: 'pad-insp-part' }, el('div', { class: 'pad-insp-head__ref' }), el('div', { class: 'pad-insp-head__s' })),
        prev, next),
      host,
      el('p', { class: 'pad-insp-why', id: 'pad-insp-why', hidden: true }),
      el('p', { class: 'pad-insp-types', id: 'pad-insp-types', hidden: true }),
      el('table', { class: 'tbl', id: 'pad-insp-pads' },
        el('thead', {}, el('tr', {}, el('th', { text: 'Pad' }), el('th', { title: 'Paste opening, width × height' }, IL.unitText('Opening (mm)')),
          el('th', { class: 'pad-insp-type', text: 'Pad type' }), el('th', { class: 'r', text: 'Dots' }), el('th', { text: 'State' }))),
        el('tbody')),
      el('div', { id: 'pad-insp-rule' }),
      el('dl', { class: 'kv kv--wrap', id: 'pad-insp-kv' }),
      el('div', { id: 'pad-insp-flags' }),
      el('div', { id: 'pad-insp-profile', hidden: true }),
      IL.sectionK('Dots of this pad'),
      el('table', { class: 'tbl', id: 'pad-insp-dots' },
        el('thead', {}, el('tr', {}, el('th', { class: 'r', text: '#' }), el('th', { class: 'r' }, IL.unitText('X (mm)')),
          el('th', { class: 'r' }, IL.unitText('Y (mm)')), el('th', { class: 'r', title: 'Dispense Z' }, IL.unitText('Z (mm)')),
          el('th', { class: 'r', title: 'Time from the program start (estimate)' }, 't'))),
        el('tbody')));
    parts.foot.append(el('div', { class: 'btn-row' }, edit, cam));
    if (M.view) M.view.destroy();   // a panel reset rebuilds the parts without an unmount
    M.view = IL.boardview.create(host, { lite: true, aria: 'The part and its dots, zoomed',
      onPad: (id) => open({ padId: id }),
      onPart: (ref) => open({ ref }) });
    M.view.picture('full');
    M.view.layers(['path']);   // the path numbers give the order; the path line only crowds a fine-pitch part
    M.planId = null;
  }

  function fill(p) {
    const sel = M.sel;
    const list = partsOf(p);
    const part = list.find((x) => x.ref === sel.ref) || null;
    const ap = artPart(sel.ref);
    const pads = part ? part.pads : [];
    const pad = pads.find((x) => x.padId === sel.padId) || null;
    const t = pad ? typeOf(pad.typeKey) : null;
    const padIds = new Set(pads.map((x) => x.padId));
    const dots = (p.dots || []).filter((d) => padIds.has(d.padId));
    const padDots = dots.filter((d) => pad && d.padId === pad.padId);
    // head: R12, then the package and the part on lines of their own (long KiCad names wrap inside a line, not across both)
    const head = $('pad-insp-part');
    IL.setText(head.firstChild, sel.ref);
    const pkg = (pad && pad.packageId) || (part && part.packageId) || (ap && ap.pkg) || '';
    const partId = (pad && pad.partId) || (ap && ap.pkg && ap.value ? ap.pkg + '-' + ap.value : null);
    setBreakable(head.lastChild, [pkg ? 'package ' + pkg : null, [partId ? 'part ' + partId : null,
      IL.plural(pads.length || (ap ? ap.pads || 0 : 0), 'pad').replace(' ', '\u00a0')].filter(Boolean).join(' · ')].filter(Boolean).join('\n'));
    // the zoomed drawing: the part's box (drawing or pads) and 1 mm around it; its dots numbered in path order
    const v = M.view;
    v.select({ padIds: pad ? [pad.padId] : [], refs: [sel.ref] });
    v.dotLabels(dots.map((d) => d.index));
    const fitKey = p.planId + '|' + sel.ref + '|' + (M.art ? 1 : 0);
    if (v.fitKey !== fitKey) {
      v.fitKey = fitKey;
      v.fit(partBox(p, pads, dots, ap));
    }
    // why a drawn-only part has no pads
    const why = !pads.length && ap ? WHY[ap.kind] || 'Not dispensed.' : null;
    IL.setText($('pad-insp-why'), why);
    IL.show($('pad-insp-why'), !!why);
    // pads table; one pad type for every pad (the usual case) is said once above it instead of in every row
    const keys = new Set(pads.map((x) => x.typeKey || ''));
    const one = pads.length > 1 && keys.size === 1;
    const k0 = pads.length ? pads[0].typeKey : null;
    const all = pads.length === 2 ? 'both pads' : 'all ' + pads.length + ' pads';
    IL.setText($('pad-insp-types'), one ? (k0 ? 'Pad type of ' + all + ': ' + typeName(pads[0], typeOf(k0)) : 'No pad type matches ' + all + '.') : null);
    IL.show($('pad-insp-types'), one);
    $('pad-insp-pads').classList.toggle('pad-insp-pads--one', one);
    const body = $('pad-insp-pads').tBodies[0];
    IL.keyed(body, JSON.stringify([p.planId, sel.ref, sel.padId, !!M.types]), () => pads.map((x) => {
      const tt = typeOf(x.typeKey);
      const tr = el('tr', { 'data-pad': x.padId, 'aria-selected': String(x.padId === sel.padId) },
        el('td', {}, el('button', { type: 'button', class: 'link', text: x.padId, 'aria-label': 'Inspect pad ' + x.padId,
          'data-focus-key': 'pad-' + x.padId })),   // the rebuilt table keeps the keyboard focus on this row (IL.keyed)
        el('td', { text: IL.fmt(x.w, 2) + ' × ' + IL.fmt(x.h, 2), title: opening(x) }),
        el('td', { class: 'pad-insp-type', text: x.typeKey ? (tt ? tt.name : x.typeKey) : 'No pad type' }),   // the full name is below
        el('td', { class: 'r', text: String(x.dotCount || 0) }), el('td', { text: x.blocked ? 'Blocked' : (x.flags && x.flags.length ? 'Check' : 'Planned') }));
      tr.addEventListener('click', () => open({ padId: x.padId }));
      return tr;
    }));
    IL.show($('pad-insp-pads'), pads.length > 0);
    // rule sentence and its details
    const rule = $('pad-insp-rule');
    IL.keyed(rule, JSON.stringify([sel.padId, pad && pad.typeKey, pad && pad.typeRule, t ? [t.version, t.status, t.name] : null]), () => {
      if (!pad) return null;
      const det = ruleDetail(pad, t);
      return [el('div', { text: ruleSentence(pad, t) }), det ? el('details', { class: 'disclose' }, el('summary', { text: 'Rule details' }),
        el('dl', { class: 'kv kv--wrap' }, ...det.map((x, i) => el(i % 2 ? 'dd' : 'dt', { class: i % 2 ? 'mono' : null, text: x })))) : null];
    });
    // key values of the selected pad
    const kv = $('pad-insp-kv');
    IL.show(kv, !!pad);
    if (pad) {
      const zs = padDots.map((d) => d.dispenseZ).filter(Number.isFinite);
      const prof = p.profile || {};
      IL.kv(kv, [
        ['Pad type', pad.typeKey ? typeName(pad, t) + ' · v' + (t ? t.version : pad.typeVersion) + ' · ' + (statusWord(t ? t.status : pad.typeStatus) || '—') : 'none'],
        ['Opening', IL.nb(opening(pad)) + ' · ' + (pad.source === 'FOOTPRINT' ? 'KiCad paste opening' : pad.source === 'PAD_TYPE' ? 'pad type default' : (pad.source || '—'))],
        ['Area', Number.isFinite(pad.areaMm2) ? IL.nb(IL.fmt(pad.areaMm2, 3) + ' mm²') : '—'],
        ['Amount', IL.padProfile && IL.padProfile.amountText(pad) ? IL.padProfile.amountText(pad) : Number.isFinite(pad.targetVolumeMm3) ? 'A ' + IL.fmt(pad.tMm, 2) + ' mm stencil would print ' + IL.fmt(pad.targetVolumeMm3, 3) + ' mm³ ('
          + IL.fmt(pad.targetMassMg, 2) + ' mg) here.' : '—', 'Information only: the dose is the dispense profile\'s turn per dot'],
        ['Dots', dotsWhy(pad, t)],
        ['Turn per dot', IL.turnLabel(prof.netStepsPerDot, prof.stepsPerRev) + (Number.isFinite(pad.rotationPerDotDeg) ? ' (' + IL.fmtDeg(pad.rotationPerDotDeg, 1) + ')' : '')],
        ['Surface Z', Number.isFinite(pad.surfaceZ) ? IL.nb(IL.fmt(pad.surfaceZ, 3) + ' mm') : '—'],
        ['Dispense Z', zs.length ? IL.nb(IL.fmt(Math.min(...zs), 3) + (zs.length > 1 && Math.max(...zs) - Math.min(...zs) > 5e-4 ? ' to ' + IL.fmt(Math.max(...zs), 3) : '') + ' mm') : '—'],
      ]);
    }
    // flags: why a pad is blocked, in plain words; a pad that needs a profile gets one notebox and its actions instead
    // of the pad-type flags and "New pad type" (PROFILE_CALIBRATION_DESIGN.md §13.8, H-P7)
    const pp = IL.padProfile ? IL.padProfile.inspectorBlock($('pad-insp-profile'), pad, p) : false;
    IL.keyed($('pad-insp-flags'), JSON.stringify([sel.padId, pad && pad.flags, t && t.status, pp]), () => (pad && pad.flags ? pad.flags : [])
      .filter((fl) => !pp || (fl !== 'NO_PAD_TYPE' && fl !== 'STATUS_BELOW_MIN')).map((fl) => el('div', { class: 'notebox notebox--block', 'data-flag': fl, text: FLAG_TEXT[fl] ? FLAG_TEXT[fl](pad, t) : fl })));
    // the dots of the pad in path order
    IL.keyed($('pad-insp-dots').tBodies[0], JSON.stringify([p.planId, sel.padId]), () => padDots.map((d) => el('tr', {},
      el('td', { class: 'r', text: String(d.index + 1) }), el('td', { class: 'r', text: IL.fmt(d.x, 2) }), el('td', { class: 'r', text: IL.fmt(d.y, 2) }),
      el('td', { class: 'r', text: IL.fmt(d.dispenseZ, 3) }), el('td', { class: 'r', text: Number.isFinite(d.te) ? IL.fmtClock(d.te) : '—' }))));
    IL.show($('pad-insp-dots'), !!pad);
    // edit the pad type (or make one for the package)
    const edit = $('pad-insp-edit');
    const href = pad && pad.typeKey ? '#profile/pad-types/' + encodeURIComponent(pad.typeKey) : pkg ? '#profile/new-pad-type/' + encodeURIComponent(pkg) : null;
    IL.patch(edit, { text: pad && pad.typeKey ? 'Edit pad type ' + typeName(pad, t) : pkg ? 'New pad type for ' + pkg : 'Edit pad type', hidden: !href || !pads.length || (pp && !(pad && pad.typeKey)) });
    edit.dataset.href = href || '';
    IL.patch($('pad-insp-prev'), { disabled: list.length < 2 });
    IL.patch($('pad-insp-next'), { disabled: list.length < 2 });
  }
  /** Machine box of a part: its drawing's body box through the plan's affine, else its pads and dots; + 1 mm. */
  function partBox(p, pads, dots, ap) {
    const m = p.board && p.board.toMachine;
    const pts = [];
    if (ap && Array.isArray(ap.box) && Array.isArray(m) && m.length === 6) {
      const [x0, y0, x1, y1] = ap.box.map(Number);
      const r = (Number(ap.a) || 0) * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
      for (const [x, y] of [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]) pts.push(IL.boardart.apply(m, ap.x + x * c - y * s, ap.y + x * s + y * c));
      // its reference label too, when KiCad put it next to the part
      const lb = ap.label;
      if (lb && Math.hypot(lb.x - ap.x, lb.y - ap.y) < 4) pts.push(Object.assign(IL.boardart.apply(m, lb.x, lb.y), { r: (Number(lb.h) || 1) * 0.8 }));
    }
    for (const x of pads) pts.push({ x: x.x, y: x.y, r: Math.hypot(x.w || 0, x.h || 0) / 2 });
    for (const d of dots) pts.push({ x: d.x, y: d.y, r: 0.4 });
    const b = IL.boardview.bbox(pts);
    if (!b) return null;
    const g = { x0: b.x0 - 1, y0: b.y0 - 1, x1: b.x1 + 1, y1: b.y1 + 1 };
    // never closer than 4 mm across: a lone 0402 pad keeps some board around it
    const cx = (g.x0 + g.x1) / 2, cy = (g.y0 + g.y1) / 2;
    const w = Math.max(4, g.x1 - g.x0), h = Math.max(4, g.y1 - g.y0);
    return { x0: cx - w / 2, y0: cy - h / 2, x1: cx + w / 2, y1: cy + h / 2 };
  }

  IL.padInspect = {
    open,
    current: () => (M.sel ? Object.assign({}, M.sel) : null),
    ruleSentence,
    partsOf,
  };
})();
