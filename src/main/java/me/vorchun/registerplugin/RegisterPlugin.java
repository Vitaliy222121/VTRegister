// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.api.RegisterPluginAPI;
import me.vorchun.registerplugin.command.AuthAdminCommand;
import me.vorchun.registerplugin.command.ChangePasswordCommand;
import me.vorchun.registerplugin.command.LoginCommand;
import me.vorchun.registerplugin.command.RegisterCommand;
import me.vorchun.registerplugin.listener.AntiBotGuard;
import me.vorchun.registerplugin.listener.AuthListener;
import me.vorchun.registerplugin.service.AccountStore;
import me.vorchun.registerplugin.service.AntiBotService;
import me.vorchun.registerplugin.service.AuthService;
import me.vorchun.registerplugin.service.AuthTimeoutService;
import me.vorchun.registerplugin.service.BedrockSupportService;
import me.vorchun.registerplugin.service.CommandLogGuard;
import me.vorchun.registerplugin.service.EasyPasswordList;
import me.vorchun.registerplugin.service.ImportService;
import me.vorchun.registerplugin.service.HealthService;
import me.vorchun.registerplugin.service.LoginAttemptService;
import me.vorchun.registerplugin.service.MailService;
import me.vorchun.registerplugin.service.MessageService;
import me.vorchun.registerplugin.service.PasswordHasher;
import me.vorchun.registerplugin.service.PasswordValidator;
import me.vorchun.registerplugin.service.PremiumService;
import me.vorchun.registerplugin.service.ReminderService;
import me.vorchun.registerplugin.service.StateSync;
import me.vorchun.registerplugin.service.SessionManager;
import me.vorchun.registerplugin.service.SpawnService;
import me.vorchun.registerplugin.service.TeleportService;
import me.vorchun.registerplugin.service.TotpService;
import me.vorchun.registerplugin.util.Compat;
import me.vorchun.registerplugin.util.ConfigMerger;
import me.vorchun.registerplugin.util.Scheduler;
import me.vorchun.registerplugin.util.ServerCore;

/**
 * VTRegister (RegisterPlugin) — плагин авторизации и защиты аккаунтов.
 *
 * Архитектура:
 *   RegisterPlugin            — сборка всего и жизненный цикл;
 *   AccountStore + storage/*  — YAML / SQLite / MySQL / MariaDB / PostgreSQL;
 *   AuthService               — единственное место проверки пароля (вне главного потока);
 *   AntiBotService + Guard    — проверка на бота в отдельном мире + защита до входа;
 *   CommandLogGuard           — пароль не попадает в консоль и логи;
 *   HealthService            — монитор консистентности сборки.
 */
public final class RegisterPlugin extends JavaPlugin {

    private static RegisterPlugin instance;
    private static final RegisterPluginAPI API = new RegisterPluginAPI();

    private AccountStore accountStore;
    private SessionManager sessionManager;
    private MessageService messageService;
    private AuthTimeoutService authTimeoutService;
    private ReminderService reminderService;
    private LoginAttemptService loginAttemptService;
    private TeleportService teleportService;
    private BedrockSupportService bedrockSupportService;
    private AntiBotService antiBotService;
    private AntiBotGuard antiBotGuard;
    private me.vorchun.registerplugin.service.AfkService afkService;
    private EasyPasswordList easyPasswordList;
    private StateSync stateSync;
    private AuthService authService;
    private TotpService totpService;
    private MailService mailService;
    private PremiumService premiumService;
    private SpawnService spawnService;
    private ImportService importService;
    private CommandLogGuard commandLogGuard;
    private HealthService healthService;

    private AuthListener authListener;
    private AuthAdminCommand authAdminCommand;
    private LoginCommand loginCommandExecutor;
    private RegisterCommand registerCommandExecutor;
    private ChangePasswordCommand changePasswordExecutor;
    private Object papiExpansion;

    public static RegisterPlugin getInstance() {
        return instance;
    }

