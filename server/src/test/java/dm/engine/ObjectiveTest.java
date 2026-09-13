package dm.engine;

import dm.content.ContentLoader;
import dm.model.Diff;
import dm.model.Ending;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.4: the objective is a prop you can take, and the way out checks for it.
 */
class ObjectiveTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static <T extends Event> List<T> eventsOf(EventLog log, Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    @DisplayName("taking the reliquary holds it, removes it from the room, and ships held on the scene")
    void takeHoldsAndRemoves() {
        var log = new EventLog();
        var engine = started(log);

        assertTrue(engine.scene().currentRoom().props().stream()
                .anyMatch(p -> p.id().equals("reliquary") && p.actions().contains("take")));
        assertFalse(engine.state().holdingObjective());
        assertFalse(engine.scene().holdingObjective());

        var diffs = engine.takeProp("reliquary");

        assertTrue(engine.state().holdingObjective());
        assertTrue(engine.scene().holdingObjective());
        assertTrue(engine.scene().currentRoom().props().stream()
                .noneMatch(p -> p.id().equals("reliquary")),
                "the prop leaves the visible list");
        assertEquals(1, eventsOf(log, Event.ObjectiveTaken.class).size());
        var taken = eventsOf(log, Event.ObjectiveTaken.class).getFirst();
        assertEquals("crypt", taken.roomId());
        assertEquals("reliquary", taken.propId());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.PropRemoved removed
                && removed.propId().equals("reliquary")));
    }

    @Test
    @DisplayName("leaving the way out holding the objective ends EXTRACTED_WITH_OBJECTIVE")
    void leaveHoldingExtractsWithObjective() {
        var log = new EventLog();
        var engine = started(log);
        engine.takeProp("reliquary");

        engine.crossExit("stair-south");

        assertEquals(Optional.of(Ending.EXTRACTED_WITH_OBJECTIVE), engine.state().ending());
        assertEquals(Ending.EXTRACTED_WITH_OBJECTIVE, engine.scene().ending().ending());
        assertEquals(1, eventsOf(log, Event.DelveEnded.class).size());
        var ended = eventsOf(log, Event.DelveEnded.class).getFirst();
        assertEquals(Ending.EXTRACTED_WITH_OBJECTIVE, ended.ending());
        assertEquals("stair-south", ended.throughExitId());
        assertEquals("crypt", engine.state().roomId(), "extracting is not a room change");
        assertTrue(eventsOf(log, Event.PartyMoved.class).isEmpty());
        assertTrue(eventsOf(log, Event.FactAsserted.class).isEmpty());
    }

    @Test
    @DisplayName("leaving the way out without taking ends EXTRACTED_WITHOUT")
    void leaveWithoutExtractsWithout() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("stair-south");

        assertEquals(Optional.of(Ending.EXTRACTED_WITHOUT), engine.state().ending());
        assertEquals(Ending.EXTRACTED_WITHOUT, engine.scene().ending().ending());
        var ended = eventsOf(log, Event.DelveEnded.class).getFirst();
        assertEquals(Ending.EXTRACTED_WITHOUT, ended.ending());
        assertFalse(engine.state().holdingObjective());
        assertTrue(eventsOf(log, Event.FactAsserted.class).isEmpty());
    }

    @Test
    @DisplayName("the gallery door still crosses and does not end the delve")
    void galleryDoorStillCrosses() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");

        assertEquals("gallery", engine.state().roomId());
        assertTrue(engine.state().ending().isEmpty());
        assertEquals(1, eventsOf(log, Event.PartyMoved.class).size());
        assertTrue(eventsOf(log, Event.DelveEnded.class).isEmpty());
    }

    @Test
    @DisplayName("after extract, every action except restart is refused")
    void refuseAfterExtract() {
        var engine = started(new EventLog());
        engine.crossExit("stair-south");

        assertThrows(IllegalArgumentException.class, () -> engine.takeProp("reliquary"));
        assertThrows(IllegalArgumentException.class, () -> engine.crossExit("door-north"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.moveTo("fighter", 5, 5, new CombatSink.Buffer()));
        assertThrows(IllegalArgumentException.class, () -> engine.revealProp("alcove"));

        engine.restart();
        assertTrue(engine.state().ending().isEmpty());
        assertEquals("crypt", engine.state().roomId());
        assertFalse(engine.state().holdingObjective());
    }
}
