package dm.model;

/**
 * A piece of set dressing on the grid.
 *
 * <p>{@code id} and {@code hidden} are additions to the M0 plan's original record: without an id
 * the {@code reveal_prop} tool has nothing to address, and without the flag there is nothing to
 * reveal. See AGENTS.md → Deviations.
 */
public record Prop(
        String id,
        PropType type,
        int x,
        int y,
        int rotation,
        boolean hidden
) {
    public Prop revealed() {
        return new Prop(id, type, x, y, rotation, false);
    }
}
