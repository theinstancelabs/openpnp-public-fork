/* designflow.js: Designs (#designs), the approved mockups `design-upload` and `design-setup` (screen flow `scrDesign`
   d0-d3, the user's OK of 11-02; docs/ui-flows/board-flow.md B) over today's abilities: the KiCad upload (the top
   side), each board file's pad-profile check (GET /api/pad-profile/check?boardFile=: it lists only the pad groups that
   need something), its `use` apply and the pad calibration (padprofile.js).
   - Designs (d0): a card per board file with its picture and what it waits on; Upload a design (Save opens Set up).
   - Set up (d2, #designs/setup/<name>): the checklist B1-B6. What the engine cannot do yet says "not built yet": the
     bottom side (BR-25), the copper check, the fine-pitch rehearsal (BR-27, BR-58), feeders (BR-28, BR-64), the
     holder. Panel: Set up the rest = the doses found, then the pad calibration (nothing moves before its own Start).
   - Ready (d3): Make a board opens Run › Start with the design chosen.
   Nothing here moves the machine. Styles: tipflow.css (Designs). */
'use strict';
(function () {
  const { el } = IL;
  const NOT_YET = 'not built yet';
  const IDENTITY = [1, 0, 0, 1, 0, 0];
  const PP = () => IL.padProfile;
  const D = { boards: null, err: null, loading: null, fresh: null, checks: new Map(), thumbs: new Map(), sel: null, watchKey: null, n: {}, bv: null, bvKey: null };

  // ================================================================================== data
  const files = () => (D.boards && D.boards.files) || [];
  /**
   * A design's key in the route: its board file's path. Names repeat, and `imported` is the drawing's newest upload, so
   * every board saved from one KiCad file shows the same slug.
   */
  const keyOf = (f) => f.file;
  const byKey = (k) => files().find((f) => keyOf(f) === k) || null;
  const routeOf = (f) => '#designs/setup/' + encodeURIComponent(keyOf(f));
  const title = (f) => String(f.name).replace(/\.board\.xml$/, '');
  /** GET /api/boards, then each board file's check (10-20 ms each). */
  function load() {
    if (D.loading) return D.loading;
    if (D.err) { D.err = null; IL.render(); }   // "Reading…" while it reads again (Retry shows it worked on)
    D.loading = (async () => {
      try { D.boards = await IL.api('GET', '/api/boards'); D.err = null; } catch (e) { D.err = IL.readErrorText(e); return; }
      await Promise.all(files().map((f) => readCheck(f.file)));
    })().finally(() => { D.loading = null; IL.render(); });
    return D.loading;
  }
  /** load() after the one in flight, which may have read before a change. */
  async function reload() {
    if (D.loading) await D.loading;
    return load();
  }
  async function readCheck(file) {
    try { D.checks.set(file, { c: await IL.api('GET', '/api/pad-profile/check?boardFile=' + encodeURIComponent(file)) }); } catch (e) { D.checks.set(file, { error: e.message || String(e) }); }
  }
  /** What a design waits on, from its check: {state: ready | wait | tip | reading | error, text, g: split groups}. */
  function stateOf(f) {
    const r = D.checks.get(f.file);
    if (!r) return { state: 'reading', text: 'Checking…' };
    if (r.error) return { state: 'error', text: 'Could not check it: ' + r.error };
    const g = PP().split(r.c);
    g.tip = g.tip.filter((x) => x.blocking);   // as Run's Pad types item: a tip group the engine does not block on (ok) waits on nothing
    if (r.c.ok) return { state: 'ready', text: 'Ready · top side', g };
    const waits = IL.plural(g.fix.length, 'pad group');
    if (g.tip.length) {
      const refs = [...new Set(g.tip.flatMap((x) => x.refs || []))];
      return { state: 'tip', text: IL.boardImport.refList(refs, 3) + (refs.length === 1 ? ' needs' : ' need') + ' another tip' + (g.fix.length ? ' · waits on ' + waits : ''), g };
    }
    return { state: 'wait', text: g.fix.length ? 'Waits on ' + waits : 'Not ready: ' + (((r.c.groups || [])[0] || {}).short || 'see Pad types'), g };
  }
  /**
   * What each design waits on changes with the tip, a pad calibration and an upload; pad types and mappings below (the
   * 'padtypes' event): read them again. A save in this tab (Run's Board step too) is the 'boards' event; every save (another
   * tab's too) moves status.boardImport.savedAtMs, and the list is read after the status that showed it (Backend, #42).
   */
  function watch(s) {
    const t = s && s.dispense && s.dispense.tip;
    const j = s && s.padCalibration;
    const key = JSON.stringify([t && (t.key || t.name), j && j.id, j && j.state, s && s.boardImport ? s.boardImport.savedAtMs : null]);
    if (key === D.watchKey) return;
    const first = D.watchKey === null;
    D.watchKey = key;
    if (!first && D.boards) reload();
  }

  IL.on('padtypes', () => { if (D.boards) reload(); });
  IL.on('boards', () => { if (D.boards) D.fresh = reload(); });   // a save or attach in this tab (Run's Board step too)
  IL.on('online', (on) => { if (on && D.err) reload(); });   // a read that failed while OpenPnP was away

  // ================================================================================== Designs (d0)
  /** A board's picture from its drawing (once per drawing and size); its frame while it loads. */
  function pic(f, w, h) {
    const id = f.art && f.art.id;
    const k = id + '|' + w;
    const box = el('span', { class: 'design__img' });
    if (id && D.thumbs.get(k)) box.append(el('img', { src: D.thumbs.get(k), alt: '' }));
    else if (id && !D.thumbs.has(k)) {
      D.thumbs.set(k, null);
      IL.boardart.get(id).then((a) => {
        const u = a ? IL.boardart.thumbUrl(a, { w, h, id }) : null;
        if (u) { D.thumbs.set(k, u); IL.render(); }
      }).catch(() => { /* the card keeps its frame */ });
    }
    return box;
  }
  const GLYPH = { ready: 'ok', wait: 'need', tip: 'block', error: 'block', reading: 'wait' };
  function renderList() {
    const n = D.n;
    const list = files();
    const key = [!!D.boards, D.err, list.map((f) => [f.name, f.file, stateOf(f).text, !!D.thumbs.get((f.art && f.art.id) + '|300')])];
    IL.keyed(n.list, JSON.stringify(key), () => {
      if (D.err && !list.length) return el('p', { class: 'notebox notebox--block' }, el('span', { text: IL.listReadError(D.err) }), ' ', IL.retryButton(reload, 'designs'));
      if (!D.boards) return el('p', { class: 'muted', text: 'Reading the designs…' });
      if (!list.length) return el('p', { class: 'notebox', text: 'No designs yet: upload one.' });
      // a read again that fails keeps the cards it has, under a note (the next visit reads them again)
      const note = D.err ? [el('p', { class: 'reason reason--need designs-note' }, el('span', { text: IL.listReadError(D.err, true) }), ' ',
        IL.retryButton(reload, 'designs-note'))] : [];
      return note.concat(list.map((f) => {
        const st = stateOf(f);
        return el('a', { class: 'design', href: routeOf(f), 'data-name': f.name, 'data-file': f.file, 'data-state': st.state, 'data-art': f.art && f.art.id ? f.art.id : null },
          pic(f, 300, 120), el('span', { class: 'design__t', text: title(f) }),
          el('span', { class: 'design__s' }, IL.glyph(GLYPH[st.state], 'xs'), el('span', { class: 'design__st', text: st.text })));
      }));
    });
    IL.setText(n.count, D.boards ? IL.plural(list.length, 'design') : '');
  }

  // ================================================================================== Set up (d2, d3)
  /** The checklist B1-B6 from a design's check: [{k, state: done | need | block | notyet | todo, t, d, act}]. */
  function stepsOf(f, st) {
    const g = st.g || { fix: [], tip: [] };
    const { use, cal, measure, hand } = kinds(g);
    const bottom = ((f.imported && f.imported.skipped) || {}).bottom || [];
    const checked = !!st.g;
    const paste = !checked ? { state: 'todo', d: st.text }
      : g.fix.length ? { state: 'need', d: IL.plural(g.fix.length, 'pad group waits', 'pad groups wait') + ' for a dose' + (use.length ? ': ' + use.length + ' can use a dose found' : ''),
        act: use.length ? 'use' : null }
        : { state: 'done', d: g.tip.length ? 'Every other pad has a dose' : 'Every pad has a dose' };
    const missing = [cal.length ? IL.plural(cal.length, 'pad group') + ' to calibrate: test dots on the test blank, then you weigh them once' : null,
      measure.length ? IL.plural(measure.length, 'pad group waits', 'pad groups wait') + ' until the tip’s dots are measured (Tip › Checks)' : null,
      hand.length ? IL.plural(hand.length, 'pad group') + ' with no dose to suggest: set by hand' : null].filter(Boolean);
    const rows = [
      // GET /api/boards counts the fiducials among the placements; the upload names them apart ("8 parts, 2 fiducials")
      { k: 'sides', state: 'done', t: 'Read the sides', d: 'Top: ' + IL.plural(Math.max(0, (Number(f.placements) || 0) - (f.fiducials || []).length), 'part')
        + (bottom.length ? ' · Bottom: not read yet (' + IL.boardImport.refList(bottom, 3) + ')' : '') },
      { k: 'copper', state: 'notyet', t: 'Bare board matches the file', d: NOT_YET + ': compare a bare board with the picture by eye' },
      Object.assign({ k: 'paste', t: 'Paste per pad' }, paste)];
    // one row per part that needs another tip (its pad groups together), with the server's reason
    const tips = new Map();
    for (const x of g.tip) {
      const pkg = (x.packageIds || [])[0] || x.group;
      if (!tips.has(pkg)) tips.set(pkg, []);
      tips.get(pkg).push(x);
    }
    for (const [pkg, gs] of tips) {
      const refs = [...new Set(gs.flatMap((x) => x.refs || []))];
      const pitch = Math.min(...gs.map((x) => Number(x.minPitchMm)).filter((v) => v > 0));
      const short = gs[0].short || 'not with this tip';
      rows.push({ k: 'tip:' + pkg, state: 'block', t: IL.boardImport.refList(refs, 3) + (isFinite(pitch) ? ' · ' + IL.fmt(pitch, 2) + ' mm pitch' : ''),
        d: short.charAt(0).toUpperCase() + short.slice(1) + ' (' + pkg + ').', why: gs[0].reasonText || null, act: 'tip' });
    }
    rows.push(
      { k: 'doses', t: 'Missing doses', state: !checked ? 'todo' : missing.length ? 'need' : 'done', d: !checked ? st.text : missing.join('. ') || 'None missing',
        act: cal.length ? 'doses' : measure.length ? 'measure' : hand.length ? 'hand' : null },
      { k: 'rehearsal', state: 'notyet', t: 'Fine-pitch rehearsal', d: NOT_YET + ': the fine-pitch pads tried on the test blank first' },
      { k: 'feeders', state: 'notyet', t: 'Feeders', d: NOT_YET + ': placing parts comes later' },
      { k: 'holder', state: 'notyet', t: 'Board holder', d: NOT_YET + ': Run finds the board each time instead' });
    return rows;
  }
  /** The groups that wait for a dose, by what gets them one: a dose found, a calibration, the tip's dots, a hand. */
  function kinds(g) {
    const measure = g.fix.filter((x) => x.suggestion && x.suggestion.kind === 'measure-tip');
    const use = g.fix.filter(PP().usable), cal = g.fix.filter(PP().calibrates);
    return { use, cal, measure, hand: g.fix.filter((x) => !use.includes(x) && !cal.includes(x) && !measure.includes(x)) };
  }
  const ROW_GLYPH = { done: 'ok', need: 'need', block: 'block', notyet: 'wait', todo: 'wait' };
  function rowEl(f, r) {
    let act = null;
    if (r.act === 'use') {
      act = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-design-use' }, 'Use the doses found');
      act.addEventListener('click', () => IL.act(act, () => useFound(f), { refusalIn: D.n.refusals, ok: usedText }));
    } else if (r.act === 'measure') {
      act = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-design-measure' }, 'Measure the tip’s dots…');
      act.addEventListener('click', () => IL.go('#tip/checks'));
    } else if (r.act === 'doses') {
      act = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-design-doses' }, 'Calibrate…');
      act.addEventListener('click', () => calibrate(f));
    } else if (r.act === 'tip') {
      act = el('button', { type: 'button', class: 'btn btn--sm btn-design-tip' }, 'Change the tip');
      act.addEventListener('click', () => IL.go('#tip/which'));
    } else if (r.act === 'hand') {
      act = el('button', { type: 'button', class: 'btn btn--sm', id: 'btn-design-hand' }, 'Set them by hand…');
      act.addEventListener('click', () => byHand(f));
    }
    return el('li', { class: 'rstep' + (r.state === 'done' ? ' rstep--done' : r.state === 'notyet' || r.state === 'todo' ? ' rstep--wait' : ''), 'data-k': r.k, 'data-state': r.state, title: r.why || null },
      IL.glyph(ROW_GLYPH[r.state], 'sm'), el('div', {}, el('div', { class: 'rstep__t', text: r.t }), el('div', { class: 'rstep__d', text: r.d })), el('span', {}, act));
  }
  function renderSetup() {
    const n = D.n;
    IL.show(n.home, !D.sel);
    IL.show(n.setup, !!D.sel);
    if (!D.sel) return;
    const f = byKey(D.sel);
    IL.setText(n.name, f ? title(f) : '');
    const im = f && f.imported;
    IL.keyed(n.meta, JSON.stringify([!!D.boards, D.err, f && f.file, im && im.atMs]), () => (!f ? (D.err ? [el('span', { text: IL.listReadError(D.err) }), ' ', IL.retryButton(reload, 'setup')]
      : el('span', { text: D.boards ? 'No such design: pick one in Designs.' : 'Reading the design…' }))
      : [el('span', { text: im && im.atMs ? 'Uploaded ' + IL.fmtDateTime(IL.toClient(im.atMs)) + (im.sourceName ? ' from ' + im.sourceName : '') : 'Board file ' + String(f.file).split('/').pop() }),
        el('span', { text: 'top side' })]));
    const st = f ? stateOf(f) : { state: 'reading', text: '' };
    const rows = f ? stepsOf(f, st) : [];
    IL.keyed(n.steps, JSON.stringify([f && f.file, rows]), () => rows.map((r) => rowEl(f, r)));
    // as Pad types' Needs: Use the doses found waits for a task, held pressure or an upload being read; a calibration also
    // for one waiting on its readings
    IL.gate(document.getElementById('btn-design-use'), PP().idle(IL.S.status));
    IL.gate(document.getElementById('btn-design-doses'), PP().calIdle(IL.S.status));
    const todo = rows.filter((r) => r.state === 'need' || r.state === 'block').length;
    IL.setText(n.todo, st.state === 'ready' ? 'Ready' : todo ? todo + ' to do' : '');
    n.todo.classList.toggle('design-todo--ok', st.state === 'ready');
    renderPic(f, st);
  }
  /** The design's top, its parts hatched where their pads wait: amber for a dose, red for another tip. */
  function renderPic(f, st) {
    const n = D.n;
    const id = f && f.art && f.art.id;
    if (!D.bv) {
      D.bv = IL.boardview.create(n.pic, { lite: true, aria: 'The design’s top side, its waiting parts hatched' });
      D.bv.picture('full');
    }
    const g = st.g || { fix: [], tip: [] };
    const tint = new Map();
    for (const x of g.fix) for (const r of x.refs || []) tint.set(r, 'wait');
    for (const x of g.tip) for (const r of x.refs || []) tint.set(r, 'block');
    const key = JSON.stringify([id, !!st.g, [...tint]]);
    if (key === D.bvKey) return;
    D.bvKey = key;
    IL.setText(n.picCap, !f || !st.g ? '' : !id ? 'No drawing of this design.' : tint.size ? 'Hatched: parts whose pads wait, amber for a dose, red for another tip.' : 'Every part on the top gets paste.');
    if (!id) { D.bv.setBoard(null); return; }
    IL.boardart.get(id).then((a) => {
      if (D.bvKey !== key) return;   // another design or state since
      D.bv.setBoard({ art: a || null, toMachine: IDENTITY });
      IL.boardImport.hatch(D.bv, a, (ref) => tint.get(ref), 'dz');
    }).catch(() => { if (D.bvKey === key) IL.setText(n.picCap, 'The drawing could not be read.'); });
  }

  // ================================================================================== actions (none moves the machine)
  const src = (f) => ({ kind: 'board', boardFile: f.file });
  const groupsOf = (f, which) => { const st = stateOf(f); return (st.g ? st.g.fix : []).filter(PP()[which]); };
  /** The check's `use` suggestions mapped (POST /api/pad-profile/apply), then its check read again (also after a refusal). */
  async function useFound(f) {
    const use = groupsOf(f, 'usable');
    if (!use.length) return null;
    try {
      return await PP().apply(src(f), use, 'use');
    } finally {
      await readCheck(f.file);
      IL.render();
    }
  }
  /** The pad calibration's own panel for the groups to calibrate: it lays test dots only after its Start. */
  function calibrate(f) {
    const cal = groupsOf(f, 'calibrates');
    if (cal.length) PP().openCal(PP().calRequest(src(f), cal));
  }
  async function rest(f) {
    const r = await useFound(f);
    calibrate(f);
    return r;
  }
  /** What "Use the doses found" did: a mapping is per package, so other designs with it change too; what it left out, why. */
  function usedText(r) {
    if (!r) return null;
    const n = (r.applied || []).length, skipped = r.skipped || [];
    return (n ? 'Used the doses found for ' + IL.plural(n, 'pad group') + '. Other designs with the same packages use them too.' : 'No dose was used.')
      + (skipped.length ? ' Not used: ' + skipped.map((x) => x.why).join('; ') + '.' : '');
  }
  /** Groups without a suggestion: Pad types › Needs a profile for this design (today's by-hand path). */
  function byHand(f) {
    PP().setSource({ boardFile: f.file, boardName: title(f) });
    IL.go('#profile/needs');
  }
  /** Run › Start with this design chosen (its Load side 1 says where the board goes). */
  function make(f) {
    IL.run.flow.selectBoard('file:' + f.file);
    IL.go('#run');
  }
  /** After Save the design: its Set up, once the save's own read ('boards') has it; not if the operator went elsewhere. */
  async function onSaved(saved) {
    const at = location.hash;
    const fresh = D.fresh || reload();
    D.fresh = null;
    await fresh;
    if (location.hash !== at) return;
    const f = files().find((x) => x.file === saved.file);
    IL.go(f ? routeOf(f) : '#designs');
  }
  /** Designs' upload: Save the design closes the dialog and opens its Set up (the button, and a file dropped here). */
  const uploadOptions = () => ({ onSaved, saveCloses: true });
  IL.designFlow = { uploadOptions };

  // ================================================================================== the panel
  function panel(p, s, first) {
    const n = D.n;
    if (first) {
      n.pTitle = el('h2');
      n.pLead = el('div', { class: 'panel__lead' });
      n.pList = el('ol', { class: 'phases', id: 'design-rest-list' });
      n.pBlock = el('div', { class: 'notebox notebox--block', id: 'design-rest-block', hidden: true });
      n.pMore = el('p', { class: 'meta place-more' }, el('a', { class: 'link', href: '#profile', text: 'Pad types' }), el('span', { text: ': the dose of every pad size, per tip.' }));
      p.body.append(el('div', { class: 'panel__title' }, n.pTitle), n.pLead, n.pList, n.pBlock, n.pMore);
      n.pRefusals = el('div', { 'data-refusals': '' });
      n.pRest = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-design-rest' }, IL.icon('i-play'), 'Set up the rest');
      n.pRest.addEventListener('click', () => { const f = byKey(D.sel); if (f) IL.act(n.pRest, () => rest(f), { refusalIn: n.pRefusals, ok: usedText }); });
      n.pMake = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-design-make' }, IL.icon('i-play'), 'Make a board');
      n.pMake.addEventListener('click', () => { const f = byKey(D.sel); if (f) make(f); });
      n.pNext = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-design-next' });
      n.pNext.addEventListener('click', () => { if (n.pNextGo) n.pNextGo(); });
      n.pWhy = el('div', { class: 'reason', id: 'design-rest-why', hidden: true });
      n.pCap = el('div', { class: 'design-cap' });
      p.foot.append(n.pRefusals, n.pRest, n.pWhy, n.pMake, n.pNext, n.pCap);
    }
    const f = D.sel ? byKey(D.sel) : null;
    const st = f ? stateOf(f) : null;
    const g = (st && st.g) || { fix: [], tip: [] };
    const { use, cal, measure, hand } = kinds(g);
    const ready = !!st && st.state === 'ready';
    const tipRefs = [...new Set(g.tip.flatMap((x) => x.refs || []))];
    const waitsOn = [g.fix.length ? IL.plural(g.fix.length, 'pad group') : null, tipRefs.length ? 'another tip for ' + IL.boardImport.refList(tipRefs, 3) : null].filter(Boolean);
    IL.setText(n.pTitle, !D.sel ? 'Designs' : ready ? 'Ready' : 'Set up the rest');
    IL.setText(n.pLead, !D.sel ? 'Pick a design to see what it still needs.' : !st || !st.g ? st ? st.text : D.err ? 'Could not read the designs.' : 'Reading the design…'
      : ready ? 'Every pad on the top has a dose. The steps not built yet are done by hand or later.'
        : waitsOn.length ? 'This design waits on ' + waitsOn.join(' and ') + '.' : st.text + '.');
    const left = !st || !st.g || ready ? [] : [
      use.length ? ['Paste per pad', 'use the doses found · nothing moves', 'todo'] : null,
      cal.length ? ['Missing doses', 'the machine lays test dots, then you weigh once', 'todo'] : null,
      measure.length ? ['The tip’s dots', 'measured in Tip › Checks first', 'todo'] : null,
      hand.length ? ['Doses by hand', 'no dose to suggest for ' + IL.plural(hand.length, 'pad group'), 'todo'] : null,
      ['Fine-pitch rehearsal', NOT_YET, 'notyet'],
      ['Feeders', NOT_YET, 'notyet']].filter(Boolean);
    IL.keyed(n.pList, JSON.stringify(left), () => left.map(([t, d, k]) => el('li', { class: k, 'data-state': k }, IL.glyph('wait', 'xs'),
      el('div', {}, el('div', { class: 'ph__t', text: t + (k === 'notyet' ? ' · ' + NOT_YET : '') }), el('div', { class: 'ph__d', text: k === 'notyet' ? 'comes later' : d })), el('span', { class: 'ph__v' }))));
    IL.setText(n.pBlock, tipRefs.length ? IL.boardImport.refList(tipRefs, 3) + (tipRefs.length === 1 ? ' needs' : ' need') + ' another tip' + (g.tip[0].short ? ': ' + g.tip[0].short : '')
      + '. This design can’t be made until the tip is changed.' : '');
    IL.show(n.pBlock, tipRefs.length > 0);
    IL.show(n.pMore, !D.sel);
    const canRest = !!st && !ready && (use.length > 0 || cal.length > 0);
    IL.show(n.pRest, canRest);
    // the visible reason for this and the steps' buttons (they show only when this does), as Needs' #needs-why; while a
    // pushed panel (the pad calibration's) covers this one, the steps' buttons keep the reason as their title
    IL.gate(n.pRest, canRest ? (cal.length ? PP().calIdle : PP().idle)(s) : null, n.pWhy);
    IL.show(n.pMake, ready);
    // nothing to run here: the step that comes next, on its own page
    const next = !st || ready || canRest ? null : measure.length ? ['Measure the tip’s dots', () => IL.go('#tip/checks')]
      : hand.length ? ['Set the doses by hand', () => byHand(f)] : tipRefs.length ? ['Change the tip', () => IL.go('#tip/which')] : null;
    if (next) { IL.setText(n.pNext, next[0]); n.pNextGo = next[1]; }
    IL.show(n.pNext, !!next);
    IL.setText(n.pCap, !D.sel || !st ? '' : ready ? 'Run opens with this design chosen.'
      : canRest ? (cal.length ? 'It uses the doses found, then opens the pad calibration. Nothing moves until you start it there.' : 'It uses the doses found. Nothing moves.') : '');
  }

  // ================================================================================== the workspace
  IL.workspace('designs', {
    mount(root) {
      const n = D.n;
      const up = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-design-upload' }, IL.icon('i-plus'), 'Upload a design');
      up.addEventListener('click', () => IL.boardImport.open(uploadOptions()));
      n.count = el('span');
      n.list = el('div', { class: 'designs', id: 'designs-list' });
      n.home = el('div', { id: 'designs-home' },
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Designs' }), el('h1', { text: 'Designs' }), el('div', { class: 'meta' }, n.count)),
        el('div', { class: 'page-head__actions' }, up)),
        n.list);
      n.name = el('h1', { id: 'design-name' });
      n.meta = el('div', { class: 'meta', id: 'design-meta' });
      n.todo = el('span', { class: 'design-todo' });
      n.steps = el('ol', { class: 'rsteps', id: 'design-steps' });
      n.refusals = el('div', { 'data-refusals': '' });
      n.pic = el('div', { class: 'pic design-pic', id: 'design-pic' });
      n.picCap = el('div', { class: 'design-cap' });
      n.setup = el('div', { id: 'design-setup', hidden: true },
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' }, el('div', { class: 'eyebrow', text: 'Design' }), n.name, n.meta)),
        el('div', { class: 'tf-row design-row' },
          el('section', { class: 'card' }, el('div', { class: 'card__head' }, el('div', {}, el('h2', { class: 'card__title', text: 'Set up' }),
            el('div', { class: 'card__sub', text: 'Set up the rest runs every step that is built. The others say what to do by hand, or that they come later.' })), n.todo),
          el('div', { class: 'card__body' }, n.refusals, n.steps)),
          el('section', { class: 'card design-piccard' }, el('div', { class: 'card__head' }, el('h2', { class: 'card__title', text: 'Top' })),
            el('div', { class: 'card__body' }, n.pic, n.picCap))));
      root.append(n.home, n.setup);
    },
    show() { D.entered = true; },
    route(r) {
      const sel = r.section === 'setup' && r.id ? r.id : null;
      if (sel !== D.sel) { D.sel = sel; D.n.refusals.replaceChildren(); IL.panel.reset(); } else if (!D.entered) return;
      D.entered = false;
      reload();   // every visit reads them again: a mapping, a pad type or the tip may have changed elsewhere
    },
    render() { renderList(); renderSetup(); },
    panel,
    nav(s) {
      watch(s);
      // unsaved pad-type edits (Pad types) are named first, as before
      const w = IL.workspaces.profile;
      const pv = w && w.nav ? w.nav(s) : null;
      if (pv && pv.dirty) return pv;
      if (!D.boards && !D.loading && !D.err) load();
      const fs = files();
      if (!fs.length || fs.some((f) => !D.checks.has(f.file))) return {};
      const ready = fs.filter((f) => stateOf(f).state === 'ready').length;
      return { sub: [ready ? ready + ' ready' : null, fs.length - ready ? (fs.length - ready) + ' to set up' : null].filter(Boolean).join(' · ') };
    },
  });
})();
