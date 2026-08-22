# Godot desktop client

**Status:** Design approved, pre-implementation
**Companion to:** `docs/m0-evaluation.md` (the feel that must survive), `docs/m0-build-plan.md`
(invariants), `AGENTS.md` (presentation numbers), `docs/ai-dm-system-design.md` (long-range stack)
**Supersedes:** `docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md` §8b
("Three.js stays for M1"). Procedural generation remains server-side; the client that renders
`SceneState` becomes Godot after the parity gate below.

---

## 1. What this is for

M0 proved a Java DM plus a thin client can feel like a Dungeon Master at a table. The client that
proved it is a Vite page: React chrome, a Three.js canvas, Web Audio, Web Speech. That stack is
wrong for two things this project now wants:

1. **Authoring.** Lighting, animation, audio buses, and Kenney imports are being tuned in code
   because there is no editor.
2. **Sharing.** Friends should double-click a desktop app, not open `localhost:5173`. Steam is a
   later wrapper around that app, not a requirement of this design.

Success criterion, in M0's style — a thing you do, not a box you tick:

> A friend who is not the author double-clicks Emberdelve on a Mac, hears the opening, heaves
> the sarcophagus, fights the goblin, and can say afterwards that a Dungeon Master ran the game.

The Java server stays the DM. Godot becomes the table.

---

## 2. Locked decisions

| Area | Decision |
|---|---|
| Engine | Godot 4, current stable |
| Client language | GDScript only. No C#, no GDExtension for this client |
| Process model | Two processes. Godot is the parent. Java is a local sidecar. Editor attaches to Gradle; the `.app` spawns |
| First ship | A macOS `.app` friends can double-click. Windows is the same layout later. Steam is later |
| Protocol | Same JSON WebSocket + `POST /tts` + `GET /health`. One additive field: `Hello.dm`. Godot is another speaker of `client/src/types.ts`, not a new protocol |
| Dual client | Vite/Three.js stays until Godot replays the M0 acceptance beats. Then `client/` is deleted in the same change that makes Godot the only client |
| Chrome | WoW-style overlay on the 3D view. One stream (prose + rolls). No fade while a line is being spoken |
| Dice | Predetermined faces from the server. 2D tray, not RigidBody. `tumble.ts` math ports as numbers |
| Voice | `POST /tts` first; OS TTS (`DisplayServer.tts_*`) per line on failure. No Web Speech |
| Secrets | Stay in Java. Godot never reads `.env` or API keys |
| Bind | Sidecar listens on `127.0.0.1` only |

---

## 3. Out of scope

| Deferred | Why |
|---|---|
| Steam, Steamworks, depots, overlay | Packaging a `.app` is the ship. Steam zips that bundle later |
| Windows / Linux exports | Same two-process layout; not in this spec's deliverable |
| Rewriting the DM in GDScript | Combat, dice, tools, LLM, TTS synthesis stay Java |
| New tools, props, rooms, rules | Invariant #10. This is a client swap |
| Physics-driven dice or grid | Would fight invariants #1 and #6 |
| Keeping a web client after parity | Dual-client is a bridge, not a product |
| Hybrid webview (Godot 3D + React chrome) | Two UI stacks, still not a normal app |
| Notarization / paid Apple Developer extras beyond "friends can run it" | Gate is feel, not App Store |
| Rewriting M1 procgen | Server still emits `SceneState`; Godot renders whatever arrives |

---

## 4. Architecture

```
┌──────────────────────── Godot (the app) ─────────────────────────┐
│  Sidecar ── spawn or attach Java, owns PID only if it spawned     │
│  Net ────── JSON WebSocket, ClientMessage / ServerMessage         │
│  Table ──── scene, transcript, rolls, awaiting_dm  (the store)    │
│  Clock ──── speak / mark / hold / silence / silence_now           │
│  World ──── crypt scene, tokens, camera, pixel viewport           │
│  Chrome ─── WoW overlay, dice tray, combat bar, title, defeat     │
│  Voice ──── POST /tts, else OS TTS                                │
│  SFX ────── Godot buses, measured layers from AGENTS.md           │
└───────────────────────────────┬──────────────────────────────────┘
                                │  ws://127.0.0.1:<port>/ws
                                │  POST /tts   GET /health
┌───────────────────────────────┴──────────────────────────────────┐
│  Java sidecar (unchanged DM: engine, tools, LLM, ElevenLabs)      │
│  Binds 127.0.0.1, honour --port (default 7070)                    │
└──────────────────────────────────────────────────────────────────┘
```

