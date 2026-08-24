package dm;

import dm.model.Entity;
import dm.model.Event;
import dm.state.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TTS kind resolver lives in {@link App} as a one-line fallback, and that one line is
 * the difference between a goblin arriving in its own voice and the narrator stealing the line.
 */
class AppTest {

    private static final Instant T = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    @DisplayName("an unresolved goblin id is itself the kind, so the arrival line keeps the goblin voice")
    void unresolvedGoblinKeepsItsKind() {
        assertEquals("goblin", App.kindForVoice(WorldState.EMPTY, "goblin"),
                "[[goblin]] is attributed before reconcile has spawned the creature");
        assertEquals("narrator", App.kindForVoice(WorldState.EMPTY, "narrator"));
    }

    @Test
    @DisplayName("a spawned creature's kind wins over its id")
    void spawnedKindWins() {
        var goblin = new Entity("goblin-1", "goblin", "Vessk",
                15, 7, 7, 4, "1d6", 2, 30, 2, 3, 3, false, Map.of());
        var state = WorldState.fold(List.of(new Event.EntitySpawned(T, goblin)));

        assertEquals("goblin", App.kindForVoice(state, "goblin-1"));
    }
}
