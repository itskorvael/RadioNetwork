package dev.radionetwork;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every spot (a station sign, as a rule) that has produced a free announcement: who put it there, where it is,
 * the channel it speaks on and what it said last. It exists so staff can find and remove a sign that is being
 * abused. One entry per spot, however often it announces. Persisted to {@code announcement-signs.yml}.
 */
public final class AnnouncementSigns {

    /** One announcing spot; {@code placer} is empty when the caller did not say who is responsible. */
    public record Entry(String world, int x, int y, int z, String placer, String channel, String lastMessage,
                        int count, long first, long last) {
    }

    private static final int MAX_ENTRIES = 500;
    private static final int MAX_MESSAGE = 80;

    private final File file;
    private final Logger logger;
    /** Oldest use first: a spot that announces again moves to the end. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    public AnnouncementSigns(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void record(String world, int x, int y, int z, @Nullable String placer, String channel, String message, long now) {
        String key = world + ";" + x + ";" + y + ";" + z;
        Entry old = entries.remove(key);
        String shown = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
        entries.put(key, new Entry(world, x, y, z, placer == null ? "" : placer, channel, shown,
                old == null ? 1 : old.count() + 1, old == null ? now : old.first(), now));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.keySet().iterator().next());
        }
        dirty = true;
    }

    /** Newest use first. */
    public List<Entry> list() {
        List<Entry> all = new ArrayList<>(entries.values());
        Collections.reverse(all);
        return all;
    }

    public int size() {
        return entries.size();
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("signs")) {
            try {
                Entry entry = new Entry(String.valueOf(raw.get("world")), ((Number) raw.get("x")).intValue(),
                        ((Number) raw.get("y")).intValue(), ((Number) raw.get("z")).intValue(),
                        raw.get("placer") == null ? "" : String.valueOf(raw.get("placer")),
                        String.valueOf(raw.get("channel")), String.valueOf(raw.get("message")),
                        ((Number) raw.get("count")).intValue(), ((Number) raw.get("first")).longValue(),
                        ((Number) raw.get("last")).longValue());
                entries.put(entry.world() + ";" + entry.x() + ";" + entry.y() + ";" + entry.z(), entry);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Skipping malformed announcement sign entry: " + raw, e);
            }
        }
    }

    public void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("world", entry.world());
            raw.put("x", entry.x());
            raw.put("y", entry.y());
            raw.put("z", entry.z());
            raw.put("placer", entry.placer());
            raw.put("channel", entry.channel());
            raw.put("message", entry.lastMessage());
            raw.put("count", entry.count());
            raw.put("first", entry.first());
            raw.put("last", entry.last());
            list.add(raw);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("signs", list);
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save announcement-signs.yml", e);
        }
    }
}
