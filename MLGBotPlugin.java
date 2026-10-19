package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MLGBotPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, BotSession> sessions = new HashMap<UUID, BotSession>();
    private Settings settings;
    private RoomManager rooms;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        rooms = new RoomManager(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            public void run() {
                tickAll();
            }
        }, 1L, 1L);
        getLogger().info("MLGBot ativado. Use /queue " + settings.queueKeyword + " ou /bot spawn");
    }

    @Override
    public void onDisable() {
        rooms.leaveAll();
        for (BotSession s : new ArrayList<BotSession>(sessions.values())) {
            s.remove(true);
        }
        sessions.clear();
    }

    public Settings settings() {
        return settings;
    }

    public RoomManager rooms() {
        return rooms;
    }

    // ------------------------------------------------------------------
    // Bots
    // ------------------------------------------------------------------

    public BotSession startBot(Player p, Location loc, BotSession.Mode mode) {
        stopBot(p);
        BotSession s = new BotSession(this, p, loc, mode, rooms.prefs(p));
        sessions.put(p.getUniqueId(), s);
        return s;
    }

    public void stopBot(Player p) {
        BotSession s = sessions.remove(p.getUniqueId());
        if (s != null) {
            s.remove(true);
        }
    }

    private void tickAll() {
        rooms.tick();
        if (sessions.isEmpty()) {
            return;
        }
        for (BotSession s : new ArrayList<BotSession>(sessions.values())) {
            boolean ok;
            try {
                ok = s.tick();
            } catch (Throwable t) {
                getLogger().warning("Erro no bot, removendo: " + t);
                t.printStackTrace();
                ok = false;
            }
            if (!ok) {
                s.remove(true);
                sessions.remove(s.getOwnerId());
            }
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "spawn";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("mlgbot.admin")) {
                sender.sendMessage(settings.msg("no-permission"));
                return true;
            }
            reloadConfig();
            settings = Settings.load(getConfig());
            sender.sendMessage(settings.msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(settings.msg("only-players"));
            return true;
        }
        Player p = (Player) sender;

        if (sub.equals("setarena")) {
            if (!p.hasPermission("mlgbot.admin")) {
                p.sendMessage(settings.msg("no-permission"));
                return true;
            }
            rooms.setArena(p.getLocation());
            p.sendMessage(settings.msg("arena-set"));
            return true;
        }

        if (!p.hasPermission("mlgbot.use")) {
            p.sendMessage(settings.msg("no-permission"));
            return true;
        }

        if (sub.equals("join")) {
            rooms.join(p);
            return true;
        }

        if (sub.equals("leave")) {
            if (rooms.isInRoom(p)) {
                rooms.leave(p, true);
                p.sendMessage(settings.msg("left"));
            } else {
                p.sendMessage(settings.msg("not-in-room"));
            }
            return true;
        }

        if (sub.equals("menu")) {
            new BotMenu(this, p).open();
            return true;
        }

        if (sub.equals("spawn") || sub.equals("fight") || sub.equals("copy") || sub.equals("reduce")) {
            BotSession.Mode mode = BotSession.Mode.parse(settings.defaultMode);
            if (mode == null) {
                mode = BotSession.Mode.REDUCE;
            }
            if (!sub.equals("spawn")) {
                mode = BotSession.Mode.parse(sub);
            } else if (args.length > 1) {
                BotSession.Mode chosen = BotSession.Mode.parse(args[1]);
                if (chosen == null) {
                    p.sendMessage(settings.msg("bad-mode"));
                    return true;
                }
                mode = chosen;
            }
            Location l = p.getLocation();
            Vector dir = l.getDirection();
            dir.setY(0);
            if (dir.lengthSquared() < 1.0E-6) {
                dir = new Vector(0, 0, 1);
            }
            dir.normalize().multiply(3.0);
            Location target = l.clone().add(dir);
            target.setY(l.getY());
            startBot(p, target, mode);
            p.sendMessage(settings.msg("spawned").replace("%mode%", mode.name().toLowerCase()));
            return true;
        }

        if (sub.equals("mode")) {
            BotSession s = sessions.get(p.getUniqueId());
            if (s == null) {
                p.sendMessage(settings.msg("no-bot"));
                return true;
            }
            BotSession.Mode m = args.length > 1 ? BotSession.Mode.parse(args[1]) : null;
            if (m == null) {
                p.sendMessage(settings.msg("bad-mode"));
                return true;
            }
            s.setMode(m);
            p.sendMessage(settings.msg("mode-changed").replace("%mode%", m.name().toLowerCase()));
            return true;
        }

        if (sub.equals("remove")) {
            if (sessions.containsKey(p.getUniqueId())) {
                stopBot(p);
                p.sendMessage(settings.msg("removed"));
            } else {
                p.sendMessage(settings.msg("no-bot"));
            }
            return true;
        }

        p.sendMessage(settings.msg("usage"));
        return true;
    }

    /** /queue cmg entra na sala do bot (funciona mesmo que outro plugin tenha o /queue). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        String[] parts = e.getMessage().trim().split("\\s+");
        if (parts.length < 2) {
            return;
        }
        String cmd = parts[0].toLowerCase();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        if (!cmd.equals(settings.queueCommand.toLowerCase())) {
            return;
        }
        String arg = parts[1].toLowerCase();
        Player p = e.getPlayer();
        if (arg.equals(settings.queueKeyword.toLowerCase())) {
            e.setCancelled(true);
            if (!p.hasPermission("mlgbot.use")) {
                p.sendMessage(settings.msg("no-permission"));
                return;
            }
            rooms.join(p);
        } else if (arg.equals("leave") && rooms.isInRoom(p)) {
            e.setCancelled(true);
            rooms.leave(p, true);
            p.sendMessage(settings.msg("left"));
        }
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        BotSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            s.onOwnerSwing();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        BotSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.onOwnerPlace(e.getBlockPlaced());
        }
        if (rooms.isInRoom(p)) {
            rooms.trackBlock(p, e.getBlockPlaced());
            rooms.scheduleRefill(p);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!rooms.isInRoom(p)) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null) {
            return;
        }
        if (it.getType() == Material.BOOK) {
            e.setCancelled(true);
            new BotMenu(this, p).open();
        } else if (it.getType() == Material.BARRIER) {
            e.setCancelled(true);
            rooms.leave(p, true);
            p.sendMessage(settings.msg("left"));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof BotMenu) {
            ((BotMenu) e.getInventory().getHolder()).click(e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof BotMenu) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && rooms.isInRoom((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && rooms.isInRoom((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (rooms.isInRoom(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        rooms.leave(p, true);
        stopBot(p);
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            public void run() {
                if (e.getPlayer().isOnline()) {
                    rooms.recover(e.getPlayer());
                }
            }
        }, 10L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (!rooms.isInRoom(e.getPlayer())) {
            stopBot(e.getPlayer());
        }
    }
}
