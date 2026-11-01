package dev.radionetwork;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Owns every registered radio and the routing rules shared by text and voice, so both transports
 * follow exactly the same distance, dimension, privacy and power rules. Main-thread only.
 */
public final class RadioManager {

    /** Both the terminal-use range and the listening range around receiving towers. */
    public static final double LISTEN_RADIUS = 5.0;
    public static final int DEFAULT_RADIUS = 250;
    public static final int MAX_CHANNEL_LENGTH = 24; // Simple Voice Chat group-name limit

    public enum Denial { NONE, NO_RADIO, NOT_TUNED, NO_ACCESS, ENCRYPTED, CLOSED, NO_POWER, JAMMED }

    public record Transmitter(@Nullable Radio radio, Denial denial) {
    }

    private final RadioNetworkPlugin plugin;
    private final File file;
    private final Map<String, Radio> radios = new LinkedHashMap<>();
    private final FrequencyRegistry frequencies;
    private final PasswordGuard passwordGuard = new PasswordGuard();
    private boolean dirty;

    public RadioManager(RadioNetworkPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "radios.yml");
        this.frequencies = new FrequencyRegistry(new File(plugin.getDataFolder(), "frequencies.yml"), plugin.getLogger());
    }

    public FrequencyRegistry frequencies() {
        return frequencies;
    }

    /** Guess limiter shared by every password prompt (terminals and receivers). */
    public PasswordGuard passwordGuard() {
        return passwordGuard;
    }

    public static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    // ---------------------------------------------------------------- registry

    public Radio register(Block block, String creatorName) {
        Radio radio = new Radio(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                creatorName, "");
        radios.put(radio.key(), radio);
        dirty = true;
        return radio;
    }

    public @Nullable Radio get(Block block) {
        return radios.get(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public @Nullable Radio get(String key) {
        return radios.get(key);
    }

    public void unregister(Radio radio) {
        radios.remove(radio.key());
        removeLight(radio);
        dirty = true;
        if (music != null) {
            music.unlinkTerminal(radio.key()); // jukeboxes linked to it lose the link and go quiet
        }
        if (repeaters != null) {
            repeaters.unlinkTerminal(radio.key()); // its repeater stays in the world but relays nothing
        }
        if (jammers != null) {
            jammers.unlinkTerminal(radio.key()); // its jammer stays in the world, inert
        }
    }

    private MusicBoxes music;

    public void music(MusicBoxes music) {
        this.music = music;
    }

    public @Nullable MusicBoxes music() {
        return music;
    }

    public Collection<Radio> all() {
        return radios.values();
    }

    public void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------- persistence

    public void load() {
        radios.clear();
        frequencies.load();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Radios saved before frequencies had owners carried their own mode; keep it for migration.
        Map<String, RadioMode> legacyModes = new LinkedHashMap<>();
        for (Map<?, ?> raw : yaml.getMapList("radios")) {
            try {
                Radio radio = new Radio(
                        String.valueOf(raw.get("world")),
                        ((Number) raw.get("x")).intValue(),
                        ((Number) raw.get("y")).intValue(),
                        ((Number) raw.get("z")).intValue(),
                        String.valueOf(raw.get("creator")),
                        String.valueOf(raw.get("channel")));
                if (raw.get("mode") != null) {
                    legacyModes.put(radio.key(), RadioMode.valueOf(String.valueOf(raw.get("mode"))));
                }
                radio.customRadius(((Number) raw.get("radius")).intValue());
                radio.fuelLevel(((Number) raw.get("fuel")).longValue());
                radio.activeFuel(FuelType.valueOf(String.valueOf(raw.get("fuel-type"))));
                if (!radio.isPowered()) {
                    radio.activeFuel(FuelType.NONE);
                }
                clampRadius(radio); // radii saved under older, larger caps come down to the current ones
                radios.put(radio.key(), radio);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping malformed radio entry: " + raw, e);
            }
        }
        migrateLegacyChannels(legacyModes);
        dirty = false;
    }

    /** The first radio on each previously unowned channel makes its creator that frequency's owner. */
    private void migrateLegacyChannels(Map<String, RadioMode> legacyModes) {
        for (Radio radio : radios.values()) {
            // Only radios saved in the old format (they carry a mode). A radio whose frequency is simply
            // missing now (released, abandoned, or unreadable) must stay offline, never become public.
            if (legacyModes.containsKey(radio.key()) && radio.isTuned() && frequencies.get(radio.channel()) == null) {
                Frequency frequency = frequencies.claim(radio.channel(), radio.creatorName());
                frequency.speech(legacyModes.getOrDefault(radio.key(), RadioMode.OPEN));
                dirty = true;
            }
        }
    }

    public void save() {
        frequencies.save();
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Radio radio : radios.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", radio.world());
            entry.put("x", radio.x());
            entry.put("y", radio.y());
            entry.put("z", radio.z());
            entry.put("creator", radio.creatorName());
            entry.put("channel", radio.channel());
            entry.put("radius", radio.customRadius());
            entry.put("fuel", radio.fuelLevel());
            entry.put("fuel-type", radio.activeFuel().name());
            entry.put("active", radio.isActive());
            list.add(entry);
        }
        yaml.set("radios", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save radios.yml", e);
        }
    }

    public void saveIfDirty() {
        if (dirty || frequencies.isDirty()) {
            save();
        }
    }

    /** Drops registrations whose Lodestone no longer exists (e.g. removed by another plugin). */
    public void pruneMissingBlocks() {
        List<Radio> gone = new ArrayList<>();
        for (Radio radio : radios.values()) {
            World world = radio.bukkitWorld();
            if (world == null || !world.isChunkLoaded(radio.x() >> 4, radio.z() >> 4)) {
                continue;
            }
            if (world.getBlockAt(radio.x(), radio.y(), radio.z()).getType() != Material.LODESTONE) {
                gone.add(radio);
            }
        }
        for (Radio radio : gone) {
            announceTunedOut(radio, radio.creatorName(), true);
            unregister(radio);
        }
    }

    // -------------------------------------------------------------- fuel/power

    /** Inserts fuel; the newly added type becomes the active type and the radius is re-capped. */
    public void addFuel(Radio radio, FuelType type, long seconds) {
        if (type == FuelType.NONE || seconds <= 0) {
            return;
        }
        radio.fuelLevel(radio.fuelLevel() + seconds);
        radio.activeFuel(type);
        clampRadius(radio);
        updateLight(radio);
        dirty = true;
    }

    /** Burns fuel. */
    public void consume(Radio radio, long seconds) {
        boolean wasPowered = radio.isPowered();
        radio.fuelLevel(radio.fuelLevel() - seconds);
        if (wasPowered && !radio.isPowered()) {
            updateLight(radio);
        }
        dirty = true;
    }

    /** Lowers CustomRadius when the active fuel cannot support it. */
    public void clampRadius(Radio radio) {
        int cap = radio.activeFuel().maxRadius();
        if (radio.customRadius() > cap) {
            radio.customRadius(cap);
            dirty = true;
        }
    }

    /** Glowstone-powered radios emit light from the air block above them. */
    public void updateLight(Radio radio) {
        World world = radio.bukkitWorld();
        if (world == null || !world.isChunkLoaded(radio.x() >> 4, radio.z() >> 4)) {
            return;
        }
        Block above = world.getBlockAt(radio.x(), radio.y() + 1, radio.z());
        boolean wantLight = radio.isPowered() && radio.activeFuel() == FuelType.GLOWSTONE;
        if (wantLight && above.getType().isAir()) {
            above.setType(Material.LIGHT, false);
            if (above.getBlockData() instanceof Levelled levelled) {
                levelled.setLevel(12);
                above.setBlockData(levelled, false);
            }
        } else if (!wantLight && above.getType() == Material.LIGHT) {
            above.setType(Material.AIR, false);
        }
    }

    private void removeLight(Radio radio) {
        World world = radio.bukkitWorld();
        if (world != null && world.isChunkLoaded(radio.x() >> 4, radio.z() >> 4)) {
            Block above = world.getBlockAt(radio.x(), radio.y() + 1, radio.z());
            if (above.getType() == Material.LIGHT) {
                above.setType(Material.AIR, false);
            }
        }
    }

    // ----------------------------------------------------------------- routing

    public List<Radio> radiosNear(Location location) {
        List<Radio> near = new ArrayList<>();
        double limit = LISTEN_RADIUS * LISTEN_RADIUS;
        for (Radio radio : radios.values()) {
            Location center = radio.center();
            if (center != null && center.getWorld().equals(location.getWorld())
                    && center.distanceSquared(location) <= limit) {
                near.add(radio);
            }
        }
        near.sort(Comparator.comparingDouble(r -> r.center().distanceSquared(location)));
        return near;
    }

    /** Frequency this radio is tuned to, or null when untuned or the frequency has been released. */
    public Frequency frequencyOf(Radio radio) {
        return radio.isTuned() ? frequencies.get(radio.channel()) : null;
    }

    /** A radio takes part in the network only while its frequency exists and its operator may use it. */
    public boolean isLive(Radio radio) {
        Frequency frequency = frequencyOf(radio);
        return frequency != null && frequency.hasAccess(radio.creatorName());
    }

    private Denial check(Radio radio, Player player) {
        Frequency frequency = frequencyOf(radio);
        if (frequency == null) {
            return Denial.NOT_TUNED;
        }
        if (!frequency.hasAccess(radio.creatorName())) {
            return Denial.NO_ACCESS;
        }
        if (!frequency.hasAccess(player.getName())) {
            return Denial.ENCRYPTED;
        }
        if (!frequency.canSpeak(player.getName())) {
            return Denial.CLOSED;
        }
        if (!radio.isPowered()) {
            return Denial.NO_POWER;
        }
        return senderJam(radio) >= 4 ? Denial.JAMMED : Denial.NONE;
    }

    /**
     * Picks the terminal a player may transmit through: within {@link #LISTEN_RADIUS}, tuned to a live
     * frequency, allowed by that frequency's access and speaking rules, and powered. When none
     * qualifies the denial describes why, judged by the nearest terminal.
     */
    public Transmitter findTransmitter(Player player) {
        List<Radio> near = radiosNear(player.getLocation());
        if (near.isEmpty()) {
            return new Transmitter(null, Denial.NO_RADIO);
        }
        for (Radio radio : near) {
            if (check(radio, player) == Denial.NONE) {
                return new Transmitter(radio, Denial.NONE);
            }
        }
        return new Transmitter(null, check(near.getFirst(), player));
    }

    // ------------------------------------------------------------------- relays

    // ------------------------------------------------------------------ jamming

    private Jammers jammers;

    public void jammers(Jammers jammers) {
        this.jammers = jammers;
    }

    public @Nullable Jammers jammers() {
        return jammers;
    }

    /** Bars lost to jamming where someone stands (0-4); only the strongest jammer counts. */
    public int jamLoss(@Nullable Location where) {
        return jammers == null ? 0 : jammers.lossAt(where);
    }

    /** Bars lost to jamming at a terminal: they come off every listener's signal, and 4 means it cannot transmit. */
    public int senderJam(Radio sender) {
        return jamLoss(sender.center());
    }

    /** How many relays in a row a signal may pass through ({@code relay.max-hops}); 0 turns relaying off. */
    public int maxRelays() {
        return Math.max(0, plugin.getConfig().getInt("relay.max-hops", 3));
    }

    private Repeaters repeaters;

    public void repeaters(Repeaters repeaters) {
        this.repeaters = repeaters;
    }

    public @Nullable Repeaters repeaters() {
        return repeaters;
    }

    /**
     * A terminal relays when it has a Signal Repeater linked and is working: powered, tuned to a frequency its
     * operator may use, with its chunk loaded. A relay passes on what it hears using its own radius.
     */
    public boolean isRelaying(Radio terminal) {
        if (repeaters == null || maxRelays() <= 0 || repeaters.forTerminal(terminal) == null
                || !terminal.isPowered() || !isLive(terminal)) {
            return false;
        }
        World world = terminal.bukkitWorld();
        return world != null && world.isChunkLoaded(terminal.x() >> 4, terminal.z() >> 4);
    }

    /** Every tower that hears a sender, and through how many relays each of them hears it (0 = directly). */
    public record Reach(List<Radio> towers, Map<Radio, Integer> relays) {
    }

    /**
     * Works out who hears {@code sender}. The sender reaches every powered tower on the same channel inside its
     * own CustomRadius (flat distance); other dimensions only count when the sender runs on an Ender Pearl. Any
     * tower reached that is a relay then passes the signal on the same way, using its own radius and fuel, up to
     * {@code relay.max-hops} relays in a row. Each radio is reached at most once (by the fewest relays), so a
     * ring of relays cannot loop. The sender itself is always the first tower of the result.
     */
    public Reach reachOf(Radio sender) {
        return reachFrom(sender, false);
    }

    /**
     * {@link #reachOf}, optionally ignoring the jamming at the sender: a jammer's own terminal sits in the middle
     * of its zone and cannot transmit, but the jam still has to be passed on through its relays, so the jammers
     * work out where their zones reach this way. (Jamming never switches a relay off; it only weakens listeners.)
     */
    public Reach reachFrom(Radio sender, boolean ignoreJam) {
        List<Radio> towers = new ArrayList<>();
        Map<Radio, Integer> relays = new java.util.IdentityHashMap<>();
        if (!sender.isPowered() || !isLive(sender) || (!ignoreJam && senderJam(sender) >= 4)) {
            return new Reach(towers, relays);
        }
        List<Radio> candidates = new ArrayList<>();
        for (Radio other : radios.values()) {
            if (other != sender && other.isPowered() && other.channel().equals(sender.channel())
                    && isLive(other) && other.bukkitWorld() != null) {
                candidates.add(other);
            }
        }
        towers.add(sender);
        relays.put(sender, 0);
        int cap = maxRelays();
        java.util.ArrayDeque<Radio> queue = new java.util.ArrayDeque<>();
        queue.add(sender);
        while (!queue.isEmpty()) {
            Radio from = queue.poll();
            int used = relays.get(from);
            // The sender always transmits; anyone else only passes it on while they relay and the cap allows.
            if (from != sender && (used >= cap || !isRelaying(from))) {
                continue;
            }
            int next = from == sender ? 0 : used + 1;
            boolean crossDimension = from.activeFuel().crossDimension();
            double radius = effectiveRadius(from);
            for (Radio other : candidates) {
                if (relays.containsKey(other) || (!crossDimension && !other.world().equals(from.world()))) {
                    continue;
                }
                double dx = other.x() - from.x();
                double dz = other.z() - from.z();
                if (dx * dx + dz * dz <= radius * radius) {
                    relays.put(other, next);
                    towers.add(other);
                    queue.add(other);
                }
            }
        }
        return new Reach(towers, relays);
    }

    /** Every powered tower that hears {@code sender}, directly or through relays. */
    public List<Radio> receiversOf(Radio sender) {
        return reachOf(sender).towers();
    }

    /**
     * The signal of each listener standing at a tower, but only for those whose signal is below a clean four
     * bars: each relay the signal passed through costs one bar, and jamming (at the sender and where the
     * listener stands) costs more. Four bars is clean, which turns into static the way a weak Pocket Receiver
     * does. Listeners left with no bars at all are not listed here: they do not hear it.
     */
    public Map<java.util.UUID, Integer> relayBars(Radio sender) {
        Map<java.util.UUID, Integer> bars = new java.util.HashMap<>();
        towerBars(sender, reachOf(sender)).forEach((player, strength) -> {
            if (strength < 4) {
                bars.put(player.getUniqueId(), strength);
            }
        });
        return bars;
    }

    /** A spark on every working repeater that is passing this signal on; call it when a signal is sent. */
    public void pulseRelays(Radio sender) {
        if (repeaters == null) {
            return;
        }
        Reach reach = reachOf(sender);
        int cap = maxRelays();
        for (Radio tower : reach.towers()) {
            if (tower != sender && reach.relays().get(tower) < cap && isRelaying(tower)) {
                Repeaters.Repeater repeater = repeaters.forTerminal(tower);
                if (repeater != null) {
                    repeaters.spark(repeater);
                }
            }
        }
    }

    /**
     * Players standing within {@link #LISTEN_RADIUS} of any tower that hears {@code sender}. Listening
     * is by proximity only: on an ENCRYPTED frequency a passer-by next to a live radio still overhears the
     * conversation, while speaking and tuning in remain locked to people with access.
     */
    public Set<Player> listenersOf(Radio sender) {
        Reach reach = reachOf(sender);
        Set<Player> listeners = new LinkedHashSet<>(towerBars(sender, reach).keySet());
        if (portables != null) {
            listeners.addAll(portables.listenersFor(sender, reach.towers(), reach.relays()));
        }
        return listeners;
    }

    /**
     * The signal (1-4 bars) of everyone who hears this sender: people at a tower get four, less a bar per relay
     * and for jamming; Pocket Receiver holders get what distance, depth and jamming leave them. Someone who is
     * both gets the better of the two. Anyone missing from the map is not listening.
     */
    public Map<java.util.UUID, Integer> signalOf(Radio sender) {
        Reach reach = reachOf(sender);
        Map<java.util.UUID, Integer> signal = new java.util.HashMap<>();
        towerBars(sender, reach).forEach((player, bars) -> signal.put(player.getUniqueId(), bars));
        if (portables != null) {
            portables.signalFor(sender, reach.towers(), reach.relays()).forEach((id, bars) -> signal.merge(id, bars, Math::max));
        }
        return signal;
    }

    /** Radio text as a listener with this signal receives it: garbled more the weaker the signal (4 bars is clean). */
    public String garble(String text, int bars) {
        double share = Garble.share(bars, plugin.getConfig().getDouble("garble.max-share", 0.5));
        return Garble.apply(text, share, java.util.concurrent.ThreadLocalRandom.current());
    }

    /** Signal (1-4 bars) of each listener who hears this channel through a Pocket Receiver; empty if none. */
    public Map<java.util.UUID, Integer> portableBars(Radio sender) {
        if (portables == null) {
            return Map.of();
        }
        Reach reach = reachOf(sender);
        return portables.signalFor(sender, reach.towers(), reach.relays());
    }

    private Weather weather;

    public void weather(Weather weather) {
        this.weather = weather;
    }

    public @Nullable Weather weather() {
        return weather;
    }

    /**
     * How far a terminal reaches right now: its radius, and at night {@code weather.night-range-bonus} more. The bonus
     * goes beyond what its fuel allows (most terminals sit at that limit, so a capped bonus would be no bonus), unless
     * {@code weather.night-respects-fuel-cap} is on. (A Pocket Receiver's own range limit still applies on top.)
     */
    public double effectiveRadius(Radio radio) {
        double base = radio.customRadius();
        if (weather == null || !weather.isNight(radio.bukkitWorld())) {
            return base;
        }
        double boosted = Math.round(base * (1.0 + weather.nightBonus()));
        if (plugin.getConfig().getBoolean("weather.night-respects-fuel-cap", false)) {
            boosted = Math.min(boosted, radio.activeFuel().maxRadius());
        }
        return Math.max(base, boosted);
    }

    private Scanners scanners;

    public void scanners(Scanners scanners) {
        this.scanners = scanners;
    }

    public @Nullable Scanners scanners() {
        return scanners;
    }

    private PortableReceivers portables;

    public void portables(PortableReceivers portables) {
        this.portables = portables;
    }

    public @Nullable PortableReceivers portables() {
        return portables;
    }

    /** Only players standing at a tower (what the voice relay targets; portables are text only). */
    public Set<Player> towerListenersOf(Radio sender) {
        return new LinkedHashSet<>(towerBars(sender, reachOf(sender)).keySet());
    }

    /**
     * Whether someone standing at {@code player} is close enough to a tower to hear it: within {@link #LISTEN_RADIUS}
     * sideways, and within {@code listening.vertical-range} blocks up or down from the middle of the tower's block.
     * Height is judged on its own, so being a floor below the terminal does not shrink how far to the side you can
     * stand (a straight 3D distance from the player's feet did exactly that).
     */
    public boolean isAtTower(Location player, Location center) {
        double dx = player.getX() - center.getX();
        double dz = player.getZ() - center.getZ();
        double vertical = Math.max(LISTEN_RADIUS, plugin.getConfig().getDouble("listening.vertical-range", 6.0));
        return dx * dx + dz * dz <= LISTEN_RADIUS * LISTEN_RADIUS && Math.abs(player.getY() - center.getY()) <= vertical;
    }

    /**
     * Players at a tower of this reach with the signal each gets (1-4 bars): four, less one per relay the
     * signal passed through, less the jamming at the sender and where they stand; the best tower they stand
     * at counts. Anyone left with no bars hears nothing and is not listed.
     */
    private Map<Player, Integer> towerBars(Radio sender, Reach reach) {
        Map<Player, Integer> listeners = new LinkedHashMap<>();
        int senderLoss = senderJam(sender);
        for (Radio receiver : reach.towers()) {
            Location center = receiver.center();
            if (center == null) {
                continue;
            }
            int used = reach.relays().get(receiver);
            int base = (used > 0 ? Math.max(1, 4 - used) : 4) - senderLoss;
            for (Player player : center.getWorld().getPlayers()) {
                if (isAtTower(player.getLocation(), center)) {
                    int strength = base - jamLoss(player.getLocation());
                    if (strength >= 1 && weather != null) {
                        strength = weather.apply(strength, weather.lossAt(player.getLocation(), false));
                    }
                    if (strength >= 1) {
                        listeners.merge(player, strength, Math::max);
                    }
                }
            }
        }
        return listeners;
    }

    // ------------------------------------------------------------ time-based drain

    /**
     * Radios burn fuel with time: every call (once a second) a powered radio that is tuned to a
     * frequency it may use loses {@code fuel.drain-per-second} seconds. Untuned or locked-out radios
     * and radios in unloaded chunks do not burn, so only a working radio costs fuel.
     */
    public void drainFuel() {
        long drain = Math.max(0, plugin.getConfig().getLong("fuel.drain-per-second", 1));
        double relayDrain = plugin.getConfig().getDouble("relay.extra-drain-per-second", 0.5);
        for (Radio radio : radios.values()) {
            if (!radio.isPowered() || !isLive(radio)) {
                continue;
            }
            World world = radio.bukkitWorld();
            if (world == null || !world.isChunkLoaded(radio.x() >> 4, radio.z() >> 4)) {
                continue;
            }
            long amount = drain;
            // A relay burns a little extra for as long as it is passing signals on (the rate can be fractional).
            if (relayDrain > 0 && isRelaying(radio)) {
                radio.relayDrainRemainder += relayDrain;
                long whole = (long) Math.floor(radio.relayDrainRemainder);
                radio.relayDrainRemainder -= whole;
                amount += whole;
            }
            if (amount > 0) {
                consume(radio, amount);
            }
        }
    }

    // ----------------------------------------------------------- text cooldown

    private final Map<java.util.UUID, Long> lastBroadcast = new java.util.HashMap<>();

    /** Milliseconds until this player may /broadcast again (0 when they can). */
    public long broadcastCooldownLeft(java.util.UUID player) {
        long cooldown = (long) (plugin.getConfig().getDouble("broadcast.cooldown-seconds", 3.0) * 1000);
        Long last = lastBroadcast.get(player);
        return last == null ? 0 : Math.max(0, cooldown - (System.currentTimeMillis() - last));
    }

    public void markBroadcast(java.util.UUID player) {
        lastBroadcast.put(player, System.currentTimeMillis());
    }

    public void forgetBroadcaster(java.util.UUID player) {
        lastBroadcast.remove(player);
    }

    // ---------------------------------------------------------- announcements

    /**
     * A free system announcement on {@code channel}, sent through the nearest powered radio on that
     * channel within {@code announcements.search-radius} blocks of the given point, so it follows the
     * same range, dimension and power rules as a player message. Used by {@code /radio announce}.
     *
     * @return true when a radio carried it
     */
    public boolean announce(World world, double x, double y, double z, String channel, String text) {
        double search = plugin.getConfig().getDouble("announcements.search-radius", 16.0);
        Location point = new Location(world, x, y, z);
        Radio sender = null;
        double best = Double.MAX_VALUE;
        for (Radio radio : radios.values()) {
            Location center = radio.center();
            if (center == null || !center.getWorld().equals(world) || !radio.channel().equals(channel)
                    || !radio.isPowered() || !isLive(radio)) {
                continue;
            }
            double distance = center.distanceSquared(point);
            if (distance <= search * search && distance < best) {
                best = distance;
                sender = radio;
            }
        }
        if (sender == null) {
            return false;
        }
        Map<java.util.UUID, Integer> signal = signalOf(sender);
        Set<Player> listeners = listenersOf(sender);
        for (Player listener : listeners) {
            String heard = garble(text, signal.getOrDefault(listener.getUniqueId(), 4));
            Chat.send(listener, "§b§l[RADIO - " + channel + "] §f" + heard);
            listener.playSound(listener.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.0f);
        }
        if (scanners != null) {
            scanners.relayText(sender, null, text, listeners);
        }
        pulseRelays(sender);
        return true;
    }

    /**
     * Tells everyone who currently hears this radio's channel that it is leaving. Call it while the
     * radio is still registered and tuned, before changing or removing it; like the tune-in notice
     * it reaches the same listeners that a message from this radio would.
     */
    public void announceTunedOut(Radio radio, String who, boolean destroyed) {
        if (!radio.isTuned()) {
            return;
        }
        String channel = radio.channel();
        String line = "§e§l[RADIO - " + channel + "] §7" + who + "'s radio "
                + (destroyed ? "was destroyed and has" : "has") + " tuned out of " + channel + " frequency.";
        listenersOf(radio).forEach(listener -> Chat.send(listener, line));
    }

    public static boolean isOperator(Player player, Radio radio) {
        return radio.creatorName().equals(player.getName()) || player.hasPermission("radionetwork.admin");
    }

    public static String cleanChannel(String input) {
        String cleaned = Chat.clean(input.replace("|", ""));
        if (cleaned.length() > MAX_CHANNEL_LENGTH) {
            cleaned = cleaned.substring(0, MAX_CHANNEL_LENGTH).trim();
        }
        return cleaned;
    }

}
