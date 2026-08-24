package dm.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No network. Only the voice choice is under test, which is the part that fails silently:
 * a wrong voice is not an error anywhere, it is a goblin that sounds like the narrator.
 */
class TtsClientTest {

    private static final Map<String, String> KINDS = Map.of(
            "goblin", "goblin",
            "goblin-1", "goblin",
            "goblin-2", "goblin",
            "fighter", "fighter");

    private TtsClient client() {
        UnaryOperator<String> kindOf = id -> KINDS.getOrDefault(id, "narrator");
        return new TtsClient("key", "narrator-voice", "goblin-voice", kindOf);
    }

    @Test
    @DisplayName("every goblin gets the goblin voice, whatever its id")
    void allGoblinsShareAVoice() {
        assertEquals("goblin-voice", client().voiceFor("goblin"));
        assertEquals("goblin-voice", client().voiceFor("goblin-1"),
                "a dungeon with two goblins gives them distinct ids and one voice");
        assertEquals("goblin-voice", client().voiceFor("goblin-2"));
    }

    @Test
    @DisplayName("the fighter and the narrator share the narrator's voice")
    void everythingElseIsTheNarrator() {
        assertEquals("narrator-voice", client().voiceFor("fighter"));
        assertEquals("narrator-voice", client().voiceFor("narrator"));
        assertEquals("narrator-voice", client().voiceFor("nobody-in-particular"));
    }
}
