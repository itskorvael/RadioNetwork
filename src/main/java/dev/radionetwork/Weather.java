package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Weather and night. Rain or snow costs a listener out in the open a bar and a thunderstorm costs more (never more
 * than {@code weather.max-loss}), but weather only weakens a signal that is still there: it never silences it
 * on its own. An Underground Antenna is shielded from it, and so is anyone under a roof, in a desert, or in
 * a dimension without weather. At night terminals reach {@code weather.night-range-bonus} farther, within what
 * their fuel allows.
 * <p>
 * A lightning strike close to a terminal knocks it offline for ten to thirty seconds. Its listeners are told when it goes
 * down and when it comes back, and when it does it announces itself on its frequency again. A strike close to a Radio
 * Jukebox, Signal Repeater or Signal Jammer destroys the Signal Cable that links it to its terminal.
 */
public final class Weather implements Listener {

    /** Biomes with no precipitation (the sky may still be stormy over them, but nothing falls). */
    private static final Set<String> DRY_BIOMES = Set.of("desert", "savanna", "savanna_plateau", "windswept_savanna",
            "badlands", "eroded_badlands", "wooded_badlands");

    /** Lightning outages in progress. */
    private final Map<Radio, Long> outages = new IdentityHashMap<>();
    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final PortableReceivers portables;
    /** The players the weather looks at; the online players, except in tests. */
    private Supplier<Collection<? extends Player>> online = Bukkit::getOnlinePlayers;

    public Weather(RadioNetworkPlugin plugin, RadioManager manager, PortableReceivers portables) {
        this.plugin = plugin;
        this.manager = manager;
        this.portables = portables;
    }

    // ------------------------------------------------------------ signal effects

    private boolean enabled() {
        return plugin.getConfig().getBoolean("weather.enabled", true);
    }

