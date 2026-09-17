extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## Headless pins for the chrome: the crypt sits in a playfield, the log is rebuilt from
## Table, the box locks while the DM has the floor, and the title click takes the floor
## before it asks the server to begin.

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
	Table.set_scene(SceneFixtures.scene(CRYPT))
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


func test_a_second_goblins_line_uses_the_hostile_green_not_narrator_tan() -> void:
	var crypt := CRYPT.duplicate(true)
	crypt["entities"] = CRYPT["entities"] + [
		{"id": "goblin-2", "kind": "goblin", "name": "Skrix", "x": 7, "y": 6,
			"hp": 6, "maxHp": 6, "isPlayerControlled": false},
	]
	Table.set_scene(SceneFixtures.scene(crypt))
	Table.transcript.append({"kind": "prose", "speakerId": "goblin-2",
		"text": "Skrix hisses."})
	var record := _transcript()
	assert_eq(record.text, "[color=#8fae72]Skrix hisses.[/color]")


func test_a_brutes_line_uses_the_hostile_green_not_narrator_tan() -> void:
	var crypt := CRYPT.duplicate(true)
	crypt["entities"] = CRYPT["entities"] + [
		{"id": "brute", "kind": "brute", "name": "Brakk", "x": 5, "y": 5,
			"hp": 16, "maxHp": 16, "isPlayerControlled": false},
	]
	Table.set_scene(SceneFixtures.scene(crypt))
	Table.transcript.append({"kind": "prose", "speakerId": "brute",
		"text": "Brakk growls."})
	var record := _transcript()
	assert_eq(record.text, "[color=#8fae72]Brakk growls.[/color]")


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


func test_the_box_is_locked_while_disconnected() -> void:
	Table.connected = false
	Table.started = true
	Table.awaiting_dm = false
	var box := _input_box()
	assert_false(box.editable)
	box.text_submitted.emit("a turn while the socket is down")
	assert_eq(Table.transcript.size(), 0)
	assert_eq(Net.outbound.size(), 0)


func test_the_box_relocks_when_the_server_returns() -> void:
	Table.connected = false
	Table.started = true
	Table.awaiting_dm = false
	var box := _input_box()
	assert_false(box.editable)
	Net.connected.emit()
	assert_true(Table.connected)
	assert_true(box.editable)


func test_enter_echoes_locally_then_sends_free_text() -> void:
	var box := _input_box()
	Table.connected = true
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

func test_a_title_click_is_refused_while_disconnected() -> void:
	Table.connected = false
	var title := _title()
	assert_true(title.visible)
	assert_lt(title.modulate.a, 1.0)

	var click := InputEventMouseButton.new()
	click.pressed = true
	title.gui_input.emit(click)

	assert_false(Table.started)
	assert_eq(Net.outbound.size(), 0)
	assert_true(title.visible)

	Net.connected.emit()
	assert_eq(title.modulate.a, 1.0)


