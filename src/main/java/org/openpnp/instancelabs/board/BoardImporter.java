package org.openpnp.instancelabs.board;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openpnp.gui.MainFrame;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotMath;
import org.openpnp.instancelabs.dispense.PadInstances;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.kicad.BoardFrame;
import org.openpnp.instancelabs.kicad.Deadline;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportProposal.FootprintP;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.openpnp.instancelabs.kicad.ImportRules;
import org.openpnp.instancelabs.kicad.ImportWarning;
import org.openpnp.instancelabs.kicad.KText;
import org.openpnp.instancelabs.kicad.KicadParser;
import org.openpnp.instancelabs.kicad.KicadPcb;
import org.openpnp.instancelabs.kicad.SExprException;
import org.openpnp.instancelabs.padprofile.GroupKey;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

/**
 * The KiCad upload (design §3): a stateless preview of what a save would make, the save itself as one transaction on
 * OpenPnP's thread, and "attach" (the drawing for an existing board file whose placements match). The browser keeps
 * the file and sends it again for the save; each request reads it again under one 20 s budget. One preview, save or
 * attach runs at a time ({@link #PIPELINE}), which also bounds the memory a 12 MB upload needs.
 */
public final class BoardImporter {
    public static final int MAX_UPLOAD = 12 * 1024 * 1024;
    public static final String TOO_LARGE = "The file is larger than 12 MB. " + SExprException.UNFILL_HINT;
    /** How long OpenPnP's thread may take to start a change (§3.6). */
    static final long EDT_START_MS = 10_000;
    /** Attach: the same ids, each within 0.5 mm and 0.5°; offsets above 0.05 mm are listed (§3.10). */
    static final double ATTACH_MM = 0.5;
    static final double ATTACH_DEG = 0.5;
    static final double OFFSET_MM = 0.05;

    static final Semaphore PIPELINE = new Semaphore(1);
    /** Test hook: the save fails right after this step of §3.6 B (0: never). */
    public static volatile int failAfterStep;
    /** Test hook: runs inside the pipeline, before the file is read (a test holds the pipeline there). */
    public static volatile Runnable inPipeline;

    /** A refusal of §3.2: the HTTP status, the error code, the operator text and extra fields of the answer. */
    public static final class ImportException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;
        public final Map<String, Object> details = new LinkedHashMap<>();

        public ImportException(int status, String code, String reason) {
            super(reason);
            this.status = status;
            this.code = code;
        }

        ImportException with(String key, Object value) {
            details.put(key, value);
            return this;
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /** What must hold before a file is read: checked once the request holds the pipeline; it throws when not. */
    @FunctionalInterface
    public interface Gate {
        void check() throws Exception;
    }

    private final Configuration configuration;
    private final DispenseService svc;
    private final BoardStore store;
    private final Gate gate;

    /**
     * @param svc the dispense engine (its pad types, package mappings and minimum status); null: no pad types (tests)
     */
    public BoardImporter(Configuration configuration, DispenseService svc) {
        this(configuration, svc, null);
    }

    /**
     * @param gate checked inside the pipeline before the file is read (the API's engine-idle check: a task that
     *        starts meanwhile is refused while the pipeline is held, {@link #reading}); null: none
     */
    public BoardImporter(Configuration configuration, DispenseService svc, Gate gate) {
        this.configuration = configuration;
        this.svc = svc;
        this.store = new BoardStore(configuration.getConfigurationDirectory());
        this.gate = gate;
    }

    /** A preview, save or attach holds the pipeline: a board file is being read (or saved). */
    public static boolean reading() {
        return PIPELINE.availablePermits() == 0;
    }

    public BoardStore store() {
        return store;
    }

    // ==== the three requests =========================================================================================

    /** POST /api/boards/import: what a save would make of the file (§3.2). */
    public Map<String, Object> preview(byte[] bytes, String fileName, boolean pasteDnp) throws Exception {
        return pipeline(() -> {
            Parsed parsed = parse(bytes, fileName, pasteDnp);
            return new Preview(parsed, EdtRunner.call(this::snapshot, EDT_START_MS)).map;
        });
    }

    /** POST /api/boards/import/save (§3.6). */
    public Map<String, Object> save(byte[] bytes, String fileName, String rawName, boolean replace, String sha256,
            String decisions, boolean pasteDnp) throws Exception {
        return pipeline(() -> {
            checkBody(bytes);
            checkSha(bytes, sha256);
            String name = name(rawName);
            Parsed parsed = parse(bytes, fileName, pasteDnp);
            ImportProposal p = parsed.proposal;
            if (BoardFrame.ORIGIN_ESTIMATED.equals(p.origin) && p.counts.fiducials < 2) {
                throw new ImportException(400, "no-origin", "This board has no outline (Edge.Cuts) and fewer than "
                        + "2 fiducials, so its origin cannot be found on the machine. Add the board outline in KiCad "
                        + "and upload again.");
            }
            Preview pv = new Preview(parsed, EdtRunner.call(this::snapshot, EDT_START_MS));
            if (decisions == null || !decisions.equals(pv.ids.decisions())) {
                throw stale(pv);
            }
            return new Save(parsed, pv, name, replace).run();
        });
    }

    /** POST /api/boards/import/attach: the drawing for a listed board file whose placements match (§3.10). */
    public Map<String, Object> attach(byte[] bytes, String fileName, String boardPath, String rawName,
            boolean replace, String sha256, boolean pasteDnp) throws Exception {
        return pipeline(() -> {
            checkBody(bytes);
            checkSha(bytes, sha256);
            if (boardPath == null || boardPath.trim().isEmpty()) {
                throw new ImportException(400, "bad-request", "'board' (the board file to attach the drawing to) is "
                        + "required.");
            }
            File boardFile = new File(boardPath.trim());
            String name = name(rawName == null || rawName.trim().isEmpty()
                    ? boardFile.getName().replaceFirst("(?i)\\.board\\.xml$", "") : rawName);
            Parsed parsed = parse(bytes, fileName, pasteDnp);
            return new Attach(parsed, boardFile, name, replace).run();
        });
    }

    private <T> T pipeline(Work<T> w) throws Exception {
        if (!PIPELINE.tryAcquire()) {
            throw new ImportException(409, "busy", "Another board file is being read: try again in a moment.");
        }
        try {
            if (gate != null) {
                gate.check();
            }
            Runnable hook = inPipeline;
            if (hook != null) {
                hook.run();
            }
            return w.run();
        }
        finally {
            PIPELINE.release();
        }
    }

    // ==== reading the file ===========================================================================================

    /** One reading of the upload: reader, parser, frame, rules and art under one deadline. */
    static final class Parsed {
        final byte[] bytes;
        final String sha256;
        final String fileName;
        final KicadPcb pcb;
        final ImportProposal proposal;
        final BoardArtBuilder.Result art;

        Parsed(byte[] bytes, String fileName, KicadPcb pcb, ImportProposal proposal, BoardArtBuilder.Result art) {
            this.bytes = bytes;
            this.sha256 = BoardArt.sha256Hex(bytes);
            this.fileName = fileName;
            this.pcb = pcb;
            this.proposal = proposal;
            this.art = art;
        }
    }

    static Parsed parse(byte[] bytes, String rawFileName, boolean pasteDnp) throws ImportException {
        checkBody(bytes);
        String fileName = KText.clean(rawFileName, 120);
        Deadline d = new Deadline(Deadline.DEFAULT_MS);
        try {
            KicadPcb pcb = KicadParser.read(bytes, d);
            BoardFrame frame = BoardFrame.of(pcb, d);
            ImportProposal p = new ImportRules(true, pasteDnp).propose(pcb, frame, d);
            BoardArtBuilder.Result art = new BoardArtBuilder().build(pcb, frame, p, d);
            Parsed parsed = new Parsed(bytes, fileName, pcb, p, art);
            art.art.source.name = fileName.isEmpty() ? null : fileName;
            art.art.source.bytes = (long) bytes.length;
            art.art.source.sha256 = parsed.sha256;
            return parsed;
        }
        catch (SExprException e) {
            if (SExprException.NOT_KICAD.equals(e.kind)) {
                throw new ImportException(400, "not-kicad", e.getMessage());
            }
            if (SExprException.SYNTAX.equals(e.kind)) {
                throw new ImportException(400, "kicad-syntax", e.getMessage());
            }
            throw new ImportException(400, "too-complex", e.getMessage());
        }
    }

    static void checkBody(byte[] bytes) throws ImportException {
        if (bytes == null || bytes.length == 0) {
            throw new ImportException(400, "bad-request", "The upload is empty.");
        }
        if (bytes.length > MAX_UPLOAD) {
            throw new ImportException(413, "too-large", TOO_LARGE);
        }
        for (byte b : bytes) {
            if (b == '{') {
                throw new ImportException(400, "bad-request", "Send the file itself, not JSON.");
            }
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return;
            }
        }
    }

