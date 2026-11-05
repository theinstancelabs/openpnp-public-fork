package org.openpnp.instancelabs.api;

/**
 * BR-98 (collision-model-design.md §6): the bed model's routes. {@code GET /api/bed} reads it (no lock).
 */
final class BedEndpoints {
    private final ApiContext ctx;

    BedEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/bed", r -> ctx.bed.view());
    }
}
