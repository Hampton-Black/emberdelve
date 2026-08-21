package dm.generate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The only source of randomness in generation.
 *
 * <p>It exists as its own type rather than a bare {@link java.util.Random} for the same reason
 * {@code DiceRoller} is injected: a generator seeded in one place and reproducible on demand is
 * testable, and one that reaches for a static random is not.
 */
class GenRandomTest {

    @Test
    @DisplayName("the same seed produces the same sequence")
    void deterministic() {
        var a = new GenRandom(42);
        var b = new GenRandom(42);

        for (int i = 0; i < 50; i++) {
            assertEquals(a.between(0, 1000), b.between(0, 1000));
        }
    }

    @Test
    @DisplayName("different seeds diverge")
    void differentSeeds() {
        var a = new GenRandom(1);
        var b = new GenRandom(2);

        boolean anyDifference = false;
        for (int i = 0; i < 50; i++) {
            if (a.between(0, 1000) != b.between(0, 1000)) {
                anyDifference = true;
            }
        }
        assertTrue(anyDifference, "two seeds produced identical sequences");
    }

    @Test
    @DisplayName("between is inclusive on both ends and never leaves the range")
    void betweenIsInclusive() {
        var random = new GenRandom(7);

        for (int i = 0; i < 500; i++) {
            int value = random.between(3, 5);
            assertTrue(value >= 3 && value <= 5, "out of range: " + value);
        }
        assertEquals(4, new GenRandom(7).between(4, 4), "a single-value range returns that value");
    }

    @Test
    @DisplayName("pick returns a member of the list")
    void pickIsAMember() {
        var random = new GenRandom(9);
        var options = List.of("a", "b", "c");

        for (int i = 0; i < 50; i++) {
            assertTrue(options.contains(random.pick(options)));
        }
    }
}
