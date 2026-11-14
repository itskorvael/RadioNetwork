package dev.radionetwork;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Small helpers for the section-sign formatted strings used throughout the plugin. */
public final class Chat {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Chat() {
    }

    public static Component text(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    /** Item names/lore: legacy text without the default italics. */
    public static Component item(String legacy) {
        return text(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static void send(Audience audience, String legacy) {
        audience.sendMessage(text(legacy));
    }

    /** The tag in front of radio traffic: a small coloured label and the channel, nothing bold. */
    public static String tag(String color, String label, String channel) {
        return "§8[" + color + label + "§8 - §f" + channel + "§8] ";
    }

    /** The same tag without a channel (the scanner's own readouts). */
    public static String tag(String color, String label) {
        return "§8[" + color + label + "§8] ";
    }

    /** Strips formatting characters and surrounding whitespace from player-written text. */
    public static String clean(String input) {
        return input.replace("§", "").replaceAll("\\p{Cntrl}", "").trim();
    }

    public static String duration(long seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
