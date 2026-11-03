package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * Plans the real test board (~/Desktop/ftp.board.xml: 40 R_0603 + 40 LED_0603 + 3 fiducials) with
 * COPIES of the live packages.xml / parts.xml in a temp config dir. Skipped where those files do
 * not exist. Never writes to ~/.openpnp2.
 */
public class LiveBoardPlanTest {
    @Test
    public void ftpBoardPlans160Dots() throws Exception {
        File home = new File(System.getProperty("user.home"));
        File boardFile = new File(home, "Desktop/ftp.board.xml");
        File live = new File(home, ".openpnp2");
        assumeTrue(boardFile.exists() && new File(live, "packages.xml").exists() && new File(live, "parts.xml").exists());
        File dir = Files.createTempDirectory("dispense-live-copy").toFile();
        for (String f : new String[] {"packages.xml", "parts.xml"}) {
            Files.copy(new File(live, f).toPath(), new File(dir, f).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File boardCopy = new File(dir, "ftp.board.xml");
        Files.copy(boardFile.toPath(), boardCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Configuration.initialize(dir);
        Configuration.get().load();
        Board board = Configuration.get().getBoard(boardCopy);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, 150, 100, 5.5, 0));
        try (DispenseDb db = DispenseDb.inMemory()) {
            DispenseConfig cfg = GcodeStreamTest.toyConfig(); // bench needle frame, purge row on a probed blank
            cfg.minPadTypeStatus = "not-started"; // seeds only: this checks geometry, not readiness
            DispensePlan plan = TestTips.planner(cfg, new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), db.packageMappings()))
                    .planBoard(bl, 150, 100);
            assertEquals(160, plan.pads.size(), plan.summary());
            assertEquals(160, plan.dots.size(), plan.summary());
            assertEquals(0, plan.blockedPads().size());
            for (DispensePlan.PlannedPad p : plan.pads) {
                assertEquals("chip-0603", p.typeKey);
                assertEquals(PadGeometry.Source.PAD_TYPE, p.source, "live packages have no footprint pads for 0603");
            }
            assertTrue(plan.pathLengthMm < plan.pathLengthNearestNeighbourMm);
            GcodeProgram prog = GcodeStreamBuilder.forPlan(cfg, new ToyMapper(150, 100, 31.5, 0), plan,
                    DispenseService.purgeSpecs(cfg), false, false, 0L);
            assertTrue(GcodeStreamValidator.validate(prog, cfg, "B").isEmpty());
            assertEquals(160, prog.dotCount);
            assertEquals(8, prog.purgeDotCount);
            System.out.println(String.format(java.util.Locale.ROOT,
                    "LiveBoardPlanTest: estimate (moves with acceleration, settles, purge) %.1f s for %d + %d purge "
                    + "dots, %d lines: %.0f DPH overall", prog.estimatedSeconds, prog.dotCount, prog.purgeDotCount,
                    prog.lines.size(), prog.dotCount / prog.estimatedSeconds * 3600));
            System.out.println("LiveBoardPlanTest: " + plan.summary() + String.format(java.util.Locale.ROOT,
                    ", total screw turn %.0f deg = %.1f revolutions", plan.totalRotationDeg(),
                    plan.totalRotationDeg() * cfg.bStepsPerDeg / cfg.bStepsPerRev));
        }
    }
}
