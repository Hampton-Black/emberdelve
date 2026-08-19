package dm.wire;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        @JsonSubTypes.Type(value = ServerMessage.Roll.class, name = "roll"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error"),
})
public sealed interface ServerMessage {

    record Hello(boolean demoMode) implements ServerMessage {}

    record Scene(SceneState scene) implements ServerMessage {}

    record Diffs(List<Diff> diffs) implements ServerMessage {}

    /** {@code isFinal} goes over the wire as {@code final} — a reserved word in Java, not in JSON. */
    record Narration(NarrationSegment segment, @JsonProperty("final") boolean isFinal)
            implements ServerMessage {}

    record Roll(RollResult result) implements ServerMessage {}

    record Error(String message) implements ServerMessage {}
}
