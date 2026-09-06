class_name Room
extends Node3D

## Floor, walls, and the wall-torch lights. Rebuilt only when `roomId` changes (invariant #4).
## Tile and segment picks are FNV-1a of the room id, matching `assets.ts` / `Renderer.ts`.

## Measured 2026-08-22 on the imported KayKit wall.gltf (kit units, before `1 / module`):
## AABB size (4.000, 4.000, 1.000). One square is the wall's width. Do not replace this
## with a guessed literal downstream — `scale = 1 / module`. Small floor tiles are a
## 2-unit half-cell in the pack; they fill a square from their own XY, not from this number.
const KAYKIT_MODULE := 4.0
const WALL_UNIT := 1.0
const WALL_DEPTH := 1.0 / KAYKIT_MODULE
const MAX_TORCH_LIGHTS := 10
const WALL_TORCH_Y := 0.42
const TORCH_HEIGHT := 0.65
const TORCH_FOOTPRINT := 0.4
## Godot energy, not Three.js candela. Retuned so the fires light the perimeter and the
## middle of a generated room is still a dark floor, not a sunlit one.
const TORCH_ENERGY := 3.2
const TORCH_RANGE := 7.0
const TORCH_INSET := WALL_DEPTH * 0.5
## Near-black with a cold cast, for a room nobody has walked into yet. Not pure black: a
## silhouette says "stone, further in"; a void says the renderer has fallen over.
const UNLIT_TINT := Color(0.043, 0.043, 0.055)
const EMBER := Color(1.0, 0.6039216, 0.2392157)
const FLAME_CORE := Color(1.0, 0.8313726, 0.5372549)
const TORCH_SPACING := {
	"TORCHLIT": 3,
	"DIM": 5,
	"DARK": 0,
}

## STONE is mostly a plain slab, with wear as the rare hole. CARVED spends its weight on
## cracked, broken, windowed and gated faces — the reason wallType is finally read.
const WALL_VARIANTS_STONE: Array[Dictionary] = [
	{"path": "kaykit_dungeon/wall", "weight": 24},
	{"path": "kaykit_dungeon/wall_broken", "weight": 3},
	{"path": "kaykit_dungeon/wall_cracked", "weight": 3},
]

## Nothing in here has a passage through it, deliberately. A hole in the wall is a way out;
## the exits list says where the ways out are, and a hashed one somewhere else is a door that
## goes nowhere. That rules out `wall_gated` and `wall_doorway`, and also
## `wall_archedwindow_open` — it is called a window but it is a shoulder-height arch reaching
## the floor, and it reads as a doorway. `wall_window_open` stays: a small barred opening high
## on the wall is a window, and a window is allowed to lead nowhere.
const WALL_VARIANTS_CARVED: Array[Dictionary] = [
	{"path": "kaykit_dungeon/wall", "weight": 10},
	{"path": "kaykit_dungeon/wall_cracked", "weight": 8},
	{"path": "kaykit_dungeon/wall_broken", "weight": 5},
	{"path": "kaykit_dungeon/wall_window_open", "weight": 5},
]

## The exit's own segment. KayKit's `wall_doorway` is a wall with a banded wooden door already
## hung in it, which is why the DOOR prop draws nothing: the door is the wall here, and a prop
## on top of it was a second door with a second ring handle. It is also what makes the click
## target and the thing you can see one object rather than two.
const WALL_DOORWAY := "kaykit_dungeon/wall_doorway"

## Same gradient Renderer.ts describes — laid vs worn — on KayKit's three-tier tiles.
## Weights keep that shape (TILED almost all laid, CRACKED mostly gone) rather than the
## two-tile numbers, which were an instrument this pack does not play.
const FLOOR_VARIANTS := {
	"STONE": [
		{"path": "kaykit_dungeon/floor_tile_small", "weight": 8},
		{"path": "kaykit_dungeon/floor_tile_small_broken_A", "weight": 2},
		{"path": "kaykit_dungeon/floor_tile_small_broken_B", "weight": 1},
	],
	"CRACKED_STONE": [
		{"path": "kaykit_dungeon/floor_tile_small_broken_A", "weight": 3},
		{"path": "kaykit_dungeon/floor_tile_small_broken_B", "weight": 2},
		{"path": "kaykit_dungeon/floor_dirt_small_A", "weight": 2},
		{"path": "kaykit_dungeon/floor_dirt_small_B", "weight": 1},
		{"path": "kaykit_dungeon/floor_dirt_small_C", "weight": 1},
		{"path": "kaykit_dungeon/floor_dirt_small_D", "weight": 1},
		{"path": "kaykit_dungeon/floor_tile_large_rocks", "weight": 2},
	],
	"TILED": [
		{"path": "kaykit_dungeon/floor_tile_small_decorated", "weight": 12},
		{"path": "kaykit_dungeon/floor_tile_large", "weight": 2},
		{"path": "kaykit_dungeon/floor_tile_small_broken_A", "weight": 1},
	],
}

