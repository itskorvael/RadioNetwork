package dev.radionetwork;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;


/** {@code /broadcast <message>} (alias {@code /bc}) - text transmission through a nearby terminal. */
public final class BroadcastCommand implements CommandExecutor {

    private final RadioManager manager;

    public BroadcastCommand(RadioManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Chat.send(sender, "§cOnly players can use the radio.");
            return true;
        }
        if (args.length == 0) {
            Chat.send(player, "§cUsage: /" + label + " <message>");
            return true;
        }

        RadioManager.Transmitter tx = manager.findTransmitter(player);
        switch (tx.denial()) {
            case NO_RADIO -> {
                Chat.send(player, "§cNo radio terminal within 5 blocks.");
                return true;
            }
            case NOT_TUNED -> {
                Chat.send(player, "§cTransmission failed. This terminal is not tuned to an active frequency.");
                return true;
            }
            case NO_ACCESS -> {
                Chat.send(player, "§cTransmission failed. This terminal's operator no longer has access to its frequency.");
                return true;
            }
            case ENCRYPTED -> {
                Chat.send(player, "§cTransmission blocked. This frequency is encrypted and you do not have the key.");
                return true;
            }
            case CLOSED -> {
                Chat.send(player, "§cTransmission blocked. Only the frequency owner and trusted players can speak here.");
                return true;
            }
            case NO_POWER -> {
                Alerts.send(player, Alerts.Level.RED, "Transmission", "Transmission failed. This terminal has no power.");
                return true;
            }
            case JAMMED -> {
                Alerts.send(player, Alerts.Level.RED, "Transmission", "Transmission failed. Heavy interference: this terminal is being jammed.");
                return true;
            }
            case NONE -> { }
        }

        Radio radio = tx.radio();
        String message = Chat.clean(String.join(" ", args));
        if (message.isEmpty()) {
            Chat.send(player, "§cUsage: /" + label + " <message>");
            return true;
        }

        long wait = manager.broadcastCooldownLeft(player.getUniqueId());
        if (wait > 0) {
            Chat.send(player, "§cChannel busy. Try again in " + String.format("%.1f", wait / 1000.0) + "s.");
            return true;
        }
        manager.markBroadcast(player.getUniqueId());

        // Each listener gets the message as their own signal carries it: garbled more the weaker it is.
        java.util.Map<java.util.UUID, Integer> signal = manager.signalOf(radio);
        java.util.Set<Player> listeners = manager.listenersOf(radio);
        for (Player listener : listeners) {
            String heard = manager.garble(message, signal.getOrDefault(listener.getUniqueId(), 4));
            Chat.send(listener, Chat.tag("§6", "Radio", radio.channel()) + "§7" + player.getName() + ": §f" + heard);
        }
        // Scanners hear it too: clear on an open frequency, noise on an encrypted one they have not decoded.
        if (manager.scanners() != null) {
            manager.scanners().relayText(radio, player.getName(), message, listeners);
        }
        manager.pulseRelays(radio);
        return true;
    }
}
