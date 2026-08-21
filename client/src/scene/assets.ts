import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import * as SkeletonUtils from "three/examples/jsm/utils/SkeletonUtils.js";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";

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

// ---- Wall pieces ----

/**
 * How deep a wall stands, in squares, and how far its inner face is from the centre of the
 * square in front of it.
 *
 * <p>Anything that mounts on a wall needs the second number, and it moves when the wall kit
 * does: Kenney's slab is 0.50 deep and the ruins wall is 0.14, which shifted the face by most
 * of a fifth of a square when the perimeter was swapped over. The alcove was built against the
 * old figure and came away from the stone the moment the walls changed.
 */
export const WALL_DEPTH = 0.14;
export const WALL_FACE = 0.5 - WALL_DEPTH / 2;

/**
 * A kit piece flattened for instancing, keeping every material it was authored with.
 *
 * <p>{@link loadKitPiece} takes the first mesh and the first material, which is fine for
 * Kenney's walls — one mesh, one material. The Quaternius pieces are one mesh split into a
 * primitive per material (`ruins/Wall` has two, `Window_Bars` three, `dungeon/Arch_Door`
 * four), and glTF loads each primitive as its own mesh, so taking the first would throw away
 * the highlight courses, the ironwork, and most of a door.
 */
export interface WallPiece {
  geometry: THREE.BufferGeometry;
  materials: THREE.Material[];
}

/**
 * Loads a wall piece and merges its primitives into one grouped geometry.
 *
 * <p>Merging with groups is what keeps this a single draw call per variant. A room's perimeter
 * is forty to sixty segments; one `InstancedMesh` per variant with a material array draws each
 * variant once, where a cloned object per segment would cost a call apiece.
 *
 * @param fitHeight when set, scale so the piece stands exactly this tall instead of using its
 *                  own module — how the two-square dungeon arches are brought down to the
 *                  height of a one-square wall
 */
export async function loadWallPiece(
  path: string,
  module: number,
  fitHeight?: number,
): Promise<WallPiece> {
  const gltf = await loader.loadAsync(`/assets/kits/${path}.glb`);
  gltf.scene.updateWorldMatrix(true, true);

  const geometries: THREE.BufferGeometry[] = [];
  const materials: THREE.Material[] = [];
  gltf.scene.traverse((child) => {
    const mesh = child as THREE.Mesh;
    if (!mesh.isMesh) return;
    // Bake the node transform in: FBX and glTF exporters both park scale and rotation on
    // nodes, and a merged geometry has no nodes left to carry it.
    const geometry = mesh.geometry.clone().applyMatrix4(mesh.matrixWorld);
    geometries.push(geometry);
    materials.push((Array.isArray(mesh.material) ? mesh.material[0] : mesh.material).clone());
  });

  if (geometries.length === 0) throw new Error(`No mesh in wall piece: ${path}`);

  const merged = mergeGeometries(geometries, true);
  if (!merged) throw new Error(`Could not merge wall piece: ${path}`);

  let scale = 1 / module;
  if (fitHeight !== undefined) {
    merged.computeBoundingBox();
    const native = merged.boundingBox!.max.y - merged.boundingBox!.min.y;
    scale = fitHeight / Math.max(native, 1e-6);
  }
  merged.scale(scale, scale, scale);

  return { geometry: merged, materials };
}

/**
 * Repaints one piece's stone to match another's, by material name.
 *
 * <p>The dungeon kit's stone runs warm (#6d604f) and the ruins kit's neutral (#636459), at
 * near-identical brightness — so a run mixing them stripes rather than blends. Both kits are
 * untextured flat colour, which is the only reason this is a colour assignment and not a
 * texture job. The source palette is read off a loaded piece rather than written down here:
 * a hard-coded hex would have to guess at the renderer's colour space, and a copied
 * {@link THREE.Color} cannot.
 */
export function retintStone(target: WallPiece, palette: Map<string, THREE.Color>): void {
  for (const material of target.materials) {
    const named = material as THREE.MeshStandardMaterial;
    const replacement =
      palette.get(STONE_ALIASES[named.name] ?? "") ?? palette.get(named.name);
    if (replacement) named.color.copy(replacement);
  }
}

/** Which of the dungeon kit's stone materials stands in for which of the ruins kit's. */
const STONE_ALIASES: Record<string, string> = {
  Wall_Dark: "Main",
  Wall_Medium: "Main",
  Wall_Highlights: "Highlights",
};

/** The named colours a piece was authored with, for feeding {@link retintStone}. */
export function paletteOf(piece: WallPiece): Map<string, THREE.Color> {
  const palette = new Map<string, THREE.Color>();
  for (const material of piece.materials) {
    const named = material as THREE.MeshStandardMaterial;
    if (named.color) palette.set(named.name, named.color.clone());
  }
  return palette;
}

// ---- Props ----

const PROP_BASE = "/assets/kits/props";

const propModels = new Map<string, THREE.Group>();

/**
 * Loads a prop model once and caches it. `path` is pack-relative, e.g. `ruins/Column_Round`.
 *
 * <p>Preloaded rather than fetched on demand for the same reason characters are: `addProp` is
 * called from a diff and has to put the object on the board on the frame it lands.
 */
export async function loadPropModel(path: string): Promise<void> {
  if (propModels.has(path)) return;
  const gltf = await loader.loadAsync(`${PROP_BASE}/${path}.glb`);
  propModels.set(path, gltf.scene);
}

/**
 * A fresh copy, scaled to fit a square and standing on the floor.
 *
 * <p>Scale is derived from the model's own bounding box rather than hard-coded, so swapping
 * `ruins/Column_Round` for `dungeon/Column` is a one-line change and not a re-measurement —
 * the same argument `tokens.ts` makes for character heights. It matters more here than there:
 * the four prop packs disagree about scale (see `LICENSES.md`), and a literal factor tuned
 * against one of them is wrong for the other three.
 *
 * <p>`footprint` caps width and depth so a prop cannot spill into a neighbouring square. The
 * spatial validator guarantees one prop per square, and a mesh that overhangs makes that
 * guarantee look like a bug.
 */
export function instanceProp(
  path: string,
  height: number,
  footprint: number,
): THREE.Object3D | null {
  const model = propModels.get(path);
  if (!model) return null;

  const copy = model.clone(true);
  const bounds = new THREE.Box3().setFromObject(copy);
  const size = bounds.getSize(new THREE.Vector3());

  const scale = Math.min(
    height / Math.max(size.y, 1e-6),
    footprint / Math.max(size.x, size.z, 1e-6),
  );
  copy.scale.setScalar(scale);

  // Centre on the square and sit the base on the floor. The renderer owns the group's own
  // position, so the offset goes on the child — writing it to the group would be overwritten.
  copy.position.set(
    -((bounds.min.x + bounds.max.x) / 2) * scale,
    -bounds.min.y * scale,
    -((bounds.min.z + bounds.max.z) / 2) * scale,
  );

  const group = new THREE.Group();
  group.add(copy);
  return group;
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
