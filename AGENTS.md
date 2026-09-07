# Emberdelve — Agent Rules

AI Dungeon Master. Java backend, Godot desktop client.

**Current milestone: M3 — gate PASSED 2026-09-06.** A room is a place you can leave and come
back to. The gate was a 19-turn played session that crossed four times, spent ≥7 turns in each
room, found the gallery niche as it was left, and replayed identical:
`docs/milestones/m3-evaluation.md`.

**Next: the generator plan** — M1's remaining half, *can the world be made rather than authored*.
The plan at `docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` is **stale in its
traversal half** (superseded by this milestone) **and in its client/Java foundation** — those
tasks are React/Three.js, and a `GameRepository` that no longer exists. Its `LayoutGenerator`,
`ExitPlacer`, and `SpatialValidator` tasks still lift.

### Milestones, and what each one settled

| | Gate | Verdict | Where |
|---|---|---|---|
| **M0** | Does this feel like a DM running a game? | PASS 2026-08-20 | `docs/milestones/m0-evaluation.md` |
| **M1** | Can the world be made rather than authored? | **Half done.** Single-room generation merged; a generated dungeon is the remaining half | `docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md` |
| **M2** | Can a fault found in play be turned into a test? | PASS 2026-09-05 | `docs/milestones/m2-evaluation.md` |
| **M3** | Is a room a place you can leave and come back to? | PASS 2026-09-06 | `docs/milestones/m3-evaluation.md` |

Godot replaced the Vite/Three.js table on 2026-08-23 (parity gate, Task 22).

### Reading order

`docs/milestones/m3-evaluation.md` first — it is the most recent gate and its §8 lists what M3 handed
forward. `docs/superpowers/specs/2026-09-05-m3-traversal-design.md` is the traversal architecture.
`docs/milestones/m2-evaluation.md` and `docs/superpowers/specs/2026-08-23-m2-spine-design.md` are the spine
everything still sits on. `docs/ai-dm-system-design.md` is the long-range design. **Amended
2026-09-05** against three gates: its §2a lists the four decisions play reversed and why, and
§14 carries both the real milestone history and the original ordering it superseded. Where it
and this file disagree, this file wins — it is the operational one.

`docs/milestones/m0-build-plan.md` and `docs/milestones/m0-evaluation.md` are history. They are still worth reading for
*why* things are the way they are, and their shortcuts table no longer describes this codebase.
Where M0 and M2 disagree, **M2 wins**. Where M2 and M3 disagree, **M3 wins**.

---

## The point of M0, and what replaced it

M0 was a **gate, not a foundation.** Everything that contributed to *feel* was in scope;
everything that contributed to *correctness, scale, or persistence* was hardcoded on purpose.
M0 code was expected to be thrown away, and much of it has been.

**M2 is the foundation M0 deliberately did not build.** The persistence and correctness shortcuts
are gone: state is a fold, the log is durable, the context is bounded. M3 retired the one-room
shortcut. The rest of M0's shortcuts — one goblin, forty lines of attack resolution — are still
in force and still deliberate. The table below says which is which.

The instinct M0 warned against still applies to everything M2 did not touch. If you are
generalising the *rules* or the *content*, stop. If you are making the *spine* correct, that is
now the job.

---

## Invariants — do not violate these

Each one is expensive to unwind. They are not style preferences.

1. **The server is authoritative.** The client never computes a roll, a hit, a legal move, or a
   death. It renders what it is told.
2. **No singleton player.** `List<PartyMember>`, always — even though M0 has exactly one member.
   Every action carries an `actorId`.
3. **Game state never lives in a Control or Node3D.** It lives in `Table`. Chrome and World
   both subscribe. Violating this rebuilds the world on a chrome redraw, which is very hard to diagnose.
4. **The 3D world is one scene, created once.**
5. **`RollResult.faces` is a `List<Integer>`.** Never collapse it to a total. The UI animates
   individual dice and crits key off the natural d20, not the sum.
6. **Roll results are logged as events.** Never plan to replay by re-rolling from a seed.
7. **All LLM-facing enums are closed and validated server-side.** No free-form string from the
   model reaches the engine. Tools use `strict: true`.
8. **Every state change is an event, and state is only ever a fold over the log.** There is no
   second write path — no setter, no `put`. If a change did not emit an event it did not happen.
   This is what keeps the log complete without anyone having to remember to keep it complete.
   *(Replaced M0's "no save, in-memory only" at the M2 gate. Sessions now write JSONL; nothing
   resumes from one — see the shortcuts table.)*
9. **Modern Java only** — records, sealed interfaces, pattern matching, virtual threads.
   No Spring. No `AbstractXFactory`. No mutable POJOs with getters and setters.
10. **Do not add tools, entity types, props, or rules** beyond what is listed below.

---

## Locked decisions

Resolved in a design review before implementation. Do not silently revisit these.

| Area | Decision |
|---|---|
| Assets | M0 shipped Kenney CC0 (Modular Dungeon + Graveyard). **Godot table is KayKit:** Adventurers Knight, Skeletons Warrior, Dungeon kit. Not 2D sprites. |
| LLM provider | **Venice.ai**, OpenAI-compatible, `https://api.venice.ai/api/v1`. One key, one endpoint, 100+ models. |
| LLM model | Two config strings, never literals: `DM_MODEL_TOOLS` (fast, reliably tool-calling) and `DM_MODEL_PROSE` (best writer). Measured picks and their disqualifications are below. |
| Narration | **Text channel**, not a tool. Inline `[[speaker]]` markers, validated against live entities. |
| TTS | ElevenLabs Flash v2.5 behind `TtsClient`. OS TTS is the working placeholder. |
| Build | Gradle + Kotlin DSL. |
| Determinism | Real DM discretion. `ScriptedDiceRoller` behind `--demo` for reproducible tuning runs. |
| Combat VO | **Dramatic beats only** — kills, crits, and the goblin's turn. Ordinary hits resolve instantly. |
| Camera | Four fixed isometric corners, 90° snap (Q/E). Never free orbit — it breaks the isometric read and grid picking. |
| Look | **Stylized isometric 3D at native resolution.** KayKit-class meshes, linear filtering, real lights. Not a 480px/960px nearest-neighbour pixel pass, not 2D isometric sprites. |

