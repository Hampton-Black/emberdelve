# M0 — Vertical Slice Build Plan

**Companion to:** `../ai-dm-system-design.md` (but this document is self-contained; you do not need the
design doc to build M0)
**Target:** 2 weeks of evenings
**Status:** Ready to build

---

## 1. What M0 is for

M0 is a **gate, not a foundation.** It exists to answer one question:

> When I type a sentence and hear a voice respond while the world visibly changes in front of me,
> does it feel like a Dungeon Master is running a game for me?

That feeling is the entire product thesis. If it lands, the remaining ~12 months are execution against
a plan. If it doesn't, no amount of rules-engine correctness or procedural generation will save it, and
finding that out now costs two weeks instead of eight months.

**Therefore:** everything that contributes to *feel* is in scope. Everything that contributes to
*correctness, scale, or persistence* is out of scope and gets hardcoded. This is not technical debt —
it is the point. M0 code is expected to be substantially thrown away.

---

## 2. Acceptance script

M0 is done when this exact five-minute session works end to end, without crashing, without manual
intervention, and without the developer explaining anything to a bystander watching it.

```
1.  App loads. An isometric stone chamber renders: braziers lit, a sarcophagus,
    a door on the north wall. A single fighter token stands near the south entrance.

2.  The narrator's voice describes the room, unprompted, as the scene fades in.

3.  Player types: "I examine the sarcophagus."
    → Narration streams as text and begins speaking within ~1.5s.
    → Narrator describes carvings on the lid.

4.  Player types: "I try to push the lid open."
    → DM calls for a Strength check.
    → Dice animate. Sound plays. Result is visible in the log.
    → On success: the lid slides open and a goblin scrambles out.
    → The goblin token appears on the grid.

5.  Combat begins. Camera pulls back. Initiative rolls. UI switches to combat mode.

6.  Player clicks a square to move, clicks the goblin to attack.
    → Attack roll animates. Damage applies. Goblin HP bar updates.

7.  Goblin takes its turn automatically: moves toward the fighter, attacks.
    → One narration call describes the goblin's whole turn, spoken aloud.

8.  Player kills the goblin. Token drops. Narrator describes the aftermath.
    → UI returns to exploration mode.

9.  Player types: "I open the north door."
    → Narrator responds. Scene does NOT change (there is only one room in M0).
    → The DM handles this gracefully in fiction rather than erroring.
```

If step 9 feels like a wall rather than a moment, that is a *content* limitation, not a failure of the
loop. Judge M0 on steps 1–8.

---

## 3. Scope

### In

| Component | M0 form |
|---|---|
| Renderer | Three.js, orthographic camera, low-res target upscaled nearest-neighbor. **Superseded for the Godot client (2026-08-23):** stylized isometric 3D at native resolution — `../superpowers/specs/2026-08-21-godot-client-design.md` |
| Room | One hand-authored room, loaded from a JSON file |
| Party | **A list containing exactly one** level-1 fighter with fixed stats |
| Monster | One goblin, fixed stats |
| Combat | Initiative, move, melee attack, damage, death. That is all. |
| Monster behavior | Move toward nearest PC; attack if adjacent. ~20 lines. |
| Narration | Streaming, segmented by speaker |
| TTS | Two voices (narrator + goblin), streamed per segment, played in order |
| Dice | Server-rolled, 2D tumble animation, **good sound** |
| Transport | One websocket, state diffs |
| State | In-memory, behind a repository interface |

### Out — and the reason

| Deferred | Why not in M0 |
|---|---|
| Postgres | M0 has no save. In-memory behind an interface; M1 swaps the impl. |
| Rules engine | Attack resolution is ~40 lines of hardcoded logic. |
| Conditions, reactions, concentration, spells | Not needed to test feel. |
| Procedural generation | One room, hand-authored. |
| Quest graph, campaign, arcs | No campaign exists in M0. |
| Synthesis / memory | Sessions are 5 minutes; send the whole transcript. |
| LLM stance assignment | The goblin has one behavior. |
| Prefetch | Nothing to prefetch. |
| 3D physics dice | 2D tumble + sound gets 80% of the feel. Physics is M2+. |
| Character creation | One hardcoded fighter. |
| Auth, save/load, multiplayer, hosting | Not a product. |

---

## 4. Hardcoded shortcuts — the explicit list

Every one of these is deliberate. An agent should implement exactly these and not "improve" them.

| # | Shortcut | Replaced in |
|---|---|---|
| 1 | Single room in `content/rooms/crypt.json`, hand-authored | M4 |
| 2 | Party is a `List<PartyMember>` **containing one member** — never a singleton field | — (permanent) |
| 3 | Fighter: AC 16, HP 12, +5 to hit, 1d8+3 damage, speed 30ft, STR +3 | M6 |
| 4 | Goblin: AC 15, HP 7, +4 to hit, 1d6+2 damage, speed 30ft | M6 |
| 5 | Attack resolution: `d20 + bonus >= AC`, nat 20 = double dice. No crit tables, no resistances. | M1 |
| 6 | Skill checks: DC from a 5-value enum only (5/10/15/20/25) | — (permanent) |
| 7 | State lives in a `ConcurrentHashMap` behind `GameRepository` | M5 |
| 8 | Event log is an in-memory `List<Event>` implementing the same interface | M5 |
| 9 | Two hardcoded voice IDs in config | M6 |
| 10 | System prompt is one string in `prompts/dm.md`, loaded at boot | M4 |
| 11 | Context = full transcript, no compaction | M5 |
| 12 | Tileset: 3 floor types, 2 wall types, 6 props | M4 |
| 13 | No error recovery — log and surface a visible error toast | M1 |
| 14 | No tests except on dice + attack resolution | M1 |

