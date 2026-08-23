extends GutTest

## Floor mix, lighting groups, and the roomId rebuild guard. Geometry is hashed from the
## room id the way Renderer.ts does, so a reconnect cannot reshuffle the tiles.

const RoomScript := preload("res://world/room.gd")

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


func _room() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var world: Node3D = packed.instantiate()
	add_child_autofree(world)
	return world.get_node("Room") as Node3D


func test_hash32_matches_three_js_fnv1a() -> void:
	# assets.ts:183 — unsigned FNV-1a, the seed every tile and wall segment is picked from.
	assert_eq(RoomScript.hash32("crypt:floor:0,0"), 173827589)
	assert_eq(RoomScript.hash32("crypt:wall:0"), 515785017)
	assert_eq(RoomScript.hash32("a"), 3826002220)
	assert_eq(RoomScript.hash32(""), 2166136261)
	assert_eq(RoomScript.hash32("room-7:floor:3,4"), 1475472425)


func test_tiled_and_cracked_stone_lay_different_floor_mixes() -> void:
	var tiled := CRYPT.duplicate(true)
	tiled["floorType"] = "TILED"
	Table.set_scene(tiled)
	var tiled_mix := _floor_mix(_room())

	var cracked := CRYPT.duplicate(true)
	cracked["floorType"] = "CRACKED_STONE"
	Table.set_scene(cracked)
	var cracked_mix := _floor_mix(_room())

	assert_eq(tiled_mix["squares"] + tiled_mix["standard"], 144, "one tile per square")
	assert_eq(cracked_mix["squares"] + cracked_mix["standard"], 144, "one tile per square")
	assert_ne(tiled_mix["squares"], cracked_mix["squares"],
		"TILED and CRACKED_STONE must not lay the same mix")
	# Renderer.ts FLOOR_VARIANTS weights, hashed from roomId "crypt".
	assert_eq(tiled_mix["squares"], 137)
	assert_eq(tiled_mix["standard"], 7)
	assert_eq(cracked_mix["squares"], 60)
	assert_eq(cracked_mix["standard"], 84)


func test_lighting_group_visibility_follows_the_preset() -> void:
	var room := _room()
	var lighting := room.get_node_or_null("Lighting")
	assert_not_null(lighting, "Room/Lighting")
	if lighting == null:
		return
	assert_true(lighting.get_node("TORCHLIT").visible, "crypt hello is TORCHLIT")
	assert_false(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("DARK").visible)

	var dim := CRYPT.duplicate(true)
	dim["lighting"] = "DIM"
	Table.set_scene(dim)
	assert_true(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("DARK").visible)

	var dark := CRYPT.duplicate(true)
	dark["lighting"] = "DARK"
	Table.set_scene(dark)
	assert_true(lighting.get_node("DARK").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("DIM").visible)


func test_rebuild_is_skipped_when_room_id_is_unchanged() -> void:
	var room := _room()
	var tiles := _floor_roots(room)
	assert_gt(tiles.size(), 0, "a built room has floor tiles")
	if tiles.is_empty():
		return
	var kept: int = tiles[0].get_instance_id()
	var moved := CRYPT.duplicate(true)
	moved["entities"][0]["x"] = 5
	Table.set_scene(moved)
	var after := _floor_roots(room)
	assert_eq(after.size(), tiles.size(), "entity motion must not relayout the floor")
	assert_eq(after[0].get_instance_id(), kept,
		"the same MeshInstance3Ds stay; rebuild would mint new ids")


func test_floor_tiles_instance_the_ruins_kit() -> void:
	var room := _room()
	var tiles := _floor_roots(room)
	assert_gt(tiles.size(), 0)
	if tiles.is_empty():
		return
	var meshes := 0
	for tile in tiles:
		meshes += _mesh_count(tile)
	assert_gt(meshes, 0, "Floor_Squares / Floor_Standard GLBs must instance")


func test_a_generated_room_uses_its_own_floor_and_lights() -> void:
	# Live `./gradlew run --args='--generate 7'` dumped:
	# generated-7  13x15  STONE / STONE / DIM  — not the crypt's surfaces.
	var generated := CRYPT.duplicate(true)
	generated["roomId"] = "generated-7"
	generated["width"] = 13
	generated["height"] = 15
	generated["floorType"] = "STONE"
	generated["wallType"] = "STONE"
	generated["lighting"] = "DIM"
	Table.set_scene(generated)
	var room := _room()
	var mix := _floor_mix(room)
	assert_eq(mix["squares"] + mix["standard"], 195)
	assert_eq(mix["squares"], 135)
	assert_eq(mix["standard"], 60)
	var lighting: Node = room.get_node("Lighting")
	assert_true(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("DARK").visible)


func test_dark_rooms_carry_no_wall_torch_lights() -> void:
	var dark := CRYPT.duplicate(true)
	dark["roomId"] = "dark-crypt"
	dark["lighting"] = "DARK"
	Table.set_scene(dark)
	var room := _room()
	assert_eq(_omni_count(room), 0, "DARK is unlit walls, not dim torches")


func test_torchlit_rooms_light_the_walls() -> void:
	var room := _room()
	var lights := _omni_count(room)
	assert_gt(lights, 0, "a generated TORCHLIT room is not an unlit void")
	assert_lte(lights, 10, "MAX_TORCH_LIGHTS")


func test_the_room_has_no_sun() -> void:
	var room := _room()
	var stack: Array[Node] = [room]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		assert_false(node is DirectionalLight3D, "a sun in a crypt undoes M0")
		for child in node.get_children():
			stack.append(child)


func _omni_count(root: Node) -> int:
	var n := 0
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is OmniLight3D:
			n += 1
		for child in node.get_children():
			stack.append(child)
	return n


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


func _floor_mix(room: Node) -> Dictionary:
	var squares := 0
	var standard := 0
	for tile in _floor_roots(room):
		var variant := String(tile.get_meta("floor_variant"))
		if "Floor_Squares" in variant:
			squares += 1
		elif "Floor_Standard" in variant:
			standard += 1
	return {"squares": squares, "standard": standard}


func _floor_roots(room: Node) -> Array[Node]:
	var found: Array[Node] = []
	var stack: Array[Node] = [room]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node.has_meta("floor_variant"):
			found.append(node)
			continue
		for child in node.get_children():
			stack.append(child)
	return found
