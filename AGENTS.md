# Emberdelve — Agent Rules

AI Dungeon Master. Java backend, React + Three.js frontend.
**Current milestone: M0** — a two-week vertical slice that exists to answer one question:
*does this feel like a Dungeon Master running a game?*

Read `docs/m0-build-plan.md` before changing anything. `docs/ai-dm-system-design.md` is the
long-range design; M0 deliberately contradicts parts of it, and where they disagree **M0 wins**.

---

## The point of M0

M0 is a **gate, not a foundation.** Everything that contributes to *feel* is in scope.
Everything that contributes to *correctness, scale, or persistence* is out of scope and gets
hardcoded. This is not technical debt — it is the point. **M0 code is expected to be thrown away.**

If you find yourself making something general, correct, or scalable, stop and re-read this section.

---

## Invariants — do not violate these

Each one is expensive to unwind. They are not style preferences.

1. **The server is authoritative.** The client never computes a roll, a hit, a legal move, or a
   death. It renders what it is told.
2. **No singleton player.** `List<PartyMember>`, always — even though M0 has exactly one member.
   Every action carries an `actorId`.
3. **Game state never lives in React state.** It lives in the Zustand store. React and Three.js
   both subscribe. Violating this causes canvas re-creation bugs that are very hard to diagnose.
4. **The Three.js renderer lives in a `useRef`** and is created exactly once.
5. **`RollResult.faces` is a `List<Integer>`.** Never collapse it to a total. The UI animates
   individual dice and crits key off the natural d20, not the sum.
6. **Roll results are logged as events.** Never plan to replay by re-rolling from a seed.
7. **All LLM-facing enums are closed and validated server-side.** No free-form string from the
   model reaches the engine. Tools use `strict: true`.
8. **No `localStorage` / `sessionStorage`.** In-memory only.
9. **Modern Java only** — records, sealed interfaces, pattern matching, virtual threads.
   No Spring. No `AbstractXFactory`. No mutable POJOs with getters and setters.
10. **Do not add tools, entity types, props, or rules** beyond what is listed below.

---

## Locked decisions

Resolved in a design review before implementation. Do not silently revisit these.

| Area | Decision |
|---|---|
| Assets | Kenney CC0. Architecture from Modular Dungeon Kit; props procedural until a kit provides them. |
| LLM | `claude-opus-5`, `effort: low`, adaptive thinking **on**, `strict: true` tools. Model and speed are config. |
| Narration | **Text channel**, not a tool. Inline `[[speaker]]` markers, validated against live entities. |
| TTS | ElevenLabs Flash v2.5 behind `TtsClient`. Web Speech API is the working placeholder. |
| Build | Gradle + Kotlin DSL. |
| Determinism | Real DM discretion. `ScriptedDiceRoller` behind `--demo` for reproducible tuning runs. |
| Combat VO | **Dramatic beats only** — kills, crits, and the goblin's turn. Ordinary hits resolve instantly. |

### Deviations from `docs/m0-build-plan.md` as written

The plan is the spec; these are the agreed amendments to it.

- **Four tools, not five.** `narrate` was removed — narration goes through the text channel so it
  streams from the first token. Remaining: `roll_check`, `reveal_prop`, `spawn_entity`, `start_combat`.
- **`Prop` gains `id` and `hidden`**; `type` is a closed enum. Without this `reveal_prop` cannot
  address anything.
- **`Diff` gains `PropRevealed`.** Without it a revealed prop never reaches the client.
- **Acceptance script step 3** exercises `reveal_prop`, so the player's first input produces a
  visible world change rather than narration alone.

---

## Never disable thinking on Opus 5

With `thinking: {type: "disabled"}` the model sometimes writes a tool call into **visible text**
instead of emitting a `tool_use` block. The turn succeeds, the call silently never runs, and no
error is raised. For a DM whose every mechanical effect is a tool call, that is silent state
corruption. Use `effort: "low"` to reduce latency instead.

---

## Hardcoded shortcuts — implement exactly these, do not "improve" them

| Shortcut | Value |
|---|---|
| Room | One, `content/rooms/crypt.json`, hand-authored |
| Party | `List<PartyMember>` containing one member |
| Fighter | AC 16, HP 12, +5 to hit, 1d8+3 damage, speed 30ft, STR +3 |
| Goblin | AC 15, HP 7, +4 to hit, 1d6+2 damage, speed 30ft |
| Attack resolution | `d20 + bonus >= AC`; nat 20 doubles dice. No crit tables, no resistances. |
| Skill check DCs | A 5-value enum only: 5 / 10 / 15 / 20 / 25 |
| State | `ConcurrentHashMap` behind `GameRepository` |
| Event log | In-memory `List<Event>` behind the same interface |
| Voices | Two hardcoded voice IDs in config |
| System prompt | One string in `prompts/dm.md`, loaded at boot |
| Context | Full transcript, no compaction |
| Errors | No recovery — log and surface a visible error toast |
| Tests | Dice and attack resolution only |

## LLM tools — exactly these four

`roll_check` (skill + difficulty enums), `reveal_prop` (per-room closed enum of hidden prop ids),
`spawn_entity` (kind: `goblin` only), `start_combat`.

Every enum is closed and validated server-side. Invalid calls are rejected with a structured
error; the model retries once, then the turn degrades to narration-only.

---

## Anti-goals

These are the things most likely to eat week two.

- **Do not build a rules engine.** ~40 lines of attack resolution. If you are modeling conditions, stop.
- **Do not tune lighting and post-processing for more than one evening.** Timebox it. This is the
  single largest time sink in the project and it will consume as much as you give it.
- **Do not add a second room.** The impulse will be strong. One room.
- **Do not build save/load.** Restarting the process is fine.
- **Do not optimize anything.** One room, two entities.
- **Do not generalize.** Every abstraction in M0 is written against a sample size of one.
- **Do not build a character sheet UI.** HP and AC as text is sufficient.

---

## Latency targets — these are the gate

| Path | Budget |
|---|---|
| Keypress → first streamed token | < 800ms |
| Keypress → first spoken word | < 1.5s |
| Click-to-move → token starts moving | < 100ms (no model in this path) |
| Full enemy round resolved and narrated | < 4s |

---

## Commands

```bash
cd server && ./gradlew run          # server on :7070
cd server && ./gradlew test         # dice + attack resolution
cd server && ./gradlew run --args='--demo'   # scripted dice, reproducible
cd client && npm run dev            # vite on :5173
```

## Secrets

`ANTHROPIC_API_KEY` and `ELEVENLABS_API_KEY` come from a gitignored `.env`.
Never commit a key. Never log one.
