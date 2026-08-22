extends GutTest

## Smoke for the sting module. There is nothing behavioural to assert about audio — the
## listening test is Task 13 — but the public API must exist, land on the right buses, and
## not push errors when called. `move_seconds` is the one number with an independent source
## of truth (`client/src/scene/tokens.ts`).


func test_the_sting_api_plays_without_errors_on_the_world_and_dice_buses() -> void:
	var sfx: Node = load("res://audio/sfx.gd").new()
	add_child_autofree(sfx)
	await wait_process_frames(1)

	for method in [
		"lid_opens", "revealed", "footsteps", "combat_begins",
		"initiative_set", "swing", "fell", "move_seconds",
	]:
		assert_has_method(sfx, method)
	if not sfx.has_method("move_seconds"):
		return

	# Literals from tokens.ts: min(0.18 + squares * 0.07, 0.75).
	assert_almost_eq(sfx.move_seconds(1), 0.25, 0.0001)
	assert_almost_eq(sfx.move_seconds(10), 0.75, 0.0001)

	sfx.lid_opens()
	sfx.revealed()
	sfx.footsteps(3)
	sfx.combat_begins()
	sfx.initiative_set(2)
	sfx.swing(false)
	sfx.swing(true)
	sfx.fell()

	var world := 0
	var dice := 0
	for child in sfx.get_children():
		if child is AudioStreamPlayer:
			match String(child.bus):
				"World":
					world += 1
				"Dice":
					dice += 1
	assert_gt(world, 0, "pool players on the World bus")
	assert_gt(dice, 0, "pool players on the Dice bus")
	assert_engine_error_count(0)
	assert_push_error_count(0)
