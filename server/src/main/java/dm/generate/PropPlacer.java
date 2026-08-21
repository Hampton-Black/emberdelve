package dm.generate;

import dm.content.KitDefinition;
import dm.model.Prop;
import dm.model.Square;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scatters the kit's props across a room's floor.
 *
 * <p>Placement is rejection sampling rather than anything cleverer: pick a square, take it if it
 * is free, try again if it is not. With a dozen props on a hundred squares the collision rate is
 * low enough that this terminates quickly, and a bounded attempt count means it terminates at
 * all. Something smarter — symmetry, clustering, rooms that read as designed — is exactly the
 * kind of improvement to make once there is a generated room to look at and judge, and exactly
 * the kind to avoid inventing before then.
 *
 * <p>The party's starting square is kept clear of anything solid. A player who spawns inside a
 * pillar is a bug the renderer will show and the engine will not.
 */
public final class PropPlacer {

    /** Enough tries that a full room gives up rather than spinning. */
    private static final int ATTEMPTS_PER_PROP = 40;

    private PropPlacer() {
    }

    public static List<Prop> place(GenRandom random, KitDefinition kit, RoomShape shape,
                                   Square partyStart) {
        var placed = new ArrayList<Prop>();
        var taken = new HashSet<Square>();

        for (var entry : kit.props()) {
            int max = entry.unique() ? Math.min(1, entry.maxCount()) : entry.maxCount();
            int count = random.between(entry.minCount(), max);

            for (int i = 0; i < count; i++) {
                var square = findFreeSquare(random, shape, taken, partyStart, entry);
                if (square == null) {
                    continue;
                }
                taken.add(square);
                placed.add(new Prop(
                        entry.type().name().toLowerCase() + "-" + i,
                        entry.type(),
                        square.x(),
                        square.y(),
                        random.pick(List.of(0, 90, 180, 270)),
                        false));
            }
        }
        return List.copyOf(placed);
    }

    private static Square findFreeSquare(GenRandom random, RoomShape shape, Set<Square> taken,
                                         Square partyStart, KitDefinition.PropEntry entry) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_PROP; attempt++) {
            var square = new Square(
                    random.between(0, shape.width() - 1),
                    random.between(0, shape.height() - 1));

            if (taken.contains(square)) {
                continue;
            }
            if (square.equals(partyStart) && entry.type().blocksMovement()) {
                continue;
            }
            return square;
        }
        return null;
    }
}
