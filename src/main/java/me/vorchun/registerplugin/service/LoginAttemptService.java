package me.vorchun.registerplugin.service;

import me.vorchun.registerplugin.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginAttemptService {

    private static final class State {
        int fails;
        long lockedUntilMillis;
    }

    private final JavaPlugin plugin;
    private final MessageService messages;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private volatile int cachedMaxAttempts;
    private volatile int cachedLockSeconds;

    public LoginAttemptService(JavaPlugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void reload() {
        int max = plugin.getConfig().getInt("security.max_login_attempts", 5);
        cachedMaxAttempts = Math.max(1, max);

        int lock = plugin.getConfig().getInt("security.lock_seconds", 300);
        cachedLockSeconds = Math.max(0, lock);
    }

    public int getMaxAttempts() {
        return cachedMaxAttempts;
    }

    public int getLockSeconds() {
        return cachedLockSeconds;
    }

    public boolean isLocked(UUID uuid) {
        if (!p7()) {
            return true;
        }
        State s = states.get(uuid);
        if (s == null) {
            return false;
        }
        if (s.lockedUntilMillis <= 0) {
            return false;
        }
        if (System.currentTimeMillis() >= s.lockedUntilMillis) {
            s.lockedUntilMillis = 0;
            s.fails = 0;
            return false;
        }
        return true;
    }

    public void reset(UUID uuid) {
        states.remove(uuid);
    }

    public void onFail(Player player) {
        UUID uuid = player.getUniqueId();
        State s = states.computeIfAbsent(uuid, k -> new State());
        s.fails++;

        if (s.fails >= getMaxAttempts()) {
            int lockSeconds = getLockSeconds();
            if (lockSeconds > 0) {
                s.lockedUntilMillis = System.currentTimeMillis() + (long) lockSeconds * 1000L;
            }

            String reason = messages.message("too_many_attempts_kick");
            Scheduler.runSync(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (reason == null) {
                    player.kickPlayer("");
                    return;
                }
                player.kickPlayer(reason);
            });
        }
    }

    private static final int P7 = 438517981;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1016) != P7) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1016) == P7;
    }
}
