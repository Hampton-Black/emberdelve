extends Node3D

## Throwaway harness for emberdelve-27l — renders the crypt at candidate ambient energies so the
## number can be judged by looking rather than by reading. Delete when the ticket closes.
##
##     /Applications/Godot.app/Contents/MacOS/Godot --path godot res://dev/light_shots.tscn
##
## It drives the real client: world.tscn, so lighting.gd picks the environment, room.gd builds the
## wall torches, and CameraRig sits at its own four corners. Nothing here restates a number the
## game holds — the only value written is EnvTorchlit's ambient_light_energy, one at a time.

const SceneFixtures := preload("res://test/scene_fixtures.gd")
const PreviewRoom := preload("res://dev/preview_room.gd")

## Two variables, not one. The playtest observation the ticket rests on compared the crypt seen
## from the gallery against the crypt stood in — and those differ by the wall fires as well as by
## the ambient, because `room.gd` only burns the torches of the room the party is in. The braziers
## are props and stay lit either way. So the ladder crosses both.
const CASES := [
	{"fires": true, "braziers": "orange", "flame": ORANGE_FLAME, "power": 4.5,
		"note": "as M3 played it"},
	{"fires": false, "braziers": "orange", "flame": ORANGE_FLAME, "power": 4.5, "note": ""},
	{"fires": false, "braziers": "green", "flame": GREEN_FLAME, "power": GREEN_POWER,
		"note": "as the DM describes it"},
	{"fires": false, "braziers": "out", "flame": GREEN_FLAME, "power": GREEN_POWER,
		"note": "every fire out — what a lantern would be the only light in"},
]

## The ambient this ticket settled. Every sheet is rendered at it; the variable is the fires.
const AMBIENT := 0.7

## The colour both recorded sessions gave the crypt unprompted — "guttering low with green,
## smokeless flame", "the green braziers still hiss". Chosen against the floor rather than in
## isolation: `Overlay.EXIT_TINT` is a teal (#3cd2c8), so a green that leans cold puts the door
## marker back where ADR-0011 found it invisible. This one sits about 56 degrees of hue off it.
##
## Energy is well under the orange 4.5 because green is where the eye is most sensitive — the same
## number reads far hotter, and the prose word is "guttering".
const GREEN_FLAME := Color(0.42, 0.86, 0.44)
const GREEN_POWER := 2.8
const ORANGE_FLAME := Color(1.0, 0.603922, 0.239216)
const OUT := "user://light_shots"
const SHOT := Vector2i(1920, 1080)
const CELL := Vector2i(960, 540)

var _world: Node3D
var _label: Label


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
	var env: Environment = (_world.get_node("Room/Lighting/TORCHLIT/WorldEnvironment")
		as WorldEnvironment).environment

	for corner in 4:
		var sheet := Image.create(CELL.x * 2, CELL.y * 2, false, Image.FORMAT_RGBA8)
		for i in CASES.size():
			var c: Dictionary = CASES[i]
			env.ambient_light_energy = AMBIENT
			_set_fires(bool(c["fires"]))
			_set_braziers(String(c["braziers"]), c["flame"], float(c["power"]))
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

	env.ambient_light_energy = AMBIENT
	_set_fires(true)
	_set_braziers("orange", ORANGE_FLAME, 4.5)
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
func _set_braziers(mode: String, flame_colour: Color, power: float) -> void:
	# Props hang under `Props/<room id>`, not directly under `Props` — world.gd is explicit that a
	# prop id is only unique within its room.
	for prop in _world.get_node("Props/crypt").get_children():
		if not String(prop.name).begins_with("brazier"):
			continue
		var flame := prop.get_node_or_null("flame") as OmniLight3D
		if flame != null:
			flame.visible = mode != "out"
			flame.light_color = flame_colour
			flame.light_energy = power
		var coals := prop.get_node_or_null("Meshes/Coals") as MeshInstance3D
		if coals != null:
			var mat := coals.get_surface_override_material(0) as StandardMaterial3D
			if mat != null:
				var lit := mode != "out"
				var tint := flame_colour.lightened(0.45)
				mat.albedo_color = tint if lit else Color(0.24, 0.23, 0.22)
				mat.emission = tint
				mat.emission_energy_multiplier = 0.85 if lit else 0.0


func _settle(frames: int) -> void:
	for _i in frames:
		await get_tree().process_frame
	await RenderingServer.frame_post_draw
