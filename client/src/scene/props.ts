import * as THREE from "three";
import { hash32, instanceProp, WALL_FACE } from "./assets";
import type { Prop, PropType } from "../types";

/**
 * Prop meshes: real models where a pack has one, procedural primitives where none does.
 *
 * <p>Kenney's Modular Dungeon Kit is architecture only — corridors, rooms, gates, stairs. It
 * ships no sarcophagus, brazier or pillar, so all six props started as primitives composed
 * here. The Quaternius packs (see `assets/kits/props/LICENSES.md`) cover three of them
 * properly, and those three now load real geometry; the rest still build from primitives.
 *
 * <p>The seam is `MESH_PROPS`. Promoting a prop is one table entry, and demoting it — because
 * a model reads badly at 480x270, say — is deleting one. Neither touches the scene schema.
 */

const STONE = 0x6b6459;
const STONE_DARK = 0x4a453d;
const IRON = 0x2e2b28;
const EMBER = 0x63d18a; // the braziers burn green

/** Candela, not a 0-1 factor. Point lights decay physically, so this is deliberately large. */
export const FLAME_INTENSITY = 26;

/**
 * Finished heights, as a fraction of the wall — which stands exactly one square.
 *
 * <p>These props were all authored at close to full wall height back when the perimeter was a
 * smooth Kenney slab with nothing on it to judge scale by. The ruins wall is laid in courses,
 * and beside real masonry a coffin as tall as the room's wall reads as a shipping container.
 * Each builder still composes at its original size and is brought down here, so the numbers
 * inside them stay the ones that were tuned by eye.
 */
const TOMB_HEIGHT = 0.58;
const BRAZIER_HEIGHT = 0.65;
const ALCOVE_HEIGHT = 0.73;

type Builder = (prop: Prop) => THREE.Object3D;

/**
 * The tomb. Still built from primitives, and deliberately so.
 *
 * <p>None of the four imported packs ships a sarcophagus — they are dungeon furniture and
 * ruined architecture, and the nearest things in them are a pedestal and a bed. Substituting
 * one of those would put the wrong object at the centre of the room. A sarcophagus is a stack
 * of stone boxes, which is a shape primitives describe exactly, so what it needed was not a
 * model but proportion and detail.
 *
 * <p>Four things make it read as a tomb rather than a crate. It is tiered, so the silhouette
 * steps rather than going straight up from the floor. The body tapers toward the top, which is
 * what stops it looking like packaging. There is a void under the lid, so the gap the lid
 * leaves is black instead of showing more stone. And the lid is shifted, turned and tilted far
 * enough to see, because "something has been working at it from the inside" is the sentence on
 * the title screen and it should be legible in the object itself.
 */