---

## 5. Architecture

```
Browser
  ├─ React (chrome: transcript, HP bars, dice log, input box)
  ├─ Zustand store  ◄── websocket ──►  Java
  ├─ Three.js canvas (in a useRef, never re-created by a React render)
  └─ Audio queue (ordered playback of TTS segments)

Java (single process, Javalin)
  ├─ WsHandler            — one session, one connection
  ├─ GameEngine           — authoritative state; applies actions; emits diffs
  ├─ DiceRoller           — interface; seeded impl + scripted test impl
  ├─ CombatEngine         — initiative, legal moves, attack resolution
  ├─ GoblinAi             — move toward nearest PC, attack if adjacent
  ├─ DmClient             — streaming LLM calls, tool dispatch
  ├─ TtsClient            — segment → voice → audio bytes
  └─ GameRepository       — interface; in-memory impl
```

No Docker, no database, no build orchestration. `./gradlew run` and `npm run dev`.

---

## 6. Core types

Define these once as the shared contract. Hand-write the TS mirror in M0; codegen arrives in M1.

```java
// ---- Scene ----
record SceneState(
    String roomId, int width, int height,
    String floorType, String wallType,
    List<Prop> props,
    List<EntityView> entities,
    LightingPreset lighting
) {}

record Prop(String type, int x, int y, int rotation) {}
record EntityView(String id, String kind, String name, int x, int y,
                  int hp, int maxHp, boolean isPlayerControlled) {}

// ---- Diffs (what actually goes over the wire after the initial scene) ----
sealed interface Diff permits EntityAdded, EntityRemoved, EntityMoved, StatChanged, ModeChanged {}

// ---- Player input ----
sealed interface PlayerAction permits FreeText, MoveTo, AttackTarget, EndTurn {}

// ---- Narration ----
record NarrationSegment(String speakerId, String text) {}  // speakerId "narrator" or an entity id

// ---- Dice ----
record RollRequest(String dice, int modifier, Advantage advantage,
                   RollPurpose purpose, String actorId,
                   Optional<String> targetId, Optional<Integer> dc) {}

record RollResult(RollRequest request, List<Integer> faces, int total, Outcome outcome) {}

enum Advantage { NORMAL, ADVANTAGE, DISADVANTAGE }
enum RollPurpose { ATTACK, SAVE, SKILL_CHECK, DAMAGE, INITIATIVE }
enum Outcome { CRIT, HIT, MISS, SUCCESS, FAILURE, CRIT_FAIL }
```

**`faces` must be a list even in M0.** The UI animates individual dice, and crits key off the natural
d20 rather than the total. Retrofitting this later means touching every call site.

---

## 7. LLM tool schema

Exactly five tools. Do not add more in M0.

```jsonc
{
  "roll_check": {
    "skill": "enum[athletics, perception, investigation, stealth, persuasion]",
    "difficulty": "enum[trivial, easy, medium, hard, very_hard]",
    "actor_id": "string"
  },
  "reveal_prop": {           // makes a hidden prop visible in the scene
    "prop_id": "string"
  },
  "spawn_entity": {          // M0: only "goblin" is valid
    "kind": "enum[goblin]",
    "x": "int", "y": "int"
  },
  "start_combat": {},
  "narrate": {               // the model's prose, segmented by speaker
    "segments": [{ "speaker_id": "string", "text": "string" }]
  }
}
```

**Every enum is closed and validated server-side.** The model cannot name a prop that doesn't exist,
spawn a creature that isn't statted, or invent a difficulty band. Invalid tool calls are rejected with
a structured error and the model retries once, then the turn degrades to narration-only.

---

## 8. Build order

Each task is independently verifiable. Do not proceed until the "done when" holds.

