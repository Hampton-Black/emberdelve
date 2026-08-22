extends GutTest

## Headless pins for the overlay: the log is rebuilt from Table, the box locks while the
## DM has the floor, and the title click takes the floor before it asks the server to begin.

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}


func before_each() -> void:
	Table.reset()
	Clock.silence_now()
	await get_tree().process_frame
	await get_tree().process_frame
	Clock.use_backend(VoiceBackend.new())
	Table.set_scene(CRYPT.duplicate(true))
	Net.outbound.clear()


func after_each() -> void:
	Clock.silence_now()
	await get_tree().process_frame
	await get_tree().process_frame


func _transcript() -> RichTextLabel:
	var script: GDScript = load("res://chrome/transcript.gd")
	assert_not_null(script, "transcript.gd")
	if script == null:
		return RichTextLabel.new()
	var node: RichTextLabel = script.new()
	add_child_autofree(node)
	return node


func _input_box() -> LineEdit:
	var script: GDScript = load("res://chrome/input_box.gd")
	assert_not_null(script, "input_box.gd")
	if script == null:
		return LineEdit.new()
	var node: LineEdit = script.new()
	add_child_autofree(node)
	return node


func _title() -> Control:
	var script: GDScript = load("res://chrome/title.gd")
	assert_not_null(script, "title.gd")
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


# ---- Transcript: rebuilt from Table, never appended to

func test_a_prose_line_is_coloured_by_who_spoke() -> void:
	Table.transcript.append({"kind": "prose", "speakerId": "narrator",
		"text": "The lid is stone."})
	var record := _transcript()
	assert_eq(record.text, "[color=#cfc4ae]The lid is stone.[/color]")


func test_the_players_line_is_a_different_blue() -> void:
	Table.transcript.append({"kind": "prose", "speakerId": "player",
		"text": "look at the sarcophagus"})
	var record := _transcript()
	assert_eq(record.text, "[color=#7fa8d0]look at the sarcophagus[/color]")


func test_a_roll_line_names_who_rolled_and_takes_its_tone_from_tumble() -> void:
	Table.transcript.append({"kind": "roll", "result": _skill_check()})
	var record := _transcript()
	# Caption is already pinned in test_tumble; this pin is the log's layout around it,
	# and that the name comes from the scene rather than the speaker-colour table.
	assert_eq(record.text,
		"[color=#9ec46a]Roderick  ATHLETICS CHECK  DC 20  17 + 5 = 22  [b]SUCCESS[/b][/color]")


func test_prose_and_rolls_share_one_list_in_arrival_order() -> void:
	Table.transcript.append({"kind": "roll", "result": _skill_check()})
	Table.transcript.append({"kind": "prose", "speakerId": "narrator",
		"text": "The lid grinds."})
	var record := _transcript()
	var roll_at := record.text.find("Roderick")
	var prose_at := record.text.find("The lid grinds.")
	assert_ne(roll_at, -1)
	assert_ne(prose_at, -1)
	assert_lt(roll_at, prose_at)


func test_the_log_rebuilds_when_table_changes_and_nothing_else_writes_it() -> void:
	var record := _transcript()
	assert_eq(record.text, "")
	Table.say_as_player("heave the lid open")
	assert_eq(record.text, "[color=#7fa8d0]heave the lid open[/color]")


# ---- Input box: locked while the DM has the floor; local echo then the wire

func test_the_box_is_locked_while_the_dm_has_the_floor() -> void:
	var box := _input_box()
	Table.set_started()
	assert_false(box.editable)
	assert_eq(box.placeholder_text, "The DM is speaking.")
	box.text_submitted.emit("sneak a turn in")
	assert_eq(Table.transcript.size(), 0)
	assert_eq(Net.outbound.size(), 0)


func test_enter_echoes_locally_then_sends_free_text() -> void:
	var box := _input_box()
	Table.started = true
	Table.awaiting_dm = false
	Table.started_changed.emit()
	assert_true(box.editable)

	var order: Array[String] = []
	Table.transcript_changed.connect(func() -> void:
		order.append("echo")
		order.append("sends_%d" % Net.outbound.size()))

	box.text_submitted.emit("look at the sarcophagus")

	assert_eq(order, ["echo", "sends_0"], "the keypress is acknowledged before the wire")
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["speakerId"], "player")
	assert_eq(Table.transcript[0]["text"], "look at the sarcophagus")
	assert_true(Table.awaiting_dm)
	assert_eq(box.text, "")
	assert_false(box.editable)

	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "freeText")
	assert_eq(Net.outbound[0]["actorId"], "fighter")
	assert_eq(Net.outbound[0]["text"], "look at the sarcophagus")


# ---- Title: take the floor, then ask for the opening

