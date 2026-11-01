package dev.radionetwork;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A song read from an Open Note Block Studio ({@code .nbs}) file. Only what playback needs is kept: the
 * notes (when, which vanilla instrument, which key, how loud), the tempo and the title. The reader is
 * defensive: every count and length is bounded, so a broken or hostile file fails with a message and
 * cannot exhaust memory. Supports the old (pre-version-1) layout and versions 1-5.
 */
public final class NbsSong {

    /** One note: song tick, vanilla instrument (0-15), NBS key (0-87), volume 0-1. */
    public record Note(int tick, int instrument, int key, float volume) {
    }

    /** Thrown for a file that is not a usable song; the message is safe to show to a player. */
    public static final class InvalidSongException extends Exception {
        public InvalidSongException(String message) {
            super(message);
        }
    }

    /** What to do with notes that use a custom instrument (a sound file only the composer has). */
    public enum CustomMode {
        /** Guess a vanilla instrument from the custom instrument's name; use the fallback if there is no clue. */
        MATCH,
        /** Play every custom note as the fallback instrument. */
        FALLBACK,
        /** Leave custom notes out. */
        SKIP
    }

    /** How custom instruments are handled; {@code fallback} is a vanilla instrument number 0-15. */
    public record Rules(CustomMode mode, int fallback) {
        public static final Rules DEFAULT = new Rules(CustomMode.MATCH, 0);
    }

