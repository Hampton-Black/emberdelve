import { webSpeech, type SpokenLine, type VoiceBackend } from "./voice";

/**
 * The ordered audio queue, and the clock the transcript runs on.
 *
 * <p>Narration streams a sentence at a time, and two sentences talking over each other is the
 * single worst thing a spoken DM can do. Lines are spoken strictly in arrival order, one at a
 * time, and a new turn drops whatever is still queued from the last one.
 *
 * <p>It also <b>paces the text</b>. The model streams a whole turn in about three seconds and the
 * voice takes twenty to say it, so text that appears on arrival has the player speed-reading
 * ahead of the narrator. Each line reveals itself as it starts being spoken, which keeps the two
 * together without anyone having to guess a words-per-minute number.
 *
 * <p>Ordering with respect to the <em>dice</em> is not handled here — it is already handled, by
 * the gate in `store.ts`. This queue is fed from the gated path, so it inherits it.
 */

/** A line to speak, or — when {@code line} is null — a marker that only runs its callback. */
interface Utterance {
  line: SpokenLine | null;
  /** Commits the line to the transcript. Runs the moment the voice reaches it. */
  reveal: () => void;
}

let backend: VoiceBackend | null = null;
let enabled = true;

const pending: Utterance[] = [];
let draining = false;

/**
 * Bumped by {@link silence}. The drain loop checks this before taking another line, which is how
 * a cancelled turn stops without leaving a half-spoken sentence in the pipe.
 */
let generation = 0;

export function setEnabled(on: boolean): void {
  enabled = on;
  if (!on) silence();
}

export function isEnabled(): boolean {
  return enabled;
}

/** Queue a line. `reveal` runs when the voice reaches it — or at once if voice is off. */
export function speak(line: SpokenLine, reveal: () => void): void {
  if (!enabled || !line.text.trim()) {
    reveal();
    return;
  }
  pending.push({ line, reveal });
  void drain();
}

/** Queue a callback with no speech, so it lands in sequence rather than ahead of the voice. */
export function mark(reveal: () => void): void {
  if (!enabled) {
    reveal();
    return;
  }
  pending.push({ line: null, reveal });
  void drain();
}

/**
 * Drop everything still queued, letting the line already in the air finish.
 *
 * <p>Cutting a voice off mid-word is jarring in a way that cutting between sentences is not, and
 * the player starting their next turn is not an emergency. A person interrupted finishes their
 * sentence; so does this.
 *
 * <p>Dropped lines are still revealed. The player interrupted the <em>speech</em>, not the
 * record — losing text from the transcript because nobody got round to saying it would be a bug.
 */
export function silence(): void {
  generation++;
  const dropped = pending.splice(0, pending.length);
  for (const utterance of dropped) utterance.reveal();
}

/** Stop dead, mid-word. For errors, where continuing to talk would be worse than the cut. */
export function silenceNow(): void {
  silence();
  backend?.stop();
}

async function drain(): Promise<void> {
  if (draining) return;
  draining = true;

  const mine = generation;
  backend ??= webSpeech();

  try {
    while (pending.length > 0 && mine === generation) {
      const utterance = pending.shift()!;
      utterance.reveal();
      if (utterance.line) await backend.speak(utterance.line);
    }
  } finally {
    draining = false;
    // A silence() during the await leaves the next turn's lines queued behind a loop that has
    // already given up on them. Without this they never get spoken at all.
    if (pending.length > 0) void drain();
  }
}
