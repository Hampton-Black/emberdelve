# M4 — Delve evaluation

**The deliverable of the milestone.** Spec §11 is one question in six criteria:

> Can you lose a delve, and does losing it sting?

**Status: PASS, signed 2026-09-13 against two logged Godot sessions on `m4-delve`. See §7.** The
first run took the reliquary, chose the north door, and died in the vault holding it. The second
took the reliquary and walked south: *Roderick came out.* Spec §11's two-session shape is now
played. Empty-handed extract was not.

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
| 3 | Hurt, in front of a door, the decision is genuinely uncertain | **Held as greed, not as 5 HP.** The greed run was at 12 HP after the gallery, 16 after the rest — never five in a doorway. They named the death: too greedy, and it was fun. The extract run was at 20 HP the whole way and took the easy door on purpose. |
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
initiative, hit twice for 5, died on the fighter's third swing. Roderick at **10**. Potion → **18**.
Corpse search invented a vial and a spare torch; the counters did not move. Crypt alcove revealed.
Clay lamp, black cloth, a key the board does not have.

**Gallery, first visit.** Dry cold, fallen ceiling, cold west brazier. Niche revealed. Six clay
tokens taken. A follower at `SOMETHING_WANDERS_IN`. Failed persuasion, natural 1, LIGHT filled to
`OUT`. The prose said pitch darkness. The gallery's wall torches did not go out — LIGHT spends the
party's torch, not the room's fires. Spare torch spent (1 remaining), LIGHT back at 0. The tools
model spawned a second Vessk. The player offered the tokens, then attacked. Two-goblin fight: first
swing killed goblin-2; goblin-3 lasted. Roderick **18 → 12**. Chapel door.

**Chapel.** Empty, as authored. Click-took the reliquary. Inspected the shrine niche, packed the
votive and cloth in narration (no `prop_taken`). Rest 12→**16**. North, not south.

**Undercroft.** Two wary goblins on first visit, grave-dust, notched blades. The player asked if
they would give trouble too; failed persuasion; reconcile `start_combat`. Neither goblin landed.
Roderick still **16**. Bookcase, empty coffin, barrel, niche with a lead cylinder. Markers for the
cylinder and the vellum inside it. A ledger names Baron Aldus Vance. North.

**Vault.** Dark room, gold chest, stone stag. Authored brute Brakk, `HOSTILE`, engine `combat.start`
on the crossing — no `start_combat` tool. Brakk won initiative, missed, missed, hit 7 (Roderick at
9), took 6 (Brakk at 10), hit **10** and killed him. `PARTY_LOST`. The greed chest was never
touched.

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
gate.

**The ALERT band `on you` is unreachable.** Spec §8b wants `quiet` / `stirring` / `hunting` / `on
you` at filled 6. Spec §6f zeros ALERT on fill. After `SOMETHING_WANDERS_IN` / `PATROL_ARRIVES` the
next prompt says the site is quiet. Spec-versus-spec. Left for the generator plan.

**Invented loot on a corpse (greed run).** A potion and a torch, asserted, durable, counters
unchanged. The hotdog class, now on a body. The player judged this working as designed.

**Typed torch after combat did not light (extract run).** Same missing exploration bar as
`emberdelve-0v6`. They asked the DM because the button was gone. The model narrated a strike,
placed a clickable `SCORCH`, and never called `use_item`. LIGHT stayed out. Recurs on 0v6, not a
second root.

**Combat highlights in the neighbour (extract run).** `PATROL_ARRIVES` started a fight while
arrival was still being spoken. Legal-move quads showed in the gallery. Movement itself was the
crypt; the overlay caught up after the first turn.

**Chrome leftovers, filed, not gate-fail.** Exploration bar stays gone after a fight
(`emberdelve-0v6`) — extract run confirmed it. Potion, torch, and take clicks are silent
(`emberdelve-kac`); rest already narrates. Arrival glues onto the previous transcript line
(`emberdelve-2ks`). Ending page sits top-right (`emberdelve-vq3`). No current-room name in the
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
last room. They were not at 5 hit points in front of a door. They were at 16, rested, greedy, and
the vault is what the brute is for. They said the death was fun.

The extract run walked the other door. Close was heard. They came out. That is what makes the first
death a choice rather than a script.

> **M4 gate: PASS** — signed 2026-09-13. A 41-turn Godot session took the reliquary, rested in the
> empty chapel, chose the north door, and fell in the vault with it. A 16-turn session took the
> same box and climbed the south stair into grey daylight. Both logs replay identical. You can
> lose a delve, you can leave with what you came for, and the first of those stung. That was the
> question.

---

## 8. Carried out of M4

Findings, not tasks. The generator plan is next; these ride with it. Playtest leftovers are
standalone beads, not children of the epic: `emberdelve-0v6`, `kac`, `2ks`, `5yj`, `vq3`, `eql`,
`z0w`, `8kf`, and from the extract run `hhb`, `87x`, `i97`, `clw`, `4f9`.

- **The doubt window sat in the vault, not at 5 HP in a doorway.** Goblins still do not spend
  enough hit points to make the chapel door a 5 HP problem. The brute does. Encounter, not max
  HP, is still what places the window — the map's own finding, confirmed rather than overturned.
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
