package dm.model;

/**
 * Where a room's local frame sits in the world frame, anchored at the entrance.
 *
 * <p>Add this to a square's local position (the same arithmetic {@link RoomOutline#beside}
 * already does for one hop) to place it on the shared board. The entrance's origin is always
 * {@code (0, 0)}.
 */
public record RoomOrigin(double x, double z) {
}
