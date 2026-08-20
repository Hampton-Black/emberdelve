package dm.engine;

import dm.model.Diff;
import dm.model.RollResult;

import java.util.List;

/**
 * Where a combat action's consequences go, in the order they happen.
 *
 * <p>A return value would not do. The goblin's turn is {@code move, pause, swing} — emitting the
 * attack roll before the move diff would land the dice while the token was still sliding. Pushing
 * through a sink makes that ordering a property of the code rather than of a convention.
 */
public interface CombatSink {

    void diffs(List<Diff> diffs);

    void roll(RollResult result);

    /**
     * One plain sentence of what just happened, for whoever is going to narrate it.
     *
     * <p>Facts, never prose — "Vessk hits Roderick for 5 damage", not "the blade bites deep". The
     * engine knows what happened and the model knows how to say it, and neither should be doing
     * the other's job. Default no-op: most callers only want the diffs.
     */
    default void beat(String fact) {
    }

    /** Collects instead of sending, for callers that need the consequences as values. */
    final class Buffer implements CombatSink {
        private final List<Diff> diffs = new java.util.ArrayList<>();
        private final List<RollResult> rolls = new java.util.ArrayList<>();
        private final List<String> beats = new java.util.ArrayList<>();

        @Override
        public void diffs(List<Diff> more) {
            diffs.addAll(more);
        }

        @Override
        public void roll(RollResult result) {
            rolls.add(result);
        }

        public List<Diff> collectedDiffs() {
            return List.copyOf(diffs);
        }

        @Override
        public void beat(String fact) {
            beats.add(fact);
        }

        public List<RollResult> collectedRolls() {
            return List.copyOf(rolls);
        }

        public List<String> collectedBeats() {
            return List.copyOf(beats);
        }
    }
}
