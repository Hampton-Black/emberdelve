# Emberdelve — Agent Rules

AI Dungeon Master. Java backend, React + Three.js frontend.
**Current milestone: M0 — gate PASSED 2026-08-20.** A two-week vertical slice that existed to
answer one question: *does this feel like a Dungeon Master running a game?* It does. The verdict,
the evidence and the two faults that survived it are in `docs/m0-evaluation.md`.

Read `docs/m0-build-plan.md` before changing anything. `docs/ai-dm-system-design.md` is the
long-range design; M0 deliberately contradicts parts of it, and where they disagree **M0 wins**.
`docs/m0-evaluation.md` is the gate itself — what three played sessions measured, the signed
verdict, and what M0 hands to M1.

---

## The point of M0

M0 is a **gate, not a foundation.** Everything that contributes to *feel* is in scope.
Everything that contributes to *correctness, scale, or persistence* is out of scope and gets
hardcoded. This is not technical debt — it is the point. **M0 code is expected to be thrown away.**

If you find yourself making something general, correct, or scalable, stop and re-read this section.

---

## Invariants — do not violate these

Each one is expensive to unwind. They are not style preferences.

1. **The server is authoritative.** The client never computes a roll, a hit, a legal move, or a
   death. It renders what it is told.
2. **No singleton player.** `List<PartyMember>`, always — even though M0 has exactly one member.
   Every action carries an `actorId`.
3. **Game state never lives in React state.** It lives in the Zustand store. React and Three.js
   both subscribe. Violating this causes canvas re-creation bugs that are very hard to diagnose.
4. **The Three.js renderer lives in a `useRef`** and is created exactly once.
5. **`RollResult.faces` is a `List<Integer>`.** Never collapse it to a total. The UI animates
   individual dice and crits key off the natural d20, not the sum.
6. **Roll results are logged as events.** Never plan to replay by re-rolling from a seed.
7. **All LLM-facing enums are closed and validated server-side.** No free-form string from the
   model reaches the engine. Tools use `strict: true`.
8. **No `localStorage` / `sessionStorage`.** In-memory only.
9. **Modern Java only** — records, sealed interfaces, pattern matching, virtual threads.
   No Spring. No `AbstractXFactory`. No mutable POJOs with getters and setters.
10. **Do not add tools, entity types, props, or rules** beyond what is listed below.

---

## Locked decisions

Resolved in a design review before implementation. Do not silently revisit these.

| Area | Decision |
|---|---|
| Assets | Kenney CC0. Architecture from Modular Dungeon Kit, tokens from Graveyard Kit (Mini Characters also installed). Props still procedural. |
| LLM provider | **Venice.ai**, OpenAI-compatible, `https://api.venice.ai/api/v1`. One key, one endpoint, 100+ models. |
| LLM model | Two config strings, never literals: `DM_MODEL_TOOLS` (fast, reliably tool-calling) and `DM_MODEL_PROSE` (best writer). Measured picks and their disqualifications are below. |
| Narration | **Text channel**, not a tool. Inline `[[speaker]]` markers, validated against live entities. |
| TTS | ElevenLabs Flash v2.5 behind `TtsClient`. Web Speech API is the working placeholder. |
| Build | Gradle + Kotlin DSL. |
| Determinism | Real DM discretion. `ScriptedDiceRoller` behind `--demo` for reproducible tuning runs. |
| Combat VO | **Dramatic beats only** — kills, crits, and the goblin's turn. Ordinary hits resolve instantly. |
| Camera | Four fixed isometric corners, 90° snap (Q/E). Never free orbit — it breaks the pixel look and grid picking. |

### Deviations from `docs/m0-build-plan.md` as written

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

## Model selection — measured, not guessed

Venice exposes `supportsFunctionCalling` and `supportsResponseSchema` per model. **Require
both.** But the capability flag is not the same as the behaviour: several models that advertise
function calling simply narrate instead of calling anything.

