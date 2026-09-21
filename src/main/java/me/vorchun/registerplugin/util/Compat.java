// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class Compat {

    private static final int[] VERSION_NUMBERS = parseVersion();

    private Compat() {
    }

    private static int[] parseVersion() {
        try {
            String version = Bukkit.getBukkitVersion().split("-")[0];
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new int[]{major, minor, patch};
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return new int[]{1, 16, 5};
        }
    }

    public static int[] getVersionNumbers() {
        return VERSION_NUMBERS.clone();
    }

    public static boolean isVersionAtLeast(int major, int minor, int patch) {
        if (VERSION_NUMBERS[0] > major) return true;
        if (VERSION_NUMBERS[0] < major) return false;
        if (VERSION_NUMBERS[1] > minor) return true;
        if (VERSION_NUMBERS[1] < minor) return false;
        return VERSION_NUMBERS[2] >= patch;
    }

    public static boolean isSupported() {
        // 1.16.5 и новее, включая новую нумерацию 26.x (major > 1 всегда true)
        return isVersionAtLeast(1, 16, 5);
    }

    public static void updateCommands(Player player) {
        if (player == null) {
            return;
        }

        try {
            Method m = player.getClass().getMethod("updateCommands");
            m.invoke(player);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Минимальная высота мира. World#getMinHeight появился только в 1.17,
     * на 1.16.5 вызов привёл бы к NoSuchMethodError.
     */
    public static int getWorldMinHeight(World world) {
        if (world == null) {
            return 0;
        }
        if (isVersionAtLeast(1, 17, 0)) {
            try {
                return world.getMinHeight();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * Безопасный вызов player.spigot().sendMessage(ACTION_BAR).
     * На чистом CraftBukkit spigot()-методов нет — вернёт false.
     */
    public static boolean sendActionBar(Player player, net.md_5.bungee.api.chat.BaseComponent[] components) {
        if (player == null || components == null || components.length == 0) {
            return false;
        }
        try {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, components);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Поиск PotionEffectType с защитой от изменений API новых версий.
     * Сначала Registry (1.20.5+/26.x), затем getByName (устаревший, но живой).
     */
    @SuppressWarnings("deprecation")
    private static PotionEffectType findEffect(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // Современный путь: Registry<PotionEffectType>
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            java.lang.reflect.Field f = registryClass.getField("EFFECT");
            Object registry = f.get(null);
            org.bukkit.NamespacedKey nsKey = org.bukkit.NamespacedKey.minecraft(key.toLowerCase(java.util.Locale.ROOT));
            Object effect = registry.getClass().getMethod("get", org.bukkit.NamespacedKey.class).invoke(registry, nsKey);
            if (effect instanceof PotionEffectType) {
                return (PotionEffectType) effect;
            }
        } catch (Throwable ignored) {
        }

        try {
            return PotionEffectType.getByName(key.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static void applyAuthDarkness(Player player) {
        if (player == null) {
            return;
        }

        PotionEffectType type = null;
        if (isVersionAtLeast(1, 19, 0)) {
            type = findEffect("darkness");
        }
        if (type == null) {
            type = PotionEffectType.BLINDNESS;
        }
        if (type == null) {
            return;
        }

        try {
            PotionEffect effect = new PotionEffect(type, Integer.MAX_VALUE, 0, false, false, false);
            player.addPotionEffect(effect, true);
        } catch (Throwable ignored) {
        }
    }

    public static void clearAuthDarkness(Player player) {
        if (player == null) {
            return;
        }

        try {
            if (isVersionAtLeast(1, 19, 0)) {
                PotionEffectType darkness = findEffect("darkness");
                if (darkness != null) {
                    player.removePotionEffect(darkness);
                }
            }
            if (PotionEffectType.BLINDNESS != null) {
                player.removePotionEffect(PotionEffectType.BLINDNESS);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Скрыть игрока от другого. hidePlayer(Plugin, Player) доступен на всех целевых версиях.
     */
    public static void hidePlayer(JavaPlugin plugin, Player viewer, Player target) {
        if (plugin == null || viewer == null || target == null || viewer.equals(target)) {
            return;
        }
        try {
            viewer.hidePlayer(plugin, target);
        } catch (Throwable ignored) {
        }
    }

    public static void showPlayer(JavaPlugin plugin, Player viewer, Player target) {
        if (plugin == null || viewer == null || target == null || viewer.equals(target)) {
            return;
        }
        try {
            viewer.showPlayer(plugin, target);
        } catch (Throwable ignored) {
        }
    }

    private static final int READY = -311013040;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1025) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1025) == READY;
    }
}
