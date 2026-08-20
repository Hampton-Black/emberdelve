# M0 — Feel evaluation

**The deliverable of the milestone.** `m0-build-plan.md` §11 says the gate is not a passing test
suite but an honest answer to one question, asked of a person who is not the author:

> When I type a sentence and hear a voice respond while the world visibly changes in front of me,
> does it feel like a Dungeon Master is running a game for me?

**Status: PASS, signed 2026-08-20 against three logged play sessions, the last on HEAD. See §6.**

---

## 1. What was under test

Three sessions were played end to end by a human on 2026-08-20. All three server logs are
preserved in `docs/evidence/`, because they are the only unbiased sample the milestone produced
and two of them were otherwise living in a temp directory.

| | Session A | Session B | Session C |
|---|---|---|---|
| Log | `evidence/session-a-solo.log` | `evidence/session-b-playtest.log` | `evidence/session-c-head.log` |
| Player | the author | a friend, first time, no explanation given | the author |
| Build | pre-fix | pre-fix | **HEAD `cbaa2f1`** |
| Typed turns | 7 | 5 | 2, then a fight |
| Outcome | — | goblin killed | **Roderick killed by Vessk** |
| `DM_MODEL_TOOLS` | `qwen3-next-80b` | `qwen3-next-80b` | `qwen3-next-80b` |
| `DM_MODEL_PROSE` | `venice-uncensored-role-play` | same | same |
| Voice | ElevenLabs Flash v2.5, server-side | same — 38 lines, 33 narrator / 5 goblin | same |

Sessions A and B predate the four consistency fixes. **Session C is the confirming run on HEAD**:
none of the four faults those commits addressed recurred, and two new ones appeared — see §4.4.

---

## 2. Acceptance script (§2), step by step

| # | Step | Status |
|---|---|---|
| 1 | Room renders: braziers, sarcophagus, door, fighter token | **Observed.** T3/T4 |
| 2 | Narrator describes the room unprompted as the scene fades in | **Observed.** 772ms to first token, 1.48s total, 184 chars |
| 3 | "I examine the sarcophagus" → carvings described | **Observed.** Session B turn 1 |
| 4 | "I push the lid" → check → dice → goblin appears on the grid | **Observed.** Session B turn 2 — heave, roll, spawn, all in one turn |
| 5 | Combat begins: camera pulls, initiative, UI switches | **Observed.** T12 |
| 6 | Click to move, click to attack, HP bar updates | **Observed.** T10 |
| 7 | Goblin's whole turn in one narration call, spoken | **Observed.** 894ms to first token, 2.1s total, 289 chars |
| 8 | Player kills the goblin, aftermath narrated, back to exploration | **Observed.** Session B, final beat: *"His twitching legs slowly still."* |
| 9 | "I open the north door" → answered in fiction, not an error | **Implemented, not observed.** `dm.md` carries the sealed-and-impassable rule; no logged session tried a door |

Steps 1–8 are the graded ones. All eight were reached by a first-time player in a five-minute
session, unassisted.

---

## 3. Latency, measured in play

**Recorded, not graded.** The budgets below were written on a text-chat mental model, before there
was a real voice pacing the scene. In play, with slow natural narration, the longer waits read as
*impactful pauses in a game* rather than as a system being slow — judged twice, once after each
session. Latency is not what breaks immersion here. §4.3 is what breaks immersion here.

Kept because the numbers are cheap to keep and a regression should still be visible. Do not spend
M1 on them unprompted.

| Path | Budget | Measured | |
|---|---|---|---|
| First mechanical feedback (dice in the air) | < 1.2s | 1.09–2.51s, median ~1.3s | **marginal** |
| First spoken word, gap filled by dice | < 8s | 4.0–10.2s end to end, median ~6.9s | **marginal** |
| First spoken word, nothing on screen | < 2.5s | 2.5–3.6s in the healthy cases | **missed — see §4.1** |
| Enemy round resolved and narrated | < 4s | ~2.1s to narrate after resolution | **met** |
| TTS, per line | — | 152–486ms, median 315ms | |

