package dev.radionetwork;

import java.util.Map;
import java.util.UUID;

/**
 * What the music system needs from voice chat to play real audio (MP3) songs. Implemented by the voice-chat
 * side of the plugin, so nothing outside {@code dev.radionetwork.voice} touches the voice-chat classes; when
 * the voice-chat plugin is absent there is no backend and audio songs simply cannot be added or played.
 */
public interface AudioBackend {

    /** A failure the player can be told about. */
    final class AudioException extends Exception {
        public AudioException(String message) {
            super(message);
        }
    }

    /**
     * One audio song playing for some listeners. {@code startedAt} is the shared clock (milliseconds) at which
     * the song began, so the frame to send is always {@code (now - startedAt) / 20}: late listeners are in sync.
     * {@code loss} maps every listener to the share (0-1) of frames to drop for them, which is the static at
     * a weak signal; people at a terminal map to 0.
     */
    record Playing(String songId, long startedAt, Map<UUID, Float> loss) {
    }

    /** True when voice chat is running and audio can be encoded and sent. */
    boolean ready();

    /**
     * Decodes an MP3 and turns it into ready-to-send 20 ms voice frames (48 kHz mono, Opus encoded).
     * This is heavy: call it away from the main thread.
     */
    byte[][] encode(byte[] mp3, int maxSeconds) throws AudioException;

    /** Starts or updates the playback for one jukebox (keyed by its position). Main thread. */
    void play(String key, Playing playing);

    /** Stops the playback for one jukebox. Main thread. */
    void stop(String key);
}
