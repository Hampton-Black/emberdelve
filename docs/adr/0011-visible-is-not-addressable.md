# ADR-0011: Visible is not addressable

**Status:** Proposed — becomes Accepted when the picking confinement lands (`emberdelve-xgg.5`)
**Milestone:** M3 — traversal, extended past the gate

## Context

Once a room the party has left stays drawn, the player can see things they cannot reach. Standing
in the gallery, the crypt's sarcophagus is on screen. The obvious next requests follow immediately:
click that door from two rooms away, walk there, act on a prop you can see.

The DM's world is scoped to one room and has been since M3. `WorldState.entitiesHere()` and
`factsHere()` filter by the current room; `ToolSchema` offers `reveal_prop` over the current room's
hidden props and `use_exit` over the current room's exits. Spec §8b's reason a neighbour is dark is
that a lit room the narrator was never told about is a room the narrator can contradict.

Drawing more rooms threatens to make that scoping look arbitrary rather than deliberate.

## Decision

**Drawing a room grants no affordance over it.** The DM's prompt and every tool enum stay scoped to
the room the party is standing in. A non-current room ships geometry and props only — never
entities. A pick that lands outside the current room's rectangle resolves to nothing.

## Consequences

- **Travel to a room you can see is ruled out** until there is multi-room routing and an answer for
  arrival narration in the rooms passed through. Those are the real costs, and neither is a
  rendering problem.
- **Entities are not drawn in non-current rooms.** A creature the player can see that the prompt
  was never told about is §8b's contradiction risk with a monster in it, and monsters move on their
  own. Spec §4b keeps entities in the fold exactly where they were left; this only declines to draw
  them. The accepted cost: a goblin left alive in a room you are looking at is not on screen.
- **`Diff` stays current-room-scoped by convention.** `Diff.PropRevealed` carries no `roomId` and
  needs none, because `reveal_prop` only ever offers the current room's hidden props. Worth stating
  so it is a decision rather than something rediscovered.
- **Rejected: widen the tool enums to visible rooms.** This is travel-to-a-visible-room wearing a
  smaller hat — it needs the routing anyway, and lands the model in a room the engine did not move
  the party to.
- **Rejected: tell the DM which rooms are visible.** Costs prompt budget, and every prompt change
  risks the tool phase, whose tail is live and already documented
  ([ADR-0008](0008-model-picks-come-from-played-sessions.md)).
- **Reopen when** fleeing or party splits arrive. Both put a party in more than one room's worth of
  space and neither can hold this line.

## Related

`AGENTS.md` invariant #1 and § *LLM tools*.
[ADR-0009](0009-the-world-frame-is-anchored-at-the-entrance.md) is what makes more rooms visible in
the first place. `../superpowers/specs/2026-09-05-m3-traversal-design.md` §7a and §8b.
