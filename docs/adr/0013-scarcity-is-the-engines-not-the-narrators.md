# ADR-0013: Scarcity is the engine's, not the narrator's

**Status:** Proposed
**Milestone:** M4 — the delve

## Context

§9 states the principle plainly: *"The model always says yes. That is not a prompt failure to be
tuned out; it is what a narrator trained to be agreeable does, and it means the engine is the only
thing in this system that can make something scarce."* Nothing in this project disputes that. The
question this ADR settles is narrower and easier to get wrong: **what counts as the model making
something scarce.**

The M4 delve clock was chartered to tick on "rooms entered, rests taken, and noisy failures", and
`emberdelve-ffi`'s charting session sharpened the last of those into a rule — a failed check ticks
the LIGHT clock one segment, a natural 1 ticks two. It looked like an engine rule. Every part of it
is engine-owned: the engine sees the outcome, the engine ticks, the engine fires the table, the DM
only narrates what the table produced.

It is not an engine rule, and the recorded sessions say so out loud:

| Session | Checks | Failures | Natural 1s |
|---|---|---|---|
| `session-m2-twenty-turns.jsonl` | 20 | **14** | 3 |
| `session-m3-traversal.jsonl` | 11 | **2** | 0 |

Same game, same player, one milestone apart. M2's DM reached for DC 15–25; M3's reached for DC
5–10. A LIGHT clock ticking on failures would have filled **seven times faster** in one session than
the other, for no reason the player did anything about.

The mechanism is `roll_check`. Its `difficulty` is a closed five-value enum, validated server-side,
exactly as invariant #7 requires — and the model still picks which of the five. §7's DC bands are
load-bearing and deliberately the model's to choose. But *whoever chooses the DC chooses the failure
rate*, and if failures spend a resource, whoever chooses the DC chooses the burn rate of the delve.
The enum being closed constrains what the model may say; it does not constrain what the model's
choice costs the player.

That is the failure mode worth naming, because it is invisible in a code review: **an engine that
spends a resource in response to a number the model chose has outsourced scarcity while appearing
not to.** Every line of the transaction is server-side. The authority still leaked.

`emberdelve-ffi.6` measured what the clock was actually worth and found the pull is real in the
other direction too — the tick rule was reached for because a played session showed the DM narrating
a dropped lantern on a natural 1, which was the right beat arrived at the wrong way.

## Decision

**No engine resource is spent as a consequence of a value the model selected.**

A clock ticks on what the player did — a room entered, a rest taken — or on the raw die, which
nobody chose. It does not tick on an outcome whose likelihood the model set.

Concretely, for M4's LIGHT clock: **a natural 1 ticks one segment; an ordinary failed check ticks
nothing.** Room entry carries the pressure, and room entry is the player's decision in its entirety.

## Consequences

- **The play observation that motivated the tick survives whole.** A natural 1 is still an engine
  tick, a fired table and a narrated event — the dropped lantern reached the right way round. It
  lands once or twice a delve, which is a bite rather than a tax.
- **The natural die is the one die the model cannot influence.** It is already privileged by
  invariant #5, which keeps `RollResult.faces` a list precisely so crits key off the natural d20
  rather than a total. This decision leans on the same property for the same reason.
- **The LIGHT clock becomes plannable, which is what it was for.**
  [ADR-0012](0012-a-consequence-may-only-do-what-a-tool-can-do.md) made LIGHT a fixed one-entry
  table so the greed decision is computable. A fill rate set by the narrator's taste in difficulty
  would have taken back with one hand what that gave with the other.
- **The rule generalises past clocks, and it should be applied when `use_item` lands.**
  `emberdelve-4h9.2` already notes that a false-positive `use_item` "spends a potion the player still
  has, which is expensive" — that is this same decision, met from a different direction, and it is
  why `use_item` is a mechanics-phase tool rather than a reconcile-phase one.
- **Rejected: cap the ticks per room.** A cap concedes the point and then bounds the damage. Below
  the cap the model still sets the rate, and the player is now also asked to model the cap.
- **Rejected: take DC selection off the model.** That would fix this by reversing §7, which is a
  much larger decision made in service of a much smaller problem — and an engine choosing DCs is a
  rules engine arriving a milestone early, against §12.
- **Rejected: keep the failed-check tick and tune around it.** There is no tuning that survives a
  sevenfold swing in fill rate, because the swing is not noise. It is a different DM making a
  different legitimate choice.
- **The cost, stated plainly:** noisy failure no longer costs light, so a player who fails loudly and
  often in one room pays nothing for it. Charting rejected natural-1-alone as "too rare to build
  pressure", and that worry is answered by the room tick rather than dismissed. If a played session
  shows the clock too quiet, the fix is the room tick or the segment count — both player-facing —
  never a return to spending on the model's arithmetic.
- **Reopen when** the engine owns DC selection, which would make a failed check a fact about the
  player rather than a fact about the narrator's mood.

## Related

`docs/ai-dm-system-design.md` §9 (attrition, and the delve clock) and §7 (the DC bands).
`AGENTS.md` invariants #5 and #7, and § *LLM tools*.
[ADR-0012](0012-a-consequence-may-only-do-what-a-tool-can-do.md) is the other half of keeping the
clock honest: what a table may *do*, where this is what may *fill* it.
Measured in `docs/evidence/m4-attrition-sim.py` and the two session logs beside it; decided on
`emberdelve-ffi.6`.
