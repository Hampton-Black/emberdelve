extends Node3D

## A BRAZIER prop: green flame when lit, grey ash when cold. The server ships an appearance
## (`lit` or `cold`; empty means lit). Room lighting `DARK` douses every brazier that is not
## authored cold — and a cold brazier stays out when the room relights.

const FLAME_COLOUR := Color(0.42, 0.86, 0.44, 1)
const FLAME_ENERGY := 2.8
const COAL_GLOW := 0.85
const ASH := Color(0.24, 0.23, 0.22, 1)

var _appearance := ""


func configure(prop: Dictionary, room_id: String) -> void:
	_appearance = String(prop.get("appearance", ""))
	apply_fires(room_id)


func apply_fires(room_id: String) -> void:
	var view: Dictionary = Table.room_by_id(room_id)
	var lighting := String(view.get("lighting", "TORCHLIT"))
	var burning := _appearance != "cold" and lighting != "DARK"
	_set_burning(burning)


func _set_burning(burning: bool) -> void:
	var flame := get_node_or_null("flame") as OmniLight3D
	if flame != null:
		flame.visible = burning
		if burning:
			flame.light_color = FLAME_COLOUR
			flame.light_energy = FLAME_ENERGY
		else:
			flame.light_energy = 0.0
	var coals := get_node_or_null("Meshes/Coals") as MeshInstance3D
	if coals == null:
		return
	var mat := _coals_material(coals)
	if mat == null:
		return
	if burning:
		var core := FLAME_COLOUR.lightened(0.45)
		mat.albedo_color = core
		mat.emission = core
		mat.emission_enabled = true
		mat.emission_energy_multiplier = COAL_GLOW
	else:
		mat.albedo_color = ASH
		mat.emission_enabled = false
		mat.emission_energy_multiplier = 0.0


func _coals_material(coals: MeshInstance3D) -> StandardMaterial3D:
	var mat := coals.get_surface_override_material(0) as StandardMaterial3D
	if mat == null:
		mat = coals.get_active_material(0) as StandardMaterial3D
	if mat == null:
		return null
	mat = mat.duplicate()
	coals.set_surface_override_material(0, mat)
	return mat
