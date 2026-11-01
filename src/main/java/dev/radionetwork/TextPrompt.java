package dev.radionetwork;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Single-line text prompt. Paper's {@code Player#openBook} only shows read-only written books (no
 * {@code PlayerEditBookEvent}), so text entry uses Paper's native dialog screen instead.
 */
public final class TextPrompt {

    private static final String KEY = "value";

    private TextPrompt() {
    }

    public static void open(RadioNetworkPlugin plugin, Player player, String title, String label,
                            String initial, int maxLength, Consumer<String> onSubmit) {
        ActionButton confirm = ActionButton.create(
                Chat.text("§aConfirm"), null, 120,
                DialogAction.customClick((view, audience) -> {
                    String text = view.getText(KEY);
                    Bukkit.getScheduler().runTask(plugin, () -> onSubmit.accept(text == null ? "" : text));
                }, ClickCallback.Options.builder().build()));
        ActionButton cancel = ActionButton.create(Chat.text("§cCancel"), null, 120, null);

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Chat.text(title))
                        .inputs(List.of(DialogInput.text(KEY, Component.text(label))
                                .initial(initial)
                                .maxLength(maxLength)
                                .width(300)
                                .build()))
                        .build())
                .type(DialogType.confirmation(confirm, cancel)));
        player.showDialog(dialog);
    }

    /** A dialog with two single-line text boxes (such as a song's name and its link). */
    public static void openTwo(RadioNetworkPlugin plugin, Player player, String title,
                               String firstLabel, String firstInitial, int firstMax,
                               String secondLabel, String secondInitial, int secondMax,
                               java.util.function.BiConsumer<String, String> onSubmit) {
        ActionButton confirm = ActionButton.create(
                Chat.text("§aConfirm"), null, 120,
                DialogAction.customClick((view, audience) -> {
                    String first = view.getText("first");
                    String second = view.getText("second");
                    Bukkit.getScheduler().runTask(plugin,
                            () -> onSubmit.accept(first == null ? "" : first, second == null ? "" : second));
                }, ClickCallback.Options.builder().build()));
        ActionButton cancel = ActionButton.create(Chat.text("§cCancel"), null, 120, null);

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Chat.text(title))
                        .inputs(List.of(
                                DialogInput.text("first", Component.text(firstLabel))
                                        .initial(firstInitial).maxLength(firstMax).width(300).build(),
                                DialogInput.text("second", Component.text(secondLabel))
                                        .initial(secondInitial).maxLength(secondMax).width(300).build()))
                        .build())
                .type(DialogType.confirmation(confirm, cancel)));
        player.showDialog(dialog);
    }
}
