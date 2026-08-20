package dm.ai;

import dm.model.NarrationSegment;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Turns a stream of text deltas into speaker-attributed segments.
 *
 * <p>Two jobs, both latency-driven:
 *
 * <ul>
 *   <li>Split on inline {@code [[speaker]]} markers so the goblin gets a different voice. A marker
 *       can arrive split across deltas ({@code "[[gob"} then {@code "lin]]"}), so a partial marker
 *       is held back rather than emitted as prose.
 *   <li>Flush at sentence boundaries. A voice cannot start until it has a complete sentence, so
 *       waiting for the whole turn would add its entire generation time to the spoken-word budget.
 * </ul>
 *
 * <p>An unknown speaker falls back to the narrator — invariant #7 in the one place the model
 * writes a free-form string.
 *
 * <p>A creature's voice also <b>ends by itself</b>. Models reliably open a line with
 * {@code [[goblin]]} and then never close it, so without this the goblin's voice would read the
 * narration that follows its own dialogue. Told-not-trusted, the same way tool arguments are.
 */
public final class NarrationParser {

    private static final String OPEN = "[[";
    private static final String CLOSE = "]]";

    /** {@code [Engage with the perception success]} — written for the reader, never for the room. */
    private static final Pattern ASIDE = Pattern.compile("\\[[^\\]]*]");

    /**
     * {@code start_combat()} — the prose model writing down the machinery.
     *
     * <p>It has no tools and never did: the mechanics are decided by a different model before a
     * word is written. But it is handed the results of those calls as context, and it imitates
     * what it is shown — observed ending a turn by simply writing {@code start_combat()}, which
     * the queue then read aloud. Built from the real tool names rather than a general pattern,
     * so it cannot swallow a legitimate parenthesis, and so adding a tool cannot leave a gap.
     */
    private static final Pattern TOOL_CALL = Pattern.compile(
            "\\b(" + String.join("|", ToolSchema.ROLL_CHECK, ToolSchema.REVEAL_PROP,
                    ToolSchema.SPAWN_ENTITY, ToolSchema.START_COMBAT, "narrate")
                    + ")\\s*\\([^)]*\\)");

    /** Every string the model might write between the brackets, mapped to the entity it means. */
    private final Map<String, String> knownSpeakers;
    private final Consumer<NarrationSegment> onSegment;

    private final StringBuilder buffer = new StringBuilder();
    private String speaker = NarrationSegment.NARRATOR;

    /** Whether a creature's quotation is currently open. See {@link #emit}. */
    private boolean insideQuote;

    /**
     * The last creature named this turn, kept after the model hands the voice back.
     *
     * <p>Models mark a creature's first line and then let it speak again with no marker at all,
     * which had the narrator reading the goblin's dialogue. In a scene with one creature, an
     * unattributed quotation is that creature far more often than it is anything else.
     *
     * <p>Seeded rather than starting empty, for the same reason it persists. A parser lives for
     * one call, and a combat beat is its own call — so a turn where the model never wrote a
     * marker had the narrator reading the goblin's surrender in its own voice, while an earlier
     * turn that happened to include one got it right. Whether the goblin sounds like the goblin
     * should not depend on which call it spoke in.
     *
     * <p>The seed is only supplied where the creature is the only thing that <em>can</em> be
     * talking — a combat beat. On a free-text turn the narrator writes the player's dialogue too,
     * and a wrong guess there is not a flat voice but somebody else's: the player's taunt read
     * back in the snarl of the thing they taunted.
     */
    private String lastCreature;

    /**
     * @param knownSpeakers what the model may write between the brackets, mapped to the entity id
     *                      it resolves to. Both ids and display names are accepted: a model told
     *                      the party contains {@code fighter} named Roderick writes
     *                      {@code [[roderick]]} about as often as {@code [[fighter]]}, and the
     *                      cost of not accepting it is the line landing in the narrator's voice.
     * @param soleCreature  the one living creature in the room, or null when there is not exactly
     *                      one, or when an unmarked quotation is not safe to guess at. With two
     *                      candidates it is a guess rather than an inference, and the narrator
     *                      keeps it.
     */
    public NarrationParser(Map<String, String> knownSpeakers, String soleCreature,
                           Consumer<NarrationSegment> onSegment) {
        this.knownSpeakers = Map.copyOf(knownSpeakers);
        this.lastCreature = soleCreature;
        this.onSegment = onSegment;
    }

    public void accept(String delta) {
        buffer.append(delta);
        drain(false);
    }

    /** Flush whatever is left, complete sentence or not. Call once the turn ends. */
    public void finish() {
        drain(true);
        emit(takeAll());
    }

    private void drain(boolean flushPartialSentences) {
        while (true) {
            int open = buffer.indexOf(OPEN);

            if (open < 0) {
                // No marker in flight. Hold a trailing '[' in case it opens one next delta.
                int safe = buffer.length();
                if (!flushPartialSentences && safe > 0 && buffer.charAt(safe - 1) == '[') {
                    safe--;
                }
                emitFrom(safe, flushPartialSentences);
                return;
            }

            int close = buffer.indexOf(CLOSE, open);
            if (close < 0) {
                // Marker started but has not closed yet — only release text before it.
                emitFrom(open, flushPartialSentences);
                return;
            }

            emit(buffer.substring(0, open));
            speaker = resolve(buffer.substring(open + OPEN.length(), close).strip());
            if (!NarrationSegment.NARRATOR.equals(speaker)) {
                lastCreature = speaker;
            }
            insideQuote = false;
            buffer.delete(0, close + CLOSE.length());
        }
    }

