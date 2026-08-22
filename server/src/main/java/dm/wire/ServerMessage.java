package dm.wire;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dm.model.Diff;
import dm.model.NarrationSegment;
import dm.model.RollResult;
import dm.model.SceneState;

import java.util.List;

/**
 * Everything the server can say. These exist as records rather than ad-hoc maps because
 * Jackson needs the declared type to emit {@link Diff}'s {@code kind} discriminator —
 * a {@code Map<String, Object>} erases it and the client's tagged union stops working.
 *
 * <p>Mirrored by {@code ServerMessage} in {@code client/src/types.ts}. Keep the names in sync.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = ServerMessage.Scene.class, name = "scene"),
        @JsonSubTypes.Type(value = ServerMessage.Diffs.class, name = "diffs"),
        @JsonSubTypes.Type(value = ServerMessage.Narration.class, name = "narration"),
        @JsonSubTypes.Type(value = ServerMessage.NarrationEnd.class, name = "narrationEnd"),
        @JsonSubTypes.Type(value = ServerMessage.Roll.class, name = "roll"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error"),
})
public sealed interface ServerMessage {

    /**
     * @param voice whether this server can synthesise speech. The browser has its own speech
     *              synthesis to fall back on, and the difference is night and day, so it is
     *              worth the client knowing which one it is about to use.
     */
    record Hello(boolean demoMode, boolean voice, boolean dm) implements ServerMessage {}

    record Scene(SceneState scene) implements ServerMessage {}

    record Diffs(List<Diff> diffs) implements ServerMessage {}

    /** One speaker-attributed span of narration, emitted as soon as its sentence completes. */
    record Narration(NarrationSegment segment) implements ServerMessage {}

    /** The DM's turn is over. Separate from {@link Narration} so "final" is never ambiguous. */
    record NarrationEnd() implements ServerMessage {}

    record Roll(RollResult result) implements ServerMessage {}

    record Error(String message) implements ServerMessage {}
}
