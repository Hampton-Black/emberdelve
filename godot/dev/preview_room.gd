@tool
extends EditorScript

## Dev-only: load an authored room JSON into the open scene, with the real kit meshes,
## and write a moved prop's x/y/rotation back without rebuilding the rest of the file.
##
## Open a blank 3D scene, never `world.tscn`. Pick the room from the Room Preview dock
## (the files in `server/.../content/rooms/`) and press Load, or File > Run this script
## to load the dock's last pick. Nothing it builds has an `owner`, so none of it is
## saved into the `.tscn` — the room JSON is the only place a room is defined.
##
## [b]Turn off Preview Sun and Preview Environment[/b] before judging how a mesh looks.
## Both are on by default and stack on top of the game's own WorldEnvironment, so the
## room reads far brighter than play. emberdelve-4h9.7 will look at this.
##
## world.gd is not a @tool script on purpose: its `_ready` connects to Table, so an
## editor-resident World is a live websocket client. Grid conversion and prop instancing
## live on `Grid` and `PropPlace`, which this tool and the game both call.
##
## After any @tool change: Project > Reload Current Project. Adding @tool does not take
## effect in a running editor; the placeholder stays loaded and the same error repeats.

const HOLDER := "RoomPreview"
const CONTENT := "../server/src/main/resources/content/rooms"
const SHOW_HIDDEN := true
const LIGHT_LIKE_THE_GAME := true

## Last room the dock picked. File > Run uses this so the room is not a const to edit.
static var last_room := ""


func _run() -> void:
	var root := EditorInterface.get_edited_scene_root()
	var report := build_into(root, picked_room())
	if report.has("error"):
		push_error("preview_room: %s" % report["error"])
		return
	print("preview_room: %s — %dx%d, %d floor, %d walls, %d torches, %d of %d props" % [
		report["roomId"], report["width"], report["height"],
		report["floor"], report["walls"], report["torches"],
		report["props"], report["propsTotal"],
	])
	if not (report["skipped"] as Array).is_empty():
		print("preview_room: no mesh for %s" % ", ".join(report["skipped"]))


static func rooms_dir() -> String:
	return ProjectSettings.globalize_path("res://").path_join(CONTENT).simplify_path()


static func room_path(room_id: String) -> String:
	return rooms_dir().path_join(room_id + ".json")


static func list_rooms() -> PackedStringArray:
	var ids: PackedStringArray = PackedStringArray()
	var dir := DirAccess.open(rooms_dir())
	if dir == null:
		return ids
	dir.list_dir_begin()
	var fname := dir.get_next()
	while fname != "":
		if not dir.current_is_dir() and fname.ends_with(".json"):
			ids.append(fname.get_basename())
		fname = dir.get_next()
	ids.sort()
	return ids


static func picked_room() -> String:
	var rooms := list_rooms()
	if last_room in rooms:
		return last_room
	if "crypt" in rooms:
		return "crypt"
	return rooms[0] if rooms.size() > 0 else ""


static func host_error(root: Node) -> String:
	if root == null:
		return "open a scene first — the preview is built into the scene you are editing."
	if root is World:
		return ("this is world.tscn, whose root is a World — Room walks up to it "
			+ "and every call fails on a placeholder. Open a blank 3D scene instead.")
	return ""


## Build the room under `root`. Returns a count of what it drew, or `{"error": ...}`.
static func build_into(root: Node, room_id: String) -> Dictionary:
	var blocked := host_error(root)
	if not blocked.is_empty():
		return {"error": blocked}

	var view := view_of(room_id)
	if view.has("error"):
		return view

	var size := Vector2i(int(view["width"]), int(view["height"]))

	var old := root.get_node_or_null(NodePath(HOLDER))
	if old != null:
		root.remove_child(old)
		old.free()

	# A plain Node3D, deliberately not a World. Room needs no World above it:
	# room.gd falls back to Grid.to_world at the origin.
	var holder := Node3D.new()
	holder.name = HOLDER
	root.add_child(holder)

	var room := Room.new()
	room.name = "Room"
	holder.add_child(room)
	room.build(view, room_id)

	if LIGHT_LIKE_THE_GAME:
		holder.add_child(_torchlit())

	var props := Node3D.new()
	props.name = "Props"
	holder.add_child(props)
	var skipped: Array[String] = []

	return {
		"roomId": room_id,
		"width": int(view["width"]),
		"height": int(view["height"]),
		"floor": _count(room, "Floor"),
		"walls": _count(room, "Walls"),
		"torches": _count(room, "Torches"),
		"props": _build_props(props, size, view, skipped),
		"propsTotal": (view.get("props", []) as Array).size(),
		"skipped": skipped,
	}


