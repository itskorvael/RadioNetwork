package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The craftable items, each a normal item carrying a hidden tag:
 * <ul>
 *   <li>Radio Terminal (a Lodestone) - the stationary radio.</li>
 *   <li>Receiver Core (an Echo Shard) - the intermediate part, crafted from circuit components.</li>
 *   <li>Pocket Receiver (a Compass) - the listen-only portable, crafted from a Core, a Compass and a
 *       Lightning Rod. It remembers its tuned channel.</li>
 *   <li>Underground Antenna (a Blaze Rod) - fits a receiver's Chassis slot.</li>
 *   <li>Earpiece (a Nautilus Shell) - also fits the Chassis slot (one part at a time); it makes the audio private.</li>
 *   <li>Radio Jukebox (a Jukebox) - plays discs over a linked terminal's channel.</li>
 *   <li>Signal Cable (a Copper Chain) - links a terminal to a Radio Jukebox or Signal Repeater; never placeable.</li>
 *   <li>Signal Repeater (a Lightning Rod) - linked to a terminal, it makes that terminal pass signals on.</li>
 *   <li>Blank Disc (a Disc Fragment) - turned into a Song Disc by adding a note-block song from a link.</li>
 *   <li>Song Disc (a Music Disc) - plays one player-added note-block song in a Radio Jukebox.</li>
 * </ul>
 * All the recipes are read from {@code config.yml}, with built-in defaults if the config is broken.
 */
public final class RadioItem {

    /** What a Signal Cable is made of: a Copper Chain, or a plain Chain on Minecraft versions before 1.21.9, which have none. */
    private static final Material CABLE_MATERIAL = firstMaterial("COPPER_CHAIN", "CHAIN", "IRON_CHAIN");

