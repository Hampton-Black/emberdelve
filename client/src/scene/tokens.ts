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
 * How far into a swing the blow actually connects. The attack clip runs 417ms, so this is a
 * little past halfway — and it gates both the hit point bar and the drop, because everything a
 * blow causes has to wait for the blow.
 */
const IMPACT_SECONDS = 0.22;
/**
 * How long the bar takes to empty once it starts.
 *
 * A fixed span rather than an exponential chase: a chase never actually arrives, so the bar was
 * still creeping a full second after the body had hit the floor.
 */
const DRAIN_SECONDS = 0.34;

const BAR_WIDTH = 0.72;
const BAR_HEIGHT = 0.085;

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
  /** Position only — never rotated, so the health bar above it can face the camera freely. */
  readonly group = new THREE.Group();

  /** Everything that turns to face a direction of travel. */
  private readonly pivot = new THREE.Group();

  private readonly mixer: THREE.AnimationMixer | null;
  private readonly actions = new Map<string, THREE.AnimationAction>();
  private current: THREE.AnimationAction | null = null;

  private readonly bar: THREE.Group;
  private readonly barFill: THREE.Mesh;
  private readonly barFillMaterial: THREE.MeshBasicMaterial;
  private hp: number;
  private maxHp: number;
  /** What the bar is showing, on its way to `hp`. The gap is the drain. */
  private shownHp: number;
  private drainFrom = 0;
  private drainT = 1;
  private drainDelay = 0;
  /** Counts down to the moment the body drops. Zero when nothing is falling. */
  private dying = 0;
  private barWanted = false;
  private readonly hostile: boolean;
  private striking = 0;
  private dead = false;

  constructor(entity: EntityView) {
    this.group.name = `entity:${entity.id}`;
    this.group.add(this.pivot);
    this.pivot.add(buildBase(entity.kind));

    this.hp = entity.hp;
    this.maxHp = entity.maxHp;
    this.shownHp = entity.hp;
    this.hostile = !entity.isPlayerControlled;
    // A token can be born dead — a reconnect during a fight, or after one. setHp cannot catch
    // this, because the hit points it is handed already match what the constructor recorded, so
    // without it the corpse stands back up on every page load.
    this.dead = entity.hp <= 0;

    const config = MODELS[entity.kind];
    const model = config ? instanceCharacter(config.path) : null;

    const height = config?.height ?? 1.15;
    this.bar = buildBar();
    this.bar.position.y = height + 0.3;
    this.bar.visible = false;
    this.group.add(this.bar);
    this.barFill = this.bar.children[1] as THREE.Mesh;
    this.barFillMaterial = this.barFill.material as THREE.MeshBasicMaterial;
    // Drawn once up front: drawBar() otherwise only runs during a drain, so an undamaged enemy
    // would wear the mesh's default green until something hit it.
    this.drawBar();

    if (!model || !config) {
      this.pivot.add(buildPlaceholder());
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

    this.pivot.add(figure);

    this.mixer = new THREE.AnimationMixer(figure);
    for (const clip of model.animations) {
      this.actions.set(clip.name, this.mixer.clipAction(clip));
    }
    this.play("idle");
    // Straight to the last frame of `die` — there was no blow to wait for, only a page load.
    if (this.dead) this.drop();
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
    this.pivot.rotation.y = Math.atan2(dx, dz);
  }

  /**
   * One swing, then back to idle. Bypasses {@link play}'s same-clip guard on purpose: two
   * attacks in a row are two swings, and the second must restart the clip rather than be
   * swallowed as a no-op.
   */
  strike(): void {
    const action = this.actions.get("attack-melee-right");
    if (!action) return;

    action.reset();
    action.setLoop(THREE.LoopOnce, 1);
    action.clampWhenFinished = false;
    if (this.current && this.current !== action) this.current.fadeOut(CROSSFADE_SECONDS);
    action.fadeIn(0.05).play();
    this.current = action;
    this.striking = action.getClip().duration;
  }

  /**
   * Hit points, as the server last reported them. The bar chases rather than snaps: the number
   * changes on the frame the diff lands, but the swing that caused it is still mid-animation,
   * and a bar that empties before the sword arrives reads as a bug.
   */
  setHp(hp: number, maxHp: number): void {
    if (hp === this.hp && maxHp === this.maxHp) return;
    const hurt = hp < this.hp;
    this.drainFrom = this.shownHp;
    this.drainT = 0;
    this.drainDelay = hurt ? IMPACT_SECONDS : 0;
    this.hp = hp;
    this.maxHp = maxHp;

    if (hp <= 0 && !this.dead) {
      this.dead = true;
      // Dead immediately, down a moment later. A creature that falls as the sword starts moving
      // has died of something the player never saw land. Set to zero when the hit points did not
      // drop — a corpse restored from a reconnect has no blow to wait for.
      this.dying = hurt ? IMPACT_SECONDS : 0;
      if (this.dying <= 0) this.drop();
    } else if (hp > 0 && this.dead) {
      // A creature is dead exactly when the server says its hit points are zero — never because
      // it once was. M0's goblin has a fixed id, so spawning a second one replaces the first,
      // and a token that remembered the corpse would leave the new one face down on the floor.
      this.dead = false;
      this.dying = 0;
      this.shownHp = hp;
      this.drainT = 1;
      this.current = null;
      this.play("idle");
    }
    this.refreshBar();
  }

  /**
   * Whether this token wants a health bar at all. Shown in combat, and whenever something is
   * hurt — a full bar over every figure in a quiet room is an MMO, not a crypt.
   */
  setBarVisible(inCombat: boolean): void {
    this.barWanted = inCombat;
    this.refreshBar();
  }

  /** The body going down, once the blow that caused it has landed. */
  private drop(): void {
    this.dying = 0;
    this.current = null;
    this.play("die");
    this.refreshBar();
  }

  private refreshBar(): void {
    // Still shown while the creature is falling: the bar has to be visible to be seen emptying.
    const down = this.dead && this.dying <= 0;
    this.bar.visible = !down && this.maxHp > 0 && (this.barWanted || this.hp < this.maxHp);
  }

  get isDead(): boolean {
    return this.dead;
  }

  /** @param faceTo the camera's world orientation, so the health bar can billboard to it */
  update(delta: number, faceTo?: THREE.Quaternion): void {
    this.mixer?.update(delta);

    if (this.dying > 0) {
      this.dying -= delta;
      if (this.dying <= 0) this.drop();
    }

    if (this.striking > 0) {
      this.striking -= delta;
      if (this.striking <= 0) {
        this.striking = 0;
        // Never back to idle over a corpse — a creature killed mid-swing stays down.
        if (!this.dead) {
          this.current = null;
          this.play("idle");
        }
      }
    }

    if (faceTo) this.bar.quaternion.copy(faceTo);

    if (this.drainDelay > 0) {
      this.drainDelay -= delta;
    } else if (this.drainT < 1) {
      this.drainT = Math.min(this.drainT + delta / DRAIN_SECONDS, 1);
      const eased = 1 - Math.pow(1 - this.drainT, 3);
      this.shownHp = this.drainFrom + (this.hp - this.drainFrom) * eased;
      this.drawBar();
    }
  }

  private drawBar(): void {
    const fraction = Math.max(0, Math.min(1, this.shownHp / Math.max(this.maxHp, 1)));
    // Scaled from the left edge, so a draining bar shortens rather than shrinking to its middle.
    this.barFill.scale.x = fraction;
    this.barFill.position.x = -(BAR_WIDTH / 2) * (1 - fraction);
    // Colour is allegiance and length is health — the tactical-RPG convention, and the reason a
    // glance at the board tells you who is who before you have read a single number.
    //
    // The player's bar deliberately does not also warn by turning red as it empties. It used to,
    // and the moment enemies went red the same colour meant two different things: a short red bar
    // was you in trouble, a long red bar was a healthy goblin. One meaning per colour. The danger
    // signal lives in the bar's length and in the hit points in the header, and if that turns out
    // to be too quiet the fix is a pulse, not a hue.
    this.barFillMaterial.color.setHex(this.hostile ? 0xb83a30 : 0x7fae56);
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

/**
 * Two unlit quads: a dark backing and a fill scaled along x.
 *
 * <p>Unlit on purpose. The crypt is dark and a health bar is chrome, not scenery — a bar that
 * dims when a brazier gutters is a bar you cannot read at the moment you most need to.
 */
function buildBar(): THREE.Group {
  const group = new THREE.Group();

  const backing = new THREE.Mesh(
    new THREE.PlaneGeometry(BAR_WIDTH + 0.05, BAR_HEIGHT + 0.05),
    new THREE.MeshBasicMaterial({ color: 0x14131a, transparent: true, opacity: 0.85 }),
  );

  const fill = new THREE.Mesh(
    new THREE.PlaneGeometry(BAR_WIDTH, BAR_HEIGHT),
    new THREE.MeshBasicMaterial({ color: 0x7fae56 }),
  );
  fill.position.z = 0.001;

  group.add(backing, fill);
  // Drawn last and never occluded: a bar hidden behind the sarcophagus is worse than no bar.
  group.traverse((child) => {
    const mesh = child as THREE.Mesh;
    if (mesh.isMesh) {
      (mesh.material as THREE.Material).depthTest = false;
      mesh.renderOrder = 10;
    }
  });
  return group;
}

/** Every model the scene needs loaded before the first token is built. */
export function characterPaths(): string[] {
  return [...new Set(Object.values(MODELS).map((m) => m.path))];
}
