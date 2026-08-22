class_name Tumble
extends RefCounted

## The dice animation, as pure data.
##
## Nothing here draws, plays a sound, or touches Table — it turns "this roll, this many
## milliseconds in" into positions, faces and opacities. Keeping it separate is what makes the
## one rule below checkable by reading it.
##
## [b]The rule: the server decides, the animation displays.[/b] Tumbling faces are decorative
## noise; the moment a die settles it shows [code]result.faces[i][/code] and nothing else.
## Physics never produces a value here (invariant #1).
##
## Ported from client/src/dice/tumble.ts. Every constant is already tuned — AGENTS.md §Dice
## records what each one bought. Do not renegotiate them here.

# ---- Layout, in tray units.

const TRAY_WIDTH := 232.0
const TRAY_HEIGHT := 76.0
const DISPLAY_SCALE := 3.0

const DIE_RADIUS := 19.0
const DIE_GAP := 8.0
const FIRST_DIE_X := 30.0
const REST_Y := 38.0

# ---- Timing, in milliseconds.

## Dice are heard before they are seen: the rattle plays over an empty tray.
const WIND_UP_MS := 240
const FLIGHT_MS := 820
## Dice landing together is a thud; landing apart is a clatter.
const LAND_STAGGER_MS := 130
const BOUNCE_MS := 280
const FADE_IN_MS := 180

const FADE_OUT_MS := 420
## How long the tray waits for narration that never comes.
const HOLD_MS := 9000
## The same wait, in combat, where the next thing to happen is the next roll.
const COMBAT_HOLD_MS := 3000

## The pause between a result becoming readable and the blow that follows it. Attacks only —
## a skill check's consequence is narration, which takes seconds to arrive on its own.
const IMPACT_BEAT_MS := 650

const LOB := 10.0
const BOUNCE_HEIGHT := 12.0
## Whole turns, so the flight ease converges on upright rather than an arbitrary angle.
const SPIN := PI * 8.0

const TONE_COLOR := {
	"crit": Color("f0d67a"),
	"good": Color("9ec46a"),
	"bad": Color("c0736a"),
	"fumble": Color("c04a4a"),
	"neutral": Color("a99e8e"),
}

const PURPOSE_LABEL := {
	"ATTACK": "ATTACK",
	"SAVE": "SAVING THROW",
	"SKILL_CHECK": "SKILL CHECK",
	"DAMAGE": "DAMAGE",
	"INITIATIVE": "INITIATIVE",
}


## When die [param index] stops moving and commits to its face.
static func land_at(index: int) -> int:
	return WIND_UP_MS + FLIGHT_MS + index * LAND_STAGGER_MS


## When the total becomes legible. Narration is held until this moment — the dice decide, then
## the DM speaks, never the other way round.
static func reveal_at(die_count: int) -> int:
	return land_at(maxi(0, die_count - 1)) + BOUNCE_MS + 120


## "Do not animate every roll." Purpose decides, not who rolled: an incoming attack is the
## tensest die in the game and the goblin throws it.
static func is_dramatic(result: Dictionary) -> bool:
	var purpose: String = result["request"]["purpose"]
	# Initiative is every combatant at once and the tray throws one roll at a time. Damage is
	# the consequence, not the question — animating it makes one swing read as two.
	return purpose != "INITIATIVE" and purpose != "DAMAGE"


