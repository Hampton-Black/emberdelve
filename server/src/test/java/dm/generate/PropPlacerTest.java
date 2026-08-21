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
}
