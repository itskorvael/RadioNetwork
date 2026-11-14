package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Listen-only Pocket Receivers. A holder hears a channel when:
 * <ul>
 *   <li>the receiver is held in their main hand or off hand (not just carried) and tuned to that channel;</li>
 *   <li>they may use the channel (public, or owner/trusted/joined with the password);</li>
 *   <li>and a powered tower on the channel in the same world gives them at least one signal bar.</li>
 * </ul>
 * Signal bars (1-4) come from distance: a tower's reach is the smaller of its own radius and the receiver
 * range ({@code portable.range}), and the closer you are within it the more bars. Rock above you then
 * takes bars away (see {@link #depthLoss}); an installed Underground Antenna ignores that loss but only
 * reaches a fraction of the distance. A holder with an Earpiece hears privately; without one the receiver
 * plays on a speaker and players within {@code portable.speaker-radius} of the holder hear it too, so text and
 * voice reach exactly the same people. With auto-scan on, a receiver that loses its chosen channel hops to
 * the nearest public channel in range and returns once the chosen one is clearly back. Voice is relayed to holders the same way it is to terminals.
 */
public final class PortableReceivers {

    public enum State { OFF, SCANNING, NO_SIGNAL, LOCKED, TOO_DEEP, JAMMED, OK }

    /** The receiver's signal: state plus 1-4 signal bars when OK; {@code auto} marks an auto-scan channel. */
    public record Signal(@Nullable String channel, State state, int bars, boolean auto) {
        Signal(@Nullable String channel, State state, int bars) {
            this(channel, state, bars, false);
        }
    }

    /** One public channel found by a scan, with its best signal. */
    public record ScanResult(String channel, int towers, int nearestBlocks, String direction, int bars) {

        /** A rough idea of how far the nearest terminal is, not a measurement. */
        public String range() {
            return nearestBlocks <= 32 ? "close" : nearestBlocks <= 96 ? "some way off" : "far";
        }
    }

    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;

    public PortableReceivers(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
    }

    /** The weather, if the plugin has it switched on (for the receiver screen). */
    public @Nullable Weather weather() {
        return manager.weather();
    }

    public boolean autoScanDefault() {
        return plugin.getConfig().getBoolean("portable.auto-scan-default", true);
    }

    public double range() {
        return plugin.getConfig().getDouble("portable.range", 300.0);
    }

    // ---------------------------------------------------------------- receivers

    /** The receiver in the main hand, then the off hand, or null. */
    public @Nullable ItemStack heldReceiver(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (items.isReceiver(inventory.getItemInMainHand())) {
            return inventory.getItemInMainHand();
        }
        return items.isReceiver(inventory.getItemInOffHand()) ? inventory.getItemInOffHand() : null;
    }

    /** The receiver that listens for this player: only one held in the main hand or off hand works. */
    public @Nullable ItemStack activeReceiver(Player player) {
        return heldReceiver(player);
    }

    // ------------------------------------------------------------ signal model

    /** A tower's reach for this receiver; an installed antenna trades range for depth. */
    private double reach(Radio tower, boolean antenna) {
        double reach = Math.min(manager.effectiveRadius(tower), range());
        return antenna ? reach * plugin.getConfig().getDouble("portable.antenna-range-multiplier", 0.5) : reach;
    }

    private static double flatDistanceSquared(Location player, Radio tower) {
        double dx = tower.x() + 0.5 - player.getX();
        double dz = tower.z() + 0.5 - player.getZ();
        return dx * dx + dz * dz;
    }

    /** Bars from distance alone: 0 when out of reach (or in another world), otherwise 1-4. */
    private int distanceBars(Location player, Radio tower, boolean antenna) {
        double reach = reach(tower, antenna);
        if (player.getWorld() == null || !player.getWorld().getName().equals(tower.world()) || reach <= 0) {
            return 0;
        }
        double distance = Math.sqrt(flatDistanceSquared(player, tower));
        if (distance > reach) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil((1 - distance / reach) * 4));
    }

    /**
     * Bars lost to rock above the player. Depth is measured below the highest block overhead; the first
     * {@code portable.free-depth} blocks cost nothing (roofs, trees), then each {@code portable.depth-per-bar}
     * blocks costs a bar. Dimensions without a sky, and a disabled penalty, lose nothing.
     */
    public int depthLoss(Location where) {
        if (!plugin.getConfig().getBoolean("portable.depth-penalty", true)) {
            return 0;
        }
        World world = where.getWorld();
        if (world == null || !world.hasSkyLight()) {
            return 0;
        }
        int depth = world.getHighestBlockYAt(where, HeightMap.MOTION_BLOCKING) - where.getBlockY();
        int free = plugin.getConfig().getInt("portable.free-depth", 4);
        if (depth <= free) {
            return 0;
        }
        int perBar = Math.max(1, plugin.getConfig().getInt("portable.depth-per-bar", 12));
        return (depth - free + perBar - 1) / perBar;
    }

    private int effectiveBars(Location player, Radio tower, boolean antenna, int loss) {
        int bars = Math.max(0, effectiveBarsUnjammed(player, tower, antenna, loss) - manager.jamLoss(player));
        Weather weather = manager.weather();
        // Weather only weakens a signal that is still there; an Underground Antenna is shielded from it.
        return bars >= 1 && weather != null ? weather.apply(bars, weather.lossAt(player, antenna)) : bars;
    }

    /** The same, before jamming: what distance and depth alone leave. */
    private int effectiveBarsUnjammed(Location player, Radio tower, boolean antenna, int loss) {
        int raw = distanceBars(player, tower, antenna);
        return raw == 0 ? 0 : Math.max(0, raw - (antenna ? 0 : loss));
    }

    // ------------------------------------------------------------------ hearing

    /** Who hears a transmission through a Pocket Receiver, and how strongly (1-4 bars) each of them gets it. */
    public record Hearing(Set<Player> players, Map<UUID, Integer> bars) {
    }

    /**
     * Holders among {@code candidates} who hear a message sent by {@code sender}: tuned to its channel
     * and getting at least one bar from one of the towers that carry it.
     */
    public Set<Player> listenersFor(Collection<? extends Player> candidates, Radio sender, List<Radio> towers) {
        return hearing(candidates, sender, towers, Map.of()).players();
    }

    /**
     * The same, with each listener's signal. People who only hear because a holder's receiver is on a
     * loudspeaker share that holder's signal (the weakest one, if several holders are near them).
     */
    public Hearing hearing(Collection<? extends Player> candidates, Radio sender, List<Radio> towers,
                           Map<Radio, Integer> relays) {
        Set<Player> hearing = new LinkedHashSet<>();
        Map<UUID, Integer> bars = new HashMap<>();
        Frequency frequency = manager.frequencyOf(sender);
        if (frequency == null || towers.isEmpty()) {
            return new Hearing(hearing, bars);
        }
        for (Player player : candidates) {
            ItemStack receiver = activeReceiver(player);
            if (receiver == null || !sender.channel().equals(items.receiverChannel(receiver))
                    || !frequency.hasAccess(player.getName())) {
                continue;
            }
            Location where = player.getLocation();
            boolean antenna = items.hasAntenna(receiver);
            int loss = antenna ? 0 : depthLoss(where);
            int best = 0;
            for (Radio tower : towers) {
                int towerBars = effectiveBars(where, tower, antenna, loss);
                if (towerBars > 0) {
                    // Relays cost relay.bars-per-hop each (none by default), but never silence a tower that is in reach.
                    towerBars = Math.max(1, towerBars - manager.relayLoss(relays.getOrDefault(tower, 0)));
                }
                best = Math.max(best, towerBars);
            }
            best -= manager.senderJam(sender); // a jammed sending terminal weakens it for everyone
            if (best < 1) {
                continue;
            }
            hearing.add(player);
            bars.merge(player.getUniqueId(), best, Math::min);
            // Without an Earpiece the receiver plays on a speaker: people close to the holder hear it too.
            if (!items.hasEarpiece(receiver)) {
                for (Player nearby : addNearby(hearing, candidates, player)) {
                    bars.merge(nearby.getUniqueId(), best, Math::min);
                }
            }
        }
        return new Hearing(hearing, bars);
    }

    /**
     * The signal a plain listener (no antenna) gets here from the towers that carry {@code sender}: what distance,
     * depth and jamming leave, {@code relay.bars-per-hop} less per relay (none by default), and less again if the sender is being jammed. 0 means nothing.
     */
    public int barsFrom(Location where, Radio sender, List<Radio> towers, Map<Radio, Integer> relays) {
        int loss = depthLoss(where);
        int best = 0;
        for (Radio tower : towers) {
            int bars = effectiveBars(where, tower, false, loss);
            if (bars > 0) {
                bars = Math.max(1, bars - manager.relayLoss(relays.getOrDefault(tower, 0)));
            }
            best = Math.max(best, bars);
        }
        return Math.max(0, best - manager.senderJam(sender));
    }

    /** The best signal a plain listener would get here on {@code channel}, whoever may use it: what a Scanner picks up. */
    public int channelBarsAny(Location where, String channel) {
        int loss = depthLoss(where);
        int best = 0;
        for (Radio radio : manager.all()) {
            if (radio.channel().equals(channel) && radio.isPowered() && manager.isLive(radio)) {
                best = Math.max(best, effectiveBars(where, radio, false, loss));
            }
        }
        return best;
    }

    /** Adds the candidates standing within the speaker radius of {@code holder} (same world); returns all of them. */
    private List<Player> addNearby(Set<Player> into, Collection<? extends Player> candidates, Player holder) {
        List<Player> near = new ArrayList<>();
        double radius = plugin.getConfig().getDouble("portable.speaker-radius", RadioManager.LISTEN_RADIUS);
        Location at = holder.getLocation();
        for (Player other : candidates) {
            if (other.equals(holder)) {
                continue;
            }
            Location there = other.getLocation();
            if (there.getWorld() != null && there.getWorld().equals(at.getWorld())
                    && there.distanceSquared(at) <= radius * radius) {
                into.add(other);
                near.add(other);
            }
        }
        return near;
    }

    /**
     * How much of a transmission is lost to static at this signal: nothing at 4 bars, rising evenly to
     * {@code portable.static-max-loss} at 1 bar (0 turns static off).
     */
    public float staticLoss(int bars) {
        double max = plugin.getConfig().getDouble("portable.static-max-loss", 0.4);
        if (max <= 0 || bars >= 4) {
            return 0f;
        }
        return (float) (max * (4 - Math.max(1, bars)) / 3.0);
    }

    /** Portable listeners for the online players; called by {@link RadioManager#listenersOf}. */
    public Set<Player> listenersFor(Radio sender, List<Radio> towers, Map<Radio, Integer> relays) {
        return hearing(Bukkit.getOnlinePlayers(), sender, towers, relays).players();
    }

    /** Signal for each online portable listener, keyed by player. */
    public Map<UUID, Integer> signalFor(Radio sender, List<Radio> towers, Map<Radio, Integer> relays) {
        return hearing(Bukkit.getOnlinePlayers(), sender, towers, relays).bars();
    }

    // --------------------------------------------------------------------- scan

    /**
     * Public channels with a powered, working terminal the receiver could hear from here, each with its
     * best signal. Private frequencies are never listed, whoever is asking.
     */
    public List<ScanResult> scan(Location from, boolean antenna) {
        int loss = antenna ? 0 : depthLoss(from);
        Map<String, double[]> found = new LinkedHashMap<>(); // channel -> {towers, nearestSquared, dx, dz, bars}
        for (Radio radio : manager.all()) {
            if (!radio.isTuned() || !radio.isPowered() || !manager.isLive(radio)) {
                continue;
            }
            Frequency frequency = manager.frequencyOf(radio);
            if (frequency == null || frequency.access() != Frequency.Access.PUBLIC) {
                continue;
            }
            int bars = effectiveBars(from, radio, antenna, loss);
            if (bars < 1) {
                continue;
            }
            double distance = flatDistanceSquared(from, radio);
            double[] entry = found.computeIfAbsent(radio.channel(), c -> new double[]{0, Double.MAX_VALUE, 0, 0, 0});
            entry[0]++;
            entry[4] = Math.max(entry[4], bars);
            if (distance < entry[1]) {
                entry[1] = distance;
                entry[2] = radio.x() + 0.5 - from.getX();
                entry[3] = radio.z() + 0.5 - from.getZ();
            }
        }
        List<ScanResult> results = new ArrayList<>();
        found.forEach((channel, e) -> results.add(new ScanResult(channel, (int) e[0],
                (int) Math.round(Math.sqrt(e[1])), direction(e[2], e[3]), (int) e[4])));
        results.sort(Comparator.comparingInt(ScanResult::nearestBlocks));
        return results;
    }

    /** Compass direction (N is toward -Z) of an offset, as eight-point text. */
    static String direction(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int index = (int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8;
        return DIRECTIONS[index];
    }

    // ------------------------------------------------------------------- signal

    public Signal signal(Player player) {
        ItemStack receiver = activeReceiver(player);
        String preferred = items.preferredChannel(receiver);
        String auto = items.autoChannel(receiver);
        String channel = auto != null ? auto : preferred;
        if (channel == null) {
            return new Signal(null, State.OFF, 0);
        }
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null) {
            return searching(receiver, preferred, channel);
        }
        if (!frequency.hasAccess(player.getName())) {
            return new Signal(channel, State.LOCKED, 0);
        }
        Location where = player.getLocation();
        boolean antenna = items.hasAntenna(receiver);
        int loss = antenna ? 0 : depthLoss(where);
        int bestRaw = 0;
        int best = 0;
        int bestUnjammed = 0;
        for (Radio radio : manager.all()) {
            if (!radio.channel().equals(channel) || !radio.isPowered() || !manager.isLive(radio)) {
                continue;
            }
            bestRaw = Math.max(bestRaw, distanceBars(where, radio, antenna));
            best = Math.max(best, effectiveBars(where, radio, antenna, loss));
            bestUnjammed = Math.max(bestUnjammed, effectiveBarsUnjammed(where, radio, antenna, loss));
        }
        if (best >= 1) {
            return new Signal(channel, State.OK, best, auto != null);
        }
        if (bestUnjammed >= 1) {
            return new Signal(channel, State.JAMMED, 0); // only the jamming stands between the player and the signal
        }
        if (autoScanOn(receiver)) {
            return searching(receiver, preferred, channel);
        }
        // In reach of a tower but rock is eating the signal: say so, so players know an antenna helps.
        return new Signal(channel, bestRaw >= 1 ? State.TOO_DEEP : State.NO_SIGNAL, 0);
    }

    /** No usable signal: with auto-scan on the receiver is looking for another channel. */
    private Signal searching(ItemStack receiver, @Nullable String preferred, String channel) {
        return new Signal(preferred != null ? preferred : channel,
                autoScanOn(receiver) ? State.SCANNING : State.NO_SIGNAL, 0);
    }

    private boolean autoScanOn(@Nullable ItemStack receiver) {
        return items.autoScanEnabled(receiver, plugin.getConfig().getBoolean("portable.auto-scan-default", true));
    }

    // --------------------------------------------------------------- auto-scan

    /** The best signal the player would get on {@code channel} here, 0 when none or no access. */
    public int channelBars(String playerName, Location where, String channel, boolean antenna) {
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null || !frequency.hasAccess(playerName)) {
            return 0;
        }
        int loss = antenna ? 0 : depthLoss(where);
        int best = 0;
        for (Radio radio : manager.all()) {
            if (radio.channel().equals(channel) && radio.isPowered() && manager.isLive(radio)) {
                best = Math.max(best, effectiveBars(where, radio, antenna, loss));
            }
        }
        return best;
    }

    /** {@link #channelBars} without jamming: what the player would get here if nobody were jamming. */
    public int channelBarsUnjammed(String playerName, Location where, String channel, boolean antenna) {
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null || !frequency.hasAccess(playerName)) {
            return 0;
        }
        int loss = antenna ? 0 : depthLoss(where);
        int best = 0;
        for (Radio radio : manager.all()) {
            if (radio.channel().equals(channel) && radio.isPowered() && manager.isLive(radio)) {
                best = Math.max(best, effectiveBarsUnjammed(where, radio, antenna, loss));
            }
        }
        return best;
    }

    /** What auto-scan should do about one receiver. {@code change} is false when nothing needs to happen. */
    public record AutoStep(boolean change, @Nullable String auto, @Nullable ScanResult found) {
        static final AutoStep KEEP = new AutoStep(false, null, null);
    }

    /**
     * Decides auto-scan for one receiver. Stays on the chosen channel while it has a signal; hops to the
     * nearest public channel (never a private one) when it is lost; and returns to the chosen channel only
     * when it is back with at least two bars, so a receiver at the edge of range does not flip back and forth.
     */
    public AutoStep autoStep(String playerName, Location where, boolean antenna, String preferred, @Nullable String current) {
        int preferredBars = channelBars(playerName, where, preferred, antenna);
        if (current != null) {
            if (preferredBars >= 2) {
                return new AutoStep(true, null, null);               // chosen channel is clearly back
            }
            if (channelBars(playerName, where, current, antenna) >= 1) {
                return AutoStep.KEEP;                                 // still fine where it is
            }
        } else if (preferredBars >= 1) {
            return AutoStep.KEEP;
        }
        for (ScanResult result : scan(where, antenna)) {              // nearest first
            if (!result.channel().equals(preferred) && !result.channel().equals(current)) {
                return new AutoStep(true, result.channel(), result);
            }
        }
        return current == null ? AutoStep.KEEP : new AutoStep(true, null, null); // nothing in range: wait on the chosen one
    }

    private final Map<UUID, Long> lastNotice = new HashMap<>();

    private int receiverSlot(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (items.isReceiver(inventory.getItemInMainHand())) {
            return inventory.getHeldItemSlot();
        }
        return items.isReceiver(inventory.getItemInOffHand()) ? 40 : -1;
    }

    /** Runs auto-scan for every player carrying a receiver; called once a second. */
    private void autoScanAll() {
        boolean defaultOn = plugin.getConfig().getBoolean("portable.auto-scan-default", true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack receiver = activeReceiver(player);
            String preferred = items.preferredChannel(receiver);
            if (receiver == null || preferred == null || !items.autoScanEnabled(receiver, defaultOn)) {
                continue;
            }
            AutoStep step = autoStep(player.getName(), player.getLocation(), items.hasAntenna(receiver),
                    preferred, items.autoChannel(receiver));
            if (!step.change()) {
                continue;
            }
            int slot = receiverSlot(player);
            if (slot < 0) {
                continue;
            }
            player.getInventory().setItem(slot, items.withAuto(receiver, step.auto()));
            boolean returning = step.auto() == null;
            // Clearing the hop only deserves a "back on" message when the chosen channel really is back.
            if (!returning || channelBars(player.getName(), player.getLocation(), preferred, items.hasAntenna(receiver)) >= 1) {
                notifyHop(player, preferred, step);
            }
        }
    }

    private void notifyHop(Player player, String preferred, AutoStep step) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < 5000) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        if (step.found() != null) {
            Alerts.send(player, Alerts.Level.YELLOW, "Signal", "Lost " + Alerts.name(preferred) + ". Now on "
                    + Alerts.name(step.found().channel()) + ".");
        } else if (step.auto() == null) {
            Alerts.send(player, Alerts.Level.CLEAR, "Signal", "Back on " + Alerts.name(preferred) + ".");
        }
    }

    // ----------------------------------------------------------- who is tuned in

    /** A player holding a receiver tuned to a channel; {@code hearing} is false when they have no signal. */
    public record TunedIn(Player player, boolean hearing, boolean auto) {
    }

    /** Online players holding a receiver tuned to {@code channel} (including auto-scan hops), by name. */
    public List<TunedIn> tunedIn(String channel) {
        return tunedIn(Bukkit.getOnlinePlayers(), channel);
    }

    public List<TunedIn> tunedIn(Collection<? extends Player> candidates, String channel) {
        List<TunedIn> found = new ArrayList<>();
        for (Player player : candidates) {
            ItemStack receiver = activeReceiver(player);
            if (receiver == null || !channel.equals(items.receiverChannel(receiver))) {
                continue;
            }
            found.add(new TunedIn(player, signal(player).state() == State.OK, items.autoChannel(receiver) != null));
        }
        found.sort(Comparator.comparing(entry -> entry.player().getName().toLowerCase()));
        return found;
    }

    // ------------------------------------------------------------------ alerts

    private record Reported(State state, @Nullable String channel, boolean weak, boolean jam) {
    }

    private final Map<UUID, Reported> reported = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();

    /**
     * Once a second: runs auto-scan, then tells each receiver carrier about changes in their signal with a
     * private chat line (only that player sees it). Only changes are reported, never a steady state, and
     * at most one alert every few seconds per player; a change that has to wait is reported once the gap passes.
     */
    public void tick() {
        autoScanAll();
        Set<UUID> carrying = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack receiver = activeReceiver(player);
            if (receiver == null || items.preferredChannel(receiver) == null) {
                continue; // no receiver, or switched off: nothing to report
            }
            carrying.add(player.getUniqueId());
            alert(player, signal(player));
        }
        reported.keySet().retainAll(carrying);
    }

    private void alert(Player player, Signal signal) {
        UUID id = player.getUniqueId();
        boolean jammedDown = signal.state() == State.OK && manager.jamLoss(player.getLocation()) > 0;
        Reported now = new Reported(signal.state(), signal.channel(), signal.state() == State.OK && signal.bars() <= 1, jammedDown);
        Reported before = reported.get(id);
        if (now.equals(before)) {
            return;
        }
        String message = messageFor(before, now, signal);
        if (message == null) {
            reported.put(id, now); // a change nobody needs to hear about
            return;
        }
        long time = System.currentTimeMillis();
        Long last = lastAlert.get(id);
        if (last != null && time - last < 4000) {
            return; // too soon after the previous alert; try again next tick
        }
        lastAlert.put(id, time);
        reported.put(id, now);
        // Only losing access or being jammed goes to chat; the rest flashes above the hotbar so chat stays for talk.
        if (now.state() == State.LOCKED || now.state() == State.JAMMED) {
            Chat.send(player, message);
        } else {
            player.sendActionBar(Chat.text(message));
        }
    }

    /** The alert for a change, or null when it should pass silently. */
    private @Nullable String messageFor(@Nullable Reported before, Reported now, Signal signal) {
        String channel = Alerts.name(now.channel());
        // A new channel (tuned by hand, or hopped by auto-scan) starts afresh: only problems are announced.
        boolean sameChannel = before != null && java.util.Objects.equals(before.channel(), now.channel());
        boolean wasProblem = sameChannel && before.state() != State.OK && before.state() != State.OFF;
        return switch (now.state()) {
            case OFF -> null;
            case NO_SIGNAL -> Alerts.line(Alerts.Level.YELLOW, "Signal", "Lost the signal on " + channel + ".");
            case SCANNING -> Alerts.line(Alerts.Level.YELLOW, "Signal", "Lost " + channel + ". Searching.");
            case TOO_DEEP -> Alerts.line(Alerts.Level.YELLOW, "Signal", "Too deep to hear " + channel + ".");
            case LOCKED -> Alerts.line(Alerts.Level.RED, "Access", "You no longer have access to " + channel + ".");
            case JAMMED -> Alerts.line(Alerts.Level.RED, "Signal", "Signal jammed on " + channel + ".");
            case OK -> {
                if (signal.auto()) {
                    yield null; // the auto-scan hop already told the player
                }
                if (wasProblem) {
                    yield Alerts.line(Alerts.Level.CLEAR, "Signal", "Signal back on " + channel + " "
                            + bars(signal.bars()) + Alerts.Level.CLEAR.body() + ".");
                }
                // Rising interference is not announced: the bars and the static show it.
                yield now.weak() && !(sameChannel && before.weak())
                        ? Alerts.line(Alerts.Level.YELLOW, "Signal", "Weak signal on " + channel + ".") : null;
            }
        };
    }

    private static String bars(int count) {
        return "§7[§a" + "|".repeat(count) + "§8" + "|".repeat(4 - count) + "§7]";
    }

    /** Drops what is remembered about a player (on quit). */
    public void forget(Player player) {
        lastNotice.remove(player.getUniqueId());
        reported.remove(player.getUniqueId());
        lastAlert.remove(player.getUniqueId());
    }

    public void clear() {
        lastNotice.clear();
        reported.clear();
        lastAlert.clear();
    }
}
