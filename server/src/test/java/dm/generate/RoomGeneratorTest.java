package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomGeneratorTest {

    private static final RoomGenerator GENERATOR = new RoomGenerator(new ContentLoader());

    @Test
    @DisplayName("the same seed generates the same room, every time")
    void deterministic() {
        assertEquals(GENERATOR.generate("crypt", 99), GENERATOR.generate("crypt", 99));
    }

    @Test
    @DisplayName("every generated room is spatially legal")
    void alwaysLegal() {
        for (long seed = 0; seed < 200; seed++) {
            var room = GENERATOR.generate("crypt", seed);

            assertEquals(List.of(),
                    SpatialValidator.check(room.shape(), room.props(), room.partyStart()),
                    "seed " + seed + " escaped validation");
        }
    }

    @Test
    @DisplayName("the party and the goblin do not start on the same square")
    void startsAreDistinct() {
        for (long seed = 0; seed < 200; seed++) {
            var room = GENERATOR.generate("crypt", seed);

            assertNotEquals(room.partyStart(), room.goblinSpawn(), "seed " + seed);
        }
    }

    @Test
    @DisplayName("it converts to a RoomDefinition the engine already understands")
    void convertsToRoomDefinition() {
        var room = GENERATOR.generate("crypt", 4);
        var definition = room.toRoomDefinition();

        assertEquals(room.roomId(), definition.roomId());
        assertEquals(room.shape().width(), definition.width());
        assertEquals(room.props().size(), definition.props().size());
        assertNotNull(definition.dmNotes());
        assertEquals(1, definition.startPositions().party().size());
    }

    @Test
    @DisplayName("obstruction survives the conversion, so combat legality still works")
    void obstructionSurvives() {
        var room = GENERATOR.generate("crypt", 11);
        var definition = room.toRoomDefinition();

        var solid = room.props().stream()
                .filter(p -> p.type().blocksMovement())
                .findFirst()
                .orElseThrow();

        assertTrue(definition.isObstructed(solid.x(), solid.y()));
        assertFalse(definition.isObstructed(room.partyStart().x(), room.partyStart().y()));
    }
}
