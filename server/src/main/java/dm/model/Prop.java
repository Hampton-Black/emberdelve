package dm.model;

import java.util.List;

/**
 * A piece of set dressing on the grid.
 *
 * <p>{@code id} and {@code hidden} are additions to the M0 plan's original record: without an id
 * the {@code reveal_prop} tool has nothing to address, and without the flag there is nothing to
 * reveal. See AGENTS.md → Deviations.
 *
 * <p>{@code actions} names what a click on this prop may do. Empty means scenery — drawn, not
 * addressable. The client does not invent legality (invariant #1).
 */
public record Prop(
        String id,
        PropType type,
        int x,
        int y,
        int rotation,
        boolean hidden,
        List<String> actions
) {
    public Prop {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public Prop(String id, PropType type, int x, int y, int rotation, boolean hidden) {
        this(id, type, x, y, rotation, hidden, List.of());
    }

    public Prop revealed() {
        return new Prop(id, type, x, y, rotation, false, actions);
    }
}
