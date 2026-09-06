extends GutTest

## Prop table, spawn, hidden/reveal, and the sarcophagus silhouette. Game facts stay in Table.

const TYPES: Array[String] = [
	"SARCOPHAGUS", "BRAZIER", "PILLAR", "RUBBLE", "ALCOVE", "DOOR",
]

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [
		{"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 7, "rotation": 0, "hidden": false},
		{"id": "fire", "type": "BRAZIER", "x": 2, "y": 8, "rotation": 0, "hidden": false},
		{"id": "column", "type": "PILLAR", "x": 3, "y": 4, "rotation": 0, "hidden": false},
		{"id": "pile", "type": "RUBBLE", "x": 2, "y": 2, "rotation": 45, "hidden": false},
		{"id": "gate", "type": "DOOR", "x": 6, "y": 11, "rotation": 180, "hidden": false},
		{"id": "niche", "type": "ALCOVE", "x": 9, "y": 6, "rotation": 270, "hidden": true},
	],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}


func before_each() -> void:
	Table.reset()
	Table.set_scene(CRYPT.duplicate(true))


func _table():
	assert_true(ResourceLoader.exists("res://world/prop_table.tres"), "prop_table.tres")
	if not ResourceLoader.exists("res://world/prop_table.tres"):
		return null
	return load("res://world/prop_table.tres")


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var node: Node3D = packed.instantiate()
	add_child_autofree(node)
	return node


func _props(world: Node) -> Node3D:
	return world.get_node_or_null("Props") as Node3D


func _prop(world: Node, id: String) -> Node3D:
	var holder := _props(world)
	if holder == null:
		return null
	return holder.get_node_or_null(id) as Node3D


func test_all_six_types_resolve() -> void:
	var table = _table()
	if table == null:
		return
	assert_true(table.has_method("scene_for"), "PropTable.scene_for(type)")
	if not table.has_method("scene_for"):
		return
	for type in TYPES:
		var packed: PackedScene = table.scene_for(type)
		assert_not_null(packed, type)
		if packed == null:
			continue
		var node: Node = packed.instantiate()
		assert_not_null(node, "%s instantiates" % type)
		node.free()


func test_hidden_props_are_not_instanced() -> void:
	var world := _world_tree()
	var holder := _props(world)
	assert_not_null(holder, "World/Props")
	if holder == null:
		return
	assert_eq(holder.get_child_count(), 5, "five visible props; the alcove stays hidden")
	assert_not_null(_prop(world, "tomb"), "tomb")
	assert_null(_prop(world, "niche"), "hidden alcove must not be in the tree")


func test_a_reveal_instances_the_prop() -> void:
	var world := _world_tree()
	var alcove := {"id": "niche", "type": "ALCOVE", "x": 9, "y": 6, "rotation": 270,
		"hidden": false}
	Table.prop_revealed.emit(alcove)
	assert_not_null(_prop(world, "niche"), "prop_revealed adds the alcove")
	assert_eq(_props(world).get_child_count(), 6)


func test_props_sit_at_grid_to_world() -> void:
	var world := _world_tree()
	var tomb := _prop(world, "tomb")
	assert_not_null(tomb, "tomb")
	if tomb == null:
		return
	assert_eq(tomb.position, world.grid_to_world("crypt", 6, 7))
	var fire := _prop(world, "fire")
	assert_not_null(fire, "fire")
	if fire == null:
		return
	assert_eq(fire.position, world.grid_to_world("crypt", 2, 8))


func test_rotation_is_degrees() -> void:
	var world := _world_tree()
	var pile := _prop(world, "pile")
	assert_not_null(pile, "pile")
	if pile == null:
		return
	assert_almost_eq(pile.rotation.y, deg_to_rad(45.0), 0.0001, "rubble 45°")
	var gate := _prop(world, "gate")
	assert_not_null(gate, "gate")
	if gate == null:
		return
	assert_almost_eq(gate.rotation.y, deg_to_rad(180.0), 0.0001, "door 180°")


func test_entity_motion_does_not_rebuild_props() -> void:
	var world := _world_tree()
	var tomb := _prop(world, "tomb")
	assert_not_null(tomb, "tomb")
	if tomb == null:
		return
	var kept: int = tomb.get_instance_id()
	var moved := CRYPT.duplicate(true)
	moved["entities"][0]["x"] = 5
	Table.set_scene(moved)
	var after := _prop(world, "tomb")
	assert_not_null(after, "tomb still there")
	if after == null:
		return
	assert_eq(after.get_instance_id(), kept,
		"the same nodes stay; rebuild would mint new ids")


