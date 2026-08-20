/**
 * A tiny sample bank for the dice.
 *
 * The design doc is blunt about this: "Audio carries most of the satisfaction. Good clatter with
 * 2D tumbling sprites beats silent physics-perfect d20s." So this is deliberately not a general
 * audio engine — it plays short clips with enough variation that they don't sound canned.
 *
 * Web Audio rather than <audio> elements because the clacks overlap, and an <audio> element that
 * is still playing cannot be retriggered.
 */

const BASE = "/assets/audio";

/**
 * Kenney's packs, grouped by the moment each clip belongs to rather than by which pack it came
 * from. Several families are the same samples at different speeds: `rate` is doing real work
 * here, and a metal hit dragged down to a third speed is a gong, not a clang.
 */
const FAMILIES = {
  /** The rattle in the hand, before the throw. */
  shake: ["dice/dice-shake-1", "dice/dice-shake-2", "dice/dice-shake-3"],
  /** Dice being picked up. */
  grab: ["dice/dice-grab-1", "dice/dice-grab-2"],
  /** The toss itself — the whole handful leaving the hand. */
  throw: ["dice/dice-throw-1", "dice/dice-throw-2", "dice/dice-throw-3"],
  /** One die hitting the table. Fires once per die, staggered. */
  land: ["dice/die-throw-1", "dice/die-throw-2", "dice/die-throw-3", "dice/die-throw-4"],
  /** A blade moving. Plays on every swing, hit or miss — the sound of the attempt. */
  swing: ["rpg/knifeSlice", "rpg/knifeSlice2"],
  /** Steel arriving on armour. Plays only when the attack connects. */
  impact: [
    "impact/impactMetal_medium_000",
    "impact/impactMetal_medium_001",
    "impact/impactMetal_medium_002",
    "impact/impactMetal_medium_003",
    "impact/impactMetal_medium_004",
  ],
  /** Only ever played far below speed, where it stops being metal and becomes a struck bell. */
  boom: [
    "impact/impactMetal_heavy_000",
    "impact/impactMetal_heavy_001",
    "impact/impactMetal_heavy_002",
    "impact/impactMetal_heavy_003",
    "impact/impactMetal_heavy_004",
  ],
  /** Something reaching the floor. Pitched down — the pack's generic hits are all light. */
  thud: [
    "impact/impactGeneric_light_000",
    "impact/impactGeneric_light_001",
    "impact/impactGeneric_light_002",
    "impact/impactGeneric_light_003",
    "impact/impactGeneric_light_004",
  ],
  /** A blade leaving its scabbard. The gesture that means a fight is starting. */
  steel: ["rpg/drawKnife1", "rpg/drawKnife2", "rpg/drawKnife3"],
  /** The blade arriving on something that is not armour. Layered under {@link FAMILIES.impact}. */
  chop: ["rpg/chop"],
  /** Ten of them, so a walk across the room never repeats a foot. */
  step: [
    "rpg/footstep00", "rpg/footstep01", "rpg/footstep02", "rpg/footstep03", "rpg/footstep04",
    "rpg/footstep05", "rpg/footstep06", "rpg/footstep07", "rpg/footstep08", "rpg/footstep09",
  ],
  /** Armour and clothing shifting. Never plays alone — it is the body under another sound. */
  cloth: ["rpg/cloth1", "rpg/cloth2", "rpg/cloth3", "rpg/cloth4"],
  /** Stone and old timber under load. */
  grind: ["rpg/creak1", "rpg/creak2", "rpg/creak3"],
  /** A catch giving way. */
  latch: ["rpg/metalLatch", "rpg/metalClick"],
} as const;

export type Family = keyof typeof FAMILIES;

let ctx: AudioContext | null = null;
let master: GainNode | null = null;
const buffers = new Map<string, AudioBuffer>();

/**
 * Browsers refuse to start an AudioContext without a user gesture, and a rejected context stays
 * dead rather than erroring — so this is called from real interactions, not from module load.
 * Idempotent; call it from anywhere a gesture happens.
 */
export function unlock(): void {
  if (!ctx) {
    ctx = new AudioContext();
    master = ctx.createGain();
    master.gain.value = 0.75;
    master.connect(ctx.destination);
    void preload(ctx);
  }
  if (ctx.state === "suspended") void ctx.resume();
}

async function preload(context: AudioContext): Promise<void> {
  const names = Object.values(FAMILIES).flat();
  await Promise.all(
    names.map(async (name) => {
      try {
        const response = await fetch(`${BASE}/${name}.ogg`);
        const bytes = await response.arrayBuffer();
        buffers.set(name, await context.decodeAudioData(bytes));
      } catch (error) {
        // A missing clip is a silent roll, not a broken turn.
        console.warn(`dice sfx '${name}' failed to load`, error);
      }
    }),
  );
}

export interface PlayOptions {
  /** 0–1, before the master gain. */
  gain?: number;
  /** Multiplies the random pitch jitter. Below 1 reads as heavier. */
  rate?: number;
  /**
   * Seconds, if the clip should be cut short. The rattle sample runs 1.5s, which would still be
   * going while the dice land — a die is shaken, then thrown, and the two must not overlap.
   */
  duration?: number;
  /**
   * Seconds to wait before starting. Scheduled on the audio clock rather than with a timer,
   * because the layers of a sting have to be tight and `setTimeout` is not.
   */
  delay?: number;
}