function sarcophagus(): THREE.Object3D {
  const group = new THREE.Group();

  const darkStone = new THREE.MeshStandardMaterial({ color: STONE_DARK, roughness: 0.95 });
  const stone = new THREE.MeshStandardMaterial({ color: STONE, roughness: 0.9 });
  const lidStone = new THREE.MeshStandardMaterial({ color: 0x7f776a, roughness: 0.85 });

  // Two plinth tiers. One box on the floor reads as a crate set down; a step reads as built.
  const base = new THREE.Mesh(new THREE.BoxGeometry(1.28, 0.13, 2.16), darkStone);
  base.position.y = 0.065;
  const step = new THREE.Mesh(new THREE.BoxGeometry(1.1, 0.1, 1.98), darkStone);
  step.position.y = 0.18;

  // Tapered chest. A four-sided cylinder is a box with a slope on it: turned an eighth turn
  // its faces square up to the grid, and the scale makes the square cross-section oblong.
  const body = new THREE.Mesh(new THREE.CylinderGeometry(0.62, 0.7, 0.54, 4), stone);
  body.rotation.y = Math.PI / 4;
  body.scale.set(0.98, 1, 1.88);
  body.position.y = 0.5;

  // The inside, so the gap under the shifted lid is darkness and not another slab of granite.
  const hollow = new THREE.Mesh(
    new THREE.BoxGeometry(0.84, 0.2, 1.72),
    new THREE.MeshStandardMaterial({ color: 0x090807, roughness: 1 }),
  );
  hollow.position.y = 0.8;

  // Lid and its carving move together, so the whole slab reads as one thing being pushed.
  const lid = new THREE.Group();
  const slab = new THREE.Mesh(new THREE.BoxGeometry(1.06, 0.16, 1.94), lidStone);
  const ridge = new THREE.Mesh(new THREE.BoxGeometry(0.46, 0.07, 1.36), lidStone);
  ridge.position.y = 0.115;
  lid.add(slab, ridge);

  // Shoved toward the foot and lifted at one corner. The old version moved it 0.04 and turned
  // it 0.03 radians, which at this render size is not a disturbed lid, it is a rounding error.
  lid.position.set(0.1, 0.88, -0.14);
  lid.rotation.set(0, 0.06, 0.022);

  group.add(base, step, body, hollow, lid);

  // A skull from the dungeon kit, set proud at the head of the lid. Detail at this size has to
  // be a shape and a value, never a texture: at 480x270 this is one pale blob, and one pale
  // blob in the right place is the difference between a stone box and a grave.
  const skull = instanceProp(SKULL_MODEL, 0.15, 0.22);
  if (skull) {
    // Lid-local, not world: the lid group is already lifted to the top of the chest, so the
    // skull only has to clear the slab's own half-thickness. Reading 0.96 off the world height
    // and setting it here floated the skull a full unit over the tomb.
    //
    // Past the end of the effigy ridge rather than on top of it, and at the end the party walks
    // toward — this is the face of the thing you see on approach.
    skull.position.set(0, 0.08, 0.8);
    skull.rotation.y = Math.PI;

    // Repainted rather than used as shipped. The kit's bone is a mid brown that all but
    // disappears against this stone in a DIM room, and the whole job of this shape is to be
    // the one light value on the lid. A fresh material, not an edit of the kit's: clone()
    // shares material references, so tinting in place would repaint every skull ever loaded.
    const bone = new THREE.MeshStandardMaterial({ color: 0xc4bba4, roughness: 0.85 });
    skull.traverse((child) => {
      if ((child as THREE.Mesh).isMesh) (child as THREE.Mesh).material = bone;
    });
    lid.add(skull);
  }

  // Waist-high on the fighter rather than shoulder-high on the wall.
  group.scale.setScalar(TOMB_HEIGHT);
  return group;
}

function brazier(): THREE.Object3D {
  const group = new THREE.Group();

  const stem = new THREE.Mesh(
    new THREE.CylinderGeometry(0.08, 0.16, 0.7, 8),
    new THREE.MeshStandardMaterial({ color: IRON, roughness: 0.7, metalness: 0.4 }),
  );
  stem.position.y = 0.35;

  const bowl = new THREE.Mesh(
    new THREE.CylinderGeometry(0.3, 0.16, 0.25, 10),
    new THREE.MeshStandardMaterial({ color: IRON, roughness: 0.6, metalness: 0.5 }),
  );
  bowl.position.y = 0.8;

  const coals = new THREE.Mesh(
    new THREE.SphereGeometry(0.2, 10, 6, 0, Math.PI * 2, 0, Math.PI / 2),
    new THREE.MeshStandardMaterial({
      color: EMBER,
      emissive: EMBER,
      emissiveIntensity: 0.85,
      roughness: 1,
    }),
  );
  coals.position.y = 0.86;

  // The light that actually lights the room. Flicker is applied per-frame by the renderer.
  // Intensity is candela: with decay 2 the falloff is physical, so this number is large.
  const light = new THREE.PointLight(EMBER, FLAME_INTENSITY, 16, 2);
  light.position.y = 1.0;
  light.name = "flame";
  light.userData.baseIntensity = FLAME_INTENSITY;
  light.castShadow = true;

  group.add(stem, bowl, coals, light);
  // Scales the bowl and the height the flame sits at. A light's range is world units and is
  // not touched by a parent's scale, which is what we want — the brazier gets smaller, the
  // room it lights does not.
  group.scale.setScalar(BRAZIER_HEIGHT);
  return group;
}

function pillar(): THREE.Object3D {
  const group = new THREE.Group();
  const mat = new THREE.MeshStandardMaterial({ color: STONE, roughness: 0.95 });

  const base = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.16, 0.9), mat);
  base.position.y = 0.08;

  const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.3, 0.34, 2.1, 8), mat);
  shaft.position.y = 1.2;

  const cap = new THREE.Mesh(new THREE.BoxGeometry(0.9, 0.18, 0.9), mat);
  cap.position.y = 2.34;

  group.add(base, shaft, cap);
  return group;
}

