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

/** Kenney's packs, grouped by the moment each clip belongs to rather than by which pack it came from. */
const FAMILIES = {
  /** The rattle in the hand, before the throw. */
  shake: ["dice/dice-shake-1", "dice/dice-shake-2", "dice/dice-shake-3"],
  /** The toss itself — the whole handful leaving the hand. */
  throw: ["dice/dice-throw-1", "dice/dice-throw-2", "dice/dice-throw-3"],
  /** One die hitting the table. Fires once per die, staggered. */
  land: ["dice/die-throw-1", "dice/die-throw-2", "dice/die-throw-3", "dice/die-throw-4"],
  /** A blade moving. Plays on every swing, hit or miss — the sound of the attempt. */
  swing: ["rpg/knifeSlice", "rpg/knifeSlice2"],
  /** Steel arriving on something. Plays only when the attack connects. */
  impact: ["rpg/metalClick", "rpg/metalLatch"],
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
  source.start();

  if (options.duration !== undefined) {
    // Ramped, not cut: stopping a sample mid-waveform is an audible click.
    const end = ctx.currentTime + options.duration;
    gain.gain.setValueAtTime(gain.gain.value, Math.max(end - 0.06, ctx.currentTime));
    gain.gain.linearRampToValueAtTime(0.0001, end);
    source.stop(end);
  }
}
