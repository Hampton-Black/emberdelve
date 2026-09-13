extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## Legal-move highlights and click routing. Membership in the server's lists is the
## entire client-side movement rule (invariant #1). Actor ids come from the scene,
## never a literal "fighter".

const KEEPER := {
	"id": "keeper", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
	"hp": 12, "maxHp": 12, "isPlayerControlled": true,
}
const GOBLIN := {
	"id": "goblin", "kind": "goblin", "name": "Vessk", "x": 8, "y": 6,
	"hp": 7, "maxHp": 7, "isPlayerControlled": false,
}

const MOVE_TINT := Color8(0x5c, 0x86, 0xc4)
const TARGET_TINT := Color8(0xc0, 0x45, 0x3c)
const HOVER_TINT := Color8(0xe8, 0xdc, 0xc0)
const OVERLAY_Y := 0.03

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [],
	"entities": [KEEPER],
}


func before_each() -> void:
	Table.reset()
	Table.set_scene(SceneFixtures.scene(CRYPT))
	Net.outbound.clear()


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var node: Node3D = packed.instantiate()
	add_child_autofree(node)
	return node


func _overlay(world: Node) -> Node:
	return world.get_node_or_null("Overlay")


func _world_with_scene(scene_dict: Dictionary) -> Node3D:
	Table.set_scene(scene_dict)
	return _world_tree()


func _combat(active_id: String, moves: Array, targets: Array) -> Dictionary:
	return {
		"order": [
			{"entityId": "keeper", "name": "Roderick", "initiative": 18,
				"isPlayerControlled": true},
			{"entityId": "goblin", "name": "Vessk", "initiative": 11,
				"isPlayerControlled": false},
		],
		"activeId": active_id, "round": 1, "movementRemaining": 6, "actionAvailable": true,
		"legalMoves": moves, "legalTargets": targets,
	}


func _fighting(active_id: String, moves: Array, targets: Array) -> Dictionary:
	var scene := CRYPT.duplicate(true)
	scene["mode"] = "COMBAT"
	scene["entities"] = [KEEPER.duplicate(), GOBLIN.duplicate()]
	scene["combat"] = _combat(active_id, moves, targets)
	return SceneFixtures.scene(scene)


func _commit_pick(world: Node, entity_id: String, square: Vector2i) -> void:
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	assert_true(overlay.has_method("intent"), "Overlay.intent")
	assert_true(overlay.has_method("commit"), "Overlay.commit")
	if not overlay.has_method("intent") or not overlay.has_method("commit"):
		return
	overlay.commit(overlay.intent(entity_id, square))


func _albedo(mesh: MeshInstance3D) -> Color:
	var mat := mesh.get_active_material(0) as BaseMaterial3D
	if mat == null:
		mat = mesh.material_override as BaseMaterial3D
	if mat == null:
		return Color.BLACK
	return mat.albedo_color


func _almost_tint(got: Color, want: Color, label: String) -> void:
	assert_almost_eq(got.r, want.r, 0.02, label + " r")
	assert_almost_eq(got.g, want.g, 0.02, label + " g")
	assert_almost_eq(got.b, want.b, 0.02, label + " b")


# ---- Membership / routing

func test_overlay_script_exists() -> void:
	assert_true(ResourceLoader.exists("res://world/overlay.gd"), "overlay.gd")
	var world := _world_tree()
	var overlay := _overlay(world)
	assert_not_null(overlay, "World/Overlay")
	if overlay == null:
		return
	assert_not_null(overlay.get_script(), "Overlay wears overlay.gd")


func test_a_legal_exploration_click_sends_move_to_the_player_controlled_actor() -> void:
	var world := _world_tree()
	_commit_pick(world, "", Vector2i(4, 3))
	assert_eq(Net.outbound.size(), 1)
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "moveTo")
	assert_eq(Net.outbound[0]["actorId"], "keeper", "actor is the player-controlled entity, never a literal fighter")
	assert_eq(int(Net.outbound[0]["x"]), 4)
	assert_eq(int(Net.outbound[0]["y"]), 3)


func test_clicking_your_own_square_out_of_combat_sends_nothing() -> void:
	var world := _world_tree()
	_commit_pick(world, "keeper", Vector2i(2, 2))
	assert_eq(Net.outbound.size(), 0)


func test_a_dead_player_cannot_click_the_floor() -> void:
	var dead := CRYPT.duplicate(true)
	dead["entities"] = [KEEPER.duplicate()]
	dead["entities"][0]["hp"] = 0
	Table.set_scene(SceneFixtures.scene(dead))
	var world := _world_tree()
	_commit_pick(world, "", Vector2i(4, 3))
	assert_eq(Net.outbound.size(), 0)


func test_a_legal_combat_move_sends_move_to_the_active_id() -> void:
	Table.set_scene(_fighting("keeper", [{"x": 3, "y": 2}], []))
	var world := _world_tree()
	_commit_pick(world, "", Vector2i(3, 2))
	assert_eq(Net.outbound.size(), 1)
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "moveTo")
	assert_eq(Net.outbound[0]["actorId"], "keeper")
	assert_eq(int(Net.outbound[0]["x"]), 3)
	assert_eq(int(Net.outbound[0]["y"]), 2)


