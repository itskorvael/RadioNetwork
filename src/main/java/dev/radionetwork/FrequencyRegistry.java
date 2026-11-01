package dev.radionetwork;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Every claimed frequency, keyed by its exact channel name. Persisted to {@code frequencies.yml}. */
public final class FrequencyRegistry {

    private final File file;
    private final Logger logger;
    private final Map<String, Frequency> frequencies = new LinkedHashMap<>();
    private boolean dirty;

    public FrequencyRegistry(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public Frequency get(String name) {
        return frequencies.get(name);
    }

    public Collection<Frequency> all() {
        return frequencies.values();
    }

    public Frequency claim(String name, String owner) {
        Frequency frequency = new Frequency(name, owner);
        frequencies.put(name, frequency);
        dirty = true;
        return frequency;
    }

    public void release(Frequency frequency) {
        frequencies.remove(frequency.name());
        dirty = true;
    }

    public List<Frequency> ownedBy(String player) {
        return frequencies.values().stream().filter(f -> f.isOwner(player)).toList();
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * Frees abandoned frequencies and returns them. A frequency is abandoned when it has had no radios
     * for {@code emptyGraceMillis}, or its owner has been offline for {@code inactiveMillis}. A value of
     * 0 disables that rule.
     */
    public List<Frequency> sweep(Collection<Radio> radios, Collection<? extends Player> online, long now,
                                 long emptyGraceMillis, long inactiveMillis) {
        Set<String> used = new java.util.HashSet<>();
        radios.forEach(radio -> used.add(radio.channel()));
        Set<String> onlineNames = new java.util.HashSet<>();
        online.forEach(player -> onlineNames.add(player.getName().toLowerCase(java.util.Locale.ROOT)));

        List<Frequency> freed = new ArrayList<>();
        for (Frequency frequency : frequencies.values()) {
            if (onlineNames.contains(frequency.owner().toLowerCase(java.util.Locale.ROOT))) {
                frequency.lastSeen(now);
                dirty = true;
            }
            if (used.contains(frequency.name())) {
                if (frequency.emptySince() != 0) {
                    frequency.emptySince(0);
                    dirty = true;
                }
            } else if (frequency.emptySince() == 0) {
                frequency.emptySince(now);
                dirty = true;
            }

            boolean emptyTooLong = emptyGraceMillis > 0 && frequency.emptySince() != 0
                    && now - frequency.emptySince() >= emptyGraceMillis;
            boolean ownerGone = inactiveMillis > 0 && now - frequency.lastSeen() >= inactiveMillis;
            if (emptyTooLong || ownerGone) {
                freed.add(frequency);
            }
        }
        freed.forEach(this::release);
        return freed;
    }

    public void load() {
        frequencies.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("frequencies")) {
            try {
                Frequency frequency = new Frequency(String.valueOf(raw.get("name")), String.valueOf(raw.get("owner")));
                frequency.access(Frequency.Access.parse(String.valueOf(raw.get("access"))));
                frequency.speech(RadioMode.valueOf(String.valueOf(raw.get("speech"))));
                frequency.restorePassword(String.valueOf(raw.get("salt")), String.valueOf(raw.get("hash")),
                        raw.get("password-length") instanceof Number length ? length.intValue() : 0);
                if (raw.get("trusted") instanceof List<?> list) {
                    list.forEach(name -> frequency.trust(String.valueOf(name)));
                }
                if (raw.get("members") instanceof List<?> list) {
                    list.forEach(name -> frequency.grantMember(String.valueOf(name)));
                }
                frequency.lastSeen(((Number) raw.get("last-seen")).longValue());
                frequency.emptySince(((Number) raw.get("empty-since")).longValue());
                frequencies.put(frequency.name(), frequency);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Skipping malformed frequency entry: " + raw.get("name"), e);
            }
        }
        dirty = false;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Frequency frequency : frequencies.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", frequency.name());
            entry.put("owner", frequency.owner());
            entry.put("access", frequency.access().name());
            entry.put("speech", frequency.speech().name());
            entry.put("salt", frequency.salt());
            entry.put("hash", frequency.hash());
            entry.put("password-length", frequency.passwordLength());
            entry.put("trusted", new ArrayList<>(frequency.trusted()));
            entry.put("members", new ArrayList<>(frequency.members()));
            entry.put("last-seen", frequency.lastSeen());
            entry.put("empty-since", frequency.emptySince());
            list.add(entry);
        }
        yaml.set("frequencies", list);
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save frequencies.yml", e);
        }
    }
}
