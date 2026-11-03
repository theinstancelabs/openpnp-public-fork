/* run.js: the Run workspace (spec docs/instancelabs/UI_DESIGN.md §8.1): the per-board loop on one screen.
 *
 * Main column: page head (board, seat, Change board / Plan again), the hero card (plan chip, recipe token, the to-scale
 * board from boardview.js, stage bar, session timeline while running) and its footer: plan summary tiles (idle),
 * live tiles (running), result tiles (after a board) or the board decision (after STOP).
 * Context panel: the five-step stepper Board → Register → Checks → Run → Result, each step a panel view
 * (data-view board|probe|checks|run|result: Register keeps the key probe), and Recovery (ownsRecovery) after STOP /
 * ABORTED / ERROR. Register board (docs/instancelabs/AUTO_REGISTER_DESIGN.md §8): home if needed → fiducials → N1 probe →
 * plan again, live progress and a result card; "Do it by hand" keeps the manual probe. The run confirm #dlg-run is an
 * in-page dialog (IL.dialog.open), never a native one.
 *
 * Contract: DEV_NOTES.md (IL.workspace, IL.ready, IL.press2, IL.fix, IL.panel ...). Styles: run.css. Registers the fix-its
 * plan-again, clean-check (two-press), probe, register, register-check, probe-by-hand, look-camera, plan-unregistered,
 * board-step and check-z. The board-import hooks (IL.boardFlow, BOARD_IMPORT_DESIGN.md §5.1.1 H1–H3) are no-ops until
 * boardflow.js loads.
 */
