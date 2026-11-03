package org.openpnp.instancelabs.dispense;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal of the engine that carries the API's status and code (tip setups design §22.2-22.4):
 * {@code tip-changed}, {@code pad-changed}, {@code needle-changed}, {@code setup-mismatch},
 * {@code another-needle}, {@code correction}, {@code too-many}, {@code disk}, {@code busy},
 * {@code not-an-image}, {@code status-evidence}, {@code refused}. The API answers it as
 * {@code {error: code, reason: message, ...details}} with its status; where it is not mapped it
 * reads as any IllegalStateException of the engine (409 refused).
 */
public class EngineRefusal extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /** The HTTP status the API answers (400, 409, 413). */
    public final int status;
    /** Short machine-readable slug, e.g. "tip-changed". */
    public final String code;
    /** Merged into the answer's body (e.g. the recorded needle change of a refused mount). */
    public final Map<String, Object> details = new LinkedHashMap<>();

    /** A refusal answered with this status and code, its message the plain-language reason. */
    public EngineRefusal(int status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
    }

    /** Adds a field to the answer's body and returns this refusal. */
    public EngineRefusal with(String key, Object value) {
        details.put(key, value);
        return this;
    }
}
