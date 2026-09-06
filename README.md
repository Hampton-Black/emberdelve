# Emberdelve

An AI Dungeon Master you play by typing at it, with an isometric 3D table that shows you what it
just did.

A language model narrates and improvises. It does not run the game. A Java engine owns the dice,
the rules, and every fact about the world, and the model reaches that world only through validated
tool calls with closed enums. When the DM says a goblin climbs out of the sarcophagus, a goblin
climbs out of the sarcophagus — or the call is rejected and nothing happens. Nothing in between.

**Status: a working vertical slice.** One hand-authored crypt, one fighter, one goblin, about forty
lines of combat rules. It is played from a terminal and a Godot window, it is not packaged, and it
is a solo evenings project. What it is *not* short of is foundations: state is an append-only event
log, sessions record themselves, and a session that went wrong can be replayed offline as a test.

---

## Quick start

**You need:** JDK 25 (the Gradle toolchain will fetch one if it can), [Godot 4.7](https://godotengine.org),
and a [Venice.ai](https://venice.ai) API key. The Gradle wrapper handles Gradle itself.

```bash
cp .env.example .env    # then put your Venice key in it
```

Two terminals. The server first:

```bash
cd server && ./gradlew run
```

Then the client — either open `godot/` in the Godot editor and press play, or:

```bash
cd godot && godot .
```

The client talks to `http://127.0.0.1:7070`. Point it somewhere else with `EMBERDELVE_SERVER`.

**Without a Venice key** the world still renders and combat still works; there is just no DM. Without
an ElevenLabs key the narration falls back to your OS voice. Neither is fatal, and the server says
which one it is missing at boot.

---

## How a turn works

You type a sentence. Three things happen, in order, and only the first two are visible.

1. **Mechanics.** A fast, reliably tool-calling model is asked what the sentence *does*. It gets
   `roll_check`, `reveal_prop`, `spawn_entity`, `start_combat` — every argument a closed enum,
   validated server-side. It writes no prose. Dice hit the table here, which is why this phase owns
   the latency budget.
2. **Narration.** A second, better-writing model describes what happened, streaming, *while the
   dice are still animating*. It has no tools at all. It is handed the engine's actual results and
   an engine-owned block of world state, and its job is to say what is already true.
3. **Reconcile.** The narration goes back to the fast model with one question: *what must change on
   the board for that to have been true?* The narrator leads, the engine follows — which is the
   order a human DM works in. They say it, then they move the miniature. Things the board cannot
   hold (a smell, a scratch, a ring on a dead hand) are recorded with `assert_fact` so they are
   still true next turn.

Two models rather than one because the requirements conflict: the model that writes the best prose
is often the one that will not call a tool, and the model that always calls tools invents props.
Split, both of those become usable. Details and the measurements behind the picks are in
[AGENTS.md](AGENTS.md).

---

## The event log, and why it is the interesting part

Every state change is an event. There is no other way to write state — no setter, no `put`. The
world is an immutable `WorldState` you get by folding the log, and if a change did not emit an
event, it did not happen.

Events are **facts, not commands**. `AttackResolved` carries the attacker, the target, both rolls,
the damage, and whether it killed. Not "attack this thing" — replaying that would re-roll and
produce a different game.

Which buys three things:

- **Every session records itself** to `server/sessions/<timestamp>-<id>.jsonl`, one event per line,
  flushed as it goes. The sessions worth having are the ones that ended badly.
- **A session replays offline.** No network, no key, no model. The recorded dice come back through
  a `ReplayDiceRoller` and the engine has to produce the same events it produced the first time.
- **The DM's context stops growing.** The model sees the last six turns verbatim, plus a projection
  folded from the whole log — entities, revealed props, the fight, and every fact the DM has
  asserted in prose. Recency is what prose needs; permanence is what consistency needs. They are
  different context.

When something goes wrong in play, the file that proves it is already on disk:

```bash
cd server && ./gradlew run --args='--replay sessions/20260905-205347-b4a16f95.jsonl'
```

Copy that file into `server/src/test/resources/sessions/` and it runs on every build. That loop —
play, capture, fix, keep — is the whole point of the architecture.

---

## Commands

```bash
cd server && ./gradlew run                          # server on :7070
cd server && ./gradlew test                         # the suite, including a replayed real session
cd server && ./gradlew run --args='--demo'          # scripted dice, reproducible
cd server && ./gradlew run --args='--generate 7'    # a seeded, procedurally generated room
cd server && ./gradlew run --args='--replay <file>' # re-run a recorded session, offline

cd godot && godot .                                 # the editor
cd godot && godot --headless -d -s addons/gut/gut_cmdln.gd -gdir=res://test -gexit   # client tests
```

---

## Layout

| Path | What lives there |
|---|---|
| `server/src/main/java/dm/model/` | The vocabulary: `Event`, `Entity`, `RollResult`, the closed enums |
| `server/src/main/java/dm/state/` | `EventLog`, `WorldState`, `SessionWriter` — the spine |
| `server/src/main/java/dm/engine/` | Dice, attack resolution, combat, the goblin's heuristics |
| `server/src/main/java/dm/ai/` | The two model clients, the three-phase turn, tool schemas, TTS |
| `server/src/main/java/dm/generate/` | Procedural room generation — shapes, props, spatial validation |
| `server/src/main/java/dm/replay/` | Replaying a recorded session against the engine |
| `server/src/main/resources/prompts/` | The DM's system prompts, as Markdown |
| `server/src/main/resources/content/` | Rooms, entities, and tile kits, as data |
| `godot/world/` | The isometric table — one scene, created once |
| `godot/chrome/` | Overlay: dice tray, combat bar, transcript, debug |
| `godot/autoload/` | `Table` holds all client state; `Net` owns the one socket; `Link` the one URL |

Two rules the layout depends on. **The server is authoritative** — the client never computes a
roll, a hit, a legal move, or a death; it renders what it is handed. And **game state never lives
in a node** — it lives in `Table`, and both the chrome and the world subscribe. Breaking the second
rebuilds the 3D world on a chrome redraw, which is miserable to diagnose.

---

## Documentation

Read in this order.

| Document | What it is |
|---|---|
| [AGENTS.md](AGENTS.md) | The rules. Invariants, locked decisions, measured model picks, anti-goals. Start here before changing anything |
| [docs/m2-evaluation.md](docs/milestones/m2-evaluation.md) | The most recent gate — a 30-turn session, what held and what did not |
| [docs/superpowers/specs/2026-08-23-m2-spine-design.md](docs/superpowers/specs/2026-08-23-m2-spine-design.md) | The architecture above, argued out |
| [docs/m0-evaluation.md](docs/milestones/m0-evaluation.md) | The first gate. Where "multi-turn consistency is the metric" comes from |
| [docs/ai-dm-system-design.md](docs/ai-dm-system-design.md) | The long-range design. Superseded twice on ordering — history, not a plan |

Each milestone is a question, not a feature list, and each one ends with a signed answer:

| | Question | |
|---|---|---|
| **M0** | Does this feel like a Dungeon Master running a game? | PASS 2026-08-20 |
| **M1** | Can the world be made rather than authored? | Half done — generation merged, navigation outstanding |
| **M2** | Can a fault found in play be turned into a test? | PASS 2026-09-05 |

Next is dungeon navigation: more than one room, and doors that work.

---

## Configuration

All of it in `.env`, which is gitignored. `.env.example` documents every key with the measurements
behind its default.

| Variable | |
|---|---|
| `VENICE_API_KEY` | Required for a DM. One key, 100+ models, OpenAI-compatible |
| `DM_MODEL_TOOLS` | The mechanics model. Wants fast and non-reasoning. Default `qwen3-next-80b` |
| `DM_MODEL_PROSE` | The narrator. Wants the best writer you can afford |
| `DM_REASONING_EFFORT_PROSE` | Set to `low` on models that think by default, or the first word takes 9–15s |
| `ELEVENLABS_API_KEY` | Optional. Without it, narration uses your OS voice |
| `EMBERDELVE_SERVER` | Client-side. Where the server is. Default `http://127.0.0.1:7070` |

Never commit a key. Never log one.

---

## Assets

Environment and character art is [KayKit](https://kaylousberg.itch.io) — Adventurers Knight,
Skeletons Warrior, and the Dungeon kit. M0 shipped on [Kenney](https://kenney.nl) CC0 kits
(Modular Dungeon, Graveyard) before the Godot table replaced it. Which kit is used where, and why
the figures are deliberately oversized on their squares, is in AGENTS.md.
