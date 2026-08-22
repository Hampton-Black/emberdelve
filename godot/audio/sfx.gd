extends Node

## The layered stings: things opening, walking, and hitting.
##
## Ported from client/src/audio/sfx.ts, world.ts and combat.ts. Every gain, rate and delay
## transfers unchanged. [code]rate[/code] is [member AudioStreamPlayer.pitch_scale]; the
## random pitch jitter from sfx.ts stays, because without it the clatter sounds canned by
## the third roll.
##
## [code]drop()[/code] and [code]drum()[/code] have no Godot equivalent — they were Web Audio
## oscillators. Each setting is a wav baked offline under [code]res://audio/samples/drop_*.wav[/code].
##
## Scheduling is a [code]SceneTreeTimer[/code] per layer, not the audio clock. About 8ms of
## jitter at 60Hz; a listening test, not a unit test (parity gate item 7).

# Kenney packs, grouped by the moment each clip belongs to. Several families are the same
# samples at different speeds: rate is doing real work, and a metal hit dragged down to a
# third speed is a gong, not a clang.
const FAMILIES := {
	"shake": ["dice/dice-shake-1", "dice/dice-shake-2", "dice/dice-shake-3"],
	"grab": ["dice/dice-grab-1", "dice/dice-grab-2"],
	"throw": ["dice/dice-throw-1", "dice/dice-throw-2", "dice/dice-throw-3"],
	"land": ["dice/die-throw-1", "dice/die-throw-2", "dice/die-throw-3", "dice/die-throw-4"],
	"swing": ["rpg/knifeSlice", "rpg/knifeSlice2"],
	"impact": [
		"impact/impactMetal_medium_000", "impact/impactMetal_medium_001",
		"impact/impactMetal_medium_002", "impact/impactMetal_medium_003",
		"impact/impactMetal_medium_004",
	],
	"boom": [
		"impact/impactMetal_heavy_000", "impact/impactMetal_heavy_001",
		"impact/impactMetal_heavy_002", "impact/impactMetal_heavy_003",
		"impact/impactMetal_heavy_004",
	],
	"thud": [
		"impact/impactGeneric_light_000", "impact/impactGeneric_light_001",
		"impact/impactGeneric_light_002", "impact/impactGeneric_light_003",
		"impact/impactGeneric_light_004",
	],
	"steel": ["rpg/drawKnife1", "rpg/drawKnife2", "rpg/drawKnife3"],
	"chop": ["rpg/chop"],
	"step": [
		"rpg/footstep00", "rpg/footstep01", "rpg/footstep02", "rpg/footstep03",
		"rpg/footstep04", "rpg/footstep05", "rpg/footstep06", "rpg/footstep07",
		"rpg/footstep08", "rpg/footstep09",
	],
	"cloth": ["rpg/cloth1", "rpg/cloth2", "rpg/cloth3", "rpg/cloth4"],
	"grind": ["rpg/creak1", "rpg/creak2", "rpg/creak3"],
	"latch": ["rpg/metalLatch", "rpg/metalClick"],
}

const DICE_FAMILIES := ["shake", "grab", "throw", "land"]

## Web Audio master gain in sfx.ts. Applied here so a ported clip is not 33% louder.
const MASTER := 0.75

## How far into the 417ms swing the blade connects. Mirrors IMPACT_SECONDS in tokens.ts.
const IMPACT_MS := 220

## When chip `index` lands, from client/src/combat/opening.ts. The sting is scheduled
## against the same numbers the bar will use, so the two rhythms interleave instead of
## colliding. One copy; the bar should read these when it exists.
const CHIP_DELAY_MS := 400
const CHIP_STAGGER_MS := 180

const WORLD_POOL := 12
const DICE_POOL := 8

var _clips: Dictionary = {}
var _drops: Dictionary = {}
var _world: Array[AudioStreamPlayer] = []
var _dice: Array[AudioStreamPlayer] = []
var _world_at := 0
var _dice_at := 0


func _ready() -> void:
	for _i in WORLD_POOL:
		_world.append(_make_player("World"))
	for _i in DICE_POOL:
		_dice.append(_make_player("Dice"))
	_load_bank()


func _make_player(bus: String) -> AudioStreamPlayer:
	var player := AudioStreamPlayer.new()
	player.bus = bus
	add_child(player)
	return player


func _load_bank() -> void:
	for family: String in FAMILIES:
		var loaded: Array = []
		for clip: String in FAMILIES[family]:
			var stream := load("res://audio/samples/%s.ogg" % clip)
			if stream is AudioStream:
				loaded.append(stream)
			else:
				push_warning("sfx clip missing: %s" % clip)
		_clips[family] = loaded
	for which in [
		"drop_lid", "drop_fell", "drop_drum_a", "drop_drum_b",
		"drop_drum_lead", "drop_combat",
	]:
		var stream := load("res://audio/samples/%s.wav" % which)
		if stream is AudioStream:
			_drops[which] = stream
		else:
			push_warning("sfx drop missing: %s" % which)


