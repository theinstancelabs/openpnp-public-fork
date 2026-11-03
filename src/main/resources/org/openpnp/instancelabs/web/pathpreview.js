/* pathpreview.js: the path preview of the Run hero (spec docs/instancelabs/BOARD_IMPORT_DESIGN.md §5.6). Plays, pauses and
   scrubs the plan's dispense order on the board view: a ghost needle moves dot to dot and the dots appear as they would
   be dispensed, on the clock of the plan's estimate (dots[i].ta / .te, seconds from the program start). Nothing moves:
   it is a drawing only.

   API (IL.pathPreview):
     const pp = IL.pathPreview.create({host, bv, button, before, onChange})
         host    element that receives the row #run-preview (collapsed until the preview starts)
         before  optional child of host to insert the row before (else appended)
         bv      the IL.boardview view to draw on (bv.preview)
         button  optional #pv-play button to wire (else one is made: pp.button, for the caller to mount)
         onChange(state)  after every start, pause and end: {active, playing, v, n}
     pp.setPlan(plan)   the plan shown (a new planId ends the preview)
     pp.allow(on)       false: the button hides and the preview ends (live, stopped, a task runs)
     pp.play(), pp.pause(), pp.clear(), pp.seek(v)   v = dots done (0…N)
     pp.state           {active, playing, v, n, tau, speed}
     pp.destroy()
   Scrub value v (0…N) = the number of dots done: dots [0, v) are drawn, the ghost sits on dot index v (dot v + 1),
   which the readout names. Without an estimate the times come from the path at 50 mm/s and 0.6 s per dot. */
