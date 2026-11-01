package dev.radionetwork;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
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
 * Placement, protection and cable linking for Signal Jammers. A jammer is a Respawn Anchor that is always
 * full and that nothing can make explode: every right-click on it is cancelled, so it can neither be used as
 * a spawn point nor blow up the way a charged anchor does outside the Nether. Its only explosion is the
 * one {@link Jammers} sets off when its terminal runs out of fuel.
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
        if (block.getType() != Material.RESPAWN_ANCHOR || !items.isJammer(event.getItemInHand())) {
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
        if (block.getType() != Material.RESPAWN_ANCHOR || !items.isJammer(event.getItemInHand())) {
            return;
        }
        RespawnAnchor data = (RespawnAnchor) block.getBlockData();
        data.setCharges(data.getMaximumCharges()); // always full, so it glows
        block.setBlockData(data, false);
        jammers.register(block, event.getPlayer().getName());
        Chat.send(event.getPlayer(), "§aSignal Jammer placed. Link it to a Radio Terminal with Signal Cables. "
                + "§7Careful: if that terminal ever runs out of fuel, the jammer fails catastrophically.");
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
                || event.getClickedBlock().getType() != Material.RESPAWN_ANCHOR) {
            return;
        }
        Jammers.Jammer jammer = jammers.get(event.getClickedBlock());
        if (jammer == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean cable = items.isCable(hand);
        // Sneaking with something in hand means "place a block against it", as in vanilla; the anchor is not used.
        if (event.getHand() == EquipmentSlot.HAND && player.isSneaking() && !hand.isEmpty() && !cable) {
            return;
        }
        // Whatever else happens, the anchor itself is never used: no spawn point, no charging, no explosion.
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
            deny(player, "§cRight-click a Radio Terminal with the cable first, then the jammer.");
            return;
        }
        if (!RadioManager.isOperator(player, terminal)) {
            deny(player, "§cOnly the terminal's operator (" + terminal.creatorName() + ") can link a jammer to it.");
            return;
        }
        Jammers.Jammer existing = jammers.forTerminal(terminal);
        if (existing == jammer) {
            deny(player, "§cThis jammer is already linked to that terminal.");
            return;
        }
        if (existing != null) {
            deny(player, "§cThat terminal already has a Signal Jammer (at " + existing.center().getBlockX() + " "
                    + existing.center().getBlockY() + " " + existing.center().getBlockZ() + "). One is enough; break it to use another.");
            return;
        }
        Location from = jammer.center();
        Location to = terminal.center();
        if (from == null || to == null || !from.getWorld().equals(to.getWorld())) {
            deny(player, "§cThe jammer and the terminal must be in the same world.");
            return;
        }
        int perCable = Math.max(1, plugin.getConfig().getInt("jammer.blocks-per-cable", 4));
        int maxLength = plugin.getConfig().getInt("jammer.max-link-length", 16);
        double distance = from.distance(to);
        if (distance > maxLength) {
            deny(player, "§cToo far: " + (int) Math.ceil(distance) + " blocks apart, and a link can be at most " + maxLength + ".");
            return;
        }
        int needed = (int) Math.ceil(distance / perCable);
        boolean free = player.getGameMode() == GameMode.CREATIVE;
        if (!free && items.countCables(player) < needed) {
            deny(player, "§cYou need " + needed + " Signal Cable" + (needed == 1 ? "" : "s") + " for " + (int) Math.ceil(distance)
                    + " blocks (1 per " + perCable + "), and you have " + items.countCables(player) + ".");
            return;
        }
        if (!free) {
            items.takeCables(player, needed);
        }
        jammers.link(jammer, terminal);
        music.clearSelection(player);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.8f);
        music.flashLine(player, from, to);
        Chat.send(player, "§aJammer linked. It starts warming up as soon as the terminal has power, and burns that "
                + "terminal's fuel faster while it runs. §cIf the fuel runs out, the jammer fails catastrophically. §7Used "
                + (free ? 0 : needed) + " cable" + (needed == 1 ? "" : "s") + ".");
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
