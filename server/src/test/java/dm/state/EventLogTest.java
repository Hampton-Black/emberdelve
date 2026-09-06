package dm.state;

import dm.model.Anchor;
import dm.model.Combatant;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    private static final Instant T = Instant.parse("2026-08-23T12:00:00Z");

    private static Entity goblin() {
        return new Entity("goblin", "goblin", "Vessk", 15, 7, 7, 4, "1d6", 2, 30, 2, "crypt",
                2, 2, false, Map.of());
    }

    private static RollResult roll(int face, int total, Outcome outcome) {
        return new RollResult(RollRequest.initiative("x", 0), List.of(face), total, outcome);
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
    @DisplayName("checks, attacks, and initiative round-trip through JSONL")
    void rollEventsRoundTrip(@TempDir Path dir) throws Exception {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);

        log.append(new Event.SessionStarted(T, Event.SCHEMA_VERSION, 7L, "tools", "prose"));
        log.append(new Event.CheckResolved(T, "fighter", Optional.empty(), 15,
                roll(18, 21, Outcome.SUCCESS), Outcome.SUCCESS));
        log.append(new Event.AttackResolved(T, "fighter", "goblin",
                roll(20, 25, Outcome.CRIT), Optional.of(roll(6, 9, Outcome.HIT)), 9, true, false));
        log.append(new Event.CombatStarted(T, List.of(
                new Combatant("fighter", "fighter", 18, true),
                new Combatant("goblin", "goblin", 9, false)),
                List.of(roll(16, 18, Outcome.SUCCESS), roll(7, 9, Outcome.SUCCESS))));
        writer.close();

        var reloaded = EventLog.load(writer.path());
        assertEquals(log.events(), reloaded.events());
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

    @Test
    @DisplayName("clearing a written session opens a new file")
    void clearOpensANewFile(@TempDir Path dir) throws Exception {
        var first = SessionWriter.open(dir);
        var log = new EventLog(first);
        log.append(new Event.SessionStarted(T, Event.SCHEMA_VERSION, 7L, "tools", "prose"));

        log.clear();
        log.append(new Event.SessionStarted(T, Event.SCHEMA_VERSION, 7L, "tools", "prose"));

        var files = Files.list(dir).toList();
        assertEquals(2, files.size());
        Path second = files.stream()
                .filter(p -> !p.equals(first.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, Files.readAllLines(first.path()).size(),
                "the old file is left as it was");
        assertEquals(1, Files.readAllLines(second).size(),
                "the new file received the post-clear event");
    }
}
