package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.TcpCommunications;
import org.openpnp.model.Configuration;

/**
 * BR-42: two threads send on one GcodeDriver at a STOP (OpenPnP's Stop thread beside the engine's line in flight, then
 * its stop lines and recovery). Every send cleared the driver's one confirmation queue first, so a send could take away
 * the ok another sender had not read yet: that sender then waited the driver's whole timeout (CI: 10 s; the engine's
 * recovery M400: 60 s).
 */
public class GcodeDriverTwoSendersTest {
    RecordingGcodeServer server;
    LateReader driver;
    ReferenceMachine machine;

    /** A sender that reads its ok late: the ok of {@link #lateLine} is in the queue before it looks. */
    static final class LateReader extends GcodeDriver {
        volatile String lateLine;
        volatile long lateMs;

        @Override
        protected Line waitForConfirmation(String command, long timeout) throws Exception {
            if (command.equals(lateLine)) {
                Thread.sleep(lateMs);
            }
            return super.waitForConfirmation(command, timeout);
        }

        int confirmationsWaiting() {
            return receivedConfirmationsQueue.size();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        server = new RecordingGcodeServer();
        File dir = new File(Files.createTempDirectory("two-senders").toFile(), ".openpnp");
        Configuration.initialize(dir);
        Configuration.get().load();
        driver = new LateReader();
        driver.createDefaults();
        driver.setConnectionKeepAlive(false);
        driver.setCommunicationsType(CommunicationsType.tcp);
        TcpCommunications tcp = (TcpCommunications) driver.getCommunications();
        tcp.setIpAddress("127.0.0.1");
        tcp.setPort(server.getPort());
        driver.setConnectWaitTimeMilliseconds(0);
        driver.setTimeoutMilliseconds(2000);
        machine = (ReferenceMachine) Configuration.get().getMachine();
        while (!machine.getDrivers().isEmpty()) {
            machine.removeDriver(machine.getDrivers().get(0));
        }
        machine.addDriver(driver);
        machine.setEnabled(true);
    }

    @AfterEach
    public void after() throws Exception {
        try {
            machine.setEnabled(false);
        }
        finally {
            server.close();
        }
    }

    @Test
    public void anOkNotReadYetIsNotClearedAwayByAnotherSender() throws Exception {
        driver.lateLine = "M410";
        driver.lateMs = 300;
        FutureTask<Void> stop = background(() -> {
            driver.sendCommand("M410");
            return null;
        }, "OpenPnP Stop");
        long t0 = System.currentTimeMillis();
        while (driver.confirmationsWaiting() == 0 && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(2);
        }
        assertTrue(driver.confirmationsWaiting() == 1, "M410's ok is in, not read yet");
        driver.sendCommand("M107 P3"); // the engine's line meanwhile
        long t1 = System.currentTimeMillis();
        stop.get(5, TimeUnit.SECONDS); // was: "timeout waiting for response to M410" after 2 s
        assertTrue(System.currentTimeMillis() - t1 < 1500, "M410's sender got an ok without waiting the timeout");
    }

    /** One sender alone, as before: the late ok of a line that timed out does not confirm the next line. */
    @Test
    public void aSenderAloneStillDropsAStaleOk() throws Exception {
        java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
        server.holdOkUntil.put("M118 late", answer);
        Exception timedOut = null;
        try {
            driver.sendCommand("M118 late", 300);
        }
        catch (Exception e) {
            timedOut = e;
        }
        assertTrue(timedOut != null && String.valueOf(timedOut.getMessage()).contains("timeout"), "timed out");
        answer.countDown();
        long t0 = System.currentTimeMillis();
        while (driver.confirmationsWaiting() == 0 && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(2);
        }
        assertTrue(driver.confirmationsWaiting() == 1, "the late ok came in");
        driver.sendCommand("M107 P2"); // drops the stale ok, then waits for its own
        Thread.sleep(100);
        assertTrue(driver.confirmationsWaiting() == 0, "its own ok confirmed it, none left over");
    }

    static <T> FutureTask<T> background(Callable<T> c, String name) {
        FutureTask<T> f = new FutureTask<>(c);
        Thread t = new Thread(f, name);
        t.setDaemon(true);
        t.start();
        return f;
    }
}
