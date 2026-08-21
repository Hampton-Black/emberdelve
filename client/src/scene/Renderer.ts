import * as THREE from "three";
import type {
  CombatView,
  EntityView,
  LightingPreset,
  Mode,
  Prop,
  SceneState,
  Square,
} from "../types";
import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer.js";
import { RenderPixelatedPass } from "three/examples/jsm/postprocessing/RenderPixelatedPass.js";
import { OutputPass } from "three/examples/jsm/postprocessing/OutputPass.js";
import {
  loadCharacter,
  loadKitPiece,
  loadPropModel,
  loadWallPiece,
  paletteOf,
  retintStone,
  toWorld,
  type KitPiece,
  type WallPiece,
} from "./assets";
import { buildProp, buildWallTorch, propModelPaths, FLAME_INTENSITY } from "./props";
import { characterPaths, moveSeconds, Token } from "./tokens";

/**
 * Horizontal resolution of the low-res render target. Everything is rendered at this width and
 * upscaled nearest-neighbour, which is what makes it read as pixel art rather than as soft 3D.
 */
const TARGET_WIDTH = 480;

/**
 * The camera snaps between four fixed isometric corners rather than orbiting freely.
 *
 * <p>Free orbit would break the look: the pixelation pass and the whole isometric read only hold
 * at the canonical elevation, and off-angle views make grid picking ambiguous. Ninety-degree
 * snapping is the tactical-RPG convention for exactly these reasons, and it solves the real
 * problem — props occluding tokens behind them.
 */
const ROTATION_STEP = Math.PI / 2;
/**
 * Deliberately slower than it was when the camera framed the whole room. A ninety-degree swing
 * throws the scene much further across a tight frame than a wide one, and the old 0.42s read as
 * a snap rather than as a turn once exploration moved in close.
 */
const ROTATION_SECONDS = 0.55;

/**
 * The two framings, as orthographic half-heights in world units.
 *
 * <p>Exploration sits close enough that the room is somewhere you are standing rather than a
 * board you are reading — roughly seven squares. Combat is the whole floor, because a tactical
 * decision you cannot see the inputs to is not a decision. The move between them is the point
 * of the mode transition: the pull-back *is* the announcement.
 */
const EXPLORATION_HALF_HEIGHT = 5.4;
const FRAMING_SECONDS = 1.1;

/**
 * How quickly the exploration camera catches up to the party — the time to close about 63% of
 * the remaining gap, applied per frame, so the follow is framerate-independent and never
 * overshoots. Loose enough that a single step does not yank the room.
 */
const FOLLOW_SECONDS = 0.34;

/** The world origin is the middle of the room; see the halving in {@link buildWalls}. */
const ROOM_CENTRE = new THREE.Vector3(0, 0, 0);

/**
 * How tall a wall stands, in squares. The ruins wall is authored at exactly this.
 *
 * <p>The combat framing raises the room's corners to this height so the walls are not cropped,
 * and it used to say 2 — twice what any wall in this game has ever been. It cost about a tenth
 * of the zoom in a landscape window, pulling the camera back to clear a parapet that was not
 * there.
 */
const WALL_UNIT = 1;

/** Breathing room around the room in the combat framing. */
const ROOM_MARGIN = 0.7;

/**
 * How far the exploration camera may push past the floor's edge to keep the party in shot.
 *
 * <p>Without it the frame must be filled with floor at all times, which sounds strict and is
 * really a rule that quietly cancels the follow: the wider the view, the less room the focus
 * has to move, and at the current exploration zoom the travel works out at a third of a square
 * in a 13x15 room and exactly nothing in an 11x15 one. The camera sat on the room's centre and
 * the party walked around inside a still frame.
 *
 * <p>What actually sits just past the floor is the wall, which is scenery rather than void, so
 * spending a little of it buys the follow back. Combat is unaffected — it asks for the room's
 * centre, and this only ever widens a range the centre is already inside.
 */
const FOLLOW_SLACK = 1.4;

/**
 * The furthest the combat camera may pull back.
 *
 * <p>Framing the whole floor is the right instinct and the wrong rule in a tall, narrow canvas.
 * Fitting a fifteen-deep room across a viewport half as wide as it is high needs a half-height
 * near eighteen — the room ends up a postage stamp in a field of black, and the tokens, which
 * are now correctly scaled against the walls, become specks. A fight is the party and whatever
 * is next to them; past this the extra floor is architecture, not information. Beyond the cap
 * the camera follows instead, which {@link clampToRoom} already keeps honest.
 */
const COMBAT_MAX_HALF_HEIGHT = 10;

/** How high up the wall a torch is mounted. The walls stand about one unit tall. */
const WALL_TORCH_Y = 0.42;

/**
 * How many point lights the torches may claim between them. Chosen to sit under the count
 * where the forward renderer starts costing more than the light adds.
 */
const MAX_TORCH_LIGHTS = 10;

/**
 * Torch spacing in squares, per lighting preset — 0 for none.
 *
 * <p>A DARK room has no torches rather than dim ones: the preset says nobody has been here to
 * light them, and a wall of guttering flames would be arguing with the fiction.
 */
const TORCH_SPACING: Record<LightingPreset, number> = {
  TORCHLIT: 3,
  DIM: 5,
  DARK: 0,
};

