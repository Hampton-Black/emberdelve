package dm.state;

import dm.model.Event;
import dm.wire.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The append-only spine, and the only way state is ever produced.
 *
 * <p>{@link #append} does three things in one place: records the fact, folds it into the world,
 * and writes the line. Nothing else may write state — spec §5a. That is not tidiness; it is what
 * keeps the log complete without anybody having to remember to keep it complete.
 */
public final class EventLog {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final SessionWriter writer;

    private volatile WorldState state = WorldState.EMPTY;

    public EventLog() {
        this(null);
    }

    public EventLog(SessionWriter writer) {
        this.writer = writer;
    }

    public synchronized void append(Event event) {
        events.add(event);
        state = state.apply(event);
        if (writer != null) {
            writer.write(event);
        }
    }

    public WorldState state() {
        return state;
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    /** Forget the session. A new game in the same process, and a new file if one is attached. */
    public synchronized void clear() {
        events.clear();
        state = WorldState.EMPTY;
    }

    /**
     * Reads a recorded session back. No writer is attached — loading a log never appends to it.
     *
     * @throws IllegalStateException if the log was written by a different schema. Refused rather
     *                               than migrated, per spec §3.
     */
    public static EventLog load(Path jsonl) {
        var log = new EventLog();
        try (var lines = Files.lines(jsonl)) {
            lines.filter(line -> !line.isBlank())
                    .map(EventLog::parse)
                    .forEach(log::append);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        requireCurrentSchema(log, jsonl);
        return log;
    }

    private static Event parse(String line) {
        try {
            return Json.MAPPER.readValue(line, Event.class);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable event: " + line, e);
        }
    }

    private static void requireCurrentSchema(EventLog log, Path jsonl) {
        Optional<Integer> version = log.events().stream()
                .filter(Event.SessionStarted.class::isInstance)
                .map(Event.SessionStarted.class::cast)
                .map(Event.SessionStarted::schemaVersion)
                .findFirst();

        if (version.isEmpty() || version.get() != Event.SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "%s was written at schema %s and this build reads schema %d. Old logs are "
                            .formatted(jsonl, version.map(String::valueOf).orElse("unknown"),
                                    Event.SCHEMA_VERSION)
                            + "refused rather than migrated — record a new session.");
        }
    }
}
