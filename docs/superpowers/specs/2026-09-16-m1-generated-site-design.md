# M1 — The generated site: can the world be made rather than authored?

**Gate question: can the world be made rather than authored?** Unchanged since 2026-08-20. What it
asks has changed, because there is now an authored world to measure a made one against.

**Status:** Draft, 2026-09-16. Not approved. Supersedes
`docs/superpowers/specs/2026-08-20-m1-procedural-generation-design.md` for M1's outstanding half and
retires `docs/superpowers/plans/2026-08-21-m1-dungeon-navigation.md` (§12 says what lifts from it).
Builds on M3's traversal spine and M4's delve. The single-room generator that merged under the
2026-08-20 spec (`dm.generate`) is its starting point, not a thing to rewrite.

Where this document and `docs/superpowers/specs/2026-09-10-m4-delve-design.md` disagree about what
a site must contain, **M4 §14 wins**. That section and
`docs/superpowers/specs/2026-09-13-m4-site-as-generator-input.md` are the specification. This
document describes how to meet it.

Section numbers are this document's unless named otherwise (*M4 §5a*, *M3 §5b*).

---

## 1. What M1 is for now

On 2026-08-20 the question was whether a seeded layout plus a dress pass could produce rooms worth
walking through. Nobody knew what a room was *for*, so the success criterion was about reading:
*descend three rooms, each a distinct place, nothing the narrator says contradicting the board.*

Three milestones later a room has a job. A room holds a secret with a mechanism, an encounter with a
disposition, fires that move one way, and door copy that matches what the engine enforces. A site
has an entrance that is also the way out, an objective close to the entrance, optional rooms
beyond it, and something worth more at the far end. M4 authored five rooms to that shape and played
a delve through them to both endings. So the question is no longer whether generated rooms read as
places. It is this:

> **DESCEND AGAIN puts you in a site nobody has seen, and the delve still works** — there is
> something to find, something to fight, a way out that asks, and a door you are not sure about.

The last clause is M4's unmet criterion 3, handed forward (`m4-evaluation.md` §2, §8). The authored
site could not answer it: seven goblins dealt 16 damage across five fights, and the brute gave no
warning before its door. The generator lays out every site from now on, so it has to budget fights
and make danger legible before a door. If it cannot, no authored tuning will survive the switch
(AGENTS.md: *re-measure on a generated site; do not tune the authored rooms for it*).

The 2026-08-20 clause about consistency still stands, and more rides on it. With every crossing
narrated (M4 §8d), the dress pass writes the only first-visit description a room will ever get.

---

## 2. What changed since the 2026-08-20 spec

Most of that spec's architecture was built by other milestones, in a different shape. Its reasoning
is still worth reading; these parts of its design are not current.

| 2026-08-20 said | Now | Settled by |
|---|---|---|
| One room loaded at a time; the renderer never holds more | Every room placed in one world frame anchored at the entrance; neighbours drawn dark; shared walls drawn once | M3, ADR-0009, ADR-0010 |
| `DungeonState` behind `GameRepository` | `WorldState`, a fold over the log. `GameRepository` is deleted | M2 |
| Dressing retained in memory, JIT on entry, covered by threshold narration | Dressing is `RoomDressed`, folded. Authored rooms emit it too | M3 §5b |
| Layout never stored, regenerated from the seed | **Reversed here:** structure is recorded at session start (§6) | this spec |
| Free movement outside combat, position as DM context | Built | M3 |
| Three.js stays; renderer re-examined later | Godot | ADR-0006 |
| A dungeon: rooms, corridors, a connectivity graph | **A site:** five rooms, bounded, with an ending | design doc §9, M4 §14 |
| Encounter balancing is out, needs a rules engine | In. M4 §5a's attrition ladder is the budget | M4 §5a, `emberdelve-q8o` |
| Dress pass writes a name, an overview, a sensory line, prop descriptions | The dress pass also writes secrets, fire lines and door signs. The generator decides that each exists; the model writes the words (§8) | M3 §12a, M4 §14 |
| Success: three rooms, each distinct | Success: a delve through an unseen site, to an ending (§11) | this spec |

