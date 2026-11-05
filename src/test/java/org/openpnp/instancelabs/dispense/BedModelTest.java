package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** BR-98: the collision model's loader (collision-model-design.md §2) and the see-saw maps (U2). */
public class BedModelTest {
    static final double HX = 124.178, HY = 183.91; // head: N1's tip at (100, 120), the needle's at (149.469, 107.984)

    @Test
    public void theFixtureLoads() throws Exception {
        BedModel m = BedFixture.model();
        assertEquals(1, m.version);
        assertEquals(2.0, m.zMarginMm, 0);
        assertEquals(0.25, m.contactDepthMm, 0);
        assertEquals(6, m.obstacles().size(), "the bed and five objects");
        assertEquals(BedModel.BED_ID, m.obstacles().get(0).id);
        assertEquals(3, m.tools().size());
        assertTrue(m.object("blank-black").blocksEveryHeight(), "added, not measured");
        assertTrue(!m.object("blank-front").blocksEveryHeight());
        // the fit, worked by hand (least squares through four points)
        assertEquals(BedFixture.rc(100, 120), m.rc(100, 120), 1e-9);
        assertEquals(-2.89, m.rc(300, 300), 1e-9);
        // a circle is the circumscribed 64-gon: every vertex at r / cos(pi/64)
        BedModel.Poly c = m.object("height-switch").poly;
        assertEquals(64, c.size());
        assertEquals(5 / Math.cos(Math.PI / 64), Math.hypot(c.x(0) - 65, c.y(0) - 25), 1e-9);
    }

    /** A file the loaders refuse: what is wrong, how the fixture's text becomes it, words of the refusal. */
    static final class Bad {
        final String what, says;
        final UnaryOperator<String> file;

        /** A change to the fixture's tree. */
        Bad(String what, Consumer<JsonObject> change, String says) {
            this(what, says, t -> {
                JsonObject j = new JsonParser().parse(t).getAsJsonObject();
                change.accept(j);
                return j.toString();
            });
        }

        private Bad(String what, String says, UnaryOperator<String> file) {
            this.what = what;
            this.says = says;
            this.file = file;
        }

        /** An edit of the fixture's text, for what a tree cannot hold (comments, quotes). */
        static Bad text(String what, UnaryOperator<String> edit, String says) {
            return new Bad(what, says, edit);
        }
    }

