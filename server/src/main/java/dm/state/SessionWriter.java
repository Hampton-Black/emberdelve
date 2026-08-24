package dm.state;

import dm.model.Event;
import dm.wire.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One JSONL file per session, one event per line.
 *
 * <p>Write-only, in the sense that matters: nothing resumes play from one of these. They are read
 * by {@code --replay} and by the test suite, and that is the whole point of them — a fault found
 * in play becomes a file you can check in.
 *
 * <p>Flushed per line rather than on close, because the sessions worth having are the ones that
 * ended badly.
 */
public final class SessionWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionWriter.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path path;
    private final BufferedWriter out;

    private SessionWriter(Path path, BufferedWriter out) {
        this.path = path;
        this.out = out;
    }

    public static SessionWriter open(Path directory) {
        try {
            Files.createDirectories(directory);
            String name = STAMP.format(Instant.now()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".jsonl";
            Path file = directory.resolve(name);
            var out = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("session log: {}", file);
            return new SessionWriter(file, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path path() {
        return path;
    }

    /**
     * Never throws. A session that cannot be written is a session played without a recording,
     * which is worse than it sounds and much better than a turn that dies mid-narration.
     */
    public void write(Event event) {
        try {
            out.write(Json.MAPPER.writeValueAsString(event));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            log.warn("could not write to the session log, play continues unrecorded", e);
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            log.warn("could not close the session log", e);
        }
    }
}
