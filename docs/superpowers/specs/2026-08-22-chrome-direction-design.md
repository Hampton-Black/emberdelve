# Chrome direction — the frame, the two modes, and the die

**Status:** Design approved 2026-08-22, pre-implementation. **Blocked on the Phase B parity gate.**
Look (2026-08-23): the world behind this frame is stylized isometric 3D at native resolution, not a 480px pixel crypt. Palette and “no parchment” still hold for this chrome pass; the old reason (photoreal UI vs pixel world) is gone, so a later chrome pass may reopen materials. Do not reopen it in this document.
**Companion to:** `docs/superpowers/specs/2026-08-21-godot-client-design.md` (the client this
dresses), `AGENTS.md` (every presentation number), `docs/m0-evaluation.md` (the feel that must
survive).
**Amends:** that spec's §7 *Chrome*. Where the two disagree, **this document wins for chrome and
that one wins for everything else.** The single substantive reversal is recorded in §2.

---

## 1. What this is for

M0 proved the DM runs a game. Phase B is building the crypt underneath it. Neither answers a
different question: *why does this read as a well-behaved client rather than a roleplaying game?*

The answer is not more information. The current overlay already shows initiative, hit points, mode,
and every roll. It reads as software because nothing in it is **an object**, nothing is **a place**,
and nothing is **a ritual**. This document adds one of each and nothing else:

- **an object** — a party rail and turn cards that are plates rather than labels;
- **a place** — a frame the crypt sits inside, and a name on it;
- **a ritual** — a die that names its difficulty before it moves, shows the sum being built, and for
  the rolls that deserve it, waits to be thrown.

Everything here is presentation over facts `Table` already holds, except the five wire fields in §10
and one change to `camera_rig.gd` in §9 that is not chrome at all and matters most.

---

## 2. Locked decisions

1. **The frame is architecture, not overlay.** A stone bezel, a party rail down the left, a chin
   along the bottom. This reverses `2026-08-21-godot-client-design.md` §7's *"Full-window crypt. UI
   is overlay, not a web sidebar."*

   That line was written to stop the browser client's sidebar being ported wholesale, and it was
   right about the sidebar. It was wrong that the only alternative is a bare window. A CRPG frame
   is neither: it is a fixed architecture the world is seen *through*, it never scrolls, it never
   reflows, and it holds the same controls in the same place all session. The cost is real and is
   priced in §4.

2. **The frame never changes. Its contents do.** Bezel, rail, chin, log and input exist in every
   mode. Cards, the action column and the economy pips exist only in `COMBAT`.

3. **The mode flip reuses the combat ceremony exactly.** Every constant in §5 already exists and was
   already tuned against the combat sting. None of them move.

4. **The rail and the cards answer different questions** and never show the same number. §6.

5. **The die has two presentations, keyed on `RollRequest.purpose`.** Attacks stay small and keep
   their measured timing; checks and saves take the screen. §8.

6. **A clicked die shortens a capped wait. It never releases one.** `Clock` keeps a timer as the
   backstop in every path. §8.4.

7. **Nothing here starts before the parity gate passes,** with the single exception in §9.

---

## 3. Out of scope

- **New rules.** No Dodge, no Disengage, no reactions. A button for a rule the server does not have
  is worse than no button. Invariant #10 still holds.
- **New entity types, props or tools.** Same reason.
- **3D physics dice.** Unchanged from `2026-08-21-godot-client-design.md` §7, including its re-entry
  condition. The full-screen check overlay in §8 is *not* a re-entry — it is a 2D presentation of
  the same `tumble.gd` samples at a larger size.
- **Party management.** The rail holds N members and shows empty frames. Nothing recruits, swaps or
  reorders them.
- **Localisation, accessibility audit, controller input.** Not refused, just not this.

---

## 4. The frame

One `CanvasLayer`, four stone edges, one keyline. Proportions are of the window, not pixels, so the
frame holds its shape at any size:

| Edge | Width | Holds |
|---|---|---|
| Left | 12.5% | The party rail |
| Right | 2.4% | Nothing. It exists so the crypt has two sides |
| Top | 2.2% | The room's name, and the round in combat |
| Bottom | 21% | The chin — log, input, action column |

The crypt keeps roughly **86% of the width and 77% of the height** — about two thirds of the window.
That is the Vault's original objection, accepted knowingly. §9 is the condition that makes it
affordable.

**The keyline is what reads as carved,** not the mass. A one-pixel brass line on the frame's inner
edge with a dark line inside it does more than the stone texture behind it. A heavier bezel was
mocked and rejected: it cost a third of the window for corners that read at 2.6%.