function rubble(): THREE.Object3D {
  const group = new THREE.Group();
  const mat = new THREE.MeshStandardMaterial({ color: STONE_DARK, roughness: 1 });

  // Deterministic scatter — the same room should look the same on every load.
  const chunks: Array<[number, number, number, number]> = [
    [0.0, 0.14, 0.0, 0.34],
    [0.3, 0.09, -0.2, 0.22],
    [-0.28, 0.07, 0.24, 0.18],
    [0.14, 0.05, 0.34, 0.13],
    [-0.18, 0.05, -0.3, 0.12],
  ];

  for (const [x, y, z, size] of chunks) {
    const chunk = new THREE.Mesh(new THREE.DodecahedronGeometry(size, 0), mat);
    chunk.position.set(x, y, z);
    chunk.rotation.set(x * 9, y * 13, z * 7);
    group.add(chunk);
  }
  return group;
}

/**
 * A recess cut into the wall — or as close to one as an opaque wall allows.
 *
 * <p>This used to be a near-black box standing on the floor square, and once placement started
 * putting alcoves against walls where they belong, it read as a slab leaning on the stone
 * rather than a hole in it. Two things were wrong. It stood in the middle of its square, half
 * a unit clear of the wall; and at 1.2 units tall it was taller than the wall it was supposedly
 * cut into, which is 1.04.
 *
 * <p>The wall cannot actually be pierced — it is one instanced mesh and there is no CSG here —
 * so the recess is faked the way low-poly kits always fake it: a very dark panel laid on the
 * stone, with a frame standing proud of it. The depth on the frame is what sells it. A flat
 * dark rectangle reads as something painted on the wall; the same rectangle behind an inch of
 * jamb and lintel reads as a hole, because the frame casts and catches light on its edges.
 *
 * <p>Local +Z is into the wall, and the face it mounts on is {@link WALL_FACE} away — half a
 * square in to the wall plane, less half the wall's depth. That figure belongs to the wall kit
 * rather than to this file, which is why it is imported: hard-coding it here is what left the
 * alcove floating when the perimeter changed kits.
 */
function alcove(): THREE.Object3D {
  const group = new THREE.Group();

  // Built at its original size in an inner group and shrunk as a whole, so every number below
  // stays the one that was tuned by eye. The mounting depth cannot ride along: the recess has
  // to stay on the wall's face however small it gets, so the face is divided by the scale here
  // and multiplied back out by it below.
  const inner = new THREE.Group();
  inner.scale.setScalar(ALCOVE_HEIGHT);
  group.add(inner);

  /** The inner face of the wall, in this prop's local space. */
  const FACE = WALL_FACE / ALCOVE_HEIGHT;
  const LAMP = 0xd9b271;

  const frameMat = new THREE.MeshStandardMaterial({ color: STONE, roughness: 0.95 });
  const voidMat = new THREE.MeshStandardMaterial({ color: 0x0b0a09, roughness: 1 });

  const back = new THREE.Mesh(new THREE.BoxGeometry(0.6, 0.64, 0.04), voidMat);
  back.position.set(0, 0.56, FACE - 0.02);

  const jambLeft = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.74, 0.17), frameMat);
  jambLeft.position.set(-0.35, 0.56, FACE - 0.085);
  const jambRight = jambLeft.clone();
  jambRight.position.x = 0.35;

  const lintel = new THREE.Mesh(new THREE.BoxGeometry(0.8, 0.11, 0.19), frameMat);
  lintel.position.set(0, 0.93, FACE - 0.09);

  const sill = new THREE.Mesh(new THREE.BoxGeometry(0.8, 0.09, 0.22), frameMat);
  sill.position.set(0, 0.2, FACE - 0.1);

  // A votive lamp on the sill. The glow is the point: a dark recess with nothing burning in it
  // is indistinguishable from a shadow at this resolution.
  const lamp = new THREE.Mesh(
    new THREE.CylinderGeometry(0.075, 0.1, 0.12, 8),
    new THREE.MeshStandardMaterial({
      color: LAMP,
      emissive: LAMP,
      emissiveIntensity: 0.9,
      roughness: 0.8,
    }),
  );
  lamp.position.set(0, 0.31, FACE - 0.11);

  // Deliberately short-range and dim. It exists to pick out the jambs and the back of the
  // recess, not to light the room — that is the torches' job, and there are already up to
  // fourteen lights in a room before this one is counted.
  const glow = new THREE.PointLight(LAMP, 4, 2.4, 2);
  glow.position.set(0, 0.45, FACE - 0.14);

  inner.add(back, jambLeft, jambRight, lintel, sill, lamp, glow);
  return group;
}

