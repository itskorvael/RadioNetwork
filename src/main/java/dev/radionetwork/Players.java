package dev.radionetwork;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Small helpers that behave the same on every supported Paper version. */
final class Players {

    private Players() {
    }

    /** Puts an item in the player's inventory, and drops whatever does not fit at their feet (what {@code Player#give} does on newer Paper). */
    static void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }
}
