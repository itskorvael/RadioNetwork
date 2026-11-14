package dev.radionetwork;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/** Placement, protection and cable linking for Signal Repeaters; right-clicking one shows what it is doing. */
public final class RepeaterListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final Repeaters repeaters;

    public RepeaterListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, Repeaters repeaters) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.repeaters = repeaters;
    }

    // --------------------------------------------------------------- placement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.LIGHTNING_ROD && items.isRepeater(event.getItemInHand())) {
            repeaters.register(event.getBlockPlaced(), event.getPlayer().getName());
        }
    }

    // -------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Repeaters.Repeater repeater = repeaters.get(event.getBlock());
        if (repeater == null) {
            return;
        }
        event.setDropItems(false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().dropItemNaturally(at, items.createRepeater());
        }
        repeaters.remove(repeater);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList());
    }

    private void explode(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            Repeaters.Repeater repeater = repeaters.get(block);
            if (repeater != null) {
                it.remove();
                Location at = block.getLocation().add(0.5, 0.5, 0.5);
                block.setType(Material.AIR);
                at.getWorld().dropItemNaturally(at, items.createRepeater());
                repeaters.remove(repeater);
            }
        }
    }

    /** Repeaters are bound to a fixed location, so pistons may not move them. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> repeaters.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> repeaters.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------- interaction

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.LIGHTNING_ROD) {
            return;
        }
        Repeaters.Repeater repeater = repeaters.get(event.getClickedBlock());
        if (repeater == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean cable = items.isCable(hand);
        // Sneaking with something else in hand means "place a block against it", as in vanilla.
        if (player.isSneaking() && !hand.isEmpty() && !cable) {
            return;
        }
        event.setCancelled(true);
        if (cable) {
            link(player, repeater);
        } else {
            Chat.send(player, repeaters.statusLine(repeater));
        }
    }

    // ----------------------------------------------------------------- linking

    private void link(Player player, Repeaters.Repeater repeater) {
        MusicBoxes music = manager.music();
        String key = music == null ? null : music.selected(player);
        Radio terminal = key == null ? null : manager.get(key);
        if (terminal == null) {
            deny(player, "§cNo terminal selected.");
            return;
        }
        if (!RadioManager.isOperator(player, terminal)) {
            deny(player, "§cOnly " + terminal.creatorName() + " can link to that terminal.");
            return;
        }
        Repeaters.Repeater existing = repeaters.forTerminal(terminal);
        if (existing == repeater) {
            deny(player, "§cAlready linked.");
            return;
        }
        if (existing != null) {
            deny(player, "§cThat terminal already has a repeater at " + existing.center().getBlockX() + " "
                    + existing.center().getBlockY() + " " + existing.center().getBlockZ() + ".");
            return;
        }
        Location from = repeater.center();
        Location to = terminal.center();
        if (from == null || to == null || !from.getWorld().equals(to.getWorld())) {
            deny(player, "§cNot in the same world.");
            return;
        }
        int perCable = Math.max(1, plugin.getConfig().getInt("relay.blocks-per-cable", 4));
        int maxLength = plugin.getConfig().getInt("relay.max-link-length", 16);
        double distance = from.distance(to);
        if (distance > maxLength) {
            deny(player, "§cToo far. Cables reach " + maxLength + " blocks.");
            return;
        }
        int needed = (int) Math.ceil(distance / perCable);
        boolean free = player.getGameMode() == GameMode.CREATIVE;
        if (!free && items.countCables(player) < needed) {
            deny(player, "§cNeeds " + needed + " cable" + (needed == 1 ? "" : "s") + ", you have " + items.countCables(player) + ".");
            return;
        }
        if (!free) {
            items.takeCables(player, needed);
        }
        repeaters.link(repeater, terminal);
        music.clearSelection(player);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
        music.flashLine(player, from, to);
        Chat.send(player, "§7Linked.");
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