    /** Emit sentences from the first {@code limit} characters, leaving the rest buffered. */
    private void emitFrom(int limit, boolean flushPartialSentences) {
        if (limit <= 0) {
            return;
        }
        String available = buffer.substring(0, limit);

        int cut = flushPartialSentences ? available.length() : lastSentenceEnd(available);
        if (cut <= 0) {
            return;
        }

        emit(available.substring(0, cut));
        buffer.delete(0, cut);
    }

    /** Index just past the last sentence-ending punctuation, or 0 if there is none. */
    private static int lastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Trailing quotes and brackets belong to the sentence that just ended.
                int end = i + 1;
                while (end < text.length() && "\"'”’)".indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                return end;
            }
        }
        return 0;
    }

    private String takeAll() {
        String remaining = buffer.toString();
        buffer.setLength(0);
        return remaining;
    }

    /**
     * Emits text, attributing each run to the voice that should say it.
     *
     * <p>The rule is deliberately blunt: <b>quoted runs belong to the marked creature, and
     * everything else belongs to the narrator.</b> A {@code [[goblin]]} marker names who is
     * currently speaking and stays in force until a different marker; it does not wrap a line.
     *
     * <p>Three separate model behaviours pushed it here, all observed:
     *
     * <ul>
     *   <li>Opening with {@code [[goblin]]} and never closing it — so prose must default to
     *       the narrator, or the goblin reads the rest of the scene.
     *   <li>Speaking twice off one marker — so a creature must keep its voice for later
     *       quotations, not just the first.
     *   <li>Putting the marker <em>after</em> the dialogue, then following it with narration —
     *       which is why unquoted text is never given to a creature, even right after a marker.
     * </ul>
     *
     * <p>The cost is that genuinely unquoted dialogue is read by the narrator. That has not been
     * seen once in practice, whereas the failure it replaces was in every other turn.
     */
    private void emit(String raw) {
        // Square brackets never appear in narration prose, and stage directions arrive both
        // inline and on their own line — so strip the span rather than test the whole segment.
        String text = TOOL_CALL.matcher(raw).replaceAll("");
        text = ASIDE.matcher(text).replaceAll("").replace("`", "");
        // Nothing pronounceable, nothing to say. Models occasionally emit a stray markdown fence
        // or a lone divider, and the queue would dutifully read it as its own line. The same test
        // runs again per segment in push(): this one catches a whole chunk that says nothing,
        // that one catches a chunk with real prose in it that still splits off a silent piece.
        if (unspeakable(text)) {
            return;
        }
        // Whose voice quoted speech belongs to right now. Until a creature has spoken this turn
        // there is nobody to hand a quotation to, so the narrator keeps it — that is what lets
        // it read a sign or a letter aloud in its own voice.
        String quoted = NarrationSegment.NARRATOR.equals(speaker) ? lastCreature : speaker;
        if (quoted == null) {
            push(NarrationSegment.NARRATOR, text);
            return;
        }

        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // A quotation never spans a blank line. Without this, one unbalanced quote mark —
            // which models do write — puts every voice after it on the wrong side of the parity
            // for the rest of the turn.
            if (insideQuote && c == '\n' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                insideQuote = false;
                push(quoted, text.substring(start, i));
                start = i;
                continue;
            }

            if (!isQuote(c)) {
                continue;
            }
            if (insideQuote) {
                insideQuote = false;
                push(quoted, text.substring(start, i + 1));
                start = i + 1;
            } else {
                push(NarrationSegment.NARRATOR, text.substring(start, i));
                insideQuote = true;
                start = i;
            }
        }

        push(insideQuote ? quoted : NarrationSegment.NARRATOR, text.substring(start));
    }

    /**
     * Emits one segment, unless there is nothing in it worth saying out loud.
     *
     * <p>Tested per segment rather than only per chunk because the split itself can manufacture
     * an empty one. A quotation containing no words — {@code ""} — is a line in the transcript
     * attributed to whoever was speaking and a moment of silence in their voice; the player sees
     * their own character apparently saying nothing. It arrives when the model reproduces
     * something example-shaped from its own prompt, which is not a thing that can be prevented
     * upstream with any confidence.
     */
    private void push(String voice, String text) {
        if (!unspeakable(text)) {
            onSegment.accept(new NarrationSegment(voice, text));
        }
    }

    /** Whether this is worth handing to a voice: blank, or punctuation with nothing inside it. */
    private static boolean unspeakable(String text) {
        return text.isBlank() || text.chars().noneMatch(Character::isLetterOrDigit);
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\u201c' || c == '\u201d';
    }

    /** The model can write any string between the brackets; only live entities are honoured. */
    private String resolve(String candidate) {
        String normalized = candidate.toLowerCase();
        if (NarrationSegment.NARRATOR.equals(normalized)) {
            return normalized;
        }
        return knownSpeakers.getOrDefault(normalized, NarrationSegment.NARRATOR);
    }
}
