# Prop kits — provenance and licensing

Every model here was converted from a source pack that is **not** in this repo. The packs
themselves live outside version control; this file is the record of where each one came from
and what we are allowed to do with it.

Sizes are per-model world units **before** any scaling the renderer applies. Read the
"Module" column before wiring a new prop: the four packs do not agree on scale, and one of
them does not agree on which axis is up.

| Folder | Source | Author | License | Models | Module |
|---|---|---|---|---|---|
| `dungeon/` | Updated Modular Dungeon (May 2019) | Quaternius | CC0 1.0 | 48 | 4 units = 1 square |
| `ruins/` | Ultimate Modular Ruins Pack (Aug 2021) | Quaternius | CC0 1.0 | 92 | 4 units = 1 square |
| `fantasy/` | Fantasy Props MegaKit \[Standard] | Quaternius | CC0 1.0 | 94 | 1 unit = 1 metre |
| `freesample/` | `FreeSample.zip` | **unknown** | **UNKNOWN — see below** | 11 | 2.5 units, **Z-up** |

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

- **`dungeon/` and `ruins/` share Kenney's module exactly.** Quaternius `Wall_ArchRound` is
  4.00 x 3.99, Kenney `template-wall` is 4.00 x 4.15. These drop in at `KIT_SCALE` unchanged,
  which is why they were the two packs wired first.
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

## Reproducing the conversion

Sources were converted with `obj2gltf@3` (OBJ packs) and `fbx2gltf@0.9.7` (FreeSample), and
textures downscaled with `sips -Z 1024`. `fantasy/` needed no geometry conversion — it ships
glTF, and the `.gltf` + `.bin` + shared sheets were copied as-is.

## What is still missing

No pack here contains a **sarcophagus**, which is the one prop M0's script turns on and the
only prop the crypt kit marks `unique`. `SARCOPHAGUS` remains procedural in `props.ts`.
The closest stand-ins are `dungeon/Pedestal` and `dungeon/Pedestal2`, and neither has a lid.
