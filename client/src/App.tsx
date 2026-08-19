import { useEffect } from "react";
import { useGame } from "./store";
import { connect, send } from "./ws";
import { Canvas } from "./ui/Canvas";

export function App() {
  const connected = useGame((s) => s.connected);
  const demoMode = useGame((s) => s.demoMode);
  const scene = useGame((s) => s.scene);
  const mode = useGame((s) => s.mode);
  const error = useGame((s) => s.error);

  useEffect(() => {
    connect();
  }, []);

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <strong style={{ letterSpacing: ".06em" }}>EMBERDELVE</strong>
        <span style={{ ...styles.pill, background: connected ? "#1f6f3f" : "#7a2020" }}>
          {connected ? "connected" : "disconnected"}
        </span>
        <span style={styles.pill}>{mode}</span>
        {demoMode && <span style={{ ...styles.pill, background: "#6b4a12" }}>demo dice</span>}
        <span style={{ flex: 1 }} />
        {scene && (
          <span style={{ ...styles.pill, background: "transparent", opacity: 0.5 }}>
            {scene.roomId} · {scene.width}×{scene.height} · {scene.entities.length} entities
          </span>
        )}
      </header>

      {error && <div style={styles.error}>{error}</div>}

      <main style={styles.stage}>
        <Canvas />
      </main>

      {/* T5 debug rig: proves diffs render with no model anywhere in the path. */}
      <footer style={styles.debug}>
        <span style={styles.debugLabel}>debug</span>
        <button style={styles.button} onClick={() => send({ type: "debugSpawnGoblin" } as never)}>
          spawn goblin
        </button>
        <button
          style={styles.button}
          onClick={() => send({ type: "debugReveal", propId: "alcove" } as never)}
        >
          reveal alcove
        </button>
        <button
          style={styles.button}
          onClick={() => {
            const fighter = scene?.entities.find((e) => e.isPlayerControlled);
            if (!fighter) return;
            send({
              type: "moveTo",
              actorId: fighter.id,
              x: Math.max(0, Math.min((scene?.width ?? 12) - 1, fighter.x + 1)),
              y: fighter.y,
            });
          }}
        >
          move east
        </button>
        <button
          style={styles.button}
          onClick={() => {
            const fighter = scene?.entities.find((e) => e.isPlayerControlled);
            if (!fighter) return;
            send({
              type: "moveTo",
              actorId: fighter.id,
              x: fighter.x,
              y: Math.max(0, Math.min((scene?.height ?? 12) - 1, fighter.y + 1)),
            });
          }}
        >
          move north
        </button>
      </footer>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page: {
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    background: "#0d0c12",
    color: "#d8cfc2",
    height: "100vh",
    display: "flex",
    flexDirection: "column",
    fontSize: 12,
  },
  header: {
    display: "flex",
    gap: ".5rem",
    alignItems: "center",
    padding: ".6rem .9rem",
    borderBottom: "1px solid #23212b",
  },
  pill: {
    background: "#23212b",
    borderRadius: 999,
    padding: ".15rem .6rem",
    fontSize: 10,
    letterSpacing: ".06em",
  },
  error: {
    background: "#4a1414",
    borderBottom: "1px solid #7a2020",
    padding: ".5rem .9rem",
  },
  stage: { flex: 1, minHeight: 0, position: "relative" },
  debug: {
    display: "flex",
    gap: ".4rem",
    alignItems: "center",
    padding: ".5rem .9rem",
    borderTop: "1px solid #23212b",
  },
  debugLabel: { fontSize: 10, opacity: 0.4, letterSpacing: ".1em", marginRight: ".3rem" },
  button: {
    background: "#23212b",
    color: "#d8cfc2",
    border: "1px solid #34313d",
    borderRadius: 3,
    padding: ".3rem .7rem",
    fontFamily: "inherit",
    fontSize: 11,
    cursor: "pointer",
  },
};
