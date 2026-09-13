package dm.generate;

import dm.content.KitDefinition;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Arranges the kit's props into a room.
 *
 * <p>This started as uniform rejection sampling — pick a square, take it if it is free — and
 * the rooms it produced were legal and lifeless: objects sprinkled on a floor rather than a
 * place someone built. The measurement that settled it was the alcove. Two thirds of them
 * (128 of 189 across 200 seeds) stood in open floor, even though {@link PropType} defines an
 * alcove as a recess in a wall and lets creatures walk through it on exactly that grounds.
 *
 * <p>So each type now says where it belongs, and placement draws from the squares that satisfy
 * it. The rules are deliberately architectural rather than decorative: braziers on the walls
 * throw their light inward across the floor, pillars one square off the wall read as a
 * colonnade instead of two lost cylinders, debris collects rather than scattering, and the
 * sarcophagus stands on the room's axis in the far half — which is exactly where the
 * hand-authored crypt puts it, facing a party that enters on the same column.
 *
 * <p>Candidates are enumerated rather than sampled. Rejection sampling against a constrained
 * affinity can fail by bad luck and silently drop a prop; enumerating every legal square and
 * choosing among them cannot, which is what lets the sarcophagus be guaranteed.
 */
public final class PropPlacer {

    private PropPlacer() {
    }

    /** Where a type of thing belongs in a room. */
    private enum Affinity {
        /** Against a wall: recesses, and anything that should throw light inward. */
        WALL,
        /** One square off the wall, so a run of them reads as a colonnade. */
        OFF_WALL,
        /** On the room's centre axis, in the half away from the party. */
        AXIS_FAR,
        /** Touching another of its own kind, because debris collects in piles. */
        CLUSTER,
    }

    private static Affinity affinityOf(PropType type) {
        return switch (type) {
            // The door and the alcove are both cut into a wall; the brazier stands against one.
            case ALCOVE, DOOR, BRAZIER -> Affinity.WALL;
            case PILLAR -> Affinity.OFF_WALL;
            case SARCOPHAGUS -> Affinity.AXIS_FAR;
            case RUBBLE -> Affinity.CLUSTER;
        };
    }

    public static List<Prop> place(GenRandom random, KitDefinition kit, RoomShape shape,
                                   Square partyStart) {
        var placed = new ArrayList<Prop>();
        var taken = new HashSet<Square>();

        for (var entry : kit.props()) {
            int max = entry.unique() ? Math.min(1, entry.maxCount()) : entry.maxCount();
            int count = random.between(entry.minCount(), max);

            for (int i = 0; i < count; i++) {
                var square = choose(random, shape, taken, partyStart, entry.type(), placed);
                if (square == null) {
                    continue;
                }
                taken.add(square);
                placed.add(new Prop(
                        entry.type().name().toLowerCase() + "-" + i,
                        entry.type(),
                        square.x(),
                        square.y(),
                        facing(random, shape, square, partyStart, entry.type()),
                        false,
                        java.util.List.of()));
            }
        }
        return List.copyOf(placed);
    }

    /**
     * A free square that satisfies the type's affinity, or any free square if none does.
     *
     * <p>The fallback matters in small rooms, where the wall ring can fill up. A prop in a
     * slightly wrong place beats a prop that silently never appeared — and the kit's minimum
     * counts are a promise the generator has to keep.
     */
    private static Square choose(GenRandom random, RoomShape shape, Set<Square> taken,
                                 Square partyStart, PropType type, List<Prop> placed) {
        var preferred = free(candidates(shape, partyStart, type, placed), taken, partyStart, type);
        var square = firstWalkable(random, shape, partyStart, type, placed, preferred);
        if (square != null) {
            return square;
        }
        var anywhere = free(allSquares(shape), taken, partyStart, type);
        return firstWalkable(random, shape, partyStart, type, placed, anywhere);
    }

