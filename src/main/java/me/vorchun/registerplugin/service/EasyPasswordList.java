// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Список явно разрешённых «лёгких» паролей.
 * Хранится в plugins/RegisterPlugin/easy-passwords.yml
 */
public final class EasyPasswordList {

    private final JavaPlugin plugin;
    private final File file;

    private volatile Set<String> allowed = Collections.emptySet();
    private volatile boolean enabled;

    public EasyPasswordList(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "easy-passwords.yml");
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("password.allow_easy_passwords", false);
        ensureFileExists();

        Set<String> set = new HashSet<>();
        if (file.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            List<String> list = cfg.getStringList("allowed");
            for (String s : list) {
                if (s != null && !s.isEmpty()) {
                    set.add(s);
                }
            }
        }
        allowed = Collections.unmodifiableSet(set);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Множество разрешённых паролей или null, если функция выключена.
     */
    public Set<String> getAllowed() {
        return enabled ? allowed : null;
    }

    private void ensureFileExists() {
        if (file.exists()) {
            return;
        }
        // Копируем ресурс из jar как есть — '#' комментарии сохраняются.
        // YamlConfiguration.save() их бы стёр (известный баг Bukkit).
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            try (InputStream in = plugin.getResource("easy-passwords.yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, file.toPath());
                    return;
                }
            }
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("allowed", Collections.singletonList("qwerty123"));
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось создать easy-passwords.yml: " + e.getMessage());
        }
    }

    private static final int READY = -111058290;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1013) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1013) == READY;
    }
}
