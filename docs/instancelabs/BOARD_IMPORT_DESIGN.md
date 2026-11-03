# KiCad board upload, board render, path preview and pad preview: design spec

Status: design for implementation, **revision 2**. It answers two critiques (data-correctness, ux-render-test); §12
logs what was done with each point. Branch `il/bench-sync-autoreg` (based on `il/bench-sync` 5e14c13de), same PR as
the registration work. Brief: `/home/lumen/.t3/projects/openpnp-revamp/tasks/board-import-brief.md`; hard rules:
`tasks/autoreg-brief.md` ("Hard rules"). **Simulation only** while this is built: no serial port, no `/dev/video*`,
no real-mode OpenPnP. Nothing in this design moves the machine.

The parallel design for registration, probe and home is `docs/instancelabs/AUTO_REGISTER_DESIGN.md` (cited as `AR`;
line numbers of its revision 2, 2,604 lines, including its §16 main-thread decisions). §10 lists the files both
designs change, what this design asks of AR, and a joint merge order.

Path abbreviations (the same as AR):

- `D/` = `src/main/java/org/openpnp/instancelabs/dispense/`
- `A/` = `src/main/java/org/openpnp/instancelabs/api/`
- `O/` = `src/main/java/org/openpnp/` (OpenPnP itself)
- `W/` = `src/main/resources/org/openpnp/instancelabs/web/`
- `T/` = `src/test/java/org/openpnp/instancelabs/`
- `K/` = `src/main/java/org/openpnp/instancelabs/kicad/` (**new**: parser, pure Java)
- `B/` = `src/main/java/org/openpnp/instancelabs/board/` (**new**: import, art, storage)
- `F/` = `src/test/resources/kicad/` (**new**: fixtures)
- `PW` = `src/test/web/webui-sim.playwright.cjs`
- Fixtures (sources, read only): `ftp` = `~/lumenpnp/pnp/pcb/ftp/ftp.kicad_pcb`, `secondary` =
  `~/lumenpnp/pnp/pcb/secondary-fid-pcb/secondary-fid-pcb.kicad_pcb`, `mobo` =
  `~/openpnp-sources/feeder/pcb/mobo/mobo.kicad_pcb`, `nano` = `~/lumenpnp-handoff/nano/source/kicad/NanoV3.3.kicad_pcb`,
  `pi10` = `~/openpnp-sources/lumenpnp/src/PCB/pi-interface/pi-interface.kicad_pcb` (KiCad 10), `pi7` =
  `~/lumenpnp/pnp/pcb/pi-interface/pi-interface.kicad_pcb` (KiCad 7), `ftp0402` =
  `~/openpnp-sources/lumenpnp/src/PCB/ftp/ftp0402/ftp0402.kicad_pcb` (KiCad 9), `nanoPaste` = `NanoV3.3-F_Paste.gbr`
  inside `~/lumenpnp-handoff/nano/source/order/arduino-nano-gerbers.zip` (KiCad 10.0.5 export), `ftp.board.xml` =
  `~/Desktop/ftp.board.xml`.

