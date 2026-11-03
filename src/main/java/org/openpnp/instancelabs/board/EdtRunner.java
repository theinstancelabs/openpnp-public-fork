package org.openpnp.instancelabs.board;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;

/**
 * OpenPnP's configuration lists (packages, parts, boards) are plain maps that Swing tables listen
 * to, so every read or change of them from an API thread goes through here: on Swing's event thread
 * when OpenPnP's window exists, otherwise on the calling thread under one global lock (headless:
 * the calls are serialised as the event thread would serialise them).
 */
public final class EdtRunner {
    /** Serialises headless calls (no window, so no event thread to do it). */
    static final ReentrantLock HEADLESS_LOCK = new ReentrantLock();
    /** "OpenPnP's window exists"; tests replace it. */
    static volatile BooleanSupplier windowExists = () -> MainFrame.get() != null;

    private static final int QUEUED = 0;
    private static final int STARTED = 1;
    private static final int CANCELLED = 2;

    private EdtRunner() {
    }

    /**
     * Runs c on Swing's event thread when OpenPnP's window exists and this is another thread;
     * otherwise here. If the event thread (or, headless, the lock) is not free within
     * {@code startTimeoutMs}, c never runs and this throws IllegalStateException; once c has
     * started, this waits for it. c's own exception is thrown as it is.
     */
    public static <T> T call(Callable<T> c, long startTimeoutMs) throws Exception {
        if (!windowExists.getAsBoolean()) {
            if (!HEADLESS_LOCK.tryLock(startTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(String.format(Locale.ROOT, "Another change to OpenPnP's parts and "
                        + "boards is still running (waited %.1f s): try again.", startTimeoutMs / 1000.0));
            }
            try {
                return c.call();
            }
            finally {
                HEADLESS_LOCK.unlock();
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return c.call();
        }
        Task<T> task = new Task<>(c);
        SwingUtilities.invokeLater(task);
        return task.await(startTimeoutMs);
    }

    private static final class Task<T> implements Runnable {
        private final Callable<T> callable;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private T result;
        private Throwable error;

        Task(Callable<T> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, STARTED)) {
                return; // the caller gave up before the event thread got here
            }
            started.countDown();
            try {
                result = callable.call();
            }
            catch (Throwable t) {
                error = t;
            }
            finally {
                done.countDown();
            }
        }

        T await(long startTimeoutMs) throws Exception {
            boolean interrupted = false;
            try {
                if (!started.await(startTimeoutMs, TimeUnit.MILLISECONDS) && state.compareAndSet(QUEUED, CANCELLED)) {
                    throw new IllegalStateException(String.format(Locale.ROOT, "OpenPnP's window is busy (it did "
                            + "not take the change within %.1f s): try again.", startTimeoutMs / 1000.0));
                }
            }
            catch (InterruptedException e) {
                if (state.compareAndSet(QUEUED, CANCELLED)) {
                    throw e;
                }
                interrupted = true; // started meanwhile: its outcome must still be reported
            }
            // Started: wait for the end whatever happens (the caller must know whether it ran).
            while (true) {
                try {
                    done.await();
                    break;
                }
                catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            if (error instanceof Error) {
                throw (Error) error;
            }
            if (error != null) {
                throw new IllegalStateException(error);
            }
            return result;
        }
    }
}