**Still true, carried forward unchanged:**

- **Spatial legality** (2026-08-20 §6). Generated content is validated for world legality, not just
  enum membership. This spec extends it from rooms to sites (§5).
- **Reseed, never repair** (`RoomGenerator`). A site that fails validation gets a new attempt seed.
- **`Exit`, not `Door`**, and the environments beyond the crypt (2026-08-20 §7b). Still not built,
  and still something M1 must not preclude.
- **Runtime asset generation** stays narrowed as 2026-08-20 §8 narrowed it. The design doc's §16
  says "Never", and 2026-08-20 §8 is the better-argued ruling.
- **The mush risk** (2026-08-20 §11). Still the likeliest way this milestone fails, and still
  measured rather than argued about.

---

## 3. Scope

### In

- **A site generator.** Seeded topology, room roles, packing in world space, exits cut into walls
  (§4, §5).
- **Room contents by role.** Props from the kit's appearance layer, hidden props behind hosts,
  takeables, occupants and fires (§7).
- **Site rules as a validator** that the authored site passes too (§5a).
- **The site's structure in the log**, so a generated session replays after the generator changes
  (§6).
- **A dress pass that writes a site's secrets and signs** as well as its descriptions, dressing
  every room before play (§8).
- **Danger legible before the door** (`emberdelve-8xh`). Signs on exits, written from what the
  generator placed behind them (§8c).
- **A live seed.** DESCEND AGAIN is a new site. `--generate <seed>` pins one. The authored site
  stays bootable (§9).
- **Named creatures** (`emberdelve-5yj`), because a generator places more goblins than a person
  would bother to name (§7d).
- **Prerequisites that change what the generator writes:** the corrected attrition sim
  (`emberdelve-q8o`), fires on `LIGHT_OUT` (`emberdelve-4f9`), and skeleton copy (`emberdelve-87x`)
  (§10).
- Two played sessions on unseen seeds, and `docs/milestones/m1-evaluation.md` (§11).

### Out — and why

§13 has the full list with re-entry conditions. The ones most likely to be reached for:

- **Loops in the topology.** Trees only (§4b).
- **Locked doors** (`emberdelve-ql3`). Every door is open, and the dress prompt says so.
- **A variable room count.** Five, so M4 §5a's delve-length figures carry over.
- **New monster kinds, a new objective, a second environment.** Each is its own milestone's content.
- **Tuning the authored rooms** (`emberdelve-hhb`, `emberdelve-i97`). The generator replaces them as
  the default, so an evening spent on their scale and torch count is spent on the fixed point, not
  on play.

---

## 4. The site

### 4a. Roles are the specification made structural

M4 §14 describes a site as rooms with jobs. The generator needs those jobs as a closed enum, so the
rules can be checked and the dress pass can be told what it is dressing.

| `RoomRole` | Job | Authored instance |
|---|---|---|
| `ENTRANCE` | Holds the way out. A fight here is the lid's: the model's decision, at `goblinSpawn` | crypt |
| `PASSAGE` | Between the entrance and the objective. Something to find, maybe a wary mob | gallery |
| `OBJECTIVE` | Holds the reliquary. **No occupants**, so a rest after taking it is a real rest | chapel |
| `DEPTH` | Beyond the objective. The mobs | undercroft |
| `HOARD` | Deepest. The brute and the greed prize | vault |

The authored site has one room per role. The generator deals roles over a topology (§4b), so a site
can have two `PASSAGE` rooms and no `DEPTH` room. The counts stay inside the bounds in §5a.

**Authored rooms get a `role` field too.** That is how the claim "the authored site is the
generator's specification" becomes a test, not a sentence (§5a).

### 4b. Topology: a tree of five, rooted at the entrance

- **Five rooms.** The ending shows the site's total (M4 §9), and M4 §5a's whole-delve loss rates
  were computed for five.
