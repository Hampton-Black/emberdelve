package dm.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * m2-evaluation.md §4, the one Established lie the gate session produced.
 *
 * <p>Turn 8 failed a wall search, and reconcile wrote down "The stone perimeter is slick with damp
 * lime and yields nothing to searching." Turn 25 found an alcove in that perimeter. The engine was
 * always going to allow it; the fault is a failed check recorded as a fact about the <em>world</em>
 * rather than about the search.
 *
 * <p>Said per turn rather than in the system prompt, for the same reason the repeat directive is:
 * it is only true on the turns where it is true, and a standing rule is one the model has no way
 * to check itself against.
 */
class ReconcilePromptTest {

    private static final String NARRATION = "Your hands find nothing but cold, damp stone.";

    @Test
    @DisplayName("a failed check warns against writing the absence down as a fact")
    void failedCheckWarnsAboutAbsence() {
        var prompt = DmService.reconcilePrompt(NARRATION, List.of(
                "investigation check, DC 15. Rolled 4 +2 = 6 — FAILURE. "
                        + "Narrate this outcome and commit to it."));

        assertTrue(prompt.contains("FAILURE"), "the outcome itself is still handed over");
        assertTrue(prompt.toLowerCase().contains("did not find"),
                "the turn has to say what a failed check means before assert_fact is offered");
        assertTrue(prompt.contains("assert_fact"),
                "the warning names the tool it is a warning about");
    }

    @Test
    @DisplayName("a critical failure counts as a failure for this")
    void critFailAlsoWarns() {
        var prompt = DmService.reconcilePrompt(NARRATION, List.of(
                "perception check, DC 10. Rolled 1 +0 = 1 — CRIT_FAIL. "
                        + "Narrate this outcome and commit to it."));

        assertTrue(prompt.toLowerCase().contains("did not find"));
    }

    @Test
    @DisplayName("a successful check is left alone")
    void successIsNotWarnedAbout() {
        var prompt = DmService.reconcilePrompt(NARRATION, List.of(
                "investigation check, DC 15. Rolled 17 +2 = 19 — SUCCESS. "
                        + "Narrate this outcome and commit to it."));

        assertFalse(prompt.toLowerCase().contains("did not find"),
                "a success establishes what was found; there is nothing to caveat");
    }

    @Test
    @DisplayName("a turn with no checks in it is unchanged")
    void noChecksNoWarning() {
        var quiet = DmService.reconcilePrompt(NARRATION, List.of());

        assertTrue(quiet.contains("The engine did nothing this turn."));
        assertFalse(quiet.toLowerCase().contains("did not find"));
    }
}
