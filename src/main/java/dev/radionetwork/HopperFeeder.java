package dev.radionetwork;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Hopper automation. A Lodestone has no inventory, so vanilla never fires InventoryMoveItemEvent
 * for a hopper aimed at one; instead this task plays the part of the hopper's transfer: every
 * hopper cooldown (8 ticks) it takes one fuel item from each enabled hopper pointing into a radio.
 */
public final class HopperFeeder extends BukkitRunnable {

    public static final long PERIOD_TICKS = 8;

    private static final BlockFace[] SIDES = {
            BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final RadioManager manager;

    public HopperFeeder(RadioManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        for (Radio radio : manager.all()) {
            World world = radio.bukkitWorld();
            if (world == null || !world.isChunkLoaded(radio.x() >> 4, radio.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(radio.x(), radio.y(), radio.z());
            for (BlockFace side : SIDES) {
                Block neighbour = block.getRelative(side);
                if (neighbour.getType() == Material.HOPPER && feed(radio, neighbour, side)) {
                    break; // one item per radio per cycle, like a single hopper transfer
                }
            }
        }
    }

    private boolean feed(Radio radio, Block hopperBlock, BlockFace side) {
        if (!(hopperBlock.getBlockData() instanceof Hopper data)
                || !data.isEnabled()
                || data.getFacing() != side.getOppositeFace()
                || !(hopperBlock.getState(false) instanceof org.bukkit.block.Hopper hopper)) {
            return false;
        }
        Inventory inventory = hopper.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            FuelType type = FuelType.of(stack.getType());
            if (type == FuelType.NONE) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, stack.isEmpty() ? null : stack);
            manager.addFuel(radio, type, type.burnSeconds());
            return true;
        }
        return false;
    }
}
