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

**Directive** — a one-clause instruction the engine leaves for the narrator's next prose call: an
arrival, a sign, a torch relit. Directives wait in the order they were left, and the next narration
spends all of them — except that one left while a fight is already running is dropped. One left by
the action that started the fight is not. Each is about **a room** or about **the party**. A
crossing discards the ones about the room being left, because they have stopped being true, and
keeps the ones about the party, because a sign that is lost is a clock that went quiet.
_Not: a **fact**, which the narrator asserts and the projection keeps for good. A directive is the
engine's, and it is spent once._

**Projection** — the engine-authored markdown block of current state that goes into every DM
prompt (`DmService.worldState`). It **never truncates**, which is its whole point against the
six-turn transcript window, so anything added to it is in every prompt for the rest of the
session. Blocks are labelled and obviously not prose, because the model imitates the shape of
what it is sent.
_Not: "the context", which is the projection plus the transcript window._

**Band** — a described state where a number would be read aloud: `barely marked` / `bloodied` /
`badly hurt` / `barely standing` (`BeatRenderer.condition`). One vocabulary, used by both the
combat beats and the projection. The rule that decides band-or-number: **a count that drives a
tool decision stays a count; a count that only describes state becomes a band.**

**Diff** — what the server sends the client after a turn (`dm.model.Diff`, sealed:
`EntityAdded`, `EntityRemoved`, `EntityMoved`, `StatChanged`, `PropRevealed`, `ModeChanged`,
`CombatChanged`). Scoped to the room the party is standing in — a diff never names a room.
The client applies diffs; it never derives them.

**Scene state** — the client's whole current picture of the world (`SceneState`): a `RoomView`
per room it may draw, plus the entities, blocked squares, mode and combat of the room the party
is standing in. *Not: the picture of one room* — it stopped being that when visited rooms stayed
on the board.

**Room view** — one room as far as the client is allowed to draw it (`RoomView`), current or not:
size, floor and wall type, its *current* lighting, exits, visible props, its covered walls, its
origin in the world frame, and whether it has been visited. One shape for every room, because the
shared-wall owner is decided by distance from the entrance rather than by where the party stands.
Entities never appear in one.

**Covered wall** — a perimeter segment a room must not draw, because a room nearer the entrance
already draws that plane (`Rooms.coveredWalls`, shipped as `RoomView.coveredWalls`). Two rooms
joined by a door share the wall it is in, and drawing both is two walls fighting for one depth.
Named per `WallSegment` — a square and one of its sides — never per wall, so a room keeps the
part of its perimeter that overhangs the shared run.

## The world

**Room** — one place, authored (`RoomDefinition`) or generated (`GeneratedRoom`). Terrain
belongs to the room, not to combat — `isObstructed` holds whether or not anyone has rolled
initiative.

**Exit** — a way out of a room (`Exit`, with a `Direction`). Taken via the `use_exit` tool,
which is mechanics-only and withheld in combat.

**Lighting preset** — what a room's own fires are doing (`LightingPreset`: `TORCHLIT`,
`BRAZIERLIT`, `DIM`, `DARK`). A fact about the place, not about where the party is standing.
`BRAZIERLIT` is `TORCHLIT`'s air with no wall torches: the room is lit only by fires that stand on
its floor as props. The crypt is the one room that has it. **Folded state, not
authored content**: `RoomDefinition.lighting` is only the initial value, and an ALERT clock
filling moves the party's room one step in the direction that room's `fires.to` names. One-way — a
room has exactly two light states, ever, and a torch relights nothing, because a torch is the
party's and the fires are the room's.
_Not: the **render level**, which is a different axis and is about visibility rather than fire._

**Carried torch** — the party's own light, burning in a fighter's hand. The `torch` consumable is
both the spare and the refuel: spending one resets the LIGHT clock, which is how far the torch
currently in hand has burned down. One is already lit when a delve starts. It is never a click
target — it is spent from the exploration bar, because a thing at hand height in front of the
camera would shadow the things that do have something behind them.
_Not: a **wall torch**, which is the room's own fixture and belongs to its lighting preset. A torch
is the party's and the fires are the room's, which is why a torch relights nothing._

**Party light** — how much light the party is casting (`PartyLight`: `FULL`, `LOW`, `GUTTERING`,
`FAILING`, `OUT`). Folded from the LIGHT clock and never stored beside it, so there is one thing to
keep correct. The five names are that clock's own **sign** names, so what the DM narrates and what
the player sees are the same five words. The level is read from the clock's current segment; a sign
fires only on crossing upward, which is why spending a torch restores the light silently.
On the board it is a pool around the party that shrinks and reddens as the level falls. The dark
past its edge hides but never blocks: stone keeps its outline, a hostile shows only where light
reaches it, and every square is as reachable as it was.
_Not: the **lighting preset** or the **render level**. Those are both about the room; this is the
only one of the three the party carries with them._

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

**Clock** — a counter that ticks on a defined unit and fires a table when it fills
(`dm.model.Clock`, design doc §9). One primitive at three scales — `DELVE`, `WATCH`, `FRONT` — of
which only `DELVE` is in scope. It speaks twice: a threshold fires a **sign**, the fill fires the
consequence. Diegetic and unlabelled — never a segment counter on screen. Clocks cannot kill.
_Not: the **narration queue**, which the client's code calls `Clock`. Unrelated._

**Consequence** — what a clock's table holds (`ConsequenceId`). A named, pre-validated bundle of
events that already have an `Event` + `Diff` pair, never an effect language: **a consequence may
only do what a tool can already do.** Drawn where a table has several entries, fixed where it has
one — a one-entry table is a fixed consequence and is allowed to be.

**Sign** — a **consequence whose bundle is empty**: what a clock says at a threshold, before it
fills. Engine-fired, table-driven, narrated by the DM. A clock that speaks only at full is a jump
scare; one that speaks every tick is a counter with extra steps.
_Not: a separate kind of thing from a consequence. One enum, one firing path; the bundle is the
only difference._

**Disposition** — how a creature arrives (`Disposition`). `HOSTILE` starts a fight; `WARY` puts
the creature on the board and leaves the meeting open. A consequence **brings something in; it
does not decide how the meeting goes.** The delve-scale ancestor of §9's reaction roll.

**Doubt window** — the run of hit points where surviving the next room is genuinely uncertain
(40–70%). §9's gate is this window containing 5: *"stand in front of a door at 5 hit points and
genuinely not know whether to open it."* Its two owners are independent, and conflating them is
what makes attrition untunable: **the encounter decides where the window sits; max hit points
decide how much bar there is above it.**
_Not: a **band**, which is wound vocabulary the player hears. A doubt window is never spoken and
never rendered — it is a property of the numbers, measured in `docs/evidence/m4-attrition-sim.py`._

**Attrition** — the spending that gives the turn loop something to be about. The engine is the
only thing that can impose it, because **the model always says yes**. Session-scoped, never
room-scoped: hit points that reset at a threshold are not a cost.

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
_Not: the **lighting preset**, which is what the room's own fires are doing. The name collision on
`DIM` is real and the axes are independent — a `LIT` room can be `DARK`._

**Narration queue** — the ordered queue that speaks one line at a time, in arrival order
(`godot/autoload/clock.gd`, so the code calls it `Clock`). `Clock.hold` is what gates the
transcript behind a dramatic roll.

_Not: "the clock", which is the delve-side counter below. The two are unrelated; the name
collision is in the code, not in the domain._

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
