package dm.ai;

import dm.model.Anchor;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7a. Truncating the transcript without these two repairs turns a fixed fault back into a
 * subtler one: a model told "the transcript has what happened last time" with no such transcript
 * invents what it is declining to repeat.
 */
class DmContextTest {

    private static final Instant T = Instant.now();

    private static EventLog logWith(String... playerLines) {
        var log = new EventLog();
        for (String line : playerLines) {
            log.append(new Event.PlayerSaid(T, "fighter", line));
            log.append(new Event.NarrationLogged(T, "narrator", "The lid does not move. [" + line + "]"));
        }
        return log;
    }

    @Test
    @DisplayName("a repeat is caught however far back the first attempt was")
    void repeatDetectionIgnoresTheWindow() {
        var log = logWith("I shove the sarcophagus lid open",
                "I look at the braziers", "I look at the door", "I check the walls",
                "I listen", "I wait", "I look up", "I search the rubble");

        assertTrue(DmService.isRepeat(log, "I try shoving that lid open again"),
                "forty turns later is still a repeat — the log is not a window");
        assertFalse(DmService.isRepeat(log, "I light a torch"));
    }

    @Test
    @DisplayName("the earlier narration is recoverable, so the directive can carry it inline")
    void earlierNarrationIsRecoverable() {
        var log = logWith("I shove the sarcophagus lid open", "I look at the braziers");

        var earlier = DmService.narrationAfter(log, "I shove the sarcophagus lid open");
        assertTrue(earlier.orElseThrow().contains("shove the sarcophagus lid open"),
                "pointing at a transcript that may not be in context is how the model invents");
    }

    @Test
    @DisplayName("a paraphrased retry still recovers the earlier narration")
    void paraphrasedRetryRecoversEarlierNarration() {
        var log = logWith("I shove the sarcophagus lid open");

        var exact = DmService.narrationAfter(log, "I shove the sarcophagus lid open");
        assertTrue(exact.orElseThrow().contains("shove the sarcophagus lid open"),
                "verbatim recovery is how the directive has always quoted last time");

        // Production appends PlayerSaid at the start of the turn, then asks narrationAfter
        // with the current line — which is a paraphrase, not the earlier string.
        log.append(new Event.PlayerSaid(T, "fighter", "I try shoving that lid open again"));

        var recovered = DmService.narrationAfter(log, "I try shoving that lid open again");
        assertEquals(exact, recovered,
                "a reworded retry still has to carry the earlier line inline — spec §7a");
    }

    @Test
    @DisplayName("the window keeps the most recent turns and drops the rest")
    void windowKeepsRecent() {
        var turns = new java.util.ArrayList<DmClient.ChatMessage>();
        for (int i = 0; i < 20; i++) {
            turns.add(DmClient.ChatMessage.user("turn " + i));
            turns.add(DmClient.ChatMessage.assistant("answer " + i));
        }

        var window = DmService.window(turns, DmService.WINDOW_TURNS);

        assertEquals(DmService.WINDOW_TURNS * 2, window.size());
        assertTrue(window.getFirst().content().contains("turn 14"));
        assertTrue(window.getLast().content().contains("answer 19"));
    }

    @Test
    @DisplayName("a folded fact appears under ## Established in the world-state the models see")
    void establishedFactsAreShownToTheModels() {
        var state = WorldState.fold(List.of(
                new Event.FactAsserted(T, "f1", "crypt",
                        "The air tastes of old iron.", Anchor.AMBIENT)));

        var text = DmService.established(state);

        assertTrue(text.contains("## Established"),
                "the heading is how the models are told these are already true");
        assertTrue(text.contains("The air tastes of old iron."));
    }
}
