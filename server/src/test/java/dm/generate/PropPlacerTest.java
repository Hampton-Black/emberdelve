package dm.generate;

import dm.content.ContentLoader;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropPlacerTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final Square START = new Square(1, 1);

    private static List<Prop> place(long seed) {
        var kit = CONTENT.kit("crypt");
        var random = new GenRandom(seed);
        var shape = ShapeGenerator.generate(random, kit);
        return PropPlacer.place(random, kit, shape, START);
    }

    @Test
    @DisplayName("the same seed places the same props in the same squares")
    void deterministic() {
        assertEquals(place(55), place(55));
    }

    @Test
    @DisplayName("no two props share a square")
    void noOverlap() {
        for (long seed = 0; seed < 100; seed++) {
            var squares = new HashSet<Square>();
            for (var prop : place(seed)) {
                assertTrue(squares.add(new Square(prop.x(), prop.y())),
                        "two props share a square at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("nothing solid is placed on the party's starting square")
    void startSquareIsClear() {
        for (long seed = 0; seed < 100; seed++) {
            for (var prop : place(seed)) {
                boolean onStart = prop.x() == START.x() && prop.y() == START.y();
                assertFalse(onStart && prop.type().blocksMovement(),
                        "a solid prop stands on the start square at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("every prop stands inside the room, never in a wall")
    void insideTheRoom() {
        var kit = CONTENT.kit("crypt");
        for (long seed = 0; seed < 100; seed++) {
            var random = new GenRandom(seed);
            var shape = ShapeGenerator.generate(random, kit);
            for (var prop : PropPlacer.place(random, kit, shape, START)) {
                assertTrue(prop.x() >= 0 && prop.x() < shape.width(), "off-grid x at seed " + seed);
                assertTrue(prop.y() >= 0 && prop.y() < shape.height(), "off-grid y at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("counts respect the kit, and a unique prop appears at most once")
    void respectsKitCounts() {
        var kit = CONTENT.kit("crypt");
        for (long seed = 0; seed < 100; seed++) {
            var props = place(seed);
            for (var entry : kit.props()) {
                long count = props.stream().filter(p -> p.type() == entry.type()).count();
                assertTrue(count >= entry.minCount(),
                        entry.type() + " below minimum at seed " + seed);
                assertTrue(count <= entry.maxCount(),
                        entry.type() + " above maximum at seed " + seed);
                if (entry.unique()) {
                    assertTrue(count <= 1, entry.type() + " is unique but appeared " + count);
                }
            }
        }
    }

    @Test
    @DisplayName("ids are unique, because reveal_prop addresses props by id")
    void idsAreUnique() {
        for (long seed = 0; seed < 100; seed++) {
            var ids = new HashSet<String>();
            for (var prop : place(seed)) {
                assertTrue(ids.add(prop.id()), "duplicate prop id at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("the sarcophagus is always placed, since M0's script opens one")
    void sarcophagusAlwaysPresent() {
        for (long seed = 0; seed < 100; seed++) {
            assertTrue(place(seed).stream().anyMatch(p -> p.type() == PropType.SARCOPHAGUS),
                    "no sarcophagus at seed " + seed);
        }
    }

    // ---- Placement affinity ----
    //
    // Uniform random scatter put objects on a floor; it did not arrange a room. Each type now
    // has a rule about where it belongs, and these are those rules. Measured before the change:
    // 189 alcoves across 200 seeds, 61 of them against a wall — so two thirds of the recesses
    // this game calls "cut into the wall" were standing in open floor.

    /** Shape and props together, since every affinity is relative to the room's dimensions. */
    private record Room(RoomShape shape, List<Prop> props) {
    }

    private static Room room(long seed, Square start) {
        var kit = CONTENT.kit("crypt");
        var random = new GenRandom(seed);
        var shape = ShapeGenerator.generate(random, kit);
        return new Room(shape, PropPlacer.place(random, kit, shape, start));
    }

    private static boolean onWall(RoomShape shape, Prop prop) {
        return prop.x() == 0 || prop.y() == 0
                || prop.x() == shape.width() - 1 || prop.y() == shape.height() - 1;
    }

    @Test
    @DisplayName("an alcove is cut into a wall, never left standing in open floor")
    void alcovesAreCutIntoWalls() {
        for (long seed = 0; seed < 100; seed++) {
            var room = room(seed, START);
            for (var prop : room.props()) {
                if (prop.type() != PropType.ALCOVE) continue;
                assertTrue(onWall(room.shape(), prop),
                        "alcove adrift at (" + prop.x() + "," + prop.y() + ") on seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("a brazier stands against a wall, so its light falls inward across the floor")
    void braziersStandAgainstWalls() {
        for (long seed = 0; seed < 100; seed++) {
            var room = room(seed, START);
            for (var prop : room.props()) {
                if (prop.type() != PropType.BRAZIER) continue;
                assertTrue(onWall(room.shape(), prop),
                        "brazier adrift at (" + prop.x() + "," + prop.y() + ") on seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("pillars stand one square off the wall, the way a colonnade does")
    void pillarsStandOneSquareOffTheWall() {
        for (long seed = 0; seed < 100; seed++) {
            var room = room(seed, START);
            for (var prop : room.props()) {
                if (prop.type() != PropType.PILLAR) continue;
                boolean inset = prop.x() == 1 || prop.y() == 1
                        || prop.x() == room.shape().width() - 2
                        || prop.y() == room.shape().height() - 2;
                assertTrue(inset && !onWall(room.shape(), prop),
                        "pillar at (" + prop.x() + "," + prop.y() + ") on seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("the sarcophagus sits on the room's axis, in the half away from the party")
    void sarcophagusSitsOnTheAxisFarFromTheParty() {
        for (long seed = 0; seed < 100; seed++) {
            final long at = seed;
            var room = room(seed, new Square(3, 0));
            var tomb = room.props().stream()
                    .filter(p -> p.type() == PropType.SARCOPHAGUS)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no sarcophagus at seed " + at));

            assertEquals(room.shape().width() / 2, tomb.x(), "off axis at seed " + seed);
            assertTrue(tomb.y() > room.shape().height() / 2,
                    "sarcophagus in the party's own half at seed " + seed);
        }
    }

    @Test
    @DisplayName("rubble piles against a wall or against other rubble, because debris collects")
    void rubbleCollects() {
        for (long seed = 0; seed < 100; seed++) {
            var room = room(seed, START);
            var rubble = room.props().stream()
                    .filter(p -> p.type() == PropType.RUBBLE)
                    .toList();

            for (var prop : rubble) {
                boolean touching = rubble.stream().anyMatch(other -> other != prop
                        && Math.abs(other.x() - prop.x()) <= 1
                        && Math.abs(other.y() - prop.y()) <= 1);
                assertTrue(onWall(room.shape(), prop) || touching,
                        "lone rubble mid-floor at (" + prop.x() + "," + prop.y()
                                + ") on seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("a prop against a wall faces into the room, not into the stone behind it")
    void wallPropsFaceInward() {
        for (long seed = 0; seed < 100; seed++) {
            var room = room(seed, START);
            for (var prop : room.props()) {
                if (!onWall(room.shape(), prop)) continue;

                // The authored crypt is the reference: its north-wall door is 180 and its
                // east-side alcove is 270, which puts south at 0 and west at 90.
                Integer expected = null;
                if (prop.y() == 0) expected = 0;
                else if (prop.x() == 0) expected = 90;
                else if (prop.y() == room.shape().height() - 1) expected = 180;
                else if (prop.x() == room.shape().width() - 1) expected = 270;

                assertEquals(expected, prop.rotation(),
                        prop.type() + " at (" + prop.x() + "," + prop.y()
                                + ") faces the wall on seed " + seed);
            }
        }
    }
}
