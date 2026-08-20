/**
 * The timing of the moment a fight starts.
 *
 * <p>Shared by the store and the combat bar for the same reason {@link ../dice/tumble} shares
 * its timings with the tray: one of them animates the beat and the other holds everything else
 * behind it, and if the two numbers ever disagree the narrator talks over the ceremony.
 *
 * <p>The whole sequence is built to fit inside the time the player was already going to wait —
 * the DM's first token — so on a real turn it costs nothing. When the model is fast, the beat
 * still lands rather than being skipped.
 */

/** The bar drops in from the top edge, just behind the drums. */
export const BAR_IN_DELAY_MS = 120;
export const BAR_IN_MS = 200;

/**
 * When the first combatant's chip lands.
 *
 * <p>Deliberately off the drum figure in {@link ../audio/combat.combatBegins}, whose strikes fall
 * at 0ms, 300ms and 720ms: chips landing at 400ms and 580ms sit in the gap between the second
 * strike and the heavy third one, so the two rhythms interleave instead of colliding. The last
 * drum then lands under the active chip lighting up, which is the "and it moves first" beat.
 */
export const CHIP_DELAY_MS = 400;
export const CHIP_STAGGER_MS = 180;
export const CHIP_IN_MS = 260;

/**
 * When the surrounding chrome — the round counter, the movement budget, the end-turn button —
 * fades up, once the order has finished arriving.
 *
 * <p>Sits just behind the heavy third drum at 720ms so it rides the tail of that transient
 * rather than landing in the gap after it. The chips themselves arrive already highlighted; an
 * earlier cut lit the active one as a separate beat here, which needed a delayed keyframe
 * duplicating the active colours in CSS and bought a distinction nobody was waiting for.
 */
export const CHROME_DELAY_MS = 760;
export const CHROME_IN_MS = 320;

/**
 * How long everything else waits. Narration, the goblin's first turn and its consequences all
 * queue behind this — see the gate in the store.
 */
export const CEREMONY_MS = 1200;

/** The bar dissolving when the fight ends. Nothing is held behind this one: the aftermath is
 *  the payoff for the kill, and making it wait on a HUD fade would be exactly backwards. */
export const CLOSE_MS = 400;

/** When chip `index` lands, in milliseconds from the mode flip. */
export function chipDelay(index: number): number {
  return CHIP_DELAY_MS + index * CHIP_STAGGER_MS;
}
