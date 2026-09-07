extends Control

## The dice, drawn. Every number it draws comes from Tumble; this file decides nothing.
##
## The stakes are drawn from the first frame — "ATHLETICS CHECK  DC 20" is legible while the die
## is still in the air, and that is most of the tension.
##
## Laid out in Tumble's 232x76 design space. In the chrome it fills the chin's right half
## and scales to that Control; isolation tests still pin it to the parent's bottom-right.

# Inset from the parent's bottom-right when the tray is tested in isolation. In chrome
# the chin's row sizes it; this pad is unused there.
const BOTTOM_PAD := 22.0
const READOUT_X := 98.0

const INK := {
	"plate": Color(10.0 / 255.0, 9.0 / 255.0, 16.0 / 255.0, 0.82),
	"edge": Color("34313d"),
	"text": Color("d8cfc2"),
	"dim": Color("8d8375"),
	"body": Color("e3d8c4"),
	"body_edge": Color("26232e"),
	"facet": Color("cbbea6"),
	"number": Color("1a1820"),
	"discard_body": Color("4f4a44"),
	"discard_number": Color("2a2732"),
}

## d20s read as a hexagon with the top face cut into it; other dice get a plain polygon.
const OUTLINE := {
	4: {"corners": 3, "tilt": -PI / 2.0},
	6: {"corners": 4, "tilt": PI / 4.0},
	8: {"corners": 4, "tilt": 0.0},
	10: {"corners": 6, "tilt": 0.0},
	12: {"corners": 5, "tilt": -PI / 2.0},
	20: {"corners": 6, "tilt": 0.0},
}

var _font: SystemFont
var _font_bold: SystemFont
var _cues := {}
var _showing_at := -1


func _ready() -> void:
	mouse_filter = MOUSE_FILTER_IGNORE
	clip_contents = true
	_font = SystemFont.new()
	_font.font_names = PackedStringArray(["Menlo", "Monaco", "Courier New", "monospace"])
	_font_bold = SystemFont.new()
	_font_bold.font_names = _font.font_names
	_font_bold.font_weight = 700
	Table.roll_thrown.connect(func(_r) -> void: set_process(true))
	set_process(false)
	_fit()
	var parent_ctrl := get_parent() as Control
	if parent_ctrl != null:
		parent_ctrl.resized.connect(_fit)


func _fit() -> void:
	# A container child (the chin's right half) is sized by the row. Pinning
	# bottom-right would fight that layout and throw on top of the log again.
	if get_parent() is Container:
		return
	var max_w := Tumble.TRAY_WIDTH * Tumble.DISPLAY_SCALE
	var parent_ctrl := get_parent() as Control
	if parent_ctrl != null and parent_ctrl.size.x > 1.0:
		max_w = minf(max_w, parent_ctrl.size.x * 0.94)
	var h := Tumble.TRAY_HEIGHT * (max_w / Tumble.TRAY_WIDTH)
	custom_minimum_size = Vector2(max_w, h)
	set_anchors_preset(PRESET_BOTTOM_RIGHT)
	offset_left = -BOTTOM_PAD - max_w
	offset_right = -BOTTOM_PAD
	offset_top = -BOTTOM_PAD - h
	offset_bottom = -BOTTOM_PAD


func _process(_delta: float) -> void:
	if Table.active_roll == null:
		_idle()
		return

	var result: Dictionary = Table.active_roll["result"]
	var started_at := int(Table.active_roll["started_at"])
	if started_at != _showing_at:
		_showing_at = started_at
		_cues.clear()

	var elapsed := Time.get_ticks_msec() - started_at
	var visual := Tumble.sample(result, elapsed, dismiss_ms(result, started_at))
	# sample() reports finished at elapsed 0 because opacity is 0. That is the start of a
	# throw, not the end.
	if visual["finished"] and elapsed > 0:
		Table.active_roll = null
		_idle()
		return

	var text := Tumble.caption(result)
	_fire_cues(elapsed, visual, text["tone"] == "crit" or text["tone"] == "fumble")
	queue_redraw()


func _idle() -> void:
	_showing_at = -1
	_cues.clear()
	set_process(false)
	queue_redraw()


