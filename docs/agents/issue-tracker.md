# Issue tracker: Beads (`bd`)

Issues, PRDs and wayfinder maps for this repo live in **beads**, a local Dolt database under
`.beads/`. Use the `bd` CLI for all operations.

This repo has a GitHub remote, but **GitHub Issues is not the tracker.** Never run `gh issue`
in place of `bd`. If a skill's instructions name `gh issue`, substitute the `bd` equivalent
below.

Full command reference: `bd prime`, or the skill at `.agents/skills/beads/SKILL.md`.

## Conventions

- **Create**: `bd create --title "..." --description "..." --type=task|bug|feature|epic|chore --priority=<0-4>`
  Priority is numeric (`0`–`4` or `P0`–`P4`, 0 = critical, 2 = default), **never** `high`/`low`.
  Use a heredoc for multi-line descriptions. `bd q "title"` is the scripting shorthand — it
  prints only the new id.
- **Read**: `bd show <id>` for the issue; `bd comments <id>` for its conversation.
  `--json` on either for machine-readable output.
- **List**: `bd list --status=open` — add `--label <name>` (AND), `--label-any a,b` (OR),
  `--json`, or `--pretty` for the tree view. `bd ready` is the one that matters most: open,
  unblocked, unclaimed.
- **Search**: `bd search "<query>"`.
- **Comment**: `bd comment <id> "..."`, or `bd comment <id> --file notes.md` for long bodies.
- **Label**: `bd label add <id> <label>` / `bd label remove <id> <label>`.
- **Claim**: `bd update <id> --claim` (assigns to you and moves it to `in_progress`).
  `bd assign <id> <name>` to hand it to someone else.
- **Close**: `bd close <id> --reason="..."`. Several at once: `bd close <id1> <id2> ...`.
  `--suggest-next` shows what the close unblocked.
- **Structure**: `bd create --parent=<id>` for a child (task under epic, subtask under task).
  Children inherit the parent's labels.

Never run `bd edit` — it opens `$EDITOR` and blocks the session. Use `bd update <id> --title/
--description/--notes/--design` instead.

## When a skill says "publish to the issue tracker"

`bd create`. Use `--type=epic` for a PRD or a feature that will get children, `--type=task`
for the implementation issues under it, `--type=bug` for a defect found in QA. Attach the
acceptance criteria with `--acceptance="..."` and the reasoning with `--design="..."` — both
are checked by `bd lint` and `--validate`.

## When a skill says "fetch the relevant ticket"

`bd show <id>` plus `bd comments <id>`. Ids look like `emberdelve-auj`, and the user will
normally pass one directly.

## Blocking and dependencies

Beads has first-class dependencies — this is the reason it's the tracker here, so use them
rather than writing "blocked by" into a description.

- `bd dep add <blocked> <blocker>` — the first depends on the second.
- `bd dep <blocker> --blocks <blocked>` — the same edge, stated the other way round.
- `bd blocked` lists everything currently gated; `bd show <id>` names what gates that one.
- `bd dep cycles` before filing a large graph.

## Wayfinding operations

Used by `/wayfinder`. The **map** is an epic; **child tickets** are its children.

- **Map**: `bd create --type=epic --labels=wayfinder:map --title "..."` — the Notes /
  Decisions-so-far / Fog body goes in `--description`.
- **Child ticket**: `bd create --parent=<map-id> --labels=wayfinder:<type>` where `<type>` is
  `research`, `prototype`, `grilling` or `task`. The question goes in the description.
- **Blocking**: `bd dep add <child> <blocker>`. A ticket is unblocked when every blocker is
  closed — beads computes this, so don't track it by hand.
- **Frontier**: `bd ready --parent=<map-id>` — descendants of the map that are open,
  unblocked and unassigned, in priority then creation order. `bd ready --parent=<map-id> --claim`
  takes the first one atomically, which is what you want when several agents share a map.
  `bd ready --explain` says why something is or isn't ready.
- **Claim**: `bd update <id> --claim` — the session's first write, before any work.
- **Resolve**: `bd comment <id> "<answer>"`, then `bd close <id>`, then append a context
  pointer to the map's Decisions-so-far with `bd update <map-id> --notes="..."`.

## Memory

`bd remember "<insight>"` for knowledge that should outlive the session; `bd memories <keyword>`
to search it. Do not create `MEMORY.md` files in this repo.

## Sync

The database is local. `bd dolt push` / `bd dolt pull` sync it over `refs/dolt/data` on the git
remote; `.beads/issues.jsonl` is a passive export, not the source of truth — never hand-edit it.
Under the conservative agent profile in `AGENTS.md`, an agent does not push without being asked.
