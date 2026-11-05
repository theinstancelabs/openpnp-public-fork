package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseProfile;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.machine.reference.driver.GcodeDriver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-77: measure the screw start angle through the engine, end to end in SIM (plan §7): the pad calibration SIM (the
 * real GcodeDriver, SimPaste's flow ripple, the SIM top camera drawing the deposits for the real DotInspector). The
 * flow peaks at count angle ψ − offsetSteps·360/1600: ψ 299 and offsetSteps 533 put the peak at 179.1 (halves: a ≈
 * 0.47 at ripple 1.0) and, at ripple 1.4, the dead quarter's start at 314.7.
 */
public class StartAngleMeasureSimTest {
    static final String MEASURE = "/api/screw/start-angle/measure";
    static final String THROWAWAYS = "[[285,262],[287.5,262]]";
    final PadCalibrationSimTest pc = new PadCalibrationSimTest();

    @AfterEach
    public void after() throws Exception {
        pc.after();
    }

    /**
     * SimPaste with this ripple and physical offset, no threads (a thread would join two blobs), a fixed noise; and a
     * throwaway raised over wet layers still touches (SimPaste has no paste height: up to the API's 0.10 + 2 × 0.15).
     */
    static Consumer<JsonObject> paste(double ripple, int offsetSteps) {
        return sc -> {
            JsonObject p = PadCalibrationSimTest.paste(sc);
            p.addProperty("ripple", ripple);
            p.addProperty("offsetSteps", offsetSteps);
            p.addProperty("threadShare", 0);
            p.addProperty("noiseSeed", 77);
            p.addProperty("maxGapMm", 0.40);
        };
    }

    /** The raw Z difference of the needle laid this much higher (mm) on the flat SIM blank. */
    double up(double mm) throws Exception {
        OpenPnpRawMapper m = new OpenPnpRawMapper(pc.sim.machine, pc.sim.n2, "b");
        return m.toRaw(285, 262, 3.01 + mm).z - m.toRaw(285, 262, 3.01).z;
    }

    /** The job's view until it reaches one of these states. */
    JsonObject awaitState(String job, String... states) throws Exception {
        JsonObject j = null;
        for (long until = System.currentTimeMillis() + 60_000; System.currentTimeMillis() < until; Thread.sleep(20)) {
            j = PadCalibrationSimTest.ok(pc.get("/api/pad-calibrations/" + job));
            for (String st : states) {
                if (st.equals(PadCalibrationSimTest.str(j, "state")) || st.equals(PadCalibrationSimTest.str(j, "step"))) {
                    return j;
                }
            }
        }
        throw new AssertionError("job " + job + " never reached " + java.util.Arrays.toString(states) + ": " + j);
    }

    /** n spots in lines of 8, 2.5 mm apart, from (x0, y0) on the SIM blank, as JSON pairs (no brackets). */
    static String spots(double x0, double y0, int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(String.format(Locale.ROOT, "[%.1f,%.1f]", x0 + 2.5 * (i % 8), y0 + 2.5 * (i / 8)));
        }
        return String.join(",", l);
    }

    /**
     * Halves of 8 pairs: row 1 from (x0, 245) after its throwaways at (x0, 262) and (x0 + 2.5, 262), both clean; row 2
     * from (x0, 253) after its own at (x0 + 5, 262), clean, and (x0 + 7.5, 262) on two wet layers.
     */
    static String halves(double x0, String more) {
        return "{\"mode\":\"halves\",\"pairs\":8,\"targets\":[" + spots(x0, 245, 16) + "," + spots(x0, 253, 16) + "],"
                + "\"throwaways\":[[" + x0 + ",262],[" + (x0 + 2.5) + ",262],[" + (x0 + 5) + ",262,0],[" + (x0 + 7.5)
                + ",262,2]]" + more + "}";
    }

    static String quarters(String more) {
        return "{\"mode\":\"quarters\",\"targets\":[" + spots(285, 245, 16) + "],\"throwaways\":" + THROWAWAYS
                + more + "}";
    }

    /** The measure's result, waited for (200: it ended). */
    JsonObject measure(String body) throws Exception {
        PadCalibrationSimTest.Res r = pc.call("POST", MEASURE, "{\"wait\":true," + body.substring(1));
        assertEquals(200, r.status, r.toString());
        return r.json().getAsJsonObject("result");
    }

    JsonObject profile() throws Exception {
        return PadCalibrationSimTest.ok(pc.get("/api/config")).getAsJsonObject("profile");
    }

    String interlock(String id) throws Exception {
        for (JsonElement e : PadCalibrationSimTest.ok(pc.get("/api/status")).getAsJsonArray("interlocks")) {
            if (id.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject().get("state").getAsString();
            }
        }
        throw new AssertionError(id);
    }

    /** The signed difference a − b on the circle (deg, −180..180). */
    static double off(double a, double b) {
        double d = ((a - b) % 360 + 540) % 360 - 180;
        return d;
    }

    static double num(JsonObject o, String k) {
        return o.get(k).getAsDouble();
    }

    @Test
    public void halvesFindTheFlowsPeakAndAnApplyAfterAStopKeepsTheRecipesOffset() throws Exception {
        pc.start(paste(1.0, 533));
        pc.sim.server.mark();
        JsonObject r = measure(halves(285, ""));
        assertEquals("done", PadCalibrationSimTest.str(r, "state"), r.toString());
        // each row after its own 2 throwaways (Process 11-04 13:0x), each at its own layers, nothing raised by the row
        List<Double> z = dotZ(sent());
        assertEquals(36, z.size(), z.toString());
        assertEquals(z.get(0), z.get(18), 0.002, "row 2's clean throwaway at the targets' Z: " + z);
        assertEquals(up(0.30), z.get(19) - z.get(0), 0.002, "two wet layers: 0.30 mm higher: " + z);
        assertEquals(z.get(0), z.get(2), 0.002, "row 1's clean throwaways at the targets' Z");
        assertEquals("halves", PadCalibrationSimTest.str(r, "mode"));
        JsonObject fit = r.getAsJsonObject("fit");
        double peak = num(fit, "peakDeg");
        System.out.println("BR-77 SIM halves: peak " + peak + " a " + num(fit, "a") + " se " + num(fit, "peakSeDeg")
                + " " + r.get("confidence") + " " + r.get("why"));
        assertEquals(0, off(peak, 179.1), 20, r.toString());
        assertEquals(0.47, num(fit, "a"), 0.1, r.toString());
        assertNotEquals("none", PadCalibrationSimTest.str(r, "confidence"), r.toString());
        assertEquals(peak, num(r, "featureDeg"), 1e-9);
        assertEquals(0, off(num(fit, "balancedStartDeg"), peak + 180), 1e-9);
        // each dot's start from its own count: row 1 from 297.9 (and 117.9), row 2 from 27.9 (and 207.9)
        JsonArray dots = r.getAsJsonObject("evidence").getAsJsonArray("dots");
        assertEquals(32, dots.size());
        double[] want = {297.9, 117.9};
        for (int i = 0; i < 32; i++) {
            double s = num(dots.get(i).getAsJsonObject(), "startDeg");
            assertEquals(0, off(s, want[i % 2] + (i < 16 ? 0 : 90)), 0.06, "dot " + i + ": " + s);
        }
        assertEquals(2, r.getAsJsonObject("evidence").getAsJsonArray("runIds").size(), "one run per row");
        assertEquals(298, num(profile(), "startAngleDeg"), 0, "nothing set without apply or evidence");
        // set from the measure's evidence at the balanced start (the 22G recipe: peak + 180)
        String job = PadCalibrationSimTest.str(r, "job");
        double balanced = Math.round(num(fit, "balancedStartDeg") * 10) / 10.0;
        JsonObject set = PadCalibrationSimTest.ok(pc.call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":"
                + balanced + ",\"evidence\":{\"runId\":\"" + job + "\"}}"));
        JsonObject f = set.getAsJsonObject("startAngleFeature");
        assertEquals("halves", f.get("mode").getAsString());
        assertEquals(job, f.get("runId").getAsString());
        assertEquals(peak, f.get("deg").getAsDouble(), 0.05);
        double oldOffset = off(num(set, "startAngleDeg"), f.get("deg").getAsDouble());
        assertEquals("ok", interlock("I11"));
        // STOP: OpenPnP's disable sends the live machine's M18 B (PowerCycleSimTest), the screw is unpowered: I11 asks
        // for a measure
        pc.sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                "M410\nM107 P3\nM107 P2\nM107 P1\nM107 P0\nM18 B");
        PadCalibrationSimTest.ok(pc.call("POST", "/api/stop", "{}"));
        assertNotEquals("ok", interlock("I11"));
        // the screw turned unpowered: its physical phase moved 400 steps (the peak -90°, to 89.1)
        JsonObject sc = PadCalibrationSimTest.scene("display");
        paste(1.0, 933).accept(sc);
        pc.writeScene(sc);
        Thread.sleep(400); // the scene file is read again within 250 ms
        PadCalibrationSimTest.ok(pc.call("POST", "/api/machine/enable", "{}"));
        PadCalibrationSimTest.ok(pc.call("POST", "/api/machine/home", "{\"wait\":true}"));
        long offAt = PadCalibrationSimTest.ok(pc.get("/api/status")).getAsJsonObject("machine")
                .getAsJsonObject("screwMotorOff").get("atMs").getAsLong();
        JsonObject a = measure(halves(310, ",\"apply\":true"));
        assertEquals("done", PadCalibrationSimTest.str(a, "state"), a.toString());
        double peak2 = num(a, "featureDeg");
        System.out.println("BR-77 SIM halves after the STOP: peak " + peak2 + " applied " + a.get("applied"));
        assertEquals(0, off(peak2, 89.1), 20, a.toString());
        JsonObject applied = a.getAsJsonObject("applied");
        assertEquals("peak", applied.get("featureFrom").getAsString());
        assertTrue(PadCalibrationSimTest.str(a, "sentence").matches("Measured \\d\\d:\\d\\d: the flow's peak at .*; the "
                + "start angle set .*"), a.toString());
        JsonObject p = profile();
        JsonObject f2 = p.getAsJsonObject("startAngleFeature");
        assertEquals(PadCalibrationSimTest.str(a, "job"), f2.get("runId").getAsString());
        assertEquals(oldOffset, off(num(p, "startAngleDeg"), f2.get("deg").getAsDouble()), 1e-9, "the recipe's offset");
        assertEquals(num(p, "startAngleDeg"), applied.get("toDeg").getAsDouble(), 0.05);
        assertEquals(balanced, applied.get("fromDeg").getAsDouble(), 0.05);
        assertTrue(p.get("startAngleSetAtMs").getAsLong() > offAt, "set after the motor-off");
        assertEquals("ok", interlock("I11"), "the measured angle clears I11");
    }

    @Test
    public void quartersFindTheDeadQuarter() throws Exception {
        pc.start(paste(1.4, 533));
        int records = pc.svc.getDb().query("SELECT * FROM dose_records").size();
        JsonObject r = measure(quarters(""));
        assertEquals(records, pc.svc.getDb().query("SELECT * FROM dose_records").size(), "a measure writes no dose "
                + "records (Process 11-04)");
        assertEquals("done", PadCalibrationSimTest.str(r, "state"), r.toString());
        JsonObject fit = r.getAsJsonObject("fit");
        double dead = num(fit, "deadQuarterStartDeg");
        System.out.println("BR-77 SIM quarters (offset 533, P 298): dead " + dead + " grid " + fit.get(
                "gridDeadStartDeg") + " " + r.get("confidence") + " " + r.get("why") + " " + fit);
        assertEquals(0, off(dead, 314.7), 22.5, r.toString());
        assertEquals(297.9, num(fit, "gridDeadStartDeg"), 0.06, "of 298/28/118/208 the 298 quarter is the dead one");
        assertEquals(298, num(r, "startDeg"), 0, "the stored angle by default");
        assertNotEquals("none", PadCalibrationSimTest.str(r, "confidence"), r.toString());
        assertEquals(dead, num(r, "featureDeg"), 1e-9, "the interpolated start is the feature");
        assertEquals(3, fit.getAsJsonObject("liveQuarterAreas").entrySet().size());
        assertEquals(4, fit.getAsJsonObject("cvPerAngle").entrySet().size());
    }

    @Test
    public void quartersAtTheWorstPhase() throws Exception {
        pc.start(paste(1.4, 1120));
        JsonObject r = measure(quarters(",\"startDeg\":316"));
        assertEquals("done", PadCalibrationSimTest.str(r, "state"), r.toString());
        JsonObject fit = r.getAsJsonObject("fit");
        double dead = num(fit, "deadQuarterStartDeg");
        System.out.println("BR-77 SIM quarters (offset 1120, P 316): dead " + dead + " grid " + fit.get(
                "gridDeadStartDeg") + " " + r.get("confidence") + " " + r.get("why") + " " + fit);
        assertEquals(0, off(dead, 182.6), 22.5, r.toString());
        assertEquals(0, off(dead, 199.0), 6, "the noise-free model's 199.0");
        assertEquals(316, num(r, "startDeg"), 0);
        assertEquals("low", PadCalibrationSimTest.str(r, "confidence"), "the dead arc straddles two quarters");
        assertEquals(dead, num(r, "featureDeg"), 1e-9);
        assertEquals(dead, num(fit, "regridStartDeg"), 1e-9, "Process 11-04: one more grid from the estimate");
    }

    private static final Pattern Z = Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern B = Pattern.compile("\\bB(-?\\d+(?:\\.\\d+)?)");

    /** The raw Z each dot of the stream was laid at: the Z in force at its first B move. */
    static List<Double> dotZ(List<String> sent) {
        List<Double> l = new ArrayList<>();
        Double z = null;
        boolean inDot = false;
        for (String s : sent) {
            if (!s.startsWith("G0 ") && !s.startsWith("G1 ")) {
                continue;
            }
            Matcher mz = Z.matcher(s), mb = B.matcher(s);
            if (mz.find()) {
                z = Double.parseDouble(mz.group(1));
                inDot = false;
            }
            if (mb.find() && !inDot && z != null) {
                l.add(z);
                inDot = true;
            }
        }
        return l;
    }

    @Test
    public void refusalsBeforeAnythingMovesAndThrowawaysOnPaste() throws Exception {
        pc.start(null);
        String t = "\"targets\":[" + spots(285, 245, 16) + "],";
        assertBad("{\"mode\":\"dose\"," + t + "\"throwaways\":" + THROWAWAYS + "}", "mode 'dose' is not built yet "
                + "(BR-77 PR C)");
        assertBad("{\"mode\":\"spiral\"," + t + "\"throwaways\":" + THROWAWAYS + "}", "'mode' must be");
        // 12 pairs by default (Process 11-04 22:1x)
        assertBad("{\"mode\":\"halves\"," + t + "\"throwaways\":" + THROWAWAYS + "}", "halves of 12 pairs take 48 "
                + "targets (2 rows of 12 pairs), not 16");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":[[285,262]]}", "at least 2 throwaways");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":[[285,262,0],[287.5,262,3]]}", "layers");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":" + THROWAWAYS + ",\"startDeg\":360}", "startDeg");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":" + THROWAWAYS + ",\"turns\":9}", "turns");
        assertBad("{\"mode\":\"halves\",\"pairs\":8,\"targets\":[" + spots(285, 245, 16) + "," + spots(285, 253, 16)
                + "],\"throwaways\":" + THROWAWAYS + "}", "halves take one set of throwaways per row: an even count, 2 to "
                + "8 per row");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":[" + spots(280, 266, 9) + "]}", "at most 8");
        assertBad("{\"mode\":\"quarters\",\"targets\":[[285,245],[286,245]," + spots(285, 250, 14) + "],\"throwaways\":"
                + THROWAWAYS + "}", "two targets lie 1.00 mm apart: at least 1.6 mm");
        assertBad("{\"mode\":\"quarters\"," + t + "\"throwaways\":[[285,247],[287.5,262]]}", "throwaway 1 lies 2.00 mm "
                + "from target 1");
        pc.sim.server.mark();
        assertRefused(quarters(",\"apply\":true"), "the start angle has no measured quarters feature: set it once from "
                + "a measure (POST /api/screw/start-angle {startAngleDeg, evidence: {runId}})");
        String off = "{\"mode\":\"quarters\",\"targets\":[[200,250]," + spots(285, 245, 15) + "],\"throwaways\":"
                + THROWAWAYS + ",\"surface\":\"blank\"}";
        assertRefused(off, "I4: target 1 (200.0, 250.0) is off the 'blank' surface");
        assertRefused(quarters(",\"surface\":\"blank\"").replace(THROWAWAYS, "[[200,262],[287.5,262]]"), "I4: throwaway 1 "
                + "(200.0, 262.0) is off the 'blank' surface");
        PadCalibrationSimTest.Res nowhere = pc.call("POST", MEASURE, "{\"mode\":\"quarters\",\"targets\":[[200,250],"
                + spots(285, 245, 15) + "],\"throwaways\":" + THROWAWAYS + "}");
        assertEquals(409, nowhere.status, nowhere.toString());
        assertTrue(nowhere.body.contains("I4: no probed surface holds every target"), nowhere.toString());
        assertFalse(nowhere.body.contains("'null'"), "no 'null' surface in the preview: " + nowhere);
        pc.sim.machine.setHomed(false);
        assertRefused(quarters(""), "machine is not homed");
        assertNothingMoved();
        PadCalibrationSimTest.ok(pc.call("POST", "/api/machine/home", "{\"wait\":true}"));
        PadCalibrationSimTest.ok(pc.call("PUT", "/api/config", "{\"outputZones\":{\"forbid\":[{\"name\":\"a clamp\","
                + "\"x\":290,\"y\":245,\"radiusMm\":1.0}]}}"));
        pc.sim.server.mark();
        assertRefused(quarters(""), "I12: target 3 puts paste at (290.0, 245.0), over the a clamp");
        PadCalibrationSimTest.ok(pc.call("PUT", "/api/config", "{\"outputZones\":{\"forbid\":[{\"name\":\"a clamp\","
                + "\"x\":287.5,\"y\":262,\"radiusMm\":1.0}]}}"));
        assertRefused(quarters(""), "I12: throwaway 2 puts paste at (287.5, 262.0), over the a clamp");
        assertNothingMoved();
        PadCalibrationSimTest.ok(pc.call("PUT", "/api/config", "{\"outputZones\":{\"forbid\":[]}}"));
        // evidence the engine does not know is no evidence; only the last 5 pad calibrations are kept
        PadCalibrationSimTest.Res unknown = pc.call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":100,"
                + "\"evidence\":{\"runId\":\"pc-1\"}}");
        assertEquals(409, unknown.status, unknown.toString());
        assertTrue(unknown.body.contains("measure first: no measure pc-1 among the last 5 pad calibrations of this "
                + "session"), unknown.toString());
        // an apply needs a stored feature of its own mode
        long t0 = System.currentTimeMillis();
        pc.svc.setStartAngle(298.0, "seeded", t0, new DispenseProfile.StartAngleFeature("halves", 118, t0, "pc-0"));
        assertRefused(quarters(",\"apply\":true"), "the start angle was set from a halves measure, not a quarters one");
        // stored from a dead quarter: with none now, the regulator is the suspect (Process 11-04); such a measure is
        // no evidence. A throwaway on one wet layer is laid 0.15 mm higher than one on a clean spot.
        pc.svc.setStartAngle(298.0, "seeded", t0, new DispenseProfile.StartAngleFeature("quarters", 314.7, t0, "pc-0"));
        pc.sim.server.mark();
        JsonObject r = measure("{\"mode\":\"quarters\",\"turns\":2,\"targets\":[" + spots(285, 245, 8) + "],"
                + "\"throwaways\":[[285,262,0],[290,262,1]]}");
        assertEquals("done", PadCalibrationSimTest.str(r, "state"), r.toString());
        assertEquals("none", PadCalibrationSimTest.str(r, "confidence"), r.toString());
        assertEquals("the dead quarter is gone: check the regulator (every quarter lays more than half the largest)",
                r.getAsJsonArray("why").get(0).getAsString());
        List<Double> z = dotZ(sent());
        assertEquals(2 + 8, z.size(), z.toString());
        assertEquals(up(0.15), z.get(1) - z.get(0), 0.002, z.toString());
        assertEquals(z.get(0), z.get(2), 0.002, "the targets at the clean throwaway's height");
        PadCalibrationSimTest.Res none = pc.call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":100,\"evidence\":"
                + "{\"runId\":\"" + PadCalibrationSimTest.str(r, "job") + "\"}}");
        assertEquals(409, none.status, none.toString());
        assertTrue(none.body.contains("is no finished start-angle measure that found an angle"), none.toString());
        // the top camera looks before and measures after
        pc.svc.setDotImager(new org.openpnp.instancelabs.dispense.DotImager() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public Frame capture(double xMm, double yMm) {
                throw new IllegalStateException("no camera");
            }

            @Override
            public List<org.openpnp.instancelabs.vision.Dot> inspect(Frame before, Frame after) {
                throw new IllegalStateException("no camera");
            }
        });
        assertRefused(quarters(""), "the measure needs the top camera");
    }

    void assertNothingMoved() {
        for (String l : sent()) {
            assertFalse(l.startsWith("G0 ") || l.startsWith("G1 ") || l.startsWith("M106"), "nothing moved: " + l);
        }
    }

    /** The lines the controller got since the mark, without comments. */
    List<String> sent() {
        List<String> l = new ArrayList<>();
        for (String s : pc.sim.server.sinceMark()) {
            String c = s.contains(";") ? s.substring(0, s.indexOf(';')).trim() : s.trim();
            if (!c.isEmpty()) {
                l.add(c);
            }
        }
        return l;
    }

    void assertBad(String body, String why) throws Exception {
        PadCalibrationSimTest.Res r = pc.call("POST", MEASURE, body);
        assertEquals(400, r.status, r.toString());
        assertTrue(r.json().get("reason").getAsString().contains(why), r.toString());
    }

    void assertRefused(String body, String why) throws Exception {
        PadCalibrationSimTest.Res r = pc.call("POST", MEASURE, body);
        assertEquals(409, r.status, r.toString());
        assertTrue(r.json().get("reason").getAsString().contains(why), r.toString());
    }

    /**
     * STOP, then Cancel, while row 2 of an applying measure lays its dots: its run ABORTED, the job stopped or cancelled,
     * nothing set; a stopped measure is no evidence.
     */
    @Test
    public void aStopOrACancelDuringRow2SetsNothing() throws Exception {
        pc.start(paste(1.0, 533));
        long t0 = System.currentTimeMillis();
        pc.svc.setStartAngle(359.1, "seeded", t0, new DispenseProfile.StartAngleFeature("halves", 179.1, t0, "pc-0"));
        JsonObject before = profile();
        String job = stopDuringRow2(285, () -> PadCalibrationSimTest.ok(pc.call("POST", "/api/stop", "{}")));
        JsonObject j = awaitState(job, "stopped");
        assertEquals("ABORTED", PadCalibrationSimTest.ok(pc.get("/api/runs/last")).getAsJsonObject("run").get("status")
                .getAsString());
        assertUnchanged(before, j);
        PadCalibrationSimTest.Res ev = pc.call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":100,\"evidence\":"
                + "{\"runId\":\"" + job + "\"}}");
        assertEquals(409, ev.status, ev.toString());
        assertTrue(ev.body.contains("measure first"), ev.toString());
        // Cancel (the job's own open route) does the same
        PadCalibrationSimTest.ok(pc.call("POST", "/api/machine/enable", "{}"));
        PadCalibrationSimTest.ok(pc.call("POST", "/api/machine/home", "{\"wait\":true}"));
        String job2 = stopDuringRow2(310, () -> PadCalibrationSimTest.ok(pc.call("POST", "/api/pad-calibrations/"
                + jobOf() + "/cancel", "{}")));
        JsonObject j2 = awaitState(job2, "cancelled");
        assertEquals("ABORTED", PadCalibrationSimTest.ok(pc.get("/api/runs/last")).getAsJsonObject("run").get("status")
                .getAsString());
        assertUnchanged(before, j2);
    }

    /** The current job's id. */
    String jobOf() throws Exception {
        return PadCalibrationSimTest.str(PadCalibrationSimTest.ok(pc.get("/api/pad-calibrations/current")), "id");
    }

    /** Starts an applying halves measure at x0 and, while its row 2 waits for an M400, runs {@code stop}. */
    String stopDuringRow2(double x0, java.util.concurrent.Callable<?> stop) throws Exception {
        PadCalibrationSimTest.Res started = pc.call("POST", MEASURE, halves(x0, ",\"apply\":true"));
        assertEquals(202, started.status, started.toString());
        String job = started.json().get("job").getAsString();
        awaitState(job, "row2");
        pc.sim.server.delayBeforeOkMs.put("M400", 3000L);
        pc.sim.server.mark();
        long t0 = System.currentTimeMillis();
        while (!sent().contains("M400") && System.currentTimeMillis() - t0 < 60_000) {
            Thread.sleep(20);
        }
        stop.call();
        pc.sim.server.delayBeforeOkMs.clear();
        return job;
    }

    void assertUnchanged(JsonObject before, JsonObject job) throws Exception {
        JsonObject after = profile();
        for (String k : new String[] {"startAngleDeg", "startAngleSetAtMs", "startAngleFeature"}) {
            assertEquals(before.get(k), after.get(k), k);
        }
        JsonObject sa = job.getAsJsonObject("result").getAsJsonObject("startAngle");
        assertTrue(sa.get("fit").isJsonNull() && sa.get("applied").isJsonNull(), sa.toString());
        assertEquals("done", PadCalibrationSimTest.str(job.getAsJsonArray("steps").get(0).getAsJsonObject(), "state"),
                "row 1 laid: " + job);
    }
}
