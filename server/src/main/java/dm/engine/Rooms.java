package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.Direction;
import dm.model.RoomOrigin;
import dm.model.RoomOutline;
import dm.model.WallSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Rooms in this session. The site's total on the ending ledger. Spec §9. */
    public int size() {
        return byId.size();
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
     * {@link #coveredWalls()} is the first — compares {@link Placed#hops}, not this order.
     *
     * <p>A room with no path back from the entrance (a one-way exit, or simply unreachable) is
     * left out rather than guessed at, the same way {@link RoomOutline#beside} itself declines
     * to guess.
     */
    public Map<String, RoomOrigin> origins() {
        var origins = new LinkedHashMap<String, RoomOrigin>();
        placed().forEach((roomId, placed) -> origins.put(roomId, placed.origin()));
        return java.util.Collections.unmodifiableMap(origins);
    }

    /**
     * Per room, the perimeter segments it must not draw because a nearer room already does.
     *
     * <p>Two rooms joined by a door share the wall it is in: {@link RoomOutline#beside} lines the
     * doors up, which puts both perimeters on one plane. Drawing both is two walls fighting for
     * one depth — it showed as a door with two ring handles, then as noise across the seam.
     *
     * <p>The plane is drawn once, by whichever of the two rooms is <em>nearer the entrance</em>,
     * ties broken by {@code roomId} so the answer is the same on every run. Not by which room the
     * party is standing in: once more than one room is on screen a middle room has shared walls on
     * both sides, and a rule that flips with the party deletes the wall between two rooms it is
     * standing in neither of.
     *
     * <p>Omission is a 1D interval overlap on the shared plane, not a direction match. A whole-run
     * omission loses the part of a perimeter that <em>overhangs</em> the other room — two 1-unit
     * holes at the crypt's north corners, seen from the gallery — and a hole in a wall is a way
     * out.
     *
     * <p>A segment is left out only when the owner's run contains it <em>entirely</em>. The parity
     * worry that made the client omit whole runs was about segment <em>centres</em>: two rooms
     * whose widths differ in parity do sit half a square out of step with each other. Their
     * <em>boundaries</em> do not. {@link RoomOutline#beside} offsets by
     * {@code exit.x - back.x + (there.width - here.width) / 2}, and that same half-square in the
     * half-width term is what puts both rooms' segment edges back on one integer lattice — so a
     * partial overlap means something upstream is wrong, and doubled stone reads as noise where a
     * half-square gap reads as a way through.
     *
     * <p>A room the entrance cannot reach is absent, not empty, exactly as in {@link #origins()}.
     */
    public Map<String, Set<WallSegment>> coveredWalls() {
        var placed = placed();
        var runs = new LinkedHashMap<String, List<WallRun>>();
        placed.forEach((roomId, at) -> runs.put(roomId, wallRuns(byId.get(roomId), at.origin())));

        // Nearest the entrance first, ties by roomId. A room yields to everything ranked above
        // it, so every point of a shared plane is drawn by the one room that ranks first over it.
        var ranked = new ArrayList<>(placed.keySet());
        ranked.sort(Comparator.comparingInt((String roomId) -> placed.get(roomId).hops())
                .thenComparing(Comparator.naturalOrder()));

        var covered = new LinkedHashMap<String, Set<WallSegment>>();
        covered.put(ranked.getFirst(), Set.of());
        for (var i = 1; i < ranked.size(); i++) {
            var mineId = ranked.get(i);
            var mineCovered = new LinkedHashSet<WallSegment>();
            for (var ownerId : ranked.subList(0, i)) {
                for (var mine : runs.get(mineId)) {
                    for (var theirs : runs.get(ownerId)) {
                        mineCovered.addAll(mine.segmentsCoveredBy(theirs));
                    }
                }
            }
            covered.put(mineId, Set.copyOf(mineCovered));
        }
        return java.util.Collections.unmodifiableMap(covered);
    }

    /** A room's place in the world frame, and how many doors it is from the entrance. */
    private record Placed(RoomOrigin origin, int hops) {
    }

    private Map<String, Placed> placed() {
        var placed = new LinkedHashMap<String, Placed>();
        placed.put(first().roomId(), new Placed(new RoomOrigin(0, 0), 0));

        Deque<String> queue = new ArrayDeque<>();
        queue.add(first().roomId());
        while (!queue.isEmpty()) {
            var hereId = queue.poll();
            var here = byId.get(hereId);
            var from = placed.get(hereId);
            for (var exit : here.exits()) {
                var thereId = exit.toRoomId();
                if (placed.containsKey(thereId) || !has(thereId)) {
                    continue;
                }
                var outline = RoomOutline.beside(here, exit, byId.get(thereId));
                if (outline == null) {
                    continue;
                }
                placed.put(thereId, new Placed(
                        new RoomOrigin(from.origin().x() + outline.offsetX(),
                                from.origin().z() + outline.offsetZ()),
                        from.hops() + 1));
                queue.add(thereId);
            }
        }
        return placed;
    }

    /**
     * One side of a room's perimeter, as a 1D interval in the world frame.
     *
     * <p>North and south walls run along x and sit on a plane of constant z; east and west walls
     * are the other way round. Comparing two of them is comparing one number for the plane and
     * two for the span, which is what makes the shared-wall rule testable without a renderer.
     *
     * @param along the axis the run extends along; {@code plane} is a value on the other one
     */
    private record WallRun(Axis along, double plane, double from, double to,
                           List<PlacedSegment> segments) {

        private static final double EPSILON = 1e-9;

        /** Every segment of this run that {@code other} stands on the whole of. */
        List<WallSegment> segmentsCoveredBy(WallRun other) {
            if (other.along != along || Math.abs(other.plane - plane) > EPSILON) {
                return List.of();
            }
            return segments.stream()
                    .filter(s -> other.from - EPSILON <= s.from() && s.to() <= other.to + EPSILON)
                    .map(PlacedSegment::segment)
                    .toList();
        }
    }

    private record PlacedSegment(WallSegment segment, double from, double to) {
    }

    /** Which way a wall run extends. X for a north or south wall, Z for an east or west one. */
    private enum Axis {
        X, Z
    }

    /** One square of wall, centred on a square's centre. */
    private static PlacedSegment spanning(WallSegment segment, double centre) {
        return new PlacedSegment(segment, centre - 0.5, centre + 0.5);
    }

    /** The four sides of a room, in the world frame. Matches {@code Room._build_walls} exactly. */
    private static List<WallRun> wallRuns(RoomDefinition room, RoomOrigin origin) {
        var halfWidth = room.width() / 2.0;
        var halfHeight = room.height() / 2.0;
        var west = origin.x() - halfWidth;
        var east = origin.x() + halfWidth;
        var north = origin.z() - halfHeight;
        var south = origin.z() + halfHeight;

        var northward = new ArrayList<PlacedSegment>();
        var southward = new ArrayList<PlacedSegment>();
        for (var x = 0; x < room.width(); x++) {
            var centre = origin.x() + RoomOutline.localX(x, room.width());
            northward.add(spanning(new WallSegment(x, room.height() - 1, Direction.NORTH), centre));
            southward.add(spanning(new WallSegment(x, 0, Direction.SOUTH), centre));
        }
        var westward = new ArrayList<PlacedSegment>();
        var eastward = new ArrayList<PlacedSegment>();
        for (var y = 0; y < room.height(); y++) {
            var centre = origin.z() + RoomOutline.localZ(y, room.height());
            westward.add(spanning(new WallSegment(0, y, Direction.WEST), centre));
            eastward.add(spanning(new WallSegment(room.width() - 1, y, Direction.EAST), centre));
        }
        return List.of(
                new WallRun(Axis.X, north, west, east, List.copyOf(northward)),
                new WallRun(Axis.X, south, west, east, List.copyOf(southward)),
                new WallRun(Axis.Z, west, north, south, List.copyOf(westward)),
                new WallRun(Axis.Z, east, north, south, List.copyOf(eastward)));
    }
}
