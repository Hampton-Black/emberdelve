package dm.model;

import java.time.Instant;

/**
 * The append-only spine. Roll results are logged here with their faces and outcome
 * (invariant #6) — replay reads results, it never re-rolls from a seed.
 */
public sealed interface Event {

    Instant at();

    record RollLogged(Instant at, RollResult result) implements Event {}

    record ActionTaken(Instant at, String actorId, String description) implements Event {}

    record NarrationLogged(Instant at, String speakerId, String text) implements Event {}

    record PropRevealedEvent(Instant at, String propId) implements Event {}

    record ModeEntered(Instant at, Mode mode) implements Event {}

    static RollLogged roll(RollResult result) {
        return new RollLogged(Instant.now(), result);
    }

    static ActionTaken action(String actorId, String description) {
        return new ActionTaken(Instant.now(), actorId, description);
    }

    static NarrationLogged narration(String speakerId, String text) {
        return new NarrationLogged(Instant.now(), speakerId, text);
    }
}
