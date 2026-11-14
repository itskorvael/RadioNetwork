package dev.radionetwork;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The Signal Scanner in use. Right-click on an encrypted frequency you have no key for opens the Decoder (the
 * code-breaking puzzle); on anything else, or when sneaking, it asks which frequency to tune to.
 */
public final class ScannerListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final Scanners scanners;

    public ScannerListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, Scanners scanners) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.scanners = scanners;
        // The wait between guesses counts down on screen.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpen, 20L, 20L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand == null || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !items.isScanner(event.getItem())) {
            return;
        }
        // Both hands fire an event; with a scanner in each, the main hand is the one that tunes.
        if (hand == EquipmentSlot.OFF_HAND && items.isScanner(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        Block clicked = event.getClickedBlock();
        // Doors, chests and the like keep working; a spyglass never zooms, since right-click is for tuning.
        if (clicked != null && isInteractable(clicked) && !event.getPlayer().isSneaking()) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        ItemStack held = inHand(player, hand);
        Chat.send(player, scanners.statusLine(player, held));
        Frequency decodable = player.isSneaking() ? null : scanners.decodable(player, held);
        if (decodable != null) {
            if (scanners.signal(player, decodable) < 1) {
                Alerts.send(player, Alerts.Level.YELLOW, "Signal", "No signal on " + Alerts.name(decodable.name()) + ".");
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> openDecoder(player));
                return;
            }
        }
        promptTune(player, held, hand);
    }

    private void promptTune(Player player, ItemStack held, EquipmentSlot hand) {
        String current = items.scannerChannel(held);
        plugin.getServer().getScheduler().runTask(plugin, () -> TextPrompt.open(plugin, player,
                "Signal Scanner", "Frequency to scan (blank = switch off)", current == null ? "" : current,
                RadioManager.MAX_CHANNEL_LENGTH, text -> submit(player, text, hand)));
    }

    // ------------------------------------------------------------------ Decoder

    private void openDecoder(Player player) {
        ItemStack scanner = scanners.held(player);
        Frequency frequency = scanner == null ? null : scanners.decodable(player, scanner);
        Scanners.Puzzle puzzle = frequency == null ? null : scanners.puzzle(player, scanner, frequency);
        if (puzzle == null) {
            return;
        }
        ScannerGui gui = new ScannerGui(frequency.name());
        draw(player, gui, puzzle, frequency);
        player.openInventory(gui.getInventory());
        scanners.viewing(player, true);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.3f);
    }

    private void draw(Player player, ScannerGui gui, Scanners.Puzzle puzzle, Frequency frequency) {
        long now = System.currentTimeMillis();
        gui.render(puzzle, scanners.stages(), scanners.maxGuesses(), scanners.signal(player, frequency),
                Math.max(0, (puzzle.nextGuessAt() - now) / 1000.0), Math.max(0, (puzzle.lockedUntil() - now) / 1000.0));
    }

    private void refreshOpen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ScannerGui gui)) {
                continue;
            }
            ItemStack scanner = scanners.held(player);
            Frequency frequency = scanner == null ? null : scanners.decodable(player, scanner);
            Scanners.Puzzle puzzle = frequency == null ? null : scanners.puzzle(player, scanner, frequency);
            if (puzzle != null) {
                draw(player, gui, puzzle, frequency);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ScannerGui gui)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true); // buttons only; nothing moves in or out
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }
        ItemStack scanner = scanners.held(player);
        Frequency frequency = scanner == null ? null : scanners.decodable(player, scanner);
        Scanners.Puzzle puzzle = frequency == null ? null : scanners.puzzle(player, scanner, frequency);
        if (puzzle == null) {
            player.closeInventory();
            deny(player, "§cScanner not in hand.");
            return;
        }
        if (slot >= ScannerGui.PALETTE_START && slot < ScannerGui.PALETTE_START + Scanners.COLORS) {
            Scanners.Result result = scanners.pick(puzzle, slot - ScannerGui.PALETTE_START);
            player.playSound(player.getLocation(), result == Scanners.Result.OK ? Sound.UI_BUTTON_CLICK : Sound.ENTITY_VILLAGER_NO,
                    0.6f, 1.2f);
        } else if (slot == ScannerGui.SLOT_UNDO) {
            scanners.undo(puzzle);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
        } else if (slot == ScannerGui.SLOT_RETUNE) {
            EquipmentSlot hand = items.isScanner(player.getInventory().getItemInMainHand()) ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
            player.closeInventory();
            promptTune(player, scanner, hand);
            return;
        } else if (slot == ScannerGui.SLOT_SUBMIT) {
            if (!submitGuess(player, scanner, puzzle)) {
                return;                // the Decoder closed
            }
        } else {
            return;
        }
        draw(player, gui, puzzle, frequency);
    }

    /** Tries the code; false when that closed the Decoder. */
    private boolean submitGuess(Player player, ItemStack scanner, Scanners.Puzzle puzzle) {
        Scanners.Result result = scanners.submit(player, scanner, puzzle);
        switch (result) {
            case DECODED -> player.closeInventory();
            case NO_SIGNAL, WRONG_SCANNER -> {
                player.closeInventory();
                deny(player, Alerts.line(Alerts.Level.YELLOW, "Signal", "Signal lost."));
            }
            case NOT_READY -> actionBar(player, "§cNeeds " + puzzle.slots() + " colours.");
            case WAIT -> actionBar(player, "§7Processing.");
            case LOCKED_OUT -> actionBar(player, "§cRecalibrating.");
            default -> {
            }
        }
        if (result == Scanners.Result.WAIT || result == Scanners.Result.LOCKED_OUT || result == Scanners.Result.NOT_READY) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
        return result != Scanners.Result.DECODED && result != Scanners.Result.NO_SIGNAL && result != Scanners.Result.WRONG_SCANNER;
    }

    private static void actionBar(Player player, String legacy) {
        Component text = Chat.text(legacy);
        player.sendActionBar(text);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ScannerGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ScannerGui && event.getPlayer() instanceof Player player) {
            scanners.viewing(player, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scanners.forget(event.getPlayer());
    }

    // ------------------------------------------------------------------- tuning

    private static ItemStack inHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }

    private static void setInHand(Player player, EquipmentSlot hand, ItemStack stack) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(stack);
        } else {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    private static boolean isInteractable(Block block) {
        if (block.getType() == Material.LODESTONE) {
            return false;
        }
        org.bukkit.block.BlockType type = block.getType().asBlockType();
        return type != null && type.isInteractable();
    }

    private void submit(Player player, String text, EquipmentSlot slot) {
        ItemStack hand = inHand(player, slot);
        if (!items.isScanner(hand)) {
            deny(player, "§cScanner not in hand.");
            return;
        }
        String channel = RadioManager.cleanChannel(text);
        if (channel.isEmpty()) {
            setInHand(player, slot, items.withScannerChannel(hand, null));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.7f);
            Chat.send(player, Chat.tag("§5", "Scanner") + "§7Switched off.");
            return;
        }
        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null) {
            deny(player, "§cNo frequency called §f" + channel + "§c.");
            return;
        }
        ItemStack tuned = items.withScannerChannel(hand, frequency.name());
        setInHand(player, slot, tuned);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        Chat.send(player, scanners.statusLine(player, tuned));
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
