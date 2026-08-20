import { useCallback, useEffect, useRef } from "react";
import { swing } from "../audio/combat";
import { Renderer } from "../scene/Renderer";
import { useGame } from "../store";
import { send } from "../ws";
import type { CombatView, SceneState, Square } from "../types";

/**
 * The Three.js boundary.
 *
 * <p>The renderer lives in a ref and is created exactly once (invariant #4). It subscribes to
 * the Zustand store directly rather than receiving props, so a React re-render can never
 * re-create the canvas — the failure mode invariant #3 exists to prevent.
 *
 * <p>Camera facing is deliberately <em>not</em> in the store: it is view state, not game state.
 * The server neither knows nor cares which corner you are looking from.
 */
export function Canvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<Renderer | null>(null);

  const rotate = useCallback((direction: -1 | 1) => {
    rendererRef.current?.rotate(direction);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || rendererRef.current) return;

    const renderer = new Renderer(canvas);
    rendererRef.current = renderer;

    // Dev-only handle. The renderer is deliberately unreachable from React, which also makes it
    // unreachable from the console — and "is that token actually there, or just unlit?" is a
    // question that comes up constantly against a dark room.
    if (import.meta.env.DEV) {
      (window as unknown as { __renderer?: Renderer }).__renderer = renderer;
    }

    let unsubscribe = () => {};
    let cancelled = false;

    renderer.init().then(() => {
      if (cancelled) return;
      renderer.start();

      let known: SceneState | null = null;

      const apply = (scene: SceneState | null) => {
        if (!scene) return;

        // A different room, or the first scene: build everything.
        if (!known || known.roomId !== scene.roomId) {
          renderer.setScene(scene);
          known = scene;
          return;
        }
        reconcile(renderer, known, scene);
        known = scene;
      };

      // One listener for everything the canvas shows, so the renderer can only ever be a
      // function of the store — never of a second stream of events arriving on its own path.
      let shownCombat: CombatView | null | undefined;
      let shownStrike = 0;

      const sync = () => {
        const state = useGame.getState();
        apply(state.scene);

        const combat = state.scene?.combat ?? null;
        if (combat !== shownCombat) {
          renderer.setCombat(combat);
          shownCombat = combat;
        }

        // `at` is the event identity: two identical misses in a row are two swings.
        if (state.strike && state.strike.at !== shownStrike) {
          shownStrike = state.strike.at;
          renderer.strike(state.strike.actorId, state.strike.targetId);
          // Driven off the same notification as the animation, so the two cannot drift apart.
          swing(state.strike.connected);
        }
      };

      sync();
      unsubscribe = useGame.subscribe(sync);
    });

    const onResize = () => renderer.resize();
    window.addEventListener("resize", onResize);

    const onPointerMove = (event: PointerEvent) => {
      const move = intent(renderer, event);
      renderer.setHover(move?.square ?? null);
      canvas.style.cursor = move ? "pointer" : "default";
    };

    const onClick = (event: PointerEvent) => {
      const move = intent(renderer, event);
      if (!move) return;

      // Sent, never applied locally. The token does not budge until the server says it moved
      // (invariant #1) — which on localhost is the same frame, and in M1 will not be.
      send(
        move.kind === "attack"
          ? { type: "attack", actorId: move.actorId, targetId: move.targetId }
          : { type: "moveTo", actorId: move.actorId, x: move.square.x, y: move.square.y },
      );
    };

    canvas.addEventListener("pointermove", onPointerMove);
    canvas.addEventListener("click", onClick as EventListener);
    canvas.addEventListener("pointerleave", () => renderer.setHover(null));

    const onKey = (event: KeyboardEvent) => {
      // Never steal keys from the input box.
      if (event.target instanceof HTMLInputElement) return;

      if (event.key === "q" || event.key === "Q") renderer.rotate(-1);
      if (event.key === "e" || event.key === "E") renderer.rotate(1);
    };
    window.addEventListener("keydown", onKey);

    return () => {
      cancelled = true;
      unsubscribe();
      window.removeEventListener("resize", onResize);
      window.removeEventListener("keydown", onKey);
      canvas.removeEventListener("pointermove", onPointerMove);
      canvas.removeEventListener("click", onClick as EventListener);
      renderer.dispose();
      rendererRef.current = null;
    };
  }, []);

  return (
    <div style={styles.wrap}>
      <canvas ref={canvasRef} style={styles.canvas} />
      <div style={styles.controls}>
        <button style={styles.rotateButton} onClick={() => rotate(-1)} title="Rotate left (Q)">
          ⟲
        </button>
        <button style={styles.rotateButton} onClick={() => rotate(1)} title="Rotate right (E)">
          ⟳
        </button>
      </div>
    </div>
  );
}

