package dm.engine;

import dm.model.Entity;
import dm.model.Square;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Move toward the nearest player character; attack if adjacent. That is the entire monster
 * behaviour M0 asks for (§4), and it is deliberately not a behaviour tree.
 *
 * <p>It is worth being clear about what this is not: it does not flee at low health, focus the
 * weakest target, or use the pillars for cover. A goblin that fought cleverly would be a better
 * goblin and a worse answer to the question M0 is asking, which is whether the <em>loop</em>
 * feels like a game.
 */
final class GoblinAi {

    static void takeTurn(CombatEngine combat, Entity actor, CombatSink sink, Runnable beat) {
        Optional<Entity> nearest = nearestFoe(combat, actor);
        if (nearest.isEmpty()) {
            return;
        }
        Entity foe = nearest.get();

        // A held breath before the creature does anything, so its turn reads as a turn rather
        // than as the player's click having two effects.
        beat.run();

        Entity self = actor;
        if (!self.isAdjacentTo(foe)) {
            var step = closestStep(combat, self, foe);
            if (step.isPresent()) {
                self = combat.step(self, step.get(), sink);
                // From here rather than from moveTo: a move only knows a destination, and what
                // the narrator needs is why. Only this class knows the goblin was closing.
                sink.beat(self.isAdjacentTo(foe)
                        ? self.name() + " closes the distance to " + foe.name() + "."
                        : self.name() + " advances toward " + foe.name() + ".");
                beat.run();
            }
        }

        if (self.isAdjacentTo(foe) && combat.actionAvailable()) {
            combat.swing(self, foe, sink);
        }
    }

    /** Ties broken by id so a goblin between two identical targets does not pick at random. */
    private static Optional<Entity> nearestFoe(CombatEngine combat, Entity actor) {
        return combat.livingEntities().stream()
                .filter(e -> e.isPlayerControlled() != actor.isPlayerControlled())
                .min(Comparator.comparingInt(actor::distanceTo).thenComparing(Entity::id));
    }

    /**
     * The reachable square that ends up nearest the target, preferring the cheapest one — so a
     * goblin that could stop three squares away or six does not burn movement to stand still.
     */
    private static Optional<Square> closestStep(CombatEngine combat, Entity actor, Entity foe) {
        return combat.reachable(actor).entrySet().stream()
                .filter(e -> e.getValue() <= combat.movementRemaining())
                .min(Comparator
                        .comparingInt((Map.Entry<Square, Integer> e) -> chebyshev(e.getKey(), foe))
                        .thenComparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .filter(square -> chebyshev(square, foe) < actor.distanceTo(foe));
    }

    private static int chebyshev(Square square, Entity entity) {
        return Math.max(Math.abs(square.x() - entity.x()), Math.abs(square.y() - entity.y()));
    }

    private GoblinAi() {
    }
}
