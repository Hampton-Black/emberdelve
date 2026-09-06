package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.RoomOrigin;
import dm.model.RoomOutline;

import java.util.ArrayDeque;
import java.util.Deque;
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

    /**
     * Places every reachable room in one world frame, anchored at the entrance.
     *
     * <p>Breadth-first from {@link #first()}, composing {@link RoomOutline#beside} offsets so
     * that a room several hops away gets a single absolute position rather than leaving that
     * composition to whoever draws it. The entrance sits at {@code (0, 0)} and never moves.
     *
     * <p>A cycle's second path to an already-placed room is ignored, not reconciled — real
     * world-space packing is the generator milestone's job. Iteration order is BFS discovery
     * order, but that is not the same thing as hop distance: two rooms discovered at the same
     * depth land in whichever order their parents' exit lists happened to be processed, not in
     * {@code roomId} order. Something that needs "nearer the entrance, ties broken by roomId" —
     * shared-wall ownership is the first — has to compare actual hop distance, not this map's
     * iteration order.
     *
     * <p>A room with no path back from the entrance (a one-way exit, or simply unreachable) is
     * left out rather than guessed at, the same way {@link RoomOutline#beside} itself declines
     * to guess.
     */
    public Map<String, RoomOrigin> origins() {
        var origins = new LinkedHashMap<String, RoomOrigin>();
        origins.put(first().roomId(), new RoomOrigin(0, 0));

        Deque<String> queue = new ArrayDeque<>();
        queue.add(first().roomId());
        while (!queue.isEmpty()) {
            var hereId = queue.poll();
            var here = byId.get(hereId);
            var hereOrigin = origins.get(hereId);
            for (var exit : here.exits()) {
                var thereId = exit.toRoomId();
                if (origins.containsKey(thereId) || !has(thereId)) {
                    continue;
                }
                var outline = RoomOutline.beside(here, exit, byId.get(thereId));
                if (outline == null) {
                    continue;
                }
                origins.put(thereId,
                        new RoomOrigin(hereOrigin.x() + outline.offsetX(),
                                hereOrigin.z() + outline.offsetZ()));
                queue.add(thereId);
            }
        }
        return java.util.Collections.unmodifiableMap(origins);
    }
}