Two tail events, both on the mechanics model, both silent while they happened: a **15.5s** tool
phase that ended in zero tool calls (Session A turn 4) and a **14.3s** one (Session B turn 5).
`qwen3-next-80b` has the same bimodal shape that disqualified `qwen3-coder-480b` — rarer, but
present. This is the single biggest latency risk carried into M1.

---

## 4. The two questions T13 owed an answer to

### 4.1 How often is a turn tool-free?

**Answer: 5 of 12 typed turns — 42% — produced no visible mechanical feedback at all.**

First, a correction to the instrument. `TurnMetrics` logs the counter as *"n/m turns used dice"*,
but it increments on `toolCalls() > 0` — which counts a turn whose only tool call was **rejected**,
and a turn whose call produced no dice. Read against `FIRST FEEDBACK`, the honest number is lower
than the counter reports: the counter says 8/12, the log says 7/12 saw anything happen.

The five turns with nothing on screen, and what the player sat through:

| Turn | Tool phase | Prose first token | Silence before the first *token* |
|---|---|---|---|
| A t5 | 1432ms | 783ms | 2.2s |
| A t7 | 1396ms | 949ms | 2.3s |
| B t1 | 1639ms | 960ms | 2.6s |
| B t4 | 5108ms (1 call, rejected) | 924ms | 6.0s |
| A t4 | 15503ms (0 calls) | 939ms | 16.4s |

The last column is silence before the first *token*, which is a **floor**. The first spoken word
comes later still: the first sentence has to finish streaming before TTS is called, and TTS itself
measured 152–486ms. The healthy turns land around 2.5–3.6s against a 2.5s budget.

**The finding is structural, not a tuning problem.** The mechanics phase runs *in front of* the
prose phase, so a turn that requests no tools pays the entire tool latency as dead air and buys
nothing with it. Rejected calls are the same silence with a wasted round trip behind it.

This does not sink the design — 58% of turns do get dice, and on those the gap is covered exactly
as intended.

**And the uncovered 42% turned out not to matter in play.** Both players sat through those waits
without reporting them; neither complaint from either session was about speed. A spoken DM sets a
slower clock than a text box does, and a pause before the voice starts reads as the DM thinking,
which is a thing a real DM does. Recorded as an architectural fact, not a defect: if M1 ever wants
it back, the two candidate fixes are a speculative prose call started in parallel and discarded if
tools fire, or a visible thinking beat. Neither is worth doing on the strength of this evidence.

### 4.2 Is ~20 seconds of speech per turn too long?

**Answer: typical turns are fine; the ceiling is at the limit.** Session B, the only session with
per-line logging, at the measured ~20 characters per second of speech:

| | Chars | ≈ Speech |
|---|---|---|
| Opening | 184 | 9s |
| Typed turns | 164 / 208 / 218 / 492 / 492 | 8s / 10s / 11s / 25s / 25s |
| Combat beats | 289 / 202 | 14s / 10s |
| **Mean typed turn** | **315** | **~16s** |

Mean is comfortably under the 393-char figure the terse prompt rule achieved. Both 492s are worth
naming: one was the goblin-spawn turn — the largest legitimate beat in the script, and 25 seconds
is arguably what it deserves — and the other was the verbatim-replay bug, since fixed in `cbaa2f1`.

**No change recommended for M0.** Re-measure once the repeat fix has a real session behind it; if
the mean holds near 315 the length rule is doing its job.

### 4.3 The metric T13 did not know it was looking for

**Multi-turn consistency is the gate, and it is the only thing that has broken immersion in any
session.** No player has yet complained about a wait. Every complaint, across all three, was the
DM contradicting a world it had already established. From A and B:

| What the player saw | Why it breaks the spell |
|---|---|
| An earlier turn replayed **character for character**, mid-fight — the goblin climbing out of a box it had already climbed out of | The world stopped having a history |
| *"Over the scratching, you hear a thin scratching sound"* | Read as a script being recited, not a scene being watched — the friend's words: *"trying overly hard to stick with a script"* |
| System-prompt text spoken into the scene | The seams of the machine, out loud |
| An empty quotation attributed to Roderick; the goblin's arrival line read in the narrator's voice | The cast stopped being distinct people |
| The goblin described as turning into a hotdog, then fighting on unchanged | Narration wrote a world change nothing could back |

