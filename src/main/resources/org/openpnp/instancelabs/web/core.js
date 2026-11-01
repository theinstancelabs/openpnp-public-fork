/* core.js: the IL namespace, foundation of the paste dispenser web UI (spec docs/instancelabs/UI_DESIGN.md).
 *
 * Owns: API client, the single store IL.S, the /api/status poll loop with render scheduling, the hash router,
 * the readiness model (IL.readiness), the status strip, STOP (+ F9, retries, recovery state), two-press buttons,
 * toasts / page bands / activity log, in-page dialogs, the context panel host and its shared views (interlocks,
 * machine, pressure, angle, purge, activity, camera, recovery), theme and keyboard.
 *
 * Loaded synchronously in <head> (before the body exists) so the theme is set before the first paint. Workspace
 * modules (run.js, setup.js, ...) load after it with `defer` and register with IL.workspace(); app.js calls
 * IL.boot() last. Contract for the workspace modules: DEV_NOTES.md next to this file.
 *
 * CSP: script-src 'self'. No inline handlers, no eval, no native confirm / alert / prompt dialogs, no modal dialogs.
 */
'use strict';
(function () {
  const IL = {};
  window.IL = IL;
  IL.version = '2026-10-28';

  // ================================================================================== constants
  const STOP_CODES = 'M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B';
  IL.STOP_CODES = STOP_CODES;
  IL.STOP_TITLE = 'Stops everything now. Sends ' + STOP_CODES + ' (quick stop, dispenser valve and pump off, pick valve '
    + 'and pump off, screw motor off), vents a pressure hold, then stops OpenPnP (the machine must be connected and homed '
    + 'again). Key: F9.';
  IL.STOP_LINE = 'STOP sends ' + STOP_CODES + ': quick stop, dispenser valve and pump off, pick valve and pump off, '
    + 'screw motor off.';
  /** What STOP did, each code in plain words (Recovery view). */
  IL.STOP_DID = [
    ['Motion stopped at once', 'M410'],
    ['Dispenser valve off: barrel vented', 'M107 P3'],
    ['Dispenser pump off', 'M107 P2'],
    ['Pick valve and pump off', 'P1 · P0'],
    ['Screw motor off (turns freely)', 'M18 B'],
    ['OpenPnP stopped: machine disabled', ''],
  ];
  IL.WORKSPACES = ['run', 'setup', 'profile', 'calibrate', 'records'];
  IL.LIMITS = {
    pollMs: 700, pollHiddenMs: 2000, statusTimeoutMs: 2500, apiTimeoutMs: 10000,
    staleMs: 1500, offlineMs: 3000,
    stopTimeoutMs: 2000, stopRetryMs: 1000, stopConfirmedShowMs: 10000,
    press2Ms: 4000, press2MinMs: 300, toastMs: 4000,
    needleDriftMm: 1.0, wasteNearMm: 3.0, attestMs: 8 * 3600 * 1000,
    planDebounceMs: 300, planEveryMs: 10000, eventsEveryMs: 5000,
  };
  const L = IL.LIMITS;
  /** Run phases during which the run holds the barrel under pressure (one pressure session per stream). */
  IL.PRESSURE_PHASES = new Set(['dispensing', 'shots', 'dispensing calibration dots']);

  // ================================================================================== storage + theme
  function lsGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) {
    try {
      if (v === null || v === undefined) window.localStorage.removeItem(k);
      else window.localStorage.setItem(k, String(v));
    } catch (e) { /* storage unavailable: keep going */ }
  }
  function lsJson(k, fallback) {
    const t = lsGet(k);
    if (!t) return fallback;
    try { return JSON.parse(t); } catch (e) { return fallback; }
  }
  IL.lsGet = lsGet;
  IL.lsSet = lsSet;
  IL.lsJson = lsJson;
  /** sessionStorage as JSON (per tab): IL.ssJson(key, fallback), IL.ssSet(key, value | null). */
  IL.ssJson = function (k, fallback) {
    try { const t = window.sessionStorage.getItem(k); return t ? JSON.parse(t) : fallback; } catch (e) { return fallback; }
  };
  IL.ssSet = function (k, v) {
    try {
      if (v === null || v === undefined) window.sessionStorage.removeItem(k);
      else window.sessionStorage.setItem(k, JSON.stringify(v));
    } catch (e) { /* storage unavailable: keep going */ }
  };

  const THEME_KEY = 'il.theme';
  const darkQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  function applyTheme(t) { document.documentElement.dataset.theme = t === 'dark' ? 'dark' : 'light'; }
  applyTheme(lsGet(THEME_KEY) || (darkQuery && darkQuery.matches ? 'dark' : 'light'));
  IL.theme = () => document.documentElement.dataset.theme;
  /** Sets the theme ('light' | 'dark'); persist false = follow the system again later. */
  IL.setTheme = function (t, persist) {
    applyTheme(t);
    lsSet(THEME_KEY, persist === false ? null : IL.theme());
    syncThemeControls();
    IL.emit('theme', IL.theme());
  };
  if (darkQuery && darkQuery.addEventListener) {
    darkQuery.addEventListener('change', (e) => { if (!lsGet(THEME_KEY)) IL.setTheme(e.matches ? 'dark' : 'light', false); });
  }
  function syncThemeControls() {
    const t = IL.theme();
    for (const b of document.querySelectorAll('[data-theme-set]')) b.setAttribute('aria-pressed', String(b.dataset.themeSet === t));
    const tg = document.getElementById('btn-theme');
    if (tg) {
      const u = tg.querySelector('use');
      if (u) u.setAttribute('href', t === 'dark' ? '#i-sun' : '#i-moon');
      tg.setAttribute('aria-label', t === 'dark' ? 'Switch to light' : 'Switch to dark');
      tg.title = tg.getAttribute('aria-label');
    }
  }

  // ================================================================================== events
  const listeners = {};
  /** Subscribes to an event; returns the unsubscribe function. Events: DEV_NOTES.md "Events". */
  IL.on = function (name, fn) {
    (listeners[name] || (listeners[name] = new Set())).add(fn);
    return () => listeners[name].delete(fn);
  };
  IL.emit = function (name, ...args) {
    const set = listeners[name];
    if (!set) return;
    for (const fn of Array.from(set)) safe(() => fn(...args), 'listener ' + name);
  };
  function safe(fn, where) {
    try { return fn(); } catch (e) { console.error('[IL] ' + (where || 'error'), e); return undefined; }
  }
  IL.safe = safe;

  // ================================================================================== DOM helpers
  const $ = (id) => document.getElementById(id);
  IL.$ = $;
  IL.$$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const SVGNS = 'http://www.w3.org/2000/svg';

  /** el('button', {class, text, title, onclick: fn, dataset: {...}, 'data-x': 1}, ...children). Falsy attrs skipped. */
  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'text') e.textContent = v;
      else if (k === 'class') e.className = v;
      else if (k === 'dataset') Object.assign(e.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : v);
    }
    appendAll(e, children);
    return e;
  }
  function appendAll(e, children) {
    for (const c of children.flat(Infinity)) {
      if (c === null || c === undefined || c === false || c === true) continue;
      e.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return e;
  }
  IL.el = el;
  IL.append = appendAll;
  IL.svgEl = function (tag, attrs, title) {
    const e = document.createElementNS(SVGNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v);
    if (title) {
      const t = document.createElementNS(SVGNS, 'title');
      t.textContent = title;
      e.appendChild(t);
    }
    return e;
  };
  /** <svg class="ic"><use href="#name"/></svg> from the sprite in index.html (i-run, i-gauge, i-lock, ...). */
  IL.icon = function (name, cls) {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('class', cls === undefined ? 'ic' : cls);
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('aria-hidden', 'true');
    const u = document.createElementNS(SVGNS, 'use');
    u.setAttribute('href', '#' + name);
    s.appendChild(u);
    return s;
  };
  const GLYPH = { ok: 'g-check', you: 'g-check', need: 'g-bang', block: 'g-x', off: 'g-dash', info: 'g-info',
    wait: 'g-dash', checking: 'g-dash', now: 'g-dash' };
  const WORD = { ok: 'OK', need: 'Needs you', block: 'Blocked', off: 'Off or not fitted', info: 'Note',
    you: 'Confirmed by you', live: 'Energised', num: 'Not done', now: 'Now', wait: 'Not known yet', checking: 'Checking' };
  IL.WORD = WORD;
  /** Status glyph (shape + colour + word for screen readers): kind ok|need|block|off|info|you|live|num|now|wait. */
  IL.glyph = function (kind, size, label, text) {
    return IL.setGlyph(el('span', { role: 'img' }), kind, size, label, text);
  };
  IL.setGlyph = function (span, kind, size, label, text) {
    if (!span) return span;
    const k = kind || 'off';
    const sig = k + '|' + (size || '') + '|' + (text === undefined || text === null ? '' : text) + '|' + (label || '');
    if (span.dataset.glyph === sig) return span;
    span.dataset.glyph = sig;
    span.className = 'st st--' + k + (size ? ' st--' + size : '');
    span.setAttribute('role', 'img');
    span.setAttribute('aria-label', label || WORD[k] || k);
    span.replaceChildren();
    if (text !== undefined && text !== null) span.textContent = String(text);
    else if (k === 'live') span.appendChild(el('span', { class: 'pulse' }));
    else {
      const s = document.createElementNS(SVGNS, 'svg');
      s.setAttribute('viewBox', '0 0 24 24');
      const u = document.createElementNS(SVGNS, 'use');
      u.setAttribute('href', '#' + (GLYPH[k] || 'g-dash'));
      s.appendChild(u);
      span.appendChild(s);
    }
    return span;
  };
  /** Diff writers: they touch the DOM only when the value changed (no layout churn on every poll). */
  IL.setText = function (node, text) {
    const t = text === null || text === undefined ? '' : String(text);
    if (node && node.textContent !== t) { node.textContent = t; return true; }
    return false;
  };
  IL.setAttr = function (node, name, value) {
    if (!node) return;
    if (value === null || value === undefined || value === false) {
      if (node.hasAttribute(name)) node.removeAttribute(name);
    } else {
      const v = value === true ? '' : String(value);
      if (node.getAttribute(name) !== v) node.setAttribute(name, v);
    }
  };
  IL.setClass = function (node, cls) { if (node && node.getAttribute('class') !== cls) node.setAttribute('class', cls); };
  /** Shows / hides with the hidden attribute (works for SVG elements too). */
  IL.show = function (node, on) {
    if (!node) return;
    if (on) { if (node.hasAttribute('hidden')) node.removeAttribute('hidden'); }
    else if (!node.hasAttribute('hidden')) node.setAttribute('hidden', '');
  };
  /** patch(node, {text, class, title, hidden, disabled, attrs: {...}, dataset: {...}}). */
  IL.patch = function (node, p) {
    if (!node || !p) return node;
    if ('text' in p) IL.setText(node, p.text);
    if ('class' in p) IL.setClass(node, p.class);
    if ('title' in p) IL.setAttr(node, 'title', p.title);
    if ('hidden' in p && node.hidden !== !!p.hidden) node.hidden = !!p.hidden;
    if ('disabled' in p && node.disabled !== !!p.disabled) node.disabled = !!p.disabled;
    if (p.attrs) for (const [k, v] of Object.entries(p.attrs)) IL.setAttr(node, k, v);
    if (p.dataset) for (const [k, v] of Object.entries(p.dataset)) { if (node.dataset[k] !== String(v)) node.dataset[k] = v; }
    return node;
  };
  /**
   * Rebuilds `container` with build() only when `key` changed. Keeps focus on an element with the same id /
   * data-fix / data-focus-key, and never rebuilds while a two-press button inside it is pending.
   */
  IL.keyed = function (container, key, build) {
    if (!container) return false;
    const k = String(key);
    if (container.dataset.key === k) return false;
    if (armed && container.contains(armed.btn)) return false;
    const a = document.activeElement;
    let sel = null;
    if (a && a !== container && container.contains(a)) {
      if (a.id) sel = '#' + CSS.escape(a.id);
      else if (a.dataset && a.dataset.focusKey) sel = '[data-focus-key="' + CSS.escape(a.dataset.focusKey) + '"]';
      else if (a.dataset && a.dataset.fix) sel = '[data-fix="' + CSS.escape(a.dataset.fix) + '"]';
    }
    container.dataset.key = k;
    const out = build();
    container.replaceChildren();
    appendAll(container, [out]);
    if (sel) {
      const n = container.querySelector(sel);
      if (n) n.focus({ preventScroll: true });
    }
    return true;
  };
  /**
   * Keeps a number with its unit and a short label with its number on one line ("2,343 ms", "pitch 1.65 mm",
   * "bench 5,490", "1 to 64"): the spaces between them become no-break spaces. Display text only.
   */
  IL.nb = function (text) {
    return String(text)
      .replace(/(\d) (?=(?:mm²|mm|ms|min|s|°|psi|rpm|DPH|mg|mm\/100|%|per|spots?|dots?|pads?|of|to)(?![\w²]))/g, '$1\u00a0')
      .replace(/\b(bench|estimate|gap|dwell|purge|pitch|slowest|fastest|median|ended|started|limit|worst|tilt|offset|frame) (?=[\d−+-])/g, '$1\u00a0')
      .replace(/([a-z])-([a-z])/g, '$1\u2011$2');
  };
  /** <dl class="kv"> from [[key, value, title?], ...]; rebuilt only when the pairs change. */
  IL.kv = function (dl, pairs) {
    const plain = pairs.every((p) => !(p[1] instanceof Node));
    const key = plain ? JSON.stringify(pairs) : null;
    if (key !== null && dl.dataset.key === key) return dl;
    if (key !== null) dl.dataset.key = key; else delete dl.dataset.key;
    dl.replaceChildren();
    for (const [k, v, title] of pairs) {
      dl.appendChild(el('dt', { text: k, title }));
      dl.appendChild(v instanceof Node ? el('dd', {}, v) : el('dd', { text: v === undefined || v === null ? '—' : IL.nb(v), title }));
    }
    return dl;
  };
  /** <time> with 24 h text and the relative age as title. secs: show seconds (safety events). */
  IL.timeEl = function (ms, secs) {
    if (!ms) return el('span', { text: '—' });
    return el('time', { datetime: new Date(ms).toISOString(), title: IL.fmtAgo(ms, IL.serverNow()) },
      secs ? IL.fmtTimeS(ms) : IL.fmtTime(ms));
  };
  /** Scrolls an element into view and outlines it for 1.5 s (deep links like #setup/purge). */
  IL.flash = function (node) {
    if (!node) return;
    node.scrollIntoView({ block: 'start', behavior: 'smooth' });
    node.classList.remove('flash');
    void node.offsetWidth;
    node.classList.add('flash');
    setTimeout(() => node.classList.remove('flash'), 1600);
  };

  // ================================================================================== formatting
  const MINUS = '−';
  const pad2 = (n) => String(n).padStart(2, '0');
  const isNum = (n) => n !== null && n !== undefined && n !== '' && Number.isFinite(Number(n));
  /** True for a finite number or numeric string (not null, undefined or ''). */
  IL.isNum = isNum;
  /** A server time stamp (…AtMs in the API) as client ms (null stays null). */
  IL.toClient = (ms) => (ms ? ms - S.skewMs : null);
  /** Display number with d decimals and a real minus (never use for input values: IL.inputNum). */
  IL.fmt = function (n, d) {
    if (!isNum(n)) return '—';
    const s = Number(n).toFixed(d === undefined ? 2 : d);
    if (!s.startsWith('-')) return s;
    return Number(s) === 0 ? s.slice(1) : MINUS + s.slice(1);
  };
  /** Always signed (+0.086, −2.590); zero has no sign. */
  IL.fmtSigned = function (n, d) {
    const s = IL.fmt(n, d);
    if (s === '—' || s.startsWith(MINUS) || Number(Number(n).toFixed(d === undefined ? 2 : d)) === 0) return s;
    return '+' + s;
  };
  IL.fmtInt = (n) => (isNum(n) ? Math.round(Number(n)).toLocaleString('en-US').replace('-', MINUS) : '—');
  /** psi as entered: 18 → "18", 18.5 → "18.5". */
  IL.fmtPsi = (p) => (isNum(p) ? String(Number(Number(p).toFixed(1))) : '—');
  IL.fmtMm = (n, d) => IL.fmt(n, d === undefined ? 2 : d) + ' mm';
  IL.fmtDeg = (n, d) => IL.fmt(n, d === undefined ? 1 : d) + '°';
  /** 75 → "1:15", 3725 → "1:02:05". */
  IL.fmtClock = function (s) {
    if (!isNum(s)) return '—';
    const t = Math.max(0, Math.round(Number(s)));
    const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), x = t % 60;
    return h ? h + ':' + pad2(m) + ':' + pad2(x) : m + ':' + pad2(x);
  };
  /** 4 → "4.0 s", 316 → "5 min 16 s". */
  IL.fmtDuration = function (s) {
    if (!isNum(s)) return '—';
    const v = Number(s);
    if (v < 90) return v.toFixed(1) + ' s';
    const t = Math.round(v), m = Math.floor(t / 60);
    if (m >= 60) return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
    return m + ' min ' + (t % 60) + ' s';
  };
  IL.fmtTime = (ms) => { if (!ms) return '—'; const d = new Date(ms); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };
  IL.fmtTimeS = (ms) => { if (!ms) return '—'; const d = new Date(ms); return IL.fmtTime(ms) + ':' + pad2(d.getSeconds()); };
  IL.fmtDateTime = function (ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    if (d.toDateString() === new Date().toDateString()) return IL.fmtTime(ms);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + IL.fmtTime(ms);
  };
  IL.fmtAgo = function (ms, now) {
    if (!ms) return '—';
    const s = Math.max(0, ((now || Date.now()) - ms) / 1000);
    if (s < 10) return s.toFixed(1) + ' s ago';
    if (s < 90) return Math.round(s) + ' s ago';
    if (s < 5400) return Math.round(s / 60) + ' min ago';
    if (s < 172800) return Math.round(s / 3600) + ' h ago';
    return Math.round(s / 86400) + ' days ago';
  };
  IL.plural = (n, one, many) => n + ' ' + (n === 1 ? one : (many || one + 's'));
  /**
   * ASCII number string for an <input> value (fmt uses a real minus that Number() cannot read). d: decimals to show
   * (0.1 → "0.10") unless that would lose digits: a stored 0.125 stays "0.125", so a save never rounds it away.
   */
  IL.inputNum = function (n, d) {
    if (!isNum(n)) return '';
    const t = d === undefined ? null : Number(n).toFixed(d);
    return t !== null && Number(t) === Number(n) ? t : String(Number(n));
  };
  const FRACTION = { 2: '½', 3: '⅓', 4: '¼', 8: '⅛' };
  /** Screw turn per dot: 800 of 1600 steps → "½ turn". */
  IL.turnLabel = function (steps, stepsPerRev) {
    const rev = stepsPerRev || 1600;
    if (!steps) return '—';
    if (steps % rev === 0) return steps / rev === 1 ? '1 turn' : (steps / rev) + ' turns';
    if (rev % steps === 0) return (FRACTION[rev / steps] || '1/' + (rev / steps)) + ' turn';
    if (steps * 4 === rev * 3) return '¾ turn';
    return steps + ' steps';
  };
  /** Recipe token parts: [name, "18 psi", "½ turn", "dwell 250 ms", "gap 0.10 mm", "purge 8"]. */
  IL.recipeParts = function (p) {
    if (!p) return [];
    return [p.name || 'profile', IL.fmtPsi(p.pressurePsi) + ' psi', IL.turnLabel(p.netStepsPerDot, p.stepsPerRev),
      'dwell ' + p.dwellMs + ' ms', 'gap ' + IL.fmt(p.gapMm, 2) + ' mm', 'purge ' + p.purgeDots];
  };
  /** The recipe token (.recipe). opts: {changed, changedText, href, title}. aria-label = parts without the name. */
  IL.recipeEl = function (p, opts) {
    const o = opts || {};
    const parts = IL.recipeParts(p);
    const e = el(o.href ? 'a' : 'span', {
      class: 'recipe' + (o.changed ? ' recipe--changed' : ''), href: o.href || null,
      title: o.title || ('Dispense profile ' + (p ? p.name : '')),
      'aria-label': (o.changed ? 'Changed since planning: plan again. ' : '') + parts.slice(1).join(' · '),
    }, parts.map((t) => el('span', { text: t })));
    if (o.changed) e.appendChild(el('span', { text: o.changedText || 'Changed since planning: plan again' }));
    return e;
  };
  IL.profileText = (f) => (f ? IL.recipeParts(f).join(' · ') : '—');
  /** Header text with its units kept lowercase inside uppercase table headers ("X mm", never "X MM"). */
  IL.unitText = (t) => String(t).split(/(\bmg\/dot\b|\bmm\b|\bmg\b|\bms\b)/).map((x, i) => (i % 2 ? el('span', { class: 'unit-lc', text: x }) : x));
  /**
   * An OpenPnP event message for people: run / plan UUIDs shortened to the 4-character ids the page shows ("plan f85d"),
   * a profile fingerprint ("psi=18.000 net=800 …") and JSON blobs folded to "…". The full text stays in the title.
   */
  IL.eventText = function (msg) {
    const t = String(msg || '').trim();
    // a profile logged as JSON reads as its recipe token
    if (/^\{[\s\S]*\}$/.test(t)) {
      try { const o = JSON.parse(t); if (o && o.name && isNum(o.pressurePsi)) return 'profile ' + IL.recipeParts(o).join(' · '); } catch (e) { /* not JSON */ }
    }
    return t
      .replace(/\b([0-9a-f]{4})[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(-subset-[\w-]+)?/gi, (m, a, sub) => a + (sub ? ' (part)' : ''))
      .replace(/ psi=[\d.]+ net=\d+ suck=[\d.]+ start=[\d.]+ rpm=[\d.]+ acc=[\d.]+ dwell=\d+ gap=[\d.]+( purge=\d+)?/g, '')
      .replace(/\{[^{}]{40,}\}|\[[^\[\]]{60,}\]/g, '{…}');
  };
  /**
   * "297.9° / 117.9° (in whole motor steps)": the angles the dots really start at (the set angle, shown next to it,
   * rounded to whole motor steps: 298.0° reads 297.9° at 4.44 steps per degree).
   */
  IL.startAnglesText = function (p) {
    if (!p || !p.partialTurn || !p.startAngles || !p.startAngles.length) return 'any angle (whole turns)';
    const t = p.startAngles.map((x) => IL.fmtDeg(x, 1)).join(' / ');
    const rounded = isNum(p.startAngleDeg) && IL.fmt(p.startAngles[0], 1) !== IL.fmt(p.startAngleDeg, 1);
    return t + (rounded ? ' (in whole motor steps)' : '');
  };
  /** The value of an input (by id or element) as a number, or undefined when empty. */
  IL.num = function (idOrEl) {
    const e = typeof idOrEl === 'string' ? $(idOrEl) : idOrEl;
    const v = e ? String(e.value).trim().replace(MINUS, '-') : '';
    return v === '' ? undefined : Number(v);
  };
  IL.str = function (idOrEl) {
    const e = typeof idOrEl === 'string' ? $(idOrEl) : idOrEl;
    const v = e ? String(e.value).trim() : '';
    return v === '' ? undefined : v;
  };
  const cap = (t) => (t ? t.charAt(0).toUpperCase() + t.slice(1) : t);
  IL.cap = cap;

  // ================================================================================== API
  class ApiError extends Error {
    constructor(status, data, fallback) {
      super((data && (data.reason || data.message)) || fallback);
      this.status = status;
      this.data = data;
      this.code = data && data.error;
    }
  }
  IL.ApiError = ApiError;
  /**
   * fetch + JSON. Resolves with the parsed body; rejects with ApiError {status, data, code}. status 0 = no
   * answer (code 'timeout' or 'network'). opts.timeoutMs (default 10 s).
   */
  IL.api = async function (method, path, body, opts) {
    const o = opts || {};
    const ms = o.timeoutMs || L.apiTimeoutMs;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), ms);
    const init = { method, headers: {}, signal: ctl.signal, cache: 'no-store' };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let r, text;
    try {
      r = await fetch(path, init);
      text = await r.text();
    } catch (e) {
      const timeout = ctl.signal.aborted;
      throw new ApiError(0, { error: timeout ? 'timeout' : 'network' },
        timeout ? 'No answer from OpenPnP within ' + Math.round(ms / 100) / 10 + ' s.' : 'OpenPnP did not answer (' + e.message + ').');
    } finally {
      clearTimeout(timer);
    }
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (!r.ok) throw new ApiError(r.status, data, r.status + ' ' + r.statusText);
    return data;
  };
  /** One line for an error: the reason, the interlock violations joined, or "outcome unknown" without an answer. */
  IL.errorText = function (e) {
    if (!e) return '';
    if (e.status === 0) return e.message + ' Outcome unknown: look at the machine.';
    let msg = e.message || String(e);
    const il = e.data && e.data.interlocks;
    if (il && il.violations && il.violations.length) {
      msg = (e.data.reason || 'Refused by the interlocks').split(':')[0] + ': '
        + il.violations.map((v) => v.code + ': ' + v.message).join('; ');
    }
    return msg;
  };

  // ================================================================================== store
  const S = {
    status: null,          // last good GET /api/status
    prevStatus: null,
    statusAt: 0,           // client ms when it arrived
    lastOkAt: 0,           // = statusAt; heartbeat age = now - lastOkAt
    online: null,          // null before the first answer, then true / false (offline after 3 s)
    skewMs: 0,             // server clock - client clock (server.timeMs)
    plan: null,            // the plan shown (GET /api/plan/{id} or POST /api/plan response)
    planId: null,
    planPending: false,    // a change-triggered re-read is in flight (readiness item 7 reads "checking")
    planForgotten: false,  // GET /api/plan/{id} answered 404 (OpenPnP restarted)
    planAt: 0,
    cleanCheck: null,      // newest clean-pad report of the plan shown (plan, its subsets, or lastRun)
    newSeatAtMs: lsJson('il.newSeatAtMs', null), // operator said "re-seated / new board": probe this seat
    route: { ws: 'run', section: null, id: null, panel: null },
    recovery: null,        // after STOP / ABORTED / ERROR: see IL.recoverySteps
    lastStop: null,        // the last recovery, kept after it cleared (board decision on Run)
    activity: [],          // this page's log, newest first: {atMs, text, kind}
    runs: null,            // GET /api/runs?limit=50
    config: null,          // GET /api/config
    events: null,          // GET /api/events?limit=50 (only while the Activity view is open)
    connectedAt: null,
    homedAt: null,
    runPressureSince: null,
    lastEndedRunId: undefined,
  };
  IL.S = S;
  let planVersion = 0;
  let attestVersion = 0;
  IL.serverNow = () => Date.now() + S.skewMs;

  // ================================================================================== activity, toast, bands
  /** Adds a line to this page's activity log (Activity view, Records). kind: info|ok|need|block|live. */
  IL.log = function (text, kind) {
    S.activity.unshift({ atMs: Date.now(), text: String(text), kind: kind || 'info' });
    if (S.activity.length > 300) S.activity.length = 300;
    IL.emit('activity', S.activity[0]);
  };
  let toastTimer = null;
  /** Toast at the top-left of the main column (≤ 4 s, at most 3 lines), always repeated in the activity log. */
  IL.toast = function (msg, kind) {
    const t = $('toast');
    IL.log(msg, kind);
    if (!t) return;
    // the text sits in an inner span that is clamped to 3 whole lines (the padding stays outside the clipped box)
    t.replaceChildren(el('span', { class: 'toast__t', text: msg }));
    t.title = msg;
    t.className = 'toast' + (kind === 'block' || kind === 'need' ? ' toast--' + kind : '');
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, L.toastMs);
  };
  /**
   * Page-level band in #msg-area (sticky top of the main column). IL.msg(key, {kind, text, sub, actions, alert})
   * shows / updates it; IL.msg(key, null) removes it. kind: block|need|info|live.
   */
  IL.msg = function (key, opts) {
    const area = $('msg-area');
    if (!area) return null;
    let box = area.querySelector(':scope > [data-msg="' + CSS.escape(key) + '"]');
    if (!opts) { if (box) box.remove(); return null; }
    const kind = opts.kind || 'info';
    const sig = JSON.stringify([kind, opts.text, opts.sub || '']) + (opts.actions ? '|a' : '');
    if (box && box.dataset.sig === sig) return box;
    const nb = el('div', { class: 'msg msg--' + kind, 'data-msg': key, role: opts.alert === false ? 'status' : 'alert' },
      IL.glyph(kind === 'live' ? 'live' : kind, 'sm'),
      el('div', { class: 'msg__t' }, opts.text, opts.sub ? el('small', { text: opts.sub }) : null),
      opts.actions ? el('div', { class: 'msg__act' }, opts.actions) : null);
    nb.dataset.sig = sig;
    if (box) box.replaceWith(nb); else area.appendChild(nb);
    return nb;
  };

  // ================================================================================== attestations (§9.4)
  const ATTEST_KEY = 'il.attest';
  /** Operator ticks that no sensor can make: regulator (psi), paste-life, prime-seen, test-dot-seen. Browser-local. */
  IL.attest = {
    all() { return lsJson(ATTEST_KEY, {}) || {}; },
    get(key) { return IL.attest.all()[key] || null; },
    set(key, value) {
      const a = IL.attest.all();
      a[key] = { key, value: value === undefined ? true : value, atMs: Date.now() };
      lsSet(ATTEST_KEY, JSON.stringify(a));
      attestVersion++;
      IL.emit('attest', key, a[key]);
      IL.render();
      return a[key];
    },
    clear(key) {
      const a = IL.attest.all();
      delete a[key];
      lsSet(ATTEST_KEY, JSON.stringify(a));
      attestVersion++;
      IL.emit('attest', key, null);
      IL.render();
    },
    /** The attestation if still valid for this status (regulator: same psi, not before a needle change, < 8 h). */
    valid(key, status, nowMs) {
      return attestValid(IL.attest.all()[key], key, status, nowMs || Date.now());
    },
  };
  function attestValid(a, key, status, now) {
    if (!a || !a.atMs || now - a.atMs > L.attestMs) return null;
    if (key === 'regulator') {
      const d = (status && status.dispense) || {};
      const psi = d.profile && d.profile.pressurePsi;
      if (!isNum(psi) || Number(a.value) !== Number(psi)) return null;
      const changed = d.needle && d.needle.changedAtMs;
      if (changed && changed > a.atMs + S.skewMs) return null;   // client stamp → server time: + skew
    }
    return a;
  }
  /** The operator answered "re-seated / new board" (or null after probing or "same seat"). */
  IL.setNewSeat = function (atMs) {
    S.newSeatAtMs = atMs || null;
    lsSet('il.newSeatAtMs', atMs ? JSON.stringify(atMs) : null);
    attestVersion++;
    IL.render();
  };
  // both live in this browser's localStorage: an answer or a tick in another tab counts here at once (without this a
  // second tab stayed "Ready to run" on the old seat's surface)
  window.addEventListener('storage', (e) => {
    if (e.key !== null && e.key !== ATTEST_KEY && e.key !== 'il.newSeatAtMs') return;
    S.newSeatAtMs = lsJson('il.newSeatAtMs', null);
    attestVersion++;
    IL.render();
  });

  // ================================================================================== machine helpers
  /** Tool positions from status.machine.positions: {n1, n2 (the dispenser nozzle), cam}. */
  IL.positions = function (status) {
    const s = status || S.status;
    const out = { n1: null, n2: null, cam: null };
    if (!s || !s.machine) return out;
    const all = s.machine.positions || [];
    const disp = s.dispense && s.dispense.nozzle;
    out.n2 = all.find((t) => t.kind === 'nozzle' && t.name === disp) || null;
    const others = all.filter((t) => t.kind === 'nozzle' && t.name !== disp);
    out.n1 = others.find((t) => t.name === 'N1') || others[0] || null;
    out.cam = all.find((t) => t.kind === 'camera') || null;
    return out;
  };
  /** mm from the dispenser needle (N2) to the waste place in X/Y, or null if either is unknown. */
  IL.wasteDistance = function (status) {
    const s = status || S.status;
    const d = s && s.dispense;
    const w = d && d.locations && d.locations[d.wasteLocationName];
    const n2 = IL.positions(s).n2;
    if (!w || !n2 || !isNum(w.x) || !isNum(w.y)) return null;
    return Math.hypot(n2.x - w.x, n2.y - w.y);
  };
  /** True when OpenPnP drives the simulator (TCP to localhost) rather than a serial controller. */
  IL.isSim = function (status) {
    const s = status || S.status;
    if (!s) return false;
    if (s.server && typeof s.server.simulated === 'boolean') return s.server.simulated;
    const drv = (s.machine && s.machine.drivers) || [];
    return drv.some((x) => /127\.0\.0\.1|localhost|::1|simulat/i.test(String(x.connection || '')));
  };
  IL.taskRunning = (s) => !!(s && ((s.run && s.run.running) || (s.dispense && s.dispense.busy)));
  /** Anything energised or moving: a task, a pressure hold (incl. switching and vent_failed), homing. */
  IL.energised = (s) => !!s && (IL.taskRunning(s)
    || !!(s.pressure && ((s.pressure.state && s.pressure.state !== 'off') || s.pressure.engineRunActive))
    || !!(s.machine && s.machine.busy));
  IL.interlock = (s, id) => ((s && s.interlocks) || []).find((i) => i.id === id) || null;
  /**
   * Why a control is locked now, or null. need: {connected, homed, idle (default true), pressureOff (default true),
   * engine}. Texts are the spec's (§13 "Locked control").
   */
  IL.lockReason = function (status, need) {
    const s = status || S.status;
    const n = Object.assign({ idle: true, pressureOff: true }, need || {});
    if (S.online === false) return 'No answer from OpenPnP: nothing can be sent until it answers again.';
    if (!s) return 'Waiting for OpenPnP';
    const m = s.machine || {}, d = s.dispense || {}, pr = s.pressure || {};
    if (n.idle && IL.taskRunning(s)) {
      const lab = s.run && s.run.running ? s.run.label : 'task';
      return lab === 'board' ? 'Locked while the board runs' : 'Locked while the ' + lab + ' runs';
    }
    if (n.pressureOff && pr.state && pr.state !== 'off') return 'Pressure is held: switch it off first';
    if (n.connected && !m.enabled) return 'Not connected: connect first';
    if (n.homed && !m.homed) return 'Not homed: home first';
    if (n.engine && (!d.running || !d.available)) return 'Dispenser unavailable' + (d.unavailableReason ? ': ' + d.unavailableReason : '');
    return null;
  };
  /**
   * Disables a control with its reason (title, and reasonEl text + visibility if given); reason null enables it again,
   * except while IL.act runs it (aria-busy). A pending two-press on a control that becomes locked is cancelled.
   */
  IL.gate = function (btn, reason, reasonEl) {
    if (!btn) return;
    const r = reason || null;
    if (r && armed && armed.btn === btn) disarm();
    const dis = !!r || btn.getAttribute('aria-busy') === 'true';   // IL.act keeps its button disabled until it ends
    if (btn.disabled !== dis) btn.disabled = dis;
    if (r) { if (btn.dataset.titleIdle === undefined) btn.dataset.titleIdle = btn.title || ''; IL.setAttr(btn, 'title', r); }
    else if (btn.dataset.titleIdle !== undefined) { IL.setAttr(btn, 'title', btn.dataset.titleIdle || null); delete btn.dataset.titleIdle; }
    if (reasonEl) { IL.setText(reasonEl, r || ''); IL.show(reasonEl, !!r); }
  };

  // ================================================================================== plan
  /** "7f3c…-subset-2" → "7f3c…". */
  IL.basePlanId = (id) => (id ? String(id).replace(/-subset-.*$/, '') : id);
  /** True if planId is the plan shown or one of its subsets. */
  IL.planMatches = (planId) => !!(S.plan && planId && IL.basePlanId(planId) === S.plan.planId);
  /** Shows this plan (after POST /api/plan or a restore): remembered in localStorage, emits 'plan'. */
  IL.setPlan = function (plan) {
    const prev = S.plan;
    S.plan = plan || null;
    S.planId = plan ? plan.planId : null;
    S.planForgotten = false;
    S.planAt = Date.now();
    if (plan) lsSet('il.planId', plan.planId);
    if (!prev || !plan || prev.planId !== (plan && plan.planId)) S.cleanCheck = null;
    if (plan && plan.cleanCheck) IL.adoptCleanCheck(plan.cleanCheck, plan.planId);
    planVersion++;
    IL.emit('plan', S.plan, prev);
    IL.render();
    return plan;
  };
  IL.clearPlan = function () {
    S.planId = null;
    lsSet('il.planId', null);
    return IL.setPlan(null);
  };
  /** Loads a plan by id (restore); 404 → S.planForgotten (plan chip "Plan forgotten (OpenPnP restarted)"). */
  IL.loadPlan = async function (id) {
    if (!id) return null;
    S.planId = id;
    try {
      return IL.setPlan(await IL.api('GET', '/api/plan/' + encodeURIComponent(id)));
    } catch (e) {
      if (e.status === 404 && S.planId === id) {
        const prev = S.plan;
        S.plan = null;
        S.planForgotten = true;
        planVersion++;
        IL.emit('plan', null, prev);
        IL.render();
      }
      return null;
    }
  };
  /** Re-reads the plan shown (fresh interlocks, clean-pad report) and notifies IL.on('plan'). */
  IL.planRefresh = async function (opts) {
    const id = S.planId;
    if (!id || S.planForgotten) return null;
    const pending = !(opts && opts.quiet);
    if (pending) { S.planPending = true; planVersion++; IL.render(); }
    try {
      const q = await IL.api('GET', '/api/plan/' + encodeURIComponent(id));
      if (S.planId === id) {
        const prev = S.plan;
        S.plan = q;
        S.planAt = Date.now();
        if (q.cleanCheck) IL.adoptCleanCheck(q.cleanCheck, q.planId);
        planVersion++;
        IL.emit('plan', q, prev);
      }
      return q;
    } catch (e) {
      if (e.status === 404 && S.planId === id) {
        const prev = S.plan;
        S.plan = null;
        S.planForgotten = true;
        planVersion++;
        IL.emit('plan', null, prev);
      }
      return null;
    } finally {
      if (pending) { S.planPending = false; planVersion++; }
      IL.render();
    }
  };
  let planTimer = null;
  /** Debounced (300 ms) IL.planRefresh(): call it after anything that can change the plan's interlocks. */
  IL.planRefreshSoon = function () {
    if (!S.planId) return;
    clearTimeout(planTimer);
    S.planPending = true;
    planVersion++;
    planTimer = setTimeout(() => { IL.planRefresh(); }, L.planDebounceMs);
  };
  /** Adopts a clean-pad report of the plan shown (or a subset) if newer than the one shown. */
  IL.adoptCleanCheck = function (cc, planId) {
    if (!cc || !IL.planMatches(planId || cc.planId)) return false;
    if (S.cleanCheck && S.cleanCheck.checkedAtMs >= cc.checkedAtMs) return false;
    S.cleanCheck = cc;
    planVersion++;
    IL.emit('cleancheck', cc);
    return true;
  };
  /** Dirty pads of a report, worst first: [{padId, share, error}]. */
  IL.dirtyPads = function (cc) {
    if (!cc || !cc.pads) return [];
    return cc.pads.filter((x) => !x.clean)
      .map((x) => ({ padId: x.padId, share: x.brightFraction, error: x.error }))
      .sort((a, b) => (a.share === null ? -1 : b.share === null ? 1 : a.share - b.share));
  };
  /** The machine-readable first sentence of a clean-pad report (spec §8.1.4, `#clean-result`). */
  /** True when a finished report found no clean pad at all: the board position is wrong, not every pad dirty (§13). */
  IL.cleanPositionWrong = function (cc) {
    if (!cc || cc.ok || cc.complete === false || !(cc.targets > 0)) return false;
    return !(cc.pads || []).some((x) => x.clean);
  };
  IL.cleanSummary = function (cc) {
    if (!cc) return '';
    const t = IL.fmtTime(cc.checkedAtMs);
    const n = cc.targets || 0;
    if (!n) return 'No pad was checked: ' + (cc.reason || cc.message || 'the camera gave no picture') + '.';
    const clean = (cc.pads || []).filter((x) => x.clean).length;
    if (cc.complete === false) return 'The check did not finish: ' + clean + ' of ' + n + ' pads looked clean (' + t + ').';
    if (cc.ok) return 'All ' + n + ' pads are clean (' + t + ').';
    if (clean === 0) return '0 of ' + n + ' pads clean: the board position looks wrong (solder mask under every box), not ' + n + ' dirty pads.';
    return clean + ' of ' + n + ' pads clean, ' + (n - clean) + ' with paste left (' + t + ').';
  };
  /**
   * Background loads: the data in S[key], the failure in S.loadErrors[key] ({text, atMs}; cleared by the next good load)
   * so a view waiting for it can say why and offer Retry instead of "Loading…" forever.
   */
  S.loadErrors = {};
  async function load(key, path) {
    try {
      S[key] = await IL.api('GET', path);
      if (S.loadErrors[key]) delete S.loadErrors[key];
      IL.emit(key, S[key]);
    } catch (e) {
      const text = IL.errorText(e);
      const prevErr = S.loadErrors[key];
      S.loadErrors[key] = { text, atMs: Date.now() };
      if (!prevErr || prevErr.text !== text) IL.log('Could not load ' + key + ': ' + text, 'need');
      IL.emit(key, S[key]);
    }
    IL.render();
    return S[key];
  }
  IL.loadRuns = () => load('runs', '/api/runs?limit=50');
  IL.loadConfig = () => load('config', '/api/config');
  IL.loadEvents = (limit) => load('events', '/api/events?limit=' + (limit || 50));
  /** The failure of the last background load of key ('runs' | 'config' | 'events') or null. */
  IL.loadError = (key) => S.loadErrors[key] || null;

  // ================================================================================== readiness (§9)
  IL.READY_ORDER = ['machine', 'angle', 'needle', 'regulator', 'padtypes', 'surface', 'plan', 'purge', 'clean'];
  const READY_OK = new Set(['ok', 'you', 'off']);
  /** Which readiness item an I4 message belongs to. */
  function i4Kind(msg, boardSurface) {
    const t = String(msg || '').toLowerCase();
    if (/needle z frame|needle was changed|needle z offset/.test(t)) return 'needle';
    if (/^place '/.test(t)) return 'place';
    if (/the plan was made/.test(t)) return 'plan';
    const m = /'([^']+)' surface/.exec(String(msg || ''));
    if (m && m[1] !== boardSurface) return 'other-surface';
    if (/not tied|another registration|^no '/.test(t)) return 'seat';
    return 'surface-bad';
  }
  IL.i4Kind = i4Kind;
  const sentence = (t) => { const x = cap(String(t || '').trim()); return x && !/[.:!?]$/.test(x) ? x + '.' : x; };

  /**
   * THE readiness model (spec §9): pure, no DOM, no store. Nothing else may compute "ready".
   *   status: GET /api/status; plan: the plan shown (or null);
   *   ctx: {nowMs, attest (IL.attest.all()), planPending, planForgotten, cleanCheck, newSeatAtMs}
   * Returns {items[9], extras[], count, total, next, needs[], blocked[], verdict:{state,text,title}, canRun,
   *   runStyle: 'go'|'hold'|'blocked', runReason, planLabel}.
   * Item: {key, n, code, title, state: ok|you|off|need|block|live|checking|wait, ready, value, short, detail, tip,
   *   fixes: [{fix, label, arg?, primary?} | {href, label} | {view, label}]}.
   */
  IL.readiness = function (status, plan, ctx) {
    const c = ctx || {};
    const s = status || {};
    const m = s.machine || {};
    const d = s.dispense || {};
    const prof = d.profile || {};
    const nd = d.needle || {};
    const now = c.nowMs || Date.now();
    const ilk = {};
    for (const i of s.interlocks || []) ilk[i.id] = i;
    const viol = (plan && plan.interlocks && plan.interlocks.violations) || [];
    const bsName = d.boardSurfaceName || 'board';
    const i4 = viol.filter((v) => v.code === 'I4').map((v) => ({ v, kind: i4Kind(v.message, bsName) }));
    const items = [];
    const extras = [];
    const add = (it) => {
      it.n = items.length + 1;
      it.ready = READY_OK.has(it.state);
      it.fixes = it.fixes || [];
      it.tip = it.tip || it.detail;
      items.push(it);
    };
    const sim = IL.isSim(s);

    // 1 Machine
    if (!m.enabled) {
      add({ key: 'machine', code: 'MACHINE', title: 'Machine', state: 'block', value: 'not connected', short: 'connect',
        detail: 'Not connected to the controller.', fixes: [{ fix: 'connect', label: 'Connect', primary: true }] });
    } else if (!m.homed) {
      add(m.busy
        ? { key: 'machine', code: 'MACHINE', title: 'Machine', state: 'live', value: 'homing', short: 'homing', detail: 'Homing…' }
        : { key: 'machine', code: 'MACHINE', title: 'Machine', state: 'need', value: 'not homed', short: 'home',
          detail: 'Not homed: positions are unknown (always after STOP).', fixes: [{ fix: 'home', label: 'Home' }] });
    } else if (m.busy && !IL.taskRunning(s)) {
      add({ key: 'machine', code: 'MACHINE', title: 'Machine', state: 'live', value: 'moving', short: 'machine moving', detail: 'The machine is moving.' });
    } else {
      add({ key: 'machine', code: 'MACHINE', title: 'Machine', state: 'ok', value: c.homedAt ? IL.fmtTime(c.homedAt) : 'homed',
        short: 'machine', detail: 'Connected and homed.' });
    }

    // 2 Start angle (I11)
    const i11 = ilk.I11;
    const angle = IL.fmtDeg(prof.startAngleDeg, 1);
    if ((i11 && i11.state === 'off') || prof.partialTurn === false) {
      add({ key: 'angle', code: 'I11', title: 'Start angle', state: 'off', value: 'whole turns', short: 'start angle',
        detail: 'Whole turns per dot: the start angle does not matter.' });
    } else if (!prof.startAngleSetAtMs) {
      add({ key: 'angle', code: 'I11', title: 'Start angle', state: 'block', value: 'not set', short: 'start angle',
        detail: 'The start angle was never set.', fixes: [{ href: '#calibrate/angle', label: 'Measure the angle' }] });
    } else if (prof.startAngleNeedsConfirm || (i11 && i11.state === 'blocked')) {
      add({ key: 'angle', code: 'I11', title: 'Start angle', state: 'need', value: 'confirm', short: 'start angle',
        detail: 'The controller reconnected after the start angle was set'
          + (prof.controllerConnectedAtMs ? ' (connected ' + IL.fmtTime(prof.controllerConnectedAtMs) + ')' : '')
          + ': was it switched off since ' + IL.fmtTime(prof.startAngleSetAtMs) + '?',
        fixes: [{ fix: 'I11-confirm', label: 'No, it stayed on: ' + angle + ' still holds' },
          { href: '#calibrate/angle', label: 'Yes, it was off: measure again' }] });
    } else {
      add({ key: 'angle', code: 'I11', title: 'Start angle', state: 'ok', value: angle, short: 'start angle',
        detail: 'Screw start angle ' + angle + ', set or confirmed ' + IL.fmtTime(prof.startAngleSetAtMs) + ' for this controller power-up.' });
    }

    // 3 Needle offset (I4 needle)
    const drift = isNum(nd.zOffsetMm) && isNum(nd.anchorOffsetMm) ? nd.zOffsetMm - nd.anchorOffsetMm : 0;
    if (!d.running) {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'wait', value: '—', short: 'needle', detail: 'The dispenser engine is not running.' });
    } else if (!nd.anchor) {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'block', value: 'no frame', short: 'needle Z frame',
        detail: 'The needle Z frame is not set.', fixes: [{ href: '#setup/needle', label: 'Open Needle Z frame' }] });
    } else if (!nd.anchored) {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'block', value: 'changed', short: 'needle Z frame',
        detail: 'The needle was changed after the needle Z frame was recorded.', fixes: [{ href: '#setup/needle', label: 'Open Needle Z frame' }] });
    } else if (!nd.touchedOff) {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'need', value: 'confirm', short: 'needle offset',
        detail: 'Needle offset not confirmed since the last needle change or frame.',
        fixes: [{ fix: 'needle-confirm', label: 'Confirm frame offset ' + IL.fmt(nd.anchorOffsetMm, 3) + ' mm' },
          { href: '#calibrate/touch-off', label: 'Run a touch-off' }] });
    } else if (Math.abs(drift) > L.needleDriftMm) {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'block', value: 'drift', short: 'needle offset',
        detail: 'Needle offset ' + IL.fmt(nd.zOffsetMm, 3) + ' mm is ' + IL.fmt(Math.abs(drift), 3) + ' mm from the frame (limit '
          + IL.fmt(L.needleDriftMm, 2) + ' mm).',
        fixes: [{ href: '#setup/needle', label: 'Record the frame again' }, { href: '#calibrate/touch-off', label: 'Touch off again' }] });
    } else {
      add({ key: 'needle', code: 'I4', title: 'Needle', state: 'ok', value: IL.fmt(nd.zOffsetMm, 3) + ' mm', short: 'needle',
        detail: 'Needle Z offset ' + IL.fmt(nd.zOffsetMm, 3) + ' mm (frame ' + IL.fmt(nd.anchorOffsetMm, 3) + ' mm).' });
    }

    // 4 Regulator (I6, attested by the operator)
    const psi = IL.fmtPsi(prof.pressurePsi);
    const reg = attestValid((c.attest || {}).regulator, 'regulator', s, now);
    if (reg) {
      add({ key: 'regulator', code: 'I6', title: 'Regulator', state: 'you', value: psi + ' psi', short: 'regulator',
        you: 'you, ' + IL.fmtTime(reg.atMs), detail: 'You read ' + psi + ' psi on the gauge at ' + IL.fmtTime(reg.atMs) + ' (no sensor).' });
    } else {
      add({ key: 'regulator', code: 'I6', title: 'Regulator', state: 'need', value: 'read gauge', short: 'read gauge',
        detail: 'Set the regulator to ' + psi + ' psi for profile ' + (prof.name || '') + ' (no sensor).',
        fixes: [{ view: 'pressure', label: 'Pressure on to read the gauge' }, { fix: 'attest-regulator', label: 'Reads ' + psi + ' psi' }] });
    }

    // 5 Pad types (PLAN)
    const gate = d.minPadTypeStatus || 'the gate';
    if (!plan) {
      add({ key: 'padtypes', code: 'PLAN', title: 'Pad types', state: 'wait', value: '—', short: 'pad types', detail: 'Known after planning.' });
    } else {
      const pads = plan.pads || [];
      const blocked = pads.filter((p) => p.blocked);
      // PLAN violations about a change after planning (gate, pad type versions) make the plan stale: item 7
      const planViol = viol.filter((v) => v.code === 'PLAN' && !/after planning/i.test(v.message));
      if (blocked.length || (planViol.length && plan.dots && plan.dots.length)) {
        const groups = new Map();
        let belowGate = 0;
        for (const p of blocked) {
          const k = p.typeKey || p.packageId || 'no pad type';
          const below = (p.flags || []).includes('STATUS_BELOW_MIN');
          if (below) belowGate++;
          const g = groups.get(k) || { key: p.typeKey, pkg: p.packageId, status: p.typeStatus, below, n: 0 };
          g.n++;
          groups.set(k, g);
        }
        const list = Array.from(groups.entries()).map(([k, g]) => k + ' ×' + g.n
          + (g.below ? ' (' + (g.status || 'no status') + ', below the gate ' + gate + ')' : !g.key ? ' (no pad type)' : '')).join(', ');
        const fixes = [];
        if (belowGate) fixes.push({ href: '#setup/settings/min-status', label: 'Lowest pad-type status' });
        // per type: set its status (pad-type side panel); a pad with no type: the pad-type dialog prefilled (§13 PLAN)
        for (const g of Array.from(groups.values()).slice(0, 3)) {
          fixes.push(g.key ? { href: '#profile/pad-types/' + encodeURIComponent(g.key), label: (g.below ? 'Set the status of ' : 'Open ') + g.key }
            : { href: '#profile/new-pad-type/' + encodeURIComponent(g.pkg || ''), label: 'New pad type' + (g.pkg ? ' for ' + g.pkg : '') });
        }
        add({ key: 'padtypes', code: 'PLAN', title: 'Pad types', state: 'block', value: (pads.length - blocked.length) + '/' + pads.length,
          short: 'pad types', chip: blocked.length ? IL.plural(blocked.length, 'pad') + ' cannot be dispensed' : 'pad types',
          detail: blocked.length ? IL.plural(blocked.length, 'pad') + ' cannot be dispensed: ' + list + '.' : sentence(planViol[0].message), fixes });
      } else if (!plan.dots || !plan.dots.length) {
        add({ key: 'padtypes', code: 'PLAN', title: 'Pad types', state: 'block', value: 'no dots', short: 'no dots', chip: 'no dots',
          detail: 'This plan has no dots: the board has no pads with a dispensable pad type.', fixes: [{ href: '#run/board', label: 'Choose another board' }] });
      } else {
        add({ key: 'padtypes', code: 'PLAN', title: 'Pad types', state: 'ok', value: pads.length + '/' + pads.length, short: 'pad types',
          detail: pads.length + ' of ' + pads.length + ' pads have a pad type at or above ' + (d.minPadTypeStatus || 'the gate') + '.' });
      }
    }

    // 6 Board surface (I4 board)
    const sf = (d.surfaces || {})[bsName];
    const newSeat = !!c.newSeatAtMs && (!sf || !sf.setAtMs || sf.setAtMs < c.newSeatAtMs + S.skewMs);
    if (!plan) {
      add({ key: 'surface', code: 'I4', title: 'Surface', state: 'wait', value: '—', short: 'board surface', detail: 'Known after planning.' });
    } else {
      const bad = i4.filter((x) => x.kind === 'surface-bad');
      const seat = i4.filter((x) => x.kind === 'seat');
      // seat: 'new' (the operator said re-seated), 'none' (no surface), 'other' (probed on another registration),
      // 'untied' (saved without a plan: saving the same points with this plan ties them); set in the block state too
      // (a moved board's dots usually fall outside the old seat's surface), Setup's editor reads it
      const which = !(seat.length || newSeat || !sf) ? undefined : newSeat ? 'new'
        : !sf || /^no '/i.test(seat[0] ? seat[0].v.message : 'no ') ? 'none'
          : /another registration/.test(seat[0].v.message) ? 'other' : 'untied';
      const why = which && { new: 'You said the board was re-seated: probe this seat.', none: 'No board surface yet: probe this seat.',
        other: 'The board surface belongs to another seat of this board.', untied: 'The board surface is not tied to this plan.' }[which];
      const seatFixes = [{ fix: 'probe', label: 'Probe this seat', primary: true }];
      if (newSeat) seatFixes.push({ fix: 'same-seat', label: 'Same board, not moved' });
      if (bad.length) {
        add({ key: 'surface', code: 'I4', title: 'Surface', state: 'block', value: 'probe again', short: 'board surface',
          detail: bad.map((x) => sentence(x.v.message)).concat(why || []).join(' '),
          fixes: which ? seatFixes : [{ fix: 'probe', label: 'Add probe points' }], seat: which });
      } else if (which) {
        add({ key: 'surface', code: 'I4', title: 'Surface', state: 'need', value: 'probe', short: 'probe seat', detail: why, fixes: seatFixes, seat: which });
      } else {
        add({ key: 'surface', code: 'I4', title: 'Surface', state: 'ok', value: 'tied', short: 'board surface',
          detail: 'Board surface: ' + IL.plural((sf.points || []).length, 'point') + (sf.plane ? ', worst ' + IL.fmt(sf.plane.maxResidualMm, 3) + ' mm' : '')
            + ', tied to this plan.' });
      }
    }

    // 7 Plan current
    const shortId = plan ? String(plan.planId).slice(0, 4) : '';
    if (!plan && c.planForgotten) {
      add({ key: 'plan', code: 'PLAN', title: 'Plan', state: 'need', value: 'forgotten', short: 'plan again',
        detail: 'Plan forgotten (OpenPnP restarted): plan again', fixes: [{ fix: 'plan-again', label: 'Plan again', primary: true }] });
    } else if (!plan) {
      add({ key: 'plan', code: 'PLAN', title: 'Plan', state: 'need', value: 'no plan', short: 'plan a board',
        detail: 'No plan yet: choose a board and plan it.', fixes: [{ href: '#run/board', label: 'Choose a board' }] });
    } else if (c.planPending) {
      add({ key: 'plan', code: 'PLAN', title: 'Plan', state: 'checking', value: 'checking…', short: 'checking', detail: 'Re-reading the plan…' });
    } else {
      const why = [];
      const at = [];
      // the start angle is part of the profile fingerprint: name it apart when it is the only difference
      const noStart = (f) => String(f || '').replace(/\bstart=[^ ]*/, '');
      if (prof.fingerprint && plan.profileFingerprint && prof.fingerprint !== plan.profileFingerprint) {
        if (noStart(prof.fingerprint) !== noStart(plan.profileFingerprint)) { why.push('the profile'); at.push(c.profileChangedAt); }
        else { why.push('the start angle'); at.push(prof.startAngleSetAtMs ? prof.startAngleSetAtMs - S.skewMs : null); }
      }
      if (isNum(plan.needleZOffsetMm) && isNum(nd.zOffsetMm) && Math.abs(plan.needleZOffsetMm - nd.zOffsetMm) > 1e-6) {
        why.push('the needle offset');
        at.push(nd.setAtMs ? nd.setAtMs - S.skewMs : null);
      }
      if (i4.some((x) => x.kind === 'plan')) { why.push('the board surface'); at.push(sf && sf.setAtMs ? sf.setAtMs - S.skewMs : null); }
      // the engine re-checks the pad types of the plan against the gate and the pad types now
      for (const v of viol.filter((x) => x.code === 'PLAN' && /after planning/i.test(x.message))) {
        why.push(/gate/i.test(v.message) ? 'the pad-type gate' : 'the pad types');
        at.push(null);
      }
      if (why.length) {
        const when = at.filter(Boolean).reduce((a, b) => Math.max(a, b), 0) || null;
        add({ key: 'plan', code: 'PLAN', title: 'Plan', state: 'need', value: 'stale', short: 'plan again', stale: why, staleAt: when,
          detail: 'Plan ' + shortId + ' is stale: ' + why.join(', ') + ' changed since planning' + (when ? ' (' + IL.fmtTime(when) + ')' : '') + '.',
          fixes: [{ fix: 'plan-again', label: 'Plan again', primary: true }] });
      } else {
        add({ key: 'plan', code: 'PLAN', title: 'Plan', state: 'ok', value: 'current', short: 'plan',
          detail: 'Plan ' + shortId + ' made ' + IL.fmtTime(plan.createdAtMs) + '; nothing changed since.' });
      }
    }

    // 8 Purge row (I9)
    const pr = d.purgeRow || {};
    if (!pr.defined) {
      add({ key: 'purge', code: 'I9', title: 'Purge row', state: 'block', value: 'not set', short: 'purge row',
        detail: 'The purge row is not set.', fixes: [{ href: '#setup/purge', label: 'Set the purge row' }] });
    } else if (pr.surfaceProbed === false) {
      add({ key: 'purge', code: 'I9', title: 'Purge row', state: 'block', value: 'no surface', short: 'purge surface',
        detail: "The purge row's surface '" + pr.surfaceName + "' is not probed.", fixes: [{ href: '#setup/surfaces', label: 'Probe the blank' }] });
    } else if (pr.spots < pr.perBoard) {
      add({ key: 'purge', code: 'I9', title: 'Purge row', state: 'block', value: 'too short', short: 'purge row too short',
        detail: 'The row has ' + IL.plural(pr.spots, 'spot') + '; a board purges ' + pr.perBoard + '. A reset cannot fix this: give the row more spots, or purge fewer dots in the profile.',
        fixes: [{ href: '#setup/purge', label: 'Purge row: more spots' }, { href: '#profile', label: 'Profile: purge dots' }] });
    } else if (pr.spotsLeft < pr.perBoard) {
      add({ key: 'purge', code: 'I9', title: 'Purge row', state: 'need', value: pr.spotsLeft + '/' + pr.spots, short: 'wipe row',
        detail: 'Only ' + pr.spotsLeft + ' unused purge spots left, ' + pr.perBoard + ' needed. Wipe the row, then reset it.',
        fixes: [{ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }] });
    } else {
      add({ key: 'purge', code: 'I9', title: 'Purge row', state: 'ok', value: pr.spotsLeft + '/' + pr.spots, short: 'purge row',
        detail: pr.spotsLeft + ' of ' + pr.spots + ' spots free; a board uses ' + pr.perBoard + '.' });
    }

    // 9 Pads clean (I10)
    const cpc = d.cleanPadCheck || {};
    const i10 = ilk.I10;
    const cc = c.cleanCheck && plan && IL.basePlanId(c.cleanCheck.planId || plan.planId) === plan.planId ? c.cleanCheck : null;
    const runChecking = s.run && s.run.running && s.run.label === 'clean-pad check';
    // "0 of 160 clean": the board position is wrong, not every pad dirty (§13): check the position, do not wipe
    const posWrong = IL.cleanPositionWrong(cc) && !runChecking;
    if (posWrong) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'need', value: 'position?', short: 'check position', posWrong: true,
        detail: IL.cleanSummary(cc) + ' Check the board X/Y and rotation in the Board step, then check the pads again.'
          + (cpc.enabled ? '' : ' The check before each board is off: the run will not look again.'),
        fixes: [{ href: '#run/board', label: 'Check the board position', primary: true }, { fix: 'clean-check', label: 'Check again' }] });
    } else if (!cpc.enabled && cc && !cc.ok && !runChecking) {
      // the check is off, but a check of this plan found paste: never "ready" next to red pads (the run will not look again)
      const n = (cc.dirtyPadIds || IL.dirtyPads(cc)).length;
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'need', value: n + ' dirty', short: n ? 'wipe ' + IL.plural(n, 'pad') : 'check pads',
        detail: IL.cleanSummary(cc) + ' The check before each board is off: the run will not look again.',
        dirty: IL.dirtyPads(cc), fixes: [{ fix: 'clean-check', label: 'Wiped: check again' }] });
    } else if (!cpc.enabled) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'off', value: 'off', short: 'pads clean',
        detail: sim ? 'Off (simulator): the simulated camera is a static picture.' : 'Off: not checked before a board.' });
    } else if (i10 && i10.state === 'blocked') {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'block', value: 'no camera', short: 'pad check camera',
        detail: 'Clean-pad check is on but there is no top camera.', fixes: [{ href: '#setup/settings', label: 'Switch the check off in Settings' }] });
    } else if (!plan) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'wait', value: '—', short: 'pads clean', detail: 'Known after planning.' });
    } else if (plan.cleanCheckRunning || runChecking) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'live', value: 'checking', short: 'pad check', detail: 'The top camera visits every pad (pressure off).' });
    } else if (!cc) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'need', value: 'not checked', short: 'check pads',
        detail: 'Not checked yet. The run checks every pad first (≈ 3 min); checking now lets you wipe before the run.',
        fixes: [{ fix: 'clean-check', label: 'Check pads' }] });
    } else if (cc.ok) {
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'ok', value: 'clean ' + IL.fmtTime(cc.checkedAtMs), short: 'pads clean',
        detail: IL.cleanSummary(cc) });
    } else {
      const dirty = IL.dirtyPads(cc);
      const named = dirty.slice(0, 3).map((x) => x.padId + (x.share === null || x.share === undefined ? '' : ' (' + IL.fmt(x.share, 2) + ')')).join(', ');
      const n = (cc.dirtyPadIds || dirty).length;
      add({ key: 'clean', code: 'I10', title: 'Pads clean', state: 'need', value: n + ' dirty', short: n ? 'wipe ' + IL.plural(n, 'pad') : 'check pads',
        detail: IL.cleanSummary(cc) + (named ? ' Paste on ' + named + (dirty.length > 3 ? ' and ' + (dirty.length - 3) + ' more' : '') + '.' : ''),
        dirty, fixes: [{ fix: 'clean-check', label: 'Wiped: check again' }] });
    }

    // Extras: other interlocks that block (or I1 warning), and plan violations no item covers
    const seen = new Set();
    const addExtra = (x) => {
      const k = x.code + '|' + x.detail;
      if (seen.has(k)) return;
      seen.add(k);
      x.ready = false;
      x.fixes = x.fixes || [];
      x.tip = x.tip || x.detail;
      extras.push(x);
    };
    if (!d.running || !d.available) {
      addExtra({ key: 'engine', code: 'ENGINE', title: 'Dispenser', state: 'block', short: 'dispenser unavailable',
        detail: 'The dispenser is unavailable' + (d.unavailableReason ? ': ' + d.unavailableReason : '') + '.' });
    }
    const hold = s.pressure && s.pressure.state;
    if (hold === 'vent_failed') {
      addExtra({ key: 'pressure', code: 'I1', title: 'Pressure', state: 'block', short: 'pressure NOT off',
        detail: 'Pressure is NOT off: the valve did not confirm. Press Pressure off or STOP again.', fixes: [{ fix: 'pressure-off', label: 'Pressure off', primary: true }] });
    } else if (hold && hold !== 'off') {
      addExtra({ key: 'pressure', code: 'I7', title: 'Pressure', state: 'need', short: 'pressure off',
        detail: 'The pressure is held by hand: switch it off before a run (the run switches it on and off itself).', fixes: [{ fix: 'pressure-off', label: 'Pressure off', primary: true }] });
    }
    for (const code of ['CONFIG', 'I1', 'I2', 'I3', 'I5', 'I7', 'I8']) {
      const i = ilk[code];
      if (!i) continue;
      if (i.state === 'blocked') addExtra(extraFor(code, i.text, 'block', i));
      else if (i.state === 'warning') addExtra(extraFor(code, i.text, 'need', i));
    }
    if (ilk.I4 && ilk.I4.state === 'blocked' && items[2].ready) addExtra({ key: 'I4', code: 'I4', title: 'Place heights', state: 'block', short: 'place heights', detail: sentence(ilk.I4.text), fixes: [{ href: '#setup/places', label: 'Open Places' }] });
    for (const v of viol) {
      if (['MACHINE', 'I9', 'I10', 'I11', 'PLAN'].includes(v.code)) continue;
      if (v.code === 'I4') {
        const k = i4Kind(v.message, bsName);
        if (k === 'place') addExtra({ key: 'I4-place', code: 'I4', title: 'Place height', state: 'block', short: 'place Z', detail: sentence(v.message), fixes: [{ href: '#setup/places', label: 'Retake Z with N1' }] });
        else if (k === 'other-surface') addExtra({ key: 'I4-surface', code: 'I4', title: 'Surface', state: 'block', short: 'blank surface', detail: sentence(v.message), fixes: [{ href: '#setup/surfaces', label: 'Open Probed surfaces' }] });
        continue;
      }
      addExtra(extraFor(v.code, v.message, 'block'));
    }

    // Verdict and the Run button
    const all = extras.concat(items);
    const notReady = all.filter((x) => !x.ready);
    const blocked = all.filter((x) => x.state === 'block');
    const count = items.filter((x) => x.ready).length;
    // NEXT: the first not-ready item in the fixed order; items that only wait for a plan never come first
    const next = notReady.find((x) => x.state !== 'wait') || notReady[0] || null;
    let verdict;
    if (blocked.length) verdict = { state: 'block', text: 'Blocked · ' + blocked[0].short, title: blocked.map((x) => x.title + ': ' + x.detail).join('\n') };
    else if (!plan && !c.planForgotten) verdict = { state: 'need', text: 'No plan yet', title: 'Choose a board and plan it (Run).' };
    else if (notReady.length) verdict = { state: 'need', text: count + ' of 9 · ' + next.short, title: notReady.map((x) => x.title + ': ' + x.detail).join('\n') };
    else verdict = { state: 'ok', text: 'Ready to run', title: 'All 9 checks are ready: a board can run now.' };
    const soft = new Set(['regulator', 'clean']);
    // items 4 (regulator: asked in the confirm) and 9 (pads clean: the run re-checks) may be not ready, unless blocked
    const hard = extras.concat(items.filter((x) => !soft.has(x.key) || x.state === 'block')).filter((x) => !x.ready);
    const idle = !IL.taskRunning(s) && !!d.running && !!d.available;
    const pressureOff = !s.pressure || !s.pressure.state || s.pressure.state === 'off';
    const canRun = !!plan && hard.length === 0 && idle && pressureOff;
    let runStyle = 'blocked', runReason = '';
    if (canRun) {
      const softNot = items.filter((x) => soft.has(x.key) && !x.ready && x.state !== 'block');
      runStyle = softNot.length ? 'hold' : 'go';
      if (softNot.some((x) => x.key === 'clean' && x.posWrong)) {
        runReason = softNot.length + ' needs you. The last pad check read no pad clean: check the board position before you run'
          + (cpc.enabled ? ' (the run checks every pad again first and refuses).' : '.');
      } else if (softNot.some((x) => x.key === 'clean') && !cpc.enabled) {
        runReason = softNot.length + ' needs you. Wipe the pads first: the check before each board is off, so the run dispenses on them as they are.';
      } else if (softNot.some((x) => x.key === 'clean')) {
        runReason = softNot.length + ' needs you. Run anyway: the run checks every pad first (≈ 3 min) and refuses while paste is left.';
      } else if (softNot.length) {
        runReason = softNot.length + ' needs you. Run anyway: the run confirm asks you to read the regulator gauge.';
      }
    } else if (!idle && IL.taskRunning(s)) {
      runReason = IL.lockReason(s) || 'Locked while the board runs';
    } else if (!pressureOff) {
      runReason = 'Pressure is held: switch it off first';
    } else if (!d.running || !d.available) {
      runReason = 'Dispenser unavailable' + (d.unavailableReason ? ': ' + d.unavailableReason : '') + '.';
    } else if (!plan) {
      runReason = 'Plan a board first.';
    } else {
      runReason = 'Finish ' + IL.plural(hard.length, 'check') + ' first: ' + hard.map((x) => x.short).join(', ') + '.';
    }
    return {
      items, extras, count, total: 9, next, needs: notReady, blocked, verdict, canRun, runStyle, runReason,
      planLabel: plan ? 'plan ' + shortId : '',
    };
  };
  function extraFor(code, text, state, i) {
    const t = String(text || '');
    const base = { key: code, code, state, short: code, fixes: [] };
    switch (code) {
      case 'CONFIG': return Object.assign(base, { title: 'Dispenser settings', short: 'settings', detail: 'Dispenser settings are not valid: ' + t + '.', fixes: [{ href: '#setup/settings', label: 'Open Settings' }] });
      case 'I1': {
        const det = (i && i.detail) || {};
        const miss = ['valveOff', 'pumpOff', 'quickStop'].filter((k) => det[k] === false);
        return Object.assign(base, { title: "OpenPnP's Stop button", short: 'Stop button', detail: "OpenPnP's own Stop button does not switch the pneumatics off."
          + (miss.length ? ' Missing: ' + miss.join(', ') + '.' : '') + " Close OpenPnP and install the machine.xml patch (dist/README.md). Until then use this page's STOP.",
        fixes: [{ view: 'interlocks', label: 'Show lines' }] });
      }
      case 'I3': return Object.assign(base, { title: 'Dispenser nozzle', short: 'N2 nozzle tips', detail: 'N2 has nozzle tips, so a pick could fire the paste pneumatics. In OpenPnP: remove N2\'s compatible nozzle tips / unload its tip.' });
      case 'I7': return Object.assign(base, { title: 'Pressure on-time', short: 'on-time', detail: sentence(t),
        fixes: [{ fix: 'run-fewer', label: 'Run fewer dots' }, { href: '#setup/settings/max-on', label: 'Raise the limit in Settings' }] });
      default: return Object.assign(base, { title: code, detail: sentence(t) });
    }
  }
  let readyMemo = null;
  /** The context IL.ready() passes to IL.readiness (from the store). */
  IL.readyCtx = () => ({ nowMs: Date.now(), attest: IL.attest.all(), planPending: S.planPending, planForgotten: S.planForgotten,
    cleanCheck: S.cleanCheck, newSeatAtMs: S.newSeatAtMs, homedAt: S.homedAt, profileChangedAt: S.profileChangedAt });
  /** Readiness of the store now (memoised per status / plan / attestation change). */
  IL.ready = function () {
    const key = S.statusAt + '|' + planVersion + '|' + attestVersion;
    if (readyMemo && readyMemo.key === key) return readyMemo.value;
    const value = IL.readiness(S.status, S.plan, IL.readyCtx());
    readyMemo = { key, value };
    return value;
  };

  // ================================================================================== verdict (strip "Board run")
  const TASK_WORD = { prime: 'Prime', 'test dot': 'Test dot', 'needle touch-off': 'Touch-off', 'dot ladder': 'Dot ladder',
    'scale shots': 'Shots', 'clean-pad check': 'Pad check', board: 'Board' };
  const VENT_S = 1;
  /**
   * Seconds left of a board run's dots (one number for the strip, the tiles and the timeline): the live rate blended
   * with the plan's estimate over the first 20 dots (the first dots alone swing the rate by minutes), plus the vent.
   */
  IL.boardLeftS = function (run) {
    if (!run || run.label !== 'board' || !isNum(run.dotsPlanned)) return null;
    const remain = Math.max(0, run.dotsPlanned - (run.dotsDone || 0));
    const p = S.plan;
    const sm = p && IL.planMatches(run.planId) ? p.summary || {} : null;
    const perPlan = sm && isNum(sm.estimatedWorkSeconds) && p.dots && p.dots.length ? sm.estimatedWorkSeconds / p.dots.length : null;
    const perLive = run.liveDph > 0 && run.dotsDone > 0 ? 3600 / run.liveDph : null;
    if (perLive === null && perPlan === null) return null;
    const w = perPlan === null ? 1 : perLive === null ? 0 : Math.min(1, run.dotsDone / 20);
    return remain * (w * (perLive || 0) + (1 - w) * (perPlan || 0)) + VENT_S;
  };
  /** The camera tour progress {k, n, left, approx} of a running check (run.js knows the per-pad time), or null. */
  function tourOf(run) {
    const n = S.plan && IL.planMatches(run.planId) ? (S.plan.pads || []).length : 0;
    return IL.tourProgress && n ? IL.tourProgress(run, run.elapsedS || 0, n) : null;
  }
  /** Live label parts of a running task: [main, long] ("96/160", " · 0:44 left"), ["Pad check ≈ 57/160", " · 1:52 left"]. */
  function taskParts(run) {
    if (!run) return ['', ''];
    const word = TASK_WORD[run.label] || cap(run.label || 'Task');
    const check = run.label === 'clean-pad check' || (run.label === 'board' && !(run.dotsDone > 0) && /I10|clean/i.test(run.phase || ''));
    if (check) {
      const tp = tourOf(run);
      return tp ? ['Pad check ' + (tp.approx ? '≈ ' : '') + tp.k + '/' + tp.n, ' · ' + IL.fmtClock(tp.left) + ' left'] : ['Pad check', ' · ' + IL.fmtClock(run.elapsedS) + ' so far'];
    }
    if (run.label === 'board') {
      if (run.dotsDone > 0) {
        const left = IL.boardLeftS(run);
        return [run.dotsDone + '/' + run.dotsPlanned, left !== null ? ' · ' + IL.fmtClock(left) + ' left' : ''];
      }
      return ['0/' + run.dotsPlanned, ' · starting'];
    }
    return [word, ''];
  }
  /** Short live label of a running task: "96/160 · 0:44 left", "Pad check ≈ 57/160 · 1:52 left", "Prime". */
  IL.taskLabel = (run) => taskParts(run).join('');
  /** The strip verdict now: {state, text, title} (task, recovery, offline and engine override the readiness). */
  IL.verdict = function () {
    const s = S.status;
    if (S.online === false) return { state: 'off', text: 'No answer', title: 'No answer from OpenPnP since ' + IL.fmtTimeS(S.lastOkAt) };
    if (!s) return { state: 'off', text: '…', title: 'Waiting for OpenPnP' };
    const run = s.run;
    if (run && run.running && !IL.stoppedRun(s)) {
      const tp = taskParts(run);
      return { state: 'live', text: tp.join(''), value: [tp[0], tp[1], 2], title: (TASK_WORD[run.label] || run.label) + ' running: ' + (run.phase || '') };
    }
    if (IL.recoveryActive()) {
      const r = S.recovery;
      return { state: 'block', text: 'Stopped ' + IL.fmtTime(r.atMs), title: recoveryHeadline(r) + '. Back to ready: see the Recovery panel.' };
    }
    const d = s.dispense || {};
    if (!d.running || !d.available) return { state: 'block', text: 'Dispenser unavailable', title: d.unavailableReason || 'The dispense engine is not running.' };
    return IL.ready().verdict;
  };

  // ================================================================================== recovery (§8.1.8)
  const REC_KEY = 'il.recovery';
  function startRecovery(r) {
    const prev = S.recovery;
    const rec = Object.assign({ atMs: Date.now(), cause: 'stop', actions: null, run: null, source: null, seenDown: false, purgeShort: false }, r);
    if (prev && Date.now() - prev.atMs < 15000) {
      // one event seen twice (our STOP answer, the ABORTED task, the machine going down): merge, STOP details win
      if (rec.cause === 'stop') Object.assign(prev, { cause: 'stop', atMs: rec.atMs, actions: rec.actions, source: rec.source });
      else if (rec.cause === 'error' && prev.cause !== 'stop') Object.assign(prev, { cause: 'error', message: rec.message });
      if (rec.run) prev.run = mergeRunSnap(prev.run, rec.run);
      prev.seenDown = prev.seenDown || rec.seenDown;
      saveRecovery();
      IL.render();
      return prev;
    }
    S.recovery = rec;
    recoveryDismissedWs = null;
    saveRecovery();
    IL.log(recoveryHeadline(rec), 'block');
    IL.emit('recovery', rec);
    IL.render();
    return rec;
  }
  /**
   * The same run seen twice (live when STOP answered, then its ABORTED end): take the newer counts and status, but keep
   * the phase it was interrupted in and its pressure on-time (the ended snapshot says "finished: ABORTED", no on-time).
   */
  function mergeRunSnap(a, b) {
    if (!a || !b || a.runId !== b.runId) return b || a;
    const m = Object.assign({}, a, b);
    if (/^finished/.test(String(b.phase || '')) && a.phase) m.phase = a.phase;
    if (b.pressureOnS === null || b.pressureOnS === undefined) m.pressureOnS = a.pressureOnS;
    if ((b.dotsDone || 0) < (a.dotsDone || 0)) { m.dotsDone = a.dotsDone; m.doneDots = a.doneDots; }
    return m;
  }
  function saveRecovery() {
    try { window.sessionStorage.setItem(REC_KEY, S.recovery ? JSON.stringify(S.recovery) : ''); } catch (e) { /* ignore */ }
  }
  function recoveryHeadline(r) {
    if (!r) return '';
    if (r.cause === 'error') return 'Ended by an error ' + IL.fmtTimeS(r.atMs);
    if (r.cause === 'openpnp') return 'Stopped in OpenPnP ' + IL.fmtTimeS(r.atMs);
    if (r.cause === 'aborted') return 'Aborted ' + IL.fmtTimeS(r.atMs);
    return 'Stopped ' + IL.fmtTimeS(r.atMs);
  }
  IL.recoveryHeadline = recoveryHeadline;
  function snapRun(run) {
    if (!run) return null;
    return { runId: run.runId, label: run.label, boardName: run.boardName, planId: run.planId, status: run.status,
      message: run.message, phase: run.phase, dotsDone: run.dotsDone, dotsPlanned: run.dotsPlanned,
      doneDots: run.doneDots ? run.doneDots.slice() : [], elapsedS: run.elapsedS,
      startedAtMs: run.startedAtMs, finishedAtMs: run.finishedAtMs, pressureOnAtMs: run.pressureOnAtMs,
      pressureOnS: S.runPressureSince ? (Date.now() - S.runPressureSince) / 1000 : null };
  }
  /**
   * The "Back to ready" steps for a recovery (spec §8.1.8): [{key, n, title, detail, done, wait, now, state, fixes}].
   * Keys: vent, connect, angle, home, purge (only when the row is or was short), decide (never done here).
   */
  IL.recoverySteps = function (status, rec) {
    const s = status || {};
    const m = s.machine || {}, d = s.dispense || {}, pr = s.pressure || {}, prof = d.profile || {}, row = d.purgeRow || {};
    const steps = [];
    // vented only once a status says so: the hold is off AND no run holds the barrel any more (right after STOP the
    // status can still show the stopped run dispensing for ~1 s: then it reads "venting…", never a tick next to "ON")
    const failed = pr.state === 'vent_failed';
    const holdOn = !!pr.state && pr.state !== 'off';
    const runHeld = !!(s.run && s.run.running && (IL.PRESSURE_PHASES.has(s.run.phase) || pr.engineRunActive));
    const vented = !holdOn && !runHeld;
    // STOP itself closes the valve (M107 P3): the hold's last event can be older than the STOP
    const lastEv = pr.lastEventAtMs ? pr.lastEventAtMs - S.skewMs : 0;
    const ventAt = rec && rec.cause === 'stop' ? Math.max(lastEv, rec.atMs || 0) : lastEv;
    // a hold still "on" in a status that arrived well after the STOP answer did not vent
    const lateOn = pr.state === 'on' && !!rec && S.statusAt - (rec.atMs || 0) > 1500;
    steps.push(failed || lateOn
      ? { key: 'vent', title: 'Pressure is NOT off', state: 'block', done: false,
        detail: 'Press Pressure off or STOP again.' + (pr.lastError ? ' ' + pr.lastError : ''), fixes: [{ fix: 'pressure-off', label: 'Pressure off', primary: true }] }
      : !vented
        ? { key: 'vent', title: 'Pressure venting…', state: 'checking', done: false, wait: true,
          detail: 'valve off, then the pump: waiting for OpenPnP to confirm', fixes: [] }
        : { key: 'vent', title: 'Pressure vented', state: 'ok', done: true,
          detail: 'valve closed' + (ventAt ? ' ' + IL.fmtTimeS(ventAt) : '') + ', pump off', fixes: [] });
    steps.push({ key: 'connect', title: 'Connect', detail: 'reconnects to the controller; nothing moves', done: !!m.enabled,
      fixes: m.enabled ? [] : [{ fix: 'connect', label: 'Connect', primary: true }] });
    const angleOk = !prof.startAngleNeedsConfirm;
    const angleSetAfter = rec && prof.startAngleSetAtMs && prof.startAngleSetAtMs - S.skewMs > rec.atMs;
    steps.push({ key: 'angle', title: 'Was the controller switched off?', wait: !m.enabled, done: !!m.enabled && angleOk,
      detail: m.enabled ? (angleOk ? 'start angle ' + IL.fmtDeg(prof.startAngleDeg, 1) + (angleSetAfter ? ', set ' + IL.fmtTime(prof.startAngleSetAtMs - S.skewMs) : ' holds')
        : 'STOP and Connect keep the screw step count; only switching the controller off loses it (I11)')
        : 'STOP keeps the screw step count; asked after Connect (I11)',
      fixes: m.enabled && !angleOk ? [{ fix: 'I11-confirm', label: 'No, it stayed on: ' + IL.fmtDeg(prof.startAngleDeg, 1) + ' still holds' },
        { href: '#calibrate/angle', label: 'Yes, it was off: measure again' }] : [] });
    steps.push({ key: 'home', title: 'Home', detail: 'M410 can lose steps: home before any move', wait: !m.enabled, done: !!m.enabled && !!m.homed,
      fixes: m.enabled && !m.homed ? [{ fix: 'home', label: 'Home', primary: true }] : [] });
    const short = !!row.defined && row.spotsLeft < row.perBoard;
    const tooShort = !!row.defined && row.spots < row.perBoard;
    if (short || (rec && rec.purgeShort)) {
      steps.push({ key: 'purge', title: short ? 'Purge row: ' + row.spotsLeft + ' left, ' + row.perBoard + ' needed' : 'Purge row reset',
        detail: tooShort ? 'the row has ' + IL.plural(row.spots, 'spot') + ', a board purges ' + row.perBoard + ': give it more spots (Setup) or purge fewer dots (Profile)'
          : short ? 'can be done now: wipe the row on the blank, then reset it (I9)' : row.spotsLeft + ' of ' + row.spots + ' spots free',
        done: !short, fixes: tooShort ? IL.fixesForCode('I9', s) : short ? [{ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }] : [] });
    }
    if (rec && rec.run && rec.run.label === 'board') {
      const pads = padsLeftOf(rec.run);
      const none = IL.noDotsReached(rec.run);
      steps.push({ key: 'decide', title: none ? 'Run the board again' : 'Decide about this board', done: false, wait: true, fixes: [{ href: '#run', label: 'Open Run' }],
        detail: none ? 'no dot reached the board: run it again from Run'
          : 'finish ' + (pads !== null ? IL.plural(pads, 'pad') : 'the ' + IL.plural(Math.max(0, rec.run.dotsPlanned - rec.run.dotsDone), 'dot') + ' left')
          + ', or wipe all and run all' });
    }
    let nowSet = false;
    steps.forEach((x, i) => {
      x.n = i + 1;
      x.state = x.state || (x.done ? 'ok' : 'num');
      if (!x.done && !x.wait && !nowSet) { x.now = true; nowSet = true; }
    });
    return steps;
  };
  /** True when a stopped board run put no dot on the board (STOP during the pad check, pump on or purge). */
  IL.noDotsReached = (run) => !!run && !(run.dotsDone > 0) && !(run.doneDots && run.doneDots.length);
  /**
   * The status still shows the run our STOP just stopped (the engine winds the task down for about a second after the
   * STOP answer): the strip and Run show it as stopped, not as dispensing. Bounded to 5 s: after that the status wins.
   */
  IL.stoppedRun = function (s) {
    const r = S.recovery;
    const run = s && s.run;
    return !!(r && r.cause === 'stop' && run && run.running && r.run && r.run.runId === run.runId && Date.now() - r.atMs < 5000);
  };
  /** Pads with a dot not dispensed by a stopped run of the plan shown (the in-progress pad counts), or null. */
  function padsLeftOf(run) {
    const p = S.plan;
    if (!p || !run || run.planId !== p.planId || !Array.isArray(run.doneDots)) return null;
    const done = new Set(run.doneDots);
    const left = new Set();
    for (const d of p.dots || []) if (!done.has(d.index)) left.add(d.padId);
    return left.size;
  }
  /** True while a recovery is open (STOP / ABORTED / ERROR until vent, connect, angle, home and purge are done). */
  IL.recoveryActive = function () {
    const r = S.recovery;
    if (!r || !S.status) return !!r;
    const steps = IL.recoverySteps(S.status, r).filter((x) => x.key !== 'decide');
    const done = steps.every((x) => x.done);
    if (done && (r.seenDown || Date.now() - r.atMs > 10000 || r.cause === 'error' || r.cause === 'aborted')) {
      S.lastStop = r;
      S.recovery = null;
      saveRecovery();
      IL.log('Back to ready after the ' + (r.cause === 'stop' ? 'STOP' : 'stop') + ' at ' + IL.fmtTime(r.atMs), 'ok');
      IL.emit('recovery', null);
      return false;
    }
    return true;
  };

  // ================================================================================== poll loop (§15)
  let pollTimer = null;
  let pollInFlight = false;
  let pollAgain = false;
  async function poll() {
    if (pollInFlight) { pollAgain = true; return; }
    pollInFlight = true;
    clearTimeout(pollTimer);
    try {
      const s = await IL.api('GET', '/api/status', undefined, { timeoutMs: L.statusTimeoutMs });
      onStatus(s);
    } catch (e) {
      // the heartbeat turns the page offline after 3 s without a good answer
    } finally {
      pollInFlight = false;
      const wait = pollAgain ? 0 : (document.hidden ? L.pollHiddenMs : L.pollMs);
      pollAgain = false;
      pollTimer = setTimeout(poll, wait);
    }
  }
  /** Polls /api/status now (e.g. right after an action) instead of waiting for the next 0.7 s tick. */
  IL.pollNow = function () { if (booted) poll(); };

  function freshnessKey(s) {
    const m = s.machine || {}, d = s.dispense || {}, p = d.profile || {}, pr = d.purgeRow || {}, n = d.needle || {};
    const surf = Object.values(d.surfaces || {}).map((x) => x.name + ':' + x.setAtMs + ':' + x.boundToBoard).join(',');
    return [m.enabled, m.homed, p.controllerConnectedAtMs, p.fingerprint, p.startAngleSetAtMs, surf, pr.usedSpots, pr.resetAtMs,
      pr.defined, n.setAtMs, n.changedAtMs, n.anchor && n.anchor.setAtMs, d.cleanPadCheck && d.cleanPadCheck.enabled,
      d.minPadTypeStatus, s.run && s.run.running].join('|');
  }
  let lastFreshKey = null;

  function onStatus(s) {
    const prev = S.status;
    const now = Date.now();
    S.prevStatus = prev;
    S.status = s;
    S.statusAt = now;
    S.lastOkAt = now;
    if (s.server && s.server.timeMs) S.skewMs = s.server.timeMs - now;
    if (S.online !== true) {
      const wasOffline = S.online === false;
      S.online = true;
      setOffline(false);
      if (wasOffline) IL.log('OpenPnP answers again', 'ok');
      IL.emit('online', true);
    }
    observe(prev, s, now);
    IL.emit('status', s, prev);
    IL.render();
  }

  function observe(prev, s, now) {
    const m = s.machine || {}, pm = (prev && prev.machine) || {};
    const d = s.dispense || {}, pr = s.pressure || {};
    const run = s.run, prun = prev && prev.run;
    // client-observed times
    if (m.enabled && !pm.enabled) S.connectedAt = now;
    if (m.homed && !pm.homed) S.homedAt = prev ? now : null;
    if (!m.homed) S.homedAt = null;
    // when this page saw the profile change (the plan chip says "stale: profile changed 11:07")
    const fp = d.profile && d.profile.fingerprint, pfp = prev && prev.dispense && prev.dispense.profile && prev.dispense.profile.fingerprint;
    if (prev && fp && pfp && fp !== pfp) S.profileChangedAt = now;
    // the gauge reading expires with any psi change (§9.4), for good: set back to the old psi, the regulator may still
    // be at the other one. A reading at the new psi stays: another tab ticked it after the change this tab sees late
    const psi = d.profile && d.profile.pressurePsi, ppsi = prev && prev.dispense && prev.dispense.profile && prev.dispense.profile.pressurePsi;
    const reg = IL.attest.get('regulator');
    if (isNum(psi) && isNum(ppsi) && Number(psi) !== Number(ppsi) && reg && Number(reg.value) !== Number(psi)) IL.attest.clear('regulator');
    // pressure on-time of a running task (client stamp of the first poll in the pressure session)
    if (run && run.running && run.pressureOnAtMs) S.runPressureSince = run.pressureOnAtMs - S.skewMs;
    else if (run && run.running && IL.PRESSURE_PHASES.has(run.phase)) { if (!S.runPressureSince) S.runPressureSince = now; }
    else if (!run || !run.running) S.runPressureSince = null;
    // first status: plan restore, recovery restore
    if (!prev) {
      S.lastEndedRunId = run && !run.running ? run.runId : null;
      restorePlan(s);
      restoreRecovery(s);
      IL.loadRuns();
    }
    // task end
    if (run && !run.running && prev && run.runId !== S.lastEndedRunId) {
      S.lastEndedRunId = run.runId;
      onTaskEnd(run);
    }
    // the machine went down while a task ran: OpenPnP's own Stop (or ours)
    if (prev && pm.enabled && !m.enabled && IL.taskRunning(prev) && !S.recovery) {
      startRecovery({ cause: 'openpnp', run: snapRun(prun), seenDown: true });
    }
    if (S.recovery) {
      if (!m.enabled) S.recovery.seenDown = true;
      const row = d.purgeRow || {};
      if (row.defined && row.spotsLeft < row.perBoard) S.recovery.purgeShort = true;
    }
    // vent_failed: band + Pressure view, until the hold reads off
    if (pr.state === 'vent_failed') {
      IL.msg('vent', { kind: 'block', text: 'Pressure is NOT off: the valve did not confirm. Press Pressure off or STOP again. If the firmware watchdog is armed, it vents by itself.',
        sub: pr.lastError || null, actions: [IL.fixButton({ fix: 'pressure-off', label: 'Pressure off', primary: true })] });
      if (!ventShown) { ventShown = true; if (!P.pushed || P.pushed.view !== 'pressure') IL.panel.show('pressure', IL.views.pressure, { sticky: true }); }
    } else if (ventShown || (prev && prev.pressure && prev.pressure.state === 'vent_failed')) {
      ventShown = false;
      IL.msg('vent', null);
    }
    // plan freshness (§9.2)
    const fk = freshnessKey(s);
    if (lastFreshKey !== null && fk !== lastFreshKey) IL.planRefreshSoon();
    lastFreshKey = fk;
    // clean-pad report of the last run (I10 refusal)
    if (s.lastRun && s.lastRun.cleanCheck) IL.adoptCleanCheck(s.lastRun.cleanCheck, s.lastRun.planId);
  }
  let ventShown = false;

  function onTaskEnd(run) {
    const word = TASK_WORD[run.label] || cap(run.label || 'task');
    const what = word + (run.label === 'board' && run.boardName ? ' ' + run.boardName : '');
    const kind = run.status === 'OK' ? 'ok' : run.status === 'REFUSED' ? 'need' : 'block';
    const st = { OK: 'finished', ABORTED: 'stopped', ERROR: 'ended by an error', REFUSED: 'refused' }[run.status] || String(run.status).toLowerCase();
    IL.log(what + ' ' + st + (run.label === 'board' || run.dotsPlanned ? ' · ' + run.dotsDone + ' of ' + run.dotsPlanned + ' dots' : '')
      + (run.message ? ': ' + run.message : ''), kind);
    if (run.status === 'ABORTED' || run.status === 'ERROR') {
      startRecovery({ cause: run.status === 'ERROR' ? 'error' : 'aborted', run: snapRun(run), atMs: Date.now(), message: run.message });
    }
    IL.planRefresh({ quiet: true });
    IL.loadRuns();
    IL.emit('taskend', run);
  }

  /**
   * Plan restore on load (§9.2): the running task's plan, else this browser's plan (localStorage il.planId) if OpenPnP
   * still keeps it, else the newest plan OpenPnP keeps (GET /api/plans: a fresh browser shows the latest plan), else the
   * last task's plan. Nothing kept at all: the stored / last plan shows as "Plan forgotten (OpenPnP restarted)".
   */
  async function restorePlan(s) {
    const run = s.run;
    const running = run && run.running && run.planId ? IL.basePlanId(run.planId) : null;
    const stored = lsGet('il.planId');
    const last = run && run.planId ? IL.basePlanId(run.planId) : null;
    if (running) { IL.loadPlan(running); return; }
    let kept = null;
    try { kept = await IL.api('GET', '/api/plans'); } catch (e) { kept = null; }   // older OpenPnP: no list
    if (S.plan || S.planId !== null) return;                                      // planned meanwhile
    const ids = Array.isArray(kept) ? kept.map((x) => x.planId) : null;
    let id = null;
    if (!ids) id = stored || last;
    else if (stored && ids.includes(stored)) id = stored;
    else if (ids.length) id = ids[0];
    else id = stored || last;                                                      // → "Plan forgotten"
    if (id) IL.loadPlan(id);
  }
  function restoreRecovery(s) {
    let saved = null;
    try { saved = JSON.parse(window.sessionStorage.getItem(REC_KEY) || 'null'); } catch (e) { saved = null; }
    const m = s.machine || {};
    const run = s.run;
    if (saved && saved.atMs && Date.now() - saved.atMs < 12 * 3600 * 1000) {
      S.recovery = saved;
    } else if (run && !run.running && (run.status === 'ABORTED' || run.status === 'ERROR') && (!m.enabled || !m.homed)) {
      S.recovery = { cause: run.status === 'ERROR' ? 'error' : 'aborted', atMs: run.finishedAtMs ? run.finishedAtMs - S.skewMs : Date.now(),
        actions: null, run: snapRun(run), message: run.message, seenDown: true, purgeShort: false };
    }
    if (S.recovery) IL.recoveryActive();
  }

  // ================================================================================== offline + heartbeat
  /** Everything but STOP is locked offline: workspaces, panel parts, strip actions and open dialogs (§15, §18). */
  const OFFLINE_INERT = '.ws, #panel-top, #panel-body, #panel-foot, #btn-start, #btn-home, .scrim, dialog.dialog';
  function setOffline(on) {
    document.documentElement.classList.toggle('is-offline', !!on);
    for (const n of document.querySelectorAll(OFFLINE_INERT)) n.inert = !!on;
    if (on) {
      IL.msg('offline', { kind: 'block', text: 'No answer from OpenPnP for 3 s: is it running? The web STOP cannot reach the machine: use OpenPnP\'s Stop button or switch the machine off.',
        sub: 'Last answer ' + IL.fmtTimeS(S.lastOkAt) + '. Everything except STOP is locked until OpenPnP answers again.' });
    } else {
      IL.msg('offline', null);
    }
  }
  let hbTicks = 0;
  function heartbeat() {
    hbTicks++;
    const age = S.lastOkAt ? Date.now() - S.lastOkAt : Date.now() - bootAt;
    const dead = age >= L.offlineMs;
    const stale = age >= L.staleMs;
    if (dead && S.online !== false && booted) {
      S.online = false;
      setOffline(true);
      IL.log('No answer from OpenPnP since ' + IL.fmtTimeS(S.lastOkAt || bootAt), 'block');
      IL.emit('online', false);
      IL.render();
    }
    const secs = age / 1000;
    const ageText = !S.lastOkAt && !dead ? '…' : secs < 10 ? secs.toFixed(1) + ' s' : secs < 90 ? Math.round(secs) + ' s' : Math.round(secs / 60) + ' min';
    const cls = dead ? 'hb--dead' : stale ? 'hb--stale' : '';
    const hb = $('heartbeat'), hs = $('heartbeat-short');
    if (hb) {
      IL.setClass(hb, 'brand__live' + (cls ? ' ' + cls : ''));
      IL.setText(hb.querySelector('.hb__who'), dead ? 'No answer' : 'OpenPnP');
      IL.setText(hb.querySelector('.hb__age'), ageText);
      IL.setText(hb.querySelector('.hb__ago'), dead ? '' : ' ago');
      IL.setAttr(hb, 'title', S.lastOkAt ? 'Last answer from OpenPnP ' + IL.fmtTimeS(S.lastOkAt) : 'Waiting for the first answer');
    }
    if (hs) {
      IL.setClass(hs, 'brand__short' + (cls ? ' ' + cls : ''));
      IL.setText(hs.querySelector('.hb__age'), ageText);
    }
    if (hbTicks % 4 === 0) { IL.emit('tick'); IL.render(); }
  }

  // ================================================================================== render scheduling
  let renderQueued = false;
  /** Schedules one render of the frame, the visible workspace and the panel (coalesced per frame). */
  IL.render = function () {
    if (renderQueued || !booted) return;
    renderQueued = true;
    if (document.hidden) setTimeout(renderAll, 0); else requestAnimationFrame(renderAll);
  };
  function renderAll() {
    renderQueued = false;
    safe(renderStrip, 'strip');
    safe(renderNav, 'nav');
    safe(syncAutoPanels, 'auto panels');
    safe(renderStopCopy, 'stop copy');
    safe(renderTitle, 'title');
    const w = WS[S.route.ws];
    if (w && w.mounted && w.render && S.status) safe(() => w.render(S.status), 'workspace ' + w.name);
    safe(renderPanel, 'panel');
    safe(gateFixButtons, 'fix gates');
  }

  // ================================================================================== strip (§4)
  /**
   * Patches a strip cell: kind (glyph + tint), value = text or [main, long, level] (the long part is dropped when the
   * strip is too narrow: level 1 first (.cells--t1), then level 2 (.cells--t2)), title = the long form.
   */
  function setCell(cell, kind, value, title) {
    if (!cell) return;
    IL.setClass(cell, 'cell cell--' + kind + (cell.id === 'pill-engine' ? ' cell--verdict' : ''));
    IL.setGlyph(cell.querySelector('.st'), kind, 'sm');
    if (value !== undefined) {
      const v = cell.querySelector('.cell__v');
      const [main, long, level] = Array.isArray(value) ? value : [value, '', 1];
      const sig = main + '\u0001' + (long || '') + (level || 1);
      if (v.dataset.v !== sig) {
        v.dataset.v = sig;
        v.replaceChildren(main, long ? el('span', { class: 'long', 'data-l': level || 1, text: long }) : '');
      }
    }
    IL.setAttr(cell, 'title', title || null);
  }
  /** Drops the long parts of the cell values (and tightens spacing) only when the strip would run under STOP. */
  let fitSig = '';
  function fitStrip() {
    const cells = $('cells');
    if (!cells) return;
    const sig = window.innerWidth + '|' + $('btn-start').hidden + $('btn-home').hidden + '|' + cells.textContent.replace(/\d/g, '0');
    if (sig === fitSig) return;
    fitSig = sig;
    const over = () => {
      const vis = Array.from(cells.children).filter((c) => !c.hidden);
      const last = vis[vis.length - 1];
      if (!last) return false;
      const limit = cells.getBoundingClientRect().right - parseFloat(getComputedStyle(cells).paddingRight || '0');
      return last.getBoundingClientRect().right > limit + 0.5;
    };
    cells.classList.remove('cells--t1', 'cells--t2');
    if (!over()) return;
    cells.classList.add('cells--t1');
    if (!over()) return;
    cells.classList.replace('cells--t1', 'cells--t2');
  }
  window.addEventListener('resize', () => { fitSig = ''; IL.render(); });
  function pressureView(s) {
    const pr = s.pressure || {}, d = s.dispense || {}, run = s.run;
    const lim = isNum(d.maxContinuousOnTimeS) ? IL.fmtClock(d.maxContinuousOnTimeS) : null;
    const onText = (secs) => ['ON ' + IL.fmtClock(secs), lim ? ' · auto-off ' + lim : '', 2];
    if (pr.state !== 'vent_failed' && IL.stoppedRun(s) && IL.PRESSURE_PHASES.has(run.phase)) {
      return { kind: 'live', v: 'Switching off…', t: 'STOP closed the valve (the barrel vents) and switched the pump off: waiting for OpenPnP to confirm.' };
    }
    switch (pr.state) {
      case 'vent_failed': return { kind: 'block', v: 'NOT off', t: 'Pressure is NOT off: the valve did not confirm. ' + (pr.lastError || '') };
      case 'starting': return { kind: 'live', v: 'Switching on…', t: 'Pump on, then the valve (pressure hold)' };
      case 'stopping': return { kind: 'live', v: 'Switching off…', t: 'Valve off (the barrel vents), then the pump' };
      case 'on': {
        const secs = pr.onSinceMs ? (IL.serverNow() - pr.onSinceMs) / 1000 : 0;
        return { kind: 'live', v: onText(secs), t: 'Pressure held by hand. Switches off by itself after ' + (lim || '?') + ' on (I7)'
          + (isNum(pr.remainingS) ? ', in ' + IL.fmtClock(pr.remainingS - ((Date.now() - S.statusAt) / 1000)) : '') + '.' };
      }
      default: break;
    }
    if (run && run.running && IL.PRESSURE_PHASES.has(run.phase) && S.runPressureSince) {
      return { kind: 'live', v: onText((Date.now() - S.runPressureSince) / 1000), t: 'Held by the running ' + (run.label || 'task') + '. Switches off by itself after ' + (lim || '?') + ' on (I7).' };
    }
    return { kind: 'off', v: ['Off', ' · vented'], t: pr.engineRunActive ? 'The running task switches the pressure on and off by itself.' : 'Valve closed, barrel vented. Set the regulator by hand.' };
  }
  function renderStrip() {
    const s = S.status;
    const off = S.online === false;
    const since = 'unknown since ' + IL.fmtTimeS(S.lastOkAt);
    const mc = $('cell-machine'), conn = $('pill-conn'), homed = $('pill-homed');
    if (!mc) return;
    const sep = mc.querySelector('.cell__sep');
    let showStart = false, showHome = false;
    const homedVisible = (on) => {
      IL.setClass(homed, on ? '' : 'sr');
      IL.setClass(conn, on ? 'long' : '');
      if (sep) IL.show(sep, on);
    };
    if (!s || off) {
      setCell(mc, 'off', undefined, off ? 'No answer from OpenPnP since ' + IL.fmtTimeS(S.lastOkAt) : 'Waiting for OpenPnP');
      IL.setText(conn, off ? cap(since) : 'Connecting…');
      IL.setText(homed, off ? 'Unknown' : '');
      homedVisible(false);
    } else {
      const m = s.machine || {};
      if (!m.present) {
        setCell(mc, 'block', undefined, 'No machine is configured in OpenPnP');
        IL.setText(conn, 'No machine');
        IL.setText(homed, 'Not homed');
        homedVisible(false);
      } else if (!m.enabled) {
        setCell(mc, 'block', undefined, 'OpenPnP is stopped: connect, then home');
        IL.setText(conn, 'Not connected');
        IL.setText(homed, 'Not homed');
        homedVisible(false);
        showStart = true;
      } else if (!m.homed) {
        IL.setText(conn, 'Connected');
        homedVisible(true);
        if (m.busy) {
          setCell(mc, 'live', undefined, 'Homing: every axis goes to its home switch');
          IL.setText(homed, 'Homing…');
        } else {
          setCell(mc, 'need', undefined, 'Connected, not homed: positions are unknown. Home before any move.');
          IL.setText(homed, 'Not homed');
          showHome = !IL.taskRunning(s) && (!s.pressure || s.pressure.state === 'off');
        }
      } else {
        const p = (s.dispense && s.dispense.profile) || {};
        setCell(mc, 'ok', undefined, 'Connected' + (p.controllerConnectedAtMs ? ' ' + IL.fmtTime(p.controllerConnectedAtMs - S.skewMs) : '')
          + ' · homed' + (S.homedAt ? ' ' + IL.fmtTime(S.homedAt) : ''));
        IL.setText(conn, 'Connected');
        IL.setText(homed, 'Homed');
        homedVisible(true);
      }
    }
    const bs = $('btn-start'), bh = $('btn-home');
    IL.show(bs, showStart);
    if (!showHome && armed && armed.btn === bh) disarm();
    IL.show(bh, showHome);

    // pressure, start angle, purge row
    const pc = $('pill-pressure'), ac = $('chip-angle'), uc = $('chip-purge');
    const d = (s && s.dispense) || {};
    const prof = d.profile || {};
    IL.setText(pc.querySelector('.cell__k'), isNum(prof.pressurePsi) ? 'Pressure · set ' + IL.fmtPsi(prof.pressurePsi) + ' psi' : 'Pressure');
    if (!s || off) {
      const t = off ? cap(since) : null;
      setCell(pc, 'off', off ? 'Unknown' : '…', t);
      setCell(ac, 'off', off ? 'Unknown' : '…', t);
      setCell(uc, 'off', off ? 'Unknown' : '…', t);
    } else {
      const pv = pressureView(s);
      setCell(pc, pv.kind, pv.v, pv.t + (isNum(prof.pressurePsi) ? ' Set the regulator to ' + IL.fmtPsi(prof.pressurePsi) + ' psi by hand (profile ' + prof.name + ').' : ''));
      const i11 = IL.interlock(s, 'I11');
      if ((i11 && i11.state === 'off') || prof.partialTurn === false) setCell(ac, 'off', 'Whole turns', 'Whole turns per dot: the start angle does not matter (I11 off)');
      else if (!prof.startAngleSetAtMs) setCell(ac, 'block', 'Not set', 'The screw start angle was never set: measure it (Calibrate, I11)');
      else if (prof.startAngleNeedsConfirm) setCell(ac, 'need', 'Confirm', 'The controller reconnected after the angle was set: was it switched off? (I11)');
      else if (S.recovery && !(s.machine && s.machine.enabled)) setCell(ac, 'need', 'Confirm', 'After STOP: asked after Connect: was the controller switched off? (I11)');
      else setCell(ac, 'ok', IL.fmtDeg(prof.startAngleDeg, 1), 'Screw start angle ' + IL.fmtDeg(prof.startAngleDeg, 1) + ', confirmed ' + IL.fmtTime(prof.startAngleSetAtMs - S.skewMs) + ' for this controller power-up (I11)');
      const row = d.purgeRow || {};
      if (!row.defined) setCell(uc, 'block', 'Not set', 'The purge row is not set (Setup, I9)');
      else if (row.spots < row.perBoard) setCell(uc, 'block', [row.spotsLeft + ' of ' + row.spots, ' · too short'], 'The row has ' + row.spots + ' spots; a board purges ' + row.perBoard + ': give it more spots (Setup) or purge fewer dots (Profile) (I9)');
      else if (row.spotsLeft < row.perBoard) setCell(uc, 'need', [row.spotsLeft + ' of ' + row.spots, ' · wipe'], row.spotsLeft + ' spots left, ' + row.perBoard + ' needed per board: wipe the row and reset it (I9)');
      else setCell(uc, 'ok', row.spotsLeft + ' of ' + row.spots, row.spotsLeft + ' spots free, ' + row.perBoard + ' per board (I9)');
    }
    const v = IL.verdict();
    const ve = $('pill-engine');
    setCell(ve, v.state, v.value || v.text, v.title);
    IL.show(ve.querySelector('.chev'), v.state !== 'live' && v.state !== 'off');
    IL.show($('sim-tag'), !!s && IL.isSim(s));
    fitStrip();
  }

  // ================================================================================== nav (§6)
  function defaultNav(name, s) {
    if (!s) return {};
    const running = IL.taskRunning(s);
    const d = s.dispense || {};
    if (name === 'run') {
      const run = s.run;
      if (S.online === false) return { sub: 'No answer', kind: 'block' };
      if (run && run.running) {
        if (run.label === 'board') return { sub: run.dotsDone + ' of ' + run.dotsPlanned, kind: 'live', badge: String(run.dotsDone), badgeKind: 'live', badgeLabel: run.dotsDone + ' dots done' };
        return { sub: (TASK_WORD[run.label] || cap(run.label)) + ' running', kind: 'live' };
      }
      if (IL.recoveryActive()) {
        const r = S.recovery;
        // short forms: the subline never wraps (§15 fixed layout)
        const at = r.run && r.run.label === 'board' && isNum(r.run.dotsDone)
          ? (IL.noDotsReached(r.run) ? 'Stopped · no dots' : 'Stopped · dot ' + r.run.dotsDone) : 'Stopped ' + IL.fmtTime(r.atMs);
        return { sub: at, kind: 'block', badge: '!', badgeKind: 'block', badgeLabel: 'Stopped', title: IL.recoveryHeadline(r) };
      }
      const r = IL.ready();
      const needs = r.needs.length;
      if (!S.plan) return { sub: S.planForgotten ? 'Plan again' : 'No plan yet', kind: S.planForgotten ? 'need' : '' };
      if (r.verdict.state === 'ok') return { sub: 'Ready to run', kind: 'ok', title: 'Ready: ' + (S.plan.boardName || 'board') };
      const kind = r.verdict.state === 'block' ? 'block' : 'need';
      return { sub: cap(r.next ? r.next.short : ''), kind, badge: String(needs), badgeKind: kind, badgeLabel: needs + ' need you' };
    }
    if (name === 'setup' || name === 'calibrate') return running ? { sub: 'Locked · running' } : {};
    if (name === 'profile') {
      const p = d.profile || {};
      if (p.startAngleNeedsConfirm) return { sub: 'Confirm angle', kind: 'need' };
      return { sub: p.name || '' };
    }
    return {};
  }
  function renderNav() {
    const s = S.status;
    for (const name of IL.WORKSPACES) {
      const a = $('tab-btn-' + name);
      if (!a) continue;
      let v = defaultNav(name, s);
      const w = WS[name];
      if (w && w.nav && s) { const o = safe(() => w.nav(s), 'nav ' + name); if (o) v = Object.assign({}, v, o); }
      const sub = a.querySelector('.nav__s'), badge = a.querySelector('.nav__badge');
      IL.setText(sub, v.sub || '');
      IL.setAttr(sub, 'title', v.sub || null);
      IL.setClass(sub, 'nav__s' + (v.kind ? ' nav__s--' + v.kind : ''));
      const hasBadge = v.badge !== undefined && v.badge !== null && v.badge !== '' && v.badge !== '0';
      if (hasBadge) {
        IL.setText(badge, v.badge);
        IL.setClass(badge, 'nav__badge' + (v.badgeKind && v.badgeKind !== 'need' ? ' nav__badge--' + v.badgeKind : ''));
        IL.setAttr(badge, 'aria-label', v.badgeLabel || null);
      }
      IL.show(badge, hasBadge);
      a.classList.toggle('nav__item--dirty', !!v.dirty);
      IL.setAttr(a, 'title', v.title || (v.dirty ? 'Unsaved changes' : null));
    }
  }

  // ================================================================================== title + favicon (§15)
  const FAV_BASE = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="6" fill="#1f2937"/>'
    + '<path d="M13 4h6v10l-3 6-3-6z" fill="#9ca3af"/><circle cx="16" cy="25" r="4" fill="#d1d5db"/>';
  const FAV = {
    idle: '/static/favicon.svg',
    live: 'data:image/svg+xml,' + encodeURIComponent(FAV_BASE + '<circle cx="25" cy="7" r="6.5" fill="#e07a1f" stroke="#fff" stroke-width="1.5"/></svg>'),
    stop: 'data:image/svg+xml,' + encodeURIComponent(FAV_BASE + '<rect x="18" y="0.5" width="13.5" height="13.5" rx="3" fill="#c62828" stroke="#fff" stroke-width="1.5"/></svg>'),
  };
  let favNow = 'idle';
  function renderTitle() {
    const s = S.status;
    let t = null, icon = 'idle';
    if (S.online === false) { t = '✕ No answer'; icon = 'stop'; }
    else if (s && s.run && s.run.running) { t = '▶ ' + IL.taskLabel(s.run); icon = 'live'; }
    else if (s && IL.recoveryActive()) { t = '■ Stopped'; icon = 'stop'; }
    else if (s) {
      const r = IL.ready();
      if (r.verdict.state === 'ok') t = 'Ready';
      else if (r.verdict.state === 'block') t = '✕ ' + r.verdict.text;
      else t = '! ' + (S.plan ? r.count + ' of 9 ready' : r.verdict.text);
      if (IL.energised(s)) icon = 'live';
    }
    const title = (t ? t + ' · ' : '') + 'Paste dispenser';
    if (document.title !== title) document.title = title;
    if (icon !== favNow) {
      favNow = icon;
      const f = $('favicon');
      if (f) f.setAttribute('href', FAV[icon]);
    }
  }

  // ================================================================================== workspaces + panel host (§7)
  const WS = {};
  IL.workspaces = WS;
  IL.views = {};
  let booted = false;
  let bootAt = Date.now();
  /**
   * Registers a workspace: spec {mount(root), render(status), show(route), hide(), panel(parts, status, first),
   * route(route), nav(status) → {sub, kind, badge, badgeKind, dirty}, defaultView, ownsRecovery}.
   * The section [data-workspace=name] is the root; mount() runs once at boot (or at once if registered later).
   */
  IL.workspace = function (name, spec) {
    if (!IL.WORKSPACES.includes(name)) console.warn('[IL] unknown workspace ' + name);
    const w = Object.assign({}, spec || {}, { name, mounted: false, root: null, scrollTop: 0 });
    WS[name] = w;
    if (booted) {
      mountWs(w);
      if (S.route.ws === name) { safe(() => w.show && w.show(S.route), 'show ' + name); P.first = true; IL.render(); }
    }
    return w;
  };
  function mountWs(w) {
    w.root = document.querySelector('[data-workspace="' + w.name + '"]');
    if (w.root && w.mount) safe(() => w.mount(w.root), 'mount ' + w.name);
    w.mounted = true;
  }
  /** Registers a pushed panel view (?panel=name): spec {title (string | fn(status)), render(parts, status, first), unmount()}. */
  IL.view = function (name, spec) { IL.views[name] = spec; return spec; };
  IL.active = () => S.route.ws;

  const P = { pushed: null, first: true };
  const partsOf = () => ({ root: $('panel'), top: $('panel-top'), body: $('panel-body'), foot: $('panel-foot'), scroll: $('panel-scroll') });
  function clearParts() {
    const p = partsOf();
    for (const n of [p.top, p.body, p.foot]) { n.replaceChildren(); delete n.dataset.key; n.className = n.className.split(' ')[0]; }
    p.scroll.scrollTop = 0;
  }
  function placeholderView(name) {
    return { title: cap(name), render(p, s, first) { if (first) p.body.append(el('p', { class: 'panel__lead', text: 'This view is not built yet.' })); } };
  }
  IL.panel = {
    /** {root, top (pinned head, e.g. the Run stepper), body (scrolls, under the STOP copy), foot (pinned), scroll}. */
    get parts() { return partsOf(); },
    /** Pushes a view with a back arrow. spec: fn(parts, status, first) or {title, render, unmount}. opts {title, routed, sticky}. */
    show(view, spec, opts) {
      const o = opts || {};
      let sp = typeof spec === 'function' ? { render: spec } : (spec || IL.views[view] || placeholderView(view));
      if (o.title) sp = Object.assign({}, sp, { title: o.title });
      if (P.pushed && P.pushed.spec.unmount) safe(() => P.pushed.spec.unmount(), 'unmount ' + P.pushed.view);
      P.pushed = { view, spec: sp, routed: !!o.routed, sticky: !!o.sticky };
      P.first = true;
      IL.setAttr($('panel'), 'data-view', view);
      if (!o.routed && S.route.panel && S.route.panel !== view) {
        S.route = Object.assign({}, S.route, { panel: null });
        history.replaceState(null, '', IL.hashFor(S.route));
      }
      IL.render();
    },
    /** Back to the workspace's default view (removes ?panel= from the hash). */
    close() {
      if (!P.pushed) return;
      if (P.pushed.view === 'recovery') recoveryDismissedWs = S.route.ws;
      if (P.pushed.spec.unmount) safe(() => P.pushed.spec.unmount(), 'unmount ' + P.pushed.view);
      P.pushed = null;
      P.first = true;
      if (S.route.panel) {
        S.route = Object.assign({}, S.route, { panel: null });
        history.replaceState(null, '', IL.hashFor(S.route));
      }
      IL.render();
    },
    /** Clears the panel and calls the current view / default panel with first = true on the next render. */
    reset() { P.first = true; IL.render(); },
    /** Sets #panel[data-view] (a default panel that shows different steps, e.g. Run: board/probe/checks/run/result). */
    setView(name) { IL.setAttr($('panel'), 'data-view', name || ''); },
    current() { return { view: $('panel') ? $('panel').dataset.view : '', pushed: !!P.pushed }; },
    isPushed(view) { return !!P.pushed && (!view || P.pushed.view === view); },
  };
  function renderPanel() {
    const p = partsOf();
    if (!p.root) return;
    const s = S.status;
    if (P.pushed) {
      const sp = P.pushed.spec;
      IL.show($('panel-bar'), true);
      IL.setText($('panel-bar-title'), typeof sp.title === 'function' ? (s ? sp.title(s) : '') : (sp.title || cap(P.pushed.view)));
      if (s) {
        if (P.first) { clearParts(); P.first = false; IL.setAttr(p.root, 'data-view', P.pushed.view); safe(() => sp.render(p, s, true), 'view ' + P.pushed.view); }
        else safe(() => sp.render(p, s, false), 'view ' + P.pushed.view);
      }
    } else {
      IL.show($('panel-bar'), false);
      const w = WS[S.route.ws];
      if (P.first) {
        clearParts();
        IL.setAttr(p.root, 'data-view', (w && w.defaultView) || S.route.ws);
        if (w && w.panel && s) { P.first = false; safe(() => w.panel(p, s, true), 'panel ' + S.route.ws); }
        else if (!w || !w.panel) P.first = false;
      } else if (w && w.panel && s) {
        safe(() => w.panel(p, s, false), 'panel ' + S.route.ws);
      }
    }
    IL.show(p.top, !!p.top.firstChild);
    IL.show(p.foot, !!p.foot.firstChild);
  }
  function renderStopCopy() {
    IL.show($('panel-stop'), IL.energised(S.status));
  }
  let recoveryDismissedWs = null;
  function syncAutoPanels() {
    const active = IL.recoveryActive();
    const w = WS[S.route.ws];
    const owns = w && w.ownsRecovery;
    if (active && !owns && !P.pushed && !S.route.panel && recoveryDismissedWs !== S.route.ws) {
      IL.panel.show('recovery', IL.views.recovery, { sticky: true });
    } else if (!active && P.pushed && P.pushed.view === 'recovery') {
      if (P.pushed.spec.unmount) safe(() => P.pushed.spec.unmount());
      P.pushed = null;
      P.first = true;
      if (S.route.panel === 'recovery') { S.route = Object.assign({}, S.route, { panel: null }); history.replaceState(null, '', IL.hashFor(S.route)); }
    }
  }

  // ================================================================================== router (§3)
  const REDIRECT = { dispense: 'setup', profiles: 'profile' };
  /** "#setup/purge?panel=pressure" → {ws, section, id, panel, params, redirected}. Legacy #dispense, #profiles redirect. */
  IL.parseHash = function (hash) {
    let h = String(hash || '').replace(/^#/, '');
    let query = '';
    const qi = h.indexOf('?');
    if (qi >= 0) { query = h.slice(qi + 1); h = h.slice(0, qi); }
    let parts = h.split('/').filter(Boolean);
    try { parts = parts.map(decodeURIComponent); } catch (e) { parts = []; }
    let ws = parts[0] || 'run';
    let redirected = !parts[0];
    if (REDIRECT[ws]) { ws = REDIRECT[ws]; redirected = true; }
    if (!IL.WORKSPACES.includes(ws)) { ws = 'run'; parts = []; redirected = true; }
    const params = new URLSearchParams(query);
    return { ws, section: parts[1] || null, id: parts.length > 2 ? parts.slice(2).join('/') : null, panel: params.get('panel') || null, params, redirected };
  };
  IL.hashFor = function (r) {
    const path = [r.ws || 'run', r.section, r.id].filter((x) => x !== null && x !== undefined && x !== '').map((x) => encodeURIComponent(x)).join('/');
    return '#' + path + (r.panel ? '?panel=' + encodeURIComponent(r.panel) : '');
  };
  /**
   * Navigates: IL.go('#setup/purge'), IL.go({section: 'checks'}) (same workspace), opts.replace. Workspace changes push
   * a history entry; in-page changes replace it.
   */
  IL.go = function (target, opts) {
    const o = opts || {};
    const r = typeof target === 'string' ? IL.parseHash(target) : Object.assign({ ws: S.route.ws }, target);
    const hash = IL.hashFor(r);
    if (hash !== location.hash) {
      const replace = o.replace !== undefined ? o.replace : r.ws === S.route.ws;
      history[replace ? 'replaceState' : 'pushState'](null, '', hash);
    }
    applyRoute();
  };
  /** Opens a pushed panel view in the current workspace (?panel=name); toggles it off when it is already open. */
  IL.openView = function (name, opts) {
    if (P.pushed && P.pushed.view === name && !(opts && opts.keep)) { IL.panel.close(); return; }
    IL.go(Object.assign({}, S.route, { panel: name }), { replace: true });
  };
  function applyRoute() {
    const r = IL.parseHash(location.hash);
    if (r.redirected) history.replaceState(null, '', IL.hashFor(r));
    const prev = S.route;
    S.route = r;
    if (!booted) return;
    if (prev.ws !== r.ws || !WS[r.ws] || !WS[r.ws].shown) switchWorkspace(prev.ws, r.ws);
    if (r.panel) {
      if (!P.pushed || P.pushed.view !== r.panel) IL.panel.show(r.panel, IL.views[r.panel] || placeholderView(r.panel), { routed: true });
    } else if (P.pushed && P.pushed.routed) {
      if (P.pushed.spec.unmount) safe(() => P.pushed.spec.unmount());
      P.pushed = null;
      P.first = true;
    }
    const w = WS[r.ws];
    // a detail chosen in this workspace (#records/boards/<id>, #profile/pad-types/<key>) shows in its default panel: a
    // pushed Recovery yields to it here (like its back arrow); the strip still says Stopped, other workspaces show it again
    if (r.id && P.pushed && P.pushed.view === 'recovery' && !(w && w.ownsRecovery) && prev.ws === r.ws && prev.id !== r.id) IL.panel.close();
    if (w && w.route) safe(() => w.route(r), 'route ' + r.ws);
    IL.emit('route', r, prev);
    IL.render();
  }
  function switchWorkspace(from, to) {
    const main = $('main');
    const pw = WS[from];
    if (pw && pw.shown) {
      pw.scrollTop = main.scrollTop;
      pw.shown = false;
      if (pw.hide) safe(() => pw.hide(), 'hide ' + from);
    }
    for (const sec of document.querySelectorAll('[data-workspace]')) sec.hidden = sec.dataset.workspace !== to;
    for (const a of document.querySelectorAll('.nav [role=tab]')) a.setAttribute('aria-selected', String(a.dataset.tab === to));
    if (P.pushed && !P.pushed.sticky) { if (P.pushed.spec.unmount) safe(() => P.pushed.spec.unmount()); P.pushed = null; }
    if (P.pushed && P.pushed.view === 'recovery' && WS[to] && WS[to].ownsRecovery) P.pushed = null;
    recoveryDismissedWs = null;
    P.first = true;
    const w = WS[to];
    if (w) {
      w.shown = true;
      if (w.show) safe(() => w.show(S.route), 'show ' + to);
      main.scrollTop = w.scrollTop || 0;
    }
  }

  // ================================================================================== STOP (§5)
  const STOP = { retry: null, unconfirmed: false, tries: 0, lastConfirmAt: 0, bandTimer: null, bandRo: null, sending: 0 };
  /** Presses STOP: POST /api/stop now (2 s timeout); retries every 1 s with the red band until one answer arrives. */
  IL.stop = function (source) {
    STOP.pressedAt = Date.now();
    setStopping(true);
    IL.log('STOP pressed (' + (source || 'button') + ')', 'block');
    STOP.source = source || 'button';
    sendStop();
  };
  function sendStop() {
    STOP.tries++;
    STOP.sending++;
    const t0 = Date.now();
    IL.api('POST', '/api/stop', undefined, { timeoutMs: L.stopTimeoutMs })
      .then((r) => stopConfirmed(r, t0))
      .catch((e) => stopFailed(e, t0))
      .finally(() => { STOP.sending--; });
  }
  function stopFailed(e, t0) {
    if (STOP.lastConfirmAt >= t0) return;          // a newer press was already confirmed
    if (!STOP.unconfirmed) {
      STOP.unconfirmed = true;
      IL.log('STOP NOT CONFIRMED: ' + (e && e.message), 'block');
    }
    showStopBand('fail');
    if (!STOP.retry) {
      sendStop();                                  // again at once, then every second until one answer arrives
      STOP.retry = setInterval(sendStop, L.stopRetryMs);
    }
  }
  function stopConfirmed(r, t0) {
    // a request sent before the last confirmation (a retry or a double press) answering late: already confirmed
    if (t0 !== undefined && t0 < STOP.lastConfirmAt) return;
    clearInterval(STOP.retry);
    STOP.retry = null;
    const tries = STOP.tries;
    const wasUnconfirmed = STOP.unconfirmed;
    STOP.tries = 0;
    STOP.unconfirmed = false;
    STOP.lastConfirmAt = Date.now();
    STOP.pressedAt = 0;
    setStopping(false);
    const actions = (r && r.actions) || [];
    const at = Date.now();
    IL.toast('STOPPED ' + IL.fmtTimeS(at) + ': ' + actions.join('; '), 'block');
    if (wasUnconfirmed) showStopBand('ok', tries); else showStopBand(null);
    const s = S.status;
    // seenDown comes only from a status that shows the machine down (the answer may arrive before that poll)
    startRecovery({ cause: 'stop', atMs: at, actions, source: STOP.source, run: s && s.run && s.run.running ? snapRun(s.run) : null });
    IL.emit('stop', r);
    IL.pollNow();
  }
  function setStopping(on) {
    for (const b of document.querySelectorAll('#btn-stop, [data-stop-copy]')) {
      b.classList.toggle('stop--sending', !!on);
      IL.setText(b.querySelector('.stop__word'), on ? 'STOPPING…' : 'STOP');
    }
    const strip = $('strip');
    if (strip) strip.classList.toggle('strip--stopping', !!on);
  }
  function showStopBand(kind, tries) {
    const b = $('stop-unconfirmed');
    if (!b) return;
    // at <= 1100 px both bands stick under the strip: --stop-band-h (0 while hidden) puts the page bands (offline,
    // vent_failed) below the STOP band instead of under it
    if (!STOP.bandRo && window.ResizeObserver) {
      STOP.bandRo = new ResizeObserver(() => document.documentElement.style.setProperty('--stop-band-h', b.offsetHeight + 'px'));
      STOP.bandRo.observe(b);
    }
    clearTimeout(STOP.bandTimer);
    if (!kind) { b.hidden = true; return; }
    if (kind === 'fail') {
      b.className = 'stop-band';
      b.replaceChildren(IL.glyph('block', 'sm'), el('span', { text: "STOP NOT CONFIRMED: press OpenPnP's Stop button or switch the machine off at its power switch now. Retrying every second." }));
    } else {
      b.className = 'stop-band stop-band--ok';
      b.replaceChildren(IL.glyph('ok', 'sm'), el('span', { text: 'STOP confirmed ' + IL.fmtTimeS(Date.now()) + ' after ' + IL.plural(tries, 'try', 'tries') }));
      STOP.bandTimer = setTimeout(() => { b.hidden = true; }, L.stopConfirmedShowMs);
    }
    b.hidden = false;
  }

  // ================================================================================== two-press (§11)
  let armed = null; // {btn, verb, action, opts, at, saved, timer}
  /**
   * Makes `btn` a two-press button: the first press arms it (striped, "Press again: <verb>", 4 s bar), a second press
   * ≥ 300 ms later runs action through IL.act. Esc, blur, a click elsewhere or 4 s cancel. verb may be a function
   * (status) → verb | null (null = single press now, e.g. Pressure on over the waste). opts: as IL.act.
   */
  IL.press2 = function (btn, verb, action, opts) {
    btn.dataset.twoPress = typeof verb === 'function' ? 'dynamic' : verb;
    btn.addEventListener('click', (ev) => press2Click(btn, verb, action, opts, ev));
    return btn;
  };
  IL.isArmed = (btn) => !!armed && (!btn || armed.btn === btn);
  IL.disarm = () => disarm();
  function press2Click(btn, verb, action, opts, ev) {
    if (btn.disabled) return;
    const v = typeof verb === 'function' ? verb(S.status) : verb;
    if (!v) { IL.act(btn, action, opts); return; }
    if (armed && armed.btn === btn) {
      if (performance.now() - armed.at < L.press2MinMs) return;   // double click: not a deliberate second press
      disarm();
      IL.act(btn, action, opts);
      return;
    }
    arm(btn, v);
    if (ev) ev.stopPropagation();
  }
  function arm(btn, verb) {
    disarm();
    const saved = Array.from(btn.childNodes);
    const icon = saved.find((n) => n.nodeType === 1 && n.tagName.toLowerCase() === 'svg');
    btn.replaceChildren();
    if (icon) btn.appendChild(icon.cloneNode(true));
    btn.appendChild(document.createTextNode('Press again: ' + verb));
    btn.classList.add('btn--press');
    btn.style.setProperty('--press-ms', L.press2Ms + 'ms');
    btn.setAttribute('aria-live', 'polite');
    armed = { btn, verb, at: performance.now(), saved, timer: setTimeout(disarm, L.press2Ms) };
    armed.blur = () => setTimeout(() => { if (armed && armed.btn === btn && document.activeElement !== btn) disarm(); }, 0);
    btn.addEventListener('blur', armed.blur);
    armed.escOff = IL.onEsc(() => { disarm(); return true; });
  }
  function disarm() {
    if (!armed) return;
    const a = armed;
    armed = null;
    clearTimeout(a.timer);
    a.btn.removeEventListener('blur', a.blur);
    if (a.escOff) a.escOff();
    a.btn.classList.remove('btn--press');
    a.btn.removeAttribute('aria-live');
    a.btn.replaceChildren(...a.saved);
  }
  // a press anywhere else cancels a pending two-press (STOP still stops: the listener never blocks)
  document.addEventListener('pointerdown', (e) => { if (armed && !armed.btn.contains(e.target)) disarm(); }, true);
  // auto-repeat never counts as a press (Enter / Space held on a two-press button)
  document.addEventListener('keydown', (e) => {
    if (e.repeat && (e.key === 'Enter' || e.key === ' ') && e.target.closest && e.target.closest('[data-two-press]')) e.preventDefault();
  }, true);

  // ================================================================================== actions, refusals, fixes
  /**
   * Runs an action from a button: disables it meanwhile, shows a refusal inline (in the nearest [data-refusals]
   * ancestor or opts.refusalIn) + toast + activity, then polls. opts: {ok: text | fn(result), refusalIn, what}.
   */
  IL.act = async function (btn, fn, opts) {
    const o = opts || {};
    const host = o.refusalIn || (btn && btn.closest && btn.closest('[data-refusals]')) || null;
    if (host) for (const n of host.querySelectorAll(':scope > .refusal')) n.remove();
    if (btn) { btn.disabled = true; btn.setAttribute('aria-busy', 'true'); }
    try {
      const r = await fn();
      if (o.ok) IL.toast(typeof o.ok === 'function' ? o.ok(r) : o.ok, 'ok');
      return r;
    } catch (e) {
      IL.refuse(e, { host, what: o.what });
      return undefined;
    } finally {
      if (btn) { btn.disabled = false; btn.removeAttribute('aria-busy'); }
      IL.pollNow();
      IL.render();
    }
  };
  /** Shows an error: toast + activity, and the inline refusal box (with fix-its per I-code) in opts.host. */
  IL.refuse = function (e, opts) {
    const o = opts || {};
    const unknown = e && e.status === 0;
    const head = unknown ? 'Outcome unknown' : e && e.status === 409 ? 'Refused' : e && e.status === 400 ? 'Not accepted' : 'Failed';
    // "Outcome unknown" once: the head says it, the line says what happened and what to do
    const line = unknown ? e.message + ' Look at the machine.' : IL.errorText(e);
    IL.toast(head + ': ' + line, unknown ? 'need' : 'block');
    if (!o.host) return null;
    const viol = (e && e.data && e.data.interlocks && e.data.interlocks.violations) || [];
    const box = el('div', { class: 'refusal' + (unknown ? ' refusal--unknown' : ''), role: 'alert' },
      el('div', { class: 'refusal__t', text: head + (o.what ? ' · ' + o.what : '') + ': '
        + (viol.length ? (e.data.reason || 'refused by the interlocks').split(':')[0] : line) }));
    const lines = viol.length ? viol : codeFromMessage(e && e.message);
    if (lines.length) {
      box.appendChild(el('ul', {}, lines.map((v) => el('li', {},
        el('abbr', { class: 'icode', title: 'Interlock ' + v.code, text: v.code }), el('span', { text: v.message }),
        IL.fixesForCode(v.code, S.status, v.message).map((f) => IL.fixButton(f, { cls: 'btn--sm' }))))));
    }
    o.host.appendChild(box);
    return box;
  };
  function codeFromMessage(msg) {
    const m = /^(I\d+|MACHINE|CONFIG|PLAN):\s*(.*)$/s.exec(String(msg || ''));
    return m ? [{ code: m[1], message: m[2] }] : [];
  }
  const FIXES = {};
  /**
   * Registers a fix-it: spec {run(btn, arg, status) → promise, press2: verb | fn(status) → verb|null, ok: toast text |
   * fn(result), gate: fn(status) → locked reason | null, href}. Every fix-it button on the page (button[data-fix]) is
   * greyed with its gate's reason on each render, so a fix offered anywhere (readiness cards, Setup band, Recovery,
   * refusals) says why it is locked. Core registers connect, home, I11-confirm, needle-confirm, purge-reset, pressure-on,
   * pressure-off, attest-regulator, attest-paste, same-seat; run.js registers plan-again, clean-check, probe, run-rest.
   */
  IL.fix = function (id, spec) { FIXES[id] = spec; return spec; };
  IL.fixes = FIXES;
  /**
   * A fix-it button from {fix, label, arg, primary, title, icon} | {href, label} | {view, label}. The handler is looked up
   * at click time, so fixes registered later work; unregistered ids fall back to spec.fallbackHref or nothing.
   */
  IL.fixButton = function (f, opts) {
    const o = opts || {};
    const b = el('button', { type: 'button', class: 'btn' + (f.primary ? ' btn--primary' : '') + (o.cls ? ' ' + o.cls : ''),
      'data-fix': f.fix || (f.view ? 'view:' + f.view : f.href ? 'go:' + f.href : null), title: f.title || null },
    f.icon ? IL.icon(f.icon) : null, f.label);
    if (f.href) b.addEventListener('click', () => IL.go(f.href));
    else if (f.view) b.addEventListener('click', () => IL.openView(f.view, { keep: true }));
    else if (f.fix) {
      const spec = FIXES[f.fix];
      if (spec && spec.press2) b.dataset.twoPress = typeof spec.press2 === 'function' ? 'dynamic' : spec.press2;
      b.addEventListener('click', (ev) => IL.runFix(f.fix, b, f.arg, ev));
      if (S.status) gateFix(b, S.status);
    }
    return b;
  };
  /** Applies the fix's gate to one button; re-enables only what this gate disabled (a module may gate it too). */
  function gateFix(b, s) {
    const spec = FIXES[b.dataset.fix];
    if (!spec || !spec.gate) return;
    const r = safe(() => spec.gate(s)) || null;
    if (r) { IL.gate(b, r); b.dataset.fixGate = r; }
    else if (b.dataset.fixGate !== undefined) {
      if (b.title === b.dataset.fixGate) IL.gate(b, null);
      delete b.dataset.fixGate;
    }
  }
  /**
   * Gates every fix-it on the page, then gives each row of fix-its a visible reason line (§11: every greyed control
   * says why, not only in its tooltip): the reasons of its gated buttons, once each. Rows that show their own reason
   * ([data-own-reason]) are left alone.
   */
  function gateFixButtons() {
    if (!S.status) return;
    const rows = new Map();
    for (const b of document.querySelectorAll('button[data-fix]')) {
      gateFix(b, S.status);
      const row = b.parentElement;
      if (!row || row.closest('[data-own-reason]')) continue;
      if (!rows.has(row)) rows.set(row, []);
      if (b.dataset.fixGate && b.disabled && !b.hidden) rows.get(row).push(b.dataset.fixGate);
    }
    for (const [row, reasons] of rows) {
      const text = Array.from(new Set(reasons)).join(' · ');
      let why = row.querySelector(':scope > .fix-why');
      if (!text) { if (why) IL.show(why, false); continue; }
      if (!why) { why = el('div', { class: 'reason fix-why' }, IL.icon('i-lock'), el('span')); row.appendChild(why); }
      IL.setText(why.lastChild, text);
      IL.show(why, true);
    }
  }
  IL.runFix = function (id, btn, arg, ev) {
    const spec = FIXES[id] || FALLBACK_FIX[id];
    if (!spec) return;
    if (spec.href) { IL.go(spec.href); return; }
    const action = () => spec.run(btn, arg, S.status);
    const verb = spec.press2;
    if (verb && btn) press2Click(btn, typeof verb === 'function' ? () => verb(S.status, arg) : verb, action, { ok: spec.ok }, ev);
    else IL.act(btn, action, { ok: spec.ok });
  };
  const FALLBACK_FIX = { 'plan-again': { href: '#run/board' }, 'clean-check': { href: '#run/checks' }, probe: { href: '#run/probe' } };
  /** Fix-its for a refusal's I-code (message: the engine's text, e.g. an I4 refusal that names a place). */
  IL.fixesForCode = function (code, status, message) {
    const s = status || S.status || {};
    const m = s.machine || {};
    const d = s.dispense || {};
    const row = d.purgeRow || {};
    const msg = String(message || '');
    switch (code) {
      case 'MACHINE': return m.enabled ? [{ fix: 'home', label: 'Home' }] : [{ fix: 'connect', label: 'Connect' }];
      case 'I11': return [{ fix: 'I11-confirm', label: 'Controller stayed on: confirm' }, { href: '#calibrate/angle', label: 'Measure again' }];
      case 'I9': return row.defined && row.spots < row.perBoard
        ? [{ href: '#setup/purge', label: 'Purge row: more spots' }, { href: '#profile', label: 'Profile: purge dots' }]
        : [{ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }];
      case 'I10': return [{ fix: 'clean-check', label: 'Wiped: check again' }];
      case 'I4': {
        const k = i4Kind(msg, d.boardSurfaceName || 'board');
        if (k === 'place') {
          const pm = /^place '([^']+)'/i.exec(msg);
          return [{ href: '#setup/places' + (pm ? '/' + encodeURIComponent(pm[1]) : ''), label: 'Retake Z with N1' + (pm ? ' (' + pm[1] + ')' : '') }];
        }
        if (k === 'other-surface') return [{ href: '#setup/surfaces', label: 'Open Probed surfaces' }];
        if (k === 'seat' || k === 'surface-bad' || k === 'plan') return [{ href: '#run/probe', label: 'Probe this seat' }];
        return [{ href: '#setup/needle', label: 'Open Needle Z frame' }];
      }
      case 'I7': return [{ href: '#setup/settings', label: 'Settings' }, { fix: 'run-fewer', label: 'Run fewer dots' }];
      case 'PLAN': return [{ href: '#profile', label: 'Pad types' }];
      case 'CONFIG': return [{ href: '#setup/settings', label: 'Open Settings' }];
      default: return [];
    }
  };

  // core fix-its
  IL.fix('connect', { run: () => IL.api('POST', '/api/machine/enable'), ok: 'Connecting to the controller…',
    gate: (s) => (S.online === false ? IL.lockReason(s) : null) });
  IL.fix('home', { press2: 'home', run: () => IL.api('POST', '/api/machine/home'), ok: 'Homing…',
    gate: (s) => IL.lockReason(s, { connected: true }) });
  IL.fix('I11-confirm', {
    gate: (s) => IL.lockReason(s, { connected: true, pressureOff: false }),
    run: async () => { const p = await IL.api('POST', '/api/screw/start-angle', {}); IL.planRefreshSoon(); return p; },
    ok: (p) => 'Start angle ' + IL.fmtDeg(p && p.startAngleDeg, 1) + ' confirmed for this controller power-up.',
  });
  IL.fix('needle-confirm', {
    gate: (s) => IL.lockReason(s, { pressureOff: false, engine: true }),
    run: async () => {
      const n = S.status && S.status.dispense && S.status.dispense.needle;
      if (!n || !isNum(n.anchorOffsetMm)) throw new Error('The needle Z frame is not set.');
      const r = await IL.api('POST', '/api/needle/offset', { offsetMm: Number(n.anchorOffsetMm.toFixed(3)), note: 'confirmed frame offset (web UI)' });
      IL.planRefreshSoon();
      return r;
    },
    ok: 'Needle offset confirmed: the frame offset.',
  });
  IL.fix('purge-reset', {
    press2: 'reset row',
    gate: (s) => IL.lockReason(s),
    run: async () => { const r = await IL.api('POST', '/api/purge-row/reset'); IL.planRefreshSoon(); return r; },
    ok: (r) => 'Purge row reset: ' + (r && isNum(r.spotsLeft) ? r.spotsLeft + ' spots free' : 'all spots free') + '.',
  });
  IL.fix('pressure-off', { run: () => IL.api('POST', '/api/pressure/stop'), ok: 'Pressure off · valve closed, then pump off' });
  IL.fix('pressure-on', {
    gate: (s) => (s.pressure && s.pressure.state && s.pressure.state !== 'off' ? 'Pressure is already on'
      : IL.lockReason(s, { connected: true, homed: true, engine: true })),
    // two presses unless the needle is over the waste (≤ 3 mm)
    press2: (s) => { const dist = IL.wasteDistance(s); return dist !== null && dist <= L.wasteNearMm ? null : 'pressure on'; },
    run: () => IL.api('POST', '/api/pressure/start'),
    ok: (r) => (r && (r.note || r.lastEvent)) || 'Pressure switching on',
  });
  IL.fix('attest-regulator', {
    run: async (btn, arg, s) => {
      const psi = s && s.dispense && s.dispense.profile && s.dispense.profile.pressurePsi;
      if (!isNum(psi)) throw new Error('The profile has no pressure.');
      return IL.attest.set('regulator', psi);
    },
    ok: (a) => 'Noted: the gauge reads ' + IL.fmtPsi(a.value) + ' psi (you, ' + IL.fmtTime(a.atMs) + ').',
  });
  IL.fix('attest-paste', { run: async () => IL.attest.set('paste-life', true), ok: 'Noted: paste within its working life.' });
  IL.fix('same-seat', { run: async () => IL.setNewSeat(null), ok: 'Same seat: the board surface still holds.' });

  // ================================================================================== dialogs (in-page, §11)
  const DLG = [];          // open dialogs, top last
  const ESC = [];          // Esc handlers, top last; each returns true when it handled Esc
  /** Registers an Esc handler (top-most first); returns the remove function. */
  IL.onEsc = function (fn) {
    ESC.push(fn);
    return () => { const i = ESC.indexOf(fn); if (i >= 0) ESC.splice(i, 1); };
  };
  const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  /**
   * Opens an in-page dialog (scrim under the strip, STOP stays live). opts: {id, eyebrow, title, keys (true: "Esc closes ·
   * F9 stops"), body, foot, size 'sm', initialFocus (element | selector | null = the dialog itself), onClose(reason)}.
   * Returns {el, scrim, head, body, foot, close(reason)}.
   */
  IL.dialog = {
    open(opts) {
      const o = opts || {};
      const id = o.id || 'dlg-' + Math.random().toString(36).slice(2, 8);
      const titleId = id + '-t';
      const head = el('div', { class: 'dialog__head' },
        el('div', {}, o.eyebrow ? el('div', { class: 'eyebrow', text: o.eyebrow }) : null, el('h2', { id: titleId, text: o.title || '' })),
        o.keys === false ? null : el('div', { class: 'dialog__keys' }, el('kbd', { text: 'Esc' }), ' closes ', el('kbd', { text: 'F9' }), ' stops'));
      const body = el('div', { class: 'dialog__body' }, o.body || null);
      const foot = el('div', { class: 'dialog__foot' }, o.foot || null);
      const dlg = el('div', { class: 'dialog' + (o.size ? ' dialog--' + o.size : ''), role: 'dialog', 'aria-modal': 'false', 'aria-labelledby': titleId, id, tabindex: '-1' }, head, body, foot);
      const scrim = el('div', { class: 'scrim', id: id + '-scrim' }, dlg);
      const prevFocus = document.activeElement;
      const h = { el: dlg, scrim, head, body, foot, id, closed: false };
      h.close = (reason) => {
        if (h.closed) return;
        h.closed = true;
        scrim.remove();
        const i = DLG.indexOf(h);
        if (i >= 0) DLG.splice(i, 1);
        h.escOff();
        if (o.onClose) safe(() => o.onClose(reason || 'close'));
        if (prevFocus && prevFocus.isConnected && prevFocus.focus) prevFocus.focus({ preventScroll: true });
      };
      h.escOff = IL.onEsc(() => { h.close('esc'); return true; });
      if (S.online === false) scrim.inert = true;
      document.body.appendChild(scrim);
      DLG.push(h);
      const f = typeof o.initialFocus === 'string' ? dlg.querySelector(o.initialFocus) : o.initialFocus;
      (f || dlg).focus({ preventScroll: true });
      return h;
    },
    /** Shows a native <dialog> non-modally (dialog.show(), never the modal variant) over our scrim. Returns a handle like open(). */
    showNative(dlgEl, opts) {
      const o = opts || {};
      const scrim = el('div', { class: 'scrim' });
      document.body.appendChild(scrim);
      if (dlgEl.parentNode !== document.body) document.body.appendChild(dlgEl);
      dlgEl.classList.add('dialog');
      dlgEl.inert = S.online === false;
      if (!dlgEl.open) dlgEl.show();
      const prevFocus = document.activeElement;
      const h = { el: dlgEl, scrim, closed: false };
      const onClose = () => h.close('close');
      h.close = (reason) => {
        if (h.closed) return;
        h.closed = true;
        dlgEl.removeEventListener('close', onClose);
        if (dlgEl.open) dlgEl.close();
        scrim.remove();
        const i = DLG.indexOf(h);
        if (i >= 0) DLG.splice(i, 1);
        h.escOff();
        if (o.onClose) safe(() => o.onClose(reason || 'close'));
        if (prevFocus && prevFocus.isConnected && prevFocus.focus) prevFocus.focus({ preventScroll: true });
      };
      dlgEl.addEventListener('close', onClose);
      h.escOff = IL.onEsc(() => { h.close('esc'); return true; });
      DLG.push(h);
      const f = typeof o.initialFocus === 'string' ? dlgEl.querySelector(o.initialFocus) : o.initialFocus;
      if (f) f.focus({ preventScroll: true });
      return h;
    },
    top() { return DLG[DLG.length - 1] || null; },
    closeAll() { while (DLG.length) DLG[DLG.length - 1].close('all'); },
  };
  function trapTab(e) {
    const h = IL.dialog.top();
    if (!h) return;
    const stop = $('btn-stop');
    const items = Array.from(h.el.querySelectorAll(FOCUSABLE)).filter((n) => n.offsetParent !== null || n === document.activeElement);
    if (!items.length) return;
    const first = items[0], last = items[items.length - 1];
    const a = document.activeElement;
    if (a === stop) { e.preventDefault(); (e.shiftKey ? last : first).focus(); return; }
    if (!h.el.contains(a)) { e.preventDefault(); first.focus(); return; }
    if (!e.shiftKey && a === last) { e.preventDefault(); stop.focus(); }
    else if (e.shiftKey && a === first) { e.preventDefault(); stop.focus(); }
  }
  function keySheet() {
    const rows = [
      [['F9'], 'STOP, from anywhere (also with focus in a field or a dialog open)'],
      [['Enter'], 'STOP when STOP has focus (Space does nothing there)'],
      [['Esc'], 'Closes the top-most thing: a pending two-press, a dialog, a panel view. Never stops.'],
      [['Alt', '1'], 'Run'], [['Alt', '2'], 'Setup'], [['Alt', '3'], 'Profile'], [['Alt', '4'], 'Calibrate'], [['Alt', '5'], 'Records'],
      [['?'], 'This list'],
    ];
    IL.dialog.open({ id: 'dlg-keys', title: 'Keys', size: 'sm',
      body: el('div', { class: 'keysheet' }, rows.map(([keys, what]) => [el('span', {}, keys.map((k) => el('kbd', { text: k }))), el('span', { text: what })])),
      foot: [el('span', { class: 'spacer' }), el('button', { type: 'button', class: 'btn', text: 'Close', onclick: () => { const t = IL.dialog.top(); if (t) t.close('button'); } })],
    });
  }
  IL.keySheet = keySheet;

  // ================================================================================== keyboard (§14)
  const isField = (n) => !!n && (n.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(n.tagName));
  window.addEventListener('keydown', (e) => {
    // F9 stops from anywhere, with any modifiers, in fields and dialogs; captured before anything else
    if (e.key === 'F9' || e.code === 'F9') {
      e.preventDefault();
      e.stopPropagation();
      if (!e.repeat) IL.stop('F9');
      return;
    }
    // Space never stops: suppressed on the STOP buttons (keydown and keyup)
    if ((e.key === ' ' || e.code === 'Space') && e.target && e.target.closest && e.target.closest('.stop')) {
      e.preventDefault();
    }
  }, true);
  window.addEventListener('keyup', (e) => {
    if ((e.key === ' ' || e.code === 'Space') && e.target && e.target.closest && e.target.closest('.stop')) e.preventDefault();
  }, true);
  // STOP: every [data-stop-copy] and #btn-stop, by delegation in the capture phase (never blocked by others). Wired
  // here, as F9 is, so STOP works even if the page never boots (app.js not loaded).
  document.addEventListener('click', (e) => {
    const b = e.target && e.target.closest && e.target.closest('#btn-stop, [data-stop-copy]');
    if (!b) return;
    e.preventDefault();
    IL.stop(e.detail === 0 ? 'key' : 'button');
  }, true);
  window.addEventListener('keydown', (e) => {
    if (e.defaultPrevented) return;
    if (e.key === 'Escape') {
      for (let i = ESC.length - 1; i >= 0; i--) {
        if (ESC[i]() === true) { e.preventDefault(); return; }
      }
      if (P.pushed) { e.preventDefault(); IL.panel.close(); }
      return;
    }
    if (e.key === 'Tab' && IL.dialog.top()) { trapTab(e); return; }
    if (e.altKey && !e.ctrlKey && !e.metaKey && /^Digit[1-5]$/.test(e.code)) {
      e.preventDefault();
      IL.go('#' + IL.WORKSPACES[Number(e.code.slice(5)) - 1]);
      return;
    }
    if (e.key === 'Enter' && isField(e.target) && e.target.tagName !== 'TEXTAREA' && !e.isComposing) {
      const host = e.target.closest('[data-enter-submits]');
      const b = host && document.getElementById(host.dataset.enterSubmits);
      if (b && !b.disabled) { e.preventDefault(); b.click(); }
      return;
    }
    if (e.key === '?' && !isField(e.target) && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); keySheet(); }
  });

  // ================================================================================== shared panel views
  const viewDefs = {};
  function sectionK(text, count) {
    return el('div', { class: 'section-k' }, text, count !== undefined && count !== null ? el('span', { class: 'count', text: String(count) }) : null, el('span', { class: 'rule' }));
  }
  IL.sectionK = sectionK;
  /** Readiness / recovery fix-it row. */
  function fixRow(fixes, cls) {
    if (!fixes || !fixes.length) return null;
    return el('div', { class: cls || 'ri__act' }, fixes.map((f) => IL.fixButton(f)));
  }
  IL.fixRow = fixRow;
  const ILK_KIND = { ok: 'ok', blocked: 'block', warning: 'need', off: 'off', info: 'info' };
  const ILK_PLAIN = {
    MACHINE: 'Machine connected and homed', CONFIG: 'Dispenser settings valid', I1: "OpenPnP's Stop switches the pneumatics off",
    I2: 'Screw zeroed before it moves', I3: 'Dispenser nozzle can never pick', I4: 'Needle Z frame, needle offset and surfaces',
    I5: 'Barrel pressure sensor', I6: "Regulator at the profile's pressure (no sensor)", I7: 'Maximum pressure on-time',
    I8: 'Paste working life', I9: 'Purge row', I10: 'Pads clean before the pressure goes on', I11: 'Screw start angle',
  };

  viewDefs.interlocks = {
    title: 'Interlocks',
    render(p, s, first) {
      if (first) {
        p.body.append(
          el('p', { class: 'panel__lead', text: 'Every check the dispenser makes before it moves or dispenses. The engine\'s own text is under each line.' }),
          el('div', { id: 'ilk-plan', class: 'stack' }),
          el('ul', { class: 'ilist', id: 'interlock-list' }));
      }
      const list = s.interlocks || [];
      const cc = S.plan && S.cleanCheck && !S.cleanCheck.ok ? S.cleanCheck : null;
      IL.keyed($('interlock-list'), JSON.stringify(list) + '|' + (cc ? cc.checkedAtMs : ''), () => list.map((i) => {
        let kind = ILK_KIND[i.state] || 'off';
        let extra = null;
        if (i.id === 'I10' && cc && i.state === 'ok') { kind = 'need'; extra = 'Paste found on ' + IL.plural((cc.dirtyPadIds || []).length, 'pad') + ' at ' + IL.fmtTime(cc.checkedAtMs) + '.'; }
        const fixes = i.state === 'blocked' || i.state === 'warning' || extra ? IL.fixesForCode(i.id, s, i.text) : [];
        const det = i.detail && i.detail.expected ? el('details', { class: 'ilist__e' }, el('summary', { text: 'Show lines' }),
          el('div', { text: 'Stop sends: ' + ((i.detail.disableCommand || []).join(', ') || 'nothing') }),
          el('div', { text: 'Expected: ' + i.detail.expected.join(', ') })) : null;
        return el('li', { class: i.state + (extra ? ' overlay' : ''), 'data-id': i.id, title: i.name },
          IL.glyph(kind, 'sm'),
          el('div', {}, el('div', { class: 'ilist__t' }, el('abbr', { class: 'icode', title: i.name, text: i.id }), ' ', ILK_PLAIN[i.id] || i.name),
            extra ? el('div', { class: 'ilist__d', text: extra }) : null),
          el('span', { class: 'icode', text: IL.WORD[kind] }),
          el('div', { class: 'ilist__e', text: i.text }), det,
          fixes.length ? fixRow(fixes, 'ilist__act') : null);
      }));
      const viol = (S.plan && S.plan.interlocks && S.plan.interlocks.violations) || [];
      IL.show($('ilk-plan'), !!S.plan);
      IL.keyed($('ilk-plan'), (S.plan ? S.plan.planId : '') + JSON.stringify(viol), () => (S.plan ? [
        sectionK('This plan · ' + String(S.plan.planId).slice(0, 4)),
        viol.length ? el('ul', { class: 'ilist' }, viol.map((v) => el('li', { class: 'blocked' }, IL.glyph('block', 'sm'),
          el('div', { class: 'ilist__t' }, el('abbr', { class: 'icode', text: v.code }), ' ', sentence(v.message)), el('span'),
          fixRow(IL.fixesForCode(v.code, s, v.message), 'ilist__act'))))
          : el('p', { class: 'panel__lead', text: 'All interlocks pass for this plan.' }),
        sectionK('Machine'),
      ] : null));
    },
  };

  viewDefs.machine = {
    title: 'Machine',
    render(p, s, first) {
      if (first) {
        p.body.append(el('dl', { class: 'kv kv--wrap', id: 'pv-machine-kv' }), el('div', { class: 'btn-row', id: 'pv-machine-act', 'data-refusals': '' }),
          sectionK('Head position (mm)'), el('div', { id: 'pv-machine-head' }),
          el('p', { class: 'panel__lead', text: "Jog in OpenPnP's window: this page cannot move the head." }),
          sectionK('Stop and nozzle'), el('ul', { class: 'ilist', id: 'pv-machine-ilk' }));
        const act = $('pv-machine-act');
        act.append(IL.fixButton({ fix: 'connect', label: 'Connect', primary: true }), IL.fixButton({ fix: 'home', label: 'Home again' }));
      }
      const m = s.machine || {}, prof = (s.dispense && s.dispense.profile) || {};
      IL.kv($('pv-machine-kv'), [
        ['Controller', m.enabled ? 'connected' + (prof.controllerConnectedAtMs ? ' since ' + IL.fmtTime(prof.controllerConnectedAtMs - S.skewMs) : '') : 'not connected'],
        ['Homed', m.homed ? 'yes' + (S.homedAt ? ', ' + IL.fmtTime(S.homedAt) : '') : 'no'],
        ['Moving', m.busy ? 'yes' : 'no'],
        ['Driver', (m.drivers || []).map((x) => x.name + (x.connection ? ' · ' + x.connection : '')).join('; ') || '—'],
        ['Simulator', IL.isSim(s) ? 'yes' : 'no'],
        ['OpenPnP API', s.server ? 'port ' + s.server.port + (s.server.version ? ' · ' + s.server.version : '') : '—'],
      ]);
      const [bc, bh] = $('pv-machine-act').children;
      IL.show(bc, !m.enabled);
      IL.show(bh, !!m.enabled);
      IL.gate(bc, S.online === false ? IL.lockReason(s) : null);
      IL.gate(bh, IL.lockReason(s, { connected: true }));
      if (bh && !IL.isArmed(bh)) IL.setText(bh, m.homed ? 'Home again' : 'Home');
      const pos = IL.positions(s);
      const row = (label, t, z) => el('tr', {}, el('td', { text: label }), el('td', { text: t ? IL.fmt(t.x, 2) : '—' }), el('td', { text: t ? IL.fmt(t.y, 2) : '—' }), el('td', { text: t && z ? IL.fmt(t.z, 2) : '—' }));
      const posKey = [pos.n1, pos.n2, pos.cam].map((t) => (t ? [t.x, t.y, t.z].map((v) => Number(v).toFixed(2)).join(',') : '-')).join('|');
      IL.keyed($('pv-machine-head'), posKey, () => el('table', { class: 'headtable' },
        el('thead', {}, el('tr', {}, ['mm', 'X', 'Y', 'Z'].map((h) => el('th', {}, IL.unitText(h))))),
        el('tbody', {}, row('N1 nozzle', pos.n1, true), row((pos.n2 && pos.n2.name ? pos.n2.name : 'N2') + ' needle', pos.n2, true), row('Camera', pos.cam, false))));
      const ilk = ['I1', 'I3'].map((id) => IL.interlock(s, id)).filter(Boolean);
      IL.keyed($('pv-machine-ilk'), JSON.stringify(ilk), () => ilk.map((i) => el('li', { class: i.state }, IL.glyph(ILK_KIND[i.state] || 'off', 'sm'),
        el('div', {}, el('div', { class: 'ilist__t' }, el('abbr', { class: 'icode', text: i.id }), ' ', ILK_PLAIN[i.id])), el('span'),
        el('div', { class: 'ilist__e', text: i.text }))));
    },
  };

  viewDefs.pressure = {
    title: 'Pressure',
    render(p, s, first) {
      if (first) {
        p.body.append(el('div', { id: 'pv-pr-state' }), el('div', { class: 'notebox notebox--info', id: 'pv-pr-target' }),
          el('dl', { class: 'kv kv--wrap', id: 'pv-pr-kv' }));
        const on = IL.fixButton({ fix: 'pressure-on', label: 'Pressure on', icon: 'i-gauge' });
        const off = IL.fixButton({ fix: 'pressure-off', label: 'Pressure off', primary: true });
        const reads = IL.fixButton({ fix: 'attest-regulator', label: 'Reads … psi' });
        p.foot.append(el('div', { class: 'btn-row', id: 'pv-pr-act', 'data-refusals': '', 'data-own-reason': '' }, on, off, reads),
          el('div', { class: 'reason', id: 'pv-pr-reason', hidden: true }),
          el('div', { class: 'reason', text: 'Switches off by itself after the on-time limit (I7). Prime, test dot and runs are locked while it is on.' }));
      }
      const pr = s.pressure || {}, d = s.dispense || {}, prof = d.profile || {};
      const pv = pressureView(s);
      const pvText = Array.isArray(pv.v) ? pv.v.slice(0, 2).join('') : pv.v;
      IL.keyed($('pv-pr-state'), pv.kind + '|' + pvText, () => el('div', { class: 'ready-head' },
        el('div', { class: 'ready-head__t' }, el('div', { class: 'eyebrow', text: 'Barrel pressure' }), el('div', { class: 'ready-head__q', text: pvText })),
        IL.glyph(pv.kind)));
      const psi = IL.fmtPsi(prof.pressurePsi);
      IL.keyed($('pv-pr-target'), psi + prof.name, () => [el('b', { text: 'Set the regulator to ' + psi + ' psi' }), el('div', { class: 'muted', text: 'profile ' + (prof.name || '') + ' · there is no pressure sensor: read the gauge. 16 psi starved the screw, 20 psi drooled.' })]);
      const dist = IL.wasteDistance(s);
      const reg = IL.attest.valid('regulator', s);
      IL.kv($('pv-pr-kv'), [
        ['State', pr.state === 'vent_failed' ? 'NOT off: the valve did not confirm' : pr.state || '—'],
        ['On since', pr.onSinceMs ? IL.fmtTimeS(pr.onSinceMs - S.skewMs) : '—'],
        ['Auto-off', isNum(d.maxContinuousOnTimeS) ? 'after ' + IL.fmtClock(d.maxContinuousOnTimeS) + ' on' + (isNum(pr.remainingS) ? ' · in ' + IL.fmtClock(pr.remainingS) : '') : '—'],
        ['Needle to waste', dist === null ? 'unknown (no waste place or no N2 position)' : IL.fmt(dist, 1) + ' mm' + (dist > L.wasteNearMm ? ': park N2 over the waste in OpenPnP first' : '')],
        ['Watchdog', pr.keepaliveArmed ? 'armed · last ping ' + (isNum(pr.lastPingAgeMs) ? IL.fmt(pr.lastPingAgeMs / 1000, 1) + ' s ago' : '—') : 'not armed'],
        ['Last event', (pr.lastEvent || '—') + (pr.lastEventAtMs ? ' · ' + IL.fmtTimeS(pr.lastEventAtMs - S.skewMs) : '')],
        ['Last error', pr.lastError || 'none'],
        ['Gauge read', reg ? 'you, ' + IL.fmtTime(reg.atMs) + ' (' + IL.fmtPsi(reg.value) + ' psi)' : 'not yet'],
      ]);
      const [bOn, bOff, bReads] = $('pv-pr-act').children;
      const holding = pr.state && pr.state !== 'off';
      const lockOn = holding ? null : IL.lockReason(s, { connected: true, homed: true, engine: true });
      IL.gate(bOn, holding ? (pr.state === 'vent_failed' ? 'Pressure is NOT off: press Pressure off' : 'Pressure is already on') : lockOn);
      const offWhy = holding || pr.state === 'vent_failed' ? null : 'Already off · vented';
      IL.gate(bOff, offWhy);
      // every greyed control says why (§11), on one line under the buttons
      const why = [holding ? 'Pressure on: already on' : lockOn ? 'Pressure on: ' + lockOn : null, offWhy ? 'Pressure off: ' + offWhy.toLowerCase() : null].filter(Boolean).join(' · ');
      IL.setText($('pv-pr-reason'), why);
      IL.show($('pv-pr-reason'), !!why);
      if (!IL.isArmed(bReads)) IL.setText(bReads, reg ? 'Read again: ' + psi + ' psi' : 'Reads ' + psi + ' psi');
    },
  };

  viewDefs.angle = {
    title: 'Start angle',
    render(p, s, first) {
      if (first) p.body.append(el('div', { id: 'pv-angle-q' }), el('dl', { class: 'kv kv--wrap', id: 'pv-angle-kv' }));
      const prof = (s.dispense && s.dispense.profile) || {};
      const a = IL.fmtDeg(prof.startAngleDeg, 1);
      const state = !prof.partialTurn ? 'off' : !prof.startAngleSetAtMs ? 'never' : prof.startAngleNeedsConfirm ? 'confirm' : 'ok';
      IL.keyed($('pv-angle-q'), state + a + prof.startAngleSetAtMs, () => {
        if (state === 'confirm') {
          return el('div', { class: 'ri ri--need', 'data-refusals': '' }, IL.glyph('need'),
            el('div', {}, el('div', { class: 'ri__t', text: 'Was the controller switched off since ' + IL.fmtTime(prof.startAngleSetAtMs - S.skewMs) + '?' }),
              el('div', { class: 'ri__d', text: 'OpenPnP reconnected to the controller' + (prof.controllerConnectedAtMs ? ' at ' + IL.fmtTime(prof.controllerConnectedAtMs - S.skewMs) : '')
                + '. STOP and Connect keep the screw step count; only switching the controller off loses it.' })),
            el('abbr', { class: 'icode', title: 'Interlock I11', text: 'I11' }),
            el('div', { class: 'ri__act' }, IL.fixButton({ fix: 'I11-confirm', label: 'No, it stayed on: ' + a + ' still holds' }),
              IL.fixButton({ href: '#calibrate/angle', label: 'Yes, it was off: measure again' })));
        }
        if (state === 'never') {
          return el('div', { class: 'ri ri--block' }, IL.glyph('block'), el('div', {}, el('div', { class: 'ri__t', text: 'The start angle was never set' }),
            el('div', { class: 'ri__d', text: 'A partial-turn profile needs the screw angle the dots start at.' })), el('abbr', { class: 'icode', text: 'I11' }),
          fixRow([{ href: '#calibrate/angle', label: 'Measure the angle', primary: true }]));
        }
        return el('div', { class: 'ri' }, IL.glyph(state === 'off' ? 'off' : 'ok'), el('div', {},
          el('div', { class: 'ri__t', text: state === 'off' ? 'Whole turns: the start angle does not matter' : 'Start angle ' + a }),
          el('div', { class: 'ri__d', text: state === 'off' ? 'Each dot turns the screw a whole number of turns.' : 'Set or confirmed for this controller power-up.' })),
        el('abbr', { class: 'icode', text: 'I11' }), state === 'ok' ? fixRow([{ href: '#calibrate/angle', label: 'Measure again' }]) : null);
      });
      IL.kv($('pv-angle-kv'), [
        ['Start angle', a],
        ['Dots start at', IL.startAnglesText(prof)],
        ['Set', prof.startAngleSetAtMs ? IL.fmtDateTime(prof.startAngleSetAtMs - S.skewMs) : 'never'],
        ['Controller connected', prof.controllerConnectedAtMs ? IL.fmtDateTime(prof.controllerConnectedAtMs - S.skewMs) : '—'],
      ]);
    },
  };

  viewDefs.purge = {
    title: 'Purge row',
    render(p, s, first) {
      if (first) {
        p.body.append(el('div', { id: 'pv-purge-strip' }), el('dl', { class: 'kv kv--wrap', id: 'pv-purge-kv' }));
        p.foot.append(el('div', { class: 'btn-row', 'data-refusals': '' }, IL.fixButton({ fix: 'purge-reset', label: 'Wiped: reset row', icon: 'i-wipe' }),
          IL.fixButton({ href: '#setup/purge', label: 'Setup › Purge row' })), el('div', { class: 'reason', id: 'pv-purge-reason', hidden: true }));
      }
      const row = (s.dispense && s.dispense.purgeRow) || {};
      IL.keyed($('pv-purge-strip'), JSON.stringify([row.spots, row.usedSpots, row.perBoard, row.defined]), () => {
        if (!row.defined) return el('div', { class: 'notebox notebox--block', text: 'The purge row is not set: set it in Setup (origin, pitch and spots on the blank).' });
        const cells = [];
        for (let i = 0; i < row.spots; i++) cells.push(el('i', { class: i < row.usedSpots ? 'used' : i < row.usedSpots + row.perBoard ? 'next' : null }));
        return [el('div', { class: 'ready-head' }, el('div', { class: 'ready-head__t' }, el('div', { class: 'eyebrow', text: 'Spots free' }),
          el('div', { class: 'ready-head__q', text: row.spots < row.perBoard ? 'Too short for one board (' + row.perBoard + ' per board)' : row.spotsLeft < row.perBoard ? 'Wipe the row and reset it' : 'Enough for ' + IL.plural(row.boardsLeft, 'board') })),
        el('div', { class: 'ready-head__n' }, String(row.spotsLeft), el('small', { text: ' of ' + row.spots }))),
        el('div', { class: 'purge purge--lg', role: 'img', 'aria-label': 'Purge row: ' + row.spotsLeft + ' of ' + row.spots + ' spots free', style: 'margin-top:10px' }, cells),
        el('div', { class: 'ready-sub' }, el('span', { text: row.usedSpots + ' used · ' + row.spotsLeft + ' free' }), el('span', { text: 'next board uses ' + row.perBoard }))];
      });
      const next = row.defined ? { x: row.x0Mm + row.usedSpots * row.pitchXMm, y: row.y0Mm + row.usedSpots * row.pitchYMm } : null;
      IL.kv($('pv-purge-kv'), [
        ['Surface', row.surfaceName || '—'],
        ['Next spot', next ? 'X ' + IL.fmt(next.x, 2) + ' · Y ' + IL.fmt(next.y, 2) + ' mm' : '—'],
        ['Pitch', row.defined ? IL.fmt(row.pitchXMm, 2) + ' / ' + IL.fmt(row.pitchYMm, 2) + ' mm' : '—'],
        ['Last wiped', row.resetAtMs ? IL.fmtDateTime(row.resetAtMs - S.skewMs) : 'never'],
      ]);
      const btn = p.foot.querySelector('[data-fix="purge-reset"]');
      IL.gate(btn, !row.defined ? 'The purge row is not set' : IL.lockReason(s) || (row.spots < row.perBoard ? 'A reset cannot help: the row has fewer spots than one board purges' : null), $('pv-purge-reason'));
    },
  };

  let eventsTimer = null;
  viewDefs.activity = {
    title: 'Activity',
    render(p, s, first) {
      if (first) {
        p.body.append(el('p', { class: 'panel__lead', text: 'Newest first: what OpenPnP logged and what this page did.' }), el('ul', { class: 'ilist', id: 'pv-activity' }));
        IL.loadEvents(50);
        clearInterval(eventsTimer);
        eventsTimer = setInterval(() => { if (!document.hidden) IL.loadEvents(50); }, L.eventsEveryMs);
      }
      const ev = (S.events || []).map((x) => ({ atMs: x.ts - S.skewMs, text: (x.code ? x.code + ': ' : '') + IL.eventText(x.message), full: x.message, kind: x.level === 'ERROR' ? 'block' : x.level === 'WARN' ? 'need' : 'info', src: 'OpenPnP' }));
      const page = S.activity.map((x) => Object.assign({ src: 'this page' }, x));
      const all = ev.concat(page).sort((a, b) => b.atMs - a.atMs).slice(0, 120);
      IL.keyed($('pv-activity'), all.length + '|' + (all[0] ? all[0].atMs + all[0].text : ''), () => all.map((x) => el('li', { class: x.kind === 'block' ? 'blocked' : x.kind === 'need' ? 'warning' : '' },
        IL.glyph(x.kind === 'block' ? 'block' : x.kind === 'need' ? 'need' : x.kind === 'ok' ? 'ok' : 'info', 'xs'),
        el('div', { title: x.full || null }, el('div', { class: 'ilist__d', style: 'color:var(--ink);margin:0' }, x.text)),
        el('span', { class: 'icode', title: x.src }, IL.fmtTimeS(x.atMs)))));
    },
    unmount() { clearInterval(eventsTimer); eventsTimer = null; },
  };

  viewDefs.camera = {
    title: 'Top camera',
    render(p, s, first) {
      if (!first) return;
      const img = el('img', { alt: 'Top camera snapshot', style: 'width:100%;border-radius:10px;display:block', hidden: true });
      const empty = el('div', { class: 'notebox', text: 'No picture yet: press Snapshot (nothing moves).' });
      const snap = el('button', { type: 'button', class: 'btn', 'data-refusals': '' }, IL.icon('i-camera'), 'Snapshot');
      snap.addEventListener('click', async () => {
        snap.disabled = true;
        try {
          const r = await fetch('/api/camera/top.jpg?w=800&t=' + Date.now(), { cache: 'no-store' });
          if (!r.ok) {
            let reason = r.status === 404 ? 'no top camera' : r.status === 503 ? 'no frame within 10 s' : r.statusText;
            try { reason = (await r.json()).reason || reason; } catch (e) { /* not JSON */ }
            throw new Error(reason);
          }
          if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
          img.dataset.url = URL.createObjectURL(await r.blob());
          img.src = img.dataset.url;
          img.hidden = false;
          empty.hidden = true;
        } catch (e) {
          img.hidden = true;
          empty.hidden = false;
          empty.textContent = 'No picture: ' + e.message + (IL.isSim(S.status) ? ' (the simulator shows a static picture)' : '');
        } finally { snap.disabled = false; }
      });
      p.body.append(img, empty, el('p', { class: 'panel__lead', text: 'Takes one picture with the top camera. Nothing moves and no light switches.' }));
      p.foot.append(el('div', { class: 'btn-row' }, snap));
    },
  };

  viewDefs.recovery = {
    // the red card under it carries the time: the bar names the view
    title: 'Recovery',
    render(p, s, first) {
      const r = S.recovery || S.lastStop;
      if (first) p.body.append(el('div', { id: 'pv-rec-card' }), el('div', { id: 'pv-rec-steps' }), el('div', { id: 'pv-rec-did' }));
      if (!r) return;
      // on Run the main column already says how many dots and "keep the board where it is"
      const onRun = S.route.ws === 'run';
      IL.keyed($('pv-rec-card'), r.atMs + r.cause + (r.run ? r.run.dotsDone : '') + onRun, () => {
        const lines = [];
        if (r.cause === 'stop') {
          lines.push(el('div', { class: 'stopcard__s', text: "By this page's STOP" + (r.source === 'F9' ? ' (F9)' : '') + '. OpenPnP confirmed it. Sent before the next line:' }),
            el('div', { class: 'gcode', text: STOP_CODES }));
        } else if (r.cause === 'error') lines.push(el('div', { class: 'stopcard__s', text: 'Ended by an error: ' + (r.message || (r.run && r.run.message) || 'see Activity') }));
        else if (r.cause === 'openpnp') lines.push(el('div', { class: 'stopcard__s', text: 'Stopped in OpenPnP while a task ran.' }));
        else lines.push(el('div', { class: 'stopcard__s', text: 'The task was stopped' + (r.message ? ': ' + r.message : '.') }));
        if (r.run && r.run.label === 'board' && !onRun) {
          lines.push(IL.noDotsReached(r.run)
            ? el('div', { class: 'stopcard__s' }, el('b', { text: (r.run.boardName || 'Board') + ': no dot reached the board.' }), ' Run it again once these steps are done.')
            : el('div', { class: 'stopcard__s' }, el('b', { text: (r.run.boardName || 'Board') + ': ' + r.run.dotsDone + ' of ' + r.run.dotsPlanned + ' dots.' }),
              ' Keep the board where it is: the dots belong to this seat.'));
        }
        return el('div', { class: 'stopcard', role: 'alert' }, el('div', { class: 'stopcard__t' }, IL.glyph('block'), recoveryHeadline(r)), lines);
      });
      const steps = IL.recoverySteps(s, r);
      const done = steps.filter((x) => x.done).length;
      const key = JSON.stringify(steps.map((x) => [x.key, x.done, x.now, x.wait, x.title, x.detail, x.fixes.length]));
      IL.keyed($('pv-rec-steps'), key, () => [sectionK('Back to ready', done + ' of ' + steps.length),
        el('ol', { class: 'rsteps', style: 'margin-top:10px' }, steps.map((x) => el('li', {
          class: 'rstep' + (x.done ? ' rstep--done' : x.now ? ' rstep--now' : x.wait ? ' rstep--wait' : ''), 'data-step': x.key, 'data-refusals': '' },
        // done ✓ · blocked ✕ · checking · the step to do now (its number) · can be done now (!) · later (its number)
        x.done ? IL.glyph('ok') : x.state === 'block' ? IL.glyph('block') : x.state === 'checking' ? IL.glyph('checking', null, 'Waiting for OpenPnP')
          : x.now ? IL.glyph('now', null, 'Now: step ' + x.n, x.n) : !x.wait ? IL.glyph('need', null, 'Can be done now') : IL.glyph('num', null, 'Step ' + x.n, x.n),
        el('div', {}, el('div', { class: 'rstep__t', text: x.title }), el('div', { class: 'rstep__d', text: x.detail })),
        el('span'),
        x.fixes.length ? fixRow(x.fixes, 'rstep__act') : null)))]);
      IL.keyed($('pv-rec-did'), r.cause + (r.actions || []).join('|'), () => (r.cause === 'stop' ? [sectionK('What STOP did'),
        el('ul', { class: 'did', style: 'margin-top:10px' }, IL.STOP_DID.map(([t, c]) => el('li', {}, IL.glyph('ok', 'xs'), el('span', { class: 't', text: t }), el('span', { class: 'c', text: c })))),
        r.actions && r.actions.length ? el('details', { class: 'disclose' }, el('summary', { text: 'OpenPnP\'s answer' }),
          el('ul', { class: 'reason', style: 'flex-direction:column;padding-left:18px' }, r.actions.map((a) => el('li', { text: a })))) : null] : null));
    },
  };
  for (const [k, v] of Object.entries(viewDefs)) IL.view(k, v);

  // ================================================================================== frame wiring + boot
  function wireFrame() {
    // (STOP clicks are wired when core.js loads, next to F9)
    // strip cells open their panel views; the verdict cell opens Run
    for (const c of document.querySelectorAll('[data-open-view]')) c.addEventListener('click', () => IL.openView(c.dataset.openView));
    $('pill-engine').addEventListener('click', () => {
      const s = S.status;
      IL.go(IL.recoveryActive() ? '#run' : s && s.run && s.run.running ? '#run/run' : '#run/checks');
    });
    $('btn-start').addEventListener('click', (ev) => IL.runFix('connect', ev.currentTarget, null, ev));
    $('btn-home').dataset.twoPress = 'home';
    $('btn-home').addEventListener('click', (ev) => IL.runFix('home', ev.currentTarget, null, ev));
    $('panel-back').addEventListener('click', () => IL.panel.close());
    for (const b of document.querySelectorAll('[data-theme-set]')) b.addEventListener('click', () => IL.setTheme(b.dataset.themeSet));
    for (const b of document.querySelectorAll('[data-theme-toggle]')) b.addEventListener('click', () => IL.setTheme(IL.theme() === 'dark' ? 'light' : 'dark'));
    $('skip-main').addEventListener('click', (e) => { e.preventDefault(); $('main').focus(); });
    window.addEventListener('hashchange', applyRoute);   // also fires on back / forward between our hash entries
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { IL.pollNow(); IL.render(); } });
    syncThemeControls();
  }
  /** Starts the page: mounts the registered workspaces, applies the route, starts the poll and the heartbeat. */
  IL.boot = function () {
    if (booted) return;
    booted = true;
    bootAt = Date.now();
    wireFrame();
    for (const w of Object.values(WS)) mountWs(w);
    S.route = { ws: null, section: null, id: null, panel: null };
    applyRoute();
    setInterval(heartbeat, 250);
    setInterval(() => { if (!document.hidden && S.planId && !S.planPending && Date.now() - S.planAt > L.planEveryMs) { S.planAt = Date.now(); IL.planRefresh({ quiet: true }); } }, 1000);
    heartbeat();
    poll();
    IL.emit('boot');
  };
  // app.js boots the page; deferred scripts run before DOMContentLoaded, so this only boots a page whose app.js did
  // not load or run (the poll, the heartbeat and the offline band still start)
  document.addEventListener('DOMContentLoaded', () => { if (!booted) IL.boot(); });
})();
