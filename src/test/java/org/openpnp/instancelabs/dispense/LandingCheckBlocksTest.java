package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.vision.Dot;

/**
 * Tip setups (§22.3, §22.8): the engine side of the landing check. After a needle change only the landing check's
 * dose blocks may lay test dots: they purge at their own spots in the band (no purge-row spot), look at the request's
 * frames, keep every new blob once (overlapping frames see some twice) and give one result photo per block as JPEG
 * bytes. Once the landing is saved, every dot is laid where the tip lands: N2 goes to the spot minus the landing.
 */
public class LandingCheckBlocksTest {
    /** Where the fake top camera finds new paste (machine mm), as a needle landing 0.8, -0.3 mm off would lay it. */
    static final class BandImager implements DotImager {
        final List<double[]> blobs = new ArrayList<>();
        static final int W = 640, H = 480;
        static final double MM_PER_PX = 0.02;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double x, double y) {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(0, 25, 206)); // the bench blank
            g.fillRect(0, 0, W, H);
            g.dispose();
            return new Frame(img, MM_PER_PX, MM_PER_PX, x, y);
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            List<Dot> l = new ArrayList<>();
            for (double[] b : blobs) {
                double dx = b[0] - after.xMm, dy = b[1] - after.yMm;
                if (Math.abs(dx) < W * MM_PER_PX / 2 && Math.abs(dy) < H * MM_PER_PX / 2) {
                    l.add(new Dot(dx, dy, 0.55, Math.PI * 0.55 * 0.55 / 4, 0.9));
                }
            }
            return l;
        }

        @Override
        public double[] fieldOfViewMm() {
            return new double[] {W * MM_PER_PX, H * MM_PER_PX};
        }
    }

    static DoseBlocksRequest ladder(DispenseService svc, List<double[]> xy) {
        DoseBlocksRequest req = new DoseBlocksRequest();
        req.kind = DoseBlocksRequest.LADDER;
        req.parkAfter = false;
        DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(800, "half", "blank");
        b.xy.addAll(xy);
        req.blocks.add(b);
        DispenseConfig c = svc.getConfig();
        req.forTip = c.tip.key;
        req.needleSinceMs = c.needleChangedAtMs;
        req.stopEpoch = svc.stopEpoch();
        return req;
    }

    static List<double[]> row(double x0, double y, int n) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new double[] {x0 + i, y});
        }
        return l;
    }

    private static final Pattern X = Pattern.compile("\\bX(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern Y = Pattern.compile("\\bY(-?\\d+(?:\\.\\d+)?)");

    /**
     * Whether the head stood at these raw X/Y after a streamed move (moves name only the axes that change; the
     * driver's backlash offsets stay well under 0.2 mm).
     */
    static boolean movesTo(List<String> sent, RawPoint p) {
        double x = Double.NaN, y = Double.NaN;
        for (String l : sent) {
            if (!l.startsWith("G0 ") && !l.startsWith("G1 ")) {
                continue;
            }
            Matcher mx = X.matcher(l), my = Y.matcher(l);
            x = mx.find() ? Double.parseDouble(mx.group(1)) : x;
            y = my.find() ? Double.parseDouble(my.group(1)) : y;
            if (Math.abs(x - p.x) < 0.2 && Math.abs(y - p.y) < 0.2) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theLandingCheckLaysInItsBandAndTheSavedLandingMovesEveryDot() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            BandImager im = new BandImager();
            svc.setDotImager(im);
            svc.recordNeedleChange("a new 22G", "tester");
            f.frame(-2.59);
            assertFalse(svc.isNeedleXyChecked());
            // a ladder is refused until the landing is checked
            DoseBlocksResult refused = svc.runDoseBlocks(ladder(svc, row(98, 40, 4))).get(60, TimeUnit.SECONDS);
            assertFalse(refused.ok);
            assertTrue(refused.message.contains("where it lands has not been checked: check where it lands (Setup › "
                    + "Dispenser & tip)"), refused.message);
            // the landing check: its purge in the band, two overlapping frames
            List<double[]> spots = row(98, 40, 8);
            for (double[] s : spots) {
                im.blobs.add(new double[] {s[0] + 0.8, s[1] - 0.3});
            }
            DoseBlocksRequest req = ladder(svc, spots);
            req.landingCheck = true;
            req.purgeXy = row(98, 46, 8);
            req.lookXy = new ArrayList<>();
            req.lookXy.add(new double[] {100, 40});
            req.lookXy.add(new double[] {106, 40});
            int used = svc.getConfig().purgeRow.usedSpots;
            DispenseConfig c = svc.getConfig();
            OpenPnpRawMapper mapper = new OpenPnpRawMapper(f.sim.machine, f.sim.n2, c.bAxisName);
            f.sim.server.mark();
            DoseBlocksResult r = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertTrue(r.ok, r.message);
            List<String> sent = f.sim.server.sinceMark();
            assertEquals(used, svc.getConfig().purgeRow.usedSpots, "no purge-row spot");
            assertTrue(movesTo(sent, mapper.toRaw(98, 46, 20)), "a purge dot at its band spot");
            assertTrue(movesTo(sent, mapper.toRaw(105, 40, 20)), "no landing yet: N2 over the spot itself");
            assertEquals(2, r.frames / 2, "two frames before and two after");
            assertEquals(8, r.newBlobs.size(), "each blob once: " + r.newBlobs.size());
            for (double[] b : im.blobs) {
                assertTrue(r.newBlobs.stream().anyMatch(x -> Math.hypot(x.x - b[0], x.y - b[1]) < 1e-9));
            }
            assertEquals(1, r.photos.size(), "one result photo per block");
            DoseBlocksResult.BlockPhoto p = r.photos.get(0);
            assertEquals(800, p.doseSteps);
            assertTrue(p.jpeg.length > 100 && (p.jpeg[0] & 0xff) == 0xff && (p.jpeg[1] & 0xff) == 0xd8, "JPEG bytes");
            assertTrue(p.widthPx <= PhotoStore.RESULT_MAX_WIDTH_PX);
            assertEquals(BandImager.MM_PER_PX, p.mmPerPx, 1e-9);
            // the step says which setup and needle laid its dots: its photos are theirs whatever is mounted later
            assertEquals(Tip.SEED_KEY, r.setupKey);
            assertEquals(svc.getConfig().needleChangedAtMs, r.needleSinceMs);
            // the landing saved: every dot of every step is laid where the tip lands
            f.land(0.8, -0.3);
            assertTrue(svc.isNeedleXyChecked());
            im.blobs.clear();
            im.blobs.add(new double[] {110, 40});
            f.sim.server.mark();
            DoseBlocksResult after = svc.runDoseBlocks(ladder(svc, row(110, 40, 1))).get(120, TimeUnit.SECONDS);
            assertTrue(after.ok, after.message);
            sent = f.sim.server.sinceMark();
            assertTrue(movesTo(sent, mapper.toRaw(110 - 0.8, 40 + 0.3, 20)), "N2 at the spot minus the landing");
            assertFalse(movesTo(sent, mapper.toRaw(110, 40, 20)));
            assertEquals(Tip.SEED_KEY, svc.getConfig().needleLanding == null ? null : svc.getConfig().tip.key);
        }
    }
}
