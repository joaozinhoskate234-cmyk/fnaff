package me.mlgbot;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Valores do config.yml lidos uma vez (e de novo no /bot reload). */
public class Settings {

    public String defaultMode;
    public String botName;
    public boolean copySkin;
    public double reach;
    public int hitCooldown;
    public int hitBackDelay;
    public double botDamage;
    public double kbHorizontal;
    public double kbVertical;
    public double kbPerLevel;
    public boolean copyMovement;
    public boolean copyJump;
    public boolean copySneak;
    public boolean copySwing;
    public boolean copyBlockPlace;
    public boolean copyHeldItem;
    public double fallDepth;
    public boolean cleanBlocks;
    public int maxBlocks;

    // modo luta
    public double fightSpeed;
    public int fightAttackInterval;
    public int fightAttackJitter;
    public int fightKnockbackLevel;
    public boolean fightBridge;
    public int fightBridgeDelay;
    public Material fightBlock;

    // sala do bot (reduce)
    public String queueCommand;
    public String queueKeyword;
    public double reduceSpeed;
    public int reduceInterval;
    public int reduceJitter;
    public double reduceDistance;
    public double defaultHitH;
    public double defaultHitV;
    public double arenaFallDepth;
    public Material kitBlock;
    public int kitStickKnockback;

    private FileConfiguration cfg;

    public static Settings load(FileConfiguration c) {
        Settings s = new Settings();
        s.cfg = c;
        s.defaultMode = c.getString("mode", "reduce");
        s.botName = c.getString("bot-name", "&cBot");
        s.copySkin = c.getBoolean("copy-skin", true);
        s.reach = c.getDouble("reach", 3.1);
        s.hitCooldown = c.getInt("hit-cooldown-ticks", 10);
        s.hitBackDelay = c.getInt("hit-back-delay-ticks", 2);
        s.botDamage = c.getDouble("bot-damage", 0.0);
        s.kbHorizontal = c.getDouble("knockback.horizontal", 0.4);
        s.kbVertical = c.getDouble("knockback.vertical", 0.4);
        s.kbPerLevel = c.getDouble("knockback.per-level", 0.5);
        s.copyMovement = c.getBoolean("copy.movement", true);
        s.copyJump = c.getBoolean("copy.jump", true);
        s.copySneak = c.getBoolean("copy.sneak", true);
        s.copySwing = c.getBoolean("copy.swing", true);
        s.copyBlockPlace = c.getBoolean("copy.block-place", true);
        s.copyHeldItem = c.getBoolean("copy.held-item", true);
        s.fallDepth = c.getDouble("fall-respawn-depth", 25.0);
        s.cleanBlocks = c.getBoolean("cleanup-blocks-on-remove", true);
        s.maxBlocks = c.getInt("max-bot-blocks", 300);

        s.fightSpeed = c.getDouble("fight.speed", 0.26);
        s.fightAttackInterval = c.getInt("fight.attack-interval-ticks", 10);
        s.fightAttackJitter = c.getInt("fight.attack-jitter-ticks", 6);
        s.fightKnockbackLevel = c.getInt("fight.knockback-level", 1);
        s.fightBridge = c.getBoolean("fight.bridge", true);
        s.fightBridgeDelay = c.getInt("fight.bridge-delay-ticks", 4);
        Material m = Material.matchMaterial(c.getString("fight.block", "SANDSTONE"));
        s.fightBlock = (m != null && m.isBlock()) ? m : Material.SANDSTONE;
        s.queueCommand = c.getString("queue.command", "queue");
        s.queueKeyword = c.getString("queue.keyword", "cmg");
        s.reduceSpeed = c.getDouble("reduce.speed", 0.34);
        s.reduceInterval = c.getInt("reduce.hit-interval-ticks", 10);
        s.reduceJitter = c.getInt("reduce.hit-jitter-ticks", 3);
        s.reduceDistance = c.getDouble("reduce.distance", 2.2);
        s.defaultHitH = c.getDouble("reduce.default-hit-power-horizontal", 0.4);
        s.defaultHitV = c.getDouble("reduce.default-hit-power-vertical", 0.1);
        s.arenaFallDepth = c.getDouble("reduce.arena-fall-respawn-depth", 30.0);
        Material kb = Material.matchMaterial(c.getString("kit.block", "SANDSTONE"));
        s.kitBlock = (kb != null && kb.isBlock()) ? kb : Material.SANDSTONE;
        s.kitStickKnockback = c.getInt("kit.stick-knockback", 1);
        return s;
    }

    /** Mensagem do config com prefixo e cores (se faltar no config antigo, usa a do plugin). */
    public String msg(String key) {
        String prefix = cfg.getString("messages.prefix");
        String text = cfg.getString("messages." + key);
        if (prefix == null) {
            prefix = "&8[&cBot&8] &7";
        }
        if (text == null) {
            text = "";
        }
        return ChatColor.translateAlternateColorCodes('&', prefix + text);
    }
}