func test_a_title_click_takes_the_floor_then_asks_the_server_to_begin() -> void:
	var title := _title()
	assert_true(title.visible)
	assert_false(Table.started)

	var order: Array[String] = []
	Table.started_changed.connect(func() -> void:
		order.append("set_started")
		order.append("sends_%d" % Net.outbound.size()))

	var click := InputEventMouseButton.new()
	click.pressed = true
	title.gui_input.emit(click)

	assert_eq(order, ["set_started", "sends_0"],
		"the DM has the floor from this moment, not from the first token")
	assert_true(Table.started)
	assert_true(Table.awaiting_dm)
	assert_false(title.visible)

	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "begin")


func test_a_second_click_does_not_begin_again() -> void:
	var title := _title()
	var click := InputEventMouseButton.new()
	click.pressed = true
	title.gui_input.emit(click)
	title.gui_input.emit(click)
	assert_eq(Net.outbound.size(), 1)


# ---- Scene tree

func test_the_project_boots_into_chrome_with_an_empty_stretching_world() -> void:
	assert_eq(ProjectSettings.get_setting("application/run/main_scene"),
		"res://chrome/chrome.tscn")
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)

	var world: SubViewportContainer = chrome.get_node("World")
	assert_true(world.stretch)
	assert_eq(world.get_child_count(), 0, "Task 15 owns the viewport")

	var record: RichTextLabel = chrome.get_node("Overlay/Log/VBox/Transcript")
	assert_true(record.bbcode_enabled)
	assert_true(record.scroll_following)
	assert_true(record.selection_enabled)
	assert_not_null(chrome.get_node("Overlay/Log/VBox/InputBox"))
	assert_not_null(chrome.get_node_or_null("Overlay/DiceTray"),
		"the tray is overlay chrome — a d20 at 480px is a smudge")
	assert_eq(chrome.get_node("World").get_node_or_null("DiceTray"), null,
		"not inside the pixelated World")
	assert_not_null(chrome.get_node("Overlay/Toast"))
	assert_not_null(chrome.get_node("Overlay/Banner"))
	assert_not_null(chrome.get_node("Overlay/Title"))
	assert_not_null(chrome.get_node("Overlay/CombatBar"))
	assert_not_null(chrome.get_node("Overlay/Defeat"))
	assert_not_null(chrome.get_node("Overlay/Defeat/Restart"),
		"the script references $Restart")
	assert_eq(chrome.get_node("Overlay/Banner").visible, not Table.connected,
		"the banner is the developer's loop, not a modal")
	assert_true(chrome.get_node("Overlay/Title").visible)


# ---- Defeat: a dead fighter is a death, not a crash

func _defeat() -> Control:
	var script: GDScript = load("res://chrome/defeat.gd")
	assert_not_null(script, "defeat.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	var btn := Button.new()
	btn.name = "Restart"
	node.add_child(btn)
	add_child_autofree(node)
	return node


func test_defeat_stays_hidden_while_the_fighter_lives() -> void:
	Table.set_started()
	var defeat := _defeat()
	assert_false(defeat.visible)


func test_defeat_appears_when_the_fighter_falls_during_a_session() -> void:
	Table.set_started()
	var defeat := _defeat()
	Table.scene["entities"][0]["hp"] = 0
	Table.scene_changed.emit()
	assert_true(defeat.visible)


func test_defeat_stays_hidden_if_the_session_has_not_started() -> void:
	Table.scene["entities"][0]["hp"] = 0
	var defeat := _defeat()
	Table.scene_changed.emit()
	assert_false(defeat.visible)


func test_restart_flags_the_table_before_it_hits_the_wire() -> void:
	var defeat := _defeat()
	Table.set_started()
	Table.say_as_player("last words")
	var restart: Button = defeat.get_node_or_null("Restart")
	assert_not_null(restart, "Restart")
	if restart == null:
		return
	# Flag first, then the wire: a scene that landed on this call stack would otherwise
	# be treated as the middle of a session.
	restart.pressed.emit()
	assert_true(Table._restarting, "expect_restart before Net.restart")
	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "restart")
	Table.set_scene(CRYPT.duplicate(true))
	assert_false(Table.started)
	assert_eq(Table.transcript, [])


# ---- Combat bar: ceremony math, end-turn lock, allegiance HP

