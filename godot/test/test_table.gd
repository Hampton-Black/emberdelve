extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [{"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 0,
		"hidden": false}],
	"entities": [{"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
		"hp": 12, "maxHp": 12, "isPlayerControlled": true}],
}

const GOBLIN := {"id": "goblin", "kind": "goblin", "name": "Vessk", "x": 8, "y": 6,
	"hp": 7, "maxHp": 7, "isPlayerControlled": false}


func before_each() -> void:
	Table.reset()
	Clock.silence_now()
	Table.set_scene(SceneFixtures.scene(CRYPT))
	await get_tree().process_frame
	# Swapped in only after the old backend's deferred `finished` has flushed above: test_clock
	# leaves a HeldVoice installed on the shared Clock, and it holds every line in the air.
	Clock.use_backend(VoiceBackend.new())


func test_the_scene_arrives_whole() -> void:
	assert_eq(Table.scene["roomId"], "crypt")
	assert_eq(Table.mode, "EXPLORATION")


func test_mode_rides_with_the_scene_so_a_reconnect_lands_mid_fight_intact() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	Table.set_scene(SceneFixtures.scene(fighting))
	assert_eq(Table.mode, "COMBAT")


func test_a_spawned_entity_joins_the_scene() -> void:
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 2)
	assert_eq(Table.entity("goblin")["name"], "Vessk")


func test_spawning_the_same_id_twice_replaces_rather_than_duplicates() -> void:
	# M0's goblin has a hardcoded id, so a second spawn replaces the first server-side.
	# Appending here would leave a phantom behind.
	var moved := GOBLIN.duplicate()
	moved["x"] = 9
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	Table.apply_diffs([{"kind": "EntityAdded", "entity": moved}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 2)
	assert_eq(Table.entity("goblin")["x"], 9)


func test_a_moved_entity_keeps_everything_but_its_square() -> void:
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "fighter",
		"fromX": 2, "fromY": 2, "x": 4, "y": 5}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["x"], 4)
	assert_eq(Table.entity("fighter")["y"], 5)
	assert_eq(Table.entity("fighter")["hp"], 12)


func test_hit_points_change_and_nothing_else_does() -> void:
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "hp", "from": 12, "to": 5}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["hp"], 5)


func test_a_stat_that_is_not_hp_is_ignored() -> void:
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "morale", "from": 1, "to": 0}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["hp"], 12)


func test_a_removed_entity_leaves_the_scene() -> void:
	Table.apply_diffs([{"kind": "EntityAdded", "entity": GOBLIN}])
	Table.apply_diffs([{"kind": "EntityRemoved", "entityId": "goblin"}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 1)


func test_a_revealed_prop_joins_the_room() -> void:
	var alcove := {"id": "alcove", "type": "ALCOVE", "x": 9, "y": 3, "rotation": 0,
		"hidden": false}
	Table.apply_diffs([{"kind": "PropRevealed", "prop": alcove}])
	await wait_frames(2)
	assert_eq(Table.room()["props"].size(), 2)


func test_revealing_the_same_prop_twice_replaces_rather_than_duplicates() -> void:
	var tomb := {"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 1,
		"hidden": false}
	Table.apply_diffs([{"kind": "PropRevealed", "prop": tomb}])
	await wait_frames(2)
	assert_eq(Table.room()["props"].size(), 1)
	assert_eq(Table.room()["props"][0]["rotation"], 1)


func test_the_mode_flips() -> void:
	Table.apply_diffs([{"kind": "ModeChanged", "mode": "COMBAT"}])
	await wait_frames(2)
	assert_eq(Table.mode, "COMBAT")


func test_a_batch_emits_exactly_one_change() -> void:
	# Godot's idiom is mutate-and-emit, and a signal per field would have the world rebuilding
	# halfway through a batch that has moved a token but not yet changed its hit points.
	# Godot captures lambda locals by value; the Array is the reference the lambda may mutate.
	var changes := [0]
	var counter := func() -> void: changes[0] += 1
	Table.scene_changed.connect(counter)
	Table.apply_diffs([
		# Const fixtures are read-only; production mutates in place.
		{"kind": "EntityAdded", "entity": GOBLIN.duplicate()},
		{"kind": "EntityMoved", "entityId": "goblin", "fromX": 8, "fromY": 6, "x": 7, "y": 6},
		{"kind": "StatChanged", "entityId": "goblin", "stat": "hp", "from": 7, "to": 3},
	])
	await wait_frames(2)
	Table.scene_changed.disconnect(counter)
	assert_eq(changes[0], 1)


func test_diffs_wait_for_the_clock() -> void:
	# A hit point bar that empties while the attack die is still in the air has answered the
	# question the die was asking.
	Clock.hold(400, func() -> void: pass)
	Table.apply_diffs([{"kind": "StatChanged", "entityId": "fighter",
		"stat": "hp", "from": 12, "to": 5}])
	await wait_frames(3)
	assert_eq(Table.entity("fighter")["hp"], 12, "the die is still in the air")
	await wait_seconds(0.5)
	assert_eq(Table.entity("fighter")["hp"], 5)


func test_with_the_clock_empty_a_diff_applies_on_the_spot() -> void:
	# Every move and every reveal the player makes for themselves. The 100ms budget lives here.
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "fighter",
		"fromX": 2, "fromY": 2, "x": 3, "y": 2}])
	await wait_frames(2)
	assert_eq(Table.entity("fighter")["x"], 3)


