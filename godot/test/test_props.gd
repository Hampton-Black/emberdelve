extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## Prop table, spawn, hidden/reveal, and the sarcophagus silhouette. Game facts stay in Table.

## DOOR is not here: its geometry is the wall segment at the exit, not a prop. See
## prop_table.gd, and test_room.gd for the segment that carries it.
const TYPES: Array[String] = [
	"SARCOPHAGUS", "BRAZIER", "PILLAR", "RUBBLE", "ALCOVE",
	"CONTAINER", "STATUE", "FURNITURE", "REMAINS", "SCENERY",
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
		{"id": "niche", "type": "ALCOVE", "x": 9, "y": 6, "rotation": 270, "hidden": true},
	],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}


func before_each() -> void:
	Table.reset()
	Table.set_scene(SceneFixtures.scene(CRYPT))


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


## Props hang under their own room: an id is only unique inside one (PropRef), and two
## generated rooms both call their first pillar `pillar-0`.
func _props(world: Node, room_id: String = "crypt") -> Node3D:
	return world.get_node_or_null("Props/%s" % room_id) as Node3D


func _prop(world: Node, id: String, room_id: String = "crypt") -> Node3D:
	var holder := _props(world, room_id)
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
	assert_null(table.scene_for("CHEST"), "CHEST folded into CONTAINER")
	assert_null(table.scene_for("DOOR"), "DOOR is the wall, not a prop mesh")


func test_hidden_props_are_not_instanced() -> void:
	var world := _world_tree()
	var holder := _props(world)
	assert_not_null(holder, "World/Props")
	if holder == null:
		return
	assert_eq(holder.get_child_count(), 4, "four visible props; the alcove stays hidden")
	assert_not_null(_prop(world, "tomb"), "tomb")
	assert_null(_prop(world, "niche"), "hidden alcove must not be in the tree")


func test_a_reveal_instances_the_prop() -> void:
	var world := _world_tree()
	var alcove := {"id": "niche", "type": "ALCOVE", "x": 9, "y": 6, "rotation": 270,
		"hidden": false}
	Table.prop_revealed.emit(alcove)
	assert_not_null(_prop(world, "niche"), "prop_revealed adds the alcove")
	assert_eq(_props(world).get_child_count(), 5)


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
	Table.prop_revealed.emit({"id": "niche", "type": "ALCOVE", "x": 9, "y": 6,
		"rotation": 270, "hidden": false})
	var niche := _prop(world, "niche")
	assert_not_null(niche, "niche")
	if niche == null:
		return
	assert_almost_eq(niche.rotation.y, deg_to_rad(270.0), 0.0001, "alcove 270°")


func test_entity_motion_does_not_rebuild_props() -> void:
	var world := _world_tree()
	var tomb := _prop(world, "tomb")
	assert_not_null(tomb, "tomb")
	if tomb == null:
		return
	var kept: int = tomb.get_instance_id()
	var moved := CRYPT.duplicate(true)
	moved["entities"][0]["x"] = 5
	Table.set_scene(SceneFixtures.scene(moved))
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
	Table.set_scene(SceneFixtures.scene(next))
	var rebuilt := _prop(world, "tomb", "crypt-2")
	assert_not_null(rebuilt, "fresh room still has the tomb")
	if rebuilt == null:
		return
	assert_ne(rebuilt.get_instance_id(), old_id, "a new roomId rebuilds the props")
	assert_eq(rebuilt.position, world.grid_to_world("crypt-2", 4, 4))
	assert_eq(_props(world, "crypt-2").get_child_count(), 1)
	assert_null(_props(world), "the room that is no longer in the scene takes its props with it")


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


## Both recorded sessions describe the crypt's braziers as green, unprompted, and treat it as world
## fact (emberdelve-4h9.16). This test used to assert ember, "or the room tints" — tinting the room
## is now the point. The hue was picked against the floor, not in isolation: `Overlay.EXIT_TINT` is
## a teal, and a green that leans colder puts the door marker back where ADR-0011 found it
## invisible. The energy is well under the old orange 4.5 because the eye is most sensitive to
## green, so the same number reads far hotter — and the prose word is "guttering".
func test_the_brazier_burns_green() -> void:
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
	assert_almost_eq(light.light_color.r, 0.42, 0.02)
	assert_almost_eq(light.light_color.g, 0.86, 0.02)
	assert_almost_eq(light.light_color.b, 0.44, 0.02)
	assert_almost_eq(light.light_energy, 2.8, 0.05)
	# The coals glow the flame's own colour, lightened — not the old warm cream under a green light.
	var coals := fire.get_node_or_null("Meshes/Coals") as MeshInstance3D
	assert_not_null(coals, "Meshes/Coals")
	if coals == null:
		return
	var mat := coals.get_active_material(0) as StandardMaterial3D
	var core := light.light_color.lightened(0.45)
	assert_almost_eq(mat.emission.r, core.r, 0.02)
	assert_almost_eq(mat.emission.g, core.g, 0.02)
	assert_almost_eq(mat.emission.b, core.b, 0.02)


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


## Two rooms, each with a pillar the generator called `pillar-0`. Prop ids are unique within a
## room and nowhere else (PropRef is room-qualified), and PropPlacer names every room's first
## pillar the same thing.
const TWO_ROOMS := {
	"roomId": "crypt", "mode": "EXPLORATION", "combat": null, "entities": [],
	"rooms": [
		{"roomId": "crypt", "width": 12, "height": 12,
			"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
			"originX": 0.0, "originZ": 0.0, "visited": true, "exits": [],
			"props": [{"id": "pillar-0", "type": "PILLAR", "x": 3, "y": 4,
				"rotation": 0, "hidden": false}]},
		{"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED", "lighting": "TORCHLIT",
			"originX": 0.0, "originZ": -14.0, "visited": true, "exits": [],
			"props": [{"id": "pillar-0", "type": "PILLAR", "x": 2, "y": 9,
				"rotation": 0, "hidden": false}]},
	],
}


func test_two_rooms_carrying_the_same_prop_id_both_draw_it() -> void:
	Table.set_scene(TWO_ROOMS)
	var world := _world_tree()
	await wait_frames(2)

	var here := world.get_node_or_null("Props/crypt/pillar-0") as Node3D
	var there := world.get_node_or_null("Props/gallery/pillar-0") as Node3D
	assert_not_null(here, "the crypt's pillar")
	assert_not_null(there, "the gallery's pillar, which shares its id and is not the same prop")
	if here == null or there == null:
		return
	assert_eq(here.position, world.grid_to_world("crypt", 3, 4))
	assert_eq(there.position, world.grid_to_world("gallery", 2, 9),
		"a room you have left keeps its furniture, in its own room")


func test_a_room_you_have_only_looked_into_is_furnished_by_nobody() -> void:
	# The server withholds an unvisited room's props. If it ever slips, a secret would be drawn
	# on the floor of a room nobody has walked into — and props hang outside the room node, so
	# nothing paints them out.
	var glimpsed := TWO_ROOMS.duplicate(true)
	glimpsed["rooms"][1]["visited"] = false
	Table.set_scene(glimpsed)
	var world := _world_tree()
	await wait_frames(2)

	assert_not_null(_prop(world, "pillar-0"), "the crypt is furnished")
	assert_null(_props(world, "gallery"), "the room beyond the door is not")
