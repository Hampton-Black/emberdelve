# M4 — Delve evaluation

**The deliverable of the milestone.** Spec §11 is one question in six criteria:

> Can you lose a delve, and does losing it sting?

**Status: PASS, signed 2026-09-13 against two logged Godot sessions on `m4-delve`. See §7.** The
first run took the reliquary, chose the north door, and died in the vault holding it. The second
took the reliquary and walked south: *Roderick came out.* Spec §11's two-session shape is now
played. Empty-handed extract was not. **Criterion 3 was not met** — no door in either run was a
hard call (§2, §4). The headline question does not rest on it; the generator plan does.

---

## 1. What was under test

Two sessions, both in the Godot client on 2026-09-13, against a fresh server on the M2 model split.
Crossings were door clicks. Chrome buttons took the reliquary, the potion, the torch, and the rest
when they were there. Typed turns are what the logs count.

| | Gate — greed and loss | Extract — the easy way out |
|---|---|---|
| Log | `../evidence/session-m4-delve.jsonl` (copy of `server/sessions/20260913-134225-bf6ea78b.jsonl`) | `../evidence/session-m4-extract.jsonl` (copy of `server/sessions/20260913-151939-d31a46aa.jsonl`) |
| Player | the author, in Godot, voice on | same |
| Build | `m4-delve` at `8562fed`, plus the first evaluation | same branch, after that evaluation |
| Typed turns | **41** | **16** |
| Crossings | **8** (`crypt→gallery→chapel→gallery→crypt→gallery→chapel→undercroft→vault`) | **4** (`crypt→gallery→chapel→gallery→crypt`) then `stair-south` |
| Clock time | ~95 minutes (`13:42:25Z`–`15:17:14Z`) | ~60 minutes (`15:19:39Z`–`16:19:32Z`) |
| Outcome | Reliquary taken; rest 12→16 HP; went deeper; **`PARTY_LOST` / `FELL_WITH_HIM`** | Reliquary taken; no rest; south; **`EXTRACTED_WITH_OBJECTIVE`** |
| `DM_MODEL_TOOLS` | `qwen3-next-80b` | same |
| `DM_MODEL_PROSE` | `gemini-3-8-flash` (`reasoning_effort=low`) | same |
| Voice | OS TTS (Daniel / Ralph), keyed on kind | same |

A third log, `20260913-162000-4b9efed5.jsonl`, is a five-event boot after the extract. It is not a
session.

---

## 2. The gate, point by point

| # | Criterion | Status |
|---|---|---|
| 1 | Take the objective, then choose between leaving with it and going deeper | **Held, twice.** Greed run: click-took the reliquary, rested, walked north. Extract run: typed `take_prop`, walked south, `stair-south`. |
| 2 | A second session in which the delve is lost | **Held.** Greed run: Brakk killed Roderick in the vault. Ending `PARTY_LOST`. The extract run is the other file spec §11 asked for, not a second wipe. Empty-handed extract was not played. |
| 3 | Hurt, in front of a door, the decision is genuinely uncertain | **Not met.** Spec §11: *if every door was an easy call in either direction, the numbers failed.* Lowest HP at any door was 10 (greed, crypt→gallery, potion clicked on arrival). The vault door was taken at 16/20, potion in hand, with nothing to say what was behind it. The extract run was at 20 HP throughout. Seven goblins in five fights dealt 16 damage in total; three of those fights dealt none. The death was a tail roll, not a doubt window (§4). They named it too greedy, and fun — both true, neither the criterion. |
| 4 | Whether the clock was felt | **LIGHT was, both runs. ALERT was.** Greed: natural 1 filled LIGHT to `OUT` in the gallery; a spare torch; a follower. Extract: LIGHT died on the gallery→crypt crossing in the same bundle as `PATROL_ARRIVES`, the crypt's braziers went `DARK`, and the player liked that total dark more than the gallery still-lit bowls. See §4. |
| 5 | Whether losing stings when nothing persists | **Held, by the player, on the greed run.** "1st run I was too greedy and died! that was fun." The extract run is the contrast that makes that sting readable: they *can* walk out. |
| 6 | The kept sessions replay offline | **Greed: `replayed 207 events: identical`** (`theM4GateSessionReplays`). **Extract: `replayed 87 events: identical`** (`theM4ExtractSessionReplays`). Schema 3. |