### Deviations from `docs/milestones/m0-build-plan.md` as written

The plan is the spec; these are the agreed amendments to it.

- **Four tools, not five.** `narrate` was removed — narration goes through the text channel so it
  streams from the first token. Remaining: `roll_check`, `reveal_prop`, `spawn_entity`, `start_combat`.
- **`Prop` gains `id` and `hidden`**; `type` is a closed enum. Without this `reveal_prop` cannot
  address anything.
- **`Diff` gains `PropRevealed`.** Without it a revealed prop never reaches the client.
- **Acceptance script step 3** exercises `reveal_prop`, so the player's first input produces a
  visible world change rather than narration alone.
- **`RollRequest` gains `Optional<Skill> skill`.** Without it the event log records that
  *something* was tested but not what, and the dice tray can only say "SKILL CHECK 22 vs DC 15".
- **`Diff` gains `CombatChanged`, and `SceneState` gains `mode` and `combat`.** The client cannot
  be told "it is your turn" without also being told what that turn permits. Coarse on purpose —
  see *Combat* below.
- **`Entity` gains `initiativeModifier`.** DEX is the only ability score the engine consults; the
  rest stay on the content definition. Without it every initiative roll is a coin flip between
  two flat d20s, and a tie is broken by nothing.
- **T10 includes `GoblinAi`,** which the plan's table lists under T11. Its deliverable is "a full
  fight is playable with **zero AI involvement**", and a goblin that cannot act is a punching bag,
  not a fight. The monster logic has no model in it, so it belongs on the near side of that line.
  T11 is now exactly one thing: the narration call.

---

## The failure mode to watch for

The load-bearing dependency in this architecture is **tool-calling reliability**, not prose
quality and not price. Every mechanical effect goes through a validated tool call.

A model that narrates beautifully but forgets to call `spawn_entity` produces a goblin that is
vividly described and never appears. That failure is **silent** and it looks like a rendering
bug. When something in the world does not match the narration, suspect a dropped tool call
before you suspect the renderer.

Related, if you ever route to Claude directly: never set `thinking: {type: "disabled"}` on
Opus 5 — it can write a tool call into visible text instead of emitting a `tool_use` block, with
no error raised. Use a low effort setting instead.

## Models — the pick, and where the reasoning lives

**Current pick, signed at the M2 gate and unchanged through M3:**

```
DM_MODEL_TOOLS=qwen3-next-80b
DM_MODEL_PROSE=gemini-3-8-flash
DM_REASONING_EFFORT_PROSE=low
```

The DM is **two models, not one**: phase 1 decides tool calls on `DM_MODEL_TOOLS` and any prose
it writes is discarded; phase 2 writes narration on `DM_MODEL_PROSE` while the dice are still
animating, which is what buys it permission to be slow. Never literals — always these config
strings. Narration stays on exactly one model; splitting *it* makes tone drift audible between
turns. Full reasoning and the measured end-to-end numbers: **ADR-0003**.

`DM_REASONING_EFFORT_PROSE=low` **is not optional.** Gemini 3.x Flash cannot turn thinking off
and `low` is the floor; at default it takes 9–15s to a first word, which is an empty screen the
dice cannot cover. Leave it unset for models that do not take the parameter — a bad value 400s
the turn.

Four things to carry into any model change. The evidence for each is in **ADR-0008**:

- **A capability flag is a claim, not a behaviour.** Require both `supportsFunctionCalling` and
  `supportsResponseSchema`, then measure against the real tool schema — several models that
  advertise function calling narrate instead. Most `e2ee-*` models report `false` and are
  incompatible outright.
- **Judge the tail, not the median.** Two finalists were disqualified for multi-second stalls
  with no error and full quota. Bimodal is worse than consistently slow.
- **The pick's own tail is live.** `qwen3-next-80b` showed 14.3s and 15.5s tool phases in play
  that no benchmark caught. Watch `FIRST FEEDBACK` in a real session.
- **`qwen3-next-80b` hallucinates props**, and holds the tools slot anyway because its prose is
  discarded. That safety is void the moment anyone promotes it to prose.

A candidate enters a slot on a **played session**, not a benchmark — single-shot numbers against
a multi-tenant provider measure a moment, not a steady state.

---

## Hardcoded shortcuts — implement exactly these, do not "improve" them

| Shortcut | Value |
|---|---|
| Rooms | Two authored: `content/rooms/crypt.json` and `gallery.json`. No generated dungeon. |
| Party | `List<PartyMember>` containing one member |
| Fighter | AC 16, HP 12, +5 to hit, 1d8+3 damage, speed 30ft, STR +3 |
| Goblin | AC 15, HP 7, +4 to hit, 1d6+2 damage, speed 30ft |
| Attack resolution | `d20 + bonus >= AC`; nat 20 doubles dice. No crit tables, no resistances. |
| Skill check DCs | A 5-value enum only: 5 / 10 / 15 / 20 / 25 |
| Voices | Two voice IDs in config, chosen by entity **kind** |
| System prompt | One string in `prompts/dm.md`, loaded at boot |
| Errors | No recovery — log and surface a visible error toast |

### Retired at the M2 gate — do not restore these

| Was | Is now |
|---|---|
| `ConcurrentHashMap` behind `GameRepository` | `WorldState`, an immutable record folded from the log. `GameRepository` is deleted |
| In-memory `List<Event>` behind the same interface | `EventLog` + `SessionWriter`, appending JSONL to `server/sessions/` |
| Full transcript, no compaction | `WINDOW_TURNS = 6` of verbatim transcript, plus an engine-owned projection that never truncates |
| Tests: dice and attack resolution only | The fold, the log, the parser, the beats, the context, and a recorded session replayed offline |

**Still deliberately absent:** Postgres, resume-from-log, snapshotting, and schema migrations. A
log written at an older `Event.SCHEMA_VERSION` is **refused, never upgraded** — discarding an old
log is free and an upgrader is a tax paid forever. No party splits (the whole party moves). No
fleeing (exits are illegal in combat).

## LLM tools — exactly these seven

On the **mechanics** pass: `roll_check` (skill + difficulty enums), `reveal_prop` (per-room closed
enum of hidden prop ids), `spawn_entity` (kind: `goblin` only), `start_combat`, `use_exit`
(current room's exits; withheld in combat), `move_entity` (living `entitiesHere()`, bounds of
this room).

