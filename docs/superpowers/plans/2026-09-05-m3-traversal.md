# M3 Traversal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Walk out of the crypt through its north door, do things in the room beyond for long enough that the transcript window forgets the crypt, walk back, and find it exactly as you left it — with nothing the DM says contradicting the room it is standing in.

**Architecture:** `WorldState` gains a room dimension the way `Fact` already has one: every keyed thing carries its `roomId` and the accessor filters. A room is composed on read from a reproducible structure plus a `Dressing` folded out of the log, so the non-deterministic half of a room survives without a second write path. Crossing is one event, `PartyMoved`, reachable from a click and from a mechanics-phase tool. The client renders the adjacent room as unlit geometry, which the DM is never told about and therefore cannot contradict.

**Tech Stack:** Java 25 (records, sealed interfaces, pattern matching), Jackson 2.18.4, JUnit 5.12.2, Gradle 9.7 Kotlin DSL, Javalin 6.7, Godot 4.7 Forward Plus with GUT. No new dependencies.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-09-05-m3-traversal-design.md`. Where this plan and the spec disagree, the spec wins.
- **Invariant #1 — the server is authoritative.** The client never computes a roll, a hit, a legal move, or a death.
- **Invariant #2 — no singleton player.** `List<PartyMember>`, always. Every action carries an `actorId`.
- **Invariant #3 — game state never lives in a Control or Node3D.** It lives in `Table`.
- **Invariant #4 — the 3D world is one scene, created once.** Two rooms rendered is still one scene.
- **Invariant #7 — all LLM-facing enums are closed and validated server-side.** No free-form string from the model reaches the engine. Tools use `strict: true` where already set.
- **Invariant #8 — every state change is an event, and state is only ever a fold over the log.** No setter, no `put`. If a change did not emit an event it did not happen.
- **Invariant #9 — modern Java only.** Records, sealed interfaces, pattern matching. No Spring, no `AbstractXFactory`, no mutable POJOs.
- **Invariant #10 — do not add tools, entity types, props, or rules beyond what is listed in AGENTS.md.** M3 adds exactly two tools, both named in the spec; AGENTS.md is updated in Task 15 and nowhere else.
- **Wire mirrors are hand-written.** Every change to a Java record in `dm.model` or `dm.wire` gets the matching GDScript reader in the same commit.
- **Commit messages** are a sentence saying what the commit does, in the repository's existing voice (`git log` for examples). No `feat:` prefixes, no `Co-Authored-By` trailer.
- **Never commit or log an API key.** `VENICE_API_KEY` and `ELEVENLABS_API_KEY` come from a gitignored `.env`.
- **Test command:** `cd server && ./gradlew test`
- **Client tests:** `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
- **Run command:** `cd server && ./gradlew run`

## Not in this plan

- **`LayoutGenerator`, `ExitPlacer`, generated multi-room dungeons.** Spec §2 and §12. The next plan.
- **World-space packing.** Two authored rooms means one hand-placed offset. Spec §3.
- **Party splits.** `PartyMoved` carries a list of movers; the policy is that the list is everyone. Spec §6a.
- **Fleeing.** Exits are illegal in combat. Spec §3.
- **Generated secrets and hidden props in generated rooms.** Spec §12a records the finding; it is generator-plan work.
- **A load-bearing dungeon seed.** Spec §5c describes what `SessionStarted.seed` becomes once a
  dungeon is generated from it. M3's two rooms are authored, so there is no seed to record and the
  field stays `0L`. The generator plan fills it; the arrangement that makes it meaningful — layout
  regenerated, dressing folded — is built here.

## File Structure

| File | Responsibility |
|---|---|
| `server/src/main/java/dm/model/Direction.java` | The four sides of a room, and the rotation a prop in each takes |
| `server/src/main/java/dm/model/Exit.java` | The edge between two scenes, and the square you land on coming through |
| `server/src/main/java/dm/model/PropRef.java` | A prop id qualified by its room — the key a reveal is remembered under |
| `server/src/main/java/dm/model/RoomOutline.java` | A neighbour room as the client needs it: size, materials, world offset |
| `server/src/main/java/dm/engine/Rooms.java` | Where the engine gets a room by id, structure and dressing composed |
| `server/src/main/java/dm/content/Dressings.java` | An authored room's `Dressing`, extracted from the fields it is spread across |
| `server/src/main/java/dm/model/Event.java` | *(modify)* `PartyMoved`; `SCHEMA_VERSION` to 2 |
| `server/src/main/java/dm/model/Entity.java` | *(modify)* carries `roomId`; `movedToRoom` |
| `server/src/main/java/dm/model/SceneState.java` | *(modify)* carries exits and neighbour outlines |
| `server/src/main/java/dm/model/RoomDefinition.java` | *(modify)* carries exits; `exitAt` |
| `server/src/main/java/dm/state/WorldState.java` | *(modify)* room scope: entities, reveals, visits, dressings |
| `server/src/main/java/dm/engine/GameEngine.java` | *(modify)* many rooms; `crossExit`; room-aware `scene()` |
| `server/src/main/java/dm/engine/CombatEngine.java` | *(modify)* terrain from the current room, not a fixed one |
| `server/src/main/java/dm/ai/ToolSchema.java` | *(modify)* `use_exit`, `move_entity`; living actors only |
| `server/src/main/java/dm/ai/ToolDispatcher.java` | *(modify)* the two new tools |
| `server/src/main/java/dm/ai/DmService.java` | *(modify)* ways out, threshold marker, visited flag, arrival directive |
| `server/src/main/java/dm/replay/ReplayRunner.java` | *(modify)* drives `PartyMoved` |
| `server/src/main/java/dm/WsHandler.java` | *(modify)* the `enterExit` verb |
| `server/src/test/java/dm/replay/FixtureRecorder.java` | Regenerates `crypt-fight.jsonl` deterministically after a schema bump |
| `server/src/main/resources/content/rooms/crypt.json` | *(modify)* an exit on `door-north`; the sealed-door note goes |
| `server/src/main/resources/content/rooms/gallery.json` | The room behind the north door |
| `server/src/main/resources/prompts/dm.md` | *(modify)* the narrator writes to the threshold, not through it |
| `server/src/main/resources/prompts/dm-tools.md` | *(modify)* when to call `use_exit` and `move_entity` |
| `server/src/main/resources/prompts/dm-reconcile.md` | *(modify)* `move_entity` only, never `use_exit` |
| `godot/world/world.gd` | *(modify)* per-room origins; `grid_to_world(room_id, x, y)` |
| `godot/world/room.gd` | *(modify)* builds a neighbour as unlit geometry |
| `godot/world/overlay.gd` | *(modify)* an exit square is an `exit` intent |
| `godot/autoload/net.gd` | *(modify)* `enter_exit` |
| `godot/autoload/table.gd` | *(modify)* exits and neighbours off the scene |

---

# Stage 1 — The fold gains a dimension

Tasks 1–5 are invisible to a player and are most of the risk. Nothing can be walked until Task 8.

---

### Task 1: `Direction`, `Exit`, and a room that knows its ways out

Pure model plus one content change. No behaviour yet — `RoomDefinition` gains a list nothing reads, so the crypt's north door becomes describable before anything can use it.

`Exit`, never `Door`. The edge is a door in a crypt and a road out of a village; M3 only ever builds doors and the name costs nothing today. Same class of decision as `RollResult.faces` being a list from day one.

**Files:**
- Create: `server/src/main/java/dm/model/Direction.java`
- Create: `server/src/main/java/dm/model/Exit.java`
- Modify: `server/src/main/java/dm/content/RoomDefinition.java`
- Modify: `server/src/main/resources/content/rooms/crypt.json`
- Test: `server/src/test/java/dm/model/ExitTest.java`

**Interfaces:**
- Produces:
  - `Direction` — `NORTH, SOUTH, EAST, WEST`, with `dx()`, `dy()`, `facing()`, `opposite()`
  - `Exit(String id, int x, int y, Direction direction, String toRoomId)`, with `square()` and `inward(int width, int height)`
  - `RoomDefinition` gains `List<Exit> exits` after `props`, and `exitAt(int x, int y)` returning `Optional<Exit>`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/model/ExitTest.java`:

```java
package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a door stands and where you land coming through it.
 *
 * <p>{@code inward} is the whole reason this type has behaviour. Landing in the doorway means
 * landing on the trigger that sent you there, and the party would bounce straight back out.
 */
class ExitTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("opposite round-trips, and the offsets agree with it")
    void directionsAreSymmetric() {
        for (var direction : Direction.values()) {
            assertEquals(direction, direction.opposite().opposite());
            assertEquals(0, direction.dx() + direction.opposite().dx());
            assertEquals(0, direction.dy() + direction.opposite().dy());
        }
    }

    @Test
    @DisplayName("north is increasing y, matching the grid block the DM is shown")
    void northIsIncreasingY() {
        // "axes: x eastward, y northward" — DmService.worldState. Two conventions in one
        // game is how "why did it walk the wrong way" bugs start.
        assertEquals(1, Direction.NORTH.dy());
        assertEquals(0, Direction.NORTH.dx());
        assertEquals(1, Direction.EAST.dx());
        assertEquals(0, Direction.EAST.dy());
    }

    @Test
    @DisplayName("the square inside a door is one step in, and on the grid")
    void inwardIsOneStepInside() {
        var north = new Exit("door-north", 6, 11, Direction.NORTH, "gallery");
        assertEquals(new Square(6, 10), north.inward(12, 12));

        var south = new Exit("door-south", 6, 0, Direction.SOUTH, "crypt");
        assertEquals(new Square(6, 1), south.inward(12, 12));

        var east = new Exit("door-east", 11, 5, Direction.EAST, "gallery");
        assertEquals(new Square(10, 5), east.inward(12, 12));

        var west = new Exit("door-west", 0, 5, Direction.WEST, "gallery");
        assertEquals(new Square(1, 5), west.inward(12, 12));
    }

    @Test
    @DisplayName("inward never leaves the grid, even in a one-square room")
    void inwardIsClamped() {
        var exit = new Exit("door-north", 0, 0, Direction.NORTH, "gallery");
        var inward = exit.inward(1, 1);
        assertEquals(new Square(0, 0), inward);
    }

    @Test
    @DisplayName("the crypt's north door is an exit, and it is found by its square")
    void cryptCarriesItsExit() {
        var crypt = CONTENT.room("crypt");

        assertEquals(1, crypt.exits().size());
        var exit = crypt.exits().getFirst();
        assertEquals("door-north", exit.id());
        assertEquals(Direction.NORTH, exit.direction());
        assertEquals(11, exit.y(), "the north wall of a 12-high room");

        assertEquals(exit, crypt.exitAt(exit.x(), exit.y()).orElseThrow());
        assertTrue(crypt.exitAt(0, 0).isEmpty());
    }

    @Test
    @DisplayName("an exit's id names a prop that is actually in the room")
    void exitsNameRealProps() {
        var crypt = CONTENT.room("crypt");
        for (var exit : crypt.exits()) {
            var prop = crypt.prop(exit.id());
            assertEquals(PropType.DOOR, prop.type());
            assertEquals(exit.x(), prop.x());
            assertEquals(exit.y(), prop.y());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.model.ExitTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Direction`

- [ ] **Step 3: Write `Direction`**

Create `server/src/main/java/dm/model/Direction.java`:

```java
package dm.model;

/**
 * A side of a room, and the way you leave through it.
 *
 * <p>Four values rather than eight. An exit is cut into a wall and a wall has one outward face;
 * the eight-neighbour rule that governs movement is a movement rule, not an architectural one.
 *
 * <p>North is increasing y, which is the convention the DM is handed under {@code ## Grid}
 * ("axes: x eastward, y northward"). Two conventions in one game is how a party walks the wrong
 * way through a door that was described correctly.
 */
public enum Direction {
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

    /** The wall's name as the DM should say it out loud. */
    public String lowerName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
```

- [ ] **Step 4: Write `Exit`**

Create `server/src/main/java/dm/model/Exit.java`:

```java
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
public record Exit(String id, int x, int y, Direction direction, String toRoomId) {

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
```

- [ ] **Step 5: Teach `RoomDefinition` about exits**

In `server/src/main/java/dm/content/RoomDefinition.java`, add the imports `dm.model.Exit` and `java.util.Optional`, add `List<Exit> exits` as a component immediately after `props`, and add the accessor. The record header becomes:

```java
public record RoomDefinition(
        String roomId,
        String name,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        LightingPreset lighting,
        List<PropDefinition> props,
        List<Exit> exits,
        StartPositions startPositions,
        DmNotes dmNotes
) {

    /**
     * Defensive, and null-tolerant because a room file written before exits existed omits the
     * key entirely and Jackson hands us null rather than an empty list.
     */
    public RoomDefinition {
        exits = exits == null ? List.of() : List.copyOf(exits);
    }
```

and add, next to `prop(String id)`:

```java
    /** The exit standing on this square, if one does. */
    public Optional<Exit> exitAt(int x, int y) {
        return exits.stream().filter(e -> e.x() == x && e.y() == y).findFirst();
    }
```

- [ ] **Step 6: Give the crypt its exit**

In `server/src/main/resources/content/rooms/crypt.json`, add an `exits` array immediately after `props`. `door-north` is already a `DOOR` prop at (6, 11) — the north wall of a 12×12 room — so this names a prop that exists rather than adding one:

```json
  "exits": [
    {
      "id": "door-north",
      "x": 6,
      "y": 11,
      "direction": "NORTH",
      "toRoomId": "gallery"
    }
  ],
```

Leave `dmNotes.theDoor` alone for now — the door is still sealed until Task 12 builds what is behind it, and deleting the note before then would let the DM promise a room that does not exist.

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.model.ExitTest'`
Expected: PASS, 6 tests

- [ ] **Step 8: Run the whole suite**

Run: `cd server && ./gradlew test`
Expected: PASS. `RoomDefinition` gained a component with a canonical-constructor default, and every existing caller reads it positionally through Jackson rather than constructing one by hand — if anything constructs a `RoomDefinition` literally, the compiler will say so and it takes `List.of()`.

- [ ] **Step 9: Commit**

```bash
git add server/src/main/java/dm/model/Direction.java \
        server/src/main/java/dm/model/Exit.java \
        server/src/main/java/dm/content/RoomDefinition.java \
        server/src/main/resources/content/rooms/crypt.json \
        server/src/test/java/dm/model/ExitTest.java
git commit -m "Give a room a way out, and say which square you land on coming through it."
```

---

### Task 2: An entity knows which room it is standing in

The first of the two changes that break the recorded fixture, so it also carries the schema bump and the tool that regenerates the fixture. Doing those apart would leave the suite red across a task boundary.

`roomId` sits immediately before `x` so the three components read as one answer to "where is it". `movedTo` keeps the room; `movedToRoom` is how it changes.

**Files:**
- Modify: `server/src/main/java/dm/model/Entity.java`
- Modify: `server/src/main/java/dm/content/EntityDefinition.java`
- Modify: `server/src/main/java/dm/model/Event.java`
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Create: `server/src/test/java/dm/replay/FixtureRecorder.java`
- Modify: `server/src/test/resources/sessions/crypt-fight.jsonl` (regenerated)
- Test: `server/src/test/java/dm/model/EntityRoomTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `Entity` gains `String roomId` immediately before `x`; `movedToRoom(String roomId, int x, int y)`
  - `EntityDefinition.spawn(String entityId, String roomId, int x, int y)`
  - `Event.SCHEMA_VERSION == 2`
  - `dm.replay.FixtureRecorder` (test sources) with a `main` that rewrites `crypt-fight.jsonl`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/model/EntityRoomTest.java`:

```java
package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityRoomTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("a spawned entity remembers which room it was spawned into")
    void spawnCarriesTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        assertEquals("gallery", goblin.roomId());
        assertEquals(4, goblin.x());
        assertEquals(4, goblin.y());
    }

    @Test
    @DisplayName("walking across a room does not change which room it is")
    void movedToKeepsTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        var moved = goblin.movedTo(7, 2);

        assertEquals("gallery", moved.roomId());
        assertEquals(7, moved.x());
        assertEquals(2, moved.y());
    }

    @Test
    @DisplayName("movedToRoom changes the room and the square together")
    void movedToRoomChangesBoth() {
        var fighter = CONTENT.entity("fighter").spawn("fighter", "crypt", 6, 1);

        var arrived = fighter.movedToRoom("gallery", 6, 1);

        assertEquals("gallery", arrived.roomId());
        assertEquals(6, arrived.x());
        // Everything else is untouched — a doorway is not a heal.
        assertEquals(fighter.hp(), arrived.hp());
        assertEquals(fighter.skillModifiers(), arrived.skillModifiers());
    }

    @Test
    @DisplayName("damage and healing leave the room alone")
    void withHpKeepsTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        assertEquals("gallery", goblin.damaged(3).roomId());
        assertEquals("gallery", goblin.withHp(1).roomId());
    }

    @Test
    @DisplayName("the client is never told which room an entity is in — it only gets one room's")
    void viewDoesNotCarryTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        // scene() only ever sends the current room's occupants, so the field would be noise
        // on the wire and a second place for the client to disagree with the server.
        var json = dm.wire.Json.MAPPER.valueToTree(goblin.toView()).toString();
        assertFalse(json.contains("roomId"), json);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.model.EntityRoomTest'`
Expected: FAIL — compilation error, `method spawn in class EntityDefinition cannot be applied to given types`

- [ ] **Step 3: Add `roomId` to `Entity`**

In `server/src/main/java/dm/model/Entity.java`, add the component and the mover. The record header gains one line before `x`:

```java
        int initiativeModifier,
        /** Which room it is standing in. Scoping lives on the entity so there is nothing to desync. */
        String roomId,
        int x,
        int y,
```

Then replace `movedTo` and `withHp`, and add `movedToRoom`:

```java
    public Entity movedTo(int newX, int newY) {
        return new Entity(id, kind, name, ac, hp, maxHp, toHit, damageDice, damageModifier,
                speedFeet, initiativeModifier, roomId, newX, newY, isPlayerControlled,
                skillModifiers);
    }

    /** Through a door. The only thing that changes which room an entity is in. */
    public Entity movedToRoom(String newRoomId, int newX, int newY) {
        return new Entity(id, kind, name, ac, hp, maxHp, toHit, damageDice, damageModifier,
                speedFeet, initiativeModifier, newRoomId, newX, newY, isPlayerControlled,
                skillModifiers);
    }

    public Entity withHp(int newHp) {
        return new Entity(id, kind, name, ac, Math.clamp(newHp, 0, maxHp), maxHp, toHit,
                damageDice, damageModifier, speedFeet, initiativeModifier, roomId, x, y,
                isPlayerControlled, skillModifiers);
    }
```

`toView()` is unchanged — `EntityView` does not gain the field.

- [ ] **Step 4: Thread it through `EntityDefinition.spawn`**

In `server/src/main/java/dm/content/EntityDefinition.java`:

```java
    public Entity spawn(String entityId, String roomId, int x, int y) {
        return new Entity(entityId, kind, name, ac, maxHp, maxHp, toHit, damageDice,
                damageModifier, speedFeet, ability("DEX"), roomId, x, y, isPlayerControlled,
                skillModifiers == null ? Map.of() : skillModifiers);
    }
```

- [ ] **Step 5: Fix the two call sites in `GameEngine`**

In `GameEngine.start()`, the spawn becomes:

```java
            members.add(fighter.spawn(entityId, room.roomId(), at.x(), at.y()));
```

In `GameEngine.spawnGoblin(int x, int y)`:

```java
        var goblin = definition.spawn(definition.id(), room.roomId(), x, y);
```

Both still read the engine's single `room` field; Task 6 replaces that field with a lookup and these lines follow it.

- [ ] **Step 6: Bump the schema**

In `server/src/main/java/dm/model/Event.java`:

```java
    /**
     * Bumped whenever a recorded log stops being readable by this build. Old logs are refused,
     * never upgraded — spec §3. Discarding one is free; an upgrader is a tax paid forever.
     *
     * <p>2 (M3): entities carry a {@code roomId}, so every {@code party_spawned} and
     * {@code entity_spawned} line written at schema 1 describes an entity standing nowhere.
     */
    int SCHEMA_VERSION = 2;
```

- [ ] **Step 7: Write the fixture recorder**

The checked-in `crypt-fight.jsonl` was written at schema 1 and is now refused, which is the designed behaviour and not a bug. It has to be re-recorded, and a schema bump will happen again, so record it with a tool rather than by hand.

Create `server/src/test/java/dm/replay/FixtureRecorder.java`:

```java
package dm.replay;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.SessionWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Rewrites the checked-in replay fixture at the current schema.
 *
 * <p>The fixture is refused rather than migrated whenever {@link Event#SCHEMA_VERSION} moves —
 * that is the rule, and it means the file has to be producible on demand instead of being a blob
 * nobody can regenerate. Scripted dice and no model, so the same command gives the same file.
 *
 * <p>Run it with:
 * {@code cd server && ./gradlew -q recordFixture}
 */
public final class FixtureRecorder {

    static final Path FIXTURE = Path.of("src/test/resources/sessions/crypt-fight.jsonl");

    /**
     * Fighter initiative 1, goblin 20 — the goblin goes first, which is the case the fixture
     * exists to cover. Then the goblin hits for 4, and the fighter answers and kills.
     */
    private static final ScriptedDiceRoller SCRIPT =
            new ScriptedDiceRoller(1, 20, 19, 4, 14, 4);

    private FixtureRecorder() {
    }

    public static void main(String[] args) throws IOException {
        Path written = record(Files.createTempDirectory("fixture"));
        Files.createDirectories(FIXTURE.getParent());
        Files.copy(written, FIXTURE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + FIXTURE.toAbsolutePath());
    }

    /** The session itself, so a test can build one without touching the checked-in file. */
    static Path record(Path directory) {
        var writer = SessionWriter.open(directory);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(
                Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));

        var engine = new GameEngine(new ContentLoader(), log, SCRIPT);
        var sink = new CombatSink.Buffer();
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> { });
        engine.combat().attack("fighter", "goblin", sink);

        writer.close();
        return writer.path();
    }
}
```

Add the Gradle task at the end of `server/build.gradle.kts`:

```kotlin
// Regenerates src/test/resources/sessions/crypt-fight.jsonl after a schema bump.
// Old logs are refused rather than migrated, so the fixture must be reproducible.
tasks.register<JavaExec>("recordFixture") {
    group = "verification"
    description = "Re-records the replay fixture at the current Event.SCHEMA_VERSION"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dm.replay.FixtureRecorder")
}
```

- [ ] **Step 8: Regenerate the fixture**

Run: `cd server && ./gradlew -q recordFixture`
Expected: `wrote .../src/test/resources/sessions/crypt-fight.jsonl`

Then check the file satisfies what `ReplayRunnerTest` asserts about it:

Run: `cd server && grep -c 'turn_advanced\|"killed":true' src/test/resources/sessions/crypt-fight.jsonl`
Expected: at least `2` — the fixture must contain a goblin turn and a killing blow, or `recordedSessionReplays` fails on its own guards.

- [ ] **Step 9: Run the tests**

Run: `cd server && ./gradlew test`
Expected: PASS. `EntityRoomTest` is green, and `ReplayRunnerTest.recordedSessionReplays` reads the regenerated fixture. `AppTest`, `CombatEngineTest`, `GameEngineEventsTest` and `WorldStateTest` construct entities through `spawn` or through the engine, so they follow the new signature without edits; anything that builds an `Entity` literally will have failed to compile in step 2 and takes `"crypt"`.

- [ ] **Step 10: Note the cost in the M2 evaluation**

`docs/evidence/session-m2-twenty-turns.jsonl` was written at schema 1 and can no longer be replayed. It stays as the record of what happened, and m2-evaluation.md §2's criterion 4 becomes a historical result rather than a reproducible one. Add to the end of `docs/m2-evaluation.md` §2:

```markdown
> **Schema note, added 2026-09-05.** M3 bumped `Event.SCHEMA_VERSION` to 2, because entities now
> carry a `roomId`. This session's log is schema 1 and is therefore refused by the current build —
> refusal rather than migration is the rule (M2 spec §3). Criterion 4 above records a result that
> was true and reproducible on the day it was signed; the file remains as evidence of what
> happened, not as a fixture that still runs. The suite's replay coverage moved to
> `crypt-fight.jsonl`, which `./gradlew recordFixture` regenerates at the current schema.
```

- [ ] **Step 11: Commit**

```bash
git add server/src/main/java/dm/model/Entity.java \
        server/src/main/java/dm/content/EntityDefinition.java \
        server/src/main/java/dm/model/Event.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/test/java/dm/replay/FixtureRecorder.java \
        server/src/test/java/dm/model/EntityRoomTest.java \
        server/src/test/resources/sessions/crypt-fight.jsonl \
        server/build.gradle.kts \
        docs/m2-evaluation.md
git commit -m "Let an entity say which room it is standing in, and make the fixture reproducible now that the old one is refused."
```

---

### Task 3: A revealed prop is remembered per room

The bug fix the spec calls out in §4a. `Event.PropRevealed` has carried `roomId` since the M2 gate and the fold throws it away, so revealing `pillar-0` in one room would mark every room's `pillar-0` revealed — and `ToolSchema` would then stop offering `reveal_prop` for a prop nobody has found.

Two authored rooms will not reproduce it, because their prop ids are hand-picked and distinct. The test constructs it deliberately.

**Files:**
- Create: `server/src/main/java/dm/model/PropRef.java`
- Modify: `server/src/main/java/dm/state/WorldState.java`
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `server/src/main/java/dm/ai/ToolSchema.java`
- Modify: `server/src/main/java/dm/ai/ToolDispatcher.java`
- Test: `server/src/test/java/dm/state/RevealScopeTest.java`

**Interfaces:**
- Consumes: `Entity.roomId` (Task 2) — not directly, but the same fold is edited.
- Produces:
  - `PropRef(String roomId, String propId)`
  - `WorldState.revealedProps()` returning `Set<PropRef>` — replaces `revealedPropIds()`
  - `WorldState.revealedHere()` returning `Set<String>` — prop ids revealed in the current room

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/state/RevealScopeTest.java`:

```java
package dm.state;

import dm.model.Event;
import dm.model.PropRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §4a. Prop ids are unique within a room and nowhere else — {@code PropPlacer} names its
 * output {@code pillar-0} in every room it generates — so a flat set of revealed ids marks a
 * prop found in a room nobody has walked into.
 */
class RevealScopeTest {

    private static Event.PropRevealed reveal(String roomId, String propId) {
        return new Event.PropRevealed(Instant.now(), roomId, propId);
    }

    @Test
    @DisplayName("revealing a prop in one room leaves its namesake hidden in another")
    void revealsDoNotLeakBetweenRooms() {
        var state = WorldState.fold(List.of(reveal("crypt", "pillar-0")));

        assertTrue(state.revealedProps().contains(new PropRef("crypt", "pillar-0")));
        assertFalse(state.revealedProps().contains(new PropRef("gallery", "pillar-0")));
    }

    @Test
    @DisplayName("revealedHere is scoped to the room the party is standing in")
    void revealedHereFollowsTheParty() {
        var state = WorldState.fold(List.of(
                reveal("crypt", "alcove"),
                reveal("gallery", "pillar-0")));

        // EMPTY starts in the crypt, and nothing in this fold moves the party.
        assertEquals("crypt", state.roomId());
        assertEquals(java.util.Set.of("alcove"), state.revealedHere());
    }

    @Test
    @DisplayName("revealing the same prop twice is not two reveals")
    void revealsAreIdempotent() {
        var state = WorldState.fold(List.of(
                reveal("crypt", "alcove"),
                reveal("crypt", "alcove")));

        assertEquals(1, state.revealedProps().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.state.RevealScopeTest'`
Expected: FAIL — compilation error, `cannot find symbol: class PropRef`

- [ ] **Step 3: Write `PropRef`**

Create `server/src/main/java/dm/model/PropRef.java`:

```java
package dm.model;

/**
 * A prop id qualified by the room it is in.
 *
 * <p>Prop ids are unique within a room and nowhere else: {@code PropPlacer} names its output
 * {@code pillar-0}, {@code brazier-1}, and every generated room in a dungeon produces the same
 * names. A flat set of revealed ids therefore marks a prop found in a room the party has never
 * entered, and {@code ToolSchema} then stops offering {@code reveal_prop} for it — a secret that
 * silently cannot be found.
 *
 * <p>A value record in a set, matching how {@code Square} is already used as a key.
 */
public record PropRef(String roomId, String propId) {
}
```

- [ ] **Step 4: Scope the fold**

In `server/src/main/java/dm/state/WorldState.java`:

Change the component `Set<String> revealedPropIds` to `Set<PropRef> revealedProps`, add the `dm.model.PropRef` import, and update `EMPTY` and the compact constructor accordingly. Then replace the reveal case and its handler:

```java
            case Event.PropRevealed e -> revealed(new PropRef(e.roomId(), e.propId()));
```

```java
    private WorldState revealed(PropRef ref) {
        var next = new LinkedHashSet<>(revealedProps);
        next.add(ref);
        return copy(entities, party, mode, next, combat, facts, consecutiveFailedChecks);
    }

    /**
     * Prop ids revealed in the room the party is standing in — the shape every caller actually
     * wants, since a room's props are addressed by bare id everywhere else.
     */
    public Set<String> revealedHere() {
        return revealedProps.stream()
                .filter(ref -> ref.roomId().equals(roomId))
                .map(PropRef::propId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
```

Rename the `copy(...)` parameter `newRevealed` to match, and update every internal `copy` call — the compiler finds all of them, since the component's type changed.

- [ ] **Step 5: Point the three readers at `revealedHere()`**

`GameEngine.scene()`:

```java
        var revealed = state().revealedHere();
```

`GameEngine.revealProp(String propId)`:

```java
        if (state().revealedHere().contains(propId)) {
            return List.of();
        }
```

`ToolSchema.build(...)`:

```java
        var hidden = engine.room().hiddenPropIds().stream()
                .filter(id -> !engine.state().revealedHere().contains(id))
                .toList();
```

`ToolDispatcher.revealProp(...)`:

```java
        if (engine.state().revealedHere().contains(propId)) {
            return Result.rejected("'" + propId + "' has already been revealed");
        }
```

`DmService.worldState(...)`:

```java
        var revealed = engine.state().revealedHere();
```

- [ ] **Step 6: Run the tests**

Run: `cd server && ./gradlew test`
Expected: PASS, including the three new tests. `WorldStateTest` may reference `revealedPropIds()` — update those assertions to `revealedHere()`, which is the same values for a single-room fold.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/model/PropRef.java \
        server/src/main/java/dm/state/WorldState.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/ai/ToolSchema.java \
        server/src/main/java/dm/ai/ToolDispatcher.java \
        server/src/main/java/dm/ai/DmService.java \
        server/src/test/java/dm/state/RevealScopeTest.java \
        server/src/test/java/dm/state/WorldStateTest.java
git commit -m "Stop a prop found in one room from counting as found in every room that names one the same."
```

---

### Task 4: The party crosses, in the log

The event, and everything the fold does with it. Nothing emits it yet — that is Task 7 — so this task is pure state.

The movers are a list even though M3's policy is that the list is everyone. Collapsing it later is free; un-collapsing it is a schema bump and a refused log, which is the same reasoning that made `RollResult.faces` a list.

**Files:**
- Modify: `server/src/main/java/dm/model/Event.java`
- Modify: `server/src/main/java/dm/state/WorldState.java`
- Test: `server/src/test/java/dm/state/PartyMovedTest.java`

**Interfaces:**
- Consumes: `Entity.movedToRoom` (Task 2), `Exit` (Task 1).
- Produces:
  - `Event.PartyMoved(Instant at, List<String> entityIds, String fromRoomId, String toRoomId, String throughExitId, int x, int y)`
  - `WorldState.visitedRoomIds()` returning `Set<String>`
  - `WorldState.hasVisited(String roomId)`

**Note on the landing square:** the event carries `x` and `y` rather than leaving the fold to recompute `Exit.inward()`. The fold must never need the content files to know where an entity ended up — `WorldState` has no `ContentLoader` and should not grow one, and a recomputed landing square would silently change if a room file were edited after a session was recorded.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/state/PartyMovedTest.java`:

```java
package dm.state;

import dm.content.ContentLoader;
import dm.model.Event;
import dm.model.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartyMovedTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static Event.PartySpawned spawnFighter() {
        return new Event.PartySpawned(Instant.now(), List.of(
                CONTENT.entity("fighter").spawn("fighter", "crypt", 6, 1)));
    }

    private static Event.EntitySpawned spawnGoblin(String roomId, int x, int y) {
        return new Event.EntitySpawned(Instant.now(),
                CONTENT.entity("goblin").spawn("goblin", roomId, x, y));
    }

    private static Event.PartyMoved cross(String from, String to, int x, int y) {
        return new Event.PartyMoved(Instant.now(), List.of("fighter"), from, to,
                "door-north", x, y);
    }

    @Test
    @DisplayName("crossing moves the party's room, its square, and the world's idea of where it is")
    void crossingMovesTheParty() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                cross("crypt", "gallery", 6, 1)));

        assertEquals("gallery", state.roomId());
        var fighter = state.find("fighter").orElseThrow();
        assertEquals("gallery", fighter.roomId());
        assertEquals(6, fighter.x());
        assertEquals(1, fighter.y());
    }

    @Test
    @DisplayName("what you leave behind stays behind")
    void nonMoversStayPut() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                spawnGoblin("crypt", 6, 6),
                cross("crypt", "gallery", 6, 1)));

        var goblin = state.find("goblin").orElseThrow();
        assertEquals("crypt", goblin.roomId(), "the goblin did not walk through the door");
        assertEquals(6, goblin.x());
        assertEquals(6, goblin.y());
    }

    @Test
    @DisplayName("entitiesHere is the room you are in, and find still sees everything")
    void entitiesHereIsScopedAndFindIsNot() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                spawnGoblin("crypt", 6, 6),
                cross("crypt", "gallery", 6, 1)));

        assertEquals(List.of("fighter"), state.entitiesHere().stream().map(e -> e.id()).toList());
        // A goblin killed in a room you have left must stay findable: TtsClient's voice lookup
        // and spawnGoblin's already-dead refusal both go through find().
        assertTrue(state.find("goblin").isPresent());
    }

    @Test
    @DisplayName("a room you have entered is remembered as visited, including where you started")
    void visitsAreRemembered() {
        var start = WorldState.fold(List.of(spawnFighter()));
        assertTrue(start.hasVisited("crypt"), "the party is standing in it");
        assertFalse(start.hasVisited("gallery"));

        var moved = start.apply(cross("crypt", "gallery", 6, 1));
        assertTrue(moved.hasVisited("gallery"));
        assertTrue(moved.hasVisited("crypt"), "leaving a room does not unvisit it");
    }

    @Test
    @DisplayName("coming back does not re-reveal or forget anything")
    void returningRestoresTheRoom() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                new Event.PropRevealed(Instant.now(), "crypt", "alcove"),
                cross("crypt", "gallery", 6, 1),
                new Event.PartyMoved(Instant.now(), List.of("fighter"), "gallery", "crypt",
                        "door-south", 6, 10)));

        assertEquals("crypt", state.roomId());
        assertTrue(state.revealedHere().contains("alcove"), "the alcove was found and stays found");
        assertEquals(6, state.find("fighter").orElseThrow().x());
        assertEquals(10, state.find("fighter").orElseThrow().y());
    }

    @Test
    @DisplayName("crossing is not a fight, and does not touch the fight that is not happening")
    void crossingLeavesModeAlone() {
        var state = WorldState.fold(List.of(
                spawnFighter(),
                new Event.ModeEntered(Instant.now(), Mode.EXPLORATION),
                cross("crypt", "gallery", 6, 1)));

        assertEquals(Mode.EXPLORATION, state.mode());
        assertTrue(state.combat().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.state.PartyMovedTest'`
Expected: FAIL — compilation error, `cannot find symbol: class PartyMoved`

- [ ] **Step 3: Add the event**

In `server/src/main/java/dm/model/Event.java`, register the subtype alongside the others:

```java
        @JsonSubTypes.Type(value = Event.PartyMoved.class, name = "party_moved"),
```

and add the record in the outcomes section, after `EntityMoved`:

```java
    /**
     * The party crossed a threshold. The one event that changes which room anything is in.
     *
     * <p>{@code entityIds} is a list although M3's policy is that it is everyone. Collapsing it
     * later is free; un-collapsing it is a schema bump and a refused log — the same argument that
     * made {@code RollResult.faces} a list from day one.
     *
     * <p>{@code x} and {@code y} are the landing square, carried rather than recomputed from
     * {@code Exit.inward()}: the fold has no {@code ContentLoader} and must not grow one, and a
     * recomputed square would silently change if a room file were edited after the session was
     * recorded.
     */
    record PartyMoved(Instant at, List<String> entityIds, String fromRoomId, String toRoomId,
                      String throughExitId, int x, int y) implements Event {}
```

- [ ] **Step 4: Fold it**

In `server/src/main/java/dm/state/WorldState.java`:

Add two components — `Set<String> visitedRoomIds` after `revealedProps`, defaulting in `EMPTY` to `Set.of("crypt")` so the starting room counts as visited from the first fold. Copy it defensively in the compact constructor.

Add the case:

```java
            case Event.PartyMoved e -> partyMoved(e);
```

and the handler, plus the two accessors:

```java
    private WorldState partyMoved(Event.PartyMoved e) {
        var next = new LinkedHashMap<>(entities);
        for (var entityId : e.entityIds()) {
            var entity = next.get(entityId);
            if (entity != null) {
                next.put(entityId, entity.movedToRoom(e.toRoomId(), e.x(), e.y()));
            }
        }
        var visited = new LinkedHashSet<>(visitedRoomIds);
        visited.add(e.toRoomId());

        return new WorldState(e.toRoomId(), next, party, mode, revealedProps, visited,
                combat, facts, consecutiveFailedChecks);
    }

    /** Rooms the party has stood in. Feeds the arrival directive — spec §7b. */
    public boolean hasVisited(String otherRoomId) {
        return visitedRoomIds.contains(otherRoomId);
    }

    /** The occupants of the room the party is standing in. Joins {@link #factsHere()}. */
    public List<Entity> entitiesHere() {
        return entities.values().stream()
                .filter(e -> e.roomId().equals(roomId))
                .toList();
    }
```

`partyMoved` is the one handler that does not go through `copy(...)`, because `copy` deliberately carries `roomId` and `visitedRoomIds` through unchanged — every other case must leave both alone, and this is the only one allowed to change them. Keep `copy`'s signature honest by adding `visitedRoomIds` to it as a pass-through parameter, so a future case cannot forget it exists.

- [ ] **Step 5: Run the tests**

Run: `cd server && ./gradlew test`
Expected: PASS, including six new tests.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/dm/model/Event.java \
        server/src/main/java/dm/state/WorldState.java \
        server/src/test/java/dm/state/PartyMovedTest.java
git commit -m "Record a party crossing a threshold, and leave behind whatever did not come along."
```

---

### Task 5: A room's prose is folded, not baked

Spec §5b. `Event.RoomDressed` exists in the schema and is explicitly inert in the fold. It folds now, and authored rooms emit it too — so the compose path is exercised end to end with deterministic content and no model, and the generator plan changes only where a `Dressing` comes from.

**Files:**
- Create: `server/src/main/java/dm/content/Dressings.java`
- Modify: `server/src/main/java/dm/state/WorldState.java`
- Modify: `server/src/main/java/dm/generate/GeneratedRoom.java`
- Test: `server/src/test/java/dm/content/DressingsTest.java`

**Interfaces:**
- Consumes: `Dressing` (existing, `dm.generate`).
- Produces:
  - `Dressings.of(RoomDefinition)` returning `Dressing`
  - `Dressings.applyTo(RoomDefinition, Dressing)` returning `RoomDefinition`
  - `WorldState.dressings()` returning `Map<String, Dressing>`; `WorldState.dressingOf(String roomId)` returning `Optional<Dressing>`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/content/DressingsTest.java`:

```java
package dm.content;

import dm.generate.Dressing;
import dm.model.Event;
import dm.state.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §5b. An authored room already contains a {@code Dressing}, spread across fields — so both
 * sources go through one compose path, and M3 exercises it with content that needs no network.
 */
class DressingsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("an authored room yields the dressing it was written with")
    void extractsFromAnAuthoredRoom() {
        var crypt = CONTENT.room("crypt");

        var dressing = Dressings.of(crypt);

        assertEquals(crypt.name(), dressing.name());
        assertEquals(crypt.dmNotes().overview(), dressing.overview());
        assertEquals(crypt.dmNotes().sensory(), dressing.sensory());
        assertEquals(crypt.prop("sarcophagus").description(),
                dressing.propDescriptions().get("sarcophagus"));
    }

    @Test
    @DisplayName("extracting then applying is the room you started with")
    void roundTrips() {
        var crypt = CONTENT.room("crypt");

        var composed = Dressings.applyTo(crypt, Dressings.of(crypt));

        assertEquals(crypt.name(), composed.name());
        assertEquals(crypt.dmNotes().overview(), composed.dmNotes().overview());
        for (var prop : crypt.props()) {
            assertEquals(prop.description(), composed.prop(prop.id()).description(),
                    prop.id());
        }
    }

    @Test
    @DisplayName("applying a dressing replaces the prose and touches nothing structural")
    void applyingLeavesStructureAlone() {
        var crypt = CONTENT.room("crypt");
        var other = new Dressing("The Drowned Vault", "Water to the ankles.",
                "It drips.", Map.of("sarcophagus", "A stone box, slick with algae."));

        var composed = Dressings.applyTo(crypt, other);

        assertEquals("The Drowned Vault", composed.name());
        assertEquals("A stone box, slick with algae.", composed.prop("sarcophagus").description());
        assertEquals(crypt.width(), composed.width());
        assertEquals(crypt.props().size(), composed.props().size());
        assertEquals(crypt.exits(), composed.exits());
        assertEquals(crypt.prop("alcove").hidden(), composed.prop("alcove").hidden());
    }

    @Test
    @DisplayName("secrets are authored and survive a dressing that has none")
    void secretsAreNotDressing() {
        var crypt = CONTENT.room("crypt");
        var other = new Dressing("x", "y", "z", Map.of());

        var composed = Dressings.applyTo(crypt, other);

        // Spec §5a: secrets are the third thing. No model writes them and none is dropped here.
        assertEquals(crypt.dmNotes().theSarcophagus(), composed.dmNotes().theSarcophagus());
        assertEquals(crypt.dmNotes().theDoor(), composed.dmNotes().theDoor());
    }

    @Test
    @DisplayName("a prop the dressing skipped keeps no description rather than a stale one")
    void skippedPropsGetNothing() {
        var crypt = CONTENT.room("crypt");
        var sparse = new Dressing("x", "y", "z", Map.of("sarcophagus", "A box."));

        var composed = Dressings.applyTo(crypt, sparse);

        assertEquals("", composed.prop("rubble").description(),
                "an undescribed prop is still on the board, just undescribed");
    }

    @Test
    @DisplayName("RoomDressed folds, keyed by room")
    void dressingsFold() {
        var one = new Dressing("The Ashen Crypt", "a", "b", Map.of());
        var two = new Dressing("The Long Gallery", "c", "d", Map.of());

        var state = WorldState.fold(List.of(
                new Event.RoomDressed(Instant.now(), "crypt", one),
                new Event.RoomDressed(Instant.now(), "gallery", two)));

        assertEquals(one, state.dressingOf("crypt").orElseThrow());
        assertEquals(two, state.dressingOf("gallery").orElseThrow());
        assertTrue(state.dressingOf("nowhere").isEmpty());
    }

    @Test
    @DisplayName("dressing a room twice keeps the second, not both")
    void redressingReplaces() {
        var first = new Dressing("First", "a", "b", Map.of());
        var second = new Dressing("Second", "c", "d", Map.of());

        var state = WorldState.fold(List.of(
                new Event.RoomDressed(Instant.now(), "crypt", first),
                new Event.RoomDressed(Instant.now(), "crypt", second)));

        assertEquals("Second", state.dressingOf("crypt").orElseThrow().name());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.content.DressingsTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Dressings`

- [ ] **Step 3: Write `Dressings`**

Create `server/src/main/java/dm/content/Dressings.java`:

```java
package dm.content;

import dm.generate.Dressing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seam between a room's structure and its prose.
 *
 * <p>Spec §5a splits a room three ways — structure, dressing, secrets. Structure can always be
 * made again for free. Dressing cannot, when a model wrote it, so it is folded out of the log.
 * Secrets are authored and belong to neither.
 *
 * <p>An authored room already <em>contains</em> a {@link Dressing}; it is simply spread across
 * {@code name}, two {@code dmNotes} fields and every prop's description. Extracting it means both
 * sources go through one compose path, and M3 exercises that path with content that needs no
 * network — so the generator plan changes only where a {@code Dressing} comes from.
 */
public final class Dressings {

    private Dressings() {
    }

    /** The dressing an authored room was written with. */
    public static Dressing of(RoomDefinition room) {
        var descriptions = new LinkedHashMap<String, String>();
        for (var prop : room.props()) {
            if (prop.description() != null && !prop.description().isBlank()) {
                descriptions.put(prop.id(), prop.description());
            }
        }
        return new Dressing(
                room.name(),
                room.dmNotes().overview(),
                room.dmNotes().sensory(),
                Map.copyOf(descriptions));
    }

    /**
     * The same room wearing different prose.
     *
     * <p>A prop the dressing skipped gets an empty description rather than keeping the one it
     * had. Holding the old text would mean a room that is half one dressing and half another,
     * and {@code DmService.worldState} already omits the description tail when it is blank — a
     * prop with nothing to say is still listed, because it is still on the board.
     *
     * <p>Secrets pass through untouched. No model writes them and this must not drop them.
     */
    public static RoomDefinition applyTo(RoomDefinition room, Dressing dressing) {
        List<RoomDefinition.PropDefinition> props = room.props().stream()
                .map(p -> new RoomDefinition.PropDefinition(
                        p.id(), p.type(), p.x(), p.y(), p.rotation(), p.hidden(),
                        dressing.propDescriptions().getOrDefault(p.id(), ""),
                        p.revealHint(), p.contains()))
                .toList();

        return new RoomDefinition(
                room.roomId(),
                dressing.name(),
                room.width(),
                room.height(),
                room.floorType(),
                room.wallType(),
                room.lighting(),
                props,
                room.exits(),
                room.startPositions(),
                new RoomDefinition.DmNotes(
                        dressing.overview(),
                        dressing.sensory(),
                        room.dmNotes().theSarcophagus(),
                        room.dmNotes().theSarcophagusOpened(),
                        room.dmNotes().theDoor()));
    }
}
```

- [ ] **Step 4: Fold `RoomDressed`**

In `server/src/main/java/dm/state/WorldState.java`, add the import `dm.generate.Dressing`, add a `Map<String, Dressing> dressings` component after `visitedRoomIds` (defaulting to `Map.of()` in `EMPTY`, copied defensively in the compact constructor and passed through `copy`), move `RoomDressed` out of the inert group, and add the handler and accessor:

```java
            case Event.RoomDressed e -> dressed(e);
```

```java
    private WorldState dressed(Event.RoomDressed e) {
        var next = new LinkedHashMap<>(dressings);
        next.put(e.roomId(), e.dressing());
        return copyWithDressings(next);
    }

    /**
     * The prose a room is wearing, if it has been dressed.
     *
     * <p>Spec §5b: this is the half of a room that cannot be made again for free, so it is the
     * half that lives in the log. A cache would be a second write path, would not survive
     * {@code restart()}, and could not be reconstructed by a replay that has no model.
     */
    public Optional<Dressing> dressingOf(String otherRoomId) {
        return Optional.ofNullable(dressings.get(otherRoomId));
    }
```

- [ ] **Step 5: Point `GeneratedRoom` at the shared path**

`GeneratedRoom.toRoomDefinition(Dressing)` currently rebuilds the room by hand and duplicates what `Dressings.applyTo` now does. Replace its body so there is one compose path rather than two that will drift:

```java
    /** The same room, with a dress pass's prose written into it. */
    public RoomDefinition toRoomDefinition(Dressing dressing) {
        return dm.content.Dressings.applyTo(toRoomDefinition(), dressing);
    }
```

`toRoomDefinition()` — the undressed form — gains `List.of()` for the new `exits` component. Generated rooms have no exits until the generator plan.

- [ ] **Step 6: Run the tests**

Run: `cd server && ./gradlew test`
Expected: PASS. `RoomDresserTest` and `RoomGeneratorTest` exercise `toRoomDefinition(dressing)` and should be unaffected — the one visible change is that an undescribed prop now gets `""` where the old code also gave `""`, so behaviour is identical.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/content/Dressings.java \
        server/src/main/java/dm/state/WorldState.java \
        server/src/main/java/dm/generate/GeneratedRoom.java \
        server/src/test/java/dm/content/DressingsTest.java
git commit -m "Keep a room's prose in the log instead of baked into the room, and let an authored room use the same path."
```

---

# Stage 2 — The engine gains rooms

Task 8 is the first one a player can walk. Until then nothing has changed on screen.

---

### Task 6: The engine looks a room up instead of holding one

`GameEngine` holds `private final RoomDefinition room`, handed in at construction, and builds a
`CombatEngine` around the same object. Two rooms breaks both. `CombatEngine` reads the room in
exactly one place — the walkability check — so it takes a supplier rather than a value.

This is also where composition lands: `room()` is structure from disk plus whatever dressing the
fold is holding for that room.

**Files:**
- Create: `server/src/main/java/dm/engine/Rooms.java`
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `server/src/main/java/dm/engine/CombatEngine.java`
- Modify: `server/src/main/java/dm/App.java`
- Test: `server/src/test/java/dm/engine/RoomsTest.java`

**Interfaces:**
- Consumes: `Dressings.applyTo` (Task 5), `WorldState.dressingOf` (Task 5).
- Produces:
  - `Rooms.authored(ContentLoader, String... roomIds)` returning `Rooms`
  - `Rooms.of(RoomDefinition...)` returning `Rooms` — for tests and for a single generated room
  - `Rooms.structure(String roomId)` returning `RoomDefinition`; `Rooms.has(String roomId)`; `Rooms.first()`
  - `GameEngine.room()` — the current room, dressed; `GameEngine.room(String roomId)` — any room, dressed
  - `CombatEngine(EventLog, DiceRoller, Supplier<RoomDefinition>)`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/engine/RoomsTest.java`:

```java
package dm.engine;

import dm.content.ContentLoader;
import dm.generate.Dressing;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine engine(EventLog log) {
        return new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt"));
    }

    @Test
    @DisplayName("a room with no dressing folded is the room as authored")
    void undressedIsTheRoomOnDisk() {
        var engine = engine(new EventLog());

        assertEquals(CONTENT.room("crypt").name(), engine.room().name());
        assertEquals("crypt", engine.room().roomId());
    }

    @Test
    @DisplayName("a folded dressing is what the engine serves")
    void foldedDressingWins() {
        var log = new EventLog();
        var engine = engine(log);

        log.append(new Event.RoomDressed(Instant.now(), "crypt",
                new Dressing("The Drowned Vault", "Water to the ankles.", "It drips.",
                        Map.of("sarcophagus", "A stone box, slick with algae."))));

        assertEquals("The Drowned Vault", engine.room().name());
        assertEquals("A stone box, slick with algae.",
                engine.room().prop("sarcophagus").description());
        assertEquals(CONTENT.room("crypt").width(), engine.room().width(),
                "structure is not dressing");
    }

    @Test
    @DisplayName("room(id) serves a room the party is not standing in")
    void anyRoomByIdWithoutMoving() {
        var engine = engine(new EventLog());

        assertEquals("crypt", engine.room("crypt").roomId());
        assertThrows(IllegalArgumentException.class, () -> engine.room("nowhere"));
    }

    @Test
    @DisplayName("the engine starts the party in the first room it was given")
    void firstRoomIsTheEntrance() {
        var log = new EventLog();
        var engine = engine(log);
        engine.start();

        assertEquals("crypt", engine.state().roomId());
        assertEquals("crypt", engine.state().find("fighter").orElseThrow().roomId());
    }

    @Test
    @DisplayName("entering a room for the first time records the dressing it is wearing")
    void firstEntryRecordsTheDressing() {
        var log = new EventLog();
        engine(log).start();

        var dressed = log.events().stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .toList();

        assertEquals(1, dressed.size(), "exactly one dressing, for the room we opened in");
        assertEquals("crypt", dressed.getFirst().roomId());
        assertEquals(CONTENT.room("crypt").name(), dressed.getFirst().dressing().name());
    }

    @Test
    @DisplayName("combat reads terrain from the room the party is in, not the one it started in")
    void combatFollowsTheParty() {
        // The walkability check is the only place CombatEngine reads a room. If it held a value
        // rather than a supplier, a fight in room 2 would be fought against room 1's pillars.
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt"));
        engine.start();

        assertSame(engine.room(), engine.combat().terrain(),
                "combat and the engine must agree about which room this is");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.engine.RoomsTest'`
Expected: FAIL — compilation error, `cannot find symbol: class Rooms`

- [ ] **Step 3: Write `Rooms`**

Create `server/src/main/java/dm/engine/Rooms.java`:

```java
package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;

import java.util.LinkedHashMap;
import java.util.List;
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
```

Note the constructor copy: `Map.copyOf` does **not** preserve insertion order, so `first()` would
return an arbitrary room and the party would start wherever the hash landed. The
`Collections.unmodifiableMap(new LinkedHashMap<>(...))` above is deliberate and must not be
"simplified" back to `Map.copyOf`.

- [ ] **Step 4: Make `CombatEngine` ask rather than hold**

In `server/src/main/java/dm/engine/CombatEngine.java`, replace the field and constructor, and add
the accessor the test uses:

```java
    private final java.util.function.Supplier<RoomDefinition> currentRoom;

    public CombatEngine(EventLog log, DiceRoller dice,
                        java.util.function.Supplier<RoomDefinition> currentRoom) {
        this.log = log;
        this.dice = dice;
        this.currentRoom = currentRoom;
    }

    /**
     * The room this fight is being fought in. A supplier rather than a value because the party
     * can now be somewhere else than where the engine was constructed, and terrain that lagged
     * behind would let a fighter walk through a pillar that is in a different room.
     */
    public RoomDefinition terrain() {
        return currentRoom.get();
    }
```

and at the walkability check (currently lines 420–422):

```java
        var room = currentRoom.get();
        return square.x() >= 0 && square.x() < room.width()
                && square.y() >= 0 && square.y() < room.height()
                && !room.isObstructed(square.x(), square.y())
```

- [ ] **Step 5: Give `GameEngine` the lookup**

In `server/src/main/java/dm/engine/GameEngine.java`, replace the `ROOM_ID` constant, the `room`
field and both constructors:

```java
    private final ContentLoader content;
    private final EventLog log;
    private final DiceRoller dice;
    private final Rooms rooms;
    private final CombatEngine combat;

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice) {
        this(content, log, dice, Rooms.authored(content, "crypt"));
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, RoomDefinition room) {
        this(content, log, dice, Rooms.of(room));
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, Rooms rooms) {
        this.content = content;
        this.log = log;
        this.dice = dice;
        this.rooms = rooms;
        this.combat = new CombatEngine(log, dice, this::room);
    }

    /** The room the party is standing in, structure and dressing composed. */
    public RoomDefinition room() {
        return room(state().roomId());
    }

    /**
     * Any room, dressed with whatever the log is holding for it.
     *
     * <p>Composed on read rather than cached. Spec §5b: a cache would not survive
     * {@code restart()} and could not be rebuilt by a replay that has no model.
     */
    public RoomDefinition room(String roomId) {
        var structure = rooms.structure(roomId);
        return state().dressingOf(roomId)
                .map(dressing -> dm.content.Dressings.applyTo(structure, dressing))
                .orElse(structure);
    }

    public Rooms rooms() {
        return rooms;
    }
```

The two-argument `RoomDefinition` constructor is kept because `App`'s `--generate` path and several
tests hand the engine one room; it now wraps it in a `Rooms` of one.

- [ ] **Step 6: Start the party in the first room, and record its dressing**

Replace `GameEngine.start()`:

```java
    /** Spawn the party at the entrance room's start positions. M3's party has one member. */
    public void start() {
        var entrance = rooms.first();
        var fighter = content.entity("fighter");
        var starts = entrance.startPositions().party();
        var members = new ArrayList<Entity>();

        for (int i = 0; i < starts.size(); i++) {
            var at = starts.get(i);
            // One member in M3, but the loop is the point — see invariant #2.
            String entityId = starts.size() == 1 ? fighter.id() : fighter.id() + "-" + i;
            members.add(fighter.spawn(entityId, entrance.roomId(), at.x(), at.y()));
        }

        // The party is in the entrance before it is spawned there: PartySpawned does not move
        // anyone, and WorldState.EMPTY names "crypt". Recording the dressing first also means
        // the opening narration sees the room already dressed.
        recordDressing(entrance.roomId());
        log.append(new Event.PartySpawned(Instant.now(), List.copyOf(members)));
        log.append(new Event.ModeEntered(Instant.now(), Mode.EXPLORATION));
    }

    /**
     * Write down what a room looks like, the first time anyone stands in it.
     *
     * <p>Authored rooms go through this exactly as generated ones will — spec §5b. The prose is
     * already on disk, so the event is redundant today and is the point: it means the fold, the
     * compose and the replay of a dressed room are all exercised now, with no model in the path,
     * rather than for the first time in the generator plan.
     */
    private void recordDressing(String roomId) {
        if (state().dressingOf(roomId).isPresent()) {
            return;
        }
        log.append(new Event.RoomDressed(Instant.now(), roomId,
                dm.content.Dressings.of(rooms.structure(roomId))));
    }
```

`WorldState.EMPTY` names `"crypt"`, which is the entrance for every session M3 builds. Add an
assertion in the constructor so a future `Rooms` whose first room is not the crypt fails loudly
rather than starting the party in a room it is not standing in:

```java
        if (!WorldState.EMPTY.roomId().equals(rooms.first().roomId())) {
            throw new IllegalArgumentException(
                    "The entrance must be '" + WorldState.EMPTY.roomId() + "' until the fold's "
                            + "starting room is configurable, got '" + rooms.first().roomId() + "'");
        }
```

- [ ] **Step 7: Replace the remaining `room` and `ROOM_ID` references**

`revealProp` used the `ROOM_ID` constant:

```java
        log.append(new Event.PropRevealed(Instant.now(), state().roomId(), propId));
```

`spawnGoblin` and `defaultGoblinSpawn` read `room` — both now call `room()`, which resolves to the
current room. `scene()` and `isInBounds` likewise. `moveTo`'s obstruction check becomes
`room().isObstructed(x, y)`, and its occupancy check must be scoped to this room, or the party
would be blocked by a goblin standing on the same square in a different one:

```java
        boolean occupied = state().entitiesHere().stream()
                .anyMatch(e -> e.isAlive() && !e.id().equals(actorId) && e.x() == x && e.y() == y);
```

`scene()`'s entity list is likewise `state().entitiesHere()` rather than `state().entities().values()`.

- [ ] **Step 8: Update `App`**

`App` builds `new GameEngine(content, eventLog, dice, room)` where `room` is one `RoomDefinition`.
That constructor still exists and still works, so the only change is the default path — the crypt
plus the room behind it, once Task 12 exists. For now:

```java
        var engine = cli.generateSeed() == null
                ? new GameEngine(content, eventLog, dice, Rooms.authored(content, "crypt"))
                : new GameEngine(content, eventLog, dice, room);
```

Task 12 adds `"gallery"` to the authored list. Leave the `--generate` branch on one room.

Also delete the now-stale comment above `engine.start()`:

```java
        // The dress pass lands here when navigation makes it per-room. A generated
        // room currently bakes Dressing into RoomDefinition and does not carry a
        // Dressing object, so there is nothing to append until then.
```

It has landed — `start()` records it.

- [ ] **Step 9: Run the tests**

Run: `cd server && ./gradlew test`
Expected: PASS. `CombatEngineTest` constructs a `CombatEngine` directly and needs its third argument
changed from a `RoomDefinition` to `() -> room`. `ReplayRunnerTest`'s programmatic fixtures now see
a `RoomDressed` event in the log; `ReplayRunner.compare` already skips `RoomDressed`, so they stay
green.

- [ ] **Step 10: Commit**

```bash
git add server/src/main/java/dm/engine/Rooms.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/engine/CombatEngine.java \
        server/src/main/java/dm/App.java \
        server/src/test/java/dm/engine/RoomsTest.java \
        server/src/test/java/dm/CombatEngineTest.java
git commit -m "Let the engine look a room up and dress it on the way out, instead of holding one forever."
```

---

### Task 7: Walking through the door, server-side

`GameEngine.crossExit` — the one call that emits `PartyMoved`. Still unreachable from the client;
Task 8 wires the verb.

Exits are illegal in combat, and this is the only place that rule lives. Without it, walking out of
a fight strands an initiative order in a room nobody is standing in.

**Files:**
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `server/src/main/java/dm/model/SceneState.java`
- Test: `server/src/test/java/dm/engine/CrossExitTest.java`

**Interfaces:**
- Consumes: `Rooms` (Task 6), `Event.PartyMoved` (Task 4), `Exit.inward` (Task 1).
- Produces:
  - `GameEngine.crossExit(String exitId)` returning `List<Diff>` — throws `IllegalArgumentException` on a bad id, an unknown destination, or an attempt during combat
  - `SceneState` gains `List<Exit> exits` after `props`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/engine/CrossExitTest.java`:

```java
package dm.engine;

import dm.content.ContentLoader;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossExitTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    @Test
    @DisplayName("crossing lands the party one square inside, not in the doorway")
    void landsInward() {
        var engine = started(new EventLog());

        engine.crossExit("door-north");

        assertEquals("gallery", engine.state().roomId());
        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals("gallery", fighter.roomId());
        // The gallery's south door answers the crypt's north one. Landing on the door itself
        // would put the party on the trigger that sent them there.
        var arrival = engine.room("gallery").exits().stream()
                .filter(e -> e.toRoomId().equals("crypt"))
                .findFirst().orElseThrow();
        assertNotEquals(arrival.square(), new dm.model.Square(fighter.x(), fighter.y()));
        assertEquals(arrival.inward(engine.room("gallery").width(),
                engine.room("gallery").height()),
                new dm.model.Square(fighter.x(), fighter.y()));
    }

    @Test
    @DisplayName("crossing emits one PartyMoved naming the exit it went through")
    void emitsOneEvent() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");

        var moves = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .toList();
        assertEquals(1, moves.size());
        assertEquals("crypt", moves.getFirst().fromRoomId());
        assertEquals("gallery", moves.getFirst().toRoomId());
        assertEquals("door-north", moves.getFirst().throughExitId());
        assertEquals(java.util.List.of("fighter"), moves.getFirst().entityIds());
    }

    @Test
    @DisplayName("only the living cross — a corpse is not carried through the door")
    void deadPartyMembersStayBehind() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);

        engine.crossExit("door-north");

        // The goblin was never party, so it stays regardless; the assertion that matters is
        // that entityIds is the living party and not "everything in the room".
        var moved = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .findFirst().orElseThrow();
        assertFalse(moved.entityIds().contains("goblin"));
        assertEquals("crypt", engine.state().find("goblin").orElseThrow().roomId());
    }

    @Test
    @DisplayName("arriving somewhere new records its dressing exactly once")
    void firstArrivalDresses() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");
        engine.crossExit("door-south");
        engine.crossExit("door-north");

        long gallery = log.events().stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .filter(e -> e.roomId().equals("gallery"))
                .count();
        assertEquals(1, gallery, "a room is dressed on first entry and never again");
    }

    @Test
    @DisplayName("an exit that is not in this room is refused")
    void unknownExitRefused() {
        var engine = started(new EventLog());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-west"));
        assertTrue(thrown.getMessage().contains("door-west"), thrown.getMessage());
    }

    @Test
    @DisplayName("you cannot walk out of a fight")
    void exitsAreIllegalInCombat() {
        var engine = started(new EventLog());
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-north"));
        assertTrue(thrown.getMessage().toLowerCase().contains("fight"), thrown.getMessage());
        assertEquals("crypt", engine.state().roomId());
    }

    @Test
    @DisplayName("the scene the client is sent carries the room's exits")
    void sceneCarriesExits() {
        var engine = started(new EventLog());

        assertEquals(engine.room().exits(), engine.scene().exits());
        assertFalse(engine.scene().exits().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.engine.CrossExitTest'`
Expected: FAIL — compilation error, `cannot find symbol: method crossExit`. The test also needs
the `gallery` room, which does not exist until Task 12; expect a `ContentLoader` failure until then.

**This is the one task that ends red.** Task 12 authors `gallery.json`. Splitting differently would
mean either authoring content before the code that reads it can compile, or writing a throwaway
stub room to delete a task later. Run `./gradlew test --tests 'dm.engine.RoomsTest'` to confirm
nothing else regressed, and carry `CrossExitTest` red until Task 12. Note it in the commit message
so a bisect does not read as a mystery.

- [ ] **Step 3: Add exits to `SceneState`**

In `server/src/main/java/dm/model/SceneState.java`, add `List<Exit> exits` immediately after
`props`, and carry it through `asSeenByPlayer`:

```java
public record SceneState(
        String roomId,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        List<Prop> props,
        /** Ways out, so the client knows which floor squares are doors. */
        List<Exit> exits,
        List<EntityView> entities,
        LightingPreset lighting,
        Mode mode,
        CombatView combat
) {
    public List<Prop> visibleProps() {
        return props.stream().filter(p -> !p.hidden()).toList();
    }

    public SceneState asSeenByPlayer() {
        return new SceneState(roomId, width, height, floorType, wallType,
                visibleProps(), exits, entities, lighting, mode, combat);
    }
}
```

- [ ] **Step 4: Write `crossExit`**

In `server/src/main/java/dm/engine/GameEngine.java`, add:

```java
    /**
     * Take the party through a door.
     *
     * <p>The whole party, always — spec §6a. {@code PartyMoved} carries a list of movers so that
     * splitting is a policy change rather than a schema bump, but M3's policy is that the list is
     * everyone still standing.
     *
     * <p>No adjacency requirement. Crossing implies walking to the door, everyone lands on
     * {@link Exit#inward}, and where anyone stood beforehand has no consequence — a rule here
     * would only ever produce a rejection the player finds annoying.
     */
    public List<Diff> crossExit(String exitId) {
        if (combat.isActive()) {
            // Fleeing is a rules milestone. Without this the fight's initiative order survives
            // in a room nobody is standing in.
            throw new IllegalArgumentException(
                    "You cannot leave in the middle of a fight.");
        }

        var here = room();
        var exit = here.exits().stream()
                .filter(e -> e.id().equals(exitId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No exit '" + exitId + "' in " + here.roomId()));

        if (!rooms.has(exit.toRoomId())) {
            throw new IllegalArgumentException(
                    "'" + exitId + "' leads to " + exit.toRoomId() + ", which is not in this "
                            + "session");
        }

        var destination = rooms.structure(exit.toRoomId());
        var arrival = destination.exits().stream()
                .filter(e -> e.toRoomId().equals(here.roomId()))
                .findFirst()
                .map(back -> back.inward(destination.width(), destination.height()))
                .orElseGet(() -> {
                    // A one-way exit is legal and has no answering door to land beside, so the
                    // destination's authored party start is the fallback.
                    var at = destination.startPositions().party().getFirst();
                    return new Square(at.x(), at.y());
                });

        var movers = state().party().stream()
                .map(member -> state().find(member.entityId()))
                .flatMap(Optional::stream)
                .filter(Entity::isAlive)
                .map(Entity::id)
                .toList();

        // Dressed before the move, so anything reading state() after this call finds a room
        // that already knows what it looks like.
        recordDressing(exit.toRoomId());
        log.append(new Event.PartyMoved(Instant.now(), movers, here.roomId(),
                exit.toRoomId(), exitId, arrival.x(), arrival.y()));

        // A room change replaces everything, which is what a fresh Scene is for. The caller
        // sends one; there is no diff small enough to be worth inventing. Spec §9.
        return List.of();
    }
```

Add `import dm.model.Square;` and `import java.util.Optional;` if not already present.
`PartyMember`'s accessor is `entityId()` — check `dm/model/PartyMember.java` and match it.

- [ ] **Step 5: Carry exits into `scene()`**

```java
        return new SceneState(here.roomId(), here.width(), here.height(),
                here.floorType(), here.wallType(), visible, here.exits(), entities,
                here.lighting(), state().mode(), combat.view());
```

where `here` is a local `var here = room();` at the top of the method, so the room is resolved once.

- [ ] **Step 6: Run what can pass**

Run: `cd server && ./gradlew test --tests 'dm.engine.RoomsTest' --tests 'dm.state.*' --tests 'dm.model.*'`
Expected: PASS. `CrossExitTest` stays red until Task 12 authors the gallery.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/model/SceneState.java \
        server/src/test/java/dm/engine/CrossExitTest.java
git commit -m "Take the party through a door, landing them inside the next room rather than in its doorway. CrossExitTest stays red until the gallery is authored in Task 12."
```

---

### Task 8: The door is clickable

The client verb, end to end. Still a cut between rooms — the dark neighbour is Task 14 — but a
player can walk out of the crypt and back.

`pick_at` returns tokens and ground squares and has no prop ray test, so an exit is picked as the
ground square it stands on. No new pick channel; the overlay's hover highlight comes free.

**Files:**
- Modify: `server/src/main/java/dm/WsHandler.java`
- Modify: `godot/autoload/net.gd`
- Modify: `godot/autoload/table.gd`
- Modify: `godot/world/overlay.gd`
- Test: `godot/test/test_overlay.gd`

**Interfaces:**
- Consumes: `GameEngine.crossExit` (Task 7), `SceneState.exits` (Task 7).
- Produces:
  - Client message `{"type": "enterExit", "exitId": "<id>"}`
  - `Net.enter_exit(exit_id: String)`
  - `Table.exit_at(square: Vector2i) -> Dictionary` — the exit on a square, or `{}`
  - `overlay.intent` may return `{"kind": "exit", "exit_id": String, "square": Vector2i}`

- [ ] **Step 1: Handle the verb server-side**

In `server/src/main/java/dm/WsHandler.java`, add a case beside `moveTo`:

```java
            // A room change replaces everything, so it answers with a whole Scene rather than
            // diffs. The client already rebuilds props and tokens when roomId changes.
            case "enterExit" -> {
                try {
                    engine.crossExit(message.path("exitId").asText(""));
                    send(ctx, new ServerMessage.Scene(engine.scene()));
                } catch (IllegalArgumentException e) {
                    send(ctx, new ServerMessage.Error(e.getMessage()));
                }
            }
```

Match the surrounding cases' error style — check how `moveTo` reports a refusal through `act` and
follow it rather than inventing a second convention.

- [ ] **Step 2: Write the failing client test**

Add to `godot/test/test_overlay.gd`:

```gdscript
func test_clicking_a_door_square_is_an_exit_not_a_move() -> void:
	var world := _world_with_scene({
		"roomId": "crypt",
		"width": 12,
		"height": 12,
		"mode": "EXPLORATION",
		"props": [],
		"exits": [{
			"id": "door-north",
			"x": 6,
			"y": 11,
			"direction": "NORTH",
			"toRoomId": "gallery",
		}],
		"entities": [{
			"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true,
		}],
		"combat": null,
	})
	var overlay = world.get_node("Overlay")

	var on_the_door: Dictionary = overlay.intent("", Vector2i(6, 11))
	assert_eq(String(on_the_door.get("kind", "")), "exit")
	assert_eq(String(on_the_door.get("exit_id", "")), "door-north")

	var plain_floor: Dictionary = overlay.intent("", Vector2i(4, 4))
	assert_eq(String(plain_floor.get("kind", "")), "move",
		"an ordinary square is still a move")


func test_committing_an_exit_sends_enter_exit() -> void:
	Net.outbound.clear()
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12, "mode": "EXPLORATION",
		"props": [],
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
		"combat": null,
	})
	var overlay = world.get_node("Overlay")

	overlay.commit(overlay.intent("", Vector2i(6, 11)))

	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "door-north")


func test_a_door_is_not_clickable_in_combat() -> void:
	Net.outbound.clear()
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12, "mode": "COMBAT",
		"props": [],
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
		# The server refuses it too (CrossExitTest.exitsAreIllegalInCombat); the client
		# should not offer a click that can only ever come back as an error.
		"combat": {"activeId": "fighter", "round": 1, "order": [],
			"moves": [], "targets": []},
	})
	var overlay = world.get_node("Overlay")

	assert_true(overlay.intent("", Vector2i(6, 11)).is_empty())
```

`_world_with_scene` is the existing helper in `test_overlay.gd` — reuse it rather than building a
world by hand, and add the `exits` key to whatever scene dictionaries it already constructs.

- [ ] **Step 3: Run the client tests to verify they fail**

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: FAIL — `intent` returns `{"kind": "move"}` on the door square.

- [ ] **Step 4: Add the wire mirror and the verb**

In `godot/autoload/net.gd`, beside `move_to`:

```gdscript
func enter_exit(exit_id: String) -> void:
	send({"type": "enterExit", "exitId": exit_id})
```

In `godot/autoload/table.gd`, beside `prop`:

```gdscript
## The exit standing on a square, or {} if none does. Mirrors dm.model.Exit.
func exit_at(square: Vector2i) -> Dictionary:
	for e in scene.get("exits", []):
		if int(e.get("x", -1)) == square.x and int(e.get("y", -1)) == square.y:
			return e
	return {}
```

- [ ] **Step 5: Make an exit square its own intent**

In `godot/world/overlay.gd`, in `intent`, immediately after the out-of-combat guards and before
the move is returned:

```gdscript
	var at: Vector2i = square
	if at.x == int(player["x"]) and at.y == int(player["y"]):
		return {}

	# A door is a decision, not a move that happens to end somewhere. The combat branch above
	# has already returned, so this is only ever reachable out of combat — which matches the
	# server, where crossExit refuses while a fight is running.
	var exit := Table.exit_at(at)
	if not exit.is_empty():
		return {"kind": "exit", "exit_id": String(exit.get("id", "")), "square": at}

	return {"kind": "move", "actor_id": String(player["id"]), "square": at}
```

and in `commit`:

```gdscript
		"exit":
			Net.enter_exit(String(action["exit_id"]))
```

- [ ] **Step 6: Run both suites**

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: PASS, three new tests.

Run: `cd server && ./gradlew test`
Expected: as Task 7 — everything but `CrossExitTest`, which waits for the gallery.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/WsHandler.java \
        godot/autoload/net.gd \
        godot/autoload/table.gd \
        godot/world/overlay.gd \
        godot/test/test_overlay.gd
git commit -m "Make a door something you click on purpose, rather than a square you happen to walk onto."
```

---

### Task 9: A crossing replays

`ReplayRunner` drives the engine from recorded outcome events. `PartyMoved` is a new outcome and
would otherwise fall into the `default -> { }` branch, so a recorded multi-room session would
replay the whole thing in room one and diverge on the first event after the door.

**Files:**
- Modify: `server/src/main/java/dm/replay/ReplayRunner.java`
- Test: `server/src/test/java/dm/replay/ReplayRunnerTest.java`

**Interfaces:**
- Consumes: `GameEngine.crossExit` (Task 7), `Event.PartyMoved` (Task 4).
- Produces: nothing new; `ReplayRunner.replay` handles one more case.

- [ ] **Step 1: Write the failing test**

Add to `server/src/test/java/dm/replay/ReplayRunnerTest.java`:

```java
    @Test
    @DisplayName("a session that walks between rooms replays into the same rooms")
    void aCrossingReplays(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var content = new ContentLoader();
        var engine = new GameEngine(content, log, new ScriptedDiceRoller(10),
                dm.engine.Rooms.authored(content, "crypt", "gallery"));
        engine.start();
        engine.crossExit("door-north");
        engine.crossExit("door-south");
        writer.close();

        assertEquals("crypt", engine.state().roomId(), "setup: back where we started");

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }

    @Test
    @DisplayName("what you leave in a room is still there when the replay comes back")
    void replayKeepsWhatWasLeftBehind(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var content = new ContentLoader();
        var engine = new GameEngine(content, log, new ScriptedDiceRoller(10),
                dm.engine.Rooms.authored(content, "crypt", "gallery"));
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.revealProp("alcove");
        engine.crossExit("door-north");
        engine.crossExit("door-south");
        writer.close();

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());

        // The replay's own fold, not the recording engine's: this is the gate's "as you left it"
        // criterion, asserted mechanically before anyone plays it.
        var replayed = EventLog.load(writer.path()).state();
        assertEquals("crypt", replayed.roomId());
        assertEquals("crypt", replayed.find("goblin").orElseThrow().roomId());
        assertTrue(replayed.revealedHere().contains("alcove"));
    }
```

Add the imports `dm.engine.ScriptedDiceRoller` if not present, and `dm.engine.Rooms`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.replay.ReplayRunnerTest'`
Expected: FAIL — the replay produces fewer events than were recorded, because `PartyMoved` is
never re-issued. (`gallery` must exist; if Task 12 has not run, expect a `ContentLoader` failure
and carry these two tests red alongside `CrossExitTest`.)

- [ ] **Step 3: Drive the crossing**

In `server/src/main/java/dm/replay/ReplayRunner.java`, add a case in the driving switch, above
`default`:

```java
                // The exit id is recorded, so replay walks the same door rather than inferring
                // one from the destination — a room with two ways into it would otherwise be a
                // coin flip that diverges on the landing square.
                case Event.PartyMoved e -> engine.crossExit(e.throughExitId());
```

`ReplayRunner.replay` builds its engine with `new GameEngine(new ContentLoader(), log, dice)`,
which is the one-room default. A recorded multi-room session needs the rooms it actually used, and
they are derivable from the log itself:

```java
        var content = new ContentLoader();
        var log = new EventLog();
        var engine = new GameEngine(content, log, dice, roomsIn(content, recorded));
```

```java
    /**
     * The rooms a recorded session visited, entrance first.
     *
     * <p>Read out of the log rather than passed in, so replaying a file needs nothing but the
     * file. {@code RoomDressed} is emitted on first entry to every room, in entry order, which
     * makes it the one event that names them all in the right order.
     */
    private static Rooms roomsIn(ContentLoader content, List<Event> recorded) {
        var ids = recorded.stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .map(Event.RoomDressed::roomId)
                .distinct()
                .toList();
        return ids.isEmpty()
                ? Rooms.authored(content, "crypt")
                : Rooms.authored(content, ids.toArray(String[]::new));
    }
```

Add `import dm.engine.Rooms;`.

The `RoomDessed` events the replay's own engine emits are already skipped by `compare`, so
re-recording them does not create a divergence.

- [ ] **Step 4: Run the tests**

Run: `cd server && ./gradlew test --tests 'dm.replay.ReplayRunnerTest'`
Expected: PASS once the gallery exists. Until Task 12, the four pre-existing tests pass and the two
new ones fail to load content.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/replay/ReplayRunner.java \
        server/src/test/java/dm/replay/ReplayRunnerTest.java
git commit -m "Replay a session that went through a door, into the rooms the log says it visited."
```

---

# Stage 3 — The DM

---

### Task 10: Two verbs the DM did not have

`use_exit`, because "I head through the north door" is the most common sentence anyone types in a
dungeon and today it produces prose about walking through a door with no board change. And
`move_entity`, because the DM has been shown everyone's coordinates under `## Entities present`
since M0 and has never had a verb that could act on them.

The phase split is the rule from spec §6d, not a preference:

> A tool belongs in reconcile when a false positive is cheap to live with and the narrator is the
> one holding the information.

Walking to a pillar is cheap and the narrator knows it was said. Leaving the room is expensive —
screen, `## Established`, and the window's frame of reference all change at once — and
m2-evaluation §8 records the tools model starting a fight off a spoken aside.

**Files:**
- Modify: `server/src/main/java/dm/ai/ToolSchema.java`
- Modify: `server/src/main/java/dm/ai/ToolDispatcher.java`
- Modify: `server/src/main/resources/prompts/dm-tools.md`
- Modify: `server/src/main/resources/prompts/dm-reconcile.md`
- Modify: `server/src/main/resources/prompts/dm.md`
- Test: `server/src/test/java/dm/ai/TraversalToolsTest.java`

**Interfaces:**
- Consumes: `GameEngine.crossExit` (Task 7), `GameEngine.moveTo` (existing).
- Produces:
  - `ToolSchema.USE_EXIT`, `ToolSchema.MOVE_ENTITY`
  - `ToolDispatcher` cases for both

- [ ] **Step 1: Count what the narrator already implies, before writing a rule about it**

Spec §6d asks for evidence rather than a guess. Read the M2 session and count how often prose
implies the fighter moved:

```bash
cd /Users/hampton/Projects/emberdelve && python3 -c "
import json, re
verbs = r'\b(cross(es|ed)?|walk(s|ed)?|step(s|ped)?|move(s|d)?|approach(es|ed)?|back(s|ed) away|retreat(s|ed)|turn(s|ed) to|head(s|ed))\b'
hits = total = 0
for line in open('docs/evidence/session-m2-twenty-turns.jsonl'):
    e = json.loads(line)
    if e.get('type') != 'narration_logged':
        continue
    total += 1
    if re.search(verbs, e.get('text', ''), re.I):
        hits += 1
        print('  ', e['text'][:110])
print(f'{hits}/{total} narration segments imply movement')
"
```

Write the number into the reconcile rule in step 6 rather than a vague warning. If it is high —
more than roughly a third — the rule has to be strict enough that only explicit, destination-named
movement is acted on. If it is low, a softer rule will do. Record the figure in the commit message.

- [ ] **Step 2: Write the failing test**

Create `server/src/test/java/dm/ai/TraversalToolsTest.java`:

```java
package dm.ai;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.wire.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraversalToolsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static List<String> toolNames(com.fasterxml.jackson.databind.node.ArrayNode tools) {
        return tools.findValuesAsText("name");
    }

    // ---- Schema ----

    @Test
    @DisplayName("the mechanics phase is offered use_exit; reconcile is not")
    void useExitIsMechanicsOnly() {
        var engine = started(new EventLog());

        assertTrue(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.USE_EXIT));
        assertFalse(toolNames(ToolSchema.forReconcile(engine)).contains(ToolSchema.USE_EXIT));
        assertFalse(ToolSchema.allowedInReconcile(ToolSchema.USE_EXIT),
                "m2-evaluation §8: a spoken aside must not be able to move the party");
    }

    @Test
    @DisplayName("move_entity is offered in both phases")
    void moveEntityIsOfferedInBoth() {
        var engine = started(new EventLog());

        assertTrue(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.MOVE_ENTITY));
        assertTrue(toolNames(ToolSchema.forReconcile(engine)).contains(ToolSchema.MOVE_ENTITY));
        assertTrue(ToolSchema.allowedInReconcile(ToolSchema.MOVE_ENTITY));
    }

    @Test
    @DisplayName("use_exit is not offered during a fight")
    void useExitIsWithdrawnInCombat() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        assertFalse(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.USE_EXIT),
                "crossExit refuses it, so offering it is a wasted round trip and a rejection "
                        + "that counts towards degrading the turn");
    }

    @Test
    @DisplayName("the exit enum is this room's exits, by id")
    void exitEnumIsClosed() {
        var engine = started(new EventLog());

        var useExit = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("function").path("name").asText().equals(ToolSchema.USE_EXIT))
                .findFirst().orElseThrow();
        var values = useExit.path("function").path("parameters").path("properties")
                .path("exit_id").path("enum");

        assertEquals(1, values.size());
        assertEquals("door-north", values.get(0).asText());
    }

    @Test
    @DisplayName("a dead entity is not offered as something to move or to roll for")
    void deadActorsAreNotOffered() {
        var log = new EventLog();
        // Fighter initiative 20, goblin 1, then a hit that kills: the same shape
        // ReplayRunnerTest uses to stage a real fight rather than a synthetic event.
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(20, 1, 20, 8, 20, 8),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        engine.spawnGoblin(6, 6);
        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        while (engine.state().find("goblin").orElseThrow().isAlive()) {
            engine.combat().attack("fighter", "goblin", sink);
        }

        var json = ToolSchema.forTurn(engine).toString();
        assertFalse(json.contains("\"goblin\""),
                "a dead goblin offered as an actor is a rejection at dispatch every time");
    }

    // ---- Dispatch ----

    @Test
    @DisplayName("use_exit takes the party through")
    void useExitCrosses() {
        var log = new EventLog();
        var engine = started(log);

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.USE_EXIT, "{\"exit_id\":\"door-north\"}"));

        assertTrue(result.ok(), result.message());
        assertEquals("gallery", engine.state().roomId());
        assertTrue(log.events().stream().anyMatch(Event.PartyMoved.class::isInstance));
    }

    @Test
    @DisplayName("use_exit on an exit that is not here is rejected, not thrown")
    void useExitRejectsUnknown() {
        var engine = started(new EventLog());

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.USE_EXIT, "{\"exit_id\":\"door-west\"}"));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
    }

    @Test
    @DisplayName("move_entity moves a token and emits the diff the client needs")
    void moveEntityMoves() {
        var log = new EventLog();
        var engine = started(log);

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY, "{\"actor_id\":\"fighter\",\"x\":3,\"y\":5}"));

        assertTrue(result.ok(), result.message());
        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals(3, fighter.x());
        assertEquals(5, fighter.y());
        assertTrue(result.diffs().stream().anyMatch(dm.model.Diff.EntityMoved.class::isInstance));
    }

    @Test
    @DisplayName("move_entity onto something solid is rejected with a reason")
    void moveEntityRefusesObstruction() {
        var engine = started(new EventLog());
        var sarcophagus = CONTENT.room("crypt").prop("sarcophagus");

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY,
                "{\"actor_id\":\"fighter\",\"x\":%d,\"y\":%d}"
                        .formatted(sarcophagus.x(), sarcophagus.y())));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
    }

    @Test
    @DisplayName("in a fight move_entity obeys the turn and the budget, because it is the same call")
    void moveEntityInheritsCombatRules() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        // Whoever is not active cannot be moved: CombatEngine.moveTo opens with requireActive.
        String inactive = engine.combat().activeId().equals("fighter") ? "goblin" : "fighter";
        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY,
                "{\"actor_id\":\"" + inactive + "\",\"x\":5,\"y\":5}"));

        assertFalse(result.ok(), "the DM does not get to play someone else's turn");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.ai.TraversalToolsTest'`
Expected: FAIL — compilation error, `cannot find symbol: variable USE_EXIT`

- [ ] **Step 4: Add the schema**

In `server/src/main/java/dm/ai/ToolSchema.java`, add the two names beside the others:

```java
    public static final String USE_EXIT = "use_exit";
    public static final String MOVE_ENTITY = "move_entity";
```

Add `MOVE_ENTITY` to `RECONCILE_TOOLS` and deliberately leave `USE_EXIT` out:

```java
    /**
     * What {@link #forReconcile} actually offers. See {@code DmService.runReconcilePhase}.
     *
     * <p>{@code USE_EXIT} is absent on purpose, and it is the one omission worth explaining.
     * Spec §6d: a tool belongs here when a false positive is cheap and the narrator holds the
     * information. Moving the party out of a room is neither — m2-evaluation §8 records the
     * mechanics model spawning a goblin and starting a fight off a spoken aside, and a room
     * change is louder than a fight.
     */
    private static final java.util.Set<String> RECONCILE_TOOLS =
            java.util.Set.of(REVEAL_PROP, SPAWN_ENTITY, START_COMBAT, ASSERT_FACT, MOVE_ENTITY);
```

Filter the actor enum to the living, and build the two tools, inside `build(GameEngine, boolean)`:

```java
        // The dead are not actors. A dead goblin offered to roll_check or move_entity is a
        // rejection at dispatch every single time, and for the mechanics model a rejection
        // counts towards having its tools taken away.
        var actorIds = engine.state().entitiesHere().stream()
                .filter(dm.model.Entity::isAlive)
                .map(dm.model.Entity::id)
                .toList();
```

```java
        // Not offered during a fight: crossExit refuses it, so offering it buys a round trip and
        // a rejection. Same reasoning as start_combat's guard directly above.
        var exits = engine.room().exits();
        if (withChecks && !engine.combat().isActive() && !exits.isEmpty()) {
            tools.add(tool(USE_EXIT,
                    "Take the party out of this room through one of its ways out. Only when the "
                            + "player has said they are leaving.",
                    properties -> enumProp(properties, "exit_id",
                            exits.stream().map(dm.model.Exit::id).toList(),
                            "Which way out they take."),
                    "exit_id"));
        }

        if (!actorIds.isEmpty()) {
            tools.add(tool(MOVE_ENTITY,
                    "Move someone to a square. Use it when the narration says they went "
                            + "somewhere, so the board matches what was said.",
                    properties -> {
                        enumProp(properties, "actor_id", actorIds, "Who moves.");
                        intProp(properties, "x", 0, engine.room().width() - 1);
                        intProp(properties, "y", 0, engine.room().height() - 1);
                    },
                    "actor_id", "x", "y"));
        }
```

- [ ] **Step 5: Dispatch them**

In `server/src/main/java/dm/ai/ToolDispatcher.java`, add the two cases:

```java
                case ToolSchema.USE_EXIT -> useExit(args);
                case ToolSchema.MOVE_ENTITY -> moveEntity(args);
```

and the handlers:

```java
    private Result useExit(JsonNode args) {
        String exitId = args.path("exit_id").asText("");
        String from = engine.state().roomId();
        try {
            engine.crossExit(exitId);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
        // No diffs: a room change replaces everything and the caller sends a fresh Scene.
        // Spec §9.
        return Result.applied(
                "The party left " + from + " and is now in " + engine.state().roomId()
                        + ". Describe what they walk into. Do not describe " + from + " again.",
                List.of());
    }

    private Result moveEntity(JsonNode args) {
        String actorId = args.path("actor_id").asText("");
        if (engine.state().find(actorId).isEmpty()) {
            return Result.rejected("no entity '" + actorId + "' is present");
        }
        int x = args.path("x").asInt(-1);
        int y = args.path("y").asInt(-1);

        var buffer = new dm.engine.CombatSink.Buffer();
        try {
            // The same call the click makes, so bounds, obstruction, occupancy, aliveness and —
            // in a fight — requireActive and the movement budget all apply without a second set
            // of rules that could disagree with the first.
            engine.moveTo(actorId, x, y, buffer);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
        return Result.applied("Moved '" + actorId + "' to (" + x + "," + y + ").",
                buffer.collectedDiffs());
    }
```

- [ ] **Step 6: Write the prompt rules**

In `server/src/main/resources/prompts/dm-tools.md`, under `## The other tools`, add:

```markdown
- `use_exit` — the player said they are leaving, and named or clearly meant one of the ways out.
  "I head through the north door." "Let's try the far door." Not "I wonder what's through there",
  which is a thought, and not "I put my ear to the door", which is a check. Leaving is the loudest
  thing that happens outside a fight: the whole room changes. When in doubt, do not.
- `move_entity` — the player said where they went, inside this room. "I cross to the east pillar."
  Call it so the token is where the player just said they are.
```

In `server/src/main/resources/prompts/dm-reconcile.md`, after the `### Never assert an absence`
section, add — with the figure from step 1 written into it:

```markdown
### Move what the narration moved

If the description says someone crossed the room, went to a thing, or backed away, call
`move_entity` so the board agrees. The player said they walked to the pillar, the narration
followed them there, and the token should not still be by the stair.

Only when a **destination** is named or obvious. Narration is full of incidental motion — a hand
raised, a head turned, a step back from heat — and none of that is a square. If you cannot say
which square they ended on, they did not move.

You cannot take the party out of the room. There is no tool for it here and that is deliberate:
leaving is the player's decision, not a consequence of how a sentence was written.
```

In `server/src/main/resources/prompts/dm.md`, add the threshold rule to whichever section holds
the standing constraints on what the narrator may make true:

```markdown
You can write the party up to a doorway. You cannot write them through it. Going somewhere else
is not something your description makes true — the room changes when the board says it changed,
and until then you are still in this one. Describe the door, the dark beyond it, their hand on
it. Stop there.
```

- [ ] **Step 7: Run the tests**

Run: `cd server && ./gradlew test --tests 'dm.ai.*'`
Expected: PASS. `DmContextTest` and `TurnHeuristicsTest` may assert on the tool list — update the
expected names rather than loosening the assertions.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/ai/ToolSchema.java \
        server/src/main/java/dm/ai/ToolDispatcher.java \
        server/src/main/resources/prompts/dm-tools.md \
        server/src/main/resources/prompts/dm-reconcile.md \
        server/src/main/resources/prompts/dm.md \
        server/src/test/java/dm/ai/TraversalToolsTest.java
git commit -m "Give the DM a way out of a room and a way to move who it just said moved, and keep the first of those out of reconcile."
```

---

### Task 11: What the DM knows on the other side of a door

Spec §7. Most of the projection swaps itself — `factsHere()` was already room-scoped and
`entitiesHere()` joined it in Task 4. This is the four things that do not swap themselves.

**Files:**
- Modify: `server/src/main/java/dm/ai/DmService.java`
- Test: `server/src/test/java/dm/ai/ThresholdContextTest.java`

**Interfaces:**
- Consumes: `WorldState.hasVisited` (Task 4), `Direction.lowerName` (Task 1).
- Produces:
  - `DmService.waysOut(RoomDefinition)` — package-private, testable without a service
  - `DmService.ARRIVAL_FIRST` / `DmService.ARRIVAL_RETURN` directives
  - A threshold line appended to the transcript on a crossing

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/ai/ThresholdContextTest.java`:

```java
package dm.ai;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7a: the player can see there is a door and cannot see what is behind it. A destination
 * id in the prompt is a string the narrator can read aloud — m2-evaluation §8's grid-coordinate
 * finding in a different costume.
 */
class ThresholdContextTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("ways out name the wall, never the room on the other side")
    void waysOutNameDirections() {
        var block = DmService.waysOut(CONTENT.room("crypt"));

        assertTrue(block.contains("north"), block);
        assertFalse(block.contains("gallery"), "the destination is not the player's to know");
        assertFalse(block.contains("door-north"), "nor is the id");
    }

    @Test
    @DisplayName("a room with no way out says so rather than printing an empty heading")
    void noExitsNoHeading() {
        var sealed = new dm.content.RoomDefinition(
                "sealed", "A Sealed Room", 4, 4,
                dm.model.FloorType.STONE, dm.model.WallType.STONE,
                dm.model.LightingPreset.DARK,
                java.util.List.of(), java.util.List.of(),
                new dm.content.RoomDefinition.StartPositions(
                        java.util.List.of(new dm.content.RoomDefinition.Point(1, 1)),
                        new dm.content.RoomDefinition.Point(2, 2)),
                new dm.content.RoomDefinition.DmNotes("o", "s", null, null, null));

        // An empty "## Ways out" heading is an invitation to invent one — the same reason
        // ToolSchema does not offer reveal_prop with an empty enum.
        assertEquals("", DmService.waysOut(sealed));
    }

    @Test
    @DisplayName("the arrival directive forks on whether the party has been here before")
    void arrivalForksOnVisits() {
        assertNotEquals(DmService.ARRIVAL_FIRST, DmService.ARRIVAL_RETURN);
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("been here"),
            DmService.ARRIVAL_RETURN);
        // The window will have dropped the first visit; "do not rebuild it from scratch" is the
        // whole point of telling it. Spec §7b.
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("not")
                        && DmService.ARRIVAL_RETURN.toLowerCase().contains("again"),
                DmService.ARRIVAL_RETURN);
    }

    @Test
    @DisplayName("the threshold line says a seam happened, and names neither room's contents")
    void thresholdMarkerMarksTheSeam() {
        var line = DmService.thresholdMarker("The Ashen Crypt", "The Long Gallery");

        assertTrue(line.contains("The Ashen Crypt"), line);
        assertTrue(line.contains("The Long Gallery"), line);
        // Bleed is the failure: room 1's tallies written onto room 2's stone, because the
        // six-turn window is still full of room 1. Spec §7b.
        assertTrue(line.toLowerCase().contains("different room")
                        || line.toLowerCase().contains("somewhere else"), line);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.ai.ThresholdContextTest'`
Expected: FAIL — compilation error, `cannot find symbol: method waysOut`

- [ ] **Step 3: Write the ways-out block**

In `server/src/main/java/dm/ai/DmService.java`:

```java
    /**
     * The ways out of a room, by the wall they are in.
     *
     * <p>Never the destination and never the exit id — spec §7a. The player can see there is a
     * door and cannot see what is behind it; the client renders the room beyond unlit for exactly
     * that reason. A room id in the prompt is a string the narrator can read aloud, which is
     * m2-evaluation §8's grid-coordinate finding wearing a different hat.
     *
     * <p>Empty when there are none, heading included. A "## Ways out" with nothing under it is an
     * invitation to invent one, the same way an empty tool enum is.
     */
    static String waysOut(RoomDefinition room) {
        if (room.exits().isEmpty()) {
            return "";
        }
        var sb = new StringBuilder("\n## Ways out\n\n");
        for (var exit : room.exits()) {
            sb.append("- a way out in the ").append(exit.direction().lowerName())
                    .append(" wall\n");
        }
        return sb.toString();
    }
```

Call it in `worldState(boolean)`, immediately before the `## Grid` block:

```java
        sb.append(waysOut(room));
```

- [ ] **Step 4: Write the visited flag and the arrival directives**

```java
    /**
     * What the narrator is asked for on arriving somewhere new. A reworded {@code OPENING} — the
     * beat is the same and the room is not.
     */
    static final String ARRIVAL_FIRST = "The party has just come through into this room. "
            + "Open it: what they walk into.";

    /**
     * And on coming back. The six-turn window has dropped the first visit, so without this the
     * narrator rebuilds the room from its dressing as though it had never been here — which is
     * the fault `## Established` warns against, in a room the transcript has entirely forgotten.
     */
    static final String ARRIVAL_RETURN = "The party has come back into a room they have been "
            + "here before. They know this place. Do not describe it again from scratch — say "
            + "what has changed, or what they came back for, in a sentence or two.";

    static String thresholdMarker(String fromRoomName, String toRoomName) {
        return "[The party left " + fromRoomName + " and is now in " + toRoomName
                + ". Everything described before this line happened in a different room.]";
    }
```

In `worldState(boolean)`, under the `## Room` heading, add the flag:

```java
        sb.append("## Room: ").append(room.name()).append("\n\n");
        if (engine.state().hasVisited(room.roomId())) {
            sb.append("The party has been in this room before.\n\n");
        }
```

`hasVisited` is true for the room you are standing in from the moment you arrive, so this line is
present on the opening turn too. That is correct: on the opening turn the transcript is empty and
the directive is `OPENING`, which already says it is an opening.

- [ ] **Step 5: Put the marker in the transcript, and pick the arrival directive**

`DmService` holds the turn transcript and chooses a directive per turn. Find where `OPENING` and
`COMBAT_BEAT` are selected and add the arrival case: after a turn whose dispatcher results
included a `use_exit`, the directive is `ARRIVAL_FIRST` or `ARRIVAL_RETURN` depending on
`engine.state().hasVisited(...)` **as it was before the crossing** — read the flag before calling
the dispatcher, or the party has always just visited the room it is standing in.

The simplest correct shape: `runMechanicsPhase` returns whether a crossing happened and what the
previous room was, and the prose phase appends `thresholdMarker(previousRoomName, room().name())`
to the transcript before its directive. Follow the existing structure rather than restructuring
the phase plumbing — the transcript append and the directive choice are two lines each in a method
that already does both for combat beats.

- [ ] **Step 6: Run the tests**

Run: `cd server && ./gradlew test --tests 'dm.ai.*'`
Expected: PASS. `DmContextTest` asserts on the world-state block and will need `## Ways out`
added to its expectations — the crypt now has one exit, so the block is no longer empty.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/ai/DmService.java \
        server/src/test/java/dm/ai/ThresholdContextTest.java \
        server/src/test/java/dm/ai/DmContextTest.java
git commit -m "Tell the DM which walls have doors, that it has been here before, and where the seam in the transcript is."
```

---

# Stage 4 — Content and rendering

---

### Task 12: The room behind the north door

`dmNotes.theDoor` currently says *"The north door is sealed and will not open in this session.
Handle it in fiction — the mechanism is broken, the stone has settled, something heavy rests
against it on the far side. Do not tell the player the room is unfinished."* This task is the one
that stops that being a lie, and the M2 session's copper key stops being a lock that clicks onto
nothing.

Unblocks `CrossExitTest` and the two new `ReplayRunnerTest` cases.

**Constraints:** invariant #10 — no new prop types. The gallery uses `DOOR`, `PILLAR`, `BRAZIER`,
`RUBBLE`, `ALCOVE`, which is the whole enum minus `SARCOPHAGUS`. No new DM-note fields either: the
gallery's hidden alcove carries a `revealHint`, which is the mechanism-backed way to hide something
(spec §12a) rather than a prose promise.

**Files:**
- Create: `server/src/main/resources/content/rooms/gallery.json`
- Modify: `server/src/main/resources/content/rooms/crypt.json`
- Modify: `server/src/main/java/dm/App.java`
- Test: `server/src/test/java/dm/content/TwoRoomsTest.java`

**Interfaces:**
- Consumes: `RoomDefinition.exits` (Task 1).
- Produces: room id `"gallery"` with a `door-south` exit answering the crypt's `door-north`.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/content/TwoRoomsTest.java`:

```java
package dm.content;

import dm.model.Direction;
import dm.model.PropType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code LayoutValidator} will assert for a generated dungeon, asserted by hand for the two
 * rooms M3 authors. A door that nothing answers is a room you fall out of.
 */
class TwoRoomsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("every exit is answered by one going the other way")
    void exitsAreReciprocal() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                var other = CONTENT.room(exit.toRoomId());
                var back = other.exits().stream()
                        .filter(e -> e.toRoomId().equals(roomId))
                        .filter(e -> e.direction() == exit.direction().opposite())
                        .findFirst();
                assertTrue(back.isPresent(),
                        roomId + " has a " + exit.direction() + " exit to " + other.roomId()
                                + " and nothing answers it");
            }
        }
    }

    @Test
    @DisplayName("an exit stands in the wall it names")
    void exitsAreOnTheirWall() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                switch (exit.direction()) {
                    case NORTH -> assertEquals(room.height() - 1, exit.y(), exit.id());
                    case SOUTH -> assertEquals(0, exit.y(), exit.id());
                    case EAST -> assertEquals(room.width() - 1, exit.x(), exit.id());
                    case WEST -> assertEquals(0, exit.x(), exit.id());
                }
            }
        }
    }

    @Test
    @DisplayName("a door is never in a corner, because a corner has no inside")
    void exitsAvoidCorners() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                boolean cornerX = exit.x() == 0 || exit.x() == room.width() - 1;
                boolean cornerY = exit.y() == 0 || exit.y() == room.height() - 1;
                assertFalse(cornerX && cornerY, roomId + ": " + exit.id() + " is in a corner");
            }
        }
    }

    @Test
    @DisplayName("nothing solid stands in a doorway, or on the square you land on")
    void doorwaysAndLandingsAreClear() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                assertFalse(room.isObstructed(exit.x(), exit.y()),
                        roomId + ": something solid is in " + exit.id());
                var inward = exit.inward(room.width(), room.height());
                assertFalse(room.isObstructed(inward.x(), inward.y()),
                        roomId + ": you would arrive inside a solid prop at " + inward);
            }
        }
    }

    @Test
    @DisplayName("an exit names a DOOR prop standing on its square")
    void exitsHaveDoors() {
        for (var roomId : new String[] {"crypt", "gallery"}) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                var prop = room.prop(exit.id());
                assertEquals(PropType.DOOR, prop.type(), exit.id());
                assertEquals(exit.x(), prop.x());
                assertEquals(exit.y(), prop.y());
            }
        }
    }

    @Test
    @DisplayName("the gallery has something to find, and it is described")
    void galleryHasAHiddenThing() {
        var gallery = CONTENT.room("gallery");

        // The gate's "as you left it" referent — reveal it, leave, come back, find it revealed.
        assertFalse(gallery.hiddenPropIds().isEmpty());
        for (var id : gallery.hiddenPropIds()) {
            var prop = gallery.prop(id);
            assertNotNull(prop.revealHint(), id + " has no hint, so nothing can find it");
            assertFalse(prop.revealHint().isBlank(), id);
            assertFalse(prop.description().isBlank(), id);
        }
    }

    @Test
    @DisplayName("the gallery reads as somewhere else, not the crypt again")
    void galleryIsNotTheCryptAgain() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");

        assertNotEquals(crypt.name(), gallery.name());
        assertNotEquals(crypt.lighting(), gallery.lighting(),
                "a second room lit exactly like the first is a reskin");
        assertNotEquals(crypt.width() + "x" + crypt.height(),
                gallery.width() + "x" + gallery.height());
    }

    @Test
    @DisplayName("the crypt no longer says its north door cannot open")
    void theSealedDoorNoteIsGone() {
        var note = CONTENT.room("crypt").dmNotes().theDoor();

        assertTrue(note == null || !note.toLowerCase().contains("sealed"),
                "the door opens now; a standing note that it does not is a lie the DM will "
                        + "keep telling: " + note);
    }

    @Test
    @DisplayName("the party start is where you land coming through the door")
    void startsAgreeWithArrivals() {
        var gallery = CONTENT.room("gallery");
        var south = gallery.exits().stream()
                .filter(e -> e.direction() == Direction.SOUTH)
                .findFirst().orElseThrow();

        var landing = south.inward(gallery.width(), gallery.height());
        var start = gallery.startPositions().party().getFirst();

        assertEquals(landing.x(), start.x(),
                "the fallback start and the arrival square should not disagree");
        assertEquals(landing.y(), start.y());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.content.TwoRoomsTest'`
Expected: FAIL — `ContentLoader` cannot find `gallery`.

- [ ] **Step 3: Author the gallery**

Create `server/src/main/resources/content/rooms/gallery.json`. Ten wide by sixteen deep, so it is
shaped nothing like the crypt's 12×12 and reads as a different kind of space when the camera
frames it. `DIM` rather than `TORCHLIT`, so walking through the door is a change in light as well
as in geometry.

`door-south` sits at (5, 0) — the south wall, non-corner in a ten-wide room — and its `inward` is
(5, 1), which is also the authored party start.

```json
{
  "roomId": "gallery",
  "name": "The Long Gallery",
  "width": 10,
  "height": 16,
  "floorType": "TILED",
  "wallType": "CARVED",
  "lighting": "DIM",
  "props": [
    {
      "id": "door-south",
      "type": "DOOR",
      "x": 5,
      "y": 0,
      "rotation": 0,
      "hidden": false,
      "description": "The slab door back to the crypt, standing open on a dark stair-head. Its iron bands are the same work as the lamp's runes."
    },
    {
      "id": "pillar-near-west",
      "type": "PILLAR",
      "x": 2,
      "y": 4,
      "rotation": 0,
      "hidden": false,
      "description": "A fluted pillar, first of a line of four. Something has been chiselled off it at shoulder height, thoroughly, leaving a pale scar."
    },
    {
      "id": "pillar-near-east",
      "type": "PILLAR",
      "x": 7,
      "y": 4,
      "rotation": 0,
      "hidden": false,
      "description": "Its partner across the aisle, scarred in the same place and the same way."
    },
    {
      "id": "pillar-far-west",
      "type": "PILLAR",
      "x": 2,
      "y": 11,
      "rotation": 0,
      "hidden": false,
      "description": "The third pillar. This one still carries its carving: a procession of small robed figures, all walking south."
    },
    {
      "id": "pillar-far-east",
      "type": "PILLAR",
      "x": 7,
      "y": 11,
      "rotation": 0,
      "hidden": false,
      "description": "The fourth. Its figures walk south too, and the last of them has no head."
    },
    {
      "id": "brazier-head",
      "type": "BRAZIER",
      "x": 5,
      "y": 14,
      "rotation": 0,
      "hidden": false,
      "description": "A cold brazier at the head of the gallery, choked with grey ash. Whatever burned here burned out a long time before the green fires downstairs were lit."
    },
    {
      "id": "ceiling-fall",
      "type": "RUBBLE",
      "x": 8,
      "y": 8,
      "rotation": 0,
      "hidden": false,
      "description": "A cone of fallen ceiling, tiles and mortar, with the dust long settled into it."
    },
    {
      "id": "niche",
      "type": "ALCOVE",
      "x": 0,
      "y": 9,
      "rotation": 0,
      "hidden": true,
      "revealHint": "The west wall's tiling runs in a straight course except where it does not — one section is set a finger's width proud of the rest, and the grout there is a different grey.",
      "description": "A niche behind a facing stone, at knee height. It holds a stack of six clay tokens, each stamped with the same procession as the far pillars."
    }
  ],
  "exits": [
    {
      "id": "door-south",
      "x": 5,
      "y": 0,
      "direction": "SOUTH",
      "toRoomId": "crypt"
    }
  ],
  "startPositions": {
    "party": [{ "x": 5, "y": 1 }],
    "goblinSpawn": { "x": 5, "y": 8 }
  },
  "dmNotes": {
    "overview": "A processional gallery, long and narrow, running north from the crypt stair. Four pillars line the aisle. The far end is a dead end with a cold brazier at it — there is no other way on from here.",
    "sensory": "Dry where the crypt was damp, and much colder. Dust hangs in the air where the ceiling has come down. Footsteps carry the whole length and come back."
  }
}
```

The crypt-specific note fields are absent, and `RoomDefinition.DmNotes` leaves them null — which
`DmService.appendNote` already handles by writing nothing. The gallery's secret is its hidden
niche, backed by a prop the `reveal_prop` tool can actually cash out. Spec §12a.

- [ ] **Step 4: Unseal the crypt's north door**

In `server/src/main/resources/content/rooms/crypt.json`, replace `dmNotes.theDoor` with what is
true now. Leave the `door-north` prop description alone — *"A slab door on the north wall, banded
in tarnished iron. It has no handle on this side."* is still true, and a door with no handle that
the copper key opens is better fiction than one that never opened at all:

```json
    "theDoor": "The north door opens onto a stair up into a long gallery. It is heavy and it sticks, and the copper lamp-key turns its lock — but it opens. Anything the player was told earlier about it being sealed was true of a door that had not been unlocked yet; do not restate it as though it still cannot open."
```

The `overview` still says *"The only exit the party knows of is the stair behind them to the
south"*, which remains true at the start of a session — the party has not tried the north door yet.
Leave it.

- [ ] **Step 5: Boot the session with both rooms**

In `server/src/main/java/dm/App.java`:

```java
        var engine = cli.generateSeed() == null
                ? new GameEngine(content, eventLog, dice, Rooms.authored(content, "crypt", "gallery"))
                : new GameEngine(content, eventLog, dice, room);
```

- [ ] **Step 6: Run everything**

Run: `cd server && ./gradlew test`
Expected: PASS — the whole suite, including `CrossExitTest` and the two `ReplayRunnerTest` cases
that have been red since Tasks 7 and 9.

If `TwoRoomsTest.doorwaysAndLandingsAreClear` fails, a prop is standing in a doorway or on a
landing square — move the prop, not the door. If `startsAgreeWithArrivals` fails, the authored
party start and `inward()` disagree; `inward()` is the one that runs in play.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/resources/content/rooms/gallery.json \
        server/src/main/resources/content/rooms/crypt.json \
        server/src/main/java/dm/App.java \
        server/src/test/java/dm/content/TwoRoomsTest.java
git commit -m "Put a gallery behind the north door, and let the crypt stop insisting the door cannot open."
```

---

### Task 13: Every room gets its own place in the world

`grid_to_world` centres the current room on the world origin, so two rooms would be drawn on top of
each other. It becomes room-aware against a per-room origin table with exactly one room in it — the
current one, at the origin — so behaviour is identical and Task 14 has somewhere to put a
neighbour.

Twelve production call sites and about twenty GUT assertions, one of them named
`test_grid_to_world_centres_the_room_on_the_origin`. A visible change, which is the good kind.

**Files:**
- Modify: `godot/world/world.gd`
- Modify: `godot/world/overlay.gd`
- Modify: `godot/world/room.gd`
- Modify: `godot/world/tokens/token.gd`
- Test: `godot/test/test_world.gd`

**Interfaces:**
- Produces:
  - `World.grid_to_world(room_id: String, x: int, y: int) -> Vector3`
  - `World.world_to_grid(point: Vector3) -> Vector2i` — still current-room, and now explicitly so
  - `World.register_room(room_id: String, size: Vector2i, origin: Vector3) -> void`
  - `World.room_origin(room_id: String) -> Vector3`
  - `World.current_room_id() -> String` — so the overlay, room and token scripts do not reach
    into the private `_room_id`

- [ ] **Step 1: Write the failing test**

Replace `test_grid_to_world_centres_the_room_on_the_origin` in `godot/test/test_world.gd` and add
the new cases:

```gdscript
func test_the_current_room_is_still_centred_on_the_origin() -> void:
	var world := _world_with_room(12, 12)
	# Unchanged behaviour for the room the party is in — the whole point of taking the room id
	# as an argument now is that a second room can exist without moving the first.
	assert_eq(world.grid_to_world("crypt", 0, 0), Vector3(-5.5, 0.0, 5.5))
	assert_eq(world.grid_to_world("crypt", 11, 11), Vector3(5.5, 0.0, -5.5))
	assert_eq(world.grid_to_world("crypt", 2, 2), Vector3(-3.5, 0.0, 3.5))


func test_a_registered_neighbour_sits_where_it_was_put() -> void:
	var world := _world_with_room(12, 12)
	world.register_room("gallery", Vector2i(10, 16), Vector3(0.0, 0.0, -14.0))

	# Its own centre, offset by its origin: a 10x16 room's (5, 8) is its middle.
	assert_eq(world.grid_to_world("gallery", 5, 8), Vector3(0.5, 0.0, -14.5))
	assert_eq(world.room_origin("gallery"), Vector3(0.0, 0.0, -14.0))


func test_an_unregistered_room_falls_back_to_the_current_one() -> void:
	var world := _world_with_room(12, 12)
	# A diff naming a room the client has not been told about must not put a token at NaN.
	assert_eq(world.grid_to_world("nowhere", 2, 2), world.grid_to_world("crypt", 2, 2))


func test_world_to_grid_still_inverts_the_current_room() -> void:
	var world := _world_with_room(12, 12)
	for x in range(12):
		for y in range(12):
			var point: Vector3 = world.grid_to_world("crypt", x, y)
			assert_eq(world.world_to_grid(point), Vector2i(x, y), "square (%d, %d)" % [x, y])
```

`_world_with_room(w, h)` builds a world whose `Table.scene` has `roomId: "crypt"` and those
dimensions — write it beside the existing helpers in `test_world.gd`, following whatever the file
already does to stand a world up. Update the remaining `grid_to_world(x, y)` call sites in
`test_world.gd`, `test_tokens.gd`, `test_overlay.gd` and `test_chrome.gd` to pass the room id.

- [ ] **Step 2: Run the client tests to verify they fail**

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: FAIL — `grid_to_world` takes two arguments.

- [ ] **Step 3: Give the world a per-room origin table**

In `godot/world/world.gd`, replace `grid_to_world` and `world_to_grid`:

```gdscript
## Where each room sits in world space, and how big it is. The room the party is in is always
## at the origin; a neighbour is offset so its answering door lines up with the one you came
## through. Invariant #4 is untouched — this is one scene with two rectangles in it, not two
## scenes.
var _origins: Dictionary = {}
var _sizes: Dictionary = {}


func register_room(room_id: String, size: Vector2i, origin: Vector3) -> void:
	_sizes[room_id] = size
	_origins[room_id] = origin


func room_origin(room_id: String) -> Vector3:
	return _origins.get(room_id, Vector3.ZERO)


## A square's centre in world space.
##
## Takes the room because there is more than one now. A room the client has not been told about
## falls back to the current one rather than to NaN — a diff naming an unknown room is a bug
## worth seeing as a token in the wrong place, not as a token that has vanished.
func grid_to_world(room_id: String, x: int, y: int) -> Vector3:
	var size: Vector2i = _sizes.get(room_id, Vector2i(_room_width(), _room_height()))
	var origin: Vector3 = _origins.get(room_id, Vector3.ZERO)
	return origin + Vector3(
		x - float(size.x) / 2.0 + 0.5,
		0.0,
		-(y - float(size.y) / 2.0 + 0.5),
	)


## The inverse, for the room the party is in — which is the only room anything is picked in.
## A neighbour is scenery until you walk into it, and clicking one is not a move the server
## would accept.
func world_to_grid(point: Vector3) -> Vector2i:
	var width := float(_room_width())
	var height := float(_room_height())
	return Vector2i(
		roundi(point.x + width / 2.0 - 0.5),
		roundi(-point.z + height / 2.0 - 0.5),
	)
```

In `_on_scene_changed`, register the current room at the origin before anything is rebuilt:

```gdscript
	if room_id != _room_id:
		_room_id = room_id
		_origins.clear()
		_sizes.clear()
		register_room(room_id, Vector2i(_room_width(), _room_height()), Vector3.ZERO)
		_rebuild_props()
		_rebuild_tokens()
		_follow_party()
```

- [ ] **Step 4: Update the twelve call sites**

Each becomes `grid_to_world(_room_id, ...)` except where the caller already knows a room id:

- `world.gd:107` — `node.position = grid_to_world(_room_id, int(prop.get("x", 0)), int(prop.get("y", 0)))`
- `world.gd:147` — `world_to_grid(token.position)` is unchanged
- `world.gd:166` — `token.position = grid_to_world(_room_id, int(entity.get("x", 0)), int(entity.get("y", 0)))`
- `world.gd:222` — `centre += grid_to_world(_room_id, int(e["x"]), int(e["y"]))`
- `overlay.gd:97`, `:113`, `:120` — `world.grid_to_world(world._room_id, ...)`; add a
  `func current_room_id() -> String: return _room_id` to `world.gd` and call that rather than
  reaching into a private, so the overlay is not coupled to the field name
- `room.gd:385` — `world.grid_to_world(world.current_room_id(), gx, gy)`
- `token.gd:97`, `:102` — `world.grid_to_world(world.current_room_id(), square.x, square.y)`

- [ ] **Step 5: Run the client tests**

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: PASS. Nothing has moved on screen — one room, at the origin, exactly as before.

- [ ] **Step 6: Commit**

```bash
git add godot/world/world.gd godot/world/overlay.gd godot/world/room.gd \
        godot/world/tokens/token.gd godot/test/
git commit -m "Let a square say which room it is in, so the world can hold more than one rectangle."
```

---

### Task 14: The room beyond the door is already there

Spec §8. The neighbour renders as floor and walls only — no torches, no props, no entities, and
nothing in the DM's prompt. An unlit, empty room is one the narrator cannot contradict, because a
dark space is what a dark space looks like.

**Files:**
- Create: `server/src/main/java/dm/model/RoomOutline.java`
- Modify: `server/src/main/java/dm/model/SceneState.java`
- Modify: `server/src/main/java/dm/engine/GameEngine.java`
- Modify: `godot/world/world.gd`
- Modify: `godot/world/room.gd`
- Test: `server/src/test/java/dm/model/RoomOutlineTest.java`
- Test: `godot/test/test_room.gd`

**Interfaces:**
- Consumes: `Exit` (Task 1), `Rooms` (Task 6), `World.register_room` (Task 13).
- Produces:
  - `RoomOutline(String roomId, int width, int height, FloorType floorType, WallType wallType, double offsetX, double offsetZ)`
  - `RoomOutline.beside(RoomDefinition here, Exit exit, RoomDefinition there)` returning `RoomOutline`
  - `SceneState` gains `List<RoomOutline> neighbours`

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/model/RoomOutlineTest.java`:

```java
package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a neighbour sits, in the client's world space.
 *
 * <p>Computed here rather than in GDScript so there is one implementation, testable without a
 * renderer — and because the generator plan's packing will need the same arithmetic at dungeon
 * scale.
 */
class RoomOutlineTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    /** The client's own mapping, so the test asserts against what will actually be drawn. */
    private static double[] worldOf(int x, int y, int width, int height) {
        return new double[] {x - width / 2.0 + 0.5, -(y - height / 2.0 + 0.5)};
    }

    @Test
    @DisplayName("the neighbour's answering door sits one step beyond the door you are looking at")
    void doorsLineUp() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");
        var north = crypt.exits().getFirst();

        var outline = RoomOutline.beside(crypt, north, gallery);

        var mine = worldOf(north.x(), north.y(), crypt.width(), crypt.height());
        var back = gallery.exits().getFirst();
        var theirs = worldOf(back.x(), back.y(), gallery.width(), gallery.height());

        // One square further north is one less z, because the client's z runs south.
        assertEquals(mine[0], outline.offsetX() + theirs[0], 1e-9);
        assertEquals(mine[1] - 1.0, outline.offsetZ() + theirs[1], 1e-9);
    }

    @Test
    @DisplayName("the outline carries what it takes to draw a floor and walls, and nothing else")
    void outlineIsGeometryOnly() {
        var crypt = CONTENT.room("crypt");
        var gallery = CONTENT.room("gallery");

        var outline = RoomOutline.beside(crypt, crypt.exits().getFirst(), gallery);

        assertEquals("gallery", outline.roomId());
        assertEquals(gallery.width(), outline.width());
        assertEquals(gallery.height(), outline.height());
        assertEquals(gallery.floorType(), outline.floorType());
        assertEquals(gallery.wallType(), outline.wallType());

        // No lighting, no props, no entities. A neighbour the DM is never told about must not
        // be a neighbour the client can render the contents of. Spec §8b.
        String json = dm.wire.Json.MAPPER.valueToTree(outline).toString();
        assertFalse(json.contains("lighting"), json);
        assertFalse(json.contains("props"), json);
        assertFalse(json.contains("entities"), json);
    }

    @Test
    @DisplayName("a one-way exit has no answering door and produces no outline")
    void oneWayExitsAreNotDrawn() {
        var crypt = CONTENT.room("crypt");
        var noWayBack = new dm.content.RoomDefinition(
                "void", "The Void", 4, 4, FloorType.STONE, WallType.STONE,
                LightingPreset.DARK, java.util.List.of(), java.util.List.of(),
                new dm.content.RoomDefinition.StartPositions(
                        java.util.List.of(new dm.content.RoomDefinition.Point(1, 1)),
                        new dm.content.RoomDefinition.Point(2, 2)),
                new dm.content.RoomDefinition.DmNotes("o", "s", null, null, null));

        // Without a door to line up on there is no defensible place to put it, and a guessed
        // one would be a room drawn through a wall.
        assertNull(RoomOutline.beside(crypt, crypt.exits().getFirst(), noWayBack));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.model.RoomOutlineTest'`
Expected: FAIL — compilation error, `cannot find symbol: class RoomOutline`

- [ ] **Step 3: Write `RoomOutline`**

Create `server/src/main/java/dm/model/RoomOutline.java`:

```java
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
 */
public record RoomOutline(String roomId, int width, int height, FloorType floorType,
                          WallType wallType, double offsetX, double offsetZ) {

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
                there.floorType(), there.wallType(), throughX - backX, throughZ - backZ);
    }

    /** Matches {@code World.grid_to_world} exactly. Two mappings would be one bug. */
    private static double localX(int x, int width) {
        return x - width / 2.0 + 0.5;
    }

    private static double localZ(int y, int height) {
        return -(y - height / 2.0 + 0.5);
    }
}
```

- [ ] **Step 4: Put neighbours on the wire**

In `server/src/main/java/dm/model/SceneState.java`, add `List<RoomOutline> neighbours` after
`exits`, and carry it through `asSeenByPlayer`.

In `GameEngine.scene()`, build them:

```java
        // Only rooms this session actually has. An exit to a room that is not loaded draws
        // nothing rather than guessing at a shape.
        var neighbours = here.exits().stream()
                .filter(exit -> rooms.has(exit.toRoomId()))
                .map(exit -> RoomOutline.beside(here, exit, rooms.structure(exit.toRoomId())))
                .filter(java.util.Objects::nonNull)
                .toList();
```

`rooms.structure` rather than `room()` on purpose: an outline needs no dressing, and composing one
would read a room's prose to draw its floor.

- [ ] **Step 5: Write the failing client test**

Add to `godot/test/test_room.gd`:

```gdscript
func test_a_neighbour_is_built_as_geometry_with_no_lights_and_no_props() -> void:
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.5, "offsetZ": -14.5,
		}],
	})
	await wait_frames(2)

	var neighbour := world.get_node_or_null("Neighbours/gallery")
	assert_not_null(neighbour, "the room beyond the door should be built")

	# Spec §8b: geometry only. A lit neighbour is a room the narrator has never been told about
	# and will describe wrongly; MAX_TORCH_LIGHTS is also a per-room budget.
	var lights := 0
	for node in neighbour.find_children("*", "Light3D", true, false):
		lights += 1
	assert_eq(lights, 0, "a neighbour carries no torches")


func test_the_neighbour_is_registered_where_the_server_put_it() -> void:
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [], "neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.5, "offsetZ": -14.5,
		}],
	})
	await wait_frames(2)

	assert_eq(world.room_origin("gallery"), Vector3(0.5, 0.0, -14.5))
```

- [ ] **Step 6: Build the neighbour**

In `godot/world/world.gd`, in `_on_scene_changed`'s room-change branch, after registering the
current room:

```gdscript
		_rebuild_neighbours()
```

and:

```gdscript
## The rooms you can see through the doorways, as floor and walls only.
##
## No torches, no props, no tokens, and nothing about them in the DM's prompt — spec §8b. The
## payoff is that walking through a door lights and dresses a room that was already standing
## there, instead of cutting to black and rebuilding the same rectangle.
func _rebuild_neighbours() -> void:
	var holder := get_node_or_null("Neighbours")
	if holder == null:
		holder = Node3D.new()
		holder.name = "Neighbours"
		add_child(holder)
	for child in holder.get_children():
		child.queue_free()

	for outline in Table.scene.get("neighbours", []):
		var room_id := String(outline.get("roomId", ""))
		if room_id.is_empty():
			continue
		var size := Vector2i(int(outline.get("width", 0)), int(outline.get("height", 0)))
		var origin := Vector3(
			float(outline.get("offsetX", 0.0)), 0.0, float(outline.get("offsetZ", 0.0)))
		register_room(room_id, size, origin)

		var node := Room.new()
		node.name = room_id
		holder.add_child(node)
		node.build_unlit(room_id, size,
			String(outline.get("floorType", "STONE")),
			String(outline.get("wallType", "STONE")))
```

In `godot/world/room.gd`, add the unlit build. The file already builds a floor and walls from a
room id, size and the two material types; `build_unlit` is that path with the torch pass skipped:

```gdscript
## Floor and walls, and no lights at all.
##
## The lit build takes its torch spacing from a LightingPreset. A neighbour has no preset, on
## purpose: it is a room the DM has not been told about, and a lit one would be a room on screen
## the narrator can contradict. It also keeps MAX_TORCH_LIGHTS a per-room budget rather than a
## number two rooms quietly share.
func build_unlit(room_id: String, size: Vector2i, floor_type: String, wall_type: String) -> void:
	_build_floor(room_id, size, floor_type)
	_build_walls(room_id, size, wall_type)
```

Refactor the existing build so the floor and wall passes are callable without the torch pass, and
so both take an explicit room id and size rather than reading `Table.scene`. Follow the file's own
structure; the FNV-1a tile and segment picks are keyed on the room id and must keep being, or the
gallery would be tiled identically to the crypt.

- [ ] **Step 7: Run both suites**

Run: `cd server && ./gradlew test`
Expected: PASS.

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: PASS.

- [ ] **Step 8: Look at it**

Run: `cd server && ./gradlew run` and start the client. Walk to the north door. Before clicking it,
confirm the gallery is visible through the doorway as dark floor and walls, correctly aligned —
the two door squares should be adjacent, not overlapping and not a square apart. Click through and
confirm the gallery lights.

If the rooms overlap or float apart, the offset arithmetic in `RoomOutline.beside` and
`World.grid_to_world` disagree; `RoomOutlineTest.doorsLineUp` asserts against a copy of the
client's mapping, so fix whichever one has drifted from the other rather than nudging a constant.

- [ ] **Step 9: Commit**

```bash
git add server/src/main/java/dm/model/RoomOutline.java \
        server/src/main/java/dm/model/SceneState.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/test/java/dm/model/RoomOutlineTest.java \
        godot/world/world.gd godot/world/room.gd godot/test/test_room.gd
git commit -m "Show the room through the doorway as dark stone, so walking in lights a place that was already there."
```

---

# Stage 5 — The gate

---

### Task 15: Play it, and write down whether it held

Spec §10. Four of the things most likely to have gone wrong are invisible to an assertion — bleed,
re-introduction on return, `move_entity` making the fighter wander, and whether the dark neighbour
reads as a dungeon or as a bug. All four are judgements about prose.

**Files:**
- Create: `docs/m3-evaluation.md`
- Create: `docs/evidence/session-m3-traversal.jsonl`
- Modify: `server/src/test/java/dm/replay/ReplayRunnerTest.java`
- Modify: `AGENTS.md`

- [ ] **Step 1: Play a session**

Run: `cd server && ./gradlew run` with a real `VENICE_API_KEY`, `DM_MODEL_TOOLS=qwen3-next-80b`,
`DM_MODEL_PROSE=gemini-3-8-flash` and `DM_REASONING_EFFORT_PROSE=low` — the split M2 was signed on,
so a difference in this session is a difference in M3 and not in the model pick.

The script has to satisfy criteria 1–3, and they are not satisfiable by wandering:

- Cross into the gallery, and **spend at least seven typed turns there** before going back.
  `WINDOW_TURNS = 6`, so a return before that tests the window rather than the facts.
- While in the gallery, **find the niche** and let the DM assert a fact about it. That is the
  concrete referent criterion 3 asks for.
- Go back to the crypt. Spend at least seven turns there — mention the tallies, the trough, the
  sarcophagus — then return to the gallery and ask about the niche and its tokens.
- Kill Vessk in the crypt, or leave him alive, and either way check on the way back that he is
  where you left him.

Watch for and note, without stopping to fix:
- room 1's details described on room 2's walls;
- either room re-introduced as new on return;
- the fighter's token moving when you did not say you moved;
- narration that says you went through a door before the board changed.

- [ ] **Step 2: Capture it**

```bash
cd /Users/hampton/Projects/emberdelve && ls -t server/sessions/*.jsonl | head -1
mkdir -p docs/evidence
cp "$(ls -t server/sessions/*.jsonl | head -1)" docs/evidence/session-m3-traversal.jsonl
```

Verify it replays before writing anything about it:

```bash
cd server && ./gradlew run --args="--replay ../docs/evidence/session-m3-traversal.jsonl"
```

Expected: `replayed N events: identical`. If it diverges, that is the finding — the plan does not
proceed to a signed gate over a divergence.

- [ ] **Step 3: Pin the session in the suite**

Add to `server/src/test/java/dm/replay/ReplayRunnerTest.java`:

```java
    @Test
    @DisplayName("the played multi-room session replays, and comes back to what it left behind")
    void theGateSessionReplays() {
        var session = Path.of("../docs/evidence/session-m3-traversal.jsonl");

        var result = ReplayRunner.replay(session);
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());

        var state = EventLog.load(session).state();
        assertTrue(state.visitedRoomIds().containsAll(java.util.Set.of("crypt", "gallery")),
                "the gate session must have been in both rooms");
        assertTrue(EventLog.load(session).events().stream()
                        .filter(Event.PartyMoved.class::isInstance).count() >= 3,
                "at least one crossing, one return, and one crossing back");
    }
```

`WorldState` needs a `visitedRoomIds()` accessor — it is a record component from Task 4, so it
already has one.

- [ ] **Step 4: Write the evaluation**

Create `docs/m3-evaluation.md`, following `docs/m2-evaluation.md`'s structure: what was under test,
the criteria table, what happened turn by turn, consistency, then a signed verdict and a "carried
out of M3" section.

The criteria table is spec §10's five, each marked with what actually happened:

| # | Criterion | Status |
|---|---|---|
| 1 | Crosses thresholds repeatedly, returns at least twice | |
| 2 | A return only counts after ≥7 turns elsewhere | |
| 3 | "As you left it" names a specific thing in a specific state | |
| 4 | Nothing the DM says contradicts the room it is in, in either direction | |
| 5 | The session replays offline, across rooms | |

Record, without grading: bleed incidents with the turn numbers, unnecessary `move_entity` calls
against the figure counted in Task 10 step 1, whether the aside-starts-a-fight behaviour from
m2-evaluation §8 recurred now that the mechanics phase has a second loud verb, and whether the
dark neighbour read as a dungeon.

Sign it PASS or FAIL. A FAIL is a finding and a follow-up plan, not a reason to loosen a criterion.

- [ ] **Step 5: Bring AGENTS.md up to date**

The rules file describes a one-room game. Change:

- The header: current milestone M3, with its gate question and verdict, and the milestone table
  gaining an M3 row. M1 stays open — the generator plan answers it.
- **Invariant #10's tool list: "exactly these five" becomes seven.** `use_exit` and `move_entity`,
  with the phase rule from spec §6d stated in one line, since it is the thing a future session
  will otherwise get wrong.
- The shortcuts table: "one room" is retired. Add what is still hardcoded — two authored rooms, no
  generated dungeon, no party splits, no fleeing.
- The commands block: `./gradlew recordFixture`, and what to do after a schema bump.
- Known-unfixed: M3's carry-outs, and the §12a finding that a generated room has nothing to find.
- The stale-plan warning about `2026-08-21-m1-dungeon-navigation.md` — its traversal half is now
  superseded by this plan. Its `LayoutGenerator` and `ExitPlacer` tasks still lift.

- [ ] **Step 6: Run everything one more time**

Run: `cd server && ./gradlew test`
Expected: PASS, gate session included.

Run: `cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add docs/m3-evaluation.md docs/evidence/session-m3-traversal.jsonl \
        server/src/test/java/dm/replay/ReplayRunnerTest.java AGENTS.md
git commit -m "Sign the traversal gate against a session that left a room, came back, and found it."
```

---

## Order of work

Stage 1 (Tasks 1–5) is invisible and is most of the risk. Task 8 is the first thing a player can
walk. Tasks 7 and 9 are the only ones that end red, and Task 12 clears both — that is called out in
their commit messages so a bisect does not read as a mystery.

| Stage | Tasks | Ends with |
|---|---|---|
| 1 — the fold gains a dimension | 1–5 | Room-scoped state, nothing observable |
| 2 — the engine gains rooms | 6–9 | A door you can click, cutting between rooms |
| 3 — the DM | 10–11 | The DM can leave a room, and knows it has been here before |
| 4 — content and rendering | 12–14 | The gallery, and the dark room through the doorway |
| 5 — the gate | 15 | A signed evaluation and a replayed session |
