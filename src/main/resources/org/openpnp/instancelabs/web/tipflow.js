/* tipflow.js: the Tip place of the agreed menu (docs/ui-flows/tip-setup-flow.md; mockups docs/ui-flows/mockups/, the
   user's OK of 11-02). Routes: #tip (On now: the tip on the dispenser, every setting with how and when it was measured
   and what makes it out of date), #tip/which (step 1: the kinds with their pictures, the kind on now first as a fresh
   tip; Park for the swap), #tip/swap (steps 2-3: It's on mounts the kind; the gauge reading is kept with the new
   needle), #tip/checks (steps 4-7 in the flow's order: what the machine can do today runs from Start the checks, the
   rest is a by-hand step or not built yet, each with why).
   On today's API (PR #3's tip setups): GET /api/tip-setups (IL.tips), POST /api/tip-setups/{key}/mount and
   …/picture, POST /api/pressure/stop, the landing check POST /api/pad-calibrations {measure: "landing"}. Asked of the
   backend: the head's move to the waste (BR-49), the path per way in and the session check (BR-51), the bed switch
   for the height (BR-48). Nothing moves before a press that says so. Park, It's on and the swap steps wait while a
   task runs or OpenPnP moves the head (It's on and the steps also for the air); the checks stop at a step that did
   not finish. Styles: tipflow.css. */
