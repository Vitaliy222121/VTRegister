// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import me.vorchun.registerplugin.util.Scheduler;
import me.vorchun.registerplugin.util.Data;

/**
 * Пакетная проверка свободного падения — Limbo-стиль, чистый Netty.
 *
 * Без спавна Entity и без загрузки чанков: игроку шлётся реальный телепорт
 * в воздух над ареной (чанк уже гружен), дальше вся валидация идёт по
 * serverbound-пакетам на eventloop — асинхронно, без main-thread.
 *
 * Проверка:
 *  1) ждём AcceptTeleportation (<= confirmTimeoutMs);
 *  2) базовая точка = эхо телепорта РОВНО в точке телепорта (ванильный
 *     клиент сразу после accept шлёт PositionLook с координатами цели);
 *     не совпала — один повтор телепорта, дальше legacy-фолбэк;
 *  3) эталон скорости ванили: v = (v - 0.08) * 0.98, допуск tolerance —
 *     проверяется на КАЖДОМ сэмпле падения, проход только после
 *     minTicks сэмплов и реального пролёта почти всей высоты;
 *  4) фейл (кик): onGround в воздухе, линейное падение, серия отклонений
 *     от формулы, «прыжок» вниз одним пакетом, приземление раньше физики.
 *
 * Инфраструктура НИКОГДА не кикает: исключение, «слепой» хендлер (пакеты
 * идут мимо), нет packet_handler в pipeline, тишина по пакетам — результат
 * с причиной {@link #R_BROKEN}/{@link #R_TIMEOUT}, сервис уходит на
 * legacy-падение по событиям Bukkit. При доказанной поломке перехвата
 * пакетный режим выключается до рестарта (одно предупреждение в лог).
 *
 * Zero-alloc в горячем пути: сессия и Field[] кэшируются, на пакет —
 * только map-lookup и примитивные чтения через рефлексию.
 */
final class FallPacketCheck {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final String HANDLER = "vt_fallcheck";
    private static final AttributeKey<UUID> UID = AttributeKey.valueOf("vt_fall_uid");
    private static final int READY = 1448549725; // инъектор подставляет при сборке

    private static boolean ready() {
        return Data.mix(0x102c) == READY;
    }

    /**
     * Причины результата, означающие сбой ИНФРАСТРУКТУРЫ, а не бота.
     * С ними AntiBotService не кикает, а повторяет это повторение падения
     * на legacy-платформе (события Bukkit).
     */
    static final String R_BROKEN = "broken";
    static final String R_TIMEOUT = "timeout";

    /** true = причина инфраструктурная: не кик, а legacy-фолбэк повторения. */
    static boolean isInfraReason(String reason) {
        return R_BROKEN.equals(reason) || R_TIMEOUT.equals(reason);
    }

    /** Подряд сессий, где хендлер не увидел НИ ОДНОГО пакета. */
    private static final int ZERO_SEEN_DEAD = 3;
    /** Сколько Bukkit-движений при нуле пакетов в хендлере = хендлер слепой. */
    private static final int BLIND_MOVES = 3;
    /** Эхо телепорта совпадает с целью с точностью до этого значения. */
    // Допуск эха телепорта: клиент (особенно новые версии через ViaVersion)
    // может прислать первую позицию уже после 1–2 тиков падения. Раньше при
    // 0.1 такой игрок получал ПОВТОРНЫЙ телепорт наверх, а потом ещё и
    // legacy-подброс — визуально один повтор физики шёл 2–3 раза.
    private static final double ORIGIN_EPS = 1.0;
    /** Запас над формулой, после которого dy — это «прыжок» вниз телепортом. */
    private static final double DROP_MARGIN = 0.5;