- **A tree, never a cycle.** A cycle is a second path, and a second path is exactly what makes
  `emberdelve-xgg.9` reachable. `Rooms.origins()` already ignores a cycle's second path rather than
  reconciling it, and a greed decision needs no loops. Loops re-enter with the region (§13).
- **The objective is one or two crossings from the entrance.** Shallow, so the player can leave the
  moment they have it.
- **The hoard is at maximum depth, and never on the path to the objective.** Going to the hoard has
  to be a choice made after the reliquary, not a toll paid on the way to it.
- **Shapes worth generating:** the authored chain (entrance → passage → objective → depth → hoard),
  and a fork where the objective and the depth rooms sit on different arms off one passage. The
  fork turns the greed decision into a choice between doors as well as a choice about the way out.

`LayoutGenerator` from the old plan's Task 1 lifts nearly as written, with roles on the nodes. Its
grid-cell adjacency becomes a hint to packing (§5b), not the geometry itself.

---

## 5. Legality at site scale

### 5a. `SiteRules`, run against both kinds of site

One validator, pure, returning violations the way `SpatialValidator` does. It checks:

- **Topology.** Five rooms, a tree, every room reachable from the entrance, exactly one exit with
  `wayOut` (on the entrance), and every exit reciprocated.
- **Roles.** Exactly one `ENTRANCE`, one `OBJECTIVE` and one `HOARD`. The objective within two
  crossings of the entrance. The hoard at maximum depth and not on the objective's path.
- **Contents.** Every room has at least one hidden prop with a host (§7b). The reliquary is in the
  objective room and nowhere else. The objective room holds no occupants. No room holds a brute
  plus mobs.
- **Fires.** Where a room has a fires object, `to` differs from the initial preset in an allowed
  direction (§7e). At least one room per site moves brighter.
- **Budget.** Authored fights within §7c's bounds.

**The authored five-room site must pass `SiteRules`.** If the authored site fails a rule, then
either the rule is wrong or the authored site broke the specification. Both are findings, and
finding them costs no play.

### 5b. Packing: rooms that are not joined do not touch

`RoomOutline.beside` already places a neighbour so the doors line up. Packing composes that down
the tree, then rejects the attempt seed if:

- **Two rooms overlap.** A tree that bends back on itself (north, east, south) will put two rooms
  on one rectangle.
- **Two rooms not joined by a door share a wall plane.** Unjoined rooms keep at least one square of
  solid rock between them.

The second rule does more than keep the map honest. It makes `emberdelve-xgg.9` unreachable by
construction: every shared plane is a doorway's plane, and both rooms of a doorway are shipped
whenever either is. The bead asked for its decision *against a real generated dungeon*, and this is
that decision. Close it with a property test over generated seeds, and keep ADR-0010 unchanged.

### 5c. Exits into walls

`ExitPlacer` from the old plan's Task 2 lifts nearly as written. Each doorway becomes a perimeter
square and a `DOOR` prop whose id matches the exit's id (M3: the thing you see and the thing you
click are one object). `SpatialValidator` gains the two rules the old plan named:

- Nothing stands on an exit square or on its `inward` square.
- Every exit is reachable from every other exit and from the party's arrival square. **Hidden solid
  props count as solid**, so revealing a secret can never seal a room.

---

## 6. The site's structure is recorded

**Reversed here: the layout is stored after all.** 2026-08-20 §7 said structure is never stored,
because it can be regenerated from `(seed, coords)`, and M3 §5c inherited that. The argument was
about a world of tens of thousands of rooms. At five rooms a structure costs a few kilobytes, and
regenerating it costs something the old argument never priced: **the generator's code becomes part
of every log's meaning.** A change to `PropPlacer` would replay last week's evidence session
against a different site. It would not refuse to replay. It would diverge silently, and
`ReplayRunner` would report a stranger failure than a refused log ever gives.

So `SessionStarted` gets a sibling input event at session start:

```java
record SiteLaidOut(Instant at, long seed, List<RoomDefinition> rooms) implements Event {}
```

- **Structure only**, the M3 §5a half: shape, props, exits, occupants, the fires object's direction,
  and roles. No prose. The prose is `RoomDressed`, and it stays there.
- **Inert in the fold**, like `SessionStarted`. `Rooms` stays immutable and outside `WorldState`.
  What changes is where `ReplayRunner` gets `Rooms`: from this event, not from
  `Rooms.authored(content, ...)` guessed from `RoomDressed` ids.
- **The authored site emits it too**, the same trick as M3 §5b. The authored path exercises
  recording and replaying structure before any generated room exists, with no model and no seed.
- **The seed is recorded and no longer load-bearing for replay.** It is what `--generate <seed>`
  and `--dump <seed>` reproduce *on this build*, which is all a seed needs to do.

This adds an event, so `Event.SCHEMA_VERSION` goes to 4 and the M4 evidence sessions are refused,
per the standing rule. Re-record `crypt-fight.jsonl`. Keep the M4 logs in `docs/evidence/` as a
record, and replace them in the suite with an M1 session once one is played.

---

## 7. What goes in a room

### 7a. Props from the appearance layer

`PropPlacer` places by `PropType` and affinity today, and it knows nothing about appearances. The
kit gains an appearance list per type, drawn from `Appearances` (the closed set, so a model never
chooses a mesh), with minimums set high enough that a room is never one sarcophagus and floor. The
old plan's kit-density arithmetic still applies: at the crypt kit's current minimums, a median room
covers four per cent of its floor. **At least two kits per site** (M4 §14: *dressed from more than
one kit*).

Each role adds its own required props. `OBJECTIVE` gets the reliquary. `HOARD` gets the greed prize
(a second takeable, `chest_gold`, not a second objective). Every room gets its hidden prop's host.

`EndingReport.OBJECTIVE_NAME = "reliquary"` stays a shortcut. The objective is the same object in
every site, and the site pass (§8a) writes why it is here.

### 7b. Every room has something to find

This closes M3 §12a. A hidden prop is an `ALCOVE`, against a wall, directly behind a **host**: a
visible solid prop a player would search, preferring a `CONTAINER`, `FURNITURE`, `STATUE` or
`SARCOPHAGUS`. All five authored rooms follow this pattern: every secret is an `ALCOVE`, hinted on
the sarcophagus, the brazier and pillars, the shrine, the bookcase, or the gold chest. It became the
rule after M3, when the gallery niche was hinted on a west wall and both players searched the
brazier instead (M4 §12).

The generator decides that a secret exists, and where. The dress pass writes the `revealHint`,
which names the host, and the `description`, and may write `contains`. `reveal_prop` is then
offered in every generated room, and the DM cannot promise a secret the board will not deliver,
because the alcove was always standing on its square.

### 7c. Encounters, budgeted from the ladder

Occupants per role, as authored, with the generator choosing inside the bounds:

| Role | Occupants | Disposition |
|---|---|---|
| `ENTRANCE` | none authored; the `goblinSpawn` slot for the lid | model's decision |
| `PASSAGE` | none, or one mob | `WARY` |
| `OBJECTIVE` | **none** | — |
| `DEPTH` | two or three mobs | `WARY` |
| `HOARD` | one brute | `HOSTILE` |

Every room keeps a `goblinSpawn` slot, even when its occupant list is empty (M4 §14).

**The numbers in that table wait on `emberdelve-q8o`.** M4 §5a's ladder gave every foe a free
approach turn that play never gave. Without that turn, one brute from 16 HP goes from 14.4% to
24.8%, and two goblins from 12 HP from 8.2% to 18.0%. The corrected ladder decides whether "two or
three mobs" should read "two", and whether a goblin should hit harder (seven goblins, 16 damage).
Correct the sim before the generator encodes a budget in code.

**Brute plus mobs is never generated.** It is what ALERT does to a party that let it fill.

