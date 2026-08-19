import * as THREE from "three";
import type { EntityView, LightingPreset, Prop, SceneState } from "../types";
import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer.js";
import { RenderPixelatedPass } from "three/examples/jsm/postprocessing/RenderPixelatedPass.js";
import { OutputPass } from "three/examples/jsm/postprocessing/OutputPass.js";
import { loadKitPiece, toWorld, type KitPiece } from "./assets";
import { buildProp, FLAME_INTENSITY } from "./props";
import { buildToken } from "./tokens";

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
const ROTATION_SECONDS = 0.42;

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
  private readonly tokens = new Map<string, THREE.Group>();
  private readonly props = new Map<string, THREE.Object3D>();
  private readonly flames: THREE.PointLight[] = [];

  private readonly composer: EffectComposer;
  private readonly pixelPass: RenderPixelatedPass;
  private readonly moving = new Map<string, { from: THREE.Vector3; to: THREE.Vector3; t: number }>();
  private azimuth = Math.PI / 4;
  private azimuthFrom = Math.PI / 4;
  private azimuthTo = Math.PI / 4;
  private rotationT = 1;
  private kit: { floor: KitPiece; wall: KitPiece } | null = null;
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
    this.positionCamera();

    this.scene.add(this.room);

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

  /** True isometric: atan(1/sqrt(2)) down, at whatever corner the player has rotated to. */
  private positionCamera(): void {
    // Orthographic, so distance only sets the clip range — not apparent size.
    const distance = 30;
    const elevation = Math.atan(1 / Math.SQRT2);

    this.camera.position.set(
      distance * Math.cos(elevation) * Math.sin(this.azimuth),
      distance * Math.sin(elevation),
      distance * Math.cos(elevation) * Math.cos(this.azimuth),
    );
    this.camera.lookAt(0, 0, 0);
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
    this.positionCamera();
  }

  async init(): Promise<void> {
    const [floor, wall] = await Promise.all([
      loadKitPiece("template-floor"),
      loadKitPiece("template-wall"),
    ]);
    this.kit = { floor, wall };
  }

  // ---- Scene construction ----

  setScene(state: SceneState): void {
    if (!this.kit) throw new Error("Renderer.init() must complete before setScene()");

    this.room.clear();
    this.tokens.clear();
    this.props.clear();
    this.flames.length = 0;
    this.dims = { width: state.width, height: state.height };

    this.buildLighting(state.lighting);
    this.buildFloor(state);
    this.buildWalls(state);

    for (const prop of state.props) this.addProp(prop);
    for (const entity of state.entities) this.addEntity(entity);

    this.fitCamera();
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
  private buildWalls(state: SceneState): void {
    const { wall } = this.kit!;
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

    const mesh = new THREE.InstancedMesh(wall.geometry, wall.material, placements.length);
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
    mesh.name = "walls";
    this.room.add(mesh);
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
    const token = buildToken(entity);
    token.position.copy(toWorld(entity.x, entity.y, this.dims.width, this.dims.height));
    this.room.add(token);
    this.tokens.set(entity.id, token);
  }

  removeEntity(entityId: string): void {
    const token = this.tokens.get(entityId);
    if (!token) return;
    this.room.remove(token);
    this.tokens.delete(entityId);
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
    if (token.position.distanceToSquared(to) < 1e-6) return;

    this.moving.set(entityId, { from: token.position.clone(), to, t: 0 });
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
      const duration = Math.min(0.18 + squares * 0.07, 0.75);
      move.t = Math.min(move.t + delta / duration, 1);

      const eased = easeOutCubic(move.t);
      token.position.lerpVectors(move.from, move.to, eased);
      // A small hop sells the step without needing an animation rig.
      token.position.y = Math.sin(eased * Math.PI) * 0.12 * Math.min(squares, 2);

      if (move.t >= 1) {
        token.position.copy(move.to);
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
      // Cheap two-frequency flicker so the braziers never pulse in lockstep.
      this.flames.forEach((flame, i) => {
        flame.intensity =
          FLAME_INTENSITY +
          Math.sin(t * 7.3 + i * 2.1) * 5 +
          Math.sin(t * 17.7 + i) * 2.5;
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
    this.pixelPass.setPixelSize(Math.max(1, Math.round(width / TARGET_WIDTH)));

    this.fitCamera();
  }

  /** Frame the whole room with a little headroom for wall height. */
  private fitCamera(): void {
    const width = this.canvas.clientWidth || 1280;
    const height = this.canvas.clientHeight || 720;
    const aspect = width / height;

    const span = Math.max(this.dims.width, this.dims.height) * Math.SQRT2;
    const halfHeight = span / 2 + 1.5;
    const halfWidth = halfHeight * aspect;

    this.camera.left = -halfWidth;
    this.camera.right = halfWidth;
    this.camera.top = halfHeight;
    this.camera.bottom = -halfHeight;
    this.camera.updateProjectionMatrix();
  }

  dispose(): void {
    this.moving.clear();
    this.disposed = true;
    cancelAnimationFrame(this.frameHandle);
    this.renderer.dispose();
  }
}

function easeOutCubic(t: number): number {
  return 1 - Math.pow(1 - t, 3);
}

function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}