func test_an_unknown_entity_id_is_ignored_not_fatal() -> void:
	Table.apply_diffs([{"kind": "EntityMoved", "entityId": "ghost",
		"fromX": 0, "fromY": 0, "x": 1, "y": 1}])
	await wait_frames(2)
	assert_eq(Table.scene["entities"].size(), 1)


func test_room_lighting_changed_writes_the_preset_onto_the_room() -> void:
	Table.apply_diffs([{"kind": "RoomLightingChanged", "roomId": "crypt", "lighting": "DARK"}])
	await wait_frames(2)
	assert_eq(Table.room()["lighting"], "DARK")
	assert_eq(Table.room_by_id("crypt")["lighting"], "DARK")


func test_can_rest_changed_writes_the_hint_onto_the_scene() -> void:
	Table.apply_diffs([{"kind": "CanRestChanged", "canRest": false}])
	await wait_frames(2)
	assert_eq(Table.scene["canRest"], false)


func test_party_light_changed_writes_the_level_onto_the_scene() -> void:
	Table.apply_diffs([{"kind": "PartyLightChanged", "partyLight": "LOW"}])
	await wait_frames(2)
	assert_eq(Table.scene["partyLight"], "LOW")


func test_a_placed_marker_joins_the_scene() -> void:
	Table.apply_diffs([{
		"kind": "MarkerPlaced",
		"marker": {"id": "marker-scorch", "tag": "SCORCH", "x": 9, "y": 6},
	}])
	await wait_frames(2)
	assert_eq(Table.scene["markers"].size(), 1)
	assert_eq(Table.marker("marker-scorch")["tag"], "SCORCH")
	assert_false(Table.marker("marker-scorch").has("text"),
		"free-form text never reaches the renderer")


func test_prop_removed_of_a_prize_does_not_claim_the_objective() -> void:
	var scene := CRYPT.duplicate(true)
	scene["props"] = [
		{"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 0, "hidden": false},
		{"id": "gold-chest", "type": "CONTAINER", "x": 3, "y": 3, "rotation": 0, "hidden": false},
	]
	scene["holdingObjective"] = false
	Table.set_scene(SceneFixtures.scene(scene))
	Table.apply_diffs([{
		"kind": "PropRemoved", "propId": "gold-chest", "holdingObjective": false,
	}])
	await wait_frames(2)
	assert_false(bool(Table.scene.get("holdingObjective", true)),
		"taking something that is not the reliquary must not mark it held")
	assert_eq(Table.room()["props"].size(), 1)
	assert_eq(String(Table.room()["props"][0]["id"]), "tomb")


func test_prop_removed_of_the_reliquary_keeps_held() -> void:
	var scene := CRYPT.duplicate(true)
	scene["props"] = [
		{"id": "reliquary", "type": "CONTAINER", "x": 4, "y": 2, "rotation": 0, "hidden": false},
	]
	scene["holdingObjective"] = false
	Table.set_scene(SceneFixtures.scene(scene))
	Table.apply_diffs([{
		"kind": "PropRemoved", "propId": "reliquary", "holdingObjective": true,
	}])
	await wait_frames(2)
	assert_true(bool(Table.scene.get("holdingObjective", false)))


# ---- The transcript, paced by the voice

func test_the_players_own_line_lands_immediately() -> void:
	Table.say_as_player("heave the lid open")
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["speakerId"], "player")
	assert_true(Table.awaiting_dm)


