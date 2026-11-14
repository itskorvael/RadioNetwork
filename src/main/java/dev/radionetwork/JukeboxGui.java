package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * The Radio Jukebox menu. The top row is the disc queue (the first filled slot being the playing one);
 * the bottom row has Skip, the repeat mode, Take all and a status line. Every slot is a display button.
 */
public final class JukeboxGui implements InventoryHolder {

    public static final int SLOT_SKIP = 9;
    public static final int SLOT_REPEAT = 10;
    public static final int SLOT_TAKE_ALL = 11;
    public static final int SLOT_ADD_SONG = 12;
    public static final int SLOT_INFO = 17;

    private final Inventory inventory = Bukkit.createInventory(this, 18, Chat.text("Radio Jukebox"));
    private final String boxKey;

    public JukeboxGui(MusicBoxes.Box box, MusicBoxes music, RadioManager manager) {
        this.boxKey = box.key();
        refresh(box, music, manager);
    }

    public String boxKey() {
        return boxKey;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void refresh(MusicBoxes.Box box, MusicBoxes music, RadioManager manager) {
        inventory.clear();
        List<Track> queue = box.queue();
        int capacity = music.capacity();
        for (int slot = 0; slot < 9; slot++) {
            if (slot >= capacity) {
                continue; // beyond the configured queue size
            }
            if (slot < queue.size()) {
                boolean playing = slot == box.position();
                Track track = queue.get(slot);
                ItemStack icon = music.itemFor(track);
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(Chat.item((playing ? "§dPlaying: §f" : "§7#" + (slot + 1) + ": §f") + music.name(track)));
                meta.lore(playing ? List.of(Chat.item(box.finished() ? "§8Finished." : "§7On air.")) : List.of());
                icon.setItemMeta(meta);
                inventory.setItem(slot, icon);
            } else {
                inventory.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, "§8Empty"));
            }
        }
        inventory.setItem(SLOT_SKIP, item(Material.ARROW, "§6Skip"));
        inventory.setItem(SLOT_REPEAT, item(Material.REPEATER, "§6Repeat: §f" + box.mode().label()));
        inventory.setItem(SLOT_ADD_SONG, item(Material.WRITABLE_BOOK, "§6Add a song",
                "§7Needs a Blank Disc in hand."));
        inventory.setItem(SLOT_TAKE_ALL, item(Material.HOPPER, "§6Take all out"));

        Radio terminal = music.terminalOf(box);
        String link;
        if (terminal == null) {
            link = "§cNot linked.";
        } else if (!terminal.isTuned()) {
            link = "§7Linked to an untuned terminal.";
        } else {
            link = "§7Linked to §f" + terminal.channel() + (music.isWorking(terminal) ? " §a(powered)" : " §c(no power)");
        }
        inventory.setItem(SLOT_INFO, item(Material.PAPER, "§bStatus", link,
                "§7Queue: §f" + queue.size() + "/" + capacity));
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item(name));
        meta.lore(Arrays.stream(lore).map(Chat::item).toList());
        item.setItemMeta(meta);
        return item;
    }
}