Four of the five are fixed (`cbaa2f1`, `ba72be6`). The fifth is structural and, in this instance,
funny.

**This is the axis Session C should be judged on**, and the one M1 should spend its budget on —
the reconcile pass, closed enums, repeat detection, speaker attribution. A DM that is slow and
consistent is a DM. A DM that is fast and contradicts itself is a text generator.

### 4.4 Session C — the confirming run, and what it turned up

The four fixed faults stayed fixed: no replay, no prompt text in the scene, no empty quotation, and
the goblin held its own voice across a four-line exchange with narrator prose in between. Two new
consistency faults appeared, both reported by the player as "it confused Roderick and Vessk."

**1. A combat beat was narrated backwards.** The engine handed the narrator
`[Vessk hits Roderick for 6 damage., Roderick is now wounded.]` and what was spoken was *"You jerk
Vessk off his feet and drive him backwards over the sarcophagus"* — attacker and victim swapped,
the player's wound never mentioned, the hit point bar draining under a scene describing the
opposite. `COMBAT_BEAT` says *"Describe only what the facts state"* and lost.

Two contributors, in order of importance:

- **The previous turn had already invented a player action.** Asked only a question, the narrator
  wrote *"In two quick strides, you close the distance and grab him by the scruff of his neck"* —
  which `dm.md` forbids outright ("what they think and what they choose is theirs alone"). Once a
  grapple fiction was in `history`, the next beat continued it over the facts. **The agency rule
  and the consistency rule are the same rule:** a narrator that writes the player's choices is
  already writing a world the engine did not authorise, and the next contradiction is one beat away.
- **The beat names the player in the third person; the prose must address them in the second.**
  `Vessk hits Roderick` has to become "your side" every single time. It was mapped wrong once and
  right once, on the killing blow, in the same fight.

**2. The player's own line was spoken in the goblin's voice.** *"What else did you see in there?"*
— the player interrogating — came out of the goblin. `NarrationParser`'s marker **stays in force
until a different marker**, so `lastCreature` survived two intervening narrator lines and took the
quotation. The model should have marked the line with the fighter's id and did not.

Worth stating plainly: **the sticky marker is not a bug.** It exists because the DM kept dropping
the goblin's voice after a single line, which was worse and more frequent. Any fix has to keep a
creature's voice across its own multi-line speech while releasing it when someone else takes over.

**Also observed, both against rules that already exist in `dm.md`:** a grid coordinate read aloud
(*"scrambles out of the rubble at (2,2)"* against **"Never say a grid coordinate"**), and a state
change announced rather than narrated (*"A fight breaks out between you and Vessk"*).

Latency for the record, unremarkable and unremarked on: opening 633ms to first token; turns at
1054ms and 1085ms to first feedback; combat beats at 850ms and 953ms.

---

## 5. The five subjective questions (§11)

*Answered from what the sessions showed. The last one is the gate and only the players can sign it.*

**1. Does the room feel like a place, or like a diagram?**

— *fill in.*

**2. When the goblin appears, is it a surprise or a state update?**

Evidence says surprise: the spawn turn drew the longest narration of the session and the goblin's
arrival line went out in its own voice. Confirm against your own memory of watching it — *fill in.*

**3. Does the narrator sound like a person running a game, or like a text-to-speech demo?**

— *fill in.* Note the run was on `venice-uncensored-role-play`, which produced *"F-f-fuck! My
clothes!"* — the tone this question is really asking about.

**4. Does landing a killing blow feel good?**

Nine audio layers, a 220ms impact beat, and the narration in §2 step 8 landed on it. — *fill in.*

**5. After the fight ends — do you want to type another thing?** *(the real question)*

The friend kept typing, went off-script far enough to turn the goblin into a hotdog, and finished
the fight. Reported afterwards as *"funny and enjoyable"* and *"still liked it and was impressed."*
That is a yes from a first-time player who had nothing explained to them.