## Milliseconds from throw to the start of the fade.
##
## Out of combat a roll is a question the DM is about to answer, so the tray waits for the
## answer. In a fight the blow is the answer, so the swing dismisses the tray exactly as
## narration does. COMBAT_HOLD_MS is the backstop for rolls with no swing — and for throws
## that have no narration coming at all (debug d20, a roll after the DM has finished).
## HOLD_MS is only the cover for a turn still waiting on the first word.
## An explicit dismiss still cannot leave before the total has been readable for a beat —
## DiceTray.tsx floors at [method Tumble.reveal_at] + 250, never a min against the backstop.
func dismiss_ms(result: Dictionary, started_at: int) -> int:
	var covering_narration := Table.mode != "COMBAT" and Table.awaiting_dm
	var backstop := Tumble.HOLD_MS if covering_narration else Tumble.COMBAT_HOLD_MS
	if Table.dice_dismiss_at == null:
		return backstop
	var faces: Array = result["faces"]
	return maxi(int(Table.dice_dismiss_at) - started_at, Tumble.reveal_at(faces.size()) + 250)


func _draw() -> void:
	if Table.active_roll == null or _font == null:
		return

	var result: Dictionary = Table.active_roll["result"]
	var started_at := int(Table.active_roll["started_at"])
	var elapsed := Time.get_ticks_msec() - started_at
	var visual := Tumble.sample(result, elapsed, dismiss_ms(result, started_at))
	if visual["finished"]:
		return

	var text := Tumble.caption(result)
	var s := Tumble.DISPLAY_SCALE
	if size.x > 1.0:
		s = minf(s, size.x / Tumble.TRAY_WIDTH)
	if size.y > 1.0:
		s = minf(s, size.y / Tumble.TRAY_HEIGHT)
	var plate := Vector2(Tumble.TRAY_WIDTH * s, Tumble.TRAY_HEIGHT * s)
	var origin := Vector2((size.x - plate.x) * 0.5, (size.y - plate.y) * 0.5)
	_paint(visual, text, s, origin)


func _paint(visual: Dictionary, text: Dictionary, s: float, origin: Vector2) -> void:
	var opacity: float = visual["opacity"]
	var reveal: float = visual["reveal"]
	var emphatic: bool = text["tone"] == "crit" or text["tone"] == "fumble"

	var plate: Color = INK["plate"]
	plate.a *= opacity
	draw_rect(Rect2(origin, Vector2(Tumble.TRAY_WIDTH * s, Tumble.TRAY_HEIGHT * s)), plate, true)

	var edge: Color = Tumble.TONE_COLOR[text["tone"]] if emphatic and reveal > 0.0 else INK["edge"]
	edge.a *= opacity
	draw_rect(
		Rect2(origin + Vector2(0.3 * s, 0.3 * s),
			Vector2((Tumble.TRAY_WIDTH - 0.6) * s, (Tumble.TRAY_HEIGHT - 0.6) * s)),
		edge, false, 0.6 * s, true)

	for die in visual["dice"]:
		_draw_die(die, opacity, s, origin)

	var stakes := String(text["label"])
	if text["target"] != "":
		stakes = "%s · %s" % [text["label"], text["target"]]
	var fitted := _fitted(stakes, s)
	var dim: Color = INK["dim"]
	dim.a *= opacity
	_draw_text(_font, origin + Vector2(READOUT_X * s, 22.0 * s), fitted[0], int(fitted[1]), dim, false)

	# The arithmetic and the verdict land with the dice, not before. The readout slides up
	# into place over `reveal` (TS translate Y, not a left-edge wipe) while fading in.
	var slide := (1.0 - reveal) * 4.0 * s
	var shown: Color = INK["text"]
	shown.a *= opacity * reveal
	_draw_text(_font, origin + Vector2(READOUT_X * s, 41.0 * s + slide), text["arithmetic"],
		int(round(11.0 * s)), shown, false)

	var verdict: Color = Tumble.TONE_COLOR[text["tone"]]
	verdict.a *= opacity * reveal
	_draw_text(_font_bold, origin + Vector2(READOUT_X * s, 59.0 * s + slide), text["outcome"],
		int(round(13.0 * s)), verdict, false)


