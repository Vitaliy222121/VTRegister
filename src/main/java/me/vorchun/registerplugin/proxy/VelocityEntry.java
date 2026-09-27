// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.proxy;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import me.vorchun.registerplugin.util.Data;

/**
 * Точка входа VTRegister на Velocity (velocity-plugin.json). Сама авторизация
 * живёт на бэкенде (Paper); здесь — запрет прокси-команд и смены сервера до
 * входа, маршрут после входа, перенос входа между серверами и лимиты
 * подключений по реальным IP.
 */
public final class VelocityEntry {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private ProxyCore core;
    private boolean spoofWarned;

    @Inject
    public VelocityEntry(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        if (!Data.sealed()) {
            logger.error("VTRegister: сборка повреждена или изменена — прокси-модуль не запущен.");
            return;
        }
        core = new ProxyCore(new ProxyCore.Platform() {
            @Override
            public void info(String msg) {
                logger.info(msg);
            }

            @Override
            public void warn(String msg) {
                logger.warn(msg);
            }

            @Override
            public File dataDir() {
                return dataDir.toFile();
            }

            @Override
            public InputStream resource(String name) {
                return VelocityEntry.class.getResourceAsStream("/" + name);
            }
        });
        core.load();
        core.loadVerified();
        checkConfig();
        // Серверы на прокси могут добавляться на лету — перепроверяем раз в минуту
        proxy.getScheduler().buildTask(this, this::checkConfig).delay(60, TimeUnit.SECONDS)
                .repeat(60, TimeUnit.SECONDS).schedule();
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getScheduler().buildTask(this, core::cleanup).repeat(30, TimeUnit.SECONDS).schedule();
        logger.info("VTRegister: прокси-модуль Velocity запущен");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (core != null) {
            core.saveVerified();
        }
    }

