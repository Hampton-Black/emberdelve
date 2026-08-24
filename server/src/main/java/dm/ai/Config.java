package dm.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a gitignored {@code .env} from the repo root, with real environment variables winning.
 * No secret is ever logged or echoed — only whether one is present.
 */
public final class Config {

    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Config load() {
        var values = new HashMap<String, String>();

        // Walk up from the working directory; ./gradlew run starts in server/.
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                values.putAll(parse(candidate));
                break;
            }
        }

        // A real environment variable always wins over the file.
        for (String key : values.keySet().toArray(String[]::new)) {
            String fromEnv = System.getenv(key);
            if (fromEnv != null && !fromEnv.isBlank()) {
                values.put(key, fromEnv);
            }
        }
        for (var entry : System.getenv().entrySet()) {
            values.putIfAbsent(entry.getKey(), entry.getValue());
        }

        return new Config(values);
    }

    private static Map<String, String> parse(Path path) {
        var parsed = new HashMap<String, String>();
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).strip();
                String value = trimmed.substring(eq + 1).strip();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    parsed.put(key, value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
        return parsed;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key)).filter(v -> !v.isBlank());
    }

    public String get(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public String orElse(String key, String fallback) {
        return has(key) ? require(key) : fallback;
    }

    public String require(String key) {
        return get(key).orElseThrow(() -> new IllegalStateException(
                key + " is not set. Copy .env.example to .env and fill it in."));
    }

    public boolean has(String key) {
        return get(key).isPresent();
    }
}
