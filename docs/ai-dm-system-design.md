# AI Dungeon Master — Initial System Design

**Status:** Design locked, pre-implementation. **Look amended 2026-08-23:** stylized isometric 3D at native resolution — see Renderer below and `docs/superpowers/specs/2026-08-21-godot-client-design.md`. The 480px nearest-neighbour target in this document is superseded. The client that draws it is Godot, not Three.js; 3D kit content (not 2D sprites) remains the variety strategy.
**Scope:** Personal single-player toy, architected so multiplayer is a feature and not a rewrite

---

## 1. What this is

A solo-playable D&D-style RPG where an LLM acts as Dungeon Master. The player controls a party of
four pregenerated characters. Exploration is free-text and narrative; combat is tactical, grid-based,
and adjudicated by a deterministic rules engine. The world renders as a stylized isometric 3D scene
(native resolution, cartoon-kit meshes — miniatures on a board, not a fake-pixel downsample and not
hand-drawn 2D isometric sprites).
Narration is spoken via TTS, with a persistent narrator voice and distinct voices for recurring NPCs.

**What it is not:** not a VTT, not a product, not multiplayer (yet), not a chat interface with pictures.
The tactical grid is the reason the 3D renderer exists. If the grid ever gets cut, cut the renderer too.

**Governing principle:** *The LLM proposes, the engine adjudicates.* The model never rolls dice, never
does arithmetic, never decides whether an attack hits, never moves an entity, and never invents an
asset. Every mechanical effect goes through a validated tool call against server-authoritative state.

---

## 2. Locked decisions

| Area | Decision | Rationale |
|---|---|---|
| Backend language | **Java 21+** | Sealed interfaces + records + exhaustive pattern matching are the right fit for a rules engine's tagged unions. Virtual threads make the streaming orchestration layer read as blocking code. |
| Rules engine style | **Content-as-data** | Spells, monsters, and effects are declarative content files evaluated by a small interpreter. Testable in isolation; modding for free. |
| Database | **Postgres** | One store. No queue, no cache layer, no vector DB. |
| Job runner | **Postgres `jobs` table + worker** | One async job exists (synthesis). River if it needs to be proper. |
| Transport | **Single websocket** | Server pushes state diffs; client sends actions. HTTP only for assets and campaign list. |
| Frontend chrome | **React + Zustand** | Training-data density buys agentic coding quality on the least familiar layer. |
| Renderer | **Orthographic isometric 3D at native resolution** (Godot client; originally Three.js). KayKit-class meshes, linear filtering. No low-res nearest-neighbour pass | Z-buffer solves depth sorting. 3D kit content scales far better than sprites for an infinite-variety game — the reason this is not 2D isometric pixel art |
| Schema source of truth | **One IDL (protobuf or JSON Schema)** generating Go-free Java records, TS types, and LLM tool schemas | Single definition, three consumers. Highest-leverage structural decision in the stack. |
| Ruleset | **Full-SRD-shaped engine, trimmed content** | 4 classes, levels 1–5, ~30 monsters CR 0–3. Engine keeps reaction hooks and concentration from day one. |
| Combat | **Full tactical grid** | Movement, range, cover, AoE, opportunity attacks. |
| Exploration | **Narrative, grid rendered but not walkable** | Avoids the interactable-object content pipeline entirely. Upgrade path stays open. |
| Monster tactics | **Deterministic heuristic AI, LLM sets stance only** | Latency, testability, tunability, no spatial hallucination. |
| Dungeon generation | **Hybrid: procedural layout → LLM stub pass → JIT room detail with prefetch** | Global coherence up front, zero perceived latency at the door. |
| Model hosting | **Hosted API, behind an interface** | Simplest for solo. Swap to local is config, not code. |
| Character creation | **6 pregens from content JSON** | Same schema a builder would later write to. |
| Voice | **Persistent narrator + per-NPC voices, voice ID stored as canon** | NPCs must sound the same across sessions. |
| Session boundary | **Explicit "End Session" button, idle timeout fallback, long rest as light trigger** | Lands on narrative boundaries; deterministic and re-runnable while tuning prompts. |
| Dice | **Hand-rolled domain model, injected `DiceRoller`, results logged as events** | Notation parsing is the least interesting part; per-die faces and replay-from-log are the requirements. |
| Dice renderer | **`@3d-dice/dice-box-threejs` (predetermined outcomes)** | Server is authoritative; physics displays a decided result rather than producing one. |
| Deployment | **`localhost` + Postgres container** | No auth, no multi-tenancy, no hosting. |

