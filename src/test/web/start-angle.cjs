/**
 * Sets the SIM's screw start angle (I11) for a test's set-up, as a measurement would after BR-02. After a controller
 * reset or a switch-off of the screw motor (every STOP's M18 B) a bare confirm is refused, and so is a set until the
 * screw has turned since (a measurement's dot pair turns it): then this turns the screw a few degrees through the G-code
 * passthrough and sets the profile's own angle. Otherwise (nothing since the angle was set, or an engine before BR-02)
 * the bare confirm does it, as before. The page's requests must be the user's (its context sends X-IL-Client: ui), as
 * the passthrough takes only the holder's lines. Nothing here moves X, Y or Z.
 *
 * setStartAngle(page, base, note) → 'confirmed' | 'measured'; throws when the angle could not be set.
 */
'use strict';

async function call(page, base, method, p, body) {
  const r = await page.request.fetch(base.replace(/\/?$/, '/') + p.replace(/^\//, ''), { method, data: body, timeout: 120000 });
  let data = null;
  try { data = await r.json(); } catch (e) { data = null; }
  return { status: r.status(), data };
}
const said = (a) => a.status + ' ' + JSON.stringify(a.data).slice(0, 300);

async function setStartAngle(page, base, note) {
  const c = await call(page, base, 'POST', '/api/screw/start-angle', { note });
  if (c.status === 200) return 'confirmed';
  if (c.status !== 409 || !/measure/i.test(String((c.data && c.data.reason) || ''))) throw new Error('start angle: ' + said(c));
  // the screw turns once (B is in degrees: 3.6° is 16 of its 1600 steps a turn), then the angle is set as measured.
  // Where B is: OpenPnP's B axis in the status (OpenPnP's driver takes M114's position line as its own report, so the
  // passthrough's M114 has no reply lines, 11-04), else the reply of a controller that prints it
  const st = await call(page, base, 'GET', '/api/status');
  // the screw turns only on a simulator: never a G-code line to a real machine from a test's set-up (Backend, 11-04)
  if (!(st.data && st.data.server && st.data.server.simulated === true)) throw new Error('start angle: not a simulator (status.server.simulated is not true): the screw was not turned');
  const ax = ((st.data && st.data.machine && st.data.machine.axes) || []).find((a) => a.letter === 'B');
  let b = ax ? Number(ax.driverCoordinate) : NaN;
  if (!Number.isFinite(b)) {
    const m = await call(page, base, 'POST', '/api/gcode', { lines: ['M114'], what: note + ': where the screw is' });
    const replies = m.data && m.data.results && m.data.results[0] ? m.data.results[0].replies.join(' ') : '';
    b = Number((/\bB:\s*(-?\d+(?:\.\d+)?)/.exec(replies) || [])[1]);
    if (m.status !== 200 || !Number.isFinite(b)) throw new Error('start angle: no B axis in the status and no position from M114: ' + said(m));
  }
  const t = await call(page, base, 'POST', '/api/gcode', { lines: ['G1 B' + (b + 3.6).toFixed(3) + ' F36000', 'M400'], what: note + ': the screw turns once' });
  if (t.status !== 200) throw new Error('start angle: the screw move: ' + said(t));
  const s = await call(page, base, 'GET', '/api/status');
  const deg = s.data && s.data.dispense && s.data.dispense.profile ? s.data.dispense.profile.startAngleDeg : null;
  const r = await call(page, base, 'POST', '/api/screw/start-angle', { startAngleDeg: deg, note: note + ' (measured again after the screw turned)' });
  if (r.status !== 200) throw new Error('start angle: the set: ' + said(r));
  return 'measured';
}

module.exports = { setStartAngle };
