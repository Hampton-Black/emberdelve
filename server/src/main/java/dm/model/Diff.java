package dm.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What actually goes over the wire after the initial {@link SceneState}.
 *
 * <p>{@code PropRevealed} is an addition to the M0 plan's original union. Without it a prop
 * revealed by the {@code reveal_prop} tool changes server state and never reaches the client.
 * See AGENTS.md → Deviations.
 *
 * <p>The {@code kind} discriminator is what makes this a discriminated union on the TypeScript
 * side — see {@code client/src/types.ts}. Keep the names in sync.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Diff.EntityAdded.class, name = "EntityAdded"),
        @JsonSubTypes.Type(value = Diff.EntityRemoved.class, name = "EntityRemoved"),
        @JsonSubTypes.Type(value = Diff.EntityMoved.class, name = "EntityMoved"),
        @JsonSubTypes.Type(value = Diff.StatChanged.class, name = "StatChanged"),
        @JsonSubTypes.Type(value = Diff.ModeChanged.class, name = "ModeChanged"),
        @JsonSubTypes.Type(value = Diff.PropRevealed.class, name = "PropRevealed"),
        @JsonSubTypes.Type(value = Diff.CombatChanged.class, name = "CombatChanged"),
})
public sealed interface Diff {

    record EntityAdded(EntityView entity) implements Diff {}

    record EntityRemoved(String entityId) implements Diff {}

    record EntityMoved(String entityId, int fromX, int fromY, int x, int y) implements Diff {}

    record StatChanged(String entityId, String stat, int from, int to) implements Diff {}

    record ModeChanged(Mode mode) implements Diff {}

    record PropRevealed(Prop prop) implements Diff {}

    /**
     * The whole combat picture, replaced wholesale: order, whose turn it is, and what that
     * combatant may legally do. Coarse on purpose — a fine-grained "movement decremented"
     * diff would let the client's idea of the legal set drift from the server's, which is
     * the one thing invariant #1 exists to prevent.
     *
     * <p>A null {@code combat} means the fight is over.
     */
    record CombatChanged(CombatView combat) implements Diff {}
}
