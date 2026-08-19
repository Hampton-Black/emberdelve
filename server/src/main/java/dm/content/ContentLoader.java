package dm.content;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads git-versioned content from the classpath at boot. Content is never in a database.
 *
 * <p>A missing or malformed content file is fatal — M0 has no error recovery
 * (shortcut #13), and a game with no room is not a game.
 */
public final class ContentLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RoomDefinition room(String roomId) {
        return read("/content/rooms/" + roomId + ".json", RoomDefinition.class);
    }

    public EntityDefinition entity(String kind) {
        return read("/content/entities/" + kind + ".json", EntityDefinition.class);
    }

    public String prompt(String name) {
        try (InputStream in = open("/prompts/" + name + ".md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompt: " + name, e);
        }
    }

    private <T> T read(String path, Class<T> type) {
        try (InputStream in = open(path)) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed content file: " + path, e);
        }
    }

    private InputStream open(String path) {
        InputStream in = ContentLoader.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing content file: " + path);
        }
        return in;
    }
}
