package dm.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two judgements a turn makes about the player's own sentence before a model sees it.
 *
 * <p>Another narrow exception to shortcut #14, for the same reason as the narration parser: both
 * fail silently. A repeat detector that never fires leaves the session's worst bug in place, and
 * one that always fires tells the DM every turn that it is repeating itself — and there is no
 * way to tell those apart by playing.
 */
class TurnHeuristicsTest {

    // ---- repeated actions ----

    @Test
    @DisplayName("the same action, reworded, is a repeat")
    void rewordedRetryIsARepeat() {
        assertTrue(DmService.resemblesAny(
                "I try shoving that sarcophagus lid open again",
                List.of("I shove the sarcophagus lid open")));
    }

    @Test
    @DisplayName("the same words in the same order are obviously a repeat")
    void verbatimRetryIsARepeat() {
        assertTrue(DmService.resemblesAny(
                "I open the sarcophagus",
                List.of("I look at the door", "I open the sarcophagus")));
    }

    @Test
    @DisplayName("the same verb against a different thing is a different question")
    void sameVerbDifferentTargetIsNotARepeat() {
        assertFalse(DmService.resemblesAny(
                "I look at the north door",
                List.of("I look at the sarcophagus")));
    }

    @Test
    @DisplayName("an unrelated action is not a repeat")
    void unrelatedActionIsNotARepeat() {
        assertFalse(DmService.resemblesAny(
                "I listen at the north door",
                List.of("I shove the sarcophagus lid open", "I hold the lantern up")));
    }

    @Test
    @DisplayName("the first turn of a session cannot be a repeat")
    void nothingEarlierIsNeverARepeat() {
        assertFalse(DmService.resemblesAny("I open the sarcophagus", List.of()));
    }

    @Test
    @DisplayName("a one-word action is too thin to compare")
    void tooFewWordsToJudge() {
        // "wait" against "I wait for it to move" would otherwise be a total overlap.
        assertFalse(DmService.resemblesAny("wait", List.of("I wait for the lid to move")));
    }

    // ---- is the player talking? ----

    @Test
    @DisplayName("a stated intention to speak counts as speech")
    void speechVerbsAreSpeech() {
        assertTrue(DmService.soundsLikeSpeech("I shout at it to come out and face me"));
        assertTrue(DmService.soundsLikeSpeech("I tell the goblin I mean it no harm"));
        assertTrue(DmService.soundsLikeSpeech("I ask what it wants"));
        assertTrue(DmService.soundsLikeSpeech("I taunt the thing in the box"));
    }

    @Test
    @DisplayName("quoted words count as speech even with no verb")
    void quotedTextIsSpeech() {
        assertTrue(DmService.soundsLikeSpeech("\"Come out of there.\""));
    }

    @Test
    @DisplayName("a physical action is not speech")
    void physicalActionsAreNotSpeech() {
        assertFalse(DmService.soundsLikeSpeech("I put my shoulder to the lid and heave"));
        assertFalse(DmService.soundsLikeSpeech("I hold the lantern up and look at the ceiling"));
        assertFalse(DmService.soundsLikeSpeech("I draw my sword and back towards the stairs"));
    }
}
