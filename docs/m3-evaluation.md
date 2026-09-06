# M3 — Traversal evaluation

**The deliverable of the milestone.** Spec §10 is one question in five criteria:

> Leave a room. Do things elsewhere for long enough that the transcript window has forgotten the
> first room entirely. Come back. Is it as you left it, and does the DM know that?

**Status: PASS, signed 2026-09-06 against one logged session of 19 typed turns on `m3-traversal`. See §7.**

---

## 1. What was under test

One session, played end to end over websocket (Godot not in the path) on 2026-09-06, against a
fresh server on the M2 model split, so a difference here is a difference in M3 and not in the
model pick. Crossings were `enterExit` clicks — the playable demo — not hoped-for `use_exit`
calls. Typed turns waited for `narrationEnd` before the next.

| | Gate session |
|---|---|
| Log | `docs/evidence/session-m3-traversal.jsonl` (copy of `server/sessions/20260906-160343-d5ef5f0a.jsonl`) |
| Player | websocket client, scripted against spec §10 |
| Build | `m3-traversal` at `72a3753`, plus this evaluation |
| Typed turns | **19** |
| Crossings | **4** (`crypt→gallery`, `gallery→crypt`, `crypt→gallery`, `gallery→crypt`) |
| Outcome | Niche found and left; crypt tallies still half-cut; Vessk never spawned; no combat |
| `DM_MODEL_TOOLS` | `qwen3-next-80b` |
| `DM_MODEL_PROSE` | `gemini-3-8-flash` (`reasoning_effort=low`) |
| Voice | not in the path (headless) |

The warm-check called the prose model sluggish (5.3s ping) and the tools model healthy (1.4s).
Turns still completed. No Venice error reached the socket.

---

## 2. The gate, point by point

| # | Criterion | Status |
|---|---|---|
| 1 | Crosses thresholds repeatedly, returns at least twice | **Held.** Four `PartyMoved`. Returned to the crypt twice and to the gallery once. |
| 2 | A return only counts after ≥7 turns elsewhere | **Held.** Gallery dwell **8** typed turns (turns 2–9) before the first return. Crypt dwell **7** typed turns (turns 10–16) before crossing back. `WINDOW_TURNS = 6` had dropped the first room both times. |
| 3 | "As you left it" names a specific thing in a specific state | **Held.** Turn 17: the west-wall niche still open, **six clay tokens** still stacked, the top one still **notched three times**, the **rectangular soot** of a lifted casket still on the floor. All three were asserted on turn 9. |
| 4 | Nothing the DM says contradicts the room it is in, in either direction | **Held, with notes in §4.** No crypt furniture described on gallery walls; no gallery procession described as living in the crypt. Returns were returns, not first arrivals. |
| 5 | The session replays offline, across rooms | **`replayed 21 events: identical`.** Pinned as `theGateSessionReplays`. |

---

## 3. What happened

Nineteen typed turns, plus the opening, in order.

**Crypt (opening + turn 1), then click north.**

The opening named the green braziers, the defaced sarcophagus with fresh scrape-marks, and the
north slab "sealed flush without a latch or ring." Turn 1 looked around: unfinished tally on a
pillar, then a nat-20 investigation found the votive alcove (lamp and grave-cloth). `roll_check`
twice on one look — perception trivial, then investigation medium — the M2 carry-out still firing.

Click `door-north`. Board cut to the gallery. No narration on the click; turn 2 consumed the
arrival.

**Gallery, eight typed turns (2–9).**

1. *(turn 2)* Look down the aisle. Dry chill instead of crypt damp; four pillars; scars on the
   near pair; south-walking procession on the far pair, last figure headless; dead-end ash
   brazier. **`reveal_prop(niche)` fired here**, before anyone touched the west wall. Narration
   did not name the niche or the tokens.
2. Walk north along the aisle. `move_entity` to (5,8). Footsteps carry the length and come back.
3. West-side pillars. Dust, blurred faces, southward march.
4. Hand along the west wall. Facing stone at knee height; niche; **six clay tokens** stamped with
   the procession. Fact asserted on `niche`.
