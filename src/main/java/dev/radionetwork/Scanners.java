package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Signal Scanners: listen-only handhelds that can tune an ENCRYPTED frequency without joining it. Without the key
 * a scanner hears noise (garbled text, voice that breaks up). To clear it the holder breaks the code in the
 * Decoder: a few code-breaking puzzles in a row, each one a little harder to do if the password is long, and each one
 * cleared takes a share of the noise away. The scanner needs a signal while it works, and a weak one slows it down.
 * Decoding belongs to one scanner and one password: when the owner changes the password, every scanner starts again.
 * <p>
 * The frequency's people are warned without any names: a yellow alert once when someone starts scanning, and a red
 * alert a little while after a scanner finishes decoding.
 */
public final class Scanners {

    /** Share of letters lost on a frequency whose key the scanner does not have, before decoding starts. */
    private static final double ENCRYPTED_TEXT_SHARE = 0.9;
    /** Share of voice packets lost in the same case. */
    private static final double ENCRYPTED_VOICE_LOSS = 0.95;

    /** The colours a code is made of, in the order of the Decoder's palette. */
    public static final int COLORS = 6;
    public static final String[] COLOR_NAMES = {"Red", "Orange", "Yellow", "Green", "Blue", "Purple"};
    public static final String[] COLOR_CODES = {"§c", "§6", "§e", "§a", "§b", "§5"};

    /** What a scanner hears of one transmission: its signal, and how much of text and voice is lost. */
    public record Heard(Player player, int bars, double textShare, float voiceLoss, boolean masked) {
    }

    /** One guess at the code: the colours tried, how many sat in the right place, and how many were right but elsewhere. */
    public record Guess(int[] pegs, int exact, int misplaced) {
    }

    /** What happened when a Decoder button was pressed. */
    public enum Result {
        OK, FULL, EMPTY, NOT_READY, WAIT, LOCKED_OUT, NO_SIGNAL, WRONG_SCANNER, GUESSED, STAGE_SOLVED, DECODED, FAILED
    }

    /** A code-breaking puzzle in progress for one scanner on one frequency. Lives in memory only. */
    public static final class Puzzle {
        private final String id;
        private final String channel;
        private final String hash;
        private final int slots;
        private int stage;
        private int[] secret;
        private final List<Guess> guesses = new ArrayList<>();
        private final int[] current;
        private int filled;
        private long nextGuessAt;
        private long lockedUntil;

        private Puzzle(String id, String channel, String hash, int slots, int stage) {
            this.id = id;
            this.channel = channel;
            this.hash = hash;
            this.slots = slots;
            this.stage = stage;
            this.current = new int[slots];
            this.secret = newSecret(slots);
            Arrays.fill(current, -1);
        }

        private static int[] newSecret(int slots) {
            int[] code = new int[slots];
            for (int i = 0; i < slots; i++) {
                code[i] = ThreadLocalRandom.current().nextInt(COLORS);
            }
            return code;
        }

        /** A fresh code for the same stage (after a failed attempt, or when the stage changes). */
        private void restart() {
            secret = newSecret(slots);
            guesses.clear();
            Arrays.fill(current, -1);
            filled = 0;
        }

        public String channel() {
            return channel;
        }

        public int slots() {
            return slots;
        }

        /** The stage being worked on, from 0. */
        public int stage() {
            return stage;
        }

        public List<Guess> guesses() {
            return guesses;
        }

        /** The colours picked so far for the next guess; -1 marks an empty slot. */
        public int[] current() {
            return current;
        }

        public int filled() {
            return filled;
        }

        public long nextGuessAt() {
            return nextGuessAt;
        }

        public long lockedUntil() {
            return lockedUntil;
        }

        /** The hidden code. Only for tests. */
        public int[] secret() {
            return secret.clone();
        }
    }

    private static final class Entry {
        final String id;
        final String channel;
        String hash;
        /** Stages cleared so far. */
        int solved;
        boolean cracked;
        long redAt;
        /** Seconds this scanner has been off the frequency since it last made progress or lost a stage. */
        double idle;

        Entry(String id, String channel, String hash) {
            this.id = id;
            this.channel = channel;
            this.hash = hash;
        }

