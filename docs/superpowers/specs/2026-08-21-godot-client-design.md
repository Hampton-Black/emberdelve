# Godot desktop client

**Status:** Design approved, revised 2026-08-22 after review, pre-implementation
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
| Engine | Godot **4.5.x**, pinned. Not "current stable" — `AudioStreamMP3.load_from_buffer`, the TTS API and the export templates all moved inside 4.x |
| Client language | GDScript only. No C#, no GDExtension for this client |
| Process model | Two processes, **attach only**. Godot never spawns Java. `cd server && ./gradlew run` in a terminal; Godot connects to it. Spawning, a bundled JRE and PID ownership are deferred with distribution |
| First ship | A Godot client that passes the §10 parity gate on the developer's own machine. **Distribution is deferred** until a full prototype exists — see §12 |
| Server address | **One setting**, `EMBERDELVE_SERVER` (default `http://127.0.0.1:7070`). Every URL — `/ws`, `/tts`, `/health` — derives from it. Never a second constant anywhere. This is what keeps a hosted server a config change rather than a rewrite |
| Protocol | Same JSON WebSocket + `POST /tts` + `GET /health`. One additive field: `Hello.dm`. Godot is another speaker of `client/src/types.ts`, not a new protocol |
| Dual client | Vite/Three.js stays until Godot replays the M0 acceptance beats. Then `client/` is deleted in the same change that makes Godot the only client |
| Chrome | WoW-style overlay on the 3D view. One stream (prose + rolls). No fade while a line is being spoken |
| Dice | Predetermined faces from the server. 2D tray, not RigidBody. `tumble.ts` math ports as numbers |
| Clock source | `Time.get_ticks_msec()`, everywhere `performance.now()` appears today. Named once so three modules do not pick three |
| Pixel look | The 3D world renders into a `SubViewport` at 480px wide, nearest-neighbour. Chrome is composited **at native resolution on top**. Never project-wide stretch — that pixelates the type |
| Voice | `POST /tts` first; OS TTS (`DisplayServer.tts_*`) per line on failure. No Web Speech |
| Secrets | Stay in Java. Godot never reads `.env` or API keys |
| Bind | The server listens on `127.0.0.1` only |

---

## 3. Out of scope

| Deferred | Why |
|---|---|
| **All packaging** — the `.app`, a bundled JRE, spawning Java, PID ownership, code signing, notarization | Distribution is a question to answer against a working prototype, not in front of one. §12 |
| **Hosting the Java server** | Attractive — it deletes the sidecar and means a friend needs no `.env` at all. It is also not a client swap: `InMemoryGameRepository` holds exactly one game, `WsHandler` is one session per process, `engine.restart()` throws away *the* game, and the bind is unauthenticated. **Session scoping and auth are the multiplayer milestone**, which is where this belongs — hosting is that milestone's deployment story, not a packaging shortcut taken early. Kept open at the cost of one setting by the `EMBERDELVE_SERVER` rule in §2 |
| Steam, Steamworks, depots, overlay | Downstream of a distribution decision that has not been made |
| Windows / Linux exports | Same layout; not in this deliverable |
| Rewriting the DM in GDScript | Combat, dice, tools, LLM, TTS synthesis stay Java |
| New tools, props, rooms, rules | Invariant #10. This is a client swap |
| Physics-driven dice or grid | Would fight invariants #1 and #6 |
| Keeping a web client after parity | Dual-client is a bridge, not a product |
| Hybrid webview (Godot 3D + React chrome) | Two UI stacks, still not a normal app |
| Rewriting M1 procgen | Server still emits `SceneState`; Godot renders whatever arrives |
| **The M1 dungeon-navigation plan** | Paused, not cancelled. `docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` is written against "React 19 + Three.js on the client" and none of its Java is built yet. Building room-to-room navigation twice is the one cost this pivot can definitely avoid. Its client-side tasks are re-planned against Godot after §10 |

---

## 4. Architecture

```
┌──────────────────────── Godot (the app) ─────────────────────────┐
│  Link ───── where the server is. One setting; every URL derives   │
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
│  Java server (unchanged DM: engine, tools, LLM, ElevenLabs)       │
│  Started by hand: cd server && ./gradlew run                      │
│  Binds 127.0.0.1, honours --port (default 7070), one client only  │
└──────────────────────────────────────────────────────────────────┘
```

