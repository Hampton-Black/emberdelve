package dm.engine;

import dm.content.ContentLoader;
import dm.model.EntityView;
import dm.model.Direction;
import dm.model.RoomView;
import dm.model.WallSegment;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static dm.engine.SyntheticRooms.room;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link dm.model.SceneState#rooms()} — the reshape emberdelve-xgg.2 makes so that a room the
 * party is not standing in goes through the same wire shape as the one it is standing in.
 *
 * <p>{@code Rooms#origins()} and {@code Rooms#coveredWalls()} already have their own tests for
 * the placement arithmetic; these only cover which rooms {@code GameEngine.scene()} decides to
 * ship, and what each one is allowed to carry.
 */
class SceneRoomsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(Rooms rooms) {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10), rooms);
        engine.start();
        return engine;
    }

    private static RoomView roomView(GameEngine engine, String roomId) {
        return engine.scene().rooms().stream()
                .filter(r -> r.roomId().equals(roomId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(roomId + " missing from scene().rooms()"));
    }

    @Test
    @DisplayName("a room with no exits is known alone, and it is visited")
    void deadEndRoomIsKnownAlone() {
        var engine = started(Rooms.of(room("crypt", 4, 4)));

        var scene = engine.scene();

        assertEquals(Set.of("crypt"),
                scene.rooms().stream().map(RoomView::roomId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(roomView(engine, "crypt").visited());
    }

    @Test
    @DisplayName("a room visible through the current room's exit is known but not visited")
    void visibleThroughAnExitIsKnownButNotVisited() {
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));

        var view = roomView(engine, "gallery");

        assertFalse(view.visited());
        assertTrue(view.props().isEmpty(),
                "the gallery is authored with props, but an unvisited room ships geometry only");
    }

    @Test
    @DisplayName("a neighbour is told which of its segments the nearer room already draws")
    void aNeighbourCarriesTheSegmentsItMustNotDraw() {
        // The client cannot work this out: ownership is BFS distance from the entrance, and the
        // client has never been told which room that is. Standing in the crypt, the crypt is the
        // entrance and owns the shared plane; the gallery's answering south run is covered.
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));

        assertEquals(List.of(), roomView(engine, "crypt").coveredWalls(),
                "the entrance draws its whole perimeter, overhanging corners included");
        assertEquals(
                IntStream.range(0, 10)
                        .mapToObj(x -> new WallSegment(x, 0, Direction.SOUTH))
                        .toList(),
                roomView(engine, "gallery").coveredWalls(),
                "the gallery leaves the whole shared run to the crypt");
    }

    @Test
    @DisplayName("what a room must not draw is what Rooms.coveredWalls() says, not what it is next to")
    void theShippedSegmentsAreTheRuleItself() {
        // The wire carries the decision, not the inputs to it — invariant #1 applied to walls.
        // Asserting against coveredWalls() rather than a literal is what keeps the two from
        // drifting apart the day the rule changes.
        var rooms = Rooms.authored(CONTENT, "crypt", "gallery");
        var engine = started(rooms);

        for (var view : engine.scene().rooms()) {
            assertEquals(rooms.coveredWalls().get(view.roomId()), Set.copyOf(view.coveredWalls()),
                    view.roomId() + " must be shipped exactly the rule's own answer");
        }
    }

    @Test
    @DisplayName("a room neither visited nor reachable through the current exit is absent")
    void unreachableRoomIsAbsent() {
        // crypt -> b -> c. Standing in crypt, c is two hops away and not offered by any exit
        // of the current room, so it must not appear at all.
        var a = room("crypt", 4, 4,
                new dm.model.Exit("a-to-b", 3, 1, dm.model.Direction.EAST, "b"));
        var b = room("b", 6, 4,
                new dm.model.Exit("b-to-a", 0, 1, dm.model.Direction.WEST, "crypt"),
                new dm.model.Exit("b-to-c", 5, 2, dm.model.Direction.EAST, "c"));
        var c = room("c", 4, 4,
                new dm.model.Exit("c-to-b", 0, 2, dm.model.Direction.WEST, "b"));
        var engine = started(Rooms.of(a, b, c));

        var ids = engine.scene().rooms().stream().map(RoomView::roomId).toList();

        assertEquals(Set.of("crypt", "b"), Set.copyOf(ids));
        assertFalse(ids.contains("c"));
    }

    @Test
    @DisplayName("the current room goes through the same shape as every other room")
    void currentRoomIsUniform() {
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));

        var view = roomView(engine, "crypt");
        var here = engine.room();

        assertEquals(here.roomId(), view.roomId());
        assertEquals(here.width(), view.width());
        assertEquals(here.height(), view.height());
        assertEquals(here.floorType(), view.floorType());
        assertEquals(here.wallType(), view.wallType());
        assertEquals(here.lighting(), view.lighting());
        assertEquals(here.exits(), view.exits());
        assertTrue(view.visited());
        assertFalse(view.props().isEmpty());
    }

    @Test
    @DisplayName("a room's revealed secrets are its own — they do not leak to or from a neighbour")
    void revealedPropsAreRoomQualified() {
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.revealProp("alcove");
        engine.crossExit("door-north");
        engine.revealProp("niche");

        var crypt = roomView(engine, "crypt");
        var gallery = roomView(engine, "gallery");

        assertTrue(crypt.props().stream().anyMatch(p -> p.id().equals("alcove")),
                "the crypt's own secret is visible on the crypt");
        assertFalse(crypt.props().stream().anyMatch(p -> p.id().equals("niche")),
                "the gallery's secret must not leak onto the crypt");
        assertTrue(gallery.props().stream().anyMatch(p -> p.id().equals("niche")),
                "the gallery's own secret is visible on the gallery");
        assertFalse(gallery.props().stream().anyMatch(p -> p.id().equals("alcove")),
                "the crypt's secret must not leak onto the gallery");
    }

    @Test
    @DisplayName("a visited room left behind stays visited, geometry and all")
    void leavingARoomKeepsItVisited() {
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));

        engine.crossExit("door-north");

        assertEquals(Set.of("crypt", "gallery"),
                engine.scene().rooms().stream().map(RoomView::roomId)
                        .collect(java.util.stream.Collectors.toSet()));
        var crypt = roomView(engine, "crypt");
        assertTrue(crypt.visited());
        assertFalse(crypt.props().isEmpty(), "a visited room keeps its props once left");
    }

    @Test
    @DisplayName("entities never ride along on a room that is not current")
    void entitiesStayCurrentRoomOnly() {
        var engine = started(Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.spawnGoblin(6, 6);

        engine.crossExit("door-north");

        var ids = engine.scene().entities().stream().map(EntityView::id).toList();
        assertEquals(List.of("fighter"), ids,
                "the goblin was left in the crypt, not carried into the gallery");
    }
}
