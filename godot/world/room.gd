class_name Room
extends Node3D

## Floor, walls, and the wall-torch lights. Rebuilt only when `roomId` changes (invariant #4).
## Tile and segment picks are FNV-1a of the room id, matching `assets.ts` / `Renderer.ts`.

## Measured 2026-08-22 on the imported GLBs (kit units, before `1 / module`):
## ruins/Wall size (1.998, 2.001, 0.286); Floor_Squares (1.992, 0.116, 1.996);
## dungeon Arch (4.104, 4.010, 0.993) — two modules, hence `fit` not module scale.
const QUATERNIUS_MODULE := 2.0
const WALL_UNIT := 1.0
const MAX_TORCH_LIGHTS := 10
const WALL_TORCH_Y := 0.42
const TORCH_HEIGHT := 0.65
const TORCH_FOOTPRINT := 0.4
## Godot energy, not Three.js candela. Retuned so the fires light the perimeter and the
## middle of a generated room is still a dark floor, not a sunlit one.
const TORCH_ENERGY := 3.2
const TORCH_RANGE := 7.0
const TORCH_INSET := 0.32
const EMBER := Color(1.0, 0.6039216, 0.2392157)
const FLAME_CORE := Color(1.0, 0.8313726, 0.5372549)
const FLOOR_STONE := {
	"Main": Color(0.2588235, 0.2862745, 0.3137255),
	"Highlights": Color(0.3294118, 0.3607843, 0.3921569),
}
const STONE_ALIASES := {
	"Wall_Dark": "Main",
	"Wall_Medium": "Main",
	"Wall_Highlights": "Highlights",
}
const TORCH_SPACING := {
	"TORCHLIT": 3,
	"DIM": 5,
	"DARK": 0,
}

const WALL_VARIANTS: Array[Dictionary] = [
	{"path": "props/ruins/Wall", "weight": 24},
	{"path": "props/ruins/Wall_Hole", "weight": 3},
	{"path": "props/ruins/Window_Bars", "weight": 3},
	{"path": "props/dungeon/Arch", "weight": 2, "fit": WALL_UNIT, "retint": true,
		"fills": "props/dungeon/Arch_bars"},
	{"path": "props/dungeon/Arch", "weight": 2, "fit": WALL_UNIT, "retint": true,
		"fills": "props/dungeon/Arch_Door"},
]

const WALL_FILLERS := {
	"props/dungeon/Arch_bars": WALL_UNIT * 0.82,
	"props/dungeon/Arch_Door": WALL_UNIT * 0.82,
}

const FLOOR_VARIANTS := {
	"STONE": [
		{"path": "props/ruins/Floor_Squares", "weight": 8},
		{"path": "props/ruins/Floor_Standard", "weight": 3},
	],
	"CRACKED_STONE": [
		{"path": "props/ruins/Floor_Standard", "weight": 7},
		{"path": "props/ruins/Floor_Squares", "weight": 5},
	],
	"TILED": [
		{"path": "props/ruins/Floor_Squares", "weight": 14},
		{"path": "props/ruins/Floor_Standard", "weight": 1},
	],
}

var _room_id := ""
var _packed: Dictionary = {}
var _wall_palette: Dictionary = {}


func _ready() -> void:
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
	_clear($Floor)
	_clear($Walls)
	_clear($Torches)
	if Table.scene.is_empty():
		return
	_build_floor()
	_build_walls()
	_build_wall_torches()


func _on_scene_changed() -> void:
	if Table.scene.is_empty():
		return
	var room_id := String(Table.scene.get("roomId", ""))
	if room_id == _room_id:
		return
	_room_id = room_id
	rebuild()


func _build_floor() -> void:
	var width := _width()
	var height := _height()
	var floor_type := String(Table.scene.get("floorType", "STONE"))
	var variants: Array = FLOOR_VARIANTS.get(floor_type, FLOOR_VARIANTS["STONE"])
	var total := _weight_total(variants)
	var holder: Node3D = $Floor

	for gy in height:
		for gx in width:
			var hashed := hash32("%s:floor:%d,%d" % [_room_id, gx, gy])
			var variant: Dictionary = _pick(variants, hashed, total)
			var tile := _instance_piece(String(variant["path"]), {})
			tile.set_meta("floor_variant", String(variant["path"]))
			tile.name = "floor_%s" % String(variant["path"]).get_file()
			_paint_stone(tile, FLOOR_STONE)
			var yaw := float((hashed >> 16) % 4) * (PI / 2.0)
			tile.rotation.y = yaw
			tile.scale = Vector3.ONE * (1.0 / QUATERNIUS_MODULE)
			var box := _aabb_of(tile)
			var at := _to_world(gx, gy)
			tile.position = Vector3(at.x, -box.end.y, at.z)
			holder.add_child(tile)


