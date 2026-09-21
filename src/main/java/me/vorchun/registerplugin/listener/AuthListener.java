// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.listener;

import me.vorchun.registerplugin.util.Scheduler;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.AccountRecord;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.AntiBotService;
import me.vorchun.registerplugin.service.AuthTimeoutService;
import me.vorchun.registerplugin.service.BedrockSupportService;
import me.vorchun.registerplugin.service.EasyPasswordList;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.ReminderService;
import me.vorchun.registerplugin.service.SessionManager;
import me.vorchun.registerplugin.service.TeleportService;
import me.vorchun.registerplugin.util.Compat;

public final class AuthListener implements Listener {

    private enum PasswordMode {
        LOGIN,
        REGISTER,
        REGISTER_CONFIRM,
        CHANGE_OLD,
        CHANGE_NEW,
        CHANGE_CONFIRM
    }

    private static final class PendingPassword {
        final PasswordMode mode;
        final long expiresAtMillis;
        final String data;

        PendingPassword(PasswordMode mode, long expiresAtMillis) {
            this(mode, expiresAtMillis, null);
        }

        PendingPassword(PasswordMode mode, long expiresAtMillis, String data) {
            this.mode = mode;
            this.expiresAtMillis = expiresAtMillis;
            this.data = data;
        }
    }

    private final JavaPlugin plugin;
    private final AccountStore accountStore;
    private final SessionManager sessionManager;
    private final AuthTimeoutService timeoutService;
    private final ReminderService reminderService;
    private final MessageService messages;
    private final TeleportService teleportService;
    private final BedrockSupportService bedrockSupportService;
    private final AntiBotService antiBotService;
    private final EasyPasswordList easyPasswordList;

    private me.vorchun.registerplugin.service.AuthService authService;
    private me.vorchun.registerplugin.service.TotpService totpService;
    private me.vorchun.registerplugin.service.MailService mailService;
    private me.vorchun.registerplugin.service.PremiumService premiumService;
    private me.vorchun.registerplugin.service.SpawnService spawnService;
    private me.vorchun.registerplugin.service.AfkService afkService;

    /** Подтверждение рискованной команды с паролем: первый раз блокируем, второй — принимаем. */
    private static final class RiskyCommand {
        final String command;
        final String password;
        final long expiresAt;

        RiskyCommand(String command, String password, long expiresAt) {
            this.command = command;
            this.password = password;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, RiskyCommand> riskyCommands = new ConcurrentHashMap<>();
    private final Map<UUID, String> pending2faPassword = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingEmailCode = new ConcurrentHashMap<>();

    private volatile int riskyConfirmSeconds = 30;
    private volatile boolean riskyConfirmEnabled = true;

    private final Map<UUID, PendingPassword> awaitingPassword = new ConcurrentHashMap<>();

    private volatile Set<String> allowedCommandsCache = Collections.emptySet();
    private volatile boolean secureMode = true;
    private volatile boolean hideDuringAuth = true;
    // always|auth_only|never - scope of darkness/blindness effect
    private volatile String authDarkness = "auth_only";
    private volatile boolean requireConfirm = false;
    private volatile boolean enforceStrength = true;
    private volatile boolean protectDeath = true;
    private volatile int passwordInputTimeoutSec = 25;

    public AuthListener(JavaPlugin plugin,
                        AccountStore accountStore,
                        SessionManager sessionManager,
                        AuthTimeoutService timeoutService,
                        ReminderService reminderService,
                        MessageService messages,
                        TeleportService teleportService,
                        BedrockSupportService bedrockSupportService,
                        AntiBotService antiBotService,
                        EasyPasswordList easyPasswordList) {
        this.plugin = plugin;
        this.accountStore = accountStore;
        this.sessionManager = sessionManager;
        this.timeoutService = timeoutService;
        this.reminderService = reminderService;
        this.messages = messages;
        this.teleportService = teleportService;
        this.bedrockSupportService = bedrockSupportService;
        this.antiBotService = antiBotService;
        this.easyPasswordList = easyPasswordList;

        reload();
    }

    /** Внедрение сервисов, создаваемых после конструктора (разрывает цикл зависимостей). */
    public void setServices(me.vorchun.registerplugin.service.AuthService authService,
                            me.vorchun.registerplugin.service.TotpService totpService,
                            me.vorchun.registerplugin.service.MailService mailService,
                            me.vorchun.registerplugin.service.PremiumService premiumService,
                            me.vorchun.registerplugin.service.SpawnService spawnService) {
        this.authService = authService;
        this.totpService = totpService;
        this.mailService = mailService;
        this.premiumService = premiumService;
        this.spawnService = spawnService;
    }

    public void setAfkService(me.vorchun.registerplugin.service.AfkService afkService) {
        this.afkService = afkService;
    }

    public void reload() {
        riskyConfirmEnabled = plugin.getConfig().getBoolean("security.confirm_password_in_command", true);
        riskyConfirmSeconds = Math.max(5, plugin.getConfig().getInt("security.confirm_timeout_seconds", 30));
        List<String> allowed = plugin.getConfig().getStringList("allowed_commands_unauthorized");
        Set<String> set = new HashSet<>();
        if (allowed == null || allowed.isEmpty()) {
            set.add("login");
            set.add("l");
            set.add("register");
            set.add("reg");
        } else {
            for (String s : allowed) {
                if (s != null && !s.isEmpty()) {
                    String v = s;
                    if (v.startsWith("/")) {
                        v = v.substring(1);
                    }
                    set.add(v.toLowerCase(Locale.ROOT));
                }
            }
        }
        allowedCommandsCache = Collections.unmodifiableSet(set);

        secureMode = plugin.getConfig().getBoolean("security.secure_password_input", true);
        hideDuringAuth = plugin.getConfig().getBoolean("security.hide_during_auth", true);
        authDarkness = plugin.getConfig().getString("security.auth_darkness", "auth_only");
        requireConfirm = plugin.getConfig().getBoolean("password.require_confirm", false);
        enforceStrength = plugin.getConfig().getBoolean("password.enforce_strength", true);
        protectDeath = plugin.getConfig().getBoolean("security.protect_inventory_on_death", true);
        int sec = plugin.getConfig().getInt("security.password_input_timeout_seconds", 25);
        passwordInputTimeoutSec = Math.max(5, Math.min(120, sec));
    }

    public boolean isSecureMode() {
        return secureMode;
    }

    public boolean isAwaitingPassword(UUID uuid) {
        return awaitingPassword.containsKey(uuid);
    }

    public EasyPasswordList getEasyPasswordList() {
        return easyPasswordList;
    }

    public boolean isEnforceStrength() {
        return enforceStrength;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (afkService != null) {
            afkService.onJoin(p);
        }

        teleportService.saveJoinLocation(p);

        // Синхронизируем видимость: скрываем всех неавторизованных от нового игрока
        syncHidingFor(p);

        if (bedrockSupportService != null && bedrockSupportService.shouldBypassAuth(p)) {
            sessionManager.login(p);
            timeoutService.stop(p);
            reminderService.stop(p);
            Compat.updateCommands(p);
            Compat.clearAuthDarkness(p);
            Scheduler.runSync(plugin, () -> messages.send(p, "join_bedrock_auto_login"));
            return;
        }

        // Изоляция ДО любой логики: темнота + пакетное скрытие. Если проверка
        // не стартует (рестарт, мир не готов, сбой) — игрок не видит мир
        // и не виден другим, а не стоит в обычном мире с вещами.
        darkness(p);
        if (hideDuringAuth) {
            applyHiding(p);
        }

        boolean premiumPending = premiumService != null && premiumService.isEnabled()
                && accountStore.isRegistered(uuid);
        boolean antibotNow = !premiumPending && antiBotService != null
                && antiBotService.isEnabled() && !antiBotService.isChecking(uuid)
                && !(antiBotService.isOnlyNewPlayers() && accountStore.isRegistered(uuid));

        // Антибот забирает игрока СРАЗУ — телепорт в мир проверки на первом же тике,
        // без промежуточного прелогин-спавна.
        if (antibotNow) {
            boolean started = false;
            try {
                started = antiBotService.beginCheck(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: beginCheck failed for "
                        + p.getName() + ": " + t);
            }
            if (started) {
                sendModeIndicator(p);
                return;
            }
        }

        // Безопасная зона до авторизации (если настроена)
        if (spawnService != null) {
            spawnService.teleportPrelogin(p);
        }
        // Прелогин-точка не задана — держим неавторизованного на платформе
        // проверки, чтобы он (особенно новый аккаунт) не стоял в мире.
        if ((spawnService == null || !spawnService.hasPrelogin())
                && antiBotService != null && antiBotService.isEnabled()) {
            Location hold = antiBotService.holdingSpot();
            if (hold != null) {
                teleportService.authorizeTeleport(uuid);
                Scheduler.runAtEntity(plugin, p, () -> {
                    if (p.isOnline()) {
                        p.teleport(hold);
                    }
                });
            }
        }

        // Премиум-автологин: лицензионный игрок входит без пароля
        if (premiumPending) {
            // Пока идёт запрос к Mojang — держим игрока как обычно (темнота + скрытие),
            // чтобы он не гулял по миру до решения
            darkness(p);
            if (hideDuringAuth) {
                applyHiding(p);
            }
            premiumService.check(p, premium -> {
                if (premium && p.isOnline() && !sessionManager.isLoggedIn(uuid)) {
                    sessionManager.login(p);
                    if (authService != null) {
                        authService.completeLogin(p, "");
                    }
                    timeoutService.stop(p);
                    reminderService.stop(p);
                    Compat.updateCommands(p);
                    Compat.clearAuthDarkness(p);
                    revealPlayer(p);
                    if (spawnService != null) {
                        spawnService.teleportAfterLogin(p, false);
                    }
                    messages.send(p, "join_premium_auto_login");
                } else {
                    startAntiBotOrAuth(p, uuid);
                }
            });
            return;
        }

        startAntiBotOrAuth(p, uuid);
    }

    /**
     * Проверка на бота (если включена) либо сразу обычный вход.
     * Напоминания и общий таймаут авторизации НЕ включаются, пока идёт проверка —
     * иначе игрок получал бы спам «введи /reg», стоя в мире проверки.
     */
    private void startAntiBotOrAuth(Player p, UUID uuid) {
        if (antiBotService != null && antiBotService.isEnabled() && !antiBotService.isChecking(uuid)) {
            boolean required = !(antiBotService.isOnlyNewPlayers() && accountStore.isRegistered(uuid))
                    || antiBotService.requiresRestartRecheck(uuid);
            boolean started = false;
            try {
                started = required && antiBotService.beginCheck(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: beginCheck failed: " + t);
            }
            if (started) {
                sendModeIndicator(p);
                return;
            }
        }
        continueAuthFlow(p, uuid);
    }

    /** Обычный вход: инструкции + таймаут + напоминания. */
    private void continueAuthFlow(Player p, UUID uuid) {
        if (!accountStore.isRegistered(uuid)) {
            darkness(p);
            if (hideDuringAuth) {
                applyHiding(p);
            }
            sendModeIndicator(p);
            Scheduler.runSync(plugin, () -> {
                messages.send(p, "join_need_register");
                messages.sendList(p, "custom_instructions");
                messages.sendList(p, "custom_instructions_register");
            });
            timeoutService.start(p);
            reminderService.start(p);
            return;
        }

        if (sessionManager.canAutoLogin(p)) {
            sessionManager.login(p);
            timeoutService.stop(p);
            reminderService.stop(p);
            Compat.updateCommands(p);
            Compat.clearAuthDarkness(p);
            revealPlayer(p);
            Scheduler.runSync(plugin, () -> messages.send(p, "join_auto_login"));
            return;
        }

        darkness(p);
        if (hideDuringAuth) {
            applyHiding(p);
        }
        sendModeIndicator(p);
        Scheduler.runSync(plugin, () -> {
            messages.send(p, "join_need_login");
            messages.sendList(p, "custom_instructions");
            messages.sendList(p, "custom_instructions_login");
        });
        timeoutService.start(p);
        reminderService.start(p);
    }

    /**
     * Индикатор режима авторизации — показывается игроку ВСЕГДА.
     * Если админ очищает ключ в конфиге, используется встроенный текст.
     */
    private void sendModeIndicator(Player p) {
        if (secureMode) {
            messages.sendOrDefault(p, "auth_mode_secure",
                    "{prefix}&#A0FFA0Режим авторизации: ЗАЩИЩЁННЫЙ &#7F7F7F— пароль вводится в чат и не светится в логах");
        } else {
            messages.sendOrDefault(p, "auth_mode_insecure",
                    "{prefix}&#FF6666Режим авторизации: НЕЗАЩИЩЁННЫЙ &#7F7F7F— пароль вводится в команду и будет виден в консоли и логах сервера!");
        }
    }

    /**
     * Вызывается AntiBotService, когда игрок прошёл все этапы проверки.
     * Продолжаем стандартный флоу авторизации.
     */
    public void onAntiBotPassed(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        UUID uuid = p.getUniqueId();
        messages.sendOrDefault(p, "antibot_passed",
                "{prefix}&#A0FFA0Проверка на бота пройдена!");
        if (sessionManager.isLoggedIn(uuid)) {
            return;
        }
        // Только теперь включаем таймаут и напоминания — во время проверки игрок
        // не должен получать спам «введи /reg».
        continueAuthFlow(p, uuid);
    }

    /**
     * Публичная обёртка: применить скрытие, если оно включено в конфиге.
     */
    public void reapplyHidingIfEnabled(Player unauth) {
        if (hideDuringAuth && unauth != null && !sessionManager.isLoggedIn(unauth.getUniqueId())) {
            applyHiding(unauth);
        }
    }

    /**
     * Скрыть неавторизованного игрока от всех и всех от него.
     */
    // Darkness per security.auth_darkness: never -> never applied
    private void darkness(Player p) {
        if (!"never".equals(authDarkness)) {
            Compat.applyAuthDarkness(p);
        }
    }

    private void applyHiding(Player unauth) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(unauth)) {
                continue;
            }
            Compat.hidePlayer(plugin, other, unauth);
            Compat.hidePlayer(plugin, unauth, other);
        }
    }