---

## 3. What happened

### Greed run (41 typed turns)

Hit points from the swing log: start 20.

**Crypt.** Inspect the sarcophagus (investigation 6 vs 15). Push the lid (athletics 20 vs 20).
`spawn_entity` put Vessk on the dais. The player asked what he was doing in there; persuasion 13 vs
15, and reconcile called `start_combat` — not the look, not the scream that followed. Vessk won
initiative, hit twice for 5, died on the fighter's third swing. Roderick at **10**. Corpse search
found a rusted key the board does not have. Crypt alcove revealed: clay lamp, black cloth.

**Gallery, first visit.** Crossed at 10 HP; potion clicked on arrival → **18**. Dry cold, fallen
ceiling, cold west brazier. Niche revealed. Six clay tokens taken. A look into the chapel, then
chapel→gallery→crypt→gallery inside two minutes — three of LIGHT's first five ticks.

**Gallery, again.** A follower at `SOMETHING_WANDERS_IN`. Failed persuasion, natural 1, LIGHT filled
to `OUT`. The player credited the natural 1; five of the six ticks were crossings. The prose said
pitch darkness. The gallery's wall torches did not go out — LIGHT spends the party's torch, not the
room's fires. Spare torch spent (1 remaining), LIGHT back at 0. The player then clicked the niche's
`SIGIL` three times; each click ran a full turn with checks, and on the second — a natural 2 on
perception — reconcile spawned a second Vessk (`emberdelve-5md`). The player offered the tokens,
then attacked. Two-goblin fight: first swing killed goblin-2; goblin-3 lasted. Roderick **18 → 12**.
Corpse search invented a vial and a spare torch; the counters did not move. Chapel door.

**Chapel.** Empty, as authored. Click-took the reliquary. Inspected the shrine niche, packed the
votive and cloth in narration (no `prop_taken`). Rest 12→**16**. North, not south.

**Undercroft.** Two wary goblins on first visit, grave-dust, notched blades. The player asked if
they would give trouble too; failed persuasion; reconcile `start_combat`. Neither goblin landed.
Roderick still **16**. Bookcase, empty coffin, barrel, niche with a lead cylinder. Markers for the
cylinder and the vellum inside it. A ledger names Baron Aldus Vance. North.

**Vault.** Dark room, gold chest, stone stag. Authored brute Brakk, `HOSTILE`, engine `combat.start`
on the crossing — no `start_combat` tool. Brakk won initiative and missed. Roderick missed on a
natural 2. Brakk hit 7 (Roderick at 9). Roderick hit 6 (Brakk at 10). Brakk rolled exactly 16
against AC 16 for **10** — the most 1d8+2 can do — and killed him. `PARTY_LOST`. The greed chest was
never touched. Everything said about the vault before the door was a colder draft
(`emberdelve-8xh`).

Returns along the way were returns. The gallery "just as you left them." The crypt still had Vessk
on the flags and the two green braziers. Arrival speech fired on every crossing, first visits and
returns both.

### Extract run (16 typed turns)

Hit points stayed **20**. Two fights, both one-swing kills after a miss.

**Crypt.** Inspect, shove (athletics 21 vs 20), look inside. Spawn Vessk. A persuasion 21 vs 15
held the parley; two natural 1s on the next questions did not. The player then attacked — mechanics
`start_combat` on "fine so be it". Vessk died on the second swing. Empty sarcophagus: bone-meal and
goblin bootprints. North.

**Gallery, chapel.** Listen for a stalker, hear nothing. Chapel: inspect the reliquary for traps
(investigation 13 vs 10), typed `take_prop`. No rest. South, not north.

**Gallery→crypt, the way out.** LIGHT filled to `OUT` on the crossing. ALERT filled on the same
bundle: `IT_IS_CLOSE`, then `PATROL_ARRIVES`. The crypt's fires moved `BRAZIERLIT` → `DARK`. Engine
combat on the patrol. Arrival: torch dead, braziers snuffed, something small by the sarcophagus.
Legal-move highlights painted the gallery during that arrival; clicks were already the crypt, and
the overlay caught up after the first turn. The player shouted, failed persuasion, killed the
second Vessk in one hit.