var _room_id := ""
var _packed: Dictionary = {}


func _ready() -> void:
	# Neighbour rooms sit under World/Neighbours, not World. They must not rebuild from
	# Table.scene — that is the current room, and would light a space the DM has not been told
	# about (spec §8b).
	if not (get_parent() is World):
		return
	Table.scene_changed.connect(_on_scene_changed)
	_on_scene_changed()


## Unsigned FNV-1a. Same numbers as `hash32` in `client/src/scene/assets.ts`.
static func hash32(text: String) -> int:
	var h: int = 2166136261
	for i in text.length():
		h ^= text.unicode_at(i)
		h = (h * 16777619) & 0xFFFFFFFF
	return h


func rebuild() -> void:
	_clear(_ensure_group("Floor"))
	_clear(_ensure_group("Walls"))
	_clear(_ensure_group("Torches"))
	if Table.scene.is_empty():
		return
	var size := Vector2i(_width(), _height())
	_build_floor(_room_id, size, String(Table.scene.get("floorType", "STONE")))
	_build_walls(_room_id, size, String(Table.scene.get("wallType", "STONE")),
		Table.scene.get("exits", []))
	_build_wall_torches()


## Floor and walls, and no lights at all.
##
## The lit build takes its torch spacing from a LightingPreset. A neighbour has no preset, on
## purpose: it is a room the DM has not been told about, and a lit one would be a room on screen
## the narrator can contradict. It also keeps MAX_TORCH_LIGHTS a per-room budget rather than a
## number two rooms quietly share.
func build_unlit(room_id: String, size: Vector2i, floor_type: String, wall_type: String,
		openings: Array = []) -> void:
	if Table.scene_changed.is_connected(_on_scene_changed):
		Table.scene_changed.disconnect(_on_scene_changed)
	_room_id = room_id
	_clear(_ensure_group("Floor"))
	_clear(_ensure_group("Walls"))
	_build_floor(room_id, size, floor_type)
	_build_walls(room_id, size, wall_type, openings, true)
	_darken(self)


## Paint every surface in an unvisited room near-black and unshaded.
##
## Withholding the torches is not enough on its own: the WorldEnvironment's ambient still lifts
## the kit textures far enough to read, and a neighbour you can see the flagstones of is a second
## lit room rather than somewhere you have not been. Unshaded also keeps the current room's
## torches from spilling across the threshold, which would light the far room by degrees as the
## party walked up to the door.
func _darken(root: Node) -> void:
	var dark := StandardMaterial3D.new()
	dark.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	dark.albedo_color = UNLIT_TINT
	for mesh in _meshes(root):
		mesh.material_override = dark
		mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


func _on_scene_changed() -> void:
	if Table.scene.is_empty():
		return
	var room_id := String(Table.scene.get("roomId", ""))
	if room_id == _room_id:
		return
	_room_id = room_id
	rebuild()


func _build_floor(room_id: String, size: Vector2i, floor_type: String) -> void:
	var width := size.x
	var height := size.y
	var variants: Array = FLOOR_VARIANTS.get(floor_type, FLOOR_VARIANTS["STONE"])
	var total := _weight_total(variants)
	var holder: Node3D = _ensure_group("Floor")

	for gy in height:
		for gx in width:
			var hashed := hash32("%s:floor:%d,%d" % [room_id, gx, gy])
			var variant: Dictionary = _pick(variants, hashed, total)
			var tile := _instance_piece(String(variant["path"]), {"fill_square": true})
			tile.set_meta("floor_variant", String(variant["path"]))
			tile.name = "floor_%s" % String(variant["path"]).get_file()
			var yaw := float((hashed >> 16) % 4) * (PI / 2.0)
			tile.rotation.y = yaw
			var box := _aabb_of(tile)
			var at := _to_world(room_id, gx, gy, size)
			tile.position = Vector3(at.x, -box.end.y, at.z)
			holder.add_child(tile)