    /**
     * Раскрыть игрока после авторизации.
     * Игроки, которые сами ещё не вошли, остаются скрытыми.
     */
    public void revealPlayer(Player authed) {
        if (authed == null) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(authed)) {
                continue;
            }
            Compat.showPlayer(plugin, other, authed);
            Compat.showPlayer(plugin, authed, other);
            if (!sessionManager.isLoggedIn(other.getUniqueId())) {
                Compat.hidePlayer(plugin, authed, other);
                Compat.hidePlayer(plugin, other, authed);
            }
        }
    }

    /**
     * При входе нового игрока скрыть от него всех неавторизованных.
     */
    private void syncHidingFor(Player joiner) {
        if (!hideDuringAuth) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(joiner)) {
                continue;
            }
            if (!sessionManager.isLoggedIn(other.getUniqueId())) {
                Compat.hidePlayer(plugin, joiner, other);
                Compat.hidePlayer(plugin, other, joiner);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        timeoutService.stop(p);
        reminderService.stop(p);
        if (afkService != null) {
            afkService.onQuit(uuid);
        }
        Compat.clearAuthDarkness(p);
        awaitingPassword.remove(uuid);
        riskyCommands.remove(uuid);
        pending2faPassword.remove(uuid);
        pendingEmailCode.remove(uuid);
        lastSafeTeleport.remove(uuid);
        if (antiBotService != null) {
            antiBotService.stripLobbyLoot(p);
            antiBotService.cancelCheck(uuid);
        }
        if (totpService != null) {
            totpService.cancelChallenge(uuid);
        }
        if (mailService != null) {
            mailService.clearCode(uuid);
        }
        sessionManager.logout(uuid);
        teleportService.clearSavedLocation(uuid);
        // На всякий случай раскрываем игрока, чтобы не остался невидимкой
        if (hideDuringAuth) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(p)) {
                    Compat.showPlayer(plugin, other, p);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        Player p = e.getPlayer();
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            return;
        }

        e.getCommands().removeIf(cmd -> {
            if (cmd == null || cmd.isEmpty()) {
                return true;
            }
            String base = cmd.toLowerCase(Locale.ROOT);
            int idx = base.indexOf(':');
            if (idx >= 0 && idx + 1 < base.length()) {
                base = base.substring(idx + 1);
            }
            // /2fa, /email, /recover нужны ДО входа (код 2FA, восстановление пароля)
            if (base.equals("2fa") || base.equals("email") || base.equals("recover")) {
                return false;
            }
            return !isAllowedCommand(base);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        boolean loggedIn = ready() && sessionManager.isLoggedIn(uuid);

        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            return;
        }

        String cmd = msg.startsWith("/") ? msg.substring(1) : msg;
        int sp = cmd.indexOf(' ');
        String base = (sp >= 0 ? cmd.substring(0, sp) : cmd).toLowerCase(Locale.ROOT);
        String cleanBase = base;
        int idx = cleanBase.indexOf(':');
        if (idx >= 0 && idx + 1 < cleanBase.length()) {
            cleanBase = cleanBase.substring(idx + 1);
        }

        boolean isAuthCmd = cleanBase.equals("l") || cleanBase.equals("login")
                || cleanBase.equals("reg") || cleanBase.equals("register");
        boolean isChangeCmd = cleanBase.equals("changepassword") || cleanBase.equals("changepw")
                || cleanBase.equals("cp") || cleanBase.equals("passwd");

        // Во время ввода пароля в чат — полная блокировка ВСЕХ команд
        // (иначе игрок мог уйти командой, не завершив ввод)
        if (!loggedIn && awaitingPassword.containsKey(uuid)) {
            e.setCancelled(true);
            return;
        }

        // Пока идёт проверка на бота (или ждём входа на сервер) — команды
        // заблокированы, кроме /rpverify <токен> и команд авторизации
        // (/login /register) — вход из лобби-очереди должен работать.
        if (!loggedIn && antiBotService != null && antiBotService.isBusy(uuid)) {
            if (isAuthCmd && !antiBotService.isChecking(uuid)) {
                // пропускаем к обычной обработке ниже — логин из очереди
            } else if (isAuthCmd) {
                // Активная проверка: /login /reg подменили бы ввод ответа
                // этапа на ввод пароля — блокируем до конца проверки.
                e.setCancelled(true);
                messages.sendOrDefault(p, "antibot_wait",
                        "{prefix}&#FF6666Сначала пройди проверку на бота — следуй инструкциям в чате");
                return;
            } else {
                e.setCancelled(true);
                if (cleanBase.equals("rpverify") && sp >= 0) {
                    int res = antiBotService.submitClickToken(p, cmd.substring(sp + 1).trim());
                    if (res == 1) {
                        messages.sendOrDefault(p, "antibot_click_wrong",
                                "{prefix}&#FF6666Неверная ссылка подтверждения. Нажми на сообщение выше.");
                    }
                } else if (antiBotService.isQueued(uuid)) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("position", String.valueOf(antiBotService.queuePosition(uuid)));
                    String m = messages.message("antibot_queue", ph);
                    if (m != null && !m.isEmpty()) {
                        p.sendMessage(m);
                    } else {
                        messages.sendOrDefault(p, "antibot_wait",
                                "{prefix}&#7F7F7FОчередь на проверку: позиция {position}");
                    }
                } else {
                    messages.sendOrDefault(p, "antibot_wait",
                            "{prefix}&#FF6666Сначала пройди проверку на бота — следуй инструкциям в чате");
                }
                return;
            }
        }

        // ---- служебные команды плагина (работают и до, и после входа) ----
        if (cleanBase.equals("2fa") || cleanBase.equals("email") || cleanBase.equals("recover")) {
            e.setCancelled(true); // ни ядро, ни другие плагины не увидят аргументы
            handleServiceCommand(p, cleanBase, cmd, sp);
            return;
        }

        if ((isAuthCmd || isChangeCmd) && plugin instanceof RegisterPlugin) {
            boolean hadArgs = sp >= 0 && sp + 1 < cmd.length() && !cmd.substring(sp + 1).trim().isEmpty();
            String args = hadArgs ? cmd.substring(sp + 1).trim() : "";

            // ЗАЩИЩЁННЫЙ режим: пароль в аргументах команды запрещён — стрипаем и переводим в чат-ввод
            if (secureMode) {
                if (hadArgs) {
                    messages.send(p, "password_in_command_blocked");
                }

                if (isChangeCmd) {
                    if (!loggedIn) {
                        e.setCancelled(true);
                        messages.send(p, "blocked_command");
                        return;
                    }
                    e.setCancelled(true);
                    beginChangePassword(p);
                    return;
                }

                if (loggedIn) {
                    e.setCancelled(true);
                    messages.send(p, "already_logged_in");
                    return;
                }

                long expiresAt = System.currentTimeMillis() + passwordInputTimeoutSec * 1000L;
                if (cleanBase.equals("l") || cleanBase.equals("login")) {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.LOGIN, expiresAt));
                    messages.send(p, "enter_password_chat_login");
                } else {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER, expiresAt));
                    messages.send(p, "enter_password_chat_register");
                }
                e.setCancelled(true);
                return;
            }

            // ---- НЕЗАЩИЩЁННЫЙ режим ----
            // Команду НИКОГДА не пропускаем ядру: иначе оно запишет пароль
            // в консоль строкой "issued server command". Исполняем сами.
            if (!hadArgs) {
                // без аргументов — обычный чат-ввод пароля
                if (isChangeCmd) {
                    e.setCancelled(true);
                    if (!loggedIn) {
                        messages.send(p, "blocked_command");
                    } else {
                        beginChangePassword(p);
                    }
                    return;
                }
                if (loggedIn) {
                    e.setCancelled(true);
                    messages.send(p, "already_logged_in");
                    return;
                }
                long expiresAt = System.currentTimeMillis() + passwordInputTimeoutSec * 1000L;
                if (cleanBase.equals("l") || cleanBase.equals("login")) {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.LOGIN, expiresAt));
                    messages.send(p, "enter_password_chat_login");
                } else {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER, expiresAt));
                    messages.send(p, "enter_password_chat_register");
                }
                e.setCancelled(true);
                return;
            }

            e.setCancelled(true);

            if (isChangeCmd) {
                if (!loggedIn) {
                    messages.send(p, "blocked_command");
                    return;
                }
                if (!confirmRiskyCommand(p, uuid, cleanBase, args)) {
                    return;
                }
                String[] parts = args.split("\\s+");
                if (parts.length < 2) {
                    beginChangePassword(p);
                    return;
                }
                changePasswordInsecure(p, parts[0], parts[1]);
                return;
            }

            if (loggedIn) {
                messages.send(p, "already_logged_in");
                return;
            }

            // Предупреждение + подтверждение: первый раз команда блокируется целиком
            if (!confirmRiskyCommand(p, uuid, cleanBase, args)) {
                return;
            }

            if (cleanBase.equals("l") || cleanBase.equals("login")) {
                loginInsecure(p, args);
            } else {
                registerInsecure(p, args);
            }
            return;
        }

        if (loggedIn) {
            return;
        }

        if (isAllowedCommand(cleanBase)) {
            return;
        }

        e.setCancelled(true);
        messages.send(p, "blocked_command");
    }

    /**
     * Игрок сам хочет сменить пароль: сначала старый пароль в чат.
     */
    public void beginChangePassword(Player p) {
        if (p == null) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + passwordInputTimeoutSec * 1000L;
        awaitingPassword.put(p.getUniqueId(), new PendingPassword(PasswordMode.CHANGE_OLD, expiresAt));
        messages.send(p, "enter_password_chat_old");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            return;
        }
        if (e.getTo() == null) {
            return;
        }
        UUID uuid = p.getUniqueId();
        if (afkService != null) {
            afkService.onMove(e);
        }

        // Игрок в многоэтапной проверке — логику движения ведёт AntiBotService
        // (падение на платформу, поворот камеры, заморозка на остальных этапах)
        if (antiBotService != null && antiBotService.isChecking(uuid)) {
            antiBotService.onMove(p, e.getFrom(), e.getTo());
            return;
        }

        // Игрок в очереди-лобби: ходить по платформе и паркуру можно,
        // провал — мгновенный возврат без урона
        if (antiBotService != null && antiBotService.onQueueMove(p, e.getFrom(), e.getTo())) {
            return;
        }

        // ВАЖНО: заморозка ДО любых других проверок — исключение выше
        // по стеку не должно открывать движение в обычном мире.
        e.setTo(e.getFrom());

        teleportService.checkVoidFall(p);
    }

    // (обработчик PlayerItemHeldEvent объединён ниже — см. onHeld)

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())
                && !(antiBotService != null
                    && antiBotService.isCheckWorld(e.getPlayer().getWorld()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShootBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Projectile)) {
            return;
        }
        Projectile proj = (Projectile) e.getEntity();
        ProjectileSource src = proj.getShooter();
        if (!(src instanceof Player)) {
            return;
        }
        Player p = (Player) src;
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        // Кнопка скорости — для всех, кто физически в мире лобби/проверки
        // (включая залогиненного админа, тестирующего лобби)
        if (antiBotService != null && e.getClickedBlock() != null
                && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && antiBotService.onSpeedButton(e.getPlayer(), e.getClickedBlock())) {
            return;
        }
        // Двойной сундук-набор: клик открывает ВИРТУАЛЬНЫЙ инвентарь на
        // игрока (реальный сундук пуст — анти-дюп), открытие раз в N сек.
        if (antiBotService != null && e.getClickedBlock() != null
                && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && antiBotService.isKitChest(e.getClickedBlock())) {
            e.setCancelled(true);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            antiBotService.openKitChest(e.getPlayer());
            return;
        }
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            // Кнопка скорости / сундук-набор в лобби-PvP
            if (antiBotService != null && e.getClickedBlock() != null
                    && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                    && antiBotService.onLobbyInteract(e.getPlayer(), e.getClickedBlock())) {
                return;
            }
            // В мире лобби/проверки разрешены клики по воздуху и удары:
            // одеть броню ПКМ, поесть, размахнуться мечом. Открытие чужих
            // блоков (RIGHT_CLICK_BLOCK) остаётся запрещённым.
            if (antiBotService != null && antiBotService.isCheckWorld(e.getPlayer().getWorld())) {
                // Сундук с инструментом этапа BLOCK — единственный открываемый
                // блок на проверке (без инструмента целевой блок не сломать)
                if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                        && antiBotService.isChecking(e.getPlayer().getUniqueId())
                        && antiBotService.isToolChestBlock(e.getPlayer(), e.getClickedBlock())) {
                    return;
                }
                if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                    return;
                }
                // ПКМ по блоку с бронёй/едой/щитом в руке — это одевание/еда,
                // а не открытие блока: разрешаем
                org.bukkit.inventory.ItemStack hand = e.getItem();
                if (hand != null) {
                    String hn = hand.getType().name();
                    if (hand.getType().isEdible() || hn.endsWith("_HELMET")
                            || hn.endsWith("_CHESTPLATE") || hn.endsWith("_LEGGINGS")
                            || hn.endsWith("_BOOTS") || hn.equals("SHIELD")
                            || hn.equals("TOTEM_OF_UNDYING")) {
                        return;
                    }
                }
            }
            e.setCancelled(true);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInvClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (antiBotService == null || !(e.getPlayer() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getPlayer();
        antiBotService.onPuzzleClose(p, e.getInventory());
        antiBotService.onKitClose(p, e.getInventory());
        antiBotService.onKitEditorClose(p, e.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        // Мир проверки/лобби — приватная зона: ломать может только этап BLOCK
        // (целевой блок) либо админ с registerplugin.admin.
        if (antiBotService != null && antiBotService.isCheckWorld(e.getBlock().getWorld())) {
            if (antiBotService.isChecking(p.getUniqueId())) {
                if (!antiBotService.onBlockBreak(p, e.getBlock())) {
                    e.setCancelled(true);
                }
            } else if (!antiBotService.canBreakInCheckWorld(p, e.getBlock())) {
                e.setCancelled(true);
            }
            return;
        }
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            return;
        }
        // Этап BLOCK антибота: разрешаем сломать ТОЛЬКО целевой блок арены
        if (antiBotService != null && antiBotService.isChecking(p.getUniqueId())) {
            if (!antiBotService.onBlockBreak(p, e.getBlock())) {
                e.setCancelled(true);
            }
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        // Мир проверки/лобби: строить могут только OP/registerplugin.admin
        // (queue_pvp.admin_modify), функциональные блоки watchdog восстановит
        if (antiBotService != null && antiBotService.isCheckWorld(e.getBlock().getWorld())
                && !antiBotService.canModifyCheckWorld(p)) {
            e.setCancelled(true);
            return;
        }
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            // До авторизации дроп запрещён всегда: в лобби игрок ходит
            // с настоящим инвентарём — выпавшие вещи безвозвратно теряются.
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getEntity();
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            return;
        }
        // Этап BLOCK: подобрать дроп сломанного блока — часть проверки
        if (antiBotService != null && antiBotService.isChecking(p.getUniqueId())) {
            if (!antiBotService.onPickup(p)) {
                e.setCancelled(true);
            } else {
                // Вещи мира проверки не покидают его — метим для вычистки
                antiBotService.tagLobbyItem(e.getItem());
            }
            return;
        }
        // В очереди-лобби подбор разрешён только внутри PvP-арены (лут сундука)
        if (antiBotService != null && antiBotService.isInQueueLobby(p.getUniqueId())
                && antiBotService.isPvpArea(p.getLocation())) {
            antiBotService.tagLobbyItem(e.getItem());
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventory(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getWhoClicked();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            // Клики по GUI пазла — обрабатывает AntiBotService
            if (antiBotService != null && antiBotService.isChecking(p.getUniqueId())) {
                org.bukkit.inventory.Inventory top = e.getView().getTopInventory();
                if (antiBotService.onPuzzleClick(p, top, e.getRawSlot())) {
                    e.setCancelled(true);
                    return;
                }
                // Сундук инструмента этапа BLOCK: брать из него — можно,
                // класть своё внутрь — нельзя (вещи бы потерялись)
                if (antiBotService.isToolChestTop(p, top)
                        && e.getRawSlot() >= 0 && e.getRawSlot() < top.getSize()) {
                    return;
                }
            }
            // Лобби: можно брать лут из PvP-сундука (только верхний инвентарь —
            // свои вещи в сундук положить нельзя, они бы потерялись)
            // Виртуальный инвентарь набора: брать можно, положить своё — нет
            if (antiBotService != null && antiBotService.isKitInv(e.getView().getTopInventory())) {
                if (e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize()
                        && e.getClick() != org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                    return;
                }
                e.setCancelled(true);
                return;
            }
            if (antiBotService != null && antiBotService.isInQueueLobby(p.getUniqueId())
                    && antiBotService.isPvpArea(p.getLocation())) {
                org.bukkit.inventory.Inventory topInv = e.getView().getTopInventory();
                if (topInv != null && topInv.getType() == org.bukkit.event.inventory.InventoryType.CHEST
                        && e.getRawSlot() >= 0 && e.getRawSlot() < topInv.getSize()
                        && e.getClick() != org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                    return;
                }
            }
            e.setCancelled(true);
            // Блокиратор слотов: попытка двигать вещи во время проверки —
            // считаем нарушение, после лимита кик (antibot.slot_lock.*)
            if (antiBotService != null) {
                antiBotService.onSlotViolation(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getPlayer();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            // На проверке: сундук инструмента (BLOCK) и GUI пазла — разрешены
            if (antiBotService != null && antiBotService.isChecking(p.getUniqueId())) {
                AntiBotService.Stage s = antiBotService.getCurrentStage(p.getUniqueId());
                if (s == AntiBotService.Stage.BLOCK || s == AntiBotService.Stage.PUZZLE) {
                    return;
                }
            }
            // Виртуальный инвентарь набора — всегда разрешён
            if (antiBotService != null && antiBotService.isKitInv(e.getInventory())) {
                return;
            }
            // В очереди-лобби: сундук только внутри PvP-арены
            if (antiBotService != null && antiBotService.isInQueueLobby(p.getUniqueId())
                    && antiBotService.isPvpArea(p.getLocation())) {
                return;
            }
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getWhoClicked();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            // В лобби драг по СВОЕМУ инвентарю разрешён (раскладка лута)
            if (antiBotService != null
                    && (antiBotService.isInQueueLobby(p.getUniqueId())
                        || (antiBotService.isCheckWorld(p.getWorld())
                            && !antiBotService.isChecking(p.getUniqueId())))) {
                int topSize = e.getView().getTopInventory() == null ? 0
                        : e.getView().getTopInventory().getSize();
                boolean allOwn = true;
                for (int rs : e.getRawSlots()) {
                    if (rs < topSize) { allOwn = false; break; }
                }
                if (allOwn) {
                    return;
                }
            }
            // Драг по GUI пазла — запрещаем (пазл только по кликам)
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())
                && !(antiBotService != null
                    && antiBotService.isCheckWorld(e.getPlayer().getWorld()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            return;
        }
        // Этап SLOTS антибота — засчитываем переключение слота,
        // событие НЕ отменяем, чтобы игрок мог реально крутить слоты
        if (antiBotService != null && antiBotService.isChecking(p.getUniqueId())) {
            antiBotService.onSlotChange(p, e.getNewSlot());
            return;
        }
        // В лобби-очереди крутить слоты можно — иначе меч не выбрать
        if (antiBotService != null && antiBotService.isCheckWorld(p.getWorld())) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            return;
        }
        if (teleportService.consumeAuthorizedTeleport(e.getPlayer().getUniqueId())) {
            return;
        }
        // Игрок в антибот-проверке: телепорты внутри арены делает только сервис
        if (antiBotService != null && antiBotService.isChecking(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        // Возврат в безопасную точку — не чаще раза в секунду,
        // иначе ядро/плагины могут вызвать шквал телепортов и лаг
        UUID uuid = e.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastSafeTeleport.get(uuid);
        if (last == null || now - last > 1000L) {
            lastSafeTeleport.put(uuid, now);
            teleportService.teleportToSafeLocation(e.getPlayer());
        }
    }

    private final Map<UUID, Long> lastSafeTeleport = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!(e.getEntered() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getEntered();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleExit(VehicleExitEvent e) {
        if (!(e.getExited() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getExited();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * ВАЖНО: обработка на LOWEST — перехватываем пароль ДО того, как
     * его увидят логгеры/античиты/сторонние плагины (они вежливые и
     * используют ignoreCancelled=true — для них сообщение уже отменено).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();

        PendingPassword pending = awaitingPassword.get(uuid);
        if (pending != null && plugin instanceof RegisterPlugin) {
            // Сначала читаем текст, затем мгновенно отменяем и стираем его,
            // чтобы пароль не ушёл дальше по конвейеру событий
            String raw = e.getMessage() == null ? "" : e.getMessage();
            e.setCancelled(true);
            e.setMessage("");
            e.getRecipients().clear();

            String s = raw.trim();
            if (s.equalsIgnoreCase("cancel") || s.equalsIgnoreCase("отмена") || s.equalsIgnoreCase("отменить")) {
                awaitingPassword.remove(uuid);
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        messages.send(p, "password_input_cancelled");
                    }
                });
                return;
            }

            long now = System.currentTimeMillis();
            if (now > pending.expiresAtMillis) {
                awaitingPassword.remove(uuid);
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        messages.send(p, "password_input_timeout");
                    }
                });
                return;
            }

            handlePendingInput(p, uuid, pending, raw);
            return;
        }

        // Этапы антибота с ответом в чате (CAPTCHA/MATH/SECRET/PUZZLE):
        // сообщение игрока — это ответ на проверку, перехватываем рано,
        // чтобы не засветить в чате/логах
        if (!sessionManager.isLoggedIn(uuid) && antiBotService != null
                && antiBotService.expectsChat(uuid)) {
            String input = e.getMessage() == null ? "" : e.getMessage();
            e.setCancelled(true);
            e.setMessage("");
            e.getRecipients().clear();
            int res = antiBotService.onCheckChat(p, input);
            if (res == 1) {
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        messages.sendOrDefault(p, "antibot_wrong_code",
                                "{prefix}&#FF6666Неверно. Смотри задание выше / попробуй ещё раз.");
                    }
                });
            } else if (res == 2) {
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        antiBotService.failCheck(uuid, messages.message("antibot_failed_kick"));
                    }
                });
            }
            return;
        }

        // Код 2FA: игрок уже ввёл пароль, ждём только код
        if (totpService != null && totpService.hasChallenge(uuid)) {
            String input = e.getMessage() == null ? "" : e.getMessage();
            e.setCancelled(true);
            e.setMessage("");
            e.getRecipients().clear();
            int res = totpService.submit(p, input, authService);
            if (res == 1) {
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        messages.send(p, "twofa_wrong_code");
                    }
                });
            } else if (res == 2) {
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        messages.send(p, "twofa_expired");
                    }
                });
            } else {
                Scheduler.runSync(plugin, () -> afterLoginSuccess(p, false));
            }
            return;
        }

        // Код подтверждения почты
        if (pendingEmailCode.containsKey(uuid) && mailService != null && mailService.isEnabled()) {
            String input = e.getMessage() == null ? "" : e.getMessage();
            if (input.trim().matches("\\d{6}")) {
                e.setCancelled(true);
                e.setMessage("");
                e.getRecipients().clear();
                handleEmailCode(p, uuid, input.trim());
                return;
            }
        }

        if (!sessionManager.isLoggedIn(uuid)) {
            // В очереди-лобби чат может быть разрешён конфигом — тогда сообщение
            // проходит, но видят его только другие ждущие (получатели = очередь)
            if (antiBotService != null && antiBotService.isQueued(uuid)
                    && antiBotService.queueChatAllowed()) {
                if (afkService != null) {
                    afkService.onActivity(uuid);
                }
                // Фильтр: анти-реклама, КД на сообщения, лимит слов, анти-повтор
                String reason = antiBotService.filterLobbyChat(p, e.getMessage());
                if (reason != null) {
                    e.setCancelled(true);
                    final String key = reason;
                    Scheduler.runSync(plugin, () -> {
                        if (p.isOnline()) {
                            messages.send(p, key);
                        }
                    });
                    return;
                }
                e.getRecipients().removeIf(r -> !(r instanceof Player)
                        || !antiBotService.isQueued(((Player) r).getUniqueId()));
                return;
            }
            e.setCancelled(true);
        }
    }

    /**
     * Страховка на MONITOR: если какой-то плагин всё же снял отмену —
     * стираем текст сообщения ещё раз.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChatMonitor(AsyncPlayerChatEvent e) {
        if (awaitingPassword.containsKey(e.getPlayer().getUniqueId())) {
            try {
                e.setMessage("");
            } catch (Throwable ignored) {
            }
        }
    }

    private void handlePendingInput(Player p, UUID uuid, PendingPassword pending, String raw) {
        RegisterPlugin rp = (RegisterPlugin) plugin;
        int maxLen = plugin.getConfig().getInt("password.max_length", 64);
        if (maxLen < 8) {
            maxLen = 8;
        }
        if (maxLen > 256) {
            maxLen = 256;
        }

        switch (pending.mode) {
            case LOGIN:
            case REGISTER: {
                if (raw.length() > maxLen) {
                    Scheduler.runSync(plugin, () -> {
                        if (p.isOnline()) {
                            messages.send(p, "password_too_long");
                        }
                    });
                    return;
                }

                // Для регистрации при require_confirm — сначала валидация, потом подтверждение
                if (pending.mode == PasswordMode.REGISTER && requireConfirm) {
                    me.vorchun.registerplugin.service.PasswordValidator.ValidationResult vr =
                            me.vorchun.registerplugin.service.PasswordValidator.validate(raw,
                                    plugin.getConfig().getInt("password.min_length", 8),
                                    maxLen, enforceStrength,
                                    easyPasswordList == null ? null : easyPasswordList.getAllowed());
                    if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_SHORT) {
                        sendAsync(p, "password_too_short");
                        return;
                    }
                    if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_LONG) {
                        sendAsync(p, "password_too_long");
                        return;
                    }
                    if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.INVALID_WHITESPACE
                            || vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.INVALID_NULL) {
                        sendAsync(p, "password_invalid");
                        return;
                    }
                    if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_WEAK) {
                        sendAsync(p, "password_too_weak");
                        return;
                    }
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER_CONFIRM,
                            System.currentTimeMillis() + passwordInputTimeoutSec * 1000L, raw));
                    sendAsync(p, "enter_password_chat_confirm");
                    return;
                }

                awaitingPassword.remove(uuid);
                final String pass = raw;
                final boolean login = pending.mode == PasswordMode.LOGIN;
                // Хеширование/проверка уходят в фоновый пул (AuthService),
                // главный поток не блокируется даже на 200k итераций PBKDF2
                if (authService == null) {
                    Scheduler.runSync(plugin, () -> {
                        if (p.isOnline()) {
                            if (login) {
                                rp.handleLogin(p, new String[]{pass});
                            } else {
                                rp.handleRegister(p, new String[]{pass});
                            }
                        }
                    });
                    return;
                }
                if (login) {
                    authService.login(p, pass, true, result -> {
                        switch (result) {
                            case OK:
                                afterLoginSuccess(p, false);
                                break;
                            case WRONG_PASSWORD:
                                messages.send(p, "wrong_password");
                                break;
                            case NOT_REGISTERED:
                                messages.send(p, "not_registered");
                                break;
                            case LOCKED:
                                messages.send(p, "too_many_attempts_kick");
                                break;
                            case NEED_2FA:
                                break;
                            default:
                                messages.send(p, "wrong_password");
                        }
                    });
                } else {
                    authService.register(p, pass, (result, validation) -> {
                        if (result == me.vorchun.registerplugin.service.AuthService.Result.OK) {
                            afterLoginSuccess(p, true);
                            return;
                        }
                        switch (validation) {
                            case TOO_SHORT:
                                messages.send(p, "password_too_short");
                                break;
                            case TOO_LONG:
                                messages.send(p, "password_too_long");
                                break;
                            case INVALID_WHITESPACE:
                            case INVALID_NULL:
                                messages.send(p, "password_invalid");
                                break;
                            case TOO_WEAK:
                                messages.send(p, "password_too_weak");
                                break;
                            default:
                                messages.send(p, "already_registered");
                        }
                    });
                }
                return;
            }

            case REGISTER_CONFIRM: {
                awaitingPassword.remove(uuid);
                if (!raw.equals(pending.data)) {
                    sendAsync(p, "passwords_dont_match");
                    // возвращаем на шаг ввода пароля
                    Scheduler.runSync(plugin, () -> {
                        if (p.isOnline()) {
                            awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER,
                                    System.currentTimeMillis() + passwordInputTimeoutSec * 1000L));
                            messages.send(p, "enter_password_chat_register");
                        }
                    });
                    return;
                }
                final String pass = pending.data;
                Scheduler.runSync(plugin, () -> {
                    if (p.isOnline()) {
                        rp.handleRegister(p, new String[]{pass});
                    }
                });
                return;
            }

            case CHANGE_OLD: {
                if (raw.length() > maxLen) {
                    sendAsync(p, "password_too_long");
                    return;
                }
                me.vorchun.registerplugin.service.AccountRecord rec = accountStore.get(uuid);
                boolean ok = rec != null && me.vorchun.registerplugin.service.PasswordHasher.verify(raw, rec.getPasswordHash());
                if (!ok) {
                    awaitingPassword.remove(uuid);
                    sendAsync(p, "change_password_wrong_old");
                    return;
                }
                awaitingPassword.put(uuid, new PendingPassword(PasswordMode.CHANGE_NEW,
                        System.currentTimeMillis() + passwordInputTimeoutSec * 1000L));
                sendAsync(p, "enter_password_chat_new");
                return;
            }

            case CHANGE_NEW: {
                me.vorchun.registerplugin.service.PasswordValidator.ValidationResult vr =
                        me.vorchun.registerplugin.service.PasswordValidator.validate(raw,
                                plugin.getConfig().getInt("password.min_length", 8),
                                maxLen, enforceStrength,
                                easyPasswordList == null ? null : easyPasswordList.getAllowed());
                if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_SHORT) {
                    sendAsync(p, "password_too_short");
                    return;
                }
                if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_LONG) {
                    sendAsync(p, "password_too_long");
                    return;
                }
                if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.INVALID_WHITESPACE
                        || vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.INVALID_NULL) {
                    sendAsync(p, "password_invalid");
                    return;
                }
                if (vr == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.TOO_WEAK) {
                    sendAsync(p, "password_too_weak");
                    return;
                }
                if (requireConfirm) {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.CHANGE_CONFIRM,
                            System.currentTimeMillis() + passwordInputTimeoutSec * 1000L, raw));
                    sendAsync(p, "enter_password_chat_confirm");
                    return;
                }
                awaitingPassword.remove(uuid);
                applyNewPassword(p, uuid, raw);
                return;
            }

            case CHANGE_CONFIRM: {
                awaitingPassword.remove(uuid);
                if (!raw.equals(pending.data)) {
                    sendAsync(p, "passwords_dont_match");
                    return;
                }
                applyNewPassword(p, uuid, pending.data);
                return;
            }
        }
    }

    private void applyNewPassword(Player p, UUID uuid, String newPassword) {
        if (authService == null) {
            return;
        }
        // Старый пароль уже проверен на предыдущем шаге — только перехешируем новый
        authService.changePassword(p, null, newPassword, false, validation -> {
            switch (validation) {
                case VALID:
                    messages.send(p, "change_password_success");
                    break;
                case TOO_SHORT:
                    messages.send(p, "password_too_short");
                    break;
                case TOO_LONG:
                    messages.send(p, "password_too_long");
                    break;
                case INVALID_WHITESPACE:
                case INVALID_NULL:
                    messages.send(p, "password_invalid");
                    break;
                default:
                    messages.send(p, "password_too_weak");
            }
        });
    }

    private void sendAsync(Player p, String key) {
        Scheduler.runSync(plugin, () -> {
            if (p.isOnline()) {
                messages.send(p, key);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            // PvP-урон внутри зоны очереди-лобби — разрешён (drака на арене)
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && antiBotService != null
                    && antiBotService.isInQueueLobby(p.getUniqueId())
                    && antiBotService.isPvpArea(p.getLocation())) {
                return;
            }
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player) {
            Player p = (Player) e.getDamager();
            if (!sessionManager.isLoggedIn(p.getUniqueId())) {
                // Punch on puzzle item frame (PUZZLE stage) = remove extra tile
                if (antiBotService != null
                        && antiBotService.onPuzzleFrameHit(p, e.getEntity())) {
                    e.setCancelled(true);
                    return;
                }
                // Атакующий и жертва в PvP-зоне очереди-лобби — разрешено
                if (e.getEntity() instanceof Player
                        && antiBotService != null
                        && antiBotService.isInQueueLobby(p.getUniqueId())
                        && antiBotService.isInQueueLobby(e.getEntity().getUniqueId())
                        && antiBotService.isPvpArea(p.getLocation())
                        && antiBotService.isPvpArea(e.getEntity().getLocation())) {
                    return;
                }
                e.setCancelled(true);
            }
            return;
        }

        if (e.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) e.getDamager();
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player) {
                Player p = (Player) src;
                if (!sessionManager.isLoggedIn(p.getUniqueId())) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerItemDamage(org.bukkit.event.player.PlayerItemDamageEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerShearEntity(org.bukkit.event.player.PlayerShearEntityEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerEditBook(org.bukkit.event.player.PlayerEditBookEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerAnimation(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(org.bukkit.event.hanging.HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player) {
            Player p = (Player) e.getRemover();
            if (!sessionManager.isLoggedIn(p.getUniqueId())) {
                if (antiBotService != null
                        && antiBotService.onPuzzleFrameHit(p, e.getEntity())) {
                    e.setCancelled(true);
                    return;
                }
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(org.bukkit.event.hanging.HangingPlaceEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---------- команды с паролем: подтверждение и внутреннее исполнение ----------

    /**
     * Первый ввод пароля в команде блокируется и требует осознанного подтверждения.
     * Возвращает true, если игрок уже подтвердил (повторил ту же команду с тем же паролем).
     *
     * Это не только UX-предупреждение: команда не уходит ядру, поэтому пароль
     * не попадает в строку "issued server command" в консоли и логах.
     */
    private boolean confirmRiskyCommand(Player p, UUID uuid, String base, String args) {
        if (!riskyConfirmEnabled) {
            messages.send(p, "insecure_mode_warn");
            return true;
        }
        long now = System.currentTimeMillis();
        RiskyCommand prev = riskyCommands.get(uuid);
        if (prev != null && prev.expiresAt > now && prev.command.equals(base) && prev.password.equals(args)) {
            riskyCommands.remove(uuid);
            messages.send(p, "insecure_confirmed");
            return true;
        }
        riskyCommands.put(uuid, new RiskyCommand(base, args, now + riskyConfirmSeconds * 1000L));
        messages.send(p, "insecure_blocked_warn");
        Map<String, String> ph = new HashMap<>();
        ph.put("seconds", String.valueOf(riskyConfirmSeconds));
        messages.send(p, "insecure_repeat_hint", ph);
        return false;
    }

    /** Вход по паролю из команды (без передачи команды ядру). */
    private void loginInsecure(Player p, String password) {
        if (authService == null) {
            messages.send(p, "insecure_mode_warn");
            ((RegisterPlugin) plugin).handleLogin(p, new String[]{password});
            return;
        }
        authService.login(p, password, false, result -> {
            switch (result) {
                case OK:
                    afterLoginSuccess(p, false);
                    warnPasswordInLogs(p);
                    break;
                case WRONG_PASSWORD:
                    messages.send(p, "wrong_password");
                    break;
                case NOT_REGISTERED:
                    messages.send(p, "not_registered");
                    break;
                case LOCKED:
                    messages.send(p, "too_many_attempts_kick");
                    break;
                case NEED_2FA:
                    break;
                default:
                    messages.send(p, "wrong_password");
            }
        });
    }

    /** Регистрация по паролю из команды. */
    private void registerInsecure(Player p, String password) {
        if (authService == null) {
            messages.send(p, "insecure_mode_warn");
            ((RegisterPlugin) plugin).handleRegister(p, new String[]{password});
            return;
        }
        authService.register(p, password, (result, validation) -> {
            if (result == me.vorchun.registerplugin.service.AuthService.Result.OK) {
                afterLoginSuccess(p, true);
                warnPasswordInLogs(p);
                return;
            }
            switch (validation) {
                case TOO_SHORT:
                    messages.send(p, "password_too_short");
                    break;
                case TOO_LONG:
                    messages.send(p, "password_too_long");
                    break;
                case INVALID_WHITESPACE:
                case INVALID_NULL:
                    messages.send(p, "password_invalid");
                    break;
                case TOO_WEAK:
                    messages.send(p, "password_too_weak");
                    break;
                default:
                    messages.send(p, "already_registered");
            }
        });
    }

    /** Смена пароля командами /changepassword <old> <new>. */
    private void changePasswordInsecure(Player p, String oldPassword, String newPassword) {
        if (authService == null) {
            messages.send(p, "change_password_usage");
            return;
        }
        authService.changePassword(p, oldPassword, newPassword, true, validation -> {
            if (validation == null) {
                messages.send(p, "change_password_wrong_old");
                return;
            }
            switch (validation) {
                case VALID:
                    messages.send(p, "change_password_success");
                    break;
                case TOO_SHORT:
                    messages.send(p, "password_too_short");
                    break;
                case TOO_LONG:
                    messages.send(p, "password_too_long");
                    break;
                case INVALID_WHITESPACE:
                case INVALID_NULL:
                    messages.send(p, "password_invalid");
                    break;
                default:
                    messages.send(p, "password_too_weak");
            }
        });
    }

    /**
     * Честное предупреждение: пароль, введённый В КОМАНДЕ, уже попал в лог сервера.
     * Текст имеет встроенный fallback и не может быть полностью вырезан из конфига —
     * игрок всегда узнает, что владелец сервера может увидеть его пароль.
     */
    private void warnPasswordInLogs(Player p) {
        messages.sendOrDefault(p, "insecure_password_in_logs",
                "{prefix}&#FF5555&lВНИМАНИЕ! &#FFFFFFТы ввёл пароль командой — он уже записан "
                        + "в консоль и логи сервера. &#FF5555Владелец сервера МОЖЕТ его увидеть. "
                        + "&#FFFFFFСмени пароль: &#A0FFA0/changepassword &#7F7F7F(или включи "
                        + "security.secure_password_input: true)");
    }

    /**
     * Общая часть после успешного входа/регистрации.
     * Маршрут игрока: прокси-трансфер в лобби → спавны → лобби-локация.
     */
    private void afterLoginSuccess(Player p, boolean firstTime) {
        if (afkService != null) {
            afkService.onLogin(p.getUniqueId());
        }
        timeoutService.stop(p);
        reminderService.stop(p);
        Compat.updateCommands(p);
        Compat.clearAuthDarkness(p);
        revealPlayer(p);
        // Игрок мог залогиниться, стоя в лобби-очереди — снимаем с очередей,
        // иначе он навсегда остался бы в лобби ожидания
        if (antiBotService != null) {
            antiBotService.leaveQueues(p);
        }
        boolean proxyTransfer = plugin.getConfig().getBoolean("proxy_server.enabled", false)
                && teleportService.isProxyMode();
        if (proxyTransfer) {
            teleportService.teleportToLobby(p);
        } else {
            // Куда отправить после входа. after_auth.target / new_target:
            //   spawn — точка setspawn (postlogin / firstjoin для новичков)
            //   zero  — спавн основного мира (нулевые координаты)
            //   last  — оставить на месте, где игрок зашёл
            //   none  — ничего не делать
            //   auto  — spawn если задан, иначе last (новичок: иначе zero)
            // after_auth.target / new_target — куда отправить после входа:
            //   auto    — spawn если задан, иначе last (новичок: иначе zero)
            //   spawn   — точка /authadmin setspawn postlogin|firstjoin
            //   zero    — спавн основного мира (нулевые координаты)
            //   coords  — точные координаты after_auth.world/x/y/z
            //   command — выполнить команду от консоли, {player} = ник
            //             (например "spawn {player}" — EssentialsSpawn и прочие)
            //   plugin  — точка спавна из другого плагина (Essentials и т.п.),
            //             через команду after_auth.plugin_command
            //   last    — оставить на месте входа   |   none — ничего
            String mode = plugin.getConfig().getString(firstTime
                    ? "after_auth.new_target" : "after_auth.target", "auto");
            if (mode == null) {
                mode = "auto";
            }
            mode = mode.toLowerCase(java.util.Locale.ROOT).trim();
            if ("auto".equals(mode)) {
                mode = firstTime ? "spawn_or_zero" : "spawn_or_last";
            }
            boolean done = false;
            if ("command".equals(mode) || "plugin".equals(mode)) {
                // Командный перенос: консоль выполняет spawn/warp для игрока —
                // так работают EssentialsSpawn, SetSpawn и любые warp-плагины.
                String cmdKey = ("plugin".equals(mode))
                        ? "after_auth.plugin_command"
                        : (firstTime ? "after_auth.command_new" : "after_auth.command");
                String cmd = plugin.getConfig().getString(cmdKey,
                        "plugin".equals(mode) ? "spawn {player}" : "");
                if (cmd == null || cmd.trim().isEmpty()) {
                    cmd = plugin.getConfig().getString("after_auth.command", "");
                }
                if (cmd != null && !cmd.trim().isEmpty()) {
                    final String run = cmd.trim().replace("{player}", p.getName())
                            .replace("{uuid}", p.getUniqueId().toString());
                    Scheduler.runSyncLater(plugin, () -> {
                        if (p.isOnline()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run);
                        }
                    }, 5L);
                    done = true;
                }
            }
            if (!done && "coords".equals(mode)) {
                org.bukkit.World cw = Bukkit.getWorld(
                        plugin.getConfig().getString("after_auth.world", "world"));
                if (cw == null && !Bukkit.getWorlds().isEmpty()) {
                    cw = Bukkit.getWorlds().get(0);
                }
                if (cw != null) {
                    final Location dest = new Location(cw,
                            plugin.getConfig().getDouble("after_auth.x", 0.5),
                            plugin.getConfig().getDouble("after_auth.y", 100.0),
                            plugin.getConfig().getDouble("after_auth.z", 0.5),
                            (float) plugin.getConfig().getDouble("after_auth.yaw", 0.0),
                            (float) plugin.getConfig().getDouble("after_auth.pitch", 0.0));
                    teleportService.authorizeTeleport(p.getUniqueId());
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (p.isOnline()) {
                            p.teleport(dest);
                        }
                    });
                    done = true;
                }
            }
            if (!done && ("spawn".equals(mode) || "spawn_or_zero".equals(mode)
                    || "spawn_or_last".equals(mode)) && spawnService != null) {
                java.util.Map<String, Location> sp = spawnService.all();
                Location target = firstTime
                        ? (sp.get("firstjoin") != null ? sp.get("firstjoin") : sp.get("postlogin"))
                        : sp.get("postlogin");
                if (target != null) {
                    spawnService.teleportAfterLogin(p, firstTime);
                    done = true;
                }
            }
            if (!done && ("zero".equals(mode) || "spawn_or_zero".equals(mode))) {
                org.bukkit.World main = Bukkit.getWorlds().isEmpty()
                        ? p.getWorld() : Bukkit.getWorlds().get(0);
                if (main != null) {
                    final Location dest = main.getSpawnLocation();
                    teleportService.authorizeTeleport(p.getUniqueId());
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (p.isOnline()) {
                            p.teleport(dest);
                        }
                    });
                    done = true;
                }
            }
            if (!done && !"last".equals(mode) && !"none".equals(mode)
                    && !"spawn_or_last".equals(mode)) {
                teleportService.teleportToLobby(p);
            }
        }
        messages.send(p, firstTime ? "register_success" : "login_success");
    }

    // ---------- /2fa, /email, /recover ----------

    private void handleServiceCommand(Player p, String base, String cmd, int sp) {
        String args = sp >= 0 ? cmd.substring(sp + 1).trim() : "";
        switch (base) {
            case "2fa":
                handleTwoFactor(p, args);
                break;
            case "email":
                handleEmail(p, args);
                break;
            case "recover":
                handleRecover(p, args);
                break;
            default:
                break;
        }
    }

    private void handleTwoFactor(Player p, String args) {
        if (totpService == null || !totpService.isEnabled()) {
            messages.send(p, "twofa_disabled");
            return;
        }
        UUID uuid = p.getUniqueId();
        // Ввод кода во время входа
        if (totpService.hasChallenge(uuid)) {
            if (args.isEmpty()) {
                messages.send(p, "twofa_enter_code");
                return;
            }
            int res = totpService.submit(p, args, authService);
            if (res == 0) {
                afterLoginSuccess(p, false);
            } else if (res == 1) {
                messages.send(p, "twofa_wrong_code");
            } else {
                messages.send(p, "twofa_expired");
            }
            return;
        }
        if (!sessionManager.isLoggedIn(uuid)) {
            messages.send(p, "blocked_command");
            return;
        }
        if (args.isEmpty()) {
            messages.send(p, "twofa_usage");
            return;
        }
        String sub = args.toLowerCase(Locale.ROOT);
        if (sub.equals("off") || sub.equals("disable") || sub.equals("выкл")) {
            totpService.disable(p);
            messages.send(p, "twofa_disabled_done");
            return;
        }
        if (sub.equals("on") || sub.equals("enable") || sub.equals("вкл")) {
            String secret = totpService.generateSecret();
            pending2faPassword.put(uuid, secret);
            messages.send(p, "twofa_setup");
            p.sendMessage(messages.format("&7Секрет: &f" + secret, new HashMap<>()));
            p.sendMessage(messages.format("&7Ссылка: &f" + totpService.buildUrl(p.getName(), secret), new HashMap<>()));
            messages.send(p, "twofa_confirm_hint");
            return;
        }
        // ввод кода для включения
        String secret = pending2faPassword.get(uuid);
        if (secret != null && totpService.check(secret, args)) {
            pending2faPassword.remove(uuid);
            totpService.enable(p, secret);
            messages.send(p, "twofa_enabled_done");
        } else if (secret != null) {
            messages.send(p, "twofa_wrong_code");
        } else {
            messages.send(p, "twofa_usage");
        }
    }

    private void handleEmail(Player p, String args) {
        if (mailService == null || !mailService.isEnabled()) {
            messages.send(p, "email_disabled");
            return;
        }
        UUID uuid = p.getUniqueId();
        if (!sessionManager.isLoggedIn(uuid)) {
            messages.send(p, "blocked_command");
            return;
        }
        if (args.isEmpty()) {
            messages.send(p, "email_usage");
            return;
        }
        if (args.indexOf('@') < 0 || args.length() < 5) {
            messages.send(p, "email_invalid");
            return;
        }
        pendingEmailCode.put(uuid, args);
        mailService.sendCode(uuid, args, "verify", p.getName(), ok -> {
            if (!p.isOnline()) {
                return;
            }
            messages.send(p, ok ? "email_code_sent" : "email_send_failed");
        });
    }

    private void handleRecover(Player p, String args) {
        if (mailService == null || !mailService.isEnabled()) {
            messages.send(p, "email_disabled");
            return;
        }
        UUID uuid = p.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            messages.send(p, "already_logged_in");
            return;
        }
        if (args.isEmpty()) {
            AccountRecord r = accountStore.get(uuid);
            if (r == null || !r.isEmailVerified()) {
                messages.send(p, "recover_no_email");
                return;
            }
            mailService.sendCode(uuid, r.getEmail(), "recover", p.getName(), ok ->
                    messages.send(p, ok ? "recover_code_sent" : "email_send_failed"));
            return;
        }
        // /recover <код> <новый пароль>
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            messages.send(p, "recover_usage");
            return;
        }
        int res = mailService.checkCode(uuid, parts[0], "recover");
        if (res == 1) {
            messages.send(p, "recover_wrong_code");
            return;
        }
        if (res != 0) {
            messages.send(p, "recover_code_expired");
            return;
        }
        if (authService == null) {
            return;
        }
        authService.changePassword(p, null, parts[1], false, validation -> {
            switch (validation) {
                case VALID:
                    messages.send(p, "recover_success");
                    break;
                case TOO_SHORT:
                    messages.send(p, "password_too_short");
                    break;
                case TOO_LONG:
                    messages.send(p, "password_too_long");
                    break;
                default:
                    messages.send(p, "password_invalid");
            }
        });
    }

    /** Подтверждение кода почты (ввод кода в чат после /email set). */
    private void handleEmailCode(Player p, UUID uuid, String input) {
        String email = pendingEmailCode.get(uuid);
        if (email == null) {
            return;
        }
        int res = mailService.checkCode(uuid, input, "verify");
        if (res == 0) {
            pendingEmailCode.remove(uuid);
            AccountRecord r = accountStore.get(uuid);
            if (r != null) {
                r.setEmail(email, true);
                accountStore.markDirty(r);
            }
            messages.send(p, "email_verified");
        } else if (res == 1) {
            messages.send(p, "email_wrong_code");
        } else {
            pendingEmailCode.remove(uuid);
            messages.send(p, "email_code_expired");
        }
    }

    private boolean isAllowedCommand(String base) {
        Set<String> set = allowedCommandsCache;
        if (set == null || set.isEmpty()) {
            return base.equals("login") || base.equals("l") || base.equals("register") || base.equals("reg");
        }
        return set.contains(base);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPotionEffect(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            org.bukkit.potion.PotionEffectType type = e.getNewEffect() != null ? e.getNewEffect().getType() : null;
            // Наши собственные эффекты (темнота/слепота) должны проходить,
            // иначе плагин сам себя блокирует на 1.19+
            if (type != null && (type.equals(org.bukkit.potion.PotionEffectType.BLINDNESS)
                    || type.getName().equalsIgnoreCase("darkness"))) {
                return;
            }
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemMove(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
        for (org.bukkit.inventory.Inventory inv : new org.bukkit.inventory.Inventory[]{e.getSource(), e.getDestination()}) {
            for (org.bukkit.entity.HumanEntity viewer : inv.getViewers()) {
                if (viewer instanceof Player) {
                    Player p = (Player) viewer;
                    if (!sessionManager.isLoggedIn(p.getUniqueId())) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerArmorStandManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    public void clearPasswordWaiting(UUID uuid) {
        awaitingPassword.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpChange(org.bukkit.event.player.PlayerExpChangeEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setAmount(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBedEnter(org.bukkit.event.player.PlayerBedEnterEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(org.bukkit.event.inventory.CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent e) {
        if (!sessionManager.isLoggedIn(e.getEnchanter().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(org.bukkit.event.inventory.PrepareAnvilEvent e) {
        if (!(e.getView().getPlayer() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getView().getPlayer();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemMend(org.bukkit.event.player.PlayerItemMendEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Защита от /kill и любой смерти до авторизации:
     * инвентарь и опыт сохраняются, дроп отменяется.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (!protectDeath) {
            return;
        }
        Player p = e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId())) {
            // PvP в очереди-лобби: убийца +N позиций, погибший −N (если включено)
            if (antiBotService != null) {
                try {
                    antiBotService.onQueuePvpDeath(p);
                } catch (Throwable ignored) {
                }
            }
            // В лобби-очереди: дропаем только броню и еду (правило PvP-арены)
            if (antiBotService != null
                    && antiBotService.filterQueueDeathDrops(e, p)) {
                return;
            }
            try {
                e.setKeepInventory(true);
                e.setKeepLevel(true);
                e.getDrops().clear();
                e.setDroppedExp(0);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * После смерти/респавна до авторизации: возвращаем ограничения —
     * темнота и (при включённом антиботе) повторная проверка не требуется,
     * просто продолжаем удерживать на месте.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            // Залогиненный умер в мире проверки (бездна и т.п.) —
            // не оставляем его там: перенаправляем в основной мир
            if (antiBotService != null && antiBotService.isCheckWorld(e.getRespawnLocation().getWorld())) {
                java.util.List<org.bukkit.World> ws = Bukkit.getWorlds();
                if (!ws.isEmpty()) {
                    e.setRespawnLocation(ws.get(0).getSpawnLocation());
                }
            }
            return;
        }
        // Игрок лобби-очереди (паркур/PvP): возрождается на спавне лобби —
        // не на prelogin-точке и не в обычном мире
        if (antiBotService != null && antiBotService.isInQueueLobby(p.getUniqueId())) {
            org.bukkit.Location ls = antiBotService.lobbySpawnLocation();
            if (ls != null) {
                e.setRespawnLocation(ls);
                return;
            }
        }
        // Игрок в антибот-проверке: даже смерть не выпускает его в обычный мир,
        // он возвращается на арену и продолжает проходить этапы
        if (antiBotService != null && antiBotService.returnToCheckOnRespawn(p)) {
            return;
        }
        Scheduler.runSyncLater(plugin, () -> {
            if (p.isOnline() && !sessionManager.isLoggedIn(p.getUniqueId())) {
                darkness(p);
                teleportService.teleportToSafeLocation(p);
            }
        }, 1L);
    }

    /**
     * Блокировка эндер-жемчуга/хоруса и любых других телепортов до входа
     * уже покрыта onTeleport (HIGHEST). Здесь дополнительно страхуем
     * телепортацию через PlayerTeleportEvent во время антибот-проверки.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalTravel(org.bukkit.event.player.PlayerPortalEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    private static final int READY = 812196035


;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100b) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100b) == READY;
    }
}