'use strict';
(function () {
  const el = IL.el;
  const SPEEDS = [1, 4, 16];
  const FRAME_MS = 1000 / 30;          // at most 30 updates per second
  const SYN_MM_S = 50, SYN_DOT_S = 0.6;

  /** Per-dot arrive / end times (s): the plan's estimate, or illustrative ones from the path. */
  function times(plan) {
    const dots = (plan && plan.dots) || [];
    const sum = (plan && plan.summary) || {};
    const ok = dots.length > 0 && !sum.estimateError && dots.every((d) => Number.isFinite(d.ta) && Number.isFinite(d.te) && d.te >= d.ta);
    if (ok) return { ta: dots.map((d) => d.ta), te: dots.map((d) => d.te), synthetic: false };
    const ta = [], te = [];
    let t = 0;
    dots.forEach((d, i) => {
      if (i) t += Math.hypot(d.x - dots[i - 1].x, d.y - dots[i - 1].y) / SYN_MM_S;
      ta.push(t);
      t += SYN_DOT_S;
      te.push(t);
    });
    return { ta, te, synthetic: true };
  }
  /** Number of dots with te ≤ tau (te ascending). */
  function doneAt(te, tau) {
    let lo = 0, hi = te.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (te[mid] <= tau + 1e-9) lo = mid + 1; else hi = mid; }
    return lo;
  }
  function pauseIcon() {
    const s = IL.svgEl('svg', { class: 'ic', viewBox: '0 0 24 24', 'aria-hidden': 'true' });
    s.append(IL.svgEl('path', { d: 'M8.5 6v12M15.5 6v12' }));
    return s;
  }
  const clock = (s) => IL.fmtClock(Math.max(0, Math.round(s)));

  class PathPreview {
    constructor(o) {
      this.o = o || {};
      this.bv = this.o.bv;
      this.allowed = true;
      this.plan = null;
      this.T = null;
      this.tau = 0;
      this.v = 0;
      this.active = false;
      this.playing = false;
      this.speed = 4;
      this.button = this.o.button || el('button', { type: 'button', class: 'btn btn--sm', id: 'pv-play' });
      this.button.setAttribute('aria-pressed', 'false');
      this.button.addEventListener('click', () => (this.playing ? this.pause() : this.play()));
      this.toggleBtn = el('button', { type: 'button', class: 'btn btn--sm btn--icon pv-preview__toggle', 'aria-label': 'Play the preview' });
      this.toggleBtn.addEventListener('click', () => (this.playing ? this.pause() : this.play()));
      this.scrub = el('input', { type: 'range', id: 'pv-scrub', class: 'pv-preview__scrub', min: '0', max: '0', step: '1', value: '0',
        'aria-label': 'Preview position: dots done' });
      this.scrub.addEventListener('input', () => { this.pause(); this.seek(Number(this.scrub.value)); });
      this.scrub.addEventListener('keydown', (e) => {
        if (e.key !== 'PageUp' && e.key !== 'PageDown') return;
        e.preventDefault();
        this.pause();
        this.seek(this.v + (e.key === 'PageUp' ? 10 : -10));
      });
      this.speedSeg = el('div', { class: 'seg', id: 'pv-speed', role: 'group', 'aria-label': 'Preview speed' },
        SPEEDS.map((x) => el('button', { type: 'button', 'data-speed': String(x), 'aria-pressed': String(x === this.speed),
          onclick: () => this.setSpeed(x) }, x + '×')));
      this.readout = el('span', { class: 'pv-readout', id: 'pv-readout' });
      this.reset = el('button', { type: 'button', class: 'link', id: 'pv-reset' }, 'Clear');
      this.reset.addEventListener('click', () => {
        this.clear();
        if (!this.button.hidden && this.button.isConnected) this.button.focus();   // the row (and Clear) just went away
      });
      this.row = el('div', { class: 'pv-preview', id: 'run-preview', role: 'group', 'aria-label': 'Path preview: nothing moves', hidden: true },
        this.toggleBtn, this.scrub, this.speedSeg, this.readout, this.reset);
      if (this.o.host) this.o.host.insertBefore(this.row, this.o.before && this.o.before.parentNode === this.o.host ? this.o.before : null);
      this.onVis = () => { if (document.hidden && this.playing) this.pause(); };
      document.addEventListener('visibilitychange', this.onVis);
      this.paintButtons();
    }

    get state() { return { active: this.active, playing: this.playing, v: this.v, n: this.n(), tau: this.tau, speed: this.speed }; }
    n() { return this.plan && this.plan.dots ? this.plan.dots.length : 0; }

    /** The plan shown; a new planId ends the preview. */
    setPlan(plan) {
      const id = plan ? plan.planId : null;
      if (this.plan && id === this.plan.planId) { this.plan = plan; return; }
      if (this.active) this.clear();
      this.plan = plan || null;
      this.T = plan ? times(plan) : null;
      this.scrub.max = String(this.n());
      this.paintButtons();
    }
    /** false: hidden and ended (a task runs, live, stopped). */
    allow(on) {
      this.allowed = !!on;
      if (!this.allowed && this.active) this.clear();
      this.paintButtons();
    }
    /** Plays from the current position (from the start when at the end). */
    play() {
      const N = this.n();
      if (!this.allowed || !N || !this.bv) return;
      if (!this.active) this.open();
      if (this.v >= N) this.seek(0);
      this.playing = true;
      this.last = performance.now();
      this.acc = 0;
      this.paintButtons();
      this.tick();
      this.changed();
    }
    pause(quiet) {
      if (!this.playing) return;
      this.playing = false;
      if (this.raf) cancelAnimationFrame(this.raf);
      this.raf = null;
      this.paintButtons();
      if (!quiet) this.changed();
    }
    /** Ends the preview: the drawing goes back to the plan's state and the row collapses. */
    clear() {
      this.pause(true);
      this.active = false;
      this.v = 0;
      this.tau = 0;
      if (this.bv) this.bv.preview(null);
      IL.show(this.row, false);
      this.paintButtons();
      this.changed();
    }
    open() {
      this.active = true;
      IL.show(this.row, true);
      this.seek(0);
    }
    /** v dots done (pauses nothing by itself): the clock goes to the arrival at dot v (or the end). */
    seek(v) {
      const N = this.n();
      if (!N) return;
      if (!this.active) this.open();
      const k = Math.max(0, Math.min(N, Math.round(Number(v) || 0)));
      this.tau = k < N ? this.T.ta[k] : this.T.te[N - 1];
      this.draw();
    }
    setSpeed(x) {
      this.speed = x;
      for (const b of this.speedSeg.children) b.setAttribute('aria-pressed', String(Number(b.dataset.speed) === x));
    }
    tick() {
      this.raf = requestAnimationFrame((now) => {
        if (!this.playing) return;
        const dt = Math.min(250, now - this.last);
        this.last = now;
        this.acc += dt;
        this.tau += dt / 1000 * this.speed;
        const end = this.T.te[this.n() - 1];
        if (this.tau >= end) {
          this.tau = end;
          this.draw();
          this.pause();
          return;
        }
        if (this.acc >= FRAME_MS) { this.acc = 0; this.draw(); }
        this.tick();
      });
    }
    /** Ghost position and dots done at the clock tau. */
    draw() {
      const N = this.n(), T = this.T, dots = this.plan.dots;
      const v = doneAt(T.te, this.tau);
      let ghost = null;
      if (v >= N) ghost = { x: dots[N - 1].x, y: dots[N - 1].y };
      else if (v === 0 || T.ta[v] <= this.tau) ghost = { x: dots[v].x, y: dots[v].y };
      else {
        const a = dots[v - 1], b = dots[v];
        const span = T.ta[v] - T.te[v - 1];
        const u = span > 0 ? Math.max(0, Math.min(1, (this.tau - T.te[v - 1]) / span)) : 1;
        ghost = { x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u };
      }
      this.v = v;
      this.bv.preview({ n: v, ghost });
      if (String(v) !== this.scrub.value) this.scrub.value = String(v);
      const text = this.text(v);
      IL.setText(this.readout, text);
      IL.setAttr(this.scrub, 'aria-valuetext', text);
      IL.setAttr(this.readout, 'title', text + '\n' + this.summary() + this.leadNote());
    }
    /** "Dot 81 of 160 · R12.1 · ½ turn · Z 3.02 mm · 0:48 of 1:36", "All 160 dots · 1:36". */
    text(v) {
      const N = this.n(), T = this.T, p = this.plan;
      const total = T.te[N - 1] - T.ta[0];
      const tail = T.synthetic ? ' · no time estimate: illustrative timing' : '';
      if (v >= N) return 'All ' + N + ' dots · ' + clock(total) + tail;
      const d = p.dots[v];
      const prof = p.profile || {};
      return 'Dot ' + (v + 1) + ' of ' + N + ' · ' + d.padId + ' · ' + IL.turnLabel(prof.netStepsPerDot, prof.stepsPerRev)
        + ' · Z ' + IL.fmt(d.dispenseZ, 2) + ' mm · ' + clock(this.tau - T.ta[0]) + ' of ' + clock(total) + tail;
    }
    /** "Path 160 dots · 601 mm · dots ≈ 1:36 (whole board ≈ 2:06)". */
    summary() {
      const N = this.n();
      if (!N) return '';
      const s = this.plan.summary || {};
      const T = this.T;
      return 'Path ' + IL.plural(N, 'dot') + (Number.isFinite(s.pathLengthMm) ? ' · ' + IL.fmtInt(s.pathLengthMm) + ' mm' : '')
        + ' · dots ≈ ' + clock(T.te[N - 1] - T.ta[0])
        + (Number.isFinite(s.estimatedSeconds) && !T.synthetic ? ' (whole board ≈ ' + clock(s.estimatedSeconds) + ')' : '');
    }
    leadNote() {
      const T = this.T, p = this.plan;
      if (!T || T.synthetic || !(T.ta[0] > 0.5)) return '';
      const purge = (p.profile && p.profile.purgeDots) || 0;
      return '\n+' + clock(T.ta[0]) + ' before the first dot (pump, valve, ' + IL.plural(purge, 'purge dot') + ', crossing)';
    }
    paintButtons() {
      const can = this.allowed && this.n() > 0 && !!this.bv;
      IL.show(this.button, can);
      this.button.disabled = !can;
      const key = this.playing ? 'pause' : 'play';
      if (this.button.dataset.state !== key) {
        this.button.dataset.state = key;
        this.button.replaceChildren(this.playing ? pauseIcon() : IL.icon('i-play'), el('span', { class: 'pv-play__t', text: this.playing ? 'Pause' : 'Preview path' }));
        this.toggleBtn.replaceChildren(this.playing ? pauseIcon() : IL.icon('i-play'));
        this.toggleBtn.setAttribute('aria-label', this.playing ? 'Pause the preview' : 'Play the preview');
      }
      this.button.setAttribute('aria-pressed', String(this.playing));
      this.button.setAttribute('aria-label', this.playing ? 'Pause the preview' : 'Preview path');
      IL.setAttr(this.button, 'title', this.plan ? (this.summary() + '\nA drawing only: nothing moves.') : null);
    }
    changed() {
      if (this.o.onChange) IL.safe(() => this.o.onChange(this.state), 'path preview');
    }
    destroy() {
      this.clear();
      document.removeEventListener('visibilitychange', this.onVis);
      this.row.remove();
      if (!this.o.button) this.button.remove();
    }
  }

  IL.pathPreview = {
    create(opts) { return new PathPreview(opts); },
    times,
  };
})();
