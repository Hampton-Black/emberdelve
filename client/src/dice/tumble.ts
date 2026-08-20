/**
 * The dice animation, as pure data.
 *
 * Nothing here draws, plays a sound, or touches the store — it turns "this roll, this many
 * milliseconds in" into positions, faces and opacities. Keeping it separate is what makes the
 * one rule below checkable by reading it.
 *
 * **The rule: the server decides, the animation displays.** Tumbling faces are decorative noise;
 * the moment a die settles it shows `result.faces[i]` and nothing else. Physics never produces a
 * value here (invariant #1, and design doc §7 "honest dice").
 */

import type { Outcome, RollResult } from "../types";

// ---- Layout, in tray units. Every number below is a design-space coordinate: the canvas
// context is scaled by DISPLAY_SCALE x devicePixelRatio, so the drawing code never has to know
// what resolution it landed on and the tray stays crisp on a Retina panel.

export const TRAY_WIDTH = 232;
export const TRAY_HEIGHT = 76;
/** Tray units to CSS pixels. */
export const DISPLAY_SCALE = 3;

const DIE_RADIUS = 19;
const DIE_GAP = 8;
const FIRST_DIE_X = 30;
const REST_Y = 38;

// ---- Timing

/** Dice are heard before they are seen: the rattle plays over an empty tray. */
export const WIND_UP_MS = 240;
const FLIGHT_MS = 820;
/** Dice landing together is a thud; landing apart is a clatter. */
const LAND_STAGGER_MS = 130;
const BOUNCE_MS = 280;
const FADE_IN_MS = 180;

export const FADE_OUT_MS = 420;
/** How long the tray waits for narration that never comes. */
export const HOLD_MS = 9000;

/**
 * The same wait, in combat.
 *
 * <p>Out of combat a roll is a question the DM is about to answer, so the tray holds until the
 * narration arrives. In combat nobody is going to say anything — the next thing that happens is
 * the next roll — and nine seconds of a tray covering a third of the board between swings is
 * how a fight stops feeling like a fight. Long enough to read, and then gone.
 */
export const COMBAT_HOLD_MS = 3000;

/**
 * The pause between a result becoming readable and the blow that follows it.
 *
 * Measured against {@link revealAt}, which is when the total first becomes legible — the readout
 * then takes another 260ms to wipe in. Without this the swing started on that same frame, so the
 * player was asked to read "HIT" and watch the hit at once, and got neither.
 *
 * Attacks only. A skill check has no animation waiting behind its result; its consequence is
 * narration, which takes seconds to arrive on its own.
 */
export const IMPACT_BEAT_MS = 650;

const LOB = 10;
const BOUNCE_HEIGHT = 12;
/** Whole turns, so the flight ease converges on upright rather than an arbitrary angle. */
const SPIN = Math.PI * 8;

/** When die `index` stops moving and commits to its face. */
export function landAt(index: number): number {
  return WIND_UP_MS + FLIGHT_MS + index * LAND_STAGGER_MS;
}

/**
 * When the total becomes legible. Narration is held until this moment — the dice decide, then
 * the DM speaks, never the other way round.
 */
export function revealAt(dieCount: number): number {
  return landAt(Math.max(0, dieCount - 1)) + BOUNCE_MS + 120;
}

// ---- Visual state

export interface DieVisual {
  x: number;
  y: number;
  rotation: number;
  face: number;
  sides: number;
  radius: number;
  /** The advantage/disadvantage die that was thrown away. Drawn dim; it counts for nothing. */
  discarded: boolean;
  settled: boolean;
}

export interface TrayVisual {
  dice: DieVisual[];
  /** 0–1, the readout wiping in. */
  reveal: number;
  opacity: number;
  /** Nothing left to draw; the caller can stop its frame loop. */
  finished: boolean;
}

/**
 * @param elapsed   milliseconds since the roll arrived
 * @param dismissAt milliseconds at which the fade-out starts
 */
export function sample(result: RollResult, elapsed: number, dismissAt: number): TrayVisual {
  const sides = diceSides(result.request.dice);
  const count = result.faces.length;
  const keepsOne = result.request.advantage !== "NORMAL";

  const dice: DieVisual[] = [];
  for (let i = 0; i < count; i++) {
    const flightMs = FLIGHT_MS + i * LAND_STAGGER_MS;
    const p = clamp01((elapsed - WIND_UP_MS) / flightMs);
    const settled = elapsed >= landAt(i);

    const restX = FIRST_DIE_X + i * (DIE_RADIUS * 2 + DIE_GAP);
    const startX = TRAY_WIDTH + DIE_RADIUS * 2;
    const startY = -DIE_RADIUS;

    const since = elapsed - landAt(i);
    const bouncing = since >= 0 && since < BOUNCE_MS;
    const b = bouncing ? since / BOUNCE_MS : 0;

    dice.push({
      x: startX + (restX - startX) * easeOutCubic(p),
      y:
        startY +
        (REST_Y - startY) * easeInQuad(p) -
        LOB * Math.sin(Math.PI * p) -
        (bouncing ? BOUNCE_HEIGHT * Math.abs(Math.sin(b * Math.PI * 2)) * (1 - b) : 0),
      rotation:
        SPIN * easeOutCubic(p) + (bouncing ? 0.22 * Math.sin(b * Math.PI * 3) * (1 - b) : 0),

      // The one line that matters. Before it settles the face is noise; after, it is the
      // server's value. There is no path by which the animation can invent a result.
      face: settled ? result.faces[i] : tumblingFace(i, elapsed, sides),

      sides,
      radius: DIE_RADIUS,
      // The server puts the kept die first, so every later die on an advantage roll is a discard.
      discarded: keepsOne && i > 0,
      settled,
    });
  }

  const fadeOut = elapsed <= dismissAt ? 1 : 1 - clamp01((elapsed - dismissAt) / FADE_OUT_MS);
  const opacity = Math.min(clamp01(elapsed / FADE_IN_MS), fadeOut);

  return {
    dice,
    reveal: clamp01((elapsed - revealAt(count)) / 260),
    opacity,
    finished: opacity <= 0,
  };
}

