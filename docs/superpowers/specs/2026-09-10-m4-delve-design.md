# M4 — The delve: a run you can lose

**Gate question: can you lose a delve, and does losing it sting?**

**Status:** Approved 2026-09-12, written 2026-09-10. Successor to
`docs/superpowers/specs/2026-09-05-m3-traversal-design.md`. Builds on M2's spine and M3's rooms, and
amends M3 in two places, each named where it happens: a room's lighting becomes folded state (§7c),
and a crossing narrates (§8d).

Written in one pass from the wayfinder map *M4 wayfinder — settle the delve design before it is
built* (`emberdelve-ffi`): its charting block and the resolution comment on each closed ticket. The
reasoning lives in those tickets, named throughout; this document makes the case. The build is
`emberdelve-4h9`, and a build ticket that disagrees with this document is stale and gets amended.

`docs/ai-dm-system-design.md` §9 calls itself "a bet, not a record". This is what the bet became
before anything was built. §15 lists what settling it reversed.

Throughout, **§9 always means the design doc's section 9**, which this whole document settles. Other
design-doc sections are named as such — *the design doc's §6* — and every other bare section number
points inside this document.

---

## 1. What M4 is for

M3 proved a room is a place you can leave and come back to. Nothing a player does there costs them
anything they will miss later.

The design doc ranks that second of its open risks, and M3's success is what made it visible:
*nothing is scarce, so nothing is at stake.* The model always says yes. That is not a prompt
failure to be tuned out — it is what an agreeable narrator does — and it means the engine is the
only thing in this system that can make a decision cost something. Until it does, the game runs on
novelty, and a better prose model makes that worse rather than better, by making the free decisions
more pleasant to make.

The gate is stated as something to feel:

> Stand in front of a door at 5 hit points and genuinely not know whether to open it.

That sentence is also an arithmetic claim, and the first thing the map found is that today's numbers
make it false (§5a). A gate the numbers cannot pass is not a gate about feel. So M4 has two jobs
that look like one: make the delve spend things, and tune the spending until the sentence is true —
then play it, to find out whether true is enough.

---

## 2. Why the delve before the generator

M1's outstanding half — `LayoutGenerator`, `ExitPlacer`, world-space packing — was the other
candidate, and M3's spec named it as next. It loses on three counts.

- **The design doc already ruled on the order.** Its §15: "prove it on authored rooms before
  generated ones, or a generator bug and a game-feel problem are indistinguishable."
- **M3's own carry-out is the evidence.** Generated rooms have nothing to find: `PropPlacer` marks
  nothing hidden, so `reveal_prop` is never offered. That is not a missing feature in `PropPlacer`.
  It is `PropPlacer` written without knowing what a room is for, and a `LayoutGenerator` written now
  would be written against the same unknown, five rooms at a time.
- **Generation makes the risk worse.** More rooms is more novelty, and novelty is what the game is
  already running on.

This is the third time the project has taken a milestone out of order, and the reason has not
changed: *build the thing that answers a question, in the order the questions have to be asked.* The
generator follows M4 and arrives with a specification it did not have before — the authored site is
what `LayoutGenerator` has to be able to produce (§14).

---

## 3. Scope

### In

- **A delve that ends**, as an event, in one of three descriptive endings (§4).
- **Attrition**: session-scoped party state, re-tuned stat blocks, consumables as a counter map, a
  rest, and an exploration bar to spend them from (§5).
- **The clock primitive at DELVE scale**: two clocks, their signs and their consequence tables (§6).
- **The party's light** — a carried torch that the LIGHT clock burns down — and a room's fires moved
  by the ALERT clock (§7).
- **The projection and the directive rail** carrying all of it to the narrator (§8).
- **The end of a delve on screen** (section 9 below).
- **An engine-owned objective**, props as click targets, and a way out that checks for it (§4c).
- **More than one hostile on the board**, a second stat block, and authored spawns
  (`emberdelve-4h9.9`).
- **A five-room authored site** extended around the crypt and the gallery (`emberdelve-4h9.8`), the
  prop appearance layer that dresses it (`emberdelve-4h9.7`), and the editor tool that makes
  authoring it affordable (`emberdelve-4h9.10`).
- **DM-placed markers** — a fact you can click (`emberdelve-4h9.6`).
- Two played sessions and `docs/milestones/m4-evaluation.md` (§11).

The last four were in the epic before the map was charted and nothing on the map reopened them.
Their design is in their own tickets, and the design doc's §9 argument for markers stands unchanged.

### Out — and why

§13 has the whole list with re-entry conditions. The ones most likely to be reached for:

- **Healing in combat.** Measured out, not chosen (§5c).
- **A downed state.** It cannot fill at a party of one (§4a).
- **A DM lighting tool.** The engine owns the dark (§7).
- **The effect interpreter, the stance layer, conditions.** The design doc's §15 puts them with the
  content milestone, together. N hostiles against the existing forty lines of `resolveAttack` is the
  whole of M4's combat scope.
- **Meta-progression.** Never.

---

## 4. The delve is a state with an ending

A delve does not end today. The fighter dies and `godot/chrome/defeat.gd` notices, client-side, from
`Table.entity("fighter")` with `hp <= 0`. That check cannot tell three endings apart, names a
singleton player against invariant 2, and decides a death the server never announced, against
invariant 1.

**`DelveEnded` is an event**, folded into `WorldState` and shipped in `SceneState`. The end of a
delve is the most important thing that happens in a session; under invariant 8, if it is not in the
log it did not happen, and it will not replay.

```java
enum Ending { EXTRACTED_WITH_OBJECTIVE, EXTRACTED_WITHOUT, PARTY_LOST }
```

- **Extracted** — the party crosses the site's way out. The exit's check for the objective decides
  which of the two.
- **Lost** — no living player-controlled entity remains.

**The server refuses every action after `DelveEnded` except `restart`.** A typed line into a
finished delve is a turn with nothing to decide.

### 4a. 0 hit points is `PARTY_LOST`, and there is no downed state

Charting settled that 0 hit points drops a member and starts a short downed clock, and that the
delve is lost if it fills before combat ends. It does not survive the code. `CombatEngine.isOver()`
ends the fight when no living player-controlled entity remains, so at a party of one the fight is
over the instant the clock would start. The clock is inert by construction.

It also rested on a premise — *at HP 12, five hit points is one bad roll from over* — that the
numbers measured false (§5a). The tension a downed clock was meant to buy is bought before the door
instead, by the brute's doubt window, which is where §9 puts the decision.

A downed state re-enters with a second party member or with the design doc's §6 death saves
(`emberdelve-4h9.13` and `emberdelve-ffi.8`, both closed).

### 4b. The ending is descriptive, never a verdict

`EXTRACTED_WITHOUT` is not a loss. **Only `PARTY_LOST` is losing.**

Two reasons, and the second is the one that binds the future. §9's greed decision only works if
leaving early is respectable: punish the empty-handed exit and the site has one correct line through
it. And §9's own first argument for bounded sites is that *a cleared site is a durable fact* the
campaign layer consumes. An enum that scores the delve pre-empts the design doc's §10, and the
standing intent is that no delve outcome guarantees campaign failure — player creativity and DM
improvisation should always be able to open another path. `WON` / `LOST` takes that away before the
campaign layer exists to use it.

### 4c. The objective is a prop, not a fact

§9's sentence stands unamended: "The objective is engine-owned, never narrator-asserted. It is a
prop with an id, taking it emits an event, and the exit checks for it. This is the difference
between an objective and a copper key."

