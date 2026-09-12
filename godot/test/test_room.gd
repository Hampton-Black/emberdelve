extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

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
	Table.set_scene(SceneFixtures.scene(CRYPT))


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
	Table.set_scene(SceneFixtures.scene(tiled))
	var tiled_mix := _floor_mix(_room())

	var cracked := CRYPT.duplicate(true)
	cracked["floorType"] = "CRACKED_STONE"
	Table.set_scene(SceneFixtures.scene(cracked))
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
	Table.set_scene(SceneFixtures.scene(stone))
	var stone_mix := _floor_mix(_room())

	var tiled := CRYPT.duplicate(true)
	tiled["floorType"] = "TILED"
	Table.set_scene(SceneFixtures.scene(tiled))
	var tiled_mix := _floor_mix(_room())

	var cracked := CRYPT.duplicate(true)
	cracked["floorType"] = "CRACKED_STONE"
	Table.set_scene(SceneFixtures.scene(cracked))
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
	assert_false(lighting.get_node("BRAZIERLIT").visible)
	assert_false(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("DARK").visible)

	var braziers := CRYPT.duplicate(true)
	braziers["lighting"] = "BRAZIERLIT"
	Table.set_scene(SceneFixtures.scene(braziers))
	assert_true(lighting.get_node("BRAZIERLIT").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("DARK").visible)

	var dim := CRYPT.duplicate(true)
	dim["lighting"] = "DIM"
	Table.set_scene(SceneFixtures.scene(dim))
	assert_true(lighting.get_node("DIM").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("BRAZIERLIT").visible)
	assert_false(lighting.get_node("DARK").visible)

	var dark := CRYPT.duplicate(true)
	dark["lighting"] = "DARK"
	Table.set_scene(SceneFixtures.scene(dark))
	assert_true(lighting.get_node("DARK").visible)
	assert_false(lighting.get_node("TORCHLIT").visible)
	assert_false(lighting.get_node("BRAZIERLIT").visible)
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
	Table.set_scene(SceneFixtures.scene(moved))
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
	Table.set_scene(SceneFixtures.scene(generated))
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
	Table.set_scene(SceneFixtures.scene(dark))
	var room := _room()
	assert_eq(_omni_count(room), 0, "DARK is unlit walls, not dim torches")


## The crypt. Its fires are the braziers, which are props; the walls carry nothing, not even an
## unlit bracket. The air is TORCHLIT's — the same Environment, so the ambient judged on the
## crypt's contact sheets is one number and not two copies of it.
func test_brazierlit_rooms_have_no_wall_torches_and_torchlit_air() -> void:
	var braziers := CRYPT.duplicate(true)
	braziers["lighting"] = "BRAZIERLIT"
	Table.set_scene(SceneFixtures.scene(braziers))
	var room := _room()
	assert_eq(_omni_count(room), 0, "the fires stand on the floor, not on the walls")
	assert_eq(room.get_node("Torches").get_child_count(), 0, "no brackets either")
	var lighting: Node = room.get_node("Lighting")
	var own := (lighting.get_node("BRAZIERLIT/WorldEnvironment") as WorldEnvironment).environment
	var torchlit: Environment = lighting.get_node("TORCHLIT/WorldEnvironment").get_meta("authored")
	assert_not_null(own, "BRAZIERLIT's environment is the active one")
	assert_same(own, torchlit)


func test_torchlit_rooms_light_the_walls() -> void:
	var room := _room()
	var lights := _omni_count(room)
	assert_gt(lights, 0, "a generated TORCHLIT room is not an unlit void")
	assert_lte(lights, 10, "MAX_TORCH_LIGHTS")


func test_rebuild_fires_snaps_wall_torches_to_the_new_preset() -> void:
	var room := _room()
	await wait_frames(2)
	assert_gt(_omni_count(room), 0, "setup: TORCHLIT has wall fires")
	var view: Dictionary = Table.room().duplicate(true)
	view["lighting"] = "DARK"
	room.rebuild_fires(view, "crypt")
	assert_eq(_omni_count(room), 0, "DARK carries no wall-torch lights")
	assert_eq(room.get_node("Torches").get_child_count(), 0, "and no brackets either")


