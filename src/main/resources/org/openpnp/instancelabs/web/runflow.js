/* runflow.js: Run › Start, the approved screen (docs/ui-flows/board-flow.md C1-C3; mockup `run-start`, the user's OK of
   11-02), over today's run abilities. run.js shows it as its step `start` at bare #run while nothing needs today's
   pages (no board run running, stopped or with a result to show, no registration but Start's own, no recovery); today's
   pages stay at #run/board|probe|checks|run|result (Step by step).
   - Main column: the design (a card per board with its picture; a click plans it where it sits: the job's place, its
     plan, the seat this browser planned, else its last registration), Boards this run (one board: the tile is BR-39)
     and Load side 1.
   - Panel: the three answers only a person can give (the tip on the dispenser, the paste within its life, the gauge:
     today's 8 h ticks, IL.attest), what needs you first (the readiness needs Start does not settle itself, as today's
     cards with their fixes), what it then does by itself, and one Start. Start also means hands and tools are clear
     (the user, 11-02): it plans when the plan is not the chosen board's, registers the board (finds it, its height),
     then runs the plan the registration made. The page chains them (BR-86 asks for one engine job), so it says to stay
     until the paste starts. A refusal stops the chain where it is, its words in the panel.
   - Live (a board runs), Needs you (a board stopped or part done) and Done (after a board run): the flow's rail, "You
     can leave now" or "Needs you", run.js's own board picture moved into this frame (its chip, recipe and dots), four
     tiles; the panel has STOP and the steps (Live), one card with one primary action and the other choices (Needs
     you: today's confirm for the pads left), or the record, Next board and another run (Done). The steps the machine
     cannot do yet (check the paste, place, check the parts) say "not built yet"; the oven is by hand.
   Styles: runflow.css. */
