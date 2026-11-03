/* records.js: the Records workspace (docs/instancelabs/UI_DESIGN.md §8.5). Segmented control #records-seg:
   Boards (#records-runs, GET /api/runs?limit=50: time, board, status apart from outcome, dots, DPH dots only / overall,
   profile), Activity (#records-events, GET /api/events?limit=100 every 5 s while shown + this page's log),
   Calibrations (#records-cals) and Touch-offs (#records-touchoffs) from GET /api/calibrations.
   Route #records/<tab>[/<id>]: <id> opens the detail in the context panel `record` (dot timings and the done-dot map
   for the last run, "Finish the remaining pads" for a stopped board, the ladder of a calibration, a touch-off's
   gaps). Read-only except that one board action, which goes through Run's confirm. Styles: profile.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const pcr = IL.pcr;
  const { isNum } = IL;
  const TABS = [['boards', 'Boards'], ['activity', 'Activity'], ['calibrations', 'Calibrations'], ['touch-offs', 'Touch-offs']];
  const R = { tab: 'boards', sel: null, panelKey: undefined, kind: 'boards', evFilter: 'all', events: null, evErr: null, evTimer: null,
    last: null, lastLoading: null, plans: {}, planLoading: {}, ladders: {}, escOff: null,
    pcals: null, pcalsErr: null, pcalsLoading: null, pcalsAt: 0, pcalsTip: 'all' };
  const serverToClient = IL.toClient;

  // ================================================================================== run helpers
  function runTitle(r) {
    switch (r.kind) {
      case 'board': return r.board || 'Board';
      case 'clean-check': return 'Pad check' + (r.board ? ' · ' + r.board : '');
      case 'shots': return r.board === 'waste' ? 'Prime' : r.board === 'waste-test-dot' ? 'Test dot' : 'Scale shots' + (r.board ? ' · ' + r.board : '');
      case 'dot-ladder': return 'Dot ladder';
      case 'touch-off': return 'Touch-off';
      // Register board, the automatic probe of a surface, Check Z (AUTO_REGISTER_DESIGN.md §8.2)
      case 'board-registration': return 'Registration' + (r.board ? ' · ' + r.board : '');
      case 'surface-probe': return 'Surface probe' + (r.board ? ' · ' + r.board : '');
      case 'z-check': return 'Z check';
      // the pad calibration's engine steps (PROFILE_CALIBRATION_DESIGN.md §13.5)
      case 'pad-ladder': return 'Pad calibration · blank';
      case 'pad-weigh': return 'Pad calibration · coupons';
      // tip setups (PROFILE_CALIBRATION_DESIGN.md §22.4): the picture of the tip from below
      case 'tip-picture': return 'Tip picture';
      default: return IL.cap(r.kind || 'Task');
    }
  }
  function outcome(r) {
    const done = r.dots_done || 0, n = r.dots_planned || 0;
    if (r.status === 'RUNNING') return n ? done + ' of ' + n + ' dots so far' : 'running';
    if (r.status === 'REFUSED') return /^I10/.test(r.message || '') ? 'pads not clean' : 'refused before any dot';
    if (r.kind === 'clean-check') return r.status === 'OK' ? 'checked' : 'not finished';
    if (r.kind !== 'board' && r.message) return r.message;
    if (!n) return r.status === 'OK' ? 'done' : '—';
    // a run of part of a board (a test of the first N dots, the pads left after a STOP): against the whole board
    const part = /-subset-/.test(String(r.plan_id || ''));
    const kind = IL.run && IL.run.kinds ? IL.run.kinds[r.plan_id] : null;
    const base = part ? (R.planList || []).find((x) => x.planId === IL.basePlanId(r.plan_id)) : null;
    if (part && kind === 'rest' && done >= n) return 'the ' + IL.plural(n, 'dot') + ' left';
    if (part) return done + ' of ' + (base ? base.dots : n) + ' dots' + (kind === 'test' ? ' (test)' : base ? '' : ' (part of the board)');
    if (r.status === 'OK' && done >= n) return 'all ' + IL.plural(n, 'dot');
    return done + ' of ' + n + ' dots';
  }
  function profileOf(r) {
    const lr = S.status && S.status.lastRun;
    if (lr && lr.runId === r.run_uuid && lr.profile) return lr.profile;
    if (R.last && R.last.runId === r.run_uuid && R.last.profile) return R.last.profile;
    const base = IL.basePlanId(r.plan_id);
    if (base && S.plan && S.plan.planId === base && S.plan.profile) return S.plan.profile;
    const k = base && (R.planList || []).find((x) => x.planId === base);
    if (k && k.profile) return k.profile;
    const p = base && R.plans[base];
    return (p && p.profile) || null;
  }
  /** GET /api/plans (small entries with their profile): names the profile of a run without fetching every full plan. */
  function loadPlanList() {
    if (R.planListLoading) return;
    R.planListLoading = true;
    IL.api('GET', '/api/plans').then((l) => { R.planList = Array.isArray(l) ? l : []; }, () => { R.planList = R.planList || []; })
      .then(() => { R.planListLoading = false; R.planListAt = Date.now(); IL.render(); });
  }
  const statusCell = (st) => el('span', { class: 'badge badge--' + pcr.statusKind(st) }, IL.glyph(pcr.statusKind(st), 'xs'), el('span', { text: pcr.statusWord(st) }));
  const runs = () => (Array.isArray(S.runs) ? S.runs : []);
  const isToday = (ms) => !!ms && new Date(ms).toDateString() === new Date().toDateString();

  async function loadLast() {
    if (R.lastLoading) return R.lastLoading;
    R.lastLoading = IL.api('GET', '/api/runs/last').then((x) => { R.last = x && x.run; }, () => { R.last = null; })
      .then(() => { R.lastLoading = null; IL.render(); });
    return R.lastLoading;
  }
  /**
   * The full plan of an opened run (its done-dot map) and, for a run of part of the board (a test, the pads left), that
   * run's own plan too (which pads it covered): at most a few kept (a 5,000-dot plan is large), oldest dropped.
   */
  const PLANS_KEPT = 4;
  function loadPlanFor(id) {
    const base = IL.basePlanId(id);
    loadPlan(base);
    if (id !== base) loadPlan(id);
  }
  function loadPlan(id) {
    if (!id || id in R.plans || R.planLoading[id]) return;
    if (S.plan && S.plan.planId === id) { R.plans[id] = S.plan; return; }
    R.planLoading[id] = true;
    IL.api('GET', '/api/plan/' + encodeURIComponent(id)).then((p) => { R.plans[id] = p; }, () => { R.plans[id] = null; })
      .then(() => {
        delete R.planLoading[id];
        const keys = Object.keys(R.plans);
        for (const k of keys.slice(0, Math.max(0, keys.length - PLANS_KEPT))) if (k !== id) delete R.plans[k];
        IL.render();
      });
  }
  async function loadEvents() {
    try { R.events = await IL.api('GET', '/api/events?limit=100'); R.evErr = null; } catch (e) { R.evErr = IL.errorText(e); }
    IL.render();
  }
  function startEvents() {
    stopEvents();
    loadEvents();
    R.evTimer = setInterval(() => { if (!document.hidden) loadEvents(); }, IL.LIMITS.eventsEveryMs || 5000);
  }
  function stopEvents() { clearInterval(R.evTimer); R.evTimer = null; }

  // ================================================================================== tabs
  function boardsTab() {
    const segB = el('button', { type: 'button', 'data-kind': 'boards', 'aria-pressed': 'true' }, 'Board runs');
    const segA = el('button', { type: 'button', 'data-kind': 'all', 'aria-pressed': 'false' }, 'All tasks');
    for (const b of [segB, segA]) b.addEventListener('click', () => { R.kind = b.dataset.kind; IL.render(); });
    const refresh = el('button', { type: 'button', class: 'btn btn--sm btn--quiet' }, IL.icon('i-refresh'), 'Refresh');
    refresh.addEventListener('click', () => IL.act(refresh, () => IL.loadRuns()));
    return el('section', { class: 'card rec-card', id: 'records-runs', 'data-tab-panel': 'boards' },
      el('div', { class: 'rec-tools' }, el('div', { class: 'seg', role: 'group', 'aria-label': 'Which runs', id: 'rec-kind' }, segB, segA),
        el('span', { class: 'rec-sum', id: 'rec-runs-sum' }), el('span', { class: 'spacer' }), refresh),
      el('div', { class: 'rec-wrap' }, el('table', { class: 'tbl rec-tbl', id: 'rec-runs-t' },
        el('thead', {}, el('tr', {}, [['When'], ['Board or task'], ['Status'], ['Outcome'], ['Dots', 'r'], ['DPH', 'r', 'Dots per hour: dots only (first to last dot) · overall (whole program)'], ['Profile'],
          ['Tip', null, 'The tip setup on the dispenser (— before tip setups were recorded)']]
          .map(([h, c, t]) => el('th', { text: h, class: c || null, title: t || null, scope: 'col' })))),
        el('tbody'))));
  }
  function activityTab() {
    const mk = (k, t) => el('button', { type: 'button', 'data-ev': k, 'aria-pressed': String(k === 'all') }, t);
    const seg = el('div', { class: 'seg', role: 'group', 'aria-label': 'Which entries', id: 'rec-ev-filter' }, mk('all', 'Everything'), mk('warn', 'Warnings and errors'), mk('page', 'This page'));
    for (const b of seg.children) b.addEventListener('click', () => { R.evFilter = b.dataset.ev; IL.render(); });
    return el('section', { class: 'card rec-card', id: 'records-events', 'data-tab-panel': 'activity', hidden: true },
      el('div', { class: 'rec-tools' }, seg, el('span', { class: 'rec-sum', id: 'rec-ev-sum' })),
      el('div', { class: 'rec-wrap' }, el('table', { class: 'tbl rec-tbl rec-ev', id: 'rec-ev-t' },
        el('thead', {}, el('tr', {}, [['Time'], [''], ['Code'], ['What happened'], ['From']].map(([h]) => el('th', { text: h, scope: 'col' })))),
        el('tbody'))));
  }
  /**
   * Calibrations (PROFILE_CALIBRATION_DESIGN.md §22.7): the pad calibrations with their "calibrated for" label, tip,
   * how, outcome and pictures (table#rec-pcals-t, GET /api/pad-calibrations, filtered per tip); the volume-model records
   * of before (table#rec-cals-t) under "Older calibration records".
   */
  function calsTab() {
    const refresh = el('button', { type: 'button', class: 'btn btn--sm btn--quiet' }, IL.icon('i-refresh'), 'Refresh');
    refresh.addEventListener('click', () => IL.act(refresh, () => loadPcals(true)));
    return el('section', { class: 'card rec-card', id: 'records-cals', 'data-tab-panel': 'calibrations', hidden: true },
      el('div', { class: 'rec-tools' }, el('div', { class: 'seg', role: 'group', 'aria-label': 'Which tips', id: 'rec-pcals-tips' }),
        el('span', { class: 'rec-sum', id: 'rec-pcals-sum' }), el('span', { class: 'spacer' }), refresh,
        el('button', { type: 'button', class: 'btn btn--sm btn--quiet', onclick: () => IL.go('#calibrate/records') }, 'Calibrate', IL.icon('i-chev'))),
      el('div', { class: 'rec-wrap' }, el('table', { class: 'tbl rec-tbl rec-pcals', id: 'rec-pcals-t' },
        el('thead', {}, el('tr', {}, [['When'], ['Calibrated for'], ['Tip'], ['How'], ['Outcome'], ['Pictures']]
          .map(([h, c, t]) => el('th', { text: h, class: c || null, title: t || null, scope: 'col' })))),
        el('tbody'))),
      el('details', { class: 'disclose rec-cals-old', id: 'rec-cals-old' }, el('summary', {}, 'Older calibration records (volume model, records only)', el('span', { class: 'rec-sum', id: 'rec-cals-sum' })),
        el('div', { class: 'rec-wrap' }, el('table', { class: 'tbl rec-tbl', id: 'rec-cals-t' },
          el('thead', {}, el('tr', {}, [['#'], ['Saved'], ['Method'], ['Status'], ['q', 'r', 'mm³ per screw turn'], ['CV', 'r'], ['n', 'r'], ['Set psi', 'r'], ['Tip']]
            .map(([h, c, t]) => el('th', { text: h, class: c || null, title: t || null, scope: 'col' })))),
          el('tbody')))));
  }
  // ---------------------------------------------------------------- pad calibrations (§22.7)
  const tipsList = () => { const d = IL.tips && IL.tips.data(); return d ? d.setups || [] : []; };
  const tipsSig = () => tipsList().map((x) => x.key + ':' + x.name).join('|');
  function tipNameOf(key) { const x = tipsList().find((t) => t.key === key); return x ? x.name : key; }
  /** GET /api/pad-calibrations (the tip filter: ?setup=): every 10 s at most while shown, and when asked. */
  function loadPcals(force) {
    if (R.pcalsLoading) return R.pcalsLoading;
    if (!force && R.pcals && R.pcalsFor === R.pcalsTip && Date.now() - R.pcalsAt < 10000) return Promise.resolve(R.pcals);
    const tip = R.pcalsTip;
    R.pcalsLoading = IL.api('GET', '/api/pad-calibrations?limit=100' + (tip !== 'all' ? '&setup=' + encodeURIComponent(tip) : ''))
      .then((l) => { R.pcals = Array.isArray(l) ? l : []; R.pcalsErr = null; R.pcalsFor = tip; }, (e) => { R.pcalsErr = IL.errorText(e); })
      .then(() => { R.pcalsLoading = null; R.pcalsAt = Date.now(); IL.render(); return R.pcals; });
    return R.pcalsLoading;
  }
  IL.on('photos', () => { if (IL.active() === 'records') loadPcals(true); });
  IL.on('taskend', () => { R.pcalsAt = 0; });
  /** The pad type's display name ("Chip 0603") from Profile's list when it is read, else its key. */
  function typeWords(key) {
    const d = pcr.padTypes && pcr.padTypes();
    const t = d ? d.families.flatMap((f) => f.types).find((x) => x.typeKey === key) : null;
    if (!t) return key;
    const fam = String(t.family || '').replace(/\s*\([^)]*\)\s*/g, ' ').trim();
    return (/^\S+$/.test(fam) && !/^custom$/i.test(fam) && !String(t.name || '').includes(' · ') ? fam + ' ' : '') + t.name;
  }
  /** A record's check in words (its stored text is short: "ok", "132 dots"). */
  const CHECK = {
    optical: (k) => 'Camera checks on the blank: ' + (k.measured || k.text || '—'),
    mass: (k) => 'Weighed ' + (k.measured || '—') + (k.predicted ? ' (predicted ' + k.predicted + ')' : '') + (k.limit ? '; ' + k.limit : ''),
    amount: (k) => 'Amount ' + (k.measured || '—') + (k.predicted ? ' (target ' + k.predicted + (k.limit ? ', ' + k.limit : '') + ')' : ''),
    r6: (k) => (k.measured || k.text || '') + (k.limit ? ' (' + k.limit + ')' : ''),
    geometry: (k) => (k.measured || 'Geometry') + (k.limit ? ' (limits ' + k.limit + ')' : ''),
    control: (k) => (k.pass ? 'The control dot matched' : 'The control dot did not match') + (k.text && k.text !== 'ok' ? ': ' + k.text : ''),
    tip: (k, x) => (k.pass ? 'Made with the ' : 'Not made with the ') + (x.setupName || 'mounted tip'),
    regulator: (k) => (k.pass ? 'The regulator read at the profile’s pressure' : 'The regulator: ' + k.text),
    validated: (k) => (k.pass ? 'No validated pad type was changed' : k.text),
  };
  const checkWords = (k, x) => (CHECK[k.key] ? CHECK[k.key](k, x) : k.text || [k.key, k.measured, k.limit].filter(Boolean).join(': '));
  const OUTCOME_KIND = { characterized: 'ok', validated: 'ok', measured: 'ok', partly: 'need', provisional: 'need', failed: 'block', stopped: 'block', cancelled: 'off' };
  function outcomeBadge(x) {
    if ((x.flags || []).includes('wrong-tip')) return el('span', { class: 'badge badge--block', title: 'Made with another tip: its versions were restored' }, IL.glyph('block', 'xs'), el('span', { text: 'wrong tip' }));
    if (pcr.STATUS[x.outcome]) return pcr.badge(x.outcome);
    const k = OUTCOME_KIND[x.outcome] || 'off';
    return el('span', { class: 'badge badge--' + k }, IL.glyph(k, 'xs'), el('span', { text: x.outcome || '—' }));
  }
  /** span.rec-thumbs: three thumbnail buttons, then "+n" (removed pictures are not shown here). */
  function thumbs(x) {
    const live = (x.photos || []).filter((p) => !p.removedAtMs);
    if (!live.length) return el('span', { class: 'dim', text: '—' });
    const out = live.slice(0, 3).map((p) => {
      const b = el('button', { type: 'button', class: 'photo-thumb rec-thumb', 'data-photo': p.id, title: (p.caption || 'Picture') + ': open' },
        el('img', { src: p.thumbUrl, alt: p.caption || 'Picture ' + p.id, width: '40', height: '30', loading: 'lazy' }));
      b.addEventListener('click', (ev) => { ev.stopPropagation(); IL.photos.view(p, { list: live, from: b }); });
      return b;
    });
    if (live.length > 3) {
      const more = el('button', { type: 'button', class: 'photo-thumb rec-thumb rec-thumb--more', title: 'All ' + live.length + ' pictures' }, '+' + (live.length - 3));
      more.addEventListener('click', (ev) => { ev.stopPropagation(); IL.go('#records/calibrations/p' + x.id); });
      out.push(more);
    }
    return el('span', { class: 'rec-thumbs' }, out);
  }
  function renderPcals() {
    const tips = tipsList().filter((x) => !x.retired || x.key === R.pcalsTip);
    IL.keyed($('rec-pcals-tips'), JSON.stringify([tips.map((x) => [x.key, x.name]), R.pcalsTip]), () => [['all', 'All tips']].concat(tips.map((x) => [x.key, x.name])).map(([k, t]) => {
      const b = el('button', { type: 'button', 'data-tip': k, 'aria-pressed': String(k === R.pcalsTip) }, t);
      b.addEventListener('click', () => { R.pcalsTip = k; loadPcals(true); IL.render(); });
      return b;
    }));
    if (!R.pcals || R.pcalsFor !== R.pcalsTip || Date.now() - R.pcalsAt > 10000) loadPcals();
    const list = R.pcals && R.pcalsFor === R.pcalsTip ? R.pcals : null;
    const sel = R.tab === 'calibrations' ? R.sel : null;
    IL.setText($('rec-pcals-sum'), R.pcalsErr ? 'Could not load the pad calibrations: ' + R.pcalsErr : !list ? 'Loading…' : IL.plural(list.length, 'pad calibration'));
    const key = JSON.stringify([list ? list.map((x) => [x.id, x.label, x.flags, (x.photos || []).map((p) => [p.id, p.removedAtMs])]) : null, sel, R.pcalsErr, !!(pcr.padTypes && pcr.padTypes())]);
    IL.keyed($('rec-pcals-t').tBodies[0], key, () => {
      if (!list) return el('tr', {}, el('td', { colspan: '6', class: 'muted rec-empty', text: R.pcalsErr ? 'Could not load the pad calibrations: ' + R.pcalsErr : 'Loading the pad calibrations…' }));
      if (!list.length) return el('tr', {}, el('td', { colspan: '6', class: 'muted rec-empty', id: 'rec-pcals-empty', text: R.pcalsTip === 'all' ? 'No pad calibrations yet: Profile › Needs a profile › Calibrate automatically.'
        : 'No pad calibrations with the ' + tipNameOf(R.pcalsTip) + ' yet.' }));
      return list.map((x) => clickRow('#records/calibrations/p' + x.id, 'pcal:' + x.id, sel === 'p' + x.id,
        el('span', { title: IL.fmtAgo(serverToClient(x.createdAtMs), Date.now()) }, IL.fmtDateTime(serverToClient(x.createdAtMs))),
        [el('td', { class: 'rec-label' }, el('div', { class: 'rec-label__t', text: x.label || '—' }),
          el('div', { class: 'rec-label__s', text: typeWords(x.typeKey) + (isNum(x.versionTo) ? ' · v' + x.versionTo : '') })),
        el('td', { class: 'rec-name', text: x.setupName || x.setupKey || '—' }),
        el('td', { class: 'nowrap', text: x.method === 'by-hand' ? 'by hand' : 'automatic' }),
        el('td', {}, outcomeBadge(x)),
        el('td', {}, thumbs(x))], (x.flags || []).includes('wrong-tip') ? 'is-bad' : ''));
    });
  }
  function touchTab() {
    return el('section', { class: 'card rec-card', id: 'records-touchoffs', 'data-tab-panel': 'touch-offs', hidden: true },
      el('div', { class: 'rec-tools' }, el('span', { class: 'rec-sum', id: 'rec-to-sum' }), el('span', { class: 'spacer' }),
        el('button', { type: 'button', class: 'btn btn--sm btn--quiet', onclick: () => IL.go('#calibrate/touch-off') }, 'Touch-off', IL.icon('i-chev'))),
      el('div', { class: 'rec-wrap' }, el('table', { class: 'tbl rec-tbl', id: 'rec-to-t' },
        el('thead', {}, el('tr', {}, [['When'], ['What'], ['Offset', 'r', 'mm'], ['Before', 'r', 'mm'], ['Δ', 'r', 'mm'], ['Contact gap', 'r', 'mm']]
          .map(([h, c, t]) => el('th', { text: h, class: c || null, title: t || null, scope: 'col' })))),
        el('tbody'))));
  }
  /** A clickable row: the first cell holds a link button (keyboard), the whole row opens the detail. */
  function clickRow(href, focusKey, selected, first, cells, cls) {
    const b = el('button', { type: 'button', class: 'link rec-open', 'data-focus-key': focusKey }, first);
    const tr = el('tr', { class: 'is-click' + (cls ? ' ' + cls : ''), 'aria-selected': selected ? 'true' : null }, el('td', { class: 'nowrap' }, b), cells);
    tr.addEventListener('click', () => IL.go(href));
    return tr;
  }
  function renderRuns() {
    for (const b of $('rec-kind').children) IL.setAttr(b, 'aria-pressed', String(b.dataset.kind === R.kind));
    const all = runs();
    // the profile of a run lives in its plan: the light plan list names it (OpenPnP keeps the last 20 plans in memory)
    const newest = all[0] ? all[0].id : null;
    if (newest !== null && R.planListFor !== newest) { R.planListFor = newest; loadPlanList(); }   // again when a new run arrives
    const list = R.kind === 'boards' ? all.filter((r) => r.kind === 'board') : all;
    const today = all.filter((r) => r.kind === 'board' && isToday(serverToClient(r.started_at)));
    const lerr = S.runs === null ? IL.loadError('runs') : null;
    IL.setText($('rec-runs-sum'), lerr ? 'Could not load the runs: ' + lerr.text : S.runs === null ? 'Loading…'
      : IL.plural(list.length, R.kind === 'boards' ? 'board run' : 'run') + ' · ' + IL.plural(today.length, 'board run') + ' today');
    IL.setClass($('rec-runs-sum'), 'rec-sum' + (lerr ? ' reason--block' : ''));
    const sel = R.tab === 'boards' ? R.sel : null;
    const lr = S.status && S.status.lastRun;
    const key = JSON.stringify([list.map((r) => [r.id, r.status, r.dots_done]), R.kind, sel, Object.keys(R.plans).length, (R.planList || []).length, lr ? lr.runId : '', S.plan ? S.plan.planId : '', lerr ? lerr.text : '',
      tipsSig()]);
    IL.keyed($('rec-runs-t').tBodies[0], key, () => {
      if (!list.length && lerr) {
        return el('tr', {}, el('td', { colspan: '8', class: 'rec-empty' }, el('div', { class: 'notebox notebox--block' }, 'Could not load the runs: ' + lerr.text + ' ',
          el('button', { type: 'button', class: 'btn btn--sm', onclick: () => IL.loadRuns() }, IL.icon('i-refresh'), 'Retry'))));
      }
      if (!list.length) return el('tr', {}, el('td', { colspan: '8', class: 'muted rec-empty', text: S.runs === null ? 'Loading the runs…' : R.kind === 'boards' ? 'No board runs yet. Board runs appear here when they end; choose All tasks for prime, test dots and calibration runs.' : 'No runs yet.' }));
      return list.map((r) => {
        const start = serverToClient(r.started_at);
        const prof = profileOf(r);
        const dph = r.kind === 'board' && (isNum(r.dph_motion) || isNum(r.dph_overall));
        return clickRow('#records/boards/' + r.run_uuid, 'run:' + r.run_uuid, sel === r.run_uuid,
          el('span', { title: IL.fmtAgo(start, Date.now()) }, IL.fmtDateTime(start)),
          [el('td', { class: 'rec-name', title: runTitle(r) }, runTitle(r)),
            el('td', {}, statusCell(r.status)),
            el('td', { class: 'rec-out', title: r.message || null, text: outcome(r) }),
            el('td', { class: 'r nowrap', text: r.dots_planned ? (r.dots_done || 0) + '/' + r.dots_planned : '—' }),
            el('td', { class: 'r nowrap', title: dph ? 'dots only ' + IL.fmtInt(r.dph_motion) + ' · overall ' + IL.fmtInt(r.dph_overall) : null },
              dph ? [el('b', { text: IL.fmtInt(r.dph_motion) }), el('span', { class: 'dim', text: ' · ' + IL.fmtInt(r.dph_overall) })] : el('span', { class: 'dim', text: '—' })),
            el('td', { class: 'rec-prof', title: prof ? IL.profileText(prof) : r.kind !== 'board' ? null : 'The profile is stored with the plan; OpenPnP keeps only the last 20 plans in memory' },
              prof ? prof.name : el('span', { class: 'dim', text: r.kind === 'board' && R.planListLoading ? '…' : '—' })),
            el('td', { class: 'rec-name', text: r.setup_key ? tipNameOf(r.setup_key) : '—', title: r.setup_key ? r.setup_key : 'before tip setups were recorded' })],
          r.status === 'ABORTED' || r.status === 'ERROR' ? 'is-bad' : '');
      });
    });
  }
  function renderEvents() {
    for (const b of $('rec-ev-filter').children) IL.setAttr(b, 'aria-pressed', String(b.dataset.ev === R.evFilter));
    const ev = (R.events || []).map((x) => ({ atMs: serverToClient(x.ts), code: x.code || '', text: IL.eventText(x.message || ''), full: x.message || '', kind: x.level === 'ERROR' ? 'block' : x.level === 'WARN' ? 'need' : 'info', src: 'OpenPnP', id: 'e' + x.id }));
    const page = S.activity.map((x, i) => ({ atMs: x.atMs, code: '', text: x.text, kind: x.kind, src: 'this page', id: 'p' + x.atMs + i }));
    let all = R.evFilter === 'page' ? page : ev.concat(page);
    if (R.evFilter === 'warn') all = all.filter((x) => x.kind === 'block' || x.kind === 'need');
    all.sort((a, b) => b.atMs - a.atMs);
    all = all.slice(0, 200);
    IL.setText($('rec-ev-sum'), R.events === null && !R.evErr ? 'Loading…' : R.evErr ? 'OpenPnP log not loaded: ' + R.evErr : IL.plural(all.length, 'entry', 'entries') + ' · refreshes every 5 s');
    IL.keyed($('rec-ev-t').tBodies[0], JSON.stringify([all.length, all[0] ? all[0].id + all[0].text : '', R.evFilter]), () => {
      if (!all.length) return el('tr', {}, el('td', { colspan: '5', class: 'muted rec-empty', text: 'Nothing yet.' }));
      return all.map((x) => el('tr', { class: x.kind === 'block' ? 'is-bad' : x.kind === 'need' ? 'is-warn' : null },
        el('td', { class: 'nowrap', title: IL.fmtDateTime(x.atMs) }, IL.fmtTimeS(x.atMs)),
        el('td', {}, IL.glyph(x.kind === 'block' ? 'block' : x.kind === 'need' ? 'need' : x.kind === 'ok' ? 'ok' : 'info', 'xs')),
        el('td', { class: 'nowrap' }, x.code ? el('span', { class: 'icode', text: x.code }) : ''),
        el('td', { class: 'rec-msg', text: x.text, title: x.full && x.full !== x.text ? x.full : null }),
        el('td', { class: 'nowrap dim', text: x.src })));
    });
  }
  function renderCals() {
    const d = pcr.calibrations && pcr.calibrations();
    const list = d ? d.calibrations.slice().reverse() : [];
    const sel = R.tab === 'calibrations' ? R.sel : null;
    IL.setText($('rec-cals-sum'), !d ? 'Loading…' : IL.plural(list.length, 'calibration') + (d.current ? ' · current #' + d.current.id : '') + ' · records only: boards use the dispense profile');
    IL.keyed($('rec-cals-t').tBodies[0], JSON.stringify([list.map((k) => k.id), sel, d && d.current ? d.current.id : null]), () => {
      if (!d) return el('tr', {}, el('td', { colspan: '9', class: 'muted rec-empty', text: 'Loading the calibrations…' }));
      if (!list.length) return el('tr', {}, el('td', { colspan: '9', class: 'muted rec-empty', text: 'No calibrations.' }));
      return list.map((k) => clickRow('#records/calibrations/' + k.id, 'cal:' + k.id, sel === String(k.id), '#' + k.id + (d.current && d.current.id === k.id ? ' · current' : ''),
        [el('td', { class: 'nowrap', text: IL.fmtDateTime(serverToClient(k.createdAtMs)) }), el('td', { text: k.method }),
          el('td', {}, pcr.badge(k.status === 'characterized' ? 'characterized' : 'provisional')),
          el('td', { class: 'r', text: IL.fmt(k.qMm3PerRev, 4) }), el('td', { class: 'r', text: k.qCv === null ? '—' : IL.fmt(100 * k.qCv, 1) + ' %' }),
          el('td', { class: 'r', text: k.qN === null ? '—' : String(k.qN) }), el('td', { class: 'r', text: isNum(k.setPressurePsi) ? IL.fmtPsi(k.setPressurePsi) : '—' }),
          el('td', { class: 'rec-name', text: k.tip || '—', title: k.tip || null })]));
    });
  }
  function renderTouchOffs() {
    const d = pcr.calibrations && pcr.calibrations();
    const list = (d && d.touchOffs) || [];
    const sel = R.tab === 'touch-offs' ? R.sel : null;
    IL.setText($('rec-to-sum'), !d ? 'Loading…' : 'The last ' + IL.plural(list.length, 'needle offset change') + ' (touch-offs, frames, confirmed offsets)');
    IL.keyed($('rec-to-t').tBodies[0], JSON.stringify([list.map((t) => t.id), sel]), () => {
      if (!d) return el('tr', {}, el('td', { colspan: '6', class: 'muted rec-empty', text: 'Loading…' }));
      if (!list.length) return el('tr', {}, el('td', { colspan: '6', class: 'muted rec-empty', text: 'No touch-offs yet.' }));
      return list.map((t) => {
        const v = pcr.touchOffView(t);
        const a = v.analysis;
        return clickRow('#records/touch-offs/' + t.id, 'to:' + t.id, sel === String(t.id), IL.fmtDateTime(serverToClient(t.created_at)),
          [el('td', { class: 'rec-name', text: v.method, title: t.method }), el('td', { class: 'r', text: IL.fmtSigned(t.z_offset_mm, 3) }),
            el('td', { class: 'r', text: isNum(t.previous_offset_mm) ? IL.fmtSigned(t.previous_offset_mm, 3) : '—' }),
            el('td', { class: 'r', text: isNum(v.delta) ? IL.fmtSigned(v.delta, 3) : '—' }),
            el('td', { class: 'r', text: a && isNum(a.contactGapMm) ? IL.fmtSigned(a.contactGapMm, 3) : '—' })]);
      });
    });
  }

  // ================================================================================== context panel `record`
  /**
   * The done-dot map of a run (the shared IL.boardview, as on Run): a timing per dispensed dot gives, per pad, its first k
   * dots in path order (subsets keep the path order); a partly dispensed pad gets the amber "check" ring as after a STOP.
   * `only`: the pads a run of part of the board covered (null: the whole board). The counts and the pads left are this
   * run's: a pad outside it may have paste from an earlier run, Records cannot tell.
   */
  function doneMap(plan, timings, only) {
    const pads = plan.pads || [];
    if (!pads.length) return null;
    const count = {};
    for (const t of timings || []) count[t.padId] = (count[t.padId] || 0) + 1;
    const byPad = {};
    for (const d of plan.dots || []) (byPad[d.padId] = byPad[d.padId] || []).push(d.index);
    const doneIdx = new Set();
    let done = 0, part = 0, left = 0, check = null;
    const leftPads = [];
    for (const p of pads) {
      const idx = byPad[p.padId] || [];
      const k = Math.min(count[p.padId] || 0, idx.length);
      for (const i of idx.slice(0, k)) doneIdx.add(i);
      if (only && !only.has(p.padId)) continue;
      if (idx.length && k >= idx.length) { done++; continue; }
      if (k > 0) { part++; if (!check) check = { index: idx[k], label: p.padId + ' · check' }; } else left++;
      if (!p.blocked) leftPads.push(p.padId);
    }
    const host = el('div', { class: 'rec-mapbox' });
    const src = plan.source || {};
    const bv = IL.boardview.create(host, { aria: 'Done-dot map' + (only ? ' of this run' : '') + ': ' + done + ' pads done, ' + part + ' partly, ' + left + ' not dispensed' });
    bv.setPlan(plan, { origin: src.source === 'file' ? { x: Number(src.x), y: Number(src.y) } : null });
    // H4 (BOARD_IMPORT_DESIGN.md §5.1.1): the board drawing under the map once boardflow.js loads
    if (IL.boardFlow) IL.boardFlow.recordsMap(bv, plan);
    R.mapBv = bv;
    if (timings) bv.update({ done: doneIdx, check });
    return { host, done, part, left, leftPads };
  }
  function runDetail(p, s, r) {
    const lr = (R.last && R.last.runId === r.run_uuid) ? R.last : null;
    const isLast = s.lastRun && s.lastRun.runId === r.run_uuid;
    if (isLast && !lr && !R.lastLoading && (!R.last || R.last.runId !== r.run_uuid)) loadLast();
    if (r.kind === 'board' || r.kind === 'clean-check') loadPlanFor(r.plan_id);
    const plan = R.plans[IL.basePlanId(r.plan_id)] || null;
    // a run of part of the board: its own plan names the pads it covered (undefined while loading, null if forgotten)
    const part = /-subset-/.test(String(r.plan_id || ''));
    const runPlan = part ? R.plans[r.plan_id] : plan;
    const key = JSON.stringify([r.id, r.status, r.dots_done, !!lr, plan ? plan.planId : null, IL.basePlanId(r.plan_id) in R.plans,
      part ? (runPlan ? 1 : runPlan === null ? 0 : -1) : '']);
    IL.keyed(p.body, key, () => {
      // H5 (BOARD_IMPORT_DESIGN.md §5.1.1): the replaced map view lets go of its ResizeObserver and SVGs
      if (R.mapBv) { R.mapBv.destroy(); R.mapBv = null; }
      const start = serverToClient(r.started_at), end = serverToClient(r.ended_at);
      const prof = profileOf(r);
      const out = [
        el('div', { class: 'rec-ph' }, el('div', { class: 'eyebrow', text: r.kind === 'board' ? 'Board run' : 'Task' }), el('h2', { class: 'rec-ph__n', text: runTitle(r) }),
          el('div', { class: 'rec-ph__s' }, statusCell(r.status), el('span', { text: outcome(r) }))),
        r.message ? el('div', { class: 'notebox' + (r.status === 'OK' ? '' : r.status === 'REFUSED' ? ' notebox--need' : ' notebox--block'), text: r.message }) : null,
        el('div', { class: 'minis' },
          mini('Dots', r.dots_planned ? String(r.dots_done || 0) : '—', r.dots_planned ? 'of ' + r.dots_planned : ''),
          mini('Total time', isNum(r.total_s) ? IL.fmtClock(r.total_s) : '—', isNum(r.motion_s) ? 'dots ' + IL.fmtClock(r.motion_s) : ''),
          mini('DPH dots only', isNum(r.dph_motion) ? IL.fmtInt(r.dph_motion) : '—', 'first to last dot · bench 5,490'),
          mini('DPH overall', isNum(r.dph_overall) ? IL.fmtInt(r.dph_overall) : '—', 'whole program')),
        el('dl', { class: 'kv kv--wrap kv--cols' }, [
          ['Started', start ? IL.fmtDateTime(start) + ':' + String(new Date(start).getSeconds()).padStart(2, '0') : '—'],
          ['Ended', end ? IL.fmtTimeS(end) : '—'],
          ['Plan', r.plan_id ? String(r.plan_id).slice(0, 8) + (/-subset-/.test(r.plan_id) ? ' (pads subset)' : '') : '—'],
          ['Calibration', r.calibration_id ? '#' + r.calibration_id : '—'],
          ['Lines sent', isNum(r.lines_sent) ? IL.fmtInt(r.lines_sent) : '—'],
          ['Firmware watchdog', r.keepalive ? 'armed' : 'not armed'],
        ].flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: IL.nb(v) })])),
        prof ? el('dl', { class: 'kv kv--wrap kv--cols' }, el('dt', { text: 'Profile' }), el('dd', { text: prof.name }),
          el('dt', { text: 'Recipe' }), el('dd', { text: IL.nb(IL.recipeParts(prof).slice(1).join(' · ')) })) : null,
      ];
      if (lr) {
        const tm = lr.dotTimings || [];
        const gaps = [];
        for (let i = 1; i < tm.length; i++) gaps.push(tm[i].startMs - tm[i - 1].startMs);
        const mean = gaps.length ? gaps.reduce((a, b) => a + b, 0) / gaps.length : null;
        const slow = gaps.length ? Math.max(...gaps) : null;
        out.push(IL.sectionK('Dot timings'), el('dl', { class: 'kv kv--wrap kv--cols' }, [
          ['First dot', tm.length ? IL.fmtTimeS(serverToClient(tm[0].startMs)) : '—'],
          ['Last dot', tm.length ? IL.fmtTimeS(serverToClient(tm[tm.length - 1].endMs)) : '—'],
          ['Dot to dot', mean !== null ? IL.fmtInt(mean) + ' ms on average · slowest ' + IL.fmtInt(slow) + ' ms' : '—'],
          ['Purge dots', isNum(lr.purgeDots) ? String(lr.purgeDots) : '—'],
          ['Work starts at', isNum(lr.workStartAngleDeg) ? IL.fmtDeg(lr.workStartAngleDeg, 1) + ' screw angle' : '—'],
          ['Pad check', lr.cleanCheck ? IL.cleanSummary(lr.cleanCheck) : 'not run'],
        ].flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: IL.nb(v) })])),
        (lr.warnings || []).length ? el('ul', { class: 'rec-warn' }, lr.warnings.map((w) => el('li', { text: w }))) : null);
      }
      if (r.kind === 'board') {
        out.push(IL.sectionK('Done-dot map'));
        const base = IL.basePlanId(r.plan_id);
        if (!plan) out.push(el('p', { class: 'panel__lead', text: base in R.plans ? 'The plan is no longer in OpenPnP’s memory (it keeps the last 20 plans), so the map cannot be drawn.' : 'Loading the plan…' }));
        else if (part && runPlan === undefined) out.push(el('p', { class: 'panel__lead', text: 'Loading the plan…' }));
        else {
          // the pads this run covered; a forgotten subset plan leaves them unknown: no counts of pads left, no Finish
          const only = part && runPlan ? new Set((runPlan.pads || []).map((x) => x.padId)) : null;
          const known = !part || !!only;
          const mp = doneMap(plan, lr ? lr.dotTimings : null, only);
          if (mp) {
            out.push(mp.host,
              el('div', { class: 'rec-legend' }, el('span', {}, el('i', { class: 'rec-lg rec-lg--done' }), lr ? mp.done + ' done' : 'dots'),
                lr && known ? el('span', {}, el('i', { class: 'rec-lg rec-lg--part' }), mp.part + ' partly') : null,
                lr && known ? el('span', {}, el('i', { class: 'rec-lg rec-lg--left' }), mp.left + ' not dispensed') : null));
            if (!lr) out.push(el('p', { class: 'panel__lead', text: 'Dot timings are kept for the last run only: the map shows the plan without done dots.' }));
            else if (part) {
              out.push(el('p', { class: 'panel__lead', text: only ? 'This run covered ' + IL.plural(only.size, 'pad') + ' of ' + (plan.pads || []).length
                + ': the counts are this run’s. A pad outside it may have paste from an earlier run.'
                : 'This run covered part of the board, and its plan is no longer in OpenPnP’s memory: finish the board from Run’s board decision.' }));
            }
            if (lr && known && (r.status === 'ABORTED' || r.status === 'ERROR') && mp.leftPads.length) {
              const fin = el('button', { type: 'button', class: 'btn btn--primary', id: 'rec-finish' }, 'Finish the ' + IL.plural(mp.leftPads.length, 'pad') + ' left', IL.icon('i-chev'));
              fin.addEventListener('click', (ev) => finishRest(fin, ev, base, mp.leftPads));
              out.push(el('div', { class: 'rec-finish', 'data-refusals': '' }, fin,
                el('div', { class: 'reason', text: 'Opens the run confirm on Run (regulator, paste, hands clear). The partly dispensed pad counts as not done. Keep the board where it is: the done dots belong to this seat.' })));
            }
          }
        }
      }
      return out;
    });
  }
  function finishRest(btn, ev, planId, padIds) {
    if (IL.fixes['run-rest']) { IL.runFix('run-rest', btn, { planId, padIds }, ev); return; }
    IL.act(btn, async () => {
      if (!S.plan || S.plan.planId !== planId) await IL.loadPlan(planId);
      IL.go('#run');
    }, { ok: 'Plan restored on Run: finish the ' + padIds.length + ' pads from the board decision there.' });
  }
  // the subline keeps a label with its number on one line ("bench 5,490")
  const mini = (k, v, s) => el('div', { class: 'mini' }, el('div', { class: 'mini__k', text: k }), el('div', { class: 'mini__v', text: v }), s ? el('div', { class: 'mini__s', text: IL.nb(s) }) : null);
  function calDetail(p, s, id) {
    const d = pcr.calibrations && pcr.calibrations();
    const k = d && d.calibrations.find((x) => String(x.id) === String(id));
    if (k && !(k.id in R.ladders)) {
      R.ladders[k.id] = undefined;
      IL.api('GET', '/api/calibrations/' + k.id + '/ladder').then((x) => { R.ladders[k.id] = x; }, () => { R.ladders[k.id] = null; }).then(() => IL.render());
    }
    const lad = k ? R.ladders[k.id] : undefined;
    IL.keyed(p.body, JSON.stringify([id, !!d, k ? k.id : null, lad === undefined ? 'l' : lad === null ? 'x' : lad.length]), () => {
      if (!d) return el('p', { class: 'panel__lead', text: 'Loading…' });
      if (!k) return el('div', { class: 'notebox notebox--need', text: 'No calibration #' + id + '.' });
      return [el('div', { class: 'rec-ph' }, el('div', { class: 'eyebrow', text: 'Calibration' + (d.current && d.current.id === k.id ? ' · current' : '') }),
        el('h2', { class: 'rec-ph__n', text: '#' + k.id + ' · ' + k.method }), el('div', { class: 'rec-ph__s' }, pcr.badge(k.status === 'characterized' ? 'characterized' : 'provisional'))),
      el('div', { class: 'minis' }, mini('q', IL.fmt(k.qMm3PerRev, 4), 'mm³ per screw turn'), mini('Spread', k.qCv === null ? '—' : IL.fmt(100 * k.qCv, 1) + ' %', k.qN ? 'n = ' + k.qN : 'CV')),
      el('dl', { class: 'kv kv--wrap' }, [
        ['Saved', IL.fmtDateTime(serverToClient(k.createdAtMs))], ['Density', isNum(k.densityMgMm3) ? IL.fmt(k.densityMgMm3, 2) + ' mg/mm³' : '—'],
        ['Tip', k.tip || '—'], ['Cartridge', k.cartridge || '—'], ['Paste lot', k.pasteLot || '—'],
        ['Set pressure', isNum(k.setPressurePsi) ? IL.fmtPsi(k.setPressurePsi) + ' psi' : '—'], ['Screw feed', isNum(k.bFeedDegS) ? IL.fmtInt(k.bFeedDegS) + ' °/s' : '—'],
        ['Barrel', (isNum(k.barrelTempC) ? IL.fmt(k.barrelTempC, 1) + ' °C' : '—') + (isNum(k.barrelPressurePsi) ? ' · ' + IL.fmtPsi(k.barrelPressurePsi) + ' psi' : '')],
        ['Valid until', k.validUntilMs ? IL.fmtDateTime(serverToClient(k.validUntilMs)) : '—'], ['Operator', k.operator || '—'],
      ].flatMap(([a, b]) => [el('dt', { text: a }), el('dd', { text: b })])),
      k.notes ? el('p', { class: 'panel__lead', text: k.notes }) : null,
      IL.sectionK('Dot ladder'),
      lad === undefined ? el('p', { class: 'panel__lead', text: 'Loading…' }) : lad === null ? el('p', { class: 'panel__lead', text: 'Could not load the ladder.' })
        : !lad.length ? el('p', { class: 'panel__lead', text: 'No ladder rows for this calibration.' })
          : el('table', { class: 'tbl rec-mini-t' }, el('thead', {}, el('tr', {}, ['Turn °', 'n', 'Ø mm', 'mg/dot'].map((h) => el('th', { class: 'r' }, IL.unitText(h))))),
            el('tbody', {}, lad.map((r) => el('tr', {}, el('td', { class: 'r', text: IL.fmt(r.rotationDeg, 1) }), el('td', { class: 'r', text: String(r.n) }),
              el('td', { class: 'r', text: IL.fmt(r.diameterMeanMm, 3) }), el('td', { class: 'r', text: IL.fmt(r.mgPerDotMean, 3) }))))),
      el('p', { class: 'panel__lead', text: 'A record only: boards use the dispense profile.' })];
    });
  }
  /**
   * A pad calibration (#records/calibrations/p<id>, §22.7): its label (editable), tip, type and versions, checks, the
   * pictures full width with Add a photo… (upload only: Records stays motion-free) and the evidence; Open the result
   * while the job is one of the 5 kept in memory; Made with another tip….
   */
  function pcalDetail(p, s, id) {
    const x = (R.pcals || []).find((k) => String(k.id) === String(id));
    if (!x && !R.pcalsLoading && R.pcalsTip !== 'all') { R.pcalsTip = 'all'; loadPcals(true); }
    const live = x ? (x.photos || []).filter((ph) => !ph.removedAtMs) : [];
    const gone = x ? (x.photos || []).filter((ph) => ph.removedAtMs) : [];
    const flagged = !!x && (x.flags || []).includes('wrong-tip');
    const key = JSON.stringify([id, !!x, x ? [x.labelAuto, live.map((ph) => ph.id), gone.length, flagged, x.jobInMemory, x.outcome] : null]);
    const built = IL.keyed(p.body, key, () => {
      if (!x) return el('p', { class: 'panel__lead', text: R.pcalsLoading || !R.pcals ? 'Loading…' : 'No pad calibration #' + id + ' among the last 100.' });
      const lab = el('textarea', { class: 'input rec-label__in', id: 'rec-label', rows: '2', maxlength: '160', 'aria-label': 'Calibrated for (1 to 160 characters)' });
      lab.value = x.label || '';
      const save = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-rec-label' }, 'Save the label');
      save.addEventListener('click', () => IL.act(save, async () => {
        const text = String(lab.value).replace(/\s+/g, ' ').trim();
        if (!text || text.length > 160) throw new IL.ApiError(400, { error: 'label', reason: 'The label must be 1 to 160 characters.' }, 'The label must be 1 to 160 characters.');
        const r = await IL.api('PUT', '/api/pad-calibrations/' + x.id + '/label', { label: text });
        R.labelDirty = false;
        await loadPcals(true);
        return r;
      }, { ok: 'Label saved.' }));
      lab.addEventListener('input', () => { R.labelDirty = lab.value !== (x.label || ''); lab.classList.toggle('input--dirty', R.labelDirty); });
      lab.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); save.click(); } });
      const auto = el('button', { type: 'button', class: 'link', 'data-action': 'label-auto', hidden: x.labelAuto !== false }, 'Use the automatic label');
      auto.addEventListener('click', () => IL.act(auto, async () => { const r = await IL.api('PUT', '/api/pad-calibrations/' + x.id + '/label', { auto: true }); R.labelDirty = false; await loadPcals(true); return r; },
        { ok: 'The automatic label is back.' }));
      const ch = IL.photos.chooser({ id: 'btn-rec-upload', inputId: 'rec-file', label: 'Add a photo…', cls: 'btn btn--sm', onFile: (f) => recUpload(f, x, ch.btn) });
      R.recUploadBtn = ch.btn;
      const open = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-rec-open' }, 'Open the result', IL.icon('i-chev'));
      open.addEventListener('click', () => IL.go('#records/calibrations/p' + x.id + '?panel=pad-cal&job=' + encodeURIComponent(x.jobId)));
      const wrong = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', id: 'btn-rec-wrongtip' }, 'Made with another tip…');
      IL.press2(wrong, 'made with another tip', async () => {
        const r = await IL.api('POST', '/api/pad-calibrations/' + x.id + '/wrong-tip', {});
        await loadPcals(true);
        if (pcr.loadPadTypes) pcr.loadPadTypes();
        IL.planRefreshSoon();
        if (IL.tips) IL.tips.openChange({});
        return r;
      }, { ok: (r) => (r && r.text) || 'Flagged: made with another tip.' });
      const checks = Array.isArray(x.checks) ? x.checks : [];
      const ev = Array.isArray(x.evidence) ? x.evidence : [];
      return [
        el('div', { class: 'rec-ph' }, el('div', { class: 'eyebrow', text: 'Pad calibration #' + x.id + ' · ' + (x.method === 'by-hand' ? 'by hand' : 'automatic') }),
          el('h2', { class: 'rec-ph__n', text: typeWords(x.typeKey) + (isNum(x.versionTo) ? ' · v' + x.versionTo : '') }),
          el('div', { class: 'rec-ph__s' }, outcomeBadge(x), el('span', { text: IL.fmtDateTime(serverToClient(x.createdAtMs)) }))),
        x.message ? el('div', { class: 'notebox', text: x.message }) : null,
        el('div', { class: 'rec-label-ed', 'data-refusals': '', 'data-enter-submits': 'btn-rec-label' },
          el('label', { class: 'field__l', for: 'rec-label' }, el('span', { text: OUTCOME_KIND[x.outcome] === 'ok' ? 'Calibrated for' : 'Calibration for' }), el('span', { class: 'unit', text: '1 to 160 characters' })),
          lab, el('div', { class: 'btn-row' }, save, auto)),
        el('dl', { class: 'kv kv--wrap kv--cols' }, [
          ['Tip', (x.setupName || x.setupKey || '—')], ['Pad type', x.typeKey + ' · ' + (isNum(x.versionFrom) ? 'v' + x.versionFrom + ' → ' : '') + (isNum(x.versionTo) ? 'v' + x.versionTo : 'no new version')],
          ['Dose', isNum(x.steps) ? (x.dotsPerPad || '—') + ' × ' + IL.turnLabel(x.steps) + (isNum(x.massPerPadMg) ? ' · ' + IL.fmt(x.massPerPadMg, 2) + ' mg per pad' : '') : '—'],
          ['Job', x.jobId || '—'], ['Evidence', ev.length ? IL.plural(ev.length, 'dose record') + ': ' + ev.join(', ') : '—'],
        ].flatMap(([a, b]) => [el('dt', { text: a }), el('dd', { text: IL.nb(String(b)) })])),
        checks.length ? el('ul', { class: 'pcal__checks' }, checks.map((k) => el('li', { class: k.pass === false ? 'bad' : null }, IL.glyph(k.pass === false ? 'block' : 'ok', 'xs'),
          el('span', { text: checkWords(k, x) })))) : null,
        IL.sectionK('Pictures', live.length || null),
        live.length ? el('div', { class: 'rec-photos' }, live.map((ph) => {
          const b = el('button', { type: 'button', class: 'photo-thumb rec-photo__b', 'data-photo': ph.id, title: (ph.caption || 'Picture') + ': open' },
            el('img', { src: ph.thumbUrl, alt: ph.caption || 'Picture ' + ph.id, loading: 'lazy' }));
          b.addEventListener('click', () => IL.photos.view(ph, { list: live, from: b, onChange: () => loadPcals(true) }));
          return el('figure', { class: 'rec-photo' }, b, el('figcaption', { text: (ph.caption || 'Picture') + ' · ' + IL.fmtDateTime(serverToClient(ph.atMs)) }));
        })) : el('p', { class: 'muted', id: 'rec-photos-none', text: 'No picture with this calibration yet.' }),
        gone.length ? el('p', { class: 'panel__lead rec-photos-gone', text: gone.map((ph) => '1 picture removed ' + IL.fmtDateTime(serverToClient(ph.removedAtMs)) + (ph.removedWhy ? ' (' + ph.removedWhy + ')' : '')).join(' · ') }) : null,
        el('div', { class: 'rec-upload', 'data-refusals': '' }, el('div', { class: 'btn-row' }, ch.btn, ch.input), el('div', { class: 'reason', id: 'rec-upload-why', hidden: true }),
          IL.photos.dropZone({ id: 'rec-drop', onFile: (f) => recUpload(f, x, null) })),
        el('div', { class: 'btn-row rec-pcal-acts', 'data-refusals': '' }, x.jobInMemory ? open : null, flagged ? null : wrong),
        x.jobInMemory ? null : el('p', { class: 'panel__lead', text: 'Its result card is no longer in OpenPnP’s memory (it keeps the last 5 calibrations since its start).' })];
    });
    if (x && !built) {
      const lab = $('rec-label');
      if (lab && document.activeElement !== lab && !R.labelDirty && lab.value !== (x.label || '')) lab.value = x.label || '';
    }
    if (R.recUploadBtn) IL.gate(R.recUploadBtn, IL.tips ? IL.tips.chooserLock(s) : null, $('rec-upload-why'));
  }
  /** A picture for a calibration from Records: uploaded as the record's tip's (kind tip), linked to it. */
  function recUpload(file, x, btn) {
    return IL.act(btn, async () => {
      const ph = await IL.photos.send(file, { kind: 'tip', setup: x.setupKey, calibrations: [x.id], caption: 'Picture of the ' + (x.setupName || 'tip') + ' (uploaded)' });
      await loadPcals(true);
      return ph;
    }, { ok: 'Picture added to calibration #' + x.id + '.', refusalIn: document.querySelector('#panel .rec-upload') });
  }
  function touchDetail(p, s, id) {
    const d = pcr.calibrations && pcr.calibrations();
    const t = d && (d.touchOffs || []).find((x) => String(x.id) === String(id));
    IL.keyed(p.body, JSON.stringify([id, !!d, t ? t.id : null]), () => {
      if (!d) return el('p', { class: 'panel__lead', text: 'Loading…' });
      if (!t) return el('div', { class: 'notebox notebox--need', text: 'No touch-off #' + id + ' among the last 20.' });
      const v = pcr.touchOffView(t);
      const a = v.analysis;
      return [el('div', { class: 'rec-ph' }, el('div', { class: 'eyebrow', text: 'Needle offset change #' + t.id }), el('h2', { class: 'rec-ph__n', text: v.method }),
        el('div', { class: 'rec-ph__s', text: IL.fmtDateTime(serverToClient(t.created_at)) })),
      el('div', { class: 'minis' }, mini('Offset', IL.fmtSigned(t.z_offset_mm, 3), 'mm'), mini('Change', isNum(v.delta) ? IL.fmtSigned(v.delta, 3) : '—', 'from ' + (isNum(t.previous_offset_mm) ? IL.fmtSigned(t.previous_offset_mm, 3) : '—') + ' mm')),
      a ? [IL.sectionK('Gaps and dots'), el('table', { class: 'tbl rec-mini-t' }, el('thead', {}, el('tr', {}, ['Gap mm', 'Dot Ø mm'].map((h) => el('th', { class: 'r' }, IL.unitText(h))))),
        el('tbody', {}, (a.gapsMm || []).map((g, i) => {
          const dot = pcr.touchOffDot(a, i);   // "not measured" (its note as the tooltip), "no dot" only for no paste
          return el('tr', { class: isNum(a.contactGapMm) && Math.abs(g - a.contactGapMm) < 1e-9 ? 'is-contact' : null },
            el('td', { class: 'r', text: IL.fmtSigned(g, 3) }), el('td', { class: 'r', text: dot.text, title: dot.note }));
        }))),
      el('p', { class: 'panel__lead', text: isNum(a.contactGapMm) ? 'Contact at a gap of ' + IL.fmtSigned(a.contactGapMm, 3) + ' mm.' + (a.reason ? ' ' + a.reason : '') : (a.reason || 'No contact found.') })] : null,
      v.note ? el('p', { class: 'panel__lead rec-note', text: v.note }) : null,
      v.det && v.det.blank ? el('p', { class: 'panel__lead', text: 'On “' + v.det.blank + '”' + (isNum(v.det.blankPlaneMaxResidualMm) ? ', surface worst residual ' + IL.fmt(v.det.blankPlaneMaxResidualMm, 3) + ' mm' : '') + '.' }) : null];
    });
  }
  function summary(p, s) {
    const all = runs();
    const boards = all.filter((r) => r.kind === 'board');
    const today = boards.filter((r) => isToday(serverToClient(r.started_at)));
    const dots = today.reduce((a, r) => a + (r.dots_done || 0), 0);
    const last = boards[0];
    const best = today.filter((r) => isNum(r.dph_motion)).sort((a, b) => b.dph_motion - a.dph_motion)[0];
    IL.keyed(p.body, JSON.stringify([all.length, today.length, dots, last ? [last.id, last.status] : null, R.tab, (R.planList || []).length]), () => [
      el('div', { class: 'panel__title' }, el('h2', { text: 'Today' })),
      el('div', { class: 'minis' }, mini('Board runs', String(today.length), today.length ? IL.plural(dots, 'dot') : 'none yet'),
        mini('Best DPH', best ? IL.fmtInt(best.dph_motion) : '—', best ? 'dots only · overall ' + IL.fmtInt(best.dph_overall) : 'dots only')),
      last ? el('div', {}, IL.sectionK('Last board'), el('button', { type: 'button', class: 'rec-last', onclick: () => IL.go('#records/boards/' + last.run_uuid) },
        statusCell(last.status), el('div', { class: 'rec-last__t' }, el('b', { text: last.board || 'Board' }), el('span', { text: outcome(last) + ' · ' + IL.fmtDateTime(serverToClient(last.started_at)) })), IL.icon('i-chev'))) : null,
      el('p', { class: 'panel__lead', text: { boards: 'Choose a run to see its timings, the done-dot map and, for a stopped board, how to finish it.', activity: 'OpenPnP’s dispense log and what this page did, newest first.', calibrations: 'Choose a pad calibration to see what it was calibrated for, its pictures and checks.', 'touch-offs': 'Choose a row to see the gaps and dots of a touch-off.' }[R.tab] }),
    ]);
  }
  function renderPanel(p, s, first) {
    if (first) { p.body.replaceChildren(); delete p.body.dataset.key; }
    if (!R.sel) { summary(p, s); return; }
    if (R.tab === 'boards') {
      const r = runs().find((x) => x.run_uuid === R.sel);
      if (!r) { IL.keyed(p.body, 'missing' + R.sel + (S.runs ? 1 : 0), () => el('div', { class: 'notebox notebox--need', text: S.runs ? 'This run is not among the last 50.' : 'Loading…' })); return; }
      runDetail(p, s, r);
    } else if (R.tab === 'calibrations') { if (/^p\d+$/.test(R.sel)) pcalDetail(p, s, R.sel.slice(1)); else calDetail(p, s, R.sel); }
    else if (R.tab === 'touch-offs') touchDetail(p, s, R.sel);
    else summary(p, s);
  }

  // ================================================================================== workspace
  function setTab(tab, sel) {
    const changed = tab !== R.tab || (sel || null) !== R.sel;
    R.tab = tab;
    R.sel = sel || null;
    for (const b of $('records-seg').children) IL.setAttr(b, 'aria-pressed', String(b.dataset.tab === tab));
    for (const sec of document.querySelectorAll('#ws-records [data-tab-panel]')) sec.hidden = sec.dataset.tabPanel !== tab;
    if (tab === 'activity' && IL.active() === 'records') { if (!R.evTimer) startEvents(); } else stopEvents();
    if (R.escOff) { R.escOff(); R.escOff = null; }
    if (R.sel) R.escOff = IL.onEsc(() => { if (IL.panel.isPushed() || IL.active() !== 'records' || !R.sel) return false; IL.go('#records/' + R.tab); return true; });
    if (changed && IL.active() === 'records') IL.panel.reset();
  }
  IL.workspace('records', {
    defaultView: 'record',
    mount(root) {
      const seg = el('div', { class: 'seg rec-seg', role: 'group', 'aria-label': 'Records', id: 'records-seg' },
        TABS.map(([k, t]) => el('button', { type: 'button', 'data-tab': k, 'aria-pressed': String(k === 'boards') }, el('span', { text: t }), el('span', { class: 'rec-seg__n', id: 'rec-n-' + k }))));
      for (const b of seg.children) b.addEventListener('click', () => IL.go('#records/' + b.dataset.tab));
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Records · history' }),
          el('h1', {}, 'Records', el('span', { class: 'h1-sub', text: 'from OpenPnP’s dispense database' })),
          el('div', { class: 'meta' }, el('span', { text: 'Runs and events are kept by OpenPnP; dot timings for the last run only. Nothing here moves the machine.' })))),
        seg, boardsTab(), activityTab(), calsTab(), touchTab());
      pcr.stickyTop(root);
    },
    show() {
      IL.loadRuns();
      loadPcals();
      if (pcr.loadCalibrations) pcr.loadCalibrations();
      if (R.tab === 'activity') startEvents();
    },
    hide() { stopEvents(); },
    route(r) {
      const tab = TABS.some(([k]) => k === r.section) ? r.section : 'boards';
      setTab(tab, r.id);
      if (r.id) {
        requestAnimationFrame(() => {
          const row = document.querySelector('#ws-records [data-tab-panel="' + tab + '"] tr[aria-selected="true"]');
          if (row) row.scrollIntoView({ block: 'nearest' });
        });
      }
    },
    render(s) {
      const all = runs(), d = pcr.calibrations && pcr.calibrations();
      IL.setText($('rec-n-boards'), S.runs ? String(all.filter((r) => r.kind === 'board').length) : '');
      IL.setText($('rec-n-activity'), '');
      IL.setText($('rec-n-calibrations'), R.pcals && R.pcalsFor === 'all' ? String(R.pcals.length) : '');
      IL.setText($('rec-n-touch-offs'), d ? String((d.touchOffs || []).length) : '');
      if (R.tab === 'boards') renderRuns();
      else if (R.tab === 'activity') renderEvents();
      else if (R.tab === 'calibrations') { renderPcals(); renderCals(); }
      else renderTouchOffs();
    },
    panel(p, s, first) { renderPanel(p, s, first); },
    nav(s) {
      const boards = runs().filter((r) => r.kind === 'board');
      const last = boards[0];
      if (last && (last.status === 'ABORTED' || last.status === 'ERROR') && isToday(serverToClient(last.started_at))) {
        return { sub: 'Last board ' + (last.status === 'ERROR' ? 'ended by an error' : 'stopped'), kind: 'block' };
      }
      // runs, not boards: a test, a stopped run and its finish are three runs on one board
      const n = boards.filter((r) => isToday(serverToClient(r.started_at))).length;
      return { sub: n ? IL.plural(n, 'board run') + ' today' : S.runs ? 'No board runs today' : IL.loadError('runs') ? 'Runs not loaded' : '' };
    },
  });
  IL.on('taskend', () => { R.last = null; });
})();
