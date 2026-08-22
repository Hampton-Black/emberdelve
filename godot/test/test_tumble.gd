extends GutTest

const Tumble := preload("res://dice/tumble.gd")


func _roll(purpose: String, faces: Array, opts: Dictionary = {}) -> Dictionary:
	return {
		"request": {
			"dice": opts.get("dice", "1d20"),
			"modifier": opts.get("modifier", 0),
			"advantage": opts.get("advantage", "NORMAL"),
			"purpose": purpose,
			"actorId": "fighter",
			"targetId": opts.get("targetId", null),
			"dc": opts.get("dc", null),
			"skill": opts.get("skill", null),
		},
		"faces": faces,
		"total": opts.get("total", 0),
		"outcome": opts.get("outcome", "SUCCESS"),
	}


# --- Timing. These must match tumble.ts exactly or the dice and the voice drift apart.

func test_the_first_die_lands_at_wind_up_plus_flight() -> void:
	assert_eq(Tumble.land_at(0), 1060)

func test_each_later_die_lands_a_stagger_behind() -> void:
	assert_eq(Tumble.land_at(1), 1190)
	assert_eq(Tumble.land_at(2), 1320)

func test_one_die_becomes_legible_after_its_bounce() -> void:
	assert_eq(Tumble.reveal_at(1), 1460)

func test_two_dice_become_legible_after_the_last_one_bounces() -> void:
	assert_eq(Tumble.reveal_at(2), 1590)

func test_a_zero_die_roll_does_not_go_backwards() -> void:
	assert_eq(Tumble.reveal_at(0), 1460)


# --- Which rolls get thrown. Purpose decides, never who rolled (AGENTS.md §Dice).

func test_attacks_saves_and_skill_checks_are_dramatic() -> void:
	assert_true(Tumble.is_dramatic(_roll("ATTACK", [14])))
	assert_true(Tumble.is_dramatic(_roll("SAVE", [9])))
	assert_true(Tumble.is_dramatic(_roll("SKILL_CHECK", [17])))

func test_damage_and_initiative_go_straight_to_the_log() -> void:
	assert_false(Tumble.is_dramatic(_roll("DAMAGE", [5])))
	assert_false(Tumble.is_dramatic(_roll("INITIATIVE", [12])))

func test_a_goblins_attack_is_as_dramatic_as_the_players() -> void:
	# The tensest die in the game is the one thrown at you.
	var incoming := _roll("ATTACK", [18])
	incoming["request"]["actorId"] = "goblin"
	assert_true(Tumble.is_dramatic(incoming))


# --- The one rule: the server decides, the animation displays.

func test_a_settled_die_shows_exactly_what_the_server_sent() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(0), 99999)
	assert_true(visual["dice"][0]["settled"])
	assert_eq(visual["dice"][0]["face"], 17)

func test_every_die_shows_its_own_server_face_once_settled() -> void:
	var result := _roll("ATTACK", [3, 20], {"advantage": "ADVANTAGE"})
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(1), 99999)
	assert_eq(visual["dice"][0]["face"], 3)
	assert_eq(visual["dice"][1]["face"], 20)

func test_a_tumbling_face_is_a_legal_face_but_not_yet_the_result() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, 400, 99999)
	assert_false(visual["dice"][0]["settled"])
	assert_between(visual["dice"][0]["face"], 1, 20)

func test_a_tumbling_face_is_stable_for_a_given_instant() -> void:
	# Derived from the clock, not from randf, so a dropped frame does not make dice stutter.
	var result := _roll("ATTACK", [17])
	var a: Dictionary = Tumble.sample(result, 400, 99999)
	var b: Dictionary = Tumble.sample(result, 400, 99999)
	assert_eq(a["dice"][0]["face"], b["dice"][0]["face"])

func test_the_kept_die_is_first_and_the_rest_are_discards() -> void:
	var result := _roll("ATTACK", [18, 4], {"advantage": "ADVANTAGE"})
	var visual: Dictionary = Tumble.sample(result, Tumble.land_at(1), 99999)
	assert_false(visual["dice"][0]["discarded"])
	assert_true(visual["dice"][1]["discarded"])

