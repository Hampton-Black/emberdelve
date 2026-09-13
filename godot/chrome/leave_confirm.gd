extends Control

## Inline confirm in the chin when the player clicks the site's way out. Spec §4d.
## LEAVE / STAY. Names whether the reliquary is carried; says nothing about whether that is good.

const INK := Color("d8cfc2")

var _font: SystemFont
var _prompt: Label
var _leave: Button
var _stay: Button


func _ready() -> void:
	mouse_filter = MOUSE_FILTER_IGNORE
	_font = SystemFont.new()
	_font.font_names = PackedStringArray(["Menlo", "Monaco", "Courier New", "monospace"])
	_build()
	Table.leave_confirm_changed.connect(_redraw)
	Table.scene_changed.connect(_redraw)
	_redraw()


func _build() -> void:
	var col := VBoxContainer.new()
	col.name = "Col"
	col.mouse_filter = MOUSE_FILTER_IGNORE
	col.add_theme_constant_override("separation", 8)
	col.set_anchors_and_offsets_preset(PRESET_FULL_RECT)
	add_child(col)

	_prompt = Label.new()
	_prompt.name = "Prompt"
	_prompt.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_prompt.add_theme_font_override("font", _font)
	_prompt.add_theme_font_size_override("font_size", 12)
	_prompt.add_theme_color_override("font_color", INK)
	col.add_child(_prompt)

	var row := HBoxContainer.new()
	row.name = "Buttons"
	row.mouse_filter = MOUSE_FILTER_IGNORE
	row.add_theme_constant_override("separation", 8)
	col.add_child(row)

	_leave = _verb("Leave", "LEAVE")
	_leave.pressed.connect(func() -> void: Table.confirm_leave())
	row.add_child(_leave)

	_stay = _verb("Stay", "STAY")
	_stay.pressed.connect(func() -> void: Table.stay())
	row.add_child(_stay)


func _verb(node_name: String, caption: String) -> Button:
	var btn := Button.new()
	btn.name = node_name
	btn.text = caption
	btn.focus_mode = Control.FOCUS_NONE
	btn.mouse_filter = Control.MOUSE_FILTER_STOP
	btn.size_flags_horizontal = SIZE_EXPAND_FILL
	btn.add_theme_font_override("font", _font)
	btn.add_theme_font_size_override("font_size", 11)
	btn.add_theme_color_override("font_color", INK)
	btn.add_theme_color_override("font_hover_color", Color("e8dcc8"))
	btn.add_theme_stylebox_override("normal", _btn_style(Color("1b1a23"), Color("3a3444")))
	btn.add_theme_stylebox_override("hover", _btn_style(Color("2a2833"), Color("5a5464")))
	btn.add_theme_stylebox_override("pressed", _btn_style(Color("1b1a23"), Color("3a3444")))
	return btn


func _btn_style(bg: Color, border: Color) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = bg
	style.border_color = border
	style.set_border_width_all(1)
	style.set_corner_radius_all(3)
	style.content_margin_left = 10.0
	style.content_margin_right = 10.0
	style.content_margin_top = 6.0
	style.content_margin_bottom = 6.0
	return style


func _redraw() -> void:
	if Table.leave_confirm.is_empty():
		visible = false
		custom_minimum_size = Vector2.ZERO
		return
	visible = true
	custom_minimum_size = Vector2(196, 0)
	var holding := bool(Table.leave_confirm.get("holding", false))
	_prompt.text = "Leave the site, with the reliquary?" if holding \
			else "Leave the site, without the reliquary?"
