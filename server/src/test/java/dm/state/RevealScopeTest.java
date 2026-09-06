package dm.state;

import dm.model.Event;
import dm.model.PropRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §4a. Prop ids are unique within a room and nowhere else — {@code PropPlacer} names its
 * output {@code pillar-0} in every room it generates — so a flat set of revealed ids marks a
 * prop found in a room nobody has walked into.
 */
class RevealScopeTest {

    private static Event.PropRevealed reveal(String roomId, String propId) {
        return new Event.PropRevealed(Instant.now(), roomId, propId);
    }

    @Test
    @DisplayName("revealing a prop in one room leaves its namesake hidden in another")
    void revealsDoNotLeakBetweenRooms() {
        var state = WorldState.fold(List.of(reveal("crypt", "pillar-0")));

        assertTrue(state.revealedProps().contains(new PropRef("crypt", "pillar-0")));
        assertFalse(state.revealedProps().contains(new PropRef("gallery", "pillar-0")));
    }

    @Test
    @DisplayName("revealedHere is scoped to the room the party is standing in")
    void revealedHereFollowsTheParty() {
        var state = WorldState.fold(List.of(
                reveal("crypt", "alcove"),
                reveal("gallery", "pillar-0")));

        // EMPTY starts in the crypt, and nothing in this fold moves the party.
        assertEquals("crypt", state.roomId());
        assertEquals(java.util.Set.of("alcove"), state.revealedHere());
    }

    @Test
    @DisplayName("revealing the same prop twice is not two reveals")
    void revealsAreIdempotent() {
        var state = WorldState.fold(List.of(
                reveal("crypt", "alcove"),
                reveal("crypt", "alcove")));

        assertEquals(1, state.revealedProps().size());
    }
}