func test_a_legal_target_click_sends_attack_with_the_active_id() -> void:
	Table.set_scene(_fighting("keeper", [{"x": 3, "y": 2}], ["goblin"]))
	var world := _world_tree()
	_commit_pick(world, "goblin", Vector2i(8, 6))
	assert_eq(Net.outbound.size(), 1)
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "attack")
	assert_eq(Net.outbound[0]["actorId"], "keeper")
	assert_eq(Net.outbound[0]["targetId"], "goblin")


func test_an_illegal_combat_click_sends_nothing() -> void:
	Table.set_scene(_fighting("keeper", [{"x": 3, "y": 2}], ["goblin"]))
	var world := _world_tree()
	_commit_pick(world, "", Vector2i(5, 5))
	assert_eq(Net.outbound.size(), 0)


func test_the_goblins_turn_sends_nothing() -> void:
	Table.set_scene(_fighting("goblin", [{"x": 7, "y": 6}, {"x": 3, "y": 2}], ["keeper"]))
	var world := _world_tree()
	_commit_pick(world, "", Vector2i(7, 6))
	_commit_pick(world, "keeper", Vector2i(2, 2))
	assert_eq(Net.outbound.size(), 0, "the goblin's turn is not the player's to click through")


func test_a_click_does_not_swing() -> void:
	Table.set_scene(_fighting("keeper", [], ["goblin"]))
	var world := _world_tree()
	var fighter := world.get_node_or_null("Tokens/keeper") as Node3D
	assert_not_null(fighter, "keeper token")
	if fighter == null:
		return
	_commit_pick(world, "goblin", Vector2i(8, 6))
	assert_eq(Net.outbound.size(), 1, "the attack went out")
	var player := fighter.find_child("AnimationPlayer", true, false) as AnimationPlayer
	assert_not_null(player, "AnimationPlayer")
	if player == null:
		return
	var current := String(player.current_animation)
	assert_true(current.ends_with("idle") or current == "idle",
		"swing stays on Table.strike, not the click: %s" % current)


# ---- Highlights

func test_legal_move_quads_sit_at_overlay_y_in_the_move_tint() -> void:
	Table.set_scene(_fighting("keeper", [{"x": 3, "y": 2}, {"x": 2, "y": 3}], []))
	var world := _world_tree()
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	var moves := overlay.get_node_or_null("Moves") as Node3D
	assert_not_null(moves, "Overlay/Moves")
	if moves == null:
		return
	assert_eq(moves.get_child_count(), 2)
	for child in moves.get_children():
		var mesh := child as MeshInstance3D
		assert_not_null(mesh, "move quad")
		if mesh == null:
			continue
		assert_almost_eq(mesh.position.y, OVERLAY_Y, 0.0001, "OVERLAY_Y 0.03 — coplanar z-fights")
		_almost_tint(_albedo(mesh), MOVE_TINT, "MOVE_TINT 5c86c4")
		var want: Vector3 = world.grid_to_world(
			"crypt",
			roundi(world.world_to_grid(mesh.position).x),
			roundi(world.world_to_grid(mesh.position).y))
		assert_almost_eq(mesh.position.x, want.x, 0.001)
		assert_almost_eq(mesh.position.z, want.z, 0.001)


func test_legal_target_quads_use_the_target_tint() -> void:
	Table.set_scene(_fighting("keeper", [], ["goblin"]))
	var world := _world_tree()
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	var targets := overlay.get_node_or_null("Targets") as Node3D
	assert_not_null(targets, "Overlay/Targets")
	if targets == null:
		return
	assert_eq(targets.get_child_count(), 1)
	var mesh := targets.get_child(0) as MeshInstance3D
	assert_not_null(mesh, "target quad")
	if mesh == null:
		return
	assert_almost_eq(mesh.position.y, OVERLAY_Y, 0.0001)
	_almost_tint(_albedo(mesh), TARGET_TINT, "TARGET_TINT c0453c")
	assert_eq(world.world_to_grid(mesh.position), Vector2i(8, 6), "under the goblin")


func test_the_goblins_turn_draws_no_legal_squares() -> void:
	Table.set_scene(_fighting("goblin", [{"x": 7, "y": 6}], ["keeper"]))
	var world := _world_tree()
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	var moves := overlay.get_node_or_null("Moves") as Node3D
	var targets := overlay.get_node_or_null("Targets") as Node3D
	assert_eq(0 if moves == null else moves.get_child_count(), 0,
		"highlighting the goblin's moves would look clickable")
	assert_eq(0 if targets == null else targets.get_child_count(), 0)


func test_hover_uses_the_hover_tint() -> void:
	var world := _world_tree()
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	assert_true(overlay.has_method("set_hover"), "set_hover")
	if not overlay.has_method("set_hover"):
		return
	overlay.set_hover(Vector2i(4, 3))
	var hover := overlay.get_node_or_null("Hover") as MeshInstance3D
	assert_not_null(hover, "Overlay/Hover")
	if hover == null:
		return
	assert_true(hover.visible)
	assert_almost_eq(hover.position.y, OVERLAY_Y + 0.004, 0.0001)
	_almost_tint(_albedo(hover), HOVER_TINT, "HOVER_TINT e8dcc0")
	assert_eq(world.world_to_grid(hover.position), Vector2i(4, 3))