Game facts live in Table. World, Chrome, and SFX subscribe. Putting HP, mode, or legal moves in
a Control node's locals is the same bug invariant #3 exists to prevent.

Feel findings port as **numbers**, not as Three.js or React code. The authoritative list is
`AGENTS.md` (dice, impact, ceremony, token scale, audio layers). If a Godot scene disagrees with
those numbers, the numbers win until a played session replaces them.

---

## 5. Process model

Two processes. **Godot never starts Java.** The developer runs `cd server && ./gradlew run` in a
terminal, exactly as today, and Godot attaches to it.

This is a deliberate narrowing of the original design. Spawning a bundled JRE was written to serve
a `.app` that is now deferred (§3), and everything it dragged behind it — PID ownership, SIGTERM
then SIGKILL, port collision, an orphaned child holding 7070 after a crash — is work that buys
nothing until a distribution decision exists.

### Attach

On boot, `GET {EMBERDELVE_SERVER}/health`. If it returns `ok`, connect. If it does not, show
“start the DM with `cd server && ./gradlew run`” and keep retrying health every second — the
message clears itself when the server comes up, which is the loop a developer actually lives in.
Godot never kills a server it did not start, and it never starts one.

`EMBERDELVE_SERVER` (default `http://127.0.0.1:7070`) is the **only** address in the client. `/ws`
derives from it by swapping the scheme to `ws`/`wss`; `/tts` and `/health` are paths on it. There
is no second constant and no port arithmetic. This is what leaves §3's hosted-server fork open at
the cost of one setting.

### Dual-client rule — enforced, not documented

Do not connect Vite and Godot to one server at the same time. `WsHandler` is one session, one
connection: a second socket gets `hello` and `scene`, then later diffs go to whichever connection
sent the last action — a failure that looks like a Godot bug and is not.

The bridge period is the whole risk window, so this is a server guard rather than a sentence in a
document. A second concurrent connection is closed with a reason the client can toast.

### Bind and CORS

The server binds **`127.0.0.1` only**. It is a local DM, not a network service. CORS `anyHost`
may remain while Vite exists (the Vite origin is still another localhost port). CORS is removed
in the same change that deletes `client/`.

### Config and secrets

`.env` stays on the Java side, found by `Config` walking up from the working directory as it does
today. Godot never parses it and never holds a key. If `Hello.dm` is false, toast that the DM is
disabled and name `.env` — a mute crypt with no explanation reads as a crash.

### Voice transport

Speech stays **HTTP `POST /tts`**, body `{ speakerId, text }`, response `audio/mpeg`. A minute of
MP3 must not share the WebSocket with click-to-move. Godot plays the bytes. If the status is not
200, that line only falls back to OS TTS. Missing `ELEVENLABS_API_KEY` is an OS-voice session,
logged by the server as today, not a crash.

Two Godot facts that cost an hour each if they are discovered during the port rather than before
it. `DisplayServer.tts_*` does nothing until **Project Settings → Audio → General → Text to
Speech** is enabled; it is off by default. And its completion callback is **global**, not
per-utterance — `tts_set_utterance_callback` fires with a utterance id, so the
“resolves on end, error or cancel and never rejects” contract in `voice.ts` needs an id-to-signal
map underneath it rather than a per-call await.

---

## 6. Java changes (only these)

The DM does not grow tools, rooms, or rules. Java grows exactly four things:

1. Bind host `127.0.0.1`. Today `app.start(PORT)` binds every interface.
2. Honour `--port` (default 7070), and log the bound address at boot.
3. **Refuse a second concurrent WebSocket.** `WsHandler` is one session, one connection, and two
   attached clients silently route narration to whichever one acted last. Close the second with a
   reason; §5 explains why this is code rather than a document.
4. `Hello` gains `dm: boolean` (Venice is configured), parallel to the existing `voice`. While
   `client/` lives, add the same field to `client/src/types.ts` in the same commit so the two
   clients do not drift.

Dropped from the original list along with spawning: PID logging for a child that no longer
exists, and the overlapping-listen hard fail, which was there to diagnose two `.app`s colliding.

`GET /health` already returns `ok`. `POST /tts` and `/ws` stay. `begin`, `restart`, debug
messages, and demo dice stay. Records and Jackson discriminators stay. The Godot client mirrors
them in GDScript; there is still no IDL until M1's existing plan for one.