**Torch, then out.** Exploration bar still gone after the fight. Typed "I frantically pull out
another torch." The prose lit it. `use_item` never fired. LIGHT stayed at 6. Two `SCORCH` markers:
"Flint sparks struck here, igniting the torch." Clickable. Then `stair-south`. Close narration:
grey daylight, wet pine, the weight of the reliquary. `EXTRACTED_WITH_OBJECTIVE`.

---

## 4. Faults, and what they actually were

**Failed parley starting a fight is desired.** `emberdelve-vy7` took `start_combat` off look-around
and off the two-failure ladder. Both sessions left the inspect alone. Both started a fight after a
failed question to a wary goblin, and on the extract run the player held a successful persuasion
first and only swung after two natural 1s. Judged in play: that feels right, and it is not a
ticket. ALERT remains the only unprovoked fight. Do not plan a prompt patch to stop this.

**Every goblin is named Vessk.** Greed run: five goblin ids, one name. Extract run: crypt Vessk and
the patrol, also Vessk. Brakk is the exception because he is a brute. Filed `emberdelve-5yj`. The
meshes are skeletons; the copy still says goblin — `emberdelve-87x`.

**LIGHT_OUT did not darken the gallery (greed run).** The party torch died. The wall torches did
not. That is spec §7c: ALERT moves `fires`, a LIGHT fill does not. The prose said pitch darkness
anyway.

**LIGHT_OUT *and* the crypt braziers went together (extract run), and that is the look they want.**
Same crossing: LIGHT fill, ALERT fill, `room_lighting_changed` crypt `BRAZIERLIT` → `DARK`. The
player's torch died and the green bowls died with it. Judged: total darkness is better, and feel
overrides the spec's "one clock meaning two things." Follow-on, not patched in front of this
gate. It was not a coincidence: LIGHT filling ticks ALERT (§6e), so that crossing took ALERT 4→5→6
and `IT_IS_CLOSE` fired in the same bundle as `PATROL_ARRIVES` — the warning never got to warn. The
two natural-1 ticks in the crypt are what put `LIGHT_OUT` on that crossing.

**The vault death was a tail roll, and the sim behind §5a is optimistic.** From 16 HP one brute
beats the fighter 14.4% of the time in `m4-attrition-sim.py`. That figure gives every foe a free
turn to close; in both sessions every hostile swung on its first turn, Brakk from three squares.
Without the approach turn it is **24.8%**, two goblins from full go 1.3% → 3.3%, and a brute at
5 HP 54% → 71%. §5a's ladder and delve rates carry the same assumption into the generator plan
(`emberdelve-q8o`). Nothing before the door said brute — the undercroft gave a colder draft — and
the player, off two trivial goblin fights, read it as safe (`emberdelve-8xh`). §5a says the danger
has to be legible before the door. It was not.

**Clicking a marker is a whole turn.** `WsHandler.inspectMarker` submits "I look at the SIGIL: …"
as free text, so the mechanics pass runs. The gallery `SIGIL` on the already-emptied niche was
clicked four times; three clicks rolled checks and one spawned goblin-3 (`emberdelve-5md`). Markers
are also standing in for props: a `SIGIL` on the revealed niche's own square, `SCORCH` for a lead
cylinder, a vellum scroll and a torch strike. The purple glyphs the player took for "the clickable
things" are those.

**A move onto something solid is refused rather than resolved.** "Inspect the chair" and "inspect
the coffin" put `move_entity` on the object's own square four times across both runs, and a click
on furniture gets the same refusal in the client (`emberdelve-zim`).

**A fact recorded the player's line as the goblin's.** 14:43:22, anchored on goblin-2: *The goblin
spoke: "I can see you. Why are you following me?"* The vellum fact anchored on the coffin. Added to
`emberdelve-pa8`.

**The ALERT band `on you` is unreachable.** Spec §8b wants `quiet` / `stirring` / `hunting` / `on
you` at filled 6. Spec §6f zeros ALERT on fill. After `SOMETHING_WANDERS_IN` / `PATROL_ARRIVES` the
next prompt says the site is quiet. Spec-versus-spec. Left for the generator plan.

**Invented loot on a corpse (greed run).** A potion and a torch, asserted, durable, counters
unchanged. The hotdog class, now on a body. The player judged this working as designed.

