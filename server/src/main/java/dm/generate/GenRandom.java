package dm.generate;

import java.util.List;
import java.util.Random;

/**
 * The single source of randomness in generation.
 *
 * <p>Wraps {@link Random} rather than exposing it, so that every generator draws through one
 * seeded stream and a room is reproducible from its seed alone. This is the same argument as
 * the injected {@code DiceRoller}: randomness that can be pinned is randomness that can be
 * tested, and a static call in a corner of a generator quietly makes a whole milestone
 * untestable.
 */
public final class GenRandom {

    private final Random random;

    public GenRandom(long seed) {
        this.random = new Random(seed);
    }

    /** Inclusive on both ends, which is how room dimensions and prop counts are written. */
    public int between(int minInclusive, int maxInclusive) {
        if (maxInclusive < minInclusive) {
            throw new IllegalArgumentException(
                    "Empty range: " + minInclusive + ".." + maxInclusive);
        }
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    public <T> T pick(List<T> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick from an empty list");
        }
        return options.get(random.nextInt(options.size()));
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }
}
