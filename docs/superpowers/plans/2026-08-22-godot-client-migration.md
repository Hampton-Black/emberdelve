# Godot Client Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Vite/React/Three.js client with a Godot 4 desktop client that passes the §10 parity gate, then delete `client/`.

**Architecture:** Four autoloads own facts and I/O — `Link` (where the server is), `Net` (the socket), `Table` (all game state), `Clock` (the presentation queue that paces everything). Scenes subscribe to `Table` and never own game facts. The Java server is unchanged except for four small things and is started by hand in a terminal; Godot attaches to it and never spawns it. The port happens in two phases: **Phase A** ends with a fully playable text-and-dice game with no 3D at all, which makes every feel-critical rule testable before a single mesh is imported. **Phase B** builds the world under it.

**Tech Stack:** Godot 4.5.x, GDScript only. GUT for tests. Java 25 + Javalin + Jackson on the server (unchanged). No new Java dependencies. No C#, no GDExtension, no physics.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-21-godot-client-design.md`. Where this plan and the spec disagree, **the spec wins**.
- **Numbers:** `AGENTS.md` is authoritative for every feel number (dice timing, impact beat, ceremony, token scale, audio layers). If a Godot scene disagrees with those numbers, the numbers win until a played session replaces them.
- **Godot version: 4.5.x, pinned.** Not "current stable".
- **Renderer: Forward+.** Task 16's edge shader reads `NORMAL_ROUGHNESS_TEXTURE`, which no other renderer provides.
- **GDScript only.** No C#, no GDExtension.
- **Invariant #1 — the server is authoritative.** The client never computes a roll, a hit, a legal move, or a death. Click-to-move tests membership in `legalMoves` from the wire and nothing else.
- **Invariant #2 — no singleton player.** Address every creature by `actorId` from the wire. Never a literal `"fighter"`, never "the first entity".
- **Invariant #3 — game state lives in `Table`,** never in a `Control` or `Node3D` local.
- **Invariant #4 — the 3D world is one scene, created once,** not rebuilt by chrome redraws.
- **Invariant #5 — `RollResult.faces` is a list of integers,** never collapsed before the tray.
- **Invariant #8 — no `user://` save of the session.** In-memory only.
- **Invariant #10 — no new tools, entity types, props, or rules.** This is a client swap.
- **One clock:** `Time.get_ticks_msec()` everywhere `performance.now()` appears today.
- **Nulls are nulls:** `RollRequest.targetId`, `dc` and `skill` arrive as JSON `null`. Compare `!= null`. Never rely on GDScript truthiness — `if dc:` is false for a DC of 0.
- **One setting for the server address:** `EMBERDELVE_SERVER`, default `http://127.0.0.1:7070`. Every URL derives from it. Never a second constant.
- **Wire mirrors are hand-written.** Any change to a Java record in `dm.model` or `dm.wire` gets the matching change to `client/src/types.ts` **and** the GDScript reader in the same commit, until `client/` is deleted.
- **Commit messages** are a sentence saying what the commit does, in the repository's existing voice (`git log` for examples). No `feat:` prefixes. **No `Co-Authored-By` trailer.**
- **Java test command:** `cd server && ./gradlew test`
- **Godot test command:** `godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`
- **Server run command:** `cd server && ./gradlew run --args='--demo'`
- **Client typecheck (while `client/` lives):** `cd client && npx tsc --noEmit`

## Not in this plan

- **Packaging.** No `.app`, no bundled JRE, no spawning Java, no code signing, no notarization. Spec §12.
- **Hosting the DM.** Belongs to the multiplayer milestone. Spec §3.
- **The M1 dungeon-navigation plan.** Paused. `docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` is written against Three.js and none of its Java exists yet; its client tasks are re-planned against Godot after the parity gate.
- **New rooms, props, entities, tools or rules.** Invariant #10.
- **An IDL / codegen for the wire.** Still hand-written mirrors.
- **Windows or Linux exports.**

---

## File Structure

### Phase A — the table

| File | Responsibility |
|---|---|
| `godot/project.godot` | Project settings: 4.5.x, autoloads, TTS enabled, window size, nearest-neighbour default filter |
| `godot/addons/gut/` | Test runner (vendored addon) |
| `godot/autoload/link.gd` | Where the server is. One setting; `base_url`, `ws_url`, `health_url` derive from it |
| `godot/autoload/net.gd` | `WebSocketPeer` polled from `_process`. JSON in, one signal per `ServerMessage.type` out |
| `godot/autoload/table.gd` | Every game fact. Applies a whole diff batch, then emits once |
| `godot/autoload/clock.gd` | `speak` / `mark` / `hold` / `silence` / `silence_now`. The presentation queue |
| `godot/dice/tumble.gd` | Pure dice presentation maths, ported from `client/src/dice/tumble.ts` |
| `godot/audio/voice.gd` | `VoiceBackend` seam plus the HTTP-MP3 and OS-TTS adapters |
| `godot/audio/sfx.gd` | Autoload. Layered stings on Godot buses; the numbers from `AGENTS.md` |
| `godot/audio/samples/` | Kenney clips, plus the baked `drop` wavs that replace Web Audio synthesis |
| `godot/chrome/chrome.tscn` | The `CanvasLayer` that holds every overlay |
| `godot/chrome/transcript.gd` | `RichTextLabel` log, colour by speaker, scrollback |
| `godot/chrome/input_box.gd` | `LineEdit` docked under the log; locked while `awaiting_dm` |
| `godot/chrome/dice_tray.gd` | 2D tray, drawn from `tumble.gd` samples |
| `godot/chrome/combat_bar.gd` | Initiative order, HP, end-turn; the ceremony |
| `godot/chrome/title.gd` | Title overlay. The click that sends `begin` |
| `godot/chrome/defeat.gd` | Defeat overlay and restart |
| `godot/chrome/debug_bar.gd` | The seven debug messages. No model, no key, no latency |
| `godot/chrome/toast.gd` | Errors and the `Hello.dm == false` notice |
| `godot/test/` | GUT suites: `test_tumble.gd`, `test_clock.gd`, `test_table.gd`, `test_link.gd`, `test_net.gd` |
| `server/src/main/java/dm/Args.java` | *(create)* `--port` and `--demo` parsing, as a testable function |
| `server/src/main/java/dm/SessionGuard.java` | *(create)* one live connection at a time |
| `server/src/main/java/dm/App.java` | *(modify)* bind host, port from `Args`, `Hello.dm` |
| `server/src/main/java/dm/WsHandler.java` | *(modify)* the session guard |
| `server/src/main/java/dm/wire/ServerMessage.java` | *(modify)* `Hello` gains `dm` |
| `client/src/types.ts` | *(modify)* mirror `Hello.dm` |

### Phase B — the world

| File | Responsibility |
|---|---|
| `godot/world/world.tscn` | The one 3D scene (invariant #4). `SubViewport` at 480px, camera, room root |
| `godot/world/pixel.gdshader` | Depth + normal edge detection over the viewport, replacing `RenderPixelatedPass` |
| `godot/world/camera_rig.gd` | Four isometric corners, 90° snap, framing, follow, pixel snap |
| `godot/world/room.gd` | Floor and walls from `floorType` / `wallType`; the grid |
| `godot/world/prop_table.tres` | `PropType` → scene path. The `MESH_PROPS` seam, as a resource |
| `godot/world/props/*.tscn` | One scene per `PropType` |
| `godot/world/tokens/token.gd` | One creature: clips, slide, health bar, impact gating |
| `godot/world/tokens/kits/graveyard/`, `.../mini/` | One folder per kit — colormaps collide otherwise |
| `godot/world/overlay.gd` | Legal-move and target highlights; hover; click routing |
| `godot/world/lighting.gd` | Brazier lights and the three `LightingPreset` groups |

---

## Phase A — the table

Phase A ends with a game that is fully playable as text and dice with no 3D whatsoever. That is deliberate: every rule that carries the *feel* — the ordered voice queue, the dice gate, the impact beat, the ceremony — lives in Phase A, and none of it needs a mesh to be judged or tested. Do not start Phase B until Task 14's checkpoint plays.

---

### Task 1: Java — `--port`, bind host, and a testable argument parser

**Files:**
- Create: `server/src/main/java/dm/Args.java`
- Create: `server/src/test/java/dm/ArgsTest.java`
- Modify: `server/src/main/java/dm/App.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record Args(boolean demoMode, int port, Long generateSeed)` with `static Args parse(String[] args)`. `App` uses it in place of its inline `Arrays.asList(args).contains("--demo")` and the `--generate` loop.

- [ ] **Step 1: Write the failing test**

Create `server/src/test/java/dm/ArgsTest.java`:

```java
package dm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsTest {

    @Test
    void defaultsToSevenThousandSeventyAndRealDice() {
        var args = Args.parse(new String[] {});
        assertEquals(7070, args.port());
        assertFalse(args.demoMode());
        assertNull(args.generateSeed());
    }

    @Test
    void readsEveryFlag() {
        var args = Args.parse(new String[] {"--demo", "--port", "7171", "--generate", "7"});
        assertTrue(args.demoMode());
        assertEquals(7171, args.port());
        assertEquals(7L, args.generateSeed());
    }

    @Test
    void aPortWithNoNumberAfterItIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"--port"}));
    }

    @Test
    void aPortThatIsNotANumberIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--port", "seven"}));
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
cd server && ./gradlew test --tests 'dm.ArgsTest'
```

Expected: FAIL — `cannot find symbol: class Args`.

- [ ] **Step 3: Write the implementation**

Create `server/src/main/java/dm/Args.java`:

```java
package dm;

/**
 * The command line, parsed once.
 *
 * <p>A record rather than three scattered loops in {@code main} because {@code --port} is now
 * load-bearing: the Godot client derives every URL it uses from one setting, and a port the
 * server misreads is a client that attaches to nothing with no error worth reading.
 */
public record Args(boolean demoMode, int port, Long generateSeed) {

    private static final int DEFAULT_PORT = 7070;

    public static Args parse(String[] args) {
        boolean demo = false;
        int port = DEFAULT_PORT;
        Long seed = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--demo" -> demo = true;
                case "--port" -> {
                    port = Integer.parseInt(value(args, i, "--port"));
                    i++;
                }
                case "--generate" -> {
                    seed = Long.parseLong(value(args, i, "--generate"));
                    i++;
                }
                default -> { }
            }
        }

        return new Args(demo, port, seed);
    }

    private static String value(String[] args, int at, String flag) {
        if (at + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[at + 1];
    }
}
```

`Integer.parseInt` throws `NumberFormatException`, which extends `IllegalArgumentException`, so the fourth test passes without a second catch.

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd server && ./gradlew test --tests 'dm.ArgsTest'
```

Expected: PASS, 0 failures.

- [ ] **Step 5: Use it in `App`, and bind the loopback**

In `server/src/main/java/dm/App.java`, replace the `demoMode` line and the `--generate` loop:

```java
        var cli = Args.parse(args);
        boolean demoMode = cli.demoMode();
        var config = Config.load();
```

Delete these lines entirely:

```java
        Long generateSeed = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--generate".equals(args[i])) {
                generateSeed = Long.parseLong(args[i + 1]);
            }
        }
```

and replace the two uses of `generateSeed` with `cli.generateSeed()`.

Then replace the final `app.start(PORT)` and the log line beneath it:

```java
        // Loopback only. This is a local DM, not a network service — and it stays that way
        // until session scoping and auth exist, which is the multiplayer milestone's work.
        app.start("127.0.0.1", cli.port());
        log.info("Emberdelve on 127.0.0.1:{} — room '{}', {} entities, dm={}{}",
                cli.port(),
                engine.room().name(),
                repo.entities().size(),
                dm == null ? "disabled" : dm.modelId(),
                demoMode ? ", demo dice" : "");
```

Delete the now-unused `private static final int PORT = 7070;` field.

- [ ] **Step 6: Run the whole suite and boot the server**

```bash
cd server && ./gradlew test
```

Expected: PASS, no regressions.

```bash
cd server && ./gradlew run --args='--demo --port 7171'
```

Expected: a log line reading `Emberdelve on 127.0.0.1:7171`. Stop it with Ctrl-C.

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/dm/Args.java server/src/test/java/dm/ArgsTest.java server/src/main/java/dm/App.java && git commit -m "Parse the command line once, and bind the loopback only"
```

---

### Task 2: Java — `Hello.dm`, and one client at a time

**Files:**
- Create: `server/src/main/java/dm/SessionGuard.java`
- Create: `server/src/test/java/dm/SessionGuardTest.java`
- Create: `server/src/test/java/dm/wire/HelloTest.java`
- Modify: `server/src/main/java/dm/wire/ServerMessage.java:36`
- Modify: `server/src/main/java/dm/WsHandler.java`
- Modify: `server/src/main/java/dm/App.java`
- Modify: `client/src/types.ts`

