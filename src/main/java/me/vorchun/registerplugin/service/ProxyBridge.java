// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import me.vorchun.registerplugin.proxy.BridgeProtocol;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Серверная (Paper) сторона моста с прокси-модулем VTRegister.
 *
 * AUTH/LOGOUT уходят на прокси — он снимает запрет смены сервера и прокси-
 * команд. TRUST приходит с прокси — «игрок уже вошёл на другом сервере сети»;
 * принимается только с верной HMAC-подписью общего секрета
 * (security.proxy_bridge.secret), иначе его мог бы подделать сам клиент.
 */
public final class ProxyBridge implements PluginMessageListener {

    private final JavaPlugin plugin;
    private final BooleanSupplier proxyMode;
    private volatile Consumer<Player> onTrust;
    private volatile boolean enabled = true;
    private volatile boolean trust = true;
    private volatile String secret = "";
    private volatile boolean badMacWarned;
    private volatile Method addChannel;
    private volatile boolean addChannelResolved;

    public ProxyBridge(JavaPlugin plugin, BooleanSupplier proxyMode) {
        this.plugin = plugin;
        this.proxyMode = proxyMode;
    }

    public void start() {
        reload();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
    }

    public void stop() {
        try {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BridgeProtocol.CHANNEL);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BridgeProtocol.CHANNEL, this);
        } catch (Throwable ignored) {
        }
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("security.proxy_bridge.enabled", true);
        secret = plugin.getConfig().getString("security.proxy_bridge.secret", "");
        if (secret == null) {
            secret = "";
        }
        trust = plugin.getConfig().getBoolean("security.proxy_bridge.trust_network_login", true);
    }

    /** Колбэк принудительного входа по TRUST (ставит RegisterPlugin). */
    public void setTrustHandler(Consumer<Player> handler) {
        this.onTrust = handler;
    }

    private boolean active() {
        return enabled && proxyMode.getAsBoolean();
    }

    /**
     * Игрок вошёл — сообщаем прокси. Первый AUTH — через тик, раньше
     * Connect из TeleportService (5 тиков), иначе модуль прокси отклонит
     * перенос как «не вошёл». Повторы через 1 и 3 сек: сообщение, отправленное
     * пока прокси ещё переключает игрока на сервер, уходит клиенту мимо
     * модуля. Повтор безвреден — AUTH идемпотентен; вышедшему из аккаунта
     * повтор не шлём.
     */
    public void notifyAuth(Player p) {
        if (!active() || p == null) {
            return;
        }
        final UUID uuid = p.getUniqueId();
        Runnable r = () -> {
            if (stillLoggedIn(uuid)) {
                send(p, BridgeProtocol.AUTH);
            }
        };
        Scheduler.runAtEntityLater(plugin, p, r, 1L);
        Scheduler.runAtEntityLater(plugin, p, r, 20L);
        Scheduler.runAtEntityLater(plugin, p, r, 60L);
    }

    private boolean stillLoggedIn(UUID uuid) {
        if (plugin instanceof me.vorchun.registerplugin.RegisterPlugin) {
            SessionManager sm = ((me.vorchun.registerplugin.RegisterPlugin) plugin).getSessionManager();
            return sm == null || sm.isLoggedIn(uuid);
        }
        return true;
    }

    /**
     * Игрок вышел из аккаунта (/logout, админ). Шлём через тик и только если
     * он ещё онлайн: logout при ВЫХОДЕ с сервера (в т.ч. при переходе на
     * другой сервер сети) прокси не касается — иначе игрок, ушедший в лобби,
     * оказался бы «не вошедшим» на прокси.
     */
    public void notifyLogout(Player p) {
        if (!active() || p == null) {
            return;
        }
        final UUID uuid = p.getUniqueId();
        Scheduler.runAtEntityLater(plugin, p, () -> {
            if (p.isOnline() && !stillLoggedIn(uuid)) {
                send(p, BridgeProtocol.LOGOUT);
            }
        }, 1L);
    }

    private void send(Player p, String type) {
        if (!p.isOnline()) {
            return;
        }
        try {
            ensureChannel(p);
            p.sendPluginMessage(plugin, BridgeProtocol.CHANNEL,
                    BridgeProtocol.encode(type, p.getUniqueId(), p.getName(), secret));
        } catch (Throwable t) {
            plugin.getLogger().warning("ProxyBridge: не отправлено " + type + " для " + p.getName() + ": " + t);
        }
    }

    /**
     * Bukkit шлёт plugin message только в каналы, которые «зарегистрировал»
     * клиент. Velocity регистрирует у сервера лишь свой bungeecord-канал,
     * поэтому добавляем наш канал игроку сами (CraftPlayer#addChannel).
     */
    private void ensureChannel(Player p) {
        if (p.getListeningPluginChannels().contains(BridgeProtocol.CHANNEL)) {
            return;
        }
        if (!addChannelResolved) {
            addChannelResolved = true;
            try {
                addChannel = p.getClass().getMethod("addChannel", String.class);
            } catch (Throwable t) {
                plugin.getLogger().warning("ProxyBridge: CraftPlayer#addChannel не найден — "
                        + "сообщения прокси могут не доходить (" + t + ")");
            }
        }
        if (addChannel != null) {
            try {
                addChannel.invoke(p, BridgeProtocol.CHANNEL);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!BridgeProtocol.CHANNEL.equals(channel) || !enabled || !trust || secret.isEmpty()) {
            return;
        }
        BridgeProtocol.Msg m = BridgeProtocol.decode(data);
        if (m == null || !BridgeProtocol.TRUST.equals(m.type)) {
            return;
        }
        if (!player.getUniqueId().equals(m.uuid) || !player.getName().equalsIgnoreCase(m.name)
                || !BridgeProtocol.verify(m, secret, System.currentTimeMillis())) {
            if (!badMacWarned) {
                badMacWarned = true;
                plugin.getLogger().warning("ProxyBridge: отклонён TRUST для " + player.getName()
                        + " — неверная подпись/время (разный secret на прокси и сервере, "
                        + "расхождение часов или подделка клиентом)");
            }
            return;
        }
        Consumer<Player> h = onTrust;
        if (h != null) {
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    h.accept(player);
                }
            });
        }
    }
}
