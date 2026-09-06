package dm.content;

import dm.generate.Dressing;
import dm.model.Event;
import dm.state.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §5b. An authored room already contains a {@code Dressing}, spread across fields — so both
 * sources go through one compose path, and M3 exercises it with content that needs no network.
 */
class DressingsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("an authored room yields the dressing it was written with")
    void extractsFromAnAuthoredRoom() {
        var crypt = CONTENT.room("crypt");

        var dressing = Dressings.of(crypt);

        assertEquals(crypt.name(), dressing.name());
        assertEquals(crypt.dmNotes().overview(), dressing.overview());
        assertEquals(crypt.dmNotes().sensory(), dressing.sensory());
        assertEquals(crypt.prop("sarcophagus").description(),
                dressing.propDescriptions().get("sarcophagus"));
    }

    @Test
    @DisplayName("extracting then applying is the room you started with")
    void roundTrips() {
        var crypt = CONTENT.room("crypt");

        var composed = Dressings.applyTo(crypt, Dressings.of(crypt));

        assertEquals(crypt.name(), composed.name());
        assertEquals(crypt.dmNotes().overview(), composed.dmNotes().overview());
        for (var prop : crypt.props()) {
            assertEquals(prop.description(), composed.prop(prop.id()).description(),
                    prop.id());
        }
    }

    @Test
    @DisplayName("applying a dressing replaces the prose and touches nothing structural")
    void applyingLeavesStructureAlone() {
        var crypt = CONTENT.room("crypt");
        var other = new Dressing("The Drowned Vault", "Water to the ankles.",
                "It drips.", Map.of("sarcophagus", "A stone box, slick with algae."));

        var composed = Dressings.applyTo(crypt, other);

        assertEquals("The Drowned Vault", composed.name());
        assertEquals("A stone box, slick with algae.", composed.prop("sarcophagus").description());
        assertEquals(crypt.width(), composed.width());
        assertEquals(crypt.props().size(), composed.props().size());
        assertEquals(crypt.exits(), composed.exits());
        assertEquals(crypt.prop("alcove").hidden(), composed.prop("alcove").hidden());
    }

    @Test
    @DisplayName("secrets are authored and survive a dressing that has none")
    void secretsAreNotDressing() {
        var crypt = CONTENT.room("crypt");
        var other = new Dressing("x", "y", "z", Map.of());

        var composed = Dressings.applyTo(crypt, other);

        // Spec §5a: secrets are the third thing. No model writes them and none is dropped here.
        assertEquals(crypt.dmNotes().theSarcophagus(), composed.dmNotes().theSarcophagus());
        assertEquals(crypt.dmNotes().theDoor(), composed.dmNotes().theDoor());
    }

    @Test
    @DisplayName("a prop the dressing skipped keeps no description rather than a stale one")
    void skippedPropsGetNothing() {
        var crypt = CONTENT.room("crypt");
        var sparse = new Dressing("x", "y", "z", Map.of("sarcophagus", "A box."));

        var composed = Dressings.applyTo(crypt, sparse);

        assertEquals("", composed.prop("rubble").description(),
                "an undescribed prop is still on the board, just undescribed");
    }

    @Test
    @DisplayName("RoomDressed folds, keyed by room")
    void dressingsFold() {
        var one = new Dressing("The Ashen Crypt", "a", "b", Map.of());
        var two = new Dressing("The Long Gallery", "c", "d", Map.of());

        var state = WorldState.fold(List.of(
                new Event.RoomDressed(Instant.now(), "crypt", one),
                new Event.RoomDressed(Instant.now(), "gallery", two)));

        assertEquals(one, state.dressingOf("crypt").orElseThrow());
        assertEquals(two, state.dressingOf("gallery").orElseThrow());
        assertTrue(state.dressingOf("nowhere").isEmpty());
    }

    @Test
    @DisplayName("dressing a room twice keeps the second, not both")
    void redressingReplaces() {
        var first = new Dressing("First", "a", "b", Map.of());
        var second = new Dressing("Second", "c", "d", Map.of());

        var state = WorldState.fold(List.of(
                new Event.RoomDressed(Instant.now(), "crypt", first),
                new Event.RoomDressed(Instant.now(), "crypt", second)));

        assertEquals("Second", state.dressingOf("crypt").orElseThrow().name());
    }
}
