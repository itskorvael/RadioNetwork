package dev.radionetwork;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Radio Jukeboxes: placed jukeboxes that play a vanilla music disc over the channel of the Radio Terminal
 * they are linked to. The disc's sound is never played from the block; each listener of that channel (the
 * people beside towers, and Pocket Receiver holders in range) gets it played straight to them, so music
 * reaches exactly the people text and voice do. Playing burns a little extra terminal fuel.
 * Main-thread only.
 */
public final class MusicBoxes {

    /** A placed Radio Jukebox. The first six fields are saved; the rest is runtime playback state. */
    public static final class Box {
        final String world;
        final int x;
        final int y;
        final int z;
        final String owner;
        @Nullable String terminalKey;
        /** The tracks, in play order; {@code position} is the one playing (or next to play). */
        final List<Track> queue = new ArrayList<>();
        int position;
        RepeatMode mode = RepeatMode.LOOP;

        long playingSince;
        boolean finished;
        double drainAccumulator;
        /** Listeners currently receiving the song (so it can be stopped for them when they leave). */
        final Set<UUID> hearing = new HashSet<>();
        /** Listeners who have been told this round that they missed the start. */
        final Set<UUID> told = new HashSet<>();
        /** For a note-block song: who hears it right now, and at what signal (4 = full, as at a terminal). */
        final Map<UUID, Integer> audience = new HashMap<>();
        /** The listeners themselves, kept alongside {@code audience} so playing a note needs no lookup. */
        final Map<UUID, Player> audiencePlayers = new HashMap<>();
        /** For a note-block song: the next note not yet played this round. */
        int noteIndex;

        Box(String world, int x, int y, int z, String owner) {
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

        /** The track playing now (or loaded to play next), or null when the queue is empty. */
        public @Nullable Track current() {
            return queue.isEmpty() ? null : queue.get(Math.min(position, queue.size() - 1));
        }

        /** The vanilla disc playing now, or null when the queue is empty or the current track is a song. */
        public @Nullable Material disc() {
            Track current = current();
            return current == null ? null : current.disc();
        }

        public List<Track> queue() {
            return java.util.Collections.unmodifiableList(queue);
        }

        public int position() {
            return position;
        }

        public RepeatMode mode() {
            return mode;
        }

        public boolean finished() {
            return finished;
        }

        public @Nullable String terminalKey() {
            return terminalKey;
        }

        /** Replaces the whole queue with one disc (or empties it with null). */
        public void setDisc(@Nullable Material disc) {
            queue.clear();
            position = 0;
            if (disc != null) {
                queue.add(Track.vanilla(disc));
            }
        }

        public void resetPlayback() {
            playingSince = 0;
            finished = false;
            drainAccumulator = 0;
            noteIndex = 0;
            told.clear();
            audience.clear();
            audiencePlayers.clear();
        }

        @Nullable Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }

    }

    /** What happens when a song ends. */
    public enum RepeatMode {
        LOOP("Loop the queue"),
        ONE("Repeat this song"),
        ONCE("Play once");

        private final String label;

        RepeatMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public RepeatMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private record Selection(String terminalKey, long madeAt) {
    }

    private static final int DEFAULT_SECONDS = 180;
    /** Approximate vanilla lengths in seconds, keyed by the part after MUSIC_DISC_. Admin-overridable. */
    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("13", 178), Map.entry("CAT", 185), Map.entry("BLOCKS", 345), Map.entry("CHIRP", 185),
            Map.entry("FAR", 174), Map.entry("MALL", 197), Map.entry("MELLOHI", 96), Map.entry("STAL", 150),
            Map.entry("STRAD", 188), Map.entry("WARD", 251), Map.entry("11", 71), Map.entry("WAIT", 238),
            Map.entry("OTHERSIDE", 195), Map.entry("5", 178), Map.entry("PIGSTEP", 149), Map.entry("RELIC", 218),
            Map.entry("CREATOR", 176), Map.entry("CREATOR_MUSIC_BOX", 73), Map.entry("PRECIPICE", 299),
            Map.entry("TEARS", 175));

