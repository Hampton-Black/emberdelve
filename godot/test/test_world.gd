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
	Table.set_scene(fighting)
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
	assert_almost_eq(world.rig.half_height, 5.4, 0.0001)
	Table.mode = "COMBAT"
	Table.scene_changed.emit()
	Table.mode_changed.emit("COMBAT")
	assert_eq(world.rig._framing_t, 0.0, "the pull-back is a tween, not a snap")
	assert_almost_eq(world.rig.half_height, 5.4, 0.0001,
		"half_height must not already be the combat target")


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


# ---- Edge pass: depth + normals, not a second pixelation

func test_the_edge_shader_loads_and_names_the_two_three_js_strengths() -> void:
	var shader: Shader = load("res://world/pixel.gdshader")
	assert_not_null(shader, "pixel.gdshader")
	if shader == null:
		return
	# canvas_item rejects hint_depth_texture in 4.7.2; spatial is the engine path
	# that actually has DEPTH and NORMAL_ROUGHNESS.
	assert_eq(shader.get_mode(), Shader.MODE_SPATIAL)
	assert_true(shader.code.contains("hint_depth_texture"), "must sample depth")
	assert_true(shader.code.contains("hint_normal_roughness_texture"), "must sample normals")
	assert_true(shader.code.contains("uniform float normal_edge_strength"),
		"tunable in the inspector, not a literal in the body")
	assert_true(shader.code.contains("uniform float depth_edge_strength"),
		"tunable in the inspector, not a literal in the body")
	assert_true(shader.code.contains("normal_edge_strength : hint_range(0.0, 2.0) = 0.5"))
	assert_true(shader.code.contains("depth_edge_strength : hint_range(0.0, 2.0) = 0.25"))


func test_the_world_wears_the_edge_pass_at_three_js_strengths() -> void:
	var world := _world_tree()
	var shader: Shader = load("res://world/pixel.gdshader")
	assert_not_null(shader, "pixel.gdshader")
	if shader == null:
		return
	var mat := _edge_material(world, shader)
	assert_not_null(mat, "world.tscn must attach pixel.gdshader")
	if mat == null:
		return
	assert_eq(mat.shader, shader)
	var n: Variant = mat.get_shader_parameter("normal_edge_strength")
	var d: Variant = mat.get_shader_parameter("depth_edge_strength")
	if n == null or d == null:
		# Dummy / headless may not reflect defaults; the scene file is the pin.
		var scene := FileAccess.get_file_as_string("res://world/world.tscn")
		assert_true(scene.contains("shader_parameter/normal_edge_strength = 0.5"))
		assert_true(scene.contains("shader_parameter/depth_edge_strength = 0.25"))
	else:
		assert_almost_eq(float(n), 0.5, 0.0001, "Renderer.ts:358 normalEdgeStrength")
		assert_almost_eq(float(d), 0.25, 0.0001, "Renderer.ts:358 depthEdgeStrength")


func _edge_material(root: Node, shader: Shader) -> ShaderMaterial:
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		for child in node.get_children():
			stack.append(child)
		if node is MeshInstance3D:
			var mesh := node as MeshInstance3D
			var mat: Material = mesh.material_override
			if mat == null:
				mat = mesh.get_surface_override_material(0)
			if mat is ShaderMaterial and (mat as ShaderMaterial).shader == shader:
				return mat
		if node is CanvasItem:
			var canvas_mat := (node as CanvasItem).material
			if canvas_mat is ShaderMaterial and (canvas_mat as ShaderMaterial).shader == shader:
				return canvas_mat
	return null
