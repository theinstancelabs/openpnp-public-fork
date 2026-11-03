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

---

## 22. Tip setups, per-tip profiles and calibration labels

Status: design for implementation (2026-10-29), revised the same day after two critiques (engine and data; flows and
UI: §22.12); written after §21; built after `finish-pr2` on a branch off `il/rc1` (PR #3, stacked on PR #2). As built
(2026-10-30, `il/tip-setups`): §22.13 lists every place the code differs. The user
(2026-10-29, verbatim): "Another thing is that we should have calibration profiles for pads. Also assuming a certain
dispenser, not head/nozzle/needle, I don't know, but you know what I mean. After we self-calibrate I should just take a
picture and mark down what this was calibrated for, like 0603 for a 22 bent tip versus a straight needle tip, etc." The
main thread's decisions D1–D7 (PT/tip-setups-brief.md) are binding; each is cited where it applies. Code is cited as it
is on `il/bench-sync-autoreg` a4bb6d20d (= `il/rc1` today); `il/fixround1` and `il/fixround1b` move a few lines of
A/PadCalibrationJob.java, D/DispenseService.java and D/DispenseConfig.java (the methods named stay), and `il/fixround2`
(session 4's F9, F10, F14: the touch-off finds its dots at their own spots, default pitch ≥ 1.6 mm) is merged before
this is built. Web files as on `il/pc-web` 197da6550, cited `W/…@pc`. `B/` = `src/main/java/org/openpnp/instancelabs/
board/`, `S/` = `…/sim/`, `V/` = `…/vision/`, `T/` = `src/test/java/org/openpnp/instancelabs/`.

### 22.0 Where it starts

| what | today | source |
|---|---|---|
| the tip | one `TipSpec` in the config: key `ad22tta-b`, name `22G tip` (the class default, so every saved `dispense-config.json` carries it: Gson writes every field), ID 0.41 mm, OD 0.72 mm (assumed), smallest dot 400 steps; changed with `PUT /api/config` (a nested setting) | D/TipSpec.java:18-29, D/DispenseConfig.java:202-203, :526-532, A/DispenseEndpoints.java:60-62 |
| pad profiles | a pad type has **one** current version (the highest per key) and every reader takes it; a version names a tip (`tip_key`), null on the seeds and on types that never had a dose of their own (an update copies the previous version and stamps the mounted tip only when the dose changes) | D/DispenseDb.java:60-62, :490-512; A/PadTypeEndpoints.java:196-197, :350-352 |
| another tip | a type whose `tipKey` is another tip is below the gate (decision 5) in plans, in the check and at Run; a null `tipKey` counts as no tip at all. So calibrating 0603 with a 25G writes chip-0603 v(n+1) for the 25G and **replaces** the 22G profile; mounting the 22G again finds a blocked type | D/TipSpec.java:32-34, D/DispensePlanner.java:205-209, P/ProfileCheck.java:261-267, D/Interlocks.java:129-145 |
| dose records | name the tip (`tip_key NOT NULL`); another tip's are stale, so are records dispensed before a needle change | D/DispenseDb.java:251, D/DoseRecord.java:122-128 |
| dispense profile | one `DispenseProfile` for every tip (pressure, turn, speed, dwell, gap), which also holds the screw start angle: a calibration of the controller's power-up and the screw, not of the needle | D/DispenseConfig.java:57-58, D/DispenseProfile.java:23-25, :46-49 |
| paste model | the 22G bent taper's at 18 psi (M_rev 2.11 mg/rev, the §1.4 dot sizes), used for every tip at about 20 call sites; the profile check's tip defaults to `Tip.g22()` when none is passed | P/DoseModel.java:19-69; e.g. P/Suggester.java:78-93, D/DispensePlanner.java:283, :386, A/PadCalibrationJob.java:459, :512; P/CheckInputs.java:17, P/ProfileCheck.java:224-226 |
| needle change | "I changed the needle" stales the needle Z frame and the touch-off (I4), never refuses, and leaves the XY trim | D/DispenseService.java:394-400, D/Interlocks.java:183-193 |
| needle XY | the needle lands at camera + N2 head offset (machine.xml: one value for every tip) + a trim of at most ±1 mm, which moves every dispensed dot | D/DispenseConfig.java:88-93, :704-705; D/GcodeStreamBuilder.java:572-578 |
| config writes | `PUT /api/config` copies the config outside the service lock, merges the patch into the whole copy and writes it all back | A/DispenseEndpoints.java:475-533 |
| locks | a job starts on the jobs' own monitor; a task claims `busy` when it is submitted; `savePadType` takes no lock; a job reads the config again at every step and stamps its records with the tip of that moment | A/PadCalibrationJobs.java:101-113; D/DispenseService.java:3403-3407, :644-648; A/PadCalibrationJob.java:1106, :1143-1146 |
| dispenser, form, pictures | nothing: no dispenser identity, no bent or straight, no photo, no "calibrated for" | – |

The real machine (PT/software-autoregister.md, "SESSION 4 RESULTS", 2026-10-29): the needle frame belongs to the
mounted needle. K = 65.60 came from the side camera (needle level with N1) and a touch-off dot ladder confirmed it
(round dots at commanded gaps 0.00–0.20 mm, strings from 0.25): every needle needs its own frame. Needles on hand
(calibration brief, mem:dispenser-hardware-facts): ADL AD22TTA-B 22G bent (30°) tapered plastic, ID 0.41 mm, in use;
DSKIT1: straight 0.5 in (12.7 mm) steel 14/18/21/23/25/27/30G (25G ID 0.25, 27G 0.20, 30G 0.15) and straight tapered
14/18/22G; SA7529: 45° bent 0.5 in steel 14/15/18/20/21/22/25/27/30G; one ITW 15898 19G footed needle. Dispenser:
Vieweg DV-5005 (400 rpm, 1.5 bar, D/DispenseProfile.java:28-31) with the DFS16-A auger. A finer tip changes the flow
(shear-thinning paste, about 1/r^1.8 and the tube length, §1.5 and the brief): mass per turn and dot size do not carry
over from one tip to another. Where a tip lands changes too: a bent tip's end sits off the hub's axis (about 2.5 mm
for 5 mm of tube past a 30° bend) and turns with how far it is screwed onto the Luer, and the see-saw moves the needle
about 0.3 mm in X/Y per mm of Z (PT/lessons.md:6), so a tip of another length lands elsewhere as well.

**What changes.** A library of tip setups, one mounted (D1). Each setup has its own dose plan per pad type and its own
dispense profile; what describes the pad (size, pattern, family, k, t) stays one definition for every setup (D2). A
needle change is a fact the engine records at once and never refuses; mounting another setup is that fact plus the
switch of the setup (D3). Where the needle lands becomes a measured value per setup, so a bent and a straight tip can
both be kept set up. Every calibration saves a "calibrated for" label, the top camera's photo of its dots and the
tip's picture (D4); the UI shows the mounted setup wherever the operator commits (D5); schema 3 only adds (D6). Out of
scope (D7): recognising a tip from its photo, per-tip profiles of the placement nozzles, and any vision on the tip
picture (it is a record, not a measurement).

| decision | where |
|---|---|
| D1 a library of tip setups, one mounted, seeded with the 22G bent taper | §22.1 (tables, fields, seed, migration) |
| D2 pad type versions and the dispense profile per setup; a copy only when asked, never above provisional | §22.1 (`parkedTips`), §22.2 |
| D3 mounting = a needle change (frame, touch-off, landing stale; records not reused; profile switches) | §22.3 |
| D4 label, automatic result photo, tip pictures, one step on the result card | §22.4, §22.5, §22.7 |
| D5 the Setup card, Profile per setup, "for the 22G bent taper", result card, Records | §22.7 |
| D6 additive schema 3, photos as files with a row each | §22.1, §22.4 |
| D7 out of scope | above |

### 22.1 Data model and schema 3

**Key rule.** A setup's key is the tip key the engine already writes (`TipSpec.key`, `pad_types.tip_key`,
`dose_records.tip_key`), so every record made so far names its setup and nothing is rewritten. `pad_types` rows with
`tip_key` null (the seeds and types that never had a dose of their own) belong to the seeded setup `ad22tta-b` (D1).
One helper reads a record's setup everywhere: `Tip.setupOf(String tipKey)` (P/Tip.java, pure; null → `Tip.SEED_KEY` =
`ad22tta-b`), used by `TipSpec.isOther` (D/TipSpec.java:32-34, which today reads null as "no tip"), the planner's guard
(D/DispensePlanner.java:205), the interlock (D/Interlocks.java:130), `PadInstances.typeInfo` (so `TypeInfo.tipKey`
reaching `ProfileCheck.otherTip`, P/ProfileCheck.java:265-267, is never null), the views, and in SQL
`COALESCE(tip_key, 'ad22tta-b')`. Every new pad type version names its setup explicitly (§22.2, writes).

New tables (append-only, triggers as D/DispenseDb.java:210-215):

```sql
CREATE TABLE IF NOT EXISTS tip_setups (
  row_id INTEGER PRIMARY KEY AUTOINCREMENT, setup_key TEXT NOT NULL, version INTEGER NOT NULL,
  name TEXT NOT NULL, dispenser TEXT NOT NULL, tip_model TEXT, gauge INTEGER,
  id_mm REAL, od_mm REAL, od_source TEXT CHECK (od_source IN ('measured','assumed')),
  form TEXT NOT NULL CHECK (form IN ('bent','straight','unknown')), bend_deg REAL,
  bend_toward TEXT CHECK (bend_toward IN ('+x','-x','+y','-y')),
  taper TEXT NOT NULL CHECK (taper IN ('tapered-plastic','straight-steel','unknown')), length_mm REAL,
  min_dot_steps INTEGER NOT NULL, model TEXT, notes TEXT, retired INTEGER NOT NULL DEFAULT 0, why TEXT,
  created_at INTEGER NOT NULL, operator TEXT, UNIQUE(setup_key, version));
CREATE TABLE IF NOT EXISTS tip_mounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('mount','needle','undo','reconcile','seed')),
  setup_key TEXT NOT NULL, setup_version INTEGER NOT NULL, from_setup_key TEXT, needle_changed_at_ms INTEGER,
  head_offset_x_mm REAL, head_offset_y_mm REAL, profile_json TEXT, landing_json TEXT, voided_json TEXT,
  why TEXT NOT NULL, simulated INTEGER NOT NULL DEFAULT 0, operator TEXT);
CREATE TABLE IF NOT EXISTS photos (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('tip','setup','result')),
  source TEXT NOT NULL CHECK (source IN ('bottom-camera','top-camera','upload')),
  setup_key TEXT NOT NULL, needle_since_ms INTEGER, job_id TEXT, dose_steps INTEGER, coupon_place TEXT,
  file TEXT NOT NULL, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL, width_px INTEGER NOT NULL, height_px INTEGER NOT NULL,
  mm_per_px REAL, x_mm REAL, y_mm REAL, caption TEXT, simulated INTEGER NOT NULL DEFAULT 0, operator TEXT);
CREATE TABLE IF NOT EXISTS photo_links (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, photo_id INTEGER NOT NULL,
  pad_calibration_id INTEGER NOT NULL, role TEXT NOT NULL CHECK (role IN ('result','tip','reference')),
  operator TEXT, UNIQUE(photo_id, pad_calibration_id));
CREATE TABLE IF NOT EXISTS photo_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, photo_id INTEGER NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('remove','restore','main')), why TEXT, operator TEXT);
CREATE TABLE IF NOT EXISTS cal_labels (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, pad_calibration_id INTEGER NOT NULL,
  label TEXT NOT NULL, auto INTEGER NOT NULL CHECK (auto IN (0, 1)), operator TEXT);
CREATE TABLE IF NOT EXISTS cal_flags (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, job_id TEXT NOT NULL, setup_key TEXT NOT NULL,
  flag TEXT NOT NULL CHECK (flag IN ('wrong-tip')), why TEXT, operator TEXT, UNIQUE(job_id, flag));
CREATE VIEW IF NOT EXISTS pad_types_by_setup AS SELECT p.*, COALESCE(p.tip_key, 'ad22tta-b') AS setup_key
  FROM pad_types p JOIN (SELECT type_key, COALESCE(tip_key, 'ad22tta-b') AS s, MAX(version) AS v
                         FROM pad_types GROUP BY type_key, COALESCE(tip_key, 'ad22tta-b')) m
  ON p.type_key = m.type_key AND COALESCE(p.tip_key, 'ad22tta-b') = m.s AND p.version = m.v;
```

| table | one row is | written by |
|---|---|---|
| `tip_setups` | a version of a setup; the current one is the highest (as pad types) | add, edit, retire (§22.3); the schema 3 seed and placeholders |
| `tip_mounts` | the history of what was on the dispenser: a needle change (`needle`), a mount of another setup (`mount`), an undo, a start-up repair (`reconcile`) or the setup in use at the upgrade (`seed`) | §22.3; the reconcile at start (below). The config is the truth: this table is the history |
| `photos` | one stored picture: its file and what it shows; `needle_since_ms` = the needle change it was taken after (§22.3) | the tip picture task, an upload, a job's result photos (§22.4) |
| `photo_links` | a photo shown with a calibration record (`pad_calibrations` row): its result photo, a picture of the tip taken for it, or the tip's reference picture at that time | §22.4 |
| `photo_events` | a picture removed, brought back, or chosen as its setup's main picture; the newest `remove`/`restore` row of a photo decides | §22.4 |
| `cal_labels` | a "calibrated for" label of a calibration record; the newest row counts | the job and the by-hand save (auto), the operator's edits (§22.5) |
| `cal_flags` | a job the operator said was made with another tip than its setup: its dose records leave the setup's evidence and dot table, its records show `wrong tip` | `Made with another tip…` (§22.3) |