---

## 3. Architecture

```
┌─────────────────────────── Browser ───────────────────────────┐
│  React chrome (sheets, dice log, transcript, inventory)       │
│  Zustand store  ◄── websocket ──►                             │
│  Three.js canvas (useRef, never re-created by render)         │
│  Audio queue (ordered segment playback)                       │
└───────────────────────────────┬───────────────────────────────┘
                                │ ws: {actor_id, action} ↑ / state diffs ↓
┌───────────────────────────────┴───────────────────────────────┐
│                      Java backend (one binary)                │
│                                                               │
│  Session Orchestrator ── virtual thread per session           │
│      ├── Turn Engine (authoritative; interrupt hooks)         │
│      ├── Rules Engine (pure, sync, no I/O, seeded RNG)        │
│      ├── Tactical AI (deterministic; executes within stance)  │
│      ├── Scene Composer (procedural layout + prop placement)  │
│      ├── LLM Client (narrator, stance, room detail) ─┐        │
│      ├── TTS Client (segment → voice → audio) ───────┤        │
│      └── Prefetch Worker (speculative room gen) ─────┘        │
│                                                               │
│  Synthesis Worker (separate; polls jobs table)                │
└───────────────────────────────┬───────────────────────────────┘
                                │
                          ┌─────┴─────┐
                          │ Postgres  │
                          └───────────┘
```

**Content files (git-versioned, loaded at boot, not in the DB):** tileset manifest, monster stats,
spells, class definitions, pregen characters, prop catalog.

---

## 4. Data model

```sql
campaign        (id, name, seed, created_at)
session         (id, campaign_id, started_at, ended_at, synthesis_status)

-- Multiplayer insurance: one row today, N rows later.
seat            (id, session_id, connection_id, controlled_entity_ids[])

entity          (id, campaign_id, kind, name, stats_json, position_room_id, x, y, voice_id)
room            (id, campaign_id, graph_key, theme, stub_json, detail_json, generated_at)
room_edge       (from_room_id, to_room_id, kind, locked_by_item_id)

event           (id, session_id, seq, type, actor_entity_id, payload_json, created_at)
                -- append-only. replay, undo, debugging, and synthesis input.
                -- roll events carry faces[] + outcome; replay reads them, never re-rolls (see §7).

canon_fact      (id, campaign_id, subject_entity_id, text, session_id, superseded_by)
session_recap   (id, session_id, text)

audio_cache     (hash, voice_id, text, audio_bytes)
jobs            (id, kind, payload_json, state, attempts, run_after)
```

Notes:

- **`event` is the spine.** Never mutate it. World state is a projection you may rebuild from it.
- **`voice_id` on `entity`** is what makes Marta sound like Marta three sessions later.
- **`canon_fact.superseded_by`** lets synthesis retract facts without deleting history.
- **No vector column yet.** Retrieval is structured: events tagged by entity, filtered by subject.
  Add `pgvector` to this same database only when a real anchorless query shows up (~50 sessions in).

---

## 5. The shared contract

One IDL defines everything that crosses a boundary. Generated into Java records, TypeScript types,
and LLM tool schemas.

Critically: **the tileset defines the enums.** If the prop catalog has 30 entries, the scene schema's
prop enum has exactly those 30 values, and so does the model's tool definition. Structurally valid
scenes by construction; no repair logic, no hallucinated assets.