/** Plays one clip from the family. A no-op until {@link unlock} has run and decoding is done. */
export function play(family: Family, options: PlayOptions = {}): void {
  if (!ctx || !master) return;

  const names = FAMILIES[family];
  const buffer = buffers.get(names[Math.floor(Math.random() * names.length)]);
  if (!buffer) return; // still decoding — silence beats a stutter

  const source = ctx.createBufferSource();
  source.buffer = buffer;
  // The same sample replayed identically sounds canned by the third roll. This is the entire
  // trick behind clatter that stays pleasant over a session.
  source.playbackRate.value = (options.rate ?? 1) * (0.9 + Math.random() * 0.2);

  const gain = ctx.createGain();
  gain.gain.value = options.gain ?? 1;

  source.connect(gain).connect(master);
  const at = ctx.currentTime + (options.delay ?? 0);
  source.start(at);

  if (options.duration !== undefined) {
    // Ramped, not cut: stopping a sample mid-waveform is an audible click.
    const end = at + options.duration;
    gain.gain.setValueAtTime(gain.gain.value, Math.max(end - 0.06, ctx.currentTime));
    gain.gain.linearRampToValueAtTime(0.0001, end);
    source.stop(end);
  }
}

/**
 * White noise, built once. The beater on a drum is a burst of broadband noise a few milliseconds
 * long; without it a synthesised drum is a pure tone and reads as a boop rather than as a hit.
 */
let noise: AudioBuffer | null = null;

function noiseBuffer(context: AudioContext): AudioBuffer {
  if (!noise) {
    noise = context.createBuffer(1, Math.floor(context.sampleRate * 0.25), context.sampleRate);
    const channel = noise.getChannelData(0);
    for (let i = 0; i < channel.length; i++) channel[i] = Math.random() * 2 - 1;
  }
  return noise;
}

export interface DrumOptions {
  /** Where the strike starts, in Hz. Higher reads as a smaller drum. */
  pitch?: number;
  /** Where it settles. This is the note you actually hear. */
  floor?: number;
  gain?: number;
  /** How long the body rings out. */
  seconds?: number;
  delay?: number;
}

/**
 * A struck drum, synthesised.
 *
 * <p>The whole trick is that the pitch collapses in about fifty milliseconds and then holds: that
 * fast fall is what the ear reads as a skin being hit rather than as a tone being played. A short
 * filtered noise burst on top supplies the beater. There is no drum sample in any of the Kenney
 * packs, and synthesising one means the pitch is a number to tune rather than a file to go
 * looking for.
 */
export function drum(options: DrumOptions = {}): void {
  if (!ctx || !master) return;

  const start = ctx.currentTime + (options.delay ?? 0);
  const seconds = options.seconds ?? 0.85;
  const peak = options.gain ?? 0.9;
  const floor = options.floor ?? 46;

  const body = ctx.createOscillator();
  body.type = "sine";
  body.frequency.setValueAtTime(options.pitch ?? 190, start);
  body.frequency.exponentialRampToValueAtTime(floor, start + 0.055);

  const bodyGain = ctx.createGain();
  // Struck, not faded in: four milliseconds of attack, then a long exponential tail.
  bodyGain.gain.setValueAtTime(0.0001, start);
  bodyGain.gain.exponentialRampToValueAtTime(peak, start + 0.004);
  bodyGain.gain.exponentialRampToValueAtTime(0.0001, start + seconds);

  body.connect(bodyGain).connect(master);
  body.start(start);
  body.stop(start + seconds + 0.02);

  // The beater: noise through a low-pass, gone in thirty milliseconds. Audible as texture on the
  // front of the hit, never as a hiss.
  const hit = ctx.createBufferSource();
  hit.buffer = noiseBuffer(ctx);

  const tone = ctx.createBiquadFilter();
  tone.type = "lowpass";
  tone.frequency.value = 340;

  const hitGain = ctx.createGain();
  hitGain.gain.setValueAtTime(peak * 0.5, start);
  hitGain.gain.exponentialRampToValueAtTime(0.0001, start + 0.03);

  hit.connect(tone).connect(hitGain).connect(master);
  hit.start(start);
  hit.stop(start + 0.06);
}

export interface DropOptions {
  /** Starting frequency in Hz. */
  from: number;
  to: number;
  seconds: number;
  gain?: number;
  delay?: number;
}

/**
 * A synthesised sine falling in pitch: the weight underneath a sting.
 *
 * <p>Nothing in these packs is low enough or long enough to sit under an impact, and this is a
 * dozen lines of Web Audio rather than a hunt for a sample. It is also the part that actually
 * makes a stinger feel large — the metal on top is only the transient, and a transient with
 * nothing under it is a loud clang.
 */
export function drop(options: DropOptions): void {
  if (!ctx || !master) return;

  const start = ctx.currentTime + (options.delay ?? 0);
  const end = start + options.seconds;

  const osc = ctx.createOscillator();
  osc.type = "sine";
  osc.frequency.setValueAtTime(options.from, start);
  osc.frequency.exponentialRampToValueAtTime(options.to, end);

  // Exponential ramps cannot reach zero, hence the near-silence at both ends. Fast in and slow
  // out: an envelope that fades in reads as a hum arriving rather than as something being hit.
  const gain = ctx.createGain();
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(options.gain ?? 0.4, start + 0.04);
  gain.gain.exponentialRampToValueAtTime(0.0001, end);

  osc.connect(gain).connect(master);
  osc.start(start);
  osc.stop(end + 0.02);
}
