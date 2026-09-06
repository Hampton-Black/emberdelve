# M3 — Traversal: a room you can leave and come back to

**Gate question: is a room a place you can leave and come back to?**

Approved 2026-09-05. Successor to `docs/superpowers/specs/2026-08-23-m2-spine-design.md`, which
this builds directly on and does not amend.

M1's own question — *can the world be made rather than authored* — stays open, and stays with the
generator plan that follows this one. Numbering M3 ahead of M1's second half is out of order and
deliberate; the project did it once already when M2 took the spine before the rest of M1, and the
reason is the same both times. See §2.

---

## 1. What M3 is for

M2 proved a fault found in play can be turned into a test. It proved it in one room, because one
room is all there has ever been: `GameEngine` holds `private final RoomDefinition room`, handed in
at construction, and `WorldState.roomId` is a field that nothing has ever assigned.

The question this milestone answers is narrower than "navigation" and more useful:

> Leave a room. Do things elsewhere for long enough that the transcript window has forgotten the
> first room entirely. Come back. Is it as you left it, and does the DM know that?

That is a persistence question wearing a movement costume. Walking through a door is the easy
half and it is not what will break. What will break is the world quietly not being there any
more — a goblin that followed you, a prop that un-revealed itself, a narrator that describes the
crypt as though it had never seen it.

---

## 2. Why traversal before the generator

The old plan, `docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md`, did both halves at
once: seeded `DungeonLayout`, exits cut into generated walls, per-room dressing, and traversal, in
fourteen tasks. It is stale in both halves — its client tasks are React and Three.js, and its Java
tasks are built around a `GameRepository` deleted at the M2 gate.

Splitting it puts the uncertain work first. The generator half is pure functions over `GenRandom`
with no model and no state in the path; `LayoutGeneratorTest` and `ExitPlacerTest` are already
written in full in the old plan and lift almost verbatim. That is the kind of code this repository
lands reliably.

The traversal half is where every invariant comes under pressure at once: the fold gains a
dimension, the log gains an event that changes what every later event means, the DM's context
changes underneath a six-turn window, and the renderer stops being able to assume the world is one
rectangle centred on the origin. None of that is provable by looking at a hundred seeds in a
terminal.

Doing traversal first also means the generator plan lands on machinery that already works, rather
than inventing traversal and generation together and finding out which one broke.

---

## 3. Scope

### In

- Room scope in `WorldState` — entities, revealed props, visited rooms, dressings.
- Two authored rooms: the crypt, and one room behind its north door.
- `Direction` and `Exit` as model types; `RoomDefinition` carrying exits.
- Crossing a threshold: the `PartyMoved` event, the `enterExit` client verb, and the `use_exit`
  tool.
- `move_entity`, which is not navigation and is being fixed here because it is the same seam.
- The DM's context across a threshold: ways out, the threshold marker, the visited flag, the
  arrival directive.
- The dark-neighbour render, and `grid_to_world` becoming room-aware.
- A played evaluation and a multi-room replay fixture.

### Out — and why

- **`LayoutGenerator` and generated multi-room dungeons.** §2. The next plan.
- **World-space packing.** Rendering a neighbour needs that neighbour's world rectangle. With two
  authored rooms that is one hand-placed offset. Packing a whole dungeon without overlaps is a
  real problem and it belongs with the generator that creates it.
- **Party splits.** §6b. The event carries a list of movers so that this stays a policy rather
  than a schema change, but the policy in M3 is that the list is everyone.
- **Fleeing.** Exits are not legal in combat. Walking out of a fight would strand an initiative
  order in a room nobody is standing in, and designing retreat is a rules milestone.
- **Prefetch.** Inherited unchanged from M1 §3 — throughout this document "M1" means
  `docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md`. The dress pass is
  covered by arrival narration, and a threshold that visibly hangs is the signal to revisit.
- **Postgres, resume, snapshots.** Still absent, still deliberate. M2 §3.

---

## 4. Room scope in the fold

`WorldState` gains room scope the way `Fact` already has it — every keyed thing carries its room,
and the accessor filters. `factsHere()` is the template, it shipped at the M2 gate, and it
survived a thirty-turn session.

