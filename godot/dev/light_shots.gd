extends Node3D

## Harness for emberdelve-27l, kept for emberdelve-4h9.16 — renders the crypt with and without its
## old wall fires and orange braziers, so a light can be judged by looking rather than by reading.
##
##     /Applications/Godot.app/Contents/MacOS/Godot --path godot res://dev/light_shots.tscn
##
## It drives the real client: world.tscn, so lighting.gd picks the environment, room.gd builds the
## wall torches, and CameraRig sits at its own four corners. The "green" case is the brazier as
## `brazier.tscn` authors it, read back rather than restated. The door's exit marker is lit in every
## shot, because it is the thing that has to read against whatever the floor has become.

const SceneFixtures := preload("res://test/scene_fixtures.gd")
const PreviewRoom := preload("res://dev/preview_room.gd")

## Two variables, not one. The playtest observation the ticket rests on compared the crypt seen
## from the gallery against the crypt stood in — and those differ by the wall fires as well as by
## the ambient, because `room.gd` only burns the torches of the room the party is in. The braziers
## are props and stay lit either way. So the ladder crosses both.
const CASES := [
	{"fires": true, "braziers": "orange", "note": "as M3 played it"},
	{"fires": false, "braziers": "orange", "note": ""},
	{"fires": false, "braziers": "green", "note": "as the game ships it — emberdelve-4h9.16"},
	{"fires": false, "braziers": "out",
		"note": "every fire out — what a lantern would be the only light in"},
]

## The ambient emberdelve-27l settled. Every sheet is rendered at it; the variable is the fires.
const AMBIENT := 0.7

## The brazier as it burned before emberdelve-4h9.16, for the comparison. The green is not here —
## it is whatever `brazier.tscn` says, captured before anything is touched.
const ORANGE_FLAME := Color(1.0, 0.603922, 0.239216)
const ORANGE_POWER := 4.5
const OUT := "user://light_shots"
const SHOT := Vector2i(1920, 1080)
const CELL := Vector2i(960, 540)

var _world: Node3D
var _label: Label
var _authored: Dictionary = {}


func _ready() -> void:
	_run()


func _run() -> void:
	DirAccess.make_dir_recursive_absolute(ProjectSettings.globalize_path(OUT))

	var view := PreviewRoom.view_of("crypt")
	if view.has("error"):
		push_error("light_shots: %s" % view["error"])
		get_tree().quit(1)
		return
	# Hidden props stay hidden: this is the room as it is played, not as it is authored.
	var props: Array = []
	for p in view.get("props", []):
		if not bool(p.get("hidden", false)):
			props.append(p)
	view["props"] = props
	view["mode"] = "EXPLORATION"
	view["combat"] = null
	view["blocked"] = []
	# A figure in the room is not decoration. AGENTS.md: tokens carry a faint emissive of their own
	# albedo, which is the thing that makes a darker ambient survivable — judging the light with an
	# empty floor would judge the wrong picture.
	view["entities"] = [{
		"id": "fighter", "kind": "fighter", "name": "Roderick",
		"x": 10, "y": 10, "hp": 20, "maxHp": 20, "isPlayerControlled": true,
	}]

	Table.reset()
	Table.set_scene(SceneFixtures.scene(view))

	_world = (load("res://world/world.tscn") as PackedScene).instantiate() as Node3D
	add_child(_world)

	var layer := CanvasLayer.new()
	_label = Label.new()
	_label.position = Vector2(28, 20)
	_label.add_theme_font_size_override("font_size", 34)
	_label.add_theme_color_override("font_color", Color(1, 1, 1))
	_label.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	_label.add_theme_constant_override("outline_size", 8)
	layer.add_child(_label)
	add_child(layer)

	await _settle(30)

	var rig = _world.get_node("Camera3D")
	# The group lighting.gd actually switched on. An inactive group's environment is null.
	var env: Environment = (_world.get_node("Room/Lighting/%s/WorldEnvironment"
		% String(view.get("lighting", "TORCHLIT"))) as WorldEnvironment).environment
	# The marker ADR-0011 found invisible once already. It has to be looked at, not assumed.
	var door: Dictionary = view.get("exits", [])[0]
	_world.get_node("Overlay").set_hover(Vector2i(int(door["x"]), int(door["y"])), "exit")

	for corner in 4:
		var sheet := Image.create(CELL.x * 2, CELL.y * 2, false, Image.FORMAT_RGBA8)
		for i in CASES.size():
			var c: Dictionary = CASES[i]
			env.ambient_light_energy = AMBIENT
			_set_fires(bool(c["fires"]))
			_set_braziers(String(c["braziers"]))
			_label.text = "wall fires %s   ·   braziers %s   ·   ambient %.2f   ·   corner %d%s" % [
				"lit" if c["fires"] else "out", c["braziers"], AMBIENT, corner,
				"" if String(c["note"]).is_empty() else "   ·   " + String(c["note"]),
			]
			await _settle(6)
			var shot := get_viewport().get_texture().get_image()
			shot.resize(CELL.x, CELL.y, Image.INTERPOLATE_LANCZOS)
			shot.convert(Image.FORMAT_RGBA8)
			sheet.blit_rect(shot, Rect2i(Vector2i.ZERO, CELL),
				Vector2i((i % 2) * CELL.x, (i / 2) * CELL.y))
		var path := ProjectSettings.globalize_path(OUT).path_join("crypt-corner%d.png" % corner)
		sheet.save_png(path)
		print("light_shots: wrote %s" % path)
		if corner < 3:
			rig.rotate_by(1)
			await _settle(50)

	print("light_shots: done — %s" % ProjectSettings.globalize_path(OUT))
	get_tree().quit()


