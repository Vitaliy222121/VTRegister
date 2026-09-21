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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Пакетная проверка свободного падения — Limbo-стиль, чистый Netty.
 *
 * Без спавна Entity и без загрузки чанков: игроку шлётся реальный телепорт
 * в воздух над ареной (чанк уже гружен), дальше вся валидация идёт по
 * serverbound-пакетам на eventloop — асинхронно, без main-thread.
 *
 * Проверка:
 *  1) ждём AcceptTeleportation (<= confirmTimeoutMs, иначе disconnect);
 *  2) первый Position-пакет = базовая точка, дальше замеряем dy за тик;
 *  3) эталон скорости ванили: v = (v - 0.08) * 0.98, допуск tolerance
 *     (Bedrock/Geyser — toleranceBedrock);
 *  4) фейл: onGround=true в воздухе, линейное падение (одинаковый dy),
 *     тишина по move-пакетам > moveTimeoutMs, превышение лимита нарушений.
 *
 * Zero-alloc в горячем пути: сессия и Field[] кэшируются, на пакет —
 * только map-lookup и примитивные чтения через рефлексию.
 */
final class FallPacketCheck {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final String HANDLER = "vt_fallcheck";
    private static final AttributeKey<UUID> UID = AttributeKey.valueOf("vt_fall_uid");
    private static final int P7 = -816388189; // инъектор подставляет при сборке

    private final Plugin plugin;
    private final AntiBotService service;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Кэш полей на класс пакета: [x, y, z, onGround]. */
    private final ConcurrentHashMap<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    /** Цепочка полей CraftPlayer -> Channel (решается один раз). */
    private volatile Field[] channelPath;
    private volatile boolean channelBroken;