| # | Task | Done when |
|---|---|---|
| **T1** | Javalin app + websocket echo | Browser console logs a round-tripped message |
| **T2** | React + Zustand + websocket, render state as raw JSON on screen | Server-pushed state appears as text in the browser |
| **T3** | Load `crypt.json`, push `SceneState`, render it in Three.js — static, no interaction | The room is visible: floor, walls, props, one fighter token |
| **T4** | Pixelation post-process + orthographic camera tuning | It looks like the reference art, not like untextured 3D |
| **T5** | Debug buttons that emit `EntityMoved` diffs | Clicking a button visibly slides the token; no page reload |
| **T6** | `DmClient` streaming narration (text only, no tools, no TTS) | Typed input produces streaming prose in the transcript |
| **T7** | Tool calling + server-side validation + `spawn_entity` | "I open the sarcophagus" can make a goblin appear on the grid |
| **T8** | `DiceRoller` + `roll_check` + 2D dice animation + sound | Skill checks roll, animate, sound, and gate the narration outcome |
| **T9** | TTS: segment → voice → ordered audio queue | Narration is spoken, in order, with the goblin in a different voice |
| **T10** | Combat mode: initiative, legal-move highlighting, click-to-move, click-to-attack | A full fight is playable with zero AI involvement |
| **T11** | `GoblinAi` + one-call-per-round enemy narration | Goblin acts on its turn and the narrator describes it |
| **T12** | Mode transitions: camera pull, initiative animation, UI shift | Combat starting feels like an event, not a state flag |
| **T13** | Feel pass — timing, easing, audio mix, lighting | See §11 |

T1–T5 involve no AI at all. T10 is playable combat with no AI at all. This is intentional: the parts
most likely to break are the parts that don't involve a model, and they should be working before a
model is anywhere near them.

---

## 9. Repo layout

```
/server
  build.gradle.kts
  src/main/java/dm/
    App.java              WsHandler.java
    engine/               GameEngine  CombatEngine  GoblinAi  DiceRoller
    model/                (records from §6)
    ai/                   DmClient  ToolDispatcher  TtsClient
    repo/                 GameRepository  InMemoryGameRepository
  src/main/resources/
    content/rooms/crypt.json
    content/entities/{fighter,goblin}.json
    prompts/dm.md
  src/test/java/dm/       DiceRollerTest  AttackResolutionTest

/client
  package.json  vite.config.ts
  src/
    store.ts              # Zustand — ALL game state
    ws.ts                 # connection, diff application
    scene/                # Three.js: Renderer, TileGrid, EntityTokens, Camera, PixelPass
    audio/                # AudioQueue, DiceSounds
    ui/                   # Transcript, InputBox, PartyPanel, DiceLog, CombatBar
  public/assets/          # tileset + models + audio

/docs
  m0-build-plan.md
  ai-dm-system-design.md
```

---

## 10. Invariants — agents will violate these

State them in the project rules file. They are not style preferences; each one is expensive to unwind.

1. **The server is authoritative.** The client never computes a roll, a hit, a legal move, or a death.
   It renders what it is told.
2. **No singleton player.** `List<PartyMember>`, always. Every action carries an `actorId`.
3. **Game state never lives in React state.** It lives in the Zustand store. React and Three.js both
   subscribe. Violating this causes canvas re-creation bugs that are very hard to diagnose.
4. **The Three.js renderer lives in a `useRef`** and is created exactly once.
5. **`RollResult.faces` is a list.** Never collapse to a total.
6. **Roll results are logged as events.** Never plan to replay by re-rolling from a seed.
7. **All LLM-facing enums are closed and validated server-side.** No free-form strings from the model
   reach the engine.
8. **No `localStorage` / `sessionStorage`.** In-memory only.
9. **Modern Java only** — records, sealed interfaces, pattern matching, virtual threads. No Spring.
   No `AbstractXFactory`. No mutable POJOs with getters and setters.
10. **Do not add tools, entity types, props, or rules beyond §4 and §7.** Scope expansion in M0 defeats
    the purpose of M0.

---

## 11. Feel evaluation — the actual gate

Run the §2 script. Score honestly. This is the only deliverable that matters.

**Hard latency targets:**

- Keypress → first streamed token: **< 800ms**
- Keypress → first spoken word: **< 1.5s**
- Click-to-move → token starts moving: **< 100ms** (no model in this path — if it's slow, something is
  architecturally wrong)
- Full enemy round resolved and narrated: **< 4s**

**Subjective questions, answered out loud, to another person if possible:**

1. Does the room feel like a *place*, or like a diagram?
2. When the goblin appears, is it a surprise or a state update?
3. Does the narrator sound like a person running a game, or like a text-to-speech demo?
4. Does landing a killing blow feel good?
5. After the fight ends — do you want to type another thing? *(This is the real question.)*

**If it fails, diagnose before rebuilding.** The three usual culprits, in order of likelihood:
audio timing and mix; animation easing and camera work; narration prompt quality. All three are
tunable within M0. A genuine "the concept doesn't work" verdict should only be reached after all
three have had a serious pass.

---

## 12. Anti-goals

Written down because they are the things most likely to eat week two.

- **Do not build a rules engine.** Forty lines of attack resolution. If you find yourself modeling
  conditions, stop.
- **Do not tune lighting and post-processing for more than one evening.** Timebox it. This is the
  single largest time sink in the project and it will consume as much as you give it.
- **Do not add a second room.** The impulse will be strong after T7. One room.
- **Do not build save/load.** Restarting the process is fine.
- **Do not optimize anything.** One room, two entities.
- **Do not generalize.** Every abstraction written in M0 is written against a sample size of one and
  will be wrong.
- **Do not build a character sheet UI.** HP and AC as text is sufficient.
