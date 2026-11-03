# What still needs the real machine

The engine follows the bench recipe (`p18_board1`: 18 psi, half a screw turn per dot, 250 ms dwell, 8 purge dots,
F9 Stop), and the simulator acceptance passes on it
(`/home/lumen/instancelabs/sim-acceptance/bench-sync-20261028-003315/results.json`). Branch `il/bench-sync-autoreg`
adds Register board (fiducials and the N1 vacuum probe), home at job start and the Z check
(`AUTO_REGISTER_DESIGN.md`), the KiCad upload and the board drawing (`BOARD_IMPORT_DESIGN.md`), and missing paste
profiles: the profile check, per-pad-type doses and the pad calibration with the weigh coupons
(`PROFILE_CALIBRATION_DESIGN.md`; the SIM acceptance `il_sim_acceptance.py padcal` runs it end to end on the SIM
paste and balance). Branch `il/tip-setups` adds tip setups (`PROFILE_CALIBRATION_DESIGN.md` §22: per-tip profiles,
mounting, where each tip lands, "calibrated for" labels and pictures; `il_sim_acceptance.py tips`). The simulator
cannot show the items below. Each one says why, and how to check it on the machine.

## Next session, in this order

Sessions 4 and 5 (below) re-measured Z in OpenPnP's frame, laid the first paste through the engine and changed the
needle. Use a jar built from `il/rc1` (or a dist made from it, item 3).

1. **Paste flow with the new needle.** After the needle swap on 2026-10-30 no paste came out (see below). Once it
   flows: isolated dots (6 mm pitch) to confirm the needle offset N2 (18.50, −70.99), then the touch-off with the
   F9/F14 fix at needle Z offset −4.75 (K 67.8) on the blank area x 305–316, y 225–239 (probed, flat to 0.02 mm).
   Apply its contact only if it agrees with the parallax K within 0.3 mm.
