package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Pocket Receiver menu: scan for public channels, enter a channel, check status, switch off, and
 * the Chassis slot that holds an Underground Antenna or an Earpiece. Every slot is a display button; the receiver's
 * real state lives on the receiver item itself.
 */
public final class ReceiverGui implements InventoryHolder {

    public static final int SLOT_SCAN = 0;
    public static final int SLOT_ENTER = 1;
    public static final int SLOT_STATUS = 2;
    public static final int SLOT_OFF = 3;
    public static final int SLOT_AUTOSCAN = 4;
    public static final int SLOT_CHASSIS = 8;
    public static final int RESULTS_START = 9;
    public static final int RESULTS_END = 26;

    private final Inventory inventory = Bukkit.createInventory(this, 27, Chat.text("Pocket Receiver"));
    private final Map<Integer, String> results = new HashMap<>();
    private final RadioItem items;

    public ReceiverGui(Player player, PortableReceivers portables, RadioItem items) {
        this.items = items;
        refreshStatus(player, portables);
        inventory.setItem(SLOT_SCAN, button(Material.SPYGLASS, "§6Scan",
                "§7Public channels within " + (int) portables.range() + " blocks."));
        inventory.setItem(SLOT_ENTER, button(Material.WRITABLE_BOOK, "§6Tune to channel"));
        inventory.setItem(SLOT_OFF, button(Material.BARRIER, "§cSwitch off"));
        showChassis(player.getInventory().getItemInMainHand());
        showAutoScan(items.autoScanEnabled(player.getInventory().getItemInMainHand(),
                portables.autoScanDefault()));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public @org.jetbrains.annotations.Nullable String channelAt(int slot) {
        return results.get(slot);
    }

    public void refreshStatus(Player player, PortableReceivers portables) {
        PortableReceivers.Signal signal = portables.signal(player);
        String state = switch (signal.state()) {
            case OFF -> "§8off";
            case SCANNING -> "§escanning";
            case NO_SIGNAL -> "§cnone";
            case LOCKED -> "§clocked";
            case TOO_DEEP -> "§ctoo deep";
            case JAMMED -> "§cjammed";
            case OK -> "§a" + "|".repeat(signal.bars()) + "§8" + "|".repeat(4 - signal.bars());
        };
        inventory.setItem(SLOT_STATUS, button(Material.CLOCK, "§bStatus",
                "§7Tuned to: " + (signal.channel() == null ? "§8off" : "§f" + signal.channel()
                        + (signal.auto() ? " §8(auto)" : "")),
                "§7Signal: " + state,
                weatherLine(player, portables),
                "§7Audio: " + (items.hasEarpiece(player.getInventory().getItemInMainHand()) ? "§aearpiece" : "§eloudspeaker")));
    }

    private String weatherLine(Player player, PortableReceivers portables) {
        Weather weather = portables.weather();
        return weather == null ? "§7Weather: §fn/a" : weather.describe(player.getWorld());
    }

    public void showAutoScan(boolean on) {
        inventory.setItem(SLOT_AUTOSCAN, button(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "§6Auto-scan: " + (on ? "§aon" : "§8off"),
                "§7Hops to a public channel when yours drops."));
    }

    /** The Chassis slot: the fitted part (antenna first), or a placeholder when empty. */
    public void showChassis(ItemStack receiver) {
        if (items.hasAntenna(receiver) || items.hasEarpiece(receiver)) {
            boolean antenna = items.hasAntenna(receiver);
            ItemStack icon = antenna ? items.createAntenna() : items.createEarpiece();
            ItemMeta meta = icon.getItemMeta();
            meta.lore(List.of(
                    Chat.item(antenna ? "§7Fitted. Hears through rock, shorter range."
                            : "§7Fitted. Only you hear the audio.")));
            icon.setItemMeta(meta);
            inventory.setItem(SLOT_CHASSIS, icon);
        } else {
            inventory.setItem(SLOT_CHASSIS, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Chassis: empty",
                    "§8Antenna or earpiece."));
        }
    }

    /** Shows scan results in the lower rows; a note explains an empty result. */
    public void showResults(List<PortableReceivers.ScanResult> found, String emptyReason) {
        results.clear();
        for (int slot = RESULTS_START; slot <= RESULTS_END; slot++) {
            inventory.setItem(slot, null);
        }
        if (found.isEmpty()) {
            inventory.setItem(RESULTS_START, button(Material.GRAY_DYE, "§7Nothing found", emptyReason));
            return;
        }
        int slot = RESULTS_START;
        for (PortableReceivers.ScanResult result : found) {
            if (slot > RESULTS_END) {
                break;
            }
            results.put(slot, result.channel());
            inventory.setItem(slot++, button(Material.NOTE_BLOCK, "§f" + result.channel(),
                    "§7Terminals: §f" + result.towers(),
                    "§7Nearest: §f" + result.range(),
                    "§7Signal: §a" + "|".repeat(result.bars()) + "§8" + "|".repeat(4 - result.bars())));
        }
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item(name));
        meta.lore(java.util.Arrays.stream(lore).map(Chat::item).toList());
        item.setItemMeta(meta);
        return item;
    }
}
