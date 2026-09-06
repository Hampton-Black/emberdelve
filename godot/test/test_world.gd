extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## Grid conversion is the one number later tasks all hang off. Square (0, 0) is the north-west
## corner of a room centred on the origin; +y on the grid is north, which is -Z in the world.

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}


func before_each() -> void:
	Table.reset()
	Table.set_scene(SceneFixtures.scene(CRYPT))


func _world() -> Node3D:
	var script: GDScript = load("res://world/world.gd")
	assert_not_null(script, "world.gd")
	if script == null:
		return Node3D.new()
	var node: Node3D = script.new()
	add_child_autofree(node)
	return node


func _world_with_room(w: int, h: int) -> Node3D:
	var scene := CRYPT.duplicate(true)
	scene["width"] = w
	scene["height"] = h
	Table.set_scene(SceneFixtures.scene(scene))
	return _world()


func test_the_current_room_is_still_centred_on_the_origin() -> void:
	var world := _world_with_room(12, 12)
	# Unchanged behaviour for the room the party is in — the whole point of taking the room id
	# as an argument now is that a second room can exist without moving the first.
	assert_eq(world.grid_to_world("crypt", 0, 0), Vector3(-5.5, 0.0, 5.5))
	assert_eq(world.grid_to_world("crypt", 11, 11), Vector3(5.5, 0.0, -5.5))
	assert_eq(world.grid_to_world("crypt", 2, 2), Vector3(-3.5, 0.0, 3.5))


func test_a_registered_neighbour_sits_where_it_was_put() -> void:
	var world := _world_with_room(12, 12)
	world.register_room("gallery", Vector2i(10, 16), Vector3(0.0, 0.0, -14.0))

	# Its own centre, offset by its origin: a 10x16 room's (5, 8) is its middle.
	assert_eq(world.grid_to_world("gallery", 5, 8), Vector3(0.5, 0.0, -14.5))
	assert_eq(world.room_origin("gallery"), Vector3(0.0, 0.0, -14.0))


func test_an_unregistered_room_falls_back_to_the_current_one() -> void:
	var world := _world_with_room(12, 12)
	# A diff naming a room the client has not been told about must not put a token at NaN.
	assert_eq(world.grid_to_world("nowhere", 2, 2), world.grid_to_world("crypt", 2, 2))


func test_world_to_grid_still_inverts_the_current_room() -> void:
	var world := _world_with_room(12, 12)
	for x in range(12):
		for y in range(12):
			var point: Vector3 = world.grid_to_world("crypt", x, y)
			assert_eq(world.world_to_grid(point), Vector2i(x, y), "square (%d, %d)" % [x, y])


func test_grid_conversion_reads_the_live_room_size_from_table() -> void:
	var world := _world_with_room(7, 5)
	if not world.has_method("grid_to_world"):
		assert_true(world.has_method("grid_to_world"))
		return
	# Odd width: the centre of square (3, 2) is the origin.
	assert_eq(world.grid_to_world("crypt", 3, 2), Vector3.ZERO)
	assert_eq(world.world_to_grid(Vector3.ZERO), Vector2i(3, 2))


func _rig() -> Camera3D:
	var script: GDScript = load("res://world/camera_rig.gd")
	assert_not_null(script, "camera_rig.gd")
	if script == null:
		return Camera3D.new()
	var node: Camera3D = script.new()
	add_child_autofree(node)
	return node


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var node: Node3D = packed.instantiate()
	add_child_autofree(node)
	return node


func test_rotate_by_steps_one_of_four_corners() -> void:
	var rig := _rig()
	if not rig.has_method("rotate_by"):
		assert_true(rig.has_method("rotate_by"))
		return
	assert_eq(rig.corner, 0)
	rig.rotate_by(1)
	assert_eq(rig.corner, 1)
	rig.rotate_by(1)
	assert_eq(rig.corner, 2)
	rig.rotate_by(-1)
	assert_eq(rig.corner, 1)
	rig.rotate_by(-2)
	assert_eq(rig.corner, 3)


func test_q_and_e_step_the_corner_from_unhandled_input() -> void:
	var rig := _rig()
	if not rig.has_method("_unhandled_input"):
		assert_true(rig.has_method("_unhandled_input"))
		return
	var q := InputEventKey.new()
	q.keycode = KEY_Q
	q.pressed = true
	rig._unhandled_input(q)
	assert_eq(rig.corner, 3, "Q turns counter-clockwise")
	var e := InputEventKey.new()
	e.keycode = KEY_E
	e.pressed = true
	rig._unhandled_input(e)
	assert_eq(rig.corner, 0, "E turns clockwise, back to the start")