**One additive field:** item 4 above. Godot uses `Hello.dm` for the missing-key toast. That is
the same envelope the browser already needed for `voice`.

---

## 7. Godot modules

New tree: `godot/` at the repo root. Autoloads hold facts and I/O; scenes hold the table.

### Link (was Sidecar)

**Interface:** `base_url() -> String`; `ws_url() -> String`; `await_health() -> bool`.

The one place that knows where the server is. Reads `EMBERDELVE_SERVER`, defaults to
`http://127.0.0.1:7070`, derives every other URL from it. Starts nothing and kills nothing.

Renamed because it no longer owns a process. A module called Sidecar that never spawns a sidecar
is a name that will send someone looking for the spawn code.

### Net

**Interface:** `connect(url)`; `send(message)`; one signal per `ServerMessage.type`.

JSON in, JSON out. `WebSocketPeer` polled from `_process` — it has no signals of its own.
Reconnects on drop, because the server is a terminal process that gets restarted a dozen times an
evening. Does not apply diffs, speak, or draw.

**Read nulls as nulls.** `RollRequest.targetId`, `dc` and `skill` are `Optional` server-side and
Jackson writes them as JSON `null`, not absent. `if request.dc:` is false for a DC of 0 and for
null alike — GDScript's falsiness is the same trap the TypeScript mirror warns about one layer
up. Compare `!= null`, always.

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

**Address every creature by `actorId` from the wire.** Never a literal `"fighter"`, never
"the first entity", never "the player-controlled one" where a list would do. Invariant #2 exists
because the party becomes a list of real people at the multiplayer milestone, and a client that
has learned there is one of them is the expensive kind of wrong.

**One signal per batch, not per diff.** `store.ts` is written in Zustand's immutable style — a
whole new state object per `applyDiffs`, so subscribers see one consistent picture. Godot's idiom
is mutate-and-emit, and a signal per field would have World rebuilding halfway through a batch
that has moved a token but not yet changed its hit points. Apply the whole `Diffs` message, then
emit once. This is `CombatChanged` replaces-never-merges, applied to the transport.

`Time.get_ticks_msec()` is the clock for `opened_at`, `started_at`, `dice_dismiss_at` and
`strike.at`. One source, named here, because three modules picking three is how the dice and the
swing drift apart.

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

**The one hazard in this port.** `drain()` in `narration.ts` ends in a `finally` that re-enters
itself if anything is still queued, and the comment above it records the bug it fixed: a
`silence()` landing during an `await` leaves the *next* turn's lines stacked behind a loop that
has already given up on them, and they are never spoken at all. GDScript has no `try`/`finally`,
so that re-entry has to be written explicitly at every exit from the loop. A naive port loses the
first turn after every interruption, silently. One of §11's two tests exists for this.

Sleep is `await get_tree().create_timer(seconds, true, false, true).timeout` — the last argument
ignores time scale, so a paused or slowed tree cannot stall the dice gate.

### World

One crypt scene, authored in the Godot editor. `SceneState` is a **kit instance list**, not a
baked unique room: floor/wall types, prop ids and poses, entity kinds and grid positions,
`LightingPreset`. The editor authors lights, meshes, and animation; Table decides which instances
are present and which light group is on.

Constraints that survive the port:

- Internal render width 480, nearest-neighbour upscale (pixel read).
- Four isometric corners, 90° snap (Q/E). No free orbit. Orthographic `Camera3D`, elevation
  `atan(1/√2)` = 35.264° below horizontal, 45° around Y — the numbers in `Renderer.ts:379`.
- Exploration vs combat framing as in `Renderer.ts` (`EXPLORATION_HALF_HEIGHT` 5.4,
  `COMBAT_MAX_HALF_HEIGHT` 10, `FOLLOW_SECONDS` 0.34, `FRAMING_SECONDS` 1.1). Camera facing is
  view state, not Table.
- Kenney kits: **one folder per kit** (colormap paths). Graveyard vs mini do not share a
  directory.
- Tokens: clips `idle`, `walk`, `attack-melee-right`, `die`. Oversized vs the 1.0 square.
  Faint self-lit so they read in a dark crypt. `IMPACT_SECONDS = 0.22` gates HP drain and the
  death clip. Godot's glTF import gives every instance its own `AnimationPlayer`, so the
  `SkeletonUtils.clone` problem in `tokens.ts` simply does not exist here.
