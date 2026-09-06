package dm.state;

import dm.content.ContentLoader;
import dm.model.Event;
import dm.model.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartyMovedTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static Event.PartySpawned spawnFighter() {
        return new Event.PartySpawned(Instant.now(), List.of(
                CONTENT.entity("fighter").spawn("fighter", "crypt", 6, 1)));
    }

    private static Event.EntitySpawned spawnGoblin(String roomId, int x, int y) {
        return new Event.EntitySpawned(Instant.now(),
                CONTENT.entity("goblin").spawn("goblin", roomId, x, y));
    }

    private static Event.PartyMoved cross(String from, String to, int x, int y) {
        return new Event.PartyMoved(Instant.now(), List.of("fighter"), from, to,
                "door-north", x, y);
    }

    @Test
    @DisplayName("crossing moves the party's room, its square, and the world's idea of where it is")
    void crossingMovesTheParty() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                cross("crypt", "gallery", 6, 1)));

        assertEquals("gallery", state.roomId());
        var fighter = state.find("fighter").orElseThrow();
        assertEquals("gallery", fighter.roomId());
        assertEquals(6, fighter.x());
        assertEquals(1, fighter.y());
    }

    @Test
    @DisplayName("what you leave behind stays behind")
    void nonMoversStayPut() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                spawnGoblin("crypt", 6, 6),
                cross("crypt", "gallery", 6, 1)));

        var goblin = state.find("goblin").orElseThrow();
        assertEquals("crypt", goblin.roomId(), "the goblin did not walk through the door");
        assertEquals(6, goblin.x());
        assertEquals(6, goblin.y());
    }

    @Test
    @DisplayName("entitiesHere is the room you are in, and find still sees everything")
    void entitiesHereIsScopedAndFindIsNot() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                spawnGoblin("crypt", 6, 6),
                cross("crypt", "gallery", 6, 1)));

        assertEquals(List.of("fighter"), state.entitiesHere().stream().map(e -> e.id()).toList());
        // A goblin killed in a room you have left must stay findable: TtsClient's voice lookup
        // and spawnGoblin's already-dead refusal both go through find().
        assertTrue(state.find("goblin").isPresent());
    }

    @Test
    @DisplayName("a room you have entered is remembered as visited, including where you started")
    void visitsAreRemembered() {
        var start = WorldState.fold(List.of(spawnFighter()));
        assertTrue(start.hasVisited("crypt"), "the party is standing in it");
        assertFalse(start.hasVisited("gallery"));

        var moved = start.apply(cross("crypt", "gallery", 6, 1));
        assertTrue(moved.hasVisited("gallery"));
        assertTrue(moved.hasVisited("crypt"), "leaving a room does not unvisit it");
    }

    @Test
    @DisplayName("coming back does not re-reveal or forget anything")
    void returningRestoresTheRoom() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                new Event.PropRevealed(Instant.now(), "crypt", "alcove"),
                cross("crypt", "gallery", 6, 1),
                new Event.PartyMoved(Instant.now(), List.of("fighter"), "gallery", "crypt",
                        "door-south", 6, 10)));

        assertEquals("crypt", state.roomId());
        assertTrue(state.revealedHere().contains("alcove"), "the alcove was found and stays found");
        assertEquals(6, state.find("fighter").orElseThrow().x());
        assertEquals(10, state.find("fighter").orElseThrow().y());
    }

    @Test
    @DisplayName("crossing is not a fight, and does not touch the fight that is not happening")
    void crossingLeavesModeAlone() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                new Event.ModeEntered(Instant.now(), Mode.EXPLORATION),
                cross("crypt", "gallery", 6, 1)));

        assertEquals(Mode.EXPLORATION, state.mode());
        assertTrue(state.combat().isEmpty());
    }
}