func test_a_move_does_not_rebuild_the_overlay_or_the_tokens() -> void:
	var world := _world_tree()
	var fighter := world.get_node_or_null("Tokens/keeper")
	var overlay := _overlay(world)
	assert_not_null(fighter, "keeper")
	assert_not_null(overlay, "Overlay")
	if fighter == null or overlay == null:
		return
	var token_id: int = fighter.get_instance_id()
	var overlay_id: int = overlay.get_instance_id()
	Table.apply_diffs([{
		"kind": "EntityMoved", "entityId": "keeper",
		"fromX": 2, "fromY": 2, "x": 4, "y": 2,
	}])
	await wait_process_frames(2)
	assert_eq(world.get_node("Tokens/keeper").get_instance_id(), token_id,
		"scene_changed on a move must not mint a new token")
	assert_eq(_overlay(world).get_instance_id(), overlay_id,
		"the overlay node is not rebuilt")


# ---- Picking: viewport space, not window space

func test_window_to_viewport_divides_out_the_container_scale() -> void:
	var world := _world_tree()
	assert_true(world.has_method("viewport_from_host"), "viewport_from_host")
	if not world.has_method("viewport_from_host"):
		return
	var host := SubViewportContainer.new()
	add_child_autofree(host)
	host.position = Vector2.ZERO
	host.size = Vector2(480, 270)
	host.scale = Vector2(4, 4)
	await wait_process_frames(1)
	var converted: Vector2 = world.viewport_from_host(host, Vector2(400, 200))
	assert_almost_eq(converted.x, 100.0, 1.0,
		"a window click at 400 on a 4× 480-wide view is viewport 100")
	assert_almost_eq(converted.y, 50.0, 1.0)
	assert_false(converted.is_equal_approx(Vector2(400, 200)),
		"forgetting the scale conversion leaves window-space coords")


func test_pick_at_reads_viewport_pixels_not_window_pixels() -> void:
	var world := _world_tree()
	assert_true(world.has_method("pick_at"), "pick_at")
	if not world.has_method("pick_at"):
		return
	await wait_process_frames(2)
	var square := Vector2i(4, 3)
	var ground: Vector3 = world.grid_to_world("crypt", square.x, square.y)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return
	var viewport_pos: Vector2 = cam.unproject_position(ground)
	var picked: Dictionary = world.pick_at(viewport_pos)
	assert_true(picked.has("square"), "pick returns a square")
	if not picked.has("square"):
		return
	assert_eq(picked["square"], square, "viewport-space unproject of the tile centre")

	# Window space on a 4× scaled 480-wide view. Using these as viewport pixels
	# is the one-tile-off (or off-board) bug the conversion exists to prevent.
	var window_pos := viewport_pos * 4.0
	var wrong: Dictionary = world.pick_at(window_pos)
	if wrong.has("square") and wrong["square"] != null:
		assert_ne(wrong["square"], square,
			"window-space coords must not pick the same tile")
	else:
		assert_true(true, "window-space coords miss the board, which is also not the tile")


const DOORWAY := {
	"roomId": "crypt", "width": 12, "height": 12, "mode": "EXPLORATION",
	"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	# The crypt's real furniture, not just the door. A fixture holding only the door is what let
	# `brazier-east` — which stands between the camera and the north wall — go unnoticed while it
	# swallowed every click on the doorway.
	"props": [
		{"id": "door-north", "type": "DOOR", "x": 6, "y": 11, "rotation": 180, "hidden": false},
		{"id": "sarcophagus", "type": "SARCOPHAGUS", "x": 6, "y": 7, "rotation": 0,
			"hidden": false},
		{"id": "brazier-west", "type": "BRAZIER", "x": 2, "y": 8, "rotation": 0, "hidden": false},
		{"id": "brazier-east", "type": "BRAZIER", "x": 9, "y": 8, "rotation": 0, "hidden": false},
		{"id": "pillar-west", "type": "PILLAR", "x": 3, "y": 4, "rotation": 0, "hidden": false},
		{"id": "pillar-east", "type": "PILLAR", "x": 8, "y": 4, "rotation": 0, "hidden": false},
		{"id": "rubble", "type": "RUBBLE", "x": 2, "y": 2, "rotation": 45, "hidden": false},
	],
	"exits": [{"id": "door-north", "x": 6, "y": 11,
		"direction": "NORTH", "toRoomId": "gallery"}],
	# What the server says is solid. Note the alcove at (9,6) is deliberately absent — it is a
	# prop you can walk into, which is why this cannot be derived from `props`.
	"blocked": [{"x": 6, "y": 7}, {"x": 2, "y": 8}, {"x": 9, "y": 8},
		{"x": 3, "y": 4}, {"x": 8, "y": 4}, {"x": 2, "y": 2}],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
		"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
	"combat": null,
}