    private static final long SELECTION_MILLIS = 60_000L;

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final SongLibrary songs;
    private final File file;
    private final Map<String, Box> boxes = new LinkedHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();

    public MusicBoxes(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, SongLibrary songs) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.songs = songs;
        this.file = new File(plugin.getDataFolder(), "jukeboxes.yml");
    }

    // ---------------------------------------------------------------- registry

    public Box register(Block block, String owner) {
        Box box = new Box(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), owner);
        box.mode = defaultMode();
        boxes.put(box.key(), box);
        save();
        return box;
    }

    public @Nullable Box get(Block block) {
        return boxes.get(RadioManager.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public @Nullable Box get(String key) {
        return boxes.get(key);
    }

    public Iterable<Box> all() {
        return boxes.values();
    }

    /** Removes a jukebox (it was broken or destroyed): stops its music. The disc is the caller's to return. */
    public void remove(Box box) {
        stopSounds(box);
        boxes.remove(box.key());
        save();
    }

    public @Nullable Radio terminalOf(Box box) {
        return box.terminalKey == null ? null : manager.get(box.terminalKey);
    }

    public void link(Box box, Radio terminal) {
        stopSounds(box);
        box.terminalKey = terminal.key();
        box.resetPlayback();
        save();
    }

    /** Its Signal Cable was destroyed (lightning): the jukebox loses its link and goes quiet. */
    public void sever(Box box) {
        stopSounds(box);
        box.terminalKey = null;
        box.resetPlayback();
        save();
    }

    /** A terminal was removed: its jukeboxes lose their link and go quiet. */
    public void unlinkTerminal(String terminalKey) {
        boolean changed = false;
        for (Box box : boxes.values()) {
            if (terminalKey.equals(box.terminalKey)) {
                stopSounds(box);
                box.terminalKey = null;
                box.resetPlayback();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------------- rules

    /** May this player put music on the terminal's channel? Same people who may speak on it. */
    public boolean canPlayOn(Player player, Radio terminal) {
        Frequency frequency = manager.frequencyOf(terminal);
        return frequency != null && frequency.canSpeak(player.getName());
    }

    public boolean isWorking(Radio terminal) {
        return terminal != null && terminal.isPowered() && manager.isLive(terminal);
    }

    /** Another jukebox on the same channel is already playing a disc. */
    public boolean channelBusy(Box self, Radio terminal) {
        for (Box other : boxes.values()) {
            if (other == self || other.disc() == null || other.finished || other.playingSince == 0) {
                continue;
            }
            Radio otherTerminal = terminalOf(other);
            if (otherTerminal != null && otherTerminal.channel().equals(terminal.channel()) && isWorking(otherTerminal)) {
                return true;
            }
        }
        return false;
    }

    public void select(Player player, Radio terminal) {
        selections.put(player.getUniqueId(), new Selection(terminal.key(), System.currentTimeMillis()));
    }

    public @Nullable String selected(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || System.currentTimeMillis() - selection.madeAt() > SELECTION_MILLIS) {
            selections.remove(player.getUniqueId());
            return null;
        }
        return selection.terminalKey();
    }

    public void clearSelection(Player player) {
        selections.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------- queue

    public int capacity() {
        return Math.max(1, Math.min(9, plugin.getConfig().getInt("music.queue-size", 5)));
    }

    /** The mode a new jukebox starts in: {@code music.repeat-mode}, or the older {@code music.repeat} switch. */
    private RepeatMode defaultMode() {
        String configured = plugin.getConfig().getString("music.repeat-mode");
        if (configured != null) {
            try {
                return RepeatMode.valueOf(configured.toUpperCase(Locale.ROOT).replace(' ', '_'));
            } catch (IllegalArgumentException ignored) {
                // fall through to the default below
            }
        }
        return plugin.getConfig().getBoolean("music.repeat", true) ? RepeatMode.LOOP : RepeatMode.ONCE;
    }

    public boolean addDisc(Box box, Material disc) {
        return addTrack(box, Track.vanilla(disc));
    }

    /** Adds a track to the end of the queue; false when the queue is full. A finished jukebox picks it up. */
    public boolean addTrack(Box box, Track track) {
        if (box.queue.size() >= capacity()) {
            return false;
        }
        boolean wasEmpty = box.queue.isEmpty();
        boolean wasFinished = box.finished;
        box.queue.add(track);
        if (wasEmpty) {
            box.position = 0;
        } else if (wasFinished) {
            box.position = box.queue.size() - 1; // the run was over; the new disc is the one to play
        }
        if (wasEmpty || wasFinished) {
            box.resetPlayback();
        }
        save();
        return true;
    }

    /** Takes the track at {@code index} out of the queue and returns it, or null if there is none there. */
    public @Nullable Track removeDisc(Box box, int index) {
        if (index < 0 || index >= box.queue.size()) {
            return null;
        }
        boolean wasCurrent = index == box.position;
        if (wasCurrent) {
            stopSounds(box); // before the queue changes, so the right song is stopped
        }
        Track removed = box.queue.remove(index);
        if (index < box.position) {
            box.position--;
        }
        if (box.queue.isEmpty()) {
            box.position = 0;
            box.resetPlayback();
        } else if (wasCurrent) {
            box.position = box.position >= box.queue.size() ? 0 : box.position;
            box.resetPlayback(); // the next disc starts a new round
        }
        save();
        return removed;
    }

    /** Empties the queue and returns every track that was in it. */
    public List<Track> takeAll(Box box) {
        stopSounds(box);
        List<Track> all = new ArrayList<>(box.queue);
        box.queue.clear();
        box.position = 0;
        box.resetPlayback();
        save();
        return all;
    }

    /** Skips to the next disc. In play-once mode, skipping the last disc ends the run. */
    public void skip(Box box) {
        if (box.queue.isEmpty()) {
            return;
        }
        stopSounds(box);
        if (box.mode == RepeatMode.ONCE && box.position == box.queue.size() - 1) {
            box.finished = true;
        } else {
            box.position = (box.position + 1) % box.queue.size();
            box.resetPlayback();
        }
        save();
    }

    public RepeatMode cycleMode(Box box) {
        box.mode = box.mode.next();
        save();
        return box.mode;
    }

    /** The track that follows the current one, or null when none will (a single song, or the last of a play-once run). */
    public @Nullable Track nextTrack(Box box) {
        if (box.queue.size() < 2 || box.mode == RepeatMode.ONE) {
            return null;
        }
        if (box.mode == RepeatMode.ONCE && box.position == box.queue.size() - 1) {
            return null;
        }
        return box.queue.get((box.position + 1) % box.queue.size());
    }

    // -------------------------------------------------------------- disc facts

    public static boolean isDisc(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType().name().startsWith("MUSIC_DISC_");
    }

    private static String suffix(Material disc) {
        return disc.name().substring("MUSIC_DISC_".length());
    }

    static Key soundKey(Material disc) {
        return Key.key("minecraft", "music_disc." + suffix(disc).toLowerCase(Locale.ROOT));
    }

    public static String songName(Material disc) {
        String name = suffix(disc).toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.isDigit(name.charAt(0)) ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private int lengthSeconds(Material disc) {
        String suffix = suffix(disc);
        int configured = plugin.getConfig().getInt("music.lengths." + suffix, -1);
        return configured > 0 ? configured : LENGTHS.getOrDefault(suffix, DEFAULT_SECONDS);
    }

    /** How long a track plays, in seconds. A song that is no longer available counts as zero. */
    public int lengthSeconds(Track track) {
        if (track.isSong()) {
            SongLibrary.Meta meta = songs.get(track.songId());
            return meta == null ? 0 : meta.seconds();
        }
        return lengthSeconds(track.disc());
    }

    public SongLibrary songs() {
        return songs;
    }

    /** The item that stands for a track: the vanilla disc, or the Song Disc of a note-block song. */
    public ItemStack itemFor(Track track) {
        if (!track.isSong()) {
            return new ItemStack(track.disc());
        }
        SongLibrary.Meta meta = songs.get(track.songId());
        if (meta == null) {
            ItemStack unknown = new ItemStack(Material.MUSIC_DISC_5);
            var itemMeta = unknown.getItemMeta();
            itemMeta.displayName(Chat.item("§7Unknown song"));
            unknown.setItemMeta(itemMeta);
            return unknown;
        }
        return items.createSongDisc(meta);
    }

    /** The name shown for a track: a vanilla disc's song, or the name its player gave a note-block song. */
    public String name(Track track) {
        if (track.isSong()) {
            SongLibrary.Meta meta = songs.get(track.songId());
            return meta == null ? "Unknown song" : meta.name();
        }
        return songName(track.disc());
    }

    // ---------------------------------------------------------------- playback

    /** Once a second: advance every jukebox, keep each listener's sound in step, and draw cable lines. */
    public void tick() {
        long now = System.currentTimeMillis();
        double extraDrain = plugin.getConfig().getDouble("music.extra-drain-per-second", 0.5);
        for (Box box : new ArrayList<>(boxes.values())) {
            World world = Bukkit.getWorld(box.world);
            if (world == null || !world.isChunkLoaded(box.x >> 4, box.z >> 4)) {
                continue;
            }
            if (world.getBlockAt(box.x, box.y, box.z).getType() != Material.JUKEBOX) {
                remove(box); // removed behind our back (another plugin, a command)
                continue;
            }
            tickBox(box, world, now, extraDrain);
        }
        showLinks();
    }

    private void tickBox(Box box, World world, long now, double extraDrain) {
        Radio terminal = terminalOf(box);
        if (box.current() == null || !isWorking(terminal)) {
            stopSounds(box);
            if (!box.finished) {
                box.playingSince = 0; // starts a fresh round when the terminal works again
                box.told.clear();
                box.audience.clear();
                box.audiencePlayers.clear();
            }
            return;
        }
        if (box.finished) {
            return;
        }
        box.drainAccumulator += extraDrain;
        long whole = (long) Math.floor(box.drainAccumulator);
        if (whole > 0) {
            box.drainAccumulator -= whole;
            manager.consume(terminal, whole);
        }

        Set<Player> listeners = manager.listenersOf(terminal);
        playStep(box, terminal, listeners, now);
        if (box.current() != null && !box.current().isSong()) {
            addStatic(terminal, listeners);
        }
        world.spawnParticle(Particle.NOTE, box.x + 0.5, box.y + 1.2, box.z + 0.5, 1, 0.3, 0.1, 0.3, 1);
    }

    /**
     * Advances one jukebox's shared song clock. The clock runs whether or not anyone is listening, so
     * nobody's arrival restarts the song. Vanilla cannot start a sound partway in, so:
     * <ul>
     *   <li>everyone in range at the start of a round (or within the short grace window after it) hears it;</li>
     *   <li>later arrivals are told when the next round begins instead of getting a restart;</li>
     *   <li>with {@code music.repeat} the disc starts a new round when it ends, so everyone in range at that
     *       moment is in sync again.</li>
     * </ul>
     */
    public void playStep(Box box, Radio terminal, Set<Player> listeners, long now) {
        Track current = box.current();
        if (current == null || box.finished) {
            return;
        }
        if (current.isSong() && (!songs.isActive(current.songId()) || (isAudio(current) && songs.audio() == null))) {
            skipUnavailable(box);   // a removed song, or an audio song while voice chat is down, cannot play; move on
            return;
        }
        long lengthMillis = (lengthSeconds(current) + (current.isSong() ? 2L : 3L)) * 1000L;
        boolean repeat = box.mode != RepeatMode.ONCE;
        if (box.playingSince == 0) {
            newRound(box, now);
        } else if (now - box.playingSince >= lengthMillis) {
            box.hearing.clear(); // the track is over; clients finish their own copy, so nothing is cut off
            if (!advance(box)) {
                stopAudio(box);
                return;
            }
            newRound(box, now); // the next track starts for everyone in range at once
            current = box.current();
            lengthMillis = (lengthSeconds(current) + (current.isSong() ? 2L : 3L)) * 1000L;
        }
        if (current.isSong()) {
            songStep(box, terminal, listeners);
            return;
        }

        long graceMillis = (long) (plugin.getConfig().getDouble("music.join-grace-seconds", 5.0) * 1000);
        boolean inGrace = now - box.playingSince <= graceMillis;
        Set<UUID> present = new HashSet<>();
        for (Player listener : listeners) {
            UUID id = listener.getUniqueId();
            present.add(id);
            if (box.hearing.contains(id)) {
                continue;
            }
            if (inGrace) {
                box.hearing.add(id);
                startFor(listener, box, terminal);
            } else if (box.told.add(id)) {
                tellMissed(listener, box, terminal, lengthMillis - (now - box.playingSince), repeat);
            }
        }
        for (Iterator<UUID> it = box.hearing.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!present.contains(id)) {
                Player gone = Bukkit.getPlayer(id);
                if (gone != null && box.disc() != null) {
                    gone.stopSound(SoundStop.namedOnSource(soundKey(box.disc()), Sound.Source.RECORD));
                }
                it.remove(); // leaving ends their copy; coming back mid-song does not restart it
            }
        }
    }

    /** Moves to the next track per the repeat mode. Returns false when a play-once run has just ended. */
    private boolean advance(Box box) {
        switch (box.mode) {
            case ONE -> { }
            case LOOP -> box.position = (box.position + 1) % box.queue.size();
            case ONCE -> {
                if (box.position + 1 < box.queue.size()) {
                    box.position++;
                } else {
                    box.finished = true; // the run is over; the discs stay inside until taken out
                    return false;
                }
            }
        }
        return true;
    }

    /** A queued song was removed from the library: skip over it without playing. */
    private void skipUnavailable(Box box) {
        stopAudio(box);
        box.hearing.clear();
        box.audience.clear();
        box.audiencePlayers.clear();
        if (box.queue.size() <= 1 || (box.mode == RepeatMode.ONCE && box.position == box.queue.size() - 1)) {
            box.finished = true;
        } else {
            box.position = (box.position + 1) % box.queue.size();
            box.playingSince = 0;
        }
    }

    /**
     * A note-block song runs on the same shared clock, but because the plugin plays every note itself,
     * a listener who arrives mid-song simply hears the notes from that moment on: everyone is in sync.
     * Here we only keep the audience (who hears it, and at what signal) up to date once a second.
     */
    private void songStep(Box box, Radio terminal, Set<Player> listeners) {
        Set<UUID> atTerminal = new HashSet<>();
        manager.towerListenersOf(terminal).forEach(player -> atTerminal.add(player.getUniqueId()));
        Map<UUID, Integer> bars = manager.portableBars(terminal);
        Map<UUID, Integer> relayed = manager.relayBars(terminal);
        Set<UUID> present = new HashSet<>();
        for (Player listener : listeners) {
            UUID id = listener.getUniqueId();
            present.add(id);
            box.audience.put(id, atTerminal.contains(id) ? relayed.getOrDefault(id, 4) : bars.getOrDefault(id, 4));
            box.audiencePlayers.put(id, listener);
            if (box.hearing.add(id)) {
                startSongFor(listener, box, terminal);
            }
        }
        box.audience.keySet().retainAll(present);
        box.audiencePlayers.keySet().retainAll(present);
        box.hearing.retainAll(present);
        if (isAudio(box.current())) {
            publishAudio(box);
        }
    }

    /** True when the track is a real audio song (played through voice chat), not a note-block song. */
    private boolean isAudio(@Nullable Track track) {
        if (track == null || !track.isSong()) {
            return false;
        }
        SongLibrary.Meta meta = songs.get(track.songId());
        return meta != null && meta.isAudio();
    }

    /** Tells the voice side who should hear this audio song now, and how much static each listener gets. */
    private void publishAudio(Box box) {
        AudioBackend backend = songs.audio();
        if (backend == null || box.current() == null) {
            return;
        }
        PortableReceivers portables = manager.portables();
        Map<UUID, Float> loss = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : box.audience.entrySet()) {
            loss.put(entry.getKey(), portables == null ? 0f : portables.staticLoss(entry.getValue()));
        }
        backend.play(box.key(), new AudioBackend.Playing(box.current().songId(), box.playingSince, loss));
    }

    private void stopAudio(Box box) {
        AudioBackend backend = songs.audio();
        if (backend != null) {
            backend.stop(box.key());
        }
    }

    private void startSongFor(Player listener, Box box, Radio terminal) {
        Track next = nextTrack(box);
        SongLibrary.Meta meta = songs.get(box.current().songId());
        Chat.send(listener, Chat.tag("§d", "Radio", terminal.channel()) + "§fNow playing: " + name(box.current())
                + (meta == null ? "" : " §7by " + meta.uploader())
                + (next == null ? "" : " §7(next: " + name(next) + ")"));
    }

    // ---------------------------------------------------------- note playback

    private static final String[] NOTE_SOUNDS = {
            "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};
    private static final Key[] NOTE_KEYS = new Key[NOTE_SOUNDS.length];
    private static final float[] PITCH = new float[88];

    static {
        for (int i = 0; i < NOTE_SOUNDS.length; i++) {
            NOTE_KEYS[i] = Key.key("minecraft", "block.note_block." + NOTE_SOUNDS[i]);
        }
        for (int key = 0; key < PITCH.length; key++) {
            int folded = key;                       // the game's pitch range is two octaves (keys 33-57);
            while (folded < 33) folded += 12;       // other keys are folded into it, keeping the note's name
            while (folded > 57) folded -= 12;
            PITCH[key] = (float) Math.pow(2, (folded - 45) / 12.0);
        }
    }

    /** Most notes any one listener is sent in a single tick; a denser burst is thinned out. */
    private static final int MAX_NOTES_PER_TICK = 24;

    /**
     * Called every game tick: plays the notes of every running song that have come due, to its audience.
     * The song's position comes from the shared clock, so late arrivals are in sync. Volume follows the
     * listener's signal, and at a weak signal some notes are dropped, like static. Falling more than a few
     * ticks behind (server lag) skips notes instead of firing them in a burst.
     */
    public void songTick(long now) {
        float master = (float) plugin.getConfig().getDouble("music.volume", 1.0);
        PortableReceivers portables = manager.portables();
        for (Box box : boxes.values()) {
            Track current = box.current();
            if (current == null || !current.isSong() || box.finished || box.playingSince == 0 || box.audience.isEmpty()
                    || isAudio(current)) {
                continue;
            }
            NbsSong song = songs.load(current.songId());
            if (song == null) {
                continue;
            }
            int position = (int) ((now - box.playingSince) / 1000.0 * song.ticksPerSecond());
            List<NbsSong.Note> notes = song.notes();
            Map<UUID, Integer> sent = new HashMap<>();
            while (box.noteIndex < notes.size() && notes.get(box.noteIndex).tick() <= position) {
                NbsSong.Note note = notes.get(box.noteIndex++);
                if (note.tick() < position - 3) {
                    continue;
                }
                playNote(box, note, master, portables, sent);
            }
        }
    }

    private void playNote(Box box, NbsSong.Note note, float master, @Nullable PortableReceivers portables, Map<UUID, Integer> sent) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        Key sound = NOTE_KEYS[note.instrument()];
        float pitch = PITCH[Math.max(0, Math.min(PITCH.length - 1, note.key()))];
        for (Map.Entry<UUID, Integer> entry : box.audience.entrySet()) {
            Player listener = box.audiencePlayers.get(entry.getKey());
            if (listener == null || sent.merge(entry.getKey(), 1, Integer::sum) > MAX_NOTES_PER_TICK) {
                continue;
            }
            int bars = entry.getValue();
            if (portables != null && random.nextFloat() < portables.staticLoss(bars)) {
                continue; // static: this note never arrives
            }
            float volume = note.volume() * master * (0.5f + 0.125f * Math.max(1, Math.min(4, bars)));
            listener.playSound(Sound.sound(sound, Sound.Source.RECORD, Math.min(1f, volume), pitch), Sound.Emitter.self());
        }
    }

    private void newRound(Box box, long now) {
        stopSounds(box);
        box.playingSince = now;
        box.noteIndex = 0;
        box.told.clear();
    }

    private void tellMissed(Player listener, Box box, Radio terminal, long millisLeft, boolean repeat) {
        String song = name(box.current());
        if (repeat) {
            Chat.send(listener, Chat.tag("§d", "Radio", terminal.channel()) + "§7" + song
                    + " is already playing. You'll catch it from the start in " + Chat.duration(Math.max(1, millisLeft / 1000)) + ".");
        } else {
            Chat.send(listener, Chat.tag("§d", "Radio", terminal.channel()) + "§7" + song
                    + " started before you tuned in.");
        }
    }

    /**
     * Static for Pocket Receiver listeners with a weak signal: the music itself can't be degraded once it is
     * playing, so crackle is layered over it, more often the weaker the signal. Terminal listeners hear it clean.
     */
    private void addStatic(Radio terminal, Set<Player> listeners) {
        PortableReceivers portables = manager.portables();
        if (portables == null) {
            return;
        }
        Set<UUID> atTerminal = new HashSet<>();
        manager.towerListenersOf(terminal).forEach(player -> atTerminal.add(player.getUniqueId()));
        var random = java.util.concurrent.ThreadLocalRandom.current();
        // People at a terminal the signal reached through relays get crackle too if the relays cost them bars (relay.bars-per-hop) or a jam does.
        manager.relayBars(terminal).forEach((id, bars) -> crackle(portables, id, bars, listeners, random));
        manager.portableBars(terminal).forEach((id, bars) -> {
            if (!atTerminal.contains(id)) {
                crackle(portables, id, bars, listeners, random);
            }
        });
    }

    private void crackle(PortableReceivers portables, UUID id, int bars, Set<Player> listeners,
                         java.util.concurrent.ThreadLocalRandom random) {
        Player listener = Bukkit.getPlayer(id);
        float loss = portables.staticLoss(bars);
        if (listener != null && loss > 0 && listeners.contains(listener) && random.nextFloat() < loss) {
            listener.playSound(listener.getLocation(), org.bukkit.Sound.BLOCK_CAMPFIRE_CRACKLE,
                    0.5f + random.nextFloat() * 0.4f, 0.9f + random.nextFloat() * 0.4f);
        }
    }

    private void startFor(Player listener, Box box, Radio terminal) {
        float volume = (float) plugin.getConfig().getDouble("music.volume", 1.0);
        // Attached to the listener: without an emitter the sound stays at the spot where they stood when it
        // started, and fades out as soon as they walk away from it.
        listener.playSound(Sound.sound(soundKey(box.disc()), Sound.Source.RECORD, volume, 1f), Sound.Emitter.self());
        Track next = nextTrack(box);
        Chat.send(listener, Chat.tag("§d", "Radio", terminal.channel()) + "§fNow playing: " + songName(box.disc())
                + (next == null ? "" : " §7(next: " + name(next) + ")"));
    }

    /** Stops the disc for everyone currently hearing it. */
    public void stopSounds(Box box) {
        stopAudio(box);
        box.audience.clear();
        box.audiencePlayers.clear();
        if (box.disc() != null) {
            for (UUID id : box.hearing) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.stopSound(SoundStop.namedOnSource(soundKey(box.disc()), Sound.Source.RECORD));
                }
            }
        }
        box.hearing.clear();
    }

    // ------------------------------------------------------------- cable lines

    /** Dust particles along a straight line, visible only to {@code viewer}. */
    public void drawLine(Player viewer, Location from, Location to) {
        double length = from.distance(to);
        int steps = Math.max(1, (int) (length * 2));
        Particle.DustOptions copper = new Particle.DustOptions(Color.fromRGB(184, 115, 51), 1.1f);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(t));
            viewer.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, copper);
        }
    }

    /** Shows the line for a few seconds, so a fresh link is easy to see. */
    public void flashLine(Player viewer, Location from, Location to) {
        for (int i = 0; i < 6; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (viewer.isOnline()) {
                    drawLine(viewer, from, to);
                }
            }, i * 10L);
        }
    }

    private void showLinks() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!items.isCable(player.getInventory().getItemInMainHand())
                    && !items.isCable(player.getInventory().getItemInOffHand())) {
                continue;
            }
            for (Box box : boxes.values()) {
                Radio terminal = terminalOf(box);
                Location a = box.center();
                Location b = terminal == null ? null : terminal.center();
                if (a != null && b != null && a.getWorld().equals(player.getWorld()) && a.distanceSquared(player.getLocation()) < 24 * 24) {
                    drawLine(player, a, b);
                }
            }
        }
    }

    // ------------------------------------------------------------- persistence

    public void load() {
        boxes.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> raw : yaml.getMapList("jukeboxes")) {
            try {
                Box box = new Box(String.valueOf(raw.get("world")), ((Number) raw.get("x")).intValue(),
                        ((Number) raw.get("y")).intValue(), ((Number) raw.get("z")).intValue(),
                        String.valueOf(raw.get("owner")));
                if (raw.get("terminal") != null) {
                    box.terminalKey = String.valueOf(raw.get("terminal"));
                }
                if (raw.get("discs") instanceof List<?> tracks) {
                    tracks.forEach(entry -> box.queue.add(Track.parse(String.valueOf(entry))));
                } else if (raw.get("disc") != null) { // saved before queues existed: a queue of one
                    box.queue.add(Track.vanilla(Material.valueOf(String.valueOf(raw.get("disc")))));
                }
                box.position = raw.get("position") instanceof Number n && n.intValue() < box.queue.size() ? Math.max(0, n.intValue()) : 0;
                box.mode = raw.get("mode") != null ? RepeatMode.valueOf(String.valueOf(raw.get("mode"))) : defaultMode();
                boxes.put(box.key(), box);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping malformed jukebox entry: " + raw, e);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Box box : boxes.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("world", box.world);
            entry.put("x", box.x);
            entry.put("y", box.y);
            entry.put("z", box.z);
            entry.put("owner", box.owner);
            if (box.terminalKey != null) {
                entry.put("terminal", box.terminalKey);
            }
            if (!box.queue.isEmpty()) {
                entry.put("discs", box.queue.stream().map(Track::key).toList());
                entry.put("position", box.position);
            }
            entry.put("mode", box.mode.name());
            list.add(entry);
        }
        yaml.set("jukeboxes", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save jukeboxes.yml", e);
        }
    }

    public void shutdown() {
        boxes.values().forEach(this::stopSounds);
        save();
    }
}
