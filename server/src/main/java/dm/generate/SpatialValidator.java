package dm.generate;

import dm.model.Prop;
import dm.model.Square;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §6. Closed enums are necessary and no longer sufficient.
 *
 * <p>Every prop the generator emits already has a legal type and legal coordinates, because the
 * kit is a closed set and the placer draws inside the bounds. What this checks is the thing a
 * type system cannot: whether the <em>arrangement</em> makes a world worth standing in. A prop
 * in a doorway is a valid enum and an invalid room, and a walled-off corner is a promise the
 * renderer makes that the engine will refuse to keep.
 *
 * <p>Returns every violation rather than throwing on the first, so a bad seed produces one
 * legible report instead of a game of whack-a-mole.
 */
public final class SpatialValidator {

    private SpatialValidator() {
    }

    public static List<String> check(RoomShape shape, List<Prop> props, Square partyStart) {
        var violations = new ArrayList<String>();
        var occupied = new HashSet<Square>();
        var ids = new HashSet<String>();

        for (var prop : props) {
            if (!ids.add(prop.id())) {
                violations.add("Duplicate prop id: " + prop.id());
            }
            if (prop.x() < 0 || prop.x() >= shape.width()
                    || prop.y() < 0 || prop.y() >= shape.height()) {
                violations.add("Prop off the grid: " + prop.id()
                        + " at " + prop.x() + "," + prop.y());
                continue;
            }
            var square = new Square(prop.x(), prop.y());
            if (!occupied.add(square)) {
                violations.add("Two props share a square at " + prop.x() + "," + prop.y());
            }
            if (square.equals(partyStart) && prop.type().blocksMovement()) {
                violations.add("A solid prop stands on the party start square at "
                        + prop.x() + "," + prop.y());
            }
        }

        violations.addAll(unreachable(shape, props, partyStart));
        return List.copyOf(violations);
    }

    /**
     * Every open square must be walkable from the party's start.
     *
     * <p>Chebyshev flood fill, matching {@code Entity.isAdjacentTo} and the movement rules —
     * two distance metrics in one game is how "why can it not walk there" bugs start.
     */
    private static List<String> unreachable(RoomShape shape, List<Prop> props, Square partyStart) {
        var blocked = new HashSet<Square>();
        for (var prop : props) {
            if (prop.type().blocksMovement()) {
                blocked.add(new Square(prop.x(), prop.y()));
            }
        }

        var reached = new HashSet<Square>();
        var queue = new ArrayDeque<Square>();
        if (!blocked.contains(partyStart)) {
            reached.add(partyStart);
            queue.add(partyStart);
        }

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var next = new Square(current.x() + dx, current.y() + dy);
                    if (next.x() < 0 || next.x() >= shape.width()
                            || next.y() < 0 || next.y() >= shape.height()) {
                        continue;
                    }
                    if (blocked.contains(next) || !reached.add(next)) {
                        continue;
                    }
                    queue.add(next);
                }
            }
        }

        var violations = new ArrayList<String>();
        for (int x = 0; x < shape.width(); x++) {
            for (int y = 0; y < shape.height(); y++) {
                var square = new Square(x, y);
                if (!blocked.contains(square) && !reached.contains(square)) {
                    violations.add("Square is unreachable from the party start: " + x + "," + y);
                }
            }
        }
        return violations;
    }
}