func test_clicking_the_door_is_an_exit_and_the_square_under_it_is_not() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	var on_the_door: Dictionary = overlay.intent("", Vector2i(6, 11), "door-north")
	assert_eq(String(on_the_door.get("kind", "")), "exit")
	assert_eq(String(on_the_door.get("exit_id", "")), "door-north")

	# The square the door stands in is still floor. Leaving it an exit as well would keep the
	# unintuitive click alive next to the intuitive one, and the two would drift.
	var under_the_door: Dictionary = overlay.intent("", Vector2i(6, 11))
	assert_eq(String(under_the_door.get("kind", "")), "move",
		"a door is the thing you click, not the tile it stands on")

	var plain_floor: Dictionary = overlay.intent("", Vector2i(4, 4))
	assert_eq(String(plain_floor.get("kind", "")), "move",
		"an ordinary square is still a move")

	# A prop with nothing behind it must not swallow the click. Movement near the pillars is
	# what would break first.
	var pillar: Dictionary = overlay.intent("", Vector2i(4, 4), "pillar-west")
	assert_eq(String(pillar.get("kind", "")), "move",
		"a target the overlay has no intent for falls through to the square")


func test_the_door_is_picked_by_pointing_at_it() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	# The door is the wall segment, not a prop standing in the square (prop_table.gd). It is
	# addressed by the exit's id, so what comes back is the same string either way.
	var door: Node3D = null
	for child in (world.get_node("Room/Walls") as Node3D).get_children():
		if child.has_meta("exit_id"):
			door = child as Node3D
	assert_not_null(door, "a wall segment tagged with the exit")
	if door == null:
		return
	assert_eq(String(door.get_meta("exit_id")), "door-north")

	# Aimed at the middle of the leaf, not at the floor square — the whole point of the change
	# is that the two are different targets.
	var leaf := AABB()
	var first := true
	for mesh in door.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	assert_false(first, "the doorway instanced a mesh")
	if first:
		return
	var aim: Vector3 = leaf.get_center()
	assert_lt(aim.z, world.grid_to_world("crypt", 6, 11).z,
		"the door is in the wall, north of the square it is addressed by")

	var picked: Dictionary = world.pick_at(cam.unproject_position(aim))
	assert_eq(String(picked.get("target_id", "")), "door-north")

	var overlay := _overlay(world)
	if overlay == null:
		return
	var action: Dictionary = overlay.intent(
		String(picked.get("entity_id", "")),
		picked.get("square", null),
		String(picked.get("target_id", "")))
	assert_eq(String(action.get("kind", "")), "exit")


func test_a_prop_does_not_swallow_a_click_meant_for_the_floor() -> void:
	# The pillar is in the crypt's own layout and is 1.6 units tall, so an isometric ray to the
	# floor behind it passes through its box. Picking must still land on the floor square the
	# ray actually reaches first, or movement goes sticky around every column in the room.
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	if cam == null:
		return

	var square := Vector2i(3, 4)
	var picked: Dictionary = world.pick_at(
		cam.unproject_position(world.grid_to_world("crypt", square.x, square.y)))
	assert_eq(picked.get("square", null), square, "the floor square is still reported")

	var overlay := _overlay(world)
	if overlay == null:
		return
	# The pillar's own square is solid, so the honest answer here is "blocked" — but it is an
	# answer about a square, which is the thing that was being lost. A swallowed click returns
	# {} and leaves the player looking at nothing.
	assert_eq(String(overlay.intent(
		String(picked.get("entity_id", "")),
		picked.get("square", null),
		String(picked.get("target_id", ""))).get("kind", "")), "blocked")

	# And open floor a step away is still an ordinary move, so the pillar is not casting a
	# shadow over its neighbours.
	var free := Vector2i(4, 5)
	var beside: Dictionary = world.pick_at(
		cam.unproject_position(world.grid_to_world("crypt", free.x, free.y)))
	assert_eq(beside.get("square", null), free)
	assert_eq(String(overlay.intent("", beside.get("square", null),
		String(beside.get("target_id", ""))).get("kind", "")), "move")


func test_a_real_click_on_the_door_sends_enter_exit() -> void:
	# The whole path the game uses: Chrome forwards a viewport-local pointer to
	# World.handle_pointer, which picks, asks the overlay, and commits. pick_at and intent are
	# each covered above; this is the one that would catch them being wired together wrongly.
	Net.outbound.clear()
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var door: Node3D = null
	for child in (world.get_node("Room/Walls") as Node3D).get_children():
		if child.has_meta("exit_id"):
			door = child as Node3D
	assert_not_null(door, "the tagged doorway")
	if door == null:
		return
	var leaf := AABB()
	var first := true
	for mesh in door.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	if first:
		return

	world.handle_pointer(cam.unproject_position(leaf.get_center()), true)

	assert_eq(Net.outbound.size(), 1, "one message, and it is the crossing")
	if Net.outbound.is_empty():
		return
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "door-north")