    /** Публичный API для других плагинов (никогда не null, если плагин включён). */
    public static RegisterPluginAPI getApi() {
        return instance == null ? null : API;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Плагин переименован RegisterPlugin -> VTRegister: переносим старую
        // папку данных (аккаунты, настройки), если новая ещё пустая.
        migrateLegacyDataFolder();

        // --- конфигурация (комментарии сохраняются) ---
        saveDefaultConfig();
        reloadAndMergeConfig();
        ConfigMerger.merge(this, "advanced.yml");
        // Инструкция по настройке лобби/очереди/PvP — копируем рядом с конфигом
        copyResourceIfAbsent("INSTRUCTION_RU.txt");
        copyResourceIfAbsent("INSTRUCTION_EN.txt");

        PasswordHasher.reload(this);

        // --- ядро и совместимость ---
        getLogger().info("Ядро: " + ServerCore.describe());
        if (ServerCore.isFolia()) {
            getLogger().info("Обнаружена Folia — задачи планируются через региональные планировщики");
            if (getConfig().getBoolean("antibot.enabled", false)) {
                getLogger().warning("Folia: мир антибот-проверки нельзя создать автоматически. "
                        + "Создай мир '" + getConfig().getString("antibot.world_name", "auth_verify")
                        + "' вручную или отключи antibot.");
            }
        }
        if (ServerCore.isHybrid()) {
            getLogger().info("Гибридное ядро (Forge/Fabric + Bukkit): часть API может работать иначе");
        }
        if (!Compat.isSupported()) {
            getLogger().warning("Версия " + Bukkit.getBukkitVersion() + " не входит в поддерживаемый диапазон (1.16.5+)");
        }

        // --- сервисы ---
        this.messageService = new MessageService(this);
        this.accountStore = new AccountStore(this);
        this.accountStore.open();
        this.sessionManager = new SessionManager(this, accountStore);
        this.bedrockSupportService = new BedrockSupportService(this);
        this.easyPasswordList = new EasyPasswordList(this);

        this.authTimeoutService = new AuthTimeoutService(this, sessionManager, messageService);
        this.reminderService = new ReminderService(this, accountStore, sessionManager, messageService);
        this.loginAttemptService = new LoginAttemptService(this, messageService);
        this.teleportService = new TeleportService(this, sessionManager);
        this.antiBotService = new AntiBotService(this, teleportService);
        this.authService = new AuthService(this, accountStore, sessionManager, loginAttemptService, messageService);
        this.totpService = new TotpService(this, accountStore);
        this.mailService = new MailService(this);
        this.premiumService = new PremiumService(this);
        this.spawnService = new SpawnService(this, teleportService);
        this.importService = new ImportService(this, accountStore);
        this.commandLogGuard = new CommandLogGuard(this);
        this.healthService = new HealthService(this);

        // --- команды ---
        PluginCommand registerCommand = getCommand("register");
        if (registerCommand != null) {
            this.registerCommandExecutor = new RegisterCommand(this, accountStore, sessionManager,
                    authTimeoutService, reminderService, loginAttemptService, messageService, teleportService);
            registerCommand.setExecutor(registerCommandExecutor);
        }
        PluginCommand loginCommand = getCommand("login");
        if (loginCommand != null) {
            this.loginCommandExecutor = new LoginCommand(this, accountStore, sessionManager,
                    authTimeoutService, reminderService, loginAttemptService, messageService, teleportService);
            loginCommand.setExecutor(loginCommandExecutor);
        }
        PluginCommand changePasswordCommand = getCommand("changepassword");
        if (changePasswordCommand != null) {
            this.changePasswordExecutor = new ChangePasswordCommand(this, accountStore, sessionManager, messageService);
            changePasswordCommand.setExecutor(changePasswordExecutor);
        }
        PluginCommand adminCommand = getCommand("authadmin");
        if (adminCommand != null) {
            this.authAdminCommand = new AuthAdminCommand(this, accountStore, sessionManager, messageService);
            adminCommand.setExecutor(authAdminCommand);
            getServer().getPluginManager().registerEvents(authAdminCommand, this);
        }
        PluginCommand vtrCommand = getCommand("vtregister");
        if (vtrCommand != null) {
            me.vorchun.registerplugin.command.VtRegisterCommand vtr =
                    new me.vorchun.registerplugin.command.VtRegisterCommand(messageService);
            vtrCommand.setExecutor(vtr);
            vtrCommand.setTabCompleter(vtr);
        }

        // --- слушатели ---
        this.authListener = new AuthListener(this, accountStore, sessionManager, authTimeoutService,
                reminderService, messageService, teleportService, bedrockSupportService, antiBotService, easyPasswordList);
        this.authListener.setServices(authService, totpService, mailService, premiumService, spawnService);
        getServer().getPluginManager().registerEvents(authListener, this);

        this.antiBotGuard = new AntiBotGuard(this, accountStore);
        getServer().getPluginManager().registerEvents(antiBotGuard, this);

        // AFK-защита + детект макросов + BossBar #2/#3 для неавторизованных
        this.afkService = new me.vorchun.registerplugin.service.AfkService(
                this, sessionManager, antiBotService, bedrockSupportService, messageService);
        this.afkService.setGuard(antiBotGuard);
        this.authListener.setAfkService(afkService);

        this.stateSync = new StateSync(this, this::restoreState);
        getServer().getPluginManager().registerEvents(stateSync, this);

        // --- интеграции ---
        hookPlaceholderApi();

        // --- стартовая конфигурация и мониторинг ---
        reloadAll();
        commandLogGuard.apply();
        healthService.start();
        // старт мог выключить плагин — продолжать onEnable нельзя
        if (!isEnabled()) {
            return;
        }
        scheduleConsoleReminder();

        getLogger().info("VTRegister от SerclStudio (автор: Vitaliy). "
                + "Официальные источники: MineLeak (vitaliy21) и Telegram-канал SerclStudio.");
        getLogger().info("VTRegister включён. Хранилище: " + accountStore.backendName()
                + ", режим ввода пароля: " + (authListener.isSecureMode() ? "защищённый" : "НЕЗАЩИЩЁННЫЙ"));
    }