'use strict';
(function () {
  const { el, S } = IL;
  const isNum = IL.isNum;
  const SEL_KEY = 'il.tipflow.sel';
  const RAIL = ['Which tip', 'Swap', 'Regulator', 'Fit', 'Lands', 'Dose', 'Accept'];
  const W = { view: 'now', sel: IL.ssJson(SEL_KEY, null), mountedTok: null, below: null, lands: null, job: null, running: false, err: null, panelWaits: false, V: {} };
  const V = W.V;

  // ================================================================================== data
  const live = () => { const d = IL.tips.data(); return ((d && d.setups) || []).filter((x) => !x.retired); };
  const mounted = () => IL.tips.mountedSetup();
  const token = () => IL.tips.token();
  const t = (ms) => (isNum(ms) ? IL.fmtTime(IL.toClient(ms)) : '—');
  function selected() {
    const all = live();
    return all.find((x) => x.key === W.sel) || mounted() || all[0] || null;
  }
  function choose(key) { W.sel = key; IL.ssSet(SEL_KEY, key); IL.panel.reset(); IL.render(); }
  const sameKind = () => { const m = mounted(), s = selected(); return !!(m && s && m.key === s.key); };
  /**
   * Why hands stay off the tip now, or null: an agent holds the machine or a take-over still stops its work (BR-72: its
   * next move could start under the hands), a task runs, OpenPnP homes or moves the head (homing counts even while the
   * machine reads off), or (pressureOff) the air is not off.
   */
  const handsLock = (s, pressureOff) => {
    const lk = (s && s.lock) || {};
    if (lk.handover) return 'Take control is still stopping ' + ((lk.takenFrom && lk.takenFrom.label) || 'the agent') + '’s work: wait for it to finish';
    const h = lk.holder;
    if (h && h.kind !== 'user') return (IL.holderName ? IL.holderName(h) : h.label || 'An agent') + ' has the machine: Take control first. Its next move could start while your hands are on the tip.';
    const m = (s && s.machine) || {};
    return IL.lockReason(s, { pressureOff }) || (m.homing || (m.enabled && m.busy) ? 'OpenPnP is busy (homing or moving): wait for it to finish' : null);
  };

  /** Every setting of the tip on now, with how and when it was measured and what makes it out of date. */
  function evidence(s) {
    const d = s.dispense || {}, nd = d.needle || {}, prof = d.profile || {}, tip = d.tip || {};
    const L = nd.landing || {};
    const lands = nd.xyChecked && L.known
      ? { v: IL.fmtSigned(L.dxMm, 2) + ', ' + IL.fmtSigned(L.dyMm, 2) + ' mm', how: (L.jobId ? 'the landing check' : (L.note || 'where it lands')) + ', ' + t(L.setAtMs) }
      : { v: 'not checked', how: 'not checked since this tip went on: Tip › Checks' };
    lands.until = 'the tip is touched or changed';
    const a = nd.anchor;
    const height = a && nd.anchored
      ? { v: 'needle contact ' + IL.fmt(a.needleContactZMm, 3) + ' mm', how: (isNum(a.rawSumMm) ? 'from K = ' + IL.fmt(a.rawSumMm, 2) + ', the side camera' : 'both tips touching, by hand')
        + ', ' + t(a.setAtMs) + ' (the bed switch, once it is fitted)' }
      : { v: 'not measured', how: 'not measured for this needle: by hand in Machine › By hand › Setup (the bed switch, once it is fitted)' };
    height.until = 'the next home, or the tip is touched';
    const reg = IL.attest.valid('regulator', s);
    const pressure = reg ? { v: IL.fmtPsi(reg.value) + ' psi', how: 'your gauge reading, ' + IL.fmtTime(reg.atMs) }
      : { v: 'not read', how: 'read the gauge at the next tip change (target ' + IL.fmtPsi(prof.pressurePsi) + ' psi)' };
    pressure.until = 'the regulator is turned, the needle changes, or after 8 h';
    const mt = mounted();
    const dose = { v: mt ? IL.tips.dotsWords(mt) : String(tip.dots || '—'), how: mt && mt.dots && mt.dots.source === 'bench' ? 'the bench tests' : 'dots on the test blank, weighed on coupons', until: 'the paste barrel changes' };
    const pads = { v: mt ? IL.tips.typesWords(mt) : '—', how: 'the pad calibration, per pad size', until: 'the dose changes' };
    const rows = [['lands', 'Where it lands', lands], ['height', 'Height', height], ['pressure', 'Pressure', pressure], ['dose', 'Dose', dose]];
    // the paste per pad Calibrate aims for (BR-85): the tip's own, else the one its pads' family shares; Process sets it
    const da = tip.doseAnchor;
    if (da) {
      const of = [da.padType, isNum(da.areaMm2) ? IL.fmt(da.areaMm2, 2) + ' mm²' : null].filter(Boolean);
      rows.push(['anchor', 'Paste per pad (target)', { v: String(Number(Number(da.massPerPadMg).toFixed(3))) + ' mg per pad' + (of.length ? ' (' + of.join(', ') + ')' : ''),
        how: da.fromFamily ? (da.source || 'the shared anchor') + ': this tip has no number of its own yet' : (da.source || 'set') + (isNum(da.setAtMs) ? ', ' + t(da.setAtMs) : ''),
        until: 'this tip is weighed again' }]);
    }
    rows.push(['padsizes', 'Pad sizes', pads]);
    return rows;
  }

  // ================================================================================== parts
  function rail(states, subs) {
    let n = 0;
    return el('div', { class: 'card' }, el('div', { class: 'steps rail', id: 'tip-rail', style: '--n:' + RAIL.length },
      RAIL.map((name, i) => {
        n++;
        const st = states[i];
        const g = st === 'done' ? 'ok' : st === 'need' ? 'need' : st === 'live' ? 'live' : st === 'now' ? 'now' : 'wait';
        const glyph = el('span', { class: 'st st--sm st--' + g, role: 'img' });
        if (g === 'ok' || g === 'need') IL.setGlyph(glyph, g, 'sm'); else if (g === 'live') glyph.append(el('span', { class: 'pulse', style: 'width:8px;height:8px' }));
        else glyph.append(String(n));
        return el('div', { class: 'step' + (st === 'done' ? ' step--done' : st === 'now' ? ' step--now' : st === 'need' ? ' step--need' : st === 'live' ? ' step--live' : ''),
          'aria-current': st === 'now' || st === 'need' || st === 'live' ? 'step' : null }, glyph, el('span', { class: 'step__t', text: name }), el('span', { class: 'step__s', text: subs[i] || '' }));
      })));
  }
  function head(eyebrow, h1, meta, actions) {
    return el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' }, el('div', { class: 'eyebrow', text: eyebrow }), h1, meta || null),
      actions ? el('div', { class: 'page-head__actions' }, actions) : null);
  }
  function leave(title, sub, need) {
    return el('div', { class: 'leave' + (need ? ' leave--need' : '') }, IL.icon(need ? 'i-hand' : 'i-walk'), el('div', {}, el('b', { text: title }), el('span', { text: sub })));
  }

  // ================================================================================== views (main column)
  function mountViews(root) {
    // On now
    V.nowName = el('h1', { class: 'tf-h1', id: 'tipnow-name' });
    V.nowMeta = el('div', { class: 'meta' });
    const change = el('button', { type: 'button', class: 'btn', id: 'btn-tipnow-change' }, IL.icon('i-swap'), 'Change the tip');
    change.addEventListener('click', () => IL.go('#tip/which'));
    V.nowPhoto = el('div', { class: 'tf-pic', id: 'tipnow-photo' });
    V.nowKv = el('dl', { class: 'kv kv--wrap', style: 'flex:1' });
    V.nowEvi = el('tbody');
    V.now = el('div', { class: 'tf-stack', 'data-view': 'now' },
      head('Tip on the dispenser', V.nowName, V.nowMeta, change),
      el('section', { class: 'card' }, el('div', { class: 'card__body tf-row', style: 'padding:14px;align-items:center' }, el('div', { style: 'flex:0 0 250px' }, V.nowPhoto), V.nowKv)),
      el('section', { class: 'card' }, el('div', { class: 'card__head' }, el('div', {}, el('h2', { class: 'card__title', text: 'Settings and their evidence' }),
        el('div', { class: 'card__sub', text: 'Measured by the machine where it can. The only typed numbers are gauge and scale readings.' }))),
      el('div', { class: 'card__body' }, el('table', { class: 'tbl evi', id: 'tipnow-evidence' },
        el('thead', {}, el('tr', {}, ['Setting', 'Value', 'How and when', 'Out of date when'].map((x) => el('th', { text: x })))), V.nowEvi))));
    // Which tip
    V.kinds = el('div', { class: 'kinds', id: 'tip-kinds' });
    V.which = el('div', { class: 'tf-stack', 'data-view': 'which', hidden: true },
      head('Set up a tip · step 1 of 7', el('h1', { class: 'tf-h1', text: 'Which tip goes on?' }),
        el('div', { class: 'meta' }, el('span', { text: 'Pick its kind. The machine works out the steps from what it knows about the kind.' }))),
      V.kinds);
    // Swap
    V.swapRail = el('div');
    V.swapPic = el('div', { class: 'tf-pic', style: 'height:236px' });
    V.swapHow = el('ol', { class: 'howto' });
    V.swapWaitWhy = el('div');
    V.swapWait = el('div', { class: 'notebox notebox--need', id: 'tip-swap-wait', hidden: true }, el('b', { text: 'Wait: keep your hands off the tip.' }), V.swapWaitWhy);
    V.swapMeta = el('div', { class: 'meta' });
    V.swapTitle = el('h1', { class: 'tf-h1', text: 'Swap the tip' });
    V.swap = el('div', { class: 'tf-stack', 'data-view': 'swap', hidden: true },
      head('Set up a tip', V.swapTitle, V.swapMeta), V.swapRail,
      el('div', { class: 'tf-row' }, el('section', { class: 'card', style: 'flex:0 0 300px' }, el('div', { class: 'card__body', style: 'padding:14px' }, V.swapPic)),
        el('section', { class: 'card' }, el('div', { class: 'card__head' }, el('h2', { class: 'card__title', text: 'Your hands' })), el('div', { class: 'card__body' }, V.swapWait, V.swapHow))));
    // Checks
    V.chkRail = el('div');
    V.chkLeave = el('div');
    V.chkResult = el('div', { class: 'tf-stack' });
    V.checks = el('div', { class: 'tf-stack', 'data-view': 'checks', hidden: true },
      head('Set up a tip', el('h1', { class: 'tf-h1', text: 'The machine checks the tip' }), el('div', { class: 'meta' }, el('span', { text: 'What the machine can check today runs from Start the checks; the rest says what to do by hand.' }))),
      V.chkLeave, V.chkRail, V.chkResult);
    root.append(V.now, V.which, V.swap, V.checks);
  }

  function renderNow(s) {
    const d = s.dispense || {}, tip = d.tip || {}, nd = d.needle || {};
    const mt = mounted();
    IL.setText(V.nowName, tip.name || 'No tip named');
    const missing = [!(nd.anchor && nd.anchored) ? 'height' : null, !nd.xyChecked ? 'where it lands' : null].filter(Boolean);
    IL.keyed(V.nowMeta, missing.join('|') + '|' + (tip.mountedAtMs || ''), () => [
      el('span', { class: missing.length ? 's-need' : 's-ok', text: missing.length ? 'Not ready: ' + missing.join(' and ') + ' to check' : 'Ready for boards' }),
      el('span', { text: tip.mountedAtMs ? 'on since ' + t(tip.mountedAtMs) : '' })]);
    IL.keyed(V.nowPhoto, mt ? (mt.mainPhoto ? mt.mainPhoto.id : 'none') + mt.key : 'no', () => (mt ? IL.tips.thumb(mt, 'big', true) : el('span', { class: 'muted', text: 'No tip named' })));
    IL.keyed(V.nowKv, JSON.stringify([tip.summary, tip.dispenser, tip.mountedAtMs, nd.changedAtMs]), () => [
      el('dt', { text: 'Kind' }), el('dd', { text: tip.summary || '—' }), el('dt', { text: 'Dispenser' }), el('dd', { text: tip.dispenser || '—' }),
      el('dt', { text: 'On since' }), el('dd', { text: tip.mountedAtMs ? t(tip.mountedAtMs) : nd.changedAtMs ? t(nd.changedAtMs) : 'before the update' })]);
    const rows = evidence(s);
    IL.keyed(V.nowEvi, JSON.stringify(rows), () => rows.map(([k, name, r]) => el('tr', { 'data-k': k }, el('td', { text: name }), el('td', { class: 'v', text: r.v }),
      el('td', { class: 'how', text: r.how }), el('td', { class: 'until', text: r.until }))));
  }

  function renderWhich() {
    const all = live(), m = mounted();
    const list = (m ? [m] : []).concat(all.filter((x) => !m || x.key !== m.key));
    const sel = selected();
    IL.keyed(V.kinds, JSON.stringify([list.map((x) => [x.key, x.name, x.mainPhoto && x.mainPhoto.id, x.form]), sel && sel.key, m && m.key]), () => {
      const cards = list.map((x) => {
        const on = m && x.key === m.key;
        const bent = x.form === 'bent';
        const b = el('button', { type: 'button', class: 'kind', 'data-key': x.key, 'aria-pressed': String(!!sel && sel.key === x.key) },
          el('div', { class: 'kind__img' }, IL.tips.thumb(x, 'big', true)), on ? el('span', { class: 'tag tag--next', text: 'On now' }) : null,
          el('div', { class: 'kind__t', text: x.name }), el('div', { class: 'kind__s', text: x.summary || '' }),
          el('div', { class: 'kind__path' }, on ? el('b', { text: 'A fresh tip of this kind' }) : null, on ? el('br') : null,
            el('span', { text: (on ? '≈ 7 min' : '≈ 9 min') + ' · you: swap it' + (bent ? ', point the bend as in its picture' : '') + ', read the gauge' })));
        b.addEventListener('click', () => choose(x.key));
        return b;
      });
      const add = el('button', { type: 'button', class: 'kind kind--new', id: 'tip-kind-new' }, el('div', { class: 'kind__img' }, IL.icon('i-plus')),
        el('div', { class: 'kind__t', text: 'A new kind' }), el('div', { class: 'kind__s', text: 'Add it from its package: gauge, length, straight or bent, taper.' }),
        el('div', { class: 'kind__path', text: '≈ 30 min · you: swap it, read the gauge, one weighing' }));
      add.addEventListener('click', () => IL.tips.openAdd({}));
      return cards.concat([add]);
    });
  }

  function renderSwap(s) {
    const sel = selected();
    const done = !!W.mountedTok && W.mountedTok === token() && mounted() && sel && mounted().key === sel.key;
    IL.setText(V.swapTitle, done ? 'The tip is on' : 'Swap the tip');
    IL.keyed(V.swapRail, String(done), () => rail(done ? ['done', 'done', 'need', 'wait', 'wait', 'wait', 'wait'] : ['done', 'need', 'wait', 'wait', 'wait', 'wait', 'wait'],
      [sel ? sel.name : '', done ? t(token()) : 'your turn', done ? 'your turn' : 'next', '', '', '', '']));
    // switching on or off, or a vent that failed, is not off; while a task runs or OpenPnP moves the head, or the air is
    // not off, the hands-on steps give way to why (a task can start from another tab meanwhile)
    const air = !!(s.pressure && s.pressure.state && s.pressure.state !== 'off');
    const why = handsLock(s, true);
    IL.keyed(V.swapMeta, JSON.stringify([done, air, !!why]), () => [el('span', { text: air ? 'The pressure is not off: switch it off first.' : 'Air off.' }),
      done || !why ? el('b', { text: done ? 'Now the gauge.' : 'Nothing moves until you press It’s on.' }) : null]);
    IL.show(V.swapHow, !why);
    IL.show(V.swapWait, !!why);
    IL.setText(V.swapWaitWhy, why || '');
    IL.keyed(V.swapPic, sel ? sel.key + (sel.mainPhoto ? sel.mainPhoto.id : '') : '', () => (sel ? IL.tips.thumb(sel, 'big', true) : null));
    IL.keyed(V.swapHow, sel ? sel.key + sel.form + sel.bendToward : '', () => [
      el('li', {}, el('span', {}, 'Unscrew the old tip.', el('small', { text: 'Paste may drip: hold a wipe under it.' }))),
      el('li', {}, el('span', { text: 'Wipe the thread.' })),
      el('li', {}, el('span', { text: 'Screw on the ' + (sel ? sel.name : 'new tip') + (sel && sel.form === 'bent' ? ', its bend pointing as in its picture' + (sel.bendToward ? ' (toward ' + sel.bendToward + ')' : '') : '') + ', finger tight.' })),
      el('li', {}, el('span', { text: 'Take off its cap.' }))]);
  }

  // ================================================================================== the checks
  const STEPS = [
    ['below', 'Look from below', 'the tip’s end against its picture, air off'],
    ['height', 'Height', ''],
    ['prime', 'Prime over the waste', ''],
    ['drip', 'Drip test', ''],
    ['lands', 'Where it lands', 'dots on the test blank'],
    ['exact', 'Exact height', 'a short row of dots at falling heights'],
    ['short', 'Short dot check', 'compared with the kind’s known dot sizes'],
    ['accept', 'Accept', 'every value stored with its evidence'],
  ];
  function checkStates(s) {
    const nd = (s.dispense || {}).needle || {};
    const st = {};
    st.below = W.below === 'running' ? ['now', 'taking the picture from below…'] : W.below === 'done' ? ['done', 'picture taken ' + t(W.belowAt)]
      : W.below && W.below.error ? ['todo', W.below.error] : ['todo', 'the machine takes a picture from below (it moves to the bottom camera)'];
    st.height = nd.anchor && nd.anchored && nd.touchedOff ? ['done', (isNum(nd.anchor.rawSumMm) ? 'from K, the side camera' : 'both tips touching') + ', ' + t(nd.anchor.setAtMs) + '; the bed switch once fitted']
      : ['hand', 'By hand first: record the needle height frame, then confirm its offset (Machine › By hand › Setup › Needle Z frame); where it lands waits for both. The machine measures it itself with the bed switch (requested).'];
    st.prime = ['hand', 'By hand today: Setup › Pressure and prime. The machine primes by itself once it can see the flow (requested).'];
    st.drip = ['hand', 'By hand today: watch the tip for a minute with the screw still. The machine checks it itself once built (requested).'];
    const job = W.job, L = nd.landing || {};
    const mine = !!job && !!L.jobId && L.jobId === job.id;   // the landing in use is this check's: saved, or its question answered
    st.lands = nd.xyChecked && (W.lands === 'done' || mine) ? ['done', 'checked ' + t(L.setAtMs)]
      : W.lands === 'running' ? ['now', job && job.step ? 'running: ' + (job.step.text || job.step.key || job.step) : 'running…']
        : W.lands && W.lands.error ? ['todo', W.lands.error] : nd.xyChecked ? ['done', 'checked ' + t(L.setAtMs)] : ['todo', 'dots on the test blank, then where they land'];
    st.exact = ['notyet', 'Not built yet: the backend picks the steps per way in (requested, BR-51).'];
    st.short = ['notyet', 'Not built yet (BR-51). Meanwhile Calibrate › Pad calibration › Measure its dots measures the dose in full.'];
    st.accept = ['notyet', 'Not built yet: the accept rules are Process’s; the backend applies them (requested).'];
    return st;
  }
  async function startChecks() {
    const s = S.status, sel = mounted();
    if (!sel || W.running) return;
    W.running = true;
    W.err = null;
    IL.render();
    try {
      W.below = 'running';
      IL.render();
      try {
        // a STOP answers ok false (takePicture throws its message); a picture still being taken answers finished false
        const res = await IL.tips.takePicture([]);
        if (!res || res.ok !== true) throw new Error(res && res.finished === false ? 'Still taking the picture: the checks stopped here. Look at the machine.' : 'No picture was stored.');
        W.below = 'done';
        W.belowAt = Date.now() + S.skewMs;
      } catch (e) {
        W.below = { error: IL.errorText(e) };
        return;   // nothing more moves after a picture that did not finish
      }
      IL.render();
      const nd = ((S.status || {}).dispense || {}).needle || {};
      if (!(nd.anchor && nd.anchored && nd.touchedOff)) {   // the engine refuses the landing check before the height and its offset (I4)
        W.lands = { error: 'Waits for the height: record the needle height frame and confirm its offset by hand first (Machine › By hand › Setup), then start the checks again.' };
        return;
      }
      W.lands = 'running';
      IL.render();
      const reg = IL.attest.valid('regulator', s), life = IL.attest.valid('paste-life', s);
      const body = { measure: 'landing', save: true };
      if (reg) body.regulator = { psi: Number(reg.value), atMs: Math.round(reg.atMs + S.skewMs) };
      if (life) body.pasteLifeAtMs = Math.round(life.atMs + S.skewMs);
      try {
        const r = await IL.api('POST', '/api/pad-calibrations', body, { timeoutMs: 60000 });
        W.job = r;
        // a read that fails on the way (no answer for a moment) is tried again; ten in a row end the tracking
        for (let i = 0, misses = 0; i < 400; i++) {
          await new Promise((ok) => setTimeout(ok, 1500));
          try {
            W.job = await IL.api('GET', '/api/pad-calibrations/' + encodeURIComponent(r.id));
            misses = 0;
          } catch (e) {
            if (++misses >= 10) throw e;
            continue;
          }
          IL.render();
          if (W.job.state !== 'running') break;
        }
        // done only when it was saved: a check that found no dot, did not fit or asks whether this tip is on saved nothing
        const res = W.job.result || {}, L = res.landing || {};
        W.lands = L.saved === true ? 'done' : { error: L.question || res.sentence || L.text || 'The landing check ended: ' + W.job.state, ask: !!L.question };
      } catch (e) { W.lands = { error: IL.errorText(e) }; }
    } finally {
      W.running = false;
      IL.render();
    }
  }

  function renderChecks(s) {
    const st = checkStates(s);
    const ok = (k) => st[k][0] === 'done';
    IL.keyed(V.chkRail, JSON.stringify([ok('below'), ok('lands'), W.running]), () => rail(['done', 'done', IL.attest.valid('regulator', s) ? 'done' : 'need', ok('below') ? 'done' : W.running ? 'live' : 'now',
      ok('lands') ? 'done' : W.running && ok('below') ? 'live' : 'wait', 'wait', 'wait'], [mounted() ? mounted().name : '', t(token()), '', '', '', '', '']));
    IL.keyed(V.chkLeave, String(W.running), () => (W.running ? leave('You can leave now', 'The machine checks what it can by itself. It asks you here if a check needs you.')
      : leave('Start the checks when your hands are clear', 'Then you can leave. The steps the machine can’t do yet say what to do by hand.', true)));
    // the check's sentence; a question (is this tip on?) is answered in the pad calibration's view of the job, and once
    // answered there the step says it is checked
    const res = W.job && W.job.result, L = (res && res.landing) || {};
    const good = W.lands === 'done' || ok('lands');
    const text = res && res.sentence && !(ok('lands') && L.question && !L.saved) ? res.sentence : null;
    const ask = !good && W.lands && W.lands.ask ? W.job.id : null;
    IL.keyed(V.chkResult, JSON.stringify([text, good, ask]), () => (text ? el('div', { class: 'notebox' + (good ? '' : ' notebox--need'), id: 'tip-checks-result' },
      el('span', { text }), ask ? el('div', { class: 'btn-row' }, answerButton(ask)) : null) : null));
  }
  /** Answer it: the landing check's question in the pad calibration's own view of that job (Save it, or another tip is on). */
  function answerButton(id) {
    const b = el('button', { type: 'button', class: 'btn btn--sm', id: 'tip-landing-answer' }, 'Answer it…');
    b.addEventListener('click', () => IL.go('#tip/checks?panel=pad-cal&job=' + encodeURIComponent(id)));
    return b;
  }

  // ================================================================================== the panel
  function panelNow(p, s, first) {
    if (!first) return;
    const ready = el('div', { class: 'notebox', text: 'Board runs use these settings. Nobody waits for a review.' });
    const go = el('a', { class: 'btn btn--primary btn--lg btn--block', href: '#run' }, IL.icon('i-run'), 'Make a board');
    p.body.append(ready);
    p.foot.append(go);
  }
  function panelWhich(p, s, first) {
    const sel = selected();
    if (first) {
      V.pTo = el('div', { class: 'panel__lead', id: 'tipwhich-to' });
      V.pSteps = el('ol', { class: 'rsteps' });
      V.pNote = el('div', { class: 'notebox' });
      p.body.append(V.pTo, V.pSteps, V.pNote);
      V.pPark = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-tip-park' }, IL.icon('i-home'), 'Park for the swap');
      V.pPark.addEventListener('click', () => IL.act(V.pPark, async () => {
        // the machine is the user's first (BR-72): no agent can take it while the hands are on the tip (Park waits while
        // one holds it, so this never stops an agent's work)
        if (S.status && S.status.lock) await IL.api('POST', '/api/lock/take', {});
        // asked in every state (switching on is not "on"); the swap waits for OpenPnP's answer "off"
        const r = await IL.api('POST', '/api/pressure/stop');
        if (!r || r.state !== 'off') {
          throw new Error(r && r.state === 'vent_failed' ? 'The pressure is NOT off: the valve did not confirm. Press Pressure off or STOP again.'
            : 'The pressure is not off yet: press Park for the swap again in a moment.');
        }
        W.mountedTok = null;
        IL.go('#tip/swap');
      }, { ok: 'Pressure off. Swap the tip.' }));
      p.foot.setAttribute('data-refusals', '');
      p.foot.append(V.pPark, el('div', { class: 'cap', style: 'margin:0', text: 'Today it takes the machine for you and switches the pressure off. Move the needle over the waste in OpenPnP; the machine does it itself once it can (requested, BR-49).' }));
    }
    const same = sameKind();
    IL.setText(V.pTo, sel ? 'To: ' + (same ? 'a fresh ' : 'the ') + sel.name + '.' : 'Pick a kind.');
    IL.keyed(V.pSteps, String(same) + (sel && sel.key), () => [
      ['The machine parks', 'switches the air off (the move to the waste comes with BR-49)', 'machine'],
      ['You swap the tip', 'then press It’s on', 'you'],
      ['You read the gauge', 'type what it shows', 'you'],
      ['The machine checks it', 'what it can today; the rest is by hand', 'machine']].map(([a, b, who], i) => el('li', { class: 'rstep' }, el('span', { class: 'st st--sm st--num', text: String(i + 1) }),
      el('div', {}, el('div', { class: 'rstep__t', text: a }), el('div', { class: 'rstep__d', text: b })), who === 'you' ? el('span', { class: 'tag tag--you', text: 'you' }) : el('span', { class: 'icode', text: 'machine' }))));
    IL.setText(V.pNote, same ? 'A fresh tip of the same kind keeps the kind’s dose settings when its short row of dots matches them (that check comes with BR-51).' : 'Another kind: its own settings load as a start; the checks then confirm them.');
    IL.gate(V.pPark, handsLock(s, false));   // Park switches the air off itself
  }
  function panelSwap(p, s, first) {
    const sel = selected();
    if (first) {
      V.pOn = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-tip-on' }, 'It’s on');
      V.pOn.addEventListener('click', () => IL.act(V.pOn, async () => {
        const k = selected();
        if (!k) throw new Error('No kind chosen');
        const r = await IL.api('POST', '/api/tip-setups/' + encodeURIComponent(k.key) + '/mount', { why: sameKind() ? 'a new needle (Tip › Swap)' : 'mounted in Tip › Swap' });
        W.mountedTok = isNum(r && r.needleChangedAtMs) ? Number(r.needleChangedAtMs) : null;   // the new needle's token
        IL.pollNow();
        await IL.tips.load();
        W.below = null; W.lands = null; W.job = null;
        IL.render();
        return r;
      }, { ok: () => 'Noted: the ' + (selected() ? selected().name : 'tip') + ' is on. Now the gauge.' }));
      V.pGauge = el('input', { class: 'input', id: 'tip-gauge', inputmode: 'decimal', autocomplete: 'off', 'aria-label': 'The gauge reads, psi' });
      V.pGaugeBtn = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-tip-gauge' }, 'Done');
      V.pGaugeBtn.addEventListener('click', () => {
        const v = Number(String(V.pGauge.value).replace(',', '.'));
        if (!isFinite(v) || v <= 0 || v > 60) { IL.toast('Type what the gauge shows, in psi.', 'need'); return; }
        IL.attest.set('regulator', v);
        IL.go('#tip/checks');
      });
      V.pGaugeBox = el('div', { class: 'card', style: 'padding:14px' }, el('div', { class: 'gauge' }, V.pGaugeT = el('div', { class: 'gauge__t' }), V.pGauge), el('div', { class: 'btn-row', style: 'margin-top:10px' }, V.pGaugeBtn));
      V.pSwapCard = el('div', { class: 'ri ri--need', 'data-refusals': '' });
      p.body.append(V.pSwapCard, el('div', { class: 'section-k' }, 'Next · Regulator', el('span', { class: 'rule' })), V.pGaugeBox,
        el('div', { class: 'note-line muted', style: 'font-size:13px', text: 'At It’s on, where the needle lands and its height go out of date. No board runs until the new tip passes.' }));
    }
    const done = !!W.mountedTok && W.mountedTok === token();
    const psi = ((s.dispense || {}).profile || {}).pressurePsi;
    const why = handsLock(s, true);   // It's on comes after the hands: never while the head may move or the air is not off
    IL.keyed(V.pSwapCard, JSON.stringify([done, sel && sel.key, why]), () => [IL.glyph(done ? 'ok' : 'need', 'sm'),
      el('div', {}, el('div', { class: 'ri__t', text: done ? 'It’s on: the ' + (sel ? sel.name : 'tip') : 'Swap the tip' }),
        el('div', { class: 'ri__d', text: done ? 'Now read the gauge below.' : why || 'The machine waits for you. The air is off.' })),
      el('div', { class: 'ri__aside' }), done ? null : el('div', { class: 'ri__act' }, V.pOn)]);
    IL.gate(V.pOn, why);
    IL.setText(V.pGaugeT, 'Turn the knob to ' + IL.fmtPsi(psi) + ' psi');
    if (!V.pGaugeT.querySelector('small')) V.pGaugeT.append(el('small', { text: 'The pump runs with the valve closed. Type what the gauge shows.' }));
    V.pGauge.disabled = !done;
    V.pGaugeBtn.disabled = !done;
    V.pGaugeBox.classList.toggle('later', !done);
  }
  function panelChecks(p, s, first) {
    if (first) {
      V.pList = el('ol', { class: 'phases', id: 'tip-checks' });
      V.pBarrel = el('div');
      p.body.append(V.pBarrel, V.pList);
      V.pStart = el('button', { type: 'button', class: 'btn btn--primary btn--lg btn--block', id: 'btn-tip-checks-start' }, IL.icon('i-play'), 'Start the checks');
      V.pStart.addEventListener('click', () => startChecks());
      p.foot.setAttribute('data-refusals', '');
      p.foot.append(V.pStart, el('div', { class: 'cap', style: 'margin:0', text: 'It moves to the bottom camera, then lays dots on the test blank. STOP stops it at once (F9).' }));
    }
    const st = checkStates(s);
    IL.keyed(V.pList, JSON.stringify(st), () => STEPS.map(([k, name, d]) => {
      const [state, detail] = st[k];
      const g = state === 'done' ? IL.glyph('ok', 'xs') : state === 'now' ? el('span', { class: 'st st--xs st--live' }, el('span', { class: 'pulse', style: 'width:6px;height:6px' }))
        : state === 'hand' ? el('span', { class: 'st st--xs st--you', text: '' }) : el('span', { class: 'st st--xs st--wait', text: '' });
      const go = k === 'height' && state === 'hand' ? el('a', { class: 'link', href: '#setup/needle', id: 'tip-height-hand', text: 'Record it…' }) : null;
      return el('li', { class: { done: '', now: 'now', todo: 'todo', hand: 'hand', notyet: 'notyet' }[state], 'data-k': k, 'data-state': state }, g,
        el('div', {}, el('div', { class: 'ph__t', text: name + (state === 'hand' ? ' · by hand' : state === 'notyet' ? ' · not built yet' : '') }), el('div', { class: 'ph__d', text: detail || d })), el('span', { class: 'ph__v' }, go));
    }));
    const life = IL.attest.valid('paste-life', s);
    IL.keyed(V.pBarrel, String(!!life), () => (life ? null : (() => {
      const same = el('button', { type: 'button', class: 'btn', id: 'tip-barrel-same' }, 'Same barrel');
      const fresh = el('button', { type: 'button', class: 'btn', id: 'tip-barrel-new' }, 'New barrel');
      same.addEventListener('click', () => IL.attest.set('paste-life', { barrel: 'same' }));
      fresh.addEventListener('click', () => IL.attest.set('paste-life', { barrel: 'new' }));
      return el('div', { class: 'ri ri--need' }, IL.glyph('need', 'sm'), el('div', {}, el('div', { class: 'ri__t', text: 'The paste barrel' }),
        el('div', { class: 'ri__d', text: 'The checks lay dots: same barrel as before, or a new one? Both mean the paste is within its working life.' })), el('div', { class: 'ri__aside' }),
        el('div', { class: 'ri__act' }, same, fresh));
    })()));
    const lock = W.running ? 'The checks are running' : !life ? 'Answer the barrel question first' : !mounted() ? 'No tip named' : IL.lockReason ? IL.lockReason(s, { connected: true, idle: true }) : null;
    IL.gate(V.pStart, lock || null);
  }

  // ================================================================================== the workspace
  IL.workspace('tip', {
    mount(root) {
      mountViews(root);
      IL.tips.load();
    },
    show() { IL.tips.load(); },
    route(r) {
      const v = ['which', 'swap', 'checks'].includes(r.section) ? r.section : 'now';
      if (v !== W.view) { W.view = v; IL.panel.reset(); }
      for (const k of ['now', 'which', 'swap', 'checks']) V[k].hidden = k !== v;
      IL.render();
    },
    render(s) {
      if (!IL.tips.data()) return;
      if (W.view === 'now') renderNow(s);
      else if (W.view === 'which') renderWhich(s);
      else if (W.view === 'swap') renderSwap(s);
      else renderChecks(s);
    },
    panel(p, s, first) {
      // a direct load: the status can come before the tips; the whole panel is built once they are read
      if (!IL.tips.data()) { W.panelWaits = true; return; }
      if (W.panelWaits) {
        W.panelWaits = false;
        if (!first) { IL.panel.reset(); return; }
      }
      if (first) {
        const title = { now: 'Ready?', which: 'Change the tip', swap: 'Your turn', checks: 'Checks' }[W.view];
        p.body.append(el('div', { class: 'panel__title' }, el('h2', { text: title })));
      }
      if (W.view === 'now') panelNow(p, s, first);
      else if (W.view === 'which') panelWhich(p, s, first);
      else if (W.view === 'swap') panelSwap(p, s, first);
      else panelChecks(p, s, first);
    },
    nav(s) { const v = IL.tips.cellView(s); return v ? { sub: Array.isArray(v.v) ? v.v.join('') : String(v.v || ''), kind: v.kind === 'ok' ? 'ok' : v.kind === 'block' ? 'block' : v.kind === 'need' ? 'need' : '' } : {}; },
  });
})();