**Interfaces:**
- Consumes: `Args` from Task 1.
- Produces: `ServerMessage.Hello(boolean demoMode, boolean voice, boolean dm)` — the Godot client reads `dm` in Task 9 and `voice` in Task 12. `SessionGuard` with `boolean claim(String sessionId)` and `void release(String sessionId)`.

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/java/dm/SessionGuardTest.java`:

```java
package dm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionGuardTest {

    @Test
    void theFirstClientGetsTheTable() {
        var guard = new SessionGuard();
        assertTrue(guard.claim("a"));
    }

    @Test
    void aSecondClientIsRefused() {
        var guard = new SessionGuard();
        guard.claim("a");
        assertFalse(guard.claim("b"));
    }

    @Test
    void theSameClientClaimingTwiceStillHoldsIt() {
        var guard = new SessionGuard();
        guard.claim("a");
        assertTrue(guard.claim("a"));
    }

    @Test
    void releasingLetsTheNextClientIn() {
        var guard = new SessionGuard();
        guard.claim("a");
        guard.release("a");
        assertTrue(guard.claim("b"));
    }

    @Test
    void aRefusedClientCannotReleaseTheHolder() {
        var guard = new SessionGuard();
        guard.claim("a");
        guard.claim("b");
        guard.release("b");
        assertFalse(guard.claim("c"));
    }
}
```

Create `server/src/test/java/dm/wire/HelloTest.java`:

```java
package dm.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloTest {

    @Test
    void carriesWhetherTheDmIsConfigured() throws Exception {
        String json = Json.MAPPER.writeValueAsString(new ServerMessage.Hello(true, false, true));
        assertTrue(json.contains("\"dm\":true"), json);
        assertTrue(json.contains("\"voice\":false"), json);
        assertTrue(json.contains("\"demoMode\":true"), json);
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd server && ./gradlew test --tests 'dm.SessionGuardTest' --tests 'dm.wire.HelloTest'
```

Expected: FAIL — `cannot find symbol: class SessionGuard`, and `Hello` takes two arguments not three.

- [ ] **Step 3: Write `SessionGuard`**

Create `server/src/main/java/dm/SessionGuard.java`:

```java
package dm;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One session, one connection — enforced rather than documented.
 *
 * <p>{@code WsHandler} captures a {@code WsContext} per turn and sends that turn's narration,
 * dice and diffs back to it. With two clients attached, a turn typed in one window is narrated
 * into the other, which presents as a Godot bug and is not one. The bridge period between the
 * two clients is exactly when that is most likely to happen, so it is five lines of Java rather
 * than a sentence someone has to remember.
 */
public final class SessionGuard {

    private final AtomicReference<String> holder = new AtomicReference<>();

    /** True if this session now owns the table. Idempotent for the holder. */
    public boolean claim(String sessionId) {
        return holder.compareAndSet(null, sessionId) || sessionId.equals(holder.get());
    }

    /** Only the holder can let go. A refused client closing must not free the table. */
    public void release(String sessionId) {
        holder.compareAndSet(sessionId, null);
    }
}
```

- [ ] **Step 4: Add `dm` to `Hello`**

In `server/src/main/java/dm/wire/ServerMessage.java`, replace line 36:

```java
    record Hello(boolean demoMode, boolean voice, boolean dm) implements ServerMessage {}
```

In `server/src/main/java/dm/WsHandler.java`, add the field, the constructor parameter and the guard. Change the field block and constructor:

```java
    private final GameEngine engine;
    private final DmService dm;
    private final boolean demoMode;
    private final boolean voice;
    private final SessionGuard guard = new SessionGuard();

    public WsHandler(GameEngine engine, DmService dm, boolean demoMode, boolean voice) {
        this.engine = engine;
        this.dm = dm;
        this.demoMode = demoMode;
        this.voice = voice;
    }
```

Replace the whole `ws.onConnect` and `ws.onClose` bodies in `register`:

```java
        ws.onConnect(ctx -> {
            if (!guard.claim(ctx.sessionId())) {
                // Not an error the client should retry. Say which one is already attached to,
                // because the usual cause is a browser tab left open beside the Godot editor.
                log.warn("refusing a second client: {}", ctx.sessionId());
                ctx.closeSession(4001, "Another client is already at this table.");
                return;
            }
            ctx.enableAutomaticPings();
            log.info("client connected: {}", ctx.sessionId());
            send(ctx, new ServerMessage.Hello(demoMode, voice, dm != null));
            send(ctx, new ServerMessage.Scene(engine.scene()));
            // Deliberately does NOT open the scene. See the `begin` case below.
        });

        ws.onMessage(ctx -> {
            try {
                handle(ctx, Json.MAPPER.readTree(ctx.message()));
            } catch (Exception e) {
                // No error recovery in M0 — log it and surface a visible toast (shortcut #13).
                log.error("failed to handle message: {}", ctx.message(), e);
                send(ctx, new ServerMessage.Error(String.valueOf(e.getMessage())));
            }
        });

        ws.onClose(ctx -> {
            guard.release(ctx.sessionId());
            log.info("client disconnected: {}", ctx.sessionId());
        });
```

`App.java` needs no change here — it already passes `dm` into the `WsHandler` constructor, and `dm != null` is read inside.

- [ ] **Step 5: Mirror the field in TypeScript**

In `client/src/types.ts`, replace the `hello` variant:

```ts
  | { type: "hello"; demoMode: boolean; voice: boolean; dm: boolean }
```

- [ ] **Step 6: Run the tests and the typecheck**

```bash
cd server && ./gradlew test
```

Expected: PASS, including the five new `SessionGuardTest` cases and `HelloTest`.

```bash
cd client && npx tsc --noEmit
```

Expected: no output. The React client ignores the new field; it only has to compile.

- [ ] **Step 7: Prove the guard by hand**

```bash
cd server && ./gradlew run --args='--demo'
```

Open `http://localhost:5173` in two browser tabs (`cd client && npm run dev` in another terminal). Expected: the second tab's socket closes and the server logs `refusing a second client`.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/java/dm/SessionGuard.java server/src/test/java/dm/SessionGuardTest.java server/src/test/java/dm/wire/HelloTest.java server/src/main/java/dm/wire/ServerMessage.java server/src/main/java/dm/WsHandler.java client/src/types.ts && git commit -m "Tell the client whether a DM is configured, and hold the table for one of them"
```

---

### Task 3: The Godot project, GUT, and `Link`

**Files:**
- Create: `godot/project.godot`
- Create: `godot/.gitignore`
- Create: `godot/autoload/link.gd`
- Create: `godot/test/test_link.gd`
- Vendor: `godot/addons/gut/`
- Modify: `AGENTS.md` (the Commands section)

**Interfaces:**
- Consumes: nothing.
- Produces: autoload `Link` with `base_url() -> String`, `ws_url() -> String`, `health_url() -> String`, `tts_url() -> String`. Every later task gets its URLs from here and defines none of its own.

- [ ] **Step 1: Create the project in the editor**

Use the Godot project manager rather than hand-writing `project.godot` — it generates the icon,
the `.godot` cache and the UID files, and gets `config/features` right for the renderer chosen.

- **Project Path:** `<repo>/godot` — inside this repository, alongside `client/` and `server/`.
  The Godot project is part of the project, not a sibling of it, and Task 22 deletes `client/`
  in a commit that has to sit next to this one in the same history.
- **Renderer: Forward+.** Not Mobile, not Compatibility. See below.
- **Version control metadata: Git.** It does *not* run `git init` or nest a repository — it only
  writes a `.gitignore` and a `.gitattributes` into `godot/`. Take it for the `.gitattributes`:
  `* text=auto eol=lf` keeps `.tscn` and `.tres` from churning their line endings, and those are
  text files the editor rewrites constantly. Cheaper now than retrofitting once CRLF is in the
  history, which matters given §12 has Windows on the roadmap.

**Why Forward+, and why it is not a free choice.** Task 16 has to rebuild the edge detection
that `RenderPixelatedPass` was doing (`Renderer.ts:358`), and that shader reads
`NORMAL_ROUGHNESS_TEXTURE` — which **only the Forward+ renderer provides**. Picking Compatibility
here does not fail at project creation; it fails five tasks later with a shader that will not
compile, and the fix is to change the renderer and re-check every material.

The other two lose on their own merits as well. Mobile exists for tiled GPUs and this is a
desktop app. Compatibility caps lights per object, and the crypt runs up to `MAX_TORCH_LIGHTS`
(10) omnis in a dark room (`Renderer.ts:120`) — Forward+'s clustered lighting is what makes that
a non-question. Nothing here is performance-driven: one room, two entities, a 480x270 internal
buffer. `AGENTS.md`: *"Do not optimize anything."*

Compatibility would only be right if a **browser** build were wanted. It is not — spec §3 lists
"keeping a web client after parity" as out of scope, and the hosted-server option in §12 is about
where the *DM* runs, not the client.

- [ ] **Step 2: Vendor GUT**

```bash
cd godot && git clone --depth 1 --branch v9.3.0 https://github.com/bitwes/Gut.git /tmp/gut && mkdir -p addons && cp -R /tmp/gut/addons/gut addons/gut && rm -rf /tmp/gut && mkdir -p autoload test
```

- [ ] **Step 3: Extend the generated `.gitignore`**

Step 1 wrote `godot/.gitignore` with `.godot/` and `/android/` already in it. Add two more:

```
*.translation
export_presets.cfg
```

`export_presets.cfg` is ignored on principle rather than need — distribution is deferred (§12) so
it will not exist yet, but it is the file that ends up holding signing identities and keystore
passwords, and spec §2 says keys never reach git.

- [ ] **Step 4: Apply the project settings**

Edit `godot/project.godot` so it reads as below. The editor wrote `config/features` and
`config_version` already — **leave them alone**; for Forward+ the features array is just the
version, with no renderer tag (a `"GL Compatibility"` entry there means the wrong renderer was
picked in Step 1).

```ini
[application]

config/name="Emberdelve"
run/main_scene="res://chrome/chrome.tscn"

[audio]

general/text_to_speech=true

[autoload]

Link="*res://autoload/link.gd"
Net="*res://autoload/net.gd"
Table="*res://autoload/table.gd"
Clock="*res://autoload/clock.gd"
Sfx="*res://audio/sfx.gd"

[display]

window/size/viewport_width=1280
window/size/viewport_height=720

[editor_plugins]

enabled=PackedStringArray("res://addons/gut/plugin.cfg")

[rendering]

textures/canvas_textures/default_texture_filter=0
```

`audio/general/text_to_speech=true` is not optional — `DisplayServer.tts_*` silently does nothing
without it, and it is off by default. `default_texture_filter=0` is nearest-neighbour.

`run/main_scene` and four of the five autoloads point at files that do not exist yet. That is
fine — the project will not run until Task 14, and every task between here and there adds one.

- [ ] **Step 5: Write the failing test**

Create `godot/test/test_link.gd`:

```gdscript
extends GutTest

# Link reads one environment variable and derives every URL from it. These tests exercise the
# derivation directly rather than the environment, because OS.set_environment does not affect
# an already-running process's view on every platform.

func test_default_is_the_local_server() -> void:
	assert_eq(Link.normalise(""), "http://127.0.0.1:7070")

func test_a_trailing_slash_is_dropped() -> void:
	assert_eq(Link.normalise("http://127.0.0.1:7070/"), "http://127.0.0.1:7070")

func test_http_becomes_ws() -> void:
	assert_eq(Link.ws_from("http://127.0.0.1:7070"), "ws://127.0.0.1:7070/ws")

func test_https_becomes_wss() -> void:
	assert_eq(Link.ws_from("https://emberdelve.example.com"), "wss://emberdelve.example.com/ws")

func test_the_live_urls_all_come_off_one_base() -> void:
	var base := Link.base_url()
	assert_eq(Link.health_url(), base + "/health")
	assert_eq(Link.tts_url(), base + "/tts")
	assert_eq(Link.ws_url(), Link.ws_from(base))
```

- [ ] **Step 6: Run it and watch it fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — the autoload script `res://autoload/link.gd` does not exist.

- [ ] **Step 7: Write `Link`**

Create `godot/autoload/link.gd`:

```gdscript
extends Node

## Where the server is. The only address in this client.
##
## Every URL the game uses — the socket, speech, health — derives from one setting. There is no
## second constant and no port arithmetic anywhere else in the project. That is what keeps a
## hosted DM a configuration change rather than a rewrite, and it costs one file.
##
## Godot never starts the server and never stops it. It is a Gradle process in a terminal
## (`cd server && ./gradlew run`) that gets restarted a dozen times an evening, and the client's
## job is to reattach quietly when it comes back.

const DEFAULT := "http://127.0.0.1:7070"
const SETTING := "EMBERDELVE_SERVER"

var _base: String = ""


func _ready() -> void:
	_base = normalise(OS.get_environment(SETTING))
	print("[link] server at ", _base)


## Trim to a bare origin. Empty means the default.
static func normalise(raw: String) -> String:
	var value := raw.strip_edges()
	if value.is_empty():
		value = DEFAULT
	while value.ends_with("/"):
		value = value.substr(0, value.length() - 1)
	return value


## The socket URL for a given base. `wss` for `https`, so a hosted server needs no code change.
static func ws_from(base: String) -> String:
	if base.begins_with("https://"):
		return "wss://" + base.substr(8) + "/ws"
	if base.begins_with("http://"):
		return "ws://" + base.substr(7) + "/ws"
	return base + "/ws"


func base_url() -> String:
	# Tests instantiate this class without _ready having run.
	if _base.is_empty():
		_base = normalise(OS.get_environment(SETTING))
	return _base


func ws_url() -> String:
	return ws_from(base_url())


func health_url() -> String:
	return base_url() + "/health"


func tts_url() -> String:
	return base_url() + "/tts"
```

- [ ] **Step 8: Stub the four autoloads that do not exist yet**

GUT cannot load the project while `project.godot` names missing autoloads. Create four placeholders that later tasks replace wholesale.

`godot/autoload/net.gd`:

```gdscript
extends Node
# Replaced in Task 7.
```

`godot/autoload/table.gd`:

```gdscript
extends Node
# Replaced in Task 8.
```

`godot/autoload/clock.gd`:

```gdscript
extends Node
# Replaced in Task 5.
```

`godot/audio/sfx.gd`:

```gdscript
extends Node
# Replaced in Task 10.
```

- [ ] **Step 9: Run the test and watch it pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

- [ ] **Step 10: Record the commands**

In `AGENTS.md`, in the `## Commands` fenced block, add two lines under the existing ones:

```bash
cd godot && godot .                 # the Godot editor
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit   # godot tests
```

- [ ] **Step 11: Commit**

```bash
git add godot AGENTS.md && git commit -m "Start the Godot project with one place that knows where the server is"
```

---

### Task 4: `tumble.gd` — the dice, as pure data

**Files:**
- Create: `godot/dice/tumble.gd`
- Create: `godot/test/test_tumble.gd`

**Interfaces:**
- Consumes: nothing. This file draws nothing, plays nothing, and touches no state.
- Produces: `Tumble.land_at(index: int) -> int`, `Tumble.reveal_at(die_count: int) -> int`, `Tumble.is_dramatic(result: Dictionary) -> bool`, `Tumble.sample(result: Dictionary, elapsed: int, dismiss_at: int) -> Dictionary`, `Tumble.caption(result: Dictionary) -> Dictionary`, `Tumble.dice_sides(expression: String) -> int`, and the constants `WIND_UP_MS`, `HOLD_MS`, `COMBAT_HOLD_MS`, `FADE_OUT_MS`, `IMPACT_BEAT_MS`, `TRAY_WIDTH`, `TRAY_HEIGHT`, `TONE_COLOR`. Task 6 (`Clock`) uses `reveal_at` and `IMPACT_BEAT_MS`; Task 8 (`Table`) uses `is_dramatic`; Task 12 (the tray) uses `sample` and `caption`.

Port of `client/src/dice/tumble.ts`. **Read that file before starting.** Every number here is already tuned and none of them is up for renegotiation — `AGENTS.md` §Dice records what each one bought.

- [ ] **Step 1: Write the failing tests**

Create `godot/test/test_tumble.gd`:

```gdscript
extends GutTest

const Tumble := preload("res://dice/tumble.gd")


func _roll(purpose: String, faces: Array, opts: Dictionary = {}) -> Dictionary:
	return {
		"request": {
			"dice": opts.get("dice", "1d20"),
			"modifier": opts.get("modifier", 0),
			"advantage": opts.get("advantage", "NORMAL"),
			"purpose": purpose,
			"actorId": "fighter",
			"targetId": opts.get("targetId", null),
			"dc": opts.get("dc", null),
			"skill": opts.get("skill", null),
		},
		"faces": faces,
		"total": opts.get("total", 0),
		"outcome": opts.get("outcome", "SUCCESS"),
	}


# --- Timing. These must match tumble.ts exactly or the dice and the voice drift apart.

func test_the_first_die_lands_at_wind_up_plus_flight() -> void:
	assert_eq(Tumble.land_at(0), 1060)

func test_each_later_die_lands_a_stagger_behind() -> void:
	assert_eq(Tumble.land_at(1), 1190)
	assert_eq(Tumble.land_at(2), 1320)

func test_one_die_becomes_legible_after_its_bounce() -> void:
	assert_eq(Tumble.reveal_at(1), 1460)

func test_two_dice_become_legible_after_the_last_one_bounces() -> void:
	assert_eq(Tumble.reveal_at(2), 1590)

func test_a_zero_die_roll_does_not_go_backwards() -> void:
	assert_eq(Tumble.reveal_at(0), 1460)


# --- Which rolls get thrown. Purpose decides, never who rolled (AGENTS.md §Dice).

func test_attacks_saves_and_skill_checks_are_dramatic() -> void:
	assert_true(Tumble.is_dramatic(_roll("ATTACK", [14])))
	assert_true(Tumble.is_dramatic(_roll("SAVE", [9])))
	assert_true(Tumble.is_dramatic(_roll("SKILL_CHECK", [17])))

func test_damage_and_initiative_go_straight_to_the_log() -> void:
	assert_false(Tumble.is_dramatic(_roll("DAMAGE", [5])))
	assert_false(Tumble.is_dramatic(_roll("INITIATIVE", [12])))

func test_a_goblins_attack_is_as_dramatic_as_the_players() -> void:
	# The tensest die in the game is the one thrown at you.
	var incoming := _roll("ATTACK", [18])
	incoming["request"]["actorId"] = "goblin"
	assert_true(Tumble.is_dramatic(incoming))


# --- The one rule: the server decides, the animation displays.

func test_a_settled_die_shows_exactly_what_the_server_sent() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(0), 99999)
	assert_true(visual["dice"][0]["settled"])
	assert_eq(visual["dice"][0]["face"], 17)

func test_every_die_shows_its_own_server_face_once_settled() -> void:
	var result := _roll("ATTACK", [3, 20], {"advantage": "ADVANTAGE"})
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(1), 99999)
	assert_eq(visual["dice"][0]["face"], 3)
	assert_eq(visual["dice"][1]["face"], 20)

func test_a_tumbling_face_is_a_legal_face_but_not_yet_the_result() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, 400, 99999)
	assert_false(visual["dice"][0]["settled"])
	assert_between(visual["dice"][0]["face"], 1, 20)

func test_a_tumbling_face_is_stable_for_a_given_instant() -> void:
	# Derived from the clock, not from randf, so a dropped frame does not make dice stutter.
	var result := _roll("ATTACK", [17])
	var a: Dictionary = Tumble.sample(result, 400, 99999)
	var b: Dictionary = Tumble.sample(result, 400, 99999)
	assert_eq(a["dice"][0]["face"], b["dice"][0]["face"])

func test_the_kept_die_is_first_and_the_rest_are_discards() -> void:
	var result := _roll("ATTACK", [18, 4], {"advantage": "ADVANTAGE"})
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(1), 99999)
	assert_false(visual["dice"][0]["discarded"])
	assert_true(visual["dice"][1]["discarded"])

func test_the_tray_finishes_after_its_fade() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, 3000 + Tumble.FADE_OUT_MS + 1, 3000)
	assert_true(visual["finished"])


# --- Wording, shared by the tray and the log so a success is never worded two ways.

func test_a_skill_check_is_named_by_its_skill() -> void:
	var result := _roll("SKILL_CHECK", [17], {"skill": "ATHLETICS", "dc": 20, "total": 22,
		"modifier": 5, "outcome": "SUCCESS"})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["label"], "ATHLETICS CHECK")
	assert_eq(cap["target"], "DC 20")
	assert_eq(cap["arithmetic"], "17 + 5 = 22")
	assert_eq(cap["outcome"], "SUCCESS")
	assert_eq(cap["tone"], "good")

func test_an_attack_is_read_against_ac_not_dc() -> void:
	var result := _roll("ATTACK", [14], {"dc": 15, "total": 19, "modifier": 5, "outcome": "HIT"})
	assert_eq(Tumble.caption(result)["target"], "AC 15")

func test_a_dc_of_zero_is_still_printed() -> void:
	# `if dc:` is false for 0. The wire says null when there is no DC, and 0 is a real DC.
	var result := _roll("SAVE", [10], {"dc": 0, "total": 10})
	assert_eq(Tumble.caption(result)["target"], "DC 0")

func test_no_dc_prints_nothing() -> void:
	assert_eq(Tumble.caption(_roll("DAMAGE", [5]))["target"], "")

func test_damage_is_not_captioned_as_a_thing_that_could_have_failed() -> void:
	var result := _roll("DAMAGE", [5], {"modifier": 3, "total": 8})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["label"], "DAMAGE")
	assert_eq(cap["outcome"], "")
	assert_eq(cap["tone"], "neutral")

func test_a_crit_is_spelled_out() -> void:
	var result := _roll("ATTACK", [20], {"dc": 15, "total": 25, "modifier": 5, "outcome": "CRIT"})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["outcome"], "CRITICAL")
	assert_eq(cap["tone"], "crit")

func test_a_fumble_is_spelled_out() -> void:
	var result := _roll("ATTACK", [1], {"outcome": "CRIT_FAIL"})
	assert_eq(Tumble.caption(_roll("ATTACK", [1], {"outcome": "CRIT_FAIL"}))["outcome"], "FUMBLE")
	assert_eq(Tumble.caption(result)["tone"], "fumble")

func test_a_lone_unmodified_die_prints_no_arithmetic() -> void:
	var result := _roll("SAVE", [12], {"total": 12})
	assert_eq(Tumble.caption(result)["arithmetic"], "12")

func test_only_the_kept_die_is_counted_on_advantage() -> void:
	var result := _roll("ATTACK", [18, 4], {"advantage": "ADVANTAGE", "modifier": 5, "total": 23})
	assert_eq(Tumble.caption(result)["arithmetic"], "18 + 5 = 23")

func test_a_negative_modifier_reads_as_a_subtraction() -> void:
	var result := _roll("SAVE", [12], {"modifier": -2, "total": 10})
	assert_eq(Tumble.caption(result)["arithmetic"], "12 − 2 = 10")


# --- How many sides to draw. Presentation only; nothing is adjudicated here.

func test_sides_are_read_off_the_expression() -> void:
	assert_eq(Tumble.dice_sides("1d20"), 20)
	assert_eq(Tumble.dice_sides("2d6"), 6)
	assert_eq(Tumble.dice_sides("d8"), 8)

func test_an_unreadable_expression_draws_a_d20() -> void:
	assert_eq(Tumble.dice_sides("nonsense"), 20)
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — `res://dice/tumble.gd` does not exist.

- [ ] **Step 3: Write `tumble.gd`**

Create `godot/dice/tumble.gd`:

```gdscript
class_name Tumble
extends RefCounted

## The dice animation, as pure data.
##
## Nothing here draws, plays a sound, or touches Table — it turns "this roll, this many
## milliseconds in" into positions, faces and opacities. Keeping it separate is what makes the
## one rule below checkable by reading it.
##
## [b]The rule: the server decides, the animation displays.[/b] Tumbling faces are decorative
## noise; the moment a die settles it shows [code]result.faces[i][/code] and nothing else.
## Physics never produces a value here (invariant #1).
##
## Ported from client/src/dice/tumble.ts. Every constant is already tuned — AGENTS.md §Dice
## records what each one bought. Do not renegotiate them here.

# ---- Layout, in tray units.

const TRAY_WIDTH := 232.0
const TRAY_HEIGHT := 76.0
const DISPLAY_SCALE := 3.0

const DIE_RADIUS := 19.0
const DIE_GAP := 8.0
const FIRST_DIE_X := 30.0
const REST_Y := 38.0

# ---- Timing, in milliseconds.

## Dice are heard before they are seen: the rattle plays over an empty tray.
const WIND_UP_MS := 240
const FLIGHT_MS := 820
## Dice landing together is a thud; landing apart is a clatter.
const LAND_STAGGER_MS := 130
const BOUNCE_MS := 280
const FADE_IN_MS := 180

const FADE_OUT_MS := 420
## How long the tray waits for narration that never comes.
const HOLD_MS := 9000
## The same wait, in combat, where the next thing to happen is the next roll.
const COMBAT_HOLD_MS := 3000

## The pause between a result becoming readable and the blow that follows it. Attacks only —
## a skill check's consequence is narration, which takes seconds to arrive on its own.
const IMPACT_BEAT_MS := 650

const LOB := 10.0
const BOUNCE_HEIGHT := 12.0
## Whole turns, so the flight ease converges on upright rather than an arbitrary angle.
const SPIN := PI * 8.0

const TONE_COLOR := {
	"crit": Color("f0d67a"),
	"good": Color("9ec46a"),
	"bad": Color("c0736a"),
	"fumble": Color("c04a4a"),
	"neutral": Color("a99e8e"),
}

const PURPOSE_LABEL := {
	"ATTACK": "ATTACK",
	"SAVE": "SAVING THROW",
	"SKILL_CHECK": "SKILL CHECK",
	"DAMAGE": "DAMAGE",
	"INITIATIVE": "INITIATIVE",
}


## When die [param index] stops moving and commits to its face.
static func land_at(index: int) -> int:
	return WIND_UP_MS + FLIGHT_MS + index * LAND_STAGGER_MS


## When the total becomes legible. Narration is held until this moment — the dice decide, then
## the DM speaks, never the other way round.
static func reveal_at(die_count: int) -> int:
	return land_at(maxi(0, die_count - 1)) + BOUNCE_MS + 120


## "Do not animate every roll." Purpose decides, not who rolled: an incoming attack is the
## tensest die in the game and the goblin throws it.
static func is_dramatic(result: Dictionary) -> bool:
	var purpose: String = result["request"]["purpose"]
	# Initiative is every combatant at once and the tray throws one roll at a time. Damage is
	# the consequence, not the question — animating it makes one swing read as two.
	return purpose != "INITIATIVE" and purpose != "DAMAGE"


## @param elapsed    milliseconds since the roll arrived
## @param dismiss_at milliseconds at which the fade-out starts
static func sample(result: Dictionary, elapsed: int, dismiss_at: int) -> Dictionary:
	var request: Dictionary = result["request"]
	var faces: Array = result["faces"]
	var sides := dice_sides(request["dice"])
	var count := faces.size()
	var keeps_one: bool = request["advantage"] != "NORMAL"

	var dice: Array[Dictionary] = []
	for i in count:
		var flight := float(FLIGHT_MS + i * LAND_STAGGER_MS)
		var p := clampf((elapsed - WIND_UP_MS) / flight, 0.0, 1.0)
		var settled := elapsed >= land_at(i)

		var rest_x := FIRST_DIE_X + i * (DIE_RADIUS * 2.0 + DIE_GAP)
		var start_x := TRAY_WIDTH + DIE_RADIUS * 2.0
		var start_y := -DIE_RADIUS

		var since := elapsed - land_at(i)
		var bouncing := since >= 0 and since < BOUNCE_MS
		var b := (since / float(BOUNCE_MS)) if bouncing else 0.0

		var bounce_y := 0.0
		var bounce_spin := 0.0
		if bouncing:
			bounce_y = BOUNCE_HEIGHT * absf(sin(b * PI * 2.0)) * (1.0 - b)
			bounce_spin = 0.22 * sin(b * PI * 3.0) * (1.0 - b)

		dice.append({
			"x": start_x + (rest_x - start_x) * _ease_out_cubic(p),
			"y": start_y + (REST_Y - start_y) * (p * p) - LOB * sin(PI * p) - bounce_y,
			"rotation": SPIN * _ease_out_cubic(p) + bounce_spin,
			# The one line that matters. Before it settles the face is noise; after, it is the
			# server's value. There is no path by which the animation can invent a result.
			"face": int(faces[i]) if settled else _tumbling_face(i, elapsed, sides),
			"sides": sides,
			"radius": DIE_RADIUS,
			# The server puts the kept die first, so every later die on advantage is a discard.
			"discarded": keeps_one and i > 0,
			"settled": settled,
		})

	var fade_out := 1.0
	if elapsed > dismiss_at:
		fade_out = 1.0 - clampf((elapsed - dismiss_at) / float(FADE_OUT_MS), 0.0, 1.0)
	var opacity := minf(clampf(elapsed / float(FADE_IN_MS), 0.0, 1.0), fade_out)

	return {
		"dice": dice,
		"reveal": clampf((elapsed - reveal_at(count)) / 260.0, 0.0, 1.0),
		"opacity": opacity,
		"finished": opacity <= 0.0,
	}


## Wording, shared by the tray and the transcript log so the two cannot drift.
static func caption(result: Dictionary) -> Dictionary:
	var request: Dictionary = result["request"]
	var faces: Array = result["faces"]
	var counted: Array = faces if request["advantage"] == "NORMAL" else faces.slice(0, 1)

	var label: String = PURPOSE_LABEL.get(request["purpose"], request["purpose"])
	# Nulls are nulls. `if request.skill:` would also be false for an empty string.
	if request["skill"] != null:
		label = "%s CHECK" % request["skill"]

	var target := ""
	if request["dc"] != null:
		var word := "AC" if request["purpose"] == "ATTACK" else "DC"
		target = "%s %d" % [word, int(request["dc"])]

	var parts: Array[String] = []
	for face in counted:
		parts.append(str(int(face)))
	var sum := " + ".join(parts)

	var modifier: int = request["modifier"]
	var arithmetic := sum
	if counted.size() != 1 or modifier != 0:
		var sign_text := ""
		if modifier != 0:
			sign_text = " %s %d" % ["−" if modifier < 0 else "+", absi(modifier)]
		arithmetic = "%s%s = %d" % [sum, sign_text, int(result["total"])]

	# Damage and initiative are adjudicated as SUCCESS because every roll needs an outcome, but
	# printing that next to "5 + 2 = 7" says nothing and reads as though it could have failed.
	var decided: bool = request["purpose"] != "DAMAGE" and request["purpose"] != "INITIATIVE"

	return {
		"label": label,
		"target": target,
		"arithmetic": arithmetic,
		"outcome": _pretty(result["outcome"]) if decided else "",
		"tone": _tone_of(result["outcome"]) if decided else "neutral",
	}


## Presentation only — how many sides to draw. Adjudication never happens on this side.
static func dice_sides(expression: String) -> int:
	var re := RegEx.create_from_string("^\\s*\\d*\\s*[dD]\\s*(\\d+)")
	var found := re.search(expression)
	return int(found.get_string(1)) if found != null else 20


static func _pretty(outcome: String) -> String:
	if outcome == "CRIT":
		return "CRITICAL"
	if outcome == "CRIT_FAIL":
		return "FUMBLE"
	return outcome


static func _tone_of(outcome: String) -> String:
	match outcome:
		"CRIT": return "crit"
		"CRIT_FAIL": return "fumble"
		"HIT", "SUCCESS": return "good"
		"MISS", "FAILURE": return "bad"
		_: return "neutral"


## A stable pseudo-random face for a given die at a given instant. Stable matters: derived from
## the clock rather than randi() so a dropped frame does not make the dice stutter.
static func _tumbling_face(index: int, elapsed: int, sides: int) -> int:
	var step := elapsed / 52  # ~19 changes a second: blurred, but still dice
	return 1 + (_hash(index * 8191 + step) % sides)


static func _hash(n: int) -> int:
	var h := (n ^ 0x9e3779b9) * 0x85ebca6b & 0xffffffff
	h ^= h >> 13
	h = h * 0xc2b2ae35 & 0xffffffff
	return (h ^ (h >> 16)) & 0xffffffff


static func _ease_out_cubic(t: float) -> float:
	return 1.0 - pow(1.0 - t, 3.0)
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures. If `test_the_first_die_lands_at_wind_up_plus_flight` fails, the constants have been mistyped — check them against `client/src/dice/tumble.ts` rather than adjusting the test.

- [ ] **Step 5: Commit**

```bash
git add godot/dice/tumble.gd godot/test/test_tumble.gd && git commit -m "Port the dice maths, with the rule that the server decides intact"
```

---

### Task 5: `Clock` — the presentation queue

**Files:**
- Create: `godot/audio/voice_backend.gd`
- Create: `godot/autoload/clock.gd` *(replaces the Task 3 stub)*
- Create: `godot/test/test_clock.gd`

**Interfaces:**
- Consumes: `Tumble` (not directly — the callers do the arithmetic).
- Produces: autoload `Clock` with `speak(line: Dictionary, reveal: Callable)`, `mark(reveal: Callable)`, `hold(ms: int, reveal: Callable)`, `silence()`, `silence_now()`, `set_enabled(on: bool)`, `is_enabled() -> bool`, `use_backend(backend: VoiceBackend)`. And `VoiceBackend`, a `RefCounted` with `signal finished`, `speak(line: Dictionary)`, `stop()`, `prime(line: Dictionary)`.

**This is the load-bearing port.** Read `client/src/audio/narration.ts` in full before starting, including every comment — each one records a bug found in play. `AGENTS.md` §Voice is the other half.

The one structural difference from the TypeScript: `VoiceBackend.speak` does not return an awaitable, it emits a `finished` signal. A GDScript function that sometimes awaits and sometimes does not is a coroutine only sometimes, and awaiting its return value is then ambiguous. A signal is unambiguous. **Backends must emit `finished` deferred, never synchronously inside `speak`** — a signal emitted before the caller has reached its `await` is a line that hangs the queue forever.

- [ ] **Step 1: Write the seam**

Create `godot/audio/voice_backend.gd`:

```gdscript
class_name VoiceBackend
extends RefCounted

## The voice seam.
##
## Implementations: the HTTP MP3 from this project's server, and the OS synthesiser (Task 11).
## Both satisfy this contract, so Clock never learns which one is speaking.

## Emitted exactly once per [method speak] — on end, on error, or on cancel. Never twice, never
## zero times, and [b]never synchronously inside speak[/b]: the caller awaits this signal, and
## one emitted before it has reached the await stalls every line behind it forever. Emit with
## [code]finished.emit.call_deferred()[/code] if the answer is already known.
signal finished

## Say one line. Must always lead to exactly one [signal finished].
func speak(_line: Dictionary) -> void:
	finished.emit.call_deferred()

## Cut the line in the air, settling it. For errors only — Clock decides when this is right.
func stop() -> void:
	pass

## Start preparing a line that is coming but is not being spoken yet. A hint, not an
## instruction; a backend with nothing to prepare leaves this alone.
func prime(_line: Dictionary) -> void:
	pass
```

- [ ] **Step 2: Write the failing tests**

Create `godot/test/test_clock.gd`:

```gdscript
extends GutTest

## A backend that finishes when told, so the tests can hold a line in the air on purpose.
class HeldVoice extends VoiceBackend:
	var spoken: Array[String] = []
	var primed: Array[String] = []
	var stopped := 0
	var _speaking := false

	func speak(line: Dictionary) -> void:
		spoken.append(line["text"])
		_speaking = true

	func prime(line: Dictionary) -> void:
		primed.append(line["text"])

	func stop() -> void:
		stopped += 1
		release()

	## Let the line in the air finish.
	func release() -> void:
		if _speaking:
			_speaking = false
			finished.emit.call_deferred()


var voice: HeldVoice
var log: Array[String]


func before_each() -> void:
	voice = HeldVoice.new()
	log = []
	Clock.use_backend(voice)
	Clock.set_enabled(true)
	Clock.silence_now()
	await get_tree().process_frame


func _note(what: String) -> Callable:
	return func() -> void: log.append(what)


func test_marks_run_in_arrival_order() -> void:
	Clock.mark(_note("one"))
	Clock.mark(_note("two"))
	Clock.mark(_note("three"))
	await wait_frames(3)
	assert_eq(log, ["one", "two", "three"])


func test_a_line_reveals_when_the_voice_reaches_it_not_when_it_arrives() -> void:
	Clock.speak({"speakerId": "narrator", "text": "first"}, _note("first"))
	Clock.speak({"speakerId": "narrator", "text": "second"}, _note("second"))
	await wait_frames(2)
	# The first line is still in the air; the second must not have revealed.
	assert_eq(log, ["first"])
	voice.release()
	await wait_frames(3)
	assert_eq(log, ["first", "second"])


func test_a_hold_keeps_the_floor_for_its_whole_duration() -> void:
	Clock.hold(300, _note("dice"))
	Clock.mark(_note("narration"))
	await wait_frames(3)
	assert_eq(log, ["dice"], "narration must not arrive while the die is in the air")
	await wait_seconds(0.4)
	assert_eq(log, ["dice", "narration"])


func test_silence_drops_the_queue_but_still_reveals_every_dropped_line() -> void:
	# Interrupting stops the speech, not the record. Text that vanished from the transcript
	# because nobody got round to saying it would be a bug.
	Clock.speak({"speakerId": "narrator", "text": "in the air"}, _note("in the air"))
	await wait_frames(2)
	Clock.speak({"speakerId": "narrator", "text": "dropped"}, _note("dropped"))
	Clock.speak({"speakerId": "narrator", "text": "also dropped"}, _note("also dropped"))
	Clock.silence()
	assert_eq(log, ["in the air", "dropped", "also dropped"])
	assert_eq(voice.spoken, ["in the air"], "dropped lines are revealed, never spoken")


func test_silence_lets_the_line_in_the_air_finish() -> void:
	Clock.speak({"speakerId": "narrator", "text": "mid-sentence"}, _note("mid-sentence"))
	await wait_frames(2)
	Clock.silence()
	assert_eq(voice.stopped, 0, "cutting a voice mid-word is for errors only")


func test_silence_now_cuts_dead() -> void:
	Clock.speak({"speakerId": "narrator", "text": "mid-sentence"}, _note("mid-sentence"))
	await wait_frames(2)
	Clock.silence_now()
	assert_eq(voice.stopped, 1)


func test_a_turn_queued_during_an_interruption_is_still_spoken() -> void:
	# The regression this whole test file exists for. A silence() landing while a line is in
	# the air leaves the next turn's lines behind a drain loop that has already given up on
	# them. In TypeScript a `finally` re-enters the loop; GDScript has no finally, so the
	# re-entry is written by hand — and without it, the first turn after every interruption is
	# silently lost.
	Clock.speak({"speakerId": "narrator", "text": "old turn"}, _note("old turn"))
	await wait_frames(2)
	Clock.silence()
	Clock.speak({"speakerId": "narrator", "text": "new turn"}, _note("new turn"))
	voice.release()
	await wait_frames(4)
	assert_has(voice.spoken, "new turn", "the new turn must still reach the voice")


func test_with_the_voice_off_lines_still_queue_and_still_reveal() -> void:
	# `enabled` decides whether it is spoken, never whether it is queued: turning the narrator
	# off changes how long things take, never what order they happen in.
	Clock.set_enabled(false)
	Clock.speak({"speakerId": "narrator", "text": "one"}, _note("one"))
	Clock.mark(_note("two"))
	Clock.speak({"speakerId": "narrator", "text": "three"}, _note("three"))
	await wait_frames(4)
	assert_eq(log, ["one", "two", "three"])
	assert_eq(voice.spoken, [], "nothing is said with the narrator off")


func test_holds_are_honoured_with_the_voice_off() -> void:
	# The animation runs either way, so the die still takes the floor.
	Clock.set_enabled(false)
	Clock.hold(300, _note("dice"))
	Clock.mark(_note("after"))
	await wait_frames(3)
	assert_eq(log, ["dice"])
	await wait_seconds(0.4)
	assert_eq(log, ["dice", "after"])


func test_an_empty_line_is_a_marker_not_a_silence_to_sit_through() -> void:
	Clock.speak({"speakerId": "narrator", "text": "   "}, _note("blank"))
	Clock.mark(_note("after"))
	await wait_frames(3)
	assert_eq(log, ["blank", "after"])
	assert_eq(voice.spoken, [])


func test_exactly_one_line_is_primed_ahead() -> void:
	# Far enough that synthesis finishes while the previous line plays, near enough that an
	# interrupted turn wastes at most one line of a metered API.
	Clock.speak({"speakerId": "narrator", "text": "one"}, _note("one"))
	Clock.speak({"speakerId": "narrator", "text": "two"}, _note("two"))
	Clock.speak({"speakerId": "narrator", "text": "three"}, _note("three"))
	await wait_frames(2)
	assert_eq(voice.primed, ["two"])
```

- [ ] **Step 3: Run them and watch them fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — `Clock` has no method `use_backend`.

- [ ] **Step 4: Write `Clock`**

Replace `godot/autoload/clock.gd` entirely:

```gdscript
extends Node

## The ordered audio queue, and the clock the rest of the game runs on.
##
## Narration streams a sentence at a time, and two sentences talking over each other is the
## single worst thing a spoken DM can do. Lines are spoken strictly in arrival order, one at a
## time, and a new turn drops whatever is still queued from the last one.
##
## It also [b]paces the text[/b]. The model streams a whole turn in about three seconds and the
## voice takes twenty to say it, so text that appears on arrival has the player speed-reading
## ahead of the narrator. Each line reveals itself as it starts being spoken.
##
## It is also the clock for [b]everything else the DM does[/b]. Narration, dice, hit point
## changes and mode switches all queue here in arrival order and are released when the voice
## reaches them. Before this existed, only the transcript was paced by the voice and the rest of
## the world ran on timers — invisible while the browser's own synthesiser read, and obvious the
## moment a real voice was three times slower: the goblin was landing blows twenty seconds
## before the narrator got round to saying it had appeared.
##
## Ported from client/src/audio/narration.ts.

var _backend: VoiceBackend = null
var _enabled := true

## Each entry: { "line": Dictionary or null, "reveal": Callable, "hold_ms": int }
var _pending: Array[Dictionary] = []
var _draining := false

## Bumped by [method silence]. The drain loop checks it before taking another line, which is how
## a cancelled turn stops without leaving a half-spoken sentence in the pipe.
var _generation := 0


func use_backend(backend: VoiceBackend) -> void:
	_backend = backend


func set_enabled(on: bool) -> void:
	_enabled = on
	if not on:
		silence()


func is_enabled() -> bool:
	return _enabled


## Queue a line. [param reveal] runs when the voice reaches it.
## An empty line is a marker, not silence to sit through.
func speak(line: Dictionary, reveal: Callable) -> void:
	if String(line.get("text", "")).strip_edges().is_empty():
		mark(reveal)
		return
	_pending.append({"line": line, "reveal": reveal, "hold_ms": 0})
	_drain()


## Queue a callback with no speech, so it lands in sequence rather than ahead of the voice.
##
## Deliberately still queued when the voice is off. The queue is the ordering, not the audio:
## turning the narrator off must change how long things take, never what order they happen in.
func mark(reveal: Callable) -> void:
	_pending.append({"line": null, "reveal": reveal, "hold_ms": 0})
	_drain()


## Queue a callback and then hold the queue open for a while afterwards.
##
## For a die: it takes the floor for as long as it is in the air, and the narration that commits
## to its result must not arrive before it lands. The hold is a timer rather than a signal from
## the tray, because if the tray never mounts the queue must still move on.
func hold(ms: int, reveal: Callable) -> void:
	_pending.append({"line": null, "reveal": reveal, "hold_ms": ms})
	_drain()


## Drop everything still queued, letting the line already in the air finish.
##
## Cutting a voice off mid-word is jarring in a way that cutting between sentences is not, and
## the player starting their next turn is not an emergency. A person interrupted finishes their
## sentence; so does this.
##
## Dropped lines are still revealed. The player interrupted the [i]speech[/i], not the record —
## losing text from the transcript because nobody got round to saying it would be a bug.
func silence() -> void:
	_generation += 1
	var dropped := _pending.duplicate()
	_pending.clear()
	for utterance in dropped:
		utterance["reveal"].call()


## Stop dead, mid-word. For errors, where continuing to talk would be worse than the cut.
func silence_now() -> void:
	silence()
	if _backend != null:
		_backend.stop()


func _drain() -> void:
	if _draining:
		return
	_draining = true

	var mine := _generation
	if _backend == null:
		_backend = VoiceBackend.new()

	while not _pending.is_empty() and mine == _generation:
		var utterance: Dictionary = _pending.pop_front()
		utterance["reveal"].call()

		# Start the next line's synthesis before waiting on this one, so a remote voice spends
		# its round trip during playback instead of in the gap after it. One ahead only.
		for queued in _pending:
			if queued["line"] != null:
				_backend.prime(queued["line"])
				break

		# `_enabled` decides whether it is spoken, never whether it is queued.
		if utterance["line"] != null and _enabled:
			_backend.speak(utterance["line"])
			await _backend.finished

		if utterance["hold_ms"] > 0:
			# `ignore_time_scale` last: a paused or slowed tree must not stall the dice gate.
			await get_tree().create_timer(
					utterance["hold_ms"] / 1000.0, true, false, true).timeout

	_draining = false

	# The line the TypeScript gets from `finally`, written out by hand because GDScript has no
	# such thing. A silence() during the await above leaves the next turn's lines queued behind
	# a loop that has already given up on them; without this they are never spoken at all.
	if not _pending.is_empty():
		_drain()
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

If `test_a_turn_queued_during_an_interruption_is_still_spoken` fails, the re-entry at the bottom of `_drain` is missing or is being `await`ed. It must be called without `await` — awaiting it nests the loop inside itself.

If any test hangs, a backend emitted `finished` synchronously inside `speak`. Emit it deferred.

- [ ] **Step 6: Commit**

```bash
git add godot/audio/voice_backend.gd godot/autoload/clock.gd godot/test/test_clock.gd && git commit -m "Port the presentation queue, including the interruption it used to lose a turn to"
```

---

### Task 6: `Net` — the socket

**Files:**
- Create: `godot/autoload/net.gd` *(replaces the Task 3 stub)*
- Create: `godot/test/test_net.gd`

**Interfaces:**
- Consumes: `Link.ws_url()`.
- Produces: autoload `Net` with signals `hello(demo_mode: bool, voice: bool, dm: bool)`, `scene(state: Dictionary)`, `diffs(list: Array)`, `narration(segment: Dictionary)`, `narration_end()`, `roll(result: Dictionary)`, `error(message: String)`, `connected()`, `disconnected(reason: String)`; and methods `send(message: Dictionary)`, `begin()`, `restart()`, `free_text(actor_id: String, text: String)`, `move_to(actor_id: String, x: int, y: int)`, `attack(actor_id: String, target_id: String)`, `end_turn(actor_id: String)`, `debug(message: Dictionary)`, `dispatch(message: Dictionary)`.

`dispatch` is public so the tests can push wire fixtures through it without a server. That is the whole testable surface — the socket itself is exercised by playing the game.

- [ ] **Step 1: Write the failing tests**

Create `godot/test/test_net.gd`:

```gdscript
extends GutTest

var seen: Array[Dictionary]


func before_each() -> void:
	seen = []
	Net.scene.connect(func(s): seen.append({"kind": "scene", "value": s}))
	Net.diffs.connect(func(d): seen.append({"kind": "diffs", "value": d}))
	Net.roll.connect(func(r): seen.append({"kind": "roll", "value": r}))
	Net.narration.connect(func(s): seen.append({"kind": "narration", "value": s}))
	Net.narration_end.connect(func(): seen.append({"kind": "narrationEnd", "value": null}))
	Net.error.connect(func(m): seen.append({"kind": "error", "value": m}))
	Net.hello.connect(func(d, v, x): seen.append({"kind": "hello",
		"value": {"demoMode": d, "voice": v, "dm": x}}))


func after_each() -> void:
	for connection in [Net.scene, Net.diffs, Net.roll, Net.narration, Net.narration_end,
			Net.error, Net.hello]:
		for c in connection.get_connections():
			connection.disconnect(c["callable"])


func test_hello_carries_all_three_flags() -> void:
	Net.dispatch({"type": "hello", "demoMode": true, "voice": false, "dm": true})
	assert_eq(seen[0]["kind"], "hello")
	assert_eq(seen[0]["value"]["dm"], true)
	assert_eq(seen[0]["value"]["voice"], false)


func test_an_old_server_without_the_dm_field_is_read_as_no_dm() -> void:
	Net.dispatch({"type": "hello", "demoMode": false, "voice": false})
	assert_eq(seen[0]["value"]["dm"], false)


func test_a_scene_arrives_whole() -> void:
	Net.dispatch({"type": "scene", "scene": {"roomId": "crypt", "width": 12}})
	assert_eq(seen[0]["kind"], "scene")
	assert_eq(seen[0]["value"]["roomId"], "crypt")


func test_diffs_arrive_as_one_batch() -> void:
	Net.dispatch({"type": "diffs", "diffs": [
		{"kind": "EntityMoved", "entityId": "goblin"},
		{"kind": "StatChanged", "entityId": "fighter"},
	]})
	assert_eq(seen.size(), 1, "one signal per batch, never one per diff")
	assert_eq(seen[0]["value"].size(), 2)


func test_narration_end_has_no_payload() -> void:
	Net.dispatch({"type": "narrationEnd"})
	assert_eq(seen[0]["kind"], "narrationEnd")


func test_a_roll_keeps_its_faces_as_a_list() -> void:
	# Invariant #5. Never collapsed to a total before the tray.
	Net.dispatch({"type": "roll", "result": {"faces": [18, 4], "total": 23}})
	assert_eq(seen[0]["value"]["faces"], [18, 4])


func test_an_unknown_message_type_is_ignored_not_fatal() -> void:
	Net.dispatch({"type": "somethingFromTheFuture"})
	assert_eq(seen, [])


func test_unparseable_json_is_ignored_not_fatal() -> void:
	Net.receive("{ this is not json")
	assert_eq(seen, [])
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — `Net` has no signal `hello`.

- [ ] **Step 3: Write `Net`**

Replace `godot/autoload/net.gd` entirely:

```gdscript
extends Node

## One connection for the whole game. The server is authoritative (invariant #1) — everything
## arriving here is applied as told, never recomputed.
##
## Does not apply diffs, speak, or draw. It turns bytes into signals and back.
##
## WebSocketPeer has no signals of its own, so it is polled from _process. That is the Godot
## idiom and it is also what keeps the 100ms click-to-move budget: a click sends on the same
## frame it was made.

signal hello(demo_mode: bool, voice: bool, dm: bool)
signal scene(state: Dictionary)
signal diffs(list: Array)
signal narration(segment: Dictionary)
signal narration_end()
signal roll(result: Dictionary)
signal error(message: String)
signal connected()
signal disconnected(reason: String)

const RECONNECT_DELAY_SECONDS := 1.0

var _socket := WebSocketPeer.new()
var _state := WebSocketPeer.STATE_CLOSED
var _retry_at := 0.0
var _want_connection := false


func _ready() -> void:
	open()


func open() -> void:
	_want_connection = true
	_retry_at = 0.0


func _process(_delta: float) -> void:
	if not _want_connection:
		return

	if _state == WebSocketPeer.STATE_CLOSED:
		# The server is a terminal process that gets restarted a dozen times an evening. Quietly
		# reattaching is the loop a developer actually lives in; a modal that has to be dismissed
		# every time is not.
		var now := Time.get_ticks_msec() / 1000.0
		if now < _retry_at:
			return
		_retry_at = now + RECONNECT_DELAY_SECONDS
		var err := _socket.connect_to_url(Link.ws_url())
		if err != OK:
			return

	_socket.poll()
	var next := _socket.get_ready_state()

	if next != _state:
		if next == WebSocketPeer.STATE_OPEN:
			connected.emit()
		elif next == WebSocketPeer.STATE_CLOSED and _state != WebSocketPeer.STATE_CLOSED:
			var reason := _socket.get_close_reason()
			push_warning("[net] socket closed: %d %s" % [_socket.get_close_code(), reason])
			disconnected.emit(reason)
		_state = next

	while _socket.get_ready_state() == WebSocketPeer.STATE_OPEN \
			and _socket.get_available_packet_count() > 0:
		receive(_socket.get_packet().get_string_from_utf8())


## Turn one raw frame into a signal. Public so tests can push fixtures without a server.
func receive(raw: String) -> void:
	var parsed = JSON.parse_string(raw)
	if typeof(parsed) != TYPE_DICTIONARY:
		push_error("[net] unparseable server message: " + raw)
		return
	dispatch(parsed)


func dispatch(message: Dictionary) -> void:
	match String(message.get("type", "")):
		"hello":
			# `get` with a default, because the field is additive and an older server predates
			# it. Never `if message.dm:` — that is also false for an explicit false.
			hello.emit(
				bool(message.get("demoMode", false)),
				bool(message.get("voice", false)),
				bool(message.get("dm", false)))
		"scene":
			scene.emit(message["scene"])
		"diffs":
			# One signal per batch. Table applies the whole list, then emits once.
			diffs.emit(message["diffs"])
		"narration":
			narration.emit(message["segment"])
		"narrationEnd":
			narration_end.emit()
		"roll":
			roll.emit(message["result"])
		"error":
			error.emit(String(message.get("message", "")))
		_:
			# A message type this client does not know is not a crash. The server and the two
			# clients drift by exactly one field at a time during the bridge.
			pass


func send(message: Dictionary) -> void:
	if _socket.get_ready_state() != WebSocketPeer.STATE_OPEN:
		push_warning("[net] dropped message, socket not open: " + str(message))
		return
	_socket.send_text(JSON.stringify(message))


# ---- The client messages, named rather than spelled out at every call site.

func begin() -> void:
	send({"type": "begin"})

func restart() -> void:
	send({"type": "restart"})

func free_text(actor_id: String, text: String) -> void:
	send({"type": "freeText", "actorId": actor_id, "text": text})

func move_to(actor_id: String, x: int, y: int) -> void:
	send({"type": "moveTo", "actorId": actor_id, "x": x, "y": y})

func attack(actor_id: String, target_id: String) -> void:
	send({"type": "attack", "actorId": actor_id, "targetId": target_id})

func end_turn(actor_id: String) -> void:
	send({"type": "endTurn", "actorId": actor_id})

## The seven debug messages. They exist so the feel can be tuned with no model, no key and no
## latency in the path — see Task 13.
func debug(message: Dictionary) -> void:
	send(message)
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add godot/autoload/net.gd godot/test/test_net.gd && git commit -m "Speak the wire protocol from Godot, one signal per message"
```

---

### Task 7: `Table` — the scene and its diffs

**Files:**
- Create: `godot/autoload/table.gd` *(replaces the Task 3 stub)*
- Create: `godot/test/test_table.gd`

**Interfaces:**
- Consumes: `Net` signals, `Clock.mark`.
- Produces: autoload `Table` holding `scene`, `mode`, `connected`, `demo_mode`, `dm_configured`, `started`; signals `scene_changed()`, `mode_changed(mode: String)`, `entity_added(entity: Dictionary)`, `prop_revealed(prop: Dictionary)`, `entity_moved(entity_id: String, from: Vector2i, to: Vector2i)`; methods `set_scene(state)`, `apply_diffs(list)`, `entity(id) -> Dictionary`, `reset()`. Task 8 adds the transcript, rolls and combat to the same file.

Port of the scene half of `client/src/store.ts`. Read it first.

- [ ] **Step 1: Write the failing tests**

Create `godot/test/test_table.gd`:

```gdscript
extends GutTest

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [{"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 0,
		"hidden": false}],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}

const GOBLIN := {"id": "goblin", "kind": "goblin", "name": "Vessk", "x": 8, "y": 6,
	"hp": 7, "maxHp": 7, "isPlayerControlled": false}


func before_each() -> void:
	Table.reset()
	Clock.silence_now()
	Table.set_scene(CRYPT.duplicate(true))
	await get_tree().process_frame


func test_the_scene_arrives_whole() -> void:
	assert_eq(Table.scene["roomId"], "crypt")
	assert_eq(Table.mode, "EXPLORATION")


func test_mode_rides_with_the_scene_so_a_reconnect_lands_mid_fight_intact() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	Table.set_scene(fighting)
	assert_eq(Table.mode, "COMBAT")


func test_a_spawned_entity_joins_the_scene() -> void:
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 2)
	assert_eq(Table.entity("goblin")["name"], "Vessk")


