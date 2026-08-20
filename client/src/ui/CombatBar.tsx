import { useGame } from "../store";
import { send } from "../ws";

/**
 * The combat HUD: who is up, what is left of their turn, and how to end it.
 *
 * <p>Reads the server's {@code CombatView} and displays it. Nothing here decides whose turn it
 * is or whether a button should work — {@code movementRemaining} and {@code actionAvailable}
 * arrive already decided, and this only draws them.
 *
 * <p>Mounted only during a fight, so it costs nothing in exploration and gives T12's transition
 * something to animate in.
 */
export function CombatBar() {
  const scene = useGame((s) => s.scene);
  const awaitingDm = useGame((s) => s.awaitingDm);
  const combat = scene?.combat ?? null;
  if (!combat || !scene) return null;

  const active = scene.entities.find((e) => e.id === combat.activeId);
  const yours = active?.isPlayerControlled ?? false;

  return (
    <div style={styles.bar}>
      <span style={styles.round}>round {combat.round}</span>

      <div style={styles.track}>
        {combat.order.map((combatant) => {
          const entity = scene.entities.find((e) => e.id === combatant.entityId);
          const down = entity !== undefined && entity.hp <= 0;
          const now = combatant.entityId === combat.activeId;

          return (
            <span
              key={combatant.entityId}
              style={{
                ...styles.chip,
                ...(now ? styles.chipActive : {}),
                opacity: down ? 0.3 : now ? 1 : 0.62,
                textDecoration: down ? "line-through" : "none",
              }}
            >
              {combatant.name}
              <span style={styles.initiative}>{combatant.initiative}</span>
            </span>
          );
        })}
      </div>

      <span style={{ flex: 1 }} />

      {yours ? (
        <>
          <span style={styles.budget}>
            {combat.movementRemaining} {combat.movementRemaining === 1 ? "square" : "squares"}
          </span>
          <span style={{ ...styles.budget, opacity: combat.actionAvailable ? 1 : 0.32 }}>
            {combat.actionAvailable ? "attack ready" : "attack spent"}
          </span>
          {/* Held while the DM is speaking. Handing the turn over mid-sentence would start an
              enemy turn whose narration the server then drops as a collision — the fight would
              carry on correctly and silently, which reads as the DM losing interest. */}
          <button
            style={{ ...styles.end, opacity: awaitingDm ? 0.35 : 1 }}
            disabled={awaitingDm}
            onClick={() => send({ type: "endTurn", actorId: combat.activeId })}
          >
            {awaitingDm ? "…" : "end turn"}
          </button>
        </>
      ) : (
        <span style={styles.waiting}>{active?.name ?? "Something"} is acting…</span>
      )}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  bar: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    display: "flex",
    alignItems: "center",
    gap: ".55rem",
    padding: ".5rem .8rem",
    background: "linear-gradient(180deg, rgba(13,12,18,.94) 0%, rgba(13,12,18,.72) 100%)",
    borderBottom: "1px solid #3a3444",
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
    fontSize: 10,
    letterSpacing: ".08em",
    color: "#d8cfc2",
    pointerEvents: "auto",
  },
  round: { opacity: 0.45, textTransform: "uppercase" },
  track: { display: "flex", gap: ".3rem", alignItems: "center" },
  chip: {
    display: "inline-flex",
    alignItems: "center",
    gap: ".38rem",
    background: "#1b1a23",
    border: "1px solid #2e2b38",
    borderRadius: 3,
    padding: ".2rem .5rem",
  },
  chipActive: {
    background: "#3b3320",
    border: "1px solid #7a6634",
    color: "#f0d67a",
  },
  initiative: { opacity: 0.5, fontSize: 9 },
  budget: { opacity: 0.7 },
  waiting: { opacity: 0.6, fontStyle: "italic" },
  end: {
    background: "#3b2320",
    color: "#e8c7b8",
    border: "1px solid #7a4034",
    borderRadius: 3,
    padding: ".28rem .8rem",
    fontFamily: "inherit",
    fontSize: 10,
    letterSpacing: ".08em",
    cursor: "pointer",
  },
};