### 7d. Creatures have names

`emberdelve-5yj`. A generated site places four or five goblins before ALERT adds any, and they are
all Vessk. Names are a seeded draw from a content list, made when the entity is minted. They are
recorded in `EntitySpawned` like everything else about the entity, so replay needs no draw. Kind,
mesh and voice stay keyed on kind. A creature's name reaches the roll log and the initiative strip,
so it comes from a list, not from a model.

### 7e. Fires, and the direction they move

Each room gets a fires object or none. The generator picks the direction from the initial preset,
so no room is told to move somewhere it cannot:

| Initial | May move to |
|---|---|
| `TORCHLIT` | `DIM`, `DARK` |
| `DIM` | `TORCHLIT` or `DARK` |
| `DARK` | `DIM`, `TORCHLIT` |

`BRAZIERLIT` stays authored-only, as its javadoc says. At least one room per site moves brighter.
The dress pass writes `lit` and `moved` (§8b).

**`emberdelve-4f9` lands first, because it changes what a fires object means.** Once `LIGHT_OUT`
puts the current room's fires out, a room whose `to` is brighter needs a ruling: does a dead torch
darken a room whose fires were due to flare? Decide it in that bead, against the authored rooms,
before any generator writes a `to`.

---

## 8. The dress pass writes the site's words

### 8a. Two passes: the site, then its rooms

The 2026-08-20 spec named coherence as the decisive limit on a large world. At site scale, the
authored site was coherent because one person wrote it around one premise: Baron Aldus Vance, a
reliquary, a crypt that had been disturbed. Five independent room calls would produce five rooms
with no relationship to one another. So:

1. **One site call.** Given the topology, the roles, the kits and the occupants, it writes the
   site's name, a two-sentence premise, and why the reliquary is here. It writes no room prose.
2. **One call per room, all five in parallel**, each given the premise, its role, its structure,
   and what lies behind each of its exits (§8c).

The premise enters every room call and the narrator's `## The site` block. It is free-form model
text that enters the prompt and reaches no engine, which is the `assert_fact` rule, already written
down.

### 8b. What a room call writes

The `Dressing` record grows, and every new field is additive and nullable, so an authored room's
extraction (`Dressings.of`) keeps working:

| Field | Was | Now written for a generated room |
|---|---|---|
| `name`, `overview`, `sensory`, `propDescriptions` | dress pass | unchanged, with `overview` held to the §8d "only description it gets" standard |
| `revealHint`, `hiddenDescription`, `contains` per hidden prop | authored, on disk | dress pass (§7b) |
| `firesLit`, `firesMoved` | authored `fires.lit`, `fires.moved` | dress pass, for the direction the generator chose |
| `theDoor` | authored | dress pass, told every door is open |
| `exitSigns` | — | dress pass (§8c) |

This amends M3 §5a's three-way split. For a generated room, **secrets are model-written, so they
are dressing, and they fold.** The split survives as a lifetime rule: anything a model wrote is
recorded. The authored site keeps its secrets on disk and extracts them into the same record.

`dress-room.md`'s standing rules carry over and tighten: describe only the ids given, never say a
coordinate, never invent a lock or a missing handle (M4 §14), and call the figure a skeleton
(`emberdelve-87x`).

### 8c. Danger is legible before the door

`emberdelve-8xh`, and the reason M4's criterion 3 could not hold: the only warning before the vault
was a colder draft. **The generator knows what is behind every door.** The room call for the room
in front of a door is told, from a closed set, what the room behind it holds: `nothing`, `mobs`,
or `brute`. It writes a sign for that exit: armour shifting, a heavy breath, bootprints in the dust.

- **Delivered through `## Ways out`**, which M3 §7a restricts to directions, never destinations. A
  sign is neither. It is something heard at a door, and 8xh's acceptance asks for exactly this path.
- **Live, not static.** The sign prints only while the occupants behind that door are unreleased or
  alive. A sign for a dead brute is a copper key.
