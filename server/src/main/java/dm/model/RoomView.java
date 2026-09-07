package dm.model;

import java.util.List;

/**
 * A room, exactly as far as the client is allowed to draw it — whether or not the party is
 * standing in it.
 *
 * <p>One shape for every room, current or not. Forced by {@code Rooms.coveredWalls()}: the
 * shared-wall owner is decided by BFS distance from the entrance, not by which room the party is
 * standing in, so the current room and a neighbour have to go through the same wall-building code
 * on the client. Two wire shapes would mean two code paths and the rule implemented twice.
 *
 * <p>{@code origin} is this room's place in the world frame {@link dm.engine.Rooms#origins()}
 * anchored at the entrance — add a square's local position to it to place that square on the
 * shared board, the same arithmetic {@link RoomOutline#beside} already does for one hop.
 *
 * <p>An unvisited room carries {@code visited = false} and an empty {@code props} regardless of
 * what is actually on its floor. The DM is never told this room exists, so a furnished neighbour
 * would be a room on screen the narrator can and will contradict — spec §8b. Entities never
 * appear here at all, for the same reason: a creature the player can see that the DM was never
 * told about is that contradiction with a monster in it. {@link dm.state.WorldState#entitiesHere()}
 * stays the only source of entities, and it is current-room-scoped by construction.
 */
public record RoomView(
        String roomId,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        LightingPreset lighting,
        /** Visible props, including secrets already revealed in this room. Empty when unvisited. */
        List<Prop> props,
        List<Exit> exits,
        /**
         * Perimeter segments this room must not draw, because a room nearer the entrance already
         * draws that plane.
         *
         * <p>Shipped rather than derived, for the same reason {@code SceneState.blocked} is: the
         * client owns no architectural rule either (invariant #1). It could not derive this one
         * anyway — ownership is BFS distance from the entrance, and the client is never told
         * which room that is, only where each room stands.
         *
         * <p>{@link dm.engine.Rooms#coveredWalls()} is where the rule lives and where it is
         * tested. This carries its answer for one room, in a fixed order so two runs of the same
         * session put the same bytes on the wire.
         */
        List<WallSegment> coveredWalls,
        double originX,
        double originZ,
        /** Whether the party has ever stood here. False means geometry only. */
        boolean visited
) {
}
