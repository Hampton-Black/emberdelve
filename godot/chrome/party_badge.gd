extends Control

## Circular party plate over the playfield. Reads Table; holds no facts (invariant #3).
## AC is a local kind table — EntityView does not ship it.

const YOURS := Color("7fae56")
const AC := {
	"fighter": 16,
	"goblin": 15,
}


func ac_for(kind: String) -> int:
	return int(AC.get(kind, 0))


func hp_fraction(hp: int, max_hp: int) -> float:
	return clampf(float(hp) / float(maxi(max_hp, 1)), 0.0, 1.0)


func _ready() -> void:
	mouse_filter = MOUSE_FILTER_IGNORE
	_build()
	Table.scene_changed.connect(_refresh)
	_refresh()


func _party_member() -> Dictionary:
	for e in Table.scene.get("entities", []):
		if bool(e.get("isPlayerControlled", false)):
			return e
	return {}


func _ignore(node: Control) -> void:
	node.mouse_filter = MOUSE_FILTER_IGNORE


func _build() -> void:
	var col := VBoxContainer.new()
	col.name = "Col"
	_ignore(col)
	col.add_theme_constant_override("separation", 6)
	add_child(col)

	var portrait := Panel.new()
	portrait.name = "Portrait"
	_ignore(portrait)
	portrait.custom_minimum_size = Vector2(64, 64)
	var ring := StyleBoxFlat.new()
	ring.bg_color = Color(0.14, 0.13, 0.12)
	ring.set_corner_radius_all(32)
	ring.set_border_width_all(3)
	ring.border_color = Color("c9b083")
	portrait.add_theme_stylebox_override("panel", ring)
	col.add_child(portrait)

	var name_l := Label.new()
	name_l.name = "Name"
	_ignore(name_l)
	name_l.add_theme_color_override("font_color", Color("c9b083"))
	name_l.add_theme_font_size_override("font_size", 12)
	col.add_child(name_l)

	var track := ColorRect.new()
	track.name = "HpBack"
	_ignore(track)
	track.color = Color(0.08, 0.08, 0.09, 1)
	track.custom_minimum_size = Vector2(72, 8)
	col.add_child(track)

	var fill := ColorRect.new()
	fill.name = "HpFill"
	_ignore(fill)
	fill.color = YOURS
	fill.set_anchors_and_offsets_preset(Control.PRESET_LEFT_WIDE)
	fill.anchor_right = 1.0
	fill.offset_right = 0.0
	track.add_child(fill)

	var hp := Label.new()
	hp.name = "Hp"
	_ignore(hp)
	hp.add_theme_color_override("font_color", Color("d8cfc2"))
	hp.add_theme_font_size_override("font_size", 11)
	col.add_child(hp)

	var ac := Label.new()
	ac.name = "Ac"
	_ignore(ac)
	ac.add_theme_color_override("font_color", Color("cfc4ae"))
	ac.add_theme_font_size_override("font_size", 11)
	col.add_child(ac)


func _refresh() -> void:
	var member := _party_member()
	visible = not member.is_empty()
	if member.is_empty():
		return
	var name_l: Label = $Col/Name
	name_l.text = String(member.get("name", ""))
	var hp := int(member.get("hp", 0))
	var max_hp := int(member.get("maxHp", 1))
	var hp_l: Label = $Col/Hp
	hp_l.text = "%d/%d" % [hp, max_hp]
	var ac_l: Label = $Col/Ac
	ac_l.text = "AC %d" % ac_for(String(member.get("kind", "")))
	var fill: ColorRect = $Col/HpBack/HpFill
	fill.color = YOURS
	fill.anchor_right = hp_fraction(hp, max_hp)
	fill.offset_right = 0.0