- No physics on the grid. Movement is a `Tween` along a slide the server already approved.
- Health bar colour is allegiance, not remaining HP. Length carries hit points.

Three things §7 originally treated as free and are not:

**The pixel pipeline is three decisions, not one.** `RenderPixelatedPass` is doing edge detection
as well as downsampling — `normalEdgeStrength` 0.5, `depthEdgeStrength` 0.25 (`Renderer.ts:358`).
A bare `SubViewport` gives the resolution and none of the outline, and the room will read flatter
without anyone knowing why; that edge pass has to be written as a screen shader over the
viewport's depth and normal buffers. The chrome must composite **on top of** the viewport at
native resolution, never through it. And `snapFocus` (`Renderer.ts:406`) quantises the camera to
whole low-resolution pixels — without it every camera follow resamples the entire frame and the
art shimmers. It is load-bearing, not polish.

**The lighting numbers do not port, and that is the one exception to §4.** `FLAME_INTENSITY` is
26 *candela* under Three.js physical decay; Godot's `OmniLight3D` is energy, range and an
attenuation curve, and there is no conversion worth trusting. These get re-tuned by eye. That is
also the single most dangerous hour in this project — `AGENTS.md`: *"do not tune lighting and
post-processing for more than one evening… it will consume as much as you give it."* **Timebox
it to one evening and stop.** The room is lit by two braziers and is genuinely dark away from
them; that darkness is correct and is not to be fixed by raising the ambient or by adding a
directional light.

**`props.ts` and `tokens.ts` are ~900 lines and are the bulk of this work.** Half the props are
primitives composed in code with proportions tuned by eye (`TOMB_HEIGHT` 0.58, the tiered and
tapered sarcophagus with a void under its lid, the two-colour flame); the rest load Quaternius
models behind the `MESH_PROPS` table. Every `PropType`, `FloorType` and `WallType` needs
remapping. Keep the seam: a Godot `Resource` mapping `PropType` to a scene path, so promoting or
demoting a prop stays one table entry and stays diffable — not editor-only wiring.

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
unlocks audio and sends `begin`.

**Restart returns to the title.** Defeat offers restart; Net sends `restart` and stays up. When
the fresh `scene` arrives, Clock is silenced, Table resets *all the way to* `started = false`,
and the title overlay comes back.

That last part is not cosmetic and the original draft dropped it. `WsHandler` calls `dm.reset()`
on restart, which clears the once-per-session guard on `openScene` — the server is *waiting* to
be asked to narrate again, and only `begin` asks it. The browser gets this for free by reloading
the page into the title screen. A Godot client that keeps `started` true after a restart lays out
a fresh room and then sits in silence forever. Routing restart back through the title is one code
path instead of two; the audio is already unlocked, so the click is only ceremony.

Dice tray is 2D, driven by a port of `client/src/dice/tumble.ts` (`WIND_UP_MS`, `landAt`,
`revealAt`, `HOLD_MS` 9000, `COMBAT_HOLD_MS` 3000, `FADE_OUT_MS`). Tumbling faces are noise;
settled faces are `result.faces[i]`. Combat dismisses the tray on the swing, not on narration.

**On 3D physics dice, which keep being suggested.** `ai-dm-system-design.md` §7 originally
specified one (`@3d-dice/dice-box-threejs`, chosen for supporting predetermined outcomes); M0
shipped the 2D tray instead and measured a whole timing chain against it, and where the two
disagree M0 wins. Godot has equivalent addons and they do support forced landing values, so
**server authority is not the objection** — it is preservable either way.

The objection is the clock. `revealAt` is *when the total becomes legible*, and it is the number
`Clock` holds narration for and the number `IMPACT_BEAT_MS` (650) was measured against —
1060ms to land, 1460ms legible, 1720ms readout finished, 2120ms swing, 2340ms impact, with the
result standing alone for about 400ms. A physics die settles when it settles. Driving the gate
off the settle would recouple the queue to the tray, which is exactly what `AGENTS.md` records
removing: *"Timer-based, not driven by the tray component, so the gate still opens if the tray
never mounts."* And if the face is being forced anyway, the simulation is decoration bought at
the price of a nondeterministic settle time, a physics dependency pinned to a Godot minor
version, and every measured number above.

