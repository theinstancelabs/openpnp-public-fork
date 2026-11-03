# Missing paste profiles: flag, suggest, calibrate automatically or by hand — design spec

Status: design for implementation, **revision 2** (after the physics-safety and ux-test-slices critiques; §19 logs every
issue). Branch `il/bench-sync-autoreg` (worktree `~/instancelabs/wt/bench-sync-autoreg`, read at f7a8bd712: AR wave 1
merged, AR wave 2 and every BI slice not yet). Brief: `/home/lumen/.t3/projects/openpnp-revamp/tasks/calibration-brief.md`;
hard rules: `tasks/autoreg-brief.md` ("Hard rules"). **Simulation only** while this is built: no serial port, no
`/dev/video*`, no real-mode OpenPnP. The two designs built in parallel on the same branch are cited as `AR`
(`docs/instancelabs/AUTO_REGISTER_DESIGN.md`, rev 2 with §16) and `BI` (`docs/instancelabs/BOARD_IMPORT_DESIGN.md`, rev 2
with §13). This design reuses their task framework (AR §5), API conventions (AR §7), UI patterns (AR §8), SIM pieces
(AR §9), the upload preview (BI §3.2, §5.8), the plan additions (BI §4.5), the lite BoardView (BI §5.2) and the pad
inspector (BI §5.7).

Path abbreviations (as AR and BI): `D/` dispense, `A/` api, `V/` vision, `S/` sim, `O/` OpenPnP, `W/` web, `B/` board,
`K/` kicad, `T/` tests (`src/test/java/org/openpnp/instancelabs/`), `PW` = `src/test/web/webui-sim.playwright.cjs`,
`PT/` = `/home/lumen/.t3/projects/openpnp-revamp/tasks/`, `tools/` = `~/instancelabs/tools/`, `runs/` =
`~/instancelabs/runs/`, `mem:` = `/home/lumen/.claude/projects/-home-lumen--t3-projects-openpnp-revamp/memory/`. New in
this design:

- `P/` = `src/main/java/org/openpnp/instancelabs/padprofile/` (new package, pure Java: physics, statistics, profile
  check, suggestion, decision; no OpenPnP, DB or HTTP types).
- `R/` = `src/test/resources/padprofile/` (fixtures of this design).

Every claim about the code carries `file:line`; bench numbers name their file. The numbers about the KiCad fixtures
(`ftp`, `mobo`, `nano`, the BI fixture sources, BOARD_IMPORT_DESIGN.md:24-28) and the statistics' pass rates come from
throw-away scripts (python `-I`, `/tmp/pc_rev/model.py`, `stats.py`, `proto4.py`, `proto4both.py`; no repository code)
that implement §1, §4 and §7.4 literally; the tests of §14 assert the same values from the Java code.

---

## 0. What gets built

1. **A stated paste model** (§1), fitted on steady-state dots only: mass per screw step at 18 psi, the screw-phase
   rules (blocks, whole pads, at least half a turn per pad), the amount per pad from a per-family anchor (the
   validated 0603 half turn for chips), a two-regime footprint law with a band, the smallest dose and why, bridging,
   overhang and needle-clearance limits, the validity of a dose record, and the predictions the calibration tests.
2. **The profile check** (§3): engine-side, on every plan, on a board file before planning, and in the KiCad upload
   preview. Pads are grouped by package and paste opening; each group gets one reason (not dispensable with this tip,
   no pad size, no pad type, below the gate, outside what its type was calibrated for). Blocking stays the existing
   gate (`NO_PAD_TYPE`, `STATUS_BELOW_MIN`, `NO_GEOMETRY`) plus the run interlocks.
3. **The suggestion** (§4): deterministic, explained with numbers: an existing pad type of the same size (one click:
   mapping and plan again), otherwise a new pad type (mapping, no regex) with a dot plan that keeps the screw phase,
   keeps the needle off earlier dots and does not bridge, the predicted mg per pad and dot size, and a confidence.
4. **A per-type dot dose** (§5, §6): {quarter, half, one turn}; dots of one dose run as a **block**; every block of a
   partial turn starts at the profile's start angle after a phase dot and flush dots; each pad's dots are consecutive
   and add up to at least half a turn; the validator checks every B amount, every block start and caps every purge
   and phase dot; an interlock checks the paste demand against what the screw was seen to sustain.
5. **Calibrate automatically** (§7): one job, unattended until a single operator step at the end. Home if needed and Z
   check, blank surface if needed, the two weigh coupons (camera look, then a probe), an optical ladder on the blank
   that always starts with the profile dose as a control, weigh dots on the coupons (imaged too), park, then **one**
   operator step (weigh each coupon, wipe it, weigh it again), the decision from every fresh record of each dose, a
   new pad-type version (`characterized` only if every check passes), plan again, a result card with camera crops.
   Before starting it says the time, paste, blank area, coupons and when you are needed.
6. **I'll do it by hand** (§8): the Calibrate guide, prefilled; its ladder and weigh steps run the same phase-locked
   engine steps; the limits are shown; raising an own-dose type to `characterized` needs the same evidence.
7. **Test-board ladder**: deferred to a follow-up (§9); `validated` stays on the existing Validate guide.
8. **Balance** (§10): an interface with manual entry (default) and a SIM balance; no serial driver.
9. **SIM** (§11): a paste model whose footprint law, noise and ripple differ on purpose from §1, with threads across
   hops, needle gap, feed limit, idle transient and coupons; the deposits drawn for DotInspector; scene variants as
   negative controls; all behind `SimGuard`.

---

## 1. Physics model

### 1.1 The screw is a flooded positive-displacement pump at 18 psi

- **Model.** With the barrel at 18 psi the Vieweg screw is full ("flooded"): a forward turn displaces a fixed volume
  and a held screw does not drool (mem:lumen-calibration-state "At 18 psi the screw is flooded (holds dry …)";
  PT/todo.md:92 "holds dry (0/0/0) … The screw is FLOODED at 18 psi"). Mass per dot = `M_rev × netSteps / 1600`, the
  net turn being forward minus the 10° suck-back (D/DispenseProfile.java:39-42; 1600 steps per revolution,
  D/DispenseConfig.java:136).
- **Number.** `M_rev = 2.11 mg/rev`: 169 mg weighed on 160 half-turn pads = 1.056 mg per half turn
  (`runs/p18_board1.json`, PT/todo.md:98; `tools/lumen.py:95-97`), i.e. **1.32 µg per motor step**. Density 3.76
  mg/mm³ (GC50 T5 84 %, PT/lessons.md:12; the engine uses 3.7, D/DispenseConfig.java:176), so 0.56 mm³/rev. The seed
  calibration's q 0.47 mm³/rev (D/DispenseDb.java:181) is 16 % low; nothing uses it for a dose
  (D/DispensePlanner.java:9-15).
- **Feed.** The highest paste demand the screw was seen to sustain at 18 psi is **2.56 mg/s**: 36 one-turn dots at
  180 rpm, dwell 250 ms, in ≈ 29.7 s, flat (ratio 0.97) (`runs/p18_blank/timing.json`: 39 spots in 38.7 s including
  three 3 s holds; PT/todo.md:92). Production half turns ask 1.61 mg/s (5,490 DPH × 1.056 mg, PT/todo.md:96). A screw
  asked for more than the paste feeds pulls in air, "30-50 turns to recover" (D/DispenseProfile.java:60-61).
- **Confidence: medium** for half turns at 18 psi (one weighing; the 3 × 100-dot repeatability weighing is open,
  PT/todo.md:236). **Low** for other doses: the full turn (2.11 mg) and the sixth (0.35 mg) are inferred, never
  weighed. At 12 psi linearity failed (54 % more turn gave 12 % more paste, PT/lessons.md:9), and the window is
  narrow: 16 psi starves, 20 psi drools (D/DispenseProfile.java:33-37; PT/todo.md:88). The mass model is valid only at
  the profile's pressure and recipe, and only when a weighing at that recipe is fresh and confirmed (§1.7, §7.5).

### 1.2 The screw phase and the phase rules

- **Measured.** Dot size cycles once per screw revolution: ±56 % in area at 12 psi (`tools/lumen.py:89-94`); net-zero
  turns give no dots (PT/lessons.md:17); one whole revolution per dot removed the swing (24/24, CV 11 %); after a power
  cycle the peak moved 186° → ~306° (mem:lumen-calibration-state, `runs/p12_phaselock`). At 18 psi the swing is weak:
  half turns from 237°/57° differ 1.17 vs 1.03 mm², from 147°/327° 1.08 vs 1.01 mm² on the blank (PT/todo.md:94); at
  the chosen 298°/118° starts the board split was 0.756 vs 0.667 mm² (on pads, where the area saturates,
  PT/todo.md:96).
- **First-harmonic model.** A turn of θ from start φ carries `m = M_rev·θ/360·[1 + a·sinc(θ/2)·cos(φ + θ/2 − ψ)]`,
  `sinc(x) = sin x / x`. The ripple factor `sinc(θ/2)` is 0.90 for a quarter, 0.64 for a half, 0.30 for three
  quarters, 0 for a whole turn. The two p18_half block pairs give relative half-differences 0.064 and 0.034, which
  solve to **ψ ≈ 299°** and `β·a·0.637 = 0.072`, so **a ≈ 0.113/β**; β (the local footprint exponent at the half turn,
  §1.4) lies between the two measured chords, 0.43 and 0.98, so **a = 0.11–0.26**. (Rev 1's "a ≳ 0.2–0.3" used β = 0.42
  everywhere; it is the upper end.) **Confidence: low-medium** (area proxy, not weighed; ψ moves with every power
  cycle; the start angle is re-set after one, I11).
- **Per pad.** A pad whose n dots of s steps are consecutive gets the integral over its turn `T = n·s`:
  `m_pad = M_rev·T/1600·[1 + a·sinc(T/2)·cos(φ0 + T/2 − ψ)]`, so its ripple factor is `sinc(T/2)` whatever n is (two
  consecutive quarter turns are one half turn of the screw). The validated 0603 recipe is one half-turn dot per pad
  (T = 800, factor 0.64), and the user accepted its pad-to-pad spread after reflow (PT/todo.md:197-198, :227).
- **The engine knows the angle**: Marlin's M114 `Count B` survives `G92 B0` (D/DispenseProfile.java:20-22; confirmed on
  the real controller, mem:lumen-calibration-state), the start angle is a calibration of the current power-up (I11,
  D/Interlocks.java:407-417), and the first purge dot turns the screw so the work starts at `startAngleDeg`
  (D/DispenseProfile.java:114-126, D/GcodeStreamBuilder.java:324-346).
- **The phase rules (binding for planner, builder, validator, interlocks and calibration):**
  - **R1 Allowed doses.** A dot's net turn is `s ∈ {400, 800, 1600}` steps (quarter, half, one turn): whole steps
    with `1600 % s == 0` (D/DispenseProfile.java:141-146 allows up to two turns; this design stops at one: large doses
    per dot load the tip, PT/lessons.md:13, a two-turn dot at today's cadence would ask ≈ 3.3 mg/s, above §1.1's
    2.56, and two turns were never dispensed). With the 22G tip `s ≥ tip.minDotSteps = 400` (§1.5). Thirds and sixths
    are not whole steps (533.3 / 266.7), so the bench's 60° sixth (`tools/plan.py:20`) is not reproducible and not
    offered.
  - **R2 Blocks.** All dots of one dose are consecutive: a **block**. A block of a partial dose starts with the screw
    at the profile's start angle; inside it the start angles cycle through `startAngle + k·s` (4 angles for quarter
    turns, 2 for half turns). Whole-turn blocks need no angle.
  - **R3 Block switch.** Between two blocks the needle goes to the purge row: for a partial dose one **phase dot** (the
    turn that leaves the screw, after F more dots, at the start angle; `phaseSteps` generalised to any dose) and
    **F = 4 flush dots** of the new dose; for a whole-turn dose F flush dots only. The flush covers carry-over
    (≈ 3 dots, `runs/p18_split_blank2/measured.csv` dots 4–6 after the half-turn block; PT/lessons.md:15).
  - **R4 Calibrated phases.** The calibration of dose s measures every start angle of its set (§7.4).
  - **R5 Whole pads.** Every pad's dots are consecutive in the program (a pad is dispensed in one go).
  - **R6 Half a turn per pad.** `n·s ≥ 800` steps. With R5 a pad's ripple factor is then at most 0.64, the validated
    0603 pads': no pad varies more with the screw angle than the pads the user validated. (One quarter-turn dot per
    pad would be 0.90.)
  - **R7 Feed.** Per block, the paste demand (predicted mass / block time from the estimate) stays at or below
    `profile.sustainedFeedMgS` (default 2.6, §1.1).
  - Today's plans are one block of 800 with one dot per pad (D/GcodeStreamBuilder.java:194), so 0603-only boards are
    unchanged.

### 1.3 Amount per pad (anchored on what the user validated)

- **Model.** `m_target = κ_f · A · k`, A the paste-opening area (KiCad aperture, D/DotMath.java:9-12), κ_f the anchor's
  mass per mm² of opening for the group's **family** f (§4.4).
- **Anchors.** A family's anchor is its `validated` pad type with a *weighed* mass per pad (`massPerPadMg` from a
  confirmed mass record, k = 1), the one nearest in log area to the group; a `characterized` type is never an anchor
  (only reflow decides the amount). The built-in **bench anchor** (`P/Anchor.BENCH`) is the chip family's: 1.056 mg per
  pad on the R_0603_1608Metric opening 0.80 × 0.95 mm, roundness 50 → 0.7257 mm² (ftp.kicad_pcb via BI §2.8), so
  **κ_chip = 1.455 mg/mm²** (stencil-equivalent 0.39 mm at 3.76 mg/mm³).
  - Why the R opening and not the camera's 0.856 mm² pad (PT/todo.md:197) nor the seed's 0.80 × 0.95 roundness 0
    (0.760 mm², D/DispenseDb.java:210): suggestions scale KiCad openings, so the anchor is measured the same way.
  - A family with no anchor of its own uses κ_chip, with confidence low and the reason `no validated {family} pads yet:
    the amount is scaled from the 0603 half turn`. Where the group's pad pitch is ≤ 1.27 mm, its target is also
    **capped at a 0.25 mm print** (`m_target ≤ 3.76·A·0.25`): at fine pitch bridging, not wetting, limits the amount;
    0.25 mm is twice the usual 0.125 mm stencil at 1.27 mm pitch (dots do not spread over the whole pad: the sixth at
    0.13 mm equivalent left bare 0603 pad, PT/todo.md:197) and well below the chip anchor's 0.39 mm. Low confidence;
    open question 2.
