package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Signal Repeaters: placed lightning rods that, once linked to a Radio Terminal with Signal Cables, turn that
 * terminal into a relay. A relay passes on every signal it hears on its channel, using its own radius, so two
 * towers that cannot reach each other can talk through it. A repeater has no channel, fuel or menu of its own:
 * everything comes from the terminal, and a terminal can have at most one. The routing itself lives in
 * {@link RadioManager#reachOf}. Main-thread only.
 */
public final class Repeaters {

    /** A placed Signal Repeater. */
    public static final class Repeater {
        final String world;
        final int x;
        final int y;
        final int z;
        final String owner;
        @Nullable String terminalKey;

        Repeater(String world, int x, int y, int z, String owner) {
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

        public @Nullable String terminalKey() {
            return terminalKey;
        }

        public @Nullable Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final MusicBoxes music;
    private final RadioItem items;
    private final File file;
    private final Map<String, Repeater> repeaters = new LinkedHashMap<>();
    /** Terminal key -> its repeater, so "is this terminal a relay?" is a single lookup. */
    private final Map<String, Repeater> byTerminal = new HashMap<>();

    public Repeaters(RadioNetworkPlugin plugin, RadioManager manager, MusicBoxes music, RadioItem items) {
        this.plugin = plugin;
        this.manager = manager;
        this.music = music;
        this.items = items;
        this.file = new File(plugin.getDataFolder(), "repeaters.yml");
    }

    // ---------------------------------------------------------------- registry

    public Repeater register(Block block, String owner) {
        Repeater repeater = new Repeater(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), owner);
        repeaters.put(repeater.key(), repeater);
        save();
        return repeater;
    }

    public @Nullable Repeater get(Block block) {
        return repeaters.get(RadioManager.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public Iterable<Repeater> all() {
        return repeaters.values();
    }

    /** Removes a repeater (it was broken or destroyed): the terminal it was linked to stops relaying. */
    public void remove(Repeater repeater) {
        repeaters.remove(repeater.key());
        rebuildIndex();
        save();
    }

    /** The repeater linked to this terminal, or null. */
    public @Nullable Repeater forTerminal(Radio terminal) {
        return byTerminal.get(terminal.key());
    }

    public @Nullable Radio terminalOf(Repeater repeater) {
        return repeater.terminalKey == null ? null : manager.get(repeater.terminalKey);
    }

    /** Links a repeater to a terminal; the caller has already checked the terminal has no other repeater. */
    public void link(Repeater repeater, Radio terminal) {
        repeater.terminalKey = terminal.key();
        rebuildIndex();
        save();
    }

    /** Its Signal Cable was destroyed (lightning): the repeater loses the link and the relay stops. */
    public void sever(Repeater repeater) {
        repeater.terminalKey = null;
        rebuildIndex();
        save();
    }

    /** A terminal was removed: its repeater loses the link and the relay stops. */
    public void unlinkTerminal(String terminalKey) {
        boolean changed = false;
        for (Repeater repeater : repeaters.values()) {
            if (terminalKey.equals(repeater.terminalKey)) {
                repeater.terminalKey = null;
                changed = true;
            }
        }
        if (changed) {
            rebuildIndex();
            save();
        }
    }

    private void rebuildIndex() {
        byTerminal.clear();
        for (Repeater repeater : repeaters.values()) {
            if (repeater.terminalKey != null) {
                byTerminal.put(repeater.terminalKey, repeater);
            }
        }
    }

    // ------------------------------------------------------------------ status

    /** One line saying what this repeater is doing, sent to chat when it is right-clicked. */
    public String statusLine(Repeater repeater) {
        Radio terminal = terminalOf(repeater);
        String head = "§6Signal Repeater §7- ";
        if (terminal == null) {
            return head + "§cnot linked§7. Use a Signal Cable on a Radio Terminal, then on this.";
        }
        String where = terminal.x() + " " + terminal.y() + " " + terminal.z();
        String channel = terminal.isTuned() ? "§f" + terminal.channel() : "§8no channel";
        if (manager.maxRelays() <= 0) {
            return head + "§7linked to the terminal at " + where + "§7, but relaying is turned off on this server.";
        }
        if (manager.isRelaying(terminal)) {
            return head + "§arelaying §7" + channel + " §7for the terminal at " + where
                    + " §7(up to " + manager.maxRelays() + " relays in a row)";
        }
        return head + "§7linked to the terminal at " + where + "§7, " + channel + "§7. §cOff§7: " + why(terminal);
    }

    /** Why a linked terminal is not relaying. */
    public String why(Radio terminal) {
        if (manager.maxRelays() <= 0) {
            return "relaying is turned off on this server.";
        }
        if (!terminal.isTuned() || manager.frequencyOf(terminal) == null) {
            return "the terminal is not tuned to a frequency.";
        }
        if (!manager.isLive(terminal)) {
            return "the terminal's operator no longer has access to its frequency.";
        }
        if (!terminal.isPowered()) {
            return "the terminal has no power.";
        }
        return "the terminal's chunk is not loaded.";
    }

    // -------------------------------------------------------------- once a second

    /** Drops repeaters whose block vanished behind our back, and draws the cable lines for cable holders. */
    public void tick() {
        List<Repeater> gone = new ArrayList<>();
        for (Repeater repeater : repeaters.values()) {
            World world = Bukkit.getWorld(repeater.world);
            if (world == null || !world.isChunkLoaded(repeater.x >> 4, repeater.z >> 4)) {
                continue;
            }
            if (world.getBlockAt(repeater.x, repeater.y, repeater.z).getType() != Material.LIGHTNING_ROD) {
                gone.add(repeater);
            }
        }
        gone.forEach(this::remove);
        showLinks();
    }

    private void showLinks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!items.isCable(player.getInventory().getItemInMainHand())
                    && !items.isCable(player.getInventory().getItemInOffHand())) {
                continue;
            }
            for (Repeater repeater : repeaters.values()) {
                Radio terminal = terminalOf(repeater);
                Location a = repeater.center();
                Location b = terminal == null ? null : terminal.center();
                if (a != null && b != null && a.getWorld().equals(player.getWorld())
                        && a.distanceSquared(player.getLocation()) < 24 * 24) {
                    music.drawLine(player, a, b);
                }
            }
        }
    }

    /** A spark from a repeater that is passing a signal on. */
    public void spark(Repeater repeater) {
        World world = Bukkit.getWorld(repeater.world);
        if (world != null && world.isChunkLoaded(repeater.x >> 4, repeater.z >> 4)) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, repeater.x + 0.5, repeater.y + 1.1, repeater.z + 0.5,
                    4, 0.15, 0.2, 0.15, 0.05);
        }
    }

    // ------------------------------------------------------------- persistence

    public void load() {
        repeaters.clear();
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (Map<?, ?> raw : yaml.getMapList("repeaters")) {
                try {
                    Repeater repeater = new Repeater(String.valueOf(raw.get("world")), ((Number) raw.get("x")).intValue(),
                            ((Number) raw.get("y")).intValue(), ((Number) raw.get("z")).intValue(),
                            String.valueOf(raw.get("owner")));
                    // A link to a terminal that no longer exists is dropped, so a terminal later placed at the
                    // same spot does not silently inherit a repeater.
                    if (raw.get("terminal") != null && manager.get(String.valueOf(raw.get("terminal"))) != null) {
                        repeater.terminalKey = String.valueOf(raw.get("terminal"));
                    }
                    repeaters.put(repeater.key(), repeater);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Skipping malformed repeater entry: " + raw, e);
                }
            }
        }
        rebuildIndex();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Repeater repeater : repeaters.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", repeater.world);
            entry.put("x", repeater.x);
            entry.put("y", repeater.y);
            entry.put("z", repeater.z);
            entry.put("owner", repeater.owner);
            if (repeater.terminalKey != null) {
                entry.put("terminal", repeater.terminalKey);
            }
            list.add(entry);
        }
        yaml.set("repeaters", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save repeaters.yml", e);
        }
    }
}
