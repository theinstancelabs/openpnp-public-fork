package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The one cleaning function for strings that reach OpenPnP (design §2.2): what simple-xml cannot write back. */
public class KTextTest {
    @Test
    public void controlsAndNonCharactersAreDropped() {
        assertEquals("ab", KText.clean("a\u0007b", 100));
        assertEquals("ab", KText.clean("a\u0000\u001Fb", 100));
        assertEquals("ab", KText.clean("a\u007Fb", 100));
        assertEquals("ab", KText.clean("a\u0080\u009Fb", 100));
        assertEquals("ab", KText.clean("a\uFFFEb" + (char) 0xFFFF, 100)); // U+FFFF not as an escape: it ends checkstyle's lexer
        assertEquals("ab", KText.clean("a" + (char) 0xD800 + "b", 100), "a lone high surrogate");
        assertEquals("ab", KText.clean("a" + (char) 0xDC00 + "b", 100), "a lone low surrogate");
        assertEquals("a\uD83D\uDE00b", KText.clean("a\uD83D\uDE00b", 100), "a real pair is kept");
        assertEquals("\u03A9 10k", KText.clean("\u03A9 10k", 100));
    }

    @Test
    public void lineBreaksAndTabsBecomeOneSpace() {
        assertEquals("10k 1%", KText.clean("10k\n1%", 100));
        assertEquals("a b c", KText.clean("a\tb\r\nc", 100));
        assertEquals("a b", KText.clean("  a \t\n  b  ", 100));
        assertEquals("a b", KText.clean("a\u00A0\u00A0b", 100));
        assertEquals("", KText.clean(" \n\t ", 100));
        assertEquals("", KText.clean(null, 100));
    }

    @Test
    public void capsCountCodePoints() {
        assertEquals("abc", KText.clean("abcdef", 3));
        assertEquals("ab", KText.clean("ab cd", 3), "no trailing space after the cut");
        String emoji = "\uD83D\uDE00";
        assertEquals(emoji + emoji, KText.clean(emoji + emoji + emoji, 2));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append('x');
        }
        assertEquals(100, KText.cleanId(sb.toString(), "P").length());
        assertEquals(60, KText.clean(sb.toString(), 60).length());
    }

    @Test
    public void emptyIdsGetTheirDefault() {
        assertEquals("FP", KText.cleanId("\u0007 \n", "FP"));
        assertEquals("P", KText.cleanId("", "P"));
        assertEquals("R1", KText.cleanId(" R1 ", "P"));
    }
}