**Countersigned by the author, 2026-08-20:** *"I've had fun and want to see what more this project
can do."* Questions 1–4 were left unanswered on purpose. They are diagnostic questions, useful for
finding out *why* a session did not land; this one landed, and question 5 is the one the milestone
turns on.

### What the players actually complained about

All four are fixed, and Session C confirmed the fixes in play.

| Reported | Fixed in |
|---|---|
| "It kinda broke at the end" — the DM replayed an earlier turn verbatim | `cbaa2f1` |
| "Trying overly hard to stick with a script" — repeated phrases within a sentence | `cbaa2f1` (frequency/presence penalties) |
| The DM quoted the system prompt into the scene | `ba72be6` |
| An empty quotation attributed to Roderick; the goblin's arrival line read by the narrator | `cbaa2f1` |

---

## 6. Verdict

**Unsigned — but the evidence is now complete.** Session C supplied the confirming run on HEAD
that this section previously said was outstanding. The four fixed faults stayed fixed. Two new
consistency faults appeared (§4.4), and the honest question is no longer *"has it been tested"* but
*"do those two block the gate?"*

The case they do not: the loop held. A first-time player reached every graded step of the §2 script
unassisted and enjoyed it; the author was killed by the goblin on the confirming run, which is the
fight working. Neither new fault is a crash, a silent drop, or an unrecoverable state — both are
the narrator getting a detail wrong in a scene that otherwise ran.

The case they do: an inverted combat beat is the narrator contradicting authoritative state, which
§4.3 names as the one thing that reliably breaks immersion. M0 exists to find out whether this
feels like a DM running a game, and a DM that tells you that you threw the goblin while the goblin
is wounding you is, in that moment, not running the game.

My read: **pass the gate, fix the two faults inside M0.** Both are small and both are prompt-and-
parser work, not architecture — which is exactly what §11 says to spend a feel pass on before
concluding anything about the concept. The concept is not in question; the narrator's grounding is.

> **M0 gate: PASS** — signed 2026-08-20. The loop holds: a first-time player reached every graded
> step of the §2 script unassisted and enjoyed it, and the author wants to keep building on it.
> The two faults in §4.4 are the narrator's grounding, not the concept, and they are prompt-and-
> parser work. M0 has answered the question it was built to ask.

---

## 7. Carried into M1

Findings, not tasks. Recorded so they are not rediscovered.

- **Multi-turn consistency is the metric** (§4.3). The most valuable finding of M0, and the one
  M1's budget belongs to.
- **Tool-free turns pay the mechanics latency in silence** (§4.1). Architecturally true, judged
  in play to be a non-problem. Recorded so nobody rediscovers it and mistakes it for urgent.
- **`qwen3-next-80b` has a latency tail** — 14–15s stalls, no error, full quota. Any single-provider
  MoE should be assumed to do this.
- **`TurnMetrics` mislabels its counter** — "turns used dice" counts turns that requested tools,
  including rejected ones. One-line fix; left alone here so the numbers above match the logs as
  they were written.
- **Rejected tool calls are pure cost** — a wasted round trip and a silent player. `start_combat`
  during combat was the only offender and is no longer offered; the pattern will recur as tools grow.
- **The narrator can describe a world change nothing can back** — the hotdog. Harmless here, the
  same class as the goblin that never spawns. The reconcile pass correctly declined to invent a
  mechanism for it.
- **Attacking during an enemy-turn narration drops the kill's own narration.** Still open. Decide
  whether the board locks during narration or the beat queues.
- **Combat beats are stated in the third person and narrated in the second** (§4.4). `Vessk hits
  Roderick` has to become "your side" on every beat, and the re-mapping is where the narrator
  inverted attacker and victim. Whatever M1 does with beats, hand the narrator the perspective it
  is going to write in.
- **A creature's voice is sticky for the whole turn** (§4.4). It has to be, or the goblin loses
  its voice after one line. But it means any later unmarked quotation — including the player's —
  inherits it.
- **No ambient bed.** The braziers burn silently.
