package dm.ai;

import dm.model.Entity;
import dm.model.Event;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.state.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BeatRendererTest {

    private static final Instant T = Instant.now();

    private static Entity entity(String id, String name, boolean player) {
        return new Entity(id, "kind", name, 15, 12, 12, 4, "1d6", 2, 30, 2, 1, 1, player,
                Map.of());
    }

    private static final WorldState WORLD = WorldState.fold(List.of(
            new Event.PartySpawned(T, List.of(entity("fighter", "Roderick", true))),
            new Event.EntitySpawned(T, entity("goblin", "Vessk", false))));

    private static RollResult roll(int face, Outcome outcome) {
        return new RollResult(RollRequest.initiative("x", 0), List.of(face), face, outcome);
    }

    @Test
    @DisplayName("the goblin hitting the player is addressed to the player")
    void hostileHitsPlayer() {
        var beat = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "goblin", "fighter",
                roll(15, Outcome.HIT), Optional.of(roll(6, Outcome.HIT)), 6, true, false));

        // This is the exact beat that was narrated backwards in §4.4.
        assertTrue(beat.startsWith("Vessk hits you"), beat);
        assertFalse(beat.contains("Roderick"),
                "the player is never named in the third person in a beat");
    }

    @Test
    @DisplayName("the player hitting the goblin is written as the player doing it")
    void playerHitsHostile() {
        var beat = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "fighter", "goblin",
                roll(18, Outcome.HIT), Optional.of(roll(7, Outcome.HIT)), 7, true, false));

        assertTrue(beat.startsWith("You hit Vessk"), beat);
    }

    @Test
    @DisplayName("a kill says who died, in the right person")
    void killsReadCorrectly() {
        assertTrue(BeatRenderer.render(WORLD, new Event.AttackResolved(T, "fighter", "goblin",
                roll(18, Outcome.HIT), Optional.of(roll(9, Outcome.HIT)), 9, true, true))
                .contains("Vessk is killed"));

        assertTrue(BeatRenderer.render(WORLD, new Event.AttackResolved(T, "goblin", "fighter",
                roll(18, Outcome.HIT), Optional.of(roll(9, Outcome.HIT)), 9, true, true))
                .contains("You are killed"));
    }

    @Test
    @DisplayName("a miss and a fumble read differently, and never state a number")
    void missesAndFumbles() {
        var miss = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "goblin", "fighter",
                roll(4, Outcome.MISS), Optional.empty(), 0, false, false));
        var fumble = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "goblin", "fighter",
                roll(1, Outcome.CRIT_FAIL), Optional.empty(), 0, false, false));

        assertTrue(miss.contains("swings at you") && miss.contains("misses"), miss);
        assertTrue(fumble.contains("fumbles"), fumble);
        assertFalse(miss.matches(".*\\d.*"), "dm.md forbids hit points said aloud");
    }
}
