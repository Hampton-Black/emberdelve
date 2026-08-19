package dm.model;

/**
 * What actually goes over the wire after the initial {@link SceneState}.
 *
 * <p>{@code PropRevealed} is an addition to the M0 plan's original union. Without it a prop
 * revealed by the {@code reveal_prop} tool changes server state and never reaches the client.
 * See AGENTS.md → Deviations.
 */
public sealed interface Diff {

    record EntityAdded(EntityView entity) implements Diff {}

    record EntityRemoved(String entityId) implements Diff {}

    record EntityMoved(String entityId, int fromX, int fromY, int x, int y) implements Diff {}

    record StatChanged(String entityId, String stat, int from, int to) implements Diff {}

    record ModeChanged(Mode mode) implements Diff {}

    record PropRevealed(Prop prop) implements Diff {}
}
