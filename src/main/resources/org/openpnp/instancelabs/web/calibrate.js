/* calibrate.js: the Calibrate workspace (docs/instancelabs/UI_DESIGN.md §8.4). Records only: boards use the dispense
   profile. Cards (route #calibrate/<card>): touch-off (#to-*, result + offset change in place, "Use for the needle
   offset", history), start angle (#pf-angle, #btn-pf-angle-set, guided dot pairs), scale shots (#shots-*, record form
   #ss-*), dot batch (#sd-*), dot ladder (#dl-*), records (#cal-current, #cal-table, #ladder-table, #cal-result).
   Each moving card lists its prerequisites as readiness rows with fix-it links and shows progress / result in place;
   moving actions are two-press. #setup-guide is filled by Set up / Validate from a pad type (IL.pcr.openGuide).
   Context panel `cal`: the running procedure, the last result, prerequisites and the head position.
   Shared helpers: IL.pcr (profile.js). Styles: profile.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const pcr = IL.pcr;
  const { isNum } = IL;
  const CAL_TASKS = new Set(['needle touch-off', 'dot ladder', 'scale shots']);
  const C = { data: null, ver: 0, loading: false, again: false, error: null, ladderOf: null, ladder: null, ladderErr: null,
    guide: null, toReq: null, applyTouched: false, prefill: {}, angleShown: undefined, lastResult: null, lastEndedId: null };

  // ================================================================================== data
  async function loadCalibrations() {
    if (C.loading) { C.again = true; return C.data; }
    C.loading = true;
    try { C.data = await IL.api('GET', '/api/calibrations'); C.error = null; C.ver++; }
    catch (e) { C.error = IL.errorText(e); }
    finally { C.loading = false; }
    if (C.again) { C.again = false; return loadCalibrations(); }
    IL.emit('calibrations', C.data);
    IL.render();
    return C.data;
  }
  pcr.calibrations = () => C.data;
  pcr.loadCalibrations = loadCalibrations;
  async function loadLadder(id) {
    C.ladderOf = id;
    C.ladder = null;
    C.ladderErr = null;
    IL.render();
    try { C.ladder = await IL.api('GET', '/api/calibrations/' + encodeURIComponent(id) + '/ladder'); }
    catch (e) { C.ladderErr = IL.errorText(e); }
    IL.render();
  }
  /** Touch-off row → {analysis, applied, delta, ...}. details is the engine's JSON (or free text for manual rows). */
  pcr.touchOffView = function (row) {
    if (!row) return null;
    let det = null;
    try { det = row.details && row.details.charAt(0) === '{' ? JSON.parse(row.details) : null; } catch (e) { det = null; }
    const a = det && det.analysis ? det.analysis : null;
    const ladder = /dot-ladder-touch-off/.test(row.method || '');
    return { row, det, analysis: a, ladder, applied: !/not applied/.test(row.method || ''),
      delta: isNum(row.previous_offset_mm) ? row.z_offset_mm - row.previous_offset_mm : null,
      method: ladder ? (/not applied/.test(row.method) ? 'Touch-off (shown only)' : 'Touch-off (used)') : /needle-frame/.test(row.method || '') ? 'Needle frame recorded' : /manual/.test(row.method || '') ? 'Offset entered or confirmed' : row.method,
      note: det ? null : row.details };
  };
  const taskOf = (s) => (s && s.run && CAL_TASKS.has(s.run.label) ? s.run : null);
  /** The engine ends a touch-off OK even when its analysis found no contact (then nothing is recorded): say so. */
  function noContact(run) {
    if (!run || run.label !== 'needle touch-off' || run.running || run.status !== 'OK' || !C.data) return false;
    return !(C.data.touchOffs || []).some((r) => /dot-ladder-touch-off/.test(r.method || '') && r.created_at >= run.startedAtMs);
  }

  // ================================================================================== small builders
  function card(id, title, sub, body, foot, cls) {
    return el('section', { class: 'card cal-card' + (cls ? ' ' + cls : ''), id, 'data-refusals': '' },
      el('div', { class: 'card__head' }, el('div', {}, el('h2', { class: 'card__title' }, title), sub ? el('div', { class: 'card__sub' }, sub) : null),
        el('span', { class: 'card__state', id: id + '-state' })),
      el('div', { class: 'card__body cal-body' }, body),
      foot ? el('div', { class: 'card__foot' }, foot) : null);
  }
  function setState(id, kind, text) {
    const st = $(id + '-state');
    if (!st || st.dataset.sig === kind + text) return;
    st.dataset.sig = kind + text;
    st.className = 'card__state' + (kind ? ' card__state--' + kind : '');
    st.replaceChildren(...(text ? [IL.glyph(kind || 'off', 'sm'), text] : []));
  }
  const runBtn = (id, label, icon) => el('button', { type: 'button', class: 'btn btn--hold', id }, icon ? IL.icon(icon) : null, label);
  const reasonEl = (id) => el('div', { class: 'reason', id, hidden: true });
  const liveEl = (id) => el('div', { class: 'cal-live', id, hidden: true, role: 'status' });
  const sub = (t) => el('div', { class: 'section-k' }, el('span', { text: t }), el('span', { class: 'rule' }));
  const placeSel = (id) => el('select', { class: 'input', id }, el('option', { value: '', text: 'No places yet' }));
  const abbr = (t, title) => el('abbr', { title, text: t });

  // ================================================================================== prerequisites
  function prereqs(s, need, place) {
    const d = s.dispense || {}, m = s.machine || {}, pr = s.pressure || {}, n = d.needle || {};
    const rows = [];
    if (need.machine) {
      rows.push(!m.enabled ? { kind: 'block', title: 'Machine connected and homed', value: 'not connected', fix: { fix: 'connect', label: 'Connect' } }
        : !m.homed ? { kind: 'need', title: 'Machine connected and homed', value: 'not homed', fix: { fix: 'home', label: 'Home' } }
          : { kind: 'ok', title: 'Machine connected and homed', value: S.homedAt ? 'homed ' + IL.fmtTime(S.homedAt) : 'homed' });
    }
    if (need.engine && (!d.running || !d.available)) rows.push({ kind: 'block', title: 'Dispenser available', value: 'unavailable', title2: d.unavailableReason || '' });
    if (need.frame) rows.push(n.anchored ? { kind: 'ok', title: 'Needle Z frame recorded', value: IL.fmtSigned(n.anchorOffsetMm, 3) + ' mm' }
      : { kind: 'block', title: 'Needle Z frame recorded', value: 'not set', fix: { href: '#setup/needle', label: 'Needle Z frame' } });
    if (need.touched) rows.push(n.touchedOff ? { kind: 'ok', title: 'Needle touched off', value: n.setAtMs ? IL.fmtTime(n.setAtMs - S.skewMs) : 'yes' }
      : { kind: 'need', title: 'Needle touched off', value: 'not yet', fix: { href: '#calibrate/touch-off', label: 'Touch-off' } });
    place = place || d.blankLocationName;
    if (need.place) {
      const loc = place && d.locations && d.locations[place];
      rows.push(loc ? { kind: 'ok', title: 'Place “' + place + '”', value: 'X ' + IL.fmt(loc.x, 2) + ' · Y ' + IL.fmt(loc.y, 2) + ' · Z ' + IL.fmt(loc.z, 2) }
        : { kind: 'block', title: place ? 'Place “' + place + '”' : 'A place to dispense on', value: 'not set', fix: { href: '#setup/places', label: 'Places' } });
    }
    if (need.surface) {
      const sf = place && d.surfaces && d.surfaces[place];
      const fit = sf && sf.plane;
      rows.push(fit ? { kind: sf.plane.maxResidualMm > 0.04 ? 'need' : 'ok', title: 'Surface “' + place + '” probed', value: 'worst ' + IL.fmt(sf.plane.maxResidualMm, 3) + ' mm' }
        : { kind: place === d.blankLocationName ? 'block' : 'need', title: 'Surface “' + (place || 'blank') + '” probed', value: 'not probed', fix: { href: '#setup/surfaces', label: 'Surfaces' } });
    }
    if (need.pressure) {
      const held = pr.state && pr.state !== 'off';
      rows.push(held ? { kind: 'block', title: 'Pressure hold off', value: pr.state === 'vent_failed' ? 'NOT off' : 'on', fix: { fix: 'pressure-off', label: 'Pressure off' } }
        : { kind: 'off', title: 'Pressure hold off', value: 'off · vented' });
    }
    return rows;
  }
  function renderPrereqs(host, rows) {
    IL.keyed(host, JSON.stringify(rows.map((r) => [r.kind, r.title, r.value, r.fix && r.fix.label])), () => pcr.prereqs(rows));
    return rows.filter((r) => r.kind === 'block');
  }
  /** Lock reason of a moving calibration action: the shared lock text, then the first blocking prerequisite. */
  function runReason(s, blockers, own) {
    const lock = IL.lockReason(s, { connected: true, homed: true, engine: true });
    if (lock) return lock;
    if (blockers.length) return 'Finish first: ' + blockers.map((b) => b.title.charAt(0).toLowerCase() + b.title.slice(1)).join(', ') + '.';
    return own || null;
  }
  /** "Running · dot 3 of 6 · 0:41" / "Finished 13:05 · …" for the card of `label`. */
  function renderLive(host, s, label, extra, endOverride) {
    const run = s.run;
    if (!run || run.label !== label) { IL.show(host, false); return; }
    const running = run.running;
    const over = !running && endOverride ? endOverride(run) : null;
    const kind = running ? 'live' : over ? over.kind : pcr.statusKind(run.status);
    const ended = run.finishedAtMs ? IL.fmtTime(run.finishedAtMs - S.skewMs) : '';
    const head = running ? 'Running · ' + (run.dotsPlanned ? 'dot ' + Math.min(run.dotsDone + 1, run.dotsPlanned) + ' of ' + run.dotsPlanned : (run.phase || 'starting'))
      + ' · ' + IL.fmtClock(run.elapsedS) : over ? over.head.replace('{t}', ended) : pcr.statusWord(run.status) + ' ' + ended + (run.dotsPlanned ? ' · ' + run.dotsDone + ' of ' + run.dotsPlanned + ' dots' : '');
    const detail = running ? (extra ? extra(run) : run.phase || '') : (run.message || '');
    const pct = run.dotsPlanned ? Math.round(100 * run.dotsDone / run.dotsPlanned) : 0;
    const sig = JSON.stringify([kind, head, detail, pct]);
    if (host.dataset.sig !== sig) {
      host.dataset.sig = sig;
      host.className = 'cal-live cal-live--' + kind;
      host.replaceChildren(IL.glyph(kind, 'sm'), el('div', { class: 'cal-live__t' }, el('div', { class: 'cal-live__h', text: head }),
        detail ? el('div', { class: 'cal-live__d', text: detail }) : null,
        running && run.dotsPlanned ? el('div', { class: 'bar' }, el('i', { style: 'width:' + pct + '%' })) : null));
    }
    IL.show(host, true);
  }

  // ================================================================================== cards
  function touchOffCard() {
    const run = runBtn('btn-to-run', 'Run touch-off', 'i-needle');
    IL.press2(run, 'run touch-off', startTouchOff, { ok: (r) => 'Touch-off started' + (r && r.apply ? ': the result will be used for the needle offset.' : ': the result is shown only.') });
    const apply = el('input', { type: 'checkbox', id: 'to-apply' });
    apply.addEventListener('change', () => { C.applyTouched = true; });
    return card('cal-touch-off', [abbr('Needle touch-off', 'Finding where the needle tip touches: dots at falling gaps; where the tip reaches the surface, the dots squash or stop forming')],
      [abbr('I4', 'Interlock I4: needle Z frame and needle offset'), ' · dots at falling gaps on the blank: where the tip touches, the dots change'],
      [el('div', { class: 'cal-split' },
        el('div', { class: 'cal-form' },
          el('div', { class: 'fgrid fgrid--3' },
            pcr.field('to-start', 'Start gap', 'mm', pcr.numInput('to-start', '0.125', '0.005'), 'The first, largest gap.'),
            pcr.field('to-step', 'Step', 'mm', pcr.numInput('to-step', '0.025', '0.005', { min: '0' })),
            pcr.field('to-steps', 'Steps', '2 to 20', pcr.numInput('to-steps', '6', '1', { min: '2', max: '20' }))),
          el('div', { class: 'fgrid' },
            pcr.field('to-rot', 'Screw turn per dot', '°', pcr.numInput('to-rot', null, '0.01', { placeholder: 'one profile dot' }),
              'Empty: one profile dot (other partial turns vary with the screw angle and hide the squash).'),
            pcr.field('to-place', 'Place', null, placeSel('to-place'))),
          el('div', { class: 'cal-gaps', id: 'to-plan' }),
          el('label', { class: 'check' }, apply, 'Use the result for the needle offset')),
        el('div', { class: 'cal-side' }, sub('Before it runs'), el('div', { id: 'to-pre' }), liveEl('to-live'), el('div', { id: 'to-result' }))),
      el('details', { class: 'disclose cal-hist', id: 'to-history' }, el('summary', { id: 'to-history-s', text: 'Last 20 touch-offs' }),
        el('div', { class: 'cal-tblwrap' }, el('table', { class: 'tbl cal-tbl', id: 'to-history-t' },
          el('thead', {}, el('tr', {}, [['When'], ['What'], ['Offset', 'mm'], ['Δ', 'mm'], ['Contact gap', 'mm']].map(([h, u]) => el('th', { text: h, title: u || null, class: u ? 'r' : null, scope: 'col' })))),
          el('tbody'))))],
      [run, reasonEl('to-reason'), el('span', { class: 'spacer' }), el('button', { type: 'button', class: 'btn btn--quiet', onclick: () => IL.go('#setup/needle') }, 'Needle Z frame', IL.icon('i-chev'))],
      'span2');
  }
  function angleCard() {
    const set = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pf-angle-set' }, 'Set angle');
    // ask for a new plan only when the angle changed and a plan is shown (its dots start from the old angle)
    set.addEventListener('click', () => IL.act(set, setAngle, { ok: (p) => 'Start angle set to ' + IL.fmtDeg(p.startAngleDeg, 1)
      + (p.changed && S.plan ? ': plan the board again (Run).' : p.changed ? '.' : ' (unchanged), confirmed for this controller power-up.') }));
    const input = pcr.numInput('pf-angle', null, '0.1', { min: '0', max: '359.9' });
    input.addEventListener('input', checkAngle);
    return card('cal-angle', 'Screw start angle', [abbr('I11', 'Interlock I11: partial turns depend on where the screw starts'), ' · measured with dot pairs'],
      [el('dl', { class: 'kv kv--wrap', id: 'cal-angle-kv' }),
        el('div', { id: 'cal-angle-ask' }),
        sub('Measure it'),
        el('ol', { class: 'cal-steps' },
          el('li', {}, 'Wipe the blank. Set a candidate angle below.'),
          el('li', {}, 'On Run, plan the board and run a test with the first few dots: the purge turns the screw to this angle, then the dots alternate between the two start angles.'),
          el('li', {}, 'Compare the two halves (alternate dots) by area or by weight. Repeat for a few angles.'),
          el('li', {}, 'Set the angle whose halves match best. After switching the controller off, measure again.')),
        el('div', { class: 'row', 'data-enter-submits': 'btn-pf-angle-set' }, pcr.field('pf-angle', 'Start angle', '° · 0 to 359.9', input), set),
        reasonEl('cal-angle-reason')],
      null);
  }
  function ladderCard() {
    const run = runBtn('btn-dl-run', 'Run dot ladder', 'i-play');
    IL.press2(run, 'run dot ladder', startLadder, { ok: (r) => 'Dot ladder started: ' + IL.plural((r && r.dots) || 0, 'dot') + '. Results appear in the records below.' });
    const rots = pcr.textInput('dl-rots', '30, 45, 58, 75, 90');
    return card('cal-ladder', [abbr('Dot ladder', 'A grid of dots made with different screw turns, measured by the camera before and after')],
      'camera · a history, not a dose',
      [el('div', { class: 'fgrid' },
        pcr.field('dl-rots', 'Screw turns', '° · comma separated', rots, null, 'span2'),
        pcr.field('dl-rep', 'Repeats', '1 to 50', pcr.numInput('dl-rep', '5', '1', { min: '1', max: '50' })),
        pcr.field('dl-pitch', 'Pitch', 'mm · 0 to 10', pcr.numInput('dl-pitch', '1.5', '0.1', { min: '0', max: '10' })),
        pcr.field('dl-place', 'Place', null, placeSel('dl-place'), null, 'span2')),
      el('label', { class: 'check' }, el('input', { type: 'checkbox', id: 'dl-prime', checked: true }), 'Prime on the waste pad first'),
      el('div', { class: 'cal-gaps', id: 'dl-plan' }),
      sub('Before it runs'), el('div', { id: 'dl-pre' }), liveEl('dl-live')],
      [run, reasonEl('dl-reason')]);
  }
  function shotsCard() {
    const run = runBtn('btn-shots-run', 'Dispense shots', 'i-play');
    IL.press2(run, 'dispense shots', startShots, { ok: (r) => 'Dispensing ' + r.count + ' shots of ' + IL.fmt(r.rotationDeg, 1) + '° at ' + r.locationName + '. Weigh the cup when it is done.' });
    const save = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-ss-save' }, 'Save scale reading');
    save.addEventListener('click', () => IL.act(save, saveShots, { ok: (r) => r }));
    return card('cal-shots', [abbr('Scale', 'Paste pushed out per full screw turn (q, mm³ per turn), measured by weighing'), ': shots'],
      'dispense big shots into a cup on the scale, then type what the scale reads',
      [el('div', { class: 'cal-split cal-split--shots' },
        el('div', { class: 'cal-form' },
          el('div', { class: 'cal-step' }, el('span', { class: 'st st--num st--sm', text: '1' }), 'Dispense the shots'),
          el('div', { class: 'fgrid fgrid--3' },
            pcr.field('shots-place', 'Cup place', null, placeSel('shots-place')),
            pcr.field('shots-rot', 'Turn per shot', '°', pcr.numInput('shots-rot', '360', '1', { min: '1', max: '3600' })),
            pcr.field('shots-count', 'Shots', '1 to 200', pcr.numInput('shots-count', '30', '1', { min: '1', max: '200' }))),
          el('div', { id: 'shots-noplace', class: 'reason reason--need', hidden: true }, el('span', {}, 'No places yet: add the cup in ', el('button', { type: 'button', class: 'link', text: 'Setup › Places', onclick: () => IL.go('#setup/places') }), '.')),
          el('div', { id: 'shots-pre' }), liveEl('shots-live'),
          el('div', { class: 'btn-row' }, run), reasonEl('shots-reason')),
        el('div', { class: 'cal-form', 'data-enter-submits': 'btn-ss-save' },
          el('div', { class: 'cal-step' }, el('span', { class: 'st st--num st--sm', text: '2' }), 'Enter what the scale reads'),
          el('div', { class: 'fgrid fgrid--3' },
            pcr.field('ss-rot', 'Turn per shot', '°', pcr.numInput('ss-rot', '360', '1')),
            pcr.field('ss-count', 'Shots', 'count', pcr.numInput('ss-count', '30', '1', { min: '1' })),
            pcr.field('ss-total', 'Total mass', 'mg', pcr.numInput('ss-total', null, '0.1', { min: '0' })),
            pcr.field('ss-each', 'Mass of each shot', 'mg · optional, gives the spread', pcr.textInput('ss-each', null, { placeholder: 'e.g. 1.81, 1.79, 1.84' }), null, 'span3'),
            pcr.field('ss-tip', 'Tip', null, pcr.textInput('ss-tip', '22G 0.41 mm')),
            pcr.field('ss-lot', 'Paste lot', null, pcr.textInput('ss-lot')),
            pcr.field('ss-psi', 'Set pressure', 'psi', pcr.numInput('ss-psi', null, '0.1', { min: '0' })),
            pcr.field('ss-temp', 'Barrel temperature', '°C', pcr.numInput('ss-temp', null, '0.1')),
            pcr.field('ss-cart', 'Cartridge', null, pcr.textInput('ss-cart')),
            pcr.field('ss-op', 'Operator', null, pcr.textInput('ss-op'))),
          el('div', { class: 'cal-gaps', id: 'ss-calc' }),
          el('label', { class: 'check' }, el('input', { type: 'checkbox', id: 'ss-char' }), 'The spread meets my pass criteria: mark it “characterized”'),
          el('div', { class: 'btn-row' }, save), reasonEl('ss-reason')))],
      null, 'span2');
  }
  function dotsCard() {
    const save = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-sd-save' }, 'Save dot batch');
    save.addEventListener('click', () => IL.act(save, saveDots, { ok: (r) => r }));
    return card('cal-dots', 'Scale: dot batch', 'weigh a batch of equal dots on a slide (gross minus tare): mg per dot for one turn',
      [el('div', { class: 'fgrid fgrid--3', 'data-enter-submits': 'btn-sd-save' },
        pcr.field('sd-cal', 'Calibration', '#', pcr.numInput('sd-cal', null, '1', { min: '1' })),
        pcr.field('sd-rot', 'Turn per dot', '°', pcr.numInput('sd-rot', null, '0.01')),
        pcr.field('sd-count', 'Dots', 'count', pcr.numInput('sd-count', null, '1', { min: '1' })),
        pcr.field('sd-mass', 'Net mass', 'mg', pcr.numInput('sd-mass', null, '0.01', { min: '0' })),
        pcr.field('sd-sd', 'Scale SD', 'mg · optional', pcr.numInput('sd-sd', null, '0.01', { min: '0' }), 'Repeatability of the scale, for the error bar.'),
        pcr.field('sd-dia', 'Mean dot diameter', 'mm · optional', pcr.numInput('sd-dia', null, '0.01', { min: '0' }), 'From the camera, if you have it.')),
      el('div', { class: 'cal-gaps', id: 'sd-calc' })],
      [save, reasonEl('sd-reason')], 'span2');
  }
  function recordsCard() {
    return card('cal-records', 'Records', 'history of the scale and ladder calibrations: boards use the dispense profile',
      [el('p', { class: 'cal-current', id: 'cal-current' }),
        el('p', { class: 'cal-result', id: 'cal-result', role: 'status' }),
        sub('Calibrations'),
        el('div', { class: 'cal-tblwrap' }, el('table', { class: 'tbl cal-tbl', id: 'cal-table' },
          el('thead', {}, el('tr', {}, [['#'], ['Method'], ['Status'], ['q', 'mm³ per screw turn'], ['CV', 'Spread: standard deviation ÷ mean'], ['n'], ['Saved'], ['Notes']]
            .map(([h, t]) => el('th', { text: h, title: t || null, scope: 'col' })))),
          el('tbody'))),
        el('div', { class: 'section-k' }, el('span', { id: 'ladder-k', text: 'Dot ladder of the current calibration' }), el('span', { class: 'rule' })),
        el('div', { class: 'cal-tblwrap' }, el('table', { class: 'tbl cal-tbl', id: 'ladder-table' },
          el('thead', {}, el('tr', {}, [['Turn', '°'], ['n'], ['Diameter', 'mm'], ['CV'], ['mg/dot'], ['Height', 'mm'], ['Method']]
            .map(([h, u]) => el('th', { text: h, title: u || null, scope: 'col' })))),
          el('tbody')))],
      [el('button', { type: 'button', class: 'btn btn--quiet', onclick: () => IL.go('#records/calibrations') }, 'All records', IL.icon('i-chev')),
        el('button', { type: 'button', class: 'btn btn--quiet', onclick: () => IL.go('#records/touch-offs') }, 'Touch-offs', IL.icon('i-chev'))], 'span2');
  }

  // ================================================================================== actions
  function touchOffPlan() {
    const a = pcr.check('to-start', { required: true, max: 2, unit: 'mm' });
    const b = pcr.check('to-step', { required: true, min: 0, minExcl: true, max: 0.5, unit: 'mm' });
    const n = pcr.check('to-steps', { required: true, int: true, min: 2, max: 20 });
    const rot = pcr.check('to-rot', { min: 0, minExcl: true, max: 720, unit: '°' });
    const errs = { 'to-start': a.err, 'to-step': b.err, 'to-steps': n.err, 'to-rot': rot.err };
    let gaps = null, lowest = null;
    if (!a.err && !b.err && !n.err) {
      gaps = [];
      for (let i = 0; i < n.v; i++) gaps.push(a.v - i * b.v);
      lowest = gaps[gaps.length - 1];
      if (lowest < -0.1 - 1e-9) errs['to-steps'] = 'The lowest gap would be ' + IL.fmt(lowest, 3) + ' mm: keep it at −0.100 mm or above.';
    }
    return { errs, gaps, lowest, body: { startGapMm: a.v, stepMm: b.v, steps: n.v, rotationDeg: rot.v, locationName: $('to-place').value || undefined, apply: $('to-apply').checked } };
  }
  async function startTouchOff() {
    const p = touchOffPlan();
    const e = Object.values(p.errs).find(Boolean);
    if (e) throw new Error(e);
    C.toReq = { gaps: p.gaps, at: Date.now() };
    return IL.api('POST', '/api/calibrations/touch-off', p.body);
  }
  function ladderPlan() {
    const txt = String($('dl-rots').value || '');
    const parts = txt.split(/[,;\s]+/).filter(Boolean);
    const rots = parts.map(Number);
    const errs = {};
    if (!parts.length) errs['dl-rots'] = 'Enter at least one turn in degrees.';
    else if (rots.some((r) => !Number.isFinite(r) || r <= 0 || r > 720)) errs['dl-rots'] = 'Each turn above 0 and at most 720°.';
    const rep = pcr.check('dl-rep', { required: true, int: true, min: 1, max: 50 });
    const pitch = pcr.check('dl-pitch', { required: true, min: 0, minExcl: true, max: 10, unit: 'mm' });
    errs['dl-rep'] = rep.err;
    errs['dl-pitch'] = pitch.err;
    return { errs, rots, body: { rotationsDeg: rots, repeats: rep.v, pitchMm: pitch.v, locationName: $('dl-place').value || undefined, primeAtWaste: $('dl-prime').checked } };
  }
  async function startLadder() {
    const p = ladderPlan();
    const e = Object.values(p.errs).find(Boolean);
    if (e) throw new Error(e);
    return IL.api('POST', '/api/calibrations/dot-ladder', p.body);
  }
  function shotsPlan() {
    const rot = pcr.check('shots-rot', { required: true, min: 0, minExcl: true, max: 3600, unit: '°' });
    const n = pcr.check('shots-count', { required: true, int: true, min: 1, max: 200 });
    return { errs: { 'shots-rot': rot.err, 'shots-count': n.err }, body: { locationName: $('shots-place').value, rotationDeg: rot.v, count: n.v } };
  }
  async function startShots() {
    const p = shotsPlan();
    const e = Object.values(p.errs).find(Boolean);
    if (e) throw new Error(e);
    if (!p.body.locationName) throw new Error('Choose the cup place (Setup › Places).');
    const r = await IL.api('POST', '/api/calibrations/shots-run', p.body);
    $('ss-rot').value = String(r.rotationDeg);
    $('ss-count').value = String(r.count);
    return r;
  }
  function eachMasses() {
    const t = String($('ss-each').value || '').trim();
    if (!t) return { list: null };
    const list = t.split(/[,;\s]+/).filter(Boolean).map(Number);
    return list.some((x) => !Number.isFinite(x) || x <= 0) ? { list: null, err: 'Masses in mg, separated by commas.' } : { list };
  }
  function shotsRecordPlan() {
    const rot = pcr.check('ss-rot', { required: true, min: 0, minExcl: true, unit: '°' });
    const n = pcr.check('ss-count', { int: true, min: 1 });
    const tot = pcr.check('ss-total', { min: 0, minExcl: true, unit: 'mg' });
    const each = eachMasses();
    const errs = { 'ss-rot': rot.err, 'ss-count': n.err, 'ss-total': tot.err, 'ss-each': each.err || null };
    if (!errs['ss-total'] && !each.list && (tot.v === undefined || n.v === undefined)) errs['ss-total'] = 'Enter the total mass and the number of shots, or each shot’s mass.';
    return { errs, rot: rot.v, count: n.v, total: tot.v, each: each.list };
  }
  async function saveShots() {
    const p = shotsRecordPlan();
    const e = Object.values(p.errs).find(Boolean);
    if (e) throw new Error(e);
    const r = await IL.api('POST', '/api/calibrations/scale-shots', {
      rotationDegPerShot: p.rot, count: p.count, totalMassMg: p.total, perShotMassMg: p.each || undefined,
      tip: IL.str('ss-tip'), pasteLot: IL.str('ss-lot'), setPressurePsi: IL.num('ss-psi'), barrelTempC: IL.num('ss-temp'),
      cartridge: IL.str('ss-cart'), operator: IL.str('ss-op'), markCharacterized: $('ss-char').checked,
    });
    const k = r.calibration;
    const text = 'Saved calibration #' + k.id + ': q = ' + IL.fmt(k.qMm3PerRev, 4) + ' mm³ per turn' + (k.qCv !== null ? ', CV ' + IL.fmt(100 * k.qCv, 1) + ' %' : '') + ' (' + k.status + '). A record: boards use the dispense profile.';
    C.lastResult = { text, kind: 'ok', at: Date.now() };
    await loadCalibrations();
    return text;
  }
  function dotsPlan() {
    const cal = pcr.check('sd-cal', { int: true, min: 1 });
    const rot = pcr.check('sd-rot', { required: true, min: 0, minExcl: true, unit: '°' });
    const n = pcr.check('sd-count', { required: true, int: true, min: 1 });
    const m = pcr.check('sd-mass', { required: true, min: 0, minExcl: true, unit: 'mg' });
    const sd = pcr.check('sd-sd', { min: 0 });
    const dia = pcr.check('sd-dia', { min: 0, minExcl: true });
    return { errs: { 'sd-cal': cal.err, 'sd-rot': rot.err, 'sd-count': n.err, 'sd-mass': m.err, 'sd-sd': sd.err, 'sd-dia': dia.err },
      body: { calibrationId: cal.v, rotationDeg: rot.v, count: n.v, totalMassMg: m.v, scaleSdMg: sd.v, diameterMeanMm: dia.v } };
  }
  async function saveDots() {
    const p = dotsPlan();
    const e = Object.values(p.errs).find(Boolean);
    if (e) throw new Error(e);
    const r = await IL.api('POST', '/api/calibrations/scale-dots', p.body);
    const text = 'Saved: ' + IL.fmt(r.row.mgPerDotMean, 4) + ' mg per dot at ' + IL.fmt(r.row.rotationDeg, 2) + '°' + (isNum(r.row.mgPerDotSe) ? ' ± ' + IL.fmt(r.row.mgPerDotSe, 4) : '') + ' (calibration #' + (p.body.calibrationId || (C.data && C.data.current ? C.data.current.id : '—')) + ').';
    C.lastResult = { text, kind: 'ok', at: Date.now() };
    await loadCalibrations();
    return text;
  }
  function checkAngle() {
    const c = pcr.check('pf-angle', { required: true, min: 0, max: 360, maxExcl: true, msg: '0 to 359.9°.' });
    pcr.fieldErr('pf-angle', IL.num('pf-angle') === undefined ? null : c.err);
    return c;
  }
  /** "Offset confirmed" when the needle offset is the frame offset (confirmed, not measured), else "Touched off". */
  function offsetWord(n) {
    return isNum(n.zOffsetMm) && isNum(n.anchorOffsetMm) && Math.abs(n.zOffsetMm - n.anchorOffsetMm) < 0.0005 ? 'Offset confirmed' : 'Touched off';
  }
  async function setAngle() {
    const c = checkAngle();
    if (c.err) throw new Error('Start angle: ' + c.err);
    const before = S.status && S.status.dispense && S.status.dispense.profile ? S.status.dispense.profile.startAngleDeg : null;
    const p = await IL.api('POST', '/api/screw/start-angle', { startAngleDeg: c.v, note: 'set in Calibrate (web UI)' });
    p.changed = !(isNum(before) && Math.abs(Number(before) - Number(p.startAngleDeg)) < 0.05);
    // the field is the operator's: only mark it synced if it still shows what was sent (never overwrite a newer edit)
    if (Number($('pf-angle').value) === Number(c.v)) C.angleShown = p.startAngleDeg;
    if (S.config) IL.loadConfig();
    IL.planRefreshSoon();
    return p;
  }
  async function useTouchOff(v) {
    const r = await IL.api('POST', '/api/needle/offset', { offsetMm: Number(v.row.z_offset_mm.toFixed(4)), note: 'touch-off #' + v.row.id + ' (web UI)' });
    IL.planRefreshSoon();
    await loadCalibrations();
    return r;
  }

  // ================================================================================== render
  function showErrs(errs) { for (const [id, msg] of Object.entries(errs)) pcr.fieldErr(id, msg); }
  /** Prefills a field from a default unless the operator typed something else (or it has focus). */
  function prefill(id, value) {
    const e = $(id);
    if (!e || document.activeElement === e || value === undefined || value === null) return;
    const v = String(value);
    if (e.value === '' || e.value === C.prefill[id]) { if (e.value !== v) e.value = v; C.prefill[id] = v; }
  }
  function renderTouchOff(s) {
    const d = s.dispense || {}, n = d.needle || {};
    const place = $('to-place').value;
    pcr.fillPlaces($('to-place'), s, d.blankLocationName);
    if (!C.applyTouched) $('to-apply').checked = !n.touchedOff;
    setState('cal-touch-off', !n.anchored ? 'block' : n.touchedOff ? 'ok' : 'need',
      !n.anchored ? 'No needle frame' : n.touchedOff ? offsetWord(n) + ' ' + (n.setAtMs ? IL.fmtTime(n.setAtMs - S.skewMs) : '') : 'Not touched off');
    const p = touchOffPlan();
    showErrs(p.errs);
    IL.setText($('to-plan'), p.gaps ? IL.plural(p.gaps.length, 'dot') + (place ? ' on “' + place + '”' : '') + ' at gaps ' + p.gaps.map((g) => IL.fmt(g, 3)).join(', ') + ' mm'
      + ' · one profile dot each' + (IL.num('to-rot') ? ' (here ' + IL.fmt(IL.num('to-rot'), 2) + '°)' : '') : 'Enter the gaps to see the dots.');
    const blockers = renderPrereqs($('to-pre'), prereqs(s, { machine: true, engine: true, frame: true, place: true, surface: true, pressure: true }, place));
    const own = Object.values(p.errs).find(Boolean) || (!place ? 'Choose the place.' : null);
    IL.gate($('btn-to-run'), runReason(s, blockers, own), $('to-reason'));
    renderLive($('to-live'), s, 'needle touch-off', (run) => {
      const g = C.toReq && C.toReq.gaps;
      const i = Math.min(run.dotsDone, (g || []).length - 1);
      return g && g.length ? 'gap ' + IL.fmt(g[i], 3) + ' mm · ' + (run.phase || '') : run.phase || '';
    }, (run) => (noContact(run) ? { kind: 'need', head: 'Finished {t} · no contact found: nothing recorded' } : null));
    renderTouchOffResult(s);
  }
  function renderTouchOffResult(s) {
    const host = $('to-result');
    const rows = (C.data && C.data.touchOffs) || [];
    const latest = rows.find((r) => /dot-ladder-touch-off/.test(r.method || '')) || null;
    const v = pcr.touchOffView(latest);
    const needle = (s.dispense && s.dispense.needle) || {};
    const inUse = v && isNum(needle.zOffsetMm) && Math.abs(needle.zOffsetMm - v.row.z_offset_mm) < 5e-4;
    IL.keyed(host, JSON.stringify([v ? v.row.id : null, inUse, C.ver]), () => {
      if (!v) return [sub('Last touch-off'), el('p', { class: 'muted cal-none', text: C.data ? 'No touch-off with dots yet.' : C.error ? 'Could not load the records: ' + C.error : 'Loading the records…' })];
      const a = v.analysis || {};
      const gaps = a.gapsMm || [], dia = a.diametersMm || [];
      const inBracket = (g) => isNum(a.bracketHighMm) && isNum(a.bracketLowMm) && g <= a.bracketHighMm + 1e-9 && g >= a.bracketLowMm - 1e-9;
      const use = el('button', { type: 'button', class: 'btn btn--sm', id: 'to-use' }, 'Use for the needle offset');
      use.addEventListener('click', () => IL.act(use, () => useTouchOff(v), { ok: 'Needle offset set to ' + IL.fmtSigned(v.row.z_offset_mm, 3) + ' mm from the touch-off: plan the board again.' }));
      return [sub('Last touch-off · ' + IL.fmtDateTime(v.row.created_at - S.skewMs)),
        el('div', { class: 'cal-to' },
          el('div', { class: 'cal-to__s', id: 'to-sum' },
            el('div', { class: 'cal-to__big' }, isNum(a.contactGapMm) ? 'Contact at a gap of ' + IL.fmtSigned(a.contactGapMm, 3) + ' mm' : 'No contact found'),
            el('div', { text: 'offset ' + IL.fmtSigned(v.row.previous_offset_mm, 3) + ' → ' + IL.fmtSigned(v.row.z_offset_mm, 3) + ' mm' + (isNum(v.delta) ? ', Δ ' + IL.fmtSigned(v.delta, 3) : '') }),
            a.reason ? el('div', { class: 'dim', text: a.reason }) : null,
            v.applied || inUse ? el('div', { class: 'cal-to__ok' }, IL.glyph('ok', 'xs'), ' In use as the needle offset')
              : el('div', { class: 'btn-row', 'data-refusals': '' }, use)),
          el('table', { class: 'tbl cal-to__t' }, el('thead', {}, el('tr', {}, el('th', { class: 'r' }, IL.unitText('Gap mm')), el('th', { class: 'r' }, IL.unitText('Dot Ø mm')), el('th', { text: '' }))),
            el('tbody', {}, gaps.map((g, i) => el('tr', { class: inBracket(g) ? 'is-contact' : null },
              el('td', { class: 'r', text: IL.fmtSigned(g, 3) }), el('td', { class: 'r', text: isNum(dia[i]) ? IL.fmt(dia[i], 3) : 'no dot' }),
              el('td', { class: 'dim', text: isNum(a.contactGapMm) && Math.abs(g - a.contactGapMm) < 1e-9 ? 'contact' : inBracket(g) ? 'bracket' : '' })))))),
        el('button', { type: 'button', class: 'btn btn--sm btn--quiet', onclick: () => IL.go('#records/touch-offs') }, 'Last 20 touch-offs', IL.icon('i-chev'))];
    });
    IL.gate($('to-use'), IL.lockReason(s, { pressureOff: false }));
    IL.setText($('to-history-s'), 'Last ' + IL.plural(rows.length, 'needle offset change') + ' (touch-offs, frames, confirmed offsets)');
    IL.keyed($('to-history-t').tBodies[0], JSON.stringify(rows.map((r) => r.id)), () => (rows.length ? rows.map((r) => {
      const x = pcr.touchOffView(r);
      return el('tr', {}, el('td', { class: 'nowrap', text: IL.fmtDateTime(r.created_at - S.skewMs) }), el('td', { text: x.method, title: r.method }),
        el('td', { class: 'r', text: IL.fmtSigned(r.z_offset_mm, 3) }), el('td', { class: 'r', text: isNum(x.delta) ? IL.fmtSigned(x.delta, 3) : '—' }),
        el('td', { class: 'r', text: x.analysis && isNum(x.analysis.contactGapMm) ? IL.fmtSigned(x.analysis.contactGapMm, 3) : '—' }));
    }) : el('tr', {}, el('td', { colspan: '5', class: 'muted', text: 'No needle offset changes yet.' }))));
  }
  function renderAngle(s) {
    const p = (s.dispense && s.dispense.profile) || {};
    const state = !p.partialTurn ? 'off' : !p.startAngleSetAtMs ? 'never' : p.startAngleNeedsConfirm ? 'confirm' : 'ok';
    setState('cal-angle', { ok: 'ok', confirm: 'need', never: 'block', off: 'off' }[state], { ok: IL.fmtDeg(p.startAngleDeg, 1), confirm: 'Confirm', never: 'Not set', off: 'Whole turns' }[state]);
    IL.kv($('cal-angle-kv'), [
      ['Start angle', IL.fmtDeg(p.startAngleDeg, 1) + (p.partialTurn && p.startAngles ? ' · dots alternate ' + IL.startAnglesText(p) : '')],
      ['Set or confirmed', p.startAngleSetAtMs ? IL.fmtDateTime(p.startAngleSetAtMs - S.skewMs) : 'never'],
      ['Controller connected', p.controllerConnectedAtMs ? IL.fmtDateTime(p.controllerConnectedAtMs - S.skewMs) : '—'],
    ]);
    IL.keyed($('cal-angle-ask'), state + p.startAngleDeg, () => (state === 'confirm' ? el('div', { class: 'ri ri--need cal-ask' }, IL.glyph('need'),
      el('div', {}, el('div', { class: 'ri__t', text: 'Was the controller switched off since ' + IL.fmtTime(p.startAngleSetAtMs - S.skewMs) + '?' }),
        el('div', { class: 'ri__d', text: 'If it stayed on, the angle still holds. If it was off, measure again below.' })),
      el('abbr', { class: 'icode', title: 'Interlock I11', text: 'I11' }),
      el('div', { class: 'ri__act' }, IL.fixButton({ fix: 'I11-confirm', label: 'No, it stayed on: ' + IL.fmtDeg(p.startAngleDeg, 1) + ' still holds' }))) : null));
    // refill #pf-angle from the server unless focused or edited
    const e = $('pf-angle');
    if (document.activeElement !== e && (e.value === '' || Number(e.value) === Number(C.angleShown)) && isNum(p.startAngleDeg) && Number(e.value) !== Number(p.startAngleDeg)) {
      e.value = IL.inputNum(p.startAngleDeg);
    }
    if (document.activeElement !== e && (e.value === '' || Number(e.value) === Number(C.angleShown))) C.angleShown = p.startAngleDeg;
    e.classList.toggle('input--dirty', e.value !== '' && Number(e.value) !== Number(p.startAngleDeg));
    const c = checkAngle();
    IL.gate($('btn-pf-angle-set'), IL.lockReason(s, { pressureOff: false }) || (IL.num('pf-angle') === undefined ? 'Enter the angle in degrees.' : c.err ? 'Start angle: ' + c.err : null), $('cal-angle-reason'));
  }
  function renderLadder(s) {
    const d = s.dispense || {};
    const place = $('dl-place').value;
    pcr.fillPlaces($('dl-place'), s, d.blankLocationName);
    const p = ladderPlan();
    showErrs(p.errs);
    const ok = !Object.values(p.errs).some(Boolean);
    IL.setText($('dl-plan'), ok ? IL.plural(p.rots.length * p.body.repeats, 'dot') + ': ' + p.rots.length + ' turns × ' + p.body.repeats + ' repeats' + (place ? ' on “' + place + '”' : '') + ' at '
      + IL.fmt(p.body.pitchMm, 1) + ' mm, imaged before and after' + ($('dl-prime').checked ? '; a prime on the waste first' : '') + '.' : 'Fix the marked fields to see the dots.');
    const blockers = renderPrereqs($('dl-pre'), prereqs(s, { machine: true, engine: true, frame: true, touched: true, place: true, surface: true, pressure: true }, place));
    const n = d.needle || {};
    const own = Object.values(p.errs).find(Boolean) || (!place ? 'Choose the place.' : !n.touchedOff ? 'Touch off the needle first (the ladder needs the touched-off offset).' : null);
    IL.gate($('btn-dl-run'), runReason(s, blockers, own), $('dl-reason'));
    const last = C.data && C.data.ladder && C.data.ladder.length;
    setState('cal-ladder', last ? 'ok' : 'off', last ? IL.plural(C.data.ladder.length, 'row') : 'No rows');
    renderLive($('dl-live'), s, 'dot ladder');
  }
  function renderShots(s) {
    const d = s.dispense || {}, prof = d.profile || {};
    const names = pcr.fillPlaces($('shots-place'), s, pcr.places(s).find((nm) => /cup|scale/i.test(nm)));
    IL.show($('shots-noplace'), !names.length);
    const place = $('shots-place').value;
    const p = shotsPlan();
    showErrs(p.errs);
    const blockers = renderPrereqs($('shots-pre'), prereqs(s, { machine: true, engine: true, place: true, pressure: true }, place));
    IL.gate($('btn-shots-run'), runReason(s, blockers, Object.values(p.errs).find(Boolean) || (!place ? 'Add a cup place in Setup › Places first.' : null)), $('shots-reason'));
    renderLive($('shots-live'), s, 'scale shots');
    prefill('ss-psi', isNum(prof.pressurePsi) ? IL.fmtPsi(prof.pressurePsi) : null);
    const r = shotsRecordPlan();
    const typed = ['ss-total', 'ss-each'].some((id) => $(id).value.trim() !== '');
    showErrs(typed ? r.errs : { 'ss-rot': r.errs['ss-rot'], 'ss-count': r.errs['ss-count'] });
    const mass = r.each ? r.each.reduce((x, y) => x + y, 0) : r.total;
    const shots = r.each ? r.each.length : r.count;
    IL.setText($('ss-calc'), mass > 0 && shots > 0 && r.rot > 0 ? IL.fmt(mass / shots, 3) + ' mg per shot of ' + IL.fmt(r.rot, 1) + '° (' + IL.fmt(r.rot / 360, 2) + ' turn)'
      + (r.each && r.each.length > 1 ? ' · spread ' + IL.fmt(100 * cv(r.each), 1) + ' %' : '') + ' · the engine turns it into q with the paste density.' : 'Enter the masses to see mg per shot.');
    IL.gate($('btn-ss-save'), IL.lockReason(s, { idle: false, pressureOff: false }) || (typed ? Object.values(r.errs).find(Boolean) : 'Enter the total mass or each shot’s mass.'), $('ss-reason'));
    const cur = C.data && C.data.current;
    setState('cal-shots', cur ? (cur.status === 'characterized' ? 'ok' : 'off') : '', cur ? 'Current #' + cur.id + ' · ' + cur.status : '');
  }
  function cv(list) {
    const m = list.reduce((a, b) => a + b, 0) / list.length;
    const v = list.reduce((a, b) => a + (b - m) * (b - m), 0) / (list.length - 1);
    return m ? Math.sqrt(v) / m : 0;
  }
  function renderDots(s) {
    const prof = (s.dispense && s.dispense.profile) || {};
    const cur = C.data && C.data.current;
    prefill('sd-cal', cur ? cur.id : null);
    prefill('sd-rot', isNum(prof.netDeg) ? Number(prof.netDeg).toFixed(2) : null);
    const p = dotsPlan();
    const typed = ['sd-count', 'sd-mass'].some((id) => $(id).value.trim() !== '');
    showErrs(typed ? p.errs : { 'sd-cal': p.errs['sd-cal'], 'sd-rot': p.errs['sd-rot'], 'sd-sd': p.errs['sd-sd'], 'sd-dia': p.errs['sd-dia'] });
    const b = p.body;
    IL.setText($('sd-calc'), b.totalMassMg > 0 && b.count > 0 ? IL.fmt(b.totalMassMg / b.count, 4) + ' mg per dot' + (b.scaleSdMg > 0 ? ' ± ' + IL.fmt(b.scaleSdMg / b.count, 4) : '')
      + ' at ' + IL.fmt(b.rotationDeg, 2) + '° · added as a ladder row of calibration #' + (b.calibrationId || (cur ? cur.id : '—')) + '.' : 'Enter the dots and the net mass to see mg per dot.');
    IL.gate($('btn-sd-save'), IL.lockReason(s, { idle: false, pressureOff: false }) || (typed ? Object.values(p.errs).find(Boolean) : 'Enter the dots and the net mass.'), $('sd-reason'));
  }
  function renderRecords() {
    const d = C.data;
    const cur = d && d.current;
    IL.keyed($('cal-current'), JSON.stringify([C.ver, C.error]), () => (!d ? el('span', { class: 'muted', text: C.error ? 'Could not load the records: ' + C.error : 'Loading the records…' })
      : cur ? [el('span', { class: 'cal-current__k', text: 'Current calibration #' + cur.id }), ' q = ', el('b', { text: IL.fmt(cur.qMm3PerRev, 4) }), ' mm³ per screw turn · ',
        pcr.badge(cur.status === 'characterized' ? 'characterized' : 'provisional'), cur.method !== cur.status ? ' ' + cur.method : '']
        : el('span', { class: 'muted', text: 'No calibration yet.' })));
    const res = C.lastResult;
    IL.setText($('cal-result'), res ? res.text : '');
    IL.show($('cal-result'), !!res);
    const sel = C.ladderOf;
    IL.keyed($('cal-table').tBodies[0], JSON.stringify([C.ver, sel]), () => {
      if (!d) return null;
      const list = d.calibrations.slice().reverse();
      if (!list.length) return el('tr', {}, el('td', { colspan: '8', class: 'muted', text: 'No calibrations.' }));
      return list.map((k) => {
        const tr = el('tr', { class: 'is-click', 'aria-selected': (sel === null ? cur && k.id === cur.id : k.id === sel) ? 'true' : null, title: 'Show the dot ladder of #' + k.id },
          el('td', {}, el('button', { type: 'button', class: 'link', 'data-focus-key': 'cal:' + k.id, text: '#' + k.id })),
          el('td', { text: k.method }), el('td', {}, pcr.badge(k.status === 'characterized' ? 'characterized' : 'provisional')),
          el('td', { class: 'r', text: IL.fmt(k.qMm3PerRev, 4) }), el('td', { class: 'r', text: k.qCv === null ? '—' : IL.fmt(100 * k.qCv, 1) + ' %' }),
          el('td', { class: 'r', text: k.qN === null ? '—' : String(k.qN) }), el('td', { class: 'nowrap', text: IL.fmtDateTime(k.createdAtMs - S.skewMs) }),
          el('td', { class: 'cal-notes', text: k.notes || '', title: k.notes || null }));
        tr.addEventListener('click', () => { if (cur && k.id === cur.id) { C.ladderOf = null; IL.render(); } else loadLadder(k.id); });
        return tr;
      });
    });
    const rows = sel === null ? (d ? d.ladder : null) : C.ladder;
    IL.setText($('ladder-k'), sel === null ? 'Dot ladder of the current calibration' : 'Dot ladder of calibration #' + sel);
    IL.keyed($('ladder-table').tBodies[0], JSON.stringify([C.ver, sel, rows ? rows.length : -1, C.ladderErr]), () => {
      if (C.ladderErr) return el('tr', {}, el('td', { colspan: '7', class: 'muted', text: 'Could not load: ' + C.ladderErr }));
      if (!rows) return el('tr', {}, el('td', { colspan: '7', class: 'muted', text: 'Loading…' }));
      if (!rows.length) return el('tr', {}, el('td', { colspan: '7', class: 'muted', text: 'No dot ladder rows (a history only: boards use the dispense profile).' }));
      return rows.map((r) => el('tr', {},
        el('td', { class: 'r', text: IL.fmt(r.rotationDeg, 1) }), el('td', { class: 'r', text: String(r.n) }),
        el('td', { class: 'r', text: IL.fmt(r.diameterMeanMm, 3) }), el('td', { class: 'r', text: r.diameterCv === null || r.diameterCv === undefined ? '—' : IL.fmt(100 * r.diameterCv, 1) + ' %' }),
        el('td', { class: 'r', text: IL.fmt(r.mgPerDotMean, 3) }), el('td', { class: 'r', text: IL.fmt(r.heightMm, 3) }), el('td', { text: r.method || '' })));
    });
  }
  function renderGuide() {
    const g = $('setup-guide');
    IL.show(g, !!C.guide);
    if (!C.guide) return;
    IL.keyed(g, JSON.stringify([C.guide.key, C.guide.validate]), () => {
      const t = C.guide.t, r = t.rotationPerDotDeg;
      const close = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', 'aria-label': 'Close the guide' }, IL.icon('i-close'), 'Close');
      close.addEventListener('click', () => { C.guide = null; IL.render(); });
      const back = el('button', { type: 'button', class: 'link', text: 'Back to ' + t.name, onclick: () => IL.go('#profile/pad-types/' + t.typeKey) });
      return [el('div', { class: 'cal-guide__h' }, IL.glyph('info', 'sm'), el('b', { text: (C.guide.validate ? 'Validating ' : 'Setting up ') + t.name + ' (' + t.typeKey + ')' }), el('span', { class: 'spacer' }), close),
        el('ol', {}, C.guide.validate ? [
          el('li', { text: 'Dispense a coupon of profile dots (' + IL.fmt(r, 1) + '° each) on a weighed slide: a test board run of a coupon board, or Scale: shots with the slide’s place.' }),
          el('li', { text: 'Weigh it and enter the batch under Scale: dot batch. A stencil would print ' + IL.fmt(t.targetMassMg, 3) + ' mg per pad (information).' }),
          el('li', { text: 'If the result meets your tolerance, edit the type in Profile and set its status to “validated on a board”.' }),
        ] : [
          el('li', { text: 'Needle Z frame recorded, blank place and blank surface set, needle touched off (the touch-off card above).' }),
          el('li', { text: 'Run the dot ladder (turns filled in around one profile dot, ' + IL.fmt(r, 1) + '°) and/or weigh a dot batch: records only, the dose stays the profile’s.' }),
          el('li', { text: 'When the dots repeat, edit the type in Profile and set its status to “characterized”.' }),
        ]), el('div', {}, back)];
    });
  }
  /** Set up / Validate from a pad type: fill the turns around one profile dot and show the steps. */
  pcr.openGuide = function (t, validate) {
    C.guide = { t, validate: !!validate, key: t.typeKey };
    C.guideFlash = true;
    const r = t.rotationPerDotDeg;
    if (isNum(r)) {
      $('dl-rots').value = [0.6, 0.8, 1, 1.2, 1.4].map((f) => (r * f).toFixed(1)).join(', ');
      $('sd-rot').value = Number(r).toFixed(2);
      C.prefill['sd-rot'] = $('sd-rot').value;
      $('to-rot').value = '';
    }
    IL.render();
  };

  // ================================================================================== context panel `cal`
  function renderPanel(p, s, first) {
    if (first) {
      p.body.append(el('div', { class: 'panel__title' }, el('h2', { text: 'Calibration runs' })),
        el('div', { id: 'cal-pv-now' }),
        el('div', { class: 'section-k' }, el('span', { text: 'Before a calibration run' }), el('span', { class: 'rule' })),
        el('div', { id: 'cal-pv-pre' }),
        el('div', { class: 'section-k' }, el('span', { text: 'Head position (mm)' }), el('span', { class: 'rule' })),
        el('div', { id: 'cal-pv-head' }),
        el('p', { class: 'panel__lead', text: "Jog in OpenPnP's window: this page cannot move the head." }));
    }
    const run = taskOf(s);
    const now = $('cal-pv-now');
    if (run) {
      const running = run.running;
      const nc = noContact(run);
      const kind = running ? 'live' : nc ? 'need' : pcr.statusKind(run.status);
      const word = (pcr.TASK[run.label] || run.label) + (nc ? ' · no contact found' : '');
      const pct = run.dotsPlanned ? Math.round(100 * run.dotsDone / run.dotsPlanned) : 0;
      const phases = (run.phases || []).slice(-6);
      const sig = JSON.stringify([run.runId, kind, run.dotsDone, run.phase, Math.floor(run.elapsedS || 0), run.message]);
      if (now.dataset.sig !== sig) {
        now.dataset.sig = sig;
        now.replaceChildren(el('div', { class: 'cal-pv cal-pv--' + kind },
          el('div', { class: 'cal-pv__h' }, IL.glyph(kind, 'sm'), el('b', { text: running ? word + ' running' : nc ? word : word + ' · ' + pcr.statusWord(run.status) }),
            el('span', { class: 'spacer' }), el('span', { class: 'nowrap', text: IL.fmtClock(run.elapsedS) })),
          run.dotsPlanned ? el('div', {}, el('div', { class: 'cal-pv__n' }, String(run.dotsDone), el('small', { text: ' of ' + IL.plural(run.dotsPlanned, 'dot') + ' done' })),
            el('div', { class: 'bar' }, el('i', { style: 'width:' + pct + '%' }))) : null,
          phases.length ? el('ol', { class: 'cal-pv__ph' }, phases.map((ph, i) => el('li', { class: i === phases.length - 1 && running ? 'now' : null, text: ph }))) : null,
          !running && run.message ? el('div', { class: 'cal-pv__m', text: run.message }) : null,
          running ? el('div', { class: 'cal-pv__m', text: 'STOP stops it at once (F9).' }) : null));
      }
    } else if (now.dataset.sig !== 'none') {
      now.dataset.sig = 'none';
      now.replaceChildren(el('p', { class: 'panel__lead', text: 'Nothing running. Touch-off, dot ladder and scale shots show their progress here; each one moves the head and dispenses, so each asks for a second press.' }));
    }
    const d = s.dispense || {};
    renderPrereqs($('cal-pv-pre'), prereqs(s, { machine: true, engine: true, frame: true, touched: true, place: true, surface: true, pressure: true }, d.blankLocationName));
    pcr.headTable($('cal-pv-head'), s);
  }

  // ================================================================================== workspace
  let root = null;
  IL.workspace('calibrate', {
    defaultView: 'cal',
    mount(r) {
      root = r;
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Calibrate · records and experiments' }),
          el('h1', {}, 'Calibrate'),
          el('div', { class: 'meta' }, el('span', { text: 'The touch-off sets the needle offset and the start angle sets where dots start. Everything else is a record.' })))),
        el('div', { class: 'notebox notebox--info cal-note', id: 'cal-history-note' }, IL.glyph('info', 'sm'),
          el('div', {}, el('b', { text: 'Records only: boards use the dispense profile. ' }),
            'A dot is a whole number of screw motor steps at a fixed start angle; partial turns vary about ±50 % with the screw angle, so ladder sizes and scale masses are a history, not a dose.')),
        el('div', { class: 'notebox notebox--info cal-guide', id: 'setup-guide', hidden: true }),
        el('div', { class: 'grid-cards cal-grid' }, touchOffCard(), angleCard(), ladderCard(), shotsCard(), dotsCard(), recordsCard()));
      for (const n of root.querySelectorAll('input, select')) n.addEventListener(n.tagName === 'SELECT' || n.type === 'checkbox' ? 'change' : 'input', () => IL.render());
      pcr.stickyTop(root);
      IL.on('taskend', (run) => { if (CAL_TASKS.has(run.label) || C.data) loadCalibrations(); });
    },
    show() { loadCalibrations(); },
    route(r) {
      const map = { 'touch-off': 'cal-touch-off', angle: 'cal-angle', shots: 'cal-shots', dots: 'cal-dots', ladder: 'cal-ladder', records: 'cal-records' };
      if (C.guideFlash) { C.guideFlash = false; requestAnimationFrame(() => requestAnimationFrame(() => IL.flash($('setup-guide')))); }
      else if (r.section && map[r.section]) requestAnimationFrame(() => IL.flash($(map[r.section])));
    },
    render(s) {
      renderGuide();
      renderTouchOff(s);
      renderAngle(s);
      renderLadder(s);
      renderShots(s);
      renderDots(s);
      renderRecords();
    },
    panel(p, s, first) { renderPanel(p, s, first); },
    nav(s) {
      if (IL.taskRunning(s)) {
        const run = taskOf(s);
        return run ? { sub: (pcr.TASK[run.label] || run.label) + ' running', kind: 'live' } : { sub: 'Locked · running' };
      }
      const n = (s.dispense && s.dispense.needle) || {};
      if (!n.anchored) return { sub: 'Needle frame first', kind: '' };
      if (!n.touchedOff) return { sub: 'Touch-off needed', kind: 'need' };
      // short: the subline never wraps ("Frame offset" = the confirmed frame offset, not a measured touch-off)
      return { sub: (offsetWord(n) === 'Touched off' ? 'Touch-off ' : 'Frame offset ') + (n.setAtMs ? IL.fmtTime(n.setAtMs - S.skewMs) : ''),
        title: offsetWord(n) + (n.setAtMs ? ' ' + IL.fmtTime(n.setAtMs - S.skewMs) : '') };
    },
  });
})();
