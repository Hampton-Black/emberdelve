# M2 — Spine evaluation

**The deliverable of the milestone.** Spec §11 is two halves of one question:

> Play a session at least twenty typed turns long. Nothing the DM says contradicts a fact the DM
> itself established. Capture that session, and replay it in the test suite with no network.

**Status: PASS, signed 2026-09-05 against one logged session of 30 typed turns on `m2-spine`. See §7.**

---

## 1. What was under test

One session, played end to end by the author on 2026-09-05, after a day of model shopping and a
shorter crypt-clearing loop that kept dying at six to eight typed turns. The script this time was
deliberate: walk the room first, do not heave the lid, do not leave, plant callbacks (the eleven
tallies, the scratches, the empty trough) and mention them again after the six-turn window had
dropped the first telling.

| | Session D |
|---|---|
| Log | `docs/evidence/session-m2-twenty-turns.jsonl` (copy of `server/sessions/20260905-205347-b4a16f95.jsonl`) |
| Player | the author |
| Build | `m2-spine`, with `DM_REASONING_EFFORT_PROSE=low` on the prose client |
| Typed turns | **30** (gate is 20) |
| Outcome | Vessk killed in one exchange; crypt searched; player left by the south stair |
| `DM_MODEL_TOOLS` | `qwen3-next-80b` |
| `DM_MODEL_PROSE` | `gemini-3-8-flash` (`reasoning_effort=low`) |
| Voice | ElevenLabs; 88 spoken segments, 84 narrator / 4 fighter / 0 goblin |

Earlier the same day, six shorter sessions on the same branch trained the model pick (Venice
role-play leaking the world-state footer; Gemini at default thinking waiting 9–15s for a first
word; DeepSeek-on-tools putting an 8.5s hole in front of a die). Session D is the confirming run
on the split that survived that. Those earlier JSONL files stay in `server/sessions/` and are not
the gate.

The goblin never spoke. The player did not parley. Sticky-creature-voice was not re-tested here;
player-line tagging was (four times, all correct).

---

## 2. The gate, point by point

| # | Criterion | Status |
|---|---|---|
| 1 | ≥20 typed turns | **30.** See §3. |
| 2 | Nothing the DM says contradicts a fact it established | **Held, with one overclaim recorded in §4.** Callbacks in turns 17–29 still knew the tallies, the scratches, Vessk's blade, the empty trough, and the key. |
| 3 | Capture the session | `docs/evidence/session-m2-twenty-turns.jsonl`, 222 events. |
| 4 | Replay it offline, no network | **`replayed 32 events: identical`** (outcome events only; `PlayerSaid` / `NarrationLogged` / `FactAsserted` / `ToolCallIssued` are skipped by `ReplayRunner`, as designed). The checked-in fixture `crypt-fight.jsonl` remains green under `./gradlew test`. |

The length is load-bearing. M0's sessions were 7, 5, and 2 typed turns. Today's morning loops were
5–11. Twenty is where `WINDOW_TURNS = 6` has dropped the opening, and the projection either still
knows the eleven marks or it does not. This session went to 30 and the marks were still there at
the recap.

> **Schema note, added 2026-09-05.** M3 bumped `Event.SCHEMA_VERSION` to 2, because entities now
> carry a `roomId`. This session's log is schema 1 and is therefore refused by the current build —
> refusal rather than migration is the rule (M2 spec §3). Criterion 4 above records a result that
> was true and reproducible on the day it was signed; the file remains as evidence of what
> happened, not as a fixture that still runs. The suite's replay coverage moved to
> `crypt-fight.jsonl`, which `./gradlew recordFixture` regenerates at the current schema.

---

## 3. What happened

Thirty typed turns, in order:

