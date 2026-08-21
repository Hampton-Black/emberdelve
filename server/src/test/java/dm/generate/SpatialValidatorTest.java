package dm.generate;

import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §6: generated content is validated for spatial legality, not just enum membership.
 *
 * <p>A prop in a doorway is a valid enum and an invalid world. Every prop here has a legal type
 * and legal coordinates; what is wrong with these rooms is the arrangement.
 */
class SpatialValidatorTest {

    private static final RoomShape SHAPE = new RoomShape(
            6, 6, dm.model.FloorType.STONE, dm.model.WallType.STONE,
            dm.model.LightingPreset.TORCHLIT);
    private static final Square START = new Square(0, 0);

    private static Prop pillar(String id, int x, int y) {
        return new Prop(id, PropType.PILLAR, x, y, 0, false);
    }

    @Test
    @DisplayName("a plain room passes")
    void legalRoomPasses() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-1", 4, 4));

        assertEquals(List.of(), SpatialValidator.check(SHAPE, props, START));
    }

    @Test
    @DisplayName("two props on one square is a violation")
    void overlapRejected() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-1", 2, 2));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("2,2"), violations.get(0));
    }

    @Test
    @DisplayName("a prop off the grid is a violation")
    void offGridRejected() {
        var props = List.of(pillar("pillar-0", 6, 2));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("pillar-0"), violations.get(0));
    }

    @Test
    @DisplayName("a solid prop on the party's start square is a violation")
    void blockedStartRejected() {
        var props = List.of(pillar("pillar-0", 0, 0));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("start")), violations.toString());
    }

    @Test
    @DisplayName("a walled-off corner is a violation, because the player can never reach it")
    void unreachableRegionRejected() {
        // Fences off (5,5) behind a diagonal-proof wall of pillars.
        var props = List.of(
                pillar("pillar-0", 4, 5),
                pillar("pillar-1", 4, 4),
                pillar("pillar-2", 5, 4));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertTrue(violations.stream().anyMatch(v -> v.contains("unreachable")),
                violations.toString());
    }

    @Test
    @DisplayName("duplicate ids are a violation, because reveal_prop addresses by id")
    void duplicateIdsRejected() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-0", 3, 3));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertTrue(violations.stream().anyMatch(v -> v.contains("pillar-0")), violations.toString());
    }

    @Test
    @DisplayName("every generated room from the real kit is legal")
    void generatedRoomsAreLegal() {
        var kit = new dm.content.ContentLoader().kit("crypt");

        for (long seed = 0; seed < 200; seed++) {
            var random = new GenRandom(seed);
            var shape = ShapeGenerator.generate(random, kit);
            var props = PropPlacer.place(random, kit, shape, START);

            assertEquals(List.of(), SpatialValidator.check(shape, props, START),
                    "seed " + seed + " generated an illegal room");
        }
    }
}
