package dm.model;

/**
 * One unit of perimeter wall: the square it stands against, and which of that square's sides.
 *
 * <p>The same pair the renderer already keys a wall placement on, so a segment named here
 * addresses exactly one piece of stone on the board.
 */
public record WallSegment(int x, int y, Direction direction) {
}
