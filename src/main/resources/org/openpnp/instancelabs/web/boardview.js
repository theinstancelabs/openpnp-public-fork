/* boardview.js: the to-scale board preview (SVG in machine mm, board +Y up) shared by Run (hero, probe overlay) and
   Records (done-dot map). Spec docs/instancelabs/UI_DESIGN.md §8.1 (hero), §8.1.3 (probe overlay), §15 ("Board SVG":
   built once per plan, polls only toggle classes).

   Classes kept for the tests: rect.pad, rect.pad.dirty, circle.dot, circle.dot.done; added: rect.pad.pasted,
   rect.pad.check, .pv-next, .pv-first, .pv-path, .pv-path--done, .pv-hull, .pv-probe, .pv-needle. Styles: run.css (.pv).

   API (IL.boardview):
     const bv = IL.boardview.create(hostElement, {id: 'plan-svg', aria, onPad(padId, ev)})
     bv.setPlan(plan, {origin: {x, y}})  build once per plan (null clears)
     bv.update({done, dirty, next, check, needle, n1, probe})  cheap: toggles classes, moves markers
         done   Set|Array of full-plan dot indexes          dirty  Map padId -> bright share (or null)
         next   dot index (energised ring + "Next D9.1")      check  {index, label} amber ring (in progress at STOP)
         needle {x, y} a needle crosshair (optional)          n1     {x, y} live N1 crosshair ("N1 now", probe)
         probe  {targets: [{x, y, done}], points: [{x, y}], margin}  probe targets + covered area (inflated hull)
     bv.setView('dots' | 'board' | 'follow'), bv.zoomBy(+1 | -1), bv.view, bv.zoom, bv.layers(set), bv.relayout()
         'follow' centres a 26 × 18 mm window on the needle when given, else on the next dot
   Geometry helpers: IL.boardview.hull(points), .inflate(points, r), .outside(hull, x, y). */