The copper key is the reference failure. Found in a lamp's fat at the M2 gate, asserted, durable,
and turning a lock in a door that could not open, it made the DM consistent and the board no wiser.
An objective carried in facts is another one. This objective is a `Prop`, a take event and a fold,
and props become click targets so that it can be taken (`emberdelve-7m6`). That ticket's lesson
governs how targets rank — *a thing with nothing behind it must not shadow a thing that has
something behind it* — and it is why the carried torch is never one (§7a).

**Assumed here, not decided on the map:** a typed *"I take the reliquary"* reaches the same take
event through a mechanics-phase path, the way `use_exit` sits beside the door click. §8f makes the
case; `emberdelve-4h9.4` settles the shape.

### 4d. The way out asks once

Crossing a door is one click because it can be undone. Leaving the site ends the run, nothing
resumes, and it is the last moment the greed decision is visible. So a click on the site's way out —
that exit and no other — puts an inline confirm in the chin: *"Leave the site, with the reliquary?
LEAVE / STAY."* It names whether the objective is carried and says nothing about it.

**A typed "I leave" does not ask.** It has already been through `use_exit`.

### 4e. The way on

One button, **DESCEND AGAIN**, for all three endings. It is today's `restart`, back through the
title so the opening narrates again. Normal play rolls `RandomDiceRoller` and `SessionStarted.seed`
is still a `0L` placeholder, so a restart is the same authored site with fresh dice: the doubt comes
back and the route does not. Two buttons — same site, new site — would offer a choice M4 does not
have.

---

## 5. Attrition — what is spent

§9's list stands: party state is session-scoped, healing is scarce, consumables are a counter map, a
delve can be lost. The first is mostly done already — M3 spawns the party once and `PartyMoved`
carries it — and `emberdelve-4h9.1` exists to prove that rather than assume it, because attrition
that silently resets at a door is the whole milestone failing quietly.

What the map added is the arithmetic.

### 5a. Five hit points, measured

Every figure here comes from `docs/evidence/m4-attrition-sim.py`, seeded, which mirrors
`CombatEngine.resolveAttack` and `GoblinAi` exactly (`emberdelve-ffi.6`). It stands in for the
`--demo` run the ticket was filed as, because there is no delve loop yet to run scripted dice
through.

**The premise was wrong, and wrong in the informative direction.** At the M0 numbers — fighter HP 12
against a goblin at +4 and 1d6+2 — five hit points survives a goblin **72%** of the time, and a
goblin costs a median of **zero**. Worse: 1 hp survives 65% and 12 hp survives 91%. What killed you
was whether the goblin connected, not what you had left. The number the gate asks the player to
agonise over carried almost no information.

Two findings made it tunable.

**The doubt window has two owners.** Call it the run of hit points where surviving the next room is
between 40% and 70%. *The encounter decides where the window sits. Max hit points decide how much
bar there is above it.* HP 20 and HP 24 against the same foes have the identical window. Treating
those as one dial is what made attrition look untunable.

**Against a party of one, the action economy inverts the obvious ladder.** N weak enemies is always
harder than one strong one, because each of them takes a full turn while the fighter takes one. At
the old numbers one brute dropped the fighter 23.7% of the time, two goblins 43.2%, three 79.9%. "A
few weak mobs" cannot be the easy fight while the goblin wears AC 15, so the goblin becomes the mob
it is already described as.

| | Was | Is |
|---|---|---|
| Fighter max HP | 12 | **20** — the run-up above the window, and the delve-length dial |
| Goblin | AC 15, HP 7, +4, 1d6+2 | **AC 12, HP 6, +3, 1d4+1** — HP 6 keeps it at 75% one-shot on a hit |
| Brute (new) | — | **AC 13, HP 16, +4, 1d8+2** |
| A rest restores | — | **4** |
| A potion restores | — | **8** |
| LIGHT and ALERT | — | **6 segments each** |
| Starting torches, potions | — | **2 and 2** |
| Site | 2 rooms | **5 rooms, about 3 holding a fight** |

The encounter ladder, at fighter HP 20:

| Encounter | Dropped from full | Doubt window | Survives at 5 hp |
|---|---|---|---|
| 2 mobs | 1.3% | 1–5 | 63.3% |
| **1 brute** | **8.2%** | **4–10** | **45.7%** |
| 3 mobs | 12.0% | 8–13 | 27.5% |
| brute + 2 mobs | 52.7% | 18–20 | 5.8% |

**The brute makes §9's sentence arithmetically true** — a fight you almost never lose fresh and
genuinely might lose hurt. One brute and three mobs land on comparable weight, which is variety
without a cliff. **Brute plus two mobs is not a designed room.** It is what the ALERT clock does to
a party that let it fill.

Whole delves: a careful run — three fights, the objective, out — is lost **26.1%** of the time and
leaves 10.8 of 20 hp. A greedy one — five fights, two rooms deeper — is lost **77.1%**. The careful
figure assumes the player fights everything and avoids nothing. Avoidance is what the ALERT signs
are for, and it is not tuned lower to look better.

**Rest and potion were chosen for legibility, not balance.** A 3-point and an 8-point rest differ by
under 1.5% across a whole delve; the fights dominate. A fifth and two fifths of the bar are numbers
a player can hold in their head, and a potion visibly worth twice the free option is what justifies
it being finite.

**Party size stays at one.** A second body cuts the wipe rate against two goblins from 42.8% to
3.1%. `List<PartyMember>` keeps that door open for nothing; M4's gate is about attrition, not party
tactics.

Exits stay illegal in combat, as locked at M2, so every fight is to the death. **The greed decision
is "do I open this door", never "do I finish this fight"** — which is why the danger has to be
legible before the door.

### 5b. A button per action the DM does not adjudicate

§9: consumables are `Map<Consumable, Integer>` on `PartyMember`, over potion / torch / rope, with
one `use_item` tool and "no UI beyond a count". A rest is an explicit player action, legal only in a
room with no living hostile, ticking every running clock, restoring a fixed amount, and never a roll
— dice are for questions the DM is about to answer, and "how much did you recover" is not one
(`emberdelve-4h9.11`).

**The count stays; "no UI" does not.** Torch, potion and rest get buttons on an exploration action
bar, the sibling of `combat_bar.gd` (`emberdelve-ffi.3`). The principle is the door's, and it is
already structural in `net.gd`: `moveTo`, `enterExit`, `attack` and `endTurn` are client messages
with no model in the path, because none of them is a question the DM answers. A torch that resets a
clock, a fixed 8-point potion and a fixed 4-point rest are the same shape. What §9 was guarding
against is *inventory* — an item pipeline, a screen, an enum that grows forever — and a button per
entry of a closed enum cannot grow. Rope gets no button: it does nothing yet, and a button that does
nothing is a capability flag claiming a behaviour.

It could not stay text-only. At `OUT` in a dark room, spending a torch is the most consequential
action in the delve, and making it depend on the mechanics model turning a sentence into `use_item`
puts the architecture's load-bearing risk on the one action that must not drop.

**The tools stay too.** The player will type *"I drink a potion"* whatever buttons exist, and a
narrated potion that does nothing is the hotdog. `use_item` and the rest are mechanics-phase tools,
and each button is a second path to the same event, as `enterExit` is to `use_exit`. Mechanics, not
reconcile, because a false positive spends something the player did not agree to.

