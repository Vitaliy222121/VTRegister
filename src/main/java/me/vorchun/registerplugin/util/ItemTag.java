// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.util;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Метка «это наш предмет» для всех версий. 1.14+ — PersistentDataContainer;
 * на 1.13 его нет — невидимая строка описания из §-кодов. Классы PDC трогает
 * только вложенный Modern, поэтому на 1.13 он просто не загружается.
 */
public final class ItemTag {

    private static final boolean PDC = classExists("org.bukkit.persistence.PersistentDataHolder");

    private ItemTag() {
    }

    public static void mark(ItemMeta meta, NamespacedKey key) {
        if (meta == null || key == null) {
            return;
        }
        if (PDC) {
            Modern.mark(meta, key);
            return;
        }
        List<String> lore = meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(hidden(key.getKey()));
        meta.setLore(lore);
    }

    public static boolean has(ItemMeta meta, NamespacedKey key) {
        if (meta == null || key == null) {
            return false;
        }
        try {
            if (PDC) {
                return Modern.has(meta, key);
            }
            List<String> lore = meta.getLore();
            return lore != null && lore.contains(hidden(key.getKey()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Материал по имени: новых блоков (1.14–1.16+) на старом ядре нет — берём замену. */
    public static Material mat(String name, Material fallback) {
        Material m = Material.getMaterial(name);
        return m != null ? m : fallback;
    }

    private static String hidden(String s) {
        StringBuilder b = new StringBuilder("§r");
        for (int i = 0; i < s.length(); i++) {
            b.append('§').append(s.charAt(i));
        }
        return b.toString();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ItemTag.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Modern {
        static void mark(ItemMeta m, NamespacedKey k) {
            m.getPersistentDataContainer().set(k, PersistentDataType.BYTE, (byte) 1);
        }

        static boolean has(ItemMeta m, NamespacedKey k) {
            return m.getPersistentDataContainer().has(k, PersistentDataType.BYTE);
        }
    }
}
