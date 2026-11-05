package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Setup's advanced JSON editor (web/setup.js) keeps its own lists of the settings the engine stamps and of the ones
 * PUT /api/config merges one level deep. A setting the server protects but the editor sends gets the server's 400, and
 * a nested setting the editor sends whole overwrites fields changed meanwhile, so the editor's lists must cover the
 * server's (PR #64: they had drifted apart).
 */
public class WebEditorSettingsListsTest {
    private static Set<String> jsSet(String js, String name) {
        Matcher m = Pattern.compile("const " + name + " = new Set\\(\\[(.*?)\\]\\)", Pattern.DOTALL).matcher(js);
        assertTrue(m.find(), name + " not found in setup.js");
        Set<String> out = new LinkedHashSet<>();
        Matcher q = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (q.find()) {
            out.add(q.group(1));
        }
        return out;
    }

    private static void assertCovers(Set<String> server, Set<String> editor, String what) {
        Set<String> missing = new TreeSet<>(server);
        missing.removeAll(editor);
        assertTrue(missing.isEmpty(), what + " missing from web/setup.js: " + missing);
    }

    @Test
    public void theEditorKnowsEverySettingTheServerProtectsOrMergesOneLevelDeep() throws Exception {
        String js;
        try (InputStream in = WebEditorSettingsListsTest.class.getResourceAsStream("/org/openpnp/instancelabs/web/setup.js")) {
            assertNotNull(in, "web/setup.js on the classpath");
            js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertCovers(DispenseEndpoints.PROTECTED_SETTINGS, jsSet(js, "STAMPED_TOP"), "protected settings (STAMPED_TOP)");
        assertCovers(DispenseEndpoints.PROTECTED_NESTED, jsSet(js, "STAMPED_NESTED"), "protected nested settings (STAMPED_NESTED)");
        // exactly the server's: a name only the editor has makes it send a partial object the server then sets whole
        assertEquals(new TreeSet<>(DispenseEndpoints.NESTED_SETTINGS), new TreeSet<>(jsSet(js, "NESTED")),
                "nested settings: web/setup.js NESTED must equal DispenseEndpoints.NESTED_SETTINGS");
    }
}
