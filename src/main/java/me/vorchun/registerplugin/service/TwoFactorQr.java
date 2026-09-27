// VTRegister - Copyright (C) 2026 Vorchun.
// Licensed under GPL-3.0 with additional terms OR VMIT - see LICENSE file.
package me.vorchun.registerplugin.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import me.vorchun.registerplugin.util.Scheduler;

/**
 * QR-код для /2fa on прямо в игре — на карте в руке. Ссылку otpauth://
 * Minecraft не открывает (только http/https), а QR игрок сканирует камерой
 * в Google Authenticator / Яндекс Ключе. Секрет никуда в интернет не уходит.
 * Карта своя у каждого игрока (контекстный рендер), выбросить её нельзя,
 * снимается после подтверждения, выхода, смерти или через 5 минут.
 */
public final class TwoFactorQr implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private volatile MapView view;
    private final Map<UUID, boolean[][]> codes = new ConcurrentHashMap<>();
    private final Set<UUID> drawn = ConcurrentHashMap.newKeySet();

    public TwoFactorQr(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "twofa_qr");
    }

    /** Выдать карту с QR. @return false — нет места в хотбаре или сбой (покажем текст). */
    public boolean give(Player p, String otpauthUrl) {
        boolean[][] img = encode(otpauthUrl);
        if (img == null || view() == null) {
            return false;
        }
        take(p);
        PlayerInventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        codes.put(p.getUniqueId(), img);
        drawn.remove(p.getUniqueId());
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.setMapView(view());
        meta.setDisplayName(org.bukkit.ChatColor.GOLD + "QR-код 2FA — отсканируй телефоном");
        me.vorchun.registerplugin.util.ItemTag.mark(meta, key);
        map.setItemMeta(meta);
        inv.setItem(slot, map);
        inv.setHeldItemSlot(slot);
        final UUID u = p.getUniqueId();
        Scheduler.runAtEntityLater(plugin, p, () -> {
            Player pl = Bukkit.getPlayer(u);
            if (pl != null && codes.containsKey(u)) {
                take(pl);
            }
        }, 20L * 300);
        return true;
    }

    /** Убрать карту и данные QR у игрока. */
    public void take(Player p) {
        codes.remove(p.getUniqueId());
        drawn.remove(p.getUniqueId());
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (isQr(c[i])) {
                inv.setItem(i, null);
            }
        }
        try {
            if (isQr(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isQr(ItemStack it) {
        if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) {
            return false;
        }
        try {
            return me.vorchun.registerplugin.util.ItemTag.has(it.getItemMeta(), key);
        } catch (Throwable t) {
            return false;
        }
    }

    private MapView view() {
        MapView v = view;
        if (v != null) {
            return v;
        }
        try {
            World w = Bukkit.getWorlds().get(0);
            v = Bukkit.createMap(w);
            for (MapRenderer r : new java.util.ArrayList<>(v.getRenderers())) {
                v.removeRenderer(r);
            }
            v.addRenderer(new MapRenderer(true) {
                @Override
                @SuppressWarnings("deprecation")
                public void render(MapView mv, MapCanvas canvas, Player player) {
                    UUID u = player.getUniqueId();
                    boolean[][] img = codes.get(u);
                    if (img == null || drawn.contains(u)) {
                        return;
                    }
                    // Индексы палитры карт (одинаковы во всех версиях): чёрный и белый
                    byte black = 119;
                    byte white = 34;
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            canvas.setPixel(x, y, img[x][y] ? black : white);
                        }
                    }
                    drawn.add(u);
                }
            });
            view = v;
            return v;
        } catch (Throwable t) {
            plugin.getLogger().warning("2FA: карта для QR не создана: " + t);
            return null;
        }
    }

    /** QR 128×128 (true — чёрный модуль). */
    static boolean[][] encode(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 128, 128, hints);
            boolean[][] out = new boolean[128][128];
            for (int x = 0; x < 128 && x < m.getWidth(); x++) {
                for (int y = 0; y < 128 && y < m.getHeight(); y++) {
                    out[x][y] = m.get(x, y);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        // Выброшенную карту мог бы отсканировать кто угодно — секрет 2FA
        if (isQr(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isQr);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        take(e.getPlayer());
    }
}
