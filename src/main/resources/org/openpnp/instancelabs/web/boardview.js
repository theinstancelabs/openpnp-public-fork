/* boardview.js: the to-scale board preview (SVG in machine mm, board +Y up) shared by Run (hero, probe overlay), the pad
   inspector, the upload preview and Records (done-dot map). Spec docs/instancelabs/UI_DESIGN.md §8.1 (hero), §8.1.3
   (probe overlay), §15 ("Board SVG": built once per plan, polls only toggle classes); the board picture under the
   overlay: docs/instancelabs/BOARD_IMPORT_DESIGN.md §5.2–5.6.

   Two stacked SVGs in the host (.pv-host): svg.pv.pv-art (the board drawing of boardart.js, aria-hidden, no pointer
   events, its own compositing layer) under svg#plan-svg.pv (the job overlay). Both carry the same viewBox, so overlays,
   the needle and probe marks stay where the board is; the art group is placed by the plan's board → machine affine
   (plan.board.toMachine) and repaints only when the view box changes.

   Classes kept for the tests: rect.pad, rect.pad.dirty, circle.dot, circle.dot.done; added: rect.pad.pasted,
   rect.pad.check, .pv-next, .pv-first, .pv-path, .pv-path--done, .pv-hull, .pv-probe, .pv-needle. No art element uses
   the classes pad or dot, and the casing nodes carry .pv-case and no state class, so the counts are unchanged.
   Styles: run.css (.pv) and board.css (the board palette, .pv--art, casing, preview).

   API (IL.boardview):
     const bv = IL.boardview.create(hostElement, {id: 'plan-svg', aria, onPad(padId, ev), onPart(ref, ev), lite})
         lite: no callouts and no grid (inspector, dialog); the host gets .pv-host--lite (both SVGs fill it)
     bv.setPlan(plan, {origin: {x, y}})  build once per plan (null clears); reads plan.board (toMachine, size,
         fiducials) and plan.dot.diameterMm (dots at their real size, never below 1.5 px, a thin ring when small)
     bv.setArt(art | null)               the board drawing: built once per art; a re-plan only changes its transform
     bv.setBoard({art, toMachine} | null)  art-only mode without a plan (upload preview, the hero before planning)
     bv.setAffine(toMachine)             art-only mode: the seat changed → the transform only
     bv.picture('full' | 'quiet' | 'off')  Off = exactly the drawing without art
     bv.update({done, dirty, next, check, needle, n1, probe, fids, cam, sugg})  cheap: toggles classes, moves markers
         done   Set|Array of full-plan dot indexes          dirty  Map padId -> bright share (or null)
         next   dot index (energised ring + "Next D9.1")      check  {index, label} amber ring (in progress at STOP)
         needle {x, y} a needle crosshair (optional)          n1     {x, y} live N1 crosshair ("N1 now", probe)
         probe  {targets: [{x, y, done, next, bad, alts: [{x, y}]}], points: [{x, y}], margin}  probe targets (alternates
                as small rings, a refused spot struck through) + covered area (inflated hull)
         fids   [{name, x, y, r, found: {x, y} | null, state}] Register board's fiducials (g.pv-l-fids, cased): a dashed
                circle of the search radius r at the expected position, a filled dot at the found one, the name
         cam    {x, y} the live top camera: crosshair and a 5 mm scale bar (also without a plan)
         sugg   [{x, y, d}] suggested dots (padprofile.js): circle.pv-sugg in g.pv-l-sugg, never circle.dot (also without a plan)
     bv.preview({n, ghost: {x, y} | null} | null)  path preview: dots [0, n) get .pv-pre, the ghost needle moves
     bv.select({padIds, refs} | null)   .sel on pads, part outlines in g.pv-l-sel
     bv.dotLabels(indexes | null)       path numbers next to those dots
     bv.fit(box | null)                 a fixed view box in machine mm {x0, y0, x1, y1} (null: the view and zoom below)
     bv.setView('dots' | 'board' | 'follow'), bv.zoomBy(+1 | -1), bv.view, bv.zoom, bv.layers(set), bv.relayout()
         'follow' centres a 26 × 18 mm window on the needle when given, else on the next dot
     bv.destroy()                       disconnects the ResizeObserver, removes both SVGs
   IL.boardview.of(host) → the view in that host. Geometry helpers: IL.boardview.hull(points), .inflate(points, r),
   .outside(hull, x, y), .bbox, .planeFit; .cased(tag, attrs, cls, widthPx, title, kinds) for marks drawn on the board. */
