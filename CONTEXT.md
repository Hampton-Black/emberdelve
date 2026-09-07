# Emberdelve — Domain Glossary

The words this project uses, and what they mean. This file is the **vocabulary**; `AGENTS.md` is
the **rules**. Where they overlap, `AGENTS.md` wins.

Use these terms in issue titles, test names, commit messages and prose. Several of the "not this"
entries are listed because the drift caused a real bug.

## The spine

**Event** — the append-only record of something that happened (`dm.model.Event`, a sealed
interface). Every state change is an event. If a change didn't emit one, it didn't happen.

**Event log** — the durable sequence of events for a session (`EventLog`), written as JSONL to
`server/sessions/`. Refused, never upgraded, when its `SCHEMA_VERSION` is older than the build's.

**World state** — the current picture, and *only* ever a fold over the event log
(`dm.state.WorldState`). There is no second write path: no setter, no `put`.
_Not: "the model", "game state object"._

**Session** — one playthrough, from `SessionStarted` to the end of the log. A session file that's
worth keeping is copied into `docs/evidence/` and replays on every build.

**Replay** — re-running a session file through the engine with no network and no key, expecting
an identical event stream. This is the test harness, not a save/load feature.
_Not: "resume". Nothing resumes from a session._

**Fixture** — a session file kept in `server/src/test/resources/sessions/` that the suite
replays. Regenerated with `./gradlew recordFixture` after a schema bump.

## The turn

**Mechanics pass** — phase 1 of a turn, on `DM_MODEL_TOOLS`. Decides tool calls; any prose it
writes is discarded. This is the phase the <800ms budget applies to.

**Reconcile pass** — the second tool pass, where the narrator's already-written prose is matched
back to world changes. No dice here; the outcome has been narrated already.

**Narration** — the prose the player reads and hears, written by `DM_MODEL_PROSE` on the text
channel with `[[speaker]]` markers. Never a tool call.

**Tool call** — a validated, closed-enum request from the model to the engine. The seven are
listed in `AGENTS.md`. A dropped one is the project's characteristic **silent failure**: the
narration describes something the world never received.

**Fact** — a free-form assertion from the narrator (`assert_fact`, `dm.model.Fact`). The one
place model text enters the prompt. It makes a round trip into the next prompt and reaches no
roll, no legal move and no renderer.

**Diff** — what the server sends the client after a turn (`dm.model.Diff`, sealed:
`EntityAdded`, `EntityRemoved`, `EntityMoved`, `StatChanged`, `PropRevealed`, `ModeChanged`,
`CombatChanged`). Scoped to the room the party is standing in — a diff never names a room.
The client applies diffs; it never derives them.

**Scene state** — the client's whole current picture of the world (`SceneState`): a `RoomView`
per room it may draw, plus the entities, blocked squares, mode and combat of the room the party
is standing in. *Not: the picture of one room* — it stopped being that when visited rooms stayed
on the board.

**Room view** — one room as far as the client is allowed to draw it (`RoomView`), current or not:
size, floor and wall type, lighting, exits, visible props, its origin in the world frame, and
whether it has been visited. One shape for every room, because the shared-wall owner is decided
by distance from the entrance rather than by where the party stands. Entities never appear in
one.

## The world

**Room** — one place, authored (`RoomDefinition`) or generated (`GeneratedRoom`). Terrain
belongs to the room, not to combat — `isObstructed` holds whether or not anyone has rolled
initiative.

**Exit** — a way out of a room (`Exit`, with a `Direction`). Taken via the `use_exit` tool,
which is mechanics-only and withheld in combat.

**Traversal** — leaving a room and coming back to find it as you left it. The M3 gate.
_Not: "navigation", which was the M1 plan's word for a different, generator-shaped problem._

**Prop** — a thing in a room (`Prop`, with an `id`, a closed `PropType`, and `hidden`).
Revealed with `reveal_prop`.

**Dressing** — generated, non-interactive detail placed in a room (`Dressing`, `RoomDresser`).
Distinct from a prop: a prop can be addressed by a tool call, a dressing cannot.

**Entity** — anything alive or once-alive on the board (`Entity`). Death leaves a body: the
entity stays at 0 hp and the token clamps on its `die` clip. Dead is what the server says now,
never what it once said.

**Party member** — a player character (`PartyMember`). Always a `List<PartyMember>`, even though
there is exactly one. Every action carries an `actorId`.
_Not: "the player" as a singleton in code — the word is fine in prose._

**Square** — one grid cell (`Square`). Distance is **Chebyshev everywhere**: a diagonal costs
one. Two metrics in one combat system is how "why can it hit me from there" starts.

**World frame** — the single coordinate space every room is placed in, anchored at the entrance
(`Rooms.origins()`, `RoomOrigin`). The entrance is at `(0, 0)` and never moves; a room's origin is
composed breadth-first from `RoomOutline.beside` offsets, server-side, so there is one
implementation of the arithmetic and it is testable without a renderer.
_Not: "the offset", which was the one-hop, party-relative thing this replaced._

## The table

**Table** — the client-side owner of game state (`godot/autoload/table.gd`). Chrome and World
both subscribe to it. Game state never lives in a `Control` or a `Node3D`.

**Chrome** — the 2D UI layer: transcript, roll log, hit points.

**World** — the 3D scene. One scene, created once.

**Token** — a KayKit figure on a base representing an entity (`godot/world/tokens/token.gd`).

**Render level** — how much of a room is drawn (`Room.Level`): `LIT` for the room the party is in,
`DIM` for one they have visited and left, `BLACK` for one only ever glimpsed through a doorway.
Chosen by a single policy function, `Room.level_for`. Only a `LIT` room contributes lights, which
is what keeps `MAX_TORCH_LIGHTS` a per-room budget however far the dungeon runs.

**Clock** — the ordered narration queue (`godot/autoload/clock.gd`). One line at a time, in
arrival order. `Clock.hold` is what gates the transcript behind a dramatic roll.

**Dramatic roll** — a roll that animates, decided by `isDramatic()` and keyed off *purpose*, not
off who rolled. Attacks, saves and skill checks throw; damage and initiative go to the log.

**Roll result** — `RollResult`, whose `faces` is a `List<Integer>` and stays that way. Crits key
off the natural d20, not the sum.

**Beat** — a deliberate pause carrying meaning: the server's `BEAT_MS` between "it reaches you"
and "it hits you", `IMPACT_BEAT_MS` before the swing, `IMPACT_SECONDS` before what the swing
causes. A beat is the DM's timing, not the renderer's.

**Stinger** — a layered sound event: a transient over a weight. Scheduled on the audio clock,
never with a timer.

## Process words

**Gate** — the question a milestone has to answer in played sessions, recorded in
`docs/milestones/m*-evaluation.md`. A gate is passed or not; it is never partially passed.

**Invariant** — one of the ten numbered rules in `AGENTS.md`. Each is expensive to unwind. They
are not style preferences.

**Shortcut** — something deliberately hardcoded, listed in `AGENTS.md` with the gate that retired
it or the reason it's still in force. A shortcut is not technical debt; it's scope.

**Anti-goal** — a thing not to build yet, with the milestone that changes the answer.
