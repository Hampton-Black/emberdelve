extends Control

## Combat chrome: who is up, what is left of their turn, and how to end it.
##
## Reads the server's CombatView and displays it. Nothing here decides whose turn it is or
## whether a button should work — movementRemaining and actionAvailable arrive already decided.
##
## Draws from [member Table.combat_beat], never from scene.combat, because the bar outlives
## the fight and has to keep names and initiative totals while it dissolves.
##
## Timed from opened_at rather than from a frame-zero clock, so a mid-fight reconnect
## backdates past the ceremony and the bar appears already assembled. Chip delays are
## [member Sfx.CHIP_DELAY_MS] / [member Sfx.CHIP_STAGGER_MS] — the same numbers the sting
## uses, so the two rhythms interleave instead of colliding.
##
## Chips live on this node (over the playfield). Attack / Move / End Turn live on
## %CombatVerbs in the chin when that host exists; isolation tests host them here.

const BAR_IN_DELAY_MS := 120
const BAR_IN_MS := 200
const CHIP_IN_MS := 260
const CHROME_DELAY_MS := 760
const CHROME_IN_MS := 320

const INK := Color("d8cfc2")
const YOURS := Color("7fae56")
const THEIRS := Color("b83a30")

var _font: SystemFont
var _row: HBoxContainer
var _round: Label
var _track: HBoxContainer
var _attack: Button
var _move: Button
var _end: Button
var _controls: VBoxContainer


func chip_delay(which: int) -> int:
	return Sfx.CHIP_DELAY_MS + which * Sfx.CHIP_STAGGER_MS


## 0 before [param delay_ms], 1 after delay+duration. A backdated [param opened_at] jumps
## this to 1, which is how a reconnect skips the drums.
func arrival(now_ms: int, opened_at: int, delay_ms: int, duration_ms: int) -> float:
	var elapsed := now_ms - opened_at
	if elapsed <= delay_ms:
		return 0.0
	if elapsed >= delay_ms + duration_ms:
		return 1.0
	return float(elapsed - delay_ms) / float(maxi(duration_ms, 1))


func allegiance_color(is_player: bool) -> Color:
	return YOURS if is_player else THEIRS


func hp_fraction(hp: int, max_hp: int) -> float:
	return clampf(float(hp) / float(maxi(max_hp, 1)), 0.0, 1.0)


func _verbs_host() -> Control:
	var host := get_node_or_null("%CombatVerbs") as Control
	return host if host != null else self


func _ready() -> void:
	mouse_filter = MOUSE_FILTER_IGNORE
	_font = SystemFont.new()
	_font.font_names = PackedStringArray(["Menlo", "Monaco", "Courier New", "monospace"])
	_build()
	Table.combat_changed.connect(_redraw)
	Table.scene_changed.connect(_redraw)
	Table.transcript_changed.connect(_redraw)
	Table.started_changed.connect(_redraw)
	Table.errored.connect(func(_m: String) -> void: _redraw())
	_redraw()


func _build() -> void:
	_row = HBoxContainer.new()
	_row.name = "Row"
	_row.mouse_filter = MOUSE_FILTER_IGNORE
	_row.add_theme_constant_override("separation", 9)
	_row.set_anchors_and_offsets_preset(PRESET_FULL_RECT)
	add_child(_row)

	_round = _label("Round", 10)
	_round.modulate = Color(INK, 0.45)
	_row.add_child(_round)

	_track = HBoxContainer.new()
	_track.name = "Track"
	_track.mouse_filter = MOUSE_FILTER_IGNORE
	_track.add_theme_constant_override("separation", 5)
	_track.size_flags_horizontal = SIZE_EXPAND_FILL
	_track.alignment = BoxContainer.ALIGNMENT_CENTER
	_row.add_child(_track)

	_controls = VBoxContainer.new()
	_controls.name = "Controls"
	_controls.mouse_filter = MOUSE_FILTER_IGNORE
	_controls.add_theme_constant_override("separation", 8)
	_controls.size_flags_horizontal = SIZE_EXPAND_FILL
	_controls.size_flags_vertical = SIZE_EXPAND_FILL
	_verbs_host().add_child(_controls)

	_attack = _verb("Attack", "ATTACK")
	_controls.add_child(_attack)

	_move = _verb("Move", "MOVE")
	_controls.add_child(_move)

	_end = _verb("EndTurn", "END TURN")
	_end.add_theme_color_override("font_color", Color("e8c7b8"))
	_end.add_theme_color_override("font_hover_color", Color("e8c7b8"))
	_end.add_theme_color_override("font_disabled_color", Color("e8c7b8"))
	_end.add_theme_stylebox_override("normal", _btn_style(Color("3b2320"), Color("7a4034")))
	_end.add_theme_stylebox_override("hover", _btn_style(Color("4a2c28"), Color("7a4034")))
	_end.add_theme_stylebox_override("pressed", _btn_style(Color("3b2320"), Color("7a4034")))
	_end.add_theme_stylebox_override("disabled", _btn_style(Color("3b2320"), Color("7a4034")))
	_end.pressed.connect(_end_turn)
	_controls.add_child(_end)


