extends GutTest

## Headless pins for the tray's state machine. `_draw` is pixels; this file asserts idle vs
## throwing, the dismiss clock, and that a finished throw lets go of Table.active_roll.


func before_each() -> void:
	Table.reset()
	Clock.silence_now()
	await get_tree().process_frame
	Clock.use_backend(VoiceBackend.new())


func after_each() -> void:
	Clock.silence_now()
	Table.active_roll = null
	Table.dice_dismiss_at = null
	Table.mode = "EXPLORATION"


func _tray() -> Control:
	var script: GDScript = load("res://chrome/dice_tray.gd")
	assert_not_null(script, "dice_tray.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func _skill_check() -> Dictionary:
	return {
		"request": {"dice": "1d20", "modifier": 5, "advantage": "NORMAL",
			"purpose": "SKILL_CHECK", "actorId": "fighter", "targetId": null,
			"dc": 20, "skill": "ATHLETICS"},
		"faces": [17], "total": 22, "outcome": "SUCCESS",
	}


func _throw_at(tray: Control, started_at: int, result: Dictionary = {}) -> void:
	var roll: Dictionary = result if not result.is_empty() else _skill_check()
	Table.active_roll = {"result": roll, "started_at": started_at}
	Table.roll_thrown.emit(roll)
	tray._process(0.0)


# ---- Idle vs throwing

func test_the_tray_pins_to_the_bottom_right() -> void:
	# The browser sat the tray on the stage's centre line. The Godot log already owns
	# the bottom-left, so centre puts the throw on top of the chat.
	var tray := _tray()
	await wait_frames(1)
	assert_eq(tray.anchor_left, 1.0)
	assert_eq(tray.anchor_right, 1.0)
	assert_eq(tray.anchor_top, 1.0)
	assert_eq(tray.anchor_bottom, 1.0)
	var max_w := Tumble.TRAY_WIDTH * Tumble.DISPLAY_SCALE
	var pad: float = tray.BOTTOM_PAD
	assert_almost_eq(tray.offset_right, -pad, 0.01)
	assert_almost_eq(tray.offset_left, -pad - max_w, 0.01)
	assert_almost_eq(tray.offset_bottom, -pad, 0.01)


func test_the_die_stands_free_with_captions_around_it() -> void:
	# The overlay tray put the readout in a recessed plate to the right of the die.
	# The chin wants the die itself, with the stakes above and the arithmetic below.
	var tray := _tray()
	await wait_frames(1)
	var layout: Dictionary = tray.stage_layout(Vector2(720, 260), 1)
	var radius: float = Tumble.DIE_RADIUS * float(layout["scale"])
	var die: Vector2 = layout["die"]
	assert_false(bool(layout["well"]), "no inner tray well")
	assert_lt(layout["stakes"].y, die.y - radius, "stakes sit above the die")
	assert_gt(layout["arithmetic"].y, die.y + radius, "arithmetic sits below the die")
	assert_gt(layout["outcome"].y, layout["arithmetic"].y, "outcome under the arithmetic")
	assert_almost_eq(layout["stakes"].x, die.x, 8.0, "stakes are centred on the die")


func test_tumble_still_decides_where_the_die_rests() -> void:
	var tray := _tray()
	await wait_frames(1)
	var layout: Dictionary = tray.stage_layout(Vector2(720, 260), 1)
	var rest: Vector2 = layout["origin"] + Vector2(Tumble.FIRST_DIE_X, Tumble.REST_Y) * float(layout["scale"])
	assert_almost_eq(rest.x, layout["die"].x, 0.51)
	assert_almost_eq(rest.y, layout["die"].y, 0.51)


func test_the_tray_stays_idle_with_no_active_roll() -> void:
	var tray := _tray()
	await wait_frames(1)
	assert_eq(Table.active_roll, null)
	assert_false(tray.is_processing(), "nothing to throw, nothing to tick")


func test_a_thrown_roll_enables_processing() -> void:
	var tray := _tray()
	await wait_frames(1)
	Table.active_roll = {"result": _skill_check(), "started_at": Time.get_ticks_msec()}
	Table.roll_thrown.emit(_skill_check())
	assert_true(tray.is_processing())


# ---- Dismiss timing. Transcribed from DiceTray.tsx, not the brief's sketch: an explicit
# dismiss floors at reveal_at + 250 so the tray never leaves before the total is readable,
# and HOLD_MS / COMBAT_HOLD_MS are only the backstop when nobody has dismissed.

func test_exploration_hold_covers_the_dm_when_a_turn_is_in_flight() -> void:
	var tray := _tray()
	await wait_frames(1)
	Table.mode = "EXPLORATION"
	Table.awaiting_dm = true
	Table.dice_dismiss_at = null
	assert_eq(tray.dismiss_ms(_skill_check(), 1_000), Tumble.HOLD_MS)


func test_a_roll_with_no_narration_coming_leaves_once_it_has_been_read() -> void:
	# Debug d20, or a throw after the DM has finished. The nine-second hold exists to cover
	# the prose wait; with nobody speaking it is the tray overstaying.
	var tray := _tray()
	await wait_frames(1)
	Table.mode = "EXPLORATION"
	Table.awaiting_dm = false
	Table.dice_dismiss_at = null
	assert_eq(tray.dismiss_ms(_skill_check(), 1_000), Tumble.COMBAT_HOLD_MS)


func test_combat_hold_is_the_backstop_when_nothing_dismisses() -> void:
	var tray := _tray()
	await wait_frames(1)
	Table.mode = "COMBAT"
	Table.dice_dismiss_at = null
	assert_eq(tray.dismiss_ms(_skill_check(), 1_000), Tumble.COMBAT_HOLD_MS)


func test_an_explicit_dismiss_cannot_leave_before_the_total_is_readable() -> void:
	var tray := _tray()
	await wait_frames(1)
	var result := _skill_check()
	var started := 5_000
	# A dismiss on the first frame would otherwise fade during the throw.
	Table.dice_dismiss_at = started
	assert_eq(tray.dismiss_ms(result, started), Tumble.reveal_at(result["faces"].size()) + 250)
	# Once the total has been readable for a beat, the dismiss clock is the one that counts.
	Table.dice_dismiss_at = started + 5_000
	assert_eq(tray.dismiss_ms(result, started), 5_000)


func test_combat_does_not_use_the_exploration_hold() -> void:
	# 4.5s is past the combat backstop+fade and still inside the exploration hold.
	var tray := _tray()
	await wait_frames(1)
	var now := Time.get_ticks_msec()
	var elapsed := Tumble.COMBAT_HOLD_MS + Tumble.FADE_OUT_MS + 200

	Table.mode = "EXPLORATION"
	Table.awaiting_dm = true
	Table.dice_dismiss_at = null
	_throw_at(tray, now - elapsed)
	assert_not_null(Table.active_roll, "exploration still holds")

	Table.mode = "COMBAT"
	tray._process(0.0)
	assert_eq(Table.active_roll, null, "combat has already left")


# ---- Finished lets go

func test_elapsed_zero_does_not_clear_a_brand_new_roll() -> void:
	# sample() reports finished at elapsed <= 0 because opacity is 0. That is the
	# start of a throw, not the end. started_at in the future forces elapsed < 0
	# so this does not depend on landing in the same millisecond as the clock.
	var tray := _tray()
	await wait_frames(1)
	_throw_at(tray, Time.get_ticks_msec() + 50)
	assert_not_null(Table.active_roll)
	assert_true(tray.is_processing())


func test_finished_clears_the_active_roll_and_goes_idle() -> void:
	var tray := _tray()
	await wait_frames(1)
	Table.mode = "EXPLORATION"
	Table.dice_dismiss_at = null
	var now := Time.get_ticks_msec()
	_throw_at(tray, now - (Tumble.HOLD_MS + Tumble.FADE_OUT_MS + 1))
	assert_eq(Table.active_roll, null)
	assert_false(tray.is_processing())
