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
                    Chat.send(player, "§cOnly the owner (" + frequency.owner() + ") can do that.");
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
            Chat.send(player, "§cNo terminal within 5 blocks.");
            return null;
        }
        Radio radio = near.getFirst();
        Frequency frequency = manager.frequencyOf(radio);
        if (frequency == null) {
            Chat.send(player, radio.isTuned()
                    ? "§c" + radio.channel() + " has no owner. §f/radio claim §cclaims it, if the terminal is yours."
                    : "§cThis terminal is not tuned to a frequency.");
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
                done(player, "§7Speaking on §f" + frequency.name() + " §7is now §f" + frequency.speech().name().toLowerCase(java.util.Locale.ROOT) + "§7.");
            }
            case "access" -> {
                switch (arg.toLowerCase(Locale.ROOT)) {
                    case "public" -> frequency.access(Frequency.Access.PUBLIC);
                    case "encrypted", "private" -> {
                        if (!frequency.hasPassword()) {
                            Chat.send(player, "§cSet a password first, from the terminal's Frequency menu.");
                            return;
                        }
                        frequency.access(Frequency.Access.ENCRYPTED);
                    }
                    default -> {
                        Chat.send(player, "§cUsage: /radio access <public|encrypted>");
                        return;
                    }
                }
                done(player, "§f" + frequency.name() + " §7is now §f" + frequency.access().name().toLowerCase(java.util.Locale.ROOT) + "§7.");
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
                done(player, "§f" + arg + " §7can now join and speak on §f" + frequency.name() + "§7.");
            }
            case "untrust" -> {
                if (!validName(player, arg)) {
                    return;
                }
                if (!frequency.untrust(arg)) {
                    Chat.send(player, "§c" + arg + " is not trusted on this frequency.");
                    return;
                }
                done(player, "§f" + arg + " §7is no longer trusted on §f" + frequency.name() + "§7.");
            }
            case "transfer" -> {
                Player target = Bukkit.getPlayerExact(arg);
                if (target == null || target.equals(player)) {
                    Chat.send(player, "§cName another player who is online.");
                    return;
                }
                String previous = frequency.owner();
                frequency.untrust(target.getName());
                frequency.owner(target.getName());
                frequency.trust(previous); // the old owner keeps access and speaking rights
                frequency.lastSeen(System.currentTimeMillis());
                done(player, "§f" + frequency.name() + " §7now belongs to §f" + target.getName() + "§7. You stay trusted on it.");
                Chat.send(target, "§f" + player.getName() + " §7gave you the frequency §f" + frequency.name() + "§7.");
            }
            case "release" -> {
                if (!arg.equalsIgnoreCase("confirm")) {
                    Chat.send(player, "§7This frees §f" + frequency.name() + " §7for anyone to claim and erases its password and trusted list. "
                            + "Run §f/radio release confirm §7to continue.");
                    return;
                }
                manager.frequencies().release(frequency);
                done(player, "§f" + frequency.name() + " §7released. Radios on it stay offline until someone claims it.");
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
            Chat.send(player, "§cThis frequency is encrypted. Only people with access can see who is on it.");
            return;
        }
        Chat.send(player, "§fMembers of " + frequency.name() + " §8(" + frequency.access().name().toLowerCase(java.util.Locale.ROOT) + ", "
                + frequency.speech().name().toLowerCase(java.util.Locale.ROOT) + ")");
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
            Chat.send(player, "§8Joined and portable names are shown to the owner only.");
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
            Chat.send(player, "§cStand next to a terminal tuned to the frequency you want.");
        } else if (manager.frequencies().get(radio.channel()) != null) {
            Chat.send(player, "§cThat frequency already has an owner.");
        } else if (!RadioManager.isOperator(player, radio)) {
            Chat.send(player, "§cOnly the terminal's operator can claim it.");
        } else {
            manager.frequencies().claim(radio.channel(), player.getName());
            done(player, "§7You now own §f" + radio.channel() + "§7.");
        }
    }

    private void info(Player player, Frequency frequency) {
        long radios = manager.all().stream().filter(r -> r.channel().equals(frequency.name())).count();
        Chat.send(player, "§fFrequency " + frequency.name());
        Chat.send(player, "§7Owner: §f" + frequency.owner());
        Chat.send(player, "§7Access: §f" + frequency.access().name().toLowerCase(java.util.Locale.ROOT)
                + (frequency.access() == Frequency.Access.ENCRYPTED ? " §7(password = key)" : ""));
        Chat.send(player, "§7Speaking: §f" + frequency.speech().name().toLowerCase(java.util.Locale.ROOT)
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
            Chat.send(player, "§7You don't own any frequencies.");
            return;
        }
        Chat.send(player, "§fYour frequencies");
        for (Frequency frequency : owned) {
            long radios = manager.all().stream().filter(r -> r.channel().equals(frequency.name())).count();
            Chat.send(player, "§7- §f" + frequency.name() + " §7" + frequency.access().name().toLowerCase(java.util.Locale.ROOT) + "/"
                    + frequency.speech().name().toLowerCase(java.util.Locale.ROOT) + ", " + radios + " radio" + (radios == 1 ? "" : "s"));
        }
    }

    private void help(Player player, String label) {
        Chat.send(player, "§fFrequency commands §8(next to a terminal)");
        for (String line : List.of(
                "info §8- §7this frequency",
                "list §8- §7frequencies you own",
                "claim §8- §7claim an unowned frequency",
                "mode <open|closed> §8- §7who may speak",
                "access <public|encrypted> §8- §7who may join",
                "trust|untrust <player> §8- §7let a player join and speak, or take that back",
                "transfer <player> §8- §7give the frequency away",
                "release [confirm] §8- §7free the frequency")) {
            Chat.send(player, "§f/" + label + " " + line);
        }
        Chat.send(player, "§8Passwords are set from the terminal's Frequency menu.");
    }

    private boolean validName(Player player, String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            Chat.send(player, "§cNot a valid player name.");
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