function door(): THREE.Object3D {
  const group = new THREE.Group();

  const frame = new THREE.Mesh(
    new THREE.BoxGeometry(1.5, 2.3, 0.24),
    new THREE.MeshStandardMaterial({ color: STONE_DARK, roughness: 0.95 }),
  );
  frame.position.y = 1.15;

  const slab = new THREE.Mesh(
    new THREE.BoxGeometry(1.15, 2.0, 0.3),
    new THREE.MeshStandardMaterial({ color: 0x5a5249, roughness: 0.9 }),
  );
  slab.position.y = 1.0;

  const bandMat = new THREE.MeshStandardMaterial({
    color: 0x3d3630,
    roughness: 0.5,
    metalness: 0.6,
  });
  for (const y of [0.45, 1.55]) {
    const band = new THREE.Mesh(new THREE.BoxGeometry(1.2, 0.14, 0.34), bandMat);
    band.position.y = y;
    group.add(band);
  }

  group.add(frame, slab);
  return group;
}

const BUILDERS: Record<PropType, Builder> = {
  SARCOPHAGUS: sarcophagus,
  BRAZIER: brazier,
  PILLAR: pillar,
  RUBBLE: rubble,
  ALCOVE: alcove,
  DOOR: door,
};

/** One model a prop may be built from. Units are world units, one to a grid square. */
export interface PropModel {
  path: string;
  height: number;
  footprint: number;
}

/**
 * Prop types that load real models instead of building one, and the models each may draw from.
 *
 * <p>`height` and `footprint` are per model rather than per type, because the kit's pieces are
 * not interchangeable at one size — a snapped-off column brought up to the height of a whole one
 * is not a broken pillar, it is a thin pillar. Each entry is sized so its silhouette stays inside
 * the square the validator reserved for it, which is what keeps a promotion invisible to the
 * fight: the same amount of room is taken up before and after.
 *
 * <p>Which model a given prop gets is hashed from the room and the prop's id, so three pillars in
 * one room are three different pillars and all three come back the same way on a reconnect.
 *
 * <p>Every variant of a type has to be the same noun. The DM is told a prop is RUBBLE and writes
 * the word "rubble" into the narration, so a broken amphora standing where the map says rubble is
 * the picture contradicting the narrator — which is the one thing this build is least willing to
 * spend. That is why the pots and the fallen wall sections are not in here, and why **rubble has
 * just the one model**: `Bricks` is the only piece in four packs that is unambiguously a pile of
 * broken stone. Its quarter-turn from the placer is the variety it gets.
 *
 * <p>The three absentees are absent for a reason. No pack ships a **sarcophagus**, which is the
 * one prop M0's script turns on. A **brazier** is a standing fire bowl and the nearest models are
 * a ground campfire and a wall torch, neither of which is the same object. An **alcove** is a
 * recess cut into a wall, so it is architecture rather than a prop, and it cannot be dropped onto
 * a floor square as a mesh without the wall around it.
 */
export const MESH_PROPS: Partial<Record<PropType, PropModel[]>> = {
  // Round, square, and one that has lost its top half. The short one keeps the kit's own
  // proportions — the same 0.4 the whole columns are brought down by — so it reads as the same
  // stone snapped off rather than as a different, stubbier order of column.
  PILLAR: [
    { path: "ruins/Column_Round", height: 1.6, footprint: 0.9 },
    { path: "ruins/Column_Square", height: 1.6, footprint: 0.9 },
    { path: "ruins/Column_Round_Short", height: 0.73, footprint: 0.9 },
  ],
  // Round and gothic arches, each with a plainer boarded version. All four are a shut door in a
  // stone surround, which is the only thing a door in this build is allowed to be — there is
  // nothing on the other side of it in M1.
  DOOR: [
    { path: "ruins/Doors_RoundArch", height: 0.9, footprint: 1.4 },
    { path: "ruins/Doors_GothicArch", height: 0.9, footprint: 1.4 },
    { path: "ruins/Doors_RoundArch_Covered", height: 0.9, footprint: 1.4 },
    { path: "ruins/Doors_GothicArch_Covered", height: 0.9, footprint: 1.4 },
  ],
  RUBBLE: [{ path: "ruins/Bricks", height: 0.5, footprint: 0.85 }],
};

