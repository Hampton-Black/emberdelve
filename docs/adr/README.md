# Architecture Decision Records

One decision per file, numbered, never deleted. A decision that stops being true gets a new ADR
that supersedes it — the old one stays, because the reasoning is the point.

`0000-template.md` is the shape. Number from the highest existing file; slug the filename after
the decision, not the component.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-server-is-authoritative.md) | The server is authoritative; the client renders what it is told | Accepted 2026-08-20 |
| [0002](0002-state-is-a-fold-over-an-event-log.md) | Every state change is an event; state is a fold over the log | Accepted 2026-09-05 |
| [0003](0003-the-dm-is-two-models.md) | The DM is two models, not one | Accepted 2026-09-05, amended |
| [0004](0004-narration-is-a-text-channel-not-a-tool.md) | Narration is a text channel, not a tool | Accepted 2026-08-20 |
| [0005](0005-the-dice-are-the-latency-budget.md) | The dice are the latency budget | Accepted 2026-08-20 |
| [0006](0006-godot-replaces-the-web-client.md) | Godot replaces the Vite/Three.js client | Accepted 2026-08-23 |
| [0007](0007-venice-ai-as-the-provider.md) | Venice.ai as the single LLM provider | Accepted 2026-08-20 |
| [0008](0008-model-picks-come-from-played-sessions.md) | Model picks come from played sessions, not benchmarks | Accepted 2026-09-05 |
| [0009](0009-the-world-frame-is-anchored-at-the-entrance.md) | The world frame is server-owned and anchored at the entrance | Accepted 2026-09-06 |
| [0010](0010-the-room-nearer-the-entrance-owns-the-shared-wall.md) | The room nearer the entrance owns the shared wall | Accepted 2026-09-06 |
| [0011](0011-visible-is-not-addressable.md) | Visible is not addressable | Accepted 2026-09-07 |
| [0012](0012-a-consequence-may-only-do-what-a-tool-can-do.md) | A consequence may only do what a tool can already do | Proposed |
| [0013](0013-scarcity-is-the-engines-not-the-narrators.md) | Scarcity is the engine's, not the narrator's | Proposed |

0001–0008 were back-filled on 2026-09-06 from decisions already recorded in `AGENTS.md`. Where an ADR
and `AGENTS.md` disagree, `AGENTS.md` is the operational document and wins; open a new ADR rather
than editing an accepted one to match.
