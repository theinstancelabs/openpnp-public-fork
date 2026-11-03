package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** EdtRunner: headless calls serialised on the caller's thread, window calls on the event thread. */
public class EdtRunnerTest {
    BooleanSupplier saved;

    @BeforeEach
    public void save() {
        saved = EdtRunner.windowExists;
    }

    @AfterEach
    public void restore() {
        EdtRunner.windowExists = saved;
    }

    @Test
    public void headlessRunsHereOneAtATime() throws Exception {
        EdtRunner.windowExists = () -> false;
        Thread caller = Thread.currentThread();
        assertSame(caller, EdtRunner.call(Thread::currentThread, 1000));

        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean bInside = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = new Thread(() -> {
            try {
                EdtRunner.call(() -> {
                    aInside.countDown();
                    return release.await(10, TimeUnit.SECONDS);
                }, 1000);
            }
            catch (Throwable t) {
                failure.set(t);
            }
        });
        a.start();
        assertTrue(aInside.await(5, TimeUnit.SECONDS));
        Thread b = new Thread(() -> {
            try {
                EdtRunner.call(() -> {
                    bInside.set(true);
                    return null;
                }, 10000);
            }
            catch (Throwable t) {
                failure.set(t);
            }
        });
        b.start();
        Thread.sleep(200);
        assertFalse(bInside.get(), "a second call waits while the first runs");
        // a third with a short start timeout gives up without running
        AtomicBoolean cRan = new AtomicBoolean();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EdtRunner.call(() -> {
            cRan.set(true);
            return null;
        }, 100));
        assertTrue(e.getMessage().contains("try again"), e.getMessage());
        release.countDown();
        a.join(5000);
        b.join(5000);
        assertTrue(bInside.get(), "the second call runs once the first is done");
        assertFalse(cRan.get());
        assertEquals(null, failure.get());
        assertFalse(EdtRunner.HEADLESS_LOCK.isLocked());
    }

    @Test
    public void headlessExceptionsComeThroughAsTheyAre() {
        EdtRunner.windowExists = () -> false;
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        assertSame(boom, assertThrows(IllegalArgumentException.class, () -> EdtRunner.call(() -> {
            throw boom;
        }, 1000)));
        assertFalse(EdtRunner.HEADLESS_LOCK.isLocked());
    }

    @Test
    public void withAWindowItRunsOnTheEventThread() throws Exception {
        EdtRunner.windowExists = () -> true;
        assertTrue(EdtRunner.call(SwingUtilities::isEventDispatchThread, 5000));
        assertEquals(42, (int) EdtRunner.call(() -> 42, 5000));
        Exception boom = new Exception("boom");
        assertSame(boom, assertThrows(Exception.class, () -> EdtRunner.call(() -> {
            throw boom;
        }, 5000)));
        // called on the event thread itself: runs at once (no deadlock)
        AtomicReference<Object> inner = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                inner.set(EdtRunner.call(() -> "direct", 5000));
            }
            catch (Exception ex) {
                inner.set(ex);
            }
        });
        assertEquals("direct", inner.get());
    }

    @Test
    public void startTimeoutCancelsBeforeRunning() throws Exception {
        EdtRunner.windowExists = () -> true;
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(5, TimeUnit.SECONDS));
        AtomicBoolean ran = new AtomicBoolean();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EdtRunner.call(() -> {
            ran.set(true);
            return null;
        }, 100));
        assertTrue(e.getMessage().contains("OpenPnP's window is busy"), e.getMessage());
        release.countDown();
        SwingUtilities.invokeAndWait(() -> {
        }); // the cancelled task has had its turn
        assertFalse(ran.get(), "a call that timed out never runs");
    }
}
