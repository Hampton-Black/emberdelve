# M1 — Procedural dungeon generation

**Status:** Design approved, pre-implementation
**Companion to:** `docs/m0-evaluation.md` (what M0 proved) and `docs/ai-dm-system-design.md` (the
long-range design, which this milestone deliberately reorders — see §9)

---

## 1. What M1 is for

M0 was a gate: *does this feel like a Dungeon Master running a game?* It passed. M1 is not another
gate — it is construction. It asks a different question:

> Can the world be **made** rather than authored?

Success criterion, in M0's style — a thing you do, not a box you tick:

> Descend three rooms into a freshly seeded dungeon. Each room reads as a distinct place. Nothing
> the narrator says contradicts what is on the board.

The last clause is the one that matters. `m0-evaluation.md` §4.3 established that multi-turn
consistency is this project's real metric, and generated content is a new and much larger surface
for the narrator to contradict.

---

## 2. Why procgen and not the rules engine

`ai-dm-system-design.md` §14 puts the rules engine at M1 and dungeon generation at M4. This
milestone takes generation first, deliberately.

- **Motivation is the scarce resource on an evenings project.** Building the correct thing in the
  correct order while bored is how solo projects die. Generation is the part that is currently
  interesting, and interest is worth spending.
- **Procgen forces the foundation anyway.** It cannot be built without content-as-data, a real
  room/kit schema, and multi-room state. Those are the durable pieces. This is a different entrance
  to the same foundation, not a detour around it.
- **Generating encounters for one goblin with one attack tests less than it appears to.** The
  rules engine is more meaningful *after* there is a world to fight in, not before.

---

## 3. Scope

### In

| Component | M1 form |
|---|---|
| Layout | Seeded, deterministic Java. Scenes, corridors, exits, connectivity graph |
| Kit | Content-as-data: tile types, prop catalog with ids and footprints |
| Dressing | One LLM pass per room, JIT on entry, validated server-side |
| Navigation | Exits work. Scene swaps on room change |
| Movement | Free movement outside combat, and the player's position as DM context (§6b) |
| State | Multi-room, still in memory behind `GameRepository` |
| Client | Existing Three.js client renders generated rooms |
| Debug view | Terminal dump of a generated dungeon, for fast iteration |

### Out — and why

| Deferred | Why not in M1 |
|---|---|
| 5e rules engine | M1's question is generation, not tactics. M0's ~40-line attack resolution survives untouched |
| Postgres | A multi-room dungeon needs state across *rooms*, not across *sessions* |
| Party beyond one fighter | Invariant #2 already makes this a content change, not an architecture change |
| Runtime asset generation | See §8 |
| Dungeon *purpose* — quests, goals, arcs | The campaign layer (M7). Rooms first, meaning after |
| Encounter balancing | Needs a rules engine to balance against |
| Prefetch | JIT dressing is covered by narration (§5). Revisit if a threshold hangs |

---

## 4. Architecture

Five pieces. Four have no model in the path.

**`DungeonLayout`** — seeded, deterministic, pure. Produces rooms, corridors, exits and a
connectivity graph from `(seed, coordinates)`. Snapshot-testable: same seed, same dungeon, forever.
Expected to become an interface with per-environment implementations (§7b); built against the crypt
only, and not generalised in M1.

**`RoomKit`** — content-as-data. Tile types, prop catalog with ids and footprints, placement rules.
This is the closed enum the model picks from, and the project's first real content-as-data schema.
Its shape outlives M1; the rules engine will load monsters and spells the same way.

**`RoomDresser`** — the single LLM pass. Given a room's shape, its neighbours and the kit, it decides
what the room *is* — a flooded reliquary, a collapsed barracks — places props by id, and writes a
stub the narrator later expands. Every output validated server-side (§6).

**`DungeonState`** — current room, visited rooms, entity positions per room. Behind the existing
`GameRepository` interface; still an in-memory implementation.

**Navigation** — exits become real. An `Exit` carries a target scene id; the client sends a move; the
server swaps the scene. M0's acceptance script step 9 ("the north door is a wall, handled in
fiction") stops being a graceful excuse and becomes a real transition.

