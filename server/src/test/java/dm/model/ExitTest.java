package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a door stands and where you land coming through it.
 *
 * <p>{@code inward} is the whole reason this type has behaviour. Landing in the doorway means
 * landing on the trigger that sent you there, and the party would bounce straight back out.
 */
class ExitTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("opposite round-trips, and the offsets agree with it")
    void directionsAreSymmetric() {
        for (var direction : Direction.values()) {
            assertEquals(direction, direction.opposite().opposite());
            assertEquals(0, direction.dx() + direction.opposite().dx());
            assertEquals(0, direction.dy() + direction.opposite().dy());
        }
    }

    @Test
    @DisplayName("north is increasing y, matching the grid block the DM is shown")
    void northIsIncreasingY() {
        // "axes: x eastward, y northward" — DmService.worldState. Two conventions in one
        // game is how "why did it walk the wrong way" bugs start.
        assertEquals(1, Direction.NORTH.dy());
        assertEquals(0, Direction.NORTH.dx());
        assertEquals(1, Direction.EAST.dx());
        assertEquals(0, Direction.EAST.dy());
    }

    @Test
    @DisplayName("the square inside a door is one step in, and on the grid")
    void inwardIsOneStepInside() {
        var north = new Exit("door-north", 6, 11, Direction.NORTH, "gallery");
        assertEquals(new Square(6, 10), north.inward(12, 12));

        var south = new Exit("door-south", 6, 0, Direction.SOUTH, "crypt");
        assertEquals(new Square(6, 1), south.inward(12, 12));

        var east = new Exit("door-east", 11, 5, Direction.EAST, "gallery");
        assertEquals(new Square(10, 5), east.inward(12, 12));

        var west = new Exit("door-west", 0, 5, Direction.WEST, "gallery");
        assertEquals(new Square(1, 5), west.inward(12, 12));
    }

    @Test
    @DisplayName("inward never leaves the grid, even in a one-square room")
    void inwardIsClamped() {
        var exit = new Exit("door-north", 0, 0, Direction.NORTH, "gallery");
        var inward = exit.inward(1, 1);
        assertEquals(new Square(0, 0), inward);
    }

    @Test
    @DisplayName("the crypt's north door is an exit, and it is found by its square")
    void cryptCarriesItsExit() {
        var crypt = CONTENT.room("crypt");

        assertEquals(2, crypt.exits().size());
        var exit = crypt.exits().getFirst();
        assertEquals("door-north", exit.id());
        assertEquals(Direction.NORTH, exit.direction());
        assertEquals(11, exit.y(), "the north wall of a 12-high room");
        assertFalse(exit.wayOut());

        var wayOut = crypt.exits().getLast();
        assertEquals("stair-south", wayOut.id());
        assertEquals(Direction.SOUTH, wayOut.direction());
        assertEquals(0, wayOut.y(), "the south wall of a 12-high room");
        assertTrue(wayOut.wayOut());

        assertEquals(exit, crypt.exitAt(exit.x(), exit.y()).orElseThrow());
        assertTrue(crypt.exitAt(0, 0).isEmpty());
    }

    @Test
    @DisplayName("an exit's id names a prop that is actually in the room")
    void exitsNameRealProps() {
        var crypt = CONTENT.room("crypt");
        for (var exit : crypt.exits()) {
            var prop = crypt.prop(exit.id());
            assertEquals(PropType.DOOR, prop.type());
            assertEquals(exit.x(), prop.x());
            assertEquals(exit.y(), prop.y());
        }
    }
}
