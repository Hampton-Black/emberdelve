package dm.generate;

import dm.content.KitDefinition;

/**
 * Chooses a room's dimensions and surfaces from the kit's palette.
 *
 * <p>Width and height are drawn independently, so rooms are rectangles rather than squares —
 * a room that is always as deep as it is wide reads as a generated grid, which is the exact
 * impression this milestone is trying to avoid.
 */
public final class ShapeGenerator {

    private ShapeGenerator() {
    }

    public static RoomShape generate(GenRandom random, KitDefinition kit) {
        return new RoomShape(
                random.between(kit.size().min(), kit.size().max()),
                random.between(kit.size().min(), kit.size().max()),
                random.pick(kit.floors()),
                random.pick(kit.walls()),
                random.pick(kit.lightings()));
    }
}