**No parchment, no tan, no felt, no wood.** This chrome pass keeps stone, bone, and brass.
The original reason was a genre collision with a 480px pixel crypt (two resolutions, two eras).
The world is now native stylized 3D; that collision is gone. Materials stay as written here until
a later chrome pass reopens them on purpose.

The frame is built from the palette already in the code: `#0d0c12` ground and `#3a3444` edge from
`combat_bar.gd`, `#cfc4ae` bone and `#c9b083` brass from `transcript.gd`, `#7fae56` yours and
`#b83a30` theirs from `combat_bar.gd`, `#f0d67a` for a crit from `tumble.gd`. One value is new — a
dimmer brass, around `#6a5c48`, for plate borders that must not compete with the active one. The
ember accent used on the active card is `#f0d67a`, not a new hue. **Highlights on the board use
brass, never VTT cyan.**

---

## 5. The two modes

`Table.mode` already flips between `EXPLORATION` and `COMBAT` and already emits `mode_changed`.
`Table.combat_opened(order_size)` already fires with the size of the order. Both are subscribed.

### 5.1 Exploration

- **No cards.** No initiative exists, so there is nothing to order. The top bezel carries the room's
  name alone.
- **No action column.** No combat verbs exist. The log takes the full width of the chin.
- **The rail is unchanged** and is the only place the party appears.

### 5.2 Combat

- **Cards** arrive under the top bezel, centred over the visible rect.
- **The action column** slides into the chin's right side; the log narrows to make room.
- **Economy pips** appear on the active member's rail plate, from `movementRemaining` and
  `actionAvailable` — the fields `combat_bar.gd` already reads.

### 5.3 The flip is the ceremony that already exists

`Sfx.initiative_set(count)` plays one tick per combatant at `CHIP_DELAY_MS + i × CHIP_STAGGER_MS`.
If the cards arrive on those same offsets, **each card lands on its own sound.** That is the whole
effect and it is already paid for.

| Constant | Lives in | Value | Does here |
|---|---|---|---|
| `BAR_IN_DELAY_MS` / `BAR_IN_MS` | `combat_bar.gd` | 120 / 200 | The frame acknowledging the flip before anything lands in it |
| `CHIP_DELAY_MS` | `sfx.gd` | 400 | When the first card lands, and when its tick plays |
| `CHIP_STAGGER_MS` | `sfx.gd` | 180 | The gap between cards |
| `CHIP_IN_MS` | `combat_bar.gd` | 260 | How long one card takes to arrive |
| `CHROME_DELAY_MS` | `combat_bar.gd` | 760 | Action column and economy pips — after the order is readable, never before |
| `CLOSE_MS` | `combat_bar.gd` | 400 | Cards and column dissolving. **The rail does not move** |
| `arrival()` | `combat_bar.gd` | — | Reused unchanged, including its backdating |

**Backdating is not optional.** `arrival()` measures from `opened_at`, so a mid-fight reconnect
finds the row already assembled instead of replaying the drums over a fight in progress. Any new
element that animates on the flip goes through `arrival()` for that reason alone.

---

## 6. The rail and the cards

They look similar and must not behave similarly. **If they ever show the same number, one of them
is wrong.**

| | Party rail | Turn cards |
|---|---|---|
| Question | Who you are | Whose turn it is |
| Lifetime | Persistent | Only while a fight is open |
| Who is in it | The party, plus empty frames | Every combatant, including the dead |
| Modes | Both | `COMBAT` only |
| Carries | Portrait, AC, name, HP, conditions, economy pips | Initiative, name, allegiance bar, turn state |
| Never carries | Initiative | AC, conditions |

**The rail holds N.** Invariant #2 is not a style note here — the empty dashed frames are the
feature. M0 has one party member and the rail must look like it is missing two, not like it was
built for one.

**A dead combatant keeps its card,** greyed with its name struck through. The old bar could not do
this because it redrew from `combat_beat`; a plate is an object and objects persist. The rail's
equivalent is a member at 0 HP, which stays in place for the same reason.

**The active card lights and drops an ember point.** The active rail plate takes a brass rim. Both
key off the same `activeId`; neither computes it.

---

## 7. The chin

Always: the log, and the input docked under it. `RichTextLabel` and `LineEdit`, as before — the
change is where they live, not what they are.

In combat only: the action column on the right, holding `ATTACK`, `MOVE · N ft`, `END TURN`, and
beneath them the compact roll readout from §8.2.

Three rules survive from `2026-08-21-godot-client-design.md` §7 unchanged, and all three are easy to
break once the chin has states:

