package dm.engine;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Production roller. Honest dice — no fudging, no bounded distributions. Streaky d20s are the game.
 */
public final class RandomDiceRoller extends AbstractDiceRoller {

    private static final String ALGORITHM = "L64X128MixRandom";

    private final RandomGenerator rng;

    public RandomDiceRoller() {
        this(RandomGenerator.of(ALGORITHM));
    }

    public RandomDiceRoller(RandomGenerator rng) {
        this.rng = rng;
    }

    /**
     * Seeded, for tests that want repeatable-but-real randomness. Note this is for tests only —
     * the campaign never replays from a seed, it replays from logged roll results (invariant #6).
     */
    public static RandomDiceRoller seeded(long seed) {
        return new RandomDiceRoller(RandomGeneratorFactory.of(ALGORITHM).create(seed));
    }

    @Override
    protected int rollDie(int sides) {
        return rng.nextInt(sides) + 1;
    }
}
