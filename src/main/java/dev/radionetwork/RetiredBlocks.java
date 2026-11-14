package dev.radionetwork;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Decommissioned terminals, jukeboxes, repeaters and jammers once they are placed: ordinary blocks that look the
 * part and do nothing. They never register with the radio network, so they have no menu, no signal and no
 * frequency. The only bookkeeping is which blocks they are, kept in each chunk's own data so it travels with the
 * world; a decommissioned block drops its decommissioned item when broken, and pistons leave it alone.
 */
public final class RetiredBlocks implements Listener {

    private final RadioItem items;
    private final NamespacedKey key;

    public RetiredBlocks(RadioNetworkPlugin plugin, RadioItem items) {
        this.items = items;
        this.key = new NamespacedKey(plugin, "decommissioned_blocks");
    }

    // ------------------------------------------------------------------- state

    /** True when this block is a placed decommissioned item (and still the block it was placed as). */
    public boolean isRetired(Block block) {
        if (RadioItem.Retired.of(block.getType()) == null) {
            return false;
        }
        long[] stored = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
        return stored != null && contains(stored, block.getBlockKey());
    }

    public void mark(Block block) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long[] stored = data.get(key, PersistentDataType.LONG_ARRAY);
        long id = block.getBlockKey();
        if (stored != null && contains(stored, id)) {
            return;
        }
        long[] grown = stored == null ? new long[1] : Arrays.copyOf(stored, stored.length + 1);
        grown[grown.length - 1] = id;
        data.set(key, PersistentDataType.LONG_ARRAY, grown);
    }

    public void unmark(Block block) {
        PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
        long[] stored = data.get(key, PersistentDataType.LONG_ARRAY);
        long id = block.getBlockKey();
        if (stored == null || !contains(stored, id)) {
            return;
        }
        long[] left = Arrays.stream(stored).filter(value -> value != id).toArray();
        if (left.length == 0) {
            data.remove(key);
        } else {
            data.set(key, PersistentDataType.LONG_ARRAY, left);
        }
    }

    private static boolean contains(long[] values, long id) {
        for (long value : values) {
            if (value == id) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- placement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        unmark(block); // whatever stood here before is gone
        RadioItem.Retired kind = items.decommissionedKind(event.getItemInHand());
        if (kind != null && block.getType() == kind.block()) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isRetired(block)) {
            return;
        }
        RadioItem.Retired kind = RadioItem.Retired.of(block.getType());
        event.setDropItems(false);
        unmark(block);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().dropItemNaturally(at, items.createDecommissioned(kind));
        }
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
            if (!isRetired(block)) {
                continue;
            }
            // Handled here so it drops the decommissioned item instead of the plain block.
            it.remove();
            RadioItem.Retired kind = RadioItem.Retired.of(block.getType());
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            unmark(block);
            block.setType(Material.AIR);
            at.getWorld().dropItemNaturally(at, items.createDecommissioned(kind));
        }
    }

    /** Pistons leave them where they are, like the working ones. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isRetired)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isRetired)) {
            event.setCancelled(true);
        }
    }

    /** A decommissioned jukebox takes no discs: it is not a jukebox any more. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || clicked.getType() != Material.JUKEBOX
                || event.getItem() == null || !event.getItem().getType().isRecord() || !isRetired(clicked)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
    }
}
