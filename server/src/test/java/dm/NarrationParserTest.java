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
    @DisplayName("a creature keeps its voice for a second line off the same marker")
    void secondQuoteKeepsTheCreatureVoice() {
        var p = parser();
        // Models mark a creature once and then let it speak several times, with narration in
        // between. Both halves have to land: the speech stays the creature's, the prose does not.
        p.accept("[[goblin]] \"I am Vessk.\" It shuffles closer. \"I am hungry.\" Then silence.");
        p.finish();

        assertTrue(textOf("goblin").contains("I am Vessk."));
        assertTrue(textOf("goblin").contains("I am hungry."), "second line lost the goblin voice");
        assertFalse(textOf("goblin").contains("shuffles closer"));
        assertTrue(textOf("narrator").contains("shuffles closer"));
        assertTrue(textOf("narrator").contains("Then silence."));
    }

    @Test
    @DisplayName("a creature's voice survives being flushed sentence by sentence")
    void voiceSurvivesStreamingBoundaries() {
        var p = parser();
        p.accept("[[goblin]] \"I am Vessk.\"");
        p.accept(" It shuffles closer.");
        p.accept(" \"I am hungry.\"");
        p.accept(" The braziers gutter.");
        p.finish();

        assertTrue(textOf("goblin").contains("I am Vessk."));
        assertTrue(textOf("goblin").contains("I am hungry."));
        assertFalse(textOf("goblin").contains("shuffles"));
        assertFalse(textOf("goblin").contains("braziers"));
    }

    @Test
    @DisplayName("a different marker hands the voice over")
    void markerHandsOver() {
        var p = parser();
        p.accept("[[goblin]] \"Mine!\" [[fighter]] \"Not any more.\" The blade clears its sheath.");
        p.finish();

        assertTrue(textOf("goblin").contains("Mine!"));
        assertTrue(textOf("fighter").contains("Not any more."));
        assertFalse(textOf("fighter").contains("blade clears"));
        assertTrue(textOf("narrator").contains("blade clears"));
    }

    @Test
    @DisplayName("narration after a marker stays with the narrator, quotes or not")
    void unquotedTextIsNeverACreature() {
        var p = parser();
        // Models put the marker after the dialogue and then keep narrating. Giving a creature
        // any unquoted text costs more than it saves: the price is that genuinely unquoted
        // dialogue is read by the narrator, which has not been observed in practice.
        p.accept("[[goblin]] It hurls itself at you, claws extended.");
        p.accept(" The lid grinds another inch.");
        p.finish();

        assertEquals("", textOf("goblin"), "narration leaked into the creature voice");
        assertTrue(textOf("narrator").contains("hurls itself"));
        assertTrue(textOf("narrator").contains("grinds another inch"));
    }

    @Test
    @DisplayName("an unbalanced quote cannot capture the rest of the turn")
    void strayQuoteDoesNotDesyncTheVoices() {
        var p = parser();
        // Observed: the model wrote a closing quote with no opening one, which flipped the
        // parity and put every voice after it on the wrong side for the rest of the turn.
        p.accept("[[goblin]] \"Ssstop!\" It raises a fist with blackened fingernails.\"");
        p.accept("\n\nThe braziers gutter and the room goes dim.");
        p.finish();

        assertTrue(textOf("goblin").contains("Ssstop!"));
        assertTrue(textOf("narrator").contains("braziers gutter"),
                "a stray quote captured the narration: " + segments);
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

    @Test
    @DisplayName("bracketed stage directions never reach the voice")
    void dropsBracketedAsides() {
        var p = parser();
        p.accept("The door gives an inch. [Engage with the perception success] Dust drifts down.");
        p.finish();

        assertFalse(textOf("narrator").contains("Engage with"),
                "a stage direction reached the voice: " + segments);
        assertTrue(textOf("narrator").contains("door gives an inch"));
        assertTrue(textOf("narrator").contains("Dust drifts down"));
    }

    @Test
    @DisplayName("an unmarked quote goes to the creature that last spoke")
    void unmarkedQuoteFallsToTheLastCreature() {
        var p = parser();
        // Observed constantly: the model marks the first line, hands back with [[narrator]],
        // then lets the creature speak again with no marker. The narrator read those lines.
        p.accept("[[goblin]] \"Hrrk!\" [[narrator]] It presses back against the stone.");
        p.accept(" \"Vessk no like questions!\" it spits.");
        p.finish();

        assertTrue(textOf("goblin").contains("Hrrk!"));
        assertTrue(textOf("goblin").contains("Vessk no like questions!"),
                "an unmarked creature line stayed with the narrator: " + segments);
        assertTrue(textOf("narrator").contains("presses back"));
        assertTrue(textOf("narrator").contains("it spits"));
    }

    @Test
    @DisplayName("before any creature speaks, the narrator keeps its own quotations")
    void narratorKeepsQuotesUntilACreatureSpeaks() {
        var p = parser();
        p.accept("A sign reads \"KEEP OUT\" in flaking paint. The door is shut.");
        p.finish();

        assertTrue(segments.stream().allMatch(x -> x.speakerId().equals("narrator")));
    }
}
