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


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var world: Node3D = packed.instantiate()
	add_child_autofree(world)
	return world


func _world_with_scene(scene_dict: Dictionary) -> Node3D:
	Table.set_scene(scene_dict)
	return _world_tree()


func _room() -> Node3D:
	return _world_tree().get_node("Room") as Node3D


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

	assert_eq(tiled_mix["total"], 144, "one tile per square")
	assert_eq(cracked_mix["total"], 144, "one tile per square")
	assert_true(_is_kaykit_floor(tiled_mix), "TILED lays KayKit tiles")
	assert_true(_is_kaykit_floor(cracked_mix), "CRACKED_STONE lays KayKit tiles")
	assert_ne(_mix_signature(tiled_mix), _mix_signature(cracked_mix),
		"TILED and CRACKED_STONE must not lay the same mix")
	# Laid flagging vs wear. TILED is still a floor; CRACKED_STONE has mostly gone.
	assert_gt(_tiled_laid(tiled_mix), _tiled_laid(cracked_mix),
		"a tiled floor keeps more laid flagging than a cracked one")
	assert_gt(_worn_count(cracked_mix), _worn_count(tiled_mix),
		"cracked stone is the worn end of the same gradient")
	# Hashed from roomId "crypt" against the KayKit weights in room.gd.
	assert_eq(int(tiled_mix.get("floor_tile_large", 0)), 21)
	assert_eq(int(tiled_mix.get("floor_tile_small_decorated", 0)), 116)
	assert_eq(int(tiled_mix.get("floor_tile_small_broken_A", 0)), 7)
	assert_eq(int(cracked_mix.get("floor_tile_small_broken_A", 0)), 30)
	assert_eq(int(cracked_mix.get("floor_tile_small_broken_B", 0)), 26)
	assert_eq(int(cracked_mix.get("floor_dirt_small_A", 0)), 28)
	assert_eq(int(cracked_mix.get("floor_tile_large_rocks", 0)), 27)


func test_stone_sits_between_tiled_and_cracked() -> void:
	var stone := CRYPT.duplicate(true)
	stone["floorType"] = "STONE"
	Table.set_scene(stone)
	var stone_mix := _floor_mix(_room())

	var tiled := CRYPT.duplicate(true)
	tiled["floorType"] = "TILED"
	Table.set_scene(tiled)
	var tiled_mix := _floor_mix(_room())

	var cracked := CRYPT.duplicate(true)
	cracked["floorType"] = "CRACKED_STONE"
	Table.set_scene(cracked)
	var cracked_mix := _floor_mix(_room())

	assert_true(_is_kaykit_floor(stone_mix))
	assert_ne(_mix_signature(stone_mix), _mix_signature(tiled_mix))
	assert_ne(_mix_signature(stone_mix), _mix_signature(cracked_mix))
	assert_gt(int(stone_mix.get("floor_tile_small", 0)), 0,
		"STONE is the small-flag floor, worn through in places")
	assert_eq(int(stone_mix.get("floor_tile_small", 0)), 105)
	assert_eq(int(stone_mix.get("floor_tile_small_broken_A", 0)), 25)
	assert_eq(int(stone_mix.get("floor_tile_small_broken_B", 0)), 14)


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


func test_floor_tiles_instance_the_kaykit() -> void:
	var room := _room()
	var tiles := _floor_roots(room)
	assert_gt(tiles.size(), 0)
	if tiles.is_empty():
		return
	var meshes := 0
	for tile in tiles:
		meshes += _mesh_count(tile)
		var variant := String(tile.get_meta("floor_variant"))
		assert_true("kaykit_dungeon" in variant, variant)
	assert_gt(meshes, 0, "KayKit floor glTFs must instance")


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
	assert_eq(mix["total"], 195)
	assert_true(_is_kaykit_floor(mix), "generated rooms use the same KayKit floor")
	assert_eq(int(mix.get("floor_tile_small", 0)), 135)
	assert_eq(int(mix.get("floor_tile_small_broken_A", 0)), 42)
	assert_eq(int(mix.get("floor_tile_small_broken_B", 0)), 18)
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


func test_carved_walls_differ_from_stone_in_geometry() -> void:
	# Task 17 left wallType unread because Three.js did. KayKit finally has the
	# pieces to honour it: cracked, broken, windows — not the same slab. Nothing with a
	# passage through it; those are exits, and exits are placed, not hashed.
	var stone := CRYPT.duplicate(true)
	stone["wallType"] = "STONE"
	Table.set_scene(stone)
	var stone_mix := _wall_mix(_room())

	var carved := CRYPT.duplicate(true)
	carved["wallType"] = "CARVED"
	Table.set_scene(carved)
	var carved_mix := _wall_mix(_room())

	assert_gt(stone_mix["total"], 0)
	assert_eq(stone_mix["total"], carved_mix["total"], "same perimeter, different faces")
	assert_ne(_mix_signature(stone_mix), _mix_signature(carved_mix),
		"CARVED is not STONE under another name")
	assert_gt(int(stone_mix.get("wall", 0)), int(carved_mix.get("wall", 0)),
		"STONE is mostly plain wall")
	var carved_worked := _worked(carved_mix)
	var stone_worked := _worked(stone_mix)
	assert_gt(carved_worked, stone_worked, "CARVED spends its weight on worked faces")