Measured against the real tool schema, one round, "heave the sarcophagus lid open":

| Model | TTFT | Calls `roll_check` correctly? |
|---|---|---|
| `qwen3-coder-480b-a35b-instruct-turbo` | 795ms | yes |
| `qwen3-next-80b` | 834ms | yes |
| `qwen3-235b-a22b-instruct-2507` | 1605ms | yes |
| `zai-org-glm-5-2` | 1924ms | yes |
| `deepseek-v4-flash` | 2437ms | yes |
| `claude-opus-5` | 2679ms | yes |
| `grok-4-6` | 5042ms | yes |
| `venice-uncensored-1-2` | 728ms | **no — narrates instead** |
| `venice-uncensored-role-play` | 914ms | **no — narrates instead** |
| `mistral-small-3-2-24b-instruct` | 1017ms | **no — narrates instead** |

Two findings that should survive this milestone:

1. **Reasoning models cannot hit the first-feedback gate.** Reasoning-on models land at 1.9–5.0s to
   first token; reasoning-off models land at 0.7–1.6s. The split is clean. Worse, the agentic
   loop multiplies it: a turn with three tool calls on `grok-4-6` measured **44 seconds**.
2. **The uncensored and roleplay models are the ones that cannot drive this architecture.**
   They write the best prose and never call a tool — the exact silent failure this design is
   most vulnerable to.

Most `e2ee-*` models report `supportsFunctionCalling: false` and are incompatible outright,
despite being the strongest privacy tier.

### Finalists, 4 samples each

| Model | TTFT samples | Tools | Prose |
|---|---|---|---|
| `qwen3-coder-480b-a35b-instruct-turbo` | 563 / 653 / 603 / 621ms | 100% valid | good, stays in-world |
| `qwen3-next-80b` | 540 / 771 / 577 / 586ms | 100% valid | **invents props** |
| `deepseek-v4-flash-0731-fast` | 1195 / 1364 / 1599 / **38478**ms | 100% valid | good, stays in-world |

**Current pick: `DM_MODEL_TOOLS=qwen3-next-80b`, `DM_MODEL_PROSE=` your best writer** —
`venice-uncensored-role-play` is what the played sessions ran on and what the tone in
`m0-evaluation.md` was judged against; `claude-opus-5` is the `.env.example` default.

**`qwen3-next-80b` has a tail too, found in play, not in the benchmark:** 14.3s and 15.5s tool
phases in two logged sessions, no error, full quota. Rarer than the 480b's and it survives the
pick, but assume *any* single-provider MoE does this and watch `FIRST FEEDBACK` for it.

`qwen3-coder-480b-a35b-instruct-turbo` looked like the winner on first measurement and is
**disqualified**. Over one session it went 563ms → 42s → 66s → 621ms → 34s, with full rate-limit
quota remaining and no error. Bimodal latency is worse than consistently slow, because you cannot
design around it. Assume any very large MoE on a smaller provider may behave this way.

**Benchmark discipline learned the hard way:** a single-shot benchmark against a multi-tenant
inference provider measures a moment, not a steady state. Sample repeatedly, and across time,
before believing a number. Every early figure in this file was collected in one burst and the
model rankings did not survive contact with a second burst.

Two disqualifiers found by measuring rather than reasoning:

- `deepseek-v4-flash-0731-fast` throws occasional **38-second stalls**. Its median is fine; its
  tail is not, and one freeze mid-session ruins a five-minute demo.
- `qwen3-next-80b` **hallucinates props** — it invented "a small silver disc, half-buried in ash"
  and a trail of footprints, neither of which exists in the room. It also misused a `[[fighter]]`
  speaker marker for plain narration. Fast and tool-correct, but it fabricates world state, which
  is the one thing the closed-enum design exists to prevent.

---

## Hardcoded shortcuts — implement exactly these, do not "improve" them