Every claim about existing code carries `file:line`. Numbers about the fixtures come from throw-away survey scripts
(python `-I`, no repository code) run on the files above. The tests in §6 assert them, and an **independent oracle**
(KiCad's own paste Gerber of nano, §6.2) checks the aperture rules, so the expected numbers do not rest only on
scripts that share the code's assumptions.

---

## 0. What gets built (one paragraph)

Four parts, none of which moves the machine:

1. **A KiCad parser** in Java with no new dependency (§2): a defensive S-expression reader with limits, a model of
   the `.kicad_pcb` file (KiCad 5 to 10), the board-frame rule, the paste-aperture rules, the classification of
   footprints, and one cleaning function for every string that reaches OpenPnP.
2. **An import** (§3): the browser sends the raw file and gets a **preview** (what was found, warnings in order, the
   board drawing, per-package pad-type resolution, the exact ids that a save would use). The operator names it and
   **saves** by sending the same bytes again (stateless). Saving is one transaction on Swing's event thread: new
   packages and parts, an OpenPnP board file, the board drawing and a copy of the upload under
   `<configDir>/instancelabs/boards/<slug>/`, the board added to OpenPnP's list, and packages.xml, parts.xml and
   boards.xml written at once. The preview can instead **attach** the drawing to an existing board file whose
   placements match (ftp.board.xml), changing nothing in OpenPnP.
3. **Board art** (§4): a compact JSON drawing of the board's top side in board millimetres, made at import, found
   by the board's **geometry digest** (not its path), with a fallback for every other board. The plan records the
   art id **when it is made**; the art is served with an immutable cache header; the plan carries the board→machine
   affine (2×3, `AffineTransform.getMatrix` order, as AR:531) and per-dot times.
4. **The render** (§5): `boardview.js` draws the art in its own SVG under today's job overlay, in one physical board
   palette with a Full / Quiet / Off picture control, overlay marks with a two-tone casing, dots at their real size,
   a **path preview**, a **pad inspector**, the **upload dialog**, a thumbnail row and origin tools in the Board step,
   and the art in the **Records** map.

---

## 1. Decisions from the brief, and how this design honours them

| Brief decision | Where | Notes |
|---|---|---|
| Run › Change board gets "Upload KiCad board" (picker + drag and drop, `.kicad_pcb`); preview; name; save | §3, §5.8 | Change board opens the Board step (W/run.js:451); the button is mounted there by `boardflow.js` (§5.1). |
| Saved boards appear in the board list, work with Register board, OpenPnP's job can load them | §3.6–3.8 | Ordinary `.board.xml` files with parts in parts.xml. |
| Server-side Java parser, no new dependencies, defensive reader (size, depth caps; no paths from the file used), KiCad 6–9, KiCad 5 best effort, unknown tokens ignored | §2.2–2.5 | **Extended to KiCad 10** (`20260206`): the user's own KiCad is 10.0.5 (nanoPaste header), and Opulo's upstream files are already KiCad 10 (pi10:2-4). KiCad 7 has a real fixture (pi7, `20221018`). |
| Board frame = bottom-left of the Edge.Cuts box, +Y up; no Edge.Cuts → footprint box + warning | §2.6 | ftp gives all 83 placements of ftp.board.xml within 3.6e-5 mm except the hand-moved FID1 (0.3136 mm). That FID1 is **not cosmetic**: it explains the bench affine's 0.994 Y scale (§2.6, OQ4). The box uses exact arc extents (mobo: 0.22 mm otherwise). |
| Top-side footprints with paste pads → Placement; fiducials → Fiducial; DNP → disabled and listed; bottom → skipped and listed; no paste pads → art only | §2.7 | Placements follow the brief. The **art and the probe keep-outs** also take the holes, top copper and mask openings of bottom-side through-hole parts (nano J1/J2: 30 drilled pads). Open points: DNP pads still get paste (OQ1), the fiducial name rule (OQ2), pin-in-paste (OQ9). |
| Package id = footprint name without library; part id = `<footprint>-<value>`; package footprint = paste apertures (pad > footprint > board margins; shapes → roundness; trapezoid/custom → bbox + warning); pad rotation relative to the footprint | §2.8–2.9, §3.5 | roundness = 200 × `roundrect_rratio` (OpenPnP's corner arc is a diameter, O/model/Footprint.java:324-337; D/DotMath.java:9-12), not ×100 as OpenPnP's KicadModImporter does (O/gui/importer/KicadModImporter.java:136-143). Checked against KiCad's own paste Gerber (§6.2). Ids are cleaned (§2.2) and allocated without collisions (§3.5). Fiducial packages keep their **copper** pad. |
| Existing package reused if its footprint matches ±0.01 mm, else never changed: suffixed id + warning | §3.5 | With the live config, ftp's three packages exist **without pads** (`~/.openpnp2/packages.xml:83-102`), so an ftp import always makes `~2` packages and parts (OQ3). For ftp.board.xml itself, §3.10 **attaches** the drawing without any new package. |
| Preview shows per package whether a pad type resolves; unresolved → Profile › New pad type | §3.9, §5.8 | Uses the existing `PadTypeResolver` with an explain variant (§4.5). The links become actions after the save. |
| Storage `instancelabs/boards/<slug>/`: `<slug>.board.xml`, `<slug>.art.json`, copy of the `.kicad_pcb` | §3.7 | Kept. Added: `instancelabs/boards/index.json` (geometry digest → art) and `<slug>/history/` (what a replace displaced, so older plans and Records keep their drawing). |
| Added to OpenPnP's board list the way `planBoardFile` does | §3.6 | `planBoardFile` only adds to the in-memory list (D/DispenseService.java:574-579 → O/model/Configuration.java:872-888); nothing persists it until OpenPnP's Save (Configuration.java:593-637). This design writes packages.xml, parts.xml and boards.xml at once (OQ5). |
| Saving over a name asks first; the existing board fingerprint makes old plans stale | §3.7 | The fingerprint covers placements only, not parts, packages or pads (D/DispenseService.java:408-421): extended (OQ6). |
| Board art made at import, board frame, outline/cut-outs (arcs flattened), drills, F.Cu, F.Mask openings, F.SilkS, references + body outlines, small; fallback for boards without KiCad art | §4.1–4.3 | Adds exposed copper as its own path (no clip path), a tiny thumbnail block, the board's declared mask/silk colours, the origin kind and the fiducials. |
| Art served separately (cacheable); the plan carries the 2×3 board→machine affine and an art reference | §4.4–4.5 | The reference is fixed when the plan is made. Affine order = AR's `BoardRegistration.affine` (AR:531). AR's sim scene uses row-major 2×3 (AR:1771); the plan does not. |
| Render: realistic top view in machine coordinates (art transformed by the plan's affine), light/dark, layer toggles, job overlay, built once per plan, polls toggle classes, smooth at a few thousand elements | §5.2–5.5 | One physical palette for both themes (only the stage follows the theme); "layer toggles" are one Full / Quiet / Off control (§5.3); a performance gate (§6.6). |
| Path preview: play/pause/scrub, needle marker, dots appear, elapsed time from the plan's estimate, 1×/4×/16×, pad, turn, Z; no motion | §5.6 | Per-dot times come from the same program estimate (§4.5). |
| Pad preview: click a pad or part → inspector; hover tooltip | §5.7 | The camera snapshot that a pad click opens today (W/run.js:487-488) moves to a button in the inspector. |
| Used in: Run hero, upload preview, Change board thumbnails, Records map, Register board flow | §5.8–5.11 | |
| Out of scope: Gerber/Eagle/Altium, bottom dispensing, panels, pick-and-place | – | Respected. (A Gerber is read only by a **test**, as an oracle.) |

---

## 2. KiCad parser (`K/`, pure Java, no OpenPnP types)

### 2.1 Classes

| Class | Role |
|---|---|
| `K/SExpr.java` | One list node: `head` (first atom), `items` (atoms as `String`, lists as `SExpr`), `line`. Helpers below. |
| `K/SExprReader.java` | Bytes → `SExpr` tree under `Limits` (§2.2). |
| `K/SExprException.java` | `line`, `column`, `kind` (`syntax`, `limit`, `not-kicad`); the message is the operator text. |
| `K/Deadline.java` | One wall-clock budget for a whole preview or save (§2.2), with an injectable clock for tests. |
| `K/KText.java` | `clean(String s, int maxCodePoints)` and `cleanId(String s, String ifEmpty)`: the one cleaning function for every string that reaches OpenPnP or the art (§2.2). |
| `K/KicadPcb.java` | The model (§2.3); data classes nested in it. |
| `K/KicadParser.java` | `SExpr` → `KicadPcb`, all KiCad 5–10 forms (§2.4–2.5). Never fatal on an unknown token. |
| `K/KGeom.java` | Arc from 3 points / KiCad 5 form, exact arc extents, flattening with a chord error and a segment cap, Bézier flattening (≤ 64 segments), segment chaining into loops through a hash grid, polygon area, point in polygon, iterative Douglas–Peucker, minimum-area enclosing rectangle of a convex polygon. |
| `K/BoardFrame.java` | The board-frame rule (§2.6): outline loop, cut-outs, `minX`, `maxY`, size, `origin` kind, `toBoard(x, y)`, `localToBoard(fp, u, v)`. |
| `K/PasteRules.java` | Paste apertures of one footprint (§2.8). |
| `K/ImportRules.java` | `ImportProposal propose(KicadPcb pcb, BoardFrame frame, Deadline d)`: classification (§2.7), apertures, pad names, package signatures and variants, placements (§2.9). |
| `K/ImportProposal.java` | The proposal (§2.9), no OpenPnP objects, **no final package or part ids** (those come from §3.5). |
| `K/ImportWarning.java` | `{String code; String level ("info" or "warn"); String group; List<String> refs; String text}` (§2.10). |

`SExpr` helpers: `SExpr child(String head)`, `List<SExpr> children(String head)`, `String atom(int i)` (null if
missing), `double num(int i, double def)` (non-finite or unparsable → `def`), `boolean flag(String bare)` (a bare
atom such as `locked`, `hide`), `boolean yes(String head)` (`(hide yes)` or the bare atom), `String str(String head)`
(first atom of a child).

### 2.2 S-expression reader, limits and string cleaning

Grammar (KiCad's own, tokenised by hand):

```
expr   := '(' item* ')'                    the first item of a list is its head
item   := expr | atom | string
atom   := one or more chars except whitespace, '(', ')', '"'    numbers, symbols, F.Cu, *.Cu, F&B.Cu, ${REFERENCE}
string := '"' ( char | '\' esc )* '"'      esc: " \ n r t → the char; any other escaped char is kept as is
```

- Whitespace is space, tab, CR, LF (nano uses CRLF, `NanoV3.3.kicad_pcb:1`). A UTF-8 BOM is skipped. Bytes are decoded
  as UTF-8 with replacement characters.
- The first token must be `(` followed by `kicad_pcb`. Otherwise the reader throws `not-kicad` with a specific text:
  `footprint`/`module` root → a footprint library file; `kicad_sch` → a schematic; anything else → not KiCad (§3.2).
- **Skipped subtrees** (read to their closing parenthesis without building nodes): `model` (3-D model paths, e.g. an
  absolute `/Users/…` path at pi10:1690), `embedded_files`, `render_cache`, `image`, `group`, `generated`. Their atoms
  are scanned, not stored: the one-atom limit does not apply inside them (a base64 font can exceed 1 MiB); the depth,
  size and time limits do.
- **Nothing from the file is ever used as a path**: `model`, `(file …)` and embedded files are skipped, and the slug
  comes from the operator's name (§3.7).

| Limit | Value | When exceeded |
|---|---|---|
| Upload size | 12 MiB (12,582,912 bytes) | 413 before reading (§3.3) |
| Nesting depth | 64 | `limit`: "nested deeper than 64 levels at line L" |
| Items (lists + atoms) | 1,500,000 (≈ 7× mobo's 204,651; ftp 115,352, nano 160,116; all nest 6 deep) | `limit`: "more than 1,500,000 items" |
| One atom or string | 1 MiB | `limit`: "a value longer than 1 MiB at line L" |
| Wall time | **20 s for the whole preview or save**: reader, parser, frame, rules and art share one `Deadline`, checked every 65,536 tokens, per footprint, per zone and per art layer | `limit`: "took longer than 20 s" |
| Art points before simplification | 2,000,000 | tracks, then zone fills are dropped, `ART_REDUCED` (§4.2) |
| Warnings kept | 500, then one `WARNINGS_TRUNCATED` with the count | – |

The 413 and `too-complex` texts (§3.2) add: "A board with filled zones is much larger: in KiCad, Edit › Unfill All
Zones, save, and upload that file (zones are then not drawn)."

**Numbers.** `Double.parseDouble`; then range checks, because a finite but absurd number would make the art step
explode after parsing (one arc of radius 1e9 mm flattened at 0.01 mm is about 7e5 points):

- coordinates outside ±2,147 mm (KiCad's own range: 32-bit nanometres) → `BAD_NUMBER`, that item skipped;
- sizes, radii and drills outside (0, 2,147] mm, widths outside [0, 2,147] mm → `BAD_NUMBER`;
- arcs and circles are flattened with an angle step of at least 1° (≤ 360 segments per full circle), Béziers with at
  most 64 segments.

**String cleaning (`K/KText`).** Strings stay raw in the model. Every string that reaches OpenPnP or the art goes
through `KText.clean`: Value (→ part id), footprint name (→ package id), Reference (→ placement id), board texts, the
browser's file name (→ package description) and the operator's board name. Reason: simple-xml 2.9.0 (pom.xml:58-59)
writes characters that XML forbids, and reading them back fails. Checked with a scratch program on the repository's
jar: an attribute holding U+0007 or U+FFFE fails with "An invalid XML character (Unicode: 0x7) was found"; `"a\nb"` and
`"a\tb"` come back as `"a b"`. A parts.xml or packages.xml read error stops OpenPnP from starting
(O/model/Configuration.java:455-462, :474-481). A placement whose part id changed loads without a part
(O/model/Placement.java:110-115), and the planner then skips it (D/BoardPadExtractor.java:52-57).

`clean(s, max)`:

1. TAB, CR and LF become a space; every other C0 control (U+0000–U+001F), DEL and C1 (U+007F–U+009F), U+FFFE, U+FFFF
   and lone surrogates are dropped;
2. whitespace runs collapse to one space; the result is trimmed;
3. capped at `max` code points (ids: 100; texts: 200; the board name: 60; the file name: 120).

`cleanId(s, ifEmpty)` = `clean(s, 100)`, and an empty result becomes `ifEmpty` (`FP` for a footprint name, `P` for a
Reference; an empty Value keeps today's rule, §2.9). Any change to an id adds `ID_CLEANED` (warn) naming the reference.
§3.6 also reads every written file back before it is moved into place (belt and braces).

### 2.3 Model (`K/KicadPcb.java`; KiCad millimetres, KiCad frame: Y down, as in the file)

```java
public final class KicadPcb {
  public int version;                       // (version N); 0 if absent
  public String generator, generatorVersion;
  public boolean legacy;                    // KiCad 5 rules: footprints are (module …)
  public Setup setup = new Setup();
  public final List<Footprint> footprints = new ArrayList<>();   // top AND bottom (classification decides)
  public final List<Graphic> graphics = new ArrayList<>();       // board-level gr_*
  public final List<Text> texts = new ArrayList<>();             // board-level gr_text
  public final List<Track> tracks = new ArrayList<>();           // segment and arc
  public final List<Via> vias = new ArrayList<>();
  public final List<Zone> zones = new ArrayList<>();             // board-level zones
  public int ignoredItems;                                       // unknown heads, counted for the info line

  public static final class Setup {
    public Double padToMaskClearance, padToPasteClearance, padToPasteClearanceRatio;
    public boolean viasTentedFront = true;  // board default for vias (§2.4: three forms)
    public String maskColor, silkColor;     // stackup F.Mask / F.SilkS (color "…"), null if absent
  }

  public static final class Footprint {
    public String lib, name;                // "Resistor_SMD", "R_0603_1608Metric"; lib "" when absent (mobo "LOGO")
    public String ref, value;               // Reference / Value (property or fp_text), raw
    public boolean refHidden; public String refLayer; public double refX, refY, refAngle, refSize;  // label (§5.3)
    public boolean top;                     // (layer "F.Cu")
    public double x, y, angle;              // (at x y [angle]); angle degrees CCW as shown, 0 when omitted
    public final Set<String> attrs = new LinkedHashSet<>();  // smd through_hole board_only exclude_from_pos_files exclude_from_bom dnp virtual
    public Double pasteMargin, pasteRatio, maskMargin;       // footprint-level overrides (null = not present)
    public final List<Pad> pads = new ArrayList<>();
    public final List<Graphic> graphics = new ArrayList<>(); // fp_*, footprint-local coordinates
    public final List<Zone> zones = new ArrayList<>();       // zones inside the footprint (F.Paste windows)
    public int line;                        // for warnings
  }

  public static final class Pad {
    public String name;                     // raw; may be "" or repeated
    public String type;                     // smd thru_hole np_thru_hole connect
    public String shape;                    // rect roundrect circle oval trapezoid custom
    public double x, y;                     // footprint-local; for a bottom footprint already mirrored by KiCad
    public double angle;                    // as written: absolute (includes the footprint angle)
    public double w, h;
    public double rratio;                   // roundrect_rratio, 0 if absent
    public Double chamferRatio; public final Set<String> chamfer = new HashSet<>();
    public double deltaX, deltaY;           // rect_delta (trapezoid)
    public double drillW, drillH, offX, offY;   // drill (circle: w = h), offset
    public final Set<String> layers = new HashSet<>();
    public Double pasteMargin, pasteRatio, maskMargin;
    public final List<Graphic> primitives = new ArrayList<>();  // custom pads, pad-local
  }

  public static final class Graphic {      // gr_* and fp_*; arcs normalised to start/mid/end
    public enum Kind { LINE, ARC, CIRCLE, RECT, POLY, BEZIER }
    public Kind kind; public String layer; public double width; public boolean filled;
    public double[] pts;                    // LINE x1 y1 x2 y2; ARC sx sy mx my ex ey; CIRCLE cx cy ex ey;
                                            // RECT x1 y1 x2 y2; POLY flattened x0 y0 x1 y1 …; BEZIER 4 control points
  }
  public static final class Text { public String text, layer, justify; public double x, y, angle, size; public boolean hidden, mirror; }
  public static final class Zone { public final Set<String> layers = new HashSet<>(); public boolean keepout;
                                   public final Map<String, List<double[]>> filled = new LinkedHashMap<>(); }  // layer -> polygons
  public static final class Track { public double x1, y1, x2, y2; public Double mx, my; public double width; public String layer; }
  public static final class Via { public double x, y, size, drill; public final Set<String> layers = new HashSet<>();
                                  public Boolean tentedFront; }   // null = the board default
}
```

Checked on nano: the bottom-side headers J1 and J2 store pad positions that, with the same transform as a top
footprint (§2.6 step 6), land on all 30 of their drill hits in nanoPaste's sibling `NanoV3.3-PTH.drl` with a maximum
distance of 0.0 mm. KiCad stores a flipped footprint's pads already mirrored, so no extra flip is applied.

### 2.4 What is extracted

| Head | Read | Ignored |
|---|---|---|
| `kicad_pcb` | `version`, `generator`, `generator_version`, `setup`, `footprint`/`module`, `gr_line`, `gr_arc`, `gr_circle`, `gr_rect`, `gr_poly`, `gr_curve`, `gr_text`, `segment`, `arc`, `via`, `zone` | `general`, `paper`, `title_block`, `layers` (canonical names are used), `net`, `net_class`, `property`, `dimension`, `target`, `table`, `group`, `image`, `embedded_*`, `generated` |
| `setup` | `pad_to_mask_clearance`, `pad_to_paste_clearance`, `pad_to_paste_clearance_ratio`; via tenting (below); `stackup` → `layer "F.Mask" (color …)`, `layer "F.SilkS" (color …)` | everything else (`covering`, `plugging`, `capping`, `filling` of KiCad 10, other `pcbplotparams`, …) |
| `footprint` | 1st atom = `LIB:NAME` (split at the first `:`; no colon → lib ""); bare flags `locked`/`placed`; `layer`; `at`; `attr`; `property "Reference"` / `fp_text reference` with its `at`, `layer`, `hide` and font size; `property "Value"` / `fp_text value`; `solder_paste_margin`; `solder_paste_ratio` or `solder_paste_margin_ratio`; `solder_mask_margin`; `fp_line`/`fp_arc`/`fp_circle`/`fp_rect`/`fp_poly`/`fp_curve`; `pad`; `zone` | `descr`, `tags`, `path`, `sheetname`, `sheetfile`, `uuid`/`tstamp`/`tedit`, `model`, `fp_text user`, other `property` entries, `net_tie_pad_groups` |
| `pad` | name, type, shape, `at`, `size`, `drill` (`d` or `oval a b`, `offset`), `layers`, `roundrect_rratio`, `chamfer_ratio`, `chamfer`, `rect_delta`, `solder_paste_margin`, `solder_paste_margin_ratio`, `solder_mask_margin`, `primitives` (`gr_poly`, `gr_line`, `gr_circle`, `gr_arc`, `gr_rect`, `gr_curve`), `options` (anchor) | `net`, `pinfunction`, `pintype`, `die_length`, `zone_connect`, `thermal_*`, `clearance`, `remove_unused_layers`, `keep_end_layers`, `pad_prop`, `padstack` (KiCad 9/10: the top-level shape and size are used; a non-`normal` mode adds `PADSTACK_IGNORED` info) |
| graphics | `start`/`mid`/`end`, `center`, `angle` (KiCad 5 arcs), `pts` (`xy` and `arc` entries), `width` or `stroke (width)`, `fill`, `layer` | `stroke (type)`, `uuid`, `locked` |
| `gr_text` | text, `at x y [angle]`, `layer`, `effects (font (size h w))`, `justify`, `mirror`, `hide` | `render_cache`, `font (face)`, `thickness` |
| `segment`/`arc` | `start`, `mid`, `end`, `width`, `layer` | `net`, `uuid`, `locked` |
| `via` | `at`, `size`, `drill`, `layers`, `tenting` (both forms below: overrides the board default) | `net`, `free`, `remove_unused_layers`, KiCad 10 `covering`/`plugging`/`capping`/`filling` |
| `zone` | `layer` / `layers` (`"F&B.Cu"`, `"*.Cu"` or a list), `keepout` (→ skipped), each `filled_polygon` (`layer`, `pts`) | `polygon` (the outline before fill), `fill` settings, `connect_pads`, `name`, `priority` |

**Fill of graphics.** A `fill` token decides when present (`yes`/`solid` = filled; `no`/`none` = not). When it is
**absent** (KiCad 5 and early KiCad 6 files): polygons on any layer except Edge.Cuts are filled, and rects and
circles with width ≤ 0 are filled, as KiCad's own loader treats legacy files. Every other shape is unfilled.

**Via tenting (board default for vias on the top side).** Three forms, first match wins:

| Form | Version | Example | Front tented when |
|---|---|---|---|
| `(tenting (front yes) (back yes))` | KiCad 10 | pi10:44-47 | `front yes` |
| `(tenting front back)` (bare atoms list the tented sides) | KiCad 9 | mobo.kicad_pcb:78 | `front` listed |
| `pcbplotparams (viasonmask false\|no\|true\|yes)` | KiCad 5–8 | ftp.kicad_pcb:95 `no`; nano:66 `false`; pi7:54 `false` | `viasonmask` false/no |
| none | – | – | tented (KiCad's default) |

A per-via `tenting` (either form) overrides it. Only untented vias open the mask in the art (§4.2).

Unknown heads anywhere are counted in `ignoredItems` and never fatal.

### 2.5 KiCad 5 / 6 / 7 / 8 / 9 / 10 differences

| Topic | KiCad 5 (`20171130`) | 6 (`20211014`) | 7 (`20221018`) | 8 (`20240108`) | 9 (`20241229`) | 10 (`20260206`) |
|---|---|---|---|---|---|---|
| Fixture | none (synthetic) | nano (CRLF) | pi7 | ftp | secondary, mobo, ftp0402 | pi10 |
| Footprint token | `module` | `footprint` | `footprint` | `footprint` | `footprint` | `footprint` |
| Reference / Value | `fp_text reference/value` | `fp_text` (nano :15398) | `fp_text` | `property "Reference"` | `property` | `property` (pi10, J3) |
| Arcs (gr/fp/track) | `start` = centre, `end` = arc start, `angle` = sweep | start/mid/end | start/mid/end | start/mid/end | start/mid/end | start/mid/end |
| Line width | `(width w)` | `(width w)` | `(stroke (width w) …)` | `stroke` | `stroke` | `stroke` |
| Polygon fill | no token: filled | `(fill solid)` (nano :15196-15216) | `(fill solid\|none)` | `(fill yes\|no)` | `(fill yes\|no)` | `(fill yes\|no)` |
| DNP | none | none (nano marks DNP in the Value) | `attr … dnp` | `attr … dnp` | `attr smd dnp` (mobo :13525) | `attr … dnp` |
| Hidden text | bare `hide` | bare `hide` | bare `hide` | `(hide yes)` | `(hide yes)` | `(hide yes)` |
| Via tenting (board) | `viasonmask` | `viasonmask` | `viasonmask` | `viasonmask` | `(tenting front back)` | `(tenting (front yes) (back yes))` |
| Zone layers | `(layer)` | `(layer)` / `(layers "F&B.Cu")` | same | same | `(layers "F.Cu" "B.Cu")` | same |
| `filled_polygon` layer | absent: the zone's layer | `(layer)` | `(layer)` | `(layer)` | `(layer)` | `(layer)` |
| Strings | often unquoted | quoted | quoted | quoted | quoted | quoted |
| Extras | `attr virtual` (= board only) | `tedit` | – | `generator_version`, `uuid` | `render_cache`, `embedded_fonts`, `padstack` | `covering`, `plugging`, `capping`, `filling`, `units`, `duplicate_pad_numbers_are_jumpers` (all ignored) |

Rules that depend on the version:

- **Every form is accepted in every version** (`width` or `stroke`, bare or `(x yes)` flags, `property` or `fp_text`,
  the three tenting forms): the version is used only for the three gates below.
- **KiCad 5 arcs** (an arc with `angle` and no `mid`): the arc starts at `end`, centre `start`, and sweeps `angle`
  degrees, **clockwise on screen for a positive angle**. Not verified on a file (OQ8): the preview's outline makes a
  wrong guess visible, and the synthetic test pins the rule.
- **Explicit zero margins**: for `version < 20240108` a present `solder_paste_margin 0` (or ratio 0) means "not set"
  and the footprint or board value applies (KiCad ≤ 7). From `20240108` a present token overrides even when it is 0
  (KiCad 8 made local overrides optional values). Unverified (no fixture sets one, OQ8); `PASTE_ZERO_OVERRIDE` (info)
  names every pad where the two readings differ.
- **Version range**: `version > 20260206` → `KICAD_NEWER` (warn: "This file is newer than KiCad 10: it was read with
  the KiCad 10 rules. Check the outline and the parts in the preview."); `module` footprints or `version < 20211014` →
  `KICAD5` (warn: "KiCad 5 board: read on a best effort basis. Check the outline and the parts in the preview.").

### 2.6 Board frame

**Rule** (brief): origin at the bottom-left corner of the Edge.Cuts bounding box, +Y up.

1. **Edge.Cuts primitives**: board-level `gr_*` on `Edge.Cuts`, plus footprint `fp_*` on `Edge.Cuts` of **every**
   footprint (top or bottom) transformed to board coordinates (mobo has 6 `fp_circle` cut-outs). Rectangles become 4
   lines; polygons a closed polyline; each circle its own closed loop.
2. **Chaining**: lines and arcs are chained by endpoints within 0.01 mm, through a hash grid of 0.02 mm cells (no
   quadratic search), in file order (deterministic). Closed chains are loops. **Outline** = the loop with the largest
   bounding-box area; **cut-outs** = the other closed loops.
3. **Box** = the exact extents of the outline loop: line endpoints, arc endpoints **plus the 0°/90°/180°/270° points
   inside the sweep**, circle centre ± r. With endpoints only, mobo's min X would be 99.95 instead of 99.7323
   (its outline arcs bulge, mobo.kicad_pcb:28202): every placement 0.22 mm off.
4. **Open outline** (a chain that does not close): `OUTLINE_OPEN` (warn, with the gap and where); the box is the exact
   extents of all Edge.Cuts primitives. Those are still the board's physical edges, so the origin kind is `edges`.
5. **No Edge.Cuts**: the box is the exact extents of every footprint's courtyard (`F.CrtYd`/`B.CrtYd`), else its
   `F.Fab`, else its pads; `NO_EDGE_CUTS` (warn). The origin kind is `estimated`: no physical corner marks it.
   **A board with origin `estimated` and fewer than 2 fiducials cannot be saved** (§3.2, 400 `no-origin`): nobody could
   find its origin on the machine, and Register board needs 2 fiducials. With ≥ 2 fiducials it is saved, and the
   Board step places it with "Camera is on FID1" (§5.9).
6. **Transform** (`BoardFrame`): `x_b = x − minX`, `y_b = maxY − y`; angles unchanged (KiCad's angle is
   counter-clockwise as shown, which is counter-clockwise in a +Y-up frame). A footprint-local point `(u, v)` of a
   footprint at `(X, Y, θ)` is `(X − minX, maxY − Y) + R(θ)·(u, −v)` with `R` the standard CCW rotation, the same
   composition `BoardPadExtractor` uses for a pad offset (D/BoardPadExtractor.java:117-123). Bottom footprints use the
   same transform (§2.3, checked on nano's drill file).
7. **Board size** = `(maxX − minX, maxY − minY)` → the board's `dimensions` (O/model/PlacementsHolder.java:49,
   :150-154). Placement coordinates are rounded to 1e-6 mm; −0 becomes 0.

**Checked**:

- ftp's Edge.Cuts box is 0..75 × −75..0, so 75 × 75 = `ftp.board.xml:2`.
- All 83 placements of ftp.board.xml match within 3.6e-5 mm (e.g. D1 37.499966, 47.499987 vs 37.5, 47.5,
  `ftp.board.xml:4-5`; R1 37.499966, 42.499987, 90°), with identical rotations (−45, −90, −135 kept; an omitted angle
  is 0). The one exception is FID1: KiCad (62.5, 5.0) vs (62.4466, 4.6910), 0.3136 mm, the hand move the brief mentions.
- mobo: 51.5677 × 89.0 mm; nano: 43.18 × 17.78 (4 `gr_circle` cut-outs); secondary: 10 × 10 (`gr_rect`).

**The hand-moved FID1 is not cosmetic.** Every bench registration fits the board→machine affine to FID1–FID3 at the
board coordinates of `tools/register.py:14`, which are ftp.board.xml's (FID1 hand-moved). Refitting all 11
`~/instancelabs/runs/board_registration*.json` files (bench venv numpy, 3-point exact affine) with each FID1:

| FID1 used | X scale | Y scale |
|---|---|---|
| ftp.board.xml (62.4466, 4.6910) | 1.0011–1.0016 | **0.9935–0.9947** |
| KiCad (62.5, 5.0) | 1.0011–1.0016 | **0.9983–0.9995** |

So the "scales 1.0013 / 0.9943" of the autoreg brief (autoreg-brief.md:35) are mostly the hand edit: a board does not
shrink 0.6 % in Y, and KiCad's FID1 brings Y back to within 0.2 % of 1. With the same measured fiducials, the
registered pads of the two board files differ by up to **0.254 mm** (R19 at board (26.25, 17.0), the low rows) and by
0.336 mm at the worst point on the board. Either today's bench plans or the import's are off on the low rows. AR's sim
scene seeds the hand-moved coordinates (AR:1773), so simulation cannot show which. The design takes KiCad's value for
the import (it is the design file) and changes nothing in ftp.board.xml; §6.2 pins the numbers, §7 item 2 asks for
the real check, and OQ4 asks the main thread to decide before plans of the two files are mixed or compared.

### 2.7 Classification of footprints (first matching rule wins)

Classification decides **placements only**. The art and the probe keep-outs take holes, top copper, top mask openings
and Edge.Cuts from **every** footprint, top or bottom (§4.2).

| # | Rule | Result | Listed as |
|---|---|---|---|
| 1 | layer `B.Cu` | not a placement (no bottom dispensing); its through-hole pads, drills, top mask openings and Edge.Cuts are drawn; art part kind `bottom` | `skipped.bottom`, one `BOTTOM_SKIPPED` (info) with the refs |
| 2 | **fiducial**: lib `Fiducial` (case-insensitive), or ref matching `(?i)^FID`, or **footprint name matching `(?i)^fiducial`** (extension, OQ2) | `Placement` type `Fiducial`, enabled (DNP is ignored for fiducials) | `fiducials`; without an F.Cu pad → art only + `FIDUCIAL_NO_COPPER` |
| 3 | has **paste apertures** (§2.8) and attr `dnp` | `Placement`, `enabled=false` | `dnp` + `DNP_STILL_PASTED` (warn, OQ1) |
| 4 | has paste apertures and attr `exclude_from_pos_files`, `board_only` or (KiCad 5) `virtual` | `Placement`, `enabled=false` (not placed, still pasted: D/DispenseConfig.java:176-177) | `pasteOnly` (info, extension) |
| 5 | has paste apertures | `Placement`, enabled | `placements` |
| 6 | otherwise (through-hole, mounting holes, logos, test points) | art only | `artOnly` with why: `no paste pads`, `through-hole`, `board only` |

- **Placement ids** are the cleaned references (§2.2). An empty reference becomes `P~1`, `P~2`… A reference already
  used by an earlier placement or fiducial gets `~2`, `~3`… (`DUPLICATE_REF`, warn). Duplicates among art-only
  footprints (the five `G***` logos of the original mobo file) change nothing.
- **Pad ids are unique over the whole board.** A pad id is `<placementId>.<padName>` (D/BoardPadExtractor.java:131),
  and pad ids key done tracking (W/records.js:261-263). Ref `U1.1` with pad `2` and ref `U1` with pad `1.2` both give
  `U1.1.2`. After placement ids and pad names are fixed, every pad id is checked; on a clash the later placement's id
  gets the next free `~n` (`PAD_ID_CLASH`, warn).
- A Value of `DNP`, `DNF`, `NP` or `DO NOT PLACE` (case-insensitive) on a rule-5 footprint adds `VALUE_SAYS_DNP`.
  Nano's FD1/FD2 (fiducials) and D+0/D−0 (art only) carry Value `DNP` and are not affected.
- `enabled` is the only OpenPnP field that DNP changes; placements keep `side=Top`, `z=0`, `rank=0`.

### 2.8 Paste apertures (`K/PasteRules.java`)

**Sources**, per top-side footprint:

1. Pads of type `smd` or `connect` whose layers contain `F.Paste` or `*.Paste`, including paste-only pads (mobo U2's
   nine `""` windows, layers `F.Paste` only).
2. Filled `fp_poly`, `fp_rect`, `fp_circle` on `F.Paste` inside the footprint (nano IC3's exposed-pad paste is a
   1.27 × 1.27 mm `fp_poly`, NanoV3.3.kicad_pcb:15196-15216; mobo J5 has two), and the `filled_polygon`s on `F.Paste`
   of zones inside the footprint (treated as `fp_poly`). A footprint zone on F.Paste without a fill adds
   `PASTE_ZONE_IGNORED` (warn).
3. Not sources: `thru_hole` pads with F.Paste (pin-in-paste) → `THT_PASTE_SKIPPED` (warn, OQ9); board-level `gr_*` on
   F.Paste (they belong to no part) → drawn only + `BOARD_PASTE_GRAPHIC` (warn).

**Margins** (KiCad's rule; brief: pad > footprint > board). The margin `m` and the ratio `ρ` are each resolved
independently: pad value → footprint value → board setup (`pad_to_paste_clearance`, `pad_to_paste_clearance_ratio`)
→ 0, with the zero rule of §2.5. Per axis: `mx = m + ρ·w`, `my = m + ρ·h`. In the fixtures the only paste setting is
the pad-level `solder_paste_margin -0.0762` on nano's pads (e.g. NanoV3.3.kicad_pcb:169); the other levels are tested
on synthetic files (§6.2).

**Shapes** → OpenPnP `Footprint.Pad` (`width`, `height`, `roundness` in percent; corner radius = min(w,h) ×
roundness / 200, O/model/Footprint.java:324-325, D/DotMath.java:9-12):

| KiCad shape | Aperture `w' × h'`, centre, rotation | Roundness | Warning |
|---|---|---|---|
| `rect` | `w+2mx × h+2my` at the pad | `r' = max(0, min(mx,my))`; `200·r'/min(w',h')` (0 for margin ≤ 0) | – |
| `roundrect` | same | `r = rratio·min(w,h)` (rratio ≤ 0.5); `r' = max(0, r + min(mx,my))`; `clamp(200·r'/min(w',h'), 0, 100)` | – |
| `circle` | `d+2mx` both | 100 | – |
| `oval` | `w+2mx × h+2my` | 100 (stadium) | – |
| `roundrect` + `chamfer` | as roundrect | as roundrect | `CHAMFER_IGNORED` (info, with the area difference: each chamfered corner removes `c²/2`, `c = chamfer_ratio·min(w,h)`) |
| `trapezoid` | bounding box `w+\|Δy\|+2mx × h+\|Δx\|+2my`, centred on the pad (a trapezoid is symmetric about its anchor) | 0 | `PAD_SHAPE_APPROX` (warn, with the area difference) |
| `custom` | bounding box, **in the pad frame**, of the anchor shape and every primitive, each grown by half its stroke width, then by the margin; **centred on that box's centre** (rotated by the pad angle into the part frame), not on the anchor | 0 | `PAD_SHAPE_APPROX` |
| F.Paste `fp_poly` / zone fill | a rectangle at **any angle** (4 corners, right angles within 0.001 mm) → exact, with its rotation; any other convex polygon → its minimum-area enclosing rectangle (exact for rectangles; otherwise the area difference is given); a concave one → its bounding box | 0 | `PASTE_POLY` (info) / `PASTE_POLY_APPROX` (warn) |
| F.Paste `fp_rect` (filled) | exact | 0 | – |
| F.Paste `fp_circle` (filled) | `2r` | 100 | – |

- `w' ≤ 0.01` or `h' ≤ 0.01` → no aperture, `APERTURE_GONE` (warn).
- Examples: ftp's 160 pads are `roundrect` 0.25 → **roundness 50**, no margin (aperture = copper: R 0.8 × 0.95 at
  ±0.825, LED 0.875 × 0.95 at ±0.7875, ftp.kicad_pcb:10893-10897, :16955-16969). Nano's pads shrink by 0.1524 mm per
  axis and their corner radius by 0.0762 (copper 0.18, paste 0.1038: nanoPaste `%ADD11RoundRect,0.103800…`). Nano's
  fiducial (1.016 mm square, rratio 0.5) is round (roundness 100).
- **Position** in the part frame (+Y up, what OpenPnP stores): the shape centre (the pad position plus the drill
  `offset` rotated by the pad's relative angle, or the custom box centre above); `(x, y) = (cu, −cv)`.
- **Rotation** = pad angle − footprint angle, normalised to (−180, 180] (KiCad writes the absolute angle: ftp R1 at 90°
  has pads `(at −0.825 0 90)`, ftp.kicad_pcb:10716, :10894; mobo J5 at −125° has pads at 235°). Graphics apertures
  have the rotation of their rectangle (0 when axis-aligned).

**Pad names** (unique within the footprint; cleaned with `KText.clean(name, 40)`):

1. Non-empty unique names are kept.
2. Repeated names: the second and later become `name~2`, `name~3` (`PAD_NAME_DUPLICATE`, info): mobo U4 `2`/`2~2`,
   J2 and J3 `MP`/`MP~2`.
3. Empty names and graphics apertures: `<host>~k`, where `host` is the name of the F.Cu pad of the same footprint
   whose rotated rectangle contains the aperture's centre (a named pad first), else `P`; `k` counts from 1 per host
   in file order, skipping names already used (`PAD_NAME_EMPTY`, info). mobo U2's nine windows inside the exposed pad
   `33` become `33~1`…`33~9`; U7/U8's two windows inside `9` become `9~1`, `9~2`; nano IC3's polygon becomes `33~1`.

**Fiducial packages** have no paste. Their footprint gets the **F.Cu pads** with the same shape rules and margin 0:
ftp's fiducials become one round 1.0 mm pad (roundness 100). AR's BoardFeatures reads the diameter from the first
round pad (AR:326-330). The planner never uses them (fiducials are skipped, D/BoardPadExtractor.java:42). The mask
opening diameter (ftp 3.0 mm from `solder_mask_margin 1`; mobo 2.0 mm) has no place in an OpenPnP package; it is in the
art's `fiducials` (§4.1) and offered to AR (§4.4, §10.2).

**Package signature**: the pads sorted by name, each `(name, x, y, w, h, rotation, roundness)` rounded to 0.001 mm,
0.001° and 0.1 %. Two footprints of the same name with different signatures in one file (a footprint edited on the
board, or a per-instance margin) are two **variants** of that name (`PACKAGE_VARIANT`, warn, naming the refs). The
proposal does not name them: §3.5 gives every variant its final id.

### 2.9 The proposal (`K/ImportProposal.java`, pure data)

```java
public final class ImportProposal {
  public double widthMm, heightMm, minX, maxY;          // board frame (KiCad coordinates of the origin: minX, maxY)
  public String outline;                                // "closed" | "open" | "none"
  public String origin;                                 // "outline" | "edges" | "estimated" (§2.6)
  public final List<PlacementP> placements = new ArrayList<>();  // file order, fiducials included
  public final List<PackageP> packages = new ArrayList<>();      // one per distinct (name, signature) = variant
  public final Map<String, List<String>> skipped = new LinkedHashMap<>();  // bottom / artOnly / dnp / pasteOnly -> refs
  public final List<ImportWarning> warnings = new ArrayList<>();
  public Counts counts;     // footprints, placements (WITHOUT fiducials), fiducials, pastePads, dnp, pasteOnly, bottom, artOnly

  public static final class PlacementP { public String id, ref, value;     // cleaned
                                         public int packageIndex; public String type; // "Placement" | "Fiducial"
                                         public boolean enabled; public double x, y, rotation; public int pads; public String why; }
  public static final class PackageP { public String baseId;           // cleaned footprint name (no library)
                                       public int variant;             // 1, 2, … in file order
                                       public String lib, kicadName; public boolean fiducial;
                                       public final List<PadP> pads = new ArrayList<>();  // mm, part frame
                                       public String signature; public double bodyW, bodyH; public final List<String> refs = new ArrayList<>(); }
  public static final class PadP { public String name; public double x, y, w, h, rotation, roundness; public String approx; }
}
```

The **part key** of a placement is `(final package id, value)`; its id is `<package id>-<value>` (a blank Value gives
the package id alone), as OpenPnP's `.pos` importer does (O/gui/importer/KicadPosImporter.java:137-139). All 83 part
ids of ftp.board.xml have this form (`R_0603_1608Metric-R_Small`, `LED_0603_1608Metric-LED_Small`,
`Fiducial_1mm_Mask3mm-Fiducial`). Final package and part ids come from the allocator (§3.5).

`counts.placements` **excludes fiducials everywhere** (the API, the dialog, the tests); `counts.fiducials` counts them.

### 2.10 Warnings: order, groups and texts

Every warning belongs to one **group**, and the dialog lists them in group order (§5.8). Texts say what happens and
what to do, in plain words with units; internal names (regexes, setting keys) stay out of them.

| Group (order) | Code | Level | Text (refs and numbers filled in) |
|---|---|---|---|
| 1 blocks dispensing | `NO_PAD_TYPE` | warn | U7, U8 (11 pads) get no paste until their package has a pad type. Save the board, then add one: New pad type for WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm. |
| 1 | `NO_PLACEMENTS` | warn | No top-side part has paste pads: there is nothing to dispense on this board. |
| 1 | `APERTURE_GONE` | warn | U4 pad 7 gets no paste: the board's paste margin removes the whole opening. Change the margin in KiCad if paste is wanted there. |
| 2 registration | `FIDUCIAL_COUNT` | warn | Only 1 fiducial on the top side: Register board needs 2 (3 are better). Without them, place the board by hand and probe it by hand. |
| 2 | `FIDUCIAL_NO_COPPER` | warn | FID4 has no copper pad, so it cannot be found by the camera: it is drawn only. |
| 3 check the preview | `NO_EDGE_CUTS` | warn | The file has no board outline (Edge.Cuts). The origin is estimated from the parts: place the board with "Camera is on FID1" after saving. |
| 3 | `OUTLINE_OPEN` | warn | The board outline is not closed (gap 0.12 mm near X 10.0, Y 3.0 mm). The board size uses every outline line; check the drawing. |
| 3 | `KICAD5` / `KICAD_NEWER` | warn | §2.5 |
| 3 | `DNP_STILL_PASTED` | warn | U2 is marked Do not populate. It will not be placed, but its 41 pads still get paste (the engine pastes disabled parts). |
| 3 | `VALUE_SAYS_DNP` | warn | U5's value says DNP but the part is not marked Do not populate in KiCad: it is imported as a normal part and gets paste. |
| 3 | `THT_PASTE_SKIPPED` | warn | J3 pads 1–4 are through-hole pads with paste (pin in paste): they get no paste. |
| 3 | `QFN_WINDOWS` | warn | U2's 9 paste windows (0.86 mm² each) inside its centre pad are dispensed as small edge pads, not as one centre pad. Check the dots in the pad inspector. |
| 3 | `PAD_SHAPE_APPROX` / `PASTE_POLY_APPROX` | warn | U5 pad 3 is a trapezoid: its paste opening is taken as the surrounding rectangle (12 % more area). |
| 3 | `PACKAGE_MAPPING_LOST` | warn | R_0603_1608Metric is mapped to pad type "Chip 0603" by hand; the new copy R_0603_1608Metric~2 is not, and gets "Chip 0805" by name. Map it in Profile › Pad types after saving. |
| 3 | `PACKAGE_VARIANT` | warn | Two different footprints are both called SOT-23 (U3 and U9 differ): the second is saved as its own package. |
| 3 | `DUPLICATE_REF` / `PAD_ID_CLASH` | warn | Two parts are named R5: the second is saved as R5~2. |
| 3 | `ID_CLEANED` | warn | U12's value had characters OpenPnP cannot store (a line break): saved as "10k 1%". |
| 3 | `BAD_NUMBER` | warn | Line 1203: a number could not be read or is out of range; that item was skipped. |
| 3 | `BOARD_PASTE_GRAPHIC` / `PASTE_ZONE_IGNORED` | warn | 2 paste shapes are drawn on the board outside any part: drawn only, no paste. |
| 3 | `ART_REDUCED` | warn | The board is too detailed to draw in full: tracks are not drawn. |
| 4 notes (collapsed) | `BOTTOM_SKIPPED` | info | 6 bottom-side parts get no paste (only the top is dispensed): J1, … |
| 4 | `PACKAGE_SUFFIXED` / `PART_SUFFIXED` | info | R_0603_1608Metric already exists in OpenPnP with other pads, so this board's copy is saved as R_0603_1608Metric~2. Other boards are not affected. (Raised to group 3 when `PACKAGE_MAPPING_LOST` applies to it.) |
| 4 | `PAD_NAME_EMPTY` / `PAD_NAME_DUPLICATE` | info | U2: 9 paste windows without a name are 33~1 … 33~9. |
| 4 | `PASTE_POLY` | info | IC3: the paste shape drawn on F.Paste is opening 33~1 (1.27 × 1.27 mm). |
| 4 | `CHAMFER_IGNORED`, `PASTE_ZERO_OVERRIDE`, `PADSTACK_IGNORED`, `ZONES_UNFILLED`, `ART_SIMPLIFIED`, `WARNINGS_TRUNCATED` | info | e.g. "Zones are not filled in this file: copper pours are not drawn." / "1,240 more notes not shown." |

### 2.11 What the fixtures give (on the stripped copies in `F/`, §6.1)

| | ftp | secondary | nano | mobo |
|---|---|---|---|---|
| KiCad | 8 (`20240108`) | 9 (`20241229`) | 6 (`20211014`, CRLF) | 9 (`20241229`) |
| Board | 75 × 75 mm, closed | 10 × 10 (`gr_rect`) | 43.18 × 17.78, 4 circular cut-outs | 51.5677 × 89.0, arcs, 13 cut-outs |
| Footprints | 89 (G1 logo removed) | 1 | 34 | 68 (6 logo footprints removed) |
| Parts (`counts.placements`) + fiducials | 80 (R1–R40, D1–D40) + 3 (FID1–FID3) | 0 + 0 | 8 + 2 (FD1, FD2) | 55 (U2 disabled) + 3 |
| Paste apertures | 160 (`roundrect` 0.25 → 50) | 0 | 55 (54 pads at −0.0762 + IC3's polygon) | 261 (259 pads + J5's 2 polygons) |
| Bottom (no placement) | G2 | REF** (a bottom fiducial) | 20 (J1, J2: 30 drilled pads drawn) | J1 |
| Art only | H1–H4, J1 | – | Silkscreen_Front, D+0, D−0, J4 | TP1–TP8, J8 |
| Notable warnings (test config, seed pad types) | only `BOTTOM_SKIPPED` | `NO_PLACEMENTS`, `FIDUCIAL_COUNT` | `PASTE_POLY` (IC3), `PAD_NAME_EMPTY`, `NO_PAD_TYPE` (J3, SW1, Y1) | `DNP_STILL_PASTED` (U2), `PAD_NAME_EMPTY` (13), `PAD_NAME_DUPLICATE` (U4, J2, J3), `PASTE_POLY` (J5), `QFN_WINDOWS` (U2), `NO_PAD_TYPE` (WSON and the connectors) |

New fixtures (survey of the source files before stripping; the stripped counts are pinned by the tests):

| | pi10 (KiCad 10) | pi7 (KiCad 7) | ftp0402 (KiCad 9) |
|---|---|---|---|
| Version | `20260206`, `generator_version "10.0"` | `20221018` | `20241229` |
| Footprints | 8 (5 `kibuzzard-*` text footprints removed) | 4 (2 `kibuzzard-*` removed) | 90 (`mfg:opulo-logo` removed) |
| Top paste pads | 12 | 8 | 160 (R/LED 0402) |
| Fiducials | 0 (`FIDUCIAL_COUNT`) | 0 | 3 |
| Via tenting | `(tenting (front yes) (back yes))` (pi10:44-47) | `viasonmask false` (pi7:54) | `(tenting front back)` (ftp0402:83) |

With the brief's fiducial rule alone, nano has 0 fiducials (FD1/FD2 are in library `NanoV3.3`).

---

## 3. Import

### 3.1 Flow (stateless: the browser keeps the file)

```
browser: File (picker or drop) ──POST raw bytes──▶ /api/boards/import?file=ftp.kicad_pcb
server:  [engine idle? one pipeline at a time? (§3.4)] one 20 s Deadline:
         SExprReader → KicadParser → BoardFrame → ImportRules (proposal) → BoardArtBuilder (art)
         → IdAllocator + pad types on a configuration snapshot (one EdtRunner call)
         ◀── 200 preview {sha256, decisions, counts, placements, packages, parts, warnings, attach, art}
browser: shows the board and the lists; the operator names it
         ──POST the same bytes──▶ /api/boards/import/save?file=…&name=FTP%20test%20board&replace=0&sha256=…&decisions=…
server:  same gates, same pipeline; sha256 must match; files staged; ONE EdtRunner transaction (§3.6):
         allocate (digest must equal `decisions`) → packages, parts → board → read back → move files → addBoard
         → packages.xml, parts.xml, boards.xml → index.json; any failure undoes all of it
         ◀── 201 {file, slug, packages, parts, art, boards}
browser: "Use this board" → Board step selects it → position (Camera is on FID1) → Plan → Register board (AR)
```

There is no server-side preview session, no import id, no expiry and no DELETE: the save re-parses the bytes (1–3 s
for the fixtures) and re-decides against the configuration as it is then.

### 3.2 Endpoints (new group `A/BoardImportEndpoints.java`, registered in the ApiServer constructor next to the others, A/ApiServer.java:111-117)

**`POST /api/boards/import?file=<browser file name>`** (preview; body limit 12 MiB, §3.3)

- Body: the raw file, `Content-Type: application/octet-stream` (any type is accepted; a body that starts with `{`
  after whitespace is a 400 "send the file itself, not JSON"). `file` is the browser's `File.name`, cleaned
  (`KText.clean(…, 120)`); it is used for display, the package description and the suggested board name only.
- 200:

```json
{"file": {"name": "ftp.kicad_pcb", "bytes": 816950, "sha256": "9c1e…(64 hex)"},
 "decisions": "5be0a1c39d2f7e44",
 "kicad": {"version": 20240108, "generator": "pcbnew", "generatorVersion": "8.0", "major": 8},
 "board": {"widthMm": 75.0, "heightMm": 75.0, "outline": "closed", "origin": "outline", "originKicad": {"x": 0.0, "y": 0.0}},
 "suggestedName": "ftp", "slug": "ftp",
 "exists": null,
 "counts": {"footprints": 89, "placements": 80, "fiducials": 3, "pastePads": 160, "dnp": 0, "pasteOnly": 0, "bottom": 1, "artOnly": 5},
 "placements": [{"id": "R1", "type": "Placement", "enabled": true, "x": 37.499966, "y": 42.499987, "rotation": 90.0,
                 "packageId": "R_0603_1608Metric", "partId": "R_0603_1608Metric-R_Small", "pads": 2, "outcome": "paste"}],
 "fiducials": [{"id": "FID1", "x": 62.5, "y": 5.0, "diameterMm": 1.0, "maskMm": 3.0}],
 "skipped": {"bottom": ["G2"], "artOnly": [{"ref": "H1", "why": "no paste pads"}], "dnp": [], "pasteOnly": []},
 "packages": [{"baseId": "R_0603_1608Metric", "variant": 1, "id": "R_0603_1608Metric~2", "state": "suffixed",
               "why": "R_0603_1608Metric exists in OpenPnP without pads", "refs": ["R1", "…"], "fiducial": false,
               "pads": [{"name": "1", "x": -0.825, "y": 0.0, "w": 0.8, "h": 0.95, "rotation": 0.0, "roundness": 50.0}],
               "padTypes": [{"typeKey": "chip-0603", "name": "Chip (2-terminal SMD) 0603", "version": 2,
                             "status": "characterized", "rule": "pattern", "matched": "0603",
                             "sentence": "Chosen because the package name contains “0603”.", "pads": 2, "dispensable": true}]}],
 "parts": [{"id": "R_0603_1608Metric~2-R_Small", "packageId": "R_0603_1608Metric~2", "state": "new", "refs": 40}],
 "warnings": [{"code": "BOTTOM_SKIPPED", "level": "info", "group": 4, "refs": ["G2"], "text": "…"}],
 "attach": [{"file": "/…/uinav-boards/00-ftp.board.xml", "name": "ftp", "maxOffsetMm": 0.3136,
             "offsets": [{"id": "FID1", "mm": 0.3136}]}],
 "art": { … §4.1 … }}
```

- `exists`: null, or `{slug, file, name, importedAtMs}` when the slug of `suggestedName` is taken.
- `state` per package: `new`, `reuse` (same id, same footprint), `suffixed` (§3.5). Per part: `new`, `reuse`, `suffixed`.
- `placements[].outcome`: `paste` (gets paste), `no-pad-type` (blocked until a pad type exists), `not-dispensed`
  (fiducial, art only, bottom); the preview tints parts by it (§5.8).
- `attach`: listed board files whose placements match this file (§3.10); empty for most uploads.
- `decisions`: the digest of §3.5; the save must send it back.

**`POST /api/boards/import/save?file=…&name=…&replace=0|1&sha256=<64 hex>&decisions=<16 hex>`** (same body, same limit)

- `name`: the operator's board name, `KText.clean(…, 60)`, 1–60 characters, at least one letter or digit.
- 201:

```json
{"file": "/home/…/instancelabs/boards/ftp/ftp.board.xml", "name": "FTP test board", "slug": "ftp", "replaced": false,
 "packages": [{"id": "R_0603_1608Metric~2", "state": "new"}], "parts": [{"id": "R_0603_1608Metric~2-R_Small", "state": "new"}],
 "art": {"id": "4b7d0e19a2c3f856", "kind": "kicad"}, "warnings": [ … ],
 "boards": { … the GET /api/boards body … }}
```

**`POST /api/boards/import/attach?file=…&board=<absolute path of a listed board file>&name=…&replace=0|1&sha256=…`**
(same body, same limit): §3.10. 201 `{slug, board, art: {id, kind: "kicad"}, offsets, boards}`.

**Errors** (ApiException codes; A/ApiServer.java:322-353 maps them):

| Status | `error` | When | `reason` |
|---|---|---|---|
| 413 | `too-large` | body > 12 MiB | `The file is larger than 12 MB.` + the unfill hint (§2.2) |
| 400 | `bad-request` | empty body / JSON body / bad name / `sha256` differs from the body | `The upload is empty.` / `Send the file itself, not JSON.` / `Name: 1 to 60 characters, at least one letter or digit.` / `The file changed since the preview: upload it again.` |
| 400 | `not-kicad` | wrong first token | `Not a KiCad board file (it starts with "<20 chars>"): choose a .kicad_pcb file (KiCad 5 to 10).` |
| 400 | `not-kicad` | `footprint`/`module` root | `This is a KiCad footprint (.kicad_mod), not a board: choose the board's .kicad_pcb file.` |
| 400 | `not-kicad` | `kicad_sch` root | `This is a KiCad schematic, not a board: choose the board's .kicad_pcb file.` |
| 400 | `kicad-syntax` | reader error | `Line 1203, column 17: <what>. The file may be cut short or not saved by KiCad.` |
| 400 | `too-complex` | a limit of §2.2 | `The board file is too complex to read here: <limit>.` + the unfill hint |
| 400 | `no-origin` | save of a board with origin `estimated` and < 2 fiducials (§2.6) | `This board has no outline (Edge.Cuts) and fewer than 2 fiducials, so its origin cannot be found on the machine. Add the board outline in KiCad and upload again.` |
| 409 | `refused` | a task runs (`ctx.requireEngineIdle`, A/ApiContext.java:88), for preview, save and attach | `Upload the board when the run has finished.` |
| 409 | `busy` | another preview, save or attach is running | `Another board file is being read: try again in a moment.` |
| 409 | `exists` | slug taken, `replace` 0 | `A board named "ftp" exists (imported 13:02): replace it?`, details `exists` |
| 409 | `preview-stale` | the save's allocation differs from the preview's `decisions` | `OpenPnP's packages or parts changed since the preview: check the new preview, then save.`, details = the new preview |
| 409 | `ids-exhausted` | no free id up to `~99` | `R_0603_1608Metric already has 99 variants in OpenPnP: remove unused packages in OpenPnP's Packages tab.` |
| 409 | `refused` | the EDT did not start the transaction within 10 s (§3.6) | `OpenPnP's window did not respond within 10 s: nothing was changed. Try again.` |
| 409 | `refused` | attach: `board` is not a listed board file, or its placements no longer match | `<file> is not in OpenPnP's board list.` / `The board file no longer matches this KiCad file (R7 moved 2.1 mm): upload it as a new board instead.` |
| 500 | `internal` | a step of the transaction failed | `Could not save the board: <cause>. Nothing was changed.` (the transaction undid every step, §3.6) |

Non-GET requests already get the Origin / `Sec-Fetch-Site` checks (A/ApiServer.java:355-374); a same-origin `fetch`
from the page passes them.

### 3.3 Upload encoding, size limit and the ApiServer changes (`A/ApiServer.java`)

Today every request body is read before routing, capped at 2 MiB for every route and turned into a String
(A/ApiServer.java:46, :278, :389-402). mobo wrapped in a JSON string is 1.57 MB, close to the cap. Changes:

1. **Per-route body limit.** `void route(String method, String regex, Handler h, int maxBodyBytes)` (the existing
   3-argument form keeps `MAX_BODY`). `handle` finds the limit of the first route whose pattern and method match
   before reading (`bodyLimit(method, path)`, else `MAX_BODY`), rejects `Content-Length` above it with 413 at once,
   and caps the read at it. The three import POSTs use 12 MiB.
2. **Raw bytes.** `Request` keeps `byte[] raw`; `public byte[] bytes()`; `json()` decodes UTF-8 lazily
   (A/ApiServer.java:181, :197-199 today).
3. **Response headers.** `Response` gets `final Map<String, String> headers` and `public Response header(String, String)`;
   `send` applies them after its defaults, so a handler can override `Cache-Control` (no-store today for every
   response, A/ApiServer.java:455).
4. **Registration**: `new BoardImportEndpoints(context).register(this)` in the constructor (A/ApiServer.java:111-117).

The browser sends the `File` object as the body with `fetch` (`connect-src 'self'`), not through `IL.api`, which is
JSON-only with a 10 s timeout (W/core.js:454-479): the dialog uses a 60 s timeout. No compression (localhost).

### 3.4 Gates and concurrency (`B/BoardImporter.java`)

- **Engine idle.** Preview, save and attach call `ctx.requireEngineIdle(ctx.availableSvc())` first
  (A/ApiContext.java:88): no parse ever runs in the JVM while it streams a dispense, registration or probe task. The
  Upload button is greyed with the same reason through `IL.gate` (W/core.js:683; §5.9).
- **One pipeline at a time.** `static final Semaphore PIPELINE = new Semaphore(1)`, `tryAcquire()` without waiting →
  409 `busy`, held for the whole preview, save or attach. The API thread pool is unbounded
  (A/ApiServer.java:104-110), so this also bounds memory: at most one 12 MiB file, one ≤ 1.5 M-item tree and one art
  build exist at a time. Two saves can therefore never allocate ids concurrently.
- **No OpenPnP objects in the preview**: `new Part(id)` registers a configuration listener that is never removed
  (O/model/Part.java:84-92, fired at once on a loaded configuration, O/model/Configuration.java:396-410). The preview
  reads ids and footprints only.

### 3.5 Packages and parts: one id allocator (`B/IdAllocator.java`)

OpenPnP's package and part ids are case-insensitive (upper-cased keys, O/model/Configuration.java:656-661,
:680-685), and `addPackage`/`addPart` silently replace an existing id (:667-673, :691-697). So ids are allocated by one
object per preview or save, against one snapshot:

- **`taken`**: a case-insensitive set holding every package id of the configuration plus every id chosen so far in
  this import (likewise for parts).
- **Packages**, in file order, one per variant (§2.8). Candidates `baseId`, `baseId~2`, … `baseId~99`, one counter per
  base shared by the in-file variants and the configuration:
  - chosen earlier in this import → next candidate;
  - in the configuration and its footprint matches this variant's signature (same pad count and names; x, y, w, h
    within 0.01 mm after unit conversion; rotation within 0.01°; roundness within 0.5) → **reuse** (stop);
  - in the configuration and different → next candidate;
  - free → **new** (stop);
  - none left → 409 `ids-exhausted`.
  - The state is `suffixed` when the id is not `baseId`.
  - Example: the configuration has a pad-less `K` (the live ftp case, `~/.openpnp2/packages.xml:83-102`) and the file
    has two variants of `K`: variant 1 → `K~2`, variant 2 → `K~3`. Before this rule both came out as `K~2`.
- **Parts**, keyed by (final package id, cleaned value), in file order. Candidates `<packageId>-<value>`, `~2`, …
  `~99`: in the configuration with the same package (id equal, case-insensitive) → **reuse**; chosen earlier in this
  import for the same key → that part; chosen for another key, or in the configuration with another package → next;
  free → **new**. Example: `SOT-23` + Value `5-X` and `SOT-23-5` + Value `X` both spell `SOT-23-5-X`; the second
  becomes `SOT-23-5-X~2`.
- **`decisions`** = the first 16 hex digits of the SHA-256 of the ordered `(package id, state)` and `(part id, state)`
  lists plus the slug's `exists` state. The preview computes it on its snapshot; the save recomputes it inside its
  transaction and answers 409 `preview-stale` (with the new preview) if it differs.
- **Right before each add** (inside the transaction): `getPackage(id) == null` / `getPart(id) == null`, else the
  transaction aborts with nothing changed (it can only fail through a bug, since allocation and adds share one EDT call).
- An existing package is **never changed** (brief): other boards' plans depend on its pads. Adopting a pad-less
  package would move ftp.board.xml's LED dots by 0.0375 mm (the KiCad LED pads sit at ±0.7875 mm, the chip-0603 type's
  pitch is 1.65 mm, D/DispenseDb.java:210-212) and change OpenPnP's fiducial vision (OQ3).
- The `~` separator keeps the seeded patterns matching: `sot-23`'s `SOT-?23(-3)?($|[^0-9-])` (D/DispenseDb.java:219)
  would stop matching `SOT-23-2`, but matches `SOT-23~2`; `chip-0603`'s `(^|[^0-9])0603([^0-9]|$)|1608Metric` matches
  `R_0603_1608Metric~2`.
- New packages: `new Package(id)`, description `KiCad <lib>:<name> · imported <date> from <cleaned file name>`,
  footprint units mm, pads from `PadP`, `bodyWidth`/`bodyHeight` from F.Fab (else courtyard). New parts:
  `new Part(id)`, `setPackage`, height 0, like OpenPnP's importer (O/gui/importer/KicadPosImporter.java:144-153).
- In the test configuration (SimMachine loads the default packages, src/main/resources/config/packages.xml:21 has
  `R0603`), an ftp import gets exactly ftp.board.xml's part ids. With a copy of the live configuration it gets `~2`.

### 3.6 OpenPnP configuration: threads, the save transaction, persistence

**Threads.** The configuration maps are plain `LinkedHashMap`s (O/model/Configuration.java:118-123), and Swing tables
listen to them: BoardsPanel to `boards` (O/gui/BoardsPanel.java:106-111), PackagesTableModel and PartsTableModel to
`packages`/`parts` (O/gui/tablemodel/PackagesTableModel.java:49; O/gui/tablemodel/PartsTableModel.java:58;
O/gui/support/PartsComboBoxModel.java:42). New `B/EdtRunner.java`:

```java
/** Runs c on Swing's event thread when OpenPnP's window exists and this is another thread; otherwise here. */
public static <T> T call(Callable<T> c, long startTimeoutMs) throws Exception
```

- "Window exists" = `MainFrame.get() != null` (O/gui/MainFrame.java:172-174). Headless (ApiServerSimTest) → on the
  calling thread, under one global `HEADLESS_LOCK`, so headless calls are serialised as the event thread would
  serialise them (a save and a `GET /api/boards` never interleave).
- If the event thread has not **started** the task within `startTimeoutMs` (10 s), the task is cancelled before it
  runs (a CAS on a `started` flag) and `IllegalStateException` → 409. Once started, the caller waits for it.
- **Every configuration read or change** of this design goes through `EdtRunner.call`, and so do the existing reads
  the import now races with (shared files, §10):
  - the import: the snapshot for the allocator and pad types (preview), the whole save transaction, attach;
  - `DispenseEndpoints.boards()`: the loops over `getBoards()` and the job's board locations
    (A/DispenseEndpoints.java:505-520; `getBoards()` copies a LinkedHashMap, O/model/Configuration.java:732-733, which
    can throw while the EDT puts);
  - `DispenseEndpoints.currentBoardLocation` (A/DispenseEndpoints.java:668-679, every run and plan check) and
    `DispenseService.planBoardFile` (D/DispenseService.java:574-579): both call `getBoard`, which adds to the list.
    Both also refuse a path that is not an existing file first (404 `Board file not found: <path>`), because
    `getBoard` would otherwise **write an empty board file** there and list it (O/model/Configuration.java:872-878);
  - `BoardArtStore` never reads the configuration itself: its callers pass Board objects read inside the same
    EdtRunner call, and fallback art is built inside that call (≈ 10 ms for ftp).

**The save transaction** (`B/BoardImporter.save`):

A. On the API thread, under `PIPELINE`: pipeline (§3.1) → proposal + art bytes; check `sha256` and `name`; stage the
   upload and the art under `<root>/.staging-<random>/` (temp files).

B. One `EdtRunner.call(…, 10 s)`; each step records what it did, for the undo:

1. allocate (§3.5) on the live configuration; its digest must equal `decisions`, else 409 `preview-stale`;
2. slug check: taken and `replace` 0 → 409 `exists`; `replace` 1 → keep `prev` = the listed Board at the final path;
3. create and add the packages, then the parts (re-check before each add, §3.5); record the added ids;
4. build the Board **here** (Part objects exist, `Placement.setPart` fires `Part.setPlacementCount` on this thread,
   O/model/Part.java:216-218): `new Board(file)`, `setName`, `setDimensions`, one `new Placement(id)` per placement with
   `setType`, `setSide(Top)`, `setLocation` (mm, z 0), `setPart`, `setEnabled` (O/model/PlacementsHolder.java:130,
   :150, :245; O/model/Placement.java:129-190); serialize it with `createSerializer()` to bytes plus `'\n'`, as
   `serializeObject` does (O/model/Configuration.java:890-899); write the staged `board.xml`; `dispose()` this off-list
   Board (O/model/PlacementsHolder.java:106-114);
5. **read back** the staged board as `loadBoard` does (:1547-1553): `createSerializer().read(Board.class, staged)`.
   Every placement id, type and part id must equal the intended ones (`Placement.commit` resolves parts through the
   configuration, O/model/Placement.java:110-115, so the parts must already be added), else abort;
6. move into place: on replace, `<slug>.art.json` and `<slug>.kicad_pcb` move to `<slug>/history/<oldArtId>.*` first;
   then `ATOMIC_MOVE` (as D/DispenseConfig.java:429-430) of the staged `.kicad_pcb`, `.art.json`, and `.board.xml` last;
7. `readBack.setFile(final canonical file)`, `setDirty(false)` (so `saveBoards`/`removeBoard` never open a dialog,
   :854-860, :1018-1042), `configuration.addBoard(readBack)` (:826-830, replaces `prev` under the same file). `prev` is
   `dispose()`d unless the job open in OpenPnP holds it (any board location of `currentJob()` whose `getBoard()` or its
   definition is `prev`); such a job keeps its old copy until it is reopened (documented);
8. `configuration.saveLibraryFiles()` (below): packages.xml, then parts.xml, then boards.xml;
9. `BoardArtIndex.put(…)` (§3.7): `index.json` written atomically.

   On an exception in any step: remove the added parts, then packages, by their exact ids (`removePart`,
   `removePackage`); put `prev` back with `addBoard(prev)`; move the history files back; delete the files moved in that
   did not exist before; 500 `Could not save the board: <cause>. Nothing was changed.` If step 8 had already written
   packages.xml or parts.xml, those files hold the added ids that memory no longer has: harmless (nothing refers to
   them) and rewritten by the next save of either kind. The log says which files.

C. On the API thread: delete the staging directory; `BoardArtStore.invalidate()`.

Headless, B runs on the API thread under `PIPELINE` and `HEADLESS_LOCK`.

**Persistence.** Only `Configuration.save()` writes packages.xml, parts.xml and boards.xml (:593-637). It also writes
machine.xml and asks about every dirty board with a JOptionPane (:970-978, :1018-1042). New public method in the fork
(`O/model/Configuration.java`):

```java
/**
 * Instance Labs: writes packages.xml, parts.xml and boards.xml only, in that order (backups as save() makes them,
 * temp file + atomic move), without dialogs; never machine.xml or board files. Each file is serialized to bytes and
 * read back with createSerializer() first; if the ids read back differ from the ids in memory, it throws before
 * anything is moved. Call on the event thread when the window exists.
 */
public synchronized void saveLibraryFiles() throws Exception
```

It reuses `createBackedUpFile` (:639-654) and the holders (`PackagesConfigurationHolder`, `PartsConfigurationHolder`,
`BoardsConfigurationHolder`, :1598-1602) with `createSerializer()` (:1555-1561); the board list is
`new ArrayList<>(boards.keySet())` without the `confirmSaveOfModified` loop of `saveBoards` (:970-978). Side effects
(OQ5): other in-memory package or part edits made in OpenPnP's window are written too (as OpenPnP's own Save would),
and **every board in OpenPnP's list is persisted in boards.xml**, including board files that an API plan added in
passing (`planBoardFile` → `getBoard`, Configuration.java:880-887): they appear in OpenPnP's Boards tab after a restart,
as they would after OpenPnP's own Save.

**Singletons per configuration.** `BoardArtStore` and `BoardArtIndex` are keyed by
`Configuration.get().getConfigurationDirectory()` and rebuilt when it changes: tests re-initialise the configuration
per test (T/dispense/SimMachine.java:33-35).

### 3.7 Storage, index, history, slug, staleness (`B/BoardStore.java`, `B/BoardArtIndex.java`)

```
<configDir>/instancelabs/boards/
    index.json                     {"version": 1,
                                    "boards": {<geometry digest>: {"artId", "slug", "how": "import"|"attach", "file", "atMs"}},
                                    "arts":   {<artId>: "<slug>/<slug>.art.json" | "<slug>/history/<artId>.art.json"}}
    <slug>/<slug>.board.xml        OpenPnP board (placements + fiducials, dimensions = Edge.Cuts box); import only
    <slug>/<slug>.art.json         the current board art (§4.1) with the import record
    <slug>/<slug>.kicad_pcb        the current upload, unchanged
    <slug>/history/<artId>.art.json, <artId>.kicad_pcb   what a replace displaced (older plans still draw it)
```

- `configDir` = `Configuration.getConfigurationDirectory()` (O/model/Configuration.java:198). In sim sessions it is the
  session's copy, so the live `~/.openpnp2` is never touched (src/test/sim/il_sim_acceptance.py:156-170).
- **Geometry digest** of a Board (`BoardArtIndex.digest(Board)`): the first 16 hex digits of the SHA-256 of its
  dimensions and, per placement sorted by id, `id|type|side|x|y|rotation` in mm at 4 decimals. It leaves out
  `enabled` and the part, so toggling D1–D40 or a byte-for-byte copy (uinav copies every listed board to
  `uinav-boards/NN-<name>`, `~/instancelabs/openpnp-uinav/python/uinav.py:307-331`) keep the drawing; any moved, added
  or removed placement (FID1's hand move included) loses it. It is computed from the Board object, not the file bytes,
  so OpenPnP re-saving an unchanged board does not matter.
- **Slug** from the operator's name only: NFKD, ASCII letters and digits lower-cased, every other run → `-`, trimmed,
  at most 40 characters, non-empty. The resolved directory must be inside the root (checked on the canonical path).
  The board's `name` attribute is the cleaned name (1–60 characters).
- **Overwrite**: a taken slug answers 409 `exists` unless `replace: 1`; the UI asks with a two-press (§5.8). Replacing
  moves the current art and upload to `history/` and writes the new files (§3.6 step 6). Refused while a task runs
  (§3.4). History is never pruned automatically (a few hundred KB per replace).
- **Old plans become stale**: the run check compares `plan.boardFingerprint` with the board as it is now
  (A/DispenseEndpoints.java:707-712), read from the configuration's Board object (:668-679). Since §3.6 replaces that
  object, changed placements are caught. Changed pads, packages or parts are **not**: the fingerprint covers only
  placement id, side, type, enabled and location (D/DispenseService.java:408-421). New `D/BoardContentDigest.of(Board)`
  appends, per placement, the part id and package id, and per distinct package a digest of its footprint (units, and
  each pad's name, x, y, w, h, rotation, roundness at 4 decimals); `boardFingerprint` calls it in one line. The run
  refusal text stays (A/DispenseEndpoints.java:710-711).
- **Registration** (AR). AR keys registrations by `file:<absolute path>` (AR:528) and checks only the key and the
  nominal seat (`matches`, AR:543); `POST /api/plan` applies a stored registration by default (`registration: "auto"`,
  AR:611-613). A replace or a hand edit that moves a fiducial would then plan with an affine fitted to the old
  fiducial positions. This design asks AR (§10.2) that `BoardRegistration.matches` also require every stored fiducial
  board coordinate (`Fid.bx/by`, AR:534) to equal the board's current fiducial placement within 1e-4 mm. That covers a
  replace and a hand edit in OpenPnP without any hook; a replace that keeps every fiducial in place keeps a
  registration that is still physically right. The test is in §6.5 item 12.
- **Deleting** an imported board is out of scope: OpenPnP's Boards tab removes it from the list; the files stay.

### 3.8 How the board appears in `GET /api/boards` and in plans

`GET /api/boards` (A/DispenseEndpoints.java:498-522) lists `Configuration.getBoards()` (read through `EdtRunner`,
§3.6), so a saved board appears in `files[]` at once. A new `A/BoardListView.addTo(map, board)` adds, in one call per
entry of `files[]` and `job[]`:

```json
{"name": "FTP test board", "file": "/…/instancelabs/boards/ftp/ftp.board.xml", "placements": 83,
 "widthMm": 75.0, "heightMm": 75.0, "origin": "outline",
 "fiducials": [{"id": "FID1", "bx": 62.5, "by": 5.0}, {"id": "FID2", "bx": 12.5, "by": 70.0}, {"id": "FID3", "bx": 62.5, "by": 70.0}],
 "art": {"id": "4b7d0e19a2c3f856", "kind": "kicad", "note": null},
 "imported": {"slug": "ftp", "atMs": 1793000000000, "sourceName": "ftp.kicad_pcb", "kicadVersion": 20240108, "how": "import"}}
```

`fiducials` are the top, enabled Fiducial placements (board mm), used by "Camera is on FID1" (§5.9). `origin` is null
for boards without KiCad art. `art.kind` is `fallback` for other boards (§4.3); `art.note` explains a fallback for a
board that had a drawing (`The board file changed since the KiCad drawing was saved.`). `imported` is null for other
files. The board is planned like any file: `POST /api/plan {source:"file", file, x, y, z, rotation}`
(A/DispenseEndpoints.java:571-583) → `planBoardFile` (D/DispenseService.java:574-579). The plan view gains the `board`
block of §4.5.

### 3.9 Pad-type resolution summary (preview and save)

- The resolver is the planner's: `new PadTypeResolver(db.currentPadTypes(), db.packageMappings())`
  (D/DispenseService.java:556-559), with the **final** package id (suffix included).
- For every aperture: `explain(packageId, x, y, area)` (§4.5) with the footprint pad centre and
  `DotMath.padAreaMm2(w, h, roundness)`, as the extractor does (D/BoardPadExtractor.java:64-77). Grouped per package:
  type, version, status, rule (`package-map`, `centre-pad`, `pattern`, `none`), the matched text, a plain sentence
  (§5.7), the pad count and `dispensable` (`status.atLeast(cfg.minPadTypeStatus())`, D/DispensePlanner.java:66, :94).
- **Links become actions after the save** (§5.8 state 6): the preview only names what is missing, because leaving the
  dialog loses the unsaved preview. Links encode the id: `'#profile/new-pad-type/' + encodeURIComponent(id)` (as
  W/core.js:987 already does); the router splits on `/` first and then decodes each part (W/core.js:2075-2076), so a
  package id containing `/` survives.
- `PACKAGE_MAPPING_LOST`: an explicit mapping (`package_pad_type`, exact id, D/PadTypeResolver.java:58-61) exists for
  `baseId` but not for the suffixed id, and the two resolve differently. The import never writes mappings (a mapping
  changes `padTypesFingerprint` and makes every plan stale, D/PadTypeResolver.java:27-37; OQ3).
- `QFN_WINDOWS`: a package whose paste-only apertures lie inside one copper pad and resolve as `qfn-dfn-perimeter`
  (the centre rule needs one pad at the origin ≥ 1.0 mm², D/PadTypeResolver.java:71-77). Nano IC3's 1.27 × 1.27 mm
  polygon (1.61 mm², at the origin) does become `qfn-centre`.

### 3.10 Attach the drawing to an existing board file (ftp.board.xml)

The board the user plans every day is ftp.board.xml, and an import of ftp would give it a twin with `~2` packages,
LED dots 0.0375 mm further in, and none of the registrations and surfaces keyed by its path (AR:528). Attach gives
the existing file the KiCad drawing and changes nothing else.

- **Candidates** (in every preview, `attach[]`): listed file boards whose placements match the proposal: the same set of
  ids among placements and fiducials (`enabled` ignored), each position within 0.5 mm and rotation within 0.5°.
  `offsets` lists every placement off by more than 0.05 mm. ftp.board.xml: one candidate, `maxOffsetMm` 0.3136,
  offsets `FID1 0.3136`.
- **`POST /api/boards/import/attach`**: same gates and pipeline; re-checks the match on the Board read inside
  `EdtRunner`; writes `<slug>/<slug>.kicad_pcb` and `<slug>/<slug>.art.json` (the art's `import.how` = `attach`,
  `import.board` = the board's path, `import.offsets`); puts `index.boards[digest(board)]`. No package, part, board file
  or boards.xml changes, so no plan becomes stale.
- The drawing is KiCad's: on ftp.board.xml the drawn FID1 copper sits 0.3136 mm from the board file's FID1 placement.
  The dialog says so before the attach ("FID1 is 0.31 mm from where the board file has it: the drawing shows KiCad's
  position"), and it is the question of §2.6 and OQ4 made visible.
- A later edit of the board file's placements changes its digest, so the drawing falls back with the note of §3.8.

---

## 4. Board art

### 4.1 JSON schema (`B/BoardArt.java`, Gson)

```json
{"format": "il-board-art", "version": 1, "kind": "kicad",
 "units": "mm", "frame": "board", "precision": 0.01,
 "size": [75.0, 75.0], "origin": "outline",
 "finish": {"mask": "slate", "silk": "white"},
 "source": {"name": "ftp.kicad_pcb", "bytes": 816950, "sha256": "9c1e…", "kicadVersion": 20240108,
            "generator": "pcbnew 8.0", "boardFile": null},
 "import": {"how": "import", "name": "FTP test board", "slug": "ftp", "atMs": 1793000000000, "importer": 1,
            "boardDigest": "a41c09e2b7d35f10", "board": null, "offsets": [],
            "counts": {…}, "packages": [{"id": "…", "state": "new"}], "parts": […], "warnings": […]},
 "outline": {"d": "M0 0H75V75H0Z", "cutouts": "", "closed": true},
 "layers": {
   "cu":      {"fill": "M…Z", "tracks": [{"w": 0.25, "d": "M10.2 3.1L12 3.1…"}]},
   "mask":    {"open": "M…Z"},
   "exposed": {"fill": "M…Z"},
   "paste":   {"fill": "M…Z"},
   "silk":    {"fill": "M…Z", "lines": [{"w": 0.15, "d": "…"}]},
   "holes":   {"pth": "M…", "npth": "M…", "via": "M…"},
   "fab":     {"lines": [{"w": 0.1, "d": "…"}]}},
 "texts": [{"t": "Functional Test Placement REV00", "x": 37.5, "y": 2.0, "a": 0, "h": 1.0, "anchor": "middle"}],
 "parts": [{"ref": "R1", "kind": "placement", "pkg": "R_0603_1608Metric", "value": "R_Small",
            "x": 37.5, "y": 42.5, "a": 90, "box": [-1.48, -0.73, 1.48, 0.73], "pads": 2,
            "label": {"x": 37.5, "y": 44.0, "a": 0, "h": 1.0}}],
 "fiducials": [{"name": "FID1", "bx": 62.5, "by": 5.0, "copperMm": 1.0, "maskMm": 3.0}],
 "thumb": {"toleranceMm": 0.1, "outline": "M…", "mask": "M…", "exposed": "M…"},
 "stats": {"points": 6123, "bytes": 98304, "toleranceMm": 0.01, "coarseToleranceMm": 0.01}}
```

- **Frame**: board millimetres, +Y up, origin at the bottom-left of the outline box (§2.6); the same frame as the
  board file's placements, so `toMachine` (§4.5) places it.
- **Paths** are SVG path data in board coordinates, numbers rounded to `precision` (0.01 mm), absolute commands
  (`M L H V A Z`), implicit `L` after the first pair. `fill` paths use the nonzero rule; `outline.d + outline.cutouts`
  is drawn with evenodd.
- `kind`: `kicad` or `fallback` (§4.3). `import` is present only in KiCad art; `source.boardFile` only in fallback art.
  `import.boardDigest` is the geometry digest (§3.7) of the board this art was saved for.
- `origin`: `outline` | `edges` | `estimated` (§2.6); null in fallback art.
- `finish`: the stackup colours of F.Mask and F.SilkS (§2.4), mapped by name: `Green`, `Red`, `Blue`, `Purple`,
  `Black`, `White`, `Yellow` → the palette keys of §5.3; anything else or absent → `slate` / `white`.
- `parts[].kind`: `placement`, `fiducial`, `dnp`, `pasteOnly`, `art`, `bottom` (bottom-side parts with drilled pads:
  hit rects only, box = their pad box). `box` is the body box in the part frame (x0, y0, x1, y1). `label` is the
  Reference's KiCad position, angle and height when it is on F.SilkS and not hidden; else null (§5.3).
- `layers.exposed`: copper the operator sees: pad and fiducial copper inside its mask opening (the pad shape when the
  mask margin is ≥ 0, else the opening), and the annuli of untented vias. Drawn in the finish colour above the mask, so
  the render needs no clip path.
- `paste` = the import's apertures (upload preview and thumbnails; hidden under a plan, whose pads are the apertures).
- `texts`: board-level `gr_text` on F.SilkS only (`\n` → several lines; cleaned with `KText.clean(…, 200)`; `${…}`
  and `<<hash>>` kept as written). Footprint texts are not drawn as silkscreen: each part gets one reference label.
- `fiducials`: from the fiducial footprints: `copperMm` = the round pad's diameter (else min(w, h)), `maskMm` =
  copper + 2 × the resolved mask margin (ftp 1.0 / 3.0, mobo 2.0 mask).
- `thumb`: outline, mask openings and exposed copper simplified at 0.1 mm (a few KB) for the thumbnail row (§5.9).

### 4.2 Generation from KiCad (`B/BoardArtBuilder.java`, `B/ArtPath.java`)

Input: `KicadPcb`, `BoardFrame`, `ImportProposal`, the `Deadline`. Everything is transformed to the board frame first
(§2.6). "Every footprint" means top **and** bottom (§2.7); a via counts only when its layer pair includes F.Cu
(through vias and blind vias from the top; buried vias are not on the top).

| Layer | Content |
|---|---|
| `outline` | the outline loop and the cut-out loops; arcs flattened with a chord error ≤ 0.01 mm (≥ 1° steps, §2.2) |
| `cu.fill` | F.Cu and `*.Cu` pad shapes of every footprint (rect, roundrect and oval with `A` arcs, circle, trapezoid and custom as polygons, chamfers cut), `filled_polygon`s of zones on F.Cu (`F&B.Cu`, `*.Cu`, lists; keepouts skipped), filled F.Cu graphics of top footprints and the board, via annuli |
| `cu.tracks` | `segment`/`arc` on F.Cu and unfilled F.Cu graphics, grouped by width (0.01 mm) into one path per width |
| `mask.open` | pads of every footprint on F.Mask/`*.Mask` grown by the mask margin (pad > footprint > `pad_to_mask_clearance`, the §2.5 zero rule; growing a rect gives corner radius = margin), filled F.Mask graphics, stroked F.Mask lines as stadium polygons, untented vias (§2.4) |
| `exposed.fill` | §4.1 |
| `paste.fill` | the apertures of §2.8 as placed on the board |
| `silk.fill` / `silk.lines` | F.SilkS filled polygons, rects and circles / lines, arcs, unfilled shapes grouped by width (top footprints and the board; bottom footprints have B.SilkS) |
| `holes.pth` / `npth` / `via` | drills of every footprint as circles (`A` arcs) or stadiums (oval drills), with the pad offset; via drills |
| `fab.lines` | per top footprint its F.Fab graphics, else its F.CrtYd, else the pad box grown 0.2 mm |
| `texts`, `parts`, `fiducials`, `thumb` | §4.1 |

- **Two tolerances.** The layers that must line up with the camera and the overlay (outline, `exposed`, `mask.open`,
  `paste`, holes, fiducials) are always simplified at 0.01 mm. Only the **coarse** layers (`cu.fill`, `cu.tracks`,
  silkscreen, fab) are coarsened when the art is too large.
- **Simplification**: iterative Douglas–Peucker (an explicit stack, no recursion) at the layer's tolerance on every
  polygon and polyline; polygons below 0.0004 mm² are dropped. The survey gives, at 0.01 mm: ftp F.Cu fill
  12,675 → 4,753 points, mobo F.Cu 10,943 → 5,793, nano F.SilkS 14,403 → 1,938 (≈ 56, 68 and 23 KB of path text).
- **Merging**: each layer is one path (tracks and lines one per width), so a board is ~15–25 path elements whatever
  its size.
- **Budget**: target ≤ 400 KB. Above it the coarse tolerance doubles (0.02, 0.04, 0.08 mm) until it fits
  (`ART_SIMPLIFIED`, info). Above 2 MB at 0.08 mm, or when the generated points would exceed 2,000,000 (§2.2), the
  tracks and then the zone fills are dropped (`ART_REDUCED`, warn). Zones without any `filled_polygon` are not drawn
  (`ZONES_UNFILLED`, info). Expected sizes: 100–200 KB for each fixture.
- **Deterministic**: the same input gives the same bytes (stable ordering, `Locale.ROOT` formatting, −0 → 0), so the
  content id (§4.4) is stable.

### 4.3 Fallback for boards without KiCad art (`B/BoardArtFallback.java`)

For any board without an index entry (hand-made files such as ftp.board.xml before an attach, OpenPnP job boards):

- **Size and outline**: `dimensions` when both are > 0 (ftp.board.xml:2: 75 × 75 → `M0 0H75V75H0Z`); otherwise the
  convex hull of the pads and fiducials grown by 2 mm, `size` = its box.
- **Copper, mask, exposed**: the pads of `new BoardPadExtractor(resolver, true).extract(bl)` with `bl` at
  `Location(mm, 0, 0, 0, 0)` (there the global pad centres are board coordinates; the same trick as AR:333-336) as
  rounded rectangles in `exposed`; mask openings = the pads grown by 0.05 mm.
- **Fiducials**: placements of type Fiducial, top, enabled: the first round footprint pad, else
  `(?i)fiducial[_-]?(\d+(?:\.\d+)?)mm` / `(?i)mask(\d+(?:\.\d+)?)mm` on the package id (AR:326-330), else 1.0 / 3.0 mm.
- **Parts**: per placement, the box of its pads grown by 0.3 mm; `kind` from type and enabled. `thumb` as §4.1.
- No tracks, zones, silkscreen or holes. `kind: "fallback"`; the render shows "Drawn from the board file (no KiCad
  drawing)" in the Board step and the inspector, plus `art.note` when there is one (§3.8).
- Built inside the caller's `EdtRunner` call (§3.6). The resolver comes from `DispenseService.get()`
  (D/DispenseService.java:109); without the service, footprint pads only.

### 4.4 Endpoint, store and what the art offers AR (`B/BoardArtStore.java`, `A/BoardImportEndpoints.java`)

- **`GET /api/board-art/{artId}`** (`[0-9a-f]{16}`): the art bytes, `application/json; charset=utf-8`,
  `Cache-Control: private, max-age=31536000, immutable` (the header override of §3.3). Unknown id → 404 `art-gone`
  ("This board drawing is no longer known: plan the board again"); the view then keeps drawing without art.
- **`artId`** = the first 16 hex digits of the SHA-256 of the art bytes (content address: the immutable cache is safe).
- `BoardArtStore`, one per configuration directory (§3.6), synchronized. It never reads the configuration: callers pass
  a `Board` read inside their `EdtRunner` call.
  - `ArtRef refFor(Board b)` → `{id, kind, note}`: `index.boards[digest(b)]` → that art (`kicad`). Otherwise fallback
    art, built and cached (LRU of 64, keyed by the geometry digest, the resolver fingerprint and a digest of the used
    packages' footprints); `note` = `The board file changed since the KiCad drawing was saved.` when the index has an
    entry for the same file path under another digest.
  - `byte[] bytes(String id)`: `index.arts[id]` → that file (its SHA-256 prefix must equal `id`), else the fallback LRU,
    else null. Files in `history/` stay servable, so an old plan or record keeps the drawing it was planned with.
  - `Imported imported(Board)`: the `import` and `source` blocks (for `GET /api/boards`).
  - **`List<KeepOut> keepOuts(Board)`** → `{x, y, r}` in board mm, the format AR decided (AR §16 item 10,
    AR:2601-2604), KiCad art only (fallback → empty):
    - every footprint, top or bottom: PTH r = half the larger side of its F.Cu copper (else drill/2 + 0.25), NPTH
      r = drill/2; vias whose pair includes F.Cu: r = size/2;
    - the outline and every cut-out as a chain of circles r = 1.0 mm, one at least every 1.0 mm along the boundary;
    - the area inside the board box but outside the outline, and inside cut-outs, covered by circles r = 0.75 mm on a
      1.0 mm grid (a circle of radius ≥ 0.71 mm covers its 1 mm cell);
    - ftp: 6 PTH (H1–H4 3.2 mm mounting holes, J1's 2 pins), the rectangular outline chain, no fill circles; nano adds
      J1/J2's 30 bottom-side header pins and 4 cut-outs; mobo adds the arcs' corner areas.
  - `List<FidArt> fiducials(Board)` → `{name, bx, by, copperMm, maskMm}` (KiCad art only), for AR's BoardFeatures
    (§10.2): today it parses the mask from the package id or defaults to 3.0 mm (AR:326-330), wrong for fiducial
    footprints named otherwise (mobo's 2.0 mm masks).
  - `List<double[]> outlineLoops(Board)` (board mm, outline first, then cut-outs): offered to AR as an exact allowed
    region instead of the circles (OQ10).
- The client keeps **no** cache of its own: `fetch(url, {cache: 'default'})` uses the browser's HTTP cache, which the
  immutable header makes exact. `IL.api` uses `cache: 'no-store'` (W/core.js:459) and is not used for art. The art is
  never embedded in the plan, which the page re-reads every 10 s (W/core.js:738).

### 4.5 Plan additions (engine and API)

**`D/DispensePlan.java`** (new fields; `copyBoardFieldsTo(DispensePlan sub)` copies them, called by
`A/PlanStore.subset`, A/PlanStore.java:90-170, in one line):

```java
/** Board frame (mm, +Y up) → machine (N2) mm: AffineTransform.getMatrix order m00 m10 m01 m11 m02 m12 (as AR's BoardRegistration.affine). */
public double[] boardToMachine;
public String boardFile;                 // absolute path of the board definition, null if none
public double boardWidthMm, boardHeightMm;
public String boardTransformStatus;      // OpenPnP PlacementsTransformStatus of the location planned
public final List<BoardFiducial> fiducials = new ArrayList<>();
public static class BoardFiducial { public String id, partId; public double bx, by, xMm, yMm; }
/** The board art when the plan was made (never re-resolved: a later replace or edit cannot put other art under these dots). */
public String boardArtId, boardArtKind, boardArtNote;
// PlannedPad gains:
public String padName, partId, typeRule, typeRuleDetail, typeRuleMatched;
public double roundness;
```

**`D/DispensePlanner.planBoard`** (D/DispensePlanner.java:43-50) fills the geometry from the `BoardLocation` it plans:

- `boardToMachine` maps three points through `Utils2D.calculateBoardPlacementLocation(bl, Location)`
  (O/util/Utils2D.java:213-218, :225-284), the call the extractor uses for every pad (D/BoardPadExtractor.java:126):
  `o = T(0,0)`, `ex = (T(100,0) − o)/100`, `ey = (T(0,100) − o)/100` → `[ex.x, ex.y, ey.x, ey.y, o.x, o.y]`. The
  art, the pads and the dots therefore agree by construction for the nominal location (O/util/Utils2D.java:105-119),
  OpenPnP's fiducial transform and AR's registration (`applyTo` sets the transform on the planned copy, AR:553-563).
  The critique checked this 3-point map is exact for OpenPnP's transform (Utils2D.java:250-283), and that the SVG
  `matrix(m00 −m10 m01 −m11 m02 −m12)` of §5.2 is the same map with machine Y drawn down.
- `fiducials`: placements of type Fiducial, top, enabled; `xMm, yMm` through the same call.
- Pad fields copied from `PadGeometry` (today only w and h are copied, D/DispensePlanner.java:71-81, although the area
  uses roundness, :103).

**The art id is fixed at plan time.** `DispenseEndpoints.plan` (A/DispenseEndpoints.java:558-592), inside the
`EdtRunner` call that reads the board it plans (§3.6), sets `plan.boardArtId/Kind/Note` from
`BoardArtStore.get().refFor(board)` (job boards: `bl.getBoard()`). `GET /api/plan/{id}` rebuilds the view on every
read (A/DispenseEndpoints.java:594-598) and Records draws from it (W/records.js:257-282); both now show the art the
plan was made with.

**`D/PadTypeResolver.java`**: `Resolution explain(String packageId, double x, double y, double area)` with
`{PadType type; String rule; String detail; String matched}`; `resolve(...)` becomes `explain(...).type` with
identical behaviour (D/PadTypeResolver.java:54-79): `package-map` (detail = the mapped key), `centre-pad` (detail
`qfn-dfn-perimeter`), `pattern` (detail = the pattern, `matched` = the text the pattern found in the package id, from
`Matcher.group()`), `none`.

**`D/PadGeometry.java`** gains `partId`, `typeRule`, `typeRuleDetail`, `typeRuleMatched`; **`D/BoardPadExtractor.java`**
fills them in both paths (D/BoardPadExtractor.java:64-116). Its signature stays: AR's BoardFeatures and
SimBoardRenderer call `new BoardPadExtractor(resolver, true).extract(bl)` (AR:333-336, :1880).

**Per-dot times** (the path preview's clock):

- `D/GcodeProgram.java`: `public double[] dotArriveSeconds, dotEndSeconds;` (by plan dot index, seconds from the
  program start; next to `estimatedSeconds`, D/GcodeProgram.java:71).
- `D/GcodeStreamBuilder.java`: `dot(DotSpec, long)` (:360-392) records `lastDotArriveT = t` right after its XY move;
  `forPlan` (:162-193) stores `lastDotArriveT` and `b.t` after each `b.dot` (:188). Same estimate, no new model.
- `A/PlanStore.Entry`: `double[] dotArriveS, dotEndS`, set in `DispenseEndpoints.estimate` (A/DispenseEndpoints.java:632-646),
  which also runs for subsets (:717-718).

**Dot size**: `estimate` also sets `Entry.dotDiameterMm` = `DotLadder.of(svc.getDotLadder(cal.id)).diameterAt(rotation)`
(D/DotLadder.java:94-112; D/DispenseService.java:531-537) for the current calibration and the plan's turn, or null.

**`A/BoardPlanView.addTo(out, entry)`** (new; one call in `A/Views.plan`, A/Views.java:205-291) adds:

```json
"board": {"name": "FTP test board", "file": "/…/ftp.board.xml", "widthMm": 75.0, "heightMm": 75.0, "side": "Top",
          "toMachine": [1.0, 0.0, 0.0, 1.0, 120.0, 120.0], "transformStatus": "NotSet",
          "fiducials": [{"id": "FID1", "bx": 62.5, "by": 5.0, "x": 182.5, "y": 125.0}],
          "art": {"id": "4b7d0e19a2c3f856", "kind": "kicad", "note": null}},
"dot": {"diameterMm": 0.62, "source": "ladder"},
pads[i]: + "padName", "partId", "roundness", "typeRule": {"kind": "pattern", "detail": "…", "matched": "0603", "sentence": "…"}
dots[i]: + "ta": 12.41, "te": 13.02          (seconds, 0.01; absent when the estimate failed)
```

`dot.source` is `ladder` or `none` (`diameterMm` null). The plan list `GET /api/plans` (A/DispenseEndpoints.java:605-614)
is unchanged; Records fetches the full plan.

---

## 5. Render

### 5.1 Files and module boundaries

| File | Owner slice | Content |
|---|---|---|
| `W/boardview.js` | web-render | `IL.boardview`: two stacked SVGs (art, overlay; §5.2), picture modes, preview, selection, camera marker, `destroy()`. |
| `W/boardart.js` (new) | web-render | `IL.boardart`: `get(id)` (fetch through the HTTP cache), `build(art, {uid})` (art → SVG nodes), `thumbUrl(art, {w, h})` (from `art.thumb`, rendered once to a blob URL), `toSvgMatrix(m)`, `outlineBox(art, m)`, `originMarker(art)`. |
| `W/pathpreview.js` (new) | web-render | `IL.pathPreview.create({host, bv, button})`: the preview row and its clock (§5.6). |
| `W/padinspect.js` (new) | web-render | `IL.views.pad` and `IL.padInspect.open({padId} or {ref})` (§5.7). |
| `W/board.css` (new) | web-render | Board palette and overlay tokens (§5.3), casing, picture modes, preview row, inspector. Loaded after run.css; run.css is untouched. |
| `W/boardflow.js` (new) | web-flow | `IL.boardFlow`: the glue to Run and Records: `mountBoardStep`, `hero`, `onPad`, `onPart`, `recordsMap` (the hooks of §5.1.1); thumbnails, `#btn-board-use-fid`, the pre-plan hero, the stage-bar controls. |
| `W/boardimport.js` (new) | web-flow | `IL.boardImport.open({file?, onSaved})`: the upload dialog (§5.8) and the page-wide drop guard. |
| `W/boardimport.css` (new) | web-flow | The dialog, the thumbnail row, the Board-step additions. |
| `W/index.html` | web-render (wave 1); web-flow adds its 3 tags in wave 2 after web-render has merged | `board.css` after run.css, `boardimport.css` after it; `boardart.js` before `boardview.js`; `pathpreview.js`, `padinspect.js`, `boardflow.js`, `boardimport.js` after it (all `defer`, W/index.html:9-22). |
| `src/test/web/devproxy.py` | web-render | `--fixtures DIR` (§6.6). Owned by no AR slice. |
| `W/run.js`, `W/records.js` | AR web-ui (owner) | Only the five hook lines of §5.1.1. |

core.js and app.css are not changed (DEV_NOTES.md:7-9). New flat names match `[A-Za-z0-9_-]+\.(html|js|css|svg|png)`
(A/ApiServer.java:47). `BoardImportSimTest` checks that index.html loads each new file and that it is served
(ApiServerSimTest's explicit list, T/api/ApiServerSimTest.java:229-230, stays as it is).

#### 5.1.1 The five hooks in AR-owned files (each a no-op until `boardflow.js` loads)

| # | Where | Line |
|---|---|---|
| H1 | Board step body (W/run.js:1183-1191), after `.board-sel-row` | `if (IL.boardFlow) IL.boardFlow.mountBoardStep(body, {select: (sel) => {…today's onBoardSelect path…}, form: () => R.form, setXY: (x, y) => {…as useCam does, W/run.js:1175…}, loadBoards});` |
| H2 | Hero view creation (W/run.js:487-488) | `onPad: (id, ev) => IL.boardFlow ? IL.boardFlow.onPad(id, ev) : IL.panel.show('camera', …today…), onPart: (ref, ev) => IL.boardFlow && IL.boardFlow.onPart(ref, ev)` |
| H3 | Hero render (W/run.js:720-735), before the empty-state switch | `const art = IL.boardFlow ? IL.boardFlow.hero(bv, {plan: p, sel, form: R.form, mode, s, hero: n.hero, stageBar: n.stageBar, row: n.timeline}) : null;` then the svg and the stage bar show when `p \|\| (art && art.shown)` (today only with a plan, W/run.js:722, :733) |
| H4 | Records map (W/records.js:276-280), after `bv.setPlan` | `if (IL.boardFlow) IL.boardFlow.recordsMap(bv, plan);` |
| H5 | Records rebuild (W/records.js:294, inside the `IL.keyed` rebuild) | `bv.destroy()` on the replaced map view |

AR's web-ui slice is asked to add them (§10.2). If it does not, the integration slice adds exactly these lines after
AR's web-ui has merged.

### 5.2 `boardview.js`: API and structure

Today: one class, eight layers built once per planId (W/boardview.js:120-128, :141-218), an axis-aligned substrate from
the pad and dot box (:157-166), a 5 mm grid (:167-172), pads with a fixed 10 % corner (:180-190), dots with a
screen-sized radius (:196-203, :290), `update()` patching classes (:325-393), no `destroy()` (the ResizeObserver,
:133-136, leaks on every Records rebuild, W/records.js:276-280, :294). It uses `IL.svgEl`, `IL.fmt`, `IL.plural` and
`IL.setAttr` from core.js (W/boardview.js:23, :186, :397).

**API** (additions in bold; everything else keeps its meaning):

```
IL.boardview.create(host, {id, aria, onPad(padId, ev), **onPart(ref, ev)**, **lite**})   lite: no callouts/grid (inspector, dialog)
bv.setPlan(plan, {origin})        once per planId; reads plan.board.toMachine
**bv.setArt(art | null)**         builds the art SVG once per art.id; on re-plan only its transform changes
**bv.setBoard({art, toMachine} | null)**   art-only mode without a plan (upload preview, hero before planning)
**bv.setAffine(toMachine)**       art-only mode: the seat changed (form edits) → transform only
**bv.picture('full' | 'quiet' | 'off')**   §5.3
bv.update({done, dirty, next, check, needle, n1, probe, fids, **cam**})   fids: AR's layer (AR:1579-1584); cam: live top camera {x, y}
bv.layers(hidden)                 today's toggles: pads, dots, path, dirty (W/boardview.js:319-322)
**bv.preview(st | null)**         st {n, ghost: {x, y} | null}: dots [0, n) get .pv-pre (diffed), ghost moved
**bv.select({padIds, refs} | null)**   .sel on pads, part outlines in g.pv-l-sel
**bv.dotLabels(indexes | null)**  path numbers next to those dots (inspector)
**bv.fit(box | null)**            fixed view box in machine mm (inspector); null = today's view/zoom logic
**bv.destroy()**                  disconnects the ResizeObserver, removes both SVGs
```

**Structure**: two stacked SVGs in the host (`.pv-host`, `position: relative`):

| SVG | Role | Content (bottom → top) |
|---|---|---|
| `svg.pv.pv-art` | the board picture; `aria-hidden`, `pointer-events: none`, absolutely positioned over the overlay's box, same `viewBox` and `preserveAspectRatio`, `will-change: transform` (its own compositing layer), `data-mask` = the finish | `g.pv-l-art` with `transform="matrix(m00 −m10 m01 −m11 m02 −m12)"` (board mm, +Y up → SVG, which draws machine X and −Y, W/boardview.js:184, :265-268): `path.pv-a-sub` (outline + cut-outs, evenodd), `path.pv-a-cu`, `g.pv-a-tracks`, `path.pv-a-mask`, `path.pv-a-open`, `path.pv-a-exposed`, `path.pv-a-paste` (preview only), `path.pv-a-silk`, `g.pv-a-silklines`, `g.pv-a-text`, `path.pv-a-hole--pth/--npth/--via`, `g.pv-a-fab`, `path.pv-a-edge`, `g.pv-a-refs` (`text.pv-lb1/2/3`), `g.pv-a-origin` |
| `svg#plan-svg.pv` | the overlay, in flow, sized as today | `g.pv-l-board` (today's substrate + grid; hidden while art shows), `g.pv-l-parts` (same matrix; `rect.pv-part[data-ref]` hit rects, `pointer-events: auto`, `<title>`), `g.pv-l-hull`, `g.pv-l-path` (`.pv-path`, `.pv-path--done`, `.pv-path--pre`, `line.pv-path--leg`), `g.pv-l-pads` (`rect.pad`, `pointer-events: auto`, above the parts so a pad click wins), `g.pv-l-dots`, `g.pv-l-sel`, `g.pv-l-marks`, `g.pv-l-fids` (AR), `g.pv-l-probe`, `g.pv-l-cam`, `g.pv-l-ghost`, `g.pv-l-labels`, `g.pv-l-callouts` |

- No clip path and no cloned copper: exposed copper is its own path (§4.1).
- `applyViewBox`/`relayout` write the same `viewBox` on both SVGs. Polls, preview frames and hover touch only the
  overlay; the art repaints only when the view box changes.
- Records' `.rec-mapbox > svg.pv` rule (W/profile.css:291-292) positions both SVGs alike.

**Kept for the tests** (W/boardview.js:5-6; UI_DESIGN.md:229-232; PW:453-457, :564-569, :586): `#plan-svg`,
`rect.pad` (exactly the plan's pads), `rect.pad.dirty`, `circle.dot` (exactly the plan's dots), `circle.dot.done`,
`rect.pad.pasted`, `.check`, `.pv-next`, `.pv-first`, `.pv-path`, `.pv-path--done`, `.pv-hull`, `.pv-probe`,
`.pv-needle`. No art element uses the classes `pad` or `dot`, and casing nodes carry `pv-case` plus no state class, so
class counts are unchanged.

**Changes inside `setPlan`**:

- Pads: `rx = ry = min(w,h) × roundness / 200` when `roundness` is present (else today's 10 %); a richer `<title>`
  (`R12.1 · Chip 0603 (characterized) · 0.80 × 0.95 mm, corners 50 % · 1 dot`). No pad-type colour (§5.4).
- Dots: when `plan.dot.diameterMm` is known, `r = max(d/2, 1.2 px × k)` (a clamp adds `.pv--dots-clamped`); else today's
  `dotRadius()` and the legend says "dot size not measured".
- Boxes: `boardBox` = the transformed board rectangle `(0,0)–(widthMm,heightMm)` when known (before the art arrives),
  else today's box; `dotsBox` includes `plan.board.fiducials` (UI_DESIGN.md:233 promises it).
- `relayout` toggles the label size buckets (§5.3).

`update()` is unchanged in cost: it never touches the art SVG (§5.5).

### 5.3 Board palette, casing, picture modes, text

**One physical palette.** A board is a physical object, so its picture looks the same in both themes; only the stage
around it and the board edge line follow the theme (`[data-theme]` on `<html>`, W/core.js:77-91). Tokens are custom
properties in board.css, so a theme switch needs no rebuild. No state hue is used for the board itself
(DEV_NOTES.md:260 "Colour means state only").

| Token | Value | Use |
|---|---|---|
| `--pv-a-sub` | `#c8bd9a` | bare FR-4 (substrate, mask openings) |
| `--pv-a-cu` | `#a8783f` | copper under the mask |
| `--pv-a-mask` / `--pv-a-mask-op` | by `data-mask`: slate `#24424e` (default), green `#1f5a2e`, red `#8a1c1c`, blue `#1c3f8a`, purple `#4a2a6e`, black `#15181a`, white `#e9ecec`, yellow `#c9a81c` / `.86` | solder mask |
| `--pv-a-bare` | `#ddd5c1` | exposed copper (finish): pads, fiducials, untented vias |
| `--pv-a-silk` | white `#f3f3ee`, black `#15181a`, yellow `#e8d44d` (by `finish.silk`) | silkscreen |
| `--pv-a-hole` | `#121719` | drills |
| `--pv-a-edge` | light theme `#0e1416`, dark theme `#8ca2ab` | board edge (the only theme-dependent art token) |
| `--pv-a-fab` / `--pv-a-ref` | `rgba(255,255,255,.3)` / `rgba(255,255,255,.85)`; on white and yellow masks `rgba(0,0,0,.35)` / `rgba(0,0,0,.85)` | part outlines / reference labels |

**Overlay tokens** (used under `.pv--art`, identical in both themes; contrast against the finish `#ddd5c1` computed):

| Token | Value | Contrast on the finish | Use |
|---|---|---|---|
| `--pv-o-ap` | `#4d5559` | 5.21:1 | paste aperture outline |
| `--pv-o-dot` | `#1f5fd1` | 3.97:1 | dot ring, done dot, pasted pad stroke |
| `--pv-o-next` | `#a84c08` | 3.88:1 | next dot ring and halo |
| `--pv-o-block` | `#b42318` | 4.50:1 | blocked and dirty pads, dirty rings |
| `--pv-o-check` | `#7a5000` | 4.83:1 | in progress at STOP |
| `--pv-o-probe` | `#145e4b` | 5.26:1 | probe targets, probed area |
| `--pv-o-case-d` / `--pv-o-case-l` | `#0b1012` / `#ffffff` | 13.09 / 1.46 | the two casing tones |
| `--pv-o-ghost`, `--pv-o-path` | `#ffffff` core on the casing | – | preview needle, path |

**Rules.**

1. Marks inside a pad (paste apertures, dots, pad states) sit on the finish; their colours reach ≥ 3:1 against
   `--pv-a-bare` (table above).
2. Every other mark (next, check and first rings, needle, ghost, dirty rings, path, hull, probe targets, fiducial
   search circles and found dots, camera crosshair, selection outline, callout strokes) gets a **two-tone casing**:
   a light stroke (`--pv-o-case-l`, mark width + 3 px), then a dark one (`--pv-o-case-d`, + 1.5 px), then the coloured
   stroke. boardview's `cased(tag, attrs, cls)` makes the three nodes; only singular marks are cased (≤ ~30 extra nodes;
   dots are not). Computed: against every background of this palette one tone reaches ≥ 6.5:1 (light tone vs the
   mask composites: slate 8.16, green 6.56, red 7.67, blue 7.80, purple 8.73, black 13.45; dark tone vs the white mask
   15.17, the yellow mask 8.51, the finish 13.09; Quiet: dark tone 8.89 on the light-theme mask, light tone 13.55 on
   the dark one).
3. **Pad and dot states are restated** in board.css under `.pv--art`, because today's theme tokens (W/run.css:78-81)
   fail on the board (computed: `--live` 1.44:1 and `--block` 1.24:1 on the slate mask; `--need-dot` 1.48:1 on the
   finish; dark-theme `--block` 1.07:1 on the finish): aperture = no fill, `--pv-o-ap` 1 px; blocked = `--pv-o-block`
   dashed, fill `rgba(180,35,24,.15)`; dirty = fill `--pv-o-block`; pasted = fill `rgba(31,95,209,.18)` + stroke
   `--pv-o-dot` (a done dot on it: 3.16:1); check = fill `rgba(122,80,0,.15)` + stroke `--pv-o-check`. Dots: undone =
   fill `rgba(31,95,209,.15)` + ring `--pv-o-dot`; done = fill `--pv-o-dot`. Never white (rev 1's white-ish undone
   fill was 1.04:1 against the silkscreen).
4. **Legend swatches use the same tokens**: board.css restates `.lg-dot`, `.lg-done`, `.lg-next`, `.lg-dirty`, `.lg-pad`
   (W/run.css:28-32) under `.hero--art`, which `boardflow.js` sets on the hero while art shows (rev 1's dark dot
   `#2f5fe0` differed from today's dark `.lg-dot` `#6f9cf5`, ΔE 35).
5. **Enforced by a test** (§6.6): in both themes and for every palette, the computed colours of every overlay class
   and art token, against `--pv-a-bare` and the mask composite.

**Picture modes** (`bv.picture`; control `#pv-picture` in the stage bar, always visible, live and stopped included;
choice kept in localStorage `il.pv.picture`):

- **Full**: everything.
- **Quiet**: copper under the mask, tracks, silkscreen, references and fab hidden; the mask lightened toward the stage
  (light theme composite `#aab2b1`, dark theme `#243034`) by a class that swaps `--pv-a-mask` (no rebuild); finish,
  holes, outline and exposed copper kept. Dots and pad marks keep the finish behind them, so rule 1 holds.
- **Off**: the art SVG hidden and `.pv--art` removed: exactly today's drawing and tokens.
- Defaults: **Quiet** in the Run hero while a plan is shown, while live and in Records; **Full** in the upload preview,
  the thumbnails, the pre-plan hero and the inspector. The operator's choice in the hero is remembered.

**Text.**

- Inside the flipped group a text gets a local `transform="translate(x y) rotate(a) scale(1 -1)"`, so it reads the
  right way round and rotates counter-clockwise like the board.
- **Reference labels** at the KiCad Reference position, angle and size when it is on F.SilkS and not hidden
  (`parts[].label`); otherwise at the part box centre, `clamp(0.3, 0.45 × min(box w, h), 1.2)` mm. Angles normalised
  to (−90°, 90°]; `font-family: var(--font-ui)`.
- **Size buckets**: each label is in `.pv-lb1` (< 0.6 mm), `.pv-lb2` (< 1.0 mm) or `.pv-lb3`; `relayout` shows a
  bucket only when its smallest label is ≥ 9 px tall on screen (rev 1's single threshold showed 0603 labels at ≈ 4 px).
- Silkscreen `gr_text`: `font-size = 1.4 × h` mm. System fonts never match KiCad's stroke font; positions and sizes do.
- Strokes: silkscreen, tracks and fab lines use their real width (scaling); the edge is a 1 px non-scaling stroke; holes
  are fills.

### 5.4 Job overlay (with art: `.pv--art` on the overlay SVG)

| Item | Drawing |
|---|---|
| Paste aperture (`rect.pad`) | §5.3 rule 3, corners from roundness. The pad type is in the `<title>` and the inspector, **not a colour**: colour means state only (DEV_NOTES.md:260), and six type tints were 1.50–1.64:1 against the finish with pairs ΔE 6.8–6.9 apart |
| Dot (`circle.dot`) | real diameter, §5.3 rule 3; first, next, halo, check cased |
| Order | the path line (cased), the first-dot marker, the preview (§5.6), path numbers in the inspector |
| Probe spots, probed area | today's shapes (W/boardview.js:401-426), cased, `--pv-o-probe` |
| Fiducials expected / found | AR's `g.pv-l-fids` (AR:1579-1584) from `plan.registrationTargets` / `registration`; `plan.board.fiducials` gives the expected positions when AR's data is absent; the art shows the fiducial copper and mask ring |
| Camera | `g.pv-l-cam`: the live top-camera crosshair (`IL.positions().cam`, W/core.js:624) and a 5 mm scale bar, in the pre-plan hero (§5.9) |
| Legend (`#run-legend`, W/run.js:770-811) | idle: `dot size not measured` when `plan.dot.diameterMm` is null; the four-item rule stays; no pad-type items |

### 5.5 Performance plan

- **Built once per art id per view**: about 15–25 path elements in the art SVG, at most ~200 label texts and one hit
  rect per part (mobo: ~70). The 5,000-element budget (UI_DESIGN.md:854) holds; §6.3 bounds the art element count
  < 1,500 for every fixture.
- **The art is its own SVG** (own compositing layer): polls, preview frames and hover repaint only the overlay.
- **Re-plan of the same board** changes only the art group's `transform`.
- **Polls**: `update()` never touches the art (it toggles classes on pads and dots, W/boardview.js:325-393).
- **View-box changes** repaint both SVGs; `follow` applies one per dot (W/boardview.js:388-391), about one per second
  while running (measured by the gate below). Labels outside their bucket are `display: none`.
- **Preview loop**: `requestAnimationFrame` only while playing and visible, at most 30 updates per second. Per update:
  the ghost's `transform`, the classes of the dots crossed since the last update (diffed), and the partial leg (a
  separate `line.pv-path--leg`). The pre-path `d` (`dFull.slice(0, off[n])`) is rewritten **only when n changes**.
- **Thumbnails**: built from `art.thumb` (a few KB), each rendered once to an `<img src="blob:…">` (CSP allows blob:
  images, A/ApiServer.java:462), when visible (IntersectionObserver).
- **Leaks**: every view is destroyed when replaced (Records, inspector, dialog).
- **Gate** (§6.6, harness test): mobo's art and a 261-pad plan, CPU throttled 4× (CDP `Emulation.setCPUThrottlingRate`):
  the art drawn ≤ 1,000 ms after `setArt`; during a 16× preview the p95 frame time ≤ 33 ms over 5 s; `bv.update()` plus
  the next paint ≤ 16 ms (median of 10 simulated polls). A failing measurement is repeated once before the check fails.

### 5.6 Path preview (`W/pathpreview.js`)

**Where**: `#pv-play` (`Preview path`, `i-play`) sits in the stage bar (mounted by `boardflow.js` through H3) when a
plan is shown, the hero mode is `idle` or `result` (W/run.js:499-508) and no task runs. Pressing it expands
`#run-preview`, a row in the hero stage in the place of `#run-timeline` (which only shows while a board runs,
W/run.js:813-818); Clear or any stop collapses it. Hidden in `live` and `stopped`.

| Id | Element | Behaviour |
|---|---|---|
| `#pv-play` | `button.btn.btn--sm`, `aria-pressed` | `Preview path` / `Pause`; plays from the current position (from 0 at the end) |
| `#pv-scrub` | `input[type=range]` 0…N, `aria-valuetext` = the readout | sets v (pauses); arrows ±1 dot, PageUp/PageDown ±10, Home/End natively |
| `#pv-speed` | `.seg`, `button[data-speed=1\|4\|16]`, `aria-pressed` | clock speed |
| `#pv-readout` | `span.pv-readout` (tabular numbers) | idle: `Path 160 dots · 412 mm · dots ≈ 1:37 (whole board ≈ 1:49)`; previewing: below |
| `#pv-reset` | `button.link` `Clear` | ends the preview (`bv.preview(null)`), collapses the row |

- **Scrub value v** (0…N) = the number of dots done. Dots `[0, v)` get `.pv-pre`; the ghost sits on dot index v
  (0-based), i.e. **dot v+1**. The readout names that dot: `Dot {v+1} of {N} · {pad id} · {turn} · Z {z} mm · {elapsed} of {total}`;
  at v = N: `All {N} dots · {total}`. Turn label: `IL.turnLabel(profile.netStepsPerDot, profile.stepsPerRev)`
  (W/core.js:372).
- **Clock**: τ runs from `ta[0]` to `te[N−1]` (§4.5) at speed × real time. At τ: v = the number of dots with `te ≤ τ`;
  the ghost sits on dot index v while `ta[v] ≤ τ`, and moves linearly from dot v−1 to dot v during `[te[v−1], ta[v]]`.
  Elapsed = τ − ta[0]; the readout's title adds "+0:12 before the first dot (pump, valve, 8 purge dots, crossing)"
  (`ta[0]`).
- **No estimate** (`summary.estimateError`): times are synthesised from the path length at 50 mm/s and 0.6 s per dot,
  and the readout says `no time estimate: illustrative timing`.
- **Drawing**: `bv.preview({n: v, ghost})` adds `.pv--preview`: dots not yet reached are dimmed, reached ones filled
  (`circle.dot.pv-pre`), real `done` styling is suspended while previewing, `path.pv-path--pre` is the path up to dot
  v−1 plus `line.pv-path--leg`, `g.pv-ghost` is a cased dashed ring with a crosshair and `<title>Preview needle: nothing
  moves</title>`, apart from the live `.pv-needle` (`--live`). The hero note (`#run-note`) reads `Preview · nothing moves`.
- **Stops**: a task starts (the poll sees `run.running`), the plan changes (new planId), the tab hides
  (`document.hidden` → pause), or `#pv-reset`. No global keyboard shortcut (Space is never bound, UI_DESIGN.md:814-833).

### 5.7 Pad preview: the inspector (`W/padinspect.js`, panel view `pad`)

**Opening**: a click on a `rect.pad` (`onPad`, replacing today's camera panel, W/run.js:487-488), on a part body
(`onPart`, the hit rects), or `#btn-pad-inspect` (icon-only, `aria-label="Inspect pads"`, in the stage bar next to
`#pv-play`: the keyboard path; it opens the first pad in path order, and the inspector's previous/next walk the parts). `IL.panel.show('pad', IL.views.pad,
{title: 'Pad R12.1'})` (W/core.js:1984-2003); the selection lives in module state (`?panel=` routes carry a name only,
W/core.js:2070-2088). **Closing**: the panel's back arrow, Esc (a pushed panel view goes back, UI_DESIGN.md:822), a new
plan without that pad. While it is open the hero shows `bv.select` on the part.

| Id | Content |
|---|---|
| `#pad-insp-part` | `R12` · `package R_0603_1608Metric~2 · part R_0603_1608Metric~2-R_Small · 2 pads`; `#pad-insp-prev` / `#pad-insp-next` (previous/next part in path order) |
| `#pad-insp-svg` | a `lite` BoardView (no id), Full picture: same plan and art, `fit` to the part box + 1 mm, the paste apertures, the dots at real size with their path numbers (`dotLabels`), the selected pad `.sel` |
| `#pad-insp-pads` | table `tr[data-pad]` (buttons, `aria-selected`): pad, opening `w × h mm, corners %`, pad type, dots, state |
| `#pad-insp-rule` | **one sentence**: pattern → `Chosen because the package name contains “0603” (pad type Chip 0603, v2, characterized).`; package map → `Mapped to this package by hand in Profile › Pad types (pad type …).`; centre pad → `Centre pad of a QFN/DFN: it sits at the part centre and is at least 1.0 mm² (pad type QFN centre, …).`; none → `No pad type matches the package WSON-8…: it gets no paste until one is added.` Then `<details class="disclose">` `Rule details` with the pattern or the mapping |
| `#pad-insp-kv` | for the selected pad (`IL.kv`): pad type (name, version, status); opening and source (`KiCad paste opening` for FOOTPRINT, `pad type default` for PAD_TYPE); area; **amount** `A 0.10 mm stencil would print 0.075 mm³ (0.31 mg) here.` (`tMm`, `targetVolumeMm3`, `targetMassMg`, D/DispensePlan.java:24-28; informational); dots and why (`one dot`, or `pad over 1.20 mm²: 4 dots in a grid`, from `GET /api/pad-types/{key}`, fetched once); turn per dot; surface Z and dispense Z |
| `#pad-insp-flags` | `notebox--block` per flag (D/PadFlag.java): `NO_PAD_TYPE` "No pad type matches the package P: add one, or map the package to a pad type."; `STATUS_BELOW_MIN` "Pad type T is not characterized yet: dispensing needs a characterized pad type."; `NO_GEOMETRY` "The pad type has no pad size and the package has no pads." |
| `#pad-insp-dots` | `#`, X, Y, Z (dispense), t (`m:ss` from `te`, `—` without an estimate) |
| `#pad-insp-edit` | `Edit pad type Chip 0603` → `#profile/pad-types/<key>`, or `New pad type for <pkg>` → `'#profile/new-pad-type/' + encodeURIComponent(pkg)` |
| `#pad-insp-cam` | `Top camera` → today's camera panel (`IL.panel.show('camera', IL.views.camera, {title: 'Top camera · pad R12.1'})`, W/core.js:2887-2917) |

- Art-only parts (`kind` `art`, `dnp`, `pasteOnly`, `fiducial`, `bottom`) open the same view with their reason
  (`Through-hole part: not dispensed`, `Do not populate: not placed, still pasted`, `Fiducial for Register board`,
  `Bottom-side part: only the top is dispensed`).
- **Hover** gives short tooltips through `<title>` on pads and part hit rects. Hover-only information is not allowed
  (UI_DESIGN.md:828), so the inspector carries everything. Dots keep `pointer-events: none` (W/run.css:82).

### 5.8 Upload dialog (`W/boardimport.js`)

`IL.dialog.open({id: 'dlg-board-import', size: 'wide', eyebrow: 'Board file', title: 'Upload a KiCad board', …})`
(W/core.js:2509-2540: in-page, `aria-modal` false, STOP stays live). `.dialog--wide` (`min(1080px, 100%)`) is defined in
boardimport.css (`.dialog` is 760 px, W/app.css:639).

**Page-wide drop guard** (at module load): no web file handles `dragover`/`drop` today (grep of W/*.js), so a file
dropped anywhere outside a drop zone makes Chrome open it in the tab, replacing the app and its STOP button.
`boardimport.js` adds document-level `dragover` and `drop` listeners that call `preventDefault()` for every drag whose
`dataTransfer.types` includes `Files`. A `.kicad_pcb` dropped outside `#board-import-drop` opens the dialog with that
file; any other file gives a toast: `Drop a .kicad_pcb file on the upload box (Run › Board › Upload KiCad board).`

| State | What shows | Ids |
|---|---|---|
| 1 choose | Drop zone with `Drop a KiCad board file (.kicad_pcb) here, or` + `Choose a file…`; the hidden `input[type=file][accept=".kicad_pcb"]`; `KiCad 6 to 10 (KiCad 5 on a best effort basis), up to 12 MB. Nothing moves.` Client checks: extension, 0 < size ≤ 12 MiB | `#board-import-drop`, `#btn-board-import-choose`, `#board-import-file` |
| 2 parsing | `Reading ftp.kicad_pcb (0.8 MB)…` with the elapsed seconds; Cancel aborts the fetch | `#board-import-status` |
| 3 preview | Left: `#board-import-preview`, a `lite` BoardView in art-only mode (`setBoard({art, toMachine: identity})`, Full, whole board, origin marker), parts tinted by **outcome**: gets paste (no tint), no pad type (`--pv-o-block` hatch), not dispensed (dimmed). Right: `#board-import-size` `75.0 × 75.0 mm · KiCad 8 · origin at the bottom-left corner of the board outline` (or `origin estimated: no board outline`); `#board-import-found` (`80 parts get paste (160 pads) · 3 fiducials · 1 bottom-side part skipped (G2) · 5 drawn only (H1–H4, J1)`); `#board-import-packages` table `tr[data-package][data-state]` (package as saved, a `~2` explained, pads, parts, pad type and its sentence or `No pad type`); hovering or focusing a row highlights its parts on the board (`bv.select({refs})`); `#board-import-warn`: groups in the order of §2.10 with headings `Blocks dispensing`, `Registration`, `Check in the preview`, and `Notes (n)` collapsed (`li[data-code][data-group]`) | listed |
| 3a attach offer | when `attach[]` is not empty: `#board-import-attach` notebox `This is the board in ftp.board.xml: all 83 parts match (FID1 is 0.31 mm away from the board file's position; the drawing shows KiCad's). Use this drawing for ftp.board.xml: nothing else changes.` + `#btn-board-import-attach` (`Use the drawing for ftp.board.xml`), next to the normal save | listed |
| 4 name | `#board-import-name` (default: the file name without `.kicad_pcb`), `#board-import-slug` `Saved as instancelabs/boards/ftp/ftp.board.xml`; when taken: `#board-import-exists` notebox `A board named "ftp" exists (imported 13:02). Saving replaces it: plans made with it must be planned again.` and `#btn-board-import-save` becomes `IL.press2` with the verb `replace board` (W/core.js:2241; UI_DESIGN.md:828-833) | listed |
| 5 saving | the Save button busy; **Esc and Cancel do nothing** while the save runs; refusals in a `[data-refusals]` foot | `#btn-board-import-save`, `#btn-board-import-cancel` |
| 6 saved | `#board-import-saved` `Saved FTP test board: 80 parts, 3 fiducials. New packages: R_0603_1608Metric~2, LED_0603_1608Metric~2, Fiducial_1mm_Mask3mm~2.`; **actions** for every package without a pad type (`New pad type for WSON-8…`, `#btn-board-import-newtype[data-package]`); `#btn-board-import-use` `Use this board` (primary), `#btn-board-import-again` `Upload another` | listed |
| 7 use | Closes; Board step: `loadBoards()`, select `file:` + file (W/run.js:1084-1146); hint `Put the top camera on FID1 and press "Camera is on FID1" (or type the origin), then Plan. Register board then finds the fiducials.` | – |

- Every save or attach result also ends in `IL.toast` (W/core.js:534), so a dialog closed early still reports.
- `preview-stale` (409): state 3 again with the new preview and `OpenPnP's packages or parts changed: check the lists
  again, then save.`
- Errors of §3.2 show in `#board-import-status` as `.reason--block` with `Choose another file`.
- Requests: `fetch('/api/boards/import?file=' + encodeURIComponent(f.name), {method: 'POST', body: f, headers:
  {'Content-Type': 'application/octet-stream'}, signal})`, 60 s; save and attach post the same `File` with the query
  of §3.2.

### 5.9 Board step: upload, thumbnails, origin, the hero before planning

- `#btn-board-upload` (`Upload KiCad board…`, `i-plus`) in `.board-sel-row` next to `#btn-board-refresh`
  (W/run.js:1183), mounted by H1. While a task runs it is greyed with `IL.gate(btn, 'Upload the board when the run has
  finished.')` (W/core.js:683), the same refusal as the API (§3.4).
- `select#board-select` stays (PW drives it, PW:440-447). Under it `#board-thumbs`: **one horizontally scrolling row**
  (at most 76 px tall with its label) of `button.board-thumb[data-board="file:<path>"|"job:<id>"]` (`aria-pressed` =
  selected), each an `<img>` from `IL.boardart.thumbUrl(art, {w: 96, h: 64})` + name + `75 × 75 mm · 80 parts` (+ a
  `KiCad` tag). At most 12, then `+n more in the list`. A click runs the same path as the select (W/run.js:1131-1146).
- `#board-imported` under the select: `Imported 13:02 from ftp.kicad_pcb (KiCad 8): 80 parts, 3 fiducials.`; fallback
  art adds `Drawn from the board file (no KiCad drawing).` and `art.note` when set.
- **`#btn-board-use-fid`** (`Camera is on FID1`), a capture next to `#btn-board-use-cam` in `#board-captures`
  (W/run.js:1172-1176, :1189): with the top camera centred on the board's first fiducial, it sets origin X/Y = cam −
  R(rotation)·(bx, by) from the form's rotation and `GET /api/boards` `fiducials`. The plan then lands within the
  camera's centring error of the true fiducials, far inside AR's 8 mm search (AR:633), and the board's corner never
  has to be found (on mobo it is a virtual point: its outline arcs bulge past the box). Disabled with a reason when the
  board has no fiducials or the camera position is unknown. For `estimated` origins the Board step says it is the way
  to place the board.
- **Origin marker** (`g.pv-a-origin`: a crosshair labelled `0,0` with 5 mm +X/+Y arrows) on the art in the upload
  preview, the thumbnails and the pre-plan hero.
- **Hero before planning**: with a board chosen and no plan, the hero shows its art at the form's seat
  (`setBoard({art, toMachine: T(x, y)·R(rot)})`, the default transform for the top side, O/util/Utils2D.java:105-119),
  Full picture, plus the **live top-camera crosshair** (`bv.update({cam})`) and a 5 mm scale bar; the view fits the board
  and the camera position together, so jogging the camera onto FID1 visibly lands on the drawn FID1 when the seat is
  right. Caption `Plan to see the dots`. This is the "outline" UI_DESIGN.md:257 promises and the code never drew
  (W/run.js:721-733).

### 5.10 Records map

Through H4: `IL.boardFlow.recordsMap(bv, plan)` fetches `IL.boardart.get(plan.board.art.id)` (the id stored in the
plan, §4.5) and calls `bv.setArt` when it arrives, Quiet picture; failures keep today's drawing. H5 destroys the
previous map view on rebuild. The 260 px box stays (W/profile.css:291-298).

### 5.11 Register board flow (AR)

AR's Register step draws probe targets and fiducials through `bv.update({probe, fids})` (AR:1579-1584). With the art
underneath, the operator sees the targets on the real copper and mask, and the search circles around the drawn
fiducials. The art offers AR keep-out circles, fiducial mask sizes and the outline loops (§4.4, §10.2).

### 5.12 Element ids (for UI_DESIGN.md §16)

Board step: `btn-board-upload`, `board-thumbs` (`button.board-thumb[data-board]`), `board-imported`,
`btn-board-use-fid`. Dialog: `dlg-board-import`, `board-import-drop`, `board-import-file`, `btn-board-import-choose`,
`board-import-status`, `board-import-preview`, `board-import-size`, `board-import-found`, `board-import-packages`,
`board-import-warn`, `board-import-attach`, `btn-board-import-attach`, `board-import-name`, `board-import-slug`,
`board-import-exists`, `btn-board-import-save`, `btn-board-import-cancel`, `board-import-saved`,
`btn-board-import-newtype`, `btn-board-import-use`, `btn-board-import-again`. Hero: `pv-play`, `pv-picture`, `btn-pad-inspect`,
`run-preview`, `pv-scrub`, `pv-speed`, `pv-readout`, `pv-reset`. Inspector (`#panel[data-view=pad]`): `pad-insp-part`,
`pad-insp-prev`, `pad-insp-next`, `pad-insp-svg`, `pad-insp-pads`, `pad-insp-rule`, `pad-insp-kv`, `pad-insp-flags`,
`pad-insp-dots`, `pad-insp-edit`, `pad-insp-cam`. SVG classes: `pv-art`, `pv-host`, `pv-l-art`, `pv-a-*`, `pv-l-parts`,
`pv-part`, `pv-pre`, `pv-ghost`, `pv-path--pre`, `pv-path--leg`, `pv-case`, `pv-l-cam`, `sel`, `pv--art`, `pv--preview`,
`pv-lb1/2/3`, `pv-art--quiet`.

### 5.13 Layout at 1280 and 1024 px

At 1280 px the hero drawing is about 540 × 370 px (`~/instancelabs/ui-revamp/impl-shots/final-run-checks-1280-dark.png`),
and the stage bar already drops legend items until they fit (W/run.js:807-810).

- **Stage bar**: legend (the existing shrink loop keeps ≥ 1 item), `#pv-picture`, the view segment, zoom, `#pv-play`,
  `#btn-pad-inspect` (icon-only).
  From 1440 px `#pv-picture` is a 3-way `.seg` (`Full | Quiet | Off`); below it one cycling button `Picture: Quiet`.
  At 1024 px `#pv-play` is icon-only with `aria-label="Preview path"`.
- **`#run-preview` expanded**: play, scrub (flex 1, min 160 px), the speed segment, the readout (ellipsised, full text
  in its `title`), Clear. At 1024 px the readout moves to a second row under the scrub.
- Checked in Playwright at 1280 and 1024 (§6.6): `.stage-bar` and `#run-preview` have `scrollWidth ≤ clientWidth + 1`,
  and `#plan-svg` is ≥ 280 px tall.

---

## 6. Tests

### 6.1 Fixtures and licences (`F/`)

| File | Source | Licence | Changes |
|---|---|---|---|
| `ftp.kicad_pcb.gz` | opulo-inc/lumenpnp @ 5ec657b, `pnp/pcb/ftp` | CERN-OHL-W v2 (`~/lumenpnp/LICENSE` item 1); "LumenPnP is (c) by Opulo" kept | footprint G1 (`mfg:opulo-logo`, ftp.kicad_pcb:15454) removed; the gr_text at :19954 cut to `Functional Test Placement REV00\n<<hash>>` (items 4–5: marks removed from derivative works); `model` subtrees removed |
| `secondary-fid-pcb.kicad_pcb` | same repo, `pnp/pcb/secondary-fid-pcb` | CERN-OHL-W v2 | none (13.6 KB) |
| `pi-interface-k7.kicad_pcb` | same repo, `pnp/pcb/pi-interface` (KiCad 7, `20221018`) | CERN-OHL-W v2 | the two `kibuzzard-*` text footprints removed (they may spell marks); `model` subtrees removed |
| `pi-interface-k10.kicad_pcb.gz` | opulo-inc/lumenpnp @ 64cf68d, `src/PCB/pi-interface` (KiCad 10, `20260206`, 107 KB) | CERN-OHL-W v2: the same design as `pnp/pcb/pi-interface`; LICENSE item 1 still names `./pnp/pcb` after upstream moved the PCBs to `src/PCB` (OQ12) | the five `kibuzzard-*` footprints and the `model` subtrees (absolute designer paths with the mark, pi10:1690) removed |
| `ftp0402.kicad_pcb.gz` | opulo-inc/lumenpnp @ 64cf68d, `src/PCB/ftp/ftp0402` (KiCad 9) | as pi10 (OQ12) | `mfg:opulo-logo` (ftp0402:14390) removed; the gr_text at :18626 cut to `Functional Test Placement REV00 (0402)` (drops the URL with the mark); `model` subtrees removed |
| `mobo.kicad_pcb.gz` | opulo-inc/feeder @ 05843dd, `pcb/mobo` | CERN-OHL-W v2 (`~/openpnp-sources/feeder/LICENSE` item 1) | the six pad-less logo footprints (`LOGO` ×2, `index:goblin`, `feeder:pulsar`, `index:logo_mask`, the OSHW symbol) and the gr_text at mobo.kicad_pcb:28359 removed |
| `NanoV3.3.kicad_pcb.gz` | theinstancelabs/lumenpnp-handoff @ 6a78577, `nano/source/kicad`, a KiCad port of the Arduino Nano V3.3 | CC BY-SA 4.0 (`nano/source/kicad/LICENSE.md`) | none; credited to Arduino and the port; stays CC BY-SA |
| `NanoV3.3-F_Paste.gbr`, `NanoV3.3-PTH.drl`, `NanoV3.3-NPTH.drl` | same repo, `nano/source/order/arduino-nano-gerbers.zip` (KiCad 10.0.5 exports of the same board) | CC BY-SA 4.0 (outputs of the licensed design) | none (3.7 KB, 1.8 KB, 0.4 KB) |
| `ftp.board.xml` | `~/Desktop/ftp.board.xml` (the bench file, FID1 moved by hand) | as ftp | none |
| `LICENSE.txt` | – | – | per file: source, commit, licence, the Opulo notice, the changes |

The removed items have no pads and are not on Edge.Cuts, so placements and the outline do not change. A test asserts
that no fixture contains `opulo` or `lumenpnp` (case-insensitive) and that the stripped files parse to the counts of
§2.11. Large files are read through `GZIPInputStream`. This follows the existing licensed fixtures in
`src/test/resources/samples/kicad/LICENSE.txt`. The fork is GPLv3 (LICENSE.txt). Never the copies under
`~/Lumen-Harness/fixtures`.

### 6.2 Parser unit tests (`T/kicad/`, JUnit, `mvn -B -o -T1 test`)

- **`SExprReaderTest`**: atoms, strings with `\"`, `\\`, `\n`; CRLF; BOM; unquoted KiCad 5 tokens; skip-heads (a 2 MiB
  `embedded_files` blob costs no nodes); depth 65 → `limit`; 1,500,001 items → `limit`; a 1 MiB + 1 string → `limit`;
  the shared `Deadline` with an injected clock (expiring during the art step after a fast parse); unbalanced
  parentheses → `syntax` with line and column; `(footprint`, `(module`, `(kicad_sch`, a Gerber start → the three
  `not-kicad` texts.
- **`KTextTest`**: BEL, other C0, DEL, C1, U+FFFE, U+FFFF and a lone surrogate dropped; TAB/CR/LF → space; collapse,
  trim, the caps by code points; empty → `ifEmpty`.
- **`KicadFtpTest`** (the brief's acceptance):
  - frame 75 × 75, origin KiCad (0, 0), origin kind `outline`; 89 footprints; `counts.placements` 80 and
    `counts.fiducials` 3;
  - every placement of `F/ftp.board.xml`: same id, type, side, rotation (exact) and part key; x/y within **0.01 mm**,
    except FID1 (expected 0.3136 ± 0.001 off). `enabled` is not compared: D1–D40 are disabled by hand in
    ftp.board.xml (:4-160) and not DNP in KiCad;
  - pads: 160 apertures; R pads 0.8 × 0.95 at (±0.825, 0), LED pads 0.875 × 0.95 at (±0.7875, 0), roundness 50,
    rotation 0, names `1`/`2`; fiducial package pad: circle 1.0, roundness 100.
- **`NanoPasteOracleTest`** (KiCad itself as the oracle): a test-only reader `T/kicad/GerberApertures.java` (~120
  lines: `%FSLAX46Y46%`, `%MOMM%`, `%ADD…C/R/O/RoundRect…%` with the RoundRect macro's corner points and radius,
  `X…Y…D03` flashes, `G36`…`G37` regions) reads `F/NanoV3.3-F_Paste.gbr`: 54 flashes and 1 region. Each of nano's 55
  apertures, placed on the board (§2.6, Gerber Y = −KiCad Y), must match one flash or the region: centre within
  0.001 mm, width and height (RoundRect: the corner polygon's sides + 2r) within 0.001 mm, corner radius
  (`min(w,h) × roundness / 200`) within 0.001 mm (e.g. 0.1038 = 0.18 − 0.0762, `%ADD11`), rotation within 0.01°
  (modulo 90° for squares, 180° otherwise). The critique measured all 54 within 6e-7 mm with these rules.
- **`NanoDrillOracleTest`**: every PTH and NPTH drill of nano's footprints, **top and bottom** (J1/J2's 30 bottom-side
  pins included), placed by §2.6, equals a hit of `F/NanoV3.3-PTH.drl` / `-NPTH.drl` within 0.001 mm, with the same
  diameter, and the hit counts match.
- **`KicadNanoTest`** (KiCad 6, CRLF): 43.18 × 17.78 with 4 cut-outs; 8 parts + FD1/FD2 (name rule); with the name
  rule switched off (a constructor flag), 0 fiducials; IC3's polygon → `33~1`, 1.27 × 1.27 at the origin; 20 bottom
  skipped; `PASTE_POLY`; `viasonmask false` → vias tented.
- **`KicadMoboTest`** (KiCad 9): exact min X 99.7323 (not 99.95) and 51.5677 × 89.0; U2 enabled = false +
  `DNP_STILL_PASTED`; U2 windows `33~1…33~9`; U4 `2`, `2~2`; J2/J3 `MP`, `MP~2`; J5 2 polygon apertures; 13 cut-outs;
  `(tenting front back)` → tented; stackup mask `Black`, silk `White` (mobo.kicad_pcb:33-45).
- **`KicadSecondaryTest`** (KiCad 9): `gr_rect` outline 10 × 10; no placements; REF** bottom; `NO_PLACEMENTS`.
- **`KicadK10Test`** (pi10): version 20260206, no `KICAD_NEWER`, 12 paste apertures, `(tenting (front yes) (back yes))`
  read, `FIDUCIAL_COUNT`, `covering`/`plugging`/… ignored without warnings; the stripped counts pinned.
- **`KicadK7Test`** (pi7): 8 paste apertures, `stroke` widths, `viasonmask false`; counts pinned.
- **`KicadFtp0402Test`** (KiCad 9): 80 parts (R/LED 0402) + 3 fiducials, 160 apertures, frame and counts pinned.
- **`Fid1RegistrationTest`** (pure math, the §2.6 finding): measured r6 fiducials (constants from
  `~/instancelabs/runs/board_registration_r6.json`: FID1 (358.301, 118.715), FID2 (308.719, 184.241), FID3
  (358.783, 183.675)); fit the 3-point affine with ftp.board.xml's fiducials and with the KiCad proposal's: Y scale
  0.9947 vs 0.9994 (± 0.0005); the per-pad difference of the two registered boards is at most 0.254 ± 0.002 mm, at
  R19 (board (26.25, 17.0), ftp.board.xml:249); the per-pad list is written to the test log.
- **`KicadSyntheticTest`** (small boards written in the test):
  - KiCad 5 `module` with unquoted tokens, `fp_text reference`, a `gr_arc` with `angle` (the §2.5 direction) and its
    extents; an `fp_poly` on F.Paste **without a fill token** → an aperture; a zero-width `gr_circle` → filled;
  - KiCad 7 (`stroke`, `attr smd dnp`); version 20260207 → `KICAD_NEWER`;
  - no Edge.Cuts → courtyard box + `NO_EDGE_CUTS`, origin `estimated`; an open outline → `OUTLINE_OPEN`, origin `edges`;
  - paste precedence: pad margin over footprint over board, ratio per axis, the zero rule on both sides of `20240108`;
  - shapes: rect with ±margin, roundrect radius shrink and clamp, circle, oval, trapezoid bbox, **custom pad whose
    primitives lie off the anchor → aperture at the box centre, grown by half the stroke width**, chamfer,
    `APERTURE_GONE`; an F.Paste `fp_poly` rectangle at 30° → exact aperture with rotation 30; a non-rectangular convex
    quad → minimum-area rectangle + `PASTE_POLY_APPROX`; a footprint zone on F.Paste with a fill → aperture, without →
    `PASTE_ZONE_IGNORED`;
  - **cleaning**: a Value with BEL, a footprint name with `\n`, a Reference with U+FFFE → cleaned ids + `ID_CLEANED`;
  - pad offset, `THT_PASTE_SKIPPED`, `DUPLICATE_REF`, **`PAD_ID_CLASH`** (ref `U1.1` pad `2` and ref `U1` pad `1.2`),
    `PACKAGE_VARIANT` (two variants, no ids in the proposal), `VALUE_SAYS_DNP`, `exclude_from_pos_files` with paste →
    disabled;
  - **hostile numbers**: a coordinate of 1e300 and an arc of radius 1e9 → `BAD_NUMBER`, items skipped; an arc of
    radius 2,000 mm → ≤ 360 segments;
  - via tenting: the three board forms and a per-via override; stackup colours.
- **`KGeomTest`**: arc from 3 points (both directions), exact extents across 0/90/180/270, flattening chord error and
  the 1° cap, chaining with 0.005 mm gaps through the hash grid (10,000 segments ≤ 200 ms), iterative Douglas–Peucker
  on 1,000,000 points (no stack overflow) and its bound on a 1,000-point circle, minimum-area rectangle of a rotated
  rectangle (exact) and of a trapezoid.

### 6.3 Art tests (`T/board/`)

- **`BoardArtBuilderTest`**, per fixture: deterministic bytes (built twice, equal); ≤ 400 KB; < 1,500 SVG elements when
  rendered; outline closed; cut-out count; holes (ftp: H1–H4 PTH 3.2 mm at (5,5), (70,5), (5,70), (70,70) and J1's two
  PTH; nano: J1/J2's 30 bottom-side pins in `holes.pth`, `cu.fill` and `exposed`); `exposed` contains every top pad
  and fiducial copper; parts (ftp 88 top; nano adds kind `bottom` for J1, J2); every path parses (a small SVG path
  tokenizer) and lies within the board box + 1 mm; no `NaN`; text count; `thumb` ≤ 8 KB; `finish` (mobo `black` /
  `white`, the others default); `fiducials` (ftp 1.0 / 3.0, mobo mask 2.0); a forced small budget coarsens only
  `cu`/silk/fab (outline, `exposed`, `mask.open`, paste and holes keep 0.01 mm, `stats.toleranceMm`); a synthetic board
  generating > 2,000,000 points → `ART_REDUCED` within the deadline.
- **`ArtPathTest`**: rounding to 0.01, −0 → 0, implicit `L`, arcs as `A`.
- **`BoardArtFallbackTest`**: ftp.board.xml on the SimMachine configuration → outline 75 × 75, 160 copper pads
  (pad-type layout), 3 fiducials at 1.0 / 3.0 mm, `kind` fallback, `thumb` present.
- **`BoardArtStoreTest`**: id = SHA-256 prefix; `refFor` through the geometry digest; a copy with the same placements
  under another path → the same KiCad art (the uinav case); toggling `enabled` → same art; a moved placement →
  fallback with the `note`; history files served by id; fallback LRU eviction; one store per configuration directory;
  `keepOuts`: ftp 6 PTH circles + the outline chain (≥ 300 circles, ≤ 1.0 mm apart) and no fill circles; nano adds the
  30 bottom-side pins and the 4 cut-out chains; mobo's fill circles lie only between the box and the arcs; every
  circle `{x, y, r}` in board mm; `fiducials`: ftp 1.0 / 3.0, mobo 2.0 mask.
- **`BoardArtFixturesTest`**: with `-Dil.writeWebFixtures=true` writes the ftp, nano and mobo art to
  `src/test/web/fixtures/art/<id>.json` and `board-map-generated.json` for the browser harness (§6.6); otherwise checks
  that the committed files equal a fresh build.

### 6.4 Engine tests (`T/dispense/`, and OpenPnP's own test tree for Configuration)

- **`PlanBoardFrameTest`**: for the nominal location, a rotated one, and one with
  `setLocalToGlobalTransform(affine with scale 1.0013/0.9943 and shear)`: `plan.boardToMachine` equals
  `getLocalToGlobalTransform().getMatrix` within 1e-9, and every pad centre equals the affine applied to its
  board-frame centre within 1e-9; `fiducials` mapped the same way; `copyBoardFieldsTo` copies every new field.
- **`PadTypeResolverExplainTest`**: the four rules, their details and `matched` (`0603` for `R_0603_1608Metric~2`);
  `resolve == explain().type` over the seeds.
- **`DotTimesTest`**: `forPlan` on `ToyMapper` (T/dispense/ToyMapper.java): `ta[i] < te[i] ≤ ta[i+1]`;
  `te[N−1] − ta[0]` ≈ `estimatedWorkSeconds` minus the cross (within the cross time); arrays have N entries.
- **`BoardContentDigestTest`**: a changed part id, package id, pad size or pad roundness changes the digest; reordering
  the configuration's maps does not.
- **`ConfigurationSaveLibraryTest`** (`src/test/java/org/openpnp/model/`): on a temp config, `saveLibraryFiles()` writes
  the three files in the order packages, parts, boards (backups made), never machine.xml; a dirty board in the list
  opens no dialog (headless: no `HeadlessException`); a package whose id holds U+0007 (set directly, bypassing the
  import) → it throws **before any file changes** (the read-back check); reload → parts and packages resolve.
- **`EdtRunnerTest`** (`T/board/`): direct when headless, but serialised under the headless lock (two threads never
  inside at once); on the EDT when a fake "window" flag is set; the start timeout cancels before running.

### 6.5 API tests (`T/api/BoardImportSimTest.java`, new; SimMachine + real ApiServer as ApiServerSimTest, T/api/ApiServerSimTest.java:60-69, with a byte-array `post`)

1. **Preview** of ftp: 200; counts 80 + 3; packages `new` (default config); `decisions`; art present; `attach` empty.
2. **Save** as `ftp`: 201; the three files under `configDir/instancelabs/boards/ftp/`; packages.xml, parts.xml and
   boards.xml **on disk** contain the new ids and the board path (without `Configuration.save()`); `index.json` maps the
   board's geometry digest to the art; `GET /api/boards` lists it with `art.kind` `kicad`, `origin` `outline`,
   `fiducials` (3), `imported.slug` `ftp`.
3. **Plan** it at (120, 120, z 10, 0): 160 pads, `source` FOOTPRINT; R pads at the same machine X/Y as chip-0603
   centres within 0.01 mm, LED pads within 0.04 mm (OQ4); `board.toMachine` = `[1, 0, 0, 1, 120, 120]`;
   `board.art.id`; pads carry `roundness` 50, `padName` and `typeRule.matched`; dots carry increasing `ta`/`te`.
4. `GET /api/board-art/{id}`: 200, `Cache-Control: private, max-age=31536000, immutable`; an unknown id → 404.
5. Save again as `ftp` without `replace` → 409 `exists`; with `replace` → 201, `replaced: true`; the old art and upload
   are in `history/` and the old art id is still served.
6. **Staleness**: plan; replace with a copy whose R1 moved 1 mm → `POST /api/run` on the old plan → 409 (fingerprint);
   replace with a copy that only adds `(solder_paste_margin -0.05)` to R1's pads → 409 too (`BoardContentDigest`).
7. **Art fixed at plan time**: plan A; replace with R1 moved → `GET /api/plan/A` still names the old art id, which
   `GET /api/board-art` still serves (from `history/`); a new plan names the new id.
8. **Hand edit and copies**: move a placement of the saved board inside the configuration (through `EdtRunner`, as
   OpenPnP's Boards tab would) → `GET /api/boards` gives `art.kind` `fallback` with the `note`, and a new plan uses the
   fallback id; a byte copy of the original board file under another path, added to the list → `kicad` art.
9. **Persistence**: re-initialise the configuration on the same directory (as SimMachine does) → the board lists with
   its 83 placements resolved to parts, a plan has 160 FOOTPRINT pads, and the art is still `kicad` (index on disk).
10. **Limits and refusals**: a body over 12 MiB with Content-Length → 413 without reading; a 3 MiB body on
    `POST /api/plan` → 413 (the 2 MiB default stays); a JSON body → 400; a `.kicad_mod` → 400 `not-kicad`; a cut file →
    400 `kicad-syntax` with a line; `Origin: http://evil.example` → 403 (A/ApiServer.java:365-368); a bad name → 400;
    a `sha256` that differs from the body → 400; while a run is in progress (a 60-dot run started with `POST /api/run`, as
    ApiServerSimTest does at :885-895) preview, save and attach → 409 `refused`; two previews at once (a test
    latch inside the pipeline) → the second 409 `busy`; a board without Edge.Cuts and 1 fiducial → save 400
    `no-origin`; `K`, `K~2` … `K~99` pre-created with other pads → 409 `ids-exhausted`; a plan of a file path that
    does not exist → 404, and no file is created.
11. **Allocator and cleaning**: (a) a pad-less `R_0603_1608Metric` package and part first → preview `suffixed`, save
    makes `R_0603_1608Metric~2` and `R_0603_1608Metric~2-R_Small`, and the existing package is unchanged (pads still 0);
    (b) a pad-less `K` and a file with two variants of `K` → `K~2` and `K~3`, two packages; (c) `SOT-23` + `5-X` and
    `SOT-23-5` + `X` → `SOT-23-5-X` and `SOT-23-5-X~2`; (d) the synthetic file with BEL / `\n` / U+FFFE plus a control
    character in the operator's name → 201 with `ID_CLEANED`, and after re-initialising the configuration from disk
    every id equals its cleaned form (OpenPnP starts, parts resolve); (e) preview, then add a conflicting package,
    then save with the old `decisions` → 409 `preview-stale` with a new preview; save with the new digest → 201.
12. **Rollback**: a test hook `BoardImporter.failAfterStep(n)` for steps 3, 5, 7 and 8 (§3.6) → 500; the configuration
    has none of the new ids, the board list is as before (`prev` restored on a replace), `<slug>/` holds exactly the
    files it held before, and the staging directory is gone.
13. **Attach**: add `F/ftp.board.xml` to the board list; the preview of ftp lists it in `attach` with
    `maxOffsetMm` 0.3136 (FID1); `POST …/attach` → 201; `GET /api/boards` gives it `art.kind` `kicad`; a plan of
    ftp.board.xml has the same 160 pads at bit-identical positions as before the attach and `board.art.kind` `kicad`;
    packages.xml and parts.xml are byte-equal to before.
14. **Concurrency**: 200 `GET /api/boards` in a loop on another thread while a save runs → no exception (the headless
    `EdtRunner` lock serialises them).
15. **Static files**: index.html loads, and the server serves, `board.css`, `boardart.js`, `pathpreview.js`,
    `padinspect.js`, `boardflow.js`, `boardimport.js`, `boardimport.css`.

`T/api/BoardImportRegistrationSimTest.java` (integration slice, needs AR): register the imported board in the SIM
scene; replace it with FID1 moved 0.3 mm; `POST /api/plan` with `registration: "auto"` → `registration` null; the run
is refused until the board is registered again (the AR change of §10.2 item 1).

### 6.6 Browser tests

**(a) Render harness** (web-render, wave 1): `src/test/web/boardview.playwright.cjs`, run by
`src/test/web/run-boardview-test.sh`. It starts a seeded SIM UI session from the current jar
(`python3 src/test/sim/il_ui_session.py start --jar … --name boardview-<timestamp>`) and
`devproxy.py --web W --api <session> --fixtures src/test/web/fixtures`. With `--fixtures`, devproxy answers
`GET /api/board-art/<id>` from `fixtures/art/<id>.json` and adds to every `POST /api/plan` and `GET /api/plan/<id>`
response a `board` block computed from the plan's seat (`toMachine` = T·R from the request, `art.id` from
`fixtures/board-map.json`, board file name → art id), synthetic `ta`/`te` (0.6 s per dot) and `dot.diameterMm` 0.6;
everything else goes to the session. So the render is built and tested before the Java side exists. Fixtures:
`sample.art.json` (hand-made, wave 1) and, once the art slice has run `BoardArtFixturesTest`, ftp, nano and mobo.
Checks:

1. Structure: `.pv-art g.pv-l-art`, `.pv-a-edge`, `.pv-a-exposed`, no `clipPath`; `#plan-svg rect.pad` and
   `circle.dot` counts equal the plan's.
2. **Matrix sign at 30°**: plan the board at rotation 30°; through `getScreenCTM()`, the art's R1 pad centre (its part
   position plus the pad offset) and every fiducial copper centre lie within 0.5 px of `rect.pad[data-pad="R1.1"]`'s
   centre and of the plan's `board.fiducials` positions (rotation 0 would not catch a sign error in m01/m10).
3. **Contrast**, both themes × the 8 mask palettes × Full and Quiet: from `getComputedStyle` of `.pv-next`, `.pv-check`,
   `rect.pad.dirty`, `.blocked`, `.pasted`, `circle.dot`, `.done`, `.pv-probe`, `.pv-hull`, `.pv-path`, `.pv-ghost`,
   the casing nodes and the art tokens: in-pad marks ≥ 3:1 against `--pv-a-bare`; for cased marks the better casing
   tone ≥ 3:1 against the finish and the mask composite; legend swatches use the same colours as the marks.
4. **Preview clock**: scrub to v = 80 → 80 `circle.dot.pv-pre` and `#pv-readout` matches
   `/^Dot 81 of 160 · \S+ · .+ · Z \d+\.\d+ mm · \d+:\d\d of \d+:\d\d/`; v = 160 → `/^All 160 dots/`; 16× play →
   `waitForFunction` on a growing `.pv-pre` count (timeout 5 s); Clear → 0 `.pv-pre`, the row collapsed.
5. Picture modes: Quiet → `.pv-art--quiet`, silkscreen hidden, dots still on the finish colour; Off → the art SVG
   hidden and no `.pv--art`.
6. Labels: at maximum zoom a `.pv-a-refs text` has a positive `getScreenCTM()` determinant (not mirrored); at the whole
   board view `.pv-lb1` labels are hidden.
7. **Performance gate** (mobo, §5.5).
8. `destroy()`: 20 create/destroy cycles leave no ResizeObserver callbacks (a wrapped ResizeObserver counts them).

**(b) Board flow on the SIM session** (web-flow, wave 2): `src/test/web/board-import.playwright.cjs`, run by
`run-webui-sim-test.sh` right after PW in the same session (one line, integration slice). PW is not edited by this
design: its checks keep passing because the art is a separate SVG and every class it counts is unchanged. Own
preconditions: dismiss a pushed Recovery panel the way PW does, `IL.go('#run/board')`; nothing in this block moves the
machine. Saved boards get unique names (`ftp-kicad-<timestamp>`), so a board already imported into the copied live
configuration never answers 409.

1. Plan `00-ftp.board.xml` at 120/120/10/**30°**: fallback art drawn; the alignment check of (a)2 against the live
   fallback art.
2. Pad inspector: click `#plan-svg rect.pad[data-pad="R1.1"]` → `#panel[data-view=pad]`; `#pad-insp-rule` matches
   `/^(Chosen because the package name contains|Mapped to this package by hand)/`; `#pad-insp-dots tbody tr` count =
   the pad's `dotCount`; `#pad-insp-svg svg rect.pad.sel`; `#pad-insp-cam` opens a panel titled `/Top camera/`; Esc →
   back. `#btn-pad-inspect` opens the first pad in path order.
3. Preview: `#pv-play` visible; pressing it expands `#run-preview`; Clear collapses it.
4. **Drop guard**: a synthetic `drop` with a file on `#main` → `location.href` unchanged and `#btn-stop` visible.
5. **Upload**: `#btn-board-upload` → `#dlg-board-import`; `page.setInputFiles('#board-import-file', {name:
   'ftp.kicad_pcb', mimeType: 'application/octet-stream', buffer: zlib.gunzipSync(fs.readFileSync(…ftp.kicad_pcb.gz))})`
   → `#board-import-found` matches `/80 parts get paste \(160 pads\)/` and `/3 fiducials/`; `#board-import-preview svg
   .pv-a-edge`; three `#board-import-packages tr[data-package]` with `data-state` in `new|suffixed`; no
   `li[data-group="1"]`; `PACKAGE_SUFFIXED` (if present) under Notes; `#board-import-attach` names ftp.board.xml and
   FID1.
6. Name `ftp-kicad-<ts>`, save → `#board-import-saved` `/^Saved/` and a toast; `#btn-board-import-use` → the dialog is
   gone, `#board-select` ends with `/ftp-kicad-<ts>/ftp-kicad-<ts>.board.xml`, the pressed thumbnail's `img` has loaded.
7. **Replace**: a second dialog; the same bytes dropped on `#board-import-drop` (`dispatchEvent('drop', {dataTransfer})`),
   the same name → `#board-import-exists`; first press → `Press again: replace board`; Esc during the save does nothing;
   second press → saved.
8. Plan the import at 120/120/10/30°: 160 pads and dots; R pad X/Y equal step 1's within 0.01 mm, LED pads within
   0.04 mm; `.pv-a-cu`, `.pv-a-silk` (Full); the alignment check against the import's own art (FID1 included).
9. **Camera is on FID1**: `#btn-board-use-fid` sets the form X/Y to `cam − R(30°)·FID1` within 0.01 mm
   (`IL.positions().cam` of the session).
10. **Attach**: upload ftp again, press `#btn-board-import-attach` → toast; `00-ftp.board.xml`'s thumbnail and
    `GET /api/boards` give `art.kind` `kicad`; its plan's pads are identical to step 1's.
11. Records: the record of PW's 20-dot run shows `.rec-mapbox .pv-art .pv-a-edge`.
12. **Layout** at 1280 × 800 and 1024 × 768: `.stage-bar` and the expanded `#run-preview` have `scrollWidth ≤
    clientWidth + 1`; `#plan-svg` is ≥ 280 px tall.
13. Dark theme: the contrast check of (a)3 on the live hero.
14. Screenshots, light and dark, of the hero, the inspector and the upload preview for ftp, mobo and nano (each
    uploaded under a unique name) at 1280 and 1440, into `OUT` for human review (as `~/instancelabs/ui-revamp/impl-shots`).
15. Its own "no JavaScript errors" and "no native dialogs" checks.

### 6.7 Sim acceptance: scenario `import` (`il_sim_acceptance.py import`, also in `all`)

1. `stage` + session as the board scenario (src/test/sim/il_sim_acceptance.py:156-170, :705); `setup_machine`.
2. `POST /api/boards/import?file=ftp.kicad_pcb` with `F/ftp.kicad_pcb.gz` gunzipped (the staged config is a copy of
   the live one): counts 80 + 3; `PACKAGE_SUFFIXED` for the R, LED and fiducial packages; `attach` names the staged
   ftp.board.xml with FID1 0.3136 mm.
3. Save as `ftp-kicad`. Check the three files, `index.json`, and that the stage's packages.xml, parts.xml and
   boards.xml contain the new ids, while `live_config_untouched` stays true (:1039, :1074-1075).
4. Plan at `BOARD_LOC` (:68-73, a rotated seat) with the surface tied as `plan_board` does (:315-325): 160 pads. R pad
   centres equal `pads_from_board(ftp.board.xml)` (:525-537) mapped through the plan's `toMachine` within 0.01 mm; LED
   pads within 0.04 mm; FID2/FID3 at the same place; FID1 0.3136 mm off.
5. Run 20 dots → OK; the engine's dots match the plan's dots (`engine_dots`, :553-572).
6. Attach the drawing to the staged ftp.board.xml; its plan is unchanged (pads equal before and after).
7. **Restart** the session on the same stage: `GET /api/boards` still lists `ftp-kicad` with `kicad` art, the attached
   drawing is still found for ftp.board.xml, and a new plan has 160 FOOTPRINT pads.
8. `results.json` gets an `import` section. With AR merged, the `register` scenario can run on the imported board too.

`src/test/sim/il_ui_session.py start … --kicad F/ftp.kicad_pcb.gz`: optional; imports and plans the KiCad board for
screenshots (integration slice).

---

## 7. What only the real machine can confirm (to REAL_MACHINE_TODO.md)

1. An imported ftp board plans its LED dots 0.0375 mm further in than today's pad-type plans (true KiCad pads):
   check the dots on the LEDs with the top camera after a run.
2. **Which FID1 is right** (§2.6, OQ4). After Register board on ftp, image the dots on the low rows (board y ≈ 17–22,
   R19 and its row) planned from ftp.board.xml and from the imported board, both registered to the same measured
   fiducials. They differ by up to 0.25 mm; the set that sits on the pads names the right FID1. Until then, do not mix
   or compare plans of the two files.
3. After Register board, the drawn copper overlays the camera image within the registration residual (a visual check
   of the art transform on a real seat).
4. "Camera is on FID1" puts the plan inside AR's fiducial search window on a real seat (a re-seat of several mm).
5. Nano and mobo apertures (−0.0762 mm margins, QFN windows) against a real stencil's openings, before a pad type is
   characterised for them.

---

## 8. Docs to update (integration slice)

- **`docs/instancelabs/WEB_API.md`**: §3 rows for `POST /api/boards/import`, `…/save`, `…/attach`,
  `GET /api/board-art/{id}`, the `GET /api/boards` fields, the plan's `board`, `dot`, pad and dot fields, the per-route
  body limit, the 404 for a missing board file; §5 the upload, render, preview and inspector; §6 the new test classes
  and counts; §7 replace "A board placed by hand … boards.xml when it saves" (WEB_API.md:183-184) with the import's
  immediate persistence, and add the limits (KiCad 5 best effort, no Gerber, bottom parts skipped, a job open in OpenPnP
  keeps its old copy, uploads refused while a task runs).
- **`docs/instancelabs/DISPENSE_API.md`**: §4 (DISPENSE_API.md:158-180) the plan fields, `PadTypeResolver.explain`,
  the fingerprint extension, the per-dot times, the art id fixed at plan time.
- **`docs/instancelabs/UI_DESIGN.md`**: §8.1 hero (art, picture control, preview, a pad click opens the inspector
  instead of the camera, UI_DESIGN.md:233-234), §8.1.1 states (the board before planning), §8.1.2 Board step (upload,
  thumbnails, Camera is on FID1), §8.5 Records map, §11 components (upload dialog, inspector, preview row), §12 tokens
  (`--pv-a-*`, `--pv-o-*`, palettes, casing), §15 render rules (two SVGs, art built once per art id, the preview loop,
  the performance gate), §16 ids (§5.12).
- **`docs/instancelabs/REAL_MACHINE_TODO.md`**: §7.
- **`W/DEV_NOTES.md`**: the files table (DEV_NOTES.md:11-30) gains the seven files and owners; the boardview API; the
  hooks of §5.1.1; the CSS vocabulary gains the board palette and overlay tokens and the casing rule.
- **`F/LICENSE.txt`** (§6.1).

---

## 9. Implementation slices (disjoint files; builders in parallel worktrees)

| Key | Wave | Depends on | Owns |
|---|---|---|---|
| `kicad` | 1 | – | K/* (all new), F/* (fixtures, oracles, LICENSE.txt), T/kicad/* |
| `plumbing` | 1 | – | D/DispensePlan, DispensePlanner, PadGeometry, PadTypeResolver, BoardPadExtractor, GcodeProgram, GcodeStreamBuilder; D/BoardContentDigest (new); O/model/Configuration (`saveLibraryFiles`); B/EdtRunner (new); T/dispense/PlanBoardFrameTest, PadTypeResolverExplainTest, DotTimesTest, BoardContentDigestTest (new); `src/test/java/org/openpnp/model/ConfigurationSaveLibraryTest`, T/board/EdtRunnerTest (new) |
| `web-render` | 1 (its boardview.js part merges first, §10.3) | – (codes against §4.1, §4.5, §5.2; runs on the base jar through devproxy fixtures) | W/boardview.js; W/boardart.js, pathpreview.js, padinspect.js, board.css (new); W/index.html (its four tags); src/test/web/devproxy.py (`--fixtures`), boardview.playwright.cjs, run-boardview-test.sh, fixtures/sample.art.json, fixtures/board-map.json (new) |
| `art` | 1b | `kicad` (model classes land first) | B/BoardArt, BoardArtBuilder, ArtPath, BoardArtFallback, BoardArtStore, BoardArtIndex, KeepOut (new); T/board/BoardArtBuilderTest, ArtPathTest, BoardArtFallbackTest, BoardArtStoreTest, BoardArtFixturesTest (new); src/test/web/fixtures/art/* (generated) |
| `import-api` | 2 | `kicad`, `art`, `plumbing`; rebased on AR tasks-api | A/BoardImportEndpoints, BoardPlanView, BoardListView; B/BoardImporter, BoardStore, IdAllocator (new); the small edits of §10.1 in A/ApiServer, DispenseEndpoints, Views, PlanStore and D/DispenseService; T/api/BoardImportSimTest, T/board/BoardImporterTest, IdAllocatorTest (new) |
| `web-flow` | 2 | `web-render` merged; `import-api` to run end to end | W/boardflow.js, boardimport.js, boardimport.css (new); W/index.html (its three tags, after web-render); src/test/web/board-import.playwright.cjs (new) |
| `integration` | 3 | all, and AR's web-ui and tasks-api | the hooks of §5.1.1 if AR's web-ui did not add them; one line in src/test/web/run-webui-sim-test.sh; src/test/sim/il_sim_acceptance.py, il_ui_session.py; T/api/BoardImportRegistrationSimTest (new); docs (§8) and W/DEV_NOTES.md; the AR wiring of `keepOuts`/`fiducials` if agreed (§10.2); fixes after the merge |

At most four builders run at once (kicad, plumbing, web-render, art). **Contracts** the parallel slices code against:
the `KicadPcb`, `BoardFrame`, `ImportProposal` and `KText` signatures (§2); the art JSON (§4.1) and `BoardArtStore`
methods (§4.4); the plan JSON and `DispensePlan` fields (§4.5); the endpoint JSON (§3.2); `boardview.js`'s API and
structure (§5.2), `IL.boardart`, `IL.pathPreview`, `IL.padInspect`, `IL.boardFlow` (§5.1, §5.1.1); `EdtRunner.call`
and `Configuration.saveLibraryFiles` (§3.6); `ApiServer.route(…, maxBodyBytes)`, `Request.bytes()`,
`Response.header` (§3.3); devproxy's fixtures protocol (§6.6).

The build stays green at every merge: `env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -o -q -T1 -Dmaven.test.skip=true package`
and `mvn -B -o -T1 test`.

---

## 10. With the autoreg design

### 10.1 Files both designs change

AR's slices own these (AR:2429-2440). This design keeps its edits there small and puts its logic in its own classes:

| File | AR slice | This design's change | Size |
|---|---|---|---|
| A/ApiServer.java | tasks-api | per-route body limit, `Request.bytes()`, `Response.header`, register `BoardImportEndpoints` | ~80 lines |
| A/DispenseEndpoints.java | tasks-api | `boards()` through `EdtRunner` + `BoardListView.addTo`; `plan()` sets the art id; `estimate()` dot times and dot size; `currentBoardLocation` through `EdtRunner` + the missing-file 404 | ~30 lines |
| A/Views.java | tasks-api | one call `BoardPlanView.addTo(out, e)` | 1 line |
| A/PlanStore.java | tasks-api | Entry fields (`dotArriveS`, `dotEndS`, `dotDiameterMm`); `subset` calls `copyBoardFieldsTo` | ~6 lines |
| D/DispenseService.java | tasks-api | `boardFingerprint` appends `BoardContentDigest.of(board)`; `planBoardFile` through `EdtRunner` + the missing-file 404 | ~8 lines |
| W/boardview.js | web-ui | §5.2 (rewritten by web-render; AR draws `g.pv-l-fids` in it) | large: lands first (§10.3) |
| W/run.js | web-ui | hooks H1–H3 (§5.1.1) | ~8 lines |
| W/records.js | web-ui | hooks H4–H5 | ~2 lines |
| W/DEV_NOTES.md | web-ui | files, API, hooks, tokens (integration, after AR) | docs |
| src/test/web/run-webui-sim-test.sh | web-ui | one line: run board-import.playwright.cjs after PW | 1 line |
| src/test/sim/il_sim_acceptance.py, il_ui_session.py | integration | scenario `import`, `--kicad` | new code |
| docs/instancelabs/WEB_API.md, DISPENSE_API.md, UI_DESIGN.md, REAL_MACHINE_TODO.md | integration | §8 | docs |
| D/DispensePlanner.java | – (this design's plumbing) | §4.5; AR §16 item 1 changes the needle floor at "planner call sites" (AR:2580-2592): may touch it too | ~40 lines |

No longer shared (rev 1 changed them): PW (`webui-sim.playwright.cjs`) and `T/api/ApiServerSimTest.java`: this design's
browser checks and static-file checks are in their own files. Interface-sensitive but not shared:
`D/BoardPadExtractor.java` (AR's BoardFeatures and SimBoardRenderer call it; the signature is unchanged).

### 10.2 What this design asks of AR

1. **`BoardRegistration.matches`** (engine-core) also requires every stored fiducial's board coordinates (`Fid.bx/by`,
   AR:534) to equal the board's current top, enabled Fiducial placement of the same name within 1e-4 mm. Without it, a
   replace or a hand edit that moves a fiducial keeps planning with the old affine under `registration: "auto"`. This
   replaces rev 1's `forgetBoardRegistration` hook, which AR never had and which would miss hand edits. Test: AR's
   unit test of `matches`, and §6.5's `BoardImportRegistrationSimTest`.
2. **Hooks H1–H5** (§5.1.1) in run.js and records.js, guarded by `IL.boardFlow`, in AR's web-ui slice.
3. **boardview.js order**: web-render's boardview.js lands first (with `g.pv-l-fids` in the overlay SVG and
   `update({fids})` accepted and ignored); AR's web-ui then draws its fiducial overlay there with the `cased()` helper
   and the overlay tokens of §5.3 (its dashed search circles cross the mask, so they need the casing).
4. **Board features from the art** (optional, wired in integration): `BoardFeatures` takes an optional
   `Map<String, double[]> fiducialHints` (name → copper and mask mm) that tasks-api fills from
   `BoardArtStore.fiducials(board)`; `ProbeTargets` takes the extra keep-out circles of AR §16 item 10 from
   `BoardArtStore.keepOuts(board)` (holes and vias of top and bottom parts, outline and cut-out chains), and, if AR
   wants it, the exact `outlineLoops` (OQ10).
5. Nothing in this design edits PW; AR's Playwright block stays where AR put it (AR:2214-2216).

### 10.3 Joint merge order

1. Wave 1: AR vision, sim, engine-core; this design's kicad, plumbing, web-render (**its boardview.js change merges
   first**, as a small PR), then art.
2. Wave 2: AR tasks-api, then import-api rebased on it; AR web-ui (after web-render's boardview.js, adding the hooks),
   then web-flow.
3. Wave 3: integration (both designs).

---

## 11. Open questions (for the main thread; the design takes the stated default)

1. **DNP parts still get paste.** The brief makes DNP placements disabled. The engine pastes disabled placements by
   default, on purpose (`includeDisabledPlacements = true`, D/DispenseConfig.java:176-177; D/BoardPadExtractor.java:45),
   and ftp.board.xml relies on it (D1–D40 are disabled, ftp.board.xml:4-160). Default: the brief's rule plus
   `DNP_STILL_PASTED`. The alternative: leave DNP footprints out of the placements (drawn and listed only).
2. **Fiducial rule.** The brief's rule (library `Fiducial` or ref `FID*`) misses nano's FD1/FD2 (library `NanoV3.3`,
   footprint `Fiducial_1mm_Mask1.5mm`). Default: also match footprint names starting with `Fiducial`.
3. **Pad-less packages from the `.pos` import.** With the live configuration, R_0603_1608Metric, LED_0603_1608Metric
   and Fiducial_1mm_Mask3mm exist without pads (`~/.openpnp2/packages.xml:83-102`), so an ftp import makes `~2`
   packages and parts, and explicit pad-type mappings do not follow (`PACKAGE_MAPPING_LOST`). Default: suffix and
   warn; for ftp.board.xml itself, attach the drawing instead (§3.10). Should the import offer to copy a mapping to
   the suffixed id (it changes the resolver fingerprint and makes every plan stale)?
4. **Which FID1 is right?** The bench's registrations fit Y scale 0.9935–0.9947 with ftp.board.xml's hand-moved FID1
   and 0.9983–0.9995 with KiCad's (§2.6); registered dots of the two files differ by up to 0.254 mm on the low rows.
   Default: the import uses KiCad's value, ftp.board.xml is not changed, the drawing attached to ftp.board.xml shows
   KiCad's FID1 0.31 mm away from the board file's, and §7 item 2 decides on the machine. Until then, should plans of
   the two files be kept apart, and should ftp.board.xml's FID1 be restored to (62.5, 5.0) if the check confirms it
   (which changes every existing ftp.board.xml plan and registration)? The other documented difference: the LED pads,
   0.0375 mm closer than today's pad-type plans (tests: R pads within 0.01 mm, LED pads within 0.04 mm).
5. **Writing packages.xml, parts.xml and boards.xml at save** (`Configuration.saveLibraryFiles` in OpenPnP's core
   class). Side effects: unsaved package or part edits made in OpenPnP's window are written at the same time, and every
   board in OpenPnP's list (including files an API plan added) is persisted in boards.xml. Acceptable?
6. **The board fingerprint** (an AR-owned file) is extended with part ids, package ids and footprint digests, because
   "the existing fingerprint makes old plans stale" does not hold for pad or part changes (D/DispenseService.java:408-421).
7. **Registration after a replace or an edit**: closed by asking AR for the `matches` change (§10.2 item 1). Does AR
   accept it?
8. **Unverified KiCad semantics** (no fixture exercises them): KiCad 5 arc direction (positive = clockwise on screen),
   and whether an explicit `0` paste margin overrides from KiCad 8 (`version ≥ 20240108`). Both are pinned by synthetic
   tests and flagged in the preview.
9. **Pin-in-paste.** "SMD pads on F.Paste" leaves out through-hole pads with paste (`THT_PASTE_SKIPPED`). Should they
   be dispensed?
10. **QFN windows and the probe's allowed region.** mobo U2's nine windows resolve as perimeter pads
    (PadTypeResolver.java:71-77); default: no change, a warning. Keep-outs are settled in AR's `{x, y, r}` format
    (§4.4). Remaining: does AR want the exact outline loops (`outlineLoops`) for its allowed region instead of the
    boundary and fill circles?
11. **Extensions to the brief**: footprints with paste that are `exclude_from_pos_files` or `board_only` become
    disabled placements (pasted, not placed); duplicate references get `~2`; the upload cap is 12 MiB; KiCad 10 is
    accepted; "attach a drawing" (§3.10); a board with no outline and fewer than 2 fiducials cannot be saved (§2.6);
    uploads are refused while a task runs.
12. **Licence of the upstream KiCad 10 files** (pi10, ftp0402, at opulo-inc/lumenpnp @ 64cf68d `src/PCB`): the
    LICENSE's item 1 grants CERN-OHL-W v2 for `./pnp/pcb`, and upstream moved the same designs to `src/PCB` without
    updating the text. Default: treat them as CERN-OHL-W v2 with credit and marks removed. If not acceptable, the KiCad
    10 test uses a synthetic file with the KiCad 10 header and forms, and ftp0402 is dropped.
13. **Hooks in AR's run.js and records.js** (§5.1.1): added by AR's web-ui (preferred), or by the integration slice
    after it merges.

---

## 12. Critique log

Each point, what was done, and where. Claims were checked in the code, the fixtures or the bench files before acting;
the check is cited in the section named.

### data-correctness

| # | sev. | point | verdict |
|---|---|---|---|
| 1 | blocker | unsanitised strings break OpenPnP's XML | **Fixed**: §2.2 `KText` (one cleaning function for ids, texts, the file name and the board name; `ID_CLEANED`), §3.6 (the board read back before it moves; `saveLibraryFiles` reads each file back before moving it), tests §6.2, §6.4, §6.5 item 11d. Verified with a scratch program on simple-xml 2.9.0: U+0007 and U+FFFE fail to read back; `\n` and `\t` become spaces. |
| 2 | major | id allocator collisions; concurrent saves | **Fixed**: §3.5 `IdAllocator` (one case-insensitive `taken` set, one counter per base for variants and config clashes, parts keyed by (package, value), `ids-exhausted` at `~99`, re-check before each add), §3.4 (one pipeline at a time for preview, save and attach; headless `EdtRunner` lock), tests §6.5 items 10, 11, 14. Changed: no separate static lock, because the `PIPELINE` semaphore already covers allocation and adds. |
| 3 | major | FID1 is not cosmetic | **Fixed**: §2.6 (refit of all 11 bench registrations: Y scale 0.9935–0.9947 vs 0.9983–0.9995; 0.254 mm at R19, 0.336 mm worst on the board), `Fid1RegistrationTest` (§6.2), §7 item 2, OQ4. |
| 4 | major | art looked up at view time, not tied to content | **Fixed**: §4.5 (art id stored in the plan when it is made, copied to subsets), §3.7 (index by geometry digest, `history/` keeps displaced art servable), §4.4 (`note` when the board changed), tests §6.5 items 7–8. Changed: the digest is of the placements' geometry, not the board.xml bytes, so toggling `enabled` or a byte-identical copy keeps the drawing; and `<slug>.art.json` stays the current art as the brief lays out, with `history/` beside it instead of a pointer file. |
| 5 | major | bottom-side holes and non-rectangular outlines missing | **Fixed**: §2.7 (classification decides placements only), §4.2 (holes, `*.Cu` pads, mask openings and Edge.Cuts from every footprint; vias only when their pair includes F.Cu), §4.4 `keepOuts` (outline and cut-out chains, fill circles outside the outline), tests §6.2 `NanoDrillOracleTest`, §6.3. Verified: nano J1/J2's 30 bottom pins land on the drill file's hits with the top transform. |
| 6 | major | KiCad 10 and KiCad 7 | **Fixed**: §2.5 (KiCad 10 column, gate above 20260206), §2.4 (three tenting forms), §6.1 (pi10, pi7, ftp0402), §6.2 tests. Verified: pi10 is `20260206`/`10.0` with the nested tenting form; pi7 is `20221018`; nanoPaste was written by 10.0.5. The upstream licence path is OQ12. |
| 7 | major | no limits after parsing; previews during tasks | **Fixed**: §2.2 (number ranges, 1° arc steps, ≤ 64 Bézier segments, 2 M art points, one 20 s deadline over the whole pipeline, 1.5 M items, the unfill hint), §4.2 (iterative Douglas–Peucker, hash-grid chaining), §3.4 (preview, save and attach refused while a task runs), tests §6.2, §6.3. |
| 8 | major | a saved registration survives a re-import or an edit | **Fixed** as (a): §3.7 and §10.2 item 1 ask AR's `matches` to compare the fiducials' board coordinates; test `BoardImportRegistrationSimTest`. **Rejected** (b) the extra forget-on-replace: with (a) it is redundant, and a replace that keeps every fiducial keeps a registration that is still physically right. |
| 9 | minor | configuration reads off the event thread; undisposed boards; singletons | **Fixed**: §3.6 (`boards()`, `currentBoardLocation`, `planBoardFile` through `EdtRunner`; the board built on the EDT; the off-list and replaced boards disposed unless a job holds them; stores keyed by configuration directory), test §6.5 item 14. Also fixed what the evidence showed: a plan of a missing path no longer creates an empty board file (404). |
| 10 | minor | save is not one transaction | **Fixed**: §3.6 (stage, one EDT call, ordered steps, exact undo, packages → parts → boards), test §6.5 item 12; §3.6 documents which boards get persisted. |
| 11 | minor | estimated origin | **Fixed** in part: §2.6 origin kinds (`outline`, `edges`, `estimated`); a board with an estimated origin and < 2 fiducials cannot be saved; "Camera is on FID1" places the others (§5.9). **Not adopted**: refusing Run without a registration (AR owns run gating), and treating an open outline as estimated (its Edge.Cuts extents are still the physical edges). |
| 12 | minor | pad id clashes; `/` in routes | **Fixed**: §2.7 `PAD_ID_CLASH`; §3.9/§5.7 links with `encodeURIComponent` (the router already decodes each part, W/core.js:2075-2076). |
| 13 | minor | KiCad 5 polygons without a fill token | **Fixed**: §2.4 fill rule (absent token: polygons off Edge.Cuts and zero-width rects/circles are filled), §2.5 row, test §6.2. |
| 14 | minor | custom-pad position, strokes, footprint paste zones, rotated rectangles | **Fixed**: §2.8 (box centre in the pad frame, half stroke widths, zone fills as sources or `PASTE_ZONE_IGNORED`, rotated rectangles exact, other convex polygons by minimum-area rectangle), tests §6.2. |
| 15 | minor | coarsening pads and mask openings | **Fixed**: §4.2 two tolerances (outline, exposed copper, mask openings, paste, holes always 0.01 mm), test §6.3. |
| 16 | minor | fiducial mask diameter not kept | **Fixed**: §4.1 `fiducials`, §4.4 `BoardArtStore.fiducials`, §10.2 item 4 (an optional hint to AR's BoardFeatures; OpenPnP packages have no mask field). |
| 17 | minor | no independent oracle | **Fixed**: §6.1 nanoPaste and the two drill files; §6.2 `NanoPasteOracleTest` (55 apertures: centre, size, corner radius, rotation) and `NanoDrillOracleTest`. Checked: `%ADD11RoundRect,0.103800…` = 0.18 − 0.0762. |

### ux-render-test

| # | sev. | point | verdict |
|---|---|---|---|
| 1 | major | overlay unreadable on the board palette | **Fixed**: §5.3 (one physical palette; two-tone casing; states restated under `.pv--art`; dots never white; legend swatches on the same tokens; a Playwright contrast check over both themes, 8 palettes, Full and Quiet), §6.6 (a)3. Verified the critique's numbers (e.g. `--live` 1.44:1 on the mask composite `#3b5359`) and computed the new ones (casing ≥ 6.5:1 everywhere). |
| 2 | major | pad-type colours | **Fixed**: §5.4 (no type colour; pad type in the tooltip and the inspector). **Not adopted**: an optional "colour by pad type" layer, which nobody asked for. |
| 3 | major | art repaints with the overlay; no budget | **Fixed** (1) exposed copper as its own path, no clip path (§4.1, §5.2); (2) a separate art SVG (§5.2); (3) the pre-path rewritten only when n changes, a separate leg, ≤ 30 updates/s (§5.5); (5) a performance gate on mobo at 4× CPU throttling (§5.5, §6.6). **Rejected** (4) culling art paths by the part box: each layer is one merged path whose box covers the board, so nothing would be culled. |
| 4 | major | a stray file drop replaces the app | **Fixed**: §5.8 page-wide drop guard; test §6.6 (b)4. Verified: no `dragover`/`drop` handler in W/*.js. |
| 5 | major | the operator must find a virtual origin | **Fixed**: §5.9 origin marker, `#btn-board-use-fid` (Camera is on FID1), the live camera crosshair and scale bar in the pre-plan hero; `fiducials` in `GET /api/boards` (§3.8); tests §6.6 (b)9, §7 item 4. |
| 6 | major | warnings unordered, jargon, links leave the preview | **Fixed**: §2.10 (groups, order, plain texts with what to do; `PACKAGE_SUFFIXED` is a note unless a mapping is lost), §5.8 (outcome tint, package rows highlight their parts, pad-type actions after the save, Esc inert while saving, a toast for every result). |
| 7 | major | layer toggles hidden when needed; crowded bar | **Fixed**: §5.3 one Full / Quiet / Off control, always visible, Quiet by default under a plan and in Records; §5.6 the preview collapses to one button; §5.13 the 1280/1024 layouts and their Playwright check. |
| 8 | major | ftp.board.xml only gets fallback art | **Fixed**: §3.10 attach (no package, part, plan or fingerprint change), tests §6.5 item 13, §6.6 (b)10, §6.7 step 6. Changed: the match uses the digest index of §3.7 rather than a separate attached.json, and the attach dialog states FID1's 0.31 mm. |
| 9 | major | no browser alignment test; rotation 0 only | **Fixed**: §6.6 (a)2 and (b)1, (b)8 at 30°, through `getScreenCTM()`; Records checks `.pv-a-edge`; dark theme contrast (b)13; screenshots of ftp, mobo, nano at 1280 and 1440 (b)14. |
| 10 | major | slices not parallel with AR; web-render untestable in wave 1 | **Fixed**: §5.1.1 (five guarded hooks instead of ~150 lines in run.js), new `boardflow.js` and `boardimport.css` (web-flow), web-render owns index.html in wave 1 and devproxy `--fixtures` with its own harness test (§6.6 a), import-api's shared edits reduced to one-line calls (§10.1), this design's browser checks in their own file with their own preconditions (PW untouched), the joint merge order (§10.3). |
| 11 | major | inspector jargon | **Fixed**: §5.7 sentences (the matched text, not the regex; the pattern behind a disclosure), the amount as "A 0.10 mm stencil would print …", plain flag texts; the test matches the sentences (§6.6 b2). |
| 12 | major | uploads during runs | **Fixed**: §3.4 (preview, save and attach refused with `requireEngineIdle`), §5.9 (the button greyed with the reason), test §6.5 item 10. |
| 13 | minor | over-engineering | **Fixed**: stateless save (§3.1; no sessions, ids, expiry or DELETE), no client art cache (§4.4), `hitAt` and `typeColours` dropped (§5.2). |
| 14 | minor | heavy thumbnails, grid pushes the form down | **Fixed**: §4.1 `thumb` block (0.1 mm, a few KB), blob images (§5.5), one scrolling row ≤ 76 px (§5.9). |
| 15 | minor | labels over pads, 4 px text | **Fixed**: §5.3 labels at the KiCad Reference position when on F.SilkS; three size buckets shown from 9 px. |
| 16 | minor | board colours ignored | **Fixed**: §2.4 stackup colours, §4.1 `finish`, §5.3 eight tuned mask palettes, all in the contrast test. |
| 17 | minor | sibling lookup breaks under uinav copies | **Fixed**: §3.7 geometry-digest index (uinav copies keep the same placements; verified uinav.py:307-331 renames the copies); Playwright uses unique names (§6.6 b). |
| 18 | minor | inconsistent counts, readout, timing | **Fixed**: §2.9 `counts.placements` excludes fiducials everywhere, §2.11 row renamed; §5.6 scrub v = dots done, ghost and readout on dot v+1; §6.6 `waitForFunction` instead of a fixed wait. |
| 19 | minor | keep-out format; missing AR method | **Fixed**: §4.4 `keepOuts` → `{x, y, r}` (AR §16 item 10), OQ10 narrowed; the AR method is replaced by the `matches` request (data-correctness 8). |

---

## 13. Main-thread decisions on §11 (2026-10-29, binding for the build)

1. **OQ1 DNP:** a do-not-place footprint is not populated, so it gets **no paste**: by default it becomes art only
   (no placement) and the preview lists it ("Not placed (DNP): no paste"). One preview checkbox, "Paste do-not-place
   parts too" (off), turns them into disabled placements (which the engine pastes, like ftp.board.xml's LEDs).
   "Disabled" in OpenPnP means "not picked and placed", which is not the same thing as DNP.
2. **OQ2:** yes, footprint names starting with `Fiducial` are fiducials too.
3. **OQ3:** suffix and warn as designed, and **carry the base package's pad-type mapping over to the suffixed id**
   (shown in the preview: "uses the pad types of R_0603_1608Metric"; plans go stale once, Plan again fixes it), so an
   imported FTP board has pad types without a trip to Profile. Attach stays the way to give ftp.board.xml its drawing.
4. **OQ4:** the default (the import uses KiCad's FID1; ftp.board.xml stays as it is; the real machine decides).
   Integration may add an offline check if the bench's after-photos near R19 can show a ~0.25 mm Y offset; report it,
   change nothing.
5. **OQ5:** acceptable; the save confirmation says it also saves OpenPnP's parts, packages and board list.
6. **OQ6:** agreed. **OQ7:** agreed (engine-core or, if it is already merged without it, tasks-api adds it with a test).
7. **OQ8, OQ9, OQ10:** the defaults (flag unverified KiCad semantics in the preview; no pin-in-paste; `{x, y, r}`
   keep-outs only).
8. **OQ11:** all accepted.
9. **OQ12:** treat the upstream lumenpnp files as CERN-OHL-W v2 with credit and the Opulo marks removed. Fixtures over
   1 MB are stored gzipped (`*.kicad_pcb.gz`, read with `GZIPInputStream` in the tests).
10. **OQ13:** AR's web-ui slice adds hooks H1–H5; web-render's boardview.js lands first with `g.pv-l-fids`.

---

## 14. As built (2026-10-29)

Merged on `il/bench-sync-autoreg` (b81d73d21): `kicad`, `art`, `plumbing`, `web-render` (with its visual pass on the
real art) and `import-api`; then, on `il/rc1`, `web-flow` (`board-import.playwright.cjs`, 57 checks against the real
import-api in a SIM session) with AR's web-ui (the AR + BI integration slice), which also passes the art's keep-outs
(drills, vias) to `ProbeTargets` (§10.2; a board without art still gets the assumed mounting hole in each corner, AR
§17). The API as built is in `WEB_API.md` §3.3. Where the code differs from the sections
above, the code is right:

- **§3.2 preview.** `POST /api/boards/import?file=…[&pasteDnp=1]`: `pasteDnp` is decision 1's checkbox ("Paste
  do-not-place parts too"; off: DNP footprints are art only). `placements[]` also carry `ref`, `value` and `why`;
  `exists` also `how` (`import` | `attach`). Each package carries `usesPadTypesOf` (decision 3's carried mapping, not
  `padTypesFrom`), `lib`, `kicadName`, `bodyW`, `bodyH`, `untypedPads` and its `outcome`.
- **§3.2 save and attach.** The 409 `preview-stale` carries the new preview as `preview` (not `details`). Attach
  answers 201 `{slug, name, board, art, maxOffsetMm, offsets, replaced, boards}`. Uploads are refused (409 `refused`,
  `Upload the board when the run has finished.`) while the engine is busy or a pad calibration is between its engine
  steps; the check is made once the request holds the pipeline (§3.4), and while it holds it every engine task that
  checks the engine is idle (run, clean check, shots, ladders, pressure hold, Register board, probes, Check Z, Home,
  pad calibration) is refused with 409 `a board file is being read (an upload takes up to about 20 s): wait for it to
  finish` (final fix round).
- **§3.2, with the profile check (PC pc-api, built on `il/pc-api`).** Each package also gets `profileCheck` (its
  groups that need a profile), `profileReason` and `profileShort`; `outcome` becomes `needs-profile` (some pads wait for
  a profile) or `not-dispensed` (no pad can get paste: the tip, or a part treated whole), and one `NEEDS_PROFILE`
  warning replaces the `NO_PAD_TYPE` ones (PC §12.6).
- **§3.8 and §4.5.** `GET /api/boards` entries carry `widthMm`, `heightMm`, `fiducials`, `art` `{id, kind, note}`,
  `origin`, `imported` and AR's `registration`. A plan carries `board` `{name, file, widthMm, heightMm, side,
  toMachine, transformStatus, fiducials, art}` with the art id fixed when the plan is made (also Register board's and
  PC's plan-again), `dot` `{diameterMm, source}`, per pad `padName`, `partId`, `roundness`, `typeRule` `{kind, detail,
  matched, sentence}`, per dot `ta` / `te` (s from the program start). OpenPnP's board list and packages are read on
  OpenPnP's thread (`EdtRunner`, 10 s to start). A plan of a board file that is gone answers 404 `Board file not
  found: …` (`getBoard` would have written an empty file there).
- **§5.1.1 hooks.** H1–H5 are in AR's web-ui (`il/ar-webui`); `boardflow.js` fills them: H1 the Board step's
  `#btn-board-upload`, `#board-thumbs`, `#board-imported` and `#btn-board-use-fid`, H2 a pad or part click opens the
  inspector, H3 the hero's drawing, picture control (`il.pv.picture`: Full, Quiet (default), Off), path preview and
  `#btn-pad-inspect`, and before planning the chosen board's drawing at the form's seat with the live camera and a
  5 mm scale bar, H4 the Records map, H5 the replaced map view destroyed.
- **§5.8 upload dialog** (`boardimport.js`, `IL.boardImport.open({file, onSaved})`): states choose → reading → preview
  → saving → saved, warnings in the four groups (Blocks dispensing, Registration, Check in the preview, Notes); a file
  dropped outside the drop zone never replaces the page (a `.kicad_pcb` opens the dialog, anything else gets a toast).
- **Review 1 minors, fixed in the final fix round.** §2.7 has a rule between 2 and 3: a top footprint with a paste
  opening outside the board's box (beyond 0.001 mm) is art only (`artOnly`, why `outside the board`), and one
  `OFF_BOARD` warning (group 3) names them: `R2 is not inside the board outline: it gets no paste (its paste would land
  off the board). Move it onto the board in KiCad if it is part of this board.` (fiducials are not affected; no
  fixture changes). §2.8's package signature reads a pad rotation of −180.000 as 180.000, so a footprint at a decimal
  angle with a 180° pad is no longer split into two variants.
- **§7** is in `REAL_MACHINE_TODO.md` (items 33–37).