func test_a_title_click_takes_the_floor_then_asks_the_server_to_begin() -> void:
	Table.connected = true
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
	Table.connected = true
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
	await wait_process_frames(4)

	assert_eq(chrome.get_node_or_null("World"), null,
		"one World — the Node3D inside the SubViewport, not a second container")

	var view: SubViewportContainer = chrome.get_node_or_null("%WorldView")
	assert_not_null(view, "WorldView is the playfield hole, addressed by unique name")
	if view == null:
		return
	assert_true(view.stretch)
	assert_eq(view.texture_filter, CanvasItem.TEXTURE_FILTER_LINEAR,
		"locked look: linear sample at native resolution")

	var sub: SubViewport = view.get_node("SubViewport")
	assert_false(sub.snap_2d_transforms_to_pixel)
	var win := chrome.get_viewport().get_visible_rect().size
	assert_lt(view.size.x, win.x, "the crypt sits in the bezel hole, not the whole window")
	assert_lt(view.size.y, win.y, "the chin is below the crypt, not over it")
	assert_eq(sub.size.x, int(view.size.x), "the SubViewport is the playfield, not the window")
	assert_eq(sub.size.y, int(view.size.y), "height follows the playfield Control")

	var world: Node3D = sub.get_node("World")
	assert_not_null(world.get_node_or_null("Camera3D"))
	assert_not_null(world.get_node_or_null("Room"), "Task 17 hangs the room here")
	assert_not_null(world.get_node_or_null("Props"), "Task 18 hangs props here")
	assert_not_null(world.get_node_or_null("Tokens"), "Task 19 hangs tokens here")
	assert_not_null(world.get_node_or_null("Overlay"), "Task 20 hangs the click overlay here")
	var cam: Camera3D = world.get_node("Camera3D")
	assert_eq(cam.projection, Camera3D.PROJECTION_ORTHOGONAL)

	assert_eq(chrome.get_node_or_null("Overlay/Log"), null,
		"the log left the overlay; it lives in the chin")
	var chin: Control = chrome.get_node_or_null("%Chin")
	assert_not_null(chin, "Chin holds the log below the crypt")
	if chin == null:
		return
	var record: RichTextLabel = chrome.get_node("%Chin/Row/Log/VBox/Transcript")
	assert_true(record.bbcode_enabled)
	assert_true(record.scroll_following)
	assert_true(record.selection_enabled)
	var input: LineEdit = chrome.get_node("%Chin/Row/Log/VBox/InputBox")
	assert_eq(input.placeholder_text, "What do you do?")
	assert_gt(chin.global_position.y + 1.0, view.global_position.y + view.size.y,
		"the chin sits below the playfield")
	assert_gt(chin.size.x, win.x * 0.7, "the chin spans the bezel, not a corner veil")
	var chat: Control = chrome.get_node("%Chin/Row/Log")
	var stage: Control = chrome.get_node_or_null("%ChinStage")
	assert_not_null(stage, "the right half of the chin holds the die and the verbs")
	if stage != null:
		assert_almost_eq(chat.size.x, stage.size.x, 80.0,
			"chat takes the left half; dice and buttons share the right")
		assert_gt(stage.global_position.x, chat.global_position.x + chat.size.x * 0.5)
	var chin_panel := (chin as PanelContainer).get_theme_stylebox("panel") as StyleBoxFlat
	assert_not_null(chin_panel, "Chin wears a StyleBoxFlat")
	if chin_panel != null:
		assert_gte(chin_panel.bg_color.a, 0.85, "the chin is a wall, not a veil over the room")

	var lintel: Label = chrome.get_node_or_null("%Lintel")
	assert_not_null(lintel, "the room name sits on the lintel")
	if lintel == null:
		return
	assert_eq(lintel.text, "THE CRYPT")
	assert_true(lintel.visible)

	assert_not_null(chrome.get_node_or_null("%DiceTray"),
		"the tray lives in the chin's right half, not over the crypt")
	assert_eq(chrome.get_node_or_null("Overlay/DiceTray"), null,
		"the tray left the overlay")
	assert_eq(world.get_node_or_null("DiceTray"), null,
		"not inside the World viewport")
	assert_eq(sub.get_node_or_null("DiceTray"), null)
	assert_not_null(chrome.get_node("Overlay/Toast"))
	assert_not_null(chrome.get_node("Overlay/Banner"))
	assert_not_null(chrome.get_node("Overlay/Title"))
	assert_eq(chrome.get_node_or_null("Overlay/CombatBar"), null,
		"the old 42px sky strip is gone")
	assert_not_null(chrome.get_node_or_null("%CombatChips"),
		"initiative lives over the playfield")
	assert_not_null(chrome.get_node_or_null("%CombatVerbs"),
		"verbs live in the chin")
	assert_false(FileAccess.file_exists("res://chrome/defeat.gd"),
		"defeat.gd is gone; the ending page replaced it")
	assert_false(FileAccess.file_exists("res://chrome/defeat.gd.uid"))
	assert_eq(chrome.get_node_or_null("Overlay/Defeat"), null,
		"Overlay/Defeat is gone; the ending page replaced it")
	assert_not_null(chrome.get_node_or_null("Overlay/Ending"))
	var ending_restart: Node = null
	var ending_node: Node = chrome.get_node_or_null("Overlay/Ending")
	if ending_node != null:
		ending_restart = ending_node.find_child("Restart", true, false)
	assert_not_null(ending_restart, "DESCEND AGAIN is the way on")
	var debug_bar := chrome.get_node_or_null("Overlay/DebugBar")
	assert_not_null(debug_bar, "the debug bar is overlay chrome")
	if debug_bar == null:
		return
	assert_true(debug_bar is HBoxContainer)
	assert_eq(debug_bar.get_child_count(), 8)
	assert_eq(chrome.get_node("Overlay/Banner").visible, not Table.connected,
		"the banner is the developer's loop, not a modal")
	assert_true(chrome.get_node("Overlay/Title").visible)

	var record_size := record.get_theme_font_size("normal_font_size")
	assert_eq(record_size, 13, "chrome type matches the browser log, not the engine default")

	var tray: Control = chrome.get_node("%DiceTray")
	assert_eq(tray.get_parent().name, "ChinStage")
	assert_true(chin.get_global_rect().has_point(tray.global_position + Vector2(8, 8)),
		"the tray sits in the chin")

	var gallery := CRYPT.duplicate(true)
	gallery["roomId"] = "gallery"
	Table.set_scene(SceneFixtures.scene(gallery))
	assert_eq(lintel.text, "THE LONG GALLERY")

	var playfield: Control = chrome.get_node_or_null("Frame/Playfield")
	assert_not_null(playfield, "Playfield")
	if playfield != null:
		assert_lt(playfield.anchor_left, 0.06, "no left character column")
	var badge: Control = chrome.get_node_or_null("%PartyBadge")
	assert_not_null(badge, "the party floats over the playfield")
	if badge != null and view.size.x > 0.0:
		var over := view.get_global_rect()
		var at := badge.global_position + badge.size * 0.5
		assert_true(over.has_point(at), "the badge sits on the crypt, not beside it")


# ---- Party badge: reads Table, floats over the playfield, ignores the pointer

