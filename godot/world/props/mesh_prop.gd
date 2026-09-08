@tool
class_name MeshProp
extends Node3D

## Kit models a mesh-backed type may draw from, and the primitive fallback sitting in the
## scene. `height` and `footprint` are per model: a snapped-off column brought up to the
## height of a whole one is not a broken pillar, it is a thin pillar.
##
## Which model a given prop gets is hashed from the room and the prop's id — `Room.hash32`,
## never `randi()`, so three pillars come back the same way on a reconnect.
##
## @tool so editor tooling can call `configure` — a non-tool script is a placeholder in
## the editor and keeps no methods. Nothing here has a side effect and there is no
## `_ready`, so it does nothing in the editor unless something asks it to.

const MESH_PROPS := {
	"PILLAR": [
		{"path": "kaykit_dungeon/pillar", "height": 1.6, "footprint": 0.9},
		{"path": "kaykit_dungeon/pillar_decorated", "height": 1.6, "footprint": 0.9},
		{"path": "kaykit_dungeon/column", "height": 0.73, "footprint": 0.9},
		{"path": "kaykit_halloween/pillar", "height": 1.6, "footprint": 0.9},
	],
	"RUBBLE": [
		{"path": "kaykit_dungeon/rubble_half", "height": 0.5, "footprint": 0.85},
		{"path": "kaykit_dungeon/rubble_large", "height": 0.5, "footprint": 0.85},
		{"path": "kaykit_halloween/bone_A", "height": 0.35, "footprint": 0.7},
		{"path": "kaykit_halloween/bone_B", "height": 0.35, "footprint": 0.7},
		{"path": "kaykit_halloween/bone_C", "height": 0.35, "footprint": 0.7},
		{"path": "kaykit_halloween/ribcage", "height": 0.45, "footprint": 0.75},
		{"path": "kaykit_halloween/skull", "height": 0.4, "footprint": 0.7},
	],
}

@export var kind: String = ""


func configure(prop: Dictionary, room_id: String) -> void:
	var type := kind if not kind.is_empty() else String(prop.get("type", ""))
	var variants: Array = MESH_PROPS.get(type, [])
	if variants.is_empty():
		return
	var idx: int = Room.hash32("%s:prop:%s" % [room_id, String(prop.get("id", ""))]) % variants.size()
	var spec: Dictionary = variants[idx]
	var mesh := make(String(spec["path"]), float(spec["height"]), float(spec["footprint"]))
	if mesh == null:
		return
	for child in get_children():
		remove_child(child)
		child.free()
	mesh.name = "Mesh"
	add_child(mesh)
	set_meta("mesh_path", String(spec["path"]))


static func make(path: String, height: float, footprint: float) -> Node3D:
	var packed: PackedScene = load(res_path(path)) as PackedScene
	if packed == null:
		return null
	var host := Node3D.new()
	var model: Node = packed.instantiate()
	host.add_child(model)
	fit(host, height, footprint)
	shadows(host)
	filter_albedo(host)
	return host


static func res_path(path: String) -> String:
	if path.begins_with("kaykit_dungeon/"):
		return "res://world/kits/kaykit_dungeon/%s.gltf" % path.trim_prefix("kaykit_dungeon/")
	if path.begins_with("kaykit_halloween/"):
		return "res://world/kits/kaykit_halloween/%s.gltf" % path.trim_prefix("kaykit_halloween/")
	if path.begins_with("ruins/"):
		return "res://world/kits/ruins/%s.glb" % path.trim_prefix("ruins/")
	if path.begins_with("dungeon/"):
		return "res://world/kits/dungeon_props/%s.glb" % path.trim_prefix("dungeon/")
	return ""


static func fit(piece: Node3D, height: float, footprint: float) -> void:
	piece.scale = Vector3.ONE
	piece.position = Vector3.ZERO
	var box := aabb_of(piece)
	var s := minf(height / maxf(box.size.y, 1e-6),
		footprint / maxf(maxf(box.size.x, box.size.z), 1e-6))
	piece.scale = Vector3.ONE * s
	piece.position = Vector3(
		-(box.position.x + box.size.x * 0.5) * s,
		-box.position.y * s,
		-(box.position.z + box.size.z * 0.5) * s,
	)


static func filter_albedo(root: Node) -> void:
	for mesh in meshes(root):
		for i in _surface_count(mesh):
			var mat := mesh.get_active_material(i)
			if mat is BaseMaterial3D:
				(mat as BaseMaterial3D).texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR


static func paint(root: Node, colour: Color, roughness: float = 0.85) -> void:
	var mat := StandardMaterial3D.new()
	mat.albedo_color = colour
	mat.roughness = roughness
	mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR
	for mesh in meshes(root):
		mesh.material_override = mat


static func shadows(root: Node) -> void:
	for mesh in meshes(root):
		mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
		mesh.gi_mode = GeometryInstance3D.GI_MODE_DISABLED


static func aabb_of(root: Node3D) -> AABB:
	var boxes: Array[AABB] = []
	_collect_aabb(root, Transform3D.IDENTITY, boxes)
	if boxes.is_empty():
		return AABB()
	var acc := boxes[0]
	for i in range(1, boxes.size()):
		acc = acc.merge(boxes[i])
	return acc


static func meshes(root: Node) -> Array[MeshInstance3D]:
	var found: Array[MeshInstance3D] = []
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is MeshInstance3D:
			found.append(node as MeshInstance3D)
		for child in node.get_children():
			stack.append(child)
	return found


static func _surface_count(mesh: MeshInstance3D) -> int:
	if mesh.mesh:
		return mesh.mesh.get_surface_count()
	return mesh.get_surface_override_material_count()


static func _collect_aabb(node: Node, parent_xf: Transform3D, boxes: Array[AABB]) -> void:
	var xf := parent_xf
	if node is Node3D:
		xf = parent_xf * (node as Node3D).transform
	if node is VisualInstance3D:
		boxes.append(xf * (node as VisualInstance3D).get_aabb())
	for child in node.get_children():
		_collect_aabb(child, xf, boxes)
