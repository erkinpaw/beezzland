package land.beezz.gate;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The rules quiz shown on the loading screen: one Да/Нет window per question.
 * A wrong answer shows the full rules with «Прочитал, пройти тест заново», then the quiz restarts.
 */
final class Quiz {

    record Question(String text, boolean yes) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(30)).build();

    final int version;
    private final String rules;
    private final List<Question> questions = new ArrayList<>();

    Quiz(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        version = y.getInt("version", 1);
        rules = y.getString("rules", "").trim();
        for (Map<?, ?> q : y.getMapList("questions")) {
            questions.add(new Question(String.valueOf(q.get("text")), "да".equalsIgnoreCase(String.valueOf(q.get("answer")))));
        }
    }

    /** Runs the whole quiz; completes with true once every question is answered right in one go. */
    CompletableFuture<Boolean> run(Audience who) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        showRules(who, done, false); // rules first, then the questions about them
        return done;
    }

    private void ask(Audience who, int i, CompletableFuture<Boolean> done) {
        if (i >= questions.size()) {
            who.closeDialog();
            done.complete(true);
            return;
        }
        Question q = questions.get(i);
        Component body = MM.deserialize("<gray>Вопрос " + (i + 1) + " из " + questions.size() + "</gray>\n\n<white>" + q.text());
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Правила BeezzLand</bold>"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body, 300)))
                        .build())
                .type(DialogType.confirmation(
                        button("<#34D399>Да", q.yes(), who, i, done),
                        button("<#F87171>Нет", !q.yes(), who, i, done))));
        who.showDialog(d);
    }

    private ActionButton button(String label, boolean right, Audience who, int i, CompletableFuture<Boolean> done) {
        return ActionButton.builder(MM.deserialize(label)).width(120)
                .action(DialogAction.customClick((view, aud) -> {
                    if (right) ask(who, i + 1, done);
                    else showRules(who, done, true);
                }, ONCE))
                .build();
    }

    /** The rules screen: first thing a new player sees, and again after a wrong answer. */
    private void showRules(Audience who, CompletableFuture<Boolean> done, boolean afterMistake) {
        String head = afterMistake
                ? "<#F87171>Ответ неверный.</#F87171> <gray>Прочитай правила ещё раз и пройди тест заново.</gray>"
                : "<gray>Добро пожаловать! Прочитай правила — после них будет короткий тест из "
                  + questions.size() + " вопросов.</gray>";
        String button = afterMistake ? "<#FACC15>Прочитал, пройти тест заново" : "<#FACC15>Прочитал, начать тест";
        Component body = MM.deserialize(head + "\n\n<white>" + rules.replace("\n", "\n\n"));
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Правила BeezzLand</bold>"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body, 300)))
                        .build())
                .type(DialogType.notice(ActionButton.builder(MM.deserialize(button)).width(240)
                        .action(DialogAction.customClick((view, aud) -> ask(who, 0, done), ONCE)).build())));
        who.showDialog(d);
    }
}
