extends PanelContainer

## The delve's last page: one sentence, a ledger, DESCEND AGAIN. Spec §9.
##
## Derived from the ending report the server ships, never from hit points. A dead
## token with no report is a body on the board, not a crash and not this page.

const IMPACT_SECONDS := 0.22
const DEATH_A_SECONDS := 0.8
const ASH := Color("8f8478")
const DAYLIGHT := Color("e6d4b0")
const INK := Color("d8cfc2")
const LEDGER := Color("a89c8c")

var _font: SystemFont
var _sentence: Label
var _ledger: Label
var _restart: Button
var _gen := 0


func delay_for(report: Dictionary) -> float:
	if String(report.get("ending", "")) == "PARTY_LOST":
		return IMPACT_SECONDS + DEATH_A_SECONDS
	return 0.0


func _ready() -> void:
	visible = false
	mouse_filter = MOUSE_FILTER_STOP
	size_flags_horizontal = 0
	size_flags_vertical = 0
	_font = SystemFont.new()
	_font.font_names = PackedStringArray(["Menlo", "Monaco", "Courier New", "monospace"])
	_build()
	Table.scene_changed.connect(_apply)
	Table.started_changed.connect(_apply)
	_apply()


func _build() -> void:
	mouse_filter = MOUSE_FILTER_STOP
	var style := StyleBoxFlat.new()
	style.bg_color = Color(0.07, 0.065, 0.06, 0.94)
	style.border_color = Color(0.28, 0.24, 0.2, 1)
	style.set_border_width_all(1)
	style.set_corner_radius_all(3)
	style.content_margin_left = 16.0
	style.content_margin_right = 16.0
	style.content_margin_top = 14.0
	style.content_margin_bottom = 14.0
	add_theme_stylebox_override("panel", style)

	var col := VBoxContainer.new()
	col.name = "Col"
	col.add_theme_constant_override("separation", 12)
	add_child(col)

	_sentence = Label.new()
	_sentence.name = "Sentence"
	_sentence.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_sentence.custom_minimum_size.x = 288.0
	_sentence.add_theme_font_override("font", _font)
	_sentence.add_theme_font_size_override("font_size", 16)
	col.add_child(_sentence)

	_ledger = Label.new()
	_ledger.name = "Ledger"
	_ledger.autowrap_mode = TextServer.AUTOWRAP_OFF
	_ledger.add_theme_font_override("font", _font)
	_ledger.add_theme_font_size_override("font_size", 11)
	_ledger.add_theme_color_override("font_color", LEDGER)
	col.add_child(_ledger)

	_restart = Button.new()
	_restart.name = "Restart"
	_restart.text = "DESCEND AGAIN"
	_restart.focus_mode = Control.FOCUS_NONE
	_restart.add_theme_font_override("font", _font)
	_restart.add_theme_font_size_override("font_size", 11)
	_restart.add_theme_color_override("font_color", INK)
	_restart.add_theme_color_override("font_hover_color", Color("e8dcc8"))
	_restart.add_theme_stylebox_override("normal", _btn_style(Color("1b1a23"), Color("3a3444")))
	_restart.add_theme_stylebox_override("hover", _btn_style(Color("2a2833"), Color("5a5464")))
	_restart.add_theme_stylebox_override("pressed", _btn_style(Color("1b1a23"), Color("3a3444")))
	_restart.pressed.connect(_descend)
	col.add_child(_restart)


func _btn_style(bg: Color, border: Color) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = bg
	style.border_color = border
	style.set_border_width_all(1)
	style.set_corner_radius_all(3)
	style.content_margin_left = 14.0
	style.content_margin_right = 14.0
	style.content_margin_top = 8.0
	style.content_margin_bottom = 8.0
	return style


func _report() -> Dictionary:
	var ending: Variant = Table.scene.get("ending")
	if ending is Dictionary:
		return ending
	return {}


func _apply() -> void:
	_gen += 1
	var mine := _gen
	var report := _report()
	if report.is_empty() or not Table.started:
		visible = false
		modulate.a = 1.0
		return
	_fill(report)
	var wait := delay_for(report)
	if wait <= 0.0:
		_reveal(false)
		return
	visible = false
	await get_tree().create_timer(wait).timeout
	if mine != _gen:
		return
	_reveal(true)


func _reveal(fade: bool) -> void:
	visible = true
	if fade:
		modulate.a = 0.0
		var tween := create_tween()
		tween.tween_property(self, "modulate:a", 1.0, 0.28)
	else:
		modulate.a = 1.0


func _fill(report: Dictionary) -> void:
	var names := _join_names(report.get("partyNames", []))
	var kind := String(report.get("ending", ""))
	var room := _room_in_the(String(report.get("roomName", "")))
	var objective := String(report.get("objectiveName", "reliquary"))
	match kind:
		"PARTY_LOST":
			_sentence.text = "%s fell in the %s." % [names, room]
			_sentence.add_theme_color_override("font_color", ASH)
		"EXTRACTED_WITH_OBJECTIVE":
			_sentence.text = "%s came out with the %s." % [names, objective]
			_sentence.add_theme_color_override("font_color", DAYLIGHT)
		_:
			_sentence.text = "%s came out." % names
			_sentence.add_theme_color_override("font_color", DAYLIGHT)
	_ledger.text = _ledger_text(report)
	custom_minimum_size = Vector2(320.0, 0.0)
	_shrink_to_content()
	call_deferred("_shrink_to_content")


func _shrink_to_content() -> void:
	# Labels need a frame of width before min-size is honest; without this the
	# Overlay's layout leaves a tall empty plate (offset_bottom 600 on a 1920² root).
	custom_minimum_size = Vector2(320.0, 0.0)
	var height := get_combined_minimum_size().y
	if absf(size.y - height) <= 1.0 and absf(offset_bottom - (48.0 + height)) <= 1.0:
		return
	reset_size()
	offset_top = 48.0
	offset_bottom = 48.0 + height
	offset_right = -20.0
	offset_left = -340.0


func _join_names(raw: Variant) -> String:
	var parts: PackedStringArray = PackedStringArray()
	if raw is Array:
		for item in raw:
			parts.append(String(item))
	if parts.is_empty():
		return ""
	return " and ".join(parts)


func _room_in_the(room_name: String) -> String:
	var titled := room_name.strip_edges()
	if titled.to_lower().begins_with("the "):
		return titled.substr(4)
	return titled


func _ledger_text(report: Dictionary) -> String:
	var lines: PackedStringArray = PackedStringArray()
	lines.append("Rooms    %d / %d" % [
		int(report.get("roomsEntered", 0)), int(report.get("roomsInSite", 0))])
	lines.append("Potions  %d / %d" % [
		int(report.get("potionsUsed", 0)), int(report.get("potionsBrought", 0))])
	lines.append("Torches  %d / %d" % [
		int(report.get("torchesUsed", 0)), int(report.get("torchesBrought", 0))])
	lines.append("Fights   %d" % int(report.get("fights", 0)))
	var fate := String(report.get("objective", ""))
	var fate_line := "left where it lay"
	match fate:
		"CARRIED_OUT":
			fate_line = "carried out"
		"FELL_WITH_HIM":
			fate_line = "fell with him"
	lines.append("%s  %s" % [String(report.get("objectiveName", "reliquary")), fate_line])
	var hurt: Variant = report.get("hurt", null)
	if hurt != null and String(hurt) != "":
		lines.append(String(hurt))
	return "\n".join(lines)


func _descend() -> void:
	Clock.silence_now()
	Table.expect_restart()
	Net.restart()
