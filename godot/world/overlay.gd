class_name BoardOverlay
extends Node3D

## Legal-move and target highlights, hover, and click routing.
##
## Every branch of [method intent] tests membership in a list that arrived over
## the wire. There is no distance check, no speed arithmetic and no reach rule
## anywhere in this file — asking whether the square is in `legalMoves` is the
## entire client-side movement rule (invariant #1).

const OVERLAY_Y := 0.03
const MOVE_TINT := Color8(0x5c, 0x86, 0xc4)
const TARGET_TINT := Color8(0xc0, 0x45, 0x3c)
const HOVER_TINT := Color8(0xe8, 0xdc, 0xc0)

## A door, and a square with something solid on it.
##
## Both are cool on purpose. The first try at the exit marker was amber, Color8(0xd8, 0x9a, 0x4a)
## — which is (0.85, 0.60, 0.29), and a torchlit floor in this room renders at about
## (0.85, 0.60, 0.35). An unshaded quad at 60% alpha over a ground it already matches is an
## invisible quad; it was not that the colour was wrong, it was that there was no colour
## difference to see. Anything that has to read against these floors has to leave the warm end.
##
## Teal for the way out, red for the way blocked, and the two never appear at once: the hover is
## one square and one intent. TARGET_TINT is also red, but it belongs to combat, where this
## marker never runs — see [method intent].
##
## The blocked marker is a floor quad and stays under whatever is standing on it, so on a big
## prop it is a red edge around the base rather than a red slab. Drawing it over the top with
## `no_depth_test` was tried and rejected: it reads as paint on the object instead of a mark on
## the square, and the square is what the click was about.
const EXIT_TINT := Color8(0x3c, 0xd2, 0xc8)
const BLOCKED_TINT := Color8(0xe0, 0x3b, 0x30)

const MOVE_SIZE := 0.86
const HOVER_SIZE := 0.92

var _move_mat: StandardMaterial3D
var _target_mat: StandardMaterial3D
var _hover_mat: StandardMaterial3D
var _exit_mat: StandardMaterial3D
var _blocked_mat: StandardMaterial3D


func _ready() -> void:
	_ensure()
	Table.scene_changed.connect(refresh)
	refresh()


## What clicking this pick would do, or `{}` if it would do nothing.
##
## Actor is `combat.activeId` in a fight, or the first living player-controlled
## entity out of one. Never a hardcoded creature id.
func intent(entity_id: String, square: Variant = null, target_id: String = "") -> Dictionary:
	var scene := Table.scene
	if scene.is_empty():
		return {}

	var combat = scene.get("combat", null)
	if combat != null and typeof(combat) == TYPE_DICTIONARY:
		var actor := Table.entity(String(combat.get("activeId", "")))
		# The goblin's turn is not the player's to click through.
		if actor.is_empty() or not bool(actor.get("isPlayerControlled", false)):
			return {}
		if not entity_id.is_empty() and _in_targets(combat, entity_id):
			return {
				"kind": "attack",
				"actor_id": String(actor["id"]),
				"target_id": entity_id,
				"square": square,
			}
		if _in_moves(combat, square):
			return {
				"kind": "move",
				"actor_id": String(actor["id"]),
				"square": square,
			}
		return {}

	# A door is a decision, not a move that happens to end somewhere, so it is the door you
	# click and not the square it stands in. The combat branch above has already returned, so
	# this is only ever reachable out of combat — which matches the server, where crossExit
	# refuses while a fight is running.
	var door := _exit_intent(target_id)
	if not door.is_empty():
		return door

	# Out of combat there is no turn and nothing to spend, so anywhere on the
	# floor will do. The server still refuses squares with something solid on them.
	var player := _first_player(scene)
	if player.is_empty() or not (square is Vector2i):
		return {}
	if int(player.get("hp", 0)) <= 0:
		return {}
	var at: Vector2i = square
	if at.x == int(player["x"]) and at.y == int(player["y"]):
		return {}

	# A square the server has already said is solid. Still a move, and still sent: the refusal
	# and its message belong to the server (invariant #1), and hearing it is the point. What
	# this adds is saying so a moment earlier, on the square itself.
	if Table.is_blocked(at):
		return {"kind": "blocked", "actor_id": String(player["id"]), "square": at}

	return {"kind": "move", "actor_id": String(player["id"]), "square": at}


## The door you clicked, or {} if the pick was not a door.
##
## Split out of [method intent] because it runs before the square branch and before the player
## is looked up: a door is a decision about the room, not something the fighter does to a floor
## tile. An {@code Exit} and its {@code DOOR} prop share an id on purpose (dm.model.Exit), which
## is the whole of the lookup.
func _exit_intent(target_id: String) -> Dictionary:
	if target_id.is_empty():
		return {}
	var exit := Table.exit_by_id(target_id)
	if exit.is_empty():
		return {}
	return {
		"kind": "exit",
		"exit_id": String(exit.get("id", "")),
		"square": Vector2i(int(exit.get("x", 0)), int(exit.get("y", 0))),
	}