    /**
     * Every file the loaders refuse, the first problem in words, in one list so lumen-sim's loader can mirror it
     * (Infra): the model's rules, every string an exact JSON string (no trimming, no number taken as text) and null
     * nowhere, a key given a value or left out (design §2 "Strings and null", 11-05), each tip's layer, the times, and
     * strict JSON.
     */
    static final List<Bad> BAD_FILES = Arrays.asList(
            new Bad("schema 2", j -> j.addProperty("schema", 2), "schema"),
            new Bad("no zMarginMm", j -> j.remove("zMarginMm"), "zMarginMm"),
            new Bad("a concave polygon", j -> obj(j, 3).add("outline", outline("polygon",
                    "[[300,130],[460,130],[380,180],[460,270],[300,270]]")), "convex"),
            new Bad("a clockwise polygon", j -> obj(j, 3).add("outline", outline("polygon",
                    "[[462.2,276.8],[461.0,127.8],[304.1,129.7],[305.3,278.7]]")), "counter-clockwise"),
            new Bad("a box with x0 > x1", j -> obj(j, 0).add("outline", outline("box", "[30,95,10,115]")), "x0 < x1"),
            new Bad("a circle of radius 0", j -> obj(j, 1).add("outline", outline("circle", "[65,25,0]")), "radius"),
            new Bad("an id twice", j -> obj(j, 1).addProperty("id", "holder-left"), "twice"),
            new Bad("an object with the bed's id", j -> obj(j, 1).addProperty("id", "bed"), "reserved"),
            new Bad("a state not in the list", j -> obj(j, 1).addProperty("state", "gone"), "state"),
            new Bad("no marginMm", j -> obj(j, 1).remove("marginMm"), "marginMm"),
            new Bad("a measured top without its record", j -> obj(j, 1).getAsJsonObject("measured").remove("top"),
                    "measured.top"),
            new Bad("a how of spaces", j -> obj(j, 1).getAsJsonObject("measured").getAsJsonObject("top").addProperty(
                    "how", " "), "how"),
            new Bad("two plane points", j -> plane(j).add("points", new JsonParser().parse(
                    "[[60,120,-3],[300,120,-2.96]]")), "3 points"),
            new Bad("three plane points in a line", j -> plane(j).add("points", new JsonParser().parse(
                    "[[60,120,-3],[180,120,-2.98],[300,120,-2.96]]")), "on one line"),
            new Bad("a layer with aboveTipMm and bottomZ", j -> layer(j, 1, 1).addProperty("bottomZ", 30),
                    "aboveTipMm or bottomZ"),
            new Bad("a layer with neither", j -> layer(j, 1, 1).remove("aboveTipMm"), "aboveTipMm or bottomZ"),
            new Bad("a fixed tool's layer above its tip", j -> {
                layer(j, 2, 0).remove("bottomZ");
                layer(j, 2, 0).addProperty("aboveTipMm", 3);
            }, "see-saw"),
            new Bad("the needle without its tipEnd", j -> tool(j, 1).remove("tipEnd"), "tipEnd"),
            new Bad("moves both", j -> tool(j, 1).addProperty("moves", "both"), "moves"),
            new Bad("no mmPerRaw", j -> j.getAsJsonObject("seesaw").getAsJsonObject("n1").remove("mmPerRaw"),
                    "mmPerRaw"),
            new Bad("end stops high to low", j -> j.getAsJsonObject("seesaw").add("endStopsRaw", new JsonParser()
                    .parse("[63,0]")), "endStopsRaw"),
            // the check sweeps N1 and the needle on every leg: exactly one of each (#66 review MAJOR 4), the counts
            // naming which is wrong (review 2 NIT)
            new Bad("N1 and no needle", j -> j.add("tools", tools(j, 0, 2)), "moves needle (here 1 and 0)"),
            new Bad("the needle and no N1", j -> j.add("tools", tools(j, 1, 2)), "moves needle (here 0 and 1)"),
            new Bad("two N1s", j -> tool(j, 1).addProperty("moves", "n1"), "moves needle (here 2 and 0)"),
            // both loaders cap the contact depth (Infra and Backend 11-04)
            new Bad("a contact depth of 1.5 mm", j -> j.addProperty("contactDepthMm", 1.5),
                    "contactDepthMm must be 0 to 1.0"),
            new Bad("a negative contact depth", j -> j.addProperty("contactDepthMm", -0.1),
                    "contactDepthMm must be 0 to 1.0"),
            new Bad("an id with a trailing space", j -> obj(j, 0).addProperty("id", "holder-left "),
                    "the id 'holder-left ' must be"),
            new Bad("an id with a newline", j -> obj(j, 0).addProperty("id", "holder-left\n"),
                    "the id 'holder-left\n' must be"),
            new Bad("an id that is a number", j -> obj(j, 0).addProperty("id", 3), "objects[0].id is required (text)"),
            new Bad("a kind with a leading space", j -> obj(j, 2).addProperty("kind", " movable"),
                    "kind is fixed or movable"),
            new Bad("a state with a newline", j -> obj(j, 0).addProperty("state", "measured\n"),
                    "state is measured, added, moved or removed"),
            new Bad("a name that is a number", j -> obj(j, 0).addProperty("name", 3),
                    "objects[0] (holder-left).name is required (text)"),
            new Bad("a how that is a number", j -> obj(j, 0).getAsJsonObject("measured").getAsJsonObject("top")
                    .addProperty("how", 7), "objects[0] (holder-left).measured.top.how must say how"),
            new Bad("bottomZ null beside aboveTipMm", j -> layer(j, 1, 1).add("bottomZ", JsonNull.INSTANCE),
                    "tools[1].layers[1].bottomZ is null"),
            new Bad("topZ null", j -> obj(j, 0).add("topZ", JsonNull.INSTANCE), "objects[0].topZ is null"),
            new Bad("restRaw null", j -> j.getAsJsonObject("seesaw").add("restRaw", JsonNull.INSTANCE),
                    "seesaw.restRaw is null"),
            // N1 and the needle each have their tip: a tip-end layer with aboveTipMm 0 (review 2 MINOR 5)
            new Bad("N1 without its tip layer", j -> {
                JsonArray holderOnly = new JsonArray();
                holderOnly.add(layer(j, 0, 1));
                tool(j, 0).add("layers", holderOnly);
            }, "tools[0] (n1): a tool on the see-saw needs its tip"),
            new Bad("the needle's tip layer above its tip", j -> layer(j, 1, 0).addProperty("aboveTipMm", 0.5),
                    "tools[1] (needle): a tool on the see-saw needs its tip"),
            new Bad("the needle's tip layer at the head", j -> layer(j, 1, 0).addProperty("at", "head"),
                    "tools[1] (needle): a tool on the see-saw needs its tip"),
            // no time later than now + 1 min: microseconds sent as milliseconds would open the needle gate for good
            // (review 2 MINOR 4)
            new Bad("a tip end measured in the future", j -> tool(j, 1).getAsJsonObject("tipEnd").addProperty("atMs",
                    System.currentTimeMillis() + 120000), "tools[1].tipEnd.atMs is later than now + 1 min"),
            new Bad("a record's time in microseconds", j -> obj(j, 0).getAsJsonObject("measured").getAsJsonObject(
                    "top").addProperty("atMs", System.currentTimeMillis() * 1000),
                    "objects[0].measured.top.atMs is later than now + 1 min"),
            new Bad("a time that is text", j -> plane(j).addProperty("atMs", "0"), "bed.plane.atMs must be a time"),
            // strict JSON, as lumen-sim reads it (review 3 NIT 3)
            Bad.text("an unquoted value", t -> t.replace("\"kind\": \"fixed\"", "\"kind\": fixed"), "not JSON"),
            Bad.text("single quotes", t -> t.replace("\"id\": \"holder-left\"", "'id': 'holder-left'"), "not JSON"),
            Bad.text("a comment", t -> t.replace("\"schema\": 1,", "\"schema\": 1, // the first schema"), "not JSON"),
            Bad.text("text after the model", t -> t + "\n{}", "not JSON"));