/**
 * The perimeter's repertoire, and how often each turn up.
 *
 * <p>Ruins carries the run: it is the only kit with a real family of wall pieces, and the
 * pillars and rubble already standing in the room come from it, so the architecture finally
 * agrees with itself. The two dungeon pieces are here for the things ruins has no equivalent
 * of — a plank door and a portcullis. Both read as a sealed way out, which is what a crypt
 * wants; an open arch would promise a passage that M1 has no rooms on the other side of.
 *
 * <p>`fit` brings a piece to the height of a plain wall. Only the arches need it: they are
 * authored two squares tall against the ruins wall's one.
 */
interface WallVariant {
  path: string;
  weight: number;
  /** Scale to this height in squares instead of using the kit's own module. */
  fit?: number;
  /** Repaint the piece's stone to the base wall's palette. */
  retint?: boolean;
  /** A second piece drawn at the same segment — the bars that fill an archway. */
  fills?: string;
}

/** Units per square in both Quaternius kits. See assets/kits/props/LICENSES.md. */
const QUATERNIUS_MODULE = 2;

const WALL_VARIANTS: WallVariant[] = [
  { path: "props/ruins/Wall", weight: 24 },
  { path: "props/ruins/Wall_Hole", weight: 3 },
  { path: "props/ruins/Window_Bars", weight: 3 },
  { path: "props/dungeon/Arch", weight: 2, fit: WALL_UNIT, retint: true,
    fills: "props/dungeon/Arch_bars" },
  { path: "props/dungeon/Arch", weight: 2, fit: WALL_UNIT, retint: true,
    fills: "props/dungeon/Arch_Door" },
];

/**
 * What stands in an archway, scaled to sit inside it.
 *
 * <p>Neither is a wall segment on its own. Brought to the height of a wall the door is 0.76
 * wide and the bars 0.73, against a segment one square across — on their own they would leave
 * a slot of open background down each side. The stone arch is the segment; these fill it.
 */
const WALL_FILLERS: Array<{ path: string; fit: number }> = [
  { path: "props/dungeon/Arch_bars", fit: WALL_UNIT * 0.82 },
  { path: "props/dungeon/Arch_Door", fit: WALL_UNIT * 0.82 },
];

/** Overlay colours. Where you may go, who you may hit, and what is under the cursor. */
const MOVE_TINT = 0x5c86c4;
const TARGET_TINT = 0xc0453c;
const HOVER_TINT = 0xe8dcc0;
/** Just off the floor. Coplanar with it z-fights, and at 480px a z-fight is a strobe. */
const OVERLAY_Y = 0.03;

const LIGHTING: Record<LightingPreset, {
  ambient: number;
  intensity: number;
  key: number;
  background: number;
}> = {
  TORCHLIT: { ambient: 0x3c3a52, intensity: 1.1, key: 0.55, background: 0x0b0a10 },
  DIM: { ambient: 0x24222f, intensity: 0.7, key: 0.35, background: 0x07070a },
  DARK: { ambient: 0x14141c, intensity: 0.35, key: 0.15, background: 0x040406 },
};

/**
 * Owns the canvas. Created exactly once and held in a useRef (invariant #4) — React never
 * re-creates it, and every update below is an imperative mutation rather than a re-render.
 */
export class Renderer {
  private readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera: THREE.OrthographicCamera;
  private readonly room = new THREE.Group();
  private readonly tokens = new Map<string, Token>();
  /** Who the player is allowed to give orders to. Highlights are drawn for nobody else. */
  private readonly playerControlled = new Set<string>();
  private readonly props = new Map<string, THREE.Object3D>();
  private readonly flames: THREE.PointLight[] = [];

  private readonly overlay = new THREE.Group();
  private readonly moveSquares: THREE.InstancedMesh;
  private readonly targetSquares: THREE.InstancedMesh;
  private readonly hoverSquare: THREE.Mesh;
  private readonly moveMaterial: THREE.MeshBasicMaterial;
  private combat: CombatView | null = null;

