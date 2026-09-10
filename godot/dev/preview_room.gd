@tool
extends EditorScript

## Draws one authored room into the scene you are editing, with the real kit meshes.
##
## A spike for emberdelve-4h9.10, not the tool that issue describes: it reads, it never writes,
## and the prop instancing here is a copy of `World._instance_prop` rather than the shared
## function the real tool has to extract. Ten minutes of code to find out whether the editor
## can show a room at all.
##
## Run it with the Godot editor open: File > Run (Ctrl+Shift+X on Linux/Windows, Cmd+Shift+X on
## macOS) with this script focused. It builds under a node called `RoomPreview` in the open
## scene, clearing any previous one first.
##
## [b]Nothing it builds has an `owner`[/b], so none of it is saved into the `.tscn` — the preview
## lives in the editor's memory and disappears when you close the scene. That is deliberate:
## a room that got serialised into `world.tscn` would be a second place the crypt is defined,
## and the room JSON is the only place it is defined.
##
## [b]Open a blank 3D scene, never `world.tscn`.[/b] That scene's root is a `World`, and
## `Room._host_world` walks up the tree to find one — in the editor it is a placeholder, so every
## `grid_to_world` and `room_origin` call through it fails. This script refuses that case rather
## than leaving it to be remembered. `LIGHT_LIKE_THE_GAME` gets you the crypt's real ambient
## without needing that scene.
##
## Why this works at all — `room.gd` holds no reference to `Table`, and its `_to_world` falls
## back to a self-centred grid when no `World` is above it. Neither is an accident of this
## script; both are properties the renderer already had.

## The room to draw. Any file in the server's content/rooms directory, without the extension.
const ROOM := "crypt"

## Hidden props are drawn. Wrong for the game, right for authoring: the crypt's alcove is a
## secret and it is still a thing you are placing.
const SHOW_HIDDEN := true

## Mirror `world.tscn`'s `EnvTorchlit` so a blank scene shows the room at the light the game
## gives it. Off gets you the editor's own neutral light, which is easier to place props by.
## These four numbers are a copy — the real tool should read the environment off `world.tscn`
## rather than restate it. Spike.
const LIGHT_LIKE_THE_GAME := true

const HOLDER := "RoomPreview"
const PROP_TABLE := preload("res://world/prop_table.tres")
const CONTENT := "../server/src/main/resources/content/rooms/"


func _run() -> void:
	var root := EditorInterface.get_edited_scene_root()
	if root == null:
		push_error("preview_room: open a scene first — the preview is built into the scene you are editing.")
		return

	if root is World:
		push_error("preview_room: this is world.tscn, whose root is a World — Room walks up to it "
			+ "and every call fails on a placeholder. Open a blank 3D scene instead; "
			+ "LIGHT_LIKE_THE_GAME gives you the same ambient.")
		return

	var old := root.get_node_or_null(NodePath(HOLDER))
	if old != null:
		root.remove_child(old)
		old.free()

	var report := build_into(root, ROOM)
	if report.has("error"):
		push_error("preview_room: %s" % report["error"])
		return

	print("preview_room: %s — %dx%d, %d floor, %d walls, %d torches, %d of %d props" % [
		report["roomId"], report["width"], report["height"],
		report["floor"], report["walls"], report["torches"],
		report["props"], report["propsTotal"],
	])
	# A prop that quietly fails to appear is this project's characteristic bug, so the ones with
	# no mesh are named rather than left as a gap in the count. DOOR is always here and is not a
	# fault: the door is the wall segment, and `prop_table.tres` says so at length.
	if not (report["skipped"] as Array).is_empty():
		print("preview_room: no mesh for %s" % ", ".join(report["skipped"]))


## Build the room under `root`. Shared with the headless check so the editor path is the tested
## one; returns a count of what it drew, or `{"error": ...}`.
static func build_into(root: Node, room_id: String) -> Dictionary:
	var view := view_of(room_id)
	if view.has("error"):
		return view

	var size := Vector2i(int(view["width"]), int(view["height"]))

	# A plain Node3D, deliberately not a World. `world.gd` is not a @tool script, so in the editor
	# its `_ready` still fires and reaches the `Table` autoload — itself a placeholder there — and
	# every call into it fails. `Room` needs no World above it: room.gd:554 falls back to the
	# centred grid this file reimplements in `_grid_to_world`.
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


## A room's JSON, in the shape `Room.build` reads.
##
## For one room the content shape is already the wire shape in everything that matters —
## crypt.json carries roomId, width, height, floorType, wallType, lighting, props and exits at
## the top level, and its exits already carry x, y and direction. Three fields are added here,
## and the empty `coveredWalls` is correct rather than a shortcut: a lone room is nearest the
## entrance by definition and so covers nothing (`test/scene_fixtures.gd`).
##
## This stops being true for two rooms side by side, where `coveredWalls` is an answer only
## `Rooms.coveredWalls()` can give. Do not reimplement it here — dump the real `RoomView` from
## the server instead. See emberdelve-4h9.10.
static func view_of(room_id: String) -> Dictionary:
	var path := ProjectSettings.globalize_path("res://").path_join(CONTENT) \
		.path_join(room_id + ".json").simplify_path()
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


static func _build_props(holder: Node3D, size: Vector2i, view: Dictionary,
		skipped: Array[String]) -> int:
	var room_id := String(view.get("roomId", ""))
	var drawn := 0
	for prop in view.get("props", []):
		if bool(prop.get("hidden", false)) and not SHOW_HIDDEN:
			continue
		var packed: PackedScene = PROP_TABLE.scene_for(String(prop.get("type", "")))
		if packed == null:
			skipped.append("%s (%s)" % [prop.get("id", "?"), prop.get("type", "?")])
			continue
		var node := packed.instantiate() as Node3D
		if node == null:
			continue
		node.name = String(prop.get("id", ""))
		node.position = _grid_to_world(int(prop.get("x", 0)), int(prop.get("y", 0)), size)
		node.rotation.y = deg_to_rad(float(prop.get("rotation", 0.0)))
		holder.add_child(node)
		if node.has_method("configure"):
			node.configure(prop, room_id)
		drawn += 1
	return drawn


## `World.grid_to_world` for a room at the origin, inlined because World cannot be built here.
## Identical to `Room._to_world`'s own no-World fallback, which is the room this draws on top of —
## if the two ever disagree the props sit off the floor tiles, which is a visible failure rather
## than a silent one. Spike duplication; 4h9.10's shared-function rule is the fix.
static func _grid_to_world(x: int, y: int, size: Vector2i) -> Vector3:
	return Vector3(
		x - float(size.x) / 2.0 + 0.5,
		0.0,
		-(y - float(size.y) / 2.0 + 0.5),
	)


static func _torchlit() -> WorldEnvironment:
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.0431373, 0.0392157, 0.0627451)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.235294, 0.227451, 0.321569)
	env.ambient_light_energy = 0.7
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	var node := WorldEnvironment.new()
	node.name = "Env"
	node.environment = env
	return node


static func _count(room: Room, group: String) -> int:
	var node := room.get_node_or_null(NodePath(group))
	return 0 if node == null else node.get_child_count()
