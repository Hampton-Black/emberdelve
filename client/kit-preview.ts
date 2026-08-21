import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";

/**
 * A contact sheet of every wall-ish piece in the four kits.
 *
 * <p>Not part of the game. It exists because choosing between wall sets by reading model names
 * is guesswork, and standing in the room to look at one costs a server restart.
 */

/** Units per game square, per pack. See assets/kits/props/LICENSES.md. */
const MODULE = { kenney: 4, quaternius: 2, freesample: 2.5 };

interface Piece {
  path: string;
  label: string;
  pack: "kenney" | "dungeon" | "ruins" | "free";
  module: number;
  /** FreeSample is authored Z-up, the Unreal/Max convention. */
  zUp?: boolean;
}

const K = (name: string): Piece => ({
  path: `/assets/kits/dungeon/${name}.glb`,
  label: name,
  pack: "kenney",
  module: MODULE.kenney,
});
const D = (name: string): Piece => ({
  path: `/assets/kits/props/dungeon/${name}.glb`,
  label: name,
  pack: "dungeon",
  module: MODULE.quaternius,
});
const R = (name: string): Piece => ({
  path: `/assets/kits/props/ruins/${name}.glb`,
  label: name,
  pack: "ruins",
  module: MODULE.quaternius,
});
const F = (name: string): Piece => ({
  path: `/assets/kits/props/freesample/${name}.glb`,
  label: name.replace(/_0?1?_?001$/, ""),
  pack: "free",
  module: MODULE.freesample,
  zUp: true,
});

const PIECES: Piece[] = [
  K("template-wall"),
  K("template-wall-detail-a"),
  K("template-wall-half"),
  K("template-wall-corner"),
  K("template-wall-top"),
  K("template-wall-stairs"),

  D("Wall_Modular"),
  D("WallCover_Modular"),
  D("Decorative_Wall"),
  D("Arch"),
  D("Arch_bars"),
  D("Arch_Door"),

  R("Wall"),
  R("Wall_Half"),
  R("Wall_Broken"),
  R("Wall_Hole"),
  R("Wall_Overgrown"),
  R("Wall_ArchRound"),
  R("Wall_ArchRound_Broken"),
  R("Wall_ArchGothic"),
  R("Wall_Double_Broken"),
  R("Wall_Double_Hole"),
  R("Window_Bars"),
  R("Window_Open"),

  F("WallBrick_Tall_01_001"),
  F("DoorFrame_02_001"),
  F("Door_03_001"),
  F("Floor_Corner_01_001"),
];

const COLS = 4;
const CELL = 240;
const ROWS = Math.ceil(PIECES.length / COLS);

const canvas = document.getElementById("c") as HTMLCanvasElement;
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(COLS * CELL, ROWS * CELL);
renderer.setScissorTest(true);
renderer.shadowMap.enabled = true;
renderer.toneMapping = THREE.ACESFilmicToneMapping;

const labels = document.getElementById("labels") as HTMLDivElement;
labels.style.gridTemplateColumns = `repeat(${COLS}, ${CELL}px)`;
labels.style.gridTemplateRows = `repeat(${ROWS}, ${CELL}px)`;

const scene = new THREE.Scene();
// Roughly the room's own light, so a piece is judged in something like the conditions it will
// actually be seen in rather than under a showroom lamp.
scene.add(new THREE.AmbientLight(0x3c3a52, 1.6));
const key = new THREE.DirectionalLight(0xffe9c4, 1.5);
key.position.set(4, 7, 5);
key.castShadow = true;
scene.add(key);
const fill = new THREE.DirectionalLight(0x8fb0d8, 0.5);
fill.position.set(-5, 3, -4);
scene.add(fill);

const ground = new THREE.Mesh(
  new THREE.PlaneGeometry(40, 40),
  new THREE.MeshStandardMaterial({ color: 0x15141a, roughness: 1 }),
);
ground.rotation.x = -Math.PI / 2;
ground.position.y = -0.001;
ground.receiveShadow = true;
scene.add(ground);

const holder = new THREE.Group();
scene.add(holder);

const loader = new GLTFLoader();

/** The game's isometric angle, so silhouettes here match silhouettes there. */
const AZIMUTH = Math.PI / 4;
const ELEVATION = Math.atan(1 / Math.SQRT2);

const loaded: Array<{ piece: Piece; object: THREE.Object3D; size: THREE.Vector3 }> = [];

async function load(): Promise<void> {
  for (const piece of PIECES) {
    const cell = document.createElement("div");
    cell.className = `cell pack-${piece.pack}`;
    labels.appendChild(cell);

    try {
      const gltf = await loader.loadAsync(piece.path);
      const object = gltf.scene;
      // No manual Z-up fix: FBX2glTF already bakes the conversion into the GLB's root node,
      // and rotating again lays the piece on its face.

      // One module to one unit, so pieces from packs with different modules are comparable and
      // a double-width piece honestly reads as two squares wide.
      const s = 1 / piece.module;
      object.scale.multiplyScalar(s);

      const box = new THREE.Box3().setFromObject(object);
      const size = box.getSize(new THREE.Vector3());
      const centre = box.getCenter(new THREE.Vector3());
      object.position.sub(new THREE.Vector3(centre.x, box.min.y, centre.z));

      object.traverse((child) => {
        if ((child as THREE.Mesh).isMesh) {
          child.castShadow = true;
          child.receiveShadow = true;
        }
      });

      loaded.push({ piece, object, size });
      cell.innerHTML =
        `<b>${piece.label}</b><i>${piece.pack} · ` +
        `${size.x.toFixed(2)} × ${size.y.toFixed(2)} × ${size.z.toFixed(2)} sq</i>`;
    } catch {
      cell.innerHTML = `<b>${piece.label}</b><i>${piece.pack} · failed to load</i>`;
    }
  }
  render();
}