func _combat_bar() -> Control:
	var script: GDScript = load("res://chrome/combat_bar.gd")
	assert_not_null(script, "combat_bar.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func _combat_view() -> Dictionary:
	return {
		"order": [
			{"entityId": "fighter", "name": "Roderick", "initiative": 18,
				"isPlayerControlled": true},
			{"entityId": "goblin", "name": "Vessk", "initiative": 11,
				"isPlayerControlled": false},
		],
		"activeId": "fighter", "round": 1, "movementRemaining": 6, "actionAvailable": true,
		"legalMoves": [{"x": 3, "y": 2}], "legalTargets": [],
	}


func _open_our_turn() -> void:
	# Backdated so the bar is assembled: these tests are about the lock and the names, not
	# the drums.
	Table.combat_beat = {
		"view": _combat_view(),
		"opened_at": Time.get_ticks_msec() - 1200,
		"closing_at": null,
	}
	Table.combat_changed.emit()


func test_chip_delay_is_the_same_number_sfx_uses() -> void:
	var bar := _combat_bar()
	assert_eq(bar.chip_delay(0), Sfx.CHIP_DELAY_MS)
	assert_eq(bar.chip_delay(1), Sfx.CHIP_DELAY_MS + Sfx.CHIP_STAGGER_MS)
	assert_eq(bar.chip_delay(2), Sfx.CHIP_DELAY_MS + 2 * Sfx.CHIP_STAGGER_MS)


func test_ceremony_arrival_is_zero_before_the_delay() -> void:
	var bar := _combat_bar()
	assert_eq(bar.arrival(1000, 1000, 400, 260), 0.0)
	assert_eq(bar.arrival(1399, 1000, 400, 260), 0.0)


func test_ceremony_arrival_is_one_after_the_duration() -> void:
	var bar := _combat_bar()
	assert_eq(bar.arrival(1660, 1000, 400, 260), 1.0)


func test_ceremony_arrival_interpolates_through_the_duration() -> void:
	var bar := _combat_bar()
	assert_almost_eq(bar.arrival(1530, 1000, 400, 260), 0.5, 0.001)


func test_a_backdated_open_has_already_assembled() -> void:
	# Mid-fight reconnect: Table backdates opened_at by CEREMONY_MS, so every piece of
	# chrome is past its delay+duration the moment the bar mounts.
	var bar := _combat_bar()
	var now := 10_000
	var opened := now - 1200
	assert_eq(bar.arrival(now, opened, bar.BAR_IN_DELAY_MS, bar.BAR_IN_MS), 1.0)
	assert_eq(bar.arrival(now, opened, bar.chip_delay(0), bar.CHIP_IN_MS), 1.0)
	assert_eq(bar.arrival(now, opened, bar.chip_delay(1), bar.CHIP_IN_MS), 1.0)
	assert_eq(bar.arrival(now, opened, bar.CHROME_DELAY_MS, bar.CHROME_IN_MS), 1.0)


func test_a_mid_fight_reconnect_lands_with_the_bar_already_assembled() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	fighting["combat"] = _combat_view()
	Table.set_scene(fighting)
	var bar := _combat_bar()
	var now := Time.get_ticks_msec()
	assert_eq(bar.arrival(now, int(Table.combat_beat["opened_at"]),
		bar.chip_delay(1), bar.CHIP_IN_MS), 1.0)


func test_the_bar_keeps_drawing_after_the_fight_ends() -> void:
	_open_our_turn()
	Table.scene["combat"] = null
	Table.combat_beat["closing_at"] = Time.get_ticks_msec()
	var bar := _combat_bar()
	await wait_frames(1)
	assert_true(bar.visible)
	var chip := bar.find_child("Chip_fighter", true, false)
	assert_not_null(chip)
	if chip == null:
		return
	var name_l: Label = chip.find_child("Name", true, false)
	assert_not_null(name_l)
	if name_l == null:
		return
	assert_eq(name_l.text, "Roderick")


func test_end_turn_is_locked_while_the_dm_has_the_floor() -> void:
	Table.awaiting_dm = true
	_open_our_turn()
	var bar := _combat_bar()
	await wait_frames(1)
	var end: Button = bar.find_child("EndTurn", true, false)
	assert_not_null(end, "EndTurn")
	if end == null:
		return
	assert_true(end.disabled)
	end.pressed.emit()
	assert_eq(Net.outbound.size(), 0)


func test_end_turn_sends_the_active_actor_when_the_floor_is_clear() -> void:
	Table.awaiting_dm = false
	_open_our_turn()
	var bar := _combat_bar()
	await wait_frames(1)
	var end: Button = bar.find_child("EndTurn", true, false)
	assert_not_null(end, "EndTurn")
	if end == null:
		return
	assert_false(end.disabled)
	end.pressed.emit()
	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "endTurn")
	assert_eq(Net.outbound[0]["actorId"], "fighter")


func test_health_colour_is_allegiance_and_length_is_hit_points() -> void:
	var bar := _combat_bar()
	assert_eq(bar.allegiance_color(true), Color("7fae56"))
	assert_eq(bar.allegiance_color(false), Color("b83a30"))
	assert_almost_eq(bar.hp_fraction(6, 12), 0.5, 0.0001)
	assert_almost_eq(bar.hp_fraction(0, 7), 0.0, 0.0001)
	assert_almost_eq(bar.hp_fraction(7, 7), 1.0, 0.0001)
