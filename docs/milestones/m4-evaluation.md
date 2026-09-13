# M4 — Delve evaluation

**The deliverable of the milestone.** Spec §11 is one question in six criteria:

> Can you lose a delve, and does losing it sting?

**Status: PASS, signed 2026-09-13 against one logged Godot session of 41 typed turns on `m4-delve`.
See §7.** Spec §11 asked for two sessions — take-the-objective-then-choose, and a second session
that is lost. This run did both in one: the reliquary left the chapel, the player chose the north
door, and the vault killed them holding it. The extract-and-leave endings were not played. That is
a process deviation, named here, not a failed gate.

---

## 1. What was under test

One session, played end to end in the Godot client on 2026-09-13, against a fresh server on the M2
model split. Crossings were door clicks. Chrome buttons took the reliquary, the potion, the torch,
and the rest. Typed turns are what the log counts.

| | Gate session |
|---|---|
| Log | `../evidence/session-m4-delve.jsonl` (copy of `server/sessions/20260913-134225-bf6ea78b.jsonl`) |
| Player | the author, in Godot, voice on |
| Build | `m4-delve` at `8562fed`, plus this evaluation |
| Typed turns | **41** |
| Crossings | **8** (`crypt→gallery→chapel→gallery→crypt→gallery→chapel→undercroft→vault`) |
| Clock time | ~95 minutes (`13:42:25Z`–`15:17:14Z`) |
| Outcome | Reliquary taken in the chapel; rest 12→16 HP; went deeper; **`PARTY_LOST` / `FELL_WITH_HIM`** in the vault |
| `DM_MODEL_TOOLS` | `qwen3-next-80b` |
| `DM_MODEL_PROSE` | `gemini-3-8-flash` (`reasoning_effort=low`) |
| Voice | OS TTS (Daniel / Ralph), keyed on kind |

A second log, `20260913-151939-d31a46aa.jsonl`, is a five-event boot. It is not a session.

---

## 2. The gate, point by point

| # | Criterion | Status |
|---|---|---|
| 1 | Take the objective, then choose between leaving with it and going deeper | **Held, in this run.** Click-took the reliquary (`objective_taken`, chapel). Rested. Walked north into the undercroft instead of south toward the way out. |
| 2 | A second session in which the delve is lost | **Held as an event, not as a second file.** Brakk killed Roderick in the vault. Ending `PARTY_LOST`. Spec asked for a separate session; the ticket asked for a played session. One file contains both required events. Extract-with-objective and empty-handed extract were not played. |
| 3 | Hurt, in front of a door, the decision is genuinely uncertain | **Held as greed, not as 5 HP.** The player was at 12 HP after the gallery, 16 after the rest, never hovering at five in front of a door. They named the death themselves: too greedy, and it was fun. That is the decision the chapel's north door is for. |
| 4 | Whether the clock was felt | **LIGHT was. ALERT was, once.** A natural 1 on persuasion filled LIGHT to `OUT` in the gallery; the player lit a spare torch and hunted in the dark. They also caught a follower (`SOMETHING_WANDERS_IN`) and spoke to it. The four ALERT band words never appear on the wire, and after a fill the prompt says *quiet* because the clock zeros — see §4. |
| 5 | Whether losing stings when nothing persists | **Held, by the player.** "1st run I was too greedy and died! that was fun." The sting was the run: the reliquary in hand, the spare torch already spent, the vault still ahead. Nothing persists, and they did not ask for it to. |
| 6 | The kept session replays offline | **`replayed 207 events: identical`.** Pinned as `theM4GateSessionReplays`. Schema 3. |

---

## 3. What happened

Forty-one typed turns, plus the opening, in order. Hit points from the swing log: start 20.

**Crypt.** Inspect the sarcophagus (investigation 6 vs 15). Push the lid (athletics 20 vs 20).
`spawn_entity` put Vessk on the dais. The player asked what he was doing in there; persuasion 13 vs
15, and reconcile called `start_combat` — not the look, not the scream that followed. Vessk won
initiative, hit twice for 5, died on the fighter's third swing. Roderick at **10**. Potion → **18**.
Corpse search invented a vial and a spare torch; the counters did not move. Crypt alcove revealed.
Clay lamp, black cloth, a key the board does not have.

