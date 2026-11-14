package dev.radionetwork;

import org.bukkit.entity.Player;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A way of asking a player to type text: a dialog screen where the server has them, the chat where it does not. */
interface PromptBackend {

    void open(RadioNetworkPlugin plugin, Player player, String title, String label, String initial, int maxLength,
              Consumer<String> onSubmit);

    void openTwo(RadioNetworkPlugin plugin, Player player, String title,
                 String firstLabel, String firstInitial, int firstMax,
                 String secondLabel, String secondInitial, int secondMax,
                 BiConsumer<String, String> onSubmit);
}
