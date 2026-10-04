// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.Scheduler;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReminderService {

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final MessageService messages;

    private final Map<UUID, Long> nextSendAtMillis = new ConcurrentHashMap<>();
    private me.vorchun.registerplugin.util.Scheduler.Task ticker;

    private volatile boolean cachedEnabled;
    private volatile boolean cachedSendChat;
    private volatile int cachedIntervalSeconds;
    /**
     * Тексты напоминаний по языку (ru/en): при language: auto каждый игрок
     * получает свой язык. Кэш строится лениво и сбрасывается в reload.
     */
    private final Map<String, Texts> cachedTexts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextChatAtMillis = new ConcurrentHashMap<>();
    /** Ждём ли пароль следующим сообщением (подсказка «Введи пароль»). Ставит RegisterPlugin. */
    private volatile java.util.function.Predicate<UUID> awaitingPassword = u -> false;

    public void setAwaitingPassword(java.util.function.Predicate<UUID> p) {
        awaitingPassword = p == null ? u -> false : p;
    }
    private volatile long cachedChatIntervalMillis = 10_000L;

    private static final class Texts {
        final BaseComponent[] loginComponents;
        final BaseComponent[] registerComponents;
        final String loginBar;
        final String registerBar;
        final String loginChat;
        final String registerChat;
        /** Подсказки по центру экрана: [заголовок, подзаголовок] для входа/регистрации/ввода пароля. */
        String[] hintLogin;
        String[] hintRegister;
        String[] hintPassword;

        Texts(String loginBar, String registerBar, String loginChat, String registerChat) {
            this.loginBar = loginBar;
            this.registerBar = registerBar;
            // компоненты собираем один раз, если в тексте нет {player}
            this.loginComponents = components(loginBar);
            this.registerComponents = components(registerBar);
            this.loginChat = loginChat;
            this.registerChat = registerChat;
        }

        private static BaseComponent[] components(String s) {
            return (s == null || s.isEmpty() || s.contains(PLAYER_TAG))
                    ? null : TextComponent.fromLegacyText(s);
        }
    }

    public ReminderService(JavaPlugin plugin, AccountStore accountStore, SessionManager sessionManager, MessageService messages) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.messages = messages;
    }

    public boolean isEnabled() {
        return cachedEnabled;
    }

    public int getIntervalSeconds() {
        return cachedIntervalSeconds;
    }

    public void reload() {
        cachedEnabled = plugin.getConfig().getBoolean("auth.reminder.enabled", true);
        cachedSendChat = plugin.getConfig().getBoolean("auth.reminder.send_chat", true);
        int sec = plugin.getConfig().getInt("auth.reminder.interval_seconds", 3);
        cachedIntervalSeconds = Math.max(1, sec);
        // Чат — реже actionbar: каждые 2 сек в чат заливали инструкции выше
        // (игрок не видел «Шаг 2: введи пароль»). 0 = как interval_seconds.
        int chatSec = plugin.getConfig().getInt("auth.reminder.chat_interval_seconds", 10);
        cachedChatIntervalMillis = (chatSec <= 0 ? cachedIntervalSeconds : Math.max(1, chatSec)) * 1000L;
        me.vorchun.registerplugin.util.ScreenHints.load(plugin.getConfig());

        cachedTexts.clear();

        if (!cachedEnabled && !me.vorchun.registerplugin.util.ScreenHints.auth()) {
            nextSendAtMillis.clear();
            nextChatAtMillis.clear();
            stopTicker();
        }
    }

    /** {player} не подставляем: кэш общий на язык, имя одного игрока не «запекается». */
    private static final String PLAYER_TAG = "{player}";
    private static final Map<String, String> KEEP_PLAYER =
            java.util.Collections.singletonMap("player", PLAYER_TAG);

    /** Тексты на языке игрока (кэш по коду языка). */
    private Texts textsFor(Player p) {
        String lang = messages.languageOf(p);
        Texts t = cachedTexts.get(lang);
        if (t != null) {
            return t;
        }
        String loginMsg = messages.message(p, "reminder_login_actionbar", KEEP_PLAYER);
        String registerMsg = messages.message(p, "reminder_register_actionbar", KEEP_PLAYER);
        String loginChat = messages.message(p, "reminder_login_chat", KEEP_PLAYER);
        String registerChat = messages.message(p, "reminder_register_chat", KEEP_PLAYER);
        t = new Texts(loginMsg, registerMsg, loginChat, registerChat);
        t.hintLogin = new String[]{messages.message(p, "hint_login_title", KEEP_PLAYER),
                messages.message(p, "hint_login_subtitle", KEEP_PLAYER)};
        t.hintRegister = new String[]{messages.message(p, "hint_register_title", KEEP_PLAYER),
                messages.message(p, "hint_register_subtitle", KEEP_PLAYER)};
        t.hintPassword = new String[]{messages.message(p, "hint_password_title", KEEP_PLAYER),
                messages.message(p, "hint_password_subtitle", KEEP_PLAYER)};
        cachedTexts.put(lang, t);
        return t;
    }

    public void start(Player player) {
        UUID uuid = player.getUniqueId();
        stop(uuid);

        if (!isEnabled() && !me.vorchun.registerplugin.util.ScreenHints.auth()) {
            return;
        }

        long nextAt = System.currentTimeMillis();
        nextSendAtMillis.put(uuid, nextAt);
        ensureTicker();
    }

    public void stop(UUID uuid) {
        nextSendAtMillis.remove(uuid);
        nextChatAtMillis.remove(uuid);
        me.vorchun.registerplugin.util.ScreenHints.forget(uuid);
        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
        }
    }

    public void stop(Player player) {
        stop(player.getUniqueId());
    }

    private synchronized void ensureTicker() {
        if (ticker != null) {
            return;
        }
        ticker = Scheduler.runSyncTimer(plugin, this::tick, 1L, 20L);
    }

    private synchronized void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
            return;
        }

        if (!isEnabled() && !me.vorchun.registerplugin.util.ScreenHints.auth()) {
            nextSendAtMillis.clear();
            stopTicker();
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMillis = (long) cachedIntervalSeconds * 1000L;

        boolean sendChat = cachedSendChat;

        for (Map.Entry<UUID, Long> e : nextSendAtMillis.entrySet()) {
            UUID uuid = e.getKey();
            Long nextAt = e.getValue();
            if (nextAt == null || now < nextAt) {
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                nextSendAtMillis.remove(uuid);
                nextChatAtMillis.remove(uuid);
                continue;
            }

            if (sessionManager.isLoggedIn(uuid)) {
                nextSendAtMillis.remove(uuid);
                nextChatAtMillis.remove(uuid);
                continue;
            }

            Texts tx = textsFor(p);
            boolean registered = accountStore.isRegistered(uuid);
            BaseComponent[] components = registered ? tx.loginComponents : tx.registerComponents;
            if (components == null) {
                // текст с {player} — подставляем имя этого игрока (редкий путь)
                String bar = registered ? tx.loginBar : tx.registerBar;
                if (bar != null && !bar.isEmpty()) {
                    components = TextComponent.fromLegacyText(bar.replace(PLAYER_TAG, p.getName()));
                }
            }
            if (cachedEnabled && components != null && components.length > 0) {
                me.vorchun.registerplugin.util.Compat.sendActionBar(p, components);
            }
            if (me.vorchun.registerplugin.util.ScreenHints.auth()) {
                // Крупная надпись по центру: что делать сейчас (screen_hints)
                boolean pw = awaitingPassword.test(uuid);
                String[] h = pw ? tx.hintPassword : registered ? tx.hintLogin : tx.hintRegister;
                if (h != null && h[0] != null) {
                    me.vorchun.registerplugin.util.ScreenHints.show(p, pw ? "password" : registered ? "login" : "register",
                            h[0].replace(PLAYER_TAG, p.getName()), h[1] == null ? "" : h[1].replace(PLAYER_TAG, p.getName()));
                }
            }

            Long chatAt = nextChatAtMillis.get(uuid);
            if (cachedEnabled && sendChat && (chatAt == null || now >= chatAt)) {
                nextChatAtMillis.put(uuid, now + cachedChatIntervalMillis);
                String chatMsg = registered ? tx.loginChat : tx.registerChat;
                if (chatMsg != null && !chatMsg.isEmpty()) {
                    p.sendMessage(chatMsg.indexOf('{') >= 0
                            ? chatMsg.replace(PLAYER_TAG, p.getName()) : chatMsg);
                }
            }

            nextSendAtMillis.put(uuid, now + intervalMillis);
        }

        if (nextSendAtMillis.isEmpty()) {
            stopTicker();
        }
    }

    private static final int READY = -1251988148;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x101b) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x101b) == READY;
    }
}
