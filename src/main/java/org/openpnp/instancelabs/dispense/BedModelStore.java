package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-98: the collision model's file, {@code <config>/instancelabs/collision-model.json}, as the engine holds it. Read
 * and validated at the engine's start, which journals its version and digest ({@value #EVENT}). No file: the check is
 * off and every route runs as before. A file that fails validation: every hooked motion is refused, naming the
 * problem. Only the engine writes it ({@link #write}): version + 1, rewritten atomically (it rides the deploy snapshot
 * like dispense-config.json), one {@value #EVENT} row (who, before, after) per change.
 */
public final class BedModelStore {
    public static final String FILE_NAME = "collision-model.json";
    public static final String EVENT = "BED_MODEL";

    /** What the file holds now. */
    public static final class State {
        public final boolean exists;
        /** The valid model, or null. */
        public final BedModel model;
        /** Why the file is not valid, or null. */
        public final String error;
        /** SHA-256 of the file's bytes, or null. */
        public final String digest;

        State(boolean exists, BedModel model, String error, String digest) {
            this.exists = exists;
            this.model = model;
            this.error = error;
            this.digest = digest;
        }
    }

    private final File file;
    /** Null: nowhere to journal (tests). */
    private final DispenseDb db;
    private volatile State state;

    public BedModelStore(File configDir, DispenseDb db) {
        this.file = fileFor(configDir);
        this.db = db;
        State s = read();
        state = s;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", "start");
        row.put("file", s.exists);
        row.put("version", s.model == null ? null : s.model.version);
        row.put("digest", s.digest);
        row.put("error", s.error);
        journal(s.error == null ? "INFO" : "WARN", row);
    }

    public static File fileFor(File configDir) {
        return new File(new File(configDir, DispenseConfig.SUBDIR), FILE_NAME);
    }

    public State state() {
        return state;
    }

    private State read() {
        if (!file.isFile()) {
            return new State(false, null, null, null);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        }
        catch (IOException e) {
            return new State(true, null, "the file cannot be read: " + e.getMessage(), null);
        }
        try {
            return new State(true, BedModel.parse(new String(bytes, StandardCharsets.UTF_8)), null, sha256(bytes));
        }
        catch (IllegalArgumentException e) {
            return new State(true, null, e.getMessage(), sha256(bytes));
        }
    }

    /**
     * Writes the next model, made from {@code from} (the valid model its caller read; null: none): after another write
     * since, it throws IllegalStateException and nothing is written, so two writes at once never lose one (#66 review
     * MAJOR 2). Version = the stored one + 1 (after an invalid file, its own version + 1 if it has one; after none, 1),
     * {@code changedAtMs} and {@code changedBy} stamped, validated as the loader does (an invalid model throws
     * IllegalArgumentException and nothing is written), rewritten atomically, journaled with {@code before} and
     * {@code after} (the part that changed).
     */
    public synchronized BedModel write(BedModel from, JsonObject next, String who, String what, JsonElement before,
            JsonElement after) throws IOException {
        State s = state;
        if (s.model != from) {
            throw new IllegalStateException("the bed model changed meanwhile: read it again (GET /api/bed)");
        }
        int was = s.model != null ? s.model.version : s.exists ? versionOfFile() : 0;
        JsonObject j = new JsonParser().parse(next.toString()).getAsJsonObject();
        j.addProperty("version", was + 1);
        j.addProperty("changedAtMs", System.currentTimeMillis());
        j.addProperty("changedBy", who);
        BedModel m = BedModel.parse(j);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(j)
                .getBytes(StandardCharsets.UTF_8);
        file.getParentFile().mkdirs();
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        Files.write(tmp.toPath(), bytes);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        state = new State(true, m, null, sha256(bytes));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("who", who);
        row.put("what", what);
        row.put("version", new int[] {was, m.version});
        row.put("before", before);
        row.put("after", after);
        journal("INFO", row);
        return m;
    }

    private int versionOfFile() {
        try {
            JsonElement v = new JsonParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("version");
            return Math.max(0, (int) Math.round(v.getAsDouble()));
        }
        catch (Exception e) {
            return 0;
        }
    }

    private void journal(String level, Map<String, Object> row) {
        if (db != null) {
            db.event(null, level, EVENT, new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(row));
        }
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
