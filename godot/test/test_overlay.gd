extends GutTest

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
	Table.set_scene(CRYPT.duplicate(true))
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
	return scene


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
	Table.set_scene(dead)
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
	var ground: Vector3 = world.grid_to_world(square.x, square.y)
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


func test_clicking_a_door_square_is_an_exit_not_a_move() -> void:
	var world := _world_with_scene({
		"roomId": "crypt",
		"width": 12,
		"height": 12,
		"mode": "EXPLORATION",
		"props": [],
		"exits": [{
			"id": "door-north",
			"x": 6,
			"y": 11,
			"direction": "NORTH",
			"toRoomId": "gallery",
		}],
		"entities": [{
			"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true,
		}],
		"combat": null,
	})
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	var on_the_door: Dictionary = overlay.intent("", Vector2i(6, 11))
	assert_eq(String(on_the_door.get("kind", "")), "exit")
	assert_eq(String(on_the_door.get("exit_id", "")), "door-north")

	var plain_floor: Dictionary = overlay.intent("", Vector2i(4, 4))
	assert_eq(String(plain_floor.get("kind", "")), "move",
		"an ordinary square is still a move")


func test_committing_an_exit_sends_enter_exit() -> void:
	Net.outbound.clear()
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12, "mode": "EXPLORATION",
		"props": [],
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 6, "y": 1, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
		"combat": null,
	})
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	overlay.commit(overlay.intent("", Vector2i(6, 11)))

	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "door-north")


func test_a_door_is_not_clickable_in_combat() -> void:
	Net.outbound.clear()
	var world := _world_with_scene({
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
	})
	var overlay := _overlay(world)
	assert_not_null(overlay, "Overlay")
	if overlay == null:
		return

	assert_true(overlay.intent("", Vector2i(6, 11)).is_empty())


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
	var ground: Vector3 = world.grid_to_world(square.x, square.y)
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
