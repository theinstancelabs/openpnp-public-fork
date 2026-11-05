/* places.js: a place of the agreed menu (the user's OK, 11-02; docs/ui-flows/mockups/); Designs has its own file
   (designflow.js):
   - Machine (#machine): Control on the right (the approved mockup `machine`): jog a tool and move it to a place
     through Backend's BR-49 (docs/backend-requests.md "BR-49 + BR-53 API": POST /api/jog {tool: camera|n1|needle, dx,
     dy, dz}: at most 10 mm in X/Y and 2 mm in Z a press; POST /api/move-to {tool, place}: at safe Z, it never lowers),
     the head's position live; the top and bottom cameras (BR-53: OpenPnP's own pictures). By hand links today's Setup,
     Calibrate and pad types, which stay workspaces of their own (Machine selected in the menu).
   Only Machine's jog and Go move the machine, each on a press that says so. Styles: tipflow.css. */
'use strict';
(function () {
  const { el, S } = IL;

  /** Profile's own menu line (profile.js nav), now shown under Machine. */
  const profileNav = (s) => { const w = IL.workspaces.profile; return w && w.nav && s ? w.nav(s) : null; };

  // ================================================================================== Machine › Control (BR-49)
  const J = { tool: 'camera', step: 1, place: null, missing: false };
  const JOG_TOOLS = [['camera', 'Camera'], ['needle', 'Needle'], ['n1', 'Nozzle']];
  const JOG_STEPS = [0.1, 1, 10];
  const TOOL_WORD = { camera: 'the camera', needle: 'the needle', n1: 'N1' };
  const Z_MAX_MM = 2;   // a Z press moves at most 2 mm (BR-49)
  /** The places a tool can be moved to: the waste and the blank first, then the stored ones (status.dispense.locations). */
  function placeNames(s) {
    const d = (s && s.dispense) || {};
    const out = [];
    for (const n of [d.wasteLocationName, d.blankLocationName].concat(Object.keys(d.locations || {}))) {
      if (n && n !== 'waste-test-dot' && (d.locations || {})[n] && !out.includes(n)) out.push(n);
    }
    return out;
  }
  /** A press of BR-49's routes; an OpenPnP without them (404) is told plainly instead of a refusal. */
  async function jogApi(path, body) {
    try {
      const r = await IL.api('POST', path, body);
      J.missing = false;
      return r;
    } catch (e) {
      if (e.status === 404) { J.missing = true; IL.render(); return null; }
      throw e;
    }
  }
  function jogBy(axis, dir) {
    // Z moves at most 2 mm a press, whatever the step (BR-49)
    const mm = axis === 'z' ? Math.min(J.step, Z_MAX_MM) : J.step;
    // one press moves one axis: only that one is sent (BR-49: X/Y or Z, never both in one press)
    return jogApi('/api/jog', { tool: J.tool, ['d' + axis]: dir === '+' ? mm : -mm });
  }
  function jogPanel(p, s, first) {
    if (first) {
      const host = el('div', { id: 'jog-panel', 'data-refusals': '' });
      const seg = (id, label, items) => el('div', { class: 'seg seg--block', id, role: 'group', 'aria-label': label }, items);
      const tools = seg('jog-tool', 'Tool to move', JOG_TOOLS.map(([k, t]) => {
        const b = el('button', { type: 'button', 'data-tool': k, 'aria-pressed': 'false', text: t });
        b.addEventListener('click', () => { J.tool = k; IL.render(); });
        return b;
      }));
      const arrow = (axis, dir, text, title) => {
        const b = el('button', { type: 'button', class: 'btn jog__b jog__' + axis + (dir === '+' ? 'p' : 'm'), 'data-axis': axis, 'data-dir': dir, title, 'aria-label': title }, text);
        b.addEventListener('click', () => IL.act(b, () => jogBy(axis, dir), { refusalIn: host }));
        return b;
      };
      const pad = el('div', { class: 'jog', id: 'jog-pad' },
        el('div', { class: 'jog__xy' }, arrow('y', '+', '↑', 'Y+ (away from you)'), arrow('x', '-', '←', 'X−'), arrow('x', '+', '→', 'X+'), arrow('y', '-', '↓', 'Y− (toward you)')),
        el('div', { class: 'jog__z' }, arrow('z', '+', 'Z ↑', 'Z up'), arrow('z', '-', 'Z ↓', 'Z down')));
      const steps = seg('jog-step', 'Step', JOG_STEPS.map((mm) => {
        const b = el('button', { type: 'button', 'data-mm': String(mm), 'aria-pressed': 'false', text: IL.fmt(mm, mm < 1 ? 1 : 0) + ' mm' });
        b.addEventListener('click', () => { J.step = mm; IL.render(); });
        return b;
      }));
      const place = el('select', { class: 'input', id: 'jog-place', 'aria-label': 'Place' });
      place.addEventListener('change', () => { J.place = place.value; });
      const go = el('button', { type: 'button', class: 'btn btn--primary', id: 'jog-go', text: 'Go' });
      IL.press2(go, () => 'move ' + TOOL_WORD[J.tool] + ' to ' + (J.place || place.value), () => jogApi('/api/move-to', { tool: J.tool, place: J.place || place.value }),
        { refusalIn: host, ok: (r) => (r ? 'Moved ' + TOOL_WORD[J.tool] + ' to ' + (J.place || place.value) + '.' : null) });
      host.append(el('div', { class: 'panel__title' }, el('h2', { text: 'Jog' })), tools, pad,
        el('div', { class: 'jog__steps' }, steps, el('div', { class: 'field__h', text: 'Z moves at most 2 mm a press. The camera has no Z.' })),
        el('div', { class: 'reason', id: 'jog-why', hidden: true }),
        el('div', { class: 'notebox notebox--need', id: 'jog-missing', hidden: true,
          text: 'Jogging from this page is not in this OpenPnP yet (Backend’s BR-49). Nothing moved.' }),
        el('div', { class: 'section-k' }, 'Move to a place', el('span', { class: 'rule' })), el('div', { class: 'jog__go' }, place, go),
        el('div', { class: 'field__h', text: 'Go travels at the safe height and stays there: lower with Z ↓.' }),
        el('div', { class: 'section-k' }, 'Head position', el('span', { class: 'rule' })), el('table', { class: 'headtable', id: 'jog-head' }));
      p.body.append(host);
    }
    for (const b of document.querySelectorAll('#jog-tool button')) IL.setAttr(b, 'aria-pressed', String(b.dataset.tool === J.tool));
    for (const b of document.querySelectorAll('#jog-step button')) IL.setAttr(b, 'aria-pressed', String(Number(b.dataset.mm) === J.step));
    // BR-49 on the see-saw (N1 and the needle share one Z: one goes up as the other goes down): nothing moves before a
    // home, and X/Y only with both nozzles at travel height (status machine.home.n1Low and lowTool: which one is out)
    const homeWhy = IL.lockReason(s, { connected: true, homed: true });
    const hm = (s && s.machine && s.machine.home) || {};
    const lowWhy = hm.n1Low ? 'Raise ' + (hm.lowTool === 'needle' ? 'the needle' : 'N1') + ' first: X and Y move only at travel height' : null;
    const xyWhy = homeWhy || lowWhy;
    const noZ = J.tool === 'camera' ? 'The camera has no Z: pick the needle or the nozzle' : null;
    for (const b of document.querySelectorAll('#jog-pad button[data-axis]')) IL.gate(b, b.dataset.axis !== 'z' ? xyWhy : noZ || homeWhy);
    const names = placeNames(s);
    const sel = document.getElementById('jog-place');
    IL.keyed(sel, JSON.stringify(names), () => names.map((n) => el('option', { value: n, text: n })));
    if (J.place && names.includes(J.place)) { if (sel.value !== J.place) sel.value = J.place; } else J.place = sel.value || null;
    IL.gate(document.getElementById('jog-go'), names.length ? xyWhy : 'No places stored yet (Setup › Places)');
    IL.setText(document.getElementById('jog-why'), xyWhy || '');
    IL.show(document.getElementById('jog-why'), !!xyWhy);
    IL.show(document.getElementById('jog-missing'), J.missing);
    const pos = IL.positions(s);
    const f = (v) => (IL.isNum(v) ? IL.fmt(v, 2) : '—');
    const row = (label, t, z) => el('tr', {}, el('td', { text: label }), el('td', { class: 'r', text: t ? f(t.x) : '—' }), el('td', { class: 'r', text: t ? f(t.y) : '—' }), el('td', { class: 'r', text: t && z ? f(t.z) : '—' }));
    const key = [pos.cam, pos.n2, pos.n1].map((t) => (t ? [t.x, t.y, t.z].map((v) => Number(v).toFixed(2)).join(',') : '-')).join('|');
    IL.keyed(document.getElementById('jog-head'), key, () => [el('thead', {}, el('tr', {}, ['mm', 'X', 'Y', 'Z'].map((h) => el('th', { text: h })))),
      el('tbody', {}, row('Camera', pos.cam, false), row('Needle', pos.n2, true), row('Nozzle N1', pos.n1, true))]);
  }

  // ================================================================================== Machine › Control: the cameras
  // BR-53 (GET /api/cameras, /api/cameras/{id}/frame.jpg: OpenPnP's own pictures of the top and bottom cameras) when this
  // OpenPnP has it; until then the top camera through GET /api/camera/top.jpg, and Bottom says what it waits for. Two
  // pictures a second while Machine is on screen; watching moves nothing and switches nothing on.
  const CAM = { id: 'top', service: null, busy: false, url: null, at: 0, err: null, timer: null };
  const CAM_IDS = [['top', 'Top'], ['bottom', 'Bottom']];
  async function camProbe() {
    try { const r = await IL.api('GET', '/api/cameras'); CAM.service = Array.isArray(r) ? r : (r && r.cameras) || []; } catch (e) { CAM.service = false; }
  }
  const camUrl = () => (CAM.service ? '/api/cameras/' + CAM.id + '/frame.jpg?w=800&t=' + Date.now() : CAM.id === 'top' ? '/api/camera/top.jpg?w=800&t=' + Date.now() : null);
  async function camTick() {
    const img = document.getElementById('cam-img');
    if (CAM.busy || document.hidden || !img || !img.isConnected || S.route.ws !== 'machine') return;
    CAM.busy = true;
    try {
      if (CAM.service === null) await camProbe();
      const url = camUrl();
      if (!url) { CAM.err = null; return; }
      const r = await fetch(url, { cache: 'no-store', headers: IL.CLIENT_HEADER || {} });
      if (!r.ok) {
        let reason = r.status === 404 ? 'no ' + CAM.id + ' camera' : r.status === 503 ? 'no picture within 10 s' : r.statusText;
        try { reason = (await r.json()).reason || reason; } catch (e) { /* not JSON */ }
        throw new Error(reason);
      }
      const blob = await r.blob();
      if (CAM.url) URL.revokeObjectURL(CAM.url);
      CAM.url = URL.createObjectURL(blob);
      img.src = CAM.url;
      // the frame's own capture time (X-Frame-At, server ms), else when it arrived: a stalled capture reads old
      const taken = Number(r.headers.get('X-Frame-At'));
      CAM.at = taken > 0 ? taken - S.skewMs : Date.now();
      CAM.err = null;
    } catch (e) {
      CAM.err = e.message || String(e);
    } finally {
      CAM.busy = false;
      renderCam(S.status);
    }
  }
  function camChoose(id) {
    if (CAM.id === id) return;
    CAM.id = id;
    CAM.at = 0;
    CAM.err = null;
    const img = document.getElementById('cam-img');
    if (img) img.removeAttribute('src');
    renderCam(S.status);
    camTick();
  }
  function cameraCard() {
    const pick = el('div', { class: 'seg', id: 'cam-pick', role: 'group', 'aria-label': 'Camera' }, CAM_IDS.map(([k, t]) => {
      const b = el('button', { type: 'button', 'data-cam': k, 'aria-pressed': String(k === CAM.id), text: t });
      b.addEventListener('click', () => camChoose(k));
      return b;
    }));
    return el('section', { class: 'card', id: 'cam-card' },
      el('div', { class: 'card__head' }, el('h2', { class: 'card__title', text: 'Camera' }), pick),
      el('div', { class: 'card__body' },
        el('div', { class: 'cam-view' }, el('img', { id: 'cam-img', alt: '' }), el('span', { class: 'cam-tag', id: 'cam-tag' }),
          el('div', { class: 'cam-empty', id: 'cam-empty', hidden: true })),
        el('p', { class: 'meta', id: 'cam-line' })));
  }
  function renderCam(s) {
    const empty = document.getElementById('cam-empty');
    if (!empty) return;
    for (const b of document.querySelectorAll('#cam-pick button')) IL.setAttr(b, 'aria-pressed', String(b.dataset.cam === CAM.id));
    const word = CAM_IDS.find((x) => x[0] === CAM.id)[1].toLowerCase();
    const sim = !!s && IL.isSim(s);
    const waits = !CAM.service && CAM.id !== 'top';
    const note = waits ? (CAM.service === null ? 'Looking for the cameras…'
      : 'The ' + word + ' camera comes with Backend’s BR-53: not in this OpenPnP yet. The top camera works now.')
      : CAM.err ? 'No picture: ' + CAM.err + '.' : !CAM.at ? 'Waiting for the first picture…' : null;
    IL.setText(empty, note || '');
    IL.show(empty, !!note);
    IL.show(document.getElementById('cam-img'), !note || (!!CAM.at && !waits));
    IL.setText(document.getElementById('cam-tag'), (sim ? 'Simulator' : 'Real') + ' ' + word + ' camera' + (CAM.at && !CAM.err ? ' · live' : ''));
    const age = CAM.at ? IL.fmtAgo(CAM.at).replace(' ago', ' old') : null;
    IL.setText(document.getElementById('cam-line'), waits ? 'Watching moves nothing and switches nothing on.'
      : [age, 'OpenPnP’s picture of the ' + word + ' camera', sim ? 'simulated' : null].filter(Boolean).join(' · '));
  }

  // ================================================================================== Machine
  IL.workspace('machine', {
    defaultView: 'jog',
    panel(p, s, first) { jogPanel(p, s, first); },
    show() { clearInterval(CAM.timer); CAM.timer = setInterval(camTick, 500); camTick(); },
    hide() { clearInterval(CAM.timer); CAM.timer = null; },
    render(s) { renderCam(s); },
    mount(root) {
      const link = (href, t, d) => el('a', { class: 'place-link', href }, el('b', { text: t }), el('span', { text: d }));
      root.append(
        el('div', { class: 'page-head' }, el('div', { class: 'page-head__text' },
          el('div', { class: 'eyebrow', text: 'Machine' }), el('h1', { text: 'Machine' }),
          el('div', { class: 'meta' }, el('span', { text: 'Nothing here is needed for a run. Use it when you want to look, move or measure by hand.' })))),
        cameraCard(),
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
