package dm.ai;

import dm.model.NarrationSegment;

import java.util.Set;
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
 *   <li>Flush at sentence boundaries. TTS cannot start until it has a complete sentence, so
 *       waiting for the whole turn would blow the 1.5s spoken-word budget on its own.
 * </ul>
 *
 * <p>An unknown speaker falls back to the narrator — invariant #7 in the one place the model
 * writes a free-form string.
 */
public final class NarrationParser {

    private static final String OPEN = "[[";
    private static final String CLOSE = "]]";

    private final Set<String> knownSpeakers;
    private final Consumer<NarrationSegment> onSegment;

    private final StringBuilder buffer = new StringBuilder();
    private String speaker = NarrationSegment.NARRATOR;

    public NarrationParser(Set<String> knownSpeakers, Consumer<NarrationSegment> onSegment) {
        this.knownSpeakers = Set.copyOf(knownSpeakers);
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

    private void emit(String text) {
        if (!text.isBlank()) {
            onSegment.accept(new NarrationSegment(speaker, text));
        }
    }

    /** The model can write any string between the brackets; only live entities are honoured. */
    private String resolve(String candidate) {
        String normalized = candidate.toLowerCase();
        if (NarrationSegment.NARRATOR.equals(normalized) || knownSpeakers.contains(normalized)) {
            return normalized;
        }
        return NarrationSegment.NARRATOR;
    }
}
