# ADR-0006: Godot replaces the Vite/Three.js client

**Status:** Accepted 2026-08-23
**Milestone:** between M1 and M2 — parity gate, Task 22

## Context

M0 and M1 built the table as a web client: Vite, React, Three.js, `Canvas.tsx`. It worked, and
the isometric look was reachable, but everything past the first render — scene graph, animation
clips, skinned meshes, audio scheduling, asset import — was hand-assembled against libraries that
each solved a fraction of it.

## Decision

The client is a Godot desktop application, in `godot/`. The migration was gated on parity with
the web client rather than on new capability.

The look is **stylized isometric 3D at native resolution**: KayKit-class meshes, linear
filtering, real lights. Camera is four fixed isometric corners with a 90° snap (Q/E), never free
orbit.

## Consequences

- Skinned characters, animation clips, glTF import and scheduled audio are engine features
  instead of project code. Each token gets its own `AnimationPlayer`, so two tokens of one model
  don't animate in lockstep.
- Client-side terms moved: `Table` is now `godot/autoload/table.gd`, the world is
  `godot/world/world.tscn`, the voice queue is `godot/autoload/clock.gd`.
- **Any plan written before 2026-08-23 is stale in its client half.** The M1 dungeon-navigation
  plan's client tasks are React/Three.js against a `GameRepository` that no longer exists; its
  `LayoutGenerator`, `ExitPlacer` and `SpatialValidator` tasks still lift.
- The 480px/960px nearest-neighbour pixel pass is gone, and figures should not be grown to "read
  at 480" — oversizing to 1.25 on a 1.0 square was for a buffer that no longer exists.
- Free orbit stays rejected: it breaks the isometric read and grid picking.
- Lighting and post-processing is the single largest time sink in the project. Timebox it to one
  evening.

## Related

`AGENTS.md` § Locked decisions, § Characters. Invariants #3 and #4.