func test_spawning_the_same_id_twice_replaces_rather_than_duplicates() -> void:
	# M0's goblin has a hardcoded id, so a second spawn replaces the first server-side.
	# Appending here would leave a phantom behind.
	var moved := GOBLIN.duplicate()
	moved["x"] = 9
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	Table.apply_diffs([{"kind": "EntityAdded", "entity": moved}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 2)
	assert_eq(Table.entity("goblin")["x"], 9)


func test_a_moved_entity_keeps_everything_but_its_square() -> void:
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "fighter",
		"fromX": 2, "fromY": 2, "x": 4, "y": 5}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["x"], 4)
	assert_eq(Table.entity("fighter")["y"], 5)
	assert_eq(Table.entity("fighter")["hp"], 12)


func test_hit_points_change_and_nothing_else_does() -> void:
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "hp", "from": 12, "to": 5}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["hp"], 5)


func test_a_stat_that_is_not_hp_is_ignored() -> void:
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "morale", "from": 1, "to": 0}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["hp"], 12)


func test_a_removed_entity_leaves_the_scene() -> void:
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	Table.apply_diffs([{"kind": "EntityRemoved", "entityId": "goblin"}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 1)


func test_a_revealed_prop_joins_the_room() -> void:
	var alcove := {"id": "alcove", "type": "ALCOVE", "x": 9, "y": 3, "rotation": 0,
		"hidden": false}
	Table.apply_diffs([{"kind": "PropRevealed", "prop": alcove}])
	await wait_frames(2)
	assert_eq(Table.scene["props"].size(), 2)


func test_revealing_the_same_prop_twice_replaces_rather_than_duplicates() -> void:
	var tomb := {"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 1,
		"hidden": false}
	Table.apply_diffs([{"kind": "PropRevealed", "prop": tomb}])
	await wait_frames(2)
	assert_eq(Table.scene["props"].size(), 1)
	assert_eq(Table.scene["props"][0]["rotation"], 1)


func test_the_mode_flips() -> void:
	Table.apply_diffs([{"kind": "ModeChanged", "mode": "COMBAT"}])
	await wait_frames(2)
	assert_eq(Table.mode, "COMBAT")


func test_a_batch_emits_exactly_one_change() -> void:
	# Godot's idiom is mutate-and-emit, and a signal per field would have the world rebuilding
	# halfway through a batch that has moved a token but not yet changed its hit points.
	var changes := 0
	var counter := func() -> void: changes += 1
	Table.scene_changed.connect(counter)
	Table.apply_diffs([
		{"kind": "EntityAdded", "entity": GOBLIN},
		{"kind": "EntityMoved", "entityId": "goblin", "fromX": 8, "fromY": 6, "x": 7, "y": 6},
		{"kind": "StatChanged", "entityId": "goblin", "stat": "hp", "from": 7, "to": 3},
	])
	await wait_frames(2)
	Table.scene_changed.disconnect(counter)
	assert_eq(changes, 1)


func test_diffs_wait_for_the_clock() -> void:
	# A hit point bar that empties while the attack die is still in the air has answered the
	# question the die was asking.
	Clock.hold(400, func() -> void: pass)
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "hp", "from": 12, "to": 5}])
	await wait_frames(3)
	assert_eq(Table.entity("fighter")["hp"], 12, "the die is still in the air")
	await wait_seconds(0.5)
	assert_eq(Table.entity("fighter")["hp"], 5)