The alternative shapes were considered and rejected. A per-room sub-record (`Map<String,
RoomState>`) makes `find(id)` a two-level search, and every global lookup in the codebase —
`App.kindForVoice`, `spawnGoblin`'s already-exists refusal, `state().find(actorId)` in the engine
and the dispatcher — would have to learn which room to look in first. Qualifying ids at generation
time (`room-3/pillar-0`) puts a longer string in the model's mouth, and prop ids are printed into
the prompt and handed to `reveal_prop`; m2-evaluation §8 already flags coordinates leaking into
`## Established` as a real risk, and this would be more of the same.

Concretely:

- `Entity` gains `roomId`. A room change is `entity.movedToRoom(...)` — one value, nothing to
  desync. `EntityView` does not carry it; `scene()` only ever sends the current room's occupants.
- `entitiesHere()` joins `factsHere()`. `find(id)` stays global.
- `revealedPropIds` becomes a set of `(roomId, propId)`. See §4a.
- `visitedRoomIds`, a `Set<String>` folded from `PartyMoved`, feeding §7b.
- `dressings`, a `Map<String, Dressing>` folded from `RoomDressed`. See §5b.

### 4a. The revealed-prop collision is a bug fix, not a refactor

`Event.PropRevealed(at, roomId, propId)` has carried `roomId` since the M2 gate. The fold discards
it:

```java
case Event.PropRevealed e -> revealed(e.propId());
```

`revealedPropIds` is a flat `Set<String>`, and `GameEngine.revealProp` gates on
`state().revealedPropIds().contains(propId)`. Prop ids are unique within a room and nowhere else —
`PropPlacer` names its output `pillar-0`, `brazier-1`, and every generated room in a dungeon
produces the same names. Revealing `pillar-0` in one room therefore marks its namesake revealed in
every room the party ever enters, and the tool that would have revealed it is never even offered,
because `ToolSchema` filters the hidden list by the same flat set.

Two authored rooms will not reproduce this: their prop ids are hand-picked and distinct. The unit
test must construct it deliberately — two rooms, one shared prop id, reveal one, assert the other
is still hidden. Left alone it would surface for the first time in the generator plan, as a prop
that is mysteriously already found.

### 4b. Entities stay where you left them

With `roomId` on `Entity`, the goblin left alive in room 2 is still in room 2, and its corpse is
still there on return, because nothing moved it. The "as you left it" half of the gate falls out
of the state shape rather than needing machinery of its own. That is the argument for §4's
approach in one sentence.

### 4c. What stays session-global

`mode`, `combat`, and `consecutiveFailedChecks`. There is one fight at a time, and a player who
has failed four checks in a row is stuck in whichever room they walk into next. Scoping the
momentum counter per room would reset the DM's sense of a floundering player every time they
changed their mind about a door.

---

## 5. Rooms: structure, dressing, secrets

### 5a. The three-way split

`RoomDefinition` today is one record holding everything, and `GeneratedRoom.toRoomDefinition
(dressing)` bakes model-written prose into it. With more than one room, the halves have different
lifetimes and have to come apart:

| | Can it be made again for free? | When it is made | Where it lives |
|---|---|---|---|
| **Structure** — shape, props, exit squares | yes — from the seed, or from disk for an authored room | eagerly | nowhere; regenerated or reloaded |
| **Dressing** — name, overview, sensory, prop descriptions | **no** for a generated room, because a model wrote it | on first entry | folded from `RoomDressed` |
| **Secrets** — `theSarcophagus`, `theDoor` | yes — authored, on disk | never made | on disk |

Secrets are the third thing because they are neither: they are not state, and no model writes
them. They stay in `crypt.json` and stay out of the fold.

**"Authored only" describes M3, not secrets.** Whether the dress pass should invent them for a
generated room is a live question, and it has a right answer and a wrong one — see §12a. The
row is a statement about this milestone, which has no generated rooms in it.

Eager structure is free — `LayoutGenerator` and `ShapeGenerator` are pure functions and the whole
dungeon's geometry costs microseconds at boot. Eager *dressing* would be one model call per room
before the player sees anything, which at M2's measured latencies is not a boot anyone would wait
through. And §8 needs the neighbour's structure to exist before the party enters it, so this is
forced as well as preferred.

### 5b. Dressing is folded, and authored rooms exercise it

`Event.RoomDressed(at, roomId, dressing)` exists in the schema today and is explicitly inert:

```java
// Inputs, session framing, and the dress pass, which is content rather than state.
case Event.RoomDressed ignored -> this;
```

