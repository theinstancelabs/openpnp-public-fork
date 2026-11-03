/* boardart.js: the board drawing (art JSON of docs/instancelabs/BOARD_IMPORT_DESIGN.md §4.1, board mm, +Y up) as SVG.
   Shared by boardview.js (the art SVG under the job overlay), the thumbnails and the upload preview.

   API (IL.boardart):
     get(id)                     Promise of the art (GET /api/board-art/<id> through the browser's HTTP cache: the server
                                 answers with an immutable header, so the client keeps no cache of its own), or null;
                                 art.id = id
     build(art, {uid})           → {root: g.pv-l-art, labels: {lb1, lb2, lb3: smallest label height in mm}, parts}
                                 one merged path per layer (§5.5); texts and labels read the right way round
     thumbUrl(art, {w, h, id})   blob: URL of a small picture made from art.thumb (rendered once per art id, or art object,
                                 and size)
     toSvgMatrix(m)              board → machine affine [m00 m10 m01 m11 m02 m12] as an SVG transform (machine Y drawn down)
     apply(m, x, y)              board point → machine {x, y}
     outlineBox(art, m)          machine box {x0, y0, x1, y1} of the board rectangle (0, 0)–size
     originMarker(art)           g.pv-a-origin: crosshair at 0,0 with 5 mm +X / +Y arrows
     labelBucket(h)              'pv-lb1' (< 0.6 mm), 'pv-lb2' (< 1.0 mm) or 'pv-lb3' */
