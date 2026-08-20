import { useEffect, useRef } from "react";
import { play } from "../audio/sfx";
import {
  caption,
  HOLD_MS,
  landAt,
  DISPLAY_SCALE,
  revealAt,
  sample,
  TRAY_HEIGHT,
  TONE_COLOR,
  TRAY_WIDTH,
  WIND_UP_MS,
  type DieVisual,
  type RollCaption,
  type TrayVisual,
} from "../dice/tumble";
import { useGame } from "../store";

/**
 * The dice, thrown over the scene.
 *
 * Laid out in a 232x76 design space and drawn at full device resolution. It deliberately does
 * not wear the scene's pixelation: at this size the readout is mostly text, and upscaled 8px
 * monospace is unreadable mush.
 *
 * Like {@link Canvas} this never re-renders: it reads the store inside a frame loop and owns no
 * React state (invariant #3). Animation state that React re-rendered would stutter.
 */
export function DiceTray() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    // Backing store at full device resolution; the context is then scaled so every draw call
    // below stays in tray units. Drawing small and upscaling is what made the text mushy.
    const density = DISPLAY_SCALE * (window.devicePixelRatio || 1);
    canvas.width = Math.round(TRAY_WIDTH * density);
    canvas.height = Math.round(TRAY_HEIGHT * density);

    const g = canvas.getContext("2d");
    if (!g) return;
    g.scale(density, density);

    let frame = 0;
    // Which roll we are currently throwing, and which of its sounds have already fired.
    let showing: object | null = null;
    let cues = new Set<string>();

    const tick = () => {
      frame = requestAnimationFrame(tick);

      const { activeRoll, diceDismissAt } = useGame.getState();
      if (!activeRoll) {
        g.clearRect(0, 0, TRAY_WIDTH, TRAY_HEIGHT);
        return;
      }

      if (activeRoll !== showing) {
        showing = activeRoll;
        cues = new Set();
      }

      const { result, startedAt } = activeRoll;
      const elapsed = performance.now() - startedAt;

      // Narration is the tray's cue to leave; if none arrives, it leaves on its own.
      // Never before the total is readable, whatever the server does.
      const dismissAt =
        diceDismissAt === null
          ? HOLD_MS
          : Math.max(diceDismissAt - startedAt, revealAt(result.faces.length) + 250);

      const visual = sample(result, elapsed, dismissAt);
      const text = caption(result);
      fireCues(elapsed, visual, text.tone === "crit" || text.tone === "fumble", cues);

      g.clearRect(0, 0, TRAY_WIDTH, TRAY_HEIGHT);
      if (!visual.finished) draw(g, visual, text);
    };

    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, []);

  return <canvas ref={canvasRef} style={styles.tray} />;
}

// ---- Sound ----

/**
 * The rattle, the toss, then one clack per die. The stagger between clacks is the whole
 * difference between a clatter and a thud — see LAND_STAGGER_MS.
 */
function fireCues(
  elapsed: number,
  visual: TrayVisual,
  emphatic: boolean,
  fired: Set<string>,
): void {
  const once = (key: string, at: number, sound: () => void) => {
    if (elapsed < at || fired.has(key)) return;
    fired.add(key);
    // A cue whose moment passed while the tab was hidden is skipped, not replayed late.
    if (elapsed < at + 400) sound();
  };

  once("shake", 0, () => play("shake", { gain: 0.55, duration: WIND_UP_MS / 1000 }));
  once("throw", WIND_UP_MS, () => play("throw", { gain: 0.8 }));

  visual.dice.forEach((die, i) =>
    once(`land-${i}`, landAt(i), () =>
      // The discarded advantage die is a quieter, higher tick — present, but not the story.
      play("land", die.discarded ? { gain: 0.3, rate: 1.15 } : { gain: 0.9 }),
    ),
  );

  // A crit or a fumble gets one extra, heavier hit under the reveal. Nothing else does.
  if (emphatic) {
    once("emphasis", revealAt(visual.dice.length), () =>
      play("throw", { gain: 0.85, rate: 0.6 }),
    );
  }
}

// ---- Drawing ----

const INK = {
  plate: "rgba(10, 9, 16, .82)",
  edge: "#34313d",
  text: "#d8cfc2",
  dim: "#8d8375",
  body: "#e3d8c4",
  bodyEdge: "#26232e",
  facet: "#cbbea6",
  number: "#1a1820",
  discardBody: "#4f4a44",
  discardNumber: "#2a2732",
};

const READOUT_X = 98;