## `openings` are exits, in the wire shape — {x, y, direction}. The segment an exit stands in
## becomes a doorway instead of a hashed face. Placement order is unchanged, because the hash
## that picks a face is the placement index: reordering this loop reshuffles every wall in the
## crypt for no reason.
##
## `omit_shared_wall` is set for a neighbour, and it drops that whole wall rather than the one
## segment. Two rooms joined by a door share the wall it is in: the server places them so the
## doors line up, which puts both perimeters on the same plane. Drawing both is two walls
## fighting for the same depth — it showed first as the crypt's north door growing a second
## ring handle, and then as the wall either side of it going to noise. The room the party is
## standing in owns that plane; the room beyond leaves the whole run alone rather than trying
## to interleave with it, which cannot half-work the way matching segment against segment can.
func _build_walls(room_id: String, size: Vector2i, wall_type: String,
		openings: Array = [], omit_shared_wall: bool = false) -> void:
	var width := size.x
	var height := size.y
	var half_w := float(width) / 2.0
	var half_h := float(height) / 2.0
	# The live room sits at the origin. A neighbour's parent is not World, so its local
	# wall placements would otherwise land on the crypt — shift them by the registered origin.
	var origin := Vector3.ZERO
	var world := _host_world()
	if world != null and not (get_parent() is World):
		origin = world.room_origin(room_id)
	var placements: Array[Dictionary] = []
	for gx in width:
		var x := float(gx) - half_w + 0.5
		placements.append({"x": x + origin.x, "z": -half_h + origin.z, "ry": 0.0,
			"square": Vector2i(gx, height - 1), "dir": "NORTH"})
		placements.append({"x": x + origin.x, "z": half_h + origin.z, "ry": PI,
			"square": Vector2i(gx, 0), "dir": "SOUTH"})
	for gy in height:
		var z := -(float(gy) - half_h + 0.5)
		placements.append({"x": -half_w + origin.x, "z": z + origin.z, "ry": PI / 2.0,
			"square": Vector2i(0, gy), "dir": "WEST"})
		placements.append({"x": half_w + origin.x, "z": z + origin.z, "ry": -PI / 2.0,
			"square": Vector2i(width - 1, gy), "dir": "EAST"})

	var variants: Array = WALL_VARIANTS_CARVED if wall_type == "CARVED" else WALL_VARIANTS_STONE
	var total := _weight_total(variants)
	var holder: Node3D = _ensure_group("Walls")

	for i in placements.size():
		var placement: Dictionary = placements[i]
		if omit_shared_wall and _on_a_shared_wall(openings, placement):
			continue
		var opening := _opening_at(openings, placement)
		if not opening.is_empty():
			var doorway := _add_wall_segment(holder, {"path": WALL_DOORWAY}, placement)
			# What the pointer addresses. An Exit carries the id of the DOOR prop standing in
			# its square, so this id resolves through Table.exit_by_id exactly as a prop's does.
			doorway.set_meta("exit_id", String(opening.get("id", "")))
			continue
		var hashed := hash32("%s:wall:%d" % [room_id, i])
		_add_wall_segment(holder, _pick(variants, hashed, total), placement)


## Whether this segment is on a wall some exit passes through — the whole run, not the one
## square. Direction alone: a room has four walls and an exit names the one it is in.
static func _on_a_shared_wall(openings: Array, placement: Dictionary) -> bool:
	for exit_dict in openings:
		if typeof(exit_dict) == TYPE_DICTIONARY \
				and String(exit_dict.get("direction", "")) == String(placement["dir"]):
			return true
	return false


static func _opening_at(openings: Array, placement: Dictionary) -> Dictionary:
	for exit_dict in openings:
		if typeof(exit_dict) != TYPE_DICTIONARY:
			continue
		if String(exit_dict.get("direction", "")) != String(placement["dir"]):
			continue
		if Vector2i(int(exit_dict.get("x", -1)), int(exit_dict.get("y", -1))) == placement["square"]:
			return exit_dict
	return {}


func _add_wall_segment(holder: Node3D, variant: Dictionary, placement: Dictionary) -> Node3D:
	var piece := _instance_piece(String(variant["path"]), {})
	piece.set_meta("wall_variant", String(variant["path"]))
	piece.name = "wall_%s" % String(variant["path"]).get_file()
	piece.position = Vector3(float(placement["x"]), 0.0, float(placement["z"]))
	piece.rotation.y = float(placement["ry"])
	holder.add_child(piece)
	return piece


