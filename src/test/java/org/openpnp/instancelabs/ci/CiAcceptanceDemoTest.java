package org.openpnp.instancelabs.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * INF-01 acceptance only (never merged): a deliberately failing unit test must block the PR; the same PR fixed goes
 * green.
 */
public class CiAcceptanceDemoTest {
    @Test
    public void failsOnPurpose() {
        assertEquals(2, 1 + 2, "deliberate failure for the INF-01 acceptance PR");
    }
}
