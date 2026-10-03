// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedrockSupportService {

    private final JavaPlugin plugin;

    private volatile boolean enabled;
    private volatile boolean trustFloodgatePlayers;
    private volatile boolean logApiErrors;
    private volatile boolean floodgateAvailable;
    private volatile boolean missingPluginWarned;
    private volatile boolean apiErrorWarned;
    // Кэш рефлексии Floodgate API: isBedrockPlayer зовётся часто (вход, AFK, этапы)
    private volatile Object floodgateApi;
    private volatile Method isFloodgatePlayerMethod;
    private volatile boolean apiResolved;
    /** Префиксы Bedrock-ников Floodgate: из plugins/floodgate/config.yml + стандартные '.' и '*'. */
    private volatile java.util.List<String> namePrefixes = java.util.Arrays.asList(".", "*");

    public BedrockSupportService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("bedrock.enabled", false);
        trustFloodgatePlayers = plugin.getConfig().getBoolean("bedrock.trust_floodgate_players", false);
        logApiErrors = plugin.getConfig().getBoolean("bedrock.log_api_errors", true);
        floodgateAvailable = findFloodgatePlugin() != null;
        missingPluginWarned = false;
        apiErrorWarned = false;
        apiResolved = false;
        floodgateApi = null;
        isFloodgatePlayerMethod = null;
        namePrefixes = readPrefixes();

        if (enabled && !floodgateAvailable) {
            warnMissingFloodgate();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldBypassAuth(Player player) {
        return enabled && trustFloodgatePlayers && isBedrockPlayer(player);
    }

    public boolean isBedrockPlayer(Player player) {
        if (!enabled || player == null) {
            return false;
        }
        Plugin floodgate = findFloodgatePlugin();
        if (floodgate == null || !floodgate.isEnabled()) {
            warnMissingFloodgate();
            return false;
        }
        return apiSaysFloodgate(player.getUniqueId());
    }

    /** Floodgate API: isFloodgatePlayer(uuid). Без Floodgate на этом сервере — false. */
    private boolean apiSaysFloodgate(UUID uuid) {
        try {
            if (!apiResolved) {
                apiResolved = true;
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = apiClass.getMethod("getInstance").invoke(null);
                isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                if (floodgateApi == null) {
                    // Floodgate ещё не включился — спросим снова при следующем вызове
                    apiResolved = false;
                }
            }
            Method m = isFloodgatePlayerMethod;
            Object api = floodgateApi;
            if (m == null || api == null) {
                return false;
            }
            return Boolean.TRUE.equals(m.invoke(api, uuid));
        } catch (Throwable t) {
            if (logApiErrors && !apiErrorWarned && findFloodgatePlugin() != null) {
                apiErrorWarned = true;
                plugin.getLogger().warning("Не удалось определить Bedrock-игрока через Floodgate API: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return false;
        }
    }

    /**
     * Похож ли вход на Bedrock-игрока Floodgate — ТОЛЬКО для мягких фильтров
     * (фильтр ников), не для входа без пароля: Floodgate выдаёт UUID вида
     * 00000000-0000-0000-xxxx-xxxxxxxxxxxx (старшие 64 бита = 0), в том числе
     * когда Floodgate стоит на прокси, а не на этом сервере. Работает и в
     * AsyncPlayerPreLoginEvent, независимо от bedrock.enabled.
     */
    public boolean looksLikeFloodgate(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() != 0L) {
            return true;
        }
        Plugin floodgate = findFloodgatePlugin();
        return floodgate != null && floodgate.isEnabled() && apiSaysFloodgate(uuid);
    }

    /** Ник без префикса Floodgate (".Steve" → "Steve"); без префикса — как есть. */
    public String stripFloodgatePrefix(String name) {
        if (name == null) {
            return null;
        }
        for (String p : namePrefixes) {
            if (!p.isEmpty() && name.startsWith(p) && name.length() > p.length()) {
                return name.substring(p.length());
            }
        }
        return name;
    }

    /** username-prefix из plugins/floodgate/config.yml (если Floodgate на этом сервере). */
    private java.util.List<String> readPrefixes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.io.File f = new java.io.File(plugin.getDataFolder().getParentFile(), "floodgate/config.yml");
            if (f.isFile()) {
                org.bukkit.configuration.file.YamlConfiguration y =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                String p = y.getString("username-prefix", null);
                if (p != null && !p.isEmpty()) {
                    out.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        if (!out.contains(".")) {
            out.add(".");
        }
        if (!out.contains("*")) {
            out.add("*");
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private Plugin findFloodgatePlugin() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        Plugin pluginByLower = pluginManager.getPlugin("floodgate");
        if (pluginByLower != null) {
            return pluginByLower;
        }
        return pluginManager.getPlugin("Floodgate");
    }

    private void warnMissingFloodgate() {
        if (missingPluginWarned) {
            return;
        }
        missingPluginWarned = true;
        plugin.getLogger().warning("Bedrock-поддержка включена, но Floodgate не найден. Bedrock bypass авторизации отключён.");
    }

    private static final int READY = 1124856746;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1011) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1011) == READY;
    }
}
