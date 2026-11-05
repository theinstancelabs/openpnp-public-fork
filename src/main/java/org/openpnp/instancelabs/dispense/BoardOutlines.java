package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** BR-81 (stub for the red run). */
public final class BoardOutlines {
    public static final class Place {
        public String place, kind, board, text;
        public double x, y;
    }

    public static BoardOutlines of(DispenseConfig c) {
        return new BoardOutlines();
    }

    public String on(double x, double y, double margin) {
        return null;
    }

    public String on(double x0, double y0, double x1, double y1, double margin) {
        return null;
    }

    public List<Place> placesOnBoards() {
        return new ArrayList<>();
    }

    public List<Map<String, Object>> view() {
        return new ArrayList<>();
    }
}