func test_crossing_a_door_silences_the_clock_and_waits_on_the_dm() -> void:
	var notes: Array[String] = []
	Clock.speak({"speakerId": "narrator", "text": "in the air"}, func() -> void:
		notes.append("in the air"))
	await wait_frames(2)
	Clock.speak({"speakerId": "narrator", "text": "dropped"}, func() -> void:
		notes.append("dropped"))
	Net.outbound.clear()
	Table.cross_exit("door-north")
	assert_true(Table.awaiting_dm)
	assert_eq(notes, ["in the air", "dropped"])
	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")


func test_a_way_out_click_asks_and_stay_does_not_send() -> void:
	var scene := CRYPT.duplicate(true)
	scene["exits"] = [{
		"id": "stair-south", "x": 6, "y": 0, "direction": "SOUTH",
		"toRoomId": "", "wayOut": true,
	}]
	scene["holdingObjective"] = true
	Table.set_scene(SceneFixtures.scene(scene))
	Net.outbound.clear()

	Table.cross_exit("stair-south")
	assert_eq(Net.outbound.size(), 0)
	assert_eq(String(Table.leave_confirm.get("exit_id", "")), "stair-south")
	assert_true(bool(Table.leave_confirm.get("holding", false)))
	assert_false(Table.awaiting_dm)

	Table.stay()
	assert_eq(Net.outbound.size(), 0)
	assert_true(Table.leave_confirm.is_empty())


func test_confirming_leave_sends_enter_exit() -> void:
	var scene := CRYPT.duplicate(true)
	scene["exits"] = [{
		"id": "stair-south", "x": 6, "y": 0, "direction": "SOUTH",
		"toRoomId": "", "wayOut": true,
	}]
	Table.set_scene(SceneFixtures.scene(scene))
	Net.outbound.clear()
	Table.cross_exit("stair-south")
	Table.confirm_leave()
	assert_eq(Net.outbound.size(), 1)
	assert_eq(String(Net.outbound[0].get("type", "")), "enterExit")
	assert_eq(String(Net.outbound[0].get("exitId", "")), "stair-south")


func test_a_new_turn_drops_the_rest_of_the_last_one() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "one"})
	Table.append_narration({"speakerId": "narrator", "text": "two"})
	Table.say_as_player("wait")
	await wait_frames(3)
	# Dropped lines still reveal — the player interrupted the speech, not the record.
	# Brief printed 3 entries; the two dropped lines share a speaker, and the paragraph merge
	# pinned above joins them on reveal (the TypeScript merges here too), so the record is two.
	assert_eq(Table.transcript.size(), 2)
	assert_eq(Table.transcript[0]["text"], "one two")
	assert_eq(Table.transcript[1]["speakerId"], "player")


func test_consecutive_lines_from_one_speaker_become_one_paragraph() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "The lid grinds."})
	Table.append_narration({"speakerId": "narrator", "text": "Dust falls."})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["text"], "The lid grinds. Dust falls.")


func test_a_speaker_change_starts_a_new_paragraph() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "It speaks."})
	Table.append_narration({"speakerId": "goblin", "text": "You woke me."})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 2)


