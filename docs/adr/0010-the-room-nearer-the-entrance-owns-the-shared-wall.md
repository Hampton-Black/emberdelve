# ADR-0010: The room nearer the entrance owns the shared wall

**Status:** Accepted 2026-09-06
**Milestone:** M3 — traversal, extended past the gate

## Context

`RoomOutline.beside` lines two rooms' doors up, which puts both perimeters on one plane. Drawing
both is two walls fighting for one depth: it showed first as a door with two ring handles, then as
texture noise across the seam.

The rule until now was **the room the party is standing in owns the shared wall**, and the loser
omitted the whole wall run its answering door was in — direction alone, "the whole run, not the one
square", because matching segment against segment can half-work when two rooms' widths differ in
parity.

Both halves fail the moment more than one room is visible at once.

**Whole-run omission loses the overhang.** The crypt is 12 wide and the gallery 10. The crypt's
north segments sit at x = −5.5 … +5.5; the gallery's south wall covers −4.5 … +4.5. Omitting the
crypt's entire north run leaves **two 1-unit holes at its north corners**. Invisible while a
non-current room is painted near-black, and a hole the moment it is not — against a playtest
finding from 2026-09-06 that is unambiguous: *a hole in the wall is a way out, and there are no
other holes*.

**A party-relative owner has no answer for a wall the party is nowhere near.** A middle room in a
chain has shared walls on both sides and `RoomOutline.back` names only one. Two adjacent rooms the
party is standing in neither of would each leave the wall to the other, and it vanishes entirely.

## Decision

`Rooms.coveredWalls()` returns, per room, the `dm.model.WallSegment`s it must **not** draw because
a nearer room already does.

The plane is drawn once, by whichever of the two rooms is **fewer doors from the entrance** —
`Placed.hops` from ADR-0009 — with ties broken by `roomId` so the answer is identical on every run.
Omission is a **1D interval overlap on the shared plane**, not a direction match: a segment is
dropped only when the owner's run contains it *whole*.

## Consequences

- **The parity worry was about centres, not boundaries — measured, not assumed.** Two rooms whose
  widths differ in parity do sit half a square out of step by segment centre. But `beside` offsets
  by `exit.x - back.x + (there.width - here.width) / 2`, and that same half-square in the
  half-width term puts both rooms' segment *edges* back on one integer lattice. A partial overlap
  therefore means something upstream is wrong, and the containment test is deliberately biased
  toward drawing: **doubled stone reads as noise where a half-square gap reads as a way through.**
  `aParityMismatchStillLandsOnWholeSegments` is the case that holds this.
- **Ownership is stable across runs and independent of where the party is.** A room's walls are
  the same whether you are standing in it, next to it, or three doors away.
- **Cost is O(n²) over placed rooms**, comparing four runs each. Irrelevant at two rooms; worth
  revisiting when the generator produces a dungeon rather than a pair.
- **Rejected: keep whole-run omission.** Cheapest, and leaves the two corner holes — regressing
  the exact fix the last playtest produced.
- **Rejected: deterministic owner, whole-run omission.** Fixes the vanishing wall between two
  non-current rooms and not the holes. Half the work, half the fix.
- **Reopen when** rooms stop being axis-aligned rectangles. Every line of this assumes a perimeter
  is four runs, each a constant on one axis and an interval on the other.

## Related

`AGENTS.md` — the two shared-wall bullets under *Fixed after the M3 gate* state this operationally
and were amended in the same commit that made the old rule false.
[ADR-0009](0009-the-world-frame-is-anchored-at-the-entrance.md) supplies both the origins and the
hop distance. Proven by `RoomsCoveredWallsTest`: a lone room covering nothing, the wider entrance
keeping its overhang, the overhang not being a whole-run omission, a parity mismatch still landing
on whole segments, a middle room right on both sides, and equal distance breaking by `roomId`.
