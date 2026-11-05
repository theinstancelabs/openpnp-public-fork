package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** BR-98: the fixture collision model (src/test/resources), shared by the unit tests, the SIM tests and lumen-sim. */
public final class BedFixture {
    public static final String RESOURCE = "/config/DispenseSimMachine/instancelabs/collision-model.json";

    private BedFixture() {
    }

    public static String text() throws Exception {
        try (InputStream in = BedFixture.class.getResourceAsStream(RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A fresh copy of the fixture's tree, to change in a test. */
    public static JsonObject json() throws Exception {
        return new JsonParser().parse(text()).getAsJsonObject();
    }

    public static BedModel model() throws Exception {
        return BedModel.parse(json());
    }

    /** Puts the fixture where the engine reads it: {@code <config>/instancelabs/collision-model.json}. */
    public static File install(File configDir) throws Exception {
        File f = BedModelStore.fileFor(configDir);
        f.getParentFile().mkdirs();
        try (InputStream in = BedFixture.class.getResourceAsStream(RESOURCE)) {
            Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    /** The plane the fixture's points fit (least squares, worked by hand): raw = x / 6000 + 7 y / 18000 - 3.0566667. */
    public static double rc(double x, double y) {
        return x / 6000.0 + 7.0 * y / 18000.0 - (2.945 + 0.03 + 7.0 * 210 / 18000.0);
    }
}