1. Look around (stair, pillars, rubble, north door, green fire).
2. Search the rubble; look up the ceiling hole (failed perception and investigation; two checks on one action).
3. East pillar — eleven tallies, the last incomplete, fresh grit on the floor.
4. West pillar — old wax, a slip, a loud clatter.
5. Stand still and listen (failed; own pulse).
6. Ear to the north door (nat 1).
7. Heave the door (19+5 vs DC 25; a hair's breadth, then seize).
8. Walk the walls for a latch (failed; "yields nothing").
9. Inspect the sarcophagus lid (failed; then the fresh scratches anyway).
10. Ear to the lid (failed; only the braziers).
11. Speak aloud about treasure. **Vessk spawns at (10,5) and combat starts.** He wins initiative, closes, misses; the player kills him in one swing.
12. Where did he come from? Eyes go to the eastern wall.
13. Search the body (nat 1; gauntlets smeared).
14. Heave the lid (fails; half an inch).
15. Knock and ask if anything is inside.
16. East wall, where he appeared.
17. Back to the east pillar — did the goblin carve the marks?
18. Back to the rubble — previous adventurers?
19. Other signs of previous visitors?
20. Match the lid scratches to Vessk's blade.
21. Use the blade as a pry-bar (lid tilts askew).
22. Peek inside — grey dust, nothing else.
23. Yell at the empty trough.
24. Inspect the east wall closer.
25. Rip cloth from the corpse, make a torch, find the alcove (lamp and grave-cloth).
26. Pick up the lamp.
27. Light it from the torch — runes matching the door bands, a copper key in the fat.
28. Key in the north door: lock opens, slab still dead under packed earth.
29. Recap out loud. The details "refuse to knit together" on a failed DC 20 — the facts themselves are not withdrawn.
30. Leave by the south stair. The amber wick goes out.

Combat was one typed turn plus clicks. The kill beat still does not name the actor
(`BeatRenderer`: "Vessk is killed by the blow"). Gemini wrote *your blade* anyway.

---

## 4. Consistency

This is the question. The log, not the memory.

**Held.** The eleven incomplete tallies (turn 3) are still the eleven incomplete tallies at the
recap (turn 29). The lid scratches (turn 9) become Vessk's blade (turn 20) and then a pry-bar
(turn 21). The empty trough (turn 22) stays empty. The key (turn 27) turns the lock (turn 28) and
the door still does not open, which is the sealed-door note answered in fiction. Nothing invented
in the morning sessions (a second goblin, a living manticore spike, a corpse that keeps talking)
showed up.

**The one overclaim.** Turn 8, after a failed wall-search, reconcile recorded *"The stone
perimeter is slick with damp lime and yields nothing to searching."* Turn 25 finds an alcove in
that perimeter. The engine was always going to allow `reveal_prop(alcove)`; the fault is
`assert_fact` writing a failed check as an empty world. A later success then contradicts the
projection. The prose did not re-introduce the alcove as if it had always been in play — it was
found with a torch — but Established said there was nothing, and then there was.

**Lesser, recorded.**

- *"The chamber is once more dark"* as the amber wick dies (turn 30) while the green braziers are
  still burning. Poetic, and wrong.
- A copper key in the lamp fat is not in `crypt.json`. Reconcile asserted it, the lock accepted
  the story, the door still did not open. The hotdog class with a backing fact: internally
  consistent, not on the board.
- The play-established story is Vessk scratching the lid **from outside**. The authored secret is
  that he was trapped **inside**. The player never heard the secret. Spec §11 is facts the DM
  established in play, not unpublished notes.
- One Established line names `Roderick` (smeared gauntlets). It was not read as a spoken beat in
  the third person.

M0 §4.3 said consistency degrades *over* a session. From turn 17 onward this one is almost only
callbacks. That is the regime the window exists for. It held.

---

## 5. Voice

Four player lines, four `[fighter]` tags, none stolen by the goblin, none left as narrator
quotation:

- *"I wonder if there's any treasure, or if this place has been picked clean too."*
- *"Hello? Is anything inside?"*
- *"Damn the hells! All that for nothing?!"*
- *"Ugh, it's blocked, I'll need to find another way."*

M0 §4.4's sticky marker put a player question in the goblin's mouth. That sequence was not
available here (Vessk died without speaking). The player-line half of the same parser is what this
session actually exercised, and it was clean. No `'s voice` leftover, no Entities/Grid dump, no
second person named "the goblin" while Vessk dies.

---

## 6. Length and latency — recorded, not graded

Narration averaged **372 characters a typed turn** (225–450, one 933-character combat bundle on
turn 11). At the ~20 characters-per-second speech rate that is about 18 seconds of voice, under
the ceiling that used to run to 25.

Timestamps are player-line → first `narration_logged` / first mechanical event, from the JSONL.
They include tools, not just prose.

| Path | This session | M0 in play |
|---|---|---|
| Keypress → die / reveal / spawn | 1.0–3.3s, median **1.7s** (21 tool turns) | 1.1–2.5s, median ~1.3s |
| Keypress → first word | 2.9–8.1s, median **4.7s** | 4.0–10.2s with dice; 2.5–3.6s empty |
| Tool-free first word (turns 12, 15, 18, 20, 22, 28, 30) | **2.9–7.9s** | the missed 2.5s row |

`low` thinking on Gemini is why the empty-screen waits are 3–8s rather than the 9–15s of the
morning's default-thinking run. Still slower than Venice role-play's ~4s, and still past the
written 2.5s empty-screen budget. In play it read as the DM thinking, same judgement as M0 §4.1.
Qwen on tools put dice in the air inside 3.3s every time this session; no 14s stall.

Turn 11's 76s "narration span" is the fight: spawn, Vessk's miss, the kill, while the player is
clicking. It is not model time.

Tokens-per-turn as billed by Venice were not logged. Character counts of spoken narration, by
typed turn, are the measurable substitute:

```
turn   1  2  3  4  5  6  7  8  9 10 11  12 13 14 15 16 17 18 19 20
chars 391 371 355 373 326 310 339 355 347 245 933 348 346 349 225 325 415 306 358 334

turn  21 22 23 24 25 26 27 28 29 30
chars 341 322 325 379 465 306 450 447 449 335
```

No climb toward the end. The window dropping old turns did not make the writer compensate with
longer recaps.

---

## 7. Verdict

The mechanical half was already true before this session (`crypt-fight.jsonl` under `./gradlew
test`). The played half was not: nothing until today was twenty typed turns long. This one is
thirty, the callbacks survive the window, the session file replays 32 outcome events identical,
and the two M0 §4.4 faults that M2 was built to close did not recur in the form the log can see.

The case against: turn 8's "yields nothing" fact is a real Established lie, and the copper key is
still a thing the board cannot hold. Both are the reconcile pass doing its job too eagerly, which
is a smaller class of bug than a narrator that invents a second goblin to stab Vessk.

> **M2 gate: PASS** — signed 2026-09-05. A 30-turn session in the one crypt, on the spine, did not
> contradict the facts it had already told except for one failed search written down as an empty
> wall. The session is on disk and it replays. A fault found in play is now a file. That was the
> question.

---

## 8. Carried out of M2

Findings, not tasks. Navigation is the next plan; these ride with it.

- **`assert_fact` on a failed search must not empty the world.** "You found nothing this pass" is
  a fact. "The perimeter yields nothing" is a hole the next success falls into.
- **Kill beats still omit the actor.** Gemini covered it. A worse writer will not. One line in
  `BeatRenderer`.
- **Talking to yourself can start a fight.** Turn 11 was a spoken aside; Qwen spawned Vessk and
  called `start_combat`. The two-failure escalation rule in `dm-tools.md` is doing that. Fine for
  a five-minute slice; it ate the parley this session needed.
- **`roll_check` twice on one action** (turn 2) is still happening despite the tools prompt.
- **Grid coordinates in Established** (`alcove at (9,6)`) will get read aloud the moment a writer
  is sloppier than Gemini.
- **Invented keys** are now durable. That is the spine working and a content problem: the north
  door still cannot open, so the key is a lock that clicks and a slab that does not. Liveable.
  Do not add a second room to cash it in.
- **Gemini `low` + Qwen tools** is the split this pass was judged on. Venice role-play is a better
  voice and a worse follower. DeepSeek-on-tools was coherent and lumpy. The 480b coder is still
  disqualified.
- **Dungeon navigation** is the next plan, against `EventLog` and `WorldState`, as the M2 plan
  already said. The crypt is done being asked to be twenty turns of new content.