```
SceneState   { room_id, dims, floor_type, wall_type, props[], entities[], lighting }
Diff         { added[], removed[], moved[], stat_changes[] }
PlayerAction { actor_id, kind: FreeText | Move | Attack | CastSpell | UseItem | EndTurn, ... }
Narration    { segments: [{ speaker_entity_id | NARRATOR, text }] }
Stance       { Aggressive | Defensive | Flee | Protect(id) | Focus(id) | Environmental }
```

---

## 6. Rules engine (Java)

Pure, synchronous, no I/O, seeded RNG. This is the part that must be exhaustively testable.

```java
sealed interface Effect
    permits Damage, Heal, ApplyCondition, RemoveCondition, Move, Concentration, Summon {}

sealed interface Trigger
    permits OnHit, OnDamaged, OnEnterSquare, OnTurnStart, OnConcentrationCheck {}

record CombatState(List<Entity> entities, Grid grid, TurnOrder order, long rngSeed) {}
```

Build with these from day one even if barely used:

- **Reaction/interrupt hook in the turn loop.** v1 implements opportunity attacks only. Retrofitting
  an interrupt point later is a rewrite; Counterspell, Shield, and Hellish Rebuke all need it.
- **Concentration as a first-class effect** with a holder and break conditions.
- **Conditions as effects**, never booleans on a character.

Content trim for v1: Fighter, Rogue, Cleric, Wizard; levels 1–5; ~30 monsters CR 0–3; only the
spells those classes get. Roughly 10% of SRD volume, 100% of a playable game.

**Agent guardrail:** pin modern idioms in the project rules file — records over classes, sealed
hierarchies, pattern matching over visitor, no Spring, no `AbstractXFactory`. Agents drift toward
2011 Java hard on a domain model shaped like this.

---

## 7. Dice

The one subsystem that touches the rules engine, the event log, and the renderer at once.

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
    Optional<Integer> dc
) {}