Game facts live in Table. World, Chrome, and SFX subscribe. Putting HP, mode, or legal moves in
a Control node's locals is the same bug invariant #3 exists to prevent.

Feel findings port as **numbers**, not as Three.js or React code. The authoritative list is
`AGENTS.md` (dice, impact, ceremony, token scale, audio layers). If a Godot scene disagrees with
those numbers, the numbers win until a played session replaces them.

---

## 5. Process model

Godot is the process friends launch. Java is a child whose job is to be the DM.

### Launch modes

**Attach (Godot editor).** On boot, `GET http://127.0.0.1:7070/health`. If it returns `ok`, connect
and do not spawn. That is how `cd server && ./gradlew run` keeps driving both Vite and the editor.
The editor never kills that server on quit. If health fails, show “start the DM with
`cd server && ./gradlew run`” and retry health; do not spawn Gradle from Godot (working directory
and `.env` discovery stay the developer’s).

**Spawn (packaged `.app` only).** Godot is the executable. It starts the bundled JRE + server jar
with `--port <n>`, polls `/health` for up to 15 seconds, then opens the WebSocket. The WebSocket
URL is derived from that port — never a second hardcoded constant. On a clean quit, SIGTERM the
child, then SIGKILL after two seconds. Spawn records the PID. If the port is taken, show “the
table is already open” and do not attach to whatever is there.

Default port is **7070**. `--port` exists so two copies of the `.app` do not collide. Godot
passes it when it spawns.

### Dual-client rule

Do not connect Vite and Godot to one server at the same time. `WsHandler` is one session, one
connection: a second socket gets `hello` and `scene`, then later diffs go to whichever connection
sent the last action. Sequential use during the bridge is fine. Simultaneous use is not.

### Bind and CORS

The sidecar binds **`127.0.0.1` only**. It is a local DM, not a network service. CORS `anyHost`
may remain while Vite exists (the Vite origin is still another localhost port). CORS is removed
in the same change that deletes `client/`.

### Config and secrets

`.env` stays on the Java side. `Config` already walks up from the working directory; environment
variables still win. Packaged, the child's working directory is
`~/Library/Application Support/Emberdelve/`. Godot does not parse `.env`. If `Hello.dm` is false,
toast that path so a friend who skipped keys is not looking at a mute crypt.

### Voice transport

Speech stays **HTTP `POST /tts`**, body `{ speakerId, text }`, response `audio/mpeg`. A minute of
MP3 must not share the WebSocket with click-to-move. Godot plays the bytes. If the status is not
200, that line only falls back to OS TTS. Missing `ELEVENLABS_API_KEY` is an OS-voice session,
logged by the server as today, not a crash.

---

## 6. Java changes (only these)

The DM does not grow tools, rooms, or rules. Java grows just enough to be a sidecar:

1. Bind host `127.0.0.1`.
2. Honour `--port` (default 7070).
3. Log PID and bind address at boot so a spawn hang is one line.
4. A second overlapping listen is a hard fail with a clear message.
5. `Hello` gains `dm: boolean` (Venice is configured), parallel to existing `voice`. While
   `client/` lives, add the same field to the TypeScript type so the two clients do not drift.

`GET /health` already returns `ok`. `POST /tts` and `/ws` stay. `begin`, `restart`, debug
messages, and demo dice stay. Records and Jackson discriminators stay. The Godot client mirrors
them in GDScript; there is still no IDL until M1's existing plan for one.

**One additive field:** item 5 above. Godot uses `Hello.dm` for the missing-key toast. That is
the same envelope the browser already needed for `voice`.

---

## 7. Godot modules

