package dm.replay;

import dm.engine.DiceRoller;
import dm.model.RollRequest;
import dm.model.RollResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invariant #6, as a class. Replay reads the faces that were rolled; it never re-rolls from a
 * seed, because a seed would reproduce the dice and not the session.
 */
public final class ReplayDiceRoller implements DiceRoller {

    private final List<RollResult> recorded;
    private final AtomicInteger next = new AtomicInteger();

    public ReplayDiceRoller(List<RollResult> recorded) {
        this.recorded = List.copyOf(recorded);
    }

    @Override
    public RollResult roll(RollRequest request) {
        int index = next.getAndIncrement();
        if (index >= recorded.size()) {
            // Falling back to a real roll here would turn a failed replay into a passing one
            // that proves nothing, which is the single worst thing this harness could do.
            throw new IllegalStateException(
                    "the engine asked for roll %d and the session recorded %d"
                            .formatted(index + 1, recorded.size()));
        }
        return recorded.get(index);
    }
}
