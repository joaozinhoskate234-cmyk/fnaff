package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A "sala do bot": o jogador entra com /queue cmg, vai para a arena, recebe o kit
 * (stick, blocos, picareta, livro, barreira) e o bot aparece na frente dele.
 * O inventario antigo e guardado (tambem em arquivo) e devolvido ao sair.
 */
public class RoomManager {

    private static class Saved {
        ItemStack[] inv;
        ItemStack[] armor;
        Location loc;
        GameMode gm;
    }

    private static class Placed {
        final Block block;
        final int typeId;

        Placed(Block block, int typeId) {
            this.block = block;
            this.typeId = typeId;
        }
    }

    private final MLGBotPlugin plugin;
    private final File dataFile;
    private final File savedDir;
    private final YamlConfiguration data;
    private Location arena;

    private final Set<UUID> inRoom = new HashSet<UUID>();
    private final Map<UUID, Saved> saved = new HashMap<UUID, Saved>();
    private final Map<UUID, PlayerPrefs> prefs = new HashMap<UUID, PlayerPrefs>();
    private final Map<UUID, List<Placed>> placed = new HashMap<UUID, List<Placed>>();

    public RoomManager(MLGBotPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.savedDir = new File(plugin.getDataFolder(), "saved");
        this.savedDir.mkdirs();
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        Object o = data.get("arena");
        if (o instanceof Location) {
            this.arena = (Location) o;
        }
    }

    // ------------------------------------------------------------------
    // Itens
    // ------------------------------------------------------------------

    public static ItemStack item(Material m, int amount, String name, String... lore) {
        return item(m, amount, (short) 0, name, lore);
    }