There is also nowhere good to draw it: inside the 480px viewport a d20 is a smudge for the same
reason a to-scale human is, and outside it is a third render path.

`tumble.gd` is pure and unit-tested, so this stays cheap to revisit — the tray is a presentation
swap behind `sample()`. **Re-entry condition:** after the parity gate, if the tray reads as flat
beside the 3D world in a played session. Not before, and not on the strength of a screenshot.

### Voice and SFX

`VoiceBackend` seam: `speak(line)` resolves on end, error, or cancel and never rejects; `stop()`;
optional `prime(line)`. Adapters: HTTP MP3 from `/tts`, OS TTS. Casting table stays closed
(narrator / goblin / fighter) and degrades to pitch/rate when the OS has no named voice.

SFX move onto Godot buses. Layer counts and delays stay those in `AGENTS.md` (move 4, reveal 2,
lid 5, combat begin 6, miss 4, hit 6, kill 9). `rate` still does real work — `boom` and `thud`
are only ever played far below speed. Kenney fighter voiceover pack stays unused.

**Audio is the one subsystem that gets worse in this move, and it was listed as the reason to
make it.** Buses buy mixing, effects and an editor; they do not buy scheduling. Every layer in
`sfx.ts` is scheduled at `ctx.currentTime + delay` — sample-accurate — and `AGENTS.md` is
explicit that *"a sting is layers a tenth of a second apart and timers smear them together."*
Godot has no "start this stream at time T" API; the best available is a frame timer, at ±8ms on
a 60Hz tree. That is far tighter than the `setTimeout` smear the note was written against, and it
is not free. **Make the nine-layer killing blow an explicit gate item and listen to it.** If it
reads as a clang rather than an event, the fallback is to bake each sting to one mixed sample
offline.

**`drop()` has to be baked to a file.** The falling sine under every impact — *"without it the
combat sting is a loud clang rather than an event"* — is a Web Audio oscillator with an envelope,
synthesised at runtime. Godot has no runtime synthesis. Render it once, offline, at each rate it
is used at, and ship the wavs.

**SFX are 2D.** They are stings on a clock, not sources in a room; `AudioStreamPlayer`, never
`AudioStreamPlayer3D`. Positional audio would add distance attenuation and panning that nothing
in the tuning accounts for, and would smear the layers it is trying to place.

---

## 8. Data flow

1. Boot → Link `await_health` → Net connect → `hello` + `scene` into Table → World builds
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
7. Restart → Net `restart` → fresh `scene` → Clock silenced, Table reset to `started = false`,
   title overlay returns. The next click sends `begin` and the room narrates itself again.
8. Quit → close the socket. Godot started nothing and stops nothing.

---

## 9. Errors

Two classes. Do not handle them the same way.