func test_a_solid_square_is_marked_before_it_is_clicked() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	# The sarcophagus. The server would refuse this move; the board says so first.
	var solid: Dictionary = overlay.intent("", Vector2i(6, 7))
	assert_eq(String(solid.get("kind", "")), "blocked")
	assert_eq(solid.get("square", null), Vector2i(6, 7))

	# The alcove's square is a prop and is walkable, which is the case that rules out reading
	# this off the prop list on the client.
	assert_eq(String(overlay.intent("", Vector2i(9, 6)).get("kind", "")), "move",
		"an alcove is a recess, not a wall")
	assert_eq(String(overlay.intent("", Vector2i(4, 4)).get("kind", "")), "move")


func test_a_blocked_click_is_still_sent_so_the_server_answers() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	var overlay := _overlay(world)
	if overlay == null:
		return

	overlay.commit(overlay.intent("", Vector2i(6, 7)))

	# The refusal and its wording are the server's (invariant #1). Swallowing the click here
	# would trade a spoken "something solid is already there" for silence.
	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "moveTo")
	assert_eq(int(Net.outbound[0].get("x", -1)), 6)
	assert_eq(int(Net.outbound[0].get("y", -1)), 7)


func test_the_hover_is_tinted_by_what_the_click_would_do() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	var overlay := _overlay(world)
	if overlay == null:
		return
	var hover := overlay.get_node("Hover") as MeshInstance3D

	overlay.set_hover(Vector2i(4, 4), "move")
	var plain: Color = (hover.material_override as StandardMaterial3D).albedo_color
	overlay.set_hover(Vector2i(6, 11), "exit")
	var exit: Color = (hover.material_override as StandardMaterial3D).albedo_color
	overlay.set_hover(Vector2i(6, 7), "blocked")
	var blocked: Color = (hover.material_override as StandardMaterial3D).albedo_color

	assert_ne(plain, exit)
	assert_ne(plain, blocked)
	assert_ne(exit, blocked)

	# The bug this replaces: the exit marker was amber over a torchlit floor that is also amber,
	# so there was nothing to see. Both markers now sit at the cool end, away from every floor
	# this game lays. `hue` is undefined for greys, and none of these are grey.
	assert_gt(exit.h, 0.4, "the exit marker must not be a warm colour")
	assert_lt(exit.h, 0.6)
	assert_gt(exit.s, 0.5, "and must not be washed out")
	assert_gt(blocked.s, 0.6, "blocked reads as a refusal, not as dim stone")


func test_committing_an_exit_sends_enter_exit() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	overlay.commit(overlay.intent("", Vector2i(6, 11), "door-north"))

	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "door-north")


func test_a_door_is_not_clickable_in_combat() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(SceneFixtures.scene({
		"roomId": "crypt", "width": 12, "height": 12, "mode": "COMBAT",
		"props": [],
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
		# The server refuses it too (CrossExitTest.exitsAreIllegalInCombat); the client
		# should not offer a click that can only ever come back as an error.
		"combat": {"activeId": "fighter", "round": 1, "order": [],
			"moves": [], "targets": []},
	}))
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	assert_true(overlay.intent("", Vector2i(6, 11), "door-north").is_empty())


func test_a_click_event_in_window_space_still_moves_the_right_square() -> void:
	# WorldView is 480 wide and scaled onto the window. A raw window-space
	# mouse event must be converted before the raycast or the click lands
	# one tile off. This test would pass a forgetful event.position if we
	# handed it viewport coords — so it hands it window coords.
	var host := SubViewportContainer.new()
	add_child_autofree(host)
	host.position = Vector2.ZERO
	host.size = Vector2(480, 270)
	host.stretch = true
	host.scale = Vector2(4, 4)
	var sub := SubViewport.new()
	sub.size = Vector2i(480, 270)
	sub.own_world_3d = true
	sub.handle_input_locally = true
	host.add_child(sub)
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return
	var world: Node3D = packed.instantiate()
	sub.add_child(world)
	await wait_process_frames(3)

	assert_true(world.has_method("pick_at"), "pick_at")
	assert_true(world.has_method("viewport_from_window"), "viewport_from_window")
	if not world.has_method("pick_at") or not world.has_method("viewport_from_window"):
		return

	var square := Vector2i(4, 3)
	var ground: Vector3 = world.grid_to_world("crypt", square.x, square.y)
	var cam: Camera3D = world.get_node("Camera3D") as Camera3D
	var viewport_pos: Vector2 = cam.unproject_position(ground)
	var window_pos: Vector2 = world.get_parent().get_parent().get_global_transform_with_canvas() * viewport_pos

	var converted: Vector2 = world.viewport_from_window(window_pos)
	assert_almost_eq(converted.x, viewport_pos.x, 2.0, "scale conversion")
	assert_almost_eq(converted.y, viewport_pos.y, 2.0)

	world.handle_pointer(converted, true)
	assert_eq(Net.outbound.size(), 1, "a legal exploration floor click sends moveTo")
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "moveTo")
	assert_eq(Net.outbound[0]["actorId"], "keeper")
	assert_eq(int(Net.outbound[0]["x"]), square.x,
		"converted window click hits the unprojected square, not a neighbour")
	assert_eq(int(Net.outbound[0]["y"]), square.y)