function render(): void {
  const total = COLS * CELL;
  for (const [i, entry] of loaded.entries()) {
    const col = i % COLS;
    const row = Math.floor(i / COLS);

    holder.clear();
    holder.add(entry.object);

    // Frame this piece: half-height from its own bounds, so a tall arch and a low wall cover
    // are both fully in shot without either being a speck.
    const reach = Math.max(entry.size.x, entry.size.y, entry.size.z, 0.8);
    const half = reach * 0.78;
    const camera = new THREE.OrthographicCamera(-half, half, half, -half, 0.1, 100);
    const r = 20;
    camera.position.set(
      Math.cos(ELEVATION) * Math.sin(AZIMUTH) * r,
      Math.sin(ELEVATION) * r,
      Math.cos(ELEVATION) * Math.cos(AZIMUTH) * r,
    );
    camera.lookAt(0, entry.size.y * 0.42, 0);

    const x = col * CELL;
    const y = total > 0 ? (Math.ceil(loaded.length / COLS) - 1 - row) * CELL : 0;
    renderer.setViewport(x, y, CELL, CELL);
    renderer.setScissor(x, y, CELL, CELL);
    renderer.render(scene, camera);
  }
}

// ---- Mixed runs ----------------------------------------------------------------------
//
// The contact sheet answers "what does this piece look like"; it does not answer "do two packs
// sit next to each other". This does: one stretch of wall, built three ways.

const RUN_LEN = 6;
const RUN_W = 720;
const RUN_H = 460;

const RUNS: Array<{ title: string; pieces: Piece[] }> = [
  {
    title: "dungeon only",
    pieces: [
      D("Wall_Modular"), D("Wall_Modular"), D("Decorative_Wall"), D("Wall_Modular"),
      D("Wall_Modular"), D("Decorative_Wall"), D("Wall_Modular"), D("Wall_Modular"),
    ],
  },
  {
    title: "ruins only",
    pieces: [
      R("Wall"), R("Wall_Broken"), R("Wall"), R("Window_Open"),
      R("Wall"), R("Wall_Hole"), R("Wall_Overgrown"), R("Wall"),
    ],
  },
  {
    title: "mixed — dungeon and ruins in one run",
    pieces: [
      R("Wall"), D("Wall_Modular"), R("Wall_Broken"), D("Decorative_Wall"),
      R("Window_Open"), D("Wall_Modular"), R("Wall_Hole"), R("Wall"),
    ],
  },
];

const canvas2 = document.getElementById("c2") as HTMLCanvasElement;
const renderer2 = new THREE.WebGLRenderer({ canvas: canvas2, antialias: true });
renderer2.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer2.setSize(RUN_W, RUN_H * RUNS.length);
renderer2.setScissorTest(true);
renderer2.shadowMap.enabled = true;
renderer2.toneMapping = THREE.ACESFilmicToneMapping;

const runLabels = document.getElementById("runLabels") as HTMLDivElement;
runLabels.style.gridTemplateColumns = `${RUN_W}px`;
runLabels.style.gridTemplateRows = `repeat(${RUNS.length}, ${RUN_H}px)`;

async function buildRuns(): Promise<void> {
  const groups: THREE.Group[] = [];

  for (const run of RUNS) {
    const cell = document.createElement("div");
    cell.className = "cell";
    cell.innerHTML = `<b>${run.title}</b>`;
    runLabels.appendChild(cell);

    const group = new THREE.Group();
    for (let i = 0; i < RUN_LEN; i++) {
      const piece = run.pieces[i % run.pieces.length];
      const gltf = await loader.loadAsync(piece.path);
      const object = gltf.scene;
      object.scale.multiplyScalar(1 / piece.module);

      const box = new THREE.Box3().setFromObject(object);
      const centre = box.getCenter(new THREE.Vector3());
      // Centred across, standing on the floor, and pushed back so every pack's inner face
      // lands on the same plane — which is the whole trick to mixing depths.
      object.position.set(i - RUN_LEN / 2 + 0.5 - centre.x, -box.min.y, -box.max.z);

      object.traverse((child) => {
        if ((child as THREE.Mesh).isMesh) {
          child.castShadow = true;
          child.receiveShadow = true;
        }
      });
      group.add(object);
    }
    groups.push(group);
  }

  for (const [i, group] of groups.entries()) {
    holder.clear();
    holder.add(group);

    // An isometric run of N squares projects to roughly 0.71N across and 0.41N + 0.8 down,
    // so the strip wants to be about 1.4:1 rather than a letterbox.
    const half = 2.05;
    const camera = new THREE.OrthographicCamera(
      -half * (RUN_W / RUN_H), half * (RUN_W / RUN_H), half, -half, 0.1, 100);
    const r = 20;
    camera.position.set(
      Math.cos(ELEVATION) * Math.sin(AZIMUTH) * r,
      Math.sin(ELEVATION) * r,
      Math.cos(ELEVATION) * Math.cos(AZIMUTH) * r,
    );
    camera.lookAt(0, 0.5, 0);

    const y = (groups.length - 1 - i) * RUN_H;
    renderer2.setViewport(0, y, RUN_W, RUN_H);
    renderer2.setScissor(0, y, RUN_W, RUN_H);
    renderer2.render(scene, camera);
  }
}

void load().then(buildRuns);
