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
        // Смена пароля: старый пароль, введённый на шаге CHANGE_OLD
        // (проверяется вместе со сменой — в пуле AuthService)
        final String oldPassword;

        PendingPassword(PasswordMode mode, long expiresAtMillis) {
            this(mode, expiresAtMillis, null, null);
        }

        PendingPassword(PasswordMode mode, long expiresAtMillis, String data) {
            this(mode, expiresAtMillis, data, null);
        }

        PendingPassword(PasswordMode mode, long expiresAtMillis, String data, String oldPassword) {
            this.mode = mode;
            this.expiresAtMillis = expiresAtMillis;
            this.data = data;
            this.oldPassword = oldPassword;
        }
    }

    /** Порог «шага» для afk_first: 0.05 блока по XZ (пакет со сдвигом 0.001 — не шаг). */
    private static final double AFK_FIRST_STEP_SQ = 0.05 * 0.05;
    private static final String RETURNS_FILE = "auth-returns.yml";

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
    private volatile me.vorchun.registerplugin.service.TwoFactorQr twoFactorQr;

    /** Настоящие точки входа игроков, заспавненных сразу на платформе входа. */
    private final Map<UUID, Location> spawnRedirected = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean spawnOnPlatform = true;

    /**
     * PlayerSpawnLocationEvent: неавторизованный появляется сразу на платформе
     * входа. Раньше он появлялся в основном мире (сервер грузил и слал ему
     * сотни чанков), а через тик улетал на платформу — второй перелёт между
     * мирами. Настоящая точка входа запоминается и вернётся после /login.
     * Премиум-автовход, IP-сессия и Bedrock без пароля — как раньше.
     * @return куда заспавнить, null — не трогать
     */
    Location redirectSpawn(Player p, Location orig) {
        if (!spawnOnPlatform || !authPlatformEnabled || p == null || orig == null
                || orig.getWorld() == null || antiBotService == null || !antiBotService.isEnabled()
                || (spawnService != null && spawnService.hasPrelogin())) {
            return null;
        }
        if (!Scheduler.isPrimaryThread()) {
            return null; // платформа строится только в главном потоке
        }
        UUID uuid = p.getUniqueId();
        try {
            if (sessionManager.isLoggedIn(uuid)
                    || (bedrockSupportService != null && bedrockSupportService.shouldBypassAuth(p))) {
                return null;
            }
            boolean autoEntry = (premiumService != null && premiumService.isEnabled())
                    || plugin.getConfig().getBoolean("session.auto_login_by_ip", false);
            if (autoEntry && accountStore.isRegistered(uuid)) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        Location spot = antiBotService.authPlatformSpotIfReady();
        if (spot == null) {
            return null;
        }
        spawnRedirected.put(uuid, orig.clone());
        return spot;
    }

    /**
     * Будет ли игрок ждать /reg или /login на платформе входа. AntiBotService
     * спрашивает после проверки: тогда он везёт игрока с арены прямо на
     * платформу (тот же мир), а не в основной мир и обратно.
     */
    public boolean holdsOnAuthPlatform(Player p) {
        if (p == null || !authPlatformEnabled || antiBotService == null || !antiBotService.isEnabled()
                || (spawnService != null && spawnService.hasPrelogin())
                || me.vorchun.registerplugin.util.ServerCore.isFolia()) {
            return false;
        }
        UUID uuid = p.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            return false;
        }
        // IP-сессия впустит зарегистрированного без платформы
        return !(accountStore.isRegistered(uuid) && me.vorchun.registerplugin.util.IpUtil.ipsTrusted()
                && sessionManager.canAutoLogin(p));
    }

    public void setTwoFactorQr(me.vorchun.registerplugin.service.TwoFactorQr qr) {
        this.twoFactorQr = qr;
    }
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
    private volatile boolean afkFirst = true;
    private volatile boolean authPlatformEnabled = true;
    private volatile boolean afkTrackAuthed = false;
    private final java.util.Set<UUID> afkFirstPending =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Location> authReturn =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Точки возврата игроков, вышедших с платформы/арены до входа
    // (иначе после перезахода позиция в playerdata — check-мир, и
    // настоящая точка теряется). uuid -> "world;x;y;z;yaw;pitch".
    private final Map<UUID, String> savedReturns = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean returnsSaveQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Object returnsFileLock = new Object();
    // Отдельный слушатель для Paper AsyncTabCompleteEvent: restoreState()
    // перерегистрирует AuthListener через unregisterAll(this) — рефлексивная
    // регистрация на this потерялась бы.
    private final Listener paperTabListener = new Listener() { };
    private volatile boolean requireConfirm = false;
    private volatile boolean enforceStrength = true;
    private volatile boolean protectDeath = true;
    private volatile int passwordInputTimeoutSec = 25;
    private volatile int passwordMinLen = 8;
    private volatile int passwordMaxLen = 64;

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
        loadSavedReturns();
        registerPaperTabBlock();
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
        // В быстром режиме антибота «сделай шаг» не нужен — проверка сразу
        afkFirst = plugin.getConfig().getBoolean("antibot.afk_first", true)
                && !plugin.getConfig().getBoolean("antibot.fast_mode", false);
        authPlatformEnabled = plugin.getConfig().getBoolean("security.auth_platform", true);
        spawnOnPlatform = plugin.getConfig().getBoolean("security.spawn_on_platform", true);
        afkTrackAuthed = plugin.getConfig().getBoolean("afk.kick_after_login", false);
        requireConfirm = plugin.getConfig().getBoolean("password.require_confirm", false);
        enforceStrength = plugin.getConfig().getBoolean("password.enforce_strength", true);
        protectDeath = plugin.getConfig().getBoolean("security.protect_inventory_on_death", true);
        int sec = plugin.getConfig().getInt("security.password_input_timeout_seconds", 25);
        passwordInputTimeoutSec = Math.max(5, Math.min(120, sec));
        // Длины пароля читаем один раз, а не на каждое сообщение в чат
        passwordMinLen = plugin.getConfig().getInt("password.min_length", 8);
        passwordMaxLen = Math.max(8, Math.min(256, plugin.getConfig().getInt("password.max_length", 64)));
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

    private static void sendCopyable(Player p, String text, String copy) {
        try {
            net.md_5.bungee.api.chat.TextComponent c = new net.md_5.bungee.api.chat.TextComponent(
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
            c.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, copy));
            p.spigot().sendMessage(c);
        } catch (Throwable t) {
            p.sendMessage(text);
        }
    }

    /**
     * Прокси подтвердил подписью (ProxyBridge/TRUST), что игрок уже вошёл на
     * другом сервере сети: зарегистрированный аккаунт входит без пароля и без
     * антибота — ожидания ввода/2FA/afk_first/проверка/очереди снимаются,
     * дальше общий afterLoginSuccess (маршрут, возврат, прокси).
     */
    public void trustNetworkLogin(Player p) {
        loginWithoutPassword(p, "join_network_auto_login");
    }

    /**
     * Вход зарегистрированного аккаунта без пароля и без антибота общим
     * путём (TRUST прокси, админский forcelogin, API). В главном потоке
     * выполняется сразу — вызывающий тут же видит isLoggedIn (иначе его
     * запасной ручной путь срабатывал раньше общего); из чужого потока —
     * переносится в поток игрока.
     * @param messageKey сообщение игроку; null — не отправлять
     */
    public void loginWithoutPassword(Player p, String messageKey) {
        if (p == null) {
            return;
        }
        if (!Scheduler.isPrimaryThread()) {
            Scheduler.runAtEntity(plugin, p, () -> loginWithoutPassword(p, messageKey));
            return;
        }
        UUID uuid = p.getUniqueId();
        if (!p.isOnline() || sessionManager.isLoggedIn(uuid) || !accountStore.isRegistered(uuid)) {
            return;
        }
        awaitingPassword.remove(uuid);
        riskyCommands.remove(uuid);
        afkFirstPending.remove(uuid);
        if (totpService != null) {
            totpService.cancelChallenge(uuid);
        }
        completeLoginNoPassword(p);
        if (!sessionManager.isLoggedIn(uuid)) {
            return;
        }
        // Проверку/очереди антибота снимает afterLoginSuccess
        afterLoginSuccess(p, false, null);
        if ("join_network_auto_login".equals(messageKey)) {
            messages.sendOrDefault(p, messageKey,
                    "{prefix}&#A0FFA0Вход подтверждён прокси — ты уже авторизован в сети");
        } else if (messageKey != null) {
            messages.send(p, messageKey);
        }
    }

    /** Вход без пароля (премиум, IP-сессия, Bedrock, прокси): сессия + AuthLoginEvent. */
    private void completeLoginNoPassword(Player p) {
        if (authService != null) {
            authService.completeLogin(p, "");
        } else {
            sessionManager.login(p);
        }
    }

    // ---------- шлюз антибота перед вводом пароля (D1) ----------

    /**
     * Можно ли сейчас регистрироваться: игрок не в очереди/на проверке/в
     * очереди входа, не в окне afk_first и антибот его пропустил
     * (прошёл проверку или она не требуется). Безопасно из чат-потока.
     */
    public boolean mayRegisterNow(UUID uuid) {
        if (uuid == null || afkFirstPending.contains(uuid)) {
            return false;
        }
        AntiBotService ab = antiBotService;
        if (ab == null) {
            return true;
        }
        try {
            return !ab.isBusy(uuid) && ab.mayRegister(uuid);
        } catch (Throwable t) {
            // Сбой — не бесплатный пропуск для бота
            return false;
        }
    }

    /**
     * Можно ли сейчас вводить пароль входа. На активной проверке — нет
     * (ввод пароля подменил бы ответ этапа). В очереди / очереди входа /
     * окне afk_first — только уже зарегистрированным аккаунтам.
     */
    public boolean mayLoginNow(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        AntiBotService ab = antiBotService;
        if (ab != null && ab.isChecking(uuid)) {
            return false;
        }
        if (afkFirstPending.contains(uuid) || (ab != null && ab.isBusy(uuid))) {
            return accountStore.isRegistered(uuid);
        }
        return true;
    }

    /** Отказ шлюза: что сказать игроку (вызывать в потоке игрока). */
    private void sendGateRefusal(Player p, boolean login) {
        UUID uuid = p.getUniqueId();
        if (afkFirstPending.contains(uuid)) {
            messages.send(p, "afk_move_prompt");
            return;
        }
        if (login) {
            messages.sendOrDefault(p, "antibot_wait",
                    "{prefix}&#FF6666Сначала пройди проверку на бота — следуй инструкциям в чате");
        } else {
            messages.sendOrDefault(p, "antibot_wait_register",
                    "{prefix}&#FF6666Регистрация откроется после проверки на бота — следуй инструкциям в чате");
        }
        if (antiBotService != null && antiBotService.isQueued(uuid)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("position", String.valueOf(antiBotService.queuePosition(uuid)));
            String m = messages.message("antibot_queue", ph);
            if (m != null && !m.isEmpty()) {
                p.sendMessage(m);
            }
        }
    }

    private void sendGateRefusalAsync(Player p, boolean login) {
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline()) {
                sendGateRefusal(p, login);
            }
        });
    }

    /**
     * Начать ввод пароля в чат (/login или /register без аргументов).
     * Шлюз антибота применяется здесь же. Вызывать в потоке игрока.
     */
    public void requestPasswordInput(Player p, boolean login) {
        if (p == null) {
            return;
        }
        UUID uuid = p.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            messages.send(p, "already_logged_in");
            return;
        }
        if (login ? !mayLoginNow(uuid) : !mayRegisterNow(uuid)) {
            sendGateRefusal(p, login);
            return;
        }
        long expiresAt = System.currentTimeMillis() + passwordInputTimeoutSec * 1000L;
        if (login) {
            awaitingPassword.put(uuid, new PendingPassword(PasswordMode.LOGIN, expiresAt));
            messages.send(p, "enter_password_chat_login");
        } else {
            awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER, expiresAt));
            messages.send(p, "enter_password_chat_register");
        }
    }

    /** Citizens-NPC — сущность Player с metadata "NPC": правила игрока к ней не применяются. */
    private static boolean isNpc(org.bukkit.entity.Entity e) {
        return e != null && e.hasMetadata("NPC");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        // Фоновая пакетная проверка с самого входа (флуд пакетами, битые координаты)
        if (antiBotService != null && !sessionManager.isLoggedIn(uuid)) {
            antiBotService.startPacketWatch(p);
        }
        // Страховка стэша: вещи, спрятанные до краша сервера, вернуть до
        // любой проверки/удержания (дёшево: файл читается только после краша)
        if (antiBotService != null) {
            antiBotService.recoverStash(p);
        }
        if (afkService != null) {
            afkService.onJoin(p);
        }

        // Заспавнен сразу на платформе — точка входа та, что была до этого
        Location redirectedFrom = spawnRedirected.remove(uuid);
        Location joinLoc = redirectedFrom != null ? redirectedFrom : p.getLocation();
        teleportService.saveJoinLocation(p, joinLoc);
        boolean joinInCheck = antiBotService != null && antiBotService.isCheckArea(joinLoc);
        restoreSavedReturn(uuid, joinInCheck);
        if (redirectedFrom != null && !joinInCheck) {
            authReturn.putIfAbsent(uuid, redirectedFrom.clone());
            if (antiBotService != null) {
                antiBotService.rememberReturn(uuid, redirectedFrom);
            }
        }

        // Синхронизируем видимость: скрываем всех неавторизованных от нового игрока
        syncHidingFor(p);

        if (bedrockSupportService != null && bedrockSupportService.shouldBypassAuth(p)) {
            completeLoginNoPassword(p);
            // Общий финализатор входа — тиком позже (как раньше сообщение):
            // маршрут after_auth / прокси-перенос не в самом PlayerJoinEvent
            Scheduler.runAtEntity(plugin, p, () -> {
                if (p.isOnline() && sessionManager.isLoggedIn(uuid)) {
                    afterLoginSuccess(p, false, "join_bedrock_auto_login");
                }
            });
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
            // Настоящая точка входа — для возврата после входа и для
            // сохранения при выходе с арены (иначе playerdata = check-мир)
            if (!joinInCheck) {
                authReturn.putIfAbsent(uuid, joinLoc.clone());
            }
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
        // С платформой входа отдельная «площадка ожидания» не нужна: дальше
        // игрока всё равно ставят на платформу (или на арену) — раньше это
        // был второй телепорт через 3 тика, а при перепроверке он уводил
        // игрока прямо с арены. Премиум ждёт ответа Mojang на платформе.
        if (authPlatformEnabled && (spawnService == null || !spawnService.hasPrelogin())) {
            if (premiumPending) {
                Scheduler.runAtEntityLater(plugin, p, () -> {
                    if (p.isOnline() && !sessionManager.isLoggedIn(uuid)
                            && (antiBotService == null || !antiBotService.isChecking(uuid))) {
                        sendToAuthPlatform(p);
                    }
                }, 3L);
            }
        } else if ((spawnService == null || !spawnService.hasPrelogin())
                && antiBotService != null && antiBotService.isEnabled()) {
            Location hold = antiBotService.holdingSpot();
            if (hold != null) {
                // Точка входа запоминается: премиум/IP-автовход или /login
                // вернут игрока сюда, а не оставят на площадке check-мира
                if (!joinInCheck) {
                    authReturn.putIfAbsent(uuid, joinLoc.clone());
                }
                // Через 3 тика, а не сразу: телепорт в тике входа заставлял
                // Essentials синхронно ждать загрузку профиля игрока (в профиле
                // spark — половина нагрузки плагина). Игрок эти 3 тика заморожен.
                Scheduler.runAtEntityLater(plugin, p, () -> {
                    if (p.isOnline() && !sessionManager.isLoggedIn(uuid)
                            && !antiBotService.isChecking(uuid)) {
                        teleportService.authorizeTeleport(uuid);
                        Compat.teleport(p, hold);
                    }
                }, 3L);
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
            premiumService.check(p, premium -> Scheduler.runAtEntity(plugin, p, () -> {
                // Пока ждали Mojang, игрок мог выйти или войти через /login —
                // тогда ни автовход, ни антибот/платформа ему не нужны
                if (!p.isOnline() || sessionManager.isLoggedIn(uuid)) {
                    return;
                }
                if (premium) {
                    // F30: лицензия заменяет пароль, но не второй фактор —
                    // с включённой 2FA сначала код (/2fa <код> или в чат)
                    AccountRecord prec = accountStore.get(uuid);
                    if (prec != null && prec.hasTotp() && totpService != null
                            && totpService.isEnabled() && !totpService.isTrusted(p)) {
                        totpService.beginChallenge(p, "", false);
                        timeoutService.start(p);
                        return;
                    }
                    completeLoginNoPassword(p);
                    if (sessionManager.isLoggedIn(uuid)) {
                        afterLoginSuccess(p, false, "join_premium_auto_login");
                        return;
                    }
                }
                startAntiBotOrAuth(p, uuid);
            }));
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
        startAntiBotOrAuth(p, uuid, false);
    }

    private void startAntiBotOrAuth(Player p, UUID uuid, boolean afkDone) {
        if (!p.isOnline() || sessionManager.isLoggedIn(uuid)) {
            return;
        }
        if (antiBotService != null && antiBotService.isEnabled() && !antiBotService.isChecking(uuid)) {
            boolean required = !(antiBotService.isOnlyNewPlayers() && accountStore.isRegistered(uuid))
                    || antiBotService.requiresRestartRecheck(uuid);
            if (required && afkFirst && !afkDone && afkService != null && afkService.isEnabled()
                    && antiBotService.queueMode() != 2) {
                // Лобби-очередь выкл: сначала AFK-проверка (шаг вперёд),
                // потом цепочка антибота. Бот без движения отлетает по
                // afk.initial_timeout — до арен он не доходит.
                afkFirstPending.add(uuid);
                if (hideDuringAuth) {
                    applyHiding(p);
                }
                sendToAuthPlatform(p);
                messages.send(p, "afk_move_prompt");
                sendModeIndicator(p);
                // Окно afk_first ограничено общим таймаутом авторизации: чат и
                // команды считаются активностью, и без таймаута бот, не делая
                // шага, висел бы здесь бесконечно. Снимается на шаге (onMove).
                timeoutService.start(p);
                return;
            }
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
            sendToAuthPlatform(p);
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

        // IP-сессия: только когда IP игроков настоящие (за прокси без
        // forwarding у всех адрес прокси — сессия досталась бы чужому)
        if (me.vorchun.registerplugin.util.IpUtil.ipsTrusted() && sessionManager.canAutoLogin(p)) {
            // F69: лут лобби (в т.ч. на курсоре) не уходит в мир и при IP-сессии
            if (antiBotService != null) {
                antiBotService.stripLobbyLoot(p);
            }
            completeLoginNoPassword(p);
            if (sessionManager.isLoggedIn(uuid)) {
                afterLoginSuccess(p, false, "join_auto_login");
                return;
            }
        }

        darkness(p);
        if (hideDuringAuth) {
            applyHiding(p);
        }
        sendToAuthPlatform(p);
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
        onAntiBotPassed(p, null);
    }

    /**
     * Проверку сняли без прохождения (антибот выключен/перезагружен, мир
     * недоступен), игрок онлайн — обычный auth-флоу БЕЗ «проверка пройдена».
     */
    public void onAntiBotAborted(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        UUID u = p.getUniqueId();
        if (sessionManager.isLoggedIn(u)) {
            return;
        }
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline() && !sessionManager.isLoggedIn(u)) {
                continueAuthFlow(p, u);
            }
        });
    }

    /**
     * @param back точка входа, куда AntiBotService возвращает игрока
     *             (null — неизвестна). Точка в check-мире не запоминается.
     */
    public void onAntiBotPassed(Player p, Location back) {
        if (p == null || !p.isOnline()) {
            return;
        }
        UUID uuid = p.getUniqueId();
        if (back != null && back.getWorld() != null
                && (antiBotService == null || !antiBotService.isCheckArea(back))) {
            authReturn.putIfAbsent(uuid, back.clone());
        }
        messages.sendOrDefault(p, "antibot_passed",
                "{prefix}&#A0FFA0Проверка на бота пройдена!");
        if (sessionManager.isLoggedIn(uuid)) {
            return;
        }
        // Только теперь включаем таймаут и напоминания — во время проверки игрок
        // не должен получать спам «введи /reg». Тиком позже: AntiBotService
        // только запланировал телепорт с арены на точку входа — платформа
        // должна запомнить её, а не арену.
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline() && !sessionManager.isLoggedIn(uuid)) {
                continueAuthFlow(p, uuid);
            }
        });
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
     * Публичная обёртка: применить auth-тьму с учётом режима и busy-игроков.
     */
    public void applyDarknessIfEnabled(Player p) {
        if (p != null && !sessionManager.isLoggedIn(p.getUniqueId())) {
            darkness(p);
        }
    }

    /**
     * Скрыть неавторизованного игрока от всех и всех от него.
     */
    // Darkness per security.auth_darkness: never -> never applied
    private void darkness(Player p) {
        if (!"never".equals(authDarkness)) {
            // Антибот сам управляет освещением проверяемого — не слепим
            // игрока в очереди/на проверке/в спектатор-грейсе.
            if (antiBotService != null && antiBotService.isBusy(p.getUniqueId())) {
                return;
            }
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
        // Сидел на платформе авторизации — возвращаем на точку входа.
        // Но если точка входа сама в check-мире (игрок
        // перезашёл, отключившись на арене/платформе) —
        // не возвращаем: там пустота, пусть решает
        // after_auth-цепочка/страховка в afterLoginSuccess.
        Location back = authReturn.remove(authed.getUniqueId());
        if (back != null && back.getWorld() != null && authed.isOnline()
                && (antiBotService == null
                    || !antiBotService.isCheckArea(back))) {
            final Location fb = back;
            Scheduler.runAtEntity(plugin, authed, () -> {
                if (authed.isOnline()) {
                    Compat.teleport(authed, fb);
                }
            });
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
     * Телепорт на общую платформу авторизации в check-мире:
     * точка входа сохраняется, после логина игрок возвращается.
     * Мир недоступен — игрок остаётся где был.
     */
    private void sendToAuthPlatform(Player p) {
        // Платформа — часть антибот-мира: при выключенном антиботе мир не
        // создаём; заданная prelogin-точка важнее платформы.
        if (!authPlatformEnabled || antiBotService == null || p == null
                || !antiBotService.isEnabled()
                || (spawnService != null && spawnService.hasPrelogin())) {
            return;
        }
        UUID uuid = p.getUniqueId();
        Location cur = p.getLocation();
        // Точку в check-мире (арена, лобби, сама платформа) не запоминаем —
        // возвращать туда после входа нельзя
        boolean added = !antiBotService.isCheckArea(cur)
                && authReturn.putIfAbsent(uuid, cur.clone()) == null;
        if (!antiBotService.toAuthPlatform(p)) {
            if (added) {
                authReturn.remove(uuid);
            }
        } else {
            // Общая платформа = одна точка для всех — взаимное скрытие
            // обязательно, иначе игроки стоят друг в друге.
            applyHiding(p);
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

    // ---------- сохранённые точки возврата (выход до входа из check-мира) ----------

    private static String serializeLoc(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ()
                + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location parseLoc(String s) {
        if (s == null) {
            return null;
        }
        // Имя мира — всё до пятой с конца ';' (в имени может встретиться ';')
        String[] parts = s.split(";");
        if (parts.length < 6) {
            return null;
        }
        int n = parts.length;
        String world = String.join(";", java.util.Arrays.copyOfRange(parts, 0, n - 5));
        org.bukkit.World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        try {
            return new Location(w, Double.parseDouble(parts[n - 5]), Double.parseDouble(parts[n - 4]),
                    Double.parseDouble(parts[n - 3]), Float.parseFloat(parts[n - 2]),
                    Float.parseFloat(parts[n - 1]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Один раз при старте (onEnable): файл маленький. */
    private void loadSavedReturns() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), RETURNS_FILE);
        if (!f.isFile()) {
            return;
        }
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            for (String k : y.getKeys(false)) {
                String v = y.getString(k);
                if (v == null) {
                    continue;
                }
                try {
                    savedReturns.put(UUID.fromString(k), v);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось прочитать " + RETURNS_FILE + ": " + t);
        }
    }

    /** Запись файла в фоне; серия изменений за тик — одна запись. */
    private void saveReturnsAsync() {
        if (!returnsSaveQueued.compareAndSet(false, true)) {
            return;
        }
        Scheduler.runAsync(plugin, () -> {
            returnsSaveQueued.set(false);
            org.bukkit.configuration.file.YamlConfiguration y =
                    new org.bukkit.configuration.file.YamlConfiguration();
            for (Map.Entry<UUID, String> en : savedReturns.entrySet()) {
                y.set(en.getKey().toString(), en.getValue());
            }
            synchronized (returnsFileLock) {
                try {
                    y.save(new java.io.File(plugin.getDataFolder(), RETURNS_FILE));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Не удалось записать " + RETURNS_FILE + ": " + t);
                }
            }
        });
    }

    /**
     * Вход: игрок стоит в check-мире (вышел оттуда до входа) — настоящую
     * точку берём из файла. Не в check-мире — запись устарела, удаляем.
     */
    private void restoreSavedReturn(UUID uuid, boolean joinInCheck) {
        String s = savedReturns.get(uuid);
        if (s == null) {
            return;
        }
        if (!joinInCheck) {
            forgetSavedReturn(uuid);
            return;
        }
        Location l = parseLoc(s);
        if (l != null && !antiBotService.isCheckArea(l)) {
            authReturn.put(uuid, l);
            antiBotService.rememberReturn(uuid, l);
        }
    }

    private void forgetSavedReturn(UUID uuid) {
        if (savedReturns.remove(uuid) != null) {
            saveReturnsAsync();
        }
    }

    // ---------- блокировка таб-комплита до входа ----------

    /**
     * Paper: AsyncTabCompleteEvent приходит ДО Brigadier и обычного
     * TabCompleteEvent — отмена гасит подсказки целиком (ники онлайн,
     * варпы, тяжёлые комплитеры). На Spigot класса нет — работает только
     * onTabComplete ниже.
     */
    private void registerPaperTabBlock() {
        final Class<? extends org.bukkit.event.Event> cls;
        final java.lang.reflect.Method getSender;
        try {
            cls = Class.forName("com.destroystokyo.paper.event.server.AsyncTabCompleteEvent")
                    .asSubclass(org.bukkit.event.Event.class);
            getSender = cls.getMethod("getSender");
        } catch (Throwable t) {
            return;
        }
        try {
            Bukkit.getPluginManager().registerEvent(cls, paperTabListener, EventPriority.LOWEST,
                    (l, ev) -> {
                        if (!cls.isInstance(ev) || !(ev instanceof org.bukkit.event.Cancellable)) {
                            return;
                        }
                        try {
                            Object s = getSender.invoke(ev);
                            if (s instanceof Player
                                    && !sessionManager.isLoggedIn(((Player) s).getUniqueId())) {
                                ((org.bukkit.event.Cancellable) ev).setCancelled(true);
                            }
                        } catch (Throwable ignored) {
                        }
                    }, plugin, false);
        } catch (Throwable t) {
            plugin.getLogger().warning("AsyncTabCompleteEvent: регистрация не удалась: " + t);
        }
    }

    /** До входа подсказки не нужны: ни одна разрешённая команда их не требует. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTabComplete(org.bukkit.event.server.TabCompleteEvent e) {
        if (e.getSender() instanceof Player
                && !sessionManager.isLoggedIn(((Player) e.getSender()).getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        spawnRedirected.remove(uuid);
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
        afkFirstPending.remove(uuid);
        Location back = authReturn.remove(uuid);
        // Вышел/кикнут до входа, стоя в check-мире (платформа, арена,
        // лобби): ядро сохранит позицию там — запоминаем настоящую точку,
        // при следующем входе она вернётся в authReturn.
        if (back != null && back.getWorld() != null && antiBotService != null
                && !sessionManager.isLoggedIn(uuid)
                && antiBotService.isCheckArea(p.getLocation())
                && !antiBotService.isCheckArea(back)) {
            savedReturns.put(uuid, serializeLoc(back));
            saveReturnsAsync();
        }
        if (antiBotService != null) {
            // F101: вышел посреди этапа после ошибок — провал для guard
            // (IP снимаем до cancelCheck; выход без ошибок не караем)
            if (plugin instanceof RegisterPlugin && antiBotService.quitCountsAsFail(uuid)) {
                ((RegisterPlugin) plugin).onAntiBotFailed(uuid,
                        me.vorchun.registerplugin.util.IpUtil.getIp(plugin, p));
            }
            antiBotService.stripLobbyLoot(p);
            antiBotService.cancelCheck(uuid);
            antiBotService.forget(uuid);
        }
        if (totpService != null) {
            totpService.cancelChallenge(uuid);
        }
        if (mailService != null) {
            mailService.clearVerifyCode(uuid);
        }
        sessionManager.logout(uuid);
        teleportService.clearSavedLocation(uuid);
        teleportService.cancelWait(uuid);
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

        boolean isLoginCmd = cleanBase.equals("l") || cleanBase.equals("login");
        boolean isAuthCmd = isLoginCmd || cleanBase.equals("reg") || cleanBase.equals("register");
        boolean isChangeCmd = cleanBase.equals("changepassword") || cleanBase.equals("changepw")
                || cleanBase.equals("cp") || cleanBase.equals("passwd");

        // Во время ввода пароля в чат — полная блокировка ВСЕХ команд
        // (иначе игрок мог уйти командой, не завершив ввод)
        if (!loggedIn && awaitingPassword.containsKey(uuid)) {
            e.setCancelled(true);
            return;
        }

        // Шлюз антибота (D1): регистрация — только после пройденной проверки
        // (не из очереди, не из окна afk_first, не с арены); вход из очереди
        // и окна afk_first — только для уже зарегистрированных аккаунтов.
        if (!loggedIn && isAuthCmd && !(isLoginCmd ? mayLoginNow(uuid) : mayRegisterNow(uuid))) {
            e.setCancelled(true);
            sendGateRefusal(p, isLoginCmd);
            return;
        }

        // Команда — активность для AFK-детектора: её засчитывает сам
        // AfkService (MONITOR, в т.ч. отменённые команды ввода пароля).

        // Пока идёт проверка на бота (или ждём входа на сервер) — команды
        // заблокированы, кроме /rpverify <токен> и команд авторизации,
        // которые уже пропустил шлюз выше (вход из лобби-очереди).
        if (!loggedIn && !isAuthCmd && antiBotService != null && antiBotService.isBusy(uuid)) {
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

                e.setCancelled(true);
                requestPasswordInput(p, isLoginCmd);
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
                e.setCancelled(true);
                requestPasswordInput(p, isLoginCmd);
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

            if (isLoginCmd) {
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
        UUID uuid = p.getUniqueId();
        Location to = e.getTo();
        if (sessionManager.isLoggedIn(uuid)) {
            // Авторизованных пасёт AFK-детектор (afk.track_authed): активность —
            // любой сдвиг (включая присед, вертикаль) и поворот камеры
            if (afkTrackAuthed && afkService != null && to != null) {
                Location from = e.getFrom();
                if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()
                        || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
                    afkService.onActivity(uuid);
                }
            }
            return;
        }
        if (to == null) {
            return;
        }
        Location from = e.getFrom();
        if (afkService != null) {
            afkService.onMove(e);
            // В spectator-грейсе игрок летает свободно — клэмпы очереди
            // и заморозка не применяются, AFK-детектор следит сам.
            if (afkService.isSpectating(uuid)) {
                return;
            }
        }
        // afk_first: игрок сделал шаг — AFK-проверка пройдена, запускаем
        // цепочку антибота. Микросдвиг пакетом (0.001) шагом не считается.
        if (afkFirstPending.contains(uuid)) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            if (dx * dx + dz * dz >= AFK_FIRST_STEP_SQ) {
                afkFirstPending.remove(uuid);
                // Во время проверки таймаут авторизации не идёт — после неё
                // continueAuthFlow запустит его заново
                timeoutService.stop(p);
                if (antiBotService == null || !antiBotService.isChecking(uuid)) {
                    // Шаг на платформе не должен «уехать» — позицию держим
                    e.setCancelled(true);
                    startAntiBotOrAuth(p, uuid, true);
                    return;
                }
            }
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

        // Заморозка отменой события: ядро само возвращает клиента на from
        // без PlayerTeleportEvent. setTo(from) превращалось в PLUGIN-телепорт,
        // который гасил наш же onTeleport — клиент не корректировался и
        // раз в секунду летел на «безопасную» точку. Повороты камеры не мешаем.
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            e.setCancelled(true);
        }

        teleportService.checkVoidFall(p);
    }

    // (обработчик PlayerItemHeldEvent объединён ниже — см. onHeld)

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())
                && !(antiBotService != null
                    && antiBotService.isCheckArea(e.getPlayer().getLocation()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShootBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
            // В PvP-зоне лобби стрельба разрешена (лук/арбалет из набора)
            if (antiBotService != null && antiBotService.isPvpArea(p.getLocation())) {
                return;
            }
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
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
            if (antiBotService != null && antiBotService.isPvpArea(p.getLocation())) {
                return;
            }
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        // Активность авторизованных (клики, блоки, инвентарь, транспорт)
        // AfkService слушает сам — здесь только правила авторизации
        boolean loggedIn = sessionManager.isLoggedIn(e.getPlayer().getUniqueId());
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
        if (!loggedIn) {
            // Кнопка скорости / сундук-набор в лобби-PvP
            if (antiBotService != null && e.getClickedBlock() != null
                    && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                    && antiBotService.onLobbyInteract(e.getPlayer(), e.getClickedBlock())) {
                return;
            }
            // В мире лобби/проверки разрешены клики по воздуху и удары:
            // одеть броню ПКМ, поесть, размахнуться мечом. Открытие чужих
            // блоков (RIGHT_CLICK_BLOCK) остаётся запрещённым.
            if (antiBotService != null && antiBotService.isCheckArea(e.getPlayer().getLocation())) {
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
                // а не открытие блока: предмет использовать можно, сам блок
                // (рычаг, дверь, калитка арены, повторитель) — нет
                org.bukkit.inventory.ItemStack hand = e.getItem();
                if (hand != null) {
                    String hn = hand.getType().name();
                    if (hand.getType().isEdible() || hn.endsWith("_HELMET")
                            || hn.endsWith("_CHESTPLATE") || hn.endsWith("_LEGGINGS")
                            || hn.endsWith("_BOOTS") || hn.equals("SHIELD")
                            || hn.equals("TOTEM_OF_UNDYING")) {
                        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                        e.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
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
        antiBotService.onToolChestClose(p, e.getInventory());
        antiBotService.onKitClose(p, e.getInventory());
        antiBotService.onKitEditorClose(p, e.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        // Мир проверки/лобби — приватная зона: ломать может только этап BLOCK
        // (целевой блок) либо админ с registerplugin.admin.
        if (antiBotService != null && antiBotService.isCheckArea(e.getBlock().getLocation())) {
            if (antiBotService.isChecking(p.getUniqueId())) {
                if (!antiBotService.onBlockBreak(p, e.getBlock())) {
                    e.setCancelled(true);
                } else {
                    // Целевой блок: дроп ставим сами ровно на место слома —
                    // натуральный разброс мог швырнуть его в бездну
                    e.setDropItems(false);
                    e.setExpToDrop(0);
                    antiBotService.dropTargetReward(e.getBlock());
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
        if (antiBotService != null && antiBotService.isCheckArea(e.getBlock().getLocation())
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
        if (isNpc(p)) {
            return;
        }
        if (sessionManager.isLoggedIn(p.getUniqueId())) {
            // F70: вошедший (тестер PvP-зоны лобби) — подобранный там лут тоже
            // метим, иначе копии и лут арены уходят в мир без тега. Только
            // PvP-арена: свои вещи админа в мире проверки не трогаем.
            if (antiBotService != null && antiBotService.isPvpArea(p.getLocation())
                    && antiBotService.isCheckArea(p.getLocation())) {
                antiBotService.tagLobbyItem(e.getItem());
            }
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
        UUID uuid = p.getUniqueId();
        if (sessionManager.isLoggedIn(uuid)) {
            return;
        }
        org.bukkit.inventory.Inventory top = e.getView().getTopInventory();
        int topSize = top == null ? 0 : top.getSize();
        int raw = e.getRawSlot();
        boolean inTop = raw >= 0 && raw < topSize;
        if (antiBotService != null && antiBotService.isChecking(uuid)) {
            // Клики по GUI пазла — обрабатывает AntiBotService (только
            // слоты самого окна: номер слота своего инвентаря в окне пазла
            // вне диапазона)
            if (inTop && antiBotService.onPuzzleClick(p, top, raw)) {
                e.setCancelled(true);
                return;
            }
            // Сундук инструмента этапа BLOCK: из него — только забирать,
            // в своём инвентаре — раскладывать (взял кирку — положил в
            // хотбар). Положить своё в сундук нельзя — но это промах
            // человека, а не бот: без нарушения слот-лока.
            if (antiBotService.isToolChestTop(p, top)) {
                if (inTop ? isTakeFromTop(e, p) : isOwnInvClick(e, topSize)) {
                    return;
                }
                e.setCancelled(true);
                return;
            }
            // Окно пазла открыто, клик по своему инвентарю — просто отмена
            if (!inTop && antiBotService.getCurrentStage(uuid) == AntiBotService.Stage.PUZZLE
                    && top != null && top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                e.setCancelled(true);
                return;
            }
        }
        // Виртуальный инвентарь набора и PvP-сундук лобби: из окна только
        // забирать (PICKUP/shift-клик с пустым курсором). SWAP_OFFHAND (F),
        // цифры и «положить» перенесли бы настоящую вещь в сундук/набор.
        if (antiBotService != null && antiBotService.isKitInv(top)) {
            if (inTop ? isTakeFromTop(e, p) : isOwnInvClick(e, topSize)) {
                return;
            }
            e.setCancelled(true);
            return;
        }
        if (antiBotService != null && top != null
                && top.getType() == org.bukkit.event.inventory.InventoryType.CHEST
                && antiBotService.isInQueueLobby(uuid)
                && antiBotService.isPvpArea(p.getLocation())) {
            if (inTop ? isTakeFromTop(e, p) : isOwnInvClick(e, topSize)) {
                return;
            }
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        // Блокиратор слотов: попытка двигать вещи во время проверки —
        // считаем нарушение, после лимита кик (antibot.slot_lock.*).
        // Клик по пустому месту — не попытка.
        if (antiBotService != null && (hasItem(e.getCurrentItem()) || hasItem(e.getCursor()))) {
            antiBotService.onSlotViolation(p);
        }
    }

    private static boolean hasItem(org.bukkit.inventory.ItemStack it) {
        return it != null && it.getType() != org.bukkit.Material.AIR;
    }

    /**
     * Клик по верхнему окну — чистое «забрать»: курсор пуст, предмет
     * уходит на курсор/в свой инвентарь, либо цифрой в ПУСТОЙ слот хотбара.
     */
    private static boolean isTakeFromTop(InventoryClickEvent e, Player p) {
        if (hasItem(e.getCursor())) {
            return false;
        }
        switch (e.getAction()) {
            case PICKUP_ALL:
            case PICKUP_HALF:
            case PICKUP_ONE:
            case PICKUP_SOME:
            case MOVE_TO_OTHER_INVENTORY:
                return true;
            case HOTBAR_SWAP: {
                int btn = e.getHotbarButton();
                return e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                        && btn >= 0 && btn <= 8 && !hasItem(p.getInventory().getItem(btn));
            }
            default:
                return false;
        }
    }

    /**
     * Клик по своему (нижнему) инвентарю, который не переносит вещи в
     * верхнее окно: shift-клик, сбор на курсор и выброс — запрещены.
     */
    private static boolean isOwnInvClick(InventoryClickEvent e, int topSize) {
        if (e.getRawSlot() < topSize) {
            return false;
        }
        switch (e.getAction()) {
            case MOVE_TO_OTHER_INVENTORY:
            case COLLECT_TO_CURSOR:
            case DROP_ALL_CURSOR:
            case DROP_ONE_CURSOR:
            case DROP_ALL_SLOT:
            case DROP_ONE_SLOT:
            case UNKNOWN:
                return false;
            default:
                return true;
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
                    if (s == AntiBotService.Stage.BLOCK
                            && antiBotService.isToolChestTop(p, e.getInventory())) {
                        antiBotService.ensureToolChestNow(p);
                        antiBotService.onToolChestOpen(p);
                    }
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
                        || (antiBotService.isCheckArea(p.getLocation())
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
                    && antiBotService.isCheckArea(e.getPlayer().getLocation()))) {
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
        if (antiBotService != null && antiBotService.isCheckArea(p.getLocation())) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (sessionManager.isLoggedIn(e.getPlayer().getUniqueId())) {
            return;
        }
        // NPC Citizens телепортирует сам плагин (tphere, путь, респавн)
        if (isNpc(e.getPlayer())) {
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
        // Коррекция в пределах того же блока (плагин «вернул на место») —
        // просто отмена: без броска на «безопасную» точку (крышу столба)
        Location tf = e.getFrom();
        Location tt = e.getTo();
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (tt != null && tt.getWorld() == tf.getWorld()
                && (cause == PlayerTeleportEvent.TeleportCause.PLUGIN
                    || cause == PlayerTeleportEvent.TeleportCause.UNKNOWN)
                && tt.getBlockX() == tf.getBlockX() && tt.getBlockY() == tf.getBlockY()
                && tt.getBlockZ() == tf.getBlockZ()) {
            return;
        }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaveCheckWorld(PlayerTeleportEvent e) {
        if (antiBotService == null) {
            return;
        }
        // Зона, а не мир: в режиме запасной арены лобби/арены висят
        // в основном мире — выход из них тоже вычищает лут лобби
        if (antiBotService.isCheckArea(e.getFrom()) && !antiBotService.isCheckArea(e.getTo())) {
            antiBotService.stripLobbyLoot(e.getPlayer());
        }
    }

    private final Map<UUID, Long> lastSafeTeleport = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (!(e.getEntered() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getEntered();
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleExit(VehicleExitEvent e) {
        if (!(e.getExited() instanceof Player)) {
            return;
        }

        Player p = (Player) e.getExited();
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
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

        // Чат — активность для AFK: засчитывает сам AfkService (MONITOR)
        boolean loggedIn = sessionManager.isLoggedIn(uuid);

        PendingPassword pending = awaitingPassword.get(uuid);
        // Пока ждали пароль, игрока перевели из очереди на проверку: ввод
        // пароля снят — сообщение идёт ответом этапа (ниже), а не паролем
        if (pending != null && !loggedIn && antiBotService != null && antiBotService.isChecking(uuid)) {
            awaitingPassword.remove(uuid);
            pending = null;
            if (!antiBotService.expectsChat(uuid)) {
                e.setCancelled(true);
                e.setMessage("");
                e.getRecipients().clear();
                return;
            }
        }
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
            final String code = e.getMessage() == null ? "" : e.getMessage().trim();
            e.setCancelled(true);
            e.setMessage("");
            e.getRecipients().clear();
            if (afkService != null) {
                afkService.onActivity(uuid);
            }
            // submit завершает вход (AuthLoginEvent синхронный) — только в
            // потоке игрока, не в асинхронном чат-потоке
            Scheduler.runAtEntity(plugin, p, () -> {
                if (!p.isOnline() || !totpService.hasChallenge(uuid)) {
                    return;
                }
                int res = totpService.submit(p, code, authService);
                if (res == 0) {
                    afterLoginSuccess(p, false);
                } else {
                    messages.send(p, res == 1 ? "twofa_wrong_code" : "twofa_expired");
                }
            });
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
        final int maxLen = passwordMaxLen;

        // Шлюз антибота (D1) — защита в глубину: ожидание пароля могло
        // начаться до перевода в очередь/окно afk_first
        if (pending.mode == PasswordMode.LOGIN ? !mayLoginNow(uuid)
                : (pending.mode == PasswordMode.REGISTER || pending.mode == PasswordMode.REGISTER_CONFIRM)
                    && !mayRegisterNow(uuid)) {
            awaitingPassword.remove(uuid);
            sendGateRefusalAsync(p, pending.mode == PasswordMode.LOGIN);
            return;
        }
        // Ввод пароля — активность для AFK-детектора (Fabric-клиент с
        // открытым чатом не шлёт движения — иначе кик «за бездействие»)
        if (afkService != null) {
            afkService.onActivity(uuid);
        }

        switch (pending.mode) {
            case LOGIN:
            case REGISTER: {
                if (raw.length() > maxLen) {
                    sendAsync(p, "password_too_long");
                    return;
                }

                // Для регистрации при require_confirm — сначала валидация, потом подтверждение
                if (pending.mode == PasswordMode.REGISTER && requireConfirm) {
                    me.vorchun.registerplugin.service.PasswordValidator.ValidationResult vr =
                            me.vorchun.registerplugin.service.PasswordValidator.validate(raw,
                                    passwordMinLen, maxLen, enforceStrength,
                                    easyPasswordList == null ? null : easyPasswordList.getAllowed());
                    String err = validationKey(vr);
                    if (err != null) {
                        sendAsync(p, err);
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
                    Scheduler.runAtEntity(plugin, p, () -> {
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
                    loginViaService(p, pass, true, false);
                } else {
                    registerViaService(p, pass, false);
                }
                return;
            }

            case REGISTER_CONFIRM: {
                awaitingPassword.remove(uuid);
                if (!raw.equals(pending.data)) {
                    sendAsync(p, "passwords_dont_match");
                    // возвращаем на шаг ввода пароля
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (p.isOnline()) {
                            awaitingPassword.put(uuid, new PendingPassword(PasswordMode.REGISTER,
                                    System.currentTimeMillis() + passwordInputTimeoutSec * 1000L));
                            messages.send(p, "enter_password_chat_register");
                        }
                    });
                    return;
                }
                final String pass = pending.data;
                // Тот же путь, что и без подтверждения: общий afterLoginSuccess
                // (выход из очередей, маршрут after_auth, прокси-перенос)
                if (authService == null) {
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (p.isOnline()) {
                            rp.handleRegister(p, new String[]{pass});
                        }
                    });
                    return;
                }
                registerViaService(p, pass, false);
                return;
            }

            case CHANGE_OLD: {
                if (raw.length() > maxLen) {
                    sendAsync(p, "password_too_long");
                    return;
                }
                // Старый пароль проверяется вместе со сменой — в пуле AuthService
                // (PBKDF2 не в чат-потоке; неудача считается LoginAttemptService)
                awaitingPassword.put(uuid, new PendingPassword(PasswordMode.CHANGE_NEW,
                        System.currentTimeMillis() + passwordInputTimeoutSec * 1000L, null, raw));
                sendAsync(p, "enter_password_chat_new");
                return;
            }

            case CHANGE_NEW: {
                me.vorchun.registerplugin.service.PasswordValidator.ValidationResult vr =
                        me.vorchun.registerplugin.service.PasswordValidator.validate(raw,
                                passwordMinLen, maxLen, enforceStrength,
                                easyPasswordList == null ? null : easyPasswordList.getAllowed());
                String err = validationKey(vr);
                if (err != null) {
                    sendAsync(p, err);
                    return;
                }
                if (requireConfirm) {
                    awaitingPassword.put(uuid, new PendingPassword(PasswordMode.CHANGE_CONFIRM,
                            System.currentTimeMillis() + passwordInputTimeoutSec * 1000L, raw,
                            pending.oldPassword));
                    sendAsync(p, "enter_password_chat_confirm");
                    return;
                }
                awaitingPassword.remove(uuid);
                applyNewPassword(p, pending.oldPassword, raw);
                return;
            }

            case CHANGE_CONFIRM: {
                awaitingPassword.remove(uuid);
                if (!raw.equals(pending.data)) {
                    sendAsync(p, "passwords_dont_match");
                    return;
                }
                applyNewPassword(p, pending.oldPassword, pending.data);
                return;
            }
        }
    }

    private me.vorchun.registerplugin.service.LoginAttemptService loginAttempts() {
        return plugin instanceof RegisterPlugin ? ((RegisterPlugin) plugin).getLoginAttemptService() : null;
    }

    /** Ключ сообщения об ошибке валидации пароля; null — пароль годен. */
    private static String validationKey(me.vorchun.registerplugin.service.PasswordValidator.ValidationResult vr) {
        if (vr == null) {
            return "password_invalid";
        }
        switch (vr) {
            case VALID:
                return null;
            case TOO_SHORT:
                return "password_too_short";
            case TOO_LONG:
                return "password_too_long";
            case TOO_WEAK:
                return "password_too_weak";
            default:
                return "password_invalid";
        }
    }

    /** Вход по паролю через AuthService (пул хеширования) + общий финализатор. */
    private void loginViaService(Player p, String pass, boolean fromChat, boolean insecure) {
        authService.login(p, pass, fromChat, result -> {
            switch (result) {
                case OK:
                    afterLoginSuccess(p, false);
                    if (insecure) {
                        warnPasswordInLogs(p);
                    }
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

    /** Регистрация через AuthService + общий финализатор (все пути регистрации). */
    private void registerViaService(Player p, String pass, boolean insecure) {
        authService.register(p, pass, (result, validation) -> {
            if (result == me.vorchun.registerplugin.service.AuthService.Result.OK) {
                afterLoginSuccess(p, true);
                if (insecure) {
                    warnPasswordInLogs(p);
                }
                return;
            }
            String err = validation == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.VALID
                    ? null : validationKey(validation);
            if (err != null) {
                messages.send(p, err);
            } else if (accountStore.isRegistered(p.getUniqueId())) {
                messages.send(p, "already_registered");
            }
            // Отказы шлюза антибота/хранилища/пула AuthService шлёт сам,
            // без колбэка — здесь второе сообщение не нужно
        });
    }

    /**
     * Смена пароля: старый проверяется в пуле AuthService вместе с
     * перехешированием нового. Неверный старый — попытка в LoginAttemptService
     * (перебор текущего пароля через /cp упирается в тот же лимит, что и /login).
     */
    private void applyNewPassword(Player p, String oldPassword, String newPassword) {
        if (authService == null) {
            return;
        }
        final UUID uuid = p.getUniqueId();
        authService.changePassword(p, oldPassword, newPassword, oldPassword != null, validation -> {
            if (validation == null) {
                messages.send(p, "change_password_wrong_old");
                me.vorchun.registerplugin.service.LoginAttemptService la = loginAttempts();
                if (la != null) {
                    la.onFail(p);
                }
                return;
            }
            if (validation == me.vorchun.registerplugin.service.PasswordValidator.ValidationResult.VALID) {
                me.vorchun.registerplugin.service.LoginAttemptService la = loginAttempts();
                if (la != null) {
                    la.reset(uuid);
                }
                messages.send(p, "change_password_success");
                return;
            }
            String err = validationKey(validation);
            messages.send(p, err != null ? err : "password_too_weak");
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
            // NPC Citizens (Sentinel-стражи, боевые NPC) — урон как обычно
            if (isNpc(p)) {
                return;
            }
            // PvP-урон внутри зоны — разрешён любому, кто физически за линией
            // (очередь, entry-очередь, залогиненный — неважно); стрелы из
            // лука/арбалета набора — тоже PvP
            EntityDamageEvent.DamageCause cause = e.getCause();
            if ((cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                    || cause == EntityDamageEvent.DamageCause.PROJECTILE)
                    && antiBotService != null
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
            if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
                // Punch on puzzle item frame (PUZZLE stage) = remove extra tile
                if (antiBotService != null
                        && antiBotService.onPuzzleFrameHit(p, e.getEntity())) {
                    e.setCancelled(true);
                    return;
                }
                // Атакующий и жертва за линией PvP-зоны — разрешено всем,
                // кто физически в зоне (очередь/entry/залогиненный тестер)
                if (e.getEntity() instanceof Player
                        && antiBotService != null
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
                if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
                    // Стрелок и жертва-игрок в PvP-зоне лобби — выстрел засчитан
                    if (e.getEntity() instanceof Player
                            && antiBotService != null
                            && antiBotService.isPvpArea(p.getLocation())
                            && antiBotService.isPvpArea(e.getEntity().getLocation())) {
                        return;
                    }
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
        if (!sessionManager.isLoggedIn(e.getPlayer().getUniqueId())
                && !(antiBotService != null
                    && antiBotService.isCheckArea(e.getPlayer().getLocation()))) {
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
        if (!mayLoginNow(p.getUniqueId())) {
            sendGateRefusal(p, true);
            return;
        }
        if (authService == null) {
            messages.send(p, "insecure_mode_warn");
            ((RegisterPlugin) plugin).handleLogin(p, new String[]{password});
            return;
        }
        loginViaService(p, password, false, true);
    }

    /** Регистрация по паролю из команды. */
    private void registerInsecure(Player p, String password) {
        if (!mayRegisterNow(p.getUniqueId())) {
            sendGateRefusal(p, false);
            return;
        }
        if (authService == null) {
            messages.send(p, "insecure_mode_warn");
            ((RegisterPlugin) plugin).handleRegister(p, new String[]{password});
            return;
        }
        registerViaService(p, password, true);
    }

    /** Смена пароля командами /changepassword <old> <new>. */
    private void changePasswordInsecure(Player p, String oldPassword, String newPassword) {
        if (authService == null) {
            messages.send(p, "change_password_usage");
            return;
        }
        applyNewPassword(p, oldPassword, newPassword);
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
     * Общая часть после успешного входа/регистрации (пароль, регистрация).
     * Маршрут игрока: прокси-трансфер в лобби → спавны → лобби-локация.
     */
    public void afterLoginSuccess(Player p, boolean firstTime) {
        afterLoginSuccess(p, firstTime, firstTime ? "register_success" : "login_success");
    }

    /**
     * ЕДИНЫЙ финализатор любого успешного входа: пароль, регистрация (с
     * подтверждением и без), IP-сессия, премиум, Bedrock, админский
     * forcelogin, API, TRUST от прокси. Сессия уже должна быть открыта
     * (SessionManager.login). Снимает ожидания/проверку/очереди, выводит из
     * check-мира, возвращает на точку входа, ведёт по after_auth / прокси.
     * @param messageKey итоговое сообщение игроку; null — не отправлять
     */
    public void afterLoginSuccess(Player p, boolean firstTime, String messageKey) {
        if (antiBotService != null) {
            antiBotService.stopPacketWatch(p);
        }
        if (p == null) {
            return;
        }
        if (!Scheduler.isPrimaryThread()) {
            Scheduler.runAtEntity(plugin, p, () -> afterLoginSuccess(p, firstTime, messageKey));
            return;
        }
        if (!p.isOnline()) {
            return;
        }
        UUID uuid = p.getUniqueId();
        awaitingPassword.remove(uuid);
        riskyCommands.remove(uuid);
        afkFirstPending.remove(uuid);
        forgetSavedReturn(uuid);
        if (afkService != null) {
            afkService.onLogin(uuid);
        }
        timeoutService.stop(p);
        reminderService.stop(p);
        Compat.updateCommands(p);
        Compat.clearAuthDarkness(p);
        // Вход в обход проверки (forcelogin, TRUST): проверку снимаем с
        // возвратом инвентаря и точки входа — иначе вещи остались бы в стейте арены
        if (antiBotService != null && antiBotService.isChecking(uuid)) {
            antiBotService.cancelCheck(uuid, true);
        }
        revealPlayer(p);
        // Игрок мог залогиниться, стоя в лобби-очереди — снимаем с очередей,
        // иначе он навсегда остался бы в лобби ожидания
        if (antiBotService != null) {
            antiBotService.leaveQueues(p);
        }
        boolean proxyTransfer = plugin.getConfig().getBoolean("proxy_server.enabled", false)
                && teleportService.isProxyMode();
        // D2: цель entry-очереди ждала авторизации — теперь переносим туда
        String entryTarget = antiBotService == null ? null : antiBotService.consumeEntryTarget(uuid);
        if (entryTarget != null && teleportService.isProxyMode()) {
            teleportService.transferToServer(p, entryTarget);
        } else if (proxyTransfer) {
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
                            Compat.teleport(p, dest);
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
                            Compat.teleport(p, dest);
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
        // Страховка: после входа игрок не должен остаться
        // в check-мире — точка входа могла быть там
        // (перезаход с арены/платформы), режим "last"/
        // "none", прокси-фолбэк на спавн check-мира и т.п.
        // Проверка отложенная: даём отложенным
        // телепортам выше сработать, потом —
        // принудительно на спавн основного мира.
        final AntiBotService ab = antiBotService;
        if (ab != null) {
            final TeleportService ts = teleportService;
            Scheduler.runAtEntityLater(plugin, p, new Runnable() {
                @Override
                public void run() {
                    if (p.isOnline() && ab.isCheckArea(p.getLocation())) {
                        org.bukkit.World main = Bukkit.getWorlds().isEmpty()
                                ? null : Bukkit.getWorlds().get(0);
                        if (main != null && !ab.isCheckWorld(main)) {
                            ts.authorizeTeleport(p.getUniqueId());
                            Compat.teleport(p, main.getSpawnLocation());
                            p.setFallDistance(0f);
                        }
                    }
                }
            }, 10L);
        }
        if (messageKey != null) {
            messages.send(p, messageKey);
        }
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
        String[] a = args.split("\\s+");
        String sub = a[0].toLowerCase(Locale.ROOT);
        AccountRecord rec = accountStore.get(uuid);
        if (sub.equals("off") || sub.equals("disable") || sub.equals("выкл")) {
            // Выключение — только с кодом: иначе угнанная сессия (IP-сессия,
            // чужой ПК) одной командой навсегда снимает второй фактор
            if (rec != null && rec.hasTotp()) {
                if (a.length < 2) {
                    messages.sendOrDefault(p, "twofa_off_need_code",
                            "{prefix}&#FFFFFFЧтобы выключить 2FA, введи код из приложения: &#A0FFA0/2fa off 123456");
                    return;
                }
                if (!totpService.check(rec.getTotpSecret(), a[1])) {
                    messages.send(p, "twofa_wrong_code");
                    me.vorchun.registerplugin.service.LoginAttemptService la = loginAttempts();
                    if (la != null) {
                        la.onFail(p);
                    }
                    return;
                }
            }
            totpService.disable(p);
            messages.send(p, "twofa_disabled_done");
            return;
        }
        if (sub.equals("on") || sub.equals("enable") || sub.equals("вкл")) {
            // Уже включена: новый секрет без кода от старого = та же дыра
            if (rec != null && rec.hasTotp()) {
                messages.sendOrDefault(p, "twofa_already_enabled",
                        "{prefix}&#FFFF66Двухфакторка уже включена. Сменить секрет: сначала &#A0FFA0/2fa off <код>");
                return;
            }
            String secret = totpService.generateSecret();
            pending2faPassword.put(uuid, secret);
            String url = totpService.buildUrl(p.getName(), secret);
            // Ссылку otpauth:// Minecraft не открывает — даём QR на карте в руке
            // (сканируется камерой в приложении) и ключ текстом для ручного ввода
            boolean qr = twoFactorQr != null && twoFactorQr.give(p, url);
            messages.sendOrDefault(p, qr ? "twofa_setup_qr" : "twofa_setup",
                    qr ? "{prefix}&#FFFFFFВ руке — &#FFD700QR-код&#FFFFFF. Открой Google Authenticator / Яндекс Ключ → «+» → «Сканировать QR-код» и наведи камеру на карту."
                            : "{prefix}&#FFFFFFОткрой приложение-аутентификатор и добавь ключ ниже вручную.");
            if (!qr) {
                messages.sendOrDefault(p, "twofa_qr_no_slot",
                        "{prefix}&#FF6666QR-код не выдан: освободи слот в хотбаре и снова напиши &#FFD700/2fa on&#FF6666 (или введи ключ вручную).");
            }
            // Ключ группами по 4 — так удобнее вводить вручную; клик копирует без пробелов
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < secret.length(); i++) {
                if (i > 0 && i % 4 == 0) {
                    grouped.append(' ');
                }
                grouped.append(secret.charAt(i));
            }
            sendCopyable(p, messages.format("&7Ключ для ручного ввода (клик — скопировать): &f&l" + grouped, new HashMap<>()), secret);
            messages.send(p, "twofa_confirm_hint");
            return;
        }
        if (sub.equals("cancel") || sub.equals("отмена")) {
            pending2faPassword.remove(uuid);
            if (twoFactorQr != null) {
                twoFactorQr.take(p);
            }
            messages.sendOrDefault(p, "twofa_cancelled", "{prefix}&#FFFF66Включение 2FA отменено.");
            return;
        }
        // ввод кода для включения
        String secret = pending2faPassword.get(uuid);
        if (secret != null && totpService.check(secret, args)) {
            pending2faPassword.remove(uuid);
            totpService.enable(p, secret);
            if (twoFactorQr != null) {
                twoFactorQr.take(p);
            }
            messages.send(p, "twofa_enabled_done");
        } else if (secret != null) {
            messages.send(p, "twofa_wrong_code");
        } else {
            messages.send(p, "twofa_usage");
        }
    }

    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}$");

    private void handleEmail(Player p, String args) {
        if (mailService == null || !mailService.isEnabled()) {
            messages.send(p, "email_disabled");
            if (p.hasPermission("registerplugin.admin")) {
                p.sendMessage(org.bukkit.ChatColor.GRAY + "Админ: включи email.enabled и заполни email.smtp в config.yml, "
                        + "затем проверь: /authadmin testmail <почта>");
            }
            return;
        }
        // Запасной ввод кода командой (/email 123456) — если чат-плагин перехватывает сообщения
        if (args.matches("\\d{6}") && pendingEmailCode.containsKey(p.getUniqueId())) {
            handleEmailCode(p, p.getUniqueId(), args);
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
        if (!EMAIL.matcher(args).matches()) {
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
            mailService.sendCode(uuid, r.getEmail(), "recover", p.getName(), ok -> {
                if (!p.isOnline()) {
                    return;
                }
                messages.send(p, ok ? "recover_code_sent" : "email_send_failed");
                if (ok) {
                    // Письмо + переход в почту + ввод кода — дольше минуты на вход:
                    // продлеваем таймаут (не больше 5 мин, чтобы ник не держали)
                    timeoutService.extend(p, Math.min(300, mailService.codeMinutes() * 60));
                }
            });
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
        // Снятие/замена эффекта (newEffect == null) пропускаем ВСЕГДА — иначе
        // наш же removePotionEffect(BLINDNESS) блокировался бы этим
        // обработчиком и слепота не снималась никогда.
        if (e.getNewEffect() == null) {
            return;
        }
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
            org.bukkit.potion.PotionEffectType type = e.getNewEffect().getType();
            boolean dark = type.equals(org.bukkit.potion.PotionEffectType.BLINDNESS)
                    || type.getName().equalsIgnoreCase("darkness");
            // Игрок занят антиботом (очередь/проверка/вход) — слепоту и
            // темноту не пускаем вовсе, откуда бы ни пришла.
            if (dark && antiBotService != null && antiBotService.isBusy(p.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
            // Своя auth-темнота на экранах входа — разрешена.
            if (dark) {
                return;
            }
            // Эффекты от плагинов (наша кнопка Speed, NIGHT_VISION и т.п.) —
            // разрешены: без этого addPotionEffect(SPEED) с кнопки лобби
            // отменялся прямо здесь и «не работал».
            if (e.getCause() == org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN) {
                return;
            }
            // В PvP-зоне лобби — полноценное PvP: зелья вреда/дебаффы тоже идут.
            if (antiBotService != null && antiBotService.isPvpArea(p.getLocation())) {
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
        if (!sessionManager.isLoggedIn(p.getUniqueId()) && !isNpc(p)) {
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
            if (antiBotService != null && antiBotService.isCheckArea(e.getRespawnLocation())) {
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

    private static final int READY = 967612790;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100b) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100b) == READY;
    }
}