- **The kind of danger, never the size.** "Something heavy moves beyond the north door" helps the
  player decide. "A brute with 16 hit points" is a stat read aloud, which `dm.md` forbids.

### 8d. Dressed before play, behind the title

M3 §5a rejected eager dressing: *one model call per room before the player sees anything is not a
boot anyone would wait through.* That was true for sequential calls with no screen to cover them.
**Reversed here.** Six calls, five of them in parallel, cost about two calls of wall-clock time. The
title screen covers that wait the way the dice cover the prose phase, and DESCEND AGAIN already goes
back through the title (M4 §4e).

- **Why not dress on arrival.** Every crossing narrates, and an arrival line needs the room's
  dressing before it can start. On-arrival dressing would put a model call between the click and
  the arrival line, 2–4 seconds with nothing to cover it, on every first visit. That is the
  uncovered wait M0 found players notice.
- **Why not dress when a room comes into view as a neighbour.** It is cheaper at the first door and
  worse everywhere else. `RoomDressed` events would land in the log at arbitrary points mid-turn,
  and a fast crossing could still reach an undressed room.
- **Failure is a visible error, not a fallback.** A room whose dress call fails twice fails the
  boot with a toast, per the standing *Errors* shortcut, and DESCEND AGAIN tries a new seed.
  `GeneratedRoom`'s "This room has not been dressed yet." must never be read aloud. The keyless
  `--generate` path keeps its undressed room for tuning layout, and never reaches a title screen.

`BEGIN` waits for the dressing if a player clicks it before the calls return.

### 8e. Which model dresses

`App.java` chose `DM_MODEL_TOOLS` for the dress pass on a JSON-parse measurement (qwen3 parsed 3 of
3, the role-play model 0 of 4). The dress pass now writes the text the narrator treats as ground
truth, and AGENTS.md says of `qwen3-next-80b`: *hallucinates props … that safety is void the moment
anyone promotes it to prose.* A dress pass that invents a lantern writes a copper key into the room
before play starts.

**Candidate: a `DM_MODEL_DRESS` config string**, measured the way ADR-0008 requires: schema-
constrained JSON (`supportsResponseSchema`) on the prose model, against qwen3, over twenty dumped
sites, counting invented props and parse failures. The candidate wins the slot only on a played
session.

---

## 9. The seed, and what boots

- **Normal play draws a seed per delve.** `SessionStarted.seed` stops being `0L`. DESCEND AGAIN is a
  different site, and M4 §13's "a different site on restart" re-enters here.
- `--generate <seed>` pins a site. `--site authored` boots the authored five, which stay the fixed
  point every M4 measurement was taken in. The fixture tests and `SiteRules` use them.
- `--dump <seed>` prints the whole site with no key and no server: the tree, each room's grid, its
  roles, occupants, hidden props and fires. This is the old plan's `DungeonDumper`, and the §10 mush
  check reads its output.
- **The default flips to generated when the gate passes**, not before. Until then, the authored
  site is what a plain `./gradlew run` plays.

---

## 10. Before the generator: the beads that change what it writes

Each of these changes a number or a rule the generator would otherwise encode. Doing them after
means re-tuning the generator against a moving target.

| Bead | Why first |
|---|---|
| `emberdelve-q8o` | The encounter budget in §7c is this sim's output |
| `emberdelve-4f9` | Settles what a fires object means before a generator writes a `to` |
| `emberdelve-5yj` | A generated site multiplies goblins |
| `emberdelve-87x` | The dress prompt is rewritten in §8b; write it once, in the right noun |
| `emberdelve-pa8` | Generated prop ids (`pillar-2`) are more ambiguous anchors than authored ones, and grid coordinates still leak into `## Established` |

`emberdelve-zim` (a move onto furniture walks beside it) is not a prerequisite, and it gets more
valuable with every generated room. Worth landing alongside §7.

