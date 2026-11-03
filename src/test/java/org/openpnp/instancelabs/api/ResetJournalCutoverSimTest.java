package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;

/**
 * #11 re-review MINOR: the first start of the journaling engine on a database the build before it used (the deployed
 * build asked for the start angle after every connect) cannot know whether the controller was switched off meanwhile,
 * so the screw count may have restarted: I11 asks for the angle once. The position stays as the connect reads it, and
 * a fresh database (a new config, every test) starts without that epoch.
 */
public class ResetJournalCutoverSimTest {
    SimMachine sim;

    @AfterEach
    public void after() throws Exception {
        DispenseService svc = DispenseService.get();
        if (svc != null) {
            svc.shutdown();
        }
        sim.close();
    }

    @Test
    public void theFirstStartOnADatabaseFromBeforeTheJournalDatesTheScrewCount() throws Exception {
        sim = new SimMachine(false);
        // what the build before the journal left: a database in use, and a start angle set under it
        try (DispenseDb db = DispenseDb.open(sim.configDir)) {
            db.event(null, "INFO", "I11", "screw start angle set to 298.0 deg");
        }
        DispenseConfig c = DispenseConfig.load(sim.configDir);
        long setAt = System.currentTimeMillis() - 60_000;
        c.profile.startAngleSetAtMs = setAt;
        c.save(sim.configDir);

        DispenseService svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
        Long epoch = svc.getControllerResetAtMs();
        assertTrue(epoch != null && epoch > setAt, "the screw count may have restarted: " + epoch);
        assertTrue(svc.getControllerReset().isPositionKnown(), "the position is not affected");
        assertEquals(null, svc.getControllerReset().detectedAtMs());
        long marked = svc.getDb().newestEvents(ControllerReset.EVENT_COUNT_EPOCH).size();
        assertEquals(1, marked);

        // once: the next start keeps that epoch and adds none
        svc = DispenseService.start(Configuration.get());
        assertEquals(epoch, svc.getControllerResetAtMs());
        assertEquals(1, svc.getDb().query("SELECT * FROM events WHERE code = ?",
                ControllerReset.EVENT_COUNT_EPOCH).size());
    }

    @Test
    public void aFreshDatabaseStartsWithoutAnEpoch() throws Exception {
        sim = new SimMachine(false);
        DispenseService svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
        assertEquals(null, svc.getControllerResetAtMs());
        svc = DispenseService.start(Configuration.get());
        assertEquals(null, svc.getControllerResetAtMs(), "a restart of a journaling engine is not a cutover");
        assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = ?", ControllerReset.EVENT_JOURNAL).isEmpty());
    }
}