1. **One stream.** Prose and rolls in arrival order, the same list as `TranscriptEntry`. No tabs.
2. **Never fade a line that is still being said.** The chin may dim when nothing is queued and
   `awaiting_dm` is false. It may not dim mid-utterance.
3. **Errors bypass everything.** `Table.set_error` deliberately skips the queue because a stuck turn
   must never hide behind a die or a sentence. Whatever the chin is doing, the error path sits on
   top of it.

### 7.1 The third state

`awaiting_dm` is a mode in practice and currently expresses itself only as a greyed `LineEdit`. With
a frame this substantial it should be said properly: **the input locks, the action column dims, and
the chin's border takes the ember.** One more subscriber to a signal that already fires.

Defeat and the title stay full-screen and sit *over* the frame rather than inside it. Restart still
routes back through the title, because the server's once-per-session opening guard is only asked by
`begin`.

---

## 8. The die

### 8.1 Two events, discriminated by a field that already exists

`RollRequest.purpose` is `ATTACK / SAVE / SKILL_CHECK / DAMAGE / INITIATIVE`, and
`Table.add_roll` already branches on it to decide whether the impact beat applies. **The split
below needs no new wire field.**

| Purpose | Presentation |
|---|---|
| `ATTACK` | Compact readout, chin right column. §8.2 |
| `SKILL_CHECK`, `SAVE` | Full-screen overlay. §8.3 |
| `DAMAGE`, `INITIATIVE` | Neither. `Tumble.is_dramatic` already excludes them |

**Attacks do not take the screen.** This is not a matter of taste. An attack happens several times a
round, and `IMPACT_BEAT_MS` (650) is the measured gap between the total becoming legible and the
blow — the entire slack an attack has. A check's consequence is narration, which `tumble.gd` records
as *"takes seconds to arrive on its own"*. That slack is where the overlay and the click fit, and
attacks do not have it. **BG3 draws the same line for the same reason.**

### 8.2 The compact readout

Face, then the modifier, then the total, then the outcome, on one line beside a hexagonal die:
`14 +3 = 17 · HIT`. Never a bare total.

Showing the sum being built is the single highest ratio of feel to effort in this document. It is
the same data the tray already has, and it is what a person at a table actually experiences: they
see fourteen and hear three added.

Timing is untouched. `land_at`, `reveal_at`, `IMPACT_BEAT_MS`, `COMBAT_HOLD_MS` and the dismissal on
the swing all behave exactly as they do today.

### 8.3 The check overlay

Two states over a dimmed crypt:

**Before** — who is rolling, what for, and `DIFFICULTY CLASS n`, above a die that has not moved.
The DC is named *before* the die, always. That is the ritual; a DC revealed afterwards is a score,
not a stake.

**After** — the settled face at size, the modifier spelled out beneath it, the total, then the
outcome.

The overlay is a presentation of the same `Tumble.sample()` output at a larger scale. `tumble.gd`
stays pure and stays the only thing that decides where a die is.

### 8.4 The click, and the one thing it threatens

The click does not threaten invariant #1. The value was always decided by the server; the click is a
release, not a randomiser, and nothing client-side produces a face.

It threatens `Clock`. `clock.gd`'s `hold()` is a timer *"because if the tray never mounts the queue
must still move on."* A click-gated roll puts a human in that position, and a player who walks away
stalls the DM permanently.

**The resolution: the click shortens a capped wait rather than releasing it.**

- The overlay appears and the queue holds for `PROMPT_CAP_MS` **plus** the existing airtime.
- A click ends the prompt segment early. It cannot extend it.
- No click, and the cap fires the throw anyway.
- Everything after the throw runs on today's numbers, unchanged.

This needs one sibling to `hold()` that awaits whichever of a timer or a signal arrives first. The
timer remains the backstop in every path, so the comment's promise is intact.

`PROMPT_CAP_MS` starts at **3000** and is a guess. It is the one number here that was not measured,
and a played session replaces it.

**Ship the overlay auto-throwing before shipping the click.** That isolates the presentation from
the queue change and lets the drama be judged on its own. If the overlay does not land without the
click, the click will not save it.

---

## 9. The camera frames a rect, not a viewport

**This is the only item that is not chrome, and it is the most important thing in this document.**

With chrome permanently owning about a third of the window, a camera that frames to the *viewport*
centres the room on the window. The room's near edge then sits under the chin, the party stands
lower than the player is looking, and it reads as a camera that is slightly wrong and never stops
being slightly wrong.

`camera_rig.gd` already snaps to four corners and frames a room. It must take a **rect** — supplied
by the frame, computed once from its own proportions — instead of the whole viewport.

