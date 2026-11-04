package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** BR-72: the machine lock's rules (who may write, acquire, take, release, lapse), on a test clock. */
public class MachineLockTest {
    final AtomicLong now = new AtomicLong(1_000_000L);
    final MachineLock lock = new MachineLock(now::get);

    String acquire(String who, String what) throws ApiException {
        return (String) lock.acquire(who, null, what, "mcp:test", null).get("token");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> holder() {
        return (Map<String, Object>) lock.view().get("holder");
    }

    static ApiException refused(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ApiException.class, e);
    }

    @Test
    public void nobodyHoldsItAnonymousWritesPassTheUisFirstWriteMakesTheUserTheHolder() throws Exception {
        assertNull(holder());
        assertNull(lock.view().get("takenFrom"));
        // CI, the deployer, curl: as before the lock
        assertNull(lock.admit(false, null, "home"));
        assertNull(holder());
        // the UI's first guarded write: the user holds it from now on (no stop: nobody was working)
        MachineLock.Activity a = lock.admit(true, null, "Home");
        assertNotNull(a);
        Map<String, Object> h = holder();
        assertEquals("user", h.get("kind"));
        assertEquals("user", h.get("who"));
        assertEquals(true, h.get("busy"));
        assertNull(h.get("expiresAtMs"), "the user's hold never lapses");
        now.addAndGet(5000);
        lock.end(a);
        assertEquals(false, holder().get("busy"));
        assertEquals(now.get(), holder().get("lastActiveMs"));
        // a second window is the user too
        lock.end(lock.admit(true, null, "Jog"));
        // anonymous writes and agents are refused now
        ApiException e = refused(() -> lock.admit(false, null, "home"));
        assertEquals(409, e.status);
        assertEquals("locked", e.code);
        assertNotNull(e.details.get("lock"));
        e = refused(() -> lock.acquire("process", null, "27G quarter map", null, null));
        assertEquals("locked", e.code);
        assertTrue(e.getMessage().contains("The user has the machine"), e.getMessage());
    }

    @Test
    public void anAgentHoldsItOnlyItsTokenWrites() throws Exception {
        String token = acquire("process", "27G quarter map, 14 dots");
        assertNotNull(token);
        assertTrue(token.length() >= 22, token);
        Map<String, Object> h = holder();
        assertEquals("agent", h.get("kind"));
        assertEquals("process", h.get("who"));
        assertEquals("Process", h.get("label"));
        assertEquals("27G quarter map, 14 dots", h.get("what"));
        assertEquals("mcp:test", h.get("thread"));
        assertEquals(now.get(), h.get("sinceMs"));
        assertEquals(now.get() + 300_000L, h.get("expiresAtMs"), "default lease 300 s");
        assertFalse(h.containsKey("token"), "the token is never shown");
        // the UI and anonymous writes: 409 locked, with the reason the UI shows
        ApiException e = refused(() -> lock.admit(true, null, "Home"));
        assertEquals("locked", e.code);
        assertTrue(e.getMessage().startsWith("Process has the machine (27G quarter map, 14 dots, since "),
                e.getMessage());
        assertTrue(e.getMessage().endsWith("Take control to use it."), e.getMessage());
        assertEquals("locked", refused(() -> lock.admit(false, null, "home")).code);
        assertEquals("locked", refused(() -> lock.acquire("infra", null, "deploy", null, null)).code);
        // a token nobody knows while someone holds it: locked too
        assertEquals("locked", refused(() -> lock.admit(false, "bogus", "home")).code);
        // its own token: counted while in flight, renews the lease when it ends
        MachineLock.Activity a = lock.admit(false, token, "gcode");
        assertEquals(true, holder().get("busy"));
        now.addAndGet(400_000L); // a long call: never lapses while it runs
        assertNull(lock.expireDue());
        lock.end(a);
        assertEquals(now.get() + 300_000L, holder().get("expiresAtMs"));
        // keepalive renews and updates what
        now.addAndGet(1000);
        lock.keepalive(token, "27G quarter map, dot 7 of 14");
        assertEquals("27G quarter map, dot 7 of 14", holder().get("what"));
        assertEquals(now.get() + 300_000L, holder().get("expiresAtMs"));
        // acquire again with its own token: a renewal, the same token
        assertEquals(token, lock.acquire("process", null, "next run", null, null, token).get("token"));
    }