    private static Material firstMaterial(String... names) {
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                return material;
            }
        }
        throw new IllegalStateException("None of these materials exist on this server: " + String.join(", ", names));
    }

    /** Token for the Receiver Core in the receiver recipe's ingredient list. */
    public static final String CORE_TOKEN = "RADIO_CORE";

    private final RadioNetworkPlugin plugin;
    private final NamespacedKey terminalTag;
    private final NamespacedKey coreTag;
    private final NamespacedKey receiverTag;
    private final NamespacedKey channelTag;
    private final NamespacedKey antennaTag;
    private final NamespacedKey blankDiscTag;
    private final NamespacedKey songDiscTag;
    private final NamespacedKey songIdTag;
    private final NamespacedKey blankDiscRecipe;
    private final NamespacedKey jukeboxTag;
    private final NamespacedKey cableTag;
    private final NamespacedKey jukeboxRecipe;
    private final NamespacedKey cableRecipe;
    private final NamespacedKey repeaterTag;
    private final NamespacedKey repeaterRecipe;
    private final NamespacedKey jammerTag;
    private final NamespacedKey jammerRecipe;
    private final NamespacedKey scannerTag;
    private final NamespacedKey scannerIdTag;
    private final NamespacedKey scannerChannelTag;
    private final NamespacedKey scannerRecipe;
    private final NamespacedKey earpieceTag;
    private final NamespacedKey earpieceInstalledTag;
    private final NamespacedKey earpieceRecipe;
    private final NamespacedKey autoTag;
    private final NamespacedKey autoScanTag;
    private final NamespacedKey antennaInstalledTag;
    private final NamespacedKey antennaRecipe;
    private final NamespacedKey terminalRecipe;
    private final NamespacedKey coreRecipe;
    private final NamespacedKey receiverRecipe;
    private final NamespacedKey decommissionedTag;

    /**
     * The things that can be decommissioned: the same block, switched off for good. A decommissioned item looks
     * like the real one without the glint, carries no radio tag and never registers, so it is pure scenery.
     */
    public enum Retired {
        TERMINAL("terminal", Material.LODESTONE, "Radio Terminal"),
        JUKEBOX("jukebox", Material.JUKEBOX, "Radio Jukebox"),
        REPEATER("repeater", Material.LIGHTNING_ROD, "Signal Repeater"),
        JAMMER("jammer", Jammers.BLOCK, "Signal Jammer");

        private final String id;
        private final Material block;
        private final String title;

        Retired(String id, Material block, String title) {
            this.id = id;
            this.block = block;
            this.title = title;
        }

        /** The name used in {@code /radio give decommissioned_<id>}. */
        public String id() {
            return id;
        }

        /** The block (and item) material. */
        public Material block() {
            return block;
        }

        public String title() {
            return title;
        }

        /** Which kind a block or item of this material would be, or null. */
        public static @Nullable Retired of(Material material) {
            for (Retired kind : values()) {
                if (kind.block == material) {
                    return kind;
                }
            }
            return null;
        }
    }

    public RadioItem(RadioNetworkPlugin plugin) {
        this.plugin = plugin;
        this.decommissionedTag = new NamespacedKey(plugin, "decommissioned");
        this.terminalTag = new NamespacedKey(plugin, "radio_terminal");
        this.coreTag = new NamespacedKey(plugin, "receiver_core");
        this.receiverTag = new NamespacedKey(plugin, "pocket_receiver");
        this.channelTag = new NamespacedKey(plugin, "receiver_channel");
        this.antennaTag = new NamespacedKey(plugin, "underground_antenna");
        this.blankDiscTag = new NamespacedKey(plugin, "blank_disc");
        this.songDiscTag = new NamespacedKey(plugin, "song_disc");
        this.songIdTag = new NamespacedKey(plugin, "song_id");
        this.blankDiscRecipe = new NamespacedKey(plugin, "blank_disc");
        this.jukeboxTag = new NamespacedKey(plugin, "radio_jukebox");
        this.cableTag = new NamespacedKey(plugin, "signal_cable");
        this.jukeboxRecipe = new NamespacedKey(plugin, "radio_jukebox");
        this.cableRecipe = new NamespacedKey(plugin, "signal_cable");
        this.repeaterTag = new NamespacedKey(plugin, "signal_repeater");
        this.repeaterRecipe = new NamespacedKey(plugin, "signal_repeater");
        this.jammerTag = new NamespacedKey(plugin, "signal_jammer");
        this.jammerRecipe = new NamespacedKey(plugin, "signal_jammer");
        this.scannerTag = new NamespacedKey(plugin, "signal_scanner");
        this.scannerIdTag = new NamespacedKey(plugin, "scanner_id");
        this.scannerChannelTag = new NamespacedKey(plugin, "scanner_channel");
        this.scannerRecipe = new NamespacedKey(plugin, "signal_scanner");
        this.earpieceTag = new NamespacedKey(plugin, "earpiece");
        this.earpieceInstalledTag = new NamespacedKey(plugin, "receiver_earpiece");
        this.earpieceRecipe = new NamespacedKey(plugin, "earpiece");
        this.autoTag = new NamespacedKey(plugin, "receiver_auto_channel");
        this.autoScanTag = new NamespacedKey(plugin, "receiver_autoscan");
        this.antennaInstalledTag = new NamespacedKey(plugin, "receiver_antenna");
        this.antennaRecipe = new NamespacedKey(plugin, "underground_antenna");
        this.terminalRecipe = new NamespacedKey(plugin, "radio_terminal");
        this.coreRecipe = new NamespacedKey(plugin, "receiver_core");
        this.receiverRecipe = new NamespacedKey(plugin, "pocket_receiver");
    }

    /** Recipe key of the Radio Terminal. */
    public NamespacedKey recipeKey() {
        return terminalRecipe;
    }

    public NamespacedKey receiverRecipeKey() {
        return receiverRecipe;
    }

    public NamespacedKey jukeboxRecipeKey() {
        return jukeboxRecipe;
    }

    public NamespacedKey earpieceRecipeKey() {
        return earpieceRecipe;
    }

    public NamespacedKey repeaterRecipeKey() {
        return repeaterRecipe;
    }

    public NamespacedKey jammerRecipeKey() {
        return jammerRecipe;
    }

    public NamespacedKey scannerRecipeKey() {
        return scannerRecipe;
    }

    public List<NamespacedKey> recipeKeys() {
        List<NamespacedKey> keys = new java.util.ArrayList<>(List.of(terminalRecipe, coreRecipe, receiverRecipe, antennaRecipe,
                earpieceRecipe, jukeboxRecipe, cableRecipe, repeaterRecipe, jammerRecipe, scannerRecipe, blankDiscRecipe));
        keys.addAll(decommissionRecipeKeys());
        return keys;
    }

    // ---------------------------------------------------------- decommissioned

    /** A decommissioned copy of a terminal, jukebox, repeater or jammer: scenery that does nothing. */
    public ItemStack createDecommissioned(Retired kind) {
        ItemStack item = new ItemStack(kind.block());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§7Decommissioned " + kind.title()));
        meta.lore(List.of(Chat.item("§7Out of service. Display only.")));
        meta.getPersistentDataContainer().set(decommissionedTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** The kind of a decommissioned item, or null for anything else. */
    public @Nullable Retired decommissionedKind(@Nullable ItemStack item) {
        return item == null || item.isEmpty() || !has(item, decommissionedTag) ? null : Retired.of(item.getType());
    }

    public boolean isDecommissioned(@Nullable ItemStack item) {
        return decommissionedKind(item) != null;
    }

    /** The kind of a working terminal, jukebox, repeater or jammer item (the ones that can be decommissioned), or null. */
    public @Nullable Retired liveKind(@Nullable ItemStack item) {
        if (isRadio(item)) {
            return Retired.TERMINAL;
        }
        if (isJukebox(item)) {
            return Retired.JUKEBOX;
        }
        if (isRepeater(item)) {
            return Retired.REPEATER;
        }
        // An older jammer item (a Respawn Anchor) is left out: it only works once placed.
        return isJammer(item) && item.getType() == Jammers.BLOCK ? Retired.JAMMER : null;
    }

    public NamespacedKey decommissionRecipeKey(Retired kind) {
        return new NamespacedKey(plugin, "decommissioned_" + kind.id());
    }

    public List<NamespacedKey> decommissionRecipeKeys() {
        return java.util.Arrays.stream(Retired.values()).map(this::decommissionRecipeKey).toList();
    }

    /** The kind a decommission recipe makes, or null when the key is not one of them. */
    public @Nullable Retired decommissionRecipeKind(NamespacedKey key) {
        for (Retired kind : Retired.values()) {
            if (decommissionRecipeKey(kind).equals(key)) {
                return kind;
            }
        }
        return null;
    }

    /** Shapeless, one ingredient: put a working item alone in a crafting grid to retire it for good. */
    private ShapelessRecipe decommissionRecipe(Retired kind) {
        ShapelessRecipe recipe = new ShapelessRecipe(decommissionRecipeKey(kind), createDecommissioned(kind));
        recipe.addIngredient(kind.block());
        return recipe;
    }

    // ------------------------------------------------------------------- items

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.LODESTONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Radio Terminal"));
        meta.lore(List.of(
                Chat.item("§7Transmits and receives on a frequency.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(terminalTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createCore() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Receiver Core"));
        meta.lore(List.of(
                Chat.item("§7A tuned circuit for radio equipment.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(coreTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** A fresh, switched-off Pocket Receiver. */
    public ItemStack createReceiver() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(receiverTag, PersistentDataType.BYTE, (byte) 1);
        applyLore(meta);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createAntenna() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Underground Antenna"));
        meta.lore(List.of(
                Chat.item("§7Pocket Receiver part. Hears through rock.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(antennaTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createJukebox() {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Radio Jukebox"));
        meta.lore(List.of(
                Chat.item("§7Plays discs over a linked terminal's channel.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(jukeboxTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isJukebox(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.JUKEBOX && has(item, jukeboxTag);
    }

    public ItemStack createBlankDisc(int amount) {
        ItemStack item = new ItemStack(Material.DISC_FRAGMENT_5, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Blank Disc"));
        meta.lore(List.of(
                Chat.item("§7Records a song from a link.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(blankDiscTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBlankDisc(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.DISC_FRAGMENT_5 && has(item, blankDiscTag);
    }

    /** A disc that plays one registered song; it carries the song's id and shows its name. */
    public ItemStack createSongDisc(SongLibrary.Meta song) {
        ItemStack item = new ItemStack(Material.MUSIC_DISC_5);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§d" + song.name()));
        meta.lore(List.of(
                Chat.item("§7Added by " + song.uploader()),
                Chat.item("§7Length: " + Chat.duration(song.seconds()) + (song.isAudio() ? " §8(audio)" : " §8(note blocks)"))));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(songDiscTag, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(songIdTag, PersistentDataType.STRING, song.id());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSongDisc(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.MUSIC_DISC_5 && has(item, songDiscTag);
    }

    /** The song id carried by a Song Disc, or null for anything else. */
    public @Nullable String songId(@Nullable ItemStack item) {
        if (!isSongDisc(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(songIdTag, PersistentDataType.STRING);
    }

    public ItemStack createCable(int amount) {
        ItemStack item = new ItemStack(CABLE_MATERIAL, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Signal Cable"));
        meta.lore(List.of(
                Chat.item("§7Links a terminal to a jukebox, repeater or jammer."),
                Chat.item("§8One cable per " + plugin.getConfig().getInt("music.blocks-per-cable", 4) + " blocks of distance.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(cableTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCable(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == CABLE_MATERIAL && has(item, cableTag);
    }

    public ItemStack createRepeater() {
        ItemStack item = new ItemStack(Material.LIGHTNING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Signal Repeater"));
        meta.lore(List.of(
                Chat.item("§7Passes on every signal its linked terminal hears.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(repeaterTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRepeater(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.LIGHTNING_ROD && has(item, repeaterTag);
    }

    /** A waxed Copper Bulb whose light shows what the jammer is doing; see {@link JammerListener}. */
    public ItemStack createJammer() {
        ItemStack item = new ItemStack(Jammers.BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§cSignal Jammer"));
        meta.lore(List.of(
                Chat.item("§7Jams every channel around its linked terminal."),
                Chat.item("§cBurns out in a blast if the terminal runs dry.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(jammerTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isJammer(@Nullable ItemStack item) {
        // Also the Respawn Anchor older jammer items were; placing one turns it into the bulb.
        return item != null && !item.isEmpty() && Jammers.isJammerBlock(item.getType()) && has(item, jammerTag);
    }

    /** How many Signal Cables the player carries (inventory and off hand). */
    public int countCables(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isCable(stack)) {
                total += stack.getAmount();
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return total + (isCable(off) ? off.getAmount() : 0);
    }

    /** Takes {@code amount} cables, starting with the main hand. */
    public void takeCables(Player player, int amount) {
        PlayerInventory inventory = player.getInventory();
        int remaining = amount;
        ItemStack main = inventory.getItemInMainHand();
        if (isCable(main)) {
            int take = Math.min(remaining, main.getAmount());
            main.setAmount(main.getAmount() - take);
            inventory.setItemInMainHand(main.isEmpty() ? null : main);
            remaining -= take;
        }
        for (int slot = 0; slot < inventory.getStorageContents().length && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isCable(stack)) {
                int take = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, stack.isEmpty() ? null : stack);
                remaining -= take;
            }
        }
        ItemStack off = inventory.getItemInOffHand();
        if (remaining > 0 && isCable(off)) {
            int take = Math.min(remaining, off.getAmount());
            off.setAmount(off.getAmount() - take);
            inventory.setItemInOffHand(off.isEmpty() ? null : off);
        }
    }

    public ItemStack createEarpiece() {
        ItemStack item = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Chat.item("§6Earpiece"));
        meta.lore(List.of(
                Chat.item("§7Pocket Receiver part. Only you hear the audio.")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(earpieceTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isEarpiece(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.NAUTILUS_SHELL && has(item, earpieceTag);
    }

    /** True when this receiver has an Earpiece installed. */
    public boolean hasEarpiece(@Nullable ItemStack receiver) {
        return isReceiver(receiver)
                && receiver.getItemMeta().getPersistentDataContainer().has(earpieceInstalledTag, PersistentDataType.BYTE);
    }

    /** A copy of the receiver with the earpiece installed or removed. */
    public ItemStack withEarpiece(ItemStack receiver, boolean installed) {
        return edit(receiver, meta -> {
            if (installed) {
                meta.getPersistentDataContainer().set(earpieceInstalledTag, PersistentDataType.BYTE, (byte) 1);
            } else {
                meta.getPersistentDataContainer().remove(earpieceInstalledTag);
            }
        });
    }

    public boolean isAntenna(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.BLAZE_ROD && has(item, antennaTag);
    }

    /** True when this receiver has an Underground Antenna installed. */
    public boolean hasAntenna(@Nullable ItemStack receiver) {
        return isReceiver(receiver)
                && receiver.getItemMeta().getPersistentDataContainer().has(antennaInstalledTag, PersistentDataType.BYTE);
    }

    /** A copy of the receiver with the antenna installed or removed. */
    public ItemStack withAntenna(ItemStack receiver, boolean installed) {
        return edit(receiver, meta -> {
            if (installed) {
                meta.getPersistentDataContainer().set(antennaInstalledTag, PersistentDataType.BYTE, (byte) 1);
            } else {
                meta.getPersistentDataContainer().remove(antennaInstalledTag);
            }
        });
    }

    public boolean isRadio(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.LODESTONE && has(item, terminalTag);
    }

    public boolean isCore(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.ECHO_SHARD && has(item, coreTag);
    }

    public boolean isReceiver(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.COMPASS && has(item, receiverTag);
    }

    private static boolean has(ItemStack item, NamespacedKey key) {
        return item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** The channel the player chose for this receiver, or null when it is switched off. */
    public @Nullable String preferredChannel(@Nullable ItemStack item) {
        return stringTag(item, channelTag);
    }

    /** The channel auto-scan temporarily moved the receiver to, or null when it is on its own channel. */
    public @Nullable String autoChannel(@Nullable ItemStack item) {
        return stringTag(item, autoTag);
    }

    /** The channel the receiver actually listens to right now: the auto-scan channel, else the chosen one. */
    public @Nullable String receiverChannel(@Nullable ItemStack item) {
        String auto = autoChannel(item);
        return auto != null ? auto : preferredChannel(item);
    }

    private @Nullable String stringTag(@Nullable ItemStack item, NamespacedKey key) {
        if (!isReceiver(item)) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return value == null || value.isEmpty() ? null : value;
    }

    /** Whether auto-scan is on for this receiver; receivers never toggled follow {@code defaultValue}. */
    public boolean autoScanEnabled(@Nullable ItemStack item, boolean defaultValue) {
        if (!isReceiver(item)) {
            return false;
        }
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(autoScanTag, PersistentDataType.BYTE);
        return flag == null ? defaultValue : flag == 1;
    }

    /** The receiver tuned to {@code channel} by the player (null switches it off); any auto-scan hop is cleared. */
    public ItemStack withChannel(ItemStack receiver, @Nullable String channel) {
        return edit(receiver, meta -> {
            if (channel == null) {
                meta.getPersistentDataContainer().remove(channelTag);
            } else {
                meta.getPersistentDataContainer().set(channelTag, PersistentDataType.STRING, channel);
            }
            meta.getPersistentDataContainer().remove(autoTag);
        });
    }

    /** The receiver moved to (or back from, with null) an auto-scan channel; the chosen channel is kept. */
    public ItemStack withAuto(ItemStack receiver, @Nullable String auto) {
        return edit(receiver, meta -> {
            if (auto == null) {
                meta.getPersistentDataContainer().remove(autoTag);
            } else {
                meta.getPersistentDataContainer().set(autoTag, PersistentDataType.STRING, auto);
            }
        });
    }

    /** Turns auto-scan on or off; turning it off returns the receiver to its chosen channel. */
    public ItemStack withAutoScan(ItemStack receiver, boolean enabled) {
        return edit(receiver, meta -> {
            meta.getPersistentDataContainer().set(autoScanTag, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
            if (!enabled) {
                meta.getPersistentDataContainer().remove(autoTag);
            }
        });
    }

    private ItemStack edit(ItemStack receiver, java.util.function.Consumer<ItemMeta> change) {
        ItemStack item = receiver.clone();
        ItemMeta meta = item.getItemMeta();
        change.accept(meta);
        applyLore(meta);
        item.setItemMeta(meta);
        return item;
    }

    /** Rewrites the name and tooltip from the receiver's stored state. */
    private void applyLore(ItemMeta meta) {
        var data = meta.getPersistentDataContainer();
        String preferred = data.get(channelTag, PersistentDataType.STRING);
        String auto = data.get(autoTag, PersistentDataType.STRING);
        boolean antenna = data.has(antennaInstalledTag, PersistentDataType.BYTE);
        boolean earpiece = data.has(earpieceInstalledTag, PersistentDataType.BYTE);
        Byte flag = data.get(autoScanTag, PersistentDataType.BYTE);
        boolean autoScan = flag == null ? plugin.getConfig().getBoolean("portable.auto-scan-default", true) : flag == 1;

        String tuned = auto != null
                ? "§f" + auto + " §8(auto-scan" + (preferred == null ? "" : ", wanted " + preferred) + ")"
                : preferred == null ? "§8off" : "§f" + preferred;
        meta.displayName(Chat.item("§6Pocket Receiver"));
        meta.lore(List.of(
                Chat.item("§7Tuned to: " + tuned),
                Chat.item("§7Chassis: " + (antenna || earpiece
                        ? "§f" + (antenna ? "Underground Antenna" : "") + (antenna && earpiece ? ", " : "") + (earpiece ? "Earpiece" : "")
                        : "§8empty")),
                Chat.item("§7Auto-scan: " + (autoScan ? "§aon" : "§8off")),
                Chat.item("§8Listen only.")));
    }


    // ----------------------------------------------------------------- scanner

    /** A fresh, switched-off Signal Scanner (a tagged Spyglass). */
    public ItemStack createScanner() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(scannerTag, PersistentDataType.BYTE, (byte) 1);
        applyScannerLore(meta);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isScanner(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getType() == Material.SPYGLASS && has(item, scannerTag);
    }

    /** The frequency this scanner is tuned to, or null when it is switched off. */
    public @Nullable String scannerChannel(@Nullable ItemStack item) {
        return scannerString(item, scannerChannelTag);
    }

    /** The scanner's own id (given when it is first tuned); decoding progress is kept against it. */
    public @Nullable String scannerId(@Nullable ItemStack item) {
        return scannerString(item, scannerIdTag);
    }

    private @Nullable String scannerString(@Nullable ItemStack item, NamespacedKey key) {
        if (!isScanner(item)) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return value == null || value.isEmpty() ? null : value;
    }

    /** The scanner tuned to {@code channel} (null switches it off); it gets an id the first time it is tuned. */
    public ItemStack withScannerChannel(ItemStack scanner, @Nullable String channel) {
        ItemStack item = scanner.clone();
        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        if (channel == null) {
            data.remove(scannerChannelTag);
        } else {
            data.set(scannerChannelTag, PersistentDataType.STRING, channel);
            if (!data.has(scannerIdTag, PersistentDataType.STRING)) {
                data.set(scannerIdTag, PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
            }
        }
        applyScannerLore(meta);
        item.setItemMeta(meta);
        return item;
    }

    private void applyScannerLore(ItemMeta meta) {
        String channel = meta.getPersistentDataContainer().get(scannerChannelTag, PersistentDataType.STRING);
        meta.displayName(Chat.item("§5Signal Scanner"));
        meta.lore(List.of(
                Chat.item("§7Tuned to: " + (channel == null || channel.isEmpty() ? "§8off" : "§f" + channel)),
                Chat.item("§7Hears encrypted frequencies as noise."),
                Chat.item("§8Listen only.")));
    }

    // ----------------------------------------------------------------- recipes

    /** Registers all three config-driven recipes. */
    public void registerRecipes() {
        unregisterRecipes();
        register(terminalRecipe(), "terminal");
        register(coreRecipe(), "core");
        register(receiverRecipe(), "receiver");
        register(antennaRecipe(), "antenna");
        register(earpieceRecipe(), "earpiece");
        register(jukeboxRecipe(), "jukebox");
        register(cableRecipe(), "cable");
        register(repeaterRecipe(), "repeater");
        register(jammerRecipe(), "jammer");
        register(scannerRecipe(), "scanner");
        register(blankDiscRecipe(), "blank disc");
        for (Retired kind : Retired.values()) {
            register(decommissionRecipe(kind), "decommissioned " + kind.id());
        }
        Bukkit.getOnlinePlayers().forEach(p -> recipeKeys().forEach(p::discoverRecipe));
    }

    private void register(org.bukkit.inventory.Recipe recipe, String name) {
        try {
            Bukkit.addRecipe(recipe);
        } catch (RuntimeException e) {
            String detail = recipe instanceof ShapedRecipe shaped
                    ? " shape=" + java.util.Arrays.toString(shaped.getShape()) + " ingredients=" + shaped.getIngredientMap()
                    : "";
            plugin.getLogger().log(Level.SEVERE, "Could not register the " + name + " recipe" + detail, e);
        }
    }

    public void unregisterRecipes() {
        recipeKeys().forEach(Bukkit::removeRecipe);
    }

    private ShapedRecipe terminalRecipe() {
        ShapedRecipe recipe = shapedFromConfig("recipe", terminalRecipe, create());
        if (recipe == null) {
            plugin.getLogger().info("Terminal recipe missing or invalid in config.yml - using the default antenna recipe.");
            recipe = shaped(terminalRecipe, create(), List.of(" L ", "ROR", " A "), Map.of(
                    'L', Material.LIGHTNING_ROD, 'R', Material.REDSTONE,
                    'O', Material.LODESTONE, 'A', Material.AMETHYST_SHARD));
        }
        return recipe;
    }

    private ShapedRecipe coreRecipe() {
        ShapedRecipe recipe = shapedFromConfig("core-recipe", coreRecipe, createCore());
        if (recipe == null) {
            plugin.getLogger().info("core-recipe missing or invalid in config.yml - using the default circuit core recipe.");
            recipe = shaped(coreRecipe, createCore(), List.of("GCG", "QDQ", "KRK"), Map.of(
                    'G', Material.GOLD_INGOT, 'C', Material.COMPARATOR, 'Q', Material.QUARTZ_BLOCK,
                    'D', Material.DIAMOND, 'K', Material.COPPER_INGOT, 'R', Material.REDSTONE_BLOCK));
        }
        return recipe;
    }

    /** Shapeless: the Core token plus ordinary materials; the Core must be a genuine tagged Core. */
    private ShapelessRecipe receiverRecipe() {
        ShapelessRecipe recipe = shapelessFromConfig();
        if (recipe == null) {
            plugin.getLogger().info("receiver-recipe missing or invalid in config.yml - using the default receiver recipe.");
            recipe = shapeless(List.of(CORE_TOKEN, "COMPASS", "LIGHTNING_ROD"));
        }
        return recipe;
    }

    private @Nullable ShapedRecipe shapedFromConfig(String path, NamespacedKey key, ItemStack result) {
        try {
            List<String> shape = plugin.getConfig().getStringList(path + ".shape");
            ConfigurationSection section = plugin.getConfig().getConfigurationSection(path + ".ingredients");
            if (section == null || shape.isEmpty()) {
                return null;
            }
            Map<Character, Material> ingredients = new HashMap<>();
            for (String id : section.getKeys(false)) {
                Material material = Material.matchMaterial(section.getString(id, ""));
                if (id.length() != 1 || material == null || !material.isItem()) {
                    return null;
                }
                ingredients.put(id.charAt(0), material);
            }
            // Every symbol used in the shape needs an ingredient; a config without the section (an older
            // file) yields none, and the built-in default recipe is used instead.
            for (String row : shape) {
                for (char symbol : row.toCharArray()) {
                    if (symbol != ' ' && !ingredients.containsKey(symbol)) {
                        return null;
                    }
                }
            }
            return shaped(key, result, shape, ingredients);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse recipe " + path, e);
            return null;
        }
    }

    private ShapedRecipe shaped(NamespacedKey key, ItemStack result, List<String> shape, Map<Character, Material> ingredients) {
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape.toArray(String[]::new));
        ingredients.forEach(recipe::setIngredient);
        return recipe;
    }

    private @Nullable ShapelessRecipe shapelessFromConfig() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("receiver-recipe.ingredients");
            return ingredients.isEmpty() ? null : shapeless(ingredients);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse receiver-recipe", e);
            return null;
        }
    }

    /**
     * The only exact-match constructor present in every supported Paper version (26.2 deprecates it in
     * favour of a factory that 26.1.2 lacks), so it is used on purpose.
     */
    @SuppressWarnings("removal")
    private static RecipeChoice exact(ItemStack stack) {
        return new RecipeChoice.ExactChoice(stack);
    }

    private ShapelessRecipe shapeless(List<String> ingredients) {
        return shapeless(receiverRecipe, createReceiver(), ingredients);
    }

    private ShapelessRecipe shapeless(NamespacedKey key, ItemStack result, List<String> ingredients) {
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (String name : ingredients) {
            if (name.equalsIgnoreCase(CORE_TOKEN)) {
                recipe.addIngredient(exact(createCore()));
                continue;
            }
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("Unknown material " + name);
            }
            recipe.addIngredient(material);
        }
        return recipe;
    }

    /** Shapeless: plain materials only. */
    private ShapelessRecipe antennaRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("antenna-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(antennaRecipe, createAntenna(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse antenna-recipe", e);
        }
        plugin.getLogger().info("antenna-recipe missing or invalid in config.yml - using the default antenna recipe.");
        return shapeless(antennaRecipe, createAntenna(),
                List.of("LIGHTNING_ROD", "COPPER_INGOT", "COPPER_INGOT", "REDSTONE_BLOCK"));
    }

    /** Shapeless: a genuine Core, a Nautilus Shell and Leather by default. */
    private ShapelessRecipe earpieceRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("earpiece-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(earpieceRecipe, createEarpiece(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse earpiece-recipe", e);
        }
        plugin.getLogger().info("earpiece-recipe missing or invalid in config.yml - using the default earpiece recipe.");
        return shapeless(earpieceRecipe, createEarpiece(), List.of(CORE_TOKEN, "NAUTILUS_SHELL", "LEATHER"));
    }

    /** Shapeless: a Jukebox, a genuine Core and a Note Block by default. */
    private ShapelessRecipe jukeboxRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("jukebox-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(jukeboxRecipe, createJukebox(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse jukebox-recipe", e);
        }
        plugin.getLogger().info("jukebox-recipe missing or invalid in config.yml - using the default jukebox recipe.");
        return shapeless(jukeboxRecipe, createJukebox(), List.of("JUKEBOX", CORE_TOKEN, "NOTE_BLOCK"));
    }

    /** Shapeless: a lightning rod, a genuine Core, two copper ingots and an amethyst shard by default. */
    private ShapelessRecipe repeaterRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("repeater-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(repeaterRecipe, createRepeater(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse repeater-recipe", e);
        }
        plugin.getLogger().info("repeater-recipe missing or invalid in config.yml - using the default repeater recipe.");
        return shapeless(repeaterRecipe, createRepeater(),
                List.of("LIGHTNING_ROD", CORE_TOKEN, "COPPER_INGOT", "COPPER_INGOT", "AMETHYST_SHARD"));
    }

    /** Shapeless: two genuine Cores, a Wither Skeleton Skull, a Nether Star, two Diamond Blocks and two Ghast Tears by default. */
    private ShapelessRecipe jammerRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("jammer-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(jammerRecipe, createJammer(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse jammer-recipe", e);
        }
        plugin.getLogger().info("jammer-recipe missing or invalid in config.yml - using the default jammer recipe.");
        return shapeless(jammerRecipe, createJammer(), List.of(CORE_TOKEN, CORE_TOKEN, "WITHER_SKELETON_SKULL",
                "NETHER_STAR", "DIAMOND_BLOCK", "DIAMOND_BLOCK", "GHAST_TEAR", "GHAST_TEAR"));
    }


    /** Shapeless: a genuine Core, a Spyglass, an Ender Pearl and an Amethyst Shard by default. */
    private ShapelessRecipe scannerRecipe() {
        try {
            List<String> ingredients = plugin.getConfig().getStringList("scanner-recipe.ingredients");
            if (!ingredients.isEmpty()) {
                return shapeless(scannerRecipe, createScanner(), ingredients);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not parse scanner-recipe", e);
        }
        plugin.getLogger().info("scanner-recipe missing or invalid in config.yml - using the default scanner recipe.");
        return shapeless(scannerRecipe, createScanner(), List.of(CORE_TOKEN, "SPYGLASS", "ENDER_PEARL", "AMETHYST_SHARD"));
    }

    /** Shaped: copper, a gold nugget and redstone; the craft makes {@code cable-amount} cables. */
    private ShapedRecipe cableRecipe() {
        int amount = Math.max(1, plugin.getConfig().getInt("cable-recipe.amount", 2));
        ShapedRecipe recipe = shapedFromConfig("cable-recipe", cableRecipe, createCable(amount));
        if (recipe == null) {
            plugin.getLogger().info("cable-recipe missing or invalid in config.yml - using the default cable recipe.");
            recipe = shaped(cableRecipe, createCable(amount), List.of("CGC", " R "), Map.of(
                    'C', Material.COPPER_INGOT, 'G', Material.GOLD_NUGGET, 'R', Material.REDSTONE));
        }
        return recipe;
    }

    /** Shaped plus: four Coal around a Note Block makes {@code blank-disc-recipe.amount} Blank Discs. */
    private ShapedRecipe blankDiscRecipe() {
        int amount = Math.max(1, plugin.getConfig().getInt("blank-disc-recipe.amount", 2));
        ShapedRecipe recipe = shapedFromConfig("blank-disc-recipe", blankDiscRecipe, createBlankDisc(amount));
        if (recipe == null) {
            plugin.getLogger().info("blank-disc-recipe missing or invalid in config.yml - using the default blank disc recipe.");
            recipe = shaped(blankDiscRecipe, createBlankDisc(amount), List.of(" C ", "CNC", " C "), Map.of(
                    'C', Material.COAL, 'N', Material.NOTE_BLOCK));
        }
        return recipe;
    }
}