**The gates.** The server refuses; the button greys as a hint — `SceneState.blocked`'s own rule. A
rest is refused while a living hostile is in the room, *not* whenever the mode is exploration,
because a `WARY` creature stands in exploration mode (§6d). A torch is refused only at exactly zero
segments, where it would reset nothing; spending one at `LOW` wastes two segments and stays legal,
because that is the greed decision. Every button goes dead while the DM speaks, keyed off
`Table.awaiting_dm`.

### 5c. No recovery in combat — a finding, not a scope call

In `m4-attrition-sim.py`, `fight()` heals nobody, and every rest and potion in `delve()` happens
between rooms. The 45.7% survival at five hit points was measured with no in-combat healing in it. A
mid-fight potion does not merely need the action-economy rule the design doc's §6 defers; it
invalidates the tuning that made the gate sentence true. All three buttons are exploration-only, and
so is `use_item`.

---

## 6. The clock

§9: "Without pressure, go deeper costs nothing and the greed decision is fake. A clock is the
pressure." It ticks, it fills, it fires a table, and "the DM narrates what the table produced; it
never decides that the torch went out."

§9 left open what the tables hold, what may tick a clock, and what a table entry may *do*. Each
answer below closes a way for the narrator to take scarcity back.

### 6a. The primitive

One record at three scales, built once. Only `DELVE` is in scope.

```java
record Clock(
    ClockId id,
    ClockScale scale,        // DELVE | WATCH | FRONT
    int filled,
    int segments,
    ClockKind kind,
    ConsequenceId consequence  // does not survive — see below
) {}
```

§9's single `consequence` does not survive, because **clocks speak twice**: a threshold fires a sign
and the fill fires a consequence. A clock that speaks only when it fills is a jump scare; one that
speaks every tick is a counter with extra steps. The sign table is a map from segment to
`ConsequenceId`, so a clock can be quiet early and loud late. Where the tables hang — the record or
the kind — is `emberdelve-4h9.3`'s to decide; what is fixed is that replay never reads them (§6g).

§9's `WANDERING` kind gets no clock of its own. Wandering monsters are ALERT's consequence table:
two clocks ticking on the same events would be one clock with two faces.

**The clock is diegetic and unlabelled.** No segment counter reaches the screen, and the wire cannot
carry one (§7a). LIGHT's read is the shrinking pool; ALERT's entire read is its signs.

**Clocks cannot kill.** A clock's job is to make leaving attractive, not to be a second health bar.
A terminal clock turns the delve into a speedrun, and §9 wants a greed decision. Death follows from
a filled LIGHT clock indirectly, which is enough.

### 6b. A sign is a consequence whose bundle is empty

**One enum, `ConsequenceId`, and one firing path** (`emberdelve-ffi.2`). A consequence is a named,
pre-validated bundle of events. A sign is one whose bundle is empty. Directives, logging and firing
are shared; the only difference is whether anything changes.

The cost is known: nothing in the types stops a sign table from holding `LIGHT_OUT`, so "a sign
table's entries have empty bundles" is a validation rule. A separate `SignId` would make that
mistake unrepresentable, at the price of duplicating everything else.

### 6c. The tables

**LIGHT — the party's torch.** The player can see it.

| Segment of 6 | `ConsequenceId` | About |
|---|---|---|
| 2 | `LIGHT_LOW` | The ring of light has drawn in. |
| 4 | `LIGHT_GUTTERING` | The flame stutters; the shadows swing. |
| 5 | `LIGHT_FAILING` | Light at arm's length. One more and it is gone. |
| **fill** | **`LIGHT_OUT`** | The torch has gone out — *your last torch*, when none are left. |

**One fill entry, on purpose.** A fully predictable LIGHT clock is what makes the greed decision
*computable*: the player counts torches against rooms and plans against the dark. The clock does not
stop at full; it sits there until a torch is spent.

**ALERT — how much the site has noticed.** The player cannot see it.

| Segment of 6 | `ConsequenceId` | About |
|---|---|---|
| 2 | `SOMETHING_STIRRED` | Far off, something moved and went quiet. |
| 4 | `THE_FLAME_LEANS` | A draught, from somewhere that was shut. |
| 5 | `IT_IS_CLOSE` | Near enough to hear. It knows roughly where you are. |

On the fill, one is drawn:

| `ConsequenceId` | Bundle | Disposition |
|---|---|---|
| `PATROL_ARRIVES` | `EntitySpawned` in the party's room, `CombatStarted` | `HOSTILE` |
| `SOMETHING_WANDERS_IN` | `EntitySpawned` in the party's room | `WARY` |
| `IT_PASSES_BY` | *(empty)* | — |
| `DRAWN_BY_THE_NOISE` | `EntityMoved` — a hostile already in the site crosses in — then `CombatStarted` | `HOSTILE` |

Every ALERT fill also moves the party's current room's fires, unconditionally (§7c).

**The draw filters to entries that would change something.** A logged consequence that did nothing
is a log that lies about what happened; `DRAWN_BY_THE_NOISE` drops out of the draw when no hostile
is elsewhere in the site.

`THE_FLAME_LEANS` reads ALERT *through the torch*, tying the two clocks together in the fiction
without tying them together in the machinery. `IT_PASSES_BY` exists because a table that always
brings a creature is one the player reads as "a fight is coming" after two draws. A quarter of the
time nothing follows, which makes a fill a threat rather than a promise.

### 6d. A consequence may only do what a tool can already do

**ADR-0012.** A bundle holds only events that already have an `Event` and `Diff` pair —
`EntitySpawned`, `EntityMoved`, `PropRevealed`, `CombatStarted` — plus scalars the engine already
folds. No new verbs and no effect language. The natural reading of "fires a table" is a scripted
effect, and a small language for scripted effects is an effect interpreter arrived at sideways,
which the design doc's anti-goal and its milestone ordering both refuse.

**`Disposition { HOSTILE, WARY }`. A consequence brings something in; it does not decide how the
meeting goes.** `HOSTILE` starts a fight. `WARY` needs no machinery at all: the creature is on the
board, no combat has started, and a swing starts one through `start_combat` as a provoked fight —
the state the M2 and M3 sessions already reached by accident. It is §9's avoid-the-encounter play
for free, and it makes "a player should not kill a creature they were not determined to kill"
structural rather than a prompt rule. It is the delve-scale ancestor of §9's reaction roll. Two
values, not one, because an unexercised seam is a claim rather than a behaviour.

**This is also the answer to turn-1 escalation** (`emberdelve-vy7`). Both 2026-09-07 sessions opened
on the same failed check. One ran five turns of parley and the other put the player in initiative
before they had done anything hostile, reading `dm-tools.md`'s two-failure rule in opposite ways.
**`start_combat` stays, for provoked fights. The two-failure escalation rule goes. The ALERT clock
becomes the only source of an unprovoked fight.** What was wrong was never that the model could
start a fight. It was that unprovoked escalation was decided by a model reading a rule it reads two
ways.

The behavioural change, named so it is not discovered in play: ALERT does not tick on failed checks,
so a player who fails check after check in one room now draws no escalation at all, where today they
would get a fight.

### 6e. What ticks what — and scarcity is the engine's

| Trigger | LIGHT | ALERT |
|---|---|---|
| A room entered, including one already visited | +1 | +1 |
| A rest taken | +1 | +1 |
| A natural 1 on a check | +1 | — |
| A fight starts | — | +1 |
| LIGHT fills | — | +1 |
| A torch spent | back to empty | — |
| ALERT fills | — | back to empty |

