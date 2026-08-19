import { useEffect } from "react";
import { useGame } from "./store";
import { connect } from "./ws";

/**
 * T2 debug view: prove that server-pushed state reaches the browser and lands in the store.
 * The Three.js canvas replaces the JSON dump in T3.
 */
export function App() {
  const connected = useGame((s) => s.connected);
  const demoMode = useGame((s) => s.demoMode);
  const scene = useGame((s) => s.scene);
  const mode = useGame((s) => s.mode);
  const transcript = useGame((s) => s.transcript);
  const rolls = useGame((s) => s.rolls);
  const error = useGame((s) => s.error);

  useEffect(() => {
    connect();
  }, []);

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <strong>Emberdelve</strong>
        <span style={{ ...styles.pill, background: connected ? "#1f6f3f" : "#7a2020" }}>
          {connected ? "connected" : "disconnected"}
        </span>
        <span style={styles.pill}>{mode}</span>
        {demoMode && <span style={{ ...styles.pill, background: "#6b4a12" }}>demo dice</span>}
      </header>

      {error && <div style={styles.error}>{error}</div>}

      <section style={styles.section}>
        <h2 style={styles.h2}>scene</h2>
        <pre style={styles.pre}>
          {scene ? JSON.stringify(scene, null, 2) : "— no scene pushed yet —"}
        </pre>
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>transcript ({transcript.length})</h2>
        <pre style={styles.pre}>
          {transcript.length
            ? transcript.map((s) => `[${s.speakerId}] ${s.text}`).join("\n\n")
            : "— nothing narrated yet —"}
        </pre>
      </section>

      <section style={styles.section}>
        <h2 style={styles.h2}>rolls ({rolls.length})</h2>
        <pre style={styles.pre}>
          {rolls.length
            ? rolls
                .map(
                  (r) =>
                    `${r.request.purpose} ${r.request.dice}${
                      r.request.modifier >= 0 ? "+" : ""
                    }${r.request.modifier} → [${r.faces.join(", ")}] = ${r.total} ${r.outcome}`,
                )
                .join("\n")
            : "— no rolls yet —"}
        </pre>
      </section>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page: {
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    background: "#12100e",
    color: "#d8cfc2",
    minHeight: "100vh",
    margin: 0,
    padding: "1.5rem",
    fontSize: 13,
  },
  header: { display: "flex", gap: ".6rem", alignItems: "center", marginBottom: "1.2rem" },
  pill: {
    background: "#2a2521",
    borderRadius: 999,
    padding: ".15rem .6rem",
    fontSize: 11,
    letterSpacing: ".04em",
  },
  error: {
    background: "#4a1414",
    border: "1px solid #7a2020",
    padding: ".6rem .8rem",
    borderRadius: 4,
    marginBottom: "1rem",
  },
  section: { marginBottom: "1.4rem" },
  h2: { fontSize: 11, textTransform: "uppercase", letterSpacing: ".1em", opacity: 0.55, margin: "0 0 .4rem" },
  pre: {
    background: "#1a1714",
    border: "1px solid #2a2521",
    borderRadius: 4,
    padding: ".8rem",
    margin: 0,
    maxHeight: 340,
    overflow: "auto",
    whiteSpace: "pre-wrap",
  },
};
