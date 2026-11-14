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

/**
 * Placement, protection and cable linking for Signal Jammers. A jammer is a waxed Copper Bulb whose light shows
 * what the unit is doing (see {@link Jammers}): every right-click on it is cancelled, so it cannot be scraped,
 * waxed or toggled by hand. Jammers placed before the change were Respawn Anchors; they turn into the bulb the
 * next time the jammers tick. Its only explosion is the one {@link Jammers} sets off when it burns out.
 */
public final class JammerListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final Jammers jammers;

    public JammerListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, Jammers jammers) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.jammers = jammers;
    }

    // --------------------------------------------------------------- placement

    /** A jammer needs open sky: refused underground and in worlds without a sky. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!Jammers.isJammerBlock(block.getType()) || !items.isJammer(event.getItemInHand())) {
            return;
        }
        String problem = jammers.coverProblem(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (problem != null) {
            event.setCancelled(true);
            Chat.send(event.getPlayer(), "§c" + problem);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!Jammers.isJammerBlock(block.getType()) || !items.isJammer(event.getItemInHand())) {
            return;
        }
        if (block.getType() != Jammers.BLOCK) {
            block.setType(Jammers.BLOCK, false); // an older jammer item (a Respawn Anchor) becomes the waxed bulb
        }
        jammers.register(block, event.getPlayer().getName());
    }

    // -------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Jammers.Jammer jammer = jammers.get(event.getBlock());
        if (jammer == null) {
            return;
        }
        event.setDropItems(false);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().dropItemNaturally(at, items.createJammer());
        }
        jammers.remove(jammer);
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
            Jammers.Jammer jammer = jammers.get(block);
            if (jammer != null) {
                it.remove();
                Location at = block.getLocation().add(0.5, 0.5, 0.5);
                block.setType(Material.AIR);
                at.getWorld().dropItemNaturally(at, items.createJammer());
                jammers.remove(jammer);
            }
        }
    }

    /** Jammers are bound to a fixed location, so pistons may not move them. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> jammers.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> jammers.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------- interaction

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || !Jammers.isJammerBlock(event.getClickedBlock().getType())) {
            return;
        }
        Jammers.Jammer jammer = jammers.get(event.getClickedBlock());
        if (jammer == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean cable = items.isCable(hand);
        // Sneaking with something in hand means "place a block against it", as in vanilla; the jammer is not used.
        if (event.getHand() == EquipmentSlot.HAND && player.isSneaking() && !hand.isEmpty() && !cable) {
            return;
        }
        // Whatever else happens, the block itself is never used: no scraping, waxing or redstone toggling by hand.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (cable) {
            link(player, jammer);
        } else {
            Chat.send(player, jammers.statusLine(jammer));
        }
    }

    // ----------------------------------------------------------------- linking

    private void link(Player player, Jammers.Jammer jammer) {
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
        Jammers.Jammer existing = jammers.forTerminal(terminal);
        if (existing == jammer) {
            deny(player, "§cAlready linked.");
            return;
        }
        if (existing != null) {
            deny(player, "§cThat terminal already has a jammer at " + existing.center().getBlockX() + " "
                    + existing.center().getBlockY() + " " + existing.center().getBlockZ() + ".");
            return;
        }
        Location from = jammer.center();
        Location to = terminal.center();
        if (from == null || to == null || !from.getWorld().equals(to.getWorld())) {
            deny(player, "§cNot in the same world.");
            return;
        }
        int perCable = Math.max(1, plugin.getConfig().getInt("jammer.blocks-per-cable", 4));
        int maxLength = plugin.getConfig().getInt("jammer.max-link-length", 16);
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
        jammers.link(jammer, terminal);
        music.clearSelection(player);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.8f);
        music.flashLine(player, from, to);
        Chat.send(player, "§7Linked.");
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
