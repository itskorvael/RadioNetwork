package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
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
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Placement, the Radio Terminal GUI, fuel intake and block-protection bookkeeping. */
public final class RadioListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem radioItem;

    public RadioListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem radioItem) {
        this.plugin = plugin;
        this.manager = manager;
        this.radioItem = radioItem;
    }

    // ---------------------------------------------------------------- crafting

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        radioItem.recipeKeys().forEach(event.getPlayer()::discoverRecipe);
    }

    /** A Blaze Rod is furnace fuel in vanilla; the tagged antenna must never be burned. */
    @EventHandler
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (radioItem.isAntenna(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    /** Owner activity feeds the abandoned-frequency rule. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        long now = System.currentTimeMillis();
        for (Frequency frequency : manager.frequencies().ownedBy(event.getPlayer().getName())) {
            frequency.lastSeen(now);
        }
        manager.frequencies().markDirty();
        manager.forgetBroadcaster(event.getPlayer().getUniqueId());
    }

    /**
     * Tagged parts only work in our own recipes: a Core is accepted only by the receiver and earpiece
     * recipes, and tagged terminals, receivers, antennas and earpieces are never ingredients (so they can't
     * be re-crafted or fed into vanilla recipes such as the Recovery Compass or a Conduit).
     */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        // Decommissioning: a working terminal, jukebox, repeater or jammer alone in the grid, and only that kind.
        if (event.getRecipe() instanceof Keyed decommission && radioItem.decommissionRecipeKind(decommission.getKey()) != null) {
            RadioItem.Retired wanted = radioItem.decommissionRecipeKind(decommission.getKey());
            int stacks = 0;
            RadioItem.Retired found = null;
            for (ItemStack stack : event.getInventory().getMatrix()) {
                if (stack != null && !stack.isEmpty()) {
                    stacks++;
                    found = radioItem.liveKind(stack);
                }
            }
            if (stacks != 1 || found != wanted) {
                event.getInventory().setResult(null);
            }
            return;
        }
        boolean takesCore = event.getRecipe() instanceof Keyed keyed
                && (keyed.getKey().equals(radioItem.receiverRecipeKey()) || keyed.getKey().equals(radioItem.earpieceRecipeKey())
                || keyed.getKey().equals(radioItem.jukeboxRecipeKey())
                || keyed.getKey().equals(radioItem.repeaterRecipeKey())
                || keyed.getKey().equals(radioItem.jammerRecipeKey())
                || keyed.getKey().equals(radioItem.scannerRecipeKey()));
        for (ItemStack stack : event.getInventory().getMatrix()) {
            if (radioItem.isRadio(stack) || radioItem.isReceiver(stack) || radioItem.isAntenna(stack)
                    || radioItem.isEarpiece(stack) || radioItem.isJukebox(stack) || radioItem.isCable(stack)
                    || radioItem.isRepeater(stack) || radioItem.isJammer(stack) || radioItem.isScanner(stack)
                    || radioItem.isBlankDisc(stack) || radioItem.isSongDisc(stack) || radioItem.isDecommissioned(stack)
                    || (radioItem.isCore(stack) && !takesCore)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // --------------------------------------------------------------- placement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.LODESTONE || !radioItem.isRadio(event.getItemInHand())) {
            return;
        }
        manager.register(event.getBlockPlaced(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Radio radio = manager.get(event.getBlock());
        if (radio != null) {
            manager.announceTunedOut(radio, radio.creatorName(), true);
            manager.unregister(radio);
            // Drop the tagged item so the radio can be picked up and re-placed (fuel stays behind).
            event.setDropItems(false);
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
                at.getWorld().dropItemNaturally(at, radioItem.create());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        unregisterAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        unregisterAll(event.blockList());
    }

    private void unregisterAll(List<Block> blocks) {
        for (Iterator<Block> it = blocks.iterator(); it.hasNext(); ) {
            Block block = it.next();
            Radio radio = manager.get(block);
            if (radio != null) {
                // Handle the block ourselves so it drops the tagged item instead of a plain Lodestone.
                it.remove();
                manager.announceTunedOut(radio, radio.creatorName(), true);
                manager.unregister(radio);
                Location at = block.getLocation().add(0.5, 0.5, 0.5);
                block.setType(Material.AIR);
                at.getWorld().dropItemNaturally(at, radioItem.create());
            }
        }
    }

    /** Radios are bound to a fixed location, so pistons may not move them. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> manager.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> manager.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------- GUI

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.LODESTONE
                || !event.getPlayer().getInventory().getItemInMainHand().isEmpty()) {
            return;
        }
        Radio radio = manager.get(event.getClickedBlock());
        if (radio == null) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().openInventory(new RadioGui(radio, manager).getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RadioGui gui) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true); // would let a double-click sweep up the control buttons
            return;
        }

        int raw = event.getRawSlot();
        if (raw >= 0 && raw < RadioGui.FUEL_START) {
            event.setCancelled(true);
            handleButton(player, gui, raw, event.getClick());
        } else if (RadioGui.isFuelSlot(raw)) {
            ItemStack incoming = incomingItem(event, player);
            if (incoming != null && !incoming.isEmpty() && !RadioGui.isFuel(incoming)) {
                event.setCancelled(true);
            }
        } else if (event.isShiftClick() && event.getCurrentItem() != null
                && !event.getCurrentItem().isEmpty() && !RadioGui.isFuel(event.getCurrentItem())) {
            event.setCancelled(true); // shift-click from the player inventory would land in the fuel core
        }
    }

    private static ItemStack incomingItem(InventoryClickEvent event, Player player) {
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP -> event.getHotbarButton() < 0
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItem(event.getHotbarButton());
            default -> null;
        };
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RadioGui)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < RadioGui.FUEL_START || (RadioGui.isFuelSlot(slot) && !RadioGui.isFuel(event.getOldCursor()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleButton(Player player, RadioGui gui, int slot, ClickType click) {
        Radio radio = manager.get(gui.radioKey());
        if (radio == null) {
            player.closeInventory();
            return;
        }
        switch (slot) {
            case RadioGui.SLOT_MODE -> handleFrequencyClick(player, gui, radio, click);
            case RadioGui.SLOT_CHANNEL -> {
                if (denyUnlessOperator(player, radio)) {
                    return;
                }
                prompt(player, "Channel", "Channel name (blank to untune)", radio.channel(),
                        RadioManager.MAX_CHANNEL_LENGTH, text -> applyChannel(player, radio, text));
            }
            case RadioGui.SLOT_RADIUS -> {
                if (denyUnlessOperator(player, radio)) {
                    return;
                }
                prompt(player, "Range", "Range in blocks", String.valueOf(radio.customRadius()),
                        10, text -> applyRadius(player, radio, text));
            }
            default -> { }
        }
    }

    private void handleFrequencyClick(Player player, RadioGui gui, Radio radio, ClickType click) {
        Frequency frequency = manager.frequencyOf(radio);
        if (frequency == null) {
            deny(player, "§cTerminal isn't tuned.");
            return;
        }
        if (!FrequencyCommand.isOwnerOrAdmin(player, frequency)) {
            deny(player, "§cOnly " + frequency.owner() + " can change that.");
            return;
        }
        switch (click) {
            case SHIFT_RIGHT -> prompt(player, "Frequency Password", "New password for " + frequency.name()
                    + " (4-32 characters)", "", 32, text -> applyPassword(player, frequency, text, false));
            case RIGHT -> {
                if (frequency.access() == Frequency.Access.PUBLIC && !frequency.hasPassword()) {
                    prompt(player, "Encrypt Frequency", "Choose a password for " + frequency.name()
                            + " (4-32 characters)", "", 32, text -> applyPassword(player, frequency, text, true));
                } else {
                    frequency.access(frequency.access().toggled());
                    afterFrequencyChange(player, gui, radio, "§7Access on §f" + frequency.name() + "§7: §f"
                            + frequency.access().name().toLowerCase(java.util.Locale.ROOT) + "§7.");
                }
            }
            case LEFT, SHIFT_LEFT -> {
                frequency.speech(frequency.speech().toggled());
                afterFrequencyChange(player, gui, radio, "§7Speaking on §f" + frequency.name() + "§7: §f"
                        + frequency.speech().name().toLowerCase(java.util.Locale.ROOT) + "§7.");
            }
            default -> { }
        }
    }

    private void afterFrequencyChange(Player player, RadioGui gui, Radio radio, String message) {
        manager.save();
        gui.refresh(radio, manager);
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1f);
        Chat.send(player, message);
    }

    /** Who is online, for telling people their access went; the probes swap this for fake players. */
    private java.util.function.Supplier<java.util.Collection<? extends Player>> online = Bukkit::getOnlinePlayers;

    private void applyPassword(Player player, Frequency frequency, String text, boolean makePrivate) {
        if (manager.frequencies().get(frequency.name()) != frequency
                || !FrequencyCommand.isOwnerOrAdmin(player, frequency)) {
            return;
        }
        String password = text.trim();
        if (password.length() < 4 || password.length() > 32) {
            deny(player, "§cPasswords must be 4-32 characters.");
            return;
        }
        // Who is about to lose access, and how many scanners are about to lose their progress.
        java.util.List<Player> removed = new java.util.ArrayList<>();
        if (frequency.access() == Frequency.Access.ENCRYPTED) {
            for (Player other : online.get()) {
                if (!other.equals(player) && frequency.hasAccess(other.getName()) && !frequency.isOwner(other.getName())
                        && !frequency.isTrusted(other.getName())) {
                    removed.add(other);
                }
            }
        }
        int scanners = manager.scanners() == null ? 0 : manager.scanners().progressOn(frequency);
        frequency.setPassword(password);
        if (makePrivate) {
            frequency.access(Frequency.Access.ENCRYPTED);
        }
        manager.save();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        Chat.send(player, "§7Password set on §f" + frequency.name() + "§7. Members who joined with the old one were removed."
                + (makePrivate ? " It is now encrypted." : ""));
        if (scanners > 0) {
            Alerts.send(player, Alerts.Level.CLEAR, "Security", "Scanner progress on " + Alerts.name(frequency.name())
                    + " was reset.");
        }
        for (Player target : removed) {
            Alerts.send(target, Alerts.Level.YELLOW, "Access", "The password for " + Alerts.name(frequency.name())
                    + " was changed. Enter the new one to rejoin.");
        }
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }

    private boolean denyUnlessOperator(Player player, Radio radio) {
        if (RadioManager.isOperator(player, radio)) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, "§cOnly " + radio.creatorName() + " can change that.");
        return true;
    }

    private void prompt(Player player, String title, String label, String initial, int maxLength,
                        Consumer<String> onSubmit) {
        player.closeInventory();
        // Open on the next tick so the close event (fuel intake) has finished first.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> TextPrompt.open(plugin, player, title, label, initial, maxLength, onSubmit));
    }

    // ------------------------------------------------------ prompt submissions

    private void applyChannel(Player player, Radio radio, String text) {
        if (manager.get(radio.key()) != radio || !RadioManager.isOperator(player, radio)) {
            return;
        }
        String channel = RadioManager.cleanChannel(text);
        if (channel.isEmpty()) {
            untune(player, radio);
            return;
        }

        Frequency frequency = manager.frequencies().get(channel);
        if (frequency == null) {
            manager.frequencies().claim(channel, player.getName());
            Chat.send(player, "§7Claimed §f" + channel + "§7.");
        } else if (!frequency.hasAccess(radio.creatorName())) {
            promptPassword(player, radio, frequency);
            return;
        }
        finishTune(player, radio, channel);
    }

    /** A blank channel name takes the radio off its frequency. */
    private void untune(Player player, Radio radio) {
        if (!radio.isTuned()) {
            Chat.send(player, "§7Already untuned.");
            return;
        }
        String previous = radio.channel();
        manager.announceTunedOut(radio, player.getName(), false);
        radio.channel("");
        manager.save();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.7f);
        Chat.send(player, "§7Untuned from §f" + previous + "§7.");
    }

    private void promptPassword(Player player, Radio radio, Frequency frequency) {
        Chat.send(player, "§f" + frequency.name() + " §7is encrypted.");
        plugin.getServer().getScheduler().runTask(plugin, () -> TextPrompt.open(plugin, player, "Encrypted Frequency",
                "Password for " + frequency.name(), "", 32, text -> {
                    PasswordGuard guard = manager.passwordGuard();
                    long wait = guard.waitMillis(player.getUniqueId());
                    if (wait > 0) {
                        deny(player, "§cToo many wrong passwords. Try again in " + PasswordGuard.describe(wait) + ".");
                        return;
                    }
                    if (manager.get(radio.key()) != radio || manager.frequencies().get(frequency.name()) != frequency) {
                        return;
                    }
                    if (!frequency.checkPassword(text.trim())) {
                        guard.failure(player.getUniqueId());
                        deny(player, "§cWrong password.");
                        return;
                    }
                    guard.success(player.getUniqueId());
                    frequency.grantMember(radio.creatorName());
                    manager.save();
                    finishTune(player, radio, frequency.name());
                }));
    }

    private void finishTune(Player player, Radio radio, String channel) {
        if (radio.isTuned() && !radio.channel().equals(channel)) {
            manager.announceTunedOut(radio, player.getName(), false); // leaving the old frequency
        }
        radio.channel(channel);
        manager.save();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        Chat.send(player, "§7Tuned to §f" + channel + "§7.");

        String notice = Chat.tag("§e", "Radio", channel) + "§7" + player.getName() + "'s radio has tuned in.";
        // A powered tower announces itself across its network; an unpowered one only to those beside it.
        // Only an encrypted frequency gets a roll call.
        if (radio.isPowered() && manager.announcesPresence(radio)) {
            manager.listenersOf(radio).forEach(listener -> Chat.send(listener, notice));
        }
    }

    private void applyRadius(Player player, Radio radio, String text) {
        if (manager.get(radio.key()) != radio || !RadioManager.isOperator(player, radio)) {
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(Chat.clean(text));
            if (requested <= 0) {
                requested = RadioManager.DEFAULT_RADIUS;
            }
        } catch (NumberFormatException e) {
            requested = RadioManager.DEFAULT_RADIUS;
        }
        int cap = radio.activeFuel().maxRadius();
        int applied = Math.min(requested, cap);
        radio.customRadius(applied);
        manager.save();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
        Chat.send(player, "§7Range set to §f" + applied + " §7blocks"
                + (applied < requested ? " (capped by " + radio.activeFuel().name().toLowerCase(java.util.Locale.ROOT) + ")" : "") + ".");
    }

    // ------------------------------------------------------------- fuel intake

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RadioGui gui)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        Radio radio = manager.get(gui.radioKey());

        Map<FuelType, Long> seconds = new HashMap<>();
        FuelType best = FuelType.NONE;
        for (int slot = RadioGui.FUEL_START; slot <= RadioGui.FUEL_END; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            FuelType type = FuelType.of(stack.getType());
            if (radio == null || type == FuelType.NONE) {
                // Radio vanished or foreign item: hand it back rather than destroy it.
                Players.give(player, stack);
            } else {
                seconds.merge(type, type.burnSeconds() * stack.getAmount(), Long::sum);
                best = best.best(type);
            }
            inventory.setItem(slot, null);
        }
        if (radio == null || seconds.isEmpty()) {
            return;
        }

        long total = seconds.values().stream().mapToLong(Long::longValue).sum();
        manager.addFuel(radio, best, total);
        manager.save();
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        Chat.send(player, "§7Added §f" + Chat.duration(total) + " §7of " + best.name().toLowerCase(java.util.Locale.ROOT)
                + ". Fuel: §f" + Chat.duration(radio.fuelLevel()) + "§7.");
    }
}