func test_with_the_clock_empty_a_diff_applies_on_the_spot() -> void:
	# Every move and every reveal the player makes for themselves. The 100ms budget lives here.
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "fighter",
		"fromX": 2, "fromY": 2, "x": 3, "y": 2}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["x"], 3)


func test_an_unknown_entity_id_is_ignored_not_fatal() -> void:
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "ghost",
		"fromX": 0, "fromY": 0, "x": 1, "y": 1}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 1)
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — `Table` has no method `reset`.

- [ ] **Step 3: Write the scene half of `Table`**

Replace `godot/autoload/table.gd` entirely:

```gdscript
extends Node

## ALL game state lives here (invariant #3). World, Chrome and SFX all subscribe. Nothing
## game-related may live in a Control's or a Node3D's locals — that is how the renderer ends up
## holding a different world from the one the transcript is describing.
##
## Ported from client/src/store.ts.
##
## Two rules that are not obvious and are load-bearing:
##
## [b]One signal per batch.[/b] The TypeScript is written in Zustand's immutable style, so a
## subscriber always sees one consistent picture. Godot's idiom is mutate-and-emit, and a signal
## per field would have the world rebuilding halfway through a batch that has moved a token but
## not yet changed its hit points. Apply the whole list, then emit once.
##
## [b]Every creature is addressed by its actorId from the wire[/b] (invariant #2). Never a
## literal "fighter", never "the first entity" — the party becomes a list of real people at the
## multiplayer milestone.

signal scene_changed()
signal mode_changed(mode: String)
signal entity_added(entity: Dictionary)
signal entity_moved(entity_id: String, from: Vector2i, to: Vector2i)
signal prop_revealed(prop: Dictionary)
signal entity_died(entity_id: String)

var connected := false
var demo_mode := false
var dm_configured := false
var scene: Dictionary = {}
var mode := "EXPLORATION"

## Whether the player has started the session. Gates a real event: until someone has clicked,
## the server has not been asked to narrate the opening.
var started := false


func reset() -> void:
	scene = {}
	mode = "EXPLORATION"
	started = false
	_reset_talk()   # the transcript half, added in Task 8


## Mode rides with the scene rather than being left to the diff that changed it: a client that
## connects mid-fight gets one message, and it has to be the whole truth.
func set_scene(state: Dictionary) -> void:
	scene = state
	mode = String(state.get("mode", "EXPLORATION"))
	_adopt_combat_from_scene()   # Task 8
	scene_changed.emit()
	mode_changed.emit(mode)


func entity(id: String) -> Dictionary:
	for e in scene.get("entities", []):
		if e["id"] == id:
			return e
	return {}


func prop(id: String) -> Dictionary:
	for p in scene.get("props", []):
		if p["id"] == id:
			return p
	return {}


## Queued with the narration, in arrival order, and released when the voice reaches it.
##
## A hit point bar that empties while the attack die is still in the air has answered the
## question the die was asking — and one that empties twenty seconds before the narrator says
## the blow was struck is worse still. Both are the same bug: the world moving on a different
## clock from the voice describing it.
##
## When nothing is queued this runs on the spot, which is every move and every reveal the player
## makes for themselves — so click-to-move keeps its 100ms budget.
func apply_diffs(list: Array) -> void:
	Clock.mark(func() -> void: _apply_now(list))


func _apply_now(list: Array) -> void:
	if scene.is_empty():
		return

	# Which side of a mode flip this batch crossed, decided in the loop and acted on after it:
	# the ceremony needs the CombatView, and that arrives in the CombatChanged diff sitting
	# behind the ModeChanged one.
	var opened := false
	var closed := false
	var announcements: Array[Callable] = []

	for diff in list:
		match String(diff["kind"]):
			"EntityAdded":
				var added: Dictionary = diff["entity"]
				var existing := entity(String(added["id"]))
				if existing.is_empty():
					scene["entities"].append(added)
					announcements.append(func() -> void: entity_added.emit(added))
				else:
					# Idempotent by id. A replaced goblin is a debug respawn, not a lid coming
					# off a second time.
					scene["entities"][_index_of_entity(String(added["id"]))] = added
					announcements.append(func() -> void: scene_changed.emit())

			"EntityRemoved":
				var at := _index_of_entity(String(diff["entityId"]))
				if at >= 0:
					scene["entities"].remove_at(at)

			"EntityMoved":
				var moving := entity(String(diff["entityId"]))
				if not moving.is_empty():
					var from := Vector2i(int(diff["fromX"]), int(diff["fromY"]))
					var to := Vector2i(int(diff["x"]), int(diff["y"]))
					moving["x"] = to.x
					moving["y"] = to.y
					var who := String(diff["entityId"])
					announcements.append(func() -> void: entity_moved.emit(who, from, to))

			"StatChanged":
				if String(diff["stat"]) == "hp":
					var hurt := entity(String(diff["entityId"]))
					if not hurt.is_empty():
						hurt["hp"] = int(diff["to"])
						if int(diff["to"]) <= 0 and int(diff["from"]) > 0:
							var who := String(diff["entityId"])
							announcements.append(func() -> void: entity_died.emit(who))

			"ModeChanged":
				var next := String(diff["mode"])
				if mode != next:
					opened = next == "COMBAT"
					closed = next == "EXPLORATION"
				mode = next

			"PropRevealed":
				var revealed: Dictionary = diff["prop"]
				var at_prop := _index_of_prop(String(revealed["id"]))
				if at_prop >= 0:
					scene["props"][at_prop] = revealed
				else:
					scene["props"].append(revealed)
					announcements.append(func() -> void: prop_revealed.emit(revealed))

			"CombatChanged":
				# Replaced wholesale, never merged. The server sends the entire legal picture
				# each time precisely so the client has no chance to hold a half-updated one.
				scene["combat"] = diff["combat"]

	_settle_combat(opened, closed)   # Task 8
	scene_changed.emit()
	if opened or closed:
		mode_changed.emit(mode)
	for announce in announcements:
		announce.call()


func _index_of_entity(id: String) -> int:
	var entities: Array = scene.get("entities", [])
	for i in entities.size():
		if entities[i]["id"] == id:
			return i
	return -1


func _index_of_prop(id: String) -> int:
	var props: Array = scene.get("props", [])
	for i in props.size():
		if props[i]["id"] == id:
			return i
	return -1
```

