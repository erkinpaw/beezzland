package land.beezz.worlds;

import java.util.Locale;

/** Worlds that open one after another, week by week, by an admin command. */
public enum Stage {
    NETHER("Незер"),
    FARM("Мир ферм"),
    END("Энд");

    private final String title;

    Stage(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Stage parse(String s) {
        for (Stage stage : values()) {
            if (stage.key().equalsIgnoreCase(s)) {
                return stage;
            }
        }
        return null;
    }
}