func _build_walls() -> void:
	var width := _width()
	var height := _height()
	var half_w := float(width) / 2.0
	var half_h := float(height) / 2.0
	var placements: Array[Dictionary] = []
	for gx in width:
		var x := float(gx) - half_w + 0.5
		placements.append({"x": x, "z": -half_h, "ry": 0.0})
		placements.append({"x": x, "z": half_h, "ry": PI})
	for gy in height:
		var z := -(float(gy) - half_h + 0.5)
		placements.append({"x": -half_w, "z": z, "ry": PI / 2.0})
		placements.append({"x": half_w, "z": z, "ry": -PI / 2.0})

	var total := _weight_total(WALL_VARIANTS)
	var holder: Node3D = $Walls
	if _wall_palette.is_empty():
		_wall_palette = _palette_of(_instance_piece("props/ruins/Wall", {}))

	for i in placements.size():
		var hashed := hash32("%s:wall:%d" % [_room_id, i])
		var variant: Dictionary = _pick(WALL_VARIANTS, hashed, total)
		_add_wall_segment(holder, variant, placements[i], bool(variant.get("retint", false)))
		var fills := String(variant.get("fills", ""))
		if not fills.is_empty():
			_add_wall_segment(holder, {"path": fills, "fit": WALL_FILLERS.get(fills, WALL_UNIT)},
				placements[i], false)


func _add_wall_segment(holder: Node3D, variant: Dictionary, placement: Dictionary, retint: bool) -> void:
	var opts := {}
	if variant.has("fit"):
		opts["fit"] = float(variant["fit"])
	var piece := _instance_piece(String(variant["path"]), opts)
	if retint and not _wall_palette.is_empty():
		_retint_stone(piece, _wall_palette)
	piece.name = "wall_%s" % String(variant["path"]).get_file()
	piece.position = Vector3(float(placement["x"]), 0.0, float(placement["z"]))
	piece.rotation.y = float(placement["ry"])
	holder.add_child(piece)


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
	var model := _instance_piece("props/ruins/Torch", {})
	_fit_prop(model, TORCH_HEIGHT, TORCH_FOOTPRINT)
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
	if opts.has("fit"):
		_fit_height(piece, float(opts["fit"]))
	else:
		piece.scale = Vector3.ONE * (1.0 / QUATERNIUS_MODULE)
	_shadows(piece)
	_nearest(piece)
	return piece


func _bake_into(host: Node3D, model: Node3D) -> void:
	# Keep the imported root's transform (glTF often parks scale there) by reparenting
	# children onto a wrapper that we then scale as a module.
	host.add_child(model)


func _fit_height(piece: Node3D, fit: float) -> void:
	piece.scale = Vector3.ONE
	var box := _aabb_of(piece)
	var native := box.size.y
	piece.scale = Vector3.ONE * (fit / maxf(native, 1e-6))


func _fit_prop(piece: Node3D, height: float, footprint: float) -> void:
	piece.scale = Vector3.ONE
	piece.position = Vector3.ZERO
	var box := _aabb_of(piece)
	var s := minf(height / maxf(box.size.y, 1e-6),
		footprint / maxf(maxf(box.size.x, box.size.z), 1e-6))
	piece.scale = Vector3.ONE * s
	piece.position = Vector3(
		-(box.position.x + box.size.x * 0.5) * s,
		-box.position.y * s,
		-(box.position.z + box.size.z * 0.5) * s,
	)


func _surface_count(mesh: MeshInstance3D) -> int:
	if mesh.mesh:
		return mesh.mesh.get_surface_count()
	return mesh.get_surface_override_material_count()


func _paint_stone(piece: Node, colours: Dictionary) -> void:
	for mesh in _meshes(piece):
		for i in _surface_count(mesh):
			var mat := mesh.get_active_material(i)
			if mat == null or not (mat is BaseMaterial3D):
				continue
			var named := _material_name(mat)
			if not colours.has(named):
				continue
			var painted: BaseMaterial3D = (mat as BaseMaterial3D).duplicate()
			painted.albedo_color = colours[named]
			painted.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
			mesh.set_surface_override_material(i, painted)


func _retint_stone(piece: Node, palette: Dictionary) -> void:
	var mapped := {}
	for named in palette.keys():
		mapped[named] = palette[named]
	for alias in STONE_ALIASES.keys():
		var target: String = STONE_ALIASES[alias]
		if palette.has(target):
			mapped[alias] = palette[target]
	_paint_stone(piece, mapped)


func _palette_of(piece: Node) -> Dictionary:
	var palette := {}
	for mesh in _meshes(piece):
		for i in _surface_count(mesh):
			var mat := mesh.get_active_material(i)
			if mat is BaseMaterial3D:
				palette[_material_name(mat)] = (mat as BaseMaterial3D).albedo_color
	piece.free()
	return palette


func _material_name(mat: Material) -> String:
	if not mat.resource_name.is_empty():
		return mat.resource_name
	if not mat.resource_path.is_empty():
		return mat.resource_path.get_file().get_basename()
	return ""


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


func _nearest(root: Node) -> void:
	# Kits are mostly vertex colour; vegetation still carries bark/leaf sheets.
	# Nearest keeps a 480px upsample from smearing those.
	for mesh in _meshes(root):
		for i in _surface_count(mesh):
			var mat := mesh.get_active_material(i)
			if mat is BaseMaterial3D:
				(mat as BaseMaterial3D).texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST


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


func _to_world(gx: int, gy: int) -> Vector3:
	var world := get_parent() as World
	if world != null:
		return world.grid_to_world(gx, gy)
	var width := float(_width())
	var height := float(_height())
	return Vector3(gx - width / 2.0 + 0.5, 0.0, -(gy - height / 2.0 + 0.5))


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