func test_a_new_room_rebuilds_props() -> void:
	var world := _world_tree()
	var tomb := _prop(world, "tomb")
	assert_not_null(tomb, "tomb")
	if tomb == null:
		return
	var old_id: int = tomb.get_instance_id()
	var next := CRYPT.duplicate(true)
	next["roomId"] = "crypt-2"
	next["props"] = [
		{"id": "tomb", "type": "SARCOPHAGUS", "x": 4, "y": 4, "rotation": 90, "hidden": false},
	]
	Table.set_scene(next)
	var rebuilt := _prop(world, "tomb")
	assert_not_null(rebuilt, "fresh room still has the tomb")
	if rebuilt == null:
		return
	assert_ne(rebuilt.get_instance_id(), old_id, "a new roomId rebuilds the props")
	assert_eq(rebuilt.position, world.grid_to_world("crypt-2", 4, 4))
	assert_eq(_props(world).get_child_count(), 1)


func test_the_sarcophagus_is_a_tomb_not_a_crate() -> void:
	# Four things a crate (one box) cannot fake: a stepped silhouette, a taper toward
	# the top, a void under the lid, and a lid that has been worked from the inside.
	assert_true(ResourceLoader.exists("res://world/props/sarcophagus.tscn"), "sarcophagus.tscn")
	if not ResourceLoader.exists("res://world/props/sarcophagus.tscn"):
		return
	var packed: PackedScene = load("res://world/props/sarcophagus.tscn")
	var tomb: Node3D = packed.instantiate()
	add_child_autofree(tomb)
	assert_eq(tomb.scale, Vector3.ONE * 0.58, "TOMB_HEIGHT")
	assert_gte(_mesh_count(tomb), 5, "base, step, body, hollow, lid — not one box")
	var lid := tomb.get_node_or_null("Lid") as Node3D
	assert_not_null(lid, "Lid")
	if lid == null:
		return
	assert_false(lid.position.is_equal_approx(Vector3.ZERO),
		"lid is shifted off the chest")
	assert_gt(absf(lid.rotation.y) + absf(lid.rotation.z), 0.05,
		"turned and tilted far enough to read as disturbed")
	var skull_mount := lid.get_node_or_null("Skull") as Node3D
	assert_not_null(skull_mount, "Skull mount on the lid")
	if skull_mount:
		assert_gt(skull_mount.get_child_count(), 0, "skull fitted on the lid")
	var hollow := tomb.get_node_or_null("Hollow") as MeshInstance3D
	assert_not_null(hollow, "Hollow — the gap under the lid is darkness")
	if hollow == null:
		return
	var mat := hollow.material_override as BaseMaterial3D
	if mat == null:
		mat = hollow.get_active_material(0) as BaseMaterial3D
	assert_not_null(mat, "hollow material")
	if mat:
		assert_lt(mat.albedo_color.r + mat.albedo_color.g + mat.albedo_color.b, 0.2,
			"void is near-black, not more stone")
	var body := tomb.get_node_or_null("Body") as MeshInstance3D
	assert_not_null(body, "Body")
	if body and body.mesh is CylinderMesh:
		var cyl := body.mesh as CylinderMesh
		assert_ne(cyl.top_radius, cyl.bottom_radius, "the chest tapers toward the top")


func test_the_brazier_burns_ember_not_green() -> void:
	assert_true(ResourceLoader.exists("res://world/props/brazier.tscn"), "brazier.tscn")
	if not ResourceLoader.exists("res://world/props/brazier.tscn"):
		return
	var packed: PackedScene = load("res://world/props/brazier.tscn")
	var fire: Node = packed.instantiate()
	add_child_autofree(fire)
	var light := _find_omni(fire)
	assert_not_null(light, "brazier OmniLight named flame")
	if light == null:
		return
	# ff9a3d — a hue with no green-dominant complement, or the room tints.
	assert_almost_eq(light.light_color.r, 1.0, 0.02)
	assert_almost_eq(light.light_color.g, 0.6039216, 0.02)
	assert_almost_eq(light.light_color.b, 0.2392157, 0.02)


func _mesh_count(root: Node) -> int:
	var n := 0
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is MeshInstance3D:
			n += 1
		for child in node.get_children():
			stack.append(child)
	return n


func _find_omni(root: Node) -> OmniLight3D:
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is OmniLight3D:
			return node as OmniLight3D
		for child in node.get_children():
			stack.append(child)
	return null
