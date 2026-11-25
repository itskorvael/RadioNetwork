package dev.radionetwork;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Local chat: ordinary chat is only heard by players close to the speaker, in the same world. Anything sent over a
 * radio (a terminal's /broadcast, voice, station announcements) is not chat and is not affected: crossing a long
 * distance is what a radio is for.
 * <p>
 * Chat that another handler has already taken (the private answers to the terminal's text prompts) is left alone.
 * The console still sees everything, and so do players with {@link #SPY}; players with {@link #GLOBAL} are heard
 * by everyone. Both default to op, so ops bypass local chat in both directions.
 */
public final class LocalChat implements Listener {

    /** Chat from a player with this permission reaches everyone, whatever the distance. */
    public static final String GLOBAL = "radionetwork.localchat.global";
    /** A player with this permission hears all chat, whatever the distance. */
    public static final String SPY = "radionetwork.localchat.spy";

    private final RadioNetworkPlugin plugin;

    public LocalChat(RadioNetworkPlugin plugin) {
        this.plugin = plugin;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("local-chat.enabled", true);
    }

    /** How far sideways a message carries, in blocks. */
    double radius() {
        return Math.max(1.0, plugin.getConfig().getDouble("local-chat.radius", 32.0));
    }

    /** How far up or down it carries, in blocks. Generous, so a cave under a base still hears the base. */
    double vertical() {
        return Math.max(1.0, plugin.getConfig().getDouble("local-chat.vertical", 32.0));
    }

    /**
     * True when a listener at {@code to} can hear a speaker at {@code from}: the same world, within {@code radius}
     * blocks sideways and {@code vertical} blocks up or down.
     */
    public static boolean hears(Location from, Location to, double radius, double vertical) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz <= radius * radius && Math.abs(from.getY() - to.getY()) <= vertical;
    }

    // HIGH, so the private answers to ChatPrompt (cancelled at LOWEST) are already gone.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled()) {
            return;
        }
        Player speaker = event.getPlayer();
        if (speaker.hasPermission(GLOBAL)) {
            return;
        }
        Location from = speaker.getLocation();
        double radius = radius();
        double vertical = vertical();
        boolean heard = false;
        for (var it = event.viewers().iterator(); it.hasNext(); ) {
            Audience viewer = it.next();
            if (!(viewer instanceof Player listener) || listener.equals(speaker)) {
                continue; // the console and the speaker keep their copy
            }
            if (listener.hasPermission(SPY)) {
                continue;
            }
            if (hears(from, listener.getLocation(), radius, vertical)) {
                heard = true;
            } else {
                it.remove();
            }
        }
        if (!heard && plugin.getConfig().getBoolean("local-chat.lonely-notice", true)) {
            // Chat runs off the main thread; the notice goes out from it.
            Bukkit.getScheduler().runTask(plugin,
                    () -> speaker.sendActionBar(Chat.text("§7No one is close enough to hear you.")));
        }
    }
}
