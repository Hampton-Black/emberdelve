package dm;

import dm.ai.NarrationParser;
import dm.model.NarrationSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** Ids and display names both, as {@code DmService.liveSpeakers} builds it. */
    private static final Map<String, String> SPEAKERS = Map.of(
            "goblin", "goblin", "vessk", "goblin",
            "fighter", "fighter", "roderick", "fighter");

    private NarrationParser parser() {
        return new NarrationParser(SPEAKERS, null, segments::add);
    }

    /** As the game builds it for a combat beat, where the creature is the only one talking. */
    private NarrationParser parserWithCreature() {
        return new NarrationParser(SPEAKERS, "goblin", segments::add);
    }

    private String textOf(String speaker) {
        return segments.stream()
                .filter(s -> s.speakerId().equals(speaker))
                .map(NarrationSegment::text)
                .reduce("", String::concat);
    }

    @Test
    @DisplayName("the one creature in the room gets its dialogue without a marker")
    void soleCreatureSpeaksUnmarked() {
        var p = parserWithCreature();
        p.accept("Vessk skids to a halt, eyes wide. \"I sssurrender! No more fighting!\" "
                + "The shortsword clatters to the stone.");
        p.finish();

        assertTrue(textOf("goblin").contains("sssurrender"),
                "a quotation in a turn the model never marked is still the goblin talking");
        assertFalse(textOf("narrator").contains("sssurrender"));
        // The prose around it is still the narrator's — the marker names a speaker, not a span.
        assertTrue(textOf("narrator").contains("skids to a halt"));
        assertTrue(textOf("narrator").contains("clatters to the stone"));
    }

    @Test
    @DisplayName("with nobody to attribute to, a quotation stays with the narrator")
    void unattributableQuotationStaysWithNarrator() {
        var p = parser();
        p.accept("A voice grinds out of the dark. \"Who goes there?\"");
        p.finish();

        assertTrue(textOf("narrator").contains("Who goes there"));
        assertEquals("", textOf("goblin"));
    }

    @Test
    @DisplayName("a leaked tool call never reaches the voice")
    void toolCallStripped() {
        var p = parser();
        p.accept("The goblin scrambles clear of the lid. start_combat()");
        p.finish();

        assertFalse(textOf("narrator").contains("start_combat"),
                "the prose model imitates the machinery it is shown, and the queue reads "
                        + "whatever it is handed");
        assertTrue(textOf("narrator").contains("scrambles clear"));
    }

    @Test
    @DisplayName("a tool call with arguments goes too, and takes its arguments with it")
    void toolCallWithArgumentsStripped() {
        var p = parser();
        p.accept("Something moves in the dark. spawn_entity({\"kind\": \"goblin\"}) It hisses.");
        p.finish();

        String spoken = textOf("narrator");
        assertFalse(spoken.contains("spawn_entity"));
        assertFalse(spoken.contains("kind"));
        assertTrue(spoken.contains("moves in the dark"));
        assertTrue(spoken.contains("It hisses"));
    }

    @Test
    @DisplayName("an ordinary parenthesis in prose survives")
    void parenthesesSurvive() {
        var p = parser();
        p.accept("The brazier (the nearer one) gutters and spits.");
        p.finish();

        assertTrue(textOf("narrator").contains("the nearer one"));
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

    @Test
    @DisplayName("a speaker marked by display name resolves to the entity")
    void displayNameResolvesToId() {
        var p = parser();
        // The state lists `goblin` — Vessk, and the model marks with whichever it feels like.
        p.accept("It shoulders the lid aside. [[Vessk]] \"Sssomeone elssse can have it.\"");
        p.finish();

        assertTrue(textOf("goblin").contains("Sssomeone elssse"),
                "a name-marked line was demoted to narration: " + segments);
        assertEquals("", textOf("Vessk"));
    }

    @Test
    @DisplayName("the player's own line is voiced as the player, not as what they are taunting")
    void playerDialogueIsAttributedToThePlayer() {
        var p = parser();
        p.accept("[[roderick]] \"Come out of the box, then.\" [[narrator]] "
                + "The words come back off the stone flatter than you meant them.");
        p.finish();

        assertTrue(textOf("fighter").contains("Come out of the box"));
        assertEquals("", textOf("goblin"),
                "the player's taunt landed in the mouth of the thing being taunted: " + segments);
        assertTrue(textOf("narrator").contains("flatter than you meant"));
    }

    @Test
    @DisplayName("on a free-text turn an unmarked quotation is nobody's but the narrator's")
    void unmarkedQuoteIsNotGuessedOnAFreeTextTurn() {
        var p = parser();
        // Both the goblin and the player can be talking here, so there is nothing to infer from.
        // parserWithCreature() is the combat beat, where only the creature can be.
        p.accept("The sound bounces off the vault. \"Come out of the box, then.\"");
        p.finish();

        assertEquals("", textOf("goblin"));
        assertTrue(textOf("narrator").contains("Come out of the box"));
    }

    @Test
    @DisplayName("a quotation with no words in it never reaches a voice")
    void emptyQuotationIsDropped() {
        var p = parser();
        // What a recited prompt skeleton looks like by the time it reaches the parser. The
        // player saw their own character credited with a line and heard nothing said.
        p.accept("[[fighter]] \"...\" [[narrator]] The chamber stays quiet.");
        p.finish();

        assertEquals("", textOf("fighter"),
                "an empty line was attributed to the player: " + segments);
        assertTrue(textOf("narrator").contains("stays quiet"));
    }

    @Test
    @DisplayName("an empty quotation inside real prose is dropped without taking the prose")
    void emptyQuotationInsideProseIsDropped() {
        var p = parser();
        p.accept("[[goblin]] \"\" [[narrator]] It works its jaw and manages nothing.");
        p.finish();

        assertEquals("", textOf("goblin"));
        assertTrue(textOf("narrator").contains("works its jaw"));
    }
}