**Every room entry ticks, including a return.** So one room deeper costs two segments, one in and
one out, which turns depth from vaguely risky into computable. A careful five-room delve is about 9
LIGHT ticks, a greedy one about 15, and one lit torch plus two spares is 18.

**Two clocks on the same triggers are one clock with two faces**, so they share a baseline and
differ in what is extra. A fight ticking ALERT puts a cost on combat that combat does not otherwise
have. LIGHT filling ticks ALERT — going dark makes you louder, which is the spiral that gives a
delve an endgame — and that is declared *on the tick side*, never inside `LIGHT_OUT`. A clock that
ticks a clock from inside a consequence is the first step toward a state machine nobody can trace.

**Charting said a failed check ticks LIGHT. It does not.** Counted in the recorded sessions:
`session-m2-twenty-turns.jsonl` failed 14 of 20 checks and `session-m3-traversal.jsonl` 2 of 11.
Same game, same player, a sevenfold difference, because M2's DM reached for DC 15–25 and M3's for DC
5–10. A LIGHT clock fed by failures burns at a rate set by the narrator's taste in difficulty.

**ADR-0013: no engine resource is spent as a consequence of a value the model selected.** The
failed-check tick looked engine-owned, and every line of it was server-side. But `roll_check`'s
difficulty is a closed enum, validated exactly as invariant 7 requires, and the model still picks
which of the five — and whoever picks the DC picks the failure rate. A closed enum constrains what
the model may say, not what its choice costs the player. The natural die is the one nobody chose,
which is why a natural 1 still ticks. The DM narrating a dropped torch on a 1, which play showed it
already reaching for, becomes an engine tick, a sign and a narrated event, reached the right way
round.

### 6f. Discharge, edges and levels

**ALERT resets when it fills; LIGHT stays full until a torch.** Light is a quantity you spend and
alert is a state that discharges, and without the reset ALERT's table fires once a delve.

**Signs fire on crossing upward only, and re-arm on the way down.** Otherwise a torch spent at
`FAILING` announces `LIGHT_GUTTERING` on the turn the player fixed the light.

**The party's light level is level-triggered** (§7a), so a torch at `FAILING` puts the pool straight
back to `FULL` and fires nothing. Two rules, deliberately different: a sign is an announcement, a
level is a state.

### 6g. What the log says

Invariant 8 forces three events, and the fold is their only reader:

- **`ClockTicked`, carrying the resulting fill** — not a delta for the fold to count.
- **`ConsequenceFired`, inert in the fold and carrying the drawn id**, emitted *alongside* the
  bundled events rather than instead of them.
- **`RoomLightingChanged(roomId, from, to)`** (§7c).

Two refusals bracket it. **A `ConsequenceFired` that the fold expands through the tables** would
make a content file part of replay correctness: edit a table after a session is recorded and the
session replays differently. `PartyMoved` carries its landing square rather than recomputing
`Exit.inward()` for the same reason. **Bundled events with no `ConsequenceFired`** would lose the
causal record. A session in which a fight appeared from nowhere and the log cannot name the clock is
a session nobody can diagnose, and "the file that proves it is already on disk" is this project's
whole workflow. `ToolCallIssued` already pays exactly this price for exactly this reason.

The draw is logged inside `ConsequenceFired`, not as a `RollResult`: nobody watches it, and
`isDramatic()` would gate it out anyway.

`Event.SCHEMA_VERSION` goes from 2 to 3, once, for all of M4's events. Older logs are refused and
`./gradlew recordFixture` regenerates the fight fixture. The stat changes need no bump of their own:
`EntitySpawned` carries the whole `Entity`, so an old log replays with the stats it was written
with.

---

## 7. The engine owns the dark

§9's sentence — *"the DM narrates what the table produced; it never decides that the torch went
out"* — is the one this section hangs on. Every decision in it is a way of keeping it true.

**There is no DM lighting tool, in either phase.** A narrator that can put the light out will do it
because it is dramatic rather than because anything was spent, and then the clock means nothing. A
room's fires going out is legitimate only as an engine-fired consequence of a clock.

That refusal left the LIGHT clock with nothing to burn. `room.gd` builds a room's fires from the
room's own `LightingPreset`; the party carried no light of its own. So light moved to the party.

### 7a. The carried torch is a torch, and as an object it is nothing

Charting said *a carried lantern* (`emberdelve-ffi.3`). That was an asset sighting —
`kaykit_halloween` ships a `lantern_standing` — and four things point the other way. §9's own
sentence says *torch*. A torch is not lantern fuel, and the `torch` consumable is what refuels the
clock, so a lantern would be an engine fact the prose cannot honestly carry. `kaykit_dungeon` ships
the handheld pair, `torch` and `torch_lit`. And the Knight's `handslot.l` is an empty, animated
joint waiting for one.

**The party's light is a pure function of the LIGHT clock.** No `Lantern` record, no field on
`PartyMember`, no new event. The clock is already folded state, and deriving the light from it
leaves one thing to keep correct; a replayed session renders identically because the clock replays
identically. **It is not a `Prop`**: props stand on a square in a room, `Diff` has no `PropMoved`,
and `reveal_prop`'s per-room enum would swallow it.

What crosses the wire is a closed five-value level:

| `PartyLight` | LIGHT segments |
|---|---|
| `FULL` | 0–1 |
| `LOW` | 2–3 |
| `GUTTERING` | 4 |
| `FAILING` | 5 |
| `OUT` | 6 |

**The five names are the LIGHT table's own**, so what the DM narrates and what the player sees are
the same five words.

Two things are refused on the wire, and between them they fix what the server owns. **Not the
segment count**: better that the wire cannot carry the counter than that the client is trusted not
to draw it. **Not a radius in world units**: that puts an appearance number in Java and tunes a look
by recompiling the server. The server owns *how much light there is*, because that folds and
replays. The client owns metres, as it already owns `TORCH_ENERGY` and the ambient.

**Out with none left is not a sixth value.** Light and supply are different axes — the same error as
confusing where the doubt window sits with how much bar is above it. The player reads supply off the
greyed button and the DM off the projection, and `LIGHT_OUT`'s directive says *your last torch* from
state that already exists.

**The nodes are split.** The pool is one `OmniLight3D` owned by `World`, a sibling of `Tokens`,
placed each frame over the centroid of living player-controlled tokens. It cannot live under a
`Room`, where it would spend that room's `MAX_TORCH_LIGHTS` budget; nor on a token, which
`_rebuild_tokens()` frees on every crossing; nor on the hand bone, which swings with the chop. The
mesh rides `handslot.l`, added after the token's height fit and emissive pass so it disturbs
neither. It has two states, `torch_lit` and then `torch` at `OUT`, and it never comes off: an empty
hand reads as a dropped torch, which did not happen. It gets an hour, or it comes out — the pool is
the read, the mesh is garnish.

**The carried torch is never a pick target.** A mesh at hand height between the camera and
everything behind it, moving every turn, is `brazier-east` in a worse position. That is why spending
a torch is a button (§5b).

### 7b. How it reads: the pool shrinks and reddens; the dark hides and never blocks

Judged by looking, through the real client, in the crypt as it ships and with its fires moved to
`DARK` (`emberdelve-ffi.5`; the prototype is kept on `prototype/ffi-5-party-light`).

**Radius, colour and flicker move together, and the room's ambient does not.** Radius alone is the
weakest read beside a brazier. In a dark room a dimming light *is* a shrinking one, and red at
`FAILING` is the most legible cue on any sheet. Dipping the room's ambient along with the party's
light blacked out the room's silhouette at the end, and crossed two axes the glossary keeps apart:
the party's light is the only one of the three the party carries.

