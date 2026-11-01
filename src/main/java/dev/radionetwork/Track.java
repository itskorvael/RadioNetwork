package dev.radionetwork;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/** One entry in a jukebox queue: a vanilla music disc, or a player-added note-block song. */
public record Track(@Nullable Material disc, @Nullable String songId) {

    public static Track vanilla(Material disc) {
        return new Track(disc, null);
    }

    public static Track song(String id) {
        return new Track(null, id);
    }

    public boolean isSong() {
        return songId != null;
    }

    /** Stable text form used when saving: {@code disc:MUSIC_DISC_CAT} or {@code song:ab12cd34}. */
    public String key() {
        return isSong() ? "song:" + songId : "disc:" + disc.name();
    }

    /** Reads {@link #key()} back; a bare material name (the older save format) is accepted too. */
    public static Track parse(String text) {
        if (text.startsWith("song:")) {
            return song(text.substring("song:".length()));
        }
        return vanilla(Material.valueOf(text.startsWith("disc:") ? text.substring("disc:".length()) : text));
    }
}