| Shortcut | Value |
|---|---|
| Room | One, `content/rooms/crypt.json`, hand-authored |
| Party | `List<PartyMember>` containing one member |
| Fighter | AC 16, HP 12, +5 to hit, 1d8+3 damage, speed 30ft, STR +3 |
| Goblin | AC 15, HP 7, +4 to hit, 1d6+2 damage, speed 30ft |
| Attack resolution | `d20 + bonus >= AC`; nat 20 doubles dice. No crit tables, no resistances. |
| Skill check DCs | A 5-value enum only: 5 / 10 / 15 / 20 / 25 |
| State | `ConcurrentHashMap` behind `GameRepository` |
| Event log | In-memory `List<Event>` behind the same interface |
| Voices | Two hardcoded voice IDs in config |
| System prompt | One string in `prompts/dm.md`, loaded at boot |
| Context | Full transcript, no compaction |
| Errors | No recovery — log and surface a visible error toast |
| Tests | Dice and attack resolution only |

## LLM tools — exactly these four

`roll_check` (skill + difficulty enums), `reveal_prop` (per-room closed enum of hidden prop ids),
`spawn_entity` (kind: `goblin` only), `start_combat`.

Every enum is closed and validated server-side. Invalid calls are rejected with a structured
error; the model retries once, then the turn degrades to narration-only.

---

## Anti-goals

These are the things most likely to eat week two.

- **Do not build a rules engine.** ~40 lines of attack resolution. If you are modeling conditions, stop.
- **Do not tune lighting and post-processing for more than one evening.** Timebox it. This is the
  single largest time sink in the project and it will consume as much as you give it.
- **Do not add a second room.** The impulse will be strong. One room.
- **Do not build save/load.** Restarting the process is fine.
- **Do not optimize anything.** One room, two entities.
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

### Why these are not the numbers in `docs/m0-build-plan.md`

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

Both questions are answered in `docs/m0-evaluation.md` §4, against 12 typed turns of real play
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

## The DM is two models, not one

Phase 1 — **mechanics**, `DM_MODEL_TOOLS`. Decides tool calls; any prose it writes is discarded.
This is the phase the &lt;800ms budget applies to, because it is what puts dice on the table.
Wants a fast, non-reasoning, reliably tool-calling model.

Phase 2 — **narration**, `DM_MODEL_PROSE`. Writes the prose with the engine's real results as
context, *while the dice are still animating*. That concurrency is what buys it permission to be
slow, so pick the best writer you can afford.

Two consequences worth knowing:

- A model that writes beautifully but cannot call tools is now **usable** — it just goes in the
  prose slot. `venice-uncensored-role-play` was disqualified outright before the split.
- A model that calls tools reliably but hallucinates props is now **usable** — it never writes
  narration, so it cannot invent anything. `qwen3-next-80b` was disqualified before the split.

Narration stays on exactly one model. Splitting *narration* across models makes tone drift
audible between turns (design doc §10); splitting mechanics off does not.

Measured end-to-end, `--demo`, "heave the sarcophagus lid open":

| Config | First dice | First word | Total |
|---|---|---|---|
| Single model (`grok-4-6`) | — | — | 44,000ms |
| Split, degraded tools model | 33,908ms | 45,616ms | 48,323ms |
| Split, healthy tools model | **1,025ms** | 7,394ms | 8,860ms |

## Characters

Tokens are Kenney character models on a small base, in `client/public/assets/kits/characters/`.
Every model across these kits carries the **same 32-clip rig** — `idle`, `walk`, `die`,
`attack-melee-right` and so on — so swapping a character is one line in `MODELS` (`tokens.ts`)
and nothing else.

Three things that are not obvious and cost an hour each if forgotten:

- **Each kit needs its own folder.** Every Kenney GLB references `Textures/colormap.png` by
  *relative* path, and the mini and graveyard kits ship **different** colormaps under that same
  name. Putting both kits in one directory silently renders one of them in the other's palette.