New tree: `godot/` at the repo root. Autoloads hold facts and I/O; scenes hold the table.

### Sidecar

**Interface:** `ensure_server() -> { url, spawned }`; `shutdown()` kills the child only if
`spawned` is true.

Does not parse game messages. Everyone else asks it for the base URL.

### Net

**Interface:** `connect(url)`; `send(message)`; signals for each `ServerMessage.type`.

JSON in, JSON out. Reconnects on drop in attach mode (server was started in a terminal and might
restart). In spawn mode a dead child is a sidecar failure, not a silent reconnect loop. Does not
apply diffs, speak, or draw.

### Table

The Zustand store, as an autoload. Holds `scene`, `mode`, `transcript`, `rolls`, `awaiting_dm`,
`started`, `active_roll`, `dice_dismiss_at`, `strike`, `combat_beat`, `error`.

Applies diffs exactly as `client/src/store.ts` does:

- `CombatChanged` replaces the whole combat view, never merges.
- Entity ids are idempotent; a second spawn of the same id replaces.
- The lid-open sting fires on the first non-player `EntityAdded`.
- Click-to-move and click-to-attack only test `legalMoves` / `legalTargets` from the wire
  (invariant #1).
- `started` is one-way; a dropped socket reconnects to a session already under way.

World, Chrome, and SFX subscribe to Table. They never own those facts.

### Clock

The presentation clock. **This is the load-bearing port.** Same operations as
`client/src/audio/narration.ts`:

| Call | Meaning |
|---|---|
| `speak(segment, reveal)` | Queue a line. `reveal` runs when the voice reaches it (commits transcript) |
| `mark(reveal)` | Queue a callback with no speech. Still queued when voice is off |
| `hold(ms, reveal)` | `mark`, then keep the floor for `ms` (dice in the air, combat ceremony) |
| `silence()` | Drop the rest of the queue, let the line in the air finish, **still run every dropped `reveal`** |
| `silence_now()` | `silence()` plus stop the current utterance. Errors only |

Ordering rules, copied not reinvented:

- Diffs, rolls, and narration enter the clock in arrival order.
- Dramatic rolls (`ATTACK`, `SAVE`, `SKILL_CHECK`) take the floor for
  `revealAt(face_count) + beat`. `beat` is `IMPACT_BEAT_MS` (650) for attacks, 0 otherwise.
  Damage and initiative do not animate; they `mark` into the log.
- Combat opening holds `CEREMONY_MS` (1200).
- Voice-off still queues and reveals; it only skips `backend.speak`.
- A new player turn calls `silence()`, then appends the player line and sets `awaiting_dm`.
- Errors call `silence_now()` and bypass the queue.
- Prime at most one ElevenLabs line ahead.

Hold is a timer, not a signal from the tray. If the tray never mounts, the queue still moves.

### World

One crypt scene, authored in the Godot editor. `SceneState` is a **kit instance list**, not a
baked unique room: floor/wall types, prop ids and poses, entity kinds and grid positions,
`LightingPreset`. The editor authors lights, meshes, and animation; Table decides which instances
are present and which light group is on.

Constraints that survive the port:

- Internal render width 480, nearest-neighbour upscale (pixel read).
- Four isometric corners, 90° snap (Q/E). No free orbit.
- Exploration vs combat framing as in `Renderer.ts` (`EXPLORATION_HALF_HEIGHT`, combat cap,
  follow). Camera facing is view state, not Table.
- Kenney kits: **one folder per kit** (colormap paths). Graveyard vs mini do not share a
  directory.
- Tokens: clips `idle`, `walk`, `attack-melee-right`, `die`. Oversized vs the 1.0 square.
  Faint self-lit so they read in a dark crypt. `IMPACT_SECONDS = 0.22` gates HP drain and the
  death clip.
- No physics on the grid. Movement is a slide the server already approved.
- Health bar colour is allegiance, not remaining HP. Length carries hit points.

### Chrome

Full-window crypt. UI is overlay, not a web sidebar.

**Chat (WoW-shaped):** translucent panel over the world, outlined type, colour by speaker,
mouse-wheel scrollback, `LineEdit` docked under the log, Enter to send. Godot `RichTextLabel` +
`LineEdit`.

Copy from WoW: overlay, contrast, colour, scrollback, input docked to the log.

Do **not** copy WoW tabs. One stream: prose and rolls in arrival order, the same list as
`TranscriptEntry`. A General/Combat split would put the die in one place and the voice in
another.

Do **not** copy WoW idle fading while a line is being spoken. The transcript is the DM, paced by
Clock. The panel may dim or shrink when nothing is queued and `awaiting_dm` is false; it must not
fade a line that is still being said.

Input is locked while `awaiting_dm`. Debug controls are locked while the DM has the floor (same
collision as today's debug bar vs opening narration). Title click is the user gesture that
unlocks audio and sends `begin`. Defeat offers restart. Net sends `restart` and stays up; when the new `scene` arrives, Table
clears transcript/rolls/combat and World rebuilds. Do not quit Godot and do not respawn Java
(that is the browser's `location.reload`, which Godot does not have).

Dice tray is 2D, driven by a port of `client/src/dice/tumble.ts` (`WIND_UP_MS`, `landAt`,
`revealAt`, `HOLD_MS` 9000, `COMBAT_HOLD_MS` 3000, `FADE_OUT_MS`). Tumbling faces are noise;
settled faces are `result.faces[i]`. Combat dismisses the tray on the swing, not on narration.

### Voice and SFX

`VoiceBackend` seam: `speak(line)` resolves on end, error, or cancel and never rejects; `stop()`;
optional `prime(line)`. Adapters: HTTP MP3 from `/tts`, OS TTS. Casting table stays closed
(narrator / goblin / fighter) and degrades to pitch/rate when the OS has no named voice.

SFX move onto Godot buses — the production reason to switch. Layer counts and delays stay those
in `AGENTS.md` (move 4, reveal 2, lid 5, combat begin 6, miss 4, hit 6, kill 9). `rate` still
does real work. Kenney fighter voiceover pack stays unused.

---

## 8. Data flow

1. Boot → Sidecar `ensure_server` → Net connect → `hello` + `scene` into Table → World builds
   the crypt under the title overlay. Connecting at boot (not at click) is what lets the room
   exist before anyone speaks, same as today's canvas behind `Title.tsx`.
2. Title click unlocks audio and sends `begin`. Opening narration is prose-only (no tools
   phase). Clock `speak`s each segment; Chrome commits lines as they start.
3. Player Enter → Table `say_as_player` + Clock `silence` + Net `freeText`.
4. Server `roll` → Clock `hold` for airtime → tray throws → transcript gains the roll at settle
   → `strike` published for attacks after `IMPACT_BEAT_MS` → World swings → impact at 0.22s
   → HP / death.
5. Server `diffs` → Clock `mark` → Table apply → World/Chrome/SFX react. Click-to-move is a
   player-authored diff path: when the clock is empty it applies immediately (100ms budget).
6. Server `narration` / `narrationEnd` → Clock → transcript / `awaiting_dm`.
7. Quit → Sidecar `shutdown` if and only if this Godot spawned the child.

---

## 9. Errors

Two classes. Do not handle them the same way.

**In-game (shortcut #13).** Failed tool, bad click, Venice error, engine exception. Log, toast,
`silence_now()`, `awaiting_dm = false`. No retry invented in Godot.

**Sidecar.** Health never comes up, child exits, port taken in spawn mode. One blocking message
(“the DM did not start” / “the table is already open”). No reconnect loop that pretends a
session exists. Attach mode never respawns a server you started in a terminal.

Voice 502/timeout is **neither**: it is a per-line fallback, not a toast and not a stalled queue.

Missing `VENICE_API_KEY` is the server's existing narration-disabled path, shown in the toast so
a friend who skipped `.env` is not looking at a mute crypt with no explanation.

---

## 10. Parity gate

Godot replaces Vite when all of the following are true on the real Java server, `--demo` dice,
authored crypt, Godot as the only human-facing client for that run:

1. Room renders: braziers, sarcophagus, door, fighter token.
2. Title click sends `begin`, audio unlocks, opening is spoken and appears in the overlay as it
   is spoken.
3. A typed inspect / heave: dice in the tray, then narration, then a visible world change
   (reveal or spawn).
4. Combat: camera pull-back, ceremony, legal-move clicks, a swing that waits for the die, HP
   drain on impact, goblin turn narrated as one beat.
5. Death: body stays, defeat overlay, restart lays the room out again.

Until that list is true, `client/` stays and remains the known-good table. After it, delete
`client/` in the same change that makes Godot the default. Do not leave two clients to drift.

The gate is played, not unit-tested. Latency numbers in `m0-evaluation.md` stay recorded, not
the grade. The grade is the same question M0 asked: does it feel like a DM is running the game.

---

## 11. Testing

Java tests do not move and do not grow a Godot suite.

Godot scripts worth writing, because they encode rules that failed in play when they drifted:

- Tumble: `landAt`, `revealAt`, `isDramatic` against the same timings as `tumble.ts`.
- Clock: `mark` / `hold` order; `silence()` still runs dropped `reveal`s; voice-off reveals
  without calling `speak`.

Everything else is the parity gate in §10. Do not build a Godot rules engine test harness.

---

## 12. macOS `.app`

Friends-only path. Daily development stays Godot editor + `./gradlew run` (attach).

One bundle:

- Godot executable and pack (the game) — this is the process friends launch.
- A bundled JRE and the server jar inside the `.app`. Godot spawns `java -jar` with
  `--port`. Do not use `jpackage` as the outer launcher; that would make Java the parent.
- Working directory for the child: `~/Library/Application Support/Emberdelve/`, so `Config`
  finds `.env` there. Keys never go in the `.app` and never go in git.

The `.app` always **spawns**. First launch: if `Hello.dm` is false, toast where to put `.env`.

Windows is the same two-process layout, not part of this deliverable. Steam is “put this bundle
in a depot,” not a requirement here. Layout should not paint the project into a Steam-incompatible corner
(no embedding the DM inside the Godot process, no binding `0.0.0.0`, no putting keys in the
client).

---

## 13. Tree

```
godot/                  # Godot 4 project (GDScript)
  project.godot
  autoload/
    sidecar.gd
    net.gd
    table.gd
    clock.gd
  world/                # crypt.tscn, tokens, kit imports
  chrome/               # overlay chat, dice, combat bar, title, defeat
  audio/                # voice.gd, buses, samples
  dice/                 # tumble.gd (port of tumble.ts)
client/                 # stays until §10; then deleted
server/                 # bind + --port only, otherwise unchanged
```

---

## 14. Deviations from earlier docs

- **`ai-dm-system-design.md` §2.** Frontend chrome is Godot Controls, not React. Renderer is
  Godot, not Three.js. Transport is still one WebSocket. Backend is still Java.
- **M1 spec §8b.** Three.js does not stay. The reasons recorded there still hold as *risks*
  (text chrome, physics temptation) and are answered in §7 Chrome and §3 (no RigidBody dice).
  The re-entry condition (authoring + a desktop ship) is what this spec is.
- **M0 “in-memory only / no localStorage.”** Unchanged. User-data `.env` is config, not game
  save. Still no save/load.
- **M0 “browser gesture unlocks audio.”** Becomes “title click unlocks Godot audio and sends
  `begin`.” Same ordering, different API.

---

## 15. Invariants (unchanged)

1. Server is authoritative. Client never computes a roll, hit, legal move, or death.
2. `List` of party members, every action has `actorId`.
3. Game state lives in Table, not in Control/World locals.
4. The 3D world is one scene, created once, not rebuilt by chrome redraws.
5. `RollResult.faces` is a list of integers, never collapsed before the tray.
6. Rolls are logged as events. No replay-by-reroll.
7. LLM-facing enums stay closed and validated server-side.
8. No `localStorage` / `sessionStorage`. No Godot `user://` save of the session.
9. Java stays modern Java. Godot stays GDScript. No Spring, no new Java frameworks for this.
10. No new tools, entity types, props, or rules in this work.
