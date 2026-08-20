package dm;

import dm.ai.NarrationParser;
import dm.model.NarrationSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deliberate narrow exception to shortcut #14 (dice and attack resolution only).
 *
 * <p>This parser holds streaming state, and its failure modes are silent or near-silent: a
 * mis-buffered marker either swallows narration or leaks "[[goblin]]" into text that gets read
 * aloud. The split-across-deltas case in particular cannot realistically be triggered by hand.
 */
class NarrationParserTest {

    private final List<NarrationSegment> segments = new ArrayList<>();

    private NarrationParser parser() {
        return new NarrationParser(Set.of("goblin", "fighter"), segments::add);
    }

    private String textOf(String speaker) {
        return segments.stream()
                .filter(s -> s.speakerId().equals(speaker))
                .map(NarrationSegment::text)
                .reduce("", String::concat);
    }

    @Test
    @DisplayName("plain narration is attributed to the narrator")
    void plainNarration() {
        var p = parser();
        p.accept("The lid grinds back three inches and stops.");
        p.finish();

        assertEquals(1, segments.size());
        assertEquals("narrator", segments.getFirst().speakerId());
        assertTrue(segments.getFirst().text().contains("grinds back"));
    }

    @Test
    @DisplayName("a speaker marker switches attribution")
    void markerSwitchesSpeaker() {
        var p = parser();
        p.accept("The lid shifts. [[goblin]] \"Ssstay back!\" [[narrator]] Something scrabbles.");
        p.finish();

        assertTrue(textOf("goblin").contains("Ssstay back!"));
        assertTrue(textOf("narrator").contains("The lid shifts."));
        assertTrue(textOf("narrator").contains("Something scrabbles."));
    }

    @Test
    @DisplayName("a marker split across deltas is never emitted as prose")
    void markerSplitAcrossDeltas() {
        var p = parser();
        // The exact shape a token stream produces.
        for (String delta : List.of("The lid shifts. ", "[[", "gob", "lin]]", " \"Back!\"")) {
            p.accept(delta);
        }
        p.finish();

        String all = segments.stream().map(NarrationSegment::text).reduce("", String::concat);
        assertFalse(all.contains("[["), "marker leaked into narration: " + all);
        assertFalse(all.contains("]]"), "marker leaked into narration: " + all);
        assertTrue(textOf("goblin").contains("Back!"));
    }

    @Test
    @DisplayName("a trailing bracket is held back rather than flushed as text")
    void trailingBracketHeld() {
        var p = parser();
        p.accept("It stops. [");
        assertFalse(
                segments.stream().anyMatch(s -> s.text().contains("[")),
                "a lone '[' was emitted before its marker could close");

        p.accept("[goblin]] \"Mine!\"");
        p.finish();
        assertTrue(textOf("goblin").contains("Mine!"));
    }

    @Test
    @DisplayName("sentences flush as they complete, so TTS can start early")
    void flushesOnSentenceBoundary() {
        var p = parser();
        p.accept("The air is cold. ");

        assertEquals(1, segments.size(), "a complete sentence should flush immediately");
        assertTrue(segments.getFirst().text().contains("cold."));

        p.accept("Dust hangs");
        assertEquals(1, segments.size(), "an incomplete sentence must not flush");

        p.finish();
        assertTrue(textOf("narrator").contains("Dust hangs"));
    }

    @Test
    @DisplayName("an unknown speaker falls back to the narrator")
    void unknownSpeakerFallsBack() {
        var p = parser();
        p.accept("[[balrog]] \"You shall not pass.\"");
        p.finish();

        assertEquals(1, segments.size());
        assertEquals("narrator", segments.getFirst().speakerId(),
                "the model must not be able to invent a voice");
    }

    @Test
    @DisplayName("no text is lost across the whole stream")
    void losesNothing() {
        var p = parser();
        String source = "It gives. [[goblin]] \"Nnno!\" [[narrator]] A hand claws at the rim.";
        for (int i = 0; i < source.length(); i += 3) {
            p.accept(source.substring(i, Math.min(i + 3, source.length())));
        }
        p.finish();

        String all = segments.stream().map(NarrationSegment::text).reduce("", String::concat);
        assertTrue(all.contains("It gives."), all);
        assertTrue(all.contains("Nnno!"), all);
        assertTrue(all.contains("A hand claws at the rim."), all);
    }

    @Test
    @DisplayName("a creature's voice ends with its quotation, without a closing marker")
    void speakerRevertsAfterQuote() {
        var p = parser();
        // The failure this exists for: models open with [[goblin]] and never close it, so
        // every line of narration that follows gets read in the goblin's voice.
        p.accept("[[goblin]] \"Ssstay back!\" The braziers hiss low. A finger pokes through.");
        p.finish();

        assertEquals("\"Ssstay back!\"", textOf("goblin").strip());
        assertTrue(textOf("narrator").contains("braziers hiss low"));
        assertTrue(textOf("narrator").contains("finger pokes through"));
    }

    @Test
    @DisplayName("a creature speaking twice needs a marker each time")
    void secondQuoteNeedsItsOwnMarker() {
        var p = parser();
        p.accept("[[goblin]] \"I am Vessk.\" It shuffles closer. [[goblin]] \"I am hungry.\" Silence.");
        p.finish();

        assertTrue(textOf("goblin").contains("I am Vessk."));
        assertTrue(textOf("goblin").contains("I am hungry."));
        assertFalse(textOf("goblin").contains("shuffles closer"));
        assertTrue(textOf("narrator").contains("shuffles closer"));
        assertTrue(textOf("narrator").contains("Silence."));
    }

    @Test
    @DisplayName("unquoted dialogue holds the voice for one segment, not the rest of the turn")
    void unquotedDialogueDoesNotRunAway() {
        var p = parser();
        p.accept("[[goblin]] Ssstay back. ");
        p.accept("The lid grinds another inch.");
        p.finish();

        assertTrue(textOf("goblin").contains("Ssstay back."));
        assertTrue(textOf("narrator").contains("grinds another inch"));
    }

    @Test
    @DisplayName("the narrator's voice never expires on its own quotes")
    void narratorKeepsSpeakingThroughQuotes() {
        var p = parser();
        p.accept("A sign reads \"KEEP OUT\" in flaking paint. The door is shut.");
        p.finish();

        assertEquals("narrator", segments.getFirst().speakerId());
        assertTrue(segments.stream().allMatch(x -> x.speakerId().equals("narrator")));
    }

    @Test
    @DisplayName("markdown artifacts are never handed to the voice")
    void dropsUnspeakableSegments() {
        var p = parser();
        p.accept("The lid gives. \n\n```\n [[goblin]] \"Nnnoooo!\" Then silence.");
        p.finish();

        assertTrue(segments.stream().noneMatch(x -> x.text().contains("`")),
                "a code fence reached the voice: " + segments);
        assertTrue(textOf("goblin").contains("Nnnoooo!"));
        assertTrue(textOf("narrator").contains("Then silence."));
    }
}
