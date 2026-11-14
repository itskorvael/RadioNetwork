package dev.radionetwork;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The single {@code /radio} command: frequency management (delegated to {@link FrequencyCommand}),
 * {@code members}, and {@code locate [page]}, which lists every radio with a clickable teleport and is
 * limited to {@code radionetwork.locate} (ops by default).
 */
public final class RadioCommand implements CommandExecutor, TabCompleter {

    public static final String LOCATE_PERMISSION = "radionetwork.locate";
    public static final String ANNOUNCE_PERMISSION = "radionetwork.announce";
    public static final String GIVE_PERMISSION = "radionetwork.give";
    public static final String ADMIN_PERMISSION = "radionetwork.admin";

    /** Every craftable item, by the name used in {@code /radio give}. */
    private static final List<String> ITEM_NAMES =
            List.of("terminal", "core", "receiver", "underground_antenna", "earpiece", "jukebox", "cable", "repeater", "jammer", "scanner", "blank_disc",
                    "decommissioned_terminal", "decommissioned_jukebox", "decommissioned_repeater", "decommissioned_jammer");
    private static final int PAGE_SIZE = 8;

    private final RadioManager manager;
    private final FrequencyCommand frequencyCommand;
    private final RadioItem items;
    private final SongLibrary songs;
    private final SongUploads uploads;