**In-game (shortcut #13).** Failed tool, bad click, Venice error, engine exception. Log, toast,
`silence_now()`, `awaiting_dm = false`. No retry invented in Godot.

**Connection.** Health never comes up, or the socket drops. A non-blocking banner — “start the
DM with `cd server && ./gradlew run`” — that clears itself when health returns. This is a
developer's loop, not a dead end: the server gets restarted a dozen times an evening and the
client should simply reattach. Godot never starts a server and never kills one.

A second client attached to the same server is closed by the guard in §6 and toasted as such,
because otherwise it presents as narration going to the wrong window.

Voice 502/timeout is **neither**: it is a per-line fallback, not a toast and not a stalled queue.

Missing `VENICE_API_KEY` is the server's existing narration-disabled path (`Hello.dm == false`),
shown in the toast so nobody is looking at a mute crypt with no explanation. The whole game
short of narration still runs on that path — which is exactly what the debug bar is for.

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
5. Death: body stays, defeat overlay, restart returns to the title, and the click after it
   narrates a fresh room.
6. **A generated room.** `./gradlew run --args='--generate 7'` boots and renders its `floorType`,
   `wallType`, lighting preset and prop variants.
7. **The killing blow sounds like an event**, not a clang. Nine layers, and the sting is what
   the ear is judging.

Item 6 was not in the first draft and its absence was a trap. `client/` has already grown the M1
render work — floor types, wall types, prop variants, camera follow — so a Godot client that
passes items 1–5 against the authored crypt and is then made the default would silently delete
rendering the project already has. Parity means parity with what `client/` does *now*, not with
what M0 shipped.

Until that list is true, `client/` stays and remains the known-good table. After it, delete
`client/` in the same change that makes Godot the default. Do not leave two clients to drift.

The gate is played, not unit-tested. Latency numbers in `m0-evaluation.md` stay recorded, not
the grade. The grade is the same question M0 asked: does it feel like a DM is running the game.

---

## 11. Testing

Java tests do not move and do not grow a Godot suite.

**Runner: GUT** (`godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit`).
Godot ships no test framework, and "worth writing" needs somewhere to write them. GUT is chosen
over gdUnit4 for being the smaller of the two; either is fine, but the spec picks one so the plan
does not have to.

Godot scripts worth writing, because they encode rules that failed in play when they drifted:

- Tumble: `land_at`, `reveal_at`, `is_dramatic` against the same timings as `tumble.ts`.
- Clock: `mark` / `hold` order; `silence()` still runs dropped `reveal`s; voice-off reveals
  without calling `speak`; **and the re-entry case** — a `silence()` during an in-flight line
  must not strand the lines queued behind it. That last one is the `finally` in `drain()`, which
  GDScript cannot express directly (§7 Clock).

Everything else is the parity gate in §10. Do not build a Godot rules engine test harness.

---

## 12. Distribution — deferred, with the corners kept open

**Nothing ships in this spec.** The deliverable is a Godot client that passes §10 on the
developer's own machine, run from the editor against `./gradlew run`. Distribution is a question
best answered in front of a working prototype rather than instead of one.

What the first draft assumed, and why it is not here: a macOS `.app` bundling a JRE, spawning
`java -jar`, owning the child's PID. That path has a cost the draft did not price. An unsigned
bundle that arrives by zip or AirDrop is quarantined and, on Apple Silicon, refuses to launch at
all — so "a friend double-clicks it" needs either ad-hoc signing plus an `xattr` incantation, or
a paid Developer ID and notarization. It also needs the friend to have a `VENICE_API_KEY`, which
means §1's success criterion could not have been met as written in any case: no key, no DM, mute
crypt.

Two live options, decided later:

- **Bundle it.** Godot as the parent process, JRE and jar inside the `.app`, keys in
  `~/Library/Application Support/Emberdelve/.env`. Cost: signing, notarization, and a friend who
  has to obtain API keys.
- **Host the DM.** The client becomes a small download that talks to a URL and needs no keys at
  all. Cost: session scoping, auth and metered spend — which is the **multiplayer milestone's**
  work, and where this belongs rather than being pulled forward as a packaging trick.

Keeping both open costs exactly one thing, and §2 already spends it: **the server address is one
setting.** Beyond that, do not paint into a corner — no embedding the DM inside the Godot
process, no binding `0.0.0.0` today, no keys in the client, and nothing in the client that
assumes a single party member (invariant #2).

---

## 13. Tree

```
godot/                  # Godot 4.5.x project (GDScript)
  project.godot
  autoload/
    link.gd             # where the server is. One setting, every URL derived
    net.gd              # WebSocketPeer, polled; one signal per ServerMessage
    table.gd            # the store. One signal per diff batch
    clock.gd            # the presentation queue. The load-bearing port
  world/                # crypt.tscn, camera, pixel SubViewport + edge shader
    props/              # one .tscn per PropType, behind prop_table.tres
    tokens/             # kit imports, one folder per kit (colormaps)
  chrome/               # overlay chat, dice tray, combat bar, title, defeat, debug bar
  audio/                # voice.gd, sfx.gd, buses, samples (incl. baked drop wavs)
  dice/                 # tumble.gd (port of tumble.ts)
  test/                 # GUT: tumble, clock
client/                 # stays until §10; then deleted
server/                 # §6's four changes only, otherwise unchanged
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
- **M0 “browser gesture unlocks audio.”** Becomes “title click sends `begin`.” Godot has no audio
  gate, so the click is no longer load-bearing for sound — but it stays the trigger, because the
  server's once-per-session opening guard is asked, never pushed, and restart depends on it.
- **This spec's own first draft.** Spawning, the bundled JRE, the `.app` and notarization are
  deferred to §12; `Sidecar` becomes `Link`; the parity gate grows a generated room and a
  listening test; restart routes back through the title. Revised 2026-08-22.

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