## A room's JSON, in the shape `Room.build` reads. Empty `coveredWalls` is correct
## for a lone room — do not reimplement `Rooms.coveredWalls()` here.
static func view_of(room_id: String) -> Dictionary:
	var path := room_path(room_id)
	if not FileAccess.file_exists(path):
		return {"error": "no room file at %s" % path}
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(path))
	if typeof(parsed) != TYPE_DICTIONARY:
		return {"error": "%s is not a JSON object" % path}
	var view: Dictionary = parsed
	view["visited"] = true
	view["coveredWalls"] = []
	view["originX"] = 0.0
	view["originZ"] = 0.0
	return view


## Parse the room JSON, mutate only x/y/rotation of one prop, re-serialize the rest
## as it was. Never rebuild the file from the fields the tool knows about.
static func write_prop(path: String, prop_id: String, x: int, y: int, rotation: int) -> String:
	if not FileAccess.file_exists(path):
		return "no room file at %s" % path
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(path))
	if typeof(parsed) != TYPE_DICTIONARY:
		return "%s is not a JSON object" % path
	var found := false
	for prop in parsed.get("props", []):
		if String(prop.get("id", "")) != prop_id:
			continue
		prop["x"] = x
		prop["y"] = y
		prop["rotation"] = rotation
		found = true
		break
	if not found:
		return "no prop '%s' in %s" % [prop_id, path]
	_whole_numbers(parsed)
	var file := FileAccess.open(path, FileAccess.WRITE)
	if file == null:
		return "cannot write %s" % path
	file.store_string(JSON.stringify(parsed, "  ") + "\n")
	file.close()
	return ""


static func write_room_prop(room_id: String, prop_id: String, x: int, y: int, rotation: int) -> String:
	return write_prop(room_path(room_id), prop_id, x, y, rotation)


static func _build_props(holder: Node3D, size: Vector2i, view: Dictionary,
		skipped: Array[String]) -> int:
	var room_id := String(view.get("roomId", ""))
	var drawn := 0
	for prop in view.get("props", []):
		if bool(prop.get("hidden", false)) and not SHOW_HIDDEN:
			continue
		var at := Grid.to_world(int(prop.get("x", 0)), int(prop.get("y", 0)), size)
		var node := PropPlace.into(holder, prop, room_id, at)
		if node == null:
			skipped.append("%s (%s)" % [prop.get("id", "?"), prop.get("type", "?")])
			continue
		drawn += 1
	return drawn


## The crypt's real ambient, read off world.tscn rather than restated. Instantiated
## off-tree so World's `_ready` never fires.
static func _torchlit() -> WorldEnvironment:
	var packed := load("res://world/world.tscn") as PackedScene
	var world: Node = packed.instantiate()
	var src := world.get_node_or_null("Room/Lighting/TORCHLIT/WorldEnvironment") as WorldEnvironment
	var env: Environment = null
	if src != null and src.environment != null:
		env = src.environment.duplicate()
	world.free()
	var node := WorldEnvironment.new()
	node.name = "Env"
	node.environment = env
	return node


static func _count(room: Room, group: String) -> int:
	var node := room.get_node_or_null(NodePath(group))
	return 0 if node == null else node.get_child_count()


## JSON.parse turns every number into a float. Whole values go back to int so a write
## does not turn every `6` in the file into `6.0`.
static func _whole_numbers(value: Variant) -> void:
	match typeof(value):
		TYPE_DICTIONARY:
			var d: Dictionary = value
			for k in d.keys():
				var v: Variant = d[k]
				if typeof(v) == TYPE_FLOAT and is_equal_approx(v, roundf(v)):
					d[k] = int(roundf(v))
				else:
					_whole_numbers(v)
		TYPE_ARRAY:
			var a: Array = value
			for i in a.size():
				var v: Variant = a[i]
				if typeof(v) == TYPE_FLOAT and is_equal_approx(v, roundf(v)):
					a[i] = int(roundf(v))
				else:
					_whole_numbers(v)