func test_a_first_scene_settles_the_camera() -> void:
	# Hello / a new roomId: snap onto the scene's own framing. Easing here would pull back
	# from exploration on a reconnect that landed mid-fight.
	Table.reset()
	var world := _world_tree()
	if world.rig == null:
		assert_not_null(world.rig, "Camera3D")
		return
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	Table.set_scene(SceneFixtures.scene(fighting))
	var target: Dictionary = world.rig._framing_target()
	assert_eq(world.rig._framing_t, 1.0, "hello snaps; easing would leave the tween in flight")
	assert_almost_eq(world.rig.half_height, target["half_height"], 0.0001)
	assert_gt(world.rig.half_height, 5.4, "a combat hello opens on the tactical view")
	assert_almost_eq(world.rig._focus.x, target["focus"].x, 0.0001)
	assert_almost_eq(world.rig._focus.y, target["focus"].y, 0.0001)
	assert_almost_eq(world.rig._focus.z, target["focus"].z, 0.0001)


func test_an_in_session_mode_change_eases_framing() -> void:
	# Same room, ModeChanged: the combat ceremony is the pull-back. Settling on every
	# scene_changed would snap it away.
	var world := _world_tree()
	if world.rig == null:
		assert_not_null(world.rig, "Camera3D")
		return
	assert_eq(world.rig._framing_t, 1.0)
	assert_almost_eq(world.rig.half_height, world.rig.EXPLORATION_HALF_HEIGHT, 0.0001)
	Table.mode = "COMBAT"
	Table.scene_changed.emit()
	Table.mode_changed.emit("COMBAT")
	assert_eq(world.rig._framing, "COMBAT")
	assert_eq(world.rig._framing_t, 0.0, "the pull-back is a tween, not a snap")
	assert_almost_eq(world.rig.half_height, world.rig.EXPLORATION_HALF_HEIGHT, 0.0001,
		"half_height must not already be the combat target")


func test_opening_combat_through_diffs_starts_the_pull_back() -> void:
	# Debug `start combat` arrives as ModeChanged + CombatChanged, not as a fresh roomId.
	Clock.silence_now()
	var world := _world_tree()
	if world.rig == null:
		assert_not_null(world.rig, "Camera3D")
		return
	assert_almost_eq(world.rig.half_height, world.rig.EXPLORATION_HALF_HEIGHT, 0.0001)
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat()},
	])
	await wait_frames(2)
	assert_eq(world.rig._framing, "COMBAT")
	# Clock.mark costs a frame, so _process may have already eaten a tick. settle() would
	# have _framing_t == 1; frame() leaves the tween in flight.
	assert_lt(world.rig._framing_t, 1.0, "in-session combat uses frame(), not settle()")
	assert_lt(world.rig.half_height, world.rig._combat_half_height() - 1.0,
		"half_height must not already be the combat target")


func test_framing_constants_match_renderer_and_outlast_the_pull_back() -> void:
	var rig := _rig()
	assert_almost_eq(rig.EXPLORATION_HALF_HEIGHT, 5.4, 0.0001)
	assert_almost_eq(rig.COMBAT_MAX_HALF_HEIGHT, 10.0, 0.0001)
	assert_almost_eq(rig.FRAMING_SECONDS, 1.1, 0.0001)
	assert_almost_eq(rig.FOLLOW_SECONDS, 0.34, 0.0001)
	assert_almost_eq(rig.FOLLOW_SLACK, 1.4, 0.0001)
	assert_eq(Table.CEREMONY_MS, 1200)
	assert_lt(int(round(rig.FRAMING_SECONDS * 1000.0)), Table.CEREMONY_MS,
		"the ceremony hold outlasts the pull-back so the DM does not talk over it")