    private static Component text(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private static String ip(Player p) {
        InetSocketAddress a = p.getRemoteAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    @Subscribe
    public void onPing(com.velocitypowered.api.event.proxy.ProxyPingEvent e) {
        if (core != null) {
            InetSocketAddress a = e.getConnection().getRemoteAddress();
            core.onPing(a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress());
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPreLogin(PreLoginEvent e) {
        if (core == null || !e.getResult().isAllowed()) {
            return;
        }
        InetSocketAddress a = e.getConnection().getRemoteAddress();
        String ip = a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
        String kick = core.checkConnection(ip, System.currentTimeMillis());
        if (kick != null) {
            e.setResult(PreLoginEvent.PreLoginComponentResult.denied(text(kick)));
        }
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent e) {
        if (core != null) {
            core.onJoin(e.getPlayer().getUniqueId(), ip(e.getPlayer()));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent e) {
        if (core != null) {
            core.onQuit(e.getPlayer().getUniqueId(), ip(e.getPlayer()));
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent e) {
        if (core == null || !e.getResult().isAllowed()) {
            return;
        }
        Player p = e.getPlayer();
        if (core.isAuthed(p.getUniqueId())) {
            return;
        }
        Optional<RegisteredServer> target = e.getResult().getServer();
        if (!target.isPresent()) {
            return;
        }
        boolean switching = p.getCurrentServer().isPresent();
        String route = core.routeUnauthed(target.get().getServerInfo().getName(), switching);
        if (route == ProxyCore.ALLOW) {
            return;
        }
        if (route == ProxyCore.DENY) {
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
            p.sendMessage(text(core.msg("switch_denied")));
            return;
        }
        for (String name : core.authServers()) {
            Optional<RegisteredServer> s = proxy.getServer(name);
            if (s.isPresent()) {
                e.setResult(ServerPreConnectEvent.ServerResult.allowed(s.get()));
                return;
            }
        }
        // Серверов входа нет на прокси (validate уже кричит в консоль) — не кикаем,
        // игрок идёт туда, куда его шлёт прокси; смена сервера до входа всё равно запрещена.
    }

    /** Сверка auth_servers/after_login_server с серверами из velocity.toml. */
    private void checkConfig() {
        if (core == null) {
            return;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (RegisteredServer s : proxy.getAllServers()) {
            names.add(s.getServerInfo().getName());
        }
        String problem = core.validate(names);
        if (problem != null) {
            core.spamProblem(problem);
        }
    }

    /**
     * Не вошедшего кикнули с сервера входа (антибот, таймаут, выключение) —
     * отключаем от прокси с причиной сервера, а не перекидываем на фолбэк:
     * иначе прокси пытается увести его в лобби, модуль это запрещает, и
     * игрок остаётся «между серверами».
     */
    @Subscribe(order = PostOrder.LAST)
    public void onKicked(KickedFromServerEvent e) {
        if (core == null || core.isAuthed(e.getPlayer().getUniqueId())
                || !core.isAuthServer(e.getServer().getServerInfo().getName())) {
            return;
        }
        Component reason = e.getServerKickReason().orElse(text(core.msg("no_auth_server")));
        e.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent e) {
        if (core == null || core.secret().isEmpty()) {
            return;
        }
        Player p = e.getPlayer();
        if (!core.isAuthed(p.getUniqueId())) {
            return;
        }
        p.getCurrentServer().ifPresent(sc -> sc.sendPluginMessage(CHANNEL,
                BridgeProtocol.encode(BridgeProtocol.TRUST, p.getUniqueId(), p.getUsername(), core.secret())));
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent e) {
        if (core == null || !(e.getCommandSource() instanceof Player) || !e.getResult().isAllowed()) {
            return;
        }
        Player p = (Player) e.getCommandSource();
        if (core.isAuthed(p.getUniqueId())) {
            return;
        }
        String label = ProxyCore.label(e.getCommand());
        // Не прокси-команда (/login, /reg...) — уходит на бэкенд как обычно
        if (label.isEmpty() || !proxy.getCommandManager().hasCommand(label) || core.isAllowedCommand(label)) {
            return;
        }
        e.setResult(CommandExecuteEvent.CommandResult.denied());
        p.sendMessage(text(core.msg("command_denied")));
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getIdentifier())) {
            return;
        }
        // Наш канал никогда не пересылаем дальше — ни клиенту, ни серверу
        e.setResult(PluginMessageEvent.ForwardResult.handled());
        if (core == null) {
            return;
        }
        if (!(e.getSource() instanceof ServerConnection)) {
            if (!spoofWarned) {
                spoofWarned = true;
                logger.warn("VTRegister: клиент прислал сообщение в канал моста — отброшено (попытка подделки)");
            }
            return;
        }
        Player p = ((ServerConnection) e.getSource()).getPlayer();
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

    private void sendAfterLogin(Player p) {
        if (core.afterLoginServers().isEmpty()) {
            return;
        }
        Optional<ServerConnection> cur = p.getCurrentServer();
        if (cur.isPresent() && !core.isAuthServer(cur.get().getServerInfo().getName())) {
            return;
        }
        final UUID id = p.getUniqueId();
        // Небольшая задержка: бэкенд успевает сохранить данные игрока
        proxy.getScheduler().buildTask(this, () -> connectChain(id, 0))
                .delay(300, TimeUnit.MILLISECONDS).schedule();
    }

    /** По списку after_login_server: первый, куда удалось подключиться. */
    private void connectChain(UUID id, int i) {
        java.util.List<String> names = core.afterLoginServers();
        Optional<Player> pl = proxy.getPlayer(id);
        if (i >= names.size() || !pl.isPresent()) {
            return;
        }
        Optional<RegisteredServer> s = proxy.getServer(names.get(i));
        if (!s.isPresent() || pl.get().getCurrentServer()
                .map(c -> c.getServer().equals(s.get())).orElse(false)) {
            connectChain(id, i + 1);
            return;
        }
        pl.get().createConnectionRequest(s.get()).connect().whenComplete((r, t) -> {
            if (t != null || r == null || !r.isSuccessful()) {
                connectChain(id, i + 1);
            }
        });
    }
}
