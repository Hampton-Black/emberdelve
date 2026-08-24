package dm.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of the whole batch: a session that happened, re-run against the engine, offline.
 *
 * <p>No Venice, no key, no network. The model's decisions are fixed inputs read from the file,
 * so what is under test is the engine's response to them — which means a change that alters how
 * a fight resolves fails here, against a real fight, rather than in play three weeks later.
 */
class ReplayRunnerTest {

    private static final Path SESSION =
            Path.of("src/test/resources/sessions/crypt-fight.jsonl");

    @Test
    @DisplayName("a recorded fight replays to the same events it produced the first time")
    void recordedSessionReplays() {
        var result = ReplayRunner.replay(SESSION);

        assertTrue(result.matched(),
                "divergence at: " + result.firstDivergence());
        assertTrue(result.compared() > 0, "an empty comparison proves nothing");
    }

    @Test
    @DisplayName("running out of recorded dice is loud, not a fresh random roll")
    void exhaustedDiceThrow() {
        var roller = new ReplayDiceRoller(java.util.List.of());
        var thrown = assertThrows(IllegalStateException.class,
                () -> roller.roll(dm.model.RollRequest.initiative("fighter", 2)));
        assertTrue(thrown.getMessage().contains("recorded"));
    }
}
