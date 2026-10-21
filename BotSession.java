package me.mlgbot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayOutChat;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityStatus;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Um bot de treino (jogador falso, so o dono enxerga).
 * A fisica (gravidade, colisao, knockback) e feita aqui mesmo, e o bot e
 * desenhado no cliente por pacotes. Ele copia o que o dono faz.
 */
public class BotSession {

    private static final double EPS = 1.0E-4;
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** COPY = copia o que voce faz. FIGHT = vai atras de voce e luta. */
    public enum Mode {
        COPY, FIGHT, REDUCE;

        public static Mode parse(String text) {
            if (text == null) {
                return null;
            }
            String t = text.trim().toLowerCase();
            if (t.equals("fight") || t.equals("luta") || t.equals("lutar") || t.equals("pvp")) {
                return FIGHT;
            }
            if (t.equals("copy") || t.equals("copiar") || t.equals("espelho") || t.equals("espelhar")) {
                return COPY;
            }
            if (t.equals("reduce") || t.equals("reducer") || t.equals("reduzir") || t.equals("red")) {
                return REDUCE;
            }
            return null;
        }
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
    private final Player owner;
    private final World world;
    private Location spawn;
    private final EntityPlayer npc;
    private final ItemStack[] equip = new ItemStack[5];
    private final List<Placed> placed = new ArrayList<Placed>();
    private final Random rnd = new Random();
    private Mode mode;
    private final PlayerPrefs prefs;

    // posicao e movimento do bot
    private double x, y, z;
    private double vy;
    private double kbX, kbZ;
    private double lastDirX, lastDirZ;
    private float yaw, pitch;
    private boolean onGround;
    private boolean sneaking, sprinting;
    private boolean removed;

    // ultima posicao do dono (para copiar os movimentos)
    private boolean hasLast;
    private double lastPx, lastPy, lastPz;

    private long tick;
    private long botHurtUntil;
    private long ownerHurtUntil;
    private long skipMirrorUntil;
    private long lastPlaceMs;
    private long nextAttackTick = 20;
    private long nextBridgeTick;
    private long nextStrafeTick;
    private long sneakUntil;
    private int strafeDir = 1;
    private int seriesLeft;
    private int hitCount;
    private int falls;

    public BotSession(MLGBotPlugin plugin, Player owner, Location loc, Mode mode, PlayerPrefs prefs) {
        this.plugin = plugin;
        this.mode = mode;
        this.prefs = prefs;
        this.owner = owner;
        this.world = loc.getWorld();
        this.spawn = loc.clone();
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();

        Location pl = owner.getLocation();
        this.yaw = (float) Math.toDegrees(Math.atan2(-(pl.getX() - x), pl.getZ() - z));
        this.pitch = 0f;

        Settings s = plugin.settings();

        // nasce em chao firme (se o ponto de 3 blocos a frente for vazio, procura outro)
        placeNearOwner(pl, s, mode == Mode.REDUCE ? s.reduceStartDistance : 3.0);
        this.spawn = new Location(world, x, y, z);
        this.yaw = (float) Math.toDegrees(Math.atan2(-(pl.getX() - x), pl.getZ() - z));

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        WorldServer ws = ((CraftWorld) world).getHandle();

        String name = ChatColor.translateAlternateColorCodes('&',
                (prefs.botNick != null && !prefs.botNick.isEmpty()) ? prefs.botNick : s.botName);
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        if (name.isEmpty()) {
            name = "Bot";
        }
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        if (s.copySkin) {
            GameProfile src = ((CraftPlayer) owner).getHandle().getProfile();
            profile.getProperties().putAll(src.getProperties());
        }
        if (prefs.skinValue != null) {
            profile.getProperties().removeAll("textures");
            Property tex = prefs.skinSignature != null
                    ? new Property("textures", prefs.skinValue, prefs.skinSignature)
                    : new Property("textures", prefs.skinValue);
            profile.getProperties().put("textures", tex);
        }

        this.npc = new EntityPlayer(server, ws, profile, new PlayerInteractManager(ws));
        this.npc.setLocation(x, y, z, yaw, pitch);
        // mostra todas as camadas da skin (capa, chapeu, etc.)
        this.npc.getDataWatcher().watch(10, Byte.valueOf((byte) 127));

        show();
    }

