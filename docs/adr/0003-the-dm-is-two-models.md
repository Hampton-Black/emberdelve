# ADR-0003: The DM is two models, not one

**Status:** Accepted 2026-09-05, amended 2026-09-05 at the M2 gate
**Milestone:** M2

## Context

The load-bearing dependency in this architecture is **tool-calling reliability**, not prose
quality and not price. Every mechanical effect goes through a validated tool call, so a model
that narrates beautifully and forgets to call `spawn_entity` produces a goblin that is vividly
described and never appears — a silent failure that looks like a rendering bug.

Measured against the real tool schema, one round, "heave the sarcophagus lid open":

| Model | TTFT | Calls `roll_check` correctly? |
|---|---|---|
| `qwen3-coder-480b-a35b-instruct-turbo` | 795ms | yes |
| `qwen3-next-80b` | 834ms | yes |
| `qwen3-235b-a22b-instruct-2507` | 1605ms | yes |
| `zai-org-glm-5-2` | 1924ms | yes |
| `deepseek-v4-flash` | 2437ms | yes |
| `claude-opus-5` | 2679ms | yes |
| `grok-4-6` | 5042ms | yes |
| `venice-uncensored-1-2` | 728ms | **no — narrates instead** |
| `venice-uncensored-role-play` | 914ms | **no — narrates instead** |
| `mistral-small-3-2-24b-instruct` | 1017ms | **no — narrates instead** |

Most `e2ee-*` models report `supportsFunctionCalling: false` and are incompatible outright,
despite being the strongest privacy tier.

Two findings drove the decision. Reasoning-on models land at 1.9–5.0s to first token against
0.7–1.6s for reasoning-off, and the agentic loop multiplies it — a three-tool-call turn on
`grok-4-6` measured **44 seconds**. And the models that write the best prose are precisely the
ones that cannot drive this architecture.

Choosing one model meant choosing which of those two failures to accept.

## Decision

Two phases, two config strings, never literals.

**Phase 1 — mechanics, `DM_MODEL_TOOLS`.** Decides tool calls; any prose it writes is discarded.
Wants a fast, non-reasoning, reliably tool-calling model. The <800ms budget applies here, because
this is the phase that puts dice on the table.

**Phase 2 — narration, `DM_MODEL_PROSE`.** Writes prose with the engine's real results as
context, *while the dice are still animating*. That concurrency is what buys permission to be
slow, so it takes the best writer available.

Measured end to end, `--demo`:

| Config | First dice | First word | Total |
|---|---|---|---|
| Single model (`grok-4-6`) | — | — | 44,000ms |
| Split, degraded tools model | 33,908ms | 45,616ms | 48,323ms |
| Split, healthy tools model | **1,025ms** | 7,394ms | 8,860ms |

## Consequences

- A model that writes beautifully but cannot call tools becomes **usable** — it goes in the prose
  slot. `venice-uncensored-role-play` was disqualified outright before the split.
  *(Amended below: usable on tool-calling is not the same as usable. That model lost the prose
  slot anyway.)*
- A model that calls tools reliably but hallucinates props becomes **usable** — it never writes
  narration, so it cannot invent anything. `qwen3-next-80b` was disqualified before the split.
- **Narration stays on exactly one model.** Splitting *narration* across models makes tone drift
  audible between turns; splitting mechanics off does not.
- Two calls per turn instead of one. The second overlaps the dice animation, so the player does
  not pay for it — which is only true while the dice keep their timing (ADR-0005).
- The health of the tools model is now the thing to check first when a turn feels slow. A
  degraded tools model costs 33 seconds before the first die lands.

---

## Amendment (M2 gate): the prose slot has its own competence bar

The split was supposed to make the prose slot indifferent to everything except writing — a
narrator cannot call a tool, so it cannot drop one, so pick the best voice and stop worrying.
Six shorter sessions in one day, then a 30-turn session, showed that was half right.

`venice-uncensored-role-play` is **the better voice and the worse follower.** It leaked the
world-state footer into spoken narration — the prompt scaffolding read aloud to the player as
though it were prose — and it drifted off the DM rules it was given. Neither failure is a tool
call. Both reach the player directly.

**`DM_MODEL_PROSE=gemini-3-8-flash` with `DM_REASONING_EFFORT_PROSE=low`.** This is the split
`../milestones/m2-evaluation.md` and `../milestones/m3-evaluation.md` were played and signed on, alongside
`DM_MODEL_TOOLS=qwen3-next-80b`.

**`low` is not optional and it is the floor.** Gemini 3.x Flash cannot turn thinking off; Venice
will not accept lower. At default thinking it takes 9–15s to a first word, which is an empty
screen the dice cannot cover (ADR-0005). `low` brings it to 3–8s. Leaving the variable unset
means default, not off — and a bad value 400s the turn, so it stays unset for models that don't
take the parameter.

### What this changes about the decision

The two slots do not have one requirement each. They have different requirements, and the prose
slot's is **instruction-following**, not tool-calling:

- Don't speak the scaffolding. The world-state footer is context, not narration.
- Don't invent world state. This is invariant #7's spirit reaching a model that touches no enum
  — `assert_fact` is the only sanctioned path for new fiction, and it round-trips through the
  prompt rather than the renderer.
- Don't say a grid coordinate aloud. Facts anchor with `at_square` internally; the player hears
  a room, not a spreadsheet.
- Name the actor in a kill beat. `BeatRenderer` supplies "Vessk is killed by the blow"; Gemini
  writes *your blade* anyway. A worse writer will invert it, and that is a one-line fix in the
  beat renderer whenever a sloppier writer arrives.

So "a beautiful writer that cannot call tools is usable" needs the qualifier: usable **if it
follows instructions.** Prose quality was never the scarce thing. Compliance was, in both slots,
for different reasons.

### Consequences of the amendment

- Tone regressed on purpose. `m0-evaluation.md` judged voice against the role-play model, so the
  M0 tone notes describe a narrator this configuration no longer runs. Read them as a target,
  not as a description of current output.
- `claude-opus-5` stays the `.env.example` default deliberately. It is a fine writer and it has
  **not** been played against a full gate, so it is the safe starting point for someone with a
  key and no session history — not the gated pick. Don't "fix" the file to match this ADR.
- A prose candidate now needs a played session, not a benchmark. Footer leakage and rule drift
  do not show up in a single-turn sample.
- The tools slot is unchanged by this: `qwen3-next-80b` still hallucinates props and still holds
  the slot, because prose it writes is discarded. That is the split doing its job.

## Related

`AGENTS.md` § Models. ADR-0004,
ADR-0005 on why an empty screen is the thing `low` is buying. ADR-0008 on how the pick was
made. `../milestones/m2-evaluation.md`,
`../milestones/m3-evaluation.md`.