func _verb(node_name: String, caption: String) -> Button:
	var btn := Button.new()
	btn.name = node_name
	btn.text = caption
	btn.focus_mode = Control.FOCUS_NONE
	btn.mouse_filter = MOUSE_FILTER_STOP
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


func _label(node_name: String, size_px: int) -> Label:
	var label := Label.new()
	label.name = node_name
	label.add_theme_font_override("font", _font)
	label.add_theme_font_size_override("font_size", size_px)
	label.add_theme_color_override("font_color", INK)
	label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	label.mouse_filter = MOUSE_FILTER_IGNORE
	return label


func _btn_style(bg: Color, border: Color) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = bg
	style.border_color = border
	style.set_border_width_all(1)
	style.set_corner_radius_all(3)
	style.content_margin_left = 13.0
	style.content_margin_right = 13.0
	style.content_margin_top = 6.0
	style.content_margin_bottom = 6.0
	return style


func _chip_style(active: bool) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	if active:
		style.bg_color = Color("3b3320")
		style.border_color = Color("7a6634")
	else:
		style.bg_color = Color("1b1a23")
		style.border_color = Color("2e2b38")
	style.set_border_width_all(1)
	style.set_corner_radius_all(3)
	style.content_margin_left = 8.0
	style.content_margin_right = 8.0
	style.content_margin_top = 3.0
	style.content_margin_bottom = 4.0
	return style


func _redraw() -> void:
	var beat = Table.combat_beat
	var verbs := _verbs_host()
	if beat == null or Table.scene.get("ending") is Dictionary:
		visible = false
		if verbs != self:
			verbs.visible = false
		mouse_filter = MOUSE_FILTER_IGNORE
		set_process(false)
		return
	visible = true
	mouse_filter = MOUSE_FILTER_IGNORE
	_sync(beat)
	_paint()
	set_process(not _settled())


func _settled() -> bool:
	var beat = Table.combat_beat
	if beat == null:
		return true
	var now := Time.get_ticks_msec()
	if beat["closing_at"] != null:
		return arrival(now, int(beat["closing_at"]), 0, Table.COMBAT_CLOSE_MS) >= 1.0
	return arrival(now, int(beat["opened_at"]), CHROME_DELAY_MS, CHROME_IN_MS) >= 1.0


func _process(_delta: float) -> void:
	_paint()
	if _settled():
		set_process(false)


func _sync(beat: Dictionary) -> void:
	var combat: Dictionary = beat["view"]
	var closing: bool = beat["closing_at"] != null
	_round.text = "ROUND %s" % str(int(combat.get("round", 1)))

	var wanted: Array[String] = []
	var order_i := 0
	for combatant in combat.get("order", []):
		var entity_id := String(combatant["entityId"])
		wanted.append(entity_id)
		var chip := _track.get_node_or_null("Chip_%s" % entity_id) as Control
		if chip == null:
			chip = _make_chip(combatant)
			_track.add_child(chip)
			_track.move_child(chip, order_i)
		_fill_chip(chip, combatant, String(combat.get("activeId", "")), closing)
		order_i += 1
	for child in _track.get_children():
		var entity_id := String(child.name).trim_prefix("Chip_")
		if not wanted.has(entity_id):
			child.queue_free()

	var active := Table.entity(String(combat.get("activeId", "")))
	var yours := (not active.is_empty() and bool(active["isPlayerControlled"])) and not closing
	_attack.visible = yours
	_move.visible = yours
	_end.visible = yours
	var verbs := _verbs_host()
	if verbs != self:
		verbs.visible = yours
	if yours:
		var squares := int(combat.get("movementRemaining", 0))
		_move.modulate.a = 1.0 if squares > 0 else 0.32
		var attack_ready := bool(combat.get("actionAvailable", false))
		_attack.modulate.a = 1.0 if attack_ready else 0.32
		_end.disabled = Table.awaiting_dm
		_end.text = "…" if Table.awaiting_dm else "END TURN"
		_end.modulate.a = 0.35 if Table.awaiting_dm else 1.0