func _badge() -> Control:
	var script: GDScript = load("res://chrome/party_badge.gd")
	assert_not_null(script, "party_badge.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func test_the_party_badge_names_the_player_and_their_armour() -> void:
	var badge := _badge()
	var name_l: Label = badge.find_child("Name", true, false)
	var hp_l: Label = badge.find_child("Hp", true, false)
	var ac_l: Label = badge.find_child("Ac", true, false)
	assert_not_null(name_l, "Name")
	assert_not_null(hp_l, "Hp")
	assert_not_null(ac_l, "Ac")
	if name_l == null or hp_l == null or ac_l == null:
		return
	assert_eq(name_l.text, "Roderick")
	assert_eq(hp_l.text, "12/12")
	assert_eq(ac_l.text, "AC 16")
	assert_eq(badge.ac_for("fighter"), 16)
	assert_eq(badge.ac_for("goblin"), 15)


func test_the_hp_bar_shortens_without_changing_colour() -> void:
	var badge := _badge()
	var fill: ColorRect = badge.find_child("HpFill", true, false)
	assert_not_null(fill, "HpFill")
	if fill == null:
		return
	assert_eq(fill.color, Color("7fae56"))
	assert_almost_eq(fill.anchor_right, 1.0, 0.001)

	Table.scene["entities"][0]["hp"] = 6
	Table.scene_changed.emit()
	var hp_l: Label = badge.find_child("Hp", true, false)
	assert_not_null(hp_l, "Hp")
	if hp_l == null:
		return
	assert_eq(hp_l.text, "6/12")
	assert_almost_eq(fill.anchor_right, 0.5, 0.001)
	assert_eq(fill.color, Color("7fae56"), "green is allegiance, not a wound warning")


func test_the_badge_ignores_the_pointer() -> void:
	var badge := _badge()
	assert_eq(badge.mouse_filter, Control.MOUSE_FILTER_IGNORE)
	for child in badge.find_children("*", "Control", true, false):
		assert_eq((child as Control).mouse_filter, Control.MOUSE_FILTER_IGNORE,
			"%s must not shadow the board" % child.name)


func test_q_and_e_step_the_corner_through_chrome() -> void:
	# Production: the rig sits in a SubViewport and never sees window keys. Chrome forwards.
	# Isolation tests that parent CameraRig to GUT's root cannot fail if this path is removed.
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(2)
	var world: Node3D = chrome.get_node_or_null("%WorldView/SubViewport/World")
	assert_not_null(world, "World sits in the playfield SubViewport")
	if world == null:
		return
	assert_not_null(world.rig, "World.rig")
	if world.rig == null:
		return
	assert_eq(world.rig.corner, 0)
	var q := InputEventKey.new()
	q.keycode = KEY_Q
	q.pressed = true
	chrome._unhandled_input(q)
	assert_eq(world.rig.corner, 3, "Q turns counter-clockwise through Chrome")
	var e := InputEventKey.new()
	e.keycode = KEY_E
	e.pressed = true
	chrome._unhandled_input(e)
	assert_eq(world.rig.corner, 0, "E turns clockwise, back to the start")


func test_the_window_opens_larger_than_the_plan_s_720p_default() -> void:
	# 1280×720 makes the overlay feel like a postage stamp and the default 16px type
	# huge inside it. A 1080p viewport is the same density with room to see the room.
	assert_eq(ProjectSettings.get_setting("display/window/size/viewport_width"), 1920)
	assert_eq(ProjectSettings.get_setting("display/window/size/viewport_height"), 1080)


# ---- Ending page: derived from the report, never from hit points

func _ending_page() -> Control:
	var script: GDScript = load("res://chrome/ending.gd")
	assert_not_null(script, "ending.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func _ending_report(kind: String, extra: Dictionary = {}) -> Dictionary:
	var report := {
		"ending": kind,
		"partyNames": ["Roderick"],
		"roomName": "The Ashen Crypt",
		"objectiveName": "reliquary",
		"roomsEntered": 1,
		"roomsInSite": 2,
		"potionsUsed": 0,
		"potionsBrought": 2,
		"torchesUsed": 0,
		"torchesBrought": 2,
		"fights": 0,
		"objective": "LEFT_WHERE_IT_LAY",
	}
	for key in extra:
		report[key] = extra[key]
	return report


func test_zero_hp_without_an_ending_report_shows_no_page() -> void:
	Table.set_started()
	var page := _ending_page()
	Table.scene["entities"][0]["hp"] = 0
	Table.scene.erase("ending")
	Table.scene_changed.emit()
	await wait_frames(1)
	assert_false(page.visible, "the page is not derived from hit points")


func test_party_lost_sentence_is_ash() -> void:
	Table.set_started()
	var page := _ending_page()
	Table.scene["ending"] = _ending_report("PARTY_LOST")
	Table.scene_changed.emit()
	await wait_seconds(page.IMPACT_SECONDS + page.DEATH_A_SECONDS + 0.05)
	assert_true(page.visible)
	var sentence: Label = page.find_child("Sentence", true, false)
	assert_not_null(sentence, "Sentence")
	if sentence == null:
		return
	assert_eq(sentence.text, "Roderick fell in the Ashen Crypt.")
	assert_eq(sentence.get_theme_color("font_color"), page.ASH)


func test_extracted_with_objective_is_daylight() -> void:
	Table.set_started()
	var page := _ending_page()
	Table.scene["ending"] = _ending_report("EXTRACTED_WITH_OBJECTIVE", {
		"objective": "CARRIED_OUT",
		"hurt": "barely marked",
	})
	Table.scene_changed.emit()
	await wait_frames(1)
	assert_true(page.visible)
	var sentence: Label = page.find_child("Sentence", true, false)
	assert_not_null(sentence, "Sentence")
	if sentence == null:
		return
	assert_eq(sentence.text, "Roderick came out with the reliquary.")
	assert_eq(sentence.get_theme_color("font_color"), page.DAYLIGHT)


func test_extracted_without_is_daylight_and_immediate() -> void:
	Table.set_started()
	var page := _ending_page()
	Table.scene["ending"] = _ending_report("EXTRACTED_WITHOUT", {"hurt": "bloodied"})
	Table.scene_changed.emit()
	await wait_frames(1)
	assert_true(page.visible, "extraction shows the page at once")
	var sentence: Label = page.find_child("Sentence", true, false)
	assert_not_null(sentence, "Sentence")
	if sentence == null:
		return
	assert_eq(sentence.text, "Roderick came out.")
	assert_eq(sentence.get_theme_color("font_color"), page.DAYLIGHT)


func test_party_lost_waits_for_the_fall() -> void:
	Table.set_started()
	var page := _ending_page()
	Table.scene["ending"] = _ending_report("PARTY_LOST")
	Table.scene_changed.emit()
	await wait_frames(1)
	assert_false(page.visible, "the page waits for IMPACT_SECONDS + Death_A")
	await wait_seconds(page.IMPACT_SECONDS + page.DEATH_A_SECONDS + 0.05)
	assert_true(page.visible)


func test_descend_again_is_live_while_the_dm_speaks() -> void:
	var page := _ending_page()
	Table.set_started()
	Table.awaiting_dm = true
	Table.scene["ending"] = _ending_report("EXTRACTED_WITHOUT", {"hurt": "barely marked"})
	Table.scene_changed.emit()
	await wait_frames(1)
	Table.say_as_player("last words")
	var restart: Button = page.find_child("Restart", true, false)
	assert_not_null(restart, "Restart")
	if restart == null:
		return
	assert_false(restart.disabled)
	restart.pressed.emit()
	assert_true(Table._restarting, "expect_restart before Net.restart")
	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "restart")
	Table.set_scene(SceneFixtures.scene(CRYPT))
	assert_false(Table.started)
	assert_eq(Table.transcript, [])


func test_ending_page_delay_matches_the_token_impact() -> void:
	var page := _ending_page()
	var token_script: GDScript = load("res://world/tokens/token.gd")
	assert_not_null(token_script, "token.gd")
	if token_script == null:
		return
	assert_eq(page.IMPACT_SECONDS, token_script.IMPACT_SECONDS)
	assert_eq(page.DEATH_A_SECONDS, 0.8)
	assert_eq(page.delay_for(_ending_report("PARTY_LOST")),
		page.IMPACT_SECONDS + page.DEATH_A_SECONDS)
	assert_eq(page.delay_for(_ending_report("EXTRACTED_WITHOUT")), 0.0)


func test_ending_hides_input_and_bars_and_dismisses_leave_confirm() -> void:
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	Table.set_started()
	Table.awaiting_dm = false
	var title: Control = chrome.get_node_or_null("Overlay/Title")
	if title != null:
		title.visible = false
	Table.leave_confirm = {"exit_id": "stair-south", "holding": false}
	Table.leave_confirm_changed.emit()
	var next := Table.scene.duplicate(true)
	next["ending"] = _ending_report("EXTRACTED_WITHOUT", {"hurt": "barely marked"})
	Table.set_scene(next)
	await wait_frames(2)
	var input: LineEdit = chrome.get_node_or_null("%Chin/Row/Log/VBox/InputBox")
	assert_not_null(input, "InputBox")
	if input != null:
		assert_false(input.visible)
	var explore: Control = chrome.get_node_or_null("%ExplorationBar")
	if explore != null:
		assert_false(explore.visible)
	var debug_bar: Control = chrome.get_node_or_null("Overlay/DebugBar")
	if debug_bar != null:
		assert_false(debug_bar.visible)
	var confirm: Control = chrome.get_node_or_null("%LeaveConfirm")
	if confirm != null:
		assert_false(confirm.visible)
	var transcript: Control = chrome.get_node_or_null("%Chin/Row/Log/VBox/Transcript")
	assert_not_null(transcript, "transcript stays")
	if transcript != null:
		assert_true(transcript.visible)
	var ending: Control = chrome.get_node_or_null("Overlay/Ending")
	assert_not_null(ending, "Ending")
	if ending != null:
		assert_true(ending.visible)
		var chin: Control = chrome.get_node_or_null("%Chin")
		var view: Control = chrome.get_node_or_null("%WorldView")
		if chin != null:
			assert_lt(ending.global_position.y + ending.size.y, chin.global_position.y + 1.0,
				"the page never reaches the chin")
		if view != null and view.size.x > 0.0:
			assert_gt(ending.global_position.x, view.global_position.x + view.size.x * 0.4,
				"the page sits at the top right")
	assert_true(Table.leave_confirm.is_empty())


func test_ending_page_is_sized_to_its_content() -> void:
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	Table.set_started()
	var title: Control = chrome.get_node_or_null("Overlay/Title")
	if title != null:
		title.visible = false
	var next := Table.scene.duplicate(true)
	next["ending"] = _ending_report("EXTRACTED_WITHOUT", {"hurt": "barely marked"})
	Table.set_scene(next)
	await wait_frames(2)
	var ending: Control = chrome.get_node_or_null("Overlay/Ending")
	assert_not_null(ending, "Ending")
	if ending == null:
		return
	assert_true(ending.visible)
	var content_h: float = ending.get_combined_minimum_size().y
	assert_gt(content_h, 40.0, "the page has a sentence, a ledger and a button")
	assert_lt(ending.size.y, 280.0, "not a ~312px empty plate")
	assert_almost_eq(ending.size.y, content_h, 8.0,
		"the control's height is its content, not a tall empty hit-target")
	var chin: Control = chrome.get_node_or_null("%Chin")
	if chin != null:
		assert_lt(ending.global_position.y + ending.size.y, chin.global_position.y + 1.0,
			"the page never reaches the chin")


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
	Table.set_scene(SceneFixtures.scene(fighting))
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


func test_attack_and_move_dim_from_the_servers_combat_view() -> void:
	Table.awaiting_dm = false
	_open_our_turn()
	var bar := _combat_bar()
	await wait_frames(1)
	var attack: Button = bar.find_child("Attack", true, false)
	var move: Button = bar.find_child("Move", true, false)
	assert_not_null(attack, "Attack")
	assert_not_null(move, "Move")
	if attack == null or move == null:
		return
	assert_gt(attack.modulate.a, 0.9)
	assert_gt(move.modulate.a, 0.9)

	Table.combat_beat["view"]["actionAvailable"] = false
	Table.combat_beat["view"]["movementRemaining"] = 0
	Table.combat_changed.emit()
	assert_lt(attack.modulate.a, 0.5, "actionAvailable gates Attack")
	assert_lt(move.modulate.a, 0.5, "movementRemaining gates Move")
	attack.pressed.emit()
	move.pressed.emit()
	assert_eq(Net.outbound.size(), 0, "Attack and Move are verbs, not new click modes")


func test_exploration_shows_no_chips_or_verbs() -> void:
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var chips: Control = chrome.get_node_or_null("%CombatChips")
	var verbs: Control = chrome.get_node_or_null("%CombatVerbs")
	assert_not_null(chips, "CombatChips")
	assert_not_null(verbs, "CombatVerbs")
	if chips == null or verbs == null:
		return
	assert_false(chips.visible)
	assert_false(verbs.visible)


func test_an_exploration_check_shows_the_die_without_combat_verbs() -> void:
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	Table.active_roll = {"result": _skill_check(), "started_at": Time.get_ticks_msec()}
	Table.roll_thrown.emit(_skill_check())
	var verbs: Control = chrome.get_node_or_null("%CombatVerbs")
	var tray: Control = chrome.get_node_or_null("%DiceTray")
	var chin: Control = chrome.get_node_or_null("%Chin")
	assert_not_null(verbs, "CombatVerbs")
	assert_not_null(tray, "DiceTray")
	assert_not_null(chin, "Chin")
	if verbs == null or tray == null or chin == null:
		return
	assert_false(verbs.visible, "a check is not a fight")
	assert_true(chin.get_global_rect().has_point(tray.global_position + Vector2(8, 8)),
		"the die sits in the chin")
	assert_not_null(Table.active_roll, "the throw is in the air")


func test_a_combat_attack_shows_the_die_beside_the_verbs() -> void:
	Table.set_started()
	_open_our_turn()
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var attack := {
		"request": {"dice": "1d20", "modifier": 5, "advantage": "NORMAL",
			"purpose": "ATTACK", "actorId": "fighter", "targetId": "goblin",
			"dc": 15, "skill": null},
		"faces": [14], "total": 19, "outcome": "HIT",
	}
	Table.active_roll = {"result": attack, "started_at": Time.get_ticks_msec()}
	Table.roll_thrown.emit(attack)
	var verbs: Control = chrome.get_node_or_null("%CombatVerbs")
	var tray: Control = chrome.get_node_or_null("%DiceTray")
	var stage: Control = chrome.get_node_or_null("%ChinStage")
	assert_not_null(verbs, "CombatVerbs")
	assert_not_null(tray, "DiceTray")
	assert_not_null(stage, "ChinStage")
	if verbs == null or tray == null or stage == null:
		return
	assert_true(verbs.visible)
	assert_eq(tray.get_parent(), stage)
	assert_eq(verbs.get_parent(), stage)
	assert_true(stage.get_global_rect().has_point(tray.global_position + Vector2(8, 8)))
	assert_true(stage.get_global_rect().has_point(verbs.global_position + Vector2(8, 8)))
	assert_lt(verbs.global_position.x, tray.global_position.x,
		"verbs sit left of the die, next to the log")
	var chat: Control = chrome.get_node("%Chin/Row/Log")
	assert_lt(verbs.global_position.x - (chat.global_position.x + chat.size.x), 40.0,
		"verbs sit just to the right of the log, not the far edge")


func test_an_enemy_turn_does_not_write_acting_in_the_chin() -> void:
	# The waiting label wrapped to one glyph per line in a skinny column. The log
	# already says who is acting; the chin does not need a second copy.
	Table.set_started()
	Table.combat_beat = {
		"view": {
			"order": [
				{"entityId": "fighter", "name": "Roderick", "initiative": 18,
					"isPlayerControlled": true},
				{"entityId": "goblin", "name": "Vessk", "initiative": 11,
					"isPlayerControlled": false},
			],
			"activeId": "goblin", "round": 1, "movementRemaining": 6, "actionAvailable": true,
			"legalMoves": [], "legalTargets": [{"x": 2, "y": 2}],
		},
		"opened_at": Time.get_ticks_msec() - 1200,
		"closing_at": null,
	}
	Table.combat_changed.emit()
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var verbs: Control = chrome.get_node_or_null("%CombatVerbs")
	assert_not_null(verbs, "CombatVerbs")
	if verbs == null:
		return
	assert_false(verbs.visible, "the enemy turn does not occupy the chin")
	var waiting: Control = chrome.find_child("Waiting", true, false)
	if waiting != null:
		assert_false(waiting.visible, "no vertical 'is acting' column")


func test_combat_puts_chips_over_the_playfield_and_verbs_in_the_chin() -> void:
	Table.set_started()
	_open_our_turn()
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var title: Control = chrome.get_node_or_null("Overlay/Title")
	if title != null:
		title.visible = false
	var chips: Control = chrome.get_node_or_null("%CombatChips")
	var verbs: Control = chrome.get_node_or_null("%CombatVerbs")
	var view: Control = chrome.get_node_or_null("%WorldView")
	var chin: Control = chrome.get_node_or_null("%Chin")
	assert_not_null(chips, "CombatChips")
	assert_not_null(verbs, "CombatVerbs")
	assert_not_null(view, "WorldView")
	assert_not_null(chin, "Chin")
	if chips == null or verbs == null or view == null or chin == null:
		return
	assert_true(chips.visible)
	assert_true(verbs.visible)
	var chip := chips.find_child("Chip_fighter", true, false)
	assert_not_null(chip, "Chip_fighter")
	if chip != null and view.size.x > 0.0:
		assert_true(view.get_global_rect().has_point(chip.global_position + chip.size * 0.5),
			"chips sit over the crypt")
	if verbs.size.x > 0.0:
		assert_true(chin.get_global_rect().has_point(verbs.global_position + Vector2(8, 8)),
			"verbs sit in the chin")
		var stage: Control = chrome.get_node_or_null("%ChinStage")
		if stage != null:
			assert_true(stage.get_global_rect().has_point(verbs.global_position + Vector2(8, 8)),
				"verbs share the right half with the die")
	assert_not_null(verbs.find_child("Attack", true, false))
	assert_not_null(verbs.find_child("Move", true, false))
	assert_not_null(verbs.find_child("EndTurn", true, false))
	assert_eq(chips.mouse_filter, Control.MOUSE_FILTER_IGNORE)


# ---- Exploration bar: potion and torch, locked while the DM has the floor

func _exploration_bar() -> Control:
	var script: GDScript = load("res://chrome/exploration_bar.gd")
	assert_not_null(script, "exploration_bar.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func _exploration_scene(extra: Dictionary = {}) -> Dictionary:
	var flat := CRYPT.duplicate(true)
	flat["potions"] = 2
	flat["torches"] = 2
	flat["rope"] = 0
	flat["canSpendTorch"] = false
	flat["canRest"] = true
	for key in extra:
		flat[key] = extra[key]
	return SceneFixtures.scene(flat)


func test_exploration_bar_shows_counts_and_hides_in_combat() -> void:
	Table.set_scene(_exploration_scene({"canSpendTorch": true}))
	var bar := _exploration_bar()
	await wait_frames(1)
	var potion: Button = bar.find_child("Potion", true, false)
	var torch: Button = bar.find_child("Torch", true, false)
	assert_not_null(potion, "Potion")
	assert_not_null(torch, "Torch")
	if potion == null or torch == null:
		return
	assert_eq(potion.text, "POTION 2")
	assert_eq(torch.text, "TORCH 2")
	assert_false(potion.disabled)
	assert_false(torch.disabled)

	Table.mode = "COMBAT"
	Table.scene_changed.emit()
	await wait_frames(1)
	assert_false(bar.visible)


func test_exploration_bar_comes_back_after_a_fight_without_a_crossing() -> void:
	# emberdelve-0v6: the bar stayed gone until the next room entry.
	Table.set_scene(_exploration_scene({"canSpendTorch": true}))
	var bar := _exploration_bar()
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat_view()},
	])
	await wait_seconds(1.4)
	assert_false(bar.visible, "hidden while fighting")
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "EXPLORATION"},
		{"kind": "CombatChanged", "combat": null},
	])
	await wait_frames(2)
	assert_false(bar.visible, "still hidden while the combat chrome dissolves")
	await wait_seconds(Table.COMBAT_CLOSE_MS / 1000.0 + 0.15)
	assert_true(bar.visible, "back once the dissolve is over, in the same room")


