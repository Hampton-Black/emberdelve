import { chipDelay } from "../combat/opening";
import { drop, drum, play } from "./sfx";

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
 * <p>Drums, synthesised — see {@link drum}. An earlier version layered a pitched-down metal hit,
 * a drawn blade and the dice; the metal and the blade were both bright, and bright is the wrong
 * register for a crypt. Nothing here is above 210Hz at the moment of the strike.
 *
 * <p>No dice in it any more. Initiative is in the log where it belongs; this is about the drop in
 * the floor, and the clatter was fighting it.
 */
export function combatBegins(): void {
  // Two quick strikes and then a heavy one — a war-drum figure rather than three even beats,
  // because an even three reads as a countdown and this is meant to read as a threat.
  drum({ pitch: 200, floor: 52, gain: 0.85, seconds: 0.7 });
  drum({ pitch: 190, floor: 48, gain: 0.8, seconds: 0.7, delay: 0.3 });
  // The last one is the lowest and the longest: the drum the room is left with.
  drum({ pitch: 210, floor: 38, gain: 1.0, seconds: 1.5, delay: 0.72 });

  // A sub beneath all three, tuned under the final floor so it thickens rather than beats
  // against it.
  drop({ from: 88, to: 30, seconds: 2.1, gain: 0.34 });
}

/**
 * The initiative order being set: one small, dull knock per combatant as its chip lands.
 *
 * <p>Scheduled against {@link chipDelay} rather than fired from the bar's own render, so the
 * sound sits on the audio clock with the drums instead of on React's frame budget.
 *
 * <p>Pitched down and quiet on purpose. The obvious choice is a bright click, and a bright click
 * is exactly what came out of the sting when it was rebuilt as drums — nothing in this cue is
 * allowed to be brighter than the room.
 */
export function initiativeSet(count: number): void {
  for (let i = 0; i < count; i++) {
    const at = chipDelay(i) / 1000;
    play("thud", { gain: 0.3, rate: 0.85, delay: at });
    // The body under the knock, barely audible on its own — a figure set down rather than a
    // counter dropped on glass.
    play("cloth", { gain: 0.18, rate: 0.75, delay: at + 0.015 });
  }
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
