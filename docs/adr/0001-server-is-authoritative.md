# ADR-0001: The server is authoritative; the client renders what it is told

**Status:** Accepted 2026-08-20
**Milestone:** M0

## Context

An AI DM has two plausible splits. The client can hold the rules and ask the model for colour,
or the server can hold everything and hand the client a picture. The first is tempting because
it makes the client feel responsive — a click resolves locally, with no round trip.

It also means the rules exist twice, in two languages, and drift. The specific failure is not
abstract: if the client computes which squares are legal, its idea of the legal set can differ
from the server's, and the player sees a move accepted that the server then rejects.

## Decision

The server decides. The client never computes a roll, a hit, a legal move, or a death.
`CombatView` ships `legalMoves` and `legalTargets` **already decided**; `intent()` on the client
asks only whether the clicked square is in a list that arrived over the wire. That is the entire
client-side movement rule.

`CombatChanged` replaces the whole combat picture rather than describing a delta, because a
fine-grained "movement decremented" diff is exactly how the two pictures drift apart.

## Consequences

- Nothing on the client knows about speed, reach, or blocking props. Difficult terrain can be
  added server-side without touching the client.
- Every mechanical interaction costs a round trip. The dice animation is what makes that
  tolerable — see ADR-0005.
- Terrain lives on the room (`RoomDefinition.isObstructed`), not on combat: walking into the
  sarcophagus is impossible whether or not anyone has rolled initiative.
- Distance is Chebyshev on both sides. Two metrics in one combat system is where
  "why can it hit me from there" bugs start.

## Related

`AGENTS.md` invariants #1, #2, #3. ADR-0005 on why the latency is survivable.
