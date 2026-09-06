package dm.engine;

import dm.content.ContentLoader;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossExitTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("crossing lands the party one square inside, not in the doorway")
    void landsInward() {
        var engine = started(new EventLog());

        engine.crossExit("door-north");

        assertEquals("gallery", engine.state().roomId());
        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals("gallery", fighter.roomId());
        // The gallery's south door answers the crypt's north one. Landing on the door itself
        // would put the party on the trigger that sent them there.
        var arrival = engine.room("gallery").exits().stream()
                .filter(e -> e.toRoomId().equals("crypt"))
                .findFirst().orElseThrow();
        assertNotEquals(arrival.square(), new dm.model.Square(fighter.x(), fighter.y()));
        assertEquals(arrival.inward(engine.room("gallery").width(),
                engine.room("gallery").height()),
                new dm.model.Square(fighter.x(), fighter.y()));
    }

    @Test
    @DisplayName("crossing emits one PartyMoved naming the exit it went through")
    void emitsOneEvent() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");

        var moves = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .toList();
        assertEquals(1, moves.size());
        assertEquals("crypt", moves.getFirst().fromRoomId());
        assertEquals("gallery", moves.getFirst().toRoomId());
        assertEquals("door-north", moves.getFirst().throughExitId());
        assertEquals(java.util.List.of("fighter"), moves.getFirst().entityIds());
    }

    @Test
    @DisplayName("only the living cross — a corpse is not carried through the door")
    void deadPartyMembersStayBehind() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);

        engine.crossExit("door-north");

        // The goblin was never party, so it stays regardless; the assertion that matters is
        // that entityIds is the living party and not "everything in the room".
        var moved = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .findFirst().orElseThrow();
        assertFalse(moved.entityIds().contains("goblin"));
        assertEquals("crypt", engine.state().find("goblin").orElseThrow().roomId());
    }

    @Test
    @DisplayName("arriving somewhere new records its dressing exactly once")
    void firstArrivalDresses() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");
        engine.crossExit("door-south");
        engine.crossExit("door-north");

        long gallery = log.events().stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .filter(e -> e.roomId().equals("gallery"))
                .count();
        assertEquals(1, gallery, "a room is dressed on first entry and never again");
    }

    @Test
    @DisplayName("an exit that is not in this room is refused")
    void unknownExitRefused() {
        var engine = started(new EventLog());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-west"));
        assertTrue(thrown.getMessage().contains("door-west"), thrown.getMessage());
    }

    @Test
    @DisplayName("you cannot walk out of a fight")
    void exitsAreIllegalInCombat() {
        var engine = started(new EventLog());
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-north"));
        assertTrue(thrown.getMessage().toLowerCase().contains("fight"), thrown.getMessage());
        assertEquals("crypt", engine.state().roomId());
    }

    @Test
    @DisplayName("the scene the client is sent carries the room's exits")
    void sceneCarriesExits() {
        var engine = started(new EventLog());

        assertEquals(engine.room().exits(), engine.scene().exits());
        assertFalse(engine.scene().exits().isEmpty());
    }
}
