package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** JSON helpers (Gson 2.2, as bundled with OpenPnP). */
public final class Json {
    private Json() {
    }

    public static final Gson GSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues()
            .disableHtmlEscaping().create();

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    /** Parses a request body; an empty body is an empty object. */
    public static JsonObject parseObject(String body) throws ApiException {
        if (body == null || body.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement e = new JsonParser().parse(body);
            if (!e.isJsonObject()) {
                throw ApiException.badRequest("the request body must be a JSON object");
            }
            return e.getAsJsonObject();
        }
        catch (JsonParseException | IllegalStateException e) {
            throw ApiException.badRequest("the request body is not valid JSON: " + e.getMessage());
        }
    }

    public static <T> T fromJson(JsonObject o, Class<T> type) throws ApiException {
        try {
            return GSON.fromJson(o, type);
        }
        catch (JsonParseException | IllegalStateException | NumberFormatException e) {
            throw ApiException.badRequest("invalid fields: " + e.getMessage());
        }
    }

    // --- typed, validating field access ---------------------------------------------------------

    private static JsonElement field(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e == null || e.isJsonNull() ? null : e;
    }

    public static boolean has(JsonObject o, String name) {
        return field(o, name) != null;
    }

    public static String str(JsonObject o, String name, String def) throws ApiException {
        JsonElement e = field(o, name);
        if (e == null) {
            return def;
        }
        if (!e.isJsonPrimitive()) {
            throw ApiException.badRequest("'" + name + "' must be a string");
        }
        String s = e.getAsString().trim();
        return s.isEmpty() ? def : s;
    }

    /** {@link #str} on one line: control characters (a newline, a tab) would split or forge a journal row (400). */
    public static String line(JsonObject o, String name, String def) throws ApiException {
        String s = str(o, name, def);
        if (s != null && s.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("'" + name + "' is one line of text: no control characters (a newline, a "
                    + "tab)");
        }
        return s;
    }

    public static Double num(JsonObject o, String name, Double def) throws ApiException {
        JsonElement e = field(o, name);
        if (e == null || (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                && e.getAsString().trim().isEmpty())) {
            return def;
        }
        try {
            double d = e.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        }
        catch (RuntimeException ex) {
            throw ApiException.badRequest("'" + name + "' must be a number");
        }
    }

    public static double reqNum(JsonObject o, String name) throws ApiException {
        Double d = num(o, name, null);
        if (d == null) {
            throw ApiException.badRequest("'" + name + "' is required");
        }
        return d;
    }

    public static Integer integer(JsonObject o, String name, Integer def) throws ApiException {
        Double d = num(o, name, def == null ? null : def.doubleValue());
        if (d == null) {
            return null;
        }
        if (d != Math.rint(d)) {
            throw ApiException.badRequest("'" + name + "' must be a whole number");
        }
        return d.intValue();
    }

    public static boolean bool(JsonObject o, String name, boolean def) throws ApiException {
        JsonElement e = field(o, name);
        if (e == null) {
            return def;
        }
        if (!e.isJsonPrimitive()) {
            throw ApiException.badRequest("'" + name + "' must be true or false");
        }
        String s = e.getAsString();
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
            return Boolean.parseBoolean(s);
        }
        throw ApiException.badRequest("'" + name + "' must be true or false");
    }

    /** A list of numbers given as a JSON array or a comma/space separated string. */
    public static double[] numbers(JsonObject o, String name, double[] def) throws ApiException {
        JsonElement e = field(o, name);
        if (e == null) {
            return def;
        }
        List<Double> l = new ArrayList<>();
        try {
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                for (JsonElement x : a) {
                    l.add(x.getAsDouble());
                }
            }
            else {
                for (String s : e.getAsString().split("[,;\\s]+")) {
                    if (!s.isEmpty()) {
                        l.add(Double.parseDouble(s));
                    }
                }
            }
        }
        catch (RuntimeException ex) {
            throw ApiException.badRequest("'" + name + "' must be a list of numbers");
        }
        if (l.isEmpty()) {
            return def;
        }
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = l.get(i);
        }
        return r;
    }

    public static List<String> strings(JsonObject o, String name) throws ApiException {
        JsonElement e = field(o, name);
        List<String> l = new ArrayList<>();
        if (e == null) {
            return l;
        }
        if (!e.isJsonArray()) {
            throw ApiException.badRequest("'" + name + "' must be a list");
        }
        for (JsonElement x : e.getAsJsonArray()) {
            l.add(x.getAsString());
        }
        return l;
    }
}