    /**
     * The first option that does not wall a square off, starting from a random one.
     *
     * <p>Affinity made this necessary. Scattered props almost never sealed anything, but props
     * that hug walls do: a brazier either side of a square and a pillar on the inside of each
     * is a legal arrangement of four legal objects and a square nobody can ever stand on. Seed
     * 143 found exactly that, at (5,0).
     *
     * <p>{@code RoomGenerator} would have reseeded past it, but a placer that leans on reseeding
     * for correctness is a placer whose output you cannot reason about on its own. Asking the
     * validator before committing a square costs one flood fill, and stranding is rare enough
     * that the first candidate almost always answers.
     */
    private static Square firstWalkable(GenRandom random, RoomShape shape, Square partyStart,
                                        PropType type, List<Prop> placed, List<Square> options) {
        if (options.isEmpty()) {
            return null;
        }
        int start = random.between(0, options.size() - 1);
        for (int i = 0; i < options.size(); i++) {
            var candidate = options.get((start + i) % options.size());
            if (!type.blocksMovement()) {
                return candidate;
            }
            var trial = new ArrayList<>(placed);
            trial.add(new Prop("trial", type, candidate.x(), candidate.y(), 0, false, java.util.List.of()));
            if (SpatialValidator.check(shape, trial, partyStart).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Square> free(List<Square> squares, Set<Square> taken, Square partyStart,
                                     PropType type) {
        var out = new ArrayList<Square>();
        for (var square : squares) {
            if (taken.contains(square)) {
                continue;
            }
            // A player who spawns inside a pillar is a bug the renderer shows and the engine
            // does not. Things you can walk through may share the square.
            if (square.equals(partyStart) && type.blocksMovement()) {
                continue;
            }
            out.add(square);
        }
        return out;
    }

    private static List<Square> candidates(RoomShape shape, Square partyStart, PropType type,
                                           List<Prop> placed) {
        int w = shape.width();
        int h = shape.height();

        return switch (affinityOf(type)) {
            case WALL -> allSquares(shape).stream().filter(s -> onWall(shape, s)).toList();
            case OFF_WALL -> allSquares(shape).stream()
                    .filter(s -> !onWall(shape, s))
                    .filter(s -> s.x() == 1 || s.y() == 1 || s.x() == w - 2 || s.y() == h - 2)
                    .toList();
            case AXIS_FAR -> {
                // The far half from wherever the party comes in, so the tomb is something you
                // walk toward rather than something you are already standing next to. It stays
                // off the end wall as well: a sarcophagus you can walk around reads as laid
                // out for viewing, and one shoved flat against the stone reads as stored.
                boolean partyLow = partyStart.y() < h / 2;
                var column = new ArrayList<Square>();
                for (int y = 1; y < h - 1; y++) {
                    if (partyLow ? y > h / 2 : y < h / 2) {
                        column.add(new Square(w / 2, y));
                    }
                }
                yield column;
            }
            case CLUSTER -> {
                // Touching one already down, so debris forms a pile. The first of its kind has
                // nothing to touch, so it settles against a wall and the rest gather on it.
                var neighbours = new ArrayList<Square>();
                for (var prop : placed) {
                    if (prop.type() != type) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            var square = new Square(prop.x() + dx, prop.y() + dy);
                            if (inside(shape, square)) {
                                neighbours.add(square);
                            }
                        }
                    }
                }
                yield neighbours.isEmpty()
                        ? allSquares(shape).stream().filter(s -> onWall(shape, s)).toList()
                        : neighbours;
            }
        };
    }

    /**
     * Which way a prop turns to face.
     *
     * <p>A thing against a wall faces into the room; the tomb on the axis squares up to the
     * party; anything else takes a free rotation. Corners are resolved south-first,
     * arbitrarily but consistently: either wall is a wall it could be cut into, and the
     * renderer needs one answer rather than the better of two.
     *
     * <p>The cardinal values are fixed by the renderer, not chosen here. A prop's local +Z is
     * its back, and a Y rotation of t sends that to (sin t, cos t) in world space; the wall a
     * given square backs onto is known from the square. Matching the two gives south 0, east
     * 90, north 180, west 270.
     *
     * <p>East and west were the other way round at first, from reading the authored crypt's
     * alcove — which sits at x=9 in a room 12 wide and so is not against a wall at all, and
     * proved nothing. It showed up the moment the alcove became a recess: instead of being set
     * into the west wall it stood half a square clear of it, facing the stone.
     *
     * <p>The axis case is the reason this takes a type at all. A free rotation is fine for a
     * pillar, which looks the same from every side, and wrong for the one object in the room
     * the player is walking toward: 157 of 200 seeds had the tomb sitting at some angle to the
     * approach, which reads as dropped rather than laid to rest. The authored crypt's
     * sarcophagus is rotation 0 with the party entering from the south, and this reproduces
     * that — turning to 180 if a room ever puts the party at the other end.
     */
    private static int facing(GenRandom random, RoomShape shape, Square square,
                              Square partyStart, PropType type) {
        if (affinityOf(type) == Affinity.AXIS_FAR) {
            return partyStart.y() < shape.height() / 2 ? 0 : 180;
        }
        if (square.y() == 0) {
            return 0;
        }
        if (square.x() == 0) {
            return 270;
        }
        if (square.y() == shape.height() - 1) {
            return 180;
        }
        if (square.x() == shape.width() - 1) {
            return 90;
        }
        return random.pick(List.of(0, 90, 180, 270));
    }

    private static boolean onWall(RoomShape shape, Square square) {
        return square.x() == 0 || square.y() == 0
                || square.x() == shape.width() - 1 || square.y() == shape.height() - 1;
    }

    private static boolean inside(RoomShape shape, Square square) {
        return square.x() >= 0 && square.x() < shape.width()
                && square.y() >= 0 && square.y() < shape.height();
    }

    private static List<Square> allSquares(RoomShape shape) {
        var out = new ArrayList<Square>(shape.width() * shape.height());
        for (int x = 0; x < shape.width(); x++) {
            for (int y = 0; y < shape.height(); y++) {
                out.add(new Square(x, y));
            }
        }
        return out;
    }
}
