package dm.engine;

import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.ConsequenceId;
import dm.model.Diff;
import dm.model.Difficulty;
import dm.model.Event;
import dm.model.LightingPreset;
import dm.model.Mode;
import dm.model.Skill;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slices 3–7 of emberdelve-4h9.3: tick sites, ALERT fill, and the draw filter.
 */
class ClockTriggersTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(ClockDraw draw) {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"), draw);
        engine.start();
        return engine;
    }

    private static GameEngine started() {
        return started(ClockDraw.random());
    }

    private static List<ConsequenceId> fired(GameEngine engine) {
        return engine.log().events().stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .map(Event.ConsequenceFired::id)
                .toList();
    }

    @Test
    @DisplayName("crossExit ticks both clocks after the party has arrived")
    void crossingTicksBoth() {
        var engine = started();

        engine.crossExit("door-north");

        assertEquals("gallery", engine.state().roomId());
        assertEquals(1, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled());
        assertTrue(fired(engine).isEmpty(), "segment 1 is quiet");
    }

    @Test
    @DisplayName("two crossings reach segment 2 and fire both clocks' signs")
    void twoCrossingsFireSegmentTwoSigns() {
        var engine = started();

        engine.crossExit("door-north");
        engine.crossExit("door-south");

        assertEquals(2, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(2, engine.state().clock(ClockId.ALERT).filled());
        assertEquals(List.of(ConsequenceId.LIGHT_LOW, ConsequenceId.SOMETHING_STIRRED),
                fired(engine));
    }

    @Test
    @DisplayName("a natural 1 ticks LIGHT; an ordinary fail ticks nothing")
    void naturalOneTicksLightAndOrdinaryFailDoesNot() {
        var nat1 = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(1),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        nat1.start();
        nat1.rollCheck("fighter", Skill.ATHLETICS, Difficulty.MEDIUM);

        assertEquals(1, nat1.state().clock(ClockId.LIGHT).filled());
        assertEquals(0, nat1.state().clock(ClockId.ALERT).filled());
        assertEquals(1, nat1.consecutiveFailedChecks(), "momentum still counts the fail");

        var ordinary = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(5),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        ordinary.start();
        ordinary.rollCheck("fighter", Skill.ATHLETICS, Difficulty.MEDIUM);

        assertEquals(0, ordinary.state().clock(ClockId.LIGHT).filled(),
                "ADR-0013: the model picked the DC, so the fail does not spend light");
        assertEquals(1, ordinary.consecutiveFailedChecks());
    }

    @Test
    @DisplayName("starting a fight ticks ALERT once")
    void fightStartTicksAlert() {
        var engine = started();
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled());
    }

    @Test
    @DisplayName("the rest hook ticks both clocks")
    void restTickAdvancesBoth() {
        var engine = started();
        engine.restTick();
        assertEquals(1, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled());
    }

    @Test
    @DisplayName("ALERT fill drawing IT_PASSES_BY resets, moves crypt fires, and ratchets")
    void alertFillPassesByAndMovesCryptFires() {
        var engine = started(new ScriptedClockDraw(ConsequenceId.IT_PASSES_BY,
                ConsequenceId.IT_PASSES_BY));

        List<Diff> first = List.of();
        for (int i = 0; i < 6; i++) {
            first = engine.tickClock(ClockId.ALERT);
        }

        assertEquals(0, engine.state().clock(ClockId.ALERT).filled());
        assertEquals(ConsequenceId.IT_PASSES_BY, fired(engine).getLast());
        assertTrue(first.stream().anyMatch(d -> d instanceof Diff.RoomLightingChanged change
                && change.roomId().equals("crypt")
                && change.lighting() == LightingPreset.DARK), first.toString());

        var lighting = engine.log().events().stream()
                .filter(Event.RoomLightingChanged.class::isInstance)
                .map(Event.RoomLightingChanged.class::cast)
                .toList();
        assertEquals(1, lighting.size());
        assertEquals("crypt", lighting.getFirst().roomId());
        assertEquals(LightingPreset.BRAZIERLIT, lighting.getFirst().from());
        assertEquals(LightingPreset.DARK, lighting.getFirst().to());
        assertEquals(LightingPreset.DARK,
                engine.scene().currentRoom().lighting());

        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.ALERT);
        }
        assertEquals(1, engine.log().events().stream()
                .filter(Event.RoomLightingChanged.class::isInstance)
                .count(), "a moved room stays moved");
    }

    @Test
    @DisplayName("a room without fires never emits RoomLightingChanged")
    void galleryFillDoesNotMoveFires() {
        var engine = started(new ScriptedClockDraw(ConsequenceId.IT_PASSES_BY));
        engine.crossExit("door-north");
        assertEquals("gallery", engine.state().roomId());

        for (int i = 0; i < 5; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        assertTrue(engine.log().events().stream()
                .noneMatch(Event.RoomLightingChanged.class::isInstance));
        assertEquals(LightingPreset.DIM, engine.scene().currentRoom().lighting(),
                "gallery stays as authored");
    }

    @Test
    @DisplayName("SOMETHING_WANDERS_IN spawns a wary goblin and does not start a fight")
    void somethingWandersIn() {
        var engine = started(new ScriptedClockDraw(ConsequenceId.SOMETHING_WANDERS_IN));
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        assertTrue(engine.state().find("goblin").isPresent());
        assertTrue(engine.state().find("goblin").orElseThrow().isAlive());
        assertEquals("crypt", engine.state().find("goblin").orElseThrow().roomId());
        assertEquals(Mode.EXPLORATION, engine.state().mode());
        assertFalse(engine.combat().isActive());
        assertEquals(ConsequenceId.SOMETHING_WANDERS_IN, fired(engine).getLast());
    }

    @Test
    @DisplayName("PATROL_ARRIVES spawns and starts combat")
    void patrolArrives() {
        var engine = started(new ScriptedClockDraw(ConsequenceId.PATROL_ARRIVES));
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        assertTrue(engine.state().find("goblin").isPresent());
        assertTrue(engine.combat().isActive());
        assertEquals(Mode.COMBAT, engine.state().mode());
        assertEquals(ConsequenceId.PATROL_ARRIVES, fired(engine).getLast());
    }

    @Test
    @DisplayName("DRAWN_BY_THE_NOISE is dropped from the draw when nobody is elsewhere")
    void drawnByTheNoiseDropsWhenNobodyIsElsewhere() {
        var seen = new java.util.ArrayList<List<ConsequenceId>>();
        ClockDraw capture = options -> {
            seen.add(List.copyOf(options));
            return ConsequenceId.IT_PASSES_BY;
        };
        var engine = started(capture);
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        assertFalse(seen.isEmpty());
        assertFalse(seen.getLast().contains(ConsequenceId.DRAWN_BY_THE_NOISE),
                "logging a draw that did nothing is a log that lies: " + seen.getLast());
        assertEquals(ConsequenceId.IT_PASSES_BY, fired(engine).getLast());
        assertTrue(engine.state().find("goblin").isEmpty());
        assertFalse(engine.combat().isActive());
    }
}
