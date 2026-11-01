package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Player-added note-block songs: the saved {@code .nbs} files, the registry of who added what, an append-only
 * history log, and the downloader that fetches a song from a link. Everything lives under the plugin folder:
 * {@code songs/<id>.nbs}, {@code songs.yml}, {@code songs.log}, and {@code songs/removed/} for removed files.
 */
public final class SongLibrary {

    /** One registered song. */
    public static final class Meta {
        private final String id;
        private String name;
        private final String uploader;
        private final UUID uploaderId;
        private final String url;
        private final long added;
        private final int seconds;
        private final int bytes;
        private boolean active = true;
        private final String kind;

        Meta(String id, String name, String uploader, UUID uploaderId, String url, long added, int seconds, int bytes,
             String kind) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.uploader = uploader;
            this.uploaderId = uploaderId;
            this.url = url;
            this.added = added;
            this.seconds = seconds;
            this.bytes = bytes;
        }

        public String id() { return id; }
        public String name() { return name; }
        public String uploader() { return uploader; }
        public UUID uploaderId() { return uploaderId; }
        public String url() { return url; }
        public long added() { return added; }
        public int seconds() { return seconds; }
        public int bytes() { return bytes; }
        public boolean active() { return active; }
        /** "nbs" (a note-block song) or "mp3" (a real audio file played through voice chat). */
        public String kind() { return kind; }
        public boolean isAudio() { return kind.equals("mp3"); }
    }

    /** Result of fetching a song: its bytes, or a message safe to show a player. */
    public record Download(byte @Nullable [] data, @Nullable String error) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int CACHE_SIZE = 16;

    private final RadioNetworkPlugin plugin;
    private final File folder;
    private final File removedFolder;
    private final File registry;
    private final File logFile;
    private final Map<String, Meta> metas = new LinkedHashMap<>();
    private volatile AudioBackend audio;
    private final Map<String, byte[][]> frameCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[][]> eldest) {
            return size() > 3;
        }
    });
    private final Map<String, NbsSong> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, NbsSong> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public SongLibrary(RadioNetworkPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "songs");
        this.removedFolder = new File(folder, "removed");
        this.registry = new File(plugin.getDataFolder(), "songs.yml");
        this.logFile = new File(plugin.getDataFolder(), "songs.log");
    }

    // ----------------------------------------------------------- voice backend

    /** Set by the voice-chat side when it is running, and cleared when it stops. */
    public void audio(@Nullable AudioBackend backend) {
        this.audio = backend;
    }

    /** The voice backend if it is running and ready, else null (audio songs cannot be added or played). */
    public @Nullable AudioBackend audio() {
        AudioBackend backend = audio;
        return backend != null && backend.ready() ? backend : null;
    }

    // ---------------------------------------------------------------- limits

    public NbsSong.Limits limits() {
        return new NbsSong.Limits(
                plugin.getConfig().getInt("songs.max-notes", 40_000),
                plugin.getConfig().getInt("songs.max-minutes", 15) * 60,
                200);
    }

    /** How custom instruments are handled, from {@code songs.custom-instruments} and {@code songs.custom-instrument-fallback}. */
    public NbsSong.Rules rules() {
        NbsSong.CustomMode mode;
        try {
            mode = NbsSong.CustomMode.valueOf(plugin.getConfig().getString("songs.custom-instruments", "match").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = NbsSong.CustomMode.MATCH;
        }
        int fallback = NbsSong.instrumentByName(plugin.getConfig().getString("songs.custom-instrument-fallback", "harp"));
        return new NbsSong.Rules(mode, fallback < 0 ? 0 : fallback);
    }

    public int maxBytes() {
        return plugin.getConfig().getInt("songs.max-kilobytes", 300) * 1024;
    }

    /** The size cap for a file at this link: audio files are allowed to be much larger than note-block songs. */
    int maxBytesFor(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".mp3") ? plugin.getConfig().getInt("songs.max-audio-megabytes", 10) * 1024 * 1024 : maxBytes();
    }

    public int maxPerPlayer() {
        return plugin.getConfig().getInt("songs.max-per-player", 5);
    }

    // -------------------------------------------------------------- registry

    public @Nullable Meta get(String id) {
        return metas.get(id);
    }

    public boolean isActive(String id) {
        Meta meta = metas.get(id);
        return meta != null && meta.active;
    }

    public List<Meta> all() {
        return new ArrayList<>(metas.values());
    }

    public @Nullable Meta byName(String name) {
        for (Meta meta : metas.values()) {
            if (meta.active && meta.name.equalsIgnoreCase(name.trim())) {
                return meta;
            }
        }
        return null;
    }

    public int activeCountBy(UUID uploader) {
        return (int) metas.values().stream().filter(m -> m.active && m.uploaderId.equals(uploader)).count();
    }

    /** Cleans a player-written name: no formatting codes or control characters, at most 32 characters. */
    public static String cleanName(String input) {
        String cleaned = input.replace("§", "").replaceAll("\\p{Cntrl}", "").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 32 ? cleaned.substring(0, 32).trim() : cleaned;
    }

    /** True when the name contains a word from {@code songs.banned-words}. */
    public boolean isBanned(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("songs.banned-words").stream()
                .anyMatch(word -> !word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT)));
    }

    /** A name no active song uses: "Tavern Jig", then "Tavern Jig (2)", and so on. */
    public String uniqueName(String wanted) {
        String name = wanted;
        for (int n = 2; byName(name) != null; n++) {
            String suffix = " (" + n + ")";
            name = (wanted.length() + suffix.length() > 32 ? wanted.substring(0, 32 - suffix.length()) : wanted) + suffix;
        }
        return name;
    }

    /** Saves a validated song for {@code uploader}: writes the file, registers it and logs it. */
    public Meta add(String name, Player uploader, String url, byte[] data, NbsSong song) throws IOException {
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        folder.mkdirs();
        Files.write(new File(folder, id + ".nbs").toPath(), data, StandardOpenOption.CREATE_NEW);
        Meta meta = new Meta(id, name, uploader.getName(), uploader.getUniqueId(), url, System.currentTimeMillis(),
                song.lengthSeconds(), data.length, "nbs");
        metas.put(id, meta);
        cache.put(id, song);
        save();
        log("ADDED    \"" + name + "\" by " + uploader.getName() + " (" + Chat.duration(meta.seconds) + ", "
                + Math.max(1, data.length / 1024) + " KB) from " + url);
        return meta;
    }

    /** Saves an audio song: the original MP3 and its ready-to-send frames, then registers and logs it. */
    public Meta addAudio(String name, Player uploader, String url, byte[] mp3, byte[][] frames) throws IOException {
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        folder.mkdirs();
        Files.write(new File(folder, id + ".mp3").toPath(), mp3, StandardOpenOption.CREATE_NEW);
        writeFrames(new File(folder, id + ".frames"), frames);
        Meta meta = new Meta(id, name, uploader.getName(), uploader.getUniqueId(), url, System.currentTimeMillis(),
                (int) Math.ceil(frames.length * (double) PcmConvert.FRAME_MILLIS / 1000.0), mp3.length, "mp3");
        metas.put(id, meta);
        frameCache.put(id, frames);
        save();
        log("ADDED    \"" + name + "\" (audio) by " + uploader.getName() + " (" + Chat.duration(meta.seconds) + ", "
                + Math.max(1, mp3.length / 1024) + " KB) from " + url);
        return meta;
    }

    /** The ready-to-send frames of an audio song, read from disk the first time; null if unavailable. Any thread. */
    public byte @Nullable [][] frames(String id) {
        byte[][] cached = frameCache.get(id);
        if (cached != null) {
            return cached;
        }
        Meta meta = metas.get(id);
        File file = new File(folder, id + ".frames");
        if (meta == null || !meta.active || !meta.isAudio() || !file.exists()) {
            return null;
        }
        try {
            byte[][] frames = readFrames(file);
            frameCache.put(id, frames);
            return frames;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read audio song " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static final int FRAMES_MAGIC = 0x524E4631; // "RNF1"

    static void writeFrames(File file, byte[][] frames) throws IOException {
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW)))) {
            out.writeInt(FRAMES_MAGIC);
            out.writeInt(frames.length);
            for (byte[] frame : frames) {
                out.writeShort(frame.length);
                out.write(frame);
            }
        }
    }

    static byte[][] readFrames(File file) throws IOException {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(file.toPath())))) {
            if (in.readInt() != FRAMES_MAGIC) {
                throw new IOException("not a frames file");
            }
            int count = in.readInt();
            if (count < 1 || count > 200_000) {
                throw new IOException("impossible frame count");
            }
            byte[][] frames = new byte[count][];
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedShort();
                frames[i] = new byte[length];
                in.readFully(frames[i]);
            }
            return frames;
        }
    }

    public boolean rename(Meta meta, String newName, String by) {
        String old = meta.name;
        meta.name = newName;
        save();
        log("RENAMED  \"" + old + "\" -> \"" + newName + "\" by " + by);
        return true;
    }

    /** Marks a song removed. Its file is moved aside so a mistake can be undone; discs of it stop playing. */
    public void remove(Meta meta, String by) {
        meta.active = false;
        cache.remove(meta.id);
        frameCache.remove(meta.id);
        for (String extension : List.of(".nbs", ".mp3", ".frames")) {
            File file = new File(folder, meta.id + extension);
            if (file.exists()) {
                try {
                    removedFolder.mkdirs();
                    Files.move(file.toPath(), new File(removedFolder, meta.id + extension).toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not move removed song file " + meta.id + extension, e);
                }
            }
        }
        save();
        log("REMOVED  \"" + meta.name + "\" by " + by);
    }

    /** The parsed song, read from disk on first use and then kept in a small cache; null if unavailable. */
    public @Nullable NbsSong load(String id) {
        NbsSong cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Meta meta = metas.get(id);
        File file = new File(folder, id + ".nbs");
        if (meta == null || !meta.active || !file.exists()) {
            return null;
        }
        try {
            NbsSong song = NbsSong.parse(Files.readAllBytes(file.toPath()), limits(), rules());
            cache.put(id, song);
            return song;
        } catch (IOException | NbsSong.InvalidSongException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read song " + id + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- history

    public void log(String line) {
        String entry = LocalDateTime.now().format(STAMP) + " " + line;
        plugin.getLogger().info("[songs] " + line);
        try {
            plugin.getDataFolder().mkdirs();
            Files.writeString(logFile.toPath(), entry + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write songs.log", e);
        }
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        metas.clear();
        if (!registry.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(registry);
        for (Map<?, ?> raw : yaml.getMapList("songs")) {
            try {
                Meta meta = new Meta(String.valueOf(raw.get("id")), String.valueOf(raw.get("name")),
                        String.valueOf(raw.get("uploader")), UUID.fromString(String.valueOf(raw.get("uploader-id"))),
                        String.valueOf(raw.get("url")), ((Number) raw.get("added")).longValue(),
                        ((Number) raw.get("seconds")).intValue(), ((Number) raw.get("bytes")).intValue(),
                        raw.get("kind") == null ? "nbs" : String.valueOf(raw.get("kind")));
                meta.active = Boolean.parseBoolean(String.valueOf(raw.get("active")));
                metas.put(meta.id, meta);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping malformed song entry: " + raw, e);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Meta meta : metas.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", meta.id);
            entry.put("name", meta.name);
            entry.put("uploader", meta.uploader);
            entry.put("uploader-id", meta.uploaderId.toString());
            entry.put("url", meta.url);
            entry.put("added", meta.added);
            entry.put("seconds", meta.seconds);
            entry.put("bytes", meta.bytes);
            entry.put("kind", meta.kind);
            entry.put("active", meta.active);
            list.add(entry);
        }
        yaml.set("songs", list);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(registry);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save songs.yml", e);
        }
    }

    // -------------------------------------------------------------- downloader

    /**
     * Fetches a song file on a background thread and hands the result to {@code done} on the main thread.
     * Only direct links are accepted: HTTPS (unless {@code songs.require-https} is off), a path ending in .nbs,
     * hosts that resolve to public addresses (unless {@code songs.allow-private-links} is on), at most three
     * redirects (each checked again), a short timeout and a hard size cap.
     */
    public void fetch(String link, Consumer<Download> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Download result = download(link);
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(result));
        });
    }

    Download download(String link) {
        try {
            URI uri = checkLink(link.trim());
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();
            int max = maxBytesFor(uri);
            for (int hop = 0; hop <= 3; hop++) {
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "RadioNetwork-songs").GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    response.body().close();
                    if (location == null) {
                        return new Download(null, "The link redirects nowhere.");
                    }
                    uri = checkLink(uri.resolve(location).toString());
                    max = maxBytesFor(uri);
                    continue;
                }
                if (status != 200) {
                    response.body().close();
                    return new Download(null, "The link answered with error " + status + ".");
                }
                try (InputStream body = response.body()) {
                    byte[] data = body.readNBytes(max + 1);
                    if (data.length > max) {
                        return new Download(null, "The file is larger than " + max / 1024 + " KB.");
                    }
                    return new Download(data, null);
                }
            }
            return new Download(null, "The link redirects too many times.");
        } catch (LinkException e) {
            return new Download(null, e.getMessage());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Download(null, "Could not download the file.");
        } catch (RuntimeException e) {
            return new Download(null, "That doesn't look like a valid link.");
        }
    }

    private static final class LinkException extends Exception {
        LinkException(String message) {
            super(message);
        }
    }

    private URI checkLink(String link) throws LinkException {
        URI uri;
        try {
            uri = URI.create(tidyLink(link));
        } catch (IllegalArgumentException e) {
            throw new LinkException("That doesn't look like a valid link. Paste the full address starting with https://");
        }
        boolean requireHttps = plugin.getConfig().getBoolean("songs.require-https", true);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || (!requireHttps && scheme.equals("http")))) {
            throw new LinkException("Only https links are accepted.");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new LinkException("That doesn't look like a valid link.");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (plugin.getConfig().getBoolean("songs.require-nbs-extension", true) && !path.endsWith(".nbs") && !path.endsWith(".mp3")) {
            throw new LinkException("The link must point straight at a .nbs or .mp3 file.");
        }
        if (!plugin.getConfig().getBoolean("songs.allow-private-links", false)) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (isPrivate(address)) {
                        throw new LinkException("That link points at a private address, which isn't allowed.");
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw new LinkException("Could not find that website.");
            }
        }
        return uri;
    }

    /**
     * Makes a pasted link parseable: surrounding whitespace and quotes go, spaces and other characters a URL
     * may not contain (non-ASCII letters, brackets...) are percent-encoded, and a Dropbox "dl=0" share link
     * becomes "dl=1" so it serves the file instead of a web page.
     */
    static String tidyLink(String link) {
        String text = link.trim();
        if (text.length() > 1 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("<") && text.endsWith(">"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean illegal = c <= 0x20 || c >= 0x7F || "\"<>\\^`{|}[]".indexOf(c) >= 0;
            if (illegal) {
                out.append('%').append(String.format("%02X", c));
            } else {
                out.append((char) c);
            }
        }
        String result = out.toString();
        if (result.toLowerCase(Locale.ROOT).contains("dropbox.com/") && result.contains("dl=0")) {
            result = result.replace("dl=0", "dl=1");
        }
        return result;
    }

    static boolean isPrivate(InetAddress address) {
        byte[] b = address.getAddress();
        boolean uniqueLocalV6 = b.length == 16 && (b[0] & 0xFE) == 0xFC;
        boolean carrierGradeNat = b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6 || carrierGradeNat;
    }

    /** All songs (active and removed) added by one player, newest last; for the admin list. */
    public List<Meta> by(String player) {
        return metas.values().stream().filter(m -> m.uploader.equalsIgnoreCase(player))
                .sorted(Comparator.comparingLong(m -> m.added)).toList();
    }
}
