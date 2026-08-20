import { moveSeconds } from "../scene/tokens";
import { drop, play } from "./sfx";

/** The sounds the room makes: things opening, and things walking about in it. */

/**
 * The sarcophagus giving up its lid.
 *
 * <p>Hung off a hostile entity arriving, which in M0 is the only way anything ever appears — the
 * goblin comes out of the sarcophagus and nothing else spawns at all. If a second spawn point is
 * ever added this needs to become a property of the prop rather than of the arrival.
 */
export function lidOpens(): void {
  // Creak dragged well below speed stops being timber and becomes stone under its own weight.
  play("grind", { gain: 0.9, rate: 0.5 });
  play("grind", { gain: 0.4, rate: 0.62, delay: 0.22 });
  // Then it lets go: the catch, the slab landing, and the room taking it.
  play("latch", { gain: 0.55, rate: 0.7, delay: 0.5 });
  play("thud", { gain: 0.75, rate: 0.44, delay: 0.62 });
  drop({ from: 84, to: 33, seconds: 0.8, gain: 0.3, delay: 0.6 });
}

/** Something coming to light — smaller than a lid, and over quickly. */
export function revealed(): void {
  play("latch", { gain: 0.5 });
  play("grind", { gain: 0.45, rate: 0.95, delay: 0.08 });
}

/**
 * Footfalls under a move, spread across exactly as long as the slide takes.
 *
 * <p>Capped at four: a six-square dash is not six audible steps at this scale, it is a hurry,
 * and the ear stops counting after three or so anyway.
 */
export function footsteps(squares: number): void {
  const steps = Math.max(1, Math.min(Math.round(squares), 4));
  const over = moveSeconds(squares);

  for (let i = 0; i < steps; i++) {
    // Offset from the start of the slide rather than from zero: the token eases in, so a step
    // exactly on the diff would land before the foot has moved.
    play("step", { gain: 0.42, delay: 0.06 + (over * i) / steps });
  }
}
