package org.openpnp.spi.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

/**
 * Instance Labs: OpenPnP fires machine listeners on the event's thread (a task's busy on the machine thread, a Stop on
 * its caller's) while other threads add theirs (the engine's start, a pressure hold, a home's cut-off). Firing must not
 * fail on that: the task that fired it never ran (PowerCycleSimTest's flakes of 11-05: OpenPnP's task ahead of the
 * engine's catch-up read), and a Stop threw before it switched anything off.
 */
public class AbstractMachineListenersTest {
    /**
     * Two listeners: the first one {@code stop} (else a task's busy) reaches waits until another thread has added a
     * listener, so one more is still to be called after the set changed.
     */
    static void addAListenerWhileFiring(Machine m, boolean stop) {
        CountDownLatch firing = new CountDownLatch(1);
        CountDownLatch added = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            m.addListener(new MachineListener.Adapter() {
                @Override
                public void machineBusy(Machine machine, boolean busy) {
                    hold(!stop && busy);
                }

                @Override
                public void machineAboutToBeDisabled(Machine machine, String reason) {
                    hold(stop);
                }

                void hold(boolean here) {
                    if (here && firing.getCount() > 0) {
                        firing.countDown();
                        try {
                            added.await(5, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
        }
        new Thread(() -> {
            try {
                if (firing.await(30, TimeUnit.SECONDS)) {
                    m.addListener(new MachineListener.Adapter() {});
                }
            }
            catch (InterruptedException e) {
                // the test ended
            }
            added.countDown();
        }).start();
    }

    @Test
    public void aTaskRunsWhileAnotherThreadAddsAListener() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            addAListenerWhileFiring(sim.machine, false);
            assertEquals("ran", sim.machine.submit(() -> "ran").get(10, TimeUnit.SECONDS));
        }
        finally {
            sim.close();
        }
    }

    @Test
    public void aStopSwitchesOffWhileAnotherThreadAddsAListener() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            addAListenerWhileFiring(sim.machine, true);
            sim.machine.setEnabled(false); // threw before its drivers' disable
            assertFalse(sim.machine.isEnabled());
        }
        finally {
            sim.close();
        }
    }
}