- **k.** 1.0, except 0.5 for a centre pad (the resolver's QFN/DFN rule, D/PadTypeResolver.java:71-77) and for any pad
  above 4.0 mm² (tabs, mounting and thermal pads). 0.5 is the seed's placeholder (D/DispenseDb.java:229-232); an
  IPC-like 50–80 % coverage of a 0.10–0.15 mm print would be k ≈ 0.2–0.4 relative to the anchor. **Confidence: low.**
- **Tolerance: −25 % / +10 % of the target.** The anchor already sits at "~80 % of the largest fillet IPC-A-610
  accepts" (PT/todo.md:119) with "flux halo with fine speckle just outside the pads … possible small solder balls"
  (PT/todo.md:200), so +25 % would reach the maximum; −25 % stays at twice the sixth turn that left bare pad.
- **Confidence of κ_chip: medium** until the 3 × 100 weighing (PT/todo.md:236): one weighing of board 1 (PT/todo.md:97-98,
  camera median 0.714 mm², :96), while the reflow-validated boards 2 and 3 showed 6–10 % more pre-reflow dot area
  (0.756 and 0.788 mm², PT/todo.md:197, :213; board 3's tinned pads also "add volume", :203), so the validated mass per
  pad was 1.06–1.16 mg. The anchor's weighing was at gap 0.10 (PT/todo.md:95), boards 2 and 3 at 0.15 (:207); the
  bench found 0.05–0.15 equivalent (D/DispenseProfile.java:53-54). The anchor is a mass per area that reflow accepted,
  not a dose record; dose records stay recipe-bound (§1.7).
- **No silent rescaling.** Every automatic calibration lays the profile dose first as a control (§7.4) and weighs it when
  no confirmed mass at the recipe exists (§7.5); the result reports the validated recipe's drift
  (`the validated half turn now gives 1.15 mg, 9 % more than the 1.06 mg weighed on 2026-10-27`); κ is never changed
  by a calibration. The engine's `targetMassMg` (A·0.10·k·ρ, D/DispensePlanner.java:102-106) stays the stencil-print
  figure and is labelled so.

### 1.4 Dot footprint vs mass (on the blank)

- **Steady-state normal-lift points only** (blank, 18 psi, 0.10 gap, 180 rpm, 250 ms dwell):

  | dose | mass (mg) | footprint (median) | n | source and exclusions |
  |---|---|---|---|---|
  | sixth (60° net) | 0.352 (inferred) | 0.359 mm², d 0.68 mm | 3 | `runs/p18_split_blank2/measured.csv` dots 7–9 (0.359/0.344/0.413). Excluded: dots 4–6 (carry-over after the half-turn block, 0.75/0.58/0.63, PT/todo.md:108); dots 10–16, the slow-lift variant (ids `smallbrk10`–`16`, `brk [0.3, 60, 200]`, `runs/p18_split_blank2.json`; PT/todo.md:108 "Slow lift: worse"); all of `runs/p18_split_blank` (1 h idle, references still ramping 0.25 → 0.9, PT/todo.md:106; its pairs 11/12 and 14/15 are one thread measured from both ends: offsets +1.070/−1.391 and +1.025/−1.403 mm at 2.5 mm pitch, `measured.csv`) |
  | half (180.18° net) | 1.056 (weighed) | 1.057 mm², d 1.160 mm (CV_d 3.9 %) | 24 | `runs/p18_half/meas.txt` dots 4–28 without dot 15 (θ 100°, a phase dot); dots 0–3 start-up |
  | one turn | 2.11 (2 × half, positive displacement) | 1.421 mm², d 1.345 mm (CV_d 2.7 %) | 33 | `runs/p18_blank/meas.txt` dots 6–38; dots 4–5 are the start-up blobs (d 1.84, circularity 0.35) |

- **Two regimes.** The mean height V/A is 0.261 mm for the sixth, 0.266 mm for the half and 0.395 mm for one turn
  (V = m/3.76). So up to about a half turn the deposit is a **disc of constant height** set by the needle (the 0.10 mm
  gap under the needle face plus the column the lift draws out): area ∝ mass. Beyond, the yield-stress paste builds up
  instead of spreading: one turn comes out taller, area ∝ m^0.43. The bench thread's "area ∝ volume^~0.4" (calibration
  brief) holds above the half turn only.
- **Law (adopted, `P/DoseModel`): piecewise power law through the points** (log–log chords):
  `A = 0.359·(m/0.352)^0.982` for m ≤ 1.056, `A = 1.057·(m/1.056)^0.428` for 1.056 ≤ m ≤ 2.11 (mm², mg).
- **Band between points.** The exponent falls with mass (0.98 → 0.43), so log A is concave in log m: between two points
  the chord is a lower bound and the next chord extended is an upper bound. At the allowed doses: **400 steps (0.528 mg):
  A 0.535–0.785 mm², d 0.83–1.00 mm**; 800 and 1600 are measured points (d 1.160, 1.345 mm; scatter CV_d 2.7–3.9 %).
  Overhang, bridging and needle-clearance checks use the **upper edge** (`dHi`); the band is shown as the predicted dot
  size until the camera has measured the dose. The lowest-phase quarter dot (§1.5) uses the lower chord.
- **Where this differs from rev 1.** Rev 1's single law `A = 1.07·m^0.42` was fitted through medians that included
  start-up and slow-lift dots; it predicted the sixth at d 0.94 mm against 0.68 measured and a quarter at 1.02 mm. The
  spherical-cap law of the engine-dose map (A ∝ m^(2/3)) is not used either.
- **Confidence:** medium at 800 and 1600 (camera on the blank; 1600's mass is inferred), low at 400 (between points; a
  quarter turn has never been dispensed). On pads the area saturates at the pad (0.74–0.80 mm² on 0603 pads vs 1.06 on
  the blank, PT/lessons.md:19), so the blank diameter is the bound used for overhang and bridging.

### 1.5 The 22G tip and the smallest dose

- Tip: ADL AD22TTA-B, 22G plastic tapered, ID 0.41 mm (D/DispenseDb.java:185; mem:dispenser-hardware-facts). The OD at
  the tip is not recorded: assumed **0.72 mm** (typical 22G tapered 0.65–0.80; REAL_MACHINE_TODO §15 item 1).
- **Evidence, read again.** On the blank, normal-lift sixth-turn dots were round 6/6 (PT/todo.md:108) at d 0.66–0.73 mm,
  about the assumed OD, and they transferred 40/40 on pads (`p18_split_board`, PT/todo.md:109). The strings of
  `p18_split_blank` were the start-up after 1 h idle (PT/todo.md:108 "Test 1's strings were the start-up"). The
  failures that count are on the board: **thin paste threads across the 1.65 mm pad-to-pad hop on about half the
  sixth-turn parts and on 1 of 40 half-turn parts** (R19, PT/todo.md:109), and bare pad after reflow on every
  sixth-turn part (PT/todo.md:197). So the limit seen is not "the footprint must be wider than the needle face" but
  the thread that a small dot trails during the hop.
- **Rule: `tip.minDotSteps = 400`** (a quarter turn, 0.528 mg). Its lowest-phase dot carries `0.528·(1 − 0.90·a)` =
  0.41–0.47 mg (a 0.11–0.26), only 1.2–1.35× the sixth that threaded. So a quarter-turn dose becomes `characterized`
  only after the **hop test** (§7.4): at least 60 moves between consecutive dots at the group's own pad-to-pad distance,
  on the blank and on the weigh coupons, with at most the binomial 95th percentile of a 3 % thread rate. An eighth
  (200) is not offered with this tip. **Confidence: low** (P6; REAL_MACHINE_TODO §15 item 2).

### 1.6 Bridging, overhang and needle-clearance limits

Checked with the blank diameter of every dot: the overhang with the mean diameter (the 0.20 limit was validated on a
mean), the gaps and the same-pad pitch with the upper edge (`dHi`, a suggestion) or the measured d95 = d̄·(1 + 1.65·CV_d)
(calibration: bridging and the needle landing in paste are tail risks):

| limit | value | source and confidence |
|---|---|---|
| overhang past the own pad edge, any axis | ≤ 0.20 mm | 0603 R half turn: (1.16 − 0.80)/2 = 0.18, measured ≤ 0.19 on the board, with flux speckle just outside (PT/todo.md:200): the upper end. Medium. |
| gap between dots of different pads | ≥ 0.30 mm | model: 3σ size (d CV 2.7–3.9 %) + landing (0.054–0.13 mm, PT/todo.md:96, :109, :213) ≈ 0.25–0.30. Validated 0.49 on 0603 R. Low. |
| gap from a dot to another pad's copper | ≥ 0.15 mm | 3σ landing + 3σ radius in quadrature ≈ 0.17. Low. |
| **centre distance of two dots on the same pad** | ≥ `d/2 + OD/2` (half turn 0.94, quarter 0.86, one turn 1.03 mm) | the needle face must not land on the earlier dot: a half-turn dot (0.28 mm³ on a 0.58 mm radius, a cap ≈ 0.45 mm tall) is taller than the 0.10 mm gap out to ≈ 0.54 mm from its centre, so the face edge (OD/2 = 0.36 from the needle axis) clears it from ≈ 0.90 mm; the bench thread's "several half-turn dots ~0.9 mm apart" (calibration brief) agrees. Low (OD assumed). |
| marginal band | overhang 0.20–0.25, dot gap 0.20–0.30, copper gap 0.10–0.15 mm | suggested with low confidence ("the measured dot decides"); the measured values must be inside the limits above before `characterized`. |

Pads of the same part whose openings overlap (AVX-9155 stacks pads 0.012 mm apart) are one deposit, not neighbours.

### 1.7 Validity of a dose record

A **dose record** (§5.3) is fresh for the current setup when all of these hold:

- same `steps`; same **dose fingerprint** (pressure, suck-back, rpm, screw acceleration, dwell, gap:
  `DispenseProfile.doseFingerprint()`, §5.4; the start angle is handled below, purge count and name excluded);
- the regulator reading attested by the operator when the record was made equals the profile pressure (the job
  requires the attestation, W/core.js:590-605, and stores its psi and time on the record, §12.2);
- same tip key (`DispenseConfig.tip.key`) and recorded after `needleChangedAtMs` (D/DispenseConfig.java:85);
- same paste key and recorded after `paste.loadedAtMs` (the "New paste loaded" press, §13.6);
- younger than `padCalibration.doseRecordValidHours` (default **8 h**: one session; drift with paste age was never
  measured, so the window is conservative and a setting);
- `simulated` equal to `SimGuard.isSimulated` now (SIM records never count on the machine, AR §9.1);
- **optical and coupon records of a partial dose** also: recorded after the controller's last connect
  (`controllerConnectedAtMs`, D/Interlocks.java:412) and after `profile.startAngleSetAtMs` (D/DispenseProfile.java:46):
  their per-phase means belong to the angle the screw started at, which a power cycle moves. Mass records
  (phase-complete counts, §7.5) and records of whole turns survive a reconnect.

Records age; types do not: a `characterized` type keeps its status when its records go stale (as today's statuses do).
After a reconnect, I11 refuses every partial-turn block until the start angle is set again (§6.5), which is what keeps a
quarter-turn type's calibrated phases meaningful.

### 1.8 Predictions the calibration tests (reported as "predicted / measured" in the result)

| # | prediction | pass band | confidence |
|---|---|---|---|
| P1 | mass per step equal across 400, 800, 1600 (positive displacement), and 1.32 µg/step at 18 psi | two doses within ±5 % of each other (confirms both, §7.5); each within ±10 % of 1.32 | medium (800), low (others) |
| P2 | two readings of one dose agree | ±5 % | medium |
| P3 | blank dot inside the band: 400 → d 0.83–1.00 mm; 800 → 1.16 ± 8 %; 1600 → 1.345 ± 8 % | as stated | medium (800, 1600), low (400) |
| P4 | phase: half turns' two starts within 13 % (bench 6 %); quarter turns' fitted first-harmonic amplitude ≤ 0.20 (predicted 0.10 = 0.072 × 0.90/0.64) | upper 95 % bound inside | low-medium |
| P5 | residual area CV (phase harmonic removed, first 2 dots after a cross excluded) ≤ 13 % (bench ≈ 6 % half, 5.4 % whole) | upper 95 % bound inside | medium |
| P6 | threads between consecutive dots at the group's hop: rate ≤ 3 % (bench: half 1/40 parts, sixth ~1/2, PT/todo.md:109) | ≤ k95(hops, 3 %) in ≥ 60 hops | low |
| P7 | after a block switch (1 phase + 4 flush) the first 2 work dots are within 2σ of their block | reported, ≥ 95 % | medium |
| P8 | the control (profile dose) matches the bench: blank area 1.057 mm² ± 15 % | as stated | medium |

### 1.9 What §1 and §4 give on the three KiCad fixtures

Package-level survey (each footprint's openings with their own neighbours; every group planned from scratch with no
pad type, so covered groups show what a new type would get), 22G tip, model diameters (`dHi`):

| package (fixture) | opening (mm) | A mm² | target mg | result |
|---|---|---|---|---|
| R_0603_1608Metric (ftp) | 0.800 × 0.950 r50 | 0.726 | 1.06 | 1 × 800, 1.06 mg (0 %), overhang 0.18, dot gap 0.49 |
| LED_0603_1608Metric (ftp) | 0.875 × 0.950 r50 | 0.790 | 1.15 | 1 × 800 (−8 %), overhang 0.14, gap 0.42 |
| R_0805 / C_0805 (mobo) | 1.025 × 1.40 r49 / 1.00 × 1.45 r50 | 1.381 / 1.396 | 2.01 / 2.03 | 1 × 1600 (+5 % / +4 %), overhang 0.16 / 0.17. Two half-turn dots need 0.94 mm between them on the 1.40 mm pad and reach 0.35 mm past it |
| D_SOD-123 (mobo) | 0.90 × 1.20 | 1.08 | 1.57 | fallback 1 × 800 (−33 %), low |
| SOT-23 (mobo) | 1.475 × 0.60 r50 | 0.866 | 1.26 | marginal 2 × 400 (−16 %), the ≤ 1.00 mm dots reach 0.20 across the 0.60 width, gap 0.39; a half-turn dot reaches 0.28 |
| SOIC-8 (mobo) | 1.95 × 0.60 r50 | 1.151 | 1.08 (capped: pitch 1.27; the chip rate would give 1.67) | marginal 2 × 400 (−2 %), overhang 0.20, gap 0.27 |
| PinHeader 2×05 1.27 (mobo) | 2.40 × 0.74 | 1.776 | 1.67 (capped) | marginal 3 × 400 (−5 %), overhang 0.16, gap 0.27 |
| PicoBlade 1.25 (mobo) | 0.80 × 1.60 r50 + mounts 2.10 × 3.00 r24 | 1.246 / 6.25 (k 0.5) | 1.17 (capped) / 4.54 | marginal 2 × 400 (−10 %), gap 0.25; mounts 4 × 800 (−7 %) |
| SOT-223-3 pins / tab (mobo) | 2.0 × 1.5 / 2.0 × 3.8 | 3.00 / 7.60 (k 0.5) | 4.37 / 5.53 | 2 × 1600 (−3 %), overhang 0.19 / 4 × 800 (−24 %): two groups, two types |
| C_Elec_6.3x7.7 (mobo) | 3.325 × 1.60 r31 | 5.27 (k 0.5) | 3.83 | 3 × 800 (−17 %) |
| Crystal 3225 (mobo) | 2.10 × 1.80 | 3.78 | 5.50 | 2 × 1600 (−23 %) |
| L_6.3x6.3 (mobo) | 1.50 × 2.40 | 3.60 | 5.24 | 2 × 1600 (−19 %) |
| AVX-9155 (mobo) | 1.30 × 1.15 r50 / 1.50 × 2.15 r20 / 1.50 × 2.125 | 1.42 / 3.21 / 3.19 | 2.07 / 4.66 / 4.64 | 1 × 1600 (+2 %) / 2 × 1600 (−10 %) / 2 × 1600 (−9 %) (overlapping pads merged) |
| 1TS003B (mobo) | 1.15 × 1.00 / 1.05 × 1.00 / 1.20 × 1.30 r25 | 1.14 / 1.04 / 1.54 | 1.65 / 1.51 / 2.24 | fallback 1 × 800 (−36 %) / fallback 1 × 800 (−30 %) / 1 × 1600 (−6 %) |
| LQFP-48 0.5, WSON-8 0.5/0.8, QFN-32 0.5 (and its 9 paste windows: dot gap 0.15), MHS110 0.8/1.04, LiteOn 0.85 (mobo) | – | – | – | **not dispensable** (dot gap < 0.20 mm) |
| LED_0805 (nano, top) | 1.048 × 1.048 r20 | 1.088 | 1.58 | fallback 1 × 800 (−33 %): one turn would be +33 % |
| SW_SPST_3.9x2.9mm (nano, top) | 0.864 × 1.829 r18 | 1.574 | 2.29 | 2 × 800 (−8 %), dots 0.94 apart, overhang 0.15 |
| USB_Mini-B (nano, top) | data 2.388 × 0.356 (0.8 pitch); 4 shell tabs 1.626 × 2.134 r23 | – / 3.44 | – / 5.00 | data **not dispensable**; tabs 2 × 1600 (−16 %) with the *partial* note (§3.3) |
| VQFN-32 (nano, top) | 0.128 × 0.548 (0.5 pitch) + EP 1.27² | 0.069 / 1.61 (k 0.5) | – / 1.17 | perimeter **not dispensable**; EP 1 × 800 (−10 %) with the *partial* note |
| Resonator 3-pin 1.0 pitch (nano, top) | 1.848 × 0.548 | – | – | **not dispensable** |
| C_0603 / Fuse_0603 (nano, bottom) | 0.948 × 0.746 r76 / 0.798 × 0.848 r17 | 0.638 / 0.672 | 0.93 / 0.98 | covered by chip-0603 (ratios 0.88 / 0.93); as new types marginal fallback 1 × 800 (+14 %, overhang 0.21) / 1 × 800 (+8 %) |
| CP_EIA-3216 tantalum A (nano, bottom) | 0.965 × 1.626 r19 | 1.562 | 2.27 | 1 × 1600 (−7 %), overhang 0.19 |
| SOT-223 (nano, bottom) | pins 1.067 × 2.083 r20 / tab 3.448 × 2.048 r25 | 2.21 / 7.00 (k 0.5) | 3.22 / 5.10 | fallback 2 × 800 (−34 %) / 4 × 800 (−17 %) |
| DO-220AA (nano, bottom) | pin 0.610 × 1.118 r12 / tab 2.515 × 2.388 r26 | 0.680 / 5.92 (k 0.5) | 0.99 / 4.31 | pin: **no plan** (a half-turn dot reaches 0.28 past the 0.61 mm width; one quarter per pad breaks R6); tab 4 × 800 (−2 %) with the *partial* note |
| R_CAY16-4 0.8, SSOP-28 0.65 (nano, bottom) | – | – | – | **not dispensable** |

- **Nano split (both sides, R/nano.pads.json):** 85 pads `tip` = **exactly the bench's 84 too-fine pads** (VQFN-32
  perimeter 32, SSOP-28 28, R_CAY16 2 × 8, USB data 5, resonator 3; calibration brief) **plus the DO-220AA pin**; 40
  pads with a plan, 6 of them with the *partial* note (USB tabs 4, VQFN EP 1, DO-220AA tab 1). The bench counted 41
  dispensable; the one-pad difference is the DO-220AA pin (open question 4). The KiCad upload imports the top side only
  (BOARD_IMPORT_DESIGN.md:73, :373): 55 pads, 40 `tip`, 15 with a plan (5 partial).
- mobo (package level): 107 pads with a plan (10 fallback), 37 marginal, 117 not dispensable. ftp: 160/160 half turns.
- 0402 (seed chip-0402, 1.02 mm pitch): centre dots of 1.00 mm leave 0.02 mm → not dispensable with the 22G tip,
  matching the bench thread ("0402 needs ~0.15 turn, close to the sixth").

---

## 2. Decisions from the brief, and how this design honours them

| Brief decision | Where | Notes |
|---|---|---|
| 1. Engine-side profile check on the upload preview and every plan; groups by package + opening; one reason each; shown in the preview, Run readiness, Profile "Needs a profile"; blocking stays the gate | §3, §12, §13 | Also on a board file before planning (`?boardFile=`). A fifth reason, `no-pad-size`, reports the existing `NO_GEOMETRY` flag. The run interlocks add refusals for doses the tip or the screw cannot do (§6.5), not new flags. |
| 2. Suggestion: same-size existing type as a one-click explicit mapping; else a new type (explicit mapping, no regex, opening size, amount from the best anchor with k, phase-keeping non-bridging dot plan, predicted mg and dot size, confidence) with the pad preview | §4, §13.4 | Anchors are per family (§1.3). Mappings gain an optional pad-name list (§5.2): a package with two opening sizes (SOT-223) gets two types. "One click" = mapping plus plan again (§12.1). The preview is BI's lite BoardView (§13.4). |
| 3. Two choices per group and "all", each saving the suggestion as provisional first; auto = home+Z, blank, purge, ladder, scale step when no fresh mass record, choose, write (`characterized` iff all pass), plan again, result card; says time/paste/blank/weighing first; `validated` stays the user's | §7, §8 | The scale step lays dot grids on two probed weigh coupons and asks once, at the end (weigh, wipe, weigh again: the bench method, PT/todo.md:97); §7.5, open question 1. |
| 4. Per-type dot dose only if it keeps the phase and is measured; else more/fewer profile dots | §1.2, §4.3, §6 | Profile dots are tried first; other doses get their own block; R5/R6 bound every pad's phase spread by the validated 0603's. |
| 5. Balance interface: manual (default) + SIM; no serial driver | §10 | Serial port in REAL_MACHINE_TODO. |
| 6. SIM paste model, deposits seen by DotInspector, SIM balance, SimGuard | §11 | Its footprint law, noise and ripple differ from §1 on purpose; scene variants are negative controls. |
| 7. Out of scope: lines, reflow inspection, automatic `validated`, real balance driver, gate default | – | Respected. |
| Bench thread notes | §1.4, §1.5, §1.6, §1.9, §9 | "Area ∝ volume^~0.4": true above the half turn only; below it area ∝ mass (constant-height disc). "Several half-turn dots ~0.9 mm apart on pads above ~1 mm²": adopted as the same-pad pitch (≥ 0.94 mm); on 0805 (1.40 mm long) two such dots reach 0.35 mm past the pad, so 0805 gets one full turn; SW_SPST, tabs and big pads get several half turns. "Small pads need ~0.15 turn": not offered (0402 not dispensable; the smallest dose is a quarter turn, characterized only with the hop test). Nano: the 84 too-fine pads match exactly; LED_0805 gets a half turn (−33 %) rather than one turn (+33 %, above the +10 % limit); the DO-220AA pin has no plan. The per-pad-size test-board ladder is deferred (§9). |
| Tip ownership (brief, needles on hand) | §1.7, §3.3, §5.1 | A type records `tipKey`; records need the same tip and to postdate the last needle change; a type calibrated with another tip gets the `outside` note `calibrate it again for this tip` (open question 5: block instead?). |

---

## 3. The profile check (`P/ProfileCheck.java`, pure)

### 3.1 Inputs

`ProfileCheck.of(CheckInputs in)`; adapters in `D/PadInstances.java` (§12.5) and in BI's preview lines (§12.6) build
the inputs from:

1. **a plan**: every `PlannedPad` (D/DispensePlan.java:14-50, plus BI's `padName`, `partId`, `roundness`, `typeRule`,
   BI §4.5) with its flags and its planned dots;
2. **a board file before planning**: `new BoardPadExtractor(resolver, cfg.includeDisabledPlacements).extract(bl)` with
   the board at the origin (as SimBoardRenderer does, AR §9.4), no dots;
3. **the KiCad upload preview**: BI's `packages[].pads` (part frame) × `placements[]` (board frame), resolved with the
   final ids and the carried mapping of BI §13 item 3, no dots.

```java
public final class PadInstance {          // P/PadInstance.java, one paste opening, board or machine mm, +Y up
  public String padId, ref, packageId, padName;
  public double x, y, rotationDeg, wMm, hMm, roundness;   // centre; w along the pad's own x
  public String typeKey; public Integer typeVersion; public String typeStatus;
  public String typeRule;                 // package-map | pad-map | centre-pad | pattern | none (BI §4.5 + §5.2)
  public java.util.List<String> flags = new java.util.ArrayList<>();   // planner flags, empty for previews
  public boolean blocked, centrePad;
  public Integer dotSteps;                // planned dose of this pad's dots, null if none
  public java.util.List<double[]> dots;   // planned dot centres (same frame), null in previews or when blocked
}
public final class CheckInputs {          // P/CheckInputs.java
  public java.util.List<PadInstance> pads; public java.util.Map<String, TypeInfo> types;   // current versions by key
  public String gate; public Tip tip; public int profileSteps, stepsPerRev = 1600;
  public java.util.Map<Integer, DoseEvidence> evidence;   // pooled fresh records per dose (§1.7, §7.6)
  public java.util.Map<String, Anchor> anchors;           // by family; chip falls back to Anchor.BENCH
  public String doseFingerprint;          // current, §1.7
  public boolean planned;                 // dots present
}
```

`TypeInfo` (key, name, family, version, status, pad w/h/roundness, k, dotSteps, dotsPerPad, tipKey, doseFp,
calAreaMm2, massPerPadMg, origin, retired), `Tip` (key, name, idMm, odMm, minDotSteps), `DoseEvidence` (steps;
mgPerStep, mgPerDot, massConfirmed, massReadings; dMeanMm, d95Mm, residCv, phaseStat, phaseLimit, driftPct, misses,
hops, threads, landing, n, opticalFresh, opticalPass, failures) and `Anchor` (family, typeKey, version, massPerPadMg,
areaMm2, mgPerMm2, bench) are plain value classes in `P/`, so the package needs no DB or OpenPnP type.

**Neighbours' dots.** In a plan, every pad's planned dots. In a preview or a board file, a pad's dots come from its
resolved type's plan (`dotSteps`/`dotsPerPad` through `P/DotGeometry`, §4.3), else from its own group's suggestion
(computed in a first pass), else a centre dot of `dHi(tip.minDotSteps)`.

### 3.2 Grouping

1. Key each opening by `(packageId, L = max(w,h), S = min(w,h), roundness)`.
2. In package-pad order (natural sort of pad names), an opening joins the first group of its package with
   `|ΔL| ≤ 0.02 mm`, `|ΔS| ≤ 0.02 mm` and `|Δroundness| ≤ 5`; else it starts a group.
3. Group id: `"g" + first 12 hex digits of SHA-1("packageId|L|S|roundness")` of the group's first opening (L, S to
   0.001 mm, roundness to 0.1): URL-safe and identical for the preview, the board file and the plan of the same final
   package id (BI §3.5).
