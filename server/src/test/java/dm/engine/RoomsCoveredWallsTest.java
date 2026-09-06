package dm.engine;

import dm.content.ContentLoader;
import dm.model.Direction;
import dm.model.Exit;
import dm.model.WallSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static dm.engine.SyntheticRooms.room;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Which perimeter segments a room leaves to the room next door.
 *
 * <p>Two rooms joined by a door share the wall it is in — the server places them so the doors
 * line up, which puts both perimeters on one plane. {@link Rooms#coveredWalls()} decides, once
 * and without a renderer, which of the two draws it.
 */
class RoomsCoveredWallsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("a room with nothing next to it draws its whole perimeter")
    void aLoneRoomCoversNothing() {
        var rooms = Rooms.of(room("solo", 4, 4));

        assertEquals(Set.of(), rooms.coveredWalls().get("solo"));
    }

    @Test
    @DisplayName("the crypt keeps the north segments that overhang the gallery")
    void theWiderEntranceKeepsItsOverhang() {
        // The crypt (12 wide) is the entrance; the gallery (10 wide) sits north of it, so the
        // crypt's north wall and the gallery's south wall are one plane. The crypt is nearer
        // the entrance and owns it — whichever room the party is standing in.
        var rooms = Rooms.authored(CONTENT, "crypt", "gallery");

        var covered = rooms.coveredWalls();

        assertEquals(Set.of(), covered.get("crypt"),
                "no hole at either north corner: the crypt draws its whole perimeter");
        assertEquals(southRun(10), covered.get("gallery"),
                "the gallery leaves the whole shared run to the crypt");
    }

    @Test
    @DisplayName("a room wider than the one it opens off keeps the ends that overhang it")
    void theOverhangIsNotAWholeRunOmission() {
        // The entrance is 4 wide and the room beyond is 8, so two segments of that room's south
        // wall stick out past the shared run at each end. Dropping the whole run — a direction
        // match rather than an interval overlap — is what leaves a hole at each corner.
        var vault = room("vault", 4, 4, new Exit("up", 1, 3, Direction.NORTH, "wide"));
        var wide = room("wide", 8, 4, new Exit("down", 3, 0, Direction.SOUTH, "vault"));

        var covered = Rooms.of(vault, wide).coveredWalls();

        assertEquals(Set.of(), covered.get("vault"));
        assertEquals(Set.of(
                new WallSegment(2, 0, Direction.SOUTH),
                new WallSegment(3, 0, Direction.SOUTH),
                new WallSegment(4, 0, Direction.SOUTH),
                new WallSegment(5, 0, Direction.SOUTH)), covered.get("wide"),
                "only the four segments the entrance's north wall actually covers");
    }

    @Test
    @DisplayName("rooms whose widths differ in parity still meet on one lattice")
    void aParityMismatchStillLandsOnWholeSegments() {
        // The reason the client omitted whole runs was that two rooms of different parity sit
        // half a square out of step. Their segment CENTRES do; their BOUNDARIES do not, because
        // beside() offsets by half the width difference as well. So no segment is ever half
        // covered, and the overhang comes out whole: three segments kept, four given up.
        var vault = room("vault", 4, 4, new Exit("up", 1, 3, Direction.NORTH, "odd"));
        var odd = room("odd", 7, 4, new Exit("down", 3, 0, Direction.SOUTH, "vault"));
        var rooms = Rooms.of(vault, odd);
        assertEquals(-0.5, rooms.origins().get("odd").x(), "half a square out of step");

        var covered = rooms.coveredWalls();

        assertEquals(Set.of(), covered.get("vault"));
        assertEquals(southRunFrom(2, 4), covered.get("odd"),
                "whole segments either way: nothing straddles the entrance's wall");
    }

    @Test
    @DisplayName("a middle room leaves the plane behind it and owns the plane ahead")
    void aMiddleRoomIsRightOnBothSides() {
        // Three rooms west to east, all four squares deep, named so that roomId order disagrees
        // with hop order: the rule is distance from the entrance, and roomId only breaks a tie.
        // With the party in b-start, a-middle and c-far are both rooms nobody is standing in and
        // the plane between them still has to be drawn exactly once.
        var start = room("b-start", 4, 4, new Exit("out", 3, 1, Direction.EAST, "a-middle"));
        var middle = room("a-middle", 6, 4,
                new Exit("back", 0, 1, Direction.WEST, "b-start"),
                new Exit("on", 5, 2, Direction.EAST, "c-far"));
        var far = room("c-far", 4, 4, new Exit("back", 0, 2, Direction.WEST, "a-middle"));

        var covered = Rooms.of(start, middle, far).coveredWalls();

        assertEquals(Set.of(), covered.get("b-start"));
        assertEquals(westRun(4), covered.get("a-middle"),
                "the middle room leaves its west wall to the entrance and still draws its east");
        assertEquals(westRun(4), covered.get("c-far"));
    }

    @Test
    @DisplayName("two rooms the same distance from the entrance settle the plane by roomId")
    void equalDistanceTiesBreakByRoomId() {
        // Both wings open off the hall's north wall and overlap each other past its corners, so
        // the plane is contested three ways. z-wing is discovered first; a-wing wins on roomId.
        var hall = room("hall", 8, 4,
                new Exit("hall-to-z", 1, 3, Direction.NORTH, "z-wing"),
                new Exit("hall-to-a", 6, 3, Direction.NORTH, "a-wing"));
        var zWing = room("z-wing", 12, 4, new Exit("z-to-hall", 1, 0, Direction.SOUTH, "hall"));
        var aWing = room("a-wing", 12, 4, new Exit("a-to-hall", 1, 0, Direction.SOUTH, "hall"));

        var covered = Rooms.of(hall, zWing, aWing).coveredWalls();

        assertEquals(Set.of(), southOnly(covered.get("hall")));
        assertEquals(southRun(12), southOnly(covered.get("z-wing")),
                "z-wing draws none of the contested plane: the hall and a-wing cover all of it");
        assertEquals(southRunFrom(0, 3), southOnly(covered.get("a-wing")),
                "a-wing leaves only the three segments the hall itself covers");
    }

    /** Every segment of a room's south wall, for a room this wide. */
    private static Set<WallSegment> southRun(int width) {
        return southRunFrom(0, width);
    }

    private static Set<WallSegment> southRunFrom(int firstX, int count) {
        var run = new LinkedHashSet<WallSegment>();
        for (var x = firstX; x < firstX + count; x++) {
            run.add(new WallSegment(x, 0, Direction.SOUTH));
        }
        return run;
    }

    /** Every segment of a room's west wall, for a room this deep. */
    private static Set<WallSegment> westRun(int height) {
        var run = new LinkedHashSet<WallSegment>();
        for (var y = 0; y < height; y++) {
            run.add(new WallSegment(0, y, Direction.WEST));
        }
        return run;
    }

    /** The wings overlap each other bodily, so only the contested plane is worth asserting on. */
    private static Set<WallSegment> southOnly(Set<WallSegment> segments) {
        return segments.stream()
                .filter(s -> s.direction() == Direction.SOUTH)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
