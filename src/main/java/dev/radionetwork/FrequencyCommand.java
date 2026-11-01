package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * Frequency management behind {@code /radio}: acts on the frequency of the radio you are standing
 * next to. Not registered as a command of its own; {@link RadioCommand} delegates to it. Passwords are never typed here (they would end up in chat and command logs); use the terminal
 * GUI's dialog for those.
 */
public final class FrequencyCommand implements TabCompleter {

    public static final List<String> SUBCOMMANDS =
            List.of("info", "mode", "access", "trust", "untrust", "transfer", "release", "claim", "list", "help");

    private final RadioManager manager;

    public FrequencyCommand(RadioManager manager) {
        this.manager = manager;
    }

    public static boolean isOwnerOrAdmin(Player player, Frequency frequency) {
        return frequency.isOwner(player.getName()) || player.hasPermission("radionetwork.admin");
    }

    /** Handles the frequency subcommands; {@code args[0]} is the subcommand. */
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "§cOnly players can manage frequencies.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(player);
            case "claim" -> claim(player);
            case "info", "mode", "access", "trust", "untrust", "transfer", "release" -> {
                Frequency frequency = nearbyFrequency(player);
                if (frequency == null) {
                    return true;
                }
                if (sub.equals("info")) {
                    info(player, frequency);
                } else if (!isOwnerOrAdmin(player, frequency)) {
                    Chat.send(player, "§cOnly the frequency owner (" + frequency.owner() + ") can do that.");
                } else {
                    owned(player, frequency, sub, args);
                }
            }
            default -> help(player, label);
        }
        return true;
    }

    /** The frequency of the nearest tuned radio within terminal range, with an error if there is none. */
    private Frequency nearbyFrequency(Player player) {
        List<Radio> near = manager.radiosNear(player.getLocation());
        if (near.isEmpty()) {
            Chat.send(player, "§cStand within 5 blocks of a Radio Terminal to manage its frequency.");
            return null;
        }
        Radio radio = near.getFirst();
        Frequency frequency = manager.frequencyOf(radio);
        if (frequency == null) {
            Chat.send(player, radio.isTuned()
                    ? "§cThe frequency §f" + radio.channel() + " §cis unclaimed. Use §f/radio claim §cif this is your radio."
                    : "§cThis radio is not tuned to a frequency yet.");
        }
        return frequency;
    }

    private void owned(Player player, Frequency frequency, String sub, String[] args) {
        String arg = args.length > 1 ? args[1] : "";
        switch (sub) {
            case "mode" -> {
                switch (arg.toLowerCase(Locale.ROOT)) {
                    case "open" -> frequency.speech(RadioMode.OPEN);
                    case "closed" -> frequency.speech(RadioMode.CLOSED);
                    default -> {
                        Chat.send(player, "§cUsage: /radio mode <open|closed>");
                        return;
                    }
                }
                done(player, "§eFrequency §6" + frequency.name() + " §espeaking is now §6" + frequency.speech().name() + "§e.");
            }
            case "access" -> {
                switch (arg.toLowerCase(Locale.ROOT)) {
                    case "public" -> frequency.access(Frequency.Access.PUBLIC);
                    case "encrypted", "private" -> {
                        if (!frequency.hasPassword()) {
                            Chat.send(player, "§cSet a password first: right-click the Frequency Settings torch in the terminal.");
                            return;
                        }
                        frequency.access(Frequency.Access.ENCRYPTED);
                    }
                    default -> {
                        Chat.send(player, "§cUsage: /radio access <public|encrypted>");
                        return;
                    }
                }
                done(player, "§eFrequency §6" + frequency.name() + " §eis now §6" + frequency.access().name() + "§e.");
            }
            case "trust" -> {
                if (!validName(player, arg)) {
                    return;
                }
                if (frequency.isOwner(arg)) {
                    Chat.send(player, "§cThe owner is always trusted.");
                    return;
                }
                frequency.trust(arg);
                done(player, "§a" + arg + " is now trusted on §f" + frequency.name() + "§a: they can join and speak.");
            }
            case "untrust" -> {
                if (!validName(player, arg)) {
                    return;
                }
                if (!frequency.untrust(arg)) {
                    Chat.send(player, "§c" + arg + " is not on the trusted list.");
                    return;
                }
                done(player, "§e" + arg + " is no longer trusted on §6" + frequency.name() + "§e.");
            }
            case "transfer" -> {
                Player target = Bukkit.getPlayerExact(arg);
                if (target == null || target.equals(player)) {
                    Chat.send(player, "§cName another player who is online: /radio transfer <player>");
                    return;
                }
                String previous = frequency.owner();
                frequency.untrust(target.getName());
                frequency.owner(target.getName());
                frequency.trust(previous); // the old owner keeps access and speaking rights
                frequency.lastSeen(System.currentTimeMillis());
                done(player, "§aFrequency §f" + frequency.name() + " §atransferred to §f" + target.getName()
                        + "§a. You stay on its trusted list.");
                Chat.send(target, "§a" + player.getName() + " transferred the frequency §f" + frequency.name() + " §ato you.");
            }
            case "release" -> {
                if (!arg.equalsIgnoreCase("confirm")) {
                    Chat.send(player, "§eThis frees §6" + frequency.name() + "§e for anyone to claim and erases its password and trusted list. "
                            + "Run §f/radio release confirm §eto continue.");
                    return;
                }
                manager.frequencies().release(frequency);
                done(player, "§aFrequency §f" + frequency.name() + " §areleased. Radios tuned to it stay offline until someone claims it.");
            }
            default -> { }
        }
    }

    /** Used by {@code /radio members}. Owner and trusted players are visible to anyone with access; the joined-with-password list is owner-only. */
    public void members(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "§cOnly players can list members.");
            return;
        }
        Frequency frequency = nearbyFrequency(player);
        if (frequency == null) {
            return;
        }
        boolean canManage = isOwnerOrAdmin(player, frequency);
        if (!canManage && !frequency.hasAccess(player.getName())) {
            Chat.send(player, "§cThis frequency is ENCRYPTED. Only people with access can see who is on it.");
            return;
        }
        Chat.send(player, "§6§lMembers of " + frequency.name() + " §7(" + frequency.access().name() + ", "
                + frequency.speech().name() + ")");
        Chat.send(player, "§7Owner: " + displayName(frequency.owner()));
        Chat.send(player, "§7Trusted (" + frequency.trusted().size() + "): " + names(frequency.trusted()));
        var portables = manager.portables();
        var tuned = portables == null ? java.util.List.<PortableReceivers.TunedIn>of() : portables.tunedIn(frequency.name());
        if (canManage) {
            Chat.send(player, "§7Joined with password (" + frequency.members().size() + "): " + names(frequency.members()));
            Chat.send(player, "§7Tuned in on a portable (" + tuned.size() + "): " + (tuned.isEmpty() ? "§8none"
                    : tuned.stream().map(t -> (t.hearing() ? "§a● §f" : "§7● §f") + t.player().getName()
                            + (t.auto() ? " §8(auto)" : "")).collect(java.util.stream.Collectors.joining("§7, "))));
            Chat.send(player, "§a● §8online / hearing   §7● §8offline / no signal");
        } else {
            Chat.send(player, "§7Tuned in on a portable: §f" + tuned.size());
            Chat.send(player, "§8The password and portable member names are only shown to the owner.");
        }
    }

    private static String names(java.util.Collection<String> stored) {
        return stored.isEmpty() ? "§8none" : stored.stream().map(FrequencyCommand::displayName)
                .collect(java.util.stream.Collectors.joining("§7, "));
    }

    /** Colored dot by online status, using the player's real capitalization when it is known. */
    private static String displayName(String stored) {
        Player online = Bukkit.getPlayerExact(stored);
        if (online != null) {
            return "§a● §f" + online.getName();
        }
        var offline = Bukkit.getOfflinePlayerIfCached(stored);
        String name = offline != null && offline.getName() != null ? offline.getName() : stored;
        return "§7● §f" + name;
    }

    private void claim(Player player) {
        List<Radio> near = manager.radiosNear(player.getLocation());
        Radio radio = near.isEmpty() ? null : near.getFirst();
        if (radio == null || !radio.isTuned()) {
            Chat.send(player, "§cStand next to one of your radios that is tuned to the frequency you want to claim.");
        } else if (manager.frequencies().get(radio.channel()) != null) {
            Chat.send(player, "§cThat frequency already has an owner.");
        } else if (!RadioManager.isOperator(player, radio)) {
            Chat.send(player, "§cOnly this radio's operator can claim its frequency.");
        } else {
            manager.frequencies().claim(radio.channel(), player.getName());
            done(player, "§aYou claimed the frequency §f" + radio.channel() + "§a.");
        }
    }

    private void info(Player player, Frequency frequency) {
        long radios = manager.all().stream().filter(r -> r.channel().equals(frequency.name())).count();
        Chat.send(player, "§6§lFrequency " + frequency.name());
        Chat.send(player, "§7Owner: §f" + frequency.owner());
        Chat.send(player, "§7Access: §f" + frequency.access().name()
                + (frequency.access() == Frequency.Access.ENCRYPTED ? " §7(password = key)" : ""));
        Chat.send(player, "§7Speaking: §f" + frequency.speech().name()
                + (frequency.speech() == RadioMode.CLOSED ? " §7(owner + trusted)" : " §7(everyone with access)"));
        Chat.send(player, "§7Radios tuned: §f" + radios);
        Chat.send(player, "§7Your access: §f" + frequency.accessReason(player.getName()));
        if (isOwnerOrAdmin(player, frequency)) {
            Chat.send(player, "§7Trusted: §f" + (frequency.trusted().isEmpty() ? "none" : String.join(", ", frequency.trusted())));
            Chat.send(player, "§7Joined with password: §f" + frequency.members().size());
        }
    }

    private void list(Player player) {
        List<Frequency> owned = manager.frequencies().ownedBy(player.getName());
        if (owned.isEmpty()) {
            Chat.send(player, "§7You don't own any frequencies. Tune a radio to a new name to claim one.");
            return;
        }
        Chat.send(player, "§6§lYour frequencies");
        for (Frequency frequency : owned) {
            long radios = manager.all().stream().filter(r -> r.channel().equals(frequency.name())).count();
            Chat.send(player, "§7- §f" + frequency.name() + " §7" + frequency.access().name() + "/"
                    + frequency.speech().name() + ", " + radios + " radio" + (radios == 1 ? "" : "s"));
        }
    }

    private void help(Player player, String label) {
        Chat.send(player, "§6§lFrequency commands §7(stand next to a radio)");
        for (String line : List.of(
                "info §8- show this frequency",
                "list §8- frequencies you own",
                "claim §8- claim your radio's unclaimed frequency",
                "mode <open|closed> §8- who may speak",
                "access <public|encrypted> §8- who may join",
                "trust|untrust <player> §8- invite list",
                "transfer <player> §8- give the frequency away",
                "release [confirm] §8- free the frequency")) {
            Chat.send(player, "§e/" + label + " §f" + line);
        }
        Chat.send(player, "§7Set or change the password from the terminal's Frequency Settings torch.");
    }

    private boolean validName(Player player, String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            Chat.send(player, "§cEnter a valid player name.");
            return false;
        }
        return true;
    }

    private void done(Player player, String message) {
        manager.save();
        Chat.send(player, message);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "mode" -> List.of("open", "closed").stream().filter(s -> s.startsWith(prefix)).toList();
                case "access" -> List.of("public", "encrypted").stream().filter(s -> s.startsWith(prefix)).toList();
                case "release" -> List.of("confirm").stream().filter(s -> s.startsWith(prefix)).toList();
                case "trust", "untrust", "transfer" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
                default -> List.of();
            };
        }
        return List.of();
    }
}
