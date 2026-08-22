import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import {
  hash32,
  loadFloorPiece,
  loadWallPiece,
  paintStone,
  type WallPiece,
} from "./src/scene/assets";
import { FLOOR_STONE, FLOOR_VARIANTS } from "./src/scene/Renderer";
import { MESH_PROPS } from "./src/scene/props";
import type { FloorType, PropType } from "./src/types";

/**
 * A contact sheet for the two things the room is mostly made of: the floor under everything and
 * the models a prop may be built from.
 *
 * <p>Not part of the game, and a sibling of `kit-preview.ts` rather than a replacement — that one
 * answers "which wall kit", this one answers "does this mix read as cracked stone" and "are these
 * three columns different enough to be worth three columns". Both questions are otherwise
 * answered by restarting the server, rolling seeds until the right room turns up, and squinting
 * at a dim green screenshot.
 *
 * <p>It imports the renderer's own tables rather than copying them. A preview that shows a
 * hand-kept copy of the floor mix is a preview that will eventually lie.
 */

const MODULE = 2; // Quaternius units per square; see assets/kits/props/LICENSES.md.

/** The game's isometric angle, so silhouettes here match silhouettes there. */
const AZIMUTH = Math.PI / 4;
const ELEVATION = Math.atan(1 / Math.SQRT2);

const gltf = new GLTFLoader();

function isoCamera(half: number, aspect: number, lookAtY: number): THREE.OrthographicCamera {
  const camera = new THREE.OrthographicCamera(
    -half * aspect, half * aspect, half, -half, 0.1, 200);
  const r = 40;
  camera.position.set(
    Math.cos(ELEVATION) * Math.sin(AZIMUTH) * r,
    Math.sin(ELEVATION) * r,
    Math.cos(ELEVATION) * Math.cos(AZIMUTH) * r,
  );
  camera.lookAt(0, lookAtY, 0);
  return camera;
}

/** Roughly the room's own light, so a piece is judged in something like play conditions. */
function lit(): THREE.Scene {
  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0x3c3a52, 1.7));
  const key = new THREE.DirectionalLight(0xffe9c4, 1.6);
  key.position.set(4, 7, 5);
  key.castShadow = true;
  scene.add(key);
  const fill = new THREE.DirectionalLight(0x8fb0d8, 0.55);
  fill.position.set(-5, 3, -4);
  scene.add(fill);
  return scene;
}

// ---- Candidate floors, against the wall ----------------------------------------------

/**
 * Every one-square floor tile in the repo, from all three usable packs.
 *
 * <p>Each is laid as a patch with a run of the game's actual perimeter wall behind it, because
 * the question a floor has to answer is not "is this a nice tile" but "does the room still read
 * as a floor and a wall, or as one continuous grey". The ruins floors fail that second question:
 * they are the same kit as the walls and share its palette exactly, so the join disappears.
 *
 * <p>FreeSample is left out. It ships one floor, it is a 2.5-unit module against everything
 * else's 2 or 4 — and it still has no licence (see `assets/kits/props/LICENSES.md`).
 */
const CANDIDATES: Array<{ path: string; module: number; pack: string }> = [
  { path: "props/ruins/Floor_Squares", module: 2, pack: "ruins" },
  { path: "props/ruins/Floor_Diamond", module: 2, pack: "ruins" },
  { path: "props/ruins/Floor_Standard", module: 2, pack: "ruins" },
  { path: "props/ruins/Floor_SquareLarge", module: 2, pack: "ruins" },
  { path: "props/dungeon/Floor_Modular", module: 2, pack: "dungeon" },
  { path: "dungeon/template-floor", module: 4, pack: "kenney" },
  { path: "dungeon/template-floor-detail", module: 4, pack: "kenney" },
  { path: "dungeon/template-floor-detail-a", module: 4, pack: "kenney" },
];

const TILE_CELL = 300;
const TILE_COLS = 4;
const PATCH_SIDE = 4;