On the **reconcile** pass only: `reveal_prop`, `spawn_entity`, `start_combat`, `assert_fact`, and
`move_entity`. No dice in that phase — the outcome has already been narrated. **`use_exit` is
mechanics-only; `move_entity` is both phases.** A tool belongs in reconcile when a false positive
is cheap to live with and the narrator is the one holding the information — walking to a pillar
is; leaving the room is not.

Every enum is closed and validated server-side. Invalid calls are rejected with a structured
error; the model retries once, then the turn degrades to narration-only.

**`assert_fact` is the one tool that carries free-form model text**, and it is not a hole in
invariant #7. The rule, stated so it does not erode: *free-form model text may enter the prompt;
anything reaching the engine or the renderer goes through a closed enum.* A fact's `text` makes
one round trip back into the next prompt — it drives no roll, gates no legal move, and reaches no
renderer. Its `anchor` (`ambient` / `at_square` / `on`) is closed and validated like everything
else.

---

## Anti-goals

These are the things most likely to eat week two.

- **Do not build a rules engine** *yet*. ~40 lines of attack resolution. If you are modeling
  conditions, stop. This is scoped to the current milestone, not a design position: the end state
  is a full SRD-shaped engine with reaction hooks and concentration, and it arrives with the
  content it adjudicates. See `docs/ai-dm-system-design.md` §6.
- **Do not tune lighting and post-processing for more than one evening.** Timebox it. This is the
  single largest time sink in the project and it will consume as much as you give it.
- **Do not add a third room, and do not generate a dungeon.** Two authored rooms is the M3
  shortcut. The generator plan is next.
- **Do not build save/load.** Sessions are *written* — that is the replay harness — but nothing
  resumes from one, and resume is the expensive half. Restarting the process is still fine.
- **Do not optimize anything.** Two authored rooms, two entities.
- **Do not generalize.** Every abstraction in M0 is written against a sample size of one.
- **Do not build a character sheet UI.** HP and AC as text is sufficient.

---

## Latency targets — recorded, not the gate

**The gate is multi-turn consistency, not speed** (`m0-evaluation.md` §4.3). Every complaint from
both played sessions was the DM contradicting a world it had already established; not one was
about a wait. With a slow, natural voice, a pause reads as the DM thinking. Keep these numbers so
a regression stays visible, and do not propose latency work on the DM path unprompted.

| Path | Budget | Where it stands |
|---|---|---|
| Keypress → the UI acknowledges the input | < 100ms | no model in this path |
| Keypress → first mechanical feedback (dice in the air) | < 1.2s | **measured in play 1.1–2.5s**, median ~1.3s |
| Keypress → first spoken word, **gap filled** | < 8s | measured in play 4.0–10.2s end to end |
| Keypress → first spoken word, **nothing on screen** | < 2.5s | **missed — 2.5–3.6s.** See `m0-evaluation.md` §4.1 |
| Click-to-move → token starts moving | < 100ms | no model in this path. T10 |
| Full enemy round resolved and narrated | < 4s | **measured ~3s** — 1.5s to resolve, 1.3–1.6s to narrate |

### Why these are not the numbers in `docs/milestones/m0-build-plan.md`

The plan's targets — first token < 800ms, first spoken word < 1.5s — come from a text-chat mental
model, where the first token *is* the experience and silence before it is the whole cost. This
game is not that. There are dice on the table and a voice talking, and those change what the
player is actually waiting through.

Two consequences:

- **"First token" is the wrong thing to measure.** What matters is the first moment something
  *happens* — a die leaving the hand. That is a tool-phase number, not a prose number, and it is
  why `TurnMetrics` logs `FIRST FEEDBACK` in preference to everything else it records.
- **Speech latency is only expensive when nothing covers it.** With dice tumbling for ~1.5s and
  the tray holding until narration starts, a seven-second wait for the first word does not read as
  a wait. With an empty screen, two seconds does. Hence one budget split into two.

### What T13 decided — measured over two played sessions

Both questions are answered in `docs/milestones/m0-evaluation.md` §4, against 12 typed turns of real play
rather than the dice-building sample the question warned about. In short:

1. **How often is a turn tool-free? 42% of turns — 5 of 12 — showed the player nothing**, and the
   2.5s row is missed on every one of them. The cause is structural: the mechanics phase runs *in
   front of* the prose phase, so a turn that requests no tools pays the whole tool latency as dead
   air and buys nothing with it. A rejected call is the same silence with a round trip behind it.
   **Judged in play to be a non-problem** — neither player remarked on a wait, and against a
   spoken narrator a pause reads as the DM thinking. Recorded, not scheduled. If it is ever worth
   fixing: a speculative prose call started in parallel and discarded if tools fire, or a visible
   thinking beat. Never a faster prose model; prose was never the problem.
2. **Is ~20 seconds of speech per turn too long? No, but the ceiling is at the limit.** Mean typed
   turn measured **315 characters, ~16 seconds**; the two 25-second outliers were the goblin-spawn
   beat (which earns it) and the since-fixed replay bug. No change for M0. Re-measure after the
   repeat fix has a real session behind it.

**Read `TurnMetrics`' counter with care.** It logs `n/m turns used dice` but increments on
`toolCalls() > 0`, so it counts a turn whose only call was rejected and a turn whose call produced
no dice. `FIRST FEEDBACK` is the honest signal — over the same 12 turns the counter said 8 and the
feedback line said 7.

---

## Characters

Tokens are KayKit figures on a small base, in `godot/world/kits/characters/`.
Swapping a character is one entry in `MODELS` (`godot/world/tokens/token.gd`) and nothing else.

Three things that are not obvious and cost an hour each if forgotten:

- **Each kit needs its own folder.** Kit GLBs reference textures by relative path; mixing kits
  in one directory silently paints one of them with the other's atlas.
- **Figures sit under the wall** — KayKit knight ~0.8 world units on a 1.0 square. Oversizing to
  1.25 was for a 480px pixel buffer where a to-scale human was ~24 pixels and read as a smudge.
  That buffer is gone. Do not grow figures to “read at 480.” Do not “fix” the dark crypt by
  raising the ambient; tokens still carry a faint emissive of their albedo.