**The ALERT spec-versus-spec hole** (M4 evaluation §4: `on you` is unreachable while a fill zeros the
clock) is not the generator's. Record it as a bead if it is not one, and leave it out of this
milestone.

---

## 11. The gate

Two played sessions on seeds neither the player nor the author has dumped or read, both in the real
Godot client, one copied to `docs/evidence/` to replay in the suite, and
`docs/milestones/m1-evaluation.md`.

| # | Criterion |
|---|---|
| 1 | **Each session is a delve to an ending** on a generated site: the objective found, the way out asked. One of the two may be lost. |
| 2 | **Nothing on the board contradicts what the narrator was given.** Counted, not felt: door copy against `crossExit`, fire lines against the lighting shown, signs against what stood behind the door, reveal hints against the host a player searched. Invented content from play (corpse loot, the hotdog) is counted separately. It is a narrator finding, not a generator one. |
| 3 | **Something was found.** At least one `reveal_prop` per session, from a hint the player followed. |
| 4 | **Hurt, in front of a door, the decision is genuinely uncertain.** M4's criterion 3, re-asked on the site the generator budgeted, with a sign on the door. Judged by the player. If it fails again, that is a finding about the ladder and not a generator bug, and it gets a bead with the numbers. |
| 5 | **The two sites read as two places.** Judged by the player. Recorded beside it: the mush check, twenty `--dump`ed sites read before the sessions, noting whether room roles and premises repeat. |
| 6 | **Both sessions replay offline, identical**, and still replay after a deliberate change to `PropPlacer` on a scratch branch. §6 exists to make this row true. |
| 7 | **The authored site still passes `SiteRules`, boots under `--site authored`, and its fixtures replay.** |

Recorded, not graded:

- Time from DESCEND AGAIN to the title's BEGIN being live, and whether the wait read as a wait.
- Invented props in the dressing, per model (§8e).
- Whether a sign was heard and acted on, and whether the fork shape (§4b) produced a choice
  between doors.
- `FIRST FEEDBACK` over the sessions against M4's figures, since `## The site` and larger rooms
  lengthen the prompt.
- Whether a player clicked something solid and was refused (`emberdelve-zim`).

---

## 12. What lifts from the 2026-08-21 plan, and what does not

| Task | Fate |
|---|---|
| 1 — the dungeon's topology (`LayoutGenerator`, `LayoutValidator`) | **Lifts**, with roles on nodes and the tree rule (§4) |
| 2 — exits cut into walls (`ExitPlacer`, `SpatialValidator` exit rules) | **Lifts** (§5c) |
| 3 — look at the whole dungeon (`DungeonDumper`) | **Lifts**, as `--dump` (§9) |
| 4 — dressed once and kept | Built by M3 as `RoomDressed`. Extended in §8, not re-planned |
| 5 — state that survives leaving the room | Built by M3. Retired |
| 6 — walking through a door | Built by M3. Retired |
| 7 — where you may walk, out of combat | Built by M3. Retired |
| 8 — the client walks between rooms | Built by M3, in Godot. Retired |
| 9 — what the party is standing next to | Built by M3. Retired |
| 10 — narration covers the threshold | Superseded by M4 §8d and §8d here |
| Follow-ups: kit density, four algorithms | **Still good advice.** Kit density is §7a. BSP zoning stays a follow-up for after the mush check |

Nothing on the client side of that plan survives. Its file table names React files and a
`GameRepository` that no longer exist.

**Client work this spec expects**, all of it small because the client renders what it is sent:
a title that waits for dressing, the lintel carrying the site's name, and whatever larger or
oddly-placed room packing reveals in `world.gd`'s room table (`emberdelve-j1u` is the natural time
for that cleanup).

---

## 13. What M1 does not build