    private final Plugin plugin;
    private final AntiBotService service;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Кэш полей на класс пакета: [x, y, z, onGround]. */
    private final ConcurrentHashMap<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    /** Цепочка полей CraftPlayer -> Channel (решается один раз). */
    private volatile Field[] channelPath;
    private volatile boolean channelBroken;
    /** Перехват доказанно не работает — пакетный режим выключен до рестарта. */
    private volatile boolean packetsDead;
    private final AtomicBoolean deadLogged = new AtomicBoolean();
    private volatile int zeroSeenStreak;

    // конфиг (перечитывается в reload)
    volatile long confirmTimeoutMs = 2500;
    volatile long moveTimeoutMs = 3000;
    volatile long maxDurationMs = 10000;
    volatile int requiredTicks = 4;
    volatile int maxViolations = 3;
    volatile int airHeight = 12;
    volatile double tolerance = 0.005;
    volatile double toleranceBedrock = 0.03;
    volatile boolean bedrockPackets;
    /** Минимум сэмплов падения до прохода — из симуляции ванили в reload. */
    volatile int minTicks = 15;
    /** Минимум сэмплов, совпавших с формулой. */
    volatile int minGood = 7;

    // ── Фоновая проверка с самого входа: флуд пакетами и битые координаты ──
    private static final String WATCH = "vt_watch";
    /** Класс пакета → «движение с координатами» (фоновая проверка с входа). */
    private final java.util.Map<Class<?>, Boolean> posPacket = new java.util.concurrent.ConcurrentHashMap<>();

    /** Встроить наблюдатель пакетов игроку (до авторизации). Молча выходит, если перехват недоступен. */
    void watch(Player p) {
        if (packetsDead) {
            return;
        }
        try {
            Channel ch = channelOf(p);
            if (ch == null || !ch.isActive() || ch.pipeline().get(WATCH) != null
                    || ch.pipeline().get("packet_handler") == null) {
                return;
            }
            ch.pipeline().addBefore("packet_handler", WATCH, new Watch(p.getUniqueId()));
        } catch (Throwable ignored) {
            // перехват не встаёт (как на сервере владельца) — фон просто выключен
        }
    }

    void unwatch(Player p) {
        try {
            Channel ch = channelOf(p);
            if (ch != null && ch.pipeline().get(WATCH) != null) {
                ch.pipeline().remove(WATCH);
            }
        } catch (Throwable ignored) {
        }
    }

    private final class Watch extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final UUID uuid;
        private long second;
        private int count;
        private int floodSeconds;
        private volatile boolean flagged;