/** What clicking here would do, or null if it would do nothing. */
type Intent =
  | { kind: "attack"; actorId: string; targetId: string; square: Square | null }
  | { kind: "move"; actorId: string; square: Square };

/**
 * Reads the pointer against the rules the server sent.
 *
 * <p>Every branch below tests membership in a list that arrived over the wire. There is no
 * distance check, no speed arithmetic and no reach rule anywhere in this file — asking whether
 * the square is in `legalMoves` is the entire client-side movement rule (invariant #1).
 */
function intent(renderer: Renderer, event: PointerEvent): Intent | null {
  const scene = useGame.getState().scene;
  if (!scene) return null;

  const { entityId, square } = renderer.pick(event.clientX, event.clientY);
  const combat = scene.combat;

  if (combat) {
    const actor = scene.entities.find((e) => e.id === combat.activeId);
    // The goblin's turn is not the player's to click through.
    if (!actor?.isPlayerControlled) return null;

    if (entityId && combat.legalTargets.includes(entityId)) {
      return { kind: "attack", actorId: actor.id, targetId: entityId, square };
    }
    if (square && combat.legalMoves.some((s) => s.x === square.x && s.y === square.y)) {
      return { kind: "move", actorId: actor.id, square };
    }
    return null;
  }

  // Out of combat there is no turn and nothing to spend, so anywhere on the floor will do.
  // The server still refuses squares with something solid on them.
  const player = scene.entities.find((e) => e.isPlayerControlled);
  if (!player || !square) return null;
  if (square.x === player.x && square.y === player.y) return null;
  return { kind: "move", actorId: player.id, square };
}

/**
 * Bring the canvas in line with the store.
 *
 * <p>The store is the single source of truth — the renderer reconciles against it rather than
 * listening to diffs on a second path, so the two can never drift out of step.
 */
function reconcile(renderer: Renderer, previous: SceneState, next: SceneState): void {
  const before = new Map(previous.entities.map((e) => [e.id, e]));
  const after = new Map(next.entities.map((e) => [e.id, e]));

  for (const [id, entity] of after) {
    const old = before.get(id);
    if (!old) {
      renderer.addEntity(entity);
    } else if (old.x !== entity.x || old.y !== entity.y) {
      renderer.moveEntity(id, entity.x, entity.y);
    }
    if (!old || old.hp !== entity.hp || old.maxHp !== entity.maxHp) {
      renderer.setHp(id, entity.hp, entity.maxHp);
    }
  }

  for (const id of before.keys()) {
    if (!after.has(id)) renderer.removeEntity(id);
  }

  const knownProps = new Set(previous.props.map((p) => p.id));
  for (const prop of next.props) {
    if (!knownProps.has(prop.id)) renderer.addProp(prop);
  }
}

const styles: Record<string, React.CSSProperties> = {
  wrap: { position: "relative", width: "100%", height: "100%" },
  canvas: { width: "100%", height: "100%", display: "block" },
  controls: {
    position: "absolute",
    right: ".7rem",
    bottom: ".7rem",
    display: "flex",
    gap: ".3rem",
  },
  rotateButton: {
    background: "rgba(35, 33, 43, .78)",
    color: "#d8cfc2",
    border: "1px solid #34313d",
    borderRadius: 3,
    width: 30,
    height: 26,
    fontSize: 14,
    lineHeight: 1,
    cursor: "pointer",
  },
};
