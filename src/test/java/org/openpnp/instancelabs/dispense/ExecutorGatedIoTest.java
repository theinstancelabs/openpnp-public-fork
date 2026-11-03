package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Gated controller I/O outside a program (homing's Z lift, the N1 probe): allowlist by scope, abort gate. */
public class ExecutorGatedIoTest {
    /** Records which path each line took; probe lines must arrive through the probe path. */
    static class PathChannel implements GcodeChannel {
        final List<String> sent = new ArrayList<>();
        final List<String> probe = new ArrayList<>();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        @Override
        public void send(String line, long timeoutMs) throws Exception {
            ControllerCommands.requireAllowed(line);
            hold();
            sent.add(line);
        }

        @Override
        public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
            send(line, timeoutMs);
            return Arrays.asList("X:1.0 Y:2.0 Z:31.5", "ok");
        }

        @Override
        public void sendProbe(String line, long timeoutMs) throws Exception {
            ControllerCommands.requireProbeAllowed(line);
            hold();
            probe.add(line);
        }

        @Override
        public List<String> collectProbe(String line, long timeoutMs) throws Exception {
            sendProbe(line, timeoutMs);
            return Arrays.asList("echo:i2c-reply: from:109 bytes:3 data:E8 F2 F6", "ok");
        }

        private void hold() throws InterruptedException {
            if (entered != null) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
        }
    }

    static DispenseExecutor exec(PathChannel ch) {
        return new DispenseExecutor(GcodeStreamTest.toyConfig(), ch, "B", () -> 0L);
    }

    @Test
    public void probeScopeUsesTheProbeGateAndOthersTheGlobalOne() throws Exception {
        PathChannel ch = new PathChannel();
        DispenseExecutor ex = exec(ch);
        RunResult r = new RunResult();
        ex.sendGated(DispenseExecutor.Scope.PROBE, "M106 P1 S180", 1000, r);
        List<String> reply = ex.queryGated(DispenseExecutor.Scope.PROBE, "M261 A109 B3 S1", 1000, r);
        assertEquals(-1510666, VacuumProber.parse(reply), "E8 F2 F6 = -1,510,666 (bench serial.log)");
        ex.sendGated(DispenseExecutor.Scope.PROBE, "G1 Z31.5000 F600", 1000, r);
        assertEquals(Arrays.asList("M106 P1 S180", "M261 A109 B3 S1", "G1 Z31.5000 F600"), ch.probe);
        // the global gate for every other scope: no N1 vacuum, no I2C
        for (DispenseExecutor.Scope sc : new DispenseExecutor.Scope[] {DispenseExecutor.Scope.DISPENSE,
            DispenseExecutor.Scope.HOME}) {
            assertThrows(IllegalStateException.class, () -> ex.sendGated(sc, "M106 P1 S180", 1000, r));
            assertThrows(IllegalStateException.class, () -> ex.queryGated(sc, "M260 A109", 1000, r));
        }
        ex.sendGated(DispenseExecutor.Scope.HOME, "G1 Z31.5000 F600", 1000, r);
        assertEquals(Arrays.asList("X:1.0 Y:2.0 Z:31.5", "ok"),
                ex.queryGated(DispenseExecutor.Scope.HOME, "M114", 1000, r));
        assertEquals(Arrays.asList("G1 Z31.5000 F600", "M114"), ch.sent);
        assertThrows(IllegalStateException.class,
                () -> ex.sendGated(DispenseExecutor.Scope.PROBE, "M106 P2 S180", 1000, r), "not a probe line");
        // every line sent is recorded, refused ones are not
        assertEquals(Arrays.asList("M106 P1 S180", "M261 A109 B3 S1", "G1 Z31.5000 F600", "G1 Z31.5000 F600", "M114"),
                r.streamedLines);
        assertEquals(5, r.linesSent);
    }

    @Test
    public void aTimeoutOfZeroOrLessIsRefused() {
        PathChannel ch = new PathChannel();
        DispenseExecutor ex = exec(ch);
        assertThrows(IllegalArgumentException.class,
                () -> ex.sendGated(DispenseExecutor.Scope.PROBE, "M400", 0, new RunResult()));
        assertThrows(IllegalArgumentException.class,
                () -> ex.queryGated(DispenseExecutor.Scope.HOME, "M114", -1, new RunResult()));
        assertTrue(ch.sent.isEmpty() && ch.probe.isEmpty());
    }

    @Test
    public void afterAnAbortNothingMoreIsSent() throws Exception {
        PathChannel ch = new PathChannel();
        DispenseExecutor ex = exec(ch);
        ex.requestAbort("STOP pressed in the web UI");
        DispenseExecutor.AbortedException e = assertThrows(DispenseExecutor.AbortedException.class,
                () -> ex.sendGated(DispenseExecutor.Scope.PROBE, "G1 Z5.0000 F300", 1000, new RunResult()));
        assertEquals("STOP pressed in the web UI", e.getMessage());
        assertThrows(DispenseExecutor.AbortedException.class,
                () -> ex.queryGated(DispenseExecutor.Scope.PROBE, "M261 A109 B3 S1", 1000, new RunResult()));
        assertTrue(ch.sent.isEmpty() && ch.probe.isEmpty());
        assertEquals("STOP pressed in the web UI", ex.getAbortReason());
    }

    @Test
    public void theStopPathWaitsForAGatedLineInFlight() throws Exception {
        PathChannel ch = new PathChannel();
        ch.entered = new CountDownLatch(1);
        ch.release = new CountDownLatch(1);
        DispenseExecutor ex = exec(ch);
        Thread t = new Thread(() -> {
            try {
                ex.sendGated(DispenseExecutor.Scope.PROBE, "G1 Z5.2500 F300", 5000, new RunResult());
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        assertTrue(ch.entered.await(5, TimeUnit.SECONDS));
        ex.requestAbort("stop");
        assertFalse(ex.awaitLineWritten(30), "the line is still between its abort check and its acknowledgement");
        ch.release.countDown();
        t.join(5000);
        assertTrue(ex.awaitLineWritten(30), "acknowledged: nothing in flight any more");
        assertEquals(Arrays.asList("G1 Z5.2500 F300"), ch.probe);
    }
}
