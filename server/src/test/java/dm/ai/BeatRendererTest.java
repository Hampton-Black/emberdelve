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
        return new Entity(id, "kind", name, 15, 12, 12, 4, "1d6", 2, 30, 2, "crypt", 1, 1, player,
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
    @DisplayName("a kill names who struck it, in the right person")
    void killsNameTheActor() {
        // m2-evaluation.md §8: the beat said only "Vessk is killed by the blow", so the narrator
        // had to guess who swung. Gemini guessed right; a worse writer will invert it, which is
        // the M0 §4.4 fault all over again.
        var playerKills = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "fighter", "goblin",
                roll(18, Outcome.HIT), Optional.of(roll(9, Outcome.HIT)), 9, true, true));
        assertTrue(playerKills.startsWith("You hit Vessk"), playerKills);
        assertTrue(playerKills.contains("Vessk is killed"), playerKills);

        var goblinKills = BeatRenderer.render(WORLD, new Event.AttackResolved(T, "goblin", "fighter",
                roll(18, Outcome.HIT), Optional.of(roll(9, Outcome.HIT)), 9, true, true));
        assertTrue(goblinKills.startsWith("Vessk hits you"), goblinKills);
        assertTrue(goblinKills.contains("You are killed"), goblinKills);
        assertFalse(goblinKills.contains("Roderick"),
                "the player is never named in the third person in a beat");
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

    @Test
    @DisplayName("closing on the party is addressed to the player")
    void closeOnPartyIsSecondPerson() {
        var beat = BeatRenderer.renderClose(WORLD, "goblin", "fighter");

        assertEquals("Vessk closes the distance to you.", beat);
        assertFalse(beat.contains("Roderick"),
                "the player is never named in the third person in a beat");
    }

    @Test
    @DisplayName("advancing on the party is addressed to the player")
    void advanceOnPartyIsSecondPerson() {
        var beat = BeatRenderer.renderAdvance(WORLD, "goblin", "fighter");

        assertEquals("Vessk advances toward you.", beat);
        assertFalse(beat.contains("Roderick"),
                "the player is never named in the third person in a beat");
    }

    @Test
    @DisplayName("closing on someone outside the party is by name")
    void closeOnNonPartyIsByName() {
        var world = WorldState.fold(List.of(
                new Event.PartySpawned(T, List.of(entity("fighter", "Roderick", true))),
                new Event.EntitySpawned(T, entity("goblin", "Vessk", false)),
                new Event.EntitySpawned(T, entity("wight", "Aldric", false))));

        assertEquals("Vessk closes the distance to Aldric.",
                BeatRenderer.renderClose(world, "goblin", "wight"));
        assertEquals("Vessk advances toward Aldric.",
                BeatRenderer.renderAdvance(world, "goblin", "wight"));
    }
}
