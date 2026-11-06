package org.openpnp.spi.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.PropertyChangeListener;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DisableFailsGcodeDriver;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

import com.google.common.util.concurrent.FutureCallback;

/**
 * Instance Labs: OpenPnP fires machine listeners on the event's thread (a task's busy on the machine thread, a Stop on
 * its caller's) while other threads add theirs (the engine's start, a pressure hold, a home's cut-off). Firing must not
 * fail on that: the task that fired it never ran (PowerCycleSimTest's flakes of 11-05: OpenPnP's task ahead of the
 * engine's catch-up read), and a Stop threw before it switched anything off. BR-106: nor on a listener that throws, nor
 * on a task dropped behind one that failed.
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

    /** A listener with a bug: it throws on {@code event}, counted in {@code heard}. */
    static MachineListener throwingOn(String event, CountDownLatch heard) {
        return new MachineListener.Adapter() {
            void bug(String here) {
                if (here.equals(event)) {
                    heard.countDown();
                    throw new IllegalStateException("test: a listener's bug in " + here);
                }
            }

            @Override
            public void machineHomed(Machine machine, boolean isHomed) {
                bug("homed(" + isHomed + ")");
            }

            @Override
            public void machineAboutToBeDisabled(Machine machine, String reason) {
                bug("aboutToBeDisabled");
            }

            @Override
            public void machineDisabled(Machine machine, String reason) {
                bug("disabled");
            }

            @Override
            public void machineBusy(Machine machine, boolean busy) {
                bug("busy(" + busy + ")");
            }
        };
    }

    /**
     * BR-106: a Stop with two listeners that throw on its {@code event}. The first one's exception ended the Stop: on
     * homed(false) and aboutToBeDisabled before its drivers (they stayed on, the machine read started). Now the Stop's
     * lines go out, it ends stopped, and the other listener still hears it (whichever the loop reaches first).
     */
    static void stopPastListenersThatThrow(String event) throws Exception {
        SimMachine sim = new SimMachine();
        try {
            sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
            long disables = sim.driver.getDisableCommandsSent();
            CountDownLatch heard = new CountDownLatch(2);
            sim.machine.addListener(throwingOn(event, heard));
            sim.machine.addListener(throwingOn(event, heard));
            sim.machine.setEnabled(false);
            assertFalse(sim.machine.isEnabled());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
            assertEquals(0, heard.getCount(), "both listeners heard it");
        }
        finally {
            sim.close();
        }
    }

    @Test
    public void aStopSwitchesOffPastHomedListenersThatThrow() throws Exception {
        stopPastListenersThatThrow("homed(false)");
    }

    @Test
    public void aStopSwitchesOffPastAboutToBeDisabledListenersThatThrow() throws Exception {
        stopPastListenersThatThrow("aboutToBeDisabled");
    }

    @Test
    public void aStopEndsPastDisabledListenersThatThrow() throws Exception {
        stopPastListenersThatThrow("disabled");
    }

    /**
     * BR-106: a "homed" property listener that throws (the GUI's binding runs on the Stop's thread) ended the Stop at
     * its setHomed(false), before its drivers and its machineAboutToBeDisabled listeners (the engine's abort, the
     * hold's vent, the pump and the VAC actuators off). The drivers switch off whatever the steps before them throw,
     * those listeners still hear the Stop, and the Stop still throws it, after them.
     */
    @Test
    public void aStopSwitchesOffPastAHomedPropertyListenerThatThrows() throws Exception {
        SimMachine sim = new SimMachine();
        PropertyChangeListener bug = e -> {
            throw new IllegalStateException("test: a homed property listener's bug");
        };
        CountDownLatch aboutToBeDisabled = new CountDownLatch(1);
        MachineListener heard = new MachineListener.Adapter() {
            @Override
            public void machineAboutToBeDisabled(Machine machine, String reason) {
                aboutToBeDisabled.countDown();
            }
        };
        try {
            sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
            long disables = sim.driver.getDisableCommandsSent();
            sim.machine.addListener(heard);
            sim.machine.addPropertyChangeListener("homed", bug);
            assertThrows(IllegalStateException.class, () -> sim.machine.setEnabled(false), "the Stop reports it");
            assertFalse(sim.machine.isEnabled());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
            assertEquals(0, aboutToBeDisabled.getCount(), "its machineAboutToBeDisabled listeners heard it");
        }
        finally {
            sim.machine.removePropertyChangeListener("homed", bug);
            sim.machine.removeListener(heard);
            sim.close();
        }
    }

    /**
     * BR-106: a listener's Error (an OOM, a StackOverflowError) is not swallowed, and it ended the Stop before its
     * drivers. They switch off first now; the Error propagates after them.
     */
    @Test
    public void aStopSwitchesOffBeforeAListenersErrorPropagates() throws Exception {
        SimMachine sim = new SimMachine();
        MachineListener error = new MachineListener.Adapter() {
            @Override
            public void machineAboutToBeDisabled(Machine machine, String reason) {
                throw new StackOverflowError("test: a listener's Error");
            }
        };
        try {
            sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
            long disables = sim.driver.getDisableCommandsSent();
            sim.machine.addListener(error);
            assertThrows(StackOverflowError.class, () -> sim.machine.setEnabled(false), "the Error propagates");
            assertFalse(sim.machine.isEnabled());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out first");
        }
        finally {
            sim.machine.removeListener(error);
            sim.close();
        }
    }

    /**
     * BR-106 review: an Error from a driver's own disable skipped the drivers after it, the machine's switch to
     * disabled and both disable events: the machine read started, the web STOP's switch-off stayed announced, and
     * Start waited and refused. The other drivers go on now, the machine ends disabled, its listeners hear
     * machineDisableFailed, and the Error propagates.
     */
    @Test
    public void aDriversErrorStillEndsTheMachineDisabled() throws Exception {
        SimMachine sim = new SimMachine(true, DisableFailsGcodeDriver.class);
        DisableFailsGcodeDriver driver = (DisableFailsGcodeDriver) sim.driver;
        CountDownLatch failed = new CountDownLatch(1);
        MachineListener heard = new MachineListener.Adapter() {
            @Override
            public void machineDisableFailed(Machine machine, String reason) {
                failed.countDown();
            }
        };
        try {
            sim.machine.addListener(heard);
            driver.disableFails = new StackOverflowError("test: the driver's Error");
            assertThrows(StackOverflowError.class, () -> sim.machine.setEnabled(false), "the Error propagates");
            assertFalse(sim.machine.isEnabled());
            assertEquals(0, failed.getCount(), "its listeners heard machineDisableFailed");
        }
        finally {
            driver.disableFails = null;
            sim.machine.removeListener(heard);
            sim.close();
        }
    }

    /**
     * BR-106: a task with two listeners that throw on its busy({@code busy}). On busy(true) the task failed before its
     * body; on busy(false) it failed after it, and the machine stayed busy for good (its setTaskThread(null) skipped).
     */
    static void taskPastBusyListenersThatThrow(boolean busy) throws Exception {
        SimMachine sim = new SimMachine();
        try {
            CountDownLatch heard = new CountDownLatch(2);
            sim.machine.addListener(throwingOn("busy(" + busy + ")", heard));
            sim.machine.addListener(throwingOn("busy(" + busy + ")", heard));
            assertEquals("ran", sim.machine.submit(() -> "ran").get(10, TimeUnit.SECONDS));
            assertFalse(sim.machine.isBusy(), "idle after it");
            assertEquals(0, heard.getCount(), "both listeners heard it");
        }
        finally {
            sim.close();
        }
    }

    @Test
    public void aTaskRunsPastBusyListenersThatThrow() throws Exception {
        taskPastBusyListenersThatThrow(true);
    }

    @Test
    public void aTaskEndsIdlePastIdleListenersThatThrow() throws Exception {
        taskPastBusyListenersThatThrow(false);
    }

    /**
     * BR-106: OpenPnP drops the tasks queued behind one that throws (its Home meeting a limit). They never completed,
     * so a caller's untimed wait for one (Machine.execute) hung for good. Now each is cancelled.
     */
    @Test
    public void aTaskQueuedBehindAFailingOneIsCancelled() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            CountDownLatch queued = new CountDownLatch(1);
            Future<Object> failing = sim.machine.submit(() -> {
                queued.await(10, TimeUnit.SECONDS);
                throw new Exception("test: a task fails");
            });
            Future<String> behind = sim.machine.submit(() -> "ran");
            queued.countDown();
            assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> behind.get(10, TimeUnit.SECONDS));
        }
        finally {
            sim.close();
        }
    }

    /**
     * BR-106: a task submitted after that purge (here by the failing task's callback) was thrown away by OpenPnP's
     * shutdownNow() without completing, and the machine stayed busy for good: with it queued, the failing task skipped
     * its idle (busy false, setTaskThread(null)). Now it is cancelled, and the machine idles.
     */
    @Test
    public void aTaskSubmittedAsAFailingOneEndsIsCancelledAndTheMachineIdles() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            AtomicReference<Future<String>> late = new AtomicReference<>();
            Future<Object> failing = sim.machine.submit(() -> {
                throw new Exception("test: a task fails");
            }, new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object result) {
                }

                @Override
                public void onFailure(Throwable t) {
                    late.set(sim.machine.submit(() -> "ran"));
                }
            });
            assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> late.get().get(10, TimeUnit.SECONDS));
            assertFalse(sim.machine.isBusy(), "idle after the failing task");
        }
        finally {
            sim.close();
        }
    }

    /**
     * BR-106: a Stop's listeners run on its thread before its drivers, and AbstractHead's switches the pump off through
     * Machine.execute: an untimed wait for a machine task. Queued behind one that then failed (execute's busy check
     * passes while that one is still queued: a task not begun is not busy), the pump's task was dropped without
     * completing, and the Stop hung before its drivers for good. Here a listener waits so.
     */
    @Test
    public void aStopWaitingForATaskDroppedBehindAFailingOneSwitchesOff() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
            long disables = sim.driver.getDisableCommandsSent();
            CountDownLatch queued = new CountDownLatch(1);
            sim.machine.addListener(new MachineListener.Adapter() {
                @Override
                public void machineAboutToBeDisabled(Machine machine, String reason) {
                    try {
                        Future<Boolean> pumpOff = machine.submit(() -> true);
                        queued.countDown();
                        pumpOff.get(); // untimed, as execute's
                    }
                    catch (Exception e) {
                        // as AbstractHead's "Cannot switch off pump."
                    }
                }
            });
            Future<Object> failing = sim.machine.submit(() -> {
                queued.await(10, TimeUnit.SECONDS);
                throw new Exception("test: a task fails");
            });
            CountDownLatch stopped = new CountDownLatch(1);
            Thread stop = new Thread(() -> {
                try {
                    sim.machine.setEnabled(false);
                    stopped.countDown();
                }
                catch (Exception e) {
                    // not stopped: the assertion below fails
                }
            });
            stop.setDaemon(true); // a Stop that hangs must not keep the test JVM up
            stop.start();
            assertTrue(stopped.await(10, TimeUnit.SECONDS), "the Stop hung before its drivers");
            assertFalse(sim.machine.isEnabled());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
            assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
        }
        finally {
            sim.close();
        }
    }
}
