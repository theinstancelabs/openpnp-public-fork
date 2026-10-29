package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;

/**
 * The stop epoch (profile calibration design §7.8): a task queued before a STOP refuses at its start and sends nothing,
 * also when the STOP found no running task to abort (the gap between two steps of a pad calibration); a request a job
 * built before a STOP refuses even when it is queued after it; tasks queued after the STOP run as usual.
 */
public class StopEpochTest {
    static boolean sentAnything(SimMachine sim) {
        for (String l : sim.server.sinceMark()) {
            if (l.startsWith("G0") || l.startsWith("G1") || l.startsWith("M106") || l.startsWith("M114")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aTaskQueuedBeforeAStopRefusesAtItsStart() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
            DispenseService svc = DispenseService.start(Configuration.get());
            try {
                CountDownLatch hold = new CountDownLatch(1);
                // the machine thread busy with another task: the next engine task waits in the queue
                Future<Object> other = sim.machine.submit(() -> {
                    hold.await(30, TimeUnit.SECONDS);
                    return null;
                });
                sim.server.mark();
                long epoch = svc.stopEpoch();
                Future<RunResult> queued = svc.checkZ();
                assertTrue(svc.isBusy());
                svc.abort("STOP pressed in the web UI");
                assertEquals(epoch + 1, svc.stopEpoch(), "every STOP moves the epoch, a task running or not");
                hold.countDown();
                other.get(30, TimeUnit.SECONDS);
                RunResult r = queued.get(30, TimeUnit.SECONDS);
                assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
                assertEquals(DispenseService.STOPPED_SINCE_QUEUED, r.message);
                assertFalse(sentAnything(sim), "nothing was sent: " + sim.server.sinceMark());
                assertFalse(svc.isBusy());

                // a pad calibration step built (its epoch read) before the STOP refuses when it is queued after it
                DoseBlocksRequest req = new DoseBlocksRequest();
                req.kind = DoseBlocksRequest.LADDER;
                DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(800, "half turn", "blank");
                b.xy.add(new double[] {100, 40});
                req.blocks.add(b);
                req.stopEpoch = epoch;
                sim.server.mark();
                DoseBlocksResult res = svc.runDoseBlocks(req).get(30, TimeUnit.SECONDS);
                assertFalse(res.ok);
                assertEquals(RunResult.Status.REFUSED, res.run.status);
                assertEquals(DispenseService.STOPPED_SINCE_QUEUED, res.message);
                assertFalse(sentAnything(sim));

                // queued after the STOP: it runs (here: nothing to check)
                RunResult after = svc.checkZ().get(30, TimeUnit.SECONDS);
                assertEquals(RunResult.Status.OK, after.status, after.toString());
            }
            finally {
                svc.shutdown();
            }
        }
    }
}
