package dm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The edge between two scenes.
 *
 * <p>{@code Exit} rather than {@code Door}, deliberately, and ahead of anything that needs the
 * distinction. The edge is a door in a crypt and a road out of a village, a path over a pass, a
 * ford across a river. M3 only ever builds doors; the name costs nothing today and is a painful
 * retrofit later.
 *
 * @param id       the id of the {@code DOOR} prop standing in this square, so the renderer and
 *                 the DM address one thing rather than two
 * @param toRoomId the scene on the other side. Never shown to the model — spec §7a
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Exit(String id, int x, int y, Direction direction, String toRoomId,
                   boolean wayOut) {

    public Exit(String id, int x, int y, Direction direction, String toRoomId) {
        this(id, x, y, direction, toRoomId, false);
    }

    public Square square() {
        return new Square(x, y);
    }

    /**
     * The square one step inside the room, where a party arriving through this door lands.
     *
     * <p>Not the doorway itself: standing in a door you have just come through is standing on the
     * trigger that sent you here, and the player would bounce straight back out.
     */
    public Square inward(int width, int height) {
        return new Square(
                Math.clamp(x - direction.dx(), 0, width - 1),
                Math.clamp(y - direction.dy(), 0, height - 1));
    }
}