# ---- Picking once the party has left the entrance


## The party has crossed north into the gallery. The crypt stays on the board behind them, DIM,
## still at the world origin; the gallery — the room they are in — stands at (0, -14).
##
## `coveredWalls` is what the server actually ships: the crypt is the entrance, so it owns the
## plane the two share and the gallery's whole south run is left to it. The gallery still hangs
## the door in it, because the pointer only ever reaches the current room's walls — which is the
## thing `test_the_door_back_is_still_clickable_from_the_room_beyond` is here to keep true.
func _crossed() -> Dictionary:
	return {
		"roomId": "gallery", "mode": "EXPLORATION", "combat": null, "blocked": [],
		"entities": [{"id": "keeper", "kind": "fighter", "name": "Roderick", "x": 5, "y": 8,
			"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
		"rooms": [
			{"roomId": "crypt", "width": 12, "height": 12, "floorType": "CRACKED_STONE",
				"wallType": "CARVED", "lighting": "TORCHLIT", "props": [], "visited": true,
				"originX": 0.0, "originZ": 0.0, "coveredWalls": [],
				"exits": [{"id": "door-north", "x": 6, "y": 11,
					"direction": "NORTH", "toRoomId": "gallery"}]},
			{"roomId": "gallery", "width": 10, "height": 16, "floorType": "TILED",
				"wallType": "CARVED", "lighting": "TORCHLIT", "props": [], "visited": true,
				"originX": 0.0, "originZ": -14.0, "coveredWalls": SceneFixtures.south_run(10),
				"exits": [{"id": "door-south", "x": 5, "y": 0,
					"direction": "SOUTH", "toRoomId": "crypt"}]},
		],
	}


func test_a_floor_click_lands_in_the_room_the_party_is_in() -> void:
	var world := _world_with_scene(_crossed())
	await wait_process_frames(2)
	var cam := world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var square := Vector2i(3, 10)
	var at: Vector2 = cam.unproject_position(world.grid_to_world("gallery", square.x, square.y))
	assert_eq(world.pick_at(at).get("square", null), square,
		"a square in the gallery is a square in the gallery, not one off the board")

	world.handle_pointer(at, true)
	assert_eq(Net.outbound.size(), 1, "and it is still a move")
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "moveTo")
	assert_eq(int(Net.outbound[0]["x"]), square.x)
	assert_eq(int(Net.outbound[0]["y"]), square.y)


func test_a_click_on_a_room_you_are_not_in_does_nothing() -> void:
	# Visible is not addressable (epic decision 8). The crypt is drawn, so it can be clicked at;
	# read against the gallery its floor resolves to a perfectly plausible gallery square, and
	# the party walks somewhere nobody pointed at.
	var world := _world_with_scene(_crossed())
	await wait_process_frames(2)
	var cam := world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var at: Vector2 = cam.unproject_position(world.grid_to_world("crypt", 2, 2))
	var picked: Dictionary = world.pick_at(at)
	assert_null(picked.get("square", null), "a room you are not in is scenery")

	var overlay := _overlay(world)
	if overlay == null:
		return
	assert_true(overlay.intent(String(picked.get("entity_id", "")), picked.get("square", null),
		String(picked.get("target_id", ""))).is_empty(), "no intent")

	world.handle_pointer(at, true)
	assert_eq(Net.outbound.size(), 0, "and nothing goes out")


func test_the_door_back_is_still_clickable_from_the_room_beyond() -> void:
	# The way home is a segment of the gallery's own south wall, fourteen squares from the world
	# origin. Picking has to reach it there, or a crossing is a one-way trip.
	var world := _world_with_scene(_crossed())
	await wait_process_frames(2)
	var cam := world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var door: Node3D = null
	for child in (world.get_node("Room/Walls") as Node3D).get_children():
		if child.has_meta("exit_id"):
			door = child as Node3D
	assert_not_null(door, "the gallery's door back to the crypt")
	if door == null:
		return
	assert_eq(String(door.get_meta("exit_id")), "door-south")

	var leaf := AABB()
	var first := true
	for mesh in door.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	assert_false(first, "the doorway instanced a mesh")
	if first:
		return

	var at: Vector2 = cam.unproject_position(leaf.get_center())
	assert_eq(String(world.pick_at(at).get("target_id", "")), "door-south")

	world.handle_pointer(at, true)
	assert_eq(Net.outbound.size(), 1, "and it still crosses")
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "enterExit")
	assert_eq(String(Net.outbound[0]["exitId"]), "door-south")


