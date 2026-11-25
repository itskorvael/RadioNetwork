package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Signal Jammers: placed waxed Copper Bulbs (their light shows the state) linked to a Radio
 * Terminal with Signal Cables. A running jammer jams every channel inside the radius of its terminal, and
 * again around any relay the terminal's signal passes through, a bar weaker per relay. It burns extra fuel
 * from the terminal, warms up, overheats and cools down on a timer, and when the terminal runs dry it smokes
 * and sparks for a few seconds and then burns out in a small blast. Only the strongest jammer counts at any spot.
 * Main-thread only.
 */
public final class Jammers {

    public enum State { IDLE, WARMUP, ACTIVE, COOLDOWN, COUNTDOWN, BLOCKED }

    /** A placed Signal Jammer. */
    public static final class Jammer {
        final String world;
        final int x;
        final int y;
        final int z;
        final String owner;
        @Nullable String terminalKey;
        State state = State.IDLE;
        /** Seconds spent in the current state (in COUNTDOWN: seconds left). */
        int seconds;
        /** How hard it was jamming when its power failed; the jam holds at this until the housing gives out. */
        double heldStrength;
        double drainRemainder;
        int soundClock;
        /** Set when it reaches full strength; the radio alert goes out once its zone has been worked out. */
        boolean announcePending;
        /** Who was told "interference detected", and on which channels, so the same people hear "cleared". */
        final Map<Player, java.util.Set<String>> told = new LinkedHashMap<>();

        Jammer(String world, int x, int y, int z, String owner) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
        }

        public String key() {
            return RadioManager.key(world, x, y, z);
        }

        public String owner() {
            return owner;
        }

        public State state() {
            return state;
        }

        public @Nullable String terminalKey() {
            return terminalKey;
        }

        public @Nullable Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    /** One circle of interference: a terminal, how many relays away it is, and how far warmed up its jammer is. */
    private record Zone(Jammer jammer, String world, double x, double z, double radius, int hops, double strength) {
    }

    private static final int MAX_LOSS = 4;

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final MusicBoxes music;
    private final RadioItem items;
    private final PortableReceivers portables;
    private final File file;
    private final Map<String, Jammer> jammers = new LinkedHashMap<>();
    private final Map<String, Jammer> byTerminal = new HashMap<>();
    private List<Zone> zones = List.of();
    private final Map<String, Long> lastNotice = new HashMap<>();
    /** Per player: the channels they are listening to while jammed and have already been told about. */
    private final Map<UUID, java.util.Set<String>> jammedTold = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();

    public Jammers(RadioNetworkPlugin plugin, RadioManager manager, MusicBoxes music, RadioItem items,
                   PortableReceivers portables) {
        this.plugin = plugin;
        this.manager = manager;
        this.music = music;
        this.items = items;
        this.portables = portables;
        this.file = new File(plugin.getDataFolder(), "jammers.yml");
    }

    // ---------------------------------------------------------------- settings

    private int warmupSeconds() {
        return Math.max(1, plugin.getConfig().getInt("jammer.warmup-seconds", 60));
    }

    private int overheatSeconds() {
        return Math.max(1, plugin.getConfig().getInt("jammer.overheat-after-seconds", 600));
    }

