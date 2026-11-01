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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

/** Placement, protection, linking with Signal Cables, and inserting or ejecting discs. */
public final class MusicListener implements Listener {

    private final RadioNetworkPlugin plugin;
    private final RadioManager manager;
    private final RadioItem items;
    private final MusicBoxes music;
    private final SongUploads uploads;

    public MusicListener(RadioNetworkPlugin plugin, RadioManager manager, RadioItem items, MusicBoxes music,
                         SongUploads uploads) {
        this.plugin = plugin;
        this.manager = manager;
        this.items = items;
        this.music = music;
        this.uploads = uploads;
    }

    // --------------------------------------------------------------- placement

    /** A Signal Cable is a Copper Chain; it must never turn into a block. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceCable(BlockPlaceEvent event) {
        if (items.isCable(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceJukebox(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.JUKEBOX && items.isJukebox(event.getItemInHand())) {
            music.register(event.getBlockPlaced(), event.getPlayer().getName());
            Chat.send(event.getPlayer(), "§aRadio Jukebox placed. Link it to a Radio Terminal with Signal Cables.");
        }
    }

    // -------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        MusicBoxes.Box box = music.get(event.getBlock());
        if (box == null) {
            return;
        }
        event.setDropItems(false);
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            at.getWorld().dropItemNaturally(at, items.createJukebox());
        }
        dropDisc(box, at);
        music.remove(box);
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
            MusicBoxes.Box box = music.get(block);
            if (box != null) {
                it.remove();
                Location at = block.getLocation().add(0.5, 0.5, 0.5);
                block.setType(Material.AIR);
                at.getWorld().dropItemNaturally(at, items.createJukebox());
                dropDisc(box, at);
                music.remove(box);
            }
        }
    }

    /** Drops every disc in the queue. */
    private void dropDisc(MusicBoxes.Box box, Location at) {
        for (Track track : box.queue()) {
            at.getWorld().dropItemNaturally(at, music.itemFor(track));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> music.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> music.get(b) != null)) {
            event.setCancelled(true);
        }
    }

    /** Hoppers can push discs into (or pull them out of) a vanilla jukebox; ours is handled by hand only. */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (isRadioJukebox(event.getDestination().getLocation()) || isRadioJukebox(event.getSource().getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean isRadioJukebox(Location location) {
        return location != null && location.getWorld() != null && music.get(location.getBlock()) != null;
    }

    // ------------------------------------------------------------- interaction

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean cable = items.isCable(hand);

        Radio terminal = manager.get(block);
        if (terminal != null && cable) {
            event.setCancelled(true);
            selectTerminal(player, terminal);
            return;
        }

        MusicBoxes.Box box = music.get(block);
        if (box == null) {
            // A Song Disc only means something in a Radio Jukebox; a plain jukebox would play a vanilla song.
            if (block.getType() == Material.JUKEBOX && items.isSongDisc(hand)) {
                event.setCancelled(true);
                deny(player, "§cThis disc only plays in a Radio Jukebox.");
            }
            return;
        }
        // Sneaking with something else in hand means "place a block against it", as in vanilla.
        if (player.isSneaking() && !hand.isEmpty() && !cable && !MusicBoxes.isDisc(hand)) {
            return;
        }
        event.setCancelled(true);
        if (cable) {
            linkBox(player, box);
        } else if (MusicBoxes.isDisc(hand)) {
            insertDisc(player, box, hand);
        } else {
            openMenu(player, box);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        music.clearSelection(event.getPlayer());
    }

    // ----------------------------------------------------------------- linking

    private void selectTerminal(Player player, Radio terminal) {
        if (!RadioManager.isOperator(player, terminal)) {
            deny(player, "§cOnly the terminal's operator (" + terminal.creatorName() + ") can link jukeboxes to it.");
            return;
        }
        music.select(player, terminal);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.4f);
        Chat.send(player, "§aTerminal selected" + (terminal.isTuned() ? " (" + terminal.channel() + ")" : "")
                + "§a. Now right-click a Radio Jukebox, Signal Repeater or Signal Jammer with the cable.");
    }

    private void linkBox(Player player, MusicBoxes.Box box) {
        String key = music.selected(player);
        Radio terminal = key == null ? null : manager.get(key);
        if (terminal == null) {
            deny(player, "§cRight-click a Radio Terminal with the cable first, then the jukebox.");
            return;
        }
        if (!RadioManager.isOperator(player, terminal)) {
            deny(player, "§cOnly the terminal's operator can link jukeboxes to it.");
            return;
        }
        Location from = box.center();
        Location to = terminal.center();
        if (from == null || to == null || !from.getWorld().equals(to.getWorld())) {
            deny(player, "§cThe jukebox and the terminal must be in the same world.");
            return;
        }
        int perCable = Math.max(1, plugin.getConfig().getInt("music.blocks-per-cable", 4));
        int maxLength = plugin.getConfig().getInt("music.max-link-length", 16);
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
        music.link(box, terminal);
        music.clearSelection(player);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
        music.flashLine(player, from, to);
        Chat.send(player, "§aJukebox linked to the terminal" + (terminal.isTuned() ? " on §f" + terminal.channel() : "")
                + "§a. Used " + (free ? 0 : needed) + " cable" + (needed == 1 ? "" : "s") + ".");
    }

    // ------------------------------------------------------------------- discs

    private void insertDisc(Player player, MusicBoxes.Box box, ItemStack disc) {
        Radio terminal = music.terminalOf(box);
        if (terminal == null) {
            deny(player, "§cThis jukebox is not linked to a Radio Terminal. Use Signal Cables.");
            return;
        }
        if (!music.canPlayOn(player, terminal)) {
            deny(player, "§cOnly people who can speak on this channel can play music on it.");
            return;
        }
        Track track;
        if (items.isSongDisc(disc)) {
            String id = items.songId(disc);
            if (id == null || !music.songs().isActive(id)) {
                deny(player, "§cThat song has been removed, so this disc can't play.");
                return;
            }
            track = Track.song(id);
        } else {
            track = Track.vanilla(disc.getType());
        }
        if (box.queue().size() >= music.capacity()) {
            deny(player, "§cThe queue is full (" + music.capacity() + " discs). Right-click with an empty hand to manage it.");
            return;
        }
        boolean wouldStart = box.queue().isEmpty() || box.finished();
        if (wouldStart && music.channelBusy(box, terminal)) {
            deny(player, "§cThis channel is already playing a disc on another jukebox.");
            return;
        }
        music.addTrack(box, track);
        if (player.getGameMode() != GameMode.CREATIVE) {
            disc.setAmount(disc.getAmount() - 1);
            player.getInventory().setItemInMainHand(disc.isEmpty() ? null : disc);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        String song = music.name(track);
        if (box.queue().size() == 1) {
            Chat.send(player, music.isWorking(terminal)
                    ? "§aNow playing " + song + " over the radio."
                    : "§eDisc loaded, but the terminal has no power. It will play when it does.");
        } else {
            Chat.send(player, "§aAdded " + song + " to the queue (" + box.queue().size() + "/" + music.capacity() + ").");
        }
    }

    // -------------------------------------------------------------------- menu

    private void openMenu(Player player, MusicBoxes.Box box) {
        player.openInventory(new JukeboxGui(box, music, manager).getInventory());
    }

    /** The owner, an admin, or anyone who may play on the linked channel may manage the queue. */
    private boolean mayManage(Player player, MusicBoxes.Box box) {
        Radio terminal = music.terminalOf(box);
        return player.getName().equals(box.owner()) || player.hasPermission("radionetwork.admin")
                || (terminal != null && music.canPlayOn(player, terminal));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof JukeboxGui gui)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true); // buttons only; nothing moves in or out
        int slot = event.getRawSlot();
        MusicBoxes.Box box = music.get(gui.boxKey());
        if (box == null) {
            player.closeInventory();
            return;
        }
        boolean isDiscSlot = slot >= 0 && slot < 9;
        if (slot == JukeboxGui.SLOT_ADD_SONG) {
            addSongButton(player);   // open to anyone with a Blank Disc; it manages no queue
            return;
        }
        boolean isButton = slot == JukeboxGui.SLOT_SKIP || slot == JukeboxGui.SLOT_REPEAT || slot == JukeboxGui.SLOT_TAKE_ALL;
        if (!isDiscSlot && !isButton) {
            return;
        }
        if (!mayManage(player, box)) {
            deny(player, "§cOnly the jukebox's owner, or people who can speak on its channel, can manage the queue.");
            return;
        }
        switch (slot) {
            case JukeboxGui.SLOT_SKIP -> {
                if (box.queue().isEmpty()) {
                    deny(player, "§cThe queue is empty.");
                    return;
                }
                music.skip(box);
                Chat.send(player, "§7Skipped.");
            }
            case JukeboxGui.SLOT_REPEAT -> {
                MusicBoxes.RepeatMode mode = music.cycleMode(box);
                Chat.send(player, "§7Repeat: §f" + mode.label());
            }
            case JukeboxGui.SLOT_TAKE_ALL -> {
                var all = music.takeAll(box);
                if (all.isEmpty()) {
                    deny(player, "§cThe queue is already empty.");
                    return;
                }
                all.forEach(track -> player.give(music.itemFor(track)));
                Chat.send(player, "§7Took out " + all.size() + " disc" + (all.size() == 1 ? "" : "s") + ". The music stops.");
            }
            default -> {
                Track taken = music.removeDisc(box, slot);
                if (taken == null) {
                    return;
                }
                player.give(music.itemFor(taken));
                Chat.send(player, "§7Took out " + music.name(taken) + ".");
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
        gui.refresh(box, music, manager);
    }

    /** The Add song button: needs a Blank Disc in hand; asks for a name and a link, then downloads the song. */
    private void addSongButton(Player player) {
        String problem = uploads.problem(player);
        if (problem != null) {
            deny(player, problem);
            return;
        }
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> TextPrompt.openTwo(plugin, player, "Add a song",
                "Song name (blank = the file's title)", "", 32,
                "Link to an .mp3 file (https)", "", 1000,
                (name, link) -> uploads.begin(player, name, link)));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof JukeboxGui) {
            event.setCancelled(true);
        }
    }

    private void deny(Player player, String message) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        Chat.send(player, message);
    }
}
