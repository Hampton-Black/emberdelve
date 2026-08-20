import { useEffect, useState } from "react";
import { isEnabled, setEnabled } from "./audio/narration";
import { unlock } from "./audio/sfx";
import { useGame } from "./store";
import { connect, send } from "./ws";
import { Canvas } from "./ui/Canvas";
import { CombatBar } from "./ui/CombatBar";
import { DiceTray } from "./ui/DiceTray";
import { InputBox } from "./ui/InputBox";
import { Transcript } from "./ui/Transcript";

export function App() {
  const connected = useGame((s) => s.connected);
  const demoMode = useGame((s) => s.demoMode);
  const scene = useGame((s) => s.scene);
  const mode = useGame((s) => s.mode);
  const error = useGame((s) => s.error);
  const setError = useGame((s) => s.setError);

  // UI preference, not game state (invariant #3) — same reasoning as the input box's draft.
  const [voice, setVoice] = useState(isEnabled());

  useEffect(() => {
    connect();

    // Browsers will not start an AudioContext without a gesture, and the first thing the player
    // does is click or type. Cheap to call repeatedly; it only does work once.
    const wake = () => unlock();
    window.addEventListener("pointerdown", wake);
    window.addEventListener("keydown", wake);
    return () => {
      window.removeEventListener("pointerdown", wake);
      window.removeEventListener("keydown", wake);
    };
  }, []);

  const party = scene?.entities.filter((e) => e.isPlayerControlled) ?? [];

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <strong style={{ letterSpacing: ".06em" }}>EMBERDELVE</strong>
        <span style={{ ...styles.pill, background: connected ? "#1f6f3f" : "#7a2020" }}>
          {connected ? "connected" : "disconnected"}
        </span>
        <span style={styles.pill}>{mode}</span>
        {demoMode && <span style={{ ...styles.pill, background: "#6b4a12" }}>demo dice</span>}

        <button
          style={{ ...styles.pill, ...styles.toggle, opacity: voice ? 1 : 0.45 }}
          onClick={() => {
            setEnabled(!voice);
            setVoice(!voice);
          }}
          title="Speak the narration aloud"
        >
          {voice ? "voice on" : "voice off"}
        </button>

        <span style={{ flex: 1 }} />

        {/* HP and AC as text is sufficient — no character sheet UI (§12). */}
        {party.map((member) => (
          <span key={member.id} style={styles.pill}>
            {member.name} {member.hp}/{member.maxHp} hp
          </span>
        ))}
      </header>

      {error && (
        <div style={styles.error} onClick={() => setError(null)} title="click to dismiss">
          {error}
        </div>
      )}

      <main style={styles.body}>
        <section style={styles.stage}>
          <div style={styles.viewport}>
            <Canvas />
            <CombatBar />
            <DiceTray />
          </div>
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
            {/* Exercises the walk cycle and facing without waiting for T10's click-to-move. */}
            <button
              style={styles.button}
              onClick={() => {
                const me = scene?.entities.find((e) => e.isPlayerControlled);
                if (me) send({ type: "moveTo", actorId: me.id, x: me.x, y: me.y >= 6 ? 1 : 9 });
              }}
            >
              walk
            </button>
            {/* Combat with no model in the path, for the same reason `roll d20` exists. */}
            <button
              style={styles.button}
              onClick={() => send({ type: "debugStartCombat" } as never)}
            >
              start combat
            </button>
            {/* A real roll down the real path — how the dice get tuned without burning a turn. */}
            <button
              style={styles.button}
              onClick={() =>
                send({ type: "debugRoll", actorId: "fighter", skill: "PERCEPTION",
                       difficulty: "MEDIUM" } as never)
              }
            >
              roll d20
            </button>
          </footer>
        </section>

        <aside style={styles.sidebar}>
          <Transcript />
          <InputBox />
        </aside>
      </main>
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
  toggle: {
    border: "1px solid #34313d",
    color: "#d8cfc2",
    fontFamily: "inherit",
    cursor: "pointer",
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
    cursor: "pointer",
  },
  body: { flex: 1, minHeight: 0, display: "flex" },
  stage: { flex: 1, minWidth: 0, display: "flex", flexDirection: "column" },
  viewport: { flex: 1, minHeight: 0, position: "relative" },
  sidebar: {
    width: 380,
    flexShrink: 0,
    borderLeft: "1px solid #23212b",
    display: "flex",
    flexDirection: "column",
    background: "#0a0910",
  },
  debug: {
    display: "flex",
    gap: ".4rem",
    alignItems: "center",
    padding: ".45rem .9rem",
    borderTop: "1px solid #23212b",
  },
  debugLabel: { fontSize: 10, opacity: 0.4, letterSpacing: ".1em", marginRight: ".3rem" },
  button: {
    background: "#23212b",
    color: "#d8cfc2",
    border: "1px solid #34313d",
    borderRadius: 3,
    padding: ".26rem .7rem",
    fontFamily: "inherit",
    fontSize: 10,
    cursor: "pointer",
  },
};
