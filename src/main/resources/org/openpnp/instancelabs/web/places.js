/* places.js: two places of the agreed menu (the user's OK, 11-02; docs/ui-flows/mockups/) until their own flows are
   built (build order: Tip, Run, Designs):
   - Designs (#designs): the boards you can make (GET /api/boards: the board files), Upload a design (today's upload
     dialog, IL.boardImport.open) and the pad types; the design's set-up checklist comes with the Designs flow (B).
   - Machine (#machine): manual control comes with BR-49 (jog, move to) and BR-53 (the camera service); By hand links
     today's Setup, Calibrate and pad types, which stay workspaces of their own (Machine selected in the menu).
   Nothing here moves the machine. Styles: tipflow.css. */
'use strict';
(function () {
  const { el, S } = IL;

  /** Profile's own menu line (profile.js nav), now shown under Designs or Machine. */
  const profileNav = (s) => { const w = IL.workspaces.profile; return w && w.nav && s ? w.nav(s) : null; };

  // ================================================================================== Designs
  const D = { boards: null, err: null, list: null, loading: false };
  async function loadBoards() {
    if (D.loading) return;
    D.loading = true;
    try { D.boards = await IL.api('GET', '/api/boards'); D.err = null; } catch (e) { D.err = e.message || String(e); } finally { D.loading = false; }
    renderDesigns();
  }
  function renderDesigns() {
    if (!D.list) return;
    const files = (D.boards && D.boards.files) || [];
    // read or not in the key: an empty first answer replaces "Reading the boards…"
    IL.keyed(D.list, JSON.stringify([!!D.boards, files.map((f) => [f.name, f.placements, f.widthMm, f.heightMm]), D.err]), () => {
      if (D.err) return el('p', { class: 'notebox notebox--block', text: 'The boards could not be read: ' + D.err });
      if (!D.boards) return el('p', { class: 'muted', text: 'Reading the boards…' });
      if (!files.length) return el('p', { class: 'notebox', text: 'No designs yet: upload one.' });
      return files.map((f) => el('div', { class: 'design', 'data-name': f.name },
        el('div', { class: 'design__t', text: f.name.replace(/\.board\.xml$/, '') }),
        el('div', { class: 'design__s', text: [IL.isNum(f.widthMm) && IL.isNum(f.heightMm) ? IL.fmt(f.widthMm, 1) + ' × ' + IL.fmt(f.heightMm, 1) + ' mm' : null,
          IL.isNum(f.placements) ? f.placements + ' parts' : null].filter(Boolean).join(' · ') })));
    });
  }
  /** "Use this board" after an upload here: the list read again, and the board chosen in Run's Board step. */
  function onImported(saved) {
    loadBoards();
    return IL.boardFlow ? IL.boardFlow.onImported(saved) : null;
  }
  IL.workspace('designs', {
    mount(root) {
      const up = el('button', { type: 'button', class: 'btn btn--primary', id: 'btn-design-upload' }, IL.icon('i-plus'), 'Upload a design');
      up.addEventListener('click', () => IL.boardImport.open({ onSaved: onImported }));
      D.list = el('div', { class: 'designs', id: 'designs-list' });
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Designs' }), el('h1', { text: 'Designs' }),
          el('div', { class: 'meta' }, el('span', { text: 'The boards you can make. Setting up a new design step by step comes with the Designs flow.' }))),
        el('div', { class: 'page-head__actions' }, up)),
        D.list,
        el('p', { class: 'meta place-more' }, el('a', { class: 'link', href: '#profile', text: 'Pad types' }), el('span', { text: ': the dose of every pad size, per tip.' })));
      renderDesigns();
    },
    show() { loadBoards(); },
    nav(s) {
      // the pad types' needs ("Needs 3 profiles", unsaved pad-type edits) belong to the designs that use them
      const pv = profileNav(s);
      if (pv && (pv.dirty || /^Needs /.test(pv.sub || ''))) return pv;
      const n = ((D.boards && D.boards.files) || []).length;
      return n ? { sub: n + (n === 1 ? ' design' : ' designs') } : {};
    },
  });

  // ================================================================================== Machine
  IL.workspace('machine', {
    mount(root) {
      const link = (href, t, d) => el('a', { class: 'place-link', href }, el('b', { text: t }), el('span', { text: d }));
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Machine' }), el('h1', { text: 'Machine' }),
          el('div', { class: 'meta' }, el('span', { text: 'Nothing here is needed for a run. Use it when you want to look, move or measure by hand.' })))),
        el('section', { class: 'card' }, el('div', { class: 'card__head' }, el('h2', { class: 'card__title', text: 'Control' })),
          el('div', { class: 'card__body' }, el('p', { class: 'muted', id: 'machine-control-later',
            text: 'Jog, move to a place and the three cameras come here once the machine can do them from the UI (requests BR-49 and BR-53). Meanwhile: jog in OpenPnP’s window; the top camera is in Setup.' }))),
        el('section', { class: 'card' }, el('div', { class: 'card__head' }, el('div', {}, el('h2', { class: 'card__title', text: 'By hand' }),
          el('div', { class: 'card__sub', text: 'Today’s tools, kept as they are (Process uses them).' }))),
          el('div', { class: 'card__body place-links' },
            link('#setup', 'Setup', 'needle height frame, surfaces, purge row, pressure and prime, places, top camera, settings'),
            link('#calibrate', 'Calibrate', 'touch-off, start angle, pad calibration, weigh dots, dot ladder'),
            link('#profile', 'Pad types', 'the dose of every pad size, per tip'))));
    },
    nav(s) {
      if (IL.taskRunning(s)) return {};   // "Locked · running" (core)
      const pv = profileNav(s);           // the screw start angle (Calibrate) is the machine's
      if (pv && / angle$/.test(pv.sub || '')) return pv;
      return {};
    },
  });
})();