**Typed torch after combat did not light (extract run) — and it is not `0v6`.** They asked the DM
because the bar was gone, but the bar is not why it failed. The server offered `use_item`: combat
over, two torches, LIGHT full (`ToolSchema.usableItems`). `qwen3-next-80b` dropped the call, and
reconcile, which has no `use_item`, placed two `SCORCH` markers for the strike instead. LIGHT stayed
out. That is the dropped-tool-call failure mode, and it would have happened with the button on
screen; the greed run's typed torch at 14:44 fired correctly. Two roots, not one.

`0v6` itself is client-side: the bar hides while `Table.combat_beat` is non-null, a fight's end
only marks the beat closing, and nothing clears it until the next room entry ships a fresh scene.

**Combat highlights in the neighbour (extract run).** `PATROL_ARRIVES` started a fight while
arrival was still being spoken. Legal-move quads showed in the gallery. Movement itself was the
crypt; the overlay caught up after the first turn.

**Chrome leftovers, filed, not gate-fail.** Exploration bar stays gone after a fight
(`emberdelve-0v6`) — extract run confirmed it. Potion, torch, and take clicks are silent
(`emberdelve-kac`); rest already narrates. Arrival glues onto the previous transcript line
(`emberdelve-2ks`); dialogue carries the model's blank lines in with it (`emberdelve-7o6`). Ending
page sits top-right (`emberdelve-vq3`). No current-room name in the
chrome (`emberdelve-eql`). Chapel candles clip the floor (`emberdelve-z0w`). `POTION (2)` copy
(`emberdelve-8kf`). Crypt skull and lantern read too big (`emberdelve-hhb`). Gallery DIM still
hangs enough wall torches to wash the aisle (`emberdelve-i97`). Legal-move highlights in the
neighbour during arrival combat (`emberdelve-clw`). LIGHT_OUT should take the room's fires with
it (`emberdelve-4f9`).

**Not a miss:** only actioned props are pick targets (`emberdelve-7m6`). The greed chest is not a
second objective. Chapel stayed empty so the rest after the reliquary was a real rest. The vault
brute is a different fight from two goblins, and the player said so. Close on extract was heard.

---

## 5. Recorded, not graded (spec §11)

| Item | Greed run | Extract run |
|---|---|---|
| Arrival speech, first vs return | **8 crossings, all narrated.** Returns named what was left. | **4 crossings, all narrated.** Crypt return named the dead torch and the snuffed braziers. |
| Bar lock after a crossing | Not remarked as grating. | Same. The bar bug that *was* felt is 0v6, after combat, not after a door. |
| Flicker and radius; hostiles in the dark | Vault is `DARK`; Brakk read. | Crypt went `DARK` with LIGHT `OUT`. The patrol read as breath and a blade in the black. |
| `OUT` in a `DARK` room | Not reached. | **Reached.** Torch out, braziers out, patrol in the dark. Judged better than a dead torch under still-lit bowls. |
| Close heard or skipped; wipe seen before told | Wipe was combat; ending page followed the kill. | **Close heard.** Grey daylight, wet pine, the weight of the reliquary. Way-out confirm not remarked as noise. |
| *Roderick came out.* | Unplayed. | **Played.** They left with it. |
| Turn-1 escalation with the rule gone | Did not recur on the inspect. Combat on failed talk, twice. | Inspect spawned; a persuasion *success* held; combat on "I attack" after two nat 1s. Patrol fight was ALERT, not talk. |
| Failing check after check, nothing escalates | Not this shape. | Two nat 1s in a row, then the player started the fight themselves. |
| Board and DM agree more often | Mixed. Reliquary click, four reveals, markers. Invented corpse loot. | Reliquary `take_prop` matched. Typed torch did not: marker, no `use_item`. |
| A prop revealed and then crossed away from | **Exercised** (alcove, niches). | No `reveal_prop` this run. |

`roll_check` twice on one action: **absent** from both sessions (and from the 2026-09-07 playtest).
Still not called fixed.

---

## 6. The numbers that are not the gate

**Greed.** 41 typed turns, 2547 characters typed, 159 narration segments, 19196 characters spoken.
Four fights, one rest, one potion, one torch. 25 checks. 4 markers. 4 reveals. 6 spawns. Replay
compared **207** engine events of 539 lines.