func test_carved_walls_differ_from_stone_in_geometry() -> void:
	# Task 17 left wallType unread because Three.js did. KayKit finally has the
	# pieces to honour it: cracked, broken, windows — not the same slab. Nothing with a
	# passage through it; those are exits, and exits are placed, not hashed.
	var stone := CRYPT.duplicate(true)
	stone["wallType"] = "STONE"
	Table.set_scene(SceneFixtures.scene(stone))
	var stone_mix := _wall_mix(_room())

	var carved := CRYPT.duplicate(true)
	carved["wallType"] = "CARVED"
	Table.set_scene(SceneFixtures.scene(carved))
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
		"roomId": "crypt", "mode": "EXPLORATION", "entities": [], "combat": null,
		"rooms": [
			{"roomId": "crypt", "width": 12, "height": 12,
				"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "originX": 0.0, "originZ": 0.0, "visited": true,
				"exits": [{"id": "door-north", "x": 6, "y": 11,
					"direction": "NORTH", "toRoomId": "gallery"}]},
			{"roomId": "gallery", "width": 10, "height": 16,
				"floorType": "TILED", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "exits": [], "originX": 0.5, "originZ": -14.5, "visited": false},
		],
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
		"roomId": "crypt", "mode": "EXPLORATION", "entities": [], "combat": null,
		"rooms": [
			{"roomId": "crypt", "width": 12, "height": 12,
				"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "exits": [], "originX": 0.0, "originZ": 0.0, "visited": true},
			{"roomId": "gallery", "width": 10, "height": 16,
				"floorType": "TILED", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "exits": [], "originX": 0.5, "originZ": -14.5, "visited": false},
		],
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
	Table.set_scene(SceneFixtures.scene(crypt))
	var mix := _wall_mix(_room())

	assert_eq(int(mix.get("wall_gated", 0)), 0, "a barred gate is a way out that is not one")
	assert_eq(int(mix.get("wall_doorway", 0)), 1, "one doorway, and it is the exit")
	assert_eq(int(mix["total"]), 48, "cutting the doorway does not drop a segment")


func test_a_room_with_no_exits_has_no_doorway_at_all() -> void:
	var sealed := CRYPT.duplicate(true)
	sealed["roomId"] = "sealed"
	sealed["wallType"] = "CARVED"
	sealed["exits"] = []
	Table.set_scene(SceneFixtures.scene(sealed))
	assert_eq(int(_wall_mix(_room()).get("wall_doorway", 0)), 0)


func test_the_doorway_is_cut_in_the_wall_the_exit_faces() -> void:
	var crypt := CRYPT.duplicate(true)
	crypt["wallType"] = "CARVED"
	crypt["exits"] = [{"id": "door-north", "x": 6, "y": 11,
		"direction": "NORTH", "toRoomId": "gallery"}]
	Table.set_scene(SceneFixtures.scene(crypt))
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
		"roomId": "crypt", "mode": "EXPLORATION", "entities": [], "combat": null,
		"rooms": [
			{"roomId": "crypt", "width": 12, "height": 12,
				"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "originX": 0.0, "originZ": 0.0, "visited": true,
				"exits": [{"id": "door-north", "x": 6, "y": 11,
					"direction": "NORTH", "toRoomId": "gallery"}]},
			{"roomId": "gallery", "width": 10, "height": 16,
				"floorType": "TILED", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "originX": 0.0, "originZ": -14.0, "visited": false,
				"exits": [{"id": "door-south", "x": 5, "y": 0,
					"direction": "SOUTH", "toRoomId": "crypt"}]},
		],
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


func test_a_neighbour_gives_up_the_segments_the_server_named_and_no_others() -> void:
	# The gallery's south wall is coplanar with the crypt's north one — the server lines the
	# doors up, which puts both perimeters on one plane. Two walls fighting for one depth showed
	# up in play as texture noise across the seam, and as a door with two ring handles. Here the
	# crypt is the entrance and owns the plane, so `coveredWalls` names the gallery's whole south
	# run; every other segment of its perimeter is its own to draw.
	var world := _world_with_scene(_two_rooms("crypt"))
	await wait_frames(2)
	var neighbour := world.get_node_or_null("Neighbours/gallery") as Node3D
	assert_not_null(neighbour, "Neighbours/gallery")
	if neighbour == null:
		return

	var mix := _wall_mix(neighbour)
	assert_eq(int(mix.get("wall_doorway", 0)), 0,
		"the room you are standing in hangs the door; the room beyond leaves it alone")
	assert_eq(int(mix["total"]), 2 * (10 + 16) - 10,
		"ten segments given up — the run the crypt covers, its own doorway among them")

	# And nothing of the neighbour stands on the shared plane at all. The gallery is north of
	# the crypt, so its remaining walls are all further out than z = -6.
	assert_eq(_segments_on_plane(neighbour, Vector3.AXIS_Z, -6.0), [] as Array,
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


func test_the_render_level_is_lit_here_dim_where_you_have_been_black_where_you_have_not() -> void:
	# One policy function, so switching visited rooms from DIM to LIT is one line in one place.
	assert_eq(RoomScript.level_for({"roomId": "crypt", "visited": true}, "crypt"),
		RoomScript.Level.LIT, "the room the party is standing in")
	assert_eq(RoomScript.level_for({"roomId": "gallery", "visited": true}, "crypt"),
		RoomScript.Level.DIM, "somewhere they have been and left")
	assert_eq(RoomScript.level_for({"roomId": "vault", "visited": false}, "crypt"),
		RoomScript.Level.BLACK, "somewhere they have only seen through a door")


## The crypt and the gallery, with the party in whichever one is named. Both visited unless
## `unseen` says otherwise — the shape GameEngine.scene() ships once you have crossed once.
##
## `coveredWalls` is what `Rooms.coveredWalls()` answers for this pair: the crypt is the entrance
## and owns the plane the two share, so the gallery's whole south run is left to it. Both sides of
## the wire, so the fixture cannot quietly disagree with the server about who draws what.
func _two_rooms(current: String, unseen: Array = []) -> Dictionary:
	return {
		"roomId": current, "mode": "EXPLORATION", "entities": [], "combat": null,
		"rooms": [
			{"roomId": "crypt", "width": 12, "height": 12,
				"floorType": "CRACKED_STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "originX": 0.0, "originZ": 0.0,
				"visited": not ("crypt" in unseen), "coveredWalls": [],
				"exits": [{"id": "door-north", "x": 6, "y": 11,
					"direction": "NORTH", "toRoomId": "gallery"}]},
			{"roomId": "gallery", "width": 10, "height": 16,
				"floorType": "TILED", "wallType": "CARVED", "lighting": "TORCHLIT",
				"props": [], "originX": 0.0, "originZ": -14.0,
				"visited": not ("gallery" in unseen), "coveredWalls": SceneFixtures.south_run(10),
				"exits": [{"id": "door-south", "x": 5, "y": 0,
					"direction": "SOUTH", "toRoomId": "crypt"}]},
		],
	}


## Every wall segment of a room standing on one plane, given by its position along the other
## axis. A north or south wall is a constant z and a run along x; an east or west wall is the
## other way round — same comparison, so one helper takes which axis holds the plane.
func _segments_on_plane(room: Node, axis: Vector3.Axis, plane: float) -> Array:
	var found: Array = []
	var holder: Node = room.get_node_or_null("Walls")
	if holder == null:
		return found
	for child in holder.get_children():
		var at := (child as Node3D).position
		if absf(at[axis] - plane) < 0.001:
			found.append(at.x if axis == Vector3.AXIS_Z else at.z)
	found.sort()
	return found


## A west-to-east chain of three 4x4 rooms, the party in the entrance. `Rooms.origins()` puts
## them at x = 0, 4 and 8; `Rooms.coveredWalls()` gives each of the two rooms beyond the entrance
## its whole west run, because the room nearer the entrance owns every plane.
func _three_rooms() -> Dictionary:
	var chain: Array = []
	var ids := ["a-entrance", "b-middle", "c-far"]
	for i in 3:
		var exits: Array = []
		if i > 0:
			exits.append({"id": "%s-back" % ids[i], "x": 0, "y": 1 + i,
				"direction": "WEST", "toRoomId": ids[i - 1]})
		if i < 2:
			exits.append({"id": "%s-on" % ids[i], "x": 3, "y": 1 + i,
				"direction": "EAST", "toRoomId": ids[i + 1]})
		var covered: Array = []
		if i > 0:
			for y in 4:
				covered.append({"x": 0, "y": y, "direction": "WEST"})
		chain.append({"roomId": ids[i], "width": 4, "height": 4,
			"floorType": "STONE", "wallType": "STONE", "lighting": "DARK",
			"props": [], "originX": float(i * 4), "originZ": 0.0, "visited": true,
			"coveredWalls": covered, "exits": exits})
	return {"roomId": "a-entrance", "mode": "EXPLORATION", "entities": [], "combat": null,
		"rooms": chain}


func test_the_wall_between_two_rooms_the_party_is_in_neither_of_is_still_there() -> void:
	# Found reviewing emberdelve-xgg.4 and latent while M3 ships two rooms. Omitting by direction
	# dropped a whole run for every room but the current one, so in a chain the middle and far
	# rooms each left their mutual wall to the other and it went missing entirely. Ownership by
	# distance from the entrance has exactly one room answering for each plane.
	var world := _world_with_scene(_three_rooms())
	await wait_frames(2)
	var middle := world.get_node_or_null("Neighbours/b-middle") as Node3D
	var far := world.get_node_or_null("Neighbours/c-far") as Node3D
	assert_not_null(middle, "Neighbours/b-middle")
	assert_not_null(far, "Neighbours/c-far")
	if middle == null or far == null:
		return

	# The plane the two share stands at x = 6, between rooms centred on 4 and 8.
	var seam: Array = _segments_on_plane(middle, Vector3.AXIS_X, 6.0) \
		+ _segments_on_plane(far, Vector3.AXIS_X, 6.0)
	seam.sort()
	assert_eq(seam, [-1.5, 0.5, 1.5] as Array,
		"the middle room draws its east wall; only its own doorway's square is an opening")


func test_a_neighbour_keeps_the_perimeter_that_overhangs_the_shared_run() -> void:
	# emberdelve-5sc, seen from the gallery. The crypt is twelve wide and the gallery ten, so a
	# whole-run omission takes the two crypt segments that stick out past the gallery with it —
	# and a hole in a wall is a way out. The crypt is the entrance, owns the plane, and is told
	# it covers nothing, so it draws the run whether or not anyone is standing in it.
	var world := _world_with_scene(_two_rooms("gallery"))
	await wait_frames(2)
	var crypt := world.get_node_or_null("Neighbours/crypt") as Node3D
	assert_not_null(crypt, "Neighbours/crypt")
	if crypt == null:
		return

	var on_the_seam := _segments_on_plane(crypt, Vector3.AXIS_Z, -6.0)
	assert_true(-5.5 in on_the_seam, "no hole at the crypt's north-west corner")
	assert_true(5.5 in on_the_seam, "no hole at the crypt's north-east corner")
	assert_eq(on_the_seam.size(), 11,
		"the whole north run but the door's own square, which the gallery hangs")
	assert_eq(int(_wall_mix(crypt)["total"]), 2 * (12 + 12) - 1,
		"nothing else of the crypt's perimeter is given up")


func test_the_shared_plane_carries_one_set_of_stone_and_one_door() -> void:
	# Two walls fighting for one depth showed in play as a door with two ring handles and then
	# as noise across the seam. From either side the plane is drawn exactly once: the crypt owns
	# the stone from either side, and the room the party is in hangs the door.
	for current in ["crypt", "gallery"]:
		var world := _world_with_scene(_two_rooms(current))
		await wait_frames(2)
		var here := world.get_node("Room") as Node3D
		var there := world.get_node_or_null(
			"Neighbours/%s" % ("gallery" if current == "crypt" else "crypt")) as Node3D
		assert_not_null(there, "the other room is on the board")
		if there == null:
			return

		var seam: Array = _segments_on_plane(here, Vector3.AXIS_Z, -6.0) \
			+ _segments_on_plane(there, Vector3.AXIS_Z, -6.0)
		var seen := {}
		for x in seam:
			assert_false(seen.has(x),
				"two segments at x=%s on the shared plane, standing in the %s" % [x, current])
			seen[x] = true
		assert_eq(seam.size(), 12, "the crypt's whole north run, once, standing in the " + current)
		assert_eq(int(_wall_mix(here).get("wall_doorway", 0))
			+ int(_wall_mix(there).get("wall_doorway", 0)), 1,
			"one door hangs in the opening, standing in the " + current)
		assert_eq(int(_wall_mix(here).get("wall_doorway", 0)), 1,
			"and it is hung by the room the party is in, which is the room that can open it")


func test_a_room_you_have_left_keeps_its_surfaces_and_gives_up_its_lights() -> void:
	var world := _world_with_scene(_two_rooms("gallery"))
	await wait_frames(2)
	var left := world.get_node_or_null("Neighbours/crypt") as Node3D
	assert_not_null(left, "the crypt is still on the board after you walk out of it")
	if left == null:
		return

	for mesh in left.find_children("*", "MeshInstance3D", true, false):
		var mat := mesh.material_override as StandardMaterial3D
		if mat != null:
			assert_ne(mat.albedo_color, RoomScript.UNLIT_TINT,
				"a room you have stood in is remembered, not blacked out")
	assert_gt(_torch_count(left), 0, "the brackets stay on its walls")
	assert_eq(_omni_count(left), 0, "but nothing in it burns")
	# The budget stays per-room however far the dungeon runs: only the room the party is in
	# ever adds a light.
	assert_eq(_omni_count(world), _omni_count(world.get_node("Room") as Node3D),
		"every OmniLight3D in the world belongs to the current room")
	assert_gt(_omni_count(world), 0, "and the current room is lit")


func _torch_count(room: Node) -> int:
	var holder := room.get_node_or_null("Torches") as Node3D
	return 0 if holder == null else holder.get_child_count()


func test_crossing_a_threshold_moves_the_camera_and_not_the_dungeon() -> void:
	var world := _world_with_scene(_two_rooms("crypt"))
	await wait_frames(2)
	assert_eq(world.room_origin("crypt"), Vector3.ZERO, "the entrance anchors the world frame")
	assert_eq(world.room_origin("gallery"), Vector3(0.0, 0.0, -14.0))

	Table.set_scene(_two_rooms("gallery"))
	await wait_frames(2)
	assert_eq(world.room_origin("crypt"), Vector3.ZERO,
		"the room you walked out of stays where it was drawn")
	assert_eq(world.room_origin("gallery"), Vector3(0.0, 0.0, -14.0),
		"and the room you walked into is not re-centred on the world origin")
	assert_not_null(world.get_node_or_null("Neighbours/crypt"),
		"the crypt is still on the board from the gallery")


func test_the_room_the_party_is_in_is_built_where_the_server_put_it() -> void:
	var world := _world_with_scene(_two_rooms("gallery"))
	await wait_frames(2)
	var here := world.get_node("Room") as Node3D

	# The gallery is 10 x 16 at (0, -14), so its south wall sits at z = -14 + 8 = -6 and the
	# door in it — square (5, 0) — at x = 5 - 5 + 0.5. Nothing about it is centred on the world
	# origin any more.
	var doorway := _segment_named(here, "wall_doorway")
	assert_not_null(doorway, "the gallery's own door back to the crypt")
	if doorway == null:
		return
	assert_almost_eq(doorway.position.x, 0.5, 0.001)
	assert_almost_eq(doorway.position.z, -6.0, 0.001)

	# Its fires burn in it, not fourteen squares south in the room it came from.
	for light in here.find_children("*", "OmniLight3D", true, false):
		var at: Vector3 = (light as Node3D).global_position
		assert_between(at.z, -22.0, -6.0, "a torch outside the room it belongs to")
		assert_between(at.x, -5.0, 5.0, "a torch outside the room it belongs to")


func test_crossing_back_finds_the_room_you_left_where_you_left_it() -> void:
	var world := _world_with_scene(_two_rooms("crypt"))
	await wait_frames(2)
	Table.set_scene(_two_rooms("gallery"))
	await wait_frames(2)
	Table.set_scene(_two_rooms("crypt"))
	await wait_frames(2)

	var here := world.get_node("Room") as Node3D
	assert_eq(int(_floor_mix(here)["total"]), 144, "the party is back in the 12x12 crypt")
	assert_gt(_omni_count(here), 0, "and it is burning again")

	var gallery := world.get_node_or_null("Neighbours/gallery") as Node3D
	assert_not_null(gallery, "the gallery stays on the board behind them")
	if gallery == null:
		return
	assert_gt(_torch_count(gallery), 0, "with its brackets")
	assert_eq(_omni_count(gallery), 0, "and its fires out")
	assert_eq(world.room_origin("gallery"), Vector3(0.0, 0.0, -14.0),
		"two crossings and nothing has moved")