New nullable columns: `pad_calibrations.setup_key TEXT`, `pad_calibrations.method TEXT` (`auto` | `by-hand`; null =
`auto`, the only kind before schema 3), `pad_calibrations.needle_since_ms INTEGER` (the job's needle token, §22.3),
`dose_records.landing_dx_mm REAL` and `landing_dy_mm REAL` (the mean landing, found − commanded, per axis; today only
its length is kept, `landing_mean_mm`), `runs.setup_key TEXT`.

Setup fields (`D/TipSetup.java`, new; `TipSetup.problems()` checks them engine-side):

| field | meaning | allowed | edit once the setup has a pad type version, a dose record or a mount |
|---|---|---|---|
| `key` | identity, = the tip key on records | `TipSpec.KEY` (D/TipSpec.java:15); default from gauge and form (`25g-straight`, `27g-bent45`, `22g-taper`), made unique with `-2`, `-3`; `undo-mount` is reserved (a route) | never |
| `dispenser` | the dispenser the tip sits on | 1–60 characters, default `Vieweg DV-5005` | never (another dispenser is another setup) |
| `gauge`, `form`, `taper` | needle gauge; `bent` / `straight`; `tapered-plastic` / `straight-steel` | 10–34 or null; `unknown` only on placeholders | never: 409 `another-needle` (§22.3) |
| `tipModel`, `idMm`, `bendDeg`, `lengthMm` | part number; bore at the end; bend angle; tube length from the hub (null = not recorded) | ≤ 60 characters or null; ID 0.05–2.0 mm; 1–90° when bent, null when straight; 3–60 mm or null | as a **correction** (two presses, a `TIP` event with old → new); the ID only within 0.03 mm of the recorded one (a measured bore, not another gauge: 22G 0.41 and 25G 0.25 differ by 0.16) |
| `odMm`, `odSource` | outer diameter at the end, `measured` or `assumed` (shown `from the chart`) | above the ID and at most 3.0 mm (as D/TipSpec.java:44-46) | plain edit |
| `bendToward` | which machine direction the bend points when the tip is on (`+x`, `-x`, `+y`, `-y`); the swap step says it and the landing check counts on it (§22.3) | null when straight or not recorded | plain edit |
| `name` | the words in every text ("not dispensable with the 25G straight") | 1–40 characters, unique among setups not retired | plain edit |
| `minDotSteps` | the smallest dot the tip takes reliably | 400, 800 or 1600 (R1); default 400 | plain edit |
| `model` | `bench-22g` when §1's model (P/DoseModel) describes this tip, else null (§22.2) | only the seed has it | never set by hand |
| `notes`, `retired` | free text; a retired setup cannot be mounted | notes ≤ 500 characters | plain edit; retire and bring back |
| photos | the setup's `photos` rows of kind `setup` or `tip`; its **main picture** is the newest one chosen with `Use as the tip's picture` (`photo_events` `main`), else the newest live `setup` picture, else the newest live tip upload | §22.4 | – |

A setup is **complete** when it has ID, OD, form and taper (not `unknown`); only a complete setup can be mounted. Every
version change of a setup goes into the plan fingerprint (§22.2), so a corrected OD makes plans stale.

**The seeded setup** (inserted by the migration, operator `seed`): key `ad22tta-b`, name `22G bent taper`, dispenser
`Vieweg DV-5005`, tip model `ADL AD22TTA-B`, gauge 22, ID 0.41, OD 0.72 `assumed`, `bent` 30°, `bendToward` null,
`tapered-plastic`, length null, minDotSteps 400, model `bench-22g`, notes `The bench tests of 2026-10-27/28 used this
tip. OD from the chart, not measured; length not recorded. Auger: Vieweg DFS16-A.`

**Schema 3 migration** (`SCHEMA_VERSION = 3`, D/DispenseDb.java:46; `migrate()`, :225-300, becomes `to2()` as today then
`to3()`, run in order from the version found, each in one transaction):

1. the backup of today (`VACUUM INTO dispense.db.v2-<yyyyMMdd-HHmmss>.bak`, :325-336) before the first step that runs;
2. `DROP VIEW IF EXISTS pad_types_current` and `pad_types_by_setup` (both select `p.*`, so any step that adds a
   `pad_types` column drops and recreates both, as :234 and :240 do for the first), the new columns (`PRAGMA
   table_info`, `ALTER TABLE … ADD COLUMN`, which fires no trigger), then both views again;
3. the seven tables and their triggers; the names added to `TABLES` (:48-50) and to a `V3_TABLES` set that
   `createSchema()` skips, as it skips `V2_TABLES` (:51-53, :201-205);
4. the seeded setup's row (when no `tip_setups` row has its key), on a fresh file too;
5. a **placeholder** setup for every other distinct key in `pad_types.tip_key` and `dose_records.tip_key`: name = the
   key, dispenser `Vieweg DV-5005`, form and taper `unknown`, no ID or OD, minDotSteps 400, notes `Made from older
   records that name this tip: fill in its bore (ID) before you use it.` It keeps those records' setup visible and
   cannot be mounted until it is completed;
6. **only on an upgrade** (`v != null`, as :269): a `schema_info` row 3 and a `SCHEMA` event (as :269-286). On a fresh
   file `seedIfEmpty` (:338-378) writes `schema_info` itself and seeds the pad types only when it finds no row there
   (:339-344), so `to3()` must not write one.

A fresh database is created at schema 3. **Nothing existing is rewritten**: a test compares every row that existed
before the upgrade with the same row after it, on the v2 column list (§22.8).

| existing data | belongs to | why |
|---|---|---|
| `pad_types` rows with `tip_key` null or `ad22tta-b` | the seeded setup | D1 |
| `pad_types` rows with another `tip_key` | that key's (placeholder) setup | the key names the tip they were made with |
| `dose_records` | the setup of their `tip_key` | today's freshness rule already keys on it (D/DoseRecord.java:122) |
| `pad_calibrations` with `setup_key` null | resolved when read, first match: the tip key of its job's dose records (`dose_records.job_id = pad_calibrations.job_id`), the tip key of the version it wrote (`version_to`), the seeded setup | a failed or stopped job wrote no version, but its dose records name the tip exactly |
| `runs` with `setup_key` null | not known (shown `—`) | runs did not record the tip |
| `calibration`, `dot_ladder`, `scale_entries` (q model, records only) | no setup; their `tip` text stays | records only |
| `package_pad_type`, `package_options`, `pad_rules` | every setup | a mapping says which pad type a package's pads are: the pad, not the needle |

**Config (`dispense-config.json` schema 4**, D/DispenseConfig.java:37):

- `migrate()` (:599-614) becomes stepwise: `if (schemaVersion < 3) { … today's block … }`, `if (schemaVersion < 4) {
  … }`. Today it is one block for every older file and hard-sets `travelAccelMmS2` and `zLiftAccelMmS2` to 2000
  (:603-604), so a plain bump would reset tuned motion values on every schema-3 file. The first save over a schema-3
  file first copies it to `dispense-config.json.v3-<yyyyMMdd-HHmmss>.bak`: an older jar (bisecting on the machine)
  loads schema 4 and drops `parkedTips` and `needleLanding` on its first save.
- `tip` stays the engine's view of the **mounted** setup (key, name, idMm, odMm, minDotSteps; new `setupVersion`,
  `model`, `dispenser`, `form`, `bendDeg`, `bendToward`), so the 20-odd readers of `cfg.tip` keep working. It becomes
  engine-stamped: `PROTECTED_SETTINGS` gains `tip`, `parkedTips`, `needleLanding` and `needleXyTrim`, `NESTED_SETTINGS`
  loses `tip` and `needleXyTrim` (A/DispenseEndpoints.java:53-62); a patch that changes them answers 400 `The tip is
  the mounted tip setup: change it in Setup › Dispenser & tip.` or `Where the needle lands is set by Check where it
  lands and the trim panel (POST /api/needle/landing, /api/needle/xy-trim).`
- `profile` stays the **mounted** setup's dispense profile (every plan, run and check reads `cfg.profile`).
- new `needleLanding`: `{dxMm, dyMm, setAtMs, jobId, photoId, headOffsetXMm, headOffsetYMm, note}` or null: where the
  mounted needle lands relative to camera + N2 head offset, each axis within ±15 mm (`MAX_LANDING_MM`), set only by a
  landing check or `Keep` (§22.3). `needleXyTrim` (±1 mm, the hand fine-tune on top) gains `headOffsetXMm` and
  `headOffsetYMm`. Every dispensed target is moved by minus (landing + trim) (D/GcodeStreamBuilder.java:572-578).
- new `parkedTips: {setupKey: {profile, needleAnchor, needleZOffsetMm, needleZOffsetSetAtMs, needleZOffsetHomeAtMs,
  needleXyTrim, needleLanding, parkedAtMs, profileFrom}}`: what every setup that is not mounted keeps. A setup gets its
  entry when it is added: `profile` (a copy, §22.3), `needleAnchor` null, `needleZOffsetMm` = the mounted one (a
  placeholder: `needleZOffsetMm` is a primitive, D/DispenseConfig.java:81, and I4 refuses everything while the anchor is
  null), both times null, `needleXyTrim` `{0, 0, setAtMs null, note "new tip"}` (`validate()` dereferences it, :704),
  `needleLanding` null.
- **At start** (the `DispenseService` constructor, D/DispenseService.java:165-170, once the config and the database are
  open) the engine reconciles, in this order: (1) a `cfg.tip.key` that names no setup gets a setup made from `cfg.tip`
  (form and taper `unknown`) and an event; (2) `cfg.tip` is compared with its setup's newest version on `idMm`, `odMm`
  and `minDotSteps` only (never the name: every saved config says `22G tip`); when they differ a version is appended
  with the config's three values, keeping the setup's name, model, form, taper and OD source, `why` `from the settings
  file at the update`; (3) `cfg.tip` is refreshed from the setup's newest version (so the 22G reads `22G bent taper`);
  (4) when `tip_mounts` is empty a row `seed` (why `in use before the update`), else when its newest row names another
  key a row `reconcile`; (5) the 3 → 4 landing: when `needleLanding` is null and the needle change was finished before
  this version (no change recorded, or touched off after it, `isNeedleTouchedOff()`, :449-458), `needleLanding` =
  `{0, 0, setAtMs: needleZOffsetSetAtMs or now, headOffset: N2's now, note "kept from before the update (boards ran
  with it)"}` and the trim keeps its values, stamped with N2's head offset now; otherwise the landing stays null and
  §22.3's rule asks for it.
- **Config writes** (A/DispenseEndpoints.java:475-533): `PUT /api/config` applies its patch inside
  `DispenseService.updateConfigAtomically(edit)` (synchronized: the edit receives a copy of the **live** config, its
  protected-settings comparisons run against that copy, and the result is validated and written under the same lock).
  Nothing a mount, a needle change, a run's purge reservation or another save wrote in between is put back. A patch
  that touches `profile` must carry `forTip` (the setup key the form was filled under; 409 `tip-changed`: `The tip
  changed while this was open (the 25G straight was mounted at 14:07): reload.`).

### 22.2 Resolution: what the mounted setup uses

The mounted setup **S** is `cfg.tip.key`. One engine method gives every reader its pad types:
`DispenseService.getPadTypes()` (D/DispenseService.java:639-641) → `TipSetups.typesFor(db, S)` (`D/TipSetups.java`,
new). A version row holds both the pad and the dose plan; S's view **composes** them per type key:

1. **Pad fields** (name, family with `Retired`, package pattern, pad width, height and roundness, layout, pitch, t, k)
   from the type's newest version of **any** setup (`pad_types_current`); its number is `padVersion`. They describe the
   pad, not the needle, so a rename, a corrected size or pattern, or a retire made under one setup holds for all, and
   the same package resolves to the same type and amount (area × t × k) whichever tip is on.
2. **Dose fields** (status, dotSteps, dotsPerPad, calAreaMm2, doseFp, massPerPadMg, origin, notes, created at,
   operator) from S's newest version (`pad_types_by_setup`); its number is `version`.
3. A type with no version of S is a **stand-in**: dose fields cleared (status `not-started`, no plan, recipe, weighed
   mass, calibrated area or origin: D2), `standIn` true, `version` 0, notes `Not calibrated for the {S} yet.`
4. Every row says `setupKey` = `tipKey` = S. The list keeps creation order, `MIN(row_id)` per key as `currentPadTypes`
   does (D/DispenseDb.java:490-502): the resolver tries patterns newest first by list position (D/PadTypeResolver.java:
   161-173), so the order, and which type wins a package, is the same for every setup.
5. Version numbers keep one line per type key across setups (the `UNIQUE(type_key, version)` of D/DispenseDb.java:148
   stays: no table rebuild). `chip-0603 v6` may be the 25G's while v5 is the 22G's; each version names its setup.

The resolver's fingerprint (D/PadTypeResolver.java:74-87) becomes `key:padVersion/version@S` per type (`~` for a
stand-in's version) plus `|tip:S@setupVersion`: mounting another setup, editing a pad definition under any setup, or
correcting the mounted setup (an OD) makes plans stale. Interlocks compare both numbers (D/Interlocks.java:120-125;
`PlannedPad` gains `typePadVersion`).

**Readers.** The no-arg `DispenseDb.currentPadTypes()` and `currentPadType(key)` (:490-512) are removed, so the compiler
finds every reader; what stays is `currentPadTypes(setup)`, `currentPadType(key, setup)` (both composed),
`padTypeHistory(key)` and `typeKeyExists(key)`. The 15 readers today move to the mounted view: D/DispenseService.java:
640, :889, :1421, :1938, D/PadInstances.java:181, B/BoardArtStore.java:312, S/SimBoardRenderer.java:208,
A/PadCalibrationJob.java:427, :1594, A/PadProfileEndpoints.java:270, :390, A/PadTypeEndpoints.java:250; the "does this
key exist" checks use `typeKeyExists`: A/PadProfileEndpoints.java:247, A/PadTypeEndpoints.java:154, :428.
`PadInstances.inputs(svc, pads, planned)` (D/PadInstances.java:177-187) takes `svc.mountedTip()` itself, so its six
callers get the mounted tip unchanged: A/PadCalibrationJob.java:432, :1530, A/PadProfileEndpoints.java:97,
A/PadProfileViews.java:47 (whose memo key, :47-61 and §21, gains the tip's cache key), A/PadTypeEndpoints.java:371 and
B/BoardImporter.java:439 (the upload preview; :463 on fixround1b). The suggestion apply (A/PadProfileEndpoints.java:
270-301) compares with S's composed version instead of `cfg.tip.key` (:277) and saves with `forSetup` (:301).
`interlockInputs` (D/DispenseService.java:887-900) no longer swallows a failed read: the plan's setup is its own `PLAN`
rule (`plan.setupKey` ≠ S, or null, is stale), and a pad-type read that fails refuses the run (`PLAN: the pad types
could not be read ({error}): plan again`) instead of skipping the version checks.

**Writes.** `DispenseService.savePadType(version, forSetup, padVersion)` becomes synchronized and names its setup:
`forSetup` must be S (409 `tip-changed`: `The tip changed while this was open (the 25G straight was mounted at 14:07):
reload.`), so an editor, a suggestion or a job that read setup A's view before a mount can never save A's dose plan as
B's version. A dose write (a calibration, a copy, a status raised by hand, `padVersion` null) takes the pad fields from
the composed view under the lock, so it never reverts a pad edit made meanwhile; a pad edit sends the `padVersion` it
showed (409 `pad-changed`: `{type} was changed meanwhile (version {n}, with the {other setup}): reload.`). Decision 17
stays per setup: a pad edit that changes the pad's size drops **S's** calibration (origin manual, no recipe, no mass,
A/PadTypeEndpoints.java:341-349); the other setups keep their plans, and the check's `calArea` ratio (0.80–1.25,
P/Limits.java:54-55; `OUTSIDE_AREA`, P/ProfileCheck.java:775-788) flags a pad that moved beyond it (§22.11 item 7).
Retire writes S's version with family `Retired` and no pattern (A/PadTypeEndpoints.java:213-227): a pad field, so the
type is retired for every setup (`Retire for every tip…`).

| reader | with S mounted | when S has no data | blocked |
|---|---|---|---|
| planner (D/DispensePlanner.java:155-268) | S's composed types; S's profile; `withTip(svc.mountedTip())` (D/DispenseService.java:680-685): same-pad pitch (:283-284) and the dot-gap flag (:386) from S's dot sizes | a pad whose dose (its type's, else the profile's) S's dot table does not know at the current recipe and paste gets the new blocking flag `DOSE_UNKNOWN`, no dots, and a plan warning naming the dose | stand-ins (`STATUS_BELOW_MIN`); `DOSE_UNKNOWN`; decision 5's guard (:205-209) through `Tip.setupOf` |
| profile check (P/ProfileCheck.java) | S's types, tip, fresh evidence and profile; texts name S; `PadInstances.inputs(…, Tip)` (D/PadInstances.java:147-171) takes the mounted tip | the `Tip.g22()` defaults (P/CheckInputs.java:17, P/ProfileCheck.java:224-226) are deleted: no tip = `IllegalArgumentException`, and the planner refuses to plan without one (`the planner needs the mounted tip`) | as §3.3 |
| suggester (P/Suggester.java:44-96) | a dose's size and mass: S's fresh records (as today), then S's **dot table**, then the bench model (only when `S.model = bench-22g`) | a dose the tip does not know is not suggested; none known: suggestion `measure-tip` | – |
| dose records (D/DoseRecord.java:106-153) | fresh by today's rules with S (tip key, needle change, the recipe of S's profile) | – | other setups' records never count (D3), nor records of a job flagged `wrong-tip` |
| calibration job (A/PadCalibrationJob.java) | S's profile, composed types and dot table; writes versions, records, labels and photos with S; records S and the needle token at its start (§22.3) | unknown dot size: spaced for max(1.6 mm, OD + 0.4 mm); unknown mass: weigh dots counted with the bench model's mass × 1.5 when the ID is above 0.41 mm (a count and R7's demand, not a claim), plausibility band 0.1–10 × that | a mount or needle change during the job (§22.3); the landing rule (§22.3) |
| by-hand guide (§8) | the same job steps and records with S; the editor saves S's version (§22.5) | as the job | as the job |
| Run (D/Interlocks.java) | the plan's setup and setup version must be S's | – | `PLAN`; the landing rule; `checkDoses` (D/Interlocks.java:381-400) refuses a planned dose `!tip.knows(steps)` |

**S's dot table** (`DoseRecords.dotTable(records, S, doseFp, pasteKey, simulated, flaggedJobs)`, new): per dose, the
newest **passing** optical record of S at the recipe of S's profile (`dose_fp`), the loaded paste (`paste_key`) and the
current simulator flag, outside jobs flagged `wrong-tip`, **any age**: it describes the tip the way the bench model
describes the 22G, while §1.7's freshness still decides `characterized`. The mass per step is the newest confirmed
weighing of S at that recipe and paste (§7.5), else its newest single weighing (the suggestion's confidence then says
`weighed once`). Records of other setups never enter it; it is computed from `dose_records` (no new table). S counts as
**measured** when its smallest dose has a dot size in the table and S has a weighing; with another paste loaded it
reads `measured with {paste}` and counts as not measured.

`P/Tip` (P/Tip.java:8-31) gains `SEED_KEY`, `setupOf(tipKey)`, `benchModel` (`S.model = bench-22g`), `measured` (per
dose: d̄, d95, record id, time), `mgPerStep`, `mgPerStepAtMs`, and the methods that replace every `DoseModel` size or
mass call: `knows(steps)`, `dLoMm(steps)`, `dHiMm(steps)`, `massMg(steps)`. With the bench model they return
DoseModel's values (the 22G bent taper behaves exactly as today: golden test, §22.8). Otherwise the measured d̄, d95 and
mgPerStep × steps; for a dose the tip does not know they **throw** (`IllegalStateException`): `knows` decides first,
and no NaN ever reaches a comparison or a view. (NaN would make `DotGeometry.axis`'s comparisons false and skip the
same-pad pitch and the overhang move, P/DotGeometry.java:74-91; make `PadInstances.bridging`'s gap test false, so
`MAY_BRIDGE` is never set, D/PadInstances.java:236-237; show as 0 through `Suggester.r3`, P/Suggester.java:812-814; and
reach the browser as a bare `NaN` token, which `JSON.parse` rejects, A/Json.java:19.) Views write null and `dots:
"none"`. The call sites: P/Suggester.java:78, :90, :93, P/ProfileCheck.java:281-282, P/Decider.java:235,
D/DispensePlanner.java:283, :386, D/DispenseService.java:2775, :3014, D/DoseBlocks.java:145, A/PadCalibrationJob.java:
459, :512, :716, :720, :1380-1381, :2099, :2129, :2390-2391, A/PadCalibrationEndpoints.java:344. `PadInstances.tip(cfg.
tip)` (D/PadInstances.java:138-140) becomes `svc.mountedTip()`: the setup with its dot table, cached per (setup
version, recipe, paste, simulator flag, newest dose record id, flagged jobs). R7's feed check (D/DoseBlocks.java:143-146)
uses S's weighed mass per step; without a weighing the bench's 1.32 µg/step, or 1.98 µg/step (× 1.5) when S's ID is
above 0.41 mm (a wider bore slips less, so the bench value would understate the demand).

Rules that follow (each tested, §22.8):

- **An unmeasured setup** (not the bench model and not measured, above) cannot say which pads it can dispense, nor
  how much a dot lays: the check reports no `tip` or `part` reason, and every group with a gate reason gets the
  suggestion `measure-tip`, sentence `Measure the {S}'s dots first (Setup › Dispenser & tip › Measure its dots, ≈ 8 min
  and two coupons to weigh): suggestions need its dot sizes and paste per turn.` Every type of S is below the gate
  anyway; the planner's `DOSE_UNKNOWN` holds back the rest (a characterized type after a recipe edit).
- **`characterized` without the bench model needs measured evidence for every dose**, the profile's dose included.
  Today the profile's dose is exempt (A/PadTypeEndpoints.java:354-357) because the bench measured it with the 22G.
  `PadTypeEndpoints.checkDose` and `DispenseService.applyPadCalibration` refuse it otherwise (409 `status-evidence`,
  the text of A/PadTypeEndpoints.java:375-378); the validated rule (:359-369) counts only S's own characterized
  versions. Run's `checkDoses` refuses a planned dose S does not know at this recipe and paste: `pad type {key}
  v{n} has {turn} dots, which the {S} never measured at this recipe and paste: measure its dots (Setup › Dispenser &
  tip)`.
- **The control and the predictions** (§7.4 control, §1.8 P3 and P8) compare with the bench only with the bench
  model; otherwise with S's newest passing record of the profile's dose in its dot table (±10 %), or with nothing
  (`first measurement of this tip`). The control's failure text gains `or another tip than the {S} is on` (§22.3).
- **Anchors stay shared.** The amount a pad needs (§1.3: mass per mm² that reflow accepted) belongs to the joint and the
  paste, not to the needle, so a validated, weighed version of any setup anchors every setup: `PadInstances.inputs`
  passes the other setups' ones in a new list `CheckInputs.moreAnchors` (the `anchors` map, P/CheckInputs.java:25-29,
  holds one per family) and `Anchor` gains `setupName`; `confidenceWhy` adds `the amount is anchored on pads validated
  with the {other setup}` when the anchor is another setup's. What delivers the amount (turn, dots per pad, dot size,
  mass per step) never carries over (§22.11 item 1).
- **Stand-ins in the texts.** `TypeInfo` gains `standIn`, `setupName` and the other setups' statuses; §3.4's
  `below-gate` text becomes `{type} is not calibrated for the {S} yet (status: {status}{; {other status} for the
  {other setup}}). Boards need pad types that are at least {gate}: calibrate it for this tip automatically or by
  hand.` (`Texts.belowGate`, P/Texts.java:200-203); decision 5's `belowGateTip` (:205-210) stays for the guard.
- **Copy from another setup** (D2: only when the operator asks): `POST /api/pad-types/{key}/copy {fromSetup, forSetup}`
  writes a version for S with the source version's dot plan (dotSteps, dotsPerPad, calAreaMm2), status `provisional`,
  origin `copied` (new, D/PadType.java:24-29), no recipe, no weighed mass, notes `Copied from the {source} (v{n},
  {status}) as a starting point: nothing was measured with the {S}.` It goes through `checkDose` like an edit (400 with
  its text when the dose is below S's `minDotSteps` or breaks R1 or R6, A/PadTypeEndpoints.java:331-340), and is
  refused when S's own version is `characterized` or `validated` (`{type} is {status} for the {S} already: a copy would
  make it provisional.`). The suggester shows the copy as `your plan`, confidence `low`, reason `the plan was copied from
  the {source} and never measured with this tip`; only a calibration of S raises it.
- **Blocked, in one list:** the switch of a mount while an engine task, the pressure hold or a pad calibration job is
  active (§22.3); anything that dispenses before S's needle frame and offset are recorded (I4); everything but prime,
  the test dot and the landing check before S's landing is checked (§22.3); every type of S below the gate; a pad whose
  dose S does not know; `characterized` on S without S's evidence; suggestions on an unmeasured S; a copy over a
  characterized or validated version of S; a write for a setup that is no longer mounted.

### 22.3 Needle changes and mounting

**The fact and the choice.** A needle change is a fact the operator reports after the needle is already off; which
setup is on is a choice of the library. They are two engine methods:

`DispenseService.recordNeedleChange(why, operator) → NeedleChange` (synchronized; **never refuses**: a refusal would
leave the frame, the offset and the landing valid for a needle that is no longer there, and with a gap of 0.10 mm a
needle 0.2 mm longer hits the board):

1. an engine task that runs is stopped first, as STOP stops it (`abort("the needle was reported changed")`,
   D/DispenseService.java:626-633): a needle cannot come off while the head moves, and the task's frame no longer
   holds;
2. `needleChangedAtMs` = max(now, previous + 1): strictly increasing, it is the **needle token** of everything measured
   for this needle (below);
3. every coupon still waiting for its readings gets a skip mass row, why `the needle was changed at {HH:MM}` (today's
   `skipRow`, A/PadCalibrationJob.java:1433-1438, moves to `DispenseService.skipCoupon`), so the database says why the
   weighing never came; its dots were dispensed before the change, so no reading of them could count anyway
   (D/DoseRecord.java:126-127). `couponReservation` frees such a place (D/DispenseService.java:2549-2568) while paste is
   still on it: the response says `wipe coupon1 before its next use`, and the next job's before-image look refuses it
   until it is wiped;
4. a `tip_mounts` row `needle` (setup S, `voided_json`: the coupons by name, the job, the task), event `TIP needle
   changed ({S}): needle Z frame, offset and landing to do`.

The API then tells a pad calibration job that waits for its readings (`PadCalibrationJobs.needleChanged(nc)`): it ends
`stopped`, text `The needle was changed at {HH:MM}: the dots on coupon1 and coupon2 no longer count. Wipe them; the
types stay as they were.` A job between steps stops at its next step (the token, below).

`DispenseService.switchTip(key, why, operator) → MountResult` (the choice; only this part may be refused). `POST
/api/tip-setups/{key}/mount` is (a) `recordNeedleChange` (always, first), then (b) `switchTip` when the key is not S. A
"mount" of S is (a) alone: "I changed the needle" for the same kind of tip (`POST /api/needle/changed` is the same
call). (b), run inside `PadCalibrationJobs.whileNoJob(…)` (synchronized on the jobs' monitor, as `start`,
A/PadCalibrationJobs.java:101-113) and holding the engine's `busy` flag (`compareAndSet(false, true)`, released after
the write; D/DispenseService.java:3404), so neither a job nor a task can start between the checks and the write:

1. **Refusals** (409 `refused`, first match; the body carries `needleChange`, the result of (a)): a dispense task runs;
   a pad calibration job has not ended (`A pad calibration is stopping: mount again in a moment.`); the pressure hold
   is on (`Switch the pressure off first: a needle comes off with the pressure off.`); the setup is retired (`The {name}
   is retired: bring it back first (Change tip › Show retired tips).`) or incomplete (`The {name} has no {ID | OD |
   form | taper} yet: fill it in first (Edit…).`). Every refusal ends `The needle change is recorded: nothing
   dispenses until the {name} is mounted and its needle Z frame recorded.` S stays mounted with a stale frame, so I4
   blocks all dispensing. Nothing moves, so the machine need not be homed or idle.
2. **Park** S: `parkedTips[S]` = its profile, needle anchor, offset with its two times, trim, landing, `parkedAtMs`.
3. **Bring in** the incoming setup's entry: its profile, frame, offset, trim and landing (a new setup's entry is the
   empty one of §22.1). The incoming profile takes the **live** `startAngleDeg` and `startAngleSetAtMs` before the
   write: the start angle belongs to the controller's power-up and the screw (D/DispenseProfile.java:23-25), which a
   needle change does not turn, so I11 stays as it was (a parked angle from another power-up is never offered). A
   profile that no longer validates is replaced by the default `DispenseProfile` named `{name} (reset: {problem})`, and
   the event says so.
4. `cfg.tip` = the incoming setup's view; `needleChangedAtMs` stays the one (a) wrote.
5. One config write: validated as `updateConfig` (D/DispenseService.java:339-356), **without** its stamping of a
   changed start angle and trim (:346-351): the restored values are not new measurements and keep their times
   (stamping them would pass I11 and the landing rule without a measurement). Then a `tip_mounts` row `mount` (from S,
   `profile_json`, `landing_json`, the head offset), event `TIP mounted the {new} (was the {old})`. The config is
   written first: it is the truth, and a row that failed to write is repaired at the next start (§22.1).

Response: `{mounted, from, atMs, needleChangedAtMs, profile, voided: {coupons, job, task}, stale: [...],
headOffset: {now, landingWas}, next: [{label, href|fix}], undoUntilMs, text}`, text `{S} mounted at {HH:MM}. Next:
record its needle Z frame (Setup › Needle Z frame).` When N2's head offset (OpenPnP, `nozzle.getHeadOffsets()`, read
only) differs from the one the incoming setup's landing was checked under, the text adds `N2's head offset is ({x},
{y}) now; the {S}'s landing was checked with ({x'}, {y'}): set it back in OpenPnP, or check where it lands.`

**Locks and tokens.** Every value measured for a needle or a setup says which one it belongs to, and the engine
refuses it under another:

| value | carries | refused (409) when it differs |
|---|---|---|
| needle Z frame, offset, trim, landing (`POST /api/needle/anchor`, `/offset`, `/xy-trim`, `/landing`) | `needleChangedAtMs` it was taken under (status `dispense.needle.changedAtMs`; null when none recorded) | `needle-changed`: `The needle was changed at {HH:MM} after these values were taken: take them again.` (missing: 400) |
| a pad type version (editor, suggestion apply, copy, by-hand raise) | `forSetup`, and `padVersion` for a pad edit | `tip-changed`, `pad-changed` (§22.2) |
| a profile patch (`PUT /api/config`) | `forTip` | `tip-changed` (§22.1) |
| a calibration job | S and `needleChangedAtMs` at its start, on every engine step's request (`DoseBlocksRequest.forTip`, `needleSinceMs`) and on its records (`pad_calibrations.setup_key`, `needle_since_ms`) | the machine thread refuses the step, and `applyPadCalibration` the write: the job ends `stopped`, `The tip changed during the calibration (the 25G straight was mounted at 14:07): nothing was saved.` or `The needle was changed at {HH:MM} during the calibration: nothing was saved.` |
| a tip picture | S and the token at its request | not stored (§22.4) |

What goes stale (nothing is deleted; parked values come back as starting values but count only once renewed):

| what | rule | asks again |
|---|---|---|
| needle Z frame | `isNeedleAnchored()`: frame older than the change (D/DispenseConfig.java:444-447) | I4 (text below) |
| touch-off / offset | `isNeedleTouchedOff()` (:449-458) | I4 `needle Z offset has not been set since the last needle change…` (D/Interlocks.java:191-192) |
| landing | **new** `DispenseService.isNeedleXyChecked()`: `needleLanding` set since the change (or no change recorded) under today's N2 head offset (within 0.001 mm), and the trim, when not zero, set under it too | the landing rule (below) |
| dose records | dispensed before the change or with another tip (D/DoseRecord.java:122-128) | the next calibration measures again |
| coupons waiting for readings | skip rows (above) | wipe them |
| dispense profile | switches with the setup (step 3) | plans stale (profile fingerprint) |
| pad types | S's composed view (§22.2) | plans stale (resolver fingerprint); `PLAN` |
| start angle | kept live across a mount (step 3); I11 as today | only after a controller connect, as today |
| regulator, prime and test-dot ticks (browser) | lapse at a needle change (W/core.js:605-614@pc does it for the regulator only today) | the run confirm and the bring-up ask again |
| surfaces, purge row, places, calibration area | N1 heights and bed geometry, not the needle | nothing |

- **I4 texts after a change** name the setup: `I4: the {S} was put on at {HH:MM}: record its needle Z frame (Setup ›
  Needle Z frame)` (D/Interlocks.java:183-186). The texts' "2.59 mm" become the mounted frame's offset.
- **I4 and a frame close to N1's** (D/DispenseConfig.java:38-39, :659-661; D/DispenseService.java:68-73): the frame
  tolerance (≤ 1.2 mm) and the 2.0 mm board-Z limit catch a height taken with the needle instead of N1 only because the
  22G's offset is −2.60 mm. When a frame's |offset| o is below 2 × `zFrameToleranceMm` + 0.1 mm, `setNeedleAnchor`
  answers with a warning and I4 refuses dispensing: `The {S}'s tip is {o} mm from N1's: I4 cannot tell a height taken
  with the needle; lower zFrameToleranceMm below {o/2 − 0.05} mm (Settings).` The board-Z limit at planning becomes
  min(2.0, o − 0.5) mm, so a board Z taken with the needle (o off) is still refused.
- **The PLAN text** of D/Interlocks.java:142-145 becomes `the tip changed after planning (the {S} was mounted at
  {HH:MM}): plan again`, and for a corrected setup `the {S} changed after planning (OD 0.72 → 0.90 mm): plan again`;
  plans carry `setupKey`, `setupName` and `setupVersion` (D/DispensePlan.java).

**Where the needle lands, per setup.** The N2 head offset in machine.xml stays one value, the one set with the 22G;
each setup keeps its own `needleLanding` (±15 mm, measured, never typed), and the trim panel (W/setup.js:642-686@pc,
±1 mm) stays the hand fine-tune on top. Both are parked with the setup, so a bent and a straight tip can both stay set
up and no swap needs an edit in OpenPnP. Both record the N2 head offset they were set under: when someone changes it in
OpenPnP, every landing set under the old one is stale (`isNeedleXyChecked()` false; readiness `N2's head offset changed
in OpenPnP since the {S}'s landing was checked: set it back, or check where it lands`).

**The landing rule** (`Interlocks.check`, D/Interlocks.java:430, from `in.xyChecked`): while the landing is not checked,
the only things that dispense are prime and the test dot at the waste (the waste is the place for stray paste; a new
needle must fill before any dot) and the landing check itself (`Scope.landingWaiver` `waste` | `landing-check`, named
like `touchOffWaiver`, D/Interlocks.java:74-95). Board runs, their purge rows, pad calibrations, Measure its dots, the
touch-off, the old dot ladder, weigh dots and dispense shots are refused: `I4: the {S} was put on at {HH:MM} and where
it lands has not been checked: check where it lands (Setup › Dispenser & tip).` Before the landing is known a dot may
land millimetres from its spot: weigh dots sit 3 mm inside a coupon's edge (§7.5), ladder dots about 1 mm inside the
area, and the touch-off pairs each dot with whatever lies within half its pitch (D/DispenseService.java:1246), so a
landing about one pitch off reads one gap step wrong, and a larger one reads as "no dot at the largest gap … raise
startGapMm", a wrong diagnosis on a new needle.

**Order after a change** (the response's `next` list and Setup's `#tip-next`; a mount's swap happens before Mount,
§22.7):

1. **Needle Z frame**: Both tips touch (preselected for a tip that was never on, or whose K is not known), or From K
   with the setup's **own** parked K (session 4's side camera). Another tip's K never fits: tapered and 0.5 in steel
   tips differ in length by up to about 10 mm (§22.10 T5). Nothing dispenses.