- **Characters carry a faint emissive of their own albedo.** The crypt is genuinely dark away
  from the two braziers, which is right for the room and wrong for the figures standing in it.
  This lifts tokens off the floor without touching scene lighting — do not "fix" it by raising
  the ambient.

Current cast: KayKit **Knight** as the fighter, **Skeleton_Warrior** as the goblin.

KayKit adventurers are skinned; the skeleton kit is the same clip set on a different mesh.
Godot's glTF import gives every instance its own `AnimationPlayer`, so two tokens of one
model do not animate in lockstep.

Origins differ between kits, so `Token` measures the bounding box rather than keeping a table of offsets.

---

## Voice

Narration is spoken through an **ordered queue** (`godot/autoload/clock.gd`): one line at a
time, strictly in arrival order, because two sentences talking over each other is the worst thing
a spoken DM can do. A new turn silences whatever is left from the last one.

Ordering against the dice is not handled there — it is inherited, because the queue is fed from
`Table`, which calls `Clock.hold` for a dramatic roll.

**The voice also paces the transcript.** The model streams a whole turn in about three seconds
and the voice takes twenty to say it, so text appended on arrival has the player speed-reading
ahead of the narrator. Each line is committed to the transcript by the queue, as it starts being
spoken — measured within 1ms of the utterance. No words-per-minute constant to tune, and it stays
correct if the voice changes. With voice off, lines reveal on arrival, which is right for reading.

Dropped lines are still revealed. Interrupting stops the *speech*, not the *record* — text that
vanished from the transcript because nobody got round to saying it would be a bug.

`VoiceBackend` is the seam. OS TTS today (free, local, and the dev default); ElevenLabs Flash
v2.5 next, synthesised **server-side** because the key must never reach the client. `speak()`
resolves on end, error, or cancel and never rejects — a rejection would stall every line behind it.

Casting is a small closed table matched by name prefix, so it degrades to pitch and rate on a
machine without those voices. On macOS: **Daniel** narrates, **Ralph** is the goblin.

### Measured, and worth keeping

- **The parser decides who speaks, not the model.** The rule is one line: **quoted runs belong to
  the marked creature, everything else belongs to the narrator.** A marker names who is speaking
  and stays in force; an unmarked quotation goes to the last creature that spoke. Four separate
  model behaviours forced this, all observed in dry runs — opening with `[[goblin]]` and never
  closing it, speaking twice off one marker, putting the marker *after* the dialogue, and
  omitting the marker entirely on a second line. Do not try to fix any of them with prompt text;
  that was tried first and it is not reliable.
- **Prompt and parser must agree.** `dm.md` used to say "return to `[[narrator]]` when the speech
  ends". The model obeyed — and then never re-marked the goblin, so every later line of dialogue
  came out in the narrator's voice. The instruction was undoing the parser's own default. It now
  says the marker stays in force.
- **A blank line closes a quotation.** Models write unbalanced quote marks, and without this one
  stray `"` flips the parity and mis-voices the rest of the turn.
- **A new turn lets the sentence in the air finish** and drops the rest. Cutting a voice mid-word
  is jarring in a way that cutting between sentences is not. Only errors cut dead.
- **Writing more about brevity made the model more verbose.** Replacing the length rule with a
  seven-line explanation (speech rate, dead air, the arithmetic) took narration from 514 to 654
  characters a turn. Replacing it with two blunt lines took it to **393**. Terse instructions win
  on length; measure before believing an edit helped.
- **The prompt's own example was a markdown code fence, and the model copied the fence.** It
  arrived as a segment and got read aloud. `NarrationParser` now drops any segment with no letter
  or digit in it; the example is plain prose.
- **Narration costs about 20 characters per second of speech.** 400 characters is ~20 seconds the
  player sits through before acting. This is the real ceiling on turn length, not the token budget.

### Combat narration

The DM does not go quiet when a fight starts, which was the loudest thing wrong with T10 — a
narrator who describes your exploration beautifully and then falls silent the instant a sword comes
out is the most obvious possible tell that nobody is running this game.

Measured: **708–782ms to first token, 1.3–1.6s total, 150–204 characters.** Far quicker than an
exploration turn, because there is no tool phase — nothing is left to adjudicate, so it is one
streaming prose call.

- **The engine states facts; the model writes prose.** `CombatSink.beat` carries plain sentences —
  "Vessk hits Roderick for 7 damage" — and never a word of description. Neither side does the
  other's job.
- **A wound is described, not counted.** The beat says "badly hurt", never "3 of 7". `dm.md`
  forbids reading hit points aloud, and handing the model a fraction is an invitation to read it.
- **The enemy's whole turn is one call**, move and swing together. One call per action would put
  combat on a five-second-per-click clock, which is what design doc §7's "dramatic beats only"
  exists to prevent. The player's own swings are narrated only on a kill, a crit or a fumble —
  decided structurally from the roll outcome and the diffs, never by reading the beat text.
- **The animation is the cover.** The enemy turn is still animating on the client when the prose
  call starts, exactly as the dice cover an exploration turn, so the narration is free rather than
  additive.
- **One narration at a time, and the asymmetry is deliberate.** Narration the *player* asked for
  (the opening, a typed turn) waits on the lock, because dropping it would silently swallow
  something they did. Narration the *engine* generated gives up, because a swing narrated ten
  seconds late is worse than one not narrated at all. Dropped beats are logged.
- **The debug bar and `end turn` are locked while the DM speaks.** Starting a fight halfway through
  the opening sentence is a state the game cannot otherwise reach, and it produced exactly the
  collision the lock now refuses.

Still open: attacking during an enemy-turn narration drops the kill's own narration. The fight
stays correct and goes silent, which reads as the DM losing interest. Rare — the window is about a
second and a half. Carried out of M0 undecided: either the board locks during narration or the
beat queues. See *Known, unfixed* below.

### The opening

The room narrates itself before the player has typed anything (§2 step 2), triggered by the click
on the title screen.
`DmService.openScene` runs the prose phase alone — there is nothing to adjudicate yet, so the
mechanics model is not consulted and the whole thing is one streaming call. Measured at
**515–712ms to first token, 1.8–3.1s total**.

