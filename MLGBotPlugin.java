package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
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

    private static String info(String text) {
        return ChatColor.translateAlternateColorCodes('&', "&8[&cBot&8] &7" + text);
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

    /** O jogador caiu da arena: fecha a contagem de golpes aguentados. */
    public void onPlayerFell(Player p) {
        BotSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.onOwnerFell();
        }
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
        String cname = cmd.getName().toLowerCase();
        if (cname.equals("botskin") || cname.equals("botnick")) {
            return nickCommand(sender, cname, args);
        }

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

        if (sub.equals("setarena") && args.length >= 5) {
            // /bot setarena <mundo> <x> <y> <z> [yaw]  (nao precisa estar no mapa)
            if (!sender.hasPermission("mlgbot.admin")) {
                sender.sendMessage(settings.msg("no-permission"));
                return true;
            }
            try {
                double x = Double.parseDouble(args[2]);
                double y = Double.parseDouble(args[3]);
                double z = Double.parseDouble(args[4]);
                float yaw = args.length >= 6 ? Float.parseFloat(args[5]) : 0f;
                rooms.setArenaRaw(args[1], x, y, z, yaw, 0f);
                sender.sendMessage(info("Arena definida: mundo &f" + args[1] + "&7, x=" + x + " y=" + y + " z=" + z));
            } catch (NumberFormatException ex) {
                sender.sendMessage(info("&cUse: /bot setarena <mundo> <x> <y> <z> [yaw]"));
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(settings.msg("only-players"));
            return true;
        }
        Player p = (Player) sender;

        if (sub.equals("where")) {
            Location l = p.getLocation();
            p.sendMessage(info("Mundo: &f" + l.getWorld().getName()
                    + "&7  x=&f" + Math.round(l.getX() * 10.0) / 10.0
                    + "&7 y=&f" + Math.round(l.getY() * 10.0) / 10.0
                    + "&7 z=&f" + Math.round(l.getZ() * 10.0) / 10.0));
            return true;
        }

        if (sub.equals("world")) {
            if (!p.hasPermission("mlgbot.admin")) {
                p.sendMessage(settings.msg("no-permission"));
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(info("&cUse: /bot world <nome da pasta do mundo>"));
                return true;
            }
            World w = rooms.loadWorld(args[1]);
            if (w == null) {
                p.sendMessage(info("&cNao achei o mundo &f" + args[1]
                        + "&c. A pasta dele precisa estar na pasta principal do servidor (ao lado do server.properties)."));
                return true;
            }
            p.teleport(w.getSpawnLocation().clone().add(0.5, 1.0, 0.5));
            p.setAllowFlight(true);
            p.setFlying(true);
            p.sendMessage(info("Voce esta no mundo &f" + w.getName()
                    + "&7 e pode voar. Va ate o ponto da arena e use &f/bot setarena&7."));
            return true;
        }

        if (sub.equals("setarena")) {
            if (!p.hasPermission("mlgbot.admin")) {
                p.sendMessage(settings.msg("no-permission"));
                return true;
            }
            rooms.setArena(p.getLocation());
            if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
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

    private boolean nickCommand(CommandSender sender, String cname, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(settings.msg("only-players"));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("mlgbot.use")) {
            p.sendMessage(settings.msg("no-permission"));
            return true;
        }
        if (args.length < 1) {
            p.sendMessage(info("&cUse: /" + cname + " <nickname>"));
            return true;
        }
        if (cname.equals("botskin")) {
            applySkin(p, args[0]);
        } else {
            String nick = args[0];
            if (nick.length() > 16) {
                nick = nick.substring(0, 16);
            }
            PlayerPrefs ps = rooms.prefs(p);
            ps.botNick = nick;
            rooms.savePrefs();
            p.sendMessage(info("Nick do bot: &f" + nick));
            restartIfActive(p);
        }
        return true;
    }

    /** Busca a skin pelo nick (Mojang) e recria o bot com ela. */
    public void applySkin(final Player p, final String nick) {
        p.sendMessage(info("Buscando a skin de &f" + nick + "&7..."));
        SkinFetcher.fetch(this, p, nick, new SkinFetcher.Callback() {
            public void done(String realNick, String value, String signature) {
                if (!p.isOnline()) {
                    return;
                }
                if (value == null) {
                    p.sendMessage(info("&cNao achei a skin de &f" + nick + "&c. Confira o nick."));
                    return;
                }
                PlayerPrefs ps = rooms.prefs(p);
                ps.skinNick = realNick;
                ps.skinValue = value;
                ps.skinSignature = signature;
                rooms.savePrefs();
                p.sendMessage(info("Skin do bot: &f" + realNick));
                restartIfActive(p);
            }
        });
    }

    private void restartIfActive(Player p) {
        if (rooms.isInRoom(p)) {
            rooms.restartBot(p);
            return;
        }
        BotSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            startBot(p, p.getLocation(), s.getMode());
        }
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

    /** Na sala do bot o jogador pode colocar blocos, mesmo que outro plugin do servidor bloqueie. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        boolean inRoom = rooms.isInRoom(p);
        if (inRoom) {
            e.setCancelled(false);
            e.setBuild(true);
        }
        if (e.isCancelled()) {
            return;
        }
        BotSession s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.onOwnerPlace(e.getBlockPlaced());
        }
        if (inRoom) {
            rooms.trackBlock(p, e.getBlockPlaced());
            rooms.onRoomPlace(p);
            rooms.scheduleRefill(p);
        }
    }

    /** Na sala so se quebra o que o proprio jogador colocou (o mapa fica protegido). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!rooms.isInRoom(p)) {
            return;
        }
        e.setCancelled(true);
        if (rooms.untrackIfTracked(p, e.getBlock())) {
            e.getBlock().setType(Material.AIR);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!rooms.isInRoom(p)) {
            return;
        }
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        ItemStack it = e.getItem();
        if (right && it != null && it.getType() == Material.BOOK) {
            e.setCancelled(true);
            new BotMenu(this, p).open();
            return;
        }
        if (right && it != null && it.getType() == Material.BARRIER) {
            e.setCancelled(true);
            rooms.leave(p, true);
            p.sendMessage(settings.msg("left"));
            return;
        }
        // outro plugin bloqueou o clique em bloco: libera dentro da sala
        if (e.isCancelled() && (a == Action.RIGHT_CLICK_BLOCK || a == Action.LEFT_CLICK_BLOCK)) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (rooms.isInRoom(e.getPlayer()) && e.getNewGameMode() != GameMode.SURVIVAL) {
            e.setCancelled(true);
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