func test_the_room_has_no_sun() -> void:
	var room := _room()
	var stack: Array[Node] = [room]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		assert_false(node is DirectionalLight3D, "a sun in a crypt undoes M0")
		for child in node.get_children():
			stack.append(child)


func test_a_neighbour_is_built_as_geometry_with_no_lights_and_no_props() -> void:
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.5, "offsetZ": -14.5,
		}],
	})
	await wait_frames(2)

	var neighbour := world.get_node_or_null("Neighbours/gallery")
	assert_not_null(neighbour, "the room beyond the door should be built")
	if neighbour == null:
		return

	# Spec §8b: geometry only. A lit neighbour is a room the narrator has never been told about
	# and will describe wrongly; MAX_TORCH_LIGHTS is also a per-room budget.
	var lights := 0
	for node in neighbour.find_children("*", "Light3D", true, false):
		lights += 1
	assert_eq(lights, 0, "a neighbour carries no torches")


func test_the_neighbour_is_registered_where_the_server_put_it() -> void:
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [], "neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.5, "offsetZ": -14.5,
		}],
	})
	await wait_frames(2)

	assert_eq(world.room_origin("gallery"), Vector3(0.5, 0.0, -14.5))


func test_the_only_hole_in_the_wall_is_the_way_out() -> void:
	# Playtest 2026-09-06: the CARVED mix carried wall_gated and wall_doorway, so the crypt
	# grew openings that led nowhere and the real door was one of several.
	var crypt := CRYPT.duplicate(true)
	crypt["wallType"] = "CARVED"
	crypt["exits"] = [{"id": "door-north", "x": 6, "y": 11,
		"direction": "NORTH", "toRoomId": "gallery"}]
	Table.set_scene(crypt)
	var mix := _wall_mix(_room())

	assert_eq(int(mix.get("wall_gated", 0)), 0, "a barred gate is a way out that is not one")
	assert_eq(int(mix.get("wall_doorway", 0)), 1, "one doorway, and it is the exit")
	assert_eq(int(mix["total"]), 48, "cutting the doorway does not drop a segment")


func test_a_room_with_no_exits_has_no_doorway_at_all() -> void:
	var sealed := CRYPT.duplicate(true)
	sealed["roomId"] = "sealed"
	sealed["wallType"] = "CARVED"
	sealed["exits"] = []
	Table.set_scene(sealed)
	assert_eq(int(_wall_mix(_room()).get("wall_doorway", 0)), 0)


func test_the_doorway_is_cut_in_the_wall_the_exit_faces() -> void:
	var crypt := CRYPT.duplicate(true)
	crypt["wallType"] = "CARVED"
	crypt["exits"] = [{"id": "door-north", "x": 6, "y": 11,
		"direction": "NORTH", "toRoomId": "gallery"}]
	Table.set_scene(crypt)
	var room := _room()
	var doorway := _segment_named(room, "wall_doorway")
	assert_not_null(doorway, "the exit's segment")
	if doorway == null:
		return
	# North wall of a 12x12 at the origin is z = -6; the door is column 6, x = 0.5.
	assert_almost_eq(doorway.position.x, 0.5, 0.001)
	assert_almost_eq(doorway.position.z, -6.0, 0.001)


func test_a_neighbour_is_dark_stone_not_a_second_lit_room() -> void:
	# Playtest 2026-09-06: withholding the torches was not enough — the ambient still lifted
	# the kit textures far enough to read, so the gallery looked like somewhere already visited.
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.0, "offsetZ": -14.0,
			"back": {"id": "door-south", "x": 5, "y": 0,
				"direction": "SOUTH", "toRoomId": "crypt"},
		}],
	})
	await wait_frames(2)
	var neighbour := world.get_node_or_null("Neighbours/gallery")
	assert_not_null(neighbour, "Neighbours/gallery")
	if neighbour == null:
		return

	var painted := 0
	for mesh in neighbour.find_children("*", "MeshInstance3D", true, false):
		var mat := mesh.material_override as StandardMaterial3D
		assert_not_null(mat, "every surface is overridden, or the kit texture shows through")
		if mat == null:
			continue
		painted += 1
		assert_eq(mat.shading_mode, BaseMaterial3D.SHADING_MODE_UNSHADED,
			"shaded, the crypt's torches would light the far room as you walked up to it")
		assert_lt(mat.albedo_color.get_luminance(), 0.1, "a room you have not been in is dark")
		assert_gt(mat.albedo_color.get_luminance(), 0.0, "and stone, not a hole in the render")
	assert_gt(painted, 0, "the neighbour has surfaces to darken")

	# The live room is untouched by any of that.
	for mesh in (world.get_node("Room") as Node3D).find_children(
			"*", "MeshInstance3D", true, false):
		var mat := mesh.material_override as StandardMaterial3D
		if mat != null:
			assert_ne(mat.albedo_color, RoomScript.UNLIT_TINT,
				"the room the party is standing in keeps its own surfaces")