/**
 * The wall torch, which is deliberately not a prop.
 *
 * <p>`LightingPreset` is already server-authoritative, and a wall torch is how a TORCHLIT room
 * gets drawn — the same relationship the ambient colour and key light already have to it.
 * Making torches props instead would put a dozen more objects on the board for the DM to
 * describe and the validator to check, to say something the lighting preset already says.
 *
 * <p>Nothing about them is load-bearing: they occupy no square, block no movement, and appear
 * in no diff. Delete this and the room is dimmer, not broken.
 */
const TORCH_MODEL = "ruins/Torch";

/** Carved into the head of the sarcophagus lid. See {@link sarcophagus}. */
const SKULL_MODEL = "dungeon/Skull";

/** Candela, like {@link FLAME_INTENSITY} — lower, because a room holds many more of them. */
export const TORCH_INTENSITY = 9;

/** How tall a mounted torch stands, and how far its light carries. */
const TORCH_HEIGHT = 0.65;
const TORCH_RANGE = 7;

/** Every model the renderer must preload before the first scene. */
export function propModelPaths(): string[] {
  return [
    ...Object.values(MESH_PROPS).flatMap((models) => models.map((m) => m.path)),
    TORCH_MODEL,
    SKULL_MODEL,
  ];
}

/** One wall torch, lit or unlit. Null if its model never loaded. */
export function buildWallTorch(lit: boolean): THREE.Object3D | null {
  const torch = instanceProp(TORCH_MODEL, TORCH_HEIGHT, 0.4);
  if (!torch) return null;

  torch.traverse((child) => {
    if ((child as THREE.Mesh).isMesh) child.castShadow = true;
  });

  if (lit) {
    // The head has to glow, not just emit. A point light inside an unlit model silhouettes it:
    // the wall behind lights up and the torch itself reads as a dark stick. The brazier already
    // solved this with its emissive coals, and at 480x270 that glowing blob is the whole
    // difference between "a torch" and "a mark on the wall".
    const flame = new THREE.Mesh(
      new THREE.SphereGeometry(0.075, 8, 6),
      new THREE.MeshStandardMaterial({
        color: EMBER,
        emissive: EMBER,
        emissiveIntensity: 1.1,
        roughness: 1,
      }),
    );
    flame.position.y = TORCH_HEIGHT * 0.94;
    torch.add(flame);

    const light = new THREE.PointLight(EMBER, TORCH_INTENSITY, TORCH_RANGE, 2);
    light.position.y = TORCH_HEIGHT;
    light.name = "flame";
    // Read back by the flicker loop, which cannot assume every flame burns as hard as a
    // brazier does.
    light.userData.baseIntensity = TORCH_INTENSITY;
    torch.add(light);
  }
  return torch;
}

/**
 * Builds one prop.
 *
 * @param roomId seeds the choice of model, together with the prop's own id. Both are needed:
 *               the prop id alone would give every room's third pillar the same shaft, and the
 *               room id alone would give one room's pillars all the same one.
 */
export function buildProp(prop: Prop, roomId: string): THREE.Object3D {
  const models = MESH_PROPS[prop.type];
  const mesh = models?.[hash32(`${roomId}:prop:${prop.id}`) % models.length];
  // A model that failed to load falls back to its primitive rather than leaving a hole in the
  // room — the same degradation `Renderer.init` gives a missing character.
  const object =
    (mesh && instanceProp(mesh.path, mesh.height, mesh.footprint)) || BUILDERS[prop.type](prop);
  object.rotation.y = THREE.MathUtils.degToRad(prop.rotation);
  object.name = `prop:${prop.id}`;
  object.traverse((child) => {
    if ((child as THREE.Mesh).isMesh) {
      child.castShadow = true;
      child.receiveShadow = true;
    }
  });
  return object;
}