'use strict';
(function () {
  const { el, $, S } = IL;
  const isNum = IL.isNum;
  const R = IL.run;
  /** Readiness items Start settles itself: it plans and it registers (the probe ties the surface). */
  const BY_START = new Set(['plan', 'surface']);
  /** The answers only a person can give: [word, attestation, label]. The gauge is readiness item 4 (regulator). */
  const TICKS = [
    ['the tip', 'tip-on', (s) => 'The ' + tipName(s) + ' is on the dispenser'],
    ['the paste', 'paste-life', () => 'Same paste barrel, within its working life'],
    ['the gauge', 'regulator', (s) => 'The gauge reads ' + IL.fmtPsi(psiOf(s)) + ' psi'],
  ];
  const F = { n: {}, chain: null, wiped: false, thumbs: new Map(), loading: new Set() };

  // ================================================================================== data
  const disp = (s) => (s && s.dispense) || {};
  const tipOf = (s) => disp(s).tip || {};
  const tipName = (s) => tipOf(s).name || 'mounted tip';
  const psiOf = (s) => (disp(s).profile || {}).pressurePsi;
  const ticked = (s, k) => !!IL.attest.valid(k, s);
  const words = (a) => (a.length > 1 ? a.slice(0, -1).join(', ') + ' and ' + a[a.length - 1] : a.join(''));
  const minutes = (sec) => '≈ ' + Math.max(1, Math.round(sec / 60)) + ' min';
  function entries() {
    const b = R.boards;
    if (!b) return [];
    return [].concat((b.job || []).map((x) => ({ key: 'job:' + x.boardLocationId, kind: 'job', name: x.name, x })),
      (b.files || []).filter((x) => x.file).map((x) => ({ key: 'file:' + x.file, kind: 'file', name: String(x.name || x.file).replace(/\.board\.xml$/, ''), x })));
  }
  const chosen = () => entries().find((e) => e.key === R.form.sel) || null;
  /** Where the board sits: the job's place, its plan, the seat this browser planned it at, else its last registration. */
  function seatOf(e) {
    if (!e) return null;
    const x = e.x;
    if (e.kind === 'job') return { x: x.x, y: x.y, z: x.z, rotation: x.rotation || 0, from: 'the job open in OpenPnP' };
    const src = S.plan && S.plan.source;
    if (src && src.source === 'file' && src.file === x.file) return { x: src.x, y: src.y, z: src.z, rotation: src.rotation || 0, from: 'its plan' };
    const rem = R.flow.remembered(x.file);
    if (rem && isNum(rem.x)) return { x: rem.x, y: rem.y, z: rem.z, rotation: rem.rotation || 0, from: 'where this browser planned it' };
    const n = x.registration && x.registration.nominal;
    if (n && isNum(n.x) && isNum(n.z)) return { x: n.x, y: n.y, z: n.z, rotation: n.rotation || 0, from: 'its last registration' };
    return null;
  }
  function planBody(e, seat) {
    if (e.kind === 'job') return { source: 'job', boardLocationId: e.x.boardLocationId };
    return { source: 'file', file: e.x.file, x: Number(seat.x), y: Number(seat.y), z: Number(seat.z), rotation: Number(seat.rotation) || 0 };
  }
  /** The plan shown is the chosen board's, current (not stale, not forgotten). */
  function planOf(e) {
    const p = S.plan, src = p && p.source;
    if (!e || !src) return null;
    const same = e.kind === 'job' ? src.source === 'job' && String(src.boardLocationId) === String(e.x.boardLocationId)
      : src.source === 'file' && src.file === e.x.file;
    return same ? p : null;
  }
  const planCurrent = (p) => { const it = IL.ready().items.find((x) => x.key === 'plan'); return !!p && !S.planForgotten && (!it || !!it.ready); };
  /** The pads of the board on this seat that already have paste (the server's): the run lays them again. */
  const pastedPads = (p) => (p ? R.flow.padsDone(p).length : 0);
  /** Readiness needs Start does not settle itself; the gauge is a tick and the pad check the run's own, as today. */
  function needs() {
    const r = IL.ready();
    return r.extras.concat(r.items).filter((x) => !x.ready && x.state !== 'wait' && !BY_START.has(x.key) && x.key !== 'regulator'
      && !(x.key === 'clean' && x.state !== 'block'));
  }
  function startReason(s) {
    const lock = IL.lockReason(s, { connected: true, homes: true, engine: true });
    if (lock) return lock;
    const e = chosen();
    if (!e) return 'Choose a design first.';
    if (!seatOf(e)) return 'Where does this board sit? Plan it once in Step by step › Board.';
    const miss = TICKS.filter((t) => !ticked(s, t[1])).map((t) => t[0]);
    if (miss.length) return 'Answer first: ' + words(miss) + '.';
    const ns = needs();
    if (ns.length) return 'First what needs you: ' + ns.map((x) => (x.short || x.title).toLowerCase()).join(', ') + '.';
    const p = planOf(e);
    // today's Register gate (the needle Z frame, the fiducials, the camera, the probe's reach) once its plan is shown
    const rw = p && planCurrent(p) ? R.flow.registerWhy(s, R.flow.probeOnlyPlan(p)) : null;
    if (rw) return rw.text.replace(/\.?$/, '.');
    if (pastedPads(p) && !F.wiped) {
      return (R.flow.padsLeft(p).length ? 'This board has paste on ' + IL.plural(pastedPads(p), 'pad') + ': press "It is a new board", tick "I wiped it", or finish the pads left (Step by step).'
        : 'This board is done: put a new one in and press "It is a new board", or wipe it and tick "I wiped it".');
    }
    return null;
  }

  // ================================================================================== actions
  /** Resolves with pred's first truthy answer on a status (now or a later one); rejects after ms. */
  function until(pred, ms, what) {
    return new Promise((resolve, reject) => {
      let off = null;
      const timer = setTimeout(() => { off(); reject(new Error(what + ': no answer after ' + IL.fmtSecs(ms / 1000) + '. See Step by step.')); }, ms);
      const test = () => {
        let v;
        try { v = pred(S.status); } catch (e) { off(); clearTimeout(timer); reject(e); return; }
        if (v) { off(); clearTimeout(timer); resolve(v); }
      };
      off = IL.on('status', test);
      test();
    });
  }
  async function choose(e, btn) {
    if (!e || F.chain || (e.key === R.form.sel && planOf(e))) return;
    const lock = IL.lockReason(S.status);
    if (lock) { IL.toast(lock, 'need'); return; }
    R.flow.selectBoard(e.key);
    const seat = seatOf(e);
    if (!seat) return;   // Load side 1 says where to set it
    await IL.act(btn, () => R.flow.plan(planBody(e, seat)), { refusalIn: F.n.refusals, what: 'Plan', ok: null });
  }
  /** One Start: plan when needed, find the board (Register board), then the run of the plan it made. */
  async function start(btn) {
    const e = chosen();
    if (!e || F.chain) return;
    F.chain = { step: 'plan' };
    IL.render();
    // the answers and the checks again before each step: an 8 h answer can lapse, another tab can untick it, a new plan
    // can block pads; the run is posted only for the plan this registration made
    const gate = () => { const why = startReason(S.status); if (why) throw new Error(why); };
    await IL.act(btn, async () => {
      if (!planCurrent(planOf(e))) {
        const seat = seatOf(e);
        if (!seat) throw new Error('Where does this board sit? Plan it once in Step by step › Board.');
        await R.flow.plan(planBody(e, seat));
      }
      gate();
      const p = S.plan;
      const before = (S.status && S.status.run && S.status.run.runId) || null;
      Object.assign(F.chain, { step: 'find', findAt: Date.now() });
      IL.render();
      const reg = await R.flow.register(R.flow.probeOnlyPlan(p) ? 'always' : 'auto');
      const waitS = (isNum(reg && reg.estimatedSeconds) ? reg.estimatedSeconds : 120) * 3 + 120;
      const end = await until((st) => {
        const r = st && st.run;
        return r && r.label === 'board registration' && r.runId && r.runId !== before && IL.basePlanId(r.planId) === p.planId && !r.running ? r : null;
      }, waitS * 1000, 'Find the board');
      if (end.status !== 'OK') throw new Error('Find the board ended (' + String(end.status).toLowerCase() + '): ' + String(end.message || 'no message').replace(/\.?$/, '.') + ' Nothing was pasted.');
      if (!end.nextPlanId) throw new Error('Find the board did not plan the board again, so nothing was pasted: see Step by step › Register.');
      F.chain.foundS = end.elapsedS;
      await until(() => S.plan && S.plan.planId === end.nextPlanId, 30000, 'The plan of the board found');
      gate();
      F.chain.step = 'paste';
      IL.render();
      const body = { planId: end.nextPlanId };
      if (F.wiped) body.wiped = true;   // ticked "I wiped it": the server forgets this seat's paste first
      return R.flow.run(body, 'full');
    }, { refusalIn: F.n.refusals, what: 'Start', ok: (r) => (r ? 'Pasting started: ' + IL.plural(r.dots, 'dot') + '. You can leave now.' : null) });
    F.chain = null;
    F.wiped = false;
    IL.render();
  }

  // ================================================================================== main column
  function pic(artId, w, h) {
    const box = el('span', { class: 'rf-img' });
    const url = artId ? F.thumbs.get(artId + '|' + w) : null;
    if (url) box.append(el('img', { src: url, alt: '' }));
    else if (!artId) box.append(el('span', { class: 'design__none', text: 'No drawing' }));
    else {
      box.append(el('span', { class: 'design__none', text: '' }));
      const key = artId + '|' + w;
      if (!F.loading.has(key)) {
        F.loading.add(key);
        IL.boardart.get(artId).then((a) => {
          const u = a ? IL.boardart.thumbUrl(a, { w, h, id: artId }) : null;
          if (u) { F.thumbs.set(key, u); IL.render(); }
        }).catch(() => { /* the card keeps its frame */ });
      }
    }
    return box;
  }
  function mount(root) {
    const n = F.n;
    n.refusals = el('div', { id: 'rs-refusals', 'data-refusals': '' });
    n.eyebrow = el('div', { class: 'eyebrow', text: 'Make a board' });
    n.title = el('h1', { class: 'tf-h1', id: 'rs-title' });
    n.meta = el('div', { class: 'meta', id: 'rs-meta' });
    n.designs = el('div', { class: 'designs rf-designs', id: 'rs-designs' });
    n.one = el('button', { type: 'button', id: 'rs-one', 'aria-pressed': 'true', text: 'One board' });
    n.tile = el('button', { type: 'button', id: 'rs-tile', 'aria-pressed': 'false', disabled: true, title: 'Not built yet (BR-39)', text: 'Boards on the tile' });
    n.loadT = el('h2', { class: 'card__title' });
    n.loadPic = el('div', { class: 'rf-pic', id: 'rs-load-pic' });
    n.loadCap = el('div', { class: 'rf-cap', id: 'rs-load-cap' });
    n.loadKv = el('dl', { class: 'kv', id: 'rs-load-kv' });
    const card = (id, title, sub, ...body) => el('section', { class: 'card', id },
      el('div', { class: 'card__head' }, el('div', {}, typeof title === 'string' ? el('h2', { class: 'card__title', text: title }) : title, sub ? el('div', { class: 'card__sub', text: sub }) : null)),
      el('div', { class: 'card__body' }, body));
    n.root = el('div', { class: 'tf-stack rf-start', id: 'run-start', hidden: true },
      el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' }, n.eyebrow, n.title, n.meta)),
      card('rs-designs-card', 'Design', null, n.designs),
      el('div', { class: 'tf-row rf-row' },
        card('rs-boards-card', 'Boards this run', 'Chosen per run: first tests or production.',
          el('div', { class: 'seg seg--block' }, n.one, n.tile),
          el('div', { class: 'rf-cap', id: 'rs-boards-cap', text: 'One board at a time. Boards on the tile: not built yet (BR-39).' })),
        card('rs-load-card', n.loadT, 'The side to paste first.', n.loadPic, n.loadCap, n.loadKv)));
    n.vEyebrow = el('div', { class: 'eyebrow', id: 'rv-eyebrow' });
    n.vTitle = el('h1', { class: 'tf-h1', id: 'rv-title' });
    n.vMeta = el('div', { class: 'meta', id: 'rv-meta' });
    n.leave = el('div', { id: 'rv-leave' });
    n.rail = el('div', { id: 'rv-rail' });
    n.slot = el('div', { class: 'rf-slot', id: 'rv-slot' });
    n.tiles = el('div', { class: 'tiles rf-tiles', id: 'rv-tiles' });
    n.view = el('div', { class: 'tf-stack rf-view', id: 'run-view', hidden: true },
      el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' }, n.vEyebrow, n.vTitle, n.vMeta)), n.leave, n.rail, n.slot, n.tiles);
    root.append(n.root, n.view);
  }
  /** view: run.js's step when it is one of the flow's (start, live, needs, done), else null (today's pages). */
  function render(s, view) {
    const n = F.n;
    if (!n.root) return;
    IL.show(n.root, view === 'start');
    const v = view && view !== 'start' ? view : null;
    IL.show(n.view, !!v);
    placeHero(!!v);
    if (view === 'start') renderStart(s);
    else if (v) renderView(s, v);
  }
  /** run.js's board picture (#run-hero) in this frame for Live, Needs you and Done; back on today's pages otherwise. */
  function placeHero(here) {
    const hero = R.n.hero;
    if (!hero || (here ? hero.parentNode === F.n.slot : hero.parentNode !== F.n.slot)) return;
    if (here) F.n.slot.append(hero); else R.n.headRef.after(hero);
    if (R.bv) setTimeout(() => R.bv.relayout(), 0);
  }
  function renderStart(s) {
    const n = F.n;
    if (!R.boards && !R.boardsLoading) R.flow.loadBoards();
    const e = chosen(), p = planOf(e);
    IL.setText(n.title, e ? e.name : 'Choose a design');
    const last = s && s.lastRun && e && p && s.lastRun.boardName === p.boardName && s.lastRun.endedAtMs ? 'last pasted ' + IL.fmtDateTime(IL.toClient(s.lastRun.endedAtMs)) : null;
    IL.keyed(n.meta, JSON.stringify([e && e.key, p && p.summary && p.summary.pads, last]), () => ['Top side', e && isNum(e.x.placements) ? IL.plural(e.x.placements, 'part') : null,
      p && p.summary ? IL.plural(p.summary.pads, 'pad') + ' to paste' : null, last].filter(Boolean).map((t) => el('span', { text: t })));
    const list = entries();
    IL.keyed(n.designs, JSON.stringify([list.map((x) => [x.key, x.name, x.x.art && x.x.art.id]), R.form.sel, !!p, [...F.thumbs.keys()].length, R.boardsErr, !R.boards]), () => {
      if (R.boardsErr) return el('p', { class: 'notebox notebox--block', text: 'The boards could not be read: ' + R.boardsErr });
      if (!R.boards) return el('p', { class: 'muted', text: 'Reading the boards…' });
      const cards = list.map((x) => {
        const on = x.key === R.form.sel;
        const b = el('button', { type: 'button', class: 'design', 'data-board': x.key, 'data-focus-key': 'design:' + x.key, 'aria-pressed': on ? 'true' : 'false', title: x.x.file || x.name },
          el('span', { class: 'design__img' }, pic(x.x.art && x.x.art.id, 300, 130)),
          el('span', { class: 'design__t', text: x.name }),
          el('span', { class: 'design__s', text: on && p && p.summary ? 'chosen · ' + IL.plural(p.summary.pads, 'pad') : isNum(x.x.placements) ? IL.plural(x.x.placements, 'part') : x.kind === 'job' ? 'the job in OpenPnP' : '' }));
        b.addEventListener('click', () => choose(x, b));
        return b;
      });
      return cards.concat(el('a', { class: 'design design--new', id: 'rs-new-design', href: '#designs' }, IL.icon('i-plus'), 'New design'));
    });
    // Load side 1
    const seat = seatOf(e);
    IL.setText(n.loadT, 'Load side 1: the top');
    const artId = e && e.x.art && e.x.art.id;
    IL.keyed(n.loadPic, JSON.stringify([artId, F.thumbs.has(artId + '|640')]), () => pic(artId, 640, 280));
    IL.setText(n.loadCap, !e ? 'Choose a design first.' : !seat ? 'Where does it sit? Plan it once in Step by step › Board: Start then finds it there every time.'
      : 'Put the board in its holder, top side up, where it sat before (' + seat.from + ').');
    const est = p ? R.flow.estimate(s) : null;
    const regS = regSeconds(p);
    IL.keyed(n.loadKv, JSON.stringify([e && e.key, seat, p && p.summary, est && Math.round(est.total / 30), regS]), () => [
      el('dt', { text: 'Side 1' }), el('dd', { text: p && p.summary ? IL.plural(p.summary.pads, 'pad') + ' · ' + IL.plural(p.summary.dots, 'dot') : e ? 'counted when it is planned' : '—' }),
      el('dt', { text: 'Where' }), el('dd', { text: seat ? 'X ' + IL.fmt(seat.x, 1) + ' · Y ' + IL.fmt(seat.y, 1) + ' mm, turned ' + IL.fmtDeg(Number(seat.rotation) || 0) : 'not known yet' }),
      el('dt', { text: 'Time' }), el('dd', { text: est ? minutes(est.total + (regS || 0)) + ', then the result' : '—' })]);
  }
  const regSeconds = (p) => { const t = p && p.registrationTargets && p.registrationTargets.estimatedSeconds; return t && isNum(t.total) ? Number(t.total) : null; };

  // ================================================================================== Live, Needs you, Done
  const RAIL = ['Session', 'Find', 'Paste', 'Check paste', 'Place', 'Check parts', 'Oven'];
  const NOT_YET = 'not built yet';
  function rail(states, subs) {
    return el('div', { class: 'card' }, el('div', { class: 'steps rail', style: '--n:' + RAIL.length },
      RAIL.map((name, i) => {
        const st = states[i];
        const g = st === 'done' ? 'ok' : st === 'need' ? 'need' : st === 'live' ? 'live' : 'wait';
        const glyph = IL.setGlyph(el('span'), g, 'sm', null, g === 'wait' ? String(i + 1) : undefined);
        return el('div', { class: 'step' + (st === 'done' ? ' step--done' : st === 'need' ? ' step--need' : st === 'live' ? ' step--live' : ''), 'data-k': name,
          'aria-current': st === 'need' || st === 'live' ? 'step' : null }, glyph, el('span', { class: 'step__t', text: name }), el('span', { class: 'step__s', text: subs[i] || '' }));
      })));
  }
  const tile = (k, v, u, sub) => el('div', { class: 'tile', 'data-k': k }, el('div', { class: 'tile__k' }, el('span', { text: k })),
    el('div', { class: 'tile__v' }, el('span', { class: 'n', text: v }), u ? el('span', { class: 'u', text: u }) : null), el('div', { class: 'tile__s', text: sub || '' }));
  const time = (ms) => (ms ? IL.fmtTime(IL.toClient(ms)) : '—');
  function viewFacts(s, v) {
    const p = S.plan, run = s && s.run;
    const name = (chosen() || {}).name || (p && String(p.boardName || '').replace(/\.board\.xml$/, '')) || 'Board';
    const nb = R.flow.boardNumber();
    const pads = p ? (p.pads || []).length : 0, done = p ? R.flow.padsDone(p).length : 0, left = p ? R.flow.padsLeft(p).length : 0;
    const dec = v === 'needs' ? R.flow.stopped(s) : null;
    const rr = v === 'done' ? R.flow.resultRun(s) : null;
    return { p, run, name, nb, pads, done, left, dec, rr };
  }
  function renderView(s, v) {
    const n = F.n;
    const f = viewFacts(s, v);
    const board = 'board ' + (f.nb || '1');
    IL.setText(n.vEyebrow, f.name + ' · ' + board + ' · side 1 (top)');
    let title, meta, leave = null, states, subs, tiles;
    if (v === 'live') {
      const r = f.run || {};
      const leftS = IL.boardLeftS(r);
      title = 'Pasting side 1';
      meta = ['Started ' + time(r.startedAtMs), isNum(r.dotsPlanned) ? (r.dotsDone || 0) + ' of ' + r.dotsPlanned + ' dots' : null];
      leave = ['ok', 'You can leave now', (isNum(leftS) ? 'About ' + minutes(leftS).replace('≈ ', '') + ' left. ' : '') + 'If something needs you sooner, it says so here.'];
      states = ['done', 'done', 'live', 'wait', 'wait', 'wait', 'wait'];
      subs = ['your answers', 'marks, height', (r.dotsDone || 0) + ' of ' + (r.dotsPlanned || '?'), NOT_YET, NOT_YET, NOT_YET, 'by hand'];
      tiles = [tile('Pads', String(f.done), 'of ' + f.pads, board), tile('Parts', '—', '', NOT_YET),
        tile('Side 1 left', isNum(leftS) ? minutes(leftS) : '—', '', 'this board'), tile('Paste on since', time(r.pressureOnAtMs), '', 'the pressure on')];
    } else if (v === 'needs') {
      const d = f.dec || { run: {} };
      const part = d.cause === 'partial';
      title = (part ? 'Part done: ' : 'Stopped: ') + board;
      meta = [(part ? 'Ended ' : 'Stopped ') + time(d.atMs + S.skewMs), (d.run.dotsDone || 0) + ' of ' + (d.run.dotsPlanned || '?') + ' dots on the board'];
      leave = ['need', 'Needs you', f.done ? 'Finish the ' + IL.plural(f.left, 'pad') + ' left, or wipe the board and run it all.' : 'No dot reached the board: run it again.'];
      states = ['done', 'done', 'need', 'wait', 'wait', 'wait', 'wait'];
      subs = ['your answers', 'marks, height', (d.run.dotsDone || 0) + ' of ' + (d.run.dotsPlanned || '?'), NOT_YET, NOT_YET, NOT_YET, 'by hand'];
      tiles = [tile('Pads', String(f.done), 'of ' + f.pads, 'have paste'), tile('Pads left', String(f.left), '', 'to finish'),
        tile('Parts', '—', '', NOT_YET), tile(part ? 'Ended' : 'Stopped', time(d.atMs + S.skewMs), '', '')];
    } else {
      const r = f.rr || {};
      const complete = f.p && !f.left;
      title = (complete ? 'Done: ' : 'Part done: ') + board;
      meta = ['finished ' + time(r.endedAtMs), isNum(r.dotsDone) ? r.dotsDone + ' of ' + r.dotsPlanned + ' dots this run' : null];
      states = ['done', 'done', complete ? 'done' : 'need', 'wait', 'wait', 'wait', 'wait'];
      subs = ['your answers', 'marks, height', complete ? 'all ' + f.pads + ' pads' : f.done + ' of ' + f.pads + ' pads', NOT_YET, NOT_YET, NOT_YET, 'by hand'];
      tiles = [tile('Pads', String(f.done), 'of ' + f.pads, 'have paste'), tile('Parts', '—', '', NOT_YET),
        tile('Hand fixes', '—', '', 'not recorded yet (BR-50)'), tile('Time', IL.fmtClock(r.totalSeconds), '', 'this run')];
    }
    IL.setText(n.vTitle, title);
    IL.keyed(n.vMeta, JSON.stringify(meta), () => meta.filter(Boolean).map((x) => el('span', { text: x })));
    IL.keyed(n.leave, JSON.stringify(leave), () => (leave ? el('div', { class: 'leave' + (leave[0] === 'need' ? ' leave--need' : ''), id: 'rv-leave-box' },
      IL.icon(leave[0] === 'need' ? 'i-hand' : 'i-walk'), el('div', {}, el('b', { text: leave[1] }), el('span', { text: leave[2] }))) : null));
    IL.keyed(n.rail, JSON.stringify([states, subs]), () => rail(states, subs));
    IL.keyed(n.tiles, JSON.stringify(tiles.map((x) => x.textContent)), () => tiles);
  }
  function panelView(parts, s, first, v) {
    const n = F.n;
    if (first) {
      parts.body.append(el('div', { class: 'panel__title' }, el('h2', { text: v === 'live' ? 'Side 1 · now' : v === 'needs' ? 'Needs you' : 'Done' })));
      n.pBody = el('div', { class: 'rf-pbody', id: 'rv-panel', 'data-refusals': '' });
      parts.body.append(n.pBody);
    }
    const f = viewFacts(s, v);
    if (v === 'live') {
      const r = f.run || {};
      const found = f.p && f.p.registrationRun && f.p.registrationRun.atMs;
      const rows = [['find', 'done', 'Find the board', found ? 'found ' + time(found) : 'marks, height', ''],
        ['paste', 'now', 'Paste', (r.dotsDone || 0) + ' of ' + (r.dotsPlanned || '?') + ' dots', IL.fmtClock(r.elapsedS)],
        ['check', 'todo', 'Check the paste', NOT_YET + ' (BR-62): look at the pads', ''], ['place', 'todo', 'Place', NOT_YET + ' (BR-28)', ''],
        ['parts', 'todo', 'Check the parts', NOT_YET, ''], ['oven', 'todo', 'Oven', 'by hand: you carry the board', '']];
      IL.keyed(n.pBody, JSON.stringify(rows), () => [el('ol', { class: 'phases', id: 'rv-steps' }, rows.map(([k, st, t, d, val]) => el('li', { class: st === 'done' ? '' : st, 'data-k': k },
        IL.glyph(st === 'done' ? 'ok' : st === 'now' ? 'live' : 'wait', 'xs'), el('div', {}, el('div', { class: 'ph__t', text: t }), el('div', { class: 'ph__d', text: d })), el('span', { class: 'ph__v', text: val })))),
        el('div', { class: 'notebox rf-note', text: 'It does not fix things by itself yet: if pads stay empty or the board moves, press STOP and do what it asks next.' })]);
    } else if (v === 'needs') {
      const d = f.dec || { run: {} };
      const none = !f.done;
      IL.keyed(n.pBody, JSON.stringify([d.cause, d.run.dotsDone, d.run.dotsPlanned, f.left, none]), () => {
        const go = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'rv-need-go' }, none ? 'Run it again' : 'Finish the ' + IL.plural(f.left, 'pad') + ' left');
        go.addEventListener('click', () => R.flow.openConfirm(none ? 'full' : 'rest'));
        const all = el('button', { type: 'button', class: 'btn', id: 'rv-need-all' }, 'Wipe all, run all');
        all.addEventListener('click', () => R.flow.openConfirm('all'));
        return el('div', { class: 'ri ri--need', 'data-key': 'stopped' }, IL.glyph('need'),
          el('div', {}, el('div', { class: 'ri__t' }, none ? 'Run the board again' : 'Finish the pads left', el('span', { class: 'tag tag--next', text: 'Next' })),
            el('div', { class: 'ri__d', text: none ? 'It stopped before the first dot: nothing to keep or wipe.'
              : (d.run.dotsDone || 0) + ' of ' + (d.run.dotsPlanned || '?') + ' dots are on the board. The pads left get their paste; the dots there stay.' })),
          el('div', { class: 'ri__act' }, go, el('details', { class: 'rf-more', id: 'rv-need-more' }, el('summary', { text: 'Other choices…' }),
            el('div', { class: 'btn-row' }, none ? null : all, el('a', { class: 'link', href: '#run/steps', text: 'Step by step' })))),
          el('div', { class: 'ri__note', text: 'Nothing moves until you press it. The run checks every pad first.' }));
      });
    } else {
      const r = f.rr || {};
      const complete = f.p && !f.left;
      IL.keyed(n.pBody, JSON.stringify([r.runId, complete, f.left]), () => {
        // a whole board: the next one (a new seat) or another run; part of it: its pads left, or the next board as it is
        const go = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'rv-done-go' }, complete ? 'Next board · same design' : 'Finish the ' + IL.plural(f.left, 'pad') + ' left');
        go.addEventListener('click', () => (complete ? (R.flow.nextBoard(), R.flow.leaveResult()) : R.flow.openConfirm('rest')));
        const other = el('button', { type: 'button', class: 'btn btn--block', id: 'rv-done-other' }, complete ? 'Choose another run' : 'Next board: unload this one as it is');
        other.addEventListener('click', () => { if (!complete) R.flow.nextBoard(); R.flow.leaveResult(); });
        return [el('div', { class: 'notebox notebox--ok', id: 'rv-done-record' }, el('b', { text: complete ? 'The paste is on' : 'Part of the board has paste' }),
          el('div', {}, 'The run is in Records with its settings and dots. ', r.runId ? el('a', { class: 'link', href: '#records/boards/' + r.runId, id: 'rv-done-open', text: 'Open the record' }) : null)),
          el('div', { class: 'notebox', text: 'Then: carry the board to the oven (by hand: the oven step is not built yet). Checking the paste and placing are not built yet.' }),
          el('div', { class: 'stack rf-done-acts' }, go, other)];
      });
    }
  }

  // ================================================================================== panel
  function panel(parts, s, first, view) {
    if (view && view !== 'start') { panelView(parts, s, first, view); return; }
    const n = F.n;
    if (first) {
      parts.body.append(el('div', { class: 'panel__title' }, el('h2', { text: 'Start' })));
      n.sessGlyph = el('span', { class: 'rf-sess__g' });
      n.sessSub = el('small', { id: 'rs-sess-sub' });
      n.ticks = TICKS.map(([word, key]) => {
        const input = el('input', { type: 'checkbox', id: 'rs-tick-' + word.replace('the ', '') });
        const lab = el('span');
        input.addEventListener('change', () => {
          const st = S.status;
          if (!input.checked) IL.attest.clear(key);
          else IL.attest.set(key, key === 'regulator' ? psiOf(st) : key === 'tip-on' ? tipOf(st).key : true);
          IL.render();
        });
        return { key, input, lab, row: el('label', { class: 'check' }, input, lab) };
      });
      n.pressure = IL.fixButton({ fix: 'pressure-on', label: 'Pressure on to adjust', title: 'Pressure on to read the gauge (press twice unless the needle is over the waste)' });
      n.pressure.id = 'rs-pressure';
      F.wiped = false;
      n.wiped = el('input', { type: 'checkbox', id: 'rs-tick-wiped' });
      n.wiped.addEventListener('change', () => { F.wiped = n.wiped.checked; IL.render(); });
      n.wipedLab = el('span');
      n.newBoard = el('button', { type: 'button', class: 'btn btn--sm', id: 'rs-new-board', title: 'Unload the board with paste and load a new one: a new seat, and the board count goes up at its first dot. Nothing moves.' },
        'It is a new board');
      n.newBoard.addEventListener('click', () => { R.flow.nextBoard(); IL.render(); });
      n.wipeRow = el('div', { class: 'rf-wipe', id: 'rs-wipe-row', hidden: true }, el('div', { class: 'rf-cap', id: 'rs-wipe-why' }), n.newBoard,
        el('label', { class: 'check' }, n.wiped, n.wipedLab));
      n.sess = el('div', { class: 'sess', id: 'rs-session' },
        el('div', { class: 'sess__h' }, n.sessGlyph, el('div', {}, 'Before you start', n.sessSub)),
        n.ticks[0].row, n.ticks[1].row, el('div', { class: 'rf-gauge' }, n.ticks[2].row, n.pressure), n.wipeRow);
      n.cards = el('div', { class: 'rf-cards', id: 'rs-need-cards' });
      n.needs = el('div', { class: 'rf-needs', id: 'rs-needs' }, n.cards, n.refusals);
      n.then = el('ol', { class: 'phases', id: 'rs-then' });
      parts.body.append(n.sess, n.needs, el('div', { class: 'section-k' }, 'Then, by itself', el('span', { class: 'rule' })), n.then);
      n.start = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-rs-start' });
      n.start.addEventListener('click', () => start(n.start));
      n.why = el('div', { class: 'reason', id: 'rs-start-why', hidden: true });
      n.cap = el('div', { class: 'rf-cap', id: 'rs-start-cap', text: 'Start also means: hands and tools are clear. Stay until the paste starts (about a minute); then you can leave.' });
      parts.foot.append(n.why, n.start, n.cap, el('a', { class: 'link rf-steps', id: 'rs-step-by-step', href: '#run/steps', text: 'Step by step: today\'s Run page' }));
    }
    // the answers
    let all = true;
    for (const t of n.ticks) {
      const def = TICKS.find((x) => x[1] === t.key);
      const on = ticked(s, t.key);
      all = all && on;
      if (t.input.checked !== on) t.input.checked = on;
      IL.setText(t.lab, def[2](s));
    }
    n.sess.classList.toggle('sess--ok', all);
    IL.setGlyph(n.sessGlyph, all ? 'ok' : 'need', 'sm');
    IL.setText(n.sessSub, all ? 'Answered. Kept 8 hours, or until the tip, the paste or the pressure changes.' : 'Three answers only you can give. Kept 8 hours. A new barrel\'s record is not built yet (BR-65).');
    const e = chosen(), p = planOf(e), pasted = pastedPads(p);
    IL.show(n.wipeRow, pasted > 0);
    if (!pasted) F.wiped = false;
    IL.setText($('rs-wipe-why'), pasted ? 'The board on this seat has paste on ' + IL.plural(pasted, 'pad') + ' (the server keeps it).' : '');
    IL.setText(n.wipedLab, 'I wiped it: no paste on it now');
    if (n.wiped.checked !== F.wiped) n.wiped.checked = F.wiped;
    for (const x of [n.wiped, n.newBoard].concat(n.ticks.map((t) => t.input))) x.disabled = !!F.chain;
    // what needs you first: a run of this plan that was refused (its words), then the checks
    const ref = R.refused && S.plan && IL.planMatches(R.refused.planId) ? R.refused : null;
    const ns = needs();
    const clean = ref && /^I10/.test(String(ref.message || '')) ? IL.ready().items.find((x) => x.key === 'clean' && !x.ready) : null;
    if (clean && !ns.includes(clean)) ns.push(clean);
    IL.keyed(n.cards, JSON.stringify([ref && [ref.runId, ref.message], ns.map((x) => [x.key, x.state, x.title, x.detail, (x.fixes || []).map((f) => f.fix)])]), () => [
      ref ? el('div', { class: 'ri ri--block', 'data-key': 'refused' }, IL.glyph('block'), el('div', {}, el('div', { class: 'ri__t', text: 'The last run was refused' }),
        el('div', { class: 'ri__d', text: String(ref.message || 'Refused before anything moved.') }))) : null]
      .concat(ns.map((x, i) => R.flow.needCard(x, !ref && i === 0, s))));
    // then, by itself
    const c = F.chain, reg = s && s.run && s.run.label === 'board registration' && s.run.running ? s.run : null;
    const regS = regSeconds(p), est = p ? R.flow.estimate(s) : null;
    const rows = [
      { k: 'find', t: 'Find the board', d: c && c.step === 'find' && reg && reg.phase ? reg.phase : 'its marks and its height; it homes first when it must',
        v: c && c.step === 'paste' ? IL.fmtClock(c.foundS) : c && c.step === 'find' ? IL.fmtClock((Date.now() - c.findAt) / 1000) : isNum(regS) ? minutes(regS) : '',
        st: c && c.step === 'paste' ? 'done' : c && c.step === 'find' ? 'now' : 'todo' },
      { k: 'paste', t: 'Paste side 1', d: (p && p.summary ? IL.plural(p.summary.pads, 'pad') + ', ' : '') + 'the camera checks every pad first',
        v: est ? minutes(est.total) : '', st: c && c.step === 'paste' ? 'now' : 'todo' }];
    IL.keyed(n.then, JSON.stringify(rows), () => rows.map((r) => el('li', { class: r.st === 'done' ? '' : r.st, 'data-k': r.k },
      IL.glyph(r.st === 'done' ? 'ok' : r.st === 'now' ? 'live' : 'wait', 'xs'),
      el('div', {}, el('div', { class: 'ph__t', text: r.t }), el('div', { class: 'ph__d', text: r.d })), el('span', { class: 'ph__v', text: r.v }))));
    // Start
    const reason = c ? null : startReason(s);
    const total = (est ? est.total : 0) + (regS || 0);
    IL.setText(n.start, c ? (c.step === 'find' ? 'Finding the board…' : c.step === 'paste' ? 'Starting the paste…' : 'Planning…') : 'Start' + (total ? ' · ' + minutes(total) : ''));
    IL.gate(n.start, c ? 'Start is under way' : reason);
    IL.setText(n.why, reason || '');
    IL.show(n.why, !!reason);
  }

  IL.runFlow = { mount, render, panel, chaining: () => !!F.chain };
})();
