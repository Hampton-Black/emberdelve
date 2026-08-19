import { useRef, useState } from "react";
import { useGame } from "../store";
import { send } from "../ws";

export function InputBox() {
  const connected = useGame((s) => s.connected);
  const awaitingDm = useGame((s) => s.awaitingDm);
  const scene = useGame((s) => s.scene);
  const sayAsPlayer = useGame((s) => s.sayAsPlayer);

  // Local UI state only — never game state (invariant #3).
  const [draft, setDraft] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const actorId = scene?.entities.find((e) => e.isPlayerControlled)?.id;
  const canSend = connected && !awaitingDm && !!actorId && draft.trim().length > 0;

  const submit = () => {
    if (!canSend || !actorId) return;
    const text = draft.trim();

    sayAsPlayer(text);
    send({ type: "freeText", actorId, text });
    setDraft("");
    inputRef.current?.focus();
  };

  return (
    <form
      style={styles.form}
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
        placeholder={awaitingDm ? "the DM is speaking…" : "I examine the sarcophagus."}
        disabled={!connected || awaitingDm}
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
  },
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