func test_a_neighbour_leaves_the_whole_shared_wall_to_the_room_you_are_in() -> void:
	var world := _world_with_scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "props": [], "entities": [], "combat": null,
		"exits": [{"id": "door-north", "x": 6, "y": 11,
			"direction": "NORTH", "toRoomId": "gallery"}],
		"neighbours": [{
			"roomId": "gallery", "width": 10, "height": 16,
			"floorType": "TILED", "wallType": "CARVED",
			"offsetX": 0.0, "offsetZ": -14.0,
			"back": {"id": "door-south", "x": 5, "y": 0,
				"direction": "SOUTH", "toRoomId": "crypt"},
		}],
	})
	await wait_frames(2)
	var neighbour := world.get_node_or_null("Neighbours/gallery") as Node3D
	assert_not_null(neighbour, "Neighbours/gallery")
	if neighbour == null:
		return

	# The gallery's south wall is coplanar with the crypt's north one — the server lines the
	# doors up, which puts both perimeters on one plane. Two walls fighting for one depth
	# showed up in play as texture noise across the seam, and as a door with two ring handles.
	var mix := _wall_mix(neighbour)
	assert_eq(int(mix.get("wall_doorway", 0)), 0,
		"the room you are standing in hangs the door; the room beyond leaves it alone")
	assert_eq(int(mix["total"]), 2 * (10 + 16) - 10,
		"the gallery's whole south run is missing, not just the door's square")

	# And nothing of the neighbour stands on the shared plane at all. The gallery is north of
	# the crypt, so its remaining walls are all further out than z = -6.
	for child in (neighbour.get_node("Walls") as Node3D).get_children():
		assert_gt(absf((child as Node3D).position.z - (-6.0)), 0.001,
			"a segment on the crypt's north wall plane would fight it for depth")


func _worked(mix: Dictionary) -> int:
	return (int(mix.get("wall_cracked", 0))
		+ int(mix.get("wall_broken", 0))
		+ int(mix.get("wall_window_open", 0)))


func _segment_named(room: Node, variant: String) -> Node3D:
	var holder: Node = room.get_node_or_null("Walls")
	if holder == null:
		return null
	for child in holder.get_children():
		if child.has_meta("wall_variant") \
				and String(child.get_meta("wall_variant")).get_file().get_basename() == variant:
			return child as Node3D
	return null


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
	var counts := {"total": 0}
	for tile in _floor_roots(room):
		var variant := String(tile.get_meta("floor_variant"))
		var key := variant.get_file().get_basename()
		counts[key] = int(counts.get(key, 0)) + 1
		counts["total"] = int(counts["total"]) + 1
	return counts


func _wall_mix(room: Node) -> Dictionary:
	var counts := {"total": 0}
	var holder: Node = room.get_node_or_null("Walls")
	if holder == null:
		return counts
	for child in holder.get_children():
		var key := ""
		if child.has_meta("wall_variant"):
			key = String(child.get_meta("wall_variant")).get_file().get_basename()
		else:
			key = String(child.name)
		counts[key] = int(counts.get(key, 0)) + 1
		counts["total"] = int(counts["total"]) + 1
	return counts


func _is_kaykit_floor(mix: Dictionary) -> bool:
	for key in mix.keys():
		if key == "total":
			continue
		if not String(key).begins_with("floor_"):
			return false
	return int(mix.get("total", 0)) > 0


func _tiled_laid(mix: Dictionary) -> int:
	return int(mix.get("floor_tile_large", 0)) + int(mix.get("floor_tile_small_decorated", 0))


func _worn_count(mix: Dictionary) -> int:
	var n := 0
	for key in mix.keys():
		var piece := String(key)
		if "broken" in piece or "dirt" in piece or "rocks" in piece:
			n += int(mix[key])
	return n


func _mix_signature(mix: Dictionary) -> String:
	var keys: Array = mix.keys()
	keys.sort()
	var parts: PackedStringArray = []
	for key in keys:
		if key == "total":
			continue
		parts.append("%s=%d" % [key, mix[key]])
	return ",".join(parts)


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