**Gallery, first visit.** Dry cold, fallen ceiling, cold west brazier. Niche revealed. Six clay
tokens taken. A follower at `SOMETHING_WANDERS_IN`. Failed persuasion, natural 1, LIGHT filled to
`OUT`. The prose said pitch darkness. The gallery's remaining fires did not go out — LIGHT spends
the party's torch, not the room's `fires`. Spare torch spent (1 remaining), LIGHT back at 0. The
tools model spawned a second Vessk. The player offered the tokens, then attacked. Two-goblin fight:
first swing killed goblin-2; goblin-3 lasted. Roderick **18 → 12**. Chapel door.

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

---

## 4. Faults, and what they actually were

**Failed parley is still a fight.** `emberdelve-vy7` took `start_combat` off look-around and off
the two-failure ladder. This session did not escalate on the inspect. It did escalate on the first
failed question, twice: crypt turn 3, undercroft approach. ALERT remains the only unprovoked fight;
a failed "what were you doing in there" is still the model's idea of provoked. Recorded. The
player then committed ("You're going to pay for that", "alright so be it") so the fights were not
stolen from an ongoing parley the way M3's were.

**Every goblin is named Vessk.** Five goblin ids, one name. Brakk is the exception because he is a
brute. Filed `emberdelve-5yj`.

**LIGHT_OUT did not darken the gallery.** The party torch died. The room's fires did not. That is
the fold: ALERT's `THE_FLAME_LEANS` / `IT_IS_CLOSE` move `fires`, a LIGHT fill does not. The prose
said pitch darkness anyway. Feel finding, not a missed wire. A player who is looking at Godot sees
bowls that are still lit.

**The ALERT band `on you` is unreachable.** Spec §8b wants `quiet` / `stirring` / `hunting` / `on
you` at filled 6. Spec §6f zeros ALERT on fill. After `SOMETHING_WANDERS_IN` the next prompt says
the site is quiet. Spec-versus-spec, not spec-versus-CONTEXT. Left for the generator plan to
notice; not patched in front of this gate.

**Invented loot on a corpse.** A potion and a torch, asserted, durable, counters unchanged. The
hotdog class, now on a body. The player judged this working as designed — fiction the board cannot
hold — and it is also the class M2 and M3 signed through.

**Chrome leftovers, filed, not gate-fail.** Exploration bar stays gone after a fight
(`emberdelve-0v6`). Potion, torch, and take clicks are silent (`emberdelve-kac`); rest already
narrates. Arrival glues onto the previous transcript line (`emberdelve-2ks`). Ending page sits
top-right (`emberdelve-vq3`). No current-room name in the chrome (`emberdelve-eql`). Chapel candles
clip the floor (`emberdelve-z0w`). `POTION (2)` copy (`emberdelve-8kf`).

**Not a miss:** only actioned props are pick targets (`emberdelve-7m6`). The greed chest is not a
second objective. Chapel stayed empty so the rest after the reliquary was a real rest. The vault
brute is a different fight from two goblins, and the player said so.

---

## 5. Recorded, not graded (spec §11)

| Item | This session |
|---|---|
| Arrival speech per delve, first vs return | **8 crossings, all narrated.** First visits named the room. Returns named what was left (Vessk on the flags; gallery pillars as left; chapel still empty). |
| Bar lock after a crossing | **Not remarked as grating.** A different bar bug — exploration chrome stays gone after combat — is `emberdelve-0v6`. |
| Flicker and radius; hostiles in the dark | **Not judged as numbers.** The vault is `DARK`; Brakk read as a figure in rusted plate and killed them. |
| `OUT` in a `DARK` room | **Not reached.** LIGHT went `OUT` in the gallery (not a DARK room) and a torch reset it before the vault. Vault entry was `LIGHT_GUTTERING`. |
| Close heard or skipped; wipe seen before told | **Wipe was combat.** The ending page followed the kill. Extract copy and the way-out confirm were not played. |
| *Roderick came out.* | **Unplayed.** |
| Turn-1 escalation with the rule gone | **Did not recur on the inspect.** Combat on the first failed talk, twice — §4. |
| Failing check after check, nothing escalates | **Not the shape of this run.** Checks mixed. Combat came from talk, from "I attack", and from a HOSTILE brute. |
| Board and DM agree more often | **Mixed.** Reliquary click, four authored reveals, markers on the niche and the cylinder. Invented corpse loot and packed votives the board never took. `grave-list` and `lead-cylinder` rejected. |
| A prop revealed and then crossed away from | **Exercised.** Crypt alcove, gallery niche, chapel niche all revealed, then left. Returns described the rooms as left. |

