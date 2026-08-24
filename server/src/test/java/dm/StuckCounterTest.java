package dm;

import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Difficulty;
import dm.model.Skill;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The count of consecutive failed checks — the one piece of state behind failing forward.
 *
 * <p>A narrow exception to shortcut #14, for the same reason {@code NarrationParserTest} is one:
 * this is silent. A counter that resets when it should not, or keeps climbing when it should
 * not, produces a DM that escalates at the wrong moment — and the only symptom is a scene that
 * feels slightly off in a way no error will ever report.
 *
 * <p>Every roll is scripted, so a failure means the engine changed rather than the dice.
 */
class StuckCounterTest {

    /** Fighter athletics is +5; HARD is DC 20, so a 14 fails and a 15 succeeds. */
    private static GameEngine engineRolling(Integer... faces) {
        var engine = new GameEngine(
                new ContentLoader(), new EventLog(), new ScriptedDiceRoller(faces));
        engine.start();
        return engine;
    }

    private static void check(GameEngine engine) {
        engine.rollCheck("fighter", Skill.ATHLETICS, Difficulty.HARD);
    }

    @Test
    @DisplayName("a fresh session is not stuck")
    void startsAtZero() {
        assertEquals(0, engineRolling(14).consecutiveFailedChecks());
    }

    @Test
    @DisplayName("consecutive failures accumulate")
    void failuresAccumulate() {
        var engine = engineRolling(14, 13, 12);

        check(engine);
        assertEquals(1, engine.consecutiveFailedChecks());
        check(engine);
        assertEquals(2, engine.consecutiveFailedChecks(), "the DM escalates on the second");
        check(engine);
        assertEquals(3, engine.consecutiveFailedChecks());
    }

    @Test
    @DisplayName("a success clears it — being stuck is about the run, not the total")
    void successResets() {
        var engine = engineRolling(14, 13, 19, 12);

        check(engine);
        check(engine);
        assertEquals(2, engine.consecutiveFailedChecks());

        check(engine); // 19 + 5 = 24 against DC 20
        assertEquals(0, engine.consecutiveFailedChecks());

        check(engine);
        assertEquals(1, engine.consecutiveFailedChecks(), "counting starts again from one");
    }

    @Test
    @DisplayName("a different skill still counts — the player is stuck either way")
    void anySkillCounts() {
        var engine = engineRolling(2, 2);

        engine.rollCheck("fighter", Skill.ATHLETICS, Difficulty.HARD);
        engine.rollCheck("fighter", Skill.PERCEPTION, Difficulty.HARD);

        assertEquals(2, engine.consecutiveFailedChecks(),
                "shoving a lid then searching a wall is one player out of ideas, not two attempts");
    }

    @Test
    @DisplayName("a new session is not stuck either")
    void restartClears() {
        var engine = engineRolling(14, 13);

        check(engine);
        check(engine);
        assertEquals(2, engine.consecutiveFailedChecks());

        engine.restart();
        assertEquals(0, engine.consecutiveFailedChecks());
    }
}