## The real `burning` path rather than an imitation of it: `Room.build` clears the Torches group
## and rebuilds it with `level == Level.LIT`, so this does the same two calls. Hiding the lights it
## made would leave the flame meshes behind, and a flame with no light in it is a picture the game
## never draws.
func _set_fires(on: bool) -> void:
	var room := _world.get_node("Room") as Room
	room._clear(room._ensure_group("Torches"))
	room._build_wall_torches(Vector2i(12, 12), "TORCHLIT", on)


## The braziers are props, so `burning` never touched them — a room you have left keeps its brazier
## light. That is why the crypt seen from the gallery still had two pools of fire in it.
func _set_braziers(mode: String) -> void:
	# Props hang under `Props/<room id>`, not directly under `Props` — world.gd is explicit that a
	# prop id is only unique within its room.
	for prop in _world.get_node("Props/crypt").get_children():
		if not String(prop.name).begins_with("brazier"):
			continue
		var flame := prop.get_node_or_null("flame") as OmniLight3D
		var coals := prop.get_node_or_null("Meshes/Coals") as MeshInstance3D
		var mat: StandardMaterial3D = null
		if coals != null:
			mat = coals.get_surface_override_material(0) as StandardMaterial3D
		# Read before the first write. The two braziers share one Coals material, so the second
		# brazier would otherwise read back whatever the first was just painted.
		if _authored.is_empty() and flame != null and mat != null:
			_authored = {"colour": flame.light_color, "power": flame.light_energy,
				"coals": mat.emission, "glow": mat.emission_energy_multiplier}
		var lit := mode != "out"
		var colour: Color = ORANGE_FLAME if mode == "orange" else _authored.get("colour", Color.WHITE)
		var core: Color = colour.lightened(0.45) if mode == "orange" \
			else _authored.get("coals", Color.WHITE)
		if flame != null:
			flame.visible = lit
			flame.light_color = colour
			flame.light_energy = ORANGE_POWER if mode == "orange" else float(_authored.get("power", 1.0))
		if mat != null:
			mat.albedo_color = core if lit else Color(0.24, 0.23, 0.22)
			mat.emission = core
			mat.emission_energy_multiplier = float(_authored.get("glow", 0.85)) if lit else 0.0


func _settle(frames: int) -> void:
	for _i in frames:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
