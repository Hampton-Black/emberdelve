import * as THREE from "three";
import type { CombatView, EntityView, LightingPreset, Prop, SceneState, Square } from "../types";
import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer.js";
import { RenderPixelatedPass } from "three/examples/jsm/postprocessing/RenderPixelatedPass.js";
import { OutputPass } from "three/examples/jsm/postprocessing/OutputPass.js";
import { loadCharacter, loadKitPiece, toWorld, type KitPiece } from "./assets";
import { buildProp, FLAME_INTENSITY } from "./props";
import { characterPaths, Token } from "./tokens";

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
    // Characters are preloaded here rather than on demand so addEntity stays synchronous —
    // a diff must be able to put a token on the board on the frame it lands.
    const [floor, wall] = await Promise.all([
      loadKitPiece("template-floor"),
      loadKitPiece("template-wall"),
      ...characterPaths().map((path) =>
        // A missing character degrades to the placeholder figure rather than killing the scene.
        loadCharacter(path).catch((error) => {
          console.error(`character '${path}' failed to load`, error);
        }),
      ),
    ]);
    this.kit = { floor, wall };
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

    for (const prop of state.props) this.addProp(prop);
    for (const entity of state.entities) this.addEntity(entity);
    for (const entity of state.entities) this.setHp(entity.id, entity.hp, entity.maxHp);

    this.setCombat(state.combat);
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
      const duration = Math.min(0.18 + squares * 0.07, 0.75);
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
      // The camera's orientation, so health bars billboard to it through the 90-degree snaps.
      for (const token of this.tokens.values()) token.update(delta, this.camera.quaternion);

      // A slow pulse on the legal squares. Static translucent tiles read as scenery; the
      // breathing is what says "these are yours to click".
      this.moveMaterial.opacity = 0.22 + Math.sin(t * 2.4) * 0.08;
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
