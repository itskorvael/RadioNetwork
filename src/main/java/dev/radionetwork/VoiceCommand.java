package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /whisper} (also {@code /w}) and {@code /shout}: the quiet and loud ends of local chat. Ordinary chat carries
 * {@code local-chat.radius}; a whisper only a few blocks, with people just outside it seeing that something was
 * said; a shout much further, clear up close and breaking up toward its edge, and from beyond ordinary speaking
 * distance the speaker is only "a voice". Shouting has a cooldown. Like {@code /broadcast}, these are commands and
 * not chat, so they do not go through other chat plugins.
 */
public final class VoiceCommand implements CommandExecutor {

    public enum Tier { WHISPER, SHOUT }

    /** What a listener gets: nothing, the words, or only the sight of a whisper. */
    public enum Reach { NONE, FULL, CUE }

    /** The distances in play, in blocks. */
    public record Ranges(double normal, double vertical, double whisper, double shout, double clear) {
    }

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final Map<UUID, Long> lastShout = new HashMap<>();

    public VoiceCommand(RadioNetworkPlugin plugin, RadioManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public Ranges ranges() {
        var c = plugin.getConfig();
        double normal = Math.max(1.0, c.getDouble("local-chat.radius", 32.0));
        double whisper = Math.max(1.0, c.getDouble("local-chat.whisper.radius", 6.0));
        double shout = Math.max(normal, c.getDouble("local-chat.shout.radius", 64.0));
        double clear = Math.min(shout, Math.max(0.0, c.getDouble("local-chat.shout.clear-radius", 40.0)));
        return new Ranges(normal, Math.max(1.0, c.getDouble("local-chat.vertical", 32.0)), whisper, shout, clear);
    }

    /** How far a tier carries. */
    public static double rangeOf(Tier tier, Ranges r) {
        return tier == Tier.WHISPER ? r.whisper() : r.shout();
    }

    /**
     * What someone {@code horizontal} blocks sideways and {@code dy} blocks up or down from the speaker gets of a
     * message at this tier. A whisper is heard within its range; out to the ordinary talking range it is only seen
     * (when {@code cue} is on); beyond that nothing. A shout is heard out to its range.
     */
    public static Reach reach(Tier tier, double horizontal, double dy, Ranges r, boolean cue) {
        if (Math.abs(dy) > r.vertical()) {
            return Reach.NONE;
        }
        if (tier == Tier.SHOUT) {
            return horizontal <= r.shout() ? Reach.FULL : Reach.NONE;
        }
        if (horizontal <= r.whisper()) {
            return Reach.FULL;
        }
        return cue && horizontal <= r.normal() ? Reach.CUE : Reach.NONE;
    }

    /** Signal bars a shout carries at this distance: 4 (clean) up to {@code clear}, falling to 1 at the shout's edge. */
    public static int bars(double horizontal, Ranges r) {
        if (horizontal <= r.clear() || r.shout() <= r.clear()) {
            return 4;
        }
        double share = Math.min(1.0, (horizontal - r.clear()) / (r.shout() - r.clear()));
        return Math.max(1, 4 - (int) Math.floor(share * 3.0));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player speaker)) {
            Chat.send(sender, "§cOnly players can do that.");
            return true;
        }
        Tier tier = command.getName().equalsIgnoreCase("shout") ? Tier.SHOUT : Tier.WHISPER;
        String verb = tier == Tier.SHOUT ? "shout" : "whisper";
        if (!plugin.getConfig().getBoolean("local-chat.enabled", true)) {
            Chat.send(speaker, "§cLocal chat is off, so everyone already hears you.");
            return true;
        }
        String text = Chat.clean(String.join(" ", args));
        if (text.isEmpty()) {
            Chat.send(speaker, "§cUsage: /" + command.getName() + " <message>");
            return true;
        }
        if (tier == Tier.SHOUT) {
            long wait = shoutCooldownLeft(speaker.getUniqueId());
            if (wait > 0) {
                Chat.send(speaker, "§cWait " + String.format("%.1f", wait / 1000.0) + "s before shouting again.");
                return true;
            }
        }

        Ranges r = ranges();
        boolean cue = plugin.getConfig().getBoolean("local-chat.whisper.notice", true);
        boolean anonymous = plugin.getConfig().getBoolean("local-chat.shout.anonymous", true);
        Location from = speaker.getLocation();
        boolean heard = false;
        for (Player listener : Bukkit.getOnlinePlayers()) {
            if (listener.equals(speaker)) {
                continue;
            }
            boolean spy = listener.hasPermission(LocalChat.SPY);
            Location at = listener.getLocation();
            if (!spy && !from.getWorld().equals(at.getWorld())) {
                continue;
            }
            double horizontal = from.getWorld().equals(at.getWorld()) ? Math.hypot(from.getX() - at.getX(), from.getZ() - at.getZ()) : 0;
            Reach got = spy ? Reach.FULL : reach(tier, horizontal, from.getY() - at.getY(), r, cue);
            switch (got) {
                case NONE -> { }
                case CUE -> Chat.send(listener, "§7" + speaker.getName() + " " + verb + "s something.");
                case FULL -> {
                    heard |= !spy;
                    boolean named = spy || tier == Tier.WHISPER || !anonymous || horizontal <= r.normal();
                    String words = tier == Tier.SHOUT && !spy ? manager.garble(text, bars(horizontal, r)) : text;
                    Chat.send(listener, "§7" + (named ? speaker.getName() : "A voice") + " " + verb + "s: §f" + words);
                }
            }
        }
        Chat.send(speaker, "§7You " + verb + ": §f" + text);
        Chat.send(Bukkit.getConsoleSender(), "§8[" + (tier == Tier.SHOUT ? "Shout" : "Whisper") + "] §7" + speaker.getName() + ": " + text);
        if (tier == Tier.SHOUT) {
            lastShout.put(speaker.getUniqueId(), System.currentTimeMillis());
        }
        if (!heard && plugin.getConfig().getBoolean("local-chat.lonely-notice", true)) {
            speaker.sendActionBar(Chat.text("§7No one is close enough to hear you."));
        }
        return true;
    }

    private long shoutCooldownLeft(UUID player) {
        Long last = lastShout.get(player);
        if (last == null) {
            return 0;
        }
        long cooldown = (long) (plugin.getConfig().getDouble("local-chat.shout.cooldown-seconds", 5.0) * 1000);
        return Math.max(0, last + cooldown - System.currentTimeMillis());
    }
}