func _draw_die(die: Dictionary, opacity: float, s: float, origin: Vector2) -> void:
	var sides: int = die["sides"]
	var shape: Dictionary = OUTLINE[sides] if OUTLINE.has(sides) else OUTLINE[20]
	var at := origin + Vector2(die["x"], die["y"]) * s
	draw_set_transform(at, die["rotation"], Vector2.ONE)

	var alpha := opacity * (0.55 if die["discarded"] else 1.0)
	var body: Color = INK["discard_body"] if die["discarded"] else INK["body"]
	body.a *= alpha
	var body_edge: Color = INK["body_edge"]
	body_edge.a *= alpha

	var radius: float = die["radius"] * s
	var pts := _polygon(int(shape["corners"]), radius, float(shape["tilt"]))
	draw_colored_polygon(pts, body)
	_stroke(pts, body_edge, 0.8 * s)

	if int(shape["corners"]) == 6:
		var facet := _polygon(3, radius * 0.72, -PI / 2.0)
		var facet_ink: Color = INK["discard_body"] if die["discarded"] else INK["facet"]
		facet_ink.a *= alpha
		draw_colored_polygon(facet, facet_ink)
		_stroke(facet, body_edge, 0.6 * s)

	var face: int = die["face"]
	var font_size := int(round((14.0 if face > 9 else 17.0) * s))
	var number: Color = INK["discard_number"] if die["discarded"] else INK["number"]
	number.a *= alpha
	var y_off := (3.0 if int(shape["corners"]) == 6 else 1.0) * s
	_draw_text(_font_bold, Vector2(0.0, y_off), str(face), font_size, number, true)

	draw_set_transform(Vector2.ZERO, 0.0, Vector2.ONE)


func _polygon(corners: int, radius: float, tilt: float) -> PackedVector2Array:
	var pts := PackedVector2Array()
	for i in corners:
		var angle := tilt + (float(i) * PI * 2.0) / float(corners)
		pts.append(Vector2(cos(angle), sin(angle)) * radius)
	return pts


func _stroke(pts: PackedVector2Array, color: Color, width: float) -> void:
	if pts.is_empty():
		return
	var closed := pts.duplicate()
	closed.append(pts[0])
	draw_polyline(closed, color, width, true)


func _draw_text(font: Font, at: Vector2, text: String, font_size: int, color: Color,
		centred: bool) -> void:
	if text.is_empty() or font_size < 1:
		return
	var sz := font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size)
	var ascent := font.get_ascent(font_size)
	var x := (at.x - sz.x * 0.5) if centred else at.x
	var baseline := at.y + ascent - sz.y * 0.5
	draw_string(font, Vector2(x, baseline), text, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size, color)


## Largest label size that will not run off the plate. "INVESTIGATION CHECK · DC 25" is nine
## characters longer than "STEALTH CHECK · DC 10", and a clipped DC is worse than a small one.
func _fitted(label: String, s: float) -> Array:
	var available := (Tumble.TRAY_WIDTH - READOUT_X - 6.0) * s
	var pt := 8.0
	while pt > 6.0:
		var font_size := int(round(pt * s))
		if _font.get_string_size(label, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x <= available:
			return [label, font_size]
		pt -= 0.5
	return [label, int(round(6.0 * s))]


func _fire_cues(elapsed: int, visual: Dictionary, emphatic: bool) -> void:
	_once("shake", 0, elapsed, func() -> void:
		Sfx.play("shake", 0.55, 1.0, 0.0, Tumble.WIND_UP_MS / 1000.0))
	_once("throw", Tumble.WIND_UP_MS, elapsed, func() -> void:
		Sfx.play("throw", 0.8))

	var dice: Array = visual["dice"]
	for i in dice.size():
		var die: Dictionary = dice[i]
		var at := Tumble.land_at(i)
		if elapsed < at or _cues.has("land-%d" % i):
			continue
		_cues["land-%d" % i] = true
		if elapsed < at + 400:
			# The discarded advantage die is a quieter, higher tick — present, but not the story.
			if die["discarded"]:
				Sfx.play("land", 0.3, 1.15)
			else:
				Sfx.play("land", 0.9)

	# A crit or a fumble gets one extra, heavier hit under the reveal. Nothing else does.
	if emphatic:
		_once("emphasis", Tumble.reveal_at(dice.size()), elapsed, func() -> void:
			Sfx.play("throw", 0.85, 0.6))


func _once(key: String, at: int, elapsed: int, sound: Callable) -> void:
	if elapsed < at or _cues.has(key):
		return
	_cues[key] = true
	# A cue whose moment passed while the tab was hidden is skipped, not replayed late.
	if elapsed < at + 400:
		sound.call()
