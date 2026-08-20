import { useGame } from "../store";

/**
 * The end of the session.
 *
 * <p>M0 has no save and no restart (§12) — the way back is to restart the process — so this is
 * deliberately an ending rather than a menu. It exists because the alternative is worse: with
 * the input box refusing text and the board refusing clicks, a dead fighter looks exactly like
 * a bug unless something says so.
 *
 * <p>Fades in rather than mounting, and never unmounts, for the same reason {@link Title} does:
 * one less thing that can re-render the canvas out from under the renderer (invariant #4).
 */
export function Defeat() {
  const scene = useGame((s) => s.scene);
  const player = scene?.entities.find((e) => e.isPlayerControlled);
  const down = player !== undefined && player.hp <= 0;

  return (
    <div style={{ ...styles.veil, opacity: down ? 1 : 0 }}>
      <div style={styles.plate}>
        <p style={styles.line}>The crypt keeps him.</p>
        <p style={styles.hint}>Restart the server to descend again</p>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  veil: {
    position: "absolute",
    inset: 0,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    // Darker and flatter than the title's veil. That one lets the braziers through because the
    // room is about to open up; this one is closing it down.
    background:
      "radial-gradient(ellipse at 50% 55%, rgba(6,5,8,.72) 0%, rgba(4,3,6,.95) 60%, #030205 100%)",
    // Slow, and slower than anything else on screen. The death animation and its narration are
    // still running underneath, and this must not arrive before they finish.
    transition: "opacity 2.4s ease 1.2s",
    // Never takes clicks. The board beneath is already refusing them, and a veil that swallowed
    // them would hide that the refusal is deliberate.
    pointerEvents: "none",
    zIndex: 15,
  },
  plate: { textAlign: "center", padding: "2rem" },
  line: {
    margin: 0,
    fontFamily: "Georgia, 'Iowan Old Style', serif",
    fontSize: 19,
    fontStyle: "italic",
    letterSpacing: ".02em",
    color: "#8f8578",
  },
  hint: {
    margin: "1.6rem 0 0",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 9,
    letterSpacing: ".14em",
    textTransform: "uppercase",
    color: "#453f3a",
  },
};
