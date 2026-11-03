package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.openpnp.spi.Machine;

/** Home when needed: the reasons, their order, and the home duration estimate (fake clock). */
public class HomeTrackerTest {
    long now = 1_000_000;
    boolean enabled = true;

    Machine machine() {
        return (Machine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Machine.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isEnabled")) {
                        return enabled;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    HomeTracker tracker(boolean homedAtStart) {
        return new HomeTracker(() -> now, homedAtStart);
    }

    static final String W = DispenseConfig.HOME_WHEN_NEEDED;

    @Test
    public void notHomedAndAReconnectAreReasons() {
        HomeTracker t = tracker(false);
        Machine m = machine();
        HomeTracker.Plan p = t.plan(true, false, W);
        assertTrue(p.needed);
        assertEquals("not-homed", p.whyCode);
        assertEquals("the machine is not homed", p.why);
        assertEquals(25, p.estimateS, 1e-9, "no home measured yet: the 25 s guess");
        now += 10;
        t.machineEnabled(m);
        now += 10;
        t.machineHomed(m, true);
        p = t.plan(true, true, W);
        assertFalse(p.needed);
        assertEquals("homed", p.whyCode);
        assertEquals(Long.valueOf(now), p.homedAtMs);
        assertEquals(Long.valueOf(now), p.sinceMs);
        // OpenPnP reconnects with a disable and an enable: "stopped", there is no separate code
        now += 1000;
        t.machineDisabled(m, "stop");
        now += 1000;
        t.machineEnabled(m);
        p = t.plan(true, true, W);
        assertTrue(p.needed);
        assertEquals("stopped", p.whyCode);
        assertEquals("stopped or reconnected since the last home", p.why);
        assertEquals(Long.valueOf(now), p.sinceMs, "the later of the disable and the enable");
        assertEquals(Long.valueOf(now), t.getConnectedAtMs(), "I11 reads the connect time here");
    }

    @Test
    public void aStopDuringTheHomeLeavesItUnconfirmedUntilACleanHome() {
        HomeTracker t = tracker(false);
        Machine m = machine();
        t.machineEnabled(m);
        now += 10;
        t.homeStarted();
        now += 1000;
        t.machineDisabled(m, "STOP pressed");
        long stop = now;
        now += 1000;
        t.machineHomed(m, true);
        HomeTracker.Plan p = t.plan(true, true, W);
        assertTrue(p.needed);
        assertEquals("home-unconfirmed", p.whyCode);
        assertEquals("a stop came during the last home", p.why);
        assertEquals(Long.valueOf(stop), p.sinceMs);
        assertTrue(t.isHomeUnconfirmed());
        assertNull(t.getHomeStartedAtMs(), "cleared at machineHomed(true)");
        assertNull(t.getLastHomeMs(), "an unconfirmed home is not a duration");
        // a clean engine home clears it, and measures the duration
        now += 5000;
        t.machineEnabled(m);
        now += 10;
        t.homeStarted();
        now += 18_000;
        t.machineHomed(m, true);
        p = t.plan(true, true, W);
        assertFalse(p.needed, p.whyCode);
        assertEquals(18.0, p.estimateS, 1e-9, "measured");
    }

    /**
     * Review 1 (machine safety): a home a STOP ended threw in OpenPnP and never reported homed. Its start is
     * forgotten ({@link HomeTracker#homeEnded}), so the next clean home from OpenPnP's own Home button is
     * confirmed, not "a stop came during the last home".
     */
    @Test
    public void aStoppedHomeLeavesNoStartForTheNextOne() {
        HomeTracker t = tracker(false);
        Machine m = machine();
        t.machineEnabled(m);
        now += 10;
        t.homeStarted();
        now += 1000;
        t.machineDisabled(m, "STOP pressed"); // OpenPnP's home throws: no machineHomed(true)
        t.homeEnded();
        assertNull(t.getHomeStartedAtMs());
        now += 5000;
        t.machineEnabled(m);
        now += 100;
        t.machineHomed(m, true); // OpenPnP's Home button: no homeStarted
        HomeTracker.Plan p = t.plan(true, true, W);
        assertFalse(p.needed, p.whyCode);
        assertEquals("homed", p.whyCode);
        assertFalse(t.isHomeUnconfirmed());
        assertNull(t.getLastHomeMs(), "no known start: no duration");
    }

    @Test
    public void aHomeWhileDisabledIsUnconfirmed() {
        HomeTracker t = tracker(false);
        enabled = false;
        t.machineHomed(machine(), true);
        assertEquals("home-unconfirmed", t.plan(false, true, W).whyCode);
    }

    @Test
    public void anOpenPnpHomeAfterAStopIsEnough() {
        HomeTracker t = tracker(true);
        Machine m = machine();
        now += 100;
        t.machineDisabled(m, "STOP pressed");
        now += 100;
        t.machineEnabled(m);
        assertEquals("stopped", t.plan(true, false, W).whyCode);
        now += 100;
        t.machineHomed(m, true); // OpenPnP's Home button: no homeStarted, machine enabled
        HomeTracker.Plan p = t.plan(true, true, W);
        assertFalse(p.needed, p.whyCode);
        assertFalse(t.isHomeUnconfirmed());
        assertEquals(25, p.estimateS, 1e-9, "no known start: no duration");
    }

    @Test
    public void homedAtServiceStartHasNoHomeTimeAndNeedsNoHome() {
        HomeTracker t = tracker(true);
        HomeTracker.Plan p = t.plan(true, true, W);
        assertFalse(p.needed);
        assertEquals("homed", p.whyCode);
        assertNull(t.getLastHomeAtMs(), "the home before the service started has no known time");
        assertNull(p.homedAtMs);
        assertTrue(t.isHomedAtStart());
        // not homed any more (a STOP's recover un-homes): needed
        assertEquals("not-homed", t.plan(true, false, W).whyCode);
    }

    @Test
    public void alwaysHomesAndComesFirst() {
        HomeTracker t = tracker(true);
        t.machineHomed(machine(), true);
        HomeTracker.Plan p = t.plan(true, true, DispenseConfig.HOME_ALWAYS);
        assertTrue(p.needed);
        assertEquals("always", p.whyCode);
        assertEquals("the setting is: always home first", p.why);
        assertNull(p.sinceMs);
        assertEquals(DispenseConfig.HOME_ALWAYS, p.policy);
        t.machineDisabled(machine(), "x");
        now += 1;
        assertEquals("always", t.plan(true, false, DispenseConfig.HOME_ALWAYS).whyCode);
        t.machineHomed(machine(), false); // un-homing is no home
        assertNull(t.getHomeStartedAtMs());
    }

    @Test
    public void theZCheckIsKept() {
        HomeTracker t = tracker(false);
        ZCheck.Result r = new ZCheck.Result();
        r.pass = true;
        t.setZCheck(r);
        assertTrue(t.getZCheck().pass);
    }

    /** The Z frame of data measured now: the service start until a home is seen, then the last home. */
    @Test
    public void theZFrameIsTheLastHome() {
        HomeTracker t = tracker(true);
        long start = now;
        now += 500;
        assertEquals(start, t.frameHomeAtMs());
        t.machineHomed(machine(), true);
        assertEquals(start + 500, t.frameHomeAtMs());
        now += 500;
        t.machineHomed(machine(), true);
        assertEquals(start + 1000, t.frameHomeAtMs());
    }
}