        Watch(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!flagged) {
                try {
                    inspect(msg);
                } catch (Throwable ignored) {
                }
            }
            super.channelRead(ctx, msg);
        }

        private void inspect(Object msg) {
            long s = System.currentTimeMillis() / 1000L;
            if (s != second) {
                // Ваниль шлёт 20–80 пакетов/с; сотни ПОДРЯД несколько секунд — флуд бота
                floodSeconds = count > service.packetWatchMaxPps() ? floodSeconds + 1 : 0;
                second = s;
                count = 0;
                if (floodSeconds >= service.packetWatchSeconds()) {
                    flag("флуд пакетами");
                    return;
                }
            }
            count++;
            // Тип пакета по имени класса — один раз на класс, а не на каждый пакет
            Class<?> cls = msg.getClass();
            Boolean pos = posPacket.get(cls);
            if (pos == null) {
                String name = cls.getName();
                pos = isMovePacket(name) && hasPosition(name);
                posPacket.put(cls, pos);
            }
            if (pos) {
                Field[] pf = fields(msg.getClass());
                if (pf != null) {
                    double x = readField(pf, msg, 0);
                    double y = readField(pf, msg, 1);
                    double z = readField(pf, msg, 2);
                    if (bad(x) || bad(y) || bad(z) || Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7
                            || Math.abs(y) > 1.0E5) {
                        flag("невозможные координаты");
                    }
                }
            }
        }

        private void flag(String reason) {
            flagged = true;
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                Scheduler.runAtEntity(plugin, p, () -> service.onBackgroundFlag(uuid, reason));
            }
        }
    }

    private static boolean bad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    FallPacketCheck(Plugin plugin, AntiBotService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void reload() {
        org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
        confirmTimeoutMs = Math.max(500, c.getLong("antibot.fall_confirm_timeout_ms", 2500));
        moveTimeoutMs = Math.max(500, c.getLong("antibot.fall_move_timeout_ms", 3000));
        maxDurationMs = Math.max(2000, c.getLong("antibot.fall_max_duration_ms", 10000));
        requiredTicks = Math.max(2, Math.min(20, c.getInt("antibot.fall_ticks", 4)));
        maxViolations = Math.max(1, c.getInt("antibot.fall_max_violations", 3));
        airHeight = Math.max(6, Math.min(80, c.getInt("antibot.fall_height", 12)));
        tolerance = Math.max(0.0005, c.getDouble("antibot.fall_tolerance", 0.01));
        toleranceBedrock = Math.max(tolerance, c.getDouble("antibot.fall_tolerance_bedrock", 0.05));
        // Допуск Bedrock/Geyser замером не подтверждён — по умолчанию
        // Bedrock идёт на legacy-падение (не кикаем эвристикой).
        bedrockPackets = c.getBoolean("antibot.fall_packets_bedrock", false);
        // Ванильное падение с высоты airHeight: сколько тиков нужно, чтобы
        // пролететь airHeight-1.5 блока. Запас 3 тика на округления.
        double v = 0.0;
        double d = 0.0;
        int n = 0;
        double need = airHeight - 1.5;
        while (d < need && n < 400) {
            v = (v - GRAVITY) * DRAG;
            d -= v;
            n++;
        }
        minTicks = Math.max(requiredTicks, n - 3);
        minGood = Math.max(requiredTicks, minTicks / 2);
    }

    /** Состояние одной проверки падения — без локов, только volatile. */
    private static final class Session {
        volatile int phase;            // 0 = ждём confirm, 1 = замер падения
        volatile long deadline;        // ms — confirm или тишина move-пакетов
        volatile long born;            // ms — общий потолок проверки
        volatile int seen;             // любые inbound-пакеты канала (живость)
        volatile int bukkitMoves;      // PlayerMoveEvent по игроку (от сервиса)
        volatile int accepts;          // AcceptTeleportation за сессию
        volatile boolean retried;      // повтор телепорта уже был
        volatile double baseY = Double.NaN;
        volatile double prevY = Double.NaN;
        volatile int samples;          // сэмплы падения после базы
        volatile int good;             // сэмплы, совпавшие с формулой
        volatile int violStreak;       // подряд идущие отклонения от формулы
        volatile double lastDy = Double.NaN;
        volatile int linearStreak;     // подряд идущие одинаковые dy
        final double tol;
        final double floorY;           // уровень пола арены — onGround ниже его законен
        final double targetY;          // высота телепорта (floorY + airHeight)
        final Location target;
        volatile Channel channel;

        Session(double tol, double floorY, Location target) {
            this.tol = tol;
            this.floorY = floorY;
            this.target = target;
            this.targetY = target.getY();
        }

        /** Новый телепорт принят — всё мерим заново от новой базы. */
        void resetMeasure() {
            baseY = Double.NaN;
            prevY = Double.NaN;
            lastDy = Double.NaN;
            samples = 0;
            good = 0;
            violStreak = 0;
            linearStreak = 0;
        }
    }

    /** Перехват доказанно сломан (до рестарта) — сервис сразу идёт в legacy. */
    boolean isBroken() {
        return packetsDead || channelBroken;
    }

    /**
     * Сервис видит PlayerMoveEvent игрока в пакетном режиме. Нужен для
     * живости: сервер движение обработал, а хендлер пакетов не видел —
     * значит хендлер «слепой».
     */
    void onBukkitMove(UUID uuid) {
        Session s = sessions.get(uuid);
        if (s != null) {
            s.bukkitMoves++;
        }
    }

    /**
     * Запуск проверки: инжект хендлера в pipeline + телепорт в воздух.
     * Вызывать с main-thread (teleport — Bukkit API).
     * @return false, если пакетный режим недоступен — сервис уходит на legacy-платформу
     */
    boolean start(Player p, World w, double x, double floorY, double z, boolean bedrock) {
        // сборка повреждена -> тихий фолбэк на legacy-проверку
        if (Data.mix(0x102c) != READY || !Data.sealed()) {
            return false;
        }
        if (packetsDead || p == null || w == null || (bedrock && !bedrockPackets)) {
            return false;
        }
        UUID uuid = p.getUniqueId();
        Location target = new Location(w, x, floorY + airHeight, z, 180f, 15f);
        Session s = new Session(bedrock ? toleranceBedrock : tolerance, floorY, target);
        Channel ch = channelOf(p);
        if (ch == null || !ch.isActive()) {
            return false;
        }
        try {
            if (ch.pipeline().get(HANDLER) == null) {
                if (ch.pipeline().get("packet_handler") == null) {
                    markDead("в pipeline нет packet_handler");
                    return false;
                }
                ch.pipeline().addBefore("packet_handler", HANDLER, new Inbound());
            }
            ch.attr(UID).set(uuid);
        } catch (Throwable t) {
            markDead("не удалось встроить хендлер: " + t.getClass().getSimpleName());
            return false;
        }
        s.channel = ch;
        long now = System.currentTimeMillis();
        s.born = now;
        s.deadline = now + confirmTimeoutMs;
        sessions.put(uuid, s);
        // Реальный телепорт: сервер сам шлёт PositionAndLook с teleportId,
        // клиент обязан ответить AcceptTeleportation — ждём его в handler'е.
        try {
            service.authorizedTeleport(p, target.clone());
        } catch (Throwable t) {
            stop(uuid);
            return false;
        }
        return true;
    }

    /** Снять сессию + хендлер (конец этапа, фейл, выход игрока). */
    void stop(UUID uuid) {
        detach(sessions.remove(uuid));
    }

    private static void detach(Session s) {
        if (s != null && s.channel != null) {
            try {
                if (s.channel.pipeline().get(HANDLER) != null) {
                    s.channel.pipeline().remove(HANDLER);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    void stopAll() {
        for (UUID u : sessions.keySet()) {
            stop(u);
        }
    }

    /** Таймауты — зовётся из сервисного тикера (main thread). */
    void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (now - s.born <= maxDurationMs && now <= s.deadline) {
                continue;
            }
            // Таймаут — это НЕ доказательство бота (фриз клиента, лаг,
            // слепой перехват). Решаем только, сломан ли перехват целиком.
            String reason = R_TIMEOUT;
            if (s.seen == 0) {
                if (s.bukkitMoves >= BLIND_MOVES) {
                    // сервер движения обработал, а хендлер не видел ничего
                    markDead("хендлер встроен, но пакетов не видит");
                    reason = R_BROKEN;
                } else if (++zeroSeenStreak >= ZERO_SEEN_DEAD) {
                    markDead(ZERO_SEEN_DEAD + " проверки подряд без единого пакета");
                    reason = R_BROKEN;
                }
            } else if (s.phase == 0 && s.bukkitMoves >= BLIND_MOVES) {
                // пакеты идут, сервер движение принял, а accept мы не узнали —
                // классификация пакетов не подходит к этому ядру
                markDead("AcceptTeleportation не распознан");
                reason = R_BROKEN;
            }
            finish(e.getKey(), s, false, reason);
        }
    }

    // ---------- внутренности ----------

    private void markDead(String why) {
        packetsDead = true;
        if (deadLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning("AntiBot: пакетная проверка падения выключена до рестарта ("
                    + why + ") — используется проверка на событиях Bukkit");
        }
    }

    private void finish(UUID uuid, Session s, boolean pass, String reason) {
        // remove(uuid, s): результат одной сессии применяется ровно один раз,
        // даже если Netty-поток и тикер финишируют одновременно
        if (s == null || !sessions.remove(uuid, s)) {
            return;
        }
        detach(s);
        if (s.seen > 0) {
            zeroSeenStreak = 0;
        }
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && p.isOnline()) {
            // Результат — на main: pass/fail трогают мир и инвентарь
            Scheduler.runAtEntity(plugin, p,
                    () -> service.onFallPacketResult(uuid, pass, reason));
        }
    }

    /** Клиент не встал в точку телепорта — один повтор телепорта в воздух. */
    private void retryTeleport(UUID uuid, Session s) {
        Player p = plugin.getServer().getPlayer(uuid);
        if (p == null) {
            return;
        }
        Scheduler.runAtEntity(plugin, p, () -> {
            if (p.isOnline() && sessions.get(uuid) == s) {
                try {
                    service.authorizedTeleport(p, s.target.clone());
                } catch (Throwable t) {
                    finish(uuid, s, false, R_TIMEOUT);
                }
            }
        });
    }

    /** Inbound-хендлер: только классификация + чтение полей, ничего не блокируем. */
    private final class Inbound extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            UUID u = ctx.channel().attr(UID).get();
            if (u != null) {
                Session s = sessions.get(u);
                if (s != null) {
                    s.seen++;
                    try {
                        onPacket(u, s, msg);
                    } catch (Throwable t) {
                        // исключение в разборе — перехват не годится для этого ядра
                        markDead("ошибка разбора пакета: " + t.getClass().getSimpleName());
                        finish(u, s, false, R_BROKEN);
                    }
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            UUID u = ctx.channel().attr(UID).get();
            if (u != null) {
                sessions.remove(u);
            }
            super.channelInactive(ctx);
        }
    }

    private void onPacket(UUID uuid, Session s, Object msg) {
        String name = msg.getClass().getName();
        long now = System.currentTimeMillis();
        // Confirm телепорта: PacketPlayInTeleportAccept (<=1.16) /
        // ServerboundAcceptTeleportationPacket (1.17+). Любой accept (в т.ч.
        // второй — стенд-телепорт арены или наш повтор) = новая база.
        if (name.contains("TeleportAccept") || name.contains("AcceptTeleportation")) {
            if (++s.accepts > 4) {
                // телепорты сыплются один за другим — замерить нечего
                finish(uuid, s, false, R_TIMEOUT);
                return;
            }
            s.phase = 1;
            s.resetMeasure();
            s.deadline = now + moveTimeoutMs;
            return;
        }
        if (s.phase == 0 || !isMovePacket(name)) {
            return;
        }
        s.deadline = now + moveTimeoutMs;
        boolean hasPos = hasPosition(name);
        Field[] pf = fields(msg.getClass());
        if (pf == null) {
            // не нашли x/y/z/onGround — ядро с другим форматом пакета
            markDead("не распознан формат move-пакета " + msg.getClass().getSimpleName());
            finish(uuid, s, false, R_BROKEN);
            return;
        }
        boolean ground = readOnGround(pf, msg);
        boolean measuring = !Double.isNaN(s.baseY);
        // onGround в воздухе (выше пола арены) — клиент летит/фризит высоту
        if (ground && !hasPos && measuring && s.samples > 0
                && !Double.isNaN(s.prevY) && s.prevY > s.floorY + 2.0) {
            finish(uuid, s, false, "ground-in-air");
            return;
        }
        if (!hasPos) {
            return; // Rot/StatusOnly — таймер сброшен, физику не меряем
        }
        double y = readField(pf, msg, 1); // поле Y — второй double
        if (Double.isNaN(y)) {
            return;
        }
        if (!measuring) {
            // Базовая точка — эхо телепорта: ванильный клиент после accept
            // шлёт PositionLook ровно в точке цели. Иначе клиент телепорт
            // проигнорировал (или его перебил другой телепорт) — один повтор,
            // потом legacy. Кикать тут нельзя: это может быть и лаг.
            if (Math.abs(y - s.targetY) > ORIGIN_EPS) {
                if (!s.retried) {
                    s.retried = true;
                    s.phase = 0;
                    s.deadline = now + confirmTimeoutMs;
                    retryTeleport(uuid, s);
                } else {
                    finish(uuid, s, false, R_TIMEOUT);
                }
                return;
            }
            s.baseY = y;
            s.prevY = y;
            return;
        }
        if (ground && y > s.floorY + 2.0) {
            finish(uuid, s, false, "ground-in-air");
            return;
        }
        double dy = s.prevY - y; // > 0 при падении
        s.prevY = y;
        // Самокалибрующаяся цепочка: ваниль v = (v - 0.08) * 0.98, значит
        // dy_next = dy * 0.98 + 0.0784. Первый сэмпл — от покоя (абсолютный
        // телепорт обнуляет скорость), дальше сверяем каждый.
        double expect = Double.isNaN(s.lastDy) ? GRAVITY * DRAG : s.lastDy * DRAG + GRAVITY * DRAG;
        // «Прыжок» вниз одним пакетом: ни лаг, ни касание пола не дают dy
        // больше формулы (касание dy только обрезает) — сразу фейл.
        if (dy > expect + s.tol + DROP_MARGIN) {
            finish(uuid, s, false, "teleport-drop");
            return;
        }
        s.samples++;
        // Зона пола: реальное приземление (onGround) или опустился до уровня
        // пола (в паутине/мёде onGround не выставляется). Последний dy тут
        // обрезан коллизией — формулу снизу не проверяем.
        if (y <= s.floorY + 1.1) {
            boolean enough = s.samples >= minTicks && s.good >= minGood
                    && s.baseY - y >= airHeight - 1.5
                    && s.violStreak < maxViolations;
            if (enough) {
                finish(uuid, s, true, null);
            } else if (ground && y <= s.floorY + 1.0) {
                // на полу раньше, чем позволяет гравитация
                finish(uuid, s, false, "early-ground");
            }
            s.lastDy = dy;
            return;
        }
        if (!Double.isNaN(s.lastDy)) {
            if (Math.abs(dy - expect) <= s.tol) {
                s.good++;
                s.violStreak = 0;
            } else if (++s.violStreak >= maxViolations) {
                finish(uuid, s, false, "physics-mismatch");
                return;
            }
        } else if (Math.abs(dy - expect) <= s.tol || Math.abs(dy) <= s.tol) {
            // первый сэмпл: от покоя (0.0784) или «пустой» пакет эха (0)
            s.good++;
        }
        // линейное падение: dy не растёт — гравитацию не симулируют
        if (!Double.isNaN(s.lastDy) && Math.abs(dy - s.lastDy) < 1.0e-6) {
            if (++s.linearStreak >= 3) {
                finish(uuid, s, false, "linear-fall");
                return;
            }
        } else {
            s.linearStreak = 0;
        }
        s.lastDy = dy;
    }

    private static boolean isMovePacket(String name) {
        return name.contains("PacketPlayInFlying")
                || name.contains("PacketPlayInPosition")
                || name.contains("PacketPlayInLook")
                || name.contains("MovePlayerPacket");
    }

    private static boolean hasPosition(String name) {
        return name.endsWith("Position") || name.endsWith("PositionLook")
                || name.endsWith("Pos") || name.endsWith("PosRot");
    }

    /** Канал игрока через NMS-цепочку — решается рефлексией один раз. */
    private Channel channelOf(Player p) {
        Field[] path = channelPath;
        if (path == null && !channelBroken) {
            path = resolveChannelPath(p);
            if (path == null) {
                channelBroken = true;
                plugin.getLogger().warning("AntiBot: канал игрока не найден (NMS) — "
                        + "падение проверяется на событиях Bukkit");
                return null;
            }
            channelPath = path;
        }
        if (path == null) {
            return null;
        }
        try {
            Object cur = p.getClass().getMethod("getHandle").invoke(p);
            for (Field f : path) {
                cur = f.get(cur);
                if (cur == null) {
                    return null;
                }
            }
            return cur instanceof Channel ? (Channel) cur : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * EntityPlayer(.playerConnection|connection) -> (.networkManager|connection) -> .channel.
     * На 1.17–1.20.4 (Spigot-маппинг) имена полей обфусцированы (b/a/k) —
     * тогда ищем по ТИПУ поля.
     */
    private Field[] resolveChannelPath(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Field f1 = findField(handle.getClass(), "playerConnection", "connection");
            if (f1 == null) {
                f1 = findFieldByType(handle.getClass(), "PlayerConnection", "ServerGamePacketListener");
            }
            if (f1 == null) {
                return null;
            }
            Object conn = f1.get(handle);
            if (conn == null) {
                return null;
            }
            Field f2 = findField(conn.getClass(), "networkManager", "connection");
            if (f2 == null || f2.getType().isPrimitive()) {
                f2 = findFieldByType(conn.getClass(), "NetworkManager", ".Connection");
            }
            if (f2 == null) {
                return null;
            }
            Object nm = f2.get(conn);
            if (nm == null) {
                return null;
            }
            Field f3 = findField(nm.getClass(), "channel");
            if (f3 == null || !Channel.class.isAssignableFrom(f3.getType())) {
                f3 = null;
                for (Class<?> c = nm.getClass(); c != null && c != Object.class && f3 == null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Channel.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            f3 = f;
                            break;
                        }
                    }
                }
            }
            return f3 == null ? null : new Field[]{f1, f2, f3};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String... names) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                for (String n : names) {
                    if (f.getName().equals(n)) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Первое поле, имя ТИПА которого содержит одну из подстрок. */
    private static Field findFieldByType(Class<?> cls, String... typeParts) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String tn = f.getType().getName();
                for (String part : typeParts) {
                    if (tn.contains(part)) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Поля [x,y,z,onGround] базового класса move-пакета, кэш на класс. */
    private Field[] fields(Class<?> cls) {
        Field[] f = fieldCache.get(cls);
        if (f != null) {
            return f;
        }
        Field[] resolved = resolveFields(cls);
        if (resolved != null) {
            fieldCache.putIfAbsent(cls, resolved);
        }
        return resolved;
    }

    private static Field[] resolveFields(Class<?> cls) {
        // идём вверх по иерархии: базовый класс держит x,y,z + boolean onGround
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] decl = c.getDeclaredFields();
            Field fx = null, fy = null, fz = null, fg = null;
            int doubles = 0;
            for (Field f : decl) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType() == double.class) {
                    if (doubles == 0) {
                        fx = f;
                    } else if (doubles == 1) {
                        fy = f;
                    } else if (doubles == 2) {
                        fz = f;
                    }
                    doubles++;
                } else if (f.getType() == boolean.class && fg == null) {
                    // onGround — первый boolean (spigot "f" / mojmap "onGround")
                    fg = f;
                }
            }
            if (doubles >= 3 && fg != null) {
                for (Field f : new Field[]{fx, fy, fz, fg}) {
                    f.setAccessible(true);
                }
                return new Field[]{fx, fy, fz, fg};
            }
        }
        return null;
    }

    private static double readField(Field[] f, Object msg, int idx) {
        try {
            return f[idx].getDouble(msg);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static boolean readOnGround(Field[] f, Object msg) {
        try {
            return f[3].getBoolean(msg);
        } catch (Throwable t) {
            return false;
        }
    }
}
