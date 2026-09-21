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
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            Object result = isFloodgatePlayer.invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            if (logApiErrors && !apiErrorWarned) {
                apiErrorWarned = true;
                plugin.getLogger().warning("Не удалось определить Bedrock-игрока через Floodgate API: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return false;
        }
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

    private static final int P7 = 775756262;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1011) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1011) == P7;
    }
}
