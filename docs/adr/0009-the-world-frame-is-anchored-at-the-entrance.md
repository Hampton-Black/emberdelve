# ADR-0009: The world frame is server-owned and anchored at the entrance

**Status:** Accepted 2026-09-06
**Milestone:** M3 — traversal, extended past the gate

## Context

M3 shipped a room and its dark neighbours. `RoomOutline.beside` computed a neighbour's offset
*relative to the room the party was standing in*, and the client re-centred that room on the world
origin every crossing. Both were right for one lit room: a neighbour is one hop away by
construction, so one offset is all there is to compose.

Keeping visited rooms on screen breaks that. A room two hops away has no offset relative to
anything the current room knows, and re-centring on every crossing is what made the dungeon read
as a series of boxes rather than one place.

The composition has to happen somewhere. `RoomOutline`'s own doc comment already says where and
why: the arithmetic is server-side *"so there is one implementation of the arithmetic and it can
be tested without a renderer."*

## Decision

`Rooms.origins()` walks the exit graph breadth-first from `Rooms.first()`, composing
`RoomOutline.beside` offsets into an absolute `dm.model.RoomOrigin` per room. **The entrance is at
`(0, 0)` and never moves.**

`beside` itself is unchanged and remains the single implementation of door alignment. A cycle's
second path to an already-placed room is ignored, not reconciled. A room the entrance cannot reach
— an unreachable room, or one behind a one-way exit that `beside` declines to place — is absent
from the map rather than guessed at.

## Consequences

- **The current room is no longer at the world origin.** That is a cost, paid deliberately, and it
  forces three client fixes that were previously hidden by the coincidence: `world_to_grid`
  becomes room-aware, and `camera_rig`'s `_framing_target()` and `_clamp_to_room()` translate to
  the current room's origin instead of `Vector3.ZERO`. Left alone, the clamp confines the camera
  to a rectangle around the entrance while it tries to follow a party elsewhere — precisely the
  failure `camera_rig.gd` already warns about, *"clamp-to-room quietly cancels the follow"*.
- **BFS discovery order is not hop distance.** Two rooms found at the same depth land in whichever
  order their parents' exit lists were processed. Anything needing "nearer the entrance" compares
  `Placed.hops` — ADR-0010 is the first such thing and will not be the last.
- **Cycles are not reconciled.** First path wins, deterministically. Real world-space packing with
  overlap avoidance stays with the generator milestone that creates the overlaps
  (`2026-09-05-m3-traversal-design.md` §3, "world-space packing").
- **Rejected: the client accumulates.** `C_abs = B_abs + offset` needs no server change at all and
  is correct for a tree. It was rejected because it moves composition somewhere it can only be
  tested with a renderer, and turns a cycle's disagreement into an emergent client behaviour
  rather than a defined server one.
- **Rejected: full packing now.** Explicitly deferred by the M3 spec, and the two authored rooms
  cannot motivate it.
- **Reopen when** the generator produces cycles whose two paths disagree by enough to misalign a
  door. First-path-wins is a placement, not a guarantee of consistency.

## Related

`AGENTS.md` invariants #1 and #4. [ADR-0001](0001-server-is-authoritative.md) — this is that
invariant applied to world space rather than to rules. [ADR-0010](0010-the-room-nearer-the-entrance-owns-the-shared-wall.md)
consumes the frame. `../superpowers/specs/2026-09-05-m3-traversal-design.md` §3 and §8a.
Proven by `RoomsOriginsTest`: the single room, one hop matching `RoomOutlineTest`, a three-room
chain composing, a cycle placing every room once, and a one-way exit leaving its destination
unplaced.