**Soft falloff.** An omni light cannot give a hard edge. A real boundary means a ring painted on the
floor, and a ring that shrinks is the gauge the clock must never draw.

| `PartyLight` | range | energy | colour | flicker |
|---|---|---|---|---|
| `FULL` | 6.0 | 2.6 | (1.00, 0.72, 0.40) | ±3% |
| `LOW` | 4.6 | 2.3 | (1.00, 0.60, 0.24) | ±6% |
| `GUTTERING` | 3.4 | 2.0 | (1.00, 0.50, 0.19) | ±12% |
| `FAILING` | 2.3 | 1.6 | (1.00, 0.40, 0.14) | ±22% |
| `OUT` | off | | | |

Client constants, attenuation 2.0, hung 0.95 above the centroid. The steps are even per level and
uneven per segment, so the pool shrinks fastest at the end, while the signs are firing. The flicker
is a starting point for the gate to judge; stills cannot judge it.

**A `DARK` room keeps a silhouette.** `EnvDark` takes `EnvTorchlit`'s ambient colour at its own
0.35. Energy could not do it — 1.4 was still black, because the old colour was near-black and the
tonemapper crushed the rest. `room.gd`'s `UNLIT_TINT` already says why it matters: a silhouette says
*stone, further in*; a void says the renderer has fallen over. Until this change, a `DARK` room you
stood in rendered darker than a room nobody had entered.

**The torch casts shadows, but not from the party**, through `Light3D.shadow_caster_mask` and a
render layer chosen by `isPlayerControlled`, never by `"fighter"`. From overhead, a figure shadowing
its own torch is a black disc at its feet.

**Hostiles carry no glow of their own.** The faint self-emission every token carries becomes
party-only, so a creature shows where light reaches it and nowhere else. **This is not a visibility
rule**: the client decides nothing, the lights do. It makes the board and the prose agree — a `WARY`
creature narrated as something moving in the dark is not glowing. The costs are accepted: a hostile
in a lit room's dark corner reads worse, dead hostiles vanish in the dark, and a hurt hostile's bar
would give it away outside combat, which M4 has no way to produce.

**The dark is a look, not a rule.** Every floor square stays a move target and there is no
visible-square list. The teeth arrive anyway: in a `DARK` room at `LOW`, the door is past the pool
and the player does not know where to click. If the dark ever needs mechanical teeth, the server
ships a square list the way it ships `SceneState.blocked`, and the level still does not become a
number.

Two lighting changes on the way here are already built. `EnvTorchlit`'s ambient went from 1.1 to 0.7
(`emberdelve-27l`): what 1.1 bought was a blue-violet wash on exactly the wall margins a shrinking
pool has to leave. And the crypt lost its wall torches (`emberdelve-4h9.16`). Both recorded sessions
describe a chamber lit by two green braziers and nothing else — eleven times in the M3 session alone
— and the board had been contradicting the DM.

### 7c. A room's fires are the room's, and ALERT moves them

**Every ALERT fill moves the party's current room one step, in the direction that room authored**
(`emberdelve-ffi.9`). A room has exactly two light states, ever: as authored, and moved. It
ratchets. A moved room stays moved for the delve, and a torch relights nothing, because a torch is
the party's and the fires are the room's.

Why ALERT, and why every fill. LIGHT is the party's fixed, plannable clock, and making it blow out
the room's fires too is one clock meaning two things. ALERT's table already held a darkening entry,
so letting it light rooms as well would jitter the dial back and forth with the draw. And the
frequency is a number: about 15 ALERT ticks a delve against 6 segments is 2–3 fills. As one entry in
four that is 0.6 fire moves a delve, and most runs would never see the site change. Unconditional on
the fill it is 2–3 rooms of five, every delve — declared on the fill side, by the same precedent
that keeps "LIGHT filling ticks ALERT" on the tick side. `THE_DARK_CLOSES_IN` left the table for it,
and its no-spawn slot survives as `IT_PASSES_BY`.

The site is walked twice, because the entrance is the way out. **A room that goes dark on the way in
is a room re-crossed in the dark on the way out.** The dial pays within one delve, not only across
playthroughs.

**Direction is a property of the place, not of the roll.** A room with fires carries:

```json
"fires": {
  "lit":   "Lit green by two braziers that should have burned out centuries ago.",
  "to":    "DARK",
  "moved": "The braziers have gone out — the green light is gone, and the cold in here is the cold of the stone."
}
```

`to` names the moved preset outright, so nothing reads an ordering into a closed enum and no room
can step twice. A room without `fires` never moves. **At least one room in the site moves brighter,
or the brighter direction comes out of the design** — an unexercised direction is a claim, not a
behaviour.

**A room's lighting becomes folded state.** `RoomDefinition.lighting` is the initial value, and
`GameEngine.roomView()` reads `WorldState.lightingIn(roomId)`, which defaults to it. This amends M3
§5a, which put lighting in a room's *structure* — the half that can be made again for free. It no
longer can.

It also exposed two live defects in a closed ticket. The narrator had never been told a room's
lighting, while `crypt.json`'s overview says *lit green by two braziers*: fire a darkening
consequence and the board goes dark under prose that cannot follow — the copper key with its
polarity reversed. So the fire sentence comes out of `overview`, and the room block prints whichever
of `lit` and `moved` is currently true. And a draw could land on a room already dark and log a
consequence that did nothing, which is why the ALERT table filters at draw.

`Room.Level` is untouched. The rule that it is a separate axis stops being merely true and becomes
load-bearing: a room's lighting now varies, and its render level still does not.

On the client this is not a wider rebuild guard; `world.gd`'s comment says why widening it breaks
the combat pull-back. A targeted path rebuilds the room's `Torches` group and re-applies
`lighting.gd`, both of which `godot/dev/light_shots.gd` already drives from outside. It snaps,
unless looking at it says otherwise.

---

## 8. The narrator across the delve

### 8a. Who owns what

| The engine decides | The narrator writes |
|---|---|
| that a clock ticked or filled, and what it drew | what the sign sounds like |
| that the torch went out, and how much light there is | what the dark is like |
| that a room's fires moved | that they went out, once — then the room block's line keeps it true |
| that something arrived, and its disposition | how the meeting feels, until someone swings |
| an unprovoked fight — ALERT's only | a provoked one, through `start_combat` |
| what a rest or potion restores, and whether one is left | the rest and the drink |
| that the delve ended, and how | the close |

The rule underneath is M2's and unchanged: free-form model text may enter the prompt; anything
reaching the engine or the renderer goes through a closed enum. ADR-0013 sharpens it for M4 — a
closed enum is not enough when the model's choice among its values sets a burn rate.

### 8b. The projection: `## The party`

The design doc names this as M4's expensive part: carrying hurt, clock and objective state across a
room boundary *as fiction* (`emberdelve-ffi.1`). The precedents disagreed — `## Momentum` ships a
count and `## The fight` forbids them — and the rule that reconciles them is the one everything else
here hangs off:

> **A count that drives a tool decision stays a count. A count that only describes state becomes a
> band.**

Consumables keep their counts, because `use_item` takes one. Hurt becomes a band, because no tool
takes hit points and a fraction is an invitation to read one aloud. The rule also predicts the
answer for anything added later, which is why it is written down rather than four separate
decisions.