5. Proud tiling, different grout. Tokens still there; mortar on the boots. Mechanics tried
   `reveal_prop(key-iron)` and was rejected — the closed enum held.
6. Search the hollow. No false bottom. Tokens still stacked.
7. Pry the facing stone. `move_entity` to (0,9), the niche square.
8. Look at the tokens. Top disc **notched three times** by a file; niche floor bears **soot in
   the shape of a small casket's base**.

Click `door-south`.

**Crypt, seven typed turns (10–16).**

1. Look around. "Familiar shadows." Door "now stands unbolted at your back." Nothing else stirred.
2. Eleven tallies, last incomplete — still there, sheared mid-cut, stone powder on the edge.
3. Sarcophagus: lid shut, scratches from beneath, silence inside.
4. Trough? **There is none.** Asserted. The M2 empty trough was a different session's invention
   and was not revived.
5. Ear to the lid. Perception: a dry scrape of iron on granite from inside, then a creature
   drawing breath. **Asserted. Nothing spawned. Combat did not start.**
6. Walk the walls, remembering the gallery. Stone unchanged; a draught from the open north door.
7. Tallies, sarcophagus, trough again. Eleventh mark still sharp; box still sealed, "hiding
   whatever breathes within"; still no trough.

Click `door-north` again.

**Gallery return (turns 17–18).**

Turn 17 asked whether the niche and tokens were as left. They were: crooked stone, six tokens,
three notches, soot rectangle. That is criterion 3.

Turn 18 asked whether the stamps still matched the pillars. A low perception roll: dust blurs the
figures into smudges. The tokens are not withdrawn; the stamps are just not readable this pass.

Click `door-south`.

**Crypt return (turn 19).**

Eleventh tally "exactly as you left it." From inside the sarcophagus, "nothing stirs to answer
your tread." Vessk was never on the grid. The body (there is no body) is where it was left:
inside a shut box the board cannot show.

---

## 4. Consistency

This is the question. The log, not the memory.

**Across the threshold, it held.** The gallery is dry, processional, and cold; the crypt is green
and damp. Returns named what had already been established rather than re-introducing the room.
Turn 10's "familiar shadows" / "nothing else has stirred" and turn 17's notched tokens are the
shape the gate asked for.

