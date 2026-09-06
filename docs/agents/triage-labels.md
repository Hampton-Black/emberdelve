# Triage Labels

The engineering skills speak in terms of five canonical triage roles. This file maps those roles
onto the label strings this repo actually uses. The tracker is beads, so a "label" is a beads
label: `bd label add <id> <label>` / `bd label remove <id> <label>`.

| Role in mattpocock/skills | Label in this repo | Meaning                                  |
| ------------------------- | ------------------ | ---------------------------------------- |
| `needs-triage`            | `needs-triage`     | Needs evaluation before anyone starts it |
| `needs-info`              | `needs-info`       | Waiting on the reporter — usually a session log |
| `ready-for-agent`         | `ready-for-agent`  | Fully specified, AFK-ready               |
| `ready-for-human`         | `ready-for-human`  | Needs a human — a judgement call about feel |
| `wontfix`                 | `wontfix`          | Will not be actioned                     |

The defaults are unchanged. When a skill names a role, apply the string in the second column.

## Two notes specific to this project

**`needs-info` almost always means "which session?"** Every session writes itself to
`server/sessions/`, so a bug reported without a session file is a bug that can't be replayed.
Ask for the file before anything else — see `AGENTS.md` § Commands on `--replay`.

**`ready-for-human` is not a fallback for "hard".** It is for the judgement calls this project
turns on: does the DM's voice sound right, does a beat land, is the room readable. Anything with
a failing replay behind it is `ready-for-agent` no matter how hard it looks, because the fixture
decides when it's done.

## Closing rather than labelling

`wontfix` is a label; beads also has real lifecycle verbs, and they carry more information:

- `bd close <id> --reason="..."` — done, or decided against.
- `bd defer <id> --until="<date>"` — right idea, wrong milestone. Better than `wontfix` for
  anything the anti-goals in `AGENTS.md` push past the current gate.
- `bd supersede <id> --with=<new-id>` — replaced by a better-stated issue.
- `bd duplicate <id> <canonical-id>` — already filed.

Prefer these to a label where they fit.
