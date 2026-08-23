extends GutTest

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
	Table.set_scene(CRYPT.duplicate(true))


func _world() -> Node3D:
	var script: GDScript = load("res://world/world.gd")
	assert_not_null(script, "world.gd")
	if script == null:
		return Node3D.new()
	var node: Node3D = script.new()
	add_child_autofree(node)
	return node


func test_grid_to_world_centres_the_room_on_the_origin() -> void:
	var world := _world()
	if not world.has_method("grid_to_world"):
		assert_true(world.has_method("grid_to_world"))
		return
	# 12×12: square (0, 0) sits at (-5.5, 0, +5.5) — north-west, +Z is south.
	assert_eq(world.grid_to_world(0, 0), Vector3(-5.5, 0.0, 5.5))
	assert_eq(world.grid_to_world(11, 11), Vector3(5.5, 0.0, -5.5))
	assert_eq(world.grid_to_world(2, 2), Vector3(-3.5, 0.0, 3.5))


func test_world_to_grid_inverts_grid_to_world() -> void:
	var world := _world()
	if not world.has_method("world_to_grid"):
		assert_true(world.has_method("world_to_grid"))
		return
	for x in 12:
		for y in 12:
			var point: Vector3 = world.grid_to_world(x, y)
			assert_eq(world.world_to_grid(point), Vector2i(x, y), "square (%d, %d)" % [x, y])


func test_grid_conversion_reads_the_live_room_size_from_table() -> void:
	var world := _world()
	if not world.has_method("grid_to_world"):
		assert_true(world.has_method("grid_to_world"))
		return
	var odd := CRYPT.duplicate(true)
	odd["width"] = 7
	odd["height"] = 5
	Table.set_scene(odd)
	# Odd width: the centre of square (3, 2) is the origin.
	assert_eq(world.grid_to_world(3, 2), Vector3.ZERO)
	assert_eq(world.world_to_grid(Vector3.ZERO), Vector2i(3, 2))


func _rig() -> Camera3D:
	var script: GDScript = load("res://world/camera_rig.gd")
	assert_not_null(script, "camera_rig.gd")
	if script == null:
		return Camera3D.new()
	var node: Camera3D = script.new()
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


func test_frame_combat_pulls_the_camera_back() -> void:
	var rig := _rig()
	if not rig.has_method("frame"):
		assert_true(rig.has_method("frame"))
		return
	var start: float = rig.half_height
	assert_almost_eq(start, 5.4, 0.0001)
	rig.frame("COMBAT")
	rig._process(1.1)
	assert_gt(rig.half_height, start, "combat is a pull-back, not a snap of the same zoom")
	assert_lte(rig.half_height, 10.0)


func test_follow_eases_the_focus_toward_the_point() -> void:
	var rig := _rig()
	if not rig.has_method("follow"):
		assert_true(rig.has_method("follow"))
		return
	var before := rig.global_position
	rig.follow(Vector3(4.0, 0.0, -3.0))
	rig._process(0.34)
	assert_ne(rig.global_position, before, "the camera actually uses the followed point")


func test_snap_focus_is_idempotent_on_a_pixel() -> void:
	var rig := _rig()
	if not rig.has_method("_snap_focus"):
		assert_true(rig.has_method("_snap_focus"))
		return
	rig._process(0.0)
	var raw := Vector3(0.37, 0.0, 0.11)
	var once: Vector3 = rig._snap_focus(raw)
	var twice: Vector3 = rig._snap_focus(once)
	assert_almost_eq(once.x, twice.x, 0.0001)
	assert_almost_eq(once.y, twice.y, 0.0001)
	assert_almost_eq(once.z, twice.z, 0.0001)
	assert_ne(once, raw, "a fractional focus is moved onto the pixel grid")
