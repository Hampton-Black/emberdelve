extends GutTest

var seen: Array[Dictionary]


func before_each() -> void:
	seen = []
	Net.scene.connect(func(s): seen.append({"kind": "scene", "value": s}))
	Net.diffs.connect(func(d): seen.append({"kind": "diffs", "value": d}))
	Net.roll.connect(func(r): seen.append({"kind": "roll", "value": r}))
	Net.narration.connect(func(s): seen.append({"kind": "narration", "value": s}))
	Net.narration_end.connect(func(): seen.append({"kind": "narrationEnd", "value": null}))
	Net.error.connect(func(m): seen.append({"kind": "error", "value": m}))
	Net.hello.connect(func(d, v, x): seen.append({"kind": "hello",
		"value": {"demoMode": d, "voice": v, "dm": x}}))


func after_each() -> void:
	for connection in [Net.scene, Net.diffs, Net.roll, Net.narration, Net.narration_end,
			Net.error, Net.hello]:
		for c in connection.get_connections():
			connection.disconnect(c["callable"])


func test_hello_carries_all_three_flags() -> void:
	Net.dispatch({"type": "hello", "demoMode": true, "voice": false, "dm": true})
	assert_eq(seen[0]["kind"], "hello")
	assert_eq(seen[0]["value"]["dm"], true)
	assert_eq(seen[0]["value"]["voice"], false)


func test_an_old_server_without_the_dm_field_is_read_as_no_dm() -> void:
	Net.dispatch({"type": "hello", "demoMode": false, "voice": false})
	assert_eq(seen[0]["value"]["dm"], false)


func test_a_scene_arrives_whole() -> void:
	Net.dispatch({"type": "scene", "scene": {"roomId": "crypt", "width": 12}})
	assert_eq(seen[0]["kind"], "scene")
	assert_eq(seen[0]["value"]["roomId"], "crypt")


func test_diffs_arrive_as_one_batch() -> void:
	Net.dispatch({"type": "diffs", "diffs": [
		{"kind": "EntityMoved", "entityId": "goblin"},
		{"kind": "StatChanged", "entityId": "fighter"},
	]})
	assert_eq(seen.size(), 1, "one signal per batch, never one per diff")
	assert_eq(seen[0]["value"].size(), 2)


func test_narration_end_has_no_payload() -> void:
	Net.dispatch({"type": "narrationEnd"})
	assert_eq(seen[0]["kind"], "narrationEnd")


func test_a_roll_keeps_its_faces_as_a_list() -> void:
	# Invariant #5. Never collapsed to a total before the tray.
	Net.dispatch({"type": "roll", "result": {"faces": [18, 4], "total": 23}})
	assert_eq(seen[0]["value"]["faces"], [18, 4])


func test_an_unknown_message_type_is_ignored_not_fatal() -> void:
	Net.dispatch({"type": "somethingFromTheFuture"})
	assert_eq(seen, [])


func test_unparseable_json_is_ignored_not_fatal() -> void:
	Net.receive("{ this is not json")
	assert_eq(seen, [])
	# The parse error and push_error are intentional (M0: log and surface); do not "fix" them away.
	assert_engine_error("error != Error::OK")
	assert_push_error("unparseable server message")