'use strict';
(function () {
  const ZOOMS = [1, 1.6, 2.5, 4, 6.4];
  const LABEL_PX = 9;        // a reference label size bucket shows once its smallest label is this tall on screen
  const DOT_MIN_PX = 1.5;    // dots at their real size, but never below this radius on screen
  const DOT_STROKE_PX = 1.6; // circle.dot's ring (run.css): drawn inside the real size, so the outer edge is the dot's edge
  const DOT_THIN_PX = 3;     // a dot under this radius on screen gets a 1 px ring (.pv--dots-thin), so an undone dot keeps a light centre
  const f = (v) => (Math.round(v * 1000) / 1000).toString();
  const svg = IL.svgEl;   // (tag, attrs, title): falsy attrs skipped, title as <title>
  const VIEWS = new WeakMap();   // host → view
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
  const union = (a, b) => (!a ? b : !b ? a : { x0: Math.min(a.x0, b.x0), y0: Math.min(a.y0, b.y0), x1: Math.max(a.x1, b.x1), y1: Math.max(a.y1, b.y1) });
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
  /** A board → machine affine [m00 m10 m01 m11 m02 m12] (AffineTransform.getMatrix order) that can place the art. */
  const okMatrix = (m) => Array.isArray(m) && m.length === 6 && m.every((v) => Number.isFinite(Number(v)))
    && Math.abs(m[0] * m[3] - m[1] * m[2]) > 1e-9;
  const mapPt = (m, x, y) => ({ x: m[0] * x + m[2] * y + m[4], y: m[1] * x + m[3] * y + m[5] });
  /** Art signature: the same drawing is never built twice in one view (re-plans only move it). */
  const artSig = (a) => (a ? [a.id, a.kind, a.source && a.source.sha256, a.import && a.import.boardDigest, a.size && a.size.join('x'),
    a.stats && a.stats.points, a.stats && a.stats.bytes, a.source && a.source.boardFile].join('|') : null);

  // ------------------------------------------------------------------------------------------ casing (§5.3 rule 2)
  /**
   * A mark with a two-tone casing: g.pv-cased holds a light and a dark copy (.pv-case, no state class) under the coloured
   * shape; board.css draws the copies only under .pv--art (mark width + 3 px light, + 1.5 px dark). widthPx: the mark's
   * stroke width. kinds: extra wrapper classes (pv-cased--<kind>). Returns {g, el, all}.
   */
  function cased(tag, attrs, cls, widthPx, title, kinds) {
    const g = svg('g', { class: 'pv-cased' + (kinds ? ' ' + String(kinds).split(' ').map((k) => 'pv-cased--' + k).join(' ') : '') });
    g.style.setProperty('--cw', (widthPx || 1.5) + 'px');
    const base = Object.assign({}, attrs);
    const l = svg(tag, Object.assign({}, base, { class: 'pv-case pv-case--l' }));
    const d = svg(tag, Object.assign({}, base, { class: 'pv-case pv-case--d' }));
    const el = svg(tag, Object.assign({}, base, { class: cls }), title);
    g.append(l, d, el);
    return { g, el, all: [l, d, el] };
  }
  const setAll = (c, name, v) => { for (const n of c.all) IL.setAttr(n, name, v); };
  /** Casing copies for the shapes of a mark group (needle, ghost, camera, probe target): inserted under them. */
  function caseShapes(g, shapes, widthPx) {
    g.style.setProperty('--cw', (widthPx || 1.5) + 'px');
    const copy = (n, k) => { const c = n.cloneNode(false); c.setAttribute('class', 'pv-case pv-case--' + k); return c; };
    g.append(...shapes.map((n) => copy(n, 'l')), ...shapes.map((n) => copy(n, 'd')), ...shapes);
    return g;
  }
  /** Crosshair shapes of radius r (circle + four arms). */
  const cross = (r, arm) => [svg('circle', { r: f(r) }), svg('path', { d: 'M' + f(-arm) + ' 0H' + f(arm) + 'M0 ' + f(-arm) + 'V' + f(arm) })];

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
      this.pic = 'full';
      this.board = null;      // art-only mode {toMachine}
      this.fixed = null;
      host.classList.add('pv-host');
      if (o.lite) host.classList.add('pv-host--lite');
      this.artSvg = svg('svg', { class: 'pv pv-art', 'aria-hidden': 'true', focusable: 'false', preserveAspectRatio: 'xMidYMid meet',
        viewBox: '0 0 100 60', 'data-mask': 'slate', 'data-silk': 'white', hidden: true });
      this.svg = svg('svg', { id: o.id || null, class: 'pv' + (o.onPart ? ' pv--pick' : ''), role: 'img', 'aria-label': o.aria || 'Board preview',
        preserveAspectRatio: 'xMidYMid meet', viewBox: '0 0 100 60' });
      const L = (name) => { const g = svg('g', { class: 'pv-l pv-l-' + name }); this.svg.appendChild(g); return g; };
      this.gBoard = L('board');
      this.gParts = L('parts');
      this.gHull = L('hull');
      this.gPath = L('path');
      this.gPads = L('pads');
      this.gDots = L('dots');
      this.gSugg = L('sugg');
      this.gSel = L('sel');
      this.gMarks = L('marks');
      this.gFids = L('fids');
      this.gProbe = L('probe');
      this.gCam = L('cam');
      this.gGhost = L('ghost');
      this.gLabels = L('labels');
      this.gCall = L('callouts');
      // live top camera: crosshair and a 5 mm scale bar (the hero before planning)
      this.camG = caseShapes(svg('g', { class: 'pv-cam', visibility: 'hidden' }, 'Top camera now'), cross(1.2, 3), 2);
      this.scaleG = svg('g', { class: 'pv-scale', visibility: 'hidden' }, 'Scale: 5 mm');
      this.scaleIn = this.scaleG.appendChild(svg('g'));
      this.gCam.append(this.camG, this.scaleG);
      host.append(this.artSvg, this.svg);
      VIEWS.set(host, this);
      this.svg.addEventListener('click', (e) => {
        const t = e.target && e.target.closest ? e.target : null;
        const pad = t && t.closest('rect.pad');
        if (pad) { if (this.opts.onPad) this.opts.onPad(pad.dataset.pad, e); return; }
        const part = t && t.closest('rect.pv-part');
        if (part && this.opts.onPart) this.opts.onPart(part.dataset.ref, e);
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
      this.dotSeq = [];
      this.padEls = new Map();
      this.padDots = new Map();
      this.preN = 0;
      this.sel = null;
      this.selKey = '';
      this.selDrawn = null;
      this.labelIdx = null;
      for (const g of [this.gBoard, this.gHull, this.gPath, this.gPads, this.gDots, this.gSel, this.gMarks, this.gFids, this.gProbe, this.gGhost, this.gLabels, this.gCall]) g.replaceChildren();
      toggle(this.svg, 'pv--preview', false);
      this.zoomIx = 0;
      this.probeBox = null;
      this.fidBox = null;
      if (!plan) { this.base = null; this.applyArt(); this.applyViewBox(); return; }
      const pads = plan.pads || [];
      const dots = plan.dots || [];
      const B = plan.board || null;
      const m = this.planMatrix();
      // extents: pads with their half diagonal, dots, the board's fiducials, and the board origin when known
      const padPts = pads.map((p) => ({ x: p.x, y: p.y, r: Math.hypot(p.w || 0, p.h || 0) / 2 }));
      const dotPts = dots.map((d) => ({ x: d.x, y: d.y, r: 0.3 }));
      const fidPts = ((B && B.fiducials) || []).filter((q) => Number.isFinite(q.x) && Number.isFinite(q.y)).map((q) => ({ x: q.x, y: q.y, r: 1.5 }));
      this.dotsBox = bbox(padPts.concat(dotPts, fidPts)) || { x0: 0, y0: 0, x1: 10, y1: 10 };
      const bw = B ? Number(B.widthMm) : 0, bh = B ? Number(B.heightMm) : 0;
      const corners = m && bw > 0 && bh > 0 ? [[0, 0], [bw, 0], [bw, bh], [0, bh]].map(([x, y]) => mapPt(m, x, y)) : null;
      if (corners) {
        // the board rectangle through the plan's affine (also before the drawing arrives)
        this.boardBox = bbox(corners);
        this.drawBoardFrame(m, bw, bh, corners);
      } else {
        const ext = c.origin && Number.isFinite(c.origin.x) && Number.isFinite(c.origin.y)
          ? bbox(padPts.concat(dotPts, fidPts, [{ x: c.origin.x, y: c.origin.y }])) : this.dotsBox;
        this.boardBox = grow(ext, 2);
        // board substrate and a 5 mm grid aligned to the origin (or to whole millimetres)
        const bb = this.boardBox;
        this.gBoard.appendChild(svg('rect', { class: 'pv-board', x: f(bb.x0), y: f(-bb.y1), width: f(bb.x1 - bb.x0), height: f(bb.y1 - bb.y0), rx: 1.2 }));
        const ox = c.origin && Number.isFinite(c.origin.x) ? c.origin.x : 0;
        const oy = c.origin && Number.isFinite(c.origin.y) ? c.origin.y : 0;
        let d = '';
        for (let gx = ox + Math.ceil((bb.x0 - ox) / 5) * 5; gx <= bb.x1; gx += 5) d += 'M' + f(gx) + ' ' + f(-bb.y1) + 'V' + f(-bb.y0);
        for (let gy = oy + Math.ceil((bb.y0 - oy) / 5) * 5; gy <= bb.y1; gy += 5) d += 'M' + f(bb.x0) + ' ' + f(-gy) + 'H' + f(bb.x1);
        if (d && !this.opts.lite) this.gBoard.appendChild(svg('path', { class: 'pv-grid', d }));
      }
      // path in dot order (the planned order), then the done part and the preview part on top; offsets per dot let the
      // preview cut the path at dot n without rebuilding it
      let dFull = '';
      this.dOff = [0];
      dots.forEach((x, i) => { dFull += (i ? 'L' : 'M') + f(x.x) + ' ' + f(-x.y); this.dOff.push(dFull.length); });
      this.dFull = dFull;
      if (dots.length > 1) this.gPath.appendChild(cased('path', { d: dFull }, 'pv-path', 1, null, 'path').g);
      this.doneC = cased('path', { d: '' }, 'pv-path pv-path--done', 1.5, null, 'path done');
      this.donePath = this.doneC.el;
      this.preC = cased('path', { d: '' }, 'pv-path--pre', 1.5, null, 'path pre');
      this.legC = cased('line', { x1: 0, y1: 0, x2: 0, y2: 0 }, 'pv-path--leg', 1.5, null, 'path pre');
      this.gPath.append(this.doneC.g, this.preC.g, this.legC.g);
      IL.setAttr(this.legC.g, 'visibility', 'hidden');
      // pads to scale (the paste openings): corners from the roundness when the plan has it
      for (const p of pads) {
        const w = p.w || 0.5, h = p.h || 0.5;
        const round = Number.isFinite(p.roundness) ? Math.max(0, Math.min(100, p.roundness)) : null;
        const r = svg('rect', {
          class: 'pad' + (p.blocked ? ' blocked' : ''), 'data-pad': p.padId, x: f(-w / 2), y: f(-h / 2),
          width: f(w), height: f(h), rx: f(Math.min(w, h) * (round === null ? 0.1 : round / 200)),
          transform: 'translate(' + f(p.x) + ' ' + f(-p.y) + ') rotate(' + f(-(p.rotation || 0)) + ')',
        }, p.padId + ' · ' + (p.typeKey ? p.typeKey + (p.typeStatus ? ' (' + p.typeStatus + ')' : '') : 'no pad type')
          + ' · ' + IL.fmt(p.w, 2) + ' × ' + IL.fmt(p.h, 2) + ' mm' + (round === null ? '' : ', corners ' + IL.fmt(round, 0) + ' %')
          + ' · ' + IL.plural(p.dotCount || 0, 'dot') + '\n' + (p.packageId || '')
          + (p.flags && p.flags.length ? '\n' + p.flags.join(', ') : '') + (p.blocked ? '\nCannot be dispensed (pad-type status)' : ''));
        this.gPads.appendChild(r);
        this.padEls.set(p.padId, r);
      }
      // dots: at their real size when the plan knows it, else to scale but never below ~2 px at the fit
      this.dotD = plan.dot && Number(plan.dot.diameterMm) > 0 ? Number(plan.dot.diameterMm) : null;
      this.vb = this.targetBox();
      this.measure();
      const rDot = this.dotRadius();
      this.rDot = rDot;
      this.rReal = null;
      for (const x of dots) {
        const el = svg('circle', { class: 'dot', cx: f(x.x), cy: f(-x.y), r: f(rDot), 'data-index': x.index, 'data-d': x.diameterMm > 0 ? x.diameterMm : null },
          'dot ' + (x.index + 1) + ' · ' + x.padId + ' · ' + IL.fmt(x.rotationDeg, 1) + '° · Z ' + IL.fmt(x.dispenseZ, 3));
        this.gDots.appendChild(el);
        this.dotEls[x.index] = el;
        this.dotSeq.push(el);
        if (!this.padDots.has(x.padId)) this.padDots.set(x.padId, []);
        this.padDots.get(x.padId).push(x.index);
      }
      this.firstC = null;
      if (dots.length) {
        const d0 = dots[0];
        this.firstC = cased('circle', { r: f(rDot * 2.8) }, 'pv-first', 1.5, 'First dot: ' + d0.padId, 'first');
        this.placeG(this.firstC.g, d0);
        this.gMarks.appendChild(this.firstC.g);
      }
      this.halo = svg('circle', { class: 'pv-next-halo', r: f(rDot * 5) });
      this.nextC = cased('circle', { r: f(rDot * 2.8) }, 'pv-next', 2.5, null, 'next');
      this.checkC = cased('circle', { r: f(rDot * 4.4) }, 'pv-check', 2.5, null, 'check');
      this.nextRing = this.nextC.el;
      this.checkRing = this.checkC.el;
      this.needle = caseShapes(svg('g', { class: 'pv-needle' }, 'Needle (N2) now'), cross(rDot * 3.2, rDot * 6), 2);
      this.n1 = caseShapes(svg('g', { class: 'pv-n1' }, 'N1 now'), cross(rDot * 4, rDot * 8), 2);
      for (const n of [this.halo, this.nextC.g, this.checkC.g, this.needle, this.n1]) { n.setAttribute('visibility', 'hidden'); this.gMarks.appendChild(n); }
      // the preview needle (a ghost: nothing moves)
      this.ghostG = caseShapes(svg('g', { class: 'pv-ghost', visibility: 'hidden' }, 'Preview needle: nothing moves'), cross(rDot * 3.2, rDot * 6), 2);
      this.gGhost.appendChild(this.ghostG);
      this.applyArt();
      this.applyViewBox();
    }
    /** The board outline and a 5 mm grid in the board frame (no art, or picture Off). */
    drawBoardFrame(m, bw, bh, corners) {
      this.gBoard.appendChild(svg('polygon', { class: 'pv-board', points: corners.map((p) => f(p.x) + ',' + f(-p.y)).join(' ') }));
      let d = '';
      for (let gx = 5; gx < bw; gx += 5) d += 'M' + f(gx) + ' 0V' + f(bh);
      for (let gy = 5; gy < bh; gy += 5) d += 'M0 ' + f(gy) + 'H' + f(bw);
      if (d && !this.opts.lite) this.gBoard.appendChild(svg('path', { class: 'pv-grid', d, transform: IL.boardart.toSvgMatrix(m) }));
    }

    // ---------------------------------------------------------------------------------------- the board drawing
    planMatrix() {
      const B = this.plan && this.plan.board;
      return B && okMatrix(B.toMachine) ? B.toMachine.map(Number) : null;
    }
    /** The affine that places the art now: the plan's, else the art-only seat. */
    matrix() { return this.plan ? this.planMatrix() : (this.board ? this.board.toMachine : null); }
    /** The board drawing (null removes it). Built once per drawing; the transform follows the plan or the seat. */
    setArt(art) {
      const sig = artSig(art);
      if (sig === this.artSig) { this.art = art || null; this.applyArt(); return; }
      this.artSig = sig;
      this.art = art || null;
      this.artRoot = null;
      this.labelMin = null;
      this.artSvg.replaceChildren();
      this.gParts.replaceChildren();
      if (art) {
        const b = IL.boardart.build(art, { uid: this.opts.id || 'pv' });
        this.artRoot = b.root;
        this.labelMin = b.labels;
        this.artSvg.appendChild(b.root);
        const fin = art.finish || {};
        IL.setAttr(this.artSvg, 'data-mask', IL.boardart.MASKS[fin.mask] ? fin.mask : 'slate');
        IL.setAttr(this.artSvg, 'data-silk', IL.boardart.SILKS[fin.silk] ? fin.silk : 'white');
        // hit rects: one per part (a click opens the inspector; pads above them win)
        const why = { fiducial: 'fiducial for Register board', art: 'not dispensed (no paste pads)', dnp: 'do not populate',
          pasteOnly: 'paste only', bottom: 'bottom side: only the top is dispensed' };
        for (const p of b.parts) {
          if (!p || !p.ref || !Array.isArray(p.box)) continue;
          const [x0, y0, x1, y1] = p.box.map(Number);
          const w = Math.max(0.6, x1 - x0), h = Math.max(0.6, y1 - y0);
          const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
          this.gParts.appendChild(svg('rect', { class: 'pv-part', 'data-ref': p.ref, 'data-kind': p.kind || null,
            x: f(cx - w / 2), y: f(cy - h / 2), width: f(w), height: f(h),
            transform: 'translate(' + f(p.x) + ' ' + f(p.y) + ') rotate(' + f(Number(p.a) || 0) + ')' },
          [p.ref, p.pkg, p.value].filter(Boolean).join(' · ') + (why[p.kind] ? '\n' + why[p.kind] : '')));
        }
      }
      this.applyArt();
      this.relabel();
    }
    /** Art-only mode without a plan: {art, toMachine} (the upload preview, the hero before planning); null leaves it. */
    setBoard(b) {
      this.board = b && okMatrix(b.toMachine) ? { toMachine: b.toMachine.map(Number) } : null;
      if (b) this.setArt(b.art || null);
      this.zoomIx = 0;
      this.applyArt();
      this.applyViewBox();
    }
    /** Art-only mode: the seat changed (form edits): the transform and the view only. */
    setAffine(m) {
      if (!this.board || !okMatrix(m)) return;
      this.board.toMachine = m.map(Number);
      this.applyArt();
      this.applyViewBox();
    }
    /** 'full' | 'quiet' | 'off' (§5.3). */
    picture(mode) {
      this.pic = mode === 'quiet' || mode === 'off' ? mode : 'full';
      this.applyArt();
    }
    applyArt() {
      const m = this.matrix();
      const on = !!(this.artRoot && m && this.pic !== 'off');
      IL.show(this.artSvg, on);
      toggle(this.svg, 'pv--art', on);
      if (on) {
        const t = IL.boardart.toSvgMatrix(m);
        IL.setAttr(this.artRoot, 'transform', t);
        IL.setAttr(this.gParts, 'transform', t);
      }
      toggle(this.artSvg, 'pv-art--quiet', this.pic === 'quiet');
      toggle(this.svg, 'pv--quiet', on && this.pic === 'quiet');   // the overlay's marks on the stage-toned mask
      toggle(this.artSvg, 'pv-art--board', !this.plan && !!this.board);
      if (this.sel) this.drawSel();
    }

    /** View box for the current view, zoom and centre (machine mm, flipped Y). */
    targetBox() {
      if (this.fixed) return this.fixed;
      let b;
      if (!this.plan) {
        const m = this.matrix();
        if (!m || !this.art) return null;
        b = grow(IL.boardart.outlineBox(this.art, m), 3);
        const cam = this.state.cam;
        if (cam) b = union(b, grow({ x0: cam.x, y0: cam.y, x1: cam.x, y1: cam.y }, 4));
      } else {
        b = this.view === 'board' ? grow(this.boardBox, 3) : grow(this.dotsBox, 2.5);
        // Dots: a board edge within 10 mm of the fit is taken in, so the board never looks cut flat on one side
        if (this.view !== 'board' && this.boardBox) {
          const bb = this.boardBox, NEAR = 10;
          if (b.x0 - bb.x0 > 0 && b.x0 - bb.x0 < NEAR) b.x0 = bb.x0 - 1;
          if (bb.x1 - b.x1 > 0 && bb.x1 - b.x1 < NEAR) b.x1 = bb.x1 + 1;
          if (b.y0 - bb.y0 > 0 && b.y0 - bb.y0 < NEAR) b.y0 = bb.y0 - 1;
          if (bb.y1 - b.y1 > 0 && bb.y1 - b.y1 < NEAR) b.y1 = bb.y1 + 1;
        }
        if (this.probeBox) b = union(b, this.probeBox);
        if (this.fidBox) b = union(b, this.fidBox);
        if (this.view === 'follow') {
          const n = this.state.needle || this.nextXY();
          const w = 26, h = 18;
          if (n) b = { x0: n.x - w / 2, x1: n.x + w / 2, y0: n.y - h / 2, y1: n.y + h / 2 };
        }
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
      if (!this.plan) return null;
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
      if (this.artSvg.getAttribute('viewBox') !== vb) this.artSvg.setAttribute('viewBox', vb);
      this.vb = b;
      this.measure();
      this.drawCallouts(true);
      this.afterScale();
    }
    /** mm per screen px at the current view box (preserveAspectRatio meet). */
    measure() {
      let r = this.svg.getBoundingClientRect();
      if (!(r.width > 20 && r.height > 20)) r = this.artSvg.getBoundingClientRect();   // overlay hidden (art-only hero)
      const b = this.vb;
      this.real = r.width > 20 && r.height > 20;
      const w = this.real ? r.width : 600, h = this.real ? r.height : 360;
      this.k = b ? Math.max((b.x1 - b.x0) / w, (b.y1 - b.y0) / h) : 0.1;
      this.px = { w, h };
      return this.k;
    }
    relayout() {
      if (this.destroyed) return;
      const k0 = this.k;
      const real0 = this.real;
      this.measure();
      const changed = !k0 || Math.abs(this.k - k0) / k0 > 0.04;
      if (changed || real0 !== this.real) this.afterScale();
      else this.placeScale();   // the visible area's corner moves with the view's size, also at the same scale
      if (!this.plan) return;
      if (!real0 && this.real && this.zoomIx === 0) this.resizeMarks();
      if (changed) this.drawCallouts(true);
    }
    /** Everything sized in screen px: label buckets, real-size dots (their 1.5 px floor and thin ring), path numbers, the scale bar. */
    afterScale() {
      this.relabel();
      this.resizeDots();
      this.drawDotLabels();
      this.placeScale();
    }
    relabel() {
      const k = this.k || 0.1;
      const lm = this.labelMin || {};
      for (const b of ['lb1', 'lb2', 'lb3']) toggle(this.artSvg, 'pv-art--' + b, Number.isFinite(lm[b]) && lm[b] / k >= LABEL_PX);
    }
    dotRadius() { return Math.max(0.2, Math.min(0.45, 2.3 * (this.k || 0.1))); }
    /**
     * Dots at the plan's dot size: the ring is drawn inside it, so what the eye sees (the ring's outer edge) is the real
     * diameter (a 0.6 mm dot on a 0.8 mm pad must not look wider than the pad). A dot under 3 px radius on screen (the
     * whole board) gets a 1 px ring and the circle never goes below 1.5 px (.pv--dots-clamped while that floor applies): with
     * the 1.6 px ring on a 1.2 px circle an undone dot was a solid disc, the same as a done one.
     */
    resizeDots() {
      if (!this.plan || !this.dotD) return;
      const k = this.k || 0.1;
      const thin = this.dotD / 2 < DOT_THIN_PX * k;
      const ring = (thin ? 1 : DOT_STROKE_PX) * k;
      const floor = DOT_MIN_PX * k;
      const inner = this.dotD / 2 - ring / 2;
      const r = Math.max(inner, floor);
      toggle(this.svg, 'pv--dots-thin', thin);
      toggle(this.svg, 'pv--dots-clamped', floor > inner);
      this.dotOuter = r + ring / 2;
      if (this.rReal !== null && Math.abs(r - this.rReal) < 1e-4) return;
      this.rReal = r;
      // a dot's own size when the plan gives it (dots[i].diameterMm: a pad type's own turn), else the plan's dot size
      for (const c of this.dotSeq) c.setAttribute('r', f(c.dataset.d ? Math.max(Number(c.dataset.d) / 2 - DOT_STROKE_PX / 2 * k, floor) : r));
    }
    /** Dot and marker radii follow the fitted scale (first layout happens after the build when the page was hidden). */
    resizeMarks() {
      const r = this.dotRadius();
      if (Math.abs(r - this.rDot) < 1e-3) return;
      this.rDot = r;
      if (!this.dotD) for (const c of this.dotSeq) c.setAttribute('r', f(r));
      if (this.firstC) setAll(this.firstC, 'r', f(r * 2.8));
      this.halo.setAttribute('r', f(r * 5));
      setAll(this.nextC, 'r', f(r * 2.8));
      setAll(this.checkC, 'r', f(r * 4.4));
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
    /** A fixed view box (machine mm), e.g. a part and 1 mm around it in the inspector; null: the view and zoom. */
    fit(box) {
      this.fixed = box && [box.x0, box.y0, box.x1, box.y1].every(Number.isFinite) && box.x1 > box.x0 && box.y1 > box.y0
        ? { x0: box.x0, y0: box.y0, x1: box.x1, y1: box.y1 } : null;
      this.applyViewBox();
    }
    /** Suggested dots at their size (padprofile.js, PROFILE_CALIBRATION_DESIGN.md §13.4); never circle.dot, so dot counts stay the plan's. */
    drawSugg(list) {
      const key = list && list.length ? JSON.stringify(list) : '';
      if (key === this.suggKey) return;
      this.suggKey = key;
      this.gSugg.replaceChildren(...(list || []).map((d) => svg('circle', { class: 'pv-sugg', cx: f(d.x), cy: f(-d.y), r: f(d.d / 2) },
        'Suggested dot · ' + IL.fmt(d.d, 2) + ' mm')));
    }
    /** Hidden layers: set of 'pads' | 'dots' | 'path' | 'dirty'. */
    layers(hidden) {
      this.hidden = new Set(hidden || []);
      for (const n of ['pads', 'dots', 'path', 'dirty']) toggle(this.svg, 'pv--no-' + n, this.hidden.has(n));
    }

    /** Patches the overlays; every argument is optional, only changes touch the DOM. */
    update(st) {
      const s = st || {};
      // the live top camera works without a plan too (the hero before planning)
      const cam = s.cam && Number.isFinite(Number(s.cam.x)) && Number.isFinite(Number(s.cam.y)) ? { x: Number(s.cam.x), y: Number(s.cam.y) } : null;
      const cam0 = this.state.cam || null;
      const camMoved = !!cam !== !!cam0 || (cam && Math.hypot(cam.x - cam0.x, cam.y - cam0.y) > 0.5);
      this.placeG(this.camG, cam);
      IL.setAttr(this.scaleG, 'visibility', cam ? 'visible' : 'hidden');
      if ('sugg' in s) this.drawSugg(s.sugg);
      if (!this.plan) {
        this.state = Object.assign({}, this.state, { cam });
        if (camMoved && this.board) this.applyViewBox();
        return;
      }
      const prev = this.state;
      const next = Object.assign({}, prev, { cam });
      // done dots and pasted pads
      const done = s.done instanceof Set ? s.done : new Set(s.done || []);
      let sum = 0, max = -1;
      for (const i of done) { sum += i; if (i > max) max = i; }
      const doneKey = done.size + ':' + max + ':' + sum;
      if (doneKey !== prev.doneKey) {
        for (let i = 0; i < this.dotEls.length; i++) toggle(this.dotEls[i], 'done', done.has(i));
        for (const [pid, idx] of this.padDots) toggle(this.padEls.get(pid), 'pasted', idx.every((i) => done.has(i)));
        const dots = this.plan.dots;
        // an index past the plan's last dot (another plan's done set) draws nothing rather than breaking the render
        const ord = Array.from(done).filter((i) => !!dots[i]).sort((a, b) => a - b);
        setAll(this.doneC, 'd', ord.length > 1 ? 'M' + ord.map((i) => f(dots[i].x) + ' ' + f(-dots[i].y)).join(' L') : '');
        if (this.firstC) this.firstC.g.setAttribute('visibility', done.size ? 'hidden' : 'visible');
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
          this.placeG(this.checkC.g, d);
        }
        this.checkC.g.setAttribute('visibility', chk ? 'visible' : 'hidden');
        next.checkKey = chkKey;
        next.calloutsDirty = true;
      }
      next.check = chk;
      const nx = s.next !== null && s.next !== undefined && this.plan.dots[s.next] ? s.next : null;
      if (nx !== prev.next) {
        if (nx !== null) { this.placeG(this.nextC.g, this.plan.dots[nx]); this.place(this.halo, this.plan.dots[nx]); }
        this.nextC.g.setAttribute('visibility', nx !== null ? 'visible' : 'hidden');
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
      // Register board's fiducials: search circles and the fiducials found
      const fk = s.fids && s.fids.length ? JSON.stringify(s.fids) : '';
      if (fk !== prev.fidKey) {
        this.drawFids(s.fids);
        next.fidKey = fk;
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
    /**
     * The 5 mm scale bar in the bottom-left corner of the view (shown with the camera crosshair): of what shows (meet
     * leaves room on one side), not of the fitted box, whose corner is the board's own and its origin marker.
     */
    placeScale() {
      const b = this.vb;
      if (!b) return;
      const k = this.k || 0.1;
      const px = this.px || { w: 0, h: 0 };
      const x0 = Math.min(b.x0, (b.x0 + b.x1 - px.w * k) / 2) + 16 * k, y = Math.min(b.y0, (b.y0 + b.y1 - px.h * k) / 2) + 16 * k;
      const key = [x0, y, k].map((v) => v.toFixed(3)).join('|');
      if (key === this.scaleKey) return;
      this.scaleKey = key;
      const d = 'M' + f(x0) + ' ' + f(-y - 4 * k) + 'V' + f(-y) + 'H' + f(x0 + 5) + 'V' + f(-y - 4 * k);
      this.scaleIn.replaceChildren();
      caseShapes(this.scaleIn, [svg('path', { d })], 2);
      const t = svg('text', { x: f(x0 + 2.5), y: f(-y - 7 * k), 'text-anchor': 'middle', 'font-size': f(12 * k) });
      t.textContent = '5 mm';
      this.scaleIn.appendChild(t);
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
        this.gHull.appendChild(cased('polygon', { points: area.map((p) => f(p.x) + ',' + f(-p.y)).join(' ') }, 'pv-hull', 1.4,
          'Probed area grown by the ' + IL.fmt(pr.margin || 3.5, 1) + ' mm margin: dots inside it take their height from the surface', 'hull').g);
      }
      const r = 1.25;
      (pr.targets || []).forEach((t, i) => {
        // the spots tried when this one is not bare or gives no contact (small rings, Register board)
        for (const a of t.alts || []) {
          this.gProbe.appendChild(caseShapes(svg('g', { class: 'pv-probe-alt', transform: 'translate(' + f(a.x) + ' ' + f(-a.y) + ')' },
            'Nearby spot for target ' + (i + 1) + ': X ' + IL.fmt(a.x, 2) + ' · Y ' + IL.fmt(a.y, 2)), [svg('circle', { r: '0.45' })], 1.2));
        }
        const g = svg('g', { class: 'pv-probe pv-probe--' + (t.bad ? 'bad' : t.done ? 'done' : t.next ? 'next' : 'todo'), transform: 'translate(' + f(t.x) + ' ' + f(-t.y) + ')' },
          'Probe target ' + (i + 1) + ': X ' + IL.fmt(t.x, 2) + ' · Y ' + IL.fmt(t.y, 2) + (t.bad ? ' (refused)' : t.done ? ' (probed)' : ''));
        caseShapes(g, [svg('circle', { r: f(r) }), svg('path', { d: t.bad ? 'M-1.6 -1.6L1.6 1.6M-1.6 1.6L1.6 -1.6' : 'M-2 0H-0.6M0.6 0H2M0 -2V-0.6M0 0.6V2' })], 2);
        const tx = svg('text', { x: '1.7', y: '-1.5' });
        tx.textContent = String(i + 1);
        g.appendChild(tx);
        this.gProbe.appendChild(g);
      });
    }

    /**
     * Register board's fiducial layer (g.pv-l-fids, AUTO_REGISTER_DESIGN.md §8.1, BOARD_IMPORT_DESIGN.md §10.2 item 3):
     * per fiducial a dashed circle of the search radius at the expected position and, once found, a filled dot there with
     * a short leg from the expected centre; the name beside it. Every mark is cased (the circles cross the mask).
     */
    drawFids(list) {
      this.gFids.replaceChildren();
      // the view keeps the search circles in sight (a fiducial near the board edge)
      const fb0 = this.fidBox;
      const ok = (list || []).filter((fd) => Number.isFinite(Number(fd.x)) && Number.isFinite(Number(fd.y)));
      this.fidBox = ok.length ? bbox(ok.map((fd) => ({ x: fd.x, y: fd.y, r: (fd.r || 8) + 0.6 }))) : null;
      if (JSON.stringify(fb0) !== JSON.stringify(this.fidBox)) this.pendingBox = true;
      for (const fd of list || []) {
        if (!Number.isFinite(Number(fd.x)) || !Number.isFinite(Number(fd.y))) continue;
        const st = fd.found ? 'found' : fd.state === 'not-found' || fd.state === 'ambiguous' || fd.state === 'not-centred' ? 'bad' : 'todo';
        const g = svg('g', { class: 'pv-fid pv-fid--' + st },
          fd.name + ': expected X ' + IL.fmt(fd.x, 2) + ' · Y ' + IL.fmt(fd.y, 2) + ', searched within ' + IL.fmt(fd.r || 8, 1) + ' mm'
            + (fd.found ? '; found X ' + IL.fmt(fd.found.x, 2) + ' · Y ' + IL.fmt(fd.found.y, 2) : st === 'bad' ? '; not found' : ''));
        g.appendChild(cased('circle', { cx: f(fd.x), cy: f(-fd.y), r: f(fd.r || 8) }, 'pv-fid-search', 1.4, null, 'fid').g);
        g.appendChild(cased('path', { d: 'M' + f(fd.x - 1.2) + ' ' + f(-fd.y) + 'H' + f(fd.x + 1.2) + 'M' + f(fd.x) + ' ' + f(-fd.y - 1.2) + 'V' + f(-fd.y + 1.2) }, 'pv-fid-expect', 1.4, null, 'fid').g);
        const at = fd.found || { x: fd.x, y: fd.y };
        if (fd.found) {
          if (Math.hypot(fd.found.x - fd.x, fd.found.y - fd.y) > 0.05) {
            g.appendChild(cased('line', { x1: f(fd.x), y1: f(-fd.y), x2: f(fd.found.x), y2: f(-fd.found.y) }, 'pv-fid-leg', 1.4, null, 'fid').g);
          }
          g.appendChild(cased('circle', { cx: f(fd.found.x), cy: f(-fd.found.y), r: '0.75' }, 'pv-fid-found', 1.4, null, 'fid').g);
        }
        const tx = svg('text', { class: 'pv-fid-name', x: f(at.x + 1.6), y: f(-at.y - 1.6) });
        tx.textContent = fd.name;
        g.appendChild(tx);
        this.gFids.appendChild(g);
      }
    }

    /** Callouts (labels with leader lines) sized in screen px: dirty pads (worst 6), next dot, in progress at STOP. */
    drawCallouts(force) {
      if (!this.plan || this.opts.lite) return;
      const s = this.state;
      const key = [s.dirtyKey, s.next, s.checkKey, this.k && this.k.toFixed(4), this.view, this.zoomIx].join('|');
      if (!force && key === this.calloutKey) return;
      this.calloutKey = key;
      this.gCall.replaceChildren();
      for (const n of this.gMarks.querySelectorAll('.pv-cased--dirty')) n.remove();
      const k = this.k || 0.1;
      const list = [];
      const dirty = s.dirty || new Map();
      const pads = this.plan.pads || [];
      if (dirty.size && dirty.size < pads.length) {
        const worst = Array.from(dirty.entries()).sort((a, b) => (a[1] === null ? -1 : b[1] === null ? 1 : a[1] - b[1])).slice(0, 6);
        for (const [pid, share] of worst) {
          const p = this.padOf(pid);
          if (!p) continue;
          const ring = cased('circle', { cx: f(p.x), cy: f(-p.y), r: f(Math.max(1.15, Math.hypot(p.w || 0, p.h || 0) * 0.7)) }, 'pv-dirty-ring', 1.5, null, 'dirty');
          this.gMarks.insertBefore(ring.g, this.gMarks.firstChild);
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
      caseShapes(g, [svg('path', { d: 'M' + f(c.x) + ' ' + f(-c.y) + 'L' + f(b.lx) + ' ' + f(-b.ly) })], 1.2);
      g.appendChild(svg('rect', { x: f(b.x), y: f(-(b.y + h)), width: f(w), height: f(h), rx: f(h * 0.22) }));
      const t = svg('text', { x: f(b.x + w / 2), y: f(-(b.y + h / 2) + fs * 0.36), 'text-anchor': 'middle', 'font-size': f(fs) });
      t.textContent = c.text;
      g.appendChild(t);
      return g;
    }

    // ---------------------------------------------------------------------------------------- preview, selection, labels
    /** Path preview (§5.6): st {n: dots done, ghost: {x, y} | null}; null ends it. Only the crossed dots are touched. */
    preview(st) {
      if (!this.plan) return;
      toggle(this.svg, 'pv--preview', !!st);
      const N = this.dotSeq.length;
      const n = st ? Math.max(0, Math.min(N, Math.floor(Number(st.n) || 0))) : 0;
      const n0 = this.preN || 0;
      if (n !== n0) {
        const on = n > n0;
        for (let i = Math.min(n, n0); i < Math.max(n, n0); i++) toggle(this.dotSeq[i], 'pv-pre', on);
        this.preN = n;
        setAll(this.preC, 'd', n > 1 ? this.dFull.slice(0, this.dOff[n]) : '');
      }
      const gh = st && st.ghost && Number.isFinite(st.ghost.x) && Number.isFinite(st.ghost.y) ? st.ghost : null;
      this.placeG(this.ghostG, gh);
      const last = n > 0 ? this.plan.dots[n - 1] : null;
      if (gh && last) {
        setAll(this.legC, 'x1', f(last.x)); setAll(this.legC, 'y1', f(-last.y));
        setAll(this.legC, 'x2', f(gh.x)); setAll(this.legC, 'y2', f(-gh.y));
      }
      IL.setAttr(this.legC.g, 'visibility', gh && last ? 'visible' : 'hidden');
    }
    /** {padIds, refs} | null: .sel on those pads, an outline around those parts. The same selection again does nothing. */
    select(sel) {
      const s = sel && ((sel.padIds && sel.padIds.length) || (sel.refs && sel.refs.length))
        ? { padIds: new Set(sel.padIds || []), refs: new Set(sel.refs || []) } : null;
      const key = s ? Array.from(s.padIds).join(',') + '|' + Array.from(s.refs).join(',') : '';
      if (key === this.selKey) return;
      this.selKey = key;
      this.sel = s;
      if (this.padEls) for (const [pid, r] of this.padEls) toggle(r, 'sel', !!s && s.padIds.has(pid));
      this.drawSel();
      if (this.labelIdx) this.drawDotLabels();   // the selected pad's numbers are placed first
    }
    /** The part outlines of the selection: redrawn only when the selection, the plan, the drawing or its seat changed. */
    drawSel() {
      const m = this.matrix();
      const key = [this.selKey, this.planId, this.artSig, m ? m.join(',') : ''].join('|');
      if (key === this.selDrawn) return;
      this.selDrawn = key;
      this.gSel.replaceChildren();
      if (!this.sel || !this.sel.refs.size) return;
      const parts = this.art && m ? (this.art.parts || []).filter((p) => p && this.sel.refs.has(p.ref) && Array.isArray(p.box)) : [];
      const drawn = new Set();
      if (parts.length) {
        const g = svg('g', { transform: IL.boardart.toSvgMatrix(m) });
        for (const p of parts) {
          const [x0, y0, x1, y1] = this.selBox(p, m);
          const c = cased('rect', { x: f(x0 - 0.3), y: f(y0 - 0.3), width: f(x1 - x0 + 0.6), height: f(y1 - y0 + 0.6), rx: 0.3 }, 'pv-sel', 2, null, 'sel');
          c.g.setAttribute('transform', 'translate(' + f(p.x) + ' ' + f(p.y) + ') rotate(' + f(Number(p.a) || 0) + ')');
          g.appendChild(c.g);
          drawn.add(p.ref);
        }
        this.gSel.appendChild(g);
      }
      // parts without a drawing: the box of their pads
      for (const ref of this.sel.refs) {
        if (drawn.has(ref) || !this.plan) continue;
        const pts = [];
        for (const p of this.plan.pads || []) if (p.placementId === ref) pts.push({ x: p.x, y: p.y, r: Math.hypot(p.w || 0, p.h || 0) / 2 });
        const b = bbox(pts);
        if (!b) continue;
        const c = cased('rect', { x: f(b.x0 - 0.3), y: f(-b.y1 - 0.3), width: f(b.x1 - b.x0 + 0.6), height: f(b.y1 - b.y0 + 0.6), rx: 0.3 }, 'pv-sel', 2, null, 'sel');
        this.gSel.appendChild(c.g);
      }
    }
    /**
     * A drawn part's outline box in its own frame: the body box (KiCad's fab outline) grown to take in the part's plan pads,
     * so the outline goes round a QFP's leads instead of between its body and its pads.
     */
    selBox(p, m) {
      let [x0, y0, x1, y1] = p.box.map(Number);
      const det = m[0] * m[3] - m[1] * m[2];
      const seat = Math.atan2(m[1], m[0]) * 180 / Math.PI;
      const pa = Number(p.a) || 0, a = pa * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
      for (const q of (this.plan && this.plan.pads) || []) {
        if (q.placementId !== p.ref) continue;
        // machine → board (the inverse affine) → the part frame; the pad's half extents at its angle to the part
        const dx = q.x - m[4], dy = q.y - m[5];
        const bx = (m[3] * dx - m[2] * dy) / det - p.x, by = (m[0] * dy - m[1] * dx) / det - p.y;
        const lx = bx * ca + by * sa, ly = by * ca - bx * sa;
        const r = ((Number(q.rotation) || 0) - seat - pa) * Math.PI / 180;
        const c = Math.abs(Math.cos(r)), s = Math.abs(Math.sin(r)), w = q.w || 0, h = q.h || 0;
        const ex = (c * w + s * h) / 2, ey = (s * w + c * h) / 2;
        x0 = Math.min(x0, lx - ex); x1 = Math.max(x1, lx + ex);
        y0 = Math.min(y0, ly - ey); y1 = Math.max(y1, ly + ey);
      }
      return [x0, y0, x1, y1];
    }
    /** Path numbers (1-based, path order) next to the dots with these indexes; null removes them. The same list again does nothing. */
    dotLabels(indexes) {
      const idx = indexes && indexes.length ? Array.from(indexes) : null;
      if (String(idx) === String(this.labelIdx)) return;
      this.labelIdx = idx;
      this.dotLabelKey = null;
      this.drawDotLabels();
    }
    /**
     * Path numbers, each pushed out from the middle of the labelled dots (outside a QFP's pad ring, beside a chip) and
     * turned up to 80° around its dot until it covers no other number or dot and stays in view. A number with no free place is
     * left out (the pads table and the dots table list every one): the selected pad's dots first, then the first and the
     * last dot, then path order, so a fine-pitch row reads 1, 3, 5… instead of a pile of digits.
     */
    drawDotLabels() {
      if (!this.plan) return;
      const vb = this.vb;
      const key = this.labelIdx ? [this.labelIdx.join(','), (this.k || 0).toFixed(4), this.selKey,
        vb ? [vb.x0, vb.y0, vb.x1, vb.y1].map((v) => v.toFixed(2)).join(',') : ''].join('|') : '';
      if (key === this.dotLabelKey) return;
      this.dotLabelKey = key;
      this.gLabels.replaceChildren();
      if (!this.labelIdx) return;
      const k = this.k || 0.1;
      const r = this.dotD && this.dotOuter ? this.dotOuter : this.rDot + DOT_STROKE_PX / 2 * k;
      const fs = 12 * k;
      const dots = this.labelIdx.map((i) => this.plan.dots[i]).filter(Boolean);
      if (!dots.length) return;
      const cx = dots.reduce((a, d) => a + d.x, 0) / dots.length, cy = dots.reduce((a, d) => a + d.y, 0) / dots.length;
      const sel = this.sel ? this.sel.padIds : new Set();
      const ends = new Set([dots[0].index, dots[dots.length - 1].index]);
      const rank = (d) => (sel.has(d.padId) ? 0 : ends.has(d.index) ? 1 : 2);
      const order = dots.slice().sort((a, b) => rank(a) - rank(b) || a.index - b.index);
      const placed = [];
      const gap = 1 * k;
      const hitsDot = (b) => dots.some((q) => {
        const nx = Math.max(b.x0, Math.min(q.x, b.x1)), ny = Math.max(b.y0, Math.min(q.y, b.y1));
        return Math.hypot(q.x - nx, q.y - ny) < r;
      });
      const hitsLabel = (b) => placed.some((p) => b.x0 < p.x1 + gap && p.x0 < b.x1 + gap && b.y0 < p.y1 + gap && p.y0 < b.y1 + gap);
      const inView = (b) => !vb || (b.x0 >= vb.x0 && b.x1 <= vb.x1 && b.y0 >= vb.y0 && b.y1 <= vb.y1);
      for (const d of order) {
        const text = String(d.index + 1);
        // the ink: bold digits about 0.64 em wide and 0.75 em tall, plus the 3 px casing (1.5 px a side)
        const w = (text.length * 0.64 * 12 + 3) * k, h = (0.75 * 12 + 3) * k;
        let ux = d.x - cx, uy = d.y - cy;
        const L = Math.hypot(ux, uy);
        const base = L > 1e-6 ? Math.atan2(uy, ux) : Math.PI / 4;
        for (const da of [0, 0.45, -0.45, 0.9, -0.9, 1.4, -1.4]) {   // outward only: never between a pad row and the part's middle
          ux = Math.cos(base + da); uy = Math.sin(base + da);
          const dist = r + 2 * k + Math.abs(ux) * w / 2 + Math.abs(uy) * h / 2;
          const bx = d.x + ux * dist, by = d.y + uy * dist;
          const b = { x0: bx - w / 2, x1: bx + w / 2, y0: by - h / 2, y1: by + h / 2 };
          if (!inView(b) || hitsLabel(b) || hitsDot(b)) continue;
          placed.push(b);
          const t = svg('text', { class: 'pv-dotlabel', x: f(bx), y: f(-by), 'font-size': f(fs), 'text-anchor': 'middle', 'dominant-baseline': 'central' });
          t.textContent = text;
          this.gLabels.appendChild(t);
          break;
        }
      }
    }

    /** Disconnects the ResizeObserver and removes both SVGs (Records rebuilds, the inspector, the dialog). */
    destroy() {
      if (this.destroyed) return;
      this.destroyed = true;
      if (this.ro) { this.ro.disconnect(); this.ro = null; }
      this.artSvg.remove();
      this.svg.remove();
      if (VIEWS.get(this.host) === this) VIEWS.delete(this.host);
    }
  }

  IL.boardview = {
    create(host, opts) { return new BoardView(host, opts); },
    /** The view living in this host (or null). */
    of(host) { return (host && VIEWS.get(host)) || null; },
    hull, inflate, outside, bbox, planeFit, cased,
  };
})();
