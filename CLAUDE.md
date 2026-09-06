# Emberdelve

@AGENTS.md

`AGENTS.md` above is the source of truth for this project's rules — invariants, locked
decisions, anti-goals, and the measured findings behind them. This file adds nothing but the
commands, so that every tool reads the same rules. **Put new project rules in `AGENTS.md`.**

`CONTEXT.md` is the domain glossary; `docs/adr/` holds architectural decisions; `docs/agents/`
tells the engineering skills where issues and domain docs live.

## Build & Test

```bash
cd server && ./gradlew test         # whole suite, including the replayed fixture session
cd server && ./gradlew run          # server on :7070
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit   # client tests
```

Both suites are the quality gate. `AGENTS.md` § Commands has the rest — `--demo`,
`--generate`, `--replay`, and `recordFixture`.

## Task tracking

`bd` (beads), not TodoWrite and not markdown checklists. `bd ready` to find work,
`bd update <id> --claim` to take it, `bd close <id>` when done. See `docs/agents/issue-tracker.md`
for how the engineering skills map onto it, and `bd prime` for the full command reference.
