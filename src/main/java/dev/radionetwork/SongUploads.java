package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a Blank Disc into a Song Disc: the player gives a name and a link, the song file is downloaded and
 * checked in the background, and only if it is accepted is the Blank Disc used up. Rejections are logged.
 */
public final class SongUploads {

    private final RadioNetworkPlugin plugin;
    private final SongLibrary songs;
    private final RadioItem items;
    private final Set<UUID> busy = new HashSet<>();

    public SongUploads(RadioNetworkPlugin plugin, SongLibrary songs, RadioItem items) {
        this.plugin = plugin;
        this.songs = songs;
        this.items = items;
    }

    /** Checks everything that can be checked before downloading; returns a message if the player cannot add a song now. */
    public String problem(Player player) {
        if (!player.hasPermission("radionetwork.songs.add")) {
            return "§cYou don't have permission to add songs.";
        }
        if (!items.isBlankDisc(player.getInventory().getItemInMainHand())) {
            return "§cHold a Blank Disc in your main hand to add a song.";
        }
        if (busy.contains(player.getUniqueId())) {
            return "§cYour last song is still being downloaded.";
        }
        boolean admin = player.hasPermission("radionetwork.admin");
        if (!admin && songs.activeCountBy(player.getUniqueId()) >= songs.maxPerPlayer()) {
            return "§cYou already have " + songs.maxPerPlayer() + " songs. Ask an admin to remove one, or remove one yourself.";
        }
        return null;
    }

    /** Starts adding a song; every outcome is reported to the player in chat. */
    public void begin(Player player, String rawName, String link) {
        String problem = problem(player);
        if (problem != null) {
            deny(player, problem);
            return;
        }
        String name = SongLibrary.cleanName(rawName);
        if (!name.isEmpty() && songs.isBanned(name)) {
            deny(player, "§cThat name isn't allowed. Choose another.");
            return;
        }
        String cleanLink = link.trim();
        if (cleanLink.isEmpty()) {
            deny(player, "§cPaste a direct link to an .mp3 file.");
            return;
        }
        busy.add(player.getUniqueId());
        Chat.send(player, "§7Downloading your song...");
        songs.fetch(cleanLink, download -> finish(player, name, cleanLink, download));
    }

    private void finish(Player player, String name, String link, SongLibrary.Download download) {
        busy.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        if (download.error() != null) {
            reject(player, link, download.error());
            return;
        }
        if (PcmConvert.looksLikeMp3(download.data())) {
            convertAudio(player, name, link, download.data());
            return;
        }
        NbsSong song;
        try {
            song = NbsSong.parse(download.data(), songs.limits(), songs.rules());
        } catch (NbsSong.InvalidSongException e) {
            reject(player, link, e.getMessage());
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!items.isBlankDisc(hand)) {
            deny(player, "§cYou are no longer holding a Blank Disc. Nothing was used.");
            return;
        }
        String chosen = name.isEmpty() ? SongLibrary.cleanName(song.title()) : name;
        if (chosen.isEmpty()) {
            chosen = "Untitled song";
        }
        if (songs.isBanned(chosen)) {
            reject(player, link, "that name isn't allowed");
            return;
        }
        chosen = songs.uniqueName(chosen);
        try {
            SongLibrary.Meta meta = songs.add(chosen, player, link, download.data(), song);
            if (player.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.isEmpty() ? null : hand);
            }
            Players.give(player, items.createSongDisc(meta));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
            Chat.send(player, "§aSong added: §f" + meta.name() + " §7(" + Chat.duration(meta.seconds()) + ")§a."
                    + customNote(song));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save a song: " + e.getMessage());
            deny(player, "§cThe song could not be saved. Nothing was used.");
        }
    }

    // ------------------------------------------------------------- audio (MP3)

    /** An MP3 needs voice chat: it is converted to voice frames in the background, then added like a song. */
    private void convertAudio(Player player, String name, String link, byte[] mp3) {
        AudioBackend backend = songs.audio();
        if (backend == null) {
            reject(player, link, "voice chat isn't running on this server, so audio files can't be added yet.");
            return;
        }
        busy.add(player.getUniqueId());
        Chat.send(player, "§7Converting your audio (this can take a few seconds)...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[][] frames = null;
            String error = null;
            try {
                frames = backend.encode(mp3, songs.limits().maxSeconds());
            } catch (AudioBackend.AudioException e) {
                error = e.getMessage();
            } catch (OutOfMemoryError e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Audio conversion ran out of memory (" + mp3.length + " byte MP3)", e);
                error = "the server ran out of memory converting it. Try a shorter or lower-quality file, or give the server more RAM.";
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Audio conversion failed (" + mp3.length + " byte MP3)", e);
                error = "the audio could not be converted (" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").";
            }
            byte[][] result = frames;
            String problem = error;
            Bukkit.getScheduler().runTask(plugin, () -> finishAudio(player, name, link, mp3, result, problem));
        });
    }

    private void finishAudio(Player player, String name, String link, byte[] mp3, byte[][] frames, String error) {
        busy.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        if (error != null) {
            reject(player, link, error);
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!items.isBlankDisc(hand)) {
            deny(player, "§cYou are no longer holding a Blank Disc. Nothing was used.");
            return;
        }
        String chosen = name.isEmpty() ? nameFromLink(link) : name;
        if (songs.isBanned(chosen)) {
            reject(player, link, "that name isn't allowed");
            return;
        }
        chosen = songs.uniqueName(chosen);
        try {
            SongLibrary.Meta meta = songs.addAudio(chosen, player, link, mp3, frames);
            if (player.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
                player.getInventory().setItemInMainHand(hand.isEmpty() ? null : hand);
            }
            Players.give(player, items.createSongDisc(meta));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
            Chat.send(player, "§aSong added: §f" + meta.name() + " §7(" + Chat.duration(meta.seconds()) + ", audio)§a.");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not save an audio song: " + e.getMessage());
            deny(player, "§cThe song could not be saved. Nothing was used.");
        }
    }

    /** A readable name from the file name in the link ("my_song.mp3" becomes "my song"). */
    static String nameFromLink(String link) {
        String path = link;
        int cut = path.indexOf('?');
        path = cut >= 0 ? path.substring(0, cut) : path;
        path = path.substring(path.lastIndexOf('/') + 1);
        int dot = path.lastIndexOf('.');
        path = dot >= 0 ? path.substring(0, dot) : path;
        String cleaned = SongLibrary.cleanName(path.replace('_', ' ').replace('-', ' '));
        return cleaned.isEmpty() ? "Untitled song" : cleaned;
    }

    /** What happened to notes that use custom instruments, or nothing when the song has none. */
    private static String customNote(NbsSong song) {
        int total = song.matchedCustom() + song.fallbackCustom() + song.skippedCustom();
        if (total == 0) {
            return "";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (song.matchedCustom() > 0) {
            parts.add(song.matchedCustom() + " matched to a similar instrument");
        }
        if (song.fallbackCustom() > 0) {
            parts.add(song.fallbackCustom() + " played with the fallback instrument");
        }
        if (song.skippedCustom() > 0) {
            parts.add(song.skippedCustom() + " left out");
        }
        return " §7Custom-instrument notes: " + String.join(", ", parts) + ".";
    }

    private void reject(Player player, String link, String reason) {
        songs.log("REJECTED link by " + player.getName() + ": " + reason + " (" + link + ")");
        deny(player, "§cSong not added: " + reason + " Your Blank Disc was not used.");
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