func test_exploration_buttons_grey_when_the_server_says_so() -> void:
	Table.set_scene(_exploration_scene({
		"potions": 0, "torches": 1, "canSpendTorch": false,
	}))
	var bar := _exploration_bar()
	await wait_frames(1)
	var potion: Button = bar.find_child("Potion", true, false)
	var torch: Button = bar.find_child("Torch", true, false)
	assert_not_null(potion, "Potion")
	assert_not_null(torch, "Torch")
	if potion == null or torch == null:
		return
	assert_true(potion.disabled)
	assert_true(torch.disabled)


func test_exploration_buttons_lock_while_the_dm_has_the_floor() -> void:
	Table.set_scene(_exploration_scene({"canSpendTorch": true}))
	Table.awaiting_dm = true
	var bar := _exploration_bar()
	await wait_frames(1)
	var potion: Button = bar.find_child("Potion", true, false)
	assert_not_null(potion, "Potion")
	if potion == null:
		return
	assert_true(potion.disabled)
	potion.pressed.emit()
	assert_eq(Net.outbound.size(), 0)


func test_exploration_buttons_redraw_on_awaiting_dm_transition() -> void:
	Table.set_scene(_exploration_scene({"canSpendTorch": true}))
	var bar := _exploration_bar()
	await wait_frames(1)
	var potion: Button = bar.find_child("Potion", true, false)
	assert_not_null(potion, "Potion")
	if potion == null:
		return
	assert_false(potion.disabled, "floor is clear after mount")

	Table.awaiting_dm = true
	Table.transcript_changed.emit()
	await wait_frames(1)
	assert_true(potion.disabled)

	Table.awaiting_dm = false
	Table.transcript_changed.emit()
	await wait_frames(1)
	assert_false(potion.disabled)


