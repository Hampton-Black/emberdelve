package dm.model;

/**
 * A side of a room, and the way you leave through it.
 *
 * <p>Four values rather than eight. An exit is cut into a wall and a wall has one outward face;
 * the eight-neighbour rule that governs movement is a movement rule, not an architectural one.
 *
 * <p>North is increasing y, which is the convention the DM is handed under {@code ## Grid}
 * ("axes: x eastward, y northward"). Two conventions in one game is how a party walks the wrong
 * way through a door that was described correctly.
 */
public enum Direction {
    NORTH(0, 1, 180),
    SOUTH(0, -1, 0),
    EAST(1, 0, 90),
    WEST(-1, 0, 270);

    private final int dx;
    private final int dy;
    private final int facing;

    Direction(int dx, int dy, int facing) {
        this.dx = dx;
        this.dy = dy;
        this.facing = facing;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    /** Degrees of Y rotation for a prop set into this wall. */
    public int facing() {
        return facing;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** The wall's name as the DM should say it out loud. */
    public String lowerName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
