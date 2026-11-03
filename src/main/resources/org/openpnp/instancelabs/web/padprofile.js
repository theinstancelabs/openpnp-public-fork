/* padprofile.js: pads that need a paste profile, their suggestions and the pad calibration job in the web UI
   (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §13; API §12). Loaded after boardimport.js and before the workspace
   modules; every hook in another module's file is a no-op without it (§13.9).

   What it shows:
   - Run readiness item 5 "Pad types" from plan.profileCheck (padTypesItem, hook H-P1 in core.js).
   - Profile › "Needs a profile" (#pt-needs: fixable groups, the tip groups and the notes collapsed), the suggestion card
     in Profile's side panel (#profile/needs/<g>), the pad-type editor fields (#pt-dotsteps, #pt-dotsperpad,
     #pt-calinfo) and "Calibrate automatically…" next to Set up / Validate (profileHooks, H-P3 in profile.js).
   - The pad-cal panel view (?panel=pad-cal): before start, running, the readings prompt, the result.
   - The upload preview's profile column and, after the save, its "Use" and "Needs a profile…" actions (H-P6), the pad
     inspector's one notebox for a pad that needs a profile and its planned amount (H-P7), the strip and lock words of
     the job (H-P2).

   API (IL.padProfile):
     groups(plan) → {fix, tip, notes}         padTypesItem(plan, d, viol) → readiness item | null (pure)
     setSource({planId} | {boardFile, boardName})   current() → {kind, check, label, planId?, boardFile?} | null
     mountNeeds(host)  profileHooks(pt)  importRows(table, preview)  importSaved(preview, {leaveTo}) → node | null
     inspectorBlock(host, pad, plan) → shown?  amountText(pad)  openCal(request)  taskParts(run)  verdict(status)
     jobLock(status)  jobActive(status)  needsHref()
   Fix-its: profile-use (arg: group id, plans again), profile-needs, pcal-open, pcal-area, pcal-wiped (two-press).
   sessionStorage: il.pp.source (a board file as the source), il.pcal.req (the calibration before start), il.pcal.job
   (the job this tab started), il.pcal.loaded (job ids whose new plan this tab loaded). Styles: padprofile.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const { isNum } = IL;
  const FIX = new Set(['no-pad-size', 'no-type', 'below-gate', 'bridge']);
  const TIPS = new Set(['tip', 'part']);
  const SRC_KEY = 'il.pp.source', REQ_KEY = 'il.pcal.req', JOB_KEY = 'il.pcal.job', LOADED_KEY = 'il.pcal.loaded';
  const STATUS_WORD = { 'not-started': 'not started', provisional: 'provisional', characterized: 'characterized', validated: 'validated on a board' };
  const TURN = { 400: ['quarter turn', 'quarter turns', 'quarter-turn'], 800: ['half turn', 'half turns', 'half-turn'], 1600: ['full turn', 'full turns', 'one-turn'] };
  const ACTIVE = new Set(['running', 'awaiting-reading', 'deciding', 'planning']);
  const LOCKING = new Set(['running', 'deciding', 'planning']);
  const P = {
    src: IL.ssJson(SRC_KEY, null),   // {boardFile, boardName, atMs}: a board file instead of the plan shown
    board: null,                     // {file, check, error, loading, stale}
    sel: null,                       // group of the suggestion card (#profile/needs/<g>)
    panelSel: undefined,
    est: {},                         // calibration estimates by key: {s, loading, at}
    art: {},                         // board drawings by id (board-file previews)
    pkgs: null, pkgsLoading: null,   // OpenPnP packages (board-file previews)
    boards: null,                    // GET /api/boards (board-file previews)
    pt: null,                        // profile.js's hook object
  };

  // ================================================================================== words
  const base = (id) => String(id || '').replace(/~\d+$/, '');
  const shortType = (name) => String(name || '').replace(/\s*\([^)]*\)\s*/g, ' ').replace(/\s+/g, ' ').trim();
  /** "Chip 0603": the type's family (without its note) and name, as Profile and the upload preview name it. */
  const typeLabel = (s) => {
    if (!s) return '';
    const fam = shortType(s.family);
    return (/^\S+$/.test(fam) && !/^custom$/i.test(fam) && !String(s.name || '').includes(' · ') ? fam + ' ' : '') + (s.name || s.typeKey || '');
  };
  const pkgOf = (g) => base((g.packageIds || [])[0]);
  const turnWords = (n, steps) => n + ' ' + (TURN[steps] ? TURN[steps][n === 1 ? 0 : 1] : IL.turnLabel(steps));
  const turnAdj = (steps) => (TURN[steps] ? TURN[steps][2] : IL.turnLabel(steps));
  const mg = (v) => (isNum(v) ? IL.fmt(v, 2) + ' mg' : '—');
  const mm = (v) => (isNum(v) ? IL.fmt(v, 2) + ' mm' : '—');
  /** An opening as the engine's texts write it: mm to 2 decimals, half up. */
  const pair2 = (w, h) => (isNum(w) && isNum(h) ? f2(w) + ' × ' + f2(h) : '—');
  const refList = (refs, n) => (refs || []).slice(0, n).join(', ') + ((refs || []).length > n ? ' +' + ((refs || []).length - n) : '');
  /** 2 decimals, half up (0.825 → 0.83, as the engine's texts round). */
  const f2 = (v) => IL.fmt(Math.round(v * 100 + 1e-6) / 100, 2);
  const dRange = (d) => (!d ? '—' : Math.abs(d[0] - d[1]) < 0.005 ? f2(d[1]) + ' mm' : f2(d[0]) + '–' + f2(d[1]) + ' mm');
  const reasonKind = (g) => (g.reason === 'outside' ? 'info' : TIPS.has(g.reason) ? 'block' : 'need');
  /** The suggestion in a few words: "Use Chip 0603", "New: 2 quarter turns per pad, ≈ 1.06 mg". */
  function suggShort(g) {
    const s = g.suggestion;
    if (!s || s.kind === 'none') return TIPS.has(g.reason) ? 'none: not dispensable' : '—';
    if (s.kind === 'use') return 'Use ' + typeLabel(s);
    const d = s.dots || {};
    const what = turnWords(d.perPad, d.steps) + ' per pad, ≈ ' + mg(s.predicted && s.predicted.mgPerPad);
    return s.kind === 'new' ? 'New: ' + what : 'Calibrate ' + typeLabel(s) + ': ' + what;
  }
  const calibrates = (g) => !!(g.suggestion && (g.suggestion.kind === 'new' || g.suggestion.kind === 'calibrate-existing'));
  const usable = (g) => !!(g.suggestion && g.suggestion.kind === 'use');

  // ================================================================================== source and check
  /** The groups of a check: fix (Profile can fix them), tip (the tip, or parts treated whole), notes (outside). */
  function split(pc) {
    const out = { fix: [], tip: [], notes: [] };
    for (const g of (pc && pc.groups) || []) {
      if (g.reason === 'outside') out.notes.push(g);
      else if (TIPS.has(g.reason)) out.tip.push(g);
      else if (FIX.has(g.reason)) out.fix.push(g);
    }
    return out;
  }
  /** The board-file source while no plan of that file is shown (a newer plan of another board wins, see the 'plan' event). */
  function boardSrc() {
    const s = P.src;
    if (!s || !s.boardFile) return null;
    const p = S.plan;
    if (p && p.source && p.source.file === s.boardFile) return null;
    return s;
  }
  /** What the Needs list and the suggestion card show: {kind: 'plan' | 'board', check, label, planId | boardFile}. */
  function current() {
    const b = boardSrc();
    if (b) {
      if (!P.board || P.board.file !== b.boardFile || P.board.stale) loadBoardCheck(b.boardFile);
      const c = P.board && P.board.file === b.boardFile ? P.board.check : null;
      return { kind: 'board', check: c, boardFile: b.boardFile, boardName: b.boardName || base(b.boardFile.split('/').pop()),
        error: P.board && P.board.error, label: 'From the board ' + (b.boardName || b.boardFile.split('/').pop()) + ' (not planned yet)' };
    }
    const p = S.plan;
    if (!p || !p.profileCheck) return null;
    return { kind: 'plan', check: p.profileCheck, planId: p.planId,
      label: 'From the plan of ' + (p.boardName || 'the board') + (p.createdAtMs ? ' (' + IL.fmtTime(IL.toClient(p.createdAtMs)) + ')' : '') };
  }
  function srcBody(c) { return c.kind === 'plan' ? { planId: c.planId } : { boardFile: c.boardFile }; }
  async function loadBoardCheck(file) {
    if (P.board && P.board.file === file && P.board.loading) return;
    P.board = Object.assign(P.board && P.board.file === file ? P.board : { file, check: null }, { loading: true, stale: false });
    try {
      P.board.check = await IL.api('GET', '/api/pad-profile/check?boardFile=' + encodeURIComponent(file), undefined, { timeoutMs: 30000 });
      P.board.error = null;
    } catch (e) {
      P.board.error = IL.errorText(e);
      if (e.status === 404 || e.status === 409) { P.src = null; IL.ssSet(SRC_KEY, null); }
    } finally {
      if (P.board) P.board.loading = false;
      IL.render();
    }
  }
  /** Shows the Needs list of a board file (after an upload) or of the plan ({planId}, or null). */
  function setSource(src) {
    P.src = src && src.boardFile ? { boardFile: src.boardFile, boardName: src.boardName || null, atMs: Date.now() } : null;
    IL.ssSet(SRC_KEY, P.src);
    if (P.src) P.board = null;
    IL.render();
  }
  /** Something the check depends on changed: plan again (the plan's check follows) or read the board file's again. */
  function refresh() {
    if (P.board) P.board.stale = true;
    staleEstimates();
    if (S.plan) IL.planRefreshSoon();
    IL.render();
  }
  IL.on('plan', (plan, prev) => {
    // a plan made after the board file was chosen (of any board) is the newer context
    if (P.src && plan && (!prev || prev.planId !== plan.planId) && plan.createdAtMs && IL.toClient(plan.createdAtMs) > P.src.atMs) setSource(null);
    if (!prev || !plan || prev.planId !== plan.planId) staleEstimates();
  });
  IL.on('padtypes', () => { if (P.board) P.board.stale = true; staleEstimates(); });
  /** Read the summaries again, keeping the last answers meanwhile (a Start button never flickers to "Reading…"). */
  function staleEstimates() { for (const e of Object.values(P.est)) e.stale = true; }

  // ================================================================================== actions
  /**
   * POST /api/pad-profile/apply: Use (the mapping) or save (the provisional type); a plan source is planned again and
   * shown. Returns the answer.
   */
  async function apply(c, groups, choice) {
    const ids = {};
    for (const g of groups) ids[g.group] = g.suggestion.id;
    const r = await IL.api('POST', '/api/pad-profile/apply', Object.assign(srcBody(c), { groups: groups.map((g) => g.group), choice, suggestionIds: ids }),
      { timeoutMs: 60000 });
    if (r.newPlanId) await IL.loadPlan(r.newPlanId);
    else if (r.planStale) IL.planRefreshSoon();
    if (IL.pcr && IL.pcr.loadPadTypes) IL.pcr.loadPadTypes();
    refresh();
    return r;
  }
  const findGroup = (c, id) => ((c && c.check && c.check.groups) || []).find((g) => g.group === id) || null;
  function useText(r, groups) {
    const a = (r.applied || [])[0];
    const who = groups.length === 1 ? pkgOf(groups[0]) : IL.plural(groups.length, 'package');
    return 'Mapped ' + who + (a && groups.length === 1 ? ' to ' + typeLabel(groups[0].suggestion) : '') + (r.newPlanId ? ': planned again.' : '.')
      + ((r.skipped || []).length ? ' ' + IL.plural(r.skipped.length, 'group') + ' skipped: ' + r.skipped.map((x) => x.why).join('; ') + '.' : '');
  }
  function useOne(btn, c, g) {
    return IL.act(btn, () => apply(c, [g], 'use'), { ok: (r) => useText(r, [g]) });
  }
  /** "I'll do it by hand": save the suggestion as a provisional type (and its mapping), then the Calibrate guide. */
  function manual(btn, c, g) {
    return IL.act(btn, async () => {
      const r = await apply(c, [g], 'save');
      const a = (r.applied || []).find((x) => x.group === g.group);
      const t = (a && a.padType) || { typeKey: a ? a.typeKey : g.suggestion.typeKey, name: g.suggestion.name, family: g.suggestion.family };
      if (IL.pcr && IL.pcr.openGuide) IL.pcr.openGuide(t, { validate: false, suggestion: g.suggestion, group: g.group, src: srcBody(c) });
      IL.go('#calibrate');
      return { t, r };
    }, { ok: (x) => 'Saved ' + (x.t.name || x.t.typeKey) + ' as provisional: the steps are on Calibrate.' });
  }

  // ================================================================================== readiness item 5 (pure)
  /**
   * Run readiness item 5 from plan.profileCheck (§13.2), or null when the check has nothing to say (core's own item
   * then). Pure: reads only its arguments.
   */
  function padTypesItem(plan, d, viol) {
    const pc = plan && plan.profileCheck;
    if (!pc) return null;
    const pads = plan.pads || [];
    const sp = split(pc);
    const fix = sp.fix.filter((g) => g.blocking);
    const tip = sp.tip.filter((g) => g.blocking);
    const it = { key: 'padtypes', code: 'PLAN', title: 'Pad types' };
    const blockedN = pads.filter((p) => p.blocked).length;
    /** "SOT-23 (no pad type, 3 pads)": one entry per package and reason. */
    const list = (gs) => {
      const m = new Map();
      for (const g of gs) {
        const k = pkgOf(g) + '|' + (TIPS.has(g.reason) ? '' : g.short);
        const x = m.get(k) || m.set(k, { pkg: pkgOf(g), short: TIPS.has(g.reason) ? null : g.short, pads: 0 }).get(k);
        x.pads += g.pads;
      }
      return Array.from(m.values()).map((x) => x.pkg + ' (' + (x.short ? x.short + ', ' : '') + IL.plural(x.pads, 'pad') + ')').join(', ');
    };
    const tipName = (pc.tip && pc.tip.name) || 'tip';
    // Refuse flagged pads off (Setup › Settings, status.dispense.refuseFlaggedPads): the engine skips the pads it cannot
    // dispense and runs the rest, so the item is a note that counts ready and names them (as the run confirm does)
    if (d && d.refuseFlaggedPads === false && blockedN && (fix.length || tip.length)) {
      const named = [fix.length ? list(fix) : '', tip.length ? list(tip) + ' not with the ' + tipName : ''].filter(Boolean).join('; ');
      return Object.assign(it, { state: 'info', value: (pads.length - blockedN) + '/' + pads.length + ' · ' + blockedN + ' skipped', short: 'pad types',
        chip: IL.plural(blockedN, 'pad') + ' skipped',
        detail: IL.plural(blockedN, 'pad') + ' skipped (Refuse flagged pads is off): ' + named + '. The other ' + (pads.length - blockedN) + ' are dispensed.',
        fixes: [{ href: '#setup/settings/refuse-flagged', label: 'Refuse flagged pads' }, { href: '#profile/needs', label: fix.length ? 'Needs a profile' : 'Why' }] });
    }
    if (fix.length) {
      const fixes = [];
      for (const g of fix.slice(0, 3)) {
        fixes.push(usable(g) ? { fix: 'profile-use', arg: g.group, label: 'Use ' + typeLabel(g.suggestion) + ' for ' + pkgOf(g) + ' · plans again' }
          : { href: '#profile/needs/' + g.group, label: 'Needs a profile: ' + pkgOf(g) });
      }
      if (fix.length > 1) fixes.push({ href: '#profile/needs', label: 'All ' + fix.length + ' in Profile › Needs a profile' });
      if (fix.some((g) => g.reason === 'below-gate')) fixes.push({ href: '#setup/settings/min-status', label: 'Lowest pad-type status' });
      const tipN = tip.reduce((a, g) => a + g.pads, 0), fixN = fix.reduce((a, g) => a + g.pads, 0);
      const names = (gs) => Array.from(new Set(gs.map(pkgOf))).join(', ');
      // the fixes below name the packages that need a profile (up to 3, then all of them): the sentence counts them
      return Object.assign(it, { state: 'block', value: (pads.length - blockedN) + '/' + pads.length, short: 'needs a profile',
        chip: IL.plural(blockedN, 'pad') + ' cannot be dispensed',
        detail: IL.plural(blockedN, 'pad') + ' cannot be dispensed: ' + fixN + ' ' + (fixN === 1 ? 'needs' : 'need') + ' a profile'
          + (fix.length > 3 ? ' (' + names(fix) + ')' : '') + (tipN ? ', ' + tipN + ' not with the ' + tipName + ' (' + names(tip) + ')' : '') + '.', fixes });
    }
    if (tip.length) {
      const n = tip.reduce((a, g) => a + g.pads, 0);
      return Object.assign(it, { state: 'block', value: (pads.length - blockedN) + '/' + pads.length, short: 'the ' + tipName,
        chip: IL.plural(n, 'pad') + ' cannot be dispensed',
        detail: IL.plural(n, 'pad') + ' cannot be dispensed with the ' + tipName + ': ' + list(tip) + '.',
        fixes: [{ href: '#setup/settings/refuse-flagged', label: 'Dispense the rest (Setup › Settings › Refuse flagged pads)' }, { href: '#profile/needs', label: 'Why' }] });
    }
    if (sp.notes.length && !blockedN) {
      return Object.assign(it, { state: 'info', value: pads.length + '/' + pads.length + ' · ' + IL.plural(sp.notes.length, 'note'), short: 'pad types',
        detail: pads.length + ' of ' + pads.length + ' pads have a pad type at or above ' + (d.minPadTypeStatus || 'the gate') + '. '
          + IL.plural(sp.notes.length, 'note') + ': ' + sp.notes.map((g) => pkgOf(g) + ' (' + g.short + ')').join(', ') + '.',
        fixes: [{ href: '#profile/needs', label: 'Notes in Profile › Needs a profile' }] });
    }
    return null;
  }
  const needsHref = () => { const c = current(); return c && c.check && (c.check.groups || []).length ? '#profile/needs' : null; };

  // ================================================================================== fix-its
  const idle = (s) => IL.lockReason(s, {}) || jobLockText(s);
  /** A calibration starts only when no other one is active (one waiting for its readings included). */
  const calIdle = (s) => idle(s) || (jobActive(s) ? 'A pad calibration is waiting for its readings: finish or cancel it first' : null);
  IL.fix('profile-use', {
    gate: idle,
    run: async (btn, arg) => {
      const c = current();
      const g = findGroup(c, arg);
      if (!g || !usable(g)) throw new Error('This suggestion is no longer current: look at Profile › Needs a profile.');
      return apply(c, [g], 'use');
    },
    ok: (r) => 'Mapped' + ((r && r.applied && r.applied[0]) ? ' ' + r.applied[0].mapping.packageId + ' to ' + r.applied[0].typeKey : '') + (r && r.newPlanId ? ': planned again.' : '.'),
  });
  IL.fix('profile-needs', { run: async (btn, arg) => IL.go('#profile/needs' + (arg ? '/' + arg : '')) });
  IL.fix('pcal-open', { run: async () => IL.openView('pad-cal', { keep: true }) });
  IL.fix('pcal-area', { run: async () => IL.go('#calibrate/padcal') });
  IL.fix('pcal-wiped', {
    press2: 'blank wiped', gate: idle,
    run: () => IL.api('POST', '/api/calibration-area/reset', {}),
    ok: (a) => 'The calibration area is free again: ' + IL.fmt(a.leftMm, 1) + ' mm left.',
  });

  // ================================================================================== calibration estimates
  /** The query of GET /api/pad-calibrations/preview for a request (the browser's attestations in server time). */
  function previewQuery(req) {
    const q = new URLSearchParams(requestQuery(req));
    const reg = IL.attest.valid('regulator', S.status);
    if (reg) { q.set('regulatorPsi', String(reg.value)); q.set('regulatorAtMs', String(Math.round(reg.atMs + S.skewMs))); }
    const life = IL.attest.valid('paste-life', S.status);
    if (life) q.set('pasteLifeAtMs', String(Math.round(life.atMs + S.skewMs)));
    return q.toString();
  }
  /** The request's own fields: the cache key of its summary (the clock skew moves the attestations' server times). */
  function requestQuery(req) {
    const q = new URLSearchParams();
    if (req.planId) q.set('planId', req.planId);
    if (req.boardFile) q.set('boardFile', req.boardFile);
    if (req.groups) q.set('groups', Array.isArray(req.groups) ? req.groups.join(',') : req.groups);
    if (req.typeKeys) q.set('typeKeys', req.typeKeys.join(','));
    if (req.steps) q.set('steps', req.steps.join(','));
    if (isNum(req.doseSteps)) q.set('doseSteps', String(req.doseSteps));
    if (isNum(req.weighDots)) q.set('weighDots', String(req.weighDots));
    if (req.suggestionIds) q.set('suggestionIds', Object.entries(req.suggestionIds).map(([g, s]) => g + ':' + s).join(','));
    return q.toString();
  }
  /** Which ticks the summary was read with (browser times): a new tick reads it again. */
  function attestKey() {
    const reg = IL.attest.valid('regulator', S.status), life = IL.attest.valid('paste-life', S.status);
    return JSON.stringify([reg && reg.value, reg && reg.atMs, life && life.atMs]);
  }
  /**
   * The before-start summary of a request: {s, error, loading}. Cached by the request; read again after 10 s, after a
   * new tick, when stale (a new plan, pad types) or when asked, keeping the last answer meanwhile, so a Start button
   * armed for its second press is not locked by a refresh.
   */
  function estimate(req, force) {
    const key = requestQuery(req);
    const att = attestKey();
    const e = P.est[key];
    if (!e || force || (!e.loading && (e.stale || e.att !== att || Date.now() - e.at > 10000))) {
      const x = P.est[key] = Object.assign(e || {}, { loading: true, at: Date.now(), att, stale: false });
      IL.api('GET', '/api/pad-calibrations/preview?' + previewQuery(req), undefined, { timeoutMs: 20000 })
        .then((s) => { x.s = s; x.error = null; }, (err) => { x.error = IL.errorText(err); })
        .then(() => { x.loading = false; x.at = Date.now(); IL.render(); });
    }
    return P.est[key];
  }
  /** " · ≈ 3:14", or nothing while the summary is refused (it then estimates nothing: never "≈ 0:00"). */
  const estText = (e) => (e && e.s && isNum(e.s.estimatedSeconds) && e.s.estimatedSeconds > 0 ? ' · ≈ ' + IL.fmtClock(e.s.estimatedSeconds) : '');
  /** A calibration request for groups of the current source: {planId | boardFile, groups, suggestionIds}. */
  function calRequest(c, groups) {
    const ids = {};
    for (const g of groups) ids[g.group] = g.suggestion.id;
    return Object.assign(srcBody(c), { groups: groups.map((g) => g.group), suggestionIds: ids });
  }

  // ================================================================================== Profile › Needs a profile
  function mountNeeds(host) {
    const useAll = el('button', { type: 'button', class: 'btn', id: 'btn-needs-use-all' });
    const autoAll = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-needs-auto-all' });
    useAll.addEventListener('click', () => {
      const c = current();
      const gs = split(c && c.check).fix.filter(usable);
      if (gs.length) IL.act(useAll, () => apply(c, gs, 'use'), { ok: (r) => useText(r, gs) });
    });
    autoAll.addEventListener('click', () => {
      const c = current();
      const gs = split(c && c.check).fix.filter(calibrates);
      if (gs.length) openCal(calRequest(c, gs));
    });
    const sec = el('section', { class: 'card pt-needs', id: 'pt-needs', hidden: true, 'data-refusals': '' },
      el('div', { class: 'card__head' },
        el('div', {}, el('h2', { class: 'card__title' }, 'Needs a profile', el('span', { class: 'count', id: 'pt-needs-count' })),
          el('div', { class: 'card__sub', id: 'pt-needs-src' })),
        el('span', { class: 'card__state', id: 'pt-needs-state' })),
      el('div', { class: 'card__body' },
        el('p', { class: 'pt-needs__lead', id: 'pt-needs-lead' }),
        el('div', { class: 'pt-needs__wrap' }, el('table', { class: 'tbl pt-needs__t', id: 'pt-needs-table' },
          el('thead', {}, el('tr', {}, ['Package', 'Why', 'Suggestion'].map((h) => el('th', { scope: 'col', text: h })))),
          el('tbody'))),
        el('details', { class: 'disclose pt-needs__more', id: 'pt-needs-tip' }, el('summary', {}, IL.glyph('info', 'xs'), el('span', { id: 'pt-needs-tip-s' })),
          el('div', { class: 'pt-needs__wrap' }, el('table', { class: 'tbl pt-needs__t pt-needs__t--tip' }, el('tbody')))),
        el('details', { class: 'disclose pt-needs__more', id: 'pt-needs-notes' }, el('summary', { id: 'pt-needs-notes-s' }),
          el('div', { class: 'pt-needs__wrap' }, el('table', { class: 'tbl pt-needs__t' }, el('tbody'))))),
      el('div', { class: 'card__foot pt-needs__foot' }, useAll, autoAll, el('div', { class: 'reason', id: 'needs-why', hidden: true })));
    host.appendChild(sec);
    return sec;
  }
  /** A row of the Needs list; lead: its action is the list's one primary (a single row; else the foot's "all" is). */
  function needsRow(c, g, kind, lead) {
    const s = g.suggestion || {};
    const main = 'btn btn--sm' + (lead ? ' btn--primary' : '');
    const open = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', 'data-action': 'open', 'data-focus-key': 'open:' + g.group }, 'Suggestion…');
    open.addEventListener('click', () => IL.go('#profile/needs/' + g.group));
    const acts = [];
    if (kind === 'fix' && usable(g)) {
      const use = el('button', { type: 'button', class: main, 'data-action': 'use', 'data-focus-key': 'use:' + g.group },
        'Use ' + typeLabel(s) + (c.kind === 'plan' ? ' · plans again' : ''));
      use.addEventListener('click', () => useOne(use, c, g));
      acts.push(use);
    }
    if (kind === 'fix' && calibrates(g)) {
      const auto = el('button', { type: 'button', class: main, 'data-action': 'auto', 'data-focus-key': 'auto:' + g.group }, 'Calibrate automatically…');
      auto.addEventListener('click', () => openCal(calRequest(c, [g])));
      const man = el('button', { type: 'button', class: 'btn btn--sm btn--quiet', 'data-action': 'manual', 'data-focus-key': 'manual:' + g.group }, 'I’ll do it by hand');
      man.addEventListener('click', () => manual(man, c, g));
      acts.push(auto, man);
    }
    acts.push(open);
    const ap = g.aperture || {};
    const partial = g.partial ? el('div', { class: 'pt-needs__partial' }, 'Other pads of ' + pkgOf(g) + ' cannot be dispensed with this tip: paste these only if you solder those pins by hand.') : null;
    return el('tr', { 'data-group': g.group, 'data-reason': g.reason },
      el('td', { class: 'pt-needs__pkg' }, el('div', { class: 'pt-needs__n' }, breakable(pkgOf(g))),
        el('div', { class: 'pt-needs__s', text: refList(g.refs, 3) + ' · ' + IL.plural(g.pads, 'pad'), title: (g.refs || []).join(', ') }),
        el('div', { class: 'pt-needs__s', text: IL.nb(pair2(ap.wMm, ap.hMm) + ' mm openings'), title: IL.fmt(ap.areaMm2, 3) + ' mm²' + (ap.roundness ? ', corners ' + IL.fmt(ap.roundness, 0) + ' %' : '') }), partial),
      el('td', {}, el('span', { class: 'pp-badge', 'data-reason': g.reason }, IL.glyph(reasonKind(g), 'xs'), el('span', { text: g.short }))),
      el('td', { class: 'pt-needs__sugg' }, el('div', { class: 'pt-needs__st' }, suggShort(g),
        s.confidence ? el('span', { class: 'pt-needs__conf', text: ' · ' + s.confidence + ' confidence' }) : null),
      el('div', { class: 'pt-needs__btns' }, acts)));
  }
  /** A long KiCad name with line-break chances after "_" and "-". */
  function breakable(id) {
    const out = [];
    String(id).split(/(?<=[_-])/).forEach((x, i) => { if (i) out.push(el('wbr')); out.push(x); });
    return out;
  }
  function renderNeeds(s) {
    const sec = $('pt-needs');
    if (!sec) return;
    const c = current();
    const pc = c && c.check;
    const sp = split(pc);
    const any = sp.fix.length + sp.tip.length + sp.notes.length > 0;
    IL.show(sec, any || !!(c && c.kind === 'board'));
    if (!c) return;
    IL.setText($('pt-needs-count'), sp.fix.length ? String(sp.fix.length) : '');
    // while pads need a profile, its actions lead: Profile's own "New pad type" steps back to a plain button
    const nb = $('btn-padtype-new');
    if (nb) nb.classList.toggle('btn--primary', !sp.fix.length);
    IL.setText($('pt-needs-src'), c.label);
    const st = $('pt-needs-state');
    const kind = sp.fix.length ? 'need' : sp.tip.length ? 'block' : 'ok';
    const word = sp.fix.length ? IL.plural(sp.fix.length, 'pad size') + ' to choose' : sp.tip.length ? 'Only the tip blocks' : pc ? 'Every pad has a profile' : 'Reading the board…';
    if (st.dataset.sig !== kind + word) { st.dataset.sig = kind + word; st.className = 'card__state card__state--' + kind; st.replaceChildren(IL.glyph(kind, 'sm'), word); }
    const tipName = (pc && pc.tip && pc.tip.name) || '22G tip';
    IL.setText($('pt-needs-lead'), c.error ? 'Could not read the board: ' + c.error : !pc ? 'Reading the board…'
      : sp.fix.length ? 'These pads get no paste until each has a pad type. Use an existing type of the same size, or let the machine calibrate the suggested one.'
        : 'Every pad that the ' + tipName + ' can dispense has a pad type.');
    const key = JSON.stringify([c.kind, c.planId || c.boardFile, (pc && pc.groups || []).map((g) => [g.group, g.reason, g.suggestion && g.suggestion.id])]);
    IL.keyed($('pt-needs-table').tBodies[0], key, () => (sp.fix.length ? sp.fix.map((g) => needsRow(c, g, 'fix', sp.fix.length === 1))
      : [el('tr', {}, el('td', { colspan: '3', class: 'muted', text: pc ? 'Nothing to choose.' : '…' }))]));
    IL.show($('pt-needs-table'), sp.fix.length > 0);
    for (const tr of sec.querySelectorAll('tr[data-group]')) tr.classList.toggle('sel', tr.dataset.group === P.sel);
    const tip = $('pt-needs-tip'), notes = $('pt-needs-notes');
    IL.show(tip, sp.tip.length > 0);
    IL.setText($('pt-needs-tip-s'), 'Cannot be dispensed with the ' + tipName + ' (' + sp.tip.reduce((a, g) => a + g.pads, 0) + ' pads)');
    IL.keyed(tip.querySelector('tbody'), key, () => sp.tip.map((g) => el('tr', { 'data-group': g.group, 'data-reason': g.reason },
      el('td', { class: 'pt-needs__pkg' }, el('div', { class: 'pt-needs__n' }, breakable(pkgOf(g))), el('div', { class: 'pt-needs__s', text: refList(g.refs, 3) + ' · ' + IL.plural(g.pads, 'pad') }),
        el('div', { class: 'pt-needs__s', text: IL.nb(pair2((g.aperture || {}).wMm, (g.aperture || {}).hMm) + ' mm openings') })),
      el('td', { class: 'pt-needs__why', text: g.reasonText }))));
    IL.show(notes, sp.notes.length > 0);
    IL.setText($('pt-needs-notes-s'), 'Notes (' + sp.notes.length + ')');
    IL.keyed(notes.querySelector('tbody'), key, () => sp.notes.map((g) => needsRow(c, g, 'note')));
    // foot: all at once
    const useAll = $('btn-needs-use-all'), autoAll = $('btn-needs-auto-all');
    const nu = sp.fix.filter(usable).length, na = sp.fix.filter(calibrates);
    IL.show(useAll, nu > 1);
    IL.setText(useAll, 'Use the matching pad types (' + nu + ')' + (c.kind === 'plan' ? ' · plans again' : ''));
    IL.show(autoAll, na.length > 1);
    if (na.length > 1) {
      const e = estimate(calRequest(c, na));
      IL.setText(autoAll, 'Calibrate all automatically… · ' + na.length + ' sizes' + estText(e));
    }
    IL.show($('pt-needs').querySelector('.pt-needs__foot'), nu > 1 || na.length > 1);
    const why = idle(s), calWhy = calIdle(s);
    IL.gate(useAll, why, $('needs-why'));
    for (const b of sec.querySelectorAll('button[data-action=use]')) IL.gate(b, why);
    for (const b of sec.querySelectorAll('button[data-action=manual], button[data-action=auto]')) IL.gate(b, calWhy);
    IL.gate(autoAll, calWhy, nu > 1 ? null : $('needs-why'));
    // one clear next action: the side panel's while it shows the calibration or a suggestion with its own buttons
    const shown = IL.panel.isPushed() ? null : findGroup(c, P.sel);
    const lead = !IL.panel.isPushed('pad-cal') && !(shown && (FIX.has(shown.reason) || shown.reason === 'outside') && (usable(shown) || calibrates(shown)));
    autoAll.classList.toggle('btn--primary', lead);
    if (sp.fix.length === 1) for (const b of sec.querySelectorAll('#pt-needs-table button[data-action=use], #pt-needs-table button[data-action=auto]')) b.classList.toggle('btn--primary', lead);
  }

  // ================================================================================== the suggestion card (Profile side panel)
  function renderSugg(parts, s, first) {
    const c = current();
    const g = findGroup(c, P.sel);
    if (first) {
      const back = el('button', { type: 'button', class: 'btn btn--quiet btn--icon', id: 'pt-sugg-back', 'aria-label': 'Back to the profile summary', title: 'Back (Esc)' }, IL.icon('i-back'));
      back.addEventListener('click', () => IL.go('#profile/needs'));
      parts.top.append(el('div', { class: 'pt-ph' }, back, el('div', { class: 'pt-ph__t' }, el('div', { class: 'eyebrow', id: 'pt-sugg-e', text: 'Needs a profile' }),
        el('h2', { id: 'pt-sugg-n' })), el('span', { id: 'pt-sugg-b' })));
      const host = el('div', { class: 'pt-sugg__pv', id: 'pt-sugg-pvhost' });
      parts.body.append(el('div', { class: 'pt-sugg', id: 'pt-sugg', 'data-refusals': '' },
        el('div', { id: 'pt-sugg-reason' }),
        el('p', { class: 'pt-sugg__text', id: 'pt-sugg-text' }),
        el('figure', { class: 'pt-sugg__fig', id: 'pt-sugg-preview' }, host, el('figcaption', { id: 'pt-sugg-cap' })),
        el('dl', { class: 'kv kv--wrap pt-sugg__kv', id: 'pt-sugg-kv' }),
        el('details', { class: 'disclose', id: 'pt-sugg-alt' }, el('summary', { text: 'Other plans' }), el('ul', { class: 'pt-sugg__alt' })),
        el('div', { id: 'pt-sugg-tip', class: 'notebox notebox--block', hidden: true })));
      const use = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pt-sugg-use' });
      const auto = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pt-sugg-auto' });
      const man = el('button', { type: 'button', class: 'btn', id: 'btn-pt-sugg-manual' }, 'I’ll do it by hand');
      const edit = el('button', { type: 'button', class: 'btn btn--quiet', id: 'pt-sugg-edit' }, 'Edit before saving…');
      use.addEventListener('click', () => { const x = current(); const gg = findGroup(x, P.sel); if (gg) useOne(use, x, gg); });
      auto.addEventListener('click', () => { const x = current(); const gg = findGroup(x, P.sel); if (gg) openCal(calRequest(x, [gg])); });
      man.addEventListener('click', () => { const x = current(); const gg = findGroup(x, P.sel); if (gg) manual(man, x, gg); });
      edit.addEventListener('click', () => { const gg = findGroup(current(), P.sel); if (gg) editSuggestion(gg); });
      parts.foot.append(el('div', { class: 'btn-row pt-sugg__foot', 'data-refusals': '' }, use, auto, man, edit), el('div', { class: 'reason', id: 'pt-sugg-why', hidden: true }));
      P.bv = IL.boardview ? IL.boardview.create(host, { lite: true, aria: 'The part with the suggested dots on its pads' }) : null;
      if (P.bv) { P.bv.picture('full'); P.bv.layers(['path']); }
      P.bvKey = null;
    }
    if (!g) {
      IL.setText($('pt-sugg-n'), 'Not in this check');
      IL.keyed($('pt-sugg-reason'), 'gone', () => el('div', { class: 'notebox notebox--need' }, c && !c.check ? 'Reading the board…'
        : 'This pad size has a profile now, or it belongs to another board.', ' ', el('button', { type: 'button', class: 'link', text: 'Back to Needs a profile', onclick: () => IL.go('#profile/needs') })));
      for (const id of ['pt-sugg-text', 'pt-sugg-preview', 'pt-sugg-kv', 'pt-sugg-alt', 'pt-sugg-tip']) IL.show($(id), false);
      for (const id of ['btn-pt-sugg-use', 'btn-pt-sugg-auto', 'btn-pt-sugg-manual', 'pt-sugg-edit']) IL.show($(id), false);
      return;
    }
    const sg = g.suggestion || {};
    const none = !sg.kind || sg.kind === 'none';
    IL.setText($('pt-sugg-n'), pkgOf(g));
    IL.setText($('pt-sugg-e'), 'Needs a profile · ' + IL.plural(g.pads, 'pad'));
    IL.keyed($('pt-sugg-b'), g.reason + g.short, () => el('span', { class: 'pp-badge', 'data-reason': g.reason }, IL.glyph(reasonKind(g), 'xs'), el('span', { text: g.short })));
    IL.keyed($('pt-sugg-reason'), g.reasonText, () => el('div', { class: 'notebox notebox--' + reasonKind(g) }, IL.glyph(reasonKind(g), 'xs'), el('span', { text: g.reasonText })));
    IL.setText($('pt-sugg-text'), IL.nb(sg.sentence || ''));
    IL.show($('pt-sugg-text'), !none);
    IL.show($('pt-sugg-kv'), !none);
    IL.show($('pt-sugg-tip'), none);
    if (none) IL.setText($('pt-sugg-tip'), TIPS.has(g.reason) ? 'Pads that cannot be dispensed keep the board from running while “Refuse flagged pads” is on (Setup › Settings).'
      : 'No suggestion for these pads: ' + g.reasonText);
    // the part with the suggested dots
    drawSuggPreview(c, g);
    if (!none) {
      const d = sg.dots || {}, pr = sg.predicted || {}, am = sg.amount || {}, op = sg.opening || g.aperture || {};
      const ripple = pr.padRipplePct && (pr.padRipplePct[0] || pr.padRipplePct[1]) ? IL.fmt(pr.padRipplePct[0], 0) + '–' + IL.fmt(pr.padRipplePct[1], 0) + ' %' : '0 % (whole turns per pad)';
      IL.kv($('pt-sugg-kv'), [
        ['Opening', pair2(op.wMm, op.hMm) + ' mm' + (op.roundness ? ' · ' + IL.fmt(op.roundness, 0) + ' % round' : '') + ' · ' + IL.fmt(op.areaMm2, 3) + ' mm²'],
        ['Target amount', IL.fmt(am.targetMg, 2) + ' mg per pad' + (am.k && am.k !== 1 ? ' (k ' + IL.fmt(am.k, 2) + ')' : '') + (am.capped ? ', capped at a 0.25 mm print' : '') + (am.rule ? ' · ' + am.rule : '')],
        ['Dots per pad and turn', d.perPad + ' ' + turnAdj(d.steps) + ' ' + (d.perPad === 1 ? 'dot' : 'dots') + ' per pad (' + IL.fmt(d.netDeg, 2) + '° each)'],
        ['Dot size', dRange(d.diameterMm) + (d.dSource === 'measured' ? ' (measured)' : ' (model)')],
        ['Per pad', mg(pr.mgPerPad) + (isNum(pr.errorPct) ? ', ' + (pr.errorPct === 0 ? 'the target' : Math.abs(pr.errorPct) + ' % ' + (pr.errorPct < 0 ? 'less' : 'more') + ' than the target') : '') + (d.mgSource && d.mgSource !== 'model' ? ' (weighed)' : '')],
        ['Spread from the screw angle', ripple],
        ['Clearances', (isNum(pr.overhangMm) ? (pr.overhangMm > 0 ? IL.fmt(pr.overhangMm, 2) + ' mm past the pad edge' : 'inside the pad') : '—')
          + (isNum(pr.dotGapMm) ? ' · ' + IL.fmt(pr.dotGapMm, 2) + ' mm to the next dot' : '') + (isNum(pr.padGapMm) ? ' · ' + IL.fmt(pr.padGapMm, 2) + ' mm to the next pad' : '')],
        ['Phase', sg.phase ? sg.phase.text : 'the profile’s own turn'],
        ['Confidence', IL.cap(sg.confidence || '—') + ((sg.confidenceWhy || []).length ? ': ' + sg.confidenceWhy.join('; ') + '.' : '.')],
      ]);
    }
    const alts = sg.alternatives || [];
    IL.show($('pt-sugg-alt'), !none && alts.length > 0);
    IL.keyed($('pt-sugg-alt').querySelector('ul'), JSON.stringify(alts), () => alts.map((a) => el('li', {},
      el('b', { text: a.perPad + ' ' + turnAdj(a.steps) + ' ' + (a.perPad === 1 ? 'dot' : 'dots') + ', ' + mg(a.mgPerPad) + ': ' }), a.why)));
    // actions
    const use = $('btn-pt-sugg-use'), auto = $('btn-pt-sugg-auto'), man = $('btn-pt-sugg-manual'), edit = $('pt-sugg-edit');
    const fixable = FIX.has(g.reason) || g.reason === 'outside';
    IL.show(use, usable(g) && fixable);
    IL.setText(use, 'Use ' + typeLabel(sg) + (c.kind === 'plan' ? ' · plans again' : ''));
    IL.show(auto, calibrates(g) && fixable);
    IL.show(man, calibrates(g) && fixable);
    IL.show(edit, calibrates(g) && fixable && sg.kind === 'new');
    if (calibrates(g) && fixable) IL.setText(auto, 'Calibrate automatically…' + estText(estimate(calRequest(c, [g]))));
    const why = idle(s), calWhy = calIdle(s);
    IL.gate(use, why, $('pt-sugg-why'));
    IL.gate(man, calWhy);
    IL.gate(auto, calWhy, use.hidden ? $('pt-sugg-why') : null);
  }
  /** The part of the group's first ref, its pads, the suggested dots at their upper diameter (g.pv-l-sugg). */
  function drawSuggPreview(c, g) {
    const bv = P.bv;
    const fig = $('pt-sugg-preview');
    const sg = g.suggestion || {};
    const d = sg.dots;
    if (!bv || !d || !(d.positions || []).length) { IL.show(fig, false); return; }
    const ref = (g.refs || [])[0];
    let box = null, sugg = [];
    if (c.kind === 'plan') {
      const p = S.plan;
      if (bv.plan !== p) { bv.setPlan(p); P.bvKey = null; }
      const pads = (p.pads || []).filter((x) => x.placementId === ref);
      const mine = pads.filter((x) => x.profileGroup === g.group || (g.padIds || []).includes(x.padId));
      sugg = dotsOn(mine, g, d);
      box = boxOf(pads.map((x) => ({ x: x.x, y: x.y, r: Math.hypot(x.w || 0, x.h || 0) / 2 })).concat(sugg.map((x) => ({ x: x.x, y: x.y, r: x.d / 2 }))));
      bv.select({ refs: [ref], padIds: mine.map((x) => x.padId) });
    } else {
      const art = boardArt(c.boardFile);
      const part = art && (art.parts || []).find((x) => x.ref === ref);
      const pk = part && pkgPads(pkgOf(g)) || null;
      if (!part || !pk) { IL.show(fig, false); return; }
      if (bv.art !== art || bv.plan) { bv.setPlan(null); bv.setBoard({ art, toMachine: [1, 0, 0, 1, 0, 0] }); P.bvKey = null; }
      const a = (Number(part.a) || 0) * Math.PI / 180, cs = Math.cos(a), sn = Math.sin(a);
      const names = new Set(g.padNames || []);
      const mine = pk.filter((x) => !names.size || names.has(x.name)).map((x) => ({ x: part.x + x.x * cs - x.y * sn, y: part.y + x.x * sn + x.y * cs,
        w: x.w, h: x.h, rotation: (Number(part.a) || 0) + (Number(x.rotation) || 0) }));
      sugg = dotsOn(mine, g, d);
      box = boxOf(pk.map((x) => ({ x: part.x + x.x * cs - x.y * sn, y: part.y + x.x * sn + x.y * cs, r: Math.hypot(x.w, x.h) / 2 })).concat(sugg.map((x) => ({ x: x.x, y: x.y, r: x.d / 2 }))));
    }
    IL.show(fig, true);
    const key = g.group + '|' + sg.id + '|' + (c.planId || c.boardFile);
    if (P.bvKey !== key && box) { P.bvKey = key; bv.fit(box); }
    bv.update({ sugg });
    // a size range is drawn at its larger end; one size needs no note
    const dm = d.diameterMm;
    IL.setText($('pt-sugg-cap'), IL.plural(sugg.length, 'suggested dot') + ' on ' + ref + ' · dots ' + dRange(dm) + ' wide'
      + (dm && Math.abs(dm[0] - dm[1]) >= 0.005 ? ' (drawn at ' + f2(dm[1]) + ' mm, the larger size)' : ''));
  }
  /** Suggested dots on pads: the group's positions are in its first opening's frame (x along its width). */
  function dotsOn(pads, g, d) {
    const op = g.aperture || (g.suggestion && g.suggestion.opening) || {};
    const D = (d.diameterMm || [0, 1])[1];
    const out = [];
    for (const p of pads) {
      const swapped = Math.abs(p.w - op.wMm) > 0.02 && Math.abs(p.w - op.hMm) <= 0.02;
      const r = (Number(p.rotation) || 0) * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
      for (const [px0, py0] of d.positions) {
        const px = swapped ? -py0 : px0, py = swapped ? px0 : py0;
        out.push({ x: p.x + px * c - py * s, y: p.y + px * s + py * c, d: D });
      }
    }
    return out;
  }
  function boxOf(pts) {
    const b = IL.boardview && IL.boardview.bbox(pts);
    if (!b) return null;
    const g = { x0: b.x0 - 1, y0: b.y0 - 1, x1: b.x1 + 1, y1: b.y1 + 1 };
    const cx = (g.x0 + g.x1) / 2, cy = (g.y0 + g.y1) / 2, w = Math.max(4, g.x1 - g.x0), h = Math.max(4, g.y1 - g.y0);
    return { x0: cx - w / 2, y0: cy - h / 2, x1: cx + w / 2, y1: cy + h / 2 };
  }
  /** The drawing of a listed board file (board-file previews), or null while it loads or when it has none. */
  function boardArt(file) {
    if (!P.boards) {
      P.boards = { loading: true, list: [] };
      IL.api('GET', '/api/boards').then((b) => { P.boards = { list: (b && b.files) || [] }; IL.render(); }, () => { P.boards = { list: [] }; });
      return null;
    }
    const b = (P.boards.list || []).find((x) => x.file === file);
    const id = b && b.art && b.art.id;
    if (!id) return null;
    if (!P.art[id]) {
      P.art[id] = { loading: true };
      IL.api('GET', '/api/board-art/' + encodeURIComponent(id), undefined, { timeoutMs: 30000 }).then((a) => { P.art[id] = { art: a }; IL.render(); }, () => { P.art[id] = { art: null }; });
    }
    return P.art[id].art || null;
  }
  /** A package's footprint pads (board-file previews). */
  function pkgPads(id) {
    if (!P.pkgs && !P.pkgsLoading) {
      P.pkgsLoading = IL.api('GET', '/api/packages').then((p) => { P.pkgs = p || []; }, () => { P.pkgs = []; }).then(() => { P.pkgsLoading = null; IL.render(); });
    }
    const p = (P.pkgs || []).find((x) => base(x.id) === id);
    return p && (p.pads || []).length ? p.pads : null;
  }
  /** "Edit before saving…": the pad-type dialog prefilled from the suggestion; saved provisional with the mapping. */
  function editSuggestion(g) {
    if (!P.pt || !P.pt.openPadType) return;
    const sg = g.suggestion;
    P.pt.openPadType(null);
    P.prefill = { group: g.group, mapping: sg.mapping, origin: 'suggested' };
    const set = (id, v) => { const n = $(id); if (n) n.value = v === null || v === undefined ? '' : String(v); };
    const op = sg.pad || sg.opening || {};
    const fam = $('pt-family');
    if (fam) { const opt = Array.from(fam.options).find((o) => o.value === sg.family); if (opt) fam.value = sg.family; }
    set('pt-name', sg.name);
    set('pt-key', sg.typeKey);
    set('pt-w', op.wMm);
    set('pt-h', op.hMm);
    set('pt-round', op.roundness || 0);
    set('pt-layout', 'footprint-only');
    set('pt-status', 'provisional');
    set('pt-pattern', '');
    set('pt-notes', sg.sentence);
    set('pt-dotsteps', sg.dots && sg.dots.steps);
    set('pt-dotsperpad', sg.dots && sg.dots.perPad);
    for (const id of ['pt-name', 'pt-w']) { const n = $(id); if (n) n.dispatchEvent(new Event('input', { bubbles: true })); }
  }

  // ================================================================================== Profile hooks (H-P3)
  /** profile.js calls these through IL.pt (§13.3); its own versions stay for when this file is absent. */
  function profileHooks(pt) {
    P.pt = pt;
    const ownStats = pt.planStats;
    const ownNew = pt.newForPackage;
    pt.texts.meta = 'One recipe; a pad type may use another turn per dot (quarter, half or one turn). Plans made before a change are refused: plan again.';
    pt.texts.dotsCol = 'Dots per pad and turn';
    pt.texts.hint = (prof) => 'Every dot is one dot of the dispense profile' + (prof ? ' “' + prof.name + '” (' + IL.turnLabel(prof.netStepsPerDot, prof.stepsPerRev) + ')' : '')
      + ' unless its pad type has its own turn.';
    pt.texts.dotsCell = (t, prof) => (t.dotSteps && (!prof || t.dotSteps !== prof.netStepsPerDot) ? t.dotsPerPad + ' × ' + IL.turnLabel(t.dotSteps) : t.dotsPerPad ? IL.plural(t.dotsPerPad, 'dot') : '—');
    pt.texts.dotPlan = (t, prof) => {
      const own = t.dotSteps && (!prof || t.dotSteps !== prof.netStepsPerDot);
      if (own) {
        const cal = t.lastCalibration && t.lastCalibration.createdAtMs ? ', calibrated ' + IL.fmtTime(IL.toClient(t.lastCalibration.createdAtMs)) : '';
        return t.dotsPerPad + ' ' + turnAdj(t.dotSteps) + ' ' + (t.dotsPerPad === 1 ? 'dot' : 'dots') + ' per pad (own turn' + cal + ')';
      }
      return IL.plural(t.dotsPerPad, 'dot') + ' of the profile per pad (' + (prof ? IL.turnLabel(prof.netStepsPerDot, prof.stepsPerRev) : IL.fmt(t.rotationPerDotDeg, 2) + '°') + ')';
    };
    pt.planStats = function () {
      const own = ownStats();
      const p = S.plan;
      if (!p || !p.profileCheck || !own['(no type)']) return own;
      // pads without a pad type: one entry per group that needs a profile, named after its package
      const out = Object.assign({}, own);
      delete out['(no type)'];
      const groups = new Map((p.profileCheck.groups || []).map((g) => [g.group, g]));
      for (const pad of p.pads || []) {
        if (pad.typeKey) continue;
        const g = groups.get(pad.profileGroup);
        const k = g ? 'needs:' + g.group : '(no type)';
        const o = out[k] || (out[k] = { pads: 0, blocked: 0, dots: 0, packages: new Set(), label: g ? pkgOf(g) + ' · ' + g.short : '(no type)',
          href: g ? '#profile/needs/' + g.group : null });
        o.pads++;
        if (pad.blocked) o.blocked++;
        o.dots += pad.dotCount || 0;
        if (pad.packageId) o.packages.add(pad.packageId);
      }
      return out;
    };
    pt.newForPackage = function (pkg) {
      const c = current();
      const g = (c && c.check && c.check.groups || []).find((x) => (x.packageIds || []).some((id) => id === pkg || base(id) === base(pkg)) && x.suggestion && x.suggestion.kind === 'new');
      if (g) editSuggestion(g);
      else ownNew(pkg);
    };
    pt.route = function (r) {
      if (r.section !== 'needs') {
        if (P.sel !== null) { P.sel = null; if (IL.active() === 'profile') IL.panel.reset(); }
        if (pt.PT.filter === 'needs') pt.PT.filter = 'all';
        return false;
      }
      pt.selectType(null);
      pt.PT.filter = 'needs';
      const sel = r.id || null;
      if (P.sel !== sel) { P.sel = sel; IL.panel.reset(); }
      P.scrollTo = { sel };   // after the next render has shown the list
      return true;
    };
    pt.panel = function (parts, s, first) {
      if (!P.sel) { P.panelSel = null; return false; }
      if (!first && P.panelSel !== P.sel) { IL.panel.reset(); return true; }
      if (first) P.panelSel = P.sel;
      renderSugg(parts, s, first);
      return true;
    };
    pt.sectionExtras.push((section) => {
      const host = section.querySelector('#profiles');
      const box = el('div', { id: 'pt-needs-host' });
      section.insertBefore(box, host);
      mountNeeds(box);
      const seg = section.querySelector('#pt-filter');
      const b = el('button', { type: 'button', 'aria-pressed': 'false', 'data-filter': 'needs', id: 'pt-filter-needs', hidden: true }, 'Needs a profile');
      b.addEventListener('click', () => IL.go('#profile/needs'));
      if (seg) seg.appendChild(b);
    });
    pt.renderExtras.push((s) => {
      renderNeeds(s);
      if (P.scrollTo && $('pt-needs') && !$('pt-needs').hidden) {
        const sel = P.scrollTo.sel;
        P.scrollTo = null;
        requestAnimationFrame(() => {
          const row = sel ? document.querySelector('#pt-needs tr[data-group="' + CSS.escape(sel) + '"]') : null;
          if (row) { row.scrollIntoView({ block: 'center' }); IL.flash(row); } else IL.flash($('pt-needs'));
        });
      }
      const c = current();
      const n = split(c && c.check).fix.length;
      const b = $('pt-filter-needs');
      IL.show(b, n > 0);
      IL.setText(b, 'Needs a profile · ' + n);
    });
    pt.filters.needs = (t) => {
      const c = current();
      return split(c && c.check).fix.some((g) => g.typeKey === t.typeKey || (g.suggestion && g.suggestion.kind !== 'new' && g.suggestion.typeKey === t.typeKey));
    };
    pt.navSubs.push(() => {
      const c = current();
      const n = split(c && c.check).fix.length;
      return n ? { sub: 'Needs ' + IL.plural(n, 'profile'), kind: 'need' } : null;
    });
    pt.sidePanelExtras.push((t) => {
      const b = el('button', { type: 'button', class: 'btn', id: 'btn-pt-auto', title: 'Ladder on the blank and weighing on the coupons: characterizes this pad type' }, 'Calibrate automatically…');
      b.addEventListener('click', () => {
        const c = current();
        const g = (c && c.check && c.check.groups || []).find((x) => x.typeKey === t.typeKey && calibrates(x));
        openCal(g ? calRequest(c, [g]) : { typeKeys: [t.typeKey] });
      });
      return b;
    });
    pt.editorExtras.push({
      build() {
        const steps = el('select', { class: 'input', id: 'pt-dotsteps' },
          el('option', { value: '', text: 'the profile’s' }), el('option', { value: '400', text: 'quarter turn (400 steps)' }),
          el('option', { value: '800', text: 'half turn (800)' }), el('option', { value: '1600', text: 'one turn (1600)' }));
        const per = el('input', { class: 'input', id: 'pt-dotsperpad', type: 'number', min: '1', max: '6', step: '1', inputmode: 'numeric', placeholder: 'by pad size', autocomplete: 'off' });
        return [el('div', { class: 'pt-dlg-grid' },
          field('pt-dotsteps', 'Turn per dot', null, steps, 'Quarter turns run as their own block after the profile’s dots.'),
          field('pt-dotsperpad', 'Dots per pad', '1 to 6', per, 'Empty: by the pad size (the pad rule).')),
        el('div', { class: 'pt-calinfo', id: 'pt-calinfo' })];
      },
      open(t, prof) {
        P.prefill = null;
        $('pt-dotsteps').value = t && t.dotSteps ? String(t.dotSteps) : '';
        $('pt-dotsperpad').value = t && t.dotSteps && t.dotsPerPad ? String(t.dotsPerPad) : '';
        const turn0 = $('pt-dotsteps').options[0];
        turn0.textContent = 'the profile’s' + (prof ? ' (' + TURN_NAME(prof.netStepsPerDot) + ')' : '');
        IL.keyed($('pt-calinfo'), JSON.stringify([t ? t.typeKey : '', t ? t.version : 0]), () => (t ? calInfo(t) : null));
      },
      body(b) {
        const st = $('pt-dotsteps').value, per = IL.num('pt-dotsperpad');
        b.dotSteps = st ? Number(st) : null;
        b.dotsPerPad = st && per !== undefined ? per : null;
        if (P.prefill) {
          b.origin = P.prefill.origin;
          if (P.prefill.mapping) b.mapping = P.prefill.mapping;
          P.prefill = null;
          refresh();
        }
        return b;
      },
      check(errs) {
        const per = IL.num('pt-dotsperpad');
        if (per !== undefined && !(Number.isInteger(per) && per >= 1 && per <= 6)) errs['pt-dotsperpad'] = 'A whole number, 1 to 6.';
        return errs;
      },
    });
  }
  const TURN_NAME = (steps) => ({ 400: 'quarter turn', 800: 'half turn', 1600: 'one turn' }[steps] || IL.turnLabel(steps));
  function field(id, label, unit, input, help) {
    return el('div', { class: 'field', id: id + '-wrap' },
      el('label', { class: 'field__l', for: id }, el('span', { text: label }), unit ? el('span', { class: 'unit', text: unit }) : el('span')),
      input, help ? el('div', { class: 'field__h', text: help }) : null);
  }
  /** Read-only facts of a type's calibration: tip, recipe, calibrated area, mass per pad, origin, last calibration. */
  function calInfo(t) {
    const rows = [];
    if (t.tipKey) rows.push(['Tip', t.tipKey]);
    if (t.doseFp) rows.push(['Recipe', 'calibrated at ' + t.doseFp]);
    if (isNum(t.calAreaMm2)) rows.push(['Calibrated for', IL.fmt(t.calAreaMm2, 3) + ' mm² pads']);
    if (isNum(t.massPerPadMg)) rows.push(['Mass per pad', IL.fmt(t.massPerPadMg, 2) + ' mg (weighed)']);
    if (t.origin) rows.push(['Origin', { suggested: 'a suggestion', manual: 'set by hand', calibrated: 'calibrated' }[t.origin] || t.origin]);
    const lc = t.lastCalibration;
    if (lc) rows.push(['Last calibration', IL.fmtDateTime(IL.toClient(lc.createdAtMs)) + (lc.outcome ? ' · ' + lc.outcome : '')]);
    if (!rows.length) return null;
    return el('dl', { class: 'kv kv--wrap' }, rows.flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: v })]));
  }

  // ================================================================================== the pad calibration job
  const job = (s) => (s && s.padCalibration) || null;
  const jobLock = (s) => { const j = job(s); return !!j && LOCKING.has(j.state); };
  const jobActive = (s) => { const j = job(s); return !!j && ACTIVE.has(j.state); };
  function jobLockText(s) { return jobLock(s) ? 'Locked while the pad calibration runs' : null; }
  // the panel's step names, short (STEP_TITLE)
  const STEP_WORD = { blank: 'blank', coupons: 'coupons', ladder: 'test dots', weigh: 'weigh dots', readings: 'readings', decide: 'deciding', plan: 'plan again' };
  /**
   * The strip's live label of the job's engine step: ['Pad calibration', ' · test dots 14/48 · ≈ 2:50 left'] (the
   * second part goes when the strip is narrow: "Pad calibration" whole rather than "Pad calibration · t…").
   */
  function taskParts(run) {
    const j = job(S.status);
    const st = j && j.steps ? j.steps.find((x) => x.state === 'now') : null;
    const word = st ? STEP_WORD[st.key] || st.key : 'running';
    const n = run.dotsPlanned ? ' ' + Math.min(run.dotsDone || 0, run.dotsPlanned) + '/' + run.dotsPlanned : '';
    const left = j && isNum(j.estimatedSecondsLeft) ? ' · ≈ ' + IL.fmtClock(j.estimatedSecondsLeft) + ' left' : '';
    return ['Pad calibration', ' · ' + word + n + left];
  }
  /** The strip verdict while the job waits for the operator or works between its engine steps, else null. */
  function verdict(s) {
    const j = job(s);
    if (!j) return null;
    // the part after the first " · " goes when the strip is too narrow (core.js setCell, level 2)
    if (j.state === 'awaiting-reading') return { state: 'need', text: 'Waiting for you · weigh', value: ['Waiting for you', ' · weigh', 2], title: 'The pad calibration waits for the coupon readings (since ' + IL.fmtTime(IL.toClient(j.awaiting && j.awaiting.sinceMs)) + '): open it' };
    if (LOCKING.has(j.state)) {
      const st = (j.steps || []).find((x) => x.state === 'now');
      const word = st ? STEP_WORD[st.key] || st.key : j.state;
      return { state: 'live', text: 'Pad calibration · ' + word, value: ['Pad calibration', ' · ' + word, 2], title: 'Pad calibration running: other machine actions wait' };
    }
    return null;
  }

  /** Opens the pad-cal view before start for a request ({planId | boardFile, groups, suggestionIds} or a by-hand one). */
  function openCal(req) {
    const r = Object.assign({}, req, { atMs: Date.now() });
    IL.ssSet(REQ_KEY, r);
    C.req = r;
    C.mode = 'before';
    estimate(r, true);
    if (IL.panel.isPushed('pad-cal')) IL.panel.reset();
    else IL.openView('pad-cal', { keep: true });
  }
  const C = { req: IL.ssJson(REQ_KEY, null), mode: null, readings: {}, confirm: null, cropsKey: null };
  /** What the view shows: before (a request not started), job (the job of this tab or a running one), idle. */
  function calMode(s) {
    const j = job(s);
    if (j && ACTIVE.has(j.state)) return 'job';
    if (C.mode === 'before' && C.req) return 'before';
    if (j && (j.id === IL.ssJson(JOB_KEY, null) || C.mode === 'job')) return 'job';
    return C.req ? 'before' : j ? 'job' : 'idle';
  }
  /** A by-hand step of the Calibrate guide (§8): the job's own engine step, started now (the caller asked twice). */
  async function startManual(req) {
    C.req = Object.assign({}, req);
    const r = await startCal();
    IL.openView('pad-cal', { keep: true });
    return r;
  }
  async function startCal() {
    const req = C.req;
    const reg = IL.attest.valid('regulator', S.status), life = IL.attest.valid('paste-life', S.status);
    const body = Object.assign({}, req, { save: true });
    delete body.atMs;
    if (reg) body.regulator = { psi: Number(reg.value), atMs: Math.round(reg.atMs + S.skewMs) };
    if (life) body.pasteLifeAtMs = Math.round(life.atMs + S.skewMs);
    const r = await IL.api('POST', '/api/pad-calibrations', body, { timeoutMs: 60000 });
    IL.ssSet(JOB_KEY, r.id);
    IL.ssSet(REQ_KEY, null);
    C.req = null;
    C.mode = 'job';
    C.readings = {};
    C.pending = false;
    IL.panel.reset();
    // saving the suggestions as provisional types planned the board again (§7.2 step 0): show that plan, the one the
    // job plans again from at its end (status.padCalibration.planId), so the plan it makes then is loaded here too
    const pid = r.preview && r.preview.source && r.preview.source.planId;
    if (pid && req.planId && pid !== req.planId && IL.planMatches(req.planId)) {
      await IL.loadPlan(pid);
      if (IL.pcr && IL.pcr.loadPadTypes) IL.pcr.loadPadTypes();
    }
    return r;
  }

  IL.view('pad-cal', {
    title: (s) => { const j = job(s); return j && ACTIVE.has(j.state) ? (j.manual ? 'Pad calibration · by hand' : 'Pad calibration') : calMode(s) === 'before' ? 'Calibrate automatically' : 'Pad calibration'; },
    render(parts, s, first) {
      const mode = calMode(s);
      if (first || C.built !== mode) {
        if (!first) { for (const n of [parts.top, parts.body, parts.foot]) n.replaceChildren(); parts.scroll.scrollTop = 0; }
        C.built = mode;
        C.cropsKey = null;
        if (mode === 'before') buildBefore(parts);
        else if (mode === 'job') buildJob(parts);
        else parts.body.append(el('p', { class: 'panel__lead', text: 'No pad calibration yet. Start one from Profile › Needs a profile (Calibrate automatically…).' }));
      }
      if (mode === 'before') renderBefore(s);
      else if (mode === 'job') renderJob(s);
    },
    unmount() { C.built = null; },
  });

  // ---------------------------------------------------------------- before start (§7.9)
  function buildBefore(parts) {
    const start = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-pcal-start' }, IL.icon('i-play'), el('span', { id: 'pcal-start-l', text: 'Calibrate' }));
    const refusal = el('div', { id: 'pcal-refusal' });
    IL.press2(start, 'calibrate', () => startCal(), { ok: () => 'Pad calibration started: STOP stops it at once (F9).', refusalIn: refusal });
    parts.body.append(el('div', { class: 'pcal', 'data-refusals': '' },
      el('p', { class: 'pcal__note', id: 'pcal-note' }),
      el('div', { class: 'notebox notebox--info pcal__needs', id: 'pcal-needs', hidden: true }),
      IL.sectionK('Steps'),
      el('table', { class: 'tbl pcal__plan', id: 'pcal-plan' }, el('tbody')),
      IL.sectionK('Pad sizes'),
      el('ul', { class: 'pcal__groups', id: 'pcal-groups-list' }),
      el('div', { id: 'pcal-fixes', class: 'pcal__fixes', hidden: true }),
      refusal));
    parts.foot.append(start, el('div', { class: 'reason', id: 'pcal-why', hidden: true }));
  }
  const STEP_TITLE = { blank: 'Probe the blank', coupons: 'Find and touch the coupons', ladder: 'Test dots on the blank', weigh: 'Weigh dots on the coupons',
    readings: 'Your readings', decide: 'Decide', plan: 'Plan again', home: 'Home', zcheck: 'Z check' };
  /**
   * The purge spots the job lays (preview purgeSpots {need, left}; rc1 review: the start card did not say, and the board
   * was short of spots right after): "It uses 26 of the 31 free purge spots: wipe the purge row and reset it before the
   * next board." Empty when it uses none.
   */
  function purgeWords(pv, s) {
    const ps = pv && pv.purgeSpots;
    if (!ps || !isNum(ps.need) || ps.need <= 0) return '';
    const row = (s && s.dispense && s.dispense.purgeRow) || {};
    const after = ps.left - ps.need;
    return 'It uses ' + ps.need + ' of the ' + IL.plural(ps.left, 'free purge spot') + (after >= 0 && after < IL.purgeNeed(row)
      ? ': wipe the purge row and reset it before the next board.' : '.');
  }
  function renderBefore(s) {
    const e = estimate(C.req);
    const pv = e && e.s;
    const pw = purgeWords(pv, s);
    IL.setText($('pcal-note'), pv ? IL.nb(pv.sentence + (pw ? ' ' + pw : '')) : e && e.error ? 'Could not read the summary: ' + e.error : 'Reading the summary…');
    IL.setText($('pcal-needs'), pv && pv.needsText ? IL.nb(pv.needsText) : '');
    IL.show($('pcal-needs'), !!(pv && pv.needsText));
    IL.keyed($('pcal-plan').tBodies[0], JSON.stringify(pv ? [pv.steps, pv.home, pv.homes, pv.zCheck, pv.purgeSpots] : null), () => {
      if (!pv) return null;
      const rows = [];
      // under the "always home first" setting it homes before every engine step (pv.homes of them)
      if (pv.home && pv.home.needed) {
        rows.push(pv.homes > 1 ? ['Home', 'before each of its ' + pv.homes + ' steps (the setting is: always home first)', pv.homes * pv.home.estimateS]
          : ['Home', 'not homed: ' + (pv.home.why || ''), pv.home.estimateS]);
      }
      if (pv.zCheck && pv.zCheck.needed) rows.push(['Z check', 'one N1 touch', pv.zCheck.estimateS]);
      for (const x of pv.steps) rows.push([STEP_TITLE[x.key] || x.key, x.state === 'skipped' ? x.text || 'skipped' : stepDetail(pv, x.key), x.state === 'skipped' ? null : x.estimatedSeconds, x.key]);
      // the purge row it uses (a phase dot and flush dots before every dose, 8 to start): in the time of the steps above
      const ps = pv.purgeSpots;
      if (ps && isNum(ps.need) && ps.need > 0) rows.push(['Purge row', ps.need + ' of ' + IL.plural(ps.left, 'free spot') + ' (' + (ps.left - ps.need) + ' left after)', 0, 'purge']);
      // the time column: the machine's estimate, "you" for the readings, nothing for a step that takes no time
      const when = (sec, k) => (k === 'readings' && sec !== null ? 'you' : isNum(sec) && sec > 0 ? '≈ ' + IL.fmtClock(sec) : sec === 0 ? '' : '—');
      return rows.map(([t, d, sec, k]) => el('tr', {}, el('td', {}, el('div', { class: 'pcal__st', text: t }), d ? el('div', { class: 'pcal__sd', text: d }) : null),
        el('td', { class: 'r nowrap', text: when(sec, k) })));
    });
    IL.keyed($('pcal-groups-list'), JSON.stringify(pv ? [pv.groups, pv.saves, pv.demotes, pv.skipped] : null), () => {
      if (!pv) return null;
      const out = (pv.groups || []).map((g) => el('li', {}, el('b', { text: g.label }),
        el('div', { class: 'pcal__sd', text: (g.kind === 'new' ? 'new pad type ' + g.typeKey + ', saved now as provisional' : g.kind === 'calibrate-existing' ? g.typeKey + ', a new provisional version now' : g.typeKey || '')
          + (isNum(g.steps) ? ' · ' + g.perPad + ' ' + turnAdj(g.steps) + ' ' + (g.perPad === 1 ? 'dot' : 'dots') + ' per pad' : '') })));
      if ((pv.saves || []).length) out.push(el('li', { class: 'pcal__sd', text: 'Plans made before this go stale: it plans again at the end.' }));
      for (const k of pv.demotes || []) out.push(el('li', { class: 'pcal__warn', text: k + ' is validated now: this calibration makes it characterized until you validate it on a board again.' }));
      for (const x of pv.skipped || []) out.push(el('li', { class: 'pcal__sd', text: (x.group || '') + ': ' + x.why }));
      return out;
    });
    // the refusal Start would get, with its fix-its in place
    const ref = pv && pv.refusal;
    const fx = $('pcal-fixes');
    IL.show(fx, !!ref);
    IL.keyed(fx, ref || '', () => (ref ? [el('div', { class: 'notebox notebox--need' }, IL.glyph('need', 'xs'), el('span', { text: ref })), IL.fixRow(fixesFor(ref, s), 'btn-row')] : null));
    const start = $('btn-pcal-start');
    if (!IL.isArmed(start)) IL.setText($('pcal-start-l'), 'Calibrate' + estText(e));
    // it homes by itself when needed: not while a STOP left N1 low (as Run, Register board and Probe the blank)
    const why = IL.lockReason(s, { connected: true, engine: true, homes: true }) || jobLockText(s) || (jobActive(s) ? 'A pad calibration is waiting for its readings: finish or cancel it first' : null)
      || (!pv ? 'Reading the summary…' : ref ? 'Fix the item above first.' : null);
    IL.gate(start, why, $('pcal-why'));
    if (e && !e.loading && Date.now() - e.at > 5000) estimate(C.req, true);
  }
  function stepDetail(pv, key) {
    if (key === 'blank' && pv.blank) return pv.blank.probe ? 'N1 touches the blank: ' + (pv.blank.probeWhy || '') : 'blank surface still valid';
    if (key === 'coupons') return (pv.coupons || []).map((k) => k.place).join(' and ') + ': camera look, then one N1 touch each';
    if (key === 'ladder') return (pv.ladder || []).map((x) => x.dots + ' ' + turnAdj(x.steps) + ' dots').join(', ') + (pv.blank ? ' in X ' + IL.fmt(pv.blank.x0, 1) + '–' + IL.fmt(pv.blank.x1, 1) + ' mm, Y ' + IL.fmt(pv.blank.y0, 1) + '–' + IL.fmt(pv.blank.y1, 1) + ' mm' : '');
    if (key === 'weigh') return (pv.coupons || []).map((k) => k.dots + ' ' + turnAdj(k.steps) + ' dots on ' + k.place + ' (≈ ' + k.expectedMg + ' mg)').join(', ') + ', then the head parks over the purge row';
    if (key === 'readings') return 'weigh each coupon, wipe it, weigh it again';
    if (key === 'decide') return 'every check against its limit; a new pad-type version';
    if (key === 'plan') return 'the board planned again with the new pad types';
    return '';
  }
  /** Inline fix-its for the §12.2 refusals of a start. */
  function fixesFor(text, s) {
    const t = String(text || '');
    const psi = s && s.dispense && s.dispense.profile ? IL.fmtPsi(s.dispense.profile.pressurePsi) : '';
    if (/^Read the regulator/.test(t)) return [{ view: 'pressure', label: 'Pressure on to read the gauge' }, { fix: 'attest-regulator', label: 'Reads ' + psi + ' psi' }];
    if (/paste is within its working life/.test(t)) return [{ fix: 'attest-paste', label: 'Paste within its life' }];
    if (/mm left and this calibration needs/.test(t)) return [{ fix: 'pcal-wiped', label: 'Blank wiped' }, { fix: 'pcal-area', label: 'Set the calibration area' }];
    if (/calibration area overlaps|calibration area/.test(t)) return [{ fix: 'pcal-area', label: 'Set the calibration area' }];
    if (/coupon places/.test(t)) return [{ href: '#setup/places', label: 'Set the coupon places' }];
    // a coupon place on the purge row or on the board's pads
    const cp = /weigh coupon place (\S+) overlaps/.exec(t);
    if (cp) return [{ href: '#setup/places/' + encodeURIComponent(cp[1]), label: 'Move ' + cp[1] + ' (Setup › Places)' }];
    // its readings, or a skip, in Calibrate › Pad calibration (the job that laid the dots may be gone)
    if (/carries \d+ dots waiting/.test(t)) return [{ href: '#calibrate/padcal', label: 'Type the readings or skip them' }];
    const m = IL.CODE_RE.exec(t);
    return m ? IL.fixesForCode(m[1], s, m[2]) : [];
  }

  // ---------------------------------------------------------------- the job: running, readings, result
  function buildJob(parts) {
    const save = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pcal-readings' }, 'Save the readings');
    const skip = el('button', { type: 'button', class: 'btn btn--quiet', id: 'btn-pcal-skip' }, 'Skip weighing (the types stay provisional: boards still refuse these pads)');
    const cancel = el('button', { type: 'button', class: 'btn btn--danger-ghost', id: 'btn-pcal-cancel' }, 'Cancel the calibration');
    save.addEventListener('click', () => saveReadings(save, !!C.confirm));
    skip.addEventListener('click', () => IL.act(skip, () => answer({ step: 'readings', skip: true }), { ok: 'Weighing skipped: the types stay provisional.', refusalIn: $('pcal-ask-ref') }));
    IL.press2(cancel, 'cancel', () => IL.api('POST', '/api/pad-calibrations/' + encodeURIComponent(job(S.status).id) + '/cancel', {}), { ok: 'Pad calibration cancelled: the coupons keep their dots until they are weighed or stale.' });
    const again = el('button', { type: 'button', class: 'btn', id: 'btn-pcal-again' }, 'Calibrate again…');
    const weighNow = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pcal-weigh-now' }, 'Type the readings');
    const finish = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pcal-finish' }, 'Finish the calibration');
    const man = el('button', { type: 'button', class: 'btn btn--quiet', id: 'btn-pcal-manual' }, 'I’ll do it by hand');
    const plan = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-pcal-plan' }, 'Checks', IL.icon('i-chev'));
    again.addEventListener('click', () => again2(false));
    finish.addEventListener('click', () => again2(true));
    weighNow.addEventListener('click', () => { C.pending = true; IL.render(); });
    man.addEventListener('click', () => {
      const j = job(S.status);
      const g = j && j.result && (j.result.groups || []).find((x) => x.outcome !== 'characterized');
      const c = current();
      const gg = g && findGroup(c, g.group);
      if (gg && calibrates(gg)) manual(man, c, gg);
      else IL.go('#calibrate');
    });
    plan.addEventListener('click', () => { IL.panel.close(); IL.go('#run/checks'); });
    parts.body.append(el('div', { class: 'pcal pcal--job' },
      el('div', { class: 'ri ri--need', id: 'pcal-ask', role: 'status', hidden: true, 'data-refusals': '' },
        IL.glyph('need'), el('div', { class: 'pcal-ask' },
          el('div', { class: 'ri__t', text: 'Weigh the coupons' }),
          el('p', { class: 'ri__d', id: 'pcal-ask-text' }),
          el('div', { id: 'pcal-coupons', 'data-enter-submits': 'btn-pcal-readings' }),
          el('div', { id: 'pcal-ask-ref' }),
          el('div', { class: 'ri__act' }, skip))),
      el('div', { class: 'ri', id: 'pcal-result', role: 'status', hidden: true, 'data-refusals': '' }),
      el('div', { class: 'pcal__more', id: 'pcal-result-more', hidden: true },
        el('ul', { class: 'pcal__res', id: 'pcal-groups' }),
        IL.sectionK('Camera crops (before · after)'),
        el('div', { class: 'pcal__crops', id: 'pcal-crops' }),
        el('details', { class: 'disclose', id: 'pcal-pred-d' }, el('summary', { text: 'Predicted and measured' }),
          el('table', { class: 'tbl pcal__pred', id: 'pcal-pred' }, el('thead', {}, el('tr', {}, ['', 'Predicted', 'Measured', ''].map((h) => el('th', { scope: 'col', text: h })))), el('tbody')),
          el('p', { class: 'pcal__sd', id: 'pcal-drift' }))),
      el('ol', { class: 'phases', id: 'pcal-phases' }),
      el('div', { class: 'pcal-live', id: 'pcal-live', role: 'status' }),
      el('div', { id: 'pcal-pending', hidden: true }),
      el('div', { id: 'pcal-ladder-box', hidden: true }, IL.sectionK('Camera checks of the dots'),
        el('p', { class: 'pcal__sd', text: 'Every dot the top camera looked at, per dot size: the test dots on the blank and the weigh dots on the coupons.' }),
        el('div', { class: 'pcal__tblwrap' }, el('table', { class: 'tbl pcal__ladder', id: 'pcal-ladder' },
          el('thead', {}, el('tr', {}, [['Turn'], ['Found', 'dots found of dots laid'], ['Threads', 'paste threads to the next dot, of the moves'], ['Spread', 'residual spread of the dot area'],
            ['Angle', 'dot area between the screw angles, and its limit'], ['Size', 'mean and 95th percentile diameter (mm)'], ['']].map(([h, t]) => el('th', { scope: 'col', title: t || null, text: h })))), el('tbody'))))));
    // the pinned foot: Save the readings while they are asked for (always in view); Cancel while the job is active (an
    // engine step is aborted; the coupons laid keep waiting for their readings); the next step after it ended
    parts.foot.append(el('div', { class: 'btn-row pcal__foot', 'data-refusals': '' }, save, plan, weighNow, finish, again, man, cancel),
      el('div', { class: 'reason', id: 'pcal-foot-why', hidden: true }),
      el('p', { class: 'pcal-stop', id: 'pcal-stopline', text: 'STOP stops it at once (F9).' }));
  }
  function again2(finish) {
    const j = job(S.status);
    if (!j) return;
    const c = current();
    const gs = (j.groups || []).map((id) => findGroup(c, id)).filter((g) => g && calibrates(g));
    if (gs.length) openCal(calRequest(c, gs));
    else if ((j.typeKeys || []).length) openCal({ typeKeys: j.typeKeys });
    else if (finish) IL.toast('Nothing left to calibrate in the current check: every chosen pad size has a profile.', 'ok');
    else IL.go('#profile/needs');
  }
  async function answer(body) {
    const j = job(S.status);
    return IL.api('POST', '/api/pad-calibrations/' + encodeURIComponent(j.id) + '/answer', body, { timeoutMs: 30000 });
  }
  /** The typed readings of every coupon; a 409 implausible or late answer asks once more (Save anyway). */
  async function saveReadings(btn, confirm) {
    const j = job(S.status);
    const cs = (j && j.awaiting && j.awaiting.coupons) || [];
    const list = cs.map((k, i) => ({ place: k.place, fullG: IL.num('pcal-full-' + (i + 1)), emptyG: IL.num('pcal-empty-' + (i + 1)) }));
    const bad = list.find((x) => !(x.fullG > 0) || !(x.emptyG > 0));
    if (bad) { IL.toast('Type both readings of ' + bad.place + ' in grams (with paste, then wiped).', 'need'); return; }
    await IL.act(btn, async () => {
      try {
        const r = await answer({ step: 'readings', coupons: list, confirm: !!confirm });
        C.confirm = null;
        return r;
      } catch (e) {
        if (e.status === 409 && e.data && (e.data.error === 'implausible' || e.data.error === 'late')) C.confirm = e.message;
        throw e;
      }
    }, { ok: 'Readings saved: it decides and plans again.', refusalIn: $('pcal-ask-ref') });
    IL.render();
  }
  function renderJob(s) {
    const j = job(s);
    if (!j) { IL.setText($('pcal-live'), 'The pad calibration is no longer known (OpenPnP restarted): its coupons are in Calibrate › Pad calibration.'); return; }
    if (!IL.ssJson(JOB_KEY, null) || ACTIVE.has(j.state)) IL.ssSet(JOB_KEY, j.id);
    const active = ACTIVE.has(j.state);
    const run = s.run && s.run.running && s.run.label === 'pad calibration' ? s.run : null;
    // phases
    const rows = (j.steps || []).map((x) => {
      const st = x.state === 'now' ? 'now' : x.state === 'done' ? 'done' : x.state === 'skipped' ? 'skip' : x.state === 'failed' || x.state === 'stopped' ? 'cut' : 'todo';
      let d = x.text || '';
      let v = '';
      if (x.state === 'now' && run) {
        d = String(run.phase || '').replace(/^pad calibration:\s*/, '') || d;
        // dots laid, as the strip counts them (the camera looks before the first one)
        if (run.dotsPlanned) v = Math.min(run.dotsPlanned, run.dotsDone || 0) + ' of ' + IL.plural(run.dotsPlanned, 'dot');
      }
      if (x.key === 'readings' && x.state === 'now') { d = 'waiting for you since ' + IL.fmtTime(IL.toClient(j.awaiting && j.awaiting.sinceMs)); }
      return { k: x.key, st, t: STEP_TITLE[x.key] || x.key, d, v };
    });
    IL.keyed($('pcal-phases'), JSON.stringify(rows), () => rows.map((x, i) => el('li', { class: x.st === 'done' ? '' : x.st, 'data-ph': x.k },
      x.st === 'done' ? IL.glyph('ok', 'sm') : x.st === 'now' ? IL.glyph(x.k === 'readings' ? 'need' : 'live', 'sm') : x.st === 'skip' ? IL.glyph('off', 'sm', 'Skipped')
        : x.st === 'cut' ? IL.glyph('block', 'sm') : IL.glyph('num', 'sm', 'Step ' + (i + 1), i + 1),
      el('div', {}, el('div', { class: 'ph__t', text: x.t }), x.d ? el('div', { class: 'ph__d', text: x.d }) : null), el('span', { class: 'ph__v', text: x.v }))));
    const live = $('pcal-live');
    const ltext = active && j.state !== 'awaiting-reading' && isNum(j.estimatedSecondsLeft) ? '≈ ' + IL.fmtClock(j.estimatedSecondsLeft) + ' left' : '';
    IL.setText(live, ltext);
    IL.show(live, !!ltext);
    // the readings prompt
    const ask = $('pcal-ask');
    const asking = j.state === 'awaiting-reading' && j.awaiting;
    IL.show(ask, !!asking);
    if (asking) renderAsk(j, s);
    // the result
    const res = j.result;
    const box = $('pcal-result');
    IL.show(box, !!res && !active);
    IL.show($('pcal-result-more'), !!res && !active && ((res.groups || []).length > 0 || (res.crops || []).length > 0));
    if (res && !active) renderResult(j, res, s);
    // pending coupons of a stopped job
    const pend = (j.pendingCoupons || []).filter((x) => !x.error);
    pendingForm($('pcal-pending'), !active && C.pending ? pend : []);
    // ladder table (once the blank has dots)
    const lad = j.ladder || [];
    IL.show($('pcal-ladder-box'), lad.some((x) => x.dots > 0));
    IL.keyed($('pcal-ladder').tBodies[0], JSON.stringify(lad), () => (lad.length ? lad.map((x) => el('tr', { 'data-steps': x.steps },
      el('td', { text: turnAdj(x.steps) }),
      el('td', { class: 'r nowrap', text: x.dots ? (isNum(x.found) ? x.found + '/' : '') + x.dots : '—' }),
      el('td', { class: 'r nowrap', text: isNum(x.hops) ? (x.threads || 0) + '/' + x.hops : '—' }),
      el('td', { class: 'r', text: isNum(x.residCv) ? IL.fmt(100 * x.residCv, 0) + ' %' : '—' }),
      el('td', { class: 'r nowrap', text: isNum(x.phase) ? IL.fmt(100 * x.phase, 0) + ' %' + (isNum(x.phaseLimit) ? ' (≤ ' + IL.fmt(100 * x.phaseLimit, 0) + ' %)' : '') : '—' }),
      el('td', { class: 'r nowrap', text: isNum(x.dMeanMm) ? IL.fmt(x.dMeanMm, 2) + ' / ' + IL.fmt(x.d95Mm, 2) + ' mm' : '—' }),
      el('td', {}, x.pass === null || x.pass === undefined ? el('span', { class: 'muted', text: x.dots ? '…' : 'not yet' })
        : el('span', { class: 'pp-pass' }, IL.glyph(x.pass ? 'ok' : 'block', 'xs'), x.pass ? 'pass' : (x.failures && x.failures[0]) || 'fail'))))
      : [el('tr', {}, el('td', { colspan: '7', class: 'muted', text: 'No test dots yet.' }))]));
    // foot
    const ended = !active;
    // the coupons of a stopped job: listed once they can be weighed (a simulator's records count while it is connected)
    const offline = !(s.machine && s.machine.enabled);
    const weighNow = ended && !C.pending && (pend.length > 0 || (!!res && !!res.pendingCoupons && offline));
    const finishNow = ended && !!(res && res.finishable) && !weighNow;
    const good = !!res && (res.outcome === 'characterized' || res.outcome === 'measured');
    IL.show($('btn-pcal-plan'), ended && !!(res && res.newPlanId));
    IL.show($('btn-pcal-weigh-now'), weighNow);
    IL.show($('btn-pcal-finish'), finishNow);
    IL.show($('btn-pcal-again'), ended && !!res && res.outcome !== 'characterized');
    // one clear next action: the readings still to type, else finishing, else Checks after a characterization, else again
    const lead = weighNow ? 'btn-pcal-weigh-now' : finishNow ? 'btn-pcal-finish' : good ? 'btn-pcal-plan' : 'btn-pcal-again';
    for (const id of ['btn-pcal-weigh-now', 'btn-pcal-finish', 'btn-pcal-plan', 'btn-pcal-again']) IL.setClass($(id), 'btn' + (id === lead ? ' btn--primary' : ''));
    IL.gate($('btn-pcal-weigh-now'), weighNow && !pend.length ? IL.lockReason(s, { idle: false, pressureOff: false, connected: true }) || 'Reading the coupons…' : null, $('pcal-foot-why'));
    IL.show($('btn-pcal-manual'), ended && !!res && res.outcome !== 'characterized' && !j.manual);
    IL.show($('pcal-stopline'), active && j.state !== 'awaiting-reading');
    IL.show($('btn-pcal-cancel'), active);
    IL.show($('btn-pcal-readings'), !!asking);
    for (const id of ['btn-pcal-again', 'btn-pcal-finish']) IL.gate($(id), jobLockText(s));
  }
  function renderAsk(j, s) {
    const a = j.awaiting;
    IL.setText($('pcal-ask-text'), IL.nb(a.text));
    const cs = a.coupons || [];
    IL.keyed($('pcal-coupons'), JSON.stringify([j.id, cs.map((k) => [k.place, k.recordId, k.crop && k.crop.after])]), () => cs.map((k, i) => {
      const n = i + 1;
      const inp = (id, label) => field(id, label, 'g', el('input', { class: 'input', id, type: 'text', inputmode: 'decimal', autocomplete: 'off', spellcheck: 'false', placeholder: '0.000' }));
      return el('div', { class: 'pcal-coupon', 'data-place': k.place },
        k.crop && k.crop.after ? el('img', { class: 'pcal-coupon__img', id: 'pcal-coupon-' + n, src: k.crop.after, alt: k.label + ': ' + k.dots + ' ' + turnAdj(k.steps) + ' dots (camera, after)', width: '72', height: '72' })
          : el('div', { class: 'pcal-coupon__img pcal-coupon__img--none', id: 'pcal-coupon-' + n, text: 'no picture' }),
        el('div', { class: 'pcal-coupon__f' },
          el('div', { class: 'pcal-coupon__t' }, el('b', { text: k.label }), ' (' + k.place + ') · ' + IL.plural(k.dots, turnAdj(k.steps) + ' dot') + ' · about ' + k.expectedMg + ' mg'),
          el('div', { class: 'pcal-coupon__in' }, inp('pcal-full-' + n, 'With paste'), inp('pcal-empty-' + n, 'Wiped')),
          el('div', { class: 'pcal-coupon__mg', id: 'pcal-mg-' + n, 'aria-live': 'polite' })));
    }));
    cs.forEach((k, i) => {
      const n = i + 1, f = IL.num('pcal-full-' + n), e = IL.num('pcal-empty-' + n);
      const ok = f > 0 && e > 0;
      const net = ok ? (f - e) * 1000 : null;
      IL.setText($('pcal-mg-' + n), ok ? '= ' + IL.fmt(net, 0) + ' mg of paste' + (k.dots ? ' (' + IL.fmt(net / k.dots, 3) + ' mg per dot)' : '') : 'Type both readings in grams.');
    });
    IL.setText($('btn-pcal-readings'), C.confirm ? 'Save anyway' : 'Save the readings');
  }
  /**
   * The readings of coupons whose job is gone (stopped, cancelled, a restart): POST /api/coupons/{id}/reading each; or
   * Skip (two presses, {skip: true}): no readings, the coupon's place is free again once its dots are wiped off.
   */
  function pendingForm(host, pend) {
    if (!host) return;
    IL.show(host, pend.length > 0);
    if (!pend.length) { IL.keyed(host, '', () => null); return; }
    const done = () => IL.emit('coupon-reading');
    IL.keyed(host, JSON.stringify(pend.map((x) => x.recordId)), () => [IL.sectionK('Coupons to weigh'),
      ...pend.map((k) => {
        const save = el('button', { type: 'button', class: 'btn btn--primary btn--sm', 'data-record': k.recordId }, 'Save');
        const skip = el('button', { type: 'button', class: 'btn btn--sm', 'data-skip': k.recordId, title: 'Do not weigh ' + k.place + ': its dots count for nothing; wipe them off before it is used again' }, 'Skip');
        const fid = 'pcal-pf-' + k.recordId, eid = 'pcal-pe-' + k.recordId;
        save.addEventListener('click', () => IL.act(save, async () => { const r = await IL.api('POST', '/api/coupons/' + k.recordId + '/reading', { fullG: IL.num(fid), emptyG: IL.num(eid) }); done(); return r; },
          { ok: (r) => r.text + '.' }));
        IL.press2(skip, 'skip weighing', async () => { const r = await IL.api('POST', '/api/coupons/' + k.recordId + '/reading', { skip: true }); done(); return r; },
          { ok: (r) => IL.cap(r.text) + '.' });
        return el('div', { class: 'pcal-coupon pcal-coupon--pending', 'data-refusals': '', 'data-enter-submits': '' },
          el('div', { class: 'pcal-coupon__f' },
            el('div', { class: 'pcal-coupon__t' }, el('b', { text: k.place }), ' · ' + IL.plural(k.dots, turnAdj(k.steps) + ' dot') + ' · about ' + k.expectedMg + ' mg · until ' + IL.fmtTime(IL.toClient(k.staleAtMs))),
            el('div', { class: 'pcal-coupon__in' },
              field(fid, 'With paste', 'g', el('input', { class: 'input', id: fid, type: 'text', inputmode: 'decimal', autocomplete: 'off', placeholder: '0.000' })),
              field(eid, 'Wiped', 'g', el('input', { class: 'input', id: eid, type: 'text', inputmode: 'decimal', autocomplete: 'off', placeholder: '0.000' })), save, skip)));
      })]);
  }
  /** A decision check in words when the engine gave none (§7.6: key, predicted, measured, limit). */
  const CHECK_WORD = {
    optical: (k) => 'Camera checks (the blank and the coupons)' + (k.measured ? ': ' + k.measured : ''),
    mass: (k) => 'Weighed ' + (k.measured || '—') + (k.predicted ? ' (predicted ' + k.predicted + ')' : '') + (k.limit ? '; ' + k.limit : ''),
    amount: (k) => 'Amount ' + (k.measured || '—') + (k.predicted ? ' (target ' + k.predicted + ', ' + (k.limit || '') + ')' : ''),
    r6: (k) => (k.measured || '') + (k.limit ? ' (' + k.limit + ')' : ''),
    geometry: (k) => (k.measured || 'Geometry') + (k.limit ? ' (limits ' + k.limit + ')' : ''),
    control: () => 'The control dot matches the bench’s half turn',
    tip: (k) => 'Calibrated with the mounted tip' + (k.limit ? ' (' + k.limit + ')' : ''),
    regulator: () => 'The regulator read at the profile’s pressure',
    validated: () => 'No validated pad type is changed',
  };
  const checkText = (k) => k.text || (CHECK_WORD[k.key] ? CHECK_WORD[k.key](k) : [k.key, k.measured, k.limit].filter(Boolean).join(': '));
  const OUT_KIND = { characterized: 'ok', partly: 'need', provisional: 'need', measured: 'ok', stopped: 'block', cancelled: 'need', failed: 'block' };
  /**
   * "Characterized 14:05: SW … planned again." → the verdict and its time in bold, the rest as the sentence goes on (a
   * number keeps its unit on its line: "9 %", "2.29 mg").
   */
  function headSplit(t) {
    const m = /^(\S+(?: \S+)? \d\d:\d\d(?: during [^:]+)?:)(\s[\s\S]*)$/.exec(String(t || ''));
    return m ? [el('b', { text: m[1] }), IL.nb(m[2])] : [IL.nb(String(t || ''))];
  }
  function renderResult(j, res, s) {
    const kind = OUT_KIND[res.outcome] || 'need';
    const box = $('pcal-result');
    IL.setClass(box, 'ri ri--' + kind);
    IL.setAttr(box, 'data-outcome', res.outcome);
    // the purge row after the job: a board needs its spots (the plan made again counts them, IL.purgeNeed)
    const row = (s && s.dispense && s.dispense.purgeRow) || {};
    const short = row.defined && row.spotsLeft < IL.purgeNeed(row);
    IL.keyed(box, JSON.stringify([res.sentence, res.control && res.control.drift, res.planError, j.warnings, short, row.spotsLeft]), () => [IL.glyph(kind),
      el('div', {}, el('div', { class: 'ri__t', id: 'pcal-result-t' }, headSplit(res.sentence)),
        // the date of the weighing stays on one line too (2026-10-27: no-break hyphens)
        res.control && res.control.drift ? el('div', { class: 'ri__d', text: IL.nb(IL.cap(res.control.drift) + '.').replace(/(\d{4})-(\d\d)-(\d\d)/g, '$1\u2011$2\u2011$3') }) : null,
        res.planError ? el('div', { class: 'ri__d', text: 'Not planned again: ' + res.planError }) : null,
        Array.from(new Set(j.warnings || [])).slice(0, 3).map((w) => el('div', { class: 'ri__d', text: IL.nb(IL.cap(w) + '.') })),
        short ? el('div', { class: 'ri__d', id: 'pcal-result-purge' }, 'Next: wipe the purge row and reset it before the next board (' + row.spotsLeft + ' of '
          + IL.plural(row.spots, 'spot') + ' left, ' + IL.purgeNeed(row) + ' needed). ', IL.fixButton({ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }, { cls: 'btn--sm' })) : null)]);
    IL.keyed($('pcal-groups'), JSON.stringify(res.groups), () => (res.groups || []).map((g) => {
      const ok = g.outcome === 'characterized';
      const checks = g.checks || [];
      return el('li', { 'data-group': g.group, 'data-outcome': g.outcome || '' },
        el('div', { class: 'pcal__gh' }, IL.glyph(ok ? 'ok' : 'need', 'xs'), el('b', { text: g.label })),
        g.typeKey ? el('div', { class: 'pcal__sd', text: g.typeKey + (g.version ? ' · version ' + g.version : '') + (g.outcome ? ' · ' + (STATUS_WORD[g.outcome] || g.outcome) : '') }) : null,
        g.text && (res.groups || []).length > 1 ? el('div', { class: 'pcal__sd', text: g.text }) : null,
        el('dl', { class: 'kv kv--wrap pcal__kv' }, [
          ['Per pad', isNum(g.perPad) ? g.perPad + ' ' + turnAdj(g.steps) + ' ' + (g.perPad === 1 ? 'dot' : 'dots') : '—'],
          ['Amount', (isNum(g.mgPerPad) ? IL.fmt(g.mgPerPad, 2) + ' mg' : '—') + (isNum(g.targetMg) ? ' (target ' + IL.fmt(g.targetMg, 2) + ' mg' + (isNum(g.errorPct) ? ', ' + (g.errorPct >= 0 ? '+' : '−') + Math.abs(Math.round(g.errorPct)) + ' %' : '') + ')' : '')],
          ['Dot size', isNum(g.dMeanMm) ? IL.fmt(g.dMeanMm, 2) + ' mm (95 % of the dots under ' + IL.fmt(g.d95Mm, 2) + ' mm)' : '—'],
          ['Spread', isNum(g.residCv) ? IL.fmt(100 * g.residCv, 0) + ' % dot to dot' + (isNum(g.phase) ? ' · ' + IL.fmt(100 * g.phase, 0) + ' % between the screw angles'
            + (isNum(g.phaseLimit) ? ' (at most ' + IL.fmt(100 * g.phaseLimit, 0) + ' %)' : '') : '') : '—'],
          ['Threads', isNum(g.hops) ? (g.threads || 0) + ' in ' + g.hops + ' moves' : '—'],
          ['Weighed', isNum(g.massReadings) ? IL.plural(g.massReadings, 'reading') + (g.massConfirmed ? ', confirmed' : ', not confirmed yet') : '—'],
        ].flatMap(([k, v]) => [el('dt', { text: k }), el('dd', { text: IL.nb(v) })])),
        checks.length ? el('ul', { class: 'pcal__checks' }, checks.slice().sort((a, b) => (a.ok ? 1 : 0) - (b.ok ? 1 : 0)).map((k) => el('li', { class: k.ok ? null : 'bad', 'data-check': k.key },
          IL.glyph(k.ok ? 'ok' : 'block', 'xs'), el('span', { text: checkText(k) })))) : null);
    }));
    // the blank's crops (first, median, last of each dose); the coupons' only when the blank has none
    const all = res.crops || [];
    const blankCrops = all.filter((c) => !c.weigh);
    const crops = (blankCrops.length ? blankCrops : all).slice(0, 9);
    const ck = JSON.stringify(crops.map((c) => c.id));
    if (C.cropsKey !== ck) {
      C.cropsKey = ck;
      $('pcal-crops').replaceChildren(...(crops.length ? crops.map((c) => el('figure', { class: 'pcal-crop' },
        el('img', { src: c.before, alt: c.alt + ', before', width: '72', height: '72', loading: 'lazy' }),
        el('img', { src: c.after, alt: c.alt, width: '72', height: '72', loading: 'lazy' }),
        el('figcaption', { text: turnAdj(c.steps) + ' · dot ' + c.dot + (c.weigh ? ' · coupon' : '') }))) : [el('p', { class: 'muted', text: 'No crops in this calibration.' })]));
    }
    const pred = res.predictions || [];
    IL.keyed($('pcal-pred').tBodies[0], JSON.stringify(pred), () => pred.map((p) => el('tr', {},
      el('td', { class: 'nowrap', text: p.id }), el('td', { text: p.prediction + (p.band ? ' (' + p.band + ')' : '') }), el('td', { text: p.measured === null || p.measured === undefined ? '—' : String(p.measured) }),
      el('td', {}, p.pass === null || p.pass === undefined ? el('span', { class: 'muted', text: '—' }) : IL.glyph(p.pass ? 'ok' : 'block', 'xs')))));
    IL.show($('pcal-pred-d'), pred.length > 0);
    IL.setText($('pcal-drift'), res.control && res.control.text ? res.control.text : '');
  }
  /** A finished job's new plan, once per job id, when it is of the plan shown (AR's catch-up rule). */
  IL.on('status', (s) => {
    const j = job(s);
    if (!j || !j.result || !j.result.newPlanId || !j.planId) return;
    const done = IL.ssJson(LOADED_KEY, []);
    if (done.includes(j.id)) return;
    if (!IL.planMatches(j.planId) && !(S.plan && S.plan.planId === j.result.newPlanId)) return;
    IL.ssSet(LOADED_KEY, done.concat([j.id]).slice(-10));
    if (!S.plan || S.plan.planId !== j.result.newPlanId) IL.loadPlan(j.result.newPlanId);
    if (IL.pcr && IL.pcr.loadPadTypes) IL.pcr.loadPadTypes();
    refresh();
  });
  /** A job that this tab did not see start (a reload, a second browser): show it in the view. */
  IL.on('status', (s, prev) => {
    const j = job(s), pj = job(prev);
    if (j && ACTIVE.has(j.state) && (!pj || pj.id !== j.id || pj.state !== j.state) && IL.panel.isPushed('pad-cal')) IL.render();
    if (j && j.state === 'awaiting-reading' && (!pj || pj.state !== 'awaiting-reading')) IL.toast('Pad calibration: the coupons are ready to weigh.', 'need');
  });

  // ================================================================================== upload preview and save (H-P6)
  /**
   * State 3 (H-P6): BI's "Pad type" column becomes "Profile": per package its reason (span.pp-badge[data-reason]) and
   * the suggestion, or "ready" and its pad types; the packages that need a profile first, then those the tip cannot
   * dispense, then the ready ones and the fiducials.
   */
  function importRows(table, pv) {
    if (!table || !pv || !(pv.packages || []).some((p) => 'profileCheck' in p)) return;
    const pk = new Map((pv.packages || []).map((p) => [p.id, p]));
    const head = table.tHead && table.tHead.rows[0];
    const th = head && head.cells[head.cells.length - 1];
    if (th && !th.classList.contains('bi-pp')) { th.classList.add('bi-pp'); th.textContent = 'Profile'; table.classList.add('bi-pkgs--pp'); }
    const body = table.tBodies[0];
    const rows = Array.from(body.rows).map((tr, i) => ({ tr, i, p: pk.get(tr.dataset.package) }));
    for (const { tr, p } of rows) {
      const td = tr.cells[tr.cells.length - 1];
      if (!p || !td || td.classList.contains('bi-pp')) continue;
      td.classList.add('bi-pp');
      td.replaceChildren(...profileCell(p));
    }
    const rank = (p) => (!p ? 5 : p.fiducial ? 4 : FIX.has(p.profileReason) ? 0 : p.profileReason === 'outside' ? 1 : TIPS.has(p.profileReason) ? 2 : 3);
    rows.sort((a, b) => rank(a.p) - rank(b.p) || a.i - b.i);
    body.append(...rows.map((x) => x.tr));
    typeLabels(() => {
      for (const n of table.querySelectorAll('[data-type-key]')) IL.setText(n, P.labels.get(n.dataset.typeKey) || n.textContent);
    });
    needsWarning(table.closest('#dlg-board-import'), pv);
  }
  /**
   * The NEEDS_PROFILE warning as one line per reason ("Not calibrated yet: LED_0805_2012Metric") instead of one
   * sentence naming every package with its reason in brackets; the server's sentence stays the item's title.
   */
  function needsWarning(dlg, pv) {
    const li = dlg && dlg.querySelector('li[data-code="NEEDS_PROFILE"]');
    if (!li || li.dataset.pp === '1') return;
    // the reasons a profile fixes first, those of the tip last
    const rank = (r) => (FIX.has(r) ? 0 : r === 'outside' ? 1 : TIPS.has(r) ? 3 : 2);
    const by = new Map();
    for (const p of (pv.packages || []).filter((x) => x.profileShort).sort((a, b) => rank(a.profileReason) - rank(b.profileReason))) {
      if (!by.has(p.profileShort)) by.set(p.profileShort, []);
      by.get(p.profileShort).push(base(p.id));
    }
    if (!by.size) return;
    const n = Array.from(by.values()).reduce((a, l) => a + l.length, 0);
    li.dataset.pp = '1';
    IL.setAttr(li, 'title', li.textContent);
    li.replaceChildren(el('div', { text: IL.plural(n, 'package needs', 'packages need') + ' a profile:' }),
      el('ul', { class: 'pp-needs-w' }, Array.from(by, ([short, ids]) => el('li', {}, el('b', { text: IL.cap(short) + ': ' }), breakable(ids.join(', '))))),
      el('div', { text: 'After saving, choose a suggestion for each in Profile › Needs a profile.' }));
  }
  /** A package's profile cell: its badge, then the suggestion (a fixable reason) or its pad types (ready). */
  function profileCell(p) {
    const badge = (reason, kind, text) => el('span', { class: 'pp-badge', 'data-reason': reason }, IL.glyph(kind, 'xs'), el('span', { text }));
    if (p.fiducial) return [badge('fiducial', 'off', 'fiducial: no paste')];
    const r = p.profileReason;
    const types = (p.padTypes || []).filter((t) => t.typeKey);
    if (!r) {
      if (!types.length) return [badge('no-type', 'need', 'no pad type')];
      return [badge('ready', 'ok', 'ready'), el('div', { class: 'pp-cell__s' }, types.map((t, i) => [i ? ', ' : '',
        el('span', { 'data-type-key': t.typeKey, text: (P.labels && P.labels.get(t.typeKey)) || t.name || t.typeKey }),
        t.status ? ' · ' + (STATUS_WORD[t.status] || t.status) : '']))];
    }
    const out = [badge(r, r === 'outside' ? 'info' : TIPS.has(r) ? 'block' : 'need', p.profileShort || r)];
    const g = FIX.has(r) ? (p.profileCheck || []).find((x) => x.reason === r && x.suggestion && x.suggestion.kind && x.suggestion.kind !== 'none') : null;
    if (g) out.push(el('div', { class: 'pp-cell__s', text: 'Suggested: ' + suggWords(g) + '.' }));
    return out;
  }
  /** The suggestion in words for the upload preview: "use Chip 0603", "a new pad type, 2 half turns per pad". */
  function suggWords(g) {
    const s = g.suggestion, d = s.dots || {};
    if (s.kind === 'use') return 'use ' + typeLabel(s);
    const per = isNum(d.perPad) ? turnWords(d.perPad, d.steps) + ' per pad' : '';
    return (s.kind === 'new' ? 'a new pad type' : 'calibrate ' + typeLabel(s)) + (per ? ', ' + per : '');
  }
  /** The pad types' names with their family ("Chip 0603"), read once; then done() (the upload preview's ready rows). */
  function typeLabels(done) {
    if (P.labels) { done(); return; }
    if (P.labelsLoading) { P.labelsLoading.then(done); return; }
    P.labelsLoading = IL.api('GET', '/api/pad-types').then((r) => {
      const m = new Map();
      for (const f of (r && r.families) || []) for (const t of f.types || []) m.set(t.typeKey, typeLabel({ family: t.family || f.family, name: t.name, typeKey: t.typeKey }));
      P.labels = m;
    }, () => { P.labels = new Map(); }).then(() => { P.labelsLoading = null; });
    P.labelsLoading.then(done);
  }
  IL.on('padtypes', () => { P.labels = null; });
  /** State 6: the packages that need a profile, "Use Chip 0603" (the mapping) and "Needs a profile…"; null when none. */
  function importSaved(pv, o) {
    const pkgs = (pv.packages || []).filter((p) => (p.profileCheck || []).some((g) => !TIPS.has(g.reason) && g.reason !== 'outside'));
    if (!(pv.packages || []).some((p) => 'profileCheck' in p)) return null;
    if (!pkgs.length) return el('div', { id: 'board-import-profile', class: 'bi-todo' }, el('p', { class: 'muted', text: 'Every pad the tip can dispense has a profile.' }));
    const rows = [];
    for (const p of pkgs) {
      for (const g of p.profileCheck || []) {
        if (TIPS.has(g.reason) || g.reason === 'outside') continue;
        const s = g.suggestion || {};
        const btns = [];
        if (s.kind === 'use') {
          const use = el('button', { type: 'button', class: 'btn btn--sm btn--primary', 'data-group': g.group, 'data-action': 'use' }, 'Use ' + typeLabel(s));
          use.addEventListener('click', () => IL.act(use, async () => {
            const r = await IL.api('POST', '/api/package-mappings', { packageId: s.mapping.packageId, padNames: s.mapping.padNames || null, typeKey: s.typeKey });
            IL.patch(use, { text: 'Uses ' + typeLabel(s), disabled: true });
            use.dataset.done = '1';
            if (IL.pcr && IL.pcr.loadPadTypes) IL.pcr.loadPadTypes();
            return r;
          }, { ok: p.id + ' uses ' + typeLabel(s) + ' now.' }));
          btns.push(use);
        }
        const needs = el('button', { type: 'button', class: 'btn btn--sm', 'data-group': g.group, 'data-action': 'needs' }, 'Needs a profile…');
        needs.addEventListener('click', () => {
          setSource({ boardFile: o.file, boardName: o.name });
          o.leaveTo('#profile/needs/' + g.group);
        });
        btns.push(needs);
        rows.push(el('li', { 'data-group': g.group }, el('div', { class: 'bi-todo__t' }, el('b', {}, breakable(p.id)),
          el('span', { class: 'muted', text: ' · ' + refList(p.refs, 3) + ' · ' + g.short + (s.kind && s.kind !== 'none' ? ' · ' + suggShort(g) : '') })), el('div', { class: 'bi-todo__b' }, btns)));
      }
    }
    return el('div', { id: 'board-import-profile', class: 'bi-todo', 'data-refusals': '' },
      el('h3', { text: IL.plural(pkgs.length, 'package needs', 'packages need') + ' a profile:' }), el('ul', {}, rows));
  }

  // ================================================================================== pad inspector (H-P7)
  /** One notebox and one set of actions for a pad whose group needs a profile; returns true when shown. */
  function inspectorBlock(host, pad, plan) {
    if (!host) return false;
    const pc = plan && plan.profileCheck;
    const g = pad && pc && (pc.groups || []).find((x) => x.group === pad.profileGroup);
    IL.show(host, !!g);
    if (!g) { IL.keyed(host, '', () => null); return false; }
    const c = { kind: 'plan', check: pc, planId: plan.planId };
    IL.keyed(host, JSON.stringify([g.group, g.reason, g.suggestion && g.suggestion.id]), () => {
      const s = g.suggestion || {};
      const btns = [];
      if (usable(g) && FIX.has(g.reason)) {
        const use = el('button', { type: 'button', class: 'btn btn--sm btn--primary', id: 'btn-pad-insp-use' }, 'Use ' + typeLabel(s) + ' · plans again');
        use.addEventListener('click', () => useOne(use, c, g));
        btns.push(use);
      }
      if (calibrates(g) && (FIX.has(g.reason) || g.reason === 'outside')) {
        const auto = el('button', { type: 'button', class: 'btn btn--sm btn--primary', id: 'btn-pad-insp-auto' }, 'Calibrate automatically…');
        auto.addEventListener('click', () => openCal(calRequest(c, [g])));
        const man = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-pad-insp-manual' }, 'I’ll do it by hand');
        man.addEventListener('click', () => manual(man, c, g));
        btns.push(auto, man);
      }
      const more = el('button', { type: 'button', class: 'link', text: 'Suggestion in Profile ›' });
      more.addEventListener('click', () => IL.go('#profile/needs/' + g.group));
      return el('div', { class: 'notebox notebox--' + reasonKind(g) + ' pp-insp', 'data-refusals': '' }, IL.glyph(reasonKind(g), 'xs'),
        el('div', {}, el('div', { text: g.reasonText }), s.sentence ? el('div', { class: 'pp-insp__s', text: s.sentence }) : null,
          btns.length ? el('div', { class: 'btn-row' }, btns) : null, el('div', {}, more)));
    });
    IL.gate($('btn-pad-insp-use'), idle(S.status));
    for (const id of ['btn-pad-insp-auto', 'btn-pad-insp-manual']) IL.gate($(id), calIdle(S.status));
    return true;
  }
  /** The amount line of the pad inspector: what the plan gives the pad, against the target and a stencil. */
  function amountText(pad) {
    if (!pad || !isNum(pad.anchorMassMg)) return null;
    const target = 'target ' + IL.fmt(pad.anchorMassMg, 2) + ' mg at your 0603 rate';
    const stencil = isNum(pad.targetMassMg) ? '; a ' + IL.fmt(pad.tMm, 2) + ' mm stencil would print ' + IL.fmt(pad.targetMassMg, 2) + ' mg' : '';
    if (!pad.dotCount || !isNum(pad.predictedMassMg) || !pad.dotSteps) return 'No dots planned (' + target + stencil + ').';
    const n = pad.dotCount;
    return 'Planned: ' + n + ' ' + turnAdj(pad.dotSteps) + ' ' + (n === 1 ? 'dot' : 'dots') + ' × ' + IL.fmt(pad.predictedMassMg / n, 2) + ' mg = '
      + IL.fmt(pad.predictedMassMg, 2) + ' mg (' + target + stencil + ').';
  }

  IL.padProfile = { groups: (plan) => split(plan && plan.profileCheck), split, padTypesItem, setSource, current, mountNeeds, profileHooks,
    importRows, importSaved, inspectorBlock, amountText, openCal, startManual, pendingForm, taskParts, verdict, jobLock, jobActive, needsHref, estimate,
    turnAdj, typeLabel };
})();
