# ADR-0007: Venice.ai as the single LLM provider

**Status:** Accepted 2026-08-20
**Milestone:** M0

## Context

The design needs two different models with different strengths (ADR-0003) and expects to keep
re-measuring as models change. Wiring each candidate to its own SDK and auth would make that
measurement expensive enough that it wouldn't happen — which is how a project ends up with a
model chosen once and never revisited.

## Decision

**Venice.ai**, OpenAI-compatible, at `https://api.venice.ai/api/v1`. One key, one endpoint,
100+ models. Models are named by config string — `DM_MODEL_TOOLS` and `DM_MODEL_PROSE` — and
never as literals in code.

## Consequences

- Swapping either model is a config change, and comparing candidates is a re-run rather than an
  integration.
- Venice exposes `supportsFunctionCalling` and `supportsResponseSchema` per model. **Require
  both** — but the flag is a capability claim, not a behaviour: several models that advertise
  function calling narrate instead. Measure against the real tool schema before trusting one.
- Most `e2ee-*` models report `supportsFunctionCalling: false` and are incompatible outright,
  despite being the strongest privacy tier.
- `VENICE_API_KEY` comes from a gitignored `.env`. Never commit a key, never log one.
- If a model is ever routed to directly rather than through Venice, provider-specific traps come
  back with it. The known one: never set `thinking: {type: "disabled"}` on Opus 5 — it can write
  a tool call into visible text instead of emitting a `tool_use` block, with no error raised.
  Use a low effort setting instead.

## Related

`AGENTS.md` § Locked decisions, § Models, § Secrets. ADR-0003, ADR-0008.
