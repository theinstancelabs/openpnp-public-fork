package org.openpnp.instancelabs.padprofile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;

/**
 * The KiCad fixtures of src/test/resources/padprofile (R/README.md): every paste opening of the ftp,
 * mobo and nano boards, both sides, in the board frame.
 */
final class PadFixtures {
    /** The resolver's QFN/DFN centre rule (D/PadTypeResolver.java): a pad of 1 mm² or more at the part origin. */
    private static final Pattern CENTRE_PACKAGE = Pattern.compile("QFN|DFN", Pattern.CASE_INSENSITIVE);

    static final class Fixture {
        String board;
        List<Part> parts = new ArrayList<>();
        List<Pad> pads = new ArrayList<>();
    }

    static final class Part {
        String ref;
        String packageId;
        String side;
        double x;
        double y;
        double rotationDeg;
        boolean dnp;
    }

    static final class Pad {
        String padId;
        String ref;
        String packageId;
        String padName;
        String side;
        double x;
        double y;
        double rotationDeg;
        double wMm;
        double hMm;
        double roundness;
        double localX;
        double localY;
    }

    private PadFixtures() {
    }

    static Fixture load(String name) {
        try (InputStream in = PadFixtures.class.getResourceAsStream("/padprofile/" + name + ".pads.json")) {
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Fixture.class);
        }
        catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    static PadInstance instance(Pad p) {
        PadInstance i = new PadInstance(p.ref, p.packageId, p.padName, p.x, p.y, p.rotationDeg, p.wMm, p.hMm,
                p.roundness);
        i.padId = p.padId;
        i.centrePad = CENTRE_PACKAGE.matcher(p.packageId).find() && Math.hypot(p.localX, p.localY) < 0.05
                && i.areaMm2() >= 1.0;
        i.typeRule = "none";
        return i;
    }

    /** Every opening of one side ("top", "bottom"). */
    static List<PadInstance> side(Fixture f, String side) {
        List<PadInstance> l = new ArrayList<>();
        for (Pad p : f.pads) {
            if (side.equals(p.side)) {
                l.add(instance(p));
            }
        }
        return l;
    }

    /** One part of each package (the first in the file), alone: the §1.9 package-level survey. */
    static Map<String, List<PadInstance>> packageLevel(Fixture f) {
        Map<String, String> firstRef = new LinkedHashMap<>();
        for (Part p : f.parts) {
            firstRef.putIfAbsent(p.packageId.replaceFirst("~\\d+$", ""), p.ref);
        }
        Map<String, List<PadInstance>> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : firstRef.entrySet()) {
            List<PadInstance> l = new ArrayList<>();
            for (Pad p : f.pads) {
                if (p.ref.equals(e.getValue())) {
                    l.add(instance(p));
                }
            }
            m.put(e.getKey(), l);
        }
        return m;
    }

    /** Parts per package (both sides, variants counted with their base package). */
    static Map<String, Integer> refsPerPackage(Fixture f) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Part p : f.parts) {
            m.merge(p.packageId.replaceFirst("~\\d+$", ""), 1, Integer::sum);
        }
        return m;
    }

    static CheckInputs inputs(List<PadInstance> pads) {
        CheckInputs in = new CheckInputs();
        in.pads = pads;
        return in;
    }
}