## Sent, never applied locally. The token does not budge until the server says
## it moved (invariant #1). The swing is Table.strike, not this path.
func commit(action: Dictionary) -> void:
	if action.is_empty():
		return
	match String(action.get("kind", "")):
		"move", "blocked":
			var at: Vector2i = action["square"]
			Net.move_to(String(action["actor_id"]), at.x, at.y)
		"attack":
			Net.attack(String(action["actor_id"]), String(action["target_id"]))
		"exit":
			Table.cross_exit(String(action["exit_id"]))


## `kind` is the intent the hover is previewing, so a door does not look like a floor tile you
## are about to walk to. Defaulted, because a caller that has no intent to show still wants the
## square lit.
func set_hover(square: Variant = null, kind: String = "") -> void:
	_ensure()
	var hover := $Hover as MeshInstance3D
	if not (square is Vector2i):
		hover.visible = false
		return
	var world := _host()
	if world == null:
		hover.visible = false
		return
	var at: Vector2i = square
	var pos: Vector3 = world.grid_to_world(world.current_room_id(), at.x, at.y)
	hover.position = Vector3(pos.x, OVERLAY_Y + 0.004, pos.z)
	match kind:
		"exit":
			hover.material_override = _exit_mat
		"blocked":
			hover.material_override = _blocked_mat
		_:
			hover.material_override = _hover_mat
	hover.visible = true


func refresh() -> void:
	_ensure()
	_clear($Moves)
	_clear($Targets)
	var combat = Table.scene.get("combat", null)
	if not _is_player_turn(combat):
		return
	var world := _host()
	if world == null:
		return
	for cell in combat.get("legalMoves", []):
		_add_quad($Moves, world.grid_to_world(world.current_room_id(), int(cell["x"]), int(cell["y"])), _move_mat, MOVE_SIZE)
	for id in combat.get("legalTargets", []):
		var entity := Table.entity(String(id))
		if entity.is_empty():
			continue
		_add_quad(
			$Targets,
			world.grid_to_world(world.current_room_id(), int(entity["x"]), int(entity["y"])),
			_target_mat,
			MOVE_SIZE,
		)


func _is_player_turn(combat: Variant) -> bool:
	if combat == null or typeof(combat) != TYPE_DICTIONARY:
		return false
	var actor := Table.entity(String(combat.get("activeId", "")))
	return not actor.is_empty() and bool(actor.get("isPlayerControlled", false))


func _in_targets(combat: Dictionary, entity_id: String) -> bool:
	for id in combat.get("legalTargets", []):
		if String(id) == entity_id:
			return true
	return false


func _in_moves(combat: Dictionary, square: Variant) -> bool:
	if not (square is Vector2i):
		return false
	var at: Vector2i = square
	for cell in combat.get("legalMoves", []):
		if int(cell["x"]) == at.x and int(cell["y"]) == at.y:
			return true
	return false


func _first_player(scene: Dictionary) -> Dictionary:
	for entity in scene.get("entities", []):
		if bool(entity.get("isPlayerControlled", false)):
			return entity
	return {}


func _host() -> World:
	return get_parent() as World


func _ensure() -> void:
	if _move_mat == null:
		_move_mat = _tint_material(MOVE_TINT, 0.3)
		_target_mat = _tint_material(TARGET_TINT, 0.42)
		_hover_mat = _tint_material(HOVER_TINT, 0.5)
		_exit_mat = _tint_material(EXIT_TINT, 0.7)
		_blocked_mat = _tint_material(BLOCKED_TINT, 0.7)
	if get_node_or_null("Moves") == null:
		var moves := Node3D.new()
		moves.name = "Moves"
		add_child(moves)
	if get_node_or_null("Targets") == null:
		var targets := Node3D.new()
		targets.name = "Targets"
		add_child(targets)
	if get_node_or_null("Hover") == null:
		var hover := _make_quad(_hover_mat, HOVER_SIZE)
		hover.name = "Hover"
		hover.visible = false
		add_child(hover)


func _add_quad(holder: Node3D, world_pos: Vector3, material: Material, size: float) -> void:
	var quad := _make_quad(material, size)
	quad.position = Vector3(world_pos.x, OVERLAY_Y, world_pos.z)
	holder.add_child(quad)


func _make_quad(material: Material, size: float) -> MeshInstance3D:
	var mesh := QuadMesh.new()
	mesh.size = Vector2(size, size)
	var inst := MeshInstance3D.new()
	inst.mesh = mesh
	inst.material_override = material
	inst.rotation_degrees = Vector3(-90.0, 0.0, 0.0)
	inst.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	inst.gi_mode = GeometryInstance3D.GI_MODE_DISABLED
	return inst


func _tint_material(color: Color, opacity: float) -> StandardMaterial3D:
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.albedo_color = Color(color.r, color.g, color.b, opacity)
	mat.depth_draw_mode = BaseMaterial3D.DEPTH_DRAW_DISABLED
	mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	return mat


func _clear(holder: Node) -> void:
	if holder == null:
		return
	for child in holder.get_children():
		holder.remove_child(child)
		child.free()
