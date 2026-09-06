package dm.content;

import dm.model.Direction;
import dm.model.PropType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code LayoutValidator} will assert for a generated dungeon, asserted by hand for the two
 * rooms M3 authors. A door that nothing answers is a room you fall out of.
 */
class TwoRoomsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("every exit is answered by one going the other way")
    void exitsAreReciprocal() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                var other = CONTENT.room(exit.toRoomId());
                var back = other.exits().stream()
                        .filter(e -> e.toRoomId().equals(roomId))
                        .filter(e -> e.direction() == exit.direction().opposite())
                        .findFirst();
                assertTrue(back.isPresent(),
                        roomId + " has a " + exit.direction() + " exit to " + other.roomId()
                                + " and nothing answers it");
            }
        }
    }

    @Test
    @DisplayName("an exit stands in the wall it names")
    void exitsAreOnTheirWall() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                switch (exit.direction()) {
                    case NORTH -> assertEquals(room.height() - 1, exit.y(), exit.id());
                    case SOUTH -> assertEquals(0, exit.y(), exit.id());
                    case EAST -> assertEquals(room.width() - 1, exit.x(), exit.id());
                    case WEST -> assertEquals(0, exit.x(), exit.id());
                }
            }
        }
    }

    @Test
    @DisplayName("a door is never in a corner, because a corner has no inside")
    void exitsAvoidCorners() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                boolean cornerX = exit.x() == 0 || exit.x() == room.width() - 1;
                boolean cornerY = exit.y() == 0 || exit.y() == room.height() - 1;
                assertFalse(cornerX && cornerY, roomId + ": " + exit.id() + " is in a corner");
            }
        }
    }

    @Test
    @DisplayName("nothing solid stands in a doorway, or on the square you land on")
    void doorwaysAndLandingsAreClear() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                assertFalse(room.isObstructed(exit.x(), exit.y()),
                        roomId + ": something solid is in " + exit.id());
                var inward = exit.inward(room.width(), room.height());
                assertFalse(room.isObstructed(inward.x(), inward.y()),
                        roomId + ": you would arrive inside a solid prop at " + inward);
            }
        }
    }

    @Test
    @DisplayName("an exit names a DOOR prop standing on its square")
    void exitsHaveDoors() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                var prop = room.prop(exit.id());
                assertEquals(PropType.DOOR, prop.type(), exit.id());
                assertEquals(exit.x(), prop.x());
                assertEquals(exit.y(), prop.y());
            }
        }
    }

    @Test
    @DisplayName("the gallery has something to find, and it is described")
    void galleryHasAHiddenThing() {
        var gallery = CONTENT.room("gallery");

        // The gate's "as you left it" referent — reveal it, leave, come back, find it revealed.
        assertFalse(gallery.hiddenPropIds().isEmpty());
        for (var id : gallery.hiddenPropIds()) {
            var prop = gallery.prop(id);
            assertNotNull(prop.revealHint(), id + " has no hint, so nothing can find it");
            assertFalse(prop.revealHint().isBlank(), id);
            assertFalse(prop.description().isBlank(), id);
        }
    }

    @Test
    @DisplayName("the gallery reads as somewhere else, not the crypt again")
    void galleryIsNotTheCryptAgain() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");

        assertNotEquals(crypt.name(), gallery.name());
        assertNotEquals(crypt.lighting(), gallery.lighting(),
                "a second room lit exactly like the first is a reskin");
        assertNotEquals(crypt.width() + "x" + crypt.height(),
                gallery.width() + "x" + gallery.height());
    }

    @Test
    @DisplayName("the crypt no longer says its north door cannot open")
    void theSealedDoorNoteIsGone() {
        var note = CONTENT.room("crypt").dmNotes().theDoor();

        assertTrue(note == null || !note.toLowerCase().contains("will not open in this session"),
                "the door opens now; a standing note that it does not is a lie the DM will "
                        + "keep telling: " + note);
    }

    @Test
    @DisplayName("the party start is where you land coming through the door")
    void startsAgreeWithArrivals() {
        var gallery = CONTENT.room("gallery");
        var south = gallery.exits().stream()
                .filter(e -> e.direction() == Direction.SOUTH)
                .findFirst().orElseThrow();

        var landing = south.inward(gallery.width(), gallery.height());
        var start = gallery.startPositions().party().getFirst();

        assertEquals(landing.x(), start.x(),
                "the fallback start and the arrival square should not disagree");
        assertEquals(landing.y(), start.y());
    }
}