2. **STOP during homing** (item 14, F15): STOP hangs the controller. Needs the firmware fix on il/safety (flashing is
   the user's decision) before anyone relies on STOP during a home.
3. **A board without fiducials** (F10): probe-only registration on the blank, then a short run.
4. **Vent before lift** (item 22, F7): measure on board 3.
5. Then the first-session items still open (1, 2, 4, 5, 9–13), the registration items 15–32, the board-import and
   profile items 33–54 as their slices reach the machine, and the tip-setup items 55–68.

## Real sessions of 2026-10-28 to 10-30 (automatic registration, board 3 and the blank)

Session 2 (2026-10-28 23:57 – 10-29 00:22): jar `il/bench-sync-autoreg` 37fe1af46, config copy
`~/instancelabs/realcfg-sw-20261028b`. A real-mode start at 22:43 on b126fefb1 came first: the top camera snapshot
was empty (no JPEG writer in that JVM; now a PNG, f7a8bd712). Data: `~/instancelabs/runs/sw_session2/`
(`register_b3_1.json`, `register_b3_2.json`, `blank_probe1.json`, `probe_300.32_245.5.json`, `looks/` with
`looks/labels.json`, `OpenPnP_session2.log`; `dispense_session2.db` there is the main file without its WAL: it has no
tables, so the PROBE records are only in the JSON results).

Session 3 (2026-10-29 03:00–04:30): jars 2df93ed3d (F1, F2, F4) and bdc9d2d31 (F6), config copy
`~/instancelabs/realcfg-sw-20261029` (session 2's, with `vacuumProbe.alternates` 4). Data:
`~/instancelabs/runs/sw_session3/` (`s2_home*.json`, `s4_home3.json`, `s3_register_probe*.json`,
`s4_register_after_home.json`, `s4_register_r12_1.json`, `s4_register_r12_2.json`, `s4_register_z535_auto.json`,
`s4_zcheck1.json`, `blank_325_2475.jpg`, `blank_305_250.jpg`, `openpnp_log/`). Everything in sessions 2 and 3
went through the web API; no blank probe, touch-off or paste was done in session 3, on purpose.

Session 4 (2026-10-29 15:28–18:05): jar a4bb6d20d (`~/instancelabs/realjar-sw7`), config copies
`~/instancelabs/realcfg-sw-20261029c` → `…f` (each session started from the previous one's config). Data:
`~/instancelabs/runs/sw_session4/` (`s1_home.json`, `s4_blank_probe1.json`, `s5_anchor.json`, `s6_touchoff1.json`,
`s7_*.json`, `s8_*.json`, `s9_*.json`, `ladder2_*.jpg`, `result_*.jpg`, `log_sw-real6..8/`). The first paste through
the engine on the real machine. Session config changes (on the copy only): chip-0603 raised to `characterized` from the
bench's evidence, the clean-pad check off (F12), the purge rows on the blank, the needle offset −2.60 mm.

Session 5 (2026-10-30 16:05–21:20): jar `~/instancelabs/realjar-sw8` (scratch build il/real9 c868c6f3f = il/rc1
e932de678 + il/fr2-wire 127a03171; not a release), config copies `~/instancelabs/realcfg-sw-20261030a` → `…k` (each
session started from the previous one's config). Data: `~/instancelabs/runs/sw_session5/` (`s1_*` to `s25_*`,
`stophome/`, `newneedle/`).

The live `~/.openpnp2` was not touched in any of these sessions.

### What the machine showed

| What | Measured | Data |
|---|---|---|
| Home through `POST /api/machine/home` | 21 s (session 2), 22 s each of the 3 homes of session 3. Register board's own home first: 23.0 s. The engine's estimate is 25 s until it has timed a home. | session notes (the answers `s2_home*.json` and `s4_home3.json` hold no time), `register_b3_1.json` (`home.seconds` 22.98) |
| Fiducials of board 3 | Every fiducial within the search radius was found, in all 8 registrations of sessions 2 and 3: 1–3 camera frames each, centred to 0.25–2.9 px. Fit scale 1.0014 / 0.9940, the bench's fit of the same board 1.0016 / 0.9935. | `register_b3_*.json`, `s3_register_probe*.json`, `s4_register_*.json`; the fit `register_b3_1.json` |
| Same seat | Re-registered without moving the board: fiducials re-found within 0.003–0.04 mm (seat tolerance 0.10 mm). | `register_b3_2.json`, `s3_register_probe2.json`, `s4_register_r12_2.json` |
| Board 3 probe | 5 of 5 contacts, Z 5.25–5.50, plane residual 0.015 mm (limit 0.04), slope 0.0037 mm/mm, no alternate needed. | `s3_register_probe2.json` |
| Z across an OpenPnP home | The same 5 points after a home: +0.025, +0.025, +0.025, +0.025, 0.000 mm. OpenPnP's homes repeat to one fine step (0.025 mm). | `s4_register_after_home.json` |
| Same-seat check | Registered again at the same seat: the 3 stored points re-touched +0.025 mm each (tolerance 0.03): surface kept. | `s4_register_r12_2.json` |
| N1 open air | Baselines −1.63 M to −1.67 M counts (window −1.90 M to −1.20 M); seal threshold 15 % of the baseline ≈ 244–251 k counts. | the probe points (`baseline`, `drop`) |
| Release | At 14 of the 15 board-3 contacts (the three full probes of session 3) the last open coarse step still held a partial seal after 1.5 s; each let go one coarse step (0.25 mm) higher, as designed. Point 3 of `s3_register_probe2.json` let go at once. | the points' notes in `s3_register_probe2.json`, `s4_register_after_home.json`, `s4_register_r12_1.json` |

### What the machine showed (session 4)

| What | Measured | Data |
|---|---|---|
| Blank probe | 4 of 4 contacts, Z 6.150–6.200, residual 0.006 mm; later 5 of 5, Z 6.200–6.300, residual 0.011 mm. The blank did not follow N1: F3 was F6 (fixed in bdc9d2d31). | `s4_blank_probe1.json`, `s7_blank_probe2.json` |
| Needle frame in OpenPnP's frame | Side camera, needle tip level with N1's: K 65.6–65.7 (read by hand, F13). Recorded on the blank at (300, 250), surface 6.18: K 65.60, needle contact 3.58, offset −2.60 mm. That is the bench's K 64.50 plus twice a ~0.55 mm frame shift. | `s5_anchor.json`, `k1/` |
| Touch-off ladder | Dots 1.0 mm apart (gaps 0.30 → −0.05): the dots ran together and the analysis saw none ("no dot at the largest gap"). Dots 1.6 mm apart (gaps 0.45 → 0.00): the photos show round dots at 0.00–0.20 and strings from 0.25, but the analysis measured only the 0.45 dot and reported a contact at 0.425 mm (F9: applied, it would have raised the needle about 0.4 mm). Neither was applied; the offset −2.60 stands. | `s6_touchoff1.json`, `s7_touchoff2.json`, `s6_after_touchoff.png`, `s7_after_touchoff2.png`, `ladder2_*.jpg` |
| Z check after a home | First try refused: the only data from before the home was session 3's 'board' surface, with no point of its own home left (F11; deleted by hand). Then 6.225 vs 6.200 (the blank), 6.200 vs 6.200 (in run 1), 6.325 vs 6.300 (run 2): all pass (limit 0.03). Run 1's check skipped a point with touch-off paste on it (look: 9 %), run 2's two with run 1's paste (19 %, 11 %); each used the next stored point. | `s7_zcheck.json`, `s7_zcheck2.json`, `s8_run1*.json`, `s9_run2.json` |
| Run 1 | 6 × 0603 on the blank: refused first by the clean-pad check (bare mask has no copper: every pad 0.00, F12); with the check off: 8 purge dots + 12 of 12, 18.6 s, motion 9.1 s (4,737 dots per hour of dot motion). | `s8_run1.json`, `s8_run1b.json` |
| Run 2 after Stop and Start | Homed first (22 s, "stopped or reconnected since the last home"), checked Z first (one touch), 8 purge dots + 6 of 6, 58.9 s. | `s9_run2.json` |
| The dots | All 18 formed, round, about 1 mm across; each has a short tail on the same side. | `result_runs_small.jpg`, `result_291.5_255.jpg`, `result_291_244.5.jpg` |

### What the machine showed (session 5)

| What | Measured | Data |
|---|---|---|
| Home | 21–22 s, every time (8 homes). | `s1_home.json`, `s13_home.json`, `s22_home.json`, `newneedle/n1_home.json`, `newneedle/n2_home.json` |
| Z check after a home | Against session 4's data: refused twice ("Z moved 0.13 mm"), then 0.08, 0.10, 0.15 after a power cycle. The first reference had paste near it and the check used the next stored point (the fallback works on the machine). After deleting the stale 'board' and 'runarea' surfaces and probing the blank again: 0.025, 0.000, 0.025 (pass). After the needle swap: refused, "Z moved −0.35 mm" at (299, 264.5): the loose blank or N1's tip moved during the swap; the new probe below replaced the data. | `s2_zcheck.json`, `s5_zcheck3.json`, `s13_zcheck.json`, `s22_zcheck.json`, `newneedle/n2_zcheck.json` |
| STOP during homing (item 14) | Web STOP 4 s into a home: X/Y stopped within ~0.3 s (top-camera frames), but M410 got no answer, the web home ended at +60 s and the controller answered nothing (not even M115). Reopening the port did not reset it (item 32). Needed a power cycle (F15). | `stophome/` |
| Blank flatness | y 226–236: flat to 0.010 mm; y 264.5–267.5: 0.015 mm; a strip at y 273–276 refused (a point 0.080 mm off the plane: the loose blank bows 0.1–0.2 mm, the size of a touch-off step). New area x 305–316, y 225–239: 0.020 mm, tilted 0.6 mm across. | `s6_blank_probe2.json`, `s18_blank_strip.json`, `s25_blank_strip2.json`, `newneedle/n4_blank_probe.json` |
| Regulator | Found at 20 psi (bumped): the tip drooled. Back at 18 psi: no drool after 5 min (bottom camera). | `needle_bottom_18psi.jpg` |
| The old needle tip moved | Between session 4 and session 5 the bent plastic 22G tip's end moved (+1.05, −2.05) mm and rose at least 1.25 mm (bottom-camera photos at the same head pose and Z). Touch-off dots landed 2 mm beside the spots the camera inspected, so every "no dot" was the camera looking beside the paste (F17). N2 head offset (25.291, −75.926) → (26.031, −77.916) (the XY trim is capped at ±1 mm); then every dot was found. Round ~1 mm dots from offset −3.45 down to −3.85 with no squash: that tip's contact was not found. | `s14_*` to `s24_*`, `s21_xydots.json` |
| Touch-off analysis on real paste (F9 fix) | Refused a real dragged string ("dots touch or are strung together"), as designed. "No dot at the largest gap" while the dots were 2 mm off target. Faint tails made round dots "joined". No contact found on the machine yet. Minor: start 0.30 − 8 × 0.05 = −0.1000000001 refused as below −0.1 (F16). | `s14_touchoff2.json` to `s24_touchoff8.json` |
| New needle (straight metal, fitted ~20:15) | The head's X/Y was lost during the swap (~20 mm, no STOP or reconnect involved, F18); OpenPnP's home fixed it. Bottom-camera parallax (`~/instancelabs/tools/parallax.py`: head ±5 mm at a fixed Z, the tip's image moves f/D px per mm): needle K 67.8 (session 4's tip 65.6), so the tip sits ~2.2 mm higher and the needle Z offset is about −4.8; the tip is (−7.86, +6.98) mm from where the old tip was, so N2 → (18.50, −70.99) (`realcfg-sw-20261030j`, not yet checked with dots). The other reading (K 63.9, from N1's cone rings) was ruled out by a 3-dot test. **No paste came out**: 14.5 auger turns (~8 µL) over two touch-offs left nothing on the board, nothing at the waste and nothing on the tip (bottom-camera photos before and after are the same). The engine sent the same programs as the runs that made dots, so the cause is physical (air, the needle's seal or a blocked tip). | `newneedle/` (`px/`, `n5_L1_h40test.json`, `n7_L2_touchoff.json`, `needle_no_paste.jpg`) |

**The bench's Z frame is not OpenPnP's.** Board 3 sealed at about 5.3 in OpenPnP's frame where the bench measured
4.50–4.55: about +0.8 mm (the bench saw 0.7 mm on 10-28). Since OpenPnP's homes repeat to 0.025 mm, the offset is the
bench's homing against OpenPnP's, not home to home. So:

- Every Z number measured on the bench (board tops, blank heights, `runs/zprobe_*.json`) is about 0.8 mm off in
  OpenPnP's frame. Type board tops measured in OpenPnP's frame (session 3 planned board 3 at 5.35).
- The needle anchor typed in session 2 (`surfaceZ` 5.5, `needleContactRawSum` K 64.50, both bench values) is not valid
  in OpenPnP's frame. On the see-saw N1 and the needle share one motor, so a shift of its zero moves the needle relation
  K by about twice the shift. **Measured in session 4: K 65.60 in OpenPnP's frame** (side camera, needle tip level with
  N1's: 65.6–65.7; recorded at (300, 250) on the blank, surface 6.18: needle contact 3.58, offset −2.60 mm,
  `s5_anchor.json`). That is twice a ~0.55 mm shift, not 0.8 mm: the earlier prediction of about 66.1 was wrong, and
  on the crash side (K 66.1 would put every dot about 0.5 mm lower than commanded). A K that errs low is the safe side:
  the engine then puts the needle above the work (session 4 started from 65.40). That K is the plastic tip's: the
  needle fitted on 10-30 reads K 67.8 (session 5).
- The engine now refuses K (`needleContactRawSum`) after a needle change and while the Z check of this home failed:
  then the frame is recorded with both tips touching (K moves with the needle and with the Z zero).

### Findings and what each became

| | Finding | What it became | On the machine |
|---|---|---|---|
| F1 | The camera look refused 4 of 12 bare spots: glossy blank in the dark zone between the ring light's reflections (89–97 % "foreign") and board 3's scratched mask (54–60 %). It still refused every paste dot, the hole ring and the edge. | Fixed (fix-real1, 2df93ed3d): the spot is compared with a smooth colour field fitted to the ring, the threshold rises with the surface's texture, only solid patches count, and wide patches (0.4 mm across) 22 or more from the field count whatever the texture. All 20 real frames are test fixtures: bare 0–1.3 %, paste, holes and edges 25–87 %. | Session 3: board 3's corner spots read 0.0 %. Still to check: alternates near board 3's corner silkscreen and holes read 17–33 % in the first session-3 run (were they bare?), the blank's purge dots, and paste on a mask as rough as board 3's at (37, 37), where the threshold for small patches is 57 and only the wide patches see paste (a 0.7 mm dot 40 out of the mask reads 12 %, anything within 22 of the mask is not seen, an edge across the spot only 6 %). Each PROBE record's look holds `rawShare`, `threshold` and `wideShare`: send any wrong call with its frame. |
| F2 | Without dots the board probe chose corners 2 mm in from the outline; target (2, 2) and both 2 mm alternates sat on ftp's corner mounting hole. | Fixed (2df93ed3d): without dots the corners sit 8 mm in; without board art a mounting hole is assumed in each corner; alternates step 5 mm and spread in direction (2 mm only where 5 mm is not free); `vacuumProbe.alternates` 4 by default. | Session 3: 5 of 5 first spots made contact. A saved `dispense-config.json` keeps its own `alternates` (session 2's had 2): set 4. With KiCad art Register board keeps the drills and vias out too (the art's keep-outs, merged on `il/rc1`). |
| F3 | The loose, bowed blank: N1 sealed slowly from Z 6.5 to 5.5 and did not let go at 6.0 (the blank springs back with N1). The probe refused, correctly. The blank had also slid ~3 mm since the bench's purge records. | It was F6 (the progressive seal, fixed in bdc9d2d31), not a blank that follows N1. | Session 3: the Z check at the typed anchor on the blank (325, 247.5) refused "N1 did not let go of the surface within 1.5 s". Session 4 (taped blank, the F6 fix): 4 of 4, then 5 of 5 contacts, and the blank did not follow N1. Keep it taped where convenient. |
| F4 | FID1 was accepted 9.24 mm from its expected position with `searchRadiusMm` 8: the radius was measured from the prediction (FID2 and FID3 came first). | Fixed (2df93ed3d): the radius bounds the distance from the expected position (the board's last registration, else its nominal seat). | A board seated farther than the radius from its nominal stops with "FID1 not found within 8.0 mm of X …" (`s4_register_z535_auto.json`: board 3 is 9.2 mm off its nominal). Correct the board X/Y (Use camera X/Y on the board origin), or widen `fiducialCheck.searchRadiusMm` (session 3 used 12: FID1 in 3 iterations, 2 frames). |
| F5 | The needle anchor was typed (5.5, K 64.50), not measured (`POST /api/needle/anchor` at 00:13:19): only so the probe had a needle-frame surface to check its contacts against. | Unchanged; the frame offset above makes it wrong in OpenPnP's frame. | Touch off in OpenPnP's frame before any paste (item 7). |
| F6 | N1 seals build up over Z and time: open at 5.40, 1.78 M at 5.375, sealed at 5.325 / 5.30 on the fine pass, while the quick coarse reads at 5.25 stayed just above the threshold (1.872 vs 1.886 M). The guard "a fine contact must lie below the last open coarse step" rejected the true contact twice: no contact. | Fixed (bdc9d2d31): the guard is gone; a replay test uses the real readings. Likely also session 2's blank. | Session 3 afterwards: 5 of 5 contacts. Item 21 compares the fine contact with a slow manual descent. |
| F7 | Every point ends with a lift to travel Z, then the vent (valve off, pump off). On a surface that follows N1 (the loose blank) the lift carries it up while N1 still holds it. | Not changed. Proposal: vent, wait for the seal to let go, then lift. | Item 22. |
| F8 | Planning again with only the board Z changed (as the I4 refusal asks) dropped the stored registration (Z was part of the seat), so the next Register started from the nominal X/Y, and with F4 board 3 (9.2 mm off) failed NOT_FOUND. | Fixed (c2ad33aaf): the board Z is not part of the seat (X/Y, rotation, side and OpenPnP's transform are). The probe window and I4 use the plan's own board Z. | Plan board 3 again at a new Z: Register board must say "same seat". |
| F9 | Session 4: the touch-off analysis missed real dots that strung or touched and concluded from the one it measured: a contact at +0.425 mm (applied, it would have raised the needle about 0.4 mm and every board dot would have missed). | Fixed in fix round 2 (`il/fixround2` 8a00dd80a: each dot measured at its known spot with the paste strings cut off, paste it cannot measure before the contact refused, the first two dots must both form and agree; 9def31f45: droplets from a needle too high are no reference), merged into `il/rc1` (bffa048ce). Calibrate › Needle touch-off shows such dots as "not measured" with the reason, and a run that recorded nothing with its message (ec19c0f15). | Item 1 above. |
| F10 | Session 4: a board with no fiducials cannot get a 'board' surface: the surface probe refuses 'board' and Register board needs 2 fiducials. Worked around by probing another name ('runarea'), then `PUT /api/surfaces/board {points, planId}`. | Fixed in fix round 2 (`il/fixround2` 0a6b501a2: Register board with probe "always" probes a board without fiducials where it is planned, X/Y not checked, and ties the surface to that seat; 3f449f647: only a board with no fiducial at all), merged into `il/rc1` (bffa048ce). Run › Register offers `Probe only (X/Y as planned)` (ec19c0f15). | Item 3 above. |
| F11 | Session 4: a Z-check dead end: the only data from before the home was a stale 'board' surface with no point of its own home left. | Fixed (final fix round, E1 item 2): the refusal says what to measure again, and the needle frame recorded after the home replaces the touch-off it covers. | Plan with an old surface, home, Check Z: it must name what to measure again, not stop. |
| F12 | Session 4: the clean-pad check (I10) refuses every pad on bare mask (no copper reads 0.00). | By design: switch it off for blank tests (`PUT /api/config {"cleanPadCheck": {"enabled": false}}`). | — |
| F13 | Session 4: the side camera was moved to a wide view (~4.9 px/mm): the bench's tip finder (`kside.py`, preflight's `k_fit`) latched onto the background. K was read by hand. | A bench tool, not the product. | Re-aim the side camera, or teach the finder the wide view. |
| F14 | Session 4: touch-off dots 1.0 mm apart run together (half-turn dots ~1.07 mm wide, plus strings). | Fixed in fix round 2 (`il/fixround2` 8a00dd80a: spacing ≥ dot + 0.4 mm, default 1.6 mm, a smaller pitch refused before anything moves), merged into `il/rc1` (bffa048ce). Calibrate › Needle touch-off has a Pitch field (ec19c0f15). | Item 1 above. |
| F15 | Session 5: STOP during a home hangs the controller: M410 is not answered, the controller answers nothing until a power cycle, and reopening the port does not reset it. | Open: firmware fix on il/safety (not flashed). | Item 2 above. |
| F16 | Session 5: the touch-off's lowest-gap check refuses a ladder that ends at −0.1 because of floating point (0.30 − 8 × 0.05). | Open (minor): compare with a small tolerance. | — |
| F17 | Session 5: a needle tip that moves, or a new needle, changes the landing XY (2 mm here) and Z without any check noticing; the touch-off then reads "no dot" as a height result. | Open: a bottom-camera tip photo at a fixed pose per session (and after a needle change); more than ~0.3 mm off → measure XY and Z again (parallax K, isolated dots). | Item 1 above. |
| F18 | Session 5: the head's X/Y can be lost with no STOP or reconnect (pushed by hand during a needle change): no engine check notices, and the next run would dispense in the wrong place. | Open: a visual home check (homing fiducial) before a run or after a needle change. | — |

Also seen in session 3: a re-register with the typed board top 4.50 (the bench's) was refused, correctly: "I4: N1
touched at Z 5.525 at point 4, 1.025 mm above the board top Z 4.500 (tolerance 1.00)".

## First real-machine session (2026-10-28 02:20–03:05, copper blank only)

The fork ran in real mode on a **copy** of the config (`~/instancelabs/realcfg-sw-20261028`, with the patched
`machine.xml`); the live `~/.openpnp2` is untouched, so items 1 and 2 still apply. Photos and data:
`~/instancelabs/runs/sw_engine_blank/` (`engine_blank_runs.jpg`, `run.json`, `run2.json`, `sw_probe_blank*.txt`).

| item | result |
|---|---|
| 1 (patched `machine.xml`) | Works on the copy: visual homing on the fiducial at 217.081, 200.223. Not installed live yet. |
| 3 (real mode) | Started and homed; web UI and interlocks worked. |
| 4 (Stop) | 1 of 4 cases done: web STOP during a pressure hold vented the valve, switched the pump off and disabled the machine (F9). Still to do: OpenPnP's Stop button, and both during a board run; vent time not measured. |
| 6 (B step count) | **Confirmed.** `Count B` was 1154124 before and after OpenPnP's home (`G92 A0 B0`), so `G92` leaves it alone. Run 2 started at 1177324 = run 1's start + 23200 steps (first purge dot 1600 + 7 × 800 + 20 × 800). Work dots started at 297.9° (298° rounded to a whole step). |
| 7, 8 (frame, probes) | Done on the blank. N1 contacts 5.75–5.80 over X 288–305, Y 242.5–249.5. The blank is **bowed**: 5.75 at Y 254 down to 5.50 at Y 270, so a plane over that span fails the 0.04 mm residual (I4 refused it, correctly). Probe close to where the dots go. |
| 11 (XY trim) | Not settled. Without trim the dots landed (+0.15, −0.04) mm off target; with the bench trim (+0.086, −0.010) still (+0.087, −0.017), scatter 0.084 mm. OpenPnP lands about +0.09 mm further in X than the bench script, likely its one-sided backlash approach. Next: check the backlash settings, or set the trim to about +0.17 X and check again. |
| 13 (engine run) | Two runs on the blank, not a board: 8 purge + 20 dots (status OK, 5,041 DPH dots only) and 8 purge + 16 dots (OK, 4,925 DPH). Dots on bare copper: 1.02–1.28 mm² (mean 1.15, CV 8 %) and 0.95 mm² (CV 12 %). Bare copper wets differently from pads, so compare areas only on a board. The first work dot came out larger (the cross from the purge row at travel Z); watch for it on a board. |

1. **Install the regenerated `machine.xml` patch, with OpenPnP closed.**
   - Why: OpenPnP rewrites `machine.xml` when it exits, so an edit made while it runs is lost. The patch now sets the N2
     head offset to (25.291, −75.926) (was 23.615, −63.947), the homing fiducial to (217.081, 200.223), `M906 B580` on
     connect, `DISABLE_COMMAND` = F9 (`M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`), and `VAC2` at S255 with the
     valve switched off first. That N2 offset was the plastic tip's on 10-28 (the tip had moved by session 5); the
     needle fitted on 10-30 sits at about (18.50, −70.99) (session 5, not yet checked with dots; next session, item 1).
     Set the patch's N2 offset from that check before installing it.
   - How: follow `dist/README.md` (the live file's sha256 must be `5cc805eb…`). After homing, the camera must sit on
     the homing fiducial and the DRO read 217.081, 200.223. Then jog the top camera to FID1 and compare with
     `runs/board_registration.json`.
   - Also check the top camera's units per pixel: 0.01068 × 0.01073 mm/px in `machine.xml`, while the bench used
     0.01074 × 0.01106. The 3 % gap in Y changes dot areas read through OpenPnP by about 3 %, and changes the
     clean-pad box by 2–3 px. Measure it once by moving the camera a known distance (for example 1 mm) over a
     fiducial and counting the pixels.

2. **Install a schema-3 `dispense-config.json`.**
   - Why: `config/dispense-config.json` is still schema 2. It loads, and the motion values are migrated, but it keeps
     `valveSettleMs 1500` where the bench uses 3000.
   - How: set the valve settle to 3000 ms in the web UI settings, or start from the engine defaults (no file).
     `GET /api/config` should show `schemaVersion 3` and `valveSettleMs 3000`.

3. **Start the fork in real mode.**
   - Why: the simulator replaces the serial port and the cameras.
   - First rebuild the dist from a clean `il/rc1` tree: `tools/instancelabs/make_dist.sh` (it refuses uncommitted
     changes and writes `dist/openpnp-il/BUILD-INFO`), and check that `BUILD-INFO` names `il/rc1` and the release
     commit. The folder there now holds `il/integration` 16ce9cc (built 2026-10-27), older than the bench recipe and
     everything above: no dispense profile, phase lock, purge row or start angle, no Register board, Z check, KiCad
     upload or pad calibration (item 2's `schemaVersion 3` check fails on it too).
   - How: run `dist/openpnp-il/openpnp-il.sh` with no other OpenPnP running. Check that:
     - the log has no errors;
     - the serial port is found by its USB serial number;
     - the web UI opens at <http://127.0.0.1:8765/>;
     - the Interlocks list shows I1 as "ok" (OpenPnP's Stop sends the full F9 sequence).
   - Done in sessions 1–3 on config copies (the serial port by its USB serial number, the simulator guard off on the
     Marlin link, live cameras, the web UI). Once items 1 and 2 are installed, do it once more on the live config.

4. **Formal Stop test.**
   - Why: the simulator shows the order of the G-code, but not when the valve really vents. Stock firmware holds
     `M410`'s "ok" for about 1 s, so the valve-off line after it reaches the board about 1 s late.
   - How: test four cases: web STOP and OpenPnP's Stop button, each during a pressure hold and during a board run.
     Each time, check that:
     - the valve vents (listen), and note the time from the press to the vent (expect about 1 s on stock firmware);
     - nothing moves after the press;
     - the pump is off;
     - the screw turns freely by hand (`M18 B`);
     - the machine needs homing again;
     - a stopped run ends `ABORTED`.
   - During a run, two "Error actuating VAC1/VAC2" dialogs are known and expected.

5. **Flash the il/safety firmware (not flashed yet).**
   - Why: it adds the host watchdog (`M990`), so the engine's keepalive switches the pneumatics off if the host dies,
     and it vents inside `M410` instead of 1 s later.
   - How: follow `firmware/FLASH.md` (back up first, BOOT+RESET into DFU, flash), then run `firmware/verify_fw.py`.
     `M115` must report `Cap:HOST_WATCHDOG:1`, and the next run must show `keepaliveActive: true`.

6. **Confirm the B step count.**
   - Why: the screw angle comes from the `Count … B:` field of `M114`. The simulator does not report it (the engine
     uses 0 and warns). `machine.xml`'s stored `reported-axes` already shows `Count … B:799` from this firmware. Still
     unconfirmed: whether `G92 B0` leaves the count alone (the engine sends it at every session start and end). If
     `G92` resets the count, the phase lock is wrong after every session.
   - How: send `M114` and note `Count B`. Send `G92 B0` and `M114` again: the position B must read 0 and `Count B`
     must be unchanged. Do not turn the screw without pressure to test it (it pulls in air). Instead, after the
     first engine session, `Count B` must equal the run's `bCountAtStart` plus the steps the session turned: the
     first purge dot's steps + 167 × 800.
   - Done in the first session (table above): `G92` leaves `Count B` alone, and run 2 started where run 1's steps
     ended.

7. **Record the needle frame and touch off, in OpenPnP's frame.**
   - Why: the needle contact comes from the bench relation "needle contact raw Z = K − N1 contact Z" (K 65.59 on
     10-27, 64.50 on the evening of 10-28, both in the bench's Z frame). Session 4 measured K in OpenPnP's frame with
     the side camera: **65.60** (offset −2.60 mm; above) for the plastic tip. The straight metal needle fitted in
     session 5 reads **K 67.8** by bottom-camera parallax (offset about −4.8; not yet checked with dots). Use the K of
     the needle on the machine; a K that errs low is the safe side (the needle then dispenses above the work). The
     engine refuses K after a needle change and while the Z check of this home failed: then touch the same spot with
     both tips (`needleContactZ`).
   - How, in this order (session 4's: the touch-off takes its dots' Z from the blank surface):
     1. Set the blank place on the live config (`PUT /api/locations/blank {x, y, z}`, N1 touching): with no blank place
        the probe refuses (`PROBE: set the 'blank' place first`).
     2. Probe the blank: `POST /api/surfaces/blank/probe {wait: true}`, and take the plane's Z at the anchor X/Y (c).
     3. Send `POST /api/needle/anchor {"surfaceZ": c, "needleContactRawSum": K, "x": …, "y": …}`.
     4. Run the touch-off on the blank (`POST /api/calibrations/touch-off`, or Calibrate › Needle touch-off) with Apply
        off (`apply: false`), and compare its contact with the after-photos (session 4's showed round dots at
        commanded gaps 0.00–0.20 and strings from 0.25). Use it (`Use for the needle offset`) only if they agree;
        else keep the frame's offset (`POST /api/needle/offset` with it, −2.60 for session 4's tip, or `Confirm frame
        offset` on the Needle Z frame card). Never record K again from a touch-off result.
        - Start at a gap of 0.2 mm or less (the default 0.125 is fine): the session-4 ladder started at 0.45 mm and
          its first dots were droplets (0.37–0.43 mm), which the touch-off now refuses as its reference (fix round 2).
          The dots stand 1.6 mm apart (the default, or the card's Pitch field); a pitch under the dot + 0.4 mm is
          refused.
        - Confirm the string cut (0.45 × the dot of the dose, tuned on one real ladder): each dot is measured at its
          spot with the strings cut off. Rows "not measured" in Calibrate › Needle touch-off (`analysis.dotStates`,
          `dotNotes`: "joined to the dot at gap …") are dots the cut did not separate; one before the contact refuses
          the run ("dots touch or are strung together …"). Note the dot sizes and any joined pairs.
   - Every Z datum now carries the home it was measured under (review 1): after the next home one Z check touch is
     enough again.

8. **Probe the board and the blank.**
   - Why: every dot takes its Z from the probed planes. A board surface is tied to the registration it was probed
     on, so a re-seat needs new probes (I4).
   - How, for the board: Register board (Run › Register, or `POST /api/plan/{id}/register {wait: true}`) finds the
     fiducials, probes five N1 points (board 3: (8, 8), (67, 8), (67, 67), (8, 67), (37.5, 37.5)) and plans again.
     Done on board 3 in session 3 (5 of 5, residual 0.015 mm). By hand stays possible (`PUT /api/surfaces/board` with
     the `planId`, then plan again). A board without fiducials: `Probe only (X/Y as planned)` on Run › Register
     (`POST /api/plan/{id}/register {probe: "always"}` on a plan without a registration): N1 probes it where it is
     planned (F10; next session, item 3).
   - How, for the blank: `POST /api/surfaces/blank/probe {wait: true}` (item 29), or at least 3 N1 points by hand
     around the purge row (largest residual ≤ 0.04 mm) with `PUT /api/surfaces/blank`. Done in session 4: 4 of 4, then
     5 of 5 contacts, residual 0.006 and 0.011 mm; the blank did not follow N1.

9. **Set up the purge row.**
   - Why: each board uses 8 fresh spots. The bench row (y 237.5, x 292–327, pitch 2.5) is already used up to x 327
     (`runs/purge_spots.json`).
   - How: wipe the blank, then:
     - `PUT /api/config {"purgeRow": {"x0Mm": 292, "y0Mm": 237.5, "pitchXMm": 2.5, "spots": 15}}`;
     - `POST /api/purge-row/reset`.
   - Status should show 15 spots left (1 board, since each board uses 8).

10. **Confirm or measure the start angle.**
    - Why: with half turns, the dots alternate between starting at 298° and 118°, and the two halves still differ by
      about 12 %. Where to split the turn is still open. The angle is also lost when the controller is
      power-cycled.
    - How: after every connect, confirm it (`POST /api/screw/start-angle`) if the controller was not power-cycled.
      Otherwise measure it again. To settle the split point, lay dot pairs on the blank at a few start angles, and
      keep the angle whose two halves match best (by area or by weight).

11. **Needle XY trim.**
    - Why: `disp.py` now uses the needle offset (25.377, −75.936). That is the `machine.xml` offset plus the p18
      landing correction (0.086, −0.010).
    - How: if the bench confirms it, send
      `PUT /api/config {"needleXyTrim": {"dxMm": 0.086, "dyMm": -0.010, "note": "p18_board1 landings"}}`.
      Then check the after-images of a board: the mean dot-to-pad offset should be under about 0.03 mm.

12. **Check the clean-pad guard against the bench.**
    - Why: OpenPnP's top-camera frames are flipped in X and Y and use a different mm-per-pixel than the bench's
      frames, and the guard was tuned on the bench's frames.
    - How: capture clean and dirty pads through OpenPnP (`POST /api/plan/{id}/clean-check`) and through `disp.py`.
      Run `vision.PadCleanTool` on both sets. For each pad, the two bright shares should agree within about 0.03,
      and every clean/dirty verdict must match.
    - Also time the check. It visits all 160 pads and waits out the top camera's settle at each one (FixedTime
      1000 ms in `machine.xml`). Expect about 3 minutes per board before the pressure goes on, which is not in the
      DPH. A shorter settle may be enough.

13. **First engine board run against the bench.**
    - Why: the bench `p18_board1` result is the target: 160/160 pads, mean dot area 0.714 mm², CV 13 %, 5,490 DPH.
    - Prediction from the simulated stream: 109.4 s for the 160 dots, i.e. 5,264 DPH, by the same model that
      predicts the bench run 2.7 % too slow (107.8 s against the measured 104.9 s). So expect about 5,400 DPH.
      Two things make the engine slightly slower than `disp.py`:
      - it crosses from the purge row at travel Z (raw 31.5, not the bench's 49.5): +0.7 s per board;
      - OpenPnP's backlash approach adds a second, short XY move per dot: +1.5 s per board.
    - How:
      1. Set the blank place, probe the blank and record the needle frame (item 7), and the purge row (item 9).
      2. Raise chip-0603 to the gate: every seeded pad type is `not-started`, and `minPadTypeStatus` is
         `characterized`, so the plan blocks every pad until then. Session 4 raised it from the bench's evidence
         (`PUT /api/pad-types/chip-0603 {"status": "characterized", "note": …}`); or calibrate it (Profile › Needs a
         profile › Calibrate automatically, item 53).
      3. Set the regulator to 18 psi.
      4. Make sure the pads and the purge spots are clean, and leave the clean-pad check on (it reads copper pads; on
         bare mask it refuses every pad, F12).
      5. Run the board.
      6. Compare the dot area and CV (after-images), and the run's `dphMotion`, with the numbers above. Session 4's runs
         on the blank (12 and 6 dots) are no board: 4,737 dots per hour of dot motion over 12 dots.

## Automatic registration (`AUTO_REGISTER_DESIGN.md` §11)

Items 14 and 16 were to come first, before anyone relied on the automatic home (§16 decision 8). The automatic home
has since run on the machine (sessions 2 to 5); 16 is measured, and 14 was tried in session 5: a STOP during a home
hangs the controller (F15).

14. **STOP during homing, and G28's axis order.**
    - Why: G28 is sent with an infinite timeout. Whether `M410` interrupts it on the Lumen firmware is unknown. The
      engine's home disconnects the drivers on a STOP (§2.3); the simulator shows that, the machine has not.
    - How: with the head clear, `POST /api/machine/home` (or Home in the strip), STOP while G28 runs. Check that motion
      stops at once, the log shows the drivers disconnected, nothing moves after the press, and the next Start asks
      for a home ("a stop came during the last home"). Watch which axis G28 homes first (the engine raises N1 before
      G28 either way).
    - Session 5 (F15): a web STOP 4 s into a home stopped X/Y within about 0.3 s, but `M410` got no answer, the web
      home ended at +60 s and the controller answered nothing (not even `M115`) until a power cycle. Until the
      il/safety firmware fix is flashed, do not STOP during a home, and run this test last in a session (the
      recovery is a power cycle by hand).
15. **The home duration.**
    - Measured: 21–23 s (above); 21–22 s for each of the 8 homes of session 5. The engine times every home it starts
      and uses the last one as its estimate, but only in memory: after OpenPnP restarts the estimate is 25 s again
      until the first home.
    - How: nothing to do; if the 25 s matters, compare `status.machine.home.estimateS` before and after a home.
16. **Z repeatability across homes.**
    - Measured: 0.025 mm on 5 points across one OpenPnP home (one fine step). The Z check's tolerance is 0.03 mm, so one
      fine step passes and two do not. The 0.7–0.8 mm was the bench's homing against OpenPnP's. Session 4: three Z
      checks after homes, +0.025, 0.000 and +0.025 mm, all passed. Session 5: against session 4's stored points the
      check read 0.13 mm (refused twice), then 0.08–0.15 mm after a power cycle; after the blank was probed again,
      0.025, 0.000 and 0.025 mm (pass).
    - How: over a day, note every Z check's delta (`run.zCheck` of each board run, `POST /api/machine/z-check`). If
      checks fail after normal homes, every run after a STOP will ask for the needle frame again.
17. **Fiducials through OpenPnP's capture path.**
    - Seen: works on board 3 (format 1920 × 1080, flips, light; 3 of 3 found and centred; fit scale equal to the
      bench's).
    - Still to check: after a fresh home, register a board at a seat the bench also registered and compare the
      fiducials with `runs/board_registration.json` (≤ 0.05 mm).
18. **Camera scale** 0.01068 × 0.01073 mm/px in `machine.xml` against the bench's 0.01074 × 0.01106.
    - Why: the centring loop absorbs it, but the mm area window and the search tiles are 3 % off in Y.
    - How: as item 1 (move the camera a known distance over a fiducial and count the pixels).
19. **Same seat after a STOP and a home.**
    - Seen across a plain OpenPnP home: same seat (fiducials 0.01–0.03 mm), the probed points +0.025 mm
      (`s4_register_after_home.json`); a same-seat check's 3 re-touched points agreed within +0.025 mm
      (`s4_register_r12_2.json`, no home in between).
    - Still to check: Register, STOP during the probe, Start, Register: an untouched board must read "same seat" and
      its 3 points must agree within 0.03 mm.
20. **Open air.**
    - Seen: −1.63 M to −1.67 M counts on board 3 (window −1.90 M to −1.20 M).
    - Still to check: the readings with no tip, a blocked tip, the N1 pump off (the simulator assumes 0) and a 0603 on
      the tip. That last one confirms the relative limits (50 k, 60 k counts) against the ≥ 80 k of
      `tools/place.py:37`.
21. **Seal and release.**
    - Seen: seals build up over Z and time (F6); 14 of the 15 board-3 contacts needed the one-step-higher release;
      contacts 5.25–5.55. The seal drop on mask was well past the 15 % threshold at contact.
    - Still to check: at one spot, compare the fine contact with a slow manual descent (is the two-in-a-row fine rule
      unbiased?); the drop on bare copper; whether 1.5 s is always enough to let go on a held surface.
22. **Vent before lift on a surface that follows N1 (F7).**
    - Why: each point ends `G1 Z<travel>`, `M400`, `M107 P1`, `M107 P0`: N1 lifts while its vacuum still holds. A loose
      or springy surface (the blank) comes up with it, and the "did not let go" refusal lifts it too.
    - Proposal (not built): at the end of a point, valve off, pump off, wait until the reading is back within half the
      drop of the baseline (at most 1.5 s), then lift.
    - How to verify: on the held-down blank and on board 3, log the readings after `M107 P1` at the contact Z (how long
      until open air), and look at the surface with the camera before and after the lift. Then decide whether the
      engine changes.
23. **VAC1 sensor mode.** The bench writes `M260 B27` (`tools/probe.py:49`), OpenPnP's actuator read writes `M260 B10`,
    and the engine sends the bench init at every probe task. Check that an OpenPnP pick after a probe still reads VAC1
    sensibly (a part on N1 against open air).
24. **The camera look on real frames** (F1). See the F1 row: the alternates near board 3's corners, the blank's purge
    dots, silkscreen and vias on other boards, the blank's edge and tape. Count the false "not bare" spots per board.
25. **Pushing to the floor.** N1 goes at most 1.5 mm below the higher of the board Z and the needle-frame surface, and
    at most 1.0 mm below the task's first contact. Confirm N1's compliance makes a push to that floor harmless (watch
    one no-contact point, e.g. over a hole).
26. **Probe targets on real boards.** F2 is fixed; board 3 needed no alternate. Count how often alternates are needed
    on other boards with `padKeepOutMm` 1.4 and the N045 tip, with and without KiCad art.
27. **Flatness refusals.** Board 3, not clamped: residual 0.015–0.035 mm (limit 0.04). The bench's 9-point
    `runs/zmap.json` was 0.048. Expect occasional "not flat enough" on boards that are not clamped; note how often.
28. **Time per probe point and per registration.** Estimates: 17 s per point, 5 s per fiducial; ≈ 2:05 with a home
    (25 s + 3 × 5 s + 5 × 17 s), ≈ 1:30 for a same seat with a home. Measure from the `runs` rows of kind
    `board-registration` (start and end) and correct `PROBE_POINT_ESTIMATE_S` / `FIDUCIAL_ESTIMATE_S` if far off.
29. **The blank around the purge row** (F3).
    - Why: it is loose, bowed (5.75 at Y 254 down to 5.50 at Y 270 on 10-28) and slid ~3 mm since the bench's purge
      records.
    - Done in session 4 (taped): 4 of 4 contacts (Z 6.15–6.20, residual 0.006 mm), later 5 of 5 (Z 6.20–6.30, residual
      0.011 mm); the blank did not follow N1 (F3 was F6). The plane was within 0.04 mm there.
    - Still to check on another day: `POST /api/surfaces/blank/probe {wait: true}` (targets 2 mm either side of the
      purge row): are the spots bare, and does the blank stay flat within 0.04 mm as the purge row moves along it?
      Session 5: flat to 0.010 mm at y 226–236 and 0.015 mm at y 264.5–267.5; a strip at y 273–276 refused (a point
      0.080 mm off the plane: the loose blank bows 0.1–0.2 mm, the size of a touch-off step).
30. **STOP during a probe on the real link.** Check the vent order and that no Z step follows `M410` (log). N1 stays
    where `M410` stopped it, vented (`status.machine.home.n1Low`); the next home raises it Z-only to the travel Z
    (31.5) before G28.
31. **Job boards.** Our registration replaces OpenPnP's own transform on the planning copy (never written back).
    Register a job board and compare with OpenPnP's fiducial check on the same seat (within 0.05 mm).
32. **Reconnect after a STOP during homing** (the drivers are disconnected). Does reopening the serial port reset the
    controller? If it does, the B step count is lost and I11 asks for the start angle. Session 5: after a STOP during a
    home, reopening the port did not reset it; it answered nothing until a power cycle (F15).

## Board import (`BOARD_IMPORT_DESIGN.md` §7)

33. **Imported ftp dots on the LEDs.** An imported ftp board plans its LED dots 0.0375 mm further in than the pad-type
    plans of `ftp.board.xml` (true KiCad pads). Check the dots on the LEDs with the top camera after a run.
34. **Which FID1 is right.** The import uses KiCad's FID1; `ftp.board.xml` stays as it is. After Register board on
    ftp, image the dots on the low rows (board y ≈ 17–22, R19 and its row) planned from `ftp.board.xml` and from the
    imported board, both registered to the same measured fiducials. They differ by up to 0.25 mm; the set that sits
    on the pads names the right FID1. Until then, do not mix or compare plans of the two files.
35. **The drawing over the camera.** After Register board, the drawn copper must overlay the camera image within the
    registration residual (a visual check of the art transform on a real seat).
36. **"Camera is on FID1"** must put the plan inside the fiducial search radius on a real seat (a re-seat of several
    mm; after F4 the radius is measured from the expected position).
37. **Nano and mobo apertures** (−0.0762 mm margins, QFN windows) against a real stencil's openings, before a pad type
    is characterised for them.

## Profile calibration (`PROFILE_CALIBRATION_DESIGN.md` §15)

Built: the profile check and its suggestions, the per-type doses in the plan, Calibrate automatically (the ladder on
the blank, the weigh dots on two coupons, one operator step at the balance, the decision, the plan made again), the
by-hand path and their web UI; the SIM acceptance passes on them. Not in this PR: the test-board ladder (PC §20
decision 8, `pc-ladder`, a follow-up), so `validated` stays on the Validate guide. These items need the machine, the
job, or a test board.

38. **Tip OD** of the AD22TTA-B (bottom camera, side camera): the 0.72 mm assumption (`TipSpec.odMm`) behind the
    same-pad dot pitch.
39. **Quarter turns at 18 psi** on the blank and on coupons: transfer, threads at the real hop, CV, the 4-phase
    amplitude; whether an eighth (200 steps) would transfer (not offered).
40. **Mass per step** across 400 / 800 / 1600 steps by weight, and the 3 × 100 repeatability.
41. **The footprint law** at quarter and full turns, by camera on the blank (the band 0.83–1.00 mm decides SOT-23,
    SOIC-8 and 1.27 mm headers).
42. **Block switch.** Are 1 phase dot + 4 flush dots enough between doses? Count the `Count B` lines and the first
    dots of each block. In the SIM a switch from half to quarter turns made the phase dot a whole turn (1600 steps:
    the screw already rested at the start angle and a phase dot turns at least 20°): about 2.1 mg on one purge spot.
43. **Weigh coupons.** Where two 40 × 30 mm coupons lie on the bed, N1's vacuum touch on them, the camera look's margin
    check, flux evaporation over 15 min, the wipe's residue.
44. **Balance data port** (U.S. Solid 110 g × 0.001 g): RS-232 or USB, protocol; a check weight at the first weighing
    of a session (optional).
45. **0805 as one full turn against two half turns** after reflow, and the needle-clearance pitch (0.94 mm) against
    the bench's "~0.9 mm apart": a test board on mobo-class parts.
46. **DotInspector circularity on real half-turn dots** (0.42–0.74, `runs/p18_half/meas.txt`) against the 0.35 string
    threshold, and the corridor thread test on real frames.
47. **The calibration area on the real blank:** where it goes (unset it is 40 × 20 mm at the blank place, moved off the
    purge row; the SIM acceptance used 75 × 14 mm 3 mm above its purge row, and a two-dose job took 9.1 mm of it),
    flatness with the purge row in one plane (the blank is bowed and loose, F3), the bare check of every spot,
    calibrations per wipe.
48. **Image tiles:** dot diameters near the frame edge against the centre (scale error ±3 % in Y, item 18).
49. **Freshness window** (8 h, `doseRecordMaxAgeH`): does mass per step drift with paste age or barrel level?
50. **Sustained feed** at 18 psi (2.56 mg/s seen with one-turn dots): where starving begins.
51. **The coupon surface probe** without the frame check (`AUTO_REGISTER_DESIGN.md` §16 decision 1's frame rule does
    not apply to it).
52. **The fine-pitch cap** (0.25 mm print) and the −25 %/+10 % band on SOIC-8, headers and PicoBlade after reflow
    (bridges counted).
53. **A full pad calibration on the machine** before any type counts: the coupon look and N1 touch on real coupons,
    the ladder's camera checks on the real blank, the operator step (weigh, wipe with isopropyl alcohol, dry, weigh
    again within 15 min), the decision on measured numbers, then a board run of the new types checked after reflow.
    The SIM numbers (`il_sim_acceptance.py padcal`: 2.30 mg per turn by design) are never evidence on the machine.
54. **The Arduino Nano (the next job), top side:** 55 paste openings; 40 cannot be dispensed with the 22G tip (VQFN-32
    perimeter, USB data, resonator) and 5 more sit on those parts (USB shell tabs, the VQFN exposed pad: not pasted
    unless "paste the dispensable pads of this part anyway" is set), so those parts are hand-soldered or stencilled.
    Two sizes need a profile: SW_SPST (2 half-turn dots per pad: the SIM characterizes it) and LED_0805 (1.05 × 1.05
    mm). No allowed dose brings the LED within −25 %/+10 % of its 1.58 mg (one turn +33 %, half a turn −33 % at 2.11 mg
    per turn), so Calibrate automatically leaves it `provisional`: a run refuses the board while its 8 pads are blocked
    (or skips them with Setup › Settings › Refuse flagged pads off). Decide on a reflowed test board (one turn against
    half a turn) and raise the type by hand, or wait for the test-board ladder (`pc-ladder`, a follow-up).

## Tip setups (`PROFILE_CALIBRATION_DESIGN.md` §22.10)

Built on `il/tip-setups`: a library of tip setups with one mounted (the seed is the 22G bent taper in use), pad
profiles and the dispense profile per setup, mounting as a needle change, where each tip lands as a measured value
(Check where it lands), Measure its dots, a "calibrated for" label and pictures on every calibration record, and the
web UI for them (`UI_DESIGN.md` §8.6). The SIM acceptance `il_sim_acceptance.py tips` runs it end to end on the SIM
paste, cameras and balance, with a SIM 25G straight that lays smaller dots and lands 2.4, −0.4 mm off; its numbers
are the simulator's by design and never evidence on the machine. Item numbers continue the list; T1–T14 are §22.10's.

55. **T1 The bottom-camera picture at travel height** (N2 Z 31.5): focus, scale and glare of the ring light. Does it
    tell the bent 22G from a straight 25G from below? If not, the phone picture is the record and the caption keeps
    `may be soft`. No paste on the bottom camera's window after 10 pictures taken right after runs (a needle carries
    paste at its end); if there is, the task must prime at the waste first.
56. **T2 Where each tip lands against the 22G bent taper** (mm): the straight 25G, the 22G straight taper, and a 45°
    bent 25G with its bend pointed as the 22G's. Within the ±6 mm search, or does a first check need `wide` (±9 mm, a
    taller calibration area)? Record the 22G's `bendToward` on the way (Setup › Dispenser & tip › Bend).
57. **T3 The landing check on the real blank:** the locator dot found among the blank's texture, the band's 20 mm for
    a first check and 12 mm for a remount, the purge dots of a needle whose landing is known only from the locator,
    and where the prime of a new needle lands at the waste (±6 mm around the waste spot must be waste).
58. **T4 The needle frame of each new tip** by Both tips touch, against session 4's side-camera K; then the touch-off
    after the landing check on a tip that was never on. (Since `il/rc1`, From K is refused after every needle change,
    so a remounted tip is framed with both tips too.)
59. **T5 Needle length and travel height:** the end of a straight 12.7 mm tube against the tapered 22G's at travel Z
    (side camera); clearance over feeders and boards; the length difference behind the "up to about 10 mm" the design
    uses.
60. **T6 Mass per step of the 25G, 27G and 30G at 18 psi:** how far below the 22G's 1.32 µg/step (slip in the auger
    against the tube's resistance, about 1/r^1.8 and its length)? Measure its dots weighs it. A wider tip (21G, 18G)
    against the × 1.5 the feed check (R7) uses before it is weighed.
61. **T7 Pressure for fine tips:** does 18 psi feed a 27G or 30G (the DV-5005 allows 21.7 psi)? Does the 30G clog (a
    0.15 mm bore is about 6 T5 balls of 25 µm across)?
62. **T8 The smallest dot per tip:** the quarter turn's thread rate at the hop with a 25G (§1.5's rule was the 22G's).
    In the SIM the 25G's quarter-turn dots were too small to find, so its setups use a half turn as the smallest dot;
    set `minDotSteps` (Edit…) from what the machine shows.
63. **T9 OD of each tip** (bottom or side camera) → `odSource measured`, which moves the same-pad pitch.
64. **T10 Start angle across tip changes:** the ripple peak (ψ ≈ 299°) stays with the screw (a mount keeps the live
    angle).
65. **T11 Photos in practice:** a phone picture copied to the PC (orientation, HEIC from an iPhone, size), disk use
    per month.
66. **T12 A remount of the same 22G model** ("I changed the needle") with its bend pointed as recorded: how far does
    the landing move (0.1–0.4 mm expected for a straight tip, more for a bent one)? Is `Keep where it lands` ever
    right for a straight one?
67. **T13 Is this the tip you think it is?** The 1.00 mm frame and 1.0 mm landing thresholds against a deliberately
    wrong tip (25G on, 22G mounted): both asked, and the control's band. (In the SIM the landing check asked and the
    control named another tip.)
68. **T14 A frame close to N1's:** does any tip's |offset| fall below 2.1 mm (I4's offset rule)?

## Software limits to keep in mind on the machine (review 1 and the final fix round)

From the first review of the merged backend and the final fix round (E1 engine safety, E2 engine correctness, merged on
`il/rc1`). Most are fixed; what is left is said in each. None changes what the items above ask, but each can explain a
surprise on the machine.

- **Board far below the needle anchor** (fixed in the final round). Planning accepts a board top up to 2.0 mm from
  the anchor, but the board probe's floor is 1.5 mm below the higher of the two. Register board now refuses before
  anything moves when that floor lies at or above the board top ("… it cannot reach that height …"): a board 1.5 mm
  or more under the anchor is probed by hand. Between about 1.25 and 1.5 mm under it the probe reaches only a board
  that sits at or above its typed Z.
- **The look before an N1 touch** takes its mm/px from the camera's default Z, not the surface Z: the 1.0 mm disc is
  slightly mis-scaled on the real camera.
- **Tasks dropped behind a failing OpenPnP task** (fixed in the final round for the web home and every engine
  endpoint). A machine task that throws makes OpenPnP drop every task queued behind it. Every engine endpoint now
  refuses while OpenPnP is busy (the web home too), and the web home returns its failure instead of throwing it. A
  task of OpenPnP's own (its Home or jog buttons) that fails can still drop an engine task queued in the moment between
  the check and the queue: do not press OpenPnP's own buttons while an engine task is starting. Such a task no longer
  keeps the engine busy for good: it ends at the next STOP or engine request (ERROR, nothing sent).
- **Pressure hold race** (fixed in the final round). Every engine task now looks at the hold again on the machine
  thread, prime, the test dot, shots, the dot ladder, the touch-off and the clean check included.
- **After a failed Z check** (fixed in the final round) the remedy works under the same home, in this order: record
  the needle frame again with both tips (K is refused then: it moves by twice a zero shift), probe the blank, take
  each place's Z again with N1 (the engine refuses a place set before the home where its Z is used: prime, the test
  dot, shots, the touch-off's prime at the waste, a coupon), touch off, register the board (the web UI's Z check
  blocker offers each step in turn; the SIM browser test walks it).
- **A STOP or failure during an engine home** (fixed in the final round) no longer leaves a stale start time behind.
  A web STOP during the web home still answers `homing failed: machine disabled: User requested stop.` (OpenPnP's
  disable listener writes its own reason), not "STOP pressed in the web UI".
- **The pad calibration** waits for OpenPnP to be idle before each engine step (at most 10 s, then it stops with
  `OpenPnP is busy …`): still do not use OpenPnP's own Home or jog buttons while a calibration runs.
- **Fixed in the final fix round (E2):** the fiducial search frames tile the radius around the expected position (the
  first frame of a re-seated board's later fiducials may then hold the mark off-centre: one more centring look); the
  fit against the previous registration is compared in the machine frame (re-seat a board about 10° turned once to
  confirm it is accepted); two pads of one name are no split pad; a two-turn profile is refused by the settings; a test
  run with fewer dots lays whole pads; no run or probe starts while an upload reads its file.
- **Tip setups, left open** (`PROFILE_CALIBRATION_DESIGN.md` §22 "As built"): the touch-off still judges every tip's
  dots by the 22G bent taper's model (its steady-dot size and the 60 % reference rule), so on a finer tip it may refuse
  its conclusion: read the contact from the photos, as item 1 says; an Undo of a mount within its 2 min puts back the
  old recipe, so a recipe edit made in those 2 min is lost; the landing check's start card does not check the needle
  frame first (the job then fails at its first step with the I4 text); a calibration decided from an earlier job's
  fresh dots lays none and links no result photo of its own (the card says so).
- **Left from E2:** a run started in the same instant an upload takes the import pipeline (well under a second) can
  still overlap the parse: start runs with the upload dialog closed. A two-fiducial board near 45° re-seated by more
  than about 13° can still be refused against its last registration (a similarity fit cannot separate the machine's
  0.64 % X/Y anisotropy). The off-board check uses the outline's box: a part in a notch of a non-rectangular board is
  not flagged. The clean-pad check keys its box by pad id, so two same-named pads of different sizes share one box. A
  `dispense-config.json` still holding 200 or 3200 steps per dot refuses runs (CONFIG) until the profile is set to 400,
  800 or 1600 (the bench uses 800).
