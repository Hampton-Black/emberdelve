package dm.engine;

import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.ConsequenceId;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2 of emberdelve-4h9.3: signs fire on crossing upward only; LIGHT fill is LIGHT_OUT
 * and ticks ALERT from the tick side, never from inside the consequence.
 */
class ClockSignsTest {

    private static GameEngine started() {
        var engine = new GameEngine(new ContentLoader(), new EventLog(), new RandomDiceRoller());
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("ticking LIGHT from 0 to 2 fires LIGHT_LOW and nothing else")
    void lightLowFiresOnSegmentTwo() {
        var engine = started();

        engine.tickClock(ClockId.LIGHT);
        engine.tickClock(ClockId.LIGHT);

        var fired = engine.log().events().stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .toList();
        assertEquals(1, fired.size());
        assertEquals(ClockId.LIGHT, fired.getFirst().clock());
        assertEquals(ConsequenceId.LIGHT_LOW, fired.getFirst().id());
        assertEquals(2, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(0, engine.state().clock(ClockId.ALERT).filled(),
                "a sign does not tick the other clock");
    }

    @Test
    @DisplayName("a torch from 5 to 0 fires no sign")
    void torchFromFailingFiresNothing() {
        var engine = started();
        for (int i = 0; i < 5; i++) {
            engine.tickClock(ClockId.LIGHT);
        }
        assertEquals(5, engine.state().clock(ClockId.LIGHT).filled());

        engine.resetLight();

        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());
        var fired = engine.log().events().stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .map(Event.ConsequenceFired::id)
                .toList();
        assertEquals(List.of(ConsequenceId.LIGHT_LOW, ConsequenceId.LIGHT_GUTTERING,
                ConsequenceId.LIGHT_FAILING), fired,
                "a torch spent at FAILING must not announce GUTTERING on the way down");
    }

    @Test
    @DisplayName("LIGHT filling fires LIGHT_OUT and ticks ALERT once")
    void lightFillTicksAlertFromTheTickSide() {
        var engine = started();
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.LIGHT);
        }

        assertEquals(6, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled(),
                "LIGHT filling ticks ALERT; declared on the tick side");

        var fired = engine.log().events().stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .map(Event.ConsequenceFired::id)
                .toList();
        assertTrue(fired.contains(ConsequenceId.LIGHT_OUT));
        assertEquals(ConsequenceId.LIGHT_OUT, fired.getLast());

        var ticks = engine.log().events().stream()
                .filter(Event.ClockTicked.class::isInstance)
                .map(Event.ClockTicked.class::cast)
                .toList();
        assertTrue(ticks.stream().anyMatch(t -> t.clock() == ClockId.ALERT && t.filled() == 1));
    }

    @Test
    @DisplayName("every id in a sign table has an empty bundle")
    void signTableEntriesHaveEmptyBundles() {
        for (var id : ClockTables.LIGHT_SIGNS.values()) {
            assertTrue(ClockTables.bundleIsEmpty(id), id.name());
        }
        for (var id : ClockTables.ALERT_SIGNS.values()) {
            assertTrue(ClockTables.bundleIsEmpty(id), id.name());
        }
    }
}
