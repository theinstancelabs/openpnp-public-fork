package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openpnp.instancelabs.board.BoardArt;
import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

/**
 * What GET /api/boards adds per board (BOARD_IMPORT_DESIGN.md §3.8): size, fiducials, the drawing ({@code art}: kicad
 * or fallback, with a note when the board changed since its KiCad drawing) and the import record. Called where
 * OpenPnP's board list is read (inside the boards() EdtRunner call).
 */
final class BoardListView {
    private BoardListView() {
    }

    static void addTo(Map<String, Object> entry, Board b) {
        if (b == null) {
            return;
        }
        Location d = b.getDimensions() == null ? null : b.getDimensions().convertToUnits(LengthUnit.Millimeters);
        entry.put("widthMm", d == null ? null : d.getX());
        entry.put("heightMm", d == null ? null : d.getY());
        List<Map<String, Object>> fids = new ArrayList<>();
        for (Placement p : b.getPlacements()) {
            if (p.getType() == Placement.Type.Fiducial && p.getSide() == Side.Top && p.isEnabled()) {
                Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
                fids.add(Views.map("id", p.getId(), "bx", l.getX(), "by", l.getY()));
            }
        }
        entry.put("fiducials", fids);
        BoardArtStore.ArtRef art;
        BoardArtStore.Imported imp;
        try {
            BoardArtStore store = BoardArtStore.get();
            art = store.refFor(b);
            imp = BoardArt.KIND_KICAD.equals(art.kind) ? store.imported(b) : null;
        }
        catch (RuntimeException e) {
            Logger.warn(e, "no drawing for board {}", b.getFile()); // one odd board must not break the list
            art = null;
            imp = null;
        }
        entry.put("origin", imp == null ? null : imp.origin);
        entry.put("art", art == null ? null : Views.map("id", art.id, "kind", art.kind, "note", art.note));
        entry.put("imported", imp == null ? null : Views.map("slug", imp.slug, "name", imp.name, "atMs", imp.atMs,
                "sourceName", imp.sourceName, "kicadVersion", imp.kicadVersion, "how", imp.how, "board", imp.board));
    }
}