    /** Each of {@link #BAD_FILES} is refused with its own words; every case is run, the wrong ones listed together. */
    @Test
    public void theBadFilesAreRefused() throws Exception {
        List<String> wrong = new ArrayList<>();
        String fixture = BedFixture.text();
        for (Bad b : BAD_FILES) {
            try {
                BedModel.parse(b.file.apply(fixture));
                wrong.add(b.what + ": loaded");
            }
            catch (IllegalArgumentException e) {
                if (!e.getMessage().contains(b.says)) {
                    wrong.add(b.what + ": '" + b.says + "' not in: " + e.getMessage());
                }
            }
        }
        assertEquals("", String.join("\n", wrong));
        JsonObject ahead = BedFixture.json();
        tool(ahead, 1).getAsJsonObject("tipEnd").addProperty("atMs", System.currentTimeMillis() + 30000);
        assertNotNull(BedModel.parse(ahead), "30 s ahead (another host's clock) loads");
        // Process's per-part methods: any non-empty how, never a fixed list (Backend 20:5x)
        JsonObject how = BedFixture.json();
        obj(how, 0).getAsJsonObject("measured").getAsJsonObject("top").addProperty("how", "caliper height + margin");
        assertNotNull(BedModel.parse(how));
    }

    /** U2: N1 and the needle below, in and above their knees, on the tilted plane, each at its own tip. */
    @Test
    public void theSeeSawMaps() throws Exception {
        BedModel m = BedFixture.model();
        BedModel.Tool n1 = m.tool("n1"), needle = m.tool("needle");
        double rc1 = BedFixture.rc(100, 120), rc2 = BedFixture.rc(149.469, 107.984);
        // N1 goes down only below its knee (raw falling); at and above it, it is up
        assertEquals(10 - rc1, m.tipZ(n1, 10.0, HX, HY), 1e-9);
        assertEquals(31.5 - rc1, m.tipZ(n1, 31.5, HX, HY), 1e-9);
        assertEquals(31.5 - rc1, m.tipZ(n1, 40.0, HX, HY), 1e-9);
        // the needle goes down only above its knee (raw rising)
        assertEquals(64.875 - rc2 - 31.5, m.tipZ(needle, 20.0, HX, HY), 1e-9);
        assertEquals(64.875 - rc2 - 31.5, m.tipZ(needle, 31.5, HX, HY), 1e-9);
        assertEquals(64.875 - rc2 - 50, m.tipZ(needle, 50.0, HX, HY), 1e-9);
        // tilted: the same raw at the back right is lower above the bed there (the plane is higher)
        assertEquals(10 + 2.89, m.tipZ(n1, 10.0, 324.178, 363.91), 1e-9);
        // the gain scales the travel
        JsonObject j = BedFixture.json();
        j.getAsJsonObject("seesaw").getAsJsonObject("n1").addProperty("mmPerRaw", 1.1);
        assertEquals(1.1 * (10 - rc1), BedModel.parse(j).tipZ(BedModel.parse(j).tool("n1"), 10.0, HX, HY), 1e-9);
    }

