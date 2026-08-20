import { useEffect, useRef } from "react";
import { caption, TONE_COLOR } from "../dice/tumble";
import { useGame } from "../store";
import type { RollResult } from "../types";

const VOICE: Record<string, { label: string; color: string; italic?: boolean }> = {
  narrator: { label: "", color: "#cdc3b4" },
  player: { label: "you", color: "#7fa6cc" },
  goblin: { label: "Vessk", color: "#9ec46a" },
  fighter: { label: "Roderick", color: "#c8b48a" },
};

export function Transcript() {
  const transcript = useGame((s) => s.transcript);
  const awaitingDm = useGame((s) => s.awaitingDm);
  const bottomRef = useRef<HTMLDivElement>(null);

  // Follow the stream. Narration lands a sentence at a time, so this runs often.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [transcript, awaitingDm]);

  return (
    <div style={styles.scroll}>
      {transcript.length === 0 && (
        <p style={styles.empty}>The crypt is quiet. Type something to begin.</p>
      )}

      {transcript.map((entry, i) => {
        // Rolls sit in the same list as prose, so the log preserves the order things happened
        // in: the dice, then the narration that commits to them.
        if (entry.kind === "roll") return <RollLine key={i} result={entry.result} />;

        const voice = VOICE[entry.speakerId] ?? { label: entry.speakerId, color: "#a99e8e" };
        const isPlayer = entry.speakerId === "player";

        return (
          <p
            key={i}
            style={{
              ...styles.line,
              color: voice.color,
              fontStyle: isPlayer ? "normal" : undefined,
              opacity: isPlayer ? 0.85 : 1,
            }}
          >
            {voice.label && <span style={styles.speaker}>{voice.label}&nbsp;&nbsp;</span>}
            {entry.text}
          </p>
        );
      })}

      {awaitingDm && <p style={styles.thinking}>▍</p>}
      <div ref={bottomRef} />
    </div>
  );
}

/** The dice log: every roll, including the ones too routine to be animated. */
function RollLine({ result }: { result: RollResult }) {
  const text = caption(result);

  return (
    <div style={styles.roll}>
      <span style={styles.rollLabel}>
        {[text.label, text.target].filter(Boolean).join("  ·  ")}
      </span>
      <span style={styles.rollMath}>{text.arithmetic}</span>
      <span style={{ ...styles.rollOutcome, color: TONE_COLOR[text.tone] }}>{text.outcome}</span>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  scroll: {
    flex: 1,
    minHeight: 0,
    overflowY: "auto",
    padding: "1rem 1.1rem",
    display: "flex",
    flexDirection: "column",
    gap: ".85rem",
  },
  empty: { opacity: 0.35, margin: 0, fontStyle: "italic" },
  line: {
    margin: 0,
    lineHeight: 1.65,
    fontSize: 13,
    fontFamily: "Georgia, 'Iowan Old Style', serif",
  },
  speaker: {
    fontFamily: "ui-monospace, Menlo, monospace",
    fontSize: 10,
    letterSpacing: ".08em",
    textTransform: "uppercase",
    opacity: 0.55,
  },
  roll: {
    display: "flex",
    flexWrap: "wrap",
    alignItems: "baseline",
    gap: ".5rem",
    padding: ".38rem .55rem",
    borderLeft: "2px solid #34313d",
    background: "#12111a",
    fontFamily: "ui-monospace, Menlo, monospace",
    fontSize: 10,
  },
  rollLabel: { letterSpacing: ".08em", opacity: 0.5 },
  rollMath: { color: "#d8cfc2", marginLeft: "auto" },
  rollOutcome: { letterSpacing: ".08em", fontWeight: 700 },
  thinking: {
    margin: 0,
    opacity: 0.5,
    animation: "none",
    color: "#cdc3b4",
  },
};
