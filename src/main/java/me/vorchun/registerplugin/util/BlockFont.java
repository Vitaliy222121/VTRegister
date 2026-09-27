// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Шрифт 3×5 для капчи из блоков. Только символы, которые в 3×5 читаются
 * однозначно: без путаницы O/0, I/1, S/5, B/8, Z/2, G/6.
 */
public final class BlockFont {

    public static final String CHARS = "ACEFHKLPRTUXY3479";
    private static final Map<Character, String[]> FONT = new HashMap<>();

    static {
        String[] f = {
                "A010101111101101", "C111100100100111", "E111100111100111", "F111100111100100",
                "H101101111101101", "K101110100110101", "L100100100100111", "P111101111100100",
                "R110101110101101", "T111010010010010", "U101101101101111", "X101101010101101",
                "Y101101010010010", "3111001111001111", "4101101111001001", "7111001010010010",
                "9111101111001111"};
        for (String g : f) {
            String b = g.substring(1);
            FONT.put(g.charAt(0), new String[]{b.substring(0, 3), b.substring(3, 6),
                    b.substring(6, 9), b.substring(9, 12), b.substring(12, 15)});
        }
    }

    private BlockFont() {
    }

    /** 5 строк по 3 символа ('1' — блок), null — символа нет в шрифте. */
    public static String[] glyph(char c) {
        return FONT.get(Character.toUpperCase(c));
    }
}
