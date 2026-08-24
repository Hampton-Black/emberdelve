package dm.engine;

import dm.content.ContentLoader;
import dm.model.Difficulty;
import dm.model.Event;
import dm.model.Skill;
import dm.state.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine is not allowed a second write path. Every one of these asserts on the log rather
 * than on the world, because the world is downstream of it now — spec §5a.
 */
class GameEngineEventsTest {

    private EventLog log;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        log = new EventLog();
        engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());
    }

    private <T extends Event> List<T> eventsOf(Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    @DisplayName("starting the game records the party rather than quietly placing it")
    void startRecordsTheParty() {
        engine.start();

        var spawned = eventsOf(Event.PartySpawned.class);
        assertEquals(1, spawned.size());
        assertFalse(spawned.getFirst().members().isEmpty());
        assertEquals(spawned.getFirst().members().size(), engine.state().party().size(),
                "the party in the world is the party in the log, because it is the same party");
    }

    @Test
    @DisplayName("a move records where it came from as well as where it went")
    void moveRecordsBothEnds() {
        engine.start();
        var fighter = engine.state().party().getFirst().entityId();
        var from = engine.state().find(fighter).orElseThrow();
        int toX = from.x();
        int toY = from.y() + 1;

        engine.moveTo(fighter, toX, toY, new CombatSink.Buffer());

        var moved = eventsOf(Event.EntityMoved.class).getLast();
        assertEquals(from.x(), moved.fromX());
        assertEquals(from.y(), moved.fromY());
        assertEquals(toX, moved.x());
        assertEquals(toY, moved.y());
        assertEquals(0, moved.movementSpent(), "movement is a combat resource, not a walk");
    }

    @Test
    @DisplayName("a check records the roll, the DC, and what it was testing")
    void checkRecordsWhatWasTested() {
        engine.start();
        var fighter = engine.state().party().getFirst().entityId();

        engine.rollCheck(fighter, Skill.ATHLETICS, Difficulty.MEDIUM);

        var checked = eventsOf(Event.CheckResolved.class).getLast();
        assertEquals(Skill.ATHLETICS, checked.skill().orElseThrow());
        assertEquals(Difficulty.MEDIUM.dc(), checked.dc());
        assertFalse(checked.roll().faces().isEmpty(), "invariant #5: the faces survive");
    }

    @Test
    @DisplayName("the momentum counter is folded from the log, not held beside it")
    void momentumComesFromTheFold() {
        // A 1 on the die fails any DC, and a 20 passes any of them.
        var failing = new GameEngine(new ContentLoader(), log,
                new ScriptedDiceRoller(List.of(1, 1, 20)));
        failing.start();
        var fighter = failing.state().party().getFirst().entityId();

        failing.rollCheck(fighter, Skill.ATHLETICS, Difficulty.MEDIUM);
        failing.rollCheck(fighter, Skill.ATHLETICS, Difficulty.MEDIUM);
        assertEquals(2, failing.consecutiveFailedChecks());
        assertEquals(2, failing.state().consecutiveFailedChecks(),
                "there is one answer to this question, and the log is where it lives");

        failing.rollCheck(fighter, Skill.ATHLETICS, Difficulty.MEDIUM);
        assertEquals(0, failing.consecutiveFailedChecks(), "a success clears it");
    }

    @Test
    @DisplayName("revealing a prop twice records it once")
    void revealIsIdempotent() {
        engine.start();
        String propId = engine.room().props().getFirst().id();

        engine.revealProp(propId);
        engine.revealProp(propId);

        assertEquals(1, eventsOf(Event.PropRevealed.class).size());
    }
}
