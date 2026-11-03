# padprofile fixtures

The paste openings of three KiCad boards, for the paste model tests
(`src/test/java/org/openpnp/instancelabs/padprofile/`, `FixtureSuggestionsTest` and others;
PROFILE_CALIBRATION_DESIGN.md §1.9, §14.1). Only pad data was taken from the designs: each part's
reference, footprint name, side and position, and each paste opening's name, centre, rotation, size and
corner roundness. Nothing else of the designs (copper, silkscreen, outlines, logos, texts, 3D models) is
in these files.

| file | source (read only) | commit | licence |
|---|---|---|---|
| `ftp.pads.json` | `~/lumenpnp/pnp/pcb/ftp/ftp.kicad_pcb` (repository `opulo-inc/lumenpnp`, `pnp/pcb/ftp`) | 5ec657b | CERN-OHL-W v2 (item 1 of the repository's LICENSE) |
| `mobo.pads.json` | `~/openpnp-sources/feeder/pcb/mobo/mobo.kicad_pcb` (repository `opulo-inc/feeder`, `pcb/mobo`) | 05843dd | CERN-OHL-W v2 (item 1 of the repository's LICENSE) |
| `nano.pads.json` | `~/lumenpnp-handoff/nano/source/kicad/NanoV3.3.kicad_pcb` (repository `theinstancelabs/lumenpnp-handoff`, `nano/source/kicad`: a KiCad port of the Arduino Nano V3.3) | 6a78577 | CC BY-SA 4.0 (`nano/source/kicad/LICENSE.md`) |

Attribution and notices:

- `ftp.pads.json` and `mobo.pads.json` are derived from hardware designs that are "(c) by Opulo" and
  licensed under the CERN Open Hardware Licence Version 2 - Weakly Reciprocal
  (https://cern.ch/cern-ohl). The source repositories require this notice to be included with derivative
  works: "LumenPnP is (c) by Opulo". Opulo's names and marks are not used in the fixtures themselves; the
  footprint names and references are the designs' own.
- `nano.pads.json` is derived from the Arduino Nano V3.3 design (Arduino, https://store.arduino.cc), as
  ported to KiCad in the source repository, licensed under Creative Commons Attribution-ShareAlike 4.0
  International (https://creativecommons.org/licenses/by-sa/4.0/). This derivative stays under CC BY-SA 4.0.
- Changes: only the pad data listed above was extracted, by `extract_pads.py`; positions are moved to
  the board frame below.

`padcal.kicad_pcb` is not taken from a design: it is PROFILE_CALIBRATION_DESIGN.md §14.3's test board written as a
KiCad 9 file for the browser test (`src/test/web/pad-profile.playwright.cjs`) and the sim acceptance. 46 × 30 mm
(Edge.Cuts from (100, 100) to (146, 130) in KiCad's frame), three 1 mm fiducials (FID1 2.5, 27.5; FID2 43.5, 27.5;
FID3 43.5, 2.5 in the board frame), all parts on top: R1–R4 `R_0603_1608Metric` (0.80 × 0.95 mm, 50 % round, at
±0.825 mm), T1 `TestChip_A` (the same pads), J1–J2 `SW_SPST_3.9x2.9mm` (0.864 × 1.829 mm, 18 % round, at ±2.0 mm),
Q1–Q3 `Test_Strip_0.62x1.50` (0.62 × 1.50 mm at ±1.0 mm), U1 `WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm` (eight 0.55 ×
0.25 mm pins 0.5 mm apart, the 0.9 × 1.6 mm exposed pad), at the positions of `PadCalibrationSimTest.writeBoard` (its
S1–S3 are not on this board). With chip-0603 characterized its upload preview needs a profile for J, Q and T (new,
new, use) and the 22G tip cannot dispense U's two groups (`PadProfileUiSimTest`).

## Format

One JSON object per board: `board`, `source`, `licence`, `generator`, `kicadVersion`, `boardMm`
(Edge.Cuts box), `parts` (one line each: `ref`, `packageId`, `side`, `x`, `y`, `rotationDeg`, `dnp`) and
`pads` (one line each: `padId`, `ref`, `packageId`, `padName`, `side`, `x`, `y`, `rotationDeg`, `wMm`,
`hMm`, `roundness`, `localX`, `localY`).

- Board frame: mm from the bottom-left corner of the Edge.Cuts box, +Y up (BOARD_IMPORT_DESIGN.md §2.6);
  bottom-side parts use the same transform (KiCad stores their pads mirrored already). `localX`/`localY`
  are the opening's centre in its part's frame (+Y up), `rotationDeg` the opening's rotation on the
  board (counter-clockwise).
- Openings follow BOARD_IMPORT_DESIGN.md §2.8: `smd`/`connect` pads on the side's paste layer and filled
  paste polygons, rectangles and circles inside a footprint; paste margins pad > footprint > board;
  roundness is OpenPnP's corner radius in % of half the short side; repeated pad names get `~2`, `~3`,
  paste windows without a name `<host pad>~k` (mobo U2's nine windows `33~1`…`33~9`, nano IC3's exposed
  pad `33~1`, mobo J5's windows `6~1` and `7~1`). Fiducials have no paste and are left out.
- `packageId` is the footprint name without its library; a second footprint of the same name with
  different openings is `name~2` (mobo J1, the bottom-side pin header).

Counts: ftp 80 parts, 160 openings (top); mobo 56 parts, 271 openings (261 top, 10 bottom); nano 23
parts, 125 openings (55 top, 70 bottom).

## Regenerating

`extract_pads.py` uses the Python standard library only and writes nothing but its standard output.
Run it with `-I` from any directory (nothing next to the board file is imported):

```sh
R=src/test/resources/padprofile
python3 -I $R/extract_pads.py ~/lumenpnp/pnp/pcb/ftp/ftp.kicad_pcb ftp \
  "ftp.kicad_pcb, pnp/pcb/ftp @ 5ec657b (README.md)" "CERN-OHL-W v2 (README.md)" > $R/ftp.pads.json
python3 -I $R/extract_pads.py ~/openpnp-sources/feeder/pcb/mobo/mobo.kicad_pcb mobo \
  "mobo.kicad_pcb, pcb/mobo @ 05843dd (README.md)" "CERN-OHL-W v2 (README.md)" > $R/mobo.pads.json
python3 -I $R/extract_pads.py ~/lumenpnp-handoff/nano/source/kicad/NanoV3.3.kicad_pcb nano \
  "NanoV3.3.kicad_pcb, Arduino Nano V3.3 KiCad port, nano/source/kicad @ 6a78577 (README.md)" \
  "CC BY-SA 4.0 (README.md)" > $R/nano.pads.json
```

The openings were checked against an independent survey of the same files (every package's openings
equal within 1e-6 mm and 0.001 % roundness), and no two openings of different parts on one side overlap.