```markdown
## The party

Session state. It followed them in here and it will follow them out.

- `roderick` — Roderick, badly hurt, at (6,1)
- objective: not yet found
- torch: guttering
- the site: stirring
- potions: 0 · torches: 1 · rope: 0

There is nothing else here worth carrying out. Do not invent a second.

The torch and anything moving toward the party are the engine's to change, not yours.
Narrate what you are told has happened. Do not decide that the light goes out.
```

- **Its own block, above `## Entities present`.** The party is session-scoped and hostiles are
  rebuilt from room content, so the block that must survive a crossing is not the one that gets
  rebuilt. A list, per invariant 2.
- **Hurt uses `BeatRenderer.condition()`'s four bands**, extracted so the combat beats and the
  projection share one wound vocabulary. The `5/12 hp` every entity ships in both prompts today
  goes; nobody ever decided it.
- **Both clocks, as bands.** The torch in `PartyLight`'s own words; ALERT as `quiet` (0–1),
  `stirring` (2–3), `hunting` (4–5), `on you` (full). ALERT has to be there even though the player
  cannot see it, or honest foreshadowing is impossible.
- **The fact-not-cue lines are not boilerplate.** `## Secrets` proved this model narrates a thing
  into being when a block reads as a promise: told a goblin would come out fighting, it wrote the
  lid grinding open on a turn where nothing had spawned. The engine owns the dark, so the block that
  mentions the torch is the block that has to say so.
- **The objective gets a state line and a closed-world sentence.** The prop listing says where it
  lies; it cannot say the party carries it out of a room the prop no longer belongs to. The sentence
  is `## Ways out`'s lesson applied before the playtest instead of after: a block vague about state
  is worse than no block, because the model fills the gap and is then consistent about the wrong
  thing.
- **All three counters, always, zeroes included.** An omitted line is an invitation, and the model
  always says yes.
- **Always present, never conditional.** A conditional pressure block makes its absence information,
  read as "no pressure" on exactly the turn a clock starts running.
- **The room block prints the room's current fire line** after `sensory` (§7c).

**Facts in the projection, permissions in the prompts, nothing said twice.** No new text goes in
`dm.md` or `dm-tools.md`; the block's own lines are the only instruction added anywhere. Terse wins,
and the wording is measured before it is believed.

**`## Momentum` keeps its count** (`emberdelve-vy7`). One wrinkle is left open: under the rule above
it stayed a count because `dm-tools.md`'s two-failure rule reads it, and that rule is going. If
nothing in the tools prompt still reads the count once it goes, the count only describes state, and
the rule says it becomes a band or leaves. `emberdelve-4h9.14` decides when it measures the block.

### 8c. The directive rail is ordered, and scoped

Signs cost no model call. They ride the latched directive rail — `pendingArrival`'s, not
`narrateCombat`'s. A sign is foreshadowing, and nothing on screen would cover an interrupting prose
call: dice cover a typed turn and animation covers a combat beat, and a sign has neither. A dropped
sign is also worse than a late one, because the point of a sign is to be acted on.

That rail is **a single `String` today**, about to have five writers: the arrival line, LIGHT's
signs, ALERT's signs, a fire move, and a torch relit. The last write wins, silently, and an
overwritten sign is a clock that went quiet, which looks like the DM losing interest. **It becomes
an ordered list, drained in order, one clause per entry.**

Order is not enough, because entries stop being true (`emberdelve-ffi.10`). **Every directive is
about a room or about the party.** A crossing drops the entries about any room but the one entered,
and keeps the party's in order.

| About a room — dropped on leaving it | About the party — kept |
|---|---|
| the arrival line | LIGHT's and ALERT's signs, `LIGHT_OUT` |
| a room's fires moving | a torch relit |
| something wandering in | |

Keeping everything tells the narrator the party has come back to a room it left; clearing the rail
on a crossing drops signs. A creature that wandered in stays in its room and is on the board on
return, which is how ADR-0011 already reads a goblin left alive somewhere else. This makes
`emberdelve-xgg.12`'s stale *"you have come back"* — delivered 16 seconds late on a key-searching
turn — impossible rather than rare.

**A directive left while a fight is already running is dropped**: engine-generated narration gives
up rather than arrive late. **One left by the action that started the fight is not.** A crossing or
rest that draws `PATROL_ARRIVES` narrates the room, then hands the fight to whoever won initiative.
The rule is judged when a directive is left, not when it is spent — otherwise the first words in a
room nobody has described are the enemy's swing.

**Directives still waiting when `DelveEnded` folds are dropped.** A sign after the delve is over is
about nothing.

### 8d. Every crossing narrates, and so does every rest

**This reverses a judgement play made, and play was right at the time.** On 2026-09-07, 15 of 17
crossings were silent and nobody missed the narration: clicking a door and walking through *is* the
honest path, and in M3 a crossing cost nothing. In M4 every entry ticks both clocks, and that
changes what silence says.

"Narrate only when something is waiting" looks cheaper, and is not. Only segments 1 and 3 of each
clock fire nothing, so a crossing is silent only when both clocks sit on a quiet segment at once —
at most one crossing in three. It narrates most crossings anyway, and the silent remainder becomes a
readout of ALERT, the clock whose entire read is meant to be the content of its signs. "Never" fails
worse. `PATROL_ARRIVES` can start a fight on a crossing, and a silent crossing opens that fight with
the enemy's round-one swing in a room nobody has described. That is the narrator going quiet when
the sword comes out — the thing `AGENTS.md` calls the loudest fault in T10.

**The rule: a player action that ticks a clock narrates.** Crossings and rests do. A torch moves
LIGHT down, which fires nothing, and the player has just watched the pool come back; its relight
directive waits for the next narration. A potion moves no clock.

- **A crossing takes `lock`, not `tryLock`, and one that finds the rail empty says nothing.** The
  asymmetry rule — narration the player asked for waits, narration the engine generated gives up —
  exists because a combat beat's facts are passed in and are lost if it is dropped. Directives wait
  on the rail by themselves and scope keeps them true while they wait, so a crossing that waits can
  be late but never wrong. Eight crossings in 36 seconds narrate the room the party ends up in.
- **Registers stay as written.** `ARRIVAL_FIRST` for a first visit, which is the only description
  that room will ever get: the window forgets it, and `ARRIVAL_RETURN` forbids describing it again.
  A return and a rest take a sentence or two.
- **The cost, named.** About two minutes of arrival speech a delve, with returns unmeasured. An
  arrival line holds the exploration bar dead for its length while doors stay live, because a rest
  under narration can fill ALERT in the middle of a sentence. If two minutes proves too much, the
  fix is a shorter register, not fewer narrated crossings, and it is measured rather than believed.

### 8e. The close is the last narration, not an extra one

The DM narrates the end of a delve (`emberdelve-ffi.4`). **It does so by taking the place of
whatever narration would otherwise have been last.**

- On `PARTY_LOST`, the enemy turn that wiped the party is already one dramatic narration call. A
  separate close after it would meet `narrating`'s lock and, being engine-generated, give up — the
  last thing the player hears, dropped in silence. So the close takes that call's place and is
  handed that turn's facts plus the ending.
- On an extraction, it takes the place of the crossing out's arrival line.

The close waits on the lock rather than giving up. It is prose-only, like `openScene`: two or three
sentences, no numbers — the page has them — and no *won*, *lost* or *failed*. **With no key
configured there is no close, and the ending is still complete**, because the page is state.

### 8f. The tools after M4

`AGENTS.md`'s seven, plus:

| Tool | Phase | Why that phase |
|---|---|---|
| `use_item` — consumable enum | mechanics | a false positive spends a potion the player has (§5b) |
| rest | mechanics | a false positive spends clock segments nobody agreed to (§5b) |
| a marker — closed tag | reconcile | the narrator holds the information; a false positive is a glyph nobody clicks (`emberdelve-4h9.6`) |
| taking the objective | mechanics | **assumed** — see below |

**The fourth row is not on the map.** It follows from the argument that kept `use_item` beside its
button — the player will type it whatever the board offers, and a narrated take the board ignores is
the hotdog holding a reliquary — and from the phase rule, since taking the objective decides an
ending and a false positive is expensive.

**Once the brute exists, `spawn_entity` has a second kind it could offer, and ADR-0013 is the test
for whether it should.** The encounter decides where the doubt window sits (§5a). A model allowed to
spawn a brute where a mob would have gone chooses the delve's lethality through a closed enum, which
is the failed-check tick in another form. Authored spawns and the ALERT table are engine-chosen.
Whether the model keeps any spawn at all is `emberdelve-4h9.9`'s to settle, with that sentence in
front of it.

`start_combat` stays, for provoked fights only (§6d).

---

## 9. The end of a delve on screen

Judged by looking, through the real chrome, over two rounds (`emberdelve-ffi.4`; the prototype is
kept on `prototype/ffi-4-delve-ending`).

**First, a finding: the body does not say it.** From the fixed isometric camera, a knight on his
back reads as one standing with his arms out — measured with `Death_A` holding its last frame and
the hips dropped from 0.41 to 0.14. The board says *where* the delve ended. Words have to say *that*
it did. `defeat.gd`'s docstring found the same thing from the other side: a death nothing marks
reads as a crash.

- **The board stays visible and undimmed.** Dimming gives all three endings the same funeral, and
  two of them are not losses.
- **A page at the top right, sized to its content** — a sentence, a ledger, a button. On the left it
  covered the transcript, which is where the player has been reading all session and where the close
  lands, and a player who has just lost still wants to read it. The page never reaches into the
  chin. The lintel keeps the room's name.
- **One screen for all three endings.** One plain sentence — *Roderick fell in the Ashen Crypt.* /
  *Roderick came out with the reliquary.* / *Roderick came out.* — tinted ash for `PARTY_LOST` and
  daylight for both extractions. No verdict word and no kicker line: round one's *"He had the
  reliquary. He went one room further."* was the client writing exactly the judgement `Ending`
  exists to withhold. Names come from the party's entities.
- **A ledger in numbers, shipped by the server:** rooms entered, of the site's total; potions drunk
  and torches burned, each as used of brought; fights; the objective under its own name — *carried
  out*, *left where it lay*, *fell with him*; and, on extracted endings only, the hurt band.
  Numbers, because the close is the prose and nothing is said twice. Shipped, because "brought" and
  "used" are folds over a log the client does not have. The site's total is shown because once a
  delve is over, a bounded site is known to be bounded.
- **The page does not wait on the model.** It arrives with `DelveEnded`, and DESCEND AGAIN is live
  while the DM speaks — the one exception to `Table.awaiting_dm`. Pressing it cuts the voice.
- **On `PARTY_LOST` the page waits for the fall**: `IMPACT_SECONDS` and then `Death_A`'s 0.8
  seconds. Everything a blow causes already waits for the blow. On an extraction the page comes at
  once.
- **At the end**, the input box is hidden, the exploration, combat and debug bars go, and the
  transcript stays scrollable.

`defeat.gd` and `Overlay/Defeat` are deleted. **The test that keeps them deleted:** a fixture with a
player entity at 0 hp and no `DelveEnded` shows no page.

---

## 10. The wire

`SceneState` gains:

- the party's `PartyLight` (§7a);
- the ending when there is one — the `Ending`, the parts of its sentence, and the ledger (section
  9);
- the consumable counts the exploration bar shows.

`RoomView.lighting` stays, and now reads the fold (§7c).

`Diff` gains a change to the party's light (a room entered, a natural 1, a torch), a change to a
room's lighting — nothing in the union can change one today — and the delve ending. Consumable and
rest changes are `emberdelve-4h9.2`'s and `emberdelve-4h9.11`'s; hit points already have a diff.
Names are the build's.

**Never on the wire:** a segment count, the ALERT band, a light radius.

Wire mirrors are hand-written GDScript readers under `godot/`, updated in the same commit as the
Java records, per the standing rule.

---

## 11. The gate

Two played sessions in the real Godot client, both written to `server/sessions/`, one copied into
`docs/evidence/` to replay in the suite, and `docs/milestones/m4-evaluation.md` with a verdict and a
§8 of what M4 hands forward (`emberdelve-4h9.15`).

| # | Criterion |
|---|---|
| 1 | A session in which the player takes the objective and then chooses between leaving with it and going deeper. |
| 2 | A second session in which the delve is lost. |
| 3 | **Hurt, in front of a door, the decision is genuinely uncertain** — judged by the player in the evaluation, not read off the log. If every door was an easy call in either direction, the numbers failed. |
| 4 | Whether the clock was felt. It is unlabelled on purpose; if the player never noticed one was running, that is the finding. |
| 5 | **Whether losing stings when nothing persists.** The sting is meant to come from within the session — what was spent, how far the party got, what it left behind. This is the assumption the milestone rests on and it may not hold, and a clear no is a better finding than a soft pass. |
| 6 | The kept session replays offline with no network, which is what proves the clocks, the fires and the ending fold. |

Recorded, not graded:

- Arrival speech per delve, first visits and returns counted separately, and whether the bar lock
  after a crossing grates.
- The flicker and radius numbers; whether hostiles read in the dark; whether `OUT` in a `DARK` room
  reads as a failure spiral or as the renderer falling over.
- Whether the close is heard or skipped; whether a wipe reads as seen before it is told; whether
  *Roderick came out.* reads as respectable; whether the confirm on the way out is noise.
- Whether turn-1 escalation recurs with the rule gone, and whether a player failing check after
  check in one room notices that nothing escalates.
- Whether the board and the DM agree more often than they did — the risk M4 attacks from two sides,
  with an engine-owned objective and with markers.
- Carried from M3 and still unexercised: a prop revealed and then crossed away from.

---

## 12. What M3 handed forward

`docs/milestones/m3-evaluation.md` §8 and the 2026-09-07 playtest, honestly accounted:

- **Invented light travels with you** — answered. The gallery lantern was a fact with no prop behind
  it; now the party carries a torch. The hotdog problem is solved the cheap way round, by giving the
  board the thing the fiction keeps inventing.
- **Authored secrets have no spawn** — `emberdelve-4h9.9`'s authored spawns.
- **Talking to yourself can start a fight, and turn-1 escalation is a coin flip** — decided (§6d),
  and built under `emberdelve-vy7`.
- **Nobody missed the narration on a crossing** — reversed for M4 (§8d), built under
  `emberdelve-xgg.12`.
- **What was missing was whether a door could be crossed** — a real door state (`emberdelve-ql3`) is
  needed only if the site authors a shut door. Otherwise no door's description may say it is shut.
- **The gallery niche was never found** — its hint was on the west wall and both players searched
  the brazier and the pillars. `emberdelve-4h9.8` puts hints where players look.
- **`EnvTorchlit`'s 1.1 is the suspect number** — now 0.7, built (§7b).
- **The gallery's cold brazier burns** (`emberdelve-4h9.17`) — open. A lit-or-cold state belongs
  with the `fires` object or the appearance layer, never with a client special case on an id.