**`Exit`, not `Door`** — deliberately. The edge between two scenes is a door in a crypt, but it is a
road out of a village, a path over a mountain pass, a ford across a river. M1 only ever builds doors;
the name is chosen now because it costs nothing today and is a painful retrofit later. Same class of
decision as `RollResult.faces` being a list from day one.

---

## 5. Data flow

```
seed → DungeonLayout            (instant, deterministic, no model)
     → player enters a room
     → RoomDresser              (one LLM call, ~2-4s)
     → spatial validation       (§6)
     → SceneState → client
```

**Narration covers the dressing latency.** The DM describes the player passing through the door
while the next room is being dressed. This is the same pattern as dice covering prose latency,
which M0 measured and confirmed works — and `m0-evaluation.md` §4.1 found that uncovered waits are
the only ones players notice.

---

## 6. The new invariant

Closed enums are still necessary and are no longer sufficient.

> **Generated content is validated for spatial legality, not just enum membership.**

A prop placed in a doorway is a **valid enum and an invalid world**. The validator must assert, at
minimum: every exit remains reachable, props do not overlap, no prop blocks the only path between
two regions, and every room in the layout is connected.

This is the same lesson as the goblin that gets vividly described and never spawns, one level up:
validation has to check the *world*, not merely the *value*. The validator exists before the first
generated room reaches a client, not after the first bad one does.

---

## 6b. Free movement, and position as context

`ai-dm-system-design.md` §15 defers grid-walkable exploration until "combat is polished and you want
interactable props." That deferral is about a *mechanic* — walking to things as the way you interact
with them, proximity triggers, an interaction model per prop, and a content pipeline behind it. That
remains deferred.

What M1 adds is narrower and much cheaper: **free movement as embodiment.** The player may walk
anywhere unobstructed outside combat because the token answering them feels good. Nothing is
triggered by proximity. Typing is still the only interaction verb.

The machinery already exists — pathfinding, the slide animation, footstep audio, and
server-authoritative legal-move computation all shipped in M0. The only change is computing a
legal-move set outside combat, which is every unobstructed square, since there is no movement budget
to spend.

**Position becomes DM context, and that is the actual reason to do it.** The world state handed to
the narrator gains what the player is standing next to. A player who walks to the sarcophagus and
types "I examine this" no longer requires the narrator to guess the referent, and "you press your
shoulder to the lid" is truer when the token is beside it. `m0-evaluation.md` §4.3 named grounding as
the metric this project lives or dies on; this is an input channel that serves it for the cost of one
field.

**The risk to watch: false affordance.** Being able to walk to the sarcophagus implies being able to
click it. Typing is already the interaction verb and the DM answers it, but this should be observed
in the first session with generated rooms rather than assumed away. If it bites, the fix is a
narrower legal-move set, not an interaction model.

---

## 7. Scaling — how this reaches a vast world