| Not built | Why | Re-enters when |
|---|---|---|
| Loops in the topology | A second path makes shared-wall ownership party-relative (`emberdelve-xgg.9`), and a site needs no loops | The region, where routes between sites are the point |
| Locked or shut doors | No content justifies one, and the prompt tells the truth (`emberdelve-ql3`) | A site role that needs a key, authored first |
| A variable room count | M4 §5a's delve figures are for five | The corrected sim says what six or seven costs |
| A second objective, or a different one | `OBJECTIVE_NAME` stays a shortcut | The campaign layer gives a site a reason other than the reliquary |
| New monster kinds | Goblin and brute are the ladder | The content and rules milestone |
| New `PropType`s | The kit's existing types plus appearances cover the authored site | A role needs a noun no type has |
| A second environment | 2026-08-20 §7b, unchanged | A site outside a crypt is wanted |
| Dressing on arrival, or prefetch | §8d | Six calls stop fitting behind the title |
| Tuning the authored rooms (`emberdelve-hhb`, `emberdelve-i97`) | The generator replaces them as the default | A generated room shows the same fault. `i97` is a DIM spacing rule, so it may |
| BSP zoning in `PropPlacer` | Fixes structure, and nobody yet knows the failure is structure | The mush check says rooms are perimeters with holes |
| Runtime asset generation | 2026-08-20 §8 | Its stated condition |
| The ALERT band hole | Spec-versus-spec in M4, not generation | Its own bead |

---

## 14. Order of work

1. **The prerequisites** (§10): `q8o`, `4f9`, `5yj`, `87x`, `pa8`. Each stands alone and each is
   playable on the authored site.
2. **Roles and `SiteRules`** (§4a, §5a), run against the authored site. No generation yet. This is
   the step where the specification becomes executable.
3. **`SiteLaidOut`** (§6), emitted by the authored site. The schema bump, a replay that builds
   `Rooms` from the log, and the fixture re-recorded.
4. **Topology** (§4b): `LayoutGenerator` with roles, and `--dump` of the tree.
5. **Packing and exits** (§5b, §5c): `ExitPlacer`, the overlap and touching rules, a property test
   that closes `xgg.9`, and `--dump` of the placed site.
6. **Contents by role** (§7): appearances in kits, hidden props behind hosts, takeables, occupants,
   fires. `SiteRules` passes on a thousand seeds.
7. **The dress pass** (§8): the site call, parallel room calls, the grown `Dressing`, signs in
   `## Ways out`, and the title waiting on it.
8. **The dress model measurement** (§8e) over twenty dumped sites.
9. **The live seed** (§9): DESCEND AGAIN draws one, and `--generate` and `--site authored` work.
10. **The mush check**, then **play the gate** (§11).

Steps 2 and 3 are invisible to a player and settle most of the design risk. Step 5 is the first
whose output can be looked at, and step 7 the first that can be played.

---

## 15. Open questions for review

Each of these is decided above with a recommendation. They are the ones worth a second opinion
before approval.

1. **Record the structure (§6), or stamp a generator version and refuse mismatched logs?**
   Recording keeps evidence sessions replaying forever and costs a schema bump now. Refusing is
   smaller, and it means every generator tweak retires the evidence.
2. **Two dress passes, site then rooms (§8a), or rooms alone?** The site pass is the answer to
   coherence and costs one serial call. The old plan's advice was to run the mush check before
   reaching for any fix.
3. **Signs on exits (§8c): from the dress pass, or engine-owned sentences like `ClockTables`?**
   Engine sentences are consistent and can never overstate the danger. Dress-pass signs sound
   like the room. The draft picks the dress pass, with a closed set of danger kinds as input.
4. **Should the objective room be guaranteed empty (§7c)?** It is what made the M4 rest a real one,
   and it is the answer to M4 §13's open question about a room the party can rest in. It also makes
   the objective room predictable.
5. **What does `LIGHT_OUT` do to a room whose fires were due to move brighter (§7e)?** Belongs to
   `emberdelve-4f9`; flagged here because the generator cannot write a `to` until it is answered.
6. **Does criterion 4 gate M1?** M4 passed without its version of it. The draft makes it a
   criterion because the generator is now the thing that owns the budget. A second failure would
   then fail M1 rather than being carried forward again.
