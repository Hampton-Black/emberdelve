# M2 — The spine: event log, projection, replay

**Status:** Design approved, pre-implementation
**Companion to:** `../../milestones/m0-evaluation.md` (the faults this closes) and
`docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md` (the milestone this
interrupts)

---

## 1. What M2 is for

M0 asked whether this feels like a Dungeon Master running a game. It does. M1 asked whether the
world can be made rather than authored; half of it is merged and half is waiting. M2 asks the
question both of them left open:

> Can a fault found in play be turned into a test?

Today it cannot. A session that goes wrong leaves a server log, a memory, and nothing executable.
`m0-evaluation.md` §4.4 is three careful paragraphs reconstructing a bug from a transcript,
because reconstructing it was the only option available. That is the thing that makes these
projects miserable to debug, and it gets worse with every model call added.

**Success criterion, in the house style — a thing you do, not a box you tick:**

> Play a session at least twenty typed turns long. Nothing the DM says contradicts a fact the DM
> itself established. Capture that session, and replay it in the test suite with no network.

Both halves matter and they are the same mechanism seen from two ends.

---

## 2. Why the spine before the rest of M1

`ai-dm-system-design.md` §14 puts persistence at M5. The procgen spec §9 already superseded §14
once by taking generation before the rules engine; this supersedes it again, and for a reason that
was not visible when either was written.

**The remaining half of M1 needs state to be room-scoped.** The navigation plan's answer is
"`GameRepository` becomes room-scoped, one `RoomState` per room." Written against an event log,
that task does not exist — events carry a `roomId` and projections filter on it. Building the
`RoomState` version first means building it, then deleting it.

