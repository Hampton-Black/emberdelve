package dm.ai;

import dm.model.Event;
import dm.state.WorldState;

/** Task 11 replaces this. For now it says exactly what CombatEngine used to say. */
public final class BeatRenderer {

    private BeatRenderer() {
    }

    public static String render(WorldState state, Event.AttackResolved attack) {
        String actor = state.find(attack.actorId()).map(e -> e.name()).orElse(attack.actorId());
        String target = state.find(attack.targetId()).map(e -> e.name()).orElse(attack.targetId());

        if (!attack.hit()) {
            return attack.attack().outcome() == dm.model.Outcome.CRIT_FAIL
                    ? actor + " swings at " + target + " and fumbles badly."
                    : actor + " swings at " + target + " and misses.";
        }
        if (attack.killed()) {
            return target + " is killed by the blow.";
        }
        return (attack.attack().isCrit()
                ? "%s lands a critical hit on %s for %d damage."
                : "%s hits %s for %d damage.")
                .formatted(actor, target, attack.damageDealt());
    }
}
