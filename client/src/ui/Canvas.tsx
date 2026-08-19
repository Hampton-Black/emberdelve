import { useCallback, useEffect, useRef } from "react";
import { Renderer } from "../scene/Renderer";
import { useGame } from "../store";
import type { SceneState } from "../types";

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

      apply(useGame.getState().scene);
      unsubscribe = useGame.subscribe((state) => apply(state.scene));
    });

    const onResize = () => renderer.resize();
    window.addEventListener("resize", onResize);

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
