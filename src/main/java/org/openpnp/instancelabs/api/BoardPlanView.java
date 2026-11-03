package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openpnp.instancelabs.board.BoardImporter;
import org.openpnp.instancelabs.dispense.DispensePlan;

/**
 * What a plan view adds for the board drawing, the path preview and the pad inspector (BOARD_IMPORT_DESIGN.md §4.5):
 * the {@code board} block (board → machine affine, size, fiducials, the art id fixed when the plan was made), the
 * {@code dot} size, per pad its name, part, roundness and the rule that chose its pad type, per dot its arrive and
 * end times from the plan's estimate.
 */
final class BoardPlanView {
    private BoardPlanView() {
    }

    @SuppressWarnings("unchecked")
    static void addTo(Map<String, Object> out, PlanStore.Entry e) {
        DispensePlan p = e.plan;
        out.put("board", board(p));
        out.put("dot", Views.map("diameterMm", e.dotDiameterMm, "source", e.dotDiameterMm == null ? "none"
                : "ladder"));
        List<Map<String, Object>> pads = (List<Map<String, Object>>) out.get("pads");
        for (int i = 0; pads != null && i < pads.size() && i < p.pads.size(); i++) {
            DispensePlan.PlannedPad x = p.pads.get(i);
            Map<String, Object> d = pads.get(i);
            d.put("padName", x.padName);
            d.put("partId", x.partId);
            d.put("roundness", x.roundness);
            d.put("typeRule", x.typeRule == null ? null : Views.map("kind", x.typeRule, "detail", x.typeRuleDetail,
                    "matched", x.typeRuleMatched, "sentence", BoardImporter.ruleSentence(x.typeRule,
                            x.typeRuleMatched, x.packageId)));
        }
        List<Map<String, Object>> dots = (List<Map<String, Object>>) out.get("dots");
        double[] ta = e.dotArriveS;
        double[] te = e.dotEndS;
        if (dots != null && ta != null && te != null && ta.length == dots.size() && te.length == dots.size()) {
            for (int i = 0; i < dots.size(); i++) {
                dots.get(i).put("ta", round2(ta[i]));
                dots.get(i).put("te", round2(te[i]));
            }
        }
    }

    /** Null for a plan without a board location. */
    static Map<String, Object> board(DispensePlan p) {
        if (p.boardToMachine == null) {
            return null;
        }
        List<Map<String, Object>> fids = new ArrayList<>();
        for (DispensePlan.BoardFiducial f : p.fiducials) {
            fids.add(Views.map("id", f.id, "partId", f.partId, "bx", f.bx, "by", f.by, "x", f.xMm, "y", f.yMm));
        }
        List<Double> m = new ArrayList<>();
        for (double v : p.boardToMachine) {
            m.add(v);
        }
        return Views.map("name", p.boardName, "file", p.boardFile, "widthMm", p.boardWidthMm, "heightMm",
                p.boardHeightMm, "side", "Top", "toMachine", m, "transformStatus", p.boardTransformStatus,
                "fiducials", fids, "art", p.boardArtId == null ? null : Views.map("id", p.boardArtId, "kind",
                        p.boardArtKind, "note", p.boardArtNote));
    }

    private static double round2(double s) {
        return Math.round(s * 100) / 100.0;
    }
}
