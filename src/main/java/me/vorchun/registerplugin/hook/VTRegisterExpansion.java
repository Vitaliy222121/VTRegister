package me.vorchun.registerplugin.hook;

import org.bukkit.entity.Player;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.vorchun.registerplugin.RegisterPlugin;
import me.vorchun.registerplugin.service.AccountRecord;

/**
 * PlaceholderAPI: %vtregister_<плейсхолдер>%
 *
 * Доступные плейсхолдеры:
 *   authenticated / auth  — yes/no: авторизован ли игрок
 *   registered            — yes/no: есть ли аккаунт
 *   storage               — YAML / SQLite / MySQL / MariaDB / PostgreSQL
 *   totp                  — yes/no: включена ли 2FA
 *   email                 — yes/no: подтверждена ли почта
 *   last_ip               — последний IP игрока (из базы)
 *   accounts              — всего аккаунтов (для скорбордов/табло)
 *   waiting               — сколько игроков ждут авторизации
 *   mode                  — secure / insecure
 *   antibot_queue         — позиция игрока в очереди проверки (0 = не в очереди)
 *
 * Пример: %vtregister_authenticated%
 */
public final class VTRegisterExpansion extends PlaceholderExpansion {

    private final RegisterPlugin plugin;

    public VTRegisterExpansion(RegisterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "vtregister";
    }

    @Override
    public String getAuthor() {
        return "Vorchun";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // не выгружать при /papi reload
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null) {
            return "";
        }
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "authenticated":
            case "auth":
                return player != null && plugin.getSessionManager().isLoggedIn(player.getUniqueId()) ? "yes" : "no";
            case "registered":
                return player != null && plugin.getAccountStore().isRegistered(player.getUniqueId()) ? "yes" : "no";
            case "storage":
                return plugin.getAccountStore().backendName();
            case "mode":
                return plugin.getAuthListener() != null && plugin.getAuthListener().isSecureMode() ? "secure" : "insecure";
            case "totp": {
                if (player == null) {
                    return "no";
                }
                AccountRecord r = plugin.getAccountStore().getCached(player.getUniqueId());
                return r != null && r.hasTotp() ? "yes" : "no";
            }
            case "email": {
                if (player == null) {
                    return "no";
                }
                AccountRecord r = plugin.getAccountStore().getCached(player.getUniqueId());
                return r != null && r.isEmailVerified() ? "yes" : "no";
            }
            case "last_ip": {
                if (player == null) {
                    return "";
                }
                AccountRecord r = plugin.getAccountStore().getCached(player.getUniqueId());
                return r == null || r.getLastIp() == null ? "" : r.getLastIp();
            }
            case "waiting": {
                int waiting = 0;
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (!plugin.getSessionManager().isLoggedIn(p.getUniqueId())) {
                        waiting++;
                    }
                }
                return String.valueOf(waiting);
            }
            case "antibot_queue": {
                if (player == null || plugin.getAntiBotService() == null) {
                    return "0";
                }
                int pos = plugin.getAntiBotService().queuePosition(player.getUniqueId());
                return String.valueOf(Math.max(0, pos));
            }
            case "accounts":
                return String.valueOf(plugin.getAccountStore().snapshotCached().size());
            default:
                return null; // неизвестный плейсхолдер — пусть PAPI вернёт null
        }
    }

    private static final int P7 = -1438619485;
    static {
        if (me.vorchun.registerplugin.service.Sec.t(0x1009) != P7 || !me.vorchun.registerplugin.service.Sec.s()) {
            throw new IllegalStateException();
        }
    }
    private static boolean p7() {
        return me.vorchun.registerplugin.service.Sec.t(0x1009) == P7;
    }
}
