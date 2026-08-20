# M1 Room Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot the game into a procedurally generated, LLM-dressed room and play M0's loop in it, with every generated room validated for spatial legality before it reaches a client.

**Architecture:** A seeded, deterministic Java generator builds a room's shape and prop placement from a content-as-data kit. A spatial validator rejects illegal worlds. One LLM pass then *dresses* the room — deciding what it is and describing it — choosing only from prop ids the generator already placed. The result is adapted to the existing `RoomDefinition`, so `GameEngine`, `CombatEngine` and the client are untouched.

**Tech Stack:** Java 25 (records, sealed interfaces, pattern matching), Jackson 2.18, JUnit 5.12, Gradle Kotlin DSL. No new dependencies.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md`. Where this plan and the spec disagree, the spec wins.
- **Invariant #1 — the server is authoritative.** The client never computes a roll, a hit, a legal move, or a death.
- **Invariant #7 — all LLM-facing enums are closed and validated server-side.** No free-form string from the model reaches the engine.
- **Invariant #9 — modern Java only.** Records, sealed interfaces, pattern matching, virtual threads. No Spring, no `AbstractXFactory`, no mutable POJOs with getters and setters.
- **New invariant (spec §6) — generated content is validated for spatial legality, not just enum membership.** A prop in an exit is a valid enum and an invalid world.
- **Determinism:** everything except `RoomDresser` is a pure function of `(seed, kit)`. Same seed, same room, forever.
- **Out of scope for this plan:** multi-room layout graphs, exits, navigation between rooms, free movement, position-as-DM-context, Postgres, the 5e rules engine, party beyond one fighter. Those are the M1 navigation plan (see "Not in this plan" below).
- **Test command:** `cd server && ./gradlew test`
- **Run command:** `cd server && ./gradlew run`

## Not in this plan

The spec covers more than one deliverable. This plan is **one generated room you can play in**. A second plan covers **a dungeon of them, walkable between**: `DungeonLayout`, `Exit`, scene swaps, multi-room state, free movement and position-as-context. Splitting here keeps each plan independently shippable — at the end of this one you boot into a generated room and play; nothing is half-wired.

## File Structure

| File | Responsibility |
|---|---|
| `server/src/main/java/dm/content/KitDefinition.java` | A kit as it appears on disk: tile palette and prop catalog |
| `server/src/main/resources/content/kits/crypt.json` | The crypt kit — the closed set the generator draws from |
| `server/src/main/java/dm/content/ContentLoader.java` | *(modify)* add `kit(String)` |
| `server/src/main/java/dm/generate/GenRandom.java` | Seeded RNG. The only source of randomness in generation |
| `server/src/main/java/dm/generate/RoomShape.java` | Dimensions, floor, wall, lighting — chosen from the kit |
| `server/src/main/java/dm/generate/ShapeGenerator.java` | `(seed, kit) → RoomShape` |
| `server/src/main/java/dm/generate/PropPlacer.java` | `(seed, kit, shape) → List<Prop>` |
| `server/src/main/java/dm/generate/SpatialValidator.java` | The new invariant. Rejects illegal worlds |
| `server/src/main/java/dm/generate/GeneratedRoom.java` | Generator output, and its adapter to `RoomDefinition` |
| `server/src/main/java/dm/generate/RoomGenerator.java` | Composes the above into one entry point |
| `server/src/main/java/dm/generate/RoomDumper.java` | ASCII rendering of a generated room, for the terminal |
| `server/src/main/java/dm/generate/RoomSource.java` | Seam: hand-authored room, or generated one |
| `server/src/main/java/dm/generate/RoomDresser.java` | The single LLM pass. Names and describes the room |
| `server/src/main/java/dm/generate/Dressing.java` | The dresser's validated output |
| `server/src/main/java/dm/App.java` | *(modify)* `--generate <seed>` flag, wire `RoomSource` |
| `server/src/main/java/dm/engine/GameEngine.java` | *(modify)* take a `RoomSource` instead of loading `crypt` directly |
| `server/src/test/java/dm/generate/*` | One test class per unit above |
| `server/src/test/java/dm/ScriptedDmClient.java` | Test double for `DmClient` — returns canned turns, no network |

---

### Task 1: The kit, as content-as-data

**Files:**
- Create: `server/src/main/resources/content/kits/crypt.json`
- Create: `server/src/main/java/dm/content/KitDefinition.java`
- Modify: `server/src/main/java/dm/content/ContentLoader.java`
- Test: `server/src/test/java/dm/content/KitDefinitionTest.java`

**Interfaces:**
- Consumes: `PropType`, `FloorType`, `WallType`, `LightingPreset` (existing enums in `dm.model`)
- Produces: `KitDefinition(String kitId, List<FloorType> floors, List<WallType> walls, List<LightingPreset> lightings, IntRange size, List<PropEntry> props)`; `KitDefinition.IntRange(int min, int max)`; `KitDefinition.PropEntry(PropType type, int minCount, int maxCount, boolean unique)`; `ContentLoader.kit(String kitId)`

- [ ] **Step 1: Write the failing test**

```java
package dm.content;

import dm.model.FloorType;
import dm.model.PropType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kit is the closed set the generator draws from. If a prop type reaches the generator
 * that has no mesh, the room renders with a hole in it — so the kit is validated at load.
 */
class KitDefinitionTest {

    @Test
    @DisplayName("the crypt kit loads and exposes its palette")
    void loads() {
        var kit = new ContentLoader().kit("crypt");

        assertEquals("crypt", kit.kitId());
        assertTrue(kit.floors().contains(FloorType.CRACKED_STONE));
        assertTrue(kit.size().min() >= 8, "a room smaller than 8 squares is not a fight");
        assertTrue(kit.size().max() <= 20);
    }

    @Test
    @DisplayName("every prop entry names a type the renderer has a mesh for")
    void propTypesAreClosed() {
        var kit = new ContentLoader().kit("crypt");

        assertFalse(kit.props().isEmpty());
        for (var entry : kit.props()) {
            assertNotNull(entry.type());
            assertTrue(entry.minCount() >= 0);
            assertTrue(entry.maxCount() >= entry.minCount());
        }
    }

