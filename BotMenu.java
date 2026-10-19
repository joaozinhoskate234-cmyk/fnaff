package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Menu do livro: configura a forca dos golpes e os modos do bot. */
public class BotMenu implements InventoryHolder {

    private static final int SLOT_H = 10;
    private static final int SLOT_V = 12;
    private static final int SLOT_CHILL = 14;
    private static final int SLOT_EPIC = 16;
    private static final int SLOT_CLOSE = 22;

    private final MLGBotPlugin plugin;
    private final Player player;
    private final Inventory inv;

    public BotMenu(MLGBotPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv = Bukkit.createInventory(this, 27, "menu");
        refresh();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open() {
        player.openInventory(inv);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static double clamp(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return Math.max(0.0, Math.min(5.0, r));
    }

    private ItemStack powerItem(Material m, String name, double value, String... examples) {
        String[] lore = new String[examples.length + 6];
        int i = 0;
        lore[i++] = "&7current hit power: &c" + fmt(value);
        for (String ex : examples) {
            lore[i++] = "&7" + ex;
        }
        lore[i++] = "";
        lore[i++] = "&8LMB: +0.1";
        lore[i++] = "&8RMB: -0.1";
        lore[i++] = "&8SHIFT LMB: +1";
        lore[i++] = "&8SHIFT RMB: -1";
        return RoomManager.item(m, 1, "&e" + name, lore);
    }

    private ItemStack toggleItem(String title, String modeName, boolean on, String line) {
        short data = (short) (on ? 10 : 8); // 10 = verde, 8 = cinza
        return RoomManager.item(Material.INK_SACK, 1, data, "&e" + title,
                "&7" + modeName + ": " + (on ? "&aENABLED" : "&cDISABLED"),
                "&7" + line);
    }

    private void refresh() {
        PlayerPrefs ps = plugin.rooms().prefs(player);
        inv.setItem(SLOT_H, powerItem(Material.STICK, "Change hit power", ps.hitH,
                "0.4 - common hit", "0.9 - hit with sprint 'W-tap'", "1.4 - hit with 'Knockback 1'"));
        inv.setItem(SLOT_V, powerItem(Material.BLAZE_ROD, "Change vertical hit power", ps.hitV,
                "0.1 - common hit"));
        inv.setItem(SLOT_CHILL, toggleItem("Change hit mode", "Chill out mode", ps.chillOut,
                "bot will be at your height"));
        inv.setItem(SLOT_EPIC, toggleItem("Change hit mode", "Mode for epic moves", ps.epicMoves,
                "bot will match your timings"));
        inv.setItem(SLOT_CLOSE, RoomManager.item(Material.BARRIER, 1, "&cClose"));
    }

    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) {
            return;
        }
        PlayerPrefs ps = plugin.rooms().prefs(player);

        double step = 0;
        ClickType t = e.getClick();
        if (t == ClickType.LEFT) {
            step = 0.1;
        } else if (t == ClickType.RIGHT) {
            step = -0.1;
        } else if (t == ClickType.SHIFT_LEFT) {
            step = 1.0;
        } else if (t == ClickType.SHIFT_RIGHT) {
            step = -1.0;
        }

        if (slot == SLOT_H && step != 0) {
            ps.hitH = clamp(ps.hitH + step);
        } else if (slot == SLOT_V && step != 0) {
            ps.hitV = clamp(ps.hitV + step);
        } else if (slot == SLOT_CHILL) {
            ps.chillOut = !ps.chillOut;
        } else if (slot == SLOT_EPIC) {
            ps.epicMoves = !ps.epicMoves;
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        } else {
            return;
        }
        plugin.rooms().savePrefs();
        refresh();
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.CLICK, 1f, 1f);
    }
}
