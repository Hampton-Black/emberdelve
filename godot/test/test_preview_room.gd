extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## The editor tool in emberdelve-4h9.10. GUT cannot drive the viewport, so these pin the
## parts that have to stay honest: the content path, the World refusal, the shared
## instancing function, and a JSON round-trip that mutates only x/y/rotation.

const PREVIEW := "res://dev/preview_room.gd"
const GRID := "res://world/grid.gd"
const PLACE := "res://world/prop_place.gd"
const WORLD_GD := "res://world/world.gd"
const CONTENT_REL := "../server/src/main/resources/content/rooms"


func _script(path: String):
	assert_true(ResourceLoader.exists(path), path)
	if not ResourceLoader.exists(path):
		return null
	return load(path)


func _has(script, method: String) -> bool:
	if script == null:
		return false
	for m in script.get_script_method_list():
		if String(m.name) == method:
			return true
	assert_true(false, "%s is missing %s" % [script.resource_path, method])
	return false


func _prop(room: Dictionary, id: String) -> Dictionary:
	for prop in room.get("props", []):
		if String(prop.get("id", "")) == id:
			return prop
	return {}


func test_content_path_resolves_from_res_not_a_machine_path() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "rooms_dir"):
		return
	var expected := ProjectSettings.globalize_path("res://") \
		.path_join(CONTENT_REL).simplify_path()
	var got := String(Preview.rooms_dir())
	assert_eq(got.trim_suffix("/"), expected.trim_suffix("/"))
	var src := FileAccess.get_file_as_string(PREVIEW)
	assert_true(src.contains('globalize_path("res://")'),
		"the hop starts at res://, so a clone on another machine still finds the rooms")
	assert_false(src.contains("/Users/"),
		"no hardcoded absolute path")
	assert_true(FileAccess.file_exists(got.path_join("crypt.json")),
		"the resolved directory is the authored rooms")


func test_preview_refuses_a_scene_whose_root_is_world() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "build_into"):
		return
	var world := World.new()
	add_child_autofree(world)
	var report: Dictionary = Preview.build_into(world, "crypt")
	assert_true(report.has("error"),
		"build_into must refuse a World itself, not only the EditorScript _run wrapper")
	if not report.has("error"):
		return
	assert_string_contains(String(report["error"]), "World")
	assert_null(world.get_node_or_null("RoomPreview"),
		"refusing must not leave a preview hanging off the World")


func test_a_blank_scene_can_host_the_preview() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "build_into"):
		return
	var root := Node3D.new()
	add_child_autofree(root)
	var report: Dictionary = Preview.build_into(root, "crypt")
	assert_false(report.has("error"), str(report.get("error", "")))
	if report.has("error"):
		return
	assert_gt(int(report["floor"]), 0)
	assert_gt(int(report["props"]), 0)
	var holder := root.get_node_or_null("RoomPreview")
	assert_not_null(holder, "RoomPreview")
	if holder == null:
		return
	assert_eq(holder.owner, null, "nothing the tool builds is saved into a .tscn")


func test_door_has_no_mesh_and_that_is_correct() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "build_into"):
		return
	var root := Node3D.new()
	add_child_autofree(root)
	var report: Dictionary = Preview.build_into(root, "crypt")
	if report.has("error"):
		assert_false(report.has("error"), str(report["error"]))
		return
	var skipped: Array = report.get("skipped", [])
	var joined := ",".join(skipped)
	assert_string_contains(joined, "door-north")
	assert_true(int(report["props"]) < int(report["propsTotal"]),
		"DOOR is skipped, not a missing prop")


func test_room_list_comes_from_the_content_directory() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "list_rooms"):
		return
	var rooms: PackedStringArray = Preview.list_rooms()
	assert_gt(rooms.size(), 1, "more than the one room a const would name")
	assert_true("crypt" in rooms)
	assert_true("gallery" in rooms)
	var src := FileAccess.get_file_as_string(PREVIEW)
	assert_false(src.contains('const ROOM := "crypt"'),
		"pick the room from the directory, not a const you edit and re-run")


func test_round_trip_moves_one_prop_and_leaves_every_other_field() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "rooms_dir") or not _has(Preview, "write_prop"):
		return
	var crypt := String(Preview.rooms_dir()).path_join("crypt.json")
	assert_true(FileAccess.file_exists(crypt), crypt)
	var original := FileAccess.get_file_as_string(crypt)
	var tmp := "user://preview_room_roundtrip.json"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	assert_not_null(f, tmp)
	if f == null:
		return
	f.store_string(original)
	f.close()
	var abs_tmp := ProjectSettings.globalize_path(tmp)

	var err := String(Preview.write_prop(abs_tmp, "alcove", 5, 3, 90))
	assert_eq(err, "", "write_prop returns an empty string on success")
	if not err.is_empty():
		return

	var before: Dictionary = JSON.parse_string(original)
	var after: Dictionary = JSON.parse_string(FileAccess.get_file_as_string(tmp))
	assert_eq(typeof(after), TYPE_DICTIONARY)
	if typeof(after) != TYPE_DICTIONARY:
		return

	for key in before:
		if key == "props":
			continue
		assert_eq(after.get(key), before[key], "room field '%s' must not change" % key)
	assert_eq((after.get("props", []) as Array).size(), (before.get("props", []) as Array).size())

	var moved_before := _prop(before, "alcove")
	var moved_after := _prop(after, "alcove")
	assert_false(moved_after.is_empty(), "alcove still in the file")
	if moved_after.is_empty():
		return
	assert_eq(int(moved_after["x"]), 5)
	assert_eq(int(moved_after["y"]), 3)
	assert_eq(int(moved_after["rotation"]), 90)
	for key in moved_before:
		if key in ["x", "y", "rotation"]:
			continue
		assert_eq(moved_after.get(key), moved_before[key],
			"alcove field '%s' must not change" % key)
	assert_true(moved_after.has("description"))
	assert_true(moved_after.has("hidden"))
	assert_true(moved_after.has("revealHint"))

	for prop in before.get("props", []):
		var id := String(prop.get("id", ""))
		if id == "alcove":
			continue
		var other := _prop(after, id)
		assert_eq(other, prop, "prop '%s' must be unchanged" % id)

	var tomb := _prop(after, "sarcophagus")
	assert_eq(tomb.get("contains"), "goblin")
	var box := _prop(after, "reliquary")
	assert_eq(box.get("actions"), ["take"])