    @Test
    @DisplayName("the kit carries a sarcophagus, because M0's script needs one")
    void carriesSarcophagus() {
        var kit = new ContentLoader().kit("crypt");

        assertTrue(kit.props().stream().anyMatch(p -> p.type() == PropType.SARCOPHAGUS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.content.KitDefinitionTest'`
Expected: FAIL — compilation error, `cannot find symbol: method kit(String)`

- [ ] **Step 3: Write minimal implementation**

Create `server/src/main/java/dm/content/KitDefinition.java`:

```java
package dm.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.PropType;
import dm.model.WallType;

import java.util.List;

/**
 * A generator kit as it appears on disk: the closed set of tiles and props a generated room
 * may draw from.
 *
 * <p>This is the first real content-as-data schema in the project and its shape is meant to
 * outlive M1 — the rules engine will load monsters and spells the same way. It is deliberately
 * a palette and not a template: the kit says what <em>may</em> appear and in what quantity,
 * and the generator decides what does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KitDefinition(
        String kitId,
        List<FloorType> floors,
        List<WallType> walls,
        List<LightingPreset> lightings,
        IntRange size,
        List<PropEntry> props
) {

    /** Inclusive on both ends. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntRange(int min, int max) {
    }

    /**
     * How many of a prop type a room may hold.
     *
     * @param unique whether a room may hold at most one, regardless of {@code maxCount} — a
     *               chamber with three sarcophagi reads as a warehouse, not a crypt
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropEntry(PropType type, int minCount, int maxCount, boolean unique) {
    }
}
```

Create `server/src/main/resources/content/kits/crypt.json`:

```json
{
  "kitId": "crypt",
  "floors": ["STONE", "CRACKED_STONE", "TILED"],
  "walls": ["STONE", "CARVED"],
  "lightings": ["TORCHLIT", "DIM", "DARK"],
  "size": { "min": 10, "max": 16 },
  "props": [
    { "type": "SARCOPHAGUS", "minCount": 1, "maxCount": 1, "unique": true },
    { "type": "BRAZIER",     "minCount": 0, "maxCount": 4, "unique": false },
    { "type": "PILLAR",      "minCount": 0, "maxCount": 4, "unique": false },
    { "type": "RUBBLE",      "minCount": 0, "maxCount": 3, "unique": false },
    { "type": "ALCOVE",      "minCount": 0, "maxCount": 2, "unique": false }
  ]
}
```

Add to `server/src/main/java/dm/content/ContentLoader.java`, directly after the `room` method:

```java
    public KitDefinition kit(String kitId) {
        return read("/content/kits/" + kitId + ".json", KitDefinition.class);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.content.KitDefinitionTest'`
Expected: PASS, 3 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/content/KitDefinition.java \
        server/src/main/java/dm/content/ContentLoader.java \
        server/src/main/resources/content/kits/crypt.json \
        server/src/test/java/dm/content/KitDefinitionTest.java
git commit -m "The generator gets a palette to draw from"
```

---

### Task 2: Seeded randomness

**Files:**
- Create: `server/src/main/java/dm/generate/GenRandom.java`
- Test: `server/src/test/java/dm/generate/GenRandomTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `GenRandom(long seed)`; `int between(int minInclusive, int maxInclusive)`; `<T> T pick(List<T> options)`; `boolean chance(double probability)`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The only source of randomness in generation.
 *
 * <p>It exists as its own type rather than a bare {@link java.util.Random} for the same reason
 * {@code DiceRoller} is injected: a generator seeded in one place and reproducible on demand is
 * testable, and one that reaches for a static random is not.
 */
class GenRandomTest {

    @Test
    @DisplayName("the same seed produces the same sequence")
    void deterministic() {
        var a = new GenRandom(42);
        var b = new GenRandom(42);

        for (int i = 0; i < 50; i++) {
            assertEquals(a.between(0, 1000), b.between(0, 1000));
        }
    }

    @Test
    @DisplayName("different seeds diverge")
    void differentSeeds() {
        var a = new GenRandom(1);
        var b = new GenRandom(2);

        boolean anyDifference = false;
        for (int i = 0; i < 50; i++) {
            if (a.between(0, 1000) != b.between(0, 1000)) {
                anyDifference = true;
            }
        }
        assertTrue(anyDifference, "two seeds produced identical sequences");
    }

    @Test
    @DisplayName("between is inclusive on both ends and never leaves the range")
    void betweenIsInclusive() {
        var random = new GenRandom(7);

        for (int i = 0; i < 500; i++) {
            int value = random.between(3, 5);
            assertTrue(value >= 3 && value <= 5, "out of range: " + value);
        }
        assertEquals(4, new GenRandom(7).between(4, 4), "a single-value range returns that value");
    }

    @Test
    @DisplayName("pick returns a member of the list")
    void pickIsAMember() {
        var random = new GenRandom(9);
        var options = List.of("a", "b", "c");

        for (int i = 0; i < 50; i++) {
            assertTrue(options.contains(random.pick(options)));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.GenRandomTest'`
Expected: FAIL — compilation error, `package dm.generate does not exist`

- [ ] **Step 3: Write minimal implementation**

```java
package dm.generate;

import java.util.List;
import java.util.Random;

/**
 * The single source of randomness in generation.
 *
 * <p>Wraps {@link Random} rather than exposing it, so that every generator draws through one
 * seeded stream and a room is reproducible from its seed alone. This is the same argument as
 * the injected {@code DiceRoller}: randomness that can be pinned is randomness that can be
 * tested, and a static call in a corner of a generator quietly makes a whole milestone
 * untestable.
 */
public final class GenRandom {

    private final Random random;

    public GenRandom(long seed) {
        this.random = new Random(seed);
    }

    /** Inclusive on both ends, which is how room dimensions and prop counts are written. */
    public int between(int minInclusive, int maxInclusive) {
        if (maxInclusive < minInclusive) {
            throw new IllegalArgumentException(
                    "Empty range: " + minInclusive + ".." + maxInclusive);
        }
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    public <T> T pick(List<T> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick from an empty list");
        }
        return options.get(random.nextInt(options.size()));
    }

    public boolean chance(double probability) {
        return random.nextDouble() < probability;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.GenRandomTest'`
Expected: PASS, 4 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/GenRandom.java \
        server/src/test/java/dm/generate/GenRandomTest.java
git commit -m "One seeded stream, so a room can be asked for twice"
```

---

### Task 3: The room's shape

**Files:**
- Create: `server/src/main/java/dm/generate/RoomShape.java`
- Create: `server/src/main/java/dm/generate/ShapeGenerator.java`
- Test: `server/src/test/java/dm/generate/ShapeGeneratorTest.java`

**Interfaces:**
- Consumes: `GenRandom`, `KitDefinition`
- Produces: `RoomShape(int width, int height, FloorType floorType, WallType wallType, LightingPreset lighting)`; `ShapeGenerator.generate(GenRandom random, KitDefinition kit)` returning `RoomShape`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShapeGeneratorTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("the same seed produces the same shape")
    void deterministic() {
        var kit = CONTENT.kit("crypt");

        var first = ShapeGenerator.generate(new GenRandom(123), kit);
        var second = ShapeGenerator.generate(new GenRandom(123), kit);

        assertEquals(first, second);
    }

    @Test
    @DisplayName("dimensions stay inside the kit's range")
    void withinKitRange() {
        var kit = CONTENT.kit("crypt");

        for (long seed = 0; seed < 100; seed++) {
            var shape = ShapeGenerator.generate(new GenRandom(seed), kit);

            assertTrue(shape.width() >= kit.size().min(), "too narrow at seed " + seed);
            assertTrue(shape.width() <= kit.size().max(), "too wide at seed " + seed);
            assertTrue(shape.height() >= kit.size().min(), "too short at seed " + seed);
            assertTrue(shape.height() <= kit.size().max(), "too tall at seed " + seed);
        }
    }

    @Test
    @DisplayName("every surface comes from the kit's palette")
    void surfacesComeFromKit() {
        var kit = CONTENT.kit("crypt");

        for (long seed = 0; seed < 100; seed++) {
            var shape = ShapeGenerator.generate(new GenRandom(seed), kit);

            assertTrue(kit.floors().contains(shape.floorType()));
            assertTrue(kit.walls().contains(shape.wallType()));
            assertTrue(kit.lightings().contains(shape.lighting()));
        }
    }

    @Test
    @DisplayName("different seeds produce different rooms")
    void seedsVary() {
        var kit = CONTENT.kit("crypt");

        var shapes = new java.util.HashSet<RoomShape>();
        for (long seed = 0; seed < 50; seed++) {
            shapes.add(ShapeGenerator.generate(new GenRandom(seed), kit));
        }
        assertTrue(shapes.size() > 5, "50 seeds produced only " + shapes.size() + " shapes");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.ShapeGeneratorTest'`
Expected: FAIL — compilation error, `cannot find symbol: class ShapeGenerator`

- [ ] **Step 3: Write minimal implementation**

Create `server/src/main/java/dm/generate/RoomShape.java`:

```java
package dm.generate;

import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.WallType;

/**
 * A room's bare dimensions and surfaces, before anything is placed in it.
 *
 * <p>Separate from {@link GeneratedRoom} because prop placement needs somewhere to place things
 * <em>onto</em>, and a half-built room record with a mutable prop list would be the obvious
 * alternative and the wrong one.
 */
public record RoomShape(
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        LightingPreset lighting
) {
}
```

Create `server/src/main/java/dm/generate/ShapeGenerator.java`:

```java
package dm.generate;

import dm.content.KitDefinition;

/**
 * Chooses a room's dimensions and surfaces from the kit's palette.
 *
 * <p>Width and height are drawn independently, so rooms are rectangles rather than squares —
 * a room that is always as deep as it is wide reads as a generated grid, which is the exact
 * impression this milestone is trying to avoid.
 */
public final class ShapeGenerator {

    private ShapeGenerator() {
    }

    public static RoomShape generate(GenRandom random, KitDefinition kit) {
        return new RoomShape(
                random.between(kit.size().min(), kit.size().max()),
                random.between(kit.size().min(), kit.size().max()),
                random.pick(kit.floors()),
                random.pick(kit.walls()),
                random.pick(kit.lightings()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.ShapeGeneratorTest'`
Expected: PASS, 4 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/RoomShape.java \
        server/src/main/java/dm/generate/ShapeGenerator.java \
        server/src/test/java/dm/generate/ShapeGeneratorTest.java
git commit -m "A room before anything is in it"
```

---

### Task 4: Placing the props

**Files:**
- Create: `server/src/main/java/dm/generate/PropPlacer.java`
- Test: `server/src/test/java/dm/generate/PropPlacerTest.java`

**Interfaces:**
- Consumes: `GenRandom`, `KitDefinition`, `RoomShape`, `Prop`, `Square` (existing)
- Produces: `PropPlacer.place(GenRandom random, KitDefinition kit, RoomShape shape, Square partyStart)` returning `List<Prop>`

Prop ids are `type-lowercase + "-" + ordinal`, e.g. `sarcophagus-0`, `brazier-1`. Later tasks depend on ids being stable for a given seed.

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.content.ContentLoader;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropPlacerTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final Square START = new Square(1, 1);

    private static List<Prop> place(long seed) {
        var kit = CONTENT.kit("crypt");
        var random = new GenRandom(seed);
        var shape = ShapeGenerator.generate(random, kit);
        return PropPlacer.place(random, kit, shape, START);
    }

    @Test
    @DisplayName("the same seed places the same props in the same squares")
    void deterministic() {
        assertEquals(place(55), place(55));
    }

    @Test
    @DisplayName("no two props share a square")
    void noOverlap() {
        for (long seed = 0; seed < 100; seed++) {
            var squares = new HashSet<Square>();
            for (var prop : place(seed)) {
                assertTrue(squares.add(new Square(prop.x(), prop.y())),
                        "two props share a square at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("nothing solid is placed on the party's starting square")
    void startSquareIsClear() {
        for (long seed = 0; seed < 100; seed++) {
            for (var prop : place(seed)) {
                boolean onStart = prop.x() == START.x() && prop.y() == START.y();
                assertFalse(onStart && prop.type().blocksMovement(),
                        "a solid prop stands on the start square at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("every prop stands inside the room, never in a wall")
    void insideTheRoom() {
        var kit = CONTENT.kit("crypt");
        for (long seed = 0; seed < 100; seed++) {
            var random = new GenRandom(seed);
            var shape = ShapeGenerator.generate(random, kit);
            for (var prop : PropPlacer.place(random, kit, shape, START)) {
                assertTrue(prop.x() >= 0 && prop.x() < shape.width(), "off-grid x at seed " + seed);
                assertTrue(prop.y() >= 0 && prop.y() < shape.height(), "off-grid y at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("counts respect the kit, and a unique prop appears at most once")
    void respectsKitCounts() {
        var kit = CONTENT.kit("crypt");
        for (long seed = 0; seed < 100; seed++) {
            var props = place(seed);
            for (var entry : kit.props()) {
                long count = props.stream().filter(p -> p.type() == entry.type()).count();
                assertTrue(count >= entry.minCount(),
                        entry.type() + " below minimum at seed " + seed);
                assertTrue(count <= entry.maxCount(),
                        entry.type() + " above maximum at seed " + seed);
                if (entry.unique()) {
                    assertTrue(count <= 1, entry.type() + " is unique but appeared " + count);
                }
            }
        }
    }

    @Test
    @DisplayName("ids are unique, because reveal_prop addresses props by id")
    void idsAreUnique() {
        for (long seed = 0; seed < 100; seed++) {
            var ids = new HashSet<String>();
            for (var prop : place(seed)) {
                assertTrue(ids.add(prop.id()), "duplicate prop id at seed " + seed);
            }
        }
    }

    @Test
    @DisplayName("the sarcophagus is always placed, since M0's script opens one")
    void sarcophagusAlwaysPresent() {
        for (long seed = 0; seed < 100; seed++) {
            assertTrue(place(seed).stream().anyMatch(p -> p.type() == PropType.SARCOPHAGUS),
                    "no sarcophagus at seed " + seed);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.PropPlacerTest'`
Expected: FAIL — compilation error, `cannot find symbol: class PropPlacer`

- [ ] **Step 3: Write minimal implementation**

```java
package dm.generate;

import dm.content.KitDefinition;
import dm.model.Prop;
import dm.model.Square;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scatters the kit's props across a room's floor.
 *
 * <p>Placement is rejection sampling rather than anything cleverer: pick a square, take it if it
 * is free, try again if it is not. With a dozen props on a hundred squares the collision rate is
 * low enough that this terminates quickly, and a bounded attempt count means it terminates at
 * all. Something smarter — symmetry, clustering, rooms that read as designed — is exactly the
 * kind of improvement to make once there is a generated room to look at and judge, and exactly
 * the kind to avoid inventing before then.
 *
 * <p>The party's starting square is kept clear of anything solid. A player who spawns inside a
 * pillar is a bug the renderer will show and the engine will not.
 */
public final class PropPlacer {

    /** Enough tries that a full room gives up rather than spinning. */
    private static final int ATTEMPTS_PER_PROP = 40;

    private PropPlacer() {
    }

    public static List<Prop> place(GenRandom random, KitDefinition kit, RoomShape shape,
                                   Square partyStart) {
        var placed = new ArrayList<Prop>();
        var taken = new HashSet<Square>();

        for (var entry : kit.props()) {
            int max = entry.unique() ? Math.min(1, entry.maxCount()) : entry.maxCount();
            int count = random.between(entry.minCount(), max);

            for (int i = 0; i < count; i++) {
                var square = findFreeSquare(random, shape, taken, partyStart, entry);
                if (square == null) {
                    continue;
                }
                taken.add(square);
                placed.add(new Prop(
                        entry.type().name().toLowerCase() + "-" + i,
                        entry.type(),
                        square.x(),
                        square.y(),
                        random.pick(List.of(0, 90, 180, 270)),
                        false));
            }
        }
        return List.copyOf(placed);
    }

    private static Square findFreeSquare(GenRandom random, RoomShape shape, Set<Square> taken,
                                         Square partyStart, KitDefinition.PropEntry entry) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_PROP; attempt++) {
            var square = new Square(
                    random.between(0, shape.width() - 1),
                    random.between(0, shape.height() - 1));

            if (taken.contains(square)) {
                continue;
            }
            if (square.equals(partyStart) && entry.type().blocksMovement()) {
                continue;
            }
            return square;
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.PropPlacerTest'`
Expected: PASS, 7 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/PropPlacer.java \
        server/src/test/java/dm/generate/PropPlacerTest.java
git commit -m "Scatter the kit across the floor, and keep off the doorstep"
```

---

### Task 5: The spatial validator

This is spec §6 — the milestone's new invariant. It exists before the first generated room reaches a client, not after the first bad one does.

**Files:**
- Create: `server/src/main/java/dm/generate/SpatialValidator.java`
- Test: `server/src/test/java/dm/generate/SpatialValidatorTest.java`

**Interfaces:**
- Consumes: `RoomShape`, `Prop`, `Square`
- Produces: `SpatialValidator.check(RoomShape shape, List<Prop> props, Square partyStart)` returning `List<String>` — empty means legal, each entry names one violation

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.model.Prop;
import dm.model.PropType;
import dm.model.Square;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §6: generated content is validated for spatial legality, not just enum membership.
 *
 * <p>A prop in a doorway is a valid enum and an invalid world. Every prop here has a legal type
 * and legal coordinates; what is wrong with these rooms is the arrangement.
 */
class SpatialValidatorTest {

    private static final RoomShape SHAPE = new RoomShape(
            6, 6, dm.model.FloorType.STONE, dm.model.WallType.STONE,
            dm.model.LightingPreset.TORCHLIT);
    private static final Square START = new Square(0, 0);

    private static Prop pillar(String id, int x, int y) {
        return new Prop(id, PropType.PILLAR, x, y, 0, false);
    }

    @Test
    @DisplayName("a plain room passes")
    void legalRoomPasses() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-1", 4, 4));

        assertEquals(List.of(), SpatialValidator.check(SHAPE, props, START));
    }

    @Test
    @DisplayName("two props on one square is a violation")
    void overlapRejected() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-1", 2, 2));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("2,2"), violations.get(0));
    }

    @Test
    @DisplayName("a prop off the grid is a violation")
    void offGridRejected() {
        var props = List.of(pillar("pillar-0", 6, 2));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("pillar-0"), violations.get(0));
    }

    @Test
    @DisplayName("a solid prop on the party's start square is a violation")
    void blockedStartRejected() {
        var props = List.of(pillar("pillar-0", 0, 0));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("start")), violations.toString());
    }

    @Test
    @DisplayName("a walled-off corner is a violation, because the player can never reach it")
    void unreachableRegionRejected() {
        // Fences off (5,5) behind a diagonal-proof wall of pillars.
        var props = List.of(
                pillar("pillar-0", 4, 5),
                pillar("pillar-1", 4, 4),
                pillar("pillar-2", 5, 4));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertTrue(violations.stream().anyMatch(v -> v.contains("unreachable")),
                violations.toString());
    }

    @Test
    @DisplayName("duplicate ids are a violation, because reveal_prop addresses by id")
    void duplicateIdsRejected() {
        var props = List.of(pillar("pillar-0", 2, 2), pillar("pillar-0", 3, 3));

        var violations = SpatialValidator.check(SHAPE, props, START);

        assertTrue(violations.stream().anyMatch(v -> v.contains("pillar-0")), violations.toString());
    }

    @Test
    @DisplayName("every generated room from the real kit is legal")
    void generatedRoomsAreLegal() {
        var kit = new dm.content.ContentLoader().kit("crypt");

        for (long seed = 0; seed < 200; seed++) {
            var random = new GenRandom(seed);
            var shape = ShapeGenerator.generate(random, kit);
            var props = PropPlacer.place(random, kit, shape, START);

            assertEquals(List.of(), SpatialValidator.check(shape, props, START),
                    "seed " + seed + " generated an illegal room");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.SpatialValidatorTest'`
Expected: FAIL — compilation error, `cannot find symbol: class SpatialValidator`

- [ ] **Step 3: Write minimal implementation**

```java
package dm.generate;

import dm.model.Prop;
import dm.model.Square;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec §6. Closed enums are necessary and no longer sufficient.
 *
 * <p>Every prop the generator emits already has a legal type and legal coordinates, because the
 * kit is a closed set and the placer draws inside the bounds. What this checks is the thing a
 * type system cannot: whether the <em>arrangement</em> makes a world worth standing in. A prop
 * in a doorway is a valid enum and an invalid room, and a walled-off corner is a promise the
 * renderer makes that the engine will refuse to keep.
 *
 * <p>Returns every violation rather than throwing on the first, so a bad seed produces one
 * legible report instead of a game of whack-a-mole.
 */
public final class SpatialValidator {

    private SpatialValidator() {
    }

    public static List<String> check(RoomShape shape, List<Prop> props, Square partyStart) {
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

        violations.addAll(unreachable(shape, props, partyStart));
        return List.copyOf(violations);
    }

    /**
     * Every open square must be walkable from the party's start.
     *
     * <p>Chebyshev flood fill, matching {@code Entity.isAdjacentTo} and the movement rules —
     * two distance metrics in one game is how "why can it not walk there" bugs start.
     */
    private static List<String> unreachable(RoomShape shape, List<Prop> props, Square partyStart) {
        var blocked = new HashSet<Square>();
        for (var prop : props) {
            if (prop.type().blocksMovement()) {
                blocked.add(new Square(prop.x(), prop.y()));
            }
        }

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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.SpatialValidatorTest'`
Expected: PASS, 7 tests

If `generatedRoomsAreLegal` fails, the placer is producing illegal rooms and **the placer is what changes** — never the validator. The likely cause is rubble fencing off a corner; the fix is for `RoomGenerator` (Task 6) to reject and reseed.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/SpatialValidator.java \
        server/src/test/java/dm/generate/SpatialValidatorTest.java
git commit -m "A prop in a doorway is a valid enum and an invalid world"
```

---

### Task 6: One entry point, and a room the engine can use

**Files:**
- Create: `server/src/main/java/dm/generate/GeneratedRoom.java`
- Create: `server/src/main/java/dm/generate/RoomGenerator.java`
- Test: `server/src/test/java/dm/generate/RoomGeneratorTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5, plus `RoomDefinition` (existing)
- Produces: `GeneratedRoom(String roomId, RoomShape shape, List<Prop> props, Square partyStart, Square goblinSpawn)` with `RoomDefinition toRoomDefinition()`; `RoomGenerator(ContentLoader content)` with `GeneratedRoom generate(String kitId, long seed)`

`toRoomDefinition()` fills `DmNotes` with placeholders that Task 8's dresser replaces. `RoomGenerator` reseeds on a validation failure so an illegal arrangement never escapes.

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomGeneratorTest {

    private static final RoomGenerator GENERATOR = new RoomGenerator(new ContentLoader());

    @Test
    @DisplayName("the same seed generates the same room, every time")
    void deterministic() {
        assertEquals(GENERATOR.generate("crypt", 99), GENERATOR.generate("crypt", 99));
    }

    @Test
    @DisplayName("every generated room is spatially legal")
    void alwaysLegal() {
        for (long seed = 0; seed < 200; seed++) {
            var room = GENERATOR.generate("crypt", seed);

            assertEquals(List.of(),
                    SpatialValidator.check(room.shape(), room.props(), room.partyStart()),
                    "seed " + seed + " escaped validation");
        }
    }

    @Test
    @DisplayName("the party and the goblin do not start on the same square")
    void startsAreDistinct() {
        for (long seed = 0; seed < 200; seed++) {
            var room = GENERATOR.generate("crypt", seed);

            assertNotEquals(room.partyStart(), room.goblinSpawn(), "seed " + seed);
        }
    }

    @Test
    @DisplayName("it converts to a RoomDefinition the engine already understands")
    void convertsToRoomDefinition() {
        var room = GENERATOR.generate("crypt", 4);
        var definition = room.toRoomDefinition();

        assertEquals(room.roomId(), definition.roomId());
        assertEquals(room.shape().width(), definition.width());
        assertEquals(room.props().size(), definition.props().size());
        assertNotNull(definition.dmNotes());
        assertEquals(1, definition.startPositions().party().size());
    }

    @Test
    @DisplayName("obstruction survives the conversion, so combat legality still works")
    void obstructionSurvives() {
        var room = GENERATOR.generate("crypt", 11);
        var definition = room.toRoomDefinition();

        var solid = room.props().stream()
                .filter(p -> p.type().blocksMovement())
                .findFirst()
                .orElseThrow();

        assertTrue(definition.isObstructed(solid.x(), solid.y()));
        assertFalse(definition.isObstructed(room.partyStart().x(), room.partyStart().y()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomGeneratorTest'`
Expected: FAIL — compilation error, `cannot find symbol: class RoomGenerator`

- [ ] **Step 3: Write minimal implementation**

Create `server/src/main/java/dm/generate/GeneratedRoom.java`:

```java
package dm.generate;

import dm.content.RoomDefinition;
import dm.model.Prop;
import dm.model.Square;

import java.util.List;

/**
 * A generated room, and its adapter to the hand-authored shape the engine already reads.
 *
 * <p>Adapting rather than replacing is deliberate. {@code GameEngine}, {@code CombatEngine} and
 * the whole client speak {@link RoomDefinition}; a generated room that arrives as one is a
 * milestone that changes the generator and nothing else. The alternative — teaching every
 * consumer about a second room type — would spend M1's budget on plumbing.
 *
 * @param partyStart  where the fighter stands when the session opens
 * @param goblinSpawn where a hostile arrives, when one does
 */
public record GeneratedRoom(
        String roomId,
        RoomShape shape,
        List<Prop> props,
        Square partyStart,
        Square goblinSpawn
) {

    /** Replaced by {@code RoomDresser} before a player ever reads it. */
    private static final String UNDRESSED = "This room has not been dressed yet.";

    public RoomDefinition toRoomDefinition() {
        var definitions = props.stream()
                .map(p -> new RoomDefinition.PropDefinition(
                        p.id(), p.type(), p.x(), p.y(), p.rotation(), p.hidden(),
                        UNDRESSED, null, null))
                .toList();

        return new RoomDefinition(
                roomId,
                "An Unnamed Chamber",
                shape.width(),
                shape.height(),
                shape.floorType(),
                shape.wallType(),
                shape.lighting(),
                definitions,
                new RoomDefinition.StartPositions(
                        List.of(new RoomDefinition.Point(partyStart.x(), partyStart.y())),
                        new RoomDefinition.Point(goblinSpawn.x(), goblinSpawn.y())),
                new RoomDefinition.DmNotes(UNDRESSED, UNDRESSED, UNDRESSED, UNDRESSED, UNDRESSED));
    }
}
```

Create `server/src/main/java/dm/generate/RoomGenerator.java`:

```java
package dm.generate;

import dm.content.ContentLoader;
import dm.model.Square;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The whole deterministic half of generation, behind one call.
 *
 * <p>An illegal arrangement is reseeded rather than repaired. Repair means writing a second
 * placer that undoes the first one's decisions, and the two would disagree the moment either
 * changed; a fresh seed is a handful of microseconds and cannot introduce a case the validator
 * has not already seen.
 */
public final class RoomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RoomGenerator.class);

    /** A kit that cannot produce a legal room in this many tries is a broken kit, not bad luck. */
    private static final int MAX_ATTEMPTS = 50;

    private final ContentLoader content;

    public RoomGenerator(ContentLoader content) {
        this.content = content;
    }

    public GeneratedRoom generate(String kitId, long seed) {
        var kit = content.kit(kitId);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long attemptSeed = seed + attempt;
            var random = new GenRandom(attemptSeed);
            var shape = ShapeGenerator.generate(random, kit);

            var partyStart = new Square(shape.width() / 2, 0);
            var props = PropPlacer.place(random, kit, shape, partyStart);

            var violations = SpatialValidator.check(shape, props, partyStart);
            if (!violations.isEmpty()) {
                log.debug("seed {} rejected: {}", attemptSeed, violations);
                continue;
            }

            var goblinSpawn = spawnAwayFrom(shape, props, partyStart);
            if (goblinSpawn == null) {
                continue;
            }
            return new GeneratedRoom(
                    "generated-" + seed, shape, props, partyStart, goblinSpawn);
        }
        throw new IllegalStateException(
                "Kit '" + kitId + "' produced no legal room in " + MAX_ATTEMPTS + " attempts");
    }

    /** The far half of the room, so a hostile does not arrive in the player's lap. */
    private static Square spawnAwayFrom(RoomShape shape, List<dm.model.Prop> props,
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomGeneratorTest'`
Expected: PASS, 5 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/GeneratedRoom.java \
        server/src/main/java/dm/generate/RoomGenerator.java \
        server/src/test/java/dm/generate/RoomGeneratorTest.java
git commit -m "Generate a room, reject it if the world is wrong, hand back one the engine reads"
```

---

### Task 7: Look at what was made

The debug view. `debug → roll d20` exists so dice feel can be tuned without burning a turn; this is the same idea for rooms.

**Files:**
- Create: `server/src/main/java/dm/generate/RoomDumper.java`
- Test: `server/src/test/java/dm/generate/RoomDumperTest.java`

**Interfaces:**
- Consumes: `GeneratedRoom`
- Produces: `RoomDumper.dump(GeneratedRoom room)` returning `String`

Legend: `@` party start, `g` goblin spawn, `S` sarcophagus, `b` brazier, `|` pillar, `%` rubble, `a` alcove, `+` door, `.` open floor.

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomDumperTest {

    @Test
    @DisplayName("the dump shows the party, the spawn and every prop")
    void showsTheRoom() {
        var room = new RoomGenerator(new ContentLoader()).generate("crypt", 3);

        var dump = RoomDumper.dump(room);

        assertTrue(dump.contains("@"), "no party marker\n" + dump);
        assertTrue(dump.contains("g"), "no goblin spawn marker\n" + dump);
        assertTrue(dump.contains("S"), "no sarcophagus\n" + dump);
        assertTrue(dump.contains(room.roomId()), "no room id in the header\n" + dump);
    }

    @Test
    @DisplayName("the grid is exactly as wide and tall as the room")
    void gridMatchesDimensions() {
        var room = new RoomGenerator(new ContentLoader()).generate("crypt", 8);

        var gridLines = RoomDumper.dump(room).lines()
                .filter(line -> line.startsWith("|"))
                .toList();

        assertEquals(room.shape().height(), gridLines.size());
        for (var line : gridLines) {
            // A leading and trailing wall character bracket each row.
            assertEquals(room.shape().width() + 2, line.length(), line);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomDumperTest'`
Expected: FAIL — compilation error, `cannot find symbol: class RoomDumper`

- [ ] **Step 3: Write minimal implementation**

```java
package dm.generate;

import dm.model.PropType;
import dm.model.Square;

/**
 * A generated room as text.
 *
 * <p>Exists for the same reason {@code debug → roll d20} does: the fastest loop wins, and
 * judging whether a room reads as a place should not cost a browser reload. Generation quality
 * is a property of what is in the room, and that is legible from a grid of characters.
 */
public final class RoomDumper {

    private RoomDumper() {
    }

    public static String dump(GeneratedRoom room) {
        var out = new StringBuilder();
        out.append(room.roomId())
                .append("  ").append(room.shape().width()).append('x').append(room.shape().height())
                .append("  ").append(room.shape().floorType())
                .append(" / ").append(room.shape().wallType())
                .append(" / ").append(room.shape().lighting())
                .append('\n');

        for (int y = 0; y < room.shape().height(); y++) {
            out.append('|');
            for (int x = 0; x < room.shape().width(); x++) {
                out.append(glyphAt(room, x, y));
            }
            out.append("|\n");
        }

        out.append("@ party  g goblin  S sarcophagus  b brazier  | pillar  % rubble  a alcove\n");
        return out.toString();
    }

    private static char glyphAt(GeneratedRoom room, int x, int y) {
        var square = new Square(x, y);
        if (square.equals(room.partyStart())) {
            return '@';
        }
        if (square.equals(room.goblinSpawn())) {
            return 'g';
        }
        return room.props().stream()
                .filter(p -> p.x() == x && p.y() == y)
                .findFirst()
                .map(p -> glyphFor(p.type()))
                .orElse('.');
    }

    private static char glyphFor(PropType type) {
        return switch (type) {
            case SARCOPHAGUS -> 'S';
            case BRAZIER -> 'b';
            case PILLAR -> '|';
            case RUBBLE -> '%';
            case ALCOVE -> 'a';
            case DOOR -> '+';
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomDumperTest'`
Expected: PASS, 2 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/RoomDumper.java \
        server/src/test/java/dm/generate/RoomDumperTest.java
git commit -m "See the room without opening a browser"
```

---

### Task 8: Dress the room

The single LLM pass. It names the room, says what it is, and describes each prop the generator already placed — choosing from ids it is given and inventing none.

**Files:**
- Create: `server/src/main/java/dm/generate/Dressing.java`
- Create: `server/src/main/java/dm/generate/RoomDresser.java`
- Create: `server/src/main/resources/prompts/dress-room.md`
- Create: `server/src/test/java/dm/ScriptedDmClient.java`
- Test: `server/src/test/java/dm/generate/RoomDresserTest.java`

**Interfaces:**
- Consumes: `DmClient` (existing), `GeneratedRoom`, `ContentLoader.prompt(String)`
- Produces: `Dressing(String name, String overview, String sensory, Map<String, String> propDescriptions)`; `RoomDresser(DmClient client, String prompt)` with `Dressing dress(GeneratedRoom room)`; `ScriptedDmClient(String... responses)` implementing `DmClient`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dress pass, with no network in it.
 *
 * <p>Invariant #7 says no free-form string from the model reaches the engine. Prop ids are the
 * closed set here: the generator has already decided what is in the room, and the model may
 * only describe what it is handed. A model that invents "silver-disc-0" gets it dropped —
 * `qwen3-next-80b` invented a small silver disc and a trail of footprints during M0, which is
 * the exact behaviour this rejects.
 */
class RoomDresserTest {

    private static final String PROMPT = new ContentLoader().prompt("dress-room");

    private static GeneratedRoom room() {
        return new RoomGenerator(new ContentLoader()).generate("crypt", 5);
    }

    @Test
    @DisplayName("a well-formed reply becomes a Dressing")
    void parsesReply() {
        var target = room();
        var firstProp = target.props().get(0).id();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber where the walls sweat cold water.",
                  "sensory": "Dripping. The smell of wet stone and old iron.",
                  "props": { "%s": "Slick with condensation." }
                }
                """.formatted(firstProp);

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertEquals("The Weeping Vault", dressing.name());
        assertTrue(dressing.overview().contains("burial chamber"));
        assertEquals("Slick with condensation.", dressing.propDescriptions().get(firstProp));
    }

    @Test
    @DisplayName("a prop id the generator never placed is dropped")
    void rejectsInventedPropIds() {
        var target = room();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber.",
                  "sensory": "Dripping.",
                  "props": { "silver-disc-0": "A small silver disc, half-buried in ash." }
                }
                """;

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertFalse(dressing.propDescriptions().containsKey("silver-disc-0"),
                "the model invented a prop and it survived validation");
        assertTrue(dressing.propDescriptions().isEmpty());
    }

    @Test
    @DisplayName("a reply wrapped in a markdown fence still parses")
    void toleratesCodeFences() {
        var target = room();
        var json = """
                ```json
                {
                  "name": "The Ossuary",
                  "overview": "Bones stacked to the ceiling.",
                  "sensory": "Dust.",
                  "props": {}
                }
                ```
                """;

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertEquals("The Ossuary", dressing.name());
    }

    @Test
    @DisplayName("an unparseable reply falls back rather than killing the room")
    void fallsBackOnGarbage() {
        var target = room();

        var dressing = new RoomDresser(new ScriptedDmClient("I'm sorry, I can't do that."), PROMPT)
                .dress(target);

        assertNotNull(dressing.name());
        assertFalse(dressing.name().isBlank());
        assertTrue(dressing.propDescriptions().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomDresserTest'`
Expected: FAIL — compilation error, `cannot find symbol: class ScriptedDmClient`

- [ ] **Step 3: Write minimal implementation**

Create `server/src/test/java/dm/ScriptedDmClient.java`:

```java
package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A {@link DmClient} that returns canned replies and never opens a socket.
 *
 * <p>The same argument as {@code ScriptedDiceRoller}: a test that fails should mean the code
 * changed, not that a model had an opinion or a provider had a bad minute.
 */
public final class ScriptedDmClient implements DmClient {

    private final Deque<String> replies = new ArrayDeque<>();

    public ScriptedDmClient(String... responses) {
        replies.addAll(List.of(responses));
    }

    @Override
    public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                 DmListener listener) {
        String reply = replies.isEmpty() ? "" : replies.poll();
        listener.onTextDelta(reply);
        return new TurnResult(reply, List.of());
    }
}
```

Create `server/src/main/resources/prompts/dress-room.md`:

```markdown
You are dressing one room of a dungeon. A generator has already decided the room's dimensions
and placed every object in it. Your job is to decide what this place *is* and to describe it.

Reply with JSON and nothing else. No preamble, no code fence, no commentary.

{
  "name": "a short evocative name, three words at most",
  "overview": "what this room is and was, two sentences",
  "sensory": "what it smells and sounds like, one sentence",
  "props": { "prop-id": "one sentence about that object in this room" }
}

Rules:

- **Describe only the prop ids you are given.** You may skip one. You may not invent one. An id
  that is not in the list is discarded, and the object it described will not exist.
- **The dimensions and positions are already decided.** Do not describe a room of a different
  shape, and do not move anything.
- **Never say a grid coordinate.** You are told positions so you know what is near what.
- Give the room a reason to exist. A crypt is not a generic stone chamber — it was built by
  someone, for someone, and something has happened in it since.
- Do not describe the party, and do not narrate. This is not spoken aloud; it is what the
  narrator will read before speaking.
```

Create `server/src/main/java/dm/generate/Dressing.java`:

```java
package dm.generate;

import java.util.Map;

/**
 * What the dress pass decided, after validation.
 *
 * @param propDescriptions keyed by prop id, and containing only ids the generator placed
 */
public record Dressing(
        String name,
        String overview,
        String sensory,
        Map<String, String> propDescriptions
) {
}
```

Create `server/src/main/java/dm/generate/RoomDresser.java`:

```java
package dm.generate;

import dm.ai.DmClient;
import dm.model.Prop;
import dm.wire.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one LLM call in generation. Decides what a room is; never decides what is in it.
 *
 * <p>The division is the point. The generator has already placed every object, so the model
 * cannot conjure a prop the renderer has no mesh for or the engine has no obstruction rule for
 * — it can only describe what it is handed. That is invariant #7 with prop ids as the closed
 * set, and it is aimed at a failure measured during M0: a tool-reliable model that invented
 * "a small silver disc, half-buried in ash" and a trail of footprints, neither of which existed.
 *
 * <p>A dressing that cannot be parsed falls back rather than throwing. An undressed room is
 * playable and dull; no room at all is a crash on the way into a door.
 */
public final class RoomDresser {

    private static final Logger log = LoggerFactory.getLogger(RoomDresser.class);

    private final DmClient client;
    private final String prompt;

    public RoomDresser(DmClient client, String prompt) {
        this.client = client;
        this.prompt = prompt;
    }

    public Dressing dress(GeneratedRoom room) {
        var conversation = List.of(
                DmClient.ChatMessage.system(prompt),
                DmClient.ChatMessage.user(describe(room)));

        // Both methods are abstract on DmListener, so neither can be omitted. Dressing does not
        // stream to anyone — the room is not on screen yet — so the deltas are dropped and an
        // error falls through to the catch below.
        var listener = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onError(Throwable error) {
                log.warn("dress pass stream error: {}", error.toString());
            }
        };

        String reply;
        try {
            reply = client.streamTurn(conversation, null, listener).text();
        } catch (RuntimeException e) {
            log.warn("dress pass failed for {}: {}", room.roomId(), e.toString());
            return fallback();
        }
        return parse(reply, room);
    }

    /** What the model is allowed to know: shape, surfaces, and the ids it may describe. */
    private static String describe(GeneratedRoom room) {
        String props = room.props().stream()
                .map(p -> "- " + p.id() + " (" + p.type().name().toLowerCase() + ")")
                .collect(Collectors.joining("\n"));

        return """
                The room is %d by %d squares. The floor is %s, the walls are %s, and it is %s.

                Objects in it, by id — describe these and only these:
                %s
                """.formatted(
                room.shape().width(), room.shape().height(),
                room.shape().floorType().name().toLowerCase().replace('_', ' '),
                room.shape().wallType().name().toLowerCase(),
                room.shape().lighting().name().toLowerCase(),
                props);
    }

    private Dressing parse(String reply, GeneratedRoom room) {
        // Models wrap JSON in a fence however firmly you ask them not to. M0 learned this the
        // hard way when a prompt's own example fence came back as narration and was read aloud.
        String json = reply.strip()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("```$", "")
                .strip();

        try {
            var node = Json.MAPPER.readTree(json);
            Set<String> known = room.props().stream().map(Prop::id).collect(Collectors.toSet());

            var descriptions = new LinkedHashMap<String, String>();
            var props = node.path("props");
            props.fieldNames().forEachRemaining(id -> {
                if (known.contains(id)) {
                    descriptions.put(id, props.get(id).asText());
                } else {
                    log.warn("dress pass invented a prop id, dropped: {}", id);
                }
            });

            String name = node.path("name").asText("");
            if (name.isBlank()) {
                return fallback();
            }
            return new Dressing(
                    name,
                    node.path("overview").asText(""),
                    node.path("sensory").asText(""),
                    Map.copyOf(descriptions));
        } catch (Exception e) {
            log.warn("unparseable dressing for {}: {}", room.roomId(), e.toString());
            return fallback();
        }
    }

    private static Dressing fallback() {
        return new Dressing(
                "An Unnamed Chamber",
                "A stone room, silent and unremarkable.",
                "Cold air and the smell of dust.",
                Map.of());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomDresserTest'`
Expected: PASS, 4 tests

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/Dressing.java \
        server/src/main/java/dm/generate/RoomDresser.java \
        server/src/main/resources/prompts/dress-room.md \
        server/src/test/java/dm/ScriptedDmClient.java \
        server/src/test/java/dm/generate/RoomDresserTest.java
git commit -m "Let the model say what the room is, never what is in it"
```

---

### Task 9: Apply the dressing, and play in it

The last task wires generation into the running game: the dressing lands on the `RoomDefinition` the DM reads, and `--generate <seed>` boots into a generated room.

**Files:**
- Create: `server/src/main/java/dm/generate/RoomSource.java`
- Modify: `server/src/main/java/dm/generate/GeneratedRoom.java` — add `toRoomDefinition(Dressing)`
- Modify: `server/src/main/java/dm/engine/GameEngine.java:34-47` — take a `RoomDefinition` rather than loading `crypt`
- Modify: `server/src/main/java/dm/App.java:40-42` — parse `--generate`, wire the source
- Test: `server/src/test/java/dm/generate/RoomSourceTest.java`

**Interfaces:**
- Consumes: everything above
- Produces: `GeneratedRoom.toRoomDefinition(Dressing dressing)`; `RoomSource.authored(ContentLoader, String roomId)`, `RoomSource.generated(ContentLoader, RoomDresser, String kitId, long seed)`, both returning `RoomDefinition`

- [ ] **Step 1: Write the failing test**

```java
package dm.generate;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomSourceTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("the authored source still loads the crypt, unchanged")
    void authoredStillWorks() {
        var room = RoomSource.authored(CONTENT, "crypt");

        assertEquals("crypt", room.roomId());
        assertEquals("The Ashen Crypt", room.name());
    }

    @Test
    @DisplayName("a dressed generated room carries the model's name and prose")
    void generatedCarriesDressing() {
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber where the walls sweat.",
                  "sensory": "Dripping water.",
                  "props": {}
                }
                """;
        var dresser = new RoomDresser(new ScriptedDmClient(json), CONTENT.prompt("dress-room"));

        var room = RoomSource.generated(CONTENT, dresser, "crypt", 12);

        assertEquals("The Weeping Vault", room.name());
        assertTrue(room.dmNotes().overview().contains("burial chamber"));
        assertTrue(room.dmNotes().sensory().contains("Dripping"));
    }

    @Test
    @DisplayName("a described prop carries its description into the DM's notes")
    void propDescriptionsLand() {
        var generated = new RoomGenerator(CONTENT).generate("crypt", 12);
        var firstProp = generated.props().get(0).id();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber.",
                  "sensory": "Dripping.",
                  "props": { "%s": "Slick with condensation." }
                }
                """.formatted(firstProp);
        var dresser = new RoomDresser(new ScriptedDmClient(json), CONTENT.prompt("dress-room"));

        var room = RoomSource.generated(CONTENT, dresser, "crypt", 12);

        assertEquals("Slick with condensation.", room.prop(firstProp).description());
    }

    @Test
    @DisplayName("the engine starts on a generated room and puts the fighter on the grid")
    void engineStartsOnGeneratedRoom() {
        var dresser = new RoomDresser(new ScriptedDmClient(""), CONTENT.prompt("dress-room"));
        var room = RoomSource.generated(CONTENT, dresser, "crypt", 21);

        var repo = new dm.repo.InMemoryGameRepository();
        var engine = new dm.engine.GameEngine(CONTENT, repo, new dm.engine.RandomDiceRoller(), room);
        engine.start();

        var scene = engine.scene();
        assertEquals(room.roomId(), scene.roomId());
        assertEquals(1, scene.entities().size());
        assertFalse(room.isObstructed(scene.entities().get(0).x(), scene.entities().get(0).y()),
                "the fighter started inside something solid");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew test --tests 'dm.generate.RoomSourceTest'`
Expected: FAIL — compilation error, `cannot find symbol: class RoomSource`

- [ ] **Step 3: Write minimal implementation**

Add to `server/src/main/java/dm/generate/GeneratedRoom.java`, after the existing `toRoomDefinition()`:

```java
    /** The same room, with the dress pass's prose written into it. */
    public RoomDefinition toRoomDefinition(Dressing dressing) {
        var definitions = props.stream()
                .map(p -> new RoomDefinition.PropDefinition(
                        p.id(), p.type(), p.x(), p.y(), p.rotation(), p.hidden(),
                        dressing.propDescriptions().getOrDefault(p.id(), UNDRESSED),
                        null, null))
                .toList();

        var plain = toRoomDefinition();
        return new RoomDefinition(
                plain.roomId(),
                dressing.name(),
                plain.width(),
                plain.height(),
                plain.floorType(),
                plain.wallType(),
                plain.lighting(),
                definitions,
                plain.startPositions(),
                new RoomDefinition.DmNotes(
                        dressing.overview(), dressing.sensory(), null, null, null));
    }
```

Create `server/src/main/java/dm/generate/RoomSource.java`:

```java
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
```

Modify `server/src/main/java/dm/engine/GameEngine.java`. Replace the constructor (currently at line 40) and keep the old signature delegating, so no existing caller breaks:

```java
    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice) {
        this(content, repo, dice, content.room("crypt"));
    }

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice,
                      RoomDefinition room) {
        this.content = content;
        this.repo = repo;
        this.dice = dice;
        this.room = room;
        this.combat = new CombatEngine(this);
    }
```

Modify `server/src/main/java/dm/App.java`. Replace lines 40-42 (`var repo = ...` through `engine.start();`) with:

```java
        var repo = new InMemoryGameRepository();

        // --generate <seed> boots into a procedurally generated room instead of the crypt.
        // The crypt stays the default: it is the room every M0 measurement was taken in.
        Long generateSeed = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--generate".equals(args[i])) {
                generateSeed = Long.parseLong(args[i + 1]);
            }
        }

        RoomDefinition room;
        if (generateSeed == null) {
            room = RoomSource.authored(content, "crypt");
        } else if (config.has("VENICE_API_KEY")) {
            room = RoomSource.generated(content, new RoomDresser(
                    new VeniceDmClient(config, config.get("DM_MODEL_TOOLS", "qwen3-next-80b"),
                            java.time.Duration.ofSeconds(30)),
                    content.prompt("dress-room")), "crypt", generateSeed);
        } else {
            // Undressed but playable — the generator half needs no key, and a room with no prose
            // is more useful than a refusal to boot while tuning layout.
            log.warn("VENICE_API_KEY not set — generating an undressed room.");
            room = new RoomGenerator(content).generate("crypt", generateSeed).toRoomDefinition();
        }

        var engine = new GameEngine(content, repo, dice, room);
        engine.start();
```

Add the imports `dm.content.RoomDefinition`, `dm.generate.RoomDresser`, `dm.generate.RoomGenerator` and `dm.generate.RoomSource` to `App.java`.

- [ ] **Step 4: Run the tests, then play it**

Run: `cd server && ./gradlew test`
Expected: PASS — the whole suite, including every M0 test unchanged.

Then run it for real:

```bash
cd server && ./gradlew run --args='--generate 7'
```

Expected: the ASCII dump in the log, a dressed room name, and the server listening on :7070. Start the client with `cd client && npm run dev`, open http://localhost:5173, and click DESCEND. **You should be standing in a room nobody authored.**

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/dm/generate/RoomSource.java \
        server/src/main/java/dm/generate/GeneratedRoom.java \
        server/src/main/java/dm/engine/GameEngine.java \
        server/src/main/java/dm/App.java \
        server/src/test/java/dm/generate/RoomSourceTest.java
git commit -m "Descend into a room nobody wrote"
```

---

## Done when

`./gradlew run --args='--generate 7'` boots the game into a generated, dressed, spatially validated room, and M0's loop — narration, dice, the goblin, the fight — plays in it unchanged.

Then judge it the way M0 was judged: generate twenty rooms with `RoomDumper`, read them, and answer whether they are places or whether they are mush. That answer decides what the next plan is.
