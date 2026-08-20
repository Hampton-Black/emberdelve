/**
 * The voice seam.
 *
 * <p>Two implementations: {@link webSpeech}, the browser's own synthesiser, and
 * {@link elevenLabs}, which asks this project's server for audio. The key never reaches the
 * browser — the server holds it and the client just fetches bytes. Both satisfy the same
 * contract, so the ordered queue in `narration.ts` never learns which one is speaking.
 */

export interface SpokenLine {
  speakerId: string;
  text: string;
}

export interface VoiceBackend {
  readonly id: string;
  /**
   * Resolves when the line has finished, failed, or was cancelled — never rejects.
   * The queue awaits this, so a rejection would stall every line behind it.
   */
  speak(line: SpokenLine): Promise<void>;
  stop(): void;
  /**
   * Start preparing a line that is coming but is not being spoken yet. Optional, and a hint
   * rather than an instruction — a backend with nothing to prepare simply omits it.
   *
   * <p>Exists because a remote voice has a round trip in front of every line, and paying it only
   * once the queue arrives at that line puts the whole latency in the gap between two sentences.
   * The queue primes exactly one line ahead: far enough that synthesis finishes while the
   * previous line is still playing, near enough that an interrupted turn wastes at most one line
   * of a metered API.
   */
  prime?(line: SpokenLine): void;
}

/**
 * Casting. Kenney-style: a small closed table, not a voice picker.
 *
 * <p>`prefer` is matched by name prefix against whatever the machine has, so this degrades to
 * pitch and rate alone on a box without these voices rather than falling silent. The macOS names
 * are the good ones — Daniel is a measured British male, Ralph is croaky enough to be a goblin
 * without any pitch shifting at all.
 */
const CASTING: Record<string, { prefer: string[]; pitch: number; rate: number }> = {
  narrator: { prefer: ["Daniel", "Alex", "Google UK English Male"], pitch: 0.95, rate: 0.96 },
  goblin: { prefer: ["Ralph", "Bahh", "Trinoids"], pitch: 1.25, rate: 1.06 },
  fighter: { prefer: ["Reed", "Rocko", "Fred"], pitch: 1.0, rate: 1.0 },
};

const DEFAULT_CAST = { prefer: [] as string[], pitch: 1.0, rate: 1.0 };

export function webSpeech(): VoiceBackend {
  // Resolved lazily: getVoices() is empty until the engine has enumerated them, and on some
  // browsers that only happens after the first `voiceschanged`.
  let voices: SpeechSynthesisVoice[] = [];
  const refresh = () => {
    voices = speechSynthesis.getVoices();
  };
  refresh();
  speechSynthesis.addEventListener("voiceschanged", refresh);

  // Held so stop() can settle the in-flight line. Without this, cancelling mid-sentence means
  // onend never fires and the queue waits forever.
  let settle: (() => void) | null = null;

  const pick = (speakerId: string): SpeechSynthesisVoice | undefined => {
    // getVoices() is empty for the first moments of a page's life and `voiceschanged` may
    // already have fired before this module existed. Cheap to re-ask than to lose the casting
    // on the session's first line.
    if (voices.length === 0) refresh();

    const cast = CASTING[speakerId] ?? DEFAULT_CAST;
    for (const name of cast.prefer) {
      const match = voices.find((v) => v.name.startsWith(name));
      if (match) return match;
    }
    return voices.find((v) => v.lang.startsWith("en"));
  };

  return {
    id: "web-speech",

    speak(line) {
      return new Promise<void>((resolve) => {
        if (!("speechSynthesis" in window)) return resolve();

        const cast = CASTING[line.speakerId] ?? DEFAULT_CAST;
        const utterance = new SpeechSynthesisUtterance(line.text);
        const voice = pick(line.speakerId);
        if (voice) utterance.voice = voice;
        utterance.pitch = cast.pitch;
        utterance.rate = cast.rate;

        let done = false;
        const finish = () => {
          if (done) return;
          done = true;
          settle = null;
          resolve();
        };

        settle = finish;
        utterance.onend = finish;
        // An error must resolve, not reject: a browser that refuses to speak should cost the
        // player silence, not a stuck turn.
        utterance.onerror = finish;

        speechSynthesis.speak(utterance);
      });
    },

    stop() {
      speechSynthesis.cancel();
      settle?.();
    },
  };
}

/** Where the audio comes from. Same origin the websocket uses; M0 is localhost-only. */
const SERVER = "http://localhost:7070";

/**
 * ElevenLabs Flash v2.5, by way of this project's own server.
 *
 * <p>Flash rather than a better-sounding model for two reasons, and they happen to agree: it is
 * the lowest-latency model they publish, which is what the 1.5s first-spoken-word budget needs,
 * and it bills at half a credit per character where the newer ones bill at one.
 *
 * <p>The audio arrives as an ordinary HTTP response and plays through an {@code <audio>} element
 * rather than the Web Audio graph the dice use. Playback can begin before the whole clip has
 * arrived, and {@code ended} is a reliable event — which the queue depends on, because the voice
 * is what paces the transcript.
 *
 * <p>Falls back per line rather than per session. One failed synthesis — a rate limit, a voice
 * the plan does not cover — costs the performance of a single sentence, not the DM's voice for
 * the rest of the game.
 */
export function elevenLabs(fallback: VoiceBackend): VoiceBackend {
  // Keyed by exactly what was asked for, so priming a line and then speaking it share one
  // request. The value is the in-flight promise rather than the result, so a `speak` that
  // arrives mid-flight joins the request already running instead of starting a second one and
  // paying for the same sentence twice.
  const pending = new Map<string, Promise<string | null>>();

  let current: HTMLAudioElement | null = null;
  let settle: (() => void) | null = null;

  const keyOf = (line: SpokenLine) => `${line.speakerId}\u0000${line.text}`;

  const fetchAudio = (line: SpokenLine): Promise<string | null> => {
    const key = keyOf(line);
    const existing = pending.get(key);
    if (existing) return existing;

    const request = fetch(`${SERVER}/tts`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(line),
    })
      .then(async (response) => {
        // 502 is the server saying the voice refused; anything else non-OK is the server itself
        // being unreachable. Both mean the same thing here — use the other voice.
        if (!response.ok) return null;
        return URL.createObjectURL(await response.blob());
      })
      .catch(() => null);

    pending.set(key, request);
    return request;
  };

  return {
    id: "elevenlabs",

    prime(line) {
      void fetchAudio(line);
    },

    async speak(line) {
      const url = await fetchAudio(line);
      pending.delete(keyOf(line));

      // Nothing to play: the browser reads it instead, so a failed voice is a worse voice rather
      // than a silent turn.
      if (url === null) return fallback.speak(line);

      return new Promise<void>((resolve) => {
        const audio = new Audio(url);
        current = audio;

        let done = false;
        const finish = () => {
          if (done) return;
          done = true;
          settle = null;
          if (current === audio) current = null;
          // This blob belongs to this line alone and nothing ever replays it.
          URL.revokeObjectURL(url);
          resolve();
        };

        settle = finish;
        audio.onended = finish;
        // Resolves rather than rejects, exactly as webSpeech does: the queue awaits this, and a
        // rejection would stall every line behind it.
        audio.onerror = finish;
        void audio.play().catch(finish);
      });
    },

    stop() {
      current?.pause();
      current = null;
      settle?.();
      fallback.stop();
    },
  };
}
