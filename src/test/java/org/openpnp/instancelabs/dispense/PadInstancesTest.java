package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.CheckInputs;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.TypeInfo;
import org.openpnp.model.Configuration;

/**
 * The engine's pads as the profile check sees them (profile calibration design §3.1, §12.5): from a
 * plan (flags, blocked, planned dots and dose), from a board before planning (board at the origin,
 * no flags or dots), the check's inputs, and the per-pad dot-gap test of decisions 11 and 14.
 */
public class PadInstancesTest {
    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("pad-instances").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
    }

    @Test
    public void fromAPlan() {
        DispenseConfig c = GcodeBlocksTest.cfg();
        DispensePlan p = PlannerDoseTest.threeDosePlan(c);
        DispensePlan.PlannedPad q = PlannerDoseTest.padOf(p, "Q1.1");
        q.flags.add(PadFlag.MAY_BRIDGE); // as if the planner had held it back
        List<PadInstance> l = PadInstances.fromPlan(p);
        assertEquals(p.pads.size(), l.size());
        Map<String, PadInstance> m = new HashMap<>();
        for (PadInstance i : l) {
            m.put(i.padId, i);
        }
        PadInstance u = m.get("U1.1");
        assertEquals("U1", u.ref);
        assertEquals("TAB_PKG", u.packageId);
        assertEquals("1", u.padName);
        assertEquals(100, u.x, 1e-12);
        assertEquals(2.0, u.wMm, 0);
        assertEquals("tab-2x15", u.typeKey);
        assertEquals(Integer.valueOf(1), u.typeVersion);
        assertEquals("characterized", u.typeStatus);
        assertEquals(Integer.valueOf(1600), u.dotSteps);
        assertEquals(2, u.dots.size());
        assertFalse(u.blocked);
        assertTrue(u.flags.isEmpty());
        PadInstance qi = m.get("Q1.1");
        assertTrue(qi.blocked);
        assertEquals(Collections.singletonList("MAY_BRIDGE"), qi.flags);
        assertNull(qi.dots, "a blocked pad carries no dots");
        assertNull(qi.dotSteps);
        // the check reads a plan's flags for blocking
        CheckInputs in = PadInstances.inputs(l, Arrays.asList(PlannerDoseTest.chip0603(), PlannerDoseTest.TAB,
                PlannerDoseTest.SOT), null, c, null, true, PadInstances.specTip(c.tip));
        ProfileCheck.Result r = ProfileCheck.of(in);
        boolean bridge = false;
        for (ProfileCheck.Group g : r.groups) {
            if (g.padIds.contains("Q1.1")) {
                bridge = ProfileCheck.BRIDGE.equals(g.reason) && g.blocking;
            }
        }
        assertTrue(bridge, "the group of a MAY_BRIDGE pad: reason bridge, blocking");
        assertFalse(r.ok);
    }

    @Test
    public void fromABoardAtTheOrigin() {
        PadTypeResolver r = new PadTypeResolver(PadTypeResolverExplainTest.seeds(), null);
        List<PadInstance> l = PadInstances.fromBoard(BoardPlanningTest.board(), r, false);
        Map<String, PadInstance> m = new HashMap<>();
        for (PadInstance i : l) {
            m.put(i.padId, i);
            assertTrue(i.flags.isEmpty());
            assertNull(i.dots);
        }
        // board mm: R1 at (10, 10), its pads at +-0.825
        assertEquals(10 - 0.825, m.get("R1.1").x, 1e-9);
        assertEquals(10, m.get("R1.1").y, 1e-9);
        assertEquals("chip-0603", m.get("R1.1").typeKey);
        assertEquals("provisional", m.get("R1.1").typeStatus);
        assertEquals("pattern", m.get("R1.1").typeRule);
        assertTrue(m.get("U1.17").centrePad);
        assertFalse(m.get("U1.1").centrePad);
        assertNull(m.get("X1").typeKey);
        assertEquals("none", m.get("X1").typeRule);
        assertFalse(m.get("X1").hasGeometry());
        assertFalse(m.containsKey("R3.1"), "disabled placement left out");
        assertTrue(PadInstances.fromBoard(BoardPlanningTest.board(), r, true).stream().anyMatch(i -> i.padId
                .equals("R3.1")));
    }

    @Test
    public void inputsAroundThePads() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.profile.dwellMs = 300;
        PadType chip = PlannerDoseTest.chip0603();
        chip.tipKey = "ad22tta-b";
        PadType gone = PlannerDoseTest.type("gone", "Gone", 1, 1, null, null);
        gone.family = PadInstances.RETIRED_FAMILY;
        Map<String, PadRule> rules = new HashMap<>();
        rules.put("chip-0603", new PadRule("chip-0603", 3, null, 1.2));
        CheckInputs in = PadInstances.inputs(new ArrayList<>(), Arrays.asList(chip, gone), rules, c,
                Collections.singleton("MIXED"), false, PadInstances.specTip(c.tip));
        assertEquals("provisional", in.gate);
        assertEquals("ad22tta-b", in.tip.key);
        assertEquals(0.72, in.tip.odMm, 0);
        assertEquals(400, in.tip.minDotSteps);
        assertEquals(800, in.profileSteps);
        assertEquals(1600, in.stepsPerRev);
        assertEquals(c.profile.doseFingerprint(), in.doseFingerprint);
        assertTrue(in.doseFingerprint.contains("dwell=300"));
        assertEquals(Collections.singleton("MIXED"), in.pastePartialPackages);
        assertFalse(in.planned);
        TypeInfo t = in.types.get("chip-0603");
        assertEquals("characterized", t.status);
        assertEquals(1.2, t.largestSingleDotPadMm2, 0);
        assertEquals(Integer.valueOf(3), t.maxDots);
        assertEquals("ad22tta-b", t.tipKey);
        assertEquals(0.8, t.padWMm, 0);
        assertFalse(t.retired);
        assertTrue(in.types.get("gone").retired);
    }

    /** Each pad on its own: only the pair that is too close; one part's stacked openings are one deposit. */
    @Test
    public void bridgingPadByPad() {
        List<PadInstance> l = new ArrayList<>();
        l.add(withDot(new PadInstance("A", "P", "1", 0, 0, 0, 0.8, 0.95, 0)));
        l.add(withDot(new PadInstance("A", "P", "2", 1.65, 0, 0, 0.8, 0.95, 0)));
        l.add(withDot(new PadInstance("B", "P", "1", 2.6, 0, 0, 0.8, 0.95, 0))); // 0.95 from A.2
        l.add(withDot(new PadInstance("B", "P", "2", 4.25, 0, 0, 0.8, 0.95, 0)));
        // a paste window stacked on C.1 (same part, overlapping): one deposit
        l.add(withDot(new PadInstance("C", "P", "1", 20, 0, 0, 1.5, 2.15, 0)));
        l.add(withDot(new PadInstance("C", "P", "1~1", 20, 0.0125, 0, 1.5, 2.125, 0)));
        double[] r = new double[l.size()];
        Arrays.fill(r, 0.58);
        Map<Integer, Integer> b = PadInstances.bridging(l, r);
        Map<Integer, Integer> want = new HashMap<>();
        want.put(1, 2);
        want.put(2, 1);
        assertEquals(want, b);
        // the same window on another part's pad is a neighbour
        l.get(5).ref = "D";
        assertEquals(4, PadInstances.bridging(l, r).size());
        // smaller dots: 0.95 - 2 x 0.37 = 0.21 mm, no bridge
        Arrays.fill(r, 0.37);
        l.get(5).ref = "C";
        assertTrue(PadInstances.bridging(l, r).isEmpty());
    }

    static PadInstance withDot(PadInstance p) {
        p.dots = new ArrayList<>();
        p.dots.add(new double[] {p.x, p.y});
        return p;
    }
}
