# ADR-0008: Model picks come from played sessions, not benchmarks

**Status:** Accepted 2026-09-05
**Milestone:** M2

## Context

ADR-0003 split the DM into two slots. It did not say how a model gets into one, and the first
attempt — measure time-to-first-token against the real tool schema, rank, pick the top — produced
a ranking that did not survive contact with a second measurement.

Every early figure in `AGENTS.md` was collected in one burst. A single-shot benchmark against a
multi-tenant inference provider measures **a moment, not a steady state**.

## Decision

A model enters a slot on the evidence of a **played session**, not a benchmark. Benchmarks
shortlist; sessions decide. Sample repeatedly, and across time, before believing a number.

Current pick, signed at the M2 gate and unchanged through M3:

```
DM_MODEL_TOOLS=qwen3-next-80b
DM_MODEL_PROSE=gemini-3-8-flash
DM_REASONING_EFFORT_PROSE=low
```

### Finalists, 4 samples each

| Model | TTFT samples | Tools | Prose |
|---|---|---|---|
| `qwen3-coder-480b-a35b-instruct-turbo` | 563 / 653 / 603 / 621ms | 100% valid | good, stays in-world |
| `qwen3-next-80b` | 540 / 771 / 577 / 586ms | 100% valid | **invents props** |
| `deepseek-v4-flash-0731-fast` | 1195 / 1364 / 1599 / **38478**ms | 100% valid | good, stays in-world |

### Disqualifications, and what each one teaches

- **`qwen3-coder-480b-a35b-instruct-turbo` — bimodal latency.** Looked like the winner on first
  measurement. Over one session it went 563ms → 42s → 66s → 621ms → 34s, with full rate-limit
  quota remaining and no error raised. **Bimodal is worse than consistently slow, because you
  cannot design around it.** Assume any very large MoE on a smaller provider may behave this way.
- **`deepseek-v4-flash-0731-fast` — a fine median and an unusable tail.** Occasional 38-second
  stalls. One freeze mid-session ruins a five-minute demo. On tools it put an 8.5s hole in front
  of a die.
- **`venice-uncensored-role-play` — the better voice, the worse follower.** See ADR-0003's
  amendment. Disqualified from prose on rule-following, not on writing.
- **`gemini-3-8-flash` at default thinking** — 9–15s to a first word. Not disqualified; corrected
  with `reasoning_effort=low`, which is the floor rather than a tuning choice.

### The tail the benchmark could not find

`qwen3-next-80b` has one too, **found in play**: 14.3s and 15.5s tool phases in two logged
sessions, no error, full quota. Rarer than the 480b's, and it survives the pick — but it is the
reason to watch `FIRST FEEDBACK` in a live session rather than trusting a clean benchmark.

## Consequences

- Adding a candidate costs a played session. That is the price of the decision, and it is why the
  model strings are config rather than literals (ADR-0007) — the alternative is a pick made once
  and never revisited.
- **A capability flag is a claim, not a behaviour.** Venice's `supportsFunctionCalling` and
  `supportsResponseSchema` are both required, and several models that advertise the first narrate
  instead of calling anything. Measure against the real tool schema.
- `qwen3-next-80b` **hallucinates props** — it invented "a small silver disc, half-buried in ash"
  and a trail of footprints that exist in no room, and misused a `[[fighter]]` marker for plain
  narration. It holds the tools slot anyway, because its prose is discarded. That is precisely
  the safety the two-model split buys, and it is void the moment anyone promotes this model to
  the prose slot.
- Latency numbers in this repo are **recorded, not gated** (ADR-0005). A model is disqualified
  for a tail that breaks a session, not for a median.
- Every ranking in this repo has a date on it. Treat one older than a milestone as a starting
  point for re-measurement, not as a result.

## Related

ADR-0003 (the split, and the prose slot's rule-following bar), ADR-0005 (why a tail is worse
than a mean), ADR-0007 (why swapping is a config change). `../milestones/m2-evaluation.md`.
