# World kits — provenance and licensing

Every mesh under `godot/world/kits/` was copied from a source pack that is **not** in this
repo. The packs live outside version control; this file is the record of where each one came
from and what we are allowed to do with it.

Sizes are per-model world units **before** any scaling the renderer applies. Read the
Module column before wiring a new prop: the packs do not agree on scale.

| Folder | Source | Author | License | Module |
|---|---|---|---|---|
| `kaykit_dungeon/` | KayKit Dungeon Pack 1.1 FREE | Kay Lousberg | CC0 1.0 | **4 units = 1 square** (measured off `wall.gltf` AABB 4×4×1) |
| `kaykit_halloween/` | KayKit Halloween Bits 1.0 FREE | Kay Lousberg | CC0 1.0 | **1 unit ≈ 1 metre**; props fitted from AABB |
| `characters/kaykit_adventurers/` | KayKit Adventurers 2.0 FREE | Kay Lousberg | CC0 1.0 | Knight; fitted to ~0.8 world units tall |
| `characters/kaykit_skeletons/` | KayKit Skeletons 1.1 FREE | Kay Lousberg | CC0 1.0 | Skeleton_Warrior; fitted to ~0.68 |
| `characters/kaykit_animations/` | KayKit Character Animations 1.1 | Kay Lousberg | CC0 1.0 | Rig_Medium clips only |
| `dungeon_props/` | Updated Modular Dungeon (May 2019) | Quaternius | CC0 1.0 | 2 units = 1 square |
| `ruins/` | Ultimate Modular Ruins Pack (Aug 2021) | Quaternius | CC0 1.0 | 2 units = 1 square |

KayKit dungeon `wall` is the live room module. Halloween Bits is a different convention and is
never scaled by the dungeon module.

Samplers on KayKit atlases (`dungeon_texture.png`, `halloweenbits_texture.png`, character
colormaps) use linear filtering. The old nearest/pixel pipeline is gone.

Do not add a pack whose zip has no license. The old `freesample/` folder was unknown-license
and must not be re-imported.