    public UUID getOwnerId() {
        return owner.getUniqueId();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        nextAttackTick = tick + 20;
    }

    // ------------------------------------------------------------------
    // Pacotes
    // ------------------------------------------------------------------

    private void send(Packet packet) {
        try {
            if (owner.isOnline()) {
                ((CraftPlayer) owner).getHandle().playerConnection.sendPacket(packet);
            }
        } catch (Exception ignored) {
            // jogador desconectando
        }
    }

    private void show() {
        send(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, npc));
        send(new PacketPlayOutNamedEntitySpawn(npc));
        send(new PacketPlayOutEntityMetadata(npc.getId(), npc.getDataWatcher(), true));
        send(new PacketPlayOutEntityHeadRotation(npc, (byte) (int) (yaw * 256.0F / 360.0F)));
        syncEquipment(true);
        // tira o bot da lista de jogadores (tab) depois que a skin carregou
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (!removed) {
                    send(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, npc));
                }
            }
        }, 20L);
    }

    public void remove(boolean cleanBlocks) {
        if (removed) {
            return;
        }
        removed = true;
        send(new PacketPlayOutEntityDestroy(npc.getId()));
        send(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, npc));
        if (cleanBlocks && plugin.settings().cleanBlocks) {
            for (Placed p : placed) {
                if (p.block.getTypeId() == p.typeId) {
                    p.block.setType(Material.AIR);
                }
            }
            placed.clear();
        }
    }

    private boolean same(ItemStack a, ItemStack b) {
        boolean ea = a == null || a.getType() == Material.AIR;
        boolean eb = b == null || b.getType() == Material.AIR;
        if (ea || eb) {
            return ea && eb;
        }
        return a.isSimilar(b);
    }

    /** Item da mao e armadura do dono aparecem no bot. */
    private void syncEquipment(boolean force) {
        if (!plugin.settings().copyHeldItem) {
            return;
        }
        ItemStack[] now = new ItemStack[] {
            owner.getItemInHand(),
            owner.getInventory().getBoots(),
            owner.getInventory().getLeggings(),
            owner.getInventory().getChestplate(),
            owner.getInventory().getHelmet()
        };
        for (int i = 0; i < 5; i++) {
            if (force || !same(equip[i], now[i])) {
                equip[i] = now[i] == null ? null : now[i].clone();
                send(new PacketPlayOutEntityEquipment(npc.getId(), i, CraftItemStack.asNMSCopy(now[i])));
            }
        }
    }

    // ------------------------------------------------------------------
    // Fisica simples (caixa de colisao de jogador: 0.6 x 1.8)
    // ------------------------------------------------------------------

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    private boolean solid(int bx, int by, int bz) {
        if (by < 0 || by > 255) {
            return false;
        }
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return true;
        }
        return world.getBlockAt(bx, by, bz).getType().isSolid();
    }

    private boolean collides(double cx, double cy, double cz) {
        int x0 = floor(cx - HALF_WIDTH + EPS);
        int x1 = floor(cx + HALF_WIDTH - EPS);
        int y0 = floor(cy + EPS);
        int y1 = floor(cy + HEIGHT - EPS);
        int z0 = floor(cz - HALF_WIDTH + EPS);
        int z1 = floor(cz + HALF_WIDTH - EPS);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (solid(bx, by, bz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Tem chao embaixo (usado para nao cair da borda quando agachado). */
    private boolean supported(double cx, double cy, double cz) {
        return collides(cx, cy - 0.15, cz);
    }

    // ------------------------------------------------------------------
    // Loop principal (1x por tick)
    // ------------------------------------------------------------------

    /** @return false se o bot deve ser removido */
    public boolean tick() {
        if (removed) {
            return false;
        }
        if (!owner.isOnline() || !owner.getWorld().equals(world)) {
            return false;
        }
        Settings s = plugin.settings();
        tick++;

        // quanto o dono se mexeu desde o tick passado
        Location pl = owner.getLocation();
        double px = pl.getX();
        double py = pl.getY();
        double pz = pl.getZ();
        double dx = 0, dy = 0, dz = 0;
        if (hasLast) {
            dx = px - lastPx;
            dy = py - lastPy;
            dz = pz - lastPz;
        }
        lastPx = px;
        lastPy = py;
        lastPz = pz;
        hasLast = true;
        // teleporte ou empurrao recente: nao copia esse movimento
        if (dx * dx + dz * dz > 9.0 || Math.abs(dy) > 5.0 || tick < skipMirrorUntil) {
            dx = 0;
            dy = 0;
            dz = 0;
        }

        // se um bloco apareceu dentro do bot, sobe
        int guard = 0;
        while (collides(x, y, z) && guard++ < 4) {
            y += 1.0;
        }

        // bot sempre olha para o dono
        double tx = px - x;
        double tz = pz - z;
        double hd = Math.sqrt(tx * tx + tz * tz);
        if (hd > 0.05) {
            yaw = (float) Math.toDegrees(Math.atan2(-tx, tz));
        }
        pitch = (float) -Math.toDegrees(Math.atan2(py - y, Math.max(hd, 0.001)));

        double inX = 0, inZ = 0;
        boolean wantJump = false;
        boolean botSneak;
        boolean botSprint;

        if (mode != Mode.COPY) {
            // ---------- MODO LUTA / REDUCE: vai atras do dono ----------
            boolean reduce = mode == Mode.REDUCE;
            if (reduce && (hd > 12.0 || y < py - 4.0)) {
                // ficou muito longe, ou ficou sem chao: aparece em chao firme na frente do dono
                placeNearOwner(pl, s, s.reduceDistance);
            }
            double yawRad = Math.toRadians(yaw);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double rgX = -Math.cos(yawRad);
            double rgZ = -Math.sin(yawRad);
            double fwd;
            double str = 0.0;
            double speed;
            if (reduce) {
                // fica a uma distancia fixa na frente do dono, sem andar de lado
                double want = s.reduceDistance;
                if (hd > want + 0.3) {
                    fwd = 1.0;
                } else {
                    // perto demais: o bot segura a posicao (nao recua quando voce anda pra frente)
                    fwd = 0.0;
                }
                speed = s.reduceSpeed;
            } else {
                if (tick >= nextStrafeTick) {
                    strafeDir = rnd.nextBoolean() ? 1 : -1;
                    nextStrafeTick = tick + 12 + rnd.nextInt(20);
                }
                if (hd > 2.6) {
                    fwd = 1.0;
                } else if (hd < 1.4) {
                    fwd = -0.6;
                } else {
                    fwd = 0.2;
                }
                str = hd < 4.5 ? strafeDir * 0.6 : 0.0;
                speed = s.fightSpeed;
            }
            if (kbX * kbX + kbZ * kbZ > 0.02) {
                speed *= 0.3;
            }
            inX = (dirX * fwd + rgX * str) * speed;
            inZ = (dirZ * fwd + rgZ * str) * speed;
            botSprint = fwd > 0.5;
            botSneak = tick < sneakUntil;
            // degrau na frente: pula
            if (onGround
                    && collides(x + dirX * 0.5, y, z + dirZ * 0.5)
                    && !collides(x + dirX * 0.5, y + 1.0, z + dirZ * 0.5)) {
                wantJump = true;
            }
        } else {
            // ---------- MODO COPIAR: faz o que o dono faz ----------
            botSneak = s.copySneak && owner.isSneaking();
            botSprint = owner.isSprinting();
            wantJump = s.copyJump && onGround && dy > 0.3 && dy < 0.7;
            if (s.copyMovement) {
                double py2 = Math.toRadians(pl.getYaw());
                double pfx = -Math.sin(py2), pfz = Math.cos(py2);
                double prx = -Math.cos(py2), prz = -Math.sin(py2);
                double fwd = dx * pfx + dz * pfz;
                double str = dx * prx + dz * prz;

                double by2 = Math.toRadians(yaw);
                double bfx = -Math.sin(by2), bfz = Math.cos(by2);
                double brx = -Math.cos(by2), brz = -Math.sin(by2);
                inX = fwd * bfx + str * brx;
                inZ = fwd * bfz + str * brz;

                double len = Math.sqrt(inX * inX + inZ * inZ);
                if (len > 0.45) {
                    inX = inX / len * 0.45;
                    inZ = inZ / len * 0.45;
                }
                if (len > 0.01) {
                    lastDirX = inX / len;
                    lastDirZ = inZ / len;
                }
            }
        }

        // agachar / correr (so o visual do bot)
        if (botSneak != sneaking || botSprint != sprinting) {
            sneaking = botSneak;
            sprinting = botSprint;
            npc.setSneaking(botSneak);
            npc.setSprinting(botSprint);
            send(new PacketPlayOutEntityMetadata(npc.getId(), npc.getDataWatcher(), true));
        }

        // pulo
        if (wantJump && onGround) {
            vy = 0.42;
        }

        // andar (agachado, ou lutando, nao cai da borda; lutando ele faz ponte)
        double sx = inX;
        double sz = inZ;
        if ((mode != Mode.COPY || sneaking) && onGround) {
            if (sx != 0 && !supported(x + sx, y, z)) {
                if (mode == Mode.FIGHT) {
                    tryBridge(s, Math.signum(sx), 0.0);
                }
                sx = 0;
            }
            if (sz != 0 && !supported(x, y, z + sz)) {
                if (mode == Mode.FIGHT) {
                    tryBridge(s, 0.0, Math.signum(sz));
                }
                sz = 0;
            }
        }
        double nx = x + sx + kbX;
        if (!collides(nx, y, z)) {
            x = nx;
        } else {
            kbX = 0;
        }
        double nz = z + sz + kbZ;
        if (!collides(x, y, nz)) {
            z = nz;
        } else {
            kbZ = 0;
        }

        boolean chill = mode == Mode.REDUCE && prefs.chillOut;
        if (chill) {
            // chill out: o bot fica na altura do dono
            y = py;
            vy = 0;
            onGround = true;
        } else {
            // gravidade (em passos pequenos para nao atravessar o chao em quedas longas)
            onGround = false;
            int steps = Math.max(1, (int) Math.ceil(Math.abs(vy) / 0.5));
            double stepY = vy / steps;
            for (int i = 0; i < steps; i++) {
                double ny = y + stepY;
                if (collides(x, ny, z)) {
                    if (vy < 0) {
                        y = floor(ny + EPS) + 1.0;
                        onGround = true;
                    } else {
                        y = floor(ny + HEIGHT - EPS) - HEIGHT - EPS;
                    }
                    vy = 0;
                    break;
                }
                y = ny;
            }
            vy = (vy - 0.08) * 0.98;
        }

        // atrito do knockback
        double friction = onGround ? 0.546 : 0.91;
        kbX *= friction;
        kbZ *= friction;
        if (Math.abs(kbX) < 0.005) {
            kbX = 0;
        }
        if (Math.abs(kbZ) < 0.005) {
            kbZ = 0;
        }

        // caiu no void: volta pro spawn
        if (mode != Mode.REDUCE && y < spawn.getY() - s.fallDepth) {
            respawn(s);
        }

        // modo luta / reduce: bate quando o dono esta ao alcance
        boolean timerAttack = mode == Mode.FIGHT || (mode == Mode.REDUCE && !prefs.epicMoves);
        if (timerAttack && tick >= nextAttackTick) {
            double ax = px - x;
            double az = pz - z;
            double ay = (py + 0.9) - (y + 1.62);
            double d3 = Math.sqrt(ax * ax + ay * ay + az * az);
            if (d3 <= (mode == Mode.REDUCE ? prefs.radius : s.reach - 0.2)) {
                send(new PacketPlayOutAnimation(npc, 0));
                botAttack();
                if (mode == Mode.REDUCE) {
                    PlayerPrefs.Training tr = prefs.training;
                    if (tr == PlayerPrefs.Training.AIM_REDUCE) {
                        nextAttackTick = tick + s.reduceInterval + rnd.nextInt(Math.max(1, s.reduceJitter));
                    } else {
                        // Classic clutcher: serie de golpes e pausa. Fastclutch: 1 golpe e pausa.
                        if (seriesLeft <= 0) {
                            seriesLeft = tr == PlayerPrefs.Training.FASTCLUTCH ? 1 : Math.max(1, prefs.seriesHits);
                        }
                        seriesLeft--;
                        if (seriesLeft > 0) {
                            nextAttackTick = tick + s.reduceInterval + rnd.nextInt(Math.max(1, s.reduceJitter));
                        } else {
                            nextAttackTick = tick + Math.max(1, (int) Math.round(prefs.seriesPause * 20.0));
                        }
                    }
                } else {
                    nextAttackTick = tick + s.fightAttackInterval + rnd.nextInt(Math.max(1, s.fightAttackJitter));
                }
            }
        }

        syncEquipment(false);

        npc.setLocation(x, y, z, yaw, pitch);
        npc.onGround = onGround;
        send(new PacketPlayOutEntityTeleport(npc));
        send(new PacketPlayOutEntityHeadRotation(npc, (byte) (int) (yaw * 256.0F / 360.0F)));
        return true;
    }

    /** Modo luta: poe um bloco na frente do bot para ele poder continuar andando (ponte). */
    private void tryBridge(Settings s, double ax, double az) {
        if (!s.fightBridge || tick < nextBridgeTick || placed.size() >= s.maxBlocks) {
            return;
        }
        int by = floor(y - 0.5);
        if (by < 0 || by > 255) {
            return;
        }
        int bx = floor(x + ax * 0.7);
        int bz = floor(z + az * 0.7);
        Block t = world.getBlockAt(bx, by, bz);
        if (t.getType() == Material.AIR) {
            t.setType(s.fightBlock);
            placed.add(new Placed(t, t.getTypeId()));
            nextBridgeTick = tick + s.fightBridgeDelay;
            sneakUntil = tick + s.fightBridgeDelay + 2;
            owner.playSound(t.getLocation(), Sound.DIG_STONE, 0.8f, 1f);
        }
    }

    /**
     * Poe o bot em chao firme perto do dono: tenta na frente dele e, se for vazio
     * (ilha pequena, borda, buraco), tenta mais perto, ao lado de tras e por fim no lugar do dono.
     */
    private boolean placeNearOwner(Location pl, Settings s, double firstDist) {
        double yawRad = Math.toRadians(pl.getYaw());
        double[] dists = new double[] {firstDist, 6.0, 4.0, 3.0, 2.2, 1.6, 1.0, -1.5, 0.0};
        double baseY = Math.floor(pl.getY());
        for (double d : dists) {
            double cx = pl.getX() - Math.sin(yawRad) * d;
            double cz = pl.getZ() + Math.cos(yawRad) * d;
            for (double yy = baseY + 1.0; yy >= baseY - 3.0; yy -= 1.0) {
                if (!collides(cx, yy, cz) && supported(cx, yy, cz)) {
                    x = cx;
                    y = yy;
                    z = cz;
                    vy = 0;
                    kbX = 0;
                    kbZ = 0;
                    onGround = true;
                    return true;
                }
            }
        }
        return false;
    }

    /** Texto em cima da hotbar (action bar) do dono. */
    private void actionBar(String text) {
        try {
            String json = "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            send(new PacketPlayOutChat(IChatBaseComponent.ChatSerializer.a(json), (byte) 2));
        } catch (Exception ignored) {
            // sem action bar nao tem problema
        }
    }

    /** O dono caiu: mostra quantos golpes aguentou, guarda o recorde e zera. */
    public void onOwnerFell() {
        if (mode != Mode.REDUCE || hitCount <= 0) {
            hitCount = 0;
            return;
        }
        String msg = "&7Voce aguentou &f" + hitCount + " &7golpes.";
        if (hitCount > prefs.bestHits) {
            prefs.bestHits = hitCount;
            plugin.rooms().savePrefs();
            msg += " &aNovo recorde!";
        } else {
            msg += " Recorde: &f" + prefs.bestHits;
        }
        owner.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&cBot&8] " + msg));
        hitCount = 0;
        seriesLeft = 0;
        nextAttackTick = tick + 30;
    }

    private void respawn(Settings s) {
        x = spawn.getX();
        y = spawn.getY();
        z = spawn.getZ();
        vy = 0;
        kbX = 0;
        kbZ = 0;
        onGround = false;
        falls++;
        owner.sendMessage(s.msg("fell").replace("%total%", String.valueOf(falls)));
        owner.playSound(owner.getLocation(), Sound.LEVEL_UP, 1f, 1.5f);
    }

    // ------------------------------------------------------------------
    // Acoes do dono
    // ------------------------------------------------------------------

    /** O dono bateu (balancou o braco). */
    public void onOwnerSwing() {
        if (removed) {
            return;
        }
        // colocar bloco tambem balanca o braco: ignora
        if (System.currentTimeMillis() - lastPlaceMs < 200L) {
            return;
        }
        Settings s = plugin.settings();

        if (rayHitsBot(s.reach)) {
            hitBot(s);
        }

        if ((s.copySwing && mode == Mode.COPY) || (mode == Mode.REDUCE && prefs.epicMoves)) {
            send(new PacketPlayOutAnimation(npc, 0));
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                public void run() {
                    if (!removed) {
                        botAttack();
                    }
                }
            }, (long) Math.max(0, s.hitBackDelay));
        }
    }

    /** O dono colocou um bloco: o bot coloca um tambem, na frente dele. */
    public void onOwnerPlace(Block b) {
        lastPlaceMs = System.currentTimeMillis();
        final Settings s = plugin.settings();
        if (mode != Mode.COPY || !s.copyBlockPlace) {
            return;
        }
        final int id = b.getTypeId();
        final byte data = b.getData();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() {
                if (removed || (lastDirX == 0 && lastDirZ == 0) || placed.size() >= s.maxBlocks) {
                    return;
                }
                int bx = floor(x + lastDirX * 0.9);
                int bz = floor(z + lastDirZ * 0.9);
                int by = floor(y - 0.5);
                if (by < 0 || by > 255) {
                    return;
                }
                Block t = world.getBlockAt(bx, by, bz);
                if (t.getType() == Material.AIR) {
                    t.setTypeIdAndData(id, data, false);
                    placed.add(new Placed(t, id));
                }
            }
        }, 3L);
    }

    /** O olhar do dono acerta a caixa do bot dentro do alcance? */
    private boolean rayHitsBot(double reach) {
        Location eye = owner.getEyeLocation();
        Vector dir = eye.getDirection();
        double[] o = new double[] {eye.getX(), eye.getY(), eye.getZ()};
        double[] d = new double[] {dir.getX(), dir.getY(), dir.getZ()};
        double[] mn = new double[] {x - 0.4, y - 0.05, z - 0.4};
        double[] mx = new double[] {x + 0.4, y + 1.85, z + 0.4};
        double tMin = 0;
        double tMax = reach;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-9) {
                if (o[i] < mn[i] || o[i] > mx[i]) {
                    return false;
                }
            } else {
                double t1 = (mn[i] - o[i]) / d[i];
                double t2 = (mx[i] - o[i]) / d[i];
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Nivel de knockback do item da mao do dono (+1 se estiver correndo). */
    private int knockbackLevel() {
        int lvl = 0;
        ItemStack hand = owner.getItemInHand();
        if (hand != null && hand.getType() != Material.AIR) {
            lvl += hand.getEnchantmentLevel(Enchantment.KNOCKBACK);
        }
        if (owner.isSprinting()) {
            lvl += 1;
        }
        return lvl;
    }

    /** Nivel de knockback dos golpes do bot. */
    private int botKnockbackLevel(Settings s) {
        if (mode == Mode.FIGHT) {
            return s.fightKnockbackLevel + (sprinting ? 1 : 0);
        }
        return knockbackLevel();
    }

    /** O dono acertou o bot: o bot e empurrado. */
    private void hitBot(Settings s) {
        if (tick < botHurtUntil) {
            return;
        }
        botHurtUntil = tick + s.hitCooldown;
        if (mode == Mode.FIGHT) {
            nextAttackTick = Math.max(nextAttackTick, tick + 6);
        }
        if (mode == Mode.REDUCE) {
            // bot do reduce nao e empurrado: so mostra que foi acertado
            send(new PacketPlayOutEntityStatus(npc, (byte) 2));
            owner.playSound(new Location(world, x, y, z), Sound.HURT_FLESH, 1f, 1f);
            return;
        }

        Location pl = owner.getLocation();
        double hx = x - pl.getX();
        double hz = z - pl.getZ();
        double len = Math.sqrt(hx * hx + hz * hz);
        if (len < 1.0E-4) {
            double r = Math.toRadians(pl.getYaw());
            hx = -Math.sin(r);
            hz = Math.cos(r);
            len = 1.0;
        }
        hx /= len;
        hz /= len;

        int lvl = knockbackLevel();
        double h = s.kbHorizontal + lvl * s.kbPerLevel;
        // igual ao Minecraft 1.8: metade da velocidade antiga + o empurrao novo
        kbX = kbX * 0.5 + hx * h;
        kbZ = kbZ * 0.5 + hz * h;
        vy = Math.min(s.kbVertical, vy * 0.5 + s.kbVertical);
        if (lvl > 0) {
            vy += 0.1;
        }

        send(new PacketPlayOutEntityStatus(npc, (byte) 2));
        owner.playSound(new Location(world, x, y, z), Sound.HURT_FLESH, 1f, 1f);
    }

    /** O bot bate no dono (se ele estiver ao alcance). */
    private void botAttack() {
        Settings s = plugin.settings();
        if (tick < ownerHurtUntil) {
            return;
        }
        Location pl = owner.getLocation();
        double dx = pl.getX() - x;
        double dz = pl.getZ() - z;
        double dy = (pl.getY() + 0.9) - (y + 1.62);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > (mode == Mode.REDUCE ? prefs.radius : s.reach)) {
            return;
        }
        ownerHurtUntil = tick + s.hitCooldown;
        skipMirrorUntil = tick + 8;
        hitCount++;
        if (mode == Mode.REDUCE) {
            actionBar(ChatColor.RED + "[" + hitCount + "]");
        }

        double hl = Math.sqrt(dx * dx + dz * dz);
        if (hl < 1.0E-4) {
            double r = Math.toRadians(yaw);
            dx = -Math.sin(r);
            dz = Math.cos(r);
            hl = 1.0;
        }
        double h;
        double v;
        if (mode == Mode.REDUCE) {
            h = prefs.hitH;
            v = prefs.hitV;
        } else {
            int lvl = botKnockbackLevel(s);
            h = s.kbHorizontal + lvl * s.kbPerLevel;
            v = s.kbVertical + (lvl > 0 ? 0.1 : 0.0);
        }

        if (s.botDamage > 0) {
            owner.damage(s.botDamage);
        }
        owner.setVelocity(new Vector(dx / hl * h, v, dz / hl * h));
        owner.playEffect(EntityEffect.HURT);
        owner.playSound(pl, Sound.HURT_FLESH, 1f, 1f);
    }
}
