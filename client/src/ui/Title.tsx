import { unlock } from "../audio/sfx";
import { useGame } from "../store";
import { send } from "../ws";

/**
 * The moment before the game.
 *
 * <p>This exists for a mechanical reason as much as a theatrical one. Browsers refuse to play
 * audio until the player has interacted with the page, and the opening narration used to be
 * pushed the instant the websocket connected — so the DM described the room to a page that could
 * not make a sound, and the transcript, which is paced by the voice, drifted away from it.
 * Clicking through this screen is the gesture that unlocks audio, and the same click is what
 * asks the server to begin. The ordering is the point.
 *
 * <p>It also buys the sample bank time to decode. The first roll of a session used to lose its
 * rattle because the buffers were still being fetched when the cue fired.
 *
 * <p>Never unmounted — it fades and stops taking clicks. Unmounting it would be one more thing
 * that can re-render the canvas out from under the renderer (invariant #4).
 */
export function Title() {
  const connected = useGame((s) => s.connected);
  const started = useGame((s) => s.started);
  const setStarted = useGame((s) => s.setStarted);

  const begin = () => {
    // Inside the click, and first: this is the trusted gesture, and everything below depends
    // on the audio context existing by the time narration arrives.
    unlock();
    setStarted();
    send({ type: "begin" });
  };

  return (
    <div
      style={{
        ...styles.veil,
        opacity: started ? 0 : 1,
        pointerEvents: started ? "none" : "auto",
      }}
    >
      <div style={styles.plate}>
        <h1 style={styles.name}>EMBERDELVE</h1>
        <p style={styles.line}>
          Something has been working at the lid from the inside.
        </p>

        <button style={styles.enter} onClick={begin} disabled={!connected}>
          {connected ? "descend" : "waiting for the server…"}
        </button>

        <p style={styles.hint}>Sound on. The DM speaks aloud.</p>
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
    // Radial rather than flat: the room is already rendered and lit behind this, and letting the
    // braziers show through the middle makes descending feel like the lights coming up on a set
    // rather than like a loading screen being dismissed.
    background:
      "radial-gradient(ellipse at 50% 55%, rgba(13,12,18,.62) 0%, rgba(8,7,11,.93) 55%, #06060a 100%)",
    transition: "opacity .9s ease",
    zIndex: 20,
  },
  plate: { textAlign: "center", padding: "2rem" },
  name: {
    margin: 0,
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 34,
    letterSpacing: ".42em",
    // The tracking is asymmetric once letter-spacing is applied to the last glyph; this pulls
    // the word back onto its true centre.
    textIndent: ".42em",
    fontWeight: 400,
    color: "#e6d9c2",
    textShadow: "0 0 26px rgba(240,214,122,.22)",
  },
  line: {
    margin: "1.1rem 0 2.2rem",
    fontFamily: "Georgia, 'Iowan Old Style', serif",
    fontSize: 14,
    fontStyle: "italic",
    color: "#a99e8e",
  },
  enter: {
    background: "#1b1a23",
    color: "#e6d9c2",
    border: "1px solid #5c5240",
    borderRadius: 3,
    padding: ".62rem 2.4rem",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 11,
    letterSpacing: ".22em",
    textTransform: "uppercase",
    cursor: "pointer",
  },
  hint: {
    margin: "1.8rem 0 0",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 9,
    letterSpacing: ".14em",
    textTransform: "uppercase",
    color: "#4f4a44",
  },
};
