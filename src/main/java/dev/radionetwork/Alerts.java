package dev.radionetwork;

import net.kyori.adventure.audience.Audience;

/**
 * The one look every alert in the plugin shares: a small coloured topic tag, then the message in plain grey.
 * <pre>
 *   [Security] Unusual activity on WX.                                           (yellow tag)
 *   [Power] Your terminal on WX was struck by lightning and went offline.        (red tag, red text)
 *   [Signal] Signal back on WX.                                                  (green tag)
 * </pre>
 * The topics are a small fixed set, so one kind of event always has the same tag: Signal (what you hear: weak,
 * lost, back, interference, jammed), Power (outages, lightning, fuel), Access (passwords and permissions),
 * Security (scanners and decoding, for the people being listened to), Jammer (a jammer's own status, for its
 * owner) and Weather. The colour says how serious it is.
 * Yellow is a warning, red is damage or danger, and green says it is over. Only red alerts colour the whole line,
 * so danger is the one thing that stands out; nothing is bold. Ordinary radio traffic (messages, "has tuned in",
 * music) is not an alert and keeps its own look (see {@link Chat#tag}).
 */
public final class Alerts {

    public enum Level {
        RED("§c"),
        YELLOW("§e"),
        CLEAR("§a");

        private final String color;

        Level(String color) {
            this.color = color;
        }

        public String color() {
            return color;
        }

        /** Colour of the message itself: grey, except that danger is red all the way through. */
        String body() {
            return this == RED ? "§c" : "§7";
        }
    }

    /** Marks where a highlighted value ends; {@link #line} turns it back into the alert's own text colour. */
    private static final String BACK = "\u0001";

    private Alerts() {
    }

    /** A value (a channel name, a number) shown in white inside an alert's text. */
    public static String name(Object value) {
        return "§f" + value + BACK;
    }

    /** The finished alert line; {@code text} may use {@link #name}. */
    public static String line(Level level, String topic, String text) {
        return "§8[" + level.color + topic + "§8] " + level.body() + text.replace(BACK, level.body());
    }

    public static void send(Audience audience, Level level, String topic, String text) {
        Chat.send(audience, line(level, topic, text));
    }
}