func _make_chip(combatant: Dictionary) -> Control:
	var panel := PanelContainer.new()
	panel.name = "Chip_%s" % String(combatant["entityId"])
	panel.mouse_filter = MOUSE_FILTER_IGNORE
	panel.add_theme_stylebox_override("panel", _chip_style(false))

	var col := VBoxContainer.new()
	col.mouse_filter = MOUSE_FILTER_IGNORE
	col.add_theme_constant_override("separation", 3)
	panel.add_child(col)

	var row := HBoxContainer.new()
	row.mouse_filter = MOUSE_FILTER_IGNORE
	row.add_theme_constant_override("separation", 6)
	col.add_child(row)

	var name_l := _label("Name", 10)
	name_l.text = String(combatant["name"])
	row.add_child(name_l)

	var init_l := _label("Initiative", 9)
	init_l.text = str(int(combatant["initiative"]))
	init_l.modulate = Color(INK, 0.5)
	row.add_child(init_l)

	var hp_back := ColorRect.new()
	hp_back.name = "HpBack"
	hp_back.color = Color("14131a")
	hp_back.custom_minimum_size = Vector2(36, 3)
	hp_back.mouse_filter = MOUSE_FILTER_IGNORE
	col.add_child(hp_back)

	var hp_fill := ColorRect.new()
	hp_fill.name = "HpFill"
	hp_fill.mouse_filter = MOUSE_FILTER_IGNORE
	hp_fill.set_anchors_and_offsets_preset(PRESET_LEFT_WIDE)
	hp_back.add_child(hp_fill)
	return panel


func _fill_chip(chip: Control, combatant: Dictionary, active_id: String, _closing: bool) -> void:
	var entity_id := String(combatant["entityId"])
	var body := Table.entity(entity_id)
	var down := not body.is_empty() and int(body["hp"]) <= 0
	var is_up := entity_id == active_id
	chip.add_theme_stylebox_override("panel", _chip_style(is_up))
	var name_l: Label = chip.find_child("Name", true, false)
	if name_l != null:
		name_l.text = String(combatant["name"])
		name_l.add_theme_color_override("font_color", Color("f0d67a") if is_up else INK)
	var init_l: Label = chip.find_child("Initiative", true, false)
	if init_l != null:
		init_l.text = str(int(combatant["initiative"]))
	chip.set_meta("resting", 0.3 if down else (1.0 if is_up else 0.62))
	chip.set_meta("down", down)

	var yours := bool(combatant.get("isPlayerControlled", false))
	if not body.is_empty():
		yours = bool(body["isPlayerControlled"])
	var fill: ColorRect = chip.find_child("HpFill", true, false)
	var back: ColorRect = chip.find_child("HpBack", true, false)
	if fill != null and back != null:
		var hp := int(body.get("hp", 1))
		var max_hp := int(body.get("maxHp", 1))
		var fraction := hp_fraction(hp, max_hp)
		fill.color = allegiance_color(yours)
		fill.anchor_right = fraction
		fill.offset_right = 0.0
		back.visible = not down


func _paint() -> void:
	var beat = Table.combat_beat
	if beat == null:
		return
	var now := Time.get_ticks_msec()
	var bar_t: float
	var chrome_t: float
	var opened := int(beat["opened_at"])
	if beat["closing_at"] != null:
		bar_t = 1.0 - arrival(now, int(beat["closing_at"]), 0, Table.COMBAT_CLOSE_MS)
		chrome_t = 1.0
	else:
		bar_t = arrival(now, opened, BAR_IN_DELAY_MS, BAR_IN_MS)
		chrome_t = arrival(now, opened, CHROME_DELAY_MS, CHROME_IN_MS)

	modulate.a = bar_t
	_round.modulate.a = 0.45 * chrome_t
	_controls.modulate.a = chrome_t
	var verbs := _verbs_host()
	if verbs != self:
		verbs.modulate.a = bar_t

	var i := 0
	for combatant in beat["view"].get("order", []):
		var chip := _track.get_node_or_null("Chip_%s" % String(combatant["entityId"])) as Control
		if chip != null:
			var land := 1.0 if beat["closing_at"] != null \
				else arrival(now, opened, chip_delay(i), CHIP_IN_MS)
			chip.modulate.a = land * float(chip.get_meta("resting", 1.0))
		i += 1


func _end_turn() -> void:
	if Table.awaiting_dm:
		return
	var beat = Table.combat_beat
	if beat == null or beat["closing_at"] != null:
		return
	Net.end_turn(String(beat["view"]["activeId"]))
