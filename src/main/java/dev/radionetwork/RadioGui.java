package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** The 9x1 "Radio Terminal": four control slots on the left, a five-slot fuel core on the right. */
public final class RadioGui implements InventoryHolder {

    public static final int SLOT_CHANNEL = 0;
    public static final int SLOT_MODE = 1;
    public static final int SLOT_RADIUS = 2;
    public static final int SLOT_STATUS = 3;
    public static final int FUEL_START = 4;
    public static final int FUEL_END = 8;

    private final String radioKey;
    private final Inventory inventory;

    public RadioGui(Radio radio, RadioManager manager) {
        this.radioKey = radio.key();
        this.inventory = Bukkit.createInventory(this, 9, Chat.text("Radio Terminal"));
        refresh(radio, manager);
    }

    public String radioKey() {
        return radioKey;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static boolean isFuelSlot(int slot) {
        return slot >= FUEL_START && slot <= FUEL_END;
    }

    public static boolean isFuel(ItemStack item) {
        return item != null && !item.isEmpty() && FuelType.of(item.getType()) != FuelType.NONE;
    }

    /** Re-renders slots 0-3 from the radio's current state; the fuel slots are left alone. */
    public void refresh(Radio radio, RadioManager manager) {
        Frequency frequency = manager.frequencyOf(radio);
        inventory.setItem(SLOT_CHANNEL, button(Material.WRITABLE_BOOK, "§6Channel", channelLore(radio, frequency)));
        inventory.setItem(SLOT_MODE, modeButton(radio, frequency));
        inventory.setItem(SLOT_RADIUS, button(Material.WRITABLE_BOOK, "§6Range",
                "§7Max range: " + radius(radio) + " blocks"));
        inventory.setItem(SLOT_STATUS, status(radio, manager));
    }

    /** The channel, and who is behind it: one owner line when the operator also owns the frequency, two when not. */
    private static String[] channelLore(Radio radio, Frequency frequency) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Tuned to: " + (radio.isTuned() ? "§f" + radio.channel() : "§8nothing"));
        if (frequency == null) {
            lore.add("§7Operator: §f" + radio.creatorName());
        } else if (frequency.owner().equalsIgnoreCase(radio.creatorName())) {
            lore.add("§7Owner: §f" + radio.creatorName());
        } else {
            lore.add("§7Operator: §f" + radio.creatorName());
            lore.add("§7Frequency owner: §f" + frequency.owner());
        }
        return lore.toArray(String[]::new);
    }

    private static ItemStack modeButton(Radio radio, Frequency frequency) {
        if (frequency == null) {
            return button(Material.REDSTONE_TORCH, "§6Frequency", "§7Not tuned.");
        }
        return button(Material.REDSTONE_TORCH, "§6Frequency",
                "§7Access: §f" + frequency.access().name().toLowerCase(java.util.Locale.ROOT),
                "§7Speaking: §f" + frequency.speech().name().toLowerCase(java.util.Locale.ROOT),
                "§8Left: speaking. Right: access. Shift+right: password.");
    }

    private static String radius(Radio radio) {
        return String.valueOf(radio.customRadius());
    }

    private static ItemStack status(Radio radio, RadioManager manager) {
        List<String> lore = new ArrayList<>();
        if (radio.isPowered()) {
            lore.add("§7Fuel: §f" + Chat.duration(radio.fuelLevel()) + " §7("
                    + radio.activeFuel().name().toLowerCase(java.util.Locale.ROOT) + ")");
            int cap = radio.activeFuel().maxRadius();
            lore.add("§7Reach: §f" + (cap == Integer.MAX_VALUE ? "unlimited" : "up to " + cap + " blocks") + "§7, "
                    + (radio.activeFuel().crossDimension() ? "any dimension" : "this dimension"));
            if (manager.isLive(radio)) {
                int linked = Math.max(0, manager.receiversOf(radio).size() - 1);
                lore.add("§7Network: §aonline§7, " + linked + " other tower" + (linked == 1 ? "" : "s"));
                if (manager.repeaters() != null && manager.repeaters().forTerminal(radio) != null) {
                    lore.add("§7Relay: " + (manager.isRelaying(radio) ? "§aon" : "§coff"));
                }
            } else {
                lore.add("§7Network: §coffline§7, not tuned to a frequency you can use");
            }
            if (manager.jammers() != null && manager.jammers().forTerminal(radio) != null) {
                lore.add("§7Jammer: §c" + manager.jammers().forTerminal(radio).state().name().toLowerCase(java.util.Locale.ROOT));
            }
        } else if (radio.hasFuel() && radio.isOffline()) {
            lore.add("§7Fuel: §f" + Chat.duration(radio.fuelLevel()));
            lore.add("§7Network: §coffline§7, lightning damage");
        } else {
            lore.add("§7Fuel: §f0:00");
            lore.add("§7Network: §coffline§7, no power");
        }
        if (manager.weather() != null) {
            lore.add(manager.weather().describe(radio.bukkitWorld()));
        }
        lore.add("§8Fuel goes in the slots on the right.");
        return button(Material.GLASS_PANE, "§bStatus", lore.toArray(String[]::new));
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