func test_the_dm_gives_up_the_floor_behind_the_queue() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "done"})
	Table.end_narration()
	await wait_frames(4)
	assert_false(Table.awaiting_dm)


# ---- Rolls

func test_every_roll_reaches_the_log() -> void:
	Table.add_roll(_attack([14], "MISS"))
	# Brief printed 2.0s; an attack's gate is reveal_at(1) + IMPACT_BEAT_MS = 2110ms, so the
	# log entry lands at ~2.1s and a 2.0s wait asserts inside the gate.
	await wait_seconds(2.3)
	assert_eq(Table.rolls.size(), 1)
	assert_eq(Table.transcript.size(), 1)
	assert_eq(Table.transcript[0]["kind"], "roll")


func test_a_damage_roll_goes_straight_to_the_log_without_being_thrown() -> void:
	var damage := _attack([5], "SUCCESS")
	damage["request"]["purpose"] = "DAMAGE"
	Table.add_roll(damage)
	await wait_frames(3)
	assert_eq(Table.rolls.size(), 1)
	assert_null(Table.active_roll, "damage does not animate")


func test_a_dramatic_roll_is_thrown_before_it_is_logged() -> void:
	Table.add_roll(_attack([14], "MISS"))
	await wait_frames(3)
	assert_not_null(Table.active_roll, "the die is in the air")
	assert_eq(Table.transcript.size(), 0, "the log lags — printing the total spoils the throw")
	# Not in the brief: wait out the gate. The drain is parked on the hold's timer, which
	# silence() cannot cancel — ending here would stall the next test's queue behind ~2s
	# left on this one's die.
	await wait_seconds(2.3)


func test_narration_waits_for_the_dice_to_land() -> void:
	Table.add_roll(_attack([14], "MISS"))
	Table.append_narration({"speakerId": "narrator", "text": "The blade goes wide."})
	await wait_frames(3)
	assert_eq(Table.transcript.size(), 0)
	# reveal_at(1) + IMPACT_BEAT_MS = 1460 + 650
	await wait_seconds(2.3)
	assert_eq(Table.transcript.size(), 2)
	assert_eq(Table.transcript[0]["kind"], "roll", "the dice decide, then the DM speaks")


func test_a_strike_is_published_once_its_dice_have_landed() -> void:
	var struck: Array[Dictionary] = []
	var watch := func(a, t, c, at) -> void:
		struck.append({"actorId": a, "targetId": t, "connected": c, "at": at})
	Table.strike.connect(watch)
	Table.add_roll(_attack([18], "HIT"))
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(struck.size(), 1)
	assert_eq(struck[0]["targetId"], "goblin")
	assert_true(struck[0]["connected"])


func test_two_identical_misses_are_two_separate_events() -> void:
	# A notification, not state. Two identical misses in a row must not collapse into one.
	# Godot captures lambda locals by value; the Array is the reference the lambda may mutate.
	var count := [0]
	var watch := func(_a, _t, _c, _at) -> void: count[0] += 1
	Table.strike.connect(watch)
	Table.add_roll(_attack([3], "MISS"))
	await wait_seconds(2.3)
	Table.add_roll(_attack([3], "MISS"))
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(count[0], 2)


func test_an_attack_with_no_target_publishes_no_strike() -> void:
	var check := _attack([17], "SUCCESS")
	check["request"]["purpose"] = "SKILL_CHECK"
	check["request"]["targetId"] = null
	# Godot captures lambda locals by value; the Array is the reference the lambda may mutate.
	# (With the brief's `count := 0` this test passes vacuously — the capture never increments.)
	var count := [0]
	var watch := func(_a, _t, _c, _at) -> void: count[0] += 1
	Table.strike.connect(watch)
	Table.add_roll(check)
	await wait_seconds(2.3)
	Table.strike.disconnect(watch)
	assert_eq(count[0], 0)


# ---- The fight