func test_the_tray_finishes_after_its_fade() -> void:
	var result := _roll("ATTACK", [17])
	var visual: Dictionary = Tumble.sample(result, 3000 + Tumble.FADE_OUT_MS + 1, 3000)
	assert_true(visual["finished"])


# --- Wording, shared by the tray and the log so a success is never worded two ways.

func test_a_skill_check_is_named_by_its_skill() -> void:
	var result := _roll("SKILL_CHECK", [17], {"skill": "ATHLETICS", "dc": 20, "total": 22,
		"modifier": 5, "outcome": "SUCCESS"})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["label"], "ATHLETICS CHECK")
	assert_eq(cap["target"], "DC 20")
	assert_eq(cap["arithmetic"], "17 + 5 = 22")
	assert_eq(cap["outcome"], "SUCCESS")
	assert_eq(cap["tone"], "good")

func test_an_attack_is_read_against_ac_not_dc() -> void:
	var result := _roll("ATTACK", [14], {"dc": 15, "total": 19, "modifier": 5, "outcome": "HIT"})
	assert_eq(Tumble.caption(result)["target"], "AC 15")

func test_a_dc_of_zero_is_still_printed() -> void:
	# `if dc:` is false for 0. The wire says null when there is no DC, and 0 is a real DC.
	var result := _roll("SAVE", [10], {"dc": 0, "total": 10})
	assert_eq(Tumble.caption(result)["target"], "DC 0")

func test_no_dc_prints_nothing() -> void:
	assert_eq(Tumble.caption(_roll("DAMAGE", [5]))["target"], "")

func test_damage_is_not_captioned_as_a_thing_that_could_have_failed() -> void:
	var result := _roll("DAMAGE", [5], {"modifier": 3, "total": 8})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["label"], "DAMAGE")
	assert_eq(cap["outcome"], "")
	assert_eq(cap["tone"], "neutral")

func test_a_crit_is_spelled_out() -> void:
	var result := _roll("ATTACK", [20], {"dc": 15, "total": 25, "modifier": 5, "outcome": "CRIT"})
	var cap: Dictionary = Tumble.caption(result)
	assert_eq(cap["outcome"], "CRITICAL")
	assert_eq(cap["tone"], "crit")

func test_a_fumble_is_spelled_out() -> void:
	var result := _roll("ATTACK", [1], {"outcome": "CRIT_FAIL"})
	assert_eq(Tumble.caption(_roll("ATTACK", [1], {"outcome": "CRIT_FAIL"}))["outcome"], "FUMBLE")
	assert_eq(Tumble.caption(result)["tone"], "fumble")

func test_a_lone_unmodified_die_prints_no_arithmetic() -> void:
	var result := _roll("SAVE", [12], {"total": 12})
	assert_eq(Tumble.caption(result)["arithmetic"], "12")

func test_only_the_kept_die_is_counted_on_advantage() -> void:
	var result := _roll("ATTACK", [18, 4], {"advantage": "ADVANTAGE", "modifier": 5, "total": 23})
	assert_eq(Tumble.caption(result)["arithmetic"], "18 + 5 = 23")

func test_a_negative_modifier_reads_as_a_subtraction() -> void:
	var result := _roll("SAVE", [12], {"modifier": -2, "total": 10})
	assert_eq(Tumble.caption(result)["arithmetic"], "12 − 2 = 10")


# --- How many sides to draw. Presentation only; nothing is adjudicated here.

func test_sides_are_read_off_the_expression() -> void:
	assert_eq(Tumble.dice_sides("1d20"), 20)
	assert_eq(Tumble.dice_sides("2d6"), 6)
	assert_eq(Tumble.dice_sides("d8"), 8)

func test_an_unreadable_expression_draws_a_d20() -> void:
	assert_eq(Tumble.dice_sides("nonsense"), 20)