func test_frame_combat_pulls_the_camera_back() -> void:
	var rig := _rig()
	if not rig.has_method("frame"):
		assert_true(rig.has_method("frame"))
		return
	rig.set_process(false)
	var start: float = rig.half_height
	assert_almost_eq(start, rig.EXPLORATION_HALF_HEIGHT, 0.0001)
	rig.frame("COMBAT")
	assert_eq(rig._framing_t, 0.0)
	rig._process(rig.FRAMING_SECONDS - 0.05)
	assert_lt(rig._framing_t, 1.0, "still easing before FRAMING_SECONDS")
	assert_gt(rig.half_height, start)
	assert_lt(rig.half_height, rig.COMBAT_MAX_HALF_HEIGHT + 0.0001)
	rig._process(0.05)
	assert_almost_eq(rig._framing_t, 1.0, 0.0001)
	assert_almost_eq(rig.half_height, rig._combat_half_height(), 0.0001)
	assert_lte(rig.half_height, rig.COMBAT_MAX_HALF_HEIGHT)


func test_follow_eases_the_focus_toward_the_point() -> void:
	var rig := _rig()
	if not rig.has_method("follow"):
		assert_true(rig.has_method("follow"))
		return
	rig.set_process(false)
	var before := rig.global_position
	rig.follow(Vector3(4.0, 0.0, -3.0))
	rig._process(rig.FOLLOW_SECONDS)
	assert_ne(rig.global_position, before, "the camera actually uses the followed point")


func test_a_follow_shorter_than_slack_still_moves() -> void:
	# FOLLOW_SLACK is clamp-to-room slack in Renderer.ts, not a deadzone. A 0.5-square
	# follow must still chase, or someone "restored" a deadzone Three.js never had.
	var rig := _rig()
	rig.set_process(false)
	rig.settle("EXPLORATION")
	var before: Vector3 = rig._focus
	rig.follow(Vector3(0.5, 0.0, 0.0))
	rig._process(rig.FOLLOW_SECONDS)
	var after: Vector3 = rig._focus
	assert_false(after.is_equal_approx(before),
		"FOLLOW_SLACK must not swallow a follow shorter than 1.4")


func test_exploration_follow_is_the_party_centroid_not_the_fighter() -> void:
	# Renderer.ts partyCentre: every isPlayerControlled token, never "the fighter".
	Table.reset()
	var party := CRYPT.duplicate(true)
	party["entities"] = [
		{"id": "keeper", "kind": "fighter", "name": "Alda", "x": 1, "y": 1,
			"hp": 12, "maxHp": 12, "isPlayerControlled": true},
		{"id": "fighter", "kind": "goblin", "name": "Decoy", "x": 10, "y": 10,
			"hp": 7, "maxHp": 7, "isPlayerControlled": false},
		{"id": "ally", "kind": "fighter", "name": "Bram", "x": 7, "y": 3,
			"hp": 12, "maxHp": 12, "isPlayerControlled": true},
	]
	Table.set_scene(SceneFixtures.scene(party))
	var world := _world_tree()
	if world.rig == null:
		assert_not_null(world.rig, "Camera3D")
		return
	var a: Vector3 = world.grid_to_world("crypt", 1, 1)
	var b: Vector3 = world.grid_to_world("crypt", 7, 3)
	var expected := (a + b) * 0.5
	assert_almost_eq(world.rig._follow_point.x, expected.x, 0.0001)
	assert_almost_eq(world.rig._follow_point.z, expected.z, 0.0001)
	assert_false(world.rig._follow_point.is_equal_approx(a),
		"must not follow a single party member")
	assert_false(world.rig._follow_point.is_equal_approx(world.grid_to_world("crypt", 10, 10)),
		"must not follow the entity whose id is fighter")


func _combat() -> Dictionary:
	return {
		"order": [
			{"entityId": "fighter", "name": "Roderick", "initiative": 18,
				"isPlayerControlled": true},
			{"entityId": "goblin", "name": "Vessk", "initiative": 11,
				"isPlayerControlled": false},
		],
		"activeId": "fighter", "round": 1, "movementRemaining": 6, "actionAvailable": true,
		"legalMoves": [{"x": 3, "y": 2}], "legalTargets": [],
	}


func test_the_pixel_pipeline_is_gone() -> void:
	var world := _world_tree()
	assert_null(world.get_node_or_null("Camera3D/Edges"),
		"the depth/normal edge overlay is not in the world")
	assert_false(ResourceLoader.exists("res://world/pixel.gdshader"),
		"pixel.gdshader is deleted")
	var scene := FileAccess.get_file_as_string("res://world/world.tscn")
	assert_false(scene.contains("pixel.gdshader"))
	assert_false(_rig().has_method("_snap_focus"),
		"camera focus is not quantised to a pixel grid")
