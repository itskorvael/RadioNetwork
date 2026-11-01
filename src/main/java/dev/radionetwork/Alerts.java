package dev.radionetwork;

import net.kyori.adventure.audience.Audience;

import java.util.Locale;

/**
 * The one look every alert in the plugin shares: a coloured icon, a topic, then the message.
 * <pre>
 *   ⚠ SCANNER: Someone is scanning frequency WX.                                (yellow)
 *   ✖ LIGHTNING: Your terminal on WX was struck and is offline for about 20 seconds.  (red)
 *   ✔ INTERFERENCE: Interference cleared. Signal restored on WX.                (green)
 * </pre>
 * Yellow is a warning, red is damage or danger, and green says it is over; each level has its own icon, so the
 * level does not depend on telling colours apart. Ordinary radio traffic (messages, "has tuned in", music) is not
 * an alert and keeps its own look.
 */
public final class Alerts {

    public enum Level {
        RED("§c", "✖"),
        YELLOW("§e", "⚠"),
        CLEAR("§a", "✔");

        private final String color;
        private final String icon;

        Level(String color, String icon) {
            this.color = color;
            this.icon = icon;
        }

        public String color() {
            return color;
        }
    }

    /** Marks where a highlighted value ends; {@link #line} turns it back into the alert's own colour. */
    private static final String BACK = "\u0001";

    private Alerts() {
    }

    /** A value (a channel name, a number) shown in white inside an alert's text. */
    public static String name(Object value) {
        return "§f" + value + BACK;
    }

    /** The finished alert line; {@code text} may use {@link #name}. */
    public static String line(Level level, String topic, String text) {
        return level.color + "§l" + level.icon + " §f§l" + topic.toUpperCase(Locale.ROOT)
                + "§8: " + level.color + text.replace(BACK, level.color);
    }

    public static void send(Audience audience, Level level, String topic, String text) {
        Chat.send(audience, line(level, topic, text));
    }
}