    // конфиг (перечитывается в reload)
    volatile long confirmTimeoutMs = 2500;
    volatile long moveTimeoutMs = 3000;
    volatile long maxDurationMs = 10000;
    volatile int requiredTicks = 4;
    volatile int maxViolations = 3;
    volatile int airHeight = 12;
    volatile double tolerance = 0.005;
    volatile double toleranceBedrock = 0.03;

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
        tolerance = Math.max(0.0005, c.getDouble("antibot.fall_tolerance", 0.005));
        toleranceBedrock = Math.max(tolerance, c.getDouble("antibot.fall_tolerance_bedrock", 0.03));
    }

    /** Состояние одной проверки падения — без локов, только volatile. */
    private static final class Session {
        volatile int phase;            // 0 = ждём confirm, 1 = замер падения
        volatile long deadline;        // ms — confirm или тишина move-пакетов
        volatile long born;            // ms — общий потолок проверки
        volatile double prevY = Double.NaN;
        volatile double vel;           // ожидаемая скорость ванили (< 0 вниз)
        volatile int ticks;            // измеренные тики падения
        volatile int violStreak;       // подряд идущие отклонения от формулы
        volatile double lastDy = Double.NaN;
        volatile int linearStreak;     // подряд идущие одинаковые dy
        final double tol;
        final double floorY;           // уровень пола арены — onGround ниже его законен
        volatile Channel channel;

        Session(double tol, double floorY) {
            this.tol = tol;
            this.floorY = floorY;
        }
    }

    /**
     * Запуск проверки: инжект хендлера в pipeline + телепорт в воздух.
     * Вызывать с main-thread (teleport — Bukkit API).
     * @return false, если канал недоступен — сервис уходит на legacy-платформу
     */
    boolean start(Player p, World w, double x, double floorY, double z, boolean bedrock) {
        // целостность: патч класса/подписи -> тихий фолбэк на legacy-проверку
        if (Sec.t(0x102c) != P7 || !Sec.s()) {
            return false;
        }
        Session s = new Session(bedrock ? toleranceBedrock : tolerance, floorY);
        Channel ch = channelOf(p);
        if (ch == null || !ch.isActive()) {
            return false;
        }
        ch.attr(UID).set(p.getUniqueId());
        if (ch.pipeline().get(HANDLER) == null) {
            ch.pipeline().addBefore("packet_handler", HANDLER, new Inbound());
        }
        s.channel = ch;
        long now = System.currentTimeMillis();
        s.born = now;
        s.deadline = now + confirmTimeoutMs;
        sessions.put(p.getUniqueId(), s);
        // Реальный телепорт: сервер сам шлёт PositionAndLook с teleportId,
        // клиент обязан ответить AcceptTeleportation — ждём его в handler'е.
        service.authorizedTeleport(p,
                new Location(w, x, floorY + airHeight, z, 180f, 15f));
        return true;
    }

    /** Снять сессию + хендлер (конец этапа, фейл, выход игрока). */
    void stop(UUID uuid) {
        Session s = sessions.remove(uuid);
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
            String reason = null;
            if (now - s.born > maxDurationMs) {
                reason = "duration";
            } else if (now > s.deadline) {
                reason = s.phase == 0 ? "confirm-timeout" : "no-move-packets";
            }
            if (reason != null) {
                finish(e.getKey(), false, reason);
            }
        }
    }

    // ---------- внутренности ----------

    private void finish(UUID uuid, boolean pass, String reason) {
        Session s = sessions.get(uuid);
        if (s == null) {
            return;
        }
        stop(uuid);
        Player p = plugin.getServer().getPlayer(uuid);
        if (p != null && p.isOnline()) {
            // Результат — на main: pass/fail трогают мир и инвентарь
            Scheduler.runAtEntity(plugin, p,
                    () -> service.onFallPacketResult(uuid, pass, reason));
        }
    }

    /** Inbound-хендлер: только классификация + чтение полей, ничего не блокируем. */
    private final class Inbound extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            UUID u = ctx.channel().attr(UID).get();
            if (u != null) {
                Session s = sessions.get(u);
                if (s != null) {
                    try {
                        onPacket(u, s, msg);
                    } catch (Throwable ignored) {
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
        // Confirm телепорта: PacketPlayInTeleportAccept (<=1.16) /
        // ServerboundAcceptTeleportationPacket (1.17+)
        if (name.contains("TeleportAccept") || name.contains("AcceptTeleportation")) {
            if (s.phase == 0) {
                s.phase = 1;
                s.deadline = System.currentTimeMillis() + moveTimeoutMs;
            }
            return;
        }
        if (s.phase == 0 || !isMovePacket(name)) {
            return;
        }
        s.deadline = System.currentTimeMillis() + moveTimeoutMs;
        boolean hasPos = hasPosition(name);
        boolean ground = readOnGround(msg);
        // onGround в воздухе (выше пола арены) — клиент летит/фризит высоту
        if (ground && !hasPos && s.ticks > 0
                && !Double.isNaN(s.prevY) && s.prevY > s.floorY + 2.0) {
            finish(uuid, false, "ground-in-air");
            return;
        }
        if (!hasPos) {
            return; // Rot/StatusOnly — таймер сброшен, физику не меряем
        }
        double y = readField(msg, 1); // поле Y — второй double
        if (Double.isNaN(y)) {
            return;
        }
        if (ground && y > s.floorY + 2.0) {
            finish(uuid, false, "ground-in-air");
            return;
        }
        if (Double.isNaN(s.prevY)) {
            // первый пакет позиции — базовая точка отсчёта
            s.prevY = y;
            return;
        }
        double dy = s.prevY - y; // > 0 при падении
        s.prevY = y;
        // эталон ванили: v = (v - 0.08) * 0.98; ожидаемый сдвиг = -v
        s.vel = (s.vel - GRAVITY) * DRAG;
        double expect = -s.vel;
        if (Math.abs(dy - expect) > s.tol
                && ++s.violStreak >= maxViolations) {
            finish(uuid, false, "physics-mismatch");
            return;
        } else if (Math.abs(dy - expect) <= s.tol) {
            s.violStreak = 0;
        }
        // линейное падение: dy не растёт — гравитацию не симулируют
        if (!Double.isNaN(s.lastDy) && Math.abs(dy - s.lastDy) < 1.0e-6
                && ++s.linearStreak >= 3) {
            finish(uuid, false, "linear-fall");
            return;
        } else if (Double.isNaN(s.lastDy) || Math.abs(dy - s.lastDy) >= 1.0e-6) {
            s.linearStreak = 0;
        }
        s.lastDy = dy;
        if (++s.ticks >= requiredTicks) {
            finish(uuid, true, null);
        }
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
            return (Channel) cur;
        } catch (Throwable t) {
            return null;
        }
    }

    /** EntityPlayer(.playerConnection|connection) -> (.networkManager|connection) -> .channel */
    private Field[] resolveChannelPath(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Field f1 = findField(handle.getClass(), "playerConnection", "connection");
            if (f1 == null) {
                return null;
            }
            Object conn = f1.get(handle);
            Field f2 = findField(conn.getClass(), "networkManager", "connection");
            if (f2 == null) {
                return null;
            }
            Object nm = f2.get(conn);
            Field f3 = findField(nm.getClass(), "channel");
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

    private double readField(Object msg, int idx) {
        Field[] f = fields(msg.getClass());
        if (f == null) {
            return Double.NaN;
        }
        try {
            return f[idx].getDouble(msg);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private boolean readOnGround(Object msg) {
        Field[] f = fields(msg.getClass());
        if (f == null) {
            return false;
        }
        try {
            return f[3].getBoolean(msg);
        } catch (Throwable t) {
            return false;
        }
    }
}
