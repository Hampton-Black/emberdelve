# ADR-0002: Every state change is an event; state is a fold over the log

**Status:** Accepted 2026-09-05
**Milestone:** M2 — replaces M0's "no save, in-memory only"

## Context

M0 kept state in memory and wrote nothing. That was a deliberate shortcut, and it cost exactly
what it was expected to: when something went wrong in a played session, there was no artefact to
reason about afterwards. The M2 gate asked whether a fault found in play could be turned into a
test, and the honest answer with in-memory state was no.

The usual fix — add persistence — has a failure mode of its own. If state can be mutated
directly *and* logged, someone eventually mutates without logging, and the log is quietly
incomplete in a way nothing detects until a replay diverges.

## Decision

`dm.model.Event` is the append-only spine. **There is no second write path** — no setter, no
`put`. `dm.state.WorldState` is only ever a fold over `EventLog`. If a change did not emit an
event, it did not happen.

Roll results are logged with their `faces` and outcome. Replay reads results; it never re-rolls
from a seed.

## Consequences

- The log is complete without anyone having to remember to keep it complete. That property is
  the whole point, and it is lost the moment a direct mutation is added "just for this one case".
- Every session writes itself to `server/sessions/`. A session worth keeping is copied to
  `docs/evidence/` or the test resources and replays on every build — the M2 workflow M3 used.
- A log written at an older `Event.SCHEMA_VERSION` is **refused, never upgraded**. Discarding a
  log is free; an upgrader is a tax paid forever. After a bump, `./gradlew recordFixture`.
- Sessions are written but nothing resumes from one. Resume is the expensive half and is an
  explicit anti-goal.
- Seed-based replay is ruled out permanently. It would be smaller on disk and would reintroduce
  the divergence this exists to prevent.

## Related

`AGENTS.md` invariants #5, #6, #8. `../milestones/m2-evaluation.md`.