    @Test
    public void takingFromAnAgentRevokesItsToken() throws Exception {
        String token = acquire("process", "feed step check");
        MachineLock.Holder held = lock.current();
        now.addAndGet(60_000L);
        MachineLock.Holder old = lock.takeForUser();
        assertSame(held, old);
        assertTrue(old.revoked, "its running batch sends nothing more");
        Map<String, Object> h = holder();
        assertEquals("user", h.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) lock.view().get("takenFrom");
        assertEquals("Process", from.get("label"));
        assertEquals("process", from.get("who"));
        assertEquals("feed step check", from.get("what"));
        assertEquals(now.get(), from.get("atMs"));
        assertEquals("user", from.get("by"));
        // every later call of the old holder: lock-lost, by whom and when
        ApiException e = refused(() -> lock.admit(false, token, "gcode"));
        assertEquals(409, e.status);
        assertEquals("lock-lost", e.code);
        @SuppressWarnings("unchecked")
        Map<String, Object> lost = (Map<String, Object>) e.details.get("lost");
        assertEquals("taken", lost.get("how"));
        assertEquals("user", lost.get("by"));
        assertEquals(now.get(), lost.get("atMs"));
        assertTrue(e.getMessage().contains("send nothing more"), e.getMessage());
        assertEquals("lock-lost", refused(() -> lock.keepalive(token, null)).code);
        assertEquals("lock-lost", refused(() -> lock.release(token, false)).code);
        assertEquals("locked", refused(() -> lock.acquire("process", null, "again", null, null)).code);
        // the user's own writes pass; a second take is a no-op
        lock.end(lock.admit(true, null, "Start"));
        assertNull(lock.takeForUser());
        // Give back: nobody, and takenFrom is cleared when the lock changes hands
        lock.release(null, true);
        assertNull(holder());
        assertNull(lock.view().get("takenFrom"));
        // the old token stays lost (not "no-lock") after the lock changed hands again
        assertEquals("lock-lost", refused(() -> lock.admit(false, token, "gcode")).code);
    }

    @Test
    public void takingWhenNobodyHoldsItMakesTheUserTheHolder() throws Exception {
        assertNull(lock.takeForUser());
        assertEquals("user", holder().get("kind"));
        assertNull(lock.view().get("takenFrom"));
    }

    @Test
    public void releaseAndLapse() throws Exception {
        String token = acquire("process", "dots");
        lock.release(token, false);
        assertNull(holder());
        ApiException e = refused(() -> lock.admit(false, token, "gcode"));
        assertEquals("lock-lost", e.code);
        assertTrue(e.details.get("lost").toString().contains("released"), e.details.toString());
        // a lease lapses ttlS after the last call ended
        String t2 = (String) lock.acquire("backend", "Backend", "BR-72 check", null, 30).get("token");
        assertNotEquals(token, t2);
        now.addAndGet(29_999L);
        assertNull(lock.expireDue());
        now.addAndGet(2);
        MachineLock.Holder lapsed = lock.expireDue();
        assertNotNull(lapsed);
        assertTrue(lapsed.revoked);
        assertEquals("backend", lapsed.who);
        assertNull(holder());
        assertNull(lock.view().get("takenFrom"), "a lapse is not a take-over");
        e = refused(() -> lock.admit(false, t2, "gcode"));
        assertEquals("lock-lost", e.code);
        assertTrue(e.details.get("lost").toString().contains("expired"), e.details.toString());
        // the user's hold never lapses
        lock.takeForUser();
        now.addAndGet(48L * 3600_000L);
        assertNull(lock.expireDue());
        assertEquals("user", holder().get("kind"));
    }

    @Test
    public void releaseRules() throws Exception {
        // nobody: nothing to release
        assertEquals("no-lock", refused(() -> lock.release(null, true)).code);
        String token = acquire("process", "dots");
        // the UI cannot give back an agent's lock: that is Take control
        assertEquals("locked", refused(() -> lock.release(null, true)).code);
        assertEquals("locked", refused(() -> lock.release(null, false)).code);
        // an unknown token while free: no-lock (an OpenPnP restart drops every lease)
        lock.release(token, false);
        ApiException e = refused(() -> lock.admit(false, "never-issued", "gcode"));
        assertEquals("no-lock", e.code);
        assertTrue(e.getMessage().contains("acquire"), e.getMessage());
    }

    @Test
    public void requestsAreChecked() {
        assertEquals("bad-request", refused(() -> lock.acquire("Process", null, "x", null, null)).code);
        assertEquals("bad-request", refused(() -> lock.acquire("user", null, "x", null, null)).code,
                "the user takes control, never acquires");
        assertEquals("bad-request", refused(() -> lock.acquire("process", null, "", null, null)).code);
        assertEquals("bad-request", refused(() -> lock.acquire("process", null, null, null, null)).code);
        assertEquals("bad-request", refused(() -> lock.acquire("process", null, new String(new char[201]).replace(
                '\0', 'x'), null, null)).code);
        assertEquals("bad-request", refused(() -> lock.acquire("process", null, "x", null, 29)).code);
        assertEquals("bad-request", refused(() -> lock.acquire("process", null, "x", null, 1801)).code);
    }
}