func test_grid_to_world_is_extracted_for_a_self_centred_room() -> void:
	var grid = _script(GRID)
	if not _has(grid, "to_world"):
		return
	# The entrance, 12x12 — the numbers test_world.gd already pins on World.
	assert_eq(grid.to_world(0, 0, Vector2i(12, 12)), Vector3(-5.5, 0.0, 5.5))
	assert_eq(grid.to_world(11, 11, Vector2i(12, 12)), Vector3(5.5, 0.0, -5.5))
	assert_eq(grid.to_world(2, 2, Vector2i(12, 12)), Vector3(-3.5, 0.0, 3.5))
	# Origin-aware: a neighbour is the same formula plus the origin World was told.
	assert_eq(grid.to_world(5, 8, Vector2i(10, 16), Vector3(0.0, 0.0, -14.0)),
		Vector3(0.5, 0.0, -14.5))
	if _has(grid, "to_square"):
		assert_eq(grid.to_square(Vector3(-3.5, 0.0, 3.5), Vector2i(12, 12)), Vector2i(2, 2))
	var world_src := FileAccess.get_file_as_string(WORLD_GD)
	assert_true(world_src.contains("Grid.to_world"),
		"World.grid_to_world delegates; the tool must not inline a second copy")
	var preview_src := FileAccess.get_file_as_string(PREVIEW)
	assert_true(preview_src.contains("Grid.to_world"),
		"the tool calls the extracted function")
	assert_false(preview_src.contains("func _grid_to_world"),
		"replace the spike's copy")


func test_world_and_the_preview_call_the_same_prop_instancer() -> void:
	var Place = _script(PLACE)
	if not _has(Place, "into"):
		return
	Table.reset()
	Table.set_scene(SceneFixtures.scene({
		"roomId": "crypt", "width": 12, "height": 12,
		"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
		"mode": "EXPLORATION", "combat": null, "props": [],
		"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick",
			"x": 2, "y": 2, "hp": 12, "maxHp": 12, "isPlayerControlled": true}],
	}))
	var packed: PackedScene = load("res://world/world.tscn")
	var world: Node3D = packed.instantiate()
	add_child_autofree(world)

	var grid = _script(GRID)
	if grid == null or not _has(grid, "to_world"):
		return
	var holder := Node3D.new()
	add_child_autofree(holder)
	var prop := {"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 7,
		"rotation": 0, "hidden": false}
	var node: Node3D = Place.into(holder, prop, "crypt",
		grid.to_world(6, 7, Vector2i(12, 12)))
	assert_not_null(node, "PropPlace.into instances a sarcophagus")
	if node == null:
		return
	assert_eq(node.position, world.grid_to_world("crypt", 6, 7),
		"a prop placed through the shared function sits at the same grid_to_world as the game")
	assert_almost_eq(node.rotation.y, 0.0, 0.0001)

	var world_src := FileAccess.get_file_as_string(WORLD_GD)
	var preview_src := FileAccess.get_file_as_string(PREVIEW)
	assert_true(world_src.contains("PropPlace.into"))
	assert_true(preview_src.contains("PropPlace.into"))
	assert_eq(world_src.find("scene_for"), -1,
		"World no longer copies the instancing; PropPlace owns scene_for")
	assert_eq(preview_src.find("scene_for"), -1,
		"the spike's copied instancing is gone")


func test_world_gd_is_not_a_tool_script() -> void:
	var src := FileAccess.get_file_as_string(WORLD_GD)
	assert_eq(src.find("@tool"), -1,
		"an editor-resident World is a live websocket client; @tool stays off world.gd")


func test_preview_reads_the_environment_off_world_tscn() -> void:
	var src := FileAccess.get_file_as_string(PREVIEW)
	assert_true(src.contains("world.tscn"),
		"read EnvTorchlit off the scene rather than restating its four numbers")
	assert_false(src.contains("0.0431373"),
		"those four numbers were the spike's copy")


func test_should_write_prop_only_on_drop_not_mid_drag() -> void:
	var Preview = _script(PREVIEW)
	if not _has(Preview, "should_write_prop"):
		return
	assert_false(Preview.should_write_prop(true, false, true),
		"mid-drag square change must not write")
	assert_false(Preview.should_write_prop(true, true, false),
		"release without a change must not write")
	assert_true(Preview.should_write_prop(true, true, true),
		"release after a drag must write once")
	assert_false(Preview.should_write_prop(false, false, true),
		"square change without a release must not write")