record RollResult(
    RollRequest request,
    List<Integer> faces,           // individual dice, NOT just the total
    int total,
    Outcome outcome                // CRIT | HIT | MISS | SUCCESS | FAILURE | CRIT_FAIL
) {}
```

**`faces` is non-negotiable.** Three things need per-die values and all three are painful to retrofit:
the UI animates each die onto its own value; crits key off the natural d20 rather than the total; and
per-die rerolls (Great Weapon Fighting, Elemental Adept, Brutal Critical) operate on individual results.

### RNG

```java
interface DiceRoller { RollResult roll(RollRequest req); }
```

Injected into the rules engine — never static, never global. Production wraps
`RandomGenerator.of("L64X128MixRandom")` (Java 17+; skip legacy `java.util.Random`). Tests inject a
scripted roller returning a fixed sequence, which is how combat stays assertable.

**Persist roll results, not seeds.** Tempting to store a campaign seed and replay deterministically —
don't. The moment the engine changes the *order* in which it consumes randomness (a bug fix, a new
reaction check, reordered condition evaluation), every historical roll shifts and the campaign
desyncs. Every roll writes an `event` row carrying its faces and outcome. **Replay reads results; it
never re-rolls.** Seeds are for tests, where the code is fixed.

### DCs are an enum

Trivial 5, Easy 10, Medium 15, Hard 20, Very Hard 25. The DM picks a band, never a number. Same
constrain-the-model pattern as the tileset and stance enums — free-form DCs mean the same task is
DC 17 in one room and DC 13 an hour later, and difficulty stops meaning anything.

### Renderer

The server decides the result, so physics must **display** a predetermined outcome, not produce one.
This constraint eliminates most dice libraries and is the first thing to check before adopting any.

- `@3d-dice/dice-box` — popular, but results come *out* of the physics sim. Wrong direction. Not this one.
- `@3d-dice/dice-box-threejs` — forked specifically for predetermined rolling. Three.js + Cannon-ES,
  matching the renderer. Targets go in the notation: `Box.roll("6d6@4,4,4,4,4,4")`. Low traffic and
  slow release cadence, so expect to read the source and probably vendor it. `@drdreo/dice-box-threejs`
  is a more actively maintained fork with the same API.
- `threejs-dice` — same capability via `DiceManager.prepareValues()`, driven inside an existing scene
  rather than a second canvas.

### Pacing and feel

- **Do not animate every roll.** Six monsters attacking twice is twelve rolls; at ~2s each that's 24
  seconds of watching dice per round and combat dies. Animate player rolls, party saves, and dramatic
  beats (crits, boss attacks). Everything else resolves instantly into the dice log.
- **Audio carries most of the satisfaction.** Good clatter with 2D tumbling sprites beats silent
  physics-perfect d20s. Cheapest available win in the whole renderer.
- **Honest dice.** No fudging, no bounded distributions. Streaky d20s are the game.

---

## 8. Turn loops

### Exploration
1. Player submits free text.
2. DM model called with: room detail, party state, relevant canon facts, rolling recent-events summary.
3. Model emits narration segments and zero or more tool calls (`roll_check`, `reveal`, `move_room`,
   `start_combat`, `create_npc`).
4. Engine validates and applies each tool call; results feed back to the model if needed.
5. Narration segments stream out, each dispatched to its voice, played in order.
6. On `move_room`: cached prefetched detail is served instantly; prefetch worker warms the new frontier.

### Combat
1. Engine rolls initiative, emits full `SceneState`, client transitions to combat mode.
2. **Player turns:** UI-driven. Client requests legal moves from server; player clicks; server validates
   and applies. No model call.
3. **Enemy turns:** one model call assigns a `Stance` per monster. Tactical AI then resolves *all*
   enemy actions deterministically and instantly.
4. **One** narration call per round describing the whole enemy round as a paragraph. Not per-monster.
5. Repeat. Combat ends → engine emits results → DM narrates the aftermath → back to exploration mode.

The mode transition should be dramatic — camera pull, initiative roll animation, UI shift. That's the
battle map coming out. It's a feature.

---

## 9. Campaign layer

Three tiers of authorship. The governing property is that the top tier is **living state, not a plan** —
it gets revised as play diverges from it.

### Tier 1 — Campaign frame (player-authored)

Session-zero intake: tone, setting, premise, why the party is together, what they're running from.
Either a short form or a conversational exchange with the DM. This is the part players care most about
and the cheapest thing in the system to build. **Do not generate it.**

### Tier 2 — Arc skeleton (LLM-generated, once per arc)

**Generate arcs, not campaigns.** Three to five sessions, one antagonist, one region. On resolution,
generate the next arc informed by what actually happened. Campaigns are chained arcs.

This is better narratively (it's how real campaigns run) and better technically: bounded generation,
no tokens spent on content the party skips, and a natural adaptation point every few sessions.

An arc skeleton contains: an antagonist, a region, 5–8 quest nodes with dependencies, 2–3 factions,
and the shape of an ending. Deliberately abstract — structure and hooks, no prose.

### Tier 3 — Rooms and encounters

JIT with prefetch, per §8.

### The quest graph

The one genuinely Beads-shaped structure in this system: dependency-aware nodes with computed
readiness. Implement the pattern, not the tool.

```sql
arc            (id, campaign_id, seq, antagonist_entity_id, region, state, resolved_at)
quest_node     (id, arc_id, kind, title, stub_json, state, invalidated_reason)
quest_edge     (from_node_id, to_node_id, kind)   -- blocks | reveals | alternative
backstory_hook (id, entity_id, quest_node_id, consumed_at)
```

`ready_nodes(arc_id)` is a recursive CTE over unsatisfied blockers. **Readiness is derived, never
stored.** The DM prompt receives a computed "available threads" block every turn rather than inferring
quest state from prose — which is where naive versions of this get it wrong.

### Adaptation — the part that actually matters

The party will murder the questgiver, ally with the villain, or ignore the plot for six sessions to run
a tavern. If the skeleton is immutable, the game starts fighting the player and they feel it instantly.

**The synthesis worker owns quest graph revision.** At session end it already has full session context
at a narrative boundary — exactly when a human DM sits down and re-plans. It reconciles the graph
against what happened: invalidates nodes, adds nodes, rewires edges, updates the antagonist's plan.

Two cheap player-input mechanisms, both high value:

- **Between-session prompt:** "What does your character want right now?" One line, feeds directly into
  skeleton revision. Cheapest possible thing that makes a campaign feel personalized.
- **Backstory hooks as unblocked nodes.** Two per pregen, authored up front, no prerequisites. The DM
  activates them opportunistically and it reads as foreshadowing.

**The antagonist is a persistent entity with mutable state, not a description.** They react across arcs;
foiled plans change their behavior. One table, and it's most of what separates "campaign" from
"sequence of dungeons."

### Encounter design

Combat is the part of this game that has to be *good*, so encounter generation gets more structure than
room generation. A room with monsters standing in it is a slugfest; the interesting part of tactical 5e
is terrain, objectives, and composition.

- **Terrain features are first-class content**, not decoration: cover, elevation (±5ft with the
  attendant rules), difficult terrain, hazards, chokepoints, destructibles. The scene schema carries
  them; the tactical AI reads them.
- **Encounter templates** as content files — *ambush*, *defend the objective*, *fighting retreat*,
  *reinforcement waves*, *environmental clock*. The LLM picks a template and populates it; it does not
  invent encounter structure from scratch.
- **Composition rules over CR math**: a controller, a bruiser, and ranged pressure produce a better
  fight than four goblins. Encode as generation constraints.
- **Non-slugfest objectives** on ~1/3 of encounters — survive N rounds, reach the lever, protect an NPC.
  This is the cheapest available lever against tactical monotony.

---

## 10. LLM roles

| Role | When | Model | On critical path? |
|---|---|---|---|
| **Narrator / DM** | Every player turn | Best available | **Yes.** The product. |
| **Arc planner** | Once per arc (3-5 sessions) | Best available | No (pre-play) |
| **Dungeon planner** | Once per dungeon | Best available | No (pre-play) |
| **Encounter builder** | Per encounter, prefetched | Mid-tier | No |
| **Room detailer** | JIT, prefetched | Mid-tier | No (prefetch hides it) |
| **Stance assigner** | Once per combat round | Small/fast | Yes, but tiny output |
| **Context compactor** | On token budget | Cheap | Inline, rolling |
| **Synthesis worker** | Session end, long rest | Big context, batch | No. Also owns quest graph revision. |
| **Continuity checker** | Async, post-display | Cheap | No — corrects state, never retracts displayed text |

Keep exactly **one** narrator voice-of-the-game. Fragmenting narration across models makes tone drift
audible immediately.

Two of these (synthesis, continuity) have no latency budget and large contexts — natural candidates
for the DGX Spark later, without touching the hosted narrator.

---

## 11. Memory

Two separate jobs that are easy to conflate:

**Rolling context compaction.** Token-budget triggered, inline, cheap model. Produces a "recent
events" block for the DM prompt. Not a session concept.

**Canon synthesis.** Triggered by End Session (primary), 30-min idle (fallback), or long rest (light
pass). Reads the event log, writes `canon_fact` rows and a `session_recap`, resolves contradictions
by setting `superseded_by`, and **revises the quest graph** against what actually happened (see §9).
Big context, expensive model, fully decoupled.

Retrieval into the DM prompt is structured, not semantic: current room detail + party state + canon
facts for entities present + facts for entities mentioned in the last N turns + rolling summary +
latest recap.

**Note:** most continuity errors are prevented architecturally, not by a checker. The DM can't
contradict inventory if it must call `get_inventory()`. Get the tool boundary right and the checker
only handles prose-level lore drift.

---

## 12. Frontend

```
websocket ──► Zustand store ──► React (chrome)
                            └─► Three.js (subscribes, mutates canvas imperatively)