It folds now, into `Map<String, Dressing> dressings`, and the engine composes structure plus
dressing on read.

The alternative — a `Dungeon` object caching rooms as they are built — fails three ways. Replay
is the decisive one: `ReplayRunner` runs with no network and no model, so a cache could only fill
itself by calling a model that is not there, and teaching replay to read `RoomDressed` back into
the cache means writing the fold a second time, worse. It is also a `put`, which is exactly what
invariant #8 exists to refuse. And it would survive `GameEngine.restart()`, which clears the log —
so a restart would enter a fresh dungeon wearing the old one's prose. That last failure is not
hypothetical; `DmService.reset()` exists because the transcript had precisely this bug.

**Authored rooms emit `RoomDressed` too, on first entry, exactly as a generated room will.**
This is the decision that keeps the path honest. An authored room already contains a
`Dressing`, spread across fields:

| `Dressing` | authored source |
|---|---|
| `name` | `RoomDefinition.name` |
| `overview` | `dmNotes.overview` |
| `sensory` | `dmNotes.sensory` |
| `propDescriptions` | `{prop.id: prop.description}` |

One extraction function, and the compose path is identical whether the prose came from a content
file or a model. The consequence is that M3 exercises folding, composing and replaying dressed
rooms end to end, with deterministic content and no network — and the generator plan then changes
only *where a `Dressing` comes from*, not the machinery that carries it.

### 5c. The seed becomes load-bearing

`App` currently logs:

```java
0L, // the dungeon seed lands here when navigation does
```

It lands here. Seed plus `LayoutGenerator` is the entire structure of a dungeon, reconstructable
offline forever; `RoomDressed` is the part that cannot be. That is M1 §7's deterministic/persisted
split expressed in M2's terms, and the two halves finally have somewhere to live.

---

## 6. Crossing a threshold

### 6a. The event

```java
record PartyMoved(Instant at, List<String> entityIds, String fromRoomId,
                  String toRoomId, String throughExitId) implements Event {}
```

The movers are a list even though M3's policy is that the list is everyone. Collapsing it later is
free; un-collapsing it is a schema bump and a refused log, which is the same reasoning that made
`RollResult.faces` a list from day one. Invariant #2 is honoured where it actually costs
something.

The party lands on `Exit.inward()` — one square inside the destination, never in the doorway.
Standing on the trigger that sent you there means bouncing straight back out.

### 6b. Two paths in

**The click.** Exit squares become a distinct intent in `overlay.intent`, sending `enterExit`.
`pick_at` returns tokens and ground squares and has no prop ray test at all, so an exit is picked
as the ground square it stands on — no new pick channel, no prop AABBs, and the overlay's existing
hover highlight comes free. `SceneState` carries the exit list so the client knows which squares
those are.

**`use_exit(exitId)`**, a closed enum of the current room's exits, rebuilt from live state the way
`reveal_prop`'s hidden list already is. This exists because "I head through the north door" is the
most common sentence anyone types in a dungeon, and today it produces prose about walking through
a door, no board change, and no tool that reconcile could use to fix it — a hotdog with a very
loud tell.

Both are out of combat only, and neither requires adjacency. Crossing implies walking to the door;
the party moves as a unit and everyone lands on `inward()` regardless, so where anyone stood
beforehand has no consequence. An adjacency rule would buy a rejection the player would only ever
find annoying.

### 6c. `use_exit` is mechanics-phase only

Not offered to reconcile. m2-evaluation §8 records the tools model spawning Vessk and calling
`start_combat` off a spoken aside — the player musing aloud about treasure, on turn 11. A model
that will start a fight from an aside will walk you out of a room from *"your eye is drawn to the
dark archway"*, and a room change is louder than a fight: the whole screen changes, `## Established`
changes, and the six-turn window's frame of reference changes underneath it.

The better argument is ordering. The mechanics pass runs on what the player typed, before any
prose exists, so `use_exit` fires in phase 1 and phase 2 then narrates with the **new** room in
context. Arrival narration falls out of the existing three-phase structure at no cost.
Reconcile-based movement inverts that: prose narrates a departure, the board changes afterwards,
and the description of the room you are now standing in has to wait a turn.

The cost is a rule in the prose prompt — the narrator may write the party *to* the threshold, not
*through* it. That is the same shape as `dm-reconcile.md`'s *"a clawed hand forces its way through
the gap — a hand is not a goblin"* and the prose prompt's *"a creature is in the room when it is
listed under Entities present, and at no other time."* Both held for thirty turns.

