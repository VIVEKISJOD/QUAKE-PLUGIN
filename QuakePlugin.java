package com.quake;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class QuakePlugin extends JavaPlugin implements Listener {

    // ================= SETTINGS (change these numbers to tune the game) =================
    static final int MATCH_SECONDS = 300;          // match length: 5 minutes
    static final int SPEED_LEVEL = 1;              // 0 = Speed I, 1 = Speed II, 2 = Speed III
    static final double ARROW_SPEED = 3.5;         // how fast arrows fly
    static final double ARROW_DAMAGE = 10.0;       // 20 = full health, so 10 = two hits to kill
    static final long SHOT_DELAY_MS = 400;         // time between shots
    static final long DASH_DELAY_MS = 1500;        // dash cooldown
    static final double DASH_POWER = 1.8;          // how far the dash throws you
    static final boolean SNEAK_DASH = true;        // true = the sneak button also dashes (handy on phones)
    static final long SPAWN_PROTECTION_MS = 3000;  // safe time after respawn
    static final int ARENA_RADIUS = 40;            // arena size around world spawn
    static final int FEED_TICKS = 80;              // kill feed line stays 4 seconds (20 ticks = 1 sec)
    static final NamedTextColor KILLER_COLOR = NamedTextColor.BLACK;
    static final NamedTextColor VICTIM_COLOR = NamedTextColor.RED;
    static final Color BURST_COLOR = Color.RED;    // death firework color
    // ====================================================================================

    final Map<UUID, Integer> kills = new HashMap<>();
    final Map<UUID, Long> lastShot = new HashMap<>();
    final Map<UUID, Long> lastDash = new HashMap<>();
    final Map<UUID, Long> protectedUntil = new HashMap<>();
    final Set<UUID> arrows = new HashSet<>();
    final List<BossBar> feed = new ArrayList<>();
    final Random random = new Random();

    Scoreboard board;
    Objective objective;
    int timeLeft = MATCH_SECONDS;

    @Override
    public void onEnable() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("quake", Criteria.DUMMY, Component.text("QUAKE ARENA"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            setupPlayer(p);
        }
    }

    // ---------- Every second ----------
    void tick() {
        timeLeft--;
        if (timeLeft <= 0) {
            endRound();
        }
        Location c = arenaCenter();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            if (l.getWorld() != c.getWorld()
                    || Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) > ARENA_RADIUS + 15
                    || l.getY() < c.getY() - 60) {
                respawn(p);
            }
        }
        updateBoard();
    }

    void updateBoard() {
        String time = String.format("%d:%02d", timeLeft / 60, timeLeft % 60);
        objective.displayName(Component.text("QUAKE  " + time, NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Player p : Bukkit.getOnlinePlayers()) {
            objective.getScore(p.getName()).setScore(kills.getOrDefault(p.getUniqueId(), 0));
        }
    }

    void endRound() {
        int best = 0;
        List<String> winners = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int k = kills.getOrDefault(p.getUniqueId(), 0);
            if (k > best) {
                best = k;
                winners.clear();
                winners.add(p.getName());
            } else if (k == best && k > 0) {
                winners.add(p.getName());
            }
        }
        Component big;
        Component small;
        if (winners.isEmpty()) {
            big = Component.text("NO WINNER", NamedTextColor.GRAY, TextDecoration.BOLD);
            small = Component.text("Nobody got a kill", NamedTextColor.YELLOW);
        } else {
            big = Component.text("WINNER: " + String.join(", ", winners), NamedTextColor.GOLD, TextDecoration.BOLD);
            small = Component.text(best + " kills", NamedTextColor.YELLOW);
        }
        Bukkit.getServer().showTitle(Title.title(big, small));

        kills.replaceAll((id, v) -> 0);
        timeLeft = MATCH_SECONDS;
        for (Player p : Bukkit.getOnlinePlayers()) {
            respawn(p);
        }
    }

    // ---------- Player setup / respawn ----------
    void setupPlayer(Player p) {
        kills.putIfAbsent(p.getUniqueId(), 0);
        p.setScoreboard(board);
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.getInventory().setItem(0, makeHoe());
        p.getInventory().setHeldItemSlot(0);
        p.setFoodLevel(20);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION,
                SPEED_LEVEL, false, false, false));
        respawn(p);
    }

    ItemStack makeHoe() {
        ItemStack hoe = new ItemStack(Material.IRON_HOE);
        ItemMeta meta = hoe.getItemMeta();
        meta.displayName(Component.text("Quake Hoe", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        hoe.setItemMeta(meta);
        return hoe;
    }

    boolean isHoe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_HOE");
    }

    Location arenaCenter() {
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    Location randomSpot() {
        Location c = arenaCenter();
        World w = c.getWorld();
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(random.nextDouble()) * (ARENA_RADIUS - 5);
        int x = (int) Math.floor(c.getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(c.getZ() + Math.sin(angle) * dist);
        Block b = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (b.isLiquid()) {
            return null;
        }
        return new Location(w, x + 0.5, b.getY() + 1, z + 0.5);
    }

    // Picks the spot farthest from every other player, so nobody can camp a spawn.
    void respawn(Player p) {
        Location best = null;
        double bestDistance = -1;
        for (int i = 0; i < 12; i++) {
            Location spot = randomSpot();
            if (spot == null) {
                continue;
            }
            double nearest = 1000000;
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p) || o.getWorld() != spot.getWorld()) {
                    continue;
                }
                nearest = Math.min(nearest, o.getLocation().distance(spot));
            }
            if (nearest > bestDistance) {
                bestDistance = nearest;
                best = spot;
            }
        }
        if (best == null) {
            best = arenaCenter();
        }
        p.teleport(best);
        p.setVelocity(new Vector(0, 0, 0));
        p.setHealth(20);
        p.setFallDistance(0);
        p.setFireTicks(0);
        protectedUntil.put(p.getUniqueId(), System.currentTimeMillis() + SPAWN_PROTECTION_MS);
    }

    // ---------- Shooting and dashing ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player p = e.getPlayer();
        if (!isHoe(p.getInventory().getItemInMainHand())) {
            return;
        }
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            e.setCancelled(true);
            shoot(p);
        } else if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            dash(p);
        }
    }

    // Sneak button = dash (phones have a sneak button on screen)
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!SNEAK_DASH || !e.isSneaking()) {
            return;
        }
        Player p = e.getPlayer();
        if (isHoe(p.getInventory().getItemInMainHand())) {
            dash(p);
        }
    }

    void shoot(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastShot.getOrDefault(p.getUniqueId(), 0L) < SHOT_DELAY_MS) {
            return;
        }
        lastShot.put(p.getUniqueId(), now);

        Vector dir = p.getEyeLocation().getDirection();
        Arrow arrow = p.launchProjectile(Arrow.class, dir.clone().multiply(ARROW_SPEED));
        arrow.setGravity(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrows.add(arrow.getUniqueId());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.5f);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            arrows.remove(arrow.getUniqueId());
            arrow.remove();
        }, 60L);
    }

    void dash(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastDash.getOrDefault(p.getUniqueId(), 0L) < DASH_DELAY_MS) {
            return;
        }
        lastDash.put(p.getUniqueId(), now);

        Vector v = p.getEyeLocation().getDirection().multiply(DASH_POWER);
        if (v.getY() < 0.2 && p.isOnGround()) {
            v.setY(0.2);
        }
        p.setVelocity(v);
        p.setFallDistance(0);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
    }

    // ---------- Damage and kills ----------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            respawn(victim);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Arrow arrow
                && arrows.contains(arrow.getUniqueId())) {
            e.setCancelled(true);
            arrows.remove(arrow.getUniqueId());
            arrow.remove();

            if (!(arrow.getShooter() instanceof Player shooter) || shooter.equals(victim)) {
                return;
            }
            if (protectedUntil.getOrDefault(victim.getUniqueId(), 0L) > System.currentTimeMillis()) {
                return;
            }
            double newHealth = victim.getHealth() - ARROW_DAMAGE;
            if (newHealth <= 0) {
                kill(shooter, victim);
            } else {
                victim.setHealth(newHealth);
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
            }
            return;
        }
        // Everything else (falling, fists, explosions, fire) does no damage.
        e.setCancelled(true);
    }

    void kill(Player killer, Player victim) {
        kills.merge(killer.getUniqueId(), 1, Integer::sum);
        burst(victim.getLocation().add(0, 1, 0));
        addFeed(killer.getName(), victim.getName());
        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        respawn(victim);
        updateBoard();
    }

    // Small firework pop where the player died. Damage is cancelled by onDamage, so it is visual only.
    void burst(Location loc) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(BURST_COLOR).build());
        meta.setPower(0);
        fw.setFireworkMeta(meta);
        fw.detonate();
    }

    // ---------- Kill feed (top middle, uses boss bars) ----------
    void addFeed(String killer, String victim) {
        Component text = Component.text(killer, KILLER_COLOR, TextDecoration.BOLD)
                .append(Component.text("  >>  ", NamedTextColor.GRAY))
                .append(Component.text(victim, VICTIM_COLOR, TextDecoration.BOLD));
        BossBar bar = BossBar.bossBar(text, 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        feed.add(bar);
        Bukkit.getServer().showBossBar(bar);
        if (feed.size() > 4) {
            BossBar old = feed.remove(0);
            Bukkit.getServer().hideBossBar(old);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (feed.remove(bar)) {
                Bukkit.getServer().hideBossBar(bar);
            }
        }, FEED_TICKS);
    }

    // ---------- Other rules ----------
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        setupPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        kills.remove(p.getUniqueId());
        lastShot.remove(p.getUniqueId());
        lastDash.remove(p.getUniqueId());
        protectedUntil.remove(p.getUniqueId());
        board.resetScores(p.getName());
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof Arrow a && arrows.contains(a.getUniqueId()) && e.getHitEntity() == null) {
            arrows.remove(a.getUniqueId());
            a.remove();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawnEvent(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> setupPlayer(p));
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            e.setCancelled(true);
        }
    }
}
