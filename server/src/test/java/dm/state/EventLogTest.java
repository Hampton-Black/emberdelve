package dm.state;

import dm.model.Anchor;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    private static final Instant T = Instant.parse("2026-08-23T12:00:00Z");

    private static Entity goblin() {
        return new Entity("goblin", "goblin", "Vessk", 15, 7, 7, 4, "1d6", 2, 30, 2,
                2, 2, false, Map.of());
    }

    @Test
    @DisplayName("appending folds as it goes")
    void appendFolds() {
        var log = new EventLog();
        log.append(new Event.EntitySpawned(T, goblin()));
        log.append(new Event.EntityMoved(T, "goblin", 2, 2, 4, 4, 2));

        assertEquals(4, log.state().find("goblin").orElseThrow().x());
        assertEquals(2, log.events().size());
    }

    @Test
    @DisplayName("a session round-trips through JSONL, one event per line")
    void roundTrip(@TempDir Path dir) throws Exception {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);

        log.append(new Event.SessionStarted(T, Event.SCHEMA_VERSION, 7L, "tools", "prose"));
        log.append(new Event.EntitySpawned(T, goblin()));
        log.append(new Event.EntityMoved(T, "goblin", 2, 2, 4, 4, 2));
        log.append(new Event.FactAsserted(T, "f1", "crypt", "Iron on the air.", Anchor.AMBIENT));
        log.append(new Event.ModeEntered(T, Mode.EXPLORATION));
        writer.close();

        var lines = Files.readAllLines(writer.path());
        assertEquals(5, lines.size(), "one event per line, no pretty printing");
        assertTrue(lines.getFirst().contains("\"type\":\"session_started\""));

        var reloaded = EventLog.load(writer.path());
        assertEquals(log.events(), reloaded.events());
        assertEquals(log.state(), reloaded.state(),
                "a log read back from disk folds to the same world");
    }

    @Test
    @DisplayName("a log from an older schema is refused, not upgraded")
    void refusesStaleSchema(@TempDir Path dir) throws Exception {
        var file = dir.resolve("old.jsonl");
        Files.writeString(file, """
                {"type":"session_started","at":"2026-01-01T00:00:00Z","schemaVersion":0,\
                "seed":1,"toolModel":"t","proseModel":"p"}
                """);

        var thrown = assertThrows(IllegalStateException.class, () -> EventLog.load(file));
        assertTrue(thrown.getMessage().contains("schema"));
    }

    @Test
    @DisplayName("clearing starts a new session, and forgets the old one")
    void clearForgets() {
        var log = new EventLog();
        log.append(new Event.EntitySpawned(T, goblin()));
        log.clear();

        assertTrue(log.events().isEmpty());
        assertEquals(WorldState.EMPTY, log.state());
    }
}
