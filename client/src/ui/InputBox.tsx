import { useRef, useState } from "react";
import { unlock } from "../audio/sfx";
import { useGame } from "../store";
import { send } from "../ws";

/**
 * Where the player talks to the DM.
 *
 * <p>Deliberately not locked during a fight. Combat has its own vocabulary — clicks on the board —
 * and the box steps back to make room for it, but it does not close: the question M0 exists to
 * answer is whether you want to type another thing, and a box that refuses you mid-fight has
 * answered it for you. Taunting a goblin is a legal move.
 */
export function InputBox() {
  const connected = useGame((s) => s.connected);
  const awaitingDm = useGame((s) => s.awaitingDm);
  const started = useGame((s) => s.started);
  const scene = useGame((s) => s.scene);
  const mode = useGame((s) => s.mode);
  const sayAsPlayer = useGame((s) => s.sayAsPlayer);

  // Local UI state only — never game state (invariant #3).
  const [draft, setDraft] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const actorId = scene?.entities.find((e) => e.isPlayerControlled)?.id;
  const canSend = connected && started && !awaitingDm && !!actorId && draft.trim().length > 0;
  const fighting = mode === "COMBAT";

  const submit = () => {
    if (!canSend || !actorId) return;
    const text = draft.trim();

    // The gesture immediately before the dice arrive — the last chance to have audio ready.
    unlock();
    sayAsPlayer(text);
    send({ type: "freeText", actorId, text });
    setDraft("");
    inputRef.current?.focus();
  };

  return (
    <form
      // Recedes during a fight rather than closing: dimmer, and asking for something a fight
      // would actually contain. The sidebar is the last place the mode change is felt.
      style={fighting ? { ...styles.form, ...styles.fighting } : styles.form}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <span style={styles.caret}>&gt;</span>
      <input
        ref={inputRef}
        style={styles.input}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={
          awaitingDm
            ? "the DM is speaking…"
            : fighting
              ? "shout, taunt, or look around…"
              : "I examine the sarcophagus."
        }
        disabled={!connected || !started || awaitingDm}
        autoFocus
      />
      <button type="submit" style={{ ...styles.button, opacity: canSend ? 1 : 0.35 }}>
        send
      </button>
    </form>
  );
}

const styles: Record<string, React.CSSProperties> = {
  form: {
    display: "flex",
    alignItems: "center",
    gap: ".55rem",
    padding: ".7rem .9rem",
    borderTop: "1px solid #23212b",
    background: "#100f15",
    transition: "opacity 500ms ease, background 500ms ease, border-color 500ms ease",
  },
  fighting: { background: "#0c0b11", borderTop: "1px solid #2a2333", opacity: 0.72 },
  caret: { opacity: 0.4, fontSize: 13 },
  input: {
    flex: 1,
    background: "transparent",
    border: "none",
    outline: "none",
    color: "#d8cfc2",
    fontFamily: "Georgia, 'Iowan Old Style', serif",
    fontSize: 13,
  },
  button: {
    background: "#23212b",
    color: "#d8cfc2",
    border: "1px solid #34313d",
    borderRadius: 3,
    padding: ".28rem .8rem",
    fontFamily: "ui-monospace, Menlo, monospace",
    fontSize: 10,
    letterSpacing: ".08em",
    cursor: "pointer",
  },
};
