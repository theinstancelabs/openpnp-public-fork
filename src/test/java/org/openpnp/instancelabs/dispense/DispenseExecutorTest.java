package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Streaming, I1 shutdown, I7 on-time, keepalive pings and DPH. */
public class DispenseExecutorTest {
    /** Fake channel with a fake clock: every line takes `msPerLine`, G4 P<n> takes n ms. */
    static class FakeChannel implements GcodeChannel {
        final List<String> sent = new ArrayList<>();
        long now = 1_000_000;
        long msPerLine = 2;
        int failAt = -1;

        @Override
        public void send(String line, long timeoutMs) throws Exception {
            if (failAt >= 0 && sent.size() == failAt) {
                sent.add(line);
                throw new Exception("simulated driver error at " + line);
            }
            sent.add(line);
            now += msPerLine;
            if (line.startsWith("G4 P")) {
                now += Long.parseLong(line.substring(4).trim());
            }
        }

        @Override
        public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
            send(line, timeoutMs);
            return Arrays.asList("ok");
        }
    }

    /** The toy board program: 8 purge dots (index -3), then 3 work dots, one pressure session. */
    static GcodeProgram program(DispenseConfig c, boolean keepalive) throws Exception {
        return GcodeStreamTest.board(c, GcodeStreamTest.mapper(), GcodeStreamTest.toyPlan(c), false, keepalive, 0L);
    }

    /** The I1 Stop sequence (bench F9), plus the watchdog disarm if it was armed. */
    static final List<String> STOP = Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B");

    static List<String> stop(boolean armed) {
        List<String> l = new ArrayList<>(STOP);
        if (armed) {
            l.add("M990 S0");
        }
        return l;
    }

    static List<String> tail(List<String> l, int n) {
        return l.subList(l.size() - n, l.size());
    }

    static RunResult run(int dots) {
        RunResult r = new RunResult();
        r.runId = "t";
        r.kind = "board";
        r.dotsPlanned = dots;
        r.startedAtMs = 1_000_000;
        return r;
    }

    @Test
    public void streamsEveryNonCommentLineInOrderWithoutPerDotM400() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        List<Integer> progress = new ArrayList<>();
        ex.stream(p, r, new DispenseListener() {
            @Override
            public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
                progress.add(done);
            }
        });
        List<String> expected = new ArrayList<>();
        for (String l : p.text()) {
            if (!l.startsWith(";")) {
                expected.add(l);
            }
        }
        assertEquals(expected, ch.sent);
        assertEquals(Arrays.asList(1, 2, 3), progress, "purge dots are not work dots");
        assertEquals(3, r.dotsDone);
        assertEquals(3, r.dotTimings.size());
        assertEquals(2, java.util.Collections.frequency(ch.sent, "M400"), "only the two session-end M400s");
        // timings: per dot start < end, monotonic
        long prev = 0;
        for (RunResult.DotTiming t : r.dotTimings) {
            assertTrue(t.startMs < t.endMs);
            assertTrue(t.startMs >= prev);
            prev = t.endMs;
        }
        assertEquals(r.dotTimings.get(0).startMs, r.firstDotStartMs);
        // last dot end = completion of the M400 after it (true end), not its last ack
        assertTrue(r.lastDotEndMs > r.dotTimings.get(2).endMs);
        double expectedDph = 3 / ((r.lastDotEndMs - r.firstDotStartMs) / 1000.0) * 3600;
        assertEquals(expectedDph, r.dphMotion(), 1e-9);
        r.endedAtMs = ch.now;
        assertTrue(r.dphOverall() < r.dphMotion());
    }

    @Test
    public void driverErrorRunsShutdownInPlanOrder() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, true);
        FakeChannel ch = new FakeChannel();
        ch.failAt = 20; // somewhere inside the session, pneumatics on
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        assertThrows(Exception.class, () -> ex.stream(p, r, null));
        assertEquals(stop(true), tail(ch.sent, 7));
    }

    @Test
    public void abortRequestStopsBeforeNextLine() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        Exception e = assertThrows(DispenseExecutor.AbortedException.class, () -> ex.stream(p, r,
                new DispenseListener() {
                    @Override
                    public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
                        ex.requestAbort("operator stop");
                    }
                }));
        assertEquals("operator stop", e.getMessage());
        assertEquals(1, r.dotsDone);
        assertEquals(stop(false), tail(ch.sent, 6));
        assertFalse(ch.sent.subList(0, ch.sent.size() - 6).contains("M410"));
    }

    /**
     * BR-82 review: a STOP while the pause after a dot's lift runs (the 25G recipe's 500 ms) leaves that dot counted:
     * it is laid and lifted, so its pad is recorded and "Finish the pads left" does not lay it again.
     */
    @Test
    public void stopDuringTheLiftPauseLeavesTheDotCounted() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.profile.liftPauseMs = 500;
        GcodeProgram p = program(c, false);
        // the first work dot's first pause piece: the G4 right after its lift
        List<String> t = new ArrayList<>();
        int pause = -1;
        boolean work = false;
        for (GcodeProgram.Line l : p.lines) {
            if (l.isComment()) {
                continue;
            }
            work |= l.has(GcodeProgram.Marker.DOT_BEGIN) && l.dotIndex == 0;
            if (work && pause < 0 && l.text.equals("G4 P250") && t.get(t.size() - 1).matches("G1 Z\\S+ F3000")) {
                pause = t.size();
            }
            t.add(l.text);
        }
        int stopAt = pause;
        DispenseExecutor[] ex = new DispenseExecutor[1];
        FakeChannel ch = new FakeChannel() {
            @Override
            public void send(String line, long timeoutMs) throws Exception {
                super.send(line, timeoutMs);
                if (sent.size() == stopAt + 1) {
                    ex[0].requestAbort("operator stop");
                }
            }
        };
        ex[0] = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        assertThrows(DispenseExecutor.AbortedException.class, () -> ex[0].stream(p, r, null));
        assertEquals("G4 P250", ch.sent.get(stopAt), "the STOP came during the pause");
        assertEquals(stop(false), ch.sent.subList(stopAt + 1, ch.sent.size()), "the rest of the pause is not sent");
        assertEquals(1, r.dotsDone, "the first work dot was laid and lifted");
        assertEquals(0, r.dotTimings.get(0).dotIndex);
    }

    @Test
    public void i7OnTimeLimitAborts() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.maxContinuousOnTimeS = 0.5;
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        ch.msPerLine = 40;
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        Exception e = assertThrows(DispenseExecutor.AbortedException.class, () -> ex.stream(p, r, null));
        assertTrue(e.getMessage().startsWith("I7"), e.getMessage());
        assertEquals(stop(false), tail(ch.sent, 6));
    }

    @Test
    public void keepalivePingsAtInterval() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.pumpToValveSettleMs = 3000;
        GcodeProgram p = program(c, true);
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        ex.stream(p, r, null);
        assertTrue(r.keepalivePings >= 3, "pings: " + r.keepalivePings);
        // no ping before arming or after disarming
        int arm = ch.sent.indexOf("M990 S3000");
        int disarm = ch.sent.indexOf("M990 S0");
        for (int i = 0; i < ch.sent.size(); i++) {
            if (ch.sent.get(i).equals("M990")) {
                assertTrue(i > arm && i < disarm);
            }
        }
        // the gap between consecutive pings while armed never exceeds interval + one G4 chunk
        long last = -1;
        long t = 1_000_000;
        long maxGap = 0;
        for (String l : ch.sent) {
            t += ch.msPerLine + (l.startsWith("G4 P") ? Long.parseLong(l.substring(4)) : 0);
            if (l.equals("M990") || l.equals("M990 S3000")) {
                if (last > 0) {
                    maxGap = Math.max(maxGap, t - last);
                }
                last = t;
            }
        }
        assertTrue(maxGap <= c.keepaliveIntervalMs * 2 + 50, "max gap " + maxGap);
    }

    static boolean isMotion(String l) {
        return l.startsWith("G0") || l.startsWith("G1");
    }

    /**
     * Review finding: a Stop that lands while a keepalive ping's "ok" is outstanding let the next
     * program line (G1 B / Z / G0 X) out after OpenPnP's DISABLE_COMMAND.
     */
    @Test
    public void stopDuringAPingLetsNoMotionLineOut() throws Exception {
        for (int k = 1; k <= 12; k++) {
            DispenseConfig c = GcodeStreamTest.toyConfig();
            GcodeProgram p = program(c, true);
            final int K = k;
            final DispenseExecutor[] ex = new DispenseExecutor[1];
            FakeChannel ch = new FakeChannel() {
                int pings;

                @Override
                public void send(String line, long timeoutMs) throws Exception {
                    super.send(line, timeoutMs);
                    now += 400;
                    if (line.equals("M990") && ++pings == K) {
                        // the Stop thread: abort, handshake, then the driver's DISABLE_COMMAND
                        ex[0].requestAbort("Stop pressed");
                        ex[0].awaitLineWritten(50);
                        sent.add("<<DISABLE_COMMAND>>");
                    }
                }
            };
            ex[0] = new DispenseExecutor(c, ch, "B", () -> ch.now);
            try {
                ex[0].stream(p, run(3), null);
            }
            catch (DispenseExecutor.AbortedException e) {
                // expected once the ping count is reached
            }
            int mark = ch.sent.indexOf("<<DISABLE_COMMAND>>");
            if (mark < 0) {
                continue; // fewer pings than k in this program
            }
            for (String l : ch.sent.subList(mark, ch.sent.size())) {
                assertFalse(isMotion(l), "k=" + k + ": motion after DISABLE_COMMAND: " + l + " in " + ch.sent);
            }
        }
    }

    /**
     * Review finding (same race, between the abort check and the write): the Stop path waits until a
     * line that passed its check has been handed to the channel, so it precedes the DISABLE_COMMAND.
     */
    @Test
    public void stopHandshakeWaitsForALineAlreadyPastItsCheck() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<String> wire = java.util.Collections.synchronizedList(new ArrayList<>());
        GcodeChannel ch = new GcodeChannel() {
            int n;

            @Override
            public void send(String line, long timeoutMs) throws Exception {
                if (++n == 15) { // a line inside the session: block before it reaches the wire
                    entered.countDown();
                    release.await();
                }
                wire.add(line);
            }

            @Override
            public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
                send(line, timeoutMs);
                return Arrays.asList("ok");
            }
        };
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", System::currentTimeMillis);
        Thread t = new Thread(() -> {
            try {
                ex.stream(p, run(3), null);
            }
            catch (Exception e) {
                // aborted
            }
        });
        t.start();
        entered.await();
        ex.requestAbort("Stop pressed");
        new Thread(() -> {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                // ignore
            }
            release.countDown();
        }).start();
        assertTrue(ex.awaitLineWritten(2000), "handshake returns once the line in flight was written");
        int written = wire.size();
        wire.add("<<DISABLE_COMMAND>>");
        t.join(5000);
        assertTrue(written >= 15, "the line already past its check went out before the Stop lines: " + wire);
        // after that line only the engine's own stop lines follow (in any interleaving with OpenPnP's)
        List<String> after = new ArrayList<>(wire.subList(15, wire.size()));
        after.remove("<<DISABLE_COMMAND>>");
        assertEquals(stop(false), after, wire.toString());
    }

    /** Review finding: every line passes the controller-command guard before it reaches the channel. */
    @Test
    public void aLineOutsideTheCommandGuardIsNeverSent() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        int i = 0;
        while (!p.lines.get(i).text.equals("M106 P3 S255")) {
            i++;
        }
        GcodeProgram.Line valve = p.lines.get(i);
        p.lines.set(i, new GcodeProgram.Line("M106 P3 S180", valve.marker, valve.dotIndex, valve.padId,
                valve.rotationDeg, valve.extra));
        FakeChannel ch = new FakeChannel();
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        Exception e = assertThrows(IllegalStateException.class, () -> ex.stream(p, r, null));
        assertTrue(e.getMessage().contains("M106 P3 S180"), e.getMessage());
        assertFalse(ch.sent.contains("M106 P3 S180"));
        assertEquals(stop(false), tail(ch.sent, 6));
    }

    /** Review finding: the abort sequence goes through sendCritical (stale controller errors). */
    @Test
    public void shutdownUsesCriticalSends() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        List<String> critical = new ArrayList<>();
        FakeChannel ch = new FakeChannel() {
            @Override
            public String sendCritical(String line, long timeoutMs) throws Exception {
                critical.add(line);
                send(line, timeoutMs);
                return line.equals("M107 P3") ? "cleared a pending error" : null;
            }
        };
        ch.failAt = 20;
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now);
        RunResult r = run(3);
        assertThrows(Exception.class, () -> ex.stream(p, r, null));
        assertEquals(stop(false), critical);
        assertTrue(r.warnings.contains("cleared a pending error"), r.warnings.toString());
    }

    /** BR-02: what the watch of the screw's motor hears (the controller's idle clock, the stop sequence's M18). */
    static class Watch implements DispenseExecutor.ScrewWatch {
        /** Per acknowledged move: true when it turned the screw. */
        final List<Boolean> moved = new ArrayList<>();
        final List<String> off = new ArrayList<>();
        int armed;
        final FakeChannel ch;

        Watch(FakeChannel ch) {
            this.ch = ch;
        }

        @Override
        public void moved(boolean screw) {
            moved.add(screw);
        }

        @Override
        public void watchdogArmed() {
            armed++;
        }

        @Override
        public void switchedOff(String line, boolean acknowledged) {
            off.add(line + (acknowledged ? "" : " (failed)"));
        }
    }

    /**
     * BR-02: every acknowledged move restarts the controller's idle clock (Marlin's, on any queued move), a screw move
     * also switches the motor on; a stream sends no M18.
     */
    @Test
    public void theWatchHearsEveryMoveOfAStream() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        Watch w = new Watch(ch);
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now).watchScrew(w);
        ex.stream(p, run(3), null);
        long bMoves = ch.sent.stream().filter(l -> l.startsWith("G1 B")).count();
        long others = ch.sent.stream().filter(l -> (l.startsWith("G0 ") || l.startsWith("G1 ")) && !l.startsWith("G1 B"))
                .count();
        assertTrue(bMoves > 0 && others > 0);
        assertEquals(bMoves, w.moved.stream().filter(b -> b).count(), "one per G1 B line");
        assertEquals(others, w.moved.stream().filter(b -> !b).count(), "one per X/Y/Z move");
        assertEquals(new ArrayList<String>(), w.off);
        assertEquals(0, w.armed, "no keepalive: the watchdog stays as it was");
        Watch k = new Watch(ch);
        new DispenseExecutor(c, ch, "B", () -> ch.now).watchScrew(k).stream(program(c, true), run(3), null);
        assertEquals(1, k.armed, "M990 S3000: the firmware forgets a trip it kept");
    }

    /** BR-02: the I1 shutdown's M18 B switches the screw's motor off: heard also when its send failed. */
    @Test
    public void theWatchHearsTheStopSequencesM18() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram p = program(c, false);
        FakeChannel ch = new FakeChannel();
        ch.failAt = 20;
        Watch w = new Watch(ch);
        DispenseExecutor ex = new DispenseExecutor(c, ch, "B", () -> ch.now).watchScrew(w);
        assertThrows(Exception.class, () -> ex.stream(p, run(3), null));
        assertEquals(Arrays.asList("M18 B"), w.off);
        FakeChannel dead = new FakeChannel() {
            @Override
            public void send(String line, long timeoutMs) throws Exception {
                throw new Exception("link closed");
            }
        };
        Watch w2 = new Watch(dead);
        new DispenseExecutor(c, dead, "B", () -> dead.now).watchScrew(w2).shutdown(run(0));
        assertEquals(Arrays.asList("M18 B (failed)"), w2.off);
    }

    /** BR-02: the lines that switch the screw's motor off (Marlin's M18 / M84) and the lines that turn it. */
    @Test
    public void whichLinesSwitchTheScrewMotorOffOrTurnTheScrew() {
        for (String l : new String[] {"M18 B", "m18 b ; screw off", "M84 B", "M18", "M84", "M018 B", "M18B", "M18 S60",
            "M18 X Y B"}) {
            assertTrue(ControllerCommands.switchesScrewOff(l, "B"), l);
        }
        for (String l : new String[] {"M18 X Y Z", "M18 A", "M17 B", "M180", "M410", "G1 B10", "; M18 B", "",
            "M107 P3"}) {
            assertFalse(ControllerCommands.switchesScrewOff(l, "B"), l);
        }
        assertTrue(ControllerCommands.switchesScrewOff("M18 A", "A"), "the screw's configured letter");
        for (String l : new String[] {"G1 B12.3456 F10800", "G0 X5 B1", "g1 b-3"}) {
            assertTrue(ControllerCommands.turnsScrew(l, "B"), l);
        }
        for (String l : new String[] {"G1 X10 F3000", "G92 B0", "M201 B10000", "M203 B2400", "G4 P100", "M18 B",
            "G1 A5"}) {
            assertFalse(ControllerCommands.turnsScrew(l, "B"), l);
        }
        for (String l : new String[] {"G0 X1", "G1 Z10 F600", "g1 b3", "G28", "G28 X Y"}) {
            assertTrue(ControllerCommands.isMove(l), l);
        }
        for (String l : new String[] {"G92 X0", "G4 P100", "M400", "M18 B", "G280", "", "G1 F600", "G0"}) {
            assertFalse(ControllerCommands.isMove(l), l + ": names no axis, queues no move");
        }
        java.util.Map<Character, Double> w = ControllerCommands.axisWords("G1 X10 B-2.5 F600 ; to the waste");
        assertEquals(2, w.size(), w.toString());
        assertEquals(10, w.get('X'), 1e-9);
        assertEquals(-2.5, w.get('B'), 1e-9);
        assertTrue(ControllerCommands.axisWords("G1 F600").isEmpty());
        assertEquals(0, ControllerCommands.axisWords("G92 B0").get('B'), 1e-9);
        assertTrue(ControllerCommands.armsWatchdog("M990 S3000"));
        assertFalse(ControllerCommands.armsWatchdog("M990 S0"), "a disarm keeps the trip");
        assertFalse(ControllerCommands.armsWatchdog("M990"), "the report");
        assertFalse(ControllerCommands.armsWatchdog("M99 S3000"));
    }

    /**
     * BR-02: the il/safety firmware's host watchdog (M990) switches the screw's motor off when it trips: its
     * "Error:host watchdog", and the "tripped" its M990 report keeps until the next arm.
     */
    @Test
    public void theHostWatchdogsTripAndItsReport() {
        assertTrue(ControllerCommands.isHostWatchdogTrip("Error:host watchdog"));
        assertTrue(ControllerCommands.isHostWatchdogTrip("error: Host watchdog"));
        assertFalse(ControllerCommands.isHostWatchdogTrip("echo:Host watchdog: off tripped"), "the report is no trip");
        assertFalse(ControllerCommands.isHostWatchdogTrip("Error:M990 S must be 0 or 100..3600000"));
        assertFalse(ControllerCommands.isHostWatchdogTrip(null));
        assertTrue(ControllerCommands.reportsWatchdogTrip(Arrays.asList("echo:Host watchdog: off tripped", "ok")));
        assertTrue(ControllerCommands.reportsWatchdogTrip(Arrays.asList("echo:Host watchdog: armed S3000 tripped")));
        assertFalse(ControllerCommands.reportsWatchdogTrip(Arrays.asList("echo:Host watchdog: armed S3000", "ok")));
        assertFalse(ControllerCommands.reportsWatchdogTrip(Arrays.asList("echo:Host watchdog: off", "ok")));
        assertFalse(ControllerCommands.reportsWatchdogTrip(Arrays.asList("ok")));
        assertFalse(ControllerCommands.reportsWatchdogTrip(null));
    }
}
