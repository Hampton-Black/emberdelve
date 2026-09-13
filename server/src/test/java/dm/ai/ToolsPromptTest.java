package dm.ai;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * emberdelve-vy7: the tools prompt must not ask the model to start the fight the clock owns.
 *
 * <p>Loaded from disk; no Venice. A prompt-level replay of the rule is 4h9.15 — this locks the
 * wording so a later edit cannot bring the two-failure coin flip back.
 */
class ToolsPromptTest {

    private static final String TOOLS = new ContentLoader().prompt("dm-tools");

    @Test
    @DisplayName("does not tell the model to change the situation on the second consecutive failure")
    void noTwoFailureEscalationRule() {
        assertFalse(TOOLS.contains("second consecutive"), TOOLS);
        assertFalse(TOOLS.contains("the situation must change"), TOOLS);
        assertFalse(TOOLS.contains("Escalate with the tools"), TOOLS);
        assertFalse(TOOLS.contains("often the right one"), TOOLS);
        assertTrue(TOOLS.contains("## Failure"), TOOLS);
        assertTrue(TOOLS.contains("You do not owe a tool call because it failed."), TOOLS);
        assertTrue(TOOLS.contains("Do not spawn a creature or start a fight"), TOOLS);
    }

    @Test
    @DisplayName("start_combat is provoked fights only; unprovoked is ALERT's")
    void startCombatIsProvokedOnly() {
        assertTrue(TOOLS.contains("`start_combat()`"), TOOLS);
        assertTrue(TOOLS.contains("only when the player started it"), TOOLS);
        assertTrue(TOOLS.contains("swung, cornered, threatened"), TOOLS);
        assertTrue(TOOLS.contains("A hostile standing there is not enough"), TOOLS);
        assertTrue(TOOLS.contains("Unprovoked fights are the engine's (ALERT)"), TOOLS);
        assertFalse(TOOLS.contains("run out of patience"), TOOLS);
        assertFalse(TOOLS.contains("does not wait for the player to swing"), TOOLS);
        assertFalse(TOOLS.contains("is scenery"), TOOLS);
    }

    @Test
    @DisplayName("actor_id names ids under The party or Entities present")
    void actorIdNamesPartyOrEntitiesPresent() {
        assertTrue(TOOLS.contains("`## The party`"), TOOLS);
        assertTrue(TOOLS.contains("`## Entities present`"), TOOLS);
        assertFalse(TOOLS.contains("only ids listed under \"Entities present\""), TOOLS);
    }
}
