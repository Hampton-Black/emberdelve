import * as THREE from "three";
import type { EntityView } from "../types";
import { instanceCharacter } from "./assets";

/**
 * Entity tokens: a Kenney character on a small base, animated.
 *
 * <p>Every model in these kits carries the same 32-clip rig, so `idle` / `walk` / `die` /
 * `attack-melee-right` work identically whichever one is configured. Swapping a character is
 * therefore a one-line change to {@link MODELS}.
 */

/** The clips this game actually asks for. The kits ship 32; the rest are for wheelchairs. */
export type TokenClip = "idle" | "walk" | "attack-melee-right" | "die";

/**
 * Which model plays which creature.
 *
 * <p>The graveyard kit carries the fantasy-shaped figures: `-keeper`, `-zombie`, `-skeleton`,
 * `-vampire`, `-ghost`. Kenney's `mini/` set is contemporary — a police officer, a
 * businessman, a doctor — and is kept because it is rig-identical and swaps in one line.
 *
 * <p>Native heights differ per model, so `height` is the wanted size in world units (one grid
 * square is 1.0) and the scale is derived from the model's own bounding box. Hard-coding a
 * scale factor instead would make every swap a re-measurement.
 *
 * <p>Figures deliberately overflow their square. Rendered at 480px internal width a
 * to-scale human is about 24 pixels tall and reads as a smudge; oversizing the figure relative
 * to its base is what tactical RPGs do, for exactly this reason.
 */
const MODELS: Record<string, { path: string; height: number; tint?: number }> = {
  fighter: { path: "graveyard/character-keeper", height: 1.25 },
  // No tint: the zombie is already the right green. Tinting a model that carries its own
  // colour just muddies the colormap.
  goblin: { path: "graveyard/character-zombie", height: 1.05 },
};

/** Used when a model is missing, so a failed asset is a grey figure rather than a blank square. */
const FALLBACK = { body: 0x8a8a8a, trim: 0xcccccc };

const BASE_COLOR: Record<string, number> = {
  fighter: 0xd6c9a8,
  goblin: 0x8f4a3d,
};

/** Fades between clips rather than cutting, which is the difference between animated and twitchy. */
const CROSSFADE_SECONDS = 0.2;

/**
 * A faint self-lit floor under the character's own colours.
 *
 * <p>The crypt is lit by two braziers and is genuinely dark everywhere else, which is correct for
 * the room and wrong for the figures standing in it — an unlit token is a black smudge the player
 * cannot find. This lifts characters off the floor without touching the scene lighting.
 */
const SELF_LIT = 0.22;

export class Token {
  readonly group = new THREE.Group();

  private readonly mixer: THREE.AnimationMixer | null;
  private readonly actions = new Map<string, THREE.AnimationAction>();
  private current: THREE.AnimationAction | null = null;

  constructor(entity: EntityView) {
    this.group.name = `entity:${entity.id}`;
    this.group.add(buildBase(entity.kind));

    const config = MODELS[entity.kind];
    const model = config ? instanceCharacter(config.path) : null;

    if (!model || !config) {
      this.group.add(buildPlaceholder());
      this.mixer = null;
      return;
    }

    const figure = model.scene;

    // Kits disagree about where the origin sits: mini models stand on y=0, graveyard models are
    // centred on the hips. Measuring is cheaper than maintaining a table of offsets.
    const bounds = new THREE.Box3().setFromObject(figure);
    const scale = config.height / Math.max(bounds.max.y - bounds.min.y, 1e-6);
    figure.scale.setScalar(scale);
    figure.position.y = -bounds.min.y * scale;

    figure.traverse((child) => {
      const mesh = child as THREE.Mesh;
      if (!mesh.isMesh) return;
      mesh.castShadow = true;
      mesh.receiveShadow = true;

      // Cloned first: SkeletonUtils shares materials, so editing in place would change every
      // token built from the same model.
      const source = Array.isArray(mesh.material) ? mesh.material[0] : mesh.material;
      const material = source.clone() as THREE.MeshStandardMaterial;

      if (config.tint !== undefined) material.color.setHex(config.tint);

      // Emissive through the colormap, so the glow is the character's own colours rather
      // than a grey wash.
      material.emissiveMap = material.map;
      material.emissive.setHex(0xffffff);
      material.emissiveIntensity = SELF_LIT;

      mesh.material = material;
    });

    this.group.add(figure);

    this.mixer = new THREE.AnimationMixer(figure);
    for (const clip of model.animations) {
      this.actions.set(clip.name, this.mixer.clipAction(clip));
    }
    this.play("idle");
  }

  /** Crossfades to a clip. `die` holds its last frame; everything else loops. */
  play(clip: TokenClip): void {
    const next = this.actions.get(clip);
    if (!next || next === this.current) return;

    next.reset();
    next.setLoop(clip === "die" ? THREE.LoopOnce : THREE.LoopRepeat, Infinity);
    next.clampWhenFinished = clip === "die";

    if (this.current) next.crossFadeFrom(this.current, CROSSFADE_SECONDS, false);
    next.play();
    this.current = next;
  }

  /** True when this token is a real rig rather than the fallback figure. */
  get animated(): boolean {
    return this.mixer !== null;
  }

  /** Turn to face a direction of travel. Kenney characters model forward as +Z. */
  faceTowards(dx: number, dz: number): void {
    if (dx === 0 && dz === 0) return;
    this.group.rotation.y = Math.atan2(dx, dz);
  }

  update(delta: number): void {
    this.mixer?.update(delta);
  }

  dispose(): void {
    this.mixer?.stopAllAction();
  }
}

/** A thin disc so the occupied square stays readable once a figure is standing on it. */
function buildBase(kind: string): THREE.Mesh {
  const base = new THREE.Mesh(
    new THREE.CylinderGeometry(0.34, 0.36, 0.05, 16),
    new THREE.MeshStandardMaterial({
      color: BASE_COLOR[kind] ?? FALLBACK.trim,
      roughness: 0.7,
      metalness: 0.2,
    }),
  );
  base.position.y = 0.025;
  base.castShadow = true;
  base.receiveShadow = true;
  return base;
}

function buildPlaceholder(): THREE.Group {
  const group = new THREE.Group();

  const body = new THREE.Mesh(
    new THREE.CapsuleGeometry(0.22, 0.5, 4, 12),
    new THREE.MeshStandardMaterial({ color: FALLBACK.body, roughness: 0.75 }),
  );
  body.position.y = 0.67;

  const head = new THREE.Mesh(
    new THREE.SphereGeometry(0.16, 12, 10),
    new THREE.MeshStandardMaterial({ color: FALLBACK.trim, roughness: 0.8 }),
  );
  head.position.y = 1.0;

  group.add(body, head);
  group.traverse((child) => {
    if ((child as THREE.Mesh).isMesh) {
      child.castShadow = true;
      child.receiveShadow = true;
    }
  });
  return group;
}

/** Every model the scene needs loaded before the first token is built. */
export function characterPaths(): string[] {
  return [...new Set(Object.values(MODELS).map((m) => m.path))];
}