**The projection is the only compaction strategy that does not lose the world.** Context is the
full transcript with no compaction (shortcut #11), which was correct for a five-turn session and
is not correct for a twenty-turn one. The obvious fix — truncate the transcript — throws away
established facts, which is the one thing §4.3 says must never happen. A projection folded from
the log keeps facts permanently and lets prose keep only what is recent. There is no version of
this that works without the log.

**And the schema is cheapest to get right before there is history to migrate.** Every field this
document adds speculatively — anchors on asserted facts, the phase tag on tool calls — costs
nothing now and costs a migration later.

---

## 3. Scope

### In

| Component | M2 form |
|---|---|
| Event schema | Structured facts. Coarse for outcomes, fine for model inputs |
| Storage | `EventLog` appending JSONL to `sessions/<timestamp>-<id>.jsonl`. Write-only |
| State | `WorldState`, an immutable record produced by folding the log. `GameRepository` is retired |
| Replay | `--replay <file>`: engine re-execution against a recorded session, fully offline |
| Context | Sliding window of recent turns, plus the projection |
| Established facts | `assert_fact` on the reconcile pass, room-scoped, anchored |
| M0 fault 1 | Combat beats handed to the narrator as structured facts, perspective rendered in Java |
| M0 fault 2 | `NarrationParser` marker lifetime; `TtsClient.voiceFor` keyed on kind |

### Out — and why

| Deferred | Why not in M2 |
|---|---|
| **Postgres** | Nothing needs it. The event schema will churn all through M2, and a relational schema is the worst place to iterate. It becomes a two-hour job once JSONL exists and the schema stops moving |
| **Resume from a log** | The fold is built either way; resume is everything *around* a turn — a log ending mid-turn, a dead TTS queue, a client handed a scene it never watched build. None of that is work that belongs in front of §1 |
| **Snapshotting** | A session is dozens to low hundreds of events. Folding is microseconds |
| **Schema migrations** | `SessionStarted` carries a version. Old logs are refused, not upgraded. An upgrader written in month two is a tax paid forever |
| **Rendering asserted facts** | Schema only. See §9 — the sigil is a stretch, not a commitment |
| **Click-to-inspect** | A new action verb, a client interaction mode, a fourth DM call path. It belongs with the rest of the site-exploration handles |
| **Dungeon navigation** | Re-planned separately once M2 lands. See §12 |

---

## 4. Events are facts, not commands

The load-bearing rule, and the one everything else depends on.

- `PlayerAttacked(actorId, targetId)` is a **command**. Replaying it re-rolls, produces a different
  outcome, and proves nothing.
- `AttackResolved(actorId, targetId, attack, damage, killed)` is a **fact**. Replaying it is a pure
  function of the log.

This is invariant #6 — *roll results are logged as events; never plan to replay by re-rolling from
a seed* — restated as a schema rule instead of a promise. The model's output is not reproducible
from a seed either, which is the deeper reason: the only reproducible thing in this system is what
actually happened.

### 4a. Granularity: coarse for outcomes, fine for inputs

**Outcomes are one event per resolved outcome.** An attack is a single fact that folds into several
state changes, not three events that a reader has to correlate.

This is not only about replay. `m0-evaluation.md` §4.4 records the narrator swapping attacker and
victim — *"you jerk Vessk off his feet"* while Vessk was in fact wounding Roderick — because the
beat handed to it is a **prose string**: `[Vessk hits Roderick for 6 damage., Roderick is now
wounded.]`. The model has to parse roles back out of that sentence and then map third person to
second, and §7 records it getting that mapping right once and wrong once in the same fight.

With `AttackResolved` carrying `actorId` and `targetId` as fields, **the perspective is rendered
in Java before the model sees anything.** "Your side" versus "their side" stops being a judgement
the narrator can get wrong, because it is no longer a judgement it makes. Fault 1 closes as a
consequence of the schema rather than as prompt tuning.

Fine-grained events give that up: `EntityDamaged(targetId, 6)` has lost the attacker.

**Inputs stay fine-grained and atomic** — `PlayerSaid`, `ToolCallIssued`, `NarrationLogged`. They
are corpus and debugging material, not outcomes, and you want them individually addressable when
reading a turn back. They are inert during the fold, with one exception: `NarrationLogged` feeds
the established-facts projection (§9).

### 4b. The boundary: log what is not reproducible from the seed

One rule, and it subsumes the deterministic/persisted split the procgen spec §7 handles as a
special case:

- **Regenerated, never logged:** layout, room shape — anything that is a pure function of
  `(seed, coords)`.
- **Recorded:** rolls, player input, tool calls, narration, and **the LLM dressing pass**.
  `RoomDressed(roomId, dressing)` is genuinely an event: it happened once, at a time, and it
  cannot be reproduced.

### 4c. The schema

```java
public sealed interface Event {
    Instant at();

    // --- session ---
    record SessionStarted(Instant at, int schemaVersion, long seed,
                          String toolModel, String proseModel) implements Event {}

    // --- inputs: fine-grained, inert in the fold ---
    record PlayerSaid(Instant at, String actorId, String text) implements Event {}
    record ToolCallIssued(Instant at, Phase phase, String name, String argumentsJson,
                          boolean accepted, String message) implements Event {}
    record NarrationLogged(Instant at, String speakerId, String text) implements Event {}

    // --- outcomes: coarse, fold to state ---
    record PartySpawned(Instant at, List<Entity> members) implements Event {}
    record EntitySpawned(Instant at, Entity entity) implements Event {}
    record EntityMoved(Instant at, String entityId, int x, int y) implements Event {}
    record AttackResolved(Instant at, String actorId, String targetId, RollResult attack,
                          Optional<RollResult> damage, int damageDealt,
                          boolean hit, boolean killed) implements Event {}
    record CheckResolved(Instant at, String actorId, Optional<Skill> skill, Difficulty dc,
                         RollResult roll, Outcome outcome) implements Event {}
    record PropRevealed(Instant at, String roomId, String propId) implements Event {}
    record RoomDressed(Instant at, String roomId, Dressing dressing) implements Event {}
    record CombatStarted(Instant at, List<Combatant> order, List<RollResult> initiative)
            implements Event {}
    record TurnAdvanced(Instant at, String activeId, int round) implements Event {}
    record CombatEnded(Instant at) implements Event {}
    record ModeEntered(Instant at, Mode mode) implements Event {}
    record FactAsserted(Instant at, String id, String roomId, String text, Anchor anchor)
            implements Event {}
}
```

`Phase` on `ToolCallIssued` distinguishes the mechanics pass from the reconcile pass. Without it a
replayed log cannot tell which model made a call, and §7 of the evaluation shows that being the
first question asked of any strange session.

`Event.ActionTaken(actorId, String description)` is deleted. It is a prose diary, and every one of
its fifteen call sites becomes a typed event above.

---

## 5. `EventLog` and `WorldState`

`GameRepository` is retired. It is replaced by two things with clearly separate jobs.

```java
public final class EventLog {          // append, read, load, write JSONL
    void append(Event event);
    List<Event> events();
    static EventLog load(Path jsonl);
}

public record WorldState(              // the fold's output. Immutable
        String roomId,
        Map<String, Entity> entities,
        List<PartyMember> party,
        Mode mode,
        Set<String> revealedPropIds,
        Optional<CombatView> combat,
        List<Fact> facts,
        int consecutiveFailedChecks) {

    static WorldState fold(List<Event> events);
    WorldState apply(Event event);
}

public record Fact(String id, String roomId, String text, Anchor anchor) {}
```

`Fact` is `FactAsserted` with the timestamp dropped — the projection of an assertion rather than
the record that it was made. The event stays in the log either way; §8's cap drops facts from the
projection, never from the log.

### 5a. One write path

The engine stops calling `put`, `setMode`, `reveal`, and `setParty`. It calls `apply(event)`,
which appends and folds.

This is the point of the exercise, and it is worth being explicit about why. There are two
separable properties here — **completeness** (the log records enough to rebuild state) and
**primacy** (state is only ever produced by folding). Replay needs only completeness. But
completeness without primacy rots on contact: the code today dual-writes at fifteen sites, and the
next feature adds a `put` with no matching event, nothing fails, and the harness silently stops
covering that path. **Primacy is what makes completeness self-enforcing** — skip the event and the
state change does not happen, loudly and immediately.

The mechanism is one method. The cost is rewriting the call sites.

### 5b. Why immutable, and not just a repository that folds

Because a turn reads state at three separate moments — the tool phase at `DmService:322`, prose at
`:418`, reconcile at `:511`. That sequencing is currently correct and entirely implicit: prose is
*meant* to see post-tool state. Nothing says so, nothing tests it, and the one open hazard in
`m0-evaluation.md` §7 — *attacking during an enemy-turn narration drops the kill's own narration* —
is this family of bug, two things racing over one mutable world.

With `WorldState` as a value, "which state does this phase see" becomes an argument you pass
rather than a property of when you happened to call. That is testable, and it is the same property
that makes §9 nearly free: established facts are another field on the same value, folded from the
same log.

`consecutiveFailedChecks` moves onto the projection for the same reason — it is currently engine
state that no event records, so a replayed session would lose the momentum block.

### 5c. Storage

Every session appends to `sessions/<timestamp>-<id>.jsonl`, one event per line. `clear()` closes
the file and opens a new one.

**Write-only means never read back into a live game.** `EventLog.load` exists and is exercised
constantly — by `--replay` and by the test suite — but nothing in M2 resumes play from a file.
That distinction is the whole of what §3 defers.

The first line is always `SessionStarted`, carrying the schema version, the dungeon seed, and both
model ids. The version so stale logs are refused rather than migrated. The seed and the models
because six weeks from now, reading a session that went strange, *which model wrote this* is the
first question — `m0-evaluation.md` asks it on nearly every page.

---

## 6. Replay

`--replay <file>` is **engine re-execution**, not a fold check.

Read the log. Stub the model entirely — feed the engine the recorded `ToolCallIssued` events in
order. Hand `ReplayDiceRoller` the recorded `RollResult`s. Assert the engine emits the same event
stream, event for event.

That is what makes an LLM-era bug reproducible: the model's decisions become fixed inputs, and
what is under test is the engine's response to them.

Two of the three seams already exist. `ScriptedDiceRoller` is the replay roller with a different
source, and `ScriptedDmClient` is already a test-only `DmClient` — replay points it at a log
instead of a fixture.

**Replay is fully offline.** No Venice, no key, no network. Which means captured sessions live in
`server/src/test/resources/sessions/` and run on every `./gradlew test`, rather than only when
someone is hunting something.

The fold is covered by ordinary unit tests, not a second replay mode. `--fold-check` is a
twenty-line addition if a fold bug ever masquerades as an engine bug; it is not worth writing
before that happens.

---

## 7. Context: recency and permanence are different context

The transcript is not dropped. It is bounded, and the projection carries what must never be lost.

**The division:** prose needs *recency* — voice, thread, phrasing to avoid. Consistency needs
*permanence* — what is true. They are different context and were only ever the same thing because
there was one buffer.

- **Window:** the last 6 turns verbatim, where a turn is one player message and the narration
  that answered it. A tuning knob, not a design decision — and one that can now be tested against
  real sessions instead of guessed at.
- **Projection:** entities, revealed props, combat, mode, momentum, and established facts. Folded,
  never truncated.

### 7a. Two repairs the window requires

Naive truncation would reintroduce §4.3's worst fault in a subtler form. Two live mechanisms read
the whole transcript, and both are anti-repetition:

- `isRepeat()` at `DmService:794` scans every user turn in `history` to catch a reworded retry.
- When it fires, the directive at `:453` says *"the transcript has what happened last time. Do not
  tell it again."*

The second is fragile under truncation. If the repeat is detected against a turn that has fallen
out of the window, the model has been told to consult a transcript that no longer contains the
thing — and a model told not to repeat something it cannot see will **invent** what it is not
repeating.

1. **`isRepeat()` reads the log, not `history`.** All `PlayerSaid` events, unbounded. It is a
   bag-of-words comparison over short strings, so cost is irrelevant, and detection reach stops
   depending on context size at all — strictly better than today.
2. **The directive carries the earlier narration inline**, pulled from the log by event id,
   instead of pointing at a transcript and hoping. Works identically whether that turn was two
   turns back or forty.

### 7b. Cost

Tokens per turn should stop growing linearly with session length. **Recorded in the evaluation,
not gated on.** It is a consequence of the design, and gating on a number never yet measured is
how a milestone turns into a tuning exercise.

Likewise latency: `m0-evaluation.md` §3 says *"do not spend M1 on them unprompted,"* and smaller
prompts can only help. Record it. Do not chase it.

---

## 8. Established facts

The DM says *"a signet ring glints on the corpse's hand."* Today nothing anywhere knows the ring
exists — `worldState()` lists authored props and nothing else — so next turn there is no ring, and
the turn after that there may be a different one.

**`assert_fact` on the reconcile pass.** That call already runs, already has the narration, and
already asks *what must change for that to be true?* This adds one question: what did you assert
that the board cannot hold? Assertions land as `FactAsserted` events and project into an
`## Established` block.

Latency is free — reconcile starts once prose has finished streaming, when the client has fifteen
seconds of speech queued and nothing to do but play it.

**Two constraints:**

1. **Room-scoped.** A fact belongs to a `roomId` and projects only while the party is in that
   room. Bounds growth structurally rather than by a magic number, and lands correctly for
   navigation — the wet walls of room 2 must not follow the party into room 5.
2. **Capped at 30 per room, oldest dropped.** A backstop against a model that asserts every turn.

### 8a. The hotdog, resolved

`m0-evaluation.md` §7 lists *"the narrator can describe a world change nothing can back — the
hotdog."* Under this design the goblin turning into a hotdog is recorded as an asserted fact, and
the DM then stays consistent about it for the remainder of the session.

That is absurd and it is also correct. §4.3's finding is that the only thing which reliably breaks
immersion is contradicting an established world. A world that commits to its own hotdog is not
contradicting anything.

---

## 9. Anchors, and invariant #7

A fact may eventually need to appear on the board. Once that is possible, a fact is two things:

- **The description** — *"a signet ring, thin gold, half-swallowed by the corpse's finger."*
  Free-form. Enters the prompt and nothing else. Drives no roll, gates no legal move.
- **The handle** — where it is, what mesh it takes. Entirely closed enums.

**Invariant #7 is unchanged, with a clarification made explicit:** *free-form model text may enter
the prompt; anything reaching the engine or the renderer goes through a closed enum.* This is
stated here rather than assumed, because "it is only narrative" is exactly how a closed enum
becomes an open one over six months.

```java
public sealed interface Anchor {
    record Ambient() implements Anchor {}
    record AtSquare(int x, int y) implements Anchor {}
    record On(String targetId) implements Anchor {}
}
```

Most facts are `Ambient` — a smell, a temperature, a sound — and never render. The field is added
now because retrofitting anchors onto a log full of anchorless facts is a migration, and adding
the field costs nothing.

### 9a. The sigil — a stretch, not a commitment

**M2 builds the schema only.** Nothing renders.

If the batch runs short, the stretch is a `SIGIL` member on `PropType` — no mesh of its own, a
billboarded marker, never blocking — and anchored facts projecting to the client as a marker where
the DM said the thing was. No inspect, no interaction. `PropType` is already documented as *"the
tileset defines the enum — the model can never name a prop that has no mesh,"* and
`blocksMovement()` is a switch, so a new member is one arm and the compiler finds every other site.

The argument for taking the stretch, from the procgen spec §2: *"motivation is the scarce resource
on an evenings project."* M2 is several evenings of refactoring during which the game plays
identically, followed by work whose payoff is the absence of a fault. A marker appearing where the
DM improvised something is the first visible thing in the batch.

---

## 10. The two faults M0 left open

Both are from `m0-evaluation.md` §4.4, and both are done **first**, before the refactor. They are
bounded, unit-testable, need no schema, no model, and no network — and opening a long invisible
batch with a named fault closed is worth more than its size.

### 10a. The sticky speaker marker

The player's own line — *"What else did you see in there?"* — was spoken in the goblin's voice,
because `lastCreature` stays in force until a different marker appears and survived two
intervening narrator lines.

**Not an identity problem.** `NarrationParser` already holds entity ids: `resolve()` maps whatever
the model wrote through `knownSpeakers`, accepting both ids and display names, and anything
unrecognised falls back to the narrator. `lastCreature` is correctly the goblin's id — it is
correct for too long.

**It is a lifetime problem, and §4.4 already specifies the constraint:** *the sticky marker is not
a bug*, it exists because the DM kept dropping the goblin's voice after one line, which was worse.
So the fix must keep a voice across its own multi-line speech while releasing it when someone else
takes over.

> **Stickiness is scoped to a contiguous run of quoted lines, and released by any intervening
> narration line.**

In the logged failure the marker survived two narrator lines; under this rule it releases at the
first. The multi-line-speech case stickiness exists for is untouched, because that case has no
narration in between. Fixture is the three-line sequence quoted verbatim in §4.4.

### 10b. `voiceFor` matches an id that is about to stop being unique

```java
return "goblin".equalsIgnoreCase(speakerId) ? goblinVoice : narratorVoice;
```

Deliberate and documented — shortcut #9, two voices, *a third voice is M6's problem* — and not
what broke in §4.4. But there is exactly one goblin today and its id is literally `goblin`. The
moment a dungeon holds two, ids diverge to `goblin-1`, the match fails, and every goblin speaks in
the narrator's voice **silently, with no error**. That is the fault class AGENTS.md warns about
under *suspect a dropped tool call before you suspect the renderer*, in the one channel with no
visual confirmation that anything went wrong.

Key the voice off `entity.kind()`. Still two voices; shortcut #9 intact.

---

## 11. The gate

Both halves. Neither alone is sufficient.

**Mechanical.** A captured real session replays event-for-event under `./gradlew test`, with no
network.

**Played.** A session of **at least twenty typed turns** produces no contradiction of a fact the DM
itself established.

The length is load-bearing. M0's sessions were 7, 5, and 2 typed turns, and §4.3's finding is that
consistency degrades *over* a session. A five-turn session cannot fail this gate, so passing it
would prove nothing. Twenty is roughly where the window begins dropping turns — the regime in which
the projection either works or is revealed to be doing nothing.

Recorded, not gated: tokens per turn (§7b), latency (§3 of the evaluation).

**And the actual product of the batch:** a contradiction found in play becomes a replay fixture.
Capture the session, trim it, check it in, and it fails until it does not. Play, capture, fix, keep
— that flywheel is worth more than any single fault M2 closes.

---

## 12. What this supersedes, and what follows

- **`ai-dm-system-design.md` §14** put persistence at M5 and the event log with it. Superseded, for
  the reasons in §2. §14 has now been reordered twice and should be read as a record of early
  intent rather than a plan.
- **`docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` is stale in both halves.** Its
  header already says the client tasks need re-planning against Godot. The Java half is stale too:
  *"`GameRepository` becomes room-scoped, one `RoomState` per room"* has no referent once
  `GameRepository` is retired. Its `LayoutGenerator`, `ExitPlacer`, and `SpatialValidator` tasks
  are untouched and still good.
- **Numbering.** M1 was procgen: room generation merged, navigation outstanding. M2 is this. Doing
  M2 before finishing M1 reads oddly and is what happened — the spine turned out to be a
  prerequisite for the other half. Renumbering to hide that would only make `git log` lie.
- **Next:** dungeon navigation, re-planned against `EventLog` and `WorldState` once M2 lands.
  Writing that plan now would mean guessing at the projection's shape and correcting it in three
  weeks.

---

## 13. Order of work

Not a plan, but the sequence the design implies, and the reason for it.

1. **The two M0 faults** (§10). Bounded, unit-testable, no schema, no network. They close named
   faults from the gate before three evenings during which the game plays identically.
2. **The event schema and the fold** (§4, §5). `Event` rewritten, `GameRepository` retired,
   fifteen call sites moved onto `apply`. The largest diff and the least visible.
3. **JSONL and replay** (§5c, §6). The first point at which the batch has produced something
   usable — capture a session, replay it offline.
4. **Context and established facts** (§7, §8, §9). The half a player can feel, and the half the
   gate's played criterion measures.
5. **The sigil**, only if the batch runs short (§9a).

Steps 2 and 3 are one refactor with a checkpoint in the middle, not two. Step 4 is the only step
that changes what a session feels like, and it is deliberately last because it is the step replay
makes testable.
