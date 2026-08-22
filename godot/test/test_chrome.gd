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
	assert_eq(chrome.get_node("Overlay/Banner").visible, not Table.connected,
		"the banner is the developer's loop, not a modal")
	assert_true(chrome.get_node("Overlay/Title").visible)