- [ ] **Step 4: Add the Task 8 placeholders so this compiles**

Append to `godot/autoload/table.gd`:

```gdscript
# ---- Filled in by Task 8. Declared here so Task 7 compiles and runs on its own.

func _reset_talk() -> void:
	pass

func _adopt_combat_from_scene() -> void:
	pass

func _settle_combat(_opened: bool, _closed: bool) -> void:
	pass
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add godot/autoload/table.gd godot/test/test_table.gd && git commit -m "Hold the scene in one place, and apply a whole batch of diffs before saying so"
```

---

### Task 8: `Table` — the transcript, the rolls and the fight

**Files:**
- Modify: `godot/autoload/table.gd` (replace the three Task 7 placeholders and append)
- Modify: `godot/test/test_table.gd` (append)

**Interfaces:**
- Consumes: `Clock.speak/mark/hold/silence/silence_now`, `Tumble.is_dramatic/reveal_at/IMPACT_BEAT_MS`.
- Produces: on `Table` — `transcript: Array[Dictionary]`, `rolls: Array[Dictionary]`, `awaiting_dm: bool`, `active_roll`, `dice_dismiss_at`, `combat_beat`, `error_message`; signals `transcript_changed()`, `roll_thrown(result: Dictionary)`, `strike(actor_id: String, target_id: String, connected: bool, at: int)`, `combat_changed()`, `combat_opened(order_size: int)`, `errored(message: String)`, `started_changed()`; methods `append_narration(segment)`, `end_narration()`, `say_as_player(text)`, `add_roll(result)`, `set_error(message)`, `set_started()`, `dismiss_dice()`.

The transcript half of `client/src/store.ts`. `CEREMONY_MS` is 1200 (`client/src/combat/opening.ts:45`).

- [ ] **Step 1: Append the failing tests**

Append to `godot/test/test_table.gd`:

```gdscript
# ---- The transcript, paced by the voice

func test_the_players_own_line_lands_immediately() -> void:
	Table.say_as_player("heave the lid open")
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["speakerId"], "player")
	assert_true(Table.awaiting_dm)


func test_a_new_turn_drops_the_rest_of_the_last_one() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "one"})
	Table.append_narration({"speakerId": "narrator", "text": "two"})
	Table.say_as_player("wait")
	await wait_frames(3)
	# Dropped lines still reveal — the player interrupted the speech, not the record.
	assert_eq(Table.transcript.size(), 3)


func test_consecutive_lines_from_one_speaker_become_one_paragraph() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "The lid grinds."})
	Table.append_narration({"speakerId": "narrator", "text": "Dust falls."})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["text"], "The lid grinds. Dust falls.")


func test_a_speaker_change_starts_a_new_paragraph() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "It speaks."})
	Table.append_narration({"speakerId": "goblin", "text": "You woke me."})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 2)


func test_the_dm_gives_up_the_floor_behind_the_queue() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "done"})
	Table.end_narration()
	await wait_frames(4)
	assert_false(Table.awaiting_dm)


# ---- Rolls

func test_every_roll_reaches_the_log() -> void:
	Table.add_roll(_attack([14], "MISS"))
	await wait_seconds(2.0)
	assert_eq(Table.rolls.size(), 1)
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["kind"], "roll")


func test_a_damage_roll_goes_straight_to_the_log_without_being_thrown() -> void:
	var damage := _attack([5], "SUCCESS")
	damage["request"]["purpose"] = "DAMAGE"
	Table.add_roll(damage)
	await wait_frames(3)
	assert_eq(Table.rolls.size(), 1)
	assert_null(Table.active_roll, "damage does not animate")


func test_a_dramatic_roll_is_thrown_before_it_is_logged() -> void:
	Table.add_roll(_attack([14], "MISS"))
	await wait_frames(3)
	assert_not_null(Table.active_roll, "the die is in the air")
	assert_eq(Table.transcript.size(), 0, "the log lags — printing the total spoils the throw")


func test_narration_waits_for_the_dice_to_land() -> void:
	Table.add_roll(_attack([14], "MISS"))
	Table.append_narration({"speakerId": "narrator", "text": "The blade goes wide."})
	await wait_frames(3)
	assert_eq(Table.transcript.size(), 0)
	# reveal_at(1) + IMPACT_BEAT_MS = 1460 + 650
	await wait_seconds(2.3)
	assert_eq(Table.transcript.size(), 2)
	assert_eq(Table.transcript[0]["kind"], "roll", "the dice decide, then the DM speaks")


func test_a_strike_is_published_once_its_dice_have_landed() -> void:
	var struck: Array[Dictionary] = []
	var watch := func(a, t, c, at) -> void:
		struck.append({"actorId": a, "targetId": t, "connected": c, "at": at})
	Table.strike.connect(watch)
	Table.add_roll(_attack([18], "HIT"))
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(struck.size(), 1)
	assert_eq(struck[0]["targetId"], "goblin")
	assert_true(struck[0]["connected"])


func test_two_identical_misses_are_two_separate_events() -> void:
	# A notification, not state. Two identical misses in a row must not collapse into one.
	var count := 0
	var watch := func(_a, _t, _c, _at) -> void: count += 1
	Table.strike.connect(watch)
	Table.add_roll(_attack([3], "MISS"))
	await wait_seconds(2.3)
	Table.add_roll(_attack([3], "MISS"))
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(count, 2)


func test_an_attack_with_no_target_publishes_no_strike() -> void:
	var check := _attack([17], "SUCCESS")
	check["request"]["purpose"] = "SKILL_CHECK"
	check["request"]["targetId"] = null
	var count := 0
	var watch := func(_a, _t, _c, _at) -> void: count += 1
	Table.strike.connect(watch)
	Table.add_roll(check)
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(count, 0)


# ---- The fight

func test_a_fight_opening_holds_the_floor_for_the_ceremony() -> void:
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat()},
	])
	Table.append_narration({"speakerId": "narrator", "text": "Steel comes out."})
	await wait_frames(3)
	assert_eq(Table.transcript.size(), 0, "a beat the narrator talks over is not a beat")
	await wait_seconds(1.4)
	assert_eq(Table.transcript.size(), 1)


func test_the_bar_outlives_the_fight_so_it_has_names_to_draw_while_it_dissolves() -> void:
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat()},
	])
	await wait_seconds(1.4)
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "EXPLORATION"},
		{"kind": "CombatChanged", "combat": null},
	])
	await wait_frames(3)
	assert_not_null(Table.combat_beat)
	assert_not_null(Table.combat_beat["closing_at"])
	assert_eq(Table.combat_beat["view"]["order"].size(), 2)


func test_a_reconnect_mid_fight_does_not_replay_the_ceremony() -> void:
	# Backdated past the ceremony on purpose: replaying the drums would announce something that
	# happened minutes ago.
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	fighting["combat"] = _combat()
	Table.set_scene(fighting)
	Table.append_narration({"speakerId": "narrator", "text": "still here"})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 1, "nothing is held for a fight already under way")


# ---- Errors bypass the queue

func test_an_error_cuts_dead_and_lifts_the_hold() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "mid-sentence"})
	Table.set_error("The DM stumbled")
	assert_eq(Table.error_message, "The DM stumbled")
	assert_false(Table.awaiting_dm)


# ---- Restart goes all the way back to the title

func test_reset_returns_to_the_title() -> void:
	# The server clears its once-per-session opening guard on restart and then waits to be asked
	# again. Only `begin` asks it, and only the title sends `begin`. A client that stays
	# `started` after a restart lays out a fresh room and sits in silence forever.
	Table.set_started()
	Table.say_as_player("something")
	Table.reset()
	assert_false(Table.started)
	assert_eq(Table.transcript, [])
	assert_eq(Table.rolls, [])
	assert_null(Table.combat_beat)


# ---- Fixtures

func _attack(faces: Array, outcome: String) -> Dictionary:
	return {
		"request": {"dice": "1d20", "modifier": 5, "advantage": "NORMAL", "purpose": "ATTACK",
			"actorId": "fighter", "targetId": "goblin", "dc": 15, "skill": null},
		"faces": faces, "total": int(faces[0]) + 5, "outcome": outcome,
	}


func _combat() -> Dictionary:
	return {
		"order": [
			{"entityId": "fighter", "name": "Roderick", "initiative": 18,
				"isPlayerControlled": true},
			{"entityId": "goblin", "name": "Vessk", "initiative": 11,
				"isPlayerControlled": false},
		],
		"activeId": "fighter", "round": 1, "movementRemaining": 6, "actionAvailable": true,
		"legalMoves": [{"x": 3, "y": 2}], "legalTargets": [],
	}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: FAIL — `Table` has no method `say_as_player`.

- [ ] **Step 3: Replace the three placeholders and append the implementation**

In `godot/autoload/table.gd`, delete the `# ---- Filled in by Task 8` block and append this instead:

```gdscript
# ---- The transcript, the rolls, and the fight

const CEREMONY_MS := 1200

signal transcript_changed()
signal roll_thrown(result: Dictionary)
signal strike(actor_id: String, target_id: String, connected: bool, at: int)
signal combat_changed()
signal combat_opened(order_size: int)
signal errored(message: String)
signal started_changed()

## Prose and rolls in one list, so the log preserves the order things actually happened in —
## the dice, then the narration that commits to them. Each entry is either
## { "kind": "prose", "speakerId": String, "text": String } or
## { "kind": "roll", "result": Dictionary }.
var transcript: Array[Dictionary] = []
var rolls: Array[Dictionary] = []

## True while the DM is mid-turn. Drives the thinking indicator and the input lockout.
var awaiting_dm := false

## The roll the tray is currently throwing, and when it arrived. Null when nothing is in flight.
var active_roll = null
## When the tray began fading, in Time.get_ticks_msec() terms. Null while it is still held.
var dice_dismiss_at = null

## The fight's chrome, or the last fight's while it fades. Separate from scene.combat because
## the chrome outlives the fight by design: the bar has to still have names and initiative
## totals to draw while it is dissolving, and by then the server has said the fight is over.
## { "view": Dictionary, "opened_at": int, "closing_at": int or null }
var combat_beat = null

var error_message := ""


func _reset_talk() -> void:
	transcript = []
	rolls = []
	awaiting_dm = false
	active_roll = null
	dice_dismiss_at = null
	combat_beat = null
	error_message = ""
	Clock.silence_now()
	transcript_changed.emit()
	started_changed.emit()


## One way while a session runs. A dropped socket reconnects to a session already under way; it
## does not put the title back up, and the server will not narrate the opening twice.
##
## The DM has the floor from this moment, not from the moment its first token lands. Those are
## about 700ms apart, and in that window the debug bar was live and the input box was open —
## long enough to start a fight underneath the opening narration.
func set_started() -> void:
	started = true
	awaiting_dm = true
	started_changed.emit()


## Narration arrives a sentence at a time. Consecutive sentences from the same speaker extend
## the current paragraph, so the transcript reads as prose rather than as a list of fragments.
##
## The transcript update is the queue's callback rather than something that happens now: the
## voice paces the text, so the player reads at the speed the DM is talking instead of racing
## twenty seconds ahead of it.
func append_narration(segment: Dictionary) -> void:
	Clock.speak(segment, func() -> void:
		# The first word of narration is the tray's cue to leave.
		if active_roll != null and dice_dismiss_at == null:
			dismiss_dice()

		var last: Dictionary = transcript[-1] if not transcript.is_empty() else {}
		if awaiting_dm and last.get("kind", "") == "prose" \
				and last.get("speakerId", "") == segment["speakerId"]:
			last["text"] = _join_prose(String(last["text"]), String(segment["text"]))
		else:
			transcript.append({"kind": "prose", "speakerId": String(segment["speakerId"]),
				"text": String(segment["text"])})
			awaiting_dm = true
		transcript_changed.emit())


## Behind the queue: clearing this early would end the thinking indicator while lines were still
## appearing, and break the paragraph merging above.
func end_narration() -> void:
	Clock.mark(func() -> void:
		awaiting_dm = false
		transcript_changed.emit())


func say_as_player(text: String) -> void:
	# A new turn drops the rest of the last one, but lets the sentence in the air finish.
	Clock.silence()
	transcript.append({"kind": "prose", "speakerId": "player", "text": text})
	awaiting_dm = true
	transcript_changed.emit()


## Every roll reaches the log; only dramatic ones get thrown.
##
## Queued like everything else, so "the dice decide, then the DM speaks" is true by construction
## rather than by luck. A dramatic roll holds the queue for as long as it is in the air, which
## is what stops narration that commits to a result from arriving ahead of the die showing it.
func add_roll(result: Dictionary) -> void:
	var dramatic := Tumble.is_dramatic(result)
	var request: Dictionary = result["request"]

	# Holding the whole queue rather than the swing alone is what keeps the order true: the hit
	# point bar, the damage line and the blow are all consequences of this roll, and none of
	# them may arrive before the player has read what the roll said.
	var beat := Tumble.IMPACT_BEAT_MS if request["purpose"] == "ATTACK" else 0
	var airtime := (Tumble.reveal_at(result["faces"].size()) + beat) if dramatic else 0

	var throw_it := func() -> void:
		rolls.append(result)
		if dramatic:
			active_roll = {"result": result, "started_at": Time.get_ticks_msec()}
			dice_dismiss_at = null
			roll_thrown.emit(result)

	# The log is a record, and records lag. Appending it as the die is thrown would print the
	# total in the sidebar while it was still in the air, which spoils the throw.
	var settle := func() -> void:
		transcript.append({"kind": "roll", "result": result})
		transcript_changed.emit()
		# Released here rather than on arrival so the swing plays when the die answers, not when
		# the server decided.
		if request["purpose"] == "ATTACK" and request["targetId"] != null:
			var hit: bool = result["outcome"] == "HIT" or result["outcome"] == "CRIT"
			strike.emit(String(request["actorId"]), String(request["targetId"]), hit,
				Time.get_ticks_msec())
			# The blow is the tray's cue to leave, exactly as narration is out of combat.
			if dice_dismiss_at == null:
				dismiss_dice()

	if dramatic:
		Clock.hold(airtime, throw_it)
		Clock.mark(settle)
	else:
		Clock.mark(func() -> void:
			throw_it.call()
			settle.call())


func dismiss_dice() -> void:
	dice_dismiss_at = Time.get_ticks_msec()


## Errors bypass the queue: a stuck turn must never be hidden behind a die or a sentence.
func set_error(message: String) -> void:
	# An error is the one case worth cutting mid-word for.
	Clock.silence_now()
	error_message = message
	awaiting_dm = false
	errored.emit(message)


## A socket that drops and reconnects during a fight rejoins one already in progress. Backdated
## past the ceremony so the drums do not announce something that happened minutes ago.
func _adopt_combat_from_scene() -> void:
	var view = scene.get("combat", null)
	if view == null:
		combat_beat = null
		return
	combat_beat = {"view": view, "opened_at": Time.get_ticks_msec() - CEREMONY_MS,
		"closing_at": null}
	combat_changed.emit()


## The combat chrome after a batch of diffs. Not pure, and deliberately so — this is the first
## point at which both facts the opening beat needs are known: that the mode flipped, and who is
## in the initiative order.
func _settle_combat(opened: bool, closed: bool) -> void:
	var now := Time.get_ticks_msec()
	var view = scene.get("combat", null)

	if opened and view != null:
		# Everything downstream waits behind the ceremony: the DM's first line about the fight,
		# the creature's opening move, and every consequence of it. The same gate the dice use,
		# for the same reason — a beat the narrator talks over is not a beat.
		Clock.hold(CEREMONY_MS, func() -> void: pass)
		combat_beat = {"view": view, "opened_at": now, "closing_at": null}
		combat_opened.emit(view["order"].size())
		combat_changed.emit()
		return

	if closed:
		# The retained view is the last live one. `view` is already null by now, and the bar has
		# to keep drawing names and totals all the way through its dissolve.
		if combat_beat != null and combat_beat["closing_at"] == null:
			combat_beat["closing_at"] = now
			combat_changed.emit()
		return

	if view == null:
		return

	# An ordinary update — the turn passing, movement spent, someone going down. The backdated
	# fallback covers a CombatChanged arriving without an opening, which is what a mid-fight
	# reconnect looks like if the scene has not landed yet.
	if combat_beat != null:
		combat_beat["view"] = view
	else:
		combat_beat = {"view": view, "opened_at": now - CEREMONY_MS, "closing_at": null}
	combat_changed.emit()


## Segments arrive pre-trimmed of nothing, so join with exactly one space.
func _join_prose(existing: String, addition: String) -> String:
	var left := existing.strip_edges(false, true)
	var right := addition.strip_edges(true, false)
	if left.is_empty():
		return right
	if right.is_empty():
		return left
	return "%s %s" % [left, right]
```

- [ ] **Step 4: Wire `Table` to `Net` in `_ready`**

Add to the top of `godot/autoload/table.gd`, after the `var started := false` line:

```gdscript
func _ready() -> void:
	Net.hello.connect(func(demo: bool, _voice: bool, has_dm: bool) -> void:
		demo_mode = demo
		dm_configured = has_dm)
	Net.scene.connect(set_scene)
	Net.diffs.connect(apply_diffs)
	Net.narration.connect(append_narration)
	Net.narration_end.connect(end_narration)
	Net.roll.connect(add_roll)
	Net.error.connect(set_error)
	Net.connected.connect(func() -> void: connected = true)
	Net.disconnected.connect(func(_reason: String) -> void: connected = false)
```

- [ ] **Step 5: Run the tests and watch them pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

If `test_narration_waits_for_the_dice_to_land` fails, `airtime` is wrong — it is `Tumble.reveal_at(face_count) + IMPACT_BEAT_MS` for attacks and `Tumble.reveal_at(face_count)` for everything else, and it holds the *whole queue*, not just the swing.

- [ ] **Step 6: Commit**

```bash
git add godot/autoload/table.gd godot/test/test_table.gd && git commit -m "Pace the transcript, the dice and the fight off one queue"
```

---

### Task 9: Voice — the HTTP adapter and the OS fallback

**Files:**
- Create: `godot/audio/http_voice.gd`
- Create: `godot/audio/os_voice.gd`
- Create: `godot/audio/casting.gd`
- Modify: `godot/autoload/clock.gd` (choose the backend from `hello`)

