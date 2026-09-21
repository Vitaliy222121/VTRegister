// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.util.Compat;
import me.vorchun.registerplugin.util.Scheduler;
import me.vorchun.registerplugin.util.ServerCore;

/**
 * Многоэтапная проверка «не бот» перед авторизацией.
 *
 * Игрок попадает в отдельный пустой мир (нулевая нагрузка) и проходит
 * настраиваемую последовательность этапов. Каждый этап можно включить/выключить,
 * для каждого задаётся свой таймаут.
 *
 * Этапы:
 *   FALL    — физика: игрока несколько раз телепортирует вверх, клиент обязан
 *             реально пролететь вниз (проверяем время падения и снижение Y);
 *   CAMERA  — непрерывное вращение камерой N секунд (боты не шлют Look-пакеты);
 *   SLOTS   — переключение слотов + проверка на чит «NoSlotChange»:
 *             сервер принудительно меняет слот и ждёт, что следующее
 *             переключение игрока будет относительно НОВОГО слота;
 *   CAPTCHA — код из чата (опционально код рисуется на карте в руке);
 *   CLICK   — клик по сообщению в чате (команда /rpverify <token>);
 *   BLOCK   — финальный: пройти N блоков, сломать случайный блок и подобрать дроп.
 *
 * Арены изолированы: каждому игроку отдельный участок с шагом arena_spacing,
 * поэтому проверки не мешают друг другу.
 */
public final class AntiBotService {

    public enum Stage {
        FALL, CAMERA, SLOTS, CAPTCHA, CLICK, PUZZLE, MATH, SECRET, BLOCK
    }

    private static final class CheckState {
        int stageIndex = -1;
        String code;
        String clickToken;
        int captchaAttempts;
        int slotViolations;
        long stageDeadline;
        // физика
        int physicsDone;
        long fallStartedAt;
        double fallStartY;
        int tossHeight;
        long landingMinMs;
        Material platformMat;
        boolean awaitingBounce;      // SLIME: ждём отскок вверх
        long bounceDeadline;
        boolean enteredCobweb;       // COBWEB: зафиксировали вход в паутину
        // Трекер изменённых игроком блоков арены (этап BLOCK) —
        // восстанавливаем после проверки, арена уходит следующему игроку целой.
        final java.util.List<long[]> blocksTouched = new java.util.ArrayList<>();
        // камера: накопленные градусы поворота + статистика линейности (Welford)
        double rotAccum;
        int rotSamples;
        double rotMean;
        double rotM2;
        float prevDelta = Float.NaN;
        int sameDeltaStreak;
        int snapStreak;
        // слоты: время принудительной смены — пакеты раньше grace-периода
        // считаются «устаревшими» (игрок ещё не видел смену) и не осуждаются
        long forcedAt;
        // мир/высота арены (verify-мир или запасная арена в небе основного мира)
        World world;
        int baseY = 64;
        // Bedrock-игрок: персональный порядок этапов (SLOTS на Geyser ненадёжен)
        boolean bedrock;
        List<Stage> stages;
        // боссбар прогресса проверки
        org.bukkit.boss.BossBar bar;
        // камера
        long rotationStartedAt;
        long lastRotationAt;
        float lastYaw = Float.NaN;
        float lastPitch = Float.NaN;
        // слоты
        int slotsDone;
        int lastSlot = -1;
        int forcedSlot = -1;
        int forceAttempts;
        boolean awaitingForceFollowUp;
        // блок
        boolean blockBroken;
        org.bukkit.Location tpTarget;
        int tpRetries;
        int arenaX;
        int arenaZ;
        Material targetBlock;
        // PUZZLE: инвентарь-головоломка «убери лишних»
        org.bukkit.inventory.Inventory puzzleInv;
        java.util.Set<Integer> puzzleRemoveSlots;
        int puzzlePlaced;
        int puzzleWrong;
        boolean puzzleAwaitConfirm;
        // PUZZLE-рамки (режим blocks): uuid рамки -> true = лишняя
        java.util.Map<java.util.UUID, Boolean> puzzleFrames;
        int puzzleExtraLeft;
        java.util.Map<String, Integer> puzzleExtraNames;
        long puzzleFrameCheckAt;
        // MATH: правильный ответ + попытки
        String mathAnswer;
        String mathExpr;
        int mathAttempts;
        // SECRET: ожидаемая строка и сколько тестов пройдено
        String secretExpected;
        int secretDone;
        // BLOCK: случайный путь, падения, сундук с инструментом
        java.util.List<int[]> pathPoints;
        int blockFalls;
        Location toolChestLoc;
        long toolChestCheckAt;
        int targetX;
        int targetZ;
        // SLOTS: рывки камеры (проверка живого клиента)
        int joltSent;
        int joltAcks;
        long joltDeadline;
        boolean slotsPendingJolts;
        // эхо нашего jolt-телепорта: клиент прислал пакет с ТЕМИ ЖЕ yaw/pitch —
        // это не ответ игрока, а подтверждение телепорта. Не засчитываем.
        float joltPendingYaw;
        float joltPendingPitch;
        boolean joltEchoPending;
        // FALL: идёт пакетная проверка падения (Netty) — onMove не трогаем
        boolean fallPacketMode;
        // SLOTS strict: слот до форса и счётчик возвратов на него (re-lock чит)
        int preForceSlot = -1;
        int relockHits;
        // фоновый подсчёт move-пакетов за окно тиков
        int movePackets;
        int packetTick;
        // CLICK: время последней отправки кнопки — пересылаем раз в 8 сек,
        // сообщение тонет в чате и игрок теряет куда нажимать
        long clickPromptAt;
        /** Когда показан текущий вопрос (капча/пример/секрет/пазл) — антибот-лимит скорости ответа. */
        long promptShownAt;
        /** Последняя показанная в боссбаре секунда — титл шлём только при её смене. */
        long lastBarSec = -1;
        /** Сколько раз уронился в бездну на этапе FALL. */
        int voidFalls;
        // анти-флай: тики подряд в полёте без разрешения
        int flyTicks;
        // FALL: повторный подброс, если клиент завис в воздухе
        int fallRetries;
        // подготовка к проверке: игрок уже на арене, идёт отсчёт до старта
        boolean preparing;
        boolean preparingInLobby;
        long prepareUntil;
        int lastPrepareSec = -1;
        // PUZZLE: рамка с картиной на стене арены
        java.util.UUID puzzleFrameId;
        // второй (рекламный) боссбар
        org.bukkit.boss.BossBar bar2;
        // восстановление состояния игрока
        GameMode previousGameMode;
        boolean previousAllowFlight;
        boolean previousFlying;
        org.bukkit.inventory.ItemStack[] savedInventory;
        org.bukkit.inventory.ItemStack[] savedArmor;
        org.bukkit.inventory.ItemStack savedOffhand;
        int savedLevel;
        float savedExp;
        boolean inventorySaved;
    }

    private final JavaPlugin plugin;
    private final TeleportService teleportService;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean enabled;
    private volatile String worldName = "auth_verify";
    private volatile int maxConcurrent = 8;
    private volatile int arenaSpacing = 32;
    private volatile int codeLength = 4;
    private volatile int maxAttempts = 5;
    private volatile int cameraSeconds = 3;
    private volatile int slotsRequired = 2;
    private volatile int physicsRepetitions = 3;
    private volatile long minFallMillis = 250L;
    private volatile int blockDistance = 5;
    private volatile boolean slotsAntiCheat = true;
    private volatile boolean mapCaptcha = false;
    private volatile boolean onlyNewPlayers = true;
    private volatile boolean restorePlayerState = true;
    private volatile boolean bossbarEnabled = true;
    private volatile int fallMinHeight = 5;
    private volatile int fallMaxHeight = 9;
    // очередь: 0 = только сообщение, 1 = боссбар на месте, 2 = лобби-платформа
    private volatile int queueMode = 2;
    private volatile boolean queueChatAllowed = false;
    private volatile int queueSpamSeconds = 5;
    private volatile boolean queueHologram = true;
    private volatile boolean queueParkour = true;
    private volatile boolean queueProxySync = true;
    private volatile boolean queueHidePlayers = true;
    private volatile int queueMaxSize = 0;
    private volatile boolean fallbackMainWorld = true;
    private volatile boolean cameraLinearity = true;
    private volatile double cameraTurns = 2.0;
    private volatile int slotGraceMillis = 300;
    private volatile int slotMaxAttempts = 3;
    private volatile long slotsResponseMs = 8000L;
    private volatile int slotRelockMax = 4;
    private volatile double cameraSnapDeg = 60.0;
    private volatile int cameraSnapMax = 4;

    private volatile boolean physicsBlocks = true;
    private volatile boolean bedrockSkipSlots = true;
    // боссбар проверки: auto — цвет по этапу; иначе имя BarColor
    private volatile String bossbarColorName = "auto";
    private volatile String bossbarTitle = "";
    // второй (рекламный) боссбар поверх основного
    private volatile boolean bossbar2Enabled = false;
    private volatile String bossbar2Title = "";
    private volatile String bossbar2Color = "PURPLE";
    // очередь-лобби: полёт для ждущих, кик флаеров, PvP-зона
    private volatile boolean queueFlight = false;
    private volatile boolean queueKickFlyers = true;
    private volatile boolean queueBuildLobby = true;
    private volatile boolean pvpEnabled = false;
    private volatile int pvpX1, pvpZ1, pvpX2, pvpZ2, pvpChestX, pvpChestZ;
    private volatile boolean slotLockEnabled = true;
    private volatile int slotLockKickAfter = 5;
    private volatile boolean adminLobbyModify = true;
    private volatile boolean adminLobbyTp = true;
    private volatile boolean protectFunctional = true;
    private volatile FallPacketCheck fallPackets;
    private volatile boolean puzzleFront = true;
    private volatile String authDarkness = "auth_only";

    private volatile int pvpChestSeconds = 15;
    private volatile List<String> pvpItems = Collections.emptyList();
    // PvP влияет на очередь: убийство +N позиций вперёд, смерть −N назад
    private volatile boolean pvpQueueSteal = true;
    private volatile int pvpGainPositions = 1;
    private volatile int pvpLosePositions = 1;
    private volatile boolean pvpHoloEnabled = true;
    private volatile String pvpHoloText = "";
    private volatile org.bukkit.entity.ArmorStand pvpHologram;
    // аварийный режим: спам в консоль о проблемах N раз каждые M секунд
    private volatile int emergencyTimes = 10;
    private volatile int emergencyIntervalSec = 300;
    // BLOCK: длина случайного пути, лимит падений, сундук с инструментом
    private volatile int blockPathLength = 14;
    private volatile int blockMaxFalls = 5;
    private volatile boolean blockToolChest = true;
    private volatile String blockToolMaterial = "GOLDEN_PICKAXE";
    private volatile String blockToolName = "";
    // SLOTS: рывки камеры — живой клиент отвечает Look-пакетами
    private volatile boolean slotCameraTest = true;
    private volatile int slotCameraJolts = 5;
    private volatile int slotCameraMinAcks = 2;
    // фоновая проверка пакетов: боты шлют аномально много move-пакетов
    private volatile boolean packetCheck = true;
    private volatile int packetMaxPerTick = 60;
    private volatile int packetWindowTicks = 3;
    // наплыв игроков: впускаем burst мест суммарно, остальных кикаем вежливо
    private volatile boolean influxEnabled = true;
    private volatile int influxBurst = 15;
    // батчи: из очереди в проверку пускаем волнами по N с паузой
    private volatile int batchSize = 5;
    private volatile int batchDelaySeconds = 4;
    private volatile long nextBatchAt;
    // отсчёт на экране перед стартом первого этапа
    private volatile int prepareSeconds = 5;
    // lobby_first_seconds > 0: все заходящие сначала N сек в очереди-лобби
    // (паркур/PvP/ожидание), потом на проверку — даже при свободных слотах
    private volatile int lobbyFirstSeconds = 8;
    private final java.util.Set<UUID> queueWarmupDone =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    // PUZZLE: картина на стене через рамку с картой
    private volatile boolean puzzleMap = true;
    private volatile boolean puzzleAutoPass = true;
    private volatile boolean puzzleUnclosable = true;
    private volatile int speedButtonSeconds = 30;
    private volatile int speedButtonLevel = 2;
    private volatile boolean speedButtonEnabled = true;
    private volatile Location speedButtonLoc;
    private volatile org.bukkit.entity.ArmorStand speedHologram;
    private volatile List<java.io.File> puzzleImages = Collections.emptyList();
    // Bedrock-совместимость (Geyser/Floodgate): по умолчанию выключена
    private volatile boolean bedrockEnabled = false;
    // экспертные: период тикера и интервал рывков камеры
    private volatile int tickTicks = 10;
    private volatile int joltIntervalTicks = 8;
    // игрок летает там, где летать нельзя → 100% чит → кик
    private volatile boolean kickFlyers = true;
    // PUZZLE: слово-старт, наборы предметов, лимит ошибок
    private volatile String puzzleConfirmWord = "vse";
    private volatile List<Material> puzzleRemove = Collections.emptyList();
    private volatile List<Material> puzzleKeep = Collections.emptyList();
    private volatile int puzzleMaxWrong = 3;
    /** Ответ быстрее этого лимита (мс) = бот. 0 — выключено. */
    private volatile int minAnswerMs = 400;
    private volatile int minClickMs = 100; // лимит скорости КЛИКА: клик <100мс = бот
    private volatile boolean kitEnabled = true;
    private volatile long kitCooldownMs = 30000L;
    private final List<org.bukkit.inventory.ItemStack> kitItems = new ArrayList<>();
    private final Map<UUID, Long> kitLastOpen = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.inventory.Inventory> kitInvs = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.inventory.Inventory> kitEditors = new ConcurrentHashMap<>();
    private final Map<UUID, Long> speedCooldown = new ConcurrentHashMap<>();
    private final List<org.bukkit.entity.ArmorStand> zoneHolos = new ArrayList<>();
    private final java.util.Set<UUID> bootPassed = ConcurrentHashMap.newKeySet();
    private volatile boolean recheckOnRestart = true;
    private volatile long slimeExtraMs = 8000L;
    private volatile List<String> puzzleSignLines = new ArrayList<>();
    // чат-фильтр лобби
    private volatile boolean chatFilterEnabled = true;
    private volatile long chatCooldownMs = 1500L;
    private volatile int chatMaxWords = 12;
    private volatile boolean chatBlockLinks = true;
    private volatile boolean chatBlockRepeat = true;
    private final Map<UUID, Long> chatLastAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatLastMsg = new ConcurrentHashMap<>();
    /** Сколько раз можно урониться в бездну на FALL до кика. */
    private volatile int fallVoidMax = 3;
    private volatile long lastStructCheckAt;
    // PUZZLE blocks-режим: рамки 3x3, на каждой 1 картинка-животное
    private volatile String puzzleMode = "both";
    private volatile List<String> puzzleTileNames = Collections.emptyList();
    private volatile List<String> puzzleRemoveNames = Collections.emptyList();
    private volatile int puzzleRemoveCount = 3;
    private volatile String puzzleTaskText = "";
    private final Map<String, org.bukkit.map.MapView> tileViews = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.awt.image.BufferedImage> tileImgs = new java.util.concurrent.ConcurrentHashMap<>();
    // MATH: диапазон чисел и куда показывать пример
    private volatile int mathMax = 20;
    private volatile String mathWhere = "chat";
    // SECRET: default | custom | random
    private volatile String secretMode = "default";
    private volatile List<String> secretCustom = Collections.emptyList();
    private volatile int secretCount = 2;
    private volatile List<Stage> stageOrder = Collections.emptyList();
    private final Map<Stage, Integer> stageTimeouts = new ConcurrentHashMap<>();
    // аварийные алерты: problemKey -> [count, lastAtMillis]
    private final Map<String, long[]> alerts = new ConcurrentHashMap<>();
    private volatile Scheduler.Task watchdog;
    private volatile long lastChestRefill;
    private volatile int lastQueueSync = -1;
    private volatile long lastVisibilitySync;

    /** Доп. данные ждущих в очереди: боссбар, тайминги спама, время входа. */
    private static final class QueueEntry {
        long joinMillis;
        long lastSpamAt;
        org.bukkit.boss.BossBar bar;
        Location lobbyReturn;
        // анти-флай в лобби очереди
        int flyTicks;
        // lobby-first: раньше этого времени в проверку не выпускать
        long notBefore;
    }

    private final Map<UUID, CheckState> checks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    private final Queue<UUID> queue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, QueueEntry> queueInfo = new ConcurrentHashMap<>();
    // Очередь ВХОДА на сервер (после прохождения проверки на бота):
    // выпускаем волнами, пока сервер не упрётся в лимит онлайна
    private final Queue<UUID> entryQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, QueueEntry> entryInfo = new ConcurrentHashMap<>();
    private final Map<UUID, CheckState> entryStates = new ConcurrentHashMap<>();
    private volatile boolean entryEnabled;
    private volatile int entryMaxOnline;
    private volatile int entryReserveSlots;
    private volatile int entryReleaseBatch = 3;
    private volatile int entryReleaseSeconds = 3;
    private volatile int entrySpamSeconds = 8;
    private volatile boolean entryBossbar = true;
    private volatile String entryTargetServer = "";
    private volatile long nextReleaseAt;

    private volatile World verifyWorld;
    private volatile World fallbackWorld;
    private volatile boolean worldTried;
    private volatile long worldRetryAt;
    private volatile Scheduler.Task ticker;

    private final NamespacedKey lobbyLootKey;

    public AntiBotService(JavaPlugin plugin, TeleportService teleportService) {
        this.plugin = plugin;
        this.teleportService = teleportService;
        this.lobbyLootKey = new NamespacedKey(plugin, "lobby_loot");
    }

    // ---------- конфигурация ----------