2. **Confirm the frame's offset** (or enter a measured one). Nothing dispenses.
3. **Read the regulator** at the incoming recipe's pressure (`Set the regulator to 18 psi and read it`; `18 → 20 psi`
   when the recipes differ). The tick lapses at every needle change.
4. **Prime until paste comes out**: a new needle is empty (its hub and tube take several mm³, about 10 turns at
   ≈ 0.5 mm³ per turn), and the landing check's purge is 8 dots. Prime and `I saw paste`.
5. **Check where it lands** (≈ 1:00, below).
6. **Touch off** (refines the offset; recommended for a tip that was never on; now its dots land on their spots).
7. **Measure its dots** (≈ 8:00; only for a setup whose dot table does not know its doses at this recipe and paste).
8. **Calibrate the pad types of {board} for the {S}** (the measure result's primary button).

After "I changed the needle" on the same kind of tip: steps 1–5 (step 1 with its own K).

**The landing check** (`POST /api/pad-calibrations {measure: "landing", wide?}`, a records-only job on §8's path,
≈ 1:00; it saves no dose record: `dose_records.kind` allows only optical, coupon and mass, D/DispenseDb.java:243, and 8
dots at uneven gaps are no ladder):

- **Needs** I4 (frame and offset since the change), §12.2's regulator and paste checks (no coupons), and a band of the
  calibration area (below). The area's clearance is checked against the purge row and the coupon places as today
  (A/PadCalibrationJob.java:879-905) and against the outline + 3 mm of every board location of OpenPnP's job, with or
  without a plan (today only the plan's pads, and only when the job has a plan).
- **Expected landing** E: the setup's own landing (set by a landing check under today's head offset), else (0, 0), the
  head offset as set with the 22G. **Search** R: 2 mm around E for a setup with a landing of its own that is straight,
  or bent with `bendToward` recorded (the swap step points it the same way, §22.7); 6 mm otherwise: a straight tip lands
  within about 2.5 mm (the 22G's bend) + 3 mm (0.3 mm/mm × a length difference of up to 10 mm) of the 22G, and a 45°
  bent tip pointed the same way as the 22G within about the same (its larger bend minus the 22G's); 9 mm with `wide`,
  offered after a locator found nothing (§22.10 T2 measures all three).
- **The band**: the next free band of the calibration area, its full width, 2R + 8 mm high (R 2 → 12 mm, R 6 → 20 mm:
  the whole default 40 × 20 mm area, so a first check of a tip needs a wiped blank; R 9 → 26 mm, which needs a taller
  area: `Searching ±9 mm needs 26 mm of the calibration area; it is 20 mm tall: make it taller (Calibrate › Pad
  calibration).`). Refused when less is left: `The calibration area has {left} mm left; the landing check needs {h} mm:
  wipe the blank (Calibrate › Pad calibration › Blank wiped).` It reserves the band as a ladder does (`usedMm`).
- **Locator**: one dot of the profile's dose at the band's centre (moved by minus E + trim, like every dot), the band
  imaged before and after by the frames that cover it (2 × 1 or 2 × 2 frames of 20.6 × 11.6 mm). The new blob nearest
  the target within ±R (the largest when a dot splashed) gives the coarse shift C. None: `No dot found within ±{R} mm of
  where the {S} should land: is paste coming out (Prime until it does)? If it is, the needle lands farther off: {bent:
  point the bend toward {direction}, as in its picture | straight: check that the needle is on straight}; or search
  ±9 mm.` Nothing is saved.
- **Purge and row**: the profile's 8 purge dots (2.5 mm apart) and then, without stopping, a row of 8 dots at 0, 2.0,
  4.5, 7.5, 11.0, 15.0, 19.5 and 24.5 mm (every gap different, the smallest 2.0 mm), on two lines 3 and 6 mm from the
  locator's dot on the side of the band with room, every dot ≥ 1.5 mm (edge to edge) from the band's edges and the
  locator, each moved by minus E + trim + C so it lands on its line. `P/LandingFit` (new, pure) tries every residual
  shift within ±1.0 mm and keeps the one that puts at least 6 of the 8 targets within 0.3 mm of a blob; the next-best
  must match at most 3 (within ±1.0 mm no distance between two row dots repeats). The landing is
  T = E + trim + C + residual, the scatter that of the matched pairs (≤ 0.15 mm, the ladder's landing limit, §7.4).
  Without a fit: `Not enough dots near their spots ({n} of 8): check that the needle is on straight, then check again.
  Look at the result photo.` Nothing is saved.
- **Saved automatically** when the fit holds (a measurement, as the touch-off applies its own): `needleLanding` = T
  with the job, the photo and today's head offset, the trim reset to 0, the card `The 25G straight lands +2.32, −0.41 mm
  from the N2 head offset (8 dots, scatter 0.05 mm). Saved: every dot is corrected by it.` Except when the setup had a
  landing of its own and T lies more than 1.0 mm from it: then nothing is saved and the card asks `The {S} lands {d} mm
  from where it landed on {date}: is the {S} on the dispenser{, its bend toward {direction} as in its picture}?` with
  `Save it: the {S} is on` (`POST /api/needle/landing {jobId, accept: true, needleChangedAtMs}`) and `Another tip is
  on: Change tip…`.
- A `TIP` event holds the matched pairs, C, the residual, the scatter and the photo id; the result photo is the band's
  after frame (§22.4). Every pad calibration ladder also records `landing_dx_mm`/`landing_dy_mm` (P/LadderStats.java:420
  has the mean); when that residual exceeds 0.15 mm its result card says `The dots land {dx}, {dy} mm off their spots:
  check where the {S} lands.`

**Keep where it lands** (`POST /api/needle/landing {keep: true, needleChangedAtMs}`, two presses, `Keep where the {S}
lands ({dx}, {dy} mm): only if this is the same straight tip, screwed on straight`): stamps `setAtMs`, note `kept after
the needle change at {HH:MM}`. Allowed only for a straight setup whose landing was set by a landing check (or kept from
before the update) under today's head offset; otherwise 409 with the reason: `A bent tip lands where its bend points:
check where it lands.`, `The {S} has no landing of its own yet: check where it lands.`, `N2's head offset changed since
the {S}'s landing was checked: check where it lands.` Never offered at a setup's first mount.

**Is this the tip you think it is?** Only the operator says which tip is on; the engine compares each new measurement
with the setup's own history and asks before it names any other cause:

| measurement | compared with | when off | text |
|---|---|---|---|
| a needle Z frame (Both tips touch) or a touch-off | the setup's previous frame (parked, or before the needle change) | more than `needleOffsetMaxDriftMm` (1.00 mm) | `The {S}'s tip touches {d} mm {higher/lower} than when it was on on {date}: is the {S} on the dispenser? (its picture)` (a warning with the main picture and `Change tip…`; the frame is saved: it measures what is on) |
| a landing | the setup's own landing | more than 1.0 mm | the landing card's question (above) |
| the control's dots (§7.4) | the bench, or S's dot table | outside the band | the control's text gains `, or another tip than the {S} is on` |

**Made with another tip…** (two presses on the result card and in Records, `POST /api/pad-calibrations/{id}/wrong-tip`,
`DispenseService.markWrongTip(jobId, operator)`): a `cal_flags` row for the record's job (a by-hand record: the jobs of
its evidence); the job's dose records leave the setup's evidence and dot table; its waiting coupons get skip rows; for
every record of the job whose version is still its setup's newest of that type, a version for that setup restoring the
dose fields of its previous own version (or the cleared plan of a stand-in), notes `restored: calibration {id} was made
with another tip`. It is the one write that names a setup that may not be mounted. The labels stay as written; the
records show `wrong tip`; then Change tip opens.

**Undo a mount** (`POST /api/tip-setups/undo-mount`, the toast `25G straight mounted. Undo` for 2 min): restores the
previous mount exactly (setup, profile, frame, offset, trim, landing and `needleChangedAtMs`, from a snapshot kept in
memory) when it is asked within 2 min of a mount of **another** setup, no engine task has run and nothing was measured
since; two presses, verb `undo: the {old} never left the dispenser` (the operator's word, as "I changed the needle"
is); a `tip_mounts` row `undo`. Otherwise 409 `Too late to undo: {something ran | the frame was recorded | 2 min have
passed}. Mount the {old} again instead.`

**Add, edit, retire** (no motion; `A/TipSetupEndpoints`): `POST /api/tip-setups` appends version 1 and writes
`parkedTips[key]` (§22.1) with a copy of the profile it names (`profileFrom`: a setup's current profile, or `default`
for `DispenseProfile`'s defaults) named `{profile name} · {new setup name}`: a recipe to start from, nothing measured
(D2); with `mount: true` (`Add and mount`) it then mounts it in the same call. A parked profile is edited on Profile once
its setup is mounted (Profile edits `cfg.profile`). `PUT /api/tip-setups/{key}` appends a version: identity fields
(dispenser, gauge, form, taper) of a setup that has a pad type version, a dose record or a mount answer 409
`another-needle`: `This makes it another needle: add a tip instead (Add a tip…, starting from this one).`; corrections
(tip model, ID within 0.03 mm, bend angle, length) need `correction: true` (409 `correction` without it, the UI's second
press) and log `TIP corrected the {S}: {field} {old} → {new}`; for S it also refreshes `cfg.tip` (refused while the
engine is busy) and the new setup version makes plans stale. Retire appends `retired 1` (not S: `The mounted tip
cannot be retired.`); bring back appends `retired 0`.

### 22.4 Photos

**Taking a picture of the tip** (`DispenseService.takeTipPicture(req)` → `Future<TipPictureResult>`, run kind
`tip-picture`, tracker label `tip picture`, an engine task as `checkCleanPads`, D/DispenseService.java:1010-1024):

1. Before it is queued (A/): `ctx.requireEngineIdle` (a pad calibration between steps included, A/ApiContext.java:92-100),
   `requireNoPressureHold` (:83-89), `requireMachineIdle` (:106-110); the setup must be S (`Only the mounted tip can be
   photographed: mount the {name} first.`); an up-looking camera must exist (`No bottom camera: OpenPnP has no camera
   looking up.`). The request records S and the needle token.
2. On the machine thread: refuses if the stop epoch moved (§7.8), if the pressure hold came on (`Switch the pressure
   off first: the head moves to the bottom camera.`), or unless the machine is started and homed (`Home first: the head
   moves to the bottom camera.`). It never homes by itself: a picture is not worth a home. The pneumatics stay off (no
   paste over optics, PT/lessons.md:4).
3. N2 (the dispenser nozzle, `cfg.nozzle`) goes to the bottom camera's X/Y with OpenPnP's
   `MovableUtils.moveToLocationAtSafeZ` (as D/DispenseService.java:3107), **target Z = `cfg.travelSafeZMm`** (31.5 mm,
   where the see-saw is balanced, D/DispenseConfig.java:75-76): a higher N2 would lower N1, which is elsewhere over the
   bed or the feeders while N2 is over the camera. When 31.5 lies outside N2's safe Z zone in OpenPnP the task refuses
   (`N2's safe Z zone ({lo}–{hi} mm) does not hold the balanced travel height 31.5 mm: the picture would move N1 down.`).
   The needle never goes below travel height, whatever its length and whether its frame is known yet. With the 22G's
   offset (−2.60 mm, session 4) its end is then about 34 mm in N1 contact height, about 28 mm above the board and
   roughly 10 mm above the bottom camera's focal plane (N1's 24.5 mm on the bench, mem:lumen-calibration-state): a
   wide-angle, deep-focus view (session 4 found no focus peak on the needle, PT/software-autoregister.md), good enough
   for a record (§22.10 T1).
4. `refuseIfStoppedSince(epoch)` again, then `TipCamera.capture()`: OpenPnP's light-settle-capture of the up-looking
   camera (`CameraCapture.captureLit(camera, null)`, V/CameraCapture.java:45-49: its light actuator on for the capture,
   as bottom vision does).
5. The head stays at travel height over the camera (the next task moves from there at safe Z, as after a camera tour).
6. `refuseIfStoppedSince(epoch)` and the token (a needle change meanwhile: not stored), then the frame is stored
   (below) as kind `tip`, source `bottom-camera`, setup S, `needle_since_ms` the token, caption `From below at
   {HH:MM}, at travel height (may be soft): the {S}` (until §22.10 T1 has passed: then `Bottom camera {HH:MM}: the {S}
   from below`), `mm_per_px` the camera's at its focal plane (information only), linked to the records the request
   names (links, below); event `TIP picture {id} of the {S}`.

About 5 s (≤ 400 mm at 200 mm/s, D/DispenseConfig.java:124, plus the camera's settle). STOP aborts it like any engine
task (no photo). `D/TipCamera` is an interface (`isAvailable()`, `locationMm()`, `capture()`) with
`D/OpenPnpTipCamera` (the first camera looking up, its own location) and `DispenseService.setTipCamera(TipCamera)` for
tests, as `setDotImager` (D/DispenseService.java:618-619).

**The result photo** (automatic, D4): `DoseBlocksResult` gains one `BlockPhoto` per block: the after frame of the
block's first tile (D/DispenseService.java:2969-2973 captures it), scaled to at most 1600 px wide and carried as JPEG
bytes (never a `BufferedImage`: 6 per job × the 5 jobs kept, A/PadCalibrationJobs.java:30, would hold about 170 MB);
the job drops them after storing. The job stores them when each engine step ends (`svc.storeResultPhotos(jobId,
result)`): kind `result`, source `top-camera`, `dose_steps`, `coupon_place` for weigh blocks, `x_mm`/`y_mm` the frame
centre, caption `Top camera {HH:MM}: {n} {turn} test dots on the blank` or `… on {coupon}`. At the decision (§7.7)
each group's record is linked (role `result`) to the photos of its chosen dose (blank and coupon). A records-only job
(the landing check, Measure its dots, the by-hand guide's ladder) stores its photos with the job; the by-hand save
links the result photos of the jobs whose dose records are its evidence ids (§22.5). At most 6 per job (4 blocks, 2
coupons); about 300 KB each.

**Links** (`photo_links`, `DispenseService.linkPhotos(photoIds, calibrationIds, role)`): a photo links to a record only
when its setup is the record's (resolved as §22.1); otherwise 409 `setup-mismatch`: `This picture is of the 25G
straight; calibration 41 was made with the 22G bent taper.` A bottom-camera picture links only to records of the same
needle (`photos.needle_since_ms` = `pad_calibrations.needle_since_ms`): after a needle change, a picture taken from
Records or an older result card cannot be attached to a calibration of the needle before. At the decision every
record is also linked (role `reference`) to its setup's main picture of that moment, captioned `Picture of the 22G bent
taper, added 10-29` (a reference, not taken during this calibration), so every record shows its tip without any step.

**Uploads** (`POST /api/photos`): a picture from a phone reaches this computer as a file (cable, cloud or mail) and is
dropped on the page or chosen with the file button (§22.7). No LAN route: `lanAccess` (A/ApiSettings.java:23-27, off
by default, set by hand, read at start) would open every route, motion and pneumatics included, to the network
without a login. The browser draws the file into a canvas (`createImageBitmap` with `imageOrientation: 'from-image'`,
at most 2048 px on the long side) and sends a JPEG of quality 0.85, so a phone's orientation is right and its metadata
(location) never leaves the browser; a file it cannot decode (an iPhone HEIC in Chrome or Firefox on Linux) gets `This
picture cannot be read here (iPhone HEIC?): share it as JPEG ("Most Compatible") or send a screenshot.` The engine
checks again, as BI §3.4 does for board files:

- `ctx.requireEngineIdle` first (no decode while the JVM streams a run or a calibration);
- one pipeline at a time: `static final Semaphore PIPELINE = new Semaphore(1)`, `tryAcquire()` without waiting, else
  409 `busy` (`Another picture is being stored: try again in a moment.`), held for header → decode → encode → store; the
  API's thread pool is unbounded (A/ApiServer.java:104-110), so this also bounds memory;
- the decode reads the header first and decodes with `ImageReadParam.setSourceSubsampling` to at most 2048 px on the
  long side, never the full raster; ARGB and indexed PNGs are drawn onto an RGB image before the JPEG writer, which
  refuses alpha.

| limit | value | refusal |
|---|---|---|
| type | JPEG (`FF D8 FF`) or PNG (`89 50 4E 47`), by the bytes, then decoded | 400 `not-an-image`: `This file is not a JPEG or PNG picture.` |
| size | request body ≤ 8 MiB (the route's own limit, A/ApiServer.java:187-195) | 413: `The picture is larger than 8 MB: send a smaller one (phones can share a reduced size).` |
| pixels | ≥ 64 px a side; ≤ 12,000 px a side and ≤ 40 Mpx, from the header before decoding | 400 `The picture is {w} × {h} px: at least 64 and at most 12,000 px a side.` |
| count | ≤ 6 tip pictures per calibration record; ≤ 20 pictures of a setup not linked to a calibration | 409 `too-many`: `The 25G straight has 20 pictures already: remove one first.` |
| disk | removable pictures (tip and setup) ≤ 2 GB; result photos are not counted (they are pruned, below) | 409 `disk`: `The pictures take 2.0 GB already: remove old ones first.` |
| caption | ≤ 200 characters, control characters removed | 400 `The caption must be at most 200 characters.` |

**Storage** (`D/PhotoStore.java`, new, synchronized): every photo is re-encoded by the engine (JPEG quality 0.85, at most
2048 px on the long side, no metadata) and a 320 px thumbnail made; both are written to a temporary file **in the month
folder** (so `ATOMIC_MOVE` stays on one file system) and moved to `<configDir>/instancelabs/photos/<yyyy-MM>/<first 20
hex of the JPEG's SHA-256>.jpg` and `….thumb.jpg`; then the row is inserted with that relative path. Equal pictures
share a file (content-addressed). At start a sweep deletes files no live row names (a crash between the move and the
insert), files of photos removed more than 7 days ago, and result photos never linked to a record (stopped and failed
jobs) after 30 days; a row whose file is missing is served as 410 `file missing`. The DB backup does not copy the
folder.

**Serving** by id only: `GET /api/photos/{id}.jpg` and `GET /api/photos/{id}/thumb.jpg` read the row, refuse a removed
photo (410 `removed`, with when and why), resolve the file under `<configDir>/instancelabs/photos/` (a path that leaves
it: 500 `photo file outside its folder`), and answer `image/jpeg` (`Response.bytes`, A/ApiServer.java:310-312) with
`Cache-Control: private, no-cache` and `ETag` = the SHA-256 (`If-None-Match` → 304; `Response.header`, :300-303, over
the default `no-store` of :518), so a removed picture leaves the screens at once. Pages use them as `<img src>`: same
origin, allowed by the CSP (`img-src 'self' data: blob:`, :523-526).

**Removal**: `DELETE /api/photos/{id} {why?}` appends a `photo_events` `remove` row; the file stays 7 days (`POST
/api/photos/{id}/restore` brings it back, the toast's `Undo`), then the start sweep deletes it unless another live row
shares it. Result photos are refused (409: `A result photo is the calibration's evidence: it stays.`); tip and setup
pictures can go. Records then say `1 picture removed {date} ({why})`.

### 22.5 The "calibrated for" label

Every calibration record (`pad_calibrations` row) gets one, D4:

- **Who writes a record:** the job's decision (today, A/PadCalibrationJob.java:1591-1672 through
  `applyPadCalibration`, D/DispenseService.java:3121-3149: method `auto`), and, new, **the by-hand save**: the pad-type
  editor saving S's version with its status raised to `characterized` or `validated`
  (`DispenseService.savePadTypeByHand(version, forSetup, label, photoIds, operator)`): method `by-hand`, outcome the new
  status (`validated` joins `PadCalibrationRecord.OUTCOMES`, D/PadCalibrationRecord.java:13-18, for by-hand records
  only: the job still never writes it), checks = the status-evidence rule that let it pass (`status-evidence`: the
  record ids; `validated-by-you` for a validation), `job_id` `hand-{createdAt}`, `needle_since_ms` the token; it links
  the result photos of the jobs its evidence came from and the main picture (§22.4).
- **Auto-fill** (`Texts.calLabel`, engine-side, when the record is written): `{type} · {setup} · {dispenser} · {psi}
  psi · {paste}{ lot {lot}}`, type first, as the user said it ("0603 for a 22 bent tip"): the pad type as Profile names
  it (the family's word and the name, W/padprofile.js:49-56@pc: `Chip 0603`; a name that contains `·` goes in brackets,
  `SOT-23 (1.48 × 0.60 mm)`), S's name and dispenser, the profile's pressure (`DispenseProfile.plain`,
  D/DispenseProfile.java:173-175) and the paste's name and lot (D/PasteSpec.java:12-16). Example: `Chip 0603 · 22G bent
  taper · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305 T5` (77 characters). Over 160 characters the paste, then the
  type, is cut with `…`. Stored as a `cal_labels` row with `auto 1`.
- **Records from before schema 3** get no row: their label is computed when read (the type, the record's setup as
  §22.1 resolves it, and the pressure and paste of its evidence dose records when it has any), `labelAuto` true, so
  Records never shows `—` for them; nothing is written for them until the operator edits one.
- **Editing:** `PUT /api/pad-calibrations/{id}/label {label}` appends a row (`auto 0`, the operator); 1–160 characters
  after trimming and joining spaces, control characters removed (400 `The label must be 1 to 160 characters.`).
  `{auto: true}` appends the record's automatic text again. A label is never part of a fingerprint, so editing it never
  makes a plan stale; that is why it lives on the record, not on the pad type version.
- **Shown:** on the result card (`Calibrated for` with the outcome badge; `Calibration for` when the outcome is below
  `characterized`), in Records, in Profile's per-tip view and side panel as visible text (never only in a `title`).

### 22.6 Web API (conventions of WEB_API.md §2–3; new groups `A/TipSetupEndpoints.java`, `A/PhotoEndpoints.java`, registered after PadCalibrationEndpoints, A/ApiServer.java:121)

| route | body / query | response; refusals |
|---|---|---|
| `GET /api/tip-setups` | `retired?` | `{mounted, mountedAtMs, needleChangedAtMs, undoUntilMs, setups: [setup view]}` |
| `POST /api/tip-setups` | the §22.1 fields, `key?`, `profileFrom?` (a setup key or `default`; default S), `mount?` | 201 `{setup, mount?, note: "Added. It has no pad profiles yet."}`; 400 field text; 409 key or name taken |
| `PUT /api/tip-setups/{key}` | fields to change, `correction?` | the setup view; 409 `another-needle`, `correction`; 409 while the engine is busy (S only) |
| `POST /api/tip-setups/{key}/retire` · `/unretire` | – | the setup view; 409 `The mounted tip cannot be retired.` |
| `GET /api/tip-setups/{key}/mount-preview` | – | `{text, consequences: [...], warnings: [...], refusal}` (the change panel's text) |
| `POST /api/tip-setups/{key}/mount` | `{why?, operator?}` | §22.3 response; 409 §22.3 refusals with `needleChange` (the change is recorded either way) |
| `POST /api/tip-setups/undo-mount` | `{operator?}` | the mount response of the restored setup; 409 too late |
| `POST /api/tip-setups/{key}/picture` | `{calibrationIds?, wait?}` | 202 `{kind: "tip picture", started: true}`, with `wait` the photo (`ApiContext.awaitIf`, A/ApiContext.java:145); 409 §22.4 refusals |
| `POST /api/needle/changed` (changed) | `{why?, operator?}` | `{needleChange: {atMs, voided: {coupons, job, task}}, text}`; never refused |
| `POST /api/needle/anchor`, `/api/needle/offset` (changed) | + `needleChangedAtMs` | as today (A/DispenseEndpoints.java:364-398), + the frame question of §22.3 and the I4 offset warning; 409 `needle-changed` |
| `POST /api/needle/xy-trim` | `{dxMm, dyMm, note?, needleChangedAtMs}` | `{needleXyTrim}`; 400 beyond ±1 mm; 409 while the engine is busy, `needle-changed` |
| `POST /api/needle/landing` | `{keep: true, needleChangedAtMs}` or `{jobId, accept: true, needleChangedAtMs}` | `{needleLanding, xyChecked: true}`; 409 §22.3 reasons, `needle-changed` |
| `PUT /api/config` (changed) | + `forTip` with `profile` | 400 `tip`, `parkedTips`, `needleLanding`, `needleXyTrim`; 409 `tip-changed` |
| `POST /api/photos` | query `kind` (`tip`/`setup`), `setup`, `calibrations?` (comma-separated ids), `job?`, `caption?`; body the image | 201 `{photo}`; §22.4 refusals; 409 `busy`, engine busy, `setup-mismatch` |
| `GET /api/photos` | `setup?, calibration?, job?, kind?, removed?, limit?` | `[photo]`, newest first |
| `GET /api/photos/{id}.jpg` · `/{id}/thumb.jpg` | `If-None-Match?` | `image/jpeg` with `ETag`; 304; 404; 410 |
| `DELETE /api/photos/{id}` | `{why?}` | `{removed: true, id, restoreUntilMs}`; 409 result photo |
| `POST /api/photos/{id}/restore` · `/main` | – | the photo view; 410 the file is gone |
| `POST /api/photos/{id}/links` | `{calibrationIds}` | `{links}`; 409 `setup-mismatch`, `needle-changed` |
| `PUT /api/pad-calibrations/{id}/label` | `{label}` or `{auto: true}` | `{id, label, auto, atMs}`; 400 |
| `POST /api/pad-calibrations/{id}/wrong-tip` | `{operator?}` | `{flagged: jobId, restored: [{typeKey, version}]}` |
| `GET /api/pad-calibrations` (changed) | `typeKey?, setup?, limit?` | rows + `setupKey, setupName, method, label, labelAuto, photos, flags, jobInMemory` |
| `POST /api/pad-calibrations` (changed), its `preview` | `measure?: "tip" \| "landing"`, `wide?` | as §12.2; `measure` = records only, no pad types; the preview gives the band and R |
| `GET /api/pad-types` (changed) | – | `forSetup: {key, name}`; S's composed rows with `setupKey, standIn, padVersion, version, nextVersion, label, others: [{setupKey, setupName, version, status, label, atMs, thumbUrl}]`; `versions`, `lastValidatedAtMs` and `lastCalibration` (A/PadTypeEndpoints.java:115-126) counted for S only |
| `GET /api/pad-types/{key}` (changed) | – | `current` (S's composed), `history` (every setup, each with `setupKey`, `setupName`), `bySetup` |
| `POST /api/pad-types/{key}/copy` | `{fromSetup, forSetup}` | the new version; 400 `checkDose`; 409 §22.2 |
| `POST`/`PUT /api/pad-types` (changed), `DELETE /api/pad-types/{key}` (retire) | + `forSetup` (required), `padVersion` (a pad edit), `calibration?: {label?, photoIds?}` | + `calibration: {id, label, photos}` when the status was raised; 409 `tip-changed`, `pad-changed` |

Setup view and photo view:

```json
{"key": "ad22tta-b", "version": 1, "name": "22G bent taper", "dispenser": "Vieweg DV-5005", "tipModel": "ADL AD22TTA-B",
 "gauge": 22, "idMm": 0.41, "odMm": 0.72, "odSource": "assumed", "form": "bent", "bendDeg": 30, "bendToward": null,
 "taper": "tapered-plastic", "lengthMm": null, "minDotSteps": 400, "model": "bench-22g", "complete": true,
 "retired": false, "mounted": true, "mountedAtMs": 1793…, "editable": {"identity": false, "correction": true},
 "summary": "ADL AD22TTA-B · 22G · bent 30° · tapered plastic · ID 0.41 mm · OD 0.72 mm (from the chart)",
 "profile": {"name": "18 psi half turn", "pressurePsi": 18, "netStepsPerDot": 800, "parked": false},
 "landing": {"dxMm": 0, "dyMm": 0, "setAtMs": 1793…, "checked": true, "note": "kept from before the update (boards ran with it)"},
 "padTypes": {"validated": 1, "characterized": 8, "provisional": 2, "names": ["Chip 0603", "Chip 0805", "SOT-23"]},
 "dots": {"source": "bench", "atMs": null, "mgPerTurn": 2.11,
          "doses": [{"steps": 400, "diameterMm": [0.83, 1.00], "source": "model"}, {"steps": 800, "diameterMm": [1.16, 1.16], "source": "model"},
                    {"steps": 1600, "diameterMm": [1.345, 1.345], "source": "model"}]},
 "mainPhoto": {"id": 12, "kind": "setup", "source": "upload", "atMs": 1793…, "url": "/api/photos/12.jpg",
               "thumbUrl": "/api/photos/12/thumb.jpg", "caption": "22G bent taper, from the side",
               "widthPx": 2048, "heightPx": 1536, "links": [41, 42], "removable": true},
 "photos": [ … the same shape … ]}
```

`dots.source` is `bench`, `measured` (with `atMs`; `diameterMm` then the measured mean and d95, as §4.6's) or `none`;
masses are per turn (`mgPerTurn`), as the UI speaks in turns. `GET /api/status` adds `dispense.tip` = `{key, name,
setupVersion, dispenser, form, bendToward, summary, mountedAtMs, dots: "bench" | "measured" | "none", thumbUrl,
undoUntilMs}`, and `dispense.needle` gains `xyChecked`, `landing` (with `keepable` and, when not, the reason of §22.3),
`headOffsetNow` and `headOffsetChanged`
(A/DispenseEndpoints.java:152-156). The job view (`status.padCalibration`, §12.3) adds `measure`, `forTip`,
`result.landing: {dxMm, dyMm, sdMm, n, found, searchMm, coarse, saved, question, text}`, `result.tipDots` (measure tip:
per dose found, d̄, d95, threads/hops, spread, pass; `mgPerTurn`), `result.photos`, and per group `calibrationId`,
`label`, `labelAuto`, `photos`. The check's `tip` (A/PadProfileViews.java:131-132) adds `setupVersion`, `dispenser` and
`dots`, writes `minDotMm` null when the tip does not know its smallest dose, and the check gains `otherTips: [{key,
name, covers, of}]` (setups not retired whose versions of every type in the plan are at or above the gate).

### 22.7 Web UI

New module `W/tipsetup.js` + `W/tipsetup.css` (loaded after `padprofile.js`, W/index.html:13, :25@pc): `IL.tips` (the
strip cell, the Setup card, the change panel, the add dialog, picture and upload actions) and `IL.photos`
(`normalize(file)`, a drop zone, a thumbnail strip, the viewer). Binding rules as §13: CSP, no inline handlers, never
`prompt()`, `confirm()` or `showModal()`; machine motion two-press (`IL.press2`): **Take a picture** (verb `move to the
camera`), **Mount** (verb `mount`), **Undo** (verb `undo: the {old} never left the dispenser`); the landing check and
Measure its dots start from the pad-cal view like every calibration. And:

- **STOP stays reachable.** The OS file chooser is modal to the browser window: while it is open F9 and the STOP button
  do nothing. Every button that opens a chooser is gated with `IL.gate(btn, IL.lockReason(s))` (W/core.js:701-731@pc),
  so it is locked while a task runs, a pad calibration runs or the pressure is held (`Locked while the board runs: add
  the photo when it has finished`); the drop zones need no chooser and stay open during a run (an upload during a run
  is still refused by the engine, §22.4, with its own text).
- **`#photo-view`** is `IL.dialog.open({id: 'photo-view', size: 'lg'})` (W/core.js:2706-2730@pc; `.dialog--lg {width:
  min(1100px, 100%)}` in tipsetup.css): the scrim starts under the strip, STOP stays live, Esc closes, focus returns to
  the thumbnail, the image fits the dialog (`object-fit: contain`), never the viewport. Its foot holds the caption,
  `Use as the tip's picture`, and `Remove this picture` (normal size, two presses; not on result photos).
- **Keyboard** (UI_DESIGN.md §14): lists to choose from are `fieldset`s of `label > input[type=radio]` (arrow keys and
  Space work natively); thumbnails are `button.photo-thumb > img[alt={caption}]`; chips are `button`s; remove lives only
  in `#photo-view`; `Enter` in a label field saves only that label (`data-enter-submits`) and never starts a motion.
- **Drop zones** (`div.photo-drop`, as Board import's, W/boardimport.js:129-150@pc) on the result card's picture block,
  the Setup card and the Records panel, with `input[type=file][accept="image/*"]` (the browser re-encodes, §22.4) and
  the hint `A phone picture: copy it to this computer first (cable, cloud or mail).`

**The strip** (W/index.html:86-93@pc): a cell `button.cell#chip-tip` after `#chip-purge`: key `Tip`, value the mounted
setup's name (`22G bent taper`, ok), `25G straight · frame and landing to do` (block) after a change until both are
done, `Incomplete` (block); it navigates to `#setup/tip`. At 1024 px the `· frame and landing to do` part is a
`span.long[data-l=2]` that hides with the strip's other long parts (cells--t2, W/core.js:1873-1911@pc, as the
Pressure cell's set value does, :2002-2004@pc); a Playwright check keeps the strip on one line at 1024 and 1280 px.

**Setup › Dispenser & tip** (`section.card#card-tip`, first in the grid, W/setup.js:2145@pc; `SECTION_CARD.tip`,
:186-187@pc):

| id | content |
|---|---|
| `#tip-state` | `22G bent taper` (ok) · `Changed {HH:MM}: frame and landing to do` (block) · `Dots not measured` (need) · `Incomplete` (block) |
| `button.photo-thumb#tip-photo`, `#tip-photo-cap` | S's main picture (opens `#photo-view`); without one `#tip-photo-none` (an outline icon) `No picture of this tip yet.`; under it two small buttons `#btn-tip-upload` `Add a photo…` (+ hidden `input#tip-file`, gated) and `#btn-tip-picture` `Take a picture from below` (gated with its reasons: `Home first`, `Pressure is held`, `Only the mounted tip`), and the drop zone |
| `h3#tip-name`, `dl#tip-kv` | name; Dispenser; Tip (`ADL AD22TTA-B · 22G · bent 30° · tapered plastic`); Bend (`points toward −Y` · `not recorded: which way does it point?` with four small buttons); Bore (`ID 0.41 mm · OD 0.72 mm (from the chart)`); Smallest dot (`quarter turn (400 steps)`); Dots (`from the bench tests (2026-10-27/28)` · `measured {date}: quarter 0.71, half 0.93, one turn 1.12 mm; 1.57 mg per turn` · `not measured yet`); Pad types (`9 pad types calibrated, 1 validated on a board` · `none calibrated yet`); Recipe (`18 psi half turn`); Lands (`+2.32, −0.41 mm, checked 10-29 14:20` · `not checked since the change`); On since (`2026-10-27 18:30` · `before the update`) |
| `ol#tip-next` | after a change: the §22.3 order, one `li` per step with a glyph, its state and a fix button; done steps collapse |
| foot | the primary: the first open `#tip-next` step after a change, else `#btn-tip-change` `Change tip…`; `details.menu#tip-more` `More`: `#btn-tip-add` `Add a tip…`, `#btn-tip-edit` `Edit…`, `#btn-tip-retire` `Retire…` (two presses; not S) |

Bring-up (W/setup.js:213-319@pc): a step `Dispenser & tip` after Settings (ok: the name; need: `dots not measured`, fix
`Measure its dots…`; block: `incomplete`, fix `Edit…`). The trim step (:286-290@pc) becomes `Where the needle lands`:
`block` `not checked since the change` when `needle.xyChecked` is false, fix `Check where it lands…` (and `Keep where it
lands` only when the engine would allow it, §22.3). The prime step (:281-285@pc) lapses at a needle change. `STAMPED_TOP`
(:24-26@pc) gains `tip`, `parkedTips`, `needleLanding` and `needleXyTrim`; `NESTED` loses `needleXyTrim`; the settings
editor sends `forTip` with any `profile` key (:1947-1962@pc). The trim panel (:642-686@pc) saves through `POST
/api/needle/xy-trim` with the token.

**Change tip** (panel view `tip-change`, `?panel=tip-change`; also what "I changed the needle" opens):

- `ol#tip-swap` first: `1 Switch the pressure off` (live: `it is off` ✓, or `#btn-tip-pressure-off`), `2 Unscrew the
  needle and screw on the one you are putting on` (for a bent one: `point its bend toward −Y, as in its picture`, with
  the picture), `3 Pick it below and mount it`. Mount is the moment the operator says the needle is on.
- `fieldset#tip-list` (legend `Which tip is on the dispenser now?`): `label.tip-opt > input[type=radio][name=tip]`,
  first the mounted one as `The 22G bent taper again (a new needle)`, then the others: thumbnail, name, summary, its
  calibrated types (`Chip 0603, Chip 0805, SOT-23 · +3 more` · `no pad types calibrated yet`), `incomplete` on
  placeholders. `details.disclose#tip-retired` `Show retired tips` lists the retired ones with `Bring back`. With only
  the mounted tip: `Only the 22G bent taper is set up. Add the tip you are putting on: Add a tip…` (and the a-new-needle
  row).
- `#tip-change-what` (the mount preview): `Nothing dispenses until you record the 25G straight's needle Z frame and
  check where it lands. The 22G bent taper keeps its 9 calibrated pad types; the 25G straight has none yet. Recipe: 18
  psi half turn · 25G straight (copied 2026-10-29). Plans made before go stale. coupon1's dots will not count: wipe it.`
  For the same tip: `A new needle: record its needle Z frame and check where it lands; its pad types and recipe stay.`
- `#btn-tip-mount` `Mount the {name}` / `This is a new needle`, two presses; gated with `Save or discard the 22G bent
  taper's recipe changes first.` while the Profile card or the pad-type editor has unsaved edits (UI_DESIGN.md §15),
  with `IL.lockReason` while a task or job runs (`a needle cannot come off while the head moves: press STOP first`);
  `#tip-change-why`. Toast after a mount of another tip: `25G straight mounted. Undo` (2 min, `#btn-tip-undo`).

**Add a tip** (in-page dialog `#dlg-tip-add`, opened as `#dlg-padtype` is, W/profile.js:725@pc; also from Change tip):
`#ta-preset` (fills the form **and the name**: the needles on hand, chart values below, OD `from the chart`; `Other…`
leaves it blank), `#ta-name`, `#ta-dispenser`, `#ta-model`, `#ta-gauge`, `#ta-id`, `#ta-od`, `#ta-od-src`, `#ta-form` +
`#ta-bend` + `#ta-toward`, `#ta-taper`, `#ta-length`, `#ta-mindot`, `#ta-profile-from` (`Start its dispense profile
from`: the setups' profiles, or `the defaults (18 psi half turn)`), `#ta-notes`, the photo (`#ta-photo`, gated, + drop
zone, `img#ta-photo-img`), more: `#ta-key`; foot `#ta-save` `Add the tip` and `#ta-save-mount` `Add and mount` (two
presses: it is on the dispenser now). The photo is uploaded after the setup is created (`kind=setup`); if that fails the
dialog says `The tip was added without its picture: {reason}`. **Edit** opens the same dialog with the key read-only;
for a setup with records the identity fields are disabled (`A different needle is a new tip: Add a tip…`), and changing
a correction field turns Save into a two-press `Correct the details` with the note `Correcting the 22G bent taper's
details? A different needle is a new tip: Add a tip…`.

| preset (fills the name) | ID mm | OD mm | form | taper | length |
|---|---|---|---|---|---|
| `22G bent taper` (ADL AD22TTA-B, 30°) | 0.41 | 0.72 | bent 30° | tapered plastic | – |
| `14G / 18G / 22G straight taper` (DSKIT1) | 1.54 / 0.84 / 0.41 | 1.83 / 1.27 / 0.72 | straight | tapered plastic | – |
| `14G / 18G / 21G / 23G / 25G / 27G / 30G straight` (DSKIT1, 0.5 in) | 1.54 / 0.84 / 0.51 / 0.33 / 0.25 / 0.20 / 0.15 | 1.83 / 1.27 / 0.82 / 0.64 / 0.51 / 0.41 / 0.31 | straight | steel | 12.7 |
| `14G / 15G / 18G / 20G / 21G / 22G / 25G / 27G / 30G bent 45°` (SA7529, 0.5 in) | 1.54 / 1.37 / 0.84 / 0.60 / 0.51 / 0.41 / 0.25 / 0.20 / 0.15 | 1.83 / 1.65 / 1.27 / 0.91 / 0.82 / 0.72 / 0.51 / 0.41 / 0.31 | bent 45° | steel | 12.7 |
| `19G footed` (ITW 15898) | 0.69 | 1.07 | straight | `unknown` (notes: `footed: the foot touches the board, the gap is not the engine's`) | – |
| `Other…` | – | – | – | – | – |

**Needle Z frame** card (W/setup.js:447-527@pc): `#btn-needle-changed` `I changed the needle` opens Change tip with the
mounted tip's `again (a new needle)` row checked (one way in; the old direct two-press post goes). `F.k` (:572@pc,
today 65.59 for every needle) is prefilled only from the mounted setup's own parked anchor, captioned `recorded for the
22G bent taper on 10-27`; for a tip that was never on the field is empty, the caption `K of the 25G straight: not known
yet`, the method preselected to Both tips touch, and the lead `A tip that was never on: touch the same spot with N1 and
with the needle (jog in OpenPnP). Another tip's K does not fit: lengths differ by up to 10 mm.`; the mount text and
`#tip-next` then say `Both tips touch, or touch off`, never `confirm the offset` alone. Every post carries the token the
heights were captured under (`IL.tips.token()` at capture). The frame question of §22.3 shows here with the picture.

**Profile** (W/profile.js@pc): `#pf-h1-sub` (:1189@pc) shows the mounted tip's thumbnail, ` · for the {S}` and a
`Change tip…` link; the profile card's sub (:473@pc) `{profile} · the recipe of the {S}: every dot on a board and on the
purge row`. Pad types:

- the Status column is S's (a stand-in's badge `not started`, with the visible line `Not calibrated for the {S} yet`);
  the seventh column of the first design is not added (COLS :578-580@pc stay as fitted at 1024 px): with more than one
  tip, the name cell gets a small `button.pt-tips` `+2 tips` that opens the side panel at `#pt-side-tips`; with one tip
  nothing is shown;
- a third filter next to the family filter, `By tip` (`#pt-by-tip`, only with more than one tip not retired): rows are
  pad types, one column per tip with the mounted one first (header: thumbnail, name, `on now`); each cell is a status
  badge `button[data-setup][data-type]` with the label's type-free part as visible text, opening that tip's version in
  the side panel; built from `GET /api/pad-types` `others` (no new route);
- the editor (`#dlg-padtype`): eyebrow `Pad type · chip-0603 · for the 25G straight` (:730@pc); version note (:732-734@pc)
  `Saves version 8 for the 25G straight; the 22G bent taper keeps version 7. Size, pattern, name, k and t are shared by
  every tip.`; Save (:754@pc) `Save version 8` from `nextVersion`; the PUT carries `forSetup` and `padVersion`;
- Retire (:1119-1131@pc) becomes `Retire for every tip…` with the note `A retired type is never matched to a package or
  dispensed, whichever tip is on.`;
- History (`#dlg-history`, :903-935@pc) gains a Tip column and a `This tip / All tips` filter; "changed" marks compare
  with the previous version **of the same tip**; footer `Version numbers count across tips: v6 is the 25G straight's.`;
- the side panel (`renderTypePanel`, :1045@pc) gains `section#pt-side-tips` `Other tips`: per setup `div.pt-tip[data-
  setup]` with the version, status, the label (visible text), the result-photo thumbnail (a button) and
  `button[data-action=copy-from]` `Copy its dots to start from` (shown while S's version is below `characterized`;
  `Saves a provisional version for the {S} with this plan: {n} {turn} dots per pad. Nothing measured comes with it.`);
  with nothing in it: `Not calibrated for any other tip.`;
- the editor's `fieldset#pt-calfor` `Calibrated for` (via `IL.pt.editorExtras`), shown when the status rises to
  `characterized` or `validated`: `#pt-label` (prefilled), `#pt-cal-photos` (a `label.check` per photo: the guide
  ladder's result photo of this dose ticked, the tip pictures), `#btn-pt-upload` `Add a photo…` (+ `input#pt-file`,
  gated) and the drop zone.

**Needs a profile** (W/padprofile.js:325-356@pc): the title reads `Needs a profile` + `span#pt-needs-for` ` for the 22G
bent taper`; with an unmeasured S, `div.notebox#pt-needs-measure` (the §22.2 sentence) and `#btn-needs-measure`
`Measure its dots… · ≈ 8 min`; rows whose suggestion is `measure-tip` show it and no calibrate button. When another
tip covers the whole board (`profileCheck.otherTips`), a note `The 22G bent taper has all 5 pad types of this board
(Change tip…)`; Run's Pad types readiness item says the same. The texts of §3.4 already name the tip through
`profileCheck.tip.name` (W/padprofile.js:213, :412@pc), now the setup's name (the fallback `22G tip` becomes `22G bent
taper`). The suggestion card gains `details.disclose#pt-sugg-tips` `Calibrated for other tips ({n})` with `Copy its
dots to start from`.

**The pad-cal view** (W/padprofile.js:895-917@pc) per mode; the title (:896@pc), the start button (:915-917@pc) and the
foot follow the job's `measure`:

| | calibrate (today) | `measure: "landing"` | `measure: "tip"` |
|---|---|---|---|
| title | `Calibrate automatically` | `Check where the 25G straight lands` | `Measure the 25G straight's dots` |
| first line (all modes) | `For the 22G bent taper (on since 10-27 18:30)` with the main picture's thumbnail | same | same |
| before start | §7.9 | `Lays one dot, then 8 purge dots and 8 dots in a row on the blank (X 286.0–326.0, Y 250.0–270.0), looks at them, and saves where the needle lands. Uses 20 mm of the calibration area ({left} left). Searches ±6 mm.` | `Lays quarter, half and one-turn dots on the blank and 2 coupons to weigh; saves the 25G straight's dot sizes and paste per turn. No pad type changes.` |
| start (two presses) | `Calibrate · ≈ M:SS`, verb `calibrate` | `Check where it lands · ≈ 1:00`, verb `lay 17 dots` | `Measure the dots · ≈ 8:00`, verb `measure` |
| result primary | as pc-web | the next `#tip-next` step (`Touch off…`, `Measure its dots…` or `Calibrate the pad types of {board}…`) | `Calibrate the 5 pad types of {board} for the 25G straight…` |
| not shown | – | `#pcal-for`, Calibrate again, I'll do it by hand | the same |

**The result card** (`pad-cal` view, W/padprofile.js:995-1047, :1216-1263@pc):

- **under the verdict, once**: `div#pcal-tip` `The tip`: the record's reference picture (the main picture, a button
  opening `#photo-view`) and the pictures taken for this calibration, then `#btn-pcal-upload` `Add a photo…` (+
  `input#pcal-file`, gated), `#btn-pcal-picture` `Take a picture from below` (two presses, gated) and the drop zone;
  linked to every record of the job. Without a main picture: `No picture of this tip yet: add one, it shows later which
  needle this was calibrated for.`;
- **per group**, inside its existing `li` of `#pcal-groups` (:1029@pc): the label as text, `Calibrated for Chip 0603 ·
  22G bent taper · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305 T5` (with the outcome badge; `Calibration for` below
  characterized), and `button.link` `Edit`, which turns it into a 2-row `textarea#pcal-label-{id}` (maxlength 160) with
  the usual dirty bar `Unsaved: Save · Discard`; `button.link[data-action=label-auto]` `Use the automatic label` only
  when the text differs from it;
- **one result photo per dose** (`figure.pcal-photo`, a button opening `#photo-view`, caption) next to `#pcal-crops`
  (:1031@pc), not one per group; without one: `No result photo: the top camera gave no frame.`;
- `#pcal-landing`: the landing line of §22.3 (saved, or the question with its two buttons); `table#pcal-tipdots`
  (measure tip): Turn · Found · Mean · d95 · Threads · Spread · Pass; then `{mg} mg per turn ({n} readings{,
  confirmed})`;
- the foot gains `#btn-pcal-wrongtip` `Made with another tip…` (two presses, §22.3). With a label edited and not saved,
  `Checks ›` (:1018@pc) first shows the dirty bar and moves focus to it; a second press discards the edit and goes
  on (UI_DESIGN.md §15).

**Run** (W/run.js@pc, now in `ts-web`): the run confirm's `You confirm (nothing senses these)` (:2746@pc) gains the
tick `label.check#run-confirm-tip` `The 22G bent taper is on the dispenser` with its thumbnail (attestation `tip-on`,
value the setup key and the needle token, cleared at every needle change and mount); Go needs it like the paste tick.
The hero's recipe token (`n.recipe`, :510-511@pc) adds ` · for the {S}` (the plan's `setupName`).

**Records** (W/records.js@pc): the Calibrations tab (:138-146, :221-236@pc) lists pad calibrations in
`table#rec-pcals-t`: When · Calibrated for (the label, and a second line `Chip 0603 · v7`) · Tip · How (`automatic` /
`by hand`) · Outcome (badge; `wrong tip` when flagged) · Pictures (`span.rec-thumbs`: buttons, three, then `+n`); a seg
`All tips / {tip}` (`GET /api/pad-calibrations?setup=`). A row opens `#records/calibrations/p{id}`: the label
(editable: `#rec-label`, `#btn-rec-label`), setup, type and versions, checks, the photos full width (`figure.rec-photo`,
buttons) with `#btn-rec-upload` `Add a photo…` (upload only: Records stays motion-free; gated; + drop zone) and the
evidence ids; `Open the result` (`#records?panel=pad-cal&job={id}`: the pad-cal view reads the `job` parameter and loads
`GET /api/pad-calibrations/{job}`, A/PadCalibrationEndpoints.java:45) while the job is one of the 5 kept in memory
(`jobInMemory`); `Made with another tip…`. Without pad calibrations: `No pad calibrations yet: Profile › Needs a
profile › Calibrate automatically.` The q-model table moves into `details.disclose#rec-cals-old` `Older calibration
records (volume model, records only)`. The Boards table gains a `Tip` column (`runs.setup_key`, `—` before the update);
`runTitle` (:19-35@pc) names `tip-picture` `Tip picture`.

**Core** (W/core.js@pc): the readiness Needle item (:1003-1027@pc): after a change `{state: 'block', value: 'changed',
detail: 'The 25G straight was put on at 14:05: record its needle Z frame.'}` (instead of `The needle was changed after
the needle Z frame was recorded.`, :1012@pc); after the drift check `{state: 'block', value: 'landing', short: 'where
it lands', detail: 'The 25G straight was put on at 14:05: where it lands has not been checked.', fixes: [Check where
it lands…, Keep where it lands (straight only)]}`, and the head-offset text of §22.3 when `headOffsetChanged`;
`attestValid` (:605-614@pc) lapses `prime-seen`, `test-dot-seen` and `tip-on` at a needle change as it does the
regulator; `TASK_WORD`/`taskParts` (:1370-1373, :1396@pc) add `tip picture` → `Tip picture · moving to the bottom
camera`.

**Calibrate** (W/calibrate.js@pc): the guide (`#setup-guide`) shows the ladder step's result photo (`img.guide-photo`)
under `table#pcal-ladder`. The scale shots' `ss-tip` field (:320@pc, free text `22G 0.41 mm`) is prefilled with the
mounted tip's name and read-only, title `the mounted tip (Setup › Dispenser & tip)`; old records keep their text.

Empty and edge states not covered above: a tip with no picture in any list shows an outline icon with `no picture`; a
removed picture shows in Records as `1 picture removed {date} ({why})`; the seeded tip's `On since` says `before the
update` until its first change.

### 22.8 Tests

**Unit** (JUnit, `mvn -B -o -T1 test`; engine tests in `ts-engine`):

- **`T/dispense/DispenseDbMigrationV3Test`**: a v2 file made with the v2 DDL and rows (pad types with `tip_key` null,
  `ad22tta-b` and `x25`; dose records of two jobs, one `x25`; pad calibrations: one characterized with `version_to`, one
  failed `x25` row without `version_to`; runs) → open → `schema_info` 3; the tables, views and triggers (UPDATE and
  DELETE abort on each); the columns; the seeded setup; a placeholder `x25` (incomplete); every old row equal before and
  after, compared **on the v2 column list**; the failed row resolves to `x25` through its job's dose records; the
  `.v2-*.bak`; a second open changes nothing; a v1 file goes to 3 in one open; a fresh database starts at 3 with the seed
  pad types (no `schema_info` row written by `to3()`).
- **`T/dispense/TipSetupsTest`**: `typesFor` composition (pad fields from the newest version of any setup, dose fields
  from S's; stand-ins with the plan cleared, `version` 0; null `tip_key` = seeded; creation order kept); `Tip.setupOf`;
  the fingerprint per setup and setup version; key derivation and uniqueness; every field check and its text; identity
  edits refused with records, corrections (ID within 0.03 mm) accepted; copy rules (`checkDose` applies: a quarter-turn
  copy onto a `minDotSteps` 800 setup is refused). **Reconcile** from a fixture copy of a real schema-3
  `dispense-config.json` (`src/test/resources/tips/dispense-config-v3.json`: `{key ad22tta-b, name "22G tip", idMm 0.41, odMm
  0.72, minDotSteps 400}`, `travelAccelMmS2` 3000): no new version, `cfg.tip.name` `22G bent taper`,
  `travelAccelMmS2` still 3000, the `.v3-*.bak` at the first save; the same with `odMm` 0.80 → a version 2 named `22G
  bent taper` with OD 0.80 (`assumed`); an unknown key → a setup; an empty `tip_mounts` → a `seed` row; the 3 → 4
  landing for a finished and an open needle change.
- **`T/dispense/PadDefinitionSharedTest`**: change chip-0603's pattern and pad width under the 25G, mount the 22G → the
  same resolution and the same amount for a board's 0603 pads; a retire under the 25G retires it for the 22G; the
  22G's characterized version keeps its status and the check flags `OUTSIDE_AREA` only beyond the ratio.
- **`T/dispense/MountTest`**: park and restore of profile, frame, offset, trim and landing; a new setup's empty entry
  (no frame, a valid `needleXyTrim`, I4 refuses); `needleChangedAtMs` strictly increasing; the live start angle carried
  across (a mount after a controller reconnect leaves I11 as it was); older records stale; the rows and events; each (b)
  refusal in order with its text, and in every one of them the needle change recorded and I4 refusing a board run; **a
  needle change with pending coupons, a job waiting for readings, the pressure hold on and an incomplete S** → recorded,
  skip rows written, the job told, then I4 refuses a board run; a needle change during a task stops it; an invalid
  incoming profile is reset; undo within 2 min, refused after a task or a frame.
- **`T/dispense/MountRaceTest`** (latches; a test hook pauses each path at its critical point): a profile patch built
  for the old setup and applied after a mount gets `tip-changed`, and no edit applied after a mount puts back the old
  tip, profile or needle fields (the edit sees the live config); a task submit and a `savePadType` for the old setup
  each interleaved with a mount (the task is refused, the save gets `tip-changed`); a frame posted after a needle change
  with the old token gets `needle-changed`; a dose-blocks step carrying the old setup or token is refused on the machine
  thread.
- **`T/dispense/PhotoStoreTest`**: JPEG and PNG accepted; an RGBA and an indexed PNG stored as RGB JPEG; GIF and garbage
  refused; a 20,000 px header refused before decoding; a 6000 × 4000 JPEG decoded subsampled (peak heap asserted under
  a bound); a JPEG with an EXIF GPS segment stored without APP1; 4000 × 3000 → 2048 × 1536 and a 320 px thumbnail; equal
  pictures share a file; removal keeps a shared file and the file for 7 days; restore; the start sweep (an orphan file,
  a row without a file, a 30-day unlinked result photo); a row whose path leaves the folder is refused; counts, the
  caption limit and the disk cap counting removable pictures only; two uploads at once → one 409 `busy`.
- **`T/dispense/TipPictureTaskTest`** (SimMachine, a fake `TipCamera` at (217.13, 152.32) that draws a ring): the
  recorded G-code moves N2 to the camera's X/Y at Z 31.5 and never below; refused when 31.5 is outside N2's safe zone,
  unhomed, under the pressure hold, for a setup not mounted; STOP during the move, and after the capture, leaves no
  photo; a needle change during the task leaves no photo; the row and its links.
- **`T/dispense/InterlocksXyTest`**: with the landing not checked, a board run, a pad calibration, the touch-off and the
  old dot ladder are refused (texts), prime, the test dot and the landing check allowed; the landing set → allowed; the
  N2 head offset changed → not checked and `Keep` refused; `Keep` refused for a bent setup and a setup without a landing
  of its own; the I4 offset rule (a frame of −1.5 mm with the 1.0 mm tolerance refused, its text; the board-Z limit
  min(2.0, o − 0.5)).
- **`T/dispense/DotTableTest`**: the newest passing optical record per dose of S at S's recipe and paste, any age;
  failed records, other setups, another recipe, another paste, a `wrong-tip` job and the other simulator flag ignored;
  mass per step from the newest confirmed pair.
- **`T/dispense/PlannerMeasuredTipTest`**: a measured 25G (dot table without a one-turn record) holds a 1600-step type
  back as `DOSE_UNKNOWN` with its warning, while a too-close pad of a known dose is still `MAY_BRIDGE` and the
  same-pad pitch uses the 25G's d95 and OD; after a dwell edit every own-dose pad is `DOSE_UNKNOWN`; `checkDoses`
  refuses an unknown dose; the planner refuses without a tip.
- **`T/padprofile/LandingFitTest`**: residual shifts up to ±1.0 mm found within 0.02 mm with 2 of 8 dots missing and one
  foreign blob; no answer with 5 matches; the second-best match ≤ 3 on 1,000 seeded rows; the locator found at 0, 2, 6
  and 8 mm off (the last only with `wide`), its window and the band heights 12, 20, 26 mm; a splash picks the largest
  new blob.
- **`T/padprofile/SuggesterSetupTest`**: the bench setup gives exactly today's results (every §1.9 fixture row, a golden
  file from a4bb6d20d); an unmeasured setup gives `measure-tip` and no `tip` groups; a measured table gives plans from
  its d95 and mass and skips unknown doses; another setup's anchor and its reason; a stand-in's below-gate text; a
  copied plan is `low`; the check JSON of an unmeasured setup parses with `JSON.parse` semantics (no NaN token,
  `minDotMm` null).
- **`T/padprofile/ProfileCheckTest`** (changed): `Tip.g22()` is named `22G bent taper` and every exact text follows;
  the tests that relied on the default tip pass `Tip.g22()` explicitly; new texts: `belowGate` for a setup (`{type} is
  not calibrated for the {S} yet (status: not started; characterized for the 22G bent taper). Boards need pad types that
  are at least characterized: calibrate it for this tip automatically or by hand.`), `measure-tip`, the control's tip
  sentence; with the 25G mounted a resolver built from `pad_types_current` marks the null-key characterized chip-0603
  `STATUS_BELOW_MIN` (the `setupOf` guard).

**API and SIM** (SimMachine + the real ApiServer, as T/api/ApiServerSimTest.java:50-75; `ts-api`):

- **`T/api/TipSetupApiSimTest`**: add with `profileFrom`, add and mount, list, edit (identity 409, correction two-step),
  retire; an OD correction of S makes the plan stale (`the 25G straight changed after planning (OD 0.51 → 0.55 mm)`);
  mount: response, stale list, status `dispense.tip`, the plan stale (`PLAN` text), the needle readiness; a
  refused mount still records the needle change; `PUT /api/config {tip}` refused; mount back: chip-0603's characterized
  version is S's again (no new version); copy from another setup; `GET /api/pad-types` per setup with `others`;
  `wrong-tip` restores the previous version and leaves the dot table; undo.
- **`T/api/MountJobRaceTest`** (latches): a job start and a mount's switch interleaved (`whileNoJob` against
  `PadCalibrationJobs.start`: one waits for the other's monitor, the switch is refused while the job has not ended); a
  `PUT /api/config` with `profile` built before a mount and sent after it gets 409 and `cfg.tip` stays the mounted
  setup; a job between steps when a mount happens ends `stopped` with nothing written.
- **`T/api/PhotoApiSimTest`**: upload (201, served with `ETag`, 304, thumbnail), each refusal, an upload during a run →
  409, two at once → one 409 `busy`; list filters; delete (410 after), restore, main; a result photo cannot be deleted;
  a link to another setup's record → 409 `setup-mismatch`; a bottom-camera picture after a needle change cannot link to
  the record before it; the picture route with the fake camera (`wait`), and its refusals.
- **`T/api/PadCalibrationSimTest`** (added cases): the §14.3 job on a second setup with `SimPaste` per tip (below):
  the 25G writes chip-0603 v(n+1) for the 25G and the 22G's version is untouched; a result photo per dose and the
  reference picture, linked; the auto label `Chip 0603 · 25G straight · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305
  T5`; the label edit; a by-hand save writes a `by-hand` record with its label and photos; `measure: "landing"` finds a
  2.4 mm `landingBiasMm` through the locator and a 0.4 mm one, saves them, and asks (saves nothing) when the setup's
  own landing is 1.5 mm away; `measure: "tip"` fills the dot table; a mount during a job stops it with nothing saved; the
  control's text with `scene.physicalTip` set to another tip.

**What the SIM fakes:**

- **`S/SimTipCamera`** (new, an OpenPnP `ReferenceCamera` looking up, like OpenPnP's SimulatedUpCamera,
  O/machine/reference/camera/SimulatedUpCamera.java:108-162): a session gets it with one more `--xml-set
  ".//camera[@looking='Up']@class=org.openpnp.instancelabs.sim.SimTipCamera"`, as the top camera is installed
  (src/test/web/run-webui-sim-test.sh:57-59@pc; `il_sim_scene.py` exports it as `TIP_CAMERA_XML_SET` next to
  `CAMERA_XML_SETS`, src/test/sim/il_sim_scene.py:52-55). Its frame is 1280 × 960 px at 0.02 mm/px, dark grey (25, 25,
  25); when N2 is in view it draws the physical tip end-on (read in-process from the engine, `DispenseService.get()`,
  D/DispenseService.java:161, as S/SimBoardRenderer.java:204-215 reads the pad types): a grey disc of its OD with a black
  bore of its ID, an amber ring of 2.5 mm for `tapered-plastic`, for `bent` a grey capsule of length `lengthMm ·
  sin(bendDeg)` (6 mm when not recorded) towards the hub, blurred by its height above the focal plane, and the tag `SIM
  bottom camera`. With N2 elsewhere the frame is empty, so a test proves the head went there; two setups give different
  rings, so it proves the picture is of the tip on the head. JUnit uses the fake `TipCamera` instead.
- **`SimPaste` per tip**: the scene's `paste` gains `byTip: {"<setup key>": {mRevMg, footprint: {c, e}, landingBiasMm:
  [dx, dy]}}` and the scene gains `physicalTip` (the key of the tip "on the head"; default: the mounted key), chosen
  in-process as above; `il_sim_scene.py --paste-by-tip 25g-straight:mRevMg=1.6,c=0.6,landing=2.4:-0.4 --physical-tip
  KEY`. The 25G then lays smaller dots than the 22G and lands 2.4 mm off, so measuring and calibrating it give numbers of
  its own, and a test can mount the 22G while the 25G is "on the head".

**Playwright** (`src/test/web/tip-setups.playwright.cjs`, after `pad-profile.playwright.cjs` in `run-webui-sim-test.sh`,
≤ 6 min; the session launched with the tip camera's `--xml-set` and the padcal scene written with `--paste-by-tip`;
`ts-web`):

0. Set-up as `pad-profile.playwright.cjs` step 0.
1. Setup › Dispenser & tip: `#tip-name` `22G bent taper`, `#tip-kv` has `Vieweg DV-5005` and `from the bench tests`,
   `#tip-photo-none` shown; the strip's `#chip-tip` reads `22G bent taper`; at 1024 px the strip is one line.
2. Add a tip from Change tip: preset `25G straight` fills the name, ID 0.25 and OD 0.51; a PNG fixture dropped on the
   drop zone shows `#ta-photo-img`; `#ta-save-mount` twice → `status.dispense.tip.key` `25g-straight`, the toast's Undo
   shown; `GET /api/tip-setups` has two, the 25G with one `setup` photo that loads.
3. The readiness Needle item is `block` with `The 25G straight was put on at`; `#chip-tip` is block; Profile's
   `#pf-h1-sub` says `for the 25G straight`; chip-0603's badge is `not started`; `By tip` shows both columns with
   chip-0603 `characterized` under the 22G; the plan shown is stale; the editor's eyebrow says `for the 25G straight`.
4. Plan the padcal board: `#pt-needs-for` says `for the 25G straight`; `#pt-needs-measure` is shown, and the note that
   the 22G bent taper has every pad type of the board.
5. Needle Z frame card: `#anchor-k` is empty with `not known yet`; frame by Both tips touch and the offset through the
   API (as PW does), the regulator and prime ticks again (they lapsed at the change); `#tip-next` step `Check where it
   lands` → pad-cal title `Check where the 25G straight lands`, start label `Check where it lands`, two presses →
   `#pcal-landing` says `Saved` (the scene's 2.4 mm bias), `#chip-tip` ok, the area used up.
6. Blank wiped (`POST /api/calibration-area/reset`), then `#btn-needs-measure` → title `Measure the 25G straight's
   dots` → start → the readings (SIM balance, typed as §14.4 step 8) → `table#pcal-tipdots` has 3 rows; `#tip-kv` says
   `measured`; the primary is `Calibrate the … pad types`; while the job ran, `#btn-tip-upload` was disabled with
   `Locked while the pad calibration runs`.
7. Calibrate the SW group → the label in its `li` starts `SW_SPST_3.9x2.9mm` and contains `25G straight · Vieweg
   DV-5005`; Edit, change, Save → `GET /api/pad-calibrations` has the new label with `labelAuto` false;
   `figure.pcal-photo img` loaded (`naturalWidth > 0`); `#btn-pcal-picture` twice → one tip picture; a JPEG fixture
   through `#pcal-file` → two.
8. Records › Calibrations: the first row of `#rec-pcals-t` shows the edited label, `SW… · v{n}` and ≥ 2 thumbnails; the
   `25G straight` filter keeps it, `22G bent taper` hides it; its panel shows the photos and `Add a photo…`.
9. Change tip → the 22G row → Mount twice: chip-0603 `characterized` with its old version; the SW type `not started`,
   `characterized` under the 25G in `By tip`; `#anchor-k` prefilled with the 22G's own K and its caption.
10. Wrong tip: with `physicalTip` 25G and the 22G mounted, the landing check asks `is the 22G bent taper on the
    dispenser?` (nothing saved); a calibration's control text names another tip; `Made with another tip…` twice →
    the record shows `wrong tip` and Change tip opens; the run confirm shows `#run-confirm-tip` unticked after the mount.
11. Remove the uploaded photo in `#photo-view` (two presses) → gone, 410 from its URL, the toast's Undo brings it back;
    the result photo has no remove button; `#photo-view` closes with Esc and STOP stays clickable while it is open.
12. No JavaScript errors and no native dialogs (its own checks).

### 22.9 Implementation slices (one build wave after `il/rc1`)

Branch `il/tip-setups` off `il/rc1` once `finish-pr2`'s gate has passed and `il/rc1` holds fixround1, fixround1b,
fixround2 and pc-web. Three builders in parallel worktrees on disjoint files, then the integration slice; merges in the
order of the table; green at every merge: `env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -o -q -T1 -Dmaven.test.skip=true
package`, `mvn -B -o -T1 test`, `src/test/web/run-webui-sim-test.sh`. PR #3, stacked on PR #2.

**Why the three can start together.** `ts-engine`'s first commit (within its first hour) is the contract below as
code: the new classes, fields, enum values and method signatures with bodies that throw
`UnsupportedOperationException`, and the `DispenseDb` method signatures. `ts-api` branches from that commit, so both
compile from the start and `ts-api` rebases on `ts-engine` when it merges. `ts-web` codes against §22.6's JSON and
§22.7's ids with devproxy fixtures (`src/test/web/fixtures/tips/*.json`, as `fixtures/padprofile/`), and runs its
Playwright on the merged engine and API.

| key | merges | owns |
|---|---|---|
| `ts-engine` | 1st | D/DispenseDb (schema 3: seven tables, two views, columns, seed and placeholders, `schema_info` only on an upgrade; composed `currentPadTypes(setup)`, `currentPadType(key, setup)`; the no-arg readers removed; `typeKeyExists`, `padCalibrations(type, setup, limit)`, the old records' setup resolution; photos, links, photo events, labels, flags, mounts); new D/TipSetup, D/TipSetups, D/PhotoStore, D/Photo, D/TipCamera, D/OpenPnpTipCamera, D/TipPictureRequest, D/TipPictureResult, D/NeedleChange, D/MountResult; D/TipSpec (fields, `isOther` through `Tip.setupOf`), D/DispenseConfig (schema 4: stepwise `migrate()`, the `.v3` backup, `parkedTips`, `needleLanding`, the trim's head offset, `MAX_LANDING_MM`, validate), D/DispenseService (the reconcile at start; `getPadTypes`, `padTypeResolver`, `mountedTip`, `recordNeedleChange`, `switchTip`, `undoMount`, `updateConfigAtomically`, synchronized `savePadType(v, forSetup, padVersion)`, `savePadTypeByHand`, `applyPadCalibration` with setup, token, auto label and links, `skipCoupon`, `markWrongTip`, `setXyTrim`, `setLanding`, `keepLanding`, `isNeedleXyChecked`, `headOffsetNow`, token checks in `setNeedleAnchor`/`setNeedleZOffset` and on dose-blocks steps, `takeTipPicture`, `setTipCamera`, `storePhoto`, `storeResultPhotos`, `linkPhotos`; `interlockInputs` with the tip, the landing and no swallowed read; `newPlanner().withTip`; the I4 offset rule and `boardZProblem`), D/DoseRecord, D/DoseRecords (`dotTable`, flagged jobs), D/PadInstances (`inputs(…, Tip)`, `inputs(svc, …)` with `mountedTip()`, `typeInfo` through `setupOf`), D/PadType (`padVersion`, `setupKey`, `standIn`, `ORIGIN_COPIED`), D/PadCalibrationRecord (`setupKey`, `method`, `needleSinceMs`, `validated` for by-hand), D/PadTypeResolver (fingerprint), D/Interlocks (landing rule and `Scope.landingWaiver`, PLAN texts and the setup rule, `checkDoses` with `knows`, I4 texts), D/DispensePlanner (`withTip`, `DOSE_UNKNOWN`), D/DispensePlan (`setupKey`, `setupName`, `setupVersion`, `typePadVersion`), D/PadFlag (`DOSE_UNKNOWN`), D/DoseBlocks (R7), D/DoseBlocksRequest (`forTip`, `needleSinceMs`, explicit spots, purge in a band, `landingCheck`), D/DoseBlocksResult (`BlockPhoto` as JPEG bytes, `newBlobs`), D/PadLadder (`landingLayout`), D/GcodeStreamBuilder (landing + trim, :572-578), D/RunResult (setup), B/BoardArtStore (one line); P/Tip, P/Suggester, P/Suggestion (`MEASURE_TIP`), P/ProfileCheck, P/Texts, P/TypeInfo, P/Decider, P/LadderStats (landing per axis), P/Anchor, P/CheckInputs (no default tip, `moreAnchors`), P/LandingFit (new); S/SimTipCamera (new), S/SimPaste (`byTip`, `physicalTip`, landing bias), S/SimScene (parses them), S/SimBoardRenderer (one line); src/test/sim/il_sim_scene.py (`TIP_CAMERA_XML_SET`, `--paste-by-tip`, `--physical-tip`); the unit tests of §22.8, src/test/resources/tips/*, and the existing tests the 22G's new name and the removed default tip touch |
| `ts-api` | 2nd (rebased on ts-engine) | A/TipSetupEndpoints, A/PhotoEndpoints (new; the upload pipeline and its `Semaphore`); A/ApiServer (two registrations, the 8 MiB photo route), A/DispenseEndpoints (status `tip` and `needle` fields, `PROTECTED_SETTINGS`/`NESTED_SETTINGS`, `putConfig` through `updateConfigAtomically` with `forTip`, `needle/changed`, the tokens on `needle/anchor` and `needle/offset`, `needle/xy-trim`, `needle/landing`), A/PadTypeEndpoints (composed rows per setup, `others`, `nextVersion`, per-setup counts, `forSetup` and `padVersion`, copy, the by-hand record, retire for every setup, `typeKeyExists`), A/PadProfileEndpoints (mounted view, `typeKeyExists`, the suggestion apply with `forSetup`), A/PadProfileViews (tip view, `otherTips`, the memo key), A/PadCalibrationEndpoints (`measure`, `wide`, the preview's band, label, wrong-tip and link routes, list fields and `setup` filter), A/PadCalibrationJob (setup and token at start, `measure` modes, the landing check's locator, purge, row, fit and save or question, tip dots, result photos, labels and links, the landing refusal, unknown sizes and masses, `versionFrom` null for a stand-in, board-location clearance with or without a plan, the needle-change stop), A/PadCalibrationJobs (`whileNoJob`, `needleChanged`), A/Views (setup, photo and pad type views); T/api/TipSetupApiSimTest, PhotoApiSimTest, MountJobRaceTest (new); PadCalibrationSimTest, PadProfileApiSimTest, PadCalibrationJobTest (added cases) |
| `ts-web` | 3rd (its Playwright on the merged engine and API) | W/tipsetup.js, W/tipsetup.css (new); W/setup.js, W/padprofile.js, W/padprofile.css, W/profile.js, W/records.js, W/core.js, W/run.js, W/calibrate.js, W/index.html (two tags and `#chip-tip`); src/test/web/tip-setups.playwright.cjs, src/test/web/fixtures/tips/* (new), two lines in run-webui-sim-test.sh (the tip camera's `--xml-set`, the new test); T/api/ApiServerSimTest (`servesTheUiAndStatus` lists the two new static files, W/DEV_NOTES.md:34-35@pc), T/api/TipSetupUiSimTest (new, as PadProfileUiSimTest) |
| `ts-integration` | 4th | src/test/sim/il_sim_acceptance.py (`tips`: add, mount, frame, prime, landing, measure, calibrate, picture, label, Records, mount back, wrong tip), il_ui_session.py (`--tip-camera`, `--physical-tip`); WEB_API.md (§22.6), DISPENSE_API.md (schema 3, config schema 4, needle changes and mounting, landing, photos), UI_DESIGN.md (§22.7: the strip cell, the card, the change panel, the per-mode pad-cal view, the run confirm tick), REAL_MACHINE_TODO.md (§22.10), W/DEV_NOTES.md (`tipsetup.js`, `IL.tips`, `IL.photos`, the `tip-change` view, `#dlg-tip-add`, `#photo-view`, `.dialog--lg`, gating file choosers); fixes after the merge |

**Contracts:** §22.1's tables, columns, config fields and setup fields; `TipSetups.typesFor` and the composed `PadType`
(`padVersion`, `version`, `setupKey`, `standIn`); `Tip.setupOf`, `Tip.knows`/`dLoMm`/`dHiMm`/`massMg`; the
`DispenseService` methods named above with these signatures: `recordNeedleChange(String why, String operator) →
NeedleChange`, `switchTip(String key, String why, String operator) → MountResult`, `undoMount(String operator) →
MountResult`, `updateConfigAtomically(ConfigEdit edit) → DispenseConfig` (`ConfigEdit`: `DispenseConfig
apply(DispenseConfig live) throws Exception`), `savePadType(PadType v, String forSetup, Integer padVersion) →
PadType`, `savePadTypeByHand(PadType v, String forSetup, String label, List<Long> photoIds, String operator) →
PadCalibrationRecord`, `setXyTrim(double dx, double dy, String note, Long needleChangedAtMs)`, `setLanding(LandingResult
r, boolean accepted, Long needleChangedAtMs)`, `keepLanding(Long needleChangedAtMs)`, `markWrongTip(String jobId, String
operator) → List<PadType>`, `skipCoupon(DoseRecord coupon, String why, String operator, String jobId)`,
`takeTipPicture(TipPictureRequest) → Future<TipPictureResult>`, `storePhoto(PhotoStore.Input) → Photo`,
`linkPhotos(List<Long> photoIds, List<Long> calibrationIds, String role)`, `mountedTip() → Tip`, `isNeedleXyChecked()`;
`PadCalibrationJobs.whileNoJob(Callable<T>) → T`; every route and JSON of §22.6; the ids of §22.7; the scene fields of
§22.8.

### 22.10 What only the real machine can confirm (to REAL_MACHINE_TODO.md, a new section after item 52; renumber if session 4's items land first)

- **T1 The bottom-camera picture at travel height** (N2 Z 31.5): focus, scale and glare of the ring light; does it
  tell the bent 22G from a straight 25G from below? If not, the phone picture is the record and the caption keeps `may
  be soft`. No paste on the bottom camera's window after 10 pictures taken right after runs (a needle carries paste at
  its end); if there is, the task primes at the waste first.
- **T2 Where each tip lands, against the 22G bent taper** (in mm): the straight 25G, the 22G straight taper, and a 45°
  bent 25G with its bend pointed as the 22G's. Within the ±6 mm search, or does a first check need `wide` (±9 mm, a
  taller area)? Record the 22G's `bendToward` on the way.
- **T3 The landing check on the real blank:** the locator found among the blank's texture, the band's 20 mm for a
  first check and 12 mm for a remount, the purge dots of a needle whose landing is known only from the locator, and
  where the prime of a new needle lands at the waste (±6 mm around the waste spot must be waste).
- **T4 The needle frame of each new tip** by Both tips touch, against session 4's side-camera K; the touch-off after
  the landing check (F9's fix) on a tip that was never on.
- **T5 Needle length and travel height:** the end of a straight 12.7 mm tube against the tapered 22G's at travel Z (side
  camera); clearance over feeders and boards; the length difference behind the "up to about 10 mm" used above.
- **T6 Mass per step of the 25G, 27G and 30G at 18 psi:** how far below the 22G's 1.32 µg/step (slip in the auger against
  the tube's resistance, about 1/r^1.8 and its length)? Measure its dots weighs it; a wider tip (21G, 18G) against the
  × 1.5 used for R7 before it is weighed.
- **T7 Pressure for fine tips:** does 18 psi feed a 27G or 30G (the DV-5005 allows 21.7 psi)? Does the 30G clog (0.15 mm
  bore ≈ 6 T5 balls of 25 µm across)?
- **T8 The smallest dot per tip:** the quarter turn's thread rate at the hop with a 25G (§1.5's rule was the 22G's).
- **T9 OD of each tip** (bottom or side camera) → `odSource measured`, which moves the same-pad pitch.
- **T10 Start angle across tip changes:** the ripple peak (ψ ≈ 299°) stays with the screw (the mount keeps the live
  angle, §22.3).
- **T11 Photos in practice:** a phone picture copied to the PC (orientation, HEIC from an iPhone, size), disk use per
  month.
- **T12 A remount of the same 22G model** ("I changed the needle") with its bend pointed as recorded: how far does the
  landing move (0.1–0.4 mm expected for a straight tip, more for a bent one)? Is `Keep where it lands` ever right for a
  straight one?
- **T13 Is this the tip you think it is?** The 1.00 mm frame and 1.0 mm landing thresholds against a deliberately
  wrong tip (25G on, 22G mounted): both asked, and the control's band.
- **T14 A frame close to N1's:** any tip whose |offset| falls below 2.1 mm (I4's offset rule)?

### 22.11 Choices this section makes (defaults; the main thread may reverse any)

1. **Anchors are shared by all setups** (§22.2): the amount a joint needs is the paste's and the pad's; only the dose
   that delivers it is per tip. Reversal: anchors per setup, so a new tip starts from the bench anchor only.
2. **Where the needle lands goes stale at every needle change** and blocks everything but prime, the test dot and the
   landing check until it is checked, or kept (straight tips only): one more step after "I changed the needle" on the
   22G too (D3 read literally; ≈ 1:00 and 12 mm of the area).
3. **The landing is per setup, not the N2 head offset** (§22.3): no swap needs an edit in OpenPnP; the head offset stays
   the 22G's and a change of it in OpenPnP stales every landing. Reversal: per-setup head offsets written to
   machine.xml at a mount (OpenPnP's configuration would then change at every swap).
4. **Bent tips are screwed on with the bend pointing the recorded way** (`bendToward`), so a first landing check
   searches ±6 mm and a remount ±2 mm; `wide` (±9 mm) is the fallback. Reversal: always ±9 mm (a 26 mm band, so a
   taller calibration area).
5. **A needle change is a fact and never refused**; during an engine task it stops the task. Mounting is that fact plus
   a switch that may be refused.
6. **Mount means "this tip is on now"**: the swap is done in the change panel before Mount, so the needle change is
   recorded when it happened.
7. **Pad definitions are shared; dose plans are per tip** (§22.2): a pad edit under one tip changes what every tip
   sees, drops only the mounted tip's calibration (decision 17), and leaves the other tips' plans to the check's
   `calArea` ratio (0.80–1.25). Reversal: a pad edit appends a lowered version for every tip.
8. **A stand-in takes the type's newest pad definition** (the composition), a retired family included.
9. **The tip picture is taken at the balanced travel height only**: safe for any needle length and before its frame
   is known; possibly soft (T1).
10. **Only the seeded setup uses §1's model**; every other setup measures its dots before it gets suggestions.
11. **Result photos cannot be removed** (they are the evidence of a `characterized` version); tip and setup pictures
    can, with 7 days to undo.
12. **The label belongs to the calibration record**, not the pad type version, so editing it never makes a plan stale.
13. **Pad rules, package mappings and the "paste partly" option stay shared** (they describe pads and packages).
14. **No phone route over the LAN**: pictures arrive as files. A later design may add a photo-only route (a one-time
    token shown as a QR code on the result card, 128 random bits, valid 10 min, one picture, bound to the setup and the
    records; a second listener that answers only `GET /upload/{token}` and `POST /api/photos/upload/{token}`, every
    other route staying on loopback).
15. **Undo a mount** for 2 min on the operator's word that the old tip never left the dispenser.

### 22.12 Critique log

Each issue: what was done, or why it was rejected (each checked against the code first: `il/bench-sync-autoreg`
a4bb6d20d, `il/fixround1b` for BI §3.4, `il/pc-web` 197da6550).

#### Engine and data

| issue | outcome |
|---|---|
| B "I changed the needle" inherits the mount's refusals; a 409 leaves the old needle's frame valid | **Fixed** §22.3: `recordNeedleChange` never refuses (verified: today's `markNeedleChanged`, D/DispenseService.java:394-400, never refuses either); a mount is (a) that fact, then (b) `switchTip`, the only part that may be refused, and its 409 carries the recorded change. `pending-coupons` and `confirm` are gone: the change writes skip rows (coupons stay weighable for 8 h, D/PadCalibrationSettings.java:36) and tells a waiting job. A reconciled `unknown` setup can always record a change. MountTest has the case asked for. |
| M `PUT /api/config` writes back a stale snapshot | **Fixed** with the critique's alternative: the patch is applied inside `updateConfigAtomically` against the live config (verified: snapshot at A/DispenseEndpoints.java:481, write at :532); a `profile` patch carries `forTip`. **Not added**: `configGeneration`, because every run's purge reservation and many engine stamps write the config, so a generation would refuse forms for writes that do not concern them. MountRaceTest, MountJobRaceTest. |
| M checks under one lock, writes under another | **Fixed** §22.3 "Locks and tokens": `whileNoJob` + the `busy` flag around the switch; `savePadType` synchronized with `forSetup` (and `padVersion`); the job carries its setup and token on every step and write. The token is `needleChangedAtMs` made strictly increasing, not the newest `tip_mounts.id`: it is written atomically with the change in the config, which is the truth, while a row can be missing after a failed insert. Latch tests in MountRaceTest and MountJobRaceTest. |
| M NaN in `P/Tip`, the planner's own check without the dot table | **Fixed** §22.2 as proposed: `withTip`, `inputs(…, Tip)`, the `Tip.g22()` defaults deleted (the planner refuses without a tip), `knows` + throwing getters, `DOSE_UNKNOWN`, null and `dots: "none"` in views, `checkDoses` with `knows`; "never measured" now reads "not in S's table at the current recipe and paste" (verified: NaN paths at P/DotGeometry.java:79-84, D/PadInstances.java:236-237, P/Suggester.java:812-814, A/Json.java:19). PlannerMeasuredTipTest, SuggesterSetupTest. |
| M the landing check comes after paste is laid and is too narrow | **Fixed** §22.3: the order puts the touch-off after the landing check; until the landing is checked only prime, the test dot and the landing check dispense (the touch-off and the old ladder included); a locator dot first, then the purge and the row in the check's own band; board outlines checked with or without a plan; `check that the needle is on straight` without a fit. **Changed**: the ±15 mm window with 3 × 3 frames. The default area is 40 × 20 mm (D/CalibrationArea.java:17-18), so a ±15 mm window inside it is impossible; bent tips are screwed on with the bend pointing the recorded way (two ends bent the same way cancel most of the offset), which keeps a first check within ±6 mm (a 20 mm band), a remount within ±2 mm, and `wide` (±9 mm, a 26 mm band) as the fallback; T2 measures it. The window lies inside the reserved band, so the "10 mm from coupons and purge spots" becomes the area's own clearances. LandingFitTest (0, 2, 6, 8 mm), InterlocksXyTest. |
| M the trim is relative to one N2 head offset | **Fixed** §22.1, §22.3: where the needle lands is a per-setup `needleLanding` (±15 mm, measured only) under the ±1 mm trim; both record the head offset (`nozzle.getHeadOffsets()`), a change stales them; `Keep` only for a straight setup whose landing a landing check set under the same head offset; the mount response names a difference. InterlocksXyTest. |
| M whole versions per setup split the pad definition | **Fixed** §22.2 as proposed: S's view composes the pad fields of the newest version of any setup with S's dose fields, ordered by `MIN(row_id)` (verified: the resolver's newest-first loop, D/PadTypeResolver.java:161-173); §22.11 item 7 answers the question asked. PadDefinitionSharedTest. |
| M a null `tip_key` passes the other-tip guard | **Fixed** §22.1: `Tip.setupOf` (in P/Tip: P/ imports nothing from D/) everywhere, and the no-arg readers removed so the compiler finds every one (verified: 15 call sites, the same on fixround1, fixround1b and pc-web). ProfileCheckTest case. |
| M nothing stops an identity edit | **Fixed** §22.1, §22.3: dispenser, gauge, form and taper never change once a setup has records (409 `another-needle`); tip model, ID (within 0.03 mm), bend and length are two-press corrections with an event; the setup version is in the fingerprint with its PLAN text. **Rejected**: "an accepted identity edit clears `model`": the edits still accepted are corrections of the same needle, which the bench model still describes. |
| M a picture can be linked to another setup's record | **Fixed** §22.4: links check the setup (409 text), bottom-camera pictures link only within the same needle (`needle_since_ms`), the by-hand save links its evidence jobs' result photos. PhotoApiSimTest. |
| M the reconcile appends a `22G tip` version on every machine | **Fixed** §22.1 (verified: D/TipSpec.java:20 and Gson's every-field write): compare `idMm`, `odMm`, `minDotSteps` only, then refresh. TipSetupsTest from a real schema-3 fixture. |
| M uploads can exhaust the heap | **Fixed** §22.4 as BI §3.4 does (verified on fixround1b: B/BoardImporter.java:191-208): engine idle, `Semaphore(1)` `tryAcquire`, subsampled decode, alpha drawn onto RGB. PhotoStoreTest, PhotoApiSimTest. |
| M the phone path asks for `lanAccess` | **Fixed** §22.4: no LAN route; pictures arrive as files (drop zones). The token route is written down as a later option, §22.11 item 14 (verified: `lanAccess` opens every route, A/ApiServer.java:100-101, :404-410). |
| M the tests miss these defects | **Fixed** §22.8: latch tests, the real schema-3 fixture, the measured-25G planner test and the JSON parse, a pad edit then a remount, the cross-setup link, a needle change under every refusal, the head offset change, parallel uploads, the migration on the v2 column list with a failed `x25` row. |
| m schema 3 steps (a)–(d) | **Fixed** §22.1: `schema_info` only on an upgrade; old `pad_calibrations` resolved through their job's dose records first; §22.0's sentence corrected (verified: `was.copy()`, A/PadTypeEndpoints.java:197); both views dropped and recreated together. |
| m config schema 4 (a)–(d) | **Fixed** §22.1: stepwise `migrate()` (test keeps 3000), the `.v3` backup, the full new-setup entry, the config as the truth with a `reconcile` row. |
| m defaults safe only for finer tips | **Fixed** §22.2: max(1.6 mm, OD + 0.4 mm); R7 × 1.5 above 0.41 mm until weighed; the dot table filters on the paste; the copy goes through `checkDose`. |
| m I4 cannot see a needle-taken height near N1's | **Fixed** §22.3: the offset rule and min(2.0, o − 0.5) for the board Z; the texts use the mounted frame's offset. |
| m the picture's height, STOP and paste | **Fixed** §22.4: Z 31.5 inside N2's safe zone or refused, `refuseIfStoppedSince` after the move and before the store, T1 checks the window. |
| m photo storage details | **Fixed** §22.4: the cap counts removable pictures, unlinked result photos pruned after 30 days, `no-cache` + `ETag`, the temporary file in the month folder and a start sweep, captions ≤ 200, JPEG bytes in `BlockPhoto`. |
| m freed coupons, the landing check's records | **Fixed** §22.3: skip rows at every needle change, `wipe coupon1 before its next use`; the landing check writes no dose record, only a `TIP` event and the landing. |
| m stand-in `versionFrom`, the Profiles row, a swallowed read | **Fixed**: `versionFrom` null for a stand-in (ts-api), the row's counts per setup (§22.6), the setup as its own PLAN rule and a failed read refusing the run (§22.2). |

#### Flows and UI

| issue | outcome |
|---|---|
| B a wrong tip on the head goes unnoticed and is calibrated in | **Fixed** §22.3, §22.7: one way in ("I changed the needle" opens Change tip); the tip where the operator commits (the strip's `#chip-tip`, the run confirm's tick, the pad-cal first line, the plan hero); the frame, the landing and the control compared with the setup's own history before any other cause is named; `Made with another tip…` restores the previous versions and flags the job; `scene.physicalTip` and Playwright step 10. |
| M a bent and a straight tip cannot both stay set up | **Fixed** with option (a): the per-setup landing, plus `bendToward` and the swap step's picture; Keep refused for bent tips. **Rejected** option (b): it needs an OpenPnP edit at every swap. |
| M a new tip gets the 22G's K | **Fixed** §22.1, §22.7: the empty entry, K only from the setup's own anchor, Both tips touch preselected, the lead text (verified: `65.59` hard-coded at W/setup.js:572@pc). |
| M steps missing after a mount | **Fixed** §22.3: the regulator and the prime are steps; the prime and test-dot ticks lapse at a change (verified: only the regulator lapses, W/core.js:607-613@pc); `Add and mount`; each result's primary is the next step. **Changed**: the swap is done in the change panel before Mount, so the change is recorded when the needle actually came off, instead of a step after it. **Rejected**: the landing row inside Measure its dots: a first check uses 20 mm and the measure ladder about 17 mm of the 20 mm area, so one job cannot hold both without a wipe between them. Flow (a): about 31 presses, 3 jobs (≈ 1:00, ≈ 8:00, ≈ 6:30), one wipe and 2 weighings. |
| M file choosers block STOP; a lightbox covers it | **Fixed** §22.7: every chooser button gated with `IL.lockReason`, drop zones, `#photo-view` as `IL.dialog.open` `lg`. |
| M the parked start angle comes back | **Fixed** §22.3: the mount carries the live angle and its time (verified: D/DispenseProfile.java:23-25). MountTest. |
| M pad-type edits, retire and history apply to one tip without saying so | **Fixed** by the composition of the first critique (one pad definition for every tip) rather than one appended version per tip: same visible result, no fan-out writes; the editor's eyebrow and note, `Retire for every tip…`, History's Tip column, filter and same-tip marks. |
| M Edit… turns the mounted tip into another needle | **Fixed** with the first critique: identity fields locked, corrections two-press with the proposed note. |
| M the pad-cal view names the wrong job in the new modes | **Fixed** §22.7: title, first line, before-start sentence, start label and verb, result primary per mode; `#pcal-for` and by hand hidden. |
| M no place to see which pad types each tip has | **Fixed** §22.7: `By tip`, `+2 tips`, the calibrated types in Change tip, the thumbnail and `Change tip…` in `#pf-h1-sub`, the other-tip note in Needs a profile and Run, Records' tip filter and second line. **Changed**: the seventh column is not added (COLS were just fitted at 1024 px). |
| M no picture once the result card closes | **Fixed** §22.4, §22.7: the reference picture linked at the decision, `Add a photo…` in Records, `Open the result` while the job is in memory. |
| M the phone route needs `lanAccess`; HEIC; no drop zone | **Fixed** §22.4, §22.7: files, drop zones, `accept="image/*"`, the HEIC sentence and the hint; the LAN route dropped. |
| M lists and thumbnails not usable from the keyboard | **Fixed** §22.7: radio `fieldset`s, thumbnail and chip buttons with alt text, remove only in `#photo-view`, `data-enter-submits`. |
| m the result card's label block | **Fixed** §22.7 as proposed, and a dirty label stops `Checks ›` once. |
| m `·` inside a type name; old records unlabelled | **Fixed** §22.5: brackets; old records' labels computed when read (nothing written for records the operator never saw). |
| m internal words in texts | **Fixed** with every proposed wording (§22.1 notes, §22.5, §22.7, the readiness text). |
| m empty states | **Fixed** §22.7; the seed's `tip_mounts` row is written by the start reconcile (the DB migration does not know the config). |
| m no undo | **Fixed** §22.3, §22.7: undo a mount for 2 min on the operator's word, Mount gated on unsaved recipe or editor edits (and refused by the engine's `forTip`/`forSetup` anyway), removed pictures kept 7 days with Undo. |
| m seven foot buttons | **Fixed** §22.7: one primary, landing and measure as `#tip-next` steps, photo buttons under the picture, the rest under `More`, retired tips in Change tip. |
| m the soft picture replaces the sharp one | **Fixed** §22.1, §22.4, §22.7: `Use as the tip's picture`, `Add a photo…` first, the `may be soft` caption until T1, the gate's reasons. |
| m presets miss tips on hand | **Fixed** §22.7: DSKIT1's straight tapered and other straight gauges, SA7529's 45° gauges, the 19G footed, `Other…`; presets fill the name. |
| m the shots card's free-text tip | **Fixed** §22.7: prefilled from the mounted tip, read-only. |

### 22.13 As built (2026-10-30)

Built on `il/tip-setups` in the slices of §22.9: `ts-engine`, `ts-api` (`il/ts-api` 00d185402 and its check), `ts-web`
(the web UI, `tip-setups.playwright.cjs`, and the operator walk's fixes) and `ts-integration` (`il/rc1` merged again up
to 6a8ae31a9, fix round 2's web UI included, the `tips` SIM acceptance, `il_ui_session.py --physical-tip` /
`--paste-by-tip`, the docs: `WEB_API.md` §3.6, `DISPENSE_API.md` §9, `UI_DESIGN.md` §8.6, `REAL_MACHINE_TODO.md` items
55–68, `DEV_NOTES.md` §5 › Tip setups). Gates of §22.9, all green. On ec82c8019: the full Maven suite 1259/1259 with
checkstyle; one SIM session of `run-webui-sim-test.sh` with webui-sim 244/244, board-import 62/62, pad-profile 71/71 and
tip-setups 68/68; `run-boardview-test.sh` 67/67. `il_sim_acceptance.py all` ran on 49d04dbab, which differs only by
`il/rc1`'s last commit (the touch-off note's no-break spaces in `calibrate.js` and its browser check, which the
acceptance does not load): load, stop (web and Swing, during a hold and during a run), board, register, import, padcal
(main 11/11, nano 6/6, controls 9/9) and tips (13/13) passed, the live config untouched, 56 min.

**Where the code differs from §22** (the code is right; each was checked against the code at this head):

*Data, config and engine*

- **Resolved after the engine slice's handoff:** the seed is named `22G bent taper` everywhere (`Tip.SEED_NAME`,
  `TipSetup.seed`, `TipSpec`'s default; the reconcile writes it into an old file's `22G tip` without a new version), the
  config is schema 4 (§22.1), and the replaced signatures are gone (no `@Deprecated` adapters: the compiler found every
  reader, as §22.2 wanted).
- Schema 4 changes no value: a file from before tip setups is recognised by its missing `parkedTips` key
  (`DispenseConfig.isBeforeTipSetups`), which also catches a schema-4 file an older jar saved over; that triggers the
  `.v3-<stamp>.bak` at the first save and the reconcile. The 3 → 4 landing rule runs at the first start with tip
  setups (`tip_mounts` empty).
- The upload semaphore lives in the engine (`PhotoStore.PIPELINE`, a `Semaphore(1)` taken without waiting), and the
  route stores through `svc.storePhoto` (which writes the `TIP` event), not in `A/PhotoEndpoints`.
- The landing check: its locator step has no purge (prime comes first, §22.3's order) and reserves the whole band; the
  row's targets are the band's spots minus C (the engine already subtracts E + trim); in a remount's 12 mm band the
  purge (3 mm) and the row (6 mm) go on opposite sides of the locator, 3 mm each, and a side with less refuses (`wipe
  the blank and check again`); the 2 × 2 frames meet at their edges, so each step adds a frame centred on any spot no
  band frame holds 2 mm inside (4–6 frames per step instead of 4); its outcome is `measured` | `question` |
  `not-found`; `DispenseService.topCameraFovMm()` lays out the frames.
- "Spaced for max(1.6 mm, OD + 0.4 mm)" is the pitch: an unknown dot size uses d95 = that − 0.3 mm (Measure its dots in
  the SIM: quarter, half and one turn, 64 + 28 + 61 dots in 18.5 mm of the default 20 mm area, against §22's ≈ 17 mm).
  Measure its dots weighs the profile's dose on both coupons (§22 says only "two coupons to weigh"), which confirms the
  mass per step.
- **The dot table pools a job's camera records of a dose** (ts-integration, found by the `tips` acceptance at the
  machine's statistics floors): §22.2 reads "the newest passing optical record" per dose, but a ladder block is sized
  for the hops of the weigh dots on the coupons (§7.4), so the weighed dose's block fails its thread count alone (27 of
  60 moves) and a tip whose smallest dot is the weighed half turn stayed "not measured" after Measure its dots said
  both doses passed. An optical record that fails alone now counts when its job's camera records of the same dose pass
  together, with their pooled sizes (`DoseRecords.dotTable(…, settings)`, `DotTableTest`). The SIM tests' shortened
  settings (60 → 10 hops) had hidden it.
- **K and a needle change** (`il/rc1`'s review, merged after §22 was designed): the engine refuses the needle frame
  from K after every needle change and after a failed Z check of this home (`needleFrameFromKRefusal`, status
  `dispense.needle.kRefusal`): K is a sum of raw heights and holds only for the needle and the home it was measured
  with. §22.3's step 1 "or From K with the setup's own parked K" is therefore gone: a tip mounted again is framed with
  both tips touching, like a tip that was never on. The needle card still prefills the tip's own K with its caption, and
  locks From K with the reason. That also closes the UI check's design risk of "From K with another tip physically on"
  for every tip that was on before; a tip that was never on has no K of its own (the card leaves K empty and
  preselects Both tips touch).
- `LadderStats.control(…, tip)` passes with `first measurement of this tip` when a setup without the bench model has
  no earlier record (§22.2 names the case; passing checks carried no text before).
- A needle change during the tip picture stops the task through the STOP path (`recordNeedleChange` aborts any running
  task), so no photo is stored; the token check before the store stays as the backstop. `SimTipCamera` draws N2 at its
  planned location (`Nozzle.getLocation()`; the SIM test machine's momentary location lags the move), and draws any
  other nozzle in view with the stock simulated camera.
- `applyPadCalibration` refuses `tip-changed` / `needle-changed` only for a record that writes a version; a stopped or
  failed job's record keeps the setup and token it names, as history (labels and links use its setup).
  `DispensePlan.copyBoardFieldsTo` copies the setup fields (run subsets kept failing `the tip changed after planning`),
  and the other-tip texts name a null tip key through `Tip.setupOf`.
- `otherTips` lists only setups that have every typed pad type of the plan at the gate (`covers == of`): a pad without a
  pad type is no tip's (the web's note counts those packages, below).

*API*

- `forSetup` on every pad-type write, `needleChangedAtMs` on the needle routes and `forTip` with a profile patch are
  required (400 when missing), as §22 says; a pad edit without `padVersion` uses the version read now (lenient).
- A mount of the mounted setup answers its own shape (`{…, same: true, needleChange, stale, next, undoUntilMs: null,
  text: "A new {S} at HH:MM. Next: …"}`), not a `MountResult`; the mount response adds `mountedName`, `fromName` and
  `warnings`; `POST /api/needle/changed` answers `needleChanged` and `next` too; the picture route's 202 adds
  `setupKey`.
- Codes: a missing photo file is 410 `file-missing`, a restore of a gone file 410 `gone`, removing or choosing a result
  photo 409 `result-photo`, a refused undo 409 `too-late`.
- A group of the job view keeps its own `label` (the result card's heading) and carries its record's as
  `calibrationLabel` / `calibrationLabelAuto`; a calibration whose control fails writes a `failed` record, and the
  result card finds it by its job id for `Made with another tip…`.
- An automatic calibration decided from an earlier job's fresh evidence lays no dots and links no result photo of its
  own (the card says so: `No result photo: this calibration laid no dots of its own (it used the dots measured
  before).`); §22.4 may expect the evidence's photos.

*Web UI* (`UI_DESIGN.md` §8.6)

- The strip's Tip cell: the words after the gauge are its level-2 long part, so at 1440 px and below it reads `22G` /
  `25G` (§22.7 hid only `· frame and landing to do`); at 1024 px `Start angle` shortens to `Angle` so the board-run
  verdict fits beside the new cell, and after a STOP at 1024 px (rc1's widest state, with the time already dropped) the
  cell goes while it is ok (a fifth strip tier, `cells--t5`), so the verdict is never cut.
- Setup's bring-up step `Dispenser & tip` shows the card's first open step after a needle change (frame, offset,
  regulator, prime, landing) instead of `dots not measured · Measure its dots…`, which §22.3's order puts after them;
  `Needle Z frame` reads ok when only the landing is open.
- Measure its dots' result: `table#pcal-tipdots` carries each turn's pass mark in its row (no Pass column, the section
  titled `Dot sizes of the tip (mm)`) so it fits the side panel at 1920 px; a measure that leaves the tip unmeasured is
  amber with `Edit the {S}…` (set its smallest dot) instead of a green `Measured`.
- Needs a profile's other-tip note also counts the packages without a pad type (`The 22G bent taper has all 5 pad
  types of this board; 3 packages have no pad type yet.`), which no tip change gives one; Run's Pad types item says the
  same, also when Refuse flagged pads is off.
- The run confirm's tip tick has a row of its own; the stale plan reads `stale: tip, pad types changed`; the landing
  and frame I4 refusals are Needle items on Run › Checks (never Surface); Profile › By tip shortens only automatic
  labels; the landing check's `Blank wiped` fix offers itself for the area refusal; Profile's plan list gives a long
  pad-type name its own row.
- Left open (minor, `UI_DESIGN.md` §8.6): the plan's `for the {S}` chip clipped at 1440 px, the Add dialog's longest
  `Start its dispense profile from` option cut, the landing check's start card not showing the band's problem before
  the ticks, Calibrate's `Before it runs` not listing where the needle lands, `Saved` said twice on the landing result,
  `Save it: the {S} is on` in one press, Change tip keeping the previous selection after `Made with another tip…`, the
  run confirm's tip tick below the fold at 1024 × 768, the All-tips history scrolling sideways at 1024 px, and keyboard
  order after `Change tip…` (focus stays on the button).

*Tests and the SIM*

- `PhotoStoreTest` checks the bounded decode by the decoded raster (6000 × 4000 decodes to 3000 × 2000; the subsample
  factor is max(1, floor(long side / 2048)), so 4000 × 3000 is stored at 2048 × 1536) instead of a peak-heap bound,
  which is flaky under GC; the API tests do not exercise the photo caps (`too-many`, `disk`), the engine's
  `PhotoStoreTest` does. `MountJobRaceTest` reuses `PadCalibrationJobTest`'s fixture by composition.
- `tip-setups.playwright.cjs` sets the SIM 25G's smallest dot to a half turn and the short SIM ladder
  (`ladderDotsPerPhase` 6, `minLadderDots` 6, `minHops` 10, `weighTargetMg` 15): with this SIM paste the quarter-turn
  dots are too small to find. Its step 10 flags step 7's record from Records (the failed control's record is flagged by
  the `tips` acceptance instead), opens the run confirm from script (the plan is blocked there), and checks the
  no-Remove on the first record with a result photo; it wipes the SIM coupons at its set-up; it takes about 17 min
  against §22.8's ≈ 6 min (120 screenshots, three landing checks, a measure and two calibrations; `TS_SHOTS=0` skips the
  screenshots). Since the rc1 merge it frames the 22G with both tips after the mount back and checks From K is locked.
- Outside its row, `ts-web` adapted the SIM set-ups the new contracts broke: `SimTipCamera` (any nozzle but N2 drawn by
  the stock simulated camera: homing failed otherwise), `il_sim_acceptance.py`'s set-up (needle tokens, `forSetup`),
  `il_ui_session.py --tip-camera`, and webui-sim and pad-profile (the tokens, `forTip` / `forSetup`, the tip tick).
- `il_sim_acceptance.py tips` runs at the machine's statistics floors (the default `padCalibration`), with the SIM 25G
  added at a half-turn smallest dot, on the padcal scene's blank grown to 50 mm (a first landing check needs a 20 mm
  band). `--api-ports K` keeps a run's sessions on fewer ports (1: all on `--api-port`).
- The SIM paste (`SimPaste`) lays the dots of the tip on the head: its `byTip` entry over the scene's paste, or the
  scene's paste itself for a tip without an entry (the seed in the tests). The `tips` acceptance and the browser test
  run the wrong tip as the 22G mounted with the 25G on the head (the landing question, then the control).

*Left for the engine* (open, `REAL_MACHINE_TODO.md` › Software limits): the touch-off judges every tip by the 22G's
model; an Undo of a mount puts the old recipe back (an edit within the 2 min is lost); the landing check's preview does
not check I4 first; `onlyAtTheWaste` still lets scale shots pass at the waste.
