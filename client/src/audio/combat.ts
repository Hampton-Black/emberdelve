import { play } from "./sfx";

/**
 * The sounds a blow makes.
 *
 * <p>A hit is two clips and a miss is one: the blade moves either way, and what distinguishes
 * them is whether anything is there when it arrives. Nothing else was needed — an explicit
 * "miss" noise would be the game telling the player something the silence already said.
 */

/** How far into the 417ms swing the blade connects. Mirrors `IMPACT_SECONDS` in `tokens.ts`. */
const IMPACT_MS = 220;

let pending: ReturnType<typeof setTimeout> | null = null;

export function swing(connected: boolean): void {
  // A miss cuts higher and thinner. Same clip, and the ear reads it as air rather than meat.
  play("swing", connected ? { gain: 0.7 } : { gain: 0.5, rate: 1.2 });

  if (pending !== null) clearTimeout(pending);
  if (!connected) return;

  // Scheduled rather than played now, so the sound lands with the blow and not with the wind-up —
  // the same 220ms the hit point bar and the death animation already wait.
  pending = setTimeout(() => {
    pending = null;
    play("impact", { gain: 0.85, rate: 0.9 });
  }, IMPACT_MS);
}

/**
 * The dice coming out. Fires once when a fight starts, not once per combatant — initiative is a
 * batch, and the tray does not show it (T12), so this is the only signal that it happened.
 */
export function initiative(): void {
  play("throw", { gain: 0.9, rate: 0.72 });
}

/** Drops anything scheduled, so a swing cannot land after the screen has moved on. */
export function silenceCombat(): void {
  if (pending !== null) clearTimeout(pending);
  pending = null;
}
