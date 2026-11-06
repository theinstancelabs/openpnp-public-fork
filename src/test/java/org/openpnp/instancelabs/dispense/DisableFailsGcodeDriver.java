package org.openpnp.instancelabs.dispense;

import org.openpnp.machine.reference.driver.GcodeDriver;

/**
 * Tests only (BR-106): a GcodeDriver whose disable fails, once {@link #disableFails} is set, before it does anything:
 * its link stays open. GcodeDriver's own disable never fails so (each line is sent on its own, the link closes).
 */
public class DisableFailsGcodeDriver extends GcodeDriver {
    public volatile Exception disableFails;

    @Override
    public void setEnabled(boolean enabled) throws Exception {
        Exception e = disableFails;
        if (!enabled && e != null) {
            throw e;
        }
        super.setEnabled(enabled);
    }
}