'use strict';
(function () {
  const { el, $, S } = IL;
  const { isNum } = IL;
  const BENCH_DPH = 5490;          // bench, 2026-10-28, 18 psi half-turn recipe, dots only
  const PER_PAD_S = 1.19;          // camera tour per pad when no measurement exists (bench: 160 pads ≈ 3:10)
  const VENT_S = 1;
  const STEPS = ['board', 'probe', 'checks', 'run', 'result'];
  const STEP_T = { board: 'Board', probe: 'Register', checks: 'Checks', run: 'Run', result: 'Result' };
  const STATUS_WORD = { OK: 'Finished', ABORTED: 'Stopped', ERROR: 'Ended by an error', REFUSED: 'Refused', RUNNING: 'Running' };
  const lsGet = IL.lsGet, lsSet = IL.lsSet, lsJson = IL.lsJson;
  const ssGet = (k) => IL.ssJson(k, null), ssSet = IL.ssSet;

  // ================================================================================== module state
  const R = {
    root: null, bv: null, n: {},              // n: main-column nodes
    boards: null, boardsErr: null, boardsLoading: false,
    form: { sel: null, file: '', x: '', y: '', z: '', rot: '0' },
    formDirty: false,
    seatSame: null,                            // remembered-entry atMs the operator said "same seat" for
    section: null,                             // step chosen in the route (#run/<step>), else automatic
    built: null,                               // step built in the panel now
    pending: null,                             // the plan kept while the Board step has another board chosen (main column: that board)
    resultRunId: null,                         // show Result after this board run (until the operator moves on)
    refused: null,                             // a board run of this plan ended REFUSED: banner on Checks
    ccRefused: null,                           // a clean-pad check of this plan refused at its start: its reason on the card
    test: false, maxDots: '',                  // "Test run with fewer dots" (cleared after every run)
    doneSet: new Set(),                        // dots of the plan shown with paste on its board (server: plan.pasted, trackDone)
    pasteVersion: null,                        // status.dispense.pasteVersion seen (a "Next board" or wipe elsewhere: read the plan again)
    maps: {},                                  // subset planId → [full-plan dot index per subset index]
    ph: { runId: null, at: {}, firstDotAt: null, endAt: null },
    probe: Object.assign({ seat: null, points: [] }, ssGet('il.run.probe') || {}),
    probeUntied: false,
    byHand: false,                             // Register step: "Do it by hand" open (panel state, not a route parameter)
    regResultRunId: ssGet('il.run.regResult') || null,   // the Register board result card shown (until Checks ›, a new plan, another task)
    regLoaded: {},                             // registration run ids whose new plan this tab loaded
    regDetail: null,                           // the last live detail of a registration {runId, detail} (stopped texts)
    dlg: null,
    decidedAt: Number(ssGet('il.run.decidedAt')) || 0,
    view: 'dots', hidden: new Set(),
    showAllDirty: false,
    kinds: ssGet('il.run.kinds') || {},        // run planId (subset ids too) → 'test' | 'rest' | 'all' | 'full' (this page started it)
  };
  IL.run = R;   // console / tests: IL.run

  // ================================================================================== small helpers
  const plan = () => S.plan;
  const shortId = (p) => (p && p.planId ? String(p.planId).slice(0, 4) : '');
  const boardShort = (name) => String(name || 'board').replace(/\.board\.xml$/i, '.board').replace(/\.xml$/i, '');
  const runningBoard = (s) => !!(s && s.run && s.run.running && s.run.label === 'board');
  const boardRunOfPlan = (s) => (s && s.run && s.run.label === 'board' && IL.planMatches(s.run.planId) ? s.run : null);
  const cleanEnabled = (s) => !!(s && s.dispense && s.dispense.cleanPadCheck && s.dispense.cleanPadCheck.enabled);
  const fmtMmV = (v) => IL.fmt(v, 2);
  function units(text) {
    // "5 min 16 s" → <n>5</n><u>min</u><n>16</n><u>s</u>
    const out = [];
    for (const part of String(text).split(' ')) out.push(el('span', { class: /^[\d.,−-]+$/.test(part) ? 'n' : 'u', text: part }));
    return out;
  }
  function tile(k, v, sub, opts) {
    const o = opts || {};
    return el('div', { class: 'tile' + (o.cls ? ' ' + o.cls : ''), id: o.id || null, title: o.title || null },
      el('div', { class: 'tile__k' }, el('span', {}, k), o.aside ? el('span', { class: 'aside', text: o.aside }) : null),
      v ? el('div', { class: 'tile__v' }, v) : null, o.extra || null, sub ? el('div', { class: 'tile__s' }, typeof sub === 'string' ? IL.nb(sub) : sub) : null);
  }
  function pair(a, al, b, bl) {
    return el('div', { class: 'pair' },
      el('div', {}, el('span', { class: 'tile__v' }, el('span', { class: 'n', text: a })), el('span', { class: 'l', text: al })),
      el('div', {}, el('span', { class: 'tile__v' }, el('span', { class: 'n', text: b })), el('span', { class: 'l', text: bl })));
  }
  const dph = (n) => (isNum(n) && n > 0 ? IL.fmtInt(n) : '—');
  const dphAbbr = () => el('abbr', { title: 'Dots per hour', text: 'DPH' });
  function setBtnLabel(btn, nodes) {
    if (!btn || IL.isArmed(btn)) return;
    const sig = nodes.map((n) => (n instanceof Node ? (n.getAttribute && n.getAttribute('href')) || n.textContent : String(n))).join('|');
    if (btn.dataset.lbl === sig) return;
    btn.dataset.lbl = sig;
    btn.replaceChildren(...nodes.map((n) => (n instanceof Node ? n : document.createTextNode(String(n)))));
  }
  function fixBtn(f, cls, id) {
    const b = IL.fixButton(f, { cls });
    if (id) b.id = id;
    return b;
  }

  // ================================================================================== plan geometry, estimates
  /** Full-plan dot index of a subset plan's dot index (subsets keep the path order, re-indexed from 0). */
  function mapIndex(runPlanId, i) {
    const p = plan();
    if (!p) return null;
    if (runPlanId === p.planId) return i;
    const m = R.maps[runPlanId];
    if (Array.isArray(m)) return m[i] === undefined ? null : m[i];
    if (m === undefined) {
      R.maps[runPlanId] = 'loading';
      IL.api('GET', '/api/plan/' + encodeURIComponent(runPlanId)).then((q) => {
        const full = plan();
        if (!full) { delete R.maps[runPlanId]; return; }
        const key = (d) => d.padId + '|' + Number(d.x).toFixed(4) + '|' + Number(d.y).toFixed(4);
        const at = new Map(full.dots.map((d) => [key(d), d.index]));
        R.maps[runPlanId] = q.dots.map((d) => at.get(key(d)));
        IL.render();
      }).catch(() => { R.maps[runPlanId] = []; });
    }
    return null;
  }
  /** Seconds of the camera pad check for n pads: measured on this plan when possible, else the bench rate. */
  function tourSeconds(nPads) {
    const p = plan();
    const cc = S.cleanCheck;
    let per = PER_PAD_S;
    const row = (S.runs || []).find((r) => r.kind === 'clean-check' && p && IL.basePlanId(r.plan_id) === p.planId && isNum(r.total_s));
    if (row && cc && cc.targets > 0) per = Math.max(0.3, Math.min(5, row.total_s / cc.targets));
    return nPads * per;
  }
  /** Board time estimate for n dots on nPads pads: {total, tour, pressure, pre} seconds. */
  function estimate(s, nDots, nPads) {
    const p = plan();
    const sm = (p && p.summary) || {};
    if (!p || !isNum(sm.estimatedSeconds)) return null;
    const N = (p.dots || []).length || 1;
    const n = nDots === undefined ? N : nDots;
    const work = isNum(sm.estimatedWorkSeconds) ? sm.estimatedWorkSeconds : sm.estimatedSeconds;
    const pre = Math.max(0, sm.estimatedSeconds - work);
    const pressure = pre + work * n / N;
    const tour = cleanEnabled(s) ? tourSeconds(nPads === undefined ? (p.pads || []).length : nPads) : 0;
    return { total: pressure + tour, tour, pressure, pre, work: work * n / N };
  }
  /** Seat of a plan's source (file boards: file + X/Y/rotation; job boards: the board location). */
  function seatKey(src) {
    if (!src) return null;
    if (src.source === 'job') return 'job:' + src.boardLocationId;
    return 'file:' + src.file + '|' + IL.fmt(src.x, 3) + '|' + IL.fmt(src.y, 3) + '|' + IL.fmt(src.rotation || 0, 2);
  }
  function jobEntry(id) { return R.boards && (R.boards.job || []).find((b) => b.boardLocationId === id); }
  /** Where the plan's board sits: {x, y, z, rot, job, fid, side}. */
  function seatOf(p) {
    const src = p && p.source;
    if (!src) return null;
    if (src.source === 'job') {
      const b = jobEntry(src.boardLocationId);
      return b ? { x: b.x, y: b.y, z: b.z, rot: b.rotation, job: true, fid: b.fiducialTransform && b.fiducialTransform !== 'NotSet', side: b.side } : { job: true };
    }
    return { x: src.x, y: src.y, z: src.z, rot: src.rotation || 0, job: false };
  }
  function remembered(file) { return file ? lsJson('il.boardloc.' + file, null) : null; }
  /**
   * A board run of the board on the plan shown's seat (status.run with the server's boardNumber, rc1 review): the board
   * the plan read has, or a newer one its first dot numbered after the plan was read. Null otherwise (a "Next board"
   * took that run's board off).
   */
  function runOfThisBoard(s) {
    const p = plan(), run = s && s.run;
    const pa = (p && p.pasted) || {};
    if (!run || run.label !== 'board' || !IL.planMatches(run.planId) || !isNum(run.boardNumber)) return null;
    return run.boardNumber === pa.board || (isNum(pa.nextBoard) && run.boardNumber >= pa.nextBoard) ? run : null;
  }
  /**
   * The number this session of the board on the plan shown's seat: kept by the server with the board's paste
   * (plan.pasted: the same in every tab and browser; a plan made again for the same seat is the same board): the
   * board's own once it has paste, else the number the next board gets ("Next board" took the last one off).
   */
  function boardNumber() {
    const pa = (plan() && plan().pasted) || {};
    const run = runOfThisBoard(S.status);
    if (run) return run.boardNumber;
    return isNum(pa.board) ? pa.board : isNum(pa.nextBoard) ? pa.nextBoard : 1;
  }
  function runRow(r) {
    if (!r) return null;
    if (r.run_uuid !== undefined) {
      return { runId: r.run_uuid, kind: r.kind, planId: r.plan_id, boardName: r.board, status: r.status, dotsDone: r.dots_done,
        dotsPlanned: r.dots_planned, dphMotion: r.dph_motion, dphOverall: r.dph_overall, totalSeconds: r.total_s,
        motionSeconds: r.motion_s, startedAtMs: r.started_at, endedAtMs: r.ended_at, message: r.message };
    }
    return r;
  }
  /** The last board run (any plan) from GET /api/runs: never a prime, test dot or clean check. */
  function lastBoardRow() {
    const rows = (S.runs || []).filter((r) => r.kind === 'board');
    return runRow(rows.find((r) => r.dots_done > 0) || rows[0] || null);
  }
  /** The last finished board run of the plan shown (status.lastRun, else GET /api/runs). */
  function resultRun(s) {
    const lr = s && s.lastRun;
    if (lr && lr.kind === 'board' && IL.planMatches(lr.planId)) return runRow(lr);
    const row = (S.runs || []).find((r) => r.kind === 'board' && IL.planMatches(r.plan_id));
    return runRow(row);
  }
  /**
   * What a board run did, against the whole board: "all 160 dots", "20 of 160 dots (test)", "the 128 dots left · board
   * complete", "61 of 160 dots". p: the plan shown (used when the run belongs to it).
   */
  function outcomeText(r, p) {
    if (!r) return '';
    if (r.status === 'REFUSED') {
      const cc = S.cleanCheck;
      if (/^I10/.test(r.message || '') && cc && !cc.ok) return IL.plural((cc.dirtyPadIds || []).length, 'pad') + ' not clean';
      return 'nothing dispensed';
    }
    const pp = p || plan();
    const mine = !!(pp && r.planId && (IL.basePlanId(r.planId) === pp.planId || (isNum(r.boardNumber) && r.boardNumber === boardNumber())));
    const N = mine ? pp.dots.length : null;
    const sub = /-subset-/.test(String(r.planId || '')) || (N !== null && r.dotsPlanned < N);
    const kind = R.kinds[r.planId] || null;
    if (!sub) return r.dotsDone === r.dotsPlanned ? 'all ' + r.dotsPlanned + ' dots' : r.dotsDone + ' of ' + r.dotsPlanned + ' dots';
    if (kind === 'rest' && r.dotsDone === r.dotsPlanned) {
      const latest = !!resultRun(S.status) && resultRun(S.status).runId === r.runId;
      return 'the ' + IL.plural(r.dotsDone, 'dot') + ' left' + (mine && latest && R.doneSet.size >= N ? ' · board complete' : '');
    }
    if (N === null) return r.dotsDone + ' of ' + r.dotsPlanned + ' dots (part of the board)';
    return r.dotsDone + ' of ' + N + ' dots' + (kind === 'test' ? ' (test)' : r.dotsDone < r.dotsPlanned ? ' (stopped)' : '');
  }

  // ================================================================================== done dots, phases (every poll)
  /**
   * The dots of the plan shown that its board has paste on (rc1 review: the server keeps them, so a new tab, another
   * browser or a reload sees the same board): plan.pasted counts the dots laid on each pad since the board was seated,
   * taken in path order on the pad, with the dots of this board's run still under way or not in the plan read yet
   * (status.run.doneDots). A "Next board" or a wipe elsewhere (status.dispense.pasteVersion) reads the plan again.
   */
  function trackDone(s) {
    const p = plan();
    if (!p) { R.doneSet = new Set(); return; }
    const pv = s && s.dispense ? s.dispense.pasteVersion : null;
    if (R.pasteVersion !== null && pv !== R.pasteVersion) IL.planRefreshSoon();
    R.pasteVersion = pv === undefined ? null : pv;
    const laid = Object.assign({}, ((p.pasted || {}).pads) || {});
    const set = new Set();
    for (const d of p.dots) if (laid[d.padId] > 0) { set.add(d.index); laid[d.padId]--; }
    const run = runOfThisBoard(s);
    for (const i of (run && run.doneDots) || []) {
      const j = mapIndex(run.planId, i);
      if (j !== null && j !== undefined) set.add(j);
    }
    R.doneSet = set;
  }
  function trackPhases(s) {
    const run = s.run;
    if (!run || run.label !== 'board') return;
    const now = Date.now();
    if (R.ph.runId !== run.runId) {
      R.ph = { runId: run.runId, at: {}, firstDotAt: null, endAt: null, fresh: true };
      // a run already under way when the page loaded: its start is known, the phases since then are estimated
      if ((run.phases || []).length) R.ph.at[run.phases[0]] = run.startedAtMs ? run.startedAtMs - S.skewMs : now;
    }
    if (run.dotsDone > 0 && !R.ph.firstDotAt) {
      R.ph.firstDotAt = run.liveDph ? now - run.dotsDone / run.liveDph * 3600000 : now;
    }
    for (const ph of run.phases || []) {
      if (R.ph.at[ph]) continue;
      if (ph === 'dispensing' && R.ph.firstDotAt && R.ph.fresh && run.dotsDone > 1) {
        const est = estimate(s);
        R.ph.at[ph] = R.ph.firstDotAt - (est ? est.pre * 1000 : 0);
      } else R.ph.at[ph] = now;
    }
    R.ph.fresh = false;
    if (!run.running && !R.ph.endAt) R.ph.endAt = run.finishedAtMs ? run.finishedAtMs - S.skewMs : now;
  }

  // ================================================================================== step logic
  function surfaceNeedsProbe(r) {
    const it = r.items.find((x) => x.key === 'surface');
    return !!it && (it.state === 'need' || it.state === 'block');
  }
  /**
   * The board decision: after a STOP / abort of this plan's board run (the stopped run, cause stop|aborted|error|openpnp),
   * or while the seated board has paste on part of its pads (a test run of the first N dots, a partial finish: cause
   * 'partial'), or null. Finish the pads left, or wipe them all and run the whole board.
   */
  function decision(s) {
    return stopDecision(s) || partialDecision(s);
  }
  function partialDecision(s) {
    const p = plan();
    if (!p || runningBoard(s) || IL.recoveryActive()) return null;
    const N = p.dots.length;
    const done = R.doneSet.size;
    if (!(done > 0 && done < N)) return null;
    const rr = resultRun(s);
    const kind = rr ? R.kinds[rr.planId] : null;
    return { cause: 'partial', kind: kind === 'test' ? 'test' : 'partial', atMs: rr && rr.endedAtMs ? rr.endedAtMs - S.skewMs : Date.now(),
      run: { runId: rr ? rr.runId : null, planId: p.planId, label: 'board', boardName: p.boardName, status: 'OK', dotsDone: done, dotsPlanned: N } };
  }
  function stopDecision(s) {
    const p = plan();
    if (!p) return null;
    let rec = S.recovery || S.lastStop;
    if (!(rec && rec.run && rec.run.label === 'board' && IL.planMatches(rec.run.planId))) rec = null;
    const run = s && s.run;
    if (!rec && run && !run.running && run.label === 'board' && (run.status === 'ABORTED' || run.status === 'ERROR')
      && IL.planMatches(run.planId) && run.dotsDone < run.dotsPlanned) {
      rec = { cause: run.status === 'ERROR' ? 'error' : 'aborted', atMs: run.finishedAtMs ? run.finishedAtMs - S.skewMs : Date.now(), run, message: run.message };
    }
    if (!rec || rec.atMs <= R.decidedAt) return null;
    if (runningBoard(s) && !IL.stoppedRun(s)) return null;
    if (!(rec.run.dotsDone < rec.run.dotsPlanned)) return null;
    return rec;
  }
  /** A stop before the first board dot: nothing to keep or wipe, the one action is to run the board again. */
  const zeroDots = (rec) => !!rec && rec.cause !== 'partial' && IL.noDotsReached(rec.run) && !R.doneSet.size;
  function autoStep(s) {
    if (runningBoard(s)) return 'run';
    if (!plan()) return 'board';
    if (R.resultRunId) {
      const rr = resultRun(s);
      if (rr && rr.runId === R.resultRunId) return 'result';
    }
    // Register board: its live phases and its result (also a same seat whose surface stays tied) show on Register
    const reg = s && s.run && s.run.label === 'board registration' ? s.run : null;
    if (reg && reg.running && IL.planMatches(reg.planId)) return 'probe';
    if (R.regResultRunId) return 'probe';
    if (surfaceNeedsProbe(IL.ready())) return 'probe';
    return 'checks';
  }
  function currentStep(s) {
    if (IL.recoveryActive()) return 'recovery';
    if (R.section && STEPS.includes(R.section)) return R.section;
    return autoStep(s);
  }
  function goStep(step) {
    R.section = step;
    IL.go({ ws: 'run', section: step }, { replace: true });
  }
  function goAuto() {
    R.section = null;
    if (IL.active() === 'run') IL.go('#run', { replace: true });
  }

  // ================================================================================== actions
  function planBodyFrom(src) {
    if (!src) return null;
    if (src.source === 'job') return { source: 'job', boardLocationId: src.boardLocationId };
    return { source: 'file', file: src.file, x: src.x, y: src.y, z: src.z, rotation: src.rotation || 0 };
  }
  async function postPlan(body) {
    const p = await IL.api('POST', '/api/plan', body);
    lsSet('il.run.source', JSON.stringify(p.source || body));
    R.resultRunId = null;
    R.refused = null;
    R.decidedAt = Date.now();
    ssSet('il.run.decidedAt', R.decidedAt);
    R.probeUntied = false;
    IL.setPlan(p);
    return p;
  }
  /** Plan again with the plan's own board and position (also after "Plan forgotten"). */
  async function planAgain() {
    const src = (plan() && plan().source) || lsJson('il.run.source', null);
    const body = planBodyFrom(src);
    if (!body) { IL.go('#run/board'); throw new Error('No board to plan again: choose a board first.'); }
    const p = await postPlan(body);
    goAuto();   // the step that matters now: Probe when this seat needs its surface, else Checks
    return p;
  }
  async function cleanCheck() {
    const p = plan();
    if (!p) throw new Error('Plan a board first.');
    const r = await IL.api('POST', '/api/plan/' + encodeURIComponent(p.planId) + '/clean-check', {});
    IL.planRefreshSoon();
    return r;
  }
  IL.fix('plan-again', { run: () => planAgain(), ok: (p) => (p ? 'Planned again: ' + p.summary.dots + ' dots on ' + p.summary.pads + ' pads' : 'Planned again'),
    gate: (s) => IL.lockReason(s, { pressureOff: false, engine: true }) });
  IL.fix('clean-check', {
    press2: 'check pads',
    gate: (s) => IL.lockReason(s, { connected: true, homed: true }) || (!plan() ? 'Plan a board first' : null),
    run: () => cleanCheck(),
    ok: (r) => 'Checking ' + IL.plural((r && r.dots) || 0, 'pad') + ': the top camera visits every pad, the pressure stays off (≈ '
      + IL.fmtClock(tourSeconds((r && r.dots) || 0)) + ').',
  });
  IL.fix('probe', { href: '#run/probe' });
  // Register board and its fix-its (AUTO_REGISTER_DESIGN.md §8.2)
  const regGate = (s) => { const w = registerWhy(s); return w ? w.text : null; };
  IL.fix('register', { press2: 'register board', gate: regGate, run: () => postRegister('auto'), ok: (r) => regOk(r) });
  // a board without fiducials (F10): Register board with probe "always" probes it only, where the plan puts it
  IL.fix('register-probe-only', {
    press2: 'probe only',
    gate: (s) => { if (plan() && !probeOnlyPlan(plan())) return 'This board has fiducials: use Register board'; const w = registerWhy(s, true); return w ? w.text : null; },
    run: () => postRegister('always'),
    ok: (r) => regOk(r),
  });
  // a new seat on a board with fiducials: the fiducials decide (the 0.1 mm rule and the 3-point check), not trust
  IL.fix('register-check', {
    press2: 'check the fiducials',
    gate: regGate,
    run: async () => { await IL.setNewSeat(null); return postRegister('auto'); },
    ok: (r) => regOk(r),
  });
  IL.fix('probe-by-hand', { run: async () => { R.byHand = true; setRegResult(null); goStep('probe'); IL.render(); return null; } });
  // Setup's Top camera card: its Snapshot moves nothing (after a refused fiducial check the head is still where it looked)
  IL.fix('look-camera', { run: async () => { IL.go('#setup/camera'); return null; } });
  IL.fix('plan-unregistered', {
    gate: (s) => IL.lockReason(s, { pressureOff: false, engine: true }),
    run: async () => {
      const body = planBodyFrom((plan() && plan().source) || lsJson('il.run.source', null));
      if (!body) throw new Error('No board to plan: choose a board first.');
      const p = await postPlan(Object.assign(body, { registration: 'none' }));
      setRegResult(null);
      goAuto();
      return p;
    },
    ok: 'Planned without fiducial correction: the dots use the board position as entered.',
  });
  IL.fix('board-step', { run: async () => { goStep('board'); return null; } });
  IL.fix('check-z', {
    press2: 'check Z',
    gate: (s) => IL.lockReason(s, { connected: true, engine: true }),
    run: () => IL.api('POST', '/api/machine/z-check', {}),
    ok: (r) => (r && r.needed === false ? 'Z needs no check: nothing was measured before the last home.'
      : 'Checking Z: ' + IL.zTouches(r && r.touches) + (r && r.reference ? (r.touches > 1 ? ', the first' : '') + ' on the ' + r.reference.name + ' at X '
        + IL.fmt(r.reference.x, 1) + ', Y ' + IL.fmt(r.reference.y, 1) : '')
        + (r && isNum(r.estimatedSeconds) ? ', ≈ ' + IL.fmtSecs(r.estimatedSeconds) : '') + '.'),
  });
  // I7 on-time: a test run of the first N dots that fits the limit (90 % of it, after settle and purge)
  IL.fix('run-fewer', {
    gate: (s) => IL.lockReason(s, { pressureOff: false }) || (!plan() ? 'Plan a board first' : null),
    run: async (btn, arg, s) => {
      const p = plan();
      const est = estimate(s);
      const lim = s && s.dispense && s.dispense.maxContinuousOnTimeS;
      let n = null;
      if (est && isNum(lim) && p.dots.length > 1) {
        const per = est.work / p.dots.length;
        n = Math.max(1, Math.min(p.dots.length - 1, Math.floor((Number(lim) * 0.9 - est.pre) / per)));
      }
      R.test = true;
      R.maxDots = n ? String(n) : '';
      goStep('checks');
      IL.render();
      setTimeout(() => { const i = $('run-maxdots'); if (i) { i.value = R.maxDots; i.focus({ preventScroll: false }); } }, 60);
      return n;
    },
    ok: (n) => (n ? 'Test run: the first ' + n + ' dots fit the on-time limit.' : 'Enter how many dots to run.'),
  });
  // Records › board detail "Finish the remaining pads": arg {planId, padIds} → the plan on Run and the run confirm
  IL.fix('run-rest', {
    run: async (btn, arg) => {
      const id = arg && IL.basePlanId(arg.planId);
      if (id && (!plan() || plan().planId !== id)) {
        const q = await IL.loadPlan(id);
        if (!q) throw new Error('Plan ' + id.slice(0, 4) + ' is no longer in OpenPnP (restarted): plan the board again.');
      }
      IL.go('#run');
      openConfirm('rest', { padIds: arg && arg.padIds });
      return null;
    },
  });

  // ================================================================================== main column (mount)
  function buildMain(root) {
    const n = R.n;
    n.eyebrow = el('div', { class: 'eyebrow', id: 'run-eyebrow' });
    n.title = el('h1', { id: 'run-title' });
    n.meta = el('div', { class: 'meta', id: 'run-meta' });
    n.lock = el('div', { class: 'lock-note run-lock', id: 'run-lock', hidden: true }, IL.icon('i-lock'),
      el('span', { text: 'Plan again, board position, setup saves and Home are locked while the board runs.' }));
    n.change = el('button', { type: 'button', class: 'btn', id: 'btn-board-change', title: 'Choose another board or position (Board step). Nothing moves.' },
      IL.icon('i-swap'), 'Change board');
    n.again = el('button', { type: 'button', class: 'btn', id: 'btn-plan-again', title: 'Plan this board again with the current profile, surface and needle. Nothing moves.' },
      IL.icon('i-refresh'), 'Plan again');
    n.change.addEventListener('click', () => goStep('board'));
    n.again.addEventListener('click', () => IL.runFix('plan-again', n.again));
    n.actions = el('div', { class: 'page-head__actions' }, n.change, n.again);
    n.headRef = el('div', { 'data-refusals': '', id: 'run-head-refusals' });
    const head = el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' }, n.eyebrow, n.title, n.meta, n.lock), n.actions);

    n.chip = el('span', { class: 'plan-chip', id: 'plan-chip' });
    n.recipe = el('span', { id: 'run-recipe', class: 'run-recipe' });
    n.note = el('div', { class: 'hero__note', id: 'run-note' });
    n.heroHead = el('div', { class: 'hero__head' }, n.chip, el('span', { class: 'spacer' }), n.recipe, n.note);
    n.svgwrap = el('div', { class: 'hero__svgwrap' });
    n.empty = el('div', { class: 'hero__empty', hidden: true });
    n.svgwrap.appendChild(n.empty);
    n.timeline = el('div', { class: 'timeline', id: 'run-timeline', role: 'img', hidden: true });
    n.legend = el('div', { class: 'legend', id: 'run-legend', 'aria-label': 'Layers: press to show or hide' });
    n.viewSeg = el('div', { class: 'seg', role: 'group', 'aria-label': 'View' });
    n.zoomOut = el('button', { type: 'button', class: 'btn', 'aria-label': 'Zoom out', title: 'Zoom out' }, IL.icon('i-minus'));
    n.zoomIn = el('button', { type: 'button', class: 'btn', 'aria-label': 'Zoom in', title: 'Zoom in' }, IL.icon('i-plus'));
    n.zoomOut.addEventListener('click', () => { if (R.bv) R.bv.zoomBy(-1); IL.render(); });
    n.zoomIn.addEventListener('click', () => { if (R.bv) R.bv.zoomBy(1); IL.render(); });
    n.stageBar = el('div', { class: 'stage-bar' }, n.legend, el('span', { class: 'spacer' }), n.viewSeg, el('div', { class: 'zoom' }, n.zoomOut, n.zoomIn));
    n.stage = el('div', { class: 'hero__stage' }, n.svgwrap, n.timeline, n.stageBar);

    n.summary = el('div', { class: 'tiles', id: 'plan-summary' });
    n.live = el('div', { class: 'tiles', id: 'run-live', hidden: true });
    n.decide = el('div', { class: 'card__foot run-decide', id: 'run-decide', hidden: true, 'data-refusals': '' });
    n.rest = el('button', { type: 'button', class: 'btn btn--lg', id: 'btn-run-rest' }, 'Finish the pads left');
    n.all = el('button', { type: 'button', class: 'btn btn--lg', id: 'btn-run-all' }, 'Wipe all, run all');
    n.decideQ = el('div', { class: 'run-decide__q' });
    n.decideWhy = el('div', { class: 'reason' }, IL.icon('i-lock'), el('span'));
    n.decide.append(el('div', { class: 'run-decide__t' }, el('div', { class: 'eyebrow', text: 'This board' }), n.decideQ, n.decideWhy), n.rest, n.all);
    n.rest.addEventListener('click', () => openConfirm(zeroDots(decision(S.status)) ? 'full' : 'rest'));
    n.all.addEventListener('click', () => openConfirm('all'));

    n.hero = el('section', { class: 'card hero', id: 'run-hero', 'aria-label': 'Plan preview' }, n.heroHead, n.stage, n.summary, n.live, n.decide);
    root.append(head, n.headRef, n.hero);

    // H2 (BOARD_IMPORT_DESIGN.md §5.1.1): a pad or part click goes to the pad inspector once boardflow.js loads
    R.bv = IL.boardview.create(n.svgwrap, { id: 'plan-svg', aria: 'Board preview to scale: pads and planned dots',
      onPad: (padId, ev) => (IL.boardFlow ? IL.boardFlow.onPad(padId, ev) : IL.panel.show('camera', IL.views.camera, { title: 'Top camera · pad ' + padId })),
      onPart: (ref, ev) => IL.boardFlow && IL.boardFlow.onPart(ref, ev) });
    R.bv.svg.setAttribute('data-testid', 'plan-svg');
    if (S.plan) R.bv.setPlan(S.plan, { origin: originOf(S.plan) });
  }
  function originOf(p) {
    const st = seatOf(p);
    return st && isNum(st.x) ? { x: Number(st.x), y: Number(st.y) } : null;
  }

  // ================================================================================== main column (render)
  function mainMode(s, step) {
    const p = plan();
    if (!p) return 'empty';
    const run = boardRunOfPlan(s);
    if (run && run.running && !IL.stoppedRun(s)) return 'live';
    if (stopDecision(s)) return 'stopped';
    if (step === 'result' && resultRun(s)) return 'result';
    if (partialDecision(s)) return 'stopped';
    return 'idle';
  }
  /** The board chosen in the Board step when it is not the plan's (picked, or "Use this board" after an upload), else null. */
  function otherBoard(p) {
    const sel = selectedBoard();
    const src = (p && p.source) || {};
    if (!sel || !p) return null;
    if (sel.kind === 'job') return src.source === 'job' && String(src.boardLocationId) === String(sel.id) ? null : sel;
    return src.source === 'file' && src.file === sel.file ? null : sel;
  }
  function renderMain(s) {
    const n = R.n;
    if (!n.hero) return;
    const step = currentStep(s);
    let p = plan();
    let mode = mainMode(s, step);
    // another board chosen in the Board step: the main column shows it before planning (its drawing at the form's seat,
    // BOARD_IMPORT_DESIGN.md §5.9), not the plan of the board before; that plan stays (and shows on the other steps)
    // until Plan
    R.pending = p && step === 'board' && (mode === 'idle' || mode === 'result') && otherBoard(p) ? p : null;
    if (R.pending) { p = null; mode = 'empty'; }
    R.mode = mode;
    const r = IL.ready();
    renderHead(s, p, mode);
    renderChip(s, p, mode, r);
    renderStage(s, p, mode, step);
    IL.show(n.summary, mode === 'idle' || mode === 'empty');
    IL.show(n.live, mode === 'live' || mode === 'result');
    IL.show(n.decide, mode === 'stopped');
    const run = s.run;
    IL.setAttr(n.live, 'data-label', run ? run.label : null);
    IL.setAttr(n.live, 'data-status', run ? run.status : null);
    IL.setAttr(n.live, 'data-mode', mode);
    if (mode === 'idle' || mode === 'empty') renderSummary(s, p);
    else if (mode === 'live') renderLive(s, p);
    else if (mode === 'result') renderResultTiles(s, p);
    else if (mode === 'stopped') renderDecide(s, p);
  }

  function renderHead(s, p, mode) {
    const n = R.n;
    const run = s.run;
    const seat = p ? seatOf(p) : null;
    const no = p ? boardNumber() : null;
    const dec0 = mode === 'stopped' ? decision(s) : null;
    const partial = !!dec0 && dec0.cause === 'partial';
    // another board chosen: Plan (the Board step's) is the one action; "Plan again" would plan the board before
    IL.show(n.actions, (mode !== 'stopped' || partial) && !R.pending);
    // the lock note names what is locked during any task (board run, pad check, prime ...): greyed controls say why
    const taskLock = IL.taskRunning(s) ? IL.lockReason(s, { pressureOff: false }) : null;
    IL.show(n.lock, !!taskLock);
    if (taskLock) IL.setText(n.lock.lastChild, 'Change board, Plan again, the board position, setup saves and Home: ' + taskLock.charAt(0).toLowerCase() + taskLock.slice(1) + '.');
    let eb = '', ebCls = 'eyebrow', title = '', meta = [];
    if (mode === 'empty' && R.pending) {
      eb = 'Run · not planned yet';
      title = (selectedBoard() || {}).name || 'Choose a board';
      meta = [el('span', { text: 'Enter where it sits on the right, then Plan. Until then the plan of ' + boardShort(R.pending.boardName)
        + ' stays. Planning moves nothing.' })];
    } else if (mode === 'empty') {
      const sel = selectedBoard();
      eb = S.planForgotten ? 'Plan forgotten (OpenPnP restarted)' : 'Run · no board planned';
      ebCls += S.planForgotten ? ' eyebrow--need' : '';
      title = sel ? sel.name : 'Choose a board';
      meta = [el('span', { text: 'Choose the board and where it sits on the right, then Plan. Planning moves nothing.' })];
    } else if (mode === 'live') {
      eb = 'Board ' + no + ' this session · running since ' + IL.fmtTimeS(run.startedAtMs - S.skewMs);
      ebCls += ' eyebrow--live';
      title = p.boardName;
    } else if (mode === 'stopped') {
      const rec = dec0;
      title = p.boardName;
      if (partial) {
        eb = 'Board ' + no + ' · ' + (rec.kind === 'test' ? 'test run finished ' : 'part done ') + IL.fmtTime(rec.atMs) + ' · ' + rec.run.dotsDone + ' of ' + rec.run.dotsPlanned + ' dots';
        ebCls += ' eyebrow--need';
      } else {
        const how = rec.cause === 'stop' ? 'ended by STOP' : rec.cause === 'error' ? 'ended by an error' : rec.cause === 'openpnp' ? 'stopped in OpenPnP' : 'stopped';
        eb = 'Board ' + no + ' this session · ' + how + ' ' + IL.fmtTimeS(rec.atMs) + ' · ' + (zeroDots(rec) ? 'no dot reached the board' : rec.run.dotsDone + ' of ' + rec.run.dotsPlanned + ' dots');
        ebCls += ' eyebrow--block';
      }
      if (seat && isNum(seat.x)) meta.push(el('span', {}, 'Seat ', el('b', { text: 'X ' + fmtMmV(seat.x) + ' · Y ' + fmtMmV(seat.y) + ' mm · Rot ' + IL.fmt(seat.rot, 1) + '°' })));
      meta.push(el('span', { text: 'plan ' + shortId(p) }));
      meta.push(el('span', { text: zeroDots(rec) ? 'No dot reached the board: nothing to keep or wipe.'
        : 'Keep the board where it is: the ' + IL.plural(R.doneSet.size || rec.run.dotsDone, 'dot') + ' belong to this seat.' }));
    } else {
      const src = p.source || {};
      eb = 'Board ' + no + ' this session · ' + (seat && seat.job ? 'job board' + (seat.fid ? ' · fiducials checked' : ' · no fiducial check') + (seat.side ? ' · ' + String(seat.side).toLowerCase() + ' side' : '') : 'board file');
      title = p.boardName;
      if (seat && isNum(seat.x)) {
        meta.push(el('span', {}, 'Seat ', el('b', { text: 'X ' + fmtMmV(seat.x) + ' · Y ' + fmtMmV(seat.y) + ' mm · Rot ' + IL.fmt(seat.rot, 1) + '°' })));
        meta.push(el('span', {}, 'Board top ', el('b', { text: 'Z ' + fmtMmV(seat.z) + ' mm' })));
      }
      const rem = src.source === 'file' ? remembered(src.file) : null;
      if (rem && rem.atMs && Number(rem.x) === Number(src.x) && Number(rem.y) === Number(src.y)) {
        meta.push(el('span', { title: IL.fmtAgo(rem.atMs) }, 'entered ' + IL.fmtTime(rem.atMs) + ' for this seat'));
      } else if (seat && seat.job) meta.push(el('span', { text: '(position from the OpenPnP job)' }));
      meta.push(p.registration ? el('span', { title: fitText(p.registration) }, 'registered ', el('b', { text: IL.fmtTime(p.registration.atMs - S.skewMs) }))
        : el('span', { class: 'muted', text: 'not registered' }));
    }
    IL.setText(n.eyebrow, eb);
    IL.setClass(n.eyebrow, ebCls);
    IL.setText(n.title, title);
    IL.setAttr(n.title, 'title', title || null);
    IL.keyed(n.meta, mode + '|' + meta.map((m) => m.textContent).join('|'), () => meta);
    IL.show(n.meta, mode !== 'live');
    // Change board / Plan again: locked while a task runs
    const lock = IL.lockReason(s, { pressureOff: false });
    IL.gate(n.change, lock);
    IL.gate(n.again, lock || (!p && !lsJson('il.run.source', null) ? 'Plan a board first' : null));
  }

  function renderChip(s, p, mode, r) {
    const n = R.n;
    let kind = 'ok', text = '', title = '', glyph = 'ok';
    const run = s.run;
    if (!p) {
      if (S.planForgotten) { kind = 'need'; glyph = 'need'; text = 'Plan forgotten (OpenPnP restarted): plan again'; title = 'OpenPnP keeps plans in memory only: plan the board again (nothing moves).'; }
      else if (R.pending) { kind = 'off'; glyph = 'off'; text = 'Not planned yet'; title = 'Enter where the board sits, then Plan (nothing moves).'; }
      else { kind = 'off'; glyph = 'off'; text = 'No plan yet'; title = 'Choose a board and plan it.'; }
    } else if (mode === 'live') {
      kind = 'live'; glyph = 'live';
      if (/I10|clean/i.test(run.phase || '')) text = 'Checking pads with the camera · pressure off';
      else if (run.dotsDone > 0) {
        const nx = nextIndex(s);
        const d = nx !== null ? p.dots[nx] : null;
        text = 'Dispensing · ' + (d ? 'next ' + d.padId : run.dotsDone + ' of ' + run.dotsPlanned);
      } else if (run.phase === 'dispensing') text = 'Pressure on · purge dots';
      else if (run.detail && run.detail.stage === 'home') text = 'Homing first · pressure off';
      else if (run.detail && run.detail.stage === 'z check') text = 'Checking Z · N1 touch';
      else text = 'Starting · waiting for OpenPnP motion';
      title = 'Board run in progress (' + (run.phase || '') + ')';
    } else if (regLive(s)) {
      // Register board runs: the plan's dots move with the board when it ends
      kind = 'live'; glyph = 'live';
      text = 'Registering · ' + IL.regProgress(regLive(s)).part;
      title = 'Register board (' + (regLive(s).phase || '') + '): the plan is made again when it ends';
    } else if (mode === 'stopped' && decision(s).cause === 'partial') {
      const rec = decision(s);
      kind = 'need'; glyph = 'need';
      text = 'Plan ' + shortId(p) + ' · ' + rec.run.dotsDone + '/' + rec.run.dotsPlanned + ' done';
      title = 'Paste on ' + IL.plural(padsDone(p).length, 'pad') + ' already: finish the pads left, or wipe them and run the whole board.';
    } else if (mode === 'stopped') {
      const rec = decision(s);
      kind = 'block'; glyph = 'block';
      text = (rec.cause === 'stop' ? 'Ended by STOP' : rec.cause === 'error' ? 'Ended by an error' : 'Stopped') + ' · '
        + (zeroDots(rec) ? 'no dot reached the board' : rec.run.dotsDone + ' of ' + rec.run.dotsPlanned + ' dots');
      title = IL.recoveryHeadline(rec);
    } else {
      const it = r.items.find((x) => x.key === 'plan');
      const sf = r.items.find((x) => x.key === 'surface');
      const pt = r.items.find((x) => x.key === 'padtypes');
      title = 'Made ' + IL.fmtTime(p.createdAtMs) + ' · ' + p.summary.dots + ' dots on ' + p.summary.pads + ' pads · ' + correctionText(p);
      if (it && it.state === 'checking') { kind = 'off'; glyph = 'checking'; text = 'Plan ' + shortId(p) + ' · checking…'; }
      else if (it && it.stale) {
        kind = 'need'; glyph = 'need';
        text = 'Plan ' + shortId(p) + ' · stale: ' + it.stale.map((x) => x.replace(/^the /, '')).join(', ') + ' changed' + (it.staleAt ? ' ' + IL.fmtTime(it.staleAt) : '');
        title = it.detail + ' Plan again.';
      } else if (pt && pt.state === 'block') { kind = 'block'; glyph = 'block'; text = 'Plan ' + shortId(p) + ' · ' + (pt.chip || pt.short); title = pt.detail; }
      else if (sf && (sf.state === 'need' || sf.state === 'block')) { kind = 'need'; glyph = 'need'; text = 'Plan ' + shortId(p) + (probeOnlyPlan(p) ? ' · probe this seat' : ' · register this seat'); title = sf.detail; }
      else { text = 'Plan ' + shortId(p) + ' · current'; title += ' with the current profile, surface and start angle'; }
    }
    const sig = kind + '|' + text;
    if (n.chip.dataset.sig !== sig) {
      n.chip.dataset.sig = sig;
      n.chip.className = 'plan-chip' + (kind !== 'ok' ? ' plan-chip--' + kind : '');
      n.chip.replaceChildren(kind === 'live' ? el('span', { class: 'pulse' }) : IL.glyph(glyph, 'sm'), el('span', { text }));
    }
    IL.setAttr(n.chip, 'title', title || null);
    // recipe token: the profile the plan was made with; amber when the live profile differs
    const prof = p && p.profile;
    const live = s.dispense && s.dispense.profile;
    const changed = !!(p && live && live.fingerprint && p.profileFingerprint && live.fingerprint !== p.profileFingerprint);
    IL.keyed(n.recipe, prof ? JSON.stringify(IL.recipeParts(prof)) + changed : '', () => (prof ? IL.recipeEl(prof, {
      changed, changedText: 'changed: plan again', href: '#profile', title: 'Dispense profile this plan was made with (' + prof.name + ')' + (changed ? ': the profile changed since, plan again' : ''),
    }) : null));
    IL.show(n.recipe, !!prof && (mode !== 'stopped' || decision(s).cause === 'partial'));
    // stopped: what was interrupted
    let note = null;
    if (mode === 'stopped' && decision(s).cause !== 'partial') {
      const rec = decision(s);
      const rr = rec.run;
      const ph = phaseAtStop(rr);
      const where = /I10|clean/i.test(ph) ? 'the camera pad check (pressure off)' : ph === 'dispensing'
        ? (rr.dotsDone > 0 ? 'board dots' : 'pressure on and purge dots') : 'the start';
      const chk = checkMark(rec);
      const pon = pressureAtStop(rr);
      note = ['Interrupted during ', el('b', { text: where }),
        pon ? ' · pressure had been on ' + IL.fmtClock(pon) : '',
        chk ? [' · dot ' + (rr.dotsDone + 1) + ' on ', el('b', { text: p.dots[chk.index].padId }), ' may be partial'] : ''];
    }
    IL.keyed(n.note, note ? JSON.stringify(el('span', {}, note).textContent) : '', () => (note ? el('span', {}, note) : null));
  }
  /** The phase a stopped run was in ('finished: ABORTED' after the task ended: the last phase seen before it). */
  function phaseAtStop(rr) {
    if (!rr) return '';
    if (!/^finished/.test(rr.phase || '')) return rr.phase || '';
    if (R.ph.runId === rr.runId) {
      const keys = Object.keys(R.ph.at).sort((a, b) => R.ph.at[a] - R.ph.at[b]);
      if (keys.length) return keys[keys.length - 1];
    }
    return rr.dotsDone > 0 ? 'dispensing' : '';
  }
  /** Camera tour progress: '57 of 160' from the optional API fields (§17), else '≈ 57 of 160' from the elapsed time. */
  function tourProgress(run, elapsedS, nPads) {
    if (isNum(run.checkDone) && isNum(run.checkPlanned) && run.checkPlanned > 0) {
      const perPad = run.checkDone > 0 ? elapsedS / run.checkDone : tourSeconds(1);
      return { text: 'pad ' + run.checkDone + ' of ' + run.checkPlanned, left: Math.max(0, (run.checkPlanned - run.checkDone) * perPad),
        k: run.checkDone, n: run.checkPlanned, approx: false };
    }
    const k = Math.min(nPads, Math.floor(elapsedS / tourSeconds(1)));
    return { text: '≈ pad ' + k + ' of ' + nPads, left: Math.max(0, tourSeconds(nPads) - elapsedS), k, n: nPads, approx: true };
  }
  // core's strip verdict and tab title show the same progress and time left as the Pads clean card
  IL.tourProgress = tourProgress;
  /** Seconds the pressure had been on when the run stopped (core's snapshot, else this page's phase stamps). */
  function pressureAtStop(rr) {
    if (isNum(rr.pressureOnS) && rr.pressureOnS > 0) return rr.pressureOnS;
    // §17 run.pressureOnAtMs (also after a reload): from the pressure on to the end of the run
    if (isNum(rr.pressureOnAtMs) && isNum(rr.finishedAtMs) && rr.finishedAtMs > rr.pressureOnAtMs) return (rr.finishedAtMs - rr.pressureOnAtMs) / 1000;
    if (R.ph.runId === rr.runId && R.ph.at.dispensing) {
      const v = ((R.ph.endAt || Date.now()) - R.ph.at.dispensing) / 1000;
      return v > 0 ? v : null;
    }
    return null;
  }
  /** The in-progress dot at STOP (amber ring): the stopped run's next dot in its own order. */
  function checkMark(rec) {
    const rr = rec && rec.run;
    if (!rr || !(rr.dotsDone > 0) || rr.dotsDone >= rr.dotsPlanned || phaseAtStop(rr) !== 'dispensing') return null;
    const j = mapIndex(rr.planId, rr.dotsDone);
    if (j === null || j === undefined || !plan().dots[j]) return null;
    return { index: j, label: plan().dots[j].padId + ' · check' };
  }
  function nextIndex(s) {
    const run = boardRunOfPlan(s);
    if (!run || !run.running || run.dotsDone >= run.dotsPlanned || run.phase !== 'dispensing') return null;
    const j = mapIndex(run.planId, run.dotsDone);
    return j === undefined ? null : j;
  }

  function renderStage(s, p, mode, step) {
    const n = R.n;
    const bv = R.bv;
    // empty board: hint instead of the SVG
    const sel = selectedBoard();
    // H3 (BOARD_IMPORT_DESIGN.md §5.1.1): the board drawing (also before planning) and its stage-bar controls, once
    // boardflow.js loads; the SVG and the stage bar show with a plan or a drawing
    const art = IL.boardFlow ? IL.boardFlow.hero(bv, { plan: p, sel, form: R.form, mode, s, hero: n.hero, stageBar: n.stageBar, row: n.timeline }) : null;
    const drawn = !!p || !!(art && art.shown);
    IL.show(n.empty, !drawn);
    IL.show(bv.svg, drawn);
    IL.keyed(n.empty, !drawn ? (S.planForgotten ? 'forgot' : sel ? 'sel' : 'none') : '', () => {
      if (drawn) return null;
      if (S.planForgotten) {
        return el('div', {}, IL.icon('i-refresh'), el('b', { text: 'Plan forgotten (OpenPnP restarted)' }),
          el('span', { text: 'OpenPnP keeps plans in memory. Plan the board again: nothing moves.' }),
          el('div', { class: 'btn-row', 'data-refusals': '' }, fixBtn({ fix: 'plan-again', label: 'Plan again', primary: true })));
      }
      return el('div', {}, IL.icon('i-run'), el('b', { text: sel ? 'Plan to see the dots' : 'Choose a board on the right' }),
        el('span', { text: sel ? 'Enter where the board sits, then Plan. The board is drawn to scale with every planned dot.' : 'Pick a job board or a board file in the Board step, enter its position, then Plan.' }));
    });
    if (!p) { IL.show(n.stageBar, drawn); IL.show(n.timeline, false); return; }
    IL.show(n.stageBar, true);
    if (bv.planId !== p.planId) bv.setPlan(p, { origin: originOf(p) });
    // overlays. Paste found by the pad check: only before a run (idle), only from a check newer than the last board run
    // of this plan (after a run the pads have paste for real), and never for "0 of N clean" (the board position looks
    // wrong: the boxes saw solder mask, not paste)
    const cc = S.cleanCheck && IL.planMatches(S.cleanCheck.planId || p.planId) ? S.cleanCheck : null;
    const lastRun = resultRun(s);
    const ccFresh = !!cc && !(lastRun && lastRun.startedAtMs && cc.checkedAtMs < lastRun.startedAtMs);
    const posWrong = ccFresh && IL.cleanPositionWrong(cc);
    const dirty = new Map();
    if (ccFresh && !posWrong && (mode === 'idle' || mode === 'empty')) for (const d of IL.dirtyPads(cc)) dirty.set(d.padId, d.share);
    const rec = mode === 'stopped' ? decision(s) : null;
    const chk = rec ? checkMark(rec) : null;
    const pos = IL.positions(s);
    // the Register step's overlay (idle, live and its result; also on a part-done board): probe targets and the
    // fiducials (§8.1 "Hero overlay"); never during a board run
    const regStep = step === 'probe' && (mode === 'idle' || mode === 'stopped');
    const probe = regStep ? probeOverlay(s) : null;
    const fids = regStep && !R.byHand ? fidOverlay(s) : null;
    bv.update({
      done: mode === 'empty' ? [] : R.doneSet,
      dirty: mode === 'live' ? new Map() : dirty,
      next: mode === 'live' ? nextIndex(s) : null,
      check: chk,
      // the needle is at the next dot: OpenPnP's own N2 position is not updated while the engine streams the board
      needle: null,
      n1: probe && pos.n1 && R.byHand ? { x: pos.n1.x, y: pos.n1.y } : null,
      probe,
      fids,
    });
    // view switch: Dots | Whole board (idle), Follow needle | Dots (running), none after STOP
    const views = mode === 'live' ? [['follow', 'Follow needle'], ['dots', 'Dots']] : mode === 'stopped' ? [] : [['dots', 'Dots'], ['board', 'Whole board']];
    if (!views.some((v) => v[0] === bv.view)) bv.setView('dots');
    IL.keyed(n.viewSeg, JSON.stringify(views) + bv.view, () => views.map(([v, t]) => el('button', { type: 'button', 'aria-pressed': String(bv.view === v),
      onclick: () => { bv.setView(v); IL.render(); } }, t)));
    IL.show(n.viewSeg, views.length > 0);
    IL.patch(n.zoomIn, { disabled: !bv.canZoomIn });
    IL.patch(n.zoomOut, { disabled: !bv.canZoomOut });
    renderLegend(s, p, mode, dirty, chk, probe, posWrong && (mode === 'idle' || mode === 'empty'), fids);
    renderTimeline(s, p, mode);
  }
  function renderLegend(s, p, mode, dirty, chk, probe, posWrong, fids) {
    const n = R.n;
    const N = p.dots.length;
    const done = R.doneSet.size;
    const items = [];
    const layer = (key, cls, text) => ({ key, cls, text });
    if (mode === 'live') {
      // the board's count (the tiles count this run): one base, named
      items.push(layer(null, 'lg-done', 'on the board · ' + done + ' of ' + N), layer(null, 'lg-dot', 'to do · ' + Math.max(0, N - done)), layer(null, 'lg-next', 'next dot'));
    } else if (mode === 'stopped') {
      items.push(layer(null, 'lg-done', 'dispensed · ' + done), layer(null, 'lg-dot', 'not dispensed · ' + Math.max(0, N - done)));
      if (chk) items.push(layer(null, 'lg-check', 'in progress at STOP · 1'));
      if (fids && fids.length) items.push(layer(null, 'lg-fid', 'fiducial'));
      if (probe) items.push(layer(null, 'lg-probe', 'probe spot'));
    } else {
      // at most four, by importance: what needs the operator first, the plain layers last
      const blocked = (p.pads || []).filter((x) => x.blocked).length;
      const all = [];
      if (posWrong && !probe) all.push([0, layer(null, 'lg-check', 'pad check: board position looks wrong')]);
      if (dirty.size && !probe) all.push([1, layer('dirty', 'lg-dirty', 'paste found · ' + dirty.size)]);
      if (blocked) all.push([2, layer(null, 'lg-dirty', 'cannot dispense · ' + blocked)]);
      if (fids && fids.length) all.push([2.5, layer(null, 'lg-fid', 'fiducial · search ' + IL.fmt(fids[0].r, 0) + ' mm')]);
      // no probe spot when the engine found none (Register board then says why): the legend names only what is drawn
      if (probe && probe.targets && probe.targets.length) all.push([3, layer(null, 'lg-probe', 'probe spot')]);
      if (probe) all.push([4, layer(null, 'lg-area', 'probed area')]);
      all.push([5, layer('dots', 'lg-dot', 'planned dot')]);
      if (done) all.push([6, layer(null, 'lg-done', 'dispensed · ' + done)]);
      all.push([7, layer('pads', 'lg-pad', 'pad')], [8, layer('path', 'lg-path', 'path')]);
      // as many as fit on one line (four at most): R.lgMax drops one at a time while the legend overflows
      const base = JSON.stringify(all);
      if (R.lgBase !== base) { R.lgBase = base; R.lgMax = 4; }
      const keep = all.sort((a, b) => a[0] - b[0]).slice(0, R.lgMax).map((x) => x[1]);
      const order = ['pads', 'dots'];
      keep.sort((a, b) => (order.includes(a.key) ? order.indexOf(a.key) : 9) - (order.includes(b.key) ? order.indexOf(b.key) : 9));
      items.push(...keep);
    }
    const key = JSON.stringify(items) + '|' + Array.from(R.hidden).join(',');
    const rebuilt = IL.keyed(n.legend, key, () => items.map((x) => (x.key
      ? el('button', { type: 'button', 'aria-pressed': String(!R.hidden.has(x.key)), title: 'Show or hide: ' + x.text.split(' · ')[0],
        onclick: () => { if (R.hidden.has(x.key)) R.hidden.delete(x.key); else R.hidden.add(x.key); R.bv.layers(R.hidden); IL.render(); } },
      el('i', { class: 'lg ' + x.cls }), x.text)
      : el('span', {}, el('i', { class: 'lg ' + x.cls }), x.text))));
    // down to the most important one: with the stage bar's picture and preview controls, two long items ("cannot dispense ·
    // 149", "fiducial · search 8 mm") were cut at 1280 and 1440 px
    if (rebuilt && mode !== 'live' && mode !== 'stopped' && R.lgMax > 1 && n.legend.clientWidth && n.legend.scrollWidth > n.legend.clientWidth + 1) {
      R.lgMax--;
      renderLegend(s, p, mode, dirty, chk, probe, posWrong, fids);
    }
  }
  window.addEventListener('resize', () => { R.lgBase = null; });
  function renderTimeline(s, p, mode) {
    const n = R.n;
    const run = boardRunOfPlan(s);
    const on = mode === 'live' && run;
    IL.show(n.timeline, !!on);
    if (!on) return;
    const now = Date.now();
    const t = phaseTimes(s, run, now);
    const segs = [];
    const inCam = /I10|clean/i.test(run.phase || '');
    // [class, width (s), label, title, short label]
    if (inCam && t.est) {
      const tp = tourProgress(run, t.cam || 0, (p.pads || []).length);
      segs.push(['tl-cam', Math.max(t.est.tour, t.cam || 0), 'Camera check · ' + tp.text + ' · ' + IL.fmtClock(t.cam || 0), null, 'Camera ' + IL.fmtClock(t.cam || 0)]);
      t.left = t.est.pressure;
    } else if (t.cam !== null) segs.push(['tl-cam', t.cam, 'Camera check · ' + IL.plural(run.dotsPlanned, 'pad') + ' · ' + IL.fmtClock(t.cam), null, 'Camera ' + IL.fmtClock(t.cam)]);
    if (t.pre !== null) segs.push(['tl-pre', Math.max(t.pre, 0.5), '', 'Pump, valve, ' + ((p.profile && p.profile.purgeDots) || 0) + ' purge dots, crossing: ' + IL.fmtClock(t.pre)]);
    if (t.dots !== null && run.dotsDone > 0) segs.push(['tl-dots', t.dots, IL.plural(run.dotsDone, 'dot') + ' · ' + IL.fmtClock(t.dots), null, IL.plural(run.dotsDone, 'dot')]);
    if (t.left !== null) segs.push(['tl-left', Math.max(t.left, 1), '≈ ' + IL.fmtClock(t.left) + ' left', null, '≈ ' + IL.fmtClock(t.left)]);
    const label = segs.map((x) => x[3] || x[2]).filter(Boolean).join(', ');
    IL.setAttr(n.timeline, 'aria-label', 'Session time: ' + label);
    const key = segs.map((x) => x[0] + Math.round(x[1]) + x[2]).join('|');
    if (IL.keyed(n.timeline, key, () => segs.map(([cls, w, text, title, short]) => el('span', { class: cls, style: 'flex:' + Math.max(0.5, w).toFixed(1),
      title: title || text || null, 'data-short': short || '' }, text)))) {
      // measured once per rebuild (each dot): a label never shows clipped
      for (const sp of n.timeline.children) {
        if (sp.scrollWidth <= sp.clientWidth + 1) continue;
        sp.textContent = sp.dataset.short || '';
        if (sp.scrollWidth > sp.clientWidth + 1) sp.textContent = '';
      }
    }
  }
  /** Seconds of the board run's parts so far: {cam, pre, dots, left, elapsed, pressureS} (client stamps, §8.1.6). */
  function phaseTimes(s, run, now) {
    const at = R.ph.runId === run.runId ? R.ph.at : {};
    const camKey = Object.keys(at).find((k) => /I10|clean/i.test(k));
    const disp = at.dispensing || null;
    const end = run.running ? now : (R.ph.endAt || now);
    const cam = camKey ? ((disp || end) - at[camKey]) / 1000 : null;
    const first = R.ph.runId === run.runId ? R.ph.firstDotAt : null;
    const pre = disp ? ((first || end) - disp) / 1000 : null;
    const dots = first ? (end - first) / 1000 : null;
    const est = estimate(s, run.dotsPlanned, run.dotsPlanned);
    let left = null;
    if (run.running) {
      if (run.dotsDone > 0) left = IL.boardLeftS(run);
      else if (est) {
        // before the first dot: what is left of the camera tour, of the settle and purge, then every dot
        const tourLeft = disp ? 0 : camKey ? Math.max(0, est.tour - (cam || 0)) : est.tour;
        left = tourLeft + (disp ? Math.max(0, est.pre - (pre || 0)) : est.pre) + est.work;
      }
    }
    const elapsed = run.startedAtMs ? (end - (run.startedAtMs - S.skewMs)) / 1000 : run.elapsedS;
    // §17 optional run.pressureOnAtMs is exact; else this page's stamp of the first "dispensing" poll
    const pressureS = isNum(run.pressureOnAtMs) ? (end - (run.pressureOnAtMs - S.skewMs)) / 1000 : disp ? (end - disp) / 1000 : 0;
    return { cam, pre, dots, left, elapsed, pressureS, est };
  }

  function renderSummary(s, p) {
    const n = R.n;
    const last = lastBoardRow();
    const lastTile = () => {
      if (!last) return tile('Last board', el('span', { class: 'n n--sm', text: '—' }), 'no board run recorded yet', { id: 'run-last' });
      const ok = last.status === 'OK';
      const sub = 'dots only · ' + (last.boardName ? boardShort(last.boardName) + ', ' : '') + IL.fmtTime(last.startedAtMs - S.skewMs)
        + ' · ' + (STATUS_WORD[last.status] || last.status).toLowerCase() + ', ' + outcomeText(last, null);
      return tile('Last board', [el('span', { class: 'n', text: dph(last.dphMotion) }), el('span', { class: 'u', text: 'DPH' })], sub,
        { id: 'run-last', cls: ok ? '' : 'tile--block', title: 'Dots only: from the first dot to the last. Overall: ' + dph(last.dphOverall) + ' DPH. Never replaced by a prime, test dot or clean check.' });
    };
    if (!p) {
      IL.keyed(n.summary, 'empty|' + (last ? last.runId : ''), () => [tile('Dots', el('span', { class: 'n', text: '—' }), 'plan to count'),
        tile('Board time', el('span', { class: 'n', text: '—' }), 'plan to estimate'),
        tile(['', dphAbbr(), ' estimate'], null, null, { aside: 'bench ' + IL.fmtInt(BENCH_DPH), extra: pair('—', 'dots only', '—', 'overall') }),
        lastTile()]);
      return;
    }
    const sm = p.summary || {};
    const est = estimate(s);
    const tourOn = cleanEnabled(s);
    const key = JSON.stringify([p.planId, sm.dots, sm.pads, sm.blockedPads, est && Math.round(est.total), tourOn, last && last.runId, last && last.status]);
    IL.keyed(n.summary, key, () => [
      tile('Dots', el('span', { class: 'n', text: IL.fmtInt(sm.dots) }), 'on ' + IL.plural(sm.pads, 'pad') + (sm.blockedPads ? ', ' + sm.blockedPads + ' cannot' : ''),
        { title: sm.pads + ' pads, ' + (sm.blockedPads || 0) + ' blocked; path ' + IL.fmt(sm.pathLengthMm, 1) + ' mm' }),
      tile('Board time', est ? units(IL.fmtDuration(est.total)) : el('span', { class: 'n', text: '—' }),
        est ? (tourOn ? 'incl. ' + IL.fmtClock(est.tour) + ' pad check' : 'pad check off') : (sm.estimateError || 'no estimate'),
        { title: est ? (tourOn ? IL.fmtClock(est.tour) + ' camera pad check (pressure off) + ' : '') + IL.fmtClock(est.pressure)
          + ' dispensing: settle, ' + sm.purgeDots + ' purge dots, crossing, every dot, vent (moves with acceleration and dwells; no host delays)' : null }),
      tile(['', dphAbbr(), ' estimate'], null, null, { aside: 'bench ' + IL.fmtInt(BENCH_DPH),
        extra: pair(dph(sm.estimatedWorkDph), 'dots only', dph(sm.estimatedDph), 'overall'),
        title: 'Dots only: from the crossing after the purge to the last dot, as the bench counts it (18 psi recipe: ' + IL.fmtInt(BENCH_DPH)
          + ' measured). Overall: the whole program with settle, purge and vent, without the camera pad check.' }),
      lastTile(),
    ]);
  }

  function renderLive(s, p) {
    const n = R.n;
    const run = boardRunOfPlan(s);
    const now = Date.now();
    const t = phaseTimes(s, run, now);
    const pct = run.dotsPlanned ? Math.min(100, 100 * run.dotsDone / run.dotsPlanned) : 0;
    const overall = t.pressureS > 3 && run.dotsDone > 0 ? run.dotsDone / t.pressureS * 3600 : null;
    const sm = p.summary || {};
    const whole = t.left !== null && t.elapsed !== null ? t.elapsed + t.left : null;
    const camKey = Object.keys(R.ph.at || {}).some((k) => /I10|clean/i.test(k));
    // a subset run (test, the pads left) is not the whole board: say what the total is for
    const subset = run.planId !== p.planId || run.dotsPlanned < p.dots.length;
    const kind = R.kinds[run.planId];
    const forWhat = !subset ? 'whole board' : kind === 'rest' ? 'these ' + IL.plural(run.dotsPlanned, 'dot') : 'this test';
    const subLeft = [(t.pressureS > 0 ? 'dispensing ' + IL.fmtClock(t.pressureS) : /I10|clean/i.test(run.phase || '') ? 'checking pads, pressure off' : 'starting'),
      whole ? [' · ', el('span', { class: 'nowrap', text: forWhat + ' ≈ ' + IL.fmtClock(whole) }), camKey ? ' incl. pad check' : ''] : ''];
    const key = JSON.stringify([run.runId, run.dotsDone, run.dotsPlanned, Math.round(t.left || -1), Math.round(t.pressureS), Math.round(run.liveDph || 0), Math.round(overall || 0), R.doneSet.size]);
    IL.keyed(n.live, key, () => [
      tile('Dots', [el('span', { class: 'n', text: String(run.dotsDone) }), el('span', { class: 'u', text: 'of ' + run.dotsPlanned })],
        subset ? 'this run · board ' + R.doneSet.size + ' of ' + p.dots.length : null,
        { cls: 'tile--big', extra: el('div', { class: 'bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': run.dotsPlanned, 'aria-valuenow': run.dotsDone, 'aria-label': 'Dots done' },
          el('i', { id: 'run-bar', style: 'width:' + pct.toFixed(1) + '%' })) }),
      tile('Time left', el('span', { class: 'n', text: t.left !== null ? IL.fmtClock(t.left) : '—' }), subLeft, { cls: 'tile--big' }),
      tile(['Live ', dphAbbr()], null, el('span', {}, 'estimate ' + dph(sm.estimatedWorkDph) + ' · ' + dph(sm.estimatedDph)),
        { aside: 'bench ' + IL.fmtInt(BENCH_DPH), extra: pair(dph(run.liveDph), 'dots only', overall ? IL.fmtInt(overall) : '—', 'overall so far'),
          title: 'Dots only: since the first board dot. Overall so far: dots over the time the pressure has been on.' }),
    ]);
  }

  function renderResultTiles(s, p) {
    const n = R.n;
    const rr = resultRun(s);
    if (!rr) return;
    const pct = rr.dotsPlanned ? Math.min(100, 100 * rr.dotsDone / rr.dotsPlanned) : 0;
    const row = (s.dispense && s.dispense.purgeRow) || {};
    const sm = p.summary || {};
    const key = JSON.stringify([rr.runId, rr.status, rr.dotsDone, rr.dphMotion, row.spotsLeft]);
    IL.keyed(n.live, key, () => [
      tile('Dots', [el('span', { class: 'n', text: String(rr.dotsDone) }), el('span', { class: 'u', text: 'of ' + rr.dotsPlanned })],
        null, { cls: rr.status === 'OK' ? '' : 'tile--block', extra: [el('div', { class: 'bar' }, el('i', { id: 'run-bar', style: 'width:' + pct.toFixed(1) + '%' })),
          el('div', { class: 'tile__s', style: 'margin-top:6px', text: (STATUS_WORD[rr.status] || rr.status) + ' · ' + outcomeText(rr, p) })] }),
      tile('Board time', el('span', { class: 'n', text: isNum(rr.totalSeconds) ? IL.fmtClock(rr.totalSeconds) : '—' }),
        isNum(rr.motionSeconds) ? [el('span', { class: 'nowrap', text: 'dots ' + IL.fmtClock(rr.motionSeconds) }), ' · ', el('span', { class: 'nowrap', text: 'ended ' + IL.fmtTime(rr.endedAtMs - S.skewMs) })] : null),
      tile(['', dphAbbr()], null, [el('span', { class: 'nowrap', text: 'estimate ' + dph(sm.estimatedWorkDph) }), ' · ', el('span', { class: 'nowrap', text: dph(sm.estimatedDph) })], { aside: 'bench ' + IL.fmtInt(BENCH_DPH),
        extra: pair(dph(rr.dphMotion), 'dots only', dph(rr.dphOverall), 'overall') }),
      tile('Purge row', row.defined ? [el('span', { class: 'n', text: String(row.spotsLeft) }), el('span', { class: 'u', text: 'of ' + row.spots })] : el('span', { class: 'n', text: '—' }),
        row.defined ? (row.spotsLeft < IL.purgeNeed(row, p) ? 'wipe and reset before the next board' : 'spots free · next board uses ' + IL.purgeNeed(row, p)) : 'not set',
        { cls: row.defined && row.spotsLeft < IL.purgeNeed(row, p) ? 'tile--need' : '' }),
    ]);
  }

  function padsLeft(p) {
    const left = [];
    const byPad = new Map();
    for (const d of p.dots) { if (!byPad.has(d.padId)) byPad.set(d.padId, []); byPad.get(d.padId).push(d.index); }
    for (const [pid, idx] of byPad) if (!idx.every((i) => R.doneSet.has(i))) left.push(pid);
    return left;
  }
  /** Pads with paste from this board's runs (at least one dot done). */
  function padsDone(p) {
    const out = new Set();
    for (const d of p.dots) if (R.doneSet.has(d.index)) out.add(d.padId);
    return Array.from(out);
  }
  function renderDecide(s, p) {
    const n = R.n;
    const rec = decision(s);
    if (!rec) return;
    const left = padsLeft(p);
    R.padsLeft = left;
    const active = IL.recoveryActive();
    const r = IL.ready();
    const cc = cleanEnabled(s);
    const zero = zeroDots(rec);
    const pasted = padsDone(p).length;
    const partial = rec.cause === 'partial';
    IL.setText(n.decideQ, zero ? 'No dot reached the board: run it again once the recovery steps are done.'
      : (partial ? (rec.kind === 'test' ? 'The test run put paste on ' : 'Paste is on ') + IL.plural(pasted, 'pad') + '. ' : '')
        + 'Finish the ' + IL.plural(left.length, 'pad') + ' left, or wipe ' + (partial ? 'the ' + IL.plural(pasted, 'pasted pad') : 'all pads') + ' and run the whole board.');
    setBtnLabel(n.rest, [zero ? 'Run the board again' : 'Finish ' + IL.plural(left.length, 'pad')]);
    setBtnLabel(n.all, [partial ? 'Wipe the ' + IL.plural(pasted, 'pad') + ', run all' : 'Wipe all, run all']);
    IL.show(n.all, !zero);
    let why = null;
    if (active) {
      const steps = IL.recoverySteps(s, S.recovery).filter((x) => x.key !== 'decide');
      why = (zero ? 'Unlocks' : 'Both unlock') + ' after steps ' + (steps.length > 1 ? '2 to ' + steps.length : '1') + ' on the right.'
        + (cc && !zero ? ' A whole-board run is refused while the ' + IL.plural(R.doneSet.size || rec.run.dotsDone, 'dispensed pad') + ' read dirty (I10).' : '');
    } else if (!r.canRun) why = r.runReason;
    else if (cc && !zero) why = 'A whole-board run checks every pad first and is refused while the ' + IL.plural(pasted, 'pasted pad') + ' read dirty (I10): wipe them first.';
    else if (!cc && !zero) why = 'The pad check is off: a whole-board run dispenses again on the ' + IL.plural(pasted, 'pasted pad') + ' unless you wipe them first.';
    IL.setText(n.decideWhy.lastChild, why || '');
    IL.show(n.decideWhy, !!why);
    const lock = active ? 'Unlocks after the recovery steps on the right' : !r.canRun ? r.runReason : null;
    IL.gate(n.rest, lock || (!zero && !left.length ? 'No pads left' : null));
    IL.gate(n.all, lock);
    IL.setClass(n.rest, 'btn btn--lg' + (!lock && (zero || left.length) ? ' btn--primary' : ''));
  }

  // ================================================================================== panel: stepper
  function stepStates(s) {
    const p = plan();
    const r = IL.ready();
    const run = s.run;
    const st = {};
    st.board = p ? { kind: 'ok', sub: boardShort(p.boardName) } : { kind: 'num', sub: 'choose' };
    const sf = r.items.find((x) => x.key === 'surface');
    const surf = s.dispense && s.dispense.surfaces && s.dispense.surfaces[s.dispense.boardSurfaceName || 'board'];
    // Register: "registered", "probed" (probe only, F10), "same seat" (the 3-point check kept the surface), "by hand" (the time in the title: the
    // five steps share about 400 px), "this seat", "again" (blocked: register the board again, or probe it: no fiducials)
    const reg = p && p.registration;
    const at = (ms) => IL.fmtTime(ms ? ms - S.skewMs : null);
    const live = regLive(s);
    if (!p) st.probe = { kind: 'num', sub: '—' };
    else if (live) st.probe = { kind: 'live', sub: IL.regProgress(live).part };
    else if (sf.state === 'ok') {
      const sameAt = reg && reg.lastCheckedAtMs && surf && surf.setAtMs && reg.lastCheckedAtMs > surf.setAtMs ? reg.lastCheckedAtMs : null;
      st.probe = sameAt ? { kind: 'ok', sub: 'same seat', title: 'same seat ' + at(sameAt) }
        : surf && /^Register board/.test(surf.note || '') ? (/\(no fiducials: probed only/.test(surf.note) ? { kind: 'ok', sub: 'probed', title: 'probed only ' + at(surf.setAtMs) }
          : { kind: 'ok', sub: 'registered', title: 'registered ' + at(surf.setAtMs) })
          : { kind: 'ok', sub: 'by hand', title: 'by hand ' + at(surf && surf.setAtMs) };
    } else st.probe = sf.state === 'block' ? { kind: 'block', sub: 'again', title: (probeOnlyPlan(p) ? 'probe' : 'register') + ' the board again' } : { kind: 'need', sub: 'this seat' };
    const needs = r.needs.filter((x) => x.state !== 'wait');
    st.checks = !p ? { kind: 'num', sub: '—' } : r.blocked.length ? { kind: 'block', sub: 'blocked' }
      : needs.length ? { kind: 'need', sub: IL.plural(needs.length, 'needs you', 'need you') } : { kind: 'ok', sub: '9 of 9' };
    if (runningBoard(s) && IL.planMatches(run.planId)) {
      st.run = { kind: 'live', sub: run.dotsDone + ' of ' + run.dotsPlanned };
      const t = phaseTimes(s, run, Date.now());
      st.result = { kind: 'num', sub: t.left !== null ? '≈ ' + IL.fmtClock(t.left) : '…' };
    } else {
      const est = p ? estimate(s) : null;
      st.run = { kind: 'num', sub: est ? '≈ ' + Math.max(1, Math.round(est.total / 60)) + ' min' : '—' };
      const rr = p ? resultRun(s) : null;
      const dec = p ? decision(s) : null;
      if (dec && dec.cause !== 'partial') st.run = { kind: 'block', sub: zeroDots(dec) ? 'no dots' : 'dot ' + dec.run.dotsDone };
      else if (dec) st.run = { kind: 'need', sub: dec.run.dotsDone + ' of ' + dec.run.dotsPlanned };
      else if (rr && rr.endedAtMs && rr.endedAtMs - S.skewMs > R.decidedAt) {
        st.run = rr.status === 'OK' ? { kind: 'ok', sub: 'ran ' + (isNum(rr.totalSeconds) ? IL.fmtClock(rr.totalSeconds) : '') }
          : rr.status === 'REFUSED' ? { kind: 'need', sub: 'refused' } : { kind: 'block', sub: 'dot ' + rr.dotsDone };
      }
      const last = rr || lastBoardRow();
      // numbered by the board that ran (the server's boardNumber of the run), not the board on the machine now
      const ran = last && isNum(last.boardNumber) ? 'board ' + last.boardNumber + ' ' : '';
      st.result = last ? { kind: last.status === 'OK' ? 'ok' : last.status === 'REFUSED' ? 'need' : 'block',
        sub: last.status === 'OK' ? ran + 'ok' : { ABORTED: 'stopped', ERROR: 'error', REFUSED: 'refused' }[last.status] || '—' } : { kind: 'num', sub: '—' };
      // a result from before "Next board" (or a new plan) is the last board's: no tick for the board waiting now
      if (!rr || !(rr.endedAtMs && rr.endedAtMs - S.skewMs > R.decidedAt)) st.result.kind = 'num';
    }
    return st;
  }
  function renderSteps(top, s, step) {
    const st = stepStates(s);
    // rebuilt only when a step's state or the current step changes; the counters under the steps ("96 of 160",
    // "≈ 1:23") are patched in place (a rebuild on every poll of a run dropped keyboard focus and lost clicks)
    const key = JSON.stringify([STEPS.map((k) => st[k].kind), step]);
    IL.keyed(top, key, () => el('nav', { class: 'steps', 'aria-label': 'This board' }, STEPS.map((k, i) => {
      const x = st[k];
      const glyph = x.kind === 'num' ? IL.glyph('num', null, 'Step ' + (i + 1), i + 1) : IL.glyph(x.kind);
      return el('button', { type: 'button', class: 'step step--' + x.kind + (x.kind === 'ok' ? ' step--done' : ''), 'data-step': k,
        'data-focus-key': 'step-' + k, 'aria-current': step === k ? 'step' : null, onclick: () => goStep(k) },
      glyph, el('span', { class: 'step__t', text: STEP_T[k] }), el('span', { class: 'step__s' }));
    })));
    for (const b of top.querySelectorAll('.step')) {
      const k = b.dataset.step;
      IL.setText(b.querySelector('.step__s'), st[k].sub);
      IL.setAttr(b, 'title', STEP_T[k] + ': ' + (st[k].title || st[k].sub));
    }
  }

  // ================================================================================== panel: Board step
  function selectedBoard() {
    const v = R.form.sel;
    if (!v) return null;
    if (v.startsWith('job:')) { const b = jobEntry(v.slice(4)); return b ? { kind: 'job', id: b.boardLocationId, name: b.name, b } : { kind: 'job', id: v.slice(4), name: 'Job board' }; }
    if (v.startsWith('file:')) { const file = v.slice(5); const b = R.boards && (R.boards.files || []).find((x) => x.file === file); return { kind: 'file', file, name: b ? b.name : file.split('/').pop() }; }
    if (v === 'other') return R.form.file ? { kind: 'file', file: R.form.file, name: R.form.file.split('/').pop(), other: true } : null;
    return null;
  }
  async function loadBoards() {
    if (R.boardsLoading) return;
    R.boardsLoading = true;
    R.boardsErr = null;
    IL.render();
    try { R.boards = await IL.api('GET', '/api/boards'); } catch (e) { R.boardsErr = IL.errorText(e); }
    R.boardsLoading = false;
    syncSelect(true);
    IL.render();
  }
  /** The board form follows the plan shown unless the operator is editing it. */
  function formFromPlan(p) {
    if (!p || !p.source || R.formDirty) return;
    const src = p.source;
    if (src.source === 'job') R.form = Object.assign(R.form, { sel: 'job:' + src.boardLocationId });
    else {
      const known = !R.boards || (R.boards.files || []).some((b) => b.file === src.file);
      R.form = Object.assign(R.form, { sel: known ? 'file:' + src.file : 'other', file: known ? '' : src.file,
        x: IL.inputNum(src.x), y: IL.inputNum(src.y), z: IL.inputNum(src.z), rot: IL.inputNum(src.rotation || 0) });
    }
  }
  function syncSelect(rebuild) {
    const sel = $('board-select');
    if (!sel) return;
    if (rebuild || !sel.options.length) {
      const opts = [];
      const b = R.boards;
      if (!b && R.boardsLoading) opts.push(el('option', { value: '', text: 'Loading boards…' }));
      if (b && (b.job || []).length) {
        opts.push(el('optgroup', { label: 'Job open in OpenPnP' }, b.job.map((x) => el('option', { value: 'job:' + x.boardLocationId,
          text: x.name + ' · ' + (x.fiducialTransform && x.fiducialTransform !== 'NotSet' ? 'fiducials checked' : 'run a fiducial check in OpenPnP') }))));
      }
      if (b && (b.files || []).length) {
        opts.push(el('optgroup', { label: 'Board files' }, b.files.filter((x) => x.file).map((x) => {
          // the folder tells boards of one name apart; an upload's folder is its name ("mobo · mobo" said it twice)
          const dir = x.file.split('/').slice(-2, -1)[0];
          const own = !!dir && String(x.name || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').startsWith(dir);
          return el('option', { value: 'file:' + x.file, title: x.file + (x.placements ? ' · ' + x.placements + ' placements' : ''), text: x.name + (dir && !own ? ' · ' + dir : '') });
        })));
      }
      if (!b && !R.boardsLoading && R.form.sel && R.form.sel !== 'other') opts.push(el('option', { value: R.form.sel, text: (selectedBoard() || {}).name || R.form.sel }));
      if (!R.form.sel) opts.unshift(el('option', { value: '', text: 'Choose a board…' }));
      opts.push(el('option', { value: 'other', text: 'Other board file…' }));
      sel.replaceChildren(...opts);
    }
    const want = R.form.sel || '';
    if (![...sel.options].some((o) => o.value === want) && !rebuild) { syncSelect(true); return; }
    if (sel.value !== want) sel.value = want;
  }
  function onBoardSelect() { selectBoard($('board-select').value); }
  /** Chooses a board (the select, or a board-import thumbnail through H1): its position from the job or remembered. */
  function selectBoard(v) {
    const sel = $('board-select');
    R.form.sel = v || null;
    if (sel && sel.value !== (v || '')) syncSelect(![...sel.options].some((o) => o.value === (v || '')));
    lsSet('il.run.sel', v || null);
    const c = selectedBoard();
    R.form.zFrom = null;
    if (c && c.kind === 'job' && c.b) {
      Object.assign(R.form, { x: IL.inputNum(c.b.x), y: IL.inputNum(c.b.y), z: IL.inputNum(c.b.z), rot: IL.inputNum(c.b.rotation) });
    } else if (c && c.kind === 'file') {
      const rem = remembered(c.file);
      if (rem) Object.assign(R.form, { x: IL.inputNum(rem.x), y: IL.inputNum(rem.y), z: IL.inputNum(rem.z), rot: IL.inputNum(rem.rotation || 0) });
    }
    R.formDirty = true;
    fillForm();
    IL.render();
  }
  /**
   * An empty Board top Z is prefilled (§8.1 Board step): the last board surface of this board (its plane at the board
   * centre), else the needle-frame surface. R.form.zFrom says where it came from (shown under the field).
   */
  function prefillZ(s) {
    if (String(R.form.z === undefined ? '' : R.form.z).trim() !== '') return;
    const c = selectedBoard();
    if (!c || c.kind === 'job') return;
    const d = (s && s.dispense) || {};
    const sf = d.surfaces && d.surfaces[d.boardSurfaceName || 'board'];
    const p = plan();
    const same = p && p.source && p.source.file === c.file;
    if (same && sf && sf.plane && isNum(sf.plane.a)) {
      const bx = p.board && isNum(p.board.widthMm) ? Number(p.source.x) + p.board.widthMm / 2 : Number(p.source.x);
      const by = p.board && isNum(p.board.heightMm) ? Number(p.source.y) + p.board.heightMm / 2 : Number(p.source.y);
      R.form.z = (sf.plane.a + sf.plane.b * bx + sf.plane.c * by).toFixed(2);
      R.form.zFrom = 'prefilled from the board surface probed ' + IL.fmtTime(sf.setAtMs - S.skewMs);
    } else if (d.needle && d.needle.anchor && isNum(d.needle.anchor.surfaceZMm)) {
      R.form.z = Number(d.needle.anchor.surfaceZMm).toFixed(2);
      R.form.zFrom = 'prefilled from the needle frame (Setup)';
    }
  }
  function fillForm() {
    for (const [id, k] of [['board-x', 'x'], ['board-y', 'y'], ['board-z', 'z'], ['board-rot', 'rot'], ['board-file', 'file']]) {
      const i = $(id);
      if (i && document.activeElement !== i && i.value !== String(R.form[k] === undefined ? '' : R.form[k])) i.value = R.form[k] === undefined ? '' : R.form[k];
    }
  }
  function field(id, label, unit, attrs) {
    const input = el('input', Object.assign({ class: 'input', id, type: 'number', inputmode: 'decimal', autocomplete: 'off' }, attrs || {}));
    return el('div', { class: 'field' }, el('label', { class: 'field__l', for: id }, el('span', {}, label), el('span', { class: 'unit', text: unit })),
      input, el('div', { class: 'field__err', id: id + '-err', hidden: true }));
  }
  function buildBoard(p) {
    const body = el('div', { class: 'stack', 'data-enter-submits': 'btn-plan' });
    const sel = el('select', { class: 'input', id: 'board-select', title: 'A board of the job open in OpenPnP (position and fiducial check from the job), or a board file placed by hand' });
    sel.addEventListener('change', onBoardSelect);
    const refresh = el('button', { type: 'button', class: 'btn btn--icon', id: 'btn-board-refresh', title: 'Reload the board list from OpenPnP', 'aria-label': 'Refresh the board list' }, IL.icon('i-refresh'));
    refresh.addEventListener('click', () => loadBoards());
    const file = el('input', { class: 'input', id: 'board-file', type: 'text', placeholder: '/home/…/my.board.xml', autocomplete: 'off', spellcheck: 'false' });
    file.addEventListener('input', () => { R.form.file = file.value.trim(); R.formDirty = true; IL.render(); });
    const fileRow = el('div', { class: 'field', id: 'board-file-row' }, el('label', { class: 'field__l', for: 'board-file' }, el('span', { text: 'Board file' }), el('span', { class: 'unit', text: '.board.xml' })), file);
    const pos = el('div', { class: 'board-pos' },
      field('board-x', 'X (board origin)', 'mm', { step: '0.01' }), field('board-y', 'Y (board origin)', 'mm', { step: '0.01' }),
      field('board-z', 'Board top Z (approximate, ±1\u00a0mm)', 'mm', { step: '0.01', placeholder: 'required',
        title: 'Roughly the height of the board top as N1 sees it (OpenPnP frame). Register board measures it; within ±1 mm is enough.' }),
      field('board-rot', 'Rotation', '°', { step: '0.1' }));
    for (const [id, k] of [['board-x', 'x'], ['board-y', 'y'], ['board-z', 'z'], ['board-rot', 'rot']]) {
      pos.querySelector('#' + id).addEventListener('input', (e) => { R.form[k] = e.target.value; R.formDirty = true; if (k === 'z') R.form.zFrom = null; const er = $(id + '-err'); if (er) er.hidden = true; e.target.classList.remove('input--invalid'); IL.render(); });
    }
    // the Board top Z: prefilled when empty (never mistaken for a measurement), and the window / frame check: a full-width
    // row under the Z and Rotation fields (inside the Z field it pushed Rotation's input out of line)
    pos.append(el('div', { class: 'field__h', id: 'board-z-note', hidden: true }));
    const useCam = el('button', { type: 'button', class: 'capture', id: 'btn-board-use-cam', title: 'Board origin X/Y = where the top camera is now (jog it onto the board origin in OpenPnP first)' });
    const useZ = el('button', { type: 'button', class: 'capture', id: 'btn-board-use-n1z', title: 'Board top Z = the N1 nozzle Z now (lower N1 until its tip touches the board top in OpenPnP first)' });
    useCam.addEventListener('click', () => { const c = IL.positions().cam; if (!c) return; R.form.x = c.x.toFixed(2); R.form.y = c.y.toFixed(2); R.formDirty = true; fillForm(); IL.render(); });
    useZ.addEventListener('click', () => { const n1 = IL.positions().n1; if (!n1) return; R.form.z = n1.z.toFixed(2); R.form.zFrom = 'live N1 Z'; R.formDirty = true; fillForm(); IL.render(); });
    const seatQ = el('div', { class: 'seat-q', id: 'board-seat-q', hidden: true },
      el('div', { class: 'seat-q__t', id: 'board-seat-t' }),
      el('div', { class: 'seg', role: 'group', 'aria-label': 'Re-seated?' },
        el('button', { type: 'button', id: 'board-seat-new', 'aria-pressed': 'false', onclick: () => { IL.setNewSeat(Date.now()); R.seatSame = null; IL.render(); } }, 'Yes: new seat'),
        el('button', { type: 'button', id: 'board-seat-same', 'aria-pressed': 'false', onclick: () => { R.seatSame = seatSince(); IL.setNewSeat(null); IL.render(); } }, 'No, same seat')),
      el('div', { class: 'field__h', id: 'board-seat-h' }));
    body.append(
      el('div', { class: 'board-sel-row' }, el('div', { class: 'field' }, el('label', { class: 'field__l', for: 'board-select' }, el('span', { text: 'Board' }), el('span', { class: 'unit', id: 'board-count' })), sel), refresh),
      fileRow,
      el('div', { class: 'reason', id: 'board-hint' }),
      seatQ,
      IL.sectionK('Position on the machine'),
      pos,
      el('div', { class: 'capture-row', id: 'board-captures' }, useCam, useZ),
      el('div', { class: 'reason', id: 'board-capture-why', hidden: true }));
    // H1 (BOARD_IMPORT_DESIGN.md §5.1.1): upload, thumbnails and the origin from the drawing, once boardflow.js loads
    if (IL.boardFlow) {
      IL.boardFlow.mountBoardStep(body, { select: (v) => selectBoard(v), form: () => R.form, loadBoards,
        setXY: (x, y) => { R.form.x = Number(x).toFixed(2); R.form.y = Number(y).toFixed(2); R.formDirty = true; fillForm(); IL.render(); } });
    }
    return body;
  }
  function buildBoardFoot() {
    const btn = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-plan',
      title: 'Work out where every dot goes (one dot of the dispense profile per pad) and the order. Nothing moves.' }, IL.icon('i-run'), 'Plan');
    btn.addEventListener('click', () => doPlan(btn));
    return el('div', { class: 'stack', style: 'gap:8px', 'data-refusals': '' }, btn, el('div', { class: 'reason', id: 'plan-why', hidden: true }),
      el('div', { class: 'run-foot__links' }, el('span', { text: 'Planning moves nothing.' }), el('span', { id: 'plan-next-hint' })));
  }
  /** When the position of the selected board was entered (or its plan made): the re-seat question asks "since then". */
  function seatSince() {
    const c = selectedBoard();
    const rem = c && c.kind === 'file' ? remembered(c.file) : null;
    const p0 = plan();
    return rem && rem.atMs ? rem.atMs : p0 && p0.source && c && p0.source.file === c.file ? p0.createdAtMs - S.skewMs : null;
  }
  function invalid(id, msg) {
    const i = $(id), e = $(id + '-err');
    if (i) i.classList.add('input--invalid');
    if (e) { e.textContent = msg; e.hidden = false; }
  }
  async function doPlan(btn) {
    const c = selectedBoard();
    let body;
    if (!c) { IL.toast('Choose a board first.', 'need'); return; }
    if (c.kind === 'job') body = { source: 'job', boardLocationId: c.id };
    else {
      const num = (k) => (String(R.form[k]).trim() === '' ? undefined : Number(String(R.form[k]).replace('−', '-')));
      body = { source: 'file', file: c.file, x: num('x'), y: num('y'), z: num('z'), rotation: num('rot') || 0 };
      let bad = false;
      for (const [k, id, msg] of [['x', 'board-x', 'Enter X of the board origin'], ['y', 'board-y', 'Enter Y of the board origin'], ['z', 'board-z', 'Enter the board top Z: the N1 nozzle Z touching the board top']]) {
        if (body[k] === undefined || !Number.isFinite(body[k])) { invalid(id, msg); bad = true; }
      }
      if (bad) return;
    }
    const p = await IL.act(btn, async () => {
      const q = await postPlan(body);
      if (c.kind === 'file') {
        const rem = remembered(c.file);
        const same = rem && Number(rem.x) === body.x && Number(rem.y) === body.y && Number(rem.z) === body.z && Number(rem.rotation || 0) === body.rotation;
        lsSet('il.boardloc.' + c.file, JSON.stringify({ x: body.x, y: body.y, z: body.z, rotation: body.rotation, atMs: same && rem.atMs ? rem.atMs : Date.now() }));
      }
      return q;
    }, { ok: (q) => 'Planned ' + q.summary.dots + ' dots on ' + q.summary.pads + ' pads' });
    if (!p) return;
    R.formDirty = false;
    setRegResult(null);
    goAuto();   // Register when this seat needs its surface (new seat, untied), else Checks
  }
  function renderBoardStep(s, parts) {
    const c = selectedBoard();
    syncSelect(false);
    prefillZ(s);
    fillForm();
    const b = R.boards;
    IL.setText($('board-count'), R.boardsLoading ? 'loading…' : b ? ((b.job || []).length ? (b.job || []).length + ' job · ' : '') + IL.plural((b.files || []).length, 'file') : '');
    IL.show($('board-file-row'), R.form.sel === 'other');
    const job = c && c.kind === 'job';
    for (const id of ['board-x', 'board-y', 'board-z', 'board-rot']) {
      const i = $(id);
      if (!i) continue;
      if (i.readOnly !== !!job) i.readOnly = !!job;
    }
    const hint = R.boardsErr ? 'Could not list the boards: ' + R.boardsErr
      : job ? 'Position from the OpenPnP job. Register board checks the fiducials again with the top camera.'
        : c ? 'Enter roughly where the board origin (its 0,0 corner) sits. After planning, Register board finds the fiducials with the top camera and probes this seat.' : 'Choose the board to dispense.';
    IL.setText($('board-hint'), hint);
    IL.setClass($('board-hint'), 'reason' + (R.boardsErr ? ' reason--block' : ''));
    // dirty fields: differ from the remembered (or planned) values
    const rem = c && c.kind === 'file' ? remembered(c.file) : null;
    const ref = rem || (plan() && plan().source && plan().source.file === (c && c.file) ? { x: plan().source.x, y: plan().source.y, z: plan().source.z, rotation: plan().source.rotation } : null);
    for (const [id, k, rk] of [['board-x', 'x', 'x'], ['board-y', 'y', 'y'], ['board-z', 'z', 'z'], ['board-rot', 'rot', 'rotation']]) {
      const i = $(id);
      if (!i) continue;
      const dirty = !job && ref && String(R.form[k]).trim() !== '' && Number(R.form[k]) !== Number(ref[rk] || 0);
      i.classList.toggle('input--dirty', !!dirty);
    }
    // the re-seat question (file boards entered before, or after "Next board")
    const q = $('board-seat-q');
    const p0 = plan();
    const since = rem && rem.atMs ? rem.atMs : p0 && p0.source && c && p0.source.file === c.file ? p0.createdAtMs - S.skewMs : S.newSeatAtMs ? S.newSeatAtMs : null;
    IL.show(q, !!since && !job);
    if (since) {
      IL.setText($('board-seat-t'), 'Was the board re-seated or changed since ' + IL.fmtTime(since) + '?');
      const newSeat = !!S.newSeatAtMs && S.newSeatAtMs >= since - 1000;
      const same = !newSeat && R.seatSame === since;
      IL.setAttr($('board-seat-new'), 'aria-pressed', String(newSeat));
      IL.setAttr($('board-seat-same'), 'aria-pressed', String(same));
      IL.setText($('board-seat-h'), newSeat ? 'New seat: after planning, Register board checks the fiducials and probes this seat.'
        : same ? 'Same seat: the board surface probed for it still holds.' : 'Position entered ' + IL.fmtTime(since) + '. A re-seated board must be registered again (one seating was tilted 0.9 mm).');
    }
    // capture buttons name the live value they take
    const pos = IL.positions(s);
    const capLock = job ? 'Job boards take their position from OpenPnP' : IL.lockReason(s, { connected: true, pressureOff: false });
    setBtnLabel($('btn-board-use-cam'), [IL.icon('i-capture'), el('span', {}, 'Use camera X/Y ', el('b', { text: pos.cam ? fmtMmV(pos.cam.x) + ', ' + fmtMmV(pos.cam.y) : '—' }))]);
    setBtnLabel($('btn-board-use-n1z'), [IL.icon('i-capture'), el('span', {}, 'Use live N1 Z ', el('b', { text: pos.n1 ? fmtMmV(pos.n1.z) : '—' }))]);
    IL.gate($('btn-board-use-cam'), capLock || (!pos.cam ? 'No top camera position' : null));
    IL.gate($('btn-board-use-n1z'), capLock || (!pos.n1 ? 'No N1 position' : null));
    IL.show($('board-captures'), !job);
    const why = $('board-capture-why');
    IL.setText(why, !job && capLock ? capLock : '');
    IL.show(why, !job && !!capLock);
    // Plan: locked while a task runs
    const lock = IL.lockReason(s, { pressureOff: false }) || (!c ? 'Choose a board first' : null);
    IL.gate($('btn-plan'), lock, $('plan-why'));
    IL.setText($('plan-next-hint'), S.newSeatAtMs && !job ? 'then: register the board' : '');
    // under the Board top Z: where a prefill came from, and the window and needle-frame check of the plan shown
    const zn = $('board-z-note');
    if (zn) {
      const p1 = plan();
      const fr = p1 && p1.registrationTargets && p1.registrationTargets.frame;
      const sameSrc = !!p1 && !!c && !job && p1.source && p1.source.file === c.file && Number(R.form.z) === Number(p1.source.z);
      const bad = sameSrc && fr && fr.ok === false && fr.message ? String(fr.message).replace(IL.CODE_RE, '$2') : null;
      const text = bad || (!job && R.form.zFrom ? IL.cap(R.form.zFrom) + ': within ±1 mm is enough, Register board measures it.' : '');
      IL.setText(zn, text);
      IL.setClass(zn, 'field__h' + (bad ? ' field__h--block' : ''));
      IL.show(zn, !!text);
    }
    // board select and position fields are locked while a task runs (the reason shows under Plan)
    const taskLock = IL.lockReason(s, { pressureOff: false });
    for (const id of ['board-select', 'board-x', 'board-y', 'board-z', 'board-rot', 'board-file']) {
      const i = $(id);
      if (i && i.disabled !== !!taskLock) i.disabled = !!taskLock;
    }
    IL.gate($('btn-board-refresh'), taskLock);
  }

  // ================================================================================== panel: Register step (key probe)
  // Register board (AUTO_REGISTER_DESIGN.md §8.1): home if needed → fiducials with the top camera → N1 vacuum probe (or
  // a same-seat re-touch) → plan again, with live progress and a result card. The by-hand path (jog N1 in OpenPnP's
  // window, add points) stays behind the "Do it by hand" disclosure; R.byHand chooses which foot shows.
  const REG = 'board registration';
  const regRun = (s) => (s && s.run && s.run.label === REG ? s.run : null);
  /** The Register board task of the plan shown while it runs, else null. */
  function regLive(s) {
    const r = regRun(s);
    return r && r.running && IL.planMatches(r.planId) ? r : null;
  }
  /** The registration view (§7.2) the result card shows: the plan's registrationRun of R.regResultRunId. */
  function regView() {
    const v = plan() && plan().registrationRun;
    return v && R.regResultRunId && v.runId === R.regResultRunId ? v : null;
  }
  /** The last live detail of a registration (status.run.detail): kept for the stopped texts after a reload of the plan. */
  function regDetailOf(runId) {
    const r = regRun(S.status);
    if (r && r.runId === runId && r.detail) return r.detail;
    return R.regDetail && R.regDetail.runId === runId ? R.regDetail.detail : null;
  }
  function setRegResult(runId) {
    R.regResultRunId = runId || null;
    ssSet('il.run.regResult', R.regResultRunId);
  }
  /** Every status: the live detail cache, the new plan of a finished registration (reload, a second browser). */
  function trackReg(s) {
    const r = regRun(s);
    if (r && r.detail) R.regDetail = { runId: r.runId, detail: r.detail };
    if (r && !r.running && r.nextPlanId && IL.planMatches(r.planId) && !R.regLoaded[r.runId]) {
      R.regLoaded[r.runId] = true;
      adoptRegPlan(r);
    }
    // another task clears the result card (it is about the board before that task)
    if (R.regResultRunId && s.run && s.run.running && s.run.label !== REG) setRegResult(null);
    const mk = machineKey(s) + '|' + (plan() ? plan().planId : '');
    if (mk !== R.machineKey) { const had = R.machineKey !== undefined; R.machineKey = mk; if (had && !IL.taskRunning(s)) refreshTargets(); }
  }
  /**
   * The plan's registration preview is made with the plan; its home plan and its checks change with the machine (a STOP,
   * a home, a reconnect). GET /api/plan/{id}/registration-targets computes it again (and keeps it with the plan).
   */
  function refreshTargets() {
    const p = plan();
    if (!p || R.targetsBusy === p.planId) return;
    R.targetsBusy = p.planId;
    IL.api('GET', '/api/plan/' + encodeURIComponent(p.planId) + '/registration-targets').then((t) => {
      if (plan() && plan().planId === p.planId && t) { plan().registrationTargets = t; R.targetsFor = null; IL.render(); }
    }).catch(() => { /* the plan's own preview stays */ }).finally(() => { R.targetsBusy = null; });
  }
  function machineKey(s) {
    const m = (s && s.machine) || {};
    return [m.enabled, m.homed, s && s.server && s.server.simulatorConfirmed, m.home && m.home.needed].join('|');
  }
  /** Shows the plan a registration made (the same seat: the server keeps its board number and paste). */
  function adoptRegPlan(r) {
    const p = plan();
    if (!r.nextPlanId || !p || r.nextPlanId === p.planId) return;
    IL.loadPlan(r.nextPlanId);
  }
  /** Register board: POST /api/plan/{id}/register (probe 'auto': the engine's new-seat flag forces the probe). */
  async function postRegister(probe) {
    const p = plan();
    if (!p) throw new Error('Plan a board first.');
    const r = await IL.api('POST', '/api/plan/' + encodeURIComponent(p.planId) + '/register', { probe: probe || 'auto' });
    setRegResult(null);
    R.byHand = false;
    if (IL.active() === 'run' && currentStep(S.status) !== 'probe') goStep('probe');
    return r;
  }
  const regOk = (r) => (r && r.probeOnly ? 'Probe only started' : 'Register board started') + (r && r.home && r.home.needed ? ': homes first (≈ ' + IL.fmtSecs(r.home.estimateS) + '), then ' : ': ')
    + (r && r.probeOnly ? '' : IL.plural(((r && r.fiducials) || []).length, 'fiducial') + ' and ') + IL.plural(((r && r.probeTargets) || []).length, 'N1 spot')
    + (r && r.probeOnly ? ' where the board is planned' : '') + (r && isNum(r.estimatedSeconds) ? ', ≈ ' + IL.fmtSecs(r.estimatedSeconds) : '') + '. STOP stops it at once.';
  /** Registration targets of the plan (null without them): {fids, probes, frame, window, est, error, count, probeOnly}. */
  function regTargets(p) {
    const t = p && p.registrationTargets;
    if (!t) return null;
    return { fids: t.fiducials || [], probes: t.probes || [], frame: t.frame || null, window: t.window || null,
      est: t.estimatedSeconds || {}, error: t.error || null, count: isNum(t.fiducialCount) ? Number(t.fiducialCount) : (t.fiducials || []).length,
      probeOnly: !!t.probeOnly };
  }
  /** A board without fiducials on a plan without a registration (F10): Register board probes it only (probe "always"). */
  const probeOnlyPlan = (p) => { const t = regTargets(p); return !!t && t.probeOnly; };
  /** What probe only does, in #register-note before the press. */
  const PROBE_ONLY_NOTE = 'This board has no fiducials: N1 probes it where it is planned; its X/Y are not checked.';
  /** The home plan now: status.machine.home (the live one), else the plan's preview. */
  function homePlan(s, p) {
    const h = s && s.machine && s.machine.home;
    if (h && s.machine.enabled) return h;
    const t = p && p.registrationTargets;
    return (t && t.home) || h || null;
  }
  /** Would the next registration be a same seat (the stored registration of this board applies, nobody said new seat)? */
  function regSameSeatLikely(s, p) {
    const reg = p && p.registration;
    if (!reg || reg.beforeNextBoard) return null;
    if (S.newSeatAtMs && S.newSeatAtMs + S.skewMs > reg.atMs) return null;
    // the surface must be tied to this plan (and so to this registration): else the engine probes every spot
    const it = IL.ready().items.find((x) => x.key === 'surface');
    return it && it.state === 'ok' ? reg : null;
  }
  const boardSurface = (s) => (s && s.dispense && s.dispense.surfaces && s.dispense.surfaces[s.dispense.boardSurfaceName || 'board']) || null;
  const anchorOf = (s) => (s && s.dispense && s.dispense.needle && s.dispense.needle.anchor) || null;
  const hasCamera = (s) => (((s && s.machine && s.machine.positions) || []).some((t) => t.kind === 'camera'));
  /**
   * Why Register board cannot start now (first of AUTO_REGISTER_DESIGN.md §8.1 `#register-why`), or null. only: probe
   * only (F10), where the board's missing fiducials are no reason.
   */
  function registerWhy(s, only) {
    const p = plan();
    // it homes by itself when needed: not while a STOP left N1 low (Run waits too), the operator looks and homes first
    const lock = IL.lockReason(s, { connected: true, engine: true, homes: true });
    if (lock) return IL.n1Low(s) ? { text: lock, fix: 'home', link: 'Home now' } : { text: lock };
    if (!p) return { text: 'Plan a board first' };
    const t = regTargets(p);
    // the plan's own registration no longer applies (registered again elsewhere, a simulator record): plan again first
    if (t && t.error && /^REG: registration /.test(t.error)) {
      return { text: String(t.error).replace(IL.CODE_RE, '$2').replace(/: (plan|register) again\.?$/, '') + ': plan again first.', fix: 'plan-again' };
    }
    if (t && t.count < 2 && !t.error) return { text: 'This board has ' + IL.plural(t.count, 'fiducial') + ' (2 needed): do it by hand' };
    if (!hasCamera(s)) return { text: 'No top camera in OpenPnP: do it by hand' };
    if (t && t.error && !(only && t.probeOnly)) return { text: String(t.error).replace(IL.CODE_RE, '$2') };
    if (!anchorOf(s)) return { text: 'Record the needle Z frame first: the probe checks its heights against it.', href: '#setup/needle', link: 'Open Needle Z frame' };
    if (t && t.frame && t.frame.ok === false && t.frame.message) {
      const text = String(t.frame.message).replace(IL.CODE_RE, '$2');
      // a board top so far under the needle frame that the probe's floor cannot reach it: the Z, the frame, or by hand
      if (/cannot reach that height/i.test(text)) {
        return { text, links: [{ href: '#run/board', link: 'Board step' }, { href: '#setup/needle', link: 'Needle Z frame' }, { fix: 'probe-by-hand', link: 'Do it by hand' }] };
      }
      return { text, href: '#run/board', link: 'Board step' };
    }
    return null;
  }
  /** Seconds Register board takes now: the plan's estimate with the live home plan (never hard-coded). */
  function regSeconds(s, p) {
    const t = regTargets(p);
    if (!t || (t.error && !t.probeOnly)) return null;
    const h = homePlan(s, p);
    const home = h && h.needed ? Number(h.estimateS) || 0 : 0;
    if (t.probeOnly) return home + (Number(t.est.probe) || 0);
    const same = regSameSeatLikely(s, p);
    const body = (Number(t.est.fiducials) || 0) + (same ? Number(t.est.sameSeat) || 0 : Number(t.est.probe) || 0);
    return home + body;
  }
  function names(list) {
    const n = list.map((f) => f.name);
    return n.length > 1 ? n.slice(0, -1).join(', ') + ' and ' + n[n.length - 1] : n.join('');
  }
  /** The pre-press line of the foot (#register-note, §8.1). */
  function registerNote(s, p) {
    const t = regTargets(p);
    if (!t || ((t.error || !t.fids.length) && !t.probeOnly)) return '';
    const h = homePlan(s, p);
    const parts = [];
    if (h && h.needed) {
      const why = IL.homeWhy(h);
      parts.push('Homes first' + (why ? ' (' + why + ')' : '') + ', ≈ ' + IL.fmtSecs(h.estimateS) + '.');
    }
    if (t.probeOnly) {
      parts.push(PROBE_ONLY_NOTE, (h && h.needed ? 'Then N1' : 'N1') + ' touches it at ' + IL.plural(t.probes.length, 'bare spot')
        + ' with its vacuum, after a camera look at each (≈ ' + IL.fmtSecs(t.est.probe) + ').',
      'The paste pressure stays off. Before you press: N1 has its tip, no part on it; hands clear of the head.');
      return parts.join(' ');
    }
    const same = regSameSeatLikely(s, p);
    const sf = boardSurface(s);
    const probe = same ? 'N1 re-touches 3 points of the ' + IL.fmtTime((sf && sf.setAtMs ? sf.setAtMs : same.atMs) - S.skewMs)
      + ' surface if the board has not moved (≈ ' + IL.fmtSecs(t.est.sameSeat) + ')'
      : 'N1 touches the board at ' + IL.plural(t.probes.length, 'bare spot') + ' with its vacuum, after a camera look at each (≈ ' + IL.fmtSecs(t.est.probe) + ')';
    parts.push((parts.length ? 'Then the' : 'The') + ' top camera finds ' + names(t.fids) + ' (≈ ' + IL.fmtSecs(t.est.fiducials) + ') and ' + probe + '.');
    parts.push('The paste pressure stays off. Before you press: N1 has its tip, no part on it; hands clear of the head.');
    return parts.join(' ');
  }
  /** What the plan's dots use (#reg-correction): the fiducial check, OpenPnP's (job board), or none. */
  function correctionText(p) {
    const reg = p && p.registration;
    if (reg) {
      return 'fiducial check ' + IL.fmtTime(reg.atMs - S.skewMs) + (reg.beforeNextBoard ? ' (before the last Next board)'
        : reg.lastCheckedAtMs && reg.lastCheckedAtMs > reg.atMs ? ', same seat at ' + IL.fmtTime(reg.lastCheckedAtMs - S.skewMs) : '');
    }
    const seat = seatOf(p);
    if (seat && seat.job && seat.fid) return "OpenPnP's fiducial check of the job board";
    return 'no fiducial correction';
  }

  // ---------------------------------------------------------------------------------- texts of the result card
  const strip = (m) => String(m || '').replace(IL.CODE_RE, '$2');
  const mmText = (v, d) => IL.fmt(v, d === undefined ? 2 : d) + ' mm';
  /** "Stopped 13:06 during …: what was kept" (§8.1 state 4), from the stage, the seat and the last live detail. */
  function stoppedText(v, at) {
    const st = v.stoppedStage || '';
    const det = regDetailOf(v.runId) || {};
    const now = (det.probes || []).find((q) => q.state === 'now' || q.state === 'look');
    const nPts = (det.probes || []).length;
    const k = now ? now.n : Math.min(nPts, (det.probes || []).filter((q) => q.state === 'contact').length + 1);
    const head = 'Stopped ' + at + ' during ';
    if (st === 'home' || !st) return head + 'the home: nothing was saved.';
    if (st === 'fiducials') {
      const f = (det.fiducials || v.fiducials || []).find((x) => x.state !== 'found');
      return head + 'the fiducial check' + (f ? ' (' + f.name + ')' : '') + ': nothing was saved.';
    }
    const where = st === 'same-seat' ? 'the same-seat check (point ' + Math.max(1, k) + ' of 3)' : st === 'probe' ? 'probe point ' + Math.max(1, k) + ' of ' + Math.max(1, nPts)
      : st === 'save' ? 'the save' : 'planning again';
    // probe only (F10) stores no registration: the surface is all it saves, and it plans again after the save
    if (v.probeOnly) return head + where + (st === 'plan' ? ': the board surface was saved; plan the board again.' : ': nothing was saved.');
    if (v.seat === 'same') {
      const mine = regOf(v);
      const held = mine ? (mine.lastCheckedAtMs > mine.atMs ? mine.lastCheckedAtMs : mine.atMs) : v.previousAtMs || v.atMs;
      return head + where + ': the board surface was not changed; the seat checked at ' + IL.fmtTime(held - S.skewMs) + ' still holds.';
    }
    return head + where + ': the fiducials' + (isNum(v.movedMm) ? ' (board moved ' + mmText(v.movedMm) + ')' : '') + ' were saved and the board planned again; '
      + 'the surface was not: Register again to probe.';
  }
  /** The registration a result stored or checked again, from the plan shown (the page head and "Dots use" show it). */
  function regOf(v) {
    const reg = plan() && plan().registration;
    return reg && v.registrationId && reg.id === v.registrationId ? reg : null;
  }
  /** The machine-readable first sentence of #reg-result (§8.1 state 3). */
  function regSentence(v, s) {
    const ended = IL.fmtTime((v.atMs || Date.now() + S.skewMs) - S.skewMs);
    if (v.status === 'ABORTED') return stoppedText(v, ended);
    // the registration's own time, as the page head and "Dots use" show it (the task ends a little later)
    const mine = regOf(v);
    const at = mine ? IL.fmtTime((v.seat === 'same' && mine.lastCheckedAtMs ? mine.lastCheckedAtMs : mine.atMs) - S.skewMs) : ended;
    const fids = v.fiducials || [];
    const pr = v.probe || {};
    const pts = pr.points || [];
    const worst = pr.plane && isNum(pr.plane.maxResidualMm) ? ', worst ' + mmText(pr.plane.maxResidualMm, 3) : '';
    const from = regRun(s) && regRun(s).runId === v.runId ? regRun(s).planId : null;
    const again = v.newPlanId && v.newPlanId !== from ? '; planned again.' : v.newPlanId ? '; plan unchanged.' : '.';
    if (!v.ok) {
      const why = strip(v.message).replace(/([^.!?])$/, '$1.');
      if (v.probeOnly) return 'Not probed: ' + why;
      if (!v.registrationStored) return 'Not registered: ' + why;
      return 'Registered ' + at + ', but the probe failed: ' + why;
    }
    // a board without fiducials, probed where it is planned (F10): no fit, no seat, no registration
    if (v.probeOnly) return 'Probed ' + at + ': ' + (pr.why || 'no fiducials: probed only, X/Y as planned') + '; '
      + (pr.saved ? IL.plural(pts.length, 'point') + worst : 'the surface was not saved') + again;
    const lim = S.config && S.config.fiducialCheck && isNum(S.config.fiducialCheck.seatToleranceMm) ? S.config.fiducialCheck.seatToleranceMm : 0.1;
    const since = v.previousAtMs ? ' since ' + IL.fmtTime(v.previousAtMs - S.skewMs) : '';
    const probed = (n) => (pr.saved ? 'probed ' + IL.plural(n, 'point') + (n ? ' again' : '') + worst : pr.skipped ? 'not probed' : 'the surface was not saved');
    if (v.seat === 'same') {
      const ss = v.sameSeat;
      const moved = 'the board moved ' + mmText(v.movedMm || 0) + since + ' (limit ' + mmText(lim) + ')';
      if (ss && ss.pass) {
        const agree = (ss.points || []).reduce((a, q) => Math.max(a, Math.abs(q.deltaMm || 0)), 0);
        return 'Same seat ' + at + ': ' + moved + ' and 3 re-touched points agree within ' + mmText(Math.max(0.01, agree)) + '; the surface probed then still applies' + again;
      }
      if (ss && !ss.pass) {
        const bad = (ss.points || []).filter((q) => !q.pass).sort((a, b) => Math.abs(b.deltaMm || 9) - Math.abs(a.deltaMm || 9))[0];
        const off = bad ? 'point ' + bad.n + (isNum(bad.deltaMm) ? ' is ' + mmText(Math.abs(bad.deltaMm)) + ' off' : ' gave no contact') + ' (limit 0.03 mm)' : 'a point is off';
        return 'Same seat ' + at + ', but the surface changed: ' + off + '; ' + probed(pts.length) + again;
      }
      return 'Same seat ' + at + ': ' + moved + '; ' + (pr.why === 'probe: always' ? '' : 'no surface was tied to it: ') + probed(pts.length) + again;
    }
    if (v.seat === 'reseated') {
      const most = fids.filter((f) => isNum(f.movedMm)).sort((a, b) => b.movedMm - a.movedMm)[0];
      const how = pr.why === 'a new seat was announced' ? ' (you said new seat)' : most ? ' (' + most.name + ' most)' : '';
      return 'Re-seated ' + at + ': the board moved ' + (isNum(v.movedMm) ? mmText(v.movedMm) : 'more than ' + mmText(lim)) + since + how + '; ' + probed(pts.length) + again;
    }
    const off = fids.reduce((a, f) => Math.max(a, Number(f.offsetFromExpectedMm) || 0), 0);
    const found = fids.filter((f) => f.found).length;
    const tail = pr.saved ? IL.plural(pts.length, 'point') + worst : pr.skipped ? 'not probed' : 'not probed';
    return 'Registered ' + at + ': ' + found + ' of ' + fids.length + ' fiducials, board ' + mmText(off) + ' from where it was planned; ' + tail + again;
  }
  /** The fit in plain numbers: "rotation +0.20° · scale X +0.20 %, Y −0.30 % · shear +0.10°". */
  function fitText(fit) {
    if (!fit) return '';
    const pct = (v) => IL.fmtSigned((v - 1) * 100, 2) + ' %';
    return 'Rotation ' + IL.fmtSigned(fit.rotationDeg, 2) + '° · scale X ' + pct(fit.scaleX) + ', Y ' + pct(fit.scaleY) + ' · shear ' + IL.fmtSigned(fit.shearDeg, 2) + '°';
  }
  /** A refused probe point in plain words: what the camera or N1 found there, and the fix. */
  function pointWhy(q) {
    if (!q) return null;
    if (q.state === 'not-bare') {
      const share = q.look && isNum(q.look.foreignShare) ? ' (' + IL.fmt(q.look.foreignShare * 100, 0) + ' % of the spot)' : '';
      return { what: 'The camera saw paste, a part or print at every spot tried' + share + '.', fix: 'Wipe it or take the part off, then Register again; or do it by hand.' };
    }
    if (q.state === 'no-contact') {
      return { what: 'N1 went down to the floor without a seal: probably a hole or a via there, or the board top Z is wrong.', fix: 'Check the board top Z in the Board step, then Register again; or do it by hand.' };
    }
    return null;
  }

  // ---------------------------------------------------------------------------------- build
  function buildProbe() {
    const byHand = el('details', { class: 'disclose reg-byhand', id: 'probe-by-hand' },
      el('summary', { text: 'Do it by hand: jog N1 in OpenPnP and add points' }),
      el('div', { class: 'stack reg-byhand__body' },
        el('div', { class: 'probe-guide', id: 'probe-guide' }),
        el('table', { class: 'ptargets', id: 'probe-targets' }),
        el('div', { id: 'probe-untied' })));
    byHand.open = !!R.byHand;
    byHand.addEventListener('toggle', () => { if (R.byHand !== byHand.open) { R.byHand = byHand.open; IL.render(); } });
    return el('div', { class: 'stack reg-body' },
      el('div', { id: 'probe-banner' }),
      el('div', { class: 'reg-corr', id: 'reg-corr' }, el('span', { class: 'reg-corr__k', text: 'Dots use' }), el('span', { id: 'reg-correction' })),
      el('div', { class: 'ri reg-result', id: 'reg-result', role: 'status', 'data-refusals': '', hidden: true }),
      el('div', { id: 'reg-preview', class: 'reg-preview' }),
      el('div', { id: 'reg-live', class: 'stack reg-live', hidden: true },
        el('ol', { class: 'phases', id: 'reg-phases' }),
        el('p', { class: 'stop-line', id: 'reg-stop-line', text: 'STOP (F9) stops it at once: N1 stays where it stopped, its vacuum off; Home raises it first.' })),
      el('div', { id: 'reg-tables', class: 'stack reg-tables' },
        el('table', { class: 'ptargets reg-tbl', id: 'reg-fids' }),
        el('table', { class: 'ptargets reg-tbl', id: 'reg-probes' })),
      el('div', { class: 'minis', id: 'probe-tiles' }),
      byHand);
  }
  function buildProbeFoot() {
    // the register set (R.byHand false)
    const reg = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-register',
      title: 'Home if needed, find the fiducials with the top camera, touch the board with N1 at bare spots, plan again. Press twice.' });
    // on a board without fiducials it is "Probe only (X/Y as planned)": probe "always" (F10, renderProbeFoot)
    const only = () => reg.dataset.mode === 'probe-only';
    IL.press2(reg, () => (only() ? 'probe only' : 'register board'), () => postRegister(only() ? 'always' : 'auto'), { ok: regOk, what: 'Register board' });
    const cont = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-reg-continue', hidden: true }, 'Checks ›');
    cont.addEventListener('click', () => { setRegResult(null); goStep('checks'); });
    const regSet = el('div', { class: 'stack reg-foot', id: 'reg-foot', style: 'gap:8px', 'data-refusals': '' },
      el('div', { class: 'reg-note', id: 'register-note' }), reg, cont, el('div', { class: 'reason', id: 'register-why', hidden: true }));
    // the by-hand set (R.byHand true): ids and behaviour unchanged
    const add = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-probe-add', title: 'Record the live N1 X/Y/Z as a surface point (lower N1 until its tip touches the board first)' });
    const save = el('button', { type: 'button', class: 'btn btn--lg btn--block', id: 'btn-probe-save', title: "Save the points as this seat's board surface, tied to the plan, and plan the board again (nothing moves)" });
    // a point far from its target or off the board top asks for a second press (the warning above says why)
    IL.press2(add, (s) => (probeWarn(s) ? 'add this point anyway' : null), async () => {
      const s = S.status;
      const n1 = IL.positions(s).n1;
      const st = probeState(s);
      if (!n1 || st.nextT < 0) return null;
      st.points.push({ target: st.nextT, x: Math.round(n1.x * 1000) / 1000, y: Math.round(n1.y * 1000) / 1000, z: Math.round(n1.z * 1000) / 1000 });
      saveProbe();
      IL.render();
      return { n: st.nextT + 1, n1 };
    }, { ok: (r) => (r ? 'Point ' + r.n + ': X ' + fmtMmV(r.n1.x) + ' · Y ' + fmtMmV(r.n1.y) + ' · Z ' + IL.fmt(r.n1.z, 3) + ' mm' : 'No point taken') });
    save.addEventListener('click', () => saveProbeAndPlan(save));
    const handSet = el('div', { class: 'stack', id: 'probe-foot-hand', style: 'gap:8px', 'data-refusals': '', hidden: true },
      el('div', { class: 'probe-live', id: 'probe-live' }), el('div', { class: 'notebox notebox--need probe-warn', id: 'probe-warn', role: 'status', hidden: true }),
      add, save, el('div', { class: 'reason', id: 'probe-why', hidden: true }));
    return el('div', { class: 'stack', style: 'gap:8px' }, regSet, handSet);
  }

  // ---------------------------------------------------------------------------------- by hand (targets, points)
  function probeTargets(p, margin) {
    if (!p || !p.dots || !p.dots.length) return [];
    // the engine's targets (bare spots away from pads, fiducials and holes, through the plan's transform): one set on the
    // board for both paths; the client's bounding box stays only as the fallback
    const rt = regTargets(p);
    if (rt && (!rt.error || rt.probeOnly) && rt.probes.length) {
      const key0 = p.planId + '|engine';
      if (R.targetsFor === key0) return R.targets;
      R.targetsFor = key0;
      R.targets = rt.probes.map((q) => ({ x: Math.round(q.x * 100) / 100, y: Math.round(q.y * 100) / 100,
        alts: (q.alternates || []).map((a) => ({ x: a.x, y: a.y })) }));
      return R.targets;
    }
    const seat = seatOf(p) || {};
    const rot = isNum(seat.rot) ? Number(seat.rot) : 0;
    const key = p.planId + '|' + margin + '|' + rot;
    if (R.targetsFor === key) return R.targets;
    // the dots' bounding box in the board's own frame (so a rotated board gets a rotated rectangle), corners pulled in
    // 1.5 mm (the margin-inflated area still covers every dot), then the centre
    const a = rot * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
    const toB = (q) => ({ x: q.x * ca + q.y * sa, y: -q.x * sa + q.y * ca });
    const toM = (q) => ({ x: q.x * ca - q.y * sa, y: q.x * sa + q.y * ca });
    const pts = p.dots.map((d) => ({ x: d.x, y: d.y }));
    const b = IL.boardview.bbox(pts.map(toB));
    const w = b.x1 - b.x0, h = b.y1 - b.y0;
    const ix = Math.min(1.5, w / 4), iy = Math.min(1.5, h / 4);
    const out = [[b.x0 + ix, b.y1 - iy], [b.x1 - ix, b.y1 - iy], [b.x1 - ix, b.y0 + iy], [b.x0 + ix, b.y0 + iy]]
      .map(([x, y]) => toM({ x, y }))
      .filter((q, i, all) => all.findIndex((o) => Math.hypot(o.x - q.x, o.y - q.y) < 3) === i);
    out.push(toM({ x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 }));
    // a dot still outside the margin (odd shapes, tiny boards): add it as a target
    for (let guard = 0; guard < 6; guard++) {
      const hh = IL.boardview.hull(out);
      let worst = null, wd = margin - 0.5;
      for (const q of pts) { const d = IL.boardview.outside(hh, q.x, q.y); if (d > wd) { wd = d; worst = q; } }
      if (!worst) break;
      out.splice(out.length - 1, 0, worst);
    }
    R.targetsFor = key;
    R.targets = out.map((q) => ({ x: Math.round(q.x * 100) / 100, y: Math.round(q.y * 100) / 100 }));
    return R.targets;
  }
  function probeState(s) {
    const p = plan();
    const cfg = S.config || {};
    const margin = isNum(cfg.surfaceMarginMm) ? Number(cfg.surfaceMarginMm) : 3.5;
    const seat = seatKey(p && p.source);
    if (R.probe.seat !== seat) { R.probe = { seat, points: [] }; ssSet('il.run.probe', R.probe); R.probeUntied = false; }
    const targets = probeTargets(p, margin);
    const points = R.probe.points;
    const doneT = new Set(points.map((q) => q.target));
    const nextT = targets.findIndex((t, i) => !doneT.has(i));
    return { p, margin, targets, points, doneT, nextT, seat };
  }
  /**
   * The hero overlay on the Register step (§8.1 "Hero overlay"): by hand, today's targets and the points taken; else the
   * engine's targets (their alternates as small rings), the live or found points, and the tied surface's area.
   */
  function probeOverlay(s) {
    const st = probeState(s);
    if (!st.p) return null;
    if (R.byHand) {
      return { margin: st.margin, points: st.points.map((q) => ({ x: q.x, y: q.y })),
        targets: st.targets.map((t, i) => ({ x: t.x, y: t.y, done: st.doneT.has(i), next: i === st.nextT })) };
    }
    const live = regLive(s);
    const v = regView();
    const det = live ? live.detail || {} : null;
    const pts = det && det.probes && det.probes.length ? det.probes : v && v.probe && v.probe.points && v.probe.points.length ? v.probe.points : null;
    if (pts) {
      return { margin: st.margin, points: pts.filter((q) => q.state === 'contact').map((q) => ({ x: q.x, y: q.y })),
        targets: pts.map((q) => ({ x: q.x, y: q.y, done: q.state === 'contact', next: q.state === 'now' || q.state === 'look',
          bad: q.state === 'not-bare' || q.state === 'no-contact' })) };
    }
    const sf = boardSurface(s);
    const tied = !!sf && sf.boundToBoard && IL.ready().items.find((x) => x.key === 'surface').state === 'ok';
    const surfPts = tied ? (sf.points || []).map((q) => (Array.isArray(q) ? { x: q[0], y: q[1] } : { x: q.x, y: q.y })) : [];
    // the engine found no bare spots (Register board is locked and says where): no numbered spots on the board, the
    // client's fallback rectangle would draw them on pads or off the outline (by hand still lists them, for a person)
    const rt = regTargets(st.p);
    const targets = rt && rt.error && !rt.probeOnly ? [] : st.targets;
    return { margin: st.margin, points: surfPts, targets: targets.map((t) => ({ x: t.x, y: t.y, alts: t.alts || [] })) };
  }
  /** The fiducial layer of the hero (g.pv-l-fids): expected search circles and the fiducials found. */
  function fidOverlay(s) {
    const p = plan();
    if (!p) return null;
    const t = regTargets(p);
    const r0 = t && t.fids.length && isNum(t.fids[0].searchRadiusMm) ? Number(t.fids[0].searchRadiusMm) : 8;
    const live = regLive(s);
    const v = regView();
    const src = live && live.detail && live.detail.fiducials ? live.detail.fiducials : v && v.fiducials && v.fiducials.length ? v.fiducials : null;
    if (src) {
      return src.map((f) => ({ name: f.name, x: f.expected.x, y: f.expected.y, r: isNum(f.searchRadiusMm) ? f.searchRadiusMm : r0,
        found: f.found ? { x: f.found.x, y: f.found.y } : null, state: f.state }));
    }
    const reg = p.registration;
    const at = new Map(((reg && reg.fiducials) || []).map((f) => [f.name, f]));
    if (t && t.fids.length) {
      return t.fids.map((f) => ({ name: f.name, x: f.x, y: f.y, r: isNum(f.searchRadiusMm) ? f.searchRadiusMm : r0,
        found: at.has(f.name) ? { x: at.get(f.name).x, y: at.get(f.name).y } : null, state: at.has(f.name) ? 'found' : 'todo' }));
    }
    // no AR data: the board's fiducials from the plan (board import), expected only
    return ((p.board && p.board.fiducials) || []).filter((f) => isNum(f.x) && isNum(f.y))
      .map((f) => ({ name: f.id || f.name, x: f.x, y: f.y, r: r0, found: null, state: 'todo' }));
  }
  function saveProbe() { ssSet('il.run.probe', R.probe); }
  const PROBE_NEAR_MM = 2.0;   // N1 farther than this from the target: ask before taking the point
  /** Limits for a probe point: {tol (Z vs the board top / frame, mm), top (board top Z or the frame surface), topName}. */
  function probeLimits(s, p) {
    const cfg = S.config || {};
    const tol = isNum(cfg.zFrameToleranceMm) ? Number(cfg.zFrameToleranceMm) : 1.0;
    const seat = seatOf(p) || {};
    const anchor = anchorOf(s);
    if (isNum(seat.z)) return { tol, top: Number(seat.z), topName: 'the board top' };
    if (anchor && isNum(anchor.surfaceZMm)) return { tol, top: Number(anchor.surfaceZMm), topName: 'the needle frame surface' };
    return { tol, top: null, topName: '' };
  }
  /** Why the live N1 position does not look like a touched target now (one sentence), or null. */
  function probeWarn(s) {
    const st = probeState(s);
    const n1 = IL.positions(s).n1;
    if (!st.p || !n1 || st.nextT < 0) return null;
    const t = st.targets[st.nextT];
    const dist = Math.hypot(n1.x - t.x, n1.y - t.y);
    if (dist > PROBE_NEAR_MM) return 'N1 is ' + IL.fmt(dist, 1) + ' mm from target ' + (st.nextT + 1) + ': jog there first.';
    const lim = probeLimits(s, st.p);
    if (lim.top !== null && Math.abs(n1.z - lim.top) > lim.tol) {
      const dz = n1.z - lim.top;
      return 'N1 is ' + IL.fmt(Math.abs(dz), 2) + ' mm ' + (dz > 0 ? 'above' : 'below') + ' ' + lim.topName + ' (Z ' + IL.fmt(lim.top, 2) + '): is the tip touching?';
    }
    return null;
  }
  async function saveProbeAndPlan(btn) {
    const s = S.status;
    const st = probeState(s);
    const p = st.p;
    if (!p || st.points.length < 3) return;
    const name = (s.dispense && s.dispense.boardSurfaceName) || 'board';
    const res = await IL.act(btn, async () => {
      const sf = await IL.api('PUT', '/api/surfaces/' + encodeURIComponent(name), {
        points: st.points.map((q) => [q.x, q.y, q.z]), planId: p.planId, note: 'web UI: probed seat of plan ' + shortId(p) + ', ' + st.points.length + ' points' });
      if (sf && sf.boundToBoard === false) { R.probeUntied = true; IL.render(); return { untied: true }; }
      R.probeUntied = false;
      const q = await postPlan(planBodyFrom(p.source));
      IL.setNewSeat(null);
      R.probe = { seat: seatKey(q.source), points: [] };
      saveProbe();
      return { sf, plan: q };
    }, { ok: (r) => (r && r.untied ? 'Saved, but not tied to the plan: plan again, then save.' : 'Board surface saved (' + IL.plural(st.points.length, 'point') + ') and the board planned again.') });
    if (res && !res.untied) { R.byHand = false; goAuto(); }
  }

  // ---------------------------------------------------------------------------------- render
  function renderProbe(s) {
    const st = probeState(s);
    const p = st.p;
    const live = regLive(s);
    const v = live ? null : regView();
    const fin = !live && !!R.regResultRunId && regRun(s) && regRun(s).runId === R.regResultRunId && !regRun(s).running ? regRun(s) : null;
    const mode = live ? 'live' : v || fin ? 'result' : 'idle';
    const r = IL.ready();
    const sfItem = r.items.find((x) => x.key === 'surface');
    const tied = !!sfItem && sfItem.state === 'ok';
    const byHand = !!R.byHand && mode !== 'live';
    const det = $('probe-by-hand');
    if (det && det.open !== byHand) det.open = byHand;
    IL.show(det, !!p && mode !== 'live');
    // banner: the seat state (idle only; the result card and the live phases say the rest)
    IL.show($('probe-banner'), mode === 'idle');
    const only = probeOnlyPlan(p);   // no fiducials (F10): N1 probes the board where it is planned
    IL.keyed($('probe-banner'), [mode, !!p, tied, !!S.newSeatAtMs, sfItem && sfItem.detail, only].join('|'), () => {
      if (mode !== 'idle') return null;
      if (!p) return el('div', { class: 'notebox', text: 'Plan the board first (Board step): Register board takes its fiducials and probe spots from the plan.' });
      if (tied && !S.newSeatAtMs) {
        return el('div', { class: 'notebox notebox--info' }, el('b', { text: p.registration ? 'This seat is registered and tied' : 'This seat is probed and tied' }),
          el('div', { class: 'muted', text: (sfItem.detail || '') + (only ? ' Probe again only if the board moved.' : ' Register again only if the board moved.') }));
      }
      const fixes = (sfItem && sfItem.fixes || []).filter((f) => f.fix === 'register-check' || f.fix === 'same-seat');
      return el('div', { class: 'notebox notebox--need probe-banner' },
        el('div', {}, el('b', { text: S.newSeatAtMs ? (only ? 'New seat: probe it' : 'New seat: register it') : 'This seat needs its surface' }),
          el('div', { class: 'muted', text: sfItem && sfItem.state === 'block' ? sfItem.detail : only ? 'No fiducials: N1 touches the board at bare spots where it is planned. A re-seated board can sit tilted.'
            : 'The fiducials say where the board sits; then N1 touches it at bare spots. A re-seated board can sit tilted.' })),
        fixes.map((f) => fixBtn(Object.assign({}, f, { label: f.fix === 'same-seat' ? 'Same seat? Keep them' : f.label }), 'btn--sm')));
    });
    // what the plan's dots use now
    IL.show($('reg-corr'), !!p);
    IL.setText($('reg-correction'), p ? correctionText(p) : '');
    IL.setAttr($('reg-corr'), 'title', p && p.registration ? 'Registration ' + String(p.registration.id || '').slice(0, 4) + ' · ' + fitText(p.registration) : null);
    renderRegPreview(s, p, mode);
    renderRegLive(s, p, live);
    renderRegResult(s, p, mode, v, fin);
    renderRegTables(s, p, mode, live, v);
    renderProbeTiles(s, st, tied, mode);
    renderByHand(s, st);
    renderProbeFoot(s, st, mode, v, fin, byHand);
  }
  /** Idle (§8.1 state 1): the seat it expects and what N1 touches (the fiducials are in #reg-fids, the times in the note). */
  function renderRegPreview(s, p, mode) {
    const box = $('reg-preview');
    const t = regTargets(p);
    IL.show(box, mode === 'idle' && !!p && !R.byHand);
    if (mode !== 'idle' || !p || R.byHand) return;
    const same = regSameSeatLikely(s, p);
    const sf = boardSurface(s);
    const alts = t ? Math.max(0, ...t.probes.map((q) => (q.alternates || []).length)) : 0;
    const key = JSON.stringify([p.planId, !!t, t && t.probes.length, alts, !!same, sf && sf.setAtMs]);
    IL.keyed(box, key + (t && t.error ? 'e' : '') + (t && t.probeOnly ? 'o' : ''), () => {
      if (!t) return el('div', { class: 'notebox', text: 'This plan has no registration preview (an older plan): plan again.' });
      if ((t.error && !t.probeOnly) || !t.probes.length) return null;   // the reason is under the button
      return el('div', { class: 'reg-expect' }, el('span', {}, same
        ? ['Same seat as ', el('b', { text: IL.fmtTime(same.atMs - S.skewMs) }), ' if every fiducial moved at most 0.10 mm: N1 re-touches 3 points of that surface. Else it probes ',
          el('b', { text: IL.plural(t.probes.length, 'spot') }), '.']
        : ['N1 probes ', el('b', { text: IL.plural(t.probes.length, 'bare spot') }), ' (numbered on the board)', alts
          ? ', with up to ' + IL.plural(alts, 'nearby spot') + ' each when one is not bare.' : '.']));
    });
  }
  /** Running: the phases (§8.1 state 2) from status.run.detail. */
  function renderRegLive(s, p, live) {
    IL.show($('reg-live'), !!live);
    if (!live) return;
    const d = live.detail || {};
    const stage = d.stage || 'home';
    const order = ['home', 'fiducials', 'fit', 'probe', 'plan'];
    const at = { home: 0, fiducials: 1, 'same-seat': 3, probe: 3, save: 3, plan: 4 }[stage];
    const rows = [];
    const stOf = (i) => (i < at ? 'done' : i === at ? 'now' : 'todo');
    const home = d.home || {};
    const est = (p && p.registrationTargets && p.registrationTargets.estimatedSeconds) || {};
    rows.push({ k: 'home', st: home.needed ? stOf(0) : 'done', t: 'Home', d: home.needed ? (stage === 'home' ? 'the head goes to its home corner · pressure off' : 'done') : 'not needed',
      v: home.needed && stage === 'home' ? '≈ ' + IL.fmtSecs(home.estimateS) : '' });
    // probe only (F10): no fiducials to find or fit, N1 probes the board where it is planned
    const only = probeOnlyPlan(p);
    const fids = d.fiducials || [];
    const nf = fids.filter((f) => f.state === 'found').length;
    // before the run reports its fiducials (while it homes) the plan's own count them: the same top-side fiducials
    // Register board looks for (its preview lists none when it could not be made, e.g. while the simulator was off)
    const nFid = fids.length || ((p && p.board && p.board.fiducials) || []).length;
    const ph = String(live.phase || '');
    const fidNow = /^fiducials: (.+)$/.exec(ph);
    if (!only) {
      rows.push({ k: 'fiducials', st: stOf(1), t: 'Fiducials · ' + nf + ' of ' + (nFid || '…') + ' found',
        d: stage === 'fiducials' && fidNow ? 'looking at ' + fidNow[1].replace(/ \((\d+) of (\d+)\)/, '').replace(/ · /g, ', ') : at > 1 ? names(fids) + ' found' : 'top camera, light on',
        v: stage === 'fiducials' ? '≈ ' + IL.fmtSecs(Math.max(0, nFid - nf) * 5) : '' });
      const seatTxt = d.seat === 'same' ? 'same seat' + (isNum(d.movedMm) ? ' · moved ' + mmText(d.movedMm) : '') : d.seat === 'reseated' ? 're-seated' + (isNum(d.movedMm) ? ' · moved ' + mmText(d.movedMm) : '')
        : d.seat === 'first' ? 'first registration of this seat' : '';
      rows.push({ k: 'fit', st: at > 1 ? 'done' : 'todo', t: 'Fit the board to the fiducials', d: at > 1 ? seatTxt : 'shift, rotation and scale from the fiducials', v: '' });
    }
    const pr = d.probes || [];
    const nOk = pr.filter((q) => q.state === 'contact').length;
    const cur = pr.find((q) => q.state === 'now' || q.state === 'look');
    const lastC = pr.filter((q) => q.state === 'contact' && isNum(q.z)).pop();
    const same = stage === 'same-seat';
    const pd = cur ? (cur.state === 'look' ? 'camera look' : 'N1 touching') + (cur.alternate ? ' at nearby spot ' + cur.alternate : '')
      + (lastC ? ' · last Z ' + IL.fmt(lastC.z, 3) + ' mm' : '') : lastC ? 'last Z ' + IL.fmt(lastC.z, 3) + ' mm' : at > 3 ? 'done' : 'N1 vacuum touch, pressure off';
    const nAll = same ? 3 : pr.length || (p && p.registrationTargets ? p.registrationTargets.probes.length : 0);
    rows.push({ k: 'probe', st: stOf(3), t: (same ? 'Same-seat check' : only ? 'Probe where it is planned' : 'Probe') + (at === 3 && nAll ? ' · point ' + Math.min(nAll, nOk + 1) + ' of ' + nAll : nAll ? ' · ' + nAll + ' points' : ''),
      d: pd, v: at === 3 ? '≈ ' + IL.fmtSecs(Math.max(0, (same ? 3 : pr.length) - nOk) * ((est.probe && pr.length ? est.probe / pr.length : 17))) : '' });
    rows.push({ k: 'plan', st: stOf(4), t: 'Plan again', d: '', v: '' });
    IL.keyed($('reg-phases'), JSON.stringify(rows), () => rows.map((x, i) => el('li', { class: x.st === 'done' ? '' : x.st, 'data-ph': x.k },
      x.st === 'done' ? IL.glyph('ok', 'sm') : x.st === 'now' ? IL.glyph('live', 'sm') : IL.glyph('num', 'sm', 'Step ' + (i + 1), i + 1),
      el('div', {}, el('div', { class: 'ph__t', text: x.t }), x.d ? el('div', { class: 'ph__d', text: x.d }) : null),
      el('span', { class: 'ph__v', text: x.v || '' }))));
  }
  /** Result (§8.1 states 3 and 4): the card with its first sentence machine-readable, then the next actions. */
  function renderRegResult(s, p, mode, v, fin) {
    const box = $('reg-result');
    IL.show(box, mode === 'result');
    if (mode !== 'result') return;
    const ok = v ? v.ok : fin.status === 'OK';
    const status = v ? v.status : fin.status;
    const kind = ok ? 'ok' : status === 'REFUSED' ? 'need' : 'block';
    IL.setClass(box, 'ri reg-result ri--' + kind);
    const key = JSON.stringify([v ? [v.runId, v.status, v.newPlanId, v.message] : [fin.runId, fin.status, fin.message], p && p.planId, !!S.config]);
    IL.keyed(box, key, () => {
      if (!v) {
        return [IL.glyph(kind), el('div', {}, el('div', { class: 'ri__t', text: STATUS_WORD[status] || status }),
          el('div', { class: 'ri__d', text: (fin.message ? strip(fin.message) + ' ' : '') + 'Reading the result…' }))];
      }
      const lines = [];
      const sentence = regSentence(v, s);
      const pts = (v.probe && v.probe.points) || [];
      const bad = pts.find((q) => q.state === 'not-bare' || q.state === 'no-contact');
      if (ok && v.fit && v.seat !== 'same') lines.push(el('div', { class: 'ri__d reg-fit', text: fitText(v.fit) + '.' }));
      // a registration whose probe failed is kept: the dots already move with the board, only the surface is missing
      if (!ok && status !== 'ABORTED' && v.registrationStored) {
        lines.push(el('div', { class: 'ri__d reg-kept', text: 'The fiducial check was saved and the board planned again with it'
          + (isNum(v.movedMm) && v.movedMm >= 0.005 ? ' (moved ' + mmText(v.movedMm) + ')' : '') + '; the board surface was not.' }));
      }
      if (bad && bad.look && bad.look.reason) lines.push(el('div', { class: 'ri__d reg-look', text: 'Camera at point ' + bad.n + ': ' + bad.look.reason + '.' }));
      for (const w of (v.warnings || []).slice(0, 2)) lines.push(el('div', { class: 'ri__d', text: IL.cap(w) + '.' }));
      // the fixes (§8.2): REG / PROBE / I4 by the message's code; "Register again" is the foot's button
      let fixes = [];
      if (ok && v.probeOnly) {
        // no fiducials to find again: its one way again is probe only
        fixes.push({ id: 'btn-reg-probe', label: 'Probe again', title: 'Touch the board again at every spot where it is planned (N1 probe) and save that surface. Press twice.' });
      } else if (ok) {
        if (v.seat === 'same' && v.sameSeat && v.sameSeat.pass) fixes.push({ id: 'btn-reg-probe', label: 'Probe again', title: 'Touch the board at every spot again (N1 probe) and save that surface. Press twice.' });
        fixes.push({ id: 'btn-reg-again', label: 'Register again', title: 'Find the fiducials again (after a move). Press twice.' });
      } else if (status === 'ABORTED') {
        fixes = [];
      } else {
        const m = IL.CODE_RE.exec(String(v.message || ''));
        const code = m ? m[1] : null;
        fixes = (code ? IL.fixesForCode(code, s, m[2]) : [{ fix: 'probe-by-hand', label: 'Do it by hand' }]).filter((f) => f.fix !== 'register');
        if (code === 'PROBE' && bad && bad.state === 'no-contact') fixes.unshift({ fix: 'board-step', label: 'Check the board top Z' });
      }
      const buttons = fixes.map((f) => (f.id ? regActionBtn(f) : fixBtn(f, 'btn--sm')));
      // one sentence (machine-readable), its lead in bold: "Registered 13:05:", "Not registered:", "Stopped 13:06 during …:"
      const cut = sentence.indexOf(': ');
      const title = cut > 0 ? [el('b', { text: sentence.slice(0, cut + 1) }), IL.nb(sentence.slice(cut + 1))] : IL.nb(sentence);
      return [IL.glyph(ok ? 'ok' : kind), el('div', { class: 'reg-result__main' }, el('div', { class: 'ri__t reg-result__t', id: 'reg-result-t' }, title), lines),
        buttons.length ? el('div', { class: 'ri__act reg-result__act' }, buttons) : null];
    });
    for (const b of box.querySelectorAll('#btn-reg-again, #btn-reg-probe')) {
      const w = registerWhy(s, b.id === 'btn-reg-probe' && probeOnlyPlan(p));
      IL.gate(b, w ? w.text : null);
    }
  }
  /** "Register again" / "Probe again" in the result card: two-press, like the foot's button. */
  function regActionBtn(f) {
    const b = el('button', { type: 'button', class: 'btn btn--sm', id: f.id, title: f.title }, f.label);
    if (f.id === 'btn-reg-probe') IL.press2(b, 'probe again', () => postRegister('always'), { ok: regOk, what: 'Probe again' });
    else IL.press2(b, 'register board', () => postRegister('auto'), { ok: regOk, what: 'Register board' });
    return b;
  }
  /** The fiducials and the probe points: expected / found, Z and state (§8.1 `table#reg-fids`, `table#reg-probes`). */
  function renderRegTables(s, p, mode, live, v) {
    const t = regTargets(p);
    const det = live ? live.detail || {} : null;
    const fids = det && det.fiducials ? det.fiducials : v && v.fiducials && v.fiducials.length ? v.fiducials
      : t && !R.byHand ? t.fids.map((f) => ({ name: f.name, expected: { x: f.x, y: f.y }, found: null, state: 'todo' })) : [];
    const fb = $('reg-fids');
    IL.show(fb, fids.length > 0 && !R.byHand);
    // the fiducial the camera looks at now: the engine visits them nearest first, its phase names it
    const fidNow = det && det.stage === 'fiducials' ? (/^fiducials: (\S+)/.exec(String(live.phase || '')) || [])[1] : null;
    const fstate = (f) => (f.found ? 'found' : f.state === 'todo' && f.name === fidNow ? 'now' : f.state || 'todo');
    const fk = JSON.stringify(fids.map((f) => [f.name, f.expected, f.found, f.state, f.offsetFromExpectedMm, f.movedMm, fstate(f)]));
    const idle = mode === 'idle';
    const regAt = idle && p && p.registration ? IL.fmtTime(p.registration.atMs - S.skewMs) : null;
    const rad = t && t.fids.length && isNum(t.fids[0].searchRadiusMm) ? Number(t.fids[0].searchRadiusMm) : 8;
    IL.keyed(fb, fk + idle, () => (fids.length ? [
      el('caption', { class: 'reg-tbl__cap' }, IL.unitText(idle ? 'Fiducials · searched within ' + mmText(rad, 0) : 'Fiducials · top camera')),
      el('thead', {}, el('tr', {}, el('th', { text: 'Name' }), el('th', {}, 'Expected X, Y ', el('span', { class: 'unit-lc', text: 'mm' })),
        el('th', {}, idle ? 'Found' : 'Off ', idle ? null : el('span', { class: 'unit-lc', text: 'mm' })), el('th', { text: '', 'aria-label': 'State' }))),
      el('tbody', {}, fids.map((f) => {
        const off = f.found ? (isNum(f.offsetFromExpectedMm) ? f.offsetFromExpectedMm : Math.hypot(f.found.x - f.expected.x, f.found.y - f.expected.y)) : null;
        const sx = fstate(f);
        const bad = sx === 'not-found' || sx === 'ambiguous' || sx === 'not-centred';
        return el('tr', { class: (sx === 'now' ? 'next' : '') + (bad ? ' bad' : '') || null, 'data-fid': f.name,
          title: f.found ? f.name + ' found at X ' + fmtMmV(f.found.x) + ', Y ' + fmtMmV(f.found.y) + (isNum(f.movedMm) ? ' · moved ' + mmText(f.movedMm) + ' since the last registration' : '') : f.name },
        el('td', { text: f.name }), el('td', { text: fmtMmV(f.expected.x) + ', ' + fmtMmV(f.expected.y) }),
        idle ? el('td', { class: regAt && !S.newSeatAtMs ? '' : 'dim', text: regAt && !S.newSeatAtMs ? regAt : '—' }) : el('td', { class: off === null ? 'dim' : '', text: off === null ? '—' : IL.fmt(off, 2) }),
        el('td', {}, sx === 'found' ? IL.glyph('ok', 'xs', 'found') : sx === 'now' ? IL.glyph('live', 'xs', 'looking') : bad ? IL.glyph('block', 'xs', sx.replace('-', ' ')) : idle ? null : IL.glyph('num', 'xs', 'not yet', '')));
      }))] : null));
    // probe points: live detail, else the result's points
    const pts = det && det.probes ? det.probes : v && v.probe && v.probe.points && v.probe.points.length ? v.probe.points : v && v.sameSeat ? v.sameSeat.points.map((q) => ({ n: q.n, x: q.x, y: q.y, z: q.z, state: q.pass ? 'contact' : 'off', storedZ: q.storedZ, deltaMm: q.deltaMm })) : [];
    const pb = $('reg-probes');
    IL.show(pb, pts.length > 0 && !R.byHand);
    const pk = JSON.stringify(pts.map((q) => [q.n, q.state, q.z, q.alternate, q.x, q.y, q.failure, q.deltaMm]));
    const same = !!(det && det.stage === 'same-seat') || !!(v && v.sameSeat && !(v.probe && v.probe.points && v.probe.points.length));
    const word = (q) => ({ todo: '—', look: 'looking', now: 'touching', contact: q.alternate ? 'alt. ' + q.alternate : 'touched', 'not-bare': 'not bare', 'no-contact': 'no contact', skipped: 'skipped', off: 'moved' }[q.state] || q.state);
    IL.keyed(pb, pk, () => (pts.length ? [
      el('caption', { class: 'reg-tbl__cap', text: same ? 'Same-seat check · 3 stored points' : 'N1 probe · vacuum touch' }),
      el('thead', {}, el('tr', {}, el('th', { text: '#' }), el('th', {}, 'X ', el('span', { class: 'unit-lc', text: 'mm' })), el('th', {}, 'Y ', el('span', { class: 'unit-lc', text: 'mm' })),
        el('th', {}, 'Z ', el('span', { class: 'unit-lc', text: 'mm' })), el('th', { text: '', 'aria-label': 'State' }))),
      el('tbody', {}, pts.map((q) => {
        const bad = q.state === 'not-bare' || q.state === 'no-contact' || q.state === 'off';
        const rows = [el('tr', { class: ((q.state === 'now' || q.state === 'look') ? 'next' : '') + (bad ? ' bad' : '') || null, 'data-n': q.n,
          title: (q.notes && q.notes.length ? q.notes.join('\n') : null) },
        el('td', { text: String(q.n) }), el('td', { text: fmtMmV(q.x) }), el('td', { text: fmtMmV(q.y) }),
        el('td', { class: isNum(q.z) ? '' : 'dim', text: isNum(q.z) ? IL.fmt(q.z, 3) : '—' }),
        el('td', { class: 'reg-st' + (bad ? ' bad' : ''), text: word(q) }))];
        const why = bad ? (q.state === 'off' ? 'stored Z ' + IL.fmt(q.storedZ, 3) + (isNum(q.deltaMm) ? ', ' + IL.fmtSigned(q.deltaMm, 3) + ' mm off (limit 0.03 mm)' : ', no contact')
          : pointWhy(q) && pointWhy(q).what) : null;
        if (why) rows.push(el('tr', { class: 'reg-why-row' }, el('td', {}), el('td', { colspan: '4', text: why })));
        return rows;
      }))] : null));
  }
  /** The tiles: the points being added by hand, else the tied surface (from either path). */
  function renderProbeTiles(s, st, tied, mode) {
    const box = $('probe-tiles');
    const sf = boardSurface(s);
    const surfPts = tied && sf ? (sf.points || []).map((q) => (Array.isArray(q) ? { x: q[0], y: q[1], z: q[2] } : q)) : [];
    const pts = R.byHand ? st.points : surfPts;
    const on = !!st.p && mode !== 'live' && (R.byHand || pts.length > 0);
    IL.show(box, on);
    if (!on) return;
    const fit = IL.boardview.planeFit(pts);
    const cfg = S.config || {};
    const lim = isNum(cfg.surfaceMaxResidualMm) ? Number(cfg.surfaceMaxResidualMm) : 0.04;
    // the worst Z against the board top Z of the plan (else the needle frame), signed (+ above, − below): the engine
    // checks a board surface against the board's own nominal Z (AUTO_REGISTER_DESIGN.md §16 item 1), as the rows do
    const lim2 = probeLimits(s, st.p);
    const tol = lim2.tol;
    const zDev = lim2.top !== null && pts.length ? pts.map((q) => q.z - lim2.top).reduce((a, b) => (Math.abs(b) > Math.abs(a) ? b : a), 0) : null;
    const mini = (k, v, u, sub, bad) => el('div', { class: 'mini' + (bad ? ' notebox--block' : '') }, el('div', { class: 'mini__k', text: k }),
      el('div', { class: 'mini__v' }, v, u ? el('span', { class: 'u', text: u }) : null), el('div', { class: 'mini__s', text: sub }));
    IL.keyed(box, JSON.stringify([R.byHand, pts.length, st.targets.length, fit && fit.worst.toFixed(4), fit && fit.slope.toFixed(5), zDev !== null && zDev.toFixed(3), lim2.top, tol]), () => [
      mini('Points', R.byHand ? pts.length + ' of ' + st.targets.length : String(pts.length), '', R.byHand ? (pts.length < 3 ? 'at least 3 to save' : 'enough to save') : 'board surface, tied'),
      mini('Worst residual', fit ? IL.fmt(fit.worst, 3) : '—', fit ? 'mm' : '', 'limit ' + IL.fmt(lim, 2) + ' mm', fit && fit.worst > lim),
      mini('Tilt', fit ? IL.fmt(fit.slope * 100, 2) : '—', fit ? 'mm / 100 mm' : '', fit ? 'plane through the points' : 'after 3 points'),
      mini(lim2.topName === 'the board top' ? 'Z vs the board top' : 'Z vs the frame', zDev !== null ? IL.fmtSigned(zDev, 2) : '—', zDev !== null ? 'mm' : '',
        'limit ±' + IL.fmt(tol, 2) + ' mm' + (lim2.top !== null ? ' of ' + IL.fmt(lim2.top, 2) : ''), zDev !== null && Math.abs(zDev) > tol),
    ]);
  }
  /** Inside "Do it by hand": the guide, the targets table and the untied note (ids and behaviour as before). */
  function renderByHand(s, st) {
    const p = st.p;
    if (!p) return;
    const n1 = IL.positions(s).n1;
    const tgt = st.nextT >= 0 ? st.targets[st.nextT] : null;
    const dist = tgt && n1 ? Math.hypot(n1.x - tgt.x, n1.y - tgt.y) : null;
    IL.keyed($('probe-guide'), [st.nextT, dist === null ? '' : dist.toFixed(2), st.points.length].join('|'), () => {
      if (!tgt) return [el('b', { text: 'All ' + st.targets.length + ' targets probed.' }), ' Save them: the board is planned again with this seat\'s surface.'];
      return ['Jog N1 to ', el('b', { text: 'target ' + (st.nextT + 1) }), " in OpenPnP's window and lower it until the tip touches. ",
        dist !== null ? el('span', { class: dist > 2 ? 'reason--need' : '' }, 'N1 is ', el('b', { text: IL.fmt(dist, 2) + ' mm' }), ' from target ' + (st.nextT + 1) + '.') : 'N1 position unknown.'];
    });
    const rows = st.targets.map((t, i) => ({ i, t, pt: st.points.find((q) => q.target === i) }));
    // rows: the target until probed, then where the point was taken; a point off its target or the board top is marked
    const plim = probeLimits(s, p);
    const ptBad = (x) => {
      if (!x.pt) return null;
      const far = Math.hypot(x.pt.x - x.t.x, x.pt.y - x.t.y);
      if (far > PROBE_NEAR_MM) return IL.fmt(far, 1) + ' mm from target ' + (x.i + 1) + ' (' + fmtMmV(x.t.x) + ', ' + fmtMmV(x.t.y) + ')';
      if (plim.top !== null && Math.abs(x.pt.z - plim.top) > plim.tol) return IL.fmtSigned(x.pt.z - plim.top, 2) + ' mm from ' + plim.topName + ' (limit ±' + IL.fmt(plim.tol, 2) + ')';
      return null;
    };
    st.ptBad = ptBad;
    st.rows = rows;
    IL.keyed($('probe-targets'), JSON.stringify([rows.map((x) => [x.t.x, x.t.y, x.pt && [x.pt.x, x.pt.y, x.pt.z]]), st.nextT, plim]), () => [
      el('thead', {}, el('tr', {}, el('th', { text: '#' }), el('th', {}, 'X ', el('span', { class: 'unit-lc', text: 'mm' })), el('th', {}, 'Y ', el('span', { class: 'unit-lc', text: 'mm' })),
        el('th', {}, 'Z ', el('span', { class: 'unit-lc', text: 'mm' })), el('th', { text: '', 'aria-label': 'Delete' }))),
      el('tbody', {}, rows.map((x) => el('tr', { class: (x.i === st.nextT ? 'next' : '') + (ptBad(x) ? ' bad' : '') || null, 'data-target': x.i,
        title: x.pt ? 'Taken at X ' + fmtMmV(x.pt.x) + ' · Y ' + fmtMmV(x.pt.y) + ' (target ' + fmtMmV(x.t.x) + ', ' + fmtMmV(x.t.y) + ')' + (ptBad(x) ? ': ' + ptBad(x) : '') : 'Target ' + (x.i + 1) },
        el('td', { text: String(x.i + 1) }), el('td', { text: fmtMmV(x.pt ? x.pt.x : x.t.x) }), el('td', { text: fmtMmV(x.pt ? x.pt.y : x.t.y) }),
        el('td', { class: x.pt ? (ptBad(x) ? 'bad' : '') : 'dim' }, x.pt ? [ptBad(x) ? IL.glyph('need', 'xs', ptBad(x)) : null, ' ' + IL.fmt(x.pt.z, 3)] : x.i === st.nextT ? 'next' : '—'),
        el('td', {}, x.pt ? el('button', { type: 'button', class: 'btn btn--quiet', title: 'Delete point ' + (x.i + 1), 'aria-label': 'Delete point ' + (x.i + 1), 'data-focus-key': 'del' + x.i,
          onclick: () => { R.probe.points = R.probe.points.filter((q) => q.target !== x.i); saveProbe(); IL.render(); } }, IL.icon('i-close')) : null)))),
    ]);
    IL.keyed($('probe-untied'), String(R.probeUntied), () => (R.probeUntied ? el('div', { class: 'refusal', role: 'alert' },
      el('div', { class: 'refusal__t', text: 'Not tied: the board or its position changed since this plan. Plan again, then save.' }),
      el('div', { class: 'btn-row' }, fixBtn({ fix: 'plan-again', label: 'Plan again', primary: true }, 'btn--sm'))) : null));
  }
  /** The pinned foot: Register board (or Checks › after a good result), or the by-hand buttons. */
  function renderProbeFoot(s, st, mode, v, fin, byHand) {
    const p = st.p;
    IL.show($('reg-foot'), !byHand);
    IL.show($('probe-foot-hand'), byHand && !!p);
    if (!byHand) {
      const live = mode === 'live';
      const ok = mode === 'result' && (v ? v.ok : fin && fin.status === 'OK');
      const btn = $('btn-register'), cont = $('btn-reg-continue');
      IL.show(cont, ok);
      IL.show(btn, !ok);
      // a board without fiducials (F10): the button probes it only, where it is planned (probe "always")
      const only = probeOnlyPlan(p);
      // N1 low: the home comes first (the reason below says so), and the press it describes comes after that home
      const note = live || mode === 'result' || IL.n1Low(s) ? '' : IL.nb(registerNote(s, p));
      IL.setText($('register-note'), note);
      IL.show($('register-note'), !!note);
      const why = live ? null : registerWhy(s, only);
      const secs = p ? regSeconds(s, p) : null;
      const run = live ? regLive(s) : null;
      const g = run ? IL.regProgress(run) : null;
      btn.dataset.mode = only ? 'probe-only' : 'register';
      setBtnLabel(btn, live ? [(only ? 'Probing…' : 'Registering…') + (g.left !== null ? ' · ≈ ' + IL.fmtClock(g.left) + ' left' : '')]
        : [IL.icon('i-capture'), (only ? 'Probe only (X/Y as planned)' : mode === 'result' ? 'Register again' : 'Register board') + (isNum(secs) ? ' · ≈ ' + IL.fmtClock(secs) : '')]);
      const idle = only ? 'Home if needed, touch the board with N1 at bare spots where it is planned (its X/Y are not checked), plan again. Press twice.'
        : 'Home if needed, find the fiducials with the top camera, touch the board with N1 at bare spots, plan again. Press twice.';
      if (btn.dataset.titleIdle !== undefined) btn.dataset.titleIdle = idle;
      else IL.setAttr(btn, 'title', idle);
      IL.gate(btn, live ? (only ? 'Probing' : 'Registering') + ': STOP stops it' : why && why.text);
      const whyLinks = why ? (why.links || [why]).filter((x) => x.href || x.fix) : [];
      IL.keyed($('register-why'), why ? why.text + '|' + whyLinks.map((x) => (x.href || '') + (x.fix || '')).join(',') : '', () => (why ? [IL.icon('i-lock'), el('span', {}, why.text,
        whyLinks.map((x, i) => [i ? ' · ' : ' ', x.href ? el('button', { type: 'button', class: 'link', onclick: () => IL.go(x.href) }, x.link || 'Open')
          : x.fix ? el('button', { type: 'button', class: 'link', 'data-fix': x.fix, onclick: (ev) => IL.runFix(x.fix, ev.currentTarget) }, x.link || 'Plan again') : null]))] : null));
      IL.show($('register-why'), !!why && !live);
      return;
    }
    if (!p) return;
    const n1 = IL.positions(s).n1;
    // foot: N1 live, Add point n, Save n points and plan again
    IL.keyed($('probe-live'), n1 ? [n1.x, n1.y, n1.z].map((x) => Number(x).toFixed(2)).join(',') : '-', () => ['X', 'Y', 'Z'].map((k) => el('div', { class: 'mini' },
      el('div', { class: 'mini__k', text: 'N1 ' + k }), el('div', { class: 'mini__v', text: n1 ? fmtMmV(n1[k.toLowerCase()]) : '—' }))));
    const add = $('btn-probe-add'), save = $('btn-probe-save');
    const lock = IL.lockReason(s, { connected: true, homed: true, pressureOff: false });
    // the warning sits in the pinned foot, right above the button it is about (visible without scrolling)
    const warn = lock ? null : probeWarn(s);
    const rows = st.rows || [];
    const ptBad = st.ptBad || (() => null);
    const badPts = rows.filter((x) => ptBad(x));
    const wtext = warn || (badPts.length ? 'Point ' + badPts.map((x) => x.i + 1).join(', ') + ': ' + ptBad(badPts[0]) + '. Delete it and probe again.' : '');
    IL.keyed($('probe-warn'), wtext, () => (wtext ? [IL.glyph('need', 'xs'), el('span', { text: wtext })] : null));
    IL.show($('probe-warn'), !!wtext);
    setBtnLabel(add, [IL.icon('i-capture'), st.nextT >= 0 ? 'Add point ' + (st.nextT + 1) + ' · N1 tip touching' : 'All targets probed']);
    IL.gate(add, lock || (st.nextT < 0 ? 'Every target has a point: save them' : !n1 ? 'No N1 position' : null));
    IL.show(add, st.nextT >= 0);
    setBtnLabel(save, [st.points.length < 3 ? 'Save after 3 points' : 'Save ' + IL.plural(st.points.length, 'point') + ' and plan again']);
    IL.show(save, st.points.length > 0);
    IL.gate(save, IL.lockReason(s, { pressureOff: false }) || (st.points.length < 3 ? 'Probe at least 3 points first' : null));
    IL.setClass(save, 'btn btn--lg btn--block' + (st.nextT < 0 ? ' btn--primary' : ''));
    const why = $('probe-why');
    IL.setText(why, lock || '');
    IL.show(why, !!lock && st.nextT >= 0);
  }

  // ================================================================================== panel: Checks step
  function buildChecks() {
    return el('div', { class: 'stack', style: 'gap:18px' },
      el('div', { id: 'run-refused', class: 'run-refused' }),
      el('div', {},
        el('div', { class: 'ready-head' }, el('div', { class: 'ready-head__t' }, el('div', { class: 'eyebrow', id: 'chk-eyebrow' }), el('div', { class: 'ready-head__q', text: 'Ready to dispense?' })),
          el('div', { class: 'ready-head__n', id: 'chk-count' })),
        el('div', { class: 'segbar', id: 'ready-segbar', role: 'img' }, Array.from({ length: 9 }, () => el('i'))),
        el('div', { class: 'ready-sub', id: 'plan-interlocks' })),
      el('div', { id: 'chk-needs' }),
      el('div', { id: 'chk-ready' }),
      el('div', { id: 'chk-wait' }));
  }
  function buildChecksFoot() {
    const reason = el('div', { class: 'run-foot__reason', id: 'run-reason', hidden: true });
    const btn = el('button', { type: 'button', class: 'btn btn--lg btn--block', id: 'btn-run' });
    btn.addEventListener('click', () => { const d = footDecision(S.status); openConfirm(d ? 'rest' : testLimit() ? 'test' : 'full'); });
    const max = el('input', { class: 'input', id: 'run-maxdots', type: 'number', min: '1', step: '1', inputmode: 'numeric', placeholder: 'all', 'aria-label': 'Only the first N dots' });
    max.value = R.maxDots;
    max.addEventListener('input', () => { R.maxDots = max.value; IL.render(); });
    const testRow = el('div', { class: 'run-test', id: 'run-test', hidden: !R.test },
      el('label', { for: 'run-maxdots', text: 'Only the first' }), max, el('span', { id: 'run-test-of' }),
      el('div', { class: 'field__err', id: 'run-maxdots-err', hidden: true }));
    const testLink = el('button', { type: 'button', class: 'link', id: 'btn-run-test' }, 'Test run with fewer dots…');
    testLink.addEventListener('click', () => {
      R.test = !R.test;
      if (!R.test) { R.maxDots = ''; max.value = ''; }
      IL.show(testRow, R.test);
      if (R.test) max.focus();
      IL.render();
    });
    const allLink = el('button', { type: 'button', class: 'link', id: 'run-all-link', hidden: true, onclick: () => openConfirm('all') }, 'Wipe all, run all…');
    // a board with all its dots already: Next board is the action (UI_DESIGN §8.1.7), running it again comes second
    const nextBtn = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-run-next', hidden: true, onclick: () => askNextBoard() },
      IL.icon('i-swap'), 'Next board');
    return el('div', { class: 'stack', style: 'gap:10px', 'data-refusals': '' }, reason, nextBtn, btn, testRow,
      el('div', { class: 'run-foot__links' }, el('span', { id: 'run-scope' }), testLink, allLink));
  }
  /** Two dots of one pad: the plan's pad index, else the pad id (the engine's rule for a test run's cut, PlanStore). */
  const samePad = (a, b) => (a.padIndex >= 0 || b.padIndex >= 0 ? a.padIndex === b.padIndex : a.padId === b.padId);
  /**
   * The "first N dots" limit if set and smaller than the board: {n, asked, total} or null; .bad for an invalid entry.
   * A test run lays whole pads (a pad's dose is all its dots): the cut falls before the pad it would split, so n can be
   * fewer than asked.
   */
  function testLimit() {
    const p = plan();
    if (!R.test || !p || String(R.maxDots).trim() === '') return null;
    const v = Number(R.maxDots);
    const total = p.dots.length;
    if (!Number.isInteger(v) || v < 1) return { bad: 'A whole number of dots, at least 1' };
    if (v >= total) return null;
    let n = v;
    while (n > 0 && samePad(p.dots[n - 1], p.dots[n])) n--;
    if (n === 0) {
      let first = 1;
      while (first < total && samePad(p.dots[0], p.dots[first])) first++;
      return { bad: 'A test run lays whole pads: the first pad, ' + p.dots[0].padId + ', has ' + first + ' dots, so ask for at least ' + first };
    }
    return { n, asked: v, total };
  }
  /** "18 of 160 dots (whole pads: 18 of the 20 asked for)" of a test limit. */
  const limitText = (lim) => lim.n + ' of ' + lim.total + ' dots' + (lim.n < lim.asked ? ' (whole pads: ' + lim.n + ' of the ' + lim.asked + ' asked for)' : '');
  function interlockLine(s) {
    const p = plan();
    const list = s.interlocks || [];
    const viol = new Set(((p && p.interlocks && p.interlocks.violations) || []).map((v) => v.code));
    const cc = p && S.cleanCheck && !S.cleanCheck.ok ? S.cleanCheck : null;
    // the same severity as the cards (§9.3): an interlock the engine blocks on but the operator can clear in place
    // (home, confirm the angle, confirm the offset, probe, wipe) counts as "needs you", not "blocked"
    const r = IL.ready();
    const sev = {};
    for (const it of r.items.concat(r.extras)) {
      if (it.ready || it.state === 'wait') continue;
      const st = it.state === 'block' ? 'block' : 'need';
      if (sev[it.code] !== 'block') sev[it.code] = st;
    }
    // a run homes first and checks Z after the home by itself: MACHINE "not homed" and I4 "Z not checked" are its own
    // first steps then, not blockers (the machine item says so). Not when nothing is left to check Z on, or Z moved (the
    // readiness extra I4-z): that blocks the run
    const mi = r.items[0] || {};
    const zBlocked = r.extras.some((x) => x.key === 'I4-z');
    const runFirst = (i) => (i.id === 'MACHINE' && !!mi.homeFirst) || (i.id === 'I4' && !zBlocked && !!(mi.homeFirst || mi.zCheckFirst)
      && /were measured before the home/i.test(String(i.text || '')));
    const c = { ok: 0, need: 0, block: 0, off: 0, first: 0 };
    const firstNames = [];
    for (const i of list) {
      let st = i.state === 'blocked' ? (runFirst(i) ? 'first' : 'block') : i.state === 'warning' ? 'need' : i.state === 'off' ? 'off' : 'ok';
      if (st === 'first') firstNames.push(i.id === 'MACHINE' ? 'home' : 'Z check');
      if (st === 'ok' && viol.has(i.id)) st = 'block';
      if (i.id === 'I10' && cc && st === 'ok') st = 'need';   // §9.3 overlay: "ok" only means a camera is there
      if (st === 'block' && sev[i.id] === 'need') st = 'need';
      c[st]++;
    }
    const parts = [];
    if (c.ok) parts.push(c.ok + ' ok');
    if (c.need) parts.push(c.need + ' needs you');
    if (c.block) parts.push(c.block + ' blocked');
    if (c.first) parts.push(firstNames.join(' and ') + ' first');
    if (c.off) parts.push(c.off + ' not fitted');
    const allPass = !!p && p.interlocks && p.interlocks.ok && !c.block && !c.need;
    return { text: allPass ? (c.first ? 'All interlocks pass · ' : 'All interlocks pass for this plan · ') + parts.join(' · ') : list.length + ' interlocks: ' + parts.join(' · '), total: list.length };
  }
  const SEG_CLASS = { ok: 'ok', you: 'you', info: 'you', off: '', need: 'need', block: 'block', live: 'live', checking: 'checking', wait: 'wait' };
  const TILE_GO = { machine: { view: 'machine' }, angle: { view: 'angle' }, needle: { href: '#setup/needle' }, regulator: { view: 'pressure' },
    padtypes: { href: '#profile', hrefNow: () => IL.padProfile && IL.padProfile.needsHref() }, surface: { step: 'probe' }, plan: { view: 'interlocks' }, purge: { view: 'purge' }, clean: { view: 'camera' } };
  function tileGo(key) {
    const g = TILE_GO[key];
    if (!g) return;
    if (g.view) IL.openView(g.view, { keep: true });
    else if (g.href) IL.go((g.hrefNow && g.hrefNow()) || g.href);
    else if (g.step) goStep(g.step);
  }
  function cleanCard(it, first, s) {
    // the pads-clean card: its first sentence is machine-readable (#clean-result, §8.1.4)
    const cc = S.cleanCheck && plan() && IL.planMatches(S.cleanCheck.planId || plan().planId) ? S.cleanCheck : null;
    const run = s.run;
    const checking = run && run.running && run.label === 'clean-pad check';
    let detail;
    const ref = checking ? null : ccRefusedText(cc);
    const refLine = ref ? el('b', { class: 'cc-refused', text: ref + ' ' }) : null;
    if (checking) {
      // the counter is patched every render (renderChecks): the card itself is not rebuilt while the check runs
      detail = ['The top camera visits every pad, the pressure stays off: ', el('span', { class: 'clean-tour', text: tourLine(run) })];
    } else if (cc && IL.cleanPositionWrong(cc)) {
      // no pad read clean: the boxes saw solder mask, the board X/Y or rotation is off (§13): check it, do not wipe
      detail = [IL.cleanSummary(cc), ' Check the board X/Y and rotation in the Board step (the camera box sits beside every pad), then check again.'];
    } else if (cc) {
      const dirty = IL.dirtyPads(cc);
      const sent = IL.cleanSummary(cc);
      detail = [sent];
      if (dirty.length && dirty.length < (cc.targets || Infinity)) {
        const shown = R.showAllDirty ? dirty : dirty.slice(0, 6);
        detail.push(' Paste on ', shown.map((d, i) => [i ? (i === shown.length - 1 && dirty.length <= shown.length ? ' and ' : ', ') : '', el('b', { text: d.padId }),
          d.share === null || d.share === undefined ? '' : ' (' + IL.fmt(d.share, 2) + ')']));
        if (dirty.length > shown.length) detail.push(' and ', el('button', { type: 'button', class: 'link', onclick: () => { R.showAllDirty = true; IL.render(); } }, (dirty.length - shown.length) + ' more'));
        detail.push('. Wipe them in place; clean copper reads ' + IL.fmt(cc.minBrightFraction, 2) + ' or more.');
      }
    } else detail = [it.detail];
    if (refLine) detail.unshift(refLine);
    const posWrong = !checking && IL.cleanPositionWrong(cc);
    const btn = fixBtn({ fix: 'clean-check', label: posWrong ? 'Check again' : cc && !cc.ok ? 'Wiped: check again' : 'Check pads', icon: 'i-refresh', title: 'The top camera looks at every pad (≈ ' + IL.fmtClock(tourSeconds(plan().pads.length)) + '), the pressure stays off. Press twice.' }, null, 'btn-clean-check');
    const cam = el('button', { type: 'button', class: 'btn btn--icon', title: 'Top camera snapshot (nothing moves)', 'aria-label': 'Top camera snapshot', onclick: () => IL.openView('camera', { keep: true }) }, IL.icon('i-camera'));
    const pos = posWrong ? fixBtn({ href: '#run/board', label: 'Check the board position', icon: 'i-swap' }, 'rt-pos') : null;
    return el('div', { class: 'ri ri--' + (it.state === 'live' ? 'live' : it.state === 'block' ? 'block' : 'need'), id: 'clean-result', 'data-key': it.key, 'data-refusals': '' },
      it.state === 'live' ? IL.glyph('live') : IL.glyph(it.state === 'block' ? 'block' : 'need'),
      el('div', {}, el('div', { class: 'ri__t' }, it.title, first ? el('span', { class: 'tag tag--next', text: 'Next' }) : null), el('div', { class: 'ri__d' }, detail)),
      el('abbr', { class: 'icode', title: 'Interlock I10', text: 'I10' }),
      checking ? null : [pos ? el('div', { class: 'ri__act ri__act--pos' }, pos) : null, el('div', { class: 'ri__act' }, btn, cam)]);
  }
  /** "Refused 14:05: the pressure hold is on: …." of a check of this plan refused at its start after the report cc, or null. */
  function ccRefusedText(cc) {
    const ref = R.ccRefused;
    if (!ref || !IL.planMatches(ref.planId) || (cc && (cc.checkedAtMs || 0) >= (ref.finishedAtMs || 0))) return null;
    const msg = String(ref.message || 'the check did not start').replace(IL.CODE_RE, '$2').replace(/[.\s]+$/, '');
    return 'Refused ' + IL.fmtTime(ref.finishedAtMs ? ref.finishedAtMs - S.skewMs : Date.now()) + ': ' + msg + '.';
  }
  /** "≈ pad 57 of 160 · 1:52 left." of a running clean-pad check. */
  function tourLine(run) {
    const tp = tourProgress(run, run.elapsedS || 0, plan().pads.length);
    return tp.text + ' · ' + IL.fmtClock(tp.left) + ' left.';
  }
  function needCard(it, first, s) {
    if (it.key === 'clean') return cleanCard(it, first, s);
    const kind = it.state === 'block' ? 'block' : it.state === 'live' ? 'live' : it.state === 'checking' ? 'checking' : 'need';
    const glyph = it.state === 'live' ? IL.glyph('live') : it.state === 'checking' ? IL.glyph('checking') : IL.glyph(kind === 'block' ? 'block' : 'need');
    const fixes = (it.fixes || []).map((f) => fixBtn(Object.assign({}, f, { primary: false })));
    return el('div', { class: 'ri ri--' + kind, 'data-key': it.key, 'data-refusals': '' },
      glyph, el('div', {}, el('div', { class: 'ri__t' }, it.title, first ? el('span', { class: 'tag tag--next', text: 'Next' }) : null), el('div', { class: 'ri__d', text: it.detail })),
      el('abbr', { class: 'icode', title: 'Interlock ' + it.code, text: it.code }),
      fixes.length ? el('div', { class: 'ri__act' }, fixes) : null);
  }
  function readyTile(it, s) {
    if (it.key === 'clean' && (S.cleanCheck || cleanEnabled(s) || plan())) {
      const cc = S.cleanCheck && plan() && IL.planMatches(S.cleanCheck.planId || plan().planId) ? S.cleanCheck : null;
      const btn = fixBtn({ fix: 'clean-check', label: 'Check pads', title: 'The top camera looks at every pad, the pressure stays off. Works with the check switched off too. Press twice.' }, 'btn--sm', 'btn-clean-check');
      return el('div', { class: 'rt rt--wide rt--clean', id: 'clean-result', title: it.tip || it.detail, 'data-refusals': '' },
        IL.glyph(it.state, 'xs'), el('span', { class: 'rt__t', text: it.title + (it.state === 'off' ? ' · off' : '') }), btn,
        el('span', { class: 'rt__d', text: ccRefusedText(cc) || (cc ? IL.cleanSummary(cc) : it.detail) }));
    }
    // a note that counts ready ("homes first, checks Z") takes the whole row: its value is longer than a tile
    return el('button', { type: 'button', class: 'rt rt--one' + (it.state === 'info' ? ' rt--wide rt--note' : ''), title: it.tip || it.detail, 'data-key': it.key, onclick: () => tileGo(it.key) },
      IL.glyph(it.state, 'xs'), el('span', { class: 'rt__t', text: it.title }), el('span', { class: 'rt__v', text: it.value }));
  }
  function renderChecks(s) {
    const p = plan();
    const r = IL.ready();
    // header: eyebrow, count, segment bar, interlock line
    IL.setText($('chk-eyebrow'), p ? 'Board ' + boardNumber() + ' · plan ' + shortId(p) : 'No plan yet');
    IL.keyed($('chk-count'), S.planPending ? 'pending' : r.count, () => (S.planPending ? el('small', { class: 'checking', text: 'checking…' }) : [String(r.count), ' ', el('small', { text: 'of 9' })]));
    const bar = $('ready-segbar');
    r.items.forEach((it, i) => IL.setClass(bar.children[i], SEG_CLASS[it.state] || ''));
    IL.setAttr(bar, 'aria-label', 'Readiness in fixed order: ' + r.items.map((it) => it.title + ' ' + (IL.WORD[it.state] || it.state)).join(', '));
    const il = interlockLine(s);
    IL.keyed($('plan-interlocks'), il.text, () => [el('span', { class: 'ready-sub__t', text: il.text, title: il.text }),
      el('button', { type: 'button', class: 'link', onclick: () => IL.openView('interlocks', { keep: true }) }, 'All ' + il.total)]);
    // a refused board run (409 from the engine, I10): its message and fix-its on top
    const ref = R.refused && p && IL.planMatches(R.refused.planId) ? R.refused : null;
    IL.keyed($('run-refused'), ref ? ref.runId : '', () => {
      if (!ref) return null;
      const m = IL.CODE_RE.exec(ref.message || '');
      return el('div', { class: 'card__banner run-refused__b', style: 'margin:0', role: 'alert' },
        el('div', { class: 'run-refused__t', title: ref.message || '' }, el('b', { text: 'Refused ' + IL.fmtTime(ref.finishedAtMs ? ref.finishedAtMs - S.skewMs : Date.now()) + ': ' }), ref.message || 'the run was refused'),
        m ? el('div', { class: 'btn-row', style: 'margin-top:8px' }, IL.fixesForCode(m[1], s, m[2]).map((f) => fixBtn(f, 'btn--sm'))) : null);
    });
    // needs you: NEXT first (the blocked item the verdict names, else the first not ready), then extras and the items in
    // their fixed order; ready tiles; items known after planning
    const needs0 = r.needs.filter((x) => x.state !== 'wait');
    const needs = r.next && needs0.includes(r.next) ? [r.next].concat(needs0.filter((x) => x !== r.next)) : needs0;
    const run = s.run;
    const ccKey = S.cleanCheck ? S.cleanCheck.checkedAtMs : '';
    const checking = !!(run && run.running && run.label === 'clean-pad check');
    const ccRef = R.ccRefused ? R.ccRefused.runId : '';
    const nk = JSON.stringify(needs.map((x) => [x.key, x.state, x.detail, (x.fixes || []).map((f) => f.label)])) + ccKey + checking + R.showAllDirty + ccRef;
    IL.keyed($('chk-needs'), nk, () => (needs.length ? [IL.sectionK('Needs you', needs.length),
      el('div', { class: 'stack', style: 'margin-top:10px;gap:10px' }, needs.map((x, i) => needCard(x, i === 0, s)))] : null));
    const tour = checking && plan() ? $('chk-needs').querySelector('.clean-tour') : null;
    if (tour) IL.setText(tour, tourLine(run));
    const ready = r.items.filter((x) => x.ready);
    const rk = JSON.stringify(ready.map((x) => [x.key, x.state, x.value, x.tip])) + ccKey + ccRef;
    IL.keyed($('chk-ready'), rk, () => (ready.length ? [IL.sectionK('Ready', ready.length),
      el('div', { class: 'rtiles', style: 'margin-top:10px' }, ready.filter((x) => x.key !== 'clean').map((x) => readyTile(x, s)),
        ready.filter((x) => x.key === 'clean').map((x) => readyTile(x, s)))] : null));
    const wait = r.items.filter((x) => x.state === 'wait');
    IL.keyed($('chk-wait'), JSON.stringify(wait.map((x) => x.key)), () => (wait.length ? [IL.sectionK('Known after planning', wait.length),
      el('div', { class: 'rtiles', style: 'margin-top:10px' }, wait.map((x) => el('div', { class: 'rt rt--one', title: x.detail }, IL.glyph('wait', 'xs'), el('span', { class: 'rt__t', text: x.title }), el('span', { class: 'rt__v', text: '—' }))))] : null));
    // the clean-check button: camera tour, pressure off, machine idle
    const cb = $('btn-clean-check');
    if (cb) IL.gate(cb, IL.lockReason(s, { connected: true, homed: true }) || (!p ? 'Plan a board first' : null));
    renderRunFoot(s, r);
  }
  /** The decision the Checks foot acts on (a zero-dot stop is no decision there: the board simply runs again). */
  function footDecision(s) {
    const d = decision(s);
    return d && !zeroDots(d) ? d : null;
  }
  function renderRunFoot(s, r) {
    const p = plan();
    const btn = $('btn-run');
    if (!btn) return;
    const dec = footDecision(s);
    const total = p ? p.dots.length : 0;
    const complete = !!p && !dec && R.doneSet.size >= total && total > 0;
    // a test run is not offered on a board that already has paste (§8.1.7), nor on one that has every dot
    IL.show($('btn-run-test'), !dec && !complete);
    IL.show($('run-all-link'), !!dec);
    IL.show($('btn-run-next'), complete);
    if (dec || complete) { R.test = false; }
    const lim = dec ? null : testLimit();
    const pending = S.planPending;
    let reason = pending ? 'Re-reading the plan (checking…)' : r.canRun ? null : r.runReason;
    if (lim && lim.bad) reason = reason || 'Test run: ' + lim.bad.toLowerCase();
    const left = dec ? padsLeft(p) : null;
    const active = IL.recoveryActive();
    if (dec && active) reason = 'Unlocks after the recovery steps on the right';
    if (dec && !left.length) reason = reason || 'No pads left';
    const style = lim && !lim.bad ? 'btn--test' : r.runStyle === 'go' && !complete ? 'btn--primary' : 'btn--hold';
    IL.setClass(btn, 'btn btn--lg btn--block ' + (reason ? '' : dec ? 'btn--primary' : style));
    setBtnLabel(btn, [IL.icon('i-play'), dec ? 'Finish ' + IL.plural(left.length, 'pad') + ' left' : lim && lim.n ? 'TEST: first ' + lim.n + ' of ' + total + ' dots' + (lim.n < lim.asked ? ' · whole pads' : '')
      : (complete ? 'Run it again · ' : 'Run board · ') + IL.plural(total, 'dot')]);
    IL.gate(btn, reason);
    // reason line above the button: soft (needs you, run anyway) or blocked (finish first); a lock (a task runs, the
    // recovery) shows the lock glyph, not the red ✕ of a blocked check
    const rl = $('run-reason');
    const pasted = p ? padsDone(p).length : 0;
    const soft = !reason && (dec
      ? (dec.cause === 'partial' ? 'Paste on ' + IL.plural(pasted, 'pad') + ' already (' + (dec.kind === 'test' ? 'test run' : 'part done') + '): finish the '
        : 'Board stopped at dot ' + dec.run.dotsDone + ': finish the ') + IL.plural(left.length, 'pad') + ' left, or wipe ' + (dec.cause === 'partial' ? 'them' : 'all pads') + ' and run the whole board.'
      : complete ? 'All ' + total + ' dots are on this board already. Unload it and press Next board, or wipe it before running it again.'
        : r.runReason);
    const text = reason || soft || '';
    const locked = !!reason && (IL.taskRunning(s) || (dec && active) || S.online === false);
    const kind = pending ? 'checking' : locked ? 'lock' : reason ? 'block' : 'need';
    IL.keyed(rl, kind + text, () => {
      if (!text) return null;
      const m = /^(\d+ needs? you\.)\s*(.*)$/.exec(text);
      return [kind === 'lock' ? IL.icon('i-lock') : IL.glyph(kind, 'xs'), el('span', {}, m ? [el('b', { text: m[1] }), ' ' + m[2]] : text)];
    });
    IL.setClass(rl, 'run-foot__reason' + (kind === 'block' ? ' run-foot__reason--block' : kind === 'lock' ? ' run-foot__reason--lock' : ''));
    IL.show(rl, !!text);
    // test limit row
    const tr = $('run-test');
    IL.show(tr, R.test);
    const inp = $('run-maxdots');
    if (inp && document.activeElement !== inp && inp.value !== String(R.maxDots)) inp.value = R.maxDots;
    if (inp) {
      IL.setAttr(inp, 'max', String(Math.max(1, total - 1)));
      inp.classList.toggle('input--invalid', !!(lim && lim.bad));
    }
    IL.setText($('run-test-of'), 'of ' + total + ' dots');
    const err = $('run-maxdots-err');
    IL.setText(err, lim && lim.bad ? lim.bad : R.test && String(R.maxDots).trim() !== '' && !lim ? 'That is the whole board: the full run.' : '');
    IL.show(err, !!err.textContent);
    IL.setText($('run-scope'), dec ? 'Only the pads left · ' + IL.plural(left.length, 'pad') : lim && lim.n ? 'TEST · first ' + limitText(lim)
      : complete ? 'Board complete · ' + total + ' of ' + total + ' dots' : 'Full board · all ' + total + ' dots');
    IL.setText($('btn-run-test'), R.test ? 'Run the full board instead' : 'Test run with fewer dots…');
  }

  // ================================================================================== panel: Run step (running)
  function buildRunStep() {
    return el('div', { class: 'stack', style: 'gap:16px' },
      el('div', { id: 'run-step-idle' }),
      el('div', { id: 'run-step-live' }, IL.sectionK('One pressure session'), el('ol', { class: 'phases', id: 'run-phases', style: 'margin-top:8px' })),
      el('div', { class: 'notebox notebox--need', id: 'run-after', hidden: true }),
      el('p', { class: 'stop-line' }, 'STOP sends ', el('span', { class: 'code', text: IL.STOP_CODES }),
        ': quick stop, dispenser valve and pump off, pick valve and pump off, screw motor off.'));
  }
  function renderRunStep(s) {
    const p = plan();
    const run = boardRunOfPlan(s);
    const live = !!(run && run.running);
    IL.show($('run-step-live'), live);
    IL.keyed($('run-step-idle'), live ? '' : 'idle' + (p ? p.planId : ''), () => {
      if (live) return null;
      const est = p ? estimate(s) : null;
      return [el('div', { class: 'notebox' }, el('b', { text: 'No board is running.' }),
        el('div', { class: 'muted', text: p ? 'Start it from the Checks step: ' + IL.plural(p.dots.length, 'dot') + (est ? ', ≈ ' + IL.fmtClock(est.total) + ' for the board.' : '.') : 'Plan a board first.' })),
      el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', onclick: () => goStep(p ? 'checks' : 'board') }, p ? 'Open Checks' : 'Choose a board'))];
    });
    if (!live) { IL.show($('run-after'), false); return; }
    const t = phaseTimes(s, run, Date.now());
    const prof = p.profile || {};
    const purgeN = prof.purgeDots || 0;
    const row = (s.dispense && s.dispense.purgeRow) || {};
    const spotA = row.defined ? row.usedSpots - purgeN + 1 : null;
    const spots = spotA !== null && spotA >= 1 ? ' on spots ' + spotA + ' to ' + row.usedSpots : '';
    const cfg = S.config || {};
    const settle = ((isNum(cfg.pumpToValveSettleMs) ? cfg.pumpToValveSettleMs : 1000) + (isNum(cfg.valveSettleMs) ? cfg.valveSettleMs : 3000)) / 1000;
    const est = estimate(s, run.dotsPlanned, run.dotsPlanned);
    const sm0 = p.summary || {};
    const perDot = isNum(sm0.estimatedWorkSeconds) && p.dots.length ? sm0.estimatedWorkSeconds / p.dots.length : null;
    const purgeEst = perDot !== null ? purgeN * perDot : null;
    const camKey = Object.keys(R.ph.at || {}).find((k) => /I10|clean/i.test(k));
    const inCam = /I10|clean/i.test(run.phase || '');
    const disp = run.phase === 'dispensing';
    const sinceDisp = R.ph.at.dispensing ? (Date.now() - R.ph.at.dispensing) / 1000 : 0;
    const dotsStarted = run.dotsDone > 0;
    const allDots = run.dotsDone >= run.dotsPlanned;
    const ph = [];
    // states: done | now | todo. A run homes first and checks Z after the home by itself (run.detail, §8.3)
    const rd = run.detail || {};
    if (rd.home && rd.home.needed) {
      ph.push({ st: rd.stage === 'home' ? 'now' : 'done', t: 'Home', d: rd.stage === 'home' ? 'the head goes to its home corner · pressure off' : 'homed',
        v: rd.stage === 'home' ? '≈ ' + IL.fmtSecs(rd.home.estimateS) : '' });
    }
    if (rd.stage === 'z check' || rd.zCheck) {
      const z = rd.zCheck;
      ph.push({ st: rd.stage === 'z check' ? 'now' : 'done', t: 'Z check',
        d: z && isNum(z.z) ? IL.nb('N1 touched Z ' + IL.fmt(z.z, 3) + ' mm, ' + IL.fmtSigned(z.deltaMm, 3) + ' mm from ' + IL.fmt(z.refZ, 3) + ' mm') : 'N1 touches a stored point', v: '' });
    }
    if (camKey || inCam || (cleanEnabled(s) && !disp)) {
      const n = run.dotsPlanned;
      const camS = t.cam;
      if (inCam) {
        const el2 = (Date.now() - (R.ph.at[camKey] || Date.now())) / 1000;
        const tp = tourProgress(run, el2, (p.pads || []).length);
        ph.push({ st: 'now', t: 'Pads checked with the camera', d: tp.text + ' · ' + IL.fmtClock(tp.left) + ' left · pressure off', v: IL.fmtClock(el2) });
      } else if (disp) ph.push({ st: 'done', t: 'Pads checked with the camera', d: IL.plural(n, 'pad') + ' clean · pressure off', v: camS !== null ? IL.fmtClock(camS) : '' });
      else ph.push({ st: 'todo', t: 'Pads checked with the camera', d: 'pressure off', v: '≈ ' + IL.fmtClock(tourSeconds(n)) });
    }
    const pre = (k) => (disp ? (dotsStarted ? 'done' : k) : 'todo');
    const stSettle = disp && !dotsStarted ? (sinceDisp < settle ? 'now' : 'done') : pre('todo');
    ph.push({ st: stSettle, t: 'Pump on, valve open, settle', v: IL.fmt(settle, 1) + ' s' });
    const purgeEnd = settle + (purgeEst || 0);
    const stPurge = disp && !dotsStarted ? (sinceDisp < settle ? 'todo' : sinceDisp < purgeEnd ? 'now' : 'done') : pre('todo');
    ph.push({ st: stPurge, t: purgeN + ' purge dots' + spots, v: purgeEst !== null ? (stPurge === 'done' && t.pre ? '≈ ' + IL.fmt(Math.max(0, Math.min(purgeEst, t.pre - settle)), 1) + ' s' : '≈ ' + IL.fmt(purgeEst, 1) + ' s') : '' });
    const stCross = disp && !dotsStarted ? (sinceDisp < purgeEnd ? 'todo' : 'now') : pre('todo');
    const crossV = dotsStarted && t.pre !== null && purgeEst !== null ? '≈ ' + IL.fmt(Math.max(0.1, t.pre - settle - purgeEst), 1) + ' s' : '';
    ph.push({ st: stCross, t: 'Cross to the board', v: crossV });
    const nx = nextIndex(s);
    const nd = nx !== null ? p.dots[nx] : null;
    ph.push({ st: dotsStarted ? (allDots ? 'done' : 'now') : 'todo', t: 'Board dots ' + run.dotsDone + ' of ' + run.dotsPlanned,
      d: dotsStarted ? (nd ? 'next ' + nd.padId + ' · ' : '') + dph(run.liveDph) + ' DPH dots only' : est ? '≈ ' + IL.fmtClock(est.work) + ' for ' + IL.plural(run.dotsPlanned, 'dot') : '',
      v: t.dots !== null ? IL.fmtClock(t.dots) : '' });
    ph.push({ st: allDots ? 'now' : 'todo', t: 'Vent, pump off, screw released', d: 'automatic at the end', v: '≈ ' + VENT_S + ' s' });
    const key = JSON.stringify(ph);
    IL.keyed($('run-phases'), key, () => ph.map((x, i) => el('li', { class: x.st === 'done' ? '' : x.st },
      x.st === 'done' ? IL.glyph('ok', 'sm') : x.st === 'now' ? IL.glyph('live', 'sm') : IL.glyph('num', 'sm', 'Step ' + (i + 1), i + 1),
      el('div', {}, el('div', { class: 'ph__t', text: x.t }), x.d ? el('div', { class: 'ph__d', text: x.d }) : null),
      el('span', { class: 'ph__v', text: x.v || '' }))));
    // after this board: what the operator does before the next one
    const after = $('run-after');
    let msg = null;
    if (row.defined) {
      const nextNo = boardNumber() + 1;
      const need = IL.purgeNeed(row, p);
      msg = row.spotsLeft < need
        ? [el('b', { text: 'After this board: ' }), 'the purge row has ' + IL.plural(row.spotsLeft, 'spot') + ' left, ' + need + ' needed. Wipe it and reset it before board ' + nextNo + '.']
        : [el('b', { text: 'After this board: ' }), 'the purge row has ' + row.spotsLeft + ' of ' + row.spots + ' spots free, enough for ' + IL.plural(Math.floor(row.spotsLeft / Math.max(1, need)), 'board') + '.'];
    }
    IL.keyed(after, msg ? el('span', {}, msg).textContent : '', () => msg);
    IL.setClass(after, 'notebox' + (row.defined && row.spotsLeft < IL.purgeNeed(row, p) ? ' notebox--need' : ''));
    IL.show(after, !!msg);
  }

  // ================================================================================== panel: Result step
  function buildResult() {
    return el('div', { class: 'stack', id: 'run-result' });
  }
  function buildResultFoot() {
    const next = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-next-board' }, IL.icon('i-swap'), 'Next board');
    next.addEventListener('click', () => askNextBoard());
    // after a test run (paste on part of the board) the next step is to finish the pads left, not another board
    const rest = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-result-rest', hidden: true }, IL.icon('i-play'), 'Finish the pads left');
    rest.addEventListener('click', () => openConfirm('rest'));
    return el('div', { class: 'stack', style: 'gap:8px', 'data-refusals': '' }, rest, el('div', { class: 'reason', id: 'result-rest-why', hidden: true }), next,
      el('div', { class: 'run-foot__links' }, el('span', { text: 'Same board again?' }), el('button', { type: 'button', class: 'link', onclick: () => goStep('checks') }, 'Back to Checks')));
  }
  function askNextBoard() {
    const h = IL.dialog.open({ id: 'dlg-next-board', eyebrow: 'Next board', title: 'Unload this board and load the next. New seat?', size: 'sm',
      body: el('p', { class: 'run-panel-lead', text: 'A new board sits a little differently every time: the Board step opens with "new seat" answered, and the surface check goes back to needs you until Register board has checked this seat.' }),
      foot: [el('span', { class: 'spacer' }),
        el('button', { type: 'button', class: 'btn btn--lg', onclick: () => h.close('cancel') }, 'Cancel'),
        el('button', { type: 'button', class: 'btn btn--primary btn--lg', onclick: () => {
          h.close('yes');
          IL.setNewSeat(Date.now());
          R.seatSame = null;
          R.resultRunId = null;
          R.refused = null;
          R.decidedAt = Date.now();
          ssSet('il.run.decidedAt', R.decidedAt);
          R.doneSet = new Set();
          // a new board starts here (the server: every tab sees it): the board count goes up at its first dot
          IL.api('POST', '/api/board/next', {}).then(() => IL.planRefresh({ quiet: true }))
            .catch((e) => IL.toast('Next board not noted: ' + IL.errorText(e), 'need'));
          goStep('board');
        } }, 'Yes: new seat')],
      initialFocus: null });
  }
  function renderResult(s) {
    const p = plan();
    const rr = p ? resultRun(s) : null;
    const box = $('run-result');
    const part = p ? partialDecision(s) : null;
    const rb = $('btn-result-rest'), nb = $('btn-next-board');
    if (rb && nb) {
      IL.show(rb, !!part);
      if (part) setBtnLabel(rb, [IL.icon('i-play'), 'Finish ' + IL.plural(padsLeft(p).length, 'pad') + ' left']);
      const r = IL.ready();
      IL.gate(rb, part && !r.canRun ? r.runReason : null, $('result-rest-why'));
      IL.setClass(nb, 'btn btn--lg btn--block' + (part ? '' : ' btn--primary'));
    }
    const row = (s.dispense && s.dispense.purgeRow) || {};
    const key = JSON.stringify([rr && rr.runId, rr && rr.status, rr && rr.dphMotion, row.spotsLeft, p && p.planId, R.doneSet.size]);
    IL.keyed(box, key, () => {
      if (!rr) {
        const last = lastBoardRow();
        return [el('div', { class: 'notebox' }, el('b', { text: 'No board of this plan has run yet.' }),
          el('div', { class: 'muted', text: last ? 'Last board: ' + boardShort(last.boardName) + ' ' + IL.fmtTime(last.startedAtMs - S.skewMs) + ', ' + (STATUS_WORD[last.status] || last.status).toLowerCase() + ', ' + dph(last.dphMotion) + ' DPH dots only.' : 'Nothing recorded since OpenPnP started.' }))];
      }
      const kind = rr.status === 'OK' ? 'ok' : rr.status === 'REFUSED' ? 'need' : 'block';
      const sm = (p && p.summary) || {};
      // the subline keeps a label with its number ("bench 5,490" was "bench" at a line's end and "5,490" on the next)
      const mini = (k, v, u, sub) => el('div', { class: 'mini' }, el('div', { class: 'mini__k' }, k), el('div', { class: 'mini__v' }, v, u ? el('span', { class: 'u', text: u }) : null), sub ? el('div', { class: 'mini__s' }, IL.nb(sub)) : null);
      return [
        el('div', { class: 'result-head' }, IL.glyph(kind), el('div', {}, el('div', { class: 'result-head__t', text: (STATUS_WORD[rr.status] || rr.status) + ' · ' + outcomeText(rr, p) }),
          el('div', { class: 'result-head__s', text: boardShort(rr.boardName || p.boardName) + ' · board ' + (isNum(rr.boardNumber) ? rr.boardNumber : boardNumber()) + ' · ended ' + IL.fmtTime(rr.endedAtMs - S.skewMs) }))),
        rr.message ? el('div', { class: 'notebox' + (kind === 'ok' ? '' : ' notebox--' + kind), text: rr.message }) : null,
        el('div', { class: 'result-tiles' },
          mini(['', dphAbbr(), ' dots only'], dph(rr.dphMotion), '', 'estimate ' + dph(sm.estimatedWorkDph) + ' · bench ' + IL.fmtInt(BENCH_DPH)),
          mini(['', dphAbbr(), ' overall'], dph(rr.dphOverall), '', 'estimate ' + dph(sm.estimatedDph)),
          mini('Total time', isNum(rr.totalSeconds) ? IL.fmtClock(rr.totalSeconds) : '—', '', isNum(rr.motionSeconds) ? 'dots ' + IL.fmtClock(rr.motionSeconds) : null),
          mini('Purge spots left', row.defined ? String(row.spotsLeft) : '—', row.defined ? 'of ' + row.spots : '', row.defined ? (row.spotsLeft < IL.purgeNeed(row, p) ? 'wipe and reset before the next board' : 'next board uses ' + IL.purgeNeed(row, p)) : 'not set')),
        p && p.profile ? el('div', {}, IL.sectionK('Profile'), el('a', { class: 'result-recipe', href: '#profile', title: 'Dispense profile this plan was made with', text: IL.profileText(p.profile) })) : null,
      ];
    });
  }

  // ================================================================================== the run confirm (#dlg-run, §8.1.5)
  function confirmMode(kind, opts) {
    const p = plan();
    const N = p.dots.length;
    if (kind === 'rest') {
      const known = new Set(p.pads.map((x) => x.padId));
      // a list from elsewhere (Records: the pads one stopped run did not reach) never brings back a pad this page saw
      // pasted on this board (an earlier test or finish run)
      const left = new Set(padsLeft(p));
      const pads = (opts && opts.padIds ? opts.padIds.filter((id) => known.has(id) && left.has(id)) : padsLeft(p)).slice();
      const nd = p.dots.filter((d) => pads.includes(d.padId)).length;
      const dec = decision(S.status);
      const of = dec && dec.cause === 'partial' ? (dec.kind === 'test' ? 'after the test run' : 'on this board') : 'of the stopped run';
      return { kind, padIds: pads, n: nd, pads: pads.length, title: 'Dispense the ' + IL.plural(pads.length, 'pad') + ' left?',
        test: 'Only the ' + IL.plural(pads.length, 'pad') + ' left ' + of, scope: 'The ' + IL.plural(pads.length, 'pad') + ' left ' + of + '.' };
    }
    const lim = kind === 'test' ? testLimit() : null;
    if (lim && lim.n) {
      const pads = new Set(p.dots.slice(0, lim.n).map((d) => d.padId)).size;
      return { kind: 'test', maxDots: lim.n, n: lim.n, pads, title: 'Dispense the first ' + lim.n + ' of ' + N + ' dots?',
        test: 'TEST: only the first ' + limitText(lim), scope: 'Test: the first ' + limitText(lim) + '.' };
    }
    // pads the engine skips (Refuse flagged pads off) are not dispensed: the confirm names them (updateDialog)
    const nPads = p.pads.filter((x) => !x.blocked).length;
    return { kind: kind === 'all' ? 'all' : 'full', n: N, pads: nPads, title: 'Dispense ' + IL.plural(N, 'dot') + ' on ' + IL.plural(nPads, 'pad') + '?',
      test: null, scope: kind === 'all' ? 'The whole board again, all ' + N + ' dots (wipe every pad first).' : 'Full board, all ' + N + ' dots.' };
  }
  function tick(id, text, sub) {
    const input = el('input', { type: 'checkbox', id });
    const who = el('span', { class: 'who' });
    const lab = el('label', { class: 'tick', for: id }, input, el('div', {}, el('div', { class: 'tick__t', text }), sub ? el('div', { class: 'tick__d', text: sub }) : null), who);
    return { lab, input, who };
  }
  function openConfirm(kind, opts) {
    const p = plan();
    const s = S.status;
    if (!p || !s) return;
    if (R.dlg) R.dlg.close('replace');
    const mode = confirmMode(kind, opts);
    const prof = (s.dispense && s.dispense.profile) || p.profile || {};
    const psi = IL.fmtPsi(prof.pressurePsi);
    const gauge = tick('run-tick-gauge', 'The gauge reads ' + psi + ' psi');
    gauge.psi = prof.pressurePsi;   // the psi the label shows: a tick records this one (updateDialog keeps both current)
    const paste = tick('run-tick-paste', 'Paste is within its working life');
    const hands = tick('run-tick-hands', 'Hands and tools are clear of the head');
    // the board already has paste on pads this run lays (the server keeps it, every tab sees it): only after an explicit
    // "I wiped this board", which the run tells the engine (POST /api/run {wiped: true}); finishing the pads left needs none
    const covered = new Set(mode.kind === 'test' ? p.dots.slice(0, mode.maxDots || 0).map((d) => d.padId) : p.pads.map((x) => x.padId));
    const pastedHere = mode.kind === 'rest' ? [] : padsDone(p).filter((id) => covered.has(id));
    const wipe = pastedHere.length ? tick('run-tick-wiped', 'I wiped this board: no paste on its ' + IL.plural(pastedHere.length, 'pad') + ' now',
      'This board (board ' + boardNumber() + ') already has paste on ' + IL.plural(pastedHere.length, 'pad') + ' this run lays: wipe them first, or press Cancel and finish the pads left.') : null;
    const reg = IL.attest.valid('regulator', s);
    gauge.input.checked = !!reg;
    paste.input.checked = !!IL.attest.valid('paste-life', s);
    hands.input.checked = false;
    gauge.input.addEventListener('change', () => {
      if (gauge.input.checked) IL.attest.set('regulator', gauge.psi); else IL.attest.clear('regulator');
      updateDialog(S.status);
    });
    paste.input.addEventListener('change', () => { if (paste.input.checked) IL.attest.set('paste-life', true); else IL.attest.clear('paste-life'); updateDialog(S.status); });
    hands.input.addEventListener('change', () => updateDialog(S.status));
    if (wipe) wipe.input.addEventListener('change', () => updateDialog(S.status));
    const pon = el('button', { type: 'button', class: 'btn', id: 'run-confirm-pressure', title: 'Pressure on to read the gauge (press twice unless the needle is over the waste)' }, IL.icon('i-gauge'), 'Pressure on to adjust');
    pon.addEventListener('click', (ev) => IL.runFix('pressure-on', pon, null, ev));
    const held = el('div', { class: 'regulator__held', id: 'run-confirm-held', hidden: true });
    const sensed = el('div', { class: 'grid2', id: 'run-sensed' });
    const testLine = el('div', { class: 'testline', id: 'run-test-line', hidden: !mode.test, role: 'note' }, IL.glyph('need', 'sm'), el('span', { text: mode.test || '' }));
    const blockBox = el('div', { id: 'run-confirm-block' });
    const refusals = el('div', { id: 'run-confirm-refusals' });
    // the run homes first and checks Z after the home by itself (AUTO_REGISTER_DESIGN.md §8.3): said before it starts
    const homeBox = el('div', { class: 'notebox notebox--info run-confirm-home', id: 'run-confirm-home', role: 'note', hidden: true });
    const body = [
      homeBox,
      el('div', { class: 'regulator' }, IL.icon('i-gauge'),
        el('div', {}, el('div', { class: 'regulator__big', id: 'run-confirm-psi', text: 'Set the regulator to ' + psi + ' psi (profile ' + (prof.name || '') + ')' }),
          el('div', { class: 'regulator__d', text: 'There is no pressure sensor: read the gauge. 16 psi starved the screw, 20 psi drooled.' })),
        el('div', { class: 'regulator__row' }, gauge.lab, pon), held),
      testLine, blockBox,
      IL.sectionK('The machine checked'), sensed,
      IL.sectionK('You confirm (nothing senses these)'),
      wipe ? el('div', { class: 'run-confirm-wiped', id: 'run-confirm-wiped' }, wipe.lab) : null,
      el('div', { class: 'grid2' }, paste.lab, hands.lab),
      refusals,
    ];
    const sum = el('div', { class: 'dialog__sum', id: 'run-confirm-sum' });
    const cancel = el('button', { type: 'button', class: 'btn btn--lg', id: 'btn-run-cancel' }, 'Cancel');
    const go = el('button', { type: 'button', class: 'btn btn--primary btn--lg', id: 'btn-run-go' });
    const first = [gauge.input, paste.input].concat(wipe ? [wipe.input] : []).concat([hands.input]).find((i) => !i.checked) || hands.input;
    const h = IL.dialog.open({ id: 'dlg-run', eyebrow: 'Run board · ' + p.boardName + ' · plan ' + shortId(p), title: mode.title, keys: true,
      body, foot: [sum, cancel, go], initialFocus: first, onClose: () => { if (R.dlg === h) R.dlg = null; } });
    h.mode = mode;
    h.n = { gauge, paste, hands, wipe, pon, held, sensed, sum, go, refusals, blockBox, homeBox };
    h.runId = s.run ? s.run.runId : null;   // the last task this tab knew of when the confirm opened
    R.dlg = h;
    cancel.addEventListener('click', () => h.close('cancel'));
    go.addEventListener('click', () => startRun(h));
    updateDialog(s);
  }
  async function startRun(h) {
    const p = plan();
    if (!p || h.closed) return;
    const m = h.mode;
    const body = { planId: p.planId };
    if (m.maxDots) body.maxDots = m.maxDots;
    if (m.padIds) body.padIds = m.padIds;
    if (h.n.wipe && h.n.wipe.input.checked) body.wiped = true;   // the server forgets the board's paste before the run
    h.starting = true;   // this run's own start closes the confirm without a note
    const res = await IL.act(h.n.go, async () => {
      const r = await IL.api('POST', '/api/run', body);
      // what this run is (the Last board tile, Records and the Result step say "test", "the pads left")
      if (r && r.planId) { R.kinds[r.planId] = m.kind; ssSet('il.run.kinds', R.kinds); }
      return r;
    }, {
      refusalIn: h.n.refusals, what: 'Run',
      ok: (r) => 'Run started: ' + IL.plural((r && r.dots) || m.n, 'dot') + (m.kind === 'test' ? ' (test)' : m.kind === 'rest' ? ' (the pads left)' : ''),
    });
    if (!res) {
      h.starting = false;
      if (!h.closed) h.n.refusals.scrollIntoView({ block: 'nearest' });
      return;
    }
    h.close('run');
    R.test = false;
    R.maxDots = '';
    R.resultRunId = null;
    R.refused = null;
    R.decidedAt = Date.now();
    ssSet('il.run.decidedAt', R.decidedAt);
    goAuto();
  }
  function updateDialog(s) {
    const h = R.dlg;
    if (!h || h.closed || !s) return;
    const p = plan();
    if (!p) { h.close('plan'); return; }
    const n = h.n, m = h.mode;
    const r = IL.ready();
    const prof = (s.dispense && s.dispense.profile) || {};
    const psi = IL.fmtPsi(prof.pressurePsi);
    // ticks: remembered attestations show "you, 13:04"
    if (prof.pressurePsi !== n.gauge.psi) {
      n.gauge.psi = prof.pressurePsi;
      IL.setText(n.gauge.lab.querySelector('.tick__t'), 'The gauge reads ' + psi + ' psi');
    }
    const reg = IL.attest.valid('regulator', s);
    if (n.gauge.input.checked && !reg) n.gauge.input.checked = false;  // expired or psi changed while open
    IL.setText(n.gauge.who, reg ? 'you, ' + IL.fmtTime(reg.atMs) : '');
    const pl = IL.attest.valid('paste-life', s);
    IL.setText(n.paste.who, pl ? 'you, ' + IL.fmtTime(pl.atMs) : 'every 8 h');
    IL.setText(n.hands.who, 'every run');
    if (n.wipe) IL.setText(n.wipe.who, 'this run');
    for (const t of [n.gauge, n.paste, n.hands, n.wipe].filter(Boolean)) t.lab.classList.toggle('on', t.input.checked);
    IL.setText($('run-confirm-psi'), 'Set the regulator to ' + psi + ' psi (profile ' + (prof.name || '') + ')');
    // a pressure hold for reading the gauge must be off again before the run
    const pr = s.pressure || {};
    const holding = pr.state && pr.state !== 'off';
    IL.keyed(n.held, holding ? pr.state : '', () => (holding ? [IL.glyph(pr.state === 'vent_failed' ? 'block' : 'live', 'sm'),
      el('span', { text: pr.state === 'vent_failed' ? 'Pressure is NOT off: press Pressure off.' : 'Pressure is on: read the gauge, then switch it off before the run.' }),
      el('span', { class: 'spacer' }), fixBtn({ fix: 'pressure-off', label: 'Pressure off', primary: true }, 'btn--sm')] : null));
    IL.show(n.held, !!holding);
    IL.gate(n.pon, holding ? 'Pressure is on' : IL.lockReason(s, { connected: true, homed: true, engine: true }));
    // what the machine checked
    const it = (k) => r.items.find((x) => x.key === k);
    const row = (s.dispense && s.dispense.purgeRow) || {};
    const sf = it('surface'), an = it('angle'), cl = it('clean');
    const surf = s.dispense && s.dispense.surfaces && s.dispense.surfaces[s.dispense.boardSurfaceName || 'board'];
    const cards = [];
    cards.push([sf.state, sf.state === 'ok' ? 'Board surface tied' : 'Board surface: ' + sf.value, sf.state === 'ok' ? IL.plural((surf && surf.points || []).length, 'point') + ' · plan ' + shortId(p) : sf.detail]);
    cards.push([an.state, an.state === 'off' ? 'Whole turns per dot' : 'Start angle ' + IL.fmtDeg(prof.startAngleDeg, 1), an.state === 'ok' ? 'confirmed ' + IL.fmtTime(prof.startAngleSetAtMs - S.skewMs) : an.detail]);
    if (row.defined) {
      // the spots this plan lays (I9: 8 to start, 5 for each further dot size)
      const need = IL.purgeNeed(row, p);
      const a = row.usedSpots + 1, b = row.usedSpots + need;
      const after = row.spotsLeft - need;
      cards.push([row.spotsLeft >= need ? 'ok' : 'need', 'Purge spots ' + a + ' to ' + b,
        after >= 0 ? IL.plural(after, 'spot') + ' left after' + (after < need ? ': wipe before board ' + (boardNumber() + 1) : '') : 'only ' + row.spotsLeft + ' left, ' + need + ' needed: wipe and reset first']);
    } else cards.push(['block', 'Purge row not set', 'Setup › Purge row']);
    // Refuse flagged pads off: the pads that cannot be dispensed are skipped, by package (PROFILE_CALIBRATION_DESIGN §21)
    const skipped = (p.pads || []).filter((x) => x.blocked);
    if (skipped.length) cards.push(['info', 'Skips ' + IL.plural(skipped.length, 'pad') + ' that cannot be dispensed', skippedText(skipped)]);
    const on = cleanEnabled(s);
    const tour = on ? '≈ ' + IL.fmtClock(tourSeconds(m.pads)) : '';
    const cc = S.cleanCheck && IL.planMatches(S.cleanCheck.planId || p.planId) ? S.cleanCheck : null;
    // the last check of this plan comes first, also with the check off (the last gate before the run says it, §8.1.5)
    const offNote = 'the check before each board is off: the run dispenses on them as they are';
    if (cc && !cc.ok && IL.cleanPositionWrong(cc)) {
      cards.push(['need', 'Pad check ' + IL.fmtTime(cc.checkedAtMs) + ': the board position looked wrong', on ? 'no pad read clean: check the position; the run checks again first, ' + tour : 'no pad read clean (solder mask under every box); ' + offNote]);
    } else if (cc && !cc.ok) {
      cards.push(['need', IL.plural((cc.dirtyPadIds || IL.dirtyPads(cc)).length, 'pad') + ' had paste at ' + IL.fmtTime(cc.checkedAtMs),
        on ? (m.kind === 'rest' ? 'the pads left are' : 'all pads') + ' checked again first, ' + tour : offNote]);
    } else if (!on) cards.push(['off', 'Pad check off', IL.isSim(s) && !(s.server && s.server.simulatorConfirmed) ? 'simulator: the camera is a static picture' : 'not checked before the board (Settings)']);
    else if (cc && cc.ok) cards.push(['ok', 'All ' + cc.targets + ' pads clean at ' + IL.fmtTime(cc.checkedAtMs), 'checked again first, ' + tour]);
    else cards.push(['need', 'Pads not checked yet', 'the run checks every pad first, ' + tour]);
    // offline: nothing sensed is known now (never a stale green tick, §15)
    if (S.online === false) for (const c of cards) { c[0] = 'off'; c[2] = 'unknown since ' + IL.fmtTimeS(S.lastOkAt); }
    IL.keyed(n.sensed, JSON.stringify(cards), () => cards.map(([st, t, d]) => el('div', { class: 'tick' }, IL.glyph(st === 'checking' || st === 'wait' ? 'off' : st),
      el('div', {}, el('div', { class: 'tick__t', text: t }), el('div', { class: 'tick__d', text: d })))));
    // blocked now (another task, machine down, plan stale ...): say why, keep the dialog
    const ticksLeft = [n.gauge.input, n.paste.input, n.hands.input].concat(n.wipe ? [n.wipe.input] : []).filter((i) => !i.checked).length;
    let why = S.online === false ? IL.lockReason(s) : S.planPending ? 'Re-reading the plan (checking…)' : !r.canRun ? r.runReason : null;
    if (!why && holding) why = 'Pressure is held: switch it off first';
    const showWhy = why && (!holding || S.online === false) && !S.planPending;
    IL.keyed(n.blockBox, showWhy ? why : '', () => (showWhy ? el('div', { class: 'refusal', role: 'alert' }, el('div', { class: 'refusal__t', text: 'Cannot run now: ' + why })) : null));
    const est = estimate(s, m.n, m.pads);
    const hf = runHomeFirst(s);
    IL.keyed(n.homeBox, hf ? hf.text : '', () => (hf ? [IL.glyph('info', 'sm'), el('span', { text: IL.nb(hf.text) })] : null));
    IL.show(n.homeBox, !!hf);
    const plan_ = (hf ? (hf.home ? 'Homes first, then ' + (hf.z ? 'checks Z, then ' : '') : 'Checks Z first, then ') : '')
      + (cleanEnabled(s) ? (hf ? 'pad check ' : 'Pad check ') + tour + ', then pressure on, ' : (hf ? 'pressure on, ' : 'Pressure on, ')) + ((p.profile && p.profile.purgeDots) || 0) + ' purge dots, the board, vent.';
    IL.keyed(n.sum, m.scope + plan_, () => [el('b', { text: m.scope }), ' ' + plan_]);
    setBtnLabel(n.go, ticksLeft ? ['Tick ' + ticksLeft + ' more to run'] : [IL.icon('i-play'), 'Run ' + IL.plural(m.n, 'dot') + (est ? ' · ≈ ' + IL.fmtClock(est.total) : '')]);
    IL.gate(n.go, why || (ticksLeft ? 'Tick ' + ticksLeft + ' more to run' : null));
    IL.setClass(n.go, 'btn btn--lg ' + (m.kind === 'test' ? 'btn--test' : 'btn--primary'));
  }

  /** "WSON-8: U3.1, U3.2 … (9) · SW_SPST: J1.1 … (4)": the skipped pads by package, a few names each. */
  function skippedText(pads) {
    const by = new Map();
    for (const x of pads) {
      const k = x.packageId || x.typeKey || 'no pad type';
      if (!by.has(k)) by.set(k, []);
      by.get(k).push(x.padId);
    }
    return Array.from(by.entries()).map(([k, ids]) => k + ': ' + ids.slice(0, 3).join(', ') + (ids.length > 3 ? ' … (' + ids.length + ')' : '')).join(' · ');
  }

  /**
   * Does the run home first and check Z (status.machine.home / zCheck)? {home, z, text} for #run-confirm-home, or null.
   * After a home every N1 height predates it: a run that homes first also checks Z when it has one to touch.
   */
  function runHomeFirst(s) {
    const m = (s && s.machine) || {};
    const h = m.home || null;
    const zc = m.zCheck || null;
    const d = (s && s.dispense) || {};
    const zData = !!(d.needle && d.needle.anchor) || Object.keys(d.surfaces || {}).length > 0;
    const zS = zc && isNum(zc.estimateS) ? zc.estimateS : 20;
    const zN = zc && zc.touchesAfterHome > 1 ? zc.touchesAfterHome : 1;
    if (h && h.needed) {
      return { home: true, z: zData, text: 'Homes first (' + IL.homeWhy(h) + '), ≈ ' + IL.fmtSecs(h.estimateS) + ': the head goes to its home corner and the top camera finds the homing fiducial. '
        + 'The pressure stays off until homing is done.' + (zData ? ' Then ' + IL.zTouches(zN) + (zN > 1 ? ' check' : ' checks') + ' Z (≈ ' + IL.fmtSecs(zS * zN) + ').' : '') };
    }
    if (zc && zc.needed && zc.reference) {
      return { home: false, z: true, text: 'Checks Z first: ' + IL.zTouches(zc.touches) + (zc.touches > 1 ? ', the first' : '') + ' on the ' + zc.reference.name
        + ' (≈ ' + IL.fmtSecs(zS * Math.max(1, zc.touches || 0)) + '), because the N1 heights predate the home at '
        + IL.fmtTime((zc.homeAtMs || Date.now() + S.skewMs) - S.skewMs) + '.' };
    }
    return null;
  }

  // ================================================================================== panel host
  function buildPanel(parts, s, step) {
    R.built = step;
    IL.panel.setView(step);
    if (step === 'recovery') {
      IL.views.recovery.render(parts, s, true);
      return;
    }
    renderSteps(parts.top, s, step);
    if (step === 'board') {
      if (!R.boards && !R.boardsLoading) loadBoards();
      parts.body.append(buildBoard(plan()));
      parts.foot.append(buildBoardFoot());
      syncSelect(true);
    } else if (step === 'probe') {
      if (!S.config) IL.loadConfig();
      parts.body.append(buildProbe());
      parts.foot.append(buildProbeFoot());
    } else if (step === 'checks') {
      parts.body.append(buildChecks());
      parts.foot.append(buildChecksFoot());
    } else if (step === 'run') {
      if (!S.config) IL.loadConfig();
      parts.body.append(buildRunStep());
    } else if (step === 'result') {
      parts.body.append(buildResult());
      parts.foot.append(buildResultFoot());
    }
  }
  function renderPanelStep(parts, s, step) {
    if (step === 'recovery') { IL.views.recovery.render(parts, s, false); return; }
    renderSteps(parts.top, s, step);
    if (step === 'board') renderBoardStep(s, parts);
    else if (step === 'probe') renderProbe(s);
    else if (step === 'checks') renderChecks(s);
    else if (step === 'run') renderRunStep(s);
    else if (step === 'result') renderResult(s);
  }

  // ================================================================================== events
  IL.on('status', (s, prev) => {
    IL.safe(() => trackPhases(s), 'run phases');
    IL.safe(() => trackDone(s), 'run done');
    IL.safe(() => trackReg(s), 'register board');
    // a task started while the run confirm is open (another tab or client, or ours): its ticks, "hands clear" above
    // all, and its dot count were for the board before that task, so the confirm closes. A new run id also counts:
    // a short task can start and end between two polls this tab saw (offline, a throttled hidden tab)
    const newRun = !!R.dlg && !!s.run && !!s.run.runId && s.run.runId !== R.dlg.runId;
    if (R.dlg && (newRun || (IL.taskRunning(s) && prev && !IL.taskRunning(prev)))) {
      if (!R.dlg.starting) IL.toast('Run confirm closed: ' + (newRun && s.run.label === 'board' ? 'a board run' : 'a task') + ' started meanwhile.', 'need');
      R.dlg.close('task');
    }
    if (R.dlg) IL.safe(() => updateDialog(s), 'run confirm');
  });
  IL.on('plan', (p, prev) => {
    // before the render that follows: the done dots of this plan's board (the server's paste), never the last plan's
    IL.safe(() => trackDone(S.status), 'run done');
    if (p && (!prev || prev.planId !== p.planId)) {
      formFromPlan(p);
      R.showAllDirty = false;
      if (R.bv) R.bv.setPlan(p, { origin: originOf(p) });
    }
    if (!p && R.bv) R.bv.setPlan(null);
    if (p && p.source) lsSet('il.run.source', JSON.stringify(p.source));
  });
  IL.on('cleancheck', () => { R.showAllDirty = false; });
  // STOP makes an open run confirm moot: close it (Recovery takes over)
  IL.on('stop', () => { if (R.dlg) R.dlg.close('stop'); });
  IL.on('online', () => { if (R.dlg) IL.safe(() => updateDialog(S.status), 'run confirm'); });
  IL.on('taskend', (run) => {
    // Register board ended: the new plan (the same physical board), and its result card on the Register step
    if (run && run.label === 'board registration' && IL.planMatches(run.planId)) {
      R.regLoaded[run.runId] = true;
      if (run.detail) R.regDetail = { runId: run.runId, detail: run.detail };
      adoptRegPlan(run);
      setRegResult(run.runId);
      R.byHand = false;
      if (IL.active() === 'run' && R.section && R.section !== 'probe') goStep('probe');
      IL.render();
      return;
    }
    // a clean-pad check refused at its start (the pressure hold switched on, a STOP) looked at no pad: the card says why
    if (run && run.label === 'clean-pad check' && IL.planMatches(run.planId)) {
      R.ccRefused = run.status === 'REFUSED' ? run : null;
      IL.render();
      return;
    }
    if (!run || run.label !== 'board' || !IL.planMatches(run.planId)) return;
    if (run.status === 'OK') R.resultRunId = run.runId;
    else if (run.status === 'REFUSED') { R.refused = run; R.resultRunId = null; }
    if (R.section === 'run' || R.section === 'result') goAuto();
    IL.render();
  });

  // ================================================================================== the workspace
  IL.workspace('run', {
    ownsRecovery: true,
    defaultView: 'checks',
    mount(root) {
      R.root = root;
      if (!R.form.sel) R.form.sel = lsGet('il.run.sel') || null;
      buildMain(root);
      if (S.plan) formFromPlan(S.plan);
      // board position fields remembered for a file board chosen before
      const c = selectedBoard();
      if (!S.plan && c && c.kind === 'file') {
        const rem = remembered(c.file);
        if (rem) Object.assign(R.form, { x: IL.inputNum(rem.x), y: IL.inputNum(rem.y), z: IL.inputNum(rem.z), rot: IL.inputNum(rem.rotation || 0) });
      }
    },
    show(route) {
      R.section = route && STEPS.includes(route.section) ? route.section : null;
      if (R.bv) setTimeout(() => R.bv.relayout(), 0);
    },
    hide() {
      // the Result step is shown right after a board; coming back to Run shows the step that matters now
      R.resultRunId = null;
    },
    route(route) {
      const sec = route && STEPS.includes(route.section) ? route.section : null;
      if (sec !== R.section) { R.section = sec; }
    },
    render(s) { renderMain(s); },
    panel(parts, s, first) {
      const step = currentStep(s);
      if (!first && step !== R.built) { IL.panel.reset(); return; }
      if (first) buildPanel(parts, s, step);
      renderPanelStep(parts, s, step);
    },
    nav(s) {
      if (!s || S.online === false || IL.taskRunning(s) || IL.recoveryActive() || !S.plan) return null;
      const dec = partialDecision(s);
      if (dec) {
        const left = padsLeft(S.plan).length;
        return { sub: 'Finish ' + IL.plural(left, 'pad'), kind: 'need', title: dec.run.dotsDone + ' of ' + dec.run.dotsPlanned + ' dots on this board: finish the ' + IL.plural(left, 'pad') + ' left' };
      }
      const r = IL.ready();
      if (r.verdict.done) return { sub: 'Board ' + boardNumber() + ' done', kind: 'ok', title: r.verdict.title };
      if (r.verdict.state === 'ok') return { sub: 'Ready: board ' + boardNumber(), kind: 'ok', title: 'Ready: ' + (S.plan.boardName || 'board') };
      return null;
    },
  });
})();
