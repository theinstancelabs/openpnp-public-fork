/* setup.js: the Setup workspace (spec docs/instancelabs/UI_DESIGN.md §8.2, mockup-setup.html).
   Page head with the bring-up count and the "Needs you" filter, the needs-you band (#setup-needs), eight cards in a fixed
   order (needle Z frame, probed surfaces, purge row, pressure and paste flow, places, top camera, profile and start
   angle, settings with the advanced JSON editor) and the default context panel `head` (live head position and the
   bring-up order). Pushed panel views registered here: `trim` (needle XY trim editor) and `probe-blank`.
   Contract: DEV_NOTES.md. Styles: setup.css. No native blocking dialogs (they would block STOP): two-press buttons and in-page dialogs only. */
'use strict';
(function () {
  const { el, $ } = IL;
  const S = IL.S;
  const L = IL.LIMITS;
  const MINUS = '−';
  const READY = new Set(['ok', 'you', 'off']);
  const { isNum } = IL;
  const fmt = IL.fmt;
  /** Server stamp (ms) → 24 h clock on this computer. */
  const tm = (ms) => (ms ? IL.fmtTime(ms - S.skewMs) : '—');
  const disp = (s) => (s && s.dispense) || {};
  const sentence = (t) => { const x = IL.cap(String(t || '').trim()); return x && !/[.:!?]$/.test(x) ? x + '.' : x; };
  const abbr = (text, title) => el('abbr', { title, text });
  const STATUS_LABELS = { 'not-started': 'not started', provisional: 'provisional', characterized: 'characterized', validated: 'validated on a board' };
  const PLACE_RE = /^[A-Za-z0-9_-]{1,40}$/;
  /** Settings only their own actions may change: never sent by the JSON editor (WEB_API.md, PUT /api/config). */
  const STAMPED_TOP = new Set(['needleZOffsetMm', 'needleZOffsetSetAtMs', 'needleChangedAtMs', 'needleAnchor', 'surfaces', 'schemaVersion']);
  const STAMPED_NESTED = new Set(['profile.startAngleSetAtMs', 'needleXyTrim.setAtMs', 'purgeRow.resetAtMs', 'purgeRow.usedSpots']);
  const NESTED = new Set(['profile', 'purgeRow', 'cleanPadCheck', 'needleXyTrim']);
  /** Maps PUT /api/config replaces whole: the editor diffs them per entry and Save sends the server's map as it is then
   *  with only the edited entries changed (a place saved meanwhile on the Places card is kept). */
  const BY_ENTRY = new Set(['locations']);
  const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v);
  /** `now` with the entries of a per-entry patch applied (null: the entry was removed in the editor). */
  function applyEntries(now, ch) {
    const out = Object.assign({}, now || {});
    for (const [n, v] of Object.entries(ch)) { if (v === null) delete out[n]; else out[n] = v; }
    return out;
  }
  /** A local validation error shown like a 400 (inline "Not accepted:" box). */
  const bad = (reason) => new IL.ApiError(400, { error: 'bad-request', reason }, reason);

  // ================================================================================== fields (dirty tracking, §15)
  // A field is refilled from the server value unless it is focused or the operator edited it; edited fields that differ
  // from the server get the amber bar (.input--dirty). Saving (or a server value equal to the edit) makes it clean again.
  const ALL = [];
  function fld(input, o) {
    const kind = input.type === 'checkbox' ? 'check' : input.tagName === 'SELECT' ? 'select' : input.tagName === 'TEXTAREA' ? 'text' : 'num';
    const f = Object.assign({ el: input, kind, d: undefined, edited: false, server: kind === 'check' ? false : '' }, o || {});
    input.addEventListener(kind === 'check' || kind === 'select' ? 'change' : 'input', () => { f.edited = true; paint(f); IL.render(); });
    input.addEventListener('blur', () => { if (f.edited && same(f)) f.edited = false; paint(f); });
    ALL.push(f);
    return f;
  }
  /** Input text for a number: 2 decimals unless that would lose digits (never rounds a stored value away). */
  function numStr(v, d) {
    if (!isNum(v)) return '';
    if (d === undefined) return String(Number(v));
    const t = Number(v).toFixed(d);
    return Number(t) === Number(v) ? t : String(Number(v));
  }
  function serverStr(f, v) {
    if (f.kind === 'check') return !!v;
    if (v === null || v === undefined) return '';
    return f.kind === 'num' ? numStr(v, f.d) : String(v);
  }
  const val = (f) => (f.kind === 'check' ? f.el.checked : String(f.el.value).trim());
  const toNum = (t) => Number(String(t).trim().replace(/−/g, '-'));
  function same(f) {
    const a = val(f), b = f.server;
    if (f.kind !== 'num') return a === b;
    if (a === '' || b === '') return a === b;
    const x = toNum(a);
    return Number.isFinite(x) && Math.abs(x - Number(b)) < 1e-9;
  }
  const dirty = (f) => !!f && f.edited && !same(f);
  function sync(f, v) {
    f.server = serverStr(f, v);
    if (f.edited && same(f) && document.activeElement !== f.el) f.edited = false;
    if (!f.edited && document.activeElement !== f.el) {
      if (f.kind === 'check') { if (f.el.checked !== f.server) f.el.checked = f.server; }
      else if (f.el.value !== f.server) f.el.value = f.server;
    }
    paint(f);
  }
  function paint(f) {
    f.el.classList.toggle('input--dirty', dirty(f));
    if (f.kind === 'num') f.el.classList.toggle('input--invalid', val(f) !== '' && !Number.isFinite(toNum(val(f))));
  }
  /** Sets a value the way the operator would (capture buttons). */
  function setVal(f, v) {
    if (f.kind === 'check') f.el.checked = !!v; else f.el.value = v;
    f.edited = true;
    paint(f);
    IL.render();
  }
  const clean = (...fs) => { for (const f of fs) if (f) { f.edited = false; paint(f); } };
  /** The number in a field, or a 400-style error naming it. */
  function need(f, what) {
    const t = val(f);
    const n = toNum(t);
    if (t === '') throw bad('Enter ' + what + '.');
    if (!Number.isFinite(n)) throw bad(IL.cap(what) + ' is not a number: “' + t + '”.');
    return n;
  }
  const opt = (f) => { const t = val(f); return t === '' ? undefined : toNum(t); };

  // ================================================================================== DOM builders
  function input(id, o) {
    return el('input', Object.assign({ class: 'input', id, type: 'text', inputmode: 'decimal', autocomplete: 'off', spellcheck: 'false' }, o || {}));
  }
  function field(label, unit, control, o) {
    const oo = o || {};
    return el('div', { class: 'field' + (oo.cls ? ' ' + oo.cls : ''), dataset: oo.dataset || null },
      el('label', { class: 'field__l', for: oo.for || (control && control.id) || null }, el('span', {}, label), unit ? el('span', { class: 'unit', text: unit }) : null),
      control, oo.hint ? el('div', { class: 'field__h', text: oo.hint }) : null);
  }
  /** A button with a label span (patched without touching the icon). */
  function btn(text, o, icon) {
    const oo = o || {};
    return el('button', Object.assign({ type: 'button', class: 'btn' }, oo), icon ? IL.icon(icon) : null, el('span', { class: 'lbl', text }));
  }
  function setLabel(b, text) {
    if (!b || IL.isArmed(b)) return;
    const l = b.querySelector('.lbl');
    if (l) IL.setText(l, text); else IL.setText(b, text);
  }
  /** Capture button: icon · what · live value (§11). */
  function capture(id, what) {
    const v = el('b', { text: '—' });
    const b = el('button', { type: 'button', class: 'capture', id }, IL.icon('i-capture'), el('span', { text: what }), v);
    b.liveEl = v;
    return b;
  }
  function mini(k) {
    const v = el('span', { class: 'su-mini-n', text: '—' });
    const s = el('div', { class: 'mini__s', text: '' });
    const node = el('div', { class: 'mini' }, el('div', { class: 'mini__k', text: k }), el('div', { class: 'mini__v' }, v, el('span', { class: 'u', text: 'mm' })), s);
    return { node, v, s };
  }
  /** A card: section.card#card-<key> with head (title, sub, state), body, foot and a refusal host. */
  function card(key, title, sub, o) {
    const oo = o || {};
    const glyph = IL.glyph('off', 'sm');
    const stTxt = el('span', { class: 'su-state-t' });
    const state = el('span', { class: 'card__state', id: oo.stateId || null, hidden: true }, glyph, stTxt);
    const body = el('div', { class: 'card__body' + (oo.bodyCls ? ' ' + oo.bodyCls : '') });
    const foot = el('div', { class: 'card__foot' });
    const ref = el('div', { class: 'su-ref', 'data-refusals': '' });
    const root = el('section', { class: 'card' + (oo.span ? ' span2' : ''), id: 'card-' + key, 'aria-labelledby': 'card-' + key + '-t', 'data-card': key },
      el('div', { class: 'card__head' }, el('div', { class: 'su-head-t' }, el('h2', { class: 'card__title', id: 'card-' + key + '-t', text: title }),
        sub ? el('div', { class: 'card__sub' }, sub) : null), state),
      body, foot, ref);
    return { key, root, body, foot, ref, state, glyph, stTxt };
  }
  function cardState(c, kind, text, title) {
    IL.show(c.state, !!text);
    IL.setGlyph(c.glyph, kind || 'off', 'sm');
    IL.setText(c.stTxt, text || '');
    IL.setClass(c.state, 'card__state card__state--' + (kind === 'you' ? 'ok' : kind || 'off'));
    IL.setAttr(c.state, 'title', title || null);
  }
  /** Foot reason line: the lock reason wins over a button's own reason. */
  function footReason(c, text, kind) {
    if (!c.reason) { c.reason = el('span', { class: 'reason su-foot-reason' }); }
    IL.setText(c.reason, text || '');
    IL.setClass(c.reason, 'reason su-foot-reason' + (kind ? ' reason--' + kind : ''));
    IL.show(c.reason, !!text);
  }
  /** Scrolls to a card and outlines it (route section, bring-up rows, band links). */
  function goCard(sec) {
    if (S.route.ws === 'setup' && S.route.section === sec && !S.route.panel) flashCard(sec);
    else IL.go('#setup/' + sec);
  }
  function flashCard(sec) {
    const c = $('card-' + (SECTION_CARD[sec] || sec));
    if (!c) return;
    if (W.filter === 'needs' && c.dataset.ok === '1') setFilter('all');
    IL.flash(c);
  }
  /** A deep link to one field: #setup/places/<name> (its Z), #setup/settings/min-status | max-on. */
  function focusTarget(sec, id) {
    let f = null;
    if (sec === 'places') { const r = P.rows.get(id); f = r && r.z.el; }
    else if (sec === 'settings') f = $({ 'min-status': 'set-minstatus', 'max-on': 'set-maxon' }[id] || '');
    if (f && f.focus) { f.focus({ preventScroll: true }); if (f.select) f.select(); }
  }
  const SECTION_CARD = { needle: 'needle', trim: 'needle', surfaces: 'surfaces', blank: 'surfaces', purge: 'purge', pressure: 'pressure',
    prime: 'pressure', places: 'places', camera: 'camera', angle: 'angle', settings: 'settings' };

  // ================================================================================== geometry helpers
  const { hull, outside, planeFit } = IL.boardview;   // convex hull, mm outside it (engine rule), plane fit
  const surfPts = (sf) => ((sf && sf.points) || []).map((p) => (Array.isArray(p) ? { x: p[0], y: p[1], z: p[2] } : { x: p.x, y: p.y, z: p.z }));
  function purgeSpots(pr) {
    const out = [];
    if (!pr || !pr.defined || !isNum(pr.x0Mm) || !isNum(pr.y0Mm)) return out;
    for (let i = 0; i < pr.spots; i++) out.push({ i, x: pr.x0Mm + i * pr.pitchXMm, y: pr.y0Mm + i * pr.pitchYMm, used: i < pr.usedSpots });
    return out;
  }
  /** Which purge spots and places a surface covers (engine rule: ≤ margin outside the hull of its points). */
  function coverage(sf, pr, places) {
    const h = hull(surfPts(sf));
    const margin = S.config && isNum(S.config.surfaceMarginMm) ? S.config.surfaceMarginMm : 3.5;
    const spots = purgeSpots(pr);
    const spotsOut = spots.filter((p) => outside(h, p.x, p.y) > margin).length;
    const placesIn = places.filter((p) => p.loc && outside(h, p.loc.x, p.loc.y) <= margin).map((p) => p.name);
    return { spots: spots.length, spotsOut, placesIn, margin };
  }
  const surfLimit = () => (S.config && isNum(S.config.surfaceMaxResidualMm) ? S.config.surfaceMaxResidualMm : 0.04);

  // ================================================================================== bring-up model (§8.2)
  // Steps 1, 3, 5, 9, 10 and 11 reuse the readiness items of IL.readiness, so Setup and Run never disagree.
  let modelMemo = null;
  const typeMemo = new WeakMap();
  function model(s) {
    const r = IL.ready();
    const attest = IL.lsGet('il.attest') || '';
    if (modelMemo && modelMemo.r === r && modelMemo.s === s && modelMemo.cfg === S.config && modelMemo.attest === attest) return modelMemo.m;
    const it = {};
    for (const x of r.items) it[x.key] = x;
    const d = disp(s), nd = d.needle || {}, pr = d.purgeRow || {}, locs = d.locations || {}, prof = d.profile || {};
    const cfg = S.config;
    const steps = [];
    const add = (o) => { o.n = steps.length + 1; o.ready = READY.has(o.state); o.fixes = o.fixes || []; steps.push(o); };
    const psi = IL.fmtPsi(prof.pressurePsi);

    // 1 Machine
    const mi = it.machine;
    add({ key: 'machine', title: 'Machine', state: mi.state, code: 'MACHINE', view: 'machine', detail: mi.detail, fixes: mi.fixes,
      value: mi.state === 'ok' ? 'homed' + (S.homedAt ? ' ' + IL.fmtTime(S.homedAt) : '') : mi.value });
    // 2 Settings (CONFIG)
    const ci = IL.interlock(s, 'CONFIG');
    if (ci && ci.state === 'blocked') {
      add({ key: 'settings', title: 'Settings', state: 'block', card: 'settings', code: 'CONFIG', value: 'not valid',
        detail: 'Dispenser settings are not valid: ' + ci.text + '.', fixes: [{ href: '#setup/settings', label: 'Open Settings' }] });
    } else {
      add({ key: 'settings', title: 'Settings', state: ci ? 'ok' : 'wait', card: 'settings', code: 'CONFIG',
        value: cfg && isNum(cfg.valveSettleMs) ? 'valve settle ' + cfg.valveSettleMs + ' ms' : 'valid', detail: 'The dispenser settings are valid.' });
    }
    // 3 Regulator (you)
    const reg = IL.attest.valid('regulator', s);
    const ri = it.regulator;
    add({ key: 'regulator', title: 'Regulator', state: ri.state, card: 'pressure', code: 'I6', you: !!reg,
      value: reg ? psi + ' psi · ' + IL.fmtTime(reg.atMs) : 'read the gauge', detail: ri.detail,
      fixes: [{ fix: 'pressure-on', label: 'Pressure on', icon: 'i-gauge' }, { fix: 'attest-regulator', label: 'Reads ' + psi + ' psi' }] });
    // 4 Places
    const wn = d.wasteLocationName || 'waste', bn = d.blankLocationName || 'blank';
    const missing = [wn, bn].filter((n) => !locs[n]);
    const i4 = IL.interlock(s, 'I4');
    const farPlace = Object.keys(locs).filter((n) => n !== 'waste-test-dot').find((n) => placeZOff(locs[n].z) !== null);
    const placeBad = i4 && i4.state === 'blocked' && /^place '/i.test(i4.text || '') ? i4.text : farPlace ? placeZText(farPlace, locs[farPlace].z) : null;
    const names = Object.keys(locs).filter((n) => n !== 'waste-test-dot');
    if (placeBad) {
      add({ key: 'places', title: 'Places', state: 'block', card: 'places', code: 'I4', value: 'check Z', detail: sentence(placeBad.split('. Place Z')[0]),
        fixes: [{ href: '#setup/places', label: 'Retake Z with N1' }] });
    } else if (missing.length) {
      add({ key: 'places', title: 'Places', state: 'need', card: 'places', value: missing.join(' and ') + ' missing',
        detail: 'No ' + missing.map((n) => "'" + n + "'").join(' or ') + ' place yet: ' + (missing.includes(wn) ? 'prime and test dots go to the waste.' : 'touch-off and the dot ladder use the blank.'),
        fixes: [{ href: '#setup/places', label: 'Open Places' }] });
    } else {
      add({ key: 'places', title: 'Places', state: 'ok', card: 'places', value: names.slice(0, 3).join(', ') + (names.length > 3 ? ' +' + (names.length - 3) : ''),
        detail: 'Places: ' + names.join(', ') + '.' });
    }
    // 5 Needle Z frame (I4 needle)
    const ni = it.needle;
    add({ key: 'needle', title: 'Needle Z frame', state: ni.state, card: 'needle', code: 'I4', detail: ni.detail, fixes: ni.fixes,
      value: ni.state === 'ok' ? IL.fmt(nd.zOffsetMm, 3) + ' mm' : ni.state === 'need' ? 'confirm offset' : ni.value });
    // 6 Blank surface
    const bsn = pr.surfaceName || bn;
    const bsf = (d.surfaces || {})[bsn];
    const lim = surfLimit();
    const blank = { key: 'blank', title: 'Blank surface', card: 'surfaces', code: 'I4', fixes: [{ fix: 'su-probe-blank', label: 'Probe the blank…' }] };
    if (!bsf) add(Object.assign(blank, { state: 'block', value: 'not probed', detail: "No '" + bsn + "' surface: the purge row, touch-off and dot ladder take their Z from it." }));
    else if (!bsf.plane) add(Object.assign(blank, { state: 'block', value: 'cannot fit', detail: "'" + bsn + "' cannot be fitted" + (bsf.error ? ': ' + bsf.error : '') + '.' }));
    else if (bsf.plane.maxResidualMm > lim) add(Object.assign(blank, { state: 'block', value: 'not flat', detail: "'" + bsn + "' is not flat enough (worst " + fmt(bsf.plane.maxResidualMm, 3) + ' mm, limit ' + fmt(lim, 2) + ').' }));
    else if (nd.anchor && (bsf.plane.minZ < nd.anchor.surfaceZMm - 1 || bsf.plane.maxZ > nd.anchor.surfaceZMm + 1)) {
      add(Object.assign(blank, { state: 'block', value: 'Z off', detail: "'" + bsn + "' is " + fmt(bsf.plane.minZ - nd.anchor.surfaceZMm, 2) + ' mm from the frame surface: surface points are N1 contact heights.' }));
    } else {
      const cov = coverage(bsf, pr, []);
      if (cov.spotsOut) add(Object.assign(blank, { state: 'need', value: cov.spotsOut + ' spots outside', detail: cov.spotsOut + ' of ' + cov.spots + " purge spots lie outside the probed '" + bsn + "' surface: add probe points near them." }));
      else add(Object.assign(blank, { state: 'ok', value: 'worst ' + fmt(bsf.plane.maxResidualMm, 3) + ' mm', detail: "'" + bsn + "': " + IL.plural(surfPts(bsf).length, 'point') + ', worst ' + fmt(bsf.plane.maxResidualMm, 3) + ' mm.' }));
    }
    // 7 Prime and test dot (you)
    const seen = [IL.attest.valid('test-dot-seen', s), IL.attest.valid('prime-seen', s)].filter(Boolean).sort((a, b) => b.atMs - a.atMs)[0];
    add({ key: 'prime', title: 'Prime and test dot', card: 'pressure', you: !!seen, state: seen ? 'you' : 'need', value: seen ? IL.fmtTime(seen.atMs) : 'not seen yet',
      detail: seen ? 'You saw paste come out at ' + IL.fmtTime(seen.atMs) + ' (kept in this browser for 8 h).' : 'Prime and a test dot at the waste show that paste comes out. Tick "I saw the dot" when it did.',
      fixes: [{ href: '#setup/pressure', label: 'Prime and test dot' }] });
    // 8 Needle XY trim
    const tr = nd.xyTrim || {};
    add({ key: 'trim', title: 'Needle XY trim', card: 'needle', state: tr.setAtMs ? 'ok' : 'off',
      value: tr.setAtMs ? IL.fmtSigned(tr.dxMm, 3) + ' / ' + IL.fmtSigned(tr.dyMm, 3) + ' mm' : 'not set · 0, 0',
      detail: tr.setAtMs ? 'Trim set ' + tm(tr.setAtMs) + '.' : 'Not set (0, 0): the needle lands at the N2 head offset.' });
    // 9 Screw start angle (I11)
    const ai = it.angle;
    add({ key: 'angle', title: 'Screw start angle', card: 'angle', code: 'I11', state: ai.state, detail: ai.detail, fixes: ai.fixes,
      value: ai.state === 'need' ? 'confirm' : ai.value });
    // 10 Purge row (I9)
    const pi = it.purge;
    add({ key: 'purge', title: 'Purge row', card: 'purge', code: 'I9', state: pi.state, detail: pi.detail, fixes: pi.fixes,
      value: pr.defined ? pr.spotsLeft + ' of ' + pr.spots + ' left' : pi.value });
    // 11 Pad types (from the plan shown)
    const ti = it.padtypes;
    let tv = ti.value;
    if (ti.state === 'ok' && S.plan) {
      if (!typeMemo.has(S.plan)) {
        const types = new Map();
        for (const p of S.plan.pads || []) if (p.typeKey) types.set(p.typeKey, p.typeStatus);
        typeMemo.set(S.plan, types);
      }
      const types = typeMemo.get(S.plan);
      tv = types.size === 1 ? Array.from(types.entries())[0].join(' ') : IL.plural(types.size, 'type') + ' · ' + ti.value + ' pads';
    }
    add({ key: 'padtypes', title: 'Pad types', state: ti.state, code: 'PLAN', href: '#profile', detail: ti.detail, fixes: ti.fixes,
      value: ti.state === 'wait' ? 'after planning' : tv });

    const count = steps.filter((x) => x.ready).length;
    const needs = steps.filter((x) => x.state === 'need' || x.state === 'block');
    const m = { steps, count, total: steps.length, needs, now: needs[0] || null, blocked: needs.some((x) => x.state === 'block') };
    modelMemo = { r, s, cfg: S.config, attest, m };
    return m;
  }

  // ================================================================================== workspace state
  const W = { root: null, filter: 'all', shown: false, pendingFlash: null, lastRoute: null };
  const C = {};      // cards
  const F = {};      // fields

  // ---------------------------------------------------------------------------------- page head + band
  function buildHead() {
    W.count = el('span', { class: 'h1-sub', id: 'su-count', text: '' });
    W.segAll = el('button', { type: 'button', 'aria-pressed': 'true', text: 'All steps' });
    W.segNeeds = el('button', { type: 'button', 'aria-pressed': 'false', text: 'Needs you' });
    W.segAll.addEventListener('click', () => setFilter('all'));
    W.segNeeds.addEventListener('click', () => setFilter('needs'));
    W.lock = el('div', { class: 'lock-note su-lock', hidden: true }, IL.icon('i-lock'), el('span'));
    W.band = el('section', { class: 'band', id: 'setup-needs', 'aria-label': 'Needs you', hidden: true });
    W.empty = el('div', { class: 'notebox su-allready', hidden: true });
    return [
      el('div', { class: 'page-head' },
        el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Setup · machine bring-up and tools' }),
          el('h1', {}, 'Setup ', W.count),
          el('div', { class: 'meta' }, el('span', {}, 'Ticks come from the machine. Steps marked ', el('span', { class: 'tag tag--you', text: 'you' }),
            ' have no sensor: your tick is kept in this browser.'))),
        el('div', { class: 'page-head__actions' }, el('div', { class: 'seg', role: 'group', 'aria-label': 'Show' }, W.segAll, W.segNeeds))),
      W.lock, W.band, W.empty,
    ];
  }
  function setFilter(f) {
    W.filter = f;
    W.root.classList.toggle('su-only-needs', f === 'needs');
    W.segAll.setAttribute('aria-pressed', String(f === 'all'));
    W.segNeeds.setAttribute('aria-pressed', String(f === 'needs'));
    IL.render();
  }
  /** The band item of a not-ready step: question, detail, answers / fix-its (§8.2). */
  function bandItem(x, s) {
    const d = disp(s), prof = d.profile || {}, pr = d.purgeRow || {};
    let q = x.title + ': ' + x.value, dd = x.detail, code = x.code || null, acts = null;
    const lg = (f) => IL.fixButton(f, { cls: 'btn--lg' });
    switch (x.key) {
      case 'machine':
        q = x.state === 'block' ? 'Not connected to the controller' : x.state === 'live' ? 'Homing…' : 'Not homed: positions are unknown';
        dd = x.state === 'block' ? 'Connect reconnects OpenPnP to the controller; nothing moves.' : 'After a STOP or a reconnect the machine must home before any move.';
        acts = x.fixes.map((f) => IL.fixButton(f));
        break;
      case 'angle': {
        const a = IL.fmtDeg(prof.startAngleDeg, 1);
        if (x.state === 'need') {
          const stop = S.recovery || S.lastStop;
          q = 'Was the controller switched off since ' + tm(prof.startAngleSetAtMs) + '?';
          code = 'I11 · screw start angle ' + a;
          dd = 'OpenPnP reconnected' + (prof.controllerConnectedAtMs ? ' at ' + tm(prof.controllerConnectedAtMs) : '')
            + (stop && stop.atMs ? ' after the STOP at ' + IL.fmtTime(stop.atMs) : '')
            + '. STOP and Connect keep the screw step count; only switching the controller off loses it.';
          acts = [IL.fixButton({ fix: 'I11-confirm', label: 'No, it stayed on: ' + a + ' still holds' }, { cls: 'btn--lg answer' }),
            IL.fixButton({ href: '#calibrate/angle', label: 'Yes, it was off: measure again' }, { cls: 'btn--lg answer' })];
        } else {
          q = 'The start angle was never set';
          dd = 'A partial-turn profile needs the screw angle the dots start at. Measure it with the dot-pair procedure.';
          acts = [IL.fixButton({ href: '#calibrate/angle', label: 'Measure the angle', primary: true })];
        }
        break;
      }
      case 'purge':
        if (pr.defined && pr.spots < pr.perBoard) {
          q = 'The purge row has ' + IL.plural(pr.spots, 'spot') + '; a board purges ' + pr.perBoard;
          dd = 'A reset cannot fix this: give the row more spots (Purge row card), or purge fewer dots per board (Profile).';
          acts = [IL.fixButton({ fix: 'su-go-purge', label: 'Purge row: more spots', primary: true }), IL.fixButton({ href: '#profile', label: 'Profile: purge dots' })];
        } else if (x.state === 'need') {
          q = 'Purge row: ' + pr.spotsLeft + ' of ' + pr.spots + ' spots left, ' + pr.perBoard + ' needed';
          dd = 'Wipe the strip on the blank (pressure off, needle away), then reset the row.';
          acts = [lg({ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }), el('span', { class: 'su-band-strip' }, purgeStrip(pr, false))];
        } else {
          q = pr.defined ? "The purge row's surface is not probed" : 'The purge row is not set';
          dd = x.detail;
          acts = [IL.fixButton({ label: pr.defined ? 'Probe the blank…' : 'Set the purge row', fix: pr.defined ? 'su-probe-blank' : 'su-go-purge', primary: true })];
        }
        break;
      case 'regulator':
        q = 'Read the regulator gauge: ' + IL.fmtPsi(prof.pressurePsi) + ' psi';
        code = 'I6 · no sensor';
        dd = 'Switch the pressure on with the needle over the waste, read the gauge, then tick it. Kept in this browser for 8 h.';
        acts = x.fixes.map((f) => IL.fixButton(f));
        break;
      case 'needle':
        if (x.state === 'need') {
          q = 'Needle offset not confirmed since the last needle change or frame';
          dd = 'The needle Z frame gives ' + IL.fmt(d.needle && d.needle.anchorOffsetMm, 3) + ' mm. Confirm it, or run a touch-off for a measured offset.';
        } else {
          q = x.detail.replace(/\.$/, '');
          dd = 'Touch the same spot with N1 and the needle, or record the frame from K, in the Needle Z frame card.';
        }
        acts = x.fixes.map((f) => IL.fixButton(f.href === '#setup/needle' ? { fix: 'su-go-needle', label: f.label } : f));
        break;
      default:
        q = x.title + ': ' + x.value;
        acts = (x.fixes.length ? x.fixes : x.card ? [{ fix: 'su-go-' + x.card, label: 'Open ' + x.title }] : [])
          .map((f) => IL.fixButton(f.href && f.href.indexOf('#setup/') === 0 ? { fix: 'su-go-' + f.href.slice(7), label: f.label } : f));
    }
    return el('div', { class: 'band__item' + (x.state === 'block' ? ' band__item--block' : ''), 'data-step': x.key, 'data-refusals': '' },
      IL.glyph(x.state === 'block' ? 'block' : x.state === 'live' ? 'live' : 'need'),
      el('div', {}, el('div', { class: 'band__q' }, el('span', { text: q }), code ? el('span', { class: 'icode', text: code }) : null),
        dd ? el('div', { class: 'band__d', text: dd }) : null),
      acts && acts.length ? el('div', { class: 'band__act' }, acts) : null);
  }
  function renderBand(s, m) {
    const items = m.needs;
    IL.show(W.band, items.length > 0);
    const key = JSON.stringify(items.map((x) => [x.key, x.state, x.value, x.detail, x.fixes.map((f) => f.label)])) + '|' + (S.lastStop ? S.lastStop.atMs : '');
    IL.keyed(W.band, key, () => items.map((x) => bandItem(x, s)));
    // machine actions in the band say why they are locked: core gates every fix-it button (IL.fix spec.gate)
    IL.show(W.empty, W.filter === 'needs' && !items.length);
    IL.setText(W.empty, 'Nothing needs you: all ' + m.total + ' steps are ready or off.');
  }
  // fix-its local to this page (scroll to a card / open the blank probe)
  for (const k of Object.keys(SECTION_CARD)) IL.fix('su-go-' + k, { run: async () => { goCard(k); } });
  IL.fix('su-probe-blank', { run: async () => { IL.openView('probe-blank', { keep: true }); } });

  // ---------------------------------------------------------------------------------- purge strip
  function purgeStrip(pr, lg) {
    const cells = [];
    for (let i = 0; i < (pr.spots || 0); i++) cells.push(el('i', { class: i < pr.usedSpots ? 'used' : i < pr.usedSpots + pr.perBoard ? 'next' : null }));
    return el('div', { class: 'purge' + (lg ? ' purge--lg' : '') + (pr.spots > 30 ? ' su-purge-wrap' : ''), role: 'img',
      'aria-label': 'Purge row: ' + pr.usedSpots + ' used, ' + pr.spotsLeft + ' free, the next board uses ' + pr.perBoard }, cells);
  }

  // ================================================================================== 1 Needle Z frame
  function buildNeedle() {
    const c = C.needle = card('needle', 'Needle Z frame', [abbr('I4', 'Interlock I4: needle Z frame, needle offset and surfaces'), " · needle tip height in OpenPnP's Z"],
      { stateId: 'needle-state', bodyCls: 'su-col' });
    c.mFrame = mini('Frame offset');
    c.mNeedle = mini('Needle offset');
    c.anchorState = el('p', { class: 'su-note', id: 'anchor-state' });
    c.segK = el('button', { type: 'button', 'aria-pressed': 'true', text: 'From K', title: 'Needle contact from the bench relation: raw Z = K − N1 contact Z' });
    c.segBoth = el('button', { type: 'button', 'aria-pressed': 'false', text: 'Both tips touch', title: 'Touch the same spot with the N1 nozzle and the needle tip' });
    c.segK.addEventListener('click', () => setMethod('k', true));
    c.segBoth.addEventListener('click', () => setMethod('both', true));
    F.n1 = fld(input('anchor-n1'), { d: 3 });
    F.k = fld(input('anchor-k'), { d: 2 });
    F.n2 = fld(input('anchor-n2'), { d: 3 });
    F.off = fld(input('needle-offset'), { d: 3 });
    c.capN1 = capture('btn-anchor-n1', 'Use live N1 Z');
    c.capN2 = capture('btn-anchor-n2', 'Use live N2 Z');
    c.capN1.addEventListener('click', () => { const p = IL.positions(S.status).n1; if (p) setVal(F.n1, p.z.toFixed(3)); });
    c.capN2.addEventListener('click', () => { const p = IL.positions(S.status).n2; if (p) setVal(F.n2, p.z.toFixed(3)); });
    c.kInfo = el('span', { class: 'su-derived' });
    c.rowK = field(abbr('K', 'Bench relation: needle contact raw Z = K − N1 contact Z (side camera, 2026-10-27: K = 65.59)'), 'mm',
      el('div', { class: 'row su-row-mid' }, F.k.el, c.kInfo), { for: 'anchor-k' });
    c.rowN2 = field('Needle contact Z, tip touching the same spot', 'mm', el('div', { class: 'row' }, F.n2.el, c.capN2), { for: 'anchor-n2' });
    c.offErr = el('div', { class: 'field__err', id: 'needle-offset-err', hidden: true });
    c.offWhy = el('div', { class: 'reason', hidden: true });
    c.other = el('details', { class: 'disclose su-other', id: 'needle-other', 'data-enter-submits': 'btn-needle-offset' }, el('summary', { text: 'Other offset…' }),
      field('Needle Z offset you measured', 'mm', F.off.el, { hint: 'Saved with the button below instead of the frame offset. It must lie within 1.00 mm of the frame offset (I4).' }), c.offErr);
    c.body.setAttribute('data-enter-submits', 'btn-anchor-k');
    c.body.append(
      el('div', { class: 'minis' }, c.mFrame.node, c.mNeedle.node), c.anchorState,
      el('div', { class: 'seg seg--block', role: 'group', 'aria-label': 'How to record the frame' }, c.segK, c.segBoth),
      field('N1 contact Z, tip touching the blank', 'mm', el('div', { class: 'row' }, F.n1.el, c.capN1), { for: 'anchor-n1' }),
      c.rowK, c.rowN2, c.other);
    c.bK = btn('Record from K', { id: 'btn-anchor-k' });
    c.bBoth = btn('Record needle frame', { id: 'btn-anchor' });
    c.bOff = btn('Confirm offset', { id: 'btn-needle-offset', title: 'Confirms the frame offset as the needle offset (clears the touched-off part of I4), or saves the one under Other offset….' });
    c.bTouch = btn('Touch-off', { class: 'btn btn--quiet', title: 'Calibrate › Touch-off: measures the needle offset on the blank' });
    c.bTouch.appendChild(IL.icon('i-chev'));
    c.bChanged = btn('I changed the needle', { id: 'btn-needle-changed', title: 'Dispensing is blocked until the needle Z frame is recorded again and the offset confirmed or touched off.' });
    c.bTouch.addEventListener('click', () => IL.go('#calibrate/touch-off'));
    footReason(c);
    c.foot.append(c.bK, c.bBoth, c.bOff, c.reason, el('span', { class: 'spacer' }), c.bTouch, c.bChanged, c.offWhy);
    // needle XY trim row
    c.trimState = el('span', { class: 'su-trim__s', id: 'trim-state' });
    c.bTrim = btn('Edit trim', { class: 'btn btn--sm' });
    c.bTrim.addEventListener('click', () => IL.openView('trim'));
    c.root.insertBefore(el('div', { class: 'su-trim' }, el('div', { class: 'su-trim__t' }, el('b', { text: 'Needle XY trim' }), c.trimState), c.bTrim), c.ref);

    const opts = { refusalIn: c.ref };
    c.bK.addEventListener('click', () => IL.act(c.bK, async () => {
      const n1 = need(F.n1, 'the N1 contact Z'), k = need(F.k, 'K');
      const r = await IL.api('POST', '/api/needle/anchor', { surfaceZ: n1, needleContactRawSum: k });
      clean(F.n1, F.k, F.n2);
      IL.planRefreshSoon();
      return r;
    }, Object.assign({ ok: (r) => 'Needle frame recorded from K = ' + fmt(F.k.el.value, 2) + ': needle contact Z ' + fmt(r.anchor.needleContactZMm, 3)
      + ' mm, offset ' + fmt(r.offsetMm, 3) + ' mm. Confirm the offset or touch off next.' }, opts)));
    c.bBoth.addEventListener('click', () => IL.act(c.bBoth, async () => {
      const n1 = need(F.n1, 'the N1 contact Z'), n2 = need(F.n2, 'the needle contact Z');
      const r = await IL.api('POST', '/api/needle/anchor', { surfaceZ: n1, needleContactZ: n2 });
      clean(F.n1, F.n2);
      IL.planRefreshSoon();
      return r;
    }, Object.assign({ ok: (r) => 'Needle frame recorded: offset ' + fmt(r.offsetMm, 3) + ' mm. Confirm the offset or touch off next.' }, opts)));
    // an offset outside the I4 limit of the frame blocks dispensing: the second press is the operator's "yes, save it"
    IL.press2(c.bOff, () => (offsetDrift() !== null && Math.abs(offsetDrift()) > L.needleDriftMm ? 'save it anyway' : null), async () => {
      const nd = disp(S.status).needle || {};
      const manual = dirty(F.off);
      const off = manual ? need(F.off, 'the needle offset in mm') : nd.anchorOffsetMm;
      if (!isNum(off)) throw bad('Record the needle Z frame first.');
      const r = await IL.api('POST', '/api/needle/offset', { offsetMm: Number(Number(off).toFixed(4)), note: manual ? 'entered in the web UI' : 'confirmed frame offset (web UI)' });
      clean(F.off);
      IL.planRefreshSoon();
      return { r, off, manual };
    }, Object.assign({ ok: (x) => 'Needle offset ' + fmt(x.off, 3) + ' mm ' + (x.manual ? 'saved.' : 'confirmed: the frame offset.') }, opts));
    IL.press2(c.bChanged, 'needle changed', async () => {
      const r = await IL.api('POST', '/api/needle/changed');
      IL.planRefreshSoon();
      return r;
    }, Object.assign({ ok: 'Needle marked as changed: record the needle Z frame again, then confirm or touch off.' }, opts));
    return c.root;
  }
  /** The offset typed under Other offset… minus the frame offset (mm), or null. */
  function offsetDrift() {
    const nd = disp(S.status).needle || {};
    const v = opt(F.off);
    return dirty(F.off) && isNum(v) && isNum(nd.anchorOffsetMm) ? v - nd.anchorOffsetMm : null;
  }
  function setMethod(m, user) {
    const c = C.needle;
    W.method = m;
    if (user) W.methodChosen = true;
    c.segK.setAttribute('aria-pressed', String(m === 'k'));
    c.segBoth.setAttribute('aria-pressed', String(m === 'both'));
    IL.show(c.rowK, m === 'k');
    IL.show(c.rowN2, m === 'both');
    IL.show(c.bK, m === 'k');
    IL.show(c.bBoth, m === 'both');
    c.body.setAttribute('data-enter-submits', m === 'k' ? 'btn-anchor-k' : 'btn-anchor');
  }
  function renderNeedle(s, m) {
    const c = C.needle;
    const d = disp(s), nd = d.needle || {}, a = nd.anchor;
    if (!W.methodChosen && W.method === undefined) setMethod(a && !isNum(a.rawSumMm) ? 'both' : 'k');
    const step = m.steps.find((x) => x.key === 'needle');
    const drift = isNum(nd.zOffsetMm) && isNum(nd.anchorOffsetMm) ? nd.zOffsetMm - nd.anchorOffsetMm : null;
    let kind = 'ok', text;
    if (!d.running) { kind = 'off'; text = 'Engine not running'; }
    else if (!a) { kind = 'block'; text = 'NOT touched off · no frame'; }
    else if (!nd.anchored) { kind = 'block'; text = 'NOT touched off · needle changed'; }
    else if (!nd.touchedOff) { kind = 'need'; text = 'NOT touched off'; }
    else if (drift !== null && Math.abs(drift) > L.needleDriftMm) { kind = 'block'; text = 'Offset drift ' + fmt(Math.abs(drift), 2) + ' mm'; }
    // the frame offset confirmed is not a touch-off: say which
    else text = (drift !== null && Math.abs(drift) < 0.0005 ? 'Offset confirmed ' : 'Touched off ') + tm(nd.setAtMs);
    cardState(c, kind, text, step ? step.detail : null);
    // minis
    IL.setText(c.mFrame.v, a ? fmt(nd.anchorOffsetMm, 3) : '—');
    IL.setText(c.mFrame.s, a ? (isNum(a.rawSumMm) ? 'from K, recorded ' : 'both tips, recorded ') + tm(a.setAtMs) + (nd.anchored ? '' : ' · needle changed since') : 'not recorded');
    IL.setText(c.mNeedle.v, fmt(nd.zOffsetMm, 3));
    IL.setText(c.mNeedle.s, drift === null ? 'no frame to compare' : 'Δ ' + IL.fmtSigned(drift, 3) + ' · limit ±' + fmt(L.needleDriftMm, 2));
    c.mNeedle.node.classList.toggle('su-mini--bad', drift !== null && Math.abs(drift) > L.needleDriftMm);
    IL.setText(c.anchorState, !a ? 'Frame not set: nothing can be dispensed until N1 and the needle touched the same surface (or the frame is recorded from K).'
      : (nd.anchored ? 'Frame recorded ' : 'Frame OUT OF DATE (needle changed): recorded ') + tm(a.setAtMs) + ': N1 contact ' + fmt(a.surfaceZMm, 3) + ' mm, needle contact '
        + fmt(a.needleContactZMm, 3) + ' mm' + (isNum(a.rawSumMm) ? ' (from K = ' + fmt(a.rawSumMm, 2) + ')' : '') + '.');
    // fields (server values)
    sync(F.n1, a ? a.surfaceZMm : null);
    sync(F.k, a && isNum(a.rawSumMm) ? a.rawSumMm : 65.59);
    sync(F.n2, a ? a.needleContactZMm : null);
    sync(F.off, nd.zOffsetMm);
    // derived needle contact from K: needle contact = N1 + C − K, C from the frame recorded with K (OpenPnP's see-saw map)
    const seesaw = a && isNum(a.rawSumMm) ? a.needleContactZMm - a.surfaceZMm + a.rawSumMm : null;
    const n1v = opt(F.n1), kv = opt(F.k);
    IL.setText(c.kInfo, seesaw !== null && isNum(n1v) && isNum(kv) ? 'needle contact ' + fmt(n1v + seesaw - kv, 2) + ' mm' : 'raw Z = K − N1 contact Z');
    // captures
    const pos = IL.positions(s);
    IL.setText(c.capN1.liveEl, pos.n1 ? fmt(pos.n1.z, 2) : '—');
    IL.setText(c.capN2.liveEl, pos.n2 ? fmt(pos.n2.z, 2) : '—');
    const capLock = IL.lockReason(s, { connected: true, idle: false, pressureOff: false });
    IL.gate(c.capN1, capLock || (pos.n1 ? null : 'No N1 position'));
    IL.gate(c.capN2, capLock || (pos.n2 ? null : 'No needle position'));
    // buttons
    const lock = IL.lockReason(s, { pressureOff: false, engine: true });
    const manual = dirty(F.off);
    IL.gate(c.bK, lock);
    IL.gate(c.bBoth, lock);
    // after "I changed the needle" only recording the frame again helps (§9.1 #3): Confirm waits for it, Record leads
    const changed = !!a && !nd.anchored;
    const offOwn = !a && !manual ? 'Record the needle Z frame first' : changed ? 'Confirm waits: record the needle Z frame again first (the needle was changed).' : null;
    IL.gate(c.bOff, lock || offOwn);
    IL.setText(c.offWhy, !lock && offOwn ? offOwn : '');
    IL.show(c.offWhy, !lock && !!offOwn);
    setLabel(c.bOff, manual && isNum(opt(F.off)) ? 'Save ' + fmt(opt(F.off), 3) + ' mm' : a ? 'Confirm ' + fmt(nd.anchorOffsetMm, 3) + ' mm' : 'Confirm offset');
    c.bOff.classList.toggle('btn--primary', !lock && !!a && !changed && (!nd.touchedOff || manual));
    for (const b of [c.bK, c.bBoth]) b.classList.toggle('btn--primary', !lock && (!a || changed));
    // a typed offset outside ±1.00 mm of the frame: say so before it is saved
    const dOff = offsetDrift();
    const farOff = dOff !== null && Math.abs(dOff) > L.needleDriftMm;
    IL.setText(c.offErr, farOff ? fmt(opt(F.off), 3) + ' mm is ' + fmt(Math.abs(dOff), 2) + ' mm from the frame ' + fmt(nd.anchorOffsetMm, 3) + ' mm: dispensing will be blocked (I4, limit ±'
      + fmt(L.needleDriftMm, 2) + ' mm). Save asks for a second press.' : '');
    IL.show(c.offErr, farOff);
    F.off.el.classList.toggle('input--invalid', farOff);
    IL.gate(c.bChanged, lock);
    footReason(c, lock);
    // trim
    const t = nd.xyTrim || {};
    IL.setText(c.trimState, t.setAtMs ? IL.fmtSigned(t.dxMm, 3) + ' / ' + IL.fmtSigned(t.dyMm, 3) + ' mm · set ' + tm(t.setAtMs) + (t.note ? ' · ' + t.note : '')
      : 'Not set (0, 0): the needle lands at the N2 head offset.');
  }

  // ---------------------------------------------------------------------------------- needle XY trim (panel view)
  F.dx = fld(input('trim-dx'), { d: 3 });
  F.dy = fld(input('trim-dy'), { d: 3 });
  F.note = fld(el('input', { class: 'input', id: 'trim-note', type: 'text', placeholder: 'e.g. from landing photos', autocomplete: 'off' }), { kind: 'text' });
  const T = { after: null };
  /** Sign sketch of the trim: target crosshair and where the needle lands. Direction to scale, length exaggerated. */
  function trimSketch() {
    const dx = opt(F.dx) || 0, dy = opt(F.dy) || 0;
    const mag = Math.hypot(dx, dy);
    const k = mag > 1e-6 ? 46 / mag : 0;          // px per mm: the landing sits 46 px from the target
    const cx = 130, cy = 76;
    const lx = cx + dx * k, ly = cy - dy * k;
    const s = IL.svgEl('svg', { viewBox: '0 0 260 152', class: 'su-sketch', role: 'img',
      'aria-label': 'The needle lands ' + fmt(Math.abs(dx), 3) + ' mm ' + (dx < 0 ? 'left' : 'right') + ' and ' + fmt(Math.abs(dy), 3) + ' mm ' + (dy < 0 ? 'below' : 'above') + ' the target' });
    s.append(
      IL.svgEl('line', { x1: 10, y1: cy, x2: 250, y2: cy, class: 'su-sk-ax' }), IL.svgEl('line', { x1: cx, y1: 8, x2: cx, y2: 144, class: 'su-sk-ax' }),
      IL.svgEl('circle', { cx, cy, r: 9, class: 'su-sk-target' }), IL.svgEl('line', { x1: cx - 15, y1: cy, x2: cx + 15, y2: cy, class: 'su-sk-target' }),
      IL.svgEl('line', { x1: cx, y1: cy - 15, x2: cx, y2: cy + 15, class: 'su-sk-target' }));
    if (k) s.append(IL.svgEl('line', { x1: cx, y1: cy, x2: lx, y2: ly, class: 'su-sk-arrow' }), IL.svgEl('circle', { cx: lx, cy: ly, r: 6, class: 'su-sk-land' }));
    const t = (x, y, txt, cls, anchor) => { const e = IL.svgEl('text', { x, y, class: cls || 'su-sk-t', 'text-anchor': anchor || 'start' }); e.textContent = txt; return e; };
    const up = ly < cy;   // landing above the target: its label above, the target's below (and the other way round)
    s.append(t(cx - 14, up || !k ? cy + 26 : cy - 16, 'target', 'su-sk-t', 'end'),
      t(250, cy - 6, '+X', 'su-sk-t su-sk-t--ax', 'end'), t(cx + 6, 16, '+Y', 'su-sk-t su-sk-t--ax'),
      t(8, 146, k ? 'direction to scale, length not' : 'no trim: lands on the target', 'su-sk-t su-sk-t--ax'));
    if (k) s.append(t(Math.max(78, Math.min(182, lx)), up ? ly - 12 : ly + 22, 'landing ' + IL.fmtSigned(dx, 3) + ', ' + IL.fmtSigned(dy, 3) + ' mm', 'su-sk-t su-sk-t--land', 'middle'));
    return s;
  }
  IL.view('trim', {
    title: 'Needle XY trim',
    render(p, s, first) {
      const nd = disp(s).needle || {}, t = nd.xyTrim || {};
      if (first) {
        T.sketch = el('div', { class: 'su-sketch-box' });
        T.cur = el('p', { class: 'su-note' });
        T.after = el('div', { class: 'su-after', hidden: true });
        T.save = btn('Save XY trim', { id: 'btn-trim', class: 'btn btn--primary' });
        T.reason = el('div', { class: 'reason', hidden: true });
        T.save.addEventListener('click', () => IL.act(T.save, async () => {
          const dx = need(F.dx, 'dx in mm (0 for none)'), dy = need(F.dy, 'dy in mm (0 for none)');
          if (Math.abs(dx) > 1 || Math.abs(dy) > 1) throw bad('The trim is limited to ±1 mm: a larger error belongs in the N2 head offset in OpenPnP.');
          const r = await IL.api('PUT', '/api/config', { needleXyTrim: { dxMm: dx, dyMm: dy, note: IL.str(F.note.el) || null } });
          clean(F.dx, F.dy, F.note);
          IL.loadConfig();
          IL.planRefreshSoon();
          T.savedAt = Date.now();
          return r;
        }, { ok: 'Needle XY trim saved: it applies to the next run (the plan does not change).', refusalIn: p.foot }));
        p.body.append(
          el('p', { class: 'panel__lead', text: 'Where the needle lands relative to its target, in machine mm (X right, Y up). Every dot is moved by minus the trim. Within ±1 mm; a larger error belongs in the N2 head offset in OpenPnP.' }),
          T.sketch, T.cur,
          el('div', { class: 'fgrid', 'data-enter-submits': 'btn-trim' },
            field('dx: landing right of the target', 'mm', F.dx.el), field('dy: landing above the target', 'mm', F.dy.el),
            field('Note', null, F.note.el, { cls: 'span2' })),
          T.after);
        p.foot.append(el('div', { class: 'btn-row', 'data-refusals': '' }, T.save), T.reason);
      }
      sync(F.dx, t.dxMm);
      sync(F.dy, t.dyMm);
      sync(F.note, t.note || '');
      IL.keyed(T.sketch, val(F.dx) + '|' + val(F.dy), trimSketch);
      IL.setText(T.cur, t.setAtMs ? 'Saved ' + tm(t.setAtMs) + ': dx ' + IL.fmtSigned(t.dxMm, 3) + ' mm, dy ' + IL.fmtSigned(t.dyMm, 3) + ' mm' + (t.note ? ' · ' + t.note : '') + '.'
        : 'Not set (0, 0): the needle lands at the N2 head offset.');
      const lock = IL.lockReason(s, { pressureOff: false, engine: true });
      const any = dirty(F.dx) || dirty(F.dy) || dirty(F.note);
      IL.gate(T.save, lock || (any ? null : 'No unsaved changes'), T.reason);
      // the trim moves every dot when the run is streamed: no new plan needed, the next run uses it
      const showAfter = !!T.savedAt && Date.now() - T.savedAt < 10 * 60 * 1000;
      IL.show(T.after, showAfter);
      IL.keyed(T.after, String(showAfter), () => (showAfter ? el('span', { text: 'Saved ' + IL.fmtTime(T.savedAt) + ': the next run, prime and test dot land with this trim. The plan and its preview do not change.' }) : null));
    },
  });

  // ================================================================================== 2 Probed surfaces
  function buildSurfaces() {
    const c = C.surfaces = card('surfaces', 'Probed surfaces', 'N1 contact heights · fit ≤ 0.04 mm', { stateId: 'surf-state' });
    c.list = el('div', { class: 'su-surfs' });
    F.surfName = { el: el('select', { class: 'input', id: 'surf-name' }) };
    F.surfPts = fld(el('textarea', { class: 'input mono', id: 'surf-points', rows: '4', spellcheck: 'false', placeholder: '[[x, y, z], ...] or a zmap.json "points" list' }));
    F.surfPts.kind = 'text';
    F.surfName.el.addEventListener('change', () => { F.surfPts.edited = false; F.surfPts.el.value = ''; IL.render(); });
    c.bAdd = el('button', { type: 'button', class: 'capture', id: 'btn-surf-add', title: "Adds N1's live X, Y and Z (N1 tip resting on the surface)" }, IL.icon('i-capture'), el('span', { text: 'Add live N1 point' }), el('b', { text: '—' }));
    c.bAdd.liveEl = c.bAdd.querySelector('b');
    c.bClear = btn('Clear points', { id: 'btn-surf-clear', class: 'btn btn--sm', title: 'Empties the list above (nothing is saved until Save surface)' });
    c.bSave = btn('Save surface', { id: 'btn-surf-save', class: 'btn btn--primary' });
    c.bDel = btn('Delete surface', { id: 'btn-surf-delete', class: 'btn btn--danger-ghost' });
    c.tie = el('div', { class: 'su-tie' });
    c.advReason = el('div', { class: 'reason', hidden: true });
    c.adv = el('details', { class: 'disclose su-adv', id: 'surf-advanced' }, el('summary', { text: 'Advanced: paste points or a zmap.json' }),
      el('div', { class: 'su-col', 'data-refusals': '' },
        field('Surface', null, F.surfName.el, { cls: 'su-surfname' }),
        field('Points, N1 contact heights in mm', null, F.surfPts.el, { hint: '[[x, y, z], …] or the points of a zmap.json. At least 3 points spread around the area.' }),
        el('div', { class: 'btn-row' }, c.bAdd, c.bClear),
        c.tie,
        el('div', { class: 'btn-row' }, c.bSave, el('span', { class: 'spacer' }), c.bDel), c.advReason));
    c.body.append(c.list, c.adv);
    c.bBoard = btn('Probe the board…', { title: 'Run › Probe: targets from the plan, live N1, save and plan again in one press' });
    c.bBlank = btn('Probe the blank…', { title: 'Probe the blank in the side panel: point table, plane fit and coverage before saving' });
    c.bBoard.addEventListener('click', () => IL.go('#run/probe'));
    c.bBlank.addEventListener('click', () => IL.openView('probe-blank', { keep: true }));
    c.foot.append(c.bBoard, c.bBlank);

    c.bAdd.addEventListener('click', () => IL.act(c.bAdd, async () => {
      const p = IL.positions(S.status).n1;
      if (!p) throw bad('No N1 position.');
      const pts = parsePoints(val(F.surfPts));
      pts.push({ x: Number(p.x.toFixed(3)), y: Number(p.y.toFixed(3)), z: Number(p.z.toFixed(3)) });
      setVal(F.surfPts, JSON.stringify(pts.map((q) => [q.x, q.y, q.z])));
    }, { refusalIn: c.adv.lastChild }));
    c.bClear.addEventListener('click', () => setVal(F.surfPts, ''));
    c.bSave.addEventListener('click', () => IL.act(c.bSave, async () => {
      const name = val(F.surfName);
      const d = disp(S.status);
      const body = { points: parsePoints(val(F.surfPts)).map((q) => [q.x, q.y, q.z]) };
      if (body.points.length < 3) throw bad('A surface needs at least 3 points spanning an area.');
      const isBoard = name === (d.boardSurfaceName || 'board');
      const tie = isBoard && S.plan && !S.planForgotten ? S.plan.planId : null;
      if (tie) body.planId = tie;
      const r = await IL.api('PUT', '/api/surfaces/' + encodeURIComponent(name), body);
      clean(F.surfPts);
      if (isBoard) W.boardSavedAt = Date.now();
      IL.planRefreshSoon();
      return { r, name, isBoard, tie };
    }, { refusalIn: c.adv.lastChild,
      ok: (x) => (x.isBoard ? (x.r && x.r.boundToBoard ? 'Board surface saved and tied to plan ' + String(x.tie).slice(0, 4) + ': plan the board again.'
        : 'Board surface saved but not tied to a board: plan the board on Run, then save these points again.') : 'Surface ' + x.name + ' saved.') }));
    IL.press2(c.bDel, 'delete surface', async () => {
      const name = val(F.surfName);
      const r = await IL.api('DELETE', '/api/surfaces/' + encodeURIComponent(name));
      clean(F.surfPts);
      F.surfPts.el.value = '';
      IL.planRefreshSoon();
      return name;
    }, { refusalIn: c.adv.lastChild, ok: (name) => 'Surface ' + name + ' deleted.' });
    return c.root;
  }
  function parsePoints(txt) {
    const t = String(txt || '').trim();
    if (!t) return [];
    let v;
    try { v = JSON.parse(t); } catch (e) { throw bad('The points are not valid JSON: ' + e.message); }
    if (v && !Array.isArray(v) && Array.isArray(v.points)) v = v.points;
    if (!Array.isArray(v)) throw bad('Points must be a list: [[x, y, z], …].');
    const out = v.map((p) => (Array.isArray(p) ? { x: p[0], y: p[1], z: p[2] } : { x: p && p.x, y: p && p.y, z: p && p.z }));
    if (out.some((p) => ![p.x, p.y, p.z].every((n) => typeof n === 'number' && Number.isFinite(n)))) throw bad('Every point needs numbers x, y and z.');
    return out;
  }
  function miniMap(sf, o) {
    const pts = surfPts(sf);
    const extra = (o && o.extra) || [];
    const all = pts.map((p) => [p.x, p.y]).concat(extra.map((p) => [p.x, p.y]));
    if (o && o.board) all.push([o.board.x0, o.board.y0], [o.board.x1, o.board.y1]);
    const svg = IL.svgEl('svg', { viewBox: '0 0 92 66', width: 92, height: 66, class: 'su-minimap', role: 'img', 'aria-label': 'Probe points of ' + (sf ? sf.name : 'the surface') });
    if (!all.length) return svg;
    let x0 = Math.min(...all.map((p) => p[0])), x1 = Math.max(...all.map((p) => p[0])), y0 = Math.min(...all.map((p) => p[1])), y1 = Math.max(...all.map((p) => p[1]));
    const w = Math.max(x1 - x0, 1), h = Math.max(y1 - y0, 1);
    const k = Math.min(72 / w, 46 / h);
    const ox = 46 - (x0 + x1) / 2 * k, oy = 33 + (y0 + y1) / 2 * k;
    const X = (x) => ox + x * k, Y = (y) => oy - y * k;
    if (o && o.board) svg.append(IL.svgEl('rect', { x: X(o.board.x0), y: Y(o.board.y1), width: (o.board.x1 - o.board.x0) * k, height: (o.board.y1 - o.board.y0) * k, rx: 2, class: 'su-mm-board' }));
    for (const p of extra) svg.append(IL.svgEl('rect', { x: X(p.x) - 1.6, y: Y(p.y) - 1.6, width: 3.2, height: 3.2, class: 'su-mm-spot' + (p.used ? ' su-mm-spot--used' : '') }));
    const hh = hull(pts);
    if (hh.length >= 3) svg.append(IL.svgEl('polygon', { points: hh.map((p) => X(p.x).toFixed(1) + ',' + Y(p.y).toFixed(1)).join(' '), class: 'su-mm-hull' }));
    for (const p of pts) svg.append(IL.svgEl('circle', { cx: X(p.x), cy: Y(p.y), r: 2.6, class: 'su-mm-pt' }));
    return svg;
  }
  let boxMemo = { plan: undefined, box: null };
  /** Pads' bounding box + 3 mm (machine mm) of the plan shown, computed once per plan. */
  function planBox(plan) {
    if (boxMemo.plan === plan) return boxMemo.box;
    const pads = (plan && plan.pads) || [];
    let box = null;
    if (pads.length) {
      let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
      for (const p of pads) { if (p.x < x0) x0 = p.x; if (p.x > x1) x1 = p.x; if (p.y < y0) y0 = p.y; if (p.y > y1) y1 = p.y; }
      box = { x0: x0 - 3, x1: x1 + 3, y0: y0 - 3, y1: y1 + 3 };
    }
    boxMemo = { plan, box };
    return box;
  }
  function surfInfo(s) {
    const d = disp(s), sfs = d.surfaces || {}, pr = d.purgeRow || {};
    const bsn = d.boardSurfaceName || 'board', blank = pr.surfaceName || d.blankLocationName || 'blank';
    const lim = surfLimit();
    const r = IL.ready();
    const surfItem = r.items.find((x) => x.key === 'surface');
    const names = [bsn, blank].concat(Object.keys(sfs).filter((n) => n !== bsn && n !== blank));
    return names.map((name) => {
      const sf = sfs[name];
      const isBoard = name === bsn;
      const o = { name, sf, isBoard, label: isBoard ? 'Board' : name === blank ? 'Blank' : name };
      if (!sf) return Object.assign(o, { kind: isBoard ? 'need' : 'block', short: 'not probed', rows: [['Points', 'none yet']] });
      const pts = surfPts(sf);
      const pl = sf.plane;
      const rows = [];
      if (!pl) return Object.assign(o, { kind: 'block', short: 'cannot fit', rows: [['Points', String(pts.length)], ['Fit', sf.error || 'no plane']] });
      const flat = pl.maxResidualMm <= lim;
      o.kind = flat ? 'ok' : 'block';
      o.short = flat ? 'fits' : 'not flat';
      if (isBoard) {
        const src = S.plan && S.plan.source;
        rows.push(['Seat', src && src.source === 'file' ? fmt(src.x, 2) + ', ' + fmt(src.y, 2) + ' · ' + fmt(src.rotation || 0, 1) + '°' : src ? 'job board (fiducials)' : 'no plan shown']);
        if (flat && S.plan) {
          if (surfItem && surfItem.state === 'ok') { o.pill = 'tied to plan ' + String(S.plan.planId).slice(0, 4); o.pillKind = 'ok'; }
          else if (surfItem && (surfItem.state === 'need' || surfItem.seat)) {
            o.pill = sf.boundToBoard ? 'another seat' : 'not tied'; o.pillKind = 'need'; o.kind = surfItem.state === 'block' ? 'block' : 'need'; o.short = 'probe this seat';
          }
        } else if (flat) { o.pill = sf.boundToBoard ? 'tied to a seat' : 'not tied'; o.pillKind = sf.boundToBoard ? 'ok' : 'need'; }
      }
      rows.push(['Points', pts.length + ' · worst ' + fmt(pl.maxResidualMm, 3) + ' mm']);
      if (isBoard) rows.push(['Tilt', fmt(pl.slopeMmPerMm * 1000, 0) + ' µm/mm · ' + tm(sf.setAtMs)]);
      else {
        rows.push(['Z', fmt(pl.minZ, 2) + ' to ' + fmt(pl.maxZ, 2) + ' mm']);
        if (name === blank) {
          const locs = d.locations || {};
          const cov = coverage(sf, pr, Object.keys(locs).filter((n) => n !== 'waste-test-dot').map((n) => ({ name: n, loc: locs[n] })));
          const parts = [];
          if (cov.spots) parts.push(cov.spotsOut ? cov.spotsOut + ' purge spots outside' : 'purge row');
          if (cov.placesIn.length) parts.push(cov.placesIn.join(', '));
          rows.push(['Covers', parts.length ? parts.join(' and ') : 'nothing yet']);
          if (cov.spotsOut && flat) { o.kind = 'need'; o.short = 'add points'; }
        }
        rows.push(['Saved', tm(sf.setAtMs)]);
      }
      o.rows = rows;
      return o;
    });
  }
  function renderSurfaces(s) {
    const c = C.surfaces;
    const d = disp(s), pr = d.purgeRow || {};
    const info = surfInfo(s);
    const worst = info.find((x) => x.kind === 'block') || info.find((x) => x.kind === 'need');
    if (!worst) cardState(c, 'ok', info.length > 1 ? (info.length === 2 ? 'Both fit' : 'All fit') : 'Fits');
    else cardState(c, worst.kind, worst.label + ': ' + worst.short);
    const bb = planBox(S.plan);
    const key = JSON.stringify(info.map((x) => [x.name, x.kind, x.pill, x.rows, x.sf && x.sf.setAtMs])) + (bb ? JSON.stringify(bb) : '') + JSON.stringify([pr.usedSpots, pr.spots, pr.x0Mm, pr.y0Mm]);
    IL.keyed(c.list, key, () => info.filter((x) => x.sf || x.isBoard || x.label === 'Blank').map((x) => el('div', { class: 'surf', 'data-surface': x.name },
      el('div', {}, el('div', { class: 'surf__t' }, IL.glyph(x.kind, 'sm'), el('span', { text: x.label }),
        x.pill ? el('span', { class: 'pill-tie' + (x.pillKind === 'need' ? ' su-pill--need' : ''), text: x.pill }) : null),
      el('dl', { class: 'kv su-kv' }, x.rows.map(([k, v]) => [el('dt', { text: k }), el('dd', { text: v })]))),
      x.sf ? miniMap(x.sf, x.isBoard ? { board: bb, extra: [] } : { extra: purgeSpots(pr) }) : null)));
    // advanced editor
    const names = info.map((x) => x.name);
    const sel = F.surfName.el;
    const optKey = names.join('|');
    if (sel.dataset.opts !== optKey) {
      const keep = sel.value || names[0];
      sel.dataset.opts = optKey;
      sel.replaceChildren(...names.map((n) => el('option', { value: n, text: n })));
      sel.value = names.includes(keep) ? keep : names[0];
    }
    const name = sel.value;
    const sf = (d.surfaces || {})[name];
    const isBoard = name === (d.boardSurfaceName || 'board');
    // the saved board points of another seat (re-seated, moved) are not offered for this one: one press of Save would
    // tie them to this plan and pass I4. The editor starts empty, as Run › Probe does; untied points are still offered
    // (saving them with the plan shown is how they get tied)
    const sfItem = isBoard && sf ? IL.ready().items.find((x) => x.key === 'surface') : null;
    const otherSeat = !!sfItem && (sfItem.seat === 'new' || sfItem.seat === 'other');
    sync(F.surfPts, sf && !otherSeat ? JSON.stringify(surfPts(sf).map((p) => [p.x, p.y, p.z])) : '');
    const plan = S.plan;
    IL.keyed(c.tie, [name, isBoard, plan && plan.planId, S.planForgotten, W.boardSavedAt, otherSeat].join('|'), () => {
      if (!isBoard) return el('span', { class: 'su-note', text: "The " + name + " surface carries " + (name === (pr.surfaceName || 'blank') ? 'the purge row, the touch-off and the dot ladder.' : 'what refers to it by name.') });
      if (plan && !S.planForgotten) {
        const src = plan.source || {};
        const at = ' (' + (plan.boardName || 'board') + (src.source === 'file' ? ' at ' + fmt(src.x, 2) + ', ' + fmt(src.y, 2) + ' · ' + fmt(src.rotation || 0, 1) + '°' : '') + ')';
        if (otherSeat) {
          return el('span', { class: 'su-note su-note--need', text: 'The saved board points were probed on another seat, so they are not offered here. Probe this seat'
            + ' (Probe the board…) or paste or add its points: Save ties them to plan ' + String(plan.planId).slice(0, 4) + at + '.' });
        }
        return [el('span', { class: 'su-note', text: 'Save ties these points to plan ' + String(plan.planId).slice(0, 4) + at + '. Plan again after saving.' }),
          W.boardSavedAt ? IL.fixButton({ fix: 'plan-again', label: 'Plan again' }, { cls: 'btn--sm' }) : null];
      }
      return el('span', { class: 'su-note su-note--need', text: 'No plan shown: the board surface would be saved untied and board runs refuse it (I4). Plan the board on Run first, or use Probe the board….' });
    });
    const pos = IL.positions(s);
    IL.setText(c.bAdd.liveEl, pos.n1 ? fmt(pos.n1.x, 2) + ', ' + fmt(pos.n1.y, 2) + ', ' + fmt(pos.n1.z, 2) : '—');
    const lock = IL.lockReason(s, { pressureOff: false, engine: true });
    IL.gate(c.bAdd, IL.lockReason(s, { connected: true, idle: false, pressureOff: false }) || (pos.n1 ? null : 'No N1 position'));
    const saveWhy = lock || (otherSeat && !dirty(F.surfPts) ? 'Points of another seat: probe this seat, or paste or add its points' : null);
    IL.gate(c.bSave, saveWhy);
    IL.gate(c.bDel, lock || (sf ? null : 'No ' + name + ' surface saved'));
    IL.gate(c.bBlank, IL.lockReason(s, { pressureOff: false }));
    IL.setText(c.advReason, saveWhy || '');
    IL.show(c.advReason, !!saveWhy);
  }

  // ---------------------------------------------------------------------------------- probe the blank (panel view)
  const PB = { points: [], name: null };
  IL.view('probe-blank', {
    title: (s) => 'Probe the ' + ((disp(s).purgeRow || {}).surfaceName || 'blank'),
    render(p, s, first) {
      const d = disp(s), pr = d.purgeRow || {};
      const name = pr.surfaceName || d.blankLocationName || 'blank';
      if (PB.name !== name) { PB.name = name; PB.points = []; }
      const saved = surfPts((d.surfaces || {})[name]);
      if (first) {
        PB.banner = el('div', { class: 'notebox notebox--info' });
        PB.live = el('table', { class: 'headtable su-live-n1' }, el('thead', {}, el('tr', {}, ['N1 now', 'X', 'Y', 'Z'].map((h) => el('th', { text: h })))),
          el('tbody', {}, el('tr', {}, el('td', { text: 'mm' }), el('td'), el('td'), el('td', { class: 'hl' }))));
        PB.table = el('div', {});
        PB.fit = el('div', {});
        PB.add = el('button', { type: 'button', class: 'btn btn--primary btn--block', id: 'btn-blank-add' }, IL.icon('i-capture'), el('span', { class: 'lbl', text: 'Add point · N1 tip touching' }));
        PB.save = btn('Save points', { class: 'btn btn--block', id: 'btn-blank-save' });
        PB.reason = el('div', { class: 'reason', hidden: true });
        PB.add.addEventListener('click', () => {
          const n1 = IL.positions(S.status).n1;
          if (!n1) return;
          PB.points.push({ x: Number(n1.x.toFixed(3)), y: Number(n1.y.toFixed(3)), z: Number(n1.z.toFixed(3)) });
          IL.render();
        });
        PB.save.addEventListener('click', () => IL.act(PB.save, async () => {
          if (PB.points.length < 3) throw bad('At least 3 points spanning an area.');
          const r = await IL.api('PUT', '/api/surfaces/' + encodeURIComponent(PB.name), { points: PB.points.map((q) => [q.x, q.y, q.z]), note: 'probed in the web UI' });
          const n = PB.points.length;
          PB.points = [];
          IL.planRefreshSoon();
          setTimeout(() => IL.panel.close(), 0);
          return n;
        }, { refusalIn: p.foot, ok: (n) => 'Surface ' + PB.name + ' saved from ' + n + ' points.' }));
        p.body.append(PB.banner,
          el('p', { class: 'panel__lead', text: "Jog N1 over a spot on the " + name + " in OpenPnP's window and lower it until the tip touches, then add the point. Spread at least 3 points (5 is better) around the purge row and the places on it." }),
          PB.live, PB.table, PB.fit);
        p.foot.append(el('div', { class: 'stack su-pb-foot', 'data-refusals': '' }, PB.add, PB.save), PB.reason);
      }
      IL.keyed(PB.banner, PB.points.length ? 'n' : 'e' + saved.length, () => (PB.points.length ? [el('b', { text: 'New probe: ' }), 'saving replaces the ' + IL.plural(saved.length, 'saved point') + '.']
        : [el('b', { text: 'Probing from empty. ' }), 'Saving replaces the ' + IL.plural(saved.length, 'saved point') + '. ',
          saved.length ? el('button', { type: 'button', class: 'link', text: 'Start from the saved points', onclick: () => { PB.points = saved.map((q) => Object.assign({}, q)); IL.render(); } }) : null]));
      const n1 = IL.positions(s).n1;
      const cells = PB.live.tBodies[0].rows[0].cells;
      IL.setText(cells[1], n1 ? fmt(n1.x, 2) : '—');
      IL.setText(cells[2], n1 ? fmt(n1.y, 2) : '—');
      IL.setText(cells[3], n1 ? fmt(n1.z, 2) : '—');
      const fit = planeFit(PB.points);
      IL.keyed(PB.table, JSON.stringify(PB.points), () => (PB.points.length ? el('table', { class: 'tbl su-pts' },
        el('thead', {}, el('tr', {}, ['#', 'X', 'Y', 'Z', 'Resid.', ''].map((h, i) => el('th', { class: i && i < 5 ? 'r' : null, text: h })))),
        el('tbody', {}, PB.points.map((q, i) => el('tr', {}, el('td', { text: String(i + 1) }), el('td', { class: 'r', text: fmt(q.x, 2) }), el('td', { class: 'r', text: fmt(q.y, 2) }),
          el('td', { class: 'r', text: fmt(q.z, 3) }),
          el('td', { class: 'r' + (fit && Math.abs(fit.residuals[i]) > surfLimit() ? ' su-bad' : ''), text: fit ? IL.fmtSigned(fit.residuals[i], 3) : '—' }),
          el('td', {}, el('button', { type: 'button', class: 'btn btn--sm btn--icon btn--quiet', title: 'Delete point ' + (i + 1), 'aria-label': 'Delete point ' + (i + 1),
            onclick: () => { PB.points.splice(i, 1); IL.render(); } }, IL.icon('i-close'))))))) : null));
      const locs = d.locations || {};
      const cov = fit ? coverage({ points: PB.points }, pr, Object.keys(locs).filter((n) => n !== 'waste-test-dot').map((n) => ({ name: n, loc: locs[n] }))) : null;
      IL.keyed(PB.fit, JSON.stringify(PB.points) + '|' + JSON.stringify([pr.spots, pr.x0Mm, pr.y0Mm]), () => el('div', { class: 'tiles su-pb-tiles' },
        tile('Points', String(PB.points.length), '', PB.points.length < 3 ? 'at least 3' : 'enough for a plane'),
        tile('Worst', fit ? fmt(fit.worst, 3) : '—', 'mm', 'limit ' + fmt(surfLimit(), 2) + ' mm', fit && fit.worst > surfLimit()),
        tile('Tilt', fit ? fmt(fit.slope * 1000, 0) : '—', 'µm/mm', fit ? 'Z ' + fmt(fit.minZ, 2) + ' to ' + fmt(fit.maxZ, 2) : ''),
        tile('Covers', cov ? (cov.spots ? (cov.spots - cov.spotsOut) + '/' + cov.spots : '—') : '—', 'spots', cov ? (cov.placesIn.length ? cov.placesIn.join(', ') : 'no place') : '', cov && cov.spotsOut > 0)));
      IL.gate(PB.add, IL.lockReason(s, { connected: true, idle: false, pressureOff: false }) || (n1 ? null : 'No N1 position'));
      setLabel(PB.add, 'Add point ' + (PB.points.length + 1) + ' · N1 tip touching');
      const lock = IL.lockReason(s, { pressureOff: false, engine: true });
      IL.gate(PB.save, lock || (PB.points.length < 3 ? 'Add at least 3 points first' : null), PB.reason);
      setLabel(PB.save, PB.points.length >= 3 ? 'Save ' + PB.points.length + ' points' : 'Save points');
      PB.save.classList.toggle('btn--primary', PB.points.length >= 3);
      PB.add.classList.toggle('btn--primary', PB.points.length < 3);
    },
  });
  function tile(k, n, u, sub, warn) {
    return el('div', { class: 'tile' + (warn ? ' su-tile--warn' : '') }, el('div', { class: 'tile__k', text: k }),
      el('div', { class: 'tile__v' }, el('span', { class: 'n', text: n }), u ? el('span', { class: 'u', text: u }) : null), sub ? el('div', { class: 'tile__s', text: sub }) : null);
  }

  // ================================================================================== 3 Purge row
  function buildPurge() {
    const c = C.purge = card('purge', 'Purge row', [abbr('I9', 'Interlock I9: purge row set with enough unused spots'), ' · spots on the blank · ', el('span', { class: 'su-pr-per', text: '— per board' })],
      { stateId: 'pr-state', bodyCls: 'su-col' });
    c.per = c.root.querySelector('.su-pr-per');
    c.strip = el('div', { class: 'su-strip' });
    c.kv = el('dl', { class: 'kv su-kv', id: 'pr-kv' });
    F.prSurf = fld(el('select', { class: 'input', id: 'pr-surface' }));
    F.prSurf.kind = 'select';
    F.prX = fld(input('pr-x0'), { d: 2 });
    F.prY = fld(input('pr-y0'), { d: 2 });
    F.prPx = fld(input('pr-pitchx'), { d: 2 });
    F.prPy = fld(input('pr-pitchy'), { d: 2 });
    F.prN = fld(input('pr-spots', { inputmode: 'numeric' }), {});
    c.cap = capture('btn-pr-usexy', 'Use live N2 X/Y');
    c.cap.addEventListener('click', () => { const n = IL.positions(S.status).n2; if (n) { setVal(F.prX, n.x.toFixed(2)); setVal(F.prY, n.y.toFixed(2)); } });
    c.body.setAttribute('data-enter-submits', 'btn-pr-save');
    c.body.append(c.strip, c.kv,
      el('div', { class: 'fgrid' },
        field('First spot X', 'mm', F.prX.el), field('First spot Y', 'mm', F.prY.el),
        field('Pitch X · Y', 'mm', el('div', { class: 'row' }, F.prPx.el, F.prPy.el), { for: 'pr-pitchx', hint: 'at least 1.5 mm between spots' }),
        field('Spots', 'count', F.prN.el),
        field('Surface the spots lie on', null, F.prSurf.el, { cls: 'span2' })),
      c.cap);
    c.bSave = btn('Save purge row', { id: 'btn-pr-save' });
    c.bReset = btn('Wiped: reset row', { id: 'btn-pr-reset', title: 'All spots are free again: press only after wiping the row.' }, 'i-wipe');
    footReason(c);
    c.foot.append(c.bSave, c.reason, el('span', { class: 'spacer' }), c.bReset);
    c.bSave.addEventListener('click', () => IL.act(c.bSave, async () => {
      const old = disp(S.status).purgeRow || {};
      const row = { surfaceName: val(F.prSurf), x0Mm: need(F.prX, 'the first spot X'), y0Mm: need(F.prY, 'the first spot Y'), pitchXMm: need(F.prPx, 'the pitch X'),
        pitchYMm: need(F.prPy, 'the pitch Y'), spots: need(F.prN, 'the number of spots') };
      if (!Number.isInteger(row.spots) || row.spots < 0) throw bad('Spots is a whole number.');
      await IL.api('PUT', '/api/config', { purgeRow: row });
      clean(F.prSurf, F.prX, F.prY, F.prPx, F.prPy, F.prN);
      IL.loadConfig();
      IL.planRefreshSoon();
      const moved = old.usedSpots > 0 && (old.surfaceName !== row.surfaceName || old.x0Mm !== row.x0Mm || old.y0Mm !== row.y0Mm);
      if (moved) askMovedRow(old.usedSpots);
      return row;
    }, { ok: 'Purge row saved', refusalIn: c.ref }));
    IL.press2(c.bReset, 'reset row', async () => {
      const r = await IL.api('POST', '/api/purge-row/reset');
      IL.loadConfig();
      IL.planRefreshSoon();
      return r;
    }, { ok: (r) => 'Purge row reset: ' + (r && isNum(r.spotsLeft) ? r.spotsLeft + ' spots free' : 'all spots free') + '.', refusalIn: c.ref });
    return c.root;
  }
  /** "The row moved. Is the new row clean?" as an in-page dialog (STOP stays live). */
  function askMovedRow(used) {
    const yes = btn('Yes, clean: free all spots');
    const no = btn('No, keep the count');
    const h = IL.dialog.open({ id: 'dlg-purge-moved', size: 'sm', eyebrow: 'Purge row', title: 'The row moved. Is the new row clean?',
      body: [el('p', { text: 'The old row had ' + IL.plural(used, 'used spot') + '. If the new row is clean, all its spots are used again from the first one. If unsure, keep the count and wipe it first.' }),
        el('div', { class: 'su-dlg-ref', 'data-refusals': '' })],
      foot: [el('span', { class: 'spacer' }), no, yes], initialFocus: null });
    no.addEventListener('click', () => h.close('no'));
    yes.addEventListener('click', () => IL.act(yes, async () => {
      const r = await IL.api('POST', '/api/purge-row/reset');
      IL.loadConfig();
      IL.planRefreshSoon();
      h.close('yes');
      return r;
    }, { ok: 'Purge row: all spots free on the new row.', refusalIn: h.body.querySelector('.su-dlg-ref') }));
  }
  function renderPurge(s, m) {
    const c = C.purge;
    const d = disp(s), pr = d.purgeRow || {};
    const step = m.steps.find((x) => x.key === 'purge');
    IL.setText(c.per, isNum(pr.perBoard) ? pr.perBoard + '\u00a0per\u00a0board' : '— per board');
    cardState(c, step.state, !pr.defined ? 'Not set' : pr.surfaceProbed === false ? 'Surface not probed' : pr.spots < pr.perBoard ? 'Too short: ' + pr.spots + ' spots, ' + pr.perBoard + ' per board'
      : pr.spotsLeft + ' of ' + pr.spots + ' left', step.detail);
    IL.keyed(c.strip, JSON.stringify([pr.defined, pr.spots, pr.usedSpots, pr.perBoard]), () => (pr.defined && pr.spots ? purgeStrip(pr, true)
      : el('div', { class: 'notebox notebox--block', text: 'Not set: board runs are refused (I9). Enter the first spot, pitch and number of spots on the blank, then save.' })));
    const next = pr.defined ? { x: pr.x0Mm + pr.usedSpots * pr.pitchXMm, y: pr.y0Mm + pr.usedSpots * pr.pitchYMm } : null;
    IL.kv(c.kv, [
      ['Spots', pr.defined ? pr.usedSpots + ' used · ' + pr.spotsLeft + ' free · ' + IL.plural(pr.boardsLeft || 0, 'board') + ' left' : '—'],
      ['Next spot', next && pr.spotsLeft > 0 ? 'X ' + fmt(next.x, 2) + ' · Y ' + fmt(next.y, 2) + ' mm' : pr.defined ? 'none: wipe and reset' : '—'],
      ['Last wiped', pr.resetAtMs ? tm(pr.resetAtMs) + ' · ' + IL.fmtAgo(pr.resetAtMs, IL.serverNow()) : 'never'],
      ['Surface', (pr.surfaceName || '—') + (pr.surfaceProbed ? ' · probed' : pr.surfaceName ? ' · NOT probed: probe the blank' : '')],
    ]);
    // surface options: probed surfaces + the configured one + the blank place name
    const names = Object.keys(d.surfaces || {});
    for (const n of [pr.surfaceName, d.blankLocationName]) if (n && !names.includes(n)) names.push(n);
    const sel = F.prSurf.el, ok = names.join('|');
    if (sel.dataset.opts !== ok) {
      const keep = sel.value;
      sel.dataset.opts = ok;
      sel.replaceChildren(...names.map((n) => el('option', { value: n, text: n + ((d.surfaces || {})[n] ? '' : ' (not probed)') })));
      if (keep && names.includes(keep)) sel.value = keep;
    }
    sync(F.prSurf, pr.surfaceName || '');
    sync(F.prX, pr.x0Mm);
    sync(F.prY, pr.y0Mm);
    sync(F.prPx, pr.pitchXMm);
    sync(F.prPy, pr.pitchYMm);
    sync(F.prN, pr.spots);
    const n2 = IL.positions(s).n2;
    IL.setText(c.cap.liveEl, n2 ? fmt(n2.x, 2) + ', ' + fmt(n2.y, 2) : '—');
    IL.gate(c.cap, IL.lockReason(s, { connected: true, idle: false, pressureOff: false }) || (n2 ? null : 'No needle position'));
    const lock = IL.lockReason(s, { pressureOff: false, engine: true });
    const any = [F.prSurf, F.prX, F.prY, F.prPx, F.prPy, F.prN].some(dirty);
    IL.gate(c.bSave, lock || (any ? null : 'No unsaved changes'));
    c.bSave.classList.toggle('btn--primary', any && !lock);
    IL.gate(c.bReset, !pr.defined ? 'The purge row is not set' : IL.lockReason(s));
    footReason(c, lock || (any ? 'Unsaved: Save purge row' : 'No unsaved changes'), any && !lock ? 'need' : null);
  }

  // ================================================================================== 4 Pressure and paste flow
  function buildPressure() {
    const c = C.pressure = card('pressure', 'Pressure and paste flow', 'hold for the gauge · prime · test dot', { stateId: 'pressure-state', bodyCls: 'su-col' });
    c.regBig = el('div', { class: 'su-reg__big' });
    c.regD = el('div', { class: 'su-reg__d' });
    c.reads = IL.fixButton({ fix: 'attest-regulator', label: 'Reads … psi' }, { cls: 'btn--sm' });
    c.who = el('span', { class: 'who' });
    c.waste = el('div', { class: 'tick su-waste' }, IL.glyph('wait', 'sm'), el('div', {}, el('div', { class: 'tick__t' }), el('div', { class: 'tick__d' })));
    c.bOn = btn('Pressure on', { id: 'btn-pressure-on', class: 'btn btn--primary', title: 'Pump on, wait, then the valve (the barrel is under pressure)' }, 'i-gauge');
    c.bOff = btn('Pressure off', { id: 'btn-pressure-off', title: 'Valve off (the barrel vents), then the pump' });
    c.onReason = el('div', { class: 'reason', hidden: true });
    c.note = el('div', { class: 'su-note' });
    F.prime = fld(input('prime-rot'), { d: 2 });
    F.testdot = fld(input('testdot-rot'), { d: 2 });
    c.bPrime = btn('Prime', { id: 'btn-prime', title: 'Pressure on, the screw turns this far at the waste, vent (one session)' });
    c.bTest = btn('Test dot', { id: 'btn-testdot', title: 'One dot next to the waste (waste-test-dot) with the profile, then vent' });
    c.flowReason = el('div', { class: 'reason', hidden: true });
    c.result = el('div', { class: 'su-result', id: 'dispense-result', 'aria-live': 'polite' });
    c.body.append(
      el('div', { class: 'su-reg' }, IL.icon('i-gauge'), el('div', {}, c.regBig, c.regD, el('div', { class: 'su-reg__row' }, c.reads, c.who))),
      c.waste,
      el('div', { class: 'row su-pr-btns', 'data-refusals': '' }, c.bOn, c.bOff), c.onReason, c.note,
      el('div', { class: 'flowrows' },
        el('label', { class: 'flowrows__l', for: 'prime-rot' }, 'Prime at the waste', el('small', { text: 'screw turn, °' })), F.prime.el, c.bPrime,
        el('label', { class: 'flowrows__l', for: 'testdot-rot' }, 'Test dot', el('small', { text: '1 profile dot, °' })), F.testdot.el, c.bTest),
      c.flowReason, c.result);
    c.foot.remove();
    const opts = { refusalIn: c.ref };
    IL.press2(c.bOn, (s) => { const dist = IL.wasteDistance(s); return dist !== null && dist <= L.wasteNearMm ? null : 'pressure on'; },
      () => IL.api('POST', '/api/pressure/start'), Object.assign({ ok: (r) => (r && (r.note || r.lastEvent)) || 'Pressure switching on' }, opts));
    c.bOff.addEventListener('click', () => IL.act(c.bOff, () => IL.api('POST', '/api/pressure/stop'), Object.assign({ ok: (r) => (r && (r.note || r.lastEvent)) || 'Pressure off' }, opts)));
    IL.press2(c.bPrime, 'prime', async () => {
      const rot = need(F.prime, 'the prime rotation in degrees');
      const r = await IL.api('POST', '/api/prime', { rotationDeg: rot });
      return r;
    }, Object.assign({ ok: (r) => 'Prime started (' + fmt(r && r.rotationDeg, 2) + '°).' }, opts));
    IL.press2(c.bTest, 'test dot', async () => {
      const rot = need(F.testdot, 'the test dot rotation in degrees');
      const r = await IL.api('POST', '/api/test-dot', { rotationDeg: rot });
      return r;
    }, Object.assign({ ok: (r) => 'Test dot started (' + fmt(r && r.rotationDeg, 1) + '°)' + (r && r.location ? ' at X ' + fmt(r.location.x, 2) + ' Y ' + fmt(r.location.y, 2) : '') + '.' }, opts));
    return c.root;
  }
  /** #pressure-state text (starts with "Off" or "ON" for the tests) and its kind. */
  function pressureState(s) {
    const pr = s.pressure || {}, d = disp(s), run = s.run;
    const lim = isNum(d.maxContinuousOnTimeS) ? IL.fmtClock(d.maxContinuousOnTimeS) : null;
    switch (pr.state) {
      case 'vent_failed': return { kind: 'block', text: 'NOT off', title: 'Pressure is NOT off: the valve did not confirm. ' + (pr.lastError || '') };
      case 'starting': return { kind: 'live', text: 'Switching on…' };
      case 'stopping': return { kind: 'live', text: 'Switching off…' };
      case 'on': {
        const on = pr.onSinceMs ? (IL.serverNow() - pr.onSinceMs) / 1000 : 0;
        const left = isNum(pr.remainingS) ? pr.remainingS - (Date.now() - S.statusAt) / 1000 : null;
        return { kind: 'live', text: 'ON ' + IL.fmtClock(on) + (left !== null ? ' · off in ' + IL.fmtClock(left) : lim ? ' · auto-off ' + lim : ''), title: 'Held by hand. Switches off by itself after ' + (lim || '?') + ' on (I7).' };
      }
      default: break;
    }
    if (run && run.running && IL.PRESSURE_PHASES.has(run.phase)) return { kind: 'live', text: 'ON · held by the ' + (run.label || 'task'), title: 'The running task switches the pressure on and off by itself.' };
    return { kind: 'off', text: 'Off · vented', title: 'Valve closed, barrel vented.' };
  }
  function renderPressure(s, m) {
    const c = C.pressure;
    const d = disp(s), pr = s.pressure || {}, prof = d.profile || {}, run = s.run;
    const ps = pressureState(s);
    cardState(c, ps.kind, ps.text, ps.title);
    const psi = IL.fmtPsi(prof.pressurePsi);
    IL.setText(c.regBig, 'Set the regulator to ' + psi + ' psi');
    IL.setText(c.regD, 'profile ' + (prof.name || '—') + ' · 16 psi starved the screw, 20 psi drooled');
    const reg = IL.attest.valid('regulator', s);
    setLabel(c.reads, reg ? 'Read again: ' + psi + ' psi' : 'The gauge reads ' + psi + ' psi');
    IL.setText(c.who, reg ? 'you, ' + IL.fmtTime(reg.atMs) : 'no sensor: read the gauge');
    c.who.classList.toggle('muted', !reg);
    c.reads.classList.toggle('btn--primary', !reg && pr.state === 'on');
    // needle over the waste
    const wn = d.wasteLocationName || 'waste';
    const dist = IL.wasteDistance(s);
    const near = dist !== null && dist <= L.wasteNearMm;
    const [g, txt] = c.waste.children;
    const hasWaste = !!(d.locations || {})[wn];
    IL.setGlyph(g, !hasWaste ? 'need' : dist === null ? 'wait' : near ? 'ok' : 'block', 'sm');
    IL.setText(txt.firstChild, !hasWaste ? "No '" + wn + "' place yet: set it in Places below" : dist === null ? 'Needle position unknown'
      : near ? "Needle over the waste: " + fmt(dist, 1) + " mm from '" + wn + "'" : 'Needle is not over the waste (' + fmt(dist, 0) + ' mm away): park N2 over the waste in OpenPnP first');
    IL.setText(txt.lastChild, near || !hasWaste ? 'The hold opens the valve. Never over a camera or the board.' : 'Pressure on then needs two presses.');
    c.waste.classList.toggle('su-waste--bad', hasWaste && dist !== null && !near);
    // pressure buttons
    const holding = pr.state && pr.state !== 'off';
    const lockOn = holding ? (pr.state === 'vent_failed' ? 'Pressure is NOT off: press Pressure off' : 'Pressure is already on') : IL.lockReason(s, { connected: true, homed: true, engine: true });
    IL.gate(c.bOn, lockOn);
    IL.setText(c.onReason, !holding && lockOn ? lockOn : '');
    IL.show(c.onReason, !holding && !!lockOn);
    IL.gate(c.bOff, holding ? null : 'Pressure is off');
    c.bOff.classList.toggle('btn--primary', !!holding);
    c.bOn.classList.toggle('btn--primary', !holding);
    const lim = isNum(d.maxContinuousOnTimeS) ? IL.fmtClock(d.maxContinuousOnTimeS) : '—';
    IL.setText(c.note, 'Switches off by itself after ' + lim + ' on (I7). Prime, test dot and runs are locked while it is on.');
    // prime / test dot
    sync(F.prime, d.primeRotationDeg);
    sync(F.testdot, d.testDot ? d.testDot.defaultRotationDeg : null);
    const lockFlow = IL.lockReason(s, { connected: true, homed: true, engine: true }) || (hasWaste ? null : "No '" + wn + "' place: set it in Places");
    IL.gate(c.bPrime, lockFlow);
    IL.gate(c.bTest, lockFlow);
    IL.setText(c.flowReason, lockFlow || '');
    IL.show(c.flowReason, !!lockFlow && !(c.onReason.textContent === lockFlow && !c.onReason.hidden));
    renderDispenseResult(s);
  }
  function renderDispenseResult(s) {
    const c = C.pressure;
    const run = s.run;
    if (run && (run.label === 'prime' || run.label === 'test dot')) W.lastRun = run;
    const r = W.lastRun;
    const key = r ? [r.runId, r.running, r.status, r.phase, Math.floor(r.elapsedS || 0)].join('|') : 'none';
    const seenKey = r ? r.label === 'prime' ? 'prime-seen' : 'test-dot-seen' : null;
    const seen = seenKey ? IL.attest.valid(seenKey, s) : null;
    const seenAfter = seen && r && r.finishedAtMs && seen.atMs >= r.finishedAtMs - S.skewMs - 1000;
    IL.keyed(c.result, key + '|' + !!seenAfter, () => {
      if (!r) return el('span', { class: 'muted', text: 'Prime and test dot run at the waste: pressure on, the screw turns, vent. Nothing else moves the board.' });
      const word = r.label === 'prime' ? 'Prime' : 'Test dot';
      if (r.running) return [IL.glyph('live', 'xs'), el('span', { text: word + ' running · ' + (r.phase || '') + (isNum(r.elapsedS) ? ' · ' + IL.fmtClock(r.elapsedS) : '') })];
      const st = { OK: 'finished', ABORTED: 'stopped', ERROR: 'ended by an error', REFUSED: 'refused' }[r.status] || String(r.status || '').toLowerCase();
      const kind = r.status === 'OK' ? 'ok' : r.status === 'REFUSED' ? 'need' : 'block';
      return [IL.glyph(kind, 'xs'), el('span', { text: word + ' ' + tm(r.finishedAtMs) + ': ' + st + (r.boardName ? ' at ' + r.boardName : '') + (r.message ? ': ' + r.message : '') + '.' }),
        r.status === 'OK' ? (seenAfter ? el('span', { class: 'tag tag--you', text: 'you saw it' })
          : el('button', { type: 'button', class: 'btn btn--sm', text: r.label === 'prime' ? 'I saw paste come out' : 'I saw the dot',
            onclick: () => { IL.attest.set(seenKey, true); IL.toast('Noted: ' + (r.label === 'prime' ? 'paste came out' : 'the test dot came out') + ' (you, ' + IL.fmtTime(Date.now()) + ').', 'ok'); } })) : null];
    });
  }

  // ================================================================================== 5 Places
  const P = { rows: new Map(), added: [], menu: null };
  function buildPlaces() {
    const c = C.places = card('places', 'Places', 'X/Y of the needle (N2) over the spot · Z = N1 nozzle touching the surface', { span: true });
    c.map = el('div', { class: 'su-bedmap-box' });
    c.tbody = el('tbody');
    c.table = el('table', { class: 'ptable', id: 'places-table' },
      el('thead', {}, el('tr', {}, ['Place', 'X', 'Y', 'Z', 'Used for', ''].map((h) => el('th', {}, h, /^[XYZ]$/.test(h) ? [' ', el('span', { class: 'unit-lc', text: 'mm' })] : null)))), c.tbody);
    c.body.append(c.map, el('div', { class: 'su-ptable-wrap' }, c.table));
    c.newName = el('input', { class: 'input', id: 'place-new', type: 'text', placeholder: 'new place name', autocomplete: 'off', spellcheck: 'false', maxlength: '40' });
    c.bAdd = btn('Add place', { id: 'btn-place-add' }, 'i-plus');
    c.note = el('span', { class: 'su-note' });
    c.foot.setAttribute('data-enter-submits', 'btn-place-add');
    c.foot.append(c.newName, c.bAdd, el('span', { class: 'spacer' }), c.note);
    c.bAdd.addEventListener('click', () => IL.act(c.bAdd, async () => {
      const name = String(c.newName.value).trim();
      if (!PLACE_RE.test(name)) throw bad('A place name uses letters, digits, - and _ only (at most 40).');
      if (name === 'waste-test-dot') throw bad("'waste-test-dot' follows the waste place and cannot be set.");
      if (!P.rows.has(name)) P.added.push(name);
      c.newName.value = '';
      renderPlaces(S.status);
      const row = P.rows.get(name);
      if (row) row.x.el.focus();
    }, { refusalIn: c.ref }));
    // Enter in a row's field saves that row only
    c.tbody.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || e.isComposing || !e.target.matches('input')) return;
      const tr = e.target.closest('tr[data-place]');
      const b = tr && tr.querySelector('button[data-action=save]');
      e.preventDefault();
      if (b && !b.disabled) b.click();
    });
    return c.root;
  }
  function usedFor(name, d) {
    if (name === (d.wasteLocationName || 'waste')) return 'prime, test dot';
    if (name === (d.blankLocationName || 'blank')) return 'touch-off, dot ladder';
    return 'scale shots, calibrations';
  }
  function placeRow(name) {
    const r = { name };
    const inp = (axis) => el('input', { class: 'input', 'data-axis': axis, type: 'text', inputmode: 'decimal', autocomplete: 'off', 'aria-label': name + ' ' + axis.toUpperCase() + ' in mm' });
    r.x = fld(inp('x'), { d: 2 });
    r.y = fld(inp('y'), { d: 2 });
    r.z = fld(inp('z'), { d: 2 });
    r.use = el('td', { class: 'use' });
    r.from = el('button', { type: 'button', class: 'btn btn--sm su-from', 'aria-haspopup': 'menu', 'aria-expanded': 'false', title: 'Take X/Y or Z from the live head position' }, 'From head ▾');
    r.save = el('button', { type: 'button', class: 'btn btn--sm', 'data-action': 'save', text: 'Saved' });
    r.remove = el('button', { type: 'button', class: 'btn btn--sm btn--icon btn--quiet', title: 'Remove this unsaved row', 'aria-label': 'Remove ' + name, hidden: true }, IL.icon('i-close'));
    r.wrap = el('span', { class: 'su-menuwrap' }, r.from);
    r.tr = el('tr', { 'data-place': name }, el('td', {}, el('b', { text: name })), el('td', {}, r.x.el), el('td', {}, r.y.el), el('td', {}, r.z.el), r.use,
      el('td', { class: 'acts' }, r.wrap, r.save, r.remove));
    r.from.addEventListener('click', (e) => { e.stopPropagation(); toggleMenu(r); });
    r.remove.addEventListener('click', () => { P.added = P.added.filter((n) => n !== name); renderPlaces(S.status); });
    // a Z far from the needle Z frame saves only on a second press (prime, test dot and touch-off there would be refused)
    IL.press2(r.save, () => (placeZOff(opt(r.z)) !== null ? 'save Z anyway' : null), async () => {
      const body = { x: need(r.x, name + ' X'), y: need(r.y, name + ' Y'), z: need(r.z, name + ' Z') };
      const res = await IL.api('PUT', '/api/locations/' + encodeURIComponent(name), body);
      clean(r.x, r.y, r.z);
      P.added = P.added.filter((n) => n !== name);
      IL.planRefreshSoon();
      return res;
    }, { ok: 'Saved place ' + name, refusalIn: C.places.ref, what: name });
    return r;
  }
  /** mm a place Z lies from the needle Z frame surface when beyond its tolerance (zFrameToleranceMm, I4), else null. */
  function placeZOff(z) {
    const a = (disp(S.status).needle || {}).anchor;
    const tol = S.config && isNum(S.config.zFrameToleranceMm) ? Number(S.config.zFrameToleranceMm) : 1.0;
    if (!a || !isNum(a.surfaceZMm) || !isNum(z)) return null;
    const dz = Number(z) - a.surfaceZMm;
    return Math.abs(dz) > tol ? dz : null;
  }
  function placeZText(name, z) {
    const a = (disp(S.status).needle || {}).anchor;
    const dz = placeZOff(z);
    const tol = S.config && isNum(S.config.zFrameToleranceMm) ? Number(S.config.zFrameToleranceMm) : 1.0;
    return dz === null ? null : name + ' Z ' + fmt(z, 2) + ' mm is ' + fmt(Math.abs(dz), 2) + ' mm ' + (dz > 0 ? 'above' : 'below') + ' the needle Z frame surface '
      + fmt(a.surfaceZMm, 2) + ' mm (limit ±' + fmt(tol, 2) + '): prime, test dot and touch-off there are refused. Retake Z with N1 touching.';
  }
  function closeMenu() {
    if (!P.menu) return;
    P.menu.el.remove();
    P.menu.row.from.setAttribute('aria-expanded', 'false');
    if (P.menu.escOff) P.menu.escOff();
    document.removeEventListener('pointerdown', P.menu.outside, true);
    P.menu = null;
  }
  function toggleMenu(r) {
    if (P.menu && P.menu.row === r) { closeMenu(); return; }
    closeMenu();
    const s = S.status, d = disp(s), nd = d.needle || {};
    const pos = IL.positions(s);
    const item = (label, value, fn, why) => el('button', { type: 'button', role: 'menuitem', class: 'su-menu__i', disabled: why ? true : null, title: why || null,
      onclick: () => { fn(); closeMenu(); r.save.focus(); } }, el('span', { text: label }), el('b', { text: why ? why : value }));
    const menu = el('div', { class: 'su-menu', role: 'menu', 'aria-label': 'From the head' },
      item('N2 X/Y', pos.n2 ? fmt(pos.n2.x, 2) + ', ' + fmt(pos.n2.y, 2) : '—', () => { setVal(r.x, pos.n2.x.toFixed(2)); setVal(r.y, pos.n2.y.toFixed(2)); }, pos.n2 ? null : 'no position'),
      item('N1 Z (tip touching)', pos.n1 ? fmt(pos.n1.z, 2) : '—', () => setVal(r.z, pos.n1.z.toFixed(3)), pos.n1 ? null : 'no position'),
      item('N2 Z (tip touching)', pos.n2 && isNum(nd.zOffsetMm) ? fmt(pos.n2.z - nd.zOffsetMm, 2) : '—', () => setVal(r.z, (pos.n2.z - nd.zOffsetMm).toFixed(3)),
        !nd.anchored ? 'needs the needle Z frame' : pos.n2 ? null : 'no position'));
    r.wrap.appendChild(menu);
    r.from.setAttribute('aria-expanded', 'true');
    const first = menu.querySelector('button:not([disabled])');
    if (first) first.focus();
    P.menu = { el: menu, row: r, outside: (e) => { if (!r.wrap.contains(e.target)) closeMenu(); } };
    P.menu.escOff = IL.onEsc(() => { const was = P.menu && P.menu.row; closeMenu(); if (was) was.from.focus(); return true; });
    document.addEventListener('pointerdown', P.menu.outside, true);
  }
  function renderPlaces(s) {
    const c = C.places;
    if (!s) return;
    const d = disp(s), locs = d.locations || {}, nd = d.needle || {};
    const wn = d.wasteLocationName || 'waste', bn = d.blankLocationName || 'blank';
    const names = [];
    for (const n of [wn, bn].concat(Object.keys(locs), P.added)) if (n !== 'waste-test-dot' && !names.includes(n)) names.push(n);
    // keep unsaved rows of places that disappeared
    for (const [n, r] of P.rows) if (!names.includes(n) && (dirty(r.x) || dirty(r.y) || dirty(r.z))) names.push(n);
    const wantDerived = !!locs[wn];
    const order = [];
    for (const n of names) { order.push(n); if (n === wn && wantDerived) order.push('#derived'); }
    // rows (built once per name, patched after)
    for (const n of names) if (!P.rows.has(n)) P.rows.set(n, placeRow(n));
    for (const [n, r] of P.rows) {
      if (names.includes(n)) continue;
      if (P.menu && P.menu.row === r) closeMenu();
      r.tr.remove();
      P.rows.delete(n);
      for (const f of [r.x, r.y, r.z]) { const i = ALL.indexOf(f); if (i >= 0) ALL.splice(i, 1); }
    }
    if (!c.derived) {
      c.derived = el('tr', { class: 'derived', 'data-derived': 'waste-test-dot' }, el('td', {}, el('b', { text: 'waste-test-dot' })), el('td'), el('td'), el('td'),
        el('td', { class: 'use', text: 'test dot' }), el('td', { class: 'acts' }, el('span', { class: 'su-note' })));
    }
    const want = order.map((n) => (n === '#derived' ? c.derived : P.rows.get(n).tr));
    const cur = Array.from(c.tbody.children);
    if (want.length !== cur.length || want.some((t, i) => t !== cur[i])) {
      const a = document.activeElement;
      want.forEach((t, i) => { if (c.tbody.children[i] !== t) c.tbody.insertBefore(t, c.tbody.children[i] || null); });
      for (const t of Array.from(c.tbody.children).slice(want.length)) t.remove();
      if (a && a.isConnected && document.activeElement !== a) a.focus({ preventScroll: true });
    }
    // derived row values
    const td = locs['waste-test-dot'] || (locs[wn] && d.testDot ? { x: locs[wn].x + d.testDot.offsetXMm, y: locs[wn].y + d.testDot.offsetYMm, z: locs[wn].z } : null);
    if (td) {
      const cells = c.derived.cells;
      IL.setText(cells[1], fmt(td.x, 2));
      IL.setText(cells[2], fmt(td.y, 2));
      IL.setText(cells[3], fmt(td.z, 2));
      const ox = d.testDot ? d.testDot.offsetXMm : 0, oy = d.testDot ? d.testDot.offsetYMm : 0;
      IL.setText(cells[5].firstChild, 'follows waste ' + [ox ? IL.fmtSigned(ox, 2) + ' mm X' : '', oy ? IL.fmtSigned(oy, 2) + ' mm Y' : ''].filter(Boolean).join(', '));
    }
    // flagged place Z (I4)
    const i4 = IL.interlock(s, 'I4');
    const fm = i4 && i4.state === 'blocked' ? /place '([^']+)'/i.exec(i4.text || '') : null;
    const lock = IL.lockReason(s, { pressureOff: false, engine: true });
    let unsaved = 0;
    let farName = null;
    for (const n of names) {
      const r = P.rows.get(n);
      const loc = locs[n];
      sync(r.x, loc ? loc.x : null);
      sync(r.y, loc ? loc.y : null);
      sync(r.z, loc ? loc.z : null);
      IL.setText(r.use, usedFor(n, d));
      const isNew = !loc;
      const dd = dirty(r.x) || dirty(r.y) || dirty(r.z) || (isNew && P.added.includes(n));
      if (dd) unsaved++;
      IL.setText(r.save, dd ? 'Save' : isNew ? 'Not set' : 'Saved');
      r.save.classList.toggle('btn--primary', dd && !lock);
      IL.gate(r.save, lock || (dd ? null : isNew ? 'Enter X, Y and Z first' : 'No unsaved changes'));
      IL.show(r.remove, P.added.includes(n) && !loc);
      const farText = loc ? placeZText(n, loc.z) : null;
      if (farText && !farName) farName = n;
      const flagged = (fm && fm[1] === n) || !!farText;
      r.z.el.classList.toggle('input--invalid', !!flagged || (val(r.z) !== '' && !Number.isFinite(toNum(val(r.z)))));
      IL.setAttr(r.z.el, 'title', fm && fm[1] === n ? sentence(i4.text.split('. Place Z')[0]) + ' Retake Z with N1.' : farText);
      IL.gate(r.from, IL.lockReason(s, { idle: false, pressureOff: false }));
    }
    if (P.menu && !P.menu.el.isConnected) closeMenu();
    const missing = [wn, bn].filter((n) => !locs[n]);
    if (unsaved) cardState(c, 'need', unsaved + ' unsaved');
    else if (fm || farName) cardState(c, 'block', "Check '" + (fm ? fm[1] : farName) + "' Z", fm ? null : placeZText(farName, locs[farName].z));
    else if (missing.length) cardState(c, 'need', IL.cap(missing.join(' and ')) + ' missing');
    else cardState(c, 'ok', Object.keys(locs).filter((n) => n !== 'waste-test-dot').length + ' places set');
    IL.setText(c.note, isNum(nd.anchorOffsetMm) ? 'A Z taken with the needle is off by about ' + fmt(Math.abs(nd.anchorOffsetMm), 2) + ' mm: take Z with N1.'
      : 'Take Z with N1 touching the surface (OpenPnP frame).');
    IL.gate(c.bAdd, IL.S.online === false ? IL.lockReason(s) : null);
    renderBedmap(s);
  }

  // ---------------------------------------------------------------------------------- bed map (machine mm, +Y up)
  function renderBedmap(s) {
    const c = C.places;
    const d = disp(s), locs = d.locations || {}, pr = d.purgeRow || {};
    const n2 = IL.positions(s).n2;
    const bb = planBox(S.plan);
    const bsf = (d.surfaces || {})[pr.surfaceName || d.blankLocationName || 'blank'];
    const key = JSON.stringify([locs, bb, purgeSpots(pr).map((p) => p.used), pr.x0Mm, pr.y0Mm, pr.pitchXMm, pr.pitchYMm, bsf && bsf.setAtMs, n2 ? [fmt(n2.x, 2), fmt(n2.y, 2)] : null, S.plan && S.plan.planId]);
    IL.keyed(c.map, key, () => bedmap(d, locs, bb, pr, bsf, n2));
  }
  function bedmap(d, locs, bb, pr, bsf, n2) {
    const wn = d.wasteLocationName || 'waste';
    const shapes = [];    // {kind, x0, y0, x1, y1 (mm), label}
    const boardName = S.plan && S.plan.boardName ? S.plan.boardName.replace(/\.board\.xml$|\.xml$/, '') : 'board';
    if (bb) shapes.push({ kind: 'board', x0: bb.x0, y0: bb.y0, x1: bb.x1, y1: bb.y1, label: boardName });
    const sp = bsf ? surfPts(bsf) : [];
    if (sp.length) {
      shapes.push({ kind: 'surf', hull: hull(sp), x0: Math.min(...sp.map((p) => p.x)), x1: Math.max(...sp.map((p) => p.x)),
        y0: Math.min(...sp.map((p) => p.y)), y1: Math.max(...sp.map((p) => p.y)), label: null });
    }
    const spots = purgeSpots(pr);
    if (spots.length) {
      const xs = spots.map((p) => p.x), ys = spots.map((p) => p.y);
      shapes.push({ kind: 'purge', spots, x0: Math.min(...xs) - 1, x1: Math.max(...xs) + 1, y0: Math.min(...ys) - 1, y1: Math.max(...ys) + 1, label: 'purge row' });
    }
    const places = Object.entries(locs).filter(([, l]) => l && isNum(l.x) && isNum(l.y));
    for (const [n, l] of places) {
      const r = n === 'waste-test-dot' ? 0.8 : 1.6;
      shapes.push({ kind: n === 'waste-test-dot' ? 'testdot' : 'place', name: n, x: l.x, y: l.y, x0: l.x - r, x1: l.x + r, y0: l.y - r, y1: l.y + r, label: n === 'waste-test-dot' ? null : n });
    }
    if (n2) shapes.push({ kind: 'head', x: n2.x, y: n2.y, x0: n2.x - 2, x1: n2.x + 2, y0: n2.y - 2, y1: n2.y + 2, label: null });
    if (!shapes.length) return el('div', { class: 'su-note', text: 'The bed map appears once places, the purge row or a plan exist.' });
    // to scale, machine mm, +Y up; room around the content for labels
    const pad = 14;
    let X0 = Math.min(...shapes.map((q) => q.x0)) - pad, X1 = Math.max(...shapes.map((q) => q.x1)) + pad;
    let Y0 = Math.min(...shapes.map((q) => q.y0)) - pad, Y1 = Math.max(...shapes.map((q) => q.y1)) + pad;
    const k = Math.min(440 / (X1 - X0), 250 / (Y1 - Y0), 4);
    const minW = 240 / k, minH = 150 / k;
    if (X1 - X0 < minW) { const g = (minW - (X1 - X0)) / 2; X0 -= g; X1 += g; }
    if (Y1 - Y0 < minH) { const g = (minH - (Y1 - Y0)) / 2; Y0 -= g; Y1 += g; }
    const VW = (X1 - X0) * k, VH = (Y1 - Y0) * k;
    const X = (x) => (x - X0) * k, Y = (y) => (Y1 - y) * k;
    const svg = IL.svgEl('svg', { viewBox: '0 0 ' + VW.toFixed(0) + ' ' + VH.toFixed(0), width: VW.toFixed(0), height: VH.toFixed(0), class: 'bedmap', role: 'img',
      'aria-label': 'Bed map to scale in machine mm: board, places, purge row and the live needle position' });
    const boxes = [];      // occupied boxes (svg units): small shapes and placed labels
    const areas = [];      // board and probed area: labels never sit inside them
    for (const q of shapes) {
      q.sb = { x0: X(q.x0) - 2, x1: X(q.x1) + 2, y0: Y(q.y1) - 2, y1: Y(q.y0) + 2 };
      if (q.kind === 'board') {
        svg.append(IL.svgEl('rect', { x: X(q.x0), y: Y(q.y1), width: (q.x1 - q.x0) * k, height: (q.y1 - q.y0) * k, rx: 3, class: 'su-bm-board' }));
        areas.push(q.sb);
      } else if (q.kind === 'surf') {
        if (q.hull.length >= 3) svg.append(IL.svgEl('polygon', { points: q.hull.map((p) => X(p.x).toFixed(1) + ',' + Y(p.y).toFixed(1)).join(' '), class: 'su-bm-surf' }));
        areas.push(q.sb);
      } else if (q.kind === 'purge') {
        const pitch = Math.hypot(pr.pitchXMm || 0, pr.pitchYMm || 0) || 2.5;
        const sz = Math.max(2.5, Math.min(7, pitch * k * 0.75));
        for (const p of q.spots) svg.append(IL.svgEl('rect', { x: X(p.x) - sz / 2, y: Y(p.y) - sz / 2, width: sz, height: sz, rx: 1, class: 'su-bm-spot' + (p.used ? ' su-bm-spot--used' : '') }));
        boxes.push(q.sb);
      } else if (q.kind === 'place') {
        svg.append(IL.svgEl('circle', { cx: X(q.x), cy: Y(q.y), r: 5, class: 'su-bm-place' + (q.name === wn ? ' su-bm-place--waste' : '') }));
        q.sb = { x0: X(q.x) - 6, x1: X(q.x) + 6, y0: Y(q.y) - 6, y1: Y(q.y) + 6 };
        boxes.push(q.sb);
      } else if (q.kind === 'testdot') {
        svg.append(IL.svgEl('circle', { cx: X(q.x), cy: Y(q.y), r: 2.2, class: 'su-bm-testdot' }));
      } else if (q.kind === 'head') {
        const g = IL.svgEl('g', { class: 'su-bm-head' });
        g.append(IL.svgEl('circle', { cx: X(q.x), cy: Y(q.y), r: 6 }), IL.svgEl('line', { x1: X(q.x) - 10, y1: Y(q.y), x2: X(q.x) + 10, y2: Y(q.y) }),
          IL.svgEl('line', { x1: X(q.x), y1: Y(q.y) - 10, x2: X(q.x), y2: Y(q.y) + 10 }));
        svg.append(g);
        q.sb = { x0: X(q.x) - 10, x1: X(q.x) + 10, y0: Y(q.y) - 10, y1: Y(q.y) + 10 };
        boxes.push(q.sb);
      }
    }
    // labels outside every shape, with a leader line, never over another shape or label
    const hit = (a, b) => a.x0 < b.x1 && a.x1 > b.x0 && a.y0 < b.y1 && a.y1 > b.y0;
    const order = shapes.filter((q) => q.label).sort((a, b) => (a.kind === 'board') - (b.kind === 'board'));
    for (const q of order) {
      const tw = q.label.length * 6.4 + 6, th = 15;
      const cx = (q.sb.x0 + q.sb.x1) / 2, cy = (q.sb.y0 + q.sb.y1) / 2;
      const cands = [];
      if (q.kind === 'board') {
        cands.push([q.sb.x0, q.sb.y0 - th - 3], [q.sb.x0, q.sb.y1 + 3], [q.sb.x1 + 6, cy - th / 2], [q.sb.x0 - tw - 6, cy - th / 2]);
      } else {
        for (const dd of [10, 26, 44, 64]) {
          cands.push([cx - tw / 2, q.sb.y0 - dd - th], [cx - tw / 2, q.sb.y1 + dd], [q.sb.x1 + dd, q.sb.y0 - dd - th + 6], [q.sb.x0 - dd - tw, q.sb.y0 - dd - th + 6],
            [q.sb.x1 + dd, q.sb.y1 + dd - 6], [q.sb.x0 - dd - tw, q.sb.y1 + dd - 6], [q.sb.x1 + dd, cy - th / 2], [q.sb.x0 - dd - tw, cy - th / 2]);
        }
      }
      let lb = null;
      for (const [lx, ly] of cands) {
        const b = { x0: lx, y0: ly, x1: lx + tw, y1: ly + th };
        if (b.x0 < 2 || b.x1 > VW - 2 || b.y0 < 2 || b.y1 > VH - 2) continue;
        if (boxes.some((o) => hit(b, o)) || areas.some((o) => o !== q.sb && hit(b, o))) continue;
        lb = b;
        break;
      }
      if (!lb) continue;   // no free spot: the legend names it
      boxes.push(lb);
      if (q.kind !== 'board') {
        const ex = Math.max(lb.x0 + 2, Math.min(lb.x1 - 2, cx)), ey = cy < lb.y0 ? lb.y0 : cy > lb.y1 ? lb.y1 : (lb.y0 + lb.y1) / 2;
        const sx = Math.max(q.sb.x0, Math.min(q.sb.x1, ex)), sy = Math.max(q.sb.y0, Math.min(q.sb.y1, ey));
        svg.append(IL.svgEl('line', { x1: sx, y1: sy, x2: ex, y2: ey, class: 'su-bm-lead' }));
      }
      const t = IL.svgEl('text', { x: lb.x0 + 3, y: lb.y1 - 4, class: 'su-bm-t' });
      t.textContent = q.label;
      svg.append(t);
    }
    // legend with the numbers
    const sw = (...kids) => { const s = IL.svgEl('svg', { viewBox: '0 0 22 16', 'aria-hidden': 'true' }); s.append(...kids); return s; };
    const li = (icon, name, txt) => el('li', {}, icon, el('div', {}, el('b', { text: name }), txt ? el('span', { text: ' ' + txt }) : null));
    const items = [el('li', { class: 'su-legend__h', text: 'Machine mm · X right, Y up' })];
    if (bb) {
      const src = S.plan && S.plan.source;
      items.push(li(sw(IL.svgEl('rect', { x: 2, y: 2, width: 18, height: 12, rx: 2, class: 'su-bm-board' })), boardName,
        src && src.source === 'file' ? 'at ' + fmt(src.x, 2) + ', ' + fmt(src.y, 2) : 'job board'));
    }
    for (const [n, l] of places) {
      if (n === 'waste-test-dot') continue;
      items.push(li(sw(IL.svgEl('circle', { cx: 11, cy: 8, r: 5, class: 'su-bm-place' + (n === wn ? ' su-bm-place--waste' : '') })), n, fmt(l.x, 2) + ', ' + fmt(l.y, 2)));
    }
    if (spots.length) {
      const used = spots.filter((p) => p.used).length;
      items.push(li(sw(IL.svgEl('rect', { x: 2, y: 5, width: 5, height: 5, class: 'su-bm-spot su-bm-spot--used' }), IL.svgEl('rect', { x: 9, y: 5, width: 5, height: 5, class: 'su-bm-spot' }),
        IL.svgEl('rect', { x: 16, y: 5, width: 5, height: 5, class: 'su-bm-spot' })), 'purge row', used + ' used · ' + (spots.length - used) + ' free'));
    }
    if (sp.length) items.push(li(sw(IL.svgEl('rect', { x: 2, y: 2, width: 18, height: 12, class: 'su-bm-surf' })), 'probed ' + (pr.surfaceName || 'blank'), IL.plural(sp.length, 'point')));
    if (n2) {
      const dist = IL.wasteDistance(S.status);
      items.push(li(sw(IL.svgEl('circle', { cx: 11, cy: 8, r: 4.5, class: 'su-bm-headsw' }), IL.svgEl('line', { x1: 3, y1: 8, x2: 19, y2: 8, class: 'su-bm-headsw' }),
        IL.svgEl('line', { x1: 11, y1: 1, x2: 11, y2: 15, class: 'su-bm-headsw' })), 'needle now', fmt(n2.x, 2) + ', ' + fmt(n2.y, 2) + (dist !== null ? ' · ' + fmt(dist, 1) + ' mm from the waste' : '')));
    }
    return [svg, el('ul', { class: 'su-legend' }, items)];
  }

  // ================================================================================== 6 Top camera
  const CAM = { timer: null, inFlight: false, visible: true, url: null };
  function buildCamera() {
    const c = C.camera = card('camera', 'Top camera', 'snapshot only · no motion, no lights');
    c.img = el('img', { id: 'cam-img', alt: 'Top camera snapshot', hidden: true });
    c.tag = el('span', { class: 'camtag', hidden: true });
    c.simTag = el('span', { class: 'camtag camtag--r', text: 'simulator: static picture', hidden: true });
    c.empty = el('div', { id: 'cam-empty', class: 'su-cam-empty', text: 'No picture yet: press Snapshot (nothing moves).' });
    c.view = el('div', { class: 'camview su-camview', role: 'group', 'aria-label': 'Top camera snapshot' }, c.img, el('span', { class: 'camcross', 'aria-hidden': 'true' }), c.tag, c.simTag, c.empty);
    c.body.append(c.view);
    c.bSnap = btn('Snapshot', { id: 'btn-cam', title: 'One picture from the top camera: nothing moves, no light switches' }, 'i-camera');
    c.auto = el('input', { type: 'checkbox', id: 'cam-auto' });
    c.foot.append(c.bSnap, el('label', { class: 'check', for: 'cam-auto' }, c.auto, 'refresh every 2 s'), el('span', { class: 'spacer' }));
    c.bSnap.addEventListener('click', () => snapshot(true));
    c.auto.addEventListener('change', camSchedule);
    c.img.addEventListener('load', () => { c.img.hidden = false; c.empty.hidden = true; });
    if ('IntersectionObserver' in window) {
      new IntersectionObserver((es) => { CAM.visible = es.some((e) => e.isIntersecting); camSchedule(); }).observe(c.view);
    }
    document.addEventListener('visibilitychange', camSchedule);
    IL.on('stop', () => { if (c.auto.checked) { c.auto.checked = false; camSchedule(); } });
    return c.root;
  }
  async function snapshot(user) {
    const c = C.camera;
    if (CAM.inFlight) return;
    CAM.inFlight = true;
    if (user) { c.bSnap.disabled = true; c.bSnap.setAttribute('aria-busy', 'true'); }
    const cam = IL.positions(S.status).cam;
    try {
      const r = await fetch('/api/camera/top.jpg?w=800&t=' + Date.now(), { cache: 'no-store' });
      if (!r.ok) {
        let reason = r.status === 404 ? 'no top camera' : r.status === 503 ? 'no frame within 10 s' : r.status + ' ' + r.statusText;
        try { const j = await r.json(); reason = j.reason || reason; } catch (e) { /* not JSON */ }
        throw new Error(reason);
      }
      const blob = await r.blob();
      if (CAM.url) URL.revokeObjectURL(CAM.url);
      CAM.url = URL.createObjectURL(blob);
      c.img.src = CAM.url;
      c.tag.textContent = (cam ? 'camera X ' + fmt(cam.x, 2) + ' · Y ' + fmt(cam.y, 2) + ' · ' : '') + 'frame ' + IL.fmtTimeS(Date.now());
      c.tag.hidden = false;
    } catch (e) {
      c.img.hidden = true;
      c.img.removeAttribute('src');
      c.tag.hidden = true;
      c.empty.hidden = false;
      c.empty.textContent = 'No picture: ' + e.message + '.' + (IL.isSim(S.status) ? ' The simulator shows a static picture.' : '');
      if (c.auto.checked) { c.auto.checked = false; camSchedule(); }
      if (user) IL.toast('Camera: ' + e.message, 'need');
    } finally {
      CAM.inFlight = false;
      if (user) { c.bSnap.disabled = false; c.bSnap.removeAttribute('aria-busy'); }
    }
  }
  function camSchedule() {
    const c = C.camera;
    const on = c && c.auto.checked && W.shown && CAM.visible && !document.hidden && S.online !== false;
    if (on && !CAM.timer) { snapshot(false); CAM.timer = setInterval(() => snapshot(false), 2000); }
    else if (!on && CAM.timer) { clearInterval(CAM.timer); CAM.timer = null; }
  }
  function renderCamera(s) {
    const c = C.camera;
    IL.show(c.simTag, IL.isSim(s) && !c.img.hidden);
    IL.gate(c.bSnap, S.online === false ? IL.lockReason(s) : null);
  }

  // ================================================================================== 7 Profile and start angle
  function buildAngle() {
    const c = C.angle = card('angle', 'Profile and start angle', 'the dispense recipe · edit on Profile');
    c.kv = el('div', {});
    c.body.append(c.kv);
    const bP = btn('Open Profile', {});
    bP.appendChild(IL.icon('i-chev'));
    const bM = btn('Measure the angle', { class: 'btn btn--quiet' });
    bM.appendChild(IL.icon('i-chev'));
    bP.addEventListener('click', () => IL.go('#profile'));
    bM.addEventListener('click', () => IL.go('#calibrate/angle'));
    c.foot.append(bP, bM);
    return c.root;
  }
  function renderAngle(s, m) {
    const c = C.angle;
    const d = disp(s), prof = d.profile || {};
    const step = m.steps.find((x) => x.key === 'angle');
    const a = IL.fmtDeg(prof.startAngleDeg, 1);
    const problems = prof.problems || [];
    if (problems.length) cardState(c, 'block', 'Profile not valid', problems.join('; '));
    else cardState(c, step.state, step.state === 'ok' ? 'Angle ' + a : step.state === 'need' ? 'Angle: confirm' : step.state === 'off' ? 'Whole turns' : 'Angle: not set', step.detail);
    const parts = IL.recipeParts(prof);
    const key = JSON.stringify([prof.fingerprint, prof.name, prof.startAngleSetAtMs, prof.controllerConnectedAtMs, prof.startAngles, problems, prof.screwRpm]);
    IL.keyed(c.kv, key, () => el('dl', { class: 'kv kv--wrap' },
      el('dt', { text: 'Profile' }), el('dd', { text: prof.name || '—' }),
      el('dt', { text: 'Recipe' }), el('dd', { text: parts.slice(1, 4).join(' · ') }),
      el('dt', {}), el('dd', { text: [parts[4], isNum(prof.screwRpm) ? fmt(prof.screwRpm, 0) + ' rpm' : null, parts[5]].filter(Boolean).join(' · ') }),
      el('dt', { text: 'Start angle' }), el('dd', { text: prof.partialTurn === false ? 'whole turns: any angle' : a + (prof.startAngles ? ' · dots alternate ' + IL.startAnglesText(prof) : '') }),
      el('dt', { text: 'Set' }), el('dd', { text: (prof.startAngleSetAtMs ? tm(prof.startAngleSetAtMs) : 'never') + (prof.controllerConnectedAtMs ? ' · controller connected ' + tm(prof.controllerConnectedAtMs) : '') }),
      problems.length ? [el('dt', { text: 'Problems' }), el('dd', { class: 'su-bad', text: problems.join('; ') })] : null));
  }

  // ================================================================================== 8 Settings + advanced JSON
  const J = { base: null, edited: false, err: null };
  function buildSettings() {
    const c = C.settings = card('settings', 'Settings', 'engine limits and checks · a save sends only the keys you changed', { span: true });
    F.maxon = fld(input('set-maxon', { inputmode: 'numeric' }), {});
    F.valve = fld(input('set-valvesettle', { inputmode: 'numeric' }), {});
    F.pump = fld(input('set-pumpsettle', { inputmode: 'numeric' }), {});
    F.minstatus = fld(el('select', { class: 'input', id: 'set-minstatus' }, Object.keys(STATUS_LABELS).map((k) => el('option', { value: k, text: STATUS_LABELS[k] }))));
    F.minstatus.kind = 'select';
    F.clean = fld(el('input', { type: 'checkbox', id: 'set-clean' }));
    F.gray = fld(input('set-gray', { inputmode: 'numeric' }), {});
    F.bright = fld(input('set-minbright'), {});
    F.boxw = fld(input('set-boxw'), { d: 3 });
    F.boxh = fld(input('set-boxh'), { d: 3 });
    c.cleanLbl = el('span', { class: 'tick__t' });
    c.cleanHint = el('div', { class: 'field__h' });
    c.cleanTick = el('label', { class: 'tick su-clean', for: 'set-clean' }, F.clean.el, el('div', {}, c.cleanLbl));
    c.i10 = el('details', { class: 'disclose su-i10', 'data-enter-submits': 'btn-settings' }, el('summary', { text: 'Clean-pad check thresholds (I10)' }),
      el('div', { class: 'su-set-grid su-set-grid--4' },
        field('Gray level', '0–254', F.gray.el, { hint: 'a pixel brighter than this counts as bare copper' }),
        field('Bright share', '0–1', F.bright.el, { hint: 'a pad is clean at or above this share' }),
        field('Box width', 'mm', F.boxw.el), field('Box height', 'mm', F.boxh.el)));
    c.grid = el('div', { class: 'su-set-grid', 'data-enter-submits': 'btn-settings' },
      field([el('span', { text: 'Max pressure on-time ' }), abbr('I7', 'Interlock I7: the pressure switches off by itself after this time')], 's', F.maxon.el, { hint: 'a hold switches off by itself after this' }),
      field('Valve settle', 'ms', F.valve.el, { hint: 'valve open → first dot (valveSettleMs)' }),
      field('Pump settle', 'ms', F.pump.el, { hint: 'pump on → valve open (pumpToValveSettleMs)' }),
      field('Lowest pad-type status allowed on a board', null, F.minstatus.el, { hint: 'pads below it are not dispensed: plan again after a change' }),
      el('div', { class: 'field su-span2' }, el('div', { class: 'field__l' }, el('span', {}, 'Clean-pad check before each board (', abbr('I10', 'Interlock I10: pads clean before the pressure goes on'), ')')), c.cleanTick, c.cleanHint));
    c.setErr = el('div', { class: 'field__err su-set-err', id: 'settings-err', role: 'status', hidden: true });
    c.body.append(c.grid, c.i10, c.setErr, buildJson(c));
    c.bSave = btn('Save settings', { id: 'btn-settings' });
    c.bJson = el('button', { type: 'button', class: 'link su-json-link' }, 'All settings (advanced JSON) ›');
    c.bJson.addEventListener('click', () => { c.adv.open = true; c.adv.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); F.json.el.focus({ preventScroll: true }); });
    footReason(c);
    c.bRetry = btn('Retry', { class: 'btn btn--sm', hidden: true }, 'i-refresh');
    c.bRetry.addEventListener('click', () => IL.loadConfig());
    c.foot.append(c.bSave, c.reason, c.bRetry, el('span', { class: 'spacer' }), c.bJson);
    c.bSave.addEventListener('click', () => IL.act(c.bSave, async () => {
      const errs = settingsErrors();
      if (errs.length) throw bad(errs.join(' '));
      const patch = {};
      if (dirty(F.maxon)) patch.maxContinuousOnTimeS = need(F.maxon, 'the maximum pressure on-time in s');
      if (dirty(F.valve)) patch.valveSettleMs = need(F.valve, 'the valve settle time in ms');
      if (dirty(F.pump)) patch.pumpToValveSettleMs = need(F.pump, 'the pump settle time in ms');
      if (dirty(F.minstatus)) patch.minPadTypeStatus = val(F.minstatus);
      const cp = {};
      if (dirty(F.clean)) cp.enabled = F.clean.el.checked;
      if (dirty(F.gray)) cp.grayLevel = need(F.gray, 'the gray level (0 to 255)');
      if (dirty(F.bright)) cp.minBrightFraction = need(F.bright, 'the bright share (0 to 1)');
      if (dirty(F.boxw)) cp.boxWMm = need(F.boxw, 'the box width in mm');
      if (dirty(F.boxh)) cp.boxHMm = need(F.boxh, 'the box height in mm');
      if (Object.keys(cp).length) patch.cleanPadCheck = cp;
      if (!Object.keys(patch).length) throw bad('No unsaved changes.');
      const r = await IL.api('PUT', '/api/config', patch);
      clean(F.maxon, F.valve, F.pump, F.minstatus, F.clean, F.gray, F.bright, F.boxw, F.boxh);
      await IL.loadConfig();
      IL.planRefreshSoon();
      return r;
    }, { ok: 'Settings saved', refusalIn: c.ref }));
    return c.root;
  }
  function buildJson(c) {
    F.json = { el: el('textarea', { class: 'input mono', id: 'set-json', rows: '16', spellcheck: 'false', 'aria-describedby': 'su-json-err set-json-diff' }) };
    F.json.el.addEventListener('input', () => { J.edited = true; jsonCheck(); IL.render(); });
    c.jErr = el('div', { class: 'field__err', id: 'su-json-err', role: 'status' });
    c.jStale = el('div', { class: 'notebox notebox--need su-json-stale', hidden: true });
    c.jDiff = el('div', { id: 'set-json-diff', class: 'su-diff' });
    c.jReload = btn('Reload and keep my edits', { class: 'btn' }, 'i-refresh');
    c.jSave = btn('Save changed keys', { id: 'btn-settings-json', class: 'btn' });
    c.jReason = el('span', { class: 'reason su-foot-reason' });
    c.adv = el('details', { class: 'disclose su-adv', id: 'set-advanced' }, el('summary', { text: 'All settings (advanced JSON)' }),
      el('div', { class: 'su-col', 'data-refusals': '' },
        el('p', { class: 'su-note', text: 'Every engine setting. Save sends only the keys you changed (one level deep for profile, purgeRow, cleanPadCheck and needleXyTrim). Keys stamped by the engine are read-only here: '
          + 'needle state, surfaces, schemaVersion, purgeRow.usedSpots and resetAtMs, profile.startAngleSetAtMs, needleXyTrim.setAtMs. '
          + 'Places (locations) are sent one by one: a place saved elsewhere meanwhile is kept.' }),
        F.json.el, c.jErr, c.jStale, c.jDiff,
        el('div', { class: 'btn-row' }, c.jSave, c.jReload, c.jReason)));
    c.adv.addEventListener('toggle', () => { if (c.adv.open) IL.loadConfig(); });
    c.jReload.addEventListener('click', () => IL.act(c.jReload, async () => {
      const mine = J.edited ? jsonDiff(J.base, jsonParse()).patch : {};
      await IL.loadConfig();
      const merged = JSON.parse(JSON.stringify(S.config || {}));
      for (const [k, v] of Object.entries(mine)) {
        if (BY_ENTRY.has(k)) merged[k] = applyEntries(merged[k], v);
        else if (NESTED.has(k) && v && typeof v === 'object' && !Array.isArray(v) && merged[k] && typeof merged[k] === 'object') Object.assign(merged[k], v);
        else merged[k] = v;
      }
      J.base = S.config;
      F.json.el.value = JSON.stringify(merged, null, 2);
      J.edited = Object.keys(mine).length > 0;
      jsonCheck();
      return Object.keys(mine).length;
    }, { ok: (n) => (n ? 'Settings reloaded; your ' + IL.plural(n, 'changed key') + ' kept.' : 'Settings reloaded.') }));
    c.jSave.addEventListener('click', () => IL.act(c.jSave, async () => {
      const cur = jsonParse();
      const df = jsonDiff(J.base, cur);
      if (!Object.keys(df.patch).length) throw bad(df.changes.length ? 'Only read-only keys changed: nothing to send.' : 'No changed keys.');
      const body = Object.assign({}, df.patch);
      const whole = Object.keys(body).filter((k) => BY_ENTRY.has(k));
      if (whole.length) {
        const now = await IL.api('GET', '/api/config');
        for (const k of whole) body[k] = applyEntries(now[k], body[k]);
      }
      const r = await IL.api('PUT', '/api/config', body);
      J.edited = false;
      await IL.loadConfig();
      IL.planRefreshSoon();
      return df.changes.filter((x) => !x.stamped).map((x) => x.path);
    }, { ok: (keys) => 'Saved ' + IL.plural(keys.length, 'changed key') + ': ' + keys.slice(0, 4).join(', ') + (keys.length > 4 ? ' …' : '') + '.' }));
    return c.adv;
  }
  function jsonParse() {
    try { return JSON.parse(F.json.el.value); } catch (e) { throw bad('The settings are not valid JSON: ' + e.message); }
  }
  const deq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  /** Changed keys of `cur` against `base`: {patch (only changed, non-stamped keys), changes [{path, from, to, stamped}], removed}. */
  function jsonDiff(base, cur) {
    const out = { patch: {}, changes: [], removed: [] };
    if (!base || !cur || typeof cur !== 'object' || Array.isArray(cur)) return out;
    for (const k of Object.keys(base)) if (!(k in cur)) out.removed.push(k);
    for (const [k, v] of Object.entries(cur)) {
      const b = base[k];
      if (BY_ENTRY.has(k) && isObj(v) && isObj(b)) {
        // per entry: {name: entry | null (removed)}; the Save handler turns it into the whole map
        for (const n of new Set(Object.keys(b).concat(Object.keys(v)))) {
          if (deq(v[n], b[n])) continue;
          out.changes.push({ path: k + '.' + n, from: b[n], to: v[n], stamped: false });
          (out.patch[k] = out.patch[k] || {})[n] = n in v ? v[n] : null;
        }
        continue;
      }
      if (NESTED.has(k) && v && typeof v === 'object' && !Array.isArray(v) && b && typeof b === 'object') {
        for (const sk of Object.keys(b)) if (!(sk in v)) out.removed.push(k + '.' + sk);
        for (const [sk, sv] of Object.entries(v)) {
          if (deq(sv, b[sk])) continue;
          const path = k + '.' + sk;
          const stamped = STAMPED_NESTED.has(path);
          out.changes.push({ path, from: b[sk], to: sv, stamped });
          if (!stamped) (out.patch[k] = out.patch[k] || {})[sk] = sv;
        }
        continue;
      }
      if (deq(v, b)) continue;
      const stamped = STAMPED_TOP.has(k);
      out.changes.push({ path: k, from: b, to: v, stamped });
      if (!stamped) out.patch[k] = v;
    }
    return out;
  }
  const short = (v) => { const t = v === undefined ? '(new)' : JSON.stringify(v); return t.length > 42 ? t.slice(0, 40) + '…' : t; };
  function jsonCheck() {
    const c = C.settings;
    let cur = null;
    J.err = null;
    try { cur = JSON.parse(F.json.el.value); } catch (e) { J.err = 'Not valid JSON: ' + e.message; }
    F.json.el.classList.toggle('input--invalid', !!J.err);
    F.json.el.classList.toggle('input--dirty', J.edited && !J.err);
    IL.setText(c.jErr, J.err || '');
    const df = cur ? jsonDiff(J.base, cur) : { patch: {}, changes: [], removed: [] };
    J.df = df;
    const key = JSON.stringify([df.changes, df.removed, !!J.err]);
    IL.keyed(c.jDiff, key, () => {
      if (J.err) return null;
      if (!df.changes.length && !df.removed.length) return el('div', { class: 'su-note', text: 'No changed keys.' });
      return [el('div', { class: 'su-diff__h', text: IL.plural(df.changes.filter((x) => !x.stamped).length, 'changed key') + ' will be sent' }),
        el('ul', {}, df.changes.map((x) => el('li', { class: x.stamped ? 'su-diff--ro' : null },
          el('code', { text: x.path }), el('span', { text: ' ' + short(x.from) + ' → ' + (x.to === undefined ? 'removed' : short(x.to)) }), x.stamped ? el('span', { class: 'tag tag--off', text: 'read-only: not sent' }) : null)),
        df.removed.map((p) => el('li', { class: 'su-diff--ro' }, el('code', { text: p }), el('span', { text: ' removed' }), el('span', { class: 'tag tag--off', text: 'ignored' }))))];
    });
  }
  /** Allowed ranges of the Settings fields (the Profile fields are checked the same way): [field, label, min, max, unit]. */
  const SET_RANGES = () => [[F.maxon, 'Max pressure on-time', 1, 3600, 's'], [F.valve, 'Valve settle', 0, 9000, 'ms'], [F.pump, 'Pump settle', 0, 9000, 'ms'],
    [F.gray, 'Gray level', 0, 254, ''], [F.bright, 'Bright share', 0.01, 1, ''], [F.boxw, 'Box width', 0.05, 3, 'mm'], [F.boxh, 'Box height', 0.05, 3, 'mm']];
  /** One sentence per edited Settings field out of range or not a number (shown under the fields; Save waits for them). */
  function settingsErrors() {
    const out = [];
    for (const [f, label, lo, hi, u] of SET_RANGES()) {
      const bad1 = dirty(f) && (val(f) === '' || !Number.isFinite(toNum(val(f))) || toNum(val(f)) < lo || toNum(val(f)) > hi);
      f.el.classList.toggle('input--invalid', bad1);
      if (bad1) out.push(label + ': must be ' + lo + ' to ' + hi + (u ? ' ' + u : '') + '.');
    }
    return out;
  }
  function renderSettings(s) {
    const c = C.settings;
    const cfg = S.config;
    const d = disp(s), cpc = cfg ? cfg.cleanPadCheck || {} : d.cleanPadCheck || {};
    const src = cfg || d;
    sync(F.maxon, src.maxContinuousOnTimeS);
    sync(F.valve, cfg ? cfg.valveSettleMs : null);
    sync(F.pump, cfg ? cfg.pumpToValveSettleMs : null);
    sync(F.minstatus, src.minPadTypeStatus || 'characterized');
    sync(F.clean, !!cpc.enabled);
    sync(F.gray, cpc.grayLevel);
    sync(F.bright, cpc.minBrightFraction);
    sync(F.boxw, cpc.boxWMm);
    sync(F.boxh, cpc.boxHMm);
    const sim = IL.isSim(s);
    const on = F.clean.el.checked;
    IL.setText(c.cleanLbl, on ? 'On: the top camera looks at every pad first' : sim ? 'Off (simulator)' : 'Off: pads are not checked');
    IL.setText(c.cleanHint, on ? (sim ? 'In the simulator, switch this off: the simulated camera is a static picture, so every pad reads dirty.'
      : 'The run looks at every pad with the top camera (≈ 3 min) before the pressure goes on.')
      : sim ? 'Simulator: the camera is a static picture, so every pad would read dirty.' : 'The run does not look at the pads: wipe them by eye before each board.');
    c.cleanTick.classList.toggle('on', on);
    const lock = IL.lockReason(s, { pressureOff: false });
    const any = [F.maxon, F.valve, F.pump, F.minstatus, F.clean, F.gray, F.bright, F.boxw, F.boxh].some(dirty);
    const errs = settingsErrors();
    IL.setText(c.setErr, errs.join(' '));
    IL.show(c.setErr, errs.length > 0);
    // a load that failed says why, with Retry (never "Loading…" forever)
    const lerr = !cfg ? IL.loadError('config') : null;
    IL.gate(c.bSave, lock || (!cfg ? (lerr ? 'Could not load the settings: ' + lerr.text : 'Loading the settings…') : errs.length ? 'Fix the values marked in red first' : any ? null : 'No unsaved changes'));
    c.bSave.classList.toggle('btn--primary', any && !lock && !errs.length);
    footReason(c, lock || (lerr ? 'Could not load the settings: ' + lerr.text : errs.length ? 'Fix the values marked in red first' : any ? 'Unsaved: Save settings' : 'No unsaved changes'),
      lerr || errs.length ? 'block' : any && !lock ? 'need' : null);
    IL.show(c.bRetry, !!lerr);
    // JSON editor: refilled from the server unless edited or focused; a server change under edits is announced
    if (cfg && !J.edited && document.activeElement !== F.json.el && J.base !== cfg) {
      J.base = cfg;
      F.json.el.value = JSON.stringify(cfg, null, 2);
      jsonCheck();
    }
    const staleKeys = J.edited && cfg && J.base && J.base !== cfg ? jsonDiff(J.base, cfg).changes.map((x) => x.path) : [];
    IL.show(c.jStale, staleKeys.length > 0);
    IL.keyed(c.jStale, staleKeys.join(','), () => (staleKeys.length ? ['The machine changed ' + staleKeys.slice(0, 4).join(', ') + (staleKeys.length > 4 ? ' …' : '')
      + ' since you started editing (a run uses purge spots). Save still sends only your changed keys. Reload and keep my edits shows the new values.'] : null));
    const jlock = IL.lockReason(s, { pressureOff: false });
    const nkeys = J.df ? Object.keys(J.df.patch).length : 0;
    IL.gate(c.jSave, jlock || (J.err ? 'Fix the JSON first' : !nkeys ? 'No changed keys' : null));
    c.jSave.classList.toggle('btn--primary', !jlock && !J.err && nkeys > 0);
    setLabel(c.jSave, nkeys ? 'Save ' + IL.plural(nkeys, 'changed key') : 'Save changed keys');
    IL.setText(c.jReason, jlock || (J.err ? 'Fix the JSON first' : !nkeys ? 'No changed keys' : ''));
    IL.show(c.jReason, !!(jlock || J.err || !nkeys));
  }

  // ================================================================================== default panel: head position + bring-up order
  const HP = {};
  function buildHeadPanel(parts) {
    HP.live = el('span', { class: 'su-live' }, el('span', { class: 'live-dot' }), el('span', { text: 'live' }));
    const row = (label) => { const tds = [el('td', { text: label }), el('td', { text: '—' }), el('td', { text: '—' }), el('td', { text: '—' })]; return { tr: el('tr', {}, tds), tds }; };
    HP.n1 = row('N1 nozzle');
    HP.n2 = row('N2 needle');
    HP.cam = row('Camera');
    HP.n1.tds[3].className = 'hl';
    HP.n1.tds[3].title = 'N1 Z: the contact height the capture buttons take';
    HP.count = el('span', { class: 'su-bu-count' });
    HP.bar = el('i', { style: 'width:0%' });
    HP.list = el('ol', { class: 'bringup', 'aria-label': 'Bring-up order' });
    parts.body.append(
      el('div', {}, el('div', { class: 'panel__title' }, el('h2', { text: 'Head position' }), el('span', { class: 'spacer' }), HP.live),
        el('table', { class: 'headtable su-headtable', 'aria-label': 'Live head position in mm' },
          el('colgroup', {}, el('col', { style: 'width:30%' }), el('col'), el('col'), el('col', { style: 'width:22%' })),
          el('thead', {}, el('tr', {}, ['mm', 'X', 'Y', 'Z'].map((h) => el('th', {}, IL.unitText(h))))),
          el('tbody', {}, HP.n1.tr, HP.n2.tr, HP.cam.tr)),
        el('p', { class: 'su-note su-head-note', text: "Jog in OpenPnP's window: this page cannot move the head. Capture buttons take these values." })),
      el('div', {}, el('div', { class: 'panel__title' }, el('h2', { text: 'Bring-up order' }), el('span', { class: 'spacer' }), HP.count),
        el('div', { class: 'progress su-bu-bar' }, HP.bar), HP.list));
  }
  function renderHeadPanel(s, m) {
    const pos = IL.positions(s);
    const setRow = (r, t, z) => { IL.setText(r.tds[1], t ? fmt(t.x, 2) : '—'); IL.setText(r.tds[2], t ? fmt(t.y, 2) : '—'); IL.setText(r.tds[3], t && z ? fmt(t.z, 2) : '—'); };
    setRow(HP.n1, pos.n1, true);
    setRow(HP.n2, pos.n2, true);
    setRow(HP.cam, pos.cam, false);
    if (pos.n2 && pos.n2.name) IL.setText(HP.n2.tds[0], pos.n2.name + ' needle');
    const live = S.online !== false && Date.now() - S.statusAt < L.staleMs;
    HP.live.classList.toggle('su-live--off', !live);
    IL.setText(HP.live.lastChild, live ? 'live' : S.online === false ? 'no answer' : 'waiting');
    IL.setText(HP.count, m.count + ' of ' + m.total);
    const pct = Math.round(100 * m.count / m.total) + '%';
    if (HP.bar.style.width !== pct) HP.bar.style.width = pct;
    const key = JSON.stringify(m.steps.map((x) => [x.state, x.value, x.you]) ) + (m.now ? m.now.key : '');
    IL.keyed(HP.list, key, () => m.steps.map((x) => {
      const isNow = m.now === x;
      const b = el('button', { type: 'button', class: 'bringup__b', 'data-step': x.key, title: x.title + ': ' + (x.detail || x.value) },
        IL.glyph(x.state === 'wait' ? 'wait' : x.state, 'xs'), el('span', { class: 'n', text: String(x.n) }), el('span', { class: 't', text: x.title }),
        el('span', { class: 'v' }, isNow ? el('span', { class: 'tag tag--now', text: 'Now' }) : [el('span', { class: 'v__t', text: x.value || '' }), x.you ? el('span', { class: 'tag tag--you', text: 'you' }) : null]));
      b.addEventListener('click', () => { if (x.card) goCard(x.card); else if (x.view) IL.openView(x.view, { keep: true }); else if (x.href) IL.go(x.href); });
      return el('li', { class: isNow ? 'now' : null, 'data-state': x.state }, b);
    }));
  }

  // ================================================================================== workspace
  IL.workspace('setup', {
    defaultView: 'head',
    mount(root) {
      W.root = root;
      root.classList.add('su-ws');
      root.append(...buildHead(),
        el('div', { class: 'grid-cards su-cards' }, buildNeedle(), buildSurfaces(), buildPurge(), buildPressure(), buildPlaces(), buildCamera(), buildAngle(), buildSettings()));
      setMethod('k');
      W.method = undefined;
      IL.on('taskend', () => { if (W.shown || J.base) IL.loadConfig(); });
    },
    show() {
      W.shown = true;
      W.lastRoute = null;
      IL.loadConfig();
      camSchedule();
    },
    hide() {
      W.shown = false;
      closeMenu();
      camSchedule();
    },
    route(r) {
      // flash the card of #setup/<card>; not when only the pushed panel view changed (?panel=)
      const prev = W.lastRoute;
      W.lastRoute = { section: r.section || '', panel: r.panel || '' };
      if (!r.section || (prev && prev.section === (r.section || '') && prev.panel !== (r.panel || ''))) return;
      if (r.section === 'trim' && !r.panel) IL.openView('trim', { keep: true });
      if (r.section === 'probe-blank' && !r.panel) { IL.openView('probe-blank', { keep: true }); return; }
      if (S.status) flashCard(r.section); else W.pendingFlash = r.section;
      if (r.id) setTimeout(() => focusTarget(r.section, r.id), 350);
    },
    render(s) {
      const m = model(s);
      W.model = m;
      IL.setText(W.count, m.count + ' of ' + m.total + ' steps ready');
      IL.setText(W.segNeeds, 'Needs you · ' + m.needs.length);
      const lock = IL.taskRunning(s) ? IL.lockReason(s) : null;
      IL.show(W.lock, !!lock);
      if (lock) IL.setText(W.lock.lastChild, lock + ': saves, captures, prime, test dot and Pressure on. STOP and Pressure off still work.');
      renderBand(s, m);
      renderNeedle(s, m);
      renderSurfaces(s);
      renderPurge(s, m);
      renderPressure(s, m);
      renderPlaces(s);
      renderCamera(s);
      renderAngle(s, m);
      renderSettings(s);
      // filter: cards with no step that needs the operator
      const needCards = new Set(m.needs.map((x) => x.card).filter(Boolean));
      for (const c of Object.values(C)) {
        const ok = !needCards.has(c.key) && !c.root.querySelector('.input--dirty');
        if (c.root.dataset.ok !== (ok ? '1' : '0')) c.root.dataset.ok = ok ? '1' : '0';
      }
      if (W.pendingFlash) { const sec = W.pendingFlash; W.pendingFlash = null; requestAnimationFrame(() => flashCard(sec)); }
    },
    panel(parts, s, first) {
      if (first) buildHeadPanel(parts);
      renderHeadPanel(s, W.model || model(s));
    },
    nav(s) {
      const anyDirty = ALL.some(dirty) || J.edited;
      if (IL.taskRunning(s)) return { sub: 'Locked · running', kind: '', dirty: anyDirty };
      const m = model(s);
      if (!m.needs.length) return { sub: 'All ' + m.total + ' steps ready', kind: 'ok', dirty: anyDirty, title: anyDirty ? 'Unsaved changes' : null };
      const kind = m.blocked ? 'block' : 'need';
      return { sub: m.needs.length + (m.needs.length === 1 ? ' needs you' : ' need you'), kind, badge: String(m.needs.length), badgeKind: kind,
        badgeLabel: m.needs.length + ' need you', dirty: anyDirty, title: anyDirty ? 'Unsaved changes' : null };
    },
  });
})();
