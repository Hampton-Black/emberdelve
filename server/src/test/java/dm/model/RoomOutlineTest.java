package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a neighbour sits, in the client's world space.
 *
 * <p>Computed here rather than in GDScript so there is one implementation, testable without a
 * renderer — and because the generator plan's packing will need the same arithmetic at dungeon
 * scale.
 */
class RoomOutlineTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    /** The client's own mapping, so the test asserts against what will actually be drawn. */
    private static double[] worldOf(int x, int y, int width, int height) {
        return new double[] {x - width / 2.0 + 0.5, -(y - height / 2.0 + 0.5)};
    }

    @Test
    @DisplayName("the neighbour's answering door sits one step beyond the door you are looking at")
    void doorsLineUp() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");
        var north = crypt.exits().getFirst();

        var outline = RoomOutline.beside(crypt, north, gallery);

        var mine = worldOf(north.x(), north.y(), crypt.width(), crypt.height());
        var back = gallery.exits().getFirst();
        var theirs = worldOf(back.x(), back.y(), gallery.width(), gallery.height());

        // One square further north is one less z, because the client's z runs south.
        assertEquals(mine[0], outline.offsetX() + theirs[0], 1e-9);
        assertEquals(mine[1] - 1.0, outline.offsetZ() + theirs[1], 1e-9);
    }

    @Test
    @DisplayName("the outline carries what it takes to draw a floor and walls, and nothing else")
    void outlineIsGeometryOnly() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");

        var outline = RoomOutline.beside(crypt, crypt.exits().getFirst(), gallery);

        assertEquals("gallery", outline.roomId());
        assertEquals(gallery.width(), outline.width());
        assertEquals(gallery.height(), outline.height());
        assertEquals(gallery.floorType(), outline.floorType());
        assertEquals(gallery.wallType(), outline.wallType());

        // No lighting, no props, no entities. A neighbour the DM is never told about must not
        // be a neighbour the client can render the contents of. Spec §8b.
        String json = dm.wire.Json.MAPPER.valueToTree(outline).toString();
        assertFalse(json.contains("lighting"), json);
        assertFalse(json.contains("props"), json);
        assertFalse(json.contains("entities"), json);
    }

    @Test
    @DisplayName("a one-way exit has no answering door and produces no outline")
    void oneWayExitsAreNotDrawn() {
        var crypt = CONTENT.room("crypt");
        var noWayBack = new dm.content.RoomDefinition(
                "void", "The Void", 4, 4, FloorType.STONE, WallType.STONE,
                LightingPreset.DARK, java.util.List.of(), java.util.List.of(),
                new dm.content.RoomDefinition.StartPositions(
                        java.util.List.of(new dm.content.RoomDefinition.Point(1, 1)),
                        new dm.content.RoomDefinition.Point(2, 2)),
                new dm.content.RoomDefinition.DmNotes("o", "s", null, null, null));

        // Without a door to line up on there is no defensible place to put it, and a guessed
        // one would be a room drawn through a wall.
        assertNull(RoomOutline.beside(crypt, crypt.exits().getFirst(), noWayBack));
    }
}
