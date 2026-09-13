extends Control

## Exploration chrome: potion, torch, and rest buttons with counts.
##
## Reads SceneState counts, canSpendTorch, and canRest. Nothing here decides legality — the
## server refuses anyway; grey states are hints only.

const INK := Color("d8cfc2")

var _font: SystemFont
var _potion: Button
var _torch: Button
var _rest: Button


func _ready() -> void:
	mouse_filter = MOUSE_FILTER_IGNORE
	_font = SystemFont.new()
	_font.font_names = PackedStringArray(["Menlo", "Monaco", "Courier New", "monospace"])
	_build()
	Table.scene_changed.connect(_redraw)
	Table.started_changed.connect(_redraw)
	Table.combat_changed.connect(_redraw)
	Table.transcript_changed.connect(_redraw)
	Table.errored.connect(func(_m: String) -> void: _redraw())
	Table.leave_confirm_changed.connect(_redraw)
	_redraw()


func _build() -> void:
	var row := HBoxContainer.new()
	row.name = "Row"
	row.mouse_filter = MOUSE_FILTER_IGNORE
	row.add_theme_constant_override("separation", 8)
	row.set_anchors_and_offsets_preset(PRESET_FULL_RECT)
	add_child(row)

	_potion = _verb("Potion", "POTION")
	_potion.pressed.connect(func() -> void: _use("potion"))
	row.add_child(_potion)

	_torch = _verb("Torch", "TORCH")
	_torch.pressed.connect(func() -> void: _use("torch"))
	row.add_child(_torch)

	_rest = _verb("Rest", "REST")
	_rest.pressed.connect(_rest_pressed)
	row.add_child(_rest)


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
	btn.add_theme_color_override("font_disabled_color", INK)
	btn.add_theme_stylebox_override("normal", _btn_style(Color("1b1a23"), Color("3a3444")))
	btn.add_theme_stylebox_override("hover", _btn_style(Color("2a2833"), Color("5a5464")))
	btn.add_theme_stylebox_override("pressed", _btn_style(Color("1b1a23"), Color("3a3444")))
	btn.add_theme_stylebox_override("disabled", _btn_style(Color("1b1a23"), Color("3a3444")))
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
	var fighting := Table.combat_beat != null
	if Table.scene.is_empty() or Table.mode != "EXPLORATION" or fighting \
			or not Table.leave_confirm.is_empty() \
			or Table.scene.get("ending") is Dictionary:
		visible = false
		custom_minimum_size = Vector2.ZERO
		return
	visible = true
	custom_minimum_size = Vector2(196, 0)
	var potions := int(Table.scene.get("potions", 0))
	var torches := int(Table.scene.get("torches", 0))
	var can_torch := bool(Table.scene.get("canSpendTorch", false))
	var can_rest := bool(Table.scene.get("canRest", false))
	var waiting := Table.awaiting_dm

	_potion.text = "POTION %d" % potions
	_potion.disabled = waiting or potions <= 0
	_potion.modulate.a = 0.35 if _potion.disabled else 1.0

	_torch.text = "TORCH %d" % torches
	_torch.disabled = waiting or not can_torch
	_torch.modulate.a = 0.35 if _torch.disabled else 1.0

	_rest.disabled = waiting or not can_rest
	_rest.modulate.a = 0.35 if _rest.disabled else 1.0


func _use(item: String) -> void:
	if Table.awaiting_dm:
		return
	var actor := _actor_id()
	if actor.is_empty():
		return
	Net.use_item(actor, item)


func _rest_pressed() -> void:
	if Table.awaiting_dm:
		return
	var actor := _actor_id()
	if actor.is_empty():
		return
	Net.rest(actor)


func _actor_id() -> String:
	for e in Table.scene.get("entities", []):
		if bool(e.get("isPlayerControlled", false)):
			return String(e["id"])
	return "fighter"
