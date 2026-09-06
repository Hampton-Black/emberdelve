# AI Dungeon Master — Initial System Design

**Status:** Written 2026-08-19, before any code existed. **Amended 2026-09-05**, after three
milestone gates played against real sessions. Roughly half the decisions below survived contact.
The ones that did not are corrected in place and explained in §2a — nothing is deleted, because a
reversed decision is more useful with its original reasoning attached than without it.

**How to read this.** This is the long-range design: the shape of the thing and why it is that
shape. `AGENTS.md` is the operational file — what an agent must actually do today — and it wins on
any conflict. Each milestone's own reasoning lives in its spec under `docs/superpowers/specs/`.

**Scope:** Personal single-player toy, architected so multiplayer is a feature and not a rewrite.

---

## 1. What this is

A solo-playable D&D-style RPG where an LLM acts as Dungeon Master. The player controls a party of
pregenerated characters. Exploration is free-text and narrative; combat is tactical, grid-based,
and adjudicated by a deterministic engine. The world renders as a stylized isometric 3D scene
(native resolution, cartoon-kit meshes — miniatures on a board, not a fake-pixel downsample and not
hand-drawn 2D isometric sprites). Narration is spoken via TTS, with a persistent narrator voice and
distinct voices for recurring NPCs.

**What it is not:** not a VTT, not a product, not multiplayer (yet), not a chat interface with
pictures. The tactical grid is the reason the 3D renderer exists. If the grid ever gets cut, cut
the renderer too.

**Governing principle:** *The LLM proposes, the engine adjudicates.* The model never rolls dice,
never does arithmetic, never decides whether an attack hits, never moves an entity, and never
invents an asset. Every mechanical effect goes through a validated tool call against
server-authoritative state.

**The principle held, and it is the one thing here that has never needed amending.** Everything
that went wrong across three gates went wrong on the model's side of that line — a goblin narrated
and never spawned, a copper key that turns a lock in a door with no mechanism behind it — and the
line is what made each of them a bug you can name rather than a game that quietly stops making
sense. Where the design has been reversed, it has almost always been *toward* this principle and
never away from it.

---

## 2. Locked decisions

Updated to what is true as of 2026-09-05. Reversals are explained in §2a.

| Area | Decision | Rationale |
|---|---|---|
| Backend language | **Java 25** | Sealed interfaces + records + exhaustive pattern matching are the right fit for a rules engine's tagged unions. Virtual threads make the streaming orchestration layer read as blocking code. |
| Rules engine style | **Content-as-data** | Spells, monsters and effects are declarative content files evaluated by a small interpreter. Testable in isolation; modding for free. Already true of rooms, entities and kits; the spell/effect layer is not built yet — see §6. |
| Persistence | **Append-only event log, JSONL on disk. No database.** | State is a fold over the log. Postgres buys nothing until something needs to be queried rather than replayed. |
| Job runner | **None** | The one async job the original design named (synthesis) does not exist yet. TTS is a synchronous call behind an interface. |
| Transport | **Single websocket** (`/ws`) | Server pushes state diffs; client sends actions. HTTP only for assets. |
| Client | **Godot 4.7, Forward Plus** (originally React + Zustand + Three.js) | One engine for chrome, renderer and audio, instead of three libraries and a bridge between them. |
| Renderer | **Orthographic isometric 3D at native resolution.** KayKit-class meshes, linear filtering, real lights. No low-res nearest-neighbour pass | Z-buffer solves depth sorting. 3D kit content scales far better than sprites for an infinite-variety game — the reason this is not 2D isometric pixel art. |
| Schema source of truth | **Java records, hand-mirrored into GDScript. No IDL.** | The IDL was the highest-leverage structural bet in the original design and it did not pay — see §5. |
| Ruleset | **Full-SRD-shaped engine, trimmed content** — *deferred, not reduced* | 4 classes, levels 1–5, ~30 monsters CR 0–3. Keeps reaction hooks and concentration. Today's ~40 lines of attack resolution are a proxy standing in for it, not a replacement — see §6. |
| Combat | **Grid movement, one melee attack, initiative, Chebyshev distance** | No reach, cover, AoE, or opportunity attacks. Terrain belongs to the room, not to combat. |
| Exploration | **Narrative. The grid is rendered, and only exits are clickable** | Avoids the interactable-object content pipeline entirely. M3 adds an explicit door click — one verb, not free walking. |
| Monster tactics | **Deterministic execution, LLM sets stance only** — *stance layer deferred, not dropped* | Latency, testability, tunability, no spatial hallucination. `GoblinAi` is the execution half, shipped and working; the stance call above it is not built yet — see §8. |
| Dungeon generation | **Procedural shape → prop placement → one LLM dress pass, at room-build time** | Shipped in M1. The JIT-with-prefetch scheme was not needed — generation is fast, and a room is dressed once at build time rather than at the door. |
| Model hosting | **Venice.ai, OpenAI-compatible, behind an interface** | Simplest for solo. Swap to local is config, not code. |
| Model count | **Two: a fast tool-caller and a good writer** | Not in the original design, and the single most valuable structural change since. See §10. |
| Character creation | **Pregens from content JSON** | Same schema a builder would later write to. One fighter exists today. |
| Voice | **Persistent narrator + per-NPC voices, cast by a closed table** | NPCs must sound the same across sessions. ElevenLabs Flash v2.5 behind `TtsClient`; OS TTS is the dev default. |
| Session boundary | **Not built.** Sessions are written; nothing resumes from one | The explicit End Session button and the idle timeout both wait on synthesis, which does not exist yet. |
| Dice | **Hand-rolled domain model, injected `DiceRoller`, results logged as events** | Notation parsing is the least interesting part; per-die faces and replay-from-log are the requirements. |
| Dice renderer | **No physics library. `godot/dice/tumble.gd` animates a decided result** | The server is authoritative, so there is nothing for a solver to solve. See §7. |
| Deployment | **`localhost`, one process, no container** | No auth, no multi-tenancy, no hosting. |