`roll_check` twice on one action: **absent** (M2 carry-out, M3 gate, 2026-09-07 playtest, this
session). Still not called fixed.

---

## 6. The numbers that are not the gate

41 typed turns, 2547 characters typed, 159 narration segments, 19196 characters spoken. Four
fights, one rest, one potion, one torch. 25 checks (no double-check on a single action). 4
markers. 4 reveals. 6 spawns: crypt Vessk, gallery wanderer, gallery model-spawn, two undercroft
occupants, vault Brakk.

The replay compared **207** engine events, not the 539 lines in the file. Narration, tool calls,
and facts are inputs or colour; the runner folds the outcomes. Identical is the claim.

---

## 7. Verdict

The mechanical half was already true before this session: clocks tick on a crossing, a rest is
refused while anything living and hostile is in the room, the reliquary is the objective, a
HOSTILE occupant starts the fight the model is no longer allowed to start for free, and a lost
delve has a page. The played half was not. Nothing until today took the thing the site is for,
walked past the way out holding it, and died in the last room.

They were not at 5 hit points in front of a door. They were at 16, rested, greedy, and the vault
is what the brute is for. They said the death was fun. The log replays. Losing is possible, and
this one stung from inside the session.

> **M4 gate: PASS** — signed 2026-09-13. A 41-turn Godot session took the reliquary, rested in the
> empty chapel, chose the north door, and fell in the vault with it. Eight crossings. Four fights.
> The torch went out on a natural 1. The log replays 207 outcome events identical. You can lose a
> delve, and this one stung. That was the question.

---

## 8. Carried out of M4

Findings, not tasks. The generator plan is next; these ride with it. Playtest leftovers already
have beads (`emberdelve-0v6`, `kac`, `2ks`, `5yj`, `vq3`, `eql`, `z0w`, `8kf`) and are not children
of the epic.

- **One session did two gate jobs.** Spec §11's two-session shape is still the right shape for
  extract copy and for a wipe that is not also the greed run. Do not take this PASS as those
  endings having been heard.
- **The doubt window sat in the vault, not at 5 HP in a doorway.** Goblins still do not spend
  enough hit points to make the chapel door a 5 HP problem. The brute does. Encounter, not max
  HP, is still what places the window — the map's own finding, confirmed rather than overturned.
- **Failed talk still starts a fight.** Look-around no longer does. The prompt's "provoked" is
  doing what the two-failure rule used to do, on a shorter fuse.
- **LIGHT fill and room fires are different objects, and play will keep mixing them up.** A dead
  party torch in a room whose bowls still burn is correct by the fold and wrong by the sentence
  "pitch darkness".
- **ALERT's band and ALERT's fill cannot both be true as written.** `on you` is dead code while
  a fill zeros the clock. The words are a prompt flavour the wire never ships.
- **Every goblin is Vessk.** Five bodies, one name. A generator that mints more goblins will
  mint more Vessks.
- **Invented loot on a corpse is the hotdog, now portable.** Fiction the counters will not honour.
- **`reveal_prop` can still beat the sentence.** Not the loud finding this time — the player was
  looking at the west wall when the gallery niche landed — and it is still true.
- **Generated rooms still have nothing to find**, and now they also have no occupants. The
  authored site is the specification: chapel empty on purpose, undercroft two `WARY` goblins,
  vault one `HOSTILE` brute, crypt's goblin still a model decision behind a lid. Disposition is
  fire-time. `spawn_entity` stays goblin-only.

**M1 stays open.** The delve is a run you can lose. The world still cannot be made rather than
authored at dungeon scale.
