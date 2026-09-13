extends GutTest

## Casting keys on entity kind, not on speaker id. Parser speaker ids are entity ids, so
## goblin-2 and brute must resolve through Table.entity before the voice table is consulted.

const GOBLIN := {"id": "goblin", "kind": "goblin", "name": "Vessk", "x": 8, "y": 6,
	"hp": 6, "maxHp": 6, "isPlayerControlled": false}
const GOBLIN_TWO := {"id": "goblin-2", "kind": "goblin", "name": "Skrix", "x": 7, "y": 6,
	"hp": 6, "maxHp": 6, "isPlayerControlled": false}
const BRUTE := {"id": "brute", "kind": "brute", "name": "Brakk", "x": 5, "y": 5,
	"hp": 16, "maxHp": 16, "isPlayerControlled": false}
const FIGHTER := {"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
	"hp": 12, "maxHp": 12, "isPlayerControlled": true}


func before_each() -> void:
	Table.reset()
	Table.set_scene({
		"roomId": "crypt", "mode": "EXPLORATION", "combat": null,
		"entities": [FIGHTER, GOBLIN, GOBLIN_TWO, BRUTE],
		"rooms": [{"roomId": "crypt", "width": 12, "height": 12,
			"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
			"props": [], "exits": [], "originX": 0.0, "originZ": 0.0, "visited": true,
			"coveredWalls": []}],
	})


func test_goblin_two_uses_the_hostile_voice_not_the_narrator_default() -> void:
	var hostile := Casting.for_speaker("goblin")
	var second := Casting.for_speaker("goblin-2")
	assert_eq(second, hostile, "kind goblin, id goblin-2 — same voice as the first goblin")
	assert_ne(second, Casting.DEFAULT, "not the narrator fallback")


func test_brute_uses_the_hostile_voice_not_the_narrator_default() -> void:
	var hostile := Casting.for_speaker("goblin")
	var brute := Casting.for_speaker("brute")
	assert_eq(brute, hostile, "brute shares the hostile voice")
	assert_ne(brute, Casting.DEFAULT, "not the narrator fallback")


func test_narrator_and_fighter_are_unchanged() -> void:
	assert_eq(Casting.for_speaker("narrator"), Casting.TABLE["narrator"])
	assert_eq(Casting.for_speaker("fighter"), Casting.TABLE["fighter"])
