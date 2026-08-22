import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import * as SkeletonUtils from "three/examples/jsm/utils/SkeletonUtils.js";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";

const loader = new GLTFLoader();

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
 * <p>Taking the first mesh and the first material would do for Kenney's walls — one mesh, one
 * material. The Quaternius pieces are one mesh split into a
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
 * Loads a floor tile and drops it so the surface you walk on is exactly y = 0.
 *
 * <p>Everything else in the renderer takes y = 0 as the floor and always has, because Kenney's
 * `template-floor` is a plane with no thickness sitting exactly there. The ruins tiles are real
 * slabs — around a sixth of a square thick, and authored straddling the origin so their top face
 * lands a little above it. Left alone that is a tile whose surface is above the ground plane the
 * picker ray hits, above the y the props and tokens stand at, and within a hundredth of a unit of
 * the movement overlay, which is close enough to z-fight and at 480px a z-fight is a strobe.
 *
 * <p>Baked into the geometry rather than fixed with a group offset, for the same reason the kit
 * scale is: instance matrices stay pure translation and rotation, and nothing downstream has to
 * know the floor is made of slabs now.
 */
export async function loadFloorPiece(path: string, module: number): Promise<WallPiece> {
  const piece = await loadWallPiece(path, module);
  piece.geometry.computeBoundingBox();
  piece.geometry.translate(0, -piece.geometry.boundingBox!.max.y, 0);
  return piece;
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

/**
 * Repaints a piece's stone to colours named here rather than copied from another piece.
 *
 * <p>{@link retintStone} matches one kit to another and is the right tool when the target is a
 * colour something else already is. The floor is the opposite problem: it has to be a colour
 * nothing else in the room is. Ruins floor and ruins wall are the same stone down to the hex, so
 * a room built from both is one continuous grey sheet with a fold in it where the wall starts.
 *
 * <p>Both kits are untextured flat colour, which is the only reason this is four numbers and not
 * a texture job.
 */
export function paintStone(piece: WallPiece, colours: Record<string, number>): void {
  for (const material of piece.materials) {
    const named = material as THREE.MeshStandardMaterial;
    const colour = colours[named.name];
    // setHex reads sRGB and converts into the renderer's working space, so these are the hexes
    // you would type into a colour picker rather than linear values.
    if (colour !== undefined) named.color.setHex(colour);
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

/**
 * FNV-1a. Any stable hash would do; what matters is that it is stable.
 *
 * <p>Every choice the client makes for itself — which wall face a segment gets, which tile a
 * floor square gets, which of a prop's models it is built from — has to come back the same on a
 * reconnect, and `Math.random` would reshuffle the room mid-session. The room id is the only
 * stable seed the client has: the generator's own seed never crosses the wire.
 */
export function hash32(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
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
