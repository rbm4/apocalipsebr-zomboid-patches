package zombie.apocalipse.humans.dialogue;

import java.util.ArrayList;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/** Per-actor greeting selection from cached labels; never reads SQL or owns an action. */
public final class HumanGreetingProvider implements Supplier<String> {
    private final String key;
    private final IntUnaryOperator choose;
    private String previous;

    public HumanGreetingProvider(String key) {
        this(key, bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound));
    }
    public HumanGreetingProvider(String key, IntUnaryOperator choose) {
        this.key = java.util.Objects.requireNonNull(key);
        this.choose = java.util.Objects.requireNonNull(choose);
    }
    public String get() {
        if (key.isEmpty()) return null;
        if (!key.startsWith("pool:")) return HumanLanguage.text(key);
        String prefix = key.substring(5);
        var snapshot = HumanDialogueCatalog.current();
        if (snapshot == null) return null;
        var lines = new ArrayList<String>();
        // Explicit numbered labels keep lookup bounded and support sparse authored pools.
        for (int i = 1; i <= 32; i++) {
            var translations = snapshot.labels().get(prefix + "." + i);
            if (translations == null) continue;
            String line = (String)HumanLanguage.resolve(translations, "EN").get("text");
            if (line == null || line.isBlank() || line.length() > 256)
                throw new IllegalArgumentException("Invalid greeting pool line");
            if (!lines.contains(line)) lines.add(line);
        }
        if (lines.size() > 1) lines.remove(previous);
        if (lines.isEmpty()) return null;
        previous = lines.get(choose.applyAsInt(lines.size()));
        return previous;
    }
}
