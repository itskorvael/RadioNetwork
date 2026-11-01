package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The Signal Scanner's Decoder: a code-breaking puzzle. The scanner hides a code of coloured pieces; pick colours along
 * the bottom row, press "Try this code", and read the two green and yellow counters: how many pieces are the right
 * colour in the right place, and how many are the right colour in the wrong place. The last four guesses stay on the board
 * (every guess is also written in chat). Every slot is a display button; the state lives in {@link Scanners}.
 */
public final class ScannerGui implements InventoryHolder {

    public static final int HISTORY_ROWS = 4;
    public static final int CURRENT_ROW = 4;
    public static final int SLOT_INFO = 43;
    public static final int SLOT_STATUS = 44;
    public static final int PALETTE_START = 45;
    public static final int SLOT_RETUNE = 51;
    public static final int SLOT_UNDO = 52;
    public static final int SLOT_SUBMIT = 53;

    private static final Material[] WOOL = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL,
            Material.LIME_WOOL, Material.LIGHT_BLUE_WOOL, Material.PURPLE_WOOL};

    private final Inventory inventory;

    public ScannerGui(String channel) {
        this.inventory = Bukkit.createInventory(this, 54, Chat.text("§5Decoder §8- §f" + channel));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /**
     * Draws the board.
     *
     * @param wait seconds until the next guess is allowed, and {@code lock} seconds the Decoder stays recalibrating
     */
    public void render(Scanners.Puzzle puzzle, int stages, int maxGuesses, int bars, double wait, double lock) {
        ItemStack blank = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < 54; slot++) {
            inventory.setItem(slot, blank);
        }
        int slots = puzzle.slots();
        List<Scanners.Guess> guesses = puzzle.guesses();
        int shown = Math.min(HISTORY_ROWS, guesses.size());
        for (int row = 0; row < HISTORY_ROWS; row++) {
            int base = row * 9;
            int index = guesses.size() - shown + row; // the newest guess sits lowest
            boolean has = row < shown;
            for (int i = 0; i < slots; i++) {
                inventory.setItem(base + i, has ? peg(guesses.get(index).pegs()[i]) : button(Material.GRAY_STAINED_GLASS_PANE, "§8-"));
            }
            if (has) {
                Scanners.Guess guess = guesses.get(index);
                inventory.setItem(base + 7, counter(Material.LIME_STAINED_GLASS_PANE, guess.exact(), "§a", "in the right place"));
                inventory.setItem(base + 8, counter(Material.YELLOW_STAINED_GLASS_PANE, guess.misplaced(), "§e", "right colour, wrong place"));
            }
        }
        for (int i = 0; i < slots; i++) {
            int color = puzzle.current()[i];
            inventory.setItem(CURRENT_ROW * 9 + i, color < 0 ? button(Material.WHITE_STAINED_GLASS_PANE, "§7Empty") : peg(color));
        }
        inventory.setItem(SLOT_INFO, button(Material.CLOCK, "§bCode " + (puzzle.stage() + 1) + " of " + stages,
                "§7Break the hidden code of §f" + slots + "§7 colours.",
                "§7Guess " + Math.min(guesses.size() + 1, maxGuesses) + " of " + maxGuesses + ".",
                "§aGreen§7: right colour, right place.",
                "§eYellow§7: right colour, wrong place.",
                "§8Colours can repeat. Every guess is in chat too."));
        String state = lock > 0 ? "§crecalibrating, " + (int) Math.ceil(lock) + " s"
                : "§a" + "|".repeat(Math.max(0, bars)) + "§8" + "|".repeat(Math.max(0, 4 - bars));
        inventory.setItem(SLOT_STATUS, button(Material.AMETHYST_SHARD, "§5Signal", "§7Signal: " + state,
                "§7A weak signal means a longer wait", "§7between guesses.", "§8No signal closes the Decoder."));
        for (int color = 0; color < Scanners.COLORS; color++) {
            ItemStack palette = peg(color);
            ItemMeta meta = palette.getItemMeta();
            meta.lore(List.of(Chat.item("§eClick to add this colour.")));
            palette.setItemMeta(meta);
            inventory.setItem(PALETTE_START + color, palette);
        }
        inventory.setItem(SLOT_RETUNE, button(Material.WRITABLE_BOOK, "§6Change frequency", "§eClick to type another one."));
        inventory.setItem(SLOT_UNDO, button(Material.BARRIER, "§cTake back the last colour"));
        boolean ready = puzzle.filled() >= slots;
        inventory.setItem(SLOT_SUBMIT, button(Material.EMERALD, "§aTry this code",
                ready ? (wait > 0 ? "§7Ready in §f" + (int) Math.ceil(wait) + " s§7." : "§eClick to try it.")
                        : "§7Pick " + (slots - puzzle.filled()) + " more colour" + (slots - puzzle.filled() == 1 ? "" : "s") + "."));
    }

    private static ItemStack peg(int color) {
        return button(WOOL[color], Scanners.COLOR_CODES[color] + Scanners.COLOR_NAMES[color]);
    }

    private static ItemStack counter(Material pane, int amount, String code, String what) {
        ItemStack stack = amount > 0 ? button(pane, code + amount + " " + what) : button(Material.GRAY_STAINED_GLASS_PANE, "§80 " + what);
        stack.setAmount(Math.max(1, amount));
        return stack;
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item(name));
        if (lore.length > 0) {
            meta.lore(java.util.Arrays.stream(lore).map(Chat::item).toList());
        }
        item.setItemMeta(meta);
        return item;
    }
}
