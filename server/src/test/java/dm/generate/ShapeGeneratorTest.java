package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShapeGeneratorTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("the same seed produces the same shape")
    void deterministic() {
        var kit = CONTENT.kit("crypt");

        var first = ShapeGenerator.generate(new GenRandom(123), kit);
        var second = ShapeGenerator.generate(new GenRandom(123), kit);

        assertEquals(first, second);
    }

    @Test
    @DisplayName("dimensions stay inside the kit's range")
    void withinKitRange() {
        var kit = CONTENT.kit("crypt");

        for (long seed = 0; seed < 100; seed++) {
            var shape = ShapeGenerator.generate(new GenRandom(seed), kit);

            assertTrue(shape.width() >= kit.size().min(), "too narrow at seed " + seed);
            assertTrue(shape.width() <= kit.size().max(), "too wide at seed " + seed);
            assertTrue(shape.height() >= kit.size().min(), "too short at seed " + seed);
            assertTrue(shape.height() <= kit.size().max(), "too tall at seed " + seed);
        }
    }

    @Test
    @DisplayName("every surface comes from the kit's palette")
    void surfacesComeFromKit() {
        var kit = CONTENT.kit("crypt");

        for (long seed = 0; seed < 100; seed++) {
            var shape = ShapeGenerator.generate(new GenRandom(seed), kit);

            assertTrue(kit.floors().contains(shape.floorType()));
            assertTrue(kit.walls().contains(shape.wallType()));
            assertTrue(kit.lightings().contains(shape.lighting()));
        }
    }

    @Test
    @DisplayName("different seeds produce different rooms")
    void seedsVary() {
        var kit = CONTENT.kit("crypt");

        var shapes = new java.util.HashSet<RoomShape>();
        for (long seed = 0; seed < 50; seed++) {
            shapes.add(ShapeGenerator.generate(new GenRandom(seed), kit));
        }
        assertTrue(shapes.size() > 5, "50 seeds produced only " + shapes.size() + " shapes");
    }
}