- **It is phrased as a beat, not a request for a description.** "Open the session: what they walk
  into" gets prose; "describe the room" gets an estate-agent listing of its contents.
- **Asked for by the client, not pushed on connect.** A socket opening is not a player arriving.
  Browsers refuse to play audio until someone has clicked something, so narrating at connect meant
  the DM described the room to a page that could not make a sound — and the transcript, which is
  paced by the voice, drifted away from it. `ui/Title.tsx` sends `begin` from inside the click that
  unlocks audio; the ordering inside that handler is the whole mechanism. It also buys the sample
  bank time to decode, which is why the first roll of a session no longer loses its rattle.
- **Once per process, not once per connection.** The client reconnects on every dropped socket and
  every dev-server reload, and a DM that re-describes the room each time is a bug that reads as a
  haunting. `debug → debugOpen` re-runs it past the guard for tuning, because during development
  the guard will otherwise eat exactly the thing being tuned.
- **The instruction stays out of `history`;** only the reply goes in. Replaying stage direction
  every turn has the model treating it as something the player said.

### Since fixed — kept because the reasoning survives the fix

**The player's dialogue was the model's to write, in the end.** It was listed here as a defect —
the prose model putting words in the player's mouth, which `dm.md` forbade outright. The rule was
wrong, not the model: a DM voicing a player's shouted taunt is a DM doing its job. `dm.md` now
says the player's *voice* is the narrator's to use while their **decisions** and **feelings** stay
untouched, which is the line that actually matters. What remains is the parser problem this
created — an unmarked quotation has to be guessed at, and the guess is now conditioned on whether
the player's own sentence says they spoke.

**A dead player character used to be only a dropped token.** Combat ended, the mode pill flipped
back to EXPLORATION, and nothing said what had happened — with the input box refusing text and the
board refusing clicks, that reads as a crash rather than a death. `ui/Defeat.tsx` marks the moment
and offers the way on. It throws the session away and lays the room out again, which is what
restarting the process did, without the terminal.

**The opening used to be silent on a cold load.** Browsers gate audio behind a user gesture and
the narration arrived before the player had made one, so the DM described the room to a page that
could not make a sound. Fixed definitively by the title beat rather than by a patch: `ui/Title.tsx`
sends `begin` from inside the click that unlocks audio. See *The opening* above — the ordering
inside that handler is the whole mechanism.

### Known, unfixed

**Attacking during an enemy-turn narration drops the kill's own narration.** The fight stays
correct and goes silent, which reads as the DM losing interest. The window is about a second and a
half. Decide whether the board locks during narration or the beat queues.

**The narrator can describe a world change nothing can back.** A player asked for the goblin to
become "an immense, steaming hotdog" and the narrator obliged; there is no transformation tool, so
the reconcile pass correctly declined to invent a mechanism and the goblin went on fighting.

*Partly answered by M2.* `assert_fact` now records assertions the board cannot hold, so the DM at
least stays **consistent** about its own hotdog rather than forgetting it next turn. What it
cannot do is make the board agree. The M2 gate produced the same class of thing with a straight
face: a copper key in a lamp's fat, asserted, durable, and turning a lock in a door that still
cannot open (`m2-evaluation.md` §4). The M3 session did it again: breathing inside a shut
sarcophagus, never spawned (`m3-evaluation.md` §4).

### Carried out of M2 — findings, not tasks

The full list is `docs/milestones/m2-evaluation.md` §8. The ones that will bite first:

- **Talking to yourself can start a fight.** A spoken aside on turn 11 of the gate session had the
  mechanics model spawn Vessk and call `start_combat`. The two-failure escalation rule in
  `dm-tools.md` is doing that. It ate the parley the session needed. Did not recur in the M3
  gate session (no spawn, no `start_combat`, no `use_exit`).
- **`roll_check` twice on one action** still happens despite the tools prompt. M3 turn 1 did it.
- **Grid coordinates leak into `## Established`** (`alcove at (9,6)`). They will be read aloud the
  moment a writer is sloppier than Gemini, against the standing "never say a grid coordinate" rule.
- **Invented content is now durable**, which is the spine working and a content problem. The north
  door opens now; the copper key is no longer a slab that cannot move. Invented lanterns still
  travel (`m3-evaluation.md` §4).

### Carried out of M3 — findings, not tasks

The full list is `docs/milestones/m3-evaluation.md` §8.

- **`reveal_prop` can beat the sentence.** A look-around was enough to put the gallery niche on
  the board before the prose named it.
- **Authored secrets still have no spawn.** Listening at the lid produced a fact and no goblin.
- ~~**Dark neighbour unread.**~~ **Read on 2026-09-07, and DIM stands** — see the playtest section
  below. Kept as a line because the reason it sat unanswered for a day is worth remembering: a
  headless gate cannot settle a question about what something looks like.
- **Generated rooms have nothing to find** (spec §12a). `PropPlacer` marks nothing hidden, so
  `reveal_prop` is never offered. A generated room has no alcove and no niche. The wrong fix is
  free-form dress-pass secrets with no mechanism; the right fix is hidden props the generator
  already knows how to place.

### Fixed after the M3 gate — playtest 2026-09-06

Three complaints from the first played session on the traversal branch, and what each one turned
out to be.

- **A hole in the wall is a way out, and there are no other holes.** The CARVED wall mix hashed
  `wall_gated`, `wall_doorway` and `wall_archedwindow_open` around the perimeter, so the crypt
  grew openings that led nowhere and the real door was one of several. All three are gone from
  the mix; `room.gd` places a `wall_doorway` at the squares the server's `exits` list names and
  nowhere else. `wall_window_open` stays — a small barred opening high on the wall reads as a
  window, and a window is allowed to lead nowhere.
- **The door is the wall, not a prop standing in front of it.** KayKit's `wall_doorway` arrives
  with a banded wooden door already hung in it, so the `DOOR` prop drew a second door in the same
  hole — visibly, as two ring handles. `DOOR` is out of `prop_table.tres`; the prop still exists
  server-side because the DM and the `Exit` both address it by id, and the wall segment carries
  that id as an `exit_id` meta so the pointer addresses the same thing. **The thing you see and
  the thing you click are one object.**