**Interfaces:**
- Consumes: `VoiceBackend`, `Link.tts_url()`, `Net.hello`.
- Produces: `HttpVoice.new(fallback: VoiceBackend, host: Node)` and `OsVoice.new()`, both `VoiceBackend`s. `Casting.for_speaker(speaker_id) -> Dictionary` with keys `prefer: Array`, `pitch: float`, `rate: float`.

Port of `client/src/audio/voice.ts`. The key never reaches the client — the server holds it and Godot fetches bytes.

- [ ] **Step 1: Write the casting table**

Create `godot/audio/casting.gd`:

```gdscript
class_name Casting
extends RefCounted

## A small closed table, not a voice picker.
##
## `prefer` is matched by name prefix against whatever the machine has, so this degrades to
## pitch and rate alone on a box without these voices rather than falling silent. The macOS
## names are the good ones — Daniel is a measured British male, Ralph is croaky enough to be a
## goblin without any pitch shifting at all.

const TABLE := {
	"narrator": {"prefer": ["Daniel", "Alex"], "pitch": 0.95, "rate": 0.96},
	"goblin": {"prefer": ["Ralph", "Bahh", "Trinoids"], "pitch": 1.25, "rate": 1.06},
	"fighter": {"prefer": ["Reed", "Rocko", "Fred"], "pitch": 1.0, "rate": 1.0},
}

const DEFAULT := {"prefer": [], "pitch": 1.0, "rate": 1.0}


static func for_speaker(speaker_id: String) -> Dictionary:
	return TABLE.get(speaker_id, DEFAULT)
```

- [ ] **Step 2: Write the OS backend**

Create `godot/audio/os_voice.gd`:

```gdscript
class_name OsVoice
extends VoiceBackend

## The machine's own synthesiser. Always built, because it is also the per-line fallback.
##
## Two Godot facts underneath this. `DisplayServer.tts_*` does nothing at all unless
## Project Settings > Audio > General > Text to Speech is on; it is off by default and the
## failure is silence, not an error. And the completion callback is [b]global[/b], not per
## utterance — one callback for the whole process, carrying an id — so the "exactly one
## finished per speak" contract needs the id tracked here rather than captured per call.

var _utterance_id := 0
var _in_flight := -1
var _voices: Array = []


func _init() -> void:
	if not DisplayServer.has_feature(DisplayServer.FEATURE_TEXT_TO_SPEECH):
		push_warning("[voice] no OS text-to-speech on this platform")
		return
	_voices = DisplayServer.tts_get_voices()
	DisplayServer.tts_set_utterance_callback(
		DisplayServer.TTS_UTTERANCE_ENDED, _on_utterance_done)
	DisplayServer.tts_set_utterance_callback(
		DisplayServer.TTS_UTTERANCE_CANCELED, _on_utterance_done)


func speak(line: Dictionary) -> void:
	if _voices.is_empty():
		finished.emit.call_deferred()
		return

	var cast := Casting.for_speaker(String(line["speakerId"]))
	_utterance_id += 1
	_in_flight = _utterance_id
	DisplayServer.tts_speak(
		String(line["text"]),
		_pick(cast["prefer"]),
		50,                          # volume
		float(cast["pitch"]),
		float(cast["rate"]),
		_utterance_id,
		true)                        # interrupt: one line at a time, always


func stop() -> void:
	# Settles the in-flight line via the CANCELED callback. Without that the queue would wait
	# forever for a line that was cut.
	DisplayServer.tts_stop()


func _on_utterance_done(id: int) -> void:
	if id != _in_flight:
		return
	_in_flight = -1
	finished.emit()


func _pick(prefer: Array) -> String:
	for wanted in prefer:
		for voice in _voices:
			if String(voice["name"]).begins_with(String(wanted)):
				return String(voice["id"])
	for voice in _voices:
		if String(voice["language"]).begins_with("en"):
			return String(voice["id"])
	return String(_voices[0]["id"])
```

- [ ] **Step 3: Write the HTTP backend**

Create `godot/audio/http_voice.gd`:

```gdscript
class_name HttpVoice
extends VoiceBackend

## Asks this project's server for audio. The key never reaches the client — the server holds it
## and Godot fetches bytes.
##
## Speech is HTTP rather than the websocket on purpose: a minute of narration is a few hundred
## kilobytes, and pushing that down the same socket the diffs use would put it behind — or in
## front of — a click-to-move that has a 100ms budget.
##
## Anything but a 200 means "use the other voice". A failed line is a worse voice, never a
## silent turn and never a stalled queue.

var _fallback: VoiceBackend
var _player: AudioStreamPlayer
var _http: HTTPRequest
## Keyed by speakerId and text, so priming a line and then speaking it share one request.
var _ready_audio: Dictionary = {}
var _in_flight_key := ""
var _in_flight_line: Dictionary = {}
var _wanted := ""
var _falling_back := false


func _init(fallback: VoiceBackend, host: Node) -> void:
	_fallback = fallback

	_player = AudioStreamPlayer.new()
	_player.bus = "Voice"
	host.add_child(_player)
	_player.finished.connect(_on_played)

	_http = HTTPRequest.new()
	host.add_child(_http)
	_http.request_completed.connect(_on_fetched)

	_fallback.finished.connect(func() -> void:
		if _falling_back:
			_falling_back = false
			finished.emit())


static func key_of(line: Dictionary) -> String:
	return "%s %s" % [line["speakerId"], line["text"]]


## Start preparing a line that is coming but is not being spoken yet. Exactly one ahead: far
## enough that synthesis finishes while the previous line plays, near enough that an interrupted
## turn wastes at most one line of a metered API.
func prime(line: Dictionary) -> void:
	_fetch(line)


func speak(line: Dictionary) -> void:
	_wanted = key_of(line)
	if _ready_audio.has(_wanted):
		_play(_ready_audio[_wanted], line)
		return
	_fetch(line)


func stop() -> void:
	_wanted = ""
	_player.stop()
	_fallback.stop()


func _fetch(line: Dictionary) -> void:
	var key := key_of(line)
	if _ready_audio.has(key) or _in_flight_key == key or _in_flight_key != "":
		return
	_in_flight_key = key
	_in_flight_line = line
	var err := _http.request(
		Link.tts_url(),
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		JSON.stringify({"speakerId": line["speakerId"], "text": line["text"]}))
	if err != OK:
		_in_flight_key = ""
		if key == _wanted:
			_give_up(line)


func _on_fetched(_result: int, code: int, _headers: PackedStringArray,
		body: PackedByteArray) -> void:
	var key := _in_flight_key
	var line := _in_flight_line
	_in_flight_key = ""
	_in_flight_line = {}

	if code != 200 or body.is_empty():
		# 502 is the server saying the voice refused; anything else non-OK is the server itself.
		# Both mean the same thing here.
		if key == _wanted:
			_give_up(line)
		return

	var stream := AudioStreamMP3.load_from_buffer(body)
	_ready_audio[key] = stream
	if key == _wanted:
		_play(stream, line)


func _play(stream: AudioStreamMP3, line: Dictionary) -> void:
	_ready_audio.erase(key_of(line))
	_player.stream = stream
	_player.play()


func _on_played() -> void:
	if _wanted != "":
		_wanted = ""
		finished.emit()


func _give_up(line: Dictionary) -> void:
	_falling_back = true
	_wanted = ""
	_fallback.speak(line)
```

- [ ] **Step 4: Choose the backend from `hello`**

Add to `godot/autoload/clock.gd`:

```gdscript
func _ready() -> void:
	# Chosen from what the server says it can do, not guessed at here: whether a real voice
	# exists is a fact about the server's configuration. The OS synthesiser is always built,
	# because it is also the per-line fallback.
	Net.hello.connect(func(_demo: bool, has_voice: bool, _dm: bool) -> void:
		var os_voice := OsVoice.new()
		use_backend(HttpVoice.new(os_voice, self) if has_voice else os_voice))
```

- [ ] **Step 5: Add the audio buses**

In the Godot editor, open the Audio panel and add three buses beside Master: `Voice`, `World`, `Dice`. Save as `godot/default_bus_layout.tres`. Then in `project.godot` under `[audio]`:

```ini
buses/default_bus_layout="res://default_bus_layout.tres"
```

`Voice` on its own bus is the point of the exercise — narration can duck the world without touching either one's gain.

- [ ] **Step 6: Verify the tests still pass**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures. The voice adapters are not unit-tested — they are exercised by playing, and Task 14's checkpoint is where a real line gets spoken.

- [ ] **Step 7: Commit**

```bash
git add godot/audio godot/autoload/clock.gd godot/project.godot godot/default_bus_layout.tres && git commit -m "Give the narrator a voice, and something to fall back to when it fails"
```

---

### Task 10: SFX — the layered stings

**Files:**
- Create: `godot/audio/sfx.gd`
- Create: `godot/audio/samples/` (copied from `client/public/assets/audio/`)
- Create: `godot/audio/samples/drop_*.wav` (baked)

**Interfaces:**
- Consumes: `Table` signals `entity_moved`, `prop_revealed`, `entity_added`, `entity_died`, `combat_opened`, `strike`.
- Produces: autoload `Sfx` (declared in Task 3, replaced here) with `lid_opens()`, `revealed()`, `footsteps(squares: int)`, `combat_begins()`, `initiative_set(count: int)`, `swing(connected: bool)`, `fell()`, and `move_seconds(squares: int) -> float`.

Ports `client/src/audio/sfx.ts`, `world.ts` and `combat.ts`. **`AGENTS.md` section Sound is the specification**: move 4 layers, prop revealed 2, sarcophagus 5, combat begins 6, miss 4, hit 6, killing blow 9. Every delay and every `rate` in `world.ts` and `combat.ts` transfers unchanged.

Two things do not port and must be handled here.

**`drop()` has no Godot equivalent.** It is a Web Audio oscillator sweeping from one frequency to another under an envelope — runtime synthesis, which Godot does not have. Without it *"the combat sting is a loud clang rather than an event"*. It is used at four settings across `world.ts` and `combat.ts`. Bake each one to a wav, once, offline.

**Scheduling is frame-quantised, not sample-accurate.** `AGENTS.md`: *"Every layer is scheduled on the audio clock, not with setTimeout — a sting is layers a tenth of a second apart and timers smear them together."* Godot has no "start at time T". A `SceneTreeTimer` per layer is about 8ms of jitter at 60Hz, far tighter than the `setTimeout` smear that note was written against. **This is the one thing in the whole migration that gets worse, and it is a listening test, not a unit test** — parity gate item 7.

- [ ] **Step 1: Bake the four sweeps**

```bash
mkdir -p godot/audio/samples && python3 - <<'PY'
import math, struct, wave

RATE = 44100

def sweep(path, f0, f1, seconds, gain):
    frames = int(RATE * seconds)
    phase = 0.0
    out = bytearray()
    for i in range(frames):
        t = i / frames
        f = f0 + (f1 - f0) * t
        phase += 2 * math.pi * f / RATE
        env = gain * math.exp(-3.2 * t)
        sample = max(-1.0, min(1.0, math.sin(phase) * env))
        out += struct.pack('<h', int(sample * 32767))
    with wave.open(path, 'w') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(out))
    print("wrote", path)

sweep('godot/audio/samples/drop_lid.wav',    84, 33, 0.8, 0.30)
sweep('godot/audio/samples/drop_fell.wav',   70, 32, 0.5, 0.22)
sweep('godot/audio/samples/drop_drum_a.wav', 190, 48, 0.7, 0.80)
sweep('godot/audio/samples/drop_drum_b.wav', 210, 38, 1.5, 1.00)
PY
```

Compare each against `drop()` in `client/src/audio/sfx.ts` and `drum()` in `client/src/audio/combat.ts` before accepting the decay constant. If a baked sweep sounds thinner than the browser's, the envelope is wrong, not the frequencies.

- [ ] **Step 2: Copy the sample bank**

```bash
cp -R client/public/assets/audio/. godot/audio/samples/
```

- [ ] **Step 3: Write `sfx.gd`**

Create `godot/audio/sfx.gd` with one `play(name: String, gain: float, rate: float, delay: float)` helper backed by a small pool of `AudioStreamPlayer`s on the `World` and `Dice` buses, and the seven public functions above. Transcribe every layer, gain, `rate` and `delay` from `client/src/audio/world.ts` and `client/src/audio/combat.ts` **without changing a number**. `rate` maps to `AudioStreamPlayer.pitch_scale`. Keep the random pitch jitter from `sfx.ts` — *"without it the clatter sounds canned by the third roll"*.

The two that carry the most: `lidOpens()` is 5 layers (`client/src/audio/world.ts:13`), `fell()` is 3 plus the baked drop (`client/src/audio/combat.ts:78`), and a killing blow is `swing(true)` and `fell()` together, which is the 9.

`moveSeconds()` is shared between the renderer's slide and the footstep spacing. Put it in one place here and have Task 19's token read it — two copies of that number would drift apart the first time anyone retuned movement.

- [ ] **Step 4: Wire it to `Table`**

`Sfx` subscribes to `Table`, never the other way round. In the chrome root script (Task 11):

```gdscript
	Table.entity_moved.connect(func(_id, from, to) -> void:
		Sfx.footsteps(maxi(absi(to.x - from.x), absi(to.y - from.y))))
	Table.prop_revealed.connect(func(_p) -> void: Sfx.revealed())
	# The lid is hung off a hostile arriving, which in M0 is the only way anything appears.
	# If a second spawn point is ever added, this becomes a property of the prop instead.
	Table.entity_added.connect(func(e) -> void:
		if not e["isPlayerControlled"]:
			Sfx.lid_opens())
	# Initiative is rolled as a batch and never reaches the tray, so the sting is the only thing
	# announcing the fight until the bar arrives behind it.
	Table.combat_opened.connect(func(count: int) -> void:
		Sfx.combat_begins()
		Sfx.initiative_set(count))
	Table.strike.connect(func(_a, _t, hit: bool, _at) -> void: Sfx.swing(hit))
	Table.entity_died.connect(func(_id) -> void: Sfx.fell())
```

- [ ] **Step 5: Listen to it**

There is nothing to assert. Once Task 13 exists, run `debug` then `start combat` through to a kill, and compare against `cd client && npm run dev` doing the same. If the nine-layer blow reads as a clang rather than an event, the layers have smeared — reduce the layer count before reaching for a bigger gain.

- [ ] **Step 6: Commit**

```bash
git add godot/audio && git commit -m "Move the stings onto Godot buses, and bake the weight that used to be synthesised"
```

---

### Task 11: Chrome — the overlay, the transcript and the input box

**Files:**
- Create: `godot/chrome/chrome.tscn`, `godot/chrome/chrome.gd`
- Create: `godot/chrome/transcript.gd`
- Create: `godot/chrome/input_box.gd`
- Create: `godot/chrome/title.gd`
- Create: `godot/chrome/toast.gd`

**Interfaces:**
- Consumes: `Table` (`transcript_changed`, `started_changed`, `errored`), `Net` (`begin`, `free_text`), `Clock`.
- Produces: `Chrome` — the scene the project boots into. Task 12 adds the tray under it, Task 13 the combat bar and defeat, Task 14 the debug bar, Task 15 the world beneath it.

Ports `client/src/ui/Transcript.tsx`, `InputBox.tsx`, `Title.tsx` and the toast in `App.tsx`. Read `AGENTS.md` section Voice for why the transcript is paced rather than appended.

Node tree for `chrome.tscn`:

```
Chrome (CanvasLayer, script chrome.gd)
├── World (SubViewportContainer)        # empty until Task 15
├── Overlay (Control, full rect)
│   ├── Log (PanelContainer)            # translucent, bottom-left
│   │   └── VBox
│   │       ├── Transcript (RichTextLabel, script transcript.gd)
│   │       └── InputBox (LineEdit, script input_box.gd)
│   ├── Toast (Label, script toast.gd)
│   ├── Banner (Label)                  # "start the DM" while health is down
│   └── Title (Control, script title.gd)   # on top; hidden once started
```

`Sfx` is an autoload (Task 3), not a child here — the wiring in Step 6 calls it by name.

**Copy from WoW:** overlay over the world rather than a sidebar, high contrast, colour by speaker, mouse-wheel scrollback, input docked under the log.

**Do not copy WoW tabs.** One stream — prose and rolls in arrival order, the same list as `Table.transcript`. A General/Combat split would put the die in one place and the voice in another.

**Do not copy WoW idle fading while a line is being spoken.** The transcript is the DM, paced by `Clock`. The panel may dim when nothing is queued and `awaiting_dm` is false; it must never fade a line that is still being said.

- [ ] **Step 1: Build the scene tree**

Create `godot/chrome/chrome.tscn` with the tree above. `Log` gets a `StyleBoxFlat` with `bg_color = Color(0.04, 0.04, 0.05, 0.72)`, anchored bottom-left, roughly 42% of the window width and 38% of its height. `Transcript` has `bbcode_enabled = true`, `scroll_following = true`, `selection_enabled = true`.

- [ ] **Step 2: Write the transcript**

