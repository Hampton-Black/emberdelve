package dm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema rule that matters is spec §4: an event is a fact, not a command. A fact carries its
 * own outcome, so replaying it is a pure function; a command would have to be re-adjudicated and
 * would produce a different session every time.
 */
class EventTest {

    @Test
    @DisplayName("an attack carries its own outcome, both sides of it, and its dice")
    void attackIsAFactNotACommand() {
        var attackRoll = new RollResult(
                RollRequest.attack("goblin", "fighter", 4, 16),
                List.of(18), 22, Outcome.HIT);
        var damageRoll = new RollResult(
                RollRequest.damage("goblin", "1d6", 2), List.of(4), 6, Outcome.HIT);

        var event = new Event.AttackResolved(Instant.now(), "goblin", "fighter",
                attackRoll, Optional.of(damageRoll), 6, true, false);

        // Both sides, as fields. This is what lets BeatRenderer put the sentence in the right
        // person without the model having to work out who did what to whom.
        assertEquals("goblin", event.actorId());
        assertEquals("fighter", event.targetId());
        assertEquals(6, event.damageDealt());
        // Invariant #5: the faces survive, and are never collapsed to the total.
        assertEquals(List.of(18), event.attack().faces());
    }

    @Test
    @DisplayName("an ambient fact is the shared instance and carries no position")
    void ambientAnchor() {
        var fact = new Event.FactAsserted(Instant.now(), "fact-1", "crypt",
                "The air tastes of old iron.", Anchor.AMBIENT);
        assertTrue(fact.anchor() instanceof Anchor.Ambient);
    }
}