    /** Bars lost to weather where a listener stands: 0 when sheltered, shielded (antenna), or dry. */
    public int lossAt(@Nullable Location where, boolean shielded) {
        if (!enabled() || shielded || where == null) {
            return 0;
        }
        World world = where.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL || !world.hasStorm()) {
            return 0;
        }
        // Under a roof (or a tree) the weather does not reach the listener.
        if (world.getHighestBlockYAt(where, HeightMap.MOTION_BLOCKING) > where.getBlockY()) {
            return 0;
        }
        // Deserts, savannas and badlands have no rain or snow, whatever the sky is doing.
        if (DRY_BIOMES.contains(world.getBiome(where).key().value())) {
            return 0;
        }
        int loss = world.isThundering()
                ? plugin.getConfig().getInt("weather.storm-loss", 2)
                : plugin.getConfig().getInt("weather.rain-loss", 1);
        return Math.max(0, Math.min(loss, plugin.getConfig().getInt("weather.max-loss", 2)));
    }

    /** A signal after weather: weakened but never silenced (a signal already at zero stays at zero). */
    public int apply(int bars, int loss) {
        return bars < 1 || loss <= 0 ? bars : Math.max(1, bars - loss);
    }

    public boolean isNight(@Nullable World world) {
        if (!enabled() || world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return false;
        }
        long time = world.getTime() % 24000L;
        return time >= plugin.getConfig().getLong("weather.night-start-tick", 13000L)
                && time < plugin.getConfig().getLong("weather.night-end-tick", 23000L);
    }

    public double nightBonus() {
        return Math.max(0.0, plugin.getConfig().getDouble("weather.night-range-bonus", 0.25));
    }

    /** One line for the terminal and receiver screens: what the weather is doing to signals right now. */
    public String describe(@Nullable World world) {
        if (!enabled() || world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return "§7Weather: §fnone here";
        }
        String sky;
        if (world.isThundering()) {
            sky = "§cthunderstorm §7(-" + Math.min(plugin.getConfig().getInt("weather.storm-loss", 2),
                    plugin.getConfig().getInt("weather.max-loss", 2)) + " bars outdoors)";
        } else if (world.hasStorm()) {
            sky = "§erain §7(-" + Math.min(plugin.getConfig().getInt("weather.rain-loss", 1),
                    plugin.getConfig().getInt("weather.max-loss", 2)) + " bar outdoors)";
        } else {
            sky = "§aclear";
        }
        return "§7Weather: " + sky + (isNight(world) ? " §8| §bnight: terminals reach +"
                + (int) Math.round(nightBonus() * 100) + "% farther" : "");
    }

    // ---------------------------------------------------------- weather notices

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            notifyRadioUsers(event.getWorld(), Alerts.line(Alerts.Level.YELLOW, "Weather",
                    "Storm interference outdoors."));
        } else {
            notifyRadioUsers(event.getWorld(), Alerts.line(Alerts.Level.CLEAR, "Weather",
                    "Storm interference cleared."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        if (event.toThunderState()) {
            notifyRadioUsers(event.getWorld(), Alerts.line(Alerts.Level.YELLOW, "Weather",
                    "Heavy interference outdoors."));
        } else {
            // A storm that ends altogether is announced by onWeather; this is for a thunderstorm easing into plain rain.
            World world = event.getWorld();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (world.hasStorm() && !world.isThundering()) {
                    notifyRadioUsers(world, Alerts.line(Alerts.Level.YELLOW, "Weather",
                            "The thunderstorm has passed. Rain continues."));
                }
            });
        }
    }

    /** Tells the players in a world who use radios (a receiver or scanner in hand, or standing at a terminal). */
    private void notifyRadioUsers(World world, String message) {
        if (!enabled() || !plugin.getConfig().getBoolean("weather.notices", false) || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        for (Player player : online.get()) {
            if (!world.equals(player.getWorld())) {
                continue;
            }
            boolean usesRadio = portables.activeReceiver(player) != null
                    || (manager.scanners() != null && manager.scanners().held(player) != null)
                    || !manager.radiosNear(player.getLocation()).isEmpty();
            if (usesRadio) {
                Chat.send(player, message);
            }
        }
    }

    // ---------------------------------------------------------------- lightning

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLightning(LightningStrikeEvent event) {
        if (!enabled() || !plugin.getConfig().getBoolean("weather.lightning.enabled", true)) {
            return;
        }
        Location bolt = event.getLightning().getLocation();
        double radius = Math.max(0.0, plugin.getConfig().getDouble("weather.lightning.radius", 5.0));
        // Cables first: a jammer whose cable is cut has nothing left to overheat when its terminal is struck too.
        severCables(bolt, radius);
        for (Radio radio : manager.all()) {
            if (near(radio.center(), bolt, radius) && radio.isTuned() && radio.isPowered()) {
                strike(radio);
            }
        }
    }

    private static boolean near(@Nullable Location at, Location bolt, double radius) {
        return at != null && at.getWorld() != null && at.getWorld().equals(bolt.getWorld())
                && at.distanceSquared(bolt) <= radius * radius;
    }

    /** A strike near a Radio Jukebox, Signal Repeater or Signal Jammer destroys the Signal Cable that links it to its terminal. */
    private void severCables(Location bolt, double radius) {
        if (!plugin.getConfig().getBoolean("weather.lightning.cut-cables", true)) {
            return;
        }
        if (manager.music() != null) {
            for (MusicBoxes.Box box : toList(manager.music().all())) {
                Radio terminal = manager.music().terminalOf(box);
                if (terminal != null && near(box.center(), bolt, radius)) {
                    manager.music().sever(box);
                    cut("Radio Jukebox", box.owner(), box.center(), terminal);
                }
            }
        }
        if (manager.repeaters() != null) {
            for (Repeaters.Repeater repeater : toList(manager.repeaters().all())) {
                Radio terminal = manager.repeaters().terminalOf(repeater);
                if (terminal != null && near(repeater.center(), bolt, radius)) {
                    manager.repeaters().sever(repeater);
                    cut("Signal Repeater", repeater.owner(), repeater.center(), terminal);
                }
            }
        }
        if (manager.jammers() != null) {
            for (Jammers.Jammer jammer : toList(manager.jammers().all())) {
                Radio terminal = manager.jammers().terminalOf(jammer);
                if (terminal != null && near(jammer.center(), bolt, radius)) {
                    manager.jammers().sever(jammer);
                    cut("Signal Jammer", jammer.owner(), jammer.center(), terminal);
                }
            }
        }
    }

    private static <T> List<T> toList(Iterable<T> items) {
        List<T> list = new ArrayList<>();
        items.forEach(list::add);
        return list;
    }

    /** Tells the device's owner, the terminal's operator and anyone at that terminal that the cable is gone. */
    private void cut(String what, String owner, @Nullable Location at, Radio terminal) {
        if (at == null) {
            return;
        }
        String message = Alerts.line(Alerts.Level.RED, "Power", "The " + what + " at " + at.getBlockX() + " "
                + at.getBlockY() + " " + at.getBlockZ() + " was struck and its cable destroyed. It is no longer linked to "
                + "the terminal on " + Alerts.name(terminal.isTuned() ? terminal.channel() : "no channel") + ".");
        Location center = terminal.center();
        for (Player player : online.get()) {
            boolean involved = player.getName().equalsIgnoreCase(owner) || player.getName().equalsIgnoreCase(terminal.creatorName())
                    || (center != null && manager.isAtTower(player.getLocation(), center));
            if (involved) {
                Chat.send(player, message);
            }
        }
    }

    /** Knocks a terminal offline for a random ten to thirty seconds and tells everyone who was listening to it. */
    public void strike(Radio radio) {
        double min = plugin.getConfig().getDouble("weather.lightning.min-seconds", 10.0);
        double max = Math.max(min, plugin.getConfig().getDouble("weather.lightning.max-seconds", 30.0));
        long seconds = Math.round(max > min ? ThreadLocalRandom.current().nextDouble(min, max) : min);
        Set<Player> audience = audience(radio); // worked out while it is still working
        radio.offlineUntil(System.currentTimeMillis() + seconds * 1000L);
        outages.put(radio, radio.offlineUntil());
        manager.updateLight(radio);
        // A jammer on this terminal overheats: it shuts down, cools off, and has to warm up all over again.
        boolean overheated = manager.jammers() != null && manager.jammers().overheat(radio);
        Location center = radio.center();
        for (Player player : audience) {
            if (center != null && manager.isAtTower(player.getLocation(), center)) {
                Alerts.send(player, Alerts.Level.RED, "Power", "Your terminal on " + Alerts.name(radio.channel())
                        + " was struck by lightning and went offline.");
                if (overheated) {
                    Alerts.send(player, Alerts.Level.RED, "Jammer", "The linked Signal Jammer overheated. "
                            + "It must cool down and warm up again.");
                }
            } else {
                Alerts.send(player, Alerts.Level.YELLOW, "Power", radio.creatorName() + "'s radio on "
                        + Alerts.name(radio.channel()) + " was struck by lightning and went offline.");
            }
        }
    }

    /** Everyone who hears this terminal: people at its towers and receiver holders in range. */
    private Set<Player> audience(Radio radio) {
        Set<Player> audience = new LinkedHashSet<>(manager.listenersOf(radio));
        Location center = radio.center();
        for (Player player : online.get()) {
            if (center != null && center.getWorld().equals(player.getWorld()) && manager.isAtTower(player.getLocation(), center)) {
                audience.add(player);
            }
        }
        audience.addAll(portables.listenersFor(online.get(), radio, manager.reachOf(radio).towers()));
        return audience;
    }

    /** Once a second: brings terminals back when their outage is over. */
    public void tick() {
        long now = System.currentTimeMillis();
        var iterator = outages.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Radio radio = entry.getKey();
            if (manager.get(radio.key()) != radio) {
                iterator.remove();             // removed meanwhile
                continue;
            }
            if (now < radio.offlineUntil()) {
                continue;                      // the terminal itself says when the outage ends
            }
            iterator.remove();
            radio.offlineUntil(0);
            restore(radio);
        }
    }

    private void restore(Radio radio) {
        manager.updateLight(radio);
        if (!radio.isPowered() || !radio.isTuned()) {
            return;                            // ran out of fuel or was untuned during the outage
        }
        Location center = radio.center();
        String rejoin = Chat.tag("§e", "Radio", radio.channel()) + "§7" + radio.creatorName() + "'s radio has tuned in.";
        boolean roll = manager.announcesPresence(radio);
        for (Player player : audience(radio)) {
            if (center != null && manager.isAtTower(player.getLocation(), center)) {
                Alerts.send(player, Alerts.Level.CLEAR, "Power", "The terminal on "
                        + Alerts.name(radio.channel()) + " is back online.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
            }
            if (roll) {
                Chat.send(player, rejoin);      // on an encrypted frequency, everyone hears it come back
            }
        }
    }

    public void clear() {
        outages.keySet().forEach(radio -> radio.offlineUntil(0));
        outages.clear();
    }
}