Create `godot/chrome/transcript.gd`. It rebuilds its BBCode from `Table.transcript` on `transcript_changed`, and nothing else ever writes to it (invariant #3).

```gdscript
extends RichTextLabel

## The DM's own record. Rebuilt from Table, never appended to directly.
##
## Prose and rolls live in one list so the log preserves the order things actually happened in —
## the dice, then the narration that commits to them.
##
## The colours come from one table so a creature reads the same whether it is speaking or
## rolling, and the roll wording comes from Tumble.caption so the tray and the log cannot drift.

const SPEAKER_COLOR := {
	"narrator": Color("cfc4ae"),
	"goblin": Color("8fae72"),
	"fighter": Color("c9b083"),
	"player": Color("7fa8d0"),
}


func _ready() -> void:
	Table.transcript_changed.connect(_redraw)
	_redraw()


func _redraw() -> void:
	var out := PackedStringArray()
	for entry in Table.transcript:
		if entry["kind"] == "prose":
			var who := String(entry["speakerId"])
			var tint: Color = SPEAKER_COLOR.get(who, SPEAKER_COLOR["narrator"])
			out.append("[color=#%s]%s[/color]" % [tint.to_html(false), entry["text"]])
		else:
			out.append(_roll_line(entry["result"]))
	text = "\n\n".join(out)


## The roll log names who rolled. Names come from scene.entities — the server is the authority
## on what a creature is called — and the tone colour comes from Tumble so a success is never
## two different greens.
func _roll_line(result: Dictionary) -> String:
	var cap := Tumble.caption(result)
	var actor := Table.entity(String(result["request"]["actorId"]))
	var who := String(actor.get("name", result["request"]["actorId"]))
	var tone: Color = Tumble.TONE_COLOR[cap["tone"]]
	var target := "  %s" % cap["target"] if cap["target"] != "" else ""
	var outcome := "  [b]%s[/b]" % cap["outcome"] if cap["outcome"] != "" else ""
	return "[color=#%s]%s  %s%s  %s%s[/color]" % [
		tone.to_html(false), who, cap["label"], target, cap["arithmetic"], outcome]
```

- [ ] **Step 3: Write the input box**

Create `godot/chrome/input_box.gd`:

```gdscript
extends LineEdit

## Enter sends. Locked while the DM has the floor — a second turn typed underneath the first is
## a state the server has to drop as a collision.

const ACTOR := "fighter"   # M0's only party member; addressed by id, never assumed to be alone


func _ready() -> void:
	text_submitted.connect(_send)
	Table.transcript_changed.connect(_relock)
	Table.started_changed.connect(_relock)
	_relock()


func _send(typed: String) -> void:
	var line := typed.strip_edges()
	if line.is_empty() or Table.awaiting_dm:
		return
	clear()
	# Local echo first, so the keypress is acknowledged inside 100ms with no model in the path.
	Table.say_as_player(line)
	Net.free_text(ACTOR, line)


func _relock() -> void:
	editable = Table.started and not Table.awaiting_dm
	placeholder_text = "The DM is speaking." if Table.awaiting_dm else "What do you do?"
```

- [ ] **Step 4: Write the title**

Create `godot/chrome/title.gd`:

```gdscript
extends Control

## The click that starts the session.
##
## The opening is asked for by the client, not pushed on connect: a socket opening is not a
## player arriving. In the browser this click also unlocked audio; Godot has no such gate, but
## the click stays the trigger for two other reasons. The server's opening is guarded
## once-per-session and only `begin` asks past the guard, and restart clears that guard — so
## the title is also how the second session gets narrated.
##
## The room is already built underneath this by the time it is shown. Connecting at boot rather
## than at the click is what lets the crypt exist before anyone speaks.


func _ready() -> void:
	Table.started_changed.connect(func() -> void: visible = not Table.started)
	gui_input.connect(_on_click)
	visible = not Table.started


func _on_click(event: InputEvent) -> void:
	if not (event is InputEventMouseButton and event.pressed):
		return
	if Table.started:
		return
	# Order matters and is the whole mechanism: take the floor, then ask for the opening. The
	# DM has it from this moment, not from the moment its first token lands — those are about
	# 700ms apart, and in that window the input box and the debug bar were live.
	Table.set_started()
	Net.begin()
```

- [ ] **Step 5: Write the toast**

Create `godot/chrome/toast.gd`. It shows `Table.error_message` on `errored`, fading after 6 seconds, and shows one notice at boot when `Table.dm_configured` is false:

```gdscript
extends Label

const NO_DM := "No DM configured. Set VENICE_API_KEY in .env and restart the server."


func _ready() -> void:
	visible = false
	Table.errored.connect(_show)
	Net.hello.connect(func(_demo: bool, _voice: bool, has_dm: bool) -> void:
		if not has_dm:
			# A mute crypt with no explanation reads as a crash. Everything short of narration
			# still works on this path, which is what the debug bar is for.
			_show(NO_DM))


func _show(message: String) -> void:
	text = message
	visible = true
	var timer := get_tree().create_timer(6.0)
	await timer.timeout
	visible = false
```

- [ ] **Step 6: Write the chrome root and wire SFX**

Create `godot/chrome/chrome.gd` with the SFX wiring from Task 10 Step 4 and the connection banner below, and nothing else. It owns no game facts (invariant #3).

The banner is spec section 9's second error class, and it is deliberately **not** a modal:

```gdscript
	# A server that is not up is a developer's loop, not a dead end — it is a Gradle process in
	# a terminal that gets restarted a dozen times an evening. The banner clears itself when
	# health comes back. Godot never starts a server and never kills one.
	Net.connected.connect(func() -> void: $Overlay/Banner.visible = false)
	Net.disconnected.connect(func(_reason: String) -> void:
		$Overlay/Banner.text = "Start the DM:  cd server && ./gradlew run"
		$Overlay/Banner.visible = true)
	$Overlay/Banner.text = "Start the DM:  cd server && ./gradlew run"
	$Overlay/Banner.visible = not Table.connected
```

- [ ] **Step 7: Run it against the real server**

Terminal one:

```bash
cd server && ./gradlew run --args='--demo'
```

Terminal two: open the Godot editor and press F5.

Expected: the title overlay appears; clicking it produces spoken narration that lands in the transcript **as it is spoken**, not all at once. Type "look at the sarcophagus" and press Enter — the line appears immediately in blue, the input locks, and the DM answers.

If the transcript arrives all at once and then the voice catches up over the next twenty seconds, `append_narration` is writing outside the `Clock.speak` callback.

- [ ] **Step 8: Commit**

```bash
git add godot/chrome && git commit -m "Put the DM's own record over the room, paced by the voice saying it"
```

---

### Task 12: Chrome — the dice tray

**Files:**
- Create: `godot/chrome/dice_tray.gd`
- Modify: `godot/chrome/chrome.tscn` (add `Overlay/DiceTray`, a `Control`)

**Interfaces:**
- Consumes: `Tumble.sample`, `Tumble.caption`, `Table.active_roll`, `Table.dice_dismiss_at`, `Table.mode`.
- Produces: nothing other tasks consume.

Port of `client/src/ui/DiceTray.tsx`. The tray draws in tray units scaled by `Tumble.DISPLAY_SCALE` — deliberately **crisp at native resolution**, not inside the pixelated viewport. A d20 at 480px is a smudge for the same reason a to-scale human is.

- [ ] **Step 1: Write the tray**

Create `godot/chrome/dice_tray.gd`:

```gdscript
extends Control

## The dice, drawn. Every number it draws comes from Tumble; this file decides nothing.
##
## The stakes are drawn from the first frame — "ATHLETICS CHECK  DC 20" is legible while the die
## is still in the air, and that is most of the tension.


func _ready() -> void:
	Table.roll_thrown.connect(func(_r) -> void: set_process(true))
	set_process(false)


func _process(_delta: float) -> void:
	if Table.active_roll == null:
		set_process(false)
		queue_redraw()
		return
	queue_redraw()


func _draw() -> void:
	if Table.active_roll == null:
		return

	var result: Dictionary = Table.active_roll["result"]
	var elapsed := Time.get_ticks_msec() - int(Table.active_roll["started_at"])

	# Out of combat a roll is a question the DM is about to answer, so the tray waits for the
	# answer. In a fight the blow is the answer, so the swing dismisses the tray exactly as
	# narration does. COMBAT_HOLD_MS is only the backstop for rolls with no swing behind them.
	var backstop := Tumble.COMBAT_HOLD_MS if Table.mode == "COMBAT" else Tumble.HOLD_MS
	var dismiss_at := backstop
	if Table.dice_dismiss_at != null:
		dismiss_at = mini(backstop, int(Table.dice_dismiss_at) - int(Table.active_roll["started_at"]))

	var visual := Tumble.sample(result, elapsed, dismiss_at)
	if visual["finished"]:
		Table.active_roll = null
		return

	# ... draw the caption, then each die, at DISPLAY_SCALE. See DiceTray.tsx for the shapes:
	# a rounded square per die, the pip count as text, discards at 0.35 alpha, and the readout
	# wiping in from the left over `visual["reveal"]`.
```

Transcribe the drawing itself from `client/src/ui/DiceTray.tsx`. Nothing about the maths changes; only the drawing API does.

- [ ] **Step 2: Watch a die**

With the server running and Task 14's debug bar not yet built, use the existing browser client's `debug` then `roll d20` to confirm the server path, then in Godot type a turn that provokes a check ("heave the lid open" with `--demo` dice). Expected: a rattle over an empty tray for 240ms, dice arriving from the right and settling 1060ms in, the total legible at 1460ms, and the narration arriving *after* that.

If narration arrives before the dice land, `Table.add_roll` is not holding the queue — see Task 8.

- [ ] **Step 3: Commit**

```bash
git add godot/chrome && git commit -m "Throw the dice the server already decided"
```

---

### Task 13: Chrome — the combat bar, defeat, and restart

**Files:**
- Create: `godot/chrome/combat_bar.gd`
- Create: `godot/chrome/defeat.gd`
- Modify: `godot/chrome/chrome.tscn`

**Interfaces:**
- Consumes: `Table.combat_beat`, `Table.combat_changed`, `Table.entity`, `Net.end_turn`, `Net.restart`.
- Produces: nothing other tasks consume.

Ports `client/src/ui/CombatBar.tsx`, `Defeat.tsx` and `client/src/combat/opening.ts`. The ceremony constants are in `opening.ts`: `BAR_IN_DELAY_MS` 120, `BAR_IN_MS` 200, `CHIP_DELAY_MS` 400, `CHIP_STAGGER_MS` 180, `CHIP_IN_MS` 260, `CHROME_DELAY_MS` 760, `CHROME_IN_MS` 320, `CEREMONY_MS` 1200, `CLOSE_MS` 400. All of them time against `Table.combat_beat.opened_at`.

- [ ] **Step 1: Write the combat bar**

Create `godot/chrome/combat_bar.gd`. It draws from `Table.combat_beat` — never from `scene.combat` — because the bar outlives the fight and has to keep names and initiative totals while it dissolves.

Rules that must survive:

- **Health bar colour is allegiance, not health.** Green is yours, red is theirs; the bar's *length* carries the hit points. One meaning per colour. If the danger signal turns out too quiet, the fix is a pulse, not a hue.
- End turn is locked while `Table.awaiting_dm` — same collision as the debug bar versus opening narration.
- The bar assembles on the ceremony timings above, backdated on a mid-fight reconnect so it appears already assembled.

- [ ] **Step 2: Write defeat and restart**

Create `godot/chrome/defeat.gd`:

```gdscript
extends Control

## A dead player character used to be only a dropped token: combat ended, the mode pill flipped
## back, and nothing said what had happened. With the input box refusing text and the board
## refusing clicks, that reads as a crash rather than a death.


func _ready() -> void:
	visible = false
	Table.scene_changed.connect(_check)
	$Restart.pressed.connect(_restart)


func _check() -> void:
	var me := Table.entity("fighter")
	visible = Table.started and not me.is_empty() and int(me["hp"]) <= 0


func _restart() -> void:
	# Net stays up. Godot does not quit and does not restart the server — that was the browser's
	# location.reload, which Godot does not have and does not need.
	Net.restart()
	# Table.reset() runs when the fresh scene lands, not here: resetting first would clear the
	# board while the old session's last diffs were still arriving.
```

- [ ] **Step 3: Route the fresh scene back to the title**

In `godot/autoload/table.gd`, `set_scene` must recognise a restart. Add a flag set by `Net.restart` and read here:

```gdscript
## Set while a restart is in flight, so the fresh scene the server sends back is recognised as
## the end of this session rather than the middle of one.
var _restarting := false


func expect_restart() -> void:
	_restarting = true
```

and at the top of `set_scene`:

```gdscript
	if _restarting:
		_restarting = false
		# All the way back to the title. The server has just cleared its once-per-session
		# opening guard and is waiting to be asked again; only `begin` asks, and only the title
		# sends `begin`. A client that stays `started` here lays out a fresh room and then sits
		# in silence forever.
		reset()
```

and in `godot/chrome/defeat.gd` `_restart`, call `Table.expect_restart()` before `Net.restart()`.

- [ ] **Step 4: Prove the whole loop**

```bash
cd server && ./gradlew run --args='--demo'
```

In Godot: click the title, type until a fight starts (or wait for Task 14's debug bar), let the fighter die. Expected: the body stays on the board, the defeat overlay appears, restart lays out a fresh room, the title returns, and the click after it produces a **new opening narration**.

If the room resets but nothing is ever spoken again, `reset()` is not clearing `started` — that is the exact bug this step exists to prevent.

- [ ] **Step 5: Commit**

```bash
git add godot/chrome godot/autoload/table.gd && git commit -m "Draw the fight, mark the death, and let a restart start over properly"
```

---

### Task 14: Chrome — the debug bar, and the Phase A checkpoint

**Files:**
- Create: `godot/chrome/debug_bar.gd`
- Modify: `godot/chrome/chrome.tscn`

**Interfaces:**
- Consumes: `Net.debug`, `Table.awaiting_dm`.
- Produces: nothing other tasks consume.

The seven debug messages `WsHandler` already understands: `debugReveal`, `debugSpawnGoblin`, `debugSetMode`, `debugScene`, `debugStartCombat`, `debugOpen`, `debugRoll`.

**Build this before Phase B, not after.** It is how the feel gets tuned with no model, no API key and no latency in the path, and it is the only way to exercise Phase B's world work without spending a turn. Every one of these paths already exists server-side.

- [ ] **Step 1: Write the bar**

Create `godot/chrome/debug_bar.gd`:

```gdscript
extends HBoxContainer

## A real roll down the real path, with no model in it — and the same for a whole fight.
##
## Locked while the DM has the floor. Starting a fight halfway through the opening sentence is a
## state the game cannot otherwise reach, and it produced exactly the collision the lock refuses.

const BUTTONS := [
	["roll d20", {"type": "debugRoll", "actorId": "fighter", "skill": "PERCEPTION",
		"difficulty": "MEDIUM"}],
	["reveal", {"type": "debugReveal", "propId": "alcove"}],
	["spawn goblin", {"type": "debugSpawnGoblin"}],
	["start combat", {"type": "debugStartCombat"}],
	["combat mode", {"type": "debugSetMode", "mode": "COMBAT"}],
	["explore mode", {"type": "debugSetMode", "mode": "EXPLORATION"}],
	["re-open", {"type": "debugOpen"}],
	["resend scene", {"type": "debugScene"}],
]


func _ready() -> void:
	for pair in BUTTONS:
		var button := Button.new()
		button.text = pair[0]
		var message: Dictionary = pair[1]
		button.pressed.connect(func() -> void: Net.debug(message))
		add_child(button)
	Table.transcript_changed.connect(_relock)
	Table.started_changed.connect(_relock)
	_relock()


func _relock() -> void:
	for button in get_children():
		(button as Button).disabled = Table.awaiting_dm


## F12 writes the current frame to a file. Not a nicety: Phase B's work is judged by comparing
## against the browser client at the same camera corner, and a frame on disk is something a
## reviewer — human or otherwise — can actually open. It also survives the session, which a
## glance at a running window does not.
func _unhandled_key_input(event: InputEvent) -> void:
	if not (event is InputEventKey and event.pressed and event.keycode == KEY_F12):
		return
	var image := get_viewport().get_texture().get_image()
	var path := "user://shot-%d.png" % Time.get_ticks_msec()
	image.save_png(path)
	print("[debug] wrote ", ProjectSettings.globalize_path(path))
```

- [ ] **Step 2: Find where the screenshots land**

```bash
cd godot && godot --headless --quit -s /dev/null 2>/dev/null; ls ~/Library/Application\ Support/Godot/app_userdata/Emberdelve/
```

`user://` maps to `~/Library/Application Support/Godot/app_userdata/Emberdelve/` on macOS. Note the path — every "compare side by side" step in Phase B writes here.

- [ ] **Step 3: Capture the run log too**

Daily loop for anything visual. `print`, `push_warning`, `push_error` and every script error land in one file:

```bash
cd godot && godot --path . 2>&1 | tee /tmp/emberdelve-run.log
```

- [ ] **Step 4: Run the full Godot suite**

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: PASS, 0 failures.

- [ ] **Step 5: Phase A checkpoint — play it with no world at all**

```bash
cd server && ./gradlew run --args='--demo'
```

In Godot, press F5 and work through this list. Every item must be true before Phase B starts, because none of them is easier to debug with a 3D scene in front of it.

- [ ] The title appears over an empty background; clicking it narrates the opening, spoken, with the text landing **as each line is said**.
- [ ] `roll d20` throws a die: rattle, tumble, settle at ~1s, total legible at ~1.5s, and the log entry appears only after that.
- [ ] A typed turn is echoed instantly in blue, the input locks, and the DM answers with dice before prose.
- [ ] `start combat` plays a six-layer sting, holds 1200ms, then assembles the bar; nothing the DM says arrives during the hold.
- [ ] `spawn goblin` plays the five-layer lid.
- [ ] Typing a new turn mid-narration lets the sentence in the air finish and drops the rest — **and every dropped line is still in the transcript**.
- [ ] Killing the fighter shows defeat; restart returns to the title; the next click narrates a fresh room.
- [ ] Stopping and restarting the Gradle server reattaches without a click.

- [ ] **Step 6: Commit**

```bash
git add godot/chrome && git commit -m "Add the debug bar, and close out a game that is playable with no world in it"
```

---

## Phase B — the world

Phase B builds the crypt under the chrome that already works. Nothing in Phase A changes; `World` becomes one more subscriber to `Table` (invariant #3), and it is created exactly once (invariant #4).

`client/src/scene/Renderer.ts`, `props.ts`, `tokens.ts` and `assets.ts` are about 2,400 lines between them and are the bulk of this migration. Read each before the task that ports it. Every constant below is quoted from them with its line number — none of them is up for renegotiation without a played session behind it.

---

### Task 15: The pixel pipeline and the camera

**Files:**
- Create: `godot/world/world.tscn`, `godot/world/world.gd`
- Create: `godot/world/camera_rig.gd`
- Modify: `godot/chrome/chrome.tscn` (put `World` under `SubViewportContainer`)

**Interfaces:**
- Consumes: `Table.scene_changed`, `Table.mode_changed`.
- Produces: `World` (a `Node3D`) with `grid_to_world(x: int, y: int) -> Vector3`, `world_to_grid(point: Vector3) -> Vector2i`, `rig: CameraRig`. `CameraRig` with `rotate_by(steps: int)`, `frame(mode: String)`, `follow(point: Vector3)`.

Node tree, and the three decisions it locks:

```
Chrome (CanvasLayer)
└── WorldView (SubViewportContainer, stretch = true, texture_filter = Nearest)
    └── SubViewport (size 480 x 270, snap_2d_transforms_to_pixel = true)
        └── World (Node3D, script world.gd)
            ├── Camera3D (orthographic, script camera_rig.gd)
            ├── Room (Node3D)     # Task 17
            ├── Props (Node3D)    # Task 18
            ├── Tokens (Node3D)   # Task 19
            └── Overlay (Node3D)  # Task 20
```

1. **The world is pixelated; the chrome is not.** Project-wide stretch mode would pixelate the transcript's type and the dice tray along with the room. Only this `SubViewport` is low-resolution; every `Control` in Task 11 draws on top of it at native resolution.
2. **480 wide** (`Renderer.ts:34` `TARGET_WIDTH`), nearest-neighbour on the container.
3. **The camera snaps to whole low-resolution pixels.** Not polish — see Step 3.

- [ ] **Step 1: Build the viewport**

Create the tree above. `SubViewportContainer.stretch = true`, and set its `texture_filter` to `TEXTURE_FILTER_NEAREST`. The `SubViewport` height follows the window aspect at 480 wide.

- [ ] **Step 2: Write the camera**

Create `godot/world/camera_rig.gd`. Orthographic `Camera3D`, from `Renderer.ts:373`:

```gdscript
extends Camera3D

## Four fixed isometric corners, 90 degrees apart, snapped. Never free orbit.
##
## Free orbit would break the look: the pixelation pass and the whole isometric read only hold
## at these four angles, because they are the angles that resolve tile edges onto the same
## screen-space slopes every frame.
##
## Camera facing is view state, not a game fact — it does not live in Table.

const DISTANCE := 30.0
## atan(1 / sqrt(2)). The true isometric elevation.
const ELEVATION := 0.6154797086703873
const ROTATION_STEP := PI / 2.0
const ROTATION_SECONDS := 0.55

const EXPLORATION_HALF_HEIGHT := 5.4
const COMBAT_MAX_HALF_HEIGHT := 10.0
const FRAMING_SECONDS := 1.1
const FOLLOW_SECONDS := 0.34
## How far the followed token may drift from centre before the camera moves at all.
const FOLLOW_SLACK := 1.4
const ROOM_MARGIN := 0.7

var corner := 0
var half_height := EXPLORATION_HALF_HEIGHT
```

Port `rotation()`, `framing()`, `follow()` and `snapFocus()` from `Renderer.ts:373-520`. Q and E step `corner` by one and ease over `ROTATION_SECONDS`.

- [ ] **Step 3: Port `snapFocus` and do not skip it**

`Renderer.ts:406-425`. It quantises the camera's focus point to whole low-resolution pixels:

```gdscript
## Quantises the focus point to whole low-resolution pixels.
##
## Nothing about a camera that translates smoothly ever shows this at native resolution. At
## 480px with nearest-neighbour upscaling, a camera that moves by a fraction of a low-res pixel
## resamples the entire frame, and every edge in the room crawls. The focus is snapped so the
## camera can only ever move in exact pixel steps, which is what keeps the art still underneath
## it.
func _snap_focus(focus: Vector3) -> Vector3:
	var rows := maxf(1.0, roundf(get_viewport().size.y))
	var unit := (half_height * 2.0) / rows
	var right := global_transform.basis.x
	var up := global_transform.basis.y
	var along := roundf(focus.dot(right) / unit) * unit
	var above := roundf(focus.dot(up) / unit) * unit
	var forward := focus - right * focus.dot(right) - up * focus.dot(up)
	return forward + right * along + up * above
```

Verify by eye: with combat framing active and a token walking, the *floor texture* must not shimmer. If it does, the snap is not being applied to the focus the camera actually uses.

- [ ] **Step 4: Verify**

Run the game with the debug bar. Q and E must snap between exactly four angles with a 0.55s ease and no in-between resting position. Nothing is rendered yet, so judge this against the camera gizmo in the editor's remote tree.

- [ ] **Step 5: Commit**

```bash
git add godot/world godot/chrome/chrome.tscn && git commit -m "Render the world at 480 pixels, from four corners that never drift"
```

---

### Task 16: The edge pass

**Files:**
- Create: `godot/world/pixel.gdshader`
- Modify: `godot/world/world.tscn`

**Interfaces:** none. This is a `ColorRect` shader over the `SubViewport` texture.

`RenderPixelatedPass` is doing two jobs and only one of them comes free with a `SubViewport`. `Renderer.ts:358`:

```
this.pixelPass.normalEdgeStrength = 0.5;
this.pixelPass.depthEdgeStrength = 0.25;
```

That is a depth-and-normal edge detector, and without it the Godot room reads flatter than the Three.js one for reasons that are very hard to guess at from a screenshot.

- [ ] **Step 1: Write the shader**

Create `godot/world/pixel.gdshader`: a `canvas_item` shader sampling `DEPTH_TEXTURE` and `NORMAL_ROUGHNESS_TEXTURE` at the four neighbours of each texel, darkening where the depth discontinuity exceeds a threshold (weight `0.25`) and lightening where the normal discontinuity does (weight `0.5`). Keep both strengths as `uniform float` so they can be tuned in the inspector rather than in code.

- [ ] **Step 2: Compare side by side**

```bash
cd client && npm run dev
```

Open both clients on the crypt, same camera corner, and compare. Expected: the sarcophagus and the pillars have the same read — outlined where they meet the floor, not floating on it.

**Timebox this to one evening**, together with Task 21. `AGENTS.md`: *"Do not tune lighting and post-processing for more than one evening. This is the single largest time sink in the project and it will consume as much as you give it."*

- [ ] **Step 3: Commit**

```bash
git add godot/world && git commit -m "Put the edges back on, which a plain viewport does not give you"
```

---

### Task 17: The room — floor, walls, and the lighting presets

**Files:**
- Create: `godot/world/room.gd`
- Create: `godot/world/lighting.gd`
- Create: `godot/world/kits/dungeon/` (copied from `client/public/assets/kits/dungeon/`)

**Interfaces:**
- Consumes: `Table.scene` (`width`, `height`, `floorType`, `wallType`, `lighting`), `World.grid_to_world`.
- Produces: `Room.rebuild()`, called on `scene_changed` when `roomId` changes.

Ports the floor and wall halves of `Renderer.ts` and `assets.ts`. `FloorType` is `STONE` / `CRACKED_STONE` / `TILED`; `WallType` is `STONE` / `CARVED`; `LightingPreset` is `TORCHLIT` / `DIM` / `DARK`.

**Each Kenney kit needs its own folder.** Every Kenney GLB references `Textures/colormap.png` by *relative* path, and the mini and graveyard kits ship **different** colormaps under that same name. Putting two kits in one directory silently renders one of them in the other's palette. This applies to Godot's importer exactly as it did to Three.js.

- [ ] **Step 1: Import the dungeon kit**

```bash
mkdir -p godot/world/kits && cp -R client/public/assets/kits/dungeon godot/world/kits/dungeon
```

Open the editor once so Godot generates `.import` files, and set the importer's texture filter to **Nearest** for every texture in the kit. A bilinear colormap at 480px is a smear.

- [ ] **Step 2: Write `room.gd`**

Plain instanced `MeshInstance3D`s under a `Room` node, rebuilt only when `roomId` changes (invariant #4 — the world is one scene, not rebuilt by chrome redraws). Port the tile choice per `floorType` and the perimeter per `wallType` from `Renderer.ts`.

Do **not** reach for `GridMap`. It is an optimisation for large tilemaps and this room is 10 to 16 squares; it constrains prop rotation to the cell grid, which `Prop.rotation` is not; and `AGENTS.md` says *"Do not optimize anything. One room, two entities."*

- [ ] **Step 3: Write `lighting.gd`**

Three light groups, one per `LightingPreset`, authored in the editor and switched by visibility — the spec's "the editor authors lights; Table decides which group is on".

The crypt is lit by its **braziers**: `OmniLight3D`s at `EMBER` (`ff9a3d`) with a near-white core (`ffd489`), because fire is not one colour and at 480x270 that core is all the fire there is. `MAX_TORCH_LIGHTS` is 10 (`Renderer.ts:120`).

**These are the numbers that do not port.** `FLAME_INTENSITY = 26` is candela under Three.js physical decay; Godot's `OmniLight3D` is energy, range and an attenuation curve, and there is no conversion worth trusting. Re-tune by eye — and *the room is supposed to be dark away from the braziers*. `AGENTS.md`: do not fix that by raising the ambient. No `DirectionalLight3D`: a sun in a crypt is the one lighting mistake that would undo every hour M0 spent here.

- [ ] **Step 4: Verify against a generated room**

```bash
cd server && ./gradlew run --args='--generate 7'
```

Expected: the room's own `floorType`, `wallType` and `lighting` are what render — not the crypt's. This is parity gate item 6, and it is why it exists.

- [ ] **Step 5: Commit**

```bash
git add godot/world && git commit -m "Lay the floor and walls the room asks for, and light it with its own fires"
```

---

### Task 18: The props

**Files:**
- Create: `godot/world/prop_table.tres`, `godot/world/prop_table.gd`
- Create: `godot/world/props/sarcophagus.tscn`, `brazier.tscn`, `pillar.tscn`, `rubble.tscn`, `alcove.tscn`, `door.tscn`
- Create: `godot/world/kits/props/` (copied per-pack, one folder each)

**Interfaces:**
- Consumes: `Table.scene.props`, `Table.prop_revealed`.
- Produces: `PropTable.scene_for(type: String) -> PackedScene`.

Port of `client/src/scene/props.ts` (481 lines). **This is the single largest task in the plan** and it is also the reason for the whole migration: half these props are primitives composed in code with proportions tuned by eye, and an editor is a better place to hold them than a `THREE.Group` constructor.

- [ ] **Step 1: Keep the seam as data**

Create `godot/world/prop_table.gd`, a `Resource` mapping `PropType` to a scene path, and `prop_table.tres` holding the six entries. This is `MESH_PROPS` from `props.ts:14`: *"Promoting a prop is one table entry, and demoting it — because a model reads badly at 480x270, say — is deleting one. Neither touches the scene schema."* Keeping it as a resource rather than editor-only wiring is what keeps that true and keeps it diffable.

- [ ] **Step 2: Author the six props**

Rebuild each as a `.tscn`. The finished heights are fractions of the wall, which stands exactly one square (`props.ts:47`): `TOMB_HEIGHT` 0.58, `BRAZIER_HEIGHT` 0.65, `ALCOVE_HEIGHT` 0.73.

The sarcophagus is the one to get right, and `props.ts:52` says exactly why it stays primitives: no imported pack ships one, and four things make it read as a tomb rather than a crate — it is **tiered** so the silhouette steps; the body **tapers** toward the top; there is a **void under the lid** so the gap is black rather than more stone; and the lid is **shifted, turned and tilted** far enough to see, because "something has been working at it from the inside" is the sentence on the title screen and should be legible in the object.

The brazier burns `EMBER` (`ff9a3d`) with a `FLAME_CORE` (`ffd489`) centre. It used to burn green, and `props.ts:22` records why that was wrong: diffuse shading multiplies the light by the surface, so a hue with no complement in the palette stops reading as stone that is lit and starts reading as stone that has been tinted.

- [ ] **Step 3: Spawn from the scene**

Instance one prop per `Table.scene.props` entry at `grid_to_world(x, y)` with `rotation`. `hidden` props are not instanced at all; `Table.prop_revealed` adds one.

- [ ] **Step 4: Verify**

Boot the crypt and compare against the browser client at the same camera corner. Then boot `--generate 7` and confirm the prop variants place correctly.

- [ ] **Step 5: Commit**

```bash
git add godot/world && git commit -m "Author the props in an editor, which is the point of all this"
```

---

### Task 19: The tokens

**Files:**
- Create: `godot/world/tokens/token.tscn`, `godot/world/tokens/token.gd`
- Create: `godot/world/kits/characters/graveyard/`, `godot/world/kits/characters/mini/`

**Interfaces:**
- Consumes: `Table.scene.entities`, `Table.entity_added`, `Table.entity_moved`, `Table.entity_died`, `Table.strike`.
- Produces: `Token` with `slide_to(square: Vector2i)`, `swing()`, `set_hp(hp: int, max_hp: int)`, `die()`.

Port of `client/src/scene/tokens.ts` (430 lines) and `AGENTS.md` section Characters.

- [ ] **Step 1: Import both kits, in separate folders**

```bash
mkdir -p godot/world/kits/characters && cp -R client/public/assets/kits/characters/graveyard godot/world/kits/characters/graveyard && cp -R client/public/assets/kits/characters/mini godot/world/kits/characters/mini
```

**One folder each, and this is not negotiable** — both kits ship a different `Textures/colormap.png` under the same relative name, and merging them silently renders one kit in the other's palette. It costs an hour to find.

- [ ] **Step 2: Write `token.gd`**

Every model across these kits carries the **same 32-clip rig** — `idle`, `walk`, `die`, `attack-melee-right` and so on — so swapping a character is one line in the model table and nothing else. Godot's glTF import gives every instance its own `AnimationPlayer`, so the `SkeletonUtils.clone` hazard in `tokens.ts:` (a plain clone shares the rig, and two tokens of one model animate in lockstep) **does not exist here**. That is one of the few things this migration makes simpler.

Constants, from `tokens.ts`:

- `IMPACT_SECONDS` 0.22 — *"a little past halfway through the 417ms swing clip"*. It gates the hit point drain **and** the death clip: a creature that falls as the sword starts moving has died of something the player never saw land.
- `DRAIN_SECONDS` 0.34, `BAR_WIDTH` 0.72, `BAR_HEIGHT` 0.085, `CROSSFADE_SECONDS` 0.2, `SELF_LIT` 0.22.
- Figure height **1.25** on a 1.0 square. Deliberately oversized: at 480px a to-scale human is ~24 pixels and reads as a smudge, and oversizing the figure relative to its base is what tactical RPGs do for exactly this reason.
- `move_seconds(squares) = min(0.18 + squares * 0.07, 0.75)` — **shared with the footstep audio** in Task 10. Two copies of that number would drift apart the first time anyone retuned movement, so define it once and have both read it.

Origins differ between kits — mini models stand on y=0, graveyard models are centred on the hips — so measure the bounding box rather than keeping a table of offsets.

**Characters carry a faint emissive of their own colormap** (`SELF_LIT` 0.22). The crypt is genuinely dark away from the two braziers, which is right for the room and wrong for the figures standing in it. In Godot this is `emission_texture = albedo_texture` with a low `emission_energy`, set per material on import. **Do not "fix" it by raising the ambient.**

**Health bar colour is allegiance, not health.** Green is yours, red is theirs; length carries hit points.

**Death leaves a body.** The entity stays at 0 hp, the token clamps on its `die` clip, and it revives if the server ever reports hit points above zero again — dead is what the server says now, never what it once said.

- [ ] **Step 3: Movement is a slide, not physics**

`Tween` from the current square to the new one over `move_seconds(squares)`, with the `walk` clip playing and `idle` after. No `CharacterBody3D`, no collision — the server already approved this move (invariant #1).

- [ ] **Step 4: Verify**

Use `spawn goblin` and `start combat`. Expected: the fighter reads clearly against the dark floor without the room getting brighter; the goblin's death drops on the blow, not before it.

- [ ] **Step 5: Commit**

```bash
git add godot/world && git commit -m "Put creatures on the board, oversized enough to read at 480 pixels"
```

---

### Task 20: Clicking the board

**Files:**
- Create: `godot/world/overlay.gd`
- Modify: `godot/world/world.gd`

**Interfaces:**
- Consumes: `Table.scene.combat.legalMoves` / `legalTargets`, `Net.move_to`, `Net.attack`.
- Produces: nothing other tasks consume.

Port of `intent()` in `client/src/ui/Canvas.tsx`.

**The entire client-side movement rule is one membership test.** `CombatView` carries `legalMoves` and `legalTargets` already decided; nothing here knows about speed, reach or blocking props. This is invariant #1 taken literally, and it is why M1 can add difficult terrain without touching the client.

- [ ] **Step 1: Highlight what is legal**

Quads at `OVERLAY_Y` 0.03 above the floor (`Renderer.ts:263` — *"coplanar with it z-fights, and at 480px a z-fight is a strobe"*). Tints from `Renderer.ts:259`: `MOVE_TINT` `5c86c4`, `TARGET_TINT` `c0453c`, `HOVER_TINT` `e8dcc0`.

- [ ] **Step 2: Route the click**

A click on a square in `legalMoves` sends `moveTo`; a click on an entity in `legalTargets` sends `attack`; anything else does nothing. Raycast from the `SubViewport`'s camera — remember to convert the mouse position out of window space into viewport space, or every click lands one tile off.

- [ ] **Step 3: Play the swing on `strike`, not on the click**

`Table.strike` fires after the dice land, and the swing plays from there. The hit point bar, the damage line and the blow are all consequences of one roll and must arrive together — that is what `IMPACT_BEAT_MS` bought, and routing the swing off the click would spend it.

- [ ] **Step 4: Verify the budget**

Click-to-move must start the slide inside 100ms. There is no model in this path; if it is slow, something is rebuilding the world on `scene_changed` instead of moving a token.

- [ ] **Step 5: Commit**

```bash
git add godot/world && git commit -m "Let the board be clicked, and let the server say what a click may do"
```

---

### Task 21: Framing the fight

**Files:**
- Modify: `godot/world/camera_rig.gd`

**Interfaces:** consumes `Table.mode_changed`, `Table.combat_beat`.

Port of the framing half of `Renderer.ts:440-520`.

- [ ] **Step 1: Two framings and a follow**

Exploration sits at `EXPLORATION_HALF_HEIGHT` 5.4 and follows the party. Combat pulls back to fit every combatant, capped at `COMBAT_MAX_HALF_HEIGHT` 10, over `FRAMING_SECONDS` 1.1 — which is the visible half of the ceremony, and is why `CEREMONY_MS` is 1200.

Follow eases over `FOLLOW_SECONDS` 0.34 and only once the followed point is more than `FOLLOW_SLACK` 1.4 units off centre. Without the slack the camera never stops moving and the pixel snap has nothing to hold still.

- [ ] **Step 2: Verify the ceremony**

`start combat` from the debug bar. Expected, in order: the six-layer sting, the camera pulling back, the bar assembling, and only then the DM's first line about the fight. If the narration lands during the pull-back, the ceremony hold in `Table._settle_combat` is missing.

- [ ] **Step 3: Commit**

```bash
git add godot/world && git commit -m "Pull the camera back when steel comes out, and let it settle before anyone speaks"
```

---

### Task 22: The parity gate, and deleting `client/`

**Files:**
- Delete: `client/`
- Modify: `server/src/main/java/dm/App.java` (remove CORS)
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-08-21-godot-client-design.md` (status)

**This gate is played, not tested.** Latency numbers in `docs/m0-evaluation.md` stay recorded, not the grade. The grade is the same question M0 asked: does it feel like a Dungeon Master is running the game.

- [ ] **Step 1: Run the gate**

One session, on the real Java server, `--demo` dice, authored crypt, **Godot as the only human-facing client for that run** — close the browser tab; the guard from Task 2 will otherwise refuse it.

- [ ] 1. The room renders: braziers, sarcophagus, door, fighter token.
- [ ] 2. Title click sends `begin`; the opening is spoken and appears in the overlay **as it is spoken**.
- [ ] 3. A typed inspect or heave: dice in the tray, then narration, then a visible world change.
- [ ] 4. Combat: camera pull-back, ceremony, legal-move clicks, a swing that waits for the die, hit points draining on impact, the goblin's turn narrated as one beat.
- [ ] 5. Death: the body stays, the defeat overlay appears, restart returns to the title, and the click after it narrates a fresh room.
- [ ] 6. A generated room: `./gradlew run --args='--generate 7'` renders its own `floorType`, `wallType`, lighting and prop variants.
- [ ] 7. The killing blow sounds like an event, not a clang. Nine layers.

**If any item fails, stop here.** `client/` stays and remains the known-good table. Fix and re-run the whole list — a gate run in pieces is not a gate.

- [ ] **Step 2: Delete the old client in one commit**

Only once every box above is ticked.

```bash
git rm -r client && cd server && ./gradlew test
```

In `server/src/main/java/dm/App.java`, remove the CORS rule — the Vite origin it existed for is gone:

```java
        var app = Javalin.create();
```

- [ ] **Step 3: Rewrite the docs that now describe a client that does not exist**

In `AGENTS.md`: the opening line ("Java backend, React + Three.js frontend"), invariants 3 and 4 (Zustand and the `useRef` renderer become `Table` and the one `World` scene), the Characters, Voice, Dice and Sound sections' file references, and the Commands block. Do not rewrite the *findings* — every one of them still holds; only the filenames change.

In the spec, set `**Status:** Shipped` and note the gate date.

- [ ] **Step 4: Run everything**

```bash
cd server && ./gradlew test
```

```bash
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit
```

Expected: both PASS. There is no `npx tsc` any more.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Make Godot the only table, and take the web client down with it"
```

- [ ] **Step 6: Unpause M1**

`docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` is unblocked. Its Java tasks stand as written; its client tasks need re-planning against Godot before anyone starts it. Note that at the top of the plan rather than letting the next reader discover it.

---

## What this plan deliberately leaves undone

- **Distribution.** No `.app`, no JRE, no signing. Spec section 12 records both live options and what each costs. Decide it in front of the working prototype this plan produces.
- **The hosted DM.** Session scoping and auth are the multiplayer milestone's work. The one thing this plan spends to keep it open is `EMBERDELVE_SERVER`.
- **Dungeon navigation.** Task 22 Step 6.
- **3D physics dice.** Spec section 7 records the reasoning and the re-entry condition: after the gate, if the tray reads as flat beside the world, in a played session.