## One clip from the family. [param family] is the TS name, not Node.name — that property
## would shadow if this were called [code]name[/code].
func play(family: String, gain: float = 1.0, rate: float = 1.0, delay: float = 0.0) -> void:
	if delay > 0.0:
		_after(delay, func() -> void: play(family, gain, rate, 0.0))
		return
	var loaded: Array = _clips.get(family, [])
	if loaded.is_empty():
		return
	var stream: AudioStream = loaded[randi() % loaded.size()]
	if stream == null:
		return
	var bus := "Dice" if DICE_FAMILIES.has(family) else "World"
	# The same sample replayed identically sounds canned by the third roll.
	_fire(stream, gain, rate * (0.9 + randf() * 0.2), bus)


func _drop(which: String, delay: float = 0.0) -> void:
	if delay > 0.0:
		_after(delay, func() -> void: _drop(which, 0.0))
		return
	var stream: AudioStream = _drops.get(which)
	if stream == null:
		return
	# Gain is baked into the wav. No pitch jitter — these are tuned tones, not clatter.
	_fire(stream, 1.0, 1.0, "World")


func _after(seconds: float, then: Callable) -> void:
	var tree := get_tree()
	if tree == null:
		return
	tree.create_timer(seconds, true, true, false).timeout.connect(
			func() -> void:
				if is_inside_tree():
					then.call(),
			CONNECT_ONE_SHOT)


func _fire(stream: AudioStream, gain: float, pitch: float, bus: String) -> void:
	var player := _take(bus)
	player.stop()
	player.stream = stream
	player.volume_db = linear_to_db(maxf(gain * MASTER, 0.0001))
	player.pitch_scale = maxf(pitch, 0.01)
	player.play()


func _take(bus: String) -> AudioStreamPlayer:
	if bus == "Dice":
		var player: AudioStreamPlayer = _dice[_dice_at]
		_dice_at = (_dice_at + 1) % _dice.size()
		return player
	var world_player: AudioStreamPlayer = _world[_world_at]
	_world_at = (_world_at + 1) % _world.size()
	return world_player


# ---- The room ---------------------------------------------------------------

## The sarcophagus giving up its lid. Hung off a hostile arriving — in M0 the only spawn.
func lid_opens() -> void:
	# Creak dragged well below speed stops being timber and becomes stone under its own weight.
	play("grind", 0.9, 0.5)
	play("grind", 0.4, 0.62, 0.22)
	play("latch", 0.55, 0.7, 0.5)
	play("thud", 0.75, 0.44, 0.62)
	_drop("drop_lid", 0.6)


## Something coming to light — smaller than a lid, and over quickly.
func revealed() -> void:
	play("latch", 0.5)
	play("grind", 0.45, 0.95, 0.08)


## Footfalls under a move, spread across exactly as long as the slide takes.
## Capped at four: a six-square dash is a hurry, and the ear stops counting after three.
func footsteps(squares: int) -> void:
	var steps := maxi(1, mini(squares, 4))
	var over := move_seconds(squares)
	for i in steps:
		# Offset from the start of the slide: the token eases in, so a step on the
		# diff would land before the foot has moved.
		play("step", 0.42, 1.0, 0.06 + (over * float(i)) / float(steps))


## How long a token takes to slide [param squares]. Shared with the renderer — two copies
## of this number would drift apart the first time anyone retuned movement.
func move_seconds(squares: int) -> float:
	return minf(0.18 + float(squares) * 0.07, 0.75)


# ---- The fight --------------------------------------------------------------

## Two quick strikes and then a heavy one — a war-drum figure, not a countdown.
func combat_begins() -> void:
	_drop("drop_drum_lead")
	_drop("drop_drum_a", 0.3)
	_drop("drop_drum_b", 0.72)
	# A sub beneath all three, tuned under the final floor so it thickens rather than beats.
	_drop("drop_combat")


## One small, dull knock per combatant as its chip lands. Pitched down on purpose —
## nothing in this cue is allowed to be brighter than the room.
func initiative_set(count: int) -> void:
	for i in count:
		var at := (CHIP_DELAY_MS + i * CHIP_STAGGER_MS) / 1000.0
		play("thud", 0.3, 0.85, at)
		play("cloth", 0.18, 0.75, at + 0.015)


## One blow. A hit is three clips and a miss is one: the blade moves either way, and
## what separates them is whether anything is there when it arrives.
func swing(connected: bool) -> void:
	if connected:
		play("swing", 0.7)
		var at := IMPACT_MS / 1000.0
		play("chop", 0.8, 1.0, at)
		play("impact", 0.4, 1.1, at + 0.02)
	else:
		play("swing", 0.5, 1.2)


## A body reaching the floor, partway through the 333ms death clip rather than at its start.
func fell() -> void:
	var at := (IMPACT_MS + 210) / 1000.0
	play("thud", 0.85, 0.42, at)
	play("cloth", 0.5, 0.8, at + 0.03)
	_drop("drop_fell", at)