    public static ItemStack item(Material m, int amount, short data, String name, String... lore) {
        ItemStack it = new ItemStack(m, amount, data);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (lore.length > 0) {
            List<String> l = new ArrayList<String>();
            for (String line : lore) {
                l.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private void giveKit(Player p) {
        Settings s = plugin.settings();
        PlayerInventory inv = p.getInventory();

        ItemStack stick = item(Material.STICK, 1, "&eStick");
        if (s.kitStickKnockback > 0) {
            stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, s.kitStickKnockback);
        }
        ItemStack pick = item(Material.IRON_PICKAXE, 1, "&ePickaxe");
        pick.addUnsafeEnchantment(Enchantment.DIG_SPEED, 2);

        inv.setItem(0, stick);
        inv.setItem(1, new ItemStack(s.kitBlock, 64));
        inv.setItem(2, pick);
        inv.setItem(7, item(Material.BOOK, 1, "&eBot settings &7(right click)"));
        inv.setItem(8, item(Material.BARRIER, 1, "&cLeave &7(right click)"));
        inv.setHeldItemSlot(0);
        p.updateInventory();
    }

    /** Os blocos do kit nunca acabam. */
    public void scheduleRefill(final Player p) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                if (!p.isOnline() || !isInRoom(p)) {
                    return;
                }
                Material block = plugin.settings().kitBlock;
                PlayerInventory inv = p.getInventory();
                for (int i = 0; i < 9; i++) {
                    ItemStack it = inv.getItem(i);
                    if (it != null && it.getType() == block && it.getAmount() < 64) {
                        it.setAmount(64);
                    }
                }
                p.updateInventory();
            }
        });
    }

    // ------------------------------------------------------------------
    // Arena e preferencias
    // ------------------------------------------------------------------

    public Location getArena() {
        return arena;
    }

    public void setArena(Location l) {
        this.arena = l.clone();
        data.set("arena", arena);
        saveData();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao consegui salvar data.yml: " + ex.getMessage());
        }
    }

    public PlayerPrefs prefs(Player p) {
        UUID id = p.getUniqueId();
        PlayerPrefs ps = prefs.get(id);
        if (ps == null) {
            Settings s = plugin.settings();
            String base = "players." + id + ".";
            ps = new PlayerPrefs(data.getDouble(base + "hit-h", s.defaultHitH),
                    data.getDouble(base + "hit-v", s.defaultHitV));
            ps.chillOut = data.getBoolean(base + "chill-out", false);
            ps.epicMoves = data.getBoolean(base + "epic-moves", false);
            prefs.put(id, ps);
        }
        return ps;
    }

    public void savePrefs() {
        for (Map.Entry<UUID, PlayerPrefs> en : prefs.entrySet()) {
            String base = "players." + en.getKey() + ".";
            PlayerPrefs ps = en.getValue();
            data.set(base + "hit-h", ps.hitH);
            data.set(base + "hit-v", ps.hitV);
            data.set(base + "chill-out", ps.chillOut);
            data.set(base + "epic-moves", ps.epicMoves);
        }
        saveData();
    }

    // ------------------------------------------------------------------
    // Entrar / sair
    // ------------------------------------------------------------------

    public boolean isInRoom(Player p) {
        return inRoom.contains(p.getUniqueId());
    }

    private Location botLocation() {
        Vector dir = arena.getDirection();
        dir.setY(0);
        if (dir.lengthSquared() < 1.0E-6) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize().multiply(3.0);
        Location t = arena.clone().add(dir);
        t.setY(arena.getY());
        return t;
    }

    public void join(Player p) {
        Settings s = plugin.settings();
        if (arena == null || arena.getWorld() == null) {
            p.sendMessage(s.msg("no-arena"));
            return;
        }
        if (isInRoom(p)) {
            p.sendMessage(s.msg("already-in"));
            return;
        }
        plugin.stopBot(p);

        UUID id = p.getUniqueId();
        PlayerInventory pi = p.getInventory();
        Saved sv = new Saved();
        sv.inv = cloneItems(pi.getContents());
        sv.armor = cloneItems(pi.getArmorContents());
        sv.loc = p.getLocation().clone();
        sv.gm = p.getGameMode();
        saved.put(id, sv);
        writeSaved(id, sv);

        inRoom.add(id);
        pi.clear();
        pi.setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect pe : new ArrayList<PotionEffect>(p.getActivePotionEffects())) {
            p.removePotionEffect(pe.getType());
        }
        p.teleport(arena);
        giveKit(p);
        p.sendMessage(s.msg("joined"));
        plugin.startBot(p, botLocation(), BotSession.Mode.REDUCE);
    }

    public void leave(Player p, boolean teleportBack) {
        UUID id = p.getUniqueId();
        if (!inRoom.contains(id)) {
            return;
        }
        plugin.stopBot(p);
        inRoom.remove(id);

        List<Placed> list = placed.remove(id);
        if (list != null) {
            for (Placed pl : list) {
                if (pl.block.getTypeId() == pl.typeId) {
                    pl.block.setType(Material.AIR);
                }
            }
        }

        Saved sv = saved.remove(id);
        if (sv == null) {
            sv = readSaved(id);
        }
        PlayerInventory pi = p.getInventory();
        pi.clear();
        pi.setArmorContents(new ItemStack[4]);
        if (sv != null) {
            pi.setContents(sv.inv);
            pi.setArmorContents(sv.armor);
            if (sv.gm != null) {
                p.setGameMode(sv.gm);
            }
            if (teleportBack && sv.loc != null && sv.loc.getWorld() != null) {
                p.teleport(sv.loc);
            }
        }
        p.setFallDistance(0f);
        p.updateInventory();
        deleteSaved(id);
    }

    public void leaveAll() {
        for (UUID id : new ArrayList<UUID>(inRoom)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                leave(p, true);
            }
        }
    }

    /** Se o servidor caiu com alguem na sala, devolve o inventario quando ele voltar. */
    public void recover(Player p) {
        UUID id = p.getUniqueId();
        if (inRoom.contains(id)) {
            return;
        }
        File f = new File(savedDir, id + ".yml");
        if (!f.exists()) {
            return;
        }
        Saved sv = readSaved(id);
        if (sv != null) {
            PlayerInventory pi = p.getInventory();
            pi.clear();
            pi.setArmorContents(new ItemStack[4]);
            pi.setContents(sv.inv);
            pi.setArmorContents(sv.armor);
            if (sv.gm != null) {
                p.setGameMode(sv.gm);
            }
            if (sv.loc != null && sv.loc.getWorld() != null) {
                p.teleport(sv.loc);
            }
            p.updateInventory();
            p.sendMessage(plugin.settings().msg("restored"));
        }
        deleteSaved(id);
    }

    public void trackBlock(Player p, Block b) {
        List<Placed> list = placed.get(p.getUniqueId());
        if (list == null) {
            list = new ArrayList<Placed>();
            placed.put(p.getUniqueId(), list);
        }
        if (list.size() < 2000) {
            list.add(new Placed(b, b.getTypeId()));
        }
    }

    /** Quem cai da arena volta para o ponto inicial. */
    public void tick() {
        if (inRoom.isEmpty() || arena == null) {
            return;
        }
        double depth = plugin.settings().arenaFallDepth;
        for (UUID id : new ArrayList<UUID>(inRoom)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (p.getWorld().equals(arena.getWorld()) && p.getLocation().getY() < arena.getY() - depth) {
                p.setFallDistance(0f);
                p.setVelocity(new Vector(0, 0, 0));
                p.teleport(arena);
            }
        }
    }

    // ------------------------------------------------------------------
    // Inventario salvo (memoria + arquivo, para nao perder item se o servidor cair)
    // ------------------------------------------------------------------

    private static ItemStack[] cloneItems(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }

    private void writeSaved(UUID id, Saved sv) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (int i = 0; i < sv.inv.length; i++) {
                y.set("inv." + i, sv.inv[i]);
            }
            for (int i = 0; i < sv.armor.length; i++) {
                y.set("armor." + i, sv.armor[i]);
            }
            y.set("loc", sv.loc);
            y.set("gm", sv.gm == null ? "SURVIVAL" : sv.gm.name());
            y.save(new File(savedDir, id + ".yml"));
        } catch (Exception ex) {
            plugin.getLogger().warning("Nao consegui salvar o inventario de " + id + ": " + ex.getMessage());
        }
    }

    private Saved readSaved(UUID id) {
        File f = new File(savedDir, id + ".yml");
        if (!f.exists()) {
            return null;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Saved sv = new Saved();
        sv.inv = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            sv.inv[i] = y.getItemStack("inv." + i);
        }
        sv.armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            sv.armor[i] = y.getItemStack("armor." + i);
        }
        Object o = y.get("loc");
        if (o instanceof Location) {
            sv.loc = (Location) o;
        }
        try {
            sv.gm = GameMode.valueOf(y.getString("gm", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            sv.gm = GameMode.SURVIVAL;
        }
        return sv;
    }

    private void deleteSaved(UUID id) {
        File f = new File(savedDir, id + ".yml");
        if (f.exists()) {
            f.delete();
        }
    }
}