func test_a_potion_click_sends_use_item_with_no_model() -> void:
	Table.set_scene(_exploration_scene())
	Table.awaiting_dm = false
	var bar := _exploration_bar()
	await wait_frames(1)
	var potion: Button = bar.find_child("Potion", true, false)
	assert_not_null(potion, "Potion")
	if potion == null:
		return
	potion.pressed.emit()
	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "useItem")
	assert_eq(Net.outbound[0]["actorId"], "fighter")
	assert_eq(Net.outbound[0]["item"], "potion")


func test_rest_button_greys_when_can_rest_is_false() -> void:
	Table.set_scene(_exploration_scene({"canRest": false}))
	var bar := _exploration_bar()
	await wait_frames(1)
	var rest_btn: Button = bar.find_child("Rest", true, false)
	assert_not_null(rest_btn, "Rest")
	if rest_btn == null:
		return
	assert_true(rest_btn.disabled)


func test_rest_button_locks_while_the_dm_has_the_floor() -> void:
	Table.set_scene(_exploration_scene())
	Table.awaiting_dm = true
	var bar := _exploration_bar()
	await wait_frames(1)
	var rest_btn: Button = bar.find_child("Rest", true, false)
	assert_not_null(rest_btn, "Rest")
	if rest_btn == null:
		return
	assert_true(rest_btn.disabled)
	rest_btn.pressed.emit()
	assert_eq(Net.outbound.size(), 0)


