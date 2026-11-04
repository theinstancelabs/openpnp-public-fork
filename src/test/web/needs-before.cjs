/**
 * pad-profile and tip-setups use the state the scripts before them leave in their SIM session (the needle frame, the
 * landing, the offsets): run alone they fail mid-run, tip-setups with paste threads at its step (6) after ~12 min (Infra
 * 11-04: the session's state, not the SIM's randomness). run-webui-sim-test.sh says which scripts ran before
 * (WEBUI_RAN); run directly with node, a script cannot know and does not check.
 * needsBefore(name, before, outFile): exits 1 at once with a named failure (also in outFile) when one of `before` did not
 * run first in this session.
 */
'use strict';
const fs = require('fs');

module.exports = function needsBefore(name, before, outFile) {
  if (process.env.WEBUI_RAN === undefined) return;
  const ran = process.env.WEBUI_RAN.split(/\s+/).filter(Boolean);
  const missing = before.filter((n) => !ran.includes(n));
  if (!missing.length) return;
  const msg = name + ' needs ' + missing.join(', ') + ' first in the same SIM session (it uses the state they leave): run WEBUI_SCRIPTS="'
    + before.concat(name).join(' ') + '", as CI\'s main leg does.';
  console.log('FAIL ' + msg);
  fs.writeFileSync(outFile, JSON.stringify({ results: [{ name: '(0) ' + msg, ok: false, detail: { ran } }] }, null, 2));
  process.exit(1);
};
