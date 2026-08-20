import { webSpeech, type SpokenLine, type VoiceBackend } from "./voice";

/**
 * The ordered audio queue.
 *
 * <p>Narration streams a sentence at a time, and two sentences talking over each other is the
 * single worst thing a spoken DM can do. Lines are spoken strictly in arrival order, one at a
 * time, and a new turn silences whatever is still queued from the last one.
 *
 * <p>Ordering with respect to the <em>dice</em> is not handled here — it is already handled, by
 * the gate in `store.ts`. This queue is fed from the gated path, so it inherits it.
 */

let backend: VoiceBackend | null = null;
let enabled = true;

const pending: SpokenLine[] = [];
let draining = false;

/**
 * Bumped by {@link silence}. An in-flight `speak` resolves after its generation is stale, and
 * the drain loop checks this before taking another line — which is how a cancelled turn stops
 * without leaving a half-spoken sentence in the pipe.
 */
let generation = 0;

export function setEnabled(on: boolean): void {
  enabled = on;
  if (!on) silence();
}

export function isEnabled(): boolean {
  return enabled;
}

export function speak(line: SpokenLine): void {
  if (!enabled || !line.text.trim()) return;
  pending.push(line);
  void drain();
}

/**
 * Drop everything still queued, letting the line already in the air finish.
 *
 * <p>Cutting a voice off mid-word is jarring in a way that cutting between sentences is not, and
 * the player starting their next turn is not an emergency. A person interrupted finishes their
 * sentence; so does this.
 */
export function silence(): void {
  generation++;
  pending.length = 0;
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
      const line = pending.shift()!;
      await backend.speak(line);
    }
  } finally {
    draining = false;
    // A silence() during the await leaves the next turn's lines queued behind a loop that has
    // already given up on them. Without this they never get spoken at all.
    if (pending.length > 0) void drain();
  }
}