    /** The game's own instruments, in note-block file order (index = instrument number). */
    private static final String[] INSTRUMENTS = {
            "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};

    /** A vanilla instrument number from its name ("harp", "piano", "drum", ...), or -1 if it is not one. */
    public static int instrumentByName(String name) {
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (int i = 0; i < INSTRUMENTS.length; i++) {
            if (INSTRUMENTS[i].equals(n)) {
                return i;
            }
        }
        return switch (n) {
            case "piano" -> 0;
            case "double_bass" -> 1;
            case "drum", "bass_drum" -> 2;
            case "click", "clap" -> 4;
            case "cowbell" -> 11;
            default -> -1;
        };
    }

    /**
     * Guesses a vanilla instrument for a custom instrument from its name, or -1 when the name gives no clue.
     * Order matters: more specific words are tried before general ones ("bass drum" before "bass").
     */
    static int guessInstrument(String customName) {
        String n = customName.toLowerCase(java.util.Locale.ROOT);
        String[][] hints = {
                {"2", "bass drum", "kick", "bassdrum", "tom", "taiko", "timpani"},
                {"3", "snare", "clap", "rimshot"},
                {"4", "hihat", "hi-hat", "hi hat", "cymbal", "click", "tick", "shaker", "rim", "tambourine"},
                {"11", "cowbell", "cow bell"},
                {"10", "iron xylo", "metallophone", "glockenspiel", "celesta"},
                {"9", "xylophone", "marimba", "vibraphone", "vibes", "kalimba"},
                {"8", "chime", "tubular", "triangle"},
                {"7", "bell"},
                {"1", "bass", "sub", "contrabass"},
                {"5", "guitar", "strum", "mandolin", "lute", "ukulele"},
                {"14", "banjo", "sitar"},
                {"6", "flute", "choir", "voice", "vocal", "oboe", "clarinet", "sax", "whistle", "pipe", "ocarina", "recorder", "aah", "ooh"},
                {"12", "didgeridoo", "horn", "trumpet", "tuba", "trombone", "brass"},
                {"13", "8-bit", "8bit", "chip", "square", "synth", "lead", "saw", "bit"},
                {"15", "pling", "electric piano", "epiano", "rhodes", "e.piano"},
                {"0", "piano", "keys", "harp", "organ", "pad", "strings", "violin", "cello"}};
        for (String[] hint : hints) {
            for (int i = 1; i < hint.length; i++) {
                if (n.contains(hint[i])) {
                    return Integer.parseInt(hint[0]);
                }
            }
        }
        return -1;
    }

    /** Limits that keep one song from being a problem for the server. */
    public record Limits(int maxNotes, int maxSeconds, int maxLayers) {
        public static final Limits DEFAULT = new Limits(40_000, 600, 200);
    }

    private final String title;
    private final String author;
    private final double ticksPerSecond;
    private final int lengthTicks;
    private final List<Note> notes;
    private final int skippedCustom;
    private final int matchedCustom;
    private final int fallbackCustom;

    private NbsSong(String title, String author, double ticksPerSecond, int lengthTicks, List<Note> notes,
                    int skippedCustom, int matchedCustom, int fallbackCustom) {
        this.title = title;
        this.author = author;
        this.ticksPerSecond = ticksPerSecond;
        this.lengthTicks = lengthTicks;
        this.notes = notes;
        this.skippedCustom = skippedCustom;
        this.matchedCustom = matchedCustom;
        this.fallbackCustom = fallbackCustom;
    }

    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    public double ticksPerSecond() {
        return ticksPerSecond;
    }

    /** Song length in song ticks (the tick after the last note). */
    public int lengthTicks() {
        return lengthTicks;
    }

    public int lengthSeconds() {
        return (int) Math.ceil(lengthTicks / ticksPerSecond);
    }

    /** Notes in time order. */
    public List<Note> notes() {
        return notes;
    }

    /** Custom-instrument notes left out (skip mode, or an instrument number the file never defines). */
    public int skippedCustom() {
        return skippedCustom;
    }

    /** Custom-instrument notes played as a vanilla instrument chosen from the instrument's name. */
    public int matchedCustom() {
        return matchedCustom;
    }

    /** Custom-instrument notes played as the fallback instrument. */
    public int fallbackCustom() {
        return fallbackCustom;
    }

    // ------------------------------------------------------------------ reading

    public static NbsSong parse(byte[] data, Limits limits) throws InvalidSongException {
        return parse(data, limits, Rules.DEFAULT);
    }

    public static NbsSong parse(byte[] data, Limits limits, Rules rules) throws InvalidSongException {
        try {
            return read(new Reader(data), limits, rules);
        } catch (EOFException e) {
            throw new InvalidSongException("The file ends too early to be a song.");
        } catch (IOException e) {
            throw new InvalidSongException("The file could not be read as a note-block song.");
        }
    }

    private static NbsSong read(Reader in, Limits limits, Rules rules) throws IOException, InvalidSongException {
        int first = in.shortLE();
        int version = 0;
        int vanillaInstruments = 10;
        int songLength;
        if (first == 0) {                       // new layout: a zero short, then the version
            version = in.u8();
            if (version < 1 || version > 5) {
                throw new InvalidSongException("Unsupported note-block file version " + version + ".");
            }
            vanillaInstruments = in.u8();
            songLength = version >= 3 ? in.shortLE() : 0;
        } else {
            songLength = first;                 // old layout: the first short is the length
        }
        int layerCount = in.shortLE();
        if (layerCount < 1 || layerCount > limits.maxLayers()) {
            throw new InvalidSongException("The song has " + layerCount + " layers; the limit is " + limits.maxLayers() + ".");
        }
        String title = in.string(200);
        String author = in.string(200);
        in.string(200);                         // original author
        in.string(1000);                        // description
        int tempo = in.shortLE();               // ticks per second x 100
        if (tempo <= 0) {
            throw new InvalidSongException("The song has no valid tempo.");
        }
        in.skip(1 + 1 + 1);                     // auto-save, auto-save duration, time signature
        in.skip(4 * 5);                         // minutes spent, left clicks, right clicks, blocks added, blocks removed
        in.string(500);                         // imported file name
        if (version >= 4) {
            in.skip(1 + 1 + 2);                 // loop on/off, max loops, loop start tick
        }

        double ticksPerSecond = tempo / 100.0;
        List<Note> notes = new ArrayList<>();
        List<int[]> raw = new ArrayList<>();    // {tick, layer, instrument, key, velocity}
        int tick = -1;
        while (true) {
            int jump = in.shortLE();
            if (jump == 0) {
                break;
            }
            tick += jump;
            if (tick / ticksPerSecond > limits.maxSeconds()) {
                throw new InvalidSongException("The song is longer than " + limits.maxSeconds() / 60 + " minutes.");
            }
            int layer = -1;
            while (true) {
                int layerJump = in.shortLE();
                if (layerJump == 0) {
                    break;
                }
                layer += layerJump;
                int instrument = in.u8();
                int key = in.u8();
                int velocity = 100;
                if (version >= 4) {
                    velocity = in.u8();
                    in.u8();                    // panning (playback is mono)
                    in.shortLE();               // fine pitch
                }
                if (raw.size() >= limits.maxNotes()) {
                    throw new InvalidSongException("The song has more than " + limits.maxNotes() + " notes.");
                }
                raw.add(new int[]{tick, layer, instrument, key, velocity});
            }
        }
        if (tick < 0) {
            throw new InvalidSongException("The song contains no notes.");
        }

        // Layer volumes follow the notes (version 2+ adds a stereo byte, version 4+ a lock byte).
        float[] layerVolume = new float[layerCount];
        java.util.Arrays.fill(layerVolume, 1f);
        for (int layer = 0; layer < layerCount; layer++) {
            try {
                in.string(200);                 // layer name
                if (version >= 4) {
                    in.u8();                    // locked
                }
                layerVolume[layer] = Math.max(0, Math.min(100, in.u8())) / 100f;
                if (version >= 2) {
                    in.u8();                    // stereo
                }
            } catch (EOFException e) {
                break;                          // a file that ends here simply plays every layer at full volume
            }
        }

        // Custom instruments are listed last: their names are all the clue we have about how they sounded.
        List<String> customNames = new ArrayList<>();
        try {
            int customCount = in.u8();
            for (int i = 0; i < customCount; i++) {
                customNames.add(in.string(100));
                in.string(200);                 // sound file name
                in.skip(2);                     // pitch, press key
            }
        } catch (EOFException e) {
            // no custom instrument section: custom notes will have no names to go on
        }

        int vanillaLimit = Math.max(vanillaInstruments, 10);
        int skippedCustom = 0;
        int matchedCustom = 0;
        int fallbackCustom = 0;
        for (int[] n : raw) {
            float layerGain = n[1] >= 0 && n[1] < layerCount ? layerVolume[n[1]] : 1f;
            float volume = Math.max(0, Math.min(100, n[4])) / 100f * layerGain;
            if (volume <= 0) {
                continue;
            }
            int instrument = n[2];
            if (instrument >= 0 && instrument < vanillaLimit && instrument < INSTRUMENTS.length) {
                notes.add(new Note(n[0], instrument, n[3], volume));   // one of the game's own instruments
                continue;
            }
            // A custom instrument: a sound file the game doesn't have. Pick a stand-in, or leave the note out.
            int customIndex = instrument - vanillaLimit;
            String name = customIndex >= 0 && customIndex < customNames.size() ? customNames.get(customIndex) : "";
            int stand = -1;
            boolean byName = false;
            if (rules.mode() == CustomMode.MATCH) {
                stand = guessInstrument(name);
                byName = stand >= 0;
            }
            if (stand < 0 && rules.mode() != CustomMode.SKIP) {
                stand = Math.max(0, Math.min(INSTRUMENTS.length - 1, rules.fallback()));
            }
            if (stand < 0) {
                skippedCustom++;
            } else {
                notes.add(new Note(n[0], stand, n[3], volume));
                if (byName) {
                    matchedCustom++;
                } else {
                    fallbackCustom++;
                }
            }
        }
        if (notes.isEmpty()) {
            throw new InvalidSongException("The song has no notes that can be played with the game's own instruments.");
        }
        int length = Math.max(songLength, tick + 1);
        if (length / ticksPerSecond > limits.maxSeconds()) {
            throw new InvalidSongException("The song is longer than " + limits.maxSeconds() / 60 + " minutes.");
        }
        return new NbsSong(title.isBlank() ? "" : title, author, ticksPerSecond, length, List.copyOf(notes),
                skippedCustom, matchedCustom, fallbackCustom);
    }

    // ------------------------------------------------------------------- reader

    /** Little-endian reader with bounds on every length. */
    private static final class Reader {
        private final DataInputStream in;

        Reader(byte[] data) {
            this.in = new DataInputStream(new ByteArrayInputStream(data));
        }

        int u8() throws IOException {
            return in.readUnsignedByte();
        }

        int shortLE() throws IOException {
            int low = in.readUnsignedByte();
            return low | (in.readUnsignedByte() << 8);
        }

        int intLE() throws IOException {
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            int b2 = in.readUnsignedByte();
            int b3 = in.readUnsignedByte();
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        void skip(int bytes) throws IOException {
            for (int i = 0; i < bytes; i++) {
                in.readUnsignedByte();
            }
        }

        String string(int maxLength) throws IOException, InvalidSongException {
            int length = intLE();
            if (length < 0 || length > maxLength * 4) {
                throw new InvalidSongException("The file has a text field of an impossible size.");
            }
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", "").replace("§", "").trim();
            return text.length() > maxLength ? text.substring(0, maxLength) : text;
        }
    }
}