  private readonly raycaster = new THREE.Raycaster();
  private readonly ground = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);

  private readonly composer: EffectComposer;
  private readonly pixelPass: RenderPixelatedPass;
  private readonly moving = new Map<string, { from: THREE.Vector3; to: THREE.Vector3; t: number }>();
  private azimuth = Math.PI / 4;
  private azimuthFrom = Math.PI / 4;
  private azimuthTo = Math.PI / 4;
  private rotationT = 1;

  /** Which framing the camera is heading for, and how far through the move it is. */
  private framing: Mode = "EXPLORATION";
  private framingT = 1;
  private framingFrom = EXPLORATION_HALF_HEIGHT;
  /** Half-height right now — interpolated during a framing change, pinned either side of one. */
  private halfHeight = EXPLORATION_HALF_HEIGHT;

  /** Where the camera is looking, and where it was looking when the current move began. */
  private readonly focus = new THREE.Vector3();
  private readonly focusFrom = new THREE.Vector3();
  /** Whatever {@link resize} last handed the pixelation pass. Needed to snap the camera to it. */
  private pixelSize = 3;
  private kit: { floor: KitPiece; walls: Map<string, WallPiece> } | null = null;
  private dims = { width: 12, height: 12 };
  private frameHandle = 0;
  private disposed = false;

  constructor(private readonly canvas: HTMLCanvasElement) {
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false });
    this.renderer.setPixelRatio(1);
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.0;

    this.camera = new THREE.OrthographicCamera(-10, 10, 10, -10, 0.1, 200);

    this.scene.add(this.room);

    // The overlay is deliberately outside `room`: setScene() clears that group on every scene,
    // and highlights must survive a reconnect that arrives mid-turn.
    this.moveMaterial = overlayMaterial(MOVE_TINT, 0.3);
    this.moveSquares = buildSquares(this.moveMaterial);
    this.targetSquares = buildSquares(overlayMaterial(TARGET_TINT, 0.42));
    this.hoverSquare = new THREE.Mesh(squareGeometry(0.92), overlayMaterial(HOVER_TINT, 0.5));
    this.hoverSquare.visible = false;
    this.overlay.add(this.moveSquares, this.targetSquares, this.hoverSquare);
    this.scene.add(this.overlay);

    // Pixelation also does normal- and depth-based edge detection, which keeps prop
    // silhouettes legible once the resolution drops.
    this.pixelPass = new RenderPixelatedPass(3, this.scene, this.camera);
    this.pixelPass.normalEdgeStrength = 0.5;
    this.pixelPass.depthEdgeStrength = 0.25;

    this.composer = new EffectComposer(this.renderer);
    this.composer.addPass(this.pixelPass);
    this.composer.addPass(new OutputPass());

    this.resize();
  }

  /**
   * Puts the camera where the current azimuth, framing and focus say it belongs.
   *
   * <p>True isometric: atan(1/sqrt(2)) down, at whatever corner the player has rotated to. The
   * elevation never changes — it is what makes the projection isometric, and the four corners
   * exist so the pixelation pass always resolves tile edges onto the same screen-space slopes.
   *
   * <p>Runs every frame rather than on demand, because the focus point moves continuously.
   */
  private applyCamera(): void {
    // Orthographic, so distance only sets the clip range — not apparent size.
    const distance = 30;
    const elevation = Math.atan(1 / Math.SQRT2);

    const offset = new THREE.Vector3(
      distance * Math.cos(elevation) * Math.sin(this.azimuth),
      distance * Math.sin(elevation),
      distance * Math.cos(elevation) * Math.cos(this.azimuth),
    );

    // Aim once at the raw focus so the camera's own basis is available to snap against.
    this.camera.position.copy(this.focus).add(offset);
    this.camera.lookAt(this.focus);
    this.camera.updateMatrixWorld(true);

    const aimed = this.snapToPixelGrid(this.focus);
    this.camera.position.copy(aimed).add(offset);
    this.camera.lookAt(aimed);

    const aspect = this.viewport().width / this.viewport().height;
    this.camera.top = this.halfHeight;
    this.camera.bottom = -this.halfHeight;
    this.camera.right = this.halfHeight * aspect;
    this.camera.left = -this.halfHeight * aspect;
    this.camera.updateProjectionMatrix();
  }

  /**
   * Quantises the focus point to whole low-resolution pixels.
   *
   * <p>Until the camera started following the party it only ever orbited, and a scene that never
   * translates never shows this: at 480px with nearest-neighbour upscaling, a camera that moves
   * by a fraction of a low-res pixel resamples the entire frame, and every edge in the room
   * crawls while you walk. Snapping the focus to the render target's own grid means the scene
   * can only ever move in exact pixel steps, which is what keeps the art still underneath it.
   *
   * <p>Both screen axes share one unit: the projection is orthographic and the half-width is the
   * half-height times the aspect, so world units per pixel come out the same either way.
   */
  private snapToPixelGrid(focus: THREE.Vector3): THREE.Vector3 {
    const rows = Math.max(1, Math.round(this.viewport().height / this.pixelSize));
    const unit = (this.halfHeight * 2) / rows;

    const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
    const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);

    const alongRight = focus.dot(right) / unit;
    const alongUp = focus.dot(up) / unit;

    return focus
      .clone()
      .addScaledVector(right, (Math.round(alongRight) - alongRight) * unit)
      .addScaledVector(up, (Math.round(alongUp) - alongUp) * unit);
  }

  private viewport(): { width: number; height: number } {
    return {
      width: this.canvas.clientWidth || 1280,
      height: this.canvas.clientHeight || 720,
    };
  }

  /**
   * Where the exploration camera wants to look: the middle of the party.
   *
   * <p>A centroid over every player-controlled token rather than "the fighter" — M0 has one
   * member but the party is a list (invariant #2), and a camera written against a sample size of
   * one is exactly the kind of thing §12 warns about.
   */
  private partyCentre(): THREE.Vector3 {
    const centre = new THREE.Vector3();
    let count = 0;

    for (const id of this.playerControlled) {
      const token = this.tokens.get(id);
      if (!token) continue;
      centre.add(token.group.position);
      count++;
    }

    // Nothing to follow — hold the room rather than snapping to the origin mid-session.
    if (count === 0) return this.focus.clone();

    // The hop in advanceMovement rides on the token's y, and a camera that inherits it bounces
    // with every step.
    return centre.divideScalar(count).setY(0);
  }

  /**
   * Cross to the other framing. The move itself is the mode transition the player sees (T12);
   * nothing else about combat is announced by the camera.
   */
  setFraming(mode: Mode): void {
    if (this.framing === mode) return;
    this.framing = mode;
    this.framingFrom = this.halfHeight;
    this.focusFrom.copy(this.focus);
    this.framingT = 0;
  }

  /** What the current framing is aiming at, before any easing. */
  private framingTarget(): { halfHeight: number; focus: THREE.Vector3 } {
    const halfHeight =
      this.framing === "COMBAT" ? this.combatHalfHeight() : EXPLORATION_HALF_HEIGHT;
    const wanted = this.framing === "COMBAT" ? ROOM_CENTRE.clone() : this.partyCentre();
    return { halfHeight, focus: this.clampToRoom(wanted, halfHeight) };
  }

  /**
   * The tightest framing that still contains the whole room.
   *
   * <p>Measured rather than derived from the grid size. The obvious formula — half the floor's
   * diagonal, plus headroom — sizes the *vertical* window to hold the room's *horizontal* extent,
   * and an isometric floor is about twice as wide on screen as it is tall. That put the combat
   * camera roughly twice as far out as it needed to be, which was invisible while it was the only
   * framing there was and became very visible the moment exploration moved in close: the
   * pull-back read as a retreat into empty space rather than as a step back to see the board.
   *
   * <p>So: project the room's eight corners onto the camera's own axes and take whichever of the
   * two constraints binds. Rotation is handled for free, and a room that is not square will be
   * framed correctly on every corner rather than only on the worst one.
   */
  private combatHalfHeight(): number {
    const { width, height } = this.viewport();
    const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
    const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);

    const x = this.dims.width / 2;
    const z = this.dims.height / 2;

    let halfSpanRight = 0;
    let halfSpanUp = 0;
    for (const cx of [-x, x]) {
      for (const cz of [-z, z]) {
        for (const cy of [0, WALL_UNIT]) {
          const corner = new THREE.Vector3(cx, cy, cz);
          halfSpanRight = Math.max(halfSpanRight, Math.abs(corner.dot(right)));
          halfSpanUp = Math.max(halfSpanUp, Math.abs(corner.dot(up)));
        }
      }
    }

    // Whichever axis runs out first decides the zoom, up to the point where pulling back
    // stops showing more fight and only shows more floor.
    const fitted = Math.max(halfSpanUp, (halfSpanRight * height) / width) + ROOM_MARGIN;
    return Math.min(fitted, COMBAT_MAX_HALF_HEIGHT);
  }

  /**
   * Keeps the frame full of room.
   *
   * <p>A follow camera pointed straight at the party walks the room off the edge of the screen
   * the moment the party stands near a wall — which in this room is where they start. Half the
   * frame becomes the void outside the floor, and the crypt reads as a model on a table rather
   * than as somewhere with more of itself behind you.
   *
   * <p>Works on the two screen axes rather than on x and z, because the floor is a diamond once
   * projected and a world-space box would clamp the wrong corners. Projecting the footprint onto
   * the camera's own basis makes the rotation fall out for free: each of the four corners gets a
   * different limit, and none of them needs to be written down.
   *
   * <p>When the room is smaller than the window along an axis — which is every axis in combat —
   * there is nothing to clamp, and it centres instead.
   */
  private clampToRoom(focus: THREE.Vector3, halfHeight: number): THREE.Vector3 {
    const halfWidth = (halfHeight * this.viewport().width) / this.viewport().height;

    const right = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 0);
    const up = new THREE.Vector3().setFromMatrixColumn(this.camera.matrixWorld, 1);

    const x = this.dims.width / 2;
    const z = this.dims.height / 2;
    const corners = [
      new THREE.Vector3(-x, 0, -z),
      new THREE.Vector3(x, 0, -z),
      new THREE.Vector3(x, 0, z),
      new THREE.Vector3(-x, 0, z),
    ];

    const clamped = focus.clone();
    for (const [axis, half] of [
      [right, halfWidth],
      [up, halfHeight],
    ] as const) {
      const spans = corners.map((corner) => corner.dot(axis));
      const low = Math.min(...spans);
      const high = Math.max(...spans);
      const at = focus.dot(axis);

      // A room that already fits is centred; there is nothing to follow into.
      const want =
        high - low <= half * 2
          ? (low + high) / 2
          : Math.min(
              Math.max(at, low + half - FOLLOW_SLACK),
              high - half + FOLLOW_SLACK,
            );
      clamped.addScaledVector(axis, want - at);
    }
    return clamped;
  }

  /**
   * Advance the framing and the follow.
   *
   * <p>While a framing change is running, both zoom and focus ride the same eased curve, so the
   * pull-back arrives as one gesture. At rest the focus instead chases the party exponentially,
   * which handles a step, a long walk and a spawn without any of them needing to agree on a
   * duration.
   */
  private advanceCamera(delta: number): void {
    const target = this.framingTarget();

    if (this.framingT < 1) {
      this.framingT = Math.min(this.framingT + delta / FRAMING_SECONDS, 1);
      const eased = easeInOutCubic(this.framingT);
      this.halfHeight = this.framingFrom + (target.halfHeight - this.framingFrom) * eased;
      this.focus.lerpVectors(this.focusFrom, target.focus, eased);
    } else {
      this.halfHeight = target.halfHeight;
      this.focus.lerp(target.focus, 1 - Math.exp(-delta / FOLLOW_SECONDS));
    }

    this.applyCamera();
  }

  /** Drop the camera straight onto the current framing, with no move. For scene construction. */
  private settleCamera(): void {
    const target = this.framingTarget();
    this.halfHeight = target.halfHeight;
    this.framingFrom = target.halfHeight;
    this.focus.copy(target.focus);
    this.focusFrom.copy(target.focus);
    this.framingT = 1;
    this.applyCamera();
  }

  /** Snap to the next corner. `direction` is -1 (counter-clockwise) or +1 (clockwise). */
  rotate(direction: -1 | 1): void {
    // Start from the target, not the current angle, so rapid presses queue rather than fight.
    this.azimuthFrom = this.azimuth;
    this.azimuthTo = this.azimuthTo + direction * ROTATION_STEP;
    this.rotationT = 0;
  }

  /** Which of the four corners the camera is heading to, for the compass readout. */
  facing(): number {
    return ((Math.round((this.azimuthTo - Math.PI / 4) / ROTATION_STEP) % 4) + 4) % 4;
  }

  private advanceRotation(delta: number): void {
    if (this.rotationT >= 1) return;

    this.rotationT = Math.min(this.rotationT + delta / ROTATION_SECONDS, 1);
    const eased = easeInOutCubic(this.rotationT);
    this.azimuth = this.azimuthFrom + (this.azimuthTo - this.azimuthFrom) * eased;
  }

  async init(): Promise<void> {
    // Characters are preloaded here rather than on demand so addEntity stays synchronous —
    // a diff must be able to put a token on the board on the frame it lands.
    const [floor] = await Promise.all([
      loadKitPiece("template-floor"),
      ...characterPaths().map((path) =>
        // A missing character degrades to the placeholder figure rather than killing the scene.
        loadCharacter(path).catch((error) => {
          console.error(`character '${path}' failed to load`, error);
        }),
      ),
      ...propModelPaths().map((path) =>
        // Likewise a missing prop model: buildProp falls back to its primitive.
        loadPropModel(path).catch((error) => {
          console.error(`prop model '${path}' failed to load`, error);
        }),
      ),
    ]);
    // The wall repertoire, then the retint. The base wall's own palette is the reference,
    // so the dungeon arch is matched to whatever ruins was authored with rather than to a
    // colour written down here.
    const pieces = await Promise.all(
      WALL_VARIANTS.map((v) => loadWallPiece(v.path, QUATERNIUS_MODULE, v.fit)),
    );
    const basePalette = paletteOf(pieces[0]);
    WALL_VARIANTS.forEach((variant, i) => {
      if (variant.retint) retintStone(pieces[i], basePalette);
    });

    const walls = new Map<string, WallPiece>();
    WALL_VARIANTS.forEach((variant, i) => walls.set(variant.path, pieces[i]));

    const fillers = await Promise.all(
      WALL_FILLERS.map((f) => loadWallPiece(f.path, QUATERNIUS_MODULE, f.fit)),
    );
    WALL_FILLERS.forEach((filler, i) => walls.set(filler.path, fillers[i]));

    this.kit = { floor, walls };
  }

  // ---- Scene construction ----

  setScene(state: SceneState): void {
    if (!this.kit) throw new Error("Renderer.init() must complete before setScene()");

    this.room.clear();
    for (const token of this.tokens.values()) token.dispose();
    this.tokens.clear();
    this.playerControlled.clear();
    this.props.clear();
    this.flames.length = 0;
    this.dims = { width: state.width, height: state.height };

    this.buildLighting(state.lighting);
    this.buildFloor(state);
    this.buildWalls(state);
    this.buildWallTorches(state);

    for (const prop of state.props) this.addProp(prop);
    for (const entity of state.entities) this.addEntity(entity);
    for (const entity of state.entities) this.setHp(entity.id, entity.hp, entity.maxHp);

    this.setCombat(state.combat);

    // Framing follows the scene's own mode, so a reconnect that lands mid-fight opens on the
    // tactical view instead of easing out to it a second time.
    this.framing = state.mode;
    this.settleCamera();
  }

  private buildLighting(preset: LightingPreset): void {
    const config = LIGHTING[preset];

    // No fog. With an orthographic camera parked 30 units out, distance-based fog swallows
    // the entire room before it reaches the near plane.
    this.scene.fog = null;
    this.renderer.setClearColor(config.background, 1);

    this.room.add(new THREE.AmbientLight(config.ambient, config.intensity));

    // A cold key from above so geometry reads even where no brazier reaches.
    const key = new THREE.DirectionalLight(0x9fb0d0, config.key);
    key.position.set(6, 14, 8);
    key.castShadow = true;
    key.shadow.mapSize.set(1024, 1024);
    key.shadow.camera.left = -12;
    key.shadow.camera.right = 12;
    key.shadow.camera.top = 12;
    key.shadow.camera.bottom = -12;
    this.room.add(key);
  }

  private buildFloor(state: SceneState): void {
    const { floor } = this.kit!;
    const count = state.width * state.height;
    const mesh = new THREE.InstancedMesh(floor.geometry, floor.material, count);
    mesh.receiveShadow = true;

    const matrix = new THREE.Matrix4();
    let i = 0;
    for (let gy = 0; gy < state.height; gy++) {
      for (let gx = 0; gx < state.width; gx++) {
        matrix.setPosition(toWorld(gx, gy, state.width, state.height));
        mesh.setMatrixAt(i++, matrix);
      }
    }
    mesh.instanceMatrix.needsUpdate = true;
    mesh.name = "floor";
    this.room.add(mesh);
  }

  /**
   * Perimeter walls. Kenney's wall geometry occupies z in [-2, 0] before scaling, so an
   * unrotated tile sits just outside a +z-facing edge — which is why each side gets a
   * different Y rotation rather than a position offset.
   */
  /**
   * The room's perimeter, in two faces rather than one.
   *
   * <p>Every segment used to be the same slab, which at this size reads as a repeating panel
   * with a seam every square — the DM kept narrating frescoes and carvings onto a wall that had
   * none. A second face breaks the repeat without adding a single new idea to the scene schema.
   *
   * <p>Only Kenney's own variants are eligible. The Quaternius packs look like they should fit,
   * and their columns and arches do, but their walls are a two-unit module against Kenney's
   * four and a fifth of the depth — a thin ruin panel butted against a chunky dungeon block.
   * `template-wall-detail-a` is the same footprint and the same depth, so it drops in.
   *
   * <p>Which segment gets which face is hashed from the room id, not drawn from Math.random.
   * The same room has to come back the same way on a reconnect, and the client is never told
   * the generator's seed — the room id is the only stable thing it has.
   */
  private buildWalls(state: SceneState): void {
    const { walls } = this.kit!;
    const { width, height } = state;
    const halfW = width / 2;
    const halfH = height / 2;

    const placements: Array<{ x: number; z: number; ry: number }> = [];

    for (let gx = 0; gx < width; gx++) {
      const x = gx - halfW + 0.5;
      placements.push({ x, z: -halfH, ry: 0 }); // north
      placements.push({ x, z: halfH, ry: Math.PI }); // south
    }
    for (let gy = 0; gy < height; gy++) {
      const z = -(gy - halfH + 0.5);
      placements.push({ x: -halfW, z, ry: Math.PI / 2 }); // west
      placements.push({ x: halfW, z, ry: -Math.PI / 2 }); // east
    }

    const total = WALL_VARIANTS.reduce((sum, v) => sum + v.weight, 0);
    const runs = new Map<string, Array<{ x: number; z: number; ry: number }>>();
    const push = (path: string, p: { x: number; z: number; ry: number }) => {
      const run = runs.get(path);
      if (run) run.push(p);
      else runs.set(path, [p]);
    };

    placements.forEach((p, i) => {
      // Hashed from the room id, not Math.random: a room has to rebuild identically on a
      // reconnect, and the client is never told the generator's seed.
      let roll = hash32(`${state.roomId}:wall:${i}`) % total;
      const variant =
        WALL_VARIANTS.find((v) => (roll -= v.weight) < 0) ?? WALL_VARIANTS[0];
      push(variant.path, p);
      if (variant.fills) push(variant.fills, p);
    });

    for (const [path, run] of runs) {
      const piece = walls.get(path);
      if (piece) this.addWallRun(piece, run, `walls:${path}`);
    }
  }

  private addWallRun(
    piece: WallPiece,
    placements: Array<{ x: number; z: number; ry: number }>,
    name: string,
  ): void {
    if (placements.length === 0) return;

    const mesh = new THREE.InstancedMesh(piece.geometry, piece.materials, placements.length);
    mesh.castShadow = true;
    mesh.receiveShadow = true;

    const matrix = new THREE.Matrix4();
    const quaternion = new THREE.Quaternion();
    const scale = new THREE.Vector3(1, 1, 1);

    placements.forEach((p, i) => {
      quaternion.setFromAxisAngle(new THREE.Vector3(0, 1, 0), p.ry);
      matrix.compose(new THREE.Vector3(p.x, 0, p.z), quaternion, scale);
      mesh.setMatrixAt(i, matrix);
    });

    mesh.instanceMatrix.needsUpdate = true;
    mesh.name = name;
    this.room.add(mesh);
  }

  /**
   * Mounts torches around the walls, as the rendering of the room's lighting preset.
   *
   * <p>The generated rooms are large and their props sit around the edges, which left the
   * middle of the floor an unlit void that read as missing rather than dark. Light on the
   * perimeter is what makes an empty floor look like a room you are standing in.
   *
   * <p>Only a bounded number of torches actually carry a light. Every point light is real work
   * in the shader, and a 15x15 room has sixty perimeter squares; past a handful the extra
   * lights change the picture far less than they cost. The rest are geometry, lit by their
   * neighbours.
   */
  private buildWallTorches(state: SceneState): void {
    const spacing = TORCH_SPACING[state.lighting];
    if (spacing === 0) return;

    const { width, height } = state;
    const halfW = width / 2;
    const halfH = height / 2;
    // Far enough off the wall face that the torch does not intersect it.
    const inset = 0.32;

    const mounts: Array<{ x: number; z: number; ry: number }> = [];
    for (let gx = 0; gx < width; gx++) {
      if (gx % spacing !== 0) continue;
      const x = gx - halfW + 0.5;
      mounts.push({ x, z: -halfH + inset, ry: 0 });
      mounts.push({ x, z: halfH - inset, ry: Math.PI });
    }
    for (let gy = 0; gy < height; gy++) {
      if (gy % spacing !== 0) continue;
      const z = -(gy - halfH + 0.5);
      mounts.push({ x: -halfW + inset, z, ry: Math.PI / 2 });
      mounts.push({ x: halfW - inset, z, ry: -Math.PI / 2 });
    }

    let lights = 0;
    for (const mount of mounts) {
      const lit = lights < MAX_TORCH_LIGHTS;
      const torch = buildWallTorch(lit);
      if (!torch) return;
      if (lit) lights++;

      torch.position.set(mount.x, WALL_TORCH_Y, mount.z);
      torch.rotation.y = mount.ry;
      this.room.add(torch);

      torch.traverse((child) => {
        if (child instanceof THREE.PointLight && child.name === "flame") {
          this.flames.push(child);
        }
      });
    }
  }

  // ---- Incremental updates, driven by diffs ----

  addProp(prop: Prop): void {
    if (this.props.has(prop.id)) return;

    const object = buildProp(prop);
    object.position.copy(toWorld(prop.x, prop.y, this.dims.width, this.dims.height));
    this.room.add(object);
    this.props.set(prop.id, object);

    object.traverse((child) => {
      if (child instanceof THREE.PointLight && child.name === "flame") {
        this.flames.push(child);
      }
    });
  }

  addEntity(entity: EntityView): void {
    if (this.tokens.has(entity.id)) {
      this.moveEntity(entity.id, entity.x, entity.y);
      return;
    }
    const token = new Token(entity);
    token.setBarVisible(this.combat !== null);
    if (entity.isPlayerControlled) this.playerControlled.add(entity.id);
    token.group.position.copy(toWorld(entity.x, entity.y, this.dims.width, this.dims.height));
    // Three-quarter view: facing the default camera corner reads better than facing straight
    // down an axis, and it is what a figure placed on a table would look like.
    token.faceTowards(1, 1);
    this.room.add(token.group);
    this.tokens.set(entity.id, token);
  }

  /** Hit points as the server last reported them. The token decides how to show the change. */
  setHp(entityId: string, hp: number, maxHp: number): void {
    this.tokens.get(entityId)?.setHp(hp, maxHp);
  }

  /** One swing, aimed. Facing is part of the blow — a sword swung at nobody reads as a stumble. */
  strike(actorId: string, targetId: string): void {
    const actor = this.tokens.get(actorId);
    const target = this.tokens.get(targetId);
    if (!actor) return;

    if (target) {
      const from = actor.group.position;
      const to = target.group.position;
      actor.faceTowards(to.x - from.x, to.z - from.z);
    }
    actor.strike();
  }

  removeEntity(entityId: string): void {
    const token = this.tokens.get(entityId);
    if (!token) return;
    token.dispose();
    this.room.remove(token.group);
    this.tokens.delete(entityId);
    this.playerControlled.delete(entityId);
  }

  /**
   * Starts an eased slide. Nothing here waits on the server — the move has already been
   * adjudicated, so the token begins moving on the same frame the diff lands (the <100ms
   * click-to-move budget has no model anywhere in its path).
   */
  moveEntity(entityId: string, x: number, y: number): void {
    const token = this.tokens.get(entityId);
    if (!token) return;

    const to = toWorld(x, y, this.dims.width, this.dims.height);
    const from = token.group.position;
    if (from.distanceToSquared(to) < 1e-6) return;

    token.faceTowards(to.x - from.x, to.z - from.z);
    token.play("walk");
    this.moving.set(entityId, { from: from.clone(), to, t: 0 });
  }

  // ---- Combat overlay ----

  /**
   * Draws the legal sets the server sent. Nothing here decides what is legal — passing the
   * squares straight from the wire to the highlight is the whole point (invariant #1).
   */
  setCombat(combat: CombatView | null): void {
    const started = (this.combat === null) !== (combat === null);
    this.combat = combat;

    if (started) {
      for (const token of this.tokens.values()) token.setBarVisible(combat !== null);
    }

    // Only ever the player's own options. Highlighting the goblin's legal moves on its turn
    // would both spoil its intent and look like squares the player could click.
    const playerTurn = combat !== null && this.playerControlled.has(combat.activeId);
    const squares = playerTurn ? this.squares : new Map<string, Square>();

    this.place(this.moveSquares, playerTurn ? combat.legalMoves : []);
    this.place(
      this.targetSquares,
      (playerTurn ? combat.legalTargets : []).flatMap((id) => {
        const square = squares.get(id);
        return square ? [square] : [];
      }),
    );
    if (!combat) this.hoverSquare.visible = false;
  }

  /** Highlight the square under the cursor, when it is one the player may actually use. */
  setHover(square: Square | null): void {
    if (!square) {
      this.hoverSquare.visible = false;
      return;
    }
    this.hoverSquare.visible = true;
    this.hoverSquare.position.copy(toWorld(square.x, square.y, this.dims.width, this.dims.height));
    this.hoverSquare.position.y = OVERLAY_Y + 0.004;
  }

  private place(mesh: THREE.InstancedMesh, squares: readonly Square[]): void {
    const matrix = new THREE.Matrix4();
    // Capacity is the buffer's length, not `mesh.count` — that one is how many to draw, and it
    // is zero whenever nothing is highlighted.
    const limit = Math.min(squares.length, mesh.instanceMatrix.count);

    for (let i = 0; i < limit; i++) {
      const world = toWorld(squares[i].x, squares[i].y, this.dims.width, this.dims.height);
      matrix.setPosition(world.x, OVERLAY_Y, world.z);
      mesh.setMatrixAt(i, matrix);
    }
    // InstancedMesh has no "hide instance": drawing fewer is how you draw fewer.
    mesh.count = limit;
    mesh.instanceMatrix.needsUpdate = true;
    mesh.visible = limit > 0;
  }

  /** Grid square of every entity, so a target highlight can find the floor under a figure. */
  private get squares(): Map<string, Square> {
    const found = new Map<string, Square>();
    for (const [id, token] of this.tokens) {
      const p = token.group.position;
      found.set(id, {
        x: Math.round(p.x + this.dims.width / 2 - 0.5),
        y: Math.round(-p.z + this.dims.height / 2 - 0.5),
      });
    }
    return found;
  }

  /**
   * What is under the pointer: a figure if the ray touches one, otherwise the floor square it
   * lands on.
   *
   * <p>Tokens are tested first because they are tall. An isometric ray through a figure's head
   * meets the floor a square or two behind it, so a ground-only pick would have the player
   * clicking a goblin's face and moving to the square beyond it.
   */
  pick(clientX: number, clientY: number): { entityId: string | null; square: Square | null } {
    const rect = this.canvas.getBoundingClientRect();
    this.raycaster.setFromCamera(
      new THREE.Vector2(
        ((clientX - rect.left) / rect.width) * 2 - 1,
        -((clientY - rect.top) / rect.height) * 2 + 1,
      ),
      this.camera,
    );

    for (const [id, token] of this.tokens) {
      if (token.isDead) continue;
      if (this.raycaster.intersectObject(token.group, true).length > 0) {
        return { entityId: id, square: this.squares.get(id) ?? null };
      }
    }

    const hit = this.raycaster.ray.intersectPlane(this.ground, new THREE.Vector3());
    if (!hit) return { entityId: null, square: null };

    const x = Math.round(hit.x + this.dims.width / 2 - 0.5);
    const y = Math.round(-hit.z + this.dims.height / 2 - 0.5);
    const inside = x >= 0 && x < this.dims.width && y >= 0 && y < this.dims.height;
    return { entityId: null, square: inside ? { x, y } : null };
  }

  /** Advance in-flight slides. Duration scales with distance so long moves do not crawl. */
  private advanceMovement(delta: number): void {
    for (const [entityId, move] of this.moving) {
      const token = this.tokens.get(entityId);
      if (!token) {
        this.moving.delete(entityId);
        continue;
      }

      const squares = move.from.distanceTo(move.to);
      const duration = moveSeconds(squares);
      move.t = Math.min(move.t + delta / duration, 1);

      const eased = easeOutCubic(move.t);
      token.group.position.lerpVectors(move.from, move.to, eased);
      // The hop stands in for a walk cycle, so a token that has one does not need it — a
      // figure that strides and bounces at the same time looks wrong.
      if (!token.animated) {
        token.group.position.y = Math.sin(eased * Math.PI) * 0.12 * Math.min(squares, 2);
      }

      if (move.t >= 1) {
        token.group.position.copy(move.to);
        token.play("idle");
        this.moving.delete(entityId);
      }
    }
  }

  // ---- Frame loop ----

  start(): void {
    const clock = new THREE.Clock();

    const tick = () => {
      if (this.disposed) return;
      this.frameHandle = requestAnimationFrame(tick);

      const delta = clock.getDelta();
      const t = clock.elapsedTime;

      this.advanceMovement(delta);
      this.advanceRotation(delta);
      // After movement, so the follow sees where the tokens actually got to this frame.
      this.advanceCamera(delta);
      // The camera's orientation, so health bars billboard to it through the 90-degree snaps.
      for (const token of this.tokens.values()) token.update(delta, this.camera.quaternion);

      // A slow pulse on the legal squares. Static translucent tiles read as scenery; the
      // breathing is what says "these are yours to click".
      this.moveMaterial.opacity = 0.22 + Math.sin(t * 2.4) * 0.08;
      // Cheap two-frequency flicker so the braziers never pulse in lockstep.
      this.flames.forEach((flame, i) => {
        // Each flame flickers around its own brightness: a wall torch is not a brazier, and
        // reading the brazier's constant here would flare every torch to match it.
        const base = (flame.userData.baseIntensity as number | undefined) ?? FLAME_INTENSITY;
        const swing = base / FLAME_INTENSITY;
        flame.intensity =
          base + Math.sin(t * 7.3 + i * 2.1) * 5 * swing + Math.sin(t * 17.7 + i) * 2.5 * swing;
      });

      this.composer.render();
    };
    tick();
  }

  resize(): void {
    const width = this.canvas.clientWidth || 1280;
    const height = this.canvas.clientHeight || 720;

    this.renderer.setSize(width, height, false);
    this.composer.setSize(width, height);

    // Pick the pixel size that lands closest to TARGET_WIDTH for this canvas.
    this.pixelSize = Math.max(1, Math.round(width / TARGET_WIDTH));
    this.pixelPass.setPixelSize(this.pixelSize);

    // Deliberately not settleCamera(): a resize mid-transition must not cancel the pull-back.
    this.applyCamera();
  }

  dispose(): void {
    this.moving.clear();
    this.disposed = true;
    cancelAnimationFrame(this.frameHandle);
    this.renderer.dispose();
  }
}

/** A flat quad the size of one grid square, lying face up. */
function squareGeometry(size: number): THREE.PlaneGeometry {
  const geometry = new THREE.PlaneGeometry(size, size);
  geometry.rotateX(-Math.PI / 2);
  return geometry;
}

function overlayMaterial(color: number, opacity: number): THREE.MeshBasicMaterial {
  return new THREE.MeshBasicMaterial({
    color,
    transparent: true,
    opacity,
    depthWrite: false,
    side: THREE.DoubleSide,
  });
}

/** Room-sized: 12x12 is 144 squares, and the whole floor can legally be highlighted at once. */
function buildSquares(material: THREE.Material): THREE.InstancedMesh {
  const mesh = new THREE.InstancedMesh(squareGeometry(0.86), material, 256);
  mesh.count = 0;
  mesh.visible = false;
  mesh.frustumCulled = false;
  return mesh;
}

function easeOutCubic(t: number): number {
  return 1 - Math.pow(1 - t, 3);
}

function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

/**
 * FNV-1a. Any stable hash would do; what matters is that it is stable — a room must rebuild
 * identically on a reconnect, and `Math.random` would reshuffle its walls mid-session.
 */
function hash32(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}