        void reset(String newHash) {
            hash = newHash;
            solved = 0;
            cracked = false;
            redAt = 0;
            idle = 0;
        }
    }

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final PortableReceivers portables;
    private final File file;
    private final Map<String, Entry> entries = new HashMap<>();
    /** Frequency name -> when a scanner was last on it; a yellow alert is sent only when it had been quiet a while. */
    private final Map<String, Long> scanSeen = new HashMap<>();
    private final Map<UUID, Puzzle> puzzles = new HashMap<>();
    /** Players who have the Decoder open right now. */
    private final Set<UUID> viewing = new HashSet<>();
    /** The players the scanners look at; the online players, except in tests. */
    private Supplier<Collection<? extends Player>> online = Bukkit::getOnlinePlayers;
    private boolean dirty;
    private int ticks;

    public Scanners(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, PortableReceivers portables) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.portables = portables;
        this.file = new File(plugin.getDataFolder(), "scanners.yml");
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String id, String channel) {
        return id + "|" + channel.toLowerCase(Locale.ROOT);
    }

    /** The scanner in the main hand, then the off hand, or null. */
    public @Nullable ItemStack held(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (items.isScanner(inventory.getItemInMainHand())) {
            return inventory.getItemInMainHand();
        }
        return items.isScanner(inventory.getItemInOffHand()) ? inventory.getItemInOffHand() : null;
    }

    /** How many puzzles in a row it takes to decode a frequency. */
    public int stages() {
        return Math.max(1, plugin.getConfig().getInt("scanner.puzzle.stages", 3));
    }

    public int maxGuesses() {
        return Math.max(1, plugin.getConfig().getInt("scanner.puzzle.max-guesses", 10));
    }

    /** Slots in the code: more for a longer password (a password saved before its length was kept counts as medium). */
    public int slotsFor(Frequency frequency) {
        int length = frequency.passwordLength();
        if (length <= 0) {
            return 5;
        }
        return length <= 5 ? 4 : length <= 9 ? 5 : 6;
    }

    /** The entry for a scanner on a frequency; a password change since it was made wipes it. */
    private Entry entry(String id, Frequency frequency) {
        Entry entry = entries.computeIfAbsent(key(id, frequency.name()), k -> new Entry(id, frequency.name(), frequency.hash()));
        if (!entry.hash.equals(frequency.hash())) {
            entry.reset(frequency.hash());
            dirty = true;
        }
        return entry;
    }

    /** Decoding progress (0 to 1) of this scanner on this frequency; 1 once it has the key. */
    public double progress(@Nullable ItemStack scanner, Frequency frequency) {
        String id = items.scannerId(scanner);
        if (id == null) {
            return 0;
        }
        Entry entry = entries.get(key(id, frequency.name()));
        if (entry == null || !entry.hash.equals(frequency.hash())) {
            return 0;
        }
        return entry.cracked ? 1.0 : Math.min(1.0, entry.solved / (double) stages());
    }

    /** Whether the frequency is one this player's scanner can only hear as noise (encrypted, no key). */
    private boolean locked(Frequency frequency, Player player) {
        return frequency.access() == Frequency.Access.ENCRYPTED && frequency.hasPassword()
                && !frequency.hasAccess(player.getName());
    }

    /** A line for the holder: what the scanner is tuned to and how far decoding has got. */
    public String statusLine(Player player, @Nullable ItemStack scanner) {
        String channel = items.scannerChannel(scanner);
        if (channel == null) {
            return "§5Signal Scanner: §7switched off.";
        }
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null) {
            return "§5Signal Scanner: §7tuned to §f" + channel + "§7, but nobody has claimed that frequency.";
        }
        if (!locked(frequency, player)) {
            return "§5Signal Scanner: §7tuned to §f" + channel + "§7, " + (frequency.access() == Frequency.Access.PUBLIC
                    ? "a public frequency, heard clearly." : "you have its key, heard clearly.");
        }
        double progress = progress(scanner, frequency);
        return "§5Signal Scanner: §7tuned to §f" + channel + " §7(encrypted), decoded §f" + (int) Math.floor(progress * 100) + "%§7"
                + (progress >= 1 ? " - fully decoded." : ". Right-click to open the Decoder.");
    }

    // ------------------------------------------------------------------ hearing

    /**
     * Scanners that hear a transmission from {@code sender}: held, tuned to its channel and with a signal, apart from
     * players already in {@code normal} (they hear it properly through a terminal or Pocket Receiver).
     */
    public List<Heard> hearing(Radio sender, Collection<? extends Player> normal) {
        List<Heard> heard = new ArrayList<>();
        Frequency frequency = manager.frequencyOf(sender);
        if (frequency == null) {
            return heard;
        }
        RadioManager.Reach reach = manager.reachOf(sender);
        if (reach.towers().isEmpty()) {
            return heard;
        }
        double garbleMax = plugin.getConfig().getDouble("garble.max-share", 0.5);
        for (Player player : online.get()) {
            ItemStack scanner = held(player);
            if (scanner == null || normal.contains(player) || !sender.channel().equals(items.scannerChannel(scanner))) {
                continue;
            }
            int bars = portables.barsFrom(player.getLocation(), sender, reach.towers(), reach.relays());
            if (bars < 1) {
                continue;
            }
            double keyText = 0;
            double keyVoice = 0;
            boolean masked = false;
            if (locked(frequency, player)) {
                double remaining = 1.0 - progress(scanner, frequency);
                keyText = ENCRYPTED_TEXT_SHARE * remaining;
                keyVoice = ENCRYPTED_VOICE_LOSS * remaining;
                masked = remaining > 0;
            }
            double text = 1 - (1 - keyText) * (1 - Garble.share(bars, garbleMax));
            float voice = (float) (1 - (1 - keyVoice) * (1 - portables.staticLoss(bars)));
            heard.add(new Heard(player, bars, text, voice, masked));
        }
        return heard;
    }

    /** Sends a radio text line to the scanners that hear {@code sender}; {@code speaker} null means a system line. */
    public void relayText(Radio sender, @Nullable String speaker, String text, Collection<? extends Player> normal) {
        for (Heard heard : hearing(sender, normal)) {
            String garbled = Garble.apply(text, heard.textShare(), ThreadLocalRandom.current());
            String who = speaker == null ? "" : (heard.masked() ? "§8???§7: " : "§7" + speaker + ": ");
            Chat.send(heard.player(), "§5§l[SCANNER - " + sender.channel() + "] " + who + "§f" + garbled);
        }
    }

    /** Tells scanners that someone is speaking by voice, the way terminals' listeners are told. */
    public void speaking(Radio sender, String speaker, Collection<? extends Player> normal) {
        for (Heard heard : hearing(sender, normal)) {
            Chat.send(heard.player(), "§5§l[SCANNER - " + sender.channel() + "] "
                    + (heard.masked() ? "§8Someone" : "§7" + speaker) + " §7is speaking on this frequency.");
        }
    }

    // ------------------------------------------------------------------ the Decoder

    /** The frequency this player's scanner could be decoding right now: tuned, encrypted, no key, and not yet decoded. */
    public @Nullable Frequency decodable(Player player, @Nullable ItemStack scanner) {
        String channel = items.scannerChannel(scanner);
        String id = items.scannerId(scanner);
        if (channel == null || id == null) {
            return null;
        }
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null || !locked(frequency, player)) {
            return null;
        }
        Entry entry = entry(id, frequency);
        return entry.cracked ? null : frequency;
    }

    /** Signal bars the scanner has on its frequency where its holder stands (0 = none). */
    public int signal(Player player, Frequency frequency) {
        return portables.channelBarsAny(player.getLocation(), frequency.name());
    }

    /**
     * The puzzle for this scanner on this frequency, started if there is none. It is kept between visits, so closing
     * the Decoder never gives a fresh code, and it starts again when a stage was lost or the password changed.
     */
    public @Nullable Puzzle puzzle(Player player, ItemStack scanner, Frequency frequency) {
        String id = items.scannerId(scanner);
        if (id == null) {
            return null;
        }
        Entry entry = entry(id, frequency);
        if (entry.cracked) {
            return null;
        }
        Puzzle puzzle = puzzles.get(player.getUniqueId());
        if (puzzle == null || !puzzle.id.equals(id) || !puzzle.channel.equals(frequency.name())
                || !puzzle.hash.equals(frequency.hash()) || puzzle.slots != slotsFor(frequency)) {
            puzzle = new Puzzle(id, frequency.name(), frequency.hash(), slotsFor(frequency), entry.solved);
            puzzles.put(player.getUniqueId(), puzzle);
        } else if (puzzle.stage != entry.solved) {
            puzzle.stage = entry.solved;
            puzzle.restart();
        }
        return puzzle;
    }

    /** Seconds before the next guess: longer when the signal is weak. */
    public double guessCooldown(int bars) {
        double base = Math.max(0, plugin.getConfig().getDouble("scanner.puzzle.guess-cooldown-seconds", 5.0));
        return base * 4.0 / Math.max(1, bars);
    }

    public Result pick(Puzzle puzzle, int color) {
        if (puzzle.filled >= puzzle.slots) {
            return Result.FULL;
        }
        puzzle.current[puzzle.filled++] = Math.max(0, Math.min(COLORS - 1, color));
        return Result.OK;
    }

    public Result undo(Puzzle puzzle) {
        if (puzzle.filled <= 0) {
            return Result.EMPTY;
        }
        puzzle.current[--puzzle.filled] = -1;
        return Result.OK;
    }

    /**
     * Tries the colours picked so far. A right code clears the stage (and the last stage decodes the frequency); a wrong
     * one is scored, and running out of guesses scrambles the code and locks the Decoder for a short while.
     */
    public Result submit(Player player, ItemStack scanner, Puzzle puzzle) {
        long now = System.currentTimeMillis();
        Frequency frequency = manager.frequencies().get(puzzle.channel);
        String id = items.scannerId(scanner);
        if (frequency == null || id == null || !id.equals(puzzle.id) || !puzzle.channel.equals(items.scannerChannel(scanner))
                || !puzzle.hash.equals(frequency.hash())) {
            return Result.WRONG_SCANNER;
        }
        int bars = signal(player, frequency);
        if (bars < 1) {
            return Result.NO_SIGNAL;
        }
        if (now < puzzle.lockedUntil) {
            return Result.LOCKED_OUT;
        }
        if (puzzle.filled < puzzle.slots) {
            return Result.NOT_READY;
        }
        if (now < puzzle.nextGuessAt) {
            return Result.WAIT;
        }
        int[] pegs = puzzle.current.clone();
        int exact = 0;
        int[] left = new int[COLORS];
        int[] tried = new int[COLORS];
        for (int i = 0; i < puzzle.slots; i++) {
            if (pegs[i] == puzzle.secret[i]) {
                exact++;
            } else {
                left[puzzle.secret[i]]++;
                tried[pegs[i]]++;
            }
        }
        int misplaced = 0;
        for (int c = 0; c < COLORS; c++) {
            misplaced += Math.min(left[c], tried[c]);
        }
        puzzle.guesses.add(new Guess(pegs, exact, misplaced));
        Arrays.fill(puzzle.current, -1);
        puzzle.filled = 0;
        puzzle.nextGuessAt = now + (long) (guessCooldown(bars) * 1000);
        Chat.send(player, "§5§l[SCANNER] §7Guess " + puzzle.guesses.size() + ": " + squares(pegs) + " §a" + exact
                + " in place§7, §e" + misplaced + " elsewhere§7.");
        if (exact == puzzle.slots) {
            return solveStage(player, frequency, puzzle, id, now);
        }
        if (puzzle.guesses.size() >= maxGuesses()) {
            long lockout = (long) (Math.max(0, plugin.getConfig().getDouble("scanner.puzzle.lockout-seconds", 20.0)) * 1000);
            puzzle.lockedUntil = now + lockout;
            puzzle.restart();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            Chat.send(player, "§5§l[SCANNER] §cDecode failed. The scanner is recalibrating for " + (lockout / 1000)
                    + " seconds, and the code has changed.");
            return Result.FAILED;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.0f + 0.1f * exact);
        return Result.GUESSED;
    }

    private Result solveStage(Player player, Frequency frequency, Puzzle puzzle, String id, long now) {
        Entry entry = entry(id, frequency);
        entry.solved = Math.min(stages(), entry.solved + 1);
        entry.idle = 0;
        dirty = true;
        if (entry.solved >= stages()) {
            entry.cracked = true;
            long min = (long) (plugin.getConfig().getDouble("scanner.red-alert-delay-min-seconds", 30.0) * 1000);
            long max = Math.max(min, (long) (plugin.getConfig().getDouble("scanner.red-alert-delay-max-seconds", 90.0) * 1000));
            entry.redAt = now + (max > min ? ThreadLocalRandom.current().nextLong(min, max + 1) : min);
            puzzles.remove(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
            Chat.send(player, "§5§l[SCANNER] §aFrequency §f" + frequency.name()
                    + "§a decoded. You can hear it clearly until its password is changed.");
            return Result.DECODED;
        }
        puzzle.stage = entry.solved;
        puzzle.restart();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        Chat.send(player, "§5§l[SCANNER] §aCode " + entry.solved + "/" + stages() + " broken. The noise on §f"
                + frequency.name() + "§a is thinning out (" + (int) Math.floor(100.0 * entry.solved / stages()) + "% decoded).");
        return Result.STAGE_SOLVED;
    }

    private static String squares(int[] pegs) {
        StringBuilder text = new StringBuilder();
        for (int peg : pegs) {
            text.append(COLOR_CODES[peg]).append("■");
        }
        return text.toString();
    }

    /** The Decoder was opened or closed by this player. */
    public void viewing(Player player, boolean open) {
        if (open) {
            viewing.add(player.getUniqueId());
        } else {
            viewing.remove(player.getUniqueId());
        }
    }

    /** Drops what is kept about a player (on quit): their puzzle and whether the Decoder is open. */
    public void forget(Player player) {
        puzzles.remove(player.getUniqueId());
        viewing.remove(player.getUniqueId());
    }

    // --------------------------------------------------------------------- tick

    /** Once a second: who is scanning (for the yellow alert), open Decoders, the delayed red alert, and slow decay. */
    public void tick() {
        long now = System.currentTimeMillis();
        Set<Entry> touched = new HashSet<>();
        Set<String> scanning = new LinkedHashSet<>();
        for (Player player : online.get()) {
            ItemStack scanner = held(player);
            String channel = items.scannerChannel(scanner);
            String id = items.scannerId(scanner);
            if (channel == null || id == null) {
                continue;
            }
            Frequency frequency = manager.frequencies().get(channel);
            if (frequency == null || !locked(frequency, player)) {
                continue;
            }
            Entry entry = entry(id, frequency);
            if (entry.cracked) {
                touched.add(entry);
                continue;
            }
            int bars = portables.channelBarsAny(player.getLocation(), channel);
            if (bars < 1) {
                continue; // no signal: nothing is decoded, and what is already decoded slowly fades
            }
            touched.add(entry);
            scanning.add(frequency.name());
            entry.idle = 0;
        }
        closeDecoders();
        yellowAlerts(scanning, now);
        fadeAndAlert(touched, now);
        if (dirty && ++ticks % 60 == 0) {
            save();
        }
    }

    /** An open Decoder closes when its scanner leaves the hand or loses its signal. */
    private void closeDecoders() {
        if (viewing.isEmpty()) {
            return;
        }
        for (Player player : online.get()) {
            if (!viewing.contains(player.getUniqueId())) {
                continue;
            }
            ItemStack scanner = held(player);
            Puzzle puzzle = puzzles.get(player.getUniqueId());
            Frequency frequency = scanner == null ? null : decodable(player, scanner);
            String problem = null;
            if (scanner == null || puzzle == null || frequency == null || !frequency.name().equals(puzzle.channel)) {
                problem = "Keep the Signal Scanner in your hand while you decode.";
            } else if (signal(player, frequency) < 1) {
                problem = "Lost the signal on " + Alerts.name(frequency.name()) + ". The Decoder closed.";
            }
            if (problem != null) {
                viewing.remove(player.getUniqueId());
                player.closeInventory();
                Alerts.send(player, Alerts.Level.YELLOW, "Scanner", problem);
            }
        }
    }

    /** Warns a frequency's people once when someone starts scanning it; it takes a quiet spell before it can warn again. */
    private void yellowAlerts(Set<String> scanning, long now) {
        long rearm = (long) (Math.max(0, plugin.getConfig().getDouble("scanner.yellow-rearm-minutes", 10.0)) * 60_000);
        for (String name : scanning) {
            Long seen = scanSeen.put(name, now);
            if (seen != null && now - seen <= rearm) {
                continue; // still the same scanning: already warned
            }
            Frequency frequency = manager.frequencies().get(name);
            if (frequency == null) {
                continue;
            }
            String line = Alerts.line(Alerts.Level.YELLOW, "Scanner", "Someone is scanning frequency "
                    + Alerts.name(frequency.name()) + ".");
            for (Player target : recipients(frequency)) {
                Chat.send(target, line);
                target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.0f);
            }
        }
        scanSeen.values().removeIf(seen -> now - seen > Math.max(rearm, 1000L));
    }

    private void fadeAndAlert(Set<Entry> touched, long now) {
        double idleLimit = Math.max(0.01, plugin.getConfig().getDouble("scanner.decay-minutes-per-stage", 7.0)) * 60.0;
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            Frequency frequency = manager.frequencies().get(entry.channel);
            if (frequency == null || !entry.hash.equals(frequency.hash()) || frequency.access() != Frequency.Access.ENCRYPTED) {
                iterator.remove();
                dirty = true;
                continue;
            }
            if (entry.redAt > 0 && now >= entry.redAt) {
                entry.redAt = 0;
                dirty = true;
                String line = Alerts.line(Alerts.Level.RED, "Decrypted", "Frequency " + Alerts.name(frequency.name())
                        + " has been decrypted. Change its password now.");
                recipients(frequency).forEach(target -> Chat.send(target, line));
            }
            if (!entry.cracked && !touched.contains(entry)) {
                if (entry.solved <= 0) {
                    iterator.remove();
                    continue;
                }
                entry.idle += 1.0;
                if (entry.idle >= idleLimit) {
                    entry.idle = 0;
                    entry.solved--;
                    dirty = true;
                }
            }
        }
    }

    /**
     * Who is warned about a frequency: everyone tuned in to it (at one of its terminals or holding a receiver on
     * it) who may use it, plus its owner and trusted players while they are online.
     */
    public Set<Player> recipients(Frequency frequency) {
        Set<Player> targets = new LinkedHashSet<>();
        String channel = frequency.name();
        for (Player player : online.get()) {
            if (frequency.isOwner(player.getName()) || frequency.isTrusted(player.getName())) {
                targets.add(player);
            }
        }
        portables.tunedIn(online.get(), channel).forEach(tuned -> {
            if (frequency.hasAccess(tuned.player().getName())) {
                targets.add(tuned.player());
            }
        });
        for (Radio radio : manager.all()) {
            Location center = radio.center();
            if (center == null || !radio.channel().equals(channel)) {
                continue;
            }
            for (Player player : online.get()) {
                if (center.getWorld().equals(player.getWorld()) && frequency.hasAccess(player.getName())
                        && manager.isAtTower(player.getLocation(), center)) {
                    targets.add(player);
                }
            }
        }
        return targets;
    }

    /** The scanner's holder forgets nothing; there is no per-player state to drop. */
    public void clear() {
        entries.clear();
        scanSeen.clear();
        puzzles.clear();
        viewing.clear();
    }

    // -------------------------------------------------------------- persistence

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("scanners")) {
            try {
                Entry entry = new Entry(String.valueOf(raw.get("id")), String.valueOf(raw.get("channel")),
                        String.valueOf(raw.get("hash")));
                double progress = ((Number) raw.get("progress")).doubleValue();
                entry.cracked = Boolean.parseBoolean(String.valueOf(raw.get("cracked")));
                entry.solved = entry.cracked ? stages() : (int) Math.floor(progress * stages() + 1e-6);
                entry.redAt = ((Number) raw.get("redAt")).longValue();
                entries.put(key(entry.id, entry.channel), entry);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping an unreadable scanner entry", e);
            }
        }
    }

    public void save() {
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", entry.id);
            raw.put("channel", entry.channel);
            raw.put("hash", entry.hash);
            raw.put("progress", entry.cracked ? 1.0 : entry.solved / (double) stages());
            raw.put("cracked", entry.cracked);
            raw.put("redAt", entry.redAt);
            list.add(raw);
        }
        yaml.set("scanners", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save scanners.yml", e);
        }
    }
}
