extends GutTest

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
	Table.set_scene(CRYPT.duplicate(true))
	await get_tree().process_frame


func test_the_scene_arrives_whole() -> void:
	assert_eq(Table.scene["roomId"], "crypt")
	assert_eq(Table.mode, "EXPLORATION")


func test_mode_rides_with_the_scene_so_a_reconnect_lands_mid_fight_intact() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["mode"] = "COMBAT"
	Table.set_scene(fighting)
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
	assert_eq(Table.scene["props"].size(), 2)


func test_revealing_the_same_prop_twice_replaces_rather_than_duplicates() -> void:
	var tomb := {"id": "tomb", "type": "SARCOPHAGUS", "x": 6, "y": 6, "rotation": 1,
		"hidden": false}
	Table.apply_diffs([{"kind": "PropRevealed", "prop": tomb}])
	await wait_frames(2)
	assert_eq(Table.scene["props"].size(), 1)
	assert_eq(Table.scene["props"][0]["rotation"], 1)


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
