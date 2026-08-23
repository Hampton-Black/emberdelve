# M1 Dungeon Navigation Implementation Plan

> **Unpaused 2026-08-23** after the Godot parity gate. Java tasks stand as written. **Client tasks in this plan are written against React + Three.js and must be re-planned against Godot before anyone starts them.** Wire mirrors are GDScript readers under `godot/`, not `client/src/types.ts`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Descend three rooms into a freshly seeded dungeon — walk through a door, arrive somewhere else, come back and find it as you left it — with nothing the narrator says contradicting what is on the board.

**Architecture:** A seeded, deterministic layout decides which rooms exist and which are joined. Each room's exits are placed into its walls before its props are, and the spatial validator refuses any arrangement that seals one. Layout is regenerated from the seed and never stored; the dress pass is not reproducible, so it is retained from first visit. `GameRepository` becomes room-scoped, so the goblin you left in room 2 is still in room 2. The client already rebuilds on a new `roomId` — walking onto a door square is what sends it one.

**Tech Stack:** Java 25 (records, sealed interfaces, pattern matching), Jackson 2.18, JUnit 5.12, Gradle Kotlin DSL, Godot 4 client (this plan's client tasks still describe React 19 + Three.js and need a Godot rewrite before they are started). No new dependencies.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md`. Where this plan and the spec disagree, the spec wins.
- **Predecessor:** `docs/superpowers/plans/2026-08-20-m1-room-generation.md`, which is complete and merged. This plan is the second half of the same milestone.
- **Invariant #1 — the server is authoritative.** The client never computes a roll, a hit, a legal move, or a death.
- **Invariant #2 — no singleton player.** `List<PartyMember>`, always. Every action carries an `actorId`.
- **Invariant #3 — game state never lives in a Control or Node3D.** It lives in `Table`.
- **Invariant #7 — all LLM-facing enums are closed and validated server-side.** No free-form string from the model reaches the engine.
- **Invariant #9 — modern Java only.** Records, sealed interfaces, pattern matching, virtual threads. No Spring, no `AbstractXFactory`, no mutable POJOs with getters and setters.
- **Spec §6 — generated content is validated for spatial legality, not just enum membership.** A prop in a doorway is a valid enum and an invalid world. This plan extends the rule to two new cases: an exit a prop stands in, and a room the layout leaves unreachable.
- **Spec §7 — the deterministic/persisted split.** Layout is regenerated from `(seed, coords)` and never stored. The dress pass cannot be regenerated from a seed, so it is retained from the moment it exists. "Retained" means kept in memory behind `GameRepository`/`Dungeon`, not written to a database.
- **Determinism:** everything except `RoomDresser` is a pure function of the seed. Same seed, same dungeon, forever.
- **Wire mirrors are hand-written.** Every change to a Java record in `dm.model` or `dm.wire` gets the matching GDScript reader in the same commit.
- **Commit messages** are a sentence saying what the commit does, in the repository's existing voice (`git log` for examples). No `feat:` prefixes, no `Co-Authored-By` trailer.
- **Test command:** `cd server && ./gradlew test`
- **Client tests:** `godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
- **Run command:** `cd server && ./gradlew run --args='--generate 7'`

## Not in this plan

- **A transition effect.** The client cuts from one room to the next. A fade is worth having and is not worth a task before anyone has walked a dungeon.
- **Prefetch.** Spec §3 defers it: the dress pass is covered by narration, and a threshold that hangs is the signal to revisit.
- **Postgres.** Spec §3. `GameRepository` gains room scope; the implementation behind it stays a map.
- **The 5e rules engine, encounter balancing, party beyond one fighter, dungeon *purpose*.** Spec §3.
- **A second environment.** Spec §7b is written down so M1's choices do not preclude it, not so M1 builds it.

## File Structure

| File | Responsibility |
|---|---|
| `server/src/main/java/dm/model/Direction.java` | The four sides of a room, and the rotation a door in each takes |
| `server/src/main/java/dm/model/Exit.java` | The edge between two scenes. `Exit`, never `Door` — spec §4 |
| `server/src/main/java/dm/generate/Doorway.java` | One edge of the topology: a direction and where it leads |
| `server/src/main/java/dm/generate/RoomNode.java` | A room in the layout: id, grid coordinates, its own seed, its doorways |
| `server/src/main/java/dm/generate/DungeonLayout.java` | The whole topology. Regenerated from the seed, never stored |
| `server/src/main/java/dm/generate/LayoutGenerator.java` | `(seed, roomCount) → DungeonLayout` |
| `server/src/main/java/dm/generate/LayoutValidator.java` | Spec §6 at dungeon scale: every room connected, every doorway reciprocated |
| `server/src/main/java/dm/generate/ExitPlacer.java` | Puts a room's doorways onto its walls as squares |
| `server/src/main/java/dm/generate/DungeonDumper.java` | The whole dungeon as text, for the terminal |
| `server/src/main/java/dm/generate/RoomProvider.java` | Where the engine gets rooms from — a seam, not a switch |
| `server/src/main/java/dm/generate/SingleRoom.java` | One authored room, as a provider. The crypt, and every existing test |
| `server/src/main/java/dm/generate/Dungeon.java` | Layout regenerated, dressing retained. Spec §7's split, in one class |
| `server/src/main/java/dm/engine/Walkable.java` | Where a creature may stand outside combat |
| `server/src/main/java/dm/generate/SpatialValidator.java` | *(modify)* the two new exit rules |
| `server/src/main/java/dm/generate/PropPlacer.java` | *(modify)* nothing shares a square with a doorway |
| `server/src/main/java/dm/generate/RoomGenerator.java` | *(modify)* generate from a `RoomNode`; emit exits and their door props |
| `server/src/main/java/dm/generate/GeneratedRoom.java` | *(modify)* carries exits |
| `server/src/main/java/dm/generate/RoomDumper.java` | *(modify)* the door glyph and an exits line |
| `server/src/main/java/dm/generate/RoomDresser.java` | *(modify)* the model is told which walls have ways out |
| `server/src/main/java/dm/content/RoomDefinition.java` | *(modify)* carries exits; `exitAt` |
| `server/src/main/java/dm/model/SceneState.java` | *(modify)* carries exits and the exploration legal-move set |
| `server/src/main/java/dm/model/Diff.java` | *(modify)* `LegalMovesChanged` |
| `server/src/main/java/dm/repo/GameRepository.java` | *(modify)* current room, visited rooms; entities and reveals scoped to a room |
| `server/src/main/java/dm/repo/InMemoryGameRepository.java` | *(modify)* one `RoomState` per room |
| `server/src/main/java/dm/engine/GameEngine.java` | *(modify)* many rooms, `enterRoom`, `exitAt`, `prepare` |
| `server/src/main/java/dm/engine/CombatEngine.java` | *(modify)* emits the exploration set when a fight ends |
| `server/src/main/java/dm/ai/DmService.java` | *(modify)* ways out, what the party stands next to, threshold and arrival narration |
| `server/src/main/java/dm/WsHandler.java` | *(modify)* crossing a threshold |
| `server/src/main/java/dm/App.java` | *(modify)* `--generate` boots a dungeon; `--dump` prints one |
| `server/src/main/resources/prompts/dress-room.md` | *(modify)* a door is a way out |
| `client/src/types.ts` | *(modify)* the wire mirror |
| `client/src/store.ts` | *(modify)* the new diff, and the floor during a transition |
| `client/src/ui/Canvas.tsx` | *(modify)* out of combat, the server's legal set decides |
| `client/src/scene/Renderer.ts` | *(modify)* exits are highlighted |
| `server/src/test/java/dm/generate/*` | One test class per unit above |
| `server/src/test/java/dm/engine/WalkableTest.java` | The exploration legal set |
| `server/src/test/java/dm/repo/InMemoryGameRepositoryTest.java` | Room scoping |

---

### Task 1: The dungeon's topology

Spec §4's `DungeonLayout`: rooms, exits and a connectivity graph from `(seed, coordinates)`, with no model in the path. Purely topological — which rooms exist, and which are joined to which. Nothing here knows how big a room is or what is in it; that is the room generator's problem and it already exists.

Corridors are edges, not rooms. Spec §7 rules out seamless room-to-room travel, so the thing between two scenes is a door you walk through, not a space you walk down.

**Files:**
- Create: `server/src/main/java/dm/model/Direction.java`
- Create: `server/src/main/java/dm/generate/Doorway.java`
- Create: `server/src/main/java/dm/generate/RoomNode.java`
- Create: `server/src/main/java/dm/generate/DungeonLayout.java`
- Create: `server/src/main/java/dm/generate/LayoutGenerator.java`
- Create: `server/src/main/java/dm/generate/LayoutValidator.java`
- Test: `server/src/test/java/dm/generate/LayoutGeneratorTest.java`

**Interfaces:**
- Consumes: `GenRandom` (existing — `between(int,int)`, `pick(List<T>)`, `chance(double)`)
- Produces:
  - `Direction` — `NORTH, SOUTH, EAST, WEST`, with `dx()`, `dy()`, `facing()`, `opposite()`
  - `Doorway(Direction direction, String toRoomId)`
  - `RoomNode(String roomId, int gridX, int gridY, long seed, List<Doorway> doorways)`, with `doorway(Direction)` returning `Optional<Doorway>`
  - `DungeonLayout(long seed, List<RoomNode> rooms)`, with `entrance()`, `room(String)`, `find(String)`
  - `LayoutGenerator.generate(long seed, int roomCount)` returning `DungeonLayout`
  - `LayoutValidator.check(DungeonLayout)` returning `List<String>` — empty means legal

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.model.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The topology, before anything is in it.
 *
 * <p>Spec §7: layout is never stored, only regenerated — which is only true if the same seed
 * gives the same dungeon forever. That is what the first test here is for, and it is the same
 * argument as the snapshot tests on room shape.
 */
class LayoutGeneratorTest {

    @Test
    @DisplayName("the same seed gives the same dungeon, forever")
    void deterministic() {
        var first = LayoutGenerator.generate(7, 8);
        var second = LayoutGenerator.generate(7, 8);

        assertEquals(first, second);
    }

    @Test
    @DisplayName("different seeds give different dungeons")
    void seedsDiffer() {
        var shapes = new HashSet<String>();
        for (long seed = 0; seed < 20; seed++) {
            shapes.add(LayoutGenerator.generate(seed, 8).rooms().stream()
                    .map(r -> r.gridX() + "," + r.gridY())
                    .reduce("", (a, b) -> a + "|" + b));
        }
        assertTrue(shapes.size() > 10, "20 seeds produced only " + shapes.size() + " layouts");
    }

    @Test
    @DisplayName("the dungeon has exactly the rooms it was asked for, each on its own cell")
    void roomCountAndCells() {
        var layout = LayoutGenerator.generate(3, 8);

        assertEquals(8, layout.rooms().size());
        var cells = layout.rooms().stream().map(r -> r.gridX() + "," + r.gridY()).toList();
        assertEquals(cells.size(), new HashSet<>(cells).size(), "two rooms share a cell");
        var ids = layout.rooms().stream().map(RoomNode::roomId).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "two rooms share an id");
    }

    @Test
    @DisplayName("every doorway is answered by one going the other way")
    void doorwaysAreReciprocal() {
        for (long seed = 0; seed < 50; seed++) {
            var layout = LayoutGenerator.generate(seed, 8);

            for (var room : layout.rooms()) {
                for (var doorway : room.doorways()) {
                    var other = layout.room(doorway.toRoomId());
                    assertTrue(
                            other.doorway(doorway.direction().opposite())
                                    .filter(d -> d.toRoomId().equals(room.roomId()))
                                    .isPresent(),
                            "seed " + seed + ": " + room.roomId() + " has a "
                                    + doorway.direction() + " door to " + other.roomId()
                                    + " and nothing answers it");
                }
            }
        }
    }

    @Test
    @DisplayName("a doorway joins cells that are actually next to each other")
    void doorwaysJoinNeighbours() {
        for (long seed = 0; seed < 50; seed++) {
            var layout = LayoutGenerator.generate(seed, 8);

            for (var room : layout.rooms()) {
                for (var doorway : room.doorways()) {
                    var other = layout.room(doorway.toRoomId());
                    assertEquals(room.gridX() + doorway.direction().dx(), other.gridX(),
                            "seed " + seed + ": " + room.roomId() + " -> " + other.roomId());
                    assertEquals(room.gridY() + doorway.direction().dy(), other.gridY(),
                            "seed " + seed + ": " + room.roomId() + " -> " + other.roomId());
                }
            }
        }
    }

    @Test
    @DisplayName("every room can be walked to from the entrance")
    void everyRoomIsReachable() {
        for (long seed = 0; seed < 200; seed++) {
            var layout = LayoutGenerator.generate(seed, 8);

            var seen = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            seen.add(layout.entrance().roomId());
            queue.add(layout.entrance().roomId());
            while (!queue.isEmpty()) {
                for (var doorway : layout.room(queue.poll()).doorways()) {
                    if (seen.add(doorway.toRoomId())) {
                        queue.add(doorway.toRoomId());
                    }
                }
            }

            assertEquals(layout.rooms().size(), seen.size(),
                    "seed " + seed + " left a room nobody can reach");
        }
    }

    @Test
    @DisplayName("no room has two doors in one wall")
    void oneDoorPerWall() {
        for (long seed = 0; seed < 200; seed++) {
            for (var room : LayoutGenerator.generate(seed, 8).rooms()) {
                var used = new HashSet<Direction>();
                for (var doorway : room.doorways()) {
                    assertTrue(used.add(doorway.direction()),
                            "seed " + seed + ": " + room.roomId() + " has two "
                                    + doorway.direction() + " doors");
                }
            }
        }
    }

    @Test
    @DisplayName("the validator passes every generated layout")
    void generatedLayoutsAreLegal() {
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(List.of(), LayoutValidator.check(LayoutGenerator.generate(seed, 8)),
                    "seed " + seed + " generated an illegal layout");
        }
    }

    @Test
    @DisplayName("a room nothing joins is rejected")
    void orphanRejected() {
        var orphan = new DungeonLayout(1, List.of(
                new RoomNode("room-0", 0, 0, 1, List.of()),
                new RoomNode("room-1", 5, 5, 2, List.of())));

        var violations = LayoutValidator.check(orphan);

        assertTrue(violations.stream().anyMatch(v -> v.contains("room-1")), violations.toString());
    }

    @Test
    @DisplayName("a doorway nothing answers is rejected")
    void unansweredDoorwayRejected() {
        var lopsided = new DungeonLayout(1, List.of(
                new RoomNode("room-0", 0, 0, 1, List.of(new Doorway(Direction.NORTH, "room-1"))),
                new RoomNode("room-1", 0, 1, 2, List.of())));

        var violations = LayoutValidator.check(lopsided);

        assertTrue(violations.stream().anyMatch(v -> v.contains("answer")), violations.toString());
    }

    @Test
    @DisplayName("a doorway to a room that does not exist is rejected")
    void danglingDoorwayRejected() {
        var dangling = new DungeonLayout(1, List.of(
                new RoomNode("room-0", 0, 0, 1, List.of(new Doorway(Direction.NORTH, "room-9")))));

        var violations = LayoutValidator.check(dangling);

        assertTrue(violations.stream().anyMatch(v -> v.contains("room-9")), violations.toString());
    }

    @Test
    @DisplayName("a one-room dungeon is legal and has no doors")
    void singleRoom() {
        var layout = LayoutGenerator.generate(11, 1);

        assertEquals(1, layout.rooms().size());
        assertEquals(List.of(), layout.entrance().doorways());
        assertEquals(List.of(), LayoutValidator.check(layout));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.LayoutGeneratorTest'`
Expected: FAIL — compilation error, `cannot find symbol: class LayoutGenerator`

- [ ] **Step 3: Write the model types**

`server/src/main/java/dm/model/Direction.java`:

```java
package dm.model;

/**
 * A side of a room, and the way you leave through it.
 *
 * <p>Four values rather than eight. An exit is cut into a wall and a wall has one outward face;
 * the eight-neighbour rule that governs movement is a movement rule, not an architectural one.
 *
 * <p>{@code facing} is the rotation a prop set into that wall takes, and it is fixed by the
 * renderer rather than chosen here — see the note on {@code PropPlacer.facing}, which resolves
 * the same four cases from a square's position and must agree with this to the degree.
 */
public enum Direction {
    /** Increasing y. The world state tells the DM "y northward", and this is that y. */
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
}
```

`server/src/main/java/dm/generate/Doorway.java`:

```java
package dm.generate;

import dm.model.Direction;

/**
 * One edge of the topology: a wall of this room, and the room on the other side of it.
 *
 * <p>Distinct from {@code Exit}, which is the same edge once a room has been given a size and
 * the door has a square to stand in. The layout does not know how big a room is.
 */
public record Doorway(Direction direction, String toRoomId) {
}
```

`server/src/main/java/dm/generate/RoomNode.java`:

```java
package dm.generate;

import dm.model.Direction;

import java.util.List;
import java.util.Optional;

/**
 * A room's place in the dungeon, before it is a room.
 *
 * <p>Carries its own seed rather than deriving one at generation time, so that a room's
 * contents depend on the dungeon's seed and its index and on nothing else. Regenerating room
 * 5 alone has to give the same room 5 the whole dungeon gave (spec §7).
 *
 * @param gridX column in the dungeon's cell grid. Not a world coordinate — rooms are separate
 *              scenes and their grids are their own
 */
public record RoomNode(String roomId, int gridX, int gridY, long seed, List<Doorway> doorways) {

    public RoomNode {
        doorways = List.copyOf(doorways);
    }

    public Optional<Doorway> doorway(Direction direction) {
        return doorways.stream().filter(d -> d.direction() == direction).findFirst();
    }
}
```

`server/src/main/java/dm/generate/DungeonLayout.java`:

```java
package dm.generate;

import java.util.List;
import java.util.Optional;

/**
 * The whole dungeon's topology.
 *
 * <p><b>This is never stored.</b> Spec §7's split puts layout on the deterministic side: it
 * is a pure function of {@code (seed, roomCount)} and is regenerated on demand, which is what
 * makes a large world cost nothing to keep. The dress pass is the half that must be retained,
 * and it lives in {@code Dungeon}.
 */
public record DungeonLayout(long seed, List<RoomNode> rooms) {

    public DungeonLayout {
        rooms = List.copyOf(rooms);
    }

    /** Where the party starts. The first room placed, which is always the origin cell. */
    public RoomNode entrance() {
        return rooms.getFirst();
    }

    public Optional<RoomNode> find(String roomId) {
        return rooms.stream().filter(r -> r.roomId().equals(roomId)).findFirst();
    }

    public RoomNode room(String roomId) {
        return find(roomId).orElseThrow(
                () -> new IllegalArgumentException("No such room: " + roomId));
    }
}
```

- [ ] **Step 4: Write the generator**

`server/src/main/java/dm/generate/LayoutGenerator.java`:

```java
package dm.generate;

import dm.model.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grows a dungeon on a cell grid from one seed.
 *
 * <p>A frontier walk rather than a maze algorithm: pick a room already placed, pick a
 * direction, and put a new room there if the cell is free. It produces the branching,
 * uneven shape a dug-out crypt has, and — because every room is joined to the one it grew
 * from — the result is connected by construction rather than by a repair pass.
 *
 * <p>On top of that tree, some adjacent pairs are joined anyway. A pure tree has exactly one
 * route between any two rooms, which reads as a corridor system with no choices in it; a
 * quarter of the extra adjacencies is enough to give a dungeon loops without turning it into
 * an open plan. Whether a given pair is joined is drawn from the same seeded stream as
 * everything else, so it is part of the dungeon's identity and not a coin flip at runtime.
 *
 * <p>Connectivity is still asserted afterwards by {@link LayoutValidator}. "Connected by
 * construction" is a claim about code that will be edited, and spec §6 asks for the check.
 */
public final class LayoutGenerator {

    /** How often two rooms that happen to be neighbours get a door as well as their tree edge. */
    private static final double LOOP_CHANCE = 0.25;

    private static final List<Direction> DIRECTIONS = List.of(Direction.values());

    /** A walk that cannot find a free cell in this many tries is boxed in, not unlucky. */
    private static final int MAX_TRIES_PER_ROOM = 100;

    private LayoutGenerator() {
    }

    public static DungeonLayout generate(long seed, int roomCount) {
        if (roomCount < 1) {
            throw new IllegalArgumentException("A dungeon needs at least one room");
        }
        var random = new GenRandom(seed);

        // Insertion-ordered, because room ids are assigned in placement order and the whole
        // layout has to be reproducible. A HashMap here would make the dungeon depend on
        // hash iteration order, which is exactly the kind of nondeterminism that only shows
        // up on someone else's machine.
        var placed = new LinkedHashMap<Cell, String>();
        var parents = new LinkedHashMap<Cell, Cell>();
        var order = new ArrayList<Cell>();

        var origin = new Cell(0, 0);
        placed.put(origin, "room-0");
        order.add(origin);

        int tries = 0;
        while (placed.size() < roomCount && tries++ < roomCount * MAX_TRIES_PER_ROOM) {
            var from = order.get(random.between(0, order.size() - 1));
            var to = from.step(random.pick(DIRECTIONS));
            if (placed.containsKey(to)) {
                continue;
            }
            placed.put(to, "room-" + placed.size());
            parents.put(to, from);
            order.add(to);
        }
        if (placed.size() < roomCount) {
            throw new IllegalStateException(
                    "Could not place " + roomCount + " rooms from seed " + seed);
        }

        var doorways = new LinkedHashMap<Cell, List<Doorway>>();
        for (var cell : order) {
            doorways.put(cell, new ArrayList<>());
        }
        var joined = new HashSet<Edge>();

        // The tree first: every room is joined to the one it grew from.
        for (var entry : parents.entrySet()) {
            join(placed, doorways, joined, entry.getKey(), entry.getValue());
        }

        // Then the loops. Only north and east are considered, so each adjacent pair is offered
        // exactly once — offering it twice would give a pair two chances and quietly double
        // the rate.
        for (var cell : order) {
            for (var direction : List.of(Direction.NORTH, Direction.EAST)) {
                var neighbour = cell.step(direction);
                if (!placed.containsKey(neighbour)) {
                    continue;
                }
                if (joined.contains(Edge.between(placed.get(cell), placed.get(neighbour)))) {
                    continue;
                }
                if (!random.chance(LOOP_CHANCE)) {
                    continue;
                }
                join(placed, doorways, joined, cell, neighbour);
            }
        }

        var rooms = new ArrayList<RoomNode>(roomCount);
        for (int i = 0; i < order.size(); i++) {
            var cell = order.get(i);
            rooms.add(new RoomNode(placed.get(cell), cell.x(), cell.y(),
                    roomSeed(seed, i), doorways.get(cell)));
        }
        return new DungeonLayout(seed, rooms);
    }

    /**
     * A room's own seed.
     *
     * <p>A large odd multiplier rather than {@code seed + index}: adjacent seeds hand
     * {@link java.util.Random} adjacent states, and rooms 3 and 4 of the same dungeon coming
     * out suspiciously alike is the "mush" failure in spec §11 arriving for a numerical
     * reason rather than a creative one.
     */
    private static long roomSeed(long seed, int index) {
        return seed * 0x9E3779B97F4A7C15L + index * 0x7FEB352D5L;
    }

    private static void join(Map<Cell, String> placed, Map<Cell, List<Doorway>> doorways,
                             Set<Edge> joined, Cell a, Cell b) {
        var direction = directionFrom(a, b);
        doorways.get(a).add(new Doorway(direction, placed.get(b)));
        doorways.get(b).add(new Doorway(direction.opposite(), placed.get(a)));
        joined.add(Edge.between(placed.get(a), placed.get(b)));
    }

    private static Direction directionFrom(Cell from, Cell to) {
        for (var direction : DIRECTIONS) {
            if (from.step(direction).equals(to)) {
                return direction;
            }
        }
        throw new IllegalArgumentException(from + " and " + to + " are not neighbours");
    }

    /** A cell in the dungeon's grid. Not a square — rooms are separate scenes. */
    private record Cell(int x, int y) {
        Cell step(Direction direction) {
            return new Cell(x + direction.dx(), y + direction.dy());
        }
    }

    /** An unordered pair of room ids, so "already joined" does not depend on which way you ask. */
    private record Edge(String a, String b) {
        static Edge between(String one, String other) {
            return one.compareTo(other) <= 0 ? new Edge(one, other) : new Edge(other, one);
        }
    }
}
```

- [ ] **Step 5: Write the validator**

`server/src/main/java/dm/generate/LayoutValidator.java`:

```java
package dm.generate;

import dm.model.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Spec §6, at the scale of a dungeon rather than a room.
 *
 * <p>{@code SpatialValidator} asks whether one room is a world worth standing in. This asks
 * whether the dungeon is one: whether every room can be walked to, and whether the doors agree
 * with each other. A room nobody can reach is the layout's version of a walled-off corner —
 * content that was generated, paid for, and cannot exist for the player.
 *
 * <p>Returns every violation rather than throwing on the first, for the same reason the spatial
 * validator does: one legible report beats a game of whack-a-mole.
 */
public final class LayoutValidator {

    private LayoutValidator() {
    }

    public static List<String> check(DungeonLayout layout) {
        var violations = new ArrayList<String>();
        var ids = new HashSet<String>();
        var cells = new HashSet<String>();

        for (var room : layout.rooms()) {
            if (!ids.add(room.roomId())) {
                violations.add("Duplicate room id: " + room.roomId());
            }
            if (!cells.add(room.gridX() + "," + room.gridY())) {
                violations.add("Two rooms share the cell " + room.gridX() + "," + room.gridY());
            }
            violations.addAll(checkDoorways(layout, room));
        }

        violations.addAll(unreachable(layout));
        return List.copyOf(violations);
    }

    private static List<String> checkDoorways(DungeonLayout layout, RoomNode room) {
        var violations = new ArrayList<String>();
        var walls = new HashSet<Direction>();

        for (var doorway : room.doorways()) {
            if (!walls.add(doorway.direction())) {
                violations.add(room.roomId() + " has two doors in its "
                        + doorway.direction().name().toLowerCase() + " wall");
            }

            var other = layout.find(doorway.toRoomId()).orElse(null);
            if (other == null) {
                violations.add(room.roomId() + " has a door to " + doorway.toRoomId()
                        + ", which does not exist");
                continue;
            }
            if (other.gridX() != room.gridX() + doorway.direction().dx()
                    || other.gridY() != room.gridY() + doorway.direction().dy()) {
                violations.add(room.roomId() + " has a "
                        + doorway.direction().name().toLowerCase() + " door to "
                        + other.roomId() + ", which is not next to it");
            }
            boolean answered = other.doorway(doorway.direction().opposite())
                    .filter(d -> d.toRoomId().equals(room.roomId()))
                    .isPresent();
            if (!answered) {
                violations.add("Nothing in " + other.roomId() + " answers the door from "
                        + room.roomId());
            }
        }
        return violations;
    }

    /** Every room must be walkable from the entrance, following doorways. */
    private static List<String> unreachable(DungeonLayout layout) {
        if (layout.rooms().isEmpty()) {
            return List.of("A dungeon with no rooms");
        }

        var seen = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        seen.add(layout.entrance().roomId());
        queue.add(layout.entrance().roomId());

        while (!queue.isEmpty()) {
            var room = layout.find(queue.poll()).orElse(null);
            if (room == null) {
                continue;
            }
            for (var doorway : room.doorways()) {
                if (seen.add(doorway.toRoomId())) {
                    queue.add(doorway.toRoomId());
                }
            }
        }

        var violations = new ArrayList<String>();
        for (var room : layout.rooms()) {
            if (!seen.contains(room.roomId())) {
                violations.add("Room is unreachable from the entrance: " + room.roomId());
            }
        }
        return violations;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.generate.LayoutGeneratorTest'`
Expected: PASS, 11 tests

- [ ] **Step 7: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS — nothing else has changed yet

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/model/Direction.java \
        server/src/main/java/dm/generate/Doorway.java \
        server/src/main/java/dm/generate/RoomNode.java \
        server/src/main/java/dm/generate/DungeonLayout.java \
        server/src/main/java/dm/generate/LayoutGenerator.java \
        server/src/main/java/dm/generate/LayoutValidator.java \
        server/src/test/java/dm/generate/LayoutGeneratorTest.java
git commit -m "Dig a dungeon out of a seed, and prove you can walk all of it"
```

---

### Task 2: Exits, cut into the walls of a room

The layout says room 0 has a door in its north wall. This decides *which square* that door stands in, puts a `DOOR` prop there so the renderer draws one, and teaches the spatial validator the two ways a generated room can ruin a door: stand something solid in it, or wall it off from the inside.

Exits are placed **before** props, so props are placed around them. Placing props first and then hunting for a free wall square is how you end up with a doorway wedged between two braziers.

**Files:**
- Create: `server/src/main/java/dm/model/Exit.java`
- Create: `server/src/main/java/dm/generate/ExitPlacer.java`
- Modify: `server/src/main/java/dm/generate/SpatialValidator.java`
- Modify: `server/src/main/java/dm/generate/PropPlacer.java`
- Modify: `server/src/main/java/dm/generate/RoomGenerator.java`
- Modify: `server/src/main/java/dm/generate/GeneratedRoom.java`
- Modify: `server/src/main/java/dm/generate/RoomDumper.java`
- Modify: `server/src/main/java/dm/content/RoomDefinition.java`
- Test: `server/src/test/java/dm/generate/ExitPlacerTest.java`

**Interfaces:**
- Consumes: `Direction`, `RoomNode`, `Doorway`, `RoomShape`, `GenRandom`, `Prop`, `PropType`, `Square`
- Produces:
  - `Exit(String id, int x, int y, Direction direction, String toRoomId)`, with `square()` and `inward(int width, int height)`
  - `ExitPlacer.place(GenRandom random, RoomShape shape, List<Doorway> doorways)` returning `List<Exit>`
  - `SpatialValidator.check(RoomShape, List<Prop>, List<Exit>, Square)` — the existing three-argument form stays, delegating with no exits
  - `PropPlacer.place(GenRandom, KitDefinition, RoomShape, Square, List<Exit>)` — the existing four-argument form stays, delegating with no exits
  - `RoomGenerator.generate(String kitId, RoomNode node)` — the existing `(String, long)` form stays, generating a room with no exits
  - `GeneratedRoom(String roomId, RoomShape shape, List<Prop> props, List<Exit> exits, Square partyStart, Square goblinSpawn)`
  - `RoomDefinition` gains a `List<Exit> exits` component after `props`, and `exitAt(int x, int y)` returning `Optional<Exit>`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.content.ContentLoader;
import dm.model.Direction;
import dm.model.Exit;
import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import dm.model.WallType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a door stands, and what may not stand in it.
 *
 * <p>Spec §6 names "every exit remains reachable" as one of the four things the validator must
 * assert, and spec §10 asks for exactly the test in {@code propInExitRejected}. A doorway with
 * a pillar in it is six legal values and a room you cannot leave.
 */
class ExitPlacerTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static final RoomShape SHAPE = new RoomShape(
            10, 12, FloorType.STONE, WallType.STONE, LightingPreset.TORCHLIT);

    private static List<Exit> place(long seed, Direction... directions) {
        var doorways = new ArrayList<Doorway>();
        for (var direction : directions) {
            doorways.add(new Doorway(direction, "room-" + direction.name().toLowerCase()));
        }
        return ExitPlacer.place(new GenRandom(seed), SHAPE, doorways);
    }

    @Test
    @DisplayName("a door stands in the wall its doorway names")
    void doorsLandOnTheRightWall() {
        for (long seed = 0; seed < 100; seed++) {
            for (var exit : place(seed, Direction.values())) {
                switch (exit.direction()) {
                    case NORTH -> assertEquals(SHAPE.height() - 1, exit.y(), "seed " + seed);
                    case SOUTH -> assertEquals(0, exit.y(), "seed " + seed);
                    case EAST -> assertEquals(SHAPE.width() - 1, exit.x(), "seed " + seed);
                    case WEST -> assertEquals(0, exit.x(), "seed " + seed);
                }
            }
        }
    }

    @Test
    @DisplayName("a door is never in a corner, because a corner has no inside")
    void doorsAvoidCorners() {
        for (long seed = 0; seed < 100; seed++) {
            for (var exit : place(seed, Direction.values())) {
                boolean cornerX = exit.x() == 0 || exit.x() == SHAPE.width() - 1;
                boolean cornerY = exit.y() == 0 || exit.y() == SHAPE.height() - 1;
                assertFalse(cornerX && cornerY,
                        "seed " + seed + ": a door in the corner at " + exit.x() + "," + exit.y());
            }
        }
    }

    @Test
    @DisplayName("the square inside a door is inside the room")
    void inwardIsInside() {
        for (long seed = 0; seed < 100; seed++) {
            for (var exit : place(seed, Direction.values())) {
                var inward = exit.inward(SHAPE.width(), SHAPE.height());
                assertTrue(inward.x() >= 0 && inward.x() < SHAPE.width(), inward.toString());
                assertTrue(inward.y() >= 0 && inward.y() < SHAPE.height(), inward.toString());
                assertNotEquals(exit.square(), inward, "seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("each door gets its own square, and its own id")
    void doorsDoNotShare() {
        var exits = place(3, Direction.values());

        assertEquals(4, exits.size());
        assertEquals(4, exits.stream().map(Exit::square).distinct().count());
        assertEquals(4, exits.stream().map(Exit::id).distinct().count());
    }

    @Test
    @DisplayName("a solid prop in a doorway is a violation")
    void propInExitRejected() {
        var exit = new Exit("door-north", 5, SHAPE.height() - 1, Direction.NORTH, "room-1");
        var props = List.of(new Prop("pillar-0", PropType.PILLAR, 5, SHAPE.height() - 1, 0, false));

        var violations = SpatialValidator.check(SHAPE, props, List.of(exit), new Square(5, 0));

        assertTrue(violations.stream().anyMatch(v -> v.contains("exit")), violations.toString());
    }

    @Test
    @DisplayName("a doorway walled off from the inside is a violation")
    void sealedExitRejected() {
        // Nothing stands in the doorway itself; the three squares that lead to it are taken.
        var exit = new Exit("door-north", 5, SHAPE.height() - 1, Direction.NORTH, "room-1");
        var props = List.of(
                new Prop("pillar-0", PropType.PILLAR, 4, SHAPE.height() - 2, 0, false),
                new Prop("pillar-1", PropType.PILLAR, 5, SHAPE.height() - 2, 0, false),
                new Prop("pillar-2", PropType.PILLAR, 6, SHAPE.height() - 2, 0, false),
                new Prop("pillar-3", PropType.PILLAR, 4, SHAPE.height() - 1, 0, false),
                new Prop("pillar-4", PropType.PILLAR, 6, SHAPE.height() - 1, 0, false));

        var violations = SpatialValidator.check(SHAPE, props, List.of(exit), new Square(5, 0));

        assertTrue(violations.stream().anyMatch(v -> v.contains("reach")), violations.toString());
    }

    @Test
    @DisplayName("an exit on the wrong wall is a violation")
    void misplacedExitRejected() {
        var exit = new Exit("door-north", 5, 5, Direction.NORTH, "room-1");

        var violations = SpatialValidator.check(SHAPE, List.of(), List.of(exit), new Square(5, 0));

        assertTrue(violations.stream().anyMatch(v -> v.contains("wall")), violations.toString());
    }

    @Test
    @DisplayName("a generated room puts a walkable door in every wall the layout asked for")
    void generatedRoomsCarryTheirExits() {
        var generator = new RoomGenerator(CONTENT);

        for (long seed = 0; seed < 100; seed++) {
            var node = new RoomNode("room-0", 0, 0, seed, List.of(
                    new Doorway(Direction.NORTH, "room-1"),
                    new Doorway(Direction.WEST, "room-2")));

            var room = generator.generate("crypt", node);

            assertEquals(2, room.exits().size(), "seed " + seed);
            for (var exit : room.exits()) {
                var door = room.props().stream()
                        .filter(p -> p.id().equals(exit.id()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "seed " + seed + ": no door prop for " + exit.id()));
                assertEquals(PropType.DOOR, door.type());
                assertEquals(exit.x(), door.x());
                assertEquals(exit.y(), door.y());
                assertEquals(exit.direction().facing(), door.rotation());
            }
            assertEquals(List.of(),
                    SpatialValidator.check(room.shape(), room.props(), room.exits(),
                            room.partyStart()),
                    "seed " + seed + " generated an illegal room");
        }
    }

    @Test
    @DisplayName("a room generated without a layout has no exits, exactly as before")
    void singleRoomsStillHaveNone() {
        var room = new RoomGenerator(CONTENT).generate("crypt", 12);

        assertEquals(List.of(), room.exits());
        assertTrue(room.props().stream().noneMatch(p -> p.type() == PropType.DOOR));
    }

    @Test
    @DisplayName("exits reach the room definition, and can be found by square")
    void exitsReachTheDefinition() {
        var node = new RoomNode("room-0", 0, 0, 21, List.of(new Doorway(Direction.EAST, "room-1")));
        var generated = new RoomGenerator(CONTENT).generate("crypt", node);

        var definition = generated.toRoomDefinition();
        var exit = generated.exits().getFirst();

        assertEquals(List.of(exit), definition.exits());
        assertEquals(exit, definition.exitAt(exit.x(), exit.y()).orElseThrow());
        assertTrue(definition.exitAt(exit.x(), exit.y() == 0 ? 1 : 0).isEmpty());
    }

    @Test
    @DisplayName("the authored crypt has no exits and does not explode")
    void authoredRoomHasNone() {
        assertEquals(List.of(), CONTENT.room("crypt").exits());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.ExitPlacerTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Exit`

- [ ] **Step 3: Write the `Exit` record**

`server/src/main/java/dm/model/Exit.java`:

```java
package dm.model;

/**
 * The edge between two scenes.
 *
 * <p>{@code Exit} rather than {@code Door}, deliberately, and ahead of anything that needs the
 * distinction (spec §4). The edge is a door in a crypt and a road out of a village, a path over
 * a pass, a ford across a river. M1 only ever builds doors; the name costs nothing today and is
 * a painful retrofit later. Same class of decision as {@code RollResult.faces} being a list from
 * day one.
 *
 * @param id         the id of the {@code DOOR} prop standing in this square, so the renderer and
 *                   the DM address one thing rather than two
 * @param direction  the wall this is cut into, and the way you go through it
 * @param toRoomId   the scene on the other side
 */
public record Exit(String id, int x, int y, Direction direction, String toRoomId) {

    public Square square() {
        return new Square(x, y);
    }

    /**
     * The square one step inside the room, where a party arriving through this door lands.
     *
     * <p>Not the doorway itself: standing in a door you have just come through is standing on
     * the trigger that sent you here, and the player would bounce straight back out.
     */
    public Square inward(int width, int height) {
        return new Square(
                Math.clamp(x - direction.dx(), 0, width - 1),
                Math.clamp(y - direction.dy(), 0, height - 1));
    }
}
```

- [ ] **Step 4: Write the placer**

`server/src/main/java/dm/generate/ExitPlacer.java`:

```java
package dm.generate;

import dm.model.Direction;
import dm.model.Exit;
import dm.model.Square;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Turns a room's doorways into squares in its walls.
 *
 * <p>Corners are excluded. A door in a corner belongs to two walls at once, and the square
 * "inside" it — the one a party arriving lands on — is a diagonal, which is a step no arrival
 * should have to take. Excluding four squares from a perimeter of forty costs nothing.
 *
 * <p>Runs before {@code PropPlacer}, so the props are arranged around the doors rather than the
 * doors squeezed in among the props. The kit never lists {@code DOOR}: a door is not decoration
 * the generator may choose to add, it is the layout made physical.
 */
public final class ExitPlacer {

    private ExitPlacer() {
    }

    public static List<Exit> place(GenRandom random, RoomShape shape, List<Doorway> doorways) {
        var exits = new ArrayList<Exit>();
        var taken = new HashSet<Square>();

        for (var doorway : doorways) {
            var options = wall(shape, doorway.direction()).stream()
                    .filter(square -> !taken.contains(square))
                    .toList();
            if (options.isEmpty()) {
                // Only reachable if a kit ever allows a room narrower than three squares, which
                // would have no non-corner wall at all. Skipping is wrong but survivable; the
                // layout validator's reciprocity check is what would catch the consequence.
                throw new IllegalStateException("No wall square for a "
                        + doorway.direction() + " door in a " + shape.width() + "x"
                        + shape.height() + " room");
            }
            var square = random.pick(options);
            taken.add(square);
            exits.add(new Exit(
                    "door-" + doorway.direction().name().toLowerCase(),
                    square.x(),
                    square.y(),
                    doorway.direction(),
                    doorway.toRoomId()));
        }
        return List.copyOf(exits);
    }

    /** A wall's squares, corners excluded. */
    private static List<Square> wall(RoomShape shape, Direction direction) {
        var squares = new ArrayList<Square>();
        int w = shape.width();
        int h = shape.height();

        switch (direction) {
            case NORTH -> {
                for (int x = 1; x < w - 1; x++) {
                    squares.add(new Square(x, h - 1));
                }
            }
            case SOUTH -> {
                for (int x = 1; x < w - 1; x++) {
                    squares.add(new Square(x, 0));
                }
            }
            case EAST -> {
                for (int y = 1; y < h - 1; y++) {
                    squares.add(new Square(w - 1, y));
                }
            }
            case WEST -> {
                for (int y = 1; y < h - 1; y++) {
                    squares.add(new Square(0, y));
                }
            }
        }
        return squares;
    }
}
```

- [ ] **Step 5: Teach the spatial validator about exits**

In `server/src/main/java/dm/generate/SpatialValidator.java`, add the `dm.model.Exit` and `dm.model.Direction` imports, replace the existing `check` method with the pair below, and replace the `unreachable` method with the two that follow. Everything else in the file is unchanged.

```java
    /** The existing three-argument form: a room with no way out of it, which is every M1 test. */
    public static List<String> check(RoomShape shape, List<Prop> props, Square partyStart) {
        return check(shape, props, List.of(), partyStart);
    }

    public static List<String> check(RoomShape shape, List<Prop> props, List<Exit> exits,
                                     Square partyStart) {
        var violations = new ArrayList<String>();
        var occupied = new HashSet<Square>();
        var ids = new HashSet<String>();

        for (var prop : props) {
            if (!ids.add(prop.id())) {
                violations.add("Duplicate prop id: " + prop.id());
            }
            if (prop.x() < 0 || prop.x() >= shape.width()
                    || prop.y() < 0 || prop.y() >= shape.height()) {
                violations.add("Prop off the grid: " + prop.id()
                        + " at " + prop.x() + "," + prop.y());
                continue;
            }
            var square = new Square(prop.x(), prop.y());
            if (!occupied.add(square)) {
                violations.add("Two props share a square at " + prop.x() + "," + prop.y());
            }
            if (square.equals(partyStart) && prop.type().blocksMovement()) {
                violations.add("A solid prop stands on the party start square at "
                        + prop.x() + "," + prop.y());
            }
        }

        var reached = reachableFrom(shape, props, partyStart);
        violations.addAll(unreachable(shape, props, reached));
        violations.addAll(blockedExits(shape, props, exits, reached));
        return List.copyOf(violations);
    }
```

```java
    /**
     * Every square the party can walk to from where it starts.
     *
     * <p>Chebyshev flood fill, matching {@code Entity.isAdjacentTo} and the movement rules —
     * two distance metrics in one game is how "why can it not walk there" bugs start.
     */
    private static Set<Square> reachableFrom(RoomShape shape, List<Prop> props, Square partyStart) {
        var blocked = blocked(props);

        var reached = new HashSet<Square>();
        var queue = new ArrayDeque<Square>();
        if (!blocked.contains(partyStart)) {
            reached.add(partyStart);
            queue.add(partyStart);
        }

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var next = new Square(current.x() + dx, current.y() + dy);
                    if (next.x() < 0 || next.x() >= shape.width()
                            || next.y() < 0 || next.y() >= shape.height()) {
                        continue;
                    }
                    if (blocked.contains(next) || !reached.add(next)) {
                        continue;
                    }
                    queue.add(next);
                }
            }
        }
        return reached;
    }

    /** Every open square must be walkable from the party's start. */
    private static List<String> unreachable(RoomShape shape, List<Prop> props,
                                            Set<Square> reached) {
        var blocked = blocked(props);
        var violations = new ArrayList<String>();

        for (int x = 0; x < shape.width(); x++) {
            for (int y = 0; y < shape.height(); y++) {
                var square = new Square(x, y);
                if (!blocked.contains(square) && !reached.contains(square)) {
                    violations.add("Square is unreachable from the party start: " + x + "," + y);
                }
            }
        }
        return violations;
    }

    /**
     * Spec §6's first named rule: every exit remains reachable.
     *
     * <p>Two ways to break a door and they fail differently. Standing a pillar in the doorway is
     * loud — the square is occupied and the prop is visibly wrong. Sealing it from the inside is
     * quiet: every prop is somewhere sensible, the door is drawn, it is simply behind a wall of
     * furniture. The second is the one that reaches a player.
     */
    private static List<String> blockedExits(RoomShape shape, List<Prop> props, List<Exit> exits,
                                             Set<Square> reached) {
        var violations = new ArrayList<String>();

        for (var exit : exits) {
            if (!onNamedWall(shape, exit)) {
                violations.add("An exit is not on the wall it names: " + exit.id()
                        + " faces " + exit.direction() + " at " + exit.x() + "," + exit.y());
                continue;
            }
            boolean stopped = props.stream().anyMatch(p ->
                    p.x() == exit.x() && p.y() == exit.y() && p.type().blocksMovement());
            if (stopped) {
                violations.add("A solid prop stands in the exit at " + exit.x() + "," + exit.y());
            }
            if (!reached.contains(exit.square())) {
                violations.add("The exit at " + exit.x() + "," + exit.y()
                        + " cannot be reached from the party start");
            }
        }
        return violations;
    }

    private static boolean onNamedWall(RoomShape shape, Exit exit) {
        return switch (exit.direction()) {
            case NORTH -> exit.y() == shape.height() - 1;
            case SOUTH -> exit.y() == 0;
            case EAST -> exit.x() == shape.width() - 1;
            case WEST -> exit.x() == 0;
        };
    }

    private static Set<Square> blocked(List<Prop> props) {
        var blocked = new HashSet<Square>();
        for (var prop : props) {
            if (prop.type().blocksMovement()) {
                blocked.add(new Square(prop.x(), prop.y()));
            }
        }
        return blocked;
    }
```

- [ ] **Step 6: Keep props out of doorways**

In `server/src/main/java/dm/generate/PropPlacer.java`, add the `dm.model.Exit` import and replace `place`, `choose`, `firstWalkable` and `free` with the versions below. Nothing else in the file changes.

```java
    /** The existing form: a room with no way out, which is every single-room generation. */
    public static List<Prop> place(GenRandom random, KitDefinition kit, RoomShape shape,
                                   Square partyStart) {
        return place(random, kit, shape, partyStart, List.of());
    }

    public static List<Prop> place(GenRandom random, KitDefinition kit, RoomShape shape,
                                   Square partyStart, List<Exit> exits) {
        var placed = new ArrayList<Prop>();
        var taken = new HashSet<Square>();
        // Nothing shares a square with a doorway — not even something you could walk through.
        // An alcove in a door is two things claiming one hole in one wall, and the renderer
        // draws both.
        for (var exit : exits) {
            taken.add(exit.square());
        }

        for (var entry : kit.props()) {
            int max = entry.unique() ? Math.min(1, entry.maxCount()) : entry.maxCount();
            int count = random.between(entry.minCount(), max);

            for (int i = 0; i < count; i++) {
                var square = choose(random, shape, taken, partyStart, entry.type(), placed, exits);
                if (square == null) {
                    continue;
                }
                taken.add(square);
                placed.add(new Prop(
                        entry.type().name().toLowerCase() + "-" + i,
                        entry.type(),
                        square.x(),
                        square.y(),
                        facing(random, shape, square, partyStart, entry.type()),
                        false));
            }
        }
        return List.copyOf(placed);
    }
```

```java
    private static Square choose(GenRandom random, RoomShape shape, Set<Square> taken,
                                 Square partyStart, PropType type, List<Prop> placed,
                                 List<Exit> exits) {
        var preferred = free(candidates(shape, partyStart, type, placed), taken, partyStart, type);
        var square = firstWalkable(random, shape, partyStart, type, placed, preferred, exits);
        if (square != null) {
            return square;
        }
        var anywhere = free(allSquares(shape), taken, partyStart, type);
        return firstWalkable(random, shape, partyStart, type, placed, anywhere, exits);
    }

    private static Square firstWalkable(GenRandom random, RoomShape shape, Square partyStart,
                                        PropType type, List<Prop> placed, List<Square> options,
                                        List<Exit> exits) {
        if (options.isEmpty()) {
            return null;
        }
        int start = random.between(0, options.size() - 1);
        for (int i = 0; i < options.size(); i++) {
            var candidate = options.get((start + i) % options.size());
            if (!type.blocksMovement()) {
                return candidate;
            }
            var trial = new ArrayList<>(placed);
            trial.add(new Prop("trial", type, candidate.x(), candidate.y(), 0, false));
            // The exits go in, so a prop that seals a doorway is refused here rather than
            // costing the generator a whole reseed.
            if (SpatialValidator.check(shape, trial, exits, partyStart).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }
```

`free` is unchanged from its current form; the exit squares are already in `taken` before the first call.

- [ ] **Step 7: Generate a room from a layout node**

Replace `server/src/main/java/dm/generate/RoomGenerator.java` with:

```java
package dm.generate;

import dm.content.ContentLoader;
import dm.model.Exit;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole deterministic half of generation, behind one call.
 *
 * <p>An illegal arrangement is reseeded rather than repaired. Repair means writing a second
 * placer that undoes the first one's decisions, and the two would disagree the moment either
 * changed; a fresh seed is a handful of microseconds and cannot introduce a case the validator
 * has not already seen.
 *
 * <p>Order matters and is the one thing not to rearrange: shape, then exits, then props. Doors
 * are the layout made physical and everything else is arranged around them.
 */
public final class RoomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RoomGenerator.class);

    /** A kit that cannot produce a legal room in this many tries is a broken kit, not bad luck. */
    private static final int MAX_ATTEMPTS = 50;

    private final ContentLoader content;

    public RoomGenerator(ContentLoader content) {
        this.content = content;
    }

    /** One room, standing on its own, with no way out. The shape M1's first plan generated. */
    public GeneratedRoom generate(String kitId, long seed) {
        return generate(kitId, new RoomNode("generated-" + seed, 0, 0, seed, List.of()));
    }

    public GeneratedRoom generate(String kitId, RoomNode node) {
        var kit = content.kit(kitId);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long attemptSeed = node.seed() + attempt;
            var random = new GenRandom(attemptSeed);
            var shape = ShapeGenerator.generate(random, kit);

            var exits = ExitPlacer.place(random, shape, node.doorways());
            var partyStart = start(shape, exits);
            var props = withDoors(PropPlacer.place(random, kit, shape, partyStart, exits), exits);

            var violations = SpatialValidator.check(shape, props, exits, partyStart);
            if (!violations.isEmpty()) {
                log.debug("seed {} rejected: {}", attemptSeed, violations);
                continue;
            }

            var goblinSpawn = spawnAwayFrom(shape, props, exits, partyStart);
            if (goblinSpawn == null) {
                continue;
            }
            return new GeneratedRoom(
                    node.roomId(), shape, props, exits, partyStart, goblinSpawn);
        }
        throw new IllegalStateException(
                "Kit '" + kitId + "' produced no legal room in " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Where the party stands in a room generated on its own.
     *
     * <p>A real arrival overrides this — {@code GameEngine.enterRoom} lands the party inside
     * whichever door it came through. What this square is actually for is generation: the
     * validator floods from it, and {@code PropPlacer} puts the sarcophagus in the half of the
     * room away from it.
     */
    private static Square start(RoomShape shape, List<Exit> exits) {
        var middle = new Square(shape.width() / 2, 0);
        boolean inADoorway = exits.stream().anyMatch(e -> e.square().equals(middle));
        return inADoorway ? new Square(shape.width() / 2, 1) : middle;
    }

    /** A door prop for every exit, so the renderer has something to draw and the DM to describe. */
    private static List<Prop> withDoors(List<Prop> props, List<Exit> exits) {
        var all = new ArrayList<>(props);
        for (var exit : exits) {
            all.add(new Prop(exit.id(), PropType.DOOR, exit.x(), exit.y(),
                    exit.direction().facing(), false));
        }
        return List.copyOf(all);
    }

    /** The far half of the room, so a hostile does not arrive in the player's lap. */
    private static Square spawnAwayFrom(RoomShape shape, List<Prop> props, List<Exit> exits,
                                        Square partyStart) {
        for (int row = shape.height() - 1; row >= shape.height() / 2; row--) {
            for (int column = 0; column < shape.width(); column++) {
                // Copied into finals: a for-loop variable is not effectively final and cannot
                // be captured by the lambda below.
                final int x = column;
                final int y = row;

                var square = new Square(x, y);
                if (square.equals(partyStart)) {
                    continue;
                }
                // A goblin standing in a doorway is standing on the way out of the room, which
                // reads as a deliberate blockade the engine has no rule for.
                if (exits.stream().anyMatch(e -> e.square().equals(square))) {
                    continue;
                }
                boolean blocked = props.stream().anyMatch(p ->
                        p.x() == x && p.y() == y && p.type().blocksMovement());
                if (!blocked) {
                    return square;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 8: Carry exits through to the room definition**

In `server/src/main/java/dm/generate/GeneratedRoom.java`: add `import dm.model.Exit;`, add `List<Exit> exits` as a component between `props` and `partyStart`, and pass `exits` as the new argument in both `new RoomDefinition(...)` calls (between the prop definitions and the start positions). The record header becomes:

```java
public record GeneratedRoom(
        String roomId,
        RoomShape shape,
        List<Prop> props,
        List<Exit> exits,
        Square partyStart,
        Square goblinSpawn
) {
```

and each `new RoomDefinition(...)` gains `exits` after its prop list — in `toRoomDefinition()`:

```java
        return new RoomDefinition(
                roomId,
                "An Unnamed Chamber",
                shape.width(),
                shape.height(),
                shape.floorType(),
                shape.wallType(),
                shape.lighting(),
                definitions,
                exits,
                new RoomDefinition.StartPositions(
                        List.of(new RoomDefinition.Point(partyStart.x(), partyStart.y())),
                        new RoomDefinition.Point(goblinSpawn.x(), goblinSpawn.y())),
                new RoomDefinition.DmNotes(UNDRESSED, UNDRESSED, UNDRESSED, UNDRESSED, UNDRESSED));
```

and in `toRoomDefinition(Dressing dressing)`:

```java
        return new RoomDefinition(
                plain.roomId(),
                dressing.name(),
                plain.width(),
                plain.height(),
                plain.floorType(),
                plain.wallType(),
                plain.lighting(),
                definitions,
                plain.exits(),
                plain.startPositions(),
                new RoomDefinition.DmNotes(
                        dressing.overview(), dressing.sensory(), null, null, null));
```

In `server/src/main/java/dm/content/RoomDefinition.java`: add `import dm.model.Exit;` and `import java.util.Optional;`, add the `List<Exit> exits` component after `props`, and add the compact constructor and lookup:

```java
    /**
     * A hand-authored room has no {@code exits} key, so Jackson hands this null. An empty list
     * is the honest reading — the crypt genuinely has no way out, which is the whole of its
     * north-door note — and it saves every caller a null check.
     */
    public RoomDefinition {
        exits = exits == null ? List.of() : List.copyOf(exits);
    }

    /** The way out of this square, if this square is one. */
    public Optional<Exit> exitAt(int x, int y) {
        return exits.stream().filter(e -> e.x() == x && e.y() == y).findFirst();
    }
```

- [ ] **Step 9: Show the doors in the dump**

In `server/src/main/java/dm/generate/RoomDumper.java`, extend the header with an exits line and add the door to the legend. Replace the body of `dump` down to the loop with:

```java
    public static String dump(GeneratedRoom room) {
        var out = new StringBuilder();
        out.append(room.roomId())
                .append("  ").append(room.shape().width()).append('x').append(room.shape().height())
                .append("  ").append(room.shape().floorType())
                .append(" / ").append(room.shape().wallType())
                .append(" / ").append(room.shape().lighting())
                .append('\n');

        if (!room.exits().isEmpty()) {
            out.append("exits: ")
                    .append(room.exits().stream()
                            .map(e -> e.direction().name().toLowerCase() + " -> " + e.toRoomId())
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append('\n');
        }
```

and change the legend line at the end to:

```java
        out.append("@ party  g goblin  S sarcophagus  b brazier  | pillar  % rubble  "
                + "a alcove  + door\n");
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd server && ./gradlew test`
Expected: PASS — the new class plus every existing generation test, which compile unchanged because the old signatures are still there

- [ ] **Step 11: Look at one by hand**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomDumperTest' --info 2>&1 | head -40`
Expected: a grid with `+` on at least one wall in the rooms that have doorways. If a `+` ever appears in a corner, `ExitPlacer.wall` is wrong.

- [ ] **Step 12: Commit**

```bash
git add server/src/main/java/dm/model/Exit.java \
        server/src/main/java/dm/generate/ExitPlacer.java \
        server/src/main/java/dm/generate/SpatialValidator.java \
        server/src/main/java/dm/generate/PropPlacer.java \
        server/src/main/java/dm/generate/RoomGenerator.java \
        server/src/main/java/dm/generate/GeneratedRoom.java \
        server/src/main/java/dm/generate/RoomDumper.java \
        server/src/main/java/dm/content/RoomDefinition.java \
        server/src/test/java/dm/generate/ExitPlacerTest.java
git commit -m "Cut a door into the wall, and refuse to stand anything in it"
```

---

### Task 3: Look at the whole dungeon

Spec §3's debug view: a terminal dump of a generated dungeon, for fast iteration. The same argument as `RoomDumper` — judging whether a dungeon is a place should not cost a browser reload, and the shape of a dungeon is legible from a grid of characters.

This needs no model and no key, so it runs on any machine and in any state.

**Files:**
- Create: `server/src/main/java/dm/generate/DungeonDumper.java`
- Modify: `server/src/main/java/dm/App.java`
- Test: `server/src/test/java/dm/generate/DungeonDumperTest.java`

**Interfaces:**
- Consumes: `DungeonLayout`, `RoomNode`, `Doorway`, `Direction`
- Produces: `DungeonDumper.dump(DungeonLayout)` returning a `String`
- `App` gains `--dump <seed>`: prints the layout and every room's grid, then exits without starting a server

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.model.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DungeonDumperTest {

    @Test
    @DisplayName("two rooms side by side are drawn joined")
    void horizontalLink() {
        var layout = new DungeonLayout(1, List.of(
                new RoomNode("room-0", 0, 0, 1, List.of(new Doorway(Direction.EAST, "room-1"))),
                new RoomNode("room-1", 1, 0, 2, List.of(new Doorway(Direction.WEST, "room-0")))));

        var dump = DungeonDumper.dump(layout);

        assertTrue(dump.contains("[ 0]-[ 1]"), dump);
    }

    @Test
    @DisplayName("two rooms stacked are drawn joined")
    void verticalLink() {
        var layout = new DungeonLayout(1, List.of(
                new RoomNode("room-0", 0, 0, 1, List.of(new Doorway(Direction.NORTH, "room-1"))),
                new RoomNode("room-1", 0, 1, 2, List.of(new Doorway(Direction.SOUTH, "room-0")))));

        var dump = DungeonDumper.dump(layout);

        assertTrue(dump.contains("|"), dump);
        // North is up: the room at the higher y prints first.
        assertTrue(dump.indexOf("[ 1]") < dump.indexOf("[ 0]"), dump);
    }

    @Test
    @DisplayName("every room is named, with where it is and where it leads")
    void listsEveryRoom() {
        var layout = LayoutGenerator.generate(7, 8);

        var dump = DungeonDumper.dump(layout);

        for (var room : layout.rooms()) {
            assertTrue(dump.contains(room.roomId()), "no line for " + room.roomId() + ":\n" + dump);
        }
    }

    @Test
    @DisplayName("the dump is as deterministic as the dungeon it draws")
    void deterministic() {
        assertEquals(DungeonDumper.dump(LayoutGenerator.generate(7, 8)),
                DungeonDumper.dump(LayoutGenerator.generate(7, 8)));
    }

    @Test
    @DisplayName("a one-room dungeon draws one room and no links")
    void singleRoom() {
        var dump = DungeonDumper.dump(LayoutGenerator.generate(4, 1));

        assertTrue(dump.contains("[ 0]"), dump);
        assertFalse(dump.contains("-["), dump);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.DungeonDumperTest'`
Expected: FAIL — compilation error, `cannot find symbol: class DungeonDumper`

- [ ] **Step 3: Write the dumper**

`server/src/main/java/dm/generate/DungeonDumper.java`:

```java
package dm.generate;

import dm.model.Direction;

import java.util.HashMap;
import java.util.Map;

/**
 * A dungeon's topology as text.
 *
 * <p>Spec §3's debug view. The room dump answers "is this a place"; this answers "is this a
 * dungeon" — whether it branches, whether it loops, whether every seed produces the same
 * lopsided caterpillar. Those are judgements you make by looking at twenty of them, which
 * means the looking has to be cheap.
 */
public final class DungeonDumper {

    /** Four for the label, one for a horizontal link. */
    private static final int PITCH = 5;

    /** Where the '|' goes under a label: {@code [ 0]} has its digit at offset 2. */
    private static final int STEM = 2;

    private DungeonDumper() {
    }

    public static String dump(DungeonLayout layout) {
        var out = new StringBuilder();
        out.append("seed ").append(layout.seed())
                .append(" — ").append(layout.rooms().size()).append(" rooms\n\n");

        out.append(map(layout));

        out.append('\n');
        for (var room : layout.rooms()) {
            out.append(room.roomId())
                    .append("  (").append(room.gridX()).append(',').append(room.gridY())
                    .append(")  ");
            if (room.doorways().isEmpty()) {
                out.append("no way out");
            } else {
                out.append("exits: ");
                for (int i = 0; i < room.doorways().size(); i++) {
                    var doorway = room.doorways().get(i);
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(doorway.direction().name().toLowerCase())
                            .append(" -> ").append(doorway.toRoomId());
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The cell grid, north up, one row of rooms and one row of links each. */
    private static String map(DungeonLayout layout) {
        int minX = layout.rooms().stream().mapToInt(RoomNode::gridX).min().orElse(0);
        int maxX = layout.rooms().stream().mapToInt(RoomNode::gridX).max().orElse(0);
        int minY = layout.rooms().stream().mapToInt(RoomNode::gridY).min().orElse(0);
        int maxY = layout.rooms().stream().mapToInt(RoomNode::gridY).max().orElse(0);

        var byCell = new HashMap<String, RoomNode>();
        for (var room : layout.rooms()) {
            byCell.put(room.gridX() + "," + room.gridY(), room);
        }

        int width = (maxX - minX + 1) * PITCH;
        var out = new StringBuilder();

        // North up, matching RoomDumper: printed the other way the map is a mirror of the game.
        for (int y = maxY; y >= minY; y--) {
            out.append(rowOfRooms(byCell, minX, maxX, y, width)).append('\n');
            if (y > minY) {
                out.append(rowOfLinks(byCell, minX, maxX, y, width)).append('\n');
            }
        }
        return out.toString();
    }

    private static String rowOfRooms(Map<String, RoomNode> byCell, int minX, int maxX, int y,
                                     int width) {
        var line = blank(width);
        for (int x = minX; x <= maxX; x++) {
            var room = byCell.get(x + "," + y);
            if (room == null) {
                continue;
            }
            int at = (x - minX) * PITCH;
            write(line, at, label(room));
            if (room.doorway(Direction.EAST).isPresent()) {
                line[at + 4] = '-';
            }
        }
        return trailingTrimmed(line);
    }

    private static String rowOfLinks(Map<String, RoomNode> byCell, int minX, int maxX, int y,
                                     int width) {
        var line = blank(width);
        for (int x = minX; x <= maxX; x++) {
            var room = byCell.get(x + "," + y);
            if (room != null && room.doorway(Direction.SOUTH).isPresent()) {
                line[(x - minX) * PITCH + STEM] = '|';
            }
        }
        return trailingTrimmed(line);
    }

    /** Two characters of index, so a forty-room dungeon still lines up. */
    private static String label(RoomNode room) {
        String index = room.roomId().startsWith("room-")
                ? room.roomId().substring("room-".length())
                : room.roomId();
        return "[%2s]".formatted(index.length() > 2 ? index.substring(index.length() - 2) : index);
    }

    private static char[] blank(int width) {
        var line = new char[width];
        java.util.Arrays.fill(line, ' ');
        return line;
    }

    private static void write(char[] line, int at, String text) {
        for (int i = 0; i < text.length() && at + i < line.length; i++) {
            line[at + i] = text.charAt(i);
        }
    }

    private static String trailingTrimmed(char[] line) {
        return new String(line).stripTrailing();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.generate.DungeonDumperTest'`
Expected: PASS, 5 tests

- [ ] **Step 5: Add the `--dump` flag**

In `server/src/main/java/dm/App.java`, add the imports `dm.generate.DungeonDumper` and `dm.generate.LayoutGenerator`, add the constant, and insert the dump branch immediately after `var content = new ContentLoader();`:

```java
    /** How many rooms a generated dungeon has. Spec's gate is three; eight leaves room to wander. */
    private static final int DUNGEON_ROOMS = 8;
```

```java
        // --dump <seed> prints a dungeon and exits. No key, no server, no browser — the loop
        // that decides whether generation is working should cost one command.
        for (int i = 0; i < args.length - 1; i++) {
            if ("--dump".equals(args[i])) {
                dumpDungeon(content, Long.parseLong(args[i + 1]));
                return;
            }
        }
```

and add the method beside `main`:

```java
    /**
     * The layout, then every room in it.
     *
     * <p>Undressed on purpose. This is the view for judging layout, and a dress pass would put
     * eight model calls and thirty seconds between the command and the answer.
     */
    private static void dumpDungeon(ContentLoader content, long seed) {
        var layout = LayoutGenerator.generate(seed, DUNGEON_ROOMS);
        var violations = LayoutValidator.check(layout);
        if (!violations.isEmpty()) {
            System.out.println("ILLEGAL LAYOUT: " + violations);
        }
        System.out.println(DungeonDumper.dump(layout));

        var generator = new RoomGenerator(content);
        for (var node : layout.rooms()) {
            System.out.println(RoomDumper.dump(generator.generate("crypt", node)));
        }
    }
```

Add `import dm.generate.LayoutValidator;` alongside the others.

- [ ] **Step 6: Look at three dungeons**

```bash
cd server && ./gradlew run -q --args='--dump 7'
```

Expected: a map, then eight room grids each with a `+` on the walls its exits list. Repeat with `--dump 8` and `--dump 9`. What you are judging: do the maps differ in shape, and does any room read as a corridor of doors with nothing in it. If every dungeon is a straight line, `LOOP_CHANCE` and the frontier walk are the dials.

- [ ] **Step 7: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/generate/DungeonDumper.java \
        server/src/test/java/dm/generate/DungeonDumperTest.java \
        server/src/main/java/dm/App.java
git commit -m "Print the whole dungeon, so a seed can be judged in one command"
```

---

### Task 4: The dungeon, dressed once and kept

Spec §7's split, in one class. The layout is regenerated from the seed and never stored. The dress pass **cannot** be regenerated — "a flooded reliquary" does not come back out of a number — so it is durable data from the moment it exists, and a room the party returns to must be the room they left. Regenerating a room's identity on re-entry is the generated-content version of the DM contradicting itself.

`RoomProvider` is the seam that keeps the crypt working. Every existing test constructs a `GameEngine` around one `RoomDefinition`, and none of them should have to know a dungeon exists.

**Files:**
- Create: `server/src/main/java/dm/generate/RoomProvider.java`
- Create: `server/src/main/java/dm/generate/SingleRoom.java`
- Create: `server/src/main/java/dm/generate/Dungeon.java`
- Test: `server/src/test/java/dm/generate/DungeonTest.java`

**Interfaces:**
- Consumes: `ContentLoader`, `RoomDresser`, `DungeonLayout`, `LayoutGenerator`, `LayoutValidator`, `RoomGenerator`, `RoomDumper`, `RoomDefinition`
- Produces:
  - `RoomProvider` — `String entranceRoomId()`, `RoomDefinition room(String roomId)`
  - `SingleRoom(RoomDefinition room) implements RoomProvider`
  - `Dungeon implements RoomProvider` — `Dungeon.generate(ContentLoader, RoomDresser, String kitId, long seed, int roomCount)`, plus `layout()` and `isVisited(String)`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7: layout is regenerated, dressing is retained.
 *
 * <p>The second of those is the one with teeth. A dress pass is an LLM writing prose, so asking
 * for the same room twice gets two different rooms — and the player walking back through a door
 * into somewhere they have never been is the gate metric failing in the most obvious way there
 * is.
 */
class DungeonTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static String dressing(String name) {
        return """
                { "name": "%s", "overview": "A burial chamber.",
                  "sensory": "Cold air.", "props": {} }
                """.formatted(name);
    }

    @Test
    @DisplayName("the layout comes back identical from the same seed, because it is never stored")
    void layoutIsRegenerated() {
        var first = Dungeon.generate(CONTENT, null, "crypt", 7, 8);
        var second = Dungeon.generate(CONTENT, null, "crypt", 7, 8);

        assertEquals(first.layout(), second.layout());
    }

    @Test
    @DisplayName("a room is dressed once and kept, however often it is asked for")
    void dressedOnce() {
        var client = new ScriptedDmClient(dressing("The Weeping Vault"), dressing("Somewhere Else"));
        var dungeon = Dungeon.generate(CONTENT,
                new RoomDresser(client, CONTENT.prompt("dress-room")), "crypt", 7, 8);
        var entrance = dungeon.entranceRoomId();

        var first = dungeon.room(entrance);
        var second = dungeon.room(entrance);

        assertEquals("The Weeping Vault", first.name());
        assertSame(first, second);
        assertEquals(1, client.conversations().size(),
                "the room was dressed twice — the second visit is a different room");
    }

    @Test
    @DisplayName("two rooms get two dress passes")
    void everyRoomGetsItsOwn() {
        var client = new ScriptedDmClient(dressing("The Weeping Vault"), dressing("The Ossuary"));
        var dungeon = Dungeon.generate(CONTENT,
                new RoomDresser(client, CONTENT.prompt("dress-room")), "crypt", 7, 8);

        var first = dungeon.room("room-0");
        var second = dungeon.room("room-1");

        assertEquals("The Weeping Vault", first.name());
        assertEquals("The Ossuary", second.name());
        assertEquals(2, client.conversations().size());
    }

    @Test
    @DisplayName("a room is not visited until it has been asked for")
    void visitedTracksWhatWasBuilt() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);

        assertFalse(dungeon.isVisited("room-3"));
        dungeon.room("room-3");
        assertTrue(dungeon.isVisited("room-3"));
    }

    @Test
    @DisplayName("with no dresser the rooms are undressed and still playable")
    void undressedWithoutADresser() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);

        var room = dungeon.room(dungeon.entranceRoomId());

        assertEquals("An Unnamed Chamber", room.name());
        assertFalse(room.props().isEmpty());
    }

    @Test
    @DisplayName("the entrance is a room the dungeon actually has")
    void entranceExists() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);

        assertEquals(dungeon.layout().entrance().roomId(), dungeon.entranceRoomId());
        assertNotNull(dungeon.room(dungeon.entranceRoomId()));
    }

    @Test
    @DisplayName("a room the dungeon does not have is refused, not invented")
    void unknownRoomRefused() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);

        assertThrows(IllegalArgumentException.class, () -> dungeon.room("room-99"));
    }

    @Test
    @DisplayName("one authored room is a provider too")
    void singleRoomProvides() {
        var crypt = CONTENT.room("crypt");
        var provider = new SingleRoom(crypt);

        assertEquals("crypt", provider.entranceRoomId());
        assertSame(crypt, provider.room("crypt"));
        assertThrows(IllegalArgumentException.class, () -> provider.room("room-0"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.DungeonTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Dungeon`

- [ ] **Step 3: Write the seam**

`server/src/main/java/dm/generate/RoomProvider.java`:

```java
package dm.generate;

import dm.content.RoomDefinition;

/**
 * Where the engine gets rooms from.
 *
 * <p>A seam rather than a switch inside {@code GameEngine}, for the same reason
 * {@code RoomSource} was one: the engine should not know that dungeons exist, and the
 * hand-authored crypt has to keep working, because it is the room every M0 measurement was
 * taken in and the only fixed point available for comparison.
 *
 * <p>Asking for a room is allowed to be slow. A room nobody has visited has to be generated and
 * dressed, which is one model call — spec §5's whole point is that narration covers it.
 */
public interface RoomProvider {

    /** Where the party starts. */
    String entranceRoomId();

    RoomDefinition room(String roomId);
}
```

`server/src/main/java/dm/generate/SingleRoom.java`:

```java
package dm.generate;

import dm.content.RoomDefinition;

/**
 * One room, as a provider. The crypt, and every test that pins a room to assert against it.
 *
 * <p>A dungeon of one, with no doors. Everything the engine does about rooms works here without
 * a special case — {@code enterRoom} is simply never reachable, because there is no exit to
 * stand on.
 */
public record SingleRoom(RoomDefinition room) implements RoomProvider {

    @Override
    public String entranceRoomId() {
        return room.roomId();
    }

    @Override
    public RoomDefinition room(String roomId) {
        if (!room.roomId().equals(roomId)) {
            throw new IllegalArgumentException(
                    "This is a one-room game; there is no '" + roomId + "'");
        }
        return room;
    }
}
```

- [ ] **Step 4: Write the dungeon**

`server/src/main/java/dm/generate/Dungeon.java`:

```java
package dm.generate;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A dungeon: a layout regenerated from its seed, and the dress passes kept from first visit.
 *
 * <p>This class is spec §7's split made concrete, and the split is what makes a large world
 * cheap. Layout, exits and connectivity are a pure function of {@code (seed, coords)} and are
 * <b>never stored</b> — Minecraft and No Man's Sky are "infinite" on precisely this trick. What
 * cannot be regenerated is the dress pass: an LLM decided this room is a flooded reliquary, and
 * no seed brings that back. So dress output is durable data from the moment it exists.
 *
 * <p>"Durable" here means kept, not written to a database. Postgres is still deferred (spec §3).
 * The requirement is that a room the party returns to is the room they left, and that the thing
 * being retained is shaped as data — so that swapping this map for a real store later is an
 * implementation change and nothing more.
 */
public final class Dungeon implements RoomProvider {

    private static final Logger log = LoggerFactory.getLogger(Dungeon.class);

    private final ContentLoader content;

    /** Null when there is no API key. An undressed dungeon is dull and playable. */
    private final RoomDresser dresser;

    private final String kitId;
    private final DungeonLayout layout;

    /** The half of the world that cannot be regenerated. Written once per room, then read. */
    private final Map<String, RoomDefinition> visited = new ConcurrentHashMap<>();

    private Dungeon(ContentLoader content, RoomDresser dresser, String kitId,
                    DungeonLayout layout) {
        this.content = content;
        this.dresser = dresser;
        this.kitId = kitId;
        this.layout = layout;
    }

    public static Dungeon generate(ContentLoader content, RoomDresser dresser, String kitId,
                                   long seed, int roomCount) {
        var layout = LayoutGenerator.generate(seed, roomCount);

        // Spec §6, at dungeon scale. A layout with an unreachable room reaches a player as a
        // door that leads nowhere, which is indistinguishable from a bug in navigation.
        var violations = LayoutValidator.check(layout);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Seed " + seed + " produced an illegal layout: "
                    + violations);
        }
        log.info("dungeon, seed {}:\n{}", seed, DungeonDumper.dump(layout));
        return new Dungeon(content, dresser, kitId, layout);
    }

    public DungeonLayout layout() {
        return layout;
    }

    @Override
    public String entranceRoomId() {
        return layout.entrance().roomId();
    }

    /** Whether this room has been built, and therefore whether asking for it is instant. */
    public boolean isVisited(String roomId) {
        return visited.containsKey(roomId);
    }

    @Override
    public RoomDefinition room(String roomId) {
        var known = visited.get(roomId);
        if (known != null) {
            return known;
        }
        // Deliberately not computeIfAbsent: building a room is an LLM call measured in seconds,
        // and holding a map's bin lock across it is a hazard waiting for a second player.
        // putIfAbsent instead, so two threads arriving at the same threshold agree on one
        // answer — the dress pass is not reproducible, and two of them are two different rooms.
        var built = build(roomId);
        var winner = visited.putIfAbsent(roomId, built);
        return winner != null ? winner : built;
    }

    private RoomDefinition build(String roomId) {
        var node = layout.room(roomId);
        var generated = new RoomGenerator(content).generate(kitId, node);
        log.info("generated {}:\n{}", roomId, RoomDumper.dump(generated));

        if (dresser == null) {
            return generated.toRoomDefinition();
        }
        var dressing = dresser.dress(generated);
        log.info("dressed {} as '{}' — {}", roomId, dressing.name(), dressing.overview());
        return generated.toRoomDefinition(dressing);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.generate.DungeonTest'`
Expected: PASS, 8 tests

- [ ] **Step 6: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/generate/RoomProvider.java \
        server/src/main/java/dm/generate/SingleRoom.java \
        server/src/main/java/dm/generate/Dungeon.java \
        server/src/test/java/dm/generate/DungeonTest.java
git commit -m "Regenerate the map from the seed, and keep what the model wrote"
```

---

### Task 5: State that survives leaving the room

Spec §4's `DungeonState`: current room, visited rooms, entity positions per room — behind the existing `GameRepository` interface, still in memory. A goblin left alive in room 2 is standing in room 2 when the party comes back, and the alcove they found there is still open.

Everything the repository already exposes becomes **scoped to the room the party is in**. That is the change that makes every caller correct without touching one of them: `repo.entities()` has always meant "who is here", and now it is true.

Revealed prop ids are scoped for a sharper reason: generated prop ids are per-room and they repeat. Two rooms both have an `alcove-0`, and a global reveal set would open the second one before the party walked in.

One behaviour changes for free and it is the right change: `GameEngine.spawnGoblin` refuses a second goblin because `repo.find("goblin")` finds the first, and that lookup is now per-room. So each room may hold one goblin, which is what "one goblin" was always meant to mean. `TtsClient` still picks its voice by the id `goblin`, so every one of them still sounds like Vessk — correct, and worth knowing before it surprises you in a playtest.

**Files:**
- Modify: `server/src/main/java/dm/repo/GameRepository.java`
- Modify: `server/src/main/java/dm/repo/InMemoryGameRepository.java`
- Test: `server/src/test/java/dm/repo/InMemoryGameRepositoryTest.java`

**Interfaces:**
- Consumes: `Entity`, `Event`, `Mode`, `PartyMember`
- Produces, on `GameRepository`:
  - `String currentRoomId()`
  - `void setCurrentRoomId(String roomId)` — also records the visit
  - `List<String> visitedRoomIds()` — in the order first entered
  - every existing method keeps its signature; `find`, `entities`, `put`, `remove`, `revealedPropIds` and `reveal` now answer about the current room

- [ ] **Step 1: Write the failing test**

```java
package dm.repo;

import dm.content.ContentLoader;
import dm.model.Mode;
import dm.model.PartyMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §4's DungeonState, behind the interface Postgres slides into in M5.
 *
 * <p>The point of these tests is that "who is in the room" and "what has been found in it" are
 * facts about a room and not about a session. A dungeon where the goblin follows you through
 * the door is not a dungeon, it is one room with several skins.
 */
class InMemoryGameRepositoryTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static dm.model.Entity goblin(int x, int y) {
        return CONTENT.entity("goblin").spawn("goblin", x, y);
    }

    @Test
    @DisplayName("an entity put down in one room is not in the next one")
    void entitiesStayWhereTheyWerePut() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.put(goblin(3, 3));
        assertEquals(1, repo.entities().size());

        repo.setCurrentRoomId("room-1");
        assertEquals(List.of(), repo.entities());
        assertTrue(repo.find("goblin").isEmpty());
    }

    @Test
    @DisplayName("an entity is still there when the party comes back")
    void entitiesAreWaitingOnReturn() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.put(goblin(3, 3).damaged(4));
        repo.setCurrentRoomId("room-1");
        repo.setCurrentRoomId("room-0");

        var found = repo.find("goblin").orElseThrow();
        assertEquals(3, found.x());
        assertEquals(found.maxHp() - 4, found.hp());
    }

    @Test
    @DisplayName("two rooms may each hold something with the same id")
    void idsAreOnlyUniqueWithinARoom() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.put(goblin(1, 1));
        repo.setCurrentRoomId("room-1");
        repo.put(goblin(9, 9));

        assertEquals(9, repo.find("goblin").orElseThrow().x());
        repo.setCurrentRoomId("room-0");
        assertEquals(1, repo.find("goblin").orElseThrow().x());
    }

    @Test
    @DisplayName("a prop revealed in one room is not revealed in another with the same id")
    void revealsAreScopedToTheirRoom() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.reveal("alcove-0");
        assertTrue(repo.revealedPropIds().contains("alcove-0"));

        repo.setCurrentRoomId("room-1");
        assertFalse(repo.revealedPropIds().contains("alcove-0"),
                "generated prop ids repeat between rooms");
    }

    @Test
    @DisplayName("visits are recorded once each, in the order they happened")
    void visitsAreRecordedInOrder() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.setCurrentRoomId("room-2");
        repo.setCurrentRoomId("room-0");

        assertEquals(List.of("room-0", "room-2"), repo.visitedRoomIds());
        assertEquals("room-0", repo.currentRoomId());
    }

    @Test
    @DisplayName("the party, the mode and the log are the session's, not the room's")
    void sessionStateIsNotScoped() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.setParty(List.of(new PartyMember("fighter")));
        repo.setMode(Mode.COMBAT);
        repo.append(dm.model.Event.action("fighter", "said: hello"));

        repo.setCurrentRoomId("room-1");

        assertEquals(1, repo.party().size());
        assertEquals(Mode.COMBAT, repo.mode());
        assertEquals(1, repo.events().size());
    }

    @Test
    @DisplayName("clearing forgets every room, not just the one the party is standing in")
    void clearForgetsEverything() {
        var repo = new InMemoryGameRepository();

        repo.setCurrentRoomId("room-0");
        repo.put(goblin(3, 3));
        repo.setCurrentRoomId("room-1");
        repo.put(goblin(4, 4));

        repo.clear();

        assertEquals(List.of(), repo.visitedRoomIds());
        assertEquals(List.of(), repo.entities());
        repo.setCurrentRoomId("room-0");
        assertEquals(List.of(), repo.entities());
    }

    @Test
    @DisplayName("a repository nobody told about rooms still works, exactly as in M0")
    void worksWithoutAnyRooms() {
        var repo = new InMemoryGameRepository();

        repo.put(goblin(2, 2));

        assertEquals(1, repo.entities().size());
        assertTrue(repo.find("goblin").isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.repo.InMemoryGameRepositoryTest'`
Expected: FAIL — compilation error, `cannot find symbol: method setCurrentRoomId`

- [ ] **Step 3: Add the room to the interface**

In `server/src/main/java/dm/repo/GameRepository.java`, add these three methods above `find`, and replace the javadoc on `find`/`entities`/`revealedPropIds` as shown:

```java
    /**
     * The room the party is standing in.
     *
     * <p>Everything below that concerns a place — {@link #find}, {@link #entities},
     * {@link #put}, {@link #remove}, {@link #revealedPropIds}, {@link #reveal} — answers about
     * this room and no other. That is not a new meaning: "who is here" is what those calls have
     * always meant, and until M1 there was only one here.
     */
    String currentRoomId();

    /** Move the party's attention to a room, and record that they have been in it. */
    void setCurrentRoomId(String roomId);

    /** Every room the party has stood in, in the order they were first entered. */
    List<String> visitedRoomIds();
```

- [ ] **Step 4: Give the in-memory implementation one state per room**

Replace `server/src/main/java/dm/repo/InMemoryGameRepository.java` with:

```java
package dm.repo;

import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.PartyMember;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spec §4's DungeonState. Still a map (shortcut #7), now one map per room.
 *
 * <p>M1 has no save. A new game is {@link #clear} and a fresh spawn, not a file — what M1 adds
 * is that state survives leaving the <em>room</em>, which is a different thing from surviving
 * leaving the session and is the one a dungeon needs (spec §3).
 */
public final class InMemoryGameRepository implements GameRepository {

    /**
     * The room a repository is in before anyone has said. Every M0 test builds one and starts
     * putting entities into it, and none of them should have to learn about rooms to keep
     * working.
     */
    private static final String NOWHERE = "";

    /** What is true of one room. Everything else on this class is true of the session. */
    private record RoomState(Map<String, Entity> entities, Set<String> revealed) {
        static RoomState empty() {
            return new RoomState(new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
        }
    }

    private final Map<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentRoom = new AtomicReference<>(NOWHERE);
    private final List<String> visited = new CopyOnWriteArrayList<>();

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<List<PartyMember>> party = new AtomicReference<>(List.of());
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.EXPLORATION);

    private RoomState here() {
        return rooms.computeIfAbsent(currentRoom.get(), id -> RoomState.empty());
    }

    @Override
    public String currentRoomId() {
        return currentRoom.get();
    }

    @Override
    public void setCurrentRoomId(String roomId) {
        currentRoom.set(roomId);
        // A list rather than a set: the order the party found rooms in is the order the DM
        // should recall them in, and "the room before this one" is a question worth being able
        // to answer.
        if (!visited.contains(roomId)) {
            visited.add(roomId);
        }
    }

    @Override
    public List<String> visitedRoomIds() {
        return List.copyOf(visited);
    }

    @Override
    public void clear() {
        rooms.clear();
        visited.clear();
        currentRoom.set(NOWHERE);
        events.clear();
        party.set(List.of());
        mode.set(Mode.EXPLORATION);
    }

    @Override
    public Optional<Entity> find(String entityId) {
        return Optional.ofNullable(here().entities().get(entityId));
    }

    @Override
    public List<Entity> entities() {
        return List.copyOf(here().entities().values());
    }

    @Override
    public void put(Entity entity) {
        here().entities().put(entity.id(), entity);
    }

    @Override
    public void remove(String entityId) {
        here().entities().remove(entityId);
    }

    @Override
    public List<PartyMember> party() {
        return party.get();
    }

    @Override
    public void setParty(List<PartyMember> members) {
        party.set(List.copyOf(members));
    }

    @Override
    public Mode mode() {
        return mode.get();
    }

    @Override
    public void setMode(Mode newMode) {
        mode.set(newMode);
    }

    @Override
    public Set<String> revealedPropIds() {
        return Set.copyOf(here().revealed());
    }

    @Override
    public void reveal(String propId) {
        here().revealed().add(propId);
    }

    @Override
    public void append(Event event) {
        events.add(event);
    }

    @Override
    public List<Event> events() {
        return List.copyOf(events);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.repo.InMemoryGameRepositoryTest'`
Expected: PASS, 8 tests

- [ ] **Step 6: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS — every M0 test builds a repository and never names a room, which is the `NOWHERE` case

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/repo/GameRepository.java \
        server/src/main/java/dm/repo/InMemoryGameRepository.java \
        server/src/test/java/dm/repo/InMemoryGameRepositoryTest.java
git commit -m "Let a room keep what was left in it"
```

---

### Task 6: Walking through a door

Spec §4's last piece. M0's acceptance script step 9 — "the north door is a wall, handled in fiction" — stops being a graceful excuse.

`GameEngine` has held one `final RoomDefinition` and one `final CombatEngine` since M0. Both become the current room's, swapped together, because a `CombatEngine` holds the room it computes legal moves against and a stale one would let the goblin walk through the new room's walls.

You cannot leave a fight. That is a rule, not an omission: an initiative order with half its combatants in another scene is not something the engine can be asked to resolve.

**Files:**
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `server/src/main/java/dm/App.java`
- Test: `server/src/test/java/dm/engine/NavigationTest.java`

**Interfaces:**
- Consumes: `RoomProvider`, `SingleRoom`, `Dungeon`, `Exit`, `Direction`, `Square`
- Produces, on `GameEngine`:
  - `GameEngine(ContentLoader, GameRepository, DiceRoller, RoomProvider)` — the new primary constructor
  - the existing `(…, RoomDefinition)` and three-argument constructors stay, wrapping in `SingleRoom`
  - `Optional<Exit> exitAt(int x, int y)`
  - `void prepare(String roomId)` — build and dress a room without entering it
  - `void enterRoom(Exit exit)`
  - `String roomName(String roomId)`
- `App`: `--generate <seed>` builds a `Dungeon` of `DUNGEON_ROOMS` rooms and boots into its entrance

- [ ] **Step 1: Write the failing test**

```java
package dm.engine;

import dm.content.ContentLoader;
import dm.generate.Dungeon;
import dm.model.Entity;
import dm.model.Exit;
import dm.repo.InMemoryGameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §10: move between rooms, assert the scene swaps and the room departed keeps its state.
 *
 * <p>No dresser anywhere below. Navigation is engine work and must be testable with no model in
 * the path, exactly as combat is.
 */
class NavigationTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private record Fixture(GameEngine engine, Dungeon dungeon, Exit firstExit) {
    }

    private static Fixture start(long seed) {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", seed, 8);
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(),
                new RandomDiceRoller(), dungeon);
        engine.start();
        var exit = engine.room().exits().getFirst();
        return new Fixture(engine, dungeon, exit);
    }

    @Test
    @DisplayName("the game starts in the dungeon's entrance")
    void startsAtTheEntrance() {
        var fixture = start(7);

        assertEquals(fixture.dungeon().entranceRoomId(), fixture.engine().room().roomId());
        assertEquals(fixture.dungeon().entranceRoomId(),
                fixture.engine().repo().currentRoomId());
        assertEquals(List.of(fixture.dungeon().entranceRoomId()),
                fixture.engine().repo().visitedRoomIds());
    }

    @Test
    @DisplayName("the entrance has a way out, and the scene knows about it")
    void theSceneCarriesExits() {
        var fixture = start(7);

        assertFalse(fixture.engine().scene().exits().isEmpty());
        assertEquals(fixture.engine().room().exits(), fixture.engine().scene().exits());
    }

    @Test
    @DisplayName("walking through a door swaps the scene")
    void enteringSwapsTheScene() {
        var fixture = start(7);
        var exit = fixture.firstExit();

        fixture.engine().enterRoom(exit);

        assertEquals(exit.toRoomId(), fixture.engine().room().roomId());
        assertEquals(exit.toRoomId(), fixture.engine().scene().roomId());
        assertEquals(exit.toRoomId(), fixture.engine().repo().currentRoomId());
    }

    @Test
    @DisplayName("the party arrives inside the door it came through, not standing in it")
    void arrivesInsideTheDoor() {
        var fixture = start(7);
        var exit = fixture.firstExit();
        var from = fixture.engine().room().roomId();

        fixture.engine().enterRoom(exit);

        var next = fixture.engine().room();
        var back = next.exits().stream()
                .filter(e -> e.toRoomId().equals(from))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no door back to " + from));

        var fighter = fixture.engine().repo().find("fighter").orElseThrow();
        assertEquals(back.inward(next.width(), next.height()),
                new dm.model.Square(fighter.x(), fighter.y()));
        assertFalse(next.isObstructed(fighter.x(), fighter.y()));
    }

    @Test
    @DisplayName("the party goes with you and nothing else does")
    void onlyThePartyTravels() {
        var fixture = start(7);
        var engine = fixture.engine();
        var spawn = engine.defaultGoblinSpawn();
        engine.spawnGoblin(spawn.x(), spawn.y());

        engine.enterRoom(fixture.firstExit());

        assertTrue(engine.repo().find("fighter").isPresent());
        assertTrue(engine.repo().find("goblin").isEmpty(), "the goblin followed the party");
    }

    @Test
    @DisplayName("the room you left is as you left it when you come back")
    void theRoomYouLeftIsKept() {
        var fixture = start(7);
        var engine = fixture.engine();
        var exit = fixture.firstExit();
        var entrance = engine.room().roomId();
        var definition = engine.room();

        var spawn = engine.defaultGoblinSpawn();
        engine.spawnGoblin(spawn.x(), spawn.y());
        var hurt = engine.repo().find("goblin").orElseThrow().damaged(3);
        engine.repo().put(hurt);

        engine.enterRoom(exit);
        var back = engine.room().exits().stream()
                .filter(e -> e.toRoomId().equals(entrance))
                .findFirst()
                .orElseThrow();
        engine.enterRoom(back);

        assertEquals(entrance, engine.room().roomId());
        assertSame(definition, engine.room(), "the room was regenerated, not remembered");
        var goblin = engine.repo().find("goblin").orElseThrow();
        assertEquals(hurt.hp(), goblin.hp());
        assertEquals(spawn.x(), goblin.x());
    }

    @Test
    @DisplayName("a room is the same room the second time it is entered")
    void roomsAreNotRebuilt() {
        var fixture = start(7);
        var engine = fixture.engine();
        var exit = fixture.firstExit();
        var entrance = engine.room().roomId();

        engine.enterRoom(exit);
        var firstVisit = engine.room();
        var back = engine.room().exits().stream()
                .filter(e -> e.toRoomId().equals(entrance)).findFirst().orElseThrow();
        engine.enterRoom(back);
        engine.enterRoom(exit);

        assertSame(firstVisit, engine.room());
    }

    @Test
    @DisplayName("you cannot walk out of a fight")
    void noLeavingCombat() {
        var fixture = start(7);
        var engine = fixture.engine();
        var spawn = engine.defaultGoblinSpawn();
        engine.spawnGoblin(spawn.x(), spawn.y());
        engine.combat().start(new CombatSink.Buffer());

        assertThrows(IllegalArgumentException.class,
                () -> engine.enterRoom(fixture.firstExit()));
    }

    @Test
    @DisplayName("the fight in the next room is fought against the next room's walls")
    void combatFollowsTheRoom() {
        var fixture = start(7);
        var engine = fixture.engine();
        var before = engine.combat();

        engine.enterRoom(fixture.firstExit());

        assertNotSame(before, engine.combat());
    }

    @Test
    @DisplayName("a restart puts the party back at the entrance with nothing else remembered")
    void restartReturnsToTheEntrance() {
        var fixture = start(7);
        var engine = fixture.engine();
        engine.enterRoom(fixture.firstExit());

        engine.restart();

        assertEquals(fixture.dungeon().entranceRoomId(), engine.room().roomId());
        assertEquals(List.of(fixture.dungeon().entranceRoomId()),
                engine.repo().visitedRoomIds());
        assertEquals(1, engine.repo().entities().size());
    }

    @Test
    @DisplayName("the square a door stands in is a way out; the one beside it is not")
    void exitAtFindsTheDoor() {
        var fixture = start(7);
        var exit = fixture.firstExit();

        assertEquals(exit, fixture.engine().exitAt(exit.x(), exit.y()).orElseThrow());
        var beside = exit.inward(fixture.engine().room().width(),
                fixture.engine().room().height());
        assertTrue(fixture.engine().exitAt(beside.x(), beside.y()).isEmpty());
    }

    @Test
    @DisplayName("the authored crypt still runs, with no exits and nowhere to go")
    void theCryptIsUnchanged() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();

        assertEquals("crypt", engine.room().roomId());
        assertEquals("crypt", repo.currentRoomId());
        assertEquals(List.of(), engine.scene().exits());
        assertEquals(1, repo.entities().size());
    }

    @Test
    @DisplayName("preparing a room builds it without moving anybody")
    void prepareDoesNotMoveThePlayer() {
        var fixture = start(7);
        var engine = fixture.engine();
        var entrance = engine.room().roomId();
        var fighter = engine.repo().find("fighter").orElseThrow();

        engine.prepare(fixture.firstExit().toRoomId());

        assertEquals(entrance, engine.room().roomId());
        assertEquals(fighter.x(), engine.repo().find("fighter").map(Entity::x).orElseThrow());
        assertTrue(fixture.dungeon().isVisited(fixture.firstExit().toRoomId()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.engine.NavigationTest'`
Expected: FAIL — compilation error, `no suitable constructor found for GameEngine(…,Dungeon)`

- [ ] **Step 3: Give the engine many rooms**

In `server/src/main/java/dm/engine/GameEngine.java`, add the imports `dm.generate.RoomProvider`, `dm.generate.SingleRoom`, `dm.model.Exit`, `dm.model.Square`, `java.util.ArrayDeque`, `java.util.HashSet`, `java.util.Optional`, and replace the fields, constructors, `restart` and `room()` with:

```java
    private final ContentLoader content;
    private final GameRepository repo;
    private final DiceRoller dice;
    private final RoomProvider rooms;

    /**
     * The room the party is in, and the fight it would have in it.
     *
     * <p>Not final any more, and swapped together. {@link CombatEngine} holds the room it
     * computes legal moves against; a fight carried into the next scene would be resolved
     * against the last one's walls.
     */
    private RoomDefinition room;
    private CombatEngine combat;

    /** See {@link #consecutiveFailedChecks()}. Session state, like the fight's turn order. */
    private int consecutiveFailedChecks;

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice) {
        this(content, repo, dice, new SingleRoom(content.room(ROOM_ID)));
    }

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice,
                      RoomDefinition room) {
        this(content, repo, dice, new SingleRoom(room));
    }

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice,
                      RoomProvider rooms) {
        this.content = content;
        this.repo = repo;
        this.dice = dice;
        this.rooms = rooms;
        setRoom(rooms.room(rooms.entranceRoomId()));
    }

    /**
     * Point the engine, the fight and the repository at one room.
     *
     * <p>Three things that must never disagree about where the party is, set in one place so
     * they cannot be set in two.
     */
    private void setRoom(RoomDefinition next) {
        this.room = next;
        this.combat = new CombatEngine(repo, dice, next);
        repo.setCurrentRoomId(next.roomId());
    }
```

```java
    public void restart() {
        combat.reset();
        repo.clear();
        consecutiveFailedChecks = 0;
        // clear() forgets which room the party was in, so the entrance has to be re-entered.
        // The dungeon keeps its dressed rooms: a new session in the same dungeon is the same
        // dungeon, and re-dressing it would spend eight model calls to make it a worse one.
        setRoom(rooms.room(rooms.entranceRoomId()));
        start();
    }
```

```java
    public RoomDefinition room() {
        return room;
    }

    /** The name of a room the party has already been in. Not a way to peek ahead. */
    public String roomName(String roomId) {
        return rooms.room(roomId).name();
    }
```

- [ ] **Step 4: Add the crossing**

Add these three methods to `GameEngine`, below `defaultGoblinSpawn()`:

```java
    /** The way out of this square, if this square is one. */
    public Optional<Exit> exitAt(int x, int y) {
        return room.exitAt(x, y);
    }

    /**
     * Build and dress a room without entering it.
     *
     * <p>Exists so the wait can be covered. A first visit costs one model call (spec §5), and
     * the whole design of the threshold is that the DM talks about the doorway while this runs.
     * Calling it twice is free — the dungeon keeps what it built.
     */
    public void prepare(String roomId) {
        rooms.room(roomId);
    }

    /**
     * Take the party through a door.
     *
     * <p>The scene changes wholesale, so this emits no diffs — the caller sends a fresh
     * {@link SceneState}. Trying to express a room change as diffs would mean removing every
     * entity, every prop and the floor, which is a scene message written the long way.
     */
    public void enterRoom(Exit exit) {
        if (combat.isActive()) {
            throw new IllegalArgumentException("You cannot walk out of a fight.");
        }
        var from = room.roomId();
        var next = rooms.room(exit.toRoomId());
        var arrival = arrivalSquare(next, from, exit);

        // Lifted out of the old room before the switch and put down after it: the repository is
        // scoped to the current room, so this is what "travelling" means to it.
        var travellers = repo.party().stream()
                .flatMap(member -> repo.find(member.entityId()).stream())
                .toList();
        travellers.forEach(traveller -> repo.remove(traveller.id()));

        setRoom(next);

        var landings = landingSquares(next, arrival, travellers.size());
        for (int i = 0; i < travellers.size(); i++) {
            var at = landings.get(i);
            repo.put(travellers.get(i).movedTo(at.x(), at.y()));
        }
        repo.append(Event.action(
                travellers.isEmpty() ? "party" : travellers.getFirst().id(),
                "went " + exit.direction().name().toLowerCase() + " into " + next.roomId()));
    }

    /**
     * Where the party lands: inside the door on the far side of the one they used.
     *
     * <p>Found by looking for the reciprocal door rather than by computing it, because the
     * layout is the authority on which door answers which and the room's own exits are what it
     * produced. The fallback is the room's authored start, which only a room with no way back
     * could need.
     */
    private static Square arrivalSquare(RoomDefinition next, String from, Exit used) {
        return next.exits().stream()
                .filter(e -> e.toRoomId().equals(from)
                        && e.direction() == used.direction().opposite())
                .findFirst()
                .map(e -> e.inward(next.width(), next.height()))
                .orElseGet(() -> {
                    var at = next.startPositions().party().getFirst();
                    return new Square(at.x(), at.y());
                });
    }

    /**
     * One free square each, starting from the arrival square and spreading outward.
     *
     * <p>M1's party is one member and this could be one square. It is a search because invariant
     * #2 says the party is a list, and the version of this that stacks four fighters on one
     * square is the version that ships.
     */
    private static List<Square> landingSquares(RoomDefinition next, Square arrival, int count) {
        var found = new ArrayList<Square>(count);
        var seen = new HashSet<Square>();
        var queue = new ArrayDeque<Square>();
        queue.add(arrival);
        seen.add(arrival);

        while (!queue.isEmpty() && found.size() < count) {
            var square = queue.poll();
            if (!next.isObstructed(square.x(), square.y())) {
                found.add(square);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var neighbour = new Square(square.x() + dx, square.y() + dy);
                    if (neighbour.x() < 0 || neighbour.x() >= next.width()
                            || neighbour.y() < 0 || neighbour.y() >= next.height()) {
                        continue;
                    }
                    if (seen.add(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
        }
        return found;
    }
```

- [ ] **Step 5: Put the exits in the scene**

The client cannot draw a door it is not told about. In `server/src/main/java/dm/model/SceneState.java`, add the `dm.model.Exit` import, add `List<Exit> exits` after `props`, and thread it through `asSeenByPlayer`:

```java
public record SceneState(
        String roomId,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        List<Prop> props,
        /** The ways out. Empty in a room with none, never null. */
        List<Exit> exits,
        List<EntityView> entities,
        LightingPreset lighting,
        Mode mode,
        /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
        CombatView combat
) {
    /** Props the player can currently see. Hidden ones stay server-side until revealed. */
    public List<Prop> visibleProps() {
        return props.stream().filter(p -> !p.hidden()).toList();
    }

    /** The scene as the client should first see it — hidden props stripped out entirely. */
    public SceneState asSeenByPlayer() {
        return new SceneState(roomId, width, height, floorType, wallType,
                visibleProps(), exits, entities, lighting, mode, combat);
    }
}
```

and in `GameEngine.scene()`:

```java
        return new SceneState(room.roomId(), room.width(), room.height(),
                room.floorType(), room.wallType(), visible, room.exits(), entities,
                room.lighting(), repo.mode(), combat.view());
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.engine.NavigationTest'`
Expected: PASS, 13 tests

- [ ] **Step 7: Boot into a dungeon**

In `server/src/main/java/dm/App.java`, add `import dm.generate.Dungeon;` and replace the three-branch room selection with:

```java
        RoomProvider rooms;
        if (generateSeed == null) {
            rooms = new SingleRoom(RoomSource.authored(content, "crypt"));
        } else {
            // The dress pass reads as writing, but what it must actually emit is a JSON object
            // with a fixed shape — which is the tools model's skill, not the prose model's.
            // Measured on seed 7, four samples each: venice-uncensored-role-play produced
            // unparseable JSON 4/4 (a string opened with " and closed with ', a stray 'あ', a
            // bad escape) and fell back to "An Unnamed Chamber" every time; qwen3-next-80b
            // parsed 3/3. A prose model tuned for roleplay cannot hold a quote character.
            //
            // A null dresser is the no-key path: the generator half needs no key, and a dungeon
            // with no prose is more useful than a refusal to boot while tuning layout.
            RoomDresser dresser = null;
            if (config.has("VENICE_API_KEY")) {
                dresser = new RoomDresser(
                        new VeniceDmClient(config, config.get("DM_MODEL_TOOLS", "qwen3-next-80b"),
                                java.time.Duration.ofSeconds(30)),
                        content.prompt("dress-room"));
            } else {
                log.warn("VENICE_API_KEY not set — generating an undressed dungeon.");
            }
            rooms = Dungeon.generate(content, dresser, "crypt", generateSeed, DUNGEON_ROOMS);
        }

        var engine = new GameEngine(content, repo, dice, rooms);
        engine.start();
```

Add `import dm.generate.RoomProvider;` and `import dm.generate.SingleRoom;`. Leave the `RoomGenerator` and `RoomDumper` imports alone — `dumpDungeon` still uses both. The `RoomDefinition` import becomes unused; drop it.

Change the closing log line's room count to name the dungeon:

```java
        log.info("Emberdelve on :{} — room '{}', {} entities, dm={}{}",
                PORT,
                engine.room().name(),
                repo.entities().size(),
                dm == null ? "disabled" : dm.modelId(),
                demoMode ? ", demo dice" : "");
```

(unchanged — it already reads the current room, which is now the dungeon's entrance)

- [ ] **Step 8: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/model/SceneState.java \
        server/src/main/java/dm/App.java \
        server/src/test/java/dm/engine/NavigationTest.java
git commit -m "Let the party leave the room they started in"
```

---

### Task 7: Where you may walk, out of combat

Spec §6b's free movement. The machinery already shipped in M0 — pathfinding, the slide, footsteps, and a server that computes the legal set. The only thing missing is a legal set *outside* combat, which is every unobstructed square, because there is no movement budget to spend.

Today the client offers a pointer over every square in the room and the server refuses the illegal ones with a toast. That is the client deciding what is legal by guessing, which is invariant #1 the wrong way round. The server sends the set; the client highlights it.

The set is "unobstructed", not "reachable", exactly as the spec words it. `SpatialValidator` guarantees every open square in a generated room is reachable from the start, so in a generated room the two are the same thing — and where they ever diverge, the validator has a bug worth seeing rather than papering over.

**Files:**
- Create: `server/src/main/java/dm/engine/Walkable.java`
- Modify: `server/src/main/java/dm/model/SceneState.java`
- Modify: `server/src/main/java/dm/model/Diff.java`
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `server/src/main/java/dm/engine/CombatEngine.java`
- Test: `server/src/test/java/dm/engine/WalkableTest.java`

**Interfaces:**
- Consumes: `RoomDefinition`, `Entity`, `Square`
- Produces:
  - `Walkable.squares(RoomDefinition room, List<Entity> present)` returning `List<Square>`, sorted by y then x
  - `SceneState` gains `List<Square> legalMoves` — the exploration set, empty during combat
  - `Diff.LegalMovesChanged(List<Square> legalMoves)`

- [ ] **Step 1: Write the failing test**

```java
package dm.engine;

import dm.content.ContentLoader;
import dm.model.Diff;
import dm.model.Mode;
import dm.model.Square;
import dm.repo.InMemoryGameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §6b: free movement outside combat, computed by the server.
 *
 * <p>"Anywhere unobstructed" is a rule, and rules live on this side of the wire. The client
 * highlighting squares it worked out for itself is how the two ideas of legality drift, which
 * is the whole of invariant #1.
 */
class WalkableTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("every open square is walkable, and the solid ones are not")
    void solidSquaresAreOut() {
        var crypt = CONTENT.room("crypt");

        var squares = Walkable.squares(crypt, List.of());

        assertTrue(squares.contains(new Square(1, 1)));
        // The sarcophagus at (6,7) is solid; the door at (6,11) is a slab in a wall face and
        // standing in a doorway is legal.
        assertFalse(squares.contains(new Square(6, 7)));
        assertTrue(squares.contains(new Square(6, 11)));
    }

    @Test
    @DisplayName("a square someone is standing on is not one you may walk to")
    void occupiedSquaresAreOut() {
        var crypt = CONTENT.room("crypt");
        var goblin = CONTENT.entity("goblin").spawn("goblin", 4, 4);

        assertFalse(Walkable.squares(crypt, List.of(goblin)).contains(new Square(4, 4)));
        assertTrue(Walkable.squares(crypt, List.of()).contains(new Square(4, 4)));
    }

    @Test
    @DisplayName("a body on the floor is not an obstacle")
    void theDeadDoNotBlock() {
        var crypt = CONTENT.room("crypt");
        var corpse = CONTENT.entity("goblin").spawn("goblin", 4, 4).withHp(0);

        assertTrue(Walkable.squares(crypt, List.of(corpse)).contains(new Square(4, 4)));
    }

    @Test
    @DisplayName("the set is ordered, so a snapshot of it means something")
    void sortedByRowThenColumn() {
        var squares = Walkable.squares(CONTENT.room("crypt"), List.of());

        for (int i = 1; i < squares.size(); i++) {
            var previous = squares.get(i - 1);
            var current = squares.get(i);
            assertTrue(previous.y() < current.y()
                            || (previous.y() == current.y() && previous.x() < current.x()),
                    previous + " came before " + current);
        }
    }

    @Test
    @DisplayName("the scene carries the exploration set, and drops it in combat")
    void theSceneCarriesTheSet() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();

        assertFalse(engine.scene().legalMoves().isEmpty());

        var spawn = engine.defaultGoblinSpawn();
        engine.spawnGoblin(spawn.x(), spawn.y());
        engine.combat().start(new CombatSink.Buffer());

        assertEquals(Mode.COMBAT, engine.mode());
        assertEquals(List.of(), engine.scene().legalMoves(),
                "in combat the legal set is the CombatView's, and only the CombatView's");
    }

    @Test
    @DisplayName("moving out of combat republishes the set, because a square just freed up")
    void movingRepublishes() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();
        var fighter = repo.find("fighter").orElseThrow();

        var sink = new CombatSink.Buffer();
        engine.moveTo("fighter", fighter.x() + 1, fighter.y(), sink);

        var published = sink.collectedDiffs().stream()
                .filter(d -> d instanceof Diff.LegalMovesChanged)
                .map(Diff.LegalMovesChanged.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no legal set was published"));

        assertTrue(published.legalMoves().contains(new Square(fighter.x(), fighter.y())),
                "the square just vacated should be walkable again");
        assertFalse(published.legalMoves().contains(new Square(fighter.x() + 1, fighter.y())),
                "the square just taken should not be");
    }

    @Test
    @DisplayName("a fight ending hands the exploration set back")
    void endingCombatRepublishes() {
        var repo = new InMemoryGameRepository();
        // Fighter wins initiative on a 20, the goblin loses on a 1, the swing is a natural 20.
        // Combatants roll initiative sorted by id, so the first face is the fighter's.
        var engine = new GameEngine(CONTENT, repo,
                new ScriptedDiceRoller(20, 1, 20, 5, 5));
        engine.start();

        // Beside the fighter, and on its last hit point, so one swing ends the fight.
        var fighter = repo.find("fighter").orElseThrow();
        engine.spawnGoblin(fighter.x(), fighter.y() + 1);
        repo.put(repo.find("goblin").orElseThrow().withHp(1));
        engine.combat().start(new CombatSink.Buffer());

        var sink = new CombatSink.Buffer();
        engine.combat().attack("fighter", "goblin", sink);

        assertEquals(Mode.EXPLORATION, engine.mode(), "the fight did not end");
        var published = sink.collectedDiffs().stream()
                .filter(d -> d instanceof Diff.LegalMovesChanged)
                .map(Diff.LegalMovesChanged.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the fight ended and nobody said where the player may walk"));
        assertFalse(published.legalMoves().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.engine.WalkableTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Walkable`

- [ ] **Step 3: Write the rule**

`server/src/main/java/dm/engine/Walkable.java`:

```java
package dm.engine;

import dm.content.RoomDefinition;
import dm.model.Entity;
import dm.model.Square;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Where a creature may stand outside combat.
 *
 * <p>Spec §6b: every unobstructed square, because there is no movement budget to spend. That is
 * a much simpler rule than {@code CombatEngine.reachable} and it is deliberately a separate one
 * — the combat rule is about a turn, and out of combat there is no turn.
 *
 * <p>Unobstructed rather than reachable, exactly as the spec words it. {@code SpatialValidator}
 * guarantees every open square in a generated room can be walked to from the start, so the two
 * agree; where they ever stop agreeing, that is a validator bug worth seeing rather than one
 * quietly absorbed here.
 *
 * <p>Sorted by row and then column, matching {@code CombatEngine.legalMoves}. A set that comes
 * back in map order is a set you cannot snapshot.
 */
public final class Walkable {

    private Walkable() {
    }

    public static List<Square> squares(RoomDefinition room, List<Entity> present) {
        var occupied = new HashSet<Square>();
        for (var entity : present) {
            // The dead are scenery. Walking over a body is grim and legal, and treating a
            // corpse as a wall leaves the room with permanent furniture nobody placed.
            if (entity.isAlive()) {
                occupied.add(new Square(entity.x(), entity.y()));
            }
        }

        var out = new ArrayList<Square>();
        for (int y = 0; y < room.height(); y++) {
            for (int x = 0; x < room.width(); x++) {
                var square = new Square(x, y);
                if (room.isObstructed(x, y) || occupied.contains(square)) {
                    continue;
                }
                out.add(square);
            }
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 4: Put the set on the wire**

In `server/src/main/java/dm/model/SceneState.java`, add the component after `combat` (`Square` is in the same package and needs no import):

```java
public record SceneState(
        String roomId,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        List<Prop> props,
        /** The ways out. Empty in a room with none, never null. */
        List<Exit> exits,
        List<EntityView> entities,
        LightingPreset lighting,
        Mode mode,
        /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
        CombatView combat,
        /**
         * Where the player may walk right now, outside combat (spec §6b). Empty during a fight:
         * the legal set then belongs to {@link CombatView}, and two answers to one question is
         * how a client ends up highlighting a square the server will refuse.
         */
        List<Square> legalMoves
) {
```

and in `asSeenByPlayer`:

```java
        return new SceneState(roomId, width, height, floorType, wallType,
                visibleProps(), exits, entities, lighting, mode, combat, legalMoves);
```

In `server/src/main/java/dm/model/Diff.java`, add the subtype registration and the record:

```java
        @JsonSubTypes.Type(value = Diff.LegalMovesChanged.class, name = "LegalMovesChanged"),
```

```java
    /**
     * Where the player may walk, outside combat. Replaced wholesale, like {@link CombatChanged}
     * and for the same reason: the server sends the whole answer so the client has no chance to
     * hold half of one.
     */
    record LegalMovesChanged(List<Square> legalMoves) implements Diff {}
```

with `import java.util.List;` added to the file.

- [ ] **Step 5: Publish it when it changes**

In `server/src/main/java/dm/engine/GameEngine.java`:

Add a helper beside `scene()`:

```java
    /**
     * The exploration legal set as a diff, for whatever just changed it.
     *
     * <p>Occupancy is the only thing that moves it — props do not appear and disappear outside
     * a reveal — so it is republished on a move, a spawn, and the two mode edges.
     */
    private Diff legalMovesDiff() {
        return new Diff.LegalMovesChanged(
                repo.mode() == Mode.EXPLORATION
                        ? Walkable.squares(room, repo.entities())
                        : List.of());
    }
```

In `scene()`, pass the set:

```java
        return new SceneState(room.roomId(), room.width(), room.height(),
                room.floorType(), room.wallType(), visible, room.exits(), entities,
                room.lighting(), repo.mode(), combat.view(),
                repo.mode() == Mode.EXPLORATION
                        ? Walkable.squares(room, repo.entities())
                        : List.of());
```

In `moveTo`, the out-of-combat branch's final line becomes:

```java
        sink.diffs(List.of(
                new Diff.EntityMoved(actorId, entity.x(), entity.y(), x, y),
                legalMovesDiff()));
```

In `spawnGoblin`, the return becomes:

```java
        return List.of(new Diff.EntityAdded(goblin.toView()), legalMovesDiff());
```

In `setMode`, the return becomes:

```java
        return List.of(new Diff.ModeChanged(mode), legalMovesDiff());
```

In `server/src/main/java/dm/engine/CombatEngine.java`, add `import dm.model.Square;` if absent and extend the two edges of a fight. In `start`:

```java
        sink.diffs(List.of(new Diff.ModeChanged(Mode.COMBAT), new Diff.CombatChanged(view()),
                new Diff.LegalMovesChanged(List.of())));
```

and in `end`:

```java
    private void end(CombatSink sink) {
        active = false;
        order = List.of();
        repo.setMode(Mode.EXPLORATION);
        repo.append(new Event.ModeEntered(Instant.now(), Mode.EXPLORATION));
        // The fight is over and the player may walk again. Handed back here rather than left
        // for the next move: without it the board is unclickable until something else happens.
        sink.diffs(List.of(new Diff.ModeChanged(Mode.EXPLORATION), new Diff.CombatChanged(null),
                new Diff.LegalMovesChanged(Walkable.squares(room, repo.entities()))));
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.engine.WalkableTest'`
Expected: PASS, 7 tests

- [ ] **Step 7: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS. `CombatEngineTest` asserts on diff *contents*, not diff counts — if any assertion there counts diffs, widen it to look for the diff it means rather than its index.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/engine/Walkable.java \
        server/src/main/java/dm/model/SceneState.java \
        server/src/main/java/dm/model/Diff.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/engine/CombatEngine.java \
        server/src/test/java/dm/engine/WalkableTest.java
git commit -m "Say where the player may walk when nobody is fighting"
```

---

### Task 8: The client walks between rooms

The renderer already rebuilds when `roomId` changes — `Canvas` has checked for that since M0. What is missing is a door the player can see, and a cursor that agrees with the server about where they may walk.

Exits are highlighted permanently, in both modes. A way out is not a suggestion the way a legal move is; it is a feature of the room, and a player who cannot see where the doors are will not go through one.

**Files:**
- Modify: `client/src/types.ts`
- Modify: `client/src/store.ts`
- Modify: `client/src/ui/Canvas.tsx`
- Modify: `client/src/scene/Renderer.ts`

**Interfaces:**
- Consumes: the wire types from Tasks 6 and 7
- Produces:
  - `types.ts`: `Direction`, `Exit`, `SceneState.exits`, `SceneState.legalMoves`, `Diff` gains `LegalMovesChanged`
  - `store.ts`: `applyDiffs` handles `LegalMovesChanged`; new action `beginTransition()`
  - `Renderer`: `setExits(exits: Exit[]): void`

- [ ] **Step 1: Mirror the wire**

In `client/src/types.ts`, add below `PropType`:

```ts
export type Direction = "NORTH" | "SOUTH" | "EAST" | "WEST";

/**
 * The edge between two scenes. `Exit`, never `Door` — the same edge is a road out of a village
 * or a ford across a river, and the name was chosen before anything needed the distinction.
 *
 * `id` is also the id of the DOOR prop standing in the square, so the renderer draws one thing.
 */
export interface Exit {
  id: string;
  x: number;
  y: number;
  direction: Direction;
  toRoomId: string;
}
```

extend `SceneState`:

```ts
export interface SceneState {
  roomId: string;
  width: number;
  height: number;
  floorType: FloorType;
  wallType: WallType;
  props: Prop[];
  /** The ways out. Empty in a room with none, never null. */
  exits: Exit[];
  entities: EntityView[];
  lighting: LightingPreset;
  mode: Mode;
  /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
  combat: CombatView | null;
  /**
   * Where the player may walk right now, outside combat. Empty during a fight — the legal set
   * then belongs to `combat`, and nothing in here ever works one out for itself (invariant #1).
   */
  legalMoves: Square[];
}
```

and add the diff:

```ts
  | { kind: "LegalMovesChanged"; legalMoves: Square[] }
```

- [ ] **Step 2: Handle the diff, and give the DM the floor at a threshold**

In `client/src/store.ts`, add the case inside `applyDiffs`, beside `CombatChanged`:

```ts
            case "LegalMovesChanged":
              // Replaced wholesale, like the combat picture, and for the same reason.
              scene = { ...scene, legalMoves: diff.legalMoves };
              break;
```

Add `beginTransition: () => void;` to the `GameState` interface, beside `setStarted`, and the action:

```ts
  /**
   * The player has stepped into a doorway and the DM is about to talk about it.
   *
   * <p>The same move as `setStarted`, and the same argument: the DM has the floor from the
   * moment the crossing begins, not from the moment its first token lands. Those are a second
   * or two apart, and in that window the input box would otherwise be open — long enough to
   * type a question into a room the player is halfway out of.
   */
  beginTransition: () => set({ awaitingDm: true }),
```

- [ ] **Step 3: Ask the server where the player may walk**

In `client/src/ui/Canvas.tsx`, replace the out-of-combat branch of `intent` with:

```ts
  // Out of combat there is no turn and nothing to spend, but there are still squares with
  // something solid on them and squares somebody is standing in. The server sent the answer;
  // this asks the list, exactly as the combat branch above does (invariant #1).
  const player = scene.entities.find((e) => e.isPlayerControlled);
  if (!player || !square) return null;
  // The server refuses this too — it is authoritative and this is only the cursor agreeing with
  // it. Without it a dead fighter still gets a pointer and a highlight over every square, which
  // reads as a game that has not noticed.
  if (player.hp <= 0) return null;
  if (!scene.legalMoves.some((s) => s.x === square.x && s.y === square.y)) return null;
  return { kind: "move", actorId: player.id, square };
```

and in `onClick`, take the floor when the click is a step into a doorway:

```ts
    const onClick = (event: PointerEvent) => {
      const move = intent(renderer, event);
      if (!move) return;

      // A door is a square you walk onto; the server does the rest. Claiming the floor here
      // rather than waiting for the first narration token keeps the input box shut for the
      // whole crossing.
      if (move.kind === "move") {
        const scene = useGame.getState().scene;
        const leaving = scene?.exits.some(
          (exit) => exit.x === move.square.x && exit.y === move.square.y,
        );
        if (leaving) useGame.getState().beginTransition();
      }

      // Sent, never applied locally. The token does not budge until the server says it moved
      // (invariant #1) — which on localhost is the same frame, and in M1 will not be.
      send(
        move.kind === "attack"
          ? { type: "attack", actorId: move.actorId, targetId: move.targetId }
          : { type: "moveTo", actorId: move.actorId, x: move.square.x, y: move.square.y },
      );
    };
```

- [ ] **Step 4: Draw the doors**

In `client/src/scene/Renderer.ts`:

Add the tint beside the others:

```ts
/** The ways out. Warm, and never the move highlight's blue — a door is not a suggestion. */
const EXIT_TINT = 0xd8a25a;
```

Add the field beside `targetSquares`:

```ts
  private readonly exitSquares: THREE.InstancedMesh;
```

Build it in the constructor, immediately after `this.targetSquares = …`:

```ts
    this.exitSquares = buildSquares(overlayMaterial(EXIT_TINT, 0.34));
    // Just under the move highlight, so a doorway inside a legal move does not z-fight with it.
    this.exitSquares.position.y = 0.002;
```

and add it to the overlay group:

```ts
    this.overlay.add(this.moveSquares, this.targetSquares, this.exitSquares, this.hoverSquare);
```

Add the method beside `setCombat`:

```ts
  /**
   * Marks the ways out, in both modes and for the whole time the room is on screen.
   *
   * <p>Not a legal-move highlight: those come and go with a turn, and a door is a fact about
   * the room. A player who cannot see where the doors are does not walk through one — and the
   * DOOR prop alone is a slab against a dark wall at 480px.
   */
  setExits(exits: readonly Exit[]): void {
    this.place(
      this.exitSquares,
      exits.map((exit) => ({ x: exit.x, y: exit.y })),
    );
  }
```

Call it from `setScene`, immediately before `this.setCombat(state.combat);`:

```ts
    this.setExits(state.exits);
```

Add `Exit` to the type import at the top of the file.

- [ ] **Step 5: Typecheck**

Run: `cd client && npx tsc --noEmit`
Expected: no errors. If `Square` is not already imported in `store.ts`, add it.

- [ ] **Step 6: Walk a dungeon**

Start the server with a key set and `--generate 7`, open the client, and walk onto a highlighted door square.

Expected: the room is replaced, the fighter is standing just inside a door on the far side, and the door back is highlighted. Walk back: the same room, with the same name, and anything left in it still there.

If the server has no key the dungeon is undressed and every room is "An Unnamed Chamber" — navigation still works, and this is the cheaper loop for checking that it does.

- [ ] **Step 7: Commit**

```bash
git add client/src/types.ts client/src/store.ts client/src/ui/Canvas.tsx \
        client/src/scene/Renderer.ts
git commit -m "Show the player the way out, and let them take it"
```

---

### Task 9: What the party is standing next to

Spec §6b's actual reason for free movement. `m0-evaluation.md` §4.3 named grounding as the metric this project lives or dies on, and this is an input channel that serves it for the cost of a few fields: a player who walks to the sarcophagus and types "I examine this" no longer requires the narrator to guess the referent.

The DM is also told the ways out, because `dm-tools.md` already forbids inventing an exit and until now the world state never listed any. And it is told which rooms the party has already been in, which is the cheapest possible answer to spec §7's coherence problem — a DM that cannot remember room 3 contradicts itself when the player walks back into it.

**Files:**
- Modify: `server/src/main/java/dm/ai/DmService.java`
- Modify: `server/src/main/java/dm/generate/RoomDresser.java`
- Modify: `server/src/main/resources/prompts/dress-room.md`
- Test: `server/src/test/java/dm/ai/WorldStateTest.java`

**Interfaces:**
- Consumes: `GameEngine.room()`, `GameEngine.roomName(String)`, `GameRepository.visitedRoomIds()`, `Exit`
- Produces: three new blocks in the string `DmService.worldState` builds. No new public method.

- [ ] **Step 1: Write the failing test**

```java
package dm.ai;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.generate.Dungeon;
import dm.model.Diff;
import dm.model.NarrationSegment;
import dm.model.RollResult;
import dm.repo.InMemoryGameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the narrator is told about where the party is.
 *
 * <p>Spec §6b: position becomes DM context, and that is the actual reason to allow free
 * movement. These tests read the prompts the two models are handed, because that is the only
 * place the claim is checkable — the alternative is judging prose, which is what the play
 * sessions are for.
 */
class WorldStateTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private record Prompts(List<String> tools, List<String> prose) {
        boolean any(String fragment) {
            return tools.stream().anyMatch(p -> p.contains(fragment))
                    || prose.stream().anyMatch(p -> p.contains(fragment));
        }
    }

    /** Runs one free-text turn and returns every system prompt the two models were handed. */
    private static Prompts turn(GameEngine engine) {
        var tools = new ScriptedDmClient();
        var prose = new ScriptedDmClient("The room is quiet.");
        new DmService(tools, prose, engine, CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"),
                CONTENT.prompt("dm-reconcile"))
                .handleFreeText("fighter", "I look around", new NoopSink());

        return new Prompts(systemPrompts(tools), systemPrompts(prose));
    }

    private static List<String> systemPrompts(ScriptedDmClient client) {
        return client.conversations().stream()
                .flatMap(List::stream)
                .filter(m -> "system".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .toList();
    }

    @Test
    @DisplayName("the DM is told what the party is standing next to")
    void adjacentPropsAreNamed() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();
        // The crypt's sarcophagus is at (6,7). Stand beside it.
        engine.moveTo("fighter", 6, 6, new dm.engine.CombatSink.Buffer());

        var prompts = turn(engine);

        assertTrue(prompts.any("Where the party is standing"), "no position block");
        assertTrue(prompts.any("`sarcophagus`"), "the tomb it is standing against went unmentioned");
    }

    @Test
    @DisplayName("standing in open floor says so, rather than saying nothing")
    void openFloorIsStated() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();
        // (5,3): the nearest prop is a pillar at (3,4), two squares away.
        engine.moveTo("fighter", 5, 3, new dm.engine.CombatSink.Buffer());

        var prompts = turn(engine);

        assertTrue(prompts.any("Where the party is standing"));
        assertTrue(prompts.any("open floor"), "an empty block invites the model to fill it");
    }

    @Test
    @DisplayName("a hidden prop the party is standing on is still hidden")
    void hiddenPropsAreNotLeaked() {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(CONTENT, repo, new RandomDiceRoller());
        engine.start();
        // The crypt's alcove is hidden, at (9,6).
        engine.moveTo("fighter", 9, 5, new dm.engine.CombatSink.Buffer());

        var prompts = turn(engine);

        long mentions = prompts.tools().stream()
                .filter(p -> p.contains("Where the party is standing"))
                .filter(p -> p.substring(p.indexOf("Where the party is standing"))
                        .contains("`alcove`"))
                .count();
        assertEquals(0, mentions, "an unrevealed alcove was named as something within reach");
    }

    @Test
    @DisplayName("the DM is told the ways out, and that there are no others")
    void exitsAreListed() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(),
                new RandomDiceRoller(), dungeon);
        engine.start();

        var prompts = turn(engine);

        assertTrue(prompts.any("Ways out"), "no exits block");
        assertTrue(prompts.any(engine.room().exits().getFirst().id()));
        assertTrue(prompts.any("Do not invent another"));
    }

    @Test
    @DisplayName("a room with no way out says so, and the crypt is one")
    void noExitsIsStated() {
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(), new RandomDiceRoller());
        engine.start();

        var prompts = turn(engine);

        assertTrue(prompts.any("Ways out"));
        assertTrue(prompts.any("None the party has found"));
    }

    @Test
    @DisplayName("the DM is reminded of the rooms already walked through")
    void visitedRoomsAreRecalled() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(),
                new RandomDiceRoller(), dungeon);
        engine.start();
        var entrance = engine.room().roomId();
        engine.enterRoom(engine.room().exits().getFirst());

        var prompts = turn(engine);

        assertTrue(prompts.any("Where the party has been"), "no history block");
        assertTrue(prompts.any(engine.roomName(entrance)));
    }

    @Test
    @DisplayName("one room visited is not a history worth reciting")
    void oneRoomIsNoHistory() {
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(), new RandomDiceRoller());
        engine.start();

        assertFalse(turn(engine).any("Where the party has been"));
    }

    private static final class NoopSink implements TurnSink {
        @Override
        public void narration(NarrationSegment segment) {
        }

        @Override
        public void diffs(List<Diff> diffs) {
        }

        @Override
        public void roll(RollResult result) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Throwable error) {
            fail(error);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.ai.WorldStateTest'`
Expected: FAIL — `no position block`

- [ ] **Step 3: Write the three blocks**

In `server/src/main/java/dm/ai/DmService.java`, add `import dm.model.Entity;` and `import dm.model.Exit;`.

Insert the call to `appendWaysOut` immediately after the visible-props loop in `worldState`, before the hidden-props block:

```java
        appendWaysOut(sb, room);
```

Insert `appendPosition` immediately after the `## Entities present` loop, before the combat block:

```java
        appendPosition(sb);
```

Insert `appendHistory` immediately before the `## Grid` block:

```java
        appendHistory(sb);
```

Then add the three methods beside `appendNote`:

```java
    /**
     * The doors, and the standing rule that they are all of them.
     *
     * <p>{@code dm-tools.md} has always said "do not invent a creature, object, or exit that is
     * not in the state below", and until M1 the state below never listed an exit — so the rule
     * was either vacuous or a flat prohibition, depending on how the model read it. Now it is
     * a closed set, which is invariant #7 applied to architecture.
     */
    private static void appendWaysOut(StringBuilder sb, RoomDefinition room) {
        sb.append("\n## Ways out\n\n");
        if (room.exits().isEmpty()) {
            sb.append("None the party has found. Do not invent one.\n");
            return;
        }
        for (var exit : room.exits()) {
            sb.append("- `").append(exit.id()).append("` — a door in the ")
                    .append(exit.direction().name().toLowerCase())
                    .append(" wall, at (").append(exit.x()).append(",").append(exit.y())
                    .append("). The party leaves by walking onto it.\n");
        }
        sb.append("\nThese are the only ways out of this room. Do not invent another.\n");
    }

    /**
     * Where the party is, in terms of what is within arm's reach of it.
     *
     * <p>Spec §6b, and the whole reason free movement is in this milestone. The coordinates were
     * always here; what was missing is the sentence that turns them into a referent. A player
     * who has walked to the tomb and types "I examine this" is not being ambiguous — the board
     * says exactly what they mean, and until now the narrator had to guess.
     *
     * <p>Hidden props are excluded. Standing next to a secret is not finding it, and a
     * narrator told there is an unrevealed alcove within reach will write the player noticing
     * it.
     */
    private void appendPosition(StringBuilder sb) {
        var player = engine.repo().entities().stream()
                .filter(Entity::isPlayerControlled)
                .filter(Entity::isAlive)
                .findFirst()
                .orElse(null);
        if (player == null) {
            return;
        }
        var revealed = engine.repo().revealedPropIds();
        var within = engine.room().props().stream()
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .filter(p -> Math.max(Math.abs(p.x() - player.x()),
                        Math.abs(p.y() - player.y())) <= 1)
                .map(p -> "`" + p.id() + "`")
                .collect(Collectors.joining(", "));

        sb.append("\n## Where the party is standing\n\n");
        if (within.isEmpty()) {
            sb.append(player.name()).append(" is on open floor, with nothing within reach.\n");
            return;
        }
        sb.append(player.name()).append(" is standing against ").append(within).append(".\n")
                .append("An unqualified \"this\", \"it\" or \"the lid\" from the player means "
                        + "one of these, unless they say otherwise.\n");
    }

    /**
     * The rooms already walked through.
     *
     * <p>Spec §7 names coherence as the second limit on how large a world can get, and the
     * cheapest part of the answer is that the DM should not describe room 3 as though the party
     * has never been in it. The transcript carries this too, uncompacted (shortcut #11) — but
     * the transcript is where it gets lost, and this is one line.
     */
    private void appendHistory(StringBuilder sb) {
        var visited = engine.repo().visitedRoomIds();
        if (visited.size() < 2) {
            return;
        }
        sb.append("\n## Where the party has been\n\n")
                .append("In order, and all of it already described to them: ")
                .append(visited.stream().map(engine::roomName)
                        .collect(Collectors.joining(", ")))
                .append(".\n");
    }
```

- [ ] **Step 4: Tell the dresser a door is a way out**

In `server/src/main/java/dm/generate/RoomDresser.java`, extend `describe` so the model knows which walls open. Replace the `return` in `describe` with:

```java
        String ways = room.exits().isEmpty()
                ? "There is no way out of this room."
                : "Ways out: " + room.exits().stream()
                        .map(e -> e.direction().name().toLowerCase())
                        .collect(Collectors.joining(", ")) + ".";

        return """
                The room is %d by %d squares. The floor is %s, the walls are %s, and it is %s.
                %s

                Objects in it, by id — describe these and only these:
                %s
                """.formatted(
                room.shape().width(), room.shape().height(),
                room.shape().floorType().name().toLowerCase().replace('_', ' '),
                room.shape().wallType().name().toLowerCase(),
                room.shape().lighting().name().toLowerCase(),
                ways,
                props);
```

In `server/src/main/resources/prompts/dress-room.md`, add one rule to the list:

```markdown
- **A door leads somewhere.** The doors in the list open onto other rooms and they are not
  sealed. Describe the door — never what is behind it, which you have not been told and which
  the player has not seen.
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd server && ./gradlew test --tests 'dm.ai.WorldStateTest'`
Expected: PASS, 7 tests

- [ ] **Step 6: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS — including `RoomSourceTest.generatedRoomPromptContainsNoNulls`, which reads every prompt for the word "null" and now reads three more blocks

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/ai/DmService.java \
        server/src/main/java/dm/generate/RoomDresser.java \
        server/src/main/resources/prompts/dress-room.md \
        server/src/test/java/dm/ai/WorldStateTest.java
git commit -m "Tell the DM what the party is standing against, and where the doors are"
```

---

### Task 10: Narration covers the threshold

Spec §5, and the pattern the whole design rests on: **the DM describes the party passing through the door while the next room is being dressed.** This is the same trick as dice covering prose latency, which M0 measured and confirmed works — and `m0-evaluation.md` §4.1 found that uncovered waits are the only ones players notice.

Two prose calls, and the split is deliberate. The first cannot know what is on the other side, because nothing does yet; the second cannot begin until it does. Running the first *while* the room is built is what makes the wait free. If the threshold still hangs, spec §11 names the fix and it is not prefetch — it is measuring which half is slow.

**Files:**
- Modify: `server/src/main/java/dm/ai/DmService.java`
- Modify: `server/src/main/java/dm/WsHandler.java`
- Test: `server/src/test/java/dm/ai/ThresholdTest.java`

**Interfaces:**
- Consumes: `GameEngine.prepare(String)`, `GameEngine.enterRoom(Exit)`, `GameEngine.exitAt(int,int)`, `TurnSink`
- Produces, on `DmService`:
  - `boolean narrateThreshold(Direction direction, TurnSink sink)` — narrates the crossing and **does not** close the turn
  - `void describeArrival(TurnSink sink)` — narrates the new room and closes the turn
- `WsHandler` gains a private `crossThreshold(WsContext, Exit)`, called from the `moveTo` case

- [ ] **Step 1: Write the failing test**

```java
package dm.ai;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.generate.Dungeon;
import dm.model.Diff;
import dm.model.Direction;
import dm.model.NarrationSegment;
import dm.model.RollResult;
import dm.repo.InMemoryGameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §5: the DM covers the dress pass by talking about the doorway.
 *
 * <p>What is checkable offline is the shape of the two calls — that the crossing is described
 * without claiming to know what is on the other side, that the arrival is described after the
 * room has actually swapped, and that exactly one of them closes the turn. Whether it *reads*
 * well is a play session's job.
 */
class ThresholdTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine engine() {
        var dungeon = Dungeon.generate(CONTENT, null, "crypt", 7, 8);
        var engine = new GameEngine(CONTENT, new InMemoryGameRepository(),
                new RandomDiceRoller(), dungeon);
        engine.start();
        return engine;
    }

    private static DmService dm(GameEngine engine, ScriptedDmClient prose) {
        return new DmService(new ScriptedDmClient(), prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
    }

    private static String lastSystemPrompt(ScriptedDmClient client) {
        var conversation = client.conversations().getLast();
        return conversation.stream()
                .filter(m -> "system".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static String lastUserPrompt(ScriptedDmClient client) {
        var conversation = client.conversations().getLast();
        return conversation.stream()
                .filter(m -> "user".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("the crossing is narrated, and the turn is not closed by it")
    void thresholdNarratesWithoutClosing() {
        var engine = engine();
        var prose = new ScriptedDmClient("The door grinds back on a wedge of dark.");
        var sink = new RecordingSink();

        boolean ran = dm(engine, prose).narrateThreshold(Direction.NORTH, sink);

        assertTrue(ran);
        assertFalse(sink.text().isBlank(), "nothing was narrated");
        assertEquals(0, sink.completions(),
                "the crossing closed the turn, so the input opens mid-doorway");
    }

    @Test
    @DisplayName("the crossing directive forbids describing the room ahead")
    void thresholdRefusesToLookAhead() {
        var engine = engine();
        var prose = new ScriptedDmClient("The door grinds back.");

        dm(engine, prose).narrateThreshold(Direction.NORTH, new RecordingSink());

        var directive = lastUserPrompt(prose);
        assertTrue(directive.contains("north"), directive);
        assertTrue(directive.toLowerCase().contains("not"), directive);
    }

    @Test
    @DisplayName("the arrival is narrated against the room the party is now in, and closes the turn")
    void arrivalClosesTheTurn() {
        var engine = engine();
        var exit = engine.room().exits().getFirst();
        engine.enterRoom(exit);

        var prose = new ScriptedDmClient("Water stands in the aisles.");
        var sink = new RecordingSink();

        dm(engine, prose).describeArrival(sink);

        assertEquals(1, sink.completions());
        assertTrue(lastSystemPrompt(prose).contains(engine.room().name()),
                "the arrival was described against the wrong room");
    }

    private static final class RecordingSink implements TurnSink {
        private final List<String> segments = new ArrayList<>();
        private final AtomicInteger completions = new AtomicInteger();

        String text() {
            return String.join(" ", segments);
        }

        int completions() {
            return completions.get();
        }

        @Override
        public void narration(NarrationSegment segment) {
            segments.add(segment.text());
        }

        @Override
        public void diffs(List<Diff> diffs) {
        }

        @Override
        public void roll(RollResult result) {
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
        }

        @Override
        public void error(Throwable error) {
            fail(error);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.ai.ThresholdTest'`
Expected: FAIL — compilation error, `cannot find symbol: method narrateThreshold`

- [ ] **Step 3: Add the two narrations**

In `server/src/main/java/dm/ai/DmService.java`, add `import dm.model.Direction;`, the two directives beside `COMBAT_BEAT`, and the two methods beside `narrateCombat`:

```java
    /**
     * The directive for a crossing.
     *
     * <p>The prohibition is the whole of it. This call runs <em>while</em> the next room is being
     * generated and dressed (spec §5), so there is nothing on the other side yet — and a prose
     * model given a doorway will walk through it and start describing the hall beyond, which the
     * player then arrives in and finds is somewhere else. That is the sarcophagus failure again:
     * the writer believes writing a thing is how a thing happens.
     */
    private static final String THRESHOLD = "The party is leaving this room through the %s "
            + "door. Narrate the crossing and nothing past it — the door, the threshold, the "
            + "first steps into the dark. Two sentences. You have not seen the next room and "
            + "must not describe it, name it, or say what is in it.";

    /** The directive on arrival. Deliberately the opening's shape: this is a room opening. */
    private static final String ARRIVAL = "The party has just walked in through a door behind "
            + "them. Open the room: what they walk into. Do not describe the door they came "
            + "through, and do not recap the passage.";
```

```java
    /**
     * Narrates the party crossing a threshold, while the room they are crossing into is built.
     *
     * <p><b>Does not close the turn.</b> {@link #describeArrival} does, once the scene has
     * actually swapped. Two narrations, one turn: the client gave the DM the floor when the
     * player stepped into the doorway and must not get it back in the middle.
     *
     * <p>Dropped rather than queued if the DM is already speaking, exactly as a combat beat is.
     *
     * @return whether it ran
     */
    public boolean narrateThreshold(Direction direction, TurnSink sink) {
        if (!narrating.tryLock()) {
            log.info("dropped a threshold beat, the DM is already speaking");
            return false;
        }
        try {
            var failed = new boolean[]{false};
            long started = System.nanoTime();
            var prose = runProsePhase(
                    THRESHOLD.formatted(direction.name().toLowerCase()),
                    List.of(), sink, failed, false);
            if (failed[0]) {
                return false;
            }
            history.add(DmClient.ChatMessage.assistant(prose.text()));
            log.info("CROSSING first token {}ms  total {}ms  {} chars",
                    prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                    prose.text().length());
            return true;
        } finally {
            narrating.unlock();
        }
    }

    /**
     * Opens the room the party has just walked into.
     *
     * <p>Waits for the floor rather than dropping, unlike the crossing: a room nobody described
     * is a room the player is standing in with no idea what it is, which is the one silence
     * this milestone cannot afford. It closes the turn either way — the client has been holding
     * its input since the click.
     */
    public void describeArrival(TurnSink sink) {
        narrating.lock();
        try {
            var failed = new boolean[]{false};
            long started = System.nanoTime();
            var prose = runProsePhase(ARRIVAL, List.of(), sink, failed, false);
            if (!failed[0]) {
                history.add(DmClient.ChatMessage.assistant(prose.text()));
                log.info("ARRIVAL  first token {}ms  total {}ms  {} chars",
                        prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                        prose.text().length());
            }
        } finally {
            narrating.unlock();
        }
        sink.complete();
    }
```

- [ ] **Step 4: Run the narration tests**

Run: `cd server && ./gradlew test --tests 'dm.ai.ThresholdTest'`
Expected: PASS, 3 tests

- [ ] **Step 5: Cross the threshold from the socket**

In `server/src/main/java/dm/WsHandler.java`, add `import dm.model.Exit;`, replace the `moveTo` case, and add the method.

```java
            case "moveTo" -> {
                int x = message.path("x").asInt();
                int y = message.path("y").asInt();
                act(ctx, sink -> engine.moveTo(message.path("actorId").asText(), x, y, sink));
                // Only if the move was actually made — an illegal one threw out of act() above
                // and never reaches here. A door square is a way out only outside a fight; in
                // combat the engine refuses to leave and the click was just a step.
                if (!engine.combat().isActive()) {
                    engine.exitAt(x, y).ifPresent(exit -> crossThreshold(ctx, exit));
                }
            }
```

```java
    /**
     * Takes the party through a door, with the DM covering the wait.
     *
     * <p>Spec §5's data flow, and the one place in this build where two slow things are
     * deliberately run against each other: the next room is generated and dressed on one
     * virtual thread while the DM narrates the doorway on another. Neither waits for the other.
     * The swap waits for both, because a scene that lands mid-sentence is a room the player is
     * looking at while being told they are still in the last one.
     *
     * <p>The whole thing is off the socket thread. It spends seconds.
     */
    private void crossThreshold(WsContext ctx, Exit exit) {
        turns.submit(() -> {
            var sink = turnSink(ctx);
            // Started first, so the model call has the dress pass to hide behind rather than
            // the other way round.
            var ready = turns.submit(() -> engine.prepare(exit.toRoomId()));

            boolean narrated = dm != null && dm.narrateThreshold(exit.direction(), sink);

            try {
                ready.get();
            } catch (Exception e) {
                log.error("could not prepare {}", exit.toRoomId(), e);
                send(ctx, new ServerMessage.Error("The way ahead is unfinished: " + e.getMessage()));
                // The client has been holding its input since the click; something has to give
                // it back, and an error is not a narration.
                send(ctx, new ServerMessage.NarrationEnd());
                return;
            }

            try {
                engine.enterRoom(exit);
            } catch (RuntimeException e) {
                log.error("could not enter {}", exit.toRoomId(), e);
                send(ctx, new ServerMessage.Error(String.valueOf(e.getMessage())));
                send(ctx, new ServerMessage.NarrationEnd());
                return;
            }

            // A whole scene, not diffs. Everything in the room changed, including the floor.
            send(ctx, new ServerMessage.Scene(engine.scene()));
            log.info("crossed {} into {}{}", exit.direction().name().toLowerCase(),
                    exit.toRoomId(), narrated ? "" : " (uncovered — the DM was busy)");

            if (dm != null) {
                dm.describeArrival(sink);
            } else {
                send(ctx, new ServerMessage.NarrationEnd());
            }
        });
    }
```

- [ ] **Step 6: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS

- [ ] **Step 7: Walk three rooms with the DM running**

```bash
cd server && ./gradlew run --args='--generate 7'
```

Open the client, click through the title, and walk through three doors. What to watch, in the server log:

- `CROSSING first token …ms` should arrive well before the dress pass finishes. If the crossing's first token lands *after* the room is built, the cover is doing nothing and the order in `crossThreshold` is wrong.
- `dressed room-N as '…'` should appear once per room and never twice for the same one.
- Walking back into a room should log no dress pass at all.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/ai/DmService.java \
        server/src/main/java/dm/WsHandler.java \
        server/src/test/java/dm/ai/ThresholdTest.java
git commit -m "Talk the party through the door while the next room is written"
```

---

## Done when

`./gradlew run --args='--generate 7'` boots into a generated dungeon, and you can:

1. Descend three rooms. Each reads as a distinct place.
2. Walk back through the door you came in by and find the room you left — same name, same prose, the goblin still where you wounded it.
3. Fight in one room, and be refused when you try to walk out of the fight.
4. Read the whole transcript afterwards and find nothing the narrator said that the board contradicts.

That last clause is the milestone. `m0-evaluation.md` §4.3 established multi-turn consistency as this project's real metric, and generated content across several rooms is a much larger surface for the narrator to contradict.

Then judge it the way M0 was judged. Three specific things to look at, because they are the three the spec calls out as the ways this fails:

- **Mush** (spec §11). Dump twenty dungeons with `--dump`, read the rooms, and answer whether room 3 and room 6 are two places or one place twice. If they are one place, the dress pass is where it is won or lost and the M0 finding applies: terse instructions beat explanatory ones, and it should be measured rather than argued about.
- **Threshold latency** (spec §11). Time a crossing from click to the first word of the crossing narration, and from there to the scene swap. If the second number is much larger than the first, the dress pass is not covered and the fix is on that side, not in prefetch.
- **False affordance** (spec §6b). Watch whether you try to click the sarcophagus after walking to it. Being able to walk to a thing implies being able to click it, and typing is still the only interaction verb. If it bites, the fix is a narrower legal-move set, not an interaction model.

What those answers decide is whether M2 is the rules engine, as spec §3 assumes, or whether generation needs another pass first.

---

## Follow-ups after the mush check (spec §11)

None of this is in this plan. It is written down because the gate above — dump twenty dungeons, read them, decide whether room 3 and room 6 are two places or one place twice — is what chooses between these, and the reasoning should reach whoever runs it.

**Run the check before reaching for any of it.** Each item fixes a different failure. Picking one now is picking a fix before knowing what broke.

### Kit density, which is probably the answer

Do the arithmetic on [crypt.json](server/src/main/resources/content/kits/crypt.json) before blaming the algorithm. Counts are drawn uniformly from each entry's range, so the expected room holds:

| Prop | Range | Mean |
|---|---|---|
| Sarcophagus | 1–1 | 1 |
| Brazier | 0–4 | 2 |
| Pillar | 0–4 | 2 |
| Rubble | 0–3 | 1.5 |
| Alcove | 0–2 | 1 |
| | | **7.5** |

Rooms are 10–16 squares on a side, so a median room is about 169 squares holding seven or eight objects — **four per cent coverage**. And every minimum except the sarcophagus is zero, so a real fraction of rooms contain one prop. No placement algorithm and no dress pass rescues a room with one object in it; the room is empty because it is empty.

Two dials, in order of yield:

1. **Raise the minimums.** A floor of three or four props per type-group is what stops the empty outlier, and it is a one-line change per entry. Watch `RoomGenerator.MAX_ATTEMPTS` afterwards — minimums that a small room cannot satisfy turn into reseeds, and a kit that cannot produce a legal room in fifty tries throws.
2. **Widen the catalog.** More kinds of thing beats more of the same thing: four braziers in a room is a lighting scheme, and a brazier, a bier, a shattered urn and a votive shelf is a place.

**What a new prop type actually costs**, since "it's just a JSON file" is true of counts and not of types. Six files, and the type system walks you through four of them:

| File | Change | Enforced? |
|---|---|---|
| `dm/model/PropType.java` | the enum value, and an arm in `blocksMovement()` | compiler |
| `dm/generate/PropPlacer.java` | an arm in `affinityOf` | compiler |
| `dm/generate/RoomDumper.java` | an arm in `glyphFor` | compiler |
| `client/src/scene/props.ts` | an entry in `BUILDERS`, a total `Record<PropType, Builder>` | tsc |
| `client/src/types.ts` | the `PropType` union | by hand |
| `content/kits/*.json` | the counts | by hand |

`MESH_PROPS` is a `Partial` record and optional — a type with no entry gets its procedural builder instead. The binding constraint is the one already written into that file's javadoc: **every model behind a type has to be the same noun.** The DM is told a prop is `RUBBLE` and writes the word "rubble", so a broken amphora standing where the map says rubble is the picture contradicting the narrator.

Note that `AGENTS.md` invariant #10 still says "do not add tools, entity types, props, or rules beyond what is listed below". That was an M0 scope fence, and spec §3 puts the prop catalog inside M1 on purpose. Update `AGENTS.md` when the first new prop lands rather than leaving the next reader to guess which document is stale.

**A second kit is cheap only if it reuses existing types.** `ContentLoader.kit(String)` already loads any file in `content/kits/`, so an `ossuary.json` that draws different counts from the same six types is genuinely one JSON file. It is a different room, not a different environment — spec §7b's village is the expensive kind and is not this.

### Four procedural-generation algorithms, weighed

The thing that decides each one is that this codebase generates at two scales, and each algorithm only fits one of them. **Layout** (Task 1) is pure topology — cells and doorways, no geometry, no walkable space between rooms. **Room interiors** are a rectangle from `ShapeGenerator` with props arranged by affinity.

| Algorithm | Where it would go | Verdict |
|---|---|---|
| Binary space partitioning | Room interiors | Worth trying, if the failure is structure |
| Perlin / simplex noise | Floor-tile variation now; terrain in §7b | Small yes, then a definite later |
| Cellular automata | Nothing in M1 | Wait for a natural-cavern kit |
| Wave function collapse | Floor tiling | No |

**Binary space partitioning.** The textbook use — carve a rectangle into sub-rectangles, put a room in each leaf, join siblings with corridors — is dead on arrival here: it produces a continuous floorplan with walkable corridors, which is exactly what spec §7 rules out. It has nothing to offer the layout either, where the cell grid exists only to decide adjacency and carries no geometry to partition.

Partitioning a **single room** is the real fit, and it targets a problem the code already admits to. `Renderer.buildWallTorches` explains itself by saying the generated rooms are large and their props sit around the edges, leaving the middle of the floor an unlit void that reads as missing rather than dark. That is structural: of six prop types, three have a `WALL` affinity, one is `OFF_WALL` and one is on the centre axis. The room is a perimeter with a hole in it.

BSP zoning would replace "each prop knows where it likes to stand" with "the room divides into regions and each region is *something*" — a nave, a side aisle, a collapsed corner. It also gives the dress pass more to work with: naming a region is a stronger prompt than listing objects. The cost is a real rewrite of `PropPlacer`, which is currently the most carefully tuned file in the generator, and every measurement in its javadoc would need retaking.

**Perlin and simplex noise.** One use today: the client picks each floor tile independently from a weighted mix, so a `CRACKED_STONE` floor is salt-and-pepper. Sampling 2D noise per square instead would make wear *cluster* — damage concentrated in one corner, intact courses elsewhere. Perhaps thirty lines in `props.ts`, and it breaks no invariant: the tile choice is already a client decision hashed from the room id.

The larger payoff is later. When spec §7b's mountain pass or village lands, noise is the obvious tool for terrain, scatter and biome edges. Nothing in a crypt needs it.

**Cellular automata.** Random fill plus smoothing iterations produces organic, blobby caves. A crypt is the opposite: `dress-room.md` says a crypt was built by someone, for someone, and CA output is unbuilt by construction. There is a narrow use — growing more natural spill shapes for the `CLUSTER` affinity, which is currently a hand-rolled "debris collects" rule — but it is marginal against everything else here. Revisit it when a kit exists whose fiction is a natural cavern, where it becomes the right tool rather than a stylistic mismatch.

**Wave function collapse.** No. Its sweet spot is a rich tileset with strong adjacency constraints where the goal is output that looks hand-authored; this project answers "looks hand-authored" with curated Kenney kits and an LLM dress pass. Against two or three floor variants per preset the payoff is small, and WFC brings the hardest debugging of the four — contradiction states and backtracking. It cannot help with props either: it guarantees *local* adjacency, and `SpatialValidator` needs *global* reachability, so every solve would still have to pass a flood fill that WFC's constraints know nothing about.
