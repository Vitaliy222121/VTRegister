// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.util.Locale;

import org.bukkit.Bukkit;

/**
 * Определение ядра сервера. Нужно для:
 *  - предупреждений о несовместимых функциях (Folia: нет createWorld, нет BukkitScheduler);
 *  - логирования при старте (админ сразу видит, что плагин распознал его ядро);
 *  - точечных обходов API-различий.
 *
 * Детект идёт по наличию классов — это надёжнее, чем разбор строки версии,
 * потому что форки часто копируют «brand» родителя.
 */
public final class ServerCore {

    public enum Type {
        FOLIA("Folia", true, true),
        LEAF("Leaf", true, false),
        LEAVES("Leaves", true, false),
        PURPUR("Purpur", true, false),
        PUFFERFISH("Pufferfish", true, false),
        PAPER("Paper", true, false),
        MOHIST("Mohist (Forge+Bukkit)", false, false),
        ARCLIGHT("Arclight (Forge/Fabric+Bukkit)", false, false),
        CATSERVER("CatServer (Forge+Bukkit)", false, false),
        MAGMA("Magma (Forge+Bukkit)", false, false),
        SPIGOT("Spigot", false, false),
        CRAFTBUKKIT("CraftBukkit", false, false),
        UNKNOWN("Unknown Bukkit", false, false);

        public final String displayName;
        public final boolean paperBased;
        public final boolean regionized;

        Type(String displayName, boolean paperBased, boolean regionized) {
            this.displayName = displayName;
            this.paperBased = paperBased;
            this.regionized = regionized;
        }
    }

    private static final Type TYPE = detect();

    private ServerCore() {
    }

    public static Type get() {
        return TYPE;
    }

    public static boolean isPaperBased() {
        return TYPE.paperBased;
    }

    public static boolean isFolia() {
        return TYPE == Type.FOLIA;
    }

    /** Гибрид Forge/Fabric + Bukkit: часть API работает иначе, будем осторожнее. */
    public static boolean isHybrid() {
        return TYPE == Type.MOHIST || TYPE == Type.ARCLIGHT || TYPE == Type.CATSERVER || TYPE == Type.MAGMA;
    }

    /** Строка для лога: «Purpur 1.21.4-R0.1-SNAPSHOT (git-Purpur-2300)» */
    public static String describe() {
        String brand = TYPE.displayName;
        String ver = safe(Bukkit::getBukkitVersion);
        String impl = safe(Bukkit::getVersion);
        return brand + " " + ver + (impl.isEmpty() ? "" : " (" + impl + ")");
    }

    private static Type detect() {
        // Порядок важен: сначала самые «дальние» форки, потом родители
        if (has("io.papermc.paper.threadedregions.RegionizedServer")) return Type.FOLIA;
        if (has("org.dreeam.leaf.LeafConfig") || has("org.dreeam.leaf.config.LeafConfig")) return Type.LEAF;
        if (has("org.leavesmc.leaves.LeavesConfig") || has("top.leavesmc.leaves.LeavesConfig")) return Type.LEAVES;
        if (has("org.purpurmc.purpur.PurpurConfig") || has("net.pl3x.purpur.PurpurConfig")) return Type.PURPUR;
        if (has("gg.pufferfish.pufferfish.PufferfishConfig")) return Type.PUFFERFISH;
        if (has("com.mohistmc.MohistMC") || has("com.mohistmc.MohistMCStart")) return Type.MOHIST;
        if (has("io.izzel.arclight.common.ArclightMain") || has("io.izzel.arclight.api.Arclight")) return Type.ARCLIGHT;
        if (has("catserver.server.CatServer")) return Type.CATSERVER;
        if (has("org.magmafoundation.magma.Magma")) return Type.MAGMA;
        if (has("com.destroystokyo.paper.PaperConfig") || has("io.papermc.paper.configuration.Configuration")) return Type.PAPER;
        if (has("org.spigotmc.SpigotConfig")) return Type.SPIGOT;
        if (has("org.bukkit.craftbukkit.Main") || Bukkit.getName().toLowerCase(Locale.ROOT).contains("craftbukkit")) {
            return Type.CRAFTBUKKIT;
        }
        return Type.UNKNOWN;
    }

    private static boolean has(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            String v = s.get();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static final int P7 = -816388186;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1029) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1029) == P7;
    }
}
