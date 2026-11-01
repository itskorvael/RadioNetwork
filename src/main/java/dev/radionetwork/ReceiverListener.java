package dev.radionetwork;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opens the Pocket Receiver menu and handles its buttons. */
public final class ReceiverListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final PortableReceivers portables;

    public ReceiverListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, PortableReceivers portables) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.portables = portables;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !items.isReceiver(event.getItem())) {
            return;
        }
        Block clicked = event.getClickedBlock();
        // Let doors, chests and the like work as usual; a Lodestone is ours, so the compass never links to it.
        if (clicked != null && clicked.getType() != Material.LODESTONE && isInteractable(clicked)
                && !event.getPlayer().isSneaking()) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.getPlayer().openInventory(new ReceiverGui(event.getPlayer(), portables, items).getInventory());
    }

    private static boolean isInteractable(Block block) {
        org.bukkit.block.BlockType type = block.getType().asBlockType();
        return type != null && type.isInteractable();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ReceiverGui gui)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true); // the menu is buttons only; nothing moves in or out
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }
        if (!items.isReceiver(player.getInventory().getItemInMainHand())) {
            player.closeInventory();
            deny(player, "§cHold your Pocket Receiver in your main hand to use its menu.");
            return;
        }
        if (slot == ReceiverGui.SLOT_CHASSIS) {
            handleChassis(player, gui, event);
            return;
        }
        // Shift-clicking a part from your own inventory fits it into the Chassis.
        if (slot >= 27 && event.isShiftClick() && isPart(event.getCurrentItem())) {
            installFrom(player, gui, event);
            return;
        }
        if (slot >= 27) {
            return;
        }
        switch (slot) {
            case ReceiverGui.SLOT_SCAN -> scan(player, gui);
            case ReceiverGui.SLOT_ENTER -> enter(player);
            case ReceiverGui.SLOT_AUTOSCAN -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                boolean on = !items.autoScanEnabled(hand, portables.autoScanDefault());
                player.getInventory().setItemInMainHand(items.withAutoScan(hand, on));
                gui.showAutoScan(on);
                gui.refreshStatus(player, portables);
                player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, on ? 1.2f : 0.8f);
                Chat.send(player, on ? "§aAuto-scan on." : "§7Auto-scan off. The receiver stays on your chosen channel.");
            }
            case ReceiverGui.SLOT_OFF -> {
                tune(player, null);
                gui.refreshStatus(player, portables);
                Chat.send(player, "§7Pocket Receiver switched off.");
            }
            default -> {
                String channel = gui.channelAt(slot);
                if (channel != null) {
                    pickScanResult(player, gui, channel);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ReceiverGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        portables.forget(event.getPlayer());
    }

    // ----------------------------------------------------------------- actions

    private void scan(Player player, ReceiverGui gui) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
        boolean antenna = items.hasAntenna(player.getInventory().getItemInMainHand());
        String reason = "§7No public channel is in range of a powered terminal.";
        if (!antenna && portables.depthLoss(player.getLocation()) > 0) {
            reason = "§7Rock above you weakens the signal. Go up, or fit an Underground Antenna.";
        }
        gui.showResults(portables.scan(player.getLocation(), antenna), reason);
        gui.refreshStatus(player, portables);
    }

    // ------------------------------------------------------------------ chassis

    /** What the Chassis slot accepts: an Underground Antenna or an Earpiece, one at a time. */
    private boolean isPart(@org.jetbrains.annotations.Nullable ItemStack stack) {
        return items.isAntenna(stack) || items.isEarpiece(stack);
    }

    private boolean chassisFilled(ItemStack receiver) {
        return items.hasAntenna(receiver) || items.hasEarpiece(receiver);
    }

    private void handleChassis(Player player, ReceiverGui gui, InventoryClickEvent event) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack cursor = event.getCursor();
        if (chassisFilled(hand)) {
            if (cursor != null && !cursor.isEmpty()) {
                deny(player, "§cEmpty your cursor first, then click to take the part out.");
                return;
            }
            boolean antenna = items.hasAntenna(hand);
            player.getInventory().setItemInMainHand(antenna ? items.withAntenna(hand, false) : items.withEarpiece(hand, false));
            ItemStack back = antenna ? items.createAntenna() : items.createEarpiece();
            // Cursor changes are applied next tick, after the server has finished handling this cancelled click.
            plugin.getServer().getScheduler().runTask(plugin, () -> player.setItemOnCursor(back));
            gui.showChassis(player.getInventory().getItemInMainHand());
            gui.refreshStatus(player, portables);
            Chat.send(player, "§7" + (antenna ? "Underground Antenna" : "Earpiece") + " removed from the Chassis.");
        } else if (isPart(cursor)) {
            if (install(player, gui, cursor)) {
                ItemStack remaining = cursor.clone();
                remaining.setAmount(cursor.getAmount() - 1);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.setItemOnCursor(remaining.isEmpty() ? null : remaining));
            }
        } else {
            deny(player, "§cOnly an Underground Antenna or an Earpiece fits the Chassis.");
        }
    }

    /** Shift-click from the player's inventory: fits one part from the clicked stack. */
    private void installFrom(Player player, ReceiverGui gui, InventoryClickEvent event) {
        ItemStack stack = event.getCurrentItem();
        if (stack != null && install(player, gui, stack)) {
            ItemStack remaining = stack.clone();
            remaining.setAmount(stack.getAmount() - 1);
            int slot = event.getSlot();
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> player.getInventory().setItem(slot, remaining.isEmpty() ? null : remaining));
        }
    }

    /** Marks the receiver in the main hand as fitted with {@code part}; the caller removes it from where it came. */
    private boolean install(Player player, ReceiverGui gui, ItemStack part) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!items.isReceiver(hand) || chassisFilled(hand)) {
            deny(player, "§cThe Chassis already holds a part. Take it out first.");
            return false;
        }
        boolean antenna = items.isAntenna(part);
        player.getInventory().setItemInMainHand(antenna ? items.withAntenna(hand, true) : items.withEarpiece(hand, true));
        gui.showChassis(player.getInventory().getItemInMainHand());
        gui.refreshStatus(player, portables);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        Chat.send(player, antenna
                ? "§aUnderground Antenna fitted. It hears through rock, at reduced range."
                : "§aEarpiece fitted. Only you hear the audio now.");
        return true;
    }

    /** A scan result was clicked: the frequency may have changed since the scan, so access is checked again. */
    void pickScanResult(Player player, ReceiverGui gui, String channel) {
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency != null && tuneIfAllowed(player, frequency)) {
            player.closeInventory();
            return;
        }
        deny(player, "§cThat channel is no longer open to you. Scan again, or enter it with its password.");
        scan(player, gui);
    }

    private void enter(Player player) {
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> TextPrompt.open(plugin, player,
                "Pocket Receiver", "Channel name (blank = switch off)", currentChannel(player),
                RadioManager.MAX_CHANNEL_LENGTH, text -> submitChannel(player, text)));
    }

    private String currentChannel(Player player) {
        String channel = items.receiverChannel(player.getInventory().getItemInMainHand());
        return channel == null ? "" : channel;
    }

    private void submitChannel(Player player, String text) {
        if (!items.isReceiver(player.getInventory().getItemInMainHand())) {
            deny(player, "§cHold your Pocket Receiver in your main hand to tune it.");
            return;
        }
        String channel = RadioManager.cleanChannel(text);
        if (channel.isEmpty()) {
            tune(player, null);
            Chat.send(player, "§7Pocket Receiver switched off.");
            return;
        }
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null) {
            deny(player, "§cNo frequency called §f" + channel + " §cexists. A radio terminal has to claim it first.");
        } else if (!tuneIfAllowed(player, frequency)) {
            askPassword(player, frequency);
        }
    }

    /**
     * Tunes the receiver to {@code frequency} only if this player has access right now, and says so.
     * Every route onto a channel goes through here (typed names and scan-result clicks), so a channel
     * that turned encrypted after a scan cannot be joined without the password.
     */
    boolean tuneIfAllowed(Player player, Frequency frequency) {
        if (manager.frequencies().get(frequency.name()) != frequency || !frequency.hasAccess(player.getName())) {
            return false;
        }
        tune(player, frequency.name());
        Chat.send(player, "§aPocket Receiver tuned to §f" + frequency.name() + "§a.");
        return true;
    }

    private void askPassword(Player player, Frequency frequency) {
        Chat.send(player, "§eFrequency §6" + frequency.name() + " §eis ENCRYPTED. Enter its password (the key) to listen in.");
        plugin.getServer().getScheduler().runTask(plugin, () -> TextPrompt.open(plugin, player, "Encrypted Frequency",
                "Password for " + frequency.name(), "", 32, text -> {
                    PasswordGuard guard = manager.passwordGuard();
                    long wait = guard.waitMillis(player.getUniqueId());
                    if (wait > 0) {
                        deny(player, "§cToo many wrong passwords. Try again in " + PasswordGuard.describe(wait) + ".");
                        return;
                    }
                    if (manager.frequencies().get(frequency.name()) != frequency
                            || !items.isReceiver(player.getInventory().getItemInMainHand())) {
                        return;
                    }
                    if (!frequency.checkPassword(text.trim())) {
                        guard.failure(player.getUniqueId());
                        deny(player, "§cWrong password.");
                        return;
                    }
                    guard.success(player.getUniqueId());
                    frequency.grantMember(player.getName());
                    manager.save();
                    tuneIfAllowed(player, frequency);
                }));
    }

    /** Writes the channel onto the receiver in the main hand (null switches it off). */
    private void tune(Player player, String channel) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (items.isReceiver(hand)) {
            player.getInventory().setItemInMainHand(items.withChannel(hand, channel));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, channel == null ? 0.7f : 1.5f);
        }
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