- **A door is clicked, not walked onto.** `pick_at` resolves the doorway under the ray and
  returns its id; `Table.exit_at(square)` is gone. A target the overlay has no intent for falls
  through to the floor square, so nothing can swallow a move.
- **Only things you can act on are pick targets.** The first cut made every prop a target, on
  the theory the seam would be wanted later. It broke the door on the first real room: picking
  takes the nearest hit, and `brazier-east` — a 1x1 box around a narrow bowl, standing between
  the camera corner and the north wall — won the ray, had no intent to offer, and fell through
  to a square that was `null` because the ray had already left the room. The click did nothing
  and the failure was silent. **A thing with nothing behind it must not shadow a thing that has
  something behind it.** Props get added to `_target_under` when they gain actions, ranked
  against each other deliberately — not on spec.
- **A marker has to leave the warm end to be seen.** The exit marker was first drawn amber,
  `0xd8994a` — which is (0.85, 0.60, 0.29), against a torchlit floor that renders at about
  (0.85, 0.60, 0.35). An unshaded quad at 60% alpha over a ground it already matches is an
  invisible quad. The markers are cool now: teal for a door, red for a solid square. Anything
  that must read against these floors has to be picked against the floor, not in isolation.
- **Where you cannot walk is shipped, not derived.** `SceneState.blocked` carries the squares the
  player can see are solid, for the same reason `CombatView` ships `legalMoves` — the client owns
  no movement rule (invariant #1). It cannot be read off `props` either: an alcove is a prop you
  can walk into. **Visible props only** — a hidden prop that blocks would put a marker on the
  board exactly where a secret is. The click is still sent and the server still refuses it in its
  own words; the marker only says so a moment earlier. The quad stays *under* whatever stands on
  the square: drawn over the top it reads as paint on the object rather than a mark on the square.
- **A one-prop fixture will not find a picking bug.** The test that missed this held only the
  door, so nothing was ever in front of it. `DOORWAY` in `test_overlay.gd` now carries the
  crypt's real furniture, and re-adding props to the picker turns it red.
- **The room nearer the entrance owns the shared wall.** The server places a neighbour so
  the two doors line up, which puts both perimeters on one plane. Drawing both is two walls
  fighting for one depth — it showed as a door with two handles, then as texture noise across the
  seam. The plane is drawn once, by whichever of the two rooms is fewer doors from the entrance,
  ties broken by `roomId`. *Not* by which room the party is standing in: that was the rule until
  `Rooms.coveredWalls()`, and it stops working the moment more than one room is on screen — a
  middle room has shared walls on both sides, and two rooms the party is standing in neither of
  would each leave the wall between them to the other.
- **Omission is an interval overlap, not a direction match.** A neighbour used to omit the whole
  wall run its answering door is in, on the grounds that matching segment against segment can
  half-work when the rooms' widths differ in parity. It loses the part of a perimeter that
  *overhangs* the shared run — two 1-unit holes at the crypt's north corners, seen from the
  gallery, and a hole in a wall is a way out. `coveredWalls()` compares 1D intervals on the shared
  plane using absolute origins, and drops a segment only when the owner's run contains it whole.
  The parity worry was about segment *centres*: two rooms whose widths differ in parity do sit
  half a square out of step, but `beside` offsets by half the width difference too, which puts
  their segment *boundaries* back on one lattice. So a partial overlap means something upstream
  is wrong, and overlapping stone reads as noise where a half-square gap reads as a way through.
- **The stone is the server's; the door is the party's.** `RoomView.coveredWalls` carries
  `Rooms.coveredWalls()`' answer for one room and `room.gd` omits exactly those segments, so both
  rules above are now what is on screen — the crypt's north corners are stone from the gallery,
  and every plane is drawn once by the same room from either side. The doorway is the exception,
  and it has to be: a door is one object that two rooms address by two ids, and `_target_under`
  only ever scans the current room's walls. So the room the party is standing in hangs the door
  and every other room draws nothing at its own exits, which leaves the opening open — which is
  also what the far side of a doorway should look like. Hang it anywhere else and you get a door
  you can see and cannot open, which ends a crossing one way.
- **Withholding the torches did not make a neighbour dark.** The ambient still lifted the kit
  textures far enough to read, so the gallery looked like somewhere already visited. A room built
  at `Room.Level.BLACK` has every surface painted near-black and **unshaded** — shaded, the crypt's
  own torches would light the far room by degrees as the party walked up to the door.

**`TurnMetrics` mislabels its counter** — see the latency section. One line; left alone so the
numbers in `m0-evaluation.md` match the logs as they were written.

### Judged in play — playtest 2026-09-07

Two played sessions, in the real client, against the list in `emberdelve-xgg.8`. The logs are
`server/sessions/20260907-030629-e6571025.jsonl` and `20260907-195254-82a68212.jsonl`.

**DIM stands, and the darker end is the better-looking one.** A visited room stays at
`Room.Level.DIM` and the fallback to LIT is not taken. The design predicted the ambient drift
between the two directions and accepted it as a cost — standing in the gallery puts the crypt under
`EnvDim`'s 0.7, standing in the crypt puts the gallery under `EnvTorchlit`'s 1.1. Play inverted
that: 0.7 was judged the better look, and the crypt is the room it was judged on. The cost was not
a cost. **`EnvTorchlit`'s 1.1 is now the suspect number, not `EnvDim`'s 0.7** — recorded, and
deliberately not acted on, because § *Anti-goals* timeboxes lighting and one observation is not a
tuning session. `emberdelve-27l` if it is ever worth an evening.

**ADR-0011 held where it mattered.** A click in a non-current room resolved to nothing and
swallowed no move; the door back stayed clickable; `brazier-east` did not shadow it. A goblin left
alive in the crypt was not drawn from the gallery and read as absence rather than as a bug. That
moved the ADR from Proposed to Accepted — the confinement had compiled since `xgg.5` and had never
been clicked at.

**Nobody missed the narration on a crossing.** Clicking a door crosses in silence: 15 of the 17
crossings across both sessions produced no narration, because `WsHandler.enterExit` arms
`pendingArrival` and never runs a prose phase. Judged in play to be right. Clicking a door and
walking through *is* the honest path, and the arrival beat was not what was missing.
`emberdelve-xgg.12` — recorded, not scheduled, the same way M0's tool-free-turn latency was.

**What was missing was knowing whether a door could be crossed at all.** `crossExit` refuses
exactly one thing, leaving mid-fight. There is no locked, no barred, no stuck — and every authored
door description said otherwise ("no handle on this side", "shut on a dark stair-head"), while
`waysOut()` listed a door and said nothing about its state and `use_exit`'s instruction closed
"when in doubt, do not". The model reconciled that afresh every turn, which is what the player
felt. **Both sessions independently invented an ATHLETICS DC 20 to kick a door that was never
shut**, and one then spent two turns trying a key found on a corpse against a lock that does not
exist. Fixed by the prompt telling the truth: `## Ways out` now states the exits are open and
names the fight as the only exception. A real door state is `emberdelve-ql3` and wants the content
that justifies it.

**A rule the model reads two ways is a rule that decides nothing.** Both sessions opened on the
identical move — inspect the sarcophagus, failed check, the first of the session. One spawned the
goblin and let five turns of parley run. The other spawned it and called `start_combat` in the same
response, putting the player in initiative before they had done anything hostile.
`dm-tools.md` says the situation must change on the *second* consecutive failure; both escalated on
the first. `emberdelve-vy7`. This is M2's "talking to yourself can start a fight" wearing a new
hat, and it decides what kind of game the session is on turn one.

**`roll_check` twice did not recur.** Carried out of M2 and out of the M3 gate; absent from both
of these sessions. Not called fixed on two sessions, but worth knowing it moved.

**Rejections are doing their job quietly.** A duplicate `spawn_entity` was refused twice and a
stale `move_entity` once, and no turn was visibly damaged by any of it.

**Latency held.** First feedback median 1.3s over 26 typed turns, range 1.0–2.6s. Three turns were
tool-free — 12%, against M0's 42%, which is the number that made the dead-air question worth asking
in the first place.

**Still not exercised: a prop revealed and then crossed away from.** Neither session managed it.
One revealed nothing at all; the other found the crypt alcove after its last crossing. The gallery
niche was never found in either — its `revealHint` is on the west wall and both players searched
the brazier and the pillars. So the room-qualified `PropRef` is correct by test and untried by
hand.

---

## Combat

Initiative, movement, one melee attack. Attack resolution is about forty lines in
`CombatEngine.resolveAttack` and is meant to stay that way — the §12 anti-goal is
*do not build a rules engine*. No reach, no cover, no opportunity attacks, no conditions.

**The server ships the legal sets, not the inputs to compute them.** `CombatView` carries
`legalMoves` and `legalTargets` already decided. Nothing on the client knows about speed,
blocking props or reach; `intent()` in `Canvas.tsx` asks whether the clicked square is in a list
that arrived over the wire, and that is the entire client-side movement rule. This is invariant #1
taken literally, and it is why M1 can add difficult terrain without touching the client.

- **`CombatChanged` replaces the whole picture.** A fine-grained "movement decremented" diff would
  let the client's idea of the legal set drift from the server's, which is the one thing
  invariant #1 exists to prevent.
- **Terrain belongs to the room** (`RoomDefinition.isObstructed`), not to combat. Walking into the
  sarcophagus is impossible whether or not anyone has rolled initiative.
- **Both metrics are Chebyshev.** Diagonals cost one square, matching `Entity.isAdjacentTo`. Two
  distance metrics in one combat system is how "why can it hit me from there" bugs start.
- **The goblin's turn is paced by the server** (`WsHandler.BEAT_MS`). This looks like a layering
  violation and is not: the beat between "it reaches you" and "it hits you" is the DM's, not the
  renderer's. `runAutomaticTurns` takes the beat as a parameter, so tests spend none of it.
- **Death leaves a body.** The entity stays in the repository at 0 hp, the token clamps on its
  `die` clip, and the DM's world state marks it `[dead]`. Tokens revive if the server ever reports
  hit points above zero again — dead is what the server says now, never what it once said.

**Health bar colour is allegiance, not health.** Green is yours, red is theirs; the bar's *length*
carries the hit points. The player's bar used to also warn by going amber then red as it emptied,
and the moment enemies went red that made one colour mean two things — a short red bar was you in
trouble, a long red bar was a healthy goblin. One meaning per colour. If the danger signal turns
out to be too quiet, the fix is a pulse, not a hue.

**The roll log names who rolled.** Names come from `scene.entities` (the server is the authority on
what a creature is called); the colour comes from the `VOICE` table, so a creature reads the same
whether it is speaking or rolling. Laid out as a grid rather than wrapping flex, so the log scans
vertically — every actor in one column, every total in another.

`debug → start combat` runs the whole fight with no model in the path, for the same reason
`debug → roll d20` exists.

---

## Dice

The dice are not decoration. They are what makes a ~6s prose latency tolerable: something with
weight happens at ~1s, and the narration lands while the player is still watching it.

- **The server decides, the animation displays.** Tumbling faces are noise; the instant a die
  settles it shows `result.faces[i]`. `godot/dice/tumble.gd` is pure and holds this rule
  in one line. No physics library — see the design doc §7 on why most of them are the wrong
  direction.
- **Narration is held until the dice land** (`Clock.hold` from `Table`). Timer-based, not
  driven by the tray node, so the gate still opens if the tray never mounts. Today the
  prose model is slow enough that ordering is never in doubt; a faster one would otherwise
  announce the outcome over a die still in the air.
- **The sidebar log is also gated,** and so are diffs. A record that arrives before the throw
  finishes spoils it, and a hit point bar that empties while the attack die is still in the air
  has answered the question the die was asking. When no roll is in flight the gate is open and
  everything passes straight through, which is what keeps click-to-move inside 100ms.
- **Not every roll animates** — `isDramatic()`, and it keys off *purpose*, not off who rolled.
  Attacks, saves and skill checks throw; damage and initiative go straight to the log. An
  incoming attack is the tensest die in the game and the goblin throws it, so "animate the
  player's rolls" would gate out exactly the wrong ones. Damage animating after to-hit makes one
  swing read as two; initiative is every combatant at once and the tray throws one roll at a time
  (T12 owns that beat).
- **The tray leaves faster in combat.** Out of combat a roll is a question the DM is about to
  answer, so the tray waits for the answer. In a fight the blow is the answer, so the swing
  dismisses the tray exactly as narration does — the readout dissolves as the sword comes down,
  which is what moves the eye from the tray back to the board. `COMBAT_HOLD_MS` (3s) is only the
  backstop for rolls with no swing behind them.
- **The blow waits for the result to be read** (`IMPACT_BEAT_MS`, 650ms after `revealAt`). The
  swing used to start on the same frame the readout began wiping in — and the wipe itself takes
  260ms — so the player was asked to read "HIT" and watch the hit at once, and got neither. The
  beat is applied to the **gate**, not to the swing alone: the hit point bar, the damage line and
  the blow are all consequences of one roll, and delaying only the animation would have the bar
  emptying before the sword moved.

  Measured, click to swing: dice land 1060ms, total legible 1460ms, readout finished 1720ms,
  swing 2120ms, impact 2340ms. The result stands alone for about 400ms.
- **Everything a blow causes waits for the blow** (`IMPACT_SECONDS`, 220ms — a little past halfway
  through the 417ms swing clip). That gates the hit point drain *and* the drop: a creature that
  falls as the sword starts moving has died of something the player never saw land.
- **Audio carries most of the satisfaction.** Rattle at 0ms, throw at 240ms, one clack per die
  staggered 130ms apart. Every clip gets random pitch jitter; without it the clatter sounds
  canned by the third roll.
- The stakes are drawn from the first frame — you can read "ATHLETICS CHECK · DC 20" while the
  die is still in the air. That is most of the tension.

### Sound

Layer counts, measured. Every layer is scheduled on the **audio clock**, not with `setTimeout` —
a sting is layers a tenth of a second apart and timers smear them together.

| Moment | Layers | What it is |
|---|---|---|
| Move | 4 | footfalls spread across the slide, capped at four |
| Prop revealed | 2 | a catch, then a short scrape |
| Sarcophagus opens | 5 | stone grinding, the catch letting go, the slab landing, a sub under it |
| **Combat begins** | 4 | three drums and a sub |
| Attack, miss | 4 | three dice, then the blade through air |
| Attack, hit | 6 | + chop and a metal ring, 220ms later |
| Killing blow | 9 | + body, cloth and a sub, 430ms later |

- **A stinger is a transient over a weight.** The metal hits are the transient; `drop()` in
  `godot/audio/sfx.gd` is a baked falling-sine wav, because nothing in these packs is low or long
  enough to sit under an impact. Without it the combat sting is a loud clang rather than an event.
- **`rate` is doing real work.** `boom` and `thud` are only ever played far below speed — a heavy
  metal hit at a third speed is a struck bell, and the creak at half speed stops being timber and
  becomes stone under its own weight. Several "families" are the same clips at different speeds.
- **A hit is three clips and a miss is one.** The blade moves either way; what separates them is
  whether anything is there when it arrives. An explicit "miss" noise would say out loud what the
  silence already says.
- **Consequences wait for the blow** — 220ms, the same delay the hit point bar and the death
  animation use, so the chop, the bar and the drop all land together instead of on the wind-up.
- **The lid is hung off a hostile arriving**, which in M0 is the only way anything ever appears.
  If a second spawn point is ever added, this has to become a property of the prop instead.
- **`moveSeconds()` is shared** between the renderer's slide and the footstep spacing. Two copies
  of that number would drift apart the first time anyone retuned movement.

**Kenney's fighter voiceover pack was deliberately not used.** It is an arcade announcer — "FIGHT",
"ROUND 1", "FLAWLESS VICTORY" — which is the wrong genre for a crypt, and it would put a second
speaking voice against the narrator. If that flavour is ever wanted it is a whole-game tone
decision, not a sound effect.

Still silent: there is no ambient bed at all. The braziers burn without a sound.

`debug → roll d20` sends a real roll down the real path with no model in it, which is how the
feel gets tuned without burning a turn or an API key.

---

## Commands

```bash
cd server && ./gradlew run          # server on :7070
cd server && ./gradlew test         # the whole suite, including the replayed fixture session
cd server && ./gradlew run --args='--demo'    # scripted dice, reproducible
cd server && ./gradlew run --args='--generate 7'   # boot a seeded generated room
cd server && ./gradlew recordFixture            # re-record crypt-fight.jsonl after a schema bump
cd godot && godot .                 # the Godot editor
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit   # godot tests
```

A log written at an older `Event.SCHEMA_VERSION` is **refused, never upgraded**. After a schema
bump, `./gradlew recordFixture` regenerates the fight fixture; copy a new played session into
`docs/evidence/` (and the suite) rather than trying to migrate the old one.

Replaying a session — no network, no key, exit 0 on an identical event stream:

```bash
cd server && ./gradlew run --args='--replay ../docs/evidence/session-m3-traversal.jsonl'
```

Every session writes itself to `server/sessions/` (gitignored). A session worth keeping gets
copied into `docs/evidence/` or `server/src/test/resources/sessions/` and replays on every build.
**That is the workflow M2 built and M3 used:** when something goes wrong in play, the file that
proves it is already on disk.

## Secrets

`VENICE_API_KEY` and `ELEVENLABS_API_KEY` come from a gitignored `.env`.
Never commit a key. Never log one.

## Agent skills

This repo is configured for the [engineering skills](https://github.com/mattpocock/skills).
`/grill-with-docs` and `/implement` are the entry points; `/tdd`, `/triage`, `/wayfinder`,
`/domain-modeling` and `/review` are reached from there or invoked directly.

### Issue tracker

Beads (`bd`), local to this repo — not GitHub Issues, even though the GitHub remote exists.
Every skill that says "publish to the issue tracker" or "fetch the relevant ticket" means `bd`.
See `docs/agents/issue-tracker.md`. Workflow reference: `.agents/skills/beads/SKILL.md`, or
`bd prime`.

### Triage labels

The five canonical triage roles map to beads labels of the same name, applied with `bd label add`.
See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. Read them before exploring.
See `docs/agents/domain.md`.

### Where this file sits

`AGENTS.md` is the source of truth for project rules. `CLAUDE.md` imports it and adds nothing
but build commands — edit this file, not that one. `CONTEXT.md` is the glossary, not a second
rules file: when the two overlap, this file wins.

---

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
