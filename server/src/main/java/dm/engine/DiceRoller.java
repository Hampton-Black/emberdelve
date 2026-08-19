package dm.engine;

import dm.model.RollRequest;
import dm.model.RollResult;

/**
 * Injected, never static, never global. Tests inject {@link ScriptedDiceRoller}, which is how
 * combat stays assertable.
 */
public interface DiceRoller {
    RollResult roll(RollRequest request);
}
