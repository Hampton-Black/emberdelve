package dm.generate;

import dm.content.ContentLoader;
import dm.model.Square;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The whole deterministic half of generation, behind one call.
 *
 * <p>An illegal arrangement is reseeded rather than repaired. Repair means writing a second
 * placer that undoes the first one's decisions, and the two would disagree the moment either
 * changed; a fresh seed is a handful of microseconds and cannot introduce a case the validator
 * has not already seen.
 */
public final class RoomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RoomGenerator.class);

    /** A kit that cannot produce a legal room in this many tries is a broken kit, not bad luck. */
    private static final int MAX_ATTEMPTS = 50;

    private final ContentLoader content;

    public RoomGenerator(ContentLoader content) {
        this.content = content;
    }

    public GeneratedRoom generate(String kitId, long seed) {
        var kit = content.kit(kitId);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long attemptSeed = seed + attempt;
            var random = new GenRandom(attemptSeed);
            var shape = ShapeGenerator.generate(random, kit);

            var partyStart = new Square(shape.width() / 2, 0);
            var props = PropPlacer.place(random, kit, shape, partyStart);

            var violations = SpatialValidator.check(shape, props, partyStart);
            if (!violations.isEmpty()) {
                log.debug("seed {} rejected: {}", attemptSeed, violations);
                continue;
            }

            var goblinSpawn = spawnAwayFrom(shape, props, partyStart);
            if (goblinSpawn == null) {
                continue;
            }
            // The fold still opens in WorldState.EMPTY's room ("crypt"). A generated session
            // is one room in that world; naming it generated-{seed} would fail the engine's
            // entrance assertion and look up a room the map does not hold.
            return new GeneratedRoom(
                    kitId, shape, props, partyStart, goblinSpawn);
        }
        throw new IllegalStateException(
                "Kit '" + kitId + "' produced no legal room in " + MAX_ATTEMPTS + " attempts");
    }

    /** The far half of the room, so a hostile does not arrive in the player's lap. */
    private static Square spawnAwayFrom(RoomShape shape, List<dm.model.Prop> props,
                                        Square partyStart) {
        for (int row = shape.height() - 1; row >= shape.height() / 2; row--) {
            for (int column = 0; column < shape.width(); column++) {
                // Copied into finals: a for-loop variable is not effectively final and cannot
                // be captured by the lambda below.
                final int x = column;
                final int y = row;

                var square = new Square(x, y);
                if (square.equals(partyStart)) {
                    continue;
                }
                boolean blocked = props.stream().anyMatch(p ->
                        p.x() == x && p.y() == y && p.type().blocksMovement());
                if (!blocked) {
                    return square;
                }
            }
        }
        return null;
    }
}
