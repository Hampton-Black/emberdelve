# ADR-0012: A consequence may only do what a tool can already do

**Status:** Proposed — settled on the M4 wayfinder map (`emberdelve-ffi.2`), unbuilt
**Milestone:** M4 — the delve

## Context

M4 introduces the clock: a counter that ticks on a defined unit and fires a table when it fills.
Design doc §9 is emphatic that the table is the point rather than an implementation detail — "the
same constrain-the-model pattern as the DC bands in §7 and the prop enum in §5. The DM narrates
what the table produced; it never decides that the torch went out."

What §9 does not say is what a table entry is allowed to *do*. The natural reading of "fires a
table" is a scripted effect, and that reading has an obvious destination: a small language for
expressing what fires — spawn this, move that, tick the other, conditionally. Which is an effect
interpreter, arrived at sideways.

Two standing decisions sit directly across that path. §12's anti-goal is *do not build a rules
engine*, whose own example is the forty lines of `CombatEngine.resolveAttack`. And §15 already
scopes the effect interpreter and the stance layer to the content milestone, **together**, on the
grounds that each is a tax alone and neither pays for itself without the content it adjudicates.

So the question is not whether an effect interpreter is wanted eventually. It is whether the clock
is allowed to be the thing that smuggles one in a milestone early, in service of two tables.

## Decision

**A consequence may only do what a tool can already do.**

A `ConsequenceId` names a pre-validated bundle of events the engine already has an `Event` +
`Diff` pair for — `EntitySpawned`, `EntityMoved`, `PropRevealed`, `CombatStarted` — plus state the
engine already models as a scalar, such as a room's `LightingPreset`. No new verbs. No composition,
no conditionals, no arguments beyond what the bundled events already take.

**A sign is a consequence whose bundle is empty.** A threshold fires a sign, the fill fires the
consequence, and the only difference between the two is whether anything changes. One enum, one
table shape, one firing path.

## Consequences

- **Invariant 8 holds without special handling.** Every verb in the budget already emits its own
  event, so a fired consequence is in the log because its parts were always going to be. Nothing
  about the clock needed a second write path, which is what makes this cheap rather than merely
  small.
- **Adding a capability to the tables means adding the tool or event first.** That is the gate, and
  it is deliberate: a consequence that can do something no tool can do is a mechanism the DM cannot
  reach, the player cannot provoke, and no test exercises. The tables get whatever the rest of the
  engine already earned.
- **`ConsequenceFired` is emitted alongside the bundled events, never instead of them.** The log
  gains one inert record naming the cause, exactly as `ToolCallIssued` does. This is a consequence
  of the budget rather than a separate choice — see the rejection below.
- **Cross-clock effects are declared on the tick side.** A filled LIGHT clock ticks ALERT, and that
  is written in ALERT's trigger list, not inside `LIGHT_OUT`. A clock that ticks a clock from
  inside a consequence is the first step toward a state machine nobody can trace, and it is the
  narrow case that most tempts the budget open.
- **Rejected: an effect language.** §15's scoping is the whole argument, and the seductive part is
  that the first version looks tiny — a list of two or three effect kinds. It grows with every
  table entry anyone wants, and the growth is invisible until it is a dialect.
- **Rejected: a separate `SignId`.** It would make one class of authoring mistake unrepresentable —
  a sign table cannot hold `LIGHT_OUT` if the types differ. The price is a duplicated table shape
  and a duplicated directive path for a thing that is already the same thing. Kept as a validation
  rule instead: a sign table's entries must have empty bundles.
- **Rejected: `ConsequenceFired` alone, expanded by the fold.** The fold would have to know the
  tables, which makes a content file part of replay correctness — edit a table after a session is
  recorded and it replays differently, silently. This is the same argument `Event.PartyMoved`
  already makes for carrying its landing square rather than recomputing it from `Exit.inward()`.
- **Rejected: the bundled events alone, with nothing naming the cause.** It loses the causal record
  in a project whose entire diagnostic workflow is that the file proving it is already on disk. A
  session where a fight appeared from nowhere and the log cannot name the clock is a session that
  cannot be diagnosed.
- **Reopen when** the content milestone builds the effect interpreter. At that point the budget
  becomes a restriction rather than a protection, and this ADR is superseded rather than amended.

## Related

`AGENTS.md` § *Anti-goals* and § *LLM tools*.
[ADR-0002](0002-state-is-a-fold-over-an-event-log.md) is what makes the bundle-of-events framing
work at all. `../ai-dm-system-design.md` §9, §12 and §15.
The four tables and the reasoning behind each entry are the resolution comment on
`emberdelve-ffi.2`; they will be carried into the M4 design spec (`emberdelve-ffi.7`).
