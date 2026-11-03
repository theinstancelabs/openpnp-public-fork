package org.openpnp.instancelabs.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * INF-01 acceptance only (never merged): a deliberately failing unit test must block the PR; the same PR fixed goes
 * green.
 */
public class CiAcceptanceDemoTest {
    @Test
    public void passesOnceFixed() {
        assertEquals(3, 1 + 2, "fixed: the INF-01 acceptance PR goes green");
    }
}
