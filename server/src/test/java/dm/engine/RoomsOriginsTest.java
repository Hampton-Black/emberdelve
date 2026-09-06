package dm.engine;

import dm.content.ContentLoader;
import dm.model.Direction;
import dm.model.Exit;
import dm.model.RoomOrigin;
import dm.model.RoomOutline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dm.engine.SyntheticRooms.room;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Placing every room in one world frame, anchored at the entrance.
 *
 * <p>{@link Rooms#origins()} walks the exit graph breadth-first from {@link Rooms#first()},
 * composing {@link RoomOutline#beside} offsets — the arithmetic itself stays tested in
 * {@code RoomOutlineTest}. This only tests the composition and the graph walk.
 */
class RoomsOriginsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("a single room is at the entrance")
    void singleRoomIsAtTheOrigin() {
        var rooms = Rooms.of(room("solo", 4, 4));

        var origins = rooms.origins();

        assertEquals(Map.of("solo", new RoomOrigin(0, 0)), origins);
    }

    @Test
    @DisplayName("crypt/gallery origins match what RoomOutlineTest asserts for one hop")
    void oneHopMatchesRoomOutline() {
        var rooms = Rooms.authored(CONTENT, "crypt", "gallery");
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");
        var north = crypt.exits().getFirst();
        var expected = RoomOutline.beside(crypt, north, gallery);

        var origins = rooms.origins();

        assertEquals(new RoomOrigin(0, 0), origins.get("crypt"));
        assertEquals(new RoomOrigin(expected.offsetX(), expected.offsetZ()),
                origins.get("gallery"));
    }

    @Test
    @DisplayName("a three-room chain composes two hops of offset")
    void threeRoomChainComposesOffsets() {
        var a = room("a", 4, 4, new Exit("a-to-b", 3, 1, Direction.EAST, "b"));
        var b = room("b", 6, 4,
                new Exit("b-to-a", 0, 1, Direction.WEST, "a"),
                new Exit("b-to-c", 5, 2, Direction.EAST, "c"));
        var c = room("c", 4, 4, new Exit("c-to-b", 0, 2, Direction.WEST, "b"));
        var rooms = Rooms.of(a, b, c);

        var origins = rooms.origins();

        var ab = RoomOutline.beside(a, a.exits().getFirst(), b);
        var bc = RoomOutline.beside(b, b.exits().get(1), c);
        assertEquals(new RoomOrigin(0, 0), origins.get("a"));
        assertEquals(new RoomOrigin(ab.offsetX(), ab.offsetZ()), origins.get("b"));
        assertEquals(new RoomOrigin(ab.offsetX() + bc.offsetX(), ab.offsetZ() + bc.offsetZ()),
                origins.get("c"), "c is placed relative to b, which is placed relative to a");
    }

    @Test
    @DisplayName("a cycle places every room once, first path winning")
    void cyclePlacesEveryRoomOnce() {
        var a = room("a", 4, 4,
                new Exit("a-to-b", 3, 1, Direction.EAST, "b"),
                new Exit("a-to-c", 0, 2, Direction.WEST, "c"));
        var b = room("b", 4, 4,
                new Exit("b-to-a", 0, 1, Direction.WEST, "a"),
                new Exit("b-to-c", 1, 0, Direction.SOUTH, "c"));
        var c = room("c", 4, 4,
                new Exit("c-to-a", 3, 2, Direction.EAST, "a"),
                new Exit("c-to-b", 1, 3, Direction.NORTH, "b"));
        var rooms = Rooms.of(a, b, c);

        var origins = rooms.origins();

        assertEquals(3, origins.size(), "every room placed exactly once");
        assertEquals(new RoomOrigin(0, 0), origins.get("a"));
        // B and C are both direct neighbours of A. The B-C edge is a second path to an
        // already-placed room and must be ignored, not reconciled.
        var ab = RoomOutline.beside(a, a.exits().getFirst(), b);
        var ac = RoomOutline.beside(a, a.exits().get(1), c);
        assertEquals(new RoomOrigin(ab.offsetX(), ab.offsetZ()), origins.get("b"));
        assertEquals(new RoomOrigin(ac.offsetX(), ac.offsetZ()), origins.get("c"));
    }

    @Test
    @DisplayName("a room reachable only through a one-way exit is not placed")
    void oneWayExitLeavesTheDestinationUnplaced() {
        var a = room("a", 4, 4, new Exit("a-to-b", 3, 1, Direction.EAST, "b"));
        // b has no answering door back to a.
        var b = room("b", 4, 4);
        var rooms = Rooms.of(a, b);

        var origins = rooms.origins();

        assertEquals(Map.of("a", new RoomOrigin(0, 0)), origins);
    }
}