'use strict';
(function () {
  const ZOOMS = [1, 1.6, 2.5, 4, 6.4];
  const f = (v) => (Math.round(v * 1000) / 1000).toString();
  const svg = IL.svgEl;   // (tag, attrs, title): falsy attrs skipped, title as <title>
  function toggle(node, cls, on) {
    if (!node) return;
    const has = node.classList.contains(cls);
    if (on && !has) node.classList.add(cls);
    else if (!on && has) node.classList.remove(cls);
  }

  // ------------------------------------------------------------------------------------------ geometry (mm)
  /** Convex hull (monotone chain) of [{x, y}] → [{x, y}] counter-clockwise. */
  function hull(points) {
    const pts = points.map((p) => ({ x: p.x, y: p.y })).sort((a, b) => a.x - b.x || a.y - b.y);
    if (pts.length < 3) return pts;
    const cross = (o, a, b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    const lower = [], upper = [];
    for (const p of pts) {
      while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop();
      lower.push(p);
    }
    for (let i = pts.length - 1; i >= 0; i--) {
      const p = pts[i];
      while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop();
      upper.push(p);
    }
    return lower.slice(0, -1).concat(upper.slice(0, -1));
  }
  /** The hull of points grown by r mm (Minkowski sum with a 32-gon): the area a margin of r covers. */
  function inflate(points, r) {
    if (!points.length) return [];
    const ring = [];
    for (const p of points) {
      for (let i = 0; i < 32; i++) {
        const a = (i / 32) * Math.PI * 2;
        ring.push({ x: p.x + r * Math.cos(a), y: p.y + r * Math.sin(a) });
      }
    }
    return hull(ring);
  }
  /** Distance of (x, y) outside a convex hull (0 inside). Hull with < 3 points: distance to the nearest point/segment. */
  function outside(h, x, y) {
    if (!h.length) return Infinity;
    if (h.length === 1) return Math.hypot(x - h[0].x, y - h[0].y);
    let inside = h.length >= 3;
    let best = Infinity;
    for (let i = 0; i < h.length; i++) {
      const a = h[i], b = h[(i + 1) % h.length];
      const ex = b.x - a.x, ey = b.y - a.y;
      if (h.length >= 3 && ex * (y - a.y) - ey * (x - a.x) < 0) inside = false;
      const L = ex * ex + ey * ey;
      const t = L ? Math.max(0, Math.min(1, ((x - a.x) * ex + (y - a.y) * ey) / L)) : 0;
      best = Math.min(best, Math.hypot(x - (a.x + t * ex), y - (a.y + t * ey)));
      if (h.length === 2) break;
    }
    return inside ? 0 : best;
  }
  function bbox(list) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const p of list) {
      const r = p.r || 0;
      x0 = Math.min(x0, p.x - r); x1 = Math.max(x1, p.x + r);
      y0 = Math.min(y0, p.y - r); y1 = Math.max(y1, p.y + r);
    }
    if (!Number.isFinite(x0)) return null;
    return { x0, y0, x1, y1 };
  }
  const grow = (b, m) => ({ x0: b.x0 - m, y0: b.y0 - m, x1: b.x1 + m, y1: b.y1 + m });
  /**
   * Least-squares plane z = a + b·x + c·y through ≥ 3 points [{x, y, z}] (centred, so machine coordinates stay well
   * conditioned): {a, b, c, residuals, worst, slope, minZ, maxZ}, or null for < 3 points or points on a line.
   */
  function planeFit(pts) {
    if (!pts || pts.length < 3) return null;
    const n = pts.length;
    const mx = pts.reduce((a, q) => a + q.x, 0) / n, my = pts.reduce((a, q) => a + q.y, 0) / n, mz = pts.reduce((a, q) => a + q.z, 0) / n;
    let sxx = 0, sxy = 0, syy = 0, sxz = 0, syz = 0;
    for (const q of pts) { const dx = q.x - mx, dy = q.y - my, dz = q.z - mz; sxx += dx * dx; sxy += dx * dy; syy += dy * dy; sxz += dx * dz; syz += dy * dz; }
    const det = sxx * syy - sxy * sxy;
    if (Math.abs(det) < 1e-9 * Math.max(1, sxx * syy)) return null;
    const b = (sxz * syy - syz * sxy) / det, c = (syz * sxx - sxz * sxy) / det;
    const a = mz - b * mx - c * my;
    const residuals = pts.map((q) => q.z - (a + b * q.x + c * q.y));
    const zs = pts.map((q) => q.z);
    return { a, b, c, residuals, worst: Math.max(...residuals.map(Math.abs)), slope: Math.hypot(b, c), minZ: Math.min(...zs), maxZ: Math.max(...zs) };
  }

  // ------------------------------------------------------------------------------------------ the view
  class BoardView {
    constructor(host, opts) {
      const o = opts || {};
      this.host = host;
      this.opts = o;
      this.view = 'dots';
      this.zoomIx = 0;
      this.hidden = new Set();
      this.state = {};
      this.svg = svg('svg', { id: o.id || null, class: 'pv', role: 'img', 'aria-label': o.aria || 'Board preview',
        preserveAspectRatio: 'xMidYMid meet', viewBox: '0 0 100 60' });
      const L = (name) => { const g = svg('g', { class: 'pv-l pv-l-' + name }); this.svg.appendChild(g); return g; };
      this.gBoard = L('board');
      this.gHull = L('hull');
      this.gPath = L('path');
      this.gPads = L('pads');
      this.gDots = L('dots');
      this.gMarks = L('marks');
      this.gProbe = L('probe');
      this.gCall = L('callouts');
      host.appendChild(this.svg);
      this.svg.addEventListener('click', (e) => {
        const pad = e.target && e.target.closest && e.target.closest('rect.pad');
        if (pad && this.opts.onPad) this.opts.onPad(pad.dataset.pad, e);
      });
      if (window.ResizeObserver) {
        this.ro = new ResizeObserver(() => this.relayout());
        this.ro.observe(host);
      }
    }

    /** Builds the board for a plan (once per plan). ctx.origin: the board's 0,0 corner in machine mm (file boards). */
    setPlan(plan, ctx) {
      const c = ctx || {};
      const id = plan ? plan.planId : null;
      if (id === this.planId && plan) return;
      this.planId = id;
      this.plan = plan || null;
      this.state = {};
      this.dotEls = [];
      this.padEls = new Map();
      this.padDots = new Map();
      for (const g of [this.gBoard, this.gHull, this.gPath, this.gPads, this.gDots, this.gMarks, this.gProbe, this.gCall]) g.replaceChildren();
      this.zoomIx = 0;
      this.probeBox = null;
      if (!plan) { this.base = null; this.applyViewBox(); return; }
      const pads = plan.pads || [];
      const dots = plan.dots || [];
      // extents: pads with their half diagonal, dots, and the board origin when known
      const padPts = pads.map((p) => ({ x: p.x, y: p.y, r: Math.hypot(p.w || 0, p.h || 0) / 2 }));
      const dotPts = dots.map((d) => ({ x: d.x, y: d.y, r: 0.3 }));
      this.dotsBox = bbox(padPts.concat(dotPts)) || { x0: 0, y0: 0, x1: 10, y1: 10 };
      const ext = c.origin && Number.isFinite(c.origin.x) && Number.isFinite(c.origin.y)
        ? bbox(padPts.concat(dotPts, [{ x: c.origin.x, y: c.origin.y }])) : this.dotsBox;
      this.boardBox = grow(ext, 2);
      // board substrate and a 5 mm grid aligned to the origin (or to whole millimetres)
      const bb = this.boardBox;
      this.gBoard.appendChild(svg('rect', { class: 'pv-board', x: f(bb.x0), y: f(-bb.y1), width: f(bb.x1 - bb.x0), height: f(bb.y1 - bb.y0), rx: 1.2 }));
      const ox = c.origin && Number.isFinite(c.origin.x) ? c.origin.x : 0;
      const oy = c.origin && Number.isFinite(c.origin.y) ? c.origin.y : 0;
      let d = '';
      for (let gx = ox + Math.ceil((bb.x0 - ox) / 5) * 5; gx <= bb.x1; gx += 5) d += 'M' + f(gx) + ' ' + f(-bb.y1) + 'V' + f(-bb.y0);
      for (let gy = oy + Math.ceil((bb.y0 - oy) / 5) * 5; gy <= bb.y1; gy += 5) d += 'M' + f(bb.x0) + ' ' + f(-gy) + 'H' + f(bb.x1);
      if (d) this.gBoard.appendChild(svg('path', { class: 'pv-grid', d }));
      // path in dot order (the planned order), then the done part on top
      if (dots.length > 1) {
        this.gPath.appendChild(svg('path', { class: 'pv-path', d: 'M' + dots.map((x) => f(x.x) + ' ' + f(-x.y)).join(' L') }));
      }
      this.donePath = svg('path', { class: 'pv-path pv-path--done', d: '' });
      this.gPath.appendChild(this.donePath);
      // pads to scale
      for (const p of pads) {
        const r = svg('rect', {
          class: 'pad' + (p.blocked ? ' blocked' : ''), 'data-pad': p.padId, x: f(-(p.w || 0.5) / 2), y: f(-(p.h || 0.5) / 2),
          width: f(p.w || 0.5), height: f(p.h || 0.5), rx: f(Math.min(p.w || 0.5, p.h || 0.5) * 0.1),
          transform: 'translate(' + f(p.x) + ' ' + f(-p.y) + ') rotate(' + f(-(p.rotation || 0)) + ')',
        }, p.padId + ' · ' + (p.packageId || '') + ' · ' + (p.typeKey || 'no pad type') + (p.typeStatus ? ' (' + p.typeStatus + ')' : '')
          + '\n' + IL.fmt(p.w, 2) + ' × ' + IL.fmt(p.h, 2) + ' mm · ' + IL.plural(p.dotCount || 1, 'dot')
          + (p.flags && p.flags.length ? '\n' + p.flags.join(', ') : '') + (p.blocked ? '\nCannot be dispensed (pad-type status)' : ''));
        this.gPads.appendChild(r);
        this.padEls.set(p.padId, r);
      }
      // dots: radius to scale but never below ~2 px at the fit (re-sized once the real size is known)
      this.vb = this.targetBox();
      this.measure();
      const rDot = this.dotRadius();
      this.rDot = rDot;
      for (const x of dots) {
        const el = svg('circle', { class: 'dot', cx: f(x.x), cy: f(-x.y), r: f(rDot), 'data-index': x.index },
          'dot ' + (x.index + 1) + ' · ' + x.padId + ' · ' + IL.fmt(x.rotationDeg, 1) + '° · Z ' + IL.fmt(x.dispenseZ, 3));
        this.gDots.appendChild(el);
        this.dotEls[x.index] = el;
        if (!this.padDots.has(x.padId)) this.padDots.set(x.padId, []);
        this.padDots.get(x.padId).push(x.index);
      }
      if (dots.length) {
        const d0 = dots[0];
        this.first = svg('circle', { class: 'pv-first', cx: f(d0.x), cy: f(-d0.y), r: f(rDot * 2.8) }, 'First dot: ' + d0.padId);
        this.gMarks.appendChild(this.first);
      }
      this.halo = svg('circle', { class: 'pv-next-halo', r: f(rDot * 5) });
      this.nextRing = svg('circle', { class: 'pv-next', r: f(rDot * 2.8) });
      this.checkRing = svg('circle', { class: 'pv-check', r: f(rDot * 4.4) });
      this.needle = svg('g', { class: 'pv-needle' }, 'Needle (N2) now');
      this.needle.append(svg('circle', { r: f(rDot * 3.2) }), svg('path', { d: 'M' + f(-rDot * 6) + ' 0H' + f(rDot * 6) + 'M0 ' + f(-rDot * 6) + 'V' + f(rDot * 6) }));
      this.n1 = svg('g', { class: 'pv-n1' }, 'N1 now');
      this.n1.append(svg('circle', { r: f(rDot * 4) }), svg('path', { d: 'M' + f(-rDot * 8) + ' 0H' + f(rDot * 8) + 'M0 ' + f(-rDot * 8) + 'V' + f(rDot * 8) }));
      for (const n of [this.halo, this.nextRing, this.checkRing, this.needle, this.n1]) { n.setAttribute('visibility', 'hidden'); this.gMarks.appendChild(n); }
      this.applyViewBox();
    }

    /** View box for the current view, zoom and centre (machine mm, flipped Y). */
    targetBox() {
      if (!this.plan) return null;
      let b = this.view === 'board' ? grow(this.boardBox, 3) : grow(this.dotsBox, 2.5);
      // Dots: a board edge within 10 mm of the fit is taken in, so the board never looks cut flat on one side
      if (this.view !== 'board' && this.boardBox) {
        const bb = this.boardBox, NEAR = 10;
        if (b.x0 - bb.x0 > 0 && b.x0 - bb.x0 < NEAR) b.x0 = bb.x0 - 1;
        if (bb.x1 - b.x1 > 0 && bb.x1 - b.x1 < NEAR) b.x1 = bb.x1 + 1;
        if (b.y0 - bb.y0 > 0 && b.y0 - bb.y0 < NEAR) b.y0 = bb.y0 - 1;
        if (bb.y1 - b.y1 > 0 && bb.y1 - b.y1 < NEAR) b.y1 = bb.y1 + 1;
      }
      if (this.probeBox) b = { x0: Math.min(b.x0, this.probeBox.x0), y0: Math.min(b.y0, this.probeBox.y0), x1: Math.max(b.x1, this.probeBox.x1), y1: Math.max(b.y1, this.probeBox.y1) };
      if (this.view === 'follow') {
        const n = this.state.needle || this.nextXY();
        const w = 26, h = 18;
        if (n) b = { x0: n.x - w / 2, x1: n.x + w / 2, y0: n.y - h / 2, y1: n.y + h / 2 };
      }
      const z = ZOOMS[this.zoomIx] || 1;
      if (z > 1) {
        const c = this.zoomCentre() || { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 };
        const w = (b.x1 - b.x0) / z, h = (b.y1 - b.y0) / z;
        // keep the zoomed box inside the full box where possible
        let cx = Math.max(b.x0 + w / 2, Math.min(b.x1 - w / 2, c.x));
        let cy = Math.max(b.y0 + h / 2, Math.min(b.y1 - h / 2, c.y));
        if (this.view === 'follow') { cx = c.x; cy = c.y; }
        b = { x0: cx - w / 2, x1: cx + w / 2, y0: cy - h / 2, y1: cy + h / 2 };
      }
      return b;
    }
    zoomCentre() {
      const s = this.state;
      if (this.view === 'follow' && s.needle) return s.needle;
      if (s.next !== null && s.next !== undefined) return this.nextXY();
      if (s.check) return this.dotXY(s.check.index);
      if (s.dirty && s.dirty.size && s.dirty.size < 20) {
        const p = this.padOf(s.dirty.keys().next().value);
        if (p) return p;
      }
      return null;
    }
    dotXY(i) { const d = this.plan && this.plan.dots[i]; return d ? { x: d.x, y: d.y } : null; }
    nextXY() { const s = this.state; return s.next !== null && s.next !== undefined ? this.dotXY(s.next) : null; }
    padOf(id) { const p = this.plan && (this.plan.pads || []).find((x) => x.padId === id); return p || null; }

    applyViewBox() {
      const b = this.targetBox();
      const vb = b ? [f(b.x0), f(-b.y1), f(b.x1 - b.x0), f(b.y1 - b.y0)].join(' ') : '0 0 100 60';
      if (this.svg.getAttribute('viewBox') !== vb) this.svg.setAttribute('viewBox', vb);
      this.vb = b;
      this.measure();
      this.drawCallouts(true);
    }
    /** mm per screen px at the current view box (preserveAspectRatio meet). */
    measure() {
      const r = this.svg.getBoundingClientRect();
      const b = this.vb;
      this.real = r.width > 20 && r.height > 20;
      const w = this.real ? r.width : 600, h = this.real ? r.height : 360;
      this.k = b ? Math.max((b.x1 - b.x0) / w, (b.y1 - b.y0) / h) : 0.1;
      return this.k;
    }
    relayout() {
      const k0 = this.k;
      const real0 = this.real;
      this.measure();
      if (!this.plan) return;
      if (!real0 && this.real && this.zoomIx === 0) this.resizeMarks();
      if (!k0 || Math.abs(this.k - k0) / k0 > 0.04) this.drawCallouts(true);
    }
    dotRadius() { return Math.max(0.2, Math.min(0.45, 2.3 * (this.k || 0.1))); }
    /** Dot and marker radii follow the fitted scale (first layout happens after the build when the page was hidden). */
    resizeMarks() {
      const r = this.dotRadius();
      if (Math.abs(r - this.rDot) < 1e-3) return;
      this.rDot = r;
      for (const c of this.dotEls) if (c) c.setAttribute('r', f(r));
      if (this.first) this.first.setAttribute('r', f(r * 2.8));
      this.halo.setAttribute('r', f(r * 5));
      this.nextRing.setAttribute('r', f(r * 2.8));
      this.checkRing.setAttribute('r', f(r * 4.4));
    }
    setView(v) {
      if (this.view === v) return;
      this.view = v;
      this.zoomIx = 0;
      this.applyViewBox();
    }
    zoomBy(step) {
      const z = Math.max(0, Math.min(ZOOMS.length - 1, this.zoomIx + step));
      if (z === this.zoomIx) return false;
      this.zoomIx = z;
      this.applyViewBox();
      return true;
    }
    get zoom() { return ZOOMS[this.zoomIx]; }
    get canZoomIn() { return this.zoomIx < ZOOMS.length - 1; }
    get canZoomOut() { return this.zoomIx > 0; }
    /** Hidden layers: set of 'pads' | 'dots' | 'path' | 'dirty'. */
    layers(hidden) {
      this.hidden = new Set(hidden || []);
      for (const n of ['pads', 'dots', 'path', 'dirty']) toggle(this.svg, 'pv--no-' + n, this.hidden.has(n));
    }

    /** Patches the overlays; every argument is optional, only changes touch the DOM. */
    update(st) {
      if (!this.plan) return;
      const s = st || {};
      const prev = this.state;
      const next = Object.assign({}, prev);
      // done dots and pasted pads
      const done = s.done instanceof Set ? s.done : new Set(s.done || []);
      let sum = 0, max = -1;
      for (const i of done) { sum += i; if (i > max) max = i; }
      const doneKey = done.size + ':' + max + ':' + sum;
      if (doneKey !== prev.doneKey) {
        for (let i = 0; i < this.dotEls.length; i++) toggle(this.dotEls[i], 'done', done.has(i));
        for (const [pid, idx] of this.padDots) toggle(this.padEls.get(pid), 'pasted', idx.every((i) => done.has(i)));
        const ord = Array.from(done).sort((a, b) => a - b);
        const dots = this.plan.dots;
        this.donePath.setAttribute('d', ord.length > 1 ? 'M' + ord.map((i) => f(dots[i].x) + ' ' + f(-dots[i].y)).join(' L') : '');
        if (this.first) this.first.setAttribute('visibility', done.size ? 'hidden' : 'visible');
        next.doneKey = doneKey;
      }
      // dirty pads (clean-pad check)
      const dirty = s.dirty instanceof Map ? s.dirty : new Map();
      const dirtyKey = Array.from(dirty.keys()).join(',');
      if (dirtyKey !== prev.dirtyKey) {
        for (const [pid, r] of this.padEls) toggle(r, 'dirty', dirty.has(pid));
        next.dirtyKey = dirtyKey;
        next.calloutsDirty = true;
      }
      next.dirty = dirty;
      // in progress at STOP (amber ring), next dot (energised ring), needle and N1 crosshairs
      const chk = s.check && this.dotXY(s.check.index) ? s.check : null;
      const chkKey = chk ? chk.index + '|' + chk.label : '';
      if (chkKey !== prev.checkKey) {
        for (const r of this.padEls.values()) toggle(r, 'check', false);
        if (chk) {
          const d = this.plan.dots[chk.index];
          toggle(this.padEls.get(d.padId), 'check', true);
          this.place(this.checkRing, d);
        }
        this.checkRing.setAttribute('visibility', chk ? 'visible' : 'hidden');
        next.checkKey = chkKey;
        next.calloutsDirty = true;
      }
      next.check = chk;
      const nx = s.next !== null && s.next !== undefined && this.plan.dots[s.next] ? s.next : null;
      if (nx !== prev.next) {
        if (nx !== null) { this.place(this.nextRing, this.plan.dots[nx]); this.place(this.halo, this.plan.dots[nx]); }
        this.nextRing.setAttribute('visibility', nx !== null ? 'visible' : 'hidden');
        this.halo.setAttribute('visibility', nx !== null ? 'visible' : 'hidden');
        next.next = nx;
        next.calloutsDirty = true;
      }
      next.needle = s.needle && Number.isFinite(s.needle.x) ? s.needle : null;
      this.placeG(this.needle, next.needle);
      next.n1 = s.n1 && Number.isFinite(s.n1.x) ? s.n1 : null;
      this.placeG(this.n1, next.n1);
      // probe targets and covered area
      const pk = s.probe ? JSON.stringify([s.probe.targets, s.probe.points, s.probe.margin]) : '';
      if (pk !== prev.probeKey) {
        this.drawProbe(s.probe);
        next.probeKey = pk;
      }
      if (this.pendingBox) { this.pendingBox = false; this.state = next; this.applyViewBox(); }
      this.state = next;
      const moved = next.needle && (!prev.needle || Math.hypot(next.needle.x - prev.needle.x, next.needle.y - prev.needle.y) > 0.5);
      if (this.view === 'follow' && (moved || (!next.needle && nx !== prev.next))) this.applyViewBox();
      else if (this.zoomIx > 0 && nx !== prev.next && nx !== null) this.applyViewBox();
      else if (next.calloutsDirty) this.drawCallouts();
      next.calloutsDirty = false;
    }
    place(node, d) { node.setAttribute('cx', f(d.x)); node.setAttribute('cy', f(-d.y)); }
    placeG(g, p) {
      if (!g) return;
      if (p) IL.setAttr(g, 'transform', 'translate(' + f(p.x) + ' ' + f(-p.y) + ')');
      IL.setAttr(g, 'visibility', p ? 'visible' : 'hidden');   // diff writes: no attribute churn on every poll
    }

    drawProbe(pr) {
      this.gHull.replaceChildren();
      this.gProbe.replaceChildren();
      const pb0 = this.probeBox;
      // the view keeps the targets and the margin-inflated area in sight
      const all = pr ? (pr.targets || []).concat(pr.points || []).map((q) => ({ x: q.x, y: q.y, r: (pr.margin || 3.5) + 0.8 })) : [];
      this.probeBox = all.length ? bbox(all) : null;
      if (JSON.stringify(pb0) !== JSON.stringify(this.probeBox)) this.pendingBox = true;
      if (!pr) return;
      const pts = pr.points || [];
      if (pts.length) {
        const area = inflate(pts, pr.margin || 3.5);
        this.gHull.appendChild(svg('polygon', { class: 'pv-hull', points: area.map((p) => f(p.x) + ',' + f(-p.y)).join(' ') },
          'Probed area grown by the ' + IL.fmt(pr.margin || 3.5, 1) + ' mm margin: dots inside it take their height from the surface'));
      }
      const r = 1.25;
      (pr.targets || []).forEach((t, i) => {
        const g = svg('g', { class: 'pv-probe pv-probe--' + (t.done ? 'done' : t.next ? 'next' : 'todo'), transform: 'translate(' + f(t.x) + ' ' + f(-t.y) + ')' },
          'Probe target ' + (i + 1) + ': X ' + IL.fmt(t.x, 2) + ' · Y ' + IL.fmt(t.y, 2) + (t.done ? ' (probed)' : ''));
        g.append(svg('circle', { r: f(r) }), svg('path', { d: 'M-2 0H-0.6M0.6 0H2M0 -2V-0.6M0 0.6V2' }));
        const tx = svg('text', { x: '1.7', y: '-1.5' });
        tx.textContent = String(i + 1);
        g.appendChild(tx);
        this.gProbe.appendChild(g);
      });
    }

    /** Callouts (labels with leader lines) sized in screen px: dirty pads (worst 6), next dot, in progress at STOP. */
    drawCallouts(force) {
      if (!this.plan) return;
      const s = this.state;
      const key = [s.dirtyKey, s.next, s.checkKey, this.k && this.k.toFixed(4), this.view, this.zoomIx].join('|');
      if (!force && key === this.calloutKey) return;
      this.calloutKey = key;
      this.gCall.replaceChildren();
      for (const n of this.gMarks.querySelectorAll('.pv-dirty-ring')) n.remove();
      const k = this.k || 0.1;
      const list = [];
      const dirty = s.dirty || new Map();
      const pads = this.plan.pads || [];
      if (dirty.size && dirty.size < pads.length) {
        const worst = Array.from(dirty.entries()).sort((a, b) => (a[1] === null ? -1 : b[1] === null ? 1 : a[1] - b[1])).slice(0, 6);
        for (const [pid, share] of worst) {
          const p = this.padOf(pid);
          if (!p) continue;
          const ring = svg('circle', { class: 'pv-dirty-ring', cx: f(p.x), cy: f(-p.y), r: f(Math.max(1.15, Math.hypot(p.w || 0, p.h || 0) * 0.7)) });
          this.gMarks.insertBefore(ring, this.gMarks.firstChild);
          list.push({ x: p.x, y: p.y, text: pid + (share === null || share === undefined ? '' : '  ' + Number(share).toFixed(2)), kind: 'dirty' });
        }
      }
      if (s.next !== null && s.next !== undefined) {
        const d = this.plan.dots[s.next];
        if (d) list.push({ x: d.x, y: d.y, text: 'Next  ' + d.padId, kind: 'next' });
      }
      if (s.check) {
        const d = this.plan.dots[s.check.index];
        if (d) list.push({ x: d.x, y: d.y, text: s.check.label, kind: 'check' });
      }
      const placed = [];
      for (const c of list) this.gCall.appendChild(this.callout(c, k, placed));
    }
    callout(c, k, placed) {
      const fs = 11.5 * k;                       // font size: ~11.5 px on screen
      const w = c.text.length * fs * 0.6 + fs * 1.2;
      const h = fs * 1.75;
      const gap = Math.max(1.6, 14 * k);
      const vb = this.vb || { x0: -1e9, x1: 1e9, y0: -1e9, y1: 1e9 };
      const boxes = {
        down: { x: c.x - w / 2, y: c.y - gap - h, lx: c.x, ly: c.y - gap },   // machine y: down = smaller y
        up: { x: c.x - w / 2, y: c.y + gap, lx: c.x, ly: c.y + gap },
        right: { x: c.x + gap, y: c.y - h / 2, lx: c.x + gap, ly: c.y },
        left: { x: c.x - gap - w, y: c.y - h / 2, lx: c.x - gap, ly: c.y },
      };
      const fits = (b) => b.x >= vb.x0 && b.x + w <= vb.x1 && b.y >= vb.y0 && b.y + h <= vb.y1;
      const clear = (b) => placed.every((p) => b.x + w < p.x || p.x + p.w < b.x || b.y + h < p.y || p.y + p.h < b.y);
      const order = ['down', 'right', 'up', 'left'];
      let side = order.find((sd) => fits(boxes[sd]) && clear(boxes[sd])) || order.find((sd) => fits(boxes[sd])) || 'down';
      const b = boxes[side];
      placed.push({ x: b.x, y: b.y, w, h });
      const g = svg('g', { class: 'pv-callout pv-callout--' + c.kind });
      g.appendChild(svg('path', { d: 'M' + f(c.x) + ' ' + f(-c.y) + 'L' + f(b.lx) + ' ' + f(-b.ly) }));
      g.appendChild(svg('rect', { x: f(b.x), y: f(-(b.y + h)), width: f(w), height: f(h), rx: f(h * 0.22) }));
      const t = svg('text', { x: f(b.x + w / 2), y: f(-(b.y + h / 2) + fs * 0.36), 'text-anchor': 'middle', 'font-size': f(fs) });
      t.textContent = c.text;
      g.appendChild(t);
      return g;
    }
  }

  IL.boardview = {
    create(host, opts) { return new BoardView(host, opts); },
    hull, inflate, outside, bbox, planeFit,
  };
})();