- **`reveal_prop` can beat the sentence** — still a finding, and not in this plan.
- **`roll_check` twice on one action** — absent from both 2026-09-07 sessions, and not called fixed.
- **Generated rooms have nothing to find** — handed to the generator (§14).

---

## 13. What M4 does not build

| Not built | Why | Re-enters when |
|---|---|---|
| Meta-progression — stash, upgrades, run tiers | It competes with the campaign layer for the same design space | Never |
| A DM lighting tool | The engine owns the dark (§7) | Never, while §9's sentence stands |
| A clock that kills | A second health bar, and a speedrun instead of a greed decision | Never, at delve scale |
| A downed state | Inert at a party of one (§4a) | A second party member, or the design doc's §6 death saves |
| Healing in combat | The doubt window was measured without it (§5c) | The design doc's §6 action economy, with a re-tune |
| The dark as a rule | Visual only (§7b) | The server ships a visible-square list — never a radius |
| A party of more than one | It cuts the danger fourteenfold, and M4's gate is attrition | The content milestone |
| The effect interpreter and the stance layer | Each is a tax on its own | Together, with the content milestone. The brute meets the stance layer's condition — a second monster kind — so note it when it lands; do not build it |
| `WATCH` and `FRONT` clock scales | Only `DELVE` has a loop to tick it | The region, and the campaign layer |
| A room's light state carried between delves | A durable fact between runs is the campaign layer's | The campaign layer |
| The design doc's §10 judging a delve's outcome | `Ending` stays descriptive so that it can, later | The campaign layer |
| Inventory, equipment, encumbrance | Consumables are a counter map | Something needs an item to be an object |
| Resume, snapshots, schema migration | A lost delve costs the run, and the run is the session | Sessions long enough that losing one hurts |
| The region and the watch loop | There is one site | Enough sites that travelling between them means something |
| A different site on restart | The seed is a placeholder, so a restart is the same site with fresh dice | The generator |
| Fleeing | Exits are illegal in combat, locked at M2 | A rules milestone that designs retreat |

Still fog rather than ruled out: an ambient sound bed for the signs to eventually be about; whether
"something worth more, deeper" needs a value concept, which is only decidable while the site is
being authored; and whether a generator must guarantee a room the party can rest in.

---

## 14. What M4 hands the generator

M1's outstanding half comes next, and the reason it waited is that nobody knew what a generated room
has to contain. The authored site is the answer. `emberdelve-4h9.8` owns this list and completes it
while authoring; this is where it starts.

**A site, not a dungeon.**

- Five rooms: an entrance that is also the way out, the objective placed shallow, about two rooms of
  optional depth, and something worth more placed deeper.
- About three fights, from the middle of the ladder — one brute, or three mobs. Brute plus two mobs
  is never designed; the ALERT clock makes it.
- A known room count, because the ending shows it.
- A way out marked as the site's exit, because it is the one that asks.

**Every room.**

- A `fires` object, or none; where there is one, a direction and a `moved` line. At least one room
  per site moves brighter.
- A lighting preset, as an initial value only. A `DARK` room still renders a silhouette.
- Hidden props with a `revealHint` and `contains`, hinted somewhere a player will actually look. M3
  §12a is the argument: a secret with a mechanism is a hidden prop, and free-form dress-pass secrets
  are the copper key at dungeon scale.
- Encounters as authored spawns with a disposition, not as a model's decision.
- In the objective's room, the objective: an engine-owned prop.
- Door descriptions that agree with what `crossExit` enforces.
- A first-visit description good enough to be the only one the room ever gets (§8d). With every
  crossing narrated, the dress pass carries more weight than it did.
- A small `PropType` and a closed appearance for each prop (`emberdelve-4h9.7`), so the generator
  can vary meshes without widening the tool schema.

**Engine rules that survive generation untouched**, because none of them is content: a rest needs no
living hostile in the room; every entry ticks both clocks; the way out's check decides the ending.

**Inputs the generator has to supply that authoring faked.** Shared walls between several rooms side
by side are `Rooms.coveredWalls()`'s answer, dumped from the server and never reimplemented in
GDScript (`emberdelve-4h9.10`). And the seed stops being a placeholder — at which point DESCEND
AGAIN is a different site.

---

## 15. What settling §9 reversed

§9 was written expecting to collect reversals, and it collected these before a line of it was built
— from counting recorded sessions, from a simulator, and from looking at the real client. None of
them came from a played M4 session. The gate will add its own.

- **"Noisy failures" tick the clock → only a natural 1 does.** The model sets the failure rate by
  choosing DCs (ADR-0013, §6e).
- **"No UI beyond a count" → a button per action the DM does not adjudicate.** The count stays, the
  tool stays, and the action that must not drop gets a path with no model in it (§5b).
- **One consequence per clock → a sign table and a consequence table.** Clocks speak twice, and a
  sign is a consequence with an empty bundle (§6a, §6b).
- **A `WANDERING` clock → ALERT's table** (§6a).
- **The fighter is fragile → the goblin was never a threat.** HP 12 becomes 20, the goblin is
  weakened, and a brute is added (§5a).
- **A room's lighting is structure → folded state.** Amends M3 §5a (§7c).
- **A crossing is silent → every crossing narrates.** A 2026-09-07 play judgement, right for M3,
  where a crossing cost nothing (§8d).
- **Charting's own:** a carried lantern became a torch (§7a); the downed clock became `PARTY_LOST`
  (§4a); the two-failure escalation rule became the ALERT clock (§6d).

---

## 16. Order of work

The build epic's dependency edges are authoritative. This is the order the questions have to be
asked in.

1. **Party state is session-scoped, proved** (`emberdelve-4h9.1`), with the fighter's 20 hit points.
2. **The Clock primitive** (`emberdelve-4h9.3`): `ClockTicked`, `ConsequenceFired`,
   `RoomLightingChanged` and the schema bump, and the ordered, scoped directive rail.
3. **Consumables and `use_item`** (`emberdelve-4h9.2`) and **the rest** (`emberdelve-4h9.11`), with
   the exploration bar.
4. **The party's light** (`emberdelve-4h9.12`): `PartyLight`, the pool, the mesh, the `DARK`
   silhouette, and hostiles without glow.
5. **Crossings and rests narrate** (`emberdelve-xgg.12`).
6. **The projection carries the delve** (`emberdelve-4h9.14`). The escalation rule comes out of
   `dm-tools.md` (`emberdelve-vy7`) in the same step, once ALERT can escalate in its place.
7. **More than one hostile**, the re-tuned stat blocks and authored spawns (`emberdelve-4h9.9`).
   `CombatEngineTest` reads the real content files and its scripted faces were chosen against AC 15
   and 7 hp, so they all move.
8. **Props as click targets** (`emberdelve-7m6`), then **the objective and the way out**
   (`emberdelve-4h9.4`).
9. **A delve can end** (`emberdelve-4h9.5`): `DelveEnded`, the page, the close.
10. **The appearance layer, the editor tool and the site** (`emberdelve-4h9.7`, `emberdelve-4h9.10`,
    `emberdelve-4h9.8`), with `emberdelve-ql3` only if the site authors a shut door. Markers
    (`emberdelve-4h9.6`) can land alongside.
11. **Play the gate** (`emberdelve-4h9.15`).

Steps 1 and 2 are invisible to a player and carry most of the risk. Step 4 is the first that can be
looked at. Step 9 is the first at which a delve can be lost.
