// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

/**
 * HEX-цвет «#RRGGBB» → код цвета чата. На 1.16+ — настоящий HEX; на 1.13–1.15
 * HEX нет — берём ближайший из 16 обычных цветов (раньше текст становился белым).
 */
public final class LegacyColor {

    private static final int[] RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String CODES = "0123456789abcdef";
    /** 0 — не проверяли, 1 — HEX есть, -1 — старое ядро без HEX. */
    private static volatile int hexState;

    private LegacyColor() {
    }

    private static final java.util.Map<String, String> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static String of(String hex) {
        if (hex == null) {
            return "";
        }
        String c = CACHE.get(hex);
        if (c != null) {
            return c;
        }
        c = compute(hex);
        if (CACHE.size() > 1024) {
            CACHE.clear();
        }
        CACHE.put(hex, c);
        return c;
    }

    private static String compute(String hex) {
        if (hexState >= 0) {
            try {
                String s = net.md_5.bungee.api.ChatColor.of(hex).toString();
                hexState = 1;
                return s;
            } catch (LinkageError e) {
                hexState = -1; // метода нет — дальше сразу ближайший цвет
            } catch (Throwable t) {
                return ""; // кривой HEX в конфиге
            }
        }
        return nearest(hex);
    }

    static String nearest(String hex) {
        int v;
        try {
            v = Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16);
        } catch (Exception e) {
            return "";
        }
        int r = v >> 16 & 0xFF;
        int g = v >> 8 & 0xFF;
        int b = v & 0xFF;
        int best = 15;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < RGB.length; i++) {
            int dr = r - (RGB[i] >> 16 & 0xFF);
            int dg = g - (RGB[i] >> 8 & 0xFF);
            int db = b - (RGB[i] & 0xFF);
            long d = 3L * dr * dr + 4L * dg * dg + 2L * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return "§" + CODES.charAt(best);
    }
}
