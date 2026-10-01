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
        FALL, CAMERA, SLOTS, CAPTCHA, CLICK, PUZZLE, MATH, SECRET, BLOCK, AIR_CAPTCHA
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
        // BLOCK: сундук инструментов открыт и затем закрыт (обязательный шаг)
        boolean chestOpened;
        /** BLOCK: ошибки (не тот блок / не тот инструмент). */
        int blockMistakes;
        boolean chestClosed;
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
        boolean toolChestWarned;
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
        long wrongBlockHintAt;
        int targetX;
        int targetZ;
        // SLOTS: рывки камеры (проверка живого клиента)
        int joltSent;
        int joltAcks;
        long joltDeadline;
        boolean slotsPendingJolts;
        // FALL: идёт пакетная проверка падения (Netty) — onMove не трогаем
        boolean fallPacketMode;
        // FALL: пакетный режим для этого игрока сорвался по инфраструктуре
        // (нет пакетов/исключение) — оставшиеся повторы идут через legacy
        boolean fallLegacyOnly;
        // FALL legacy: траектория текущего повтора по событиям Bukkit —
        // число снижающихся сэмплов, макс. шаг за событие, прошлый шаг,
        // сколько раз шаг рос (ускорение = гравитация), нарушения траектории
        int fallSamples;
        double fallMaxStep;
        double fallLastStep;
        int fallAccel;
        int fallTrajFails;
        /** Пазл keep: какое животное ОСТАВИТЬ (всё остальное — лишнее). */
        String puzzleKeep;
        /** Задержки ответов (мс) — «ровные» ответы бота видны по разбросу. */
        java.util.List<Long> answerDelays;
        /** AIR_CAPTCHA: накоплено градусов поворота, показан ли код, код, попытки, блоки. */
        float airTurned;
        boolean airShown;
        String airCode;
        int airAttempts;
        int[] airPad;
        java.util.List<int[]> airBlocks;
        /** План повторов FALL: вид каждого повтора (FK_*), первый — обычный. */
        int[] fallPlan;
        /** Материалы платформ, уже выпавшие на этом этапе, — без повторов. */
        java.util.Set<Material> fallUsedMats;
        int fallKind;
        /** LAUNCH: высота в момент толчка, время толчка, поднялся ли, повторы. */
        double launchBaseY;
        long launchAt;
        boolean launchRose;
        int launchRetries;
        // CAMERA/BLOCK: суммарные градусы текущей серии одинаковых дельт,
        // стрик «роботизированных» snap-поворотов и последний snap
        double sameDeltaDeg;
        float prevSnapDelta = Float.NaN;
        boolean cameraHintSent;
        // SLOTS: хоть один jolt-телепорт подтверждён клиентом (Accept)
        // либо проверка подтверждения недоступна на этом ядре
        boolean joltConfirmed;
        // PUZZLE-стена: имя тайла на ячейку, сид отрисовки, что уже нарисовано
        String[] puzzleCells;
        long puzzleSeed;
        boolean[] puzzleCellDrawn;
        // PUZZLE-GUI: время последнего засчитанного клика
        long lastPuzzleClickAt;
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
        /** Этап, отрисованный в боссбаре — пропускаем
            повторные setTitle/setProgress на каждый move-пакет. */
        Stage lastBarStage = null;
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
        // режим/полёт уже запомнены (стэш лобби-очереди или прошлый
        // preparePlayer) — повторный prepare не перезаписывает их полётом лобби
        boolean stateSaved;
        // стэш перенесён из лобби-очереди: в руках только лут лобби — очистить
        boolean clearOnPrepare;
        // CLICK: токены кнопок-приманок и счётчики промахов
        java.util.Set<String> clickDecoys;
        int clickDecoyHits;
        int clickWrong;
        int clickPrompts;
        // CAPTCHA на карте: код в чат/титл не пишется; что уже нарисовано
        boolean captchaOnMap;
        String captchaDrawn;
        long captchaSeed;
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
    // antibot.empty_inventory: на проверке/в лобби настоящий инвентарь в стэше
    private volatile boolean emptyInventory = true;
    private volatile boolean bossbarEnabled = true;
    private volatile int fallMinHeight = 5;
    private volatile int fallMaxHeight = 9;
    // очередь: 0 = только сообщение, 1 = боссбар на месте, 2 = лобби-платформа
    private volatile int queueMode = 2;
    /** antibot.mode: fast — проверка сразу при входе, без лобби/очередей/afk_first. */
    private volatile boolean fastMode = false;
    private volatile boolean queueChatAllowed = false;
    private volatile int queueSpamSeconds = 5;
    private volatile boolean queueHologram = true;
    private volatile boolean queueParkour = true;
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
    private volatile int cameraSnapMax = 8;
    // Линейность прицела: сколько ОДИНАКОВЫХ нетривиальных дельт подряд
    // и сколько градусов такой серии нужно, чтобы считать поворот ботом
    private volatile int linearityStreak = 60;
    private static final double LINEARITY_MIN_DEG = 360.0;
    /** Дельта поворота ниже этого (°) — микродвижение/квант мыши, серию рвёт. */
    private static final float ROT_TRIVIAL_DEG = 1.0f;

    // FALL: пакетный режим признан сломанным (инъекция не встаёт, пакеты
    // не видны) — дальше до reload только legacy на событиях Bukkit
    private volatile boolean fallPacketsBroken;
    /** Причины пакетного FALL, означающие реальную физику бота — кик.
     *  "teleport-drop" сюда не входит: один рывок вниз мог дать и сбой
     *  классификации пакетов — такой повтор переигрывается через legacy. */
    private static final java.util.Set<String> FALL_BOT_REASONS = new java.util.HashSet<>(
            java.util.Arrays.asList("physics-mismatch", "ground-in-air", "linear-fall", "early-ground"));

    // Folia: мир/арены антибота не поддерживаются — выключаем целиком
    private volatile boolean foliaWarned;
    // Игроки, пропущенные к авторизации без проверки ПО ДИЗАЙНУ (мир
    // недоступен, сбой старта, вошли при выключенном антиботе) — им /reg можно
    private final java.util.Set<UUID> admittedNoCheck = ConcurrentHashMap.newKeySet();
    // Прокси: целевой сервер entry-очереди ждёт авторизации (D2)
    private final Map<UUID, String> pendingEntryTarget = new ConcurrentHashMap<>();
    // Снятие проверки без прохождения: продолжить обычный auth-флоу
    private volatile java.util.function.Consumer<Player> abortHook;
    // AFK-сервис: режим до AFK-spectator (F41)
    private volatile AfkService afkService;
    // Лобби-очередь: настоящий инвентарь/режим/полёт неавторизованного
    // игрока лежат здесь, в лобби у него только лут лобби (самозванец
    // с чужим ником на offline-mode не съест и не потеряет чужие вещи)
    private final Map<UUID, CheckState> queueStash = new ConcurrentHashMap<>();
    // PvP-очередь: последнее засчитанное убийство пары убийца->жертва
    // (свои аккаунты не «прокачивают» позицию, убивая друг друга)
    private final Map<Long, Long> pvpPairAt = new ConcurrentHashMap<>();
    private static final long PVP_PAIR_COOLDOWN_MS = 60_000L;
    // onDisable: шедулер уже не принимает задачи — телепорты синхронно
    private volatile boolean shuttingDown;

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
    private volatile int blockMaxMistakes = 1;
    private volatile int captchaAttempts = 3;
    private volatile boolean blockToolChest = true;
    /** BLOCK: целевой блок ломается только после «открыл и закрыл сундук». */
    private volatile boolean blockChestRequired = true;
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
    private volatile int puzzleMaxWrong = 1;
    /** Ответ быстрее этого лимита (мс) = бот. 0 — выключено. */
    private volatile int minAnswerMs = 400;
    private volatile int minClickMs = 100; // лимит скорости КЛИКА: клик <100мс = бот
    // CLICK: кнопок-приманок рядом с настоящей (0 — старый режим, одна ссылка)
    private volatile int clickDecoyCount = 2;
    private volatile boolean kitEnabled = true;
    private volatile long kitCooldownMs = 30000L;
    private final List<org.bukkit.inventory.ItemStack> kitItems = new ArrayList<>();
    private final Map<UUID, Long> kitLastOpen = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.inventory.Inventory> kitInvs = new ConcurrentHashMap<>();
    private final Map<UUID, org.bukkit.inventory.Inventory> kitEditors = new ConcurrentHashMap<>();
    private final Map<UUID, Long> speedCooldown = new ConcurrentHashMap<>();
    private final List<org.bukkit.entity.ArmorStand> zoneHolos = new ArrayList<>();
    private final java.util.Set<UUID> bootPassed = ConcurrentHashMap.newKeySet();
    private volatile boolean recheckOnRestart = false;
    /** Срок действия пройденной проверки для зарегистрированных (antibot.recheck_hours). */
    private final AntiBotPasses passes;
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
    private volatile long lastItemCleanAt;
    private volatile int lobbyItemCleanS = 60;
    // PUZZLE blocks-режим: рамки 3x3, на каждой 1 картинка-животное
    private volatile String puzzleMode = "both";
    private volatile List<String> puzzleTileNames = Collections.emptyList();
    private volatile List<String> puzzleRemoveNames = Collections.emptyList();
    private volatile int puzzleRemoveCount = 3;
    private volatile String puzzleTaskText = "";
    /** puzzle_style: keep — «оставь только X», remove — «убери лишних X». */
    private volatile boolean puzzleStyleKeep = true;
    private volatile String puzzleTaskKeepText = "";
    // PUZZLE-стена: карта на позицию ячейки + кэш палитр тайлов
    private final org.bukkit.map.MapView[] cellViews = new org.bukkit.map.MapView[9];
    private final Map<String, byte[]> tileBytes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, org.bukkit.map.MapView> puzzleViews = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.awt.image.BufferedImage> tileImgs = new java.util.concurrent.ConcurrentHashMap<>();
    // MATH: диапазон чисел и куда показывать пример
    private volatile boolean mathAdd = true;
    private volatile boolean mathMul;
    private volatile boolean packetWatchEnabled = true;
    private volatile int packetWatchMaxPps = 300;
    private volatile int packetWatchSeconds = 3;

    int packetWatchMaxPps() {
        return packetWatchMaxPps;
    }

    int packetWatchSeconds() {
        return packetWatchSeconds;
    }

    /** Фоновая пакетная проверка с самого входа (до авторизации). */
    public void startPacketWatch(Player p) {
        FallPacketCheck fp = fallPackets;
        if (packetWatchEnabled && enabled && fp != null && p != null) {
            fp.watch(p);
        }
    }

    public void stopPacketWatch(Player p) {
        FallPacketCheck fp = fallPackets;
        if (fp != null && p != null) {
            fp.unwatch(p);
        }
    }

    /** Фоновая проверка поймала бота (флуд пакетами / битые координаты). Главный поток. */
    void onBackgroundFlag(UUID uuid, String reason) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            return;
        }
        if (plugin instanceof RegisterPlugin
                && ((RegisterPlugin) plugin).getSessionManager().isLoggedIn(uuid)) {
            return; // уже вошёл — фон для него выключен
        }
        plugin.getLogger().info("AntiBot: фоновая пакетная проверка — " + p.getName() + " (" + reason + ")");
        if (checks.containsKey(uuid)) {
            failCheck(uuid, msg("antibot_failed_kick"));
        } else {
            if (plugin instanceof RegisterPlugin && ((RegisterPlugin) plugin).getAntiBotGuard() != null) {
                ((RegisterPlugin) plugin).getAntiBotGuard().onAntiBotFail(uuid);
            }
            p.kickPlayer(msg("antibot_failed_kick"));
        }
    }

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
        String lastBarText;
        float lastBarProg = -1f;
        Location lobbyReturn;
        // анти-флай в лобби очереди
        int flyTicks;
        // lobby-first: раньше этого времени в проверку не выпускать
        long notBefore;
    }

    private final Map<UUID, CheckState> checks = new ConcurrentHashMap<>();
    private final Map<UUID, Material> lastTargets = new ConcurrentHashMap<>();
    private final Map<Material, org.bukkit.inventory.ItemStack> toolCache = new ConcurrentHashMap<>();
    private volatile List<org.bukkit.inventory.ItemStack> cachedPvpItems = null;
    private volatile int[] puzzleRemoveWeights = new int[]{1};
    private volatile boolean puzzleSameTarget = false;
    private volatile String lastHoloText = null;
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
        this.passes = new AntiBotPasses(plugin);
    }

    // ---------- конфигурация ----------

    public void reload() {
        final boolean wasEnabled = enabled;
        enabled = plugin.getConfig().getBoolean("antibot.enabled", false);
        // Folia: арены/лобби пишут блоки чужих регионов и телепортят из
        // глобального тикера — полуработающая проверка кикала бы людей.
        // Честно выключаем антибот целиком, авторизация работает как обычно.
        if (enabled && ServerCore.isFolia()) {
            if (!foliaWarned) {
                foliaWarned = true;
                plugin.getLogger().warning("AntiBot: на Folia проверка на бота с этапами НЕ поддерживается — "
                        + "antibot.enabled игнорируется, игроки идут сразу к авторизации.");
            }
            enabled = false;
        }
        worldName = nonEmpty(plugin.getConfig().getString("antibot.world_name", "auth_verify"), "auth_verify");
        maxConcurrent = Math.max(1, plugin.getConfig().getInt("antibot.max_concurrent_checks", 8));
        arenaSpacing = Math.max(16, plugin.getConfig().getInt("antibot.arena_spacing", 32));
        codeLength = Math.max(3, Math.min(8, plugin.getConfig().getInt("antibot.code_length", 4)));
        String pfx = plugin.getConfig().getString("antibot.captcha_prefixes", ".#!$");
        captchaPrefixes = (pfx == null || pfx.isEmpty()) ? ".#!$" : pfx;
        maxAttempts = Math.max(1, plugin.getConfig().getInt("antibot.max_attempts", 5));
        cameraSeconds = Math.max(1, Math.min(15, plugin.getConfig().getInt("antibot.camera_seconds", 3)));
        slotsRequired = Math.max(1, Math.min(8, plugin.getConfig().getInt("antibot.slots_required", 2)));
        physicsRepetitions = Math.max(1, Math.min(8, plugin.getConfig().getInt("antibot.physics_repetitions", 5)));
        fallSmartPlan = plugin.getConfig().getBoolean("antibot.fall_smart_plan", true);
        answerTimingEnabled = plugin.getConfig().getBoolean("antibot.answer_timing.enabled", true);
        answerMsPerChar = Math.max(0, plugin.getConfig().getInt("antibot.answer_timing.min_ms_per_char", 120));
        answerMinSamples = Math.max(2, plugin.getConfig().getInt("antibot.answer_timing.min_samples", 3));
        answerMaxStddevMs = Math.max(0, plugin.getConfig().getInt("antibot.answer_timing.max_stddev_ms", 40));
        airTurnDegrees = Math.max(30, plugin.getConfig().getInt("antibot.air_captcha.turn_degrees", 150));
        airCodeLength = Math.max(3, Math.min(6, plugin.getConfig().getInt("antibot.air_captcha.code_length", 4)));
        airNoise = Math.max(0, Math.min(30, plugin.getConfig().getInt("antibot.air_captcha.noise_blocks", 6)));
        airTilt = plugin.getConfig().getBoolean("antibot.air_captcha.tilt", true);
        airScale = Math.max(1, Math.min(3, plugin.getConfig().getInt("antibot.air_captcha.scale", 2)));
        airDiagonal = plugin.getConfig().getBoolean("antibot.air_captcha.diagonal", true);
        holdingPadWorld = null; // после reload площадку ожидания проверить заново
        captchaStrike = plugin.getConfig().getBoolean("antibot.captcha_strike", false);
        restorePerTick = Math.max(0, plugin.getConfig().getInt("antibot.arena_restore_per_tick", 2));
        surgeEnabled = plugin.getConfig().getBoolean("antibot.surge.enabled", true);
        surgeTrigger = Math.max(3, plugin.getConfig().getInt("antibot.surge.trigger_joins", 15));
        surgeStartsPerSecond = Math.max(1, plugin.getConfig().getInt("antibot.surge.max_starts_per_second", 3));
        surgeCalmMs = Math.max(5, plugin.getConfig().getInt("antibot.surge.calm_seconds", 30)) * 1000L;
        verifyViewDistance = Math.max(0, plugin.getConfig().getInt("antibot.world_view_distance", 3));
        fallLaunch = plugin.getConfig().getBoolean("antibot.fall_launch", true);
        minFallMillis = Math.max(50L, plugin.getConfig().getLong("antibot.min_fall_millis", 250L));
        blockDistance = Math.max(2, Math.min(10, plugin.getConfig().getInt("antibot.block_distance", 5)));
        slotsAntiCheat = plugin.getConfig().getBoolean("antibot.slots_anti_cheat", true);
        mapCaptcha = plugin.getConfig().getBoolean("antibot.map_captcha", false);
        onlyNewPlayers = plugin.getConfig().getBoolean("antibot.only_new_players", true);
        restorePlayerState = plugin.getConfig().getBoolean("antibot.restore_player_state", true);
        emptyInventory = plugin.getConfig().getBoolean("antibot.empty_inventory", true);
        bossbarEnabled = plugin.getConfig().getBoolean("antibot.bossbar", true);
        fallMinHeight = Math.max(3, Math.min(20, plugin.getConfig().getInt("antibot.fall_min_height", 5)));
        fallMaxHeight = Math.max(fallMinHeight, Math.min(30, plugin.getConfig().getInt("antibot.fall_max_height", 9)));

        String qm = plugin.getConfig().getString("antibot.queue_mode", "bossbar").toLowerCase(java.util.Locale.ROOT);
        queueMode = "lobby".equals(qm) ? 2 : ("bossbar".equals(qm) ? 1 : 0);
        queueChatAllowed = plugin.getConfig().getBoolean("antibot.queue_chat_allowed", false);
        queueSpamSeconds = Math.max(2, Math.min(30, plugin.getConfig().getInt("antibot.queue_spam_seconds", 5)));
        queueHologram = plugin.getConfig().getBoolean("antibot.queue_hologram", true);
        queueParkour = plugin.getConfig().getBoolean("antibot.queue_parkour", true);
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
        // Быстрый свайп мышью = 4–6 событий ≥60° подряд — ниже 8 людей не судим
        cameraSnapMax = Math.max(8, Math.min(30, plugin.getConfig().getInt("antibot.camera_snap_max", 8)));
        linearityStreak = Math.max(30, Math.min(500, plugin.getConfig().getInt("antibot.linearity_streak", 60)));

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
        lobbyItemCleanS = Math.max(0, plugin.getConfig().getInt("antibot.lobby_item_clean_seconds", 60));
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
        // reload = новая попытка пакетного режима (админ мог починить инжект)
        fallPacketsBroken = false;
        if (plugin.getConfig().getBoolean("antibot.fall_packets", true)) {
            if (fallPackets == null) {
                fallPackets = new FallPacketCheck(plugin, this);
            }
            try {
                fallPackets.reload();
            } catch (Throwable t) {
                disableFallPackets("reload: " + t);
            }
        } else if (fallPackets != null) {
            // Идущие пакетные сессии колбэка уже не дадут — переводим
            // их на legacy-повтор, иначе игрок висит до таймаута этапа
            disableFallPackets(null);
        }
        pvpHoloText = plugin.getConfig().getString("antibot.queue_pvp.hologram_text",

                "&c⚠ PvP-зона: смерть = -{lose} в очереди, убийство = +{gain}");

        emergencyTimes = Math.max(1, plugin.getConfig().getInt("antibot.emergency.alert_times", 10));
        emergencyIntervalSec = Math.max(30, plugin.getConfig().getInt("antibot.emergency.alert_interval_seconds", 300));

        blockPathLength = Math.max(6, Math.min(40, plugin.getConfig().getInt("antibot.block_path_length", 14)));
        blockMaxFalls = Math.max(1, Math.min(20, plugin.getConfig().getInt("antibot.block_max_falls", 5)));
        blockMaxMistakes = Math.max(0, plugin.getConfig().getInt("antibot.block_max_mistakes", 1));
        captchaAttempts = Math.max(1, plugin.getConfig().getInt("antibot.captcha_attempts",
                plugin.getConfig().getInt("antibot.max_attempts", 3)));
        blockToolChest = plugin.getConfig().getBoolean("antibot.block_tool_chest", true);
        clickColorMode = plugin.getConfig().getString("antibot.click_buttons.color_mode", "lang")
                .trim().toLowerCase(java.util.Locale.ROOT);
        // YAML читает off/no как false — это тоже «без цвета»
        if ("false".equals(clickColorMode) || "no".equals(clickColorMode)) {
            clickColorMode = "off";
        }
        clickButtonColor = plugin.getConfig().getString("antibot.click_buttons.button_color", "&a");
        clickDecoyColor = plugin.getConfig().getString("antibot.click_buttons.decoy_color", "&c");
        clickSingleColor = plugin.getConfig().getString("antibot.click_buttons.single_color", "&f");
        clickBold = plugin.getConfig().getBoolean("antibot.click_buttons.bold", true);
        blockChestRequired = plugin.getConfig().getBoolean("antibot.block_chest_required", true);
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
                Material.OCELOT_SPAWN_EGG, me.vorchun.registerplugin.util.ItemTag.mat("CAT_SPAWN_EGG", Material.OCELOT_SPAWN_EGG));
        puzzleKeep = materials(plugin.getConfig().getStringList("antibot.puzzle_keep"),
                Material.WOLF_SPAWN_EGG, Material.LLAMA_SPAWN_EGG, Material.RABBIT_SPAWN_EGG,
                Material.PARROT_SPAWN_EGG, me.vorchun.registerplugin.util.ItemTag.mat("FOX_SPAWN_EGG", Material.TURTLE_SPAWN_EGG));
        puzzleMaxWrong = Math.max(1, Math.min(10, plugin.getConfig().getInt("antibot.puzzle_max_wrong", 1)));
        minAnswerMs = Math.max(0, Math.min(5000,
                plugin.getConfig().getInt("antibot.min_answer_ms", 400)));
        fallVoidMax = Math.max(1, Math.min(20,
                plugin.getConfig().getInt("antibot.fall_void_max", 3)));
        clickDecoyCount = Math.max(0, Math.min(3, plugin.getConfig().getInt("antibot.click_decoys", 2)));
        minClickMs = Math.max(0, Math.min(2000,
                plugin.getConfig().getInt("antibot.min_click_ms", 100)));
        kitEnabled = plugin.getConfig().getBoolean("antibot.queue_pvp.kit_enabled", true);
        kitCooldownMs = Math.max(1L, plugin.getConfig().getInt("antibot.queue_pvp.kit_cooldown_seconds", 30)) * 1000L;
        loadKit();
        recheckOnRestart = plugin.getConfig().getBoolean("antibot.recheck_on_restart", false);
        passes.configure(Math.max(0, plugin.getConfig().getInt("antibot.recheck_hours", 24)) * 3600_000L);
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
        // "имя" или "имя:вес" — вес = относительный шанс выпадения в «лишние»
        puzzleRemoveNames = new ArrayList<>();
        List<Integer> rw = new ArrayList<>();
        List<String> rawRemove = plugin.getConfig().getStringList("antibot.puzzle_remove_names");
        if (rawRemove != null) {
            for (String e : rawRemove) {
                if (e == null) {
                    continue;
                }
                String nm = e.trim();
                int wgt = 1;
                int ci = nm.lastIndexOf(':');
                if (ci > 0) {
                    try {
                        wgt = Math.max(1, Integer.parseInt(nm.substring(ci + 1).trim()));
                        nm = nm.substring(0, ci).trim();
                    } catch (Throwable ignored) {
                    }
                }
                if (!nm.isEmpty()) {
                    puzzleRemoveNames.add(nm);
                    rw.add(wgt);
                }
            }
        }
        if (puzzleRemoveNames.isEmpty()) {
            puzzleRemoveNames.add("man_black");
            rw.add(1);
        }
        int[] wa = new int[rw.size()];
        for (int i = 0; i < wa.length; i++) {
            wa[i] = rw.get(i);
        }
        puzzleRemoveWeights = wa;
        puzzleSameTarget = plugin.getConfig().getBoolean("antibot.puzzle_same_target", false);
        puzzleTileNames = plugin.getConfig().getStringList("antibot.puzzle_tiles");
        if (puzzleTileNames == null || puzzleTileNames.isEmpty()) {
            puzzleTileNames = java.util.Arrays.asList("cat", "dog", "pig", "cow", "chicken",
                    "sheep", "rabbit", "fox", "panda", "man_black");
        }
        // puzzle_random_targets (по умолчанию ВКЛ): «лишние» — любой тайл из
        // puzzle_tiles с равным шансом. Веса puzzle_remove_names не действуют —
        // иначе старый дефолт man_black:50 давал «чёрного человека» почти всегда.
        if (plugin.getConfig().getBoolean("antibot.puzzle_random_targets", true)
                && puzzleTileNames.size() >= 2) {
            puzzleRemoveNames = new ArrayList<>(puzzleTileNames);
            int[] uni = new int[puzzleRemoveNames.size()];
            java.util.Arrays.fill(uni, 1);
            puzzleRemoveWeights = uni;
        }
        puzzleTaskText = plugin.getConfig().getString("antibot.puzzle_task", "");
        puzzleStyleKeep = !"remove".equalsIgnoreCase(
                plugin.getConfig().getString("antibot.puzzle_style", "keep").trim());
        puzzleTaskKeepText = plugin.getConfig().getString("antibot.puzzle_task_keep", "");
        toolCache.clear();
        cachedPvpItems = null;
        ensurePuzzleTiles();

        mathMax = Math.max(5, Math.min(99, plugin.getConfig().getInt("antibot.math_max", 20)));
        java.util.List<String> mops = plugin.getConfig().getStringList("antibot.math_operations");
        mathAdd = mops == null || mops.isEmpty() || mops.contains("add");
        mathMul = mops != null && mops.contains("mul");
        if (!mathAdd && !mathMul) {
            mathAdd = true;
        }
        packetWatchEnabled = plugin.getConfig().getBoolean("antibot.packet_watch.enabled", true);
        packetWatchMaxPps = Math.max(60, plugin.getConfig().getInt("antibot.packet_watch.max_packets_per_second", 300));
        packetWatchSeconds = Math.max(1, plugin.getConfig().getInt("antibot.packet_watch.flood_seconds", 3));
        // Подпись jar для packOk — посчитать заранее в фоне, а не на первом
        // входе в главном потоке (в профиле spark было чтение jar)
        Scheduler.runAsync(plugin, AntiBotService::computeSigLocal);
        mathWhere = plugin.getConfig().getString("antibot.math_where", "chat");

        secretMode = plugin.getConfig().getString("antibot.secret_mode", "default").toLowerCase(java.util.Locale.ROOT);
        secretCustom = plugin.getConfig().getStringList("antibot.secret_custom");
        secretCount = Math.max(1, Math.min(5, plugin.getConfig().getInt("antibot.secret_count", 2)));

        List<Stage> order = new ArrayList<>();
        if (plugin.getConfig().getBoolean("antibot.stages.fall", true)) order.add(Stage.FALL);
        if (plugin.getConfig().getBoolean("antibot.stages.camera", false)) order.add(Stage.CAMERA);
        if (plugin.getConfig().getBoolean("antibot.stages.slots", false)) order.add(Stage.SLOTS);
        if (plugin.getConfig().getBoolean("antibot.stages.captcha", false)) order.add(Stage.CAPTCHA);
        if (plugin.getConfig().getBoolean("antibot.stages.click", false)) order.add(Stage.CLICK);
        if (plugin.getConfig().getBoolean("antibot.stages.puzzle", true)) order.add(Stage.PUZZLE);
        if (plugin.getConfig().getBoolean("antibot.stages.math", false)) order.add(Stage.MATH);
        if (plugin.getConfig().getBoolean("antibot.stages.secret", false)) order.add(Stage.SECRET);
        if (plugin.getConfig().getBoolean("antibot.stages.air_captcha", false)) order.add(Stage.AIR_CAPTCHA);
        if (plugin.getConfig().getBoolean("antibot.stages.block", false)) order.add(Stage.BLOCK);
        stageOrder = Collections.unmodifiableList(order);

        // ── Быстрый режим (antibot.mode: fast, по умолчанию) ──
        // Проверка стартует сразу при входе: лобби не строится, entry-очереди
        // и lobby_first_seconds нет. Если одновременных проверок больше
        // fast_max_concurrent — лишние ждут на месте с таймером в боссбаре.
        fastMode = plugin.getConfig().getBoolean("antibot.fast_mode", true);
        if (fastMode) {
            queueMode = 1;
            lobbyFirstSeconds = 0;
            entryEnabled = false;
            maxConcurrent = Math.max(1, Math.min(500,
                    plugin.getConfig().getInt("antibot.fast_max_concurrent", 50)));
            List<String> fs = plugin.getConfig().getStringList("antibot.fast_stages");
            if (fs != null && !fs.isEmpty()) {
                List<Stage> fo = new ArrayList<>();
                for (String n : fs) {
                    try {
                        Stage s = Stage.valueOf(n.trim().toUpperCase(java.util.Locale.ROOT));
                        if (!fo.contains(s)) {
                            fo.add(s);
                        }
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("antibot.fast_stages: неизвестный этап '" + n
                                + "' (есть: fall, camera, slots, captcha, click, puzzle, math, secret, block, air_captcha)");
                    }
                }
                if (!fo.isEmpty()) {
                    stageOrder = Collections.unmodifiableList(fo);
                }
            }
        }

        scanCrashStash();
        stageTimeouts.clear();
        // 5 повторов (паутина, подброс) дольше 3-х: не меньше ~9 сек на повтор
        stageTimeouts.put(Stage.FALL, Math.max(sec("antibot.timeouts.fall", 45),
                Math.min(180, physicsRepetitions * 9)));
        stageTimeouts.put(Stage.CAMERA, sec("antibot.timeouts.camera", 30));
        stageTimeouts.put(Stage.SLOTS, sec("antibot.timeouts.slots", 35));
        stageTimeouts.put(Stage.CAPTCHA, sec("antibot.timeouts.captcha", 60));
        stageTimeouts.put(Stage.CLICK, sec("antibot.timeouts.click", 25));
        stageTimeouts.put(Stage.PUZZLE, sec("antibot.timeouts.puzzle", 60));
        stageTimeouts.put(Stage.MATH, sec("antibot.timeouts.math", 40));
        stageTimeouts.put(Stage.SECRET, sec("antibot.timeouts.secret", 40));
        stageTimeouts.put(Stage.BLOCK, sec("antibot.timeouts.block", 60));
        stageTimeouts.put(Stage.AIR_CAPTCHA, sec("antibot.timeouts.air_captcha", 60));

        if (!enabled) {
            // Антибот выключили на лету: всех, кого он держал, возвращаем
            // в обычный флоу — инвентарь, полёт, точка входа, таймаут и
            // подсказки /reg. Иначе люди зависали в лобби мира проверки.
            List<Player> aborted = new ArrayList<>();
            List<Player> passedEntry = new ArrayList<>();
            for (UUID uuid : new ArrayList<>(checks.keySet())) {
                Player p = Bukkit.getPlayer(uuid);
                cancelCheck(uuid, true);
                if (p != null && p.isOnline()) {
                    aborted.add(p);
                }
            }
            for (UUID u : new ArrayList<>(queue)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    leaveQueues(p);
                    aborted.add(p);
                } else {
                    dequeue(u);
                }
            }
            for (UUID u : new ArrayList<>(entryQueue)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    // очередь входа = проверку уже прошёл
                    leaveQueues(p);
                    bootPassed.add(u);
                    passedEntry.add(p);
                } else {
                    dequeueEntry(u);
                }
            }
            checks.clear();
            returnLocations.clear();
            queue.clear();
            for (QueueEntry qe : queueInfo.values()) {
                removeBar(qe.bar);
            }
            queueInfo.clear();
            for (QueueEntry qe : entryInfo.values()) {
                removeBar(qe.bar);
            }
            entryQueue.clear();
            entryInfo.clear();
            entryStates.clear();
            removeHologram();
            lobbyBuilt = false;
            stopTicker();
            stopWatchdog();
            for (Player p : aborted) {
                notifyAborted(p);
            }
            if (plugin instanceof RegisterPlugin) {
                for (Player p : passedEntry) {
                    ((RegisterPlugin) plugin).onAntiBotPassed(p);
                }
            }
        } else {
            ensureWatchdog();
            if (!wasEnabled) {
                // Антибот включили на лету: уже онлайн-игроки вошли без
                // проверки по дизайну — регистрацию им не блокируем
                for (Player p : Bukkit.getOnlinePlayers()) {
                    admittedNoCheck.add(p.getUniqueId());
                }
            }
            // Мир создаём заранее при включении — иначе первый заходящий
            // ждал бы генерацию мира вместо мгновенной проверки.
            // worldTried сбрасываем: после неудачи каждый reload пробует заново.
            worldTried = false;
            World w = getOrCreateWorld();
            if (w == null) {
                plugin.getLogger().severe("AntiBot включён, но мир проверки недоступен — "
                        + (fallbackMainWorld ? "будет использована запасная арена в небе основного мира!"
                        : "игроки будут пропускаться к обычной авторизации!"));
            } else if (queueMode == 2 || entryEnabled) {
                // Лобби нужно только очередям; reload перестраивает его
                // (могли смениться углы PvP/паркур). Без очередей — не строим.
                lobbyBuilt = false;
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

    /**
     * Нужна ли проверка при входе. Новичку — всегда; only_new_players: false —
     * всем; зарегистрированному — когда истёк срок прошлой проверки
     * (recheck_hours, по умолчанию 24 ч) или включена перепроверка после рестарта.
     */
    public boolean checkRequired(UUID uuid, boolean registered) {
        if (!registered || !onlyNewPlayers) {
            return true;
        }
        return requiresRestartRecheck(uuid) || passes.expired(uuid);
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
        try {
            return beginCheck0(player);
        } catch (Throwable t) {
            // Сбой старта — не оставляем игрока полуснятым: чистим следы
            // и пускаем к обычной авторизации (как при недоступном мире)
            UUID uuid = player.getUniqueId();
            plugin.getLogger().warning("AntiBot: не удалось начать проверку для "
                    + player.getName() + ": " + t);
            CheckState st = checks.remove(uuid);
            // стэш лобби мог уже переехать в стейт проверки — вернуть вещи
            try {
                if (st != null) {
                    restorePlayer(player, st);
                }
            } catch (Throwable ignored) {
            }
            dequeue(uuid);
            removeBar(st);
            returnLocations.remove(uuid);
            admittedNoCheck.add(uuid);
            return false;
        }
    }

    private boolean beginCheck0(Player player) {
        if (!packOk()) {
            plugin.getLogger().warning("[VTRegister] build: check blocked");
            player.kickPlayer(msg("antibot_failed_kick"));
            return true;
        }
        // остался стэш от аварийной остановки — вернуть вещи ДО нового стэша
        recoverStash(player);
        World w = getOrCreateWorld();
        if (w == null) {
            if (!fallbackMainWorld) {
                plugin.getLogger().warning("AntiBot: мир проверки недоступен, игрок " + player.getName() + " пропущен");
                admittedNoCheck.add(player.getUniqueId());
                return false;
            }
            // Запасной вариант: арена высоко в небе основного мира —
            // проверка продолжает работать даже без отдельного мира.
            w = player.getWorld();
            fallbackWorld = w;
        }

        UUID uuid = player.getUniqueId();
        if (checks.containsKey(uuid) || queueInfo.containsKey(uuid)) {
            return true;
        }

        // Наплыв игроков: жёсткий потолок мест (проверки + очередь).
        // Лишних кикаем вежливо — это и есть распределение нагрузки:
        // бот-волна не сможет парализовать вход реальных игроков.
        if (influxEnabled && checks.size() + queue.size() >= influxBurst) {
            player.kickPlayer(msg("antibot_influx_kick"));
            return true;
        }

        // Точка возврата — только вне зоны антибота: зашёл, стоя на
        // арене/в лобби (вышел оттуда прошлый раз) — туда не возвращаем
        Location here = player.getLocation();
        if (!isCheckArea(here)) {
            returnLocations.putIfAbsent(uuid, here.clone());
        }

        // lobby_first: все заходящие сначала греются в лобби (паркур,
        // PvP-арена, боссбар) — проверка стартует через N секунд.
        // queueWarmupDone: игрок уже отсидел своё — второй раз не гоняем.
        if (checks.size() >= maxConcurrent
                || (queueMode == 2 && lobbyFirstSeconds > 0 && !queueWarmupDone.remove(uuid))
                || !surgeAllowStart()) {
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
        // Из лобби-очереди: настоящий инвентарь/режим/полёт уже в стэше —
        // переносим в стейт проверки, в руках сейчас только лут лобби
        CheckState qs = queueStash.remove(uuid);
        if (qs != null) {
            adoptStash(st, qs);
        }
        checks.put(uuid, st);
        ensureTicker();

        sendMessage(player, "antibot_started", 0);
        createBar(player, st);

        Scheduler.runAtEntity(plugin, player, () -> {
            if (!player.isOnline() || !checks.containsKey(uuid)) {
                return;
            }
            // Мир арены: verify-мир ИЛИ запасной (основной мир игрока).
            // Проверяем именно его — в fallback-режиме verifyWorld всегда
            // null, и раньше проверка снималась на следующем тике: игрок
            // оставался без проверки и без подсказок/таймаута авторизации.
            World aw = st.world;
            if (aw == null || Bukkit.getWorld(aw.getUID()) == null) {
                plugin.getLogger().warning("AntiBot: мир арены выгружен — проверка "
                        + player.getName() + " снята, игрок идёт к обычной авторизации");
                cancelCheck(uuid, true);
                admittedNoCheck.add(uuid);
                notifyAborted(player);
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
                // (паркур/PvP). Настоящие вещи — сразу в стэш: в лобби можно
                // есть/пить/умереть в PvP, а игрок ещё не авторизован. По
                // окончании отсчёта тикер телепортирует его на арену.
                saveMoveState(player, st);
                stashInventory(player, st);
                if (st.inventorySaved) {
                    st.clearOnPrepare = true;
                }
                teleportService.authorizeTeleport(uuid);
                Compat.teleport(player, lobbySpawn(lw));
                {
                    clearVision(player);
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
            {
                clearVision(player);
            }
            teleportService.authorizeTeleport(uuid);
            Compat.teleport(player, arenaSpawn(st));
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
        // арена ещё восстанавливается — слот не отдаём
        for (Integer ax : restoringArenas) {
            int idx = ax / arenaSpacing;
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
        // точка возврата — только вне зоны антибота (не арена/лобби)
        Location jl = player.getLocation();
        qe.lobbyReturn = isCheckArea(jl) ? returnLocations.get(uuid) : jl.clone();
        queueInfo.put(uuid, qe);
        queue.offer(uuid);
        ensureTicker();

        sendMessage(player, "antibot_queue", queuePosition(uuid));
        // темнота/blindness — только экран рег/логин, в очереди не нужна
        {
            final Player fp0 = player;
            Scheduler.runAtEntity(plugin, fp0, () -> {
                if (fp0.isOnline()) {
                    clearVision(fp0);
                }
            });
        }

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
                if (player.isOnline() && queueInfo.containsKey(uuid)) {
                    // Неавторизованный в лобби (еда, зелья, PvP-смерть):
                    // настоящие вещи и режим/полёт — в стэш, вернём на
                    // любом выходе из очереди (проверка, логин, выход, кик)
                    if (!queueStash.containsKey(uuid)) {
                        CheckState qs = new CheckState();
                        saveMoveState(player, qs);
                        stashInventory(player, qs);
                        queueStash.put(uuid, qs);
                    }
                    Compat.teleport(player, lobbySpawn(fw));
                    // темнота/blindness только для reg/login — в лобби светло
                    {
                        clearVision(player);
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
        // Вещи/опыт из стэша назад, полёт лобби — к прежнему состоянию
        // (не стоял в очереди — полёт не трогаем: /fly и креатив целы)
        restoreQueueStash(uuid, qe != null);
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
    /** Мир, в котором построено лобби (verify-мир или запасной). */
    private volatile World lobbyWorld;
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
        if (w == null) {
            return;
        }
        if (lobbyBuilt && w.equals(lobbyWorld)) {
            // Лобби уже стоит: полную перестройку (~8 тыс. setType + чанки)
            // на каждый вход не делаем. Голограмма пропала (чанк выгружался,
            // стойку убрали) — только её и возвращаем, если чанк загружен.
            // Повреждения декора чинит verifyLobbyIntegrity/repairLobbyDecor.
            if (queueHologram && (hologram == null || !hologram.isValid())
                    && w.isChunkLoaded(0, LOBBY_Z >> 4)) {
                spawnHologram(w, lobbyBaseY(w));
            }
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
        // Чанк выгружен — не поднимаем его с диска ради декора: пропускаем.
        // Иначе каждый проход ремонта создаёт chunk-ticket'ы и гоняет
        // light-engine на главном потоке (трейс ChunkMapDistance в spark).
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
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
                    fix(w, cx, y + 4, LOBBY_Z + cz, LANTERN_M);
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
                    fix(w, x, py + 1, pz + gap + 1, LANTERN_M);
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
                        fix(w, px, y + 4, pz, LANTERN_M);
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
                    fix(w, pvpChestX + 4, y, pvpChestZ, me.vorchun.registerplugin.util.ItemTag.mat("POLISHED_BLACKSTONE", Material.STONE));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Удаляет дропнутые предметы в границах лобби (арены проверок не трогаем). */
    private void cleanLobbyItems(World w) {
        if (w == null) {
            return;
        }
        try {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                if (!(e instanceof org.bukkit.entity.Item)) {
                    continue;
                }
                Location l = e.getLocation();
                if (Math.abs(l.getBlockX()) <= 50
                        && Math.abs(l.getBlockZ() - LOBBY_Z) <= 60) {
                    e.remove();
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
        lobbyWorld = w;
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
                w.getBlockAt(cx, y + 4, LOBBY_Z + cz).setType(LANTERN_M, false);
            }
        }
        // Центр: золотая метка спавна + табличка с подсказкой.
        w.getBlockAt(0, y, LOBBY_Z).setType(Material.GOLD_BLOCK, false);
        try {
            Block signBlock = w.getBlockAt(0, y + 1, LOBBY_Z + 3);
            signBlock.setType(SIGN_M, false);
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
                w.getBlockAt(px, y + 4, pz).setType(LANTERN_M, false);
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
            w.getBlockAt(bx, y, bz).setType(me.vorchun.registerplugin.util.ItemTag.mat("POLISHED_BLACKSTONE", Material.STONE), false);
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
            w.getBlockAt(x, py + 1, pz + gap + 1).setType(LANTERN_M, false);
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
            String t = toBarText("&eВ очереди: &f" + queue.size() + " &7чел.");
            if (!t.equals(lastHoloText)) {
                lastHoloText = t;
                hologram.setCustomName(t);
            }
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
        watchdog = Scheduler.runSyncTimer(plugin, this::watchdogTick, 60L, 1200L);
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
        purgeOffline();
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

    /**
     * Раз в минуту: per-UUID кэши (цель BLOCK, чат-фильтр, кит, кнопка
     * скорости) чистим от вышедших игроков. На offline-сервере у каждого
     * бота свой UUID — без чистки карты росли весь аптайм.
     */
    private void purgeOffline() {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        purgeOffline(lastTargets.keySet(), seen);
        purgeOffline(chatLastAt.keySet(), seen);
        purgeOffline(chatLastMsg.keySet(), seen);
        purgeOffline(kitLastOpen.keySet(), seen);
        purgeOffline(speedCooldown.keySet(), seen);
        purgeOffline(kitInvs.keySet(), seen);
        purgeOffline(admittedNoCheck, seen);
        purgeOffline(pendingEntryTarget.keySet(), seen);
        for (Iterator<Map.Entry<UUID, CheckState>> it = queueStash.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CheckState> e = it.next();
            if (Bukkit.getPlayer(e.getKey()) == null) {
                it.remove();
                orphanStash(e.getKey(), e.getValue());
            }
        }
        long now = System.currentTimeMillis();
        pvpPairAt.values().removeIf(t -> now - t > PVP_PAIR_COOLDOWN_MS);
    }

    private static void purgeOffline(java.util.Set<UUID> keys, java.util.Set<UUID> online) {
        for (Iterator<UUID> it = keys.iterator(); it.hasNext(); ) {
            UUID u = it.next();
            if (online.contains(u)) {
                continue;
            }
            if (Bukkit.getPlayer(u) == null) {
                it.remove();
            } else {
                online.add(u);
            }
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
        keepChunk(st.world, st.arenaX, st.arenaZ);
        keepChunk(st.world, st.arenaX, st.arenaZ - 16);
        if (st.world == null) {
            return;
        }
        // Стартовая платформа — только твёрдый блок: пакетная проверка
        // считает падение по высоте/формуле, а «особые» блоки (паутина)
        // заставляли бы игрока тонуть без onGround — лишняя путаница.
        buildPlatform(st, randomSolidBlock());
    }

    /**
     * Путь для этапа BLOCK: СЛУЧАЙНЫЙ зигзаг (у каждого игрока свой), длиной
     * block_path_length, шириной в 1 блок — бот, идущий напрямик, сорвётся.
     * В конце пути — целевой блок, у старта — сундук с инструментом.
     */
    private void buildBlockPath(UUID uuid, CheckState st) {
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
        st.targetBlock = randomTargetBlock(uuid);
        int[] end = st.pathPoints.get(st.pathPoints.size() - 1);
        w.getBlockAt(end[0], y + 1, end[1] - 1).setType(st.targetBlock, false);
        w.getBlockAt(end[0], y, end[1] - 1).setType(Material.STONE_BRICKS, false);
        st.pathPoints.add(new int[]{end[0], end[1] - 1});
        st.targetX = end[0];
        st.targetZ = end[1] - 1;
        // Сундуки-призраки прошлых проверок в зоне арены: клик по чужому
        // сундуку считался бы нарушением слот-лока. Сносим до постановки своего.
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -3; dz >= -(blockPathLength + 4); dz--) {
                    org.bukkit.block.Block stray = w.getBlockAt(
                            st.arenaX + dx, st.baseY + dy, st.arenaZ + dz);
                    if (stray.getType() == Material.CHEST) {
                        stray.setType(Material.AIR, false);
                    }
                }
            }
        }
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
                org.bukkit.inventory.Inventory inv = chest.getInventory();
                inv.setItem(10, makeTool(Material.GOLDEN_PICKAXE));
                inv.setItem(12, makeTool(Material.GOLDEN_AXE));
                inv.setItem(14, makeTool(Material.GOLDEN_SHOVEL));
                inv.setItem(16, makeTool(Material.SHEARS));
                chest.update(true, false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Спец-инструмент для этапа BLOCK — целевой блок без него не сломать. */
    private org.bukkit.inventory.ItemStack makeTool(Material mat) {
        if (mat == null) {
            mat = Material.GOLDEN_PICKAXE;
        }
        org.bukkit.inventory.ItemStack c = toolCache.get(mat);
        if (c == null) {
            c = buildTool(mat);
            toolCache.put(mat, c);
        }
        return c.clone();
    }

    private org.bukkit.inventory.ItemStack buildTool(Material mat) {
        org.bukkit.inventory.ItemStack tool = new org.bukkit.inventory.ItemStack(mat);
        try {
            org.bukkit.inventory.meta.ItemMeta meta = tool.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(toBarText(blockToolName == null || blockToolName.isEmpty()
                        ? "&eКлюч арены" : blockToolName));
                meta.setUnbreakable(true);
                me.vorchun.registerplugin.util.ItemTag.mark(meta, lobbyLootKey);
                tool.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
        }
        return tool;
    }

    /** Инструменты этапа BLOCK прямо в инвентарь — сундук остаётся дублирующим. */
    private void giveBlockTools(Player player) {
        if (player == null) {
            return;
        }
        try {
            org.bukkit.inventory.PlayerInventory inv = player.getInventory();
            inv.addItem(makeTool(Material.GOLDEN_PICKAXE), makeTool(Material.GOLDEN_AXE),
                    makeTool(Material.GOLDEN_SHOVEL), makeTool(Material.SHEARS));
        } catch (Throwable ignored) {
        }
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
    private static final Material[] SOLID_PLATFORM = {
            Material.STONE, Material.GRASS_BLOCK, Material.OAK_PLANKS,
            Material.SANDSTONE, Material.SNOW_BLOCK, Material.DIRT,
            Material.NETHERRACK, Material.END_STONE
    };

    /** Твёрдый блок, которого ещё не было на этом этапе (все повторы — разные). */
    private Material uniqueSolidBlock(CheckState st) {
        if (st.fallUsedMats == null) {
            st.fallUsedMats = java.util.EnumSet.noneOf(Material.class);
        }
        List<Material> free = new ArrayList<>();
        for (Material m : SOLID_PLATFORM) {
            if (!st.fallUsedMats.contains(m)) {
                free.add(m);
            }
        }
        if (free.isEmpty()) {
            st.fallUsedMats.clear();
            free.addAll(java.util.Arrays.asList(SOLID_PLATFORM));
        }
        Material m = free.get(random.nextInt(free.size()));
        st.fallUsedMats.add(m);
        return m;
    }

    private Material randomSolidBlock() {
        return SOLID_PLATFORM[random.nextInt(SOLID_PLATFORM.length)];
    }

    private Material randomPlatformBlock() {
        if (!physicsBlocks) {
            return randomSolidBlock();
        }
        Material[] pool = {
                Material.STONE, Material.GRASS_BLOCK, Material.OAK_PLANKS,
                Material.SANDSTONE, Material.SNOW_BLOCK, Material.DIRT,
                Material.COBWEB, me.vorchun.registerplugin.util.ItemTag.mat("HONEY_BLOCK", Material.SLIME_BLOCK)
        };
        return pool[random.nextInt(pool.length)];
    }

    /** Цель по категориям инструмента: кирка/топор/лопата/ножницы. */
    private static final Material[] TARGET_POOL = {
            // кирка
            Material.COAL_ORE, Material.IRON_ORE, Material.GOLD_ORE, Material.STONE,
            // топор
            Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG,
            // лопата
            Material.DIRT, Material.SAND, Material.GRAVEL,
            // ножницы
            Material.COBWEB, Material.WHITE_WOOL, Material.OAK_LEAVES
    };

    /** Каким инструментом ломается цель — проверка «правильного» инструмента. */
    private static Material requiredToolFor(Material target) {
        switch (target) {
            case OAK_LOG: case BIRCH_LOG: case SPRUCE_LOG:
                return Material.GOLDEN_AXE;
            case DIRT: case SAND: case GRAVEL:
                return Material.GOLDEN_SHOVEL;
            case COBWEB: case WHITE_WOOL: case OAK_LEAVES:
                return Material.SHEARS;
            default:
                return Material.GOLDEN_PICKAXE;
        }
    }

    /** Случайная цель без повтора прошлой для этого игрока. */
    private Material randomTargetBlock(UUID uuid) {
        Material last = lastTargets.get(uuid);
        Material pick;
        int guard = 0;
        do {
            pick = TARGET_POOL[random.nextInt(TARGET_POOL.length)];
        } while (pick == last && ++guard < 10);
        lastTargets.put(uuid, pick);
        return pick;
    }

    private Location arenaSpawn(CheckState st) {
        return new Location(st.world, st.arenaX + 0.5, st.baseY + 1, st.arenaZ + 0.5, 180f, 0f);
    }

    /** Спавн основного мира — запасная точка выпуска из мира проверки. */
    private static Location mainSpawn() {
        try {
            List<World> ws = Bukkit.getWorlds();
            return ws.isEmpty() ? null : ws.get(0).getSpawnLocation();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Запомнить состояние игрока и перевести в режим, пригодный для проверок. */
    private void preparePlayer(Player player, CheckState st) {
        // Режим/полёт запоминаем ОДИН раз: повторный prepare (респавн на
        // арене) и полёт лобби-очереди не должны попасть в «прежнее» состояние
        saveMoveState(player, st);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0f);
        player.setVelocity(player.getVelocity().zero());
        {
            clearVision(player);
        }
        // Настоящие вещи уже в стэше (лобби) — в руках только лут лобби
        if (st.clearOnPrepare) {
            st.clearOnPrepare = false;
            dropLobbyCursor(player);
            clearInventory(player);
        }
        // В мире проверки игрок должен быть «пустым»: ни предметов, ни опыта.
        // Всё сохраняем и вернём после проверки (или при выходе/кике).
        stashInventory(player, st);
    }

    private void saveMoveState(Player player, CheckState st) {
        if (st.stateSaved) {
            return;
        }
        // AFK-spectator: «прежний» режим — тот, что был ДО spectator (F41)
        AfkService afk = afkService;
        GameMode prev = afk != null ? afk.spectatorPrevMode(player.getUniqueId()) : null;
        st.previousGameMode = prev != null ? prev : player.getGameMode();
        st.previousAllowFlight = player.getAllowFlight();
        st.previousFlying = player.isFlying();
        st.stateSaved = true;
    }

    /** Сохранить и очистить инвентарь/опыт (antibot.empty_inventory), один раз на стейт. */
    private static boolean stashEmpty(CheckState st) {
        for (org.bukkit.inventory.ItemStack[] arr : new org.bukkit.inventory.ItemStack[][]{
                st.savedInventory, st.savedArmor}) {
            if (arr != null) {
                for (org.bukkit.inventory.ItemStack it : arr) {
                    if (it != null && it.getType() != Material.AIR) {
                        return false;
                    }
                }
            }
        }
        return (st.savedOffhand == null || st.savedOffhand.getType() == Material.AIR)
                && st.savedLevel == 0 && st.savedExp == 0f;
    }

    private void stashInventory(Player player, CheckState st) {
        if (!emptyInventory || st.inventorySaved) {
            return;
        }
        stripLobbyLoot(player);
        st.savedInventory = player.getInventory().getContents().clone();
        st.savedArmor = player.getInventory().getArmorContents().clone();
        st.savedOffhand = player.getInventory().getItemInOffHand().clone();
        st.savedLevel = player.getLevel();
        st.savedExp = player.getExp();
        st.inventorySaved = true;
        // страховка от краша сервера: вещи живут только в памяти, пока
        // игрок на проверке/в очереди — копия на диск до возврата.
        // Пустой инвентарь (почти все новички) не пишем — нечего терять.
        if (!stashEmpty(st)) {
            persistStash(player, st);
        }
        clearInventory(player);
    }

    private static void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setLevel(0);
        player.setExp(0f);
        player.updateInventory();
    }

    /** Стэш лобби-очереди -> стейт проверки (processQueue -> beginCheck). */
    private static void adoptStash(CheckState st, CheckState qs) {
        if (qs.stateSaved) {
            st.previousGameMode = qs.previousGameMode;
            st.previousAllowFlight = qs.previousAllowFlight;
            st.previousFlying = qs.previousFlying;
            st.stateSaved = true;
        }
        if (qs.inventorySaved) {
            st.savedInventory = qs.savedInventory;
            st.savedArmor = qs.savedArmor;
            st.savedOffhand = qs.savedOffhand;
            st.savedLevel = qs.savedLevel;
            st.savedExp = qs.savedExp;
            st.inventorySaved = true;
            st.clearOnPrepare = true;
        }
    }

    /**
     * Снять игрока с лобби-очереди: вернуть стэш (вещи, опыт, полёт).
     * Без стэша (не лобби-режим) — только убрать полёт лобби.
     */
    private void restoreQueueStash(UUID uuid, boolean wasQueued) {
        CheckState qs = queueStash.remove(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) {
            // вышел, не получив вещи назад: файл стэша вернёт их при входе
            orphanStash(uuid, qs);
            return;
        }
        if (qs != null) {
            dropLobbyCursor(p);
            restoreInventory(p, qs);
        }
        if (qs != null || wasQueued) {
            resetLobbyFlight(p, qs);
        }
    }

    /**
     * Полёт лобби (queue_flight) не должен пережить очередь: возвращаем
     * тот, что был до неё (креатив/право fly), а не false для всех.
     */
    private void resetLobbyFlight(Player p, CheckState st) {
        if (!queueFlight || p == null) {
            return;
        }
        try {
            boolean allow;
            if (st != null && st.stateSaved) {
                allow = st.previousAllowFlight;
            } else {
                GameMode gm = p.getGameMode();
                allow = gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
            }
            p.setAllowFlight(allow);
            p.setFlying(allow && st != null && st.previousFlying);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Лут лобби на курсоре и открытые окна (набор/сундук) — до возврата
     * вещей: иначе при закрытии окна (телепорт) предмет с курсора падал
     * в уже восстановленный инвентарь в обход всех strip-проверок.
     */
    private void dropLobbyCursor(Player p) {
        if (p == null) {
            return;
        }
        try {
            if (isLobbyLoot(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
            }
            org.bukkit.inventory.InventoryView v = p.getOpenInventory();
            if (v != null && v.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
                    && v.getType() != org.bukkit.event.inventory.InventoryType.CREATIVE) {
                p.closeInventory();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Телепорт при снятии/выпуске. В onDisable шедулер задачи уже не
     * принимает (IllegalPluginAccessException обрывал shutdown и терял
     * инвентари) — там телепортируем сразу, мы на main-потоке.
     */
    private void teleportSafe(Player p, Location to) {
        if (p == null || to == null) {
            return;
        }
        if (shuttingDown || !plugin.isEnabled()) {
            try {
                if (p.isOnline()) {
                    Compat.teleport(p, to);
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline()) {
                Compat.teleport(p, to);
            }
        });
    }

    // ---------- страховка стэша от краша сервера ----------
    // Пока игрок на проверке/в лобби-очереди, его вещи лежат только в
    // памяти, а ядро автосохранением пишет в playerdata ПУСТОЙ инвентарь.
    // Краш/kill -9 в этом окне = потеря вещей. Копия стэша на диске
    // (plugins/<plugin>/stash/<uuid>.yml) живёт до возврата вещей; все
    // записи/удаления — в одном потоке по порядку, не на main.

    private final java.util.concurrent.ExecutorService stashIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "VTRegister-antibot-stash");
                t.setDaemon(true);
                return t;
            });
    // uuid с файлом стэша, оставшимся от прошлого запуска (краш)
    private final java.util.Set<UUID> crashStash = ConcurrentHashMap.newKeySet();
    private volatile boolean crashStashScanned;

    private java.io.File stashFile(UUID u) {
        return new java.io.File(new java.io.File(plugin.getDataFolder(), "stash"), u + ".yml");
    }

    private static List<org.bukkit.inventory.ItemStack> stashList(org.bukkit.inventory.ItemStack[] arr) {
        List<org.bukkit.inventory.ItemStack> out = new ArrayList<>();
        if (arr != null) {
            for (org.bukkit.inventory.ItemStack it : arr) {
                out.add(it == null ? new org.bukkit.inventory.ItemStack(Material.AIR) : it.clone());
            }
        }
        return out;
    }

    private static org.bukkit.inventory.ItemStack[] stashArray(List<?> list) {
        if (list == null) {
            return null;
        }
        org.bukkit.inventory.ItemStack[] out = new org.bukkit.inventory.ItemStack[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object o = list.get(i);
            if (o instanceof org.bukkit.inventory.ItemStack
                    && ((org.bukkit.inventory.ItemStack) o).getType() != Material.AIR) {
                out[i] = (org.bukkit.inventory.ItemStack) o;
            }
        }
        return out;
    }

    private void persistStash(Player player, CheckState st) {
        if (shuttingDown || player == null) {
            return;
        }
        final String data;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    new org.bukkit.configuration.file.YamlConfiguration();
            y.set("inv", stashList(st.savedInventory));
            y.set("armor", stashList(st.savedArmor));
            y.set("offhand", st.savedOffhand);
            y.set("level", st.savedLevel);
            y.set("exp", (double) st.savedExp);
            data = y.saveToString();
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: копия стэша " + player.getName() + " не сохранена: " + t);
            return;
        }
        final java.io.File f = stashFile(player.getUniqueId());
        try {
            stashIo.execute(() -> {
                try {
                    java.io.File dir = f.getParentFile();
                    if (!dir.isDirectory()) {
                        dir.mkdirs();
                    }
                    java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(),
                            data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable t) {
                    plugin.getLogger().warning("AntiBot: копия стэша не записана: " + t);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Стэш остался без хозяина (игрок уже офлайн, вещи не вернули) —
     * файл на диске остаётся и вернёт вещи при следующем входе.
     */
    private void orphanStash(UUID u, CheckState st) {
        if (u != null && st != null && st.inventorySaved) {
            crashStash.add(u);
        }
    }

    private void dropStashFile(UUID u) {
        if (u == null) {
            return;
        }
        crashStash.remove(u);
        final java.io.File f = stashFile(u);
        try {
            stashIo.execute(() -> {
                try {
                    java.nio.file.Files.deleteIfExists(f.toPath());
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            try {
                java.nio.file.Files.deleteIfExists(f.toPath());
            } catch (Throwable ignored) {
            }
        }
    }

    /** Список файлов стэша от прошлого запуска — один раз при старте. */
    private void scanCrashStash() {
        if (crashStashScanned) {
            return;
        }
        crashStashScanned = true;
        try {
            java.io.File[] fs = new java.io.File(plugin.getDataFolder(), "stash").listFiles();
            if (fs == null) {
                return;
            }
            for (java.io.File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".yml")) {
                    continue;
                }
                try {
                    crashStash.add(UUID.fromString(n.substring(0, n.length() - 4)));
                } catch (Throwable ignored) {
                }
            }
            if (!crashStash.isEmpty()) {
                plugin.getLogger().warning("AntiBot: найдено " + crashStash.size()
                        + " стэш(ей) инвентаря после аварийной остановки — вернём при входе игроков.");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Вход игрока: остался стэш от краша — вернуть вещи. Только если
     * сейчас в инвентаре пусто (кроме лута лобби): иначе ядро не успело
     * записать пустой инвентарь, вещи и так на месте — возврат был бы
     * дюпом. Файл удаляется в любом случае. Вызывать ДО beginCheck.
     */
    public void recoverStash(Player p) {
        if (p == null || crashStash.isEmpty()) {
            return;
        }
        UUID u = p.getUniqueId();
        if (!crashStash.remove(u) || queueStash.containsKey(u) || checks.containsKey(u)
                || entryStates.containsKey(u)) {
            return;
        }
        java.io.File f = stashFile(u);
        try {
            if (f.isFile() && inventoryEmptyForRecover(p)) {
                org.bukkit.configuration.file.YamlConfiguration y =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                org.bukkit.inventory.ItemStack[] inv = stashArray(y.getList("inv"));
                org.bukkit.inventory.ItemStack[] armor = stashArray(y.getList("armor"));
                org.bukkit.inventory.ItemStack off = y.getItemStack("offhand");
                if (inv != null) {
                    p.getInventory().setContents(inv);
                }
                if (armor != null) {
                    p.getInventory().setArmorContents(armor);
                }
                p.getInventory().setItemInOffHand(off);
                if (p.getLevel() == 0 && p.getExp() == 0f) {
                    p.setLevel(y.getInt("level", 0));
                    p.setExp((float) y.getDouble("exp", 0.0));
                }
                p.updateInventory();
                plugin.getLogger().warning("AntiBot: " + p.getName()
                        + " — инвентарь восстановлен из стэша после аварийной остановки.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: стэш " + p.getName() + " не восстановлен: " + t);
        }
        dropStashFile(u);
    }

    private boolean inventoryEmptyForRecover(Player p) {
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR && !isLobbyLoot(it)) {
                return false;
            }
        }
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getArmorContents()) {
            if (it != null && it.getType() != Material.AIR && !isLobbyLoot(it)) {
                return false;
            }
        }
        return true;
    }

    /** Вернуть предметы и опыт, сохранённые при входе в мир проверки. */
    private void restoreInventory(Player player, CheckState st) {
        if (st == null || !st.inventorySaved || player == null) {
            return;
        }
        if (!player.isOnline()) {
            // уже офлайн — вещи вернёт файл стэша при следующем входе
            orphanStash(player.getUniqueId(), st);
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
            // вещи на месте — страховочный файл стэша больше не нужен
            dropStashFile(player.getUniqueId());
        } catch (Throwable ignored) {
        }
        st.inventorySaved = false;
    }

    /**
     * Страховка от «тёмного мира»: снимаем BLINDNESS/DARKNESS и даём
     * NIGHT_VISION — даже если арена оказалась в ночном fallback-мире,
     * игрок видит. NV снимается в restorePlayer при выпуске.
     */
    private static void clearVision(Player p) {
        if (p == null) {
            return;
        }
        Compat.clearAuthDarkness(p); // дёшево: снимает, только если эффект висит
        try {
            if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION)) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.NIGHT_VISION,
                        Integer.MAX_VALUE, 0, true, false, false));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Вернуть игроку его режим/полёт/предметы после проверки. */
    private void restorePlayer(Player player, CheckState st) {
        try {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION);
        } catch (Throwable ignored) {
        }
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
            if ((queueInfo.containsKey(uuid) || entryInfo.containsKey(uuid)) && queueMode == 2) {
                Scheduler.runAtEntityLater(plugin, player, () -> {
                    if (player.isOnline() && (queueInfo.containsKey(uuid) || entryInfo.containsKey(uuid))) {
                        teleportService.authorizeTeleport(uuid);
                        Compat.teleport(player, lobbySpawn(player.getWorld()));
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
                Compat.teleport(player, arenaSpawn(st));
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
        clearStageExtras(st);      // стена/знак пазла и путь — не блокируют дальше
        st.puzzleFrameId = null;
        st.captchaAttempts = 0;
        st.slotsDone = 0;
        st.preForceSlot = -1;
        st.relockHits = 0;
        st.joltConfirmed = false;
        st.lastSlot = -1;
        st.forcedSlot = -1;
        st.forceAttempts = 0;
        st.awaitingForceFollowUp = false;
        st.blockBroken = false;
        st.chestOpened = false;
        st.chestClosed = false;
        st.blockMistakes = 0;
        st.physicsDone = 0;
        st.fallPlan = buildFallPlan(physicsRepetitions);
        st.fallUsedMats = null;
        st.fallKind = FK_NORMAL;
        st.launchAt = 0;
        st.launchRose = false;
        st.launchRetries = 0;
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
        st.sameDeltaDeg = 0;
        st.snapStreak = 0;
        st.prevSnapDelta = Float.NaN;
        st.cameraHintSent = false;
        st.fallTrajFails = 0;
        st.puzzleCells = null;
        st.puzzleCellDrawn = null;
        st.lastPuzzleClickAt = 0L;
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
        st.mathExpr = null;
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
                // AIR_CAPTCHA ставит игрока в воздух сам — этот телепорт (он
                // выполняется тиком позже) вернул бы его на арену
                if (player.isOnline() && checks.containsKey(uuid)
                        && getCurrentStage(uuid) != Stage.AIR_CAPTCHA) {
                    Compat.teleport(player, stand);
                    player.setFallDistance(0f);
                }
            });
        }

        stageHint(player, st, stage);
        switch (stage) {
            case FALL:
                sendMessage(player, "antibot_stage_fall", physicsRepetitions);
                // Пакетная проверка падения (Netty, Limbo-стиль): телепорт
                // в воздух + AcceptTeleportation + замер dy по формуле
                // v=(v-0.08)*0.98. Не сработала инъекция — legacy-платформа.
                if (tryFallPackets(player, st)) {
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
                // код на карте — в чат/титл его текстом НЕ пишем (иначе
                // бот читает его регуляркой, карта ничего не добавляла)
                st.captchaOnMap = mapCaptcha && giveCaptchaMap(player, st);
                sendCaptcha(player, st);
                break;
            case CLICK:
                st.clickToken = generateToken();
                st.clickDecoys = null;
                st.clickPrompts = 0;
                st.clickDecoyHits = 0;
                st.clickWrong = 0;
                sendClickPrompt(player, st, true);
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
                buildBlockPath(player.getUniqueId(), st);
                giveBlockTools(player);
                sendMessage(player, "antibot_stage_block", blockPathLength);
                if (blockToolChest && blockChestRequired) {
                    // Отдельный ключ: у старых серверов текст antibot_stage_block
                    // не обновляется сам, а про сундук игрок должен узнать
                    sendMessage(player, "antibot_block_chest_hint", 0);
                    sendTitle(player, "antibot_block_chest_title", "antibot_block_chest_subtitle");
                }
                break;
            case AIR_CAPTCHA:
                startAirCaptcha(player, st);
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
        Compat.teleport(p, loc);
    }

    /**
     * Старт пакетного повтора FALL. Любое исключение инъекции (нет
     * "packet_handler" в pipeline, чужой инжектор) = пакетный режим
     * сломан: пишем в лог один раз и дальше только legacy. Этап при
     * этом НЕ засчитывается и игрок не остаётся без задачи.
     * @return true — пакетная сессия запущена
     */
    private boolean tryFallPackets(Player p, CheckState st) {
        int kind = fallKindFor(st);
        if (fallSmartPlan && (kind == FK_WEB || kind == FK_LAUNCH)) {
            // паутину и подброс проверяем по событиям Bukkit (пакетная проверка
            // умеет только свободное падение)
            return false;
        }
        FallPacketCheck fp = fallPackets;
        if (fp == null || fallPacketsBroken || st.fallLegacyOnly || st.world == null || fp.isBroken()) {
            return false;
        }
        try {
            return fp.start(p, st.world, st.arenaX + 0.5, st.baseY + 1, st.arenaZ + 0.5, st.bedrock);
        } catch (Throwable t) {
            disableFallPackets("инъекция: " + t);
            return false;
        }
    }

    /**
     * Выключить пакетный FALL. why != null — сбой инфраструктуры (лог один
     * раз, до reload пакетный режим не пробуем); null — выключен конфигом.
     * Идущие пакетные сессии переводятся на legacy-повтор: колбэка от них
     * уже не будет, а висеть до таймаута этапа нельзя.
     */
    private void disableFallPackets(String why) {
        FallPacketCheck fp = fallPackets;
        fallPackets = null;
        if (why != null && !fallPacketsBroken) {
            fallPacketsBroken = true;
            plugin.getLogger().warning("AntiBot: пакетная проверка падения отключена до reload ("
                    + why + ") — этап FALL проверяется по событиям движения Bukkit");
        }
        if (fp != null) {
            try {
                fp.stopAll();
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<UUID, CheckState> e : checks.entrySet()) {
            CheckState st = e.getValue();
            if (!st.fallPacketMode) {
                continue;
            }
            st.fallPacketMode = false;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                fallBackToLegacy(p, st);
            }
        }
    }

    /**
     * Текущий повтор FALL переходит на legacy-платформу (события Bukkit).
     * Дедлайн этапа продлеваем: время съела пакетная сессия, а сбой
     * инфраструктуры — не вина игрока. Повтор — через 12 тиков, чтобы
     * телепорт на платформу дошёл раньше подброса.
     */
    /**
     * Пакетная проверка не дала результата, а игрок ещё в воздухе над
     * ареной — переводим текущее падение в legacy-замер с текущей высоты.
     * @return false — игрок уже внизу/не над ареной: нужен новый подброс
     */
    private boolean continueLegacyInAir(Player p, CheckState st) {
        Location l = p.getLocation();
        double h = l.getY() - (st.baseY + 1);
        if (st.world == null || l.getWorld() != st.world || h < 2.5
                || Math.abs(l.getX() - (st.arenaX + 0.5)) > 4.5
                || Math.abs(l.getZ() - (st.arenaZ + 0.5)) > 4.5) {
            return false;
        }
        st.fallLegacyOnly = true;
        st.fallKind = FK_NORMAL;
        st.tossHeight = (int) Math.floor(h);
        st.fallStartY = l.getY();
        st.fallStartedAt = System.currentTimeMillis();
        st.landingMinMs = Math.max(150L, (long) (h * 40L));
        st.awaitingBounce = false;
        st.enteredCobweb = false;
        st.fallSamples = 0;
        st.fallMaxStep = 0;
        st.fallLastStep = 0;
        st.fallAccel = 0;
        st.stageDeadline = Math.max(st.stageDeadline, System.currentTimeMillis() + 15_000L);
        return true;
    }

    private void fallBackToLegacy(Player p, CheckState st) {
        st.fallLegacyOnly = true;
        st.stageDeadline = System.currentTimeMillis()
                + stageTimeouts.getOrDefault(Stage.FALL, 20) * 1000L;
        final UUID uuid = p.getUniqueId();
        authorizedTeleport(p, arenaSpawn(st));
        p.setFallDistance(0f);
        Scheduler.runAtEntityLater(plugin, p, () -> {
            if (p.isOnline() && checks.get(uuid) == st && !st.fallPacketMode
                    && getCurrentStage(uuid) == Stage.FALL) {
                startFallRep(p, st);
            }
        }, 12L);
    }

    /**
     * Коллбек пакетной проверки падения (приходит уже на main-thread).
     * pass -> следующий повтор/этап. fail по ФИЗИКЕ (линейное падение,
     * onGround в воздухе, расхождение с формулой) -> кик. fail по
     * инфраструктуре (таймаут подтверждения, тишина, нет трафика) — не
     * вина игрока: повтор уходит на legacy, кика и бесплатного прохода нет.
     */
    void onFallPacketResult(UUID uuid, boolean pass, String reason) {
        CheckState st = checks.get(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (st == null || p == null || !p.isOnline() || !st.fallPacketMode) {
            return;
        }
        st.fallPacketMode = false;
        if (!pass) {
            if (reason != null && FALL_BOT_REASONS.contains(reason)) {
                // Пакеты видны и физика неверна — перехват работает
                plugin.getLogger().info("AntiBot: fall-check failed для "
                        + p.getName() + " (" + reason + ")");
                failCheck(uuid, msg("antibot_failed_kick"));
                return;
            }
            plugin.getLogger().info("AntiBot: пакетный fall-check " + p.getName()
                    + " без результата (" + reason + ") — повтор через события Bukkit");
            // Перехват признан сломанным (хендлер не видит пакетов, accept не
            // распознан; «слепоту» FallPacketCheck ловит сам по PlayerMove) —
            // выключаем пакетный режим целиком: идущие сессии сразу уходят на
            // legacy, а не досиживают каждая свой таймаут. Счётчика таймаутов
            // нет: иначе бот, молча не подтверждая телепорт 3 раза, выключил
            // бы пакетную проверку для всего сервера.
            FallPacketCheck fp = fallPackets;
            if (FallPacketCheck.R_BROKEN.equals(reason) || (fp != null && fp.isBroken())) {
                disableFallPackets("перехват пакетов не работает");
            }
            // Игрок ещё падает — досчитываем ЭТО падение по событиям Bukkit,
            // без нового подброса (иначе повтор физики визуально дублируется)
            if (continueLegacyInAir(p, st)) {
                return;
            }
            fallBackToLegacy(p, st);
            return;
        }
        st.physicsDone++;
        if (st.physicsDone >= physicsRepetitions) {
            // Клиент висел в воздухе — возвращаем на арену перед следующим этапом
            authorizedTeleport(p, arenaSpawn(st));
            p.setFallDistance(0f);
            passStage(p);
            return;
        }
        sendMessage(p, "antibot_fall_next", physicsRepetitions - st.physicsDone);
        if (tryFallPackets(p, st)) {
            st.fallPacketMode = true;
        } else {
            // Канал сломался посреди этапа — не караем, но и не засчитываем:
            // оставшиеся повторы проходят через legacy-платформу.
            fallBackToLegacy(p, st);
        }
    }

    /** Сундук с инструментом этапа BLOCK — клик по нему разрешён. */
    public boolean isToolChestBlock(Player p, org.bukkit.block.Block b) {
        CheckState st = checks.get(p.getUniqueId());
        return st != null && st.toolChestLoc != null
                && b != null && st.toolChestLoc.equals(b.getLocation());
    }

    /** Watchdog: сундук инструмента цел и внутри лежит инструмент. */
    /** Имя целевого блока для сообщений — из lang (block_name_*), фолбэк enum. */
    private String blockDisplayName(Material m) {
        MessageService ms = messages();
        if (ms != null) {
            String n = ms.message("block_name_" + m.name(), new HashMap<>());
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** Имя нужного инструмента — из lang (tool_name_*), фолбэк enum. */
    private String toolDisplayName(Material m) {
        MessageService ms = messages();
        if (ms != null) {
            String n = ms.message("tool_name_" + m.name(), new HashMap<>());
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /**
     * Дроп целевого блока: ставим ровно на место сломанного с нулевой
     * скоростью — не улетает с пути в бездну (натуральный дроп выключаем
     * в слушателе через setDropItems(false)).
     */
    public void dropTargetReward(org.bukkit.block.Block block) {
        try {
            org.bukkit.entity.Item item = block.getWorld().dropItem(
                    block.getLocation().add(0.5, 0.3, 0.5),
                    new org.bukkit.inventory.ItemStack(block.getType()));
            item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            item.setPickupDelay(10);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Снести постройки завершённого этапа: стена пазла (любая сторона),
     * остатки знака/маркера, путь BLOCK, сундук и целевой блок.
     * Арена приватна — всё построено нами, чужое не заденет.
     */
    private void clearStageExtras(CheckState st) {
        clearAirCaptcha(st);
        if (st == null || st.world == null) {
            return;
        }
        World w = st.world;
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, st.arenaZ - 4)
                            .setType(Material.AIR, false);
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, st.arenaZ + 4)
                            .setType(Material.AIR, false);
                }
            }
            w.getBlockAt(st.arenaX - 2, st.baseY + 2, st.arenaZ - 3)
                    .setType(Material.AIR, false);
            w.getBlockAt(st.arenaX, st.baseY + 2, st.arenaZ - 3)
                    .setType(Material.AIR, false);
            w.getBlockAt(st.arenaX - 2, st.baseY + 2, st.arenaZ - 4)
                    .setType(Material.AIR, false);
            if (st.pathPoints != null) {
                for (int[] pp : st.pathPoints) {
                    w.getBlockAt(pp[0], st.baseY, pp[1]).setType(Material.AIR, false);
                    w.getBlockAt(pp[0], st.baseY + 1, pp[1]).setType(Material.AIR, false);
                }
            }
            if (st.toolChestLoc != null) {
                w.getBlockAt(st.toolChestLoc).setType(Material.AIR, false);
                w.getBlockAt(st.toolChestLoc.getBlockX(), st.baseY,
                        st.toolChestLoc.getBlockZ()).setType(Material.AIR, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureToolChest(CheckState st) {
        if (st == null || st.toolChestLoc == null || st.world == null) {
            return;
        }
        if (!st.world.isChunkLoaded(st.toolChestLoc.getBlockX() >> 4,
                st.toolChestLoc.getBlockZ() >> 4)) {
            return;
        }
        org.bukkit.block.Block b = st.toolChestLoc.getBlock();
        if (b.getType() != Material.CHEST) {
            b.setType(Material.CHEST, false);
        }
        try {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
            org.bukkit.inventory.Inventory inv = chest.getInventory();
            Material[] tools = {Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
                    Material.GOLDEN_SHOVEL, Material.SHEARS};
            int[] slots = {10, 12, 14, 16};
            boolean dirty = false;
            for (int i = 0; i < tools.length; i++) {
                org.bukkit.inventory.ItemStack cur = inv.getItem(slots[i]);
                if (cur == null || cur.getType() != tools[i]) {
                    inv.setItem(slots[i], makeTool(tools[i]));
                    dirty = true;
                }
            }
            if (dirty) {
                chest.update(true, false);
            }
        } catch (Throwable t) {
            if (!st.toolChestWarned) {
                st.toolChestWarned = true;
                plugin.getLogger().warning("AntiBot: сундук инструментов не наполнен: " + t);
            }
        }
    }

    /** Наполнить сундук инструментов прямо при открытии (страховка к тикеру). */
    public void ensureToolChestNow(Player p) {
        if (p != null) {
            ensureToolChest(checks.get(p.getUniqueId()));
        }
    }

    /** Верхний инвентарь — сундук инструмента этапа BLOCK. */
    public boolean isToolChestTop(Player p, org.bukkit.inventory.Inventory top) {
        CheckState st = checks.get(p.getUniqueId());
        if (st == null || st.toolChestLoc == null || top == null) {
            return false;
        }
        // == на CraftInventory не работает: getState() каждый раз даёт новый
        // wrapper — сравниваем по координатам блока сундука.
        try {
            Location loc = top.getLocation();
            return loc != null && loc.equals(st.toolChestLoc);
        } catch (Throwable t) {
            return false;
        }
    }

    private void startFallRep(Player player, CheckState st) {

        UUID uuid = player.getUniqueId();
        int kind = fallSmartPlan ? fallKindFor(st) : -1;
        st.fallKind = kind < 0 ? FK_NORMAL : kind;
        if (kind == FK_LAUNCH) {
            startLaunchRep(player, st);
            return;
        }
        buildPlatform(st, kind == FK_WEB ? Material.COBWEB
                : kind < 0 ? randomPlatformBlock() : uniqueSolidBlock(st));
        int height = fallMinHeight + random.nextInt(Math.max(1, fallMaxHeight - fallMinHeight + 1));
        if (kind == FK_HIGH) {
            // Высокое падение: клиент обязан разогнаться (шаг за тик растёт
            // до ~1.2 блока) — «ровное» опускание бота здесь видно лучше всего
            height = Math.min(30, fallMaxHeight + 6 + random.nextInt(5));
        }
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
            Compat.teleport(player, target);
            player.setFallDistance(0f);
            st.tpTarget = target.clone();
            st.tpRetries = 3;
            // Траектория нового повтора — с нуля
            st.fallSamples = 0;
            st.fallMaxStep = 0;
            st.fallLastStep = 0;
            st.fallAccel = 0;
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
                Compat.teleport(player, st.tpTarget);
                player.setFallDistance(0f);
                return true;
            } else {
                st.tpTarget = null;
            }
        }
        // Пакетная проверка падения: мир/фриз не трогаем — валидирует
        // FallPacketCheck на уровне пакетов, результат придёт коллбеком.
        if (st.fallPacketMode) {
            // Живость хендлера: сервер движение видит, а пакетов нет — «слепой»
            FallPacketCheck fp = fallPackets;
            if (fp != null) {
                fp.onBukkitMove(player.getUniqueId());
            }
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
                if (st.fallKind == FK_LAUNCH) {
                    onLaunchMove(player, st, to);
                    return true;
                }
                double y = to.getY();
                int floor = st.baseY;
                // Траектория: Paper шлёт PlayerMoveEvent на каждый пакет со
                // сдвигом >1/16 блока — при падении это каждый тик. Копим
                // снижающиеся сэмплы, макс. шаг и рост шага (ускорение).
                // Одна позиция «сразу на пол» или прыжок после 8 с тишины
                // физику не пройдут, сколько бы времени ни прошло.
                if (!st.awaitingBounce && y < from.getY() - 0.001) {
                    double step = from.getY() - y;
                    st.fallSamples++;
                    if (step > st.fallMaxStep) {
                        st.fallMaxStep = step;
                    }
                    if (step > st.fallLastStep + 0.005) {
                        st.fallAccel++;
                    }
                    st.fallLastStep = step;
                }
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
                    if (onPlatform && !fallTrajectoryOk(st)) {
                        // Стоит на платформе, но падения не было (молчал 8 с
                        // и прыгнул вниз одним пакетом) — повтор не засчитан
                        onFallTrajectoryBad(player, st);
                    } else if (onPlatform) {
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
                        // До отскока — само падение должно быть настоящим
                        if (!fallTrajectoryOk(st)) {
                            onFallTrajectoryBad(player, st);
                            return true;
                        }
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
                    float delta = rotDelta(yaw, pitch, st);
                    // Эвристики CAMERA НЕ кикают (D7): контроллер/стик и
                    // Bedrock крутят с постоянной скоростью — от спин-бота
                    // их не отличить. «Роботизированный» поворот (длинная
                    // серия одинаковых дельт или серия одинаковых snap-рывков)
                    // просто не засчитывается в обороты: бот упрётся в таймаут
                    // этапа, человек получит подсказку и докрутит как угодно.
                    boolean robotic = linearityHit(st, delta);
                    // Snap: ПОДРЯД одинаковые резкие рывки ≥snap_degrees.
                    // Быстрый свайп мышью даёт разные дельты — стрик рвётся.
                    if (delta >= cameraSnapDeg && !st.bedrock) {
                        if (!Float.isNaN(st.prevSnapDelta)
                                && Math.abs(delta - st.prevSnapDelta) < 1.0f) {
                            st.snapStreak++;
                        } else {
                            st.snapStreak = 0;
                        }
                        st.prevSnapDelta = delta;
                        if (st.snapStreak > cameraSnapMax) {
                            robotic = true;
                        }
                    } else {
                        st.snapStreak = 0;
                        st.prevSnapDelta = Float.NaN;
                    }
                    if (robotic) {
                        if (!st.cameraHintSent) {
                            st.cameraHintSent = true;
                            sendMessage(player, "antibot_camera_uneven", 0);
                        }
                        st.lastYaw = yaw;
                        st.lastPitch = pitch;
                        return true;
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
                // Позиция заморожена. Самостоятельный поворот камеры (>10° —
                // порог события Bukkit) — признак живого клиента. Эхо нашего
                // jolt-телепорта сюда не приходит вовсе: internalTeleport
                // выставляет lastYaw = цели, подтверждение не даёт события —
                // его ловит joltAccepted() по полю PlayerConnection.
                if (to.getYaw() != from.getYaw() || to.getPitch() != from.getPitch()) {
                    st.joltAcks++;
                }
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                return true;
            }
            case AIR_CAPTCHA: {
                onAirMove(player, st, from, to);
                return true;
            }
            case BLOCK: {
                // Линейность прицела и на этапе ломания: спин-бот идёт путь,
                // вращая прицел с идеально ровной дельтой каждый пакет.
                // Кик — только за ДЛИННУЮ серию (linearity_streak событий и
                // ≥360° суммарно) одинаковых нетривиальных дельт: поворот
                // стиком на зигзаге пути короче, ходьба без поворота серию
                // рвёт, Bedrock не судим. Snap тут не ловим: резкие повороты
                // на зигзаг-пути законны.
                float yaw = to.getYaw();
                float pitch = to.getPitch();
                if (!Float.isNaN(st.lastYaw)
                        && linearityHit(st, rotDelta(yaw, pitch, st))) {
                    plugin.getLogger().info("AntiBot: линейный прицел на BLOCK у " + player.getName()
                            + " (" + st.sameDeltaStreak + " одинаковых дельт подряд)");
                    failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                    return true;
                }
                st.lastYaw = yaw;
                st.lastPitch = pitch;
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

    /**
     * Поворот прицела с прошлого события (yaw+pitch, градусы). yaw у
     * Java-клиента непрерывный (может уйти за ±360) — берём кратчайший
     * угол, иначе при обороте за событие дельта уходила в минус.
     */
    private static float rotDelta(float yaw, float pitch, CheckState st) {
        float dYaw = Math.abs(((yaw - st.lastYaw) % 360f + 540f) % 360f - 180f);
        return dYaw + Math.abs(pitch - st.lastPitch);
    }

    /**
     * Серия одинаковых нетривиальных дельт прицела (спин-бот). Дельта
     * ниже кванта мыши или пакет без поворота серию рвёт; Bedrock
     * (тач/геймпад через Geyser) не судим вовсе.
     * @return true — серия ≥ linearity_streak событий и ≥360° суммарно
     */
    private boolean linearityHit(CheckState st, float delta) {
        if (!cameraLinearity || st.bedrock || delta < ROT_TRIVIAL_DEG) {
            st.sameDeltaStreak = 0;
            st.sameDeltaDeg = 0;
            st.prevDelta = Float.NaN;
            return false;
        }
        if (!Float.isNaN(st.prevDelta) && Math.abs(delta - st.prevDelta) < 0.001f) {
            st.sameDeltaStreak++;
            st.sameDeltaDeg += delta;
        } else {
            st.sameDeltaStreak = 0;
            st.sameDeltaDeg = 0;
        }
        st.prevDelta = delta;
        return st.sameDeltaStreak >= linearityStreak && st.sameDeltaDeg >= LINEARITY_MIN_DEG;
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
        if (!fallTrajectoryOk(st)) {
            // время выждано, но промежуточных позиций падения не было
            onFallTrajectoryBad(player, st);
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

    /**
     * Legacy FALL: похожа ли траектория повтора на реальное падение.
     * Живой клиент с высоты h ≥ 5 даёт 10+ событий движения, шаг за
     * событие растёт (dy·0.98+0.078) и не превышает терминальные 3.92.
     * Требования заниженные (половина высоты, 2 ускорения) — лаг и пинг
     * их не ломают, а «одна позиция на пол» и «молчу, потом прыгаю» — да.
     * Bedrock (Geyser) падает по своей физике — только число сэмплов.
     */
    private boolean fallTrajectoryOk(CheckState st) {
        int need = st.bedrock ? 3 : Math.max(4, st.tossHeight / 2);
        if (st.fallSamples < need || st.fallMaxStep > 4.0) {
            return false;
        }
        // Высокое падение (15+ блоков): живой клиент разгоняется до шага
        // ~1.2 за тик. Шаг < 0.8 на такой высоте = «спуск» без гравитации.
        if (st.fallKind == FK_HIGH && !st.bedrock && st.fallMaxStep < 0.8) {
            return false;
        }
        return st.bedrock || st.fallAccel >= 2;
    }

    // ── Умный план FALL: разные виды повторов ─────────────────────────────
    private static final int FK_NORMAL = 0;
    private static final int FK_HIGH = 1;
    private static final int FK_WEB = 2;
    private static final int FK_LAUNCH = 3;
    private volatile boolean fallSmartPlan = true;
    private volatile boolean fallLaunch = true;

    /**
     * План повторов: первый — обычное падение (разминка), остальные —
     * в случайном порядке высокое падение, паутина, подброс и обычные.
     * Каждый вид проверяет свою часть физики клиента — бот, заточенный
     * под один сценарий, на другом ломается.
     */
    private int[] buildFallPlan(int reps) {
        List<Integer> extra = new ArrayList<>();
        extra.add(FK_HIGH);
        if (physicsBlocks) {
            extra.add(FK_WEB);
        }
        if (fallLaunch) {
            extra.add(FK_LAUNCH);
        }
        List<Integer> rest = new ArrayList<>();
        for (int i = 1; i < reps; i++) {
            rest.add(i - 1 < extra.size() ? extra.get(i - 1) : FK_NORMAL);
        }
        Collections.shuffle(rest, random);
        int[] plan = new int[Math.max(1, reps)];
        plan[0] = FK_NORMAL;
        for (int i = 1; i < plan.length; i++) {
            plan[i] = rest.get(i - 1);
        }
        return plan;
    }

    private int fallKindFor(CheckState st) {
        if (!fallSmartPlan || st.fallPlan == null || st.fallPlan.length == 0) {
            return FK_NORMAL;
        }
        return st.fallPlan[Math.min(st.physicsDone, st.fallPlan.length - 1)];
    }

    /**
     * Подброс: игрок стоит на платформе, сервер даёт ему скорость вверх.
     * Живой клиент (любая версия, Bedrock через Geyser) отыгрывает её сам —
     * взлетает на ~2 блока и приземляется. Бот без физики стоит на месте.
     */
    private void startLaunchRep(Player player, CheckState st) {
        final UUID uuid = player.getUniqueId();
        buildPlatform(st, uniqueSolidBlock(st));
        st.tossHeight = 0;
        st.fallStartedAt = 0;
        st.launchAt = 0;
        st.launchRose = false;
        final Location stand = arenaSpawn(st);
        teleportService.authorizeTeleport(uuid);
        Scheduler.runAtEntity(plugin, player, () -> {
            if (!player.isOnline() || checks.get(uuid) != st) {
                return;
            }
            Compat.teleport(player, stand);
            player.setFallDistance(0f);
            sendMessage(player, "antibot_fall_launch", 0);
        });
        // Толчок — через секунду, когда игрок точно стоит на платформе
        Scheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline() || checks.get(uuid) != st || getCurrentStage(uuid) != Stage.FALL
                    || st.fallKind != FK_LAUNCH) {
                return;
            }
            st.launchBaseY = player.getLocation().getY();
            st.launchAt = System.currentTimeMillis();
            st.fallStartedAt = st.launchAt;
            final long at = st.launchAt;
            player.setFallDistance(0f);
            player.setVelocity(new org.bukkit.util.Vector(0, 0.9, 0));
            // Не поднялся за 3.5 с: лаг/потеря пакета — один повтор, потом — бот.
            // Проверка по таймеру, а не по движению: бот может не слать движение вовсе.
            Scheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline() || checks.get(uuid) != st || st.launchAt != at || st.launchRose
                        || getCurrentStage(uuid) != Stage.FALL) {
                    return;
                }
                if (++st.launchRetries > 1) {
                    plugin.getLogger().info("AntiBot: FALL-подброс не отыгран клиентом у " + player.getName());
                    failCheck(uuid, msg("antibot_failed_kick"));
                    return;
                }
                startLaunchRep(player, st);
            }, 70L);
        }, 20L);
    }

    /** LAUNCH: после толчка клиент обязан подняться и снова встать на платформу. */
    private void onLaunchMove(Player player, CheckState st, Location to) {
        double y = to.getY();
        if (y < st.baseY - 5) {
            st.voidFalls++;
            if (st.voidFalls > fallVoidMax) {
                failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
                return;
            }
            sendMessage(player, "antibot_fall_void", fallVoidMax - st.voidFalls + 1);
            startLaunchRep(player, st);
            return;
        }
        if (st.launchAt == 0) {
            return;
        }
        if (!st.launchRose) {
            if (y >= st.launchBaseY + (st.bedrock ? 0.5 : 1.0)) {
                st.launchRose = true;
            }
            return;
        }
        if (y <= st.baseY + 1.6 && (player.isOnGround()
                || System.currentTimeMillis() - st.launchAt > 2500L)) {
            st.launchAt = 0;
            st.physicsDone++;
            if (st.physicsDone >= physicsRepetitions) {
                passStage(player);
            } else {
                sendMessage(player, "antibot_fall_next", physicsRepetitions - st.physicsDone);
                startFallRep(player, st);
            }
        }
    }

    // ── Тайминг ответов: слишком быстро и «ровно» = бот ────────────────────
    private volatile boolean answerTimingEnabled = true;
    private volatile int answerMsPerChar = 120;
    private volatile int answerMinSamples = 3;
    private volatile int answerMaxStddevMs = 40;

    /**
     * Проверить ответ по времени: быстрее answer_timing.min_ms_per_char на
     * символ (человек так не печатает) или ответы с почти одинаковой
     * задержкой (разброс меньше max_stddev_ms на min_samples ответах — у
     * человека задержки гуляют на секунды). Учитываются только ответы
     * вводом/кликом (капча, пример, секрет, кнопка, пазл-слово, AIR).
     * @return true — бот, проверка уже провалена
     */
    private boolean answerIsBot(Player p, CheckState st, String input) {
        if (!answerTimingEnabled || st.promptShownAt <= 0) {
            return false;
        }
        long delay = System.currentTimeMillis() - st.promptShownAt;
        if (input != null && answerMsPerChar > 0) {
            int chars = input.trim().length();
            if (chars > 0 && delay < (long) chars * answerMsPerChar) {
                plugin.getLogger().info("AntiBot: " + p.getName() + " ввёл ответ слишком быстро ("
                        + delay + " мс на " + chars + " симв.)");
                failCheck(p.getUniqueId(), msg("antibot_kick_fast"));
                return true;
            }
        }
        if (st.answerDelays == null) {
            st.answerDelays = new ArrayList<>();
        }
        st.answerDelays.add(delay);
        int n = st.answerDelays.size();
        if (answerMaxStddevMs > 0 && n >= answerMinSamples) {
            double mean = 0;
            for (long d : st.answerDelays) {
                mean += d;
            }
            mean /= n;
            double var = 0;
            for (long d : st.answerDelays) {
                var += (d - mean) * (d - mean);
            }
            double sd = Math.sqrt(var / n);
            if (sd < answerMaxStddevMs) {
                plugin.getLogger().info("AntiBot: " + p.getName() + " отвечает с одинаковой задержкой ("
                        + Math.round(mean) + " мс ± " + Math.round(sd) + " мс на " + n + " ответах)");
                failCheck(p.getUniqueId(), msg("antibot_failed_kick"));
                return true;
            }
        }
        return false;
    }

    private void sendTitle(Player p, String titleKey, String subKey) {
        MessageService ms = messages();
        if (ms == null) {
            return;
        }
        try {
            Map<String, String> ph = new HashMap<>();
            CheckState st = checks.get(p.getUniqueId());
            ph.put("block", st != null && st.targetBlock != null ? blockDisplayName(st.targetBlock) : "?");
            String t = ms.message(p, titleKey, ph);
            String s = ms.message(p, subKey, ph);
            if (t != null && !t.isEmpty() && !t.contains(titleKey)) {
                p.sendTitle(t, s == null || s.contains(subKey) ? "" : s, 10, 60, 10);
            }
        } catch (Throwable ignored) {
        }
    }

    // ── AIR_CAPTCHA: игрок в воздухе крутит прицелом → код из блоков → ввод ──
    private volatile int airTurnDegrees = 150;
    private volatile int airCodeLength = 4;
    private volatile int airNoise = 6;
    private volatile boolean airTilt = true;
    // Блоки 1.14+: на 1.13 их нет — замена (иначе NoSuchFieldError при стройке)
    private static final Material LANTERN_M = me.vorchun.registerplugin.util.ItemTag.mat("LANTERN", Material.GLOWSTONE);
    private static final Material SIGN_M = me.vorchun.registerplugin.util.ItemTag.mat("OAK_SIGN",
            me.vorchun.registerplugin.util.ItemTag.mat("SIGN", Material.STONE));
    private volatile int airScale = 2;
    private volatile boolean airDiagonal = true;
    /** Контрастные к дневному небу цвета (белого/голубого нет — сливаются с небом). */
    private static final Material[] AIR_COLORS = {
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.LIME_CONCRETE,
            Material.GREEN_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE,
            Material.MAGENTA_CONCRETE, Material.BLACK_CONCRETE, Material.BROWN_CONCRETE};

    private void startAirCaptcha(Player player, CheckState st) {
        final UUID uuid = player.getUniqueId();
        clearAirCaptcha(st);
        st.airShown = false;
        st.airTurned = 0f;
        st.airCode = null;
        st.airAttempts = 0;
        st.lastYaw = Float.NaN;
        st.lastPitch = Float.NaN;
        World w = st.world;
        if (w == null) {
            passStage(player); // мира нет — сбой инфраструктуры, не караем
            return;
        }
        // Невидимый барьер под ногами: игрок «висит» в воздухе, fly-кика нет
        int bx = st.arenaX;
        int by = st.baseY + 9;
        int bz = st.arenaZ;
        w.getBlockAt(bx, by, bz).setType(Material.BARRIER, false);
        st.airPad = new int[]{bx, by, bz};
        Location stand = new Location(w, bx + 0.5, by + 1, bz + 0.5, 180f, 0f);
        teleportService.authorizeTeleport(uuid);
        Compat.teleport(player, stand);
        player.setFallDistance(0f);
        st.tpTarget = stand.clone();
        st.tpRetries = 3;
        sendMessage(player, "antibot_stage_air_captcha", airTurnDegrees);
    }

    /** Позиция заморожена, поворот камеры копится; «роботизированный» не считается. */
    private void onAirMove(Player player, CheckState st, Location from, Location to) {
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        if (st.airShown) {
            return;
        }
        float yaw = to.getYaw();
        float pitch = to.getPitch();
        if (!Float.isNaN(st.lastYaw)) {
            float delta = rotDelta(yaw, pitch, st);
            if (!linearityHit(st, delta)) {
                st.airTurned += delta;
            }
        }
        st.lastYaw = yaw;
        st.lastPitch = pitch;
        if (st.airTurned >= airTurnDegrees) {
            buildAirCaptcha(player, st);
        }
    }

    /**
     * Выложить код блоками впереди, на весь экран: крупные буквы (каждая
     * точка шрифта — квадрат scale×scale блоков), у каждой буквы свой цвет
     * (соседние не совпадают), строка идёт по случайной диагонали (вверх,
     * вниз или ровно) с «прыжками» букв, вокруг — одиночные блоки-шум.
     * Размер и расстояние до стены подбираются так, чтобы код занял экран
     * (обзор 70°), но не залез на соседнюю арену. ~150 блоков, без физики.
     */
    private void buildAirCaptcha(Player player, CheckState st) {
        World w = st.world;
        if (w == null || st.airPad == null) {
            return;
        }
        clearAirWall(st);
        String alphabet = me.vorchun.registerplugin.util.BlockFont.CHARS;
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < airCodeLength; i++) {
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        st.airCode = code.toString();
        st.airBlocks = new ArrayList<>();
        int n = code.length();
        int maxW = Math.max(12, arenaSpacing - 4); // не залезать на соседнюю арену
        int s = airScale;
        while (s > 1 && n * 3 * s + (n - 1) * (s >= 3 ? 2 : 1) > maxW) {
            s--; // не влезает — буквы мельче, зато стена ближе (см. dist)
        }
        int gap = s >= 3 ? 2 : 1;
        // «Кривые» промежутки — только если остаётся место
        int extraGap = airTilt && n * 3 * s + (n - 1) * (gap + 1) <= maxW ? 1 : 0;
        int width = n * 3 * s + (n - 1) * (gap + extraGap);
        int dir = airDiagonal ? random.nextInt(3) - 1 : 0; // -1 вниз, 0 ровно, 1 вверх
        int rise = s;                                       // подъём на букву
        int span = (n - 1) * rise * Math.abs(dir);
        int height = 5 * s + span + (airTilt ? 2 : 0);
        // Расстояние: текст ≈ 85% ширины и не выше 85% высоты экрана
        int dist = Math.max((int) Math.ceil(width / 2.1), (int) Math.ceil(height / 1.19));
        dist = Math.max(7, Math.min(20, dist));
        int wallZ = st.arenaZ - dist;
        double eyeY = st.airPad[1] + 1 + 1.62;
        int minOff = Math.min(0, dir * (n - 1) * rise);
        int maxOff = Math.max(0, dir * (n - 1) * rise);
        int top0 = (int) Math.round(eyeY + (5 * s - 1) / 2.0 - (minOff + maxOff) / 2.0);
        int slack = Math.max(0, (maxW - width) / 2);
        int x = st.arenaX - width / 2 + (slack > 0 ? random.nextInt(slack + 1) - slack / 2 : 0);
        int minX = x;
        java.util.Set<Long> used = new java.util.HashSet<>();
        int prevColor = -1;
        for (int k = 0; k < n; k++) {
            String[] g = me.vorchun.registerplugin.util.BlockFont.glyph(code.charAt(k));
            int ci;
            do {
                ci = random.nextInt(AIR_COLORS.length);
            } while (ci == prevColor);
            prevColor = ci;
            Material m = AIR_COLORS[ci];
            int top = top0 + dir * k * rise + (airTilt ? random.nextInt(3) - 1 : 0);
            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 3; c++) {
                    if (g[r].charAt(c) != '1') {
                        continue;
                    }
                    for (int sy = 0; sy < s; sy++) {
                        for (int sx = 0; sx < s; sx++) {
                            int bx = x + c * s + sx;
                            int by = top - r * s - sy;
                            w.getBlockAt(bx, by, wallZ).setType(m, false);
                            st.airBlocks.add(new int[]{bx, by, wallZ});
                            used.add(((long) bx << 32) ^ (by & 0xFFFFFFFFL));
                        }
                    }
                }
            }
            x += 3 * s + gap + (extraGap > 0 ? random.nextInt(2) : 0);
        }
        // Шум: одиночные блоки (у букв штрих — квадрат s×s), не вплотную к
        // буквам: человеку не мешают, «сканировать» стену построчно — мешают
        int noiseTop = top0 + maxOff + 2;
        int noiseH = height + 4;
        int noiseW = Math.max(1, x - gap - minX); // в пределах своей стены
        for (int i = 0, tries = 0; i < airNoise && tries < airNoise * 10; tries++) {
            int nx = minX + random.nextInt(noiseW);
            int ny = noiseTop - random.nextInt(noiseH);
            boolean near = false;
            for (int ox = -1; ox <= 1 && !near; ox++) {
                for (int oy = -1; oy <= 1 && !near; oy++) {
                    near = used.contains(((long) (nx + ox) << 32) ^ ((ny + oy) & 0xFFFFFFFFL));
                }
            }
            if (near) {
                continue;
            }
            Material m = s >= 2 ? AIR_COLORS[random.nextInt(AIR_COLORS.length)] : Material.LIGHT_GRAY_CONCRETE;
            w.getBlockAt(nx, ny, wallZ).setType(m, false);
            st.airBlocks.add(new int[]{nx, ny, wallZ});
            used.add(((long) nx << 32) ^ (ny & 0xFFFFFFFFL));
            i++;
        }
        st.airShown = true;
        st.promptShownAt = System.currentTimeMillis();
        sendMessage(player, "antibot_air_captcha_shown", airCodeLength);
    }

    public int submitAirCode(Player player, String input) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null) {
            return 0;
        }
        if (!st.airShown || st.airCode == null) {
            sendMessage(player, "antibot_air_turn_first", 0);
            return 0;
        }
        if (tooFast(st)) {
            failCheck(uuid, msg("antibot_kick_fast"));
            return 2;
        }
        if (answerIsBot(player, st, input)) {
            return 2;
        }
        String in = input == null ? "" : input.trim().replace(" ", "");
        if (st.airCode.equalsIgnoreCase(in)) {
            authorizedTeleport(player, arenaSpawn(st));
            player.setFallDistance(0f);
            clearAirCaptcha(st);
            passStage(player);
            return 0;
        }
        if (++st.airAttempts >= captchaAttempts) {
            return 2;
        }
        buildAirCaptcha(player, st); // новый код на том же месте
        return 1;
    }

    private void clearAirWall(CheckState st) {
        if (st == null || st.airBlocks == null || st.world == null) {
            return;
        }
        for (int[] b : st.airBlocks) {
            st.world.getBlockAt(b[0], b[1], b[2]).setType(Material.AIR, false);
        }
        st.airBlocks = null;
    }

    private void clearAirCaptcha(CheckState st) {
        if (st == null) {
            return;
        }
        clearAirWall(st);
        if (st.airPad != null && st.world != null) {
            st.world.getBlockAt(st.airPad[0], st.airPad[1], st.airPad[2]).setType(Material.AIR, false);
            st.airPad = null;
        }
    }

    /** Повтор без настоящего падения: первый раз — переподброс, второй — кик. */
    private void onFallTrajectoryBad(Player player, CheckState st) {
        st.awaitingBounce = false;
        if (++st.fallTrajFails >= 2) {
            plugin.getLogger().info("AntiBot: FALL без траектории падения у " + player.getName()
                    + " (сэмплов " + st.fallSamples + ", ускорений " + st.fallAccel
                    + ", макс. шаг " + String.format(java.util.Locale.ROOT, "%.2f", st.fallMaxStep) + ")");
            failCheck(player.getUniqueId(), msg("antibot_failed_kick"));
            return;
        }
        startFallRep(player, st);
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
            failCheck(uuid, msg("antibot_kick_fast"));
            return 2;
        }
        if (answerIsBot(player, st, input)) {
            return 2;
        }
        if (st.code.equalsIgnoreCase(input == null ? "" : input.trim())) {
            st.code = null;
            passStage(player);
            return 0;
        }
        int attempts = ++st.captchaAttempts;
        if (attempts >= captchaAttempts) {
            return 2;
        }
        st.code = generateCode();
        // код рисуется на карте — в чат/титл его текстом НЕ пишем
        st.captchaOnMap = mapCaptcha && giveCaptchaMap(player, st);
        sendCaptcha(player, st);
        return 1;
    }

    /**
     * Клик по кнопке (/rpverify <token>).
     * @return 0 — верно, 1 — неверный токен, 2 — нажата приманка (игрок уже уведомлён)
     */
    public int submitClickToken(Player player, String token) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.get(uuid);
        if (st == null || st.clickToken == null) {
            return 0;
        }
        if (minClickMs > 0 && st.promptShownAt > 0
                && System.currentTimeMillis() - st.promptShownAt < minClickMs) {
            failCheck(uuid, msg("antibot_kick_fast"));
            return 1;
        }
        if (answerIsBot(player, st, null)) {
            return 1;
        }
        String t = token == null ? "" : token.trim();
        if (st.clickDecoys != null && st.clickDecoys.contains(t)) {
            // Приманка: человек читает подпись («Я БОТ»/«ВЫЙТИ»), бот жмёт
            // первый попавшийся ClickEvent. Один промах прощаем — новые
            // токены и новый порядок кнопок; второй = бот.
            if (++st.clickDecoyHits >= 2) {
                failCheck(uuid, msg("antibot_failed_kick"));
                return 2;
            }
            MessageService ms = messages();
            String warn = ms == null ? null : ms.message(player, "antibot_click_decoy_hit", new HashMap<>());
            player.sendMessage(warn == null || warn.isEmpty()
                    ? "§cНе та кнопка — прочитай подписи и нажми «Я НЕ БОТ»." : warn);
            st.clickToken = generateToken();
            st.clickDecoys = null;
            sendClickPrompt(player, st, true);
            return 2;
        }
        if (!st.clickToken.equals(t)) {
            // подбор токена руками/скриптом — не бесконечно
            if (++st.clickWrong >= maxAttempts) {
                failCheck(uuid, msg("antibot_failed_kick"));
                return 2;
            }
            return 1;
        }
        st.clickToken = null;
        st.clickDecoys = null;
        passStage(player);
        return 0;
    }

    /** Этап BLOCK: игрок открыл сундук инструментов. */
    public void onToolChestOpen(Player p) {
        CheckState st = checks.get(p.getUniqueId());
        if (st != null && getCurrentStage(p.getUniqueId()) == Stage.BLOCK) {
            st.chestOpened = true;
        }
    }

    /** Этап BLOCK: закрыл сундук после открытия — шаг «открой и закрой» выполнен. */
    public void onToolChestClose(Player p, org.bukkit.inventory.Inventory top) {
        CheckState st = checks.get(p.getUniqueId());
        if (st == null || !st.chestOpened || st.chestClosed
                || getCurrentStage(p.getUniqueId()) != Stage.BLOCK || !isToolChestTop(p, top)) {
            return;
        }
        st.chestClosed = true;
        if (blockChestRequired) {
            sendMessage(p, "antibot_block_chest_done", 0);
        }
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
            // Сначала — открыть и закрыть сундук у старта (боту без GUI-логики
            // это лишний шаг, человеку — 2 секунды)
            if (blockToolChest && blockChestRequired && !(st.chestOpened && st.chestClosed)) {
                sendMessage(player, "antibot_block_chest_first", 0);
                return false;
            }
            // Если включён сундук с инструментом — ломать можно ТОЛЬКО им
            if (blockToolChest) {
                Material need = requiredToolFor(st.targetBlock);
                org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() != need) {
                    if (blockMistake(player, st)) {
                        return false;
                    }
                    sendMessage(player, "antibot_need_tool", 0);
                    return false;
                }
            }
            st.blockBroken = true;
            st.blocksTouched.add(new long[]{block.getX(), block.getY(), block.getZ()});
            return true;
        }
        if (blockMistake(player, st)) {
            return false;
        }
        // Подсказываем, ЧТО ломать — не чаще раза в 1.5 сек
        long nowMs = System.currentTimeMillis();
        if (nowMs - st.wrongBlockHintAt > 1500L) {
            st.wrongBlockHintAt = nowMs;
            sendMessage(player, "antibot_wrong_block", 0);
        }
        return false; // чужие блоки ломать нельзя
    }

    /**
     * Ошибка на этапе BLOCK (сломал не тот блок / ударил не тем инструментом).
     * @return true — лимит block_max_mistakes достигнут, проверка провалена
     */
    private boolean blockMistake(Player player, CheckState st) {
        if (blockMaxMistakes <= 0) {
            return false;
        }
        if (++st.blockMistakes >= blockMaxMistakes) {
            plugin.getLogger().info("AntiBot: " + player.getName() + " — ошибка на этапе BLOCK ("
                    + st.blockMistakes + "/" + blockMaxMistakes + ")");
            failCheck(player.getUniqueId(), msg("antibot_kick_mistake"));
            return true;
        }
        return false;
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
     * раз (телепорт с новым yaw/pitch). Живой клиент подтверждает каждый
     * телепорт (AcceptTeleportation) — это видно по полю ожидания в
     * PlayerConnection без Netty. Бот без клиента телепорт не подтвердит.
     * Самостоятельные повороты игрока (>10°) тоже засчитываются.
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
                // Перед новым рывком — подтвердил ли клиент прошлый
                pollJoltAccepted(player, st);
                teleportService.authorizeTeleport(uuid);
                Location loc = player.getLocation().clone();
                loc.setYaw(random.nextFloat() * 360f - 180f);
                loc.setPitch(random.nextFloat() * 60f - 30f);
                Compat.teleport(player, loc);
                st.joltSent++;
            }, i * interval);
        }
        st.joltDeadline = System.currentTimeMillis() + slotCameraJolts * interval * 50L + 2500L;
    }

    /** Отметить подтверждение последнего jolt-телепорта (или его недоступность). */
    private void pollJoltAccepted(Player player, CheckState st) {
        if (st.joltSent <= 0 || st.joltConfirmed) {
            return;
        }
        Boolean acc = teleportAccepted(player);
        // null — поле на этом ядре не найдено: тест недоступен, не караем
        if (acc == null || acc) {
            st.joltConfirmed = true;
        }
    }

    /** Живой клиент на рывки камеры ответил (подтверждение или поворот). */
    private boolean joltsAnswered(CheckState st) {
        return st.joltConfirmed || st.joltAcks >= slotCameraMinAcks;
    }

    // Подтверждение телепорта без Netty: CraftPlayer.getHandle() ->
    // playerConnection -> teleportPos (1.16) / awaitingPositionFromClient
    // (Mojang-маппинги). null в поле = клиент подтвердил последний телепорт.
    private static volatile boolean tpFieldsResolved;
    private static volatile java.lang.reflect.Method tpHandle;
    private static volatile java.lang.reflect.Field tpConn;
    private static volatile java.lang.reflect.Field tpAwait;

    /** @return TRUE — подтверждён, FALSE — ещё ждём, null — узнать нельзя. */
    private Boolean teleportAccepted(Player p) {
        try {
            if (!tpFieldsResolved) {
                resolveTpFields(p);
            }
            if (tpAwait == null) {
                return null;
            }
            Object conn = tpConn.get(tpHandle.invoke(p));
            return conn == null ? null : tpAwait.get(conn) == null;
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized void resolveTpFields(Player p) {
        if (tpFieldsResolved) {
            return;
        }
        try {
            java.lang.reflect.Method gh = p.getClass().getMethod("getHandle");
            Object handle = gh.invoke(p);
            java.lang.reflect.Field cf = findField(handle.getClass(),
                    new String[]{"playerConnection", "connection"},
                    new String[]{"PlayerConnection", "ServerGamePacketListenerImpl"});
            Object conn = cf == null ? null : cf.get(handle);
            java.lang.reflect.Field af = conn == null ? null : findField(conn.getClass(),
                    new String[]{"teleportPos", "awaitingPositionFromClient"},
                    new String[]{"Vec3D", "Vec3"});
            if (af != null) {
                tpHandle = gh;
                tpConn = cf;
                tpAwait = af;
            } else {
                plugin.getLogger().info("AntiBot: подтверждение телепорта на этом ядре не читается — "
                        + "рывки камеры SLOTS засчитываются без него");
            }
        } catch (Throwable t) {
            plugin.getLogger().info("AntiBot: подтверждение телепорта недоступно: " + t);
        } finally {
            tpFieldsResolved = true;
        }
    }

    /**
     * Поле по имени (вверх по иерархии), иначе ЕДИНСТВЕННОЕ поле класса
     * с типом из typeNames (обфусцированные имена 1.17+). Неоднозначно — null.
     */
    private static java.lang.reflect.Field findField(Class<?> c, String[] names, String[] typeNames) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (String n : names) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            java.lang.reflect.Field found = null;
            int hits = 0;
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String sn = f.getType().getSimpleName();
                for (String tn : typeNames) {
                    if (tn.equals(sn)) {
                        found = f;
                        hits++;
                        break;
                    }
                }
            }
            if (hits == 1) {
                found.setAccessible(true);
                return found;
            }
            if (hits > 1) {
                return null;
            }
        }
        return null;
    }

    /**
     * Этап SLOTS пройден по слотам — но сначала проверяем ответы на рывки
     * камеры. Если клиент не ответил ни на один рывок — это бот.
     */
    private void finishSlots(Player player, CheckState st) {
        pollJoltAccepted(player, st);
        if (slotCameraTest && st.joltSent > 0 && !joltsAnswered(st)) {
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
            sendMessage(player, puzzleFront ? "antibot_puzzle_front" : "antibot_puzzle_turn", 0);
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
        // Цели называем в задании по виду яйца, а не подписью на предмете:
        // имя «Забери меня!» бот читал из пакета слотов и кликал вслепую.
        // Нет нового lang-ключа (старый перевод) — оставляем ★-подпись,
        // иначе человек не поймёт задание.
        MessageService pms = messages();
        StringBuilder targets = new StringBuilder();
        for (Material m : new java.util.LinkedHashSet<>(puzzleRemove)) {
            if (targets.length() > 0) {
                targets.append(", ");
            }
            targets.append(eggDisplay(m));
        }
        Map<String, String> tph = new HashMap<>();
        tph.put("targets", targets.toString());
        tph.put("stage_num", String.valueOf(st.stageIndex + 1));
        tph.put("stage_total", String.valueOf(st.stages != null ? st.stages.size() : stageOrder.size()));
        String taskText = pms == null ? null : pms.message(player, "antibot_stage_puzzle_gui", tph);
        boolean starLabels = taskText == null || taskText.isEmpty();
        for (int i = 0; i < 27; i++) {
            Material m = layout.get(i);
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m);
            if (puzzleRemove.contains(m)) {
                if (starLabels) {
                    try {
                        org.bukkit.inventory.meta.ItemMeta im = it.getItemMeta();
                        if (im != null) {
                            im.setDisplayName(toBarText("&c★ Забери меня!"));
                            it.setItemMeta(im);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                st.puzzleRemoveSlots.add(i);
            }
            inv.setItem(i, it);
        }
        st.puzzlePlaced = removeCount;
        st.puzzleInv = inv;
        st.puzzleAwaitConfirm = false;
        st.lastPuzzleClickAt = 0L;
        if (starLabels) {
            sendMessage(player, "antibot_stage_puzzle", 0);
        } else {
            player.sendMessage(taskText);
        }
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
            World w = st.world;
            // MapView кэшируется по имени файла: рендер один раз на картинку,
            // нет новых saved-map на каждую проверку.
            org.bukkit.map.MapView view = puzzleViews.get(f.getName());
            if (view == null) {
                java.awt.image.BufferedImage raw = javax.imageio.ImageIO.read(f);
                final java.awt.image.BufferedImage img;
                if (raw != null && (raw.getWidth() != 128 || raw.getHeight() != 128)) {
                    img = new java.awt.image.BufferedImage(128, 128,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = img.createGraphics();
                    g2.drawImage(raw, 0, 0, 128, 128, null);
                    g2.dispose();
                } else {
                    img = raw;
                }
                view = Bukkit.createMap(w);
                view.getRenderers().forEach(view::removeRenderer);
                // Палитра конвертируется ОДИН раз при создании view 
                // render() потом только копирует байты, без matchColor на пиксель.
                final byte[] palette = fastPalette(img);
                view.addRenderer(new org.bukkit.map.MapRenderer() {
                    private boolean drawn;
                    @Override
                    @SuppressWarnings("deprecation")
                    public void render(org.bukkit.map.MapView mv, org.bukkit.map.MapCanvas canvas, Player p) {
                        if (drawn) {
                            return;
                        }
                        drawn = true;
                        for (int x = 0; x < 128; x++) {
                            for (int y = 0; y < 128; y++) {
                                canvas.setPixel(x, y, palette[x + y * 128]);
                            }
                        }
                    }
                });
                puzzleViews.put(f.getName(), view);
            }
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
        // rawSlot вне окна пазла (свой инвентарь 27..62, -999 мимо окна):
        // getItem бросил бы IndexOutOfBounds — клик просто поглощаем
        if (slot < 0 || slot >= inv.getSize() || st.puzzleRemoveSlots == null) {
            return true;
        }
        org.bukkit.inventory.ItemStack item = inv.getItem(slot);
        if (item == null || item.getType() == Material.AIR) {
            return true;
        }
        long now = System.currentTimeMillis();
        // Клик раньше min_answer_ms после открытия окна — человек ещё не
        // разглядел яйца. Не кикаем (мог щёлкать до открытия), не засчитываем.
        if (tooFast(st)) {
            return true;
        }
        // Клики чаще min_click_ms (бот щёлкает все слоты за тик) не
        // засчитываются — ни прогрессом, ни ошибкой (человек не наказан)
        if (minClickMs > 0 && st.lastPuzzleClickAt > 0L
                && now - st.lastPuzzleClickAt < minClickMs) {
            return true;
        }
        st.lastPuzzleClickAt = now;
        if (!st.puzzleRemoveSlots.contains(slot) || !puzzleRemove.contains(item.getType())) {
            // Клик по «нужному» — ошибка, перебор кликов больше не бесплатен
            st.puzzleWrong++;
            if (st.puzzleWrong >= puzzleMaxWrong) {
                failCheck(player.getUniqueId(), msg("antibot_kick_attempts"));
            } else {
                sendPuzzleWrong(player, st);
            }
            return true;
        }
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
        return true;
    }

    /** Название яйца для текста задания: CAT_SPAWN_EGG -> "cat". */
    private static String eggDisplay(Material m) {
        String n = m.name().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith("_spawn_egg")) {
            n = n.substring(0, n.length() - "_spawn_egg".length());
        }
        return tileDisplay(n.replace('_', ' '));
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
            failCheck(uuid, msg("antibot_kick_fast"));
            return true;
        }
        if (answerIsBot(player, st, null)) {
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
            failCheck(player.getUniqueId(), msg("antibot_kick_attempts"));
            return;
        }
        sendPuzzleWrong(player, st);
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
            int extras = Math.min(puzzleRemoveCount, 8);
            List<String> cells = new ArrayList<>(9);
            Map<String, Integer> counts = new HashMap<>();
            st.puzzleKeep = null;
            if (puzzleStyleKeep && puzzleTileNames.size() >= 2) {
                // «Оставь только X»: одно животное на все «правильные» клетки,
                // лишние — любые другие картинки (у каждого игрока свои)
                String keepName = puzzleTileNames.get(random.nextInt(puzzleTileNames.size()));
                List<String> others = new ArrayList<>(puzzleTileNames);
                others.remove(keepName);
                Collections.shuffle(others, random);
                for (int i = 0; i < extras; i++) {
                    String n = others.get(i % others.size());
                    cells.add(n);
                    counts.merge(n, 1, Integer::sum);
                }
                while (cells.size() < 9) {
                    cells.add(keepName);
                }
                st.puzzleKeep = keepName;
                return placePuzzleWall(player, st, cells, counts, extras);
            }
            // «Убери лишних»: «оставляемые» — тайлы, которых нет среди лишних
            // (раньше при случайных целях список был пуст и все клетки
            // становились свинками — одна «лишняя» свинья среди «правильных»)
            List<String> keep = new ArrayList<>(puzzleTileNames);
            keep.removeAll(puzzleRemoveNames);
            String single = puzzleSameTarget ? pickRemoveName() : null;
            // Изменяемый набор: singleton падал на add() и стена всегда
            // уходила в GUI-фолбэк при puzzle_same_target=true
            java.util.Set<String> usedExtra = new java.util.HashSet<>();
            if (single != null) {
                usedExtra.add(single);
            }
            for (int i = 0; i < extras; i++) {
                String n = single != null ? single : pickRemoveName(usedExtra);
                cells.add(n);
                usedExtra.add(n);
                counts.merge(n, 1, Integer::sum);
            }
            if (keep.isEmpty()) {
                keep.addAll(puzzleTileNames);
                keep.removeAll(usedExtra);
            }
            if (keep.isEmpty()) {
                keep.add("pig");
            }
            while (cells.size() < 9) {
                cells.add(keep.get(random.nextInt(keep.size())));
            }
            return placePuzzleWall(player, st, cells, counts, extras);
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: стена пазла не создана: " + t.getMessage());
            return false;
        }
    }

    /** Построить стену 3x3 и повесить рамки; counts — лишние по видам. */
    private boolean placePuzzleWall(Player player, CheckState st, List<String> cells,
                                    Map<String, Integer> counts, int extras) {
        try {
            Collections.shuffle(cells, random);

            World w = st.world;
            // puzzle_front=true: стена ПЕРЕД игроком (смотрит на -Z,
            // стена на arenaZ-4) — задание сразу перед прицелом.
            // false — стена позади (arenaZ+4): старый режим с оборотом.
            int wallZ = st.arenaZ + (puzzleFront ? -4 : 4);
            int frameZ = st.arenaZ + (puzzleFront ? -3 : 3);
            org.bukkit.block.BlockFace frameFace = puzzleFront
                    ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 1; dy <= 3; dy++) {
                    w.getBlockAt(st.arenaX + dx, st.baseY + dy, wallZ)
                            .setType(Material.QUARTZ_BLOCK, false);
                }
            }
            st.puzzleFrames = new HashMap<>();
            st.puzzleExtraNames = counts;
            st.puzzleExtraLeft = extras;
            // Картинка ячейки рисуется per-player при каждой проверке со
            // случайным сдвигом и шумом: map-id = только позиция ячейки,
            // а байты карты не совпадают между заходами (таблица «хэш
            // карты -> животное» не работает).
            st.puzzleCells = cells.toArray(new String[0]);
            st.puzzleSeed = random.nextLong();
            st.puzzleCellDrawn = new boolean[9];
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
                frame.setItem(cellItem(i, w), false);
                // НЕ invulnerable/fixed: у invulnerable-сущности NMS отсекает
                // урон до EntityDamageByEntityEvent — удар бы не обрабатывался.
                // Защита от лишних — через отмену события в onPuzzleFrameHit.
                st.puzzleFrames.put(frame.getUniqueId(), st.puzzleKeep != null
                        ? !name.equals(st.puzzleKeep) : counts.containsKey(name));
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
        String keepName = st.puzzleKeep == null ? "" : keepDisplay(player, st.puzzleKeep);
        ph.put("keep", keepName);
        String text = null;
        if (st.puzzleKeep != null) {
            if (puzzleTaskKeepText != null && !puzzleTaskKeepText.trim().isEmpty()) {
                text = org.bukkit.ChatColor.translateAlternateColorCodes('&', puzzleTaskKeepText);
                for (Map.Entry<String, String> e : ph.entrySet()) {
                    text = text.replace("{" + e.getKey() + "}", e.getValue());
                }
            } else if (ms != null) {
                text = ms.message(player, "antibot_stage_puzzle_keep", ph);
            }
            if (text == null || text.isEmpty() || text.contains("antibot_stage_puzzle_keep")) {
                text = "\u00A7fПроверка: \u00A7aоставь только \u00A76" + keepName
                        + "\u00A7a — всё остальное убери ударом (\u00A7f" + st.puzzleExtraLeft + "\u00A7a шт.)";
            }
        } else if (puzzleTaskText != null && !puzzleTaskText.trim().isEmpty()) {
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

    /**
     * Предмет-карта для ячейки стены (0..8). MapView — один на ПОЗИЦИЮ
     * ячейки на всё время работы (без утечки map-id), рендерер
     * контекстный: рисует тайл, выпавший этому игроку в этой проверке.
     */
    private org.bukkit.inventory.ItemStack cellItem(int idx, World w) {
        org.bukkit.map.MapView view = cellView(idx, w);
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

    private org.bukkit.map.MapView cellView(int idx, World w) {
        org.bukkit.map.MapView view = cellViews[idx];
        if (view != null || w == null) {
            return view;
        }
        try {
            view = Bukkit.createMap(w);
            view.getRenderers().forEach(view::removeRenderer);
            final int cell = idx;
            view.addRenderer(new org.bukkit.map.MapRenderer(true) {
                @Override
                public void render(org.bukkit.map.MapView mv,
                        org.bukkit.map.MapCanvas canvas, Player p) {
                    CheckState st = checks.get(p.getUniqueId());
                    if (st == null || st.puzzleCells == null || st.puzzleCellDrawn == null
                            || cell >= st.puzzleCells.length || st.puzzleCellDrawn[cell]) {
                        return;
                    }
                    st.puzzleCellDrawn[cell] = true;
                    drawCell(canvas, st.puzzleCells[cell], st.puzzleSeed * 31L + cell);
                }
            });
            cellViews[idx] = view;
            return view;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Тайл на холст карты со сдвигом ±6px (края тянем) и ~3% шума из
     * пикселей того же тайла. Человек узнаёт животное как раньше, а
     * байтовый слепок карты у каждой проверки свой.
     */
    @SuppressWarnings("deprecation")
    private void drawCell(org.bukkit.map.MapCanvas canvas, String name, long seed) {
        byte[] px = tileBytes(name);
        if (px == null) {
            return;
        }
        java.util.Random r = new java.util.Random(seed);
        int dx = r.nextInt(13) - 6;
        int dy = r.nextInt(13) - 6;
        // зеркало по горизонтали: животное узнаётся так же, а слепок
        // (в т.ч. перцептивный хэш) у отражённой картинки другой
        boolean mirror = r.nextBoolean();
        // Шум ~3%: xorshift на месте — java.util.Random (атомарный CAS)
        // 16 тыс. раз на клетку был заметен в профиле
        long s = seed ^ 0x9E3779B97F4A7C15L;
        if (s == 0) {
            s = 1;
        }
        int n = px.length;
        for (int y = 0; y < 128; y++) {
            int sy = Math.max(0, Math.min(127, y - dy)) * 128;
            for (int x = 0; x < 128; x++) {
                int sx = Math.max(0, Math.min(127, x - dx));
                byte b = px[sy + (mirror ? 127 - sx : sx)];
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                if ((s & 1023) < 31) {
                    b = px[(int) ((s >>> 33) % n)];
                }
                canvas.setPixel(x, y, b);
            }
        }
    }

    /** Палитра тайла 128×128 — конвертируется один раз и кэшируется. */
    @SuppressWarnings("deprecation")
    private byte[] tileBytes(String name) {
        if (name == null) {
            return null;
        }
        byte[] b = tileBytes.get(name);
        if (b != null) {
            return b;
        }
        java.awt.image.BufferedImage img = tileImgs.get(name);
        if (img == null) {
            return null;
        }
        try {
            b = fastPalette(img);
        } catch (Throwable t) {
            return null;
        }
        if (b == null || b.length < 128 * 128) {
            return null;
        }
        tileBytes.put(name, b);
        return b;
    }

    /**
     * Картинка → палитра карты. MapPalette.imageToBytes подбирает цвет для
     * КАЖДОГО из 16384 пикселей (перебор всей палитры) — в профиле это было
     * главное потребление плагина. В тайле единицы-десятки уникальных цветов,
     * поэтому подбор запоминаем по цвету: 16384 → ~50 вызовов matchColor.
     */
    @SuppressWarnings("deprecation")
    private static byte[] fastPalette(java.awt.image.BufferedImage img) {
        int w = Math.min(128, img.getWidth());
        int h = Math.min(128, img.getHeight());
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] out = new byte[128 * 128];
        java.util.HashMap<Integer, Byte> memo = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = rgb[y * w + x];
                if ((c >>> 24) < 128) {
                    out[y * 128 + x] = 0;
                    continue;
                }
                Byte m = memo.get(c);
                if (m == null) {
                    m = org.bukkit.map.MapPalette.matchColor(new java.awt.Color(c, true));
                    memo.put(c, m);
                }
                out[y * 128 + x] = m;
            }
        }
        return out;
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
            // Палитры пересчитываются от свежих картинок
            tileBytes.clear();
            for (String raw : names) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String name = raw.trim();
                java.io.File f = new java.io.File(dir, name + ".png");
                try {
                    if (f.exists()) {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                        if (img == null) {
                            plugin.getLogger().warning("AntiBot: тайл '" + name
                                    + "': файл не читается как картинка — пропущен");
                            continue;
                        }
                        // Карта = ровно 128×128: свой PNG другого размера
                        // масштабируем, иначе он рисовался полосами/обрезком
                        if (img.getWidth() != 128 || img.getHeight() != 128) {
                            java.awt.image.BufferedImage sc = new java.awt.image.BufferedImage(
                                    128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            java.awt.Graphics2D g = sc.createGraphics();
                            try {
                                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                g.drawImage(img, 0, 0, 128, 128, null);
                            } finally {
                                g.dispose();
                            }
                            img = sc;
                        }
                        tileImgs.put(name, img);
                    } else {
                        java.awt.image.BufferedImage img = drawTile(name);
                        javax.imageio.ImageIO.write(img, "PNG", f);
                        tileImgs.put(name, img);
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("AntiBot: тайл '" + name + "': " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: не удалось подготовить тайлы пазла: " + t.getMessage());
        }
        // Палитры всех тайлов — заранее и в фоне: первый же игрок на пазле
        // не платит за конвертацию в главном потоке.
        final java.util.List<String> warm = new java.util.ArrayList<>(tileImgs.keySet());
        Scheduler.runAsync(plugin, () -> {
            for (String n : warm) {
                tileBytes(n);
            }
        });
    }

    /** Случайное «лишнее» имя по весам из puzzle_remove_names ("имя:вес"). */
    private String pickRemoveName() {
        return pickRemoveName(null);
    }

    /**
     * Взвешенный выбор «лишнего» тайла. exclude — уже выбранные имена:
     * один и тот же тип не выпадает дважды на одной стене, поэтому
     * тяжёлый вес (man_black:50) не забивает всю сетку. Если исключены
     * все имена — выбираем из полного пула.
     */
    private String pickRemoveName(java.util.Set<String> exclude) {
        int[] w = puzzleRemoveWeights;
        int total = 0;
        for (int i = 0; i < puzzleRemoveNames.size(); i++) {
            if (exclude == null || !exclude.contains(puzzleRemoveNames.get(i))) {
                total += Math.max(1, i < w.length ? w[i] : 1);
            }
        }
        if (total <= 0) {
            exclude = null;
            for (int x : w) {
                total += Math.max(1, x);
            }
        }
        if (total <= 0 || puzzleRemoveNames.isEmpty()) {
            return "man_black";
        }
        int r = random.nextInt(total);
        for (int i = 0; i < puzzleRemoveNames.size(); i++) {
            if (exclude != null && exclude.contains(puzzleRemoveNames.get(i))) {
                continue;
            }
            r -= Math.max(1, i < w.length ? w[i] : 1);
            if (r < 0) {
                return puzzleRemoveNames.get(i);
            }
        }
        return puzzleRemoveNames.get(0);
    }

    /**
     * Название «оставляемого» животного во мн. числе («свинок», «котов») —
     * lang-ключ puzzle_keep_<имя>; нет ключа (свой PNG) — обычное название.
     */
    private String keepDisplay(Player player, String name) {
        MessageService ms = messages();
        if (ms != null) {
            String key = "puzzle_keep_" + name.toLowerCase(java.util.Locale.ROOT);
            String v = ms.message(player, key, new HashMap<>());
            if (v != null && !v.isEmpty() && !v.contains(key)) {
                return org.bukkit.ChatColor.stripColor(v);
            }
        }
        return tileDisplay(name.replace('_', ' '));
    }

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
            case "ocelot": return "оцелот";
            case "wolf": return "волк";
            case "llama": return "лама";
            case "parrot": return "попугай";
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
            // Чанки лобби не гружены — любой getBlockAt дергал бы диск.
            if (w.isChunkLoaded(0, (LOBBY_Z + 3) >> 4)) {
                org.bukkit.block.Block signBlock = w.getBlockAt(0, y + 1, LOBBY_Z + 3);
                if (signBlock.getType() != SIGN_M) {
                    signBlock.setType(SIGN_M, false);
                }
                org.bukkit.block.BlockState bs = signBlock.getState();
                if (bs instanceof org.bukkit.block.Sign) {
                    org.bukkit.block.Sign sign = (org.bukkit.block.Sign) bs;
                    if (sign.getLine(0) == null || sign.getLine(0).isEmpty()) {
                        sign.setLine(0, "Очередь");
                        sign.setLine(1, "на проверку");
                        sign.setLine(2, "жди на боссбаре");
                        sign.update(true, false);
                    }
                }
            }
            // Полное восстановление декора: фонари/стёкла/паркур/PvP-зону
            // ломают чаще всего — переставляем только отличающиеся блоки.
            repairLobbyDecor(w, y);
            if (pvpEnabled && w.isChunkLoaded(pvpChestX >> 4, pvpChestZ >> 4)) {
                if (w.getBlockAt(pvpChestX, y + 1, pvpChestZ).getType() != Material.CHEST) {
                    w.getBlockAt(pvpChestX, y, pvpChestZ).setType(Material.SMOOTH_STONE, false);
                    w.getBlockAt(pvpChestX, y + 1, pvpChestZ).setType(Material.CHEST, false);
                    refillPvpChest();
                }
                if (speedButtonEnabled && speedButtonLoc != null
                        && speedButtonLoc.getWorld() != null
                        && speedButtonLoc.getWorld().isChunkLoaded(
                                speedButtonLoc.getBlockX() >> 4,
                                speedButtonLoc.getBlockZ() >> 4)
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
        // Удар раньше min_answer_ms после показа задания — человек ещё
        // не успел прочитать текст, бот бьёт сразу. Не кикаем (случайный
        // клик зажатой ЛКМ после телепорта), но и не засчитываем.
        if (tooFast(st)) {
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
                plugin.getLogger().info("AntiBot: " + player.getName() + " — ошибка в пазле ("
                        + st.puzzleWrong + "/" + puzzleMaxWrong + ")");
                failCheck(uuid, msg("antibot_kick_mistake"));
            } else {
                sendPuzzleWrong(player, st);
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
        if (!enabled || ServerCore.isFolia()) {
            return null;
        }
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            w = getOrCreateWorld();
        }
        if (w == null) {
            return null;
        }
        if (queueBuildLobby) {
            ensureLobby(w);
        } else if (holdingPadWorld != w) {
            buildHoldingPad(w); // 25 блоков — один раз на мир, а не на каждый вход
            holdingPadWorld = w;
        }
        return lobbySpawn(w);
    }

    private volatile World holdingPadWorld;

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
        // math_operations: add — сложение (по умолчанию), mul — умножение
        // самых простых чисел 2..5 (таблица умножения, без вычитания и минусов)
        boolean mul = mathMul && (!mathAdd || random.nextBoolean());
        String expr;
        if (mul) {
            int a = 2 + random.nextInt(4);
            int b = 2 + random.nextInt(4);
            st.mathAnswer = String.valueOf(a * b);
            expr = a + " \u00D7 " + b;
        } else {
            int a = 1 + random.nextInt(mathMax);
            int b = 1 + random.nextInt(mathMax);
            st.mathAnswer = String.valueOf(a + b);
            expr = a + " + " + b;
        }
        st.mathExpr = expr;
        sendMathPrompt(player, st);
    }

    /**
     * Подсказка по центру экрана: «Проверка N/M» + что делать на этапе.
     * На капче не показываем — там на экране сам код (antibot_captcha_title).
     */
    private void stageHint(Player p, CheckState st, Stage stage) {
        if (!me.vorchun.registerplugin.util.ScreenHints.antibot() || p == null || st == null || stage == null
                || stage == Stage.CAPTCHA) {
            return;
        }
        MessageService ms = messages();
        if (ms == null) {
            return;
        }
        Map<String, String> ph = new HashMap<>();
        putStageNum(ph, st);
        String title = ms.message(p, "hint_stage_title", ph);
        if (title == null || title.isEmpty()) {
            return;
        }
        String sub = ms.message(p, "hint_stage_" + stage.name().toLowerCase(java.util.Locale.ROOT), ph);
        me.vorchun.registerplugin.util.ScreenHints.show(p, "stage:" + st.stageIndex + ":" + stage, title, sub);
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Вечный день и без монстров в мире проверки. Имена правил менялись:
     * doDaylightCycle/doMobSpawning (до 1.21.x) → advance_time/spawn_monsters (26.x).
     * Раньше на 26.x правило не находилось — в мире шла ночь.
     */
    @SuppressWarnings("deprecation")
    private static void setDayForever(World w) {
        for (String rule : new String[]{"doDaylightCycle", "advance_time", "minecraft:advance_time"}) {
            try {
                if (w.setGameRuleValue(rule, "false")) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String rule : new String[]{"doMobSpawning", "spawn_monsters", "minecraft:spawn_monsters"}) {
            try {
                if (w.setGameRuleValue(rule, "false")) {
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** {stage_num}/{stage_total} для текста этапа (раньше в math/secret их не было — игрок видел скобки). */
    private void putStageNum(Map<String, String> ph, CheckState st) {
        List<Stage> order = st.stages != null ? st.stages : stageOrder;
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
    }

    private void sendMathPrompt(Player player, CheckState st) {
        MessageService ms = messages();
        Map<String, String> ph = new HashMap<>();
        ph.put("expr", st.mathExpr == null ? "?" : st.mathExpr);
        putStageNum(ph, st);
        String text = ms == null ? null : ms.message(player, "antibot_stage_math", ph);
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
            failCheck(uuid, msg("antibot_kick_fast"));
            return 2;
        }
        if (answerIsBot(player, st, input)) {
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
        st.secretExpected = pickSecret(st);
        MessageService ms = messages();
        Map<String, String> ph = new HashMap<>();
        ph.put("command", st.secretExpected);
        ph.put("num", String.valueOf(st.secretDone + 1));
        ph.put("total", String.valueOf(secretCount));
        putStageNum(ph, st);
        String text = ms == null ? null : ms.message(player, "antibot_stage_secret", ph);
        player.sendMessage(text == null || text.isEmpty()
                ? "&eНапиши в чат точно: &f" + st.secretExpected : text);
        st.promptShownAt = System.currentTimeMillis();
    }

    /**
     * Префикс теста строго чередуется по номеру: чётный — '.', нечётный — '#'.
     * Чит, фильтрующий только один вид команд, всё равно спалится на втором.
     */
    private String pickSecret(CheckState st) {
        boolean wantDot = (st.secretDone % 2) == 0;
        String prefix = wantDot ? "." : "#";
        switch (secretMode) {
            case "custom":
                if (secretCustom != null && !secretCustom.isEmpty()) {
                    List<String> mine = new ArrayList<>();
                    for (String c : secretCustom) {
                        if (c != null && c.startsWith(prefix)) {
                            mine.add(c);
                        }
                    }
                    if (!mine.isEmpty()) {
                        return mine.get(random.nextInt(mine.size()));
                    }
                    return secretCustom.get(random.nextInt(secretCustom.size()));
                }
                return prefix + "bind";
            case "random": {
                StringBuilder sb = new StringBuilder(prefix);
                for (int i = 0; i < 4; i++) {
                    sb.append((char) ('a' + random.nextInt(26)));
                }
                return sb.toString();
            }
            default: {
                String[] pool = wantDot
                        ? new String[]{".bind", ".help", ".bro"}
                        : new String[]{"#bro", "#bind", "#help"};
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
            failCheck(uuid, msg("antibot_kick_fast"));
            return 2;
        }
        if (answerIsBot(player, st, input)) {
            return 2;
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
        return s == Stage.CAPTCHA || s == Stage.MATH || s == Stage.SECRET || s == Stage.PUZZLE
                || s == Stage.AIR_CAPTCHA;
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
            case AIR_CAPTCHA:
                return submitAirCode(player, input);
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

    /**
     * Вернуть видимость после проверки: на проверке игрок был скрыт ото
     * всех. Если он ещё не залогинен, reapplyHiding скроет его правильно.
     */
    private void unhideChecked(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == player) {
                continue;
            }
            try {
                o.showPlayer(plugin, player);
                player.showPlayer(plugin, o);
            } catch (Throwable ignored) {
            }
        }
        if (plugin instanceof RegisterPlugin) {
            ((RegisterPlugin) plugin).reapplyHiding(player);
        }
    }

    public void finishCheck(Player player) {
        UUID uuid = player.getUniqueId();
        CheckState st = checks.remove(uuid);
        // Все этапы пройдены: отсюда считается срок до перепроверки (recheck_hours)
        passes.mark(uuid);
        if (fallPackets != null) {
            fallPackets.stop(uuid);
        }
        removeBar(st);
        // Очередь ВХОДА на сервер: проверка пройдена, но слотов нет —
        // игрок ждёт на платформе лобби, инвентарь пока не возвращаем
        // (он ещё не авторизован — ограничения должны оставаться).
        if (entryEnabled && !entryHasCapacity() && player.isOnline()) {
            // Слот арены освобождён прямо сейчас (checks.remove) и сразу
            // уйдёт следующему — чистим арену СЕЙЧАС. Иначе поздний
            // restoreArena при выпуске ломал бы платформу/стену пазла
            // чужой идущей проверки в том же слоте.
            removePuzzlePicture(st);
            restoreArena(st);
            if (st != null) {
                st.world = null;
            }
            unhideChecked(player);
            enqueueEntry(player, uuid, st);
            processQueue();
            return;
        }
        releasePlayer(player, st);
        processQueue();
        if (checks.isEmpty() && queue.isEmpty() && entryQueue.isEmpty()) {
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
        // Проверка пройдена: с этого момента регистрация разрешена (D1),
        // и после рестарта-перепроверки (recheck_on_restart) — тоже.
        // Сюда приходят и выпуски из очереди входа.
        bootPassed.add(uuid);
        admittedNoCheck.remove(uuid);
        Location back = returnLocations.remove(uuid);
        removePuzzlePicture(st);
        restoreArena(st);
        // курсор/окно набора — до возврата вещей (лут с курсора иначе
        // падал в инвентарь при закрытии окна телепортом)
        dropLobbyCursor(player);
        restorePlayer(player, st);
        stripLobbyLoot(player);
        unhideChecked(player);
        // Прокси: Connect на целевой сервер ТОЛЬКО после авторизации (D2).
        // Прошёл капчу ≠ ввёл пароль: раньше игрок улетал на lobby под
        // чужим ником, не вводя пароль. Уже авторизован — шлём сразу,
        // иначе откладываем: AuthListener.afterLoginSuccess заберёт цель.
        boolean transferred = false;
        if (entryTargetServer != null && !entryTargetServer.isEmpty() && player.isOnline()) {
            SessionManager sm = plugin instanceof RegisterPlugin
                    ? ((RegisterPlugin) plugin).getSessionManager() : null;
            if (sm != null && sm.isLoggedIn(uuid)) {
                teleportService.transferToServer(player, entryTargetServer);
                transferred = true;
            } else {
                pendingEntryTarget.put(uuid, entryTargetServer);
            }
        }
        // Точка возврата передаётся явно — не зависит от порядка задач runAtEntity
        Location passBack = null;
        if (!transferred && player.isOnline()) {
            // Точка входа в зоне антибота (вышел прошлый раз с арены/из
            // лобби) — там не оставляем: слот арены сразу получит другой
            // игрок, а запасная арена висит в небе основного мира (сойдёшь
            // с пятачка — разобьёшься). Такой точки нет — на спавн мира.
            boolean backOk = back != null && back.getWorld() != null && !isCheckArea(back);
            passBack = backOk ? back : null;
            final Location to = backOk ? back
                    : (isCheckArea(player.getLocation()) ? mainSpawn() : null);
            if (to != null) {
                if (plugin instanceof RegisterPlugin && ((RegisterPlugin) plugin).holdsOnAuthPlatform(player)
                        && toAuthPlatform(player)) {
                    // Дальше /reg или /login на платформе в этом же мире: вместо
                    // «арена → основной мир → платформа» (два перелёта между
                    // мирами с отправкой чанков) — один короткий телепорт.
                    // В основной мир — после входа, на запомненную точку.
                    if (passBack == null) {
                        passBack = to;
                    }
                } else {
                    teleportService.authorizeTeleport(uuid);
                    teleportSafe(player, to);
                }
            }
        }
        if (plugin instanceof RegisterPlugin) {
            ((RegisterPlugin) plugin).onAntiBotPassed(player, passBack);
        }
    }

    // ---------- очередь входа на сервер (после проверки) ----------

    /** Свободен ли слот для выпуска: max_online или (слоты сервера − резерв). */
    private boolean entryHasCapacity() {
        int cap = entryMaxOnline > 0 ? entryMaxOnline
                : Math.max(1, Bukkit.getMaxPlayers() - entryReserveSlots);
        // Слот занимают только выпущенные: ждущие входа, ждущие проверки
        // и проверяемые — нет (иначе толпа ботов в очереди на проверку
        // держала бы в entry-очереди людей, которые проверку уже прошли)
        int released = Bukkit.getOnlinePlayers().size() - entryQueue.size()
                - queue.size() - checks.size();
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
                if (player.isOnline() && entryInfo.containsKey(uuid)) {
                    Compat.teleport(player, lobbySpawn(fw));
                    {
                        clearVision(player);
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
        return entryInfo.containsKey(uuid);
    }

    private void dequeueEntry(UUID uuid) {
        entryQueue.remove(uuid);
        CheckState est = entryStates.remove(uuid);
        QueueEntry qe = entryInfo.remove(uuid);
        if (qe != null) {
            removeBar(qe.bar);
            // Полёт лобби снимаем, возвращая тот, что был ДО проверки
            // (креатив/право fly не теряются после /login из очереди)
            resetLobbyFlight(Bukkit.getPlayer(uuid), est);
        }
        if (Bukkit.getPlayer(uuid) == null) {
            orphanStash(uuid, est);
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
            {
                clearVision(p);
            }
            if (qe.bar != null) {
                String bt = toBarText("&bВход на сервер: &f" + pos + "/" + entryQueue.size()
                        + "  &7·  ~" + (pos * entryReleaseSeconds / Math.max(1, entryReleaseBatch)) + "s"
                        + "  &7·  &aпроверка пройдена");
                float pr = (float) Math.max(0.02, 1.0 - (pos - 1.0) / Math.max(1, entryQueue.size()));
                if (!bt.equals(qe.lastBarText)) {
                    qe.lastBarText = bt;
                    qe.bar.setTitle(bt);
                }
                if (pr != qe.lastBarProg) {
                    qe.lastBarProg = pr;
                    qe.bar.setProgress(pr);
                }
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
                orphanStash(next, st);
                continue;
            }
            released++;
            resetLobbyFlight(p, st);
            releasePlayer(p, st);
            sendMessage(p, "antibot_entry_released", 0);
        }
        nextReleaseAt = now + entryReleaseSeconds * 1000L;
    }

    public void failCheck(UUID uuid, String reason) {
        failCheck(uuid, reason, true);
    }

    /**
     * Провал проверки. evidence=true — ДОКАЗАННЫЙ бот (неверная физика, флуд,
     * робот-ответы, ошибка/обманка): засчитывается в ступени бана по IP.
     * evidence=false (таймаут — лаг, AFK) — только кик, без бана.
     */
    public void failCheck(UUID uuid, String reason, boolean evidence) {
        // Тоже может прийти с асинхронного чата — restoreArena/kick только main
        if (!Scheduler.isPrimaryThread()) {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl != null) {
                Scheduler.runAtEntity(plugin, pl, () -> failCheck(uuid, reason, evidence));
            } else {
                Scheduler.runSync(plugin, () -> failCheck(uuid, reason, evidence));
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
        // IP снимаем ДО кика: после выхода адрес уже не достать надёжно
        String failIp = "";
        try {
            Bukkit.getPluginManager().callEvent(new me.vorchun.registerplugin.api.AntiBotFailEvent(
                    uuid, p != null && p.isOnline() ? p : null, reason, evidence));
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBotFailEvent: ошибка в чужом обработчике: " + t);
        }
        if (p != null && p.isOnline()) {
            failIp = me.vorchun.registerplugin.util.IpUtil.getIp(plugin, p);
            restorePlayer(p, st);
            unhideChecked(p);
            p.kickPlayer(reason == null ? "" : reason);
        } else {
            orphanStash(uuid, st);
        }
        if (evidence && plugin instanceof RegisterPlugin) {
            ((RegisterPlugin) plugin).onAntiBotFailed(uuid, failIp);
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
            if (st != null) {
                dropLobbyCursor(p);
            }
            restorePlayer(p, st);
            unhideChecked(p);
            if (teleportBack && back != null && back.getWorld() != null
                    && !isCheckArea(back) && p.isOnline()) {
                teleportService.authorizeTeleport(uuid);
                teleportSafe(p, back);
            }
        } else {
            orphanStash(uuid, st);
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
        while (checks.size() < maxConcurrent && !queue.isEmpty() && promoted < batchSize
                && surgeTokensLeft() > 0) {
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
            if (!beginCheck(p)) {
                // Проверка не стартовала (мир недоступен/сбой) — игрок уже
                // вне очереди: вернуть его в обычный auth-флоу, а не бросить
                leaveQueues(p);
                cancelCheck(next, true);
                notifyAborted(p);
            }
        }
        if (promoted > 0) {
            // Видимость в лобби обновилась — пересчитать ОДИН раз за волну
            syncQueueVisibility();
            if (!queue.isEmpty()) {
                nextBatchAt = System.currentTimeMillis() + batchDelaySeconds * 1000L;
            }
        }
    }

    // ---------- состояние ----------

    /**
     * Можно ли этому игроку регистрироваться прямо сейчас (D1).
     * true — антибот выключен/без этапов; игрок прошёл проверку (в эту
     * сессию или с момента старта сервера, включая выпуск из очереди
     * входа); проверка для него не требуется; или он пропущен без
     * проверки по дизайну (мир недоступен, сбой старта, вошёл при
     * выключенном антиботе). false — стоит в очереди/очереди входа, идёт
     * проверка, либо проверку ещё не проходил. Окно afk_first
     * AuthListener отсекает сам — AntiBotService о нём не знает.
     */
    public boolean mayRegister(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (!enabled || stageOrder.isEmpty()) {
            return true;
        }
        if (isBusy(uuid)) {
            return false;
        }
        if (bootPassed.contains(uuid) || admittedNoCheck.contains(uuid)) {
            return true;
        }
        // Проверка не требуется: only_new_players + уже зарегистрирован
        // (и нет обязательной перепроверки после рестарта)
        if (onlyNewPlayers && !requiresRestartRecheck(uuid) && plugin instanceof RegisterPlugin) {
            AccountStore as = ((RegisterPlugin) plugin).getAccountStore();
            return as != null && as.isRegistered(uuid);
        }
        return false;
    }

    /**
     * Колбэк «проверку сняли без прохождения, игрок онлайн» — RegisterPlugin
     * вешает сюда продолжение обычного auth-флоу (таймаут, подсказки /reg).
     */
    public void setAbortHook(java.util.function.Consumer<Player> hook) {
        this.abortHook = hook;
    }

    /** AFK-сервис: preparePlayer берёт у него режим до AFK-spectator. */
    public void setAfkService(AfkService afkService) {
        this.afkService = afkService;
    }

    /** Проверка снята без прохождения — вернуть игрока в обычный auth-флоу. */
    private void notifyAborted(Player p) {
        if (p == null || !p.isOnline()) {
            return;
        }
        java.util.function.Consumer<Player> h = abortHook;
        try {
            if (h != null) {
                h.accept(p);
            } else if (plugin instanceof RegisterPlugin) {
                // Хук не подключён — хотя бы запускаем обычный флоу
                // (таймаут + подсказки), чтобы игрок не висел без них
                ((RegisterPlugin) plugin).onAntiBotPassed(p);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: abort-hook: " + t);
        }
    }

    /**
     * Целевой сервер entry-очереди, отложенный до авторизации (D2):
     * AuthListener.afterLoginSuccess забирает его и шлёт Connect.
     * null — переносить некуда.
     */
    public String consumeEntryTarget(UUID uuid) {
        return uuid == null ? null : pendingEntryTarget.remove(uuid);
    }

    /** Забыть per-UUID следы игрока (выход с сервера): кэши лобби/чата/кита. */
    public void forget(UUID u) {
        if (u == null) {
            return;
        }
        lastTargets.remove(u);
        chatLastAt.remove(u);
        chatLastMsg.remove(u);
        kitLastOpen.remove(u);
        speedCooldown.remove(u);
        kitInvs.remove(u);
        kitEditors.remove(u);
        pendingEntryTarget.remove(u);
        admittedNoCheck.remove(u);
    }

    /** Быстрый режим антибота (без лобби, очередей и afk_first). */
    public boolean isFastMode() {
        return fastMode;
    }

    /** Проходил ли игрок проверку с момента старта сервера (для API). */
    public boolean hasPassedCheck(UUID uuid) {
        return uuid != null && bootPassed.contains(uuid);
    }

    public boolean isChecking(UUID uuid) {
        return checks.containsKey(uuid);
    }

    /**
     * F101: выход посреди проверки ПОСЛЕ ошибок считается провалом (иначе бот
     * сбрасывал счётчик ошибок перезаходом). Выход без единой ошибки (лаг,
     * краш клиента) — не провал: человека за обрыв связи не караем.
     */
    public boolean quitCountsAsFail(UUID uuid) {
        CheckState st = checks.get(uuid);
        return st != null && st.stageIndex >= 0 && !st.preparing
                && (st.captchaAttempts > 0 || st.mathAttempts > 0 || st.puzzleWrong > 0
                    || st.clickWrong > 0 || st.clickDecoyHits > 0);
    }

    public boolean isQueued(UUID uuid) {
        return queueInfo.containsKey(uuid);
    }

    /** Игрок занят антиботом: в очереди, на проверке ИЛИ ждёт входа на сервер. */
    public boolean isBusy(UUID uuid) {
        return checks.containsKey(uuid) || queueInfo.containsKey(uuid) || entryInfo.containsKey(uuid);
    }

    public boolean queueChatAllowed() {
        return queueChatAllowed;
    }

    /** Игрок ждёт в очереди-лобби (режим 2). */
    public boolean isInQueueLobby(UUID uuid) {
        return queueMode == 2 && (queueInfo.containsKey(uuid) || entryInfo.containsKey(uuid));
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
        if (queueMode != 2 || to == null || (!queueInfo.containsKey(uuid) && !entryInfo.containsKey(uuid))) {
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
        // onDisable: шедулер задачи уже не принимает — телепорты синхронно
        // (teleportSafe), а сбой на одном игроке не обрывает цикл: иначе
        // у остальных пропадал инвентарь, а onDisable не доходил до
        // сброса базы аккаунтов на диск.
        shuttingDown = true;
        passes.saveNow();
        // незаконченные восстановления арен — сразу, пока плагин жив
        for (CheckState rs; (rs = restoreQueue.poll()) != null; ) {
            try {
                restoreArenaNow(rs);
            } catch (Throwable ignored) {
            }
        }
        for (UUID u : new ArrayList<>(checks.keySet())) {
            try {
                cancelCheck(u, true);
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: shutdown/проверка " + u + ": " + t);
            }
        }
        java.util.Set<UUID> queued = new java.util.LinkedHashSet<>(queue);
        queued.addAll(queueStash.keySet());
        for (UUID u : queued) {
            try {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    leaveQueues(p);
                } else {
                    dequeue(u);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: shutdown/очередь " + u + ": " + t);
            }
        }
        for (UUID u : new ArrayList<>(entryQueue)) {
            try {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) {
                    leaveQueues(p);
                } else {
                    dequeueEntry(u);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("AntiBot: shutdown/очередь входа " + u + ": " + t);
            }
        }
        queueStash.clear();
        pvpPairAt.clear();
        queueInfo.clear();
        entryInfo.clear();
        entryStates.clear();
        returnLocations.clear();
        // per-UUID кэши лобби/чата/кита — целиком
        lastTargets.clear();
        chatLastAt.clear();
        chatLastMsg.clear();
        kitLastOpen.clear();
        speedCooldown.clear();
        kitInvs.clear();
        kitEditors.clear();
        pendingEntryTarget.clear();
        admittedNoCheck.clear();
        visPvp.clear();
        if (fallPackets != null) {
            fallPackets.stopAll();
            fallPackets = null;
        }
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        stopTicker();
        // дописать/удалить файлы стэша (возвраты выше поставили удаления)
        stashIo.shutdown();
        try {
            stashIo.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
                CheckState ost = e.getValue();
                if (fallPackets != null) {
                    fallPackets.stop(e.getKey());
                }
                dequeue(e.getKey());
                returnLocations.remove(e.getKey());
                removeBar(ost);
                removePuzzlePicture(ost);
                restoreArena(ost);
                orphanStash(e.getKey(), ost);
                continue;
            }
            CheckState st = e.getValue();
            // Слепота — только рег/логин: снимаем на ВСЕХ фазах проверки,
            // включая отсчёт (applyAuthDarkness из join-flow мог прийти позже)
            {
                clearVision(p);
            }
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
                        // Первый этап — строго ПОСЛЕ телепорта на арену:
                        // иначе FALL-подброс в воздух уходил раньше, а
                        // телепорт на пол арены — тиком позже, и первый
                        // повтор засчитывался без падения.
                        st.preparingInLobby = false;
                        preparePlayer(p, st);
                        teleportService.authorizeTeleport(p.getUniqueId());
                        final CheckState fst = st;
                        Scheduler.runAtEntity(plugin, p, () -> {
                            if (p.isOnline() && checks.get(p.getUniqueId()) == fst) {
                                Compat.teleport(p, arenaSpawn(fst));
                                p.setFallDistance(0f);
                                advanceStage(p);
                            }
                        });
                        continue;
                    }
                    advanceStage(p);
                    continue;
                }
                int sec = (int) Math.ceil(leftMs / 1000.0);
                if (sec != st.lastPrepareSec) {
                    st.lastPrepareSec = sec;
                    sendMessage(p, "antibot_prepare", sec);
                    MessageService pms = messages();
                    Map<String, String> pph = new HashMap<>();
                    pph.put("seconds", String.valueOf(sec));
                    if (me.vorchun.registerplugin.util.ScreenHints.prepare() && pms != null) {
                        // Отсчёт перед проверкой — тексты в lang (hint_prepare_*), не вшиты в код
                        me.vorchun.registerplugin.util.ScreenHints.once(p,
                                pms.message(p, "hint_prepare_title", pph), pms.message(p, "hint_prepare_subtitle", pph), 25);
                    }
                    if (st.bar != null) {
                        String barText = pms == null ? null : pms.message(p, "antibot_prepare_bar", pph);
                        st.bar.setTitle(barText != null && !barText.isEmpty() ? barText
                                : toBarText("&eСтарт проверки через &f" + sec + " &eсек"));
                        st.bar.setProgress(Math.max(0.02, (double) sec / Math.max(1, prepareSeconds)));
                    }
                }
                continue;
            }
            if (me.vorchun.registerplugin.util.ScreenHints.antibot()) {
                stageHint(p, st, getCurrentStage(e.getKey())); // повторы — по screen_hints.refresh_seconds
            }
            if (st.stageDeadline > 0 && now > st.stageDeadline) {
                // Таймаут — не доказательство (лаг, отошёл): кик без бана
                failCheck(e.getKey(), msg("antibot_timeout"), false);
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
                pollJoltAccepted(p, st);
                if (joltsAnswered(st)) {
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
                // Инструмент в инвентаре пропал — выдать снова.
                try {
                    boolean hasTool = false;
                    for (org.bukkit.inventory.ItemStack tl : p.getInventory().getContents()) {
                        if (tl != null && (tl.getType() == Material.GOLDEN_PICKAXE
                                || tl.getType() == Material.GOLDEN_AXE
                                || tl.getType() == Material.GOLDEN_SHOVEL
                                || tl.getType() == Material.SHEARS)) {
                            hasTool = true;
                            break;
                        }
                    }
                    if (!hasTool) {
                        giveBlockTools(p);
                    }
                } catch (Throwable ignored) {
                }
            }
            // CLICK: пересылаем кликабельную кнопку каждые 8 сек —
            // сообщение тонет в чате, игрок теряет куда нажимать
            if (getCurrentStage(e.getKey()) == Stage.CLICK
                    && now - st.clickPromptAt > 8000L) {
                sendClickPrompt(p, st, false);
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
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = 1; dy <= 3; dy++) {
                            org.bukkit.block.Block wb = w.getBlockAt(
                                    st.arenaX + dx, st.baseY + dy, wallZ);
                            if (wb.getType() != Material.QUARTZ_BLOCK) {
                                wb.setType(Material.QUARTZ_BLOCK, false);
                            }
                        }
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
        // Сломанные структуры лобби чиним раз в ~3 секунды — ТОЛЬКО когда в
        // проверочном мире есть игроки. Иначе getBlockAt() на выгруженных
        // чанках (keepSpawnInMemory=false) грузил бы их с диска каждые 3с.
        World lw = verifyWorld != null ? verifyWorld : fallbackWorld;
        boolean lobbyActive = lw != null && !lw.getPlayers().isEmpty();
        // Чиним только когда в ЛОББИ кто-то стоит (очередь): игроки на
        // аренах/платформе лобби не трогают — не тратим тик на таблички.
        if (lobbyActive && queueBuildLobby && lobbyBuilt && !queue.isEmpty() && !isSurge()
                && now - lastStructCheckAt > 3000L) {
            lastStructCheckAt = now;
            restoreLobbyStructures();
            verifyLobbyIntegrity(lw);
        }

        // Чистка дропов в лобби: раз в lobby_item_clean_seconds, один
        // проход по сущностям мира — Item в границах лобби удаляем.
        // В пустом мире дропов не бывает — пропускаем.
        if (lobbyActive && queueBuildLobby && lobbyBuilt && lobbyItemCleanS > 0
                && now - lastItemCleanAt >= lobbyItemCleanS * 1000L) {
            lastItemCleanAt = now;
            cleanLobbyItems(lw);
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
        if (((queue.size() + entryQueue.size()) > 1 || !checks.isEmpty())
                && now - lastVisibilitySync > 1000L) {
            lastVisibilitySync = now;
            syncQueueVisibility();
        }
    }

    private void tickQueue(long now) {
        int pos = 0;
        List<UUID> snapshot = new ArrayList<>(queue);
        int qsize = snapshot.size(); // queue.size() — обход всей очереди на каждый вызов
        for (UUID u : snapshot) {
            pos++;
            Player p = Bukkit.getPlayer(u);
            QueueEntry qe = queueInfo.get(u);
            if (p == null || !p.isOnline() || qe == null) {
                dequeue(u);
                continue;
            }
            {
                clearVision(p);
            }
            // Боссбар с позицией (режимы 1 и 2)
            if (qe.bar != null) {
                String bt = toBarText("&eОчередь: &f" + pos + "/" + qsize
                        + "  &7·  ~" + (pos * batchDelaySeconds) + "s"
                        + "  &7·  &bпроверка на бота");
                float pr = (float) Math.max(0.02, 1.0 - (pos - 1.0) / Math.max(1, qsize));
                if (!bt.equals(qe.lastBarText)) {
                    qe.lastBarText = bt;
                    qe.bar.setTitle(bt);
                }
                if (pr != qe.lastBarProg) {
                    qe.lastBarProg = pr;
                    qe.bar.setProgress(pr);
                }
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
                        if (fp.isOnline() && queueInfo.containsKey(u)) {
                            Compat.teleport(fp, lobbySpawn(fp.getWorld()));
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
        if (queueMode == 2 && pvpEnabled && !queue.isEmpty()
                && now - lastChestRefill >= pvpChestSeconds * 1000L) {
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
        if (!isCheckArea(clicked.getLocation())) {
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
        // и для залогиненного админа, зашедшего проверить (isCheckWorld уже
        // гарантирован ранним return выше — членство в очереди не требуется).
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
        if (b == null || !isCheckArea(b.getLocation())) {
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
        Compat.teleport(p, new Location(w, 0.5, y + 1, LOBBY_Z + 0.5));
    }

    /**
     * Отдельный мир проверки/лобби — приватная зона, ломать нельзя никому
     * без прав. Запасная арена (fallback_main_world) живёт в ОСНОВНОМ мире:
     * он миром проверки не считается — иначе весь основной мир становился
     * «лобби» (стройка запрещена всем, возврат после проверки пропускался).
     * Для запасной арены/лобби точная проверка по координатам — {@link #isCheckArea}.
     */
    public boolean isCheckWorld(World w) {
        World vw = verifyWorld;
        return w != null && vw != null && vw.equals(w);
    }

    /**
     * Точка внутри зоны антибота: весь отдельный мир проверки ИЛИ, в режиме
     * запасной арены, только небесные арены и лобби в основном мире.
     */
    public boolean isCheckArea(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World w = loc.getWorld();
        if (isCheckWorld(w)) {
            return true;
        }
        World fw = fallbackWorld;
        if (fw == null || !fw.equals(w)) {
            return false;
        }
        int top = Math.min(fallbackBaseY(w), lobbyBaseY(w));
        if (loc.getY() < top - 24) {
            return false;
        }
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        // полоса арен: слоты по X с шагом arena_spacing, Z около 0
        if (x >= -48 && x <= maxConcurrent * arenaSpacing + 48 && z >= -48 && z <= 48) {
            return true;
        }
        // лобби-платформа, паркур и PvP-зона вокруг LOBBY_Z
        int minX = Math.min(-96, Math.min(pvpX1, pvpX2) - 8);
        int maxX = Math.max(96, Math.max(pvpX1, pvpX2) + 8);
        int minZ = Math.min(LOBBY_Z - 96, Math.min(pvpZ1, pvpZ2) - 8);
        int maxZ = Math.max(LOBBY_Z + 96, Math.max(pvpZ1, pvpZ2) + 8);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
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
            me.vorchun.registerplugin.util.ItemTag.mark(m, lobbyLootKey);
            it.setItemMeta(m);
            itemEnt.setItemStack(it);
        } catch (Throwable ignored) {
        }
    }

    /** Зона PvP в очереди-лобби (координаты из конфига). */
    public boolean isPvpArea(Location loc) {
        if (!pvpEnabled || loc == null || !isCheckArea(loc)) {
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
        UUID uuid = p.getUniqueId();
        if (!isInQueueLobby(uuid)) {
            return false;
        }
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        // keepInventory=true + непустые drops = дюп: ядро выкидывает drops
        // И оставляет те же вещи жертве. Выбираем одно поведение:
        //  - настоящие вещи в стэше (в руках только лут лобби) — правило
        //    арены: выпадают броня и еда, остальное сгорает (набор
        //    открывается снова); выпавшее помечено лутом лобби;
        //  - стэша нет (empty_inventory: false) — в руках НАСТОЯЩИЕ вещи:
        //    ничего не теряется и ничего не выпадает.
        CheckState qs = queueStash.get(uuid);
        CheckState es = entryStates.get(uuid);
        boolean stashed = (qs != null && qs.inventorySaved) || (es != null && es.inventorySaved);
        if (!stashed) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            return true;
        }
        e.setKeepInventory(false);
        java.util.Iterator<org.bukkit.inventory.ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            org.bukkit.inventory.ItemStack item = it.next();
            if (item == null) {
                it.remove();
                continue;
            }
            Material t = item.getType();
            String tn = t.name();
            boolean armor = tn.endsWith("_HELMET") || tn.endsWith("_CHESTPLATE")
                    || tn.endsWith("_LEGGINGS") || tn.endsWith("_BOOTS");
            boolean food = t.isEdible();
            if (!armor && !food) {
                it.remove();
            } else {
                tagKitItem(item);
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
        if (qe != null) {
            // очередь входа = проверку уже прошёл: после рестарта-перепроверки
            // (recheck_on_restart) повторно не гоняем
            bootPassed.add(uuid);
            admittedNoCheck.remove(uuid);
        }
        boolean queued = queueInfo.containsKey(uuid) || queueStash.containsKey(uuid);
        if (qe != null || queued) {
            // Лут лобби на курсоре/в открытом наборе — до возврата вещей
            dropLobbyCursor(p);
        }
        if (queued) {
            dequeue(uuid);
        }
        if (entryInfo.containsKey(uuid)) {
            dequeueEntry(uuid);
        }
        if (est != null) {
            try {
                restorePlayer(p, est);
            } catch (Throwable ignored) {
            }
        }
        stripLobbyLoot(p);
        unhideChecked(p);
        if (!shuttingDown) {
            syncQueueVisibility();
        }
        final Location back = qe != null ? qe.lobbyReturn : null;
        if (back != null && back.getWorld() != null && !isCheckArea(back) && p.isOnline()) {
            teleportService.authorizeTeleport(uuid);
            teleportSafe(p, back);
        }
    }

    /**
     * Восстановить арену после проверки: платформу перестраиваем в бедрок,
     * стену пазла/табличку убираем, сломанный целевой блок возвращаем.
     * Арена переиспользуется — следующий игрок должен видеть её целой.
     */
    // ── Восстановление арен порциями: не больше arena_restore_per_tick за тик ──
    private final java.util.ArrayDeque<CheckState> restoreQueue = new java.util.ArrayDeque<>();
    private final java.util.Set<Integer> restoringArenas = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Scheduler.Task restoreTask;
    private volatile int restorePerTick = 2;

    /** Поставить арену на восстановление (слот не выдаётся, пока не восстановлена). */
    private void restoreArena(CheckState st) {
        if (st == null || st.world == null) {
            return;
        }
        if (restorePerTick <= 0 || shuttingDown || !Scheduler.isPrimaryThread()) {
            restoreArenaNow(st);
            return;
        }
        restoringArenas.add(st.arenaX);
        restoreQueue.add(st);
        if (restoreTask == null) {
            restoreTask = Scheduler.runSyncTimer(plugin, this::drainRestores, 1L, 1L);
        }
    }

    private void drainRestores() {
        for (int i = 0; i < Math.max(1, restorePerTick); i++) {
            CheckState st = restoreQueue.poll();
            if (st == null) {
                Scheduler.Task t = restoreTask;
                restoreTask = null;
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            try {
                restoreArenaNow(st);
            } finally {
                restoringArenas.remove(st.arenaX);
            }
        }
    }

    private void restoreArenaNow(CheckState st) {
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
            // стена пазла (любая сторона), знак, путь BLOCK, сундук, цель
            clearStageExtras(st);
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
        if (!isCheckArea(b.getLocation())) {
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
                me.vorchun.registerplugin.util.ItemTag.mark(m, lobbyLootKey);
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
                org.bukkit.configuration.file.YamlConfiguration y = me.vorchun.registerplugin.util.ConfigMerger
                        .loadYamlTolerant(f, plugin);
                java.util.List<?> list = y == null ? null : y.getList("items");
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

    /** Зачарование по ключу minecraft:… — работает и до, и после переименования полей в 1.20.5. */
    private static void enchant(org.bukkit.inventory.ItemStack it, String key, int level) {
        try {
            org.bukkit.enchantments.Enchantment e =
                    org.bukkit.enchantments.Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(key));
            if (e != null) {
                it.addUnsafeEnchantment(e, level);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Дефолтный набор: незерит prot4 + меч sharp1 + щит + 25 яблок. */
    private List<org.bukkit.inventory.ItemStack> defaultKit() {
        List<org.bukkit.inventory.ItemStack> k = new ArrayList<>();
        for (Material m : new Material[]{
                me.vorchun.registerplugin.util.ItemTag.mat("NETHERITE_HELMET", Material.DIAMOND_HELMET),
                me.vorchun.registerplugin.util.ItemTag.mat("NETHERITE_CHESTPLATE", Material.DIAMOND_CHESTPLATE),
                me.vorchun.registerplugin.util.ItemTag.mat("NETHERITE_LEGGINGS", Material.DIAMOND_LEGGINGS),
                me.vorchun.registerplugin.util.ItemTag.mat("NETHERITE_BOOTS", Material.DIAMOND_BOOTS)}) {
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m);
            enchant(it, "protection", 4); // по ключу: имя поля сменилось в 1.20.5
            k.add(it);
        }
        org.bukkit.inventory.ItemStack sw = new org.bukkit.inventory.ItemStack(Material.DIAMOND_SWORD);
        enchant(sw, "sharpness", 1);
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
            // курсор: предмет набора, зажатый мышью, иначе переживал выпуск
            if (isLobbyLoot(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isLobbyLoot(org.bukkit.inventory.ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        try {
            return me.vorchun.registerplugin.util.ItemTag.has(item.getItemMeta(), lobbyLootKey);
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
        if (!pvpQueueSteal || queueMode != 2 || !queueInfo.containsKey(victim.getUniqueId())) {
            return;
        }
        Player killer = victim.getKiller();
        // Штраф жертве применяем только если это было PvP-убийство
        // (иначе случайная смерть от мобов/падения несправедливо откатит позицию)
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        // Одна и та же пара (в любую сторону) — не чаще раза в минуту:
        // два своих аккаунта, по очереди убивая друг друга, иначе
        // проталкивали одного из них в голову очереди
        long now = System.currentTimeMillis();
        int h1 = killer.getUniqueId().hashCode();
        int h2 = victim.getUniqueId().hashCode();
        long pair = ((long) Math.min(h1, h2) << 32) ^ (Math.max(h1, h2) & 0xffffffffL);
        Long lastPair = pvpPairAt.get(pair);
        if (lastPair != null && now - lastPair < PVP_PAIR_COOLDOWN_MS) {
            return;
        }
        pvpPairAt.put(pair, now);
        if (pvpPairAt.size() > 4096) {
            pvpPairAt.values().removeIf(t -> now - t > PVP_PAIR_COOLDOWN_MS);
        }
        if (queueInfo.containsKey(killer.getUniqueId())) {
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
        if (w == null || !w.isChunkLoaded(pvpChestX >> 4, pvpChestZ >> 4)) {
            return;
        }
        int y = lobbyBaseY(w);
        org.bukkit.block.Block b = w.getBlockAt(pvpChestX, y + 1, pvpChestZ);
        if (b.getType() != Material.CHEST) {
            return;
        }
        if (cachedPvpItems == null) {
            cachedPvpItems = parsePvpItems();
        }
        try {
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) b.getState();
            org.bukkit.inventory.Inventory inv = chest.getInventory();
            // Сундук уже полон — не трогаем (нет пересоздания ItemStack).
            boolean full = true;
            for (int i = 0; i < cachedPvpItems.size() && i < 27; i++) {
                if (inv.getItem(i) == null) {
                    full = false;
                    break;
                }
            }
            if (full && !cachedPvpItems.isEmpty()) {
                return;
            }
            inv.clear();
            int slot = 0;
            for (org.bukkit.inventory.ItemStack item : cachedPvpItems) {
                if (slot < 27) {
                    inv.setItem(slot++, item.clone());
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
                        me.vorchun.registerplugin.util.ItemTag.mark(meta, lobbyLootKey);
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
        long now = System.currentTimeMillis();
        boolean full = now - lastFullVisSync > 15000L;
        if (full) {
            lastFullVisSync = now;
        }
        // Проверяемые изолированы полностью: их не видит никто другой
        // и они не видят других — арене не должны «светиться» в лобби.
        // Проход checks × online — только когда сменился состав проверок
        // или онлайн (новый игрок), а не каждую секунду.
        int isoSig = checks.keySet().hashCode() * 31 + Bukkit.getOnlinePlayers().size();
        if (!checks.isEmpty() && (full || isoSig != lastIsoSig)) {
            for (UUID u : checks.keySet()) {
                Player cp = Bukkit.getPlayer(u);
                if (cp == null || !cp.isOnline()) {
                    continue;
                }
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o == cp) {
                        continue;
                    }
                    try {
                        o.hidePlayer(plugin, cp);
                        cp.hidePlayer(plugin, o);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        lastIsoSig = isoSig;
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
        // только внутри PvP-арены. Пары пересчитываем ТОЛЬКО для игроков,
        // у которых что-то сменилось (новый в очереди / вошёл-вышел из
        // PvP): при бот-волне в 500 человек полный O(n²) каждую секунду
        // съедал тик. Полный проход — раз в 15 с как страховка.
        int n = queued.size();
        boolean[] inPvp = new boolean[n];
        boolean[] changed = new boolean[n];
        java.util.Set<UUID> present = new java.util.HashSet<>(n * 2 + 1);
        boolean any = full;
        for (int i = 0; i < n; i++) {
            Player a = queued.get(i);
            UUID au = a.getUniqueId();
            present.add(au);
            inPvp[i] = pvpEnabled && queueHidePlayers && isPvpArea(a.getLocation(visLoc));
            Boolean was = visPvp.put(au, inPvp[i]);
            changed[i] = full || was == null || was != inPvp[i];
            any |= changed[i];
        }
        visPvp.keySet().retainAll(present);
        if (!any) {
            return;
        }
        for (int i = 0; i < n; i++) {
            Player a = queued.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j || !(changed[i] || changed[j])) {
                    continue;
                }
                Player b = queued.get(j);
                boolean see = !queueHidePlayers || (inPvp[i] && inPvp[j]);
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

    // syncQueueVisibility: прошлый PvP-статус ждущих, подпись изоляции
    // проверяемых, время полного прохода и переиспользуемая Location
    private final Map<UUID, Boolean> visPvp = new HashMap<>();
    private final Location visLoc = new Location(null, 0, 0, 0);
    private int lastIsoSig;
    private long lastFullVisSync;

    /** Текущий режим очереди: 0 = выкл, 1 = bossbar, 2 = лобби. */
    public int queueMode() {
        return queueMode;
    }

    /**
     * Общая платформа авторизации в check-мире (z=512, отдельно от арен
     * и лобби). Все ждущие входа стоят там — взаимно скрытые. Ленивая
     * постройка: платформа и мир создаются при первом вызове.
     * @return false — мир недоступен, игрок остаётся где был
     */
    public boolean toAuthPlatform(Player p) {
        // Folia: постройка платформы в чужом регионе невозможна — игрок
        // ждёт авторизацию там, где стоит (auth-часть остаётся рабочей)
        if (p == null || ServerCore.isFolia()) {
            return false;
        }
        World w = getOrCreateWorld();
        if (w == null) {
            return false;
        }
        Location loc = authPlatformLoc;
        if (loc == null || loc.getWorld() != w) {
            buildAuthPlatform(w);
            loc = authPlatformLoc;
        }
        if (loc == null) {
            return false;
        }
        final Location fl = loc;
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline()) {
                Location cur = p.getLocation();
                // Уже на платформе (заспавнен сразу на ней) — телепорт не нужен
                if (cur.getWorld() != fl.getWorld() || cur.distanceSquared(fl) > 16.0) {
                    teleportService.authorizeTeleport(p.getUniqueId());
                    Compat.teleport(p, fl);
                }
                p.setFallDistance(0f);
            }
        });
        return true;
    }

    /**
     * Точка платформы входа для спавна прямо на ней — только если мир
     * проверки уже есть (в событии входа мир не создаём). null — нельзя.
     */
    public Location authPlatformSpotIfReady() {
        if (!enabled || ServerCore.isFolia()) {
            return null;
        }
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            return null;
        }
        Location loc = authPlatformLoc;
        if (loc == null || loc.getWorld() != w) {
            buildAuthPlatform(w);
            loc = authPlatformLoc;
        }
        return loc == null ? null : loc.clone();
    }

    private final java.util.concurrent.atomic.AtomicBoolean platformBuildQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * То же для асинхронного события спавна (Paper 1.21.9+, фаза configuration):
     * мир здесь не трогаем. Платформа ещё не построена — заказываем постройку
     * в главном потоке, а этого игрока ведёт обычный маршрут с телепортом.
     */
    public Location authPlatformSpotCached() {
        if (!enabled || ServerCore.isFolia()) {
            return null;
        }
        World w = verifyWorld != null ? verifyWorld : fallbackWorld;
        if (w == null) {
            return null;
        }
        Location loc = authPlatformLoc;
        if (loc == null || loc.getWorld() != w) {
            if (platformBuildQueued.compareAndSet(false, true)) {
                Scheduler.runSync(plugin, () -> {
                    platformBuildQueued.set(false);
                    authPlatformSpotIfReady();
                });
            }
            return null;
        }
        return loc.clone();
    }

    /** Настоящая точка входа (игрок заспавнен сразу в мире проверки). */
    public void rememberReturn(UUID uuid, Location where) {
        if (uuid != null && where != null && where.getWorld() != null && !isCheckArea(where)) {
            returnLocations.putIfAbsent(uuid, where.clone());
        }
    }

    private void buildAuthPlatform(World w) {
        keepChunk(w, 0, AUTH_PLATFORM_Z);
        try {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    w.getBlockAt(dx, 63, AUTH_PLATFORM_Z + dz)
                            .setType(org.bukkit.Material.BEDROCK, false);
                }
            }
            authPlatformLoc = new Location(w, 0.5, 64, AUTH_PLATFORM_Z + 0.5, 0f, 0f);
        } catch (Throwable t) {
            plugin.getLogger().warning("AntiBot: auth platform build failed: " + t);
        }
    }

    private static final int AUTH_PLATFORM_Z = 512;
    private volatile Location authPlatformLoc;

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
                // Каждый вызов — отдельно: на новых ядрах часть методов устарела
                // (setKeepSpawnInMemory) или правила переименованы, и раньше одно
                // исключение пропускало все остальные настройки, включая запрет мобов
                final World fw = w;
                quietly(() -> fw.setAutoSave(false));
                // День навсегда + без непогоды — проверочный мир не тёмный, мобы не спавнятся
                quietly(() -> setDayForever(fw));
                quietly(() -> fw.setTime(6000));
                quietly(() -> fw.setStorm(false));
                quietly(() -> fw.setThundering(false));
                // Спавн-чанки проверочного мира не нужны в памяти — экономия
                quietly(() -> fw.setKeepSpawnInMemory(false));
                // В пустом мире мобов нет, но лимиты зануляем на всякий случай
                quietly(() -> fw.setMonsterSpawnLimit(0));
                quietly(() -> fw.setAnimalSpawnLimit(0));
                quietly(() -> fw.setAmbientSpawnLimit(0));
                quietly(() -> fw.setWaterAnimalSpawnLimit(0));
                verifyWorld = w;
                // Лобби строится лениво (ensureLobby) — только если очередь
                // реально нужна; мир сменился — старая постройка не наша
                lobbyBuilt = false;
                // Мир пустой: дальность прорисовки 3 вместо 10 — при телепорте
                // сервер шлёт 49 чанков вместо 441 (в профиле это главная цена)
                if (verifyViewDistance > 0) {
                    try {
                        w.getClass().getMethod("setViewDistance", int.class).invoke(w, verifyViewDistance);
                    } catch (Throwable ignored) {
                        // не Paper — остаётся общая дальность сервера
                    }
                }
                // Платформа авторизации — сразу при создании мира (на старте),
                // а не на первом игроке: иначе синхронная загрузка чанка в тике входа.
                buildAuthPlatform(w);
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

    /** «Не всё лишнее убрано» — с остатком лишних и остатком попыток. */
    private void sendPuzzleWrong(Player player, CheckState st) {
        MessageService ms = messages();
        if (ms == null || player == null || st == null) {
            return;
        }
        int left = st.puzzleFrames != null ? st.puzzleExtraLeft
                : (st.puzzleRemoveSlots == null ? 0 : st.puzzleRemoveSlots.size());
        Map<String, String> ph = new HashMap<>();
        List<Stage> order = st.stages != null ? st.stages : stageOrder;
        ph.put("blocks", String.valueOf(left));
        ph.put("attempts_left", String.valueOf(Math.max(0, puzzleMaxWrong - st.puzzleWrong)));
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
        String text = ms.message("antibot_puzzle_wrong", ph);
        if (text != null && !text.isEmpty()) {
            player.sendMessage(text);
        }
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
        ph.put("block", st != null && st.targetBlock != null
                ? blockDisplayName(st.targetBlock) : "?");
        ph.put("tool", st != null && st.targetBlock != null
                ? toolDisplayName(requiredToolFor(st.targetBlock)) : "?");
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
        // Код на карте в руке: текстом его не пишем нигде (чат, титл) —
        // иначе бот берёт его регуляркой и карта ничего не добавляет.
        // Bedrock (Floodgate) — код и текстом: карты там рисуются не везде.
        boolean hideCode = st.captchaOnMap && !bedrockClient(player);
        Map<String, String> ph = new HashMap<>();
        String shown;
        if (hideCode) {
            shown = ms.message(player, "antibot_captcha_on_map", new HashMap<>());
            if (shown == null || shown.isEmpty()) {
                shown = "§6[на карте в руке]";
            }
        } else {
            shown = st.code == null ? "" : st.code;
        }
        ph.put("code", shown);
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(st.stages != null ? st.stages.size() : stageOrder.size()));
        String text = ms.message(player, "antibot_stage_captcha", ph);
        if (text == null || text.isEmpty()) {
            text = "§eВведи код в чат: §f" + shown;
        }
        player.sendMessage(text);
        // TITLE_DUP
        try {
            String ttl = ms.message(player, "antibot_captcha_title", ph);
            String sub = hideCode ? ms.message(player, "antibot_captcha_map_subtitle", ph)
                    : ms.message(player, "antibot_captcha_subtitle", ph);
            if (hideCode && (sub == null || sub.isEmpty())) {
                sub = "§7Посмотри на карту в руке";
            }
            if (ttl != null && !ttl.isEmpty()) {
                player.sendTitle(ttl, sub == null ? "" : sub, 10, 70, 10);
            }
        } catch (Throwable ignored) {
        }
        st.promptShownAt = System.currentTimeMillis();
    }

    /**
     * CLICK: строка-инструкция + ряд кнопок в случайном порядке — одна
     * настоящая и click_decoys приманок (свои токены). Бот, жмущий любой
     * ClickEvent из JSON, попадает в приманку. Текстовая команда
     * /rpverify — сразу только Bedrock (клик там не работает), Java —
     * с третьей пересылки (~16 с), если клик так и не дошёл.
     * first=false — пересылка: токены и порядок те же, таймер скорости
     * ответа НЕ сбрасывается (клик по старой кнопке в момент пересылки
     * иначе считался «слишком быстрым» и кикал человека).
     */
    private void sendClickPrompt(Player player, CheckState st, boolean first) {
        st.clickPromptAt = System.currentTimeMillis();
        st.clickPrompts++;
        MessageService ms = messages();
        java.util.List<Stage> order = st.stages != null ? st.stages : stageOrder;
        Map<String, String> ph = new HashMap<>();
        ph.put("stage_num", String.valueOf(st.stageIndex + 1));
        ph.put("stage_total", String.valueOf(order.size()));
        int decoys = clickDecoyCount;
        if (decoys > 0 && st.clickDecoys == null && st.clickToken != null) {
            st.clickDecoys = new java.util.LinkedHashSet<>();
            while (st.clickDecoys.size() < decoys) {
                String t = generateToken();
                if (!t.equals(st.clickToken)) {
                    st.clickDecoys.add(t);
                }
            }
        }
        boolean sent = false;
        try {
            if (decoys <= 0 || st.clickDecoys == null) {
                String text = ms == null ? null : ms.message(player, "antibot_stage_click", ph);
                if (text == null || text.isEmpty()) {
                    text = "§eПроверка: нажми на это сообщение";
                }
                net.md_5.bungee.api.chat.TextComponent comp = new net.md_5.bungee.api.chat.TextComponent(
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
                comp.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rpverify " + st.clickToken));
                player.spigot().sendMessage(comp);
            } else {
                String text = ms == null ? null : ms.message(player, "antibot_stage_click_buttons", ph);
                if (text == null || text.isEmpty()) {
                    text = "§eПроверка " + ph.get("stage_num") + "/" + ph.get("stage_total")
                            + ": §fнажми кнопку §a§l[✔ Я НЕ БОТ]";
                }
                player.sendMessage(text);
                // Порядок кнопок — новый случайный при КАЖДОЙ отправке:
                // «Я не бот» не стоит на одном месте, клик по позиции не сработает
                List<String> tokens = new ArrayList<>(st.clickDecoys);
                tokens.add(st.clickToken);
                Collections.shuffle(tokens, random);
                net.md_5.bungee.api.chat.TextComponent row = new net.md_5.bungee.api.chat.TextComponent("  ");
                int decoyIdx = 0;
                for (String t : tokens) {
                    String label;
                    if (t.equals(st.clickToken)) {
                        label = ms == null ? null : ms.message(player, "antibot_click_button", ph);
                        if (label == null || label.isEmpty()) {
                            label = "§a§l[✔ Я НЕ БОТ]";
                        }
                    } else {
                        decoyIdx++;
                        label = ms == null ? null
                                : ms.message(player, "antibot_click_decoy_" + Math.min(decoyIdx, DECOY_LABELS.length), ph);
                        if (label == null || label.isEmpty()) {
                            label = DECOY_LABELS[(decoyIdx - 1) % DECOY_LABELS.length];
                        }
                    }
                    label = styleClickLabel(label, t.equals(st.clickToken));
                    net.md_5.bungee.api.chat.TextComponent b = new net.md_5.bungee.api.chat.TextComponent(
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(label));
                    b.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                            net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/rpverify " + t));
                    row.addExtra(b);
                    row.addExtra(new net.md_5.bungee.api.chat.TextComponent("   "));
                }
                player.spigot().sendMessage(row);
            }
            sent = true;
        } catch (Throwable ignored) {
        }
        // Текстовый дубль команды: Bedrock (клик не поддерживается) —
        // сразу; Java — только если клик-ивент не отправился или игрок
        // не справился за две пересылки (моды чата, особые клиенты)
        if (!sent || bedrockClient(player) || st.clickPrompts >= 3) {
            player.sendMessage("§7Если клик не сработал — введи: §f/rpverify " + st.clickToken);
        }
        if (first) {
            st.promptShownAt = System.currentTimeMillis();
        }
    }

    // Цвета кнопок этапа CLICK (antibot.click_buttons.*)
    private volatile String clickColorMode = "lang";
    private volatile String clickButtonColor = "&a";
    private volatile String clickDecoyColor = "&c";
    private volatile String clickSingleColor = "&f";
    private volatile boolean clickBold = true;

    /**
     * Цвет кнопки по настройке: lang — как в lang-файле; custom — свой цвет
     * для «Я не бот» и для обманок; single — все одним цветом; off — без цвета.
     */
    private String styleClickLabel(String label, boolean real) {
        String mode = clickColorMode;
        if ("lang".equals(mode)) {
            return label;
        }
        String plain = org.bukkit.ChatColor.stripColor(label);
        String color;
        if ("off".equals(mode)) {
            color = "";
        } else if ("single".equals(mode)) {
            color = colorCode(clickSingleColor);
        } else {
            color = colorCode(real ? clickButtonColor : clickDecoyColor);
        }
        return color + (clickBold ? org.bukkit.ChatColor.BOLD.toString() : "") + plain;
    }

    /** "&a" / "&#55FF55" / "#55FF55" → код цвета чата. */
    private static String colorCode(String c) {
        if (c == null || c.trim().isEmpty()) {
            return "";
        }
        String s = c.trim();
        String hex = s.startsWith("&#") ? s.substring(1) : s;
        if (hex.matches("#[0-9a-fA-F]{6}")) {
            try {
                return me.vorchun.registerplugin.util.LegacyColor.of(hex);
            } catch (Throwable ignored) {
                // 1.15 и старше — без HEX, ближайший обычный цвет не подбираем
                return "";
            }
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

    private static final String[] DECOY_LABELS = {
            "§c§l[✖ Я БОТ]",
            "§7§l[ПРОПУСТИТЬ]",
            "§6§l[ВЫЙТИ]"
    };

    // Floodgate API (кэш рефлексии): Bedrock-клиент независимо от
    // bedrock.enabled — нужен, чтобы не требовать от него клик/карту
    private static volatile Object floodgateApiObj;
    private static volatile java.lang.reflect.Method floodgateIsPlayer;

    /** Bedrock-клиент (Floodgate)? Без Floodgate — только по стейту проверки. */
    private boolean bedrockClient(Player player) {
        if (player == null) {
            return false;
        }
        CheckState st = checks.get(player.getUniqueId());
        if (st != null && st.bedrock) {
            return true;
        }
        try {
            java.lang.reflect.Method m = floodgateIsPlayer;
            Object inst = floodgateApiObj;
            if (m == null || inst == null) {
                if (floodgateUnavailable || Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                    return false;
                }
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                inst = api.getMethod("getInstance").invoke(null);
                if (inst == null) {
                    return false;
                }
                m = api.getMethod("isFloodgatePlayer", UUID.class);
                floodgateIsPlayer = m;
                floodgateApiObj = inst;
            }
            return Boolean.TRUE.equals(m.invoke(inst, player.getUniqueId()));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Floodgate есть, но его API не виден этому плагину: не бросаем
            // ClassNotFoundException на каждой проверке (дорого — в профиле)
            floodgateUnavailable = true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile boolean floodgateUnavailable;

    // ── Режим наплыва ─────────────────────────────────────────────────────
    private volatile boolean surgeEnabled = true;
    private volatile int verifyViewDistance = 3;
    private volatile int surgeTrigger = 15;
    private volatile int surgeStartsPerSecond = 3;
    private volatile long surgeCalmMs = 30_000L;
    private volatile long surgeUntil;
    private final java.util.ArrayDeque<Long> surgeJoins = new java.util.ArrayDeque<>();
    private long surgeSecond;
    private int surgeStarts;

    /** Идёт наплыв (волна входов на проверку)? */
    public boolean isSurge() {
        return surgeEnabled && System.currentTimeMillis() < surgeUntil;
    }

    /**
     * Учёт входа на проверку + решение, можно ли стартовать СЕЙЧАС. Во время
     * наплыва стартуем не больше surge.max_starts_per_second проверок в
     * секунду (арена, телепорт, мир — самое дорогое), остальные ждут на месте
     * с боссбаром и выпускаются очередью по жетонам.
     */
    private synchronized boolean surgeAllowStart() {
        if (!surgeEnabled) {
            return true;
        }
        long now = System.currentTimeMillis();
        surgeJoins.addLast(now);
        while (!surgeJoins.isEmpty() && now - surgeJoins.peekFirst() > 10_000L) {
            surgeJoins.pollFirst();
        }
        if (surgeJoins.size() >= surgeTrigger) {
            if (now >= surgeUntil) {
                plugin.getLogger().warning("AntiBot: наплыв игроков (" + surgeJoins.size()
                        + " входов за 10 с) — проверки стартуют дозированно, по "
                        + surgeStartsPerSecond + " в секунду");
            }
            surgeUntil = now + surgeCalmMs;
        }
        if (now >= surgeUntil) {
            return true;
        }
        return takeSurgeToken(now);
    }

    private synchronized boolean takeSurgeToken(long now) {
        long sec = now / 1000L;
        if (sec != surgeSecond) {
            surgeSecond = sec;
            surgeStarts = 0;
        }
        if (surgeStarts >= surgeStartsPerSecond) {
            return false;
        }
        surgeStarts++;
        return true;
    }

    /** Сколько ещё проверок можно запустить в эту секунду (без наплыва — без лимита). */
    private synchronized int surgeTokensLeft() {
        if (!isSurge()) {
            return Integer.MAX_VALUE;
        }
        long sec = System.currentTimeMillis() / 1000L;
        if (sec != surgeSecond) {
            return surgeStartsPerSecond;
        }
        return Math.max(0, surgeStartsPerSecond - surgeStarts);
    }

    /** Держать чанк арены/платформы загруженным: телепорт не ждёт загрузку чанка. */
    private void keepChunk(World w, int x, int z) {
        if (w == null) {
            return;
        }
        try {
            w.addPluginChunkTicket(x >> 4, z >> 4, plugin);
        } catch (Throwable ignored) {
            // API тикетов нет (старое ядро) — просто без удержания
        }
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
            // Новый бар — принудительная отрисовка, дедуп по старым
            // значениям иначе пропустит setTitle и бар будет пустым.
            st.lastBarStage = null;
            st.lastBarSec = -1;
        }
        try {
            Stage stage = getCurrentStage(player.getUniqueId());
            long sec = Math.max(0L, (st.stageDeadline - System.currentTimeMillis()) / 1000L);
            // Отправляем пакеты только когда реально что-то сменилось:
            // этап (цвет+прогресс) или секунда в титле. На CAMERA-этапе
            // updateBar зовётся на каждый move-пакет (~20/с) — без дедупа
            // это 20 setTitle-пакетов и сборка строки каждый раз.
            if (stage == st.lastBarStage && sec == st.lastBarSec) {
                return;
            }
            st.lastBarStage = stage;
            st.lastBarSec = sec;
            st.bar.setColor(barColor(stage));
            List<Stage> order = st.stages != null ? st.stages : stageOrder;
            double progress = order.isEmpty() ? 0.0
                    : (double) st.stageIndex / (double) order.size();
            st.bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
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
        // MATH с показом в боссбаре: пример держим в заголовке весь этап —
        // раньше ежесекундный отсчёт затирал его через ≤1 с
        if (st.mathExpr != null && !"chat".equalsIgnoreCase(mathWhere)
                && st.stageIndex >= 0 && st.stageIndex < order.size()
                && order.get(st.stageIndex) == Stage.MATH) {
            ph.put("expr", st.mathExpr);
            text = ms == null ? null : ms.message("antibot_bossbar_math", ph);
            if (text == null || text.isEmpty()) {
                text = "&eРеши: &f" + st.mathExpr
                        + (secondsLeft > 0 ? " &7(" + secondsLeft + "с)" : "");
            }
            return toBarText(text);
        }
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

    /**
     * Код на карте в руке. Одна MapView на сервер (без утечки map-id на
     * каждую капчу), рендерер контекстный: каждому игроку рисуется ЕГО
     * код — крупно, со сдвигом символов, волнистыми линиями и шумом по
     * сиду проверки. Текстом код никуда не уходит (см. sendCaptcha).
     * @return true — карта выдана; false — показать код обычным текстом
     */
    private boolean giveCaptchaMap(Player player, CheckState st) {
        try {
            org.bukkit.map.MapView view = captchaView(player.getWorld());
            if (view == null) {
                return false;
            }
            st.captchaDrawn = null;
            st.captchaSeed = random.nextLong();
            org.bukkit.inventory.ItemStack map = new org.bukkit.inventory.ItemStack(Material.FILLED_MAP);
            org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) map.getItemMeta();
            if (meta == null) {
                return false;
            }
            meta.setMapView(view);
            try {
                me.vorchun.registerplugin.util.ItemTag.mark(meta, lobbyLootKey);
            } catch (Throwable ignored) {
            }
            map.setItemMeta(meta);
            player.getInventory().setItemInMainHand(map);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private volatile org.bukkit.map.MapView captchaView;

    private org.bukkit.map.MapView captchaView(World fallback) {
        org.bukkit.map.MapView v = captchaView;
        if (v != null) {
            return v;
        }
        World w = verifyWorld != null ? verifyWorld : fallback;
        if (w == null) {
            return null;
        }
        v = Bukkit.createMap(w);
        v.getRenderers().forEach(v::removeRenderer);
        try {
            v.setTrackingPosition(false);
        } catch (Throwable ignored) {
        }
        v.addRenderer(new org.bukkit.map.MapRenderer(true) {
            @Override
            public void render(org.bukkit.map.MapView mv, org.bukkit.map.MapCanvas canvas, Player p) {
                CheckState st = checks.get(p.getUniqueId());
                if (st == null || st.code == null || st.code.equals(st.captchaDrawn)) {
                    return;
                }
                st.captchaDrawn = st.code;
                drawCaptcha(canvas, st.code, st.captchaSeed);
            }
        });
        captchaView = v;
        return v;
    }

    private static volatile byte[] captchaColors;

    /** Цвета капчи: фон, чернила, шум — MapPalette считается один раз. */
    @SuppressWarnings("deprecation")
    private static byte[] captchaColors() {
        byte[] c = captchaColors;
        if (c == null) {
            try {
                c = new byte[]{
                        org.bukkit.map.MapPalette.matchColor(new java.awt.Color(236, 232, 218)),
                        org.bukkit.map.MapPalette.matchColor(new java.awt.Color(30, 30, 60)),
                        org.bukkit.map.MapPalette.matchColor(new java.awt.Color(150, 150, 170))
                };
            } catch (Throwable t) {
                // matchColor помечен к удалению в новых версиях — готовые индексы
                // палитры карт: белый, чёрный, светло-серый
                c = new byte[]{34, 119, 90};
            }
            captchaColors = c;
        }
        return c;
    }

    /**
     * Капча на холст 128×128: символы шрифта Minecraft увеличены в 2–3
     * раза, у каждого свой сдвиг по Y, две волнистые линии чернилами
     * поперёк текста и ~4% шума. Человек читает сразу; OCR/таблица по
     * байтам карты — нет (каждая проверка рисуется по своему сиду).
     */
    private static void drawCaptcha(org.bukkit.map.MapCanvas canvas, String code, long seed) {
        byte[] col = captchaColors();
        byte bg = col[0];
        byte ink = col[1];
        byte noise = col[2];
        java.util.Random r = new java.util.Random(seed);
        long s = seed ^ 0x2545F4914F6CDD1DL; // шум фона ~4% — xorshift, не Random на пиксель
        if (s == 0) {
            s = 1;
        }
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                canvas.setPixel(x, y, (s & 1023) < 41 ? noise : bg);
            }
        }
        org.bukkit.map.MapFont font = org.bukkit.map.MinecraftFont.Font;
        int scale = code.length() <= 6 ? 3 : 2;
        int total = 0;
        for (int i = 0; i < code.length(); i++) {
            org.bukkit.map.MapFont.CharacterSprite sp = font.getChar(code.charAt(i));
            if (sp != null) {
                total += (sp.getWidth() + 1) * scale + 1;
            }
        }
        int x = Math.max(2, (128 - total) / 2 + r.nextInt(9) - 4);
        int startX = x;
        int baseY = 64 - 4 * scale;
        int jitter = 2 * scale;
        for (int i = 0; i < code.length(); i++) {
            org.bukkit.map.MapFont.CharacterSprite sp = font.getChar(code.charAt(i));
            if (sp == null) {
                continue;
            }
            int oy = baseY + r.nextInt(2 * jitter + 1) - jitter;
            for (int row = 0; row < sp.getHeight(); row++) {
                for (int c = 0; c < sp.getWidth(); c++) {
                    if (!sp.get(row, c)) {
                        continue;
                    }
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = x + c * scale + sx;
                            int py = oy + row * scale + sy;
                            if (px >= 0 && px < 128 && py >= 0 && py < 128) {
                                canvas.setPixel(px, py, ink);
                            }
                        }
                    }
                }
            }
            x += (sp.getWidth() + 1) * scale + r.nextInt(2);
        }
        // Волнистые линии-помехи того же цвета, что и текст (1px против
        // штриха 2–3px — человеку не мешают, цветом их не отфильтровать)
        for (int l = 0; l < 2; l++) {
            double phase = r.nextDouble() * Math.PI * 2;
            double amp = 3 + r.nextInt(5);
            double period = 24 + r.nextInt(24);
            int yc = baseY + 4 * scale + r.nextInt(4 * scale + 1) - 2 * scale;
            for (int px = 0; px < 128; px++) {
                int py = yc + (int) Math.round(amp * Math.sin(phase + px * Math.PI * 2 / period));
                if (py >= 0 && py < 128) {
                    canvas.setPixel(px, py, ink);
                }
            }
        }
        if (captchaStrike) {
            // «Зачёркнутая» капча: линии цвета текста через середину и крест-
            // накрест по диагоналям. Склеивают буквы в одно пятно для OCR,
            // человеку не мешают: линия 1px, штрих буквы 2–3px
            int x0 = Math.max(0, startX - 4);
            int x1 = Math.min(127, x + 3);
            int yTop = Math.max(0, baseY - jitter);
            int yBot = Math.min(127, baseY + 8 * scale + jitter);
            int mid = baseY + 4 * scale;
            strike(canvas, x0, mid + r.nextInt(5) - 2, x1, mid + r.nextInt(5) - 2, ink);
            strike(canvas, x0, yTop + r.nextInt(4), x1, yBot - r.nextInt(4), ink);
            strike(canvas, x0, yBot - r.nextInt(4), x1, yTop + r.nextInt(4), ink);
        }
    }

    /** Капча на карте с зачёркиванием (antibot.captcha_strike), по умолчанию выкл. */
    private static volatile boolean captchaStrike;

    /** Линия 1px (Брезенхэм) — десятки пикселей, рисуется один раз на код. */
    private static void strike(org.bukkit.map.MapCanvas canvas, int x0, int y0, int x1, int y1, byte ink) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        for (int i = 0; i < 512; i++) {
            if (x0 >= 0 && x0 < 128 && y0 >= 0 && y0 < 128) {
                canvas.setPixel(x0, y0, ink);
            }
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
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

    private static final int READY = 1448549759;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x100e) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x100e) == READY;
    }
}
