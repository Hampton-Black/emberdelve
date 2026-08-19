package dm.model;

/**
 * One span of narration attributed to a voice. {@code speakerId} is either
 * {@link #NARRATOR} or a live entity id — parsed from inline [[speaker]] markers in the
 * model's text output and validated against the current scene before it reaches TTS.
 */
public record NarrationSegment(String speakerId, String text) {

    public static final String NARRATOR = "narrator";

    public static NarrationSegment narrator(String text) {
        return new NarrationSegment(NARRATOR, text);
    }

    public boolean isNarrator() {
        return NARRATOR.equals(speakerId);
    }
}
