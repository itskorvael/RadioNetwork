package dev.radionetwork.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.mp3.Mp3Decoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import dev.radionetwork.AudioBackend;
import dev.radionetwork.Chat;
import dev.radionetwork.Mp3Fallback;
import dev.radionetwork.PcmConvert;
import dev.radionetwork.SongLibrary;
import dev.radionetwork.Radio;
import dev.radionetwork.RadioManager;
import dev.radionetwork.RadioNetworkPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Voice half of the network. Voice follows the same routing rules as {@code /broadcast}: the main
 * thread (every 10 ticks) decides who may transmit and who hears them, and the voice thread only
 * reads those snapshots, so no Bukkit state is touched from voice-chat threads.
 */
public final class RadioVoicePlugin implements VoicechatPlugin, AudioBackend {

    /** A player counts as speaking while their last mic packet is younger than this. */
    private static final long SPEAKING_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(600);
    private static final long RELAY_IDLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long LOOP_TICKS = 10;

    /** What the main thread decided about a player standing at a usable terminal. */
    private record Snapshot(String channel, List<UUID> listeners, Map<UUID, Float> loss) {
    }

    private record Relay(StaticAudioChannel channel, long lastUsedNanos) {
    }

    private static RadioVoicePlugin instance;

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final SongLibrary songs;
    private volatile VoicechatApi baseApi;

    private volatile VoicechatServerApi api;
    private BukkitTask loop;

    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPacket = new ConcurrentHashMap<>();
    private final Map<String, Relay> relays = new ConcurrentHashMap<>();
    private final Map<String, Group> groupsByChannel = new ConcurrentHashMap<>();
    private final Set<UUID> groupIds = ConcurrentHashMap.newKeySet();

    // Main-thread only.
    private final Set<UUID> wasSpeaking = new HashSet<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();

    private RadioVoicePlugin(RadioNetworkPlugin plugin, RadioManager manager, SongLibrary songs) {
        this.plugin = plugin;
        this.manager = manager;
        this.songs = songs;
    }

