package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.driver.GcodeDriver;

/**
 * BR-44: the live machine keeps its controller link open while it is off (connection-keep-alive). When that link
 * dies meanwhile (the controller switched off: its USB port goes away), Start opens the link again, with the
 * CONNECT_COMMAND a fresh controller needs, instead of failing on the dead port ("IO Error on reading from the
 * controller", seen by the deploy end-to-end test after a power cycle).
 */
public class KeptAliveLinkTest {
    SimMachine sim;

    @AfterEach
    public void after() throws Exception {
        if (sim != null) {
            sim.close();
        }
    }

    /** The driver's reader has ended: it answers "IO Error on reading from the controller". */
    boolean readerEnded() {
        try {
            sim.driver.receiveResponses();
            return false;
        }
        catch (Exception e) {
            return String.valueOf(e.getMessage()).contains("IO Error on reading");
        }
    }

    @Test
    public void aStartAfterTheKeptAliveLinkDiedOpensItAgain() throws Exception {
        sim = new SimMachine(false);
        sim.driver.setConnectionKeepAlive(true);
        String connect = sim.driver.getCommand(null, GcodeDriver.CommandType.CONNECT_COMMAND);
        sim.machine.setEnabled(true);
        sim.machine.setEnabled(false); // Stop: the link stays open

        sim.server.dropLink(); // the controller is switched off
        long until = System.currentTimeMillis() + 5000;
        while (!readerEnded()) { // OpenPnP's reader sees the end of the link
            assertTrue(System.currentTimeMillis() < until, "OpenPnP's reader never saw the link end");
            Thread.sleep(20);
        }
        sim.server.mark();
        sim.machine.setEnabled(true);
        assertTrue(sim.machine.isEnabled());

        List<String> sent = sim.server.sinceMark();
        String first = connect == null ? null : connect.split("\n")[0].split(";")[0].trim();
        assertTrue(first == null || first.isEmpty() || sent.stream().anyMatch(l -> l.startsWith(first)),
                "the reopened link gets the CONNECT_COMMAND again (" + first + "): " + sent);
        // and it works: a command goes out and is answered
        List<String> m114 = new DriverGcodeChannel(sim.driver).sendAndCollect("M114", 5000);
        assertTrue(m114.stream().anyMatch(l -> l.startsWith("X:")), m114.toString());
    }
}