    private int cooldownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("jammer.cooldown-seconds", 60));
    }

    /**
     * How far a terminal's jam reaches: its own radius, but never more than {@code jammer.max-radius} whatever
     * the fuel (an Ender Pearl terminal has no radius cap, and must not be able to jam a whole dimension).
     */
    public int jamRadius(Radio terminal) {
        return Math.min(terminal.customRadius(), Math.max(1, plugin.getConfig().getInt("jammer.max-radius", 150)));
    }

    private int countdownSeconds() {
        return Math.max(1, plugin.getConfig().getInt("jammer.countdown-seconds", 10));
    }

    // ---------------------------------------------------------------- registry

    public Jammer register(Block block, String owner) {
        Jammer jammer = new Jammer(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), owner);
        jammers.put(jammer.key(), jammer);
        save();
        return jammer;
    }

    public @Nullable Jammer get(Block block) {
        return jammers.get(RadioManager.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public Iterable<Jammer> all() {
        return jammers.values();
    }

    /** A jammer was broken, destroyed or blew up: it stops jamming at once. */
    public void remove(Jammer jammer) {
        endAlerts(jammer);
        jammers.remove(jammer.key());
        rebuildIndex();
        rebuildZones();
        save();
    }

    public @Nullable Radio terminalOf(Jammer jammer) {
        return jammer.terminalKey == null ? null : manager.get(jammer.terminalKey);
    }

    public @Nullable Jammer forTerminal(Radio terminal) {
        return byTerminal.get(terminal.key());
    }

    public void link(Jammer jammer, Radio terminal) {
        jammer.terminalKey = terminal.key();
        jammer.state = State.IDLE;
        jammer.seconds = 0;
        rebuildIndex();
        save();
    }

    /** Its Signal Cable was destroyed (lightning): the jammer goes inert (no jamming, no countdown). */
    public void sever(Jammer jammer) {
        endAlerts(jammer);
        jammer.terminalKey = null;
        jammer.state = State.IDLE;
        jammer.seconds = 0;
        rebuildIndex();
        rebuildZones();
        save();
    }

    /** A terminal was removed: its jammer goes inert (no jamming, no countdown). */
    public void unlinkTerminal(String terminalKey) {
        boolean changed = false;
        for (Jammer jammer : jammers.values()) {
            if (terminalKey.equals(jammer.terminalKey)) {
                endAlerts(jammer);
                jammer.terminalKey = null;
                jammer.state = State.IDLE;
                jammer.seconds = 0;
                changed = true;
            }
        }
        if (changed) {
            rebuildIndex();
            rebuildZones();
            save();
        }
    }

    private void rebuildIndex() {
        byTerminal.clear();
        for (Jammer jammer : jammers.values()) {
            if (jammer.terminalKey != null) {
                byTerminal.put(jammer.terminalKey, jammer);
            }
        }
    }

    // --------------------------------------------------------------- jamming

    /** Bars lost to jamming at a spot (0-4): the strongest jammer there counts, jammers never add up. */
    public int lossAt(@Nullable Location where) {
        if (where == null || zones.isEmpty() || where.getWorld() == null) {
            return 0;
        }
        int worst = 0;
        for (Zone zone : zones) {
            worst = Math.max(worst, lossIn(zone, where));
            if (worst >= MAX_LOSS) {
                break;
            }
        }
        return worst;
    }

    private int lossIn(Zone zone, Location where) {
        if (where.getWorld() == null || !where.getWorld().getName().equals(zone.world())) {
            return 0;
        }
        double dx = where.getX() - zone.x();
        double dz = where.getZ() - zone.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance >= zone.radius()) {
            return 0;
        }
        // Full strength at the middle, falling to the edge; a warming-up jammer ramps up from nothing. Each
        // relay the jam was passed through takes one bar off.
        double raw = zone.strength() * (MAX_LOSS * (1 - distance / zone.radius()) + 0.5);
        int level = Math.min(MAX_LOSS, (int) Math.round(raw));
        return Math.max(0, level - zone.hops());
    }

    /** Bars this one jammer takes off at a spot (for its own listing). */
    private int lossFrom(Jammer jammer, Location where) {
        int worst = 0;
        for (Zone zone : zones) {
            if (zone.jammer() == jammer) {
                worst = Math.max(worst, lossIn(zone, where));
            }
        }
        return worst;
    }

    /** How hard a warming-up or jamming jammer is jamming, from 0 to 1. */
    private double strengthOf(Jammer jammer) {
        return switch (jammer.state) {
            case ACTIVE -> 1.0;
            case WARMUP -> Math.min(1.0, jammer.seconds / (double) warmupSeconds());
            default -> 0.0;
        };
    }

    private void rebuildZones() {
        List<Zone> fresh = new ArrayList<>();
        for (Jammer jammer : jammers.values()) {
            // A jammer that lost its power keeps jamming until it blows: the signal only comes back with the bang.
            boolean failing = jammer.state == State.COUNTDOWN;
            if (jammer.state != State.WARMUP && jammer.state != State.ACTIVE && !failing) {
                continue;
            }
            Radio terminal = terminalOf(jammer);
            if (terminal == null || (!failing && !terminal.isPowered())) {
                continue;
            }
            double strength = failing ? jammer.heldStrength : strengthOf(jammer);
            if (strength <= 0) {
                continue;
            }
            fresh.add(new Zone(jammer, terminal.world(), terminal.x() + 0.5, terminal.z() + 0.5,
                    jamRadius(terminal), 0, strength));
            // The jam travels the way a signal does: every relay it reaches passes it on, a bar weaker each time.
            if (manager.isLive(terminal)) {
                RadioManager.Reach reach = manager.reachFrom(terminal, true, failing);
                int cap = manager.maxRelays();
                for (Radio tower : reach.towers()) {
                    int used = reach.relays().get(tower);
                    if (tower != terminal && used < cap && manager.isRelaying(tower)) {
                        fresh.add(new Zone(jammer, tower.world(), tower.x() + 0.5, tower.z() + 0.5,
                                jamRadius(tower), used + 1, strength));
                    }
                }
            }
        }
        zones = fresh;
    }

    // -------------------------------------------------------------- once a second

    public void tick() {
        tick(Bukkit.getOnlinePlayers());
    }

    /** One second of work; {@code players} are who is told about jammed channels. */
    void tick(java.util.Collection<? extends Player> players) {
        List<Jammer> gone = new ArrayList<>();
        for (Jammer jammer : new ArrayList<>(jammers.values())) {
            World world = Bukkit.getWorld(jammer.world);
            if (world == null || !world.isChunkLoaded(jammer.x >> 4, jammer.z >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(jammer.x, jammer.y, jammer.z);
            if (!isJammerBlock(block.getType())) {
                gone.add(jammer);
                continue;
            }
            if (block.getType() != BLOCK) {
                // An old jammer (a Respawn Anchor), or a bulb someone scraped with an axe: back to the waxed bulb.
                block.setType(BLOCK, false);
            }
            step(jammer, world, players);
            light(jammer, block);
        }
        gone.forEach(this::remove);
        rebuildZones();
        for (Jammer jammer : jammers.values()) {
            if (jammer.announcePending) {
                jammer.announcePending = false;
                announceStart(jammer, players);
            }
        }
        notifyListeners(players);
        showLinks();
    }

    /** What a jammer is made of: a waxed Copper Bulb, which never oxidises. Its light shows what the unit is doing. */
    public static final Material BLOCK = Material.WAXED_COPPER_BULB;

    /** Any Copper Bulb (a scraped one still counts until it is waxed again), or the Respawn Anchor older jammers were. */
    public static boolean isJammerBlock(Material type) {
        return type == Material.RESPAWN_ANCHOR || type.name().endsWith("COPPER_BULB");
    }

    /**
     * The status light: dark while idle, cooling down or shut down, flickering while it warms up, steady while it
     * jams, and flickering again when it is failing. It is set every second, so a redstone pulse cannot change it.
     */
    private void light(Jammer jammer, Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.Lightable bulb)) {
            return;
        }
        boolean on = switch (jammer.state) {
            case ACTIVE -> true;
            case WARMUP, COUNTDOWN -> jammer.seconds % 2 == 0;
            default -> false;
        };
        if (bulb.isLit() != on) {
            bulb.setLit(on);
            block.setBlockData(bulb, false);
        }
    }

    private void step(Jammer jammer, World world, java.util.Collection<? extends Player> players) {
        Radio terminal = terminalOf(jammer);
        if (terminal == null) {
            jammer.state = State.IDLE;
            jammer.seconds = 0;
            return;
        }
        World terminalWorld = terminal.bukkitWorld();
        if (terminalWorld == null || !terminalWorld.isChunkLoaded(terminal.x() >> 4, terminal.z() >> 4)) {
            return;
        }
        // Jammers need open sky: buried under blocks, one shuts down (it does not explode) until it is uncovered.
        if (jammer.state != State.COUNTDOWN) {
            String problem = coverProblem(world, jammer.x, jammer.y, jammer.z);
            if (problem != null) {
                if (jammer.state != State.BLOCKED) {
                    endAlerts(jammer);
                    jammer.state = State.BLOCKED;
                    jammer.seconds = 0;
                    Player owner = Bukkit.getPlayerExact(jammer.owner);
                    if (owner != null) {
                        Alerts.send(owner, Alerts.Level.RED, "Jammer", "Your Signal Jammer at " + jammer.x + " " + jammer.y + " "
                                + jammer.z + " has shut down: " + problem.substring(0, 1).toLowerCase() + problem.substring(1)
                                + " Uncover it and it warms up again.");
                    }
                }
                return;
            }
            if (jammer.state == State.BLOCKED) {
                jammer.state = State.IDLE;
                jammer.seconds = 0;
            }
        }
        boolean powered = terminal.isPowered();
        boolean fueled = terminal.hasFuel();
        switch (jammer.state) {
            case IDLE -> {
                if (powered) {
                    begin(jammer, State.WARMUP, world);
                }
            }
            case WARMUP, ACTIVE, COOLDOWN -> {
                if (fueled && !powered) {
                    // Knocked offline by lightning: the jam is off, and nothing explodes. A jammer that is cooling
                    // down keeps cooling (but waits to warm up until the terminal is back); the others wait.
                    if (jammer.state == State.COOLDOWN && jammer.seconds + 1 < cooldownSeconds()) {
                        running(jammer, terminal, world);
                    }
                    return;
                }
                if (!powered) {
                    // The jam does not lift now: the people told about it hear "cleared" when the jammer explodes.
                    jammer.heldStrength = strengthOf(jammer);
                    jammer.state = State.COUNTDOWN;
                    jammer.seconds = countdownSeconds();
                    countdownStep(jammer, world, players);
                    return;
                }
                running(jammer, terminal, world);
            }
            case COUNTDOWN -> countdownStep(jammer, world, players);
        }
    }

    private void begin(Jammer jammer, State state, World world) {
        jammer.state = state;
        jammer.seconds = 0;
        jammer.soundClock = 0;
        float volume = soundVolume();
        Location at = jammer.center();
        if (at != null) {
            if (state == State.WARMUP) {
                world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, volume, 0.6f);
            } else if (state == State.COOLDOWN) {
                world.playSound(at, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, volume, 0.7f);
            }
        }
    }

    /** Warming up, jamming or cooling down on a working terminal. */
    private void running(Jammer jammer, Radio terminal, World world) {
        jammer.seconds++;
        switch (jammer.state) {
            case WARMUP -> {
                drain(jammer, terminal);
                if (jammer.seconds >= warmupSeconds()) {
                    jammer.state = State.ACTIVE;
                    jammer.seconds = 0;
                    jammer.announcePending = true;
                }
            }
            case ACTIVE -> {
                drain(jammer, terminal);
                if (jammer.seconds >= overheatSeconds()) {
                    endAlerts(jammer);
                    begin(jammer, State.COOLDOWN, world); // overheated: it shuts down, it does not explode
                    return;
                }
            }
            case COOLDOWN -> {
                if (jammer.seconds >= cooldownSeconds()) {
                    begin(jammer, State.WARMUP, world);
                    return;
                }
            }
            default -> { }
        }
        if (jammer.state == State.WARMUP || jammer.state == State.ACTIVE) {
            hum(jammer, world);
        }
    }

    /** Extra fuel burned from the terminal: a multiple of what a playing jukebox burns. */
    private void drain(Jammer jammer, Radio terminal) {
        double rate = plugin.getConfig().getDouble("jammer.drain-multiplier", 2.0)
                * plugin.getConfig().getDouble("music.extra-drain-per-second", 0.5);
        if (rate <= 0) {
            return;
        }
        jammer.drainRemainder += rate;
        long whole = (long) Math.floor(jammer.drainRemainder);
        if (whole > 0) {
            jammer.drainRemainder -= whole;
            manager.consume(terminal, whole);
        }
    }

    private float soundVolume() {
        return (float) Math.max(0.0, plugin.getConfig().getDouble("jammer.sound-volume", 6.0));
    }

    /** A low hum every few seconds, climbing in pitch while it warms up. Loud enough to hear from afar, no beam. */
    private void hum(Jammer jammer, World world) {
        jammer.soundClock++;
        if (jammer.soundClock % 3 != 1) {
            return;
        }
        Location at = jammer.center();
        if (at == null) {
            return;
        }
        float pitch = jammer.state == State.ACTIVE ? 1.0f
                : 0.5f + 0.5f * (float) Math.min(1.0, jammer.seconds / (double) warmupSeconds());
        world.playSound(at, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, soundVolume(), pitch);
        if (jammer.state == State.ACTIVE) {
            // A faint wisp of heat off the casing while it works.
            world.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.6, 0), 2, 0.15, 0.05, 0.15, 0.005);
        }
    }

    // ---------------------------------------------------------------- explosion

    /**
     * Each second of the countdown the jammer gives itself away in the world, not in chat: smoke and sparks that
     * thicken, a crackle, and a whine that climbs in pitch. At zero it burns out.
     */
    private void countdownStep(Jammer jammer, World world, java.util.Collection<? extends Player> players) {
        if (jammer.seconds <= 0) {
            burnOut(jammer, world);
            return;
        }
        Location at = jammer.center();
        if (at != null) {
            int total = countdownSeconds();
            double progress = 1.0 - Math.min(total, jammer.seconds) / (double) total; // 0 at the start, 1 just before the end
            world.spawnParticle(Particle.LARGE_SMOKE, at, 3 + (int) (10 * progress), 0.3, 0.3, 0.3, 0.02);
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, 5 + (int) (20 * progress), 0.4, 0.4, 0.4, 0.2);
            world.playSound(at, Sound.BLOCK_CAMPFIRE_CRACKLE, SoundCategory.BLOCKS, 2.0f, 0.9f + 0.4f * (float) progress);
            world.playSound(at, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 2.5f, 1.0f + (float) progress);
        }
        jammer.seconds--;
    }

    /** The unit gives out: a flash of smoke and sparks and a small blast that destroys the jammer. */
    private void burnOut(Jammer jammer, World world) {
        Location at = jammer.center();
        remove(jammer);
        if (at == null) {
            return;
        }
        world.getBlockAt(jammer.x, jammer.y, jammer.z).setType(Material.AIR, false);
        Player owner = Bukkit.getPlayerExact(jammer.owner);
        if (owner != null) {
            Alerts.send(owner, Alerts.Level.RED, "Jammer", "Your Signal Jammer at " + jammer.x + " " + jammer.y + " "
                    + jammer.z + " burned out: its terminal ran out of fuel.");
        }
        world.spawnParticle(Particle.LARGE_SMOKE, at, 40, 0.5, 0.5, 0.5, 0.05);
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 60, 0.6, 0.6, 0.6, 0.4);
        world.createExplosion(at, (float) plugin.getConfig().getDouble("jammer.explosion-power", 2.0),
                plugin.getConfig().getBoolean("jammer.explosion-fire", false),
                plugin.getConfig().getBoolean("jammer.explosion-breaks-blocks", false));
    }

    // ------------------------------------------------------------ open sky rule

    /**
     * Why a jammer cannot be here, or null when it can: it needs a world with open sky (not the Nether or End)
     * and at most {@code jammer.max-cover} blocks of cover above it, so a roof or a tree is fine but a bunker or
     * a cave is not.
     */
    public @Nullable String coverProblem(World world, int x, int y, int z) {
        if (!world.hasSkyLight()) {
            return "Jammers only work in worlds with open sky (not the Nether or the End).";
        }
        int max = plugin.getConfig().getInt("jammer.max-cover", 4);
        int cover = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) - y;
        if (cover > max) {
            return "A jammer needs open sky: it has " + cover + " blocks of cover above it, and at most " + max + " are allowed.";
        }
        return null;
    }

    // ------------------------------------------------------------ radio alert

    /**
     * Once a jammer reaches full strength, listeners at the terminals it covers and holders of Pocket Receivers
     * tuned to a channel it covers get a chat alert on that channel. No place, no owner. A channel is announced
     * at most once per {@code jammer.alert-cooldown-seconds}.
     */
    private void announceStart(Jammer jammer, java.util.Collection<? extends Player> players) {
        long now = System.currentTimeMillis();
        long cooldown = (long) (plugin.getConfig().getDouble("jammer.alert-cooldown-seconds", 60.0) * 1000);
        Map<Player, java.util.Set<String>> candidates = new LinkedHashMap<>();
        java.util.Set<String> channels = new java.util.TreeSet<>();
        for (Player player : players) {
            if (!player.isOnline()) {
                continue;
            }
            Location where = player.getLocation();
            if (lossFrom(jammer, where) <= 0) {
                continue;
            }
            java.util.Set<String> heard = new java.util.TreeSet<>();
            ItemStack receiver = portables.activeReceiver(player);
            String tuned = receiver == null ? null : items.receiverChannel(receiver);
            if (tuned != null && !tuned.isEmpty()) {
                heard.add(tuned);
            }
            for (Radio radio : manager.radiosNear(where)) {
                Location center = radio.center();
                if (radio.isTuned() && center != null && manager.isAtTower(where, center)) {
                    heard.add(radio.channel());
                }
            }
            if (!heard.isEmpty()) {
                candidates.put(player, heard);
                channels.addAll(heard);
            }
        }
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (String channel : channels) {
            Long last = lastAlert.get(channel);
            if (last == null || now - last >= cooldown) {
                allowed.add(channel);
                lastAlert.put(channel, now);
            }
        }
        // jammer.channel-alerts: false leaves a jam to be noticed by the static and the lost bars alone.
        if (!plugin.getConfig().getBoolean("jammer.channel-alerts", true)) {
            return;
        }
        candidates.forEach((player, heard) -> {
            for (String channel : heard) {
                if (allowed.contains(channel)) {
                    Alerts.send(player, Alerts.Level.YELLOW, "Signal", "Interference detected on " + Alerts.name(channel)
                            + ". Signal degraded.");
                    jammedTold.computeIfAbsent(player.getUniqueId(), k -> new java.util.HashSet<>()).add(channel);
                    jammer.told.computeIfAbsent(player, k -> new java.util.TreeSet<>()).add(channel);
                }
            }
        });
    }

    /** The jam is over: everyone who was told about it is told it has cleared. */
    /**
     * A lightning strike on the terminal overheats its jammer: a warming-up or jamming jammer shuts down at once,
     * cools down, and then warms up from the start again. Returns whether there was a jammer for that to happen to.
     */
    public boolean overheat(Radio terminal) {
        Jammer jammer = forTerminal(terminal);
        World world = terminal.bukkitWorld();
        if (jammer == null || world == null || (jammer.state != State.WARMUP && jammer.state != State.ACTIVE)) {
            return false;
        }
        endAlerts(jammer);
        begin(jammer, State.COOLDOWN, world);
        rebuildZones();
        return true;
    }

    private void endAlerts(Jammer jammer) {
        jammer.announcePending = false;
        jammer.told.forEach((player, channels) -> {
            if (player.isOnline()) {
                for (String channel : channels) {
                    Alerts.send(player, Alerts.Level.CLEAR, "Signal", "Interference cleared. Signal restored on "
                            + Alerts.name(channel) + ".");
                }
            }
        });
        jammer.told.clear();
    }

    // ------------------------------------------------- joining a jammed frequency

    /**
     * Tells people who are listening to a jammed frequency, and only them: someone who tunes a Pocket Receiver
     * to a channel, steps up to a tuned terminal or tunes one while jammed, or who is already listening when the
     * jam reaches them. People without a radio on a covered channel hear nothing. Once per person per channel
     * until they leave the jam, and at most once per {@code jammer.listen-notice-cooldown-seconds}.
     */
    private void notifyListeners(java.util.Collection<? extends Player> players) {
        long now = System.currentTimeMillis();
        long cooldown = (long) (plugin.getConfig().getDouble("jammer.listen-notice-cooldown-seconds", 30.0) * 1000);
        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (Player player : players) {
            if (!player.isOnline()) {
                continue;
            }
            UUID id = player.getUniqueId();
            present.add(id);
            Location where = player.getLocation();
            int jam = lossAt(where);
            java.util.Set<String> noticed = jammedTold.computeIfAbsent(id, k -> new java.util.HashSet<>());
            if (jam <= 0) {
                noticed.clear(); // out of the jam: walking back in warns again
                continue;
            }
            Map<String, Integer> signal = new TreeMap<>();
            ItemStack receiver = portables.activeReceiver(player);
            String tuned = receiver == null ? null : items.receiverChannel(receiver);
            if (tuned != null && !tuned.isEmpty()) {
                boolean antenna = items.hasAntenna(receiver);
                // Only a channel the receiver would pick up without the jam counts: far from every tower there is nothing to jam.
                if (portables.channelBarsUnjammed(player.getName(), where, tuned, antenna) > 0) {
                    signal.merge(tuned, portables.channelBars(player.getName(), where, tuned, antenna), Math::max);
                }
            }
            for (Radio radio : manager.radiosNear(where)) {
                Location center = radio.center();
                if (radio.isTuned() && radio.isPowered() && manager.isLive(radio) && center != null
                        && manager.isAtTower(where, center)) {
                    signal.merge(radio.channel(), Math.max(0, 4 - jam), Math::max);
                }
            }
            noticed.retainAll(signal.keySet());
            for (Map.Entry<String, Integer> entry : signal.entrySet()) {
                if (!noticed.add(entry.getKey())) {
                    continue;
                }
                String key = id + "|" + entry.getKey();
                if (now - lastNotice.getOrDefault(key, 0L) < cooldown) {
                    continue;
                }
                lastNotice.put(key, now);
                Chat.send(player, noticeLine(entry.getKey(), entry.getValue()));
            }
        }
        jammedTold.keySet().retainAll(present);
    }

    private static String noticeLine(String channel, int bars) {
        return bars <= 0
                ? Alerts.line(Alerts.Level.RED, "Signal", "No signal on " + Alerts.name(channel) + ".")
                : Alerts.line(Alerts.Level.YELLOW, "Signal", "Signal on " + Alerts.name(channel) + " is " + bars + "/4.");
    }

    private void showLinks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!items.isCable(player.getInventory().getItemInMainHand())
                    && !items.isCable(player.getInventory().getItemInOffHand())) {
                continue;
            }
            for (Jammer jammer : jammers.values()) {
                Radio terminal = terminalOf(jammer);
                Location a = jammer.center();
                Location b = terminal == null ? null : terminal.center();
                if (a != null && b != null && a.getWorld().equals(player.getWorld())
                        && a.distanceSquared(player.getLocation()) < 24 * 24) {
                    music.drawLine(player, a, b);
                }
            }
        }
    }

    // ------------------------------------------------------------------ status

    public String statusLine(Jammer jammer) {
        Radio terminal = terminalOf(jammer);
        String head = "§cSignal Jammer §7- ";
        if (terminal == null) {
            return head + "§cnot linked§7. Use a Signal Cable on a Radio Terminal, then on this.";
        }
        String where = terminal.x() + " " + terminal.y() + " " + terminal.z();
        return head + switch (jammer.state) {
            case IDLE -> "§7linked to the terminal at " + where + "§7. Waiting for it to have power.";
            case WARMUP -> "§ewarming up §7(" + (100 * jammer.seconds / warmupSeconds()) + "%) - jamming "
                    + jamRadius(terminal) + " blocks around the terminal at " + where + "§7, getting stronger.";
            case ACTIVE -> "§cjamming §7every channel within " + jamRadius(terminal) + " blocks of the terminal at "
                    + where + "§7. Overheats in " + Chat.duration(Math.max(0, overheatSeconds() - jammer.seconds)) + ".";
            case COOLDOWN -> "§bcooling down §7(" + Chat.duration(Math.max(0, cooldownSeconds() - jammer.seconds))
                    + " left), then it warms up again.";
            case BLOCKED -> "§8shut down §7- buried under blocks. It needs open sky (at most "
                    + plugin.getConfig().getInt("jammer.max-cover", 4) + " blocks of cover). Uncover it and it warms up again.";
            case COUNTDOWN -> "§4failing §7- the terminal at " + where + "§7 ran out of fuel. It burns out in " + jammer.seconds + "s.";
        };
    }

    // -------------------------------------------------------------------- list

    /**
     * {@code /radio jammer list}: for each jammer within {@code jammer.list-range} blocks (admins see every
     * jammer), the frequencies it is jamming with the terminals and Pocket Receivers affected. Receivers are
     * listed without their holders' names and only to the nearest ten blocks. Encrypted frequencies are not named
     * to anyone but admins.
     */
    public void list(CommandSender sender) {
        list(sender, Bukkit.getOnlinePlayers());
    }

    void list(CommandSender sender, java.util.Collection<? extends Player> players) {
        boolean admin = sender.hasPermission("radionetwork.admin");
        Location here = sender instanceof Player player ? player.getLocation() : null;
        double range = plugin.getConfig().getDouble("jammer.list-range", 10.0);
        List<Jammer> shown = new ArrayList<>();
        for (Jammer jammer : jammers.values()) {
            Location at = jammer.center();
            boolean close = at != null && here != null && at.getWorld().equals(here.getWorld())
                    && at.distanceSquared(here) <= range * range;
            if (admin || close) {
                shown.add(jammer);
            }
        }
        if (shown.isEmpty()) {
            Chat.send(sender, "§cStand within " + (int) range + " blocks of a Signal Jammer to see what it is jamming.");
            return;
        }
        for (Jammer jammer : shown) {
            describe(sender, jammer, admin, players);
        }
    }

    private void describe(CommandSender sender, Jammer jammer, boolean admin, java.util.Collection<? extends Player> players) {
        Chat.send(sender, "§8--- Jammer at " + jammer.x + " " + jammer.y + " " + jammer.z + " ---");
        Chat.send(sender, statusLine(jammer));
        Map<String, List<String>> terminals = new TreeMap<>();
        Map<String, List<String>> receivers = new TreeMap<>();
        Map<String, Integer> hiddenTerminals = new HashMap<>();
        Map<String, Integer> hiddenReceivers = new HashMap<>();
        for (Radio radio : manager.all()) {
            Location center = radio.center();
            int loss = center == null ? 0 : lossFrom(jammer, center);
            if (!radio.isTuned() || loss <= 0) {
                continue;
            }
            String shownName = nameOf(radio.channel(), admin);
            if (shownName == null) {
                hiddenTerminals.merge("Encrypted frequency", 1, Integer::sum);
                continue;
            }
            terminals.computeIfAbsent(shownName, k -> new ArrayList<>())
                    .add(radio.x() + " " + radio.y() + " " + radio.z() + " §8(-" + loss + " bar" + (loss == 1 ? "" : "s") + ")");
        }
        for (Player player : players) {
            ItemStack receiver = portables.activeReceiver(player);
            String channel = receiver == null ? null : items.receiverChannel(receiver);
            if (channel == null || channel.isEmpty()) {
                continue;
            }
            Location where = player.getLocation();
            int loss = lossFrom(jammer, where);
            if (loss <= 0) {
                continue;
            }
            String shownName = nameOf(channel, admin);
            if (shownName == null) {
                hiddenReceivers.merge("Encrypted frequency", 1, Integer::sum);
                continue;
            }
            receivers.computeIfAbsent(shownName, k -> new ArrayList<>())
                    .add("around " + Math.round(where.getX() / 10.0) * 10 + " " + Math.round(where.getZ() / 10.0) * 10
                            + " §8(-" + loss + " bar" + (loss == 1 ? "" : "s") + ")");
        }
        java.util.Set<String> names = new java.util.TreeSet<>();
        names.addAll(terminals.keySet());
        names.addAll(receivers.keySet());
        if (names.isEmpty() && hiddenTerminals.isEmpty() && hiddenReceivers.isEmpty()) {
            Chat.send(sender, "§7Nothing is being jammed right now.");
            return;
        }
        for (String name : names) {
            Chat.send(sender, "§7Frequency: §f" + name);
            Chat.send(sender, "§7  Terminals:");
            List<String> t = terminals.getOrDefault(name, List.of());
            if (t.isEmpty()) {
                Chat.send(sender, "§8    none");
            }
            t.forEach(line -> Chat.send(sender, "§f    - " + line));
            Chat.send(sender, "§7  Portable radios:");
            List<String> r = receivers.getOrDefault(name, List.of());
            if (r.isEmpty()) {
                Chat.send(sender, "§8    none");
            }
            r.forEach(line -> Chat.send(sender, "§f    - " + line));
        }
        if (!hiddenTerminals.isEmpty() || !hiddenReceivers.isEmpty()) {
            Chat.send(sender, "§eEncrypted frequencies §7(names and places hidden): §f"
                    + hiddenTerminals.getOrDefault("Encrypted frequency", 0) + " terminal(s), "
                    + hiddenReceivers.getOrDefault("Encrypted frequency", 0) + " portable radio(s)");
        }
    }

    /** The frequency name to show, or null when it is private and the viewer is not an admin. */
    private @Nullable String nameOf(String channel, boolean admin) {
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency != null && frequency.access() != Frequency.Access.PUBLIC && !admin) {
            return null;
        }
        return channel;
    }

    // ------------------------------------------------------------- persistence

    public void load() {
        jammers.clear();
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (Map<?, ?> raw : yaml.getMapList("jammers")) {
                try {
                    Jammer jammer = new Jammer(String.valueOf(raw.get("world")), ((Number) raw.get("x")).intValue(),
                            ((Number) raw.get("y")).intValue(), ((Number) raw.get("z")).intValue(),
                            String.valueOf(raw.get("owner")));
                    if (raw.get("terminal") != null && manager.get(String.valueOf(raw.get("terminal"))) != null) {
                        jammer.terminalKey = String.valueOf(raw.get("terminal"));
                    }
                    jammers.put(jammer.key(), jammer);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Skipping malformed jammer entry: " + raw, e);
                }
            }
        }
        rebuildIndex();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Jammer jammer : jammers.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", jammer.world);
            entry.put("x", jammer.x);
            entry.put("y", jammer.y);
            entry.put("z", jammer.z);
            entry.put("owner", jammer.owner);
            if (jammer.terminalKey != null) {
                entry.put("terminal", jammer.terminalKey);
            }
            list.add(entry);
        }
        yaml.set("jammers", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save jammers.yml", e);
        }
    }
}
