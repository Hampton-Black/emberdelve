package dm.ai;

import dm.model.Entity;
import dm.model.Event;
import dm.model.Outcome;
import dm.state.WorldState;

/**
 * Turns a combat fact into the sentence the narrator is handed.
 *
 * <p>The whole of this class is m0-evaluation.md §4.4 fault 1. The engine used to hand over
 * "Vessk hits Roderick for 6 damage." and leave the narrator to work out that Roderick is the
 * person being spoken to. It got that mapping right once and wrong once in the same fight, and
 * the wrong one described the player throwing the goblin across the room while the player's own
 * hit points drained on screen.
 *
 * <p>So the mapping stops being a judgement. The event carries both ids; the party is known; the
 * person is arithmetic.
 */
public final class BeatRenderer {

    private BeatRenderer() {
    }

    public static String render(WorldState state, Event.AttackResolved attack) {
        boolean actorIsPlayer = isParty(state, attack.actorId());
        boolean targetIsPlayer = isParty(state, attack.targetId());

        String actor = actorIsPlayer ? "You" : nameOf(state, attack.actorId());
        String target = address(state, attack.targetId());

        if (!attack.hit()) {
            String verb = attack.attack().outcome() == Outcome.CRIT_FAIL
                    ? (actorIsPlayer ? "swing at %s and fumble badly." : "swings at %s and fumbles badly.")
                    : (actorIsPlayer ? "swing at %s and miss." : "swings at %s and misses.");
            return actor + " " + verb.formatted(target);
        }

        String hit = attack.attack().isCrit()
                ? (actorIsPlayer ? "land a critical hit on %s for %d damage."
                                 : "lands a critical hit on %s for %d damage.")
                : (actorIsPlayer ? "hit %s for %d damage." : "hits %s for %d damage.");

        // A kill replaces the wound clause; it does not replace the sentence. The killing blow
        // used to return "Vessk is killed by the blow." on its own, which is the one beat in the
        // game that dropped its attacker — leaving the narrator to guess who had swung, on the
        // most important beat of the fight. m2-evaluation.md §8. The guess it has to make on a
        // hit is the guess it got backwards in m0-evaluation.md §4.4.
        String outcome = attack.killed()
                ? (targetIsPlayer ? "You are killed by the blow."
                                  : capitalise(target) + " is killed by the blow.")
                : condition(state, attack.targetId(), targetIsPlayer);

        return actor + " " + hit.formatted(target, attack.damageDealt()) + " " + outcome;
    }

    /**
     * The goblin closing into reach. Same person rule as {@link #render}: second person when the
     * party is the target, by name otherwise.
     */
    public static String renderClose(WorldState state, String actorId, String targetId) {
        return nameOf(state, actorId) + " closes the distance to " + address(state, targetId) + ".";
    }

    /**
     * The goblin advancing but not yet in reach. Same person rule as {@link #renderClose}.
     */
    public static String renderAdvance(WorldState state, String actorId, String targetId) {
        return nameOf(state, actorId) + " advances toward " + address(state, targetId) + ".";
    }

    private static String address(WorldState state, String entityId) {
        return isParty(state, entityId) ? "you" : nameOf(state, entityId);
    }

    private static boolean isParty(WorldState state, String entityId) {
        return state.party().stream().anyMatch(m -> m.entityId().equals(entityId));
    }

    private static String nameOf(WorldState state, String entityId) {
        return state.find(entityId).map(Entity::name).orElse(entityId);
    }

    /**
     * How hurt something looks. Never a number — {@code dm.md} forbids hit points said aloud, and
     * a fraction in the beat is an invitation to read one out.
     */
    private static String condition(WorldState state, String entityId, boolean isPlayer) {
        var entity = state.find(entityId).orElse(null);
        if (entity == null) {
            return "";
        }
        double left = (double) entity.hp() / Math.max(entity.maxHp(), 1);
        String state0 = left > 0.7 ? "barely marked"
                : left > 0.4 ? "bloodied"
                : left > 0.15 ? "badly hurt"
                : "barely standing";
        return isPlayer
                ? "You are now " + state0 + "."
                : capitalise(entity.name()) + " is now " + state0 + ".";
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