4. Per group: package id, pad names, refs, pad ids, count, the opening, `minPitchMm` (nearest centre of any other
   opening on the board), `minEdgeGapMm` (nearest copper), `hopMm` (the shortest centre distance between dots of two
   different pads of the same part in the group's plan or suggestion: the move a thread forms on, §1.5), the resolved
   type (the group splits by type if its openings resolve differently) and the flags.

Neighbours are found board-wide with a 3 mm spatial grid over all openings (other parts included); pads of the same part
whose rectangles overlap are not neighbours (§1.6).

### 3.3 Reasons (one per group, first match wins)

| order | reason code | when | blocking |
|---|---|---|---|
| 1 | `tip` | `tipKind`: `pitch` (centre dots of `dHi(minDotSteps)` leave a dot gap < 0.20 mm), `copper` (such a dot reaches another pad's copper), `no-plan` (§4.3 finds no plan, step 9) | yes if the pads are blocked by a flag, else a note (the gate decides, brief 1) |
| 2 | `no-pad-size` | `NO_GEOMETRY` (D/DispensePlanner.java:98-101) | yes |
| 3 | `no-type` | `NO_PAD_TYPE` (D/DispensePlanner.java:87-91) | yes |
| 4 | `below-gate` | `STATUS_BELOW_MIN` (D/DispensePlanner.java:95-97) | yes |
| 5 | `outside` | the pads pass the gate, and: area ratio `A / calArea(type)` outside 0.80–1.25 (`calArea` = `type.calAreaMm2`, else the type's pad area); or a planned dot breaks a §1.6 limit **beyond the marginal band** (inside it the type's measured dot decided); or `type.tipKey` set and ≠ the current tip; or `type.doseFp` set and ≠ the current dose fingerprint | no (Note) |
| – | none | otherwise | the group is not listed |

- **`partial`** (an attribute, not a reason): another group of the same package is `tip`. The group keeps its own
  reason and suggestion; its text gets the partial sentence (§3.4) and the Needs list shows it with its package.
- A type whose dose the current tip cannot do, or which breaks R6, is refused by the run interlock (§6.5) and listed as
  `outside` with the interlock's words.
- Counts: `fixable` = blocking groups with reason `no-pad-size`, `no-type` or `below-gate` (what Profile can fix);
  `tipBlocking` = blocked `tip` groups; `notes` = `outside` groups.

The check never adds or removes a `PadFlag`; `blocking` is "some pad of the group is blocked by its flags"
(D/DispensePlan.java:34-41). Run is refused exactly as today (D/Interlocks.java:420-441) plus §6.5.

### 3.4 Texts (exact; `P/Texts.java`, numbers with `Locale.ROOT`, mm to 2 decimals, mg to 2, % whole; plurals as `IL.plural`)

- `tip` pitch, gap > 0: `Not dispensable with the {tipName}: these pads are {pitch} mm apart, and the smallest dot this tip makes reliably ({dMin} mm, a quarter turn) would leave only {gap} mm to the next pad's dot (at least {cDd} mm needed). A finer tip, a paste line or a stencil would be needed; this version does not do those.`
- `tip` pitch, gap ≤ 0: `Not dispensable with the {tipName}: a {dMin} mm dot, the smallest this tip makes reliably, is wider than the {pitch} mm pad pitch, so neighbouring dots would overlap by {overlap} mm. A finer tip, a paste line or a stencil would be needed; this version does not do those.`
- `tip` copper: `Not dispensable with the {tipName}: the smallest dot this tip makes reliably ({dMin} mm) would reach {into} mm onto the next pad. A finer tip, a paste line or a stencil would be needed; this version does not do those.`
- `tip` no-plan: `Not dispensable with the {tipName}: on these {w} × {h} mm pads the closest plan ({n} {turn} {dot|dots} per pad) reaches {ovh} mm past the pad edge (at most 0.25 mm) or comes within {gap} mm of the next pad's dot (at least 0.20 mm).`
- `partial` (appended): ` The other pads of {package} ({finePitch} mm apart) cannot be dispensed with this tip: paste these only if you solder those pins by hand.`
- `no-pad-size`: `{package} has no pads in OpenPnP and its pad type {typeName} has no pad layout: add the footprint's pads in OpenPnP, or upload the KiCad board.`
- `no-type`: `No pad type for {package} ({w} × {h} mm openings, {n} pads on {parts} parts): these pads get no paste until a pad type is chosen.`
- `below-gate`: `{typeName} is not calibrated yet (status: {status}). Boards need pad types that are at least {gate}: calibrate it automatically or by hand.`
- `outside` (area): `{typeName} was calibrated for {tw} × {th} mm pads ({tA} mm²); these are {w} × {h} mm ({A} mm², {pct} % {larger|smaller}), so each gets {pm} mg instead of about {am} mg.`
- `outside` (geometry): `{typeName}'s dots on these pads would reach {ovh} mm past the pad edge (at most 0.25 mm)` / `… leave {gap} mm to the next pad's dot (at least 0.20 mm)` / `… come within {gap} mm of the next pad (at least 0.10 mm)`, then `: a pad type for this size avoids it.`
- `outside` (tip): `{typeName} was calibrated with the {oldTip}; the tip now is the {tipName}: calibrate it again for this tip.`
- `outside` (recipe): `{typeName} was calibrated with another recipe ({diff}); calibrate it again, or set the profile back.`
- `outside` (interlock): the §6.5 refusal text.

`{status}`, `{gate}` use Profile's words (`not started`, `provisional`, `characterized`, `validated on a board`,
W/profile.js STATUS words); `{turn}` from `DoseModel.turnName`: `quarter-turn`, `half-turn`, `one-turn`.

### 3.5 Output and where it is computed

```json
"profileCheck": {
  "ok": false, "fixable": 2, "tipBlocking": 1, "notes": 1, "computedAtMs": 1793…,
  "tip": {"key": "ad22tta-b", "name": "22G tip", "odMm": 0.72, "minDotSteps": 400, "minDotMm": [0.83, 1.00]},
  "anchors": [{"family": "Chip (2-terminal SMD)", "typeKey": null, "bench": true, "massPerPadMg": 1.056, "areaMm2": 0.726,
               "mgPerMm2": 1.455, "text": "the half turn you validated on 0603 pads after reflow (1.06 mg on 0.73 mm², weighed 2026-10-27)"}],
  "groups": [{
    "group": "g3f9a0c41b2de", "label": "SOT-23 · 1.48 × 0.60 mm",
    "packageIds": ["SOT-23"], "padNames": ["1", "2", "3"], "allPadsOfPackage": true, "refs": ["D2"], "parts": 1,
    "pads": 3, "padIds": ["D2.1", "D2.2", "D2.3"],
    "aperture": {"wMm": 1.475, "hMm": 0.600, "roundness": 50, "areaMm2": 0.866},
    "minPitchMm": 1.90, "minEdgeGapMm": 0.53, "hopMm": 1.90,
    "typeKey": "sot-23", "typeVersion": 1, "typeStatus": "not-started", "typeRule": "pattern",
    "reason": "below-gate", "tipKind": null, "partial": false, "blocking": true, "fixable": true,
    "short": "not calibrated yet",
    "reasonText": "SOT-23 is not calibrated yet (status: not started). Boards need pad types that are at least characterized: calibrate it automatically or by hand.",
    "suggestion": { … §4.6 … }
  }]
}
```

- **Plan**: `A/PadProfileViews.addTo(out, entry, svc)` (one call in `Views.plan`, A/Views.java:205-291, next to BI's
  `BoardPlanView.addTo`). Memoised in a `WeakHashMap<PlanStore.Entry, Memo>` keyed by the entry instance, the value
  holding `{tuple: (planId, padTypesFingerprint, gate, evidence version), result}`; recomputed when the tuple differs.
- **Board file**: `GET /api/pad-profile/check?boardFile=<path of a listed board>` (§12.1).
- **Preview**: BI's preview pipeline calls `ProfileCheck.of(…)` and adds `packages[].profileCheck` (§12.6).

---

## 4. The suggestion (`P/Suggester.java`, pure, deterministic)

### 4.1 Tip test ("can the needle dispense these pads at all")

Every opening of the board gets one centre dot of `dHi(tip.minDotSteps)` (the measured d95 if a fresh optical record
exists; 1.00 mm for the 22G by §1.4). Not dispensable (`tip`, `pitch` or `copper`) if an opening of the group has a dot
gap `< 0.20 mm` or its dot reaches another pad's copper. `0.20 ≤ gap < 0.30` is marginal.

### 4.2 Reuse an existing pad type

- **Candidates**: current, not retired types with status ≥ gate; `tipKey` null or the current tip; `doseFp` null or
  current; **same pad size**: `0.90 ≤ A/calArea ≤ 1.10` and `|ΔL|, |ΔS| ≤ 0.10 mm` against the type's pad; the type's
  own dot plan (its `dotSteps`/`dotsPerPad`, or the profile's dose with DotSizer, D/DotSizer.java:19-34) passes §1.6 on
  every opening with its neighbours' own dots, with mass within −25 %/+10 % of `m_target`. Choose the smallest
  `|A/calArea − 1|`, then the higher status, then the key. Result `kind: "use"`.
- **`below-gate`**: if the group is covered by its own type (`0.80 ≤ A/calArea ≤ 1.25`) and no other type is reusable,
  `kind: "calibrate-existing"`. **Its plan**: when the type already carries `dotSteps`/`dotsPerPad` (origin `manual`,
  or a `suggested` type edited in the dialog), that plan is the chosen plan, and §4.3's best plan, if different, becomes
  the alternative the ladder also measures; otherwise §4.3's plan. If not covered, `kind: "new"`.
- **`outside`**: reuse, else `new`. **`tip`** and **`no-pad-size`**: `kind: "none"`.

### 4.3 New dot plan

1. `m_target = κ_f·A·k`, capped at fine pitch for families without an anchor (§1.3).
2. Candidates: every `s ∈ {800, 1600, 400}` allowed by the tip (R1) and `n ∈ 1..6` (DotSizer's default maximum,
   D/DotSizer.java:13) with `n·s ≥ 800` (R6).
3. **Positions** `P/DotGeometry.layoutN(w, h, n, pMin, rDot)`, `pMin = dHi(s)/2 + tip.odMm/2` (§1.6), `rDot =
   dHi(s)/2`: DotSizer's grid (cell centres, `bestGrid`, D/DotSizer.java:37-54); along an axis where the cell pitch is
   below `pMin` the dots spread to `pMin`; along an axis where the outer dot (radius `rDot`) overhangs more than 0.20 mm
   and the pitch is above `pMin`, the dots move inward, down to `pMin`. `D/DotSizer.layout` delegates to `layoutN(w, h,
   n, 0, 0)` (no pitch floor, no radius: nothing moves), so today's positions are unchanged.
4. **Evaluate**: `mass = n·m(s)` (m from the confirmed mass of s; else from the confirmed mass per step of another dose,
   positive displacement; else `M_rev·s/1600`), `err = mass/m_target − 1`, d = the fresh measured d95 of s (gaps,
   pitch) and d̄ (overhang), else `dHi(s)`; on every opening of the group: max overhang, min dot gap, min copper gap,
   against the neighbours' own dots (§3.1; the group's own other pads with this plan).
5. **Pass**: overhang ≤ 0.20 + 1e-9, dot gap ≥ 0.30, copper gap ≥ 0.15, −25 % ≤ err ≤ +10 %. Order by **dose class**
   (the profile dose, then 1600, then 400: measured before untested, phase-free before partial), then `|err|`, then `n`.
6. **Fallback** (none passes): geometry passes, −40 % ≤ err ≤ +15 %, ordered by `|err|` rounded to 1 %, then class, then
   n; confidence low, `the closest amount that fits is {err} %`.
7. **Marginal**: geometry inside the marginal band (overhang ≤ 0.25, dot gap ≥ 0.20, copper gap ≥ 0.10), −25 % ≤ err ≤
   +10 %, ordered as in 5; confidence low, `marginal: the measured dot size decides`.
8. **Marginal fallback**: marginal geometry and the fallback mass band; confidence low.
9. Else **no plan** → reason `tip` (no-plan text, with the closest plan's violation).

### 4.4 Name, key, family, mapping

- **family** (classification and anchor only, nothing is written as a pattern): `QFN|DFN|SON|LGA` → `No-lead ICs`;
  `SOIC|SSOP|TSSOP|MSOP|QFP|SOP` → `Leaded ICs`; `SOT|SOD|SMA|SMB|SMC` → `Small-outline discretes`; exactly two openings
  of one size → `Chip (2-terminal SMD)`; else `Custom` (the families of A/PadTypeEndpoints.java:31-32).
- **name**: base package id (without `~n`) + ` · {L} × {S} mm`; if the package has more than one group, + ` pads {first}–{last}`.
- **key**: `slug(base package id)` cut to 34 characters + `-{L×100}x{S×100}` (e.g. `sot-23-148x60`), made unique with
  the existing `uniqueKey` (A/PadTypeEndpoints.java:316-330); always matches `KEY` (:35).
- **mapping**: `{packageId, padNames}`; `padNames` is null when the group is all openings of the package, else the
  group's pad names (§5.2). Never a regex.
- **type fields**: `padWMm`/`padHMm`/`padRoundness` = the opening, layout `footprint-only` (D/PadType.java:16-17), `tMm` =
  `cfg.pasteThicknessMm` (informational), `k`, pattern null, status `provisional` (on save), `dotSteps = s`,
  `dotsPerPad = n`, `tipKey`, `doseFp` null until calibrated, `calAreaMm2 = A`, `origin = "suggested"`, notes = the
  suggestion sentence + date.

### 4.5 Confidence

- **high**: s has a fresh passing optical record and a confirmed mass; the family has its own anchor;
  `0.8 ≤ A/anchorArea ≤ 1.25`; k = 1; pass (not fallback or marginal).
- **medium**: s ∈ {800, 1600}; the family has an anchor (own, or chip for chips); `0.5 ≤ A/anchorArea ≤ 2.5`; k = 1; pass.
- **low**: everything else. `confidenceWhy` lists every reason that lowered it (exact phrases): `a quarter turn has never
  been dispensed on this machine`, `no validated {family} pads yet: the amount is scaled from the 0603 half turn`, `…
  and capped at a 0.25 mm print at this {pitch} mm pitch`, `the amount for pads above 4 mm² is a placeholder (k 0.5)`,
  `this pad is {x}× the 0603 pad the amount is anchored on`, `the closest amount that fits is {err} %`, `marginal: the
  dots leave {gap} mm to the next pad (0.30 wanted); the measured dot size decides`, `marginal: the dots reach {ovh} mm
  past the {w} mm pad (0.20 wanted); the measured dot size decides`, `no weighing at this recipe yet`, `the mass per
  step comes from another turn size`.

### 4.6 Suggestion JSON

The SOT-23 group of §3.5 (reason `below-gate`; the seed type `sot-23`, pad 1.325 × 0.60 mm, 0.795 mm², covers the
0.866 mm² openings: ratio 1.09):

```json
{"kind": "calibrate-existing", "id": "s7d02e1a9c4f0", "group": "g3f9a0c41b2de",
 "typeKey": "sot-23", "typeVersion": 1, "name": "SOT-23", "family": "Small-outline discretes",
 "mapping": null, "planFrom": "suggested",
 "opening": {"wMm": 1.475, "hMm": 0.600, "roundness": 50, "areaMm2": 0.866},
 "amount": {"targetMg": 1.26, "k": 1.0, "mgPerMm2": 1.455, "capped": false,
            "rule": "1.46 mg per mm² of opening: the half turn you validated on 0603 pads (1.06 mg on 0.73 mm²)"},
 "dots": {"perPad": 2, "steps": 400, "turn": "quarter turn", "netDeg": 90.09, "mgPerDot": 0.53, "mgSource": "model",
          "diameterMm": [0.83, 1.00], "dSource": "model", "positions": [[-0.43, 0.0], [0.43, 0.0]]},
 "predicted": {"mgPerPad": 1.06, "errorPct": -16, "overhangMm": 0.20, "dotGapMm": 0.39, "padGapMm": 0.46, "padRipplePct": [7, 17]},
 "fit": "marginal",
 "phase": {"rule": "block", "text": "Quarter-turn dots are laid together after the half-turn dots; 5 dots on the purge row first bring the screw to the same start angle, so every quarter turn repeats, and each pad gets its 2 dots in a row (half a screw turn, like the 0603 pads)."},
 "confidence": "low", "confidenceWhy": ["a quarter turn has never been dispensed on this machine", "no validated Small-outline discretes pads yet: the amount is scaled from the 0603 half turn", "marginal: the dots reach 0.20 mm past the 0.60 mm pad (0.20 wanted); the measured dot size decides", "no weighing at this recipe yet"],
 "sentence": "Calibrate SOT-23 for these pads: 2 quarter-turn dots per pad (0.83–1.00 mm wide), about 1.06 mg per pad, 16 % less than the 1.26 mg these pads would get at your 0603 rate; the dots may reach 0.20 mm past the pad edge (the calibration's measured dot decides).",
 "calibration": {"doses": [400], "control": 800, "weigh": [400]},
 "alternatives": [{"steps": 800, "perPad": 1, "mgPerPad": 1.06, "why": "a half-turn dot (1.16 mm) reaches 0.28 mm past the 0.60 mm pad"}]}
```

- `padRipplePct`: the per-pad spread from the screw angle, `100·a·sinc(T/2)` for a = 0.11 and 0.26 (§1.2).
- `kind: "new"` adds `"typeKey"` (new, §4.4), `"name"`, `"family"`, `"mapping": {"packageId", "padNames"}` and `"pad"`
  (= the opening); e.g. `Test_Strip_0.62x1.50` (§14.3): `New pad type Test_Strip_0.62x1.50 · 1.50 × 0.62 mm: 2
  quarter-turn dots per pad (0.83–1.00 mm wide), about 1.06 mg per pad, 22 % less than the 1.35 mg these pads would get
  at your 0603 rate; at most 0.19 mm past the pad edge.`
- `kind: "calibrate-existing"` saves a new provisional version of the existing type with the chosen plan, `tipKey` and
  `calAreaMm2 = A`; its pad fields and pattern stay. `planFrom`: `suggested` or `your plan` (§4.2).
- `kind: "use"`: `{"kind": "use", "typeKey": "chip-0603", "name": "Chip 0603", "mapping": {…}, "sentence": "Use Chip 0603: same pad (0.80 × 0.95 mm; these are 0.80 × 0.95 mm). 1 half-turn dot per pad, 1.06 mg, dots 1.16 mm wide.", "confidence": …}`.
- `id` = 12 hex of SHA-1 over the group id, the chosen plan, the anchors, the evidence used and the types fingerprint:
  the apply endpoint recomputes it and answers 409 `suggestion-stale` if it changed (like BI's `decisions`, BI §3.5).
- `calibration.doses`: the chosen dose and the next allowed doses on either side that have a passing plan of their own
  (§4.3 step 5; at most 3); `control`: the profile dose (always laid first, §7.4); `weigh`: the doses with no confirmed
  mass (§7.5).

---

## 5. Data model, migration, settings

### 5.1 `pad_types` (append-only rows, D/DispenseDb.java:86-93): new nullable columns

| column | Java (`D/PadType.java`) | meaning |
|---|---|---|
| `dot_steps INTEGER` | `Integer dotSteps` | net steps per dot; null = the profile's `netStepsPerDot` |
| `dots_per_pad INTEGER` | `Integer dotsPerPad` | 1..6; null = DotSizer with the rule, as today |
| `tip_key TEXT` | `String tipKey` | the tip the dose was set or calibrated for |
| `dose_fp TEXT` | `String doseFp` | `DispenseProfile.doseFingerprint()` at calibration |
| `cal_area_mm2 REAL` | `Double calAreaMm2` | opening area the dot plan was made for |
| `mass_per_pad_mg REAL` | `Double massPerPadMg` | confirmed weighed mass per pad at calibration (anchor source, §1.3) |
| `origin TEXT` | `String origin` | `seed` \| `manual` \| `suggested` \| `auto-calibrated` |

Validation in `insertPadTypeVersion` (D/DispenseDb.java:243-292) and in `PadTypeEndpoints.apply`
(A/PadTypeEndpoints.java:221-278): `dotSteps` null or in `{400, 800, 1600}` (`DoseBlocks.isAllowed`); `dotsPerPad`
null or 1..6; when both are set `dotsPerPad·dotSteps ≥ 800` (R6); `calAreaMm2`, `massPerPadMg` null or > 0. The tip
minimum is checked where the tip is known (`PadTypeEndpoints.apply`, the planner, §6.5).

**Status evidence** (`PadTypeEndpoints.apply`, engine-side, not only the browser): a version with status
`characterized` whose `dotSteps` is set and differs from the profile's dose is refused (409 `status-evidence`) unless a
fresh passing optical record and a confirmed mass of that dose exist: `Chip 0805 uses one-turn dots, which have no fresh
camera check on the blank and no confirmed weighing at this recipe: run the ladder and Weigh dots (Calibrate) first.`
`validated` for such a type needs the type's previous version with the same `dotSteps` and `dotsPerPad` at
`characterized`. `DispenseService.applyPadCalibration` (§7.7) is the other writer. Type versions already make plans
stale (D/Interlocks.java:106-110), so the dose lives on the type, not in `pad_rules` (rules are invisible to staleness,
D/PadTypeResolver.java:27-37).

### 5.2 `package_pad_type`: per-pad mappings

- New column `pad_names TEXT` (comma-joined, natural-sorted pad names; null = the whole package).
- `DispenseDb.mapPackage(String packageId, List<String> padNames, String typeKey)`; the existing two-argument method
  stays (null names). The current mapping of a key `(package_id, pad_names)` is its highest version.
- `packageMappings()` → `Map<String, String>` keyed `packageId` (whole) or `packageId + "#" + names` (per pad).
- **Resolver** (D/PadTypeResolver.java:54-79, gains `padName`): `resolve(packageId, padName, x, y, area)` and BI's
  `explain(...)`: (1) a per-pad mapping whose names contain `padName` → rule `pad-map`; (2) the whole-package mapping →
  `package-map`; (3) pattern; (4) centre rule. The old signature calls with `padName = null` (skips step 1).
  `fingerprint()` includes the per-pad keys. `BoardPadExtractor` passes `fpad.getName()` (D/BoardPadExtractor.java:75)
  and the layout's `"1","2",…` names (:110-115).
- BI's inspector sentence for `pad-map` (BI §5.7 table): `Mapped to pads {names} of this package in Profile (pad type {name}, v{n}, {status}).`

### 5.3 New tables (append-only, triggers as D/DispenseDb.java:148-153)

```sql
CREATE TABLE IF NOT EXISTS dose_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('optical','coupon','mass')),
  steps INTEGER NOT NULL, n INTEGER NOT NULL,
  -- optical / coupon: what the camera saw
  found INTEGER, misses INTEGER, hops INTEGER, threads INTEGER, hop_mm REAL,
  area_mean_mm2 REAL, resid_cv REAL, diameter_mean_mm REAL, diameter_cv REAL, diameter_p95_mm REAL,
  phase_json TEXT, drift REAL, landing_mean_mm REAL, landing_sd_mm REAL, dots_json TEXT, pass INTEGER, why TEXT,
  coupon_place TEXT,                     -- coupon: the place; pending until a mass row names it
  -- mass: a reading pair of a coupon record
  coupon_record_id INTEGER, full_mg REAL, empty_mg REAL, total_mg REAL, mg_per_dot REAL, mg_per_step REAL, mg_se REAL,
  weigh_delay_s INTEGER, confirmed_by INTEGER,
  -- conditions (§1.7)
  pressure_psi REAL NOT NULL, dose_fp TEXT NOT NULL, tip_key TEXT NOT NULL, paste_key TEXT NOT NULL,
  regulator_psi REAL, regulator_at_ms INTEGER, paste_life_at_ms INTEGER,
  controller_connected_at_ms INTEGER, start_angle_set_at_ms INTEGER, start_angle_deg REAL,
  method TEXT NOT NULL,                  -- auto | manual | typed | sim
  simulated INTEGER NOT NULL DEFAULT 0, run_uuid TEXT, job_id TEXT, notes TEXT, operator TEXT);
CREATE TABLE IF NOT EXISTS pad_calibrations (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, job_id TEXT NOT NULL, type_key TEXT NOT NULL,
  version_from INTEGER, version_to INTEGER, outcome TEXT NOT NULL,   -- characterized | provisional | stopped | cancelled | failed
  steps INTEGER, dots_per_pad INTEGER, mass_per_pad_mg REAL, checks_json TEXT NOT NULL, evidence_json TEXT, runs_json TEXT,
  simulated INTEGER NOT NULL DEFAULT 0, message TEXT, operator TEXT);
```

`D/DoseRecord.java` (with `whyStale(now, cfg, epochs)`, §1.7), `D/PadCalibrationRecord.java` (Gson-friendly),
`DispenseDb.insertDoseRecord`, `doseRecords(steps, sinceMs)`, `pendingCoupons(sinceMs)` (coupon records with no mass row
naming them), `insertPadCalibration`, `padCalibrations(typeKey, limit)`. The existing `calibration`, `dot_ladder`,
`scale_entries` tables and their endpoints stay for the manual history (D/DispenseDb.java:101-113, 134-138).

### 5.4 Migration and versioning

- `SCHEMA_VERSION = 2` (D/DispenseDb.java:27). After `createSchema()` (:83-156) and before `seedIfEmpty()`,
  `migrate()` runs in one transaction:
  1. `v = SELECT MAX(version) FROM schema_info`; if `v >= 2` return;
  2. for each new column: `PRAGMA table_info(<table>)`, `ALTER TABLE <table> ADD COLUMN <col>` if missing (an `ALTER`
     does not fire the append-only `BEFORE UPDATE` triggers);
  3. `DROP VIEW IF EXISTS pad_types_current` and create it again (D/DispenseDb.java:139-141);
  4. create the two tables and their triggers; add `dose_records`, `pad_calibrations` to `TABLES` (:29-31);
  5. `INSERT INTO schema_info(version, created_at) VALUES (2, now)` (schema_info is append-only: a new row).
- **Backup**: for a file database at version 1, copy `dispense.db` to `dispense.db.v1-<yyyyMMdd-HHmmss>.bak` before
  step 2 (the database is the audit record; the real machine has one).
- A fresh database creates v2 directly (seeds get `origin = 'seed'`, the other new columns null).
- **`DispenseConfig`** (`dispense-config.json`, schema unchanged: new nested objects with defaults, Gson ignores
  unknown fields; D/DispenseConfig.java:504-576):
  - `tip` (`D/TipSpec`): `key "ad22tta-b"`, `name "22G tip"`, `idMm 0.41`, `odMm 0.72`, `minDotSteps 400`; set with
    `PUT /api/config`; freshness uses `needleChangedAtMs` (the existing "I changed the needle" press, W/setup.js:483,
    :520).
  - `paste` (`D/PasteSpec`): `key "gc50-t5"`, `name "Loctite GC50 SAC305 T5"`, `lot null`, `loadedAtMs null` (stamped by
    `POST /api/paste`, the "New paste loaded" press, §13.6).
  - `calibrationArea` (`D/CalibrationArea`): `surfaceName "blank"`, `x0Mm, y0Mm, widthMm, heightMm` (null = the default:
    40 × 20 mm from the blank place `cfg.blankLocationName`, moved to stay ≥ 1.5 mm from every purge spot), `usedMm 0`,
    `resetAtMs` (both stamped); `isDefined()`, `effective(cfg)`, `leftMm()`, `reserve(heightMm)`.
  - `padCalibration` (`D/PadCalibrationSettings`): `maxResidCv 0.13`, `maxHalfSplit 0.13`, `maxQuarterAmplitude 0.20`,
    `massTolLow 0.25`, `massTolHigh 0.10`, `confirmTolerance 0.05`, `controlTolerance 0.15`, `weighTargetMg 70`,
    `doseRecordValidHours 8`, `readingConfirmAfterMin 15`, `couponPlaces ["coupon1", "coupon2"]`, `couponSizeMm
    [40, 30]`, `couponMarginMm 3`, `couponWindowMm 0.3`, `flushDots 4`, `ladderDotsPerPhase 12`, `minLadderDots 28`,
    `minHops 60`, `threadRateOk 0.03`, `missRateOk 0.005`. Code constants (`P/Limits`): the §1.6 limits, the string and
    thread rules (§7.4), drift 10 %, landing 0.15/0.08 mm, weigh dots 30..200.
  - Validation in `DispenseConfig.validate()` (D/DispenseConfig.java:604): the defined area ≥ 1.5 mm from every purge
    spot and ≥ 3 mm from every coupon place's rectangle; tip `minDotSteps` in R1; settings in range. **Off the SIM**
    (`PUT /api/config`, §12.1, where SimGuard is known): `ladderDotsPerPhase ≥ 12`, `minLadderDots ≥ 28`, `minHops ≥
    60`, `weighTargetMg ≥ 50`.
  - `PROTECTED_NESTED` (A/DispenseEndpoints.java:56-57) gains `calibrationArea.usedMm`, `calibrationArea.resetAtMs`,
    `paste.loadedAtMs`; `NESTED_SETTINGS` (:48-49) gains the four objects.
- **`DispenseProfile`** (D/DispenseProfile.java): `doseFingerprint()` = `psi suck rpm acc dwell gap` from
  `fingerprint()` (:129-133) without net/start/purge; `phaseSteps(cfg, restCount, dotsAfter, stepsPerDot)` (the
  existing 3-argument method calls it with `netStepsPerDot`, :119-126); `sustainedFeedMgS 2.6` (R7, §1.1).

---

## 6. Plan, stream, validator, interlocks (per-type dose)

### 6.1 Planner (`D/DispensePlanner.java:53-153`)

- Per typed, unblocked pad: `s = t.dotSteps ?? profile.netStepsPerDot`; `n = t.dotsPerPad`, else today's
  `DotSizer.layout` (:112); positions `DotGeometry.layoutN(w, h, n, pMin, rDot)` for types with their own `dotSteps`
  or `dotsPerPad` (§4.3 step 3), today's DotSizer positions for the others. `PlannedPad` gains `Integer dotSteps`, `int dotsPerPad`,
  `int startPhase`; `PlannedDot` gains `int netSteps`, `int block`, `int padIndex`; `rotationDeg = netSteps /
  bStepsPerDeg` (D/DispensePlan.java:64-65 becomes "the dot's dose").
- **Blocks**: dots grouped by `netSteps`; block order = the profile's dose first if present, then descending steps.
- **Order inside a block (R5)**: `PathOrder.order` (D/DispensePlanner.java:144-150; D/PathOrder.java:3-5) runs over the
  block's **pads** (their centres) from the end of the previous block (block 0 from `planStart()`,
  D/DispenseService.java:581-588; block k from the purge spot its switch will use); each pad's dots follow in a
  nearest-first serpentine from the previous pad's last dot. With one dot per pad this is today's order (golden test,
  §14.1). Indices run across blocks; `startPhase` = `floorMod(blockStartAngle + stepsBeforeInBlock, 1600)`.
- `DispensePlan.blocks`: `[{index, netSteps, turn, dots, pads, firstDot, lastDot, phaseDot, flushDots, demandMgS}]`
  (`demandMgS` filled by the estimate); the summary adds `· 3 dot sizes: half turn ×120, one turn ×64, quarter turn ×24`.
- `targetMassMg` keeps its meaning (stencil print, informational). Predicted mass, per-pad ripple and dot size per pad
  are added in the view (§12.5), not stored in the plan.

### 6.2 Builder (`D/GcodeStreamBuilder.java`)

- One implementation for board plans and calibration blocks: `forBlocks(cfg, mapper, List<BlockSpec> blocks,
  List<DotSpec> purgeSpots, int flush, boolean bZeroed, boolean keepalive, Long bCount, double floorRefZ)`;
  `forPlan` (:166-200) builds its `BlockSpec`s from consecutive equal `netSteps` in `plan.dots` (so a
  `PlanStore.subset`, A/PlanStore.java:90-170, that keeps order keeps blocks).
- Program: session start, travel, pressurize; **initial purge**: `profile.purgeDots` spots with block 0's dose, the
  first one `phaseSteps(c, rest, purgeDots − 1, s0)` (today's rule, :333-344, generalised); for each further block,
  travel at travel Z to the next purge spot, a phase dot `phaseSteps(c, rest, F, s)` if the dose is partial, F flush
  dots of `s`, then the cross to the block's first dot without stopping; every work dot `b.dot(spec, d.netSteps)`
  (replaces :194). End session as today.
- `GcodeProgram` (D/GcodeProgram.java) gains `List<BlockInfo> blocks` (netSteps, firstDot, restCountAtStart,
  startAngleDeg, seconds), `long[] dotStartCount` (by work-dot index; null when the count is unknown), `Line.netSteps`
  and `Line.padIndex` (`int`, set on `DOT_BEGIN`; −1 elsewhere); `purgeDotCount` counts every purge-row dot.
- `partial` anywhere (not the profile) decides whether a missing count refuses (:329-332 and
  D/DispenseService.java:768 use `DoseBlocks.anyPartial(blocks, cfg)`).

### 6.3 `D/DoseBlocks.java` (new, pure)

`isAllowed(steps, cfg)` (the profile's own `netStepsPerDot`, which keeps today's rule, D/DispenseProfile.java:141-146;
or a per-type dose in {400, 800, 1600} at or above `tip.minDotSteps`), `isPartial(steps, rev)`, `blocksOf(List<PlannedDot>)`, `anyPartial(...)`,
`purgeSpotsNeeded(blocks, profile, flush) = purgeDots + Σ_{k≥1} (flush + (partial ? 1 : 0))`, `turnName(steps)`,
`demandMgS(block, mgPerStep, seconds)`.

### 6.4 Validator (`D/GcodeStreamValidator.java:71-175`): new B rules

1. Track the absolute B targets in steps from each `G92 B0` (`round(value × bStepsPerDeg) × bExtrudeSign`).
2. In every dot (DOT_BEGIN..DOT_END): net = the rest after the back move − the rest before; for work dots (index ≥ 0)
   `net == Line.netSteps` and `DoseBlocks.isAllowed(net, cfg)`.
3. Purge, phase and flush dots: `0 < net ≤ 1600 + 89` (one revolution plus the 20° minimum phase turn,
   D/DispenseProfile.java:29-30, :119-126).
4. At each block start of a partial dose, when `bCountAtStart` is known: `floorMod(bCountAtStart + rest, 1600) ==
   profile.startAngleSteps(cfg)`. With rule 2 this fixes every work dot's start angle, so the per-pad phases the plan
   predicts are the ones dispensed.
5. Two consecutive work dots with different `netSteps` must have a block start between them.
6. A pad's work dots are consecutive (`Line.padIndex` runs never resume).
7. The program's total net B ≤ Σ planned work steps + purge-row dots × 1689.

Texts: `line {n} '{s}': dot {i} turns {net} steps, its plan says {planned}`, `… {net} steps is not a quarter, half or
whole turn (1600 steps)`, `line {n}: purge dot {i} turns {net} steps, more than one revolution and the 20° phase turn`,
`block {k} ({turn}) starts at {a}°, not at the start angle {b}°`, `dots {i} and {j} have different turns without a
block start (phase dot and flush) between them`, `pad {p}'s dots are split (dots {i} and {j})`, `the program turns the
screw {x} steps; the plan allows at most {y}`.

### 6.5 Interlocks (`D/Interlocks.java`)

- **I9** (:385-396): needs `purgeSpotsNeeded(...)` spots (not `profile.purgeDots`); text `only {left} unused purge
  spot(s) left, {need} needed ({purge} to start and {per} for each further dot size): wipe the purge row and reset
  it`. It applies to board runs and to calibration blocks (`in.purgeSpots > 0`).
- **I11** (:407-417): when any block is partial (`in.partialTurn`), for board runs and calibration blocks (today only
  inside `if (in.boardRun)`, :384).
- `Inputs.purgeDots` (D/DispenseService.java:713) becomes `DoseBlocks.purgeSpotsNeeded(plan…)`; `boardScope`
  (:627-646) and `purgeSpecs(c, n)` (:595-611, the spots' rotation is replaced by the block's dose) supply that many
  spots; the estimate (`DispenseEndpoints.estimate`, A/DispenseEndpoints.java:637) passes them and the blocks' seconds.
- **PLAN** (refusals, not flags):
  - a planned dot whose `netSteps` is not allowed for the tip: `pad type {key} v{n} has {turn} dots; the {tipName}
    takes quarter, half or one-turn dots ({minTurn} at least): edit it and plan again`;
  - a pad breaking R6: `pad type {key} v{n} gives each pad {T} steps ({turn}); every pad needs at least half a screw
    turn in a row, or its paste varies more with the screw angle than the validated 0603 pads: use more dots or a
    larger turn`.
- **I6-feed** (R7): a block whose `demandMgS > profile.sustainedFeedMgS`: `block {k} ({turn}) asks the screw for {x}
  mg/s; the most it was seen to keep up with at {psi} psi is {y} mg/s: lengthen the dwell or use more, smaller dots`.
- Unchanged: `refuseFlaggedPads`, the gate, staleness (types carry the dose, §5.1).

---

## 7. Calibrate automatically

### 7.1 Shape: a job of engine steps and one operator step

Each engine task is one job on OpenPnP's machine thread under the `busy` flag (D/DispenseService.java:1097-1160), and
no task can wait for the operator (AR has none; its only chaining is the plan-again callback, AR §5.3). So:

- **`A/PadCalibrationJob`** (API layer, like `PlanStore`, A/ApiContext.java:21-23) runs a list of steps. Engine steps are
  ordinary engine tasks (kinds `pad-ladder`, `pad-weigh`, AR's `surface-probe`), each its own pressure session that ends
  vented (D/GcodeStreamBuilder.java:408-430). The one operator step (the readings) comes after the last engine step,
  with the head parked and the pressure off.
- It moves on from `DispenseListener.onFinished` (registered in `ApiContext.attach`, A/ApiContext.java:66-77; `busy` is
  cleared before listeners run, D/DispenseService.java:1175-1181), handing the next submit to its own single-thread
  executor. The submit happens under the job's lock after checking its stop flag (§7.8).
- One job at a time: `A/PadCalibrationJobs` in `ApiContext`. While a job is `running`, `deciding` or `planning`,
  `ctx.requireEngineIdle` (A/ApiContext.java:88-92) also refuses other dispense tasks: `a pad calibration is running
  ({step}): wait for it or press STOP` (this covers the gaps between its engine steps). At `awaiting-reading` the lock
  is released; only the coupon places stay reserved (§7.5).

### 7.2 Steps

| # | step | kind | when | what |
|---|---|---|---|---|
| 0 | preflight + save | sync | always | §12.2's checks; then the suggestions are saved as provisional types + mappings (§12.1 `apply`) |
| 1 | blank | engine `surface-probe` (AR §5.7) | the area's surface is missing, its points predate the last home or controller connect, or the area's corners lie outside its hull + 1 mm | homes if needed; targets = the purge-row targets (AR `ProbeTargets.forRow`) ∪ the area's corners inset 1 mm + its centre; every target is looked at before the touch (AR §4.5) |
| 2 | coupons | engine `surface-probe` per coupon place, `frameCheck false`, window ±0.3 mm around the place's z | a weighing is planned | camera look first: `BareSpotCheck` (V/BareSpotCheck.java:8-33) at the weigh grid's four corners pushed out by `couponMarginMm` and at its centre: all bare, reference colour within 25 of the blank's; then 3 N1 touches 6 mm inside the coupon's corners |
| 3 | ladder | engine `pad-ladder` | always (the control); plus every dose of the chosen plans that lacks a fresh passing optical record or enough hops | home if needed (AR §2.3), Z check (AR §2.4), initial purge 8, the control block (profile dose) first, then one block per dose (descending), imaged before and after (§7.4) |
| 4 | weigh dots | engine `pad-weigh` | some chosen dose has no confirmed mass (§7.5) | purge 8, then per coupon its grid of one dose, imaged before and after; a `coupon` record per coupon; ends parked at travel Z over the next purge spot |
| 5 | readings | operator | after step 4 | per coupon: weigh, wipe, weigh again (§7.5); a `mass` record per coupon |
| 6 | decide | sync | always | §7.6–7.7 over every fresh record (pooled) |
| 7 | plan again | sync | the job was started from a plan | `A/PlanAgain.of(ctx, entry)` (§12.1), the same helper `apply` uses; `result.newPlanId` |

- **"All"** = one job for every group whose suggestion kind is `new` or `calibrate-existing` (`use` groups are excluded:
  they have "Use the matching pad types"). The ladder measures the union of their doses (a blank dot does not depend on
  the pad; the hop is the shortest of the groups sharing the dose), the weighing covers up to `couponPlaces.size()`
  doses, the decision runs per group.
- **Weigh allocation** (2 coupons): doses needing a confirmed mass, ordered by the number of groups that chose them,
  then ascending steps (the less-known small doses first). None → no coupons. One dose `s` → coupon 1 = s; coupon 2 = the profile dose if it has no
  confirmed mass (the control's weighing, §1.3), else s again. Two doses → one each (they confirm each other by mass per
  step, P1). More → the first two; the rest stay provisional with `not weighed in this job (2 coupons): Calibrate again
  weighs the {turn}`.
- If step 3 changes a group's chosen dose (§7.6), step 4 weighs the new choice.

### 7.3 Engine task `DispenseService.runDoseBlocks(DoseBlocksRequest)` → `Future<DoseBlocksResult>`

```java
public final class DoseBlocksRequest {     // D/DoseBlocksRequest.java
  public String kind;                      // "pad-ladder" | "pad-weigh"
  public List<Block> blocks = new ArrayList<>();
  public boolean image = true;             // before and after frames
  public Double areaReserveMm;             // calibration-area height to stamp right before streaming (ladder)
  public long stopEpoch;                   // DispenseService.stopEpoch() when the job built the request (§7.8)
  public boolean parkAfter = true;         // travel Z over the next purge spot at the end
  public static final class Block {
    public int netSteps; public String label; public double hopMm;
    public String surfaceName;             // the dots' Z: the calibration area's surface, or the coupon place's own plane
    public String couponPlace;             // pad-weigh: the coupon (bare check, reservation, record)
    public List<double[]> xy = new ArrayList<>();   // in dispense order
  }
}
public final class DoseBlocksResult {      // D/DoseBlocksResult.java
  public RunResult run; public HomeReport home; public ZCheck.Result zCheck; public String stoppedStage;
  public Long bCountAtStart; public final List<BlockOut> blocks = new ArrayList<>(); public final List<Crop> crops = new ArrayList<>();
  public final List<String> refusedSpots = new ArrayList<>();   // "x,y: <BareSpotCheck reason>"
  public static final class BlockOut { public int netSteps; public String label, couponPlace; public final List<DotOut> dots = new ArrayList<>(); }
  public static final class DotOut { public double x, y; public Long startCount; public Integer phase; public Dot found;
                                     public Double dxMm, dyMm; public boolean threadToNext, string; }
  public static final class Crop { public String id; public int block, dot; public byte[] beforePng, afterPng; }
}
```

Flow (label `pad calibration` for `ctx.tracker.expect`, A/RunTracker.java:47-63):

1. refuse if `stopEpoch() != req.stopEpoch` (`STOP was pressed after this step was queued`); enabled and pressure-hold
   refusals (AR §5.2), `Session`, `homeIfNeeded`, `zCheckIfNeeded` (AR §2.3–2.4);
2. per block `plane = planeOf(c, surfaceName)`, else REFUSED `I4: no {surface} surface: probe it first`; work specs at
   `plane.z + needleZOffsetMm + gap` (as D/DotLadderCalibration.java:40-55); coupon planes are checked floor-only
   against their own place z (`needleFloorZMm(placeZ)`, D/DispenseConfig.java:456-465; AR §16 item 1);
3. purge spots = `purgeSpecs(c, purgeSpotsNeeded)`; scope: the surface under every work dot, the purge row, I9, I11,
   I6-feed (`Session.preflight`, D/DispenseService.java:1270-1278, with `purgeSpots`/`partialTurn` set, §6.5);
4. estimate program → preflight with its pressurised time (I7);
5. **before frames per tile** (§7.4), camera only; **every work-dot spot must pass `BareSpotCheck`** in its tile (no
   paste, no edge, no foreign object: a slid blank or an old dot fails); else REFUSED `the {area|coupon} is not clean or
   not flat under {n} of the dots (first at X {x}, Y {y}: {reason}): wipe it, or move the calibration area
   (Calibrate › Pad calibration)`, with `refusedSpots`;
6. check `stopEpoch()` again; `runSegment` (D/DispenseService.java:1289-1321) with `forBlocks(..., readBCount())` (a
   missing count with a partial block refuses with today's I11 text, :768-772); `beforeStream` reserves the purge spots
   (:889-903) and the area;
7. after frames per tile; `imager().inspectAll(before, after)`; per spec the nearest dot within half the pitch
   (`TestDotCheck.nearest`); `startCount` from `GcodeProgram.dotStartCount`; `threadToNext` from
   `PadLadder.threadBetween` (§7.4); 3 crops per block (the first accepted, the median, the worst by area), 3 × 3 mm,
   96 px, PNG;
8. park; phases `pad calibration: dispensing block 2 of 3 (quarter turn)`; `onDetail` (AR §5.1) with `{stage, block,
   blocks, dot, dots, estimatedSecondsLeft}` after each dot and tile.

`DotImager` (D/DotImager.java) gains `default List<Dot> inspectAll(Frame b, Frame a)` (inspect with minCircularity 0 and
maxDiameterMm 3.0, V/DotInspectorSettings.java:10-21, so strings and big dots are classified, not dropped) and
`default double[] fieldOfViewMm()` (null = unknown → one frame per dot, D/DispenseService.java:1339-1359).

### 7.4 Ladder layout and statistics (`D/PadLadder.java` layout and frames; `P/LadderStats.java` statistics, pure)

- **Dots per dose**: `N = max(minLadderDots, ladderDotsPerPhase × phases, 61 − hops already pooled or planned on the
  coupons)` with phases = 1600/s: quarter 48, half 28, one turn 28. The control (profile dose) is 12 dots when the
  profile dose is not itself calibrated in this job; they count toward its record.
- **Pitch**: along a row `p = clamp(hopMm, d95Hi + 0.3, 2.5)` (the group's own pad-to-pad move, so a thread forms as it
  would on the board; at least 0.3 mm between dot edges for the camera; at most 2.5 mm); rows `max(p, d95Hi + 0.5)`
  apart, serpentine; every dot inset `d95Hi/2 + 0.5` mm from the area's edges; one row pitch between doses; the
  reserved height includes the insets.
- **Tiles**: with a field of view (1920 × 1080 at 0.0107 mm/px ≈ 20.6 × 11.6 mm, AR §3.3), greedy tiles along each row
  with every dot ≥ 1.0 mm inside the frame; else one frame per dot.
- **Per dot**: found or miss; landing = found − commanded; phase k = `floorMod(startCount − startAngleSteps, 1600) / s`;
  `string` = circularity < 0.35 or area > 1.8 × the dose's median of round dots; **thread to the next dot** = more than
  15 % of the pixels of a 0.3 mm wide corridor between the two dots' edges differ from the before frame by more than
  BareSpotCheck's colour delta (20), or both spots fall inside one found blob; a string counts as a thread. Every move
  between consecutive dots of one dose is a hop.
- **Per dose**, on every fresh record of the dose pooled (failed ones included), the first 2 dots after a cross
  excluded from repeat, phase and drift (PT/lessons.md:15; reported under P7):

  | check | statistic | limit | why / operating point (scratch `/tmp/pc_rev/stats.py`) |
  |---|---|---|---|
  | transfer | misses in N dots | ≤ k95(N, 0.5 %) (N 60 → 1, 180 → 3) | bench 0 in 240 board dots |
  | threads | threads in H hops, H ≥ `minHops` 60 | ≤ k95(H, 3 %) (60 → 4, 90 → 6, 180 → 9) | passes 97 % at a 3 % rate, 14 % at 12 %, 0.02 % at 25 %; the sixth threaded on ~50 % of parts |
  | repeat | residual area CV after removing the phase harmonic; upper bound `cv·(1 + 1.645/√(2N))` | ≤ 0.13 | bench ≈ 0.06 (half), 0.054 (whole): fails < 1 %; at CV 0.08 < 6 %; at 0.12 most runs fail |
  | phase, half | `|mean(φ0) − mean(φ1)| / (mean(φ0) + mean(φ1)) + 1.645·SE` | ≤ 0.13 | bench 0.06 (PT/todo.md:94, :96): passes; twice the bench split fails |
  | phase, quarter | least-squares first-harmonic amplitude of area vs phase `+ 1.645·SE` | ≤ 0.20 | predicted 0.10: fails 0.1 % at CV 0.10; 1.5× fails 2 %; 2× fails 98 % |
  | drift | OLS slope over the dose's dots × (N − 1) / mean | fail if > 10 % and > 2 SE | a flat run fails ≈ 1 %; a 20 % trend fails 95 % (starving screws trend, mem `spd_A..D`) |
  | landing | |mean offset|, sd | ≤ 0.15 mm, ≤ 0.08 mm | bench 0.057–0.12 mm; a fail points to the needle offset, not the dose |
  | size | d̄, d95 = d̄·(1 + 1.65·CV_d); P3 band | used in §7.6 | – |
  | control | the profile dose's median area vs the bench 1.057 mm² (±15 %) and vs its newest fresh passing record (±10 %) | inside | outside → the job stops before the coupons: `the control half-turn dots are 0.82 mm², 22 % below the bench's 1.06 mm²: check the regulator (18 psi) and the paste before calibrating` |

- Every dose writes a `dose_records` row `kind optical` with the epochs of §1.7, pass or fail.

### 7.5 The weighing

- **Why not shots in the cup.** `runShots` stacks every shot at one XY (D/DispenseService.java:936-940): a half-turn dot
  is ≈ 0.4 mm tall (PT/todo.md:98) while the needle works 0.05–0.15 mm above the surface (D/DispenseProfile.java:53-54),
  so from the second shot on the needle sits in the pile.
- **Coupons.** Two pieces of blank PCB with the blank's solder mask (so the camera sees the dots and the bare check
  works), at least 40 × 30 mm and 3 g (N1's vacuum touch must not lift one; parts stay on a vented tip,
  mem:lumen-calibration-state), each on a named place (`coupon1`, `coupon2`, Setup › Places; the place's z is its top,
  an N1 contact Z in OpenPnP's frame). The operator lays them, clean, before Start (§7.9).
- **Count**: `N = clamp(ceil(weighTargetMg / m_pred(s)), 30, 200)` rounded up to a multiple of the dose's phases, so
  `N·s ≡ 0 mod 1600` and the first-harmonic ripple cancels in the total: quarter 136, half 68, one turn 34 (≈ 70 mg).
- **Layout**: serpentine rows at the dose's hop pitch (§7.4), centred on the place, every dot ≥ `couponMarginMm` (3) +
  d95/2 inside the coupon's nominal rectangle (quarter: 136 dots at 2.0 mm need 30 × 20 mm of the 34 × 24 mm inside the
  margin: 136 quarter-turn dots at 2.0 mm are 8 rows of 17, 32 × 14 mm of dot centres inside the 33 × 23 mm a 40 × 30 mm
  coupon leaves); step 2's camera look proves the coupon is there with that margin. The weigh dots are imaged too, so they count
  toward transfer and threads (136 quarter dots = 135 hops).
- **Readings (weigh, wipe, weigh again; PT/todo.md:97)**: per coupon two absolute readings, the coupon with paste and the
  coupon wiped clean; `total = full − empty`; no tare, so an auto-zeroing or auto-off balance cannot corrupt it.
  Readability 1 mg each → σ_net ≈ 1.4 mg ≈ 2 % of 70 mg (`mg_se` = 1.4 / N mg per dot).
- **Plausibility**: expected = found dots × m_pred; outside 0.5–2× → 409 `implausible` (`You typed {g} g − {g2} g = {mg}
  mg; about {exp} mg was expected for {N} {turn} dots. Check the unit and that the wiped coupon was weighed second, then
  save again to keep it.`; a second identical answer with `confirm: true` is accepted).
- **Delay**: a reading more than `readingConfirmAfterMin` (15) after the weigh dots needs a confirm (`The coupon waited
  {m} min: the flux dries, so the reading may be low. Save it anyway?`); the record keeps `weigh_delay_s`. Readings are
  accepted until the coupon record would be stale (§1.7).
- **Confirmation (P1, P2)**: a dose's mass is **confirmed** when two fresh readings agree within ±5 % in mass per step
  and at least one of them is of that dose (two coupons of one dose, or one coupon each of two doses: positive
  displacement says the mass per step is the same). `confirmed_by` names the other record. One reading alone leaves the
  types provisional: `the amount was weighed once; a second weighing (another coupon or another job) confirms it`.
- **Reservation**: while a coupon record is pending (dots laid, no mass, fresh), engine tasks that dispense or probe at
  that place refuse: `coupon1 carries 136 dots waiting to be weighed: type the readings or skip them (Calibrate › Pad
  calibration)`.
- **Prompt** (`#pcal-ask-text`, exact): `The dots are laid and the head is parked over the purge row. For each coupon:
  put it on the balance and type the reading; wipe the paste off with a lint-free wipe and isopropyl alcohol, let it
  dry, put it back on the balance and type the second reading. Coupon 1 has 136 quarter-turn dots (about 0.072 g);
  coupon 2 has 68 half-turn dots (about 0.072 g). Waiting for you since {HH:MM}.` Fields per coupon `With paste (g)` and
  `Wiped (g)` (3 decimals), the echo `= {mg} mg of paste`, `Save the readings`; `Skip weighing (the types stay
  provisional: boards still refuse these pads)`.
- **Connected balance** (SIM `connected`, a future serial driver): the job reads both values itself after the human
  steps (in the SIM the emulated human moves and wipes the coupon, §11.6).
- The readings write `dose_records` rows `kind mass, method typed|sim`, `mg_per_dot = total / N`, `coupon_record_id`.

### 7.6 The decision (`P/Decider.java`, pure)

Per group, with the pooled fresh evidence of every dose (all fresh records, pass or fail, statistics recomputed on the
pooled dots, so a repeat cannot erase a failure):

1. **Candidates**: the chosen plan (your edited plan for `calibrate-existing`, §4.2) and the alternatives whose doses
   have pooled optical evidence. Re-run §4.3 steps 3–8 with the measured d̄ (overhang), d95 (gaps and same-pad pitch)
   and the confirmed mass per dot (else predicted). The chosen plan stays unless it fails a check below and an
   alternative passes; then the result says so (`your 2 half-turn dots reach 0.24 mm past the pad: calibrated 1 one-turn
   dot instead`).
2. **Checks for `characterized`** (each one line with predicted / measured / limit):
   - the dose's optical checks pass on the pooled evidence (transfer, threads with ≥ 60 hops, repeat, phase, drift,
     landing; §7.4);
   - the dose's mass is confirmed (§7.5);
   - mean per pad `n·m̂` within −25 % / +10 % of `m_target`;
   - R5/R6 hold; the per-pad spread `100·a·sinc(T/2)` with the measured a is reported;
   - §1.6 on every opening of the group with its neighbours' own dots, the overhang with the measured d̄ and the gaps and
     same-pad pitch with the measured d95 (the marginal band does not pass);
   - the control passed (§7.4);
   - the tip key is the current tip; the regulator attestation was given (§12.2);
   - the type is not `validated` already, unless the before-start view named it (`Chip 0805 is validated; calibrating
     it again makes it characterized until you validate it again`).
3. Fallback plans (§4.3 steps 6 and 8) never reach `characterized`: they stay provisional with `the closest amount that
   fits is {err} %`; the result adds `you can raise it by hand after a test board` for profile-dose plans.

### 7.7 What gets written

- **All checks pass**: a new type version with status `characterized`, `dotSteps`, `dotsPerPad`, `tipKey`, `doseFp`,
  `calAreaMm2`, `massPerPadMg = n·m̂`, `origin "auto-calibrated"`, notes `Calibrated automatically {date} on the blank
  and {coupons} weigh coupons: {n} {turn} {dot|dots} per pad, about {mpp} mg per pad (target {t} mg). Job {id}.`,
  written only through `DispenseService.applyPadCalibration(PadCalibrationRecord)` (engine-side rule: it re-checks every
  `checks_json` entry is a pass; nothing in the job writes `validated`; a unit test asserts both).
- **Some check fails, or skipped**: no new version if the plan did not change; a new **provisional** version when the
  measured plan differs from the saved one (so the editor shows what was measured). The reasons go into
  `pad_calibrations.checks_json` and the result card.
- Always: one `pad_calibrations` row per group (with `evidence_json`: the record ids pooled), the dose records, `PCAL`
  events with the step JSON (like AR's REG/PROBE, AR §5.4). Mapping writes happened at step 0.

### 7.8 STOP, cancel, failures

| when | effect | kept |
|---|---|---|
| STOP at any time | `MachineEndpoints.stop` (A/MachineEndpoints.java:75-113) **first** calls `ctx.padCalJobs.stop("STOP pressed in the web UI")`, which sets the job's stop flag under the job's submit lock, then `svc.abort` as today. `DispenseService.abort` (:503-509) also increments a **stop epoch**; every task records `stopEpoch()` when it is queued and refuses at its start and before streaming if it moved. So a step queued in the gap between two steps (when `abort` finds no executor, :505-508, and STOP's grace branch is skipped, :97) never pressurises. Job `stopped`; Recovery opens (AR §8.1 state 4) | records of finished steps; pending coupons stay pending |
| `Cancel` during an engine step | the same as STOP for the engine (`svc.abort`); job `cancelled` | as above |
| `Cancel` or STOP at the readings | the job ends; nothing moves | the blank and coupon records; the result offers `Type the readings` (the pending coupons, until stale) and `Finish the calibration` |
| engine REFUSED (I4, I9, I11, I6-feed, MACHINE, PROBE, bare check, …) | job `failed` with the refusal; the fix-its of the code (AR §8.2, W/core.js:2418-2446) | as above |
| ERROR | job `failed`; Recovery | as above |
| OpenPnP restarts mid-job | the job is lost (memory); `GET /api/coupons/pending` still finds the pending coupons (DB), and a new start reuses every fresh record, so it resumes where it stopped | records |

`Finish the calibration` is an ordinary start: with every record fresh the preview has no engine step (`Nothing to
dispense: the blank and coupon results from 14:05 are still fresh`), and the job goes straight to decide and plan.

### 7.9 Before-start summary (`GET /api/pad-calibrations/preview`; `#pcal-note`)

Computed from the steps (§7.2), the programs' estimates (`GcodeProgram.estimatedSeconds`), AR's home and Z-check
estimates (`status.machine.home.estimateS`, `machine.zCheck.estimateS`, AR §7.5), the blank-probe estimate (AR §7.4),
captures × 0.9 s, each coupon look and probe 1 + 3 × 17 s (AR §11 item 14). Example (groups J and Q of §14.3):

`Homes first (≈ 25 s) and checks Z (≈ 20 s). Checks and touches your two weigh coupons (≈ 1:45). Lays 76 test dots on
the blank (28 half-turn, 48 quarter-turn) in X 286.0–326.0 mm, Y 250.0–261.0 mm (9.0 of 20.0 mm left after this) and
looks at them with the top camera (≈ 1:10). Then lays 136 quarter-turn dots on coupon 1 and 68 half-turn dots on coupon
2 (≈ 2:50) and parks the head over the purge row. Then it needs you once: weigh each coupon, wipe it and weigh it again
(4 readings). About 0.22 g of paste, ≈ 6:30 before your readings.`

Then `You need: two clean weigh coupons on coupon1 and coupon2 (blank PCB, 40 × 30 mm, at least 3 g), the balance, a
lint-free wipe and isopropyl alcohol.`, the groups, the types it will save now (`Plans made before this go stale: it
plans again at the end.`), and any validated type that becomes characterized.

JSON: `{steps[], estimatedSeconds, pasteMg, blank: {x0, x1, y0, y1, usesMm, leftMm, leftAfterMm}, coupons: [{place,
steps, turn, dots, expectedMg}], purgeSpots: {need, left}, home: {...}, zCheck: {...}, probes: [...], groups: [...],
saves: [...], demotes: [...], needs: [...], refusal}`.

### 7.10 Result card (`#pcal-result`, first sentence machine-readable, UI_DESIGN.md:308-310)

- `Characterized {HH:MM}: {label} gets {n} {turn} {dot|dots} per pad, about {mpp} mg per pad ({e} % {less|more} than the {t} mg target); planned again.`
- `Not characterized {HH:MM}: {label} stays provisional: {first failed check in plain words}.` (e.g. `quarter-turn dots trailed a paste thread to the next dot 12 times in 182 moves (at most 9 allowed)`, `the amount was weighed once; a second weighing confirms it`, `the dots, up to 1.10 mm wide, leave 0.17 mm to the next pin (0.30 needed)`)
- `Stopped {HH:MM} during {step}: the blank results were kept{; the coupons still need weighing until {HH:MM}}.`
- `Failed {HH:MM}: {refusal}`.
- Then one `li` per group with a table (turn, dots per pad, mg per pad and target, dot size, residual CV, phase,
  threads/hops, mass readings, per-pad spread: predicted / measured / limit), `#pcal-crops` (3 crops per dose: before /
  after), `#pcal-pred` (P1–P8 rows with this run's values), and the control's drift sentence (§1.3).

---

## 8. I'll do it by hand

- The button first saves the suggestion as a provisional type + mapping (§12.1 `apply`, `choice: "save"`), then
  `IL.pcr.openGuide(t, {validate: false, suggestion})` (W/calibrate.js:623-635; the `(t, bool)` call keeps working) and
  goes to `#calibrate`. The guide's key adds the suggestion id (:605).
- Guide steps (`renderGuide`, :601-621) for a suggestion: (1) needle frame, blank place and surface, touch-off; (2) **Run
  the ladder** (`#btn-guide-ladder`, press2 `lay the ladder`): `POST /api/pad-calibrations {typeKeys: [key], steps:
  ["ladder"], doseSteps}`, the same phase-locked engine step, area and records as §7; the guide shows `table#pcal-ladder`
  with the §7.4 limits and pass or fail per check; (3) **Weigh dots** (`#wd-*` card: `wd-steps`, `wd-count` (§7.5),
  coupon places; press2 `lay the weigh dots`): `steps: ["weigh"]`, coupon check, probe, weigh dots and the same readings;
  (4) in Profile, the dots per pad and the turn (`#pt-dotsteps`, `#pt-dotsperpad`, §13.3) and the status. Target line:
  `{target} mg per pad; predicted {mpp} mg with {n} {turn} {dot|dots}.`
- The operator decides; the editor refuses `characterized` for an own-dose type without the evidence (§5.1) and says
  which record is missing.
- The existing Dot ladder card (forDots, no phase lock, D/GcodeStreamBuilder.java:206-241) stays for profile-dose
  history; its subtitle becomes `camera · records only (no phase lock)`. `Scale: shots` (`shotsCard`, :213-247) collapses
  into `details.disclose` `Older method: shots into a cup (records only)`.

---

## 9. Test-board ladder (deferred)

Deferred to a follow-up (both critiques: scope, and its variants were not run through the geometry, tip and phase
checks). `validated` stays on the existing Validate guide (W/calibrate.js:611-615) and the pad-type editor. When it is
built: variants pass §4.3's checks or are marked `may bridge` and need a confirm; only weighed variants can become
anchors (§1.3). Open question 8.

---

## 10. Balance interface

```java
public interface Balance {                         // D/Balance.java
  String kind();                                   // "manual" | "sim" (later "serial")
  boolean isConnected();                           // true: the job reads by itself
  double resolutionMg();                           // 1.0 for the U.S. Solid 110 g × 0.001 g
  Reading read(long timeoutMs) throws Exception;   // waits for a stable reading (absolute, no tare)
  final class Reading { public double mg; public boolean stable; public long atMs; }
}
```

- `D/ManualBalance`: not connected, resolution 1 mg. The job asks the operator (§7.5).
- `S/SimBalance` (§11.6): returned by `A/PadCalibrationJobs.balance()` only when `SimGuard.isSimulated(machine)` and the
  scene has a `balance`; mode `connected` or `display`. Otherwise the manual one.
- No serial driver: the balance's data port is unknown (REAL_MACHINE_TODO §15 item 7).

---

## 11. Simulator

### 11.1 What makes it testable today, and what is missing

- The SIM controller hook sees every line with the planned machine location (O/util/GcodeServer.java:309-318;
  `setDriver` :88-92); `RecordingGcodeServer` feeds a model with tracked XYZ and keeps Marlin's B step count across
  `G92` (T/dispense/RecordingGcodeServer.java:20-29, :103, :186-197).
- OpenPnP's simulator answers M114 without `Count` (O/util/GcodeServer.java:568-590), so the engine assumes 0
  (D/DispenseService.java:1328-1336): no phase is ever exercised.
- The SIM blank and paste colours differ by 18 (S/SimBoardRenderer.java:43-48), below DotInspector's threshold 25
  (V/DotInspectorSettings.java:17); all SIM paste is a fixed 1.0 mm disk (:51); the camera's frame cache key has no
  paste state (S/SimBoardCamera.java:111-119); the scene file is re-read when its stamp changes (S/SimScene.java:600-644).

### 11.2 `S/SimPaste.java`: the deposit model

- **Store and connection.** `SimPaste.forConfiguration(Configuration c)` returns null unless the scene file exists and
  has a `paste` block (gated like SimVacuum, S/SimVacuum.java:84-89). Deposits live in a static `Store` keyed by the
  scene file path (they survive scene reloads and reconnects, as paste on the bench does); the **screw state** is per
  connection: uinav creates one server per connection (AR §9.3), so a reconnect is a power cycle.
- **Screw state.** Logical B (deg, as the controller's G92/G1 B words, D/GcodeStreamBuilder.java:276, :426), the step
  count since this connection (`round(ΔB × 4.44)` per move, D/DispenseConfig.java:134), and a physical offset drawn per
  connection from `Random(seed + connectionNumber)`: the physical angle `θ = (count + offset) mod 1600` is unknown to the
  engine until it reads the count, and is lost on reconnect (I11's premise).
- **Pressure.** On only while `M106 P2 S255` and `M106 P3 S255` are both in force (D/ControllerCommands.java:43-46).
- **Mass of a forward B move** with pressure on, from θ0 to θ1 (the back move subtracts its own integral, so net-zero
  turns lay nothing): `m_ex = mRev·Δ/1600 + mRev·ripple/(2π)·[sin(2πθ1/1600 − ψ) − sin(2πθ0/1600 − ψ)]`, then × the
  transient `f(0) = 1 + oozeFirst·g(idle)`, `f(n ≥ 1) = 1 − refillDip·g(idle)·e^{−(n−1)/refillTauDots}` with `g(idle) =
  min(1, idle / idleFullS)` (the first dots after an idle, PT/lessons.md:8; after `idleDeadS` with the pressure off the
  first two dots lay nothing, PT/todo.md:106, :211), × `(1 + N(0, cv))`, × the **feed** factor
  `min(1, feedLimitMgS / demand)` (demand = paste laid in the last 5 s / 5 s).
- **Carry-over (tip bead).** `deposited = transferRatio·(bead + m_ex)`, `bead' = (1 − transferRatio)·(bead + m_ex)`.
- **Gap.** Needle-to-surface gap = the commanded N2 Z − (surface Z + `needleZOffsetMm` of the scene): above `maxGapMm`
  (0.20, D/DispenseProfile.java:53-54) nothing is deposited (all stays in the bead); below 0 the deposit is smeared
  (axis ratio 2, counted as a string).
- **Where.** At the end of the back move: tip XY = raw head XY (`SimVacuum.rawXyz`, S/SimVacuum.java:169) + `n2OffsetMm` +
  `N(landingBiasMm, landingSdMm)`; region blank / board / coupon k / bed (`SimScene.regionAt`, S/SimScene.java:368).
- **Footprint.** `A = c·m^e` (default `c 0.875, e 0.77`: deliberately not §1.4's piecewise law, so the calibration must
  measure) × `(1 + N(0, cv/2))`, an ellipse of axis ratio `1 + |N(0, 0.05)|`.
- **Threads.** On the move after a dot of mass m, with probability `threadShare` (0.5) for `m ≤ threadFullMg` (0.35),
  falling linearly to 0 at `threadZeroMg` (0.45), a thread deposit: an ellipse 0.15 mm wide from the dot's edge along
  the move, 0.6 × the hop long, 0.02 mg.
- **Count report.** With `paste.reportCount` (default true) the M114 reply gets `Count X:0 Y:0 Z:0 B:<count>` before
  `ok C:…`, which the engine's parser reads (D/DispenseService.java:905-916) and the live config's position regex
  ignores (`~/.openpnp2/machine.xml:553-555`). Scenes without `paste` are unchanged, so the acceptance check of the
  `no M114 Count` warning (src/test/sim/il_sim_acceptance.py:99) still holds there.
- **API.** `onLine(String line, AxesLocation loc, Driver d)` (GcodeServer) and `onLine(line, x, y, z)`
  (RecordingGcodeServer); `deposits(region)`, `couponMassMg(place)`, `wipe(region)`, `version()` (increments on every
  deposit and wipe); `countLine()`.

### 11.3 Scene additions (`<configDir>/instancelabs/sim-scene.json`, AR §9.2)

```json
"paste": {"mRevMg": 2.30, "ripple": 0.15, "ripplePeakDeg": 299, "cv": 0.08, "seed": 11,
          "footprint": {"c": 0.875, "e": 0.77},
          "transferRatio": 0.75, "oozeFirst": 0.40, "refillDip": 0.30, "refillTauDots": 2, "idleFullS": 300, "idleDeadS": 3600,
          "threadShare": 0.5, "threadFullMg": 0.35, "threadZeroMg": 0.45, "missBelowMg": 0.12,
          "maxGapMm": 0.20, "needleZOffsetMm": null, "feedLimitMgS": 3.0,
          "landingSdMm": 0.03, "landingBiasMm": [0, 0], "n2OffsetMm": [25.291, -75.926], "reportCount": true},
"coupons": [{"place": "coupon1", "rect": [330, 40, 370, 70], "z": 5.80, "bgr": [206, 25, 0], "massMg": 4100},
            {"place": "coupon2", "rect": [330, 75, 370, 105], "z": 5.80, "bgr": [206, 25, 0], "massMg": 4060}],
"balance": {"mode": "display", "resolutionMg": 1, "noiseMg": 0.4, "settleS": 2},
"blank": {"bgr": [206, 25, 0]}
```

- `mRevMg` 2.30 differs on purpose from the configured 2.11 (`tools/lumen.py:97`): the weighing must find 1.15 mg per
  half turn, not 1.06. The footprint law gives d 0.85 / 1.11 / 1.45 mm at the SIM's quarter, half and one turn, against
  §1.4's 0.83–1.00 / 1.16 / 1.345 at the model's masses.
- `n2OffsetMm` and `needleZOffsetMm` (null = the config's) are written by `il_sim_scene.py` from the staged machine.xml
  and dispense-config.json (the bench N2 offset is `~/instancelabs/config/machine.xml.patched:282`).
- `blank.bgr`: default today's `142, 59, 0` (S/SimBoardRenderer.java:48), so AR's tests and Playwright blocks are
  untouched; padcal scenes set `206, 25, 0` with `il_sim_scene.py --bench-blank`.
- `SimScene` parses the blocks (`SimScene.of`, S/SimScene.java:179); `SimVacuum` seals on the coupon planes too.

### 11.4 Rendering and DotInspector

- **Colours** (bench frames `runs/p18_half/before_005.jpg`, `after_005.jpg`, `before_006.jpg`, `after_006.jpg`,
  `before_020.jpg`, `after_020.jpg` at the frame centre: blank BGR (204–209, 0–53, 0), paste on the blank (147–155,
  36–44, 1)): with `--bench-blank`, `BLANK = bgr(206, 25, 0)` and `BLANK_PASTE = bgr(150, 40, 0)` (Δ blue 56 ≥ 25);
  coupons use their `bgr` and `BLANK_PASTE`; board paste keeps `PASTE` (S/SimBoardRenderer.java:47). This matches AR's
  BareSpotCheck premise "paste is far from the blank (~52)" (V/BareSpotCheck.java:23-24).
- `SimBoardRenderer.render(…, List<SimPaste.Deposit> deposits)` draws each deposit and thread as a filled ellipse of its
  own size; the old signature passes `SimPaste.depositsFor(sc)`. The static `blankDots` stay.
- `SimBoardCamera`'s cache key adds `SimPaste.version()` (S/SimBoardCamera.java:111).
- AR's planned JUnit helper `SceneImager` (AR §10.2) renders through `SimBoardRenderer`, so the engine path measures real
  pixels in JUnit.

### 11.5 Guard

Everything runs only inside `GcodeServer` / `RecordingGcodeServer` with a scene file, as SimVacuum (AR §9.1). The SIM
endpoints (§12.4) refuse unless `SimGuard.isSimulated(machine)`: `409 refused "the simulator is not confirmed on this
connection"`. Records made in SIM carry `simulated = 1` and never count on the machine (§1.7).

### 11.6 `S/SimBalance.java`

`reading = round(couponMass(loaded) + N(0, noiseMg), resolutionMg)` where `couponMass` = the coupon's `massMg` + its
deposits; `stable` after `settleS` since the load. `load(place)` puts a coupon on the balance. `connected`: the job
reads it, and the **emulated human** between the two readings calls `load(place)`, `SimPaste.wipe(place)` and
`load(place)` again (documented as the human's steps). `display`: the job prompts; a test (the "operator") calls
`POST /api/sim/balance/load`, reads `GET /api/sim/balance`, wipes with `POST /api/sim/paste/wipe`, reads again.

### 11.7 Scene variants (negative controls, §14.3)

| variant | expected |
|---|---|
| `reportCount false` | the quarter-turn block refuses (I11 count text) |
| `ripple 0.45` | the phase checks fail (quarter amplitude ≈ 0.31, half split ≈ 0.22); J and Q stay provisional |
| `threadZeroMg 0.65` | quarter-turn threads > k95: Q stays provisional, text names threads |
| `cv 0.16` | the repeat check fails |
| `landingBiasMm [0.2, 0]` | the landing check fails (`points to the needle offset`) |
| coupon 1 `rect` moved 1.5 mm off its place | step 2 refuses (`Coupon 1 is not at its place: its edge is 1.5 mm inside the weigh grid's margin`) |
| `needleZOffsetMm` + 0.15 (gap 0.25) | misses: transfer fails |
| reconnect between calibration and run | the run refuses the 400 block (I11) until the start angle is confirmed; a new job cannot reuse the old quarter optical record (stale by `controllerConnectedAtMs`) |
| `idleDeadS 0` (dead first dots) | the purge absorbs them: the ladder still passes (P7 reported) |
| profile `dwellMs 50` with one-turn dots | I6-feed refuses before anything moves |

---

## 12. Web API (conventions of WEB_API.md §2–3 and AR §7)

### 12.1 Profile check and mappings (`A/PadProfileEndpoints.java`, new group, registered next to PadTypeEndpoints, A/ApiServer.java:111-117)

| route | body / query | response |
|---|---|---|
| `GET /api/pad-profile/check` | `planId` or `boardFile` (a listed board path) | `profileCheck` (§3.5); 404 unknown plan; 409 board not listed |
| `POST /api/pad-profile/apply` | `{planId?, boardFile?, groups: ["g…"] \| "all", choice: "use" \| "save", suggestionIds: {g: s}}` | `{applied: [{group, kind, typeKey, version, mapping}], skipped: [{group, why}], newPlanId, planStale}`; with a plan source and nothing running it plans again (`A/PlanAgain`) and returns `newPlanId` (`planStale: false`); else `planStale: true`; 409 `suggestion-stale` (with the new check), 409 while a task or a running job is active |
| `GET /api/package-mappings` | – | `[{packageId, padNames, typeKey, version, atMs}]` |
| `POST /api/package-mappings` | `{packageId, padNames?: [..], typeKey: "…" \| null}` | `{mapping, padTypesFingerprint, note: "Plans made before this are stale: plan again."}`; 400 unknown type or bad names; 409 while a task runs |
| `POST /api/paste` | `{key?, name, lot}` | the paste; stamps `loadedAtMs` |
| `PUT /api/calibration-area` | `{surfaceName?, x0Mm, y0Mm, widthMm, heightMm}` | the area; 400 overlap with the purge row or a coupon place |
| `POST /api/calibration-area/reset` | – | `usedMm 0`, `resetAtMs`; 409 while a task runs (like `purge-row/reset`, A/DispenseEndpoints.java:765-771) |
| `PUT /api/config` (changed) | as today | off the SIM, refuses the shortened calibration settings: `padCalibration.minHops below 60 is allowed in the simulator only` (one text per floor, §5.4) |

`choice: "save"` for `kind use` writes the mapping; for `new` and `calibrate-existing` it writes the provisional type
version (through `svc.savePadType`, D/DispenseService.java:520-524) and the mapping. The mapping writer is the one BI
§13 item 3 needs for its carried mapping (`svc.getDb().mapPackage` + a `PAD_TYPE` event). `A/PlanAgain.of(ctx, entry)`
re-plans an entry from its source as AR §5.3 does (`svc.planBoard(dispense.baseBoardLocation(src), reg)`, estimate,
`ctx.plans.put`) and is reused by the job (§7.2 step 7).

### 12.2 Pad calibrations (`A/PadCalibrationEndpoints.java`, new group)

| route | body | response |
|---|---|---|
| `GET /api/pad-calibrations/preview` | `planId`/`boardFile`, `groups` (or `all`) or `typeKeys`, `steps?` | §7.9 JSON |
| `POST /api/pad-calibrations` | `{planId?, boardFile?, groups?: [..] \| "all", typeKeys?, steps?: ["ladder","weigh"], doseSteps?, save: true, suggestionIds, regulator: {psi, atMs}, pasteLifeAtMs, wait?}` | 202 `{kind: "pad calibration", id, steps, preview, started: true}`; with `wait` the result (AR's `awaitIf`, A/ApiContext.java:121-137). `steps: ["ladder"]` or `["weigh"]` with `doseSteps` and `typeKeys` is the by-hand path (§8): records, no decision |
| `GET /api/pad-calibrations/current` | – | the job view (as `status.padCalibration`) or null |
| `POST /api/pad-calibrations/{id}/answer` | `{step: "readings", coupons: [{place, fullG, emptyG}], confirm?}` \| `{step: "readings", skip: true}` | the job view; 409 `implausible`, 409 `late` (needs `confirm`), 409 wrong step |
| `POST /api/pad-calibrations/{id}/cancel` | – | the job view (engine abort during an engine step) |
| `GET /api/pad-calibrations` | `typeKey?, limit?` | `pad_calibrations` rows |
| `GET /api/pad-calibrations/{id}/crops/{cropId}.png` | – | PNG (`Response.bytes`, A/ApiServer.java:263-265); last 5 jobs kept in memory |
| `GET /api/dose-records` | `steps?, freshOnly?` | rows with `fresh` and `whyStale` |
| `GET /api/coupons/pending` | – | pending coupon records (place, steps, dots, laidAtMs, staleAtMs, jobId) |
| `POST /api/coupons/{recordId}/reading` | `{fullG, emptyG, confirm?}` | the mass record and its confirmation state (the readings of a stopped job or after a restart) |

Checks before 202 (in order, AR §7.2 style): `availableSvc`; `requireEngineIdle` (incl. a running job); no pressure
hold; OpenPnP idle; the groups exist and have a suggestion (`Nothing to calibrate: every chosen group cannot be
dispensed with the 22G tip.`); suggestion ids current; **regulator attestation** given and current (`Read the regulator
first: it must show 18.0 psi (Run › Checks › Regulator).`) and **paste life** attested (`Confirm the paste is within its
working life first (Run › Checks).`), both from the browser's attestations (W/core.js:567-605, :2488-2492); the area
(default when unset, §5.4) fits (`The calibration area has {left} mm left and this calibration needs {need} mm: wipe the
blank and press “Blank wiped” (Calibrate › Pad calibration).`) and does not overlap the purge row, a coupon place or the
current plan's pads + 3 mm (`The calibration area overlaps {what}: move it (Calibrate › Pad calibration).`); the coupon
places when a weighing is planned (`Set the weigh coupon places first: Setup › Places › coupon1 and coupon2 (where the
coupons lie).`) and no pending coupon on them; purge spots (I9 text); `checkBasic` interlocks with home-first (AR §2.6).
Then save (§12.1), `tracker.expect("pad calibration")`, start.

### 12.3 Status

`GET /api/status` gets `padCalibration` (next to `run`, A/DispenseEndpoints.java:113):

```json
{"id": "pc-17930…", "state": "awaiting-reading", "step": "readings", "startedAtMs": …, "planId": "…", "groups": ["g…"],
 "steps": [{"key": "blank", "state": "skipped", "text": "blank surface probed 13:02 (still valid)"},
           {"key": "coupons", "state": "done", "text": "coupon1 and coupon2 found and touched"},
           {"key": "ladder", "state": "done", "text": "76 dots: half ok, quarter ok"},
           {"key": "weigh", "state": "done", "text": "136 quarter-turn dots on coupon1, 68 half-turn on coupon2"},
           {"key": "readings", "state": "now"}, {"key": "decide", "state": "todo"}, {"key": "plan", "state": "todo"}],
 "ladder": [{"steps": 400, "turn": "quarter turn", "dots": 48, "found": 48, "hops": 182, "threads": 2, "residCv": 0.07,
             "phase": 0.11, "phaseLimit": 0.20, "dMeanMm": 0.88, "d95Mm": 0.95, "pass": true}],
 "awaiting": {"kind": "readings", "text": "…", "sinceMs": …, "confirmAfterMs": …, "staleAtMs": …,
              "coupons": [{"place": "coupon1", "steps": 400, "dots": 136, "expectedMg": 72}], "unit": "g", "resolutionMg": 1},
 "pendingCoupons": [...], "balance": {"kind": "manual", "connected": false},
 "estimatedSecondsLeft": null, "result": null}
```

`state`: `running` | `awaiting-reading` | `deciding` | `planning` | `done` | `stopped` | `cancelled` | `failed`.
`result.newPlanId` carries the plan made at step 7; a page or a second browser loads it once per job id when
`IL.planMatches(planId)` (AR's catch-up rule for `run.nextPlanId`, AR §7.5, §8.1). While an engine step runs,
`status.run` is that task (label `pad calibration`, kinds `pad-ladder`/`pad-weigh`).

### 12.4 SIM (`A/SimEndpoints.java`, refused unless `SimGuard.isSimulated`)

`GET /api/sim/balance` → `{mg, display: "4.172 g", stable, loaded}`; `POST /api/sim/balance/load {place}`;
`GET /api/sim/paste/deposits?region=` → `[{x, y, mg, dMm, region, thread}]`; `POST /api/sim/paste/wipe {region}`.

### 12.5 Plan view additions (`A/PadProfileViews.addTo`, one line in `Views.plan`)

- `profileCheck` (§3.5);
- per pad: `profileGroup`, `dotSteps`, `dotsPerPad`, `startPhase`, `predictedMassMg` (n·m(s)), `padRipplePct` (§4.6),
  `anchorMassMg` (κ_f·A·k), `dotDiameterMm` (`[lo, hi]` or measured), `clearanceMm` (min of the §1.6 gaps);
- per dot: `netSteps`, `block`, `padIndex`, `diameterMm` (BI's single `plan.dot.diameterMm`, BI §4.5, stays for old
  clients; boardview draws `dots[i].diameterMm` when present, hook H-P8);
- `blocks` (§6.1, with `demandMgS`).
- `D/PadInstances.java` (pc-stream): `fromPlan(DispensePlan, Configuration)`, `fromBoard(BoardLocation, resolver)`.

### 12.6 Upload preview (BI §3.2)

- `packages[].profileCheck: [{group, padNames, reason, tipKind, partial, short, reasonText, suggestion}]` (null when the
  package is covered), from the same check with the final ids and BI §13 item 3's carried mapping.
- When `profileCheck` is present, **`NEEDS_PROFILE` replaces `NO_PAD_TYPE`** (BOARD_IMPORT_DESIGN.md:497): one group-1
  warning for all packages, each with its highest reason: `{n} packages need a profile: SOT-23 (no pad type), Chip 0805
  (not calibrated yet), WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm (cannot be dispensed with the 22G tip). After saving, choose
  a suggestion for each in Profile › Needs a profile.`
- `placements[].outcome` `needs-profile` replaces `no-pad-type` (BI §3.2): a part whose package has any group with a
  reason other than `outside`; tip-only packages get `not-dispensed`.

### 12.7 Pad types (A/PadTypeEndpoints.java)

The list row (:91-116) reports the type's own `dotsPerPad` and `rotationPerDotDeg` (`dotSteps`, else the profile's),
adds `dotSteps`, `turn`, `tipKey`, `calAreaMm2`, `massPerPadMg`, `origin`, `lastCalibration` (the newest
`pad_calibrations` row); `apply` (:221-278) accepts the new fields with §5.1's validation and status-evidence rule
(409 `status-evidence`). `Views.padType` (A/Views.java:293) writes them.

---

## 13. Web UI

Binding rules (AR §8; UI brief): CSP `script-src 'self'`, no inline handlers, STOP always visible, plain language with
units, the 0.7 s poll without jank, never `prompt()`/`confirm()`/`showModal()` (W/DEV_NOTES.md:26-29), machine motion is
two-press (`IL.press2`, W/core.js:2241). New code lives in **`W/padprofile.js` + `W/padprofile.css`** (flat names,
A/ApiServer.java:47) and in `W/calibrate.js` (no other design owns it). pc-web adds the hook lines of §13.9 to the files
AR web-ui and BI own, after those slices have merged.

### 13.1 `IL.padProfile` (padprofile.js)

`groups(plan)`, `padTypesItem(plan, d, viol)` (readiness item 5, §13.2), `mountNeeds(host)` (§13.3),
`IL.views.ptNeeds` (suggestion card, §13.4), `IL.views['pad-cal']` (§13.5), `importRows(table, preview)` /
`importSaved(host, preview)` (§13.7), `inspectorBlock(pad)` / `amountText(pad)` (§13.8), `profileHooks(pt)` (§13.3),
fix-its `profile-use` (gate: idle), `profile-needs`, `pcal-open`, `pcal-area`, `pcal-wiped`; `setSource({planId} |
{boardFile})`.

### 13.2 Run readiness, item 5 "Pad types" (W/core.js:960-998)

- With `plan.profileCheck` (else today's code):
  - **fixable blocking groups** → `state: 'block'` (shown `Blocked`, W/core.js:173), value `{ok}/{pads}`, chip `{n}
    pads cannot be dispensed`, detail `{n} pads cannot be dispensed: SOT-23 (no pad type, 3 pads), Chip 0805 (not
    calibrated yet, 64 pads).` Fixes (≤ 3 groups, then all): `use` → `{fix: 'profile-use', arg: g, label: 'Use Chip 0603
    for TestChip_A · plans again'}`; others → `{href: '#profile/needs/' + g, label: 'Needs a profile: SOT-23'}`; then
    `{href: '#profile/needs', label: 'All 2 in Profile › Needs a profile'}`; the existing `Lowest pad-type status` fix
    when below-gate groups exist (:983).
  - **blocked only by `tip` groups** → `state: 'block'`, detail `{n} pads cannot be dispensed with the 22G tip: WSON-8…
    (20 pads), USB_Mini-B data pins (5 pads).`, fixes `{href: '#setup/settings/refuse-flagged', label: 'Dispense the
    rest (Setup › Settings › Refuse flagged pads)'}` and `{href: '#profile/needs', label: 'Why'}` (open question 6).
  - **only notes** (`outside`) → `{state: 'info', ready: true, value: '{pads}/{pads} · 2 notes'}` with the Note glyph
    (AR's "homes first" precedent, AR §8.2), because any not-ready item other than the regulator and pads-clean blocks
    Run (W/core.js:1190-1192).
  - ok: unchanged.
- `TILE_GO.padtypes` (W/run.js:1600) → `#profile/needs` when groups exist.

### 13.3 Profile › "Needs a profile"

- `section.card.pt-needs#pt-needs` inside `#pt-section` between `#profiles-hint` and `#profiles` (W/profile.js:572-573),
  shown when groups exist. Header `h2` `Needs a profile` + `#pt-needs-count` (fixable groups only); `#pt-needs-src` `From
  the plan of FTP test board (13:05)` / `From the board mobo (not planned yet)`.
- `table#pt-needs-table`, rows `tr[data-group][data-reason=no-pad-size|no-type|below-gate]` (fixable): package and `D2 ·
  3 pads`; opening (`pcr.fmtPadPair`); reason badge + `short`; suggestion short (`Use Chip 0603` / `New: 2 quarter turns
  per pad, ≈ 1.06 mg`); buttons `button[data-action=open]` `Suggestion…`, `[data-action=use]` `Use Chip 0603 · plans
  again` (kind use), `[data-action=auto]` `Calibrate automatically…` (single press: opens `pad-cal` before start),
  `[data-action=manual]` `I'll do it by hand`. A `partial` row adds the partial sentence under its package.
- `details.disclose#pt-needs-tip` (collapsed, Info glyph) `Cannot be dispensed with the 22G tip ({n})`: `tr[data-reason=tip]`
  rows with the reason and no action buttons; `details.disclose#pt-needs-notes` (collapsed) `Notes ({n})`: `outside` rows
  with `Suggestion…`.
- Foot: `#btn-needs-use-all` `Use the matching pad types ({n}) · plans again`, `#btn-needs-auto-all` `Calibrate all
  automatically… · ≈ {M:SS}` (opens `pad-cal`), `#needs-why` (reason line, `IL.gate`). Rebuilt with `IL.keyed` on
  (source, group ids, suggestion ids).
- Filter segment `button[data-filter=needs]#pt-filter-needs` `Needs a profile · {fixable}`; nav sub `Needs {n} profiles`
  (kind `need`) counts fixable groups only; no sub for tip-only or notes.
- **Hook** (H-P3): profile.js exposes its hookable functions once, `IL.pt = {route, planStats, newForPackage,
  sidePanelExtras: [], editorExtras: [], navSubs: []}`, and calls `IL.padProfile && IL.padProfile.profileHooks(IL.pt)`
  at module init; profile.js calls those entries through `IL.pt`. padprofile.js then adds the `needs` routes
  (`#profile/needs`, `#profile/needs/<g>`, W/profile.js:1174-1195), groups `planStats` and the overview by
  `profileGroup` instead of `(no type)` (:147-151, :1030-1034), replaces `newForPackage` (:754-767) by "prefill from
  the package's suggestion and save with a mapping" (no `^pkg$` pattern), adds the editor fields and the side-panel
  button. profile.js's own `newForPackage` stays unchanged for when padprofile.js is absent.
- Pad-type editor (`#dlg-padtype`, :656-698) via `editorExtras`: `#pt-dotsteps` (`Turn per dot`: `the profile's (half
  turn)`, `quarter turn (400 steps)`, `half turn (800)`, `one turn (1600)`), `#pt-dotsperpad` (1–6, empty = by pad
  size), read-only `#pt-calinfo` (tip, recipe, calibrated area, mass per pad, origin, evidence). Side panel (:1071-1082)
  via `sidePanelExtras`: `Calibrate automatically…` (`#btn-pt-auto`) next to Set up / Validate.
- **Texts that become false**, replaced through the same hook lines: the Profile meta `One recipe for every dot.`
  (:1165) → `One recipe; a pad type may use another turn per dot (quarter, half or one turn).`; the column hint `Dots per
  pad, each one dot of the dispense profile` (:576) → `Dots per pad and turn`; the side panel `{n} dots of the profile
  per pad` (:1080) → `2 quarter-turn dots per pad (own turn, calibrated 14:05)` / `1 dot of the profile per pad (half
  turn)`.

### 13.4 The suggestion card (side panel `#profile/needs/<g>`, `IL.views.ptNeeds`)

| id | content |
|---|---|
| `#pt-sugg-reason` | notebox `block` / `need` / `info` with `reasonText` |
| `#pt-sugg-text` | the suggestion `sentence` |
| `#pt-sugg-preview` | a `lite` `IL.boardview` (BI §5.2), like `#pad-insp-svg`: from the plan (`setPlan` + `fit(part box + 1 mm)`), or art-only (`setBoard({art, toMachine})`) for a board file or the upload preview; the suggested dots through `bv.update({sugg: [{x, y, d}]})`, drawn as `circle.pv-sugg` at their predicted upper diameter in `g.pv-l-sugg` (never `circle.dot`, so the plan's dot counts stay true); caption `dots 0.83–1.00 mm` |
| `#pt-sugg-kv` | Opening; Amount (target, rule, cap); Dots per pad and turn; Dot size (band or measured); Per pad (predicted, %); Per-pad spread from the screw angle; Edge and neighbour clearances; Phase (`phase.text`); Confidence + why |
| `#pt-sugg-alt` | `details.disclose` `Other plans` (alternatives with why) |
| buttons | `#btn-pt-sugg-use` (kind use, `Use Chip 0603 · plans again`), `#btn-pt-sugg-auto` (`Calibrate automatically… · ≈ M:SS`, opens `pad-cal`), `#btn-pt-sugg-manual`, `#pt-sugg-edit` `Edit before saving…` (opens `#dlg-padtype` prefilled; saved as provisional with the mapping; a later calibration keeps the edited plan, §4.2) |
| `#pt-sugg-why` | reason line |
| tip groups | `#pt-sugg-tip`: the reason and `Pads that cannot be dispensed keep the board from running while “Refuse flagged pads” is on (Setup › Settings).` No action buttons. |

### 13.5 The calibration panel view (`IL.view('pad-cal')`, route `?panel=pad-cal`, W/core.js:1971, 2103-2117)

Stays visible from every workspace while the operator weighs (as AR's `probe-blank` view, W/setup.js:5). Every
`Calibrate automatically…` entry point opens it in the before-start state; only `#btn-pcal-start` starts.

| state | ids |
|---|---|
| before start | `#pcal-note` (§7.9 sentence), `#pcal-needs` (`You need: …`), `table#pcal-plan` (steps with estimates), `#pcal-groups-list` (groups, saves, demotions), `#pcal-fixes` (inline fix-its for the §12.2 refusals: `Set the calibration area`, `Blank wiped`, `Read the regulator`, `Paste within its life`, `Set the coupon places`), `#btn-pcal-start` (press2, verb `calibrate`, label `Calibrate · ≈ M:SS`), `#pcal-why` |
| running | `ol.phases#pcal-phases` (Home, Z check, Blank, Coupons, Ladder `quarter turn · dot 14 of 48`, Weigh, Readings, Decide, Plan again; glyphs as `#run-phases`, W/run.js:1840-1849), `table#pcal-ladder` (turn, dots, found, threads/hops, residual CV, phase and limit, d, pass), `#pcal-live` `STOP stops it at once (F9).` |
| awaiting readings | `div.ri.ri--need#pcal-ask` (`role=status`): `#pcal-ask-text` (§7.5, with `Waiting for you since 14:05`), a camera snapshot per coupon (`img#pcal-coupon-1` from the after frame), per coupon `input#pcal-full-1`, `input#pcal-empty-1` (`inputmode=decimal`, `data-enter-submits`), `#pcal-mg-1` (`= 71 mg of paste`), …; `#btn-pcal-readings` (`Save the readings`), `#btn-pcal-skip` (`Skip weighing (the types stay provisional: boards still refuse these pads)`), `#btn-pcal-cancel` |
| result | `div.ri#pcal-result` (`ri--ok|ri--need|ri--block`, `data-refusals`), `ul#pcal-groups`, `#pcal-crops` (`img` from the crops route, `alt` = dose and dot), `table#pcal-pred`, `#btn-pcal-again` (`Calibrate again…`: pools with these results), `#btn-pcal-weigh-now` (`Type the readings`, when pending coupons are fresh), `#btn-pcal-finish` (`Finish the calibration`, when every record is fresh), `#btn-pcal-manual`, `#btn-pcal-plan` (`Checks ›` when planned again) |

- `CAL_TASKS` (W/calibrate.js:14) and `pcr.TASK` (W/profile.js:71-72) get `'pad calibration'`; `TASK_WORD` / `taskParts`
  (W/core.js:1258-1300): `Pad calibration · ladder 14/48 · ≈ 2:50 left`; `lockReason` (:663): `Locked while the pad
  calibration runs`. `records.js` `runTitle`: `pad-ladder` → `Pad calibration · blank`, `pad-weigh` → `Pad calibration
  · coupons`.
- The status strip's task chip shows `Waiting for you · weigh` (kind `need`) at `awaiting-reading`; a click opens `pad-cal`.
- The job id is kept in sessionStorage (`il.pcal.job`); a reload or a second browser finds it in `status.padCalibration`
  and restores the prompt.

### 13.6 Calibrate › Pad calibration card (`#cal-padcal`, calibrate.js)

- A small map (`svg#pca-map`: blank place, purge row spots, coupon places, the area, its used part) and fields `#pca-x0`,
  `#pca-y0`, `#pca-w`, `#pca-h` (prefilled with the effective area; `Default: 40 × 20 mm at the blank place`),
  `#btn-pca-save` (`PUT /api/calibration-area`), `#pca-left` (`12.5 of 20.0 mm left ≈ 1 calibration`), `#btn-pca-wiped`
  (press2 `blank wiped` → `POST /api/calibration-area/reset`), `#btn-pca-paste` (press2 `new paste loaded` →
  `POST /api/paste`; `Weighings and camera results from before stop counting.`), the coupon line (`coupon1 and coupon2 set
  (Setup › Places)` or the link), and the pending coupons (`coupon1: 136 dots to weigh until 22:05 · Type the readings`).
- Calibrate texts that become false: the notebox `Records only: boards use the dispense profile …` (W/calibrate.js:689-690)
  → `Boards use the profile's dot unless a pad type has its own turn (set by Calibrate automatically or by hand). The dot
  ladder and scale cards below are records only.`; the ladder subtitle `camera · a history, not a dose` (:202) →
  `camera · records only (no phase lock)`.

### 13.7 Upload preview and save (BI §5.8; hook H-P6 in boardimport.js)

- State 3: `#board-import-packages` rows get `td.bi-pp` with `span.pp-badge[data-reason]` + `short` (`ready: Chip
  0603`, `no pad type`, `not calibrated yet`, `cannot be dispensed with the 22G tip`); `needs-profile` parts get the
  hatch that BI gives `no-pad-type` (BI §5.8), `tip`-only parts the not-dispensed tint; one group-1 warning
  (`NEEDS_PROFILE`, §12.6).
- State 6 (after the save, ids are final): `#board-import-profile`: `{n} packages need a profile:` and per group
  `button[data-group][data-action=use]` (`Use Chip 0603`, writes the mapping) and `[data-action=needs]` (`Needs a
  profile…`: `IL.padProfile.setSource({boardFile})`, closes, goes to `#profile/needs/<g>`). It replaces
  `#btn-board-import-newtype` (BI §5.12).

### 13.8 Pad inspector (BI §5.7; hook H-P7 in padinspect.js)

- When the pad's group has a reason, `#pad-insp-profile` (the group's reason notebox, the suggestion sentence,
  `#btn-pad-insp-use`, `#btn-pad-insp-auto` (`Calibrate automatically…`, opens `pad-cal`), `#btn-pad-insp-manual`)
  **replaces** the `NO_PAD_TYPE` and `STATUS_BELOW_MIN` noteboxes of `#pad-insp-flags` (`NO_GEOMETRY` stays) and the
  `New pad type for <pkg>` of `#pad-insp-edit` (BOARD_IMPORT_DESIGN.md:1391-1393): one notebox, one set of actions.
- The `amount` line of `#pad-insp-kv` becomes `IL.padProfile.amountText(pad)`: `Planned: 2 quarter-turn dots × 0.53 mg =
  1.06 mg (target 1.26 mg at your 0603 rate; a 0.10 mm stencil would print 0.32 mg).`

### 13.9 Hooks in other owners' files (each a no-op until padprofile.js loads; pc-web adds them after the owners merged)

| # | file (owner) | line |
|---|---|---|
| H-P1 | W/core.js (AR web-ui) item 5, :960-998 | `const pt = IL.padProfile && plan && plan.profileCheck ? IL.padProfile.padTypesItem(plan, d, viol) : null; if (pt) add(pt); else { …today… }` |
| H-P2 | W/core.js `TASK_WORD`/`taskParts`/`lockReason` | the `pad calibration` entries of §13.5 |
| H-P3 | W/profile.js (AR web-ui) | `IL.pt = {…}` + `IL.padProfile && IL.padProfile.profileHooks(IL.pt)`; calls through `IL.pt`; `pcr.TASK`; the three texts of §13.3 |
| H-P4 | W/run.js (AR web-ui) | `TILE_GO.padtypes` |
| H-P5 | W/records.js (AR web-ui) | `runTitle` kinds |
| H-P6 | W/boardimport.js (BI web-flow) | `importRows` in state 3, `importSaved` in state 6 |
| H-P7 | W/padinspect.js (BI web-render) | `inspectorBlock` (replacing the two flag noteboxes and the new-type link), `amountText` |
| H-P8 | W/boardview.js (BI web-render) | dot radius from `dots[i].diameterMm` when present; `update({sugg})` → `g.pv-l-sugg` `circle.pv-sugg` |
| H-P9 | W/index.html (BI) | `padprofile.css` after `boardimport.css`; `padprofile.js` after `boardimport.js` (defer) |

---

## 14. Tests

### 14.1 Unit (JUnit, `mvn -B -o -T1 test`)

- **`T/padprofile/DoseModelTest`**: the three source points and their exclusions as constants; chord exponents 0.982 /
  0.428 (±0.005); band at 400 = d [0.825, 1.000] (±0.005); d at 800 / 1600 = 1.160 / 1.345; mean heights 0.261 / 0.266
  / 0.395 mm; ripple factors 0.90 / 0.64 / 0.30 / 0; a = 0.113/β for β ∈ [0.43, 0.98]; allowed doses {400, 800, 1600}
  (not 200, 266, 533, 3200); turn names.
- **`T/padprofile/DotGeometryTest`**: `layoutN` spread to pMin (0805 2 × 800 → ±0.47) and inward moves;
  `layoutN(…, 0, 0)` equals today's DotSizer positions on every DotSizerTest case.
- **`T/padprofile/LadderStatsTest`**: half split and quarter harmonic fit on synthetic sets (seeded) with known answers;
  residual CV, the drift rule, `k95` table (60 → 4, 90 → 6, 180 → 9; misses 60 → 1, 180 → 3); pooling of a failed and a
  passing record; exclusion of the first 2 dots after a cross; the operating points of §7.4 within ±3 % on 2,000 seeded
  trials.
- **`T/padprofile/ProfileCheckTest`**: grouping tolerances, group ids stable across preview/board/plan inputs, reason order
  (tip > no-pad-size > no-type > below-gate > outside), `tipKind`, the `partial` attribute, overlapping pads, blocking only
  from flags, `outside` geometry only beyond the marginal band, `fixable` counts, **every §3.4 text** (exact strings,
  including gap ≤ 0 and singular/plural), `outside` area 0.79 (nano LED_0805 vs chip-0805 calibrated on 1.381 mm²), by tip,
  by recipe.
- **`T/padprofile/SuggesterTest`**: the §4.3 order (profile dose first; 0805 → 1 × 1600 because two half-turn dots 0.94 apart
  reach 0.35); R6 (no single-quarter pads); the family cap (SOIC-8 1.08 mg); neighbours with their own dots changing a
  result; fallback, marginal and marginal-fallback paths; use vs calibrate-existing vs new; **calibrate-existing keeps an
  edited plan**; key/name/family; mapping with pad names for SOT-223; suggestion id changes when evidence changes;
  confidence reasons.
- **`T/padprofile/FixtureSuggestionsTest`**: on `R/ftp.pads.json`, `R/mobo.pads.json`, `R/nano.pads.json` (both sides; the
  extraction script and its source paths in `R/README.md`): every row of §1.9 (kind, steps, n, ±1 % mass error); the nano
  split: 85 `tip` (the bench's 84 too-fine pads listed by ref and pad, plus the DO-220AA pin), 40 with a plan, 6 partial;
  top side only 40 / 15 / 5.
- **`T/padprofile/DeciderTest`**: measured dots turning SOIC-8 marginal into pass (d̄ 0.84, d95 0.92: overhang 0.12, gap
  0.35) and fail (d̄ 1.02, d95 1.10: gap 0.17); pooled evidence where a later passing record does not erase an earlier
  failure; one reading vs a confirmed pair; −25 %/+10 % edges; control fail; an edited plan replaced with its text;
  fallback never characterized; validated never written.
- **`T/dispense/DispenseDbMigrationTest`**: a v1 file made with the v1 DDL and rows → open → v2 columns, view, tables,
  triggers (UPDATE/DELETE still abort), a `schema_info` row 2, the backup file, old rows read with null new fields; a
  second open is a no-op.
- **`T/dispense/DoseRecordTest`**: freshness by each §1.7 rule (incl. controller connect and start angle for partial
  optical records, not for mass records; regulator psi; paste loaded; needle changed).
- **`T/dispense/ResolverPadMapTest`**: pad-map > package-map > pattern > centre rule; fingerprint changes with names;
  SOT-223 pins and tab on two types.
- **`T/dispense/PlannerDoseTest`**: per-type dose and dots; blocks ordered (profile dose first, then descending); pads whole
  (R5); one-dot-per-pad plans in today's order; `startPhase`; R6 and tip-minimum PLAN refusals.
- **`T/dispense/GcodeBlocksTest`**: `forBlocks`: initial purge phase, block switch phase dot + 4 flush, every block start at
  298° with a count of 1234, whole-turn blocks without a phase dot; `forPlan` of an 800-only plan byte-identical to today's
  program (a golden file from the current builder).
- **`T/dispense/ValidatorBlocksTest`**: each §6.4 rule fires on a hand-edited program (wrong net, 533 steps, a 2000-step
  purge dot, block start at 28°, a dose change without a switch, a split pad, an over-long total).
- **`T/dispense/InterlocksBlocksTest`**: I9 spot counting (8 + 5 + 4), I11 for a quarter-turn block with a half-turn
  profile (board run and calibration), I6-feed at dwell 50 ms with one-turn dots.
- **`T/dispense/PadLadderTest`**, **`WeighPlanTest`**: N, pitch from the hop, insets, rows, tiles (20.6 × 11.6 mm), the
  corridor thread test on rendered frames, phases; weigh counts 136/68/34, the coupon grid inside 40 × 30 − 3 mm margins;
  weigh allocation for 0, 1, 2, 3 doses.
- **`T/dispense/StopEpochTest`**: a task queued before `abort()` refuses at its start; the job's submit after `stop()` does
  not happen.
- **`T/sim/SimPasteTest`**: mass of half/whole/quarter turns at known physical angles (ripple factor 0.64/0/0.90), net zero
  = 0, transient and idle, carry-over decay, gap misses and smears, feed starving, threads by mass, Count line, reconnect
  resets the count and the offset; **`SimBalanceTest`** (rounding, load, settle, emulated human); 
  **`GcodeServerPasteHookTest`** (no `paste` block → byte-identical M114).
- **`T/sim/SimPasteInspectTest`**: render deposits of 0.53/1.06/2.11 mg and a thread through `SimBoardRenderer` (bench
  blank) and measure with `DotInspector` (real OpenCV): found, d within ±5 % of the deposit, the thread found by the
  corridor test; the old colours would miss them (a regression assert at 18 levels).

### 14.2 API (SimMachine + real ApiServer, as T/api/ApiServerSimTest.java:50-75)

- **`T/api/PadProfileApiSimTest`** (pc-api): check on a plan and on a board file; apply `use` (mapping → plan again →
  `newPlanId` → group gone); apply `save` (provisional version with the dot plan); `suggestion-stale`; mappings
  validation; paste stamping; calibration-area validation and reset; status-evidence 409; SIM-only floors refused off the
  SIM (a stubbed `SimGuard`); all refused while a task runs; the plan view's per-pad and per-dot fields; `Views.padType`
  fields; **the upload preview through BI's endpoint**: `packages[].profileCheck`, `NEEDS_PROFILE` replacing `NO_PAD_TYPE`
  (one group-1 warning), `needs-profile` outcomes, and K/ apertures = R/*.pads.json within 0.001 mm.
- **`T/api/PadCalibrationJobTest`** (pc-task, fake engine steps): state machine (running → awaiting-reading → deciding →
  done), implausible and late readings, skip, cancel during an engine step (abort) and at the readings (pending coupons
  kept), STOP between steps (no submit; epoch refusal), the lock during running and its release at the readings, coupon
  reservation texts, one job at a time, `Finish the calibration` with fresh records (no engine step), preflight refusals in
  §12.2 order.
- **`T/api/PadProfileUiSimTest`** (pc-web): `index.html` loads `padprofile.js` and `padprofile.css` and both are served
  (as BI's BoardImportSimTest does; W/DEV_NOTES.md:26-27).

### 14.3 SIM end to end (`T/api/PadCalibrationSimTest`, pc-task)

RecordingGcodeServer with the §11.3 scene (`paste`, two coupons, bench blank) and `reportBCount`, AR's `SceneImager`
drawing the deposits, SimBalance `connected` (emulated human), the real service and ApiServer. Board (built in code):
R1–R4 `R_0603_1608Metric` (chip-0603 characterized as in ApiServerSimTest.java:580), J1–J2 `SW_SPST_3.9x2.9mm` (no
type; 0.864 × 1.829 r18 at ±2.0), Q1–Q3 `Test_Strip_0.62x1.50` (two 0.62 × 1.50 mm openings, long axis Y, at ±1.0 mm;
no seed pattern matches), U1 `WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm`, T1 `TestChip_A` (0.80 × 0.95 r50 at ±0.825).

1. Plan → `profileCheck`: J (no-type, new 2 × 800, −8 %), Q (no-type, new 2 × 400, −22 %), U (two groups, tip), T (no-type,
   use chip-0603); `fixable` 3.
2. Apply `use` for T (→ `newPlanId`); `POST /api/pad-calibrations {groups: "all", save: true, regulator, pasteLifeAtMs,
   wait: true}` (J and Q).
3. Expect: coupons looked at and touched; ladder blocks 800 (28) and 400 (48) with phase and flush dots; every partial
   block start at 298° in the recorded G-code (count-based); DotInspector finds 76/76; the quarter-turn harmonic fit from 4
   phases; weigh dots 136 × 400 on coupon1 and 68 × 800 on coupon2; readings ≈ 0.575 and ≈ 1.15 mg per dot (the SIM's 2.30
   mg/rev, ±4 %), not the model's 0.53 / 1.06, and they confirm each other (mass per step within 5 %); the control's drift
   sentence (`… 9 % more than the 1.06 mg weighed …`); both types `characterized` with `massPerPadMg` 2.30 (J, 0 % vs 2.29)
   and 1.15 (Q, −15 % vs 1.35) ±5 %; `result.newPlanId` set; the new plan has no fixable group (U stays `tip`).
4. Run the new plan with `refuseFlaggedPads = false` (U skipped): blocks 800 (R, T, J) and 400 (Q) with one switch; each
   Q pad's 2 dots consecutive; SimPaste deposits per pad within ±10 % of `predictedMassMg`.
5. A second job with no motion (`Finish the calibration`) writes nothing new and plans nothing; the second coupon look of
   a new weighing passes after the emulated human wiped the coupons.
6. Negative controls: every row of §11.7 (one scene each, the expected outcome asserted).

### 14.4 Playwright (`src/test/web/pad-profile.playwright.cjs`, run after `board-import.playwright.cjs` by `run-webui-sim-test.sh`)

Runtime budget ≤ 6 min (the SIM runs in real time). The test shortens the job through `PUT /api/config`
(`padCalibration.ladderDotsPerPhase 3`, `minLadderDots 6`, `minHops 10`, `weighTargetMg 15`; allowed in the SIM only).

0. Set-up: dismiss a pushed Recovery panel as PW does; Start and enable; `POST /api/machine/home`; `POST
   /api/purge-row/reset`; write the padcal scene (`python3 src/test/sim/il_sim_scene.py --bench-blank --paste --coupons
   --balance display`, the session's config dir from the runner; the scene reloads on its stamp, S/SimScene.java:600-644);
   set the places `coupon1`/`coupon2`; attest the regulator and the paste life (`IL.attest.set`); chip-0603 is
   `characterized` (raised through the pad-types API if the seeded session has it lower; a profile-dose type needs no
   evidence, §5.1).
1. Upload `R/padcal.kicad_pcb` through `#board-import-file` (BI's step-5 helper): `span.pp-badge[data-reason]` per
   package; exactly one `li[data-code=NEEDS_PROFILE][data-group="1"]` and no `NO_PAD_TYPE`; save; `[data-action=needs]` →
   `#profile/needs/<g>`.
2. Plan the imported board at a fixed seat → Run Checks: the Pad types card reads `Blocked` with `Needs a profile: SW_SPST_3.9x2.9mm`.
3. Click it → `#pt-needs` lists 3 fixable groups (J, Q, T) with `data-reason`; `#pt-needs-tip` (collapsed) holds the 2 WSON
   groups with `cannot be dispensed with the 22G tip`; the nav sub says `Needs 3 profiles`.
4. Open the SW group → `#pt-sugg-text` starts `New pad type SW_SPST_3.9x2.9mm`; `#pt-sugg-preview circle.pv-sugg` count =
   4; `#plan-svg circle.dot` count unchanged.
5. `Use Chip 0603 · plans again` on the TestChip_A row (one click) → `IL.S.plan.planId` changes to the response's
   `newPlanId` and the row is gone.
6. Pad inspector on a SW pad: `#pad-insp-profile` present, no `NO_PAD_TYPE` notebox in `#pad-insp-flags`, no `New pad type`
   in `#pad-insp-edit`.
7. `#btn-pt-sugg-auto` on the SW card (one click) → `pad-cal` shows `#pcal-note` matching `/needs you once/` and
   `status.run` is null; `#btn-pcal-start` (two presses) → phases advance; the strip chip reads `Pad calibration · …`.
8. At `#pcal-ask`: the strip chip reads `Waiting for you · weigh`; `page.reload()` → `#pcal-ask` is back with the coupon
   fields (both coupons carry 30 half-turn dots: one dose, the profile's, weighed twice, §7.2); for each coupon k the test
   (the operator) loads it on the SIM balance, reads, wipes, reads, types into `#pcal-full-k` / `#pcal-empty-k` and
   checks `#pcal-mg-k`; then saves.
9. `#pcal-result` first sentence starts `Characterized`; `#pcal-crops img` ≥ 3 load; `IL.S.plan.planId ===
   status.padCalibration.result.newPlanId`; the Needs list has no SW or TestChip_A row; Q remains.
10. Manual path on Q: `I'll do it by hand` → `#calibrate` with `#setup-guide` naming the suggestion; `#btn-guide-ladder`
    (two presses) lays the ladder and `table#pcal-ladder` shows the limits; the Weigh dots card prefilled (`#wd-steps`
    400, `#wd-count` 32: ceil(15 / 0.53) = 29 → 30 → a multiple of 4).
11. Calibrate › Pad calibration: `#pca-left` reads `… mm left`; `#btn-pca-wiped` (two presses) → `usedMm 0`.
12. STOP while `#pcal-ask` is shown (a second job) → `#pcal-result` `Stopped … the blank results were kept; the coupons
    still need weighing …` and `#btn-pcal-weigh-now` present.
13. Its own "no JavaScript errors" and "no native dialogs" checks.

### 14.5 Sim acceptance (`il_sim_acceptance.py padcal`, also in `all`)

uinav SIM session with the staged config, the padcal scene (`--bench-blank --paste --coupons --balance display`): upload
`R/padcal.kicad_pcb` through BI's import (preview shows `NEEDS_PROFILE` and the per-package reasons) → save → plan at a
fixed seat → check → apply all → calibrate all (the script is the operator: balance loads, wipes and readings) → plan
again → run with flagged pads skipped → assert per-pad deposit mass, block switches and `Count` lines in the controller
log, the result sentence, and that the `pad_calibrations` rows are `simulated`. A `nano` check runs the profile check on
the uploaded Nano (top side): 40 `tip`, 15 with a plan, 5 partial.

---

## 15. What only the real machine can confirm (to REAL_MACHINE_TODO.md)

1. **Tip OD** of the AD22TTA-B (bottom camera, side camera): the 0.72 mm assumption behind the same-pad pitch.
2. **Quarter turns at 18 psi** on the blank and on coupons: transfer, threads at the real hop, CV, the 4-phase amplitude
   (P4–P6); whether an eighth (200) would transfer (not offered).
3. **Mass per step** across 400/800/1600 by weight (P1) and the 3 × 100 repeatability (P2, PT/todo.md:236).
4. **The footprint law** at quarter and full turns by camera on the blank (P3: the band 0.83–1.00 mm decides SOT-23,
   SOIC-8 and 1.27 mm headers).
5. **Block switch**: are 1 phase dot + 4 flush dots enough (P7)? Count `Count B` lines and per-block first dots.
6. **Weigh coupons**: where two 40 × 30 mm coupons lie on the bed, N1's vacuum touch on them, the camera look's margin
   check, flux evaporation over 15 min, the wipe's residue.
7. **Balance data port** (U.S. Solid 110 g × 0.001 g): RS-232/USB, protocol; a serial `Balance` later; a check weight at
   the first weighing of a session (optional).
8. **0805 as one full turn vs two half turns** after reflow (§1.9), and the needle-clearance pitch (0.94 mm) against the
   bench thread's "~0.9 mm apart": a test board on mobo-class parts.
9. **DotInspector circularity on real half-turn dots** (0.42–0.74, `runs/p18_half/meas.txt`) vs the 0.35 string threshold,
   and the corridor thread test on real frames.
10. **The calibration area** on the real blank: flatness with the purge row in one plane (the blank is bowed and loose,
    mem:lumen-calibration-state; slid ~2 mm, PT/todo.md:209), the bare check of every spot, calibrations per wipe.
11. **Image tiles**: dot diameters near the frame edge vs at the centre (scale error ±3 % in Y, AR §11 item 5).
12. **Freshness window** (8 h): does mass per step drift with paste age or barrel level?
13. **Sustained feed** at 18 psi (2.56 mg/s seen with one-turn dots): where starving begins.
14. **The coupon surface probe** with `frameCheck false` (AR §16 item 1's frame rule does not apply to it).
15. **The fine-pitch cap** (0.25 mm print) and the −25 %/+10 % band on SOIC-8, headers and PicoBlade after reflow (bridges
    metered).

---

## 16. Docs to update (integration slice)

- **WEB_API.md**: §3 the routes of §12; `status.padCalibration`; plan fields; preview fields; SIM routes; §5 the UI; §6 the
  new test classes; §7 limits (the job is lost on restart, its coupons are not; Z modelled only as the gap).
- **DISPENSE_API.md**: §2 `tip`, `paste`, `calibrationArea`, `padCalibration`, `profile.sustainedFeedMgS`; §3 schema v2, the
  migration, the new tables and columns; §4 per-type dose, blocks, whole pads; §5 `forBlocks`, phase dots,
  `runDoseBlocks`, `applyPadCalibration`, the stop epoch; §6 dose records and the job; §7 I9/I11/PLAN/I6-feed changes; §8
  the model's limits (§1 confidences).
- **UI_DESIGN.md**: §8.3 Profile (Needs a profile, suggestion card, editor fields), §8.4 Calibrate (Pad calibration card,
  guide, Weigh dots), §9.1 item 5 (tip-only and notes), §13 texts (incl. §13.3 and §13.6 replacements), §16 ids (§13 of
  this design), §17.
- **REAL_MACHINE_TODO.md**: §15 as new numbered items.
- **W/DEV_NOTES.md**: `padprofile.js`, its hooks and `IL.pt`, `IL.padProfile`, the `pad-cal` view, the sessionStorage key.

---

## 17. Implementation slices (disjoint files; builders in parallel worktrees)

| key | wave | depends on | owns |
|---|---|---|---|
| `pc-model` | 1 | – | P/* (new: PadInstance, TypeInfo, Tip, DoseEvidence, Anchor, CheckInputs, ProfileCheck, Suggester, Suggestion, DoseModel, DotGeometry, LadderStats, Decider, Texts, Limits, GroupKey, Family); D/DotSizer (its grid delegates to `P/DotGeometry.layoutN(…, 0)`, same positions); T/padprofile/*; R/ftp.pads.json, R/mobo.pads.json, R/nano.pads.json, R/padcal.kicad_pcb, R/README.md |
| `pc-data` | 1 | – | D/DispenseDb (migration v2, tables, mapPackage names, pendingCoupons), D/PadType, D/DispenseConfig (4 objects + validate), D/DispenseProfile (doseFingerprint, phaseSteps overload, sustainedFeedMgS); new D/TipSpec, PasteSpec, CalibrationArea, PadCalibrationSettings, DoseRecord, PadCalibrationRecord; T/dispense/DispenseDbMigrationTest, DoseRecordTest; existing DispenseDbTest, DispenseProfileTest (only if needed) |
| `pc-sim` | 1 | – | S/SimPaste, S/SimBalance (new); D/Balance, D/ManualBalance (new); S/SimScene (paste, coupons, balance, blank.bgr), S/SimBoardRenderer, S/SimBoardCamera, S/SimVacuum (coupon planes); O/util/GcodeServer (paste hook + Count line); T/dispense/RecordingGcodeServer (`paste`); T/sim/SimPasteTest, SimBalanceTest, GcodeServerPasteHookTest, SimPasteInspectTest; existing T/sim tests only if a default moves; src/test/sim/il_sim_scene.py (`--paste`, `--coupons`, `--balance`, `--bench-blank`, `n2OffsetMm`, `needleZOffsetMm`) |
| `pc-stream` | 2 | pc-model, pc-data; BI `plumbing`; AR `tasks-api` (for the shared lines) | D/DispensePlanner, D/DispensePlan, D/GcodeStreamBuilder, D/GcodeProgram, D/GcodeStreamValidator, D/Interlocks, D/PadTypeResolver, D/BoardPadExtractor; D/DoseBlocks, D/PadInstances (new); the §6.5 lines in D/DispenseService (`purgeSpecs(c, n)`, `interlockInputs`, the I11 condition in `runPlan`); T/dispense/PlannerDoseTest, GcodeBlocksTest, ValidatorBlocksTest, InterlocksBlocksTest, ResolverPadMapTest, PadInstancesTest (new); existing GcodeStreamTest, InterlocksTest, BoardPlanningTest (only if needed) |
| `pc-api` | 3 | pc-stream; BI `import-api` (rebased on it) | A/PadProfileEndpoints, A/PadProfileViews, A/PlanAgain (new); A/PadTypeEndpoints; the lines in A/Views (`addTo`, `padType` fields), A/ApiServer (register PadProfileEndpoints), A/DispenseEndpoints (PROTECTED_NESTED, NESTED_SETTINGS, the SIM-only floors in `PUT /api/config`, `estimate` passing blocks and purge spots); the preview lines in BI's import (`B/BoardImporter` or `A/BoardImportEndpoints`: `packages[].profileCheck`, `NEEDS_PROFILE` replacing `NO_PAD_TYPE`, `needs-profile`); T/api/PadProfileApiSimTest (new) |
| `pc-task` | 4 | pc-api, pc-sim | A/PadCalibrationJob, A/PadCalibrationJobs, A/PadCalibrationEndpoints, A/SimEndpoints (new); D/DoseBlocksRequest, D/DoseBlocksResult, D/PadLadder, D/WeighPlan (new); D/DispenseService (`runDoseBlocks`, `applyPadCalibration`, area and coupon reservation, stop epoch, the coupon probe path); D/SurfaceProbeRequest (`frameCheck`, `windowMm`); D/DotImager (`inspectAll`, `fieldOfViewMm`), D/OpenPnpDotImager; the lines in A/ApiContext (jobs, lock), A/MachineEndpoints (stop order), A/DispenseEndpoints (`status.padCalibration`), A/ApiServer (register PadCalibrationEndpoints, SimEndpoints); T/api/PadCalibrationJobTest, PadCalibrationSimTest; T/dispense/PadLadderTest, WeighPlanTest, StopEpochTest (new) |
| `pc-web` | 4 (starts once AR web-ui and BI web-render, web-flow merged; codes against §12–§13; its Playwright finishes after pc-task) | pc-api; AR web-ui; BI web-render (devproxy `--fixtures`, index.html order), BI web-flow | W/padprofile.js, W/padprofile.css (new); W/calibrate.js; the hook lines H-P1…H-P9 (§13.9) in W/core.js, profile.js, run.js, records.js, boardimport.js, padinspect.js, boardview.js, index.html; src/test/web/devproxy.py (`--route-fixtures DIR`, development only); src/test/web/pad-profile.playwright.cjs, src/test/web/fixtures/padprofile/*.json (new); one line in src/test/web/run-webui-sim-test.sh; T/api/PadProfileUiSimTest (new) |
| `pc-integration` | 5 | all; AR and BI integration | src/test/sim/il_sim_acceptance.py (`padcal`), il_ui_session.py (`--padcal`); docs (§16), W/DEV_NOTES.md; fixes after the merge |

- PC runs at most three builders at once (wave 1: model, data, sim; wave 4: task, web).
- **Why each wave-1 slice compiles alone**: pc-model needs only `java.*` and the one D/DotSizer delegation it owns;
  pc-data touches only engine data classes; pc-sim defines `D/Balance` itself and reads the scene.
- **Contracts** coded against without seeing each other: §3.1 and §4.6 classes and JSON; `P/DotGeometry.layoutN`,
  `P/LadderStats`; §5 columns, settings and `DispenseDb` methods; `DoseBlocks` and `forBlocks` signatures (§6.2–6.3);
  `DoseBlocksRequest/Result` (§7.3); `Balance` (§10); `SimPaste` API and scene JSON (§11.2–11.3); every route and JSON of
  §12; ids of §13; the hook lines of §13.9.
- **Joint merge order** (with AR §13 and BI §10.3): wave 1 — AR (merged), BI kicad, plumbing, web-render, art; PC model,
  data, sim. Wave 2 — AR tasks-api, BI import-api (rebased on it), AR web-ui, BI web-flow; PC stream (after BI plumbing and
  AR tasks-api). Wave 3 — AR and BI integration; PC api (after BI import-api). Wave 4 — PC task; PC web (after AR web-ui,
  BI web-render/web-flow and both integration slices). Wave 5 — PC integration. Green at every merge:
  `env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -o -q -T1 -Dmaven.test.skip=true package` and `mvn -B -o -T1 test`.

**Files this design changes that AR or BI slices also change**: D/DispenseService.java, A/DispenseEndpoints.java,
A/Views.java, A/ApiServer.java, A/ApiContext.java, A/MachineEndpoints.java (AR tasks-api; BI import-api for
DispenseService, DispenseEndpoints, Views, ApiServer); D/DispensePlanner.java, D/DispensePlan.java, D/PadTypeResolver.java,
D/BoardPadExtractor.java, D/GcodeStreamBuilder.java, D/GcodeProgram.java (BI plumbing); B/BoardImporter.java or
A/BoardImportEndpoints.java (BI import-api); W/core.js, W/profile.js, W/run.js, W/records.js (AR web-ui; BI integration
for run.js and records.js); W/boardview.js (AR web-ui, BI web-render); W/padinspect.js, src/test/web/devproxy.py (BI
web-render); W/boardimport.js (BI web-flow); W/index.html (BI web-render, web-flow); src/test/web/run-webui-sim-test.sh
(AR web-ui, BI integration); src/test/sim/il_sim_acceptance.py, src/test/sim/il_ui_session.py (AR and BI integration);
docs/instancelabs/WEB_API.md, DISPENSE_API.md, UI_DESIGN.md, REAL_MACHINE_TODO.md (AR and BI integration);
W/DEV_NOTES.md (AR web-ui, BI integration). Already merged in AR wave 1 and owned by nobody now, but written by AR's
engine-core and sim slices: D/DispenseConfig.java, D/GcodeStreamValidator.java, D/Interlocks.java, D/DotImager.java,
D/OpenPnpDotImager.java, D/SurfaceProbeRequest.java, S/SimScene.java, S/SimVacuum.java, S/SimBoardRenderer.java,
S/SimBoardCamera.java, O/util/GcodeServer.java, T/dispense/RecordingGcodeServer.java, src/test/sim/il_sim_scene.py (AR §16
item 1 may still touch the first three through its planner call sites: rebase, do not reorder).

---

## 18. Open questions (for the main thread; the design takes the stated default)

1. **Weigh coupons, not the cup.** Two blank-PCB coupons (≥ 40 × 30 mm, ≥ 3 g) on named places, weighed once at the end
   (weigh, wipe, weigh again). Is there room on the bed for two coupons, or is the balance itself on the bed (then the
   coupons could stay and only the readings are typed)?
2. **Fine-pitch cap at a 0.25 mm print** for families without a validated anchor (the critique suggested 0.15–0.20).
   With 0.20, SOIC-8 has no plan within −25 %/+10 % (two quarter turns are +22 %). Keep 0.25?
3. **−25 % / +10 % band.** It excludes one turn on the nano LED_0805 (+33 %) and gives it a half turn (−33 %, a fallback
   that only the operator can raise). The bench thread said "≈ one turn". Keep?
4. **DO-220AA pin and the package rule.** The pin (0.61 mm wide) has no plan (a half-turn dot reaches 0.28 mm past it;
   one quarter turn per pad breaks R6), so the nano shows 85 `tip` pads vs the bench's 84. Pads of a package whose other
   pads cannot be dispensed (USB tabs, VQFN EP, DO-220AA tab) get a plan with the *partial* note instead of `tip`. Allow
   a single quarter turn on such pins (marginal), and is pasting partial packages wanted?
5. **Tip mismatch** (a type calibrated with another tip) is a Note (the gate is unchanged). Block it instead?
6. **Boards with undispensable pads** (nano VQFN/USB, mobo LQFP) stay blocked by `refuseFlaggedPads = true`
   (D/DispenseConfig.java:170). Readiness offers "Dispense the rest" as a link to that setting. Add a per-run "skip N
   pads" fix-it?
7. **Homing.** AR §1 says nothing but Register, Probe and Run homes silently; brief decision 3 adds it to this job. The job
   says so before it starts (§7.9). Confirm.
8. **The test-board ladder** (§9) is deferred. Follow-up PR?
9. **Freshness 8 h** for dose records, with no drift data. Acceptable as a setting?
10. **k = 0.5 for pads above 4 mm²** is a placeholder (IPC-like 0.2–0.4). Keep until a test board says otherwise?
11. **A measured dot-gap break** (a calibrated type's dots measured closer than 0.20 mm to a neighbour's on a new board)
    is an `outside` Note. Should it become a flag that goes through the existing gate?
12. **Same-pad dot pitch** ≥ d/2 + OD/2 (0.94 mm for half turns) from the assumed 0.72 mm OD; it makes 0805 one full turn.
    Keep until the OD is measured?

---

## 19. Critique log

Each issue: what was done, or why it was rejected (verified against the code and the bench files first).

### physics-safety

| issue | outcome |
|---|---|
| B1 footprint law on non-steady dots, string evidence misread | **Fixed** §1.4, §1.5, §1.9, §14.1: refit on steady normal-lift points (excluded start-up, slow-lift and the double-counted threads, all verified in the run files); two regimes with a band; tests assert the points and the band. The band's upper edge (not the critic's 0.77 fit) is used for geometry, so SOT-23/SOIC-8/headers stay marginal. |
| M1 smallest dose on the mean mass; blank test blind to hop threads | **Fixed in part** §1.5, §7.4: lowest-phase mass stated (0.41–0.47 mg); quarter turns need the hop test (≥ 60 hops at the group's own pad-to-pad distance, blank + coupons); doses outside the tip's set are a run refusal (§6.5). **Rejected** "d ≥ 1.2 × OD": the sixth at d 0.68 ≈ the assumed OD transferred 40/40 on pads and 6/6 on the blank (PT/todo.md:108-109), so face width is not the observed limit. |
| M2 phase per pad/part not controlled | **Fixed** §1.2 R5/R6, §6.1, §6.4 rules 4 and 6, §7.6: pads whole and at least half a turn per pad (the validated 0603's ripple factor bounds every pad); per-pad spread reported; validator checks consecutive pads and block starts (which fix every dot's phase). Phase checks are on area statistics with explicit operating points (§7.4). |
| M3 records reused across power cycles, regulator, paste; anchor recipe | **Fixed** §1.7, §5.3, §12.2: partial-dose optical and coupon records go stale after a reconnect or a start-angle change; the regulator and paste-life attestations are required and stored. **Kept** `POST /api/paste`: the paste-life tick (W/core.js:569, W/run.js:1983-1989) attests working life, not a syringe change, and is browser-local. **Rejected** "group back as outside/stale after a reconnect": records age, types don't; I11 already refuses partial blocks until the start angle is re-set (§1.7, §11.7 test). Anchor vs recipe: the anchor is a reflow-accepted mass per area, not a dose record (§1.3). |
| M4 ladder statistics thin, coin-flip phase test, no pooling, single weighing | **Fixed** §7.4–7.6: binomial thread and miss rules with ≥ 60 hops (weigh dots imaged too), harmonic fit with an upper bound, drift as a slope test, first 2 dots after a cross excluded, every fresh record pooled including failures, mass confirmed only by two readings within ±5 % (two coupons per job). Operating points computed (`/tmp/pc_rev/stats.py`). |
| M5 anchor ignores family; symmetric ±25 % | **Fixed** §1.3, §4.5: anchors per family from weighed validated types; κ confidence medium; −25 %/+10 %; the control weighed and its drift reported, never silent rescaling; fine-pitch cap for families without an anchor (0.25 mm, open question 2). |
| M6 per-type dose below the tip minimum or above what was run | **Fixed** §1.2 R1/R7, §5.1, §6.4, §6.5: doses {400, 800, 1600}; PLAN refusal below the tip minimum or breaking R6; I6-feed demand check against 2.56 mg/s; validator caps purge/phase dots at 1689 steps and the program total. **Rejected** the new blocking PadFlag: brief decision 1 keeps blocking on the gate, and a run interlock refusal is as strong without changing the flag set. |
| M7 0805 = layout artefact; neighbours as minimum dots | **Fixed** neighbours with their own planned dots (§3.1, §4.3 step 4); dots move inward when they overhang (§4.3 step 3). **Rejected** "overlapping dots on the same pad are fine": the needle face would land in the earlier dot (taller than the gap out to ≈ 0.54 mm from its centre), so the same-pad pitch is ≥ 0.94 mm (the bench thread's ~0.9), and 0805 stays one full turn for that physical reason (§1.6, open question 12). Dot-gap flag: open question 11. |
| M8 area and coupon placement checks circular or missing | **Fixed** §7.2 steps 1–2, §7.3 step 5, §12.2: the area may not overlap the purge row, coupons or the plan's pads + 3 mm; every dot spot must pass BareSpotCheck before dispensing; re-probe after a home or reconnect; manual ladder and weigh go through the same reservation (§8). **Rejected in part**: the area's corners stay probe targets, because the proof that they lie on the blank is the camera look before every touch, the frame check and the bare check of every spot, not the hull; feeder rows are not modelled in the dispense config, and AR §16 item 6 chose the camera look over keep-out zones. |
| M9 weigh coupon never located in XY | **Fixed** §7.2 step 2, §7.5: camera look at the grid's corners pushed out by a 3 mm margin, probe window ±0.3 mm, floor-only frame reference stated, coupon ≥ 3 g, coupon dots imaged and counted before the readings, expected mass from the found dots. |
| M10 STOP between job steps | **Fixed** §7.1, §7.8, §14.1 StopEpochTest: STOP stops the job first under its submit lock; engine stop epoch; Cancel during an engine step = engine abort. |
| M11 SIM confirms its own model | **Fixed** §11.2–11.3, §11.7: footprint law, noise (cv 0.08) and ripple differ from §1; threads, gap, feed and idle modelled; ten negative-control scene variants with expected outcomes. |
| M12 manual path skips the phase lock | **Fixed** §8, §5.1: the guide's ladder and weigh steps run the job's phase-locked engine steps; `characterized` for own-dose types needs the evidence engine-side. |
| m1 ladder dots overrun the area, doses touch | **Fixed** §7.4: insets d95/2 + 0.5 mm, one row pitch between doses, reserved height includes insets. |
| m2 tare procedure, weigh target, delay | **Fixed** §7.5, §5.4: two absolute readings (weigh, wipe, weigh again), no tare; `weighTargetMg ≥ 50` off the SIM; delay recorded and confirmed after 15 min. Check weight: optional, REAL_MACHINE_TODO §15 item 7. |
| m3 test-board variants unchecked | **Deferred** with §9; the rules for when it is built are written there. |

### ux-test-slices

| issue | outcome |
|---|---|
| B1 no way to start from the UI | **Fixed** §13.6 Pad calibration card (area, left, Blank wiped, New paste loaded, coupons, pending readings), default area, inline fix-its in pad-cal (§13.5), Playwright set-up and steps 11. |
| B2 slices don't compile in parallel | **Fixed** §17: pc-api waits for pc-stream (wave 3); `estimate` moved to pc-api; BI import-api placed in the merge order before pc-api; the grid lives in `P/DotGeometry` with D/DotSizer delegating (owned by pc-model); pc-web waits for BI web-render's `--fixtures`. |
| M1 pc-web not loadable until wave 4; H-P3 not one line | **Fixed** §13.3, §13.9, §17: pc-web owns its index.html tags and every hook line after AR/BI merge; H-P3 is `IL.pt` + one guarded `profileHooks` call; newForPackage untouched in profile.js; PadProfileUiSimTest checks the static files. |
| M2 two mid-job interruptions, hand placement at machine X/Y | **Fixed** §7.2, §7.5, §7.9: coupons laid before Start, one operator step at the end, 3 mm margin with a camera check, coupon snapshots in the prompt, places named `coupon1`/`coupon2`. |
| M3 walk-away discards the coupon | **Fixed** §7.1, §7.5, §7.8, §13.5: no timeout at the readings (until stale; confirm after 15 min); lock released; `Waiting for you since`; `Type the readings` and `Finish the calibration`; pending coupons survive a restart. BI's upload preview is refused only during engine steps, with BI's own text. |
| M4 summary vs press2 | **Fixed** §13.3–13.5, §14.4 step 7: every entry opens pad-cal before start (single press, ellipsis); only `#btn-pcal-start` is press2; validated demotions named. |
| M5 edited suggestion overwritten | **Fixed** §4.2, §7.6, §14.1 SuggesterTest/DeciderTest. |
| M6 duplicate preview warning and inspector flags | **Fixed** §12.6, §13.7, §13.8, §14.4 steps 1 and 6. |
| M7 second pad renderer | **Fixed** §13.4: lite IL.boardview with `update({sugg})` (H-P8); drawPreview dropped. |
| M8 Use not one click; no new plan id | **Fixed** §12.1 (`newPlanId`), §12.3 (`result.newPlanId`, AR catch-up rule), §14.4 steps 5 and 9. |
| M9 nagging tip groups | **Fixed** §3.3 counts, §13.2, §13.3: only fixable groups counted; tip and notes in collapsed details; tip-only readiness text with the setting link. |
| M10 texts | **Fixed** §3.4, §4.6, §6.1, §7.5, §7.10 with the proposed wording; frozen in ProfileCheckTest. |
| M11 existing texts become false | **Fixed** §13.3, §13.6 replacement texts. |
| M12 by-hand path measures something else | **Fixed** §8: same engine steps, limits shown, shots collapsed. |
| M13 Playwright contradicts the design, no set-up | **Fixed** §14.4: Blocked, 3 fixable + tip details, "blank results were kept", set-up step 0, upload, inspector, chip, reload, runtime budget. |
| M14 SIM coupon reused | **Fixed** §11.6 (emulated human wipes), §11.3 (coupon colour = the bench blank's), §14.3 step 5. |
| m1 SIM colours vs AR tests | **Fixed** §11.3: `blank.bgr` scene field, default today's; `--bench-blank` for padcal scenes. |
| m2 test shortcuts on the real machine | **Fixed** §5.4, §12.1: floors off the SIM, refusal text, API test. |
| m3 single-press motion | **Fixed** by M2: no mid-job motion prompt remains; the head parks over the purge row and the prompt says so. |
| m4 'all' undefined | **Fixed** §7.2 ("all" = new and calibrate-existing groups; weigh allocation tied to coupons), §12.2 `groups: "all"`. |
| m5 coupon probe frame check | **Fixed** §7.2 step 2, §7.3 step 2: `frameCheck false`, window ±0.3 mm; REAL_MACHINE_TODO §15 item 14. |
| m6 scope | **Fixed**: §9 deferred; R1 limited to {400, 800, 1600}; tip config-only, freshness on `needleChangedAtMs`, `POST /api/tip` dropped. |
| m7 WeakHashMap memo never hits | **Fixed** §3.5: keyed by the PlanStore.Entry instance. |

---

## 20. Main-thread decisions on the open questions (2026-10-28, binding for the slices)

1. **Coupons.** Keep the weigh/wipe/weigh coupons. One coupon place is enough to start a job; two are recommended (the
   second reading confirms the mass, §7.5). Whether the bed has room for two is asked of the user in the report; until
   then the before-start view says what a missing `coupon2` costs (types stay `provisional` until a second weighing).
2. **Fine-pitch cap stays 0.25 mm** of print equivalent for families with no validated anchor (the user's validated
   0603 dose is ≈ 0.33 mm; 0.20 leaves SOIC-8 with no plan).
3. **Band −25 %/+10 %: keep it as the confidence band, but the fallback is the nearer allowed dose, ties to the
   larger.** Paste that is too small failed visibly after reflow (the sixth turn left bare pad), while the half turn on
   0603 did not bridge. So the nano LED_0805 gets **one turn** (+33 % vs −33 %: a tie → larger), marked low
   confidence, which matches the bench thread's "about one turn". The −25 %/+10 % band still decides `high/medium` vs
   `low` confidence.
4. **DO-220AA pin and partly undispensable parts.** No single quarter turn on such pins (R-rules stand); 85 not-dispensable
   on the nano is the honest count. **A part with any undispensable pad is treated whole**: by default none of its pads
   are pasted (a part whose pins cannot be reflowed is hand-soldered, and paste on its tabs only gets in the way); the
   group text says so and offers "paste the dispensable pads of this part anyway" per package (stored as a mapping
   option, default off).
5. **A pad type calibrated with another tip blocks** (it is treated as below the gate, reason "calibrated for tip X,
   mounted tip Y"). Mass per turn and the dot size change with the tip (bench: flow ∝ ~1/r^1.8 and tube length), so it
   is not a note.
6. **Undispensable pads: add the per-run skip.** Readiness offers "Skip these N pads for this run" (listed by part),
   recorded in the run (`skippedPads`, shown in Records), instead of steering the operator to the global
   `refuseFlaggedPads` setting. The setting stays as it is.
7. **Homing: confirmed.** A job homes first when needed and says so before it starts (the user asked for home at job
   start; AR tasks-api implements the run's home + Z check).
8. **Test-board ladder: in this branch, as the last calibration slice (`pc-ladder`), after pc-web.** The user's bench
   note makes it the path to `validated` ("each new pad size costs one test board, once"). Scope: plan a real board
   with one pad type's instances split across up to 3 labelled doses from {400, 800, 1600} steps that pass §4.3's checks
   (others marked "may bridge", confirm to include), the run record keeps which part got which dose, and Validate shows
   the parts per dose so the operator picks the winner after reflow → new `validated` version at that dose. No reflow
   automation. If time runs short it is the slice to drop, and the report says so.
9. **Dose record freshness 8 h**: accepted as a setting (`doseRecordMaxAgeH`), stated as a placeholder until there is
   drift data.
10. **k = 0.5 above 4 mm²**: accepted as a placeholder, always `low` confidence until a test board validates it.
11. **Measured dots breaking the 0.20 mm dot gap on a new board: a flag through the existing gate** (bridging is a
    defect, not a note). Reason text "dots may bridge to {neighbour}".
12. **Same-pad pitch from the assumed 0.72 mm tip OD: keep**, and add "measure the 22G tip OD (bottom camera)" to
    REAL_MACHINE_TODO; the value lives in `TipSpec` so a measurement changes it in one place.

Slice order (fits the joint plan): pc-model, pc-data, pc-sim now (no dependencies); pc-stream after BI plumbing is
merged (AR tasks-api is merged); pc-api after BI import-api; pc-task; pc-web with/after AR web-ui and BI web-flow;
pc-ladder; pc-integration with the wave-3 integration.

### 20.1 Follow-up decisions after pc-model / pc-data / pc-sim (2026-10-29)

13. **Decision 3's band is ±40 % around the nearer allowed dose** (confirmed). The three changed §1.9 rows stand: nano
    LED_0805 1×1600 (+33 %), nano SOT-223 pins 2×1600 (+31 %), mobo 1TS003B 1.15×1.00 1×1600 (+28 %), all `low`
    confidence.
14. **Geometry failures split per instance (pc-stream / pc-api).** A group keeps one plan, but instances whose own
    neighbours make that plan break the dot gap or overhang (e.g. nano bottom C3.1/C4.1 at 1.27 mm) are reported and
    blocked as their own sub-group ("2 of 14 pads are too close to a neighbour"), instead of blocking the whole group.
15. **The ripple negative control fails clearly:** the J variant uses ripple 0.6 (or its own lower transferRatio) so
    the half-turn split check fails with margin; a negative control that only just fails proves little (pc-task).
16. **Decision 5 also blocks in plans:** pc-stream gives pads whose type was calibrated with another tip
    `STATUS_BELOW_MIN` with the tip text (the preview already blocks).
17. **Hand edits reset calibration provenance (pc-api):** a pad-type edit that changes the pad or the dose writes
    origin `manual` and clears doseFp / massPerPadMg, so it cannot become a family anchor.
18. **`R/padcal.kicad_pcb` belongs to pc-web** (with the board-import parser merged it can be checked); one recipe
    formatter (`DispenseProfile.fingerprintDiff`) for both texts (pc-api).

---

## 21. As built (2026-10-29)

- **Merged** on `il/bench-sync-autoreg` (b81d73d21): `pc-model`, `pc-data`, `pc-sim` and `pc-stream` (per-type doses in
  the planner, the stream, the validator and the interlocks), with review 1's dose-stream fix: one tight instance no
  longer fails its whole group, and the planner holds back each pad that is too close to a neighbour (decision 14).
- **Merged** after it (a4bb6d20d and `il/pc-web`): `pc-api` (the routes of `WEB_API.md` §3.4), `pc-task` (the job,
  `runDoseBlocks`, the weighing, the decision, `applyPadCalibration`, the stop epoch, `/api/pad-calibrations…`,
  `/api/dose-records`, `/api/coupons…`, `status.padCalibration`, the SIM routes: `WEB_API.md` §3.5) with review 2's
  fixes, and `pc-web` (`padprofile.js` and its hooks, Needs a profile, the suggestion card, the `pad-cal` view,
  Calibrate › Pad calibration; `UI_DESIGN.md` §8.3–8.4, `DEV_NOTES.md` §5).
- **`pc-integration`** (`il/pc-integration`): the `padcal` SIM acceptance (`il_sim_acceptance.py padcal`: §14.5 on
  `padcal.kicad_pcb`, the Nano's upload and calibration, every negative control of §11.7), `il_ui_session.py
  --padcal`, and the docs of §16 written from the code.
- **Not in this PR: `pc-ladder`** (the test-board ladder, decision 8) is a follow-up; `validated` stays on the
  existing Validate guide until it comes. Decision 6's per-run skip has no API yet (`RunResult.skippedPads` exists):
  runs skip undispensable pads only with `refuseFlaggedPads: false`, the switch Setup › Settings › Refuse flagged pads
  (`#set-refuseflagged`, the link of `Dispense the rest`); with it off the Pad types item is a note that counts ready
  (`9 pads skipped (Refuse flagged pads is off): …`) and the run confirm names the pads skipped (rc1 review).
- **Seen in the SIM acceptance:** the Nano's LED_0805 stays `provisional` after Calibrate automatically (decision 3's
  one turn is +33 % on the machine, +47 % with the SIM's 2.30 mg per turn; half a turn −33 % / −27 %: no allowed dose
  is within −25 %/+10 %), so the Nano's 8 LED pads need a reflowed test board or a hand raise; a block switch from
  half to quarter turns can make the phase dot a whole turn on the purge row (`REAL_MACHINE_TODO.md` items 42, 54).

Where `pc-api` differs from §12, the code is right:

- `GET /api/calibration-area` exists (the area as set, where it is in effect, what is left, the default).
- `POST /api/package-mappings` also takes `pastePartial` (decision 4's per-package option) and answers it with the
  mapping; a body needs `typeKey` (null clears) or `pastePartial`.
- `POST /api/pad-profile/apply` answers `planError` too when planning again failed (the changes stand, `planStale`
  true). `apply`, the mappings, `POST /api/paste` and the area routes are refused while the engine is busy.
- `POST /api/pad-types` accepts `origin` (`manual` | `suggested`: an edited suggestion keeps its origin) and a
  `mapping` `{packageId, padNames?}` written with the type. Decision 17 is enforced on update: a change of the pad or
  the dose writes origin `manual` and clears `doseFp` and `massPerPadMg`; a change of the dose also stamps the mounted
  tip's key.
- `PUT /api/config` off the simulator refuses `padCalibration.ladderDotsPerPhase`, `minLadderDots`, `minHops` and
  `weighTargetMg` below their real floors (`… below N is allowed in the simulator only`), unless the stored value was
  already below.
- The plan view's profile check is memoised per plan entry while its inputs (types, gate, tip, profile steps, dose
  fingerprint, partial-paste packages, evidence) hold; a check that fails leaves the plan view whole with
  `profileCheckError`.

Review 1's dose-stream minors and review 2's calibration minors, fixed in the final fix round:

- The stream takes each dot's pad from the planner's `padIndex` (two pads of one name are no split pad); a run subset
  keeps each dot's pad index and dose.
- `DispenseProfile.validate` takes 400, 800 or 1600 steps per dot (R1): a two-turn profile is a CONFIG refusal before
  anything moves, not a stream refusal after the home.
- A "Test run with fewer dots" lays whole pads: the cut falls before the pad it would split (400 `a test run lays whole
  pads: the first pad, A.1, has 2 dots, so ask for at least 2` when the first pad alone is larger).
- §7.5: the readings' answer refuses one coupon typed twice before writing (400 `coupon1 is typed twice: type each
  coupon's two readings once`); `POST /api/coupons/{recordId}/reading` also takes `{skip: true}` (a coupon left after
  Cancel or STOP at the readings is freed: `coupon1: weighing skipped; wipe its 68 half-turn dots off before it is used
  again`); mass per dot is per dot laid, in the mass row and the pooled evidence.
- §7.2: after the ladder a coupon whose new dose's grid would leave the margin its coupon step checked weighs its
  planned dose (job warning `the quarter-turn weigh dots do not fit where the camera checked coupon1: it weighs the
  half turn as planned; Calibrate again weighs the quarter turn`); a coupon place on the purge row or within 3 mm of
  the plan's pads is refused at the start (`The weigh coupon place coupon1 overlaps the purge row: move it (Setup ›
  Places › coupon1).`); under `homeBeforeRun` "always" the preview counts a home before every engine step (`Homes
  before each of its 5 steps (the setting is: always home first; ≈ 2:05)`).

§15 is in `REAL_MACHINE_TODO.md` (items 38–52; 53–54 from the integration slice).