### 6d. `move_entity`, and the rule for which phase a tool belongs in

```java
enumProp(properties, "actor_id", actorIds, "Who moves.");
intProp(properties, "x", 0, engine.room().width() - 1);
intProp(properties, "y", 0, engine.room().height() - 1);
```

`spawn_entity`'s parameter shape with `roll_check`'s enum. `actorIds` is already computed at the
top of `ToolSchema.build`. No new schema machinery.

It routes through `GameEngine.moveTo` — the same call the click makes — so it inherits bounds,
obstruction, occupancy and aliveness, and in combat `CombatEngine.moveTo` opens with
`requireActive(actorId)` and a budget check. The DM therefore cannot move a token on someone
else's turn or spend movement that is not there, and the rejection returns as a tool result the
existing loop already feeds back to the model. Nothing new to design; it inherits.

**This one goes in both phases**, and the asymmetry with `use_exit` is a rule rather than a
preference:

> A tool belongs in reconcile when a false positive is cheap to live with and the narrator is the
> one holding the information.

Walking to a pillar: cheap to be wrong about, and the narrator is exactly who knows it was said.
Leaving the room: expensive, and the player should own it. The same rule explains the gates
already in `ToolSchema` — `assert_fact` is reconcile-only, and `roll_check` is mechanics-only
because a die thrown after narration can only contradict it.

The failure this fixes is a reconcile failure: the player says they cross to the east pillar, the
prose follows them, and the token stays where it was. The DM has been shown everyone's coordinates
under `## Entities present` since M0 and has never had a verb that could act on them.

**One risk to measure rather than guess.** Reconcile calling `move_entity` means prose that says
"you cross to the far wall" slides a token, and at 372 characters a turn the narrator writes a lot
of incidental motion. Before writing the reconcile rule, count how often the thirty-turn session
at `docs/evidence/session-m2-twenty-turns.jsonl` implies movement. The momentum directive was
tuned against evidence; so is this.

**In passing:** `actorIds` includes the dead, so a dead goblin is offered to `roll_check` today and
rejected at dispatch. Filter to the living, the way `hidden` already filters to unrevealed.

---

## 7. The DM across a threshold

Most of the projection swaps itself. `factsHere()` is already room-scoped, so `## Established`
changes rooms for free; `## Entities present` becomes `entitiesHere()`; the room block, prop lists
and `## Grid` follow the room lookup.

### 7a. Ways out name directions, never destinations

A new `## Ways out` block, listing exits by the wall they are in. *"A door in the north wall."*
Never `door-north → room-2`.

The player can see there is a door and cannot see what is behind it, and §8's dark neighbour is
honest about exactly that. A destination id in the prompt is a string the narrator can read aloud,
which is m2-evaluation §8's grid-coordinate finding in a different costume.

### 7b. The threshold marker and the visited flag

Cross into room 2 and the six-turn window is still full of room 1's prose — the eleven tallies,
the sarcophagus, the fresh grit — while the world-state block describes different walls. That is
the setup for bleed: room 1's details written onto room 2's stone. The M2 session showed how hard
this narrator leans on the transcript for texture, producing callbacks for thirty turns; that is
the same mechanism pointed the wrong way.

So the transcript gets a line at the crossing, saying the party left one room and is now in
another and that everything above it happened somewhere else.

Coming back is the opposite problem. Return to the crypt after ten turns elsewhere and the window
has dropped every mention of it; the narrator rebuilds the room from its dressing plus
`## Established`, with nothing telling it this is a return rather than a first look.
`## Established` already says *"do not re-introduce them as new"*, but that directive has never
been tested against a room the transcript has entirely forgotten. `visitedRoomIds` makes it
explicit, and lets the arrival directive fork: describe what they walk into, versus they know this
place and do not rebuild it from scratch.

A carried per-room summary was considered and rejected. `## Established` *is* the room's memory —
that is what it was built for and it survived the window at the gate. A second summarisation layer
is a second thing that can disagree with the facts, and when they disagree the narrator has no way
to know which is right.

---

## 8. Rendering: the dark neighbour

Both rooms are in the 3D world at once. Walking through a door does not cut to black and rebuild
the same rectangle with different textures; the room beyond is already there, unlit, and lights as
you enter it.