func _build_wall_torches() -> void:
	var lighting := String(Table.scene.get("lighting", "TORCHLIT"))
	var spacing: int = int(TORCH_SPACING.get(lighting, 0))
	if spacing <= 0:
		return
	var width := _width()
	var height := _height()
	var half_w := float(width) / 2.0
	var half_h := float(height) / 2.0
	var mounts: Array[Dictionary] = []
	for gx in width:
		if gx % spacing != 0:
			continue
		var x := float(gx) - half_w + 0.5
		mounts.append({"x": x, "z": -half_h + TORCH_INSET, "ry": 0.0})
		mounts.append({"x": x, "z": half_h - TORCH_INSET, "ry": PI})
	for gy in height:
		if gy % spacing != 0:
			continue
		var z := -(float(gy) - half_h + 0.5)
		mounts.append({"x": -half_w + TORCH_INSET, "z": z, "ry": PI / 2.0})
		mounts.append({"x": half_w - TORCH_INSET, "z": z, "ry": -PI / 2.0})

	var lights := 0
	var holder: Node3D = $Torches
	for mount in mounts:
		var lit := lights < MAX_TORCH_LIGHTS
		if lit:
			lights += 1
		holder.add_child(_make_wall_torch(mount, lit))


func _make_wall_torch(mount: Dictionary, lit: bool) -> Node3D:
	var torch := Node3D.new()
	torch.name = "torch"
	torch.position = Vector3(float(mount["x"]), WALL_TORCH_Y, float(mount["z"]))
	torch.rotation.y = float(mount["ry"])
	var model := _instance_piece("kaykit_dungeon/torch_mounted", {"prop": true})
	_fit_mounted(model, TORCH_HEIGHT, TORCH_FOOTPRINT)
	torch.add_child(model)
	if not lit:
		return torch
	var flame := MeshInstance3D.new()
	var ball := SphereMesh.new()
	ball.radius = 0.075
	ball.height = 0.15
	ball.radial_segments = 8
	ball.rings = 6
	flame.mesh = ball
	var glow := StandardMaterial3D.new()
	glow.albedo_color = FLAME_CORE
	glow.emission_enabled = true
	glow.emission = FLAME_CORE
	glow.emission_energy_multiplier = 1.1
	glow.roughness = 1.0
	flame.material_override = glow
	flame.position.y = TORCH_HEIGHT * 0.94
	flame.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	torch.add_child(flame)
	var light := OmniLight3D.new()
	light.name = "flame"
	light.light_color = EMBER
	light.light_energy = TORCH_ENERGY
	light.omni_range = TORCH_RANGE
	light.omni_attenuation = 2.0
	light.shadow_enabled = false
	light.position.y = TORCH_HEIGHT
	torch.add_child(light)
	return torch


func _instance_piece(path: String, opts: Dictionary) -> Node3D:
	var piece := Node3D.new()
	piece.name = path.get_file()
	var packed := _load_packed(path)
	if packed == null:
		return piece
	var model: Node = packed.instantiate()
	if model is Node3D:
		_bake_into(piece, model as Node3D)
	else:
		piece.add_child(model)
	if bool(opts.get("fill_square", false)):
		_fill_square(piece)
	elif not bool(opts.get("prop", false)):
		piece.scale = Vector3.ONE * (1.0 / KAYKIT_MODULE)
	_shadows(piece)
	_filter_albedo(piece)
	return piece


func _bake_into(host: Node3D, model: Node3D) -> void:
	# Keep the imported root's transform (glTF often parks scale there) by reparenting
	# children onto a wrapper that we then scale as a module.
	host.add_child(model)


func _fill_square(piece: Node3D) -> void:
	piece.scale = Vector3.ONE
	var box := _aabb_of(piece)
	var span := maxf(box.size.x, box.size.z)
	piece.scale = Vector3.ONE * (WALL_UNIT / maxf(span, 1e-6))


