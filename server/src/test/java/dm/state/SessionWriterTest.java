package dm.state;

import dm.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionWriterTest {

    @Test
    @DisplayName("a line is on disk before the next one is written")
    void flushesPerLine(@TempDir Path dir) throws Exception {
        try (var writer = SessionWriter.open(dir)) {
            writer.write(new Event.SessionStarted(
                    Instant.now(), Event.SCHEMA_VERSION, 7L, "tools", "prose"));

            // Not after close. The sessions worth having are the ones that ended badly.
            assertEquals(1, Files.readAllLines(writer.path()).size());
        }
    }

    @Test
    @DisplayName("two sessions in one process get two files")
    void oneFilePerSession(@TempDir Path dir) throws Exception {
        try (var first = SessionWriter.open(dir); var second = SessionWriter.open(dir)) {
            assertNotEquals(first.path(), second.path());
        }
        assertEquals(2, Files.list(dir).count());
    }
}