    public void reload() {
        enabled = plugin.getConfig().getBoolean("antibot.enabled", false);
        worldName = nonEmpty(plugin.getConfig().getString("antibot.world_name", "auth_verify"), "auth_verify");
        maxConcurrent = Math.max(1, plugin.getConfig().getInt("antibot.max_concurrent_checks", 8));
        arenaSpacing = Math.max(16, plugin.getConfig().getInt("antibot.arena_spacing", 32));
        codeLength = Math.max(3, Math.min(8, plugin.getConfig().getInt("antibot.code_length", 4)));
        String pfx = plugin.getConfig().getString("antibot.captcha_prefixes", ".#!$");
        captchaPrefixes = (pfx == null || pfx.isEmpty()) ? ".#!$" : pfx;
        maxAttempts = Math.max(1, plugin.getConfig().getInt("antibot.max_attempts", 5));
        cameraSeconds = Math.max(1, Math.min(15, plugin.getConfig().getInt("antibot.camera_seconds", 3)));
        slotsRequired = Math.max(1, Math.min(8, plugin.getConfig().getInt("antibot.slots_required", 2)));
        physicsRepetitions = Math.max(1, Math.min(8, plugin.getConfig().getInt("antibot.physics_repetitions", 3)));
        minFallMillis = Math.max(50L, plugin.getConfig().getLong("antibot.min_fall_millis", 250L));
        blockDistance = Math.max(2, Math.min(10, plugin.getConfig().getInt("antibot.block_distance", 5)));
        slotsAntiCheat = plugin.getConfig().getBoolean("antibot.slots_anti_cheat", true);
        mapCaptcha = plugin.getConfig().getBoolean("antibot.map_captcha", false);
        onlyNewPlayers = plugin.getConfig().getBoolean("antibot.only_new_players", true);
        restorePlayerState = plugin.getConfig().getBoolean("antibot.restore_player_state", true);
        bossbarEnabled = plugin.getConfig().getBoolean("antibot.bossbar", true);
        fallMinHeight = Math.max(3, Math.min(20, plugin.getConfig().getInt("antibot.fall_min_height", 5)));
        fallMaxHeight = Math.max(fallMinHeight, Math.min(30, plugin.getConfig().getInt("antibot.fall_max_height", 9)));

        String qm = plugin.getConfig().getString("antibot.queue_mode", "lobby").toLowerCase(java.util.Locale.ROOT);
        queueMode = "lobby".equals(qm) ? 2 : ("bossbar".equals(qm) ? 1 : 0);
        queueChatAllowed = plugin.getConfig().getBoolean("antibot.queue_chat_allowed", false);
        queueSpamSeconds = Math.max(2, Math.min(30, plugin.getConfig().getInt("antibot.queue_spam_seconds", 5)));
        queueHologram = plugin.getConfig().getBoolean("antibot.queue_hologram", true);
        queueParkour = plugin.getConfig().getBoolean("antibot.queue_parkour", true);
        queueProxySync = plugin.getConfig().getBoolean("antibot.queue_proxy_sync", true);
        queueHidePlayers = plugin.getConfig().getBoolean("antibot.queue_hide_players", false);
        queueMaxSize = Math.max(0, plugin.getConfig().getInt("antibot.queue_max_size", 0));
        fallbackMainWorld = plugin.getConfig().getBoolean("antibot.fallback_main_world", true);
        cameraLinearity = plugin.getConfig().getBoolean("antibot.camera_linearity", true);
        cameraTurns = Math.max(0.5, Math.min(10.0, plugin.getConfig().getDouble("antibot.camera_turns", 2.0)));
        slotGraceMillis = Math.max(100, Math.min(2000, plugin.getConfig().getInt("antibot.slot_grace_millis", 300)));
        slotMaxAttempts = Math.max(1, Math.min(6, plugin.getConfig().getInt("antibot.slot_max_attempts", 3)));
        slotsResponseMs = Math.max(2000L, plugin.getConfig().getLong("antibot.slots_response_ms", 8000L));
        slotRelockMax = Math.max(2, Math.min(10, plugin.getConfig().getInt("antibot.slot_relock_max", 4)));
        cameraSnapDeg = Math.max(30.0, plugin.getConfig().getDouble("antibot.camera_snap_degrees", 60.0));
        cameraSnapMax = Math.max(2, Math.min(12, plugin.getConfig().getInt("antibot.camera_snap_max", 4)));

        physicsBlocks = plugin.getConfig().getBoolean("antibot.physics_blocks", true);
        bedrockSkipSlots = plugin.getConfig().getBoolean("antibot.bedrock_skip_slots", true);

        bossbarColorName = plugin.getConfig().getString("antibot.bossbar_color", "auto");
        bossbarTitle = plugin.getConfig().getString("antibot.bossbar_title", "");
        bossbar2Enabled = plugin.getConfig().getBoolean("antibot.bossbar2_enabled", false);
        bossbar2Title = plugin.getConfig().getString("antibot.bossbar2_title", "");
        bossbar2Color = plugin.getConfig().getString("antibot.bossbar2_color", "PURPLE");

        queueFlight = plugin.getConfig().getBoolean("antibot.queue_flight", false);
        queueKickFlyers = plugin.getConfig().getBoolean("antibot.queue_kick_flyers", true);
        queueBuildLobby = plugin.getConfig().getBoolean("antibot.queue_build_lobby", true);
        pvpEnabled = plugin.getConfig().getBoolean("antibot.queue_pvp.enabled", true);
        pvpX1 = plugin.getConfig().getInt("antibot.queue_pvp.corner1_x", 21);
        pvpZ1 = plugin.getConfig().getInt("antibot.queue_pvp.corner1_z", -484);
        pvpX2 = plugin.getConfig().getInt("antibot.queue_pvp.corner2_x", -21);
        pvpZ2 = plugin.getConfig().getInt("antibot.queue_pvp.corner2_z", -442);
        pvpChestX = plugin.getConfig().getInt("antibot.queue_pvp.chest_x", 0);
        pvpChestZ = plugin.getConfig().getInt("antibot.queue_pvp.chest_z", -463);
        pvpChestSeconds = Math.max(5, plugin.getConfig().getInt("antibot.queue_pvp.chest_seconds", 15));
        pvpItems = plugin.getConfig().getStringList("antibot.queue_pvp.items");
        pvpQueueSteal = plugin.getConfig().getBoolean("antibot.queue_pvp.queue_steal", true);
        pvpGainPositions = Math.max(1, plugin.getConfig().getInt("antibot.queue_pvp.gain_positions", 1));
        pvpLosePositions = Math.max(1, plugin.getConfig().getInt("antibot.queue_pvp.lose_positions", 1));
        pvpHoloEnabled = plugin.getConfig().getBoolean("antibot.queue_pvp.hologram", true);
        slotLockEnabled = plugin.getConfig().getBoolean("antibot.slot_lock.enabled", true);
        slotLockKickAfter = Math.max(0, plugin.getConfig().getInt("antibot.slot_lock.kick_after", 5));
        adminLobbyModify = plugin.getConfig().getBoolean("antibot.queue_pvp.admin_modify", true);
        adminLobbyTp = plugin.getConfig().getBoolean("antibot.queue_pvp.admin_lobby_tp", true);
        protectFunctional = plugin.getConfig().getBoolean("antibot.queue_pvp.protect_functional", true);
        puzzleFront = plugin.getConfig().getBoolean("antibot.puzzle_front", true);
        authDarkness = plugin.getConfig().getString("security.auth_darkness", "auth_only");
        if (plugin.getConfig().getBoolean("antibot.fall_packets", true)) {
            if (fallPackets == null) {
                fallPackets = new FallPacketCheck(plugin, this);
            }
            fallPackets.reload();
        } else {
            if (fallPackets != null) {
                fallPackets.stopAll();
            }
            fallPackets = null;
        }
        pvpHoloText = plugin.getConfig().getString("antibot.queue_pvp.hologram_text",

                "&c⚠ PvP-зона: смерть = -{lose} в очереди, убийство = +{gain}");

        emergencyTimes = Math.max(1, plugin.getConfig().getInt("antibot.emergency.alert_times", 10));
        emergencyIntervalSec = Math.max(30, plugin.getConfig().getInt("antibot.emergency.alert_interval_seconds", 300));

        blockPathLength = Math.max(6, Math.min(40, plugin.getConfig().getInt("antibot.block_path_length", 14)));
        blockMaxFalls = Math.max(1, Math.min(20, plugin.getConfig().getInt("antibot.block_max_falls", 5)));
        blockToolChest = plugin.getConfig().getBoolean("antibot.block_tool_chest", true);
        blockToolMaterial = plugin.getConfig().getString("antibot.block_tool_material", "GOLDEN_PICKAXE");
        blockToolName = plugin.getConfig().getString("antibot.block_tool_name", "");

        slotCameraTest = plugin.getConfig().getBoolean("antibot.slot_camera_test", true);
        slotCameraJolts = Math.max(2, Math.min(10, plugin.getConfig().getInt("antibot.slot_camera_jolts", 5)));
        slotCameraMinAcks = Math.max(1, Math.min(slotCameraJolts,
                plugin.getConfig().getInt("antibot.slot_camera_min_acks", 2)));

        packetCheck = plugin.getConfig().getBoolean("antibot.packet_check", true);
        packetMaxPerTick = Math.max(20, plugin.getConfig().getInt("antibot.packet_max_per_tick", 60));
        packetWindowTicks = Math.max(1, Math.min(10, plugin.getConfig().getInt("antibot.packet_window_ticks", 3)));
        kickFlyers = plugin.getConfig().getBoolean("antibot.kick_flyers", true);

        influxEnabled = plugin.getConfig().getBoolean("antibot.influx.enabled", true);
        influxBurst = Math.max(1, plugin.getConfig().getInt("antibot.influx.burst", 64));
        batchSize = Math.max(1, plugin.getConfig().getInt("antibot.batch_size", 5));
        batchDelaySeconds = Math.max(0, plugin.getConfig().getInt("antibot.batch_delay_seconds", 4));
        prepareSeconds = Math.max(0, Math.min(15, plugin.getConfig().getInt("antibot.prepare_seconds", 5)));
        lobbyFirstSeconds = Math.max(0, Math.min(60, plugin.getConfig().getInt("antibot.lobby_first_seconds", 8)));
        puzzleMap = plugin.getConfig().getBoolean("antibot.puzzle_map", true);
        // GUI-пазл: авто-проход когда всё лишнее убрано (слово-старт —
        // только если auto_pass выключен); unclosable — окно нельзя закрыть.
        puzzleAutoPass = plugin.getConfig().getBoolean("antibot.puzzle_auto_pass", true);
        puzzleUnclosable = plugin.getConfig().getBoolean("antibot.puzzle_unclosable", true);
        speedButtonEnabled = plugin.getConfig().getBoolean("antibot.queue_pvp.speed_button.enabled", true);
        speedButtonSeconds = Math.max(5, Math.min(300,
                plugin.getConfig().getInt("antibot.queue_pvp.speed_button.seconds", 30)));
        speedButtonLevel = Math.max(1, Math.min(5,
                plugin.getConfig().getInt("antibot.queue_pvp.speed_button.level", 2)));
        bedrockEnabled = plugin.getConfig().getBoolean("bedrock.enabled", false);
        tickTicks = Math.max(5, Math.min(40, plugin.getConfig().getInt("antibot.tick_ticks", 10)));
        joltIntervalTicks = Math.max(2, Math.min(40, plugin.getConfig().getInt("antibot.jolt_interval_ticks", 8)));

        // Очередь входа на сервер (после проверки на бота)
        entryEnabled = plugin.getConfig().getBoolean("antibot.entry_queue.enabled", false);
        entryMaxOnline = Math.max(0, plugin.getConfig().getInt("antibot.entry_queue.max_online", 0));
        entryReserveSlots = Math.max(0, plugin.getConfig().getInt("antibot.entry_queue.reserve_slots", 0));
        entryReleaseBatch = Math.max(1, plugin.getConfig().getInt("antibot.entry_queue.release_batch", 3));
        entryReleaseSeconds = Math.max(1, plugin.getConfig().getInt("antibot.entry_queue.release_seconds", 3));
        entrySpamSeconds = Math.max(2, plugin.getConfig().getInt("antibot.entry_queue.spam_seconds", 8));
        entryBossbar = plugin.getConfig().getBoolean("antibot.entry_queue.bossbar", true);
        entryTargetServer = plugin.getConfig().getString("antibot.entry_queue.target_server", "");

        puzzleConfirmWord = plugin.getConfig().getString("antibot.puzzle_confirm_word", "vse");
        puzzleRemove = materials(plugin.getConfig().getStringList("antibot.puzzle_remove"),
                Material.OCELOT_SPAWN_EGG, Material.CAT_SPAWN_EGG);
        puzzleKeep = materials(plugin.getConfig().getStringList("antibot.puzzle_keep"),
                Material.WOLF_SPAWN_EGG, Material.LLAMA_SPAWN_EGG, Material.RABBIT_SPAWN_EGG,
                Material.PARROT_SPAWN_EGG, Material.FOX_SPAWN_EGG);
        puzzleMaxWrong = Math.max(1, Math.min(10, plugin.getConfig().getInt("antibot.puzzle_max_wrong", 3)));
        minAnswerMs = Math.max(0, Math.min(5000,
                plugin.getConfig().getInt("antibot.min_answer_ms", 400)));
        fallVoidMax = Math.max(1, Math.min(20,
                plugin.getConfig().getInt("antibot.fall_void_max", 3)));
        minClickMs = Math.max(0, Math.min(2000,
                plugin.getConfig().getInt("antibot.min_click_ms", 100)));
        kitEnabled = plugin.getConfig().getBoolean("antibot.queue_pvp.kit_enabled", true);
        kitCooldownMs = Math.max(1L, plugin.getConfig().getInt("antibot.queue_pvp.kit_cooldown_seconds", 30)) * 1000L;
        loadKit();
        recheckOnRestart = plugin.getConfig().getBoolean("antibot.recheck_on_restart", true);
        slimeExtraMs = Math.max(0L, plugin.getConfig().getInt("antibot.slime_extra_seconds", 8)) * 1000L;
        puzzleSignLines = plugin.getConfig().getStringList("antibot.puzzle_sign_lines");
        if (puzzleSignLines == null || puzzleSignLines.isEmpty()) {
            puzzleSignLines = java.util.Arrays.asList(
                    "&6&l\u041e\u0411\u0415\u0420\u041d\u0418\u0421\u042c!",
                    "\u043f\u0430\u0437\u043b &c\u0417\u0410 \u0421\u041f\u0418\u041d\u041e\u0419",
                    "\u0440\u0430\u0437\u0432\u0435\u0440\u043d\u0438\u0441\u044c \u043d\u0430 180\u00b0",
                    "&a\u21161 \u043f\u0440\u043e\u0442\u0438\u0432 \u0431\u043e\u0442\u043e\u0432 =)");
        }
        chatFilterEnabled = plugin.getConfig().getBoolean("antibot.queue_chat_filter.enabled", true);
        chatCooldownMs = Math.max(0L, plugin.getConfig().getInt("antibot.queue_chat_filter.cooldown_ms", 1500));
        chatMaxWords = Math.max(1, plugin.getConfig().getInt("antibot.queue_chat_filter.max_words", 12));
        chatBlockLinks = plugin.getConfig().getBoolean("antibot.queue_chat_filter.block_links", true);
        chatBlockRepeat = plugin.getConfig().getBoolean("antibot.queue_chat_filter.block_repeat", true);
        puzzleMode = plugin.getConfig().getString("antibot.puzzle_mode", "both")
                .toLowerCase(java.util.Locale.ROOT).trim();
        puzzleRemoveCount = Math.max(1, Math.min(8, plugin.getConfig().getInt("antibot.puzzle_remove_count", 3)));
        puzzleRemoveNames = plugin.getConfig().getStringList("antibot.puzzle_remove_names");
        if (puzzleRemoveNames == null || puzzleRemoveNames.isEmpty()) {
            puzzleRemoveNames = java.util.Arrays.asList("man_black");
        }
        puzzleTileNames = plugin.getConfig().getStringList("antibot.puzzle_tiles");
        if (puzzleTileNames == null || puzzleTileNames.isEmpty()) {
            puzzleTileNames = java.util.Arrays.asList("cat", "dog", "pig", "cow", "chicken",
                    "sheep", "rabbit", "fox", "panda", "man_black");
        }
        puzzleTaskText = plugin.getConfig().getString("antibot.puzzle_task", "");
        ensurePuzzleTiles();

        mathMax = Math.max(5, Math.min(99, plugin.getConfig().getInt("antibot.math_max", 20)));
        mathWhere = plugin.getConfig().getString("antibot.math_where", "chat");

        secretMode = plugin.getConfig().getString("antibot.secret_mode", "default").toLowerCase(java.util.Locale.ROOT);
        secretCustom = plugin.getConfig().getStringList("antibot.secret_custom");
        secretCount = Math.max(1, Math.min(5, plugin.getConfig().getInt("antibot.secret_count", 2)));

        List<Stage> order = new ArrayList<>();
        if (plugin.getConfig().getBoolean("antibot.stages.fall", true)) order.add(Stage.FALL);
        if (plugin.getConfig().getBoolean("antibot.stages.camera", true)) order.add(Stage.CAMERA);
        if (plugin.getConfig().getBoolean("antibot.stages.slots", true)) order.add(Stage.SLOTS);
        if (plugin.getConfig().getBoolean("antibot.stages.captcha", true)) order.add(Stage.CAPTCHA);
        if (plugin.getConfig().getBoolean("antibot.stages.click", true)) order.add(Stage.CLICK);
        if (plugin.getConfig().getBoolean("antibot.stages.puzzle", true)) order.add(Stage.PUZZLE);
        if (plugin.getConfig().getBoolean("antibot.stages.math", false)) order.add(Stage.MATH);
        if (plugin.getConfig().getBoolean("antibot.stages.secret", false)) order.add(Stage.SECRET);
        if (plugin.getConfig().getBoolean("antibot.stages.block", false)) order.add(Stage.BLOCK);
        stageOrder = Collections.unmodifiableList(order);

        stageTimeouts.clear();
        stageTimeouts.put(Stage.FALL, sec("antibot.timeouts.fall", 20));
        stageTimeouts.put(Stage.CAMERA, sec("antibot.timeouts.camera", 30));
        stageTimeouts.put(Stage.SLOTS, sec("antibot.timeouts.slots", 35));
        stageTimeouts.put(Stage.CAPTCHA, sec("antibot.timeouts.captcha", 60));
        stageTimeouts.put(Stage.CLICK, sec("antibot.timeouts.click", 25));
        stageTimeouts.put(Stage.PUZZLE, sec("antibot.timeouts.puzzle", 60));
        stageTimeouts.put(Stage.MATH, sec("antibot.timeouts.math", 40));
        stageTimeouts.put(Stage.SECRET, sec("antibot.timeouts.secret", 40));
        stageTimeouts.put(Stage.BLOCK, sec("antibot.timeouts.block", 60));

        if (!enabled) {
            for (UUID uuid : new ArrayList<>(checks.keySet())) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    cancelCheck(uuid, true);
                }
            }
            checks.clear();
            returnLocations.clear();
            queue.clear();
            for (QueueEntry qe : queueInfo.values()) {
                removeBar(qe.bar);
            }
            queueInfo.clear();
            removeHologram();
            lobbyBuilt = false;
            stopTicker();
            stopWatchdog();
        } else {
            ensureWatchdog();
            // Мир создаём заранее при включении — иначе первый заходящий
            // ждал бы генерацию мира вместо мгновенной проверки.
            // worldTried сбрасываем: после неудачи каждый reload пробует заново.
            worldTried = false;
            World w = getOrCreateWorld();
            if (w == null) {
                plugin.getLogger().severe("AntiBot включён, но мир проверки недоступен — "
                        + (fallbackMainWorld ? "будет использована запасная арена в небе основного мира!"
                        : "игроки будут пропускаться к обычной авторизации!"));
            } else {
                buildLobby(w);
            }
            // Картинки пазла — генерируем/подхватываем заранее, а не на этапе
            ensurePuzzleImages();
        }
    }

    private int sec(String key, int def) {
        return Math.max(3, Math.min(180, plugin.getConfig().getInt(key, def)));
    }

    private static String nonEmpty(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim();
    }

    /** После рестарта сервера все игроки обязаны пройти проверку заново. */
    public boolean requiresRestartRecheck(UUID uuid) {
        return recheckOnRestart && !bootPassed.contains(uuid);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOnlyNewPlayers() {
        return onlyNewPlayers;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    // ---------- запуск проверки ----------

    /**
     * Начать проверку. Возвращает false, если проверка не требуется
     * (выключена, нет этапов, мир недоступен) — тогда игрока пускаем к обычной авторизации.
     */
    public boolean beginCheck(Player player) {
        if (!enabled || stageOrder.isEmpty()) {
            return false;
        }
        if (!packOk()) {
            plugin.getLogger().warning("[VTRegister] build: check blocked");
            player.kickPlayer(msg("antibot_failed_kick"));
            return true;
        }
        World w = getOrCreateWorld();
        if (w == null) {
            if (!fallbackMainWorld) {
                plugin.getLogger().warning("AntiBot: мир проверки недоступен, игрок " + player.getName() + " пропущен");
                return false;
            }
            // Запасной вариант: арена высоко в небе основного мира —
            // проверка продолжает работать даже без отдельного мира.
            w = player.getWorld();
            fallbackWorld = w;
        }

        UUID uuid = player.getUniqueId();
        if (checks.containsKey(uuid) || queue.contains(uuid)) {
            return true;
        }

        // Наплыв игроков: жёсткий потолок мест (проверки + очередь).
        // Лишних кикаем вежливо — это и есть распределение нагрузки:
        // бот-волна не сможет парализовать вход реальных игроков.
        if (influxEnabled && checks.size() + queue.size() >= influxBurst) {
            player.kickPlayer(msg("antibot_influx_kick"));
            return true;
        }

        returnLocations.putIfAbsent(uuid, player.getLocation().clone());

        // lobby_first: все заходящие сначала греются в лобби (паркур,
        // PvP-арена, боссбар) — проверка стартует через N секунд.
        // queueWarmupDone: игрок уже отсидел своё — второй раз не гоняем.
        if (checks.size() >= maxConcurrent
                || (queueMode == 2 && lobbyFirstSeconds > 0 && !queueWarmupDone.remove(uuid))) {
            enqueue(player, uuid);
            return true;
        }

        CheckState st = new CheckState();
        int slot = nextArenaSlot();
        st.world = w;
        st.baseY = (w == verifyWorld) ? 64 : fallbackBaseY(w);
        st.arenaX = slot * arenaSpacing;
        st.arenaZ = 0;
        st.bedrock = isBedrock(player);
        st.stages = stagesFor(st.bedrock);
        checks.put(uuid, st);
        ensureTicker();

        sendMessage(player, "antibot_started", 0);
        createBar(player, st);

        Scheduler.runAtEntity(plugin, player, () -> {
            if (!player.isOnline() || !checks.containsKey(uuid)) {
                return;
            }
            if (verifyWorld == null) {
                // Мир выгрузили посреди проверки — не валимся, а снимаем проверку
                cancelCheck(uuid, true);
                return;
            }
            prepareArena(st);
            World lw = verifyWorld != null ? verifyWorld : fallbackWorld;
            boolean lobbyWait = queueMode == 2 && lw != null && prepareSeconds > 0;
            if (lobbyWait) {
                ensureLobby(lw);
            }
            if (lobbyWait && lobbyBuilt) {
                // Готовимся В ЛОББИ: игрок ждёт отсчёт на платформе очереди
                // (паркур/PvP), инвентарь пока его собственный. По окончании
                // отсчёта тикер телепортирует его на арену и запустит этапы.
                teleportService.authorizeTeleport(uuid);
                player.teleport(lobbySpawn(lw));
                if (!"always".equals(authDarkness)) {
                    Compat.clearAuthDarkness(player);
                }
                feedForLobby(player);
                st.preparing = true;
                st.preparingInLobby = true;
                st.prepareUntil = System.currentTimeMillis() + prepareSeconds * 1000L;
                st.lastPrepareSec = -1;
                return;
            }
            preparePlayer(player, st);
            // проверка идёт на арене — темнота reg/login здесь не нужна
            if (!"always".equals(authDarkness)) {
                Compat.clearAuthDarkness(player);
            }
            teleportService.authorizeTeleport(uuid);
            player.teleport(arenaSpawn(st));
            if (prepareSeconds > 0) {
                // Отсчёт на экране перед первым этапом — игрок видит,
                // что его сейчас проверят и впустят на сервер
                st.preparing = true;
                st.prepareUntil = System.currentTimeMillis() + prepareSeconds * 1000L;
                st.lastPrepareSec = -1;
            } else {
                advanceStage(player);
            }
        });
        return true;
    }

    /** Свободный слот арены (0..maxConcurrent-1). */
    private int nextArenaSlot() {
        boolean[] used = new boolean[maxConcurrent];
        for (CheckState st : checks.values()) {
            int idx = st.arenaX / arenaSpacing;
            if (idx >= 0 && idx < used.length) {
                used[idx] = true;
            }
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return random.nextInt(Math.max(1, maxConcurrent));
    }

    // ---------- очередь: три режима ----------

    /**
     * Поставить игрока в очередь.
     * Режим 0 (none): просто сообщение. 1 (bossbar): боссбар с позицией.
     * 2 (lobby): общая платформа с паркуром, голограммой и боссбаром.
     */
    private void enqueue(Player player, UUID uuid) {
        // Лимит онлайна в очереди — переполнение кикаем вежливо
        if (queueMaxSize > 0 && queue.size() >= queueMaxSize) {
            player.kickPlayer(msg("antibot_queue_full"));
            return;
        }
        QueueEntry qe = new QueueEntry();
        qe.joinMillis = System.currentTimeMillis();
        qe.notBefore = qe.joinMillis + lobbyFirstSeconds * 1000L;
        qe.lobbyReturn = player.getLocation().clone();
        queueInfo.put(uuid, qe);
        queue.offer(uuid);
        ensureTicker();

        sendMessage(player, "antibot_queue", queuePosition(uuid));

        if (queueMode == 1 && bossbarEnabled) {
            qe.bar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.YELLOW,
                    org.bukkit.boss.BarStyle.SOLID);
            qe.bar.addPlayer(player);
        } else if (queueMode == 2) {
            World w = verifyWorld != null ? verifyWorld : fallbackWorld;
            if (w == null) {
                w = player.getWorld();
            }
            ensureLobby(w);
            final World fw = w;
            if (bossbarEnabled) {
                qe.bar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.YELLOW,
                        org.bukkit.boss.BarStyle.SOLID);
                qe.bar.addPlayer(player);
            }
            teleportService.authorizeTeleport(uuid);
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline() && queue.contains(uuid)) {
                    player.teleport(lobbySpawn(fw));
                    // темнота/blindness только для reg/login — в лобби светло
                    if (!"always".equals(authDarkness)) {
                        Compat.clearAuthDarkness(player);
                    }
                    feedForLobby(player);
                    if (queueFlight) {
                        player.setAllowFlight(true);
                        player.setFlying(true);
                    }
                    syncQueueVisibility();
                }
            });
        }
    }

    private void dequeue(UUID uuid) {
        queue.remove(uuid);
        queueWarmupDone.remove(uuid);
        QueueEntry qe = queueInfo.remove(uuid);
        if (qe != null) {
            removeBar(qe.bar);
        }
        // Полёт в лобби отключаем — он только для зоны ожидания
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && queueFlight) {
            try {
                p.setAllowFlight(false);
                p.setFlying(false);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- лобби-платформа очереди ----------

    private static final int LOBBY_Z = -512;

    /**
     * Контроль целостности jar: пересчитывает хеш байтов всех
     * plugin-классов и сверяет с эталоном в cache.idx. Ловит битые
     * сборки и частично распакованные архивы до входа в проверку.
     */
    public static boolean packOk() {
        try {
            String expect = readXorResource("/cache.idx");
            return expect != null && expect.equals(computeSigLocal());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readXorResource(String name) {
        try {
            java.io.InputStream in = AntiBotService.class.getResourceAsStream(name);
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[128];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            in.close();
            byte[] b = bos.toByteArray();
            char[] c = new char[b.length];
            for (int i = 0; i < b.length; i++) {
                c[i] = (char) ((b[i] & 0xFF) ^ 0x5A);
            }
            return new String(c);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile String localSigCache;

    /** Хеш по всем plugin-классам jar (HealthService исключён — держит константы). */
    private static String computeSigLocal() {
        String v = localSigCache;
        if (v != null) {
            return v;
        }
        try {
            java.net.URL loc = AntiBotService.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.jar.JarFile jf = new java.util.jar.JarFile(new java.io.File(loc.toURI()));
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String nm = en.nextElement().getName();
                if (nm.startsWith("me/vorchun/registerplugin/") && nm.endsWith(".class")
                        && !nm.contains("/libs/")
                        && !nm.equals("me/vorchun/registerplugin/service/HealthService.class")) {
                    names.add(nm);
                }
            }
            java.util.Collections.sort(names);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String nm : names) {
                md.update(nm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.io.InputStream in = jf.getInputStream(jf.getEntry(nm));
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                }
                in.close();
            }
            jf.close();
            byte[] d = md.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            localSigCache = hex.toString();
            return localSigCache;
        } catch (Throwable t) {
            return "err";
        }
    }

    private volatile boolean lobbyBuilt;
    private volatile org.bukkit.entity.ArmorStand hologram;

    private int lobbyBaseY(World w) {
        return (w == verifyWorld) ? 64 : Math.min(240, w.getMaxHeight() - 10);
    }

    private Location lobbySpawn(World w) {
        int y = lobbyBaseY(w);
        return new Location(w, 0.5, y + 1, LOBBY_Z + 0.5, 0f, 0f);
    }

    /** Платформа очереди + 3 полосы паркура + голограмма. Строится один раз. */
    private void ensureLobby(World w) {
        if (lobbyBuilt && hologram != null && hologram.isValid()) {
            return;
        }
        buildLobby(w);
    }

    /**
     * Проверка целостности лобби: если платформа "не та" (мир старый,
     * схематика сломана, кто-то снёс пол) — принудительная перестройка.
     */
    /** Поставить блок только если он отличается — без лишних пакетов. */
    private static void fix(World w, int x, int y, int z, Material m) {
        if (w.getBlockAt(x, y, z).getType() != m) {
            w.getBlockAt(x, y, z).setType(m, false);
        }
    }

    /**
     * Починка декора лобби теми же циклами, что и buildLobby/buildPvpZone/
     * buildParkour, но с guarded-записью: восстанавливаем сломанные
     * фонари, стёкла бортика, башни, пол PvP, стены, колонны, красную
     * линию, полосы паркура и финиш-фонари.
     */
    private void repairLobbyDecor(World w, int y) {
        if (!queueBuildLobby) {
            return;
        }
        final int R = 27;
        try {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    Material mat = ((dx + dz) & 1) == 0
                            ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE;
                    fix(w, dx, y, LOBBY_Z + dz, mat);
                    if (Math.abs(dx) == R || Math.abs(dz) == R) {
                        boolean gate = pvpEnabled && dz == R && Math.abs(dx) <= 2;
                        if (dz == -R && (Math.abs(dx + 20) <= 1 || Math.abs(dx + 10) <= 1
                                || Math.abs(dx) <= 1 || Math.abs(dx - 10) <= 1
                                || Math.abs(dx - 20) <= 1 || Math.abs(dx - 26) <= 1)) {
                            gate = true;
                        }
                        if (!gate) {
                            fix(w, dx, y + 1, LOBBY_Z + dz, Material.GLASS);
                            fix(w, dx, y + 2, LOBBY_Z + dz, Material.GLASS);
                        }
                    }
                }
            }
            for (int lx = -24; lx <= 24; lx += 6) {
                for (int lz = -24; lz <= 24; lz += 6) {
                    fix(w, lx, y, LOBBY_Z + lz, Material.SEA_LANTERN);
                }
            }
            for (int cx : new int[]{-R, R}) {
                for (int cz : new int[]{-R, R}) {
                    for (int h = 1; h <= 3; h++) {
                        fix(w, cx, y + h, LOBBY_Z + cz, Material.STONE_BRICKS);
                    }
                    fix(w, cx, y + 4, LOBBY_Z + cz, Material.LANTERN);
                }
            }
            fix(w, 0, y, LOBBY_Z, Material.GOLD_BLOCK);
            if (queueParkour) {
                int[][] lanes = {
                        {-20, 2, 0}, {-10, 2, 1}, {0, 3, 0},
                        {10, 3, 1}, {20, 4, 1}, {26, 4, 0}};
                Material[] mats = {Material.GRASS_BLOCK, Material.OAK_PLANKS,
                        Material.POLISHED_GRANITE, Material.IRON_BLOCK,
                        Material.DIAMOND_BLOCK};
                for (int lane = 0; lane < lanes.length; lane++) {
                    int x = lanes[lane][0];
                    int gap = lanes[lane][1];
                    int riseEvery = lanes[lane][2] + 2;
                    Material mat = mats[lane % mats.length];
                    int py = y + 1;
                    int pz = LOBBY_Z - 29;
                    for (int i = 0; i < 18; i++) {
                        fix(w, x, py, pz, mat);
                        pz -= (gap + 1);
                        if (i % riseEvery == riseEvery - 1) {
                            py += 1;
                        }
                    }
                    fix(w, x, py + 1, pz + gap + 1, Material.LANTERN);
                }
            }
            if (pvpEnabled) {
                int minX = Math.min(pvpX1, pvpX2), maxX = Math.max(pvpX1, pvpX2);
                int minZ = Math.min(pvpZ1, pvpZ2), maxZ = Math.max(pvpZ1, pvpZ2);
                int midZ = (minZ + maxZ) / 2;
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Material mat = (Math.abs(x) <= 2 || z == midZ)
                                ? Material.GRAVEL : Material.SMOOTH_STONE;
                        fix(w, x, y, z, mat);
                        boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                        if (edge && !(z == minZ && Math.abs(x) <= 2)) {
                            fix(w, x, y + 1, z, Material.COBBLESTONE_WALL);
                            fix(w, x, y + 2, z, Material.COBBLESTONE_WALL);
                            fix(w, x, y + 3, z, Material.NETHER_BRICK_FENCE);
                        }
                    }
                }
                for (int px : new int[]{minX + 7, maxX - 7}) {
                    for (int pz : new int[]{minZ + 7, maxZ - 7}) {
                        for (int h = 1; h <= 3; h++) {
                            fix(w, px, y + h, pz, Material.STONE_BRICKS);
                        }
                        fix(w, px, y + 4, pz, Material.LANTERN);
                    }
                }
                for (int lx = minX; lx <= maxX; lx++) {
                    fix(w, lx, y, minZ, Material.RED_CONCRETE);
                    fix(w, lx, y, minZ - 1, Material.RED_CONCRETE);
                }
                fix(w, pvpChestX, y, pvpChestZ, Material.SMOOTH_STONE);
                fix(w, pvpChestX + 1, y, pvpChestZ, Material.SMOOTH_STONE);
                fix(w, pvpChestX + 1, y + 1, pvpChestZ, Material.CHEST);
                if (speedButtonEnabled) {
                    fix(w, pvpChestX + 4, y, pvpChestZ, Material.POLISHED_BLACKSTONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void verifyLobbyIntegrity(World w) {

        if (w == null || !queueBuildLobby) {
            return;
        }
        int y = lobbyBaseY(w);
        org.bukkit.block.Block center = w.getBlockAt(0, y, LOBBY_Z);
        Material t = center.getType();
        if (t != Material.GOLD_BLOCK) {
            plugin.getLogger().warning("AntiBot: лобби повреждено (центр = " + t
                    + ") — принудительная перестройка");
            lobbyBuilt = false;
            buildLobby(w);
        }
    }

    private void buildLobby(World w) {
        if (w == null) {
            return;
        }
        int y = lobbyBaseY(w);
        // queue_build_lobby: false — админ вставляет свою схематику вручную
        // (инструкция в INSTRUCTION_RU.txt), мы лишь вешаем голограмму.
        if (!queueBuildLobby) {
            spawnHologram(w, y);
            lobbyBuilt = true;
            return;
        }
        // Главная платформа 55x55 — свободно вмещает 60+ ждущих.
        // Шахматный пол, стеклянный бортик, южная сторона с воротами
        // на PvP-арену (арена примыкает — мостов через пустоту нет).
        final int R = 27;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                Material mat = ((dx + dz) & 1) == 0 ? Material.STONE_BRICKS : Material.POLISHED_ANDESITE;
                w.getBlockAt(dx, y, LOBBY_Z + dz).setType(mat, false);
                if (Math.abs(dx) == R || Math.abs(dz) == R) {
                    // Ворота на PvP-арену: в южной стене проход шириной 5
                    boolean gate = pvpEnabled && dz == R && Math.abs(dx) <= 2;
                    // Проёмы к полосам паркура в северной стене (иначе полосы
                    // оказываются замурованными за стеклом — до них нельзя дойти)
                    if (dz == -R && (Math.abs(dx + 20) <= 1 || Math.abs(dx + 10) <= 1
                            || Math.abs(dx) <= 1 || Math.abs(dx - 10) <= 1
                            || Math.abs(dx - 20) <= 1 || Math.abs(dx - 26) <= 1)) {
                        gate = true;
                    }
                    if (!gate) {
                        w.getBlockAt(dx, y + 1, LOBBY_Z + dz).setType(Material.GLASS, false);
                        w.getBlockAt(dx, y + 2, LOBBY_Z + dz).setType(Material.GLASS, false);
                    }
                }
            }
        }
        // Свет: морские фонари, вмурованные в пол сеткой — ночь и тёмный
        // запасной мир не мешают ожиданию.
        for (int lx = -24; lx <= 24; lx += 6) {
            for (int lz = -24; lz <= 24; lz += 6) {
                w.getBlockAt(lx, y, LOBBY_Z + lz).setType(Material.SEA_LANTERN, false);
            }
        }
        // Угловые башни: кирпичный столб 3 блока + фонарь — ориентиры.
        for (int cx : new int[]{-R, R}) {
            for (int cz : new int[]{-R, R}) {
                for (int h = 1; h <= 3; h++) {
                    w.getBlockAt(cx, y + h, LOBBY_Z + cz).setType(Material.STONE_BRICKS, false);
                }
                w.getBlockAt(cx, y + 4, LOBBY_Z + cz).setType(Material.LANTERN, false);
            }
        }
        // Центр: золотая метка спавна + табличка с подсказкой.
        w.getBlockAt(0, y, LOBBY_Z).setType(Material.GOLD_BLOCK, false);
        try {
            Block signBlock = w.getBlockAt(0, y + 1, LOBBY_Z + 3);
            signBlock.setType(Material.OAK_SIGN, false);
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) signBlock.getState();
            sign.setLine(0, "Очередь");
            sign.setLine(1, "на проверку");
            sign.setLine(2, "жди на боссбаре");
            sign.update(true, false);
        } catch (Throwable ignored) {
        }
        if (queueParkour) {
            buildParkour(w, y);
        }
        if (pvpEnabled) {
            buildPvpZone(w, y);
        }
        spawnHologram(w, y);
        lobbyBuilt = true;
    }

    /**
     * PvP-зона: площадка под координатами из конфига + сундук с лутом.
     * Границы задаёт сервер (corner1/corner2) — зона может быть любой формы,
     * мы лишь кладём под ней пол и ставим сундук.
     */
    private void buildPvpZone(World w, int y) {
        int minX = Math.min(pvpX1, pvpX2), maxX = Math.max(pvpX1, pvpX2);
        int minZ = Math.min(pvpZ1, pvpZ2), maxZ = Math.max(pvpZ1, pvpZ2);
        int midZ = (minZ + maxZ) / 2;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Пол арены: гладкий камень, центр — гравийная дорожка
                Material mat = (Math.abs(x) <= 2 || z == midZ)
                        ? Material.GRAVEL : Material.SMOOTH_STONE;
                w.getBlockAt(x, y, z).setType(mat, false);
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                if (edge) {
                    // Ворота только в северной стене — проход из лобби
                    // (арена примыкает: её minZ = край лобби + 1)
                    boolean gate = z == minZ && Math.abs(x) <= 2;
                    if (!gate) {
                        w.getBlockAt(x, y + 1, z).setType(Material.COBBLESTONE_WALL, false);
                        w.getBlockAt(x, y + 2, z).setType(Material.COBBLESTONE_WALL, false);
                        w.getBlockAt(x, y + 3, z).setType(Material.NETHER_BRICK_FENCE, false);
                    }
                }
            }
        }
        // Колонны-укрытия внутри арены + фонари на них
        for (int px : new int[]{minX + 7, maxX - 7}) {
            for (int pz : new int[]{minZ + 7, maxZ - 7}) {
                for (int h = 1; h <= 3; h++) {
                    w.getBlockAt(px, y + h, pz).setType(Material.STONE_BRICKS, false);
                }
                w.getBlockAt(px, y + 4, pz).setType(Material.LANTERN, false);
            }
        }
        // ДВОЙНОЙ сундук с PvP-набором. Реальный инвентарь всегда пуст —
        // клик по нему открывает виртуальный набор на игрока (openKitChest),
        // так что подсмотреть/вычерпать чужой лут невозможно.
        w.getBlockAt(pvpChestX, y, pvpChestZ).setType(Material.SMOOTH_STONE, false);
        w.getBlockAt(pvpChestX + 1, y, pvpChestZ).setType(Material.SMOOTH_STONE, false);
        w.getBlockAt(pvpChestX, y + 1, pvpChestZ).setType(Material.CHEST, false);
        w.getBlockAt(pvpChestX + 1, y + 1, pvpChestZ).setType(Material.CHEST, false);
        // Красная линия на границе PvP: вся северная стена (ворота) — красный
        // бетон на уровне пола; переступил — ты в PvP-зоне.
        for (int lx = minX; lx <= maxX; lx++) {
            w.getBlockAt(lx, y, minZ).setType(Material.RED_CONCRETE, false);
            w.getBlockAt(lx, y, minZ - 1).setType(Material.RED_CONCRETE, false);
        }
        // маленькие голограммы-указатели у линии (с двух сторон ворот)
        try {
            removeHoloList();
            zoneHolos.add(spawnHolo(w, new Location(w, -3.0, y + 1.8, minZ + 1.5),
                    "&c\u2694 Впереди PvP-зона"));
            zoneHolos.add(spawnHolo(w, new Location(w, 3.0, y + 1.8, minZ - 0.5),
                    "&a\u2714 Мирная зона"));
        } catch (Throwable ignored) {
        }
        // Кнопка скорости в 4 блоках от сундука: постамент + кнопка + голограмма.
        // Ждущий нажимает и получает Speed уровня N на speedButtonSeconds сек.
        if (speedButtonEnabled) {
            int bx = pvpChestX + 4;
            int bz = pvpChestZ;
            w.getBlockAt(bx, y, bz).setType(Material.POLISHED_BLACKSTONE, false);
            org.bukkit.block.Block btn = w.getBlockAt(bx, y + 1, bz);
            try {
                btn.setType(Material.STONE_BUTTON, false);
                org.bukkit.block.data.type.Switch sw =
                        (org.bukkit.block.data.type.Switch) btn.getBlockData();
                sw.setFace(org.bukkit.block.data.type.Switch.Face.FLOOR);
                btn.setBlockData(sw, false);
            } catch (Throwable t) {
                btn.setType(Material.STONE_BUTTON, false);
            }
            speedButtonLoc = btn.getLocation();
            // голограмма-подсказка над кнопкой
            if (speedHologram != null) {
                try { speedHologram.remove(); } catch (Throwable ignored) {}
                speedHologram = null;
            }
            try {
                Location hl = new Location(w, bx + 0.5, y + 2.6, bz + 0.5);
                org.bukkit.entity.ArmorStand as = w.spawn(hl, org.bukkit.entity.ArmorStand.class);
                as.setVisible(false);
                as.setGravity(false);
                as.setCustomNameVisible(true);
                as.setMarker(true);
                as.setInvulnerable(true);
                as.setCustomName(toBarText("&e⚡ Нажми кнопку = Скорость " + speedButtonLevel
                        + " на " + speedButtonSeconds + " сек"));
                speedHologram = as;
            } catch (Throwable ignored) {
            }
        }
        // голограмма-предупреждение у линии PvP
        if (pvpHoloEnabled) {
            if (pvpHologram != null) {
                try {
                    pvpHologram.remove();
                } catch (Throwable ignored) {
                }
                pvpHologram = null;
            }
            try {
                Location loc = new Location(w, (minX + maxX) / 2.0 + 0.5, y + 3.0, minZ + 0.5);
                org.bukkit.entity.ArmorStand as = w.spawn(loc, org.bukkit.entity.ArmorStand.class);
                as.setVisible(false);
                as.setGravity(false);
                as.setCustomNameVisible(true);
                as.setMarker(true);
                as.setInvulnerable(true);
                as.setCustomName(toBarText(pvpHoloText
                        .replace("{lose}", String.valueOf(pvpLosePositions))
                        .replace("{gain}", String.valueOf(pvpGainPositions))));
                pvpHologram = as;
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Три полосы паркура к северу от платформы: лёгкий (2 блока), средний (3),
     * сложный (4 с подъёмом). Материалы разные, чтобы линии отличались.
     */
    private void buildParkour(World w, int y) {
        int[][] lanes = {
                {-20, 2, 0},  // лёгкий: шаг 2
                {-10, 2, 1},  // лёгкий-средний: шаг 2, подъём
                {0, 3, 0},    // средний
                {10, 3, 1},   // средний-сложный
                {20, 4, 1},   // сложный: шаг 4, подъём чаще
                {26, 4, 0},   // самый сложный: шаг 4, подъём каждые 2 блока — высоко
        };
        Material[] mats = {Material.GRASS_BLOCK, Material.OAK_PLANKS, Material.POLISHED_GRANITE,
                Material.IRON_BLOCK, Material.DIAMOND_BLOCK};
        for (int lane = 0; lane < lanes.length; lane++) {
            int x = lanes[lane][0];
            int gap = lanes[lane][1];
            int riseEvery = lanes[lane][2] + 2;
            Material mat = mats[lane % mats.length];
            int py = y + 1;
            int pz = LOBBY_Z - 29;   // за северным бортиком платформы (R=27)
            for (int i = 0; i < 18; i++) {
                w.getBlockAt(x, py, pz).setType(mat, false);
                pz -= (gap + 1);
                if (i % riseEvery == riseEvery - 1) {
                    py += 1;
                }
            }
            // финиш-маяк: факел на вершине каждой полосы
            w.getBlockAt(x, py + 1, pz + gap + 1).setType(Material.LANTERN, false);
        }
    }

    private void spawnHologram(World w, int y) {
        if (!queueHologram) {
            return;
        }
        try {
            // убираем ТОЛЬКО голограмму очереди — PvP-голограмма своя
            if (hologram != null) {
                try {
                    hologram.remove();
                } catch (Throwable ignored) {
                }
                hologram = null;
            }
            Location loc = new Location(w, 0.5, y + 3.0, LOBBY_Z + 0.5);
            // Убираем дублёров: старые стойки-голограммы рядом (мигрированные/
            // оставшиеся от прошлой сборки лобби) — иначе текст наезжает друг на друга
            for (org.bukkit.entity.Entity near : w.getNearbyEntities(loc, 2.0, 3.0, 2.0)) {
                if (near instanceof org.bukkit.entity.ArmorStand
                        && ((org.bukkit.entity.ArmorStand) near).isMarker()) {
                    try {
                        near.remove();
                    } catch (Throwable ignored) {
                    }
                }
            }
            org.bukkit.entity.ArmorStand as = w.spawn(loc, org.bukkit.entity.ArmorStand.class);
            as.setVisible(false);
            as.setGravity(false);
            as.setCustomNameVisible(true);
            as.setMarker(true);
            as.setInvulnerable(true);
            as.setCustomName(toBarText("&eОчередь: &f0"));
            hologram = as;
        } catch (Throwable ignored) {
        }
    }

    private void removeHologram() {
        if (hologram != null) {
            try {
                hologram.remove();
            } catch (Throwable ignored) {
            }
            hologram = null;
        }
        if (pvpHologram != null) {
            try {
                pvpHologram.remove();
            } catch (Throwable ignored) {
            }
            pvpHologram = null;
        }
    }

    private void updateHologram() {
        if (hologram == null || !hologram.isValid()) {
            return;
        }
        try {
            hologram.setCustomName(toBarText("&eВ очереди: &f" + queue.size() + " &7чел."));
        } catch (Throwable ignored) {
        }
    }

    /** Высота запасной арены в обычном мире — высокое небо, никого не заденет. */
    private int fallbackBaseY(World w) {
        return Math.min(245, w.getMaxHeight() - 10);
    }

    // ---------- аварийный режим: вотчдог ----------

    /**
     * Вотчдог: раз в 60 сек собирает список проблем и спамит их в консоль —
     * максимум emergency.alert_times раз с интервалом emergency.alert_interval_seconds.
     * Плагин продолжает работать в деградированном режиме, но админ видит беду.
     */
    private void ensureWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = Scheduler.runSyncTimer(plugin, this::watchdogTick, 1200L, 1200L);
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        alerts.clear();
    }

    private void watchdogTick() {
        if (!enabled) {
            return;
        }
        List<String> problems = new ArrayList<>();
        if (verifyWorld == null && worldTried) {
            problems.add("мир проверки '" + worldName + "' НЕ создан — "
                    + (fallbackMainWorld ? "работает запасная арена в небе" : "игроки проходят БЕЗ проверки!"));
        }
        if (stageOrder.isEmpty()) {
            problems.add("все этапы антибота выключены в конфиге — проверка ничего не делает!");
        }
        if (queueMode == 2 && queueBuildLobby && verifyWorld != null && !lobbyBuilt) {
            problems.add("лобби очереди не построено — ждущие стоят на месте");
        }
        long now = System.currentTimeMillis();
        for (String p : problems) {
            long[] a = alerts.computeIfAbsent(p, k -> new long[]{0L, 0L});
            if (a[0] >= emergencyTimes || now - a[1] < emergencyIntervalSec * 1000L) {
                continue;
            }
            a[0]++;
            a[1] = now;
            plugin.getLogger().severe("==================================================");
            plugin.getLogger().severe("AntiBot АВАРИЯ (" + a[0] + "/" + emergencyTimes + "): " + p);
            plugin.getLogger().severe("AntiBot: повторю предупреждение через "
                    + emergencyIntervalSec + " сек, если не починишь.");
            plugin.getLogger().severe("==================================================");
        }
    }

    /** Список материалов из конфига с запасными значениями. */
    private static List<Material> materials(List<String> names, Material... fallback) {
        List<Material> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                Material m = n == null ? null : Material.matchMaterial(n.trim());
                if (m != null) {
                    out.add(m);
                }
            }
        }
        if (out.isEmpty()) {
            Collections.addAll(out, fallback);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Подготовить арену: платформа, коридор и случайный блок для этапа BLOCK.
     * Строим только серверной стороной — чанки подгружаются автоматически.
     */
    private void prepareArena(CheckState st) {
        if (st.world == null) {
            return;
        }
        buildPlatform(st, randomPlatformBlock());
    }

    /**
     * Путь для этапа BLOCK: СЛУЧАЙНЫЙ зигзаг (у каждого игрока свой), длиной
     * block_path_length, шириной в 1 блок — бот, идущий напрямик, сорвётся.
     * В конце пути — целевой блок, у старта — сундук с инструментом.
     */
    private void buildBlockPath(CheckState st) {
        World w = st.world;
        if (w == null) {
            return;
        }
        int y = st.baseY;
        st.pathPoints = new ArrayList<>();
        int x = st.arenaX;
        int z = st.arenaZ - 4;
        for (int i = 0; i < blockPathLength; i++) {
            // каждый шаг: -Z и случайное смещение по X (-1/0/+1, в пределах ±6)
            if (i > 0) {
                x += random.nextInt(3) - 1;
                x = Math.max(st.arenaX - 6, Math.min(st.arenaX + 6, x));
                z -= 1;
            }
            w.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
            st.pathPoints.add(new int[]{x, z});
        }
        // целевой блок в конце пути
        st.targetBlock = randomTargetBlock();
        int[] end = st.pathPoints.get(st.pathPoints.size() - 1);
        w.getBlockAt(end[0], y + 1, end[1] - 1).setType(st.targetBlock, false);
        w.getBlockAt(end[0], y, end[1] - 1).setType(Material.STONE_BRICKS, false);
        st.pathPoints.add(new int[]{end[0], end[1] - 1});
        st.targetX = end[0];
        st.targetZ = end[1] - 1;
        // сундук со спец-инструментом — открывается только на этапе BLOCK
        if (blockToolChest) {
            int cx = st.arenaX + 2;
            int cz = st.arenaZ - 4;
            w.getBlockAt(cx, y, cz).setType(Material.STONE_BRICKS, false);
            org.bukkit.block.Block chestBlock = w.getBlockAt(cx, y + 1, cz);
            chestBlock.setType(Material.CHEST, false);
            st.toolChestLoc = chestBlock.getLocation();
            try {
                org.bukkit.block.Chest chest = (org.bukkit.block.Chest) chestBlock.getState();
                chest.getInventory().setItem(13, makeBlockTool());
                chest.update(true, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Спец-инструмент для этапа BLOCK — целевой блок без него не сломать. */
    private org.bukkit.inventory.ItemStack makeBlockTool() {
        Material mat = Material.matchMaterial(blockToolMaterial);
        if (mat == null) {
            mat = Material.GOLDEN_PICKAXE;
        }
        org.bukkit.inventory.ItemStack tool = new org.bukkit.inventory.ItemStack(mat);
        try {
            org.bukkit.inventory.meta.ItemMeta meta = tool.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(toBarText(blockToolName == null || blockToolName.isEmpty()
                        ? "&eКлюч арены" : blockToolName));
                meta.setUnbreakable(true);
                tool.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
        }
        return tool;
    }

    /** Платформа 7x7 из указанного материала — на каждое падение блоки разные. */
    private void buildPlatform(CheckState st, Material mat) {
        World w = st.world;
        if (w == null) {
            return;
        }
        st.platformMat = mat;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                w.getBlockAt(st.arenaX + dx, st.baseY, st.arenaZ + dz).setType(mat, false);
            }
        }
        // Под COBWEB — твёрдая основа, иначе игрок провалится сквозь паутину
        if (mat == Material.COBWEB) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY - 1, st.arenaZ + dz)
                            .setType(Material.BEDROCK, false);
                }
            }
        }
    }

    /**
     * Пул блоков платформы. При physics_blocks=true добавляются блоки с особой
     * физикой: SLIME — клиент обязан реально отскочить вверх; COBWEB — должен
     * медленно погружаться (мгновенное «приземление» = бот). Пакетные читы
     * такую физику не воспроизводят.
     */
    private Material randomPlatformBlock() {
        Material[] normal = {
                Material.STONE, Material.GRASS_BLOCK, Material.OAK_PLANKS,
                Material.SANDSTONE, Material.SNOW_BLOCK, Material.DIRT,
                Material.NETHERRACK, Material.END_STONE
        };
        if (!physicsBlocks) {
            return normal[random.nextInt(normal.length)];
        }
        Material[] pool = {
                Material.STONE, Material.GRASS_BLOCK, Material.OAK_PLANKS,
                Material.SANDSTONE, Material.SNOW_BLOCK, Material.DIRT,
                Material.COBWEB, Material.HONEY_BLOCK
        };
        return pool[random.nextInt(pool.length)];
    }

    private static Material randomTargetBlock() {
        Material[] pool = {
                Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE,
                Material.OAK_LOG, Material.BIRCH_LOG, Material.STONE
        };
        return pool[(int) (Math.random() * pool.length)];
    }

    private Location arenaSpawn(CheckState st) {
        return new Location(st.world, st.arenaX + 0.5, st.baseY + 1, st.arenaZ + 0.5, 180f, 0f);
    }

    /** Запомнить состояние игрока и перевести в режим, пригодный для проверок. */
    private void preparePlayer(Player player, CheckState st) {
        st.previousGameMode = player.getGameMode();
        st.previousAllowFlight = player.getAllowFlight();
        st.previousFlying = player.isFlying();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0f);
        player.setVelocity(player.getVelocity().zero());
        // В мире проверки игрок должен быть «пустым»: ни предметов, ни опыта.
        // Всё сохраняем и вернём после проверки (или при выходе/кике).
        if (plugin.getConfig().getBoolean("antibot.empty_inventory", true) && !st.inventorySaved) {
            stripLobbyLoot(player);
            st.savedInventory = player.getInventory().getContents().clone();
            st.savedArmor = player.getInventory().getArmorContents().clone();
            st.savedOffhand = player.getInventory().getItemInOffHand().clone();
            st.savedLevel = player.getLevel();
            st.savedExp = player.getExp();
            st.inventorySaved = true;
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setItemInOffHand(null);
            player.setLevel(0);
            player.setExp(0f);
            player.updateInventory();
        }
    }

    /** Вернуть предметы и опыт, сохранённые при входе в мир проверки. */
    private void restoreInventory(Player player, CheckState st) {
        if (st == null || !st.inventorySaved || player == null || !player.isOnline()) {
            return;
        }
        try {
            if (st.savedInventory != null) {
                player.getInventory().setContents(st.savedInventory);
            }
            if (st.savedArmor != null) {
                player.getInventory().setArmorContents(st.savedArmor);
            }
            player.getInventory().setItemInOffHand(st.savedOffhand);
            player.setLevel(st.savedLevel);
            player.setExp(st.savedExp);
            player.updateInventory();
        } catch (Throwable ignored) {
        }
        st.inventorySaved = false;
    }

    /** Вернуть игроку его режим/полёт/предметы после проверки. */
    private void restorePlayer(Player player, CheckState st) {
        restoreInventory(player, st);
        if (!restorePlayerState || st == null) {
            return;
        }
        try {
            if (st.previousGameMode != null) {
                player.setGameMode(st.previousGameMode);
            }
            player.setAllowFlight(st.previousAllowFlight);
            if (st.previousFlying && st.previousAllowFlight) {
                player.setFlying(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Игрок умер/возродился во время проверки (например, ядро принудительно убило).
     * Возвращаем его на арену и продолжаем проверку — он не должен попасть
     * в обычный мир, пока не пройдёт этапы.
     */
    public boolean returnToCheckOnRespawn(Player player) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null) {
            // Игрок в очереди-лобби (умер в PvP-зоне) — обратно на платформу.
            // Покрывает и очередь проверки, и очередь входа на сервер.
            if ((queue.contains(uuid) || entryQueue.contains(uuid)) && queueMode == 2) {
                Scheduler.runAtEntityLater(plugin, player, () -> {
                    if (player.isOnline() && (queue.contains(uuid) || entryQueue.contains(uuid))) {
                        teleportService.authorizeTeleport(uuid);
                        player.teleport(lobbySpawn(player.getWorld()));
                        if (queueFlight) {
                            player.setAllowFlight(true);
                            player.setFlying(true);
                        }
                    }
                }, 1L);
                return true;
            }
            return false;
        }
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline() && checks.containsKey(uuid)) {
                preparePlayer(player, st);
                teleportService.authorizeTeleport(uuid);
                player.teleport(arenaSpawn(st));
                sendMessage(player, "antibot_respawn", 0);
            }
        }, 1L);
        return true;
    }

    // ---------- этапы ----------

    private void advanceStage(Player player) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null) {
            return;
        }
        st.stageIndex++;
        removePuzzlePicture(st);   // рамка прошлого этапа — убрать
        st.puzzleFrameId = null;
        st.captchaAttempts = 0;
        st.slotsDone = 0;
        st.preForceSlot = -1;
        st.relockHits = 0;
        st.joltEchoPending = false;
        st.lastSlot = -1;
        st.forcedSlot = -1;
        st.forceAttempts = 0;
        st.awaitingForceFollowUp = false;
        st.blockBroken = false;
        st.physicsDone = 0;
        st.awaitingBounce = false;
        st.enteredCobweb = false;
        st.platformMat = null;
        st.lastYaw = Float.NaN;
        st.lastPitch = Float.NaN;
        st.rotationStartedAt = 0L;
        st.lastRotationAt = 0L;
        st.rotAccum = 0;
        st.rotSamples = 0;
        st.rotMean = 0;
        st.rotM2 = 0;
        st.prevDelta = Float.NaN;
        st.sameDeltaStreak = 0;
        st.puzzleInv = null;
        st.puzzleRemoveSlots = null;
        st.puzzlePlaced = 0;
        st.puzzleWrong = 0;
        st.puzzleAwaitConfirm = false;
        removePuzzleFrames(st);
        st.puzzleFrames = null;
        st.puzzleExtraLeft = 0;
        st.puzzleExtraNames = null;
        st.puzzleFrameCheckAt = 0;
        st.promptShownAt = 0;
        st.voidFalls = 0;
        st.lastBarSec = -1;
        st.mathAnswer = null;
        st.mathAttempts = 0;
        st.secretExpected = null;
        st.secretDone = 0;
        st.pathPoints = null;
        st.blockFalls = 0;
        st.toolChestCheckAt = 0;
        st.toolChestLoc = null;
        st.joltSent = 0;
        st.joltAcks = 0;
        st.joltDeadline = 0L;
        st.slotsPendingJolts = false;
        st.movePackets = 0;

        List<Stage> order = st.stages != null ? st.stages : stageOrder;
        if (st.stageIndex >= order.size()) {
            finishCheck(player);
            return;
        }
        Stage stage = order.get(st.stageIndex);
        st.stageDeadline = System.currentTimeMillis() + stageTimeouts.getOrDefault(stage, 30) * 1000L;
        updateBar(player, st);

        // Перед НЕ-физическим этапом перестраиваем платформу в твёрдую
        // и ставим игрока в её центр: после падения на паутину/слизь
        // нельзя оставлять его внутри блоков — экран в паутине, капчу
        // и кнопку CLICK не разглядеть, пазл неудобен.
        if (stage != Stage.FALL && st.world != null && player.isOnline()) {
            buildPlatform(st, Material.BEDROCK);
            final Location stand = (stage == Stage.BLOCK)
                    ? new Location(st.world, st.arenaX + 0.5, st.baseY + 1,
                            st.arenaZ - 3.5, 180f, 0f)
                    : arenaSpawn(st);
            st.tpTarget = stand.clone();
            st.tpRetries = 3;
            teleportService.authorizeTeleport(uuid);
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline() && checks.containsKey(uuid)) {
                    player.teleport(stand);
                    player.setFallDistance(0f);
                }
            });
        }

        switch (stage) {
            case FALL:
                sendMessage(player, "antibot_stage_fall", physicsRepetitions);
                // Пакетная проверка падения (Netty, Limbo-стиль): телепорт
                // в воздух + AcceptTeleportation + замер dy по формуле
                // v=(v-0.08)*0.98. Не сработала инъекция — legacy-платформа.
                if (fallPackets != null && st.world != null
                        && fallPackets.start(player, st.world, st.arenaX + 0.5,
                                st.baseY + 1, st.arenaZ + 0.5, st.bedrock)) {
                    st.fallPacketMode = true;
                    break;
                }
                // Сначала игрок СТОИТ на платформе (стенд-телепорт дошёл),
                // подброс — с задержкой: иначе телепорт в воздух мог обогнать
                // телепорт на платформу, и игрок улетал в бездну.
                Scheduler.runAtEntityLater(plugin, player, () -> {
                    if (player.isOnline() && checks.containsKey(uuid)
                            && getCurrentStage(uuid) == Stage.FALL) {
                        startFallRep(player, st);
                    }
                }, 12L);
                break;
            case CAMERA:
                sendMessage(player, "antibot_stage_camera", cameraSeconds);
                break;
            case SLOTS:
                sendMessage(player, "antibot_stage_slots", slotsRequired);
                if (slotCameraTest) {
                    scheduleCameraJolts(player, st);
                }
                break;
            case CAPTCHA:
                st.code = generateCode();
                if (mapCaptcha) {
                    giveCaptchaMap(player, st);
                }
                sendCaptcha(player, st);
                break;
            case CLICK:
                st.clickToken = generateToken();
                sendClickPrompt(player, st);
                break;
            case PUZZLE:
                startPuzzle(player, st);
                break;
            case MATH:
                startMath(player, st);
                break;
            case SECRET:
                nextSecret(player, st);
                break;
            case BLOCK:
                buildBlockPath(st);
                sendMessage(player, "antibot_stage_block", blockPathLength);
                break;
        }
    }

    /**
     * Одно повторение падения: платформа из СЛУЧАЙНОГО блока, игрока
     * мгновенно подбрасывает на случайную высоту — клиент обязан реально
     * пролететь вниз. Минимальное время падения считаем от высоты,
     * чтобы телепорт/onGround-читы отсекались по физике.
     */
    /** Телепорт с разрешением в трекере телепортов (для FallPacketCheck). */
    void authorizedTeleport(Player p, Location loc) {
        teleportService.authorizeTeleport(p.getUniqueId());
        p.teleport(loc);
    }

    /**
     * Коллбек пакетной проверки падения (приходит уже на main-thread).
     * pass -> ставим игрока на арену и двигаем этап; fail -> кик.
     */
    void onFallPacketResult(UUID uuid, boolean pass, String reason) {
        CheckState st = checks.get(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (st == null || p == null || !p.isOnline() || !st.fallPacketMode) {
            return;
        }
        st.fallPacketMode = false;
        if (!pass) {
            plugin.getLogger().info("AntiBot: fall-check failed для "
                    + p.getName() + " (" + reason + ")");
            failCheck(uuid, msg("antibot_failed_kick"));
            return;
        }
        // Клиент висел в воздухе — возвращаем на арену перед следующим этапом
        authorizedTeleport(p, arenaSpawn(st));
        p.setFallDistance(0f);
        st.physicsDone++;
        passStage(p);
    }

    /** Сундук с инструментом этапа BLOCK — клик по нему разрешён. */
    public boolean isToolChestBlock(Player p, org.bukkit.block.Block b) {
        CheckState st = checks.get(p.getUniqueId());
        return st != null && st.toolChestLoc != null
                && b != null && st.toolChestLoc.equals(b.getLocation());
    }

    /** Watchdog: сундук инструмента цел и внутри лежит инструмент. */
    private void ensureToolChest(CheckState st) {
        if (st == null || st.toolChestLoc == null || st.world == null) {
            return;
        }
        org.bukkit.block.Block b = st.toolChestLoc.getBlock();
        if (b.getType() != Material.CHEST) {
            b.setType(Material.CHEST, false);
        }
        try {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
            Material need = Material.matchMaterial(blockToolMaterial);
            if (need == null) {
                need = Material.GOLDEN_PICKAXE;
            }
            org.bukkit.inventory.ItemStack cur = chest.getInventory().getItem(13);
            if (cur == null || cur.getType() != need) {
                chest.getInventory().setItem(13, makeBlockTool());
                chest.update(true, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Верхний инвентарь — сундук инструмента этапа BLOCK. */
    public boolean isToolChestTop(Player p, org.bukkit.inventory.Inventory top) {
        CheckState st = checks.get(p.getUniqueId());
        if (st == null || st.toolChestLoc == null || top == null) {
            return false;
        }
        org.bukkit.block.Block b = st.toolChestLoc.getBlock();
        return b.getState() instanceof org.bukkit.block.Chest
                && ((org.bukkit.block.Chest) b.getState()).getInventory() == top;
    }

    private void startFallRep(Player player, CheckState st) {

        UUID uuid = player.getUniqueId();
        buildPlatform(st, randomPlatformBlock());
        int height = fallMinHeight + random.nextInt(Math.max(1, fallMaxHeight - fallMinHeight + 1));
        st.tossHeight = height;
        st.fallStartY = st.baseY + 1 + height;
        st.fallStartedAt = 0;
        // Реальное падение с h блоков в MC занимает ~0.7–1.5 сек в зависимости
        // от высоты. Отсчёт стартует ПОСЛЕ фактического телепорта (внутри
        // runAtEntity), а порог даёт запас на пинг и медленные клиенты —
        // мгновенное «приземление» телепорт-чита всё равно отсекается.
        st.landingMinMs = Math.max(minFallMillis, 300L + height * 45L);
        st.awaitingBounce = false;
        st.enteredCobweb = false;
        teleportService.authorizeTeleport(uuid);
        Location target = new Location(st.world, st.arenaX + 0.5, st.baseY + 1 + height,
                st.arenaZ + 0.5, 180f, 15f);
        Scheduler.runAtEntity(plugin, player, () -> {
            if (!player.isOnline() || !checks.containsKey(uuid)) {
                return;
            }
            if (st.world == null) {
                cancelCheck(uuid, true);
                return;
            }
            player.teleport(target);
            player.setFallDistance(0f);
            st.tpTarget = target.clone();
            st.tpRetries = 3;
            // Отсчёт падения — с момента реального телепорта, иначе лаг
            // планировщика/пинг съедал запас и честные игроки слетали.
            st.fallStartedAt = System.currentTimeMillis();
        });
    }

    // ---------- события (вызываются из AuthListener) ----------

    /** @return true, если событие обработано плагином (движение/поворот контролируем мы). */
    public boolean onMove(Player player, Location from, Location to) {
        CheckState st = checks.get(player.getUniqueId());
        if (st == null || to == null) {
            return false;
        }
        st.movePackets++;
        // Глобальный трекер телепортов: если пакет телепорта потерялся
        // (лаг/пинг), следующий move-пакет придёт далеко от цели —
        // повторяем телепорт до tpRetries раз.
        if (st.tpTarget != null) {
            if (to.distanceSquared(st.tpTarget) < 9.0) {
                st.tpTarget = null;
            } else if (st.tpRetries > 0) {
                st.tpRetries--;
                teleportService.authorizeTeleport(player.getUniqueId());
                player.teleport(st.tpTarget);
                player.setFallDistance(0f);
                return true;
            } else {
                st.tpTarget = null;
            }
        }
        // Пакетная проверка падения: мир/фриз не трогаем — валидирует
        // FallPacketCheck на уровне пакетов, результат придёт коллбеком.
        if (st.fallPacketMode) {
            return true;
        }
        Stage stage = getCurrentStage(player.getUniqueId());
        // Упал ниже арены вне этапа падения/блока — вернуть на арену.
        // Иначе фриз позиции держит игрока парящим над бездной, и ваниль
        // кикает "Flying is not enabled".
        if (stage != Stage.FALL && stage != Stage.BLOCK && !st.preparingInLobby
                && to.getY() < st.baseY - 1) {
            to.setX(st.arenaX + 0.5);
            to.setY(st.baseY + 1);
            to.setZ(st.arenaZ - 3.5);
            player.setFallDistance(0f);
            return true;
        }
        if (stage == null) {
            // Отсчёт перед стартом (preparing): позицию фризим, камеру нет —
            // иначе игрок успевал сойти с платформы в пустоту до 1-го этапа.
            if (st.preparing) {
                if (st.preparingInLobby) {
                    // В лобби ходим свободно (паркур, PvP); провал — возврат на спавн
                    int lb = lobbyBaseY(to.getWorld());
                    if (to.getY() < lb - 4) {
                        to.setX(0.5);
                        to.setZ(LOBBY_Z + 0.5);
                        to.setY(lb + 1);
                        player.setFallDistance(0f);
                    }
                } else {
                    // Фриз на платформе до старта этапа — но если игрок
                    // уже провалился под арену, держать его в пустоте
                    // нельзя: ваниль кикнет за fly. Возвращаем на арену.
                    if (to.getY() < st.baseY - 3) {
                        Location rs = arenaSpawn(st);
                        to.setX(rs.getX());
                        to.setY(rs.getY());
                        to.setZ(rs.getZ());
                        player.setFallDistance(0f);
                    } else {
                        to.setX(from.getX());
                        to.setY(from.getY());
                        to.setZ(from.getZ());
                    }
                }
            } else {
                // Окно между входом в проверку и телепортом на арену:
                // позицию фризим, чтобы игрок не «гулял» по обычному миру
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
            }
            return true;
        }
        switch (stage) {
            case FALL: {
                if (st.fallStartedAt == 0) {
                    // Телепорт на высоту ещё не выполнен — позицию фризим,
                    // иначе игрок сходит с края арены и улетает в бездну.
                    // Но если он УЖЕ в бездне — фриз даст ванильный fly-кик:
                    // считаем это падением в пустоту и перекидываем.
                    if (to.getY() < st.baseY - 3) {
                        st.voidFalls++;
                        if (st.voidFalls > fallVoidMax) {
                            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                            return true;
                        }
                        sendMessage(player, "antibot_fall_void",
                                fallVoidMax - st.voidFalls + 1);
                        startFallRep(player, st);
                        return true;
                    }
                    to.setX(from.getX());
                    to.setY(from.getY());
                    to.setZ(from.getZ());
                    return true;
                }
                double y = to.getY();
                int floor = st.baseY;
                // Клиент «завис» в воздухе (телепорт не дошёл/потерялся):
                // один повторный подброс вместо пустого ожидания таймаута.
                if (st.fallStartedAt > 0 && System.currentTimeMillis() - st.fallStartedAt > 8000L) {
                    // В паутине игрок тонет медленно ПО ЗАМЫСЛУ — не перебрасываем,
                    // иначе он никогда не доползёт до дна (бесконечный цикл).
                    boolean sinkingInWeb = st.platformMat == Material.COBWEB
                            && to.getY() <= st.baseY + 1.5;
                    // ГЛАВНОЕ: если игрок физически стоит на платформе —
                    // засчитываем падение. Иначе при потерянном пакете
                    // приземления он бы перекидывался наверх раз за разом.
                    boolean onPlatform = !sinkingInWeb
                            && to.getY() <= st.baseY + 2.2 && to.getY() > st.baseY - 2
                            && Math.abs(to.getX() - (st.arenaX + 0.5)) <= 4.5
                            && Math.abs(to.getZ() - (st.arenaZ + 0.5)) <= 4.5;
                    if (onPlatform) {
                        st.awaitingBounce = false;
                        st.physicsDone++;
                        if (st.physicsDone >= physicsRepetitions) {
                            passStage(player);
                        } else {
                            sendMessage(player, "antibot_fall_next",
                                    physicsRepetitions - st.physicsDone);
                            startFallRep(player, st);
                        }
                    } else if (!sinkingInWeb && st.fallRetries < 1) {
                        st.fallRetries++;
                        startFallRep(player, st);
                    }
                    return true;
                }
                if (y < floor - 5) {
                    // Уронился в бездну мимо платформы — это НЕ проход этапа:
                    // сообщение + принудительный повтор подброса.
                    st.voidFalls++;
                    if (st.voidFalls > fallVoidMax) {
                        failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                        return true;
                    }
                    sendMessage(player, "antibot_fall_void", fallVoidMax - st.voidFalls + 1);
                    startFallRep(player, st);
                    return true;
                }
                long took = System.currentTimeMillis() - st.fallStartedAt;

                // Реальная проверка физики по типу платформы:
                if (st.platformMat == Material.SLIME_BLOCK) {
                    // Слизень: упав, клиент ОБЯЗАН отскочить вверх. Фиксируем
                    // первое касание, затем ждём отскок (y заметно поднялся).
                    double touchY = floor + 1.62;
                    if (!st.awaitingBounce && player.isOnGround() && y <= touchY) {
                        st.awaitingBounce = true;
                        st.fallStartedAt = System.currentTimeMillis();
                        // Отскок: min-время маленькое (физику уже доказало падение),
                        // а общий дедлайн этапа продлеваем — игроку нужно время
                        // взлететь, упасть и остановиться (учитываем пинг/лаг).
                        st.landingMinMs = Math.min(minFallMillis, 150L);
                        st.stageDeadline += slimeExtraMs;
                        return true;
                    }
                    if (st.awaitingBounce && !player.isOnGround() && y > touchY + 0.6) {
                        onFallLanded(player, st, took);
                        return true;
                    }
                    // Стоит на слизи (шифт гасит отскок) или приземлился обратно —
                    // само падение уже доказало живого клиента, высота отскока
                    // не обязательна. Засчитываем без проверки времени отскока.
                    if (st.awaitingBounce && player.isOnGround() && y <= touchY
                            && System.currentTimeMillis() - st.fallStartedAt > 300L) {
                        st.awaitingBounce = false;
                        st.physicsDone++;
                        if (st.physicsDone >= physicsRepetitions) {
                            passStage(player);
                        } else {
                            sendMessage(player, "antibot_fall_next",
                                    physicsRepetitions - st.physicsDone);
                            startFallRep(player, st);
                        }
                    }
                    return true;
                }
                if (st.platformMat == Material.COBWEB) {
                    // Паутина: «приземление» = вошёл в паутину (y <= floor+1.05).
                    // Ждать onGround на бедрок-подложке нельзя: тонет слишком
                    // медленно и честный игрок слетал по таймауту этапа.
                    // Защиту даёт замеренное время падения (took), а не сам блок.
                    if (y <= floor + 1.05) {
                        onFallLanded(player, st, took);
                    }
                    return true;
                }

                // Обычный блок / HONEY: на земле (или остановился по вертикали —
                // isOnGround у клиента с пингом может не выставиться) +
                // на уровне платформы + реально снизился со стартовой высоты.
                boolean settled = Math.abs(to.getY() - from.getY()) < 0.001
                        && y <= floor + 1.6;
                boolean landed = (player.isOnGround() || settled)
                        && y <= floor + 1.6
                        && (st.fallStartY - y) >= st.tossHeight * 0.6;
                if (landed) {
                    onFallLanded(player, st, took);
                }
                return true;
            }
            case CAMERA: {
                if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                    to.setX(from.getX());
                    to.setY(from.getY());
                    to.setZ(from.getZ());
                }
                float yaw = to.getYaw();
                float pitch = to.getPitch();
                if (!Float.isNaN(st.lastYaw)) {
                    float dYaw = Math.abs(yaw - st.lastYaw);
                    if (dYaw > 180f) {
                        dYaw = 360f - dYaw;
                    }
                    float dPitch = Math.abs(pitch - st.lastPitch);
                    float delta = dYaw + dPitch;
                    // Линейность: бот крутит с идеально одинаковой дельтой
                    // каждый пакет. Человек — никогда. >30 одинаковых подряд = бот.
                    if (cameraLinearity && delta > 0.01f) {
                        if (!Float.isNaN(st.prevDelta) && Math.abs(delta - st.prevDelta) < 0.001f) {
                            st.sameDeltaStreak++;
                            if (st.sameDeltaStreak > 30) {
                                failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                                return true;
                            }
                        } else {
                            st.sameDeltaStreak = 0;
                        }
                        st.prevDelta = delta;
                    }
                    // Snap-детект: ПОДРЯД резкие повороты >snap_degrees за пакет —
                    // человек делает одиночный рывок, спин-бот крутит каждый
                    // пакет. Одиночный рывок стрик сбрасывает — ложных нет.
                    if (delta >= cameraSnapDeg) {
                        if (++st.snapStreak > cameraSnapMax) {
                            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                            return true;
                        }
                    } else {
                        st.snapStreak = 0;
                    }
                    // Копим суммарный поворот (yaw+pitch). Человек проходит,
                    // покрутив камерой на сумму ~N полных оборотов — быстро или
                    // медленно, с паузами, любой скоростью. Бот со статичным
                    // прицелом не накопит градусы вообще.
                    st.rotAccum += delta;
                    if (st.rotAccum >= cameraTurns * 360.0) {
                        passStage(player);
                        return true;
                    }
                    updateBar(player, st);
                }
                st.lastYaw = yaw;
                st.lastPitch = pitch;
                return true;
            }
            case SLOTS: {
                // Позиция заморожена, но Look-пакеты считаем — это ответ
                // клиента на наши рывки камеры (бот их не дублирует).
                if (st.joltEchoPending
                        && Math.abs(to.getYaw() - st.joltPendingYaw) < 0.5f
                        && Math.abs(to.getPitch() - st.joltPendingPitch) < 0.5f) {
                    // Это эхо нашего собственного jolt-телепорта, а не
                    // самостоятельный поворот — без него счётчик забивал
                    // сервер сам себе.
                    st.joltEchoPending = false;
                } else if (to.getYaw() != from.getYaw() || to.getPitch() != from.getPitch()) {
                    st.joltAcks++;
                }
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                return true;
            }
            case BLOCK: {
                double y = to.getY();
                if (y < st.baseY - 2) {
                    // сорвался со случайного пути — возврат на старт
                    st.blockFalls++;
                    if (st.blockFalls > blockMaxFalls) {
                        failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                        return true;
                    }
                    teleportService.authorizeTeleport(player.getUniqueId());
                    to.setX(st.arenaX + 0.5);
                    to.setZ(st.arenaZ - 3.5);
                    to.setY(st.baseY + 1);
                    player.setFallDistance(0f);
                    sendMessage(player, "antibot_block_fall", blockMaxFalls - st.blockFalls);
                    return true;
                }
                clampToArena(st, to);
                return true;
            }
            default: {
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                return true;
            }
        }
    }

    private void clampToArena(CheckState st, Location to) {
        double minX = st.arenaX - 8.5;
        double maxX = st.arenaX + 8.5;
        double minZ = st.arenaZ - (blockPathLength + 6.5);
        double maxZ = st.arenaZ + 3.5;
        if (to.getX() < minX) to.setX(minX);
        if (to.getX() > maxX) to.setX(maxX);
        if (to.getZ() < minZ) to.setZ(minZ);
        if (to.getZ() > maxZ) to.setZ(maxZ);
        if (to.getY() < st.baseY - 5) {
            to.setY(st.baseY + 1);
            to.setX(st.arenaX + 0.5);
            to.setZ(st.arenaZ + 0.5);
        }
    }

    /** Ответ пришёл быстрее min_answer_ms после показа вопроса = бот. */
    private boolean tooFast(CheckState st) {
        return minAnswerMs > 0 && st.promptShownAt > 0
                && System.currentTimeMillis() - st.promptShownAt < minAnswerMs;
    }

    /** Засчитать успешное приземление/отскок и перейти к следующему повторению. */
    private void onFallLanded(Player player, CheckState st, long took) {
        if (took < st.landingMinMs) {
            // слишком быстро — телепорт-чит или подмена onGround
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
            return;
        }
        st.physicsDone++;
        if (st.physicsDone >= physicsRepetitions) {
            passStage(player);
        } else {
            sendMessage(player, "antibot_fall_next", physicsRepetitions - st.physicsDone);
            startFallRep(player, st);
        }
    }

    /** Смена слота хотбара. @return true — игрок в проверке. */
    public boolean onSlotChange(Player player, int newSlot) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null) {
            return false;
        }
        if (getCurrentStage(uuid) != Stage.SLOTS) {
            return true;
        }

        if (st.awaitingForceFollowUp) {
            long sinceForce = System.currentTimeMillis() - st.forcedAt;

            // Grace-период: первые slotGraceMillis после принудительной смены
            // слота приходят «старые» пакеты (клиент ещё не видел наш forcedSlot)
            // либо эхо-подтверждение. Их НЕ судим — просто запоминаем состояние.
            if (sinceForce < slotGraceMillis) {
                st.lastSlot = newSlot;
                return true;
            }

            // newSlot == forcedSlot после grace — игрок ещё не двигал слот,
            // это повторное эхо. Ждём дальше (не штрафуем).
            if (newSlot == st.forcedSlot) {
                st.lastSlot = newSlot;
                return true;
            }

            // Slot-lock чит: клиент игнорирует серверный forced-слот и тут же
            // возвращает выделение на СВОЙ заблокированный слот (preForceSlot).
            // Живой игрок может так сделать один раз — просим другой слот,
            // повторные возвраты = автоматический re-lock -> фейл.
            if (newSlot == st.preForceSlot) {
                st.lastSlot = newSlot;
                if (++st.relockHits >= slotRelockMax) {
                    failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                } else {
                    sendMessage(player, "antibot_slots_other", 0);
                }
                return true;
            }

            // Реальная смена на НОВЫЙ слот (не forced и не заблокированный) —
            // живой игрок действительно крутит хотбар.
            st.awaitingForceFollowUp = false;
            finishSlots(player, st);
            return true;
        }

        if (st.lastSlot != -1 && newSlot != st.lastSlot) {
            st.slotsDone++;
        }
        st.lastSlot = newSlot;
        if (st.slotsDone >= slotsRequired) {
            if (slotsAntiCheat) {
                forceSlotCheck(player, st);
            } else {
                finishSlots(player, st);
            }
        }
        return true;
    }

    /**
     * Анти-чит NoSlotChange: сервер сам ставит слот F. После grace-периода ждём
     * реальную смену на слот != F. Ванильный клиент её даст (колесо/цифры);
     * бот, игнорирующий серверный forced-слот, — нет. Проверка отказоустойчива:
     * не карает за старые пакеты и за выбор слота цифрами.
     */
    private void forceSlotCheck(Player player, CheckState st) {
        st.forceAttempts++;
        if (st.forceAttempts > slotMaxAttempts) {
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
            return;
        }
        // Запоминаем слот, который чит мог «залочить»: возврат на него после
        // форса — признак автоматической блокировки, а не выбора игрока.
        st.preForceSlot = st.lastSlot;
        // Выбираем forced != текущему слоту игрока
        st.forcedSlot = (st.lastSlot + 3) % 9;
        player.getInventory().setHeldItemSlot(st.forcedSlot);
        st.forcedAt = System.currentTimeMillis();
        st.awaitingForceFollowUp = true;
        sendMessage(player, "antibot_slots_force", 0);
    }

    /** Ответ на капчу из чата. @return 0 — верно, 1 — неверно, 2 — попытки исчерпаны */
    public int submitCode(Player player, String input) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.code == null) {
            return 0;
        }
        if (tooFast(st)) {
            failCheck(uuid, msg("antibot_failed_kick"));
            return 2;
        }
        if (st.code.equalsIgnoreCase(input == null ? "" : input.trim())) {
            st.code = null;
            passStage(player);
            return 0;
        }
        int attempts = ++st.captchaAttempts;
        if (attempts >= maxAttempts) {
            return 2;
        }
        st.code = generateCode();
        if (mapCaptcha) {
            giveCaptchaMap(player, st);
        }
        sendCaptcha(player, st);
        return 1;
    }

    /** Клик по сообщению (/rpverify <token>). @return 0 — верно, 1 — неверный токен */
    public int submitClickToken(Player player, String token) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.clickToken == null) {
            return 0;
        }
        if (minClickMs > 0 && st.promptShownAt > 0
                && System.currentTimeMillis() - st.promptShownAt < minClickMs) {
            failCheck(uuid, msg("antibot_failed_kick"));
            return 1;
        }
        if (!st.clickToken.equals(token)) {
            return 1;
        }
        st.clickToken = null;
        passStage(player);
        return 0;
    }

    /** Сломан блок на этапе BLOCK? @return true — событие наше (отменять нельзя). */
    public boolean onBlockBreak(Player player, Block block) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || getCurrentStage(uuid) != Stage.BLOCK) {
            return false;
        }
        if (block.getType() == st.targetBlock
                && block.getX() == st.targetX
                && block.getZ() == st.targetZ) {
            // Если включён сундук с инструментом — ломать можно ТОЛЬКО им
            if (blockToolChest) {
                Material need = Material.matchMaterial(blockToolMaterial);
                if (need == null) {
                    need = Material.GOLDEN_PICKAXE;
                }
                org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() != need) {
                    sendMessage(player, "antibot_need_tool", 0);
                    return false;
                }
            }
            st.blockBroken = true;
            st.blocksTouched.add(new long[]{block.getX(), block.getY(), block.getZ()});
            return true;
        }
        return false; // чужие блоки ломать нельзя
    }

    /** Подобрал дроп? @return true — событие наше. */
    public boolean onPickup(Player player) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || getCurrentStage(uuid) != Stage.BLOCK) {
            return false;
        }
        if (st.blockBroken) {
            passStage(player);
        }
        return true;
    }

    private void passStage(Player player) {
        // Чат/команды могут прийти с АСИНХРОННОГО потока — смена этапа
        // перестраивает мир (buildBlockPath/platform), Paper ловит
        // "Asynchronous block remove". Переносим на главный.
        if (!Scheduler.isPrimaryThread()) {
            Scheduler.runAtEntity(plugin, player, () -> passStage(player));
            return;
        }
        sendMessage(player, "antibot_stage_passed", 0);
        advanceStage(player);
    }

    // ---------- SLOTS: рывки камеры (проверка живого клиента) ----------

    /**
     * Во время этапа SLOTS сервер резко дёргает камеру игрока slot_camera_jolts
     * раз. Живой клиент отвечает на каждый телепорт Look-пакетом (onMove с
     * новым yaw). Бот без реального клиента пакеты не пришлёт — кик.
     */
    private void scheduleCameraJolts(Player player, CheckState st) {
        UUID uuid = player.getUniqueId();
        long interval = joltIntervalTicks;
        for (int i = 1; i <= slotCameraJolts; i++) {
            Scheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline() || !checks.containsKey(uuid)
                        || getCurrentStage(uuid) != Stage.SLOTS) {
                    return;
                }
                teleportService.authorizeTeleport(uuid);
                Location loc = player.getLocation().clone();
                loc.setYaw(random.nextFloat() * 360f - 180f);
                loc.setPitch(random.nextFloat() * 60f - 30f);
                player.teleport(loc);
                st.joltSent++;
                st.joltPendingYaw = loc.getYaw();
                st.joltPendingPitch = loc.getPitch();
                st.joltEchoPending = true;
            }, i * interval);
        }
        st.joltDeadline = System.currentTimeMillis() + slotCameraJolts * interval * 50L + 2500L;
    }

    /**
     * Этап SLOTS пройден по слотам — но сначала проверяем ответы на рывки
     * камеры. Если клиент не ответил на минимум рывков — это бот.
     */
    private void finishSlots(Player player, CheckState st) {
        if (slotCameraTest && st.joltSent > 0 && st.joltAcks < slotCameraMinAcks) {
            if (System.currentTimeMillis() < st.joltDeadline) {
                // рывки ещё идут — ждём, решение примет tick()
                st.slotsPendingJolts = true;
                return;
            }
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
            return;
        }
        passStage(player);
    }

    // ---------- PUZZLE: «убери лишних» + слово-старт ----------

    /**
     * Пазл: GUI-инвентарь 27 слотов с животными. Нужно убрать ТОЛЬКО целевых
     * (puzzle_remove — например, котиков), не трогая остальных. Затем игрок
     * пишет в чат слово-старт (puzzle_confirm_word) — идёт проверка 3…2…1.
     */
    private void startPuzzle(Player player, CheckState st) {
        // Режим blocks: стена 3x3 из рамок, на каждой 1 животное —
        // лишних надо УДАРИТЬ. both: рамки, при сбое — GUI-фолбэк.
        if (!"gui".equals(puzzleMode) && spawnPuzzleGrid(player, st)) {
            sendPuzzleTask(player, st);
            sendMessage(player, "antibot_puzzle_turn", 0);
            return;
        }
        openPuzzleGui(player, st);
    }

    /** GUI-вариант пазла: инвентарь 27 слотов, забрать помеченные яйца. */
    private void openPuzzleGui(Player player, CheckState st) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27,
                toBarText("&c&lЗАБЕРИ &f&lвсе яйца &8[&c★&8]"));
        st.puzzleRemoveSlots = new java.util.HashSet<>();
        int removeCount = 4 + random.nextInt(3); // 4–6 целей
        List<Material> layout = new ArrayList<>();
        for (int i = 0; i < removeCount; i++) {
            layout.add(puzzleRemove.get(random.nextInt(puzzleRemove.size())));
        }
        while (layout.size() < 27) {
            layout.add(puzzleKeep.get(random.nextInt(puzzleKeep.size())));
        }
        Collections.shuffle(layout, random);
        for (int i = 0; i < 27; i++) {
            Material m = layout.get(i);
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m);
            if (puzzleRemove.contains(m)) {
                try {
                    org.bukkit.inventory.meta.ItemMeta im = it.getItemMeta();
                    if (im != null) {
                        im.setDisplayName(toBarText("&c★ Забери меня!"));
                        it.setItemMeta(im);
                    }
                } catch (Throwable ignored) {
                }
                st.puzzleRemoveSlots.add(i);
            }
            inv.setItem(i, it);
        }
        st.puzzlePlaced = removeCount;
        st.puzzleInv = inv;
        st.puzzleAwaitConfirm = false;
        sendMessage(player, "antibot_stage_puzzle", 0);
        if (!puzzleAutoPass) {
            MessageService ms2 = messages();
            Map<String, String> ph2 = new HashMap<>();
            ph2.put("word", puzzleConfirmWord);
            String hint = ms2 == null ? null : ms2.message("antibot_puzzle_finish_hint", ph2);
            if (hint != null && !hint.isEmpty()) {
                player.sendMessage(hint);
            }
        }
        spawnPuzzlePicture(player, st);
        player.openInventory(inv);
        st.promptShownAt = System.currentTimeMillis();
    }

    // ---------- PUZZLE: картина на стене (рамка + карта с PNG) ----------

    /**
     * Картинки пазла живут в plugins/RegisterPlugin/puzzles/*.png —
     * сервер может подложить СВОИ изображения (128×128 рекомендуется).
     * Если папка пустая — генерируем 4 варианта сами (пиксельные зверюшки).
     */
    private void ensurePuzzleImages() {
        try {
            java.io.File dir = new java.io.File(plugin.getDataFolder(), "puzzles");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            java.io.File[] pngs = dir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
            if (pngs != null && pngs.length > 0) {
                puzzleImages = java.util.Arrays.asList(pngs);
                return;
            }
            List<java.io.File> made = new ArrayList<>();
            for (int v = 0; v < 4; v++) {
                java.io.File f = new java.io.File(dir, "puzzle_" + (v + 1) + ".png");
                javax.imageio.ImageIO.write(drawPuzzleImage(v), "PNG", f);
                made.add(f);
            }
            puzzleImages = made;
            plugin.getLogger().info("AntiBot: сгенерировано " + made.size() + " картинок пазла в puzzles/");
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: не удалось подготовить картинки пазла: " + t.getMessage());
            puzzleImages = Collections.emptyList();
        }
    }

    /** Сцена 128×128: небо, земля и ряд зверей — целевые (котики) среди прочих. */
    private java.awt.image.BufferedImage drawPuzzleImage(int variant) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x87CEEB));      // небо
        g.fillRect(0, 0, 128, 96);
        g.setColor(new java.awt.Color(0x7CB342));      // трава
        g.fillRect(0, 96, 128, 32);
        g.setColor(new java.awt.Color(0x8D6E63));      // земля
        g.fillRect(0, 118, 128, 10);
        g.setColor(new java.awt.Color(0xFFF176));      // солнце
        g.fillOval(104, 8, 16, 16);
        int n = 5 + variant % 2;
        for (int i = 0; i < n; i++) {
            int ax = 6 + i * (116 / n);
            int kind = (i + variant) % 4;
            switch (kind) {
                case 0: drawCat(g, ax, 66); break;
                case 1: drawDog(g, ax, 68); break;
                case 2: drawGiraffe(g, ax, 34); break;
                default: drawMouse(g, ax + 4, 80); break;
            }
        }
        g.dispose();
        return img;
    }

    private void drawCat(java.awt.Graphics2D g, int x, int y) {
        g.setColor(new java.awt.Color(0xE8913A));
        g.fillOval(x, y, 20, 14);                     // тело
        g.fillOval(x + 13, y - 8, 12, 12);            // голова
        g.fillPolygon(new int[]{x + 15, x + 18, x + 15}, new int[]{y - 8, y - 14, y - 3}, 3);
        g.fillPolygon(new int[]{x + 22, x + 25, x + 22}, new int[]{y - 8, y - 14, y - 3}, 3);
        g.fillRect(x - 4, y - 6, 4, 3);               // хвост вверх
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(x + 17, y - 4, 2, 3);
        g.fillOval(x + 22, y - 4, 2, 3);
    }

    private void drawDog(java.awt.Graphics2D g, int x, int y) {
        g.setColor(new java.awt.Color(0x8D6E63));
        g.fillOval(x, y, 22, 13);                     // тело
        g.fillOval(x + 15, y - 7, 12, 11);            // голова
        g.setColor(new java.awt.Color(0x5D4037));     // висячие уши
        g.fillRect(x + 15, y - 7, 3, 8);
        g.fillRect(x + 24, y - 7, 3, 8);
        g.setColor(new java.awt.Color(0x8D6E63));
        g.fillRect(x - 3, y - 5, 3, 3);               // хвост
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(x + 19, y - 4, 2, 2);
        g.fillOval(x + 25, y - 1, 2, 2);              // нос
    }

    private void drawGiraffe(java.awt.Graphics2D g, int x, int y) {
        g.setColor(new java.awt.Color(0xF4B942));
        g.fillRect(x + 4, y, 8, 42);                  // шея
        g.fillOval(x + 2, y - 6, 12, 10);             // голова
        g.fillRect(x + 4, y - 10, 2, 5);              // рожки
        g.fillRect(x + 10, y - 10, 2, 5);
        g.fillOval(x - 4, y + 38, 20, 12);            // тело
        g.setColor(new java.awt.Color(0x8D6E63));     // пятна
        g.fillOval(x + 5, y + 8, 4, 4);
        g.fillOval(x + 6, y + 20, 4, 4);
        g.fillOval(x, y + 40, 5, 5);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(x + 10, y - 4, 2, 2);
    }

    private void drawMouse(java.awt.Graphics2D g, int x, int y) {
        g.setColor(new java.awt.Color(0x9E9E9E));
        g.fillOval(x, y, 16, 11);                     // тело
        g.fillOval(x + 11, y - 5, 9, 9);              // голова
        g.fillOval(x + 12, y - 11, 7, 7);             // ухо
        g.setColor(new java.awt.Color(0xF48FB1));
        g.fillOval(x + 14, y - 9, 3, 3);              // внутри уха
        g.fillOval(x + 18, y - 1, 2, 2);              // нос
        g.setColor(new java.awt.Color(0x9E9E9E));
        g.drawLine(x - 5, y + 6, x, y + 8);           // хвост
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(x + 14, y - 3, 2, 2);
    }

    /**
     * Картина на стене перед игроком: белая стена 3×3 + рамка с картой.
     * Карта показывает случайный PNG из puzzles/ — живой человек видит
     * «кого убирать», бот картину не прочитает.
     */
    private void spawnPuzzlePicture(Player player, CheckState st) {
        if (!puzzleMap || puzzleImages.isEmpty() || st.world == null) {
            return;
        }
        try {
            java.io.File f = puzzleImages.get(random.nextInt(puzzleImages.size()));
            final java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
            World w = st.world;
            org.bukkit.map.MapView view = Bukkit.createMap(w);
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new org.bukkit.map.MapRenderer() {
                private boolean drawn;
                @Override
                public void render(org.bukkit.map.MapView mv, org.bukkit.map.MapCanvas canvas, Player p) {
                    if (drawn) {
                        return;
                    }
                    drawn = true;
                    canvas.drawImage(0, 0, img);
                }
            });
            org.bukkit.inventory.ItemStack map = new org.bukkit.inventory.ItemStack(Material.FILLED_MAP);
            org.bukkit.inventory.meta.MapMeta mm = (org.bukkit.inventory.meta.MapMeta) map.getItemMeta();
            mm.setMapView(view);
            map.setItemMeta(mm);
            // стена перед лицом (игрок смотрит на -Z)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, st.arenaZ - 4)
                            .setType(Material.QUARTZ_BLOCK, false);
                }
            }
            Location fl = new Location(w, st.arenaX + 0.5, st.baseY + 2, st.arenaZ - 3.0);
            org.bukkit.entity.ItemFrame frame = w.spawn(fl, org.bukkit.entity.ItemFrame.class);
            try {
                frame.setFacingDirection(org.bukkit.block.BlockFace.SOUTH);
            } catch (Throwable ignored) {
            }
            frame.setItem(map);
            st.puzzleFrameId = frame.getUniqueId();
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: картина пазла не создана: " + t.getMessage());
        }
    }

    private void removePuzzlePicture(CheckState st) {
        if (st != null && st.puzzleFrames != null) {
            removePuzzleFrames(st);
            st.puzzleFrames = null;
        }
        if (st == null || st.puzzleFrameId == null || st.world == null) {
            return;
        }
        try {
            org.bukkit.entity.Entity e = Bukkit.getEntity(st.puzzleFrameId);
            if (e != null) {
                e.remove();
            }
        } catch (Throwable ignored) {
        }
        st.puzzleFrameId = null;
    }

    /**
     * Клик по GUI пазла. «Лишних» можно забирать (удаляем), «нужных» трогать
     * нельзя — клик по ним отменяется.
     * @return true — клик наш (событие надо отменить/обработать).
     */
    public boolean onPuzzleClick(Player player, org.bukkit.inventory.Inventory inv, int slot) {
        CheckState st = checks.get(player.getUniqueId());
        if (st == null || st.puzzleInv == null || inv == null || inv != st.puzzleInv) {
            return false;
        }
        org.bukkit.inventory.ItemStack item = inv.getItem(slot);
        if (item != null && st.puzzleRemoveSlots.contains(slot)
                && puzzleRemove.contains(item.getType())) {
            inv.setItem(slot, null);
            st.puzzleRemoveSlots.remove(slot);
            // Авто-проход: всё лишнее убрано — этап засчитан сразу,
            // без слова-старта и обратного отсчёта.
            if (puzzleAutoPass && st.puzzleRemoveSlots.isEmpty()) {
                final UUID u = player.getUniqueId();
                Scheduler.runAtEntityLater(plugin, player, () -> {
                    if (player.isOnline() && checks.containsKey(u)) {
                        try { player.closeInventory(); } catch (Throwable ignored) {}
                        sendMessage(player, "antibot_puzzle_done", 0);
                        passStage(player);
                    }
                }, 10L);
            }
        }
        return true;
    }

    /**
     * Закрытие окна пазла: если этап ещё идёт — окно открывается заново
     * (unclosable-GUI: игрок не может «потерять» задание клавишей E/Esc).
     *  true - это было окно пазла (событие поглощено).
     */
    public boolean onPuzzleClose(final Player player, org.bukkit.inventory.Inventory inv) {
        CheckState st = checks.get(player.getUniqueId());
        if (st == null || st.puzzleInv == null || inv != st.puzzleInv) {
            return false;
        }
        if (st.puzzleAwaitConfirm || !puzzleUnclosable
                || getCurrentStage(player.getUniqueId()) != Stage.PUZZLE) {
            return true;
        }
        final UUID u = player.getUniqueId();
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline() && checks.containsKey(u)
                    && getCurrentStage(u) == Stage.PUZZLE && !st.puzzleAwaitConfirm) {
                player.openInventory(st.puzzleInv);
            }
        }, 1L);
        return true;
    }

    /**
     * Слово-старт из чата («vse»). Запускает обратный отсчёт 3…2…1
     * и финальную валидацию пазла.
     * @return true — сообщение наше (его надо скрыть из чата).
     */
    public boolean submitPuzzleConfirm(final Player player, String input) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || getCurrentStage(uuid) != Stage.PUZZLE || st.puzzleAwaitConfirm) {
            return false;
        }
        if (tooFast(st)) {
            failCheck(uuid, msg("antibot_failed_kick"));
            return true;
        }
        if (puzzleAutoPass) {
            return false;   // слово не нужно — проход автоматический
        }
        if (input == null || !input.trim().equalsIgnoreCase(puzzleConfirmWord)) {
            return false;
        }
        st.puzzleAwaitConfirm = true;
        try {
            player.closeInventory();
        } catch (Throwable ignored) {
        }
        // «идёт проверка 3…2…1» — по сообщению в секунду, затем валидация
        for (int i = 3; i >= 1; i--) {
            final int n = i;
            Scheduler.runAtEntityLater(plugin, player, () -> {
                if (player.isOnline() && checks.containsKey(uuid)) {
                    sendMessage(player, "antibot_puzzle_countdown", n);
                }
            }, (3L - i) * 20L);
        }
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline() && checks.containsKey(uuid)) {
                validatePuzzle(player, st);
            }
        }, 60L);
        return true;
    }

    private void validatePuzzle(Player player, CheckState st) {
        boolean cleared = st.puzzleFrames != null
                ? st.puzzleExtraLeft <= 0
                : (st.puzzleRemoveSlots != null && st.puzzleRemoveSlots.isEmpty());
        if (cleared) {
            passStage(player);
            return;
        }
        st.puzzleWrong++;
        st.puzzleAwaitConfirm = false;
        if (st.puzzleWrong >= puzzleMaxWrong) {
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
            return;
        }
        int left = st.puzzleFrames != null ? st.puzzleExtraLeft
                : (st.puzzleRemoveSlots == null ? 0 : st.puzzleRemoveSlots.size());
        sendMessage(player, "antibot_puzzle_wrong", left);
        if (st.puzzleInv != null) {
            player.openInventory(st.puzzleInv);
        }
    }

    // ---------- PUZZLE blocks: стена 3x3, на каждом блоке 1 животное ----------

    /**
     * Стена 3x3 из рамок перед игроком. В каждой рамке карта с ОДНИМ
     * животным-тайлом. Часть рамок — «лишние» (puzzle_remove_names),
     * их надо убрать ударом. Картинки тайлов: puzzles/tiles/<имя>.png —
     * админ может подложить свои PNG (скачанные/сгенерированные).
     * @return false — рамки не встали, нужен GUI-фолбэк
     */
    private boolean spawnPuzzleGrid(Player player, CheckState st) {
        if (st.world == null) {
            return false;
        }
        try {
            List<String> keep = new ArrayList<>(puzzleTileNames);
            keep.removeAll(puzzleRemoveNames);
            if (keep.isEmpty()) {
                keep.add("pig");
            }
            int extras = Math.min(puzzleRemoveCount, 8);
            List<String> cells = new ArrayList<>(9);
            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < extras; i++) {
                String n = puzzleRemoveNames.get(random.nextInt(puzzleRemoveNames.size()));
                cells.add(n);
                counts.merge(n, 1, Integer::sum);
            }
            while (cells.size() < 9) {
                cells.add(keep.get(random.nextInt(keep.size())));
            }
            Collections.shuffle(cells, random);

            World w = st.world;
            // puzzle_front=true: стена ПЕРЕД игроком (смотрит на -Z,
            // стена на arenaZ-4) — задание сразу перед прицелом.
            // false — стена позади (arenaZ+4): старый режим с оборотом.
            int wallZ = st.arenaZ + (puzzleFront ? -4 : 4);
            int frameZ = st.arenaZ + (puzzleFront ? -3 : 3);
            org.bukkit.block.BlockFace frameFace = puzzleFront
                    ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
            int signX = puzzleFront ? st.arenaX - 2 : st.arenaX;
            int signZ = st.arenaZ - 3;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, wallZ)
                            .setType(Material.QUARTZ_BLOCK, false);
                }
            }
            // табличка-указатель ПЕРЕД игроком (на -Z, лицом к нему)
            try {
                if (!puzzleFront) {
                    w.getBlockAt(st.arenaX, st.baseY + 2, st.arenaZ - 4)
                            .setType(Material.QUARTZ_BLOCK, false);
                }
                org.bukkit.block.Block sb = w.getBlockAt(signX, st.baseY + 2, signZ);
                // front-режим: знак стоит сбоку от стены — стоячий OAK_SIGN
                // (wall sign без опорного блока отвалился бы)
                if (puzzleFront) {
                    sb.setType(Material.OAK_SIGN, false);
                    org.bukkit.block.data.Rotatable rot =
                            (org.bukkit.block.data.Rotatable) sb.getBlockData();
                    rot.setRotation(org.bukkit.block.BlockFace.SOUTH);
                    sb.setBlockData(rot, false);
                } else {
                    sb.setType(Material.OAK_WALL_SIGN, false);
                    org.bukkit.block.data.Directional dir =
                            (org.bukkit.block.data.Directional) sb.getBlockData();
                    dir.setFacing(org.bukkit.block.BlockFace.SOUTH);
                    sb.setBlockData(dir, false);
                }
                org.bukkit.block.Sign ps = (org.bukkit.block.Sign) sb.getState();
                // Текст таблички — из конфига antibot.puzzle_sign_lines
                for (int li = 0; li < 4 && li < puzzleSignLines.size(); li++) {
                    ps.setLine(li, org.bukkit.ChatColor.translateAlternateColorCodes('&',
                            puzzleSignLines.get(li)));
                }
                ps.update(true, false);
            } catch (Throwable ignored) {
            }
            st.puzzleFrames = new HashMap<>();
            st.puzzleExtraNames = counts;
            st.puzzleExtraLeft = extras;
            for (int i = 0; i < 9; i++) {
                String name = cells.get(i);
                int dx = (i % 3) - 1;
                int dy = 1 + (i / 3);
                Location fl = new Location(w, st.arenaX + dx + 0.5,
                        st.baseY + dy + 0.5, frameZ + 0.0);
                org.bukkit.entity.ItemFrame frame =
                        w.spawn(fl, org.bukkit.entity.ItemFrame.class);
                try {
                    frame.setFacingDirection(frameFace);
                } catch (Throwable ignored) {
                }
                frame.setItem(tileItem(name, w), false);
                // НЕ invulnerable/fixed: у invulnerable-сущности NMS отсекает
                // урон до EntityDamageByEntityEvent — удар бы не обрабатывался.
                // Защита от лишних — через отмену события в onPuzzleFrameHit.
                st.puzzleFrames.put(frame.getUniqueId(), counts.containsKey(name));
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: стена пазла не создана: " + t.getMessage());
            return false;
        }
    }

    /** Текст задания для blocks-пазла (свой puzzle_task или автогенерация). */
    private void sendPuzzleTask(Player player, CheckState st) {
        MessageService ms = messages();
        StringBuilder tg = new StringBuilder();
        if (st.puzzleExtraNames != null) {
            for (Map.Entry<String, Integer> e : st.puzzleExtraNames.entrySet()) {
                if (tg.length() > 0) {
                    tg.append(", ");
                }
                tg.append(tileDisplay(e.getKey())).append(" \u00D7").append(e.getValue());
            }
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("targets", tg.toString());
        ph.put("count", String.valueOf(st.puzzleExtraLeft));
        ph.put("word", puzzleConfirmWord);
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(st.stages != null ? st.stages.size() : stageOrder.size()));
        String text = null;
        if (puzzleTaskText != null && !puzzleTaskText.trim().isEmpty()) {
            text = org.bukkit.ChatColor.translateAlternateColorCodes('&', puzzleTaskText)
                    .replace("{targets}", tg.toString())
                    .replace("{count}", String.valueOf(st.puzzleExtraLeft))
                    .replace("{word}", puzzleConfirmWord)
                    .replace("{stage_num}", ph.get("stage_num"))
                    .replace("{stage_total}", ph.get("stage_total"));
        } else if (ms != null) {
            text = ms.message("antibot_stage_puzzle_blocks", ph);
        }
        if (text == null || text.isEmpty()) {
            text = "\u00A7c\u00A7lПроверка: \u00A7fубери лишних — \u00A7c"
                    + tg + "\u00A7f. Ударь по лишней картинке!";
        }
        player.sendMessage(text);
        st.promptShownAt = System.currentTimeMillis();
        // Подсказка завершения: при выключенном авто-проходе — слово в чат
        if (!puzzleAutoPass && ms != null) {
            String hint = ms.message("antibot_puzzle_finish_hint", ph);
            if (hint != null && !hint.isEmpty()) {
                player.sendMessage(hint);
            }
        }
    }

    /** Предмет-карта с тайлом. MapView кэшируется на тайл — без утечки map-id. */
    private org.bukkit.inventory.ItemStack tileItem(String name, World w) {
        org.bukkit.map.MapView view = tileViews.computeIfAbsent(name,
                n -> createTileView(n, w));
        org.bukkit.inventory.ItemStack it =
                new org.bukkit.inventory.ItemStack(Material.FILLED_MAP);
        if (view != null) {
            try {
                org.bukkit.inventory.meta.MapMeta mm =
                        (org.bukkit.inventory.meta.MapMeta) it.getItemMeta();
                if (mm != null) {
                    mm.setMapView(view);
                    it.setItemMeta(mm);
                }
            } catch (Throwable ignored) {
            }
        }
        return it;
    }

    private org.bukkit.map.MapView createTileView(String name, World w) {
        java.awt.image.BufferedImage img = tileImgs.get(name);
        if (img == null || w == null) {
            return null;
        }
        try {
            org.bukkit.map.MapView view = Bukkit.createMap(w);
            view.getRenderers().forEach(view::removeRenderer);
            view.addRenderer(new org.bukkit.map.MapRenderer() {
                private boolean drawn;
                @Override
                public void render(org.bukkit.map.MapView mv,
                        org.bukkit.map.MapCanvas canvas, Player p) {
                    if (drawn) {
                        return;
                    }
                    drawn = true;
                    canvas.drawImage(0, 0, img);
                }
            });
            return view;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Тайлы: свои PNG из puzzles/tiles/<имя>.png или автогенерация. */
    private void ensurePuzzleTiles() {
        try {
            java.io.File dir = new java.io.File(plugin.getDataFolder(), "puzzles/tiles");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            names.addAll(puzzleTileNames);
            names.addAll(puzzleRemoveNames);
            for (String name : names) {
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                java.io.File f = new java.io.File(dir, name.trim() + ".png");
                try {
                    if (f.exists()) {
                        tileImgs.put(name, javax.imageio.ImageIO.read(f));
                    } else {
                        java.awt.image.BufferedImage img = drawTile(name.trim());
                        javax.imageio.ImageIO.write(img, "PNG", f);
                        tileImgs.put(name.trim(), img);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("AntiBot: тайл '" + name + "': " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: не удалось подготовить тайлы пазла: " + t.getMessage());
        }
    }

    /** Русское имя тайла для текста задания (вместо служебного id). */
    private static String tileDisplay(String n) {
        if (n == null) {
            return "?";
        }
        switch (n.toLowerCase(java.util.Locale.ROOT)) {
            case "cat": return "кот";
            case "dog": return "собака";
            case "pig": return "свинья";
            case "cow": return "корова";
            case "chicken": return "курица";
            case "sheep": return "овца";
            case "rabbit": return "кролик";
            case "fox": return "лиса";
            case "panda": return "панда";
            case "giraffe": return "жираф";
            case "mouse": return "мышь";
            case "man": return "человек";
            case "man_black":
            case "bandit": return "человек в чёрном";
            default: return n;
        }
    }

    /**
     * Самовосстановление лобби: табличка очереди, сундук PvP и кнопка
     * скорости проверяются каждые ~5 сек и переставляются, если сломаны.
     */
    private void restoreLobbyStructures() {
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            return;
        }
        try {
            int y = lobbyBaseY(w);
            org.bukkit.block.Block signBlock = w.getBlockAt(0, y + 1, LOBBY_Z + 3);
            if (!(signBlock.getState() instanceof org.bukkit.block.Sign)) {
                signBlock.setType(Material.OAK_SIGN, false);
            }
            org.bukkit.block.Sign sign = (org.bukkit.block.Sign) signBlock.getState();
            if (sign.getLine(0) == null || sign.getLine(0).isEmpty()) {
                sign.setLine(0, "Очередь");
                sign.setLine(1, "на проверку");
                sign.setLine(2, "жди на боссбаре");
                sign.update(true, false);
            }
            // Полное восстановление декора: фонари/стёкла/паркур/PvP-зону
            // ломают чаще всего — переставляем только отличающиеся блоки.
            repairLobbyDecor(w, y);
            if (pvpEnabled) {
                if (w.getBlockAt(pvpChestX, y + 1, pvpChestZ).getType() != Material.CHEST) {
                    w.getBlockAt(pvpChestX, y, pvpChestZ).setType(Material.SMOOTH_STONE, false);
                    w.getBlockAt(pvpChestX, y + 1, pvpChestZ).setType(Material.CHEST, false);
                    refillPvpChest();
                }
                if (speedButtonEnabled && speedButtonLoc != null
                        && !(speedButtonLoc.getBlock().getBlockData()
                                instanceof org.bukkit.block.data.type.Switch)) {
                    org.bukkit.block.Block btn = speedButtonLoc.getBlock();
                    btn.setType(Material.STONE_BUTTON, false);
                    try {
                        org.bukkit.block.data.type.Switch sw =
                                (org.bukkit.block.data.type.Switch) btn.getBlockData();
                        sw.setFace(org.bukkit.block.data.type.Switch.Face.FLOOR);
                        btn.setBlockData(sw, false);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Тайл 128x128 для имени: фон + спрайт животного (или буква, если имя чужое). */
    private java.awt.image.BufferedImage drawTile(String name) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new java.awt.Color(0x87CEEB));
        g.fillRect(0, 0, 128, 100);
        g.setColor(new java.awt.Color(0x7CB342));
        g.fillRect(0, 100, 128, 28);
        switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "cat":     drawCat(g, 48, 70); break;
            case "dog":     drawDog(g, 48, 70); break;
            case "giraffe": drawGiraffe(g, 50, 55); break;
            case "mouse":   drawMouse(g, 50, 80); break;
            case "pig":     drawPig(g); break;
            case "cow":     drawCow(g); break;
            case "chicken": drawChicken(g); break;
            case "sheep":   drawSheep(g); break;
            case "rabbit":  drawRabbit(g); break;
            case "fox":     drawFox(g); break;
            case "panda":   drawPanda(g); break;
            case "man":     drawMan(g, new java.awt.Color(0x3F51B5)); break;
            case "man_black":
            case "bandit":  drawMan(g, java.awt.Color.BLACK); break;
            default:        drawLetterTile(g, name); break;
        }
        g.dispose();
        return img;
    }

    private void drawPig(java.awt.Graphics2D g) {
        g.setColor(new java.awt.Color(0xF8A5C2));
        g.fillOval(38, 62, 52, 32);
        g.fillOval(74, 48, 30, 28);
        g.setColor(new java.awt.Color(0xE57B9B));
        g.fillOval(96, 60, 12, 10);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(82, 54, 4, 4);
        g.fillOval(94, 54, 4, 4);
        g.fillOval(99, 63, 2, 2);
        g.fillOval(103, 63, 2, 2);
    }

    private void drawCow(java.awt.Graphics2D g) {
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(36, 58, 56, 36);
        g.fillOval(76, 42, 32, 30);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(44, 62, 12, 10);
        g.fillOval(62, 74, 10, 12);
        g.fillOval(82, 46, 5, 5);
        g.fillOval(95, 46, 5, 5);
        g.setColor(new java.awt.Color(0xE8B4B8));
        g.fillOval(86, 58, 18, 12);
    }

    private void drawChicken(java.awt.Graphics2D g) {
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(44, 52, 40, 42);
        g.setColor(java.awt.Color.RED);
        g.fillOval(56, 42, 10, 8);
        g.fillOval(64, 40, 10, 10);
        g.setColor(new java.awt.Color(0xF9A825));
        g.fillPolygon(new int[]{78, 90, 78}, new int[]{66, 72, 74}, 3);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(70, 58, 4, 4);
        g.setColor(new java.awt.Color(0xF9A825));
        g.fillRect(54, 94, 4, 10);
        g.fillRect(70, 94, 4, 10);
    }

    private void drawSheep(java.awt.Graphics2D g) {
        g.setColor(new java.awt.Color(0xEEEEEE));
        for (int i = 0; i < 7; i++) {
            g.fillOval(36 + (i % 4) * 14, 58 + (i / 4) * 14, 18, 18);
        }
        g.setColor(new java.awt.Color(0x616161));
        g.fillOval(86, 60, 22, 20);
        g.fillRect(44, 92, 6, 12);
        g.fillRect(74, 92, 6, 12);
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(91, 66, 4, 4);
        g.fillOval(99, 66, 4, 4);
    }

    private void drawRabbit(java.awt.Graphics2D g) {
        g.setColor(new java.awt.Color(0xB0A99F));
        g.fillOval(46, 66, 36, 30);
        g.fillOval(64, 46, 24, 24);
        g.fillOval(68, 18, 8, 30);
        g.fillOval(80, 18, 8, 30);
        g.setColor(new java.awt.Color(0xF48FB1));
        g.fillOval(70, 22, 4, 22);
        g.fillOval(82, 22, 4, 22);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(70, 54, 4, 4);
        g.fillOval(80, 54, 4, 4);
    }

    private void drawFox(java.awt.Graphics2D g) {
        g.setColor(new java.awt.Color(0xE97132));
        g.fillOval(36, 62, 50, 30);
        g.fillPolygon(new int[]{76, 104, 84}, new int[]{48, 62, 70}, 3);
        g.fillPolygon(new int[]{78, 82, 78}, new int[]{44, 58, 52}, 3);
        g.fillPolygon(new int[]{92, 96, 92}, new int[]{44, 58, 52}, 3);
        g.setColor(java.awt.Color.WHITE);
        g.fillPolygon(new int[]{40, 20, 36}, new int[]{66, 72, 80}, 3);
        g.fillOval(96, 66, 10, 8);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(84, 56, 4, 4);
        g.fillOval(100, 68, 4, 4);
    }

    private void drawPanda(java.awt.Graphics2D g) {
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(40, 58, 48, 38);
        g.fillOval(52, 34, 34, 32);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(52, 30, 12, 12);
        g.fillOval(78, 30, 12, 12);
        g.fillOval(58, 46, 9, 11);
        g.fillOval(74, 46, 9, 11);
        g.fillOval(66, 58, 8, 6);
        g.fillOval(40, 88, 12, 10);
        g.fillOval(76, 88, 12, 10);
    }

    private void drawMan(java.awt.Graphics2D g, java.awt.Color cloth) {
        g.setColor(new java.awt.Color(0xE0AC69));
        g.fillOval(56, 26, 18, 18);
        g.setColor(cloth);
        g.fillRect(52, 44, 26, 34);
        g.fillRect(52, 78, 10, 26);
        g.fillRect(68, 78, 10, 26);
        g.fillRect(38, 46, 12, 26);
        g.fillRect(80, 46, 12, 26);
        g.setColor(java.awt.Color.BLACK);
        g.fillOval(60, 32, 3, 3);
        g.fillOval(68, 32, 3, 3);
    }

    private void drawLetterTile(java.awt.Graphics2D g, String name) {
        g.setColor(new java.awt.Color(0x546E7A));
        g.fillOval(34, 40, 60, 56);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 40));
        String letter = (name == null || name.isEmpty()) ? "?"
                : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.drawString(letter, 64 - fm.stringWidth(letter) / 2, 80);
    }

    /**
     * Удар по рамке пазла (EntityDamageByEntity / HangingBreakByEntity).
     * Лишняя рамка снимается; удар по «нужной» — ошибка.
     * @return true — сущность наша, событие надо отменить
     */
    public boolean onPuzzleFrameHit(Player player, org.bukkit.entity.Entity ent) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.puzzleFrames == null
                || getCurrentStage(uuid) != Stage.PUZZLE) {
            return false;
        }
        Boolean extra = st.puzzleFrames.get(ent.getUniqueId());
        if (extra == null) {
            return true;
        }
        if (extra) {
            st.puzzleFrames.remove(ent.getUniqueId());
            try {
                ent.remove();
            } catch (Throwable ignored) {
            }
            st.puzzleExtraLeft--;
            if (st.puzzleExtraLeft <= 0) {
                if (puzzleAutoPass) {
                    sendMessage(player, "antibot_puzzle_done", 0);
                    passStage(player);
                } else {
                    MessageService ms = messages();
                    Map<String, String> ph = new HashMap<>();
                    ph.put("word", puzzleConfirmWord);
                    String hint = ms == null ? null : ms.message("antibot_puzzle_finish_hint", ph);
                    if (hint != null && !hint.isEmpty()) {
                        player.sendMessage(hint);
                    }
                }
            } else {
                sendMessage(player, "antibot_puzzle_removed", st.puzzleExtraLeft);
            }
        } else {
            st.puzzleWrong++;
            if (st.puzzleWrong >= puzzleMaxWrong) {
                failCheck(uuid, msg("antibot_failed_kick"));
            } else {
                sendMessage(player, "antibot_puzzle_wrong", st.puzzleExtraLeft);
            }
        }
        return true;
    }

    /** Снять все рамки пазла (смена этапа / выход / отмена). */
    private void removePuzzleFrames(CheckState st) {
        if (st == null || st.puzzleFrames == null) {
            return;
        }
        for (java.util.UUID id : st.puzzleFrames.keySet()) {
            try {
                org.bukkit.entity.Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    e.remove();
                }
            } catch (Throwable ignored) {
            }
        }
        st.puzzleFrames.clear();
    }

    /**
     * Точка безопасного ожидания для неавторизованных: платформа лобби
     * (паркур/PvP-зона уже есть) или мини-площадка, если лобби не строится.
     * Чтобы после рестарта игрок НЕ стоял в обычном мире с вещами.
     */
    public Location holdingSpot() {
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            w = getOrCreateWorld();
        }
        if (w == null) {
            return null;
        }
        if (queueBuildLobby) {
            ensureLobby(w);
        } else {
            buildHoldingPad(w);
        }
        return lobbySpawn(w);
    }

    /** Мини-площадка 5x5 из бедрока, когда лобби-платформа выключена. */
    private void buildHoldingPad(World w) {
        int y = lobbyBaseY(w);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                w.getBlockAt(dx, y, LOBBY_Z + dz).setType(Material.BEDROCK, false);
            }
        }
    }

    // ---------- MATH: пример на экране/в чате ----------

    private void startMath(Player player, CheckState st) {
        int a = 1 + random.nextInt(mathMax);
        int b = 1 + random.nextInt(mathMax);
        String expr;
        if (random.nextBoolean()) {
            st.mathAnswer = String.valueOf(a + b);
            expr = a + " + " + b;
        } else {
            st.mathAnswer = String.valueOf(a - b);
            expr = a + " - " + b;
        }
        st.mathExpr = expr;
        sendMathPrompt(player, st);
    }

    private void sendMathPrompt(Player player, CheckState st) {
        MessageService ms = messages();
        Map<String, String> ph = new HashMap<>();
        ph.put("expr", st.mathExpr == null ? "?" : st.mathExpr);
        String text = ms == null ? null : ms.message("antibot_stage_math", ph);
        if (text == null || text.isEmpty()) {
            text = "&eРеши пример и напиши ответ в чат: &f" + st.mathExpr;
        }
        if (!"bossbar".equalsIgnoreCase(mathWhere)) {
            player.sendMessage(text);
        }
        if (!"chat".equalsIgnoreCase(mathWhere) && st.bar != null) {
            try {
                st.bar.setTitle(toBarText(text));
            } catch (Throwable ignored) {
            }
        }
        st.promptShownAt = System.currentTimeMillis();
    }

    /** Ответ на пример. @return 0 — верно, 1 — неверно, 2 — попытки исчерпаны */
    public int submitMath(Player player, String input) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.mathAnswer == null) {
            return 0;
        }
        if (tooFast(st)) {
            failCheck(uuid, msg("antibot_failed_kick"));
            return 2;
        }
        if (st.mathAnswer.equals(input == null ? "" : input.trim())) {
            st.mathAnswer = null;
            passStage(player);
            return 0;
        }
        int attempts = ++st.mathAttempts;
        if (attempts >= maxAttempts) {
            return 2;
        }
        startMath(player, st);
        return 1;
    }

    // ---------- SECRET: тест на чит-команды (.bind / #help / ...) ----------

    /**
     * Чит-клиенты перехватывают команды вида .help/#bind и НЕ шлют их в чат.
     * Живой клиент отправляет строку как обычное сообщение — мы её и ждём.
     */
    private void nextSecret(Player player, CheckState st) {
        st.secretExpected = pickSecret();
        MessageService ms = messages();
        Map<String, String> ph = new HashMap<>();
        ph.put("command", st.secretExpected);
        ph.put("num", String.valueOf(st.secretDone + 1));
        ph.put("total", String.valueOf(secretCount));
        String text = ms == null ? null : ms.message("antibot_stage_secret", ph);
        player.sendMessage(text == null || text.isEmpty()
                ? "&eНапиши в чат точно: &f" + st.secretExpected : text);
        st.promptShownAt = System.currentTimeMillis();
    }

    private String pickSecret() {
        switch (secretMode) {
            case "custom":
                if (secretCustom != null && !secretCustom.isEmpty()) {
                    return secretCustom.get(random.nextInt(secretCustom.size()));
                }
                return ".bind";
            case "random": {
                StringBuilder sb = new StringBuilder(random.nextBoolean() ? "." : "#");
                for (int i = 0; i < 4; i++) {
                    sb.append((char) ('a' + random.nextInt(26)));
                }
                return sb.toString();
            }
            default: {
                String[] pool = {".bind", ".help", ".bro", "#bro", "#bind", "#help"};
                return pool[random.nextInt(pool.length)];
            }
        }
    }

    /** Ответ на секрет-тест. @return 0 — верно/ещё идёт, 1 — ждём дальше */
    public int submitSecret(Player player, String input) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.secretExpected == null) {
            return 0;
        }
        if (tooFast(st)) {
            failCheck(uuid, msg("antibot_failed_kick"));
            return 1;
        }
        if (!st.secretExpected.equals(input == null ? "" : input.trim())) {
            return 1;
        }
        st.secretExpected = null;
        st.secretDone++;
        if (st.secretDone >= secretCount) {
            passStage(player);
        } else {
            nextSecret(player, st);
        }
        return 0;
    }

    /** Ждёт ли текущий этап ответа в чате (CAPTCHA/MATH/SECRET/PUZZLE). */
    public boolean expectsChat(UUID uuid) {
        Stage s = getCurrentStage(uuid);
        return s == Stage.CAPTCHA || s == Stage.MATH || s == Stage.SECRET || s == Stage.PUZZLE;
    }

    /**
     * Единая точка ответов из чата.
     * @return 0 — верно/проигнорировано, 1 — неверно, 2 — попытки исчерпаны
     */
    public int onCheckChat(Player player, String input) {
        // AsyncPlayerChatEvent приходит с асинхронного потока — все ветки
        // ниже трогают Bukkit (этапы, арена, инвентарь, кик). Переносим
        // обработку на главный поток; результат применяется там же.
        if (!Scheduler.isPrimaryThread()) {
            Scheduler.runAtEntity(plugin, player,
                    () -> applyChatResult(player, onCheckChat(player, input)));
            return 0;
        }
        UUID uuid = player.getUniqueId();
        Stage s = getCurrentStage(uuid);
        if (s == null) {
            return 0;
        }
        switch (s) {
            case CAPTCHA:
                return submitCode(player, input);
            case MATH:
                return submitMath(player, input);
            case SECRET:
                return submitSecret(player, input);
            case PUZZLE:
                return submitPuzzleConfirm(player, input) ? 0 : 1;
            default:
                return 0;
        }
    }

    /**
     * Побочные эффекты ответа на проверку, применяемые на главном потоке:
     * 1 — неверный ответ (сообщение), 2 — исчерпаны попытки (фейл).
     */
    private void applyChatResult(Player player, int res) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (res == 1) {
            MessageService ms = messages();
            if (ms != null) {
                ms.sendOrDefault(player, "antibot_wrong_code",
                        "{prefix}&#FF6666Неверно. Смотри задание выше / попробуй ещё раз.");
            }
        } else if (res == 2) {
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
        }
    }

    // ---------- завершение ----------

    public void finishCheck(Player player) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.remove(uuid);
        if (fallPackets != null) {
            fallPackets.stop(uuid);
        }
        removeBar(st);
        // Очередь ВХОДА на сервер: проверка пройдена, но слотов нет —
        // игрок ждёт на платформе лобби, инвентарь пока не возвращаем
        // (он ещё не авторизован — ограничения должны оставаться).
        if (entryEnabled && !entryHasCapacity() && player.isOnline()) {
            enqueueEntry(player, uuid, st);
            processQueue();
            return;
        }
        releasePlayer(player, st);
        bootPassed.add(uuid);
        processQueue();
        if (checks.isEmpty()) {
            stopTicker();
        }
    }

    /** Финальный выпуск игрока: инвентарь назад + телепорт/трансфер. */
    private void releasePlayer(Player player, CheckState st) {
        if (!packOk()) {
            plugin.getLogger().warning("[VTRegister] build: release blocked");
            return;
        }

        UUID uuid = player.getUniqueId();
        Location back = returnLocations.remove(uuid);
        removePuzzlePicture(st);
        restoreArena(st);
        restorePlayer(player, st);
        stripLobbyLoot(player);
        // Прокси: если задан целевой сервер — отправляем туда через
        // Velocity/BungeeCord Connect, иначе обычный возврат на точку входа
        if (entryTargetServer != null && !entryTargetServer.isEmpty() && player.isOnline()) {
            teleportService.transferToServer(player, entryTargetServer);
        } else if (back != null && back.getWorld() != null && player.isOnline()) {
            teleportService.authorizeTeleport(uuid);
            Scheduler.runAtEntity(plugin, player, () -> player.teleport(back));
        }
        if (plugin instanceof RegisterPlugin) {
            ((RegisterPlugin) plugin).onAntiBotPassed(player);
        }
    }

    // ---------- очередь входа на сервер (после проверки) ----------

    /** Свободен ли слот для выпуска: max_online или (слоты сервера − резерв). */
    private boolean entryHasCapacity() {
        int cap = entryMaxOnline > 0 ? entryMaxOnline
                : Math.max(1, Bukkit.getMaxPlayers() - entryReserveSlots);
        // Ждущие в entry-очереди слот НЕ занимают — считаем только выпущенных
        int released = Bukkit.getOnlinePlayers().size() - entryQueue.size();
        return released < cap;
    }

    private void enqueueEntry(Player player, UUID uuid, CheckState st) {
        QueueEntry qe = new QueueEntry();
        qe.joinMillis = System.currentTimeMillis();
        qe.lobbyReturn = returnLocations.remove(uuid);
        entryInfo.put(uuid, qe);
        if (st != null) {
            entryStates.put(uuid, st);
        }
        entryQueue.offer(uuid);
        ensureTicker();
        sendMessage(player, "antibot_entry_queue", entryPosition(uuid));
        if (entryBossbar && bossbarEnabled) {
            qe.bar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.BLUE,
                    org.bukkit.boss.BarStyle.SOLID);
            qe.bar.addPlayer(player);
        }
        // Ждут на той же лобби-платформе — паркур и PvP-зона скрашивают ожидание
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w != null) {
            ensureLobby(w);
            final World fw = w;
            teleportService.authorizeTeleport(uuid);
            Scheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline() && entryQueue.contains(uuid)) {
                    player.teleport(lobbySpawn(fw));
                    if (!"always".equals(authDarkness)) {
                        Compat.clearAuthDarkness(player);
                    }
                    feedForLobby(player);
                    if (queueFlight) {
                        player.setAllowFlight(true);
                        player.setFlying(true);
                    }
                }
            });
        }
    }

    /** Позиция в очереди входа (1 = следующий на выпуск). */
    public int entryPosition(UUID uuid) {
        int pos = 0;
        for (UUID u : entryQueue) {
            pos++;
            if (u.equals(uuid)) {
                return pos;
            }
        }
        return -1;
    }

    public boolean isEntryQueued(UUID uuid) {
        return entryQueue.contains(uuid);
    }

    private void dequeueEntry(UUID uuid) {
        entryQueue.remove(uuid);
        entryStates.remove(uuid);
        QueueEntry qe = entryInfo.remove(uuid);
        if (qe != null) {
            removeBar(qe.bar);
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && queueFlight) {
            try {
                p.setAllowFlight(false);
                p.setFlying(false);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Тик entry-очереди: боссбар с позицией, напоминания, выпуск волнами
     * по release_batch человек каждые release_seconds — пока есть слоты.
     */
    private void tickEntry(long now) {
        if (entryQueue.isEmpty()) {
            return;
        }
        int pos = 0;
        for (UUID u : new ArrayList<>(entryQueue)) {
            pos++;
            Player p = Bukkit.getPlayer(u);
            QueueEntry qe = entryInfo.get(u);
            if (p == null || !p.isOnline() || qe == null) {
                dequeueEntry(u);
                continue;
            }
            if (qe.bar != null) {
                qe.bar.setTitle(toBarText("&bВход на сервер: &f" + pos + "/" + entryQueue.size()
                        + "  &7·  ~" + (pos * entryReleaseSeconds / Math.max(1, entryReleaseBatch)) + "s"
                        + "  &7·  &aпроверка пройдена"));
                qe.bar.setProgress(Math.max(0.02, 1.0 - (pos - 1.0) / Math.max(1, entryQueue.size())));
            }
            if (now - qe.lastSpamAt >= entrySpamSeconds * 1000L) {
                qe.lastSpamAt = now;
                sendMessage(p, "antibot_entry_queue", pos);
            }
        }
        // Выпуск волнами: release_batch за раз, пауза release_seconds
        if (now < nextReleaseAt || !entryHasCapacity()) {
            return;
        }
        int released = 0;
        while (released < entryReleaseBatch && entryHasCapacity()) {
            UUID next = entryQueue.poll();
            if (next == null) {
                break;
            }
            QueueEntry qe = entryInfo.remove(next);
            if (qe != null) {
                removeBar(qe.bar);
                if (qe.lobbyReturn != null) {
                    returnLocations.putIfAbsent(next, qe.lobbyReturn);
                }
            }
            Player p = Bukkit.getPlayer(next);
            CheckState st = entryStates.remove(next);
            if (p == null || !p.isOnline()) {
                continue;
            }
            released++;
            releasePlayer(p, st);
            sendMessage(p, "antibot_entry_released", 0);
        }
        nextReleaseAt = now + entryReleaseSeconds * 1000L;
    }

    public void failCheck(UUID uuid, String reason) {
        // Тоже может прийти с асинхронного чата — restoreArena/kick только main
        if (!Scheduler.isPrimaryThread()) {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl != null) {
                Scheduler.runAtEntity(plugin, pl, () -> failCheck(uuid, reason));
            } else {
                Scheduler.runSync(plugin, () -> failCheck(uuid, reason));
            }
            return;
        }
        CheckState st = checks.remove(uuid);
        if (fallPackets != null) {
            fallPackets.stop(uuid);
        }
        dequeue(uuid);
        returnLocations.remove(uuid);
        removeBar(st);
        removePuzzlePicture(st);
        restoreArena(st);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && p.isOnline()) {
            restorePlayer(p, st);
            p.kickPlayer(reason == null ? "" : reason);
        }
        if (plugin instanceof RegisterPlugin) {
            ((RegisterPlugin) plugin).onAntiBotFailed(uuid);
        }
        processQueue();
        if (checks.isEmpty() && queue.isEmpty() && entryQueue.isEmpty()) {
            stopTicker();
        }
    }

    public void cancelCheck(UUID uuid, boolean teleportBack) {
        CheckState st = checks.remove(uuid);
        if (fallPackets != null) {
            fallPackets.stop(uuid);
        }
        restoreArena(st);
        // Игрок мог быть в очереди входа — его инвентарь лежит в entryStates
        if (st == null) {
            st = entryStates.get(uuid);
        }
        QueueEntry qe = queueInfo.get(uuid);
        Location back = returnLocations.remove(uuid);
        if (back == null && qe != null) {
            back = qe.lobbyReturn;
        }
        if (back == null) {
            QueueEntry eq = entryInfo.get(uuid);
            if (eq != null) {
                back = eq.lobbyReturn;
            }
        }
        dequeue(uuid);
        dequeueEntry(uuid);
        queueWarmupDone.remove(uuid);
        removeBar(st);
        removePuzzlePicture(st);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            restorePlayer(p, st);
            if (teleportBack && back != null && back.getWorld() != null && p.isOnline()) {
                final Location fb = back;
                teleportService.authorizeTeleport(uuid);
                Scheduler.runAtEntity(plugin, p, () -> p.teleport(fb));
            }
        }
        if (checks.isEmpty() && queue.isEmpty() && entryQueue.isEmpty()) {
            stopTicker();
        }
    }

    public void cancelCheck(UUID uuid) {
        cancelCheck(uuid, false);
    }

    private void processQueue() {
        // Волновой выпуск: за одну волну не больше batch_size игроков,
        // следующая волна — через batch_delay_seconds. Плавная нагрузка:
        // арены строятся и заполняются порциями, а не всем скопом.
        if (System.currentTimeMillis() < nextBatchAt) {
            return;
        }
        int promoted = 0;
        while (checks.size() < maxConcurrent && !queue.isEmpty() && promoted < batchSize) {
            UUID next = queue.peek();
            if (next == null) {
                break;
            }
            // lobby-first прогрев: голова очереди ещё греется — ждём,
            // очередь FIFO, обходить голову нельзя
            QueueEntry headInfo = queueInfo.get(next);
            if (headInfo != null && System.currentTimeMillis() < headInfo.notBefore) {
                break;
            }
            Player p = Bukkit.getPlayer(next);
            if (p == null || !p.isOnline()) {
                dequeue(next);
                continue;
            }
            queueWarmupDone.add(next);
            // Игрок выходит из очереди в проверку
            QueueEntry qe = queueInfo.get(next);
            if (qe != null && qe.lobbyReturn != null) {
                returnLocations.putIfAbsent(next, qe.lobbyReturn);
            }
            queue.poll();
            QueueEntry rem = queueInfo.remove(next);
            if (rem != null) {
                removeBar(rem.bar);
            }
            promoted++;
            beginCheck(p);
            // Видимость в лобби обновилась — пересчитать
            syncQueueVisibility();
        }
        if (promoted > 0 && !queue.isEmpty()) {
            nextBatchAt = System.currentTimeMillis() + batchDelaySeconds * 1000L;
        }
    }

    // ---------- состояние ----------

    public boolean isChecking(UUID uuid) {
        return checks.containsKey(uuid);
    }

    public boolean isQueued(UUID uuid) {
        return queue.contains(uuid);
    }

    /** Игрок занят антиботом: в очереди, на проверке ИЛИ ждёт входа на сервер. */
    public boolean isBusy(UUID uuid) {
        return checks.containsKey(uuid) || queue.contains(uuid) || entryQueue.contains(uuid);
    }

    public boolean queueChatAllowed() {
        return queueChatAllowed;
    }

    /** Игрок ждёт в очереди-лобби (режим 2). */
    public boolean isInQueueLobby(UUID uuid) {
        return queueMode == 2 && (queue.contains(uuid) || entryQueue.contains(uuid));
    }

    /**
     * Движение игрока в очереди-лобби: гулять по платформе и паркуру можно,
     * но за пределы не выпускаем — при провале возвращаем на спавн лобби.
     * @return true — событие обработано (игрок в лобби).
     */
    public boolean onQueueMove(Player player, Location from, Location to) {
        UUID uuid = player.getUniqueId();
        // Движение по лобби-платформе разрешено и ждущим проверки,
        // и ждущим входа на сервер (entry-очередь)
        if (queueMode != 2 || to == null || (!queue.contains(uuid) && !entryQueue.contains(uuid))) {
            return false;
        }
        int base = lobbyBaseY(player.getWorld());
        if (to.getY() < base - 4) {
            // провалился с паркура — мгновенный возврат, без урона и смерти
            to.setX(0.5);
            to.setZ(LOBBY_Z + 0.5);
            to.setY(base + 1);
            player.setFallDistance(0f);
        }
        return true;
    }

    public int queuePosition(UUID uuid) {
        int pos = 0;
        for (UUID u : queue) {
            pos++;
            if (u.equals(uuid)) {
                return pos;
            }
        }
        return -1;
    }

    public Stage getCurrentStage(UUID uuid) {
        CheckState st = checks.get(uuid);
        if (st == null || st.stageIndex < 0) {
            return null;
        }
        List<Stage> order = st.stages != null ? st.stages : stageOrder;
        if (st.stageIndex >= order.size()) {
            return null;
        }
        return order.get(st.stageIndex);
    }

    /** Bedrock-игрок? (через Floodgate/Geyser, если установлены). */
    private boolean isBedrock(Player player) {
        // Мастер-выключатель bedrock.enabled: false = Floodgate/Geyser
        // игроки проходят проверку как обычные Java-клиенты
        if (!bedrockEnabled) {
            return false;
        }
        try {
            if (plugin instanceof RegisterPlugin) {
                BedrockSupportService bs = ((RegisterPlugin) plugin).getBedrockSupportService();
                return bs != null && bs.isBedrockPlayer(player);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Порядок этапов для конкретного игрока. Для Bedrock убираем SLOTS —
     * на Geyser серверная смена хотбар-слота ненадёжна и давала бы
     * ложные кики. Остальные этапы (падение, камера, капча, клик) работают.
     */
    private List<Stage> stagesFor(boolean bedrock) {
        if (!bedrock || !bedrockSkipSlots) {
            return stageOrder;
        }
        List<Stage> filtered = new ArrayList<>(stageOrder);
        filtered.remove(Stage.SLOTS);
        return Collections.unmodifiableList(filtered);
    }

    public int activeChecks() {
        return checks.size();
    }

    // ---------- тикер (таймауты этапов) ----------

    private void ensureTicker() {
        if (ticker != null) {
            return;
        }
        ticker = Scheduler.runSyncTimer(plugin, this::tick, 20L, tickTicks);
    }

    /** Полная остановка при выключении плагина: все проверки отменены,
     *  очереди расформированы, бары сняты, состояние игроков возвращено. */
    public void shutdown() {
        for (UUID u : new ArrayList<>(checks.keySet())) {
            cancelCheck(u, true);
        }
        for (UUID u : new ArrayList<>(queue)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                leaveQueues(p);
            } else {
                dequeue(u);
            }
        }
        for (UUID u : new ArrayList<>(entryQueue)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                leaveQueues(p);
            } else {
                dequeueEntry(u);
            }
        }
        queueInfo.clear();
        entryInfo.clear();
        entryStates.clear();
        returnLocations.clear();
        if (fallPackets != null) {
            fallPackets.stopAll();
            fallPackets = null;
        }
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        stopTicker();
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (checks.isEmpty() && queue.isEmpty() && entryQueue.isEmpty()) {
            stopTicker();
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, CheckState>> it = checks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CheckState> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                returnLocations.remove(e.getKey());
                continue;
            }
            CheckState st = e.getValue();
            // Подготовка к проверке: игрок уже на арене, идёт отсчёт.
            // Каждую секунду — тайтл + чат «готовься, сейчас проверка».
            if (st.preparing) {
                long leftMs = st.prepareUntil - now;
                if (leftMs <= 0) {
                    st.preparing = false;
                    st.stageDeadline = 0;
                    if (st.preparingInLobby) {
                        // Отсчёт прошёл в лобби — переносим на арену
                        // и прячем инвентарь прямо перед первым этапом.
                        st.preparingInLobby = false;
                        preparePlayer(p, st);
                        teleportService.authorizeTeleport(p.getUniqueId());
                        final CheckState fst = st;
                        Scheduler.runAtEntity(plugin, p, () -> {
                            if (p.isOnline() && checks.containsKey(p.getUniqueId())) {
                                p.teleport(arenaSpawn(fst));
                                p.setFallDistance(0f);
                            }
                        });
                    }
                    advanceStage(p);
                    continue;
                }
                int sec = (int) Math.ceil(leftMs / 1000.0);
                if (sec != st.lastPrepareSec) {
                    st.lastPrepareSec = sec;
                    sendMessage(p, "antibot_prepare", sec);
                    try {
                        p.sendTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&eГотовься!"),
                                org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                        "&fПроверка через &a" + sec + " &fсек"),
                                0, 25, 5);
                    } catch (Throwable ignored) {
                    }
                    if (st.bar != null) {
                        st.bar.setTitle(toBarText("&eСтарт проверки через &f" + sec + " &eсек"));
                        st.bar.setProgress(Math.max(0.02, (double) sec / Math.max(1, prepareSeconds)));
                    }
                }
                continue;
            }
            if (st.stageDeadline > 0 && now > st.stageDeadline) {
                failCheck(e.getKey(), msg("antibot_timeout"));
                continue;
            }
            // Летает БЕЗ разрешения сервера — чит. getAllowFlight()=true
            // означает, что полёт выдан другим плагином/правом — не караем.
            // Три тика подряд: одиночный флаг isFlying не кикает.
            if (kickFlyers && p.isFlying() && !p.getAllowFlight()) {
                if (++st.flyTicks >= 3) {
                    failCheck(e.getKey(), msg("antibot_fly_kick"));
                    continue;
                }
            } else {
                st.flyTicks = 0;
            }
            // Фоновая проверка пакетов: бюджет packet_max_per_tick × окно
            // тиков — человек столько не шлёт, бот-поток выходит за лимит
            if (packetCheck) {
                if (++st.packetTick >= packetWindowTicks) {
                    if (st.movePackets > (long) packetMaxPerTick * packetWindowTicks) {
                        failCheck(e.getKey(), msg("antibot_failed_kick"));
                        continue;
                    }
                    st.movePackets = 0;
                    st.packetTick = 0;
                }
            } else {
                st.movePackets = 0;
            }
            // SLOTS: принудительная смена слота послана, а ответа нет дольше
            // slots_response_ms — клиент игнорирует серверный forced-слот
            // (чит-блокировка слотов). Перефорсируем до лимита попыток, дальше кик.
            if (getCurrentStage(e.getKey()) == Stage.SLOTS
                    && st.awaitingForceFollowUp
                    && now - st.forcedAt > slotsResponseMs) {
                if (st.forceAttempts >= slotMaxAttempts) {
                    failCheck(e.getKey(), msg("antibot_failed_kick"));
                } else {
                    forceSlotCheck(p, st);
                }
                continue;
            }
            // SLOTS ждёт ответов на рывки камеры
            if (st.slotsPendingJolts) {
                if (st.joltAcks >= slotCameraMinAcks) {
                    st.slotsPendingJolts = false;
                    passStage(p);
                    continue;
                }
                if (now > st.joltDeadline) {
                    failCheck(e.getKey(), msg("antibot_failed_kick"));
                    continue;
                }
            }

            // BLOCK-watchdog: сундук с инструментом сломан/пуст ->
            // переставляем и кладём инструмент обратно (иначе этап мёртв).
            if (blockToolChest && getCurrentStage(e.getKey()) == Stage.BLOCK
                    && now - st.toolChestCheckAt > 2000L) {
                st.toolChestCheckAt = now;
                ensureToolChest(st);
            }
            // CLICK: пересылаем кликабельную кнопку каждые 8 сек —
            // сообщение тонет в чате, игрок теряет куда нажимать
            if (getCurrentStage(e.getKey()) == Stage.CLICK
                    && now - st.clickPromptAt > 8000L) {
                sendClickPrompt(p, st);
            }
            // PUZZLE-watchdog: dead frames -> GUI fallback; closed GUI -> reopen
            // GUI не открылся/закрыт -> переоткрываем (unclosable)
            if (getCurrentStage(p.getUniqueId()) == Stage.PUZZLE
                    && now - st.puzzleFrameCheckAt > 2000L) {
                st.puzzleFrameCheckAt = now;
                if (st.puzzleFrames != null && !st.puzzleFrames.isEmpty()) {
                    boolean anyAlive = false;
                    for (java.util.UUID fid : st.puzzleFrames.keySet()) {
                        if (Bukkit.getEntity(fid) != null) { anyAlive = true; break; }
                    }
                    if (!anyAlive) {
                        st.puzzleFrames = null;
                        openPuzzleGui(p, st);
                    }
                }
                if (puzzleUnclosable && st.puzzleInv != null && !st.puzzleAwaitConfirm
                        && p.getOpenInventory().getTopInventory() != st.puzzleInv) {
                    p.openInventory(st.puzzleInv);
                }
                // Watchdog стены пазла: игрок/взрыв/чит мог убрать стену
                // или табличку — переставляем, иначе задание не видно.
                if (st.world != null && st.puzzleFrames != null) {
                    World w = st.world;
                    int wallZ = st.arenaZ + (puzzleFront ? -4 : 4);
                    int signX = puzzleFront ? st.arenaX - 2 : st.arenaX;
                    int signZ = st.arenaZ - 3;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = 1; dy <= 3; dy++) {
                            org.bukkit.block.Block wb = w.getBlockAt(
                                    st.arenaX + dx, st.baseY + dy, wallZ);
                            if (wb.getType() != Material.QUARTZ_BLOCK) {
                                wb.setType(Material.QUARTZ_BLOCK, false);
                            }
                        }
                    }
                    org.bukkit.block.Block sb = w.getBlockAt(
                            signX, st.baseY + 2, signZ);
                    if (!(sb.getState() instanceof org.bukkit.block.Sign)
                            || ((org.bukkit.block.Sign) sb.getState()).getLine(0).isEmpty()) {
                        if (!puzzleFront) {
                            w.getBlockAt(st.arenaX, st.baseY + 2, st.arenaZ - 4)
                                    .setType(Material.QUARTZ_BLOCK, false);
                        }
                        if (puzzleFront) {
                            sb.setType(Material.OAK_SIGN, false);
                        } else {
                            sb.setType(Material.OAK_WALL_SIGN, false);
                        }
                        try {
                            if (puzzleFront) {
                                org.bukkit.block.data.Rotatable rot =
                                        (org.bukkit.block.data.Rotatable) sb.getBlockData();
                                rot.setRotation(org.bukkit.block.BlockFace.SOUTH);
                                sb.setBlockData(rot, false);
                            } else {
                                org.bukkit.block.data.Directional dir =
                                        (org.bukkit.block.data.Directional) sb.getBlockData();
                                dir.setFacing(org.bukkit.block.BlockFace.SOUTH);
                                sb.setBlockData(dir, false);
                            }
                        } catch (Throwable ignored) {
                        }
                        org.bukkit.block.Sign ps = (org.bukkit.block.Sign) sb.getState();
                        for (int li = 0; li < 4 && li < puzzleSignLines.size(); li++) {
                            ps.setLine(li, org.bukkit.ChatColor.translateAlternateColorCodes('&', puzzleSignLines.get(li)));
                        }
                        ps.update(true, false);
                    }
                }
            }
            // Обратный отсчёт в боссбаре: титл обновляем только когда сменилась
            // показанная секунда — не шлём лишние пакеты 20 раз в секунду.
            if (st.bar != null && st.stageDeadline > 0) {
                long left = Math.max(0L, (st.stageDeadline - now) / 1000L);
                if (left != st.lastBarSec) {
                    st.lastBarSec = left;
                    updateBarTitle(st, left);
                }
            }
        }

        // Лобби-watchdog: табличка/сундук/кнопка сломаны -> восстановить.
        // Снаружи per-player цикла: работает и когда проверяемых нет.
        // Сломанные структуры лобби чиним раз в ~3 секунды
        if (queueBuildLobby && lobbyBuilt && now - lastStructCheckAt > 3000L) {
            lastStructCheckAt = now;
            restoreLobbyStructures();
            verifyLobbyIntegrity(verifyWorld != null ? verifyWorld : fallbackWorld);
        }

        if (fallPackets != null) {
            fallPackets.tick();
        }
        // Очередь: позиции, боссбар, напоминания в лобби
        tickQueue(now);
        // Очередь входа на сервер: позиции + выпуск волнами
        tickEntry(now);

        if (checks.size() < maxConcurrent && !queue.isEmpty()) {
            processQueue();
        }
        // Ресинк видимости раз в секунду: игроки входят/выходят из PvP-зоны —
        // там видимость включается, снаружи — выключается обратно.
        if ((queue.size() + entryQueue.size()) > 1
                && now - lastVisibilitySync > 1000L) {
            lastVisibilitySync = now;
            syncQueueVisibility();
        }
    }

    private void tickQueue(long now) {
        int pos = 0;
        for (UUID u : new ArrayList<>(queue)) {
            pos++;
            Player p = Bukkit.getPlayer(u);
            QueueEntry qe = queueInfo.get(u);
            if (p == null || !p.isOnline() || qe == null) {
                dequeue(u);
                continue;
            }
            // Боссбар с позицией (режимы 1 и 2)
            if (qe.bar != null) {
                qe.bar.setTitle(toBarText("&eОчередь: &f" + pos + "/" + queue.size()
                        + "  &7·  ~" + (pos * batchDelaySeconds) + "s"
                        + "  &7·  &bпроверка на бота"));
                qe.bar.setProgress(Math.max(0.02, 1.0 - (pos - 1.0) / Math.max(1, queue.size())));
            }
            // Лобби-режим: спасение с паркура + напоминание в чат
            if (queueMode == 2) {
                // Полёт для ждущих (queue_flight) — восстанавливаем если слетел
                if (queueFlight && !p.getAllowFlight()) {
                    p.setAllowFlight(true);
                }
                // Летает без разрешения — читер в очереди. Полёт, выданный
                // другим плагином (allowFlight), не караем; три тика подряд.
                if (queueKickFlyers && !queueFlight && p.isFlying() && !p.getAllowFlight()) {
                    if (++qe.flyTicks >= 3) {
                        dequeue(u);
                        p.kickPlayer(msg("antibot_fly_kick"));
                        continue;
                    }
                } else {
                    qe.flyTicks = 0;
                }
                if (p.getLocation().getY() < lobbyBaseY(p.getWorld()) - 4) {
                    teleportService.authorizeTeleport(u);
                    final Player fp = p;
                    Scheduler.runAtEntity(plugin, p, () -> {
                        if (fp.isOnline() && queue.contains(u)) {
                            fp.teleport(lobbySpawn(fp.getWorld()));
                            fp.setFallDistance(0f);
                        }
                    });
                }
                if (now - qe.lastSpamAt >= queueSpamSeconds * 1000L) {
                    qe.lastSpamAt = now;
                    sendMessage(p, "antibot_queue_wait", pos);
                }
            }
        }
        updateHologram();
        // PvP-сундук пополняется по таймеру
        if (queueMode == 2 && pvpEnabled && now - lastChestRefill >= pvpChestSeconds * 1000L) {
            lastChestRefill = now;
            refillPvpChest();
        }
        // Видимость пересчитываем только при изменении состава очереди
        if (queue.size() != lastQueueSync) {
            lastQueueSync = queue.size();
            syncQueueVisibility();
        }
    }

    public boolean isPvpEnabled() {
        return pvpEnabled && queueMode == 2;
    }

    /**
     * Полная еда и насыщение для ждущего в лобби: голод заморожен
     * (FoodLevelChangeEvent отменён), поэтому при заходе с голодом < 7
     * спринт в лобби просто не работал — чиним однократной подкормкой.
     */
    private void feedForLobby(Player p) {
        try {
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setExhaustion(0f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Клик по кнопке скорости в PvP-зоне лобби.
     * Returns true — блок является кнопкой (событие обработано).
     */
    public boolean onSpeedButton(Player p, org.bukkit.block.Block clicked) {
        if (!speedButtonEnabled || clicked == null
                || !clicked.getType().name().endsWith("_BUTTON")) {
            return false;
        }
        // Точная привязка: если постамент кнопки известен — принимаем только
        // её. Иначе любая кнопка в зоне лобби мира проверки (блок могли
        // сдвинуть/переставить).
        if (!isCheckWorld(clicked.getWorld())) {
            return false;
        }
        if (speedButtonLoc != null && speedButtonLoc.getWorld() == clicked.getWorld()
                && speedButtonLoc.distanceSquared(clicked.getLocation()) > 4.0) {
            return false;
        }
        int lbY = lobbyBaseY(clicked.getWorld());
        if (Math.abs(clicked.getY() - lbY) > 6 || Math.abs(clicked.getX()) > 80
                || Math.abs(clicked.getZ() - LOBBY_Z) > 120) {
            return false;
        }
        // Работает для всех в мире лобби/проверки: и для ждущих в очереди,
        // и для залогиненного админа, зашедшего проверить.
        if (!isInQueueLobby(p.getUniqueId()) && !isCheckWorld(clicked.getWorld())) {
            return false;
        }
        // Анти-спам: не чаще раза в 3 секунды (и от кликеров-читеров)
        Long lastPress = speedCooldown.get(p.getUniqueId());
        if (lastPress != null && System.currentTimeMillis() - lastPress < 3000L) {
            return true;
        }
        speedCooldown.put(p.getUniqueId(), System.currentTimeMillis());
        try {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SPEED,
                    speedButtonSeconds * 20, speedButtonLevel - 1, false, true, true));
        } catch (Throwable t) {
            plugin.getLogger().warning("[VTRegister] speed button: " + t);
        }
        sendMessage(p, "antibot_speed_button", speedButtonSeconds);
        return true;
    }

    /**
     * Взаимодействие с блоком в очереди-лобби: разрешены только наши —
     * кнопка скорости и сундук PvP-зоны. Всё остальное остаётся запрещённым.
     */
    public boolean onLobbyInteract(Player p, org.bukkit.block.Block b) {
        if (b == null || !isInQueueLobby(p.getUniqueId())) {
            return false;
        }
        if (onSpeedButton(p, b)) {
            return true;
        }
        return pvpEnabled && b.getType() == Material.CHEST && isPvpArea(b.getLocation());
    }

    /** OP или registerplugin.admin может строить/ломать в лобби (настраиваемо). */
    public boolean canModifyCheckWorld(Player p) {
        return adminLobbyModify
                && (p.isOp() || p.hasPermission("registerplugin.admin"));
    }

    /** Сундук-набор, кнопка скорости и таблички лобби — не ломаются никем. */
    public boolean isFunctionalLobbyBlock(org.bukkit.block.Block b) {
        if (b == null || !isCheckWorld(b.getWorld())) {
            return false;
        }
        Material t = b.getType();
        if (t == Material.STONE_BUTTON) {
            return true;
        }
        if (t == Material.CHEST && isKitChest(b)) {
            return true;
        }
        if (t.name().endsWith("_SIGN")) {
            int y = lobbyBaseY(b.getWorld());
            return Math.abs(b.getX()) < 60 && Math.abs(b.getZ() - LOBBY_Z) < 90
                    && Math.abs(b.getY() - y) < 8;
        }
        return false;
    }

    /** Можно ли сломать блок в мире проверки: функциональные — никому. */
    public boolean canBreakInCheckWorld(Player p, org.bukkit.block.Block b) {
        if (protectFunctional && isFunctionalLobbyBlock(b)) {
            return false;
        }
        return canModifyCheckWorld(p);
    }

    public boolean allowLobbyTp() {
        return adminLobbyTp;
    }

    /** Точка спавна лобби-очереди (для респавна после PvP-смерти). */
    public Location lobbySpawnLocation() {
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            return null;
        }
        return new Location(w, 0.5, lobbyBaseY(w) + 1, LOBBY_Z + 0.5);
    }

    /** Телепорт админа в лобби-очередь (/authadmin lobby). */

    public void teleportToLobby(Player p) {
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null || p == null) {
            return;
        }
        int y = lobbyBaseY(w);
        if (teleportService != null) {
            teleportService.authorizeTeleport(p.getUniqueId());
        }
        p.teleport(new Location(w, 0.5, y + 1, LOBBY_Z + 0.5));
    }

    /** Мир проверки/лобби — приватная зона, ломать нельзя никому без прав. */

    public boolean isCheckWorld(World w) {
        if (w == null) {
            return false;
        }
        World vw = verifyWorld != null ? verifyWorld : fallbackWorld;
        return vw != null && vw.equals(w);
    }

    /**
     * Пометить поднятый предмет как «лут лобби/проверки» — такие вещи
     * вычищаются при старте проверки, выпуске в мир и выходе с сервера.
     */
    public void tagLobbyItem(org.bukkit.entity.Item itemEnt) {
        try {
            org.bukkit.inventory.ItemStack it = itemEnt.getItemStack();
            if (it == null || it.getType() == Material.AIR) {
                return;
            }
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (m == null) {
                return;
            }
            m.getPersistentDataContainer().set(lobbyLootKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
            itemEnt.setItemStack(it);
        } catch (Throwable ignored) {
        }
    }

    /** Зона PvP в очереди-лобби (координаты из конфига). */
    public boolean isPvpArea(Location loc) {
        if (!pvpEnabled || loc == null) {
            return false;
        }
        int minX = Math.min(pvpX1, pvpX2), maxX = Math.max(pvpX1, pvpX2);
        int minZ = Math.min(pvpZ1, pvpZ2), maxZ = Math.max(pvpZ1, pvpZ2);
        return loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
    }

    /**
     * Фильтр чата очереди-лобби: анти-реклама, КД, лимит слов, анти-повтор.
     * Всё настраивается в antibot.queue_chat_filter.
     * @return null если сообщение пропускаем; иначе ключ причины
     */
    public String filterLobbyChat(Player p, String message) {
        if (!chatFilterEnabled || !isInQueueLobby(p.getUniqueId())) {
            return null;
        }
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = chatLastAt.get(uuid);
        if (last != null && now - last < chatCooldownMs) {
            return "chat_slowdown";
        }
        if (message == null) {
            return null;
        }
        String msg = message.trim();
        if (chatBlockLinks) {
            String low = msg.toLowerCase(java.util.Locale.ROOT);
            // ссылки/домены/IP — типичная реклама чужих серверов
            if (low.matches(".*(https?://|www\\..).*")
                    || low.matches(".*\\b[a-z0-9-]+\\.(ru|com|net|org|gg|io|su|me|cc|xyz|top|fun|host)\\b.*")
                    || low.matches(".*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")
                    || low.matches(".*\\bjoin\\s+\\S+\\.(\\w+).*")) {
                return "chat_ads_blocked";
            }
        }
        if (chatMaxWords > 0 && msg.split("\\s+").length > chatMaxWords) {
            return "chat_too_long";
        }
        if (chatBlockRepeat) {
            String prev = chatLastMsg.get(uuid);
            if (prev != null && prev.equalsIgnoreCase(msg)) {
                return "chat_no_repeat";
            }
        }
        chatLastAt.put(uuid, now);
        chatLastMsg.put(uuid, msg);
        return null;
    }

    /**
     * Дроп при смерти в лобби-PvP: падает ТОЛЬКО броня и еда,
     * всё остальное авто-очищается (не дропается и не выносится).
     * @return true — смерть обработана нашим правилом
     */
    public boolean filterQueueDeathDrops(org.bukkit.event.entity.PlayerDeathEvent e, Player p) {
        if (!isInQueueLobby(p.getUniqueId())) {
            return false;
        }
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        java.util.Iterator<org.bukkit.inventory.ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            org.bukkit.inventory.ItemStack item = it.next();
            if (item == null) {
                it.remove();
                continue;
            }
            Material t = item.getType();
            boolean armor = t.name().endsWith("_HELMET") || t.name().endsWith("_CHESTPLATE")
                    || t.name().endsWith("_LEGGINGS") || t.name().endsWith("_BOOTS");
            boolean food = t.isEdible();
            if (!armor && !food) {
                it.remove();
            }
        }
        return true;
    }

    /** Игрок залогинился, стоя в очереди — снять со всех очередей сразу. */
    public void leaveQueues(Player p) {
        if (p == null) {
            return;
        }
        UUID uuid = p.getUniqueId();
        QueueEntry qe = entryInfo.get(uuid);
        CheckState est = entryStates.get(uuid);
        if (queue.contains(uuid)) {
            dequeue(uuid);
        }
        if (entryQueue.contains(uuid)) {
            dequeueEntry(uuid);
        }
        if (est != null) {
            try {
                restorePlayer(p, est);
            } catch (Throwable ignored) {
            }
        }
        stripLobbyLoot(p);
        syncQueueVisibility();
        final Location back = qe != null ? qe.lobbyReturn : null;
        if (back != null && back.getWorld() != null && p.isOnline()) {
            teleportService.authorizeTeleport(uuid);
            Scheduler.runAtEntity(plugin, p, () -> {
                if (p.isOnline()) {
                    p.teleport(back);
                }
            });
        }
    }

    /**
     * Восстановить арену после проверки: платформу перестраиваем в бедрок,
     * стену пазла/табличку убираем, сломанный целевой блок возвращаем.
     * Арена переиспользуется — следующий игрок должен видеть её целой.
     */
    private void restoreArena(CheckState st) {
        if (st == null || st.world == null) {
            return;
        }
        World w = st.world;
        try {
            // платформа в исходный бедрок
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -1; dy <= 0; dy++) {
                        org.bukkit.block.Block b =
                                w.getBlockAt(st.arenaX + dx, st.baseY + dy, st.arenaZ + dz);
                        Material bt = b.getType();
                        if (bt != Material.BEDROCK && bt != Material.AIR) {
                            b.setType(dy == 0 ? Material.BEDROCK : Material.AIR, false);
                        }
                    }
                }
            }
            // стена пазла позади (arenaZ+4) и столб таблички спереди (arenaZ-3/-4)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, st.arenaZ + 4)
                            .setType(Material.AIR, false);
                }
            }
            w.getBlockAt(st.arenaX, st.baseY + 2, st.arenaZ - 4).setType(Material.AIR, false);
            w.getBlockAt(st.arenaX, st.baseY + 2, st.arenaZ - 3).setType(Material.AIR, false);
            // блоки, тронутые игроком на этапе BLOCK, снова ставим целевым блоком
            for (long[] pos : st.blocksTouched) {
                w.getBlockAt((int) pos[0], (int) pos[1], (int) pos[2])
                        .setType(Material.BEDROCK, false);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------- PvP-набор: двойной сундук, виртуальный инвентарь ----------

    /** Любой из двух блоков сундука-набора. */
    public boolean isKitChest(org.bukkit.block.Block b) {
        if (!kitEnabled || !pvpEnabled || b == null || b.getType() != Material.CHEST) {
            return false;
        }
        World w = b.getWorld();
        if (!isCheckWorld(w)) {
            return false;
        }
        int y = lobbyBaseY(w);
        if (b.getY() == y + 1 && b.getZ() == pvpChestZ
                && (b.getX() == pvpChestX || b.getX() == pvpChestX + 1)) {
            return true;
        }
        // Запасной вариант: сундук внутри PvP-арены лобби тоже открывает набор
        return isPvpArea(b.getLocation());
    }

    /**
     * Блокиратор слотов: попытка двигать вещи в инвентаре во время проверки.
     * Считаем нарушения — после slot_lock.kick_after подряд кик (0 = без кика).
     */
    public void onSlotViolation(Player p) {
        if (!slotLockEnabled || p == null) {
            return;
        }
        UUID uuid = p.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null) {
            return;
        }
        if (slotLockKickAfter > 0 && ++st.slotViolations >= slotLockKickAfter) {
            failCheck(uuid, msg("antibot_failed_kick"));
        }
    }

    /** Это виртуальный инвентарь набора? (разрешаем клики внутри) */

    public boolean isKitInv(org.bukkit.inventory.Inventory inv) {
        return inv != null && kitInvs.containsValue(inv);
    }

    /**
     * Открыть виртуальный «двойной сундук» с набором. Анти-дюп и лимиты:
     *  - набор открывается раз в kit_cooldown_seconds на ИГРОКА
     *  - реальный сундук пуст — взять лишнее неоткуда
     *  - выданные вещи PDC-тегированы → вычищаются при выходе из лобби
     */
    public boolean openKitChest(Player p) {
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = kitLastOpen.get(uuid);
        if (last != null && now - last < kitCooldownMs) {
            sendMessage(p, "antibot_kit_cooldown",
                    (int) ((kitCooldownMs - (now - last)) / 1000L) + 1);
            return true;
        }
        kitLastOpen.put(uuid, now);
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54,
                toBarText("&6PvP-набор &7· раз в " + (kitCooldownMs / 1000) + " сек"));
        int slot = 0;
        for (org.bukkit.inventory.ItemStack it : kitItems) {
            if (it == null || it.getType() == Material.AIR || slot >= 54) {
                continue;
            }
            inv.setItem(slot++, tagKitItem(it.clone()));
        }
        kitInvs.put(uuid, inv);
        p.openInventory(inv);
        sendMessage(p, "antibot_kit_open", 0);
        return true;
    }

    /** Тег ItemStack — вещи набора помечаем при выдаче. */
    private org.bukkit.inventory.ItemStack tagKitItem(org.bukkit.inventory.ItemStack it) {
        try {
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (m != null) {
                m.getPersistentDataContainer().set(lobbyLootKey,
                        PersistentDataType.BYTE, (byte) 1);
                it.setItemMeta(m);
            }
        } catch (Throwable ignored) {
        }
        return it;
    }

    public void onKitClose(Player p, org.bukkit.inventory.Inventory inv) {
        if (inv != null && kitInvs.get(p.getUniqueId()) == inv) {
            kitInvs.remove(p.getUniqueId());
        }
    }

    // ---------- редактор набора: /authadmin pvpkit ----------

    public void openKitEditor(Player p) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54,
                toBarText("&cPvP-набор: положи вещи и закрой"));
        int slot = 0;
        for (org.bukkit.inventory.ItemStack it : kitItems) {
            if (it != null && slot < 54) {
                inv.setItem(slot++, it.clone());
            }
        }
        kitEditors.put(p.getUniqueId(), inv);
        p.openInventory(inv);
    }

    /** Закрытие редактора: сохранить набор в pvpchest.yml. */
    public boolean onKitEditorClose(Player p, org.bukkit.inventory.Inventory inv) {
        if (inv == null || kitEditors.get(p.getUniqueId()) != inv) {
            return false;
        }
        kitEditors.remove(p.getUniqueId());
        kitItems.clear();
        for (org.bukkit.inventory.ItemStack it : inv.getContents()) {
            if (it != null && it.getType() != Material.AIR) {
                kitItems.add(it.clone());
            }
        }
        saveKit();
        p.sendMessage(toBarText("&aPvP-набор сохранён: "
                + kitItems.size() + " предметов"));
        return true;
    }

    private void loadKit() {
        kitItems.clear();
        try {
            java.io.File f = new java.io.File(plugin.getDataFolder(), "pvpchest.yml");
            if (f.isFile()) {
                org.bukkit.configuration.file.YamlConfiguration y =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                java.util.List<?> list = y.getList("items");
                if (list != null) {
                    for (Object o : list) {
                        if (o instanceof org.bukkit.inventory.ItemStack) {
                            kitItems.add((org.bukkit.inventory.ItemStack) o);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: набор не загружен: " + t.getMessage());
        }
        if (kitItems.isEmpty()) {
            kitItems.addAll(defaultKit());
        }
    }

    private void saveKit() {
        try {
            java.io.File f = new java.io.File(plugin.getDataFolder(), "pvpchest.yml");
            org.bukkit.configuration.file.YamlConfiguration y =
                    new org.bukkit.configuration.file.YamlConfiguration();
            y.set("items", new ArrayList<>(kitItems));
            y.save(f);
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: набор не сохранён: " + t.getMessage());
        }
    }

    /** Дефолтный набор: незерит prot4 + меч sharp1 + щит + 25 яблок. */
    private List<org.bukkit.inventory.ItemStack> defaultKit() {
        List<org.bukkit.inventory.ItemStack> k = new ArrayList<>();
        for (Material m : new Material[]{Material.NETHERITE_HELMET,
                Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS}) {
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m);
            it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, 4);
            k.add(it);
        }
        org.bukkit.inventory.ItemStack sw = new org.bukkit.inventory.ItemStack(Material.DIAMOND_SWORD);
        sw.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, 1);
        k.add(sw);
        k.add(new org.bukkit.inventory.ItemStack(Material.SHIELD));
        k.add(new org.bukkit.inventory.ItemStack(Material.GOLDEN_APPLE, 25));
        return k;
    }

    private org.bukkit.entity.ArmorStand spawnHolo(World w, Location loc, String text) {
        org.bukkit.entity.ArmorStand as = w.spawn(loc, org.bukkit.entity.ArmorStand.class);
        as.setVisible(false);
        as.setGravity(false);
        as.setCustomNameVisible(true);
        as.setMarker(true);
        as.setInvulnerable(true);
        as.setCustomName(toBarText(text));
        return as;
    }

    private void removeHoloList() {
        for (org.bukkit.entity.ArmorStand as : zoneHolos) {
            try {
                as.remove();
            } catch (Throwable ignored) {
            }
        }
        zoneHolos.clear();
    }

    /**
     * Убрать из инвентаря весь лут лобби (помечен lobbyLootKey).
     * Вызывается при старте проверки, выпуске в мир и выходе игрока —
     * аренные предметы не должны покидать лобби ни в каком виде.
     */
    public void stripLobbyLoot(Player p) {
        if (p == null) {
            return;
        }
        try {
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            org.bukkit.inventory.ItemStack[] all = inv.getContents();
            boolean dirty = false;
            for (int i = 0; i < all.length; i++) {
                if (isLobbyLoot(all[i])) {
                    all[i] = null;
                    dirty = true;
                }
            }
            if (dirty) {
                inv.setContents(all);
            }
            org.bukkit.inventory.ItemStack[] armor = inv.getArmorContents();
            dirty = false;
            for (int i = 0; i < armor.length; i++) {
                if (isLobbyLoot(armor[i])) {
                    armor[i] = null;
                    dirty = true;
                }
            }
            if (dirty) {
                inv.setArmorContents(armor);
            }
            if (isLobbyLoot(inv.getItemInOffHand())) {
                inv.setItemInOffHand(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isLobbyLoot(org.bukkit.inventory.ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        try {
            return item.getItemMeta().getPersistentDataContainer()
                    .has(lobbyLootKey, PersistentDataType.BYTE);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Смерть ждущего в очереди-лобби. Если убийца — тоже ждущий игрок,
     * он получает +gain_positions, погибший откатывается на lose_positions.
     * Вызывается из PlayerDeathEvent до keepInventory-логики.
     */
    public void onQueuePvpDeath(Player victim) {
        if (!pvpQueueSteal || queueMode != 2 || !queue.contains(victim.getUniqueId())) {
            return;
        }
        Player killer = victim.getKiller();
        // Штраф жертве применяем только если это было PvP-убийство
        // (иначе случайная смерть от мобов/падения несправедливо откатит позицию)
        if (killer == null) {
            return;
        }
        if (queue.contains(killer.getUniqueId()) && !killer.getUniqueId().equals(victim.getUniqueId())) {
            shiftInQueue(killer.getUniqueId(), -pvpGainPositions);
            sendMessage(killer, "antibot_queue_gain", pvpGainPositions);
        }
        shiftInQueue(victim.getUniqueId(), pvpLosePositions);
        sendMessage(victim, "antibot_queue_lose", pvpLosePositions);
    }

    /**
     * Сдвигает игрока в очереди: delta < 0 — ближе к началу, delta > 0 — дальше.
     * Очередь пересобирается атомарно: конкурентные offer/poll безопасны,
     * так как ConcurrentLinkedQueue допускает модификацию на лету.
     */
    private void shiftInQueue(UUID uuid, int delta) {
        if (delta == 0) {
            return;
        }
        synchronized (queue) {
            java.util.List<UUID> list = new ArrayList<>(queue);
            int idx = list.indexOf(uuid);
            if (idx < 0) {
                return;
            }
            list.remove(idx);
            int target = Math.max(0, Math.min(list.size(), idx + delta));
            list.add(target, uuid);
            queue.clear();
            queue.addAll(list);
        }
    }

    /** Сундук PvP-зоны: лежит на pvp_chest координатах, пополняется по таймеру. */
    private void refillPvpChest() {
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            return;
        }
        int y = lobbyBaseY(w);
        org.bukkit.block.Block b = w.getBlockAt(pvpChestX, y + 1, pvpChestZ);
        if (b.getType() != Material.CHEST) {
            return;
        }
        try {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
            chest.getInventory().clear();
            int slot = 0;
            for (org.bukkit.inventory.ItemStack item : parsePvpItems()) {
                if (slot < 27) {
                    chest.getInventory().setItem(slot++, item);
                }
            }
            chest.update(true, false);
        } catch (Throwable ignored) {
        }
    }

    /** Парсер "MATERIAL[:ENCHANT:LEVEL][:COUNT]" из queue_pvp.items. */
    private List<org.bukkit.inventory.ItemStack> parsePvpItems() {
        List<org.bukkit.inventory.ItemStack> out = new ArrayList<>();
        List<String> defs = pvpItems;
        if (defs == null || defs.isEmpty()) {
            defs = java.util.Arrays.asList(
                    "NETHERITE_CHESTPLATE:PROTECTION:25",
                    "DIAMOND_SWORD:SHARPNESS:1",
                    "GOLDEN_APPLE:25");
        }
        for (String def : defs) {
            try {
                String[] parts = def.split(":");
                Material mat = Material.matchMaterial(parts[0].trim());
                if (mat == null) {
                    continue;
                }
                org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(mat);
                if (parts.length >= 3) {
                    org.bukkit.enchantments.Enchantment ench = enchant(parts[1].trim());
                    if (ench != null) {
                        item.addUnsafeEnchantment(ench, Integer.parseInt(parts[2].trim()));
                    }
                }
                if (parts.length >= 4) {
                    item.setAmount(Math.max(1, Integer.parseInt(parts[3].trim())));
                } else if (parts.length == 2) {
                    // "GOLDEN_APPLE:25" — два поля: материал + количество
                    try {
                        item.setAmount(Math.max(1, Integer.parseInt(parts[1].trim())));
                    } catch (NumberFormatException ignored) {
                    }
                }
                // Лут лобби помечается тегом: его нельзя вынести в мир —
                // вычищается при старте проверки, выпуске и выходе.
                try {
                    org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(lobbyLootKey,
                                PersistentDataType.BYTE, (byte) 1);
                        item.setItemMeta(meta);
                    }
                } catch (Throwable ignored) {
                }
                out.add(item);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static org.bukkit.enchantments.Enchantment enchant(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        // удобные алиасы
        if ("PROTECTION".equals(n)) n = "PROTECTION_ENVIRONMENTAL";
        if ("SHARPNESS".equals(n)) n = "DAMAGE_ALL";
        if ("UNBREAKING".equals(n)) n = "DURABILITY";
        org.bukkit.enchantments.Enchantment e = org.bukkit.enchantments.Enchantment.getByName(n);
        if (e == null) {
            try {
                e = org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft(name.toLowerCase(java.util.Locale.ROOT)));
            } catch (Throwable ignored) {
            }
        }
        return e;
    }

    /** Видимость в очереди-лобби: queue_hide_players=false — все видят
     * друг друга (PvP-арена, взаимодействие); true — невидимы, кроме PvP-зоны. */
    private void syncQueueVisibility() {
        if (queueMode != 2) {
            return;
        }
        java.util.List<Player> queued = new ArrayList<>();
        for (UUID u : queue) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                queued.add(p);
            }
        }
        for (UUID u : entryQueue) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                queued.add(p);
            }
        }
        // queue_hide_players=false — вся очередь видна; иначе видимость
        // только внутри PvP-арены.
        for (Player a : queued) {
            boolean aInPvp = pvpEnabled && isPvpArea(a.getLocation());
            for (Player b : queued) {
                if (a == b) {
                    continue;
                }
                boolean see = !queueHidePlayers
                        || (aInPvp && isPvpArea(b.getLocation()));
                try {
                    if (see) {
                        a.showPlayer(plugin, b);
                    } else {
                        a.hidePlayer(plugin, b);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------- мир проверки ----------

    private World getOrCreateWorld() {
        World w = verifyWorld;
        if (w != null) {
            return w;
        }
        // После неудачи повторяем не чаще раза в 30 сек (могла быть временная ошибка)
        if (worldTried && System.currentTimeMillis() < worldRetryAt) {
            return null;
        }
        worldTried = true;
        worldRetryAt = System.currentTimeMillis() + 30_000L;
        try {
            w = Bukkit.getWorld(worldName);
            if (w == null) {
                if (ServerCore.isFolia()) {
                    plugin.getLogger().severe("AntiBot: Folia не поддерживает создание миров — "
                            + "создай мир '" + worldName + "' вручную или отключи антибот");
                    return null;
                }
                WorldCreator creator = new WorldCreator(worldName);
                creator.type(WorldType.FLAT);
                creator.generateStructures(false);
                creator.generator(new VoidGenerator());
                w = creator.createWorld();
            }
            if (w != null) {
                try {
                    w.setAutoSave(false);
                    // Спавн-чанки проверочного мира не нужны в памяти — экономия
                    w.setKeepSpawnInMemory(false);
                    // В пустом мире мобов нет, но лимиты зануляем на всякий случай
                    w.setMonsterSpawnLimit(0);
                    w.setAnimalSpawnLimit(0);
                    w.setAmbientSpawnLimit(0);
                    w.setWaterAnimalSpawnLimit(0);
                } catch (Throwable ignored) {
                }
                verifyWorld = w;
                buildLobby(w);
                plugin.getLogger().info("AntiBot: мир проверки '" + worldName + "' готов");
            }
        } catch (Throwable t) {
            // Спамим в консоль — админ должен видеть, что проверка не работает
            plugin.getLogger().severe("==================================================");
            plugin.getLogger().severe("AntiBot: НЕ УДАЛОСЬ создать мир проверки '" + worldName
                    + "': " + t.getMessage());
            plugin.getLogger().severe(fallbackMainWorld
                    ? "AntiBot: будет использована ЗАПАСНАЯ арена в небе основного мира."
                    : "AntiBot: игроки пропускаются БЕЗ проверки! Включи antibot.fallback_main_world или почини мир.");
            plugin.getLogger().severe("AntiBot: повторная попытка через 30 сек.");
            plugin.getLogger().severe("==================================================");
        }
        return verifyWorld;
    }

    /** Полностью пустой генератор — нулевая нагрузка на чанки. */
    public static final class VoidGenerator extends ChunkGenerator {
        @Override
        @SuppressWarnings("deprecation")
        public ChunkData generateChunkData(World world, java.util.Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world);
        }

        @Override
        public Location getFixedSpawnLocation(World world, java.util.Random random) {
            return new Location(world, 0.5, 65, 0.5);
        }
    }

    // ---------- сообщения ----------

    private MessageService messages() {
        if (plugin instanceof RegisterPlugin) {
            return ((RegisterPlugin) plugin).getMessageService();
        }
        return null;
    }

    private void sendMessage(Player player, String key, int number) {
        MessageService ms = messages();
        if (ms == null || player == null) {
            return;
        }
        Map<String, String> ph = new HashMap<>();
        CheckState st = checks.get(player.getUniqueId());
        List<Stage> order = (st != null && st.stages != null) ? st.stages : stageOrder;
        ph.put("stage_num", String.valueOf(st == null ? 1 : st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
        ph.put("seconds", String.valueOf(cameraSeconds));
        ph.put("slots", String.valueOf(slotsRequired));
        ph.put("blocks", String.valueOf(number));
        ph.put("position", String.valueOf(number));
        ph.put("word", puzzleConfirmWord);
        String text = ms.message(key, ph);
        if (text != null && !text.isEmpty()) {
            player.sendMessage(text);
        }
    }

    private void sendCaptcha(Player player, CheckState st) {
        MessageService ms = messages();
        if (ms == null) {
            return;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("code", st.code == null ? "" : st.code);
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(stageOrder.size()));
        String text = ms.message("antibot_stage_captcha", ph);
        if (text == null || text.isEmpty()) {
            text = "§eВведи код в чат: §f" + (st.code == null ? "?" : st.code);
        }
        player.sendMessage(text);
        // TITLE_DUP
        try {
            String ttl = ms.message("antibot_captcha_title", ph);
            String sub = ms.message("antibot_captcha_subtitle", ph);
            if (ttl != null && !ttl.isEmpty()) {
                player.sendTitle(ttl, sub == null ? "" : sub, 10, 70, 10);
            }
        } catch (Throwable ignored) {
        }
        st.promptShownAt = System.currentTimeMillis();
    }

    private void sendClickPrompt(Player player, CheckState st) {
        st.clickPromptAt = System.currentTimeMillis();
        MessageService ms = messages();
        java.util.List<Stage> order = st.stages != null ? st.stages : stageOrder;
        Map<String, String> ph = new HashMap<>();
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
        String text = ms == null ? null : ms.message("antibot_stage_click", ph);
        if (text == null || text.isEmpty()) {
            text = "§eПроверка: нажми на это сообщение или введи команду ниже";
        }
        try {
            net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(text);
            comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rpverify " + st.clickToken));
            player.spigot().sendMessage(comp);
        } catch (Throwable t) {
            player.sendMessage(text);
        }
        // Всегда дублируем команду текстом: если клик-ивент недоступен
        // (Bedrock/старый клиент) — игрок вводит её вручную.
        player.sendMessage("§7Если клик не сработал — введи: §f/rpverify " + st.clickToken);
        st.promptShownAt = System.currentTimeMillis();
    }

    // ---------- боссбар ----------

    private void createBar(Player player, CheckState st) {
        if (!bossbarEnabled || st.bar != null) {
            return;
        }
        try {
            st.bar = Bukkit.createBossBar(barTitle(st, 0L), barColor(null),
                    org.bukkit.boss.BarStyle.SOLID);
            st.bar.setProgress(0.0);
            st.bar.addPlayer(player);
        } catch (Throwable ignored) {
        }
        // Второй (рекламный) боссбар — свой текст и цвет из конфига
        if (bossbar2Enabled && st.bar2 == null) {
            try {
                org.bukkit.boss.BarColor c = barColorByName(bossbar2Color,
                        org.bukkit.boss.BarColor.PURPLE);
                String title = bossbar2Title == null || bossbar2Title.isEmpty()
                        ? "&dДобро пожаловать!" : bossbar2Title;
                st.bar2 = Bukkit.createBossBar(toBarText(title), c,
                        org.bukkit.boss.BarStyle.SOLID);
                st.bar2.setProgress(1.0);
                st.bar2.addPlayer(player);
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateBar(Player player, CheckState st) {
        if (!bossbarEnabled || player == null || !player.isOnline()) {
            return;
        }
        if (st.bar == null) {
            createBar(player, st);
            if (st.bar == null) {
                return;
            }
        }
        try {
            Stage stage = getCurrentStage(player.getUniqueId());
            st.bar.setColor(barColor(stage));
            List<Stage> order = st.stages != null ? st.stages : stageOrder;
            double progress = order.isEmpty() ? 0.0
                    : (double) st.stageIndex / (double) order.size();
            st.bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
            long sec = Math.max(0L, (st.stageDeadline - System.currentTimeMillis()) / 1000L);
            st.lastBarSec = sec;
            updateBarTitle(st, sec);
        } catch (Throwable ignored) {
        }
    }

    private void updateBarTitle(CheckState st, long secondsLeft) {
        if (st.bar == null) {
            return;
        }
        try {
            st.bar.setTitle(barTitle(st, secondsLeft));
        } catch (Throwable ignored) {
        }
    }

    private String barTitle(CheckState st, long secondsLeft) {
        MessageService ms = messages();
        List<Stage> order = st.stages != null ? st.stages : stageOrder;
        Map<String, String> ph = new HashMap<>();
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
        ph.put("seconds", String.valueOf(secondsLeft));
        String text;
        // bossbar_title: свой шаблон из конфига — приоритет над lang
        if (bossbarTitle != null && !bossbarTitle.isEmpty()) {
            text = bossbarTitle;
            for (Map.Entry<String, String> en : ph.entrySet()) {
                text = text.replace("{" + en.getKey() + "}", en.getValue());
            }
        } else {
            text = ms == null ? null : ms.message("antibot_bossbar", ph);
            if (text == null || text.isEmpty()) {
                text = "&eПроверка на бота… &fэтап " + (st.stageIndex + 1) + "/" + order.size()
                        + (secondsLeft > 0 ? " &7(" + secondsLeft + "с)" : "");
            }
        }
        return toBarText(text);
    }

    /** HEX &#RRGGBB → §x§R§R§G§G§B§B (боссбар понимает только legacy-коды). */
    private static String toBarText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 7 < text.length() && text.charAt(i + 1) == '#') {
                String hex = text.substring(i + 2, i + 8);
                boolean ok = true;
                for (int j = 0; j < 6; j++) {
                    if (Character.digit(hex.charAt(j), 16) < 0) { ok = false; break; }
                }
                if (ok) {
                    sb.append('§').append('x');
                    for (int j = 0; j < 6; j++) {
                        sb.append('§').append(Character.toUpperCase(hex.charAt(j)));
                    }
                    i += 7;
                    continue;
                }
            }
            if (c == '&' && i + 1 < text.length()
                    && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(text.charAt(i + 1))) >= 0) {
                sb.append('§').append(Character.toLowerCase(text.charAt(i + 1)));
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private org.bukkit.boss.BarColor barColor(Stage stage) {
        // bossbar_color: "auto" — цвет по этапу; иначе фиксированный из конфига
        if (bossbarColorName != null && !"auto".equalsIgnoreCase(bossbarColorName)) {
            org.bukkit.boss.BarColor fixed = barColorByName(bossbarColorName, null);
            if (fixed != null) {
                return fixed;
            }
        }
        if (stage == null) {
            return org.bukkit.boss.BarColor.BLUE;
        }
        switch (stage) {
            case FALL: return org.bukkit.boss.BarColor.BLUE;
            case CAMERA: return org.bukkit.boss.BarColor.PURPLE;
            case SLOTS: return org.bukkit.boss.BarColor.YELLOW;
            case CAPTCHA: return org.bukkit.boss.BarColor.PINK;
            case CLICK: return org.bukkit.boss.BarColor.GREEN;
            case PUZZLE: return org.bukkit.boss.BarColor.RED;
            case MATH: return org.bukkit.boss.BarColor.GREEN;
            case SECRET: return org.bukkit.boss.BarColor.PINK;
            case BLOCK: return org.bukkit.boss.BarColor.WHITE;
            default: return org.bukkit.boss.BarColor.BLUE;
        }
    }

    private static org.bukkit.boss.BarColor barColorByName(String name,
            org.bukkit.boss.BarColor fallback) {
        try {
            return org.bukkit.boss.BarColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void removeBar(CheckState st) {
        if (st != null && st.bar != null) {
            try {
                st.bar.removeAll();
            } catch (Throwable ignored) {
            }
            st.bar = null;
        }
        if (st != null && st.bar2 != null) {
            try {
                st.bar2.removeAll();
            } catch (Throwable ignored) {
            }
            st.bar2 = null;
        }
    }

    private void removeBar(org.bukkit.boss.BossBar bar) {
        if (bar != null) {
            try {
                bar.removeAll();
            } catch (Throwable ignored) {
            }
        }
    }

    private String msg(String key) {
        MessageService ms = messages();
        String text = ms == null ? null : ms.message(key);
        if (text == null || text.isEmpty()) {
            return key.equals("antibot_timeout")
                    ? "Время проверки истекло. Перезайди на сервер."
                    : "Проверка на бота не пройдена.";
        }
        return text;
    }

    /** Код на карте в руке — боту сложнее прочитать, человек видит сразу. */
    private void giveCaptchaMap(Player player, CheckState st) {
        try {
            org.bukkit.map.MapView view = Bukkit.createMap(verifyWorld);
            view.getRenderers().forEach(view::removeRenderer);
            final String code = st.code;
            view.addRenderer(new org.bukkit.map.MapRenderer() {
                @Override
                public void render(org.bukkit.map.MapView mapView, org.bukkit.map.MapCanvas canvas, Player p) {
                    canvas.drawText(8, 8, org.bukkit.map.MinecraftFont.Font, "КОД:");
                    canvas.drawText(8, 24, org.bukkit.map.MinecraftFont.Font, code);
                }
            });
            org.bukkit.inventory.ItemStack map = new org.bukkit.inventory.ItemStack(Material.FILLED_MAP);
            org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) map.getItemMeta();
            meta.setMapView(view);
            try {
                meta.getPersistentDataContainer().set(lobbyLootKey,
                        PersistentDataType.BYTE, (byte) 1);
            } catch (Throwable ignored) {
            }

            map.setItemMeta(meta);
            player.getInventory().setItemInMainHand(map);
        } catch (Throwable ignored) {
        }
    }

    private String generateCode() {
        // Генерируем до тех пор, пока код не будет уникален: не такой, как у
        // другого игрока на проверке прямо сейчас, и не повтор прошлого.
        String last = lastGeneratedCode;
        for (int tries = 0; tries < 16; tries++) {
            StringBuilder sb = new StringBuilder(codeLength + 1);
            sb.append(captchaPrefixes.charAt(random.nextInt(captchaPrefixes.length())));
            for (int i = 0; i < codeLength; i++) {
                sb.append(CAPTCHA_ALPHABET.charAt(random.nextInt(CAPTCHA_ALPHABET.length())));
            }
            String code = sb.toString();
            if (code.equals(last)) {
                continue;
            }
            boolean clash = false;
            for (CheckState other : checks.values()) {
                if (code.equals(other.code)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                lastGeneratedCode = code;
                return code;
            }
        }
        return generateCodeFallback();
    }

    private volatile String lastGeneratedCode;

    private String generateCodeFallback() {
        StringBuilder sb = new StringBuilder(codeLength + 1);
        sb.append(captchaPrefixes.charAt(random.nextInt(captchaPrefixes.length())));
        for (int i = 0; i < codeLength; i++) {
            sb.append(CAPTCHA_ALPHABET.charAt(random.nextInt(CAPTCHA_ALPHABET.length())));
        }
        return sb.toString();
    }

    // CAPTCHA: алфавит без похожих символов (0/O, 1/l/I); префикс из конфига
    private static final String CAPTCHA_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private volatile String captchaPrefixes = ".#!$";

    private String generateToken() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static final int READY = 2109234906;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100e) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100e) == READY;
    }
}
