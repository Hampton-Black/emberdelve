import {
  BAR_IN_DELAY_MS,
  BAR_IN_MS,
  CHIP_IN_MS,
  CHROME_DELAY_MS,
  CHROME_IN_MS,
  CLOSE_MS,
  chipDelay,
} from "../combat/opening";
import { useGame } from "../store";
import { send } from "../ws";

/**
 * The combat HUD: who is up, what is left of their turn, and how to end it.
 *
 * <p>Reads the server's {@code CombatView} and displays it. Nothing here decides whose turn it
 * is or whether a button should work — {@code movementRemaining} and {@code actionAvailable}
 * arrive already decided, and this only draws them.
 *
 * <p>It is also the fight's opening ceremony (T12). The chips arrive one at a time, highest
 * initiative first, each carrying the d20 that put it there — so the player watches the order
 * being established rather than being handed it. An earlier version had them fly in unsorted
 * and then re-sort into place; tweening a flex reorder needs measured widths and a FLIP pass,
 * and it reads worse besides. Revealing them in order is the more legible beat.
 *
 * <p>Everything is timed from {@code openedAt} through CSS delays rather than from a frame loop:
 * the bar owns no animation state, holds no React state, and re-renders only when the fight
 * actually changes (invariant #3).
 *
 * <p>Like {@link Title} it fades rather than unmounting. The fight is over well before the bar
 * has finished dissolving, and a component that disappears the instant its data does cannot
 * show the end of anything.
 */
export function CombatBar() {
  const scene = useGame((s) => s.scene);
  const beat = useGame((s) => s.combatBeat);
  const awaitingDm = useGame((s) => s.awaitingDm);
  if (!beat || !scene) return null;

  const combat = beat.view;
  const closing = beat.closingAt !== null;
  const active = scene.entities.find((e) => e.id === combat.activeId);
  const yours = (active?.isPlayerControlled ?? false) && !closing;

  return (
    <div
      // Keyed on the fight, so a second one replays the ceremony from the top instead of
      // inheriting the first one's finished animations.
      key={beat.openedAt}
      style={{
        ...styles.bar,
        ...(closing
          ? { opacity: 0, transform: "translateY(-100%)", pointerEvents: "none" }
          : { animation: `combatBarIn ${BAR_IN_MS}ms ease-out ${BAR_IN_DELAY_MS}ms both` }),
      }}
    >
      <span style={{ ...styles.round, ...settleIn(closing) }}>round {combat.round}</span>

      <div style={styles.track}>
        {combat.order.map((combatant, index) => {
          const entity = scene.entities.find((e) => e.id === combatant.entityId);
          const down = entity !== undefined && entity.hp <= 0;
          const now = combatant.entityId === combat.activeId;

          return (
            <span
              key={combatant.entityId}
              style={
                closing
                  ? styles.arrival
                  : {
                      ...styles.arrival,
                      animation:
                        `chipIn ${CHIP_IN_MS}ms cubic-bezier(.2,.9,.3,1.2) ` +
                        `${chipDelay(index)}ms both`,
                    }
              }
            >
              <span
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
            </span>
          );
        })}
      </div>

      <span style={{ flex: 1 }} />

      <div style={{ ...styles.controls, ...settleIn(closing) }}>
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
          <span style={styles.waiting}>
            {closing ? "the room is still again" : `${active?.name ?? "Something"} is acting…`}
          </span>
        )}
      </div>
    </div>
  );
}

/** Fades a piece of chrome up once the order has finished arriving. */
function settleIn(closing: boolean): React.CSSProperties {
  return closing
    ? {}
    : { animation: `combatSettle ${CHROME_IN_MS}ms ease-out ${CHROME_DELAY_MS}ms both` };
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
    // Only the exit is a transition; the entrance is keyframed, because it has to run on mount.
    transition: `opacity ${CLOSE_MS}ms ease-in, transform ${CLOSE_MS}ms ease-in`,
  },
  round: { opacity: 0.45, textTransform: "uppercase" },
  track: { display: "flex", gap: ".3rem", alignItems: "center" },
  /** Owns the entrance only, so the chip inside it keeps its own resting opacity. */
  arrival: { display: "inline-flex" },
  controls: { display: "flex", alignItems: "center", gap: ".55rem" },
  chip: {
    display: "inline-flex",
    alignItems: "center",
    gap: ".38rem",
    background: "#1b1a23",
    border: "1px solid #2e2b38",
    borderRadius: 3,
    padding: ".2rem .5rem",
    // The turn passing from one combatant to the next, rather than the highlight snapping over.
    transition:
      "background 260ms ease-out, border-color 260ms ease-out, color 260ms ease-out," +
      " opacity 260ms ease-out",
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
