package org.openpnp.instancelabs.api;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * BR-72: the one machine lock (P0, user 11-03: "I should be able to click that button and take it for myself"). The
 * holder is nobody, the user (every web UI window: {@value #UI_HEADER}: ui) or one agent (a token from
 * {@link #acquire}, sent as {@value #TOKEN_HEADER}). Pure state and rules: {@link LockEndpoints} stops the machine
 * around a take-over or a lapse and guards the writes with {@link #admit}.
 *
 * <p>A guarded write passes when nobody holds the lock (anyone: CI, the deployer and the tools of before; the UI's
 * first one makes the user the holder), when the user holds it and the request is the UI's, or when an agent holds it
 * and the request carries its token. Anything else is 409 {@code locked}; a token whose lock was taken, released or
 * lapsed answers {@code lock-lost}, one never issued {@code no-lock}. An agent's lease ends {@code ttlS} after its last
 * call ended, never while one runs; the user's never lapses (hands in the machine).</p>
 */
final class MachineLock {
    static final String UI_HEADER = "X-IL-Client";
    static final String TOKEN_HEADER = "X-IL-Lock";
    static final int DEFAULT_TTL_S = 300;
    static final int MIN_TTL_S = 30;
    static final int MAX_TTL_S = 1800;
    static final int MAX_WHAT = 200;
    /** Tokens remembered after their lock ended, so a late call learns how (lock-lost), not "never issued". */
    private static final int LOST_KEPT = 64;
    private static final Pattern WHO = Pattern.compile("[a-z][a-z0-9-]{0,19}");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    enum Kind {
        AGENT, USER
    }

    /** A holder. Mutable fields change under the lock's monitor, except {@link #revoked}. */
    static final class Holder {
        final Kind kind;
        final String who;
        final String label;
        final String thread;
        /** Null for the user. */
        final String token;
        final long sinceMs;
        final long ttlMs;
        String what;
        long lastActiveMs;
        int inFlight;
        /** Set when the lock is taken from it or lapses: its running batch sends nothing more. */
        volatile boolean revoked;
        /** Its work on the machine runs (a 202 task, a home, a hold), seen by the lease check (LockEndpoints). */
        boolean working;

        Holder(Kind kind, String who, String label, String what, String thread, String token, long now, long ttlMs) {
            this.kind = kind;
            this.who = who;
            this.label = label;
            this.what = what;
            this.thread = thread;
            this.token = token;
            this.sinceMs = now;
            this.lastActiveMs = now;
            this.ttlMs = ttlMs;
        }
    }

    /** One guarded call of the holder, counted until {@link MachineLock#end}. */
    static final class Activity {
        final Holder holder;

        Activity(Holder holder) {
            this.holder = holder;
        }
    }

    private static final class Lost {
        final String how;
        final String by;
        final long atMs;
        final String label;
        final String what;

        Lost(String how, String by, long atMs, String label, String what) {
            this.how = how;
            this.by = by;
            this.atMs = atMs;
            this.label = label;
            this.what = what;
        }
    }

    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private Holder holder;
    private Map<String, Object> takenFrom;
    /** A take-over is still stopping the agent's work: nobody's guarded write passes until it ends. */
    private boolean handover;
    private final Map<String, Lost> lost = new LinkedHashMap<String, Lost>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lost> eldest) {
            return size() > LOST_KEPT;
        }
    };

    MachineLock(LongSupplier clock) {
        this.clock = clock;
    }

    /** {@code GET /api/lock} and status {@code lock}. */
    synchronized Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("holder", holder == null ? null : holderView(holder));
        v.put("takenFrom", takenFrom == null ? null : new LinkedHashMap<>(takenFrom));
        v.put("handover", handover);
        return v;
    }

    private static Map<String, Object> holderView(Holder h) {
        return Views.map("kind", h.kind == Kind.USER ? "user" : "agent", "who", h.who, "label", h.label, "what", h.what,
                "thread", h.thread, "sinceMs", h.sinceMs, "lastActiveMs", h.lastActiveMs, "busy", h.inFlight > 0 || h.working,
                "expiresAtMs", h.kind == Kind.USER ? null : h.lastActiveMs + h.ttlMs);
    }

    /** The holder now, or null. */
    synchronized Holder current() {
        return holder;
    }

    // ------------------------------------------------------------------------------------------
    // Agents
    // ------------------------------------------------------------------------------------------

    synchronized Map<String, Object> acquire(String who, String label, String what, String thread, Integer ttlS)
            throws ApiException {
        return acquire(who, label, what, thread, ttlS, null);
    }

    /**
     * An agent takes the lock while nobody holds it; with its own token while it holds it, a renewal (the same token).
     * Never from anyone else: only the user takes over.
     */
    synchronized Map<String, Object> acquire(String who, String label, String what, String thread, Integer ttlS,
            String presentedToken) throws ApiException {
        if (who == null || !WHO.matcher(who).matches() || "user".equals(who)) {
            throw ApiException.badRequest("'who' must be the agent's role in lower case (process, backend, infra, ui, "
                    + "...); the user takes control from the web UI instead");
        }
        what = checkWhat(what, true);
        if (ttlS != null && (ttlS < MIN_TTL_S || ttlS > MAX_TTL_S)) {
            throw ApiException.badRequest("'ttlS' must be " + MIN_TTL_S + ".." + MAX_TTL_S + " s");
        }
        if (thread != null && thread.length() > 80) {
            throw ApiException.badRequest("'thread' is at most 80 characters");
        }
        long now = clock.getAsLong();
        if (holder != null && holder.kind == Kind.AGENT && !holder.revoked && holder.token.equals(presentedToken)) {
            holder.what = what;
            holder.lastActiveMs = now;
            return Views.map("token", holder.token, "lock", view());
        }
        if (holder != null) {
            throw locked(false);
        }
        byte[] b = new byte[18];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        String l = label == null || label.trim().isEmpty() ? Character.toUpperCase(who.charAt(0)) + who.substring(1)
                : label.trim();
        if (l.length() > 40) {
            throw ApiException.badRequest("'label' is at most 40 characters");
        }
        setHolder(new Holder(Kind.AGENT, who, l, what, thread, token, now,
                (ttlS == null ? DEFAULT_TTL_S : ttlS) * 1000L));
        return Views.map("token", token, "lock", view());
    }

    /** Renews an agent's lease while a tool computes, and updates what it does. */
    synchronized Map<String, Object> keepalive(String token, String what) throws ApiException {
        Holder h = holderOf(token);
        if (what != null) {
            h.what = checkWhat(what, false);
        }
        h.lastActiveMs = clock.getAsLong();
        return view();
    }

    /**
     * An agent's release (its token), or the user's Give back (the UI, while the user holds it). Nobody holds it after.
     */
    synchronized Map<String, Object> release(String token, boolean ui) throws ApiException {
        if (token != null) {
            Holder h = holderOf(token);
            end(h, "released", h.who);
            return view();
        }
        if (holder == null) {
            throw new ApiException(409, "no-lock", "nobody holds the machine");
        }
        if (handover) {
            throw takingOver();
        }
        if (!ui || holder.kind != Kind.USER) {
            throw locked(ui);
        }
        setHolder(null);
        return view();
    }

    // ------------------------------------------------------------------------------------------
    // The user
    // ------------------------------------------------------------------------------------------

    /**
     * Take control: the user holds the lock from now on. Returns the agent it was taken from (revoked: its token
     * answers lock-lost, its running batch stops), or null when nobody or the user held it.
     */
    synchronized Holder takeForUser() {
        long now = clock.getAsLong();
        Holder old = holder;
        if (old != null && old.kind == Kind.USER) {
            return null;
        }
        if (old == null) {
            setHolder(user(now));
            return null;
        }
        boolean lapsing = old.revoked; // its lease ran out: the lapse is stopping its work already
        if (!lapsing) {
            end(old, "taken", "user");
        }
        holder = user(now);
        takenFrom = Views.map("label", old.label, "who", old.who, "what", old.what, "atMs", now, "by", "user");
        handover = !lapsing;
        return lapsing ? null : old;
    }

    /** The take-over's stop has ended (confirmed or not): the user's controls work from now on. */
    synchronized void handoverDone() {
        handover = false;
    }

    private ApiException takingOver() {
        String from = takenFrom == null ? "the agent" : String.valueOf(takenFrom.get("label"));
        return new ApiException(409, "taking-over", "Take control is still stopping " + from + "'s work: a moment, "
                + "then try again").with("lock", view());
    }

    private Holder user(long now) {
        return new Holder(Kind.USER, "user", "User", "using the web UI", null, null, now, 0);
    }

    // ------------------------------------------------------------------------------------------
    // Guarded writes and the lease
    // ------------------------------------------------------------------------------------------

    /**
     * A guarded write: null when it passes uncounted (nobody holds the lock and the request is not the UI's), the
     * holder's activity to {@link #end} when it passes, else 409 locked, lock-lost or no-lock.
     */
    synchronized Activity admit(boolean ui, String token, String what) throws ApiException {
        long now = clock.getAsLong();
        Holder h;
        if (token != null) {
            if (holder == null || holder.kind != Kind.AGENT || holder.revoked || !holder.token.equals(token)) {
                throw notHolder(token, ui);
            }
            h = holder;
        }
        else if (handover) {
            throw takingOver();
        }
        else if (holder == null) {
            if (!ui) {
                return null;
            }
            h = user(now);
            h.what = checkWhat(what, false) == null ? h.what : what;
            setHolder(h);
        }
        else if (ui && holder.kind == Kind.USER) {
            h = holder;
        }
        else {
            throw locked(ui);
        }
        h.inFlight++;
        h.lastActiveMs = now;
        return new Activity(h);
    }

    synchronized void end(Activity a) {
        if (a != null) {
            a.holder.inFlight--;
            a.holder.lastActiveMs = clock.getAsLong();
        }
    }

    /** The agent whose lease ran out now (revoked; nobody holds the lock after), or null. */
    synchronized Holder expireDue() {
        Holder h = beginExpiry();
        if (h != null) {
            finishExpiry(h);
        }
        return h;
    }

    /**
     * The agent whose lease ran out now, revoked (its token answers lock-lost), or null. It still holds the lock, so
     * nobody else starts while its work is stopped; {@link #finishExpiry} then frees the lock.
     */
    synchronized Holder beginExpiry() {
        Holder h = holder;
        if (h == null || h.kind != Kind.AGENT || h.revoked || h.inFlight > 0
                || clock.getAsLong() < h.lastActiveMs + h.ttlMs) {
            return null;
        }
        h.revoked = true;
        lost.put(h.token, new Lost("expired", "lease", clock.getAsLong(), h.label, h.what));
        return h;
    }

    /** Nobody holds the lock after a lapse (unless the user took it meanwhile). */
    synchronized void finishExpiry(Holder h) {
        if (holder == h) {
            setHolder(null);
        }
    }

    /** Whether a call of this holder runs now. */
    synchronized boolean busy(Holder h) {
        return h.inFlight > 0;
    }

    /** The holder's work on the machine runs: its lease counts from when that ends (#19 review MAJOR 2). */
    synchronized void touch(Holder h) {
        h.lastActiveMs = clock.getAsLong();
    }

    /** Whether the holder's work on the machine runs now (shown as busy; renews the lease while true). */
    synchronized void markWorking(Holder h, boolean working) {
        h.working = working;
        if (working) {
            h.lastActiveMs = clock.getAsLong();
        }
    }

    /** The lock-lost answer for a holder whose lock ended (its batch saw it revoked). */
    synchronized ApiException lostError(Holder h) {
        return notHolder(h.token, false);
    }

    /** The holder of this token; lock-lost when its lock ended, else locked (someone holds it) or no-lock. */
    private Holder holderOf(String token) throws ApiException {
        if (token != null && holder != null && holder.kind == Kind.AGENT && !holder.revoked
                && holder.token.equals(token)) {
            return holder;
        }
        throw notHolder(token, false);
    }

    private ApiException notHolder(String token, boolean ui) {
        Lost l = token == null ? null : lost.get(token);
        if (l != null) {
            String when = hhmm(l.atMs);
            String why = "taken".equals(l.how) ? "the user took control at " + when
                    : "expired".equals(l.how) ? "the lease ran out at " + when + " (no call or keepalive in time)"
                            : "it was released at " + when;
            return new ApiException(409, "lock-lost", l.label + "'s lock ended: " + why + ": send nothing more")
                    .with("lock", view()).with("lost", Views.map("how", l.how, "by", l.by, "atMs", l.atMs, "what", l.what));
        }
        if (holder != null) {
            return locked(ui);
        }
        return new ApiException(409, "no-lock", "this lock token is not known (the lock ended, or OpenPnP restarted): "
                + "acquire the lock first (POST /api/lock/acquire)").with("lock", view());
    }

    private ApiException locked(boolean ui) {
        Holder h = holder;
        String reason;
        if (h.kind == Kind.USER) {
            reason = "The user has the machine (since " + hhmm(h.sinceMs) + "): wait until they give it back, or ask "
                    + "through the PM.";
        }
        else {
            reason = h.label + " has the machine (" + h.what + ", since " + hhmm(h.sinceMs) + "). "
                    + (ui ? "Take control to use it." : "Wait until nobody holds it (GET /api/lock).");
        }
        return new ApiException(409, "locked", reason).with("lock", view());
    }

    /** The holder's lock ends (taken, released, expired): its token is remembered as lost. */
    private void end(Holder h, String how, String by) {
        h.revoked = true;
        lost.put(h.token, new Lost(how, by, clock.getAsLong(), h.label, h.what));
        if (holder == h) {
            setHolder(null);
        }
    }

    /** A new holder; the last take-over is shown until the lock next changes hands. */
    private void setHolder(Holder h) {
        holder = h;
        takenFrom = null;
        handover = false;
    }

    private static String checkWhat(String what, boolean required) throws ApiException {
        if (what == null || what.trim().isEmpty()) {
            if (required) {
                throw ApiException.badRequest("'what' (what the agent does with the machine) is required");
            }
            return null;
        }
        if (what.length() > MAX_WHAT) {
            throw ApiException.badRequest("'what' is at most " + MAX_WHAT + " characters");
        }
        return what.trim();
    }

    private static String hhmm(long ms) {
        return HHMM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }
}