func test_no_camera_corner_makes_a_room_you_are_not_in_clickable() -> void:
	# A doorway wins a pick when the ray leaves the room without touching its floor, and with a
	# neighbour drawn that ray now lands on something. Swept over all four corners and the whole
	# of the crypt's floor: never a square, and never a target that is not this room's own way
	# out. The one thing a click on the room beyond can do is take the door it came through —
	# which is the door you are looking through, at the floor just past it. Anything else would
	# be the party acting in a room they are not in.
	var world := _world_with_scene(_crossed())
	await wait_process_frames(2)
	var rig: CameraRig = world.rig
	assert_not_null(rig, "Camera3D")
	if rig == null:
		return

	var squares: Array[String] = []
	var targets: Array[String] = []
	var crossings := 0
	for _corner in 4:
		for x in range(12):
			for y in range(12):
				var at: Vector2 = rig.unproject_position(world.grid_to_world("crypt", x, y))
				var picked: Dictionary = world.pick_at(at)
				if picked.get("square", null) != null:
					squares.append("corner %d, crypt (%d, %d)" % [rig.corner, x, y])
				var target := String(picked.get("target_id", ""))
				if target == "door-south":
					crossings += 1
				elif not target.is_empty():
					targets.append("corner %d, crypt (%d, %d): %s" % [rig.corner, x, y, target])
		rig.rotate_by(1)
		rig._process(1.0)
		rig._process(1.0)

	assert_eq(squares, [] as Array[String], "a square in a room the party is not in")
	assert_eq(targets, [] as Array[String], "something other than the way out was addressable")
	assert_gt(crossings, 0,
		"the floor seen through the open door still picks the door, or crossing back by "
		+ "pointing at the room you came from has quietly stopped working")


# ---- Props as click targets (emberdelve-7m6)


func _doorway_with_actioned_prop() -> Dictionary:
	var scene := DOORWAY.duplicate(true)
	scene["props"].append({
		"id": "relic-fixture", "type": "RUBBLE", "x": 6, "y": 4, "rotation": 0,
		"hidden": false, "actions": ["take"],
	})
	return SceneFixtures.scene(scene)


func _prop_mesh_aabb(world: Node, prop_id: String) -> AABB:
	var prop := world.get_node_or_null("Props/crypt/%s" % prop_id) as Node3D
	assert_not_null(prop, "prop %s" % prop_id)
	if prop == null:
		return AABB()
	var leaf := AABB()
	var first := true
	for mesh in prop.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	assert_false(first, "prop %s has a mesh" % prop_id)
	return leaf


func test_brazier_east_does_not_steal_the_north_door() -> void:
	# Regression: brazier-east stands between the camera and the north wall. Scenery must not
	# shadow the doorway when the ray passes through both.
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var door: Node3D = null
	for child in (world.get_node("Room/Walls") as Node3D).get_children():
		if child.has_meta("exit_id"):
			door = child as Node3D
	assert_not_null(door, "the tagged doorway")
	if door == null:
		return
	var leaf := AABB()
	var first := true
	for mesh in door.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	if first:
		return

	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "door-north",
		"brazier-east is scenery; the doorway still wins")


func test_an_actioned_prop_is_returned_by_pick_at() -> void:
	var world := _world_with_scene(_doorway_with_actioned_prop())
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var leaf := _prop_mesh_aabb(world, "relic-fixture")
	if leaf.size == Vector3.ZERO:
		return
	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "relic-fixture")


func test_a_prop_without_actions_is_not_a_pick_target() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	if cam == null:
		return

	var leaf := _prop_mesh_aabb(world, "brazier-east")
	if leaf.size == Vector3.ZERO:
		return
	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "",
		"scenery props are not pick targets")


func test_the_carried_torch_is_not_a_pick_target() -> void:
	var world := _world_with_scene(SceneFixtures.scene(DOORWAY))
	await wait_process_frames(2)
	var fighter := world.get_node_or_null("Tokens/fighter")
	assert_not_null(fighter, "fighter")
	if fighter == null:
		return
	var torch := fighter.find_child("CarriedTorch", true, false)
	assert_not_null(torch, "CarriedTorch on handslot.l")
	if torch == null:
		return
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	if cam == null:
		return

	var leaf := AABB()
	var first := true
	for mesh in torch.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	if first:
		return

	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "",
		"the carried torch is never a pick target")


func test_committing_a_prop_intent_sends_use_prop() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(_doorway_with_actioned_prop())
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	var action: Dictionary = overlay.intent("", Vector2i(6, 4), "relic-fixture")
	assert_eq(String(action.get("kind", "")), "prop")
	assert_eq(String(action.get("prop_id", "")), "relic-fixture")
	assert_eq(String(action.get("action", "")), "take")

	overlay.commit(action)

	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "useProp")
	assert_eq(String(Net.outbound[0].get("propId", "")), "relic-fixture")
	assert_eq(String(Net.outbound[0].get("action", "")), "take")


func test_a_prop_intent_does_not_look_like_a_floor_move() -> void:
	var world := _world_with_scene(_doorway_with_actioned_prop())
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return
	var hover := overlay.get_node("Hover") as MeshInstance3D

	overlay.set_hover(Vector2i(4, 4), "move")
	var move: Color = (hover.material_override as StandardMaterial3D).albedo_color
	overlay.set_hover(Vector2i(6, 4), "prop")
	var prop: Color = (hover.material_override as StandardMaterial3D).albedo_color

	assert_ne(move, prop, "a prop intent must not look like a floor move")