func test_a_rest_click_sends_rest_with_no_model() -> void:
	Table.set_scene(_exploration_scene())
	Table.awaiting_dm = false
	var bar := _exploration_bar()
	await wait_frames(1)
	var rest_btn: Button = bar.find_child("Rest", true, false)
	assert_not_null(rest_btn, "Rest")
	if rest_btn == null:
		return
	rest_btn.pressed.emit()
	assert_eq(Net.outbound.size(), 1)
	assert_eq(Net.outbound[0]["type"], "rest")
	assert_eq(Net.outbound[0]["actorId"], "fighter")


# ---- Debug bar: eight buttons, exact wire dictionaries, locked while the DM has the floor

const DEBUG_BUTTONS := [
	["roll d20", {"type": "debugRoll", "actorId": "fighter", "skill": "PERCEPTION",
		"difficulty": "MEDIUM"}],
	["reveal", {"type": "debugReveal", "propId": "alcove"}],
	["spawn goblin", {"type": "debugSpawnGoblin"}],
	["start combat", {"type": "debugStartCombat"}],
	["combat mode", {"type": "debugSetMode", "mode": "COMBAT"}],
	["explore mode", {"type": "debugSetMode", "mode": "EXPLORATION"}],
	["re-open", {"type": "debugOpen"}],
	["resend scene", {"type": "debugScene"}],
]


