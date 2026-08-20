import { elevenLabs, webSpeech, type SpokenLine, type VoiceBackend } from "./voice";

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
 * <p>It is also the clock for <b>everything else the DM does</b>. Narration lines, dice, hit
 * point changes and mode switches all queue here in arrival order, and each is released when the
 * voice reaches it. Before that, only the transcript was paced by the voice and the rest of the
 * world ran on timers — which was invisible while the browser's own synthesiser was reading, and
 * became obvious the moment a real voice was three times slower: the goblin was landing blows
 * twenty seconds before the narrator got round to saying it had appeared.
 */

/** A line to speak, or — when {@code line} is null — a marker that only runs its callback. */
interface Utterance {
  line: SpokenLine | null;
  /** Commits the line to the transcript. Runs the moment the voice reaches it. */
  reveal: () => void;
  /**
   * Extra time to hold the queue after {@link reveal}, for something that needs the floor but
   * makes no sound of its own — a die in the air. Applied whether or not the voice is on,
   * because the animation runs either way.
   */
  holdMs?: number;
}

let backend: VoiceBackend | null = null;
let enabled = true;

/**
 * Choose the voice, once, from what the server says it can do.
 *
 * <p>Called from the `hello` message rather than guessed at here: whether a real voice exists is
 * a fact about the server's configuration, and the browser has no way to know it. The browser's
 * own synthesiser is always built, because it is also the per-line fallback.
 */
export function useServerVoice(available: boolean): void {
  const browser = webSpeech();
  backend = available ? elevenLabs(browser) : browser;
}

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

/**
 * Queue a line. `reveal` runs when the voice reaches it.
 *
 * <p>An empty line is a marker, not silence to sit through.
 */
export function speak(line: SpokenLine, reveal: () => void): void {
  if (!line.text.trim()) {
    mark(reveal);
    return;
  }
  pending.push({ line, reveal });
  void drain();
}

/**
 * Queue a callback with no speech, so it lands in sequence rather than ahead of the voice.
 *
 * <p>Deliberately still queued when the voice is off. The queue is the ordering, not the audio:
 * turning the narrator off must change how long things take, never what order they happen in.
 */
export function mark(reveal: () => void): void {
  pending.push({ line: null, reveal });
  void drain();
}

/**
 * Queue a callback and then hold the queue open for a while afterwards.
 *
 * <p>For a die: it takes the floor for as long as it is in the air, and the narration that
 * commits to its result must not arrive before it lands. The hold is a timer rather than a
 * signal from the tray, because if the tray never mounts the queue must still move on.
 */
export function hold(ms: number, reveal: () => void): void {
  pending.push({ line: null, reveal, holdMs: ms });
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

      // Start the next line's synthesis before waiting on this one, so a remote voice spends its
      // round trip during playback instead of in the gap after it. One ahead only — see
      // VoiceBackend.prime.
      const next = pending.find((queued) => queued.line !== null);
      if (next?.line) backend.prime?.(next.line);

      // `enabled` decides whether it is spoken, never whether it is queued: with the narrator
      // off, lines reveal as fast as they arrive and the order is exactly the same.
      if (utterance.line && enabled) await backend.speak(utterance.line);
      if (utterance.holdMs) await sleep(utterance.holdMs);
    }
  } finally {
    draining = false;
    // A silence() during the await leaves the next turn's lines queued behind a loop that has
    // already given up on them. Without this they never get spoken at all.
    if (pending.length > 0) void drain();
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
