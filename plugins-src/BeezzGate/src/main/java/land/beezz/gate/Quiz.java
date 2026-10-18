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
import java.util.Collections;
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
    private final int ask;
    private final String rules;
    private final String fullRules;
    private final List<Question> questions = new ArrayList<>();

    Quiz(File file, File fullRulesFile) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        version = y.getInt("version", 1);
        ask = y.getInt("ask", 0);
        rules = y.getString("rules", "").trim();
        fullRules = markdown(fullRulesFile);
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

    private void ask(Audience who, List<Question> qs, int i, CompletableFuture<Boolean> done) {
        if (i >= qs.size()) {
            who.closeDialog();
            done.complete(true);
            return;
        }
        Question q = qs.get(i);
        Component body = MM.deserialize("<gray>Вопрос " + (i + 1) + " из " + qs.size() + "</gray>\n\n<white>" + q.text());
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Правила BeezzLand</bold>"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body, 300)))
                        .build())
                .type(DialogType.confirmation(
                        button("<#34D399>Да", q.yes(), who, qs, i, done),
                        button("<#F87171>Нет", !q.yes(), who, qs, i, done))));
        who.showDialog(d);
    }

    private ActionButton button(String label, boolean right, Audience who, List<Question> qs, int i, CompletableFuture<Boolean> done) {
        return ActionButton.builder(MM.deserialize(label)).width(120)
                .action(DialogAction.customClick((view, aud) -> {
                    if (right) ask(who, qs, i + 1, done);
                    else showRules(who, done, true);
                }, ONCE))
                .build();
    }

    /** The rules screen: first thing a new player sees, and again after a wrong answer. */
    private void showRules(Audience who, CompletableFuture<Boolean> done, boolean afterMistake) {
        String head = afterMistake
                ? "<#F87171>Ответ неверный.</#F87171> <gray>Прочитай правила ещё раз и пройди тест заново.</gray>"
                : "<gray>Добро пожаловать! Прочитай правила — после них будет короткий тест из "
                  + count() + " вопросов.</gray>";
        String button = afterMistake ? "<#FACC15>Пройти тест заново" : "<#FACC15>Прочитал, начать тест";
        Component body = MM.deserialize(head + "\n\n<white>" + rules.replace("\n", "\n\n"));
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Правила BeezzLand</bold>"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(body, 300)))
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.builder(MM.deserialize("<white>Подробные правила")).width(180)
                                .action(DialogAction.customClick((view, aud) -> showFullRules(who, done, afterMistake), ONCE)).build(),
                        ActionButton.builder(MM.deserialize(button)).width(180)
                                .action(DialogAction.customClick((view, aud) -> ask(who, pick(), 0, done), ONCE)).build()))
                        .columns(2).build()));
        who.showDialog(d);
    }

    /** All the rules (docs/RULES.md), with a way back to the short version and the test. */
    private void showFullRules(Audience who, CompletableFuture<Boolean> done, boolean afterMistake) {
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Правила BeezzLand</bold> <gray>— подробно"))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(fullRules), 360)))
                        .build())
                .type(DialogType.notice(ActionButton.builder(MM.deserialize("<#FACC15>Назад к тесту")).width(200)
                        .action(DialogAction.customClick((view, aud) -> showRules(who, done, afterMistake), ONCE)).build())));
        who.showDialog(d);
    }

    /** RULES.md → dialog text: «## раздел» = yellow heading, «- 1.1. пункт» = numbered line, **жирный**, `команда`. */
    static String markdown(File file) {
        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "<gray>Подробные правила — на сайте и в Discord.";
        }
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("# ")) continue;
            line = inline(line.replace("<", "‹"));
            if (line.startsWith("## ")) {
                out.append(out.isEmpty() ? "" : "\n").append("<#FACC15><bold>").append(line.substring(3)).append("</bold></#FACC15>\n");
            } else if (line.startsWith("- ")) {
                String item = line.substring(2);
                int sp = item.indexOf(' ');
                if (sp > 0 && item.substring(0, sp).matches("[0-9.]+")) {
                    out.append("<#FACC15>").append(item, 0, sp).append("</#FACC15> <white>").append(item.substring(sp + 1)).append("</white>\n");
                } else {
                    out.append("<white>• ").append(item).append("</white>\n");
                }
            } else {
                out.append("<gray>").append(line).append("</gray>\n");
            }
        }
        return out.toString().strip();
    }

    private static String inline(String s) {
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<bold>$1</bold>");
        return s.replaceAll("`(.+?)`", "<#93C5FD>$1</#93C5FD>");
    }

    private int count() {
        return ask > 0 ? Math.min(ask, questions.size()) : questions.size();
    }

    /** A fresh random set of questions in random order for every attempt, so answers can't be memorised by position. */
    private List<Question> pick() {
        List<Question> all = new ArrayList<>(questions);
        Collections.shuffle(all);
        return all.subList(0, count());
    }
}