    public static void register(RadioNetworkPlugin plugin, RadioManager manager, SongLibrary songs) {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat is installed but its API service is unavailable.");
            return;
        }
        instance = new RadioVoicePlugin(plugin, manager, songs);
        service.registerPlugin(instance);
        plugin.getLogger().info("Registered with Simple Voice Chat.");
    }

    public static void shutdown() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    // ------------------------------------------------------------ plugin hooks

    @Override
    public String getPluginId() {
        return "radionetwork";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        this.baseApi = voicechatApi;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> stop());
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> forget(event.getPlayerUuid()));
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        api = event.getVoicechat();
        startAudioPlayer();
        songs.audio(this);
        if (loop == null) {
            loop = new BukkitRunnable() {
                @Override
                public void run() {
                    tick();
                }
            }.runTaskTimer(plugin, LOOP_TICKS, LOOP_TICKS);
        }
    }

    private void stop() {
        songs.audio(null);
        stopAudioPlayer();
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
        VoicechatServerApi server = api;
        if (server != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                VoicechatConnection connection = server.getConnectionOf(player.getUniqueId());
                if (connection != null && inRadioGroup(connection)) {
                    connection.setGroup(null);
                }
            }
            groupIds.forEach(server::removeGroup);
        }
        groupIds.clear();
        groupsByChannel.clear();
        snapshots.clear();
        relays.clear();
        wasSpeaking.clear();
        api = null;
    }

    private void forget(UUID player) {
        snapshots.remove(player);
        lastPacket.remove(player);
        lastAlert.remove(player);
        relays.keySet().removeIf(key -> key.startsWith(player + ">") || key.endsWith(">" + player));
    }

    // -------------------------------------------------- voice thread: packets

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID id = sender.getPlayer().getUuid();
        long now = System.nanoTime();
        lastPacket.put(id, now);

        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null) {
            // Not at a usable terminal. Someone still sitting in a radio group is gagged until the
            // main-thread loop ejects them (privacy mode, fuel, or range failed).
            if (inRadioGroup(sender) && event.isCancellable()) {
                event.cancel();
            }
            return;
        }
        // Radio speech only travels through the network, never as ordinary proximity or group audio.
        if (event.isCancellable()) {
            event.cancel();
        }
        relay(id, snapshot, event.getPacket(), now);
    }

    private void relay(UUID speaker, Snapshot snapshot, MicrophonePacket packet, long now) {
        VoicechatServerApi server = api;
        if (server == null) {
            return;
        }
        for (UUID listener : snapshot.listeners()) {
            VoicechatConnection target = server.getConnectionOf(listener);
            if (target == null || !target.isConnected() || target.isDisabled()) {
                continue;
            }
            Float loss = snapshot.loss().get(listener);
            if (loss != null && java.util.concurrent.ThreadLocalRandom.current().nextFloat() < loss) {
                continue; // static: this packet never reaches a listener with a weak signal
            }
            String key = speaker + ">" + listener;
            Relay relay = relays.compute(key, (k, existing) -> {
                StaticAudioChannel channel = existing != null ? existing.channel() : null;
                if (channel == null || channel.isClosed()) {
                    channel = server.createStaticAudioChannel(UUID.randomUUID());
                    if (channel == null) {
                        return null;
                    }
                    channel.setBypassGroupIsolation(true);
                    channel.addTarget(target);
                }
                return new Relay(channel, now);
            });
            if (relay != null) {
                relay.channel().send(packet.getOpusEncodedData());
            }
        }
    }

    private void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        if (group == null || !groupIds.contains(group.getId())) {
            return;
        }
        UUID id = event.getConnection().getPlayer().getUuid();
        Snapshot snapshot = snapshots.get(id);
        Long last = lastPacket.get(id);
        boolean talking = last != null && System.nanoTime() - last < SPEAKING_WINDOW_NANOS;
        if (snapshot == null || !snapshot.channel().equals(group.getName()) || !talking) {
            event.cancel();
        }
    }

    private boolean inRadioGroup(VoicechatConnection connection) {
        Group group = connection.getGroup();
        return group != null && groupIds.contains(group.getId());
    }

    // ------------------------------------------------ main thread: enforcement

    private void tick() {
        VoicechatServerApi server = api;
        if (server == null) {
            return;
        }
        long now = System.nanoTime();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            VoicechatConnection connection = server.getConnectionOf(id);
            if (connection == null || !connection.isInstalled() || !connection.isConnected() || connection.isDisabled()) {
                snapshots.remove(id);
                wasSpeaking.remove(id);
                continue;
            }

            Long last = lastPacket.get(id);
            boolean speaking = last != null && now - last < SPEAKING_WINDOW_NANOS;
            Radio radio = manager.findTransmitter(player).radio();

            if (radio == null) {
                // Out of range, unpowered, or CLOSED to this player: they may only listen.
                snapshots.remove(id);
                wasSpeaking.remove(id);
                eject(connection);
                continue;
            }

            // Terminal listeners, plus Pocket Receiver holders (and, without an Earpiece, the people beside
            // them) when portable voice is on, so voice reaches exactly who text does.
            boolean portableVoice = plugin.getConfig().getBoolean("portable.voice", true);
            Set<Player> heardNormally = portableVoice ? manager.listenersOf(radio) : manager.towerListenersOf(radio);
            List<UUID> listeners = new java.util.ArrayList<>(heardNormally.stream()
                    .map(Player::getUniqueId)
                    .filter(listener -> !listener.equals(id))
                    .toList());
            Map<UUID, Float> loss = new java.util.HashMap<>(staticLoss(radio, portableVoice));
            // Signal Scanners hear it too: clear on an open frequency, broken up on an encrypted one not yet decoded.
            if (portableVoice && manager.scanners() != null) {
                for (var scanner : manager.scanners().hearing(radio, heardNormally)) {
                    UUID scannerId = scanner.player().getUniqueId();
                    if (!scannerId.equals(id)) {
                        listeners.add(scannerId);
                        loss.merge(scannerId, scanner.voiceLoss(), Math::max);
                    }
                }
            }
            snapshots.put(id, new Snapshot(radio.channel(), listeners, loss));

            if (!speaking) {
                wasSpeaking.remove(id);
                eject(connection);
                continue;
            }

            ensureInGroup(server, connection, radio.channel());
            manager.pulseRelays(radio);
            if (wasSpeaking.add(id)) {
                announce(player, radio, now);
            }
        }

        relays.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastUsedNanos() > RELAY_IDLE_NANOS) {
                entry.getValue().channel().flush();
                return true;
            }
            return false;
        });
    }

    /**
     * Static: the weaker a listener's signal, the larger the share of voice packets dropped for them. A
     * Pocket Receiver's signal comes from distance and depth; a person at a terminal hears clean audio
     * unless the signal reached that terminal through relays, each of which costs a bar.
     */
    private Map<UUID, Float> staticLoss(Radio radio, boolean portableVoice) {
        var portables = manager.portables();
        if (portables == null) {
            return Map.of();
        }
        Map<UUID, Float> loss = new java.util.HashMap<>();
        manager.relayBars(radio).forEach((player, bars) -> {
            float share = portables.staticLoss(bars);
            if (share > 0) {
                loss.put(player, share);
            }
        });
        if (!portableVoice) {
            return loss;
        }
        Set<UUID> atTerminal = new HashSet<>();
        manager.towerListenersOf(radio).forEach(player -> atTerminal.add(player.getUniqueId()));
        manager.portableBars(radio).forEach((player, bars) -> {
            float share = portables.staticLoss(bars);
            if (share > 0 && !atTerminal.contains(player)) {
                loss.put(player, share);
            }
        });
        return loss;
    }

    private void ensureInGroup(VoicechatServerApi server, VoicechatConnection connection, String channel) {
        Group current = connection.getGroup();
        if (current != null && groupIds.contains(current.getId()) && current.getName().equals(channel)) {
            return;
        }
        Group group = groupsByChannel.computeIfAbsent(channel, name -> {
            Group created = server.groupBuilder()
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .setHidden(true)
                    .build();
            if (created != null) {
                groupIds.add(created.getId());
            }
            return created;
        });
        if (group != null) {
            connection.setGroup(group);
        }
    }

    private void eject(VoicechatConnection connection) {
        if (inRadioGroup(connection)) {
            connection.setGroup(null);
        }
    }

    /** Text parity: listeners without voice chat still learn that someone is on the air. */
    private void announce(Player speaker, Radio radio, long now) {
        long cooldown = TimeUnit.MILLISECONDS.toNanos(
                (long) (plugin.getConfig().getDouble("voice.alert-cooldown-seconds", 10.0) * 1000));
        Long previous = lastAlert.get(speaker.getUniqueId());
        if (previous != null && now - previous < cooldown) {
            return;
        }
        lastAlert.put(speaker.getUniqueId(), now);
        String line = "§e§l[RADIO - " + radio.channel() + "] §7" + speaker.getName() + " is speaking on this frequency.";
        java.util.Set<Player> targets = new java.util.LinkedHashSet<>(manager.listenersOf(radio));
        // For testing, the speaker can see their own alert too (even when they only hold a Pocket Receiver).
        if (plugin.getConfig().getBoolean("voice.speaker-alert-self", true)) {
            targets.add(speaker);
        } else {
            targets.remove(speaker);
        }
        targets.forEach(target -> Chat.send(target, line));
        if (manager.scanners() != null) {
            manager.scanners().speaking(radio, speaker.getName(), targets);
        }
    }

    // ============================================================ audio songs (MP3)

    /** One audio song being sent: when it started, who gets it and how much static each of them gets. */
    private static final class Playback {
        volatile String songId;
        volatile long startedAt;
        volatile Map<UUID, Float> loss;
        volatile long sent = -1;

        Playback(Playing playing) {
            update(playing);
        }

        void update(Playing playing) {
            if (!playing.songId().equals(songId) || playing.startedAt() != startedAt) {
                sent = -1;          // a new round: start again from its first frame
            }
            songId = playing.songId();
            startedAt = playing.startedAt();
            loss = playing.loss();
        }
    }

    private final Map<String, Playback> playbacks = new ConcurrentHashMap<>();
    private final Map<String, StaticAudioChannel> audioChannels = new ConcurrentHashMap<>();
    private volatile java.util.concurrent.ScheduledExecutorService audioPlayer;
    /** Only one MP3 is converted at a time, so several uploads cannot pile up in memory. */
    private static final java.util.concurrent.Semaphore ENCODING = new java.util.concurrent.Semaphore(1);

    private void startAudioPlayer() {
        if (audioPlayer != null) {
            return;
        }
        var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RadioNetwork-audio");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::sendFrames, 20, PcmConvert.FRAME_MILLIS, TimeUnit.MILLISECONDS);
        audioPlayer = executor;
    }

    private void stopAudioPlayer() {
        var executor = audioPlayer;
        audioPlayer = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        playbacks.clear();
        audioChannels.clear();
    }

    @Override
    public boolean ready() {
        return api != null && audioPlayer != null;
    }

    @Override
    public void play(String key, Playing playing) {
        playbacks.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Playback(playing);
            }
            existing.update(playing);
            return existing;
        });
    }

    @Override
    public void stop(String key) {
        playbacks.remove(key);
        audioChannels.keySet().removeIf(channelKey -> channelKey.startsWith(key + "|"));
    }

    /**
     * Turns an MP3 into 48 kHz mono 20 ms Opus frames. The song is decoded once here; playing afterwards is
     * just sending the frame the shared clock points at, so there is nothing to decode or seek while it plays.
     */
    @Override
    public byte[][] encode(byte[] mp3, int maxSeconds) throws AudioException {
        VoicechatApi base = baseApi;
        if (base == null) {
            throw new AudioException("voice chat isn't running.");
        }
        try {
            ENCODING.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioException("the conversion was interrupted.");
        }
        try {
            // Voice chat's own decoder is native (LAME) and returns null where that library does not work.
            // Then, or when it cannot read the file, the bundled pure-Java decoder takes over.
            byte[] plain = PcmConvert.stripTags(mp3);
            short[] pcm = null;
            int channels = 1;
            float sampleRate = PcmConvert.RATE;
            Mp3Decoder decoder = base.createMp3Decoder(new java.io.ByteArrayInputStream(plain));
            if (decoder != null) {
                try {
                    int bitrate = decoder.getBitrate();
                    if (bitrate > 0 && mp3.length * 8.0 / bitrate > maxSeconds + 5) {
                        throw new AudioException("the song is longer than " + maxSeconds / 60 + " minutes.");
                    }
                    javax.sound.sampled.AudioFormat format = decoder.getAudioFormat();
                    pcm = decoder.decode();
                    channels = Math.max(1, format.getChannels());
                    sampleRate = format.getSampleRate();
                } catch (java.io.IOException e) {
                    pcm = null;
                }
            }
            if (pcm == null) {
                try {
                    Mp3Fallback.Audio audio = Mp3Fallback.decode(plain, maxSeconds);
                    pcm = audio.pcm();
                    channels = audio.channels();
                    sampleRate = audio.sampleRate();
                } catch (Mp3Fallback.TooLongException e) {
                    throw new AudioException(e.getMessage());
                } catch (java.io.IOException e) {
                    throw new AudioException("that isn't a readable MP3 file.");
                }
            }
            double seconds = (double) pcm.length / channels / sampleRate;
            if (seconds > maxSeconds) {
                throw new AudioException("the song is longer than " + maxSeconds / 60 + " minutes.");
            }
            if (seconds < 1) {
                throw new AudioException("the song is too short.");
            }
            short[][] chunks = PcmConvert.frames(PcmConvert.toMono48k(pcm, channels, sampleRate));
            pcm = null;
            OpusEncoder encoder = base.createEncoder(OpusEncoderMode.AUDIO);
            try {
                byte[][] frames = new byte[chunks.length][];
                for (int i = 0; i < chunks.length; i++) {
                    frames[i] = encoder.encode(chunks[i]);
                }
                return frames;
            } finally {
                encoder.close();
            }
        } finally {
            ENCODING.release();
        }
    }

    /**
     * Every 20 ms: for each audio song that is playing, send the frame the shared clock points at to each
     * of its listeners. A listener who joins late simply gets the frames from the current moment, so everyone
     * hears the same point of the song. A listener with a weak signal has a share of frames dropped (static).
     */
    private void sendFrames() {
        try {
            VoicechatServerApi server = api;
            if (server == null) {
                return;
            }
            var random = java.util.concurrent.ThreadLocalRandom.current();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Playback> entry : playbacks.entrySet()) {
                Playback playback = entry.getValue();
                byte[][] frames = songs.frames(playback.songId);
                long index = (now - playback.startedAt) / PcmConvert.FRAME_MILLIS;
                if (frames == null || index < 0 || index >= frames.length) {
                    continue;   // not available, or the song is over and the main thread is about to start the next
                }
                // Send the current frame, and at most two it fell behind on (a hiccup must not become a burst).
                long from = Math.max(playback.sent + 1, index - 2);
                Map<UUID, Float> listeners = playback.loss;
                for (long i = from; i <= index; i++) {
                    for (Map.Entry<UUID, Float> listener : listeners.entrySet()) {
                        if (listener.getValue() > 0 && random.nextFloat() < listener.getValue()) {
                            continue;
                        }
                        VoicechatConnection connection = server.getConnectionOf(listener.getKey());
                        if (connection == null || !connection.isConnected() || connection.isDisabled()) {
                            continue;
                        }
                        String channelKey = entry.getKey() + "|" + listener.getKey();
                        StaticAudioChannel channel = audioChannels.computeIfAbsent(channelKey, k -> {
                            StaticAudioChannel created = server.createStaticAudioChannel(UUID.randomUUID());
                            if (created != null) {
                                created.setBypassGroupIsolation(true);
                                created.addTarget(connection);
                            }
                            return created;
                        });
                        if (channel != null) {
                            channel.send(frames[(int) i]);
                        }
                    }
                }
                playback.sent = index;
                String prefix = entry.getKey() + "|";
                audioChannels.keySet().removeIf(k -> k.startsWith(prefix)
                        && !listeners.containsKey(UUID.fromString(k.substring(prefix.length()))));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Audio player hiccup: " + e);   // keep the timer alive
        }
    }
}
