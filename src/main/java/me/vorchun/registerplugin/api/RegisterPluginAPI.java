// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.api;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.util.Scheduler;

/**
 * Публичный API RegisterPlugin (VTRegister).
 *
 * Использование из другого плагина:
 * <pre>
 *   RegisterPluginAPI api = RegisterPluginAPI.get();
 *   if (api != null &amp;&amp; api.isAuthenticated(player)) { ... }
 * </pre>
 * Если плагин не установлен — get() вернёт null, всегда проверяйте.
 */
public final class RegisterPluginAPI {

    /** Создаётся ядром плагина; другим плагинам нужен только get(). */
    public RegisterPluginAPI() {
    }

    /** null, если RegisterPlugin не установлен/не включён. */
    public static RegisterPluginAPI get() {
        if (!ready()) {
            return null;
        }
        return RegisterPlugin.getApi();
    }

    public boolean isAuthenticated(Player player) {
        return player != null && isAuthenticated(player.getUniqueId());
    }

    public boolean isAuthenticated(UUID uuid) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        return rp != null && rp.getSessionManager() != null && rp.getSessionManager().isLoggedIn(uuid);
    }

    public boolean isRegistered(UUID uuid) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        return rp != null && rp.getAccountStore() != null && rp.getAccountStore().isRegistered(uuid);
    }

    /**
     * Зарегистрирован ли ник. Для онлайн-игрока отвечает из кэша; для офлайн-ника
     * читает базу — это БЛОКИРУЮЩИЙ вызов: из главного потока для офлайн-ника
     * всегда false (используйте {@link #isRegisteredAsync(String, Consumer)}).
     */
    public boolean isRegistered(String name) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        if (rp == null || rp.getAccountStore() == null || name == null || name.isEmpty()) {
            return false;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return rp.getAccountStore().isRegistered(online.getUniqueId());
        }
        if (Bukkit.isPrimaryThread()) {
            return false;
        }
        return rp.getAccountStore().findByNameBlocking(name) != null;
    }

    /** Асинхронная проверка ника; колбэк — в главном потоке. */
    public void isRegisteredAsync(String name, Consumer<Boolean> callback) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        if (callback == null) {
            return;
        }
        if (rp == null || rp.getAccountStore() == null || name == null || name.isEmpty()) {
            callback.accept(false);
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(rp.getAccountStore().isRegistered(online.getUniqueId()));
            return;
        }
        rp.getAccountStore().findByNameAsync(name, r -> callback.accept(r != null));
    }

    /**
     * Принудительно авторизовать игрока (например, после внешней проверки).
     * Вход идёт общим путём AuthListener (снятие проверок, маршрут после входа,
     * мост прокси), как у админского /authadmin forcelogin.
     */
    public void forceLogin(Player player) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        if (rp == null || player == null || rp.getSessionManager() == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Scheduler.runAtEntity(rp, player, () -> forceLogin(player));
            return;
        }
        if (!player.isOnline() || rp.getSessionManager().isLoggedIn(player.getUniqueId())) {
            return;
        }
        if (rp.getAuthListener() != null && rp.getAccountStore() != null
                && rp.getAccountStore().isRegistered(player.getUniqueId())) {
            rp.getAuthListener().loginWithoutPassword(player, null);
        }
        if (!rp.getSessionManager().isLoggedIn(player.getUniqueId())) {
            // Аккаунта нет (или общий путь недоступен) — прежнее поведение
            rp.getSessionManager().login(player);
            Bukkit.getPluginManager().callEvent(new AuthLoginEvent(player, false));
        }
    }

    public void forceLogout(Player player, boolean kick) {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        if (rp != null && player != null && rp.getAuthService() != null) {
            rp.getAuthService().logout(player, kick);
        }
    }

    /** Зарегистрирован ли плагин и включён. */
    public boolean isAvailable() {
        return RegisterPlugin.getInstance() != null && RegisterPlugin.getInstance().isEnabled();
    }

    /** Текущий backend хранилища: "YAML", "SQLite", "MySQL"… */
    public String getStorageName() {
        RegisterPlugin rp = RegisterPlugin.getInstance();
        return rp == null || rp.getAccountStore() == null ? "-" : rp.getAccountStore().backendName();
    }

    /** Удобная проверка «чужой плагин вообще стоит на сервере». */
    public static boolean isPluginPresent() {
        // Плагин переименован в VTRegister; старое имя — на случай старой сборки
        Plugin p = Bukkit.getPluginManager().getPlugin("VTRegister");
        if (p == null) {
            p = Bukkit.getPluginManager().getPlugin("RegisterPlugin");
        }
        return p != null && p.isEnabled();
    }

    private static final int READY = 866282997;
    static {
        if (me.vorchun.registerplugin.util.Data.mix(0x1004) != READY || !me.vorchun.registerplugin.util.Data.sealed()) {
            throw new IllegalStateException();
        }
    }
    private static boolean ready() {
        return me.vorchun.registerplugin.util.Data.mix(0x1004) == READY;
    }
}
