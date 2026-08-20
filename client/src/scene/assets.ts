import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import * as SkeletonUtils from "three/examples/jsm/utils/SkeletonUtils.js";

/**
 * Kenney's Modular Dungeon Kit is authored on a 4-unit module grid (template-floor.glb
 * measures exactly 4 x 0 x 4, origin-centred). Scaling by 1/4 makes one game square equal
 * one world unit, which keeps every grid calculation in the renderer integer-friendly.
 */
export const KIT_SCALE = 0.25;

const KIT_BASE = "/assets/kits/dungeon";

const loader = new GLTFLoader();

/** Geometry + material pulled out of a kit model, ready to instance. */
export interface KitPiece {
  geometry: THREE.BufferGeometry;
  material: THREE.Material;
}

/**
 * Loads a kit model and flattens it to a single geometry/material pair.
 *
 * <p>Instancing matters here: a 12x12 floor is 144 tiles, and 144 separate meshes is 144 draw
 * calls for something that is one repeated rock. Merging to a single InstancedMesh keeps it at one.
 */
export async function loadKitPiece(name: string): Promise<KitPiece> {
  const gltf = await loader.loadAsync(`${KIT_BASE}/${name}.glb`);

  let found: THREE.Mesh | null = null;
  gltf.scene.traverse((child) => {
    if (!found && (child as THREE.Mesh).isMesh) found = child as THREE.Mesh;
  });

  if (!found) throw new Error(`No mesh in kit model: ${name}`);
  const mesh = found as THREE.Mesh;

  // Bake the kit scale into the geometry so instance matrices stay pure translation/rotation.
  const geometry = mesh.geometry.clone();
  geometry.scale(KIT_SCALE, KIT_SCALE, KIT_SCALE);

  const material = Array.isArray(mesh.material) ? mesh.material[0] : mesh.material;
  return { geometry, material: material.clone() };
}

/** Grid square -> world position, with the room centred on the origin. */
export function toWorld(
  gx: number,
  gy: number,
  width: number,
  height: number,
): THREE.Vector3 {
  return new THREE.Vector3(
    gx - width / 2 + 0.5,
    0,
    -(gy - height / 2 + 0.5), // +y is north, which is -Z away from the camera
  );
}

// ---- Characters ----

const CHARACTER_BASE = "/assets/kits/characters";

/** A loaded character kit model: the rig plus every clip Kenney ships with it. */
export interface CharacterModel {
  scene: THREE.Group;
  animations: THREE.AnimationClip[];
}

const characters = new Map<string, CharacterModel>();

/**
 * Loads a character once and caches it. `path` is kit-relative, e.g. `mini/character-male-b`.
 *
 * <p>The kit folder matters: every Kenney GLB references `Textures/colormap.png` by relative
 * path, and the mini and graveyard kits ship <em>different</em> colormaps. Serving them from one
 * directory silently renders one kit in the other's palette.
 */
export async function loadCharacter(path: string): Promise<CharacterModel> {
  const cached = characters.get(path);
  if (cached) return cached;

  const gltf = await loader.loadAsync(`${CHARACTER_BASE}/${path}.glb`);
  const model: CharacterModel = { scene: gltf.scene, animations: gltf.animations };
  characters.set(path, model);
  return model;
}

/**
 * A fresh, independently animatable copy. Plain `Object3D.clone()` is wrong here — it shares
 * the skeleton, so two tokens of the same model would animate as one.
 */
export function instanceCharacter(path: string): CharacterModel | null {
  const model = characters.get(path);
  if (!model) return null;
  return { scene: SkeletonUtils.clone(model.scene) as THREE.Group, animations: model.animations };
}