    private static void checkSha(byte[] bytes, String sha256) throws ImportException {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new ImportException(400, "bad-request", "'sha256' (64 hex digits, from the preview) is required.");
        }
        if (!sha256.equalsIgnoreCase(BoardArt.sha256Hex(bytes))) {
            throw new ImportException(400, "bad-request", "The file changed since the preview: upload it again.");
        }
    }

    private static String name(String raw) throws ImportException {
        String n = BoardStore.cleanName(raw);
        if (n == null) {
            throw new ImportException(400, "bad-request", "Name: 1 to 60 characters, at least one letter or digit.");
        }
        return n;
    }

    // ==== OpenPnP's lists, read on its thread ========================================================================

    /** What the preview needs from OpenPnP's lists: package and part ids, and the listed board files (attach). */
    static final class Snap {
        IdAllocator.Snapshot ids;
        final List<Listed> boards = new ArrayList<>();
    }

    /** A listed board file: its placements and fiducials, id → {x, y, rotation, 1 if a fiducial}. */
    static final class Listed {
        String file;
        String name;
        final Map<String, double[]> placements = new LinkedHashMap<>();
    }

    private Snap snapshot() throws IOException {
        Snap s = new Snap();
        s.ids = IdAllocator.Snapshot.of(configuration);
        String root = store.root().getCanonicalPath() + File.separator;
        for (Board b : configuration.getBoards()) {
            if (b.getFile() == null || b.getFile().getCanonicalPath().startsWith(root)) {
                continue; // imports are replaced, not attached to
            }
            s.boards.add(listed(b));
        }
        return s;
    }

    static Listed listed(Board b) throws IOException {
        Listed l = new Listed();
        l.file = b.getFile() == null ? null : b.getFile().getCanonicalPath();
        l.name = b.getName();
        for (Placement p : b.getPlacements()) {
            if (p.getType() != Placement.Type.Placement && p.getType() != Placement.Type.Fiducial) {
                continue;
            }
            Location loc = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            l.placements.put(p.getId(), new double[] {loc.getX(), loc.getY(), loc.getRotation(),
                    p.getType() == Placement.Type.Fiducial ? 1 : 0});
        }
        return l;
    }

    // ==== the preview ================================================================================================

    /** The preview of one reading against one snapshot: the JSON of §3.2 and the decisions behind it. */
    final class Preview {
        final Parsed parsed;
        final IdAllocator.Result ids;
        final PadTypes padTypes;
        final List<Map<String, Object>> packages = new ArrayList<>();
        final List<ImportWarning> warnings;
        final Map<String, Object> map = new LinkedHashMap<>();

        Preview(Parsed parsed, Snap snap) throws Exception {
            this.parsed = parsed;
            ImportProposal p = parsed.proposal;
            try {
                ids = IdAllocator.allocate(p, snap.ids);
            }
            catch (IdAllocator.Exhausted e) {
                throw new ImportException(409, "ids-exhausted", e.getMessage());
            }
            padTypes = padTypes(p, ids, snap.ids);
            ProfileCheck.Result check = profileCheck(p, ids, padTypes);
            for (int i = 0; i < p.packages.size(); i++) {
                packages.add(packageEntry(p.packages.get(i), ids.packages.get(i), padTypes, check));
            }
            List<ImportWarning> more = new ArrayList<>(parsed.art.warnings);
            more.addAll(padTypeWarnings(p, packages));
            more.addAll(idWarnings(p, ids, padTypes));
            warnings = allWarnings(p, more);

            String suggested = suggestedName(parsed.fileName);
            String slug = BoardStore.slug(suggested);
            map.put("file", map("name", parsed.fileName, "bytes", parsed.bytes.length, "sha256", parsed.sha256));
            map.put("decisions", ids.decisions());
            KicadPcb pcb = parsed.pcb;
            map.put("kicad", map("version", pcb.version, "generator", pcb.generator == null ? null
                    : KText.clean(pcb.generator, 60), "generatorVersion", pcb.generatorVersion == null ? null
                            : KText.clean(pcb.generatorVersion, 60), "major", kicadMajor(pcb)));
            map.put("board", map("widthMm", p.widthMm, "heightMm", p.heightMm, "outline", p.outline, "origin",
                    p.origin, "originKicad", map("x", p.minX, "y", p.maxY)));
            map.put("suggestedName", suggested);
            map.put("slug", slug);
            map.put("exists", existsView(store.existing(slug)));
            map.put("pasteDnp", p.pasteDnp);
            map.put("counts", p.counts);
            List<Map<String, Object>> placements = new ArrayList<>();
            List<Map<String, Object>> fiducials = new ArrayList<>();
            for (int i = 0; i < p.placements.size(); i++) {
                PlacementP x = p.placements.get(i);
                IdAllocator.PartId part = ids.partOf(i);
                placements.add(map("id", x.id, "ref", x.ref, "value", x.value, "type", x.type, "enabled", x.enabled,
                        "x", x.x, "y", x.y, "rotation", x.rotation, "packageId", ids.packages.get(x.packageIndex).id,
                        "partId", part.id, "pads", x.pads, "why", x.why, "outcome",
                        packages.get(x.packageIndex).get("outcome")));
                if ("Fiducial".equals(x.type)) {
                    BoardArt.FidArt f = fidArt(parsed.art.art, x.id);
                    fiducials.add(map("id", x.id, "x", x.x, "y", x.y, "diameterMm", f == null ? null : f.copperMm,
                            "maskMm", f == null ? null : f.maskMm));
                }
            }
            map.put("placements", placements);
            map.put("fiducials", fiducials);
            List<Map<String, Object>> artOnly = new ArrayList<>();
            for (FootprintP f : p.footprints) {
                if (FootprintP.ART.equals(f.kind)) {
                    artOnly.add(map("ref", f.ref, "why", f.why));
                }
            }
            map.put("skipped", map("bottom", p.skipped.get(ImportProposal.SKIP_BOTTOM), "artOnly", artOnly, "dnp",
                    p.skipped.get(ImportProposal.SKIP_DNP), "pasteOnly", p.skipped.get(ImportProposal.SKIP_PASTE_ONLY)));
            map.put("packages", packages);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (IdAllocator.PartId x : ids.parts) {
                parts.add(map("id", x.id, "packageId", x.packageId, "value", x.value, "state", x.state, "refs",
                        x.refs.size()));
            }
            map.put("parts", parts);
            map.put("warnings", warnings);
            List<Map<String, Object>> attach = new ArrayList<>();
            for (Listed l : snap.boards) {
                Match m = match(p, l);
                if (m.ok) {
                    attach.add(map("file", l.file, "name", l.name, "maxOffsetMm", m.maxOffsetMm, "offsets",
                            m.offsets));
                }
            }
            map.put("attach", attach);
            map.put("art", parsed.art.art);
        }
    }

    private ImportException stale(Preview pv) {
        return new ImportException(409, "preview-stale", "OpenPnP's packages or parts changed since the preview: "
                + "check the new preview, then save.").with("preview", pv.map);
    }

    /**
     * The profile check of the board as it would be saved (PROFILE_CALIBRATION_DESIGN.md §12.6): every paste opening of
     * its placements in the board frame, with the final package ids and the pad types they resolve to (the carried
     * mappings included); null without the engine.
     */
    ProfileCheck.Result profileCheck(ImportProposal p, IdAllocator.Result ids, PadTypes pt) throws SQLException {
        if (svc == null || svc.getDb() == null) {
            return null;
        }
        List<PadInstance> pads = new ArrayList<>();
        for (PlacementP x : p.placements) {
            PackageP pk = p.packages.get(x.packageIndex);
            if (pk.fiducial) {
                continue;
            }
            String pkgId = ids.packages.get(x.packageIndex).id;
            double c = Math.cos(Math.toRadians(x.rotation));
            double s = Math.sin(Math.toRadians(x.rotation));
            for (PadP pad : pk.pads) {
                PadInstance i = new PadInstance(x.id, pkgId, pad.name, x.x + pad.x * c - pad.y * s,
                        x.y + pad.x * s + pad.y * c, x.rotation + pad.rotation, pad.w, pad.h, pad.roundness);
                PadTypeResolver.Resolution r = pt.resolver.explain(pkgId, pad.name, pad.x, pad.y,
                        DotMath.padAreaMm2(pad.w, pad.h, pad.roundness));
                i.typeRule = r.rule;
                i.centrePad = PadTypeResolver.Resolution.CENTRE_PAD.equals(r.rule);
                if (r.type != null) {
                    i.typeKey = r.type.typeKey;
                    i.typeVersion = r.type.version;
                    i.typeStatus = r.type.status == null ? null : r.type.status.dbName;
                }
                pads.add(i);
            }
        }
        return ProfileCheck.of(PadInstances.inputs(svc, pads, false));
    }

    /**
     * One package of the preview (§3.2, §3.9): its id and why, its pads, the pad types its pads resolve to (with the
     * rule and a sentence), and the outcome of its placements: paste, no-pad-type or not-dispensed. The one place a
     * package's entry is built. With the profile check (PROFILE_CALIBRATION_DESIGN.md §12.6) the package also lists
     * its groups that need a profile, and needs-profile replaces no-pad-type: some pads wait for a profile;
     * not-dispensed when no pad of it can get paste (the tip, or parts treated whole).
     */
    Map<String, Object> packageEntry(PackageP pk, IdAllocator.PackageId id, PadTypes pt, ProfileCheck.Result check) {
        List<Map<String, Object>> pads = new ArrayList<>();
        for (PadP pad : pk.pads) {
            pads.add(map("name", pad.name, "x", pad.x, "y", pad.y, "w", pad.w, "h", pad.h, "rotation", pad.rotation,
                    "roundness", pad.roundness, "approx", pad.approx));
        }
        Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        int untyped = 0;
        if (!pk.fiducial) {
            for (PadP pad : pk.pads) {
                PadTypeResolver.Resolution r = pt.resolver.explain(id.id, pad.name, pad.x, pad.y,
                        DotMath.padAreaMm2(pad.w, pad.h, pad.roundness));
                PadType t = r.type;
                untyped += t == null ? 1 : 0;
                String k = (t == null ? "" : t.typeKey) + '|' + r.rule + '|' + r.matched;
                Map<String, Object> e = types.get(k);
                if (e == null) {
                    e = map("typeKey", t == null ? null : t.typeKey, "name", t == null ? null : t.name, "version",
                            t == null ? null : t.version, "status", t == null || t.status == null ? null
                                    : t.status.dbName, "rule", r.rule, "detail", r.detail, "matched", r.matched,
                            "sentence", ruleSentence(r.rule, r.matched, id.id), "pads", 0, "dispensable",
                            t != null && t.status != null && t.status.atLeast(pt.minStatus));
                    types.put(k, e);
                }
                e.put("pads", (Integer) e.get("pads") + 1);
            }
        }
        String outcome = pk.fiducial ? "not-dispensed" : untyped > 0 ? "no-pad-type" : "paste";
        Map<String, Object> e = map("baseId", pk.baseId, "variant", pk.variant, "id", id.id, "state", id.state, "why",
                id.why, "usesPadTypesOf", pt.carriedFrom.get(id.id), "lib", pk.lib, "kicadName", pk.kicadName, "refs",
                pk.refs, "fiducial", pk.fiducial, "bodyW", pk.bodyW, "bodyH", pk.bodyH, "pads", pads, "padTypes",
                new ArrayList<>(types.values()), "untypedPads", untyped, "outcome", outcome);
        if (check != null && !pk.fiducial) {
            List<Map<String, Object>> groups = new ArrayList<>();
            int needs = 0;
            int none = 0;
            ProfileCheck.Group highest = null;
            for (ProfileCheck.Group g : check.groups) {
                if (!g.packageIds.contains(id.id)) {
                    continue;
                }
                groups.add(map("group", g.group, "padNames", g.padNames, "reason", g.reason, "tipKind", g.tipKind,
                        "partial", g.partial, "short", g.shortText, "reasonText", g.reasonText, "suggestion",
                        g.suggestion));
                boolean noPaste = ProfileCheck.TIP.equals(g.reason) || ProfileCheck.PART.equals(g.reason);
                none += noPaste ? g.pads : 0;
                needs += noPaste || ProfileCheck.OUTSIDE.equals(g.reason) ? 0 : 1;
                if (!ProfileCheck.OUTSIDE.equals(g.reason) && (highest == null || REASON_ORDER.indexOf(g.reason)
                        < REASON_ORDER.indexOf(highest.reason))) {
                    highest = g;
                }
            }
            e.put("profileCheck", groups.isEmpty() ? null : groups);
            e.put("profileReason", highest == null ? null : highest.reason);
            e.put("profileShort", highest == null ? null : highest.shortText);
            e.put("outcome", needs > 0 ? "needs-profile"
                    : none > 0 && none >= pk.pads.size() * pk.refs.size() ? "not-dispensed" : "paste");
        }
        return e;
    }

    /** The profile check's reasons, highest first (§3.3): the one a package is named with in NEEDS_PROFILE. */
    static final List<String> REASON_ORDER = Arrays.asList(ProfileCheck.TIP, ProfileCheck.PART, ProfileCheck.NO_PAD_SIZE,
            ProfileCheck.NO_TYPE, ProfileCheck.BELOW_GATE, ProfileCheck.BRIDGE, ProfileCheck.OUTSIDE);

    /**
     * NO_PAD_TYPE: one warning per package whose placements are blocked until it has a pad type (group 1). With the
     * profile check, one NEEDS_PROFILE warning names every package with pads that cannot be pasted yet instead, each
     * with its highest reason (PROFILE_CALIBRATION_DESIGN.md §12.6).
     */
    @SuppressWarnings("unchecked")
    static List<ImportWarning> padTypeWarnings(ImportProposal p, List<Map<String, Object>> packages) {
        List<ImportWarning> l = new ArrayList<>();
        List<String> named = new ArrayList<>();
        List<String> needRefs = new ArrayList<>();
        boolean checked = false;
        for (Map<String, Object> e : packages) {
            checked |= e.containsKey("profileCheck");
            if (e.get("profileShort") != null) {
                named.add(GroupKey.basePackage((String) e.get("id")) + " (" + e.get("profileShort") + ")");
                needRefs.addAll((List<String>) e.get("refs"));
            }
        }
        if (checked) {
            if (!named.isEmpty()) {
                l.add(ImportWarning.of("NEEDS_PROFILE", needRefs, named.size() + (named.size() == 1 ? " package needs"
                        : " packages need") + " a profile: " + String.join(", ", named) + ". After saving, choose a "
                        + "suggestion for each in Profile › Needs a profile."));
            }
            return l;
        }
        for (Map<String, Object> e : packages) {
            if (!"no-pad-type".equals(e.get("outcome"))) {
                continue;
            }
            List<String> refs = (List<String>) e.get("refs");
            int pads = (Integer) e.get("untypedPads") * refs.size();
            boolean one = refs.size() == 1;
            l.add(ImportWarning.of("NO_PAD_TYPE", refs, ImportWarning.names(refs) + " (" + pads + (pads == 1
                    ? " pad)" : " pads)") + (one ? " gets" : " get") + " no paste until " + (one ? "its" : "their")
                    + " package has a pad type. Save the board, then add one: New pad type for " + e.get("id")
                    + "."));
        }
        return l;
    }

    /** PACKAGE_SUFFIXED and PART_SUFFIXED (notes): why an id got a ~n, and that other boards are not affected. */
    static List<ImportWarning> idWarnings(ImportProposal p, IdAllocator.Result ids, PadTypes pt) {
        List<ImportWarning> l = new ArrayList<>();
        for (IdAllocator.PackageId x : ids.packages) {
            if (!IdAllocator.SUFFIXED.equals(x.state)) {
                continue;
            }
            String carried = pt.carriedFrom.get(x.id);
            l.add(ImportWarning.of("PACKAGE_SUFFIXED", p.packages.get(x.index).refs, capitalize(x.why)
                    + ", so this board's copy is saved as " + x.id + ". Other boards are not affected."
                    + (carried == null ? "" : " It uses the pad types of " + carried + ".")));
        }
        for (IdAllocator.PartId x : ids.parts) {
            if (IdAllocator.SUFFIXED.equals(x.state)) {
                l.add(ImportWarning.of("PART_SUFFIXED", x.refs, "OpenPnP already has a part "
                        + x.id.substring(0, x.id.lastIndexOf('~')) + " of another package, so this board's part is "
                        + "saved as " + x.id + "."));
            }
        }
        return l;
    }

    /** The proposal's warnings plus more, capped and in group order (the proposal itself is left as it is). */
    static List<ImportWarning> allWarnings(ImportProposal p, List<ImportWarning> more) {
        List<ImportWarning> l = new ArrayList<>();
        int dropped = p.warningsDropped;
        for (ImportWarning w : p.warnings) {
            if (!"WARNINGS_TRUNCATED".equals(w.code)) {
                l.add(w);
            }
        }
        for (ImportWarning w : more) {
            if (!ImportWarning.addCapped(l, w, ImportProposal.MAX_WARNINGS)) {
                dropped++;
            }
        }
        ImportWarning.sort(l);
        if (dropped > 0) {
            l.add(ImportWarning.of("WARNINGS_TRUNCATED", null,
                    String.format(Locale.ROOT, "%,d more notes not shown.", dropped)));
        }
        return l;
    }

    /**
     * The sentence for how a pad type was chosen (the preview and the pad inspector): pattern, package-map, centre-pad
     * or none.
     */
    public static String ruleSentence(String rule, String matched, String packageId) {
        if (PadTypeResolver.Resolution.PATTERN.equals(rule)) {
            return "Chosen because the package name contains “" + (matched == null ? "" : matched) + "”.";
        }
        if (PadTypeResolver.Resolution.PACKAGE_MAP.equals(rule)) {
            return "Mapped to this package by hand in Profile › Pad types.";
        }
        if (PadTypeResolver.Resolution.PAD_MAP.equals(rule)) {
            return "Mapped to pads " + (matched == null ? "" : matched.replace(",", ", ")) + " of this package in "
                    + "Profile.";
        }
        if (PadTypeResolver.Resolution.CENTRE_PAD.equals(rule)) {
            return "Centre pad of a QFN/DFN: it sits at the part centre and is at least 1.0 mm².";
        }
        return "No pad type matches the package " + packageId + ": it gets no paste until one is added.";
    }

    // ==== pad types and the carried mappings =========================================================================

    /** The planner's pad types for the final ids, with the base packages' mappings carried to new ~n ids (§13 item 3). */
    static final class PadTypes {
        final PadTypeResolver resolver;
        final PadTypeStatus minStatus;
        /** New package id → the package whose mappings it takes. */
        final Map<String, String> carriedFrom = new LinkedHashMap<>();
        /** New package id → its mapping keys and types (whole package: no '#'; some pads: id#names). */
        final Map<String, Map<String, String>> carried = new LinkedHashMap<>();

        PadTypes(PadTypeResolver resolver, PadTypeStatus minStatus) {
            this.resolver = resolver;
            this.minStatus = minStatus;
        }
    }

    PadTypes padTypes(ImportProposal p, IdAllocator.Result ids, IdAllocator.Snapshot snap) throws SQLException {
        List<PadType> types = svc == null || svc.getDb() == null ? Collections.emptyList() : svc.getPadTypes();
        Map<String, String> mappings = svc == null || svc.getDb() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(svc.getDb().packageMappings());
        PadTypeStatus min = svc == null ? PadTypeStatus.CHARACTERIZED : svc.getConfig().minPadTypeStatus();
        Map<String, Map<String, String>> carried = new LinkedHashMap<>();
        Map<String, String> from = new LinkedHashMap<>();
        for (IdAllocator.PackageId x : ids.packages) {
            if (!IdAllocator.SUFFIXED.equals(x.state) || !keysOf(mappings, x.id).isEmpty()) {
                continue;
            }
            String base = p.packages.get(x.index).baseId;
            String baseId = snap.packageIds.getOrDefault(IdAllocator.key(base), base);
            Map<String, String> theirs = keysOf(mappings, baseId);
            if (theirs.isEmpty()) {
                continue;
            }
            Map<String, String> mine = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : theirs.entrySet()) {
                mine.put(x.id + e.getKey().substring(baseId.length()), e.getValue());
            }
            carried.put(x.id, mine);
            from.put(x.id, baseId);
            mappings.putAll(mine);
        }
        PadTypes pt = new PadTypes(new PadTypeResolver(types, mappings), min);
        pt.carried.putAll(carried);
        pt.carriedFrom.putAll(from);
        return pt;
    }

    /** A package's mapping keys: its id (the whole package) and id#names (some pads). */
    private static Map<String, String> keysOf(Map<String, String> mappings, String packageId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            if (e.getKey().equals(packageId) || e.getKey().startsWith(packageId + "#")) {
                m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    // ==== attach candidates ==========================================================================================

    /** Whether a board file's placements match the file's (§3.10), and by how much they are off. */
    static final class Match {
        boolean ok;
        String why;
        double maxOffsetMm;
        final List<BoardArt.Offset> offsets = new ArrayList<>();
    }

    static Match match(ImportProposal p, Listed board) {
        Match m = new Match();
        Map<String, double[]> left = new LinkedHashMap<>(board.placements);
        double max = 0;
        for (PlacementP x : p.placements) {
            double[] b = left.remove(x.id);
            if (b == null) {
                m.why = x.id + " is not in the board file";
                return m;
            }
            if ((b[3] == 1) != "Fiducial".equals(x.type)) {
                m.why = x.id + " is a fiducial in one file and a part in the other";
                return m;
            }
            double d = Math.hypot(b[0] - x.x, b[1] - x.y);
            double dr = Math.abs(((b[2] - x.rotation) % 360 + 540) % 360 - 180);
            if (d > ATTACH_MM) {
                m.why = String.format(Locale.ROOT, "%s moved %.1f mm", x.id, d);
                return m;
            }
            if (dr > ATTACH_DEG) {
                m.why = String.format(Locale.ROOT, "%s is turned %.1f°", x.id, dr);
                return m;
            }
            if (d > OFFSET_MM) {
                m.offsets.add(new BoardArt.Offset(x.id, round4(d)));
            }
            max = Math.max(max, d);
        }
        if (!left.isEmpty()) {
            m.why = left.keySet().iterator().next() + " is not in the KiCad file";
            return m;
        }
        m.ok = true;
        m.maxOffsetMm = round4(max);
        return m;
    }

    // ==== the save (§3.6) ============================================================================================

    /** One save: the art staged on the API thread, then one transaction on OpenPnP's thread, undone on any failure. */
    private final class Save {
        final Parsed parsed;
        final Preview pv;
        final ImportProposal p;
        final String name;
        final String slug;
        final boolean replace;
        final long atMs = System.currentTimeMillis();
        File staging;
        String artId;

        // what the transaction did, for the undo
        final List<Package> addedPackages = new ArrayList<>();
        final List<Part> addedParts = new ArrayList<>();
        final List<String[]> addedMappings = new ArrayList<>(); // package id, joined pad names or null
        final List<File[]> moved = new ArrayList<>();          // from, to (in the order done)
        Board readBack;
        Board listed;
        Board prev;
        boolean prevListed;
        boolean libraryWrite;

        Save(Parsed parsed, Preview pv, String name, boolean replace) {
            this.parsed = parsed;
            this.pv = pv;
            this.p = parsed.proposal;
            this.name = name;
            this.slug = BoardStore.slug(name);
            this.replace = replace;
        }

        Map<String, Object> run() throws Exception {
            staging = store.staging();
            try {
                byte[] art = artBytes();
                artId = BoardArt.idOf(art);
                Files.write(new File(staging, slug + ".kicad_pcb").toPath(), parsed.bytes);
                Files.write(new File(staging, slug + ".art.json").toPath(), art);
                AtomicBoolean started = new AtomicBoolean();
                Map<String, Object> out;
                try {
                    out = EdtRunner.call(() -> {
                        started.set(true);
                        return transaction();
                    }, EDT_START_MS);
                }
                catch (IllegalStateException e) {
                    if (started.get()) {
                        throw e;
                    }
                    throw new ImportException(409, "refused", "OpenPnP's window did not respond within 10 s: "
                            + "nothing was changed. Try again.");
                }
                return out;
            }
            catch (Stale s) {
                parsed.art.art.imported = null; // the preview's drawing has no import record
                throw stale(new Preview(parsed, EdtRunner.call(BoardImporter.this::snapshot, EDT_START_MS)));
            }
            finally {
                BoardStore.deleteTree(staging);
                BoardArtStore.forDir(configuration.getConfigurationDirectory()).invalidate();
            }
        }

        /** The drawing with its source and import record: the bytes whose SHA-256 prefix is the art id. */
        byte[] artBytes() {
            BoardArt a = parsed.art.art;
            BoardArt.Import i = BoardArt.Import.from(p, null);
            i.warnings.clear();
            i.warnings.addAll(pv.warnings);
            i.how = "import";
            i.name = name;
            i.slug = slug;
            i.atMs = atMs;
            i.boardDigest = BoardArtIndex.digest(p);
            for (IdAllocator.PackageId x : pv.ids.packages) {
                i.packages.add(new BoardArt.IdState(x.id, x.state));
            }
            for (IdAllocator.PartId x : pv.ids.parts) {
                i.parts.add(new BoardArt.IdState(x.id, x.state));
            }
            a.imported = i;
            a.measure();
            return a.toBytes();
        }

        /** §3.6 B, on OpenPnP's thread. Any failure undoes every step done. */
        Map<String, Object> transaction() throws Exception {
            try {
                // 1. allocate on the live lists: the same decisions as the preview, or the preview is stale
                IdAllocator.Result ids;
                try {
                    ids = IdAllocator.allocate(p, IdAllocator.Snapshot.of(configuration));
                }
                catch (IdAllocator.Exhausted e) {
                    throw new Stale();
                }
                if (!ids.decisions().equals(pv.ids.decisions())) {
                    throw new Stale();
                }
                step(1);
                // 2. the name
                BoardStore.Existing existing = store.existing(slug);
                if (existing != null && !replace) {
                    throw exists(existing);
                }
                File finalBoard = store.boardFile(slug).getCanonicalFile();
                for (Board b : configuration.getBoards()) {
                    if (b.getFile() != null && b.getFile().getCanonicalFile().equals(finalBoard)) {
                        prev = b;
                    }
                }
                step(2);
                // 3. packages, then parts, then the carried pad-type mappings
                addPackagesAndParts(ids);
                step(3);
                // 4. the board, written to the staging directory
                File staged = new File(staging, slug + ".board.xml");
                writeBoard(ids, staged);
                step(4);
                // 5. read back as OpenPnP will load it
                readBack = readBack(ids, staged);
                step(5);
                // 6. move into place (the board file last)
                moveIntoPlace(staged, existing);
                step(6);
                // 7. into OpenPnP's board list
                readBack.setFile(finalBoard);
                readBack.setDirty(false);
                listed = readBack;
                prevListed = prev != null;
                configuration.addBoard(readBack);
                step(7);
                // 8. packages.xml, parts.xml and boards.xml
                libraryWrite = true;
                configuration.saveLibraryFiles();
                step(8);
                // 9. the index: this board's geometry → its drawing (and where a replaced drawing went)
                Map<String, String> arts = new LinkedHashMap<>();
                arts.put(artId, BoardStore.artPath(slug));
                if (existing != null && existing.artId != null) {
                    arts.put(existing.artId, BoardStore.historyArtPath(slug, existing.artId));
                }
                BoardArtIndex.forDir(configuration.getConfigurationDirectory()).put(BoardArtIndex.digest(readBack),
                        new BoardArtIndex.Entry(artId, slug, "import", finalBoard.getPath(), atMs), arts);
                if (prev != null && prev != readBack && !jobHolds(prev)) {
                    prev.dispose();
                }
                Logger.info("Instance Labs: board {} imported from {} as {} ({} placements, art {})", name,
                        parsed.fileName, finalBoard, p.placements.size(), artId);
                return result(ids, finalBoard, existing != null);
            }
            catch (Stale | ImportException e) {
                undo();
                throw e;
            }
            catch (Throwable t) {
                Logger.error(t, "Instance Labs: saving board {} failed, undoing it", name);
                undo();
                throw new ImportException(500, "internal", "Could not save the board: " + message(t)
                        + ". Nothing was changed.");
            }
        }

        void addPackagesAndParts(IdAllocator.Result ids) throws Exception {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(atMs));
            for (IdAllocator.PackageId x : ids.packages) {
                if (!x.creates()) {
                    continue;
                }
                if (configuration.getPackage(x.id) != null) {
                    throw new IllegalStateException("package " + x.id + " appeared while saving");
                }
                PackageP pk = p.packages.get(x.index);
                Package pkg = new Package(x.id);
                String lib = pk.lib == null || pk.lib.isEmpty() ? "" : pk.lib + ":";
                pkg.setDescription(KText.clean("KiCad " + lib + pk.kicadName + " · imported " + date + " from "
                        + (parsed.fileName.isEmpty() ? "an upload" : parsed.fileName), 200));
                Footprint fp = pkg.getFootprint();
                fp.setUnits(LengthUnit.Millimeters);
                for (PadP pad : pk.pads) {
                    Footprint.Pad fpad = new Footprint.Pad();
                    fpad.setName(pad.name);
                    fpad.setX(pad.x);
                    fpad.setY(pad.y);
                    fpad.setWidth(pad.w);
                    fpad.setHeight(pad.h);
                    fpad.setRotation(pad.rotation);
                    fpad.setRoundness(pad.roundness);
                    fp.addPad(fpad);
                }
                fp.setBodyWidth(pk.bodyW);
                fp.setBodyHeight(pk.bodyH);
                configuration.addPackage(pkg);
                addedPackages.add(pkg);
            }
            for (IdAllocator.PartId x : ids.parts) {
                if (!x.creates()) {
                    continue;
                }
                if (configuration.getPart(x.id) != null) {
                    throw new IllegalStateException("part " + x.id + " appeared while saving");
                }
                Part part = new Part(x.id);
                part.setPackage(configuration.getPackage(x.packageId));
                configuration.addPart(part);
                addedParts.add(part);
            }
            if (svc != null && svc.getDb() != null) {
                for (Map.Entry<String, Map<String, String>> c : pv.padTypes.carried.entrySet()) {
                    for (Map.Entry<String, String> e : c.getValue().entrySet()) {
                        int hash = e.getKey().indexOf('#');
                        String names = hash < 0 ? null : e.getKey().substring(hash + 1);
                        svc.getDb().mapPackage(c.getKey(), names == null ? null : Arrays.asList(names.split(",")),
                                e.getValue());
                        addedMappings.add(new String[] {c.getKey(), names});
                    }
                }
            }
        }

        void writeBoard(IdAllocator.Result ids, File staged) throws Exception {
            Board b = new Board(staged);
            try {
                b.setName(name);
                b.setDimensions(new Location(LengthUnit.Millimeters, p.widthMm, p.heightMm, 0, 0));
                for (int i = 0; i < p.placements.size(); i++) {
                    PlacementP x = p.placements.get(i);
                    Placement pl = new Placement(x.id);
                    pl.setType("Fiducial".equals(x.type) ? Placement.Type.Fiducial : Placement.Type.Placement);
                    pl.setSide(Side.Top);
                    pl.setLocation(new Location(LengthUnit.Millimeters, x.x, x.y, 0, x.rotation));
                    pl.setPart(configuration.getPart(ids.partOf(i).id));
                    pl.setEnabled(x.enabled);
                    b.addPlacement(pl);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Configuration.createSerializer().write(b, out);
                out.write('\n');
                Files.write(staged.toPath(), out.toByteArray());
            }
            finally {
                b.dispose();
            }
        }

        Board readBack(IdAllocator.Result ids, File staged) throws Exception {
            Board rb = Configuration.createSerializer().read(Board.class, staged);
            List<Placement> l = rb.getPlacements();
            if (l.size() != p.placements.size()) {
                rb.dispose();
                throw new IllegalStateException("the board file read back with " + l.size() + " placements instead of "
                        + p.placements.size());
            }
            for (int i = 0; i < l.size(); i++) {
                Placement pl = l.get(i);
                PlacementP x = p.placements.get(i);
                String partId = ids.partOf(i).id;
                if (!x.id.equals(pl.getId()) || !x.type.equals(String.valueOf(pl.getType())) || pl.getPart() == null
                        || !partId.equals(pl.getPart().getId())) {
                    rb.dispose();
                    throw new IllegalStateException("the board file did not read back as written (placement " + x.id
                            + ", part " + partId + ")");
                }
            }
            return rb;
        }

        /** On a replace the old drawing and upload go to history/ and the old board file aside; then the new ones in. */
        void moveIntoPlace(File staged, BoardStore.Existing existing) throws IOException {
            File dir = store.dir(slug);
            Files.createDirectories(dir.toPath());
            File art = store.artFile(slug);
            File kicad = store.kicadFile(slug);
            File board = store.boardFile(slug);
            if (existing != null) {
                String old = existing.artId != null ? existing.artId : "displaced-" + atMs;
                File history = store.historyDir(slug);
                if (art.isFile() || kicad.isFile()) {
                    Files.createDirectories(history.toPath());
                }
                if (art.isFile()) {
                    move(art, new File(history, old + ".art.json"));
                }
                if (kicad.isFile()) {
                    move(kicad, new File(history, old + ".kicad_pcb"));
                }
                if (board.isFile()) {
                    move(board, new File(staging, "displaced.board.xml"));
                }
            }
            move(new File(staging, slug + ".kicad_pcb"), kicad);
            move(new File(staging, slug + ".art.json"), art);
            move(staged, board);
        }

        void move(File from, File to) throws IOException {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            moved.add(new File[] {from, to});
        }

        /** Every step done, in reverse. Each part of the undo is tried even when an earlier one fails. */
        void undo() {
            try {
                if (listed != null && prevListed) {
                    configuration.addBoard(prev);
                    listed.dispose();
                }
                else if (listed != null) {
                    configuration.removeBoard(listed); // not dirty: no question asked; disposes it
                }
                else if (readBack != null) {
                    readBack.dispose();
                }
            }
            catch (Throwable t) {
                Logger.error(t, "undo: board list");
            }
            for (int i = moved.size() - 1; i >= 0; i--) {
                File[] m = moved.get(i);
                try {
                    Files.move(m[1].toPath(), m[0].toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }
                catch (Throwable t) {
                    Logger.error(t, "undo: move {} back to {}", m[1], m[0]);
                }
            }
            moved.clear();
            for (String[] m : addedMappings) {
                try {
                    svc.getDb().mapPackage(m[0], m[1] == null ? null : Arrays.asList(m[1].split(",")), null);
                }
                catch (Throwable t) {
                    Logger.error(t, "undo: pad type mapping of {}", m[0]);
                }
            }
            for (Part part : addedParts) {
                try {
                    configuration.removePart(part);
                }
                catch (Throwable t) {
                    Logger.error(t, "undo: part {}", part.getId());
                }
            }
            for (Package pkg : addedPackages) {
                try {
                    configuration.removePackage(pkg);
                }
                catch (Throwable t) {
                    Logger.error(t, "undo: package {}", pkg.getId());
                }
            }
            if (libraryWrite) {
                try {
                    configuration.saveLibraryFiles(); // the files on disk as OpenPnP's lists are again
                }
                catch (Throwable t) {
                    Logger.error(t, "undo: packages.xml, parts.xml and boards.xml could not be written again: they may "
                            + "name the ids of the failed save until OpenPnP saves");
                }
            }
        }

        void step(int n) {
            if (failAfterStep == n) {
                throw new IllegalStateException("test: the save fails after step " + n);
            }
        }

        Map<String, Object> result(IdAllocator.Result ids, File finalBoard, boolean replaced) {
            List<Map<String, Object>> packages = new ArrayList<>();
            for (IdAllocator.PackageId x : ids.packages) {
                packages.add(map("id", x.id, "state", x.state, "usesPadTypesOf", pv.padTypes.carriedFrom.get(x.id)));
            }
            List<Map<String, Object>> parts = new ArrayList<>();
            for (IdAllocator.PartId x : ids.parts) {
                parts.add(map("id", x.id, "state", x.state));
            }
            return map("file", finalBoard.getPath(), "name", name, "slug", slug, "replaced", replaced, "packages",
                    packages, "parts", parts, "art", map("id", artId, "kind", BoardArt.KIND_KICAD), "counts", p.counts,
                    "warnings", pv.warnings);
        }
    }

    /** The live lists no longer give the preview's ids. */
    private static final class Stale extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private ImportException exists(BoardStore.Existing e) {
        String when = e.importedAtMs == null ? "" : " (" + (e.how == null || "import".equals(e.how) ? "imported "
                : "drawing attached ") + time(e.importedAtMs) + ")";
        return new ImportException(409, "exists", "A board named \"" + (e.name == null ? e.slug : e.name) + "\" exists"
                + when + ": replace it?").with("exists", existsView(e));
    }

    static Map<String, Object> existsView(BoardStore.Existing e) {
        return e == null ? null : map("slug", e.slug, "file", e.file, "name", e.name, "importedAtMs", e.importedAtMs,
                "how", e.how);
    }

    // ==== attach (§3.10) =============================================================================================

    private final class Attach {
        final Parsed parsed;
        final File boardFile;
        final String name;
        final String slug;
        final boolean replace;
        final long atMs = System.currentTimeMillis();

        Attach(Parsed parsed, File boardFile, String name, boolean replace) {
            this.parsed = parsed;
            this.boardFile = boardFile;
            this.name = name;
            this.slug = BoardStore.slug(name);
            this.replace = replace;
        }

        Map<String, Object> run() throws Exception {
            ImportProposal p = parsed.proposal;
            Object[] target = EdtRunner.call(() -> {
                String want = boardFile.getCanonicalPath();
                for (Board b : configuration.getBoards()) {
                    if (b.getFile() != null && b.getFile().getCanonicalPath().equals(want)) {
                        return new Object[] {listed(b), BoardArtIndex.digest(b)};
                    }
                }
                return null;
            }, EDT_START_MS);
            if (target == null) {
                throw new ImportException(409, "refused", boardFile.getPath() + " is not in OpenPnP's board list.");
            }
            Listed board = (Listed) target[0];
            String digest = (String) target[1];
            Match m = match(p, board);
            if (!m.ok) {
                throw new ImportException(409, "refused", "The board file no longer matches this KiCad file ("
                        + m.why + "): upload it as a new board instead.");
            }
            BoardStore.Existing existing = store.existing(slug);
            if (existing != null && !replace) {
                throw exists(existing);
            }
            BoardArt a = parsed.art.art;
            BoardArt.Import i = BoardArt.Import.from(p, null);
            i.warnings.clear();
            i.warnings.addAll(allWarnings(p, parsed.art.warnings));
            i.how = "attach";
            i.name = name;
            i.slug = slug;
            i.atMs = atMs;
            i.boardDigest = digest;
            i.board = board.file;
            i.offsets.addAll(m.offsets);
            a.imported = i;
            a.measure();
            byte[] art = a.toBytes();
            String artId = BoardArt.idOf(art);

            File staging = store.staging();
            List<File[]> moved = new ArrayList<>();
            try {
                Files.write(new File(staging, slug + ".kicad_pcb").toPath(), parsed.bytes);
                Files.write(new File(staging, slug + ".art.json").toPath(), art);
                Files.createDirectories(store.dir(slug).toPath());
                File artFile = store.artFile(slug);
                File kicadFile = store.kicadFile(slug);
                Map<String, String> arts = new LinkedHashMap<>();
                arts.put(artId, BoardStore.artPath(slug));
                if (existing != null && existing.artId != null) {
                    File history = store.historyDir(slug);
                    Files.createDirectories(history.toPath());
                    if (artFile.isFile()) {
                        moveRecorded(artFile, new File(history, existing.artId + ".art.json"), moved);
                    }
                    if (kicadFile.isFile()) {
                        moveRecorded(kicadFile, new File(history, existing.artId + ".kicad_pcb"), moved);
                    }
                    arts.put(existing.artId, BoardStore.historyArtPath(slug, existing.artId));
                }
                moveRecorded(new File(staging, slug + ".kicad_pcb"), kicadFile, moved);
                moveRecorded(new File(staging, slug + ".art.json"), artFile, moved);
                BoardArtIndex.forDir(configuration.getConfigurationDirectory()).put(digest,
                        new BoardArtIndex.Entry(artId, slug, "attach", board.file, atMs), arts);
            }
            catch (Throwable t) {
                Logger.error(t, "Instance Labs: attaching the drawing to {} failed, undoing it", board.file);
                for (int k = moved.size() - 1; k >= 0; k--) {
                    try {
                        Files.move(moved.get(k)[1].toPath(), moved.get(k)[0].toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    catch (Throwable u) {
                        Logger.error(u, "undo: move {} back", moved.get(k)[1]);
                    }
                }
                throw new ImportException(500, "internal", "Could not attach the drawing: " + message(t)
                        + ". Nothing was changed.");
            }
            finally {
                BoardStore.deleteTree(staging);
                BoardArtStore.forDir(configuration.getConfigurationDirectory()).invalidate();
            }
            Logger.info("Instance Labs: drawing {} from {} attached to {}", artId, parsed.fileName, board.file);
            return map("slug", slug, "name", name, "board", board.file, "art", map("id", artId, "kind",
                    BoardArt.KIND_KICAD), "maxOffsetMm", m.maxOffsetMm, "offsets", m.offsets, "replaced",
                    existing != null);
        }
    }

    private static void moveRecorded(File from, File to, List<File[]> moved) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        moved.add(new File[] {from, to});
    }

    // ==== helpers ====================================================================================================

    /** The job open in OpenPnP holds this board (its old copy stays until the job is opened again). */
    private static boolean jobHolds(Board b) {
        try {
            MainFrame f = MainFrame.get();
            Job j = f == null || f.getJobTab() == null ? null : f.getJobTab().getJob();
            if (j == null) {
                return false;
            }
            for (BoardLocation bl : j.getBoardLocations()) {
                Board x = bl.getBoard();
                if (x == b || (x != null && x.getDefinition() == b)) {
                    return true;
                }
            }
        }
        catch (RuntimeException e) {
            return true; // when in doubt, keep it
        }
        return false;
    }

    static BoardArt.FidArt fidArt(BoardArt a, String name) {
        for (BoardArt.FidArt f : a.fiducials) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** The file name without .kicad_pcb, as a board name (letters or digits), else "board". */
    static String suggestedName(String fileName) {
        String n = BoardStore.cleanName(fileName.replaceFirst("(?i)\\.kicad_pcb$", ""));
        return n == null ? "board" : n;
    }

    /** KiCad's major version: generator_version when the file has it (KiCad 8 on), else the file format date. */
    static int kicadMajor(KicadPcb pcb) {
        if (pcb.generatorVersion != null && pcb.generatorVersion.matches("\\d{1,3}(\\..*)?")) {
            return Integer.parseInt(pcb.generatorVersion.replaceFirst("\\..*", ""));
        }
        int v = pcb.version;
        return v < 20211014 ? 5 : v < 20221018 ? 6 : v < 20240108 ? 7 : v < 20241229 ? 8 : v < 20260206 ? 9 : 10;
    }

    private static String time(long ms) {
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(ms))
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()));
        return new SimpleDateFormat(today ? "HH:mm" : "yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m.replaceFirst("[.\\s]+$", "");
    }

    static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