**Do this before the art judgements, not after.** Every framing decision in Phase B is made against
this rect: how big a room reads, where a token sits, whether the braziers are in shot. Deciding it
later means re-judging all of them.

It also opens a door worth leaving open: nothing requires the rect to be the same in both modes,
once cards occupy the top.

---

## 10. What the server has to grow

Each is a Java record change, a mirror in `client/src/types.ts` while it lives, and a GDScript
reader — **in one commit**, per the migration plan's rule. None of them may land before the parity
gate; invariant #10 forbids it during a client swap.

| Field | Why | Without it |
|---|---|---|
| `Entity.conditions` | Status chips on the token, the rail and nothing else | **The only one whose absence costs the player information.** Prone, bleeding and blinded stay invisible. Do this one first |
| `Entity.ac` | The rail's AC tab; "vs AC 13" in both die presentations | Readouts say "vs 13" with no label |
| `Entity.portrait` | A key, not an image. The plate picks art from a local table, as `prop_table.tres` already does for props | Silhouettes. Survivable — **ship it this way first** |
| `Scene.roomName` | Names the place on the top bezel | The bezel's top edge stays empty |
| `RollResult.modifierBreakdown` | "+3 wisdom & proficiency" instead of "+3" | A bare "+3", which is still most of the win |

---

## 11. What this replaces

| Today | Becomes |
|---|---|
| `combat_bar.gd` — a 42px strip pinned to the top | Turn cards under the top bezel, plus economy pips on the rail. Its constants and `arrival()` survive; its layout does not |
| The floating log panel at 42% width | The chin's log, at the chin's width |
| `dice_tray.gd` bottom-right | The compact readout in the chin (attacks) and the overlay (checks). `tumble.gd` is untouched |
| No mode indicator at all — the Godot bar never ported the browser's mode pill | The frame itself. Mode is legible from whether cards exist |
| No party representation at all | The rail |

`title.gd`, `defeat.gd`, `toast.gd` and `debug_bar.gd` are unchanged in behaviour and re-placed only
as far as the frame requires.

---

## 12. Order of work

1. **Phase B finishes untouched.** The gate compares against the browser client; a redesigned
   overlay makes that comparison meaningless.
2. **The camera rect (§9).** The exception to "after the gate" — it is small, it is in a file being
   actively worked, and everything downstream is judged against it. Land it while that context is
   warm.
3. **The frame, the chin, and the arithmetic.** Bezel, rail with silhouettes, chin with log and
   input, and `14 +3 = 17 vs 13` everywhere a roll is shown. Name, HP and initiative are already on
   the wire; AC and conditions simply do not render yet. No new fields, no timing changes.
4. **The mode flip.** Cards and action column on the §5.3 offsets. This is where the row-overflow
   question in §13 gets answered, because five cards make it obvious.
5. **The check overlay, auto-throwing.** Presentation only. No `Clock` change.
6. **The click and its cap.** The early-release hold, then a played session to set `PROMPT_CAP_MS`.
7. **The wire fields (§10),** conditions first, once the client swap is closed out.

---

## 13. Open questions

These are genuinely undecided. None of them blocks step 3.

1. **The card row past five combatants.** Three fit comfortably, eight will not. Shrink, scroll, or
   drop portraits above a threshold — decided at step 4, against real cards, not in advance. A chip
   row never hit this wall because chips are much narrower.
2. **Saves during combat.** A `SAVE` mid-fight gets the same full-screen treatment as an exploration
   check, which may read as an interruption rather than a stake. Either drop the click for saves and
   auto-throw with the same visuals, or shorten the cap while `mode == "COMBAT"`. Needs a played
   session.
3. **`PROMPT_CAP_MS`.** 3000 is a guess. §8.4.
4. **Whether the visible rect differs by mode.** §9.

---

## 14. Invariants (unchanged)

Nothing in this document alters any of them, and three are load-bearing here specifically:

- **#1 — the server is authoritative.** The click releases a value the server already chose. No face,
  hit, legal move or death is computed here.
- **#2 — no singleton player.** The rail holds N and shows the empty frames. Every plate is addressed
  by `actorId` from the wire.
- **#3 — game state lives in `Table`.** The frame, the rail, the cards and both die presentations all
  subscribe. None of them own a fact.
- **#4 — the 3D world is one scene, created once.** The chin and the rail are `Control`s over it, and
  a mode flip does not rebuild it.
- **#5 — `RollResult.faces` stays a list.** §8.2 exists precisely because the total is not the point.
- **#10 — no new tools, entity types, props or rules** until the client swap is closed. §10 waits.
