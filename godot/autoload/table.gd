extends Node

## ALL game state lives here (invariant #3). World, Chrome and SFX all subscribe. Nothing
## game-related may live in a Control's or a Node3D's locals — that is how the renderer ends up
## holding a different world from the one the transcript is describing.
##
## Ported from client/src/store.ts.
##
## Two rules that are not obvious and are load-bearing:
##
## [b]One signal per batch.[/b] The TypeScript is written in Zustand's immutable style, so a
## subscriber always sees one consistent picture. Godot's idiom is mutate-and-emit, and a signal
## per field would have the world rebuilding halfway through a batch that has moved a token but
## not yet changed its hit points. Apply the whole list, then emit once.
##
## [b]Every creature is addressed by its actorId from the wire[/b] (invariant #2). Never a
## literal "fighter", never "the first entity" — the party becomes a list of real people at the
## multiplayer milestone.

signal scene_changed()
signal mode_changed(mode: String)
signal entity_added(entity: Dictionary)
signal entity_moved(entity_id: String, from: Vector2i, to: Vector2i)
signal prop_revealed(prop: Dictionary)
signal entity_died(entity_id: String)

var connected := false
var demo_mode := false
var dm_configured := false
var scene: Dictionary = {}
var mode := "EXPLORATION"

## Whether the player has started the session. Gates a real event: until someone has clicked,
## the server has not been asked to narrate the opening.
var started := false


func reset() -> void:
	scene = {}
	mode = "EXPLORATION"
	started = false
	_reset_talk()   # the transcript half, added in Task 8


## Mode rides with the scene rather than being left to the diff that changed it: a client that
## connects mid-fight gets one message, and it has to be the whole truth.
func set_scene(state: Dictionary) -> void:
	scene = state
	mode = String(state.get("mode", "EXPLORATION"))
	_adopt_combat_from_scene()   # Task 8
	scene_changed.emit()
	mode_changed.emit(mode)


func entity(id: String) -> Dictionary:
	for e in scene.get("entities", []):
		if e["id"] == id:
			return e
	return {}


func prop(id: String) -> Dictionary:
	for p in scene.get("props", []):
		if p["id"] == id:
			return p
	return {}


## Queued with the narration, in arrival order, and released when the voice reaches it.
##
## A hit point bar that empties while the attack die is still in the air has answered the
## question the die was asking — and one that empties twenty seconds before the narrator says
## the blow was struck is worse still. Both are the same bug: the world moving on a different
## clock from the voice describing it.
##
## When nothing is queued this runs on the spot, which is every move and every reveal the player
## makes for themselves — so click-to-move keeps its 100ms budget.
func apply_diffs(list: Array) -> void:
	Clock.mark(func() -> void: _apply_now(list))


func _apply_now(list: Array) -> void:
	if scene.is_empty():
		return

	# Which side of a mode flip this batch crossed, decided in the loop and acted on after it:
	# the ceremony needs the CombatView, and that arrives in the CombatChanged diff sitting
	# behind the ModeChanged one.
	var opened := false
	var closed := false
	var announcements: Array[Callable] = []

	for diff in list:
		match String(diff["kind"]):
			"EntityAdded":
				var added: Dictionary = diff["entity"]
				var existing := entity(String(added["id"]))
				if existing.is_empty():
					scene["entities"].append(added)
					announcements.append(func() -> void: entity_added.emit(added))
				else:
					# Idempotent by id. A replaced goblin is a debug respawn, not a lid coming
					# off a second time.
					scene["entities"][_index_of_entity(String(added["id"]))] = added
					announcements.append(func() -> void: scene_changed.emit())

			"EntityRemoved":
				var at := _index_of_entity(String(diff["entityId"]))
				if at >= 0:
					scene["entities"].remove_at(at)

			"EntityMoved":
				var moving := entity(String(diff["entityId"]))
				if not moving.is_empty():
					var from := Vector2i(int(diff["fromX"]), int(diff["fromY"]))
					var to := Vector2i(int(diff["x"]), int(diff["y"]))
					moving["x"] = to.x
					moving["y"] = to.y
					var who := String(diff["entityId"])
					announcements.append(func() -> void: entity_moved.emit(who, from, to))

			"StatChanged":
				if String(diff["stat"]) == "hp":
					var hurt := entity(String(diff["entityId"]))
					if not hurt.is_empty():
						hurt["hp"] = int(diff["to"])
						if int(diff["to"]) <= 0 and int(diff["from"]) > 0:
							var who := String(diff["entityId"])
							announcements.append(func() -> void: entity_died.emit(who))

			"ModeChanged":
				var next := String(diff["mode"])
				if mode != next:
					opened = next == "COMBAT"
					closed = next == "EXPLORATION"
				mode = next

			"PropRevealed":
				var revealed: Dictionary = diff["prop"]
				var at_prop := _index_of_prop(String(revealed["id"]))
				if at_prop >= 0:
					scene["props"][at_prop] = revealed
				else:
					scene["props"].append(revealed)
					announcements.append(func() -> void: prop_revealed.emit(revealed))

			"CombatChanged":
				# Replaced wholesale, never merged. The server sends the entire legal picture
				# each time precisely so the client has no chance to hold a half-updated one.
				scene["combat"] = diff["combat"]

	_settle_combat(opened, closed)   # Task 8
	scene_changed.emit()
	if opened or closed:
		mode_changed.emit(mode)
	for announce in announcements:
		announce.call()


func _index_of_entity(id: String) -> int:
	var entities: Array = scene.get("entities", [])
	for i in entities.size():
		if entities[i]["id"] == id:
			return i
	return -1


func _index_of_prop(id: String) -> int:
	var props: Array = scene.get("props", [])
	for i in props.size():
		if props[i]["id"] == id:
			return i
	return -1


# ---- Filled in by Task 8. Declared here so Task 7 compiles and runs on its own.

func _reset_talk() -> void:
	pass

func _adopt_combat_from_scene() -> void:
	pass

func _settle_combat(_opened: bool, _closed: bool) -> void:
	pass