## @param elapsed    milliseconds since the roll arrived
## @param dismiss_at milliseconds at which the fade-out starts
static func sample(result: Dictionary, elapsed: int, dismiss_at: int) -> Dictionary:
	var request: Dictionary = result["request"]
	var faces: Array = result["faces"]
	var sides := dice_sides(request["dice"])
	var count := faces.size()
	var keeps_one: bool = request["advantage"] != "NORMAL"

	var dice: Array[Dictionary] = []
	for i in count:
		var flight := float(FLIGHT_MS + i * LAND_STAGGER_MS)
		var p := clampf((elapsed - WIND_UP_MS) / flight, 0.0, 1.0)
		var settled := elapsed >= land_at(i)

		var rest_x := FIRST_DIE_X + i * (DIE_RADIUS * 2.0 + DIE_GAP)
		var start_x := TRAY_WIDTH + DIE_RADIUS * 2.0
		var start_y := -DIE_RADIUS

		var since := elapsed - land_at(i)
		var bouncing := since >= 0 and since < BOUNCE_MS
		var b := (since / float(BOUNCE_MS)) if bouncing else 0.0

		var bounce_y := 0.0
		var bounce_spin := 0.0
		if bouncing:
			bounce_y = BOUNCE_HEIGHT * absf(sin(b * PI * 2.0)) * (1.0 - b)
			bounce_spin = 0.22 * sin(b * PI * 3.0) * (1.0 - b)

		dice.append({
			"x": start_x + (rest_x - start_x) * _ease_out_cubic(p),
			"y": start_y + (REST_Y - start_y) * (p * p) - LOB * sin(PI * p) - bounce_y,
			"rotation": SPIN * _ease_out_cubic(p) + bounce_spin,
			# The one line that matters. Before it settles the face is noise; after, it is the
			# server's value. There is no path by which the animation can invent a result.
			"face": int(faces[i]) if settled else _tumbling_face(i, elapsed, sides),
			"sides": sides,
			"radius": DIE_RADIUS,
			# The server puts the kept die first, so every later die on advantage is a discard.
			"discarded": keeps_one and i > 0,
			"settled": settled,
		})

	var fade_out := 1.0
	if elapsed > dismiss_at:
		fade_out = 1.0 - clampf((elapsed - dismiss_at) / float(FADE_OUT_MS), 0.0, 1.0)
	var opacity := minf(clampf(elapsed / float(FADE_IN_MS), 0.0, 1.0), fade_out)

	return {
		"dice": dice,
		"reveal": clampf((elapsed - reveal_at(count)) / 260.0, 0.0, 1.0),
		"opacity": opacity,
		"finished": opacity <= 0.0,
	}


## Wording, shared by the tray and the transcript log so the two cannot drift.
static func caption(result: Dictionary) -> Dictionary:
	var request: Dictionary = result["request"]
	var faces: Array = result["faces"]
	var counted: Array = faces if request["advantage"] == "NORMAL" else faces.slice(0, 1)

	var label: String = PURPOSE_LABEL.get(request["purpose"], request["purpose"])
	# Nulls are nulls. `if request.skill:` would also be false for an empty string.
	if request["skill"] != null:
		label = "%s CHECK" % request["skill"]

	var target := ""
	if request["dc"] != null:
		var word := "AC" if request["purpose"] == "ATTACK" else "DC"
		target = "%s %d" % [word, int(request["dc"])]

	var parts: Array[String] = []
	for face in counted:
		parts.append(str(int(face)))
	var sum := " + ".join(parts)

	var modifier: int = request["modifier"]
	var arithmetic := sum
	if counted.size() != 1 or modifier != 0:
		var sign_text := ""
		if modifier != 0:
			sign_text = " %s %d" % ["−" if modifier < 0 else "+", absi(modifier)]
		arithmetic = "%s%s = %d" % [sum, sign_text, int(result["total"])]

	# Damage and initiative are adjudicated as SUCCESS because every roll needs an outcome, but
	# printing that next to "5 + 2 = 7" says nothing and reads as though it could have failed.
	var decided: bool = request["purpose"] != "DAMAGE" and request["purpose"] != "INITIATIVE"

	return {
		"label": label,
		"target": target,
		"arithmetic": arithmetic,
		"outcome": _pretty(result["outcome"]) if decided else "",
		"tone": _tone_of(result["outcome"]) if decided else "neutral",
	}


## Presentation only — how many sides to draw. Adjudication never happens on this side.
static func dice_sides(expression: String) -> int:
	var re := RegEx.create_from_string("^\\s*\\d*\\s*[dD]\\s*(\\d+)")
	var found := re.search(expression)
	return int(found.get_string(1)) if found != null else 20


static func _pretty(outcome: String) -> String:
	if outcome == "CRIT":
		return "CRITICAL"
	if outcome == "CRIT_FAIL":
		return "FUMBLE"
	return outcome


static func _tone_of(outcome: String) -> String:
	match outcome:
		"CRIT": return "crit"
		"CRIT_FAIL": return "fumble"
		"HIT", "SUCCESS": return "good"
		"MISS", "FAILURE": return "bad"
		_: return "neutral"


## A stable pseudo-random face for a given die at a given instant. Stable matters: derived from
## the clock rather than randi() so a dropped frame does not make the dice stutter.
static func _tumbling_face(index: int, elapsed: int, sides: int) -> int:
	@warning_ignore("integer_division")
	var step := elapsed / 52  # ~19 changes a second: blurred, but still dice
	return 1 + (_hash(index * 8191 + step) % sides)


static func _hash(n: int) -> int:
	var h := (n ^ 0x9e3779b9) * 0x85ebca6b & 0xffffffff
	h ^= h >> 13
	h = h * 0xc2b2ae35 & 0xffffffff
	return (h ^ (h >> 16)) & 0xffffffff


static func _ease_out_cubic(t: float) -> float:
	return 1.0 - pow(1.0 - t, 3.0)
