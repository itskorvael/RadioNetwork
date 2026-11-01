package dev.radionetwork;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Throttles password guessing for private frequencies, shared by terminals and Pocket Receivers.
 * Wrong guesses cost a growing wait: 3 seconds for the first two, then 1 minute, then 5 minutes after
 * six in a row. A correct password clears it, and so does ten quiet minutes.
 */
public final class PasswordGuard {

    private static final long FORGET_AFTER_MILLIS = 10 * 60_000L;

    private record State(int failures, long lockedUntil, long lastFailure) {
    }

    private final Map<UUID, State> states = new HashMap<>();

    /** Milliseconds this player must still wait before another guess; 0 when they may try now. */
    public long waitMillis(UUID player) {
        State state = states.get(player);
        return state == null ? 0 : Math.max(0, state.lockedUntil() - System.currentTimeMillis());
    }

    public void failure(UUID player) {
        long now = System.currentTimeMillis();
        State previous = states.get(player);
        int failures = previous == null || now - previous.lastFailure() > FORGET_AFTER_MILLIS ? 1 : previous.failures() + 1;
        long delay = failures < 3 ? 3_000L : failures < 6 ? 60_000L : 300_000L;
        states.put(player, new State(failures, now + delay, now));
    }

    public void success(UUID player) {
        states.remove(player);
    }

    public void forget(UUID player) {
        states.remove(player);
    }

    /** "12s" or "3m 5s", rounded up, for messages. */
    public static String describe(long millis) {
        long seconds = (millis + 999) / 1000;
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
