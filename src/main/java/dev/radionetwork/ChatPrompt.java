package dev.radionetwork;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Text prompts for servers without dialog screens (Paper 1.21 to 1.21.5): the player is asked to type the answer in
 * chat. Their next chat message is taken as the answer and never shown to anyone else; typing {@code cancel} (or waiting
 * a minute) abandons it.
 */
final class ChatPrompt implements PromptBackend, Listener {

    private static final long TIMEOUT_TICKS = 20L * 60;

    private record Pending(String title, int maxLength, Consumer<String> onSubmit, long expiresAt) {
    }

    private final RadioNetworkPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    ChatPrompt(RadioNetworkPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void open(RadioNetworkPlugin plugin, Player player, String title, String label, String initial, int maxLength,
                     Consumer<String> onSubmit) {
        long expires = System.currentTimeMillis() + TIMEOUT_TICKS * 50;
        synchronized (pending) {
            pending.put(player.getUniqueId(), new Pending(title, maxLength, onSubmit, expires));
        }
        Chat.send(player, "§6" + title + " §8» §f" + label + (initial.isEmpty() ? "" : " §7(now: §f" + initial + "§7)"));
        Chat.send(player, "§7Type your answer in chat" + (maxLength > 0 ? " (up to " + maxLength + " characters)" : "")
                + ". Nobody else will see it. Type §fcancel§7 to stop.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            synchronized (pending) {
                Pending p = pending.get(player.getUniqueId());
                if (p != null && p.expiresAt() == expires) {
                    pending.remove(player.getUniqueId());
                    Chat.send(player, "§7That question timed out.");
                }
            }
        }, TIMEOUT_TICKS);
    }

    @Override
    public void openTwo(RadioNetworkPlugin plugin, Player player, String title,
                        String firstLabel, String firstInitial, int firstMax,
                        String secondLabel, String secondInitial, int secondMax,
                        BiConsumer<String, String> onSubmit) {
        open(plugin, player, title, firstLabel, firstInitial, firstMax,
                first -> open(plugin, player, title, secondLabel, secondInitial, secondMax,
                        second -> onSubmit.accept(first, second)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending taken;
        synchronized (pending) {
            taken = pending.remove(event.getPlayer().getUniqueId());
        }
        if (taken == null) {
            return;
        }
        event.setCancelled(true); // the answer is private
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                Chat.send(player, "§7Cancelled.");
                return;
            }
            String answer = taken.maxLength() > 0 && text.length() > taken.maxLength() ? text.substring(0, taken.maxLength()) : text;
            taken.onSubmit().accept(answer);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synchronized (pending) {
            pending.remove(event.getPlayer().getUniqueId());
        }
    }
}