### 8a. `grid_to_world` becomes room-aware

Today every room is centred on the world origin:

```gdscript
func grid_to_world(x: int, y: int) -> Vector3:
	var width := float(_room_width())
	...
	return Vector3(x - width / 2.0 + 0.5, 0.0, -(y - height / 2.0 + 0.5))
```

It becomes `grid_to_world(room_id, x, y)` against a per-room origin table. Twelve production call
sites across `world.gd`, `overlay.gd`, `room.gd` and `token.gd`, and about twenty GUT assertions —
one of them named `test_grid_to_world_centres_the_room_on_the_origin`. A small function with a
wide blast radius, all of it typed and visible, which is the good kind.

Invariant #4 — *the 3D world is one scene, created once* — is not in tension with this. It says one
scene, not one room.

### 8b. Why the neighbour is dark

Geometry only: floor and walls, no torches, no props, no entities, and **nothing in the DM's
prompt**.

That last clause is the whole design. If a lit, dressed neighbour were on screen while the
narrator had never been told it exists, it would write a sealed passage or a dark corridor over a
room the player is looking at — which reopens the M2 gate's question in a new form and is a
milestone's worth of risk, not a task. An unlit, empty room is one the narrator cannot contradict,
because a dark space is what a dark space looks like. The fiction and the rendering agree by
construction.

It also gives the crossing a better beat than a fade: the dark room lights and dresses itself as
you arrive, at the same moment the arrival narration lands.

`MAX_TORCH_LIGHTS = 10` is per-room and `TORCH_ENERGY` was retuned so the middle of a generated
room stays a dark floor. A dark neighbour adds no lights, so neither number is disturbed.

---

## 9. The wire

`SceneState` gains:

- `List<Exit> exits` — so the client knows which ground squares are doors.
- A neighbour outline per exit: room id, dimensions, floor and wall type, and the world offset
  relative to this room.

**No new `Diff` variant.** A room change replaces everything, which is what `ServerMessage.Scene`
is already for — *"sent on any change large enough that a diff would be silly"* — and `world.gd`
already rebuilds props and tokens when `roomId` changes. A room change sends a fresh `Scene`.

Wire mirrors are hand-written GDScript readers under `godot/`, updated in the same commit as the
Java record, per the standing rule.

---

## 10. The gate

A played session and an offline replay, written up as `../../milestones/m3-evaluation.md` and signed, in the
shape M0 and M2 used.

Mechanical criteria alone would not do. Four of the things most likely to go wrong here are
invisible to an assertion: bleed, re-introduction on return, `move_entity` making the fighter
wander on loose narration, and whether the dark neighbour reads as a dungeon or as a bug. Every one
of those is a judgement about prose.

| # | Criterion |
|---|---|
| 1 | The session crosses thresholds repeatedly, and returns to a room at least twice. |
| 2 | **A return counts only after seven or more turns spent elsewhere.** `WINDOW_TURNS = 6`; an earlier return tests the window rather than the facts. |
| 3 | **"As you left it" names a specific thing in a specific state** — a revealed prop and a fact asserted about it, found unchanged. Not a vibe. |
| 4 | Nothing the DM says about the room it is in contradicts what that room has established, including across a threshold in both directions. |
| 5 | The session replays offline with no network, across rooms — which is what proves `PartyMoved` folds and that entities stayed where they were. |

Criterion 2 is the one that makes this falsifiable. M2 needed thirty turns for the same reason: a
callback inside the window proves only that the window works.

Recorded but not graded, in the M2 style: bleed incidents, unnecessary `move_entity` calls, and
whether the aside-starts-a-fight behaviour from m2-evaluation §8 recurs now that there is a second
loud verb in the mechanics phase.

---

## 11. What M2 handed forward

m2-evaluation §8 listed six carry-outs and said navigation would carry them. Honestly accounted:

- **`assert_fact` on a failed search must not empty the world** — fixed 2026-09-05, commit
  `2922ea6`. Not in this plan.
- **Kill beats omit the actor** — fixed 2026-09-05, commit `cd3a01f`. Not in this plan.
- **Talking to yourself can start a fight** — not a task here, but it is the direct reason
  `use_exit` is mechanics-only (§6c), and §10's recorded-not-graded notes watch for it.
- **Grid coordinates in `## Established`** — not a task here, but it is the direct reason
  `## Ways out` names directions rather than destination ids (§7a).