func _fit_mounted(piece: Node3D, height: float, footprint: float) -> void:
	# Bracket stays on z = 0 (the wall face). Centering xz would bury half the torch.
	piece.scale = Vector3.ONE
	piece.position = Vector3.ZERO
	var box := _aabb_of(piece)
	var s := minf(height / maxf(box.size.y, 1e-6),
		footprint / maxf(maxf(box.size.x, box.size.z), 1e-6))
	piece.scale = Vector3.ONE * s
	piece.position = Vector3(
		-(box.position.x + box.size.x * 0.5) * s,
		-box.position.y * s,
		-box.position.z * s,
	)


func _surface_count(mesh: MeshInstance3D) -> int:
	if mesh.mesh:
		return mesh.mesh.get_surface_count()
	return mesh.get_surface_override_material_count()


func _meshes(root: Node) -> Array[MeshInstance3D]:
	var found: Array[MeshInstance3D] = []
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is MeshInstance3D:
			found.append(node as MeshInstance3D)
		for child in node.get_children():
			stack.append(child)
	return found


func _shadows(root: Node) -> void:
	for mesh in _meshes(root):
		mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
		mesh.gi_mode = GeometryInstance3D.GI_MODE_DISABLED


func _filter_albedo(root: Node) -> void:
	for mesh in _meshes(root):
		for i in _surface_count(mesh):
			var mat := mesh.get_active_material(i)
			if mat is BaseMaterial3D:
				(mat as BaseMaterial3D).texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR


func _aabb_of(root: Node3D) -> AABB:
	var boxes: Array[AABB] = []
	_collect_aabb(root, Transform3D.IDENTITY, boxes)
	if boxes.is_empty():
		return AABB()
	var acc := boxes[0]
	for i in range(1, boxes.size()):
		acc = acc.merge(boxes[i])
	return acc


func _collect_aabb(node: Node, parent_xf: Transform3D, boxes: Array[AABB]) -> void:
	var xf := parent_xf
	if node is Node3D:
		xf = parent_xf * (node as Node3D).transform
	if node is VisualInstance3D:
		boxes.append(xf * (node as VisualInstance3D).get_aabb())
	for child in node.get_children():
		_collect_aabb(child, xf, boxes)


func _load_packed(path: String) -> PackedScene:
	if _packed.has(path):
		return _packed[path]
	var res := _res_path(path)
	var scene: PackedScene = null
	if ResourceLoader.exists(res):
		scene = load(res) as PackedScene
	_packed[path] = scene
	return scene


func _res_path(path: String) -> String:
	if path.begins_with("kaykit_dungeon/"):
		return "res://world/kits/kaykit_dungeon/%s.gltf" % path.trim_prefix("kaykit_dungeon/")
	if path.begins_with("kaykit_halloween/"):
		return "res://world/kits/kaykit_halloween/%s.gltf" % path.trim_prefix("kaykit_halloween/")
	if path.begins_with("props/ruins/"):
		return "res://world/kits/ruins/%s.glb" % path.substr("props/ruins/".length())
	if path.begins_with("props/dungeon/"):
		return "res://world/kits/dungeon_props/%s.glb" % path.substr("props/dungeon/".length())
	return ""


func _pick(variants: Array, hashed: int, total: int) -> Dictionary:
	var roll: int = hashed % total
	for variant in variants:
		roll -= int(variant["weight"])
		if roll < 0:
			return variant
	return variants[0]


func _weight_total(variants: Array) -> int:
	var total := 0
	for variant in variants:
		total += int(variant["weight"])
	return total


func _ensure_group(group_name: String) -> Node3D:
	var node := get_node_or_null(group_name) as Node3D
	if node == null:
		node = Node3D.new()
		node.name = group_name
		add_child(node)
	return node


func _host_world() -> World:
	var node: Node = get_parent()
	while node:
		if node is World:
			return node as World
		node = node.get_parent()
	return null


func _to_world(room_id: String, gx: int, gy: int, size: Vector2i) -> Vector3:
	var world := _host_world()
	# Neighbour parent is not World. Floors are world-space via grid_to_world, which already
	# includes the registered origin — do not also move this node, or the offset applies twice.
	if world != null and not (get_parent() is World):
		return world.grid_to_world(room_id, gx, gy)
	return Vector3(
		gx - float(size.x) / 2.0 + 0.5,
		0.0,
		-(gy - float(size.y) / 2.0 + 0.5),
	)


func _width() -> int:
	return int(Table.scene.get("width", 12))


func _height() -> int:
	return int(Table.scene.get("height", 12))


func _clear(node: Node) -> void:
	if node == null:
		return
	for child in node.get_children():
		node.remove_child(child)
		child.free()
