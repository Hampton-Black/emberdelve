import * as THREE from "three";
import { instanceProp } from "./assets";
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

type Builder = (prop: Prop) => THREE.Object3D;

function sarcophagus(): THREE.Object3D {
  const group = new THREE.Group();

  const dais = new THREE.Mesh(
    new THREE.BoxGeometry(1.5, 0.12, 2.2),
    new THREE.MeshStandardMaterial({ color: STONE_DARK, roughness: 0.95 }),
  );
  dais.position.y = 0.06;

  const body = new THREE.Mesh(
    new THREE.BoxGeometry(1.0, 0.5, 1.8),
    new THREE.MeshStandardMaterial({ color: STONE, roughness: 0.9 }),
  );
  body.position.y = 0.37;

  // Lid, tapered and set slightly askew — something has been working at it from inside.
  const lid = new THREE.Mesh(
    new THREE.BoxGeometry(1.08, 0.16, 1.88),
    new THREE.MeshStandardMaterial({ color: 0x7a7266, roughness: 0.85 }),
  );
  lid.position.set(0.04, 0.7, 0);
  lid.rotation.y = 0.03;

  group.add(dais, body, lid);
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
  light.castShadow = true;

  group.add(stem, bowl, coals, light);
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

function alcove(): THREE.Object3D {
  const group = new THREE.Group();

  const recess = new THREE.Mesh(
    new THREE.BoxGeometry(0.7, 0.9, 0.3),
    new THREE.MeshStandardMaterial({ color: 0x14120f, roughness: 1 }),
  );
  recess.position.y = 0.75;

  const lamp = new THREE.Mesh(
    new THREE.CylinderGeometry(0.1, 0.13, 0.14, 8),
    new THREE.MeshStandardMaterial({
      color: 0xd9b271,
      emissive: 0xd9b271,
      emissiveIntensity: 0.7,
      roughness: 0.8,
    }),
  );
  lamp.position.set(0, 0.5, 0.02);

  const cloth = new THREE.Mesh(
    new THREE.BoxGeometry(0.34, 0.16, 0.2),
    new THREE.MeshStandardMaterial({ color: 0x1d1a18, roughness: 1 }),
  );
  cloth.position.set(0, 0.95, 0.02);

  const glow = new THREE.PointLight(0xd9b271, 6, 5, 2);
  glow.position.set(0, 0.7, 0.3);

  group.add(recess, lamp, cloth, glow);
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

/**
 * Prop types that load a real model instead of building one.
 *
 * <p>`height` and `footprint` are world units, one unit to a grid square. They are set to the
 * silhouette each primitive already occupied, so promoting a prop changes what it is made of
 * and not how much room it takes up — the fight reads the same before and after.
 *
 * <p>The three absentees are absent for a reason. No pack ships a **sarcophagus**, which is
 * the one prop M0's script turns on. A **brazier** is a standing fire bowl and the nearest
 * models are a ground campfire and a wall torch, neither of which is the same object. An
 * **alcove** is a recess cut into a wall, so it is architecture rather than a prop, and it
 * cannot be dropped onto a floor square as a mesh without the wall around it.
 */
const MESH_PROPS: Partial<Record<PropType, { path: string; height: number; footprint: number }>> = {
  PILLAR: { path: "ruins/Column_Round", height: 2.4, footprint: 0.9 },
  DOOR: { path: "ruins/Doors_RoundArch", height: 2.3, footprint: 1.4 },
  RUBBLE: { path: "ruins/Bricks", height: 0.5, footprint: 0.85 },
};

/** Every model the prop table needs, for the renderer to preload before the first scene. */
export function propModelPaths(): string[] {
  return Object.values(MESH_PROPS).map((m) => m.path);
}

export function buildProp(prop: Prop): THREE.Object3D {
  const mesh = MESH_PROPS[prop.type];
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
