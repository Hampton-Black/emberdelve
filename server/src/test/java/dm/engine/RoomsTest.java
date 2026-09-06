package dm.engine;

import dm.content.ContentLoader;
import dm.generate.Dressing;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine engine(EventLog log) {
        return new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt"));
    }

    @Test
    @DisplayName("a room with no dressing folded is the room as authored")
    void undressedIsTheRoomOnDisk() {
        var engine = engine(new EventLog());

        assertEquals(CONTENT.room("crypt").name(), engine.room().name());
        assertEquals("crypt", engine.room().roomId());
    }

    @Test
    @DisplayName("a folded dressing is what the engine serves")
    void foldedDressingWins() {
        var log = new EventLog();
        var engine = engine(log);

        log.append(new Event.RoomDressed(Instant.now(), "crypt",
                new Dressing("The Drowned Vault", "Water to the ankles.", "It drips.",
                        Map.of("sarcophagus", "A stone box, slick with algae."))));

        assertEquals("The Drowned Vault", engine.room().name());
        assertEquals("A stone box, slick with algae.",
                engine.room().prop("sarcophagus").description());
        assertEquals(CONTENT.room("crypt").width(), engine.room().width(),
                "structure is not dressing");
    }

    @Test
    @DisplayName("room(id) serves a room the party is not standing in")
    void anyRoomByIdWithoutMoving() {
        var engine = engine(new EventLog());

        assertEquals("crypt", engine.room("crypt").roomId());
        assertThrows(IllegalArgumentException.class, () -> engine.room("nowhere"));
    }

    @Test
    @DisplayName("the engine starts the party in the first room it was given")
    void firstRoomIsTheEntrance() {
        var log = new EventLog();
        var engine = engine(log);
        engine.start();

        assertEquals("crypt", engine.state().roomId());
        assertEquals("crypt", engine.state().find("fighter").orElseThrow().roomId());
    }

    @Test
    @DisplayName("entering a room for the first time records the dressing it is wearing")
    void firstEntryRecordsTheDressing() {
        var log = new EventLog();
        engine(log).start();

        var dressed = log.events().stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .toList();

        assertEquals(1, dressed.size(), "exactly one dressing, for the room we opened in");
        assertEquals("crypt", dressed.getFirst().roomId());
        assertEquals(CONTENT.room("crypt").name(), dressed.getFirst().dressing().name());
    }

    @Test
    @DisplayName("combat reads terrain from the room the party is in, not the one it started in")
    void combatFollowsTheParty() {
        // The walkability check is the only place CombatEngine reads a room. If it held a value
        // rather than a supplier, a fight in room 2 would be fought against room 1's pillars.
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt"));
        engine.start();

        assertEquals(engine.room().roomId(), engine.combat().terrain().roomId(),
                "combat and the engine must agree about which room this is");
        assertEquals(engine.room().width(), engine.combat().terrain().width());
    }
}