    public RadioCommand(RadioManager manager, FrequencyCommand frequencyCommand, RadioItem items,
                        SongLibrary songs, SongUploads uploads) {
        this.manager = manager;
        this.frequencyCommand = frequencyCommand;
        this.items = items;
        this.songs = songs;
        this.uploads = uploads;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("locate")) {
            locate(sender, args);
        } else if (sub.equals("announce")) {
            announce(sender, args);
        } else if (sub.equals("signs")) {
            signs(sender, args);
        } else if (sub.equals("give")) {
            give(sender, args, label);
        } else if (sub.equals("song")) {
            song(sender, args, label);
        } else if (sub.equals("jammer")) {
            jammer(sender, args, label);
        } else if (sub.equals("members")) {
            frequencyCommand.members(sender);
        } else if (sub.equals("release") && sender.hasPermission(ADMIN_PERMISSION)
                && !(args.length == 2 && args[1].equalsIgnoreCase("confirm"))) {
            release(sender, args);
        } else if (FrequencyCommand.SUBCOMMANDS.contains(sub) && !sub.equals("help")) {
            frequencyCommand.onCommand(sender, command, label, args);
        } else {
            help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        Chat.send(sender, "§6§lRadio commands §7(stand next to a radio)");
        for (String line : List.of(
                "info §8- show this frequency",
                "members §8- owner, trusted and joined players",
                "list §8- frequencies you own",
                "claim §8- claim your radio's unclaimed frequency",
                "mode <open|closed> §8- who may speak",
                "access <public|encrypted> §8- who may join",
                "trust <player> §8- trust a player on this frequency",
                "untrust <player> §8- remove a trusted player",
                "transfer <player> §8- give the frequency away",
                "release [confirm] §8- free the frequency")) {
            Chat.send(sender, "§e/" + label + " §f" + line);
        }
        if (sender.hasPermission(LOCATE_PERMISSION)) {
            Chat.send(sender, "§e/" + label + " §flocate [page] §8- list every radio with teleport links");
        }
        if (sender.hasPermission(LOCATE_PERMISSION)) {
            Chat.send(sender, "§e/" + label + " §fsigns [page] §8- every sign that has announced, who placed it, with teleport links");
        }
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            Chat.send(sender, "§e/" + label + " §frelease §8- (admin) list every frequency, and free any of them");
        }
        Chat.send(sender, "§e/" + label + " §fjammer list §8- what the Signal Jammer next to you is jamming");
        if (sender.hasPermission("radionetwork.songs.add")) {
            Chat.send(sender, "§e/" + label + " §fsong <add|list|info|rename|remove> §8- your note-block songs (add needs a Blank Disc)");
        }
        if (sender.hasPermission(GIVE_PERMISSION)) {
            Chat.send(sender, "§e/" + label + " §fgive <item> [amount] [player] §8- " + String.join(", ", ITEM_NAMES));
        }
        if (sender.hasPermission(ANNOUNCE_PERMISSION)) {
            Chat.send(sender, "§e/" + label + " §fannounce [as:<player>] <world-key> <x> <y> <z> <channel> | <message> §8- free station announcement");
        }
        Chat.send(sender, "§7Set or change a password from the terminal's Frequency Settings torch.");
    }

    /**
     * {@code /radio announce <world-key> <x> <y> <z> <channel> | <message>}: a free announcement on a
     * channel, carried by the nearest powered radio to that point. Built for other plugins (such as
     * AutomatedTrains) and command blocks, so a routine miss (no radio nearby) is silent for the console.
     */
    private void announce(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ANNOUNCE_PERMISSION)) {
            Chat.send(sender, "§cYou don't have permission to make announcements.");
            return;
        }
        String usage = "§cUsage: /radio announce [as:<player>] <world-key> <x> <y> <z> <channel> | <message>";
        // "as:<player>" says whose sign this is: the announcement only goes out if that player may speak on the channel.
        String speaker = null;
        int at = 1;
        if (args.length > 1 && args[1].regionMatches(true, 0, "as:", 0, 3)) {
            speaker = args[1].substring(3);
            at = 2;
            if (speaker.isBlank()) {
                Chat.send(sender, usage);
                return;
            }
        }
        if (args.length < at + 6) {
            Chat.send(sender, usage);
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(args[at]);
        World world = key == null ? null : Bukkit.getWorld(key);
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(args[at + 1]);
            y = Double.parseDouble(args[at + 2]);
            z = Double.parseDouble(args[at + 3]);
        } catch (NumberFormatException e) {
            Chat.send(sender, usage);
            return;
        }
        String rest = String.join(" ", java.util.Arrays.copyOfRange(args, at + 4, args.length));
        int split = rest.indexOf(" | ");
        if (world == null || split < 0) {
            Chat.send(sender, world == null ? "§cUnknown world: " + args[at] : usage);
            return;
        }
        String channel = RadioManager.cleanChannel(rest.substring(0, split));
        String message = Chat.clean(rest.substring(split + 3));
        if (message.length() > 200) {
            message = message.substring(0, 200);
        }
        if (channel.isEmpty() || message.isEmpty()) {
            Chat.send(sender, usage);
            return;
        }
        if (!manager.announce(world, x, y, z, channel, message, speaker) && sender instanceof Player) {
            Chat.send(sender, "§7Nothing was announced: no powered radio on §f" + channel + " §7is near that point"
                    + (speaker == null ? "." : ", or §f" + speaker + " §7may not speak on it."));
        }
    }

    /**
     * {@code /radio give <item> [amount] [player]}: hands out the craftable items. Vanilla /give cannot create
     * them, because each is an ordinary item carrying a hidden tag.
     */
    private void give(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            Chat.send(sender, "§cYou don't have permission to give radio items.");
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, "§cUsage: /" + label + " give <item> [amount] [player]  §7(" + String.join(", ", ITEM_NAMES) + ")");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!ITEM_NAMES.contains(name)) {
            Chat.send(sender, "§cUnknown item. Choose one of: " + String.join(", ", ITEM_NAMES));
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                Chat.send(sender, "§cAmount must be a whole number between 1 and 64.");
                return;
            }
            if (amount < 1 || amount > 64) {
                Chat.send(sender, "§cAmount must be a whole number between 1 and 64.");
                return;
            }
        }
        Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : sender instanceof Player self ? self : null;
        if (target == null) {
            Chat.send(sender, args.length > 3 ? "§cThat player is not online." : "§cName a player: /" + label + " give <item> [amount] <player>");
            return;
        }
        ItemStack stack = switch (name) {
            case "terminal" -> items.create();
            case "core" -> items.createCore();
            case "receiver" -> items.createReceiver();
            case "underground_antenna" -> items.createAntenna();
            case "earpiece" -> items.createEarpiece();
            case "jukebox" -> items.createJukebox();
            case "repeater" -> items.createRepeater();
            case "jammer" -> items.createJammer();
            case "scanner" -> items.createScanner();
            case "blank_disc" -> items.createBlankDisc(1);
            case "decommissioned_terminal" -> items.createDecommissioned(RadioItem.Retired.TERMINAL);
            case "decommissioned_jukebox" -> items.createDecommissioned(RadioItem.Retired.JUKEBOX);
            case "decommissioned_repeater" -> items.createDecommissioned(RadioItem.Retired.REPEATER);
            case "decommissioned_jammer" -> items.createDecommissioned(RadioItem.Retired.JAMMER);
            default -> items.createCable(1);
        };
        stack.setAmount(amount);
        // Anything that does not fit in the inventory drops at the player's feet.
        target.getInventory().addItem(stack).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        Chat.send(sender, "§aGave " + amount + " " + name + (amount == 1 ? "" : "s") + " to " + target.getName() + ".");
        if (!target.equals(sender)) {
            Chat.send(target, "§aYou received " + amount + " " + name + (amount == 1 ? "" : "s") + ".");
        }
    }

    /** {@code /radio jammer list}: what the jammer you are standing next to is jamming. */
    private void jammer(CommandSender sender, String[] args, String label) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("list")) {
            Chat.send(sender, "§cUsage: /" + label + " jammer list  §7(stand next to a Signal Jammer)");
            return;
        }
        if (manager.jammers() == null) {
            Chat.send(sender, "§cJammers are not available.");
            return;
        }
        manager.jammers().list(sender);
    }

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    /**
     * {@code /radio song <add|list|info|rename|remove|give>}. Names can contain spaces, so a name and what
     * follows it are separated by {@code |}: {@code /radio song rename Old Name | New Name}.
     */
    private void song(CommandSender sender, String[] args, String label) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        String rest = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
        boolean admin = sender.hasPermission("radionetwork.admin");
        switch (sub) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    Chat.send(sender, "§cOnly players can add songs.");
                    return;
                }
                int bar = rest.indexOf('|');
                String name = bar >= 0 ? rest.substring(0, bar).trim() : "";
                String link = bar >= 0 ? rest.substring(bar + 1).trim() : rest.trim();
                if (link.isEmpty()) {
                    Chat.send(player, "§cUsage: /" + label + " song add <name> | <link to an .mp3 file>   §7(hold a Blank Disc)");
                    return;
                }
                uploads.begin(player, name, link);
            }
            case "list" -> {
                String who = rest.isBlank() ? (sender instanceof Player p ? p.getName() : "") : rest.trim();
                if (who.isEmpty()) {
                    Chat.send(sender, "§cName a player: /" + label + " song list <player>");
                    return;
                }
                if (!who.equalsIgnoreCase(sender.getName()) && !admin) {
                    Chat.send(sender, "§cYou can only list your own songs.");
                    return;
                }
                List<SongLibrary.Meta> found = songs.by(who).stream().filter(m -> m.active() || admin).toList();
                if (found.isEmpty()) {
                    Chat.send(sender, "§7" + who + " has no songs.");
                    return;
                }
                Chat.send(sender, "§6§lSongs by " + who);
                for (SongLibrary.Meta m : found) {
                    Chat.send(sender, "§7- §f" + m.name() + " §7(" + Chat.duration(m.seconds()) + (m.isAudio() ? ", audio" : "") + ")" + (m.active() ? "" : " §c[removed]"));
                }
            }
            case "info" -> withSong(sender, rest, admin, meta -> {
                Chat.send(sender, "§6§l" + meta.name() + (meta.active() ? "" : " §c[removed]"));
                Chat.send(sender, "§7Added by §f" + meta.uploader() + " §7on " + DATE.format(java.time.Instant.ofEpochMilli(meta.added())));
                Chat.send(sender, "§7Type: §f" + (meta.isAudio() ? "audio (voice chat)" : "note blocks") + " §7Length: §f" + Chat.duration(meta.seconds()) + " §7Size: §f" + Math.max(1, meta.bytes() / 1024) + " KB §7Id: §f" + meta.id());
                if (admin) {
                    Chat.send(sender, "§7Link: §f" + meta.url());
                }
            });
            case "rename" -> {
                int bar = rest.indexOf('|');
                if (bar < 0) {
                    Chat.send(sender, "§cUsage: /" + label + " song rename <old name> | <new name>");
                    return;
                }
                withSong(sender, rest.substring(0, bar), admin, meta -> {
                    String name = SongLibrary.cleanName(rest.substring(bar + 1));
                    if (name.isEmpty() || songs.isBanned(name)) {
                        Chat.send(sender, "§cThat name isn't allowed.");
                        return;
                    }
                    SongLibrary.Meta clash = songs.byName(name);
                    if (clash != null && clash != meta) {
                        Chat.send(sender, "§cAnother song already has that name.");
                        return;
                    }
                    songs.rename(meta, name, sender.getName());
                    Chat.send(sender, "§aRenamed to §f" + name + "§a. Discs already made show the old name until they are re-crafted.");
                });
            }
            case "remove" -> withSong(sender, rest, admin, meta -> {
                songs.remove(meta, sender.getName());
                Chat.send(sender, "§aRemoved §f" + meta.name() + "§a. Discs of it stop playing.");
            });
            case "give" -> {
                if (!admin) {
                    Chat.send(sender, "§cOnly admins can give song discs.");
                    return;
                }
                int bar = rest.indexOf('|');
                String name = (bar >= 0 ? rest.substring(0, bar) : rest).trim();
                String target = bar >= 0 ? rest.substring(bar + 1).trim() : "";
                Player player = target.isEmpty() ? (sender instanceof Player p ? p : null) : Bukkit.getPlayerExact(target);
                SongLibrary.Meta meta = songs.byName(name);
                if (meta == null || player == null) {
                    Chat.send(sender, "§cUsage: /" + label + " song give <name> [| <player>]  §7(song and player must exist)");
                    return;
                }
                player.getInventory().addItem(items.createSongDisc(meta)).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                Chat.send(sender, "§aGave the disc for §f" + meta.name() + "§a to " + player.getName() + ".");
            }
            default -> Chat.send(sender, "§cUsage: /" + label + " song <add|list|info|rename|remove|give>");
        }
    }

    /** Finds a song by name that the sender may manage: its uploader, or an admin. */
    private void withSong(CommandSender sender, String name, boolean admin, java.util.function.Consumer<SongLibrary.Meta> action) {
        SongLibrary.Meta meta = songs.byName(name.trim());
        if (meta == null) {
            Chat.send(sender, "§cNo song called \"" + name.trim() + "\".");
        } else if (!admin && !meta.uploader().equalsIgnoreCase(sender.getName())) {
            Chat.send(sender, "§cThat isn't your song.");
        } else {
            action.accept(meta);
        }
    }

    /**
     * {@code /radio signs [page]}: every spot that has produced an announcement, newest first, with who placed it, the
     * channel, what it said last and a teleport link. Built so a sign that is being abused can be found and removed.
     */
    private void signs(CommandSender sender, String[] args) {
        if (!sender.hasPermission(LOCATE_PERMISSION)) {
            Chat.send(sender, "§cYou don't have permission to list announcing signs.");
            return;
        }
        AnnouncementSigns log = manager.signs();
        List<AnnouncementSigns.Entry> all = log == null ? List.of() : log.list();
        if (all.isEmpty()) {
            Chat.send(sender, "§7No sign has announced anything yet.");
            return;
        }
        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                Chat.send(sender, "§cUsage: /radio signs [page]");
                return;
            }
        }
        Chat.send(sender, "§6§lAnnouncing signs §7(" + all.size() + ") - page " + page + "/" + pages);
        long now = System.currentTimeMillis();
        for (AnnouncementSigns.Entry entry : all.subList((page - 1) * PAGE_SIZE, Math.min(all.size(), page * PAGE_SIZE))) {
            String who = entry.placer().isEmpty() ? "§8unknown" : "§f" + entry.placer();
            Component line = Chat.text("§7- §f" + entry.channel() + " §8\"" + entry.lastMessage() + "\" §7by " + who
                    + " §b" + entry.x() + " " + entry.y() + " " + entry.z() + " §7x" + entry.count() + ", last "
                    + Chat.duration(Math.max(0, now - entry.last()) / 1000) + " ago ");
            NamespacedKey world = NamespacedKey.fromString(entry.world());
            if (sender instanceof Player && world != null && Bukkit.getWorld(world) != null) {
                // Stations put the sign two blocks under the rail, so the rail is the place to stand.
                String teleport = "/execute in " + entry.world() + " run tp @s " + (entry.x() + 0.5) + " "
                        + (entry.y() + 2) + " " + (entry.z() + 0.5);
                line = line.append(Chat.text("§a§l[TP]").clickEvent(ClickEvent.runCommand(teleport))
                        .hoverEvent(HoverEvent.showText(Chat.text("§7Teleport to this sign"))));
            }
            sender.sendMessage(line);
        }
        if (page < pages) {
            Chat.send(sender, "§7Next page: §f/radio signs " + (page + 1));
        }
    }

    /**
     * {@code /radio release} for admins: lists every frequency (with a click-to-release button), and
     * {@code /radio release <name>} asks to confirm before {@code /radio release <name> confirm} frees it. Everyone
     * else, and an admin's bare {@code /radio release confirm}, free the frequency next to them as before.
     */
    private void release(CommandSender sender, String[] args) {
        List<String> words = java.util.Arrays.asList(args).subList(1, args.length);
        if (words.isEmpty() || (words.get(0).equalsIgnoreCase("list") && words.size() <= 2)) {
            listFrequencies(sender, words.size() == 2 ? words.get(1) : "1");
            return;
        }
        boolean confirm = words.size() > 1 && words.get(words.size() - 1).equalsIgnoreCase("confirm");
        String name = RadioManager.cleanChannel(String.join(" ", confirm ? words.subList(0, words.size() - 1) : words));
        Frequency frequency = manager.frequencies().get(name);
        if (frequency == null) {
            frequency = manager.frequencies().all().stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
        if (frequency == null) {
            Chat.send(sender, "§cNo frequency called §f" + name + "§c. Run §f/radio release §cto see them all.");
            return;
        }
        String command = "/radio release " + frequency.name();
        if (!confirm) {
            Component ask = Chat.text("§eThis frees §6" + frequency.name() + " §e(owned by §6" + frequency.owner()
                    + "§e) for anyone to claim and erases its password and trusted list. Run §f" + command + " confirm §eto continue. ");
            sender.sendMessage(sender instanceof Player
                    ? ask.append(Chat.text("§a§l[CONFIRM]").clickEvent(ClickEvent.runCommand(command + " confirm"))
                            .hoverEvent(HoverEvent.showText(Chat.text("§7Free this frequency now"))))
                    : ask);
            return;
        }
        manager.frequencies().release(frequency);
        manager.save();
        Chat.send(sender, "§aFrequency §f" + frequency.name() + " §areleased. Radios tuned to it stay offline until someone claims it.");
        Player owner = Bukkit.getPlayerExact(frequency.owner());
        if (owner != null && !owner.equals(sender)) {
            Alerts.send(owner, Alerts.Level.YELLOW, "Access", "An admin released " + Alerts.name(frequency.name())
                    + ". Radios tuned to it are offline until it is claimed again.");
        }
    }

    private void listFrequencies(CommandSender sender, String pageText) {
        List<Frequency> all = manager.frequencies().all().stream()
                .sorted(Comparator.comparing(Frequency::name, String.CASE_INSENSITIVE_ORDER)).toList();
        if (all.isEmpty()) {
            Chat.send(sender, "§7There are no frequencies.");
            return;
        }
        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page;
        try {
            page = Math.max(1, Math.min(pages, Integer.parseInt(pageText)));
        } catch (NumberFormatException e) {
            Chat.send(sender, "§cUsage: /radio release list [page]");
            return;
        }
        Chat.send(sender, "§6§lFrequencies §7(" + all.size() + ") - page " + page + "/" + pages);
        long now = System.currentTimeMillis();
        for (Frequency frequency : all.subList((page - 1) * PAGE_SIZE, Math.min(all.size(), page * PAGE_SIZE))) {
            long radios = manager.all().stream().filter(r -> frequency.name().equals(r.channel())).count();
            boolean ownerOnline = Bukkit.getPlayerExact(frequency.owner()) != null;
            String seen = ownerOnline ? "online" : Chat.duration(Math.max(0, now - frequency.lastSeen()) / 1000) + " ago";
            String text = "§7- §f" + frequency.name() + " §7by §f" + frequency.owner() + " §8[" + frequency.access().name()
                    + ", " + frequency.speech().name() + "] §7" + radios + " radio" + (radios == 1 ? "" : "s")
                    + ", owner seen " + seen + " ";
            Component line = Chat.text(text);
            sender.sendMessage(sender instanceof Player
                    ? line.append(Chat.text("§c§l[RELEASE]")
                            .clickEvent(ClickEvent.runCommand("/radio release " + frequency.name()))
                            .hoverEvent(HoverEvent.showText(Chat.text("§7Free this frequency (asks to confirm first)"))))
                    : line);
        }
        if (page < pages) {
            Chat.send(sender, "§7Next page: §f/radio release list " + (page + 1));
        }
    }

    private void locate(CommandSender sender, String[] args) {
        if (!sender.hasPermission(LOCATE_PERMISSION)) {
            Chat.send(sender, "§cYou don't have permission to locate radios.");
            return;
        }
        List<Radio> radios = manager.all().stream()
                .sorted(Comparator.comparing((Radio r) -> r.channel()).thenComparing(Radio::world)
                        .thenComparingInt(Radio::x).thenComparingInt(Radio::z))
                .toList();
        if (radios.isEmpty()) {
            Chat.send(sender, "§7There are no radios.");
            return;
        }

        int pages = (radios.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                Chat.send(sender, "§cUsage: /radio locate [page]");
                return;
            }
        }

        Chat.send(sender, "§6§lRadios §7(" + radios.size() + ") - page " + page + "/" + pages);
        for (Radio radio : radios.subList((page - 1) * PAGE_SIZE, Math.min(radios.size(), page * PAGE_SIZE))) {
            sender.sendMessage(line(radio, sender instanceof Player));
        }
        if (page < pages) {
            Chat.send(sender, "§7Next page: §f/radio locate " + (page + 1));
        }
    }

    private Component line(Radio radio, boolean clickable) {
        String channel = radio.isTuned() ? radio.channel() : "§8untuned";
        String state = radio.isPowered() ? "§a" + radio.activeFuel().name() + " " + Chat.duration(radio.fuelLevel()) : "§coffline";
        String coords = radio.world() + " " + radio.x() + " " + radio.y() + " " + radio.z();
        Component line = Chat.text("§7- §f" + channel + " §7by §f" + radio.creatorName() + " §7[" + state + "§7] §b" + coords + " ");
        if (!clickable) {
            return line;
        }
        World world = radio.bukkitWorld();
        if (world == null) {
            return line.append(Chat.text("§8(world not loaded)"));
        }
        String teleport = "/execute in " + world.getKey() + " run tp @s "
                + (radio.x() + 0.5) + " " + (radio.y() + 1) + " " + (radio.z() + 0.5);
        return line.append(Chat.text("§a§l[TP]")
                .clickEvent(ClickEvent.runCommand(teleport))
                .hoverEvent(HoverEvent.showText(Chat.text("§7Teleport to this radio"))));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            java.util.List<String> options = new java.util.ArrayList<>(FrequencyCommand.SUBCOMMANDS);
            options.add("members");
            options.add("jammer");
            if (sender.hasPermission(LOCATE_PERMISSION)) {
                options.add("locate");
                options.add("signs");
            }
            if (sender.hasPermission(ANNOUNCE_PERMISSION)) {
                options.add("announce");
            }
            if (sender.hasPermission(GIVE_PERMISSION)) {
                options.add("give");
            }
            if (sender.hasPermission("radionetwork.songs.add")) {
                options.add("song");
            }
            return options.stream().filter(o -> o.startsWith(prefix)).sorted().toList();
        }
        if (args[0].equalsIgnoreCase("jammer") && args.length == 2) {
            return "list".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("list") : List.of();
        }
        if (args[0].equalsIgnoreCase("song") && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("add", "list", "info", "rename", "remove", "give").stream()
                    .filter(o -> o.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("give") && sender.hasPermission(GIVE_PERMISSION)) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> options = switch (args.length) {
                case 2 -> ITEM_NAMES;
                case 3 -> List.of("1", "8", "16", "64");
                case 4 -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                default -> List.of();
            };
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("release") && args.length == 2 && sender.hasPermission(ADMIN_PERMISSION)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> options = new java.util.ArrayList<>(List.of("list", "confirm"));
            manager.frequencies().all().forEach(f -> options.add(f.name()));
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return frequencyCommand.onTabComplete(sender, command, alias, args);
    }
}