// ---- Pacing: which rolls get the full treatment

/**
 * "Do not animate every roll" (design doc §7). Six monsters attacking twice is twelve rolls; at
 * two seconds each that is twenty-four seconds of watching dice, and combat dies. Dramatic beats
 * only — the rest resolve straight into the log.
 *
 * Purpose decides, not who rolled. An incoming attack is the tensest die in the game and the
 * goblin throws it, so "animate the player's rolls" would gate out exactly the wrong ones.
 */
export function isDramatic(result: RollResult): boolean {
  switch (result.request.purpose) {
    // A batch, not a moment: initiative is every combatant at once and the tray throws one roll
    // at a time. The second throw would replace the first mid-flight. T12 owns that beat.
    case "INITIATIVE":
      return false;
    // The consequence, not the question. Animating to-hit and then damage makes one swing read
    // as two — and by the time damage is rolled the interesting thing has already happened.
    case "DAMAGE":
      return false;
    // Attacks, saves and skill checks: whoever is rolling, this is the moment in doubt.
    default:
      return true;
  }
}

// ---- Wording, shared by the tray and the transcript log so they cannot drift

export interface RollCaption {
  /** "PERCEPTION CHECK" */
  label: string;
  /** "DC 15", "AC 15", or "" */
  target: string;
  /** "17 +5 = 22" — the arithmetic in full, because hiding it makes the dice feel decorative. */
  arithmetic: string;
  outcome: string;
  tone: "crit" | "good" | "bad" | "fumble" | "neutral";
}

/** Shared by the tray and the transcript so a success is never two different greens. */
export const TONE_COLOR: Record<RollCaption["tone"], string> = {
  crit: "#f0d67a",
  good: "#9ec46a",
  bad: "#c0736a",
  fumble: "#c04a4a",
  neutral: "#a99e8e",
};

export function caption(result: RollResult): RollCaption {
  const { request } = result;
  const counted = request.advantage === "NORMAL" ? result.faces : result.faces.slice(0, 1);

  const skill = request.skill ? `${request.skill} CHECK` : null;
  const label =
    skill ??
    { ATTACK: "ATTACK", SAVE: "SAVING THROW", SKILL_CHECK: "SKILL CHECK", DAMAGE: "DAMAGE", INITIATIVE: "INITIATIVE" }[
      request.purpose
    ];

  const target =
    request.dc == null ? "" : `${request.purpose === "ATTACK" ? "AC" : "DC"} ${request.dc}`;

  const sum = counted.join(" + ");
  const modifier = request.modifier === 0 ? "" : ` ${signed(request.modifier)}`;
  const arithmetic =
    counted.length === 1 && request.modifier === 0
      ? `${sum}`
      : `${sum}${modifier} = ${result.total}`;

  // Damage and initiative are adjudicated as SUCCESS because every roll needs an outcome, but
  // printing that word next to "5 + 2 = 7" says nothing and reads as though it could have failed.
  const decided =
    request.purpose !== "DAMAGE" && request.purpose !== "INITIATIVE";

  return {
    label,
    target,
    arithmetic,
    outcome: decided ? pretty(result.outcome) : "",
    tone: decided ? toneOf(result.outcome) : "neutral",
  };
}

function pretty(outcome: Outcome): string {
  return outcome === "CRIT" ? "CRITICAL" : outcome === "CRIT_FAIL" ? "FUMBLE" : outcome;
}

function toneOf(outcome: Outcome): RollCaption["tone"] {
  switch (outcome) {
    case "CRIT":
      return "crit";
    case "CRIT_FAIL":
      return "fumble";
    case "HIT":
    case "SUCCESS":
      return "good";
    case "MISS":
    case "FAILURE":
      return "bad";
    default:
      return "neutral";
  }
}

// ---- Helpers

/** Presentation only — how many sides to draw. Adjudication never happens on this side. */
export function diceSides(expression: string): number {
  const match = /^\s*\d*\s*[dD]\s*(\d+)/.exec(expression);
  return match ? Number(match[1]) : 20;
}

function signed(n: number): string {
  return n < 0 ? `− ${Math.abs(n)}` : `+ ${n}`;
}

/**
 * A stable pseudo-random face for a given die at a given instant. Stable matters: derived from
 * the clock rather than `Math.random()` so a dropped frame does not make the dice stutter.
 */
function tumblingFace(index: number, elapsed: number, sides: number): number {
  const step = Math.floor(elapsed / 52); // ~19 changes a second: blurred, but still dice
  return 1 + (hash(index * 8191 + step) % sides);
}

function hash(n: number): number {
  let h = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  return (h ^ (h >>> 16)) >>> 0;
}

const clamp01 = (t: number) => (t < 0 ? 0 : t > 1 ? 1 : t);
const easeOutCubic = (t: number) => 1 - Math.pow(1 - t, 3);
const easeInQuad = (t: number) => t * t;
