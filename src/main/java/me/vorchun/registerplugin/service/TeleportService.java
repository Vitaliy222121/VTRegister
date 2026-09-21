// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.util.Scheduler;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

public final class TeleportService implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final SessionManager sessionManager;

    private final Map<UUID, Location> savedLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> authorizedTeleports = new ConcurrentHashMap<>();
    private boolean proxyMode = false;
    private String proxyType = "bungeecord";
    private String lastLoggedProxyState;
    private boolean proxyTeleportEnabled = false;
    private String proxyTargetServer = "lobby";
    private Location lobbyLocation = null;

    private boolean proxySecurityConfigured = false;

    // автоопределение лобби по IP и безопасный перенос
    private String lobbyAddress = "";
    private boolean autoDetectName = true;
    private boolean safeTransfer = true;
    private boolean fallbackToLobbyLocation = true;
    private int retrySeconds = 3;
    private final java.util.List<String> knownServers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, String> serverAddresses = new ConcurrentHashMap<>();

    public TeleportService(JavaPlugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        detectProxyMode();
        loadConfig();
        registerChannels();
    }

    private void registerChannels() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "BungeeCord", this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "velocity:player");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "velocity:player", this);
    }

    /**
     * Ответы прокси: список серверов и их адреса.
     * BungeeCord присылает их в ответ на GetServers / ServerIP.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            com.google.common.io.ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if ("GetServers".equals(sub)) {
                String[] servers = in.readUTF().split(", ");
                knownServers.clear();
                java.util.Collections.addAll(knownServers, servers);
                return;
            }
            if ("ServerIP".equals(sub)) {
                String name = in.readUTF();
                String ip = in.readUTF();
                int port = in.readUnsignedShort();
                serverAddresses.put(name.toLowerCase(java.util.Locale.ROOT), ip + ":" + port);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Запросить у прокси список серверов и их адреса (для автоопределения лобби). */
    private void requestServerList(Player player) {
        if (player == null) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("GetServers");
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    private void requestServerIp(Player player, String serverName) {
        if (player == null || serverName == null || serverName.isEmpty()) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ServerIP");
            out.writeUTF(serverName);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Определить имя лобби-сервера по настроенному lobby_address.
     * Возвращает имя, если удалось сопоставить адрес; иначе null.
     */
    public String detectLobbyServerName(Player player) {
        String address = lobbyAddress;
        if (address == null || address.trim().isEmpty() || !autoDetectName) {
            return null;
        }
        String needle = address.trim().toLowerCase(java.util.Locale.ROOT);
        // 1) уже знаем адреса — ищем совпадение
        for (Map.Entry<String, String> e : serverAddresses.entrySet()) {
            if (e.getValue() != null && e.getValue().toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                return e.getKey();
            }
        }
        // 2) ещё не знаем — попросим у прокси и подождём ответ на следующем входе
        if (knownServers.isEmpty()) {
            requestServerList(player);
        }
        for (String name : knownServers) {
            requestServerIp(player, name);
        }
        return null;
    }

    private void detectProxyMode() {
        // 1) Явная ручная настройка в config.yml имеет приоритет
        String manualType = plugin.getConfig().getString("proxy.type", "auto");
        if (manualType != null && !manualType.equalsIgnoreCase("auto") && !manualType.trim().isEmpty()) {
            proxyMode = true;
            proxyType = manualType.trim().toLowerCase();
            plugin.getLogger().info("Режим прокси: включён вручную (" + proxyType + ")");
            return;
        }

        // 2) Автоопределение из spigot.yml (защищено — Bukkit.spigot() нет на чистом CraftBukkit)
        boolean bungeeEnabled = false;
        boolean velocityEnabled = false;
        try {
            bungeeEnabled = Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
            velocityEnabled = Bukkit.spigot().getConfig().getBoolean("settings.velocity-support.enabled", false);
        } catch (Throwable ignored) {
        }

        // Paper глобальный конфиг для Velocity (Paper 1.19+): proxies.velocity.enabled
        try {
            Object paperConfig = Bukkit.getServer().getClass().getMethod("getPaperConfig").invoke(Bukkit.getServer());
            if (paperConfig != null) {
                Object v = paperConfig.getClass().getMethod("getBoolean", String.class, boolean.class)
                        .invoke(paperConfig, "proxies.velocity.enabled", false);
                if (v instanceof Boolean && (Boolean) v) {
                    velocityEnabled = true;
                }
            }
        } catch (Throwable ignored) {
        }

        if (velocityEnabled) {
            proxyMode = true;
            proxyType = "velocity";
        } else if (bungeeEnabled) {
            proxyMode = true;
            proxyType = "bungeecord";
        } else {
            proxyMode = plugin.getConfig().getBoolean("proxy_mode", false) || plugin.getConfig().getBoolean("proxy.enabled", false);
            String t = plugin.getConfig().getString("proxy_type", null);
            if (t == null) {
                t = plugin.getConfig().getString("proxy.type", "bungeecord");
            }
            proxyType = t.toLowerCase();
        }

        // reload() вызывается несколько раз при старте — логируем только при смене режима
        String state = proxyMode + ":" + proxyType;
        if (!state.equals(lastLoggedProxyState)) {
            lastLoggedProxyState = state;
            plugin.getLogger().info("Режим прокси: " + (proxyMode ? "включён (" + proxyType + ")" : "выключен"));
        }
    }

    private void loadConfig() {
        reload();
    }

    public void reload() {
        detectProxyMode();

        proxyTeleportEnabled = plugin.getConfig().getBoolean("proxy_server.enabled", false);
        proxyTargetServer = plugin.getConfig().getString("proxy_server.server_name", "lobby");
        lobbyAddress = plugin.getConfig().getString("proxy_server.lobby_address", "");
        autoDetectName = plugin.getConfig().getBoolean("proxy_server.auto_detect_name", true);
        safeTransfer = plugin.getConfig().getBoolean("proxy_server.safe_transfer", true);
        fallbackToLobbyLocation = plugin.getConfig().getBoolean("proxy_server.fallback_to_lobby_location", true);
        retrySeconds = Math.max(1, plugin.getConfig().getInt("proxy_server.retry_seconds", 3));

        proxySecurityConfigured = plugin.getConfig().getBoolean("proxy_security.enabled", false);
        if (proxySecurityConfigured) {
            plugin.getLogger().warning("proxy_security в TeleportService не может проверять подлинность игрока на backend-сервере и не используется для auth-решений");
        }

        if (plugin.getConfig().getBoolean("lobby.enabled", false)) {
            String worldName = plugin.getConfig().getString("lobby.world", "world");
            double x = plugin.getConfig().getDouble("lobby.x", 0);
            double y = plugin.getConfig().getDouble("lobby.y", 100);
            double z = plugin.getConfig().getDouble("lobby.z", 0);
            float yaw = (float) plugin.getConfig().getDouble("lobby.yaw", 0);
            float pitch = (float) plugin.getConfig().getDouble("lobby.pitch", 0);

            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                lobbyLocation = new Location(world, x, y, z, yaw, pitch);
            }
        } else {
            lobbyLocation = null;
        }
    }

    public boolean isProxyConnectionValid(Player player) {
        return player != null && player.getAddress() != null && player.getAddress().getAddress() != null;
    }

    public void saveJoinLocation(Player player) {
        if (proxyMode && !sessionManager.isLoggedIn(player.getUniqueId())) {
            if (!isProxyConnectionValid(player)) {
                plugin.getLogger().warning("Не удалось сохранить точку входа для игрока " + player.getName() + ": адрес соединения недоступен");
                return;
            }
            savedLocations.put(player.getUniqueId(), player.getLocation().clone());
        }
    }

    public void teleportToSafeLocation(Player player) {
        if (!player.isOnline()) {
            return;
        }

        Location safeLoc = findSafeLocation(player);
        if (safeLoc != null) {
            Scheduler.runSync(plugin, () -> {
                if (player.isOnline()) {
                    authorizeTeleport(player.getUniqueId());
                    player.teleport(safeLoc);
                }
            });
        }
    }

    public void teleportToLobby(Player player) {
        if (!player.isOnline()) {
            return;
        }

        if (proxyTeleportEnabled && proxyMode) {
            if (!isProxyConnectionValid(player)) {
                plugin.getLogger().warning("Не удалось перевести игрока " + player.getName() + " через прокси: адрес соединения недоступен");
                fallbackLocal(player);
                return;
            }
            // Имя сервера: сначала пробуем определить по lobby_address
            String detected = detectLobbyServerName(player);
            String target = detected != null ? detected : proxyTargetServer;
            if (detected != null && !detected.equalsIgnoreCase(proxyTargetServer)) {
                plugin.getLogger().info("Лобби определено по адресу " + lobbyAddress + " → сервер '" + detected + "'");
            }
            transferSafely(player, target);
            return;
        }

        fallbackLocal(player);
    }

    /**
     * Безопасный перенос через прокси (fail-safe с повтором):
     *   t+2т — снять ограничения лобби, очистить эффекты, отправить Connect;
     *   t+retry — игрок всё ещё здесь → повторная отправка Connect;
     *   t+2*retry — снова неудача → сообщение «сервер недоступен» + fallback.
     * Пока идёт перенос игрок неуязвим и стоит на безопасной точке.
     */
    private void transferSafely(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        if (safeTransfer) {
            Scheduler.runSync(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                // Платформа: не даём упасть и умереть во время переключения
                player.setAllowFlight(true);
                player.setFlying(true);
                player.setInvulnerable(true);
                player.setFallDistance(0f);
                Location anchor = lobbyLocation != null && lobbyLocation.getWorld() != null
                        ? lobbyLocation
                        : player.getLocation();
                authorizeTeleport(uuid);
                player.teleport(anchor);
            });
        }
        connectWithRetry(player, serverName);
    }

    /**
     * Отправка Connect с fail-safe: первая попытка через 2 тика,
     * повтор через retry_seconds, затем уведомление игроку и fallback.
     */
    public void connectWithRetry(Player player, String serverName) {
        final UUID uuid = player.getUniqueId();
        // 2 тика — чтобы клиент успел применить состояние после авторизации
        Scheduler.runSyncLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            clearForTransfer(player);
            sendConnect(player, serverName);
        }, 2L);
        // Fail-safe №1: повторная отправка через retry_seconds
        Scheduler.runSyncLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.getLogger().info("Повторный Connect для " + player.getName()
                        + " → '" + serverName + "' (первая попытка не сработала)");
                sendConnect(player, serverName);
            }
        }, 2L + retrySeconds * 20L);
        // Fail-safe №2: повторная тоже не помогла — уведомляем и возвращаем
        Scheduler.runSyncLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.getLogger().warning("Игрок " + player.getName() + " всё ещё на этом сервере "
                    + "после двух Connect в '" + serverName + "'. Проверь имя сервера и прокси.");
            if (player instanceof Player) {
                sendMessage(player, "proxy_transfer_failed",
                        "&cЦелевой сервер временно недоступен. Попробуй перезайти позже.");
            }
            if (safeTransfer) {
                player.setInvulnerable(false);
                if (fallbackToLobbyLocation) {
                    player.setAllowFlight(false);
                    player.setFlying(false);
                }
            }
            if (fallbackToLobbyLocation) {
                fallbackLocal(player);
            }
        }, 2L + retrySeconds * 40L);
    }

    /** Снять лобби-ограничения перед Connect: эффекты темноты/слепоты, падение. */
    private void clearForTransfer(Player player) {
        try {
            me.vorchun.registerplugin.util.Compat.clearAuthDarkness(player);
            for (org.bukkit.potion.PotionEffect eff : player.getActivePotionEffects()) {
                org.bukkit.potion.PotionEffectType t = eff.getType();
                if (t == null) {
                    continue;
                }
                if (t.equals(org.bukkit.potion.PotionEffectType.BLINDNESS)
                        || t.equals(org.bukkit.potion.PotionEffectType.CONFUSION)
                        || t.getName().equalsIgnoreCase("darkness")) {
                    player.removePotionEffect(t);
                }
            }
            player.setFallDistance(0f);
        } catch (Throwable ignored) {
        }
    }

    private void sendMessage(Player player, String key, String def) {
        String text = null;
        try {
            if (plugin instanceof RegisterPlugin
                    && ((RegisterPlugin) plugin).getMessageService() != null) {
                text = ((RegisterPlugin) plugin).getMessageService().message(key);
            }
        } catch (Throwable ignored) {
        }
        if (text == null || text.isEmpty()) {
            text = org.bukkit.ChatColor.translateAlternateColorCodes('&', def);
        }
        player.sendMessage(text);
    }

    /** Одна отправка Connect на оба канала прокси (BungeeCord + Velocity-legacy). */
    private void sendConnect(Player player, String serverName) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            // "BungeeCord" работает и на Velocity (bungee-plugin-message-channel=true)
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
            plugin.getLogger().info("Игрок " + player.getName() + " → Connect '" + serverName + "'");
        } catch (Throwable t) {
            plugin.getLogger().warning("Connect для " + player.getName() + " не отправлен: " + t);
        }
        if ("velocity".equals(proxyType)) {
            try {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("Connect");
                out.writeUTF(serverName);
                player.sendPluginMessage(plugin, "velocity:player", out.toByteArray());
            } catch (Throwable ignored) {
            }
        }
    }

    /** Локальная безопасная точка: lobby-локация → сохранённая точка входа → спавн мира. */
    private void fallbackLocal(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location target = lobbyLocation;
        if (target == null || target.getWorld() == null) {
            target = savedLocations.remove(player.getUniqueId());
        }
        if (target == null || target.getWorld() == null) {
            target = player.getWorld().getSpawnLocation();
        }
        final Location dest = target;
        Scheduler.runSync(plugin, () -> {
            if (player.isOnline()) {
                authorizeTeleport(player.getUniqueId());
                player.teleport(dest);
                player.setFallDistance(0f);
            }
        });
    }

    /**
     * Перенос игрока на конкретный сервер прокси по имени.
     * Тип прокси (BungeeCord/Velocity) выбирается автоматически по конфигу.
     * Если прокси-режим выключен или имя пустое — ничего не делаем.
     */
    public void transferToServer(Player player, String serverName) {
        if (player == null || !player.isOnline() || serverName == null || serverName.isEmpty()) {
            return;
        }
        if (!proxyMode) {
            plugin.getLogger().warning("transferToServer('" + serverName + "') вызван при выключенном прокси-режиме — игрок остаётся здесь");
            return;
        }
        connectWithRetry(player, serverName);
    }

    public void checkVoidFall(Player player) {
        if (sessionManager.isLoggedIn(player.getUniqueId())) {
            return;
        }

        Location loc = player.getLocation();
        int minY = me.vorchun.registerplugin.util.Compat.getWorldMinHeight(loc.getWorld());
        if (loc.getY() < minY - 10) {
            teleportToSafeLocation(player);
        }
    }

    private Location findSafeLocation(Player player) {
        Location original = player.getLocation();
        World world = original.getWorld();
        if (world == null) {
            return null;
        }

        int x = original.getBlockX();
        int z = original.getBlockZ();
        int minHeight = me.vorchun.registerplugin.util.Compat.getWorldMinHeight(world);

        for (int y = world.getMaxHeight() - 1; y > minHeight; y--) {
            Location check = new Location(world, x, y, z);
            if (!check.getBlock().isEmpty() && check.getBlock().getType().isSolid()) {
                Location safe = check.clone().add(0.5, 1, 0.5);
                safe.setYaw(original.getYaw());
                safe.setPitch(original.getPitch());
                return safe;
            }
        }

        return world.getSpawnLocation();
    }

    public void clearSavedLocation(UUID uuid) {
        savedLocations.remove(uuid);
        authorizedTeleports.remove(uuid);
    }

    /**
     * Разрешить один ближайший телепорт игроку (для внутренних телепортов плагина:
     * мир антибот-проверки, возврат, безопасная точка).
     */
    public void authorizeTeleport(UUID uuid) {
        if (uuid == null || !ready()) {
            return;
        }
        authorizedTeleports.put(uuid, System.currentTimeMillis() + 5000L);
    }

    public boolean consumeAuthorizedTeleport(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long expiresAt = authorizedTeleports.remove(uuid);
        return expiresAt != null && expiresAt >= System.currentTimeMillis();
    }

    public boolean isProxyMode() {
        return proxyMode;
    }

    public String getProxyType() {
        return proxyType;
    }

    private static final int READY = 812196055











;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101f) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101f) == READY;
    }
}
