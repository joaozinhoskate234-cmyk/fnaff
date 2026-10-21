package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Menu do livro "Bot settings": modos de treino e configuracoes do bot. */
public class BotMenu implements InventoryHolder {

    private static final int S_AIM = 10;
    private static final int S_FAST = 11;
    private static final int S_CLASSIC = 12;
    private static final int S_BRIDGE = 13;

    private static final int S_HITH = 19;
    private static final int S_HITV = 20;
    private static final int S_RADIUS = 21;
    private static final int S_SERIES = 22;
    private static final int S_PAUSE = 23;
    private static final int S_CHILL = 24;
    private static final int S_EPIC = 25;

    private static final int S_SKIN = 30;
    private static final int S_FINISH = 32;
    private static final int S_CLOSE = 40;

    private static final Random RND = new Random();

    private final MLGBotPlugin plugin;
    private final Player player;
    private final Inventory inv;

    public BotMenu(MLGBotPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv = Bukkit.createInventory(this, 45, "Bot settings");
        refresh();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open() {
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------
    // Itens
    // ------------------------------------------------------------------

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static double clamp(double v, double min, double max) {
        double r = Math.round(v * 10.0) / 10.0;
        return Math.max(min, Math.min(max, r));
    }

    private ItemStack modeItem(Material m, String title, boolean active, String... lines) {
        String[] lore = new String[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lore[i] = "&7" + lines[i];
        }
        return RoomManager.item(m, 1, "&eMode: " + title + (active ? " &a\u2714" : ""), lore);
    }

    private ItemStack numberItem(Material m, String name, String current, String... extra) {
        List<String> lore = new ArrayList<String>();
        lore.add("&7current: &c" + current);
        for (String e : extra) {
            lore.add("&7" + e);
        }
        lore.add("");
        lore.add("&8LMB: +0.1");
        lore.add("&8RMB: -0.1");
        lore.add("&8SHIFT LMB: +1");
        lore.add("&8SHIFT RMB: -1");
        return RoomManager.item(m, 1, "&e" + name, lore.toArray(new String[0]));
    }

    private ItemStack toggleItem(String modeName, boolean on, String line) {
        short data = (short) (on ? 10 : 8); // 10 = verde, 8 = cinza
        return RoomManager.item(Material.INK_SACK, 1, data, "&eChange hit mode",
                "&7" + modeName + ": " + (on ? "&aENABLED" : "&cDISABLED"),
                "&7" + line);
    }

    private ItemStack skinItem(PlayerPrefs ps) {
        ItemStack it = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(RoomManager.color("&eSelect random skin"));
        List<String> lore = new ArrayList<String>();
        lore.add(RoomManager.color("&b" + (ps.skinNick == null ? "(your own skin)" : ps.skinNick)));
        lore.add(RoomManager.color("&c/botskin <nickname>"));
        lore.add(RoomManager.color("&c/botnick <nickname>"));
        meta.setLore(lore);
        if (meta instanceof SkullMeta && ps.skinNick != null) {
            ((SkullMeta) meta).setOwner(ps.skinNick);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void refresh() {
        PlayerPrefs ps = plugin.rooms().prefs(player);
        PlayerPrefs.Training t = ps.training;

        inv.setItem(S_AIM, modeItem(Material.STICK, "Aim/Reduce training", t == PlayerPrefs.Training.AIM_REDUCE,
                "Your goal is to survive without falling into the abyss for as long as possible",
                "Set the settings to match your skill level"));
        inv.setItem(S_FAST, modeItem(Material.BLAZE_ROD, "Fastclutch training", t == PlayerPrefs.Training.FASTCLUTCH,
                "No one wants to hit you for clutches?",
                "So this mode is made especially for you",
                "Find the settings you are comfortable with"));
        inv.setItem(S_CLASSIC, modeItem(Material.STONE, "Classic clutcher", t == PlayerPrefs.Training.CLASSIC,
                "Bot will hit you with specified series of hits",
                "Make some set up for comfortable gameplay"));
        inv.setItem(S_BRIDGE, modeItem(Material.SMOOTH_STAIRS, "Bridging", t == PlayerPrefs.Training.BRIDGING,
                "Sharpen your skills and get to the finish line as quickly as possible",
                "Set up a comfortable finish position"));

        inv.setItem(S_HITH, numberItem(Material.REDSTONE, "Change hit power", fmt(ps.hitH),
                "0.4 - common hit", "0.9 - hit with sprint 'W-tap'", "1.4 - hit with 'Knockback 1'"));
        inv.setItem(S_HITV, numberItem(Material.FEATHER, "Change vertical hit power", fmt(ps.hitV),
                "0.1 - common hit"));
        inv.setItem(S_RADIUS, numberItem(Material.ARROW, "Change the attack radius of the bot", fmt(ps.radius),
                "recommended 3.5+"));
        inv.setItem(S_SERIES, RoomManager.item(Material.PAPER, 1, "&eHits in series (Classic clutcher)",
                "&7current: &c" + ps.seriesHits, "",
                "&8LMB: +1", "&8RMB: -1", "&8SHIFT LMB: +5", "&8SHIFT RMB: -5"));
        inv.setItem(S_PAUSE, numberItem(Material.WATCH, "Pause between series (seconds)", fmt(ps.seriesPause)));
        inv.setItem(S_CHILL, toggleItem("Chill out mode", ps.chillOut, "bot will be at your height"));
        inv.setItem(S_EPIC, toggleItem("Mode for epic moves", ps.epicMoves, "bot will match your timings"));

        inv.setItem(S_SKIN, skinItem(ps));
        String finish = ps.finishSet ? "x=" + (int) ps.finishX + " y=" + (int) ps.finishY + " z=" + (int) ps.finishZ : "not set";
        inv.setItem(S_FINISH, RoomManager.item(Material.BEACON, 1, "&eSet finish position (Bridging)",
                "&7finish: &c" + finish,
                "&7best time: &c" + (ps.bestTime > 0 ? fmt(ps.bestTime) + "s" : "-"), "",
                "&8Click: use the spot where you are standing"));
        inv.setItem(S_CLOSE, RoomManager.item(Material.BARRIER, 1, "&cClose"));
    }

    // ------------------------------------------------------------------
    // Cliques
    // ------------------------------------------------------------------

    private static double step(ClickType t, double small, double big) {
        if (t == ClickType.LEFT) {
            return small;
        } else if (t == ClickType.RIGHT) {
            return -small;
        } else if (t == ClickType.SHIFT_LEFT) {
            return big;
        } else if (t == ClickType.SHIFT_RIGHT) {
            return -big;
        }
        return 0;
    }

    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) {
            return;
        }
        RoomManager rooms = plugin.rooms();
        PlayerPrefs ps = rooms.prefs(player);
        ClickType t = e.getClick();
        boolean modeChanged = false;

        if (slot == S_AIM) {
            modeChanged = ps.training != PlayerPrefs.Training.AIM_REDUCE;
            ps.training = PlayerPrefs.Training.AIM_REDUCE;
        } else if (slot == S_FAST) {
            modeChanged = ps.training != PlayerPrefs.Training.FASTCLUTCH;
            ps.training = PlayerPrefs.Training.FASTCLUTCH;
        } else if (slot == S_CLASSIC) {
            modeChanged = ps.training != PlayerPrefs.Training.CLASSIC;
            ps.training = PlayerPrefs.Training.CLASSIC;
        } else if (slot == S_BRIDGE) {
            modeChanged = ps.training != PlayerPrefs.Training.BRIDGING;
            ps.training = PlayerPrefs.Training.BRIDGING;
        } else if (slot == S_HITH) {
            ps.hitH = clamp(ps.hitH + step(t, 0.1, 1.0), 0.0, 5.0);
        } else if (slot == S_HITV) {
            ps.hitV = clamp(ps.hitV + step(t, 0.1, 1.0), 0.0, 5.0);
        } else if (slot == S_RADIUS) {
            ps.radius = clamp(ps.radius + step(t, 0.1, 1.0), 1.0, 8.0);
        } else if (slot == S_SERIES) {
            int d = (int) step(t, 1, 5);
            ps.seriesHits = Math.max(1, Math.min(20, ps.seriesHits + d));
        } else if (slot == S_PAUSE) {
            ps.seriesPause = clamp(ps.seriesPause + step(t, 0.1, 1.0), 0.5, 20.0);
        } else if (slot == S_CHILL) {
            ps.chillOut = !ps.chillOut;
        } else if (slot == S_EPIC) {
            ps.epicMoves = !ps.epicMoves;
        } else if (slot == S_SKIN) {
            List<String> skins = plugin.getConfig().getStringList("random-skins");
            if (!skins.isEmpty()) {
                final String nick = skins.get(RND.nextInt(skins.size()));
                plugin.applySkin(player, nick);
            }
        } else if (slot == S_FINISH) {
            Location l = player.getLocation();
            ps.finishSet = true;
            ps.finishWorld = l.getWorld().getName();
            ps.finishX = l.getX();
            ps.finishY = l.getY();
            ps.finishZ = l.getZ();
        } else if (slot == S_CLOSE) {
            player.closeInventory();
            return;
        } else {
            return;
        }

        rooms.savePrefs();
        if (modeChanged) {
            rooms.restartBot(player);
        }
        refresh();
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.CLICK, 1f, 1f);
    }
}
