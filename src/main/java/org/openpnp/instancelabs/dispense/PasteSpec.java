package org.openpnp.instancelabs.dispense;

import java.util.List;

/**
 * The paste in the syringe (profile calibration design §1.7, §5.4). Dose records name the paste
 * ({@link #key}) and count only after the last "New paste loaded" press ({@link #loadedAtMs},
 * stamped by POST /api/paste; the browser's paste-life tick attests working life, not a change).
 */
public class PasteSpec {
    /** Identity recorded on dose records. */
    public String key = "gc50-t5";
    public String name = "Loctite GC50 SAC305 T5";
    public String lot;
    /** Engine-stamped: when this paste was loaded; null = never pressed. */
    public Long loadedAtMs;

    /** Problems with these values, added to {@link DispenseConfig#validate()}. */
    void validate(List<String> p) {
        if (key == null || !TipSpec.KEY.matcher(key).matches()) {
            p.add("paste.key must be lower-case letters, digits and '-' (max 48)");
        }
        if (name == null || name.trim().isEmpty()) {
            p.add("paste.name is required");
        }
    }
}