- **Figures are deliberately oversized** — about 1.25 world units on a 1.0 square. At 480px
  internal width a to-scale human is ~24 pixels and reads as a smudge. Oversizing the figure
  relative to its base is what tactical RPGs do, for exactly this reason.
- **Characters carry a faint emissive of their own colormap.** The crypt is genuinely dark away
  from the two braziers, which is right for the room and wrong for the figures standing in it.
  This lifts tokens off the floor without touching scene lighting — do not "fix" it by raising
  the ambient.

Current cast: the **keeper** (a gravedigger in a coat and hat) as the fighter, the **zombie** as
the goblin. `graveyard/` also holds `-skeleton`, `-vampire` and `-ghost`. Kenney's `mini/` set is
contemporary — police officer, businessman, doctor — and is kept only because it is rig-identical
and therefore a one-line swap.

Graveyard models are **node-animated** (`skins: 0`, six part meshes) rather than skinned. Both
kinds work through the same `AnimationMixer` path and both need `SkeletonUtils.clone` — a plain
`Object3D.clone()` shares the rig, so two tokens of one model would animate in lockstep.

Origins differ between kits — mini models stand on y=0, graveyard models are centred on the hips
— so `Token` measures the bounding box rather than keeping a table of offsets.

---

## Voice

Narration is spoken through an **ordered queue** (`client/src/audio/narration.ts`): one line at a
time, strictly in arrival order, because two sentences talking over each other is the worst thing
a spoken DM can do. A new turn silences whatever is left from the last one.

Ordering against the dice is not handled there — it is inherited, because the queue is fed from
the gated path in `store.ts`.

**The voice also paces the transcript.** The model streams a whole turn in about three seconds
and the voice takes twenty to say it, so text appended on arrival has the player speed-reading
ahead of the narrator. Each line is committed to the transcript by the queue, as it starts being
spoken — measured within 1ms of the utterance. No words-per-minute constant to tune, and it stays
correct if the voice changes. With voice off, lines reveal on arrival, which is right for reading.

Dropped lines are still revealed. Interrupting stops the *speech*, not the *record* — text that
vanished from the transcript because nobody got round to saying it would be a bug.

`VoiceBackend` is the seam. Web Speech today (free, local, and the dev default); ElevenLabs Flash
v2.5 next, synthesised **server-side** because the key must never reach the browser. `speak()`
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
the reconcile pass correctly declined to invent a mechanism and the goblin went on fighting. Same
class as the goblin that is described but never spawns — harmless only because this instance was
funny. Not worth a prompt line on a prototype.

**`TurnMetrics` mislabels its counter** — see the latency section. One line; left alone so the
numbers in `m0-evaluation.md` match the logs as they were written.

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
  settles it shows `result.faces[i]`. `client/src/dice/tumble.ts` is pure and holds this rule
  in one line. No physics library — see the design doc §7 on why most of them are the wrong
  direction.
- **Narration is held until the dice land** (`throughGate` in `store.ts`). Timer-based, not
  driven by the tray component, so the gate still opens if the tray never mounts. Today the
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
| **Combat begins** | 6 | two struck booms, a drawn blade, then the dice |
| Attack, miss | 4 | three dice, then the blade through air |
| Attack, hit | 6 | + chop and a metal ring, 220ms later |
| Killing blow | 9 | + body, cloth and a sub, 430ms later |

- **A stinger is a transient over a weight.** The metal hits are the transient; `drop()` in
  `sfx.ts` synthesises the weight as a falling sine, because nothing in these packs is low or long
  enough to sit under an impact and it is a dozen lines of Web Audio rather than a hunt for a
  sample. Without it the combat sting is a loud clang rather than an event.
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
cd server && ./gradlew test         # dice, attack resolution, combat legality
cd server && ./gradlew run --args='--demo'   # scripted dice, reproducible
cd client && npm run dev            # vite on :5173
```

## Secrets

`VENICE_API_KEY` and `ELEVENLABS_API_KEY` come from a gitignored `.env`.
Never commit a key. Never log one.
