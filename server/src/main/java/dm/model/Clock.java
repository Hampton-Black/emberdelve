package dm.model;

/**
 * A counter that ticks on what the player did, fills, and fires a table.
 *
 * <p>Spec §6a. The tables hang on {@link ClockKind} via {@code ClockTables}; the record itself
 * does not carry a consequence, because a clock speaks at a threshold and again at the fill.
 */
public record Clock(ClockId id, ClockScale scale, int filled, int segments, ClockKind kind) {

    public static final int SEGMENTS = 6;

    public static Clock empty(ClockId id, ClockKind kind) {
        return new Clock(id, ClockScale.DELVE, 0, SEGMENTS, kind);
    }
}
