import { useGame } from "../store";
import { restart } from "../ws";

/**
 * The end of the session.
 *
 * <p>M0 has no save (§12) and nothing here loads one: the button throws the session away and
 * lays the room out again, which is what restarting the process did, without the terminal. It
 * earns its place because the goblin is genuinely dangerous — a playtest that ends in death
 * should not also end in a rebuild.
 *
 * <p>It also exists because the alternative is worse: with the input box refusing text and the
 * board refusing clicks, a dead fighter looks exactly like a bug unless something says so.
 *
 * <p>Fades in rather than mounting, and never unmounts, for the same reason {@link Title} does:
 * one less thing that can re-render the canvas out from under the renderer (invariant #4).
 */
export function Defeat() {
  const scene = useGame((s) => s.scene);
  const player = scene?.entities.find((e) => e.isPlayerControlled);
  const down = player !== undefined && player.hp <= 0;

  return (
    <div
      style={{
        ...styles.veil,
        opacity: down ? 1 : 0,
        // Hidden means gone, not merely transparent — an invisible button over the board would
        // still take the click meant for a square.
        visibility: down ? "visible" : "hidden",
      }}
    >
      <div style={styles.plate}>
        <p style={styles.line}>The crypt keeps him.</p>
        <button style={styles.again} onClick={restart}>
          descend again
        </button>
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
    // The veil itself never takes clicks — the board beneath is already refusing them, and one
    // that swallowed them would hide that the refusal is deliberate. The button re-enables them
    // for itself, and only once the veil is actually up.
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
  again: {
    marginTop: "1.9rem",
    background: "#141219",
    color: "#a99e8e",
    border: "1px solid #443c33",
    borderRadius: 3,
    padding: ".58rem 2.1rem",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 10,
    letterSpacing: ".2em",
    textTransform: "uppercase",
    cursor: "pointer",
    pointerEvents: "auto",
  },
};
