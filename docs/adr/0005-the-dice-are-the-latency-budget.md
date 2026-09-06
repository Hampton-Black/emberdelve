# ADR-0005: The dice are the latency budget

**Status:** Accepted 2026-08-20, extended 2026-09-05
**Milestone:** M0, refined through M2

## Context

Prose from a good writer model takes several seconds. The instinct is to treat that as a
performance problem and optimise it — cheaper model, shorter prompt, fewer tokens. Every one of
those trades away the thing the project exists to be good at.

The gate was never speed. Both played M0 sessions produced complaints about the DM contradicting
a world it had already established; **not one** was about latency.

## Decision

The dice are not decoration — they are what makes the prose latency tolerable. Something with
weight happens at ~1s, and the narration lands while the player is still watching it.

The rules that hold that together:

- **The server decides, the animation displays.** Tumbling faces are noise; the instant a die
  settles it shows `result.faces[i]`. No physics library.
- **Narration is held until the dice land** (`Clock.hold` from `Table`), timer-based rather than
  driven by the tray node, so the gate still opens if the tray never mounts.
- **The log and the diffs are gated too.** A hit point bar that empties while the attack die is
  in the air has answered the question the die was asking. With no roll in flight the gate is
  open and everything passes straight through, which keeps click-to-move inside 100ms.
- **Not every roll animates.** `isDramatic()` keys off *purpose*, not off who rolled — attacks,
  saves and skill checks throw; damage and initiative go to the log.
- **The blow waits for the result to be read** (`IMPACT_BEAT_MS`, 650ms after `revealAt`), and
  everything a blow causes waits for the blow (`IMPACT_SECONDS`, 220ms).
- **The stakes are drawn from the first frame** — "ATHLETICS CHECK · DC 20" is readable while the
  die is still in the air. That is most of the tension.

Measured, click to swing: dice land 1060ms, legible 1460ms, readout finished 1720ms, swing
2120ms, impact 2340ms.

## Consequences

- Latency numbers are **recorded, not gated**. Consistency is the gate.
- "Animate the player's rolls" is wrong and was rejected: an incoming attack is the tensest die
  in the game, and the goblin throws it.
- Damage animating after to-hit makes one swing read as two, so it doesn't animate.
- If the prose model ever gets fast enough to beat the dice, the gate is what keeps it from
  announcing an outcome over a die still in the air. Don't remove it as dead weight.
- This is why ADR-0003's second call is free: it runs inside a window the player is already
  spending.

## Related

`AGENTS.md` § Dice, § Latency targets. ADR-0003.
