# M4 site as generator input

The authored five-room site (`crypt` → `gallery` → `chapel` → `undercroft` → `vault`) is what a
generated room must be able to contain. This is the list `emberdelve-4h9.8` hands M1's remaining
half. Spec §14.

A **site**, not a dungeon: an entrance that is also the way out, the objective placed shallow,
about two rooms of optional depth, and something worth more placed deeper. A known room count,
because the ending shows it.

**Every room** must be able to carry:

- **A hidden prop with a `revealHint`**, hinted on the object a player will actually look at (the
  shrine, the bookcase, the chest they came for — not a wall they walked past). `contains` when
  something is inside. Free-form dress-pass secrets are not this.
- **An encounter slot** (`goblinSpawn`, plus `occupants` with a `Disposition`). Authored, not a
  model's decision to invent a fight. About three rooms in a site hold a fight; brute+two mobs is
  never authored. The slot is there even when a room's occupants list is empty.
- **An objective or prize anchor** when the room has one. The objective is engine-owned
  (`reliquary`, `actions: ["take"]`). A greed prize is a second takeable, not a second objective.
- **A `fires` object, or none.** Where there is one: `lit`, `to`, `moved`. At least one room per
  site moves brighter than its initial lighting preset. A `DARK` room still renders a silhouette.
- **A way-out flag** on the site's exit and no other. `crossExit` checks it; door copy must not
  invent a lock, a missing handle, or a dead end the engine does not enforce.
- **A first-visit description** (`dmNotes.overview` / `sensory`) good enough to be the only one
  the room ever gets. Every crossing narrates.
- **Door copy that matches the engine.** Open, unless a real door state exists. Fight is the only
  block today.
- **A small `PropType` and a closed appearance** for each prop that needs one, dressed from more
  than one kit. Scenery has empty `actions`.

Engine rules that survive generation untouched: a rest needs no living hostile in the room; every
entry ticks both clocks; the way out's check decides the ending.

Inputs authoring faked that the generator still has to supply: shared walls (`Rooms.coveredWalls()`),
and a seed that is no longer a placeholder.