func _debug_bar() -> HBoxContainer:
	var script: GDScript = load("res://chrome/debug_bar.gd")
	assert_not_null(script, "debug_bar.gd")
	if script == null:
		return HBoxContainer.new()
	var node: HBoxContainer = script.new()
	add_child_autofree(node)
	return node


func test_the_debug_bar_has_eight_buttons_with_the_right_labels() -> void:
	var bar := _debug_bar()
	assert_eq(bar.get_child_count(), 8)
	if bar.get_child_count() != 8:
		return
	for i in DEBUG_BUTTONS.size():
		var button := bar.get_child(i) as Button
		assert_not_null(button, "button %d" % i)
		if button == null:
			continue
		assert_eq(button.text, DEBUG_BUTTONS[i][0])


func test_pressing_a_debug_button_sends_the_exact_dictionary() -> void:
	var bar := _debug_bar()
	if bar.get_child_count() != DEBUG_BUTTONS.size():
		assert_eq(bar.get_child_count(), 8)
		return
	for i in DEBUG_BUTTONS.size():
		Net.outbound.clear()
		var button := bar.get_child(i) as Button
		assert_not_null(button, "button %d" % i)
		if button == null:
			continue
		button.pressed.emit()
		assert_eq(Net.outbound.size(), 1, "button %s" % DEBUG_BUTTONS[i][0])
		if Net.outbound.is_empty():
			continue
		assert_eq(Net.outbound[0], DEBUG_BUTTONS[i][1], "button %s" % DEBUG_BUTTONS[i][0])