```

**Game state never lives in React state.** Both React and Three.js subscribe to the store. The
renderer lives in a `useRef` and is never re-created by a render. Get this boundary right on day one.

Renderer: orthographic isometric camera, native resolution, linear filtering, glTF kits. Do not
downsample toward a pixel look — that fight was lost in play against KayKit. Performance is a
non-issue at a dozen entities. The Godot client spec owns the viewport.

Audio: segments arrive tagged, are synthesized in parallel, queued, and played strictly in order.
Cache by `hash(text, voice_id)` — narration repeats more than you'd expect.

**Time-sink warning:** 3D has many knobs and it is very easy to lose three weeks tuning lights and
post-processing instead of shipping. Timebox the look. Also expect more manual visual iteration than
elsewhere — agents can't see what they made.

---

## 13. Content pipeline

**Do this before writing code:** pick the asset kit. Low-poly fantasy dungeon set — Kenney (free) or
Synty (paid). Then design the scene schema around exactly what that kit can express.

This inverts the usual order deliberately. Art direction is the real bottleneck; agents will write
your renderer but they will not make your game look good. Locking the kit first means your prop enum,
tile types, and room dimensions are all constrained by something real from day one.

SRD 5.1 content: transcribe from open JSON dumps into your content format. CC-BY-4.0 — attribute it,
keep D&D branding out.

---

## 14. Milestones

**M0 — Vertical slice (target: 2 weeks).** One hardcoded room. One goblin. Narration streams, TTS
speaks, scene renders, "I open the door" mutates state, tiles update. No campaign, no character
creation, no save. *Gate: if this loop doesn't feel magical, more code won't fix it.*

**M1 — Rules engine.** Pure Java core. Dice domain model + injected `DiceRoller`. Grid, movement,
attacks, damage, conditions, opportunity attacks. Scripted roller in tests. Heavily unit tested.
No LLM involved at all.

**M2 — Tactical combat.** Initiative, turn engine, combat-mode UI, legal-move computation, monster
heuristic AI. Playable combat with zero AI narration.

**M3 — Combat narration.** Stance assignment, one-call-per-round narration, mode transitions.

**M4 — Dungeon generation.** Procedural layout, LLM stub pass, JIT room detail, prefetch worker.

**M5 — Persistence and memory.** Event log, save/load, End Session, synthesis worker, recaps.

**M6 — Content.** 4 classes, 6 pregens, 30 monsters, spell list. The point at which it's a real game.

**M7 — Campaign layer.** Session-zero intake, arc skeleton generation, quest graph + derived readiness,
backstory hooks, synthesis-driven graph revision, persistent antagonist.

**M8 — Encounter depth.** Terrain features in the scene schema, encounter templates, composition rules,
objective-based encounters. This is the milestone that makes the part you actually care about good —
consider pulling it earlier if M3 leaves combat feeling flat.

Note that M1–M2 have no AI in them at all. That's intentional — the hardest, most bug-prone,
most testable part of this system is completely deterministic, and you should build it that way.

---

## 15. Deferred, with re-entry conditions

| Deferred | Revisit when |
|---|---|
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

1. **3D polish is a time sink.** Timebox it or it eats the project.
2. **Tactical AI tuning is open-ended.** Ship something mediocre, tune later. Seeded RNG makes this
   testable, which is the whole reason it's deterministic.
3. **Java agent drift toward legacy idioms.** Mitigated by project rules, not eliminated.
4. **The gap between "works" and "feels alive"** is mostly animation and audio timing, and it's wider
   than it looks. M0 exists to measure it early.
5. **Scope creep via the rules engine.** 5e's long tail is infinite. The trim list is a commitment,
   not a suggestion.
6. **Mushy generated content.** The real failure mode of this genre: everything medium, no stakes,
   nothing lands. Defended against by structure — quest graph, persistent antagonist, encounter
   templates — not by better prompts.
7. **The BG3 comparison.** BG3's budget was authored content: dialogue, VO, cinematics, hand-built
   levels. This project inverts that ratio deliberately — systems heavy, authored content near zero.
   Every BG3 feature you find yourself wanting (dialogue trees, romance arcs, cinematic camera) is
   authored content, which is the one thing this project cannot afford. **Not BG3.**
8. **Tactical monotony.** Combat is the reason this project exists. If encounters are four goblins in
   an empty room, the whole thing fails at the part that matters most.
