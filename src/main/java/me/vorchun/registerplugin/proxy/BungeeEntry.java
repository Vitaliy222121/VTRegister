// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.proxy;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import me.vorchun.registerplugin.util.Data;

/**
 * Точка входа VTRegister на BungeeCord/Waterfall (bungee.yml). Логика та же,
 * что у Velocity — общий {@link ProxyCore}.
 */
public final class BungeeEntry extends Plugin implements Listener {

    private ProxyCore core;
    private boolean spoofWarned;

    @Override
    public void onEnable() {
        if (!Data.sealed()) {
            getLogger().severe("VTRegister: сборка повреждена или изменена — прокси-модуль не запущен.");
            return;
        }
        core = new ProxyCore(new ProxyCore.Platform() {
            @Override
            public void info(String msg) {
                getLogger().info(msg);
            }

            @Override
            public void warn(String msg) {
                getLogger().warning(msg);
            }

            @Override
            public File dataDir() {
                return getDataFolder();
            }

            @Override
            public InputStream resource(String name) {
                return BungeeEntry.class.getResourceAsStream("/" + name);
            }
        });
        core.load();
        core.loadVerified();
        checkConfig();
        getProxy().getScheduler().schedule(this, this::checkConfig, 60, 60, TimeUnit.SECONDS);
        getProxy().registerChannel(BridgeProtocol.CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getScheduler().schedule(this, core::cleanup, 30, 30, TimeUnit.SECONDS);
        getLogger().info("VTRegister: прокси-модуль BungeeCord запущен");
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.saveVerified();
        }
    }

    private static BaseComponent[] text(String legacy) {
        return TextComponent.fromLegacyText(legacy);
    }

    @SuppressWarnings("deprecation")
    private static String ip(InetSocketAddress a) {
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPing(net.md_5.bungee.api.event.ProxyPingEvent e) {
        if (core != null) {
            core.onPing(ip(e.getConnection().getAddress()));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(PreLoginEvent e) {
        if (core == null || e.isCancelled()) {
            return;
        }
        String kick = core.checkConnection(ip(e.getConnection().getAddress()), System.currentTimeMillis());
        if (kick != null) {
            e.setCancelled(true);
            e.setCancelReason(text(kick));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPostLogin(PostLoginEvent e) {
        if (core != null) {
            core.onJoin(e.getPlayer().getUniqueId(), ip(e.getPlayer().getAddress()));
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent e) {
        if (core != null) {
            core.onQuit(e.getPlayer().getUniqueId(), ip(e.getPlayer().getAddress()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerConnect(ServerConnectEvent e) {
        if (core == null || e.isCancelled() || e.getTarget() == null) {
            return;
        }
        ProxiedPlayer p = e.getPlayer();
        if (core.isAuthed(p.getUniqueId())) {
            return;
        }
        boolean switching = p.getServer() != null;
        String route = core.routeUnauthed(e.getTarget().getName(), switching);
        if (route == ProxyCore.ALLOW) {
            return;
        }
        if (route == ProxyCore.DENY) {
            e.setCancelled(true);
            p.sendMessage(text(core.msg("switch_denied")));
            return;
        }
        for (String name : core.authServers()) {
            ServerInfo s = getProxy().getServerInfo(name);
            if (s != null) {
                e.setTarget(s);
                return;
            }
        }
        // Серверов входа нет на прокси (validate уже кричит в консоль) — не кикаем
    }

    /** Сверка auth_servers/after_login_server с серверами из config.yml BungeeCord. */
    private void checkConfig() {
        if (core == null) {
            return;
        }
        String problem = core.validate(getProxy().getServers().keySet());
        if (problem != null) {
            core.spamProblem(problem);
        }
    }

    /**
     * Не вошедшего кикнули с сервера входа (антибот, таймаут, выключение) —
     * не перекидываем на фолбэк (модуль всё равно запретит смену сервера),
     * а отключаем с причиной сервера.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(ServerKickEvent e) {
        if (core == null || e.getKickedFrom() == null || core.isAuthed(e.getPlayer().getUniqueId())
                || !core.isAuthServer(e.getKickedFrom().getName())) {
            return;
        }
        e.setCancelled(false);
    }

    @EventHandler
    public void onSwitch(ServerSwitchEvent e) {
        if (core == null || core.secret().isEmpty()) {
            return;
        }
        ProxiedPlayer p = e.getPlayer();
        if (core.isAuthed(p.getUniqueId()) && p.getServer() != null) {
            p.getServer().sendData(BridgeProtocol.CHANNEL, BridgeProtocol.encode(
                    BridgeProtocol.TRUST, p.getUniqueId(), p.getName(), core.secret()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent e) {
        if (core == null || e.isCancelled() || !e.isCommand() || !(e.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) e.getSender();
        if (core.isAuthed(p.getUniqueId())) {
            return;
        }
        // Команды бэкенда (/login, /reg...) пропускаем — их решает VTRegister на сервере
        if (!e.isProxyCommand() || core.isAllowedCommand(ProxyCore.label(e.getMessage()))) {
            return;
        }
        e.setCancelled(true);
        p.sendMessage(text(core.msg("command_denied")));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginMessage(PluginMessageEvent e) {
        if (!BridgeProtocol.CHANNEL.equals(e.getTag())) {
            return;
        }
        // Наш канал никогда не пересылаем дальше — ни клиенту, ни серверу
        e.setCancelled(true);
        if (core == null) {
            return;
        }
        if (!(e.getSender() instanceof Server) || !(e.getReceiver() instanceof ProxiedPlayer)) {
            if (!spoofWarned) {
                spoofWarned = true;
                getLogger().warning("VTRegister: клиент прислал сообщение в канал моста — отброшено (попытка подделки)");
            }
            return;
        }
        ProxiedPlayer p = (ProxiedPlayer) e.getReceiver();
        BridgeProtocol.Msg m = BridgeProtocol.decode(e.getData());
        if (m == null || !p.getUniqueId().equals(m.uuid)) {
            return;
        }
        if (!core.secret().isEmpty() && !BridgeProtocol.verify(m, core.secret(), System.currentTimeMillis())) {
            core.warnBadMac();
            return;
        }
        if (BridgeProtocol.AUTH.equals(m.type)) {
            core.markAuthed(p.getUniqueId(), ip(p));
            sendAfterLogin(p);
        } else if (BridgeProtocol.LOGOUT.equals(m.type)) {
            core.markLogout(p.getUniqueId());
        }
    }

    @SuppressWarnings("deprecation")
    private static String ip(ProxiedPlayer p) {
        return ip(p.getAddress());
    }

    private void sendAfterLogin(ProxiedPlayer p) {
        if (core.afterLoginServers().isEmpty()) {
            return;
        }
        if (p.getServer() != null && !core.isAuthServer(p.getServer().getInfo().getName())) {
            return;
        }
        final UUID id = p.getUniqueId();
        getProxy().getScheduler().schedule(this, () -> connectChain(id, 0), 300, TimeUnit.MILLISECONDS);
    }

    /** По списку after_login_server: первый, куда удалось подключиться. */
    private void connectChain(UUID id, int i) {
        List<String> names = core.afterLoginServers();
        ProxiedPlayer p = getProxy().getPlayer(id);
        if (i >= names.size() || p == null) {
            return;
        }
        ServerInfo s = getProxy().getServerInfo(names.get(i));
        if (s == null || (p.getServer() != null && p.getServer().getInfo().equals(s))) {
            connectChain(id, i + 1);
            return;
        }
        p.connect(s, (ok, err) -> {
            if (ok == null || !ok) {
                connectChain(id, i + 1);
            }
        });
    }
}