func test_a_click_on_the_world_view_sends_move_to() -> void:
	# Production clicks land on WorldView (a Control). World._unhandled_input never
	# sees them — the same SubViewport isolation that made Chrome forward Q/E.
	Table.set_started()
	Table.awaiting_dm = false
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var title: Control = chrome.get_node_or_null("Overlay/Title")
	if title != null:
		title.visible = false
	var world: Node3D = chrome.get_node_or_null("%WorldView/SubViewport/World")
	assert_not_null(world, "World sits in the playfield SubViewport")
	if world == null:
		return
	assert_true(world.has_method("handle_pointer"),
		"World.handle_pointer is the viewport-local click seam Chrome forwards onto")
	var cam: Camera3D = world.get_node("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null or not world.has_method("grid_to_world"):
		return
	var square := Vector2i(4, 3)
	var ground: Vector3 = world.grid_to_world("crypt", square.x, square.y)
	var viewport_pos: Vector2 = cam.unproject_position(ground)
	var view: SubViewportContainer = chrome.get_node("%WorldView")
	var window_pos: Vector2 = view.get_global_transform_with_canvas() * viewport_pos
	var click := InputEventMouseButton.new()
	click.button_index = MOUSE_BUTTON_LEFT
	click.pressed = true
	click.position = viewport_pos
	click.global_position = window_pos
	Net.outbound.clear()
	view.gui_input.emit(click)
	assert_eq(Net.outbound.size(), 1, "clicking the board through WorldView sends moveTo")
	if Net.outbound.is_empty():
		return
	assert_eq(Net.outbound[0]["type"], "moveTo")
	assert_eq(int(Net.outbound[0]["x"]), square.x)
	assert_eq(int(Net.outbound[0]["y"]), square.y)


func test_hover_uses_control_local_position() -> void:
	# WorldView.gui_input delivers Control-local pixels in event.position.
	# SubViewportContainer then pushes the same event into the 3D world;
	# World must not convert it again or the hover sits a board away.
	Table.set_started()
	Table.awaiting_dm = false
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var title: Control = chrome.get_node_or_null("Overlay/Title")
	if title != null:
		title.visible = false
	var view: SubViewportContainer = chrome.get_node_or_null("%WorldView")
	assert_not_null(view, "WorldView")
	if view == null:
		return
	var world: Node3D = view.get_node("SubViewport/World")
	var cam: Camera3D = world.get_node("Camera3D") as Camera3D
	assert_not_null(cam, "Camera3D")
	if cam == null:
		return
	var square := Vector2i(4, 3)
	var ground: Vector3 = world.grid_to_world("crypt", square.x, square.y)
	var viewport_pos: Vector2 = cam.unproject_position(ground)
	var motion := InputEventMouseMotion.new()
	motion.position = viewport_pos
	motion.global_position = view.get_global_transform_with_canvas() * viewport_pos
	view.gui_input.emit(motion)
	var hover: MeshInstance3D = world.get_node_or_null("Overlay/Hover")
	assert_not_null(hover, "Overlay/Hover")
	if hover == null:
		return
	assert_true(hover.visible, "hover is showing")
	assert_eq(world.world_to_grid(hover.position), square,
		"hover sits on the tile under the cursor")


func test_world_inside_a_subviewport_does_not_rehandle_the_pointer() -> void:
	# Chrome already forwarded the Control-local event. A second pass through
	# World._unhandled_input would divide by scale again.
	Table.set_started()
	var packed: PackedScene = load("res://chrome/chrome.tscn")
	assert_not_null(packed, "chrome.tscn")
	if packed == null:
		return
	var chrome: Node = packed.instantiate()
	add_child_autofree(chrome)
	await wait_process_frames(4)
	var world: Node3D = chrome.get_node_or_null("%WorldView/SubViewport/World")
	assert_not_null(world, "World sits in the playfield SubViewport")
	if world == null:
		return
	assert_true(world.get_viewport() is SubViewport)
	var motion := InputEventMouseMotion.new()
	motion.position = Vector2(800, 400)
	motion.global_position = Vector2(800, 400)
	Net.outbound.clear()
	world._unhandled_input(motion)
	world._unhandled_input(InputEventMouseButton.new())
	var hover: MeshInstance3D = world.get_node_or_null("Overlay/Hover")
	if hover != null:
		assert_false(hover.visible, "SubViewport World must not place hover from _unhandled_input")


func test_debug_buttons_are_locked_while_the_dm_has_the_floor() -> void:
	var bar := _debug_bar()
	for child in bar.get_children():
		assert_false((child as Button).disabled, "floor is clear after reset")

	Table.awaiting_dm = true
	Table.started_changed.emit()
	for child in bar.get_children():
		assert_true((child as Button).disabled)

	Table.awaiting_dm = false
	Table.transcript_changed.emit()
	for child in bar.get_children():
		assert_false((child as Button).disabled)


# ---- Way-out confirm (emberdelve-4h9.4)

func _leave_confirm() -> Control:
	var script: GDScript = load("res://chrome/leave_confirm.gd")
	assert_not_null(script, "leave_confirm.gd")
	if script == null:
		return Control.new()
	var node: Control = script.new()
	add_child_autofree(node)
	return node


func test_way_out_confirm_names_whether_the_reliquary_is_carried() -> void:
	var scene := CRYPT.duplicate(true)
	scene["exits"] = [{
		"id": "stair-south", "x": 6, "y": 0, "direction": "SOUTH",
		"toRoomId": "", "wayOut": true,
	}]
	scene["holdingObjective"] = true
	Table.set_scene(SceneFixtures.scene(scene))
	Table.cross_exit("stair-south")
	var confirm := _leave_confirm()
	await wait_frames(1)
	assert_true(confirm.visible)
	var prompt: Label = confirm.find_child("Prompt", true, false)
	assert_not_null(prompt, "Prompt")
	if prompt == null:
		return
	assert_eq(prompt.text, "Leave the site, with the reliquary?")
	assert_not_null(confirm.find_child("Leave", true, false), "LEAVE")
	assert_not_null(confirm.find_child("Stay", true, false), "STAY")

	Table.stay()
	await wait_frames(1)
	assert_false(confirm.visible)


func test_way_out_confirm_without_the_objective_says_without() -> void:
	var scene := CRYPT.duplicate(true)
	scene["exits"] = [{
		"id": "stair-south", "x": 6, "y": 0, "direction": "SOUTH",
		"toRoomId": "", "wayOut": true,
	}]
	scene["holdingObjective"] = false
	Table.set_scene(SceneFixtures.scene(scene))
	Table.cross_exit("stair-south")
	var confirm := _leave_confirm()
	await wait_frames(1)
	var prompt: Label = confirm.find_child("Prompt", true, false)
	assert_not_null(prompt, "Prompt")
	if prompt == null:
		return
	assert_eq(prompt.text, "Leave the site, without the reliquary?")
