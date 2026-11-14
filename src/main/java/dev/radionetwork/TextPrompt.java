package dev.radionetwork;

import org.bukkit.entity.Player;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Asks a player to type a line of text. Paper 1.21.6 and newer show a dialog screen; older servers (1.21 to 1.21.5)
 * have no dialogs, so the player types the answer in chat instead.
 */
public final class TextPrompt {

    private static PromptBackend backend;

    private TextPrompt() {
    }

    private static synchronized PromptBackend backend(RadioNetworkPlugin plugin) {
        if (backend == null) {
            backend = dialogsAvailable() ? loadDialogs(plugin) : new ChatPrompt(plugin);
        }
        return backend;
    }

    private static boolean dialogsAvailable() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static PromptBackend loadDialogs(RadioNetworkPlugin plugin) {
        try {
            return (PromptBackend) Class.forName("dev.radionetwork.DialogPrompt").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Dialog screens are not available (" + e + "); asking in chat instead.");
            return new ChatPrompt(plugin);
        }
    }

    public static void open(RadioNetworkPlugin plugin, Player player, String title, String label,
                            String initial, int maxLength, Consumer<String> onSubmit) {
        backend(plugin).open(plugin, player, title, label, initial, maxLength, onSubmit);
    }

    /** Two text boxes (such as a song's name and its link). */
    public static void openTwo(RadioNetworkPlugin plugin, Player player, String title,
                               String firstLabel, String firstInitial, int firstMax,
                               String secondLabel, String secondInitial, int secondMax,
                               BiConsumer<String, String> onSubmit) {
        backend(plugin).openTwo(plugin, player, title, firstLabel, firstInitial, firstMax,
                secondLabel, secondInitial, secondMax, onSubmit);
    }
}
