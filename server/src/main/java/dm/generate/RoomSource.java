package dm.generate;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the room the session runs in comes from.
 *
 * <p>A seam rather than a switch inside {@code GameEngine}: the engine should not know that
 * generation exists, and a hand-authored crypt has to keep working, because it is the room every
 * M0 measurement was taken in and the only fixed point available for comparison.
 */
public final class RoomSource {

    private static final Logger log = LoggerFactory.getLogger(RoomSource.class);

    private RoomSource() {
    }

    public static RoomDefinition authored(ContentLoader content, String roomId) {
        return content.room(roomId);
    }

    public static RoomDefinition generated(ContentLoader content, RoomDresser dresser,
                                           String kitId, long seed) {
        var room = new RoomGenerator(content).generate(kitId, seed);
        log.info("generated room, seed {}:\n{}", seed, RoomDumper.dump(room));

        var dressing = dresser.dress(room);
        log.info("dressed as '{}' — {}", dressing.name(), dressing.overview());

        return room.toRoomDefinition(dressing);
    }
}
