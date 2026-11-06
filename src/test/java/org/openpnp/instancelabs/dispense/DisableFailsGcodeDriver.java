package org.openpnp.instancelabs.dispense;

import org.openpnp.machine.reference.driver.GcodeDriver;

/**
 * Tests only (BR-106): a GcodeDriver whose disable fails, once {@link #disableFails} is set, before it does anything:
 * its link stays open. GcodeDriver's own disable never fails so (each line is sent on its own, the link closes).
 */
public class DisableFailsGcodeDriver extends GcodeDriver {
    /** What its disable throws: an Exception or an Error. */
    public volatile Throwable disableFails;

    @Override
    public void setEnabled(boolean enabled) throws Exception {
        Throwable t = disableFails;
        if (!enabled && t instanceof Error) {
            throw (Error) t;
        }
        if (!enabled && t != null) {
            throw (Exception) t;
        }
        super.setEnabled(enabled);
    }
}