### 2a. What play reversed, and why

Four decisions were overturned by playing the thing. Each is worth keeping because the *reason* it
was wrong generalises.

**Postgres → an event log on disk.** The original design gave persistence a schema before it gave
the game a spine, and the two are not the same problem. What the project actually needed was the
guarantee that *state is a fold over the log and there is no second write path* — a constraint on
the code, not a choice of store. Once that constraint held, the store stopped mattering: JSONL
replays, and nothing in the game queries across sessions. Postgres is deferred, not cancelled, and
it arrives the day something needs a query rather than a replay.
(`specs/2026-08-23-m2-spine-design.md` §5.)

**The IDL → nothing.** "One IDL generating Java records, TS types, and LLM tool schemas" was named
the highest-leverage structural decision in the stack. It was never built, and the reason is that
its three consumers turned out to want different things: the tool schema must be built *from live
engine state* — the hidden props in this room, the entities that are still alive — which a static
IDL cannot express. `ToolSchema.build(engine)` reads the world and emits the enum, every turn. The
principle the IDL existed to serve survived intact and became invariant #7; the mechanism did not.
See §5.

**React + Zustand + Three.js → Godot.** Three libraries, a canvas that must never be re-created by
a render, an audio queue built by hand, and a store to keep game state out of React. Godot has one
scene tree, one audio bus, and one place for state to live — and the boundary the original design
was most careful about (game state never lives in a UI node) is *easier* to hold there, not harder.
Migrated 2026-08-23 behind a parity gate. (`specs/2026-08-22-chrome-direction-design.md`.)

**One model → two.** Not a reversal so much as a discovery, and the most valuable one. The original
design assumed one narrator and warned against fragmenting narration across models — which is still
right. What it missed is that *mechanics is not narration*. Splitting the tool-calling phase onto a
fast non-reasoning model and leaving prose on the best writer available made two previously
disqualified models usable and cut a measured 44-second turn to 8.9 seconds. See §10.

---

## 3. Architecture

```
┌──────────────────────────── Godot client ─────────────────────────────┐
│  Chrome (Control): transcript, dice tray, roll log, HP, mode pill     │
│  Table (autoload) ◄── websocket ──►  the only place game state lives  │
│  World (Node3D): one scene, created once. Isometric, 90° snap         │
│  Clock (autoload): ordered speech queue; also paces the transcript    │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ ws /ws: {actorId, action} ↑ / diffs ↓
┌───────────────────────────────┴───────────────────────────────────────┐
│                      Java backend (one process)                       │
│                                                                       │
│  WsHandler ── one session                                             │
│      ├── GameEngine ── authoritative. Emits events, folds WorldState  │
│      ├── CombatEngine ── pure, sync, no I/O, injected DiceRoller      │
│      ├── GoblinAi ── deterministic execution. Stance layer not built  │
│      ├── RoomGenerator ── shape → props → one LLM dress pass          │
│      ├── DmService ─┬── mechanics model  (tools)     ┐                │
│      │              ├── prose model      (narration) ├─ Venice.ai     │
│      │              └── reconcile pass   (tools)     ┘                │
│      └── TtsClient ── OS TTS today, ElevenLabs behind the seam        │
│                                                                       │
│  EventLog ──► SessionWriter ──► server/sessions/*.jsonl               │
│       ▲                                                               │
│       └── ReplayRunner reads one back with no network and no key      │
└───────────────────────────────────────────────────────────────────────┘
```

**Content files (git-versioned, loaded at boot):** rooms, entity definitions, kit manifest, prompts.

The synthesis worker and the prefetch worker in the original diagram do not exist. Neither does the
job table they would have polled.

---

## 4. Data model

**There is no database.** The spine is an append-only list of events, mirrored to JSONL as it is
written, and every piece of world state is a fold over it.

```java
sealed interface Event {
    int SCHEMA_VERSION = 1;
    Instant at();
}
// SessionStarted, PlayerSaid, NarrationLogged, ToolCallIssued, ModeEntered,
// PartySpawned, EntitySpawned, EntityMoved, AttackResolved, CheckResolved,
// PropRevealed, RoomDressed, CombatStarted, TurnAdvanced, CombatEnded, FactAsserted

record WorldState(
    String roomId,
    Map<String, Entity> entities,
    List<PartyMember> party,
    Mode mode,
    Set<String> revealedPropIds,
    Optional<CombatRecord> combat,
    List<Fact> facts,
    int consecutiveFailedChecks
) { }
```

Notes:

- **The log is the spine, and `WorldState` is a projection with no independent existence.** It is an
  immutable record rebuilt by `apply(state, event)` — there is no setter and no `put`. If a change
  did not emit an event, it did not happen. This is what keeps the log complete without anyone
  having to remember to keep it complete. (Invariant #8.)
- **Roll events carry `faces[]` and the outcome. Replay reads them and never re-rolls.** The
  original design's reasoning for this is unchanged and is in §7.
- **A log written at an older `SCHEMA_VERSION` is refused, never upgraded.** Discarding an old log
  is free; an upgrader is a tax paid forever. The version is a constant on the interface rather than
  a field somebody remembers to bump, so refusing is the default and permitting is the effort.
- **Facts are how the world holds what has no mechanism.** A smell, a scratch on a wall, a ring on a
  dead hand: the DM asserts them, they fold into `WorldState.facts`, and they are still true next
  turn. `canon_fact` in the original design was the same idea with a table under it.

### The shape persistence takes when it arrives

Deferred, not cancelled. Kept so the fold's component list is not later mistaken for a schema.

```sql
campaign        (id, name, seed, created_at)
session         (id, campaign_id, started_at, ended_at, synthesis_status)
seat            (id, session_id, connection_id, controlled_entity_ids[])  -- one row today, N later
entity          (id, campaign_id, kind, name, stats_json, position_room_id, x, y, voice_id)
room            (id, campaign_id, graph_key, theme, stub_json, detail_json, generated_at)
room_edge       (from_room_id, to_room_id, kind, locked_by_item_id)
event           (id, session_id, seq, type, actor_entity_id, payload_json, created_at)
canon_fact      (id, campaign_id, subject_entity_id, text, session_id, superseded_by)
session_recap   (id, session_id, text)
audio_cache     (hash, voice_id, text, audio_bytes)
jobs            (id, kind, payload_json, state, attempts, run_after)
```

- **`voice_id` on `entity`** is what makes Marta sound like Marta three sessions later.
- **`canon_fact.superseded_by`** lets synthesis retract facts without deleting history.
- **No vector column.** Retrieval is structured: facts and entities, filtered by room and recency.
  Add `pgvector` to this same database only when a real anchorless query fails (~50 sessions in).

---

## 5. The shared contract

The original design specified one IDL — protobuf or JSON Schema — generating Java records,
TypeScript types, and LLM tool schemas from a single definition, and called it the highest-leverage
structural decision in the stack.

**It was not built, and the reason is worth more than the IDL would have been.** Its three consumers
do not want the same artefact. The wire types and the client types are static and could have been
generated. The *tool schema cannot be*, because it has to be built from live engine state: the enum
of hidden prop ids in the room the party is standing in, the entities that are still alive and can
therefore be attacked, the exits this room actually has. `ToolSchema.build` takes the engine and
emits that, every turn. A generated static schema would have had to be narrowed at call time
anyway, which is the whole job.

What the IDL was *for*, though, is exactly right, and is now invariant #7:

> **The tileset defines the enums.** If the prop catalog has 30 entries, the scene schema's prop
> enum has exactly those 30 values, and so does the model's tool definition. Structurally valid
> scenes by construction; no repair logic, no hallucinated assets.

Stated in the form the project now uses it: **free-form model text may enter the prompt; anything
reaching the engine or the renderer goes through a closed enum, validated server-side.** Tools use
`strict: true`. Invalid calls are rejected with a structured error; the model retries once, then the
turn degrades to narration-only.

`assert_fact` is the one tool carrying free-form text, and it is not a hole in the rule: a fact's
`text` makes one round trip back into the next prompt. It drives no roll, gates no legal move, and
reaches no renderer. Its `anchor` is a closed enum like everything else.

The types that cross the boundary, as they actually exist:

```
SceneState   { roomId, width, height, floorType, wallType, props[], entities[],
               lighting, mode, combat }
Diff         { EntityAdded | EntityRemoved | EntityMoved | StatChanged
             | ModeChanged | PropRevealed | CombatChanged }
PlayerAction { FreeText | MoveTo | AttackTarget | EndTurn }
Narration    { segments: [{ speakerId | NARRATOR, text }] }

Stance       { Aggressive | Defensive | Flee | Protect(id) | Focus(id) | Environmental }
             -- not built. Listed because it is a closed enum by design, and the
             -- shape it has to be when the stance layer arrives. See §8.
```

Java records are the source; the GDScript side is hand-mirrored. At this size that is cheaper than a
generator, and the websocket is the only place the two ever meet.

---

## 6. Rules engine

**The end state is a full-SRD-shaped engine with trimmed content, and that has not changed.** What
has changed is when it gets built. Everything shipped so far stands in for it — deliberately, to
keep the questions coming in the order they have to be asked — and none of it is the design.

### The engine this is heading toward

Pure, synchronous, no I/O, seeded RNG. This is the part that must be exhaustively testable.

```java
sealed interface Effect
    permits Damage, Heal, ApplyCondition, RemoveCondition, Move, Concentration, Summon {}

sealed interface Trigger
    permits OnHit, OnDamaged, OnEnterSquare, OnTurnStart, OnConcentrationCheck {}

record CombatState(List<Entity> entities, Grid grid, TurnOrder order, long rngSeed) {}
```

Three properties to build in from the start of that work, even if barely used at first:

- **A reaction/interrupt hook in the turn loop.** Opportunity attacks first. Counterspell, Shield
  and Hellish Rebuke all need the same interrupt point, and it is the one piece of structure that
  is genuinely painful to add late — see the cost below.
- **Concentration as a first-class effect**, with a holder and break conditions.
- **Conditions as effects**, never booleans on a character.

Content trim: Fighter, Rogue, Cleric, Wizard; levels 1–5; ~30 monsters CR 0–3; only the spells those
classes get. Roughly 10% of SRD volume, 100% of a playable game. The trim is a content decision and
says nothing about the engine's shape — a trimmed-content SRD engine is still an SRD engine.

Content-as-data applies here the same way it already does to rooms and creatures: spells, monsters
and effects are declarative content files evaluated by a small interpreter. Testable in isolation,
and modding for free.

### What stands in for it today, and why

About forty lines in `CombatEngine.resolveAttack`: `d20 + bonus >= AC`, natural 20 doubles the
damage dice, natural 1 misses. No conditions, no resistances, no crit tables, no reach, no cover, no
opportunity attacks.

That is a **proxy, not a verdict.** It exists because the questions the project has been answering —
does this feel like a DM running a game, can the world be made rather than authored, can a fault
found in play be turned into a test — are all answerable with one attack and one enemy, and none of
them gets clearer with an effect interpreter underneath. Forward progress on those questions was
worth more than completeness on this one.

`AGENTS.md` carries "do not build a rules engine" as an anti-goal, and it means it — but it is a
**milestone-scoped instruction, not a design position.** Read it as "not yet, and not while you are
in the middle of something else," not as "never." The design is this section.

### What the proxy is costing, stated plainly

The original design's argument for building the interrupt point early was that retrofitting one is a
rewrite. That argument is correct, it has not been refuted, and deferring it was a decision to pay
that cost later rather than a decision that the cost is not real. Two places where the bill will
come due, both worth knowing before the work starts:

- **`CombatEngine.attack` resolves and applies in one call.** There is no point between "an attack is
  declared" and "the attack resolves" for anything to fire in. That gap *is* the interrupt point, and
  opening it is the first task of the real engine, not a detail of it.
- **`Event.AttackResolved` is one coarse event** carrying the to-hit roll, the damage roll, the
  damage applied, and whether the target died. That coarseness is deliberate and well-reasoned —
  split into separate damage and death events, the beat handed to the narrator loses its attacker,
  and `m0-evaluation.md` §4.4 records what that cost the first time. But a reaction fires *between*
  the parts of that event, so the interrupt work and the narration's need for a whole beat are on a
  collision course. Solve it by keeping the coarse event as a *projection* over finer ones rather
  than by making the narrator reassemble a swing from fragments.

### What already holds, and should keep holding

These were specified up front for testability rather than for 5e, and they were built:

- **Pure, synchronous, no I/O, injected RNG.** `CombatEngine` has no clock and no network. A
  `ScriptedDiceRoller` makes any fight assertable, which is what turns a bad turn into a test.
- **Content-as-data** for rooms, entities and kits — the same pattern the spell and effect layer
  will use, already proven on simpler content.
- **Modern Java idioms pinned in the project rules file** — records over classes, sealed hierarchies,
  pattern matching over visitor, no Spring, no `AbstractXFactory`. Agents drift toward 2011 Java hard
  on a domain model shaped like this, and the pin is what keeps it out. This matters more, not less,
  once `Effect` and `Trigger` exist: a sealed hierarchy with exhaustive pattern matching is the whole
  reason this is Java, and it is exactly what an unpinned agent will turn into a visitor.

### When it gets built

When the content it adjudicates exists — more than one enemy kind, more than one attack, a spell
list. An effect interpreter with one attack to interpret is a tax; the same interpreter with four
classes and thirty monsters is the thing that makes them cheap. §14 puts it with **Content**, and the
two are one milestone rather than two.

---

## 7. Dice

The one subsystem that touches the engine, the event log, and the renderer at once. **This section
survived contact almost entirely** — it is the part of the original design that was most nearly
right, and the reason is that it was reasoning about a specific failure rather than about
completeness.

### Domain model, not a notation parser

The Java dice-notation libraries that exist parse `2d6+3` into a number. That is not the hard part.
In 5e you never roll `1d20+5` — you make an *attack roll with advantage against AC 15*. Model the
intent, add notation parsing later if you ever want a `/roll` command.

```java
record RollRequest(
    DiceExpr dice,                 // 1d20, 2d6, 8d6
    List<Modifier> modifiers,
    Advantage advantage,           // NORMAL | ADVANTAGE | DISADVANTAGE
    RollPurpose purpose,           // ATTACK | SAVE | SKILL_CHECK | DAMAGE | INITIATIVE
    EntityId actor,
    Optional<EntityId> target,
    Optional<Integer> dc,
    Optional<Skill> skill
) {}

record RollResult(
    RollRequest request,
    List<Integer> faces,           // individual dice, NOT just the total
    int total,
    Outcome outcome                // CRIT | HIT | MISS | SUCCESS | FAILURE | CRIT_FAIL
) {}
```

**`faces` is non-negotiable.** Three things need per-die values and all three are painful to
retrofit: the UI animates each die onto its own value; crits key off the natural d20 rather than the
total; and per-die rerolls operate on individual results. (Invariant #5.)

`Optional<Skill> skill` was added during M0 for a fourth reason the original design missed: without
it the event log records that *something* was tested but not what, and the dice tray can only say
"SKILL CHECK 22 vs DC 15".

### RNG

```java
interface DiceRoller { RollResult roll(RollRequest req); }
```

Injected into the engine — never static, never global. Production wraps
`RandomGenerator.of("L64X128MixRandom")`. Tests inject a scripted roller returning a fixed sequence,
which is how combat stays assertable.

**Persist roll results, not seeds.** Tempting to store a campaign seed and replay deterministically —
don't. The moment the engine changes the *order* in which it consumes randomness (a bug fix, a new
check, reordered evaluation), every historical roll shifts and the campaign desyncs. Every roll
writes an event carrying its faces and outcome. **Replay reads results; it never re-rolls.** Seeds
are for tests and for room generation, where the code is fixed.

This one paid off directly: `ReplayDiceRoller` reads faces back out of the log, which is what lets a
recorded session replay in the test suite with no network and no API key.

### DCs are an enum

Trivial 5, Easy 10, Medium 15, Hard 20, Very Hard 25. The DM picks a band, never a number. Same
constrain-the-model pattern as the prop enum — free-form DCs mean the same task is DC 17 in one room
and DC 13 an hour later, and difficulty stops meaning anything.

### Renderer — no physics library

The original design surveyed three dice libraries against the criterion that physics must **display**
a predetermined outcome rather than produce one, and picked `@3d-dice/dice-box-threejs`.

**The right answer turned out to be none of them.** If the server has already decided the result,
there is nothing for a solver to solve, and a physics engine's entire value is the part being thrown
away. `godot/dice/tumble.gd` is a pure function of time: tumbling faces are noise, and the instant a
die settles it shows `result.faces[i]`. It has no dependency, and it cannot desync from the server,
because it was never computing anything.

The survey's criterion was correct — it just eliminated more than it looked like it did. Keep the
criterion. It is the first thing to check before adopting any renderer for anything the server has
already decided.

### Pacing and feel

- **Do not animate every roll.** Six monsters attacking twice is twelve rolls; at ~2s each that is
  24 seconds of watching dice per round, and combat dies. `isDramatic()` keys off *purpose*, not off
  who rolled: attacks, saves and skill checks throw; damage and initiative go straight to the log.
  An incoming attack is the tensest die in the game and the goblin throws it, so "animate the
  player's rolls" would gate out exactly the wrong ones.
- **Audio carries most of the satisfaction.** Good clatter beats silent physics-perfect d20s. The
  cheapest available win in the whole renderer, and measured to be exactly that.
- **The dice are what make prose latency tolerable.** Something with weight happens at ~1s, and the
  narration lands while the player is still watching it. Narration is held until the dice land, and
  so are the diffs — a hit point bar that empties while the attack die is still in the air has
  answered the question the die was asking.
- **Honest dice.** No fudging, no bounded distributions. Streaky d20s are the game.

---

## 8. Turn loops

### Exploration — three phases, two models

The original design described one model call emitting narration and tool calls together. It is three
passes, and the split is the most consequential change to this section.

1. Player submits free text.
2. **Mechanics pass** (`DM_MODEL_TOOLS`). Called with room detail, party state, established facts,
   and a bounded transcript window. Emits tool calls — `roll_check`, `reveal_prop`, `spawn_entity`,
   `start_combat`. Any prose it writes is discarded.
3. Engine validates and applies each call. Dice go on the table here, at ~1s.
4. **Prose pass** (`DM_MODEL_PROSE`), streaming, *while the dice are still animating*. It writes the
   narration with the engine's real results in hand, so it cannot contradict them.
5. **Reconcile pass** (`DM_MODEL_TOOLS`). Reads what the narrator just said and makes the world match
   it: `reveal_prop`, `spawn_entity`, `start_combat`, `assert_fact`. No dice in this phase — the
   outcome has already been narrated, and a die thrown now can only contradict it.
6. Narration segments stream out, each dispatched to its voice, played strictly in order.

**Why a reconcile pass exists at all.** The failure this architecture is most vulnerable to is
*silent*: a model that narrates a goblin beautifully and forgets to call `spawn_entity` produces a
creature that is vividly described and never appears, and it looks like a rendering bug. Reconcile is
the pass that catches the board up to the narrator, and it exists because that failure was observed
rather than predicted.

**Which pass a tool belongs to has a rule.** A tool belongs in reconcile when a false positive is
cheap to live with and the narrator is the one holding the information. `start_combat` is in
reconcile and it is the uncomfortable case — a spoken aside once spawned a goblin and started a fight
nobody asked for. Anything whose false positive is expensive stays in mechanics, where the player's
own words gate it.

### Combat

1. Engine rolls initiative, emits the full `SceneState`, client transitions to combat mode.
2. **Player turns:** UI-driven. The server ships the *legal sets* — `legalMoves`, `legalTargets` —
   already decided; the client asks whether a clicked square is in a list that arrived over the wire,
   and that is the entire client-side movement rule. No model call.
3. **Enemy turns:** one model call assigns a `Stance` per monster; the tactical AI then resolves
   *all* enemy actions deterministically and instantly. **Today only the second half exists** —
   `GoblinAi` picks its move with no model in the path. See below.
4. **One narration call per enemy turn**, move and swing together. Measured 708–782ms to first token,
   1.3–1.6s total. The animation is the cover, exactly as the dice cover an exploration turn.
5. The player's own swings are narrated only on a kill, a crit or a fumble — decided structurally
   from the roll outcome and the diffs, never by reading the beat text.

### The stance layer, and why it is worth the model call

The split — **the model picks intent, the engine executes it** — is the same governing principle as
everywhere else in this system, applied to monsters. It buys three things a fully deterministic AI
cannot: monsters that read the *fiction* rather than the board (a wounded cultist flees, a bodyguard
interposes because of who it is guarding, not because of a hit point threshold), a fight that can
change character mid-combat when the situation does, and a place to put behaviour that would
otherwise be a growing pile of hand-tuned heuristics.

It stays cheap because `Stance` is a closed enum and the output is a handful of tokens — one call
per round for the whole enemy side, never one per monster, and never per action. The model never
picks a square, a target's position, or a roll. Spatial reasoning stays with the engine, which is
where the original design put it and why it put it there.

**Not built, and not missed yet, for a reason that expires.** There is one enemy kind and it has one
thing it can do, so there is no intent to pick — a stance call today would be a model round trip to
choose between aggressive and aggressive. It becomes worth building the moment a second monster kind
exists and a fight can be coordinated or not. `GoblinAi` is the execution half and is not throwaway:
the stance layer sits *above* it and constrains what it does, rather than replacing it.

**The engine states facts; the model writes prose.** `CombatSink.beat` carries plain sentences —
"Vessk hits Roderick for 7 damage" — and never a word of description. A wound is described, not
counted: the beat says "badly hurt", never "3 of 7", because handing the model a fraction is an
invitation to read it aloud.

The mode transition is dramatic — camera pull, initiative animation, UI shift. That is the battle map
coming out. It's a feature.

---

## 9. Campaign layer

**Unbuilt, and unamended.** Nothing in this section has been contradicted by play, because nothing in
it has been attempted. It is the design as written on day one, and it is where the project goes after
the dungeon is a place rather than a room.

Three tiers of authorship. The governing property is that the top tier is **living state, not a
plan** — it gets revised as play diverges from it.

### Tier 1 — Campaign frame (player-authored)

Session-zero intake: tone, setting, premise, why the party is together, what they're running from.
Either a short form or a conversational exchange with the DM. This is the part players care most
about and the cheapest thing in the system to build. **Do not generate it.**

### Tier 2 — Arc skeleton (LLM-generated, once per arc)

**Generate arcs, not campaigns.** Three to five sessions, one antagonist, one region. On resolution,
generate the next arc informed by what actually happened. Campaigns are chained arcs.

This is better narratively (it is how real campaigns run) and better technically: bounded generation,
no tokens spent on content the party skips, and a natural adaptation point every few sessions.

An arc skeleton contains: an antagonist, a region, 5–8 quest nodes with dependencies, 2–3 factions,
and the shape of an ending. Deliberately abstract — structure and hooks, no prose.

### Tier 3 — Rooms and encounters

Generated per §8 and `specs/2026-08-20-m1-procedural-generation-design.md`.

### The quest graph

The one genuinely dependency-shaped structure in this system: nodes with computed readiness.
Implement the pattern, not the tool.

```sql
arc            (id, campaign_id, seq, antagonist_entity_id, region, state, resolved_at)
quest_node     (id, arc_id, kind, title, stub_json, state, invalidated_reason)
quest_edge     (from_node_id, to_node_id, kind)   -- blocks | reveals | alternative
backstory_hook (id, entity_id, quest_node_id, consumed_at)
```

`ready_nodes(arc_id)` is a recursive CTE over unsatisfied blockers. **Readiness is derived, never
stored.** The DM prompt receives a computed "available threads" block every turn rather than
inferring quest state from prose — which is where naive versions of this get it wrong. It is the
same shape as the engine-owned world-state projection that already works; see §11.

### Adaptation — the part that actually matters

The party will murder the questgiver, ally with the villain, or ignore the plot for six sessions to
run a tavern. If the skeleton is immutable, the game starts fighting the player and they feel it
instantly.

**The synthesis worker owns quest graph revision.** At session end it already has full session
context at a narrative boundary — exactly when a human DM sits down and re-plans. It reconciles the
graph against what happened: invalidates nodes, adds nodes, rewires edges, updates the antagonist's
plan.

Two cheap player-input mechanisms, both high value:

- **Between-session prompt:** "What does your character want right now?" One line, feeds directly
  into skeleton revision. The cheapest possible thing that makes a campaign feel personalized.
- **Backstory hooks as unblocked nodes.** Two per pregen, authored up front, no prerequisites. The DM
  activates them opportunistically and it reads as foreshadowing.

**The antagonist is a persistent entity with mutable state, not a description.** They react across
arcs; foiled plans change their behavior. One table, and it is most of what separates "campaign" from
"sequence of dungeons."

### Encounter design

Combat is the part of this game that has to be *good*, so encounter generation gets more structure
than room generation. A room with monsters standing in it is a slugfest; the interesting part of
tactical 5e is terrain, objectives, and composition.

- **Terrain features are first-class content**, not decoration: cover, elevation, difficult terrain,
  hazards, chokepoints, destructibles. The scene schema carries them; the AI reads them. Terrain
  already belongs to the room rather than to combat, which is the seam this needs.
- **Encounter templates** as content files — *ambush*, *defend the objective*, *fighting retreat*,
  *reinforcement waves*, *environmental clock*. The LLM picks a template and populates it; it does
  not invent encounter structure from scratch.
- **Composition rules over CR math**: a controller, a bruiser, and ranged pressure produce a better
  fight than four goblins. Encode as generation constraints.
- **Non-slugfest objectives** on ~1/3 of encounters — survive N rounds, reach the lever, protect an
  NPC. The cheapest available lever against tactical monotony.

---

## 10. LLM roles

| Role | When | Model class | On critical path? |
|---|---|---|---|
| **Mechanics** | Every player turn, first | Fast, non-reasoning, reliably tool-calling | **Yes** — it is what puts dice on the table |
| **Narrator / DM** | Every player turn, concurrent with the dice | Best writer available | Yes. The product. |
| **Reconcile** | Every player turn, after narration | Same as mechanics | No — it corrects state, never retracts displayed text |
| **Room dresser** | Once per generated room, at build time | Mid-tier | No |
| **Arc planner** | Once per arc — *not built* | Best available | No (pre-play) |
| **Stance assigner** | Once per combat round — *not built* | Small/fast | Yes, but a handful of tokens |
| **Encounter builder** | Per encounter — *not built* | Mid-tier | No |
| **Synthesis worker** | Session end — *not built* | Big context, batch | No. Also owns quest graph revision. |

Keep exactly **one** narrator voice-of-the-game. Fragmenting *narration* across models makes tone
drift audible immediately. Splitting *mechanics* off does not — that was the discovery.

### What the split bought, measured

`--demo`, "heave the sarcophagus lid open", end to end:

| Config | First dice | First word | Total |
|---|---|---|---|
| Single model | — | — | 44,000ms |
| Split, degraded tools model | 33,908ms | 45,616ms | 48,323ms |
| Split, healthy tools model | **1,025ms** | 7,394ms | 8,860ms |

Two models that were disqualified outright became usable. A model that writes beautifully but cannot
call tools goes in the prose slot. A model that calls tools reliably but hallucinates props goes in
the mechanics slot, where it never writes narration and therefore cannot invent anything.

### The failure mode this design is most exposed to

**Tool-calling reliability, not prose quality and not price** — and its failure mode is silent. A
described goblin that never spawns looks like a rendering bug. When something in the world does not
match the narration, suspect a dropped tool call before you suspect the renderer.

Measured findings that should outlive the current model picks:

- **Reasoning models cannot hit the first-feedback budget.** Reasoning-on lands at 1.9–5.0s to first
  token, reasoning-off at 0.7–1.6s. The split is clean, and the agentic loop multiplies it.
- **Uncensored and roleplay models write the best prose and never call a tool** — the exact silent
  failure this design is most vulnerable to. They are only safe in the prose slot.
- **Bimodal latency is worse than consistently slow**, because you cannot design around it. One model
  went 563ms → 42s → 66s → 621ms → 34s with full quota remaining and no error raised.
- **A single-shot benchmark against a multi-tenant provider measures a moment, not a steady state.**
  Every early figure collected in one burst had its rankings overturned by a second burst.

The full measurement tables and the current picks live in `AGENTS.md`, which is where they belong.
They change faster than this document should.

---

## 11. Memory

Two separate jobs that are easy to conflate. The original design named both; only the first exists.

**Recency — the transcript window.** `WINDOW_TURNS = 6` of verbatim transcript. Bounded, cheap, and
deliberately forgetful.

**Permanence — the engine-owned projection.** Everything the world knows that must not be forgotten is
rendered fresh into the prompt every turn from `WorldState`: the room, the party, what is revealed,
what is established, what is dead. **It never truncates**, because it is not a summary of the
conversation — it is a statement of the board.

That division is the correction to the original design's "rolling summary". A summary that gets
compacted will eventually compact away the fact the player cares about; a projection cannot, because
it is regenerated from state rather than accumulated from text.

**Established facts** (`FactAsserted` → `WorldState.facts`) are how the world holds what has no
mechanism behind it. `assert_fact` is the tool; the fact makes one round trip back into the next
prompt and touches nothing else.

**Never assert an absence.** A fact is something that *is* true in a place you can see. "There is
nothing here" is a claim about a room you are not looking at, and it is the one kind of assertion a
torch or a better roll can prove wrong later. This matters most after a failed check, which is
exactly when the narrator reaches for it.

**Canon synthesis is not built.** Triggered by End Session (primary), idle (fallback), or long rest
(light pass); reads the log, writes canon facts and a recap, resolves contradictions by setting
`superseded_by`, and revises the quest graph. Big context, expensive model, fully decoupled. It
arrives with the campaign layer.

**Note, and it held:** most continuity errors are prevented architecturally, not by a checker. The DM
cannot contradict the board if the board is in front of it every turn. Get the tool boundary right
and a checker only has prose-level lore drift left to handle.

---

## 12. Frontend

```
websocket ──► Table (autoload) ──► Chrome (Control nodes)
                                └─► World (Node3D, one scene, created once)
                                └─► Clock (ordered speech queue)
```

**Game state never lives in a UI node.** Both chrome and world subscribe to `Table`. Violating this
rebuilds the world on a chrome redraw, which is very hard to diagnose. This is invariant #3, and it is
the same boundary the React design was most careful about — it just costs less to hold here.

Renderer: orthographic isometric camera, four fixed corners with 90° snap, native resolution, linear
filtering, real lights, glTF kits. Never free orbit — it breaks the isometric read and grid picking.
Do not downsample toward a pixel look; that fight was lost in play against KayKit.

Audio: narration goes through an ordered queue, one line at a time, strictly in arrival order, because
two sentences talking over each other is the worst thing a spoken DM can do.

**The voice paces the transcript.** The model streams a turn in about three seconds and the voice
takes twenty to say it, so text appended on arrival has the player speed-reading ahead of the
narrator. Each line is committed to the transcript as it starts being spoken. No words-per-minute
constant to tune, and it stays correct if the voice changes.

**Time-sink warning:** 3D has many knobs and it is very easy to lose three weeks tuning lights and
post-processing instead of shipping. Timebox the look. Expect more manual visual iteration than
elsewhere — agents can't see what they made.

---

## 13. Content pipeline

**Do this before writing code:** pick the asset kit, then design the scene schema around exactly what
that kit can express.

This inverts the usual order deliberately. Art direction is the real bottleneck; agents will write
your renderer but they will not make your game look good. Locking the kit first means your prop enum,
tile types, and room dimensions are all constrained by something real from day one.

**Settled:** Kenney CC0 for M0, KayKit for the Godot table — Adventurers Knight, Skeletons Warrior,
Dungeon kit. The advice was followed and it worked; the prop enum is the kit's contents, which is why
the model cannot ask for an asset that does not exist.

One thing the advice did not anticipate: **each kit needs its own folder.** Kit GLBs reference
textures by relative path, and mixing two kits in one directory silently paints one of them with the
other's atlas.

SRD 5.1 content: transcribe from open JSON dumps into your content format. CC-BY-4.0 — attribute it,
keep D&D branding out.

---

## 14. Milestones

**The original ordering below was superseded twice, both times for the same reason**, and the reason
is worth stating once because it will come up again: *build the thing that answers a question, in the
order the questions have to be asked.* M1 took world generation before the rules engine because "can
the world be made rather than authored" was a live question and "does concentration work" was not. M2
took the event-log spine before the rest of M1 because a fault found in play was not yet something you
could turn into a test, and every milestone after it would have paid that cost.

### As actually built

| | Gate question | Verdict | Where |
|---|---|---|---|
| **M0** | Does this feel like a DM running a game? | PASS 2026-08-20 | `docs/m0-evaluation.md` |
| **M1** | Can the world be made rather than authored? | **Half done** — room generation merged; the dungeon is outstanding | `specs/2026-08-20-m1-procedural-generation-design.md` |
| **M2** | Can a fault found in play be turned into a test? | PASS 2026-09-05 | `docs/m2-evaluation.md` |
| **M3** | Is a room a place you can leave and come back to? | Planned 2026-09-05 | `specs/2026-09-05-m3-traversal-design.md` |

The Godot migration sits between M1 and M2, gated on parity rather than on a question of its own
(`specs/2026-08-22-chrome-direction-design.md`).

M0 was **a gate, not a foundation.** Everything contributing to *feel* was in scope; everything
contributing to *correctness, scale, or persistence* was hardcoded on purpose, and much of that code
has since been thrown away as intended. M2 is the foundation M0 deliberately did not build.

### Still ahead

**Dungeon generation.** `LayoutGenerator`, `ExitPlacer`, spatial validation, and world-space packing
so a dungeon's rooms have non-overlapping rectangles to render. Answers M1's outstanding half.

**Content, the rules engine, and the stance layer, together.** More than one enemy, more than one
attack, more than one party member — the SRD-shaped engine of §6 that adjudicates them, and the
stance call of §8 that gives monsters something to decide. One milestone, not three, because each is
a tax on its own and they only pay off in each other's company: an effect interpreter with a single
attack to interpret, or a stance call choosing between aggressive and aggressive, is overhead. With
four classes and thirty monsters, both are what make the content cheap.

**Persistence and resume.** Sessions are written today and nothing resumes from one; resume is the
expensive half. Snapshotting and schema migration arrive with it, or the log-refusal rule in §4 stops
being free.

**Campaign layer.** §9, entire: session-zero intake, arc skeletons, the quest graph with derived
readiness, backstory hooks, the synthesis worker, the persistent antagonist.

**Encounter depth.** §9's encounter design: terrain features in the scene schema, encounter templates,
composition rules, objective-based encounters. This is the one that makes the part you actually care
about good.

### The original plan, kept as a record of intent

M0 vertical slice → M1 rules engine → M2 tactical combat → M3 combat narration → M4 dungeon generation
→ M5 persistence and memory → M6 content → M7 campaign layer → M8 encounter depth.

Its note read: *"M1–M2 have no AI in them at all. That's intentional — the hardest, most bug-prone,
most testable part of this system is completely deterministic, and you should build it that way."*

The instinct was right and the sequencing was wrong. The deterministic parts that got built early —
dice, the fold, replay, `GoblinAi` — are exactly the ones that have never needed rewriting. The
deterministic part that was *deferred*, the effect interpreter, is still coming and is still the
design; what changed is that it now arrives alongside the content it adjudicates rather than years
ahead of it. That is the difference between "build the testable part first" and "build it before
anything needs it".

---

## 15. Deferred, with re-entry conditions

| Deferred | Revisit when |
|---|---|
| Postgres | Something needs a query rather than a replay |
| Resume from a session log | Sessions get long enough that losing one hurts |
| Schema migration | Resume exists. Until then, refusing an old log is free |
| The SRD engine of §6 (effects, conditions, reactions) | The content it adjudicates exists — more than one enemy, more than one attack, a spell list |
| The stance layer above `GoblinAi` (§8) | A second monster kind exists, so there is a choice to make and a fight can read as coordinated |
| An IDL | Two clients, or a second consumer of the wire types |
| Multiplayer | The solo game is fun and someone asks to play |
| Grid-walkable exploration | Combat is polished and you want interactable props |
| Vector search | ~50 sessions in, and an anchorless query actually fails |
| Off-screen world simulation | Probably never. This is where projects in this genre die. |
| Local models | Sharing it with others makes per-user cost a constraint |
| Levels 6+ | Tier 1 play is fully solid |
| Runtime asset generation | Never |
| Auth, hosting, payments | It stops being a toy |

---

## 16. Open risks

Reordered by what play actually showed. The first one was not on the original list and belongs at the
top of it.

1. **Tool-calling reliability is the load-bearing dependency**, not prose quality and not price, and
   its failure mode is silent. A described goblin that never spawns looks like a rendering bug.
   Mitigated structurally by the reconcile pass and by closed enums; never eliminated.
2. **The narrator can describe a world change nothing can back.** A player asked for the goblin to
   become an immense steaming hotdog and the narrator obliged. `assert_fact` now makes the DM
   *consistent* about its own hotdog; it cannot make the board agree. The M2 gate produced the same
   shape with a straight face — a copper key, asserted and durable, turning a lock in a door with no
   mechanism behind it. **Invented content is now durable, which is the spine working and a content
   problem.**
3. **3D polish is a time sink.** Timebox it or it eats the project. Unchanged, and still true.
4. **Java agent drift toward legacy idioms.** Mitigated by project rules, not eliminated.
5. **The gap between "works" and "feels alive"** is mostly animation and audio timing, and it is wider
   than it looks. M0 existed to measure it early, and it was right to.
6. **Scope creep via the rules engine.** 5e's long tail is infinite. The content trim list — 4
   classes, levels 1–5, ~30 monsters — is a commitment, not a suggestion. The risk runs the other
   way too: the ~40-line proxy is easy to mistake for the design and leave in place, and §6 exists
   so that mistake has something to be corrected against.
7. **Mushy generated content.** The real failure mode of this genre: everything medium, no stakes,
   nothing lands. Defended against by structure — quest graph, persistent antagonist, encounter
   templates — not by better prompts.
8. **The BG3 comparison.** BG3's budget was authored content: dialogue, VO, cinematics, hand-built
   levels. This project inverts that ratio deliberately — systems heavy, authored content near zero.
   Every BG3 feature you find yourself wanting is authored content, which is the one thing this
   project cannot afford. **Not BG3.**
9. **Tactical monotony.** Combat is the reason this project exists. If encounters are four goblins in
   an empty room, the whole thing fails at the part that matters most.

### The risk that was not on the list

**Latency was budgeted for and turned out not to be the problem.** Two played sessions produced not
one complaint about a wait; every complaint was the DM contradicting a world it had already
established. The original design's targets came from a text-chat mental model, where the first token
*is* the experience. This game is not that — there are dice on the table and a voice talking, and
against a slow natural voice a pause reads as the DM thinking.

The numbers are kept so a regression stays visible, not as a gate. **The gate is multi-turn
consistency.** Everything M2 built follows from that sentence.
