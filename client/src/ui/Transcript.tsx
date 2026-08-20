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
  const scene = useGame((s) => s.scene);
  const bottomRef = useRef<HTMLDivElement>(null);

  // The server's names, not the VOICE table's: an entity that is on the board is the authority
  // on what it is called. VOICE still supplies the colour, so a creature reads the same whether
  // it is speaking or rolling.
  const names = new Map(scene?.entities.map((e) => [e.id, e.name]) ?? []);

  // Follow the stream. Narration lands a sentence at a time, so this runs often.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [transcript, awaitingDm]);

  return (
    <div style={styles.scroll}>
      {transcript.length === 0 && (
        <p style={styles.empty}>The crypt is quiet.</p>
      )}

      {transcript.map((entry, i) => {
        // Rolls sit in the same list as prose, so the log preserves the order things happened
        // in: the dice, then the narration that commits to them.
        if (entry.kind === "roll") {
          return <RollLine key={i} result={entry.result} who={names} />;
        }

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
function RollLine({ result, who }: { result: RollResult; who: ReadonlyMap<string, string> }) {
  const text = caption(result);
  const actorId = result.request.actorId;
  // Falls back to the id rather than going blank: an unattributed roll in a log is worse than
  // an ugly one, because the whole point of the line is who did it.
  const name = who.get(actorId) ?? VOICE[actorId]?.label ?? actorId;

  return (
    <div style={styles.roll}>
      <span style={{ ...styles.rollActor, color: VOICE[actorId]?.color ?? "#a99e8e" }}>
        {name}
      </span>
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
    // A grid, not wrapping flex: four columns means the log scans vertically — every actor in
    // one column, every total in another — and the longest label no longer knocks the outcome
    // onto its own line.
    display: "grid",
    gridTemplateColumns: "auto minmax(0, 1fr) auto auto",
    alignItems: "baseline",
    columnGap: ".5rem",
    padding: ".38rem .55rem",
    borderLeft: "2px solid #34313d",
    background: "#12111a",
    fontFamily: "ui-monospace, Menlo, monospace",
    fontSize: 10,
  },
  rollActor: {
    letterSpacing: ".08em",
    textTransform: "uppercase",
    fontWeight: 700,
    opacity: 0.9,
  },
  rollLabel: { letterSpacing: ".08em", opacity: 0.5 },
  rollMath: { color: "#d8cfc2", textAlign: "right", whiteSpace: "nowrap" },
  rollOutcome: { letterSpacing: ".08em", fontWeight: 700 },
  thinking: {
    margin: 0,
    opacity: 0.5,
    animation: "none",
    color: "#cdc3b4",
  },
};