async function buildTiles(): Promise<void> {
  const rows = Math.ceil(CANDIDATES.length / TILE_COLS);
  const canvas = document.getElementById("tiles") as HTMLCanvasElement;
  const renderer = new THREE.WebGLRenderer({
    canvas, antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setSize(TILE_COLS * TILE_CELL, rows * TILE_CELL);
  renderer.setScissorTest(true);
  renderer.shadowMap.enabled = true;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const labels = document.getElementById("tileLabels") as HTMLDivElement;
  labels.style.gridTemplateColumns = `repeat(${TILE_COLS}, ${TILE_CELL}px)`;
  labels.style.gridTemplateRows = `repeat(${rows}, ${TILE_CELL}px)`;

  const pieces = await Promise.all(
    CANDIDATES.map((c) => loadFloorPiece(c.path, c.module).catch(() => null)),
  );
  const wall = await loadWallPiece("props/ruins/Wall", MODULE);

  const scene = lit();
  const holder = new THREE.Group();
  scene.add(holder);

  pieces.forEach((piece, i) => {
    const candidate = CANDIDATES[i];
    const cell = document.createElement("div");
    cell.className = "cell";
    labels.appendChild(cell);

    if (!piece) {
      cell.innerHTML = `<b>${candidate.path}</b><i>failed to load</i>`;
      return;
    }

    holder.clear();
    const half = PATCH_SIDE / 2;
    for (let gx = 0; gx < PATCH_SIDE; gx++) {
      for (let gz = 0; gz < PATCH_SIDE; gz++) {
        const tile = new THREE.Mesh(piece.geometry, piece.materials);
        tile.position.set(gx - half + 0.5, 0, gz - half + 0.5);
        tile.receiveShadow = true;
        holder.add(tile);
      }
      // Two walls, so the floor is judged against a corner rather than a single flat backdrop.
      const north = new THREE.Mesh(wall.geometry, wall.materials);
      north.position.set(gx - half + 0.5, 0, -half);
      const west = new THREE.Mesh(wall.geometry, wall.materials);
      west.position.set(-half, 0, gx - half + 0.5);
      west.rotation.y = Math.PI / 2;
      for (const segment of [north, west]) {
        segment.castShadow = true;
        segment.receiveShadow = true;
        holder.add(segment);
      }
    }

    cell.innerHTML =
      `<b>${candidate.path.split("/").pop()}</b><i>${candidate.pack} · ` +
      `module ${candidate.module}</i>`;

    const col = i % TILE_COLS;
    const row = Math.floor(i / TILE_COLS);
    const y = (rows - 1 - row) * TILE_CELL;
    renderer.setViewport(col * TILE_CELL, y, TILE_CELL, TILE_CELL);
    renderer.setScissor(col * TILE_CELL, y, TILE_CELL, TILE_CELL);
    renderer.render(scene, isoCamera(PATCH_SIDE * 0.42, 1, 0.3));
  });
}

// ---- Recolouring the ruins tiles ------------------------------------------------------

/**
 * Candidate floor stones, against the ruins wall's own olive grey.
 *
 * <p>Two ways to separate a floor from the wall above it: drop its value so the wall reads as the
 * lit surface, or push its hue away so the two stones are different rock. Both are here, plus one
 * that does each by half.
 */
const TINTS: Array<{ label: string; note: string; main: number; highlight: number }> = [
  { label: "as authored", note: "the wall's own stone", main: 0x636459, highlight: 0x7a7a6c },
  { label: "darker", note: "same hue, lower value", main: 0x3f4038, highlight: 0x505146 },
  { label: "cold slate", note: "pushed blue, lower value", main: 0x3b4148, highlight: 0x4c5359 },
  { label: "cold and pale", note: "pushed blue, wall-ish value", main: 0x555f66, highlight: 0x6b757c },
];

const TINT_CELL = 300;

async function buildTints(): Promise<void> {
  const canvas = document.getElementById("tints") as HTMLCanvasElement;
  const renderer = new THREE.WebGLRenderer({
    canvas, antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setSize(TINTS.length * TINT_CELL, TINT_CELL);
  renderer.setScissorTest(true);
  renderer.shadowMap.enabled = true;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const labels = document.getElementById("tintLabels") as HTMLDivElement;
  labels.style.gridTemplateColumns = `repeat(${TINTS.length}, ${TINT_CELL}px)`;
  labels.style.gridTemplateRows = `${TINT_CELL}px`;

  const wall = await loadWallPiece("props/ruins/Wall", MODULE);
  const scene = lit();
  const holder = new THREE.Group();
  scene.add(holder);

  for (const [i, tint] of TINTS.entries()) {
    // A fresh load per tint: the materials are what is being recoloured, and a shared piece
    // would have every cell wearing the last cell's stone.
    const piece = await loadFloorPiece("props/ruins/Floor_Squares", MODULE);
    for (const material of piece.materials) {
      const named = material as THREE.MeshStandardMaterial;
      named.color.setHex(named.name === "Highlights" ? tint.highlight : tint.main);
    }

    holder.clear();
    const half = PATCH_SIDE / 2;
    for (let gx = 0; gx < PATCH_SIDE; gx++) {
      for (let gz = 0; gz < PATCH_SIDE; gz++) {
        const tile = new THREE.Mesh(piece.geometry, piece.materials);
        tile.position.set(gx - half + 0.5, 0, gz - half + 0.5);
        tile.receiveShadow = true;
        holder.add(tile);
      }
      const north = new THREE.Mesh(wall.geometry, wall.materials);
      north.position.set(gx - half + 0.5, 0, -half);
      const west = new THREE.Mesh(wall.geometry, wall.materials);
      west.position.set(-half, 0, gx - half + 0.5);
      west.rotation.y = Math.PI / 2;
      for (const segment of [north, west]) {
        segment.castShadow = true;
        segment.receiveShadow = true;
        holder.add(segment);
      }
    }

    const cell = document.createElement("div");
    cell.className = "cell";
    cell.innerHTML = `<b>${tint.label}</b><i>${tint.note}</i>`;
    labels.appendChild(cell);

    renderer.setViewport(i * TINT_CELL, 0, TINT_CELL, TINT_CELL);
    renderer.setScissor(i * TINT_CELL, 0, TINT_CELL, TINT_CELL);
    renderer.render(scene, isoCamera(PATCH_SIDE * 0.42, 1, 0.3));
  }
}

// ---- Floors --------------------------------------------------------------------------

const PATCH = 6;
const FLOOR_CELL = 400;
const TYPES: FloorType[] = ["STONE", "CRACKED_STONE", "TILED"];

async function buildFloors(): Promise<void> {
  const canvas = document.getElementById("floors") as HTMLCanvasElement;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setSize(TYPES.length * FLOOR_CELL, FLOOR_CELL);
  renderer.setScissorTest(true);
  renderer.shadowMap.enabled = true;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const labels = document.getElementById("floorLabels") as HTMLDivElement;
  labels.style.gridTemplateColumns = `repeat(${TYPES.length}, ${FLOOR_CELL}px)`;
  labels.style.gridTemplateRows = `${FLOOR_CELL}px`;

  const paths = [...new Set(TYPES.flatMap((t) => FLOOR_VARIANTS[t].map((v) => v.path)))];
  const tiles = new Map<string, WallPiece>();
  await Promise.all(
    paths.map(async (path) => {
      const piece = await loadFloorPiece(path, MODULE);
      paintStone(piece, FLOOR_STONE);
      tiles.set(path, piece);
    }),
  );

  const wall = await loadWallPiece("props/ruins/Wall", MODULE);
  const scene = lit();
  const holder = new THREE.Group();
  scene.add(holder);

  TYPES.forEach((type, column) => {
    const variants = FLOOR_VARIANTS[type];
    const total = variants.reduce((sum, v) => sum + v.weight, 0);
    const used = new Map<string, number>();

    holder.clear();
    for (let gy = 0; gy < PATCH; gy++) {
      for (let gx = 0; gx < PATCH; gx++) {
        // The renderer's seed, verbatim — same room id, same square, same tile.
        const seed = hash32(`preview:floor:${gx},${gy}`);
        let roll = seed % total;
        const variant = variants.find((v) => (roll -= v.weight) < 0) ?? variants[0];
        used.set(variant.path, (used.get(variant.path) ?? 0) + 1);

        const piece = tiles.get(variant.path)!;
        const mesh = new THREE.Mesh(piece.geometry, piece.materials);
        mesh.receiveShadow = true;
        mesh.rotation.y = ((seed >>> 16) % 4) * (Math.PI / 2);
        mesh.position.set(gx - PATCH / 2 + 0.5, 0, gy - PATCH / 2 + 0.5);
        holder.add(mesh);
      }
      // A corner of real wall, so the patch is judged against the thing it has to differ from.
      const north = new THREE.Mesh(wall.geometry, wall.materials);
      north.position.set(gy - PATCH / 2 + 0.5, 0, -PATCH / 2);
      const west = new THREE.Mesh(wall.geometry, wall.materials);
      west.position.set(-PATCH / 2, 0, gy - PATCH / 2 + 0.5);
      west.rotation.y = Math.PI / 2;
      for (const segment of [north, west]) {
        segment.castShadow = true;
        segment.receiveShadow = true;
        holder.add(segment);
      }
    }

    const cell = document.createElement("div");
    cell.className = "cell";
    const mix = [...used.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([path, n]) => `${path.split("/").pop()} ×${n}`)
      .join("  ·  ");
    cell.innerHTML = `<b>${type}</b><i>${mix}</i>`;
    labels.appendChild(cell);

    const camera = isoCamera(PATCH * 0.46, 1, 0.3);
    renderer.setViewport(column * FLOOR_CELL, 0, FLOOR_CELL, FLOOR_CELL);
    renderer.setScissor(column * FLOOR_CELL, 0, FLOOR_CELL, FLOOR_CELL);
    renderer.render(scene, camera);
  });
}

// ---- Prop variants -------------------------------------------------------------------

const PROP_CELL = 260;
const PROP_COLS = 4;

async function buildProps(): Promise<void> {
  const entries: Array<{ type: PropType; path: string; height: number; footprint: number }> = [];
  for (const [type, models] of Object.entries(MESH_PROPS)) {
    for (const model of models ?? []) entries.push({ type: type as PropType, ...model });
  }

  const rows = Math.ceil(entries.length / PROP_COLS);
  const canvas = document.getElementById("props") as HTMLCanvasElement;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setSize(PROP_COLS * PROP_CELL, rows * PROP_CELL);
  renderer.setScissorTest(true);
  renderer.shadowMap.enabled = true;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const labels = document.getElementById("propLabels") as HTMLDivElement;
  labels.style.gridTemplateColumns = `repeat(${PROP_COLS}, ${PROP_CELL}px)`;
  labels.style.gridTemplateRows = `repeat(${rows}, ${PROP_CELL}px)`;

  const scene = lit();
  const holder = new THREE.Group();
  scene.add(holder);

  // One square of the real perimeter wall, behind every prop. Scale against the wall is the
  // thing that keeps going wrong, so it should be in the frame rather than in the caption.
  const wall = await loadWallPiece("props/ruins/Wall", MODULE);
  const floor = await loadFloorPiece("props/ruins/Floor_Squares", MODULE);
  paintStone(floor, FLOOR_STONE);

  // Everything loaded before anything is drawn: an `await` between two render calls gives the
  // browser a chance to composite and throw the drawing buffer away, which shows up as a sheet
  // of black cells with only the last one filled in.
  const models = new Map<string, THREE.Group>();
  await Promise.all(
    [...new Set(entries.map((e) => e.path))].map(async (path) => {
      try {
        models.set(path, (await gltf.loadAsync(`/assets/kits/props/${path}.glb`)).scene);
      } catch {
        /* left out of the map; the cell reports it */
      }
    }),
  );

  for (const [i, entry] of entries.entries()) {
    const cell = document.createElement("div");
    cell.className = "cell";
    labels.appendChild(cell);

    holder.clear();

    for (const dx of [-1, 0, 1]) {
      const tile = new THREE.Mesh(floor.geometry, floor.materials);
      tile.position.set(dx, 0, 0);
      tile.receiveShadow = true;
      holder.add(tile);
      const segment = new THREE.Mesh(wall.geometry, wall.materials);
      segment.position.set(dx, 0, -0.5);
      segment.castShadow = true;
      segment.receiveShadow = true;
      holder.add(segment);
    }

    let size = new THREE.Vector3();
    const source = models.get(entry.path);
    if (source) {
      const model = source.clone(true);
      const bounds = new THREE.Box3().setFromObject(model);
      const raw = bounds.getSize(new THREE.Vector3());
      // instanceProp's own sizing, so the preview shows the size the game will draw.
      const scale = Math.min(
        entry.height / Math.max(raw.y, 1e-6),
        entry.footprint / Math.max(raw.x, raw.z, 1e-6),
      );
      model.scale.setScalar(scale);
      model.position.set(
        -((bounds.min.x + bounds.max.x) / 2) * scale,
        -bounds.min.y * scale,
        -((bounds.min.z + bounds.max.z) / 2) * scale,
      );
      model.traverse((child) => {
        if ((child as THREE.Mesh).isMesh) {
          child.castShadow = true;
          child.receiveShadow = true;
        }
      });
      size = raw.multiplyScalar(scale);
      holder.add(model);
      cell.innerHTML =
        `<b>${entry.type} · ${entry.path.split("/").pop()}</b>` +
        `<i>${size.x.toFixed(2)} × ${size.y.toFixed(2)} × ${size.z.toFixed(2)} sq` +
        ` · wall is 1.00 tall</i>`;
    } else {
      cell.innerHTML = `<b>${entry.type} · ${entry.path}</b><i>failed to load</i>`;
    }

    const camera = isoCamera(1.35, 1, 0.5);
    const col = i % PROP_COLS;
    const row = Math.floor(i / PROP_COLS);
    const y = (rows - 1 - row) * PROP_CELL;
    renderer.setViewport(col * PROP_CELL, y, PROP_CELL, PROP_CELL);
    renderer.setScissor(col * PROP_CELL, y, PROP_CELL, PROP_CELL);
    renderer.render(scene, camera);
  }
}

await buildTiles();
await buildTints();
await buildFloors();
await buildProps();