func test_a_fight_opening_holds_the_floor_for_the_ceremony() -> void:
	assert_eq(Table.CEREMONY_MS, 1200)
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat()},
	])
	# Not in the brief: a frame between the diffs and the line. Godot's drain starts deferred
	# (Task 5), so the ceremony hold is only queued once the diffs apply — on the wire the two
	# never arrive in one synchronous burst. Without the frame the line queues ahead of the
	# hold and talks over the beat.
	await wait_frames(2)
	Table.append_narration({"speakerId": "narrator", "text": "Steel comes out."})
	# FRAMING_SECONDS is 1.1; the hold must cover the pull-back. 0.9s is still inside both.
	await wait_seconds(0.9)
	assert_eq(Table.transcript.size(), 0, "narration must not land during the camera pull-back")
	await wait_seconds(0.5)
	assert_eq(Table.transcript.size(), 1)


func test_the_bar_outlives_the_fight_so_it_has_names_to_draw_while_it_dissolves() -> void:
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "COMBAT"},
		{"kind": "CombatChanged", "combat": _combat()},
	])
	await wait_seconds(1.4)
	Table.apply_diffs([
		{"kind": "ModeChanged", "mode": "EXPLORATION"},
		{"kind": "CombatChanged", "combat": null},
	])
	await wait_frames(3)
	assert_not_null(Table.combat_beat)
	assert_not_null(Table.combat_beat["closing_at"])
	assert_eq(Table.combat_beat["view"]["order"].size(), 2)


func test_a_reconnect_mid_fight_does_not_replay_the_ceremony() -> void:
	# Backdated past the ceremony on purpose: replaying the drums would announce something that
	# happened minutes ago.
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	fighting["combat"] = _combat()
	Table.set_scene(SceneFixtures.scene(fighting))
	Table.append_narration({"speakerId": "narrator", "text": "still here"})
	await wait_frames(4)
	assert_eq(Table.transcript.size(), 1, "nothing is held for a fight already under way")


# ---- Errors bypass the queue

func test_an_error_cuts_dead_and_lifts_the_hold() -> void:
	Table.append_narration({"speakerId": "narrator", "text": "mid-sentence"})
	Table.set_error("The DM stumbled")
	assert_eq(Table.error_message, "The DM stumbled")
	assert_false(Table.awaiting_dm)


# ---- Restart goes all the way back to the title

func test_reset_returns_to_the_title() -> void:
	# The server clears its once-per-session opening guard on restart and then waits to be asked
	# again. Only `begin` asks it, and only the title sends `begin`. A client that stays
	# `started` after a restart lays out a fresh room and sits in silence forever.
	Table.set_started()
	Table.say_as_player("something")
	Table.reset()
	assert_false(Table.started)
	assert_eq(Table.transcript, [])
	assert_eq(Table.rolls, [])
	assert_null(Table.combat_beat)


func test_set_scene_recognises_a_restart() -> void:
	# Flag set → reset called → started cleared. The fresh scene is still laid out; only the
	# session is thrown away. A client that returned before applying it would sit on an empty
	# board, and one that kept `started` would never send `begin` again.
	Table.set_started()
	Table.say_as_player("something")
	Table.expect_restart()
	Table.set_scene(SceneFixtures.scene(CRYPT))
	assert_false(Table.started)
	assert_false(Table._restarting)
	assert_eq(Table.transcript, [])
	assert_null(Table.combat_beat)
	assert_eq(Table.scene["roomId"], "crypt")
	assert_eq(Table.entity("fighter")["hp"], 12)


func test_an_ordinary_scene_does_not_end_the_session() -> void:
	Table.set_started()
	Table.set_scene(SceneFixtures.scene(CRYPT))
	assert_true(Table.started)
	assert_eq(Table.entity("fighter")["name"], "Roderick")


# ---- Fixtures

func _attack(faces: Array, outcome: String) -> Dictionary:
	return {
		"request": {"dice": "1d20", "modifier": 5, "advantage": "NORMAL", "purpose": "ATTACK",
			"actorId": "fighter", "targetId": "goblin", "dc": 15, "skill": null},
		"faces": faces, "total": int(faces[0]) + 5, "outcome": outcome,
	}


func _combat() -> Dictionary:
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