    /** Unknown inputs are the worst case, never assumed: no knee or raw Z puts the tip at its lowest end stop. */
    @Test
    public void unknownInputsAreTheWorstCase() throws Exception {
        double rc1 = BedFixture.rc(100, 120), rc2 = BedFixture.rc(149.469, 107.984);
        BedModel m = BedFixture.model();
        assertEquals(0 - rc1, m.tipZ(m.tool("n1"), null, HX, HY), 1e-9, "raw unknown: N1 at end stop 0");
        assertEquals(64.875 - rc2 - 63, m.tipZ(m.tool("needle"), null, HX, HY), 1e-9, "raw unknown: needle at 63");
        JsonObject j = BedFixture.json();
        j.getAsJsonObject("seesaw").getAsJsonObject("n1").remove("kneeRaw");
        j.getAsJsonObject("seesaw").getAsJsonObject("needle").remove("kneeRaw");
        BedModel k = BedModel.parse(j);
        assertEquals(0 - rc1, k.tipZ(k.tool("n1"), 31.5, HX, HY), 1e-9, "no knee: N1 at its lowest end stop");
        assertEquals(64.875 - rc2 - 63, k.tipZ(k.tool("needle"), 31.5, HX, HY), 1e-9);
        j.getAsJsonObject("seesaw").getAsJsonObject("K").remove("raw");
        BedModel noK = BedModel.parse(j);
        assertEquals(Double.NEGATIVE_INFINITY, noK.tipZ(noK.tool("needle"), 31.5, HX, HY), 0, "no K: anywhere");
    }

    /** The Z errors: the measurements' and the home-to-home repeat for each raw value of another home (K counts twice). */
    @Test
    public void theTipErrorsCountTheHomes() throws Exception {
        BedModel m = BedFixture.model();
        // see-saw 0.05 + plane 0.05, all three raw values of home 0
        assertEquals(0.1, m.tipErrMm(m.tool("n1"), 0L), 1e-12);
        assertEquals(0.1, m.tipErrMm(m.tool("needle"), 0L), 1e-12);
        assertEquals(0.1 + 2 * 0.1, m.tipErrMm(m.tool("n1"), 5L), 1e-12, "plane and knees of another home");
        assertEquals(0.1 + 4 * 0.1, m.tipErrMm(m.tool("needle"), 5L), 1e-12, "plane, K twice, knees");
        assertEquals(0, m.tipErrMm(m.tool("camera"), 5L), 0);
    }

    static JsonObject obj(JsonObject j, int i) {
        return j.getAsJsonArray("objects").get(i).getAsJsonObject();
    }

    static JsonObject tool(JsonObject j, int i) {
        return j.getAsJsonArray("tools").get(i).getAsJsonObject();
    }

    /** The fixture's tools {@code keep}, in that order. */
    static JsonArray tools(JsonObject j, int... keep) {
        JsonArray a = new JsonArray();
        for (int i : keep) {
            a.add(tool(j, i));
        }
        return a;
    }

    static JsonObject layer(JsonObject j, int tool, int layer) {
        return tool(j, tool).getAsJsonArray("layers").get(layer).getAsJsonObject();
    }

    static JsonObject plane(JsonObject j) {
        return j.getAsJsonObject("bed").getAsJsonObject("plane");
    }

    static JsonObject outline(String kind, String values) {
        JsonObject o = new JsonObject();
        o.add(kind, new JsonParser().parse(values));
        return o;
    }
}