**No bleed of the kind the spec feared.** Crypt tallies, trough, green fire, and sarcophagus did
not appear on gallery walls. Gallery processions and clay tokens did not migrate downstairs.
Mentions of the *other* room were directional (a draught from the gallery, a dry chill replacing
the crypt's damp), which is contrast, not furniture in the wrong place.

**The board got ahead of the mouth once.** Turn 2 revealed the niche as a prop while the prose
described pillars and an ash brazier. Turns 5–9 then found it in fiction. A Godot player would
have seen a west-wall alcove appear with no sentence pointing at it. The click-path inverse —
narration walking through a door before the board cut — did not happen; crossings were
`enterExit`, and arrival prose landed in the new room.

**The creature in the box is a fact the board cannot hold.** Turn 14 heard breathing; turn 16
kept it ("hiding whatever breathes within"); turn 19 said nothing stirred. That last line is
silence on this pass, not a retraction — but nothing ever called `spawn_entity`, so the secret
the sarcophagus is authored to contain stayed prompt-text. Same class as M2's copper key:
internally consistent, not on the grid.

**Lesser, recorded.**

- Opening called the north door sealed flush. The click opened it anyway. Turn 10 called it
  unbolted, which is the `theDoor` note doing its job after the fact. The opening still described
  a door that could not open.
- An invented **lantern** lights the gallery from turn 2 onward. The votive lamp was revealed in
  the crypt alcove and never taken. Durable invention, spoken, never a prop.
- Turn 6's `reveal_prop(key-iron)` was rejected. Closed enum.
- Turn 1 rolled perception *and* investigation for one look-around. `roll_check` twice on one
  action is still happening.
- `move_entity` twice, both on turns that named a destination (walk north; pry at the west-wall
  niche). Not the wandering the reconcile rule was written to prevent. Against Task 10's **6/88**
  implied-movement baseline, 2 accepted calls in 19 typed turns, both requested.

**Did not recur.** Talking-to-yourself-starts-a-fight: no `spawn_entity`, no `start_combat`, no
`use_exit`. The mechanics phase had the second loud verb and did not walk the party through a
door they had not clicked.

---

## 5. Voice

Headless — no TTS, no Godot transcript pacing. Parser still tagged. Opening and every typed turn
arrived as narrator spans; the fighter was not given stolen dialogue. No `'s voice` leftover, no
Entities/Grid dump, no grid coordinate spoken aloud (facts used `at_square` internally; Gemini
did not read them).

---

## 6. Length and latency — recorded, not graded

Narration averaged **336 characters** a typed turn (266–443). Opening was 364 characters, 1.9s to
first token, 2.5s total.

`TurnMetrics` on the server (tools / prose first-token / total):

| | This session |
|---|---|
| FIRST FEEDBACK (when a die or reveal landed) | 1.2–2.6s |
| Tool-free first word | 1.6–2.1s tools-phase dead air, then 0.97–1.4s to first token |
| Longest tools phase | **7.8s** (turn 4) — Qwen's known tail, once |
| Prose first token | 0.97–1.4s. `low` thinking. No 9–15s stall |

The warm-check's 5.3s prose ping did not show up as 5s first tokens in play.

```
turn   1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19
chars 443 442 389 341 316 266 359 335 383 285 329 314 281 293 338 349 315 324 285
```

No climb toward the end. The window dropping the other room did not make the writer recap it.

---

## 7. Verdict

The mechanical half was already true before this session (`aCrossingReplays`,
`replayKeepsWhatWasLeftBehind`). The played half was not: nothing until today spent seven turns
in a second room and came back asking after a named thing. This one did. The niche was as left.
The tallies were as left. The session file replays 21 outcome events identical. The DM did not
bleed rooms, did not re-introduce either chamber as new, and did not walk the fighter on
narration that had not asked for a walk.

The case against: the niche appeared on the board a few turns before the mouth found it; a
creature breathed inside a sarcophagus nothing spawned; a lantern that is not a prop lit the
gallery. Those are the same class M2 signed through — reconcile recording what the board cannot
hold, and a reveal that beat the sentence. They are findings. They are not a failed return.

> **M3 gate: PASS** — signed 2026-09-06. A 19-turn session left the crypt, spent eight turns in
> the gallery, came home, spent seven, went back, and found the notched tokens where it had put
> them. Four crossings. The log replays. A room is a place you can leave and come back to. That
> was the question.

---

## 8. Carried out of M3

Findings, not tasks. The generator plan is next; these ride with it.

- **`reveal_prop` can beat the sentence.** Turn 2 put the niche on the board while the prose
  described the aisle. A look-around is enough for the tools model to cash a hidden prop. The
  player who is looking at Godot sees the thing before they are told it is there.
- **Authored secrets still have no spawn.** Listening at the lid produced a durable fact and no
  goblin. `theSarcophagus` says Vessk is in the box; `spawn_entity` is still a model decision.
  The hotdog class, now behind a door.
- **Invented light travels with you.** The gallery lantern was never a prop. It will sit in
  Established until someone contradicts it.
- **`roll_check` twice on one action** (turn 1) survived the second room.
- **`use_exit` stayed quiet** when crossings were clicks. That is the design. It is also untested
  as a typed "I go through the door" path in this session — do not take silence for a played
  typed crossing.
- **Dark neighbour unread.** This session was headless. Whether the unlit gallery through the
  crypt doorway reads as a dungeon or as a hole in the world is still a Godot judgement. Write
  **not observed**, not guessed.
- **Generated rooms still have nothing to find** (spec §12a). `PropPlacer` marks nothing hidden,
  so `reveal_prop` is never offered. The crypt has an alcove and the gallery has a niche because
  they are authored. A generated room has no such object. That is a larger hole than missing
  flavour text, and it is generator-plan work — not a reason to let the dress pass invent
  free-form secrets with no mechanism.

**M1 stays open.** Traversal was the half that had to go first. The world still cannot be made
rather than authored at dungeon scale.
