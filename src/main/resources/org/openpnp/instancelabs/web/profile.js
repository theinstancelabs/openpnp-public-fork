/* profile.js: the Profile workspace (docs/instancelabs/UI_DESIGN.md §8.3).
   - Dispense profile card #pf-card: two-column form with unit + range on each label line, bench evidence at the field,
     derived turn line #pf-turn-info, client checks mirroring the engine's (problems at the field, #pf-problems for the
     engine's own text), a sticky unsaved bar #pf-diff (diff + consequences, Discard two-press, Save; above 180 rpm the
     save asks in-page). The poll never overwrites a focused or edited field.
   - Screw start angle: #pf-angle-kv, #pf-angle-warn, the two equal answers (#btn-pf-angle-confirm, #btn-pf-angle-measure).
   - Pad types #profiles: .family cards (sticky headers) in the engine's family order, tr[data-key] rows with
     .badge.<status>, Edit (#dlg-padtype, versioned) and More › (side panel: Set up, Validate, History #dlg-history,
     rule editor #pt-rule-*, matched packages, Retire two-press).
   - Context panel `pt`: "What this profile does" (screw dial from the edited values) and the pad-type gate; with a
     selected type (#profile/pad-types/<key>) its side panel.
   Also defines IL.pcr, small helpers shared by profile.js, calibrate.js and records.js (same owner).
   Contract: DEV_NOTES.md. Styles: profile.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const { isNum } = IL;
  const MAX_PSI = 21.7;
  const BENCH_RPM = 180;

  // ================================================================================== IL.pcr (shared helpers)
  const pcr = IL.pcr || (IL.pcr = {});
  /** Pad-type statuses in order: word (row badge), kind (glyph), long (title). */
  const STATUS = {
    'not-started': { word: 'not started', kind: 'off', long: 'Not started: no dots measured for this pad size yet' },
    provisional: { word: 'provisional', kind: 'need', long: 'Provisional: first dots made, not yet shown to repeat' },
    characterized: { word: 'characterized', kind: 'ok', long: 'Characterized: the dots repeat (ladder or scale)' },
    validated: { word: 'validated', kind: 'ok', long: 'Validated on a board: a coupon matched the prediction' },
  };
  const STATUS_ORDER = ['not-started', 'provisional', 'characterized', 'validated'];
  const STATUS_OPTION = { 'not-started': 'not started', provisional: 'provisional', characterized: 'characterized', validated: 'validated on a board' };
  pcr.STATUS = STATUS;
  pcr.STATUS_ORDER = STATUS_ORDER;
  /** .badge.<status>: glyph + word. retired: the Retired family. */
  pcr.badge = function (status, retired) {
    if (retired) return el('span', { class: 'badge badge--off retired', title: 'Retired: kept in the history, never matched or dispensed' }, IL.glyph('off', 'xs', 'Retired'), el('span', { text: 'retired' }));
    const st = STATUS[status] || { word: status || '—', kind: 'off', long: status };
    return el('span', { class: 'badge badge--' + st.kind + ' ' + status, title: st.long }, IL.glyph(st.kind, 'xs', st.long), el('span', { text: st.word }));
  };
  /** Pad size: 2 decimals unless the third one matters (1.325). */
  const needs3 = (n) => isNum(n) && Math.abs(n * 100 - Math.round(n * 100)) > 1e-6;
  pcr.fmtPad = (n) => (!isNum(n) ? '—' : IL.fmt(n, needs3(n) ? 3 : 2));
  /** "1.025 × 1.400": both with the same decimals. */
  pcr.fmtPadPair = (w, h) => { const d = needs3(w) || needs3(h) ? 3 : 2; return IL.fmt(w, d) + ' × ' + IL.fmt(h, d); };
  /** Keeps --pcr-sticky (height of the sticky #msg-area band) on a workspace root, for sticky headers below it. */
  pcr.stickyTop = function (root) {
    const area = $('msg-area');
    const set = () => root.style.setProperty('--pcr-sticky', ((area && area.offsetHeight) || 0) + 'px');
    if (area && window.ResizeObserver) new ResizeObserver(set).observe(area);
    set();
  };
  /** Named places (no derived waste-test-dot). */
  pcr.places = function (status) {
    const d = (status && status.dispense) || {};
    return Object.keys(d.locations || {}).filter((n) => n !== 'waste-test-dot').sort();
  };
  /** Fills a place <select>, keeps the chosen value; preferred when nothing is chosen yet. */
  pcr.fillPlaces = function (sel, status, preferred) {
    const names = pcr.places(status);
    const key = names.join('|');
    if (sel.dataset.key !== key) {
      const keep = sel.value;
      sel.dataset.key = key;
      sel.replaceChildren(...(names.length ? names.map((n) => el('option', { value: n, text: n }))
        : [el('option', { value: '', text: 'No places yet' })]));
      if (keep && names.includes(keep)) sel.value = keep;
      else if (preferred && names.includes(preferred)) sel.value = preferred;
    }
    return names;
  };
  pcr.TASK = { 'needle touch-off': 'Touch-off', 'dot ladder': 'Dot ladder', 'scale shots': 'Scale shots', prime: 'Prime',
    'test dot': 'Test dot', 'clean-pad check': 'Pad check', board: 'Board', 'board registration': 'Register', 'blank probe': 'Blank probe', 'z check': 'Z check',
    'pad calibration': 'Pad calibration' };
  pcr.statusWord = (st) => ({ OK: 'Finished', ABORTED: 'Stopped', ERROR: 'Ended by an error', REFUSED: 'Refused', RUNNING: 'Running' }[st] || IL.cap(String(st || '—').toLowerCase()));
  pcr.statusKind = (st) => ({ OK: 'ok', ABORTED: 'block', ERROR: 'block', REFUSED: 'need', RUNNING: 'live' }[st] || 'off');
  /**
   * Prerequisite rows (readiness style): [{kind ok|need|block|off|live, title, value, fix}] → ul.prq.
   * fix = IL fix object ({href|view|fix, label}).
   */
  pcr.prereqs = function (rows) {
    return el('ul', { class: 'prq' }, rows.map((r) => el('li', { class: 'prq__i prq__i--' + r.kind, title: r.title2 || null },
      IL.glyph(r.kind, 'xs'), el('span', { class: 'prq__t', text: r.title }), el('span', { class: 'prq__v', text: r.value || '' }),
      r.fix ? IL.fixButton(r.fix, { cls: 'btn--sm btn--quiet prq__fix' }) : null)));
  };
  /** Head position table (N1, N2 needle, camera): fixed layout, tabular numbers; rebuilt only when a value changes. */
  pcr.headTable = function (host, status) {
    const pos = IL.positions(status);
    const key = [pos.n1, pos.n2, pos.cam].map((t) => (t ? [t.x, t.y, t.z].map((v) => Number(v).toFixed(2)).join(',') : '-')).join('|');
    IL.keyed(host, key, () => el('table', { class: 'headtable' },
      el('thead', {}, el('tr', {}, ['mm', 'X', 'Y', 'Z'].map((h) => el('th', {}, IL.unitText(h))))),
      el('tbody', {}, [['N1 nozzle', pos.n1, true], [(pos.n2 && pos.n2.name ? pos.n2.name : 'N2') + ' needle', pos.n2, true], ['Camera', pos.cam, false]]
        .map(([label, t, z]) => el('tr', {}, el('td', { text: label }), el('td', { text: t ? IL.fmt(t.x, 2) : '—' }),
          el('td', { text: t ? IL.fmt(t.y, 2) : '—' }), el('td', { text: t && z ? IL.fmt(t.z, 2) : '—' }))))));
  };

  /** A .field: label line (name left, unit right), the input, an error line, an optional help line. */
  pcr.field = function (id, label, unit, input, help, cls) {
    return el('div', { class: 'field' + (cls ? ' ' + cls : ''), id: id + '-wrap' },
      el('label', { class: 'field__l', for: id }, el('span', { text: label }), unit ? el('span', { class: 'unit', text: unit }) : el('span')),
      input, el('div', { class: 'field__err', id: id + '-err', hidden: true, role: 'status' }), help ? el('div', { class: 'field__h', text: help }) : null);
  };
  pcr.numInput = (id, value, step, extra) => el('input', Object.assign({ class: 'input', id, type: 'number', step: step || 'any', inputmode: 'decimal',
    autocomplete: 'off', value: value === undefined || value === null ? null : String(value) }, extra || {}));
  pcr.textInput = (id, value, extra) => el('input', Object.assign({ class: 'input', id, type: 'text', autocomplete: 'off', spellcheck: 'false',
    value: value === undefined || value === null ? null : String(value) }, extra || {}));
  /** Shows a field error (or clears it with null) at the field: red border + the message under it. */
  pcr.fieldErr = function (id, msg) {
    const e = $(id), m = $(id + '-err');
    if (e) { e.classList.toggle('input--invalid', !!msg); IL.setAttr(e, 'aria-invalid', msg ? 'true' : null); }
    if (m) { IL.setText(m, msg || ''); IL.show(m, !!msg); }
  };
  /** Number check of an input: {v, err} (empty → v undefined; err only when required or out of range). */
  pcr.check = function (id, o) {
    const opt = o || {};
    const v = IL.num(id);
    let err = null;
    if (v === undefined) { if (opt.required) err = 'Required.'; }
    else if (!Number.isFinite(v)) err = 'Enter a number.';
    else if (opt.int && !Number.isInteger(v)) err = 'A whole number.';
    else if (opt.min !== undefined && (opt.minExcl ? v <= opt.min : v < opt.min)) err = opt.msg || ((opt.minExcl ? 'Above ' : 'At least ') + opt.min + (opt.unit ? ' ' + opt.unit : '') + '.');
    else if (opt.max !== undefined && (opt.maxExcl ? v >= opt.max : v > opt.max)) err = opt.msg || ((opt.maxExcl ? 'Below ' : 'At most ') + opt.max + (opt.unit ? ' ' + opt.unit : '') + '.');
    return { v, err };
  };

  // ================================================================================== pad-type data
  const PT = { data: null, ver: 0, loading: false, again: false, error: null, pkgs: null, pkgLoading: null, sel: null,
    panelSel: undefined, showRetired: false, filter: 'all', watchKey: null, flashKey: null, escOff: null, dlg: null,
    editKey: null, hist: null, planStats: {}, planStatsOf: undefined };
  async function loadPadTypes() {
    if (PT.loading) { PT.again = true; return PT.data; }
    PT.loading = true;
    try { PT.data = await IL.api('GET', '/api/pad-types'); PT.error = null; PT.ver++; }
    catch (e) { PT.error = IL.errorText(e); }
    finally { PT.loading = false; }
    if (PT.again) { PT.again = false; return loadPadTypes(); }
    IL.emit('padtypes', PT.data);
    IL.render();
    return PT.data;
  }
  pcr.padTypes = () => PT.data;
  pcr.loadPadTypes = loadPadTypes;
  const allTypes = () => (PT.data ? PT.data.families.flatMap((f) => f.types.map((t) => Object.assign({ retired: !!f.retired }, t))) : []);
  const typeByKey = (k) => allTypes().find((t) => t.typeKey === k) || null;
  /** A long KiCad name with line-break chances after "_" and "-" (never inside a word or a number). */
  const breakable = (id) => String(id).split(/(?<=[_-])/).flatMap((x, i) => (i ? [el('wbr'), x] : [x]));
  const gateStatus = (s) => (PT.data && PT.data.minPadTypeStatus) || (s && s.dispense && s.dispense.minPadTypeStatus) || 'characterized';
  /** Pads and dots of the plan shown, per pad type. */
  function planStats() {
    if (PT.planStatsOf === S.plan) return PT.planStats;
    const out = {};
    for (const pad of (S.plan && S.plan.pads) || []) {
      const k = pad.typeKey || '(no type)';
      const o = out[k] || (out[k] = { pads: 0, blocked: 0, dots: 0, packages: new Set() });
      o.pads++;
      if (pad.blocked) o.blocked++;
      o.dots += pad.dotCount || 0;
      if (pad.packageId) o.packages.add(pad.packageId);
    }
    PT.planStats = out;
    PT.planStatsOf = S.plan;
    return out;
  }
  async function loadPackages() {
    if (PT.pkgs) return PT.pkgs;
    if (!PT.pkgLoading) {
      PT.pkgLoading = IL.api('GET', '/api/packages').then((p) => { PT.pkgs = p || []; }, () => { PT.pkgs = []; })
        .then(() => { PT.pkgLoading = null; fillFootprints(); IL.render(); });
    }
    await PT.pkgLoading;
    return PT.pkgs;
  }
  /** Packages whose id the type's pattern finds (case-insensitive, like the engine's PadTypeResolver). */
  function matchedPackages(pattern) {
    if (!pattern || !PT.pkgs) return null;
    let re;
    try { re = new RegExp(pattern, 'i'); } catch (e) { return { bad: true, list: [] }; }
    return { bad: false, list: PT.pkgs.filter((p) => re.test(p.id)).map((p) => p.id) };
  }

  // ================================================================================== dispense profile form
  const FIELDS = [
    { id: 'pf-name', key: 'name', text: true, label: 'Name' },
    { id: 'pf-psi', key: 'pressurePsi', label: 'Regulator pressure', unit: 'psi', range: '0.1 to 21.7', step: '0.1', min: '0.1', max: String(MAX_PSI),
      check: (v) => v > 0 && v <= MAX_PSI, err: 'Must be above 0 and at most 21.7 psi (DV-5005: 1.5 bar).',
      helpId: 'pf-psi-note', help: '16 psi starved the screw, 20 psi drooled. Set by hand on the regulator: there is no pressure sensor.' },
    { id: 'pf-turn', key: 'netStepsPerDot', select: true, label: 'Screw turn per dot', unit: 'a quarter, half or whole turn',
      help: 'Every dot starts at the same screw angle again, so partial turns stay repeatable.' },
    { id: 'pf-dwell', key: 'dwellMs', int: true, label: 'Dwell', unit: 'ms', range: '0 to 5000', step: '10', min: '0', max: '5000',
      check: (v) => v >= 0 && v <= 5000, err: 'Must be 0 to 5000 ms.', help: 'Wait after the forward turn: the dot-size knob at a fixed pressure.' },
    { id: 'pf-suck', key: 'suckBackDeg', label: 'Suck-back', unit: '°', range: '0 to 30', step: '0.5', min: '0', max: '30',
      check: (v) => v >= 0 && v <= 30, err: 'Must be 0 to 30°.', help: 'Turns back after the dwell, so the paste stops at the needle.' },
    { id: 'pf-gap', key: 'gapMm', dec: 2, label: 'Gap above the pad', unit: 'mm', range: '0.05 to 0.15', step: '0.01', min: '0.05', max: '0.15',
      check: (v) => v >= 0.05 - 1e-9 && v <= 0.15 + 1e-9, err: 'Must be 0.05 to 0.15 mm.', help: '0.05 to 0.15 mm behave the same; a 0.20 mm gap misses dots.' },
    { id: 'pf-rpm', key: 'screwRpm', label: 'Screw speed', unit: 'rpm', range: '1 to 400', step: '10', min: '1', max: '400',
      check: (v) => v > 0 && v <= 400, err: 'Must be above 0 and at most 400 rpm (Vieweg maximum).',
      help: '180 rpm proven: never faster than the paste feeds the screw.' },
    { id: 'pf-accel', key: 'screwAccelDegS2', label: 'Screw acceleration', unit: '°/s²', range: '1,000 to 30,000', step: '1000', min: '1000', max: '30000',
      check: (v) => v >= 1000 && v <= 30000, err: 'Must be 1,000 to 30,000 °/s².', help: 'Only the screw turns; X, Y and Z keep their own acceleration.' },
    { id: 'pf-purge', key: 'purgeDots', int: true, label: 'Purge dots per board', unit: 'dots', range: '0 to 60', step: '1', min: '0', max: '60',
      check: (v) => v >= 0 && v <= 60, err: 'Must be 0 to 60 dots.', help: 'On the purge row before every board; the first one turns the screw to the start angle.' },
    { id: 'pf-notes', key: 'notes', text: true, textarea: true, label: 'Notes' },
  ];
  const FINGERPRINT_KEYS = new Set(['pressurePsi', 'netStepsPerDot', 'suckBackDeg', 'screwRpm', 'screwAccelDegS2', 'dwellMs', 'gapMm', 'purgeDots']);
  const PF = { dirty: new Set(), expect: null, expectUntil: 0, turnKey: null, errors: {}, tipKey: null };

  function readVal(f) {
    const e = $(f.id);
    if (!e) return undefined;
    if (f.select) return e.value === '' ? undefined : Number(e.value);
    if (f.text) return e.value;
    return IL.num(e);
  }
  function same(f, a, b) {
    if (f.text) return String(a === null || a === undefined ? '' : a).trim() === String(b === null || b === undefined ? '' : b).trim();
    if (!isNum(a) || !isNum(b)) return !isNum(a) && !isNum(b);
    return Math.abs(Number(a) - Number(b)) < 1e-9;
  }
  function writeVal(f, v) {
    const e = $(f.id);
    if (!e) return;
    const t = f.text ? (v === null || v === undefined ? '' : String(v)) : f.select ? String(v) : IL.inputNum(v, f.dec);
    if (f.select && t && !Array.from(e.options).some((o) => o.value === t)) PF.turnKey = null;
    if (e.value !== t) e.value = t;
  }
  const srvProfile = (s) => (s && s.dispense && s.dispense.profile) || null;
  /** The mounted tip setup (status dispense.tip): its key goes with every write made for it (forTip, forSetup). */
  const tipKey = (s) => (s && s.dispense && s.dispense.tip && s.dispense.tip.key) || null;
  const tipName = (s) => (s && s.dispense && s.dispense.tip && s.dispense.tip.name) || 'mounted tip';
  /** The values the form compares with: the server's, or what we just saved until the status shows it. */
  function baseProfile(s) {
    const p = srvProfile(s);
    if (!p) return null;
    if (PF.expect) {
      const shown = FIELDS.every((f) => same(f, p[f.key], PF.expect[f.key]));
      if (shown || Date.now() > PF.expectUntil) PF.expect = null;
      else return Object.assign({}, p, PF.expect);
    }
    return p;
  }
  /** The profile as edited (unsaved values), for the derived line, the diff and the panel dial. */
  function editedProfile(s) {
    const b = baseProfile(s);
    if (!b) return null;
    const out = Object.assign({}, b);
    for (const f of FIELDS) {
      const v = readVal(f);
      if (f.text ? v !== undefined : isNum(v)) out[f.key] = f.text ? v : Number(v);
    }
    return out;
  }
  /** The doses a profile may have (the engine's validate): a quarter, half or whole turn of the screw. */
  const turnSteps = (rev) => [rev / 4, rev / 2, rev].filter((n) => Number.isInteger(n));
  function turnOptions(p) {
    const rev = p.stepsPerRev || 1600;
    const steps = turnSteps(rev);
    const cur = readVal(FIELDS[2]);
    for (const extra of [p.netStepsPerDot, cur]) if (isNum(extra) && !steps.includes(Number(extra))) steps.push(Number(extra));
    return steps.sort((a, b) => a - b);
  }
  function fillTurn(p) {
    const opts = turnOptions(p);
    const key = opts.join(',') + '|' + p.stepsPerRev;
    if (PF.turnKey === key) return;
    PF.turnKey = key;
    const sel = $('pf-turn');
    const keep = sel.value;
    sel.replaceChildren(...opts.map((n) => el('option', { value: String(n), text: IL.turnLabel(n, p.stepsPerRev) + ' · ' + n + ' steps' })));
    sel.value = keep && opts.includes(Number(keep)) ? keep : String(p.netStepsPerDot);
  }
  /** Client checks, the same rules as the engine's validate() (so the engine's problems show at the field). */
  function checkFields(p) {
    const errs = {};
    for (const f of FIELDS) {
      if (f.text) continue;
      const v = readVal(f);
      if (!isNum(v)) { errs[f.id] = 'Enter a number.'; continue; }
      if (f.int && !Number.isInteger(Number(v))) { errs[f.id] = 'Must be a whole number.'; continue; }
      if (f.check && !f.check(Number(v))) errs[f.id] = f.err;
    }
    const name = readVal(FIELDS[0]);
    if (!String(name || '').trim()) errs['pf-name'] = 'Give the profile a name.';
    const rev = p.stepsPerRev || 1600;
    const net = readVal(FIELDS[2]);
    // a stored profile from before the rule (200 or 3200 steps) shows its value with the reason, until another is saved
    if (isNum(net) && !turnSteps(rev).includes(Number(net))) {
      errs['pf-turn'] = 'Must be ' + turnSteps(rev).join(', ').replace(/, (\d+)$/, ' or $1') + ' steps (a quarter, half or whole turn): the start angle repeats and a purge dot turns at most one revolution.';
    }
    const partial = isNum(net) && net % rev !== 0;
    const purge = readVal(FIELDS[8]);
    if (!errs['pf-purge'] && partial && isNum(purge) && purge < 2) errs['pf-purge'] = 'At least 2 for a partial turn: the first purge dot sets the start angle, one more flushes it.';
    const rpm = readVal(FIELDS[6]);
    if (!errs['pf-rpm'] && isNum(rpm) && p.feedDegPerMin && p.screwRpm && S.config && isNum(S.config.bMaxFeedDegS) && rpm * 6 > S.config.bMaxFeedDegS) {
      errs['pf-rpm'] = 'Above the screw axis limit of ' + IL.fmtInt(S.config.bMaxFeedDegS / 6) + ' rpm.';
    }
    return errs;
  }
  function diffParts(b, e) {
    const parts = [];
    const turn = (n) => IL.turnLabel(n, b.stepsPerRev);
    const d = (f, label, fmt, unit) => { if (!same(f, b[f.key], e[f.key])) parts.push(label + ' ' + fmt(b[f.key]) + ' → ' + fmt(e[f.key]) + (unit || '')); };
    const F = Object.fromEntries(FIELDS.map((f) => [f.key, f]));
    if (!same(F.name, b.name, e.name)) parts.push('name “' + (b.name || '') + '” → “' + String(e.name || '').trim() + '”');
    d(F.pressurePsi, 'pressure', IL.fmtPsi, ' psi');
    d(F.netStepsPerDot, 'turn', turn);
    d(F.dwellMs, 'dwell', String, ' ms');
    d(F.suckBackDeg, 'suck-back', (v) => IL.fmt(v, 1), '°');
    d(F.gapMm, 'gap', (v) => IL.fmt(v, 2), ' mm');
    d(F.screwRpm, 'speed', IL.fmtInt, ' rpm');
    d(F.screwAccelDegS2, 'acceleration', IL.fmtInt, ' °/s²');
    d(F.purgeDots, 'purge', String, ' dots');
    if (!same(F.notes, b.notes, e.notes)) parts.push('notes edited');
    const stale = FIELDS.some((f) => FINGERPRINT_KEYS.has(f.key) && !same(f, b[f.key], e[f.key]));
    const psiChanged = !same(F.pressurePsi, b.pressurePsi, e.pressurePsi);
    const cons = [];
    if (stale) cons.push('plans become stale');
    else if (parts.length) cons.push('plans stay current');
    if (psiChanged) cons.push('regulator: set it to ' + IL.fmtPsi(e.pressurePsi) + ' psi');
    else if (stale) cons.push('regulator unchanged');
    return { parts, cons, stale, psiChanged };
  }
  /** "forward 190.09° · back 9.91° · net 180.18° per dot · 800 steps · G1 B F64800" from the edited values. */
  function turnInfo(b, e) {
    const spd = b.stepsPerDeg || 4.44;
    const steps = Number(e.netStepsPerDot);
    const back = Math.round(Number(e.suckBackDeg) * spd);
    const net = steps / spd, fwd = (steps + back) / spd;
    const feed = Number(e.screwRpm) * 360;
    return { net, fwd, back: fwd - net, backSteps: back, steps, feed,
      text: 'forward ' + IL.fmt(fwd, 2) + '° · back ' + IL.fmt(fwd - net, 2) + '° · net ' + IL.fmt(net, 2) + '° per dot · ' + steps + ' steps · G1 B F' + Math.round(feed) };
  }
  function syncProfile(s) {
    const b = baseProfile(s);
    if (!b) return;
    // the tip setup whose recipe the form shows (§22.1): kept while edits are unsaved, so a save after a mount is refused
    if (!PF.dirty.size || !PF.tipKey) PF.tipKey = tipKey(s);
    fillTurn(b);
    const active = document.activeElement;
    for (const f of FIELDS) {
      const e = $(f.id);
      if (!PF.dirty.has(f.id) && active !== e && !same(f, readVal(f), b[f.key])) writeVal(f, b[f.key]);
    }
    recompute(s);
  }
  /** Dirty flags, field errors, derived line, rpm warning, unsaved bar: after every poll and every keystroke. */
  function recompute(s) {
    const b = baseProfile(s);
    if (!b) return;
    PF.dirty.clear();
    for (const f of FIELDS) {
      const dirty = !same(f, readVal(f), b[f.key]);
      if (dirty) PF.dirty.add(f.id);
      $(f.id).classList.toggle('input--dirty', dirty);
    }
    const errs = PF.errors = checkFields(b);
    for (const f of FIELDS) {
      const e = $(f.id), m = $(f.id + '-err');
      const msg = errs[f.id] || '';
      e.classList.toggle('input--invalid', !!msg);
      IL.setAttr(e, 'aria-invalid', msg ? 'true' : null);
      if (m) { IL.setText(m, msg); IL.show(m, !!msg); }
    }
    const ed = editedProfile(s);
    const ti = turnInfo(b, ed);
    const edited = PF.dirty.has('pf-turn') || PF.dirty.has('pf-suck') || PF.dirty.has('pf-rpm');
    const valid = !errs['pf-turn'] && !errs['pf-suck'] && !errs['pf-rpm'];
    IL.setText($('pf-turn-info'), valid ? (edited ? 'After saving: ' : '') + ti.text : 'Fix the turn, suck-back and speed to see the screw motion.');
    const rpm = readVal(FIELDS[6]);
    const warnRpm = isNum(rpm) && rpm > BENCH_RPM;
    const rw = $('pf-rpm-warn');
    const shownBefore = rw && !rw.hidden;
    IL.show(rw, warnRpm);
    // the warning appears under the field: bring it above the sticky unsaved bar (scroll-padding keeps it clear)
    if (warnRpm && !shownBefore && rw) requestAnimationFrame(() => rw.scrollIntoView({ block: 'nearest' }));
    renderBar(s, b, ed);
  }
  function renderBar(s, b, ed) {
    const dirty = PF.dirty.size > 0;
    const foot = $('pf-foot');
    foot.classList.toggle('pf-foot--dirty', dirty);
    IL.show($('pf-diff'), dirty);
    IL.show($('pf-saved'), !dirty);
    const errIds = Object.keys(PF.errors);
    const errNames = errIds.map((id) => (FIELDS.find((f) => f.id === id) || {}).label).filter(Boolean).map((t) => t.toLowerCase());
    const lock = IL.lockReason(s, { pressureOff: false });
    const reason = lock || (errIds.length ? 'Fix ' + IL.plural(errIds.length, 'field') + ' first: ' + errNames.join(', ') + '.' : null);
    if (dirty) {
      const df = diffParts(b, ed);
      IL.setText($('pf-diff-t'), df.parts.join(' · '));
      IL.setText($('pf-diff-c'), df.cons.join(' · '));
      const save = $('btn-pf-save');
      IL.gate(save, reason, $('pf-save-reason'));
      IL.gate($('btn-pf-discard'), null);
    }
    const p = srvProfile(s) || {};
    const problems = p.problems || [];
    IL.keyed($('pf-problems'), JSON.stringify(problems), () => problems.map((x) => el('li', { text: x })));
    IL.show($('pf-problems-box'), problems.length > 0);
    // card head state
    const st = $('pf-state');
    const kind = problems.length ? 'block' : dirty ? 'need' : 'ok';
    const text = problems.length ? IL.plural(problems.length, 'problem') : dirty ? IL.plural(PF.dirty.size, 'unsaved change') : 'In use';
    const sig = kind + text;
    if (st.dataset.sig !== sig) {
      st.dataset.sig = sig;
      st.className = 'card__state card__state--' + kind;
      st.replaceChildren(IL.glyph(kind, 'sm'), text);
    }
  }
  function collectBody() {
    const v = (i) => readVal(FIELDS[i]);
    return { name: String(v(0) || '').trim(), pressurePsi: v(1), netStepsPerDot: v(2), dwellMs: v(3), suckBackDeg: v(4), gapMm: v(5),
      screwRpm: v(6), screwAccelDegS2: v(7), purgeDots: v(8), notes: v(9) || '' };
  }
  async function doSave(body) {
    const s0 = S.status;
    const before = srvProfile(s0) || {};
    await IL.api('PUT', '/api/config', { profile: body, forTip: PF.tipKey || tipKey(s0) });
    PF.expect = Object.assign({}, body);
    PF.expectUntil = Date.now() + 4000;
    IL.planRefreshSoon();
    if (S.config) IL.loadConfig();
    loadPadTypes();
    recompute(S.status);
    const psiChanged = !same(FIELDS[1], before.pressurePsi, body.pressurePsi);
    const stale = FIELDS.some((f) => FINGERPRINT_KEYS.has(f.key) && !same(f, before[f.key], body[f.key]));
    return (psiChanged ? 'Profile saved. Set the regulator to ' + IL.fmtPsi(body.pressurePsi) + ' psi.' : 'Profile saved.')
      + (stale ? ' Plan the board again before a run.' : '');
  }
  function saveProfile(btn) {
    const body = collectBody();
    if (body.screwRpm > BENCH_RPM) {
      let go;
      const h = IL.dialog.open({
        id: 'dlg-pf-rpm', size: 'sm', eyebrow: 'Save profile', title: 'Run the screw at ' + IL.fmtInt(body.screwRpm) + ' rpm?',
        body: [el('p', { class: 'dlg-p', text: 'The bench runs 180 rpm. Never run the screw faster than the paste feeds it: it pulls in air and needs 30 to 50 turns to recover.' }),
          el('div', { 'data-refusals': '', id: 'pf-rpm-refusals' })],
        foot: [el('span', { class: 'spacer' }),
          el('button', { type: 'button', class: 'btn', text: 'Keep editing', onclick: () => h.close('cancel') }),
          go = el('button', { type: 'button', class: 'btn btn--test', text: 'Save at ' + IL.fmtInt(body.screwRpm) + ' rpm' })],
        initialFocus: null,
      });
      go.addEventListener('click', () => IL.act(go, async () => { const t = await doSave(body); h.close('saved'); return t; },
        { ok: (t) => t, refusalIn: $('pf-rpm-refusals') }));
      return Promise.resolve(undefined);
    }
    return IL.act(btn, () => doSave(body), { ok: (t) => t });
  }
  function discardEdits() {
    const b = baseProfile(S.status);
    if (!b) return;
    for (const f of FIELDS) writeVal(f, b[f.key]);
    PF.dirty.clear();
    recompute(S.status);
  }

  // ================================================================================== DOM: profile card + start angle
  function field(f) {
    let input;
    const common = { class: 'input', id: f.id, autocomplete: 'off', spellcheck: f.text && !f.textarea ? 'false' : null };
    if (f.select) input = el('select', common);
    else if (f.textarea) input = el('textarea', Object.assign({ rows: '2' }, common));
    else if (f.text) input = el('input', Object.assign({ type: 'text', maxlength: '80' }, common));
    else input = el('input', Object.assign({ type: 'number', step: f.step, min: f.min, max: f.max, inputmode: 'decimal' }, common));
    return el('div', { class: 'field' + (f.span ? ' span2' : ''), 'data-field': f.key },
      el('label', { class: 'field__l', for: f.id }, el('span', { text: f.label }),
        el('span', { class: 'unit', text: [f.unit, f.range].filter(Boolean).join(' · ') })),
      input,
      el('div', { class: 'field__err', id: f.id + '-err', hidden: true, role: 'status' }),
      f.help ? el('div', { class: 'field__h', id: f.helpId || null, text: f.help }) : null,
      f.id === 'pf-rpm' ? el('div', { class: 'notebox notebox--need pf-warn', id: 'pf-rpm-warn', hidden: true, role: 'status' },
        IL.glyph('need', 'xs'), el('span', { text: 'Faster than the bench’s 180 rpm: never run the screw faster than the paste feeds it (it pulls in air and needs 30 to 50 turns to recover).' })) : null);
  }
  const F = (id) => FIELDS.find((f) => f.id === id);
  function buildProfileCard() {
    const save = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pf-save' }, 'Save profile');
    const discard = el('button', { type: 'button', class: 'btn', id: 'btn-pf-discard' }, 'Discard');
    save.addEventListener('click', () => { if (!save.disabled && PF.dirty.size && !Object.keys(PF.errors).length) saveProfile(save); });
    IL.press2(discard, 'discard edits', async () => discardEdits(), { ok: 'Edits discarded: the form shows the saved profile.' });
    const group = (title, sub, ...fields) => el('div', { class: 'pf-group' },
      el('div', { class: 'section-k' }, el('span', { text: title }), el('span', { class: 'rule' })),
      sub ? el('div', { class: 'pf-group__sub', text: sub }) : null,
      el('div', { class: 'fgrid' }, fields));
    const card = el('section', { class: 'card pf-card', id: 'pf-card', 'data-enter-submits': 'btn-pf-save', 'data-refusals': '' },
      el('div', { class: 'card__head' },
        el('div', {}, el('h2', { class: 'card__title', text: 'Dispense profile' }),
          el('div', { class: 'card__sub' }, el('span', { id: 'pf-title-name' }), el('span', { id: 'pf-title-tip', text: ' · the recipe of every dot on a board and on the purge row' }))),
        el('span', { class: 'card__state', id: 'pf-state' })),
      el('div', { class: 'card__body pf-body' },
        el('div', { class: 'notebox notebox--block', id: 'pf-problems-box', hidden: true },
          el('b', { text: 'OpenPnP reports problems with the saved profile (boards are refused until they are fixed):' }),
          el('ul', { id: 'pf-problems', class: 'pf-problems' })),
        group('Recipe', null, field(F('pf-name')), field(F('pf-psi'))),
        group('One dot', 'Each dot: lower to the gap, turn the screw forward, wait the dwell, turn back, lift.',
          field(F('pf-turn')), field(F('pf-dwell')), field(F('pf-suck')), field(F('pf-gap')),
          el('div', { class: 'pf-derived span2' }, IL.icon('i-angle'), el('span', { id: 'pf-turn-info' }))),
        group('Screw motion', null, field(F('pf-rpm')), field(F('pf-accel'))),
        group('Before each board', null, field(F('pf-purge')), field(F('pf-notes')))),
      el('div', { class: 'card__foot pf-foot', id: 'pf-foot' },
        el('div', { class: 'pf-saved', id: 'pf-saved' }, IL.glyph('ok', 'xs'), el('span', { text: 'Saved in OpenPnP. A change makes existing plans stale: plan the board again after saving.' })),
        el('div', { class: 'pf-diff', id: 'pf-diff', hidden: true, role: 'status' },
          el('div', { class: 'pf-diff__t' }, IL.glyph('need', 'sm'),
            el('div', {}, el('div', { class: 'pf-diff__k' }, 'Unsaved: ', el('span', { id: 'pf-diff-t' })),
              el('div', { class: 'pf-diff__c', id: 'pf-diff-c' }),
              el('div', { class: 'reason', id: 'pf-save-reason', hidden: true }))),
          el('div', { class: 'btn-row pf-diff__b' }, discard, save))));
    for (const f of FIELDS) {
      const e = card.querySelector('#' + f.id);
      e.addEventListener(f.select ? 'change' : 'input', () => { recompute(S.status); IL.render(); });
      e.addEventListener('blur', () => { recompute(S.status); IL.render(); });
    }
    return card;
  }
  function buildAngleCard() {
    const confirm = el('button', { type: 'button', class: 'btn pf-answer', id: 'btn-pf-angle-confirm',
      title: 'The angle measured earlier still holds: the controller has not been switched off since. Nothing moves.' }, 'Controller stayed on: still holds');
    const measure = el('button', { type: 'button', class: 'btn pf-answer', id: 'btn-pf-angle-measure',
      title: 'Opens Calibrate › Start angle (the angle is counted from the motor steps since the controller was switched on).' }, 'It was switched off: measure again');
    confirm.addEventListener('click', (ev) => { if (!confirm.disabled) IL.runFix('I11-confirm', confirm, null, ev); });
    measure.addEventListener('click', () => IL.go('#calibrate/angle'));
    return el('section', { class: 'card pf-angle', id: 'pf-angle-card', 'data-refusals': '' },
      el('div', { class: 'card__head' },
        el('div', {}, el('h2', { class: 'card__title', text: 'Screw start angle' }),
          el('div', { class: 'card__sub' }, el('abbr', { title: 'Interlock I11: partial turns depend on where the screw starts', text: 'I11' }),
            ' · the screw angle the first board dot starts at')),
        el('span', { class: 'card__state', id: 'pf-angle-state' })),
      el('div', { class: 'card__body pf-angle__body' },
        el('div', { class: 'pf-angle__now' },
          el('div', { class: 'mini' }, el('div', { class: 'mini__k', text: 'Start angle' }), el('div', { class: 'mini__v', id: 'pf-angle-v' }),
            el('div', { class: 'mini__s', id: 'pf-angle-s' })),
          el('dl', { class: 'kv kv--wrap', id: 'pf-angle-kv' })),
        el('div', { class: 'pf-angle__ask' },
          el('div', { class: 'notebox notebox--need', id: 'pf-angle-warn', hidden: true, role: 'status' }),
          el('div', { class: 'pf-angle__q', id: 'pf-angle-q' }),
          el('div', { class: 'pf-answers' }, confirm, measure),
          el('div', { class: 'reason', id: 'pf-angle-reason', hidden: true }),
          el('p', { class: 'field__h', text: 'The angle is counted from the screw motor steps since the controller was switched on. STOP and Connect keep that count; switching the controller off loses it.' }))));
  }
  function renderAngle(s) {
    const p = srvProfile(s) || {};
    const a = IL.fmtDeg(p.startAngleDeg, 1);
    const state = !p.partialTurn ? 'off' : !p.startAngleSetAtMs ? 'never' : p.startAngleNeedsConfirm ? 'confirm' : 'ok';
    const setAt = p.startAngleSetAtMs ? p.startAngleSetAtMs - S.skewMs : null;
    const conAt = p.controllerConnectedAtMs ? p.controllerConnectedAtMs - S.skewMs : null;
    IL.setText($('pf-angle-v'), a);
    IL.setText($('pf-angle-s'), p.partialTurn && p.startAngles ? 'dots alternate ' + IL.startAnglesText(p) : 'whole turns: any angle');
    IL.kv($('pf-angle-kv'), [
      ['Set or confirmed', setAt ? IL.fmtDateTime(setAt) : 'never'],
      ['Controller connected', conAt ? IL.fmtDateTime(conAt) : (s.machine && s.machine.enabled ? 'yes' : 'not connected')],
      ['Turn per dot', IL.turnLabel(p.netStepsPerDot, p.stepsPerRev) + (p.partialTurn ? ' (partial: the angle matters)' : ' (whole: the angle does not matter)')],
    ]);
    const warn = $('pf-angle-warn');
    const wtext = state === 'confirm' ? 'Was the controller switched off since ' + IL.fmtTime(setAt) + '? OpenPnP reconnected' + (conAt ? ' at ' + IL.fmtTime(conAt) : '') + ' after the angle was set. Answer before the next board (I11).'
      : state === 'never' ? 'The start angle has never been set: measure it before a board run (I11).' : '';
    IL.setText(warn, wtext);
    IL.setClass(warn, 'notebox ' + (state === 'never' ? 'notebox--block' : 'notebox--need'));
    IL.show(warn, !!wtext);
    IL.setText($('pf-angle-q'), state === 'ok' ? 'After the next reconnect, answer here (or in the strip):' : state === 'off' ? 'Whole turns: the start angle does not matter.' : '');
    const st = $('pf-angle-state');
    const kind = { ok: 'ok', confirm: 'need', never: 'block', off: 'off' }[state];
    const text = { ok: 'Holds', confirm: 'Confirm', never: 'Not set', off: 'Whole turns' }[state];
    if (st.dataset.sig !== kind + text) { st.dataset.sig = kind + text; st.className = 'card__state card__state--' + kind; st.replaceChildren(IL.glyph(kind, 'sm'), text); }
    const confirm = $('btn-pf-angle-confirm');
    if (!IL.isArmed(confirm) && confirm.getAttribute('aria-busy') !== 'true') IL.setText(confirm, 'Controller stayed on: ' + a + ' still holds');
    IL.show(confirm, state !== 'off' && state !== 'never');
    IL.show($('btn-pf-angle-measure'), state !== 'off');
    IL.setText($('btn-pf-angle-measure'), state === 'never' ? 'Measure the angle' : 'It was switched off: measure again');
    IL.gate(confirm, IL.lockReason(s, { pressureOff: false }), $('pf-angle-reason'));
  }

  // ================================================================================== DOM: pad types
  function buildPadTypesSection() {
    const add = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-padtype-new', 'data-testid': 'new-pad-type' }, IL.icon('i-plus'), 'New pad type');
    add.addEventListener('click', () => openPadType(null));
    const retired = el('input', { type: 'checkbox', id: 'show-retired' });
    retired.addEventListener('change', () => { PT.showRetired = retired.checked; IL.render(); });
    const segAll = el('button', { type: 'button', 'aria-pressed': 'true', 'data-filter': 'all' }, 'All types');
    const segPlan = el('button', { type: 'button', 'aria-pressed': 'false', 'data-filter': 'plan', id: 'pt-filter-plan' }, 'Used by this plan');
    // every tip's status per pad type (§22.7), only with more than one tip
    const segTip = el('button', { type: 'button', 'aria-pressed': 'false', 'data-filter': 'by-tip', id: 'pt-by-tip', hidden: true, title: 'Each tip’s status of every pad type: the mounted one first' }, 'By tip');
    for (const b of [segAll, segPlan, segTip]) b.addEventListener('click', () => { if (!b.disabled) { PT.filter = b.dataset.filter; IL.render(); } });
    const section = el('div', { class: 'pt-section', id: 'pt-section' },
      el('div', { class: 'pt-head' },
        el('div', { class: 'pt-head__t' }, el('h2', { text: 'Pad types' }), el('span', { class: 'count', id: 'pt-count' })),
        el('div', { class: 'pt-tools' },
          el('div', { class: 'seg', role: 'group', 'aria-label': 'Which pad types', id: 'pt-filter' }, segAll, segPlan, segTip),
          el('label', { class: 'check' }, retired, 'show retired'),
          add)),
      el('p', { class: 'pt-hint', id: 'profiles-hint' }),
      el('div', { id: 'profiles', class: 'pt-families', 'aria-live': 'off' }));
    for (const f of IL.pt.sectionExtras) f(section);
    return section;
  }
  const COLS = [['Pad type', null, 'pt-c-name'], ['Status', 'not started → provisional → characterized → validated on a board', 'pt-c-st'],
    ['Pad mm', 'Pad width × height in mm', 'pt-c-pad r'], ['Dots', 'Dots per pad, each one dot of the dispense profile', 'pt-c-dots r'],
    ['In plan', 'Pads of this type in the plan shown', 'pt-c-plan r'], ['Validated', 'Last time this type was saved as validated on a board', 'pt-c-val'], ['', null, 'pt-c-act']];
  function renderPadTypes(s) {
    const host = $('profiles');
    const stats = IL.pt.planStats();
    const hasPlan = !!(S.plan && S.plan.pads);
    if (!hasPlan && PT.filter === 'plan') PT.filter = 'all';
    for (const b of $('pt-filter').children) IL.setAttr(b, 'aria-pressed', String(b.dataset.filter === PT.filter));
    const fp = $('pt-filter-plan');
    fp.disabled = !hasPlan;
    IL.setAttr(fp, 'title', hasPlan ? 'Only the pad types this plan uses (' + (S.plan.boardName || 'board') + ')' : 'No plan shown: plan a board on Run first');
    IL.setText(fp, hasPlan ? 'Used by this plan · ' + Object.keys(stats).length : 'Used by this plan');
    const gate = gateStatus(s);
    const prof = srvProfile(s);
    const tips = liveTips();
    const bt = $('pt-by-tip');
    IL.show(bt, tips.length > 1);
    if (tips.length < 2 && PT.filter === 'by-tip') PT.filter = 'all';
    IL.setText($('profiles-hint'), 'A board refuses pads whose type is below “' + (STATUS_OPTION[gate] || gate) + '” (Lowest pad-type status allowed on a board, in Setup › Settings). '
      + IL.pt.texts.hint(prof) + (PT.data && PT.data.forSetup ? ' Statuses are the ' + PT.data.forSetup.name + '’s, the tip on the dispenser.' : ''));
    const n = allTypes().filter((t) => !t.retired).length;
    IL.setText($('pt-count'), PT.data ? String(n) : '');
    const key = JSON.stringify([PT.ver, PT.error, PT.showRetired, PT.filter, hasPlan ? S.plan.planId : '', hasPlan ? S.plan.pads.length : 0, gate,
      tips.map((x) => [x.key, x.name, x.mainPhoto && x.mainPhoto.id])]);
    const rebuilt = IL.keyed(host, key, () => {
      if (!PT.data) return el('div', { class: 'card ws-note' }, PT.error ? el('div', { class: 'refusal', role: 'alert' }, el('div', { class: 'refusal__t', text: 'Could not load the pad types: ' + PT.error }))
        : el('p', { class: 'muted', text: 'Loading pad types…' }));
      const fams = PT.data.families.filter((f) => (!f.retired || PT.showRetired));
      if (PT.filter === 'by-tip') return byTipTables(fams, tips);
      const out = [];
      for (const fam of fams) {
        const types = fam.types.filter((t) => PT.filter === 'all' || (PT.filter === 'plan' ? stats[t.typeKey] : !IL.pt.filters[PT.filter] || IL.pt.filters[PT.filter](t)));
        if (PT.filter !== 'all' && !types.length) continue;
        const pass = fam.types.filter((t) => t.dispensable).length;
        out.push(el('section', { class: 'card family' + (fam.retired ? ' family--retired' : ''), 'data-family': fam.family },
          el('div', { class: 'family__head' }, el('h2', { text: fam.family }),
            el('span', { class: 'count', text: String(fam.types.length) }),
            el('span', { class: 'family__s', text: fam.retired ? 'never matched or dispensed' : pass ? pass + ' allowed on a board' : 'none allowed on a board yet' })),
          el('table', { class: 'tbl pt-table' },
            el('colgroup', {}, COLS.map((c) => el('col', { class: c[2].split(' ')[0] }))),
            el('thead', {}, el('tr', {}, COLS.map(([h, t, c]) => el('th', { title: h === 'Dots' ? IL.pt.texts.dotsCol : t, class: c.includes(' r') ? 'r' : null, scope: 'col' }, IL.unitText(h))))),
            el('tbody', {}, types.length ? types.map((t) => padTypeRow(Object.assign({ retired: !!fam.retired }, t), stats[t.typeKey], prof))
              : [el('tr', {}, el('td', { colspan: String(COLS.length), class: 'muted', text: 'No types yet' }))]))));
      }
      if (!out.length) out.push(el('div', { class: 'card ws-note' }, el('p', { class: 'muted', text: PT.filter === 'needs' ? 'No existing pad type is involved: the sizes above need new ones.' : 'No pad types match.' })));
      return out;
    });
    // selection highlight (no rebuild)
    for (const tr of host.querySelectorAll('tr[data-key]')) IL.setAttr(tr, 'aria-selected', tr.dataset.key === PT.sel ? 'true' : null);
    if (rebuilt && PT.flashKey) {
      const tr = host.querySelector('tr[data-key="' + CSS.escape(PT.flashKey) + '"]');
      PT.flashKey = null;
      if (tr) IL.flash(tr);
    }
  }
  function padTypeRow(t, st, prof) {
    const edit = el('button', { type: 'button', class: 'btn btn--sm', 'data-focus-key': 'edit:' + t.typeKey, title: 'Change this type (saved as version ' + (t.nextVersion || t.version + 1) + ')' }, 'Edit');
    const more = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', 'data-focus-key': 'more:' + t.typeKey,
      title: 'Set up, validate, history, pad rule, retire', 'aria-label': 'More about ' + t.name }, 'More', IL.icon('i-chev'));
    edit.addEventListener('click', () => openPadType(typeByKey(t.typeKey)));
    more.addEventListener('click', () => IL.go('#profile/pad-types/' + t.typeKey));
    const dots = IL.pt.texts.dotsCell(t, prof);
    const dotTitle = IL.pt.texts.dotTitle(t, prof)
      + (t.rule ? ' Pad rule v' + t.rule.version + (t.rule.largestSingleDotPadMm2 ? ': above ' + IL.fmt(t.rule.largestSingleDotPadMm2, 2) + ' mm² a grid' : '') + '.' : '')
      + ' A stencil of t ' + IL.fmt(t.tMm, 2) + ' mm and k ' + IL.fmt(t.k, 2) + ' would print ' + IL.fmt(t.targetMassMg, 3) + ' mg (information).';
    const plan = st ? el('span', { class: st.blocked ? 'pt-blocked' : null, title: st.pads + ' pads, ' + st.dots + ' dots in the plan shown' + (st.blocked ? '; ' + st.blocked + ' cannot be dispensed (below the gate)' : '') },
      String(st.pads), st.blocked ? el('small', { text: ' · ' + st.blocked + ' blocked' }) : null) : el('span', { class: 'dim', text: '—' });
    // other tips' versions of this type (§22.7): "+2 tips" opens the side panel at Other tips
    const others = (t.others || []).length;
    const tipsBtn = others ? el('button', { type: 'button', class: 'pt-tips', 'data-focus-key': 'tips:' + t.typeKey, title: 'Calibrated for ' + (t.others || []).map((o) => 'the ' + o.setupName).join(', ') + ': open' },
      '+' + IL.plural(others, 'tip')) : null;
    if (tipsBtn) tipsBtn.addEventListener('click', (ev) => { ev.stopPropagation(); PT.focusTips = true; IL.go('#profile/pad-types/' + t.typeKey); });
    return el('tr', { 'data-key': t.typeKey, class: (t.retired ? 'is-retired' : '') + (t.dispensable ? ' is-allowed' : '') + (t.standIn ? ' is-standin' : '') },
      el('td', { class: 'pt-name', title: t.typeKey + ' v' + t.version + (t.packagePattern ? '\npackages: ' + t.packagePattern : '') + (t.notes ? '\n' + t.notes : '') },
        el('div', { class: 'pt-name__t' }, el('span', { text: t.name }), tipsBtn),
        el('div', { class: 'pt-name__k', text: t.typeKey + (t.standIn ? '' : ' · v' + t.version) })),
      el('td', {}, pcr.badge(t.standIn ? 'not-started' : t.status, t.retired),
        t.standIn && !t.retired ? el('div', { class: 'pt-standin', text: 'Not calibrated for the ' + (PT.data && PT.data.forSetup ? PT.data.forSetup.name : 'mounted tip') + ' yet' }) : null),
      el('td', { class: 'r nowrap', text: pcr.fmtPadPair(t.padWMm, t.padHMm) }),
      el('td', { class: 'r nowrap', title: dotTitle, text: dots }),
      el('td', { class: 'r nowrap' }, plan),
      el('td', { class: 'nowrap' }, t.lastValidatedAtMs ? IL.timeEl(t.lastValidatedAtMs - S.skewMs) : el('span', { class: 'dim', text: '—' })),
      el('td', { class: 'pt-act' }, el('div', { class: 'pt-act__b' }, edit, more)));
  }

  /** The tip setups that are not retired, the mounted one first (IL.tips' library). */
  function liveTips() {
    const d = IL.tips && IL.tips.data();
    return d ? (d.setups || []).filter((x) => !x.retired).sort((a, b) => (b.mounted ? 1 : 0) - (a.mounted ? 1 : 0)) : [];
  }
  /**
   * A "calibrated for" label without its pad type (the first part) and the tip's name: "Vieweg DV-5005 · 18 psi · …".
   * Only the automatic label has that shape ({type} · {tip} · …); a label the operator wrote is shown whole.
   */
  function labelRest(label, setupName) {
    const parts = String(label || '').split(' · ');
    return parts.length > 1 && setupName && parts[1] === setupName ? parts.slice(2).join(' · ') : String(label || '');
  }
  /** By tip (§22.7): rows are pad types, one column per tip with the mounted one first; a cell opens that tip's version. */
  function byTipTables(fams, tips) {
    const out = [];
    for (const fam of fams) {
      if (fam.retired) continue;
      const types = fam.types;
      if (!types.length) continue;
      out.push(el('section', { class: 'card family pt-bytip', 'data-family': fam.family },
        el('div', { class: 'family__head' }, el('h2', { text: fam.family }), el('span', { class: 'count', text: String(types.length) }),
          el('span', { class: 'family__s', text: 'each tip’s own status' })),
        el('div', { class: 'pt-bytip__wrap' }, el('table', { class: 'tbl pt-bytip__t' },
          el('thead', {}, el('tr', {}, el('th', { scope: 'col', class: 'pt-bytip__name' }, 'Pad type'),
            tips.map((x) => el('th', { scope: 'col', 'data-setup': x.key }, el('div', { class: 'pt-bytip__h' }, IL.tips.thumb(x, 'xs'),
              el('span', { class: 'pt-bytip__hn', text: x.name }), x.mounted ? el('span', { class: 'tag tag--now', text: 'on now' }) : null))))),
          el('tbody', {}, types.map((t) => el('tr', { 'data-key': t.typeKey },
            el('td', { class: 'pt-name' }, el('div', { class: 'pt-name__t', text: t.name }), el('div', { class: 'pt-name__k', text: t.typeKey })),
            tips.map((x) => {
              const o = x.mounted ? (t.standIn ? null : { status: t.status, version: t.version, label: t.label })
                : (t.others || []).find((k) => k.setupKey === x.key) || null;
              if (!o) return el('td', { 'data-setup': x.key }, el('span', { class: 'dim pt-bytip__none', text: 'not calibrated' }));
              const b = el('button', { type: 'button', class: 'pt-bt', 'data-setup': x.key, 'data-type': t.typeKey, 'data-focus-key': 'bt:' + x.key + ':' + t.typeKey,
                title: 'The ' + x.name + '’s version ' + o.version + (o.label ? ': ' + o.label : '') }, pcr.badge(o.status),
              o.label ? el('span', { class: 'pt-bt__l', text: labelRest(o.label, x.name) }) : el('span', { class: 'pt-bt__l', text: 'v' + o.version }));
              b.addEventListener('click', () => { PT.focusSetup = x.mounted ? null : x.key; PT.focusTips = !x.mounted; IL.go('#profile/pad-types/' + t.typeKey); });
              return el('td', { 'data-setup': x.key }, b);
            }))))))));
    }
    return out.length ? out : [el('div', { class: 'card ws-note' }, el('p', { class: 'muted', text: 'No pad types.' }))];
  }

  // ================================================================================== pad-type dialog (#dlg-padtype)
  const FAMILIES = ['Chip (2-terminal SMD)', 'Small-outline discretes', 'Leaded ICs', 'No-lead ICs', 'Custom'];
  const LAYOUTS = { single: 'One pad at the part centre', 'two-terminal': 'Two pads (chip, SOD)', 'sot23-3': 'SOT-23 (three pads)', 'footprint-only': 'From the package footprint only' };
  function ptField(id, label, unit, input, help, cls) {
    return el('div', { class: 'field' + (cls ? ' ' + cls : ''), id: id + '-wrap' },
      el('label', { class: 'field__l', for: id }, el('span', { text: label }), unit ? el('span', { class: 'unit', text: unit }) : el('span')),
      input, help ? el('div', { class: 'field__h', text: help }) : null);
  }
  function buildPadTypeDialog() {
    const num = (id, step, extra) => el('input', Object.assign({ class: 'input', id, type: 'number', step, inputmode: 'decimal', autocomplete: 'off' }, extra || {}));
    const txt = (id, extra) => el('input', Object.assign({ class: 'input', id, type: 'text', autocomplete: 'off', spellcheck: 'false' }, extra || {}));
    const cancel = el('button', { type: 'button', class: 'btn', id: 'pt-cancel' }, 'Cancel');
    const save = el('button', { type: 'submit', class: 'btn btn--primary', id: 'pt-save', value: 'save' }, 'Save');
    const form = el('form', { id: 'padtype-form', class: 'dlg-form', novalidate: true, 'data-enter-submits': 'pt-save' },
      el('div', { class: 'dialog__head' },
        el('div', {}, el('div', { class: 'eyebrow', id: 'pt-eyebrow', text: 'Pad type' }), el('h2', { id: 'dlg-padtype-title', text: 'New pad type' })),
        el('div', { class: 'dialog__keys' }, el('kbd', { text: 'Esc' }), ' closes ', el('kbd', { text: 'F9' }), ' stops')),
      el('div', { class: 'dialog__body' },
        el('div', { class: 'notebox notebox--info pt-version', id: 'pt-version' }),
        el('div', { class: 'pt-dlg-grid' },
          ptField('pt-family', 'Family', null, el('select', { class: 'input', id: 'pt-family' })),
          ptField('pt-family-other', 'Other family', null, txt('pt-family-other'), null, 'pt-other'),
          ptField('pt-name', 'Name', null, txt('pt-name', { required: true, placeholder: 'e.g. 0201', maxlength: '60' })),
          ptField('pt-key', 'Key', 'stable across versions', txt('pt-key', { placeholder: 'made from the family and name', pattern: '[a-z0-9][a-z0-9-]*', maxlength: '48' }))),
        ptField('pt-footprint', 'Take the pad size from an OpenPnP footprint', 'optional', el('select', { class: 'input', id: 'pt-footprint' }, el('option', { value: '', text: '—' }))),
        el('div', { class: 'pt-dlg-pad' },
          el('div', { class: 'pt-dlg-grid pt-dlg-grid--3' },
            ptField('pt-w', 'Pad width', 'mm', num('pt-w', '0.005', { required: true, min: '0.01' })),
            ptField('pt-h', 'Pad height', 'mm', num('pt-h', '0.005', { required: true, min: '0.01' })),
            ptField('pt-round', 'Roundness', '%', num('pt-round', '1', { min: '0', max: '100', value: '0' })),
            ptField('pt-layout', 'Pad layout', null, el('select', { class: 'input', id: 'pt-layout' }, Object.entries(LAYOUTS).map(([k, v]) => el('option', { value: k, text: v }))),
              'Used when the package has no footprint pads.', 'pt-span2'),
            ptField('pt-pitch', 'Pitch', 'mm', num('pt-pitch', '0.005', { min: '0' }), 'Centre to centre.')),
          el('figure', { class: 'pt-preview', id: 'pt-preview', 'aria-label': 'Pad layout to scale' })),
        el('div', { class: 'pt-dlg-grid pt-dlg-grid--3' },
          ptField('pt-status', 'Status', null, el('select', { class: 'input', id: 'pt-status' })),
          ptField('pt-t', 'Paste thickness', 'mm', num('pt-t', '0.005', { min: '0', value: '0.10' }), 'Like a stencil’s.'),
          ptField('pt-k', 'Area share k', '0 to 1', num('pt-k', '0.05', { min: '0', max: '1', value: '1.0' }), 'Less than 1 for QFN centre pads.')),
        IL.pt.editorExtras.map((x) => x.build()),
        ptField('pt-pattern', 'Package names', 'regular expression, matched against OpenPnP package ids', txt('pt-pattern', { placeholder: 'e.g. 0603|1608Metric' })),
        el('div', { class: 'pt-derived', id: 'pt-derived' }),
        ptField('pt-notes', 'Notes', null, el('textarea', { class: 'input', id: 'pt-notes', rows: '2' })),
        el('div', { id: 'pt-error', class: 'pt-error', 'data-refusals': '', role: 'alert' })),
      el('div', { class: 'dialog__foot' }, el('div', { class: 'dialog__sum', id: 'pt-sum' }), cancel, save));
    const dlg = el('dialog', { id: 'dlg-padtype', class: 'dialog pt-dlg', 'aria-labelledby': 'dlg-padtype-title' }, form);
    form.addEventListener('submit', savePadType);
    cancel.addEventListener('click', () => { if (PT.dlg) PT.dlg.close('cancel'); });
    for (const n of form.querySelectorAll('input, select, textarea')) n.addEventListener('input', () => ptDerived());
    form.querySelector('#pt-family').addEventListener('change', () => { IL.show($('pt-family-other-wrap'), $('pt-family').value === '__other'); ptDerived(); });
    form.querySelector('#pt-footprint').addEventListener('change', pickFootprint);
    return dlg;
  }
  function fillFootprints() {
    const sel = $('pt-footprint');
    if (!sel || !PT.pkgs || sel.dataset.filled) return;
    sel.dataset.filled = '1';
    const opts = [el('option', { value: '', text: '—' })];
    for (const p of PT.pkgs) {
      (p.pads || []).forEach((pad, i) => opts.push(el('option', { value: p.id + '\u0000' + i,
        text: p.id + ' · pad ' + pad.name + ' · ' + pcr.fmtPad(pad.w) + ' × ' + pcr.fmtPad(pad.h) + ' mm' })));
    }
    if (opts.length === 1) opts.push(el('option', { value: '', text: 'No package in OpenPnP has footprint pads', disabled: true }));
    sel.replaceChildren(...opts);
  }
  function fillFamilySelect(value) {
    const sel = $('pt-family');
    const fams = FAMILIES.slice();
    if (PT.data) for (const f of PT.data.families) if (!fams.includes(f.family) && !f.retired) fams.push(f.family);
    if (value === 'Retired') fams.push('Retired');
    sel.replaceChildren(...fams.map((f) => el('option', { value: f, text: f })), el('option', { value: '__other', text: 'Other…' }));
    if (value && !fams.includes(value)) { sel.value = '__other'; $('pt-family-other').value = value; }
    else { sel.value = value || 'Custom'; $('pt-family-other').value = ''; }
    IL.show($('pt-family-other-wrap'), sel.value === '__other');
  }
  function openPadType(t) {
    if (PT.dlg) return;
    const gate = gateStatus(S.status);
    PT.editKey = t ? t.typeKey : null;
    // the editor saves for the tip mounted now (forSetup) and the pad version it showed (padVersion, §22.2)
    PT.dlgTip = tipKey(S.status);
    PT.dlgPadVersion = t && isNum(t.padVersion) ? t.padVersion : null;
    const tn = tipName(S.status);
    const next = t ? (t.nextVersion || t.version + 1) : 1;
    IL.setText($('dlg-padtype-title'), t ? 'Edit ' + t.name : 'New pad type');
    IL.setText($('pt-eyebrow'), (t ? 'Pad type · ' + t.typeKey : 'Pad type · new') + ' · for the ' + tn);
    const keeps = t ? (t.others || []).map((o) => 'the ' + o.setupName + ' keeps version ' + o.version) : [];
    const ver = $('pt-version');
    ver.replaceChildren(IL.glyph('info', 'xs'), el('span', { text: (t
      ? 'Saves version ' + next + ' for the ' + tn + (keeps.length ? '; ' + keeps.join(', ') : '') + '. Nothing is overwritten: History keeps every version.'
      : 'A new type starts at version 1, for the ' + tn + '. Its key cannot change later; edits add versions.') + ' Size, pattern, name, k and t are shared by every tip.' }));
    fillFamilySelect(t ? t.family : 'Custom');
    $('pt-status').replaceChildren(...STATUS_ORDER.map((k) => el('option', { value: k, text: STATUS_OPTION[k] + (k === gate ? ' (the gate)' : '') })));
    const set = (id, v) => { $(id).value = v === null || v === undefined ? '' : String(v); };
    set('pt-name', t ? t.name : '');
    set('pt-key', t ? t.typeKey : '');
    $('pt-key').disabled = !!t;
    set('pt-w', t ? t.padWMm : '');
    set('pt-h', t ? t.padHMm : '');
    set('pt-round', t ? t.padRoundness : 0);
    $('pt-layout').value = t ? t.layout : 'single';
    set('pt-pitch', t && t.pitchMm !== null ? t.pitchMm : '');
    $('pt-status').value = t ? t.status : 'not-started';
    set('pt-t', IL.inputNum(t ? t.tMm : 0.1, 2));
    set('pt-k', IL.inputNum(t ? t.k : 1, 2));
    set('pt-pattern', t && t.packagePattern ? t.packagePattern : '');
    set('pt-notes', t && t.notes ? t.notes : '');
    $('pt-footprint').value = '';
    $('pt-error').replaceChildren();
    for (const n of $('padtype-form').querySelectorAll('.input--invalid')) n.classList.remove('input--invalid');
    IL.setText($('pt-save'), t ? 'Save version ' + next : 'Save');
    for (const x of IL.pt.editorExtras) x.open(t, srvProfile(S.status));
    PT.dlg = IL.dialog.showNative($('dlg-padtype'), { initialFocus: '#pt-name', onClose: () => { PT.dlg = null; } });
    ptDerived();
    loadPackages().then(() => ptDerived());
  }
  /** New pad type for a package of the plan that has none: pattern ^<package>$, its first footprint pad when known. */
  function newForPackage(pkg) {
    openPadType(null);
    if (!pkg) return;
    $('pt-pattern').value = '^' + pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
    $('pt-name').value = pkg;
    loadPackages().then(() => {
      const opt = Array.from($('pt-footprint').options).find((o) => o.value.split('\u0000')[0] === pkg);
      if (opt && !IL.num('pt-w')) { $('pt-footprint').value = opt.value; pickFootprint(); }
      ptDerived();
    });
    ptDerived();
  }
  function pickFootprint() {
    const v = $('pt-footprint').value;
    if (!v || !PT.pkgs) return;
    const [id, i] = v.split('\u0000');
    const pkg = PT.pkgs.find((p) => p.id === id);
    const pad = pkg && pkg.pads[Number(i)];
    if (!pad) return;
    $('pt-w').value = String(Number(pad.w.toFixed(4)));
    $('pt-h').value = String(Number(pad.h.toFixed(4)));
    $('pt-round').value = String(pad.roundness || 0);
    if (!$('pt-pattern').value) $('pt-pattern').value = '^' + id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
    if (!$('pt-layout').value || $('pt-layout').value === 'single') $('pt-layout').value = 'footprint-only';
    ptDerived();
  }
  function ptValues() {
    const fam = $('pt-family').value === '__other' ? IL.str('pt-family-other') : $('pt-family').value;
    return { family: fam, name: IL.str('pt-name'), typeKey: IL.str('pt-key'), w: IL.num('pt-w'), h: IL.num('pt-h'), round: IL.num('pt-round'),
      layout: $('pt-layout').value, pitch: IL.num('pt-pitch'), status: $('pt-status').value, t: IL.num('pt-t'), k: IL.num('pt-k'),
      pattern: IL.str('pt-pattern'), notes: IL.str('pt-notes') };
  }
  function ptErrors(v) {
    const e = {};
    if (!v.family) e['pt-family-other'] = 'Name the family.';
    if (!v.name) e['pt-name'] = 'A name is required.';
    if (!PT.editKey && v.typeKey && !/^[a-z0-9][a-z0-9-]{0,47}$/.test(v.typeKey)) e['pt-key'] = 'Lower-case letters, digits and - only.';
    if (!(v.w > 0)) e['pt-w'] = 'Enter the pad width in mm.';
    if (!(v.h > 0)) e['pt-h'] = 'Enter the pad height in mm.';
    if (v.round !== undefined && !(v.round >= 0 && v.round <= 100)) e['pt-round'] = '0 to 100 %.';
    if ((v.layout === 'two-terminal' || v.layout === 'sot23-3') && !(v.pitch > 0)) e['pt-pitch'] = 'This layout needs the pitch.';
    if (v.t !== undefined && !(v.t > 0)) e['pt-t'] = 'Above 0 mm.';
    if (v.k !== undefined && !(v.k > 0 && v.k <= 1)) e['pt-k'] = 'Above 0, at most 1.';
    if (v.pattern) { try { new RegExp(v.pattern, 'i'); } catch (x) { e['pt-pattern'] = 'Not a valid regular expression.'; } }
    for (const x of IL.pt.editorExtras) if (x.check) x.check(e);
    return e;
  }
  /** Live preview: pads to scale, area, stencil-equivalent volume, matched packages; field errors after a save try. */
  function ptDerived() {
    const v = ptValues();
    IL.show($('pt-pitch-wrap'), v.layout === 'two-terminal' || v.layout === 'sot23-3');
    const fig = $('pt-preview');
    fig.replaceChildren(padPreview(v));
    const area = v.w > 0 && v.h > 0 ? v.w * v.h - (4 - Math.PI) * Math.pow(Math.min(v.w, v.h) / 2 * Math.max(0, Math.min(100, v.round || 0)) / 100, 2) : null;
    const vol = area !== null && v.t > 0 && v.k > 0 ? area * v.t * v.k : null;
    const m = matchedPackages(v.pattern);
    const parts = [];
    if (area !== null) parts.push('Pad area ' + IL.fmt(area, 3) + ' mm²');
    if (vol !== null) parts.push('a stencil of this t and k would print ' + IL.fmt(vol * 1000, 1) + ' nl (information: every dot is one profile dot)');
    const out = [el('div', { text: parts.join(' · ') || 'Enter the pad size to see its area.' })];
    if (v.pattern) {
      out.push(el('div', { class: m && m.bad ? 'pt-derived__bad' : null }, m === null ? 'Package names: loading the packages…'
        : m.bad ? 'Package names: not a valid regular expression.'
          : 'Package names match ' + IL.plural(m.list.length, 'OpenPnP package') + (m.list.length ? ': ' + m.list.slice(0, 6).join(', ') + (m.list.length > 6 ? ' and ' + (m.list.length - 6) + ' more' : '') : '') + '.'));
    }
    $('pt-derived').replaceChildren(...out);
    const t = PT.editKey ? typeByKey(PT.editKey) : null;
    IL.setText($('pt-sum'), t ? (t.standIn ? 'New version ' + (t.nextVersion || 1) + ' for the ' + tipName(S.status) : 'Version ' + t.version + ' → ' + (t.nextVersion || t.version + 1))
      + (t.status !== v.status ? ' · status ' + STATUS_OPTION[t.status] + ' → ' + STATUS_OPTION[v.status] : '')
      : (v.name ? '“' + v.name + '” in ' + (v.family || '—') + ', version 1' : 'New type, version 1'));
    if ($('padtype-form').dataset.tried) {
      const errs = ptErrors(v);
      markPtErrors(errs);
      if (!Object.keys(errs).length) for (const n of $('pt-error').querySelectorAll('.pt-client')) n.remove();
    }
  }
  function markPtErrors(errs) {
    for (const n of $('padtype-form').querySelectorAll('.input')) {
      const msg = errs[n.id];
      n.classList.toggle('input--invalid', !!msg);
      const wrap = n.closest('.field');
      let m = wrap && wrap.querySelector('.field__err');
      if (msg && wrap && !m) wrap.appendChild(m = el('div', { class: 'field__err' }));
      if (m) { IL.setText(m, msg || ''); IL.show(m, !!msg); }
    }
  }
  function padPreview(v) {
    const W = 220, H = 120;
    const svg = IL.svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, role: 'img' });
    if (!(v.w > 0 && v.h > 0)) {
      svg.appendChild(IL.svgEl('text', { x: W / 2, y: H / 2, 'text-anchor': 'middle', class: 'pv-t' })).textContent = 'pad size';
      return svg;
    }
    const pitch = v.pitch > 0 ? v.pitch : 0;
    let centres = [[0, 0]];
    if (v.layout === 'two-terminal' && pitch) centres = [[-pitch / 2, 0], [pitch / 2, 0]];
    if (v.layout === 'sot23-3' && pitch) centres = [[-pitch / 2, -0.95], [-pitch / 2, 0.95], [pitch / 2, 0]];
    const xs = centres.flatMap(([x]) => [x - v.w / 2, x + v.w / 2]), ys = centres.flatMap(([, y]) => [y - v.h / 2, y + v.h / 2]);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const sc = Math.min((W - 24) / (maxX - minX), (H - 34) / (maxY - minY));
    const ox = W / 2 - (minX + maxX) / 2 * sc, oy = (H - 14) / 2 - (minY + maxY) / 2 * sc;
    const r = Math.min(v.w, v.h) / 2 * Math.max(0, Math.min(100, v.round || 0)) / 100 * sc;
    for (const [x, y] of centres) {
      svg.appendChild(IL.svgEl('rect', { x: ox + (x - v.w / 2) * sc, y: oy + (y - v.h / 2) * sc, width: v.w * sc, height: v.h * sc, rx: r, class: 'pv-pad' }));
      svg.appendChild(IL.svgEl('circle', { cx: ox + x * sc, cy: oy + y * sc, r: Math.max(2.5, Math.min(v.w, v.h) * sc * 0.18), class: 'pv-dot' }));
    }
    const lab = IL.svgEl('text', { x: W / 2, y: H - 3, 'text-anchor': 'middle', class: 'pv-t' });
    lab.textContent = pcr.fmtPad(v.w) + ' × ' + pcr.fmtPad(v.h) + ' mm' + (centres.length > 1 ? ' · pitch ' + pcr.fmtPad(pitch) : '') + (v.layout === 'footprint-only' ? ' · footprint pads win' : '');
    svg.appendChild(lab);
    return svg;
  }
  async function savePadType(ev) {
    ev.preventDefault();
    const btn = $('pt-save');
    if (btn.disabled) return;
    const form = $('padtype-form');
    form.dataset.tried = '1';
    const v = ptValues();
    const errs = ptErrors(v);
    markPtErrors(errs);
    $('pt-error').replaceChildren();
    if (Object.keys(errs).length) {
      $('pt-error').appendChild(el('div', { class: 'refusal pt-client' }, el('div', { class: 'refusal__t', text: 'Not saved: ' + IL.plural(Object.keys(errs).length, 'field') + ' to fix.' })));
      const first = form.querySelector('.input--invalid');
      if (first) first.focus();
      return;
    }
    const body = { family: v.family, name: v.name, padWMm: v.w, padHMm: v.h, padRoundness: v.round || 0, layout: v.layout,
      pitchMm: v.pitch === undefined ? null : v.pitch, tMm: v.t, k: v.k, status: v.status, packagePattern: v.pattern || null, notes: v.notes || null };
    const editKey = PT.editKey;
    if (!editKey && v.typeKey) body.typeKey = v.typeKey;
    body.forSetup = PT.dlgTip || tipKey(S.status);
    if (editKey && isNum(PT.dlgPadVersion)) body.padVersion = PT.dlgPadVersion;
    for (const x of IL.pt.editorExtras) if (x.body) x.body(body);
    await IL.act(btn, async () => {
      const r = editKey ? await IL.api('PUT', '/api/pad-types/' + encodeURIComponent(editKey), body) : await IL.api('POST', '/api/pad-types', body);
      if (PT.dlg) PT.dlg.close('saved');
      delete form.dataset.tried;
      PT.flashKey = r.padType.typeKey;
      await loadPadTypes();
      IL.planRefreshSoon();
      return r;
    }, { ok: (r) => 'Saved ' + r.padType.name + ' (' + r.padType.typeKey + ' v' + r.padType.version + ', for the ' + tipName(S.status) + ').'
      + (r.calibration && r.calibration.label ? ' Calibrated for ' + r.calibration.label + '.' : ''), refusalIn: $('pt-error') });
  }

  // ================================================================================== history dialog (#dlg-history)
  function buildHistoryDialog() {
    const close = el('button', { type: 'button', class: 'btn', id: 'history-close' }, 'Close');
    close.addEventListener('click', () => { if (PT.hist) PT.hist.close('close'); });
    const segThis = el('button', { type: 'button', 'aria-pressed': 'true', 'data-hist': 'this', id: 'history-this' }, 'This tip');
    const segAll = el('button', { type: 'button', 'aria-pressed': 'false', 'data-hist': 'all', id: 'history-all' }, 'All tips');
    for (const b of [segThis, segAll]) b.addEventListener('click', () => { PT.histTips = b.dataset.hist; if (PT.histData) historyTable(PT.histData); });
    return el('dialog', { id: 'dlg-history', class: 'dialog pt-dlg pt-hist', 'aria-labelledby': 'dlg-history-title' },
      el('div', { class: 'dlg-form' },
        el('div', { class: 'dialog__head' }, el('div', {}, el('div', { class: 'eyebrow', text: 'Pad type history' }), el('h2', { id: 'dlg-history-title', text: 'History' })),
          el('div', { class: 'dialog__keys' }, el('kbd', { text: 'Esc' }), ' closes ', el('kbd', { text: 'F9' }), ' stops')),
        el('div', { class: 'pt-hist__tools' }, el('div', { class: 'seg', role: 'group', 'aria-label': 'Which tips', id: 'history-tips' }, segThis, segAll)),
        el('div', { class: 'dialog__body', id: 'history-body' }),
        el('div', { class: 'dialog__foot' }, el('div', { class: 'dialog__sum', id: 'history-sum', text: 'Newest first. Changed values are marked against the version before of the same tip.' }), close)));
  }
  async function showHistory(key, btn) {
    const h = await IL.api('GET', '/api/pad-types/' + encodeURIComponent(key));
    PT.histData = h;
    PT.histTips = 'this';
    historyTable(h);
    PT.hist = IL.dialog.showNative($('dlg-history'), { initialFocus: '#history-close', onClose: () => { PT.hist = null; } });
    return h;
  }
  /** History: newest first, a Tip column; "changed" marks compare with the previous version of the same tip (§22.7). */
  function historyTable(h) {
    const key = h.current.typeKey;
    const mine = (h.forSetup && h.forSetup.key) || tipKey(S.status);
    for (const b of $('history-tips').children) IL.setAttr(b, 'aria-pressed', String(b.dataset.hist === PT.histTips));
    IL.setText($('dlg-history-title'), 'History of ' + h.current.name + ' (' + key + ')');
    const cols = [['v', (x) => 'v' + x.version, 'version'], ['Tip', (x) => x.setupName || x.setupKey || '—', null], ['Saved', (x) => IL.fmtDateTime(x.createdAtMs - S.skewMs), null], ['By', (x) => x.operator || '—', null],
      ['Family', (x) => x.family, 'family'], ['Status', (x) => (STATUS_OPTION[x.status] || x.status), 'status'],
      ['Pad mm', (x) => pcr.fmtPad(x.padWMm) + ' × ' + pcr.fmtPad(x.padHMm) + (x.padRoundness ? ' · ' + IL.fmt(x.padRoundness, 0) + ' %' : ''), 'pad'],
      ['Layout', (x) => (x.layout || '—') + (x.pitchMm ? ' · ' + pcr.fmtPad(x.pitchMm) : ''), 'layout'], ['t · k', (x) => IL.fmt(x.tMm, 3) + ' · ' + IL.fmt(x.k, 2), 'tk'],
      ['Packages', (x) => x.packagePattern || '—', 'pattern'], ['Notes', (x) => x.notes || '', 'notes']];
    const sig = (x, c) => c[1](x);
    const all = h.history.slice().sort((a, b) => b.version - a.version);
    const list = PT.histTips === 'all' ? all : all.filter((x) => (x.setupKey || mine) === mine);
    if (!list.some((x) => x.operator)) cols.splice(cols.findIndex((c) => c[0] === 'By'), 1);
    if (PT.histTips !== 'all') cols.splice(1, 1);
    $('history-body').replaceChildren(el('div', { class: 'pt-hist__wrap' }, el('table', { class: 'tbl pt-hist__t' },
      el('thead', {}, el('tr', {}, cols.map((c) => el('th', { scope: 'col' }, IL.unitText(c[0]))))),
      el('tbody', {}, list.length ? list.map((x) => {
        // the version before of the same tip
        const older = all.find((y) => y.version < x.version && (y.setupKey || mine) === (x.setupKey || mine));
        return el('tr', { 'data-setup': x.setupKey || '' }, cols.map((c) => {
          const changed = older && c[2] && c[2] !== 'version' && sig(x, c) !== sig(older, c);
          return el('td', { class: (changed ? 'chg' : '') + (c[0] === 'Notes' || c[0] === 'Packages' || c[0] === 'Family' || c[0] === 'Tip' ? ' wrap' : ''), title: changed ? 'Changed from: ' + sig(older, c) : null, text: sig(x, c) });
        }));
      }) : [el('tr', {}, el('td', { colspan: String(cols.length), class: 'muted', text: 'No version for the ' + tipName(S.status) + ' yet: All tips shows the others.' }))]))));
    const newest = all[0];
    const other = all.find((x) => x.setupKey && x.setupKey !== mine);
    IL.setText($('history-sum'), 'Newest first. Changed values are marked against the version before of the same tip.'
      + (other ? ' Version numbers count across tips: v' + other.version + ' is the ' + (other.setupName || other.setupKey) + '’s.' : newest ? ' Version numbers count across tips.' : ''));
  }

  // ================================================================================== context panel `pt`
  function dial(e) {
    const W = 208, H = 168, cx = W / 2, cy = H / 2, R = 56;
    const svg = IL.svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, class: 'pf-dial', role: 'img' });
    const rev = e.stepsPerRev || 1600, spd = e.stepsPerDeg || 4.44;
    const pt = (deg, r) => [cx + r * Math.sin(deg * Math.PI / 180), cy - r * Math.cos(deg * Math.PI / 180)];
    const arc = (a0, sweep, r) => {
      const s = Math.min(Math.abs(sweep), 359.9) * Math.sign(sweep || 1);
      const [x0, y0] = pt(a0, r), [x1, y1] = pt(a0 + s, r);
      return 'M' + x0.toFixed(2) + ' ' + y0.toFixed(2) + ' A' + r + ' ' + r + ' 0 ' + (Math.abs(s) > 180 ? 1 : 0) + ' ' + (s > 0 ? 1 : 0) + ' ' + x1.toFixed(2) + ' ' + y1.toFixed(2);
    };
    svg.appendChild(IL.svgEl('circle', { cx, cy, r: R, class: 'pf-dial__ring' }));
    for (let a = 0; a < 360; a += 30) { const [x0, y0] = pt(a, R - 4), [x1, y1] = pt(a, R + 4); svg.appendChild(IL.svgEl('line', { x1: x0, y1: y0, x2: x1, y2: y1, class: 'pf-dial__tick' })); }
    const net = Number(e.netStepsPerDot) || 800;
    const partial = net % rev !== 0;
    const startSteps = ((Math.round(Number(e.startAngleDeg) / 360 * rev) % rev) + rev) % rev;
    const starts = [];
    if (partial) for (let k = 0; k < 8; k++) { const a = ((startSteps + k * net) % rev) * 360 / rev; if (starts.some((x) => Math.abs(x - a) < 1e-6)) break; starts.push(a); }
    const back = Math.round(Number(e.suckBackDeg) * spd) / spd;
    const fwd = net / spd + back;
    const a0 = partial ? starts[0] : 0;
    svg.appendChild(IL.svgEl('path', { d: arc(a0, Math.min(fwd, 359.9), R), class: 'pf-dial__fwd' }));
    svg.appendChild(IL.svgEl('path', { d: arc(a0 + fwd, -Math.min(back, 359), R - 9), class: 'pf-dial__back' }));
    for (const a of starts) {
      const [x, y] = pt(a, R);
      svg.appendChild(IL.svgEl('circle', { cx: x, cy: y, r: 5.5, class: 'pf-dial__start' }));
      const sn = Math.sin(a * Math.PI / 180), cs = Math.cos(a * Math.PI / 180);
      const [lx, ly] = pt(a, R + 11);
      const t = IL.svgEl('text', { x: lx, y: ly + 4 - cs * 5, 'text-anchor': sn > 0.35 ? 'start' : sn < -0.35 ? 'end' : 'middle', class: 'pf-dial__lab' });
      t.textContent = IL.fmt(a, 1) + '°';
      svg.appendChild(t);
    }
    const c = IL.svgEl('text', { x: cx, y: cy - 2, 'text-anchor': 'middle', class: 'pf-dial__c' });
    c.textContent = IL.turnLabel(net, rev);
    svg.appendChild(c);
    const c2 = IL.svgEl('text', { x: cx, y: cy + 15, 'text-anchor': 'middle', class: 'pf-dial__c2' });
    c2.textContent = 'per dot';
    svg.appendChild(c2);
    svg.setAttribute('aria-label', 'Screw dial: forward ' + IL.fmt(fwd, 2) + '°, back ' + IL.fmt(back, 2) + '°' + (starts.length ? ', dots start at ' + starts.map((x) => IL.fmt(x, 1) + '°').join(' and ') : ''));
    return { svg, fwd, back, net: net / spd, starts };
  }
  function renderOverview(p, s, first) {
    if (first) {
      p.body.append(
        el('div', { class: 'panel__title' }, el('h2', { text: 'What this profile does' })),
        el('div', { id: 'pt-ov-does' }),
        el('div', { class: 'section-k' }, el('span', { text: 'Pad-type gate' }), el('span', { class: 'rule' })),
        el('div', { id: 'pt-ov-gate' }),
        el('div', { class: 'section-k', id: 'pt-ov-plan-k' }, el('span', { text: 'Pad types in this plan' }), el('span', { class: 'rule' })),
        el('div', { id: 'pt-ov-plan' }));
    }
    const b = baseProfile(s);
    if (!b) return;
    const e = PF.dirty.size ? editedProfile(s) : b;
    const valid = !Object.keys(PF.errors).length;
    const key = JSON.stringify([valid ? FIELDS.map((f) => e[f.key]) : 'invalid', b.startAngleDeg, PF.dirty.size > 0]);
    IL.keyed($('pt-ov-does'), key, () => {
      if (!valid) return el('div', { class: 'notebox notebox--block', text: 'Fix the marked profile fields to see what a dot does.' });
      const dl = dial(e);
      const turn = IL.turnLabel(e.netStepsPerDot, e.stepsPerRev);
      return [PF.dirty.size ? el('div', { class: 'notebox notebox--need pt-ov-unsaved', text: 'Shown with your unsaved values. Saving makes existing plans stale.' }) : null,
        el('div', { class: 'pf-does' }, dl.svg,
          el('ul', { class: 'pf-legend' },
            el('li', {}, el('i', { class: 'pf-legend__fwd' }), 'forward ' + IL.fmt(dl.fwd, 2) + '°'),
            el('li', {}, el('i', { class: 'pf-legend__back' }), IL.nb('back ' + IL.fmt(dl.back, 2) + '° (suck-back)')),
            el('li', {}, el('i', { class: 'pf-legend__start' }), dl.starts.length ? 'dots start at ' + dl.starts.map((x) => IL.fmt(x, 1) + '°').join(' / ') : 'whole turns: any start angle'))),
        el('ol', { class: 'pf-steps' },
          el('li', {}, el('b', { text: 'Pressure. ' }), 'The regulator holds ' + IL.fmtPsi(e.pressurePsi) + ' psi on the barrel. It is set by hand: no sensor reads it.'),
          el('li', {}, el('b', { text: 'Before each board. ' }), IL.plural(e.purgeDots, 'purge dot') + ' on the purge row' + (dl.starts.length ? '; the first turns the screw to the start angle ' + IL.fmtDeg(e.startAngleDeg, 1)
            // the dial shows the angle in whole motor steps (298.0° → 297.9°): say so once, here
            + (IL.fmt(dl.starts[0], 1) !== IL.fmt(e.startAngleDeg, 1) ? ' (' + IL.fmtDeg(dl.starts[0], 1) + ' in whole motor steps)' : '') : '') + '.'),
          el('li', {}, el('b', { text: 'Each dot. ' }), 'The needle stops ' + IL.fmt(e.gapMm, 2) + ' mm above the pad. The screw turns forward ' + IL.fmt(dl.fwd, 2) + '° at ' + IL.fmtInt(e.screwRpm)
            + ' rpm, waits ' + e.dwellMs + ' ms, then turns back ' + IL.fmt(dl.back, 2) + '°: net ' + turn + ' (' + IL.fmt(dl.net, 2) + '°).'),
          el('li', {}, el('b', { text: 'Plans. ' }), 'A plan remembers this recipe. After a change, a board is refused until it is planned again.'))];
    });
    const gate = gateStatus(s);
    const types = allTypes().filter((t) => !t.retired);
    const pass = types.filter((t) => t.dispensable);
    const stats = IL.pt.planStats();
    const blocked = Object.entries(stats).filter(([, v]) => v.blocked);
    IL.keyed($('pt-ov-gate'), JSON.stringify([gate, PT.ver, S.plan ? S.plan.planId : '', blocked.length]), () => {
      const gi = STATUS_ORDER.indexOf(gate);
      return [el('div', { class: 'pt-gate' },
        el('div', { class: 'pt-gate__t' }, 'Lowest pad-type status allowed on a board: ', el('b', { text: STATUS_OPTION[gate] || gate })),
        el('ol', { class: 'pt-ladder', 'aria-label': 'Pad-type statuses, lowest first' }, STATUS_ORDER.map((k, i) => el('li', { class: i >= gi ? 'on' : null },
          IL.glyph(i >= gi ? 'ok' : 'off', 'xs'), el('span', { text: STATUS_OPTION[k] }), i === gi ? el('span', { class: 'tag tag--now', text: 'gate' }) : null))),
        el('div', { class: 'pt-gate__s', text: PT.data ? pass.length + ' of ' + types.length + ' pad types ' + (pass.length === 1 ? 'is' : 'are') + ' allowed on a board'
          + (pass.length ? ': ' + pass.map((t) => t.name).join(', ') + '.' : '.') : 'Loading the pad types…' }),
        IL.fixRow([{ href: '#setup/settings', label: 'Change the gate in Settings' }], 'btn-row'))];
    });
    const hasPlan = !!(S.plan && S.plan.pads);
    IL.show($('pt-ov-plan-k'), hasPlan);
    IL.keyed($('pt-ov-plan'), JSON.stringify([hasPlan ? S.plan.planId : '', PT.ver, Object.keys(stats).length]), () => {
      if (!hasPlan) return null;
      const rows = Object.entries(stats).sort((a, b) => b[1].pads - a[1].pads);
      return el('ul', { class: 'pt-plan' }, rows.map(([k, v]) => {
        const t = typeByKey(k);
        const btn = el('button', { type: 'button', class: 'pt-plan__i', 'data-focus-key': 'plan:' + k },
          t ? pcr.badge(t.status, t.retired) : IL.glyph('block', 'xs'),
          el('span', { class: 'pt-plan__n' }, breakable(t ? t.name : v.label || k)),
          el('span', { class: 'pt-plan__v' + (v.blocked ? ' pt-blocked' : ''), text: IL.plural(v.pads, 'pad') + (v.blocked ? ' · ' + v.blocked + ' blocked' : '') }),
          IL.icon('i-chev'));
        btn.addEventListener('click', () => (t ? IL.go('#profile/pad-types/' + k) : IL.go(v.href || '#profile/new-pad-type/' + ([...v.packages][0] || ''))));
        if (!t) btn.title = 'No pad type matches these packages (' + [...v.packages].join(', ') + '): make one';
        return el('li', {}, btn);
      }));
    });
  }
  function renderTypePanel(p, s, first) {
    const key = PT.sel;
    const t = typeByKey(key);
    if (first) {
      const back = el('button', { type: 'button', class: 'btn btn--quiet btn--icon', id: 'pt-back', 'aria-label': 'Back to the profile summary', title: 'Back (Esc)' }, IL.icon('i-back'));
      back.addEventListener('click', () => IL.go('#profile'));
      p.top.append(el('div', { class: 'pt-ph' }, back, el('div', { class: 'pt-ph__t' }, el('div', { class: 'eyebrow', id: 'pt-ph-e', text: 'Pad type' }), el('h2', { id: 'pt-ph-n', text: key })), el('span', { id: 'pt-ph-b' })));
      // the pad-rule form is its own region: rebuilt only for another type or a newly saved rule, never on a poll or when
      // the package list arrives (typed values stay, §15)
      p.body.append(el('div', { id: 'pt-side' }), el('section', { id: 'pt-side-tips', class: 'pt-side-tips', 'aria-label': 'Other tips', 'data-refusals': '' }),
        el('div', { id: 'pt-rule-box' }), el('div', { id: 'pt-retire-box' }));
      const edit = el('button', { type: 'button', class: 'btn btn--primary', id: 'pt-side-edit' }, 'Edit…');
      const hist = el('button', { type: 'button', class: 'btn', id: 'pt-side-history' }, 'History');
      edit.addEventListener('click', () => { const x = typeByKey(PT.sel); if (x) openPadType(x); });
      hist.addEventListener('click', () => IL.act(hist, () => showHistory(PT.sel, hist)));
      p.foot.append(el('div', { class: 'btn-row', 'data-refusals': '' }, edit, hist));
    }
    const clearRest = () => { IL.keyed($('pt-rule-box'), '', () => null); IL.keyed($('pt-retire-box'), '', () => null); IL.keyed($('pt-side-tips'), '', () => null); };
    if (!PT.data) { IL.keyed($('pt-side'), 'loading', () => el('p', { class: 'panel__lead', text: 'Loading the pad types…' })); clearRest(); return; }
    if (!t) {
      IL.keyed($('pt-side'), 'missing' + key, () => el('div', { class: 'notebox notebox--need' }, 'No pad type “' + key + '”. ', el('button', { type: 'button', class: 'link', text: 'Back to the profile', onclick: () => IL.go('#profile') })));
      clearRest();
      return;
    }
    IL.setText($('pt-ph-n'), t.name);
    IL.setText($('pt-ph-e'), (t.standIn ? 'not for the ' + tipName(s) + ' yet' : 'v' + t.version) + ' · ' + t.family);
    IL.keyed($('pt-ph-b'), t.status + t.retired, () => pcr.badge(t.status, t.retired));
    const st = IL.pt.planStats()[t.typeKey];
    const m = matchedPackages(t.packagePattern);
    if (t.packagePattern && !PT.pkgs) loadPackages();
    const rule = t.rule || {};
    const sideKey = JSON.stringify([t.typeKey, t.version, t.rule ? t.rule.version : 0, t.dispensable, t.dotsPerPad, t.lastValidatedAtMs, !!PT.pkgs, st ? [st.pads, st.blocked] : 0, gateStatus(s)]);
    IL.keyed($('pt-side'), sideKey, () => {
      const area = t.padAreaMm2;
      const setUp = el('button', { type: 'button', class: 'btn', title: 'Calibrate around this pad size: dot ladder and scale (records only)' }, 'Set up');
      const validate = el('button', { type: 'button', class: 'btn', title: 'Dispense a coupon at this size and compare with the prediction' }, 'Validate');
      setUp.addEventListener('click', () => guide(t, false));
      validate.addEventListener('click', () => guide(t, true));
      return [
        t.dispensable ? null : el('div', { class: 'notebox notebox--need' }, IL.glyph('need', 'xs'), ' Below the gate (', el('b', { text: STATUS_OPTION[gateStatus(s)] }), '): boards refuse its pads.'),
        el('dl', { class: 'kv kv--wrap' },
          [['Pad', pcr.fmtPad(t.padWMm) + ' × ' + pcr.fmtPad(t.padHMm) + ' mm' + (t.padRoundness ? ' · ' + IL.fmt(t.padRoundness, 0) + ' % round' : '') + ' · ' + IL.fmt(area, 3) + ' mm²'],
            ['Layout', (LAYOUTS[t.layout] || t.layout) + (t.pitchMm ? ' · pitch ' + pcr.fmtPad(t.pitchMm) + ' mm' : '')],
            ['Dot plan', IL.pt.texts.dotPlan(t, srvProfile(s))],
            ['Stencil equivalent', 't ' + IL.fmt(t.tMm, 2) + ' mm · k ' + IL.fmt(t.k, 2) + ' → ' + IL.fmt(t.targetMassMg, 3) + ' mg (information)'],
            ['In this plan', st ? IL.plural(st.pads, 'pad') + ', ' + IL.plural(st.dots, 'dot') + (st.blocked ? ', ' + st.blocked + ' blocked' : '') : S.plan ? 'not used' : 'no plan shown'],
            ['Versions', t.standIn ? 'none for the ' + tipName(s) + ' yet (the pad: version ' + t.padVersion + ')'
              : t.versions + ' · this one ' + IL.fmtDateTime(t.createdAtMs - S.skewMs) + (t.operator ? ' by ' + t.operator : '')],
            ['Last validated', t.lastValidatedAtMs ? IL.fmtDateTime(t.lastValidatedAtMs - S.skewMs) : 'never'],
          ].flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: IL.nb(v) })])),
        t.notes ? el('p', { class: 'panel__lead', text: t.notes }) : null,
        el('div', { class: 'section-k' }, el('span', { text: 'Calibrate this size' }), el('span', { class: 'rule' })),
        el('div', { class: 'btn-row' }, setUp, validate, IL.pt.sidePanelExtras.map((f) => f(t, s))),
        el('div', { class: 'section-k' }, el('span', { text: 'Packages it matches' }), el('span', { class: 'rule' })),
        el('div', { class: 'pt-pkgs' }, !t.packagePattern ? el('span', { class: 'muted', text: 'No package pattern: assigned only by the engine’s own rules (e.g. QFN centre pads).' })
          : el('div', {}, el('code', { class: 'pt-pat', text: t.packagePattern }),
            el('div', { class: 'pt-pkgs__l' }, m === null ? 'Loading OpenPnP packages…' : m.bad ? 'Not a valid regular expression.'
              : m.list.length ? m.list.join(', ') : 'No OpenPnP package matches.')))];
    });
    // the other tips' versions of this type (§22.7): status, label, result picture, and a copy of their dots to start from
    const det = typeDetail(t);
    const tk = JSON.stringify([t.typeKey, t.version, t.status, t.standIn, t.others, det ? det.ver : null, tipKey(s)]);
    const tipsBuilt = IL.keyed($('pt-side-tips'), tk, () => sideTips(t, det, s));
    if (PT.focusTips && (tipsBuilt || !PT.focusDone)) {
      PT.focusDone = true;
      const sel = PT.focusSetup ? '#pt-side-tips .pt-tip[data-setup="' + CSS.escape(PT.focusSetup) + '"]' : '#pt-side-tips';
      requestAnimationFrame(() => { const n = document.querySelector(sel); if (n) { n.scrollIntoView({ block: 'nearest' }); IL.flash(n); } });
      PT.focusTips = false;
    }
    IL.keyed($('pt-rule-box'), JSON.stringify([t.typeKey, t.rule ? t.rule.version : 0]), () => {
      const ruleSave = el('button', { type: 'button', class: 'btn', id: 'btn-pt-rule-save' }, 'Save pad rule');
      ruleSave.addEventListener('click', () => IL.act(ruleSave, () => saveRule(t), { ok: (r) => 'Pad rule saved for ' + t.name + ' (v' + r.version + ').' }));
      const rnum = (id, val, step, extra) => el('input', Object.assign({ class: 'input', id, type: 'number', step, inputmode: 'decimal', value: isNum(val) ? String(val) : '', autocomplete: 'off' }, extra || {}));
      return [
        el('div', { class: 'section-k' }, el('span', { text: 'Pad rule' }), el('span', { class: 'rule' }), t.rule ? el('span', { class: 'count', text: 'v' + t.rule.version }) : null),
        el('div', { class: 'pt-rule', 'data-enter-submits': 'btn-pt-rule-save', 'data-refusals': '' },
          el('p', { class: 'field__h', text: 'Large pads can get a grid of profile dots: above the largest single-dot area, a pad gets area ÷ that area dots, rounded up, at most the maximum.' }),
          el('div', { class: 'fgrid' },
            ptField('pt-rule-largest', 'Largest pad for one dot', 'mm²', rnum('pt-rule-largest', rule.largestSingleDotPadMm2, '0.1', { min: '0', placeholder: 'no grid' })),
            ptField('pt-rule-maxdots', 'Most dots on one pad', '1 to 64', rnum('pt-rule-maxdots', rule.maxDots, '1', { min: '1', max: '64', placeholder: '6' }))),
          el('div', { class: 'pt-rule__pre', id: 'pt-rule-pre' }),
          ptField('pt-rule-notes', 'Notes', null, el('input', { class: 'input', id: 'pt-rule-notes', type: 'text', autocomplete: 'off', value: rule.notes || '' })),
          el('details', { class: 'disclose' }, el('summary', { text: 'Fit factor (kept for older records)' }),
            ptField('pt-rule-fit', 'Fit factor', 'unused since the dose model was removed', rnum('pt-rule-fit', rule.fitFactor, '0.05', { min: '0' }))),
          el('div', { class: 'btn-row' }, ruleSave))];
    });
    IL.keyed($('pt-retire-box'), t.typeKey + '|' + !!t.retired, () => {
      const retire = el('button', { type: 'button', class: 'btn btn--danger-ghost', id: 'pt-side-retire' }, 'Retire for every tip…');
      IL.press2(retire, 'retire ' + t.name + ' for every tip', async () => {
        const body = { forSetup: tipKey(S.status) };
        if (isNum(t.padVersion)) body.padVersion = t.padVersion;
        const r = await IL.api('DELETE', '/api/pad-types/' + encodeURIComponent(t.typeKey), body);
        IL.go('#profile');
        await loadPadTypes();
        return r;
      }, { ok: (r) => 'Retired ' + t.name + ': ' + ((r && r.note) || 'kept in the history, never matched to a package, never dispensed') + '.' });
      return [
        t.retired ? null : el('div', { class: 'pt-retire', 'data-refusals': '' },
          el('div', { class: 'section-k' }, el('span', { text: 'Retire' }), el('span', { class: 'rule' })),
          el('p', { class: 'field__h', text: 'A retired type is never matched to a package or dispensed, whichever tip is on. It stays in the history.' }), retire)];
    });
    // live rule preview (cheap; inputs are never rewritten)
    const lg = IL.num('pt-rule-largest'), mx = IL.num('pt-rule-maxdots');
    const n = lg > 0 && t.padAreaMm2 > lg ? Math.min(mx > 0 ? mx : 6, Math.ceil(t.padAreaMm2 / lg - 1e-9)) : 1;
    IL.setText($('pt-rule-pre'), 'With this rule a ' + pcr.fmtPad(t.padWMm) + ' × ' + pcr.fmtPad(t.padHMm) + ' mm pad (' + IL.fmt(t.padAreaMm2, 2) + ' mm²) gets ' + IL.plural(n, 'dot') + '.');
    IL.gate($('pt-side-retire'), IL.lockReason(s, { idle: false, pressureOff: false }));
  }
  /** GET /api/pad-types/{key} (its versions per tip, bySetup): read once per type version, for the other tips' dot plans. */
  function typeDetail(t) {
    const k = t.typeKey + '@' + t.version + '@' + (t.others || []).map((o) => o.setupKey + o.version).join(',');
    PT.details = PT.details || {};
    const d = PT.details[k];
    if (!d) {
      const x = PT.details[k] = { loading: true, ver: 0 };
      IL.api('GET', '/api/pad-types/' + encodeURIComponent(t.typeKey)).then((r) => { x.r = r; }, () => { x.r = null; }).then(() => { x.loading = false; x.ver = 1; IL.render(); });
      return null;
    }
    return d.loading ? null : d;
  }
  const TURN_ADJ = { 400: 'quarter-turn', 800: 'half-turn', 1600: 'one-turn' };
  /** section#pt-side-tips: per other tip div.pt-tip[data-setup] (version, status, label, result photo, Copy its dots). */
  function sideTips(t, det, s) {
    const others = t.others || [];
    const tn = tipName(s);
    const below = t.standIn || !['characterized', 'validated'].includes(t.status);
    const prof = srvProfile(s) || {};
    const out = [IL.sectionK('Other tips', others.length || null)];
    if (!others.length) { out.push(el('p', { class: 'muted pt-tip__none', text: 'Not calibrated for any other tip.' })); return out; }
    for (const o of others) {
      const v = det && det.r && det.r.bySetup ? det.r.bySetup[o.setupKey] : null;
      const steps = v ? (v.dotSteps || prof.netStepsPerDot) : null;
      const plan = v && isNum(v.dotsPerPad) ? v.dotsPerPad + ' ' + (TURN_ADJ[steps] || IL.turnLabel(steps)) + ' ' + (v.dotsPerPad === 1 ? 'dot' : 'dots') + ' per pad' : null;
      let pic = null;
      if (o.thumbUrl) {
        pic = el('button', { type: 'button', class: 'photo-thumb photo-thumb--sm', title: 'The result photo of the ' + o.setupName + '’s calibration' },
          el('img', { src: o.thumbUrl, alt: 'Result photo: ' + (o.label || o.setupName), loading: 'lazy', width: '72', height: '54' }));
        pic.addEventListener('click', () => IL.photos && IL.photos.view({ id: o.calibrationId, url: o.thumbUrl.replace(/\/thumb\.jpg$/, '.jpg'), thumbUrl: o.thumbUrl,
          caption: o.label || 'Calibrated for the ' + o.setupName, kind: 'result', atMs: o.atMs }, { from: pic }));
      }
      let copy = null, note = null;
      if (below && o.status && o.status !== 'not-started') {
        copy = el('button', { type: 'button', class: 'btn btn--sm', 'data-action': 'copy-from', 'data-setup': o.setupKey }, 'Copy its dots to start from');
        note = el('div', { class: 'pt-tip__note', text: 'Saves a provisional version for the ' + tn + ' with this plan' + (plan ? ': ' + plan : '') + '. Nothing measured comes with it.' });
        copy.addEventListener('click', () => IL.act(copy, async () => {
          const r = await IL.api('POST', '/api/pad-types/' + encodeURIComponent(t.typeKey) + '/copy', { fromSetup: o.setupKey, forSetup: tipKey(S.status) });
          await loadPadTypes();
          IL.planRefreshSoon();
          return r;
        }, { ok: (r) => 'Saved ' + t.name + ' v' + r.padType.version + ' for the ' + tn + ' as provisional: copied from the ' + o.setupName + ', nothing measured.' }));
      }
      out.push(el('div', { class: 'pt-tip', 'data-setup': o.setupKey },
        el('div', { class: 'pt-tip__h' }, el('b', { text: o.setupName }), pcr.badge(o.status), el('span', { class: 'dim', text: 'v' + o.version + (o.atMs ? ' · ' + IL.fmtDateTime(IL.toClient(o.atMs)) : '') })),
        o.label ? el('div', { class: 'pt-tip__l', text: 'Calibrated for ' + o.label }) : null,
        plan ? el('div', { class: 'pt-tip__p', text: plan }) : null,
        pic || copy ? el('div', { class: 'pt-tip__a' }, pic, copy) : null, note));
    }
    return out;
  }
  async function saveRule(t) {
    const body = { maxDots: IL.num('pt-rule-maxdots') === undefined ? null : IL.num('pt-rule-maxdots'),
      fitFactor: IL.num('pt-rule-fit') === undefined ? null : IL.num('pt-rule-fit'),
      largestSingleDotPadMm2: IL.num('pt-rule-largest') === undefined ? null : IL.num('pt-rule-largest'), notes: IL.str('pt-rule-notes') || null };
    if (body.maxDots !== null && !(Number.isInteger(body.maxDots) && body.maxDots >= 1 && body.maxDots <= 64)) throw new Error('Most dots on one pad: a whole number 1 to 64.');
    if (body.largestSingleDotPadMm2 !== null && !(body.largestSingleDotPadMm2 > 0)) throw new Error('Largest pad for one dot: above 0 mm² (or empty for no grid).');
    const r = await IL.api('PUT', '/api/pad-types/' + encodeURIComponent(t.typeKey) + '/rule', body);
    await loadPadTypes();
    IL.planRefreshSoon();
    return r;
  }
  function guide(t, validate) {
    if (IL.pcr.openGuide) IL.pcr.openGuide(t, validate);
    IL.go('#calibrate');
  }
  function selectType(key) {
    if (PT.sel === key) return;
    PT.sel = key || null;
    if (PT.escOff) { PT.escOff(); PT.escOff = null; }
    if (PT.sel) PT.escOff = IL.onEsc(() => { if (IL.panel.isPushed() || IL.active() !== 'profile' || !PT.sel) return false; IL.go('#profile'); return true; });
    if (IL.active() === 'profile') IL.panel.reset();
  }

  // ================================================================================== hooks (padprofile.js)
  /**
   * What padprofile.js may change or add (PROFILE_CALIBRATION_DESIGN.md §13.3, hook H-P3): profile.js calls these
   * through IL.pt; without padprofile.js they are profile.js's own.
   */
  IL.pt = { PT, route: null, panel: null, planStats, newForPackage, openPadType, selectType, filters: {}, sectionExtras: [], renderExtras: [],
    sidePanelExtras: [], editorExtras: [], navSubs: [],
    /** Unsaved recipe or pad-type edits (Change tip's Mount waits for them, §22.7). */
    unsaved: () => PF.dirty.size > 0 || !!PT.dlg,
    texts: {
      meta: 'One recipe for every dot. Plans made before a change are refused: plan again.',
      dotsCol: 'Dots per pad, each one dot of the dispense profile',
      hint: (prof) => 'Every dot is one dot of the dispense profile' + (prof ? ' “' + prof.name + '” (' + IL.turnLabel(prof.netStepsPerDot, prof.stepsPerRev) + ')' : '') + '.',
      dotsCell: (t) => (t.dotsPerPad ? IL.plural(t.dotsPerPad, 'dot') : '—'),
      dotTitle: (t, prof) => 'Each pad gets ' + (t.dotsPerPad ? IL.plural(t.dotsPerPad, 'dot') : '—') + ' of the profile' + (prof ? ' “' + prof.name + '”' : '')
        + ' (' + IL.fmt(t.rotationPerDotDeg, 2) + '° net per dot).',
      dotPlan: (t) => IL.plural(t.dotsPerPad, 'dot') + ' of the profile per pad (' + IL.fmt(t.rotationPerDotDeg, 2) + '° each)',
    } };
  if (IL.padProfile) IL.padProfile.profileHooks(IL.pt);

  // ================================================================================== workspace
  let root = null;
  IL.workspace('profile', {
    defaultView: 'pt',
    mount(r) {
      root = r;
      root.append(
        el('div', { class: 'page-head' },
          el('div', { class: 'page-head__text' },
            el('div', { class: 'eyebrow', text: 'Profile · dispense recipe and pad types' }),
            el('h1', {}, 'Profile', el('span', { class: 'h1-sub', id: 'pf-h1-sub' })),
            el('div', { class: 'meta pf-meta' }, el('span', { id: 'pf-recipe' }), el('span', { text: IL.pt.texts.meta })))),
        buildProfileCard(),
        buildAngleCard(),
        buildPadTypesSection(),
        buildPadTypeDialog(),
        buildHistoryDialog());
      pcr.stickyTop(root);
    },
    show() { loadPadTypes(); },
    route(r) {
      if (IL.pt.route && IL.pt.route(r)) return;
      if (r.section === 'new-pad-type') {
        selectType(null);
        IL.pt.newForPackage(r.id);
        setTimeout(() => IL.go('#profile/pad-types', { replace: true }), 0);   // a reload must not reopen it
        return;
      }
      if (r.section === 'pad-types') {
        selectType(r.id || null);
        const go = () => {
          const target = r.id ? root.querySelector('tr[data-key="' + CSS.escape(r.id) + '"]') : $('pt-section');
          if (target) target.scrollIntoView({ block: r.id ? 'center' : 'start' });
        };
        if (PT.data) requestAnimationFrame(go);
        else { const off = IL.on('padtypes', () => { off(); requestAnimationFrame(go); }); }
      } else {
        selectType(null);
        if (r.section === 'angle') IL.flash($('pf-angle-card'));
        else if (r.section === 'profile' || r.section === 'card') IL.flash($('pf-card'));
      }
    },
    render(s) {
      const p = srvProfile(s);
      if (p) {
        IL.setText($('pf-title-name'), p.name);
        // the mounted tip (§22.7): its picture, "· for the {S}" and Change tip…
        const tn = tipName(s), thumbUrl = s.dispense.tip && s.dispense.tip.thumbUrl;
        IL.setText($('pf-title-tip'), ' · the recipe of the ' + tn + ': every dot on a board and on the purge row');
        IL.keyed($('pf-h1-sub'), JSON.stringify([p.name, tn, thumbUrl]), () => {
          const change = el('button', { type: 'button', class: 'link pf-tip-change', id: 'pf-tip-change', text: 'Change tip…' });
          change.addEventListener('click', () => { if (IL.tips) IL.tips.openChange({}); });
          return [thumbUrl ? el('img', { class: 'tip-thumb tip-thumb--xs', src: thumbUrl, alt: 'The ' + tn }) : el('span', { class: 'tip-thumb tip-thumb--xs tip-thumb--none', title: 'No picture of the ' + tn + ' yet' }, IL.tips ? IL.tips.noPicture() : null),
            el('span', { class: 'pf-h1-sub__t', text: p.name + ' · for the ' + tn }), change];
        });
        IL.keyed($('pf-recipe'), JSON.stringify(IL.recipeParts(p)), () => IL.recipeEl(p, { title: 'The saved dispense profile' }));
        syncProfile(s);
        renderAngle(s);
      }
      // another tip mounted (or its details corrected): its own pad types (§22.2)
      const wk = p ? p.fingerprint + '|' + (s.dispense.minPadTypeStatus || '') + '|' + tipKey(s) + '@' + ((s.dispense.tip || {}).setupVersion || '') : '';
      if (PT.watchKey !== null && wk !== PT.watchKey && PT.data) loadPadTypes();
      PT.watchKey = wk;
      renderPadTypes(s);
      for (const f of IL.pt.renderExtras) f(s);
    },
    panel(parts, s, first) {
      if (IL.pt.panel && IL.pt.panel(parts, s, first)) return;
      if (!first && PT.panelSel !== PT.sel) { IL.panel.reset(); return; }
      if (first) PT.panelSel = PT.sel;
      if (PT.sel) renderTypePanel(parts, s, first);
      else renderOverview(parts, s, first);
    },
    nav(s) {
      const p = srvProfile(s) || {};
      if (PF.dirty.size) return { sub: 'Unsaved changes', kind: 'need', dirty: true, title: 'Unsaved profile changes: they stay here until you save or discard them' };
      if (p.partialTurn && p.startAngleNeedsConfirm) return { sub: 'Confirm angle', kind: 'need' };
      if (p.partialTurn && !p.startAngleSetAtMs) return { sub: 'Measure angle', kind: 'block' };
      for (const f of IL.pt.navSubs) { const x = f(s); if (x) return x; }
      return { sub: p.name || '' };
    },
  });
})();
