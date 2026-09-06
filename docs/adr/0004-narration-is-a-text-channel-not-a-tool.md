# ADR-0004: Narration is a text channel, not a tool

**Status:** Accepted 2026-08-20
**Milestone:** M0 — amends `../milestones/m0-build-plan.md`, which specified five tools

## Context

The build plan gave the model a `narrate` tool alongside the mechanical ones, which is the
obvious shape: everything the DM does becomes a structured call, and the server decides what to
do with each.

It has one cost that turns out to dominate. A tool call is only usable once it is complete and
parsed, so narration delivered as a tool argument cannot stream. The player waits for the whole
turn's prose before seeing a word of it.

## Decision

`narrate` is removed. Narration goes through the **text channel** and streams from the first
token. Speakers are marked inline with `[[speaker]]`, validated against live entities.

Four tools remain on the mechanics pass: `roll_check`, `reveal_prop`, `spawn_entity`,
`start_combat` — later joined by `use_exit` and `move_entity`, and by `assert_fact` on reconcile.

## Consequences

- Prose starts arriving immediately, which is what makes a ~6s narration latency tolerable.
- `[[speaker]]` markers are free-form text and must be validated against live entities before
  they reach the renderer — an unvalidated marker is a name the world doesn't contain.
- `assert_fact` is the one tool carrying free-form model text, and it is not a hole in the
  closed-enum invariant. The rule, stated so it doesn't erode: *free-form model text may enter
  the prompt; anything reaching the engine or the renderer goes through a closed enum.* A fact's
  `text` makes one round trip into the next prompt — it drives no roll, gates no legal move, and
  reaches no renderer. Its `anchor` is closed and validated like everything else.
- The voice queue, not the model, paces the transcript. The model streams a turn in about three
  seconds and the voice takes twenty to say it.

## Related

`AGENTS.md` invariant #7, § LLM tools. ADR-0003.