'use strict';
(function () {
  const svg = IL.svgEl;
  const f = (v) => (Math.round(v * 1000) / 1000).toString();
  const MASKS = { slate: '#24424e', green: '#1f5a2e', red: '#8a1c1c', blue: '#1c3f8a', purple: '#4a2a6e', black: '#15181a',
    white: '#e9ecec', yellow: '#c9a81c' };
  const SILKS = { white: '#f3f3ee', black: '#15181a', yellow: '#e8d44d' };
  const LABEL_MIN = 0.3, LABEL_MAX = 1.2;

  /**
   * The art JSON of an art id, or null (404 art-gone: the view keeps drawing without art). The JSON has no id of its own:
   * art.id is set to the id asked for, so a view builds each drawing once per id (two fallback drawings of one board
   * file can match in every other field).
   */
  async function get(id) {
    if (!id || !/^[0-9a-f]{16}$/.test(String(id))) return null;
    const r = await fetch('/api/board-art/' + id, { cache: 'default', headers: { Accept: 'application/json' } });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error('board drawing ' + id + ': HTTP ' + r.status);
    const art = await r.json();
    if (art && typeof art === 'object' && art.id === undefined) art.id = String(id);
    return art;
  }

  const toSvgMatrix = (m) => 'matrix(' + [m[0], -m[1], m[2], -m[3], m[4], -m[5]].map(f).join(' ') + ')';
  const apply = (m, x, y) => ({ x: m[0] * x + m[2] * y + m[4], y: m[1] * x + m[3] * y + m[5] });
  function outlineBox(art, m) {
    const w = art && art.size ? Number(art.size[0]) : 0, h = art && art.size ? Number(art.size[1]) : 0;
    const pts = [[0, 0], [w, 0], [w, h], [0, h]].map(([x, y]) => apply(m, x, y));
    return { x0: Math.min(...pts.map((p) => p.x)), y0: Math.min(...pts.map((p) => p.y)),
      x1: Math.max(...pts.map((p) => p.x)), y1: Math.max(...pts.map((p) => p.y)) };
  }
  const labelBucket = (h) => (h < 0.6 ? 'pv-lb1' : h < 1.0 ? 'pv-lb2' : 'pv-lb3');
  /** Angle normalised to (−90°, 90°]: a label never reads upside down. */
  function upright(a) {
    let x = ((Number(a) || 0) % 360 + 360) % 360;
    if (x > 90 && x <= 270) x -= 180;
    else if (x > 270) x -= 360;
    return x;
  }
  /** Text inside the flipped group: local translate + rotate (counter-clockwise like the board) + un-flip. */
  const textAt = (x, y, a) => 'translate(' + f(x) + ' ' + f(y) + ') rotate(' + f(a) + ') scale(1 -1)';

  function originMarker(art) {
    const g = svg('g', { class: 'pv-a-origin' }, 'Board origin (0, 0): placements are measured from here' + (art && art.origin === 'estimated'
      ? ' (estimated: the file has no board outline)' : ''));
    const d = 'M-1.5 0H5M0 -1.5V5M4.2 -0.6L5 0L4.2 0.6M-0.6 4.2L0 5L0.6 4.2';
    g.append(svg('path', { class: 'pv-a-origin__case', d }), svg('path', { d }));
    const t = svg('text', { transform: textAt(0.6, -0.9, 0), 'font-size': '1.3' });
    t.textContent = '0,0';
    g.appendChild(t);
    for (const [x, y, s] of [[5.6, 0, 'X'], [0, 5.8, 'Y']]) {
      const l = svg('text', { transform: textAt(x, y, 0), 'font-size': '1.2' });
      l.textContent = s;
      g.appendChild(l);
    }
    return g;
  }

  /** art → SVG nodes (untransformed: the caller sets the board → machine matrix on root). */
  function build(art, opts) {
    const o = opts || {};
    const L = (art && art.layers) || {};
    const get2 = (a, b) => (L[a] && L[a][b]) || '';
    const root = svg('g', { class: 'pv-l-art', 'data-art': o.uid || null });
    const path = (cls, d, attrs) => (d ? root.appendChild(svg('path', Object.assign({ class: cls, d }, attrs || {}))) : null);
    const lines = (cls, list) => {
      const g = svg('g', { class: cls });
      for (const l of list || []) if (l && l.d) g.appendChild(svg('path', { d: l.d, 'stroke-width': f(Math.max(0.01, Number(l.w) || 0.1)) }));
      root.appendChild(g);
      return g;
    };
    const outline = art.outline || {};
    const board = (outline.d || '') + (outline.cutouts || '');
    path('pv-a-sub', board);
    path('pv-a-cu', get2('cu', 'fill'));
    lines('pv-a-tracks', L.cu && L.cu.tracks);
    path('pv-a-mask', board);
    path('pv-a-open', get2('mask', 'open'));
    path('pv-a-exposed', get2('exposed', 'fill'));
    path('pv-a-paste', get2('paste', 'fill'));
    path('pv-a-silk', get2('silk', 'fill'));
    lines('pv-a-silklines', L.silk && L.silk.lines);
    // board texts on the silkscreen (KiCad's stroke font never matches a system font; position and size do)
    const gt = svg('g', { class: 'pv-a-text' });
    for (const t of art.texts || []) {
      const h = Number(t.h) || 1;
      const rows = String(t.t || '').split('\n');
      const pitch = h * 1.6;
      rows.forEach((row, i) => {
        if (!row) return;
        const n = svg('text', { transform: textAt(t.x, t.y, upright(t.a)), y: f((i - (rows.length - 1) / 2) * pitch), 'font-size': f(h * 1.4),
          'text-anchor': t.anchor === 'start' || t.anchor === 'end' ? t.anchor : 'middle' });
        n.textContent = row;
        gt.appendChild(n);
      });
    }
    root.appendChild(gt);
    path('pv-a-hole--pth', get2('holes', 'pth'));
    path('pv-a-hole--npth', get2('holes', 'npth'));
    path('pv-a-hole--via', get2('holes', 'via'));
    lines('pv-a-fab', L.fab && L.fab.lines);
    path('pv-a-edge', board);
    // one reference label per top part: KiCad's Reference position when it is on the silkscreen, else the body centre
    const refs = svg('g', { class: 'pv-a-refs' });
    const labels = { lb1: Infinity, lb2: Infinity, lb3: Infinity };
    for (const p of art.parts || []) {
      if (!p || !p.ref || p.kind === 'bottom') continue;
      let x, y, a, h, fab = false;
      if (p.label && Number.isFinite(Number(p.label.x))) {
        x = Number(p.label.x); y = Number(p.label.y); a = upright(p.label.a); h = Number(p.label.h) || 1;
      } else if (p.pads !== undefined && p.pads !== null && Number(p.pads) === 0) {
        continue;   // a hidden reference of a pad-less footprint (a logo, nano's "Silkscreen_Front" artwork): nothing to find
      } else {
        const b = p.box || [-0.5, -0.5, 0.5, 0.5];
        const cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        const r = (Number(p.a) || 0) * Math.PI / 180;
        x = p.x + cx * Math.cos(r) - cy * Math.sin(r);
        y = p.y + cx * Math.sin(r) + cy * Math.cos(r);
        a = upright(Math.abs(b[3] - b[1]) > Math.abs(b[2] - b[0]) ? (Number(p.a) || 0) + 90 : p.a);
        h = Math.max(LABEL_MIN, Math.min(LABEL_MAX, 0.45 * Math.min(Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]))));
        fab = true;   // not printed on the board: drawn fainter than the silkscreen (board.css)
      }
      const cls = labelBucket(h);
      const k = cls.slice(3);
      labels[k] = Math.min(labels[k], h);
      const n = svg('text', { class: cls + (fab ? ' pv-a-ref--fab' : ''), transform: textAt(x, y, a), 'font-size': f(h * 1.4), 'data-ref': p.ref });
      n.textContent = p.ref;
      refs.appendChild(n);
    }
    root.appendChild(refs);
    root.appendChild(originMarker(art));
    return { root, labels, parts: art.parts || [] };
  }

  // ------------------------------------------------------------------------------------------ thumbnails
  const thumbs = new Map();       // art id + size → blob URL (rendered once)
  const thumbsByArt = new WeakMap();   // art object → {size → blob URL} when no id is given
  const esc = (s) => String(s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  /** A small picture of the board from art.thumb (outline, mask openings, exposed copper at 0.1 mm) as a blob: URL. */
  function thumbUrl(art, size) {
    if (!art || !art.size) return null;
    const w = (size && size.w) || 96, h = (size && size.h) || 64;
    const id = size && size.id;
    if (!id && !thumbsByArt.has(art)) thumbsByArt.set(art, new Map());
    const cache = id ? thumbs : thumbsByArt.get(art);
    const key = (id || '') + '|' + w + 'x' + h;
    if (cache.has(key)) return cache.get(key);
    const t = art.thumb || {};
    const bw = Number(art.size[0]) || 1, bh = Number(art.size[1]) || 1;
    const pad = Math.max(bw, bh) * 0.04;
    const mask = MASKS[(art.finish && art.finish.mask) || 'slate'] || MASKS.slate;
    const outline = t.outline || (art.outline && art.outline.d) || 'M0 0H' + bw + 'V' + bh + 'H0Z';
    const body = '<g transform="matrix(1 0 0 -1 0 ' + f(bh) + ')">'
      + '<path d="' + esc(outline) + '" fill="' + mask + '" fill-rule="evenodd"/>'
      + (t.mask ? '<path d="' + esc(t.mask) + '" fill="#c8bd9a"/>' : '')
      + (t.exposed ? '<path d="' + esc(t.exposed) + '" fill="#ddd5c1"/>' : '')
      + '<path d="' + esc(outline) + '" fill="none" stroke="#0e1416" stroke-width="' + f(Math.max(bw, bh) / 160) + '" fill-rule="evenodd"/>'
      + '<path d="M0 0H' + f(Math.min(5, bw / 6)) + 'M0 0V' + f(Math.min(5, bh / 6)) + '" stroke="#ffffff" stroke-width="' + f(Math.max(bw, bh) / 90) + '"/>'
      + '</g>';
    const doc = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="' + [-pad, -pad, bw + 2 * pad, bh + 2 * pad].map(f).join(' ')
      + '" preserveAspectRatio="xMidYMid meet">' + body + '</svg>';
    const url = URL.createObjectURL(new Blob([doc], { type: 'image/svg+xml' }));
    cache.set(key, url);
    return url;
  }

  IL.boardart = { get, build, thumbUrl, toSvgMatrix, apply, outlineBox, originMarker, labelBucket, upright, MASKS, SILKS };
})();
