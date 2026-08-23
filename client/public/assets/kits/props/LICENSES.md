# Prop kits — provenance and licensing

Every model here was converted from a source pack that is **not** in this repo. The packs
themselves live outside version control; this file is the record of where each one came from
and what we are allowed to do with it.

Sizes are per-model world units **before** any scaling the renderer applies. Read the
"Module" column before wiring a new prop: the four packs do not agree on scale, and one of
them does not agree on which axis is up.

| Folder | Source | Author | License | Models | Module |
|---|---|---|---|---|---|
| `dungeon/` | Updated Modular Dungeon (May 2019) | Quaternius | CC0 1.0 | 48 | 2 units = 1 square |
| `ruins/` | Ultimate Modular Ruins Pack (Aug 2021) | Quaternius | CC0 1.0 | 92 | 2 units = 1 square |
| `fantasy/` | Fantasy Props MegaKit \[Standard] | Quaternius | CC0 1.0 | 94 | 1 unit = 1 metre |
| `freesample/` | `FreeSample.zip` | **unknown** | **UNKNOWN — see below** | 11 | 2.5 units, **Z-up** |
| `godot/world/kits/kaykit_dungeon/` | KayKit Dungeon Pack 1.1 FREE | Kay Lousberg | CC0 1.0 | cherry-picked floors, walls, torches, pillars, rubble | **4 units = 1 square** |
| `godot/world/kits/kaykit_halloween/` | KayKit Halloween Bits 1.0 FREE | Kay Lousberg | CC0 1.0 | cherry-picked coffin/crypt candidates, bones, shrine, plaque, arch_gate | **1 unit ≈ 1 metre**; props fitted from AABB |

## ⚠️ `freesample/` has no license

The source zip contains no license file, no readme, and names no author. Its materials are
called `M_DungeonCrawler_*`, which suggests it is a free sample of a commercial "Dungeon
Crawler" pack, but that is inference and not a grant of rights.

It was imported at the maintainer's explicit request, in its own folder, so that the question
stays visible. **Do not ship a build containing `freesample/` until its origin and terms are
confirmed.** If the terms turn out to be restrictive, deleting the folder is the whole fix —
nothing outside it references these models.

## Scale, and why it is not uniform

`assets.ts` bakes `KIT_SCALE = 0.25` into kit geometry because Kenney's Modular Dungeon Kit is
authored on a 4-unit module. Measured against that:

- **`dungeon/` and `ruins/` are a two-unit module — half Kenney's.** Their modular pieces
  measure 2.00 square: `dungeon/Floor_Modular` is 2.00 x 2.00, `dungeon/Wall_Modular` 2.00 x
  2.01, `ruins/Floor_Standard` 1.99 x 1.98, `ruins/Wall` 2.00 x 2.00. Placing one of these on
  the module grid needs a factor of 0.5, not `KIT_SCALE`.

  This entry first claimed they matched Kenney's four-unit module, measured off
  `ruins/Wall_ArchRound` at 4.00 x 3.99. That piece is a double — the pack's own how-to says
  arches and curves "use the space of two walls" — so it measured two modules and read as one.
  Nothing broke on it: the props wired in `props.ts` are fitted to an explicit height and
  footprint rather than scaled by the module, so they never depended on the figure. But it
  rules the Quaternius walls out as substitutes for Kenney's, which are also four times the
  depth (1.99 against 0.29).
- **Kenney's own `template-wall-detail-a` is the one true drop-in**, at 4.00 x 4.23 x 1.99
  against `template-wall`'s 4.00 x 4.15 x 1.99. `Renderer.buildWalls` uses it as a second wall
  face.
- **KayKit Dungeon 1.1 is a four-unit module, measured off `wall`.** Godot AABB on the
  imported `wall.gltf` is 4.000 × 4.000 × 1.000 (width × height × depth). `scale = 1 / 4`.
  `floor_tile_large` is the same 4-unit cell; `floor_tile_small*` is a 2-unit half-cell in
  the pack and is brought up to one square from its own XY span, not from a second guessed
  factor. Halloween Bits is a different convention (a `pillar` is 1.000 wide) and is never
  scaled by the dungeon module — `instanceProp` fits those from the bounding box.
- **`fantasy/` is authored in metres.** A barrel is 0.90 tall and a large table is 0.81 —
  real furniture dimensions, not module dimensions. Scaling these by `KIT_SCALE` would make
  them doll furniture. They need their own factor.
- **`freesample/` is Z-up and carries its scale on the node, not the geometry.** Each model's
  mesh node has `scale: [50, 50, 50]`, and the tall axis is Z rather than Y — the Unreal/Max
  convention. `loadKitPiece` reads `mesh.geometry` and discards node transforms, so a
  `freesample/` model loaded through it renders at 1/50 size and lying on its side. Wiring one
  means handling both, which is the main reason none are wired yet.

## Textures

- `dungeon/` and `ruins/` are flat-material and vertex-coloured — no texture maps at all,
  apart from bark and leaf sheets on the ruins vegetation. This is why 140 models fit in 10MB.
- `fantasy/` shares 13 PBR trim sheets across all 94 models, referenced by relative URI, so
  the models stay small and the browser fetches each sheet once. Downscaled from 4K to 1024:
  the render target is 480x270, where a 4K normal map is 35MB of bandwidth nobody can see.
- `freesample/` shares 4 baked diffuse sheets, likewise downscaled to 1024. The FBX referenced
  them by names that do not exist on disk (`M_DungeonCrawler_PropsGrp2_001_diffuseMap` vs
  `BAKE_Props_grp2_DiffuseMap- 4K.png`), so the converter embedded a 1x1 placeholder in every
  model; each GLB was rewritten to point at the shared sheet its material calls for.
- **KayKit** is one atlas per pack (`dungeon_texture.png`, `halloweenbits_texture.png`),
  copied next to the `.gltf` + `.bin` pair. Sampler and Godot material filter are **Nearest**
  — a bilinear atlas at 480px bleeds neighbouring swatches into every edge. The Halloween
  pack was cherry-picked (fifteen named models), not copied whole.

## Reproducing the conversion

Sources were converted with `obj2gltf@3` (OBJ packs) and `fbx2gltf@0.9.7` (FreeSample), and
textures downscaled with `sips -Z 1024`. `fantasy/` needed no geometry conversion — it ships
glTF, and the `.gltf` + `.bin` + shared sheets were copied as-is.

## What is still missing

No pack here contains a **sarcophagus** that passes the four qualities the crypt needs
(tiered silhouette, taper, a void under the lid, lid shifted/turned/tilted). Halloween Bits
ships `coffin`, `coffin_decorated` and `crypt`; they were imported as candidates and left off
the live `prop_table` — see Task 18a. `SARCOPHAGUS` remains the primitive scene. The closest
stand-ins in the older packs are `dungeon/Pedestal` and `dungeon/Pedestal2`, and neither has a
lid.
