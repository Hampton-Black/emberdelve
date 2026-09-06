# Domain Docs

How the engineering skills should consume this repo's domain documentation.

## Before exploring, read these

- **`AGENTS.md`** — the operational rules: invariants, locked decisions, anti-goals, and the
  measurements behind them. Read this first. It outranks everything else here.
- **`CONTEXT.md`** at the repo root — the glossary. What the domain nouns mean and which
  synonyms to avoid.
- **`docs/adr/`** — architectural decisions that touch the area you're about to work in.

This is a **single-context** repo. There is no `CONTEXT-MAP.md`: the Kotlin server in `server/`
and the Godot client in `godot/` share one vocabulary, and splitting the glossary along that line
would duplicate every term that crosses the wire.

If a file listed here doesn't exist yet, proceed silently — don't flag its absence and don't
propose creating it upfront. `/domain-modeling` (reached through `/grill-with-docs` and
`/improve-codebase-architecture`) creates ADRs lazily, when a decision actually gets made.

## File structure

```
/
├── AGENTS.md          ← project rules (CLAUDE.md imports this)
├── CONTEXT.md         ← glossary
├── docs/
│   ├── adr/           ← architectural decisions
│   ├── agents/        ← this directory: skill configuration
│   ├── evidence/      ← played sessions kept as replay fixtures
│   └── milestones/    ← gate results and build plans (m0, m2, m3)
├── server/            ← Kotlin/Gradle, authoritative
└── godot/             ← Godot client, renders what it's told
```

## Use the glossary's vocabulary

When your output names a domain concept — an issue title, a test name, a hypothesis, a refactor
proposal — use the term as `CONTEXT.md` defines it. Don't drift to a synonym the glossary
explicitly avoids; several of them are avoided because the drift caused a real bug.

If the concept you need isn't in the glossary, that's a signal: either you're inventing language
the project doesn't use, or there's a genuine gap worth noting for `/domain-modeling`.

## Flag conflicts, don't route around them

`AGENTS.md` § Invariants and § Locked decisions are the load-bearing ones. If your output
contradicts an invariant, stop and say so — those are listed precisely because each is expensive
to unwind. If it contradicts an ADR or a locked decision, surface it explicitly rather than
silently overriding:

> _Contradicts ADR-0003 (the DM is two models) — but worth reopening because…_

Milestone precedence, when documents disagree: M3 beats M2 beats M0, and `AGENTS.md` beats
`docs/ai-dm-system-design.md`.
