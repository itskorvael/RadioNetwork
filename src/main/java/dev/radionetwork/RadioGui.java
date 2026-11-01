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
        inventory.setItem(SLOT_CHANNEL, button(Material.WRITABLE_BOOK, "§6Set Channel Name/Frequency",
                "§7Current Channel: " + (radio.isTuned() ? radio.channel() : "§cNot tuned"),
                "§7Station Operator: " + radio.creatorName(),
                "§7Frequency Owner: " + (frequency != null ? frequency.owner() : "§8unclaimed"),
                "§eClick to write/change channel.",
                "§8Leave it blank to untune."));
        inventory.setItem(SLOT_MODE, modeButton(radio, frequency));
        inventory.setItem(SLOT_RADIUS, button(Material.WRITABLE_BOOK, "§6Set Network Transmission Distance",
                "§7Max Network Distance: " + radius(radio) + " blocks",
                "§eClick to write specific block range (Operator Only)."));
        inventory.setItem(SLOT_STATUS, status(radio, manager));
    }

    private static ItemStack modeButton(Radio radio, Frequency frequency) {
        if (frequency == null) {
            return button(Material.REDSTONE_TORCH, "§6Frequency Settings",
                    "§7This radio is not tuned to a claimed frequency.",
                    "§eTune it first to claim or join a frequency.");
        }
        return button(Material.REDSTONE_TORCH, "§6Frequency Settings",
                "§7Frequency: " + frequency.name() + " §8(owner: " + frequency.owner() + ")",
                "§7Access: " + frequency.access().name()
                        + (frequency.access() == Frequency.Access.ENCRYPTED ? " §8(password = key)" : ""),
                "§7Speaking: " + frequency.speech().name()
                        + (frequency.speech() == RadioMode.CLOSED ? " §8(owner + trusted)" : " §8(everyone with access)"),
                "§eLeft-click: toggle OPEN/CLOSED §8(owner only)",
                "§eRight-click: toggle PUBLIC/ENCRYPTED §8(owner only)",
                "§eShift+right-click: change password §8(owner only)");
    }

    private static String radius(Radio radio) {
        return String.valueOf(radio.customRadius());
    }

    private static ItemStack status(Radio radio, RadioManager manager) {
        List<String> lore = new ArrayList<>();
        if (radio.isPowered()) {
            lore.add("§7Fuel remaining: §f" + Chat.duration(radio.fuelLevel()) + " §7(" + radio.fuelLevel() + "s)");
            lore.add("§7Active fuel: §f" + radio.activeFuel().name());
            lore.add("§7Dimensions: §f" + (radio.activeFuel().crossDimension() ? "Cross-dimensional" : "Same dimension"));
            int cap = radio.activeFuel().maxRadius();
            lore.add("§7Radius cap: §f" + (cap == Integer.MAX_VALUE ? "unlimited" : cap + " blocks"));
            if (manager.isLive(radio)) {
                int linked = Math.max(0, manager.receiversOf(radio).size() - 1);
                lore.add("§7Network: §aONLINE §7- " + linked + " linked tower" + (linked == 1 ? "" : "s"));
                if (manager.repeaters() != null && manager.repeaters().forTerminal(radio) != null) {
                    lore.add("§7Relay: " + (manager.isRelaying(radio)
                            ? "§aON §7- passes signals on (up to " + manager.maxRelays() + " in a row)"
                            : "§cOFF §7- relaying is turned off on this server"));
                }
            } else {
                lore.add("§7Network: §cOFFLINE §7- not tuned to an accessible frequency");
            }
            if (manager.jammers() != null && manager.jammers().forTerminal(radio) != null) {
                lore.add("§7Jammer: §c" + manager.jammers().forTerminal(radio).state().name().toLowerCase(java.util.Locale.ROOT)
                        + " §7- burns extra fuel; if this terminal runs dry the jammer fails catastrophically");
            }
        } else if (radio.hasFuel() && radio.isOffline()) {
            lore.add("§7Fuel remaining: §f" + Chat.duration(radio.fuelLevel()) + " §7(" + radio.fuelLevel() + "s)");
            lore.add("§7Network: §cOFFLINE §7- §c⚡ struck by lightning, back in a few seconds");
        } else {
            lore.add("§7Fuel remaining: §f0:00");
            lore.add("§7Active fuel: §fNONE");
            lore.add("§7Network: §cOFFLINE §7- no power");
        }
        if (manager.weather() != null) {
            lore.add(manager.weather().describe(radio.bukkitWorld()));
        }
        lore.add("§8Drop Redstone, Glowstone Dust or Ender Pearls");
        lore.add("§8in the slots to the right to refuel.");
        return button(Material.GLASS_PANE, "§bRadio Status", lore.toArray(String[]::new));
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
