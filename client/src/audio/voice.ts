/**
 * The voice seam.
 *
 * <p>One implementation today ({@link webSpeech}), one planned: ElevenLabs Flash v2.5, which
 * synthesises <em>server-side</em> because the API key must never reach the browser. Both satisfy
 * the same contract, so the ordered queue in `narration.ts` never learns which is speaking.
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
