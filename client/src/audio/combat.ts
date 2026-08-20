import { drop, play } from "./sfx";

/**
 * The sounds a fight makes.
 *
 * <p>Everything here is scheduled on the audio clock rather than with timers. A sting is layers
 * a tenth of a second apart, and `setTimeout` is not accurate enough to keep them from smearing
 * into each other.
 */

/** How far into the 417ms swing the blade connects. Mirrors `IMPACT_SECONDS` in `tokens.ts`. */
const IMPACT_MS = 220;

/**
 * Combat opening.
 *
 * <p>A stinger is a transient over a weight. The metal hits are the transient; the sine drop is
 * the weight, and without it this is a loud clang rather than an event. The drawn blade in the
 * middle is what makes it read as *this* kind of fight — the dice on the end are still there,
 * because initiative was still rolled, but they are no longer the whole of it.
 */
export function combatBegins(): void {
  drop({ from: 128, to: 38, seconds: 1.2, gain: 0.42 });
  play("boom", { gain: 0.95, rate: 0.34 });
  // A second, higher strike just behind the first reads as resonance rather than as two hits.
  play("boom", { gain: 0.36, rate: 0.52, delay: 0.1 });
  play("steel", { gain: 0.8, delay: 0.3 });
  play("grab", { gain: 0.45, delay: 0.66 });
  play("throw", { gain: 0.55, rate: 0.88, delay: 0.92 });
}

/**
 * One blow. A hit is three clips and a miss is one: the blade moves either way, and what
 * separates them is whether anything is there when it arrives. An explicit "miss" noise would
 * be the game saying out loud what the silence already says.
 */
export function swing(connected: boolean): void {
  // A miss cuts higher and thinner. Same clip, and the ear reads it as air rather than meat.
  play("swing", connected ? { gain: 0.7 } : { gain: 0.5, rate: 1.2 });
  if (!connected) return;

  // Delayed to the moment of contact — the same 220ms the hit point bar and the death animation
  // already wait — so all three land together instead of on the wind-up.
  const at = IMPACT_MS / 1000;
  play("chop", { gain: 0.8, delay: at });
  // Metal just under the chop, so a blow lands on a creature wearing something rather than in a
  // butcher's shop.
  play("impact", { gain: 0.4, rate: 1.1, delay: at + 0.02 });
}

/** A body reaching the floor, partway through the 333ms death clip rather than at its start. */
export function fell(): void {
  const at = (IMPACT_MS + 210) / 1000;
  play("thud", { gain: 0.85, rate: 0.42, delay: at });
  play("cloth", { gain: 0.5, rate: 0.8, delay: at + 0.03 });
  drop({ from: 70, to: 32, seconds: 0.5, gain: 0.22, delay: at });
}
