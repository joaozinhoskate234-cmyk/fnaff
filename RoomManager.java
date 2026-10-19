package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
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
        String lw;
        double lx, ly, lz;
        float lyaw, lpitch;
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
    // a arena e guardada como texto + numeros (nao como objeto Location), assim
    // nao quebra se o mundo ainda nao carregou quando o plugin liga
    private boolean arenaSet;
    private String arenaWorld;
    private double ax, ay, az;
    private float ayaw, apitch;

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
        if (data.contains("arena.world")) {
            this.arenaWorld = data.getString("arena.world");
            this.ax = data.getDouble("arena.x");
            this.ay = data.getDouble("arena.y");
            this.az = data.getDouble("arena.z");
            this.ayaw = (float) data.getDouble("arena.yaw");
            this.apitch = (float) data.getDouble("arena.pitch");
            this.arenaSet = arenaWorld != null;
        } else {
            Object o = data.get("arena");
            if (o instanceof Location && ((Location) o).getWorld() != null) {
                setArena((Location) o); // migra o formato antigo
            }
        }
        if (arenaSet) {
            plugin.getLogger().info("Arena carregada: mundo " + arenaWorld + " (" + (int) ax + ", " + (int) ay + ", " + (int) az + ")");
        } else {
            plugin.getLogger().info("Arena ainda nao definida. Use /bot setarena dentro do mapa.");
        }
    }

    private static String warn(String text) {
        return ChatColor.translateAlternateColorCodes('&', "&8[&cBot&8] &c" + text);
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

    public boolean isArenaSet() {
        return arenaSet;
    }

    /** Local da arena, ou null se o mundo nao esta carregado. */
    public Location getArena(boolean tryLoadWorld) {
        if (!arenaSet) {
            return null;
        }
        World w = Bukkit.getWorld(arenaWorld);
        if (w == null && tryLoadWorld) {
            File level = new File(new File(Bukkit.getWorldContainer(), arenaWorld), "level.dat");
            if (level.exists()) {
                try {
                    w = Bukkit.createWorld(new WorldCreator(arenaWorld));
                } catch (Exception ex) {
                    plugin.getLogger().warning("Nao consegui carregar o mundo " + arenaWorld + ": " + ex.getMessage());
                }
            }
        }
        if (w == null) {
            return null;
        }
        return new Location(w, ax, ay, az, ayaw, apitch);
    }

    public void setArena(Location l) {
        this.arenaWorld = l.getWorld().getName();
        this.ax = l.getX();
        this.ay = l.getY();
        this.az = l.getZ();
        this.ayaw = l.getYaw();
        this.apitch = l.getPitch();
        this.arenaSet = true;
        data.set("arena", null);
        data.set("arena.world", arenaWorld);
        data.set("arena.x", ax);
        data.set("arena.y", ay);
        data.set("arena.z", az);
        data.set("arena.yaw", (double) ayaw);
        data.set("arena.pitch", (double) apitch);
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

    private Location botLocation(Location arena) {
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
        if (!arenaSet) {
            p.sendMessage(s.msg("no-arena"));
            return;
        }
        if (isInRoom(p)) {
            p.sendMessage(s.msg("already-in"));
            return;
        }
        Location arena = getArena(true);
        if (arena == null) {
            p.sendMessage(warn("O mundo da arena (&f" + arenaWorld + "&c) nao esta carregado no servidor. "
                    + "Carregue o mundo ou use &f/bot setarena &cdentro do mapa de novo."));
            return;
        }
        plugin.stopBot(p);

        // guarda o estado antes de mexer em qualquer coisa
        UUID id = p.getUniqueId();
        PlayerInventory pi = p.getInventory();
        Location from = p.getLocation();
        Saved sv = new Saved();
        sv.inv = cloneItems(pi.getContents());
        sv.armor = cloneItems(pi.getArmorContents());
        sv.lw = from.getWorld().getName();
        sv.lx = from.getX();
        sv.ly = from.getY();
        sv.lz = from.getZ();
        sv.lyaw = from.getYaw();
        sv.lpitch = from.getPitch();
        sv.gm = p.getGameMode();

        // primeiro o teleporte: se outro plugin bloquear, nada foi alterado
        if (!p.teleport(arena)) {
            p.sendMessage(warn("Nao consegui te levar para a arena (outro plugin bloqueou o teleporte)."));
            return;
        }

        saved.put(id, sv);
        writeSaved(id, sv);
        inRoom.add(id);

        pi = p.getInventory();
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
        giveKit(p);
        p.sendMessage(s.msg("joined"));
        plugin.startBot(p, botLocation(arena), BotSession.Mode.REDUCE);
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
            if (teleportBack) {
                backTeleport(p, sv);
            }
        }
        p.setFallDistance(0f);
        p.updateInventory();
        deleteSaved(id);
    }

    /** Volta para onde o jogador estava; se aquele mundo sumiu, vai para o spawn do mundo principal. */
    private void backTeleport(Player p, Saved sv) {
        World w = sv.lw == null ? null : Bukkit.getWorld(sv.lw);
        if (w != null) {
            p.teleport(new Location(w, sv.lx, sv.ly, sv.lz, sv.lyaw, sv.lpitch));
        } else if (!Bukkit.getWorlds().isEmpty()) {
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
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
            backTeleport(p, sv);
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
        if (inRoom.isEmpty() || !arenaSet) {
            return;
        }
        Location arena = getArena(false);
        if (arena == null) {
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
            y.set("loc.world", sv.lw);
            y.set("loc.x", sv.lx);
            y.set("loc.y", sv.ly);
            y.set("loc.z", sv.lz);
            y.set("loc.yaw", (double) sv.lyaw);
            y.set("loc.pitch", (double) sv.lpitch);
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
        if (y.contains("loc.world")) {
            sv.lw = y.getString("loc.world");
            sv.lx = y.getDouble("loc.x");
            sv.ly = y.getDouble("loc.y");
            sv.lz = y.getDouble("loc.z");
            sv.lyaw = (float) y.getDouble("loc.yaw");
            sv.lpitch = (float) y.getDouble("loc.pitch");
        } else {
            Object o = y.get("loc");
            if (o instanceof Location && ((Location) o).getWorld() != null) {
                Location l = (Location) o;
                sv.lw = l.getWorld().getName();
                sv.lx = l.getX();
                sv.ly = l.getY();
                sv.lz = l.getZ();
                sv.lyaw = l.getYaw();
                sv.lpitch = l.getPitch();
            }
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
