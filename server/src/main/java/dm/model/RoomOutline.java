package dm.model;

import dm.content.RoomDefinition;

/**
 * An adjacent room, as much of it as the client is allowed to draw.
 *
 * <p>Floor, walls, size and where to put it. No lighting, no props, no entities — spec §8b. The
 * DM is never told this room exists, so a lit and furnished neighbour would be a room on screen
 * that the narrator can and will contradict. Unlit and empty, there is nothing to contradict: a
 * dark space looks like a dark space, and walking through the door is what lights it.
 *
 * <p>The offset is in the client's world units, where one square is one unit, x runs east and z
 * runs south. Computed server-side so there is one implementation of the arithmetic and it can
 * be tested without a renderer.
 *
 * @param offsetX added to a square's local position to place it in the current room's frame
 * @param back    this room's answering door, so the client leaves that one wall open instead of
 *                drawing stone across the opening the party is looking through. The only thing
 *                said about the inside of a neighbour, and it is said about a wall, not a room
 */
public record RoomOutline(String roomId, int width, int height, FloorType floorType,
                          WallType wallType, double offsetX, double offsetZ, Exit back) {

    /**
     * Place {@code there} so that its answering door sits one square beyond {@code exit}.
     *
     * @return null when nothing in {@code there} leads back — a one-way exit has no door to line
     *         up on, and a guessed placement is a room drawn through a wall
     */
    public static RoomOutline beside(RoomDefinition here, Exit exit, RoomDefinition there) {
        var back = there.exits().stream()
                .filter(e -> e.toRoomId().equals(here.roomId()))
                .filter(e -> e.direction() == exit.direction().opposite())
                .findFirst()
                .orElse(null);
        if (back == null) {
            return null;
        }

        double doorX = localX(exit.x(), here.width());
        double doorZ = localZ(exit.y(), here.height());
        double backX = localX(back.x(), there.width());
        double backZ = localZ(back.y(), there.height());

        // One square through the door, in the direction it faces. z runs south, so a step north
        // is a step of -1 in z.
        double throughX = doorX + exit.direction().dx();
        double throughZ = doorZ - exit.direction().dy();

        return new RoomOutline(there.roomId(), there.width(), there.height(),
                there.floorType(), there.wallType(), throughX - backX, throughZ - backZ, back);
    }

    /** Matches {@code World.grid_to_world} exactly. Two mappings would be one bug. */
    private static double localX(int x, int width) {
        return x - width / 2.0 + 0.5;
    }

    private static double localZ(int y, int height) {
        return -(y - height / 2.0 + 0.5);
    }
}
