package dm.state;

import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.model.ClockId;
import dm.model.ClockKind;
import dm.model.ClockScale;
import dm.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 1 of emberdelve-4h9.3: clocks fold from {@code ClockTicked}, a torch empties LIGHT,
 * and ALERT discharges when it fills. No crossing / check / fight tick sites yet.
 */
class ClockFoldTest {

    private static final Instant T = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    @DisplayName("an empty world already has both DELVE clocks, empty")
    void emptyWorldHasTwoEmptyClocks() {
        var state = WorldState.EMPTY;
        var light = state.clock(ClockId.LIGHT);
        var alert = state.clock(ClockId.ALERT);

        assertEquals(ClockScale.DELVE, light.scale());
        assertEquals(ClockKind.LIGHT, light.kind());
        assertEquals(0, light.filled());
        assertEquals(6, light.segments());

        assertEquals(ClockScale.DELVE, alert.scale());
        assertEquals(ClockKind.ALERT, alert.kind());
        assertEquals(0, alert.filled());
        assertEquals(6, alert.segments());
    }

    @Test
    @DisplayName("ClockTicked carries the resulting fill, not a delta")
    void clockTickedSetsFill() {
        var state = WorldState.fold(List.of(
                new Event.ClockTicked(T, ClockId.LIGHT, 3),
                new Event.ClockTicked(T, ClockId.ALERT, 2)));

        assertEquals(3, state.clock(ClockId.LIGHT).filled());
        assertEquals(2, state.clock(ClockId.ALERT).filled());
    }

    @Test
    @DisplayName("a torch hook empties LIGHT and fires nothing on the way down")
    void torchHookEmptiesLight() {
        var log = new EventLog();
        log.append(new Event.ClockTicked(T, ClockId.LIGHT, 5));
        var engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());

        engine.resetLight();

        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());
        assertTrue(log.events().stream().noneMatch(Event.ConsequenceFired.class::isInstance),
                "a torch spent at FAILING must not announce GUTTERING on the way down");
    }

    @Test
    @DisplayName("ALERT fill resets to empty; LIGHT stays full")
    void alertFillResetsAndLightStays() {
        var state = WorldState.fold(List.of(
                new Event.ClockTicked(T, ClockId.LIGHT, 6),
                new Event.ClockTicked(T, ClockId.ALERT, 6)));

        assertEquals(6, state.clock(ClockId.LIGHT).filled(), "LIGHT sits full until a torch");
        assertEquals(0, state.clock(ClockId.ALERT).filled(), "ALERT discharges when it fills");
    }
}
