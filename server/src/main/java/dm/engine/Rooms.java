package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every room this session can be in, as structure.
 *
 * <p>Structure only — spec §5a. What a room <em>looks like</em> is a {@code Dressing} folded out
 * of the log, and {@link GameEngine#room()} is where the two meet. Keeping them apart here is
 * what stops this becoming a cache: nothing in this class ever changes after construction, so
 * there is no second write path for invariant #8 to worry about.
 *
 * <p>Insertion-ordered, because the first room is where the party starts.
 */
public final class Rooms {

    private final Map<String, RoomDefinition> byId;

    private Rooms(Map<String, RoomDefinition> byId) {
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("A session needs at least one room");
        }
        // Insertion-ordered: first() is the entrance, and Map.copyOf would make that arbitrary.
        this.byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    /** Rooms read from the content files, in the order named. The first is the entrance. */
    public static Rooms authored(ContentLoader content, String... roomIds) {
        var ordered = new LinkedHashMap<String, RoomDefinition>();
        for (var roomId : roomIds) {
            ordered.put(roomId, content.room(roomId));
        }
        return new Rooms(ordered);
    }

    /** For tests, and for the generated single room {@code --generate} still produces. */
    public static Rooms of(RoomDefinition... rooms) {
        var ordered = new LinkedHashMap<String, RoomDefinition>();
        for (var room : rooms) {
            ordered.put(room.roomId(), room);
        }
        return new Rooms(ordered);
    }

    public boolean has(String roomId) {
        return byId.containsKey(roomId);
    }

    public RoomDefinition structure(String roomId) {
        var room = byId.get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("No such room: " + roomId);
        }
        return room;
    }

    /** Where the party starts. */
    public RoomDefinition first() {
        return byId.values().iterator().next();
    }
}
