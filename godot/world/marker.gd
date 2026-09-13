class_name MarkerGlyph
extends Node3D

## Generic interaction glyph. One mesh for every tag; the tag tints it. Never a unique
## kit per SCORCH/SIGIL/TRACKS — the server ships the tag, this paints it.

const TINTS := {
	"SCORCH": Color8(0xc4, 0x5c, 0x3a),
	"SIGIL": Color8(0x6b, 0x7b, 0xd4),
	"TRACKS": Color8(0x8a, 0x6b, 0x3c),
}

const RADIUS := 0.22
const HEIGHT := 0.05


func configure(marker: Dictionary) -> void:
	name = String(marker.get("id", name))
	var tag := String(marker.get("tag", "SCORCH"))
	var tint: Color = TINTS.get(tag, TINTS["SCORCH"])
	_ensure_mesh(tint)


func _ensure_mesh(tint: Color) -> void:
	if get_node_or_null("Mesh") != null:
		return
	var mesh := CylinderMesh.new()
	mesh.top_radius = RADIUS
	mesh.bottom_radius = RADIUS
	mesh.height = HEIGHT
	mesh.radial_segments = 12
	var inst := MeshInstance3D.new()
	inst.name = "Mesh"
	inst.mesh = mesh
	inst.position = Vector3(0.0, HEIGHT * 0.5, 0.0)
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.albedo_color = tint
	mat.emission_enabled = true
	mat.emission = tint
	mat.emission_energy_multiplier = 0.35
	inst.material_override = mat
	inst.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(inst)