- **`roll_check` twice on one action** — still a finding. Unrelated to traversal.
- **Invented keys are durable** — still a finding, and it stops being awkward the moment the north
  door opens. The M2 session found a copper key that turned the lock while the slab stayed dead,
  because `dmNotes.theDoor` says the door is sealed *because the room is unfinished*. This
  milestone finishes it.

`door-north` is already a `DOOR` prop at (6, 11) — the north wall of a 12×12 room, non-corner,
with the party starting at (6, 1) at the opposite end. It has been an exit square in everything but
function since M0. Room 1 needs no new content: one exit on an existing prop, and a DM note to
delete.

---

## 12. What follows

The generator plan. `LayoutGenerator`, `ExitPlacer` and the `SpatialValidator` exit rules lift
from the old plan nearly verbatim; what is new is world-space packing, so that a dungeon's rooms
have non-overlapping rectangles for §8 to render. It inherits a working traversal spine and
changes only where a `Dressing` comes from.

M1's gate — *can the world be made rather than authored* — is answered there, not here.

### 12a. Generated discovery, and the secret that is safe to invent

Not M3 work. Recorded here because the question arrives the moment §5a's table is read, and
because there is a real hole underneath it.

**The hole.** `PropPlacer` hardcodes the hidden flag:

```java
placed.add(new Prop(
        entry.type().name().toLowerCase() + "-" + i,
        entry.type(),
        square.x(), square.y(),
        facing(random, shape, square, partyStart, entry.type()),
        false));
```

So a generated room has no hidden props, and `ToolSchema` gates `reveal_prop` on
`hiddenPropIds()` being non-empty — which means **the tool is never offered in a generated room
at all.** The crypt has its alcove; a generated room has nothing whatsoever to find. Discovery
does not exist in generated content today, and that is a larger gap than the missing secrets.

**The wrong fix** is letting the dress pass write free-form secrets — a paragraph per room about
what is buried under the flagstones. That is prompt text with no mechanism behind it, and the
player who investigates finds that nothing can happen: there is no prop to reveal, and
`SPAWNABLE_KINDS` is `List.of("goblin")` with `spawnGoblin` refusing a second, so "something
waits inside" cannot cash out either. `DmService.worldState`'s own comment on the secrets block
records what this costs — the prose model, told a goblin was inside the sarcophagus and would
come out fighting, wrote it climbing out on a turn where nothing had spawned and no fight had
started. m2-evaluation §4 caught the same shape again with the copper key: internally
consistent, not on the board. Generating that per room is manufacturing the failure on purpose,
at dungeon scale.

**The right fix is the same change as the hole.** A secret with a mechanism is a hidden prop.
`PropDefinition` already carries `hidden`, `revealHint` and `contains`, and
`GeneratedRoom.toRoomDefinition` fills all three with `null`. Have the generator mark some props
hidden and the dress pass write those fields, and the model invents what is concealed and why
anyone would find it, while `reveal_prop` cashes it out against a closed enum and the renderer
draws a prop that was always standing on a square. Invariant #7 is untouched, and the DM cannot
promise anything the board will not deliver, because the thing it is being coy about already
exists.

---

## 13. Order of work

1. `Direction`, `Exit`, `RoomDefinition.exits`, `exitAt`. Pure model, no behaviour.
2. Room scope in `WorldState`: `Entity.roomId`, `entitiesHere()`, the `(roomId, propId)` reveal
   set and its collision test, `visitedRoomIds`.
3. `RoomDressed` folds; the authored-room `Dressing` extraction; the engine composes on read.
4. The room lookup replacing `GameEngine`'s single `RoomDefinition`, and `CombatEngine` with it.
5. `PartyMoved`, and `enterExit` end to end with the client still cutting between rooms.
6. `use_exit` and `move_entity`, with the prose and reconcile prompt rules — the `move_entity`
   rule written after counting the M2 session.
7. The DM's cross-threshold context: ways out, threshold marker, visited flag, arrival directive.
8. The second authored room.
9. `grid_to_world` room-aware; the dark neighbour; the wire's neighbour outline.
10. Capture a multi-room session, add the replay fixture, play the gate, write
    `../../milestones/m3-evaluation.md`.

Steps 1–4 are invisible to a player and are most of the risk. Step 5 is the first one that can be
walked.