The renderer never constrains this. One room is loaded at a time, so the client holds one room's
geometry whether the world has five rooms or fifty thousand. (Seamless room-to-room travel *would*
be a streaming problem, and the design doc's narrative-exploration decision rules it out.)

Three real limits, in the order they are reached:

1. **Context — first, and soon.** Shortcut #11 sends the whole transcript uncompacted. That dies in
   session two. A DM that cannot remember room 3 when the player returns to it contradicts itself,
   which is the gate metric failing for a mechanical reason. **This is the true ceiling on world
   size**, and it is M5's synthesis work.
2. **Coherence — second, and decisive.** Infinite generation is easy; infinite *meaning* is not.
   Forty dressed rooms with no relationship to one another is design-doc risk #6 exactly. Roguelikes
   answer this with bounded runs and floors: infinity comes from repetition with variation inside a
   structure, never from continuous expansion.
3. **Storage — barely a limit.** A room definition is a few KB. Ten thousand rooms is tens of MB.

### The split that makes a large world cheap

Build this into M1 even though persistence is deferred. It is nearly free now and expensive to
retrofit.

| Layer | Deterministic? | Storage |
|---|---|---|
| Layout, exits, connectivity | Yes, from `(seed, coords)` | **Never stored** — regenerated on demand |
| Dress pass (what the room *is*) | **No** — an LLM wrote it | **Persisted on first visit** |
| World deltas (exit opened, goblin dead) | No | Persisted |

Minecraft and No Man's Sky are "infinite" on precisely this trick: store the seed and the deltas,
not the world. The wrinkle specific to this project is that **the dress pass is not reproducible** —
"a flooded reliquary" cannot be regenerated from a seed — so dress output is durable data from the
moment it exists.

**"Persisted" in M1 means kept, not written to a database.** Postgres is still deferred (§3). The
requirement here is that dress output is *retained rather than regenerated* when the player returns
to a room, and that it is shaped as durable data — so that swapping the in-memory implementation of
`GameRepository` for a real store later is an implementation change and nothing more. Regenerating a
room's identity on re-entry would be the generated-content version of the DM contradicting itself.

**The long-term shape is bounded regions, unbounded in number**: each dungeon coherent and finite,
the world made of many. *Expanding* is not the goal; room 40 meaning something is the goal, and
meaning comes from structure (M7), not from generation.

---

## 7b. Environments beyond the crypt

M1 builds one environment. This section exists so the model does not have to be unpicked when the
second one arrives.

**A "room" is a bounded scene, not a walled chamber.** The model is already a grid, a tile set,
props, entities and exits — nothing in it requires walls or a ceiling. A village is cobblestone and
grass tiles with building props and roads; a mountain pass is rock tiles with a linear topology.
Structurally they are the same object, which is why the abstraction is worth keeping literal-minded.

**Openness is a framing problem, not a data problem.** The isometric CRPGs this project resembles —
Baldur's Gate, Pillars of Eternity, Divinity — are all bounded scenes and none of them read as boxes.
Four techniques do the work: a backdrop with no gameplay in it (distant mountains, fog, a skyline);
soft edges that give a reason not to walk that way (a cliff, a river, a treeline, a row of building
fronts); a larger grid with the camera pulled further back; and detail near the player falling off to
silhouette in the distance. `SceneState` already carries `LightingPreset`; a backdrop and an edge
treatment are the same kind of field.

**Only combat needs tactical fidelity.** An open area has to be a *tactical* space when a fight
starts and a *described* space the rest of the time. A village is a backdrop and a conversation until
someone draws a blade, at which point it becomes a grid with cover, elevation and line of sight. That
is a far smaller problem than rendering a fully interactable town, and it is worth not giving up
accidentally.

Note that free movement (§6b) does **not** weaken this. The player walking around a village is not
the same as the village needing interaction-by-proximity; the mode that needs fidelity is still
combat alone.

**What changes when the second environment lands:**

| Concern | Cost |
|---|---|
| Kits | Free — `RoomKit` is content-as-data, so a village kit is a JSON file |
| Layout | Real work — a crypt is a graph of chambers, a settlement clusters around a centre, a pass is *linear*. `DungeonLayout` becomes an interface with implementations |
| Scene attributes | Small — backdrop and edge treatment alongside the existing lighting preset |
| Exits | Already handled, by the naming decision in §4 |

None of this is built in M1. It is written down so that M1's choices do not preclude it.

---

## 8. Runtime asset generation — overturning a "never"

`ai-dm-system-design.md` §15 lists runtime asset generation as deferred with the re-entry condition
**"Never."** That ruling is narrowed here rather than kept or discarded, because the reasoning
behind it turns out to distinguish four different things:

1. **Runtime mesh generation** — still refused. Untrusted topology, no rig (every character depends
   on the shared 32-clip skeleton), and per-asset latency.
2. **AI composing rooms from a fixed kit** — this milestone. The generated artifact is a room
   *definition*, not a mesh.
3. **AI generating content data** — stat blocks, item text. Same closed-enum protection.
4. **Build-time asset generation** — new props authored during development, reviewed by a human,
   baked into the kit before play. Not considered by the original ruling and not covered by its
   objection.

### The case that reopened it

A playtester asked the DM to turn the goblin into a hotdog and it obliged — with no transformation
tool, so the board never changed (`m0-evaluation.md` §4.4). Generating the picture would not have
fixed that; it would have made it worse, because the board would then agree with the narration while
the *rules* still did not.

Two findings worth keeping:

- **The pixelated look is unusually forgiving of generated imagery.** At ~480px internal width a
  token is 24–40 pixels, and almost anything downsampled that far reads as deliberate pixel art. An
  inanimate target also needs no rig and can be a billboard rather than a mesh. The art was never
  the hard part.
- **The rules bound the generation space.** Polymorph's targets are "a beast of CR ≤ X" — a closed
  set. Generated assets for it are not unbounded generation; they are rendering a closed enum with
  large membership, which is invariant #7 applied to art instead of tool arguments. A CR-bounded
  beast library can therefore be generated at *build* time and reviewed. True runtime generation is
  needed only for what falls outside the rules entirely — the hotdog — which is the exception, not
  the mechanism.

**Re-entry condition:** when the rules engine gains an effect whose result space is larger than the
asset kit. It fires on its own, and it arrives with the mechanics already built — which is the part
that was actually missing.

---

## 8b. The renderer decision

**Three.js stays for M1.** A switch to a game engine (Godot was the candidate considered; Unity was
judged heavier than this game needs) was weighed and deferred, for reasons worth recording:

- **The combat engine is unaffected either way.** 5e tactical combat is grid-based integer geometry
  — A*, Chebyshev distance, ray-grid line of sight, set math for AoE templates. It needs no physics,
  and a physics engine would actively fight invariant #1 and invariant #6 by introducing float
  nondeterminism into a system whose rolls are replayed from an event log.
- **Procedural generation is server-side.** The client renders whatever `SceneState` arrives. What is
  genuinely client-side is the kit structure and the preview loop.
- **The text chrome is HTML's strength.** A scrolling per-speaker-coloured transcript is trivial in
  CSS and awkward in an engine's UI system, and it is the layer the player reads most.
- **The feel findings port as numbers, not code.** The 220ms impact beat, nine layers on a killing
  blow, 1.25-unit oversized figures — all engine-independent and all already written down in
  `AGENTS.md`. This is what makes deferring cheap rather than debt-accruing.

**Re-entry condition:** when authoring rooms and terrain by hand becomes the bottleneck — which the
kit work in this milestone will make concrete. What an engine actually buys is editor tooling,
animation state machines and audio buses; none of those are load-bearing yet. If the bake-off is run,
it should be timeboxed to one evening and judged on **text chrome, room authoring, and generated-room
preview speed** against the Three.js client that already exists.

---

## 9. Deviations from `ai-dm-system-design.md`

The design doc is the long-range plan; these are the agreed amendments.

- **Milestone order.** Generation (doc M4) comes before the rules engine (doc M1). Reasons in §2.
- **Runtime asset generation** moves from "never" to a conditional re-entry. §8.
- **A spatial-legality validator** is required, which the doc does not describe. §6.
- **The deterministic/persisted split** is adopted now rather than at the persistence milestone. §7.
- **The renderer choice** is explicitly re-examined and deferred, with a re-entry condition. §8b.
- **`Exit` replaces `Door`** in the scene model, ahead of any non-dungeon environment. §4, §7b.
- **Free movement outside combat** is pulled in, narrowed to embodiment plus DM grounding rather than
  the interaction mechanic the doc deferred. §6b.

---

## 10. Testing

All offline, all fast, extending the `ScriptedDiceRoller` pattern to generation.

- **Layout**: snapshot tests on seeded output. Same seed, same dungeon.
- **Dresser**: run against a scripted `DmClient` with no network, asserting that valid output is
  accepted and that out-of-kit prop ids are rejected.
- **Spatial legality**: place a prop in an exit, assert rejection. Disconnect a room, assert
  rejection.
- **Navigation**: move between rooms, assert scene swap and preserved state in the room departed.

---

## 11. Risks

**Mush.** Three rooms that all read as "a dark stone room." The real failure mode, design-doc risk
#6, and the dress pass is where it is won or lost. The M0 finding applies directly: terse
instructions beat explanatory ones, and this should be measured rather than argued about.

**Threshold latency.** If narration fails to cover the dress pass, every threshold hangs. Uncovered
waits are the ones players notice.

**Dress-pass nonsense.** Blocked exits, overlapping props, unreachable rooms. The validator (§6) is
the answer and must exist first.

**Scope creep into the rules engine.** Generated encounters will make the ~40-line attack resolution
feel thin. It stays thin. That is M2's problem.