function draw(g: CanvasRenderingContext2D, visual: TrayVisual, text: RollCaption): void {
  g.save();
  g.globalAlpha = visual.opacity;

  const emphatic = text.tone === "crit" || text.tone === "fumble";

  g.fillStyle = INK.plate;
  g.fillRect(0, 0, TRAY_WIDTH, TRAY_HEIGHT);
  g.strokeStyle = emphatic && visual.reveal > 0 ? TONE_COLOR[text.tone] : INK.edge;
  g.lineWidth = 0.6;
  g.strokeRect(0.3, 0.3, TRAY_WIDTH - 0.6, TRAY_HEIGHT - 0.6);

  for (const die of visual.dice) drawDie(g, die);

  g.textBaseline = "middle";
  g.textAlign = "left";

  // The stakes are legible from the first frame — you know what you are rolling and against
  // what while the die is still in the air. That is most of the tension.
  g.fillStyle = INK.dim;
  g.fillText(fitted(g, [text.label, text.target].filter(Boolean).join(" · ")), READOUT_X, 22);

  // The arithmetic and the verdict land with the dice, not before.
  g.save();
  g.globalAlpha = visual.opacity * visual.reveal;
  g.translate(0, (1 - visual.reveal) * 4);

  g.font = "11px Menlo, Monaco, monospace";
  g.fillStyle = INK.text;
  g.fillText(text.arithmetic, READOUT_X, 41);

  g.font = "bold 13px Menlo, Monaco, monospace";
  g.fillStyle = TONE_COLOR[text.tone];
  g.fillText(text.outcome, READOUT_X, 59);
  g.restore();

  g.restore();
}

/**
 * Sets the label font to the largest size that will not run off the plate, and returns the text.
 * "INVESTIGATION CHECK · DC 25" is nine characters longer than "STEALTH CHECK · DC 10", and a
 * clipped DC is worse than a small one.
 */
function fitted(g: CanvasRenderingContext2D, label: string): string {
  const available = TRAY_WIDTH - READOUT_X - 6;
  for (let size = 8; size > 6; size -= 0.5) {
    g.font = `${size}px Menlo, Monaco, monospace`;
    if (g.measureText(label).width <= available) break;
  }
  return label;
}

/** d20s read as a hexagon with the top face cut into it; other dice get a plain polygon. */
const OUTLINE: Record<number, { corners: number; tilt: number }> = {
  4: { corners: 3, tilt: -Math.PI / 2 },
  6: { corners: 4, tilt: Math.PI / 4 },
  8: { corners: 4, tilt: 0 },
  10: { corners: 6, tilt: 0 },
  12: { corners: 5, tilt: -Math.PI / 2 },
  20: { corners: 6, tilt: 0 },
};

function drawDie(g: CanvasRenderingContext2D, die: DieVisual): void {
  const shape = OUTLINE[die.sides] ?? OUTLINE[20];

  g.save();
  g.translate(die.x, die.y);
  g.rotate(die.rotation);
  if (die.discarded) g.globalAlpha *= 0.55;

  polygon(g, shape.corners, die.radius, shape.tilt);
  g.fillStyle = die.discarded ? INK.discardBody : INK.body;
  g.fill();
  g.strokeStyle = INK.bodyEdge;
  // Sub-unit weights are legible now that the tray draws at device resolution.
  g.lineWidth = 0.8;
  g.stroke();

  if (shape.corners === 6) {
    // The facet the number sits on.
    polygon(g, 3, die.radius * 0.72, -Math.PI / 2);
    g.fillStyle = die.discarded ? INK.discardBody : INK.facet;
    g.fill();
    g.lineWidth = 0.6;
    g.stroke();
  }

  g.fillStyle = die.discarded ? INK.discardNumber : INK.number;
  // Two digits need to be narrower than one: the facet is a triangle, so the room
  // available shrinks the further the text sits from the base.
  g.font = `bold ${die.face > 9 ? 14 : 17}px Menlo, Monaco, monospace`;
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.fillText(String(die.face), 0, shape.corners === 6 ? 3 : 1);

  g.restore();
}

function polygon(g: CanvasRenderingContext2D, corners: number, radius: number, tilt: number): void {
  g.beginPath();
  for (let i = 0; i < corners; i++) {
    const angle = tilt + (i * Math.PI * 2) / corners;
    const x = Math.cos(angle) * radius;
    const y = Math.sin(angle) * radius;
    if (i === 0) g.moveTo(x, y);
    else g.lineTo(x, y);
  }
  g.closePath();
}

const styles: Record<string, React.CSSProperties> = {
  tray: {
    position: "absolute",
    left: "50%",
    bottom: "1.4rem",
    transform: "translateX(-50%)",
    width: TRAY_WIDTH * DISPLAY_SCALE,
    maxWidth: "94%",
    // Not a fixed height: a narrow window clamps the width, and a fixed height would stretch
    // the dice into ovals rather than shrinking the tray.
    height: "auto",
    pointerEvents: "none",
  },
};
