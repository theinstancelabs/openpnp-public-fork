package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-76: the clean-pad ledger (docs/backend-requests.md BR-76, Process's addendum 11-04 08:0x): dots marked by X/Y at
 * dispense time on every pad of the registered board, air turns, off-pad dots, layers, wipes, the budget, the plan
 * need, the file, Process's import; and the line watcher that finds the dots in the lines sent to the controller.
 */
public class PadLedgerTest {
    static final double TOP = 5.5;
    File dir;
    PadLedger led;

    static List<PadLedger.Pad> pads() {
        return Arrays.asList(new PadLedger.Pad("R1.1", 100, 50, 0.9, 0.95, 0),
                new PadLedger.Pad("R1.2", 101.6, 50, 0.9, 0.95, 0), new PadLedger.Pad("D1.1", 100, 60, 0.9, 0.95, 90),
                new PadLedger.Pad("D1.2", 100, 61.6, 0.9, 0.95, 90),
                // an LED pad of the Nano tile's kind: not a chip family, turned 45°
                new PadLedger.Pad("H1.L.A", 110, 55, 0.6, 0.4, 45), new PadLedger.Pad("R5.1", 120, 50, 0.9, 0.95, 0));
    }

    static PadLedger.Board board() {
        Map<String, Object> view = new HashMap<>();
        view.put("widthMm", 40.0);
        view.put("heightMm", 30.0);
        view.put("toMachine", Arrays.asList(1.0, 0.0, 0.0, 1.0, 90.0, 40.0));
        return new PadLedger.Board("ftp", "ftp.board.xml", TOP, pads(), view);
    }

    static double[] pt(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    static List<double[]> at(double x, double y, double z) {
        return Collections.singletonList(pt(x, y, z));
    }

    @BeforeEach
    public void before() throws Exception {
        dir = Files.createTempDirectory("pad-ledger").toFile();
        led = new PadLedger(new File(dir, PadLedger.FILE_NAME));
        led.setBoards(Collections.singletonList(board()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> pad(Map<String, Object> pads, String id) {
        for (Map<String, Object> p : (List<Map<String, Object>>) pads.get("pads")) {
            if (id.equals(p.get("id"))) {
                return p;
            }
        }
        throw new AssertionError("no pad " + id);
    }

    Map<String, Object> entry() {
        List<Map<String, Object>> l = led.status();
        assertEquals(1, l.size(), String.valueOf(l));
        return l.get(0);
    }

    @Test
    public void aDotMarksThePadUnderItByXyWhateverTheRunCallsIt() {
        assertTrue(led.status().isEmpty(), "no ledger before the board's first record");
        led.dot(at(100.3, 50.2, TOP + 0.2), 1000, "Process", "dot", "run-1");
        Map<String, Object> e = entry();
        assertEquals("ftp", e.get("board"));
        assertEquals(6, e.get("total"));
        assertEquals(5, e.get("clean"));
        Map<String, Object> p = pad(led.pads("ftp"), "R1.1");
        assertEquals("used", p.get("state"));
        assertEquals(1000L, p.get("atMs"));
        assertEquals("Process", p.get("by"));
        assertEquals("dot", p.get("kind"));
        assertEquals(1, p.get("layers"));
        assertEquals("R", p.get("set"));
        assertEquals("clean", pad(led.pads("ftp"), "R1.2").get("state"));
    }

    @Test
    public void anLedPadCounts() {
        // inside the turned 0.6 x 0.4 opening (+ 0.15 mm), outside it unturned (y - 55 = 0.45 > 0.2 + 0.15)
        led.dot(at(110.17, 55.45, TOP), 1000, "Process", "dot", null);
        assertEquals("used", pad(led.pads("ftp"), "H1.L.A").get("state"));
    }

    @Test
    public void aTurnTwoMmUpMarksNothing() {
        led.dot(at(101.6, 50, TOP + 2), 1000, "Process", "dot", null);
        assertTrue(led.status().isEmpty(), "an air turn starts no ledger: " + led.status());
        led.dot(at(101.6, 50, TOP + 0.9), 2000, "Process", "dot", null);
        assertEquals("used", pad(led.pads("ftp"), "R1.2").get("state"), "0.9 mm up is a dot");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aDotOffEveryPadIsListedAsOffPad() {
        led.dot(at(105, 45, TOP + 0.1), 1000, "Process", "dot", null);
        Map<String, Object> pads = led.pads("ftp");
        List<Map<String, Object>> off = (List<Map<String, Object>>) pads.get("offPad");
        assertEquals(1, off.size(), String.valueOf(off));
        assertEquals(105.0, ((Number) off.get(0).get("x")).doubleValue(), 1e-9);
        assertEquals(45.0, ((Number) off.get(0).get("y")).doubleValue(), 1e-9);
        assertEquals(1000L, off.get(0).get("atMs"));
        assertEquals("Process", off.get(0).get("by"));
        assertEquals(6, entry().get("clean"), "an off-pad dot spends no pad");
        led.dot(at(300, 300, TOP), 2000, "Process", "dot", null);
        assertEquals(1, ((List<?>) led.pads("ftp").get("offPad")).size(), "a dot off the board is not the board's");
    }

    @Test
    public void aSecondDotOnAPadGivesTwoLayers() {
        led.dot(at(100, 60, TOP), 1000, "Process", "throwaway", null);
        led.dot(at(100.1, 60.1, TOP), 2000, "UI", "dot", "run-2");
        Map<String, Object> p = pad(led.pads("ftp"), "D1.1");
        assertEquals(2, p.get("layers"));
        assertEquals(2000L, p.get("atMs"), "the last dot's time");
        assertEquals("UI", p.get("by"));
        assertEquals(5, entry().get("clean"));
    }

    @Test
    public void aLineOfPasteMarksEveryPadUnderIt() {
        List<double[]> line = new ArrayList<>();
        for (double x = 99.6; x <= 102.0 + 1e-9; x += 0.2) {
            line.add(pt(x, 50, TOP + 0.1));
        }
        led.dot(line, 1000, "Process", "dot", null);
        assertEquals(1, pad(led.pads("ftp"), "R1.1").get("layers"));
        assertEquals(1, pad(led.pads("ftp"), "R1.2").get("layers"), "one layer per pad per line");
        assertTrue(((List<?>) led.pads("ftp").get("offPad")).isEmpty(), "the line touched pads: " + led.pads("ftp"));
    }

    @Test
    public void aWipeClearsEveryPadButTheFixedOnes() {
        led.importProcess("ftp", json("{board:'ftp',wipes:[],fixed:{'R5.1':'old solder'},pasted:{}}"), 500);
        led.dot(at(100, 50, TOP), 1000, "Process", "dot", null);
        led.dot(at(100, 60, TOP), 1000, "Process", "dot", null);
        led.dot(at(120, 50, TOP), 1000, "Process", "dot", null);
        assertEquals(3, entry().get("clean"), "6 pads, 1 fixed, 2 used");
        assertEquals("fixed", pad(led.pads("ftp"), "R5.1").get("state"));
        assertEquals("old solder", pad(led.pads("ftp"), "R5.1").get("note"));
        led.wipe("ftp", Collections.singletonList("D1.1"), "the web UI's user", null, 2000);
        assertEquals("used", pad(led.pads("ftp"), "R1.1").get("state"), "a wipe of some pads leaves the others");
        assertEquals("clean", pad(led.pads("ftp"), "D1.1").get("state"));
        led.dot(at(105, 45, TOP), 2500, "Process", "dot", null);
        Map<String, Object> w = led.wipe("ftp", null, "the web UI's user", "wiped by hand", 3000);
        assertEquals(5, w.get("clean"), String.valueOf(w));
        assertEquals(3000L, w.get("wipedAtMs"));
        assertEquals("fixed", pad(led.pads("ftp"), "R5.1").get("state"), "fixed survives a wipe");
        assertEquals(0, pad(led.pads("ftp"), "R1.1").get("layers"));
        assertTrue(((List<?>) led.pads("ftp").get("offPad")).isEmpty(), "a whole wipe takes the off-pad paste too");
        assertThrows(IllegalArgumentException.class, () -> led.wipe("ftp", Collections.singletonList("Q9.9"), null,
                null, 4000), "a pad the board does not have");
    }

    @Test
    public void theBudgetCountsTheDistinctPadsARunPastes() {
        led.dot(at(100, 50, TOP), 1000, "Process", "dot", null);
        led.dot(at(101.6, 50, TOP), 1000, "Process", "dot", null);
        led.dot(at(100, 60, TOP), 1000, "Process", "dot", null);
        // three dots on two pads (a repeat), one off every pad, one off the board
        List<double[]> run = Arrays.asList(pt(100, 61.6, TOP), pt(100.1, 61.6, TOP), pt(110, 55, TOP), pt(105, 45, TOP),
                pt(300, 300, TOP));
        PadLedger.Budget b = led.budget(run);
        assertNotNull(b);
        assertEquals("ftp", b.board);
        assertEquals(2, b.needed);
        assertEquals(3, b.clean);
        assertFalse(b.isShort());
        led.dot(at(110, 55, TOP), 2000, "Process", "dot", null);
        led.dot(at(120, 50, TOP), 2000, "Process", "dot", null);
        b = led.budget(run);
        assertEquals(1, b.clean);
        assertTrue(b.isShort(), "1 clean, the run needs 2");
        assertNull(led.budget(at(300, 300, TOP)), "a run that pastes no ledger board");
    }

    @Test
    public void thePlanNeedIsShownAndRefusesThePlansFirstRun() {
        led.need("ftp", 5, "Process", "the gap ladder", 1000);
        led.dot(at(100, 50, TOP), 1500, "UI", "dot", null);
        led.dot(at(100, 60, TOP), 1500, "UI", "dot", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> need = (Map<String, Object>) entry().get("need");
        assertEquals(5, need.get("pads"));
        assertEquals("Process", need.get("by"));
        assertEquals("the gap ladder", need.get("what"));
        PadLedger.Budget b = led.budget(at(101.6, 50, TOP));
        assertEquals(1, b.needed);
        assertEquals(4, b.clean);
        assertEquals(Integer.valueOf(5), b.planNeed);
        assertTrue(b.isShort(), "4 clean, the plan needs 5");
        assertEquals(5, b.required());
        led.wipe("ftp", null, "the web UI's user", null, 2000);
        assertNull(entry().get("need"), "a wipe that covers it clears it");
        led.need("ftp", 3, "Process", "the gap ladder", 3000);
        assertFalse(led.budget(at(101.6, 50, TOP)).isShort());
        led.started("ftp");
        assertNull(entry().get("need"), "the plan's first run started with enough clean pads");
        led.need("ftp", 3, "Process", "again", 4000);
        led.need("ftp", 0, "Process", null, 5000);
        assertNull(entry().get("need"), "{pads: 0} clears it");
    }

    @Test
    public void theCountsSurviveARestart() {
        led.importProcess("ftp", json("{board:'ftp',wipes:[],fixed:{'R5.1':'old solder'},pasted:{}}"), 500);
        led.dot(at(100, 50, TOP), 1000, "Process", "dot", "run-1");
        led.dot(at(100, 50, TOP), 1100, "Process", "dot", "run-1");
        led.dot(at(105, 45, TOP), 1200, "Process", "dot", null);
        led.wipe("ftp", Collections.singletonList("D1.1"), "the web UI's user", null, 1300);
        led.need("ftp", 4, "Process", "set 5", 1400);
        PadLedger again = new PadLedger(new File(dir, PadLedger.FILE_NAME));
        again.setBoards(Collections.singletonList(board()));
        assertEquals(led.status(), again.status());
        assertEquals(led.pads("ftp"), again.pads("ftp"));
        assertEquals(2, pad(again.pads("ftp"), "R1.1").get("layers"));
    }

    @Test
    public void importsProcessLedgerFile() {
        led.dot(at(100, 60, TOP), 1700000000000L, "Process", "dot", null); // before the file's wipe: wiped since
        led.importProcess("ftp", json("{board:'ftp',wipes:[{at:'2026-11-03T19:12:00',note:'user wipe'},"
                + "{at:'2026-11-03T22:08:00',note:'D pads wiped'}],fixed:{'R5.1':'old solder (survives a wipe)'},"
                + "pasted:{'R1.2':{at:'2026-11-03T22:17:45',run:'p34/b25/rowD2_board',kind:'throwaway'},"
                + "'G1.J2.7':{at:'2026-11-04T01:30:21',run:'p33/p35_lad0.30',kind:'dot'}},notes:['x']}"), 99);
        Map<String, Object> p = pad(led.pads("ftp"), "R1.2");
        assertEquals("used", p.get("state"));
        assertEquals("throwaway", p.get("kind"));
        assertEquals(java.time.LocalDateTime.parse("2026-11-03T22:17:45").atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(), p.get("atMs"));
        assertEquals(1, p.get("layers"));
        assertEquals("clean", pad(led.pads("ftp"), "D1.1").get("state"), "the file's wipe came after that dot");
        assertEquals("fixed", pad(led.pads("ftp"), "R5.1").get("state"));
        Map<String, Object> e = entry();
        assertEquals(4, e.get("clean"), "6 pads, R5.1 fixed, R1.2 pasted; G1.J2.7 is no pad of this board");
        assertEquals(java.time.LocalDateTime.parse("2026-11-03T22:08:00").atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(), e.get("wipedAtMs"));
        led.wipe("ftp", null, "the web UI's user", null, System.currentTimeMillis());
        assertThrows(IllegalStateException.class, () -> led.importProcess("ftp", json("{board:'ftp',wipes:[],fixed:{},"
                + "pasted:{}}"), 100), "the engine's own wipe is newer than any file of Process's");
        assertThrows(IllegalArgumentException.class, () -> led.importProcess("ftp", json("{board:'ftp',pasted:[]}"),
                100));
    }

    @Test
    public void theBoardKeyIsTheBoardFilesName() {
        assertEquals("ftp", PadLedger.keyOf("file:/home/x/runs/sw_session2/ftp.board.xml"));
        assertNull(PadLedger.keyOf("job:BL1"));
    }

    static JsonObject json(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    // ------------------------------------------------------------------------------------------
    // The line watcher
    // ------------------------------------------------------------------------------------------

    /** Raw X/Y/Z as OpenPnP knows them before any line names them (the head at 10, 20, 30; B at 0). */
    static Double openPnp(char axis) {
        return axis == 'X' ? 10.0 : axis == 'Y' ? 20.0 : axis == 'Z' ? 30.0 : axis == 'B' ? 0.0 : null;
    }

    static DotWatch watch() {
        return new DotWatch('X', 'Y', 'Z', 'B', 1, 0.1);
    }

    static List<List<double[]>> feed(DotWatch w, String... lines) {
        List<List<double[]>> dots = new ArrayList<>();
        for (String l : lines) {
            List<double[]> d = w.line(l, PadLedgerTest::openPnp);
            if (!d.isEmpty()) {
                dots.add(d);
            }
        }
        return dots;
    }

    @Test
    public void aForwardTurnWithTheValveOpenIsADotWhereTheTipIs() {
        List<List<double[]>> d = feed(watch(), "G1 X100 Y50 F6000", "G1 Z3.1", "M106 P2 S255", "M106 P3 S255",
                "G1 B90 F600", "G4 P200", "G1 Z5", "M107 P3");
        assertEquals(1, d.size());
        assertEquals(1, d.get(0).size());
        assertEquals(100, d.get(0).get(0)[0], 1e-9);
        assertEquals(50, d.get(0).get(0)[1], 1e-9);
        assertEquals(3.1, d.get(0).get(0)[2], 1e-9);
    }

    @Test
    public void noDotWithTheValveClosedOrATurnBack() {
        assertTrue(feed(watch(), "G1 X100 Y50 Z3", "G1 B90").isEmpty(), "valve closed");
        assertTrue(feed(watch(), "M106 P3 S255", "M107 P3", "G1 B90").isEmpty(), "valve closed again");
        assertTrue(feed(watch(), "M106 P3 S0", "G1 B90").isEmpty(), "S0 closes it");
        assertTrue(feed(watch(), "M106 P1 S180", "G1 B90").isEmpty(), "N1's valve is not the needle's");
        assertTrue(feed(watch(), "M106 P3 S255", "G1 B-5").isEmpty(), "a turn back lays nothing");
        assertTrue(feed(watch(), "M106 P3 S255", "G1 B0.05").isEmpty(), "less than half a step");
        DotWatch w = watch();
        feed(w, "M106 P3 S255");
        w.valveClosed();
        assertTrue(feed(w, "G1 B90").isEmpty(), "a STOP closed it");
        assertEquals(1, feed(new DotWatch('X', 'Y', 'Z', 'B', -1, 0.1), "M106 P3 S255", "G1 B-90").size(),
                "the extrude sign");
    }

    @Test
    public void turnsAtOneSpotAreOneDotAndAMoveStartsTheNext() {
        DotWatch w = watch();
        List<List<double[]>> d = feed(w, "M106 P3 S255", "G1 X100 Y50 Z3", "G1 B45", "G1 B90", "G1 Z4", "G1 Z3",
                "G1 B135", "G1 X102", "G1 B180", "G92 B0", "G1 B45");
        assertEquals(3, d.size(), "a lift and a move each start a new dot; G92 sets B without a turn");
        assertEquals(102, d.get(2).get(0)[0], 1e-9);
    }

    @Test
    public void aTurnWhileMovingLaysALineSampledAlongItsPath() {
        List<List<double[]>> d = feed(watch(), "G1 X100 Y50 Z3", "M106 P3 S255", "G1 X101 B90");
        assertEquals(1, d.size());
        List<double[]> line = d.get(0);
        assertTrue(line.size() >= 6, "every 0.2 mm or closer: " + line.size());
        assertEquals(100, line.get(0)[0], 1e-9);
        assertEquals(101, line.get(line.size() - 1)[0], 1e-9);
    }

    @Test
    public void anAxisTheLinesHaveNotNamedIsOpenPnpsUntilTheyDo() {
        DotWatch w = watch();
        List<List<double[]>> d = feed(w, "M106 P3 S255", "G1 B90");
        assertEquals(10, d.get(0).get(0)[0], 1e-9);
        assertEquals(30, d.get(0).get(0)[2], 1e-9);
        d = feed(w, "G1 X50", "G1 B180");
        assertEquals(50, d.get(0).get(0)[0], 1e-9);
        w.forget(); // OpenPnP moved the head itself: its coordinates again
        d = feed(w, "G1 B270");
        assertEquals(10, d.get(0).get(0)[0], 1e-9);
    }
}