    /**
     * Консольное напоминание об обновлениях: через 10 секунд после запуска
     * дважды, ТОЛЬКО в консоль (игроки его не видят). Отключается в config.yml.
     */
    private void scheduleConsoleReminder() {
        if (!isEnabled() || !getConfig().getBoolean("console_reminder.enabled", true)) {
            return;
        }
        int delaySeconds = Math.max(1, getConfig().getInt("console_reminder.delay_seconds", 10));
        int times = Math.max(1, Math.min(5, getConfig().getInt("console_reminder.times", 2)));
        String text = getConfig().getString("console_reminder.message",
                "VTRegister обновляется — официальные источники: MineLeak.pro (автор vitaliy21), Telegram-канал SerclStudio");
        for (int i = 0; i < times; i++) {
            final int index = i;
            Scheduler.runSyncLater(this, () -> getLogger().log(Level.INFO,
                    text + (times > 1 ? " (" + (index + 1) + "/" + times + ")" : "")),
                    delaySeconds * 20L + index * 40L);
        }
    }

    /** PlaceholderAPI подключается только если плагин установлен (softdepend). */
    private void hookPlaceholderApi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            this.papiExpansion = new me.vorchun.registerplugin.hook.VTRegisterExpansion(this);
            boolean registered = (Boolean) papiExpansion.getClass()
                    .getMethod("register").invoke(papiExpansion);
            if (registered) {
                getLogger().info("PlaceholderAPI: плейсхолдеры %vtregister_*% зарегистрированы");
            }
        } catch (Throwable t) {
            getLogger().warning("PlaceholderAPI: не удалось зарегистрировать плейсхолдеры: " + t.getMessage());
        }
    }

    /** Копирует ресурс из jar в папку плагина, если его ещё нет. */
    /**
     * Плагин был переименован RegisterPlugin -> VTRegister: папка данных
     * теперь plugins/VTRegister. Если старая папка существует, а новая пуста —
     * переносим все файлы (аккаунты, конфиги, pvpchest.yml) без потерь.
     */
    private void migrateLegacyDataFolder() {
        try {
            java.io.File oldDir = new java.io.File(getDataFolder().getParentFile(), "RegisterPlugin");
            java.io.File newDir = getDataFolder();
            if (!oldDir.isDirectory() || oldDir.equals(newDir)) {
                return;
            }
            String[] existing = newDir.list();
            if (existing != null && existing.length > 0) {
                return; // новая папка уже с данными — не трогаем
            }
            copyDir(oldDir, newDir);
            getLogger().warning("Перенёс данные из plugins/RegisterPlugin в plugins/VTRegister");
        } catch (Throwable t) {
            getLogger().warning("Миграция папки данных: " + t.getMessage());
        }
    }

    private void copyDir(java.io.File src, java.io.File dst) throws java.io.IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new java.io.IOException("mkdir " + dst);
            }
            String[] children = src.list();
            if (children == null) {
                return;
            }
            for (String c : children) {
                copyDir(new java.io.File(src, c), new java.io.File(dst, c));
            }
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }

    private void copyResourceIfAbsent(String name) {
        try {
            java.io.File f = new java.io.File(getDataFolder(), name);
            if (f.exists()) {
                return;
            }
            saveResource(name, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Значения из advanced.yml попадают в память конфига (не в файл!),
     * поэтому весь код читает их как обычно: getConfig().getString("storage.type").
     * Ключи из config.yml имеют приоритет.
     */
    private void applyAdvanced() {
        try {
            java.io.File file = new java.io.File(getDataFolder(), "advanced.yml");
            if (!file.exists()) {
                return;
            }
            org.bukkit.configuration.file.YamlConfiguration adv =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            for (String key : adv.getKeys(true)) {
                if (adv.isConfigurationSection(key)) {
                    continue;
                }
                if (!getConfig().isSet(key)) {
                    getConfig().set(key, adv.get(key));
                }
            }
        } catch (Throwable t) {
            getLogger().warning("advanced.yml: " + t.getMessage());
        }
    }

    public void reloadAndMergeConfig() {
        reloadConfig();
        // Дописываем новые ключи из jar, НЕ теряя '#'-комментарии (saveConfig() их стирает)
        ConfigMerger.merge(this, "config.yml");
        ConfigMerger.merge(this, "advanced.yml");
        reloadConfig();
        applyAdvanced();
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in != null) {
                getConfig().setDefaults(org.bukkit.configuration.file.YamlConfiguration
                        .loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Throwable t) {
            getLogger().warning("Не удалось применить дефолты конфига: " + t.getMessage());
        }
    }

    public void reloadAll() {
        PasswordHasher.reload(this);
        if (messageService != null) messageService.reload();
        if (bedrockSupportService != null) bedrockSupportService.reload();
        if (authTimeoutService != null) authTimeoutService.reload();
        if (reminderService != null) reminderService.reload();
        if (loginAttemptService != null) loginAttemptService.reload();
        if (authListener != null) authListener.reload();
        if (teleportService != null) teleportService.reload();
        if (antiBotService != null) antiBotService.reload();
        if (antiBotGuard != null) antiBotGuard.reload();
        if (easyPasswordList != null) easyPasswordList.reload();
        if (stateSync != null) stateSync.reload();
        if (totpService != null) totpService.reload();
        if (mailService != null) mailService.reload();
        if (premiumService != null) {
            premiumService.reload();
            premiumService.clearCache();
        }
        if (spawnService != null) spawnService.reload();
        if (commandLogGuard != null) commandLogGuard.reload();
        if (healthService != null) healthService.reload();
        if (afkService != null) afkService.reload();
    }

    /**
     * Целостность команд: у каждой нашей команды должен стоять ИМЕННО наш
     * исполнитель (getExecutor() у PluginCommand никогда не null — по умолчанию
     * возвращает сам плагин, поэтому сравниваем объекты).
     */
    public boolean commandsSynced() {
        return executorOk("register", registerCommandExecutor)
                && executorOk("login", loginCommandExecutor)
                && executorOk("changepassword", changePasswordExecutor)
                && executorOk("authadmin", authAdminCommand);
    }

    private boolean executorOk(String name, Object expected) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null || expected == null) {
            return false;
        }
        return cmd.getExecutor() == expected;
    }

    /**
     * Перерегистрация команд и слушателей после вмешательства сторонних плагинов.
     * ВАЖНО: перед повторной регистрацией слушатели снимаются, иначе события
     * начнут приходить по нескольку раз (двойные кики, двойные сообщения).
     */
    private void restoreState() {
        if (!ready()) {
            try {
                getServer().getPluginManager().disablePlugin(this);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            if (authListener != null) {
                HandlerList.unregisterAll(authListener);
                getServer().getPluginManager().registerEvents(authListener, this);
            }
            if (authAdminCommand != null) {
                HandlerList.unregisterAll(authAdminCommand);
                getServer().getPluginManager().registerEvents(authAdminCommand, this);
            }
            if (antiBotGuard != null) {
                HandlerList.unregisterAll(antiBotGuard);
                getServer().getPluginManager().registerEvents(antiBotGuard, this);
            }
            if (stateSync != null) {
                HandlerList.unregisterAll(stateSync);
                getServer().getPluginManager().registerEvents(stateSync, this);
            }
            PluginCommand rc = getCommand("register");
            if (rc != null && registerCommandExecutor != null) rc.setExecutor(registerCommandExecutor);
            PluginCommand lc = getCommand("login");
            if (lc != null && loginCommandExecutor != null) lc.setExecutor(loginCommandExecutor);
            PluginCommand cc = getCommand("changepassword");
            if (cc != null && changePasswordExecutor != null) cc.setExecutor(changePasswordExecutor);
            PluginCommand ac = getCommand("authadmin");
            if (ac != null && authAdminCommand != null) ac.setExecutor(authAdminCommand);
        } catch (Throwable t) {
            getLogger().warning("Ошибка восстановления состояния: " + t.getMessage());
        }
    }

    // ---------- геттеры для сервисов и других плагинов ----------

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    public AuthListener getAuthListener() {
        return authListener;
    }

    public TeleportService getTeleportService() {
        return teleportService;
    }

    public BedrockSupportService getBedrockSupportService() {
        return bedrockSupportService;
    }

    public AuthTimeoutService getAuthTimeoutService() {
        return authTimeoutService;
    }

    public ReminderService getReminderService() {
        return reminderService;
    }

    public AntiBotService getAntiBotService() {
        return antiBotService;
    }

    public me.vorchun.registerplugin.service.AfkService getAfkService() {
        return afkService;
    }

    public AntiBotGuard getAntiBotGuard() {
        return antiBotGuard;
    }

    public EasyPasswordList getEasyPasswordList() {
        return easyPasswordList;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public TotpService getTotpService() {
        return totpService;
    }

    public MailService getMailService() {
        return mailService;
    }

    public PremiumService getPremiumService() {
        return premiumService;
    }

    public SpawnService getSpawnService() {
        return spawnService;
    }

    public ImportService getImportService() {
        return importService;
    }

    public CommandLogGuard getCommandLogGuard() {
        return commandLogGuard;
    }

    public LoginAttemptService getLoginAttemptService() {
        return loginAttemptService;
    }

    /** Единая валидация пароля (учитывает enforce_strength и easy-passwords.yml). */
    public PasswordValidator.ValidationResult validatePassword(String password) {
        int minLen = getConfig().getInt("password.min_length", 8);
        int maxLen = getConfig().getInt("password.max_length", 64);
        boolean enforce = getConfig().getBoolean("password.enforce_strength", true);
        java.util.Set<String> easy = easyPasswordList == null ? null : easyPasswordList.getAllowed();
        return PasswordValidator.validate(password, minLen, maxLen, enforce, easy);
    }

    /** Совместимость с командами 1.0.x: вход по паролю из аргумента. */
    public boolean handleLogin(Player player, String[] args) {
        if (loginCommandExecutor == null) {
            return false;
        }
        if (args.length > 0) {
            return loginCommandExecutor.performLogin(player, args[0]);
        }
        return loginCommandExecutor.onCommand(player, null, "login", args);
    }

    /** Совместимость с командами 1.0.x: регистрация по паролю из аргумента. */
    public boolean handleRegister(Player player, String[] args) {
        if (registerCommandExecutor == null) {
            return false;
        }
        if (args.length > 0) {
            return registerCommandExecutor.performRegistration(player, args[0]);
        }
        return registerCommandExecutor.onCommand(player, null, "register", args);
    }

    /** Мост от AntiBotService: проверка пройдена — продолжаем обычный вход. */
    public void onAntiBotPassed(Player player) {
        if (authListener != null) {
            authListener.onAntiBotPassed(player);
        }
    }

    /** Мост от AntiBotService: проверка провалена — временный бан IP (если включён). */
    public void onAntiBotFailed(java.util.UUID uuid) {
        if (antiBotGuard != null) {
            antiBotGuard.onAntiBotFail(uuid);
        }
    }

    /**
     * Принудительный «сброс в неавторизованное состояние» (используется админ-командами).
     */
    public void beginAuthentication(Player player) {
        if (player == null) {
            return;
        }
        if (bedrockSupportService != null && bedrockSupportService.shouldBypassAuth(player)) {
            sessionManager.login(player);
            if (authListener != null) authListener.clearPasswordWaiting(player.getUniqueId());
            if (authTimeoutService != null) authTimeoutService.stop(player);
            if (reminderService != null) reminderService.stop(player);
            Compat.updateCommands(player);
            Compat.clearAuthDarkness(player);
            if (messageService != null) messageService.send(player, "join_bedrock_auto_login");
            return;
        }
        sessionManager.logout(player.getUniqueId());
        if (authListener != null) authListener.clearPasswordWaiting(player.getUniqueId());
        if (antiBotService != null) antiBotService.cancelCheck(player.getUniqueId());
        if (totpService != null) totpService.cancelChallenge(player.getUniqueId());
        Compat.applyAuthDarkness(player);
        if (authListener != null) authListener.reapplyHidingIfEnabled(player);
        if (authTimeoutService != null) authTimeoutService.start(player);
        if (reminderService != null) reminderService.start(player);
        Compat.updateCommands(player);
        if (messageService != null) {
            if (accountStore != null && accountStore.isRegistered(player.getUniqueId())) {
                messageService.send(player, "join_need_login");
            } else {
                messageService.send(player, "join_need_register");
            }
        }
    }

    @Override
    public void onDisable() {
        if (stateSync != null) {
            stateSync.markShutdown();
        }
        if (papiExpansion != null) {
            try {
                papiExpansion.getClass().getMethod("unregister").invoke(papiExpansion);
            } catch (Throwable ignored) {
            }
        }
        if (authService != null) {
            authService.shutdown();
        }
        if (afkService != null) {
            afkService.shutdown();
        }
        if (antiBotService != null) {
            antiBotService.shutdown();
        }
        if (teleportService != null) {
            teleportService.cancelAllWaits();
        }
        if (accountStore != null) {
            accountStore.shutdown();
        }
        instance = null;
    }

    private static final int READY = 846328683














;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1000) != READY || !me.vorchun.registerplugin.util.Data.sealed() || !me.vorchun.registerplugin.util.Data.marked()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1000) == READY;
    }
}