func _doorway_with_reliquary() -> Dictionary:
	var scene := DOORWAY.duplicate(true)
	scene["props"].append({
		"id": "reliquary", "type": "CONTAINER", "appearance": "chest",
		"x": 4, "y": 2, "rotation": 0,
		"hidden": false, "actions": ["take"],
	})
	scene["exits"].append({
		"id": "stair-south", "x": 6, "y": 0, "direction": "SOUTH",
		"toRoomId": "", "wayOut": true,
	})
	return SceneFixtures.scene(scene)


func test_an_actioned_reliquary_is_returned_by_pick_at() -> void:
	var world := _world_with_scene(_doorway_with_reliquary())
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var leaf := _prop_mesh_aabb(world, "reliquary")
	if leaf.size == Vector3.ZERO:
		return
	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "reliquary")


func test_committing_a_way_out_asks_instead_of_crossing() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(_doorway_with_reliquary())
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	var action: Dictionary = overlay.intent("", Vector2i(6, 0), "stair-south")
	assert_eq(String(action.get("kind", "")), "exit")
	overlay.commit(action)

	assert_eq(Net.outbound.size(), 0, "a way-out click must not send enterExit")
	assert_false(Table.leave_confirm.is_empty(), "the chin confirm is pending")
	assert_eq(String(Table.leave_confirm.get("exit_id", "")), "stair-south")


func test_staying_from_the_way_out_confirm_does_not_send() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(_doorway_with_reliquary())
	var overlay := _overlay(world)
	if overlay == null:
		return
	overlay.commit(overlay.intent("", Vector2i(6, 0), "stair-south"))
	Table.stay()

	assert_eq(Net.outbound.size(), 0)
	assert_true(Table.leave_confirm.is_empty())


func test_leaving_from_the_way_out_confirm_sends_enter_exit() -> void:
	Net.outbound.clear()
	var world := _world_with_scene(_doorway_with_reliquary())
	var overlay := _overlay(world)
	if overlay == null:
		return
	overlay.commit(overlay.intent("", Vector2i(6, 0), "stair-south"))
	Table.confirm_leave()

	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "stair-south")
	assert_true(Table.leave_confirm.is_empty())


# ---- Dense room: scenery must not shadow a handle (emberdelve-4h9.7 / 7m6)


func _authored_crypt_scene() -> Dictionary:
	var Preview = load("res://dev/preview_room.gd")
	var crypt: Dictionary = Preview.view_of("crypt")
	assert_false(crypt.has("error"), str(crypt.get("error", "")))
	var props: Array = crypt.get("props", [])
	assert_gte(props.size(), 20, "the authored crypt must be densely furnished")
	var kits := {}
	for prop in props:
		var appearance := String(prop.get("appearance", ""))
		if appearance.is_empty():
			continue
		# kit is the folder prefix of the mesh path — appearances.gd owns the map.
		kits[appearance] = true
	assert_gte(props.size(), 20)
	crypt["mode"] = "EXPLORATION"
	crypt["combat"] = null
	crypt["blocked"] = []
	crypt["entities"] = [{
		"id": "fighter", "kind": "fighter", "name": "Roderick",
		"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true,
	}]
	return SceneFixtures.scene(crypt)


func test_the_authored_crypt_draws_from_at_least_two_kits() -> void:
	var Appear = load("res://world/appearances.gd")
	assert_not_null(Appear, "appearances.gd")
	if Appear == null or not Appear.has_method("spec_for"):
		assert_true(false, "appearances.gd.spec_for")
		return
	var Preview = load("res://dev/preview_room.gd")
	var crypt: Dictionary = Preview.view_of("crypt")
	var kits := {}
	for prop in crypt.get("props", []):
		var spec: Dictionary = Appear.spec_for(String(prop.get("appearance", "")))
		if spec.is_empty():
			continue
		kits[String(spec.get("kit", ""))] = true
	assert_gte(kits.size(), 2, "kits used: " + str(kits.keys()))


func test_dense_scenery_does_not_steal_the_north_door() -> void:
	var world := _world_with_scene(_authored_crypt_scene())
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var door: Node3D = null
	for child in (world.get_node("Room/Walls") as Node3D).get_children():
		if child.has_meta("exit_id") and String(child.get_meta("exit_id")) == "door-north":
			door = child as Node3D
	assert_not_null(door, "the tagged north doorway")
	if door == null:
		return
	var leaf := AABB()
	var first := true
	for mesh in door.find_children("*", "MeshInstance3D", true, false):
		var box: AABB = mesh.global_transform * mesh.get_aabb()
		leaf = box if first else leaf.merge(box)
		first = false
	if first:
		return

	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "door-north",
		"scenery in a dense room must not shadow the doorway")


func test_dense_scenery_does_not_steal_the_reliquary() -> void:
	var world := _world_with_scene(_authored_crypt_scene())
	await wait_process_frames(2)
	var cam: Camera3D = world.get_node_or_null("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return

	var leaf := _prop_mesh_aabb(world, "reliquary")
	if leaf.size == Vector3.ZERO:
		return
	var picked: Dictionary = world.pick_at(cam.unproject_position(leaf.get_center()))
	assert_eq(String(picked.get("target_id", "")), "reliquary",
		"scenery must not shadow an actioned handle")