**Extract.** 16 typed turns, 723 characters typed, 66 narration segments. Two fights, no rest, no
`item_used`. 10 checks (including two nat 1s). 2 markers, both fake torch-strikes. 2 spawns. Replay
compared **87** engine events of 209 lines.

---

## 7. Verdict

The mechanical half was already true before either session: clocks tick on a crossing, a rest is
refused while anything living and hostile is in the room, the reliquary is the objective, a
HOSTILE occupant starts the fight the model is no longer allowed to start for free, and a lost
delve has a page. The played half was not.

The greed run took the thing the site is for, walked past the way out holding it, and died in the
last room. They were not at 5 hit points in front of a door. They were at 16, rested, with no sign
of what was behind it, and lost a fight they win three times in four or better — on the brute's
best possible swing. They said the death was fun, and it was. It was not the doubt window.

**Criterion 3 is not met, and the pass does not rest on it.** The question was whether a delve can
be lost and whether losing stings; both played true. Whether a hurt party at a door faces a real
decision is what the attrition numbers were built to answer, and neither run reached it: goblins
do not spend enough hit points, and the one fight that does was invisible from the door. That goes
to the generator, which inherits §5a as its encounter budget.

The extract run walked the other door. Close was heard. They came out. That is what makes the first
death a choice rather than a script.

> **M4 gate: PASS** — signed 2026-09-13. A 41-turn Godot session took the reliquary, rested in the
> empty chapel, chose the north door, and fell in the vault with it. A 16-turn session took the
> same box and climbed the south stair into grey daylight. Both logs replay identical. You can
> lose a delve, you can leave with what you came for, and the first of those stung. That was the
> question. No door in either run was a hard call; that one stays open.

---

## 8. Carried out of M4

Findings, not tasks. The generator plan is next; these ride with it. Playtest leftovers are
standalone beads, not children of the epic: `emberdelve-0v6`, `kac`, `2ks`, `5yj`, `vq3`, `eql`,
`z0w`, `8kf`, from the extract run `hhb`, `87x`, `i97`, `clw`, `4f9`, and from reading both logs
afterwards `7o6`, `5md`, `zim`, `q8o`, `8xh`.

- **The doubt window was never reached.** Seven goblins in five fights dealt 16 damage; three
  fights dealt none. The lowest HP at any door was 10. The brute kills, but from 16 HP that is a
  tail (14–25%), not a window, and nothing before the door announced it (`emberdelve-8xh`).
  Encounter, not max HP, is still what places the window — and the sim that placed it gives every
  foe a free approach turn play never gave (`emberdelve-q8o`).
- **Markers have become the prop affordance, and a click on one is a full turn** with checks and
  spawns behind it (`emberdelve-5md`).
- **Failed talk starting a fight is the game.** Look-around no longer does. Do not "fix" a wary
  goblin that draws steel after a failed persuasion; play asked for that.
- **LIGHT fill and room fires are different objects, and play now wants them coupled when the
  torch dies.** A dead party torch under still-lit bowls (greed, gallery) was correct by spec §7c
  and wrong by eye. Torch *and* braziers out (extract, crypt) was an ALERT fill landing on the
  same crossing, and it is the look they want. Follow-on: `emberdelve-4f9`. Feel overrides the spec.
- **ALERT's band and ALERT's fill cannot both be true as written.** `on you` is dead code while
  a fill zeros the clock.
- **Every goblin is Vessk.** `emberdelve-5yj`. And they are written as goblins in front of
  skeleton meshes (`emberdelve-87x`).
- **Invented loot on a corpse is the hotdog, now portable.** Fiction the counters will not honour.
- **`reveal_prop` can still beat the sentence.** Not the loud finding this time, and still true.
- **Generated rooms still have nothing to find**, and now they also have no occupants. The
  authored site is the specification: chapel empty on purpose, undercroft two `WARY` goblins,
  vault one `HOSTILE` brute, crypt's goblin still a model decision behind a lid. Disposition is
  fire-time. `spawn_entity` stays goblin-only.

**M1 stays open.** The delve is a run you can lose, and a run you can leave. The world still
cannot be made rather than authored at dungeon scale.
