extends Node

## ALL game state lives here (invariant #3). World, Chrome and SFX all subscribe. Nothing
## game-related may live in a Control's or a Node3D's locals — that is how the renderer ends up
## holding a different world from the one the transcript is describing.
##
## Ported from client/src/store.ts.
##
## Two rules that are not obvious and are load-bearing:
##
## [b]One signal per batch.[/b] The TypeScript is written in Zustand's immutable style, so a
## subscriber always sees one consistent picture. Godot's idiom is mutate-and-emit, and a signal
## per field would have the world rebuilding halfway through a batch that has moved a token but
## not yet changed its hit points. Apply the whole list, then emit once.
##
## [b]Every creature is addressed by its actorId from the wire[/b] (invariant #2). Never a
## literal "fighter", never "the first entity" — the party becomes a list of real people at the
## multiplayer milestone.

signal scene_changed()
signal mode_changed(mode: String)
signal entity_added(entity: Dictionary)
signal entity_moved(entity_id: String, from: Vector2i, to: Vector2i)
signal prop_revealed(prop: Dictionary)
signal entity_died(entity_id: String)

var connected := false
var demo_mode := false
var dm_configured := false
var scene: Dictionary = {}
var mode := "EXPLORATION"

## Whether the player has started the session. Gates a real event: until someone has clicked,
## the server has not been asked to narrate the opening.
var started := false

## Set while a restart is in flight, so the fresh scene the server sends back is recognised as
## the end of this session rather than the middle of one.
var _restarting := false


func expect_restart() -> void:
	_restarting = true


func _ready() -> void:
	Net.hello.connect(func(demo: bool, _voice: bool, has_dm: bool) -> void:
		demo_mode = demo
		dm_configured = has_dm)
	Net.scene.connect(set_scene)
	Net.diffs.connect(apply_diffs)
	Net.narration.connect(append_narration)
	Net.narration_end.connect(end_narration)
	Net.roll.connect(add_roll)
	Net.error.connect(set_error)
	Net.connected.connect(func() -> void: connected = true)
	Net.disconnected.connect(func(_reason: String) -> void: connected = false)


func reset() -> void:
	_restarting = false
	scene = {}
	mode = "EXPLORATION"
	started = false
	_reset_talk()   # the transcript half, added in Task 8


## Mode rides with the scene rather than being left to the diff that changed it: a client that
## connects mid-fight gets one message, and it has to be the whole truth.
func set_scene(state: Dictionary) -> void:
	if _restarting:
		_restarting = false
		# All the way back to the title. The server has just cleared its once-per-session
		# opening guard and is waiting to be asked again; only `begin` asks, and only the
		# title sends `begin`. A client that stays `started` here lays out a fresh room
		# and then sits in silence forever.
		reset()
	scene = state
	mode = String(state.get("mode", "EXPLORATION"))
	_adopt_combat_from_scene()   # Task 8
	scene_changed.emit()
	mode_changed.emit(mode)


func entity(id: String) -> Dictionary:
	for e in scene.get("entities", []):
		if e["id"] == id:
			return e
	return {}


func prop(id: String) -> Dictionary:
	for p in scene.get("props", []):
		if p["id"] == id:
			return p
	return {}


## Whether the server has told us this square is solid.
##
## A lookup, never a rule. The set arrives in the scene (SceneState.blocked) because the client
## owns no movement rule — invariant #1 — and it cannot be read off `props` either: an alcove is
## a prop you can walk into. Absent from the set is not a promise the move is legal; a creature
## may be standing there, and a hidden solid prop is deliberately not advertised.
func is_blocked(square: Vector2i) -> bool:
	for cell in scene.get("blocked", []):
		if int(cell.get("x", -1)) == square.x and int(cell.get("y", -1)) == square.y:
			return true
	return false


## The exit with this id, or {} if there is none. Mirrors dm.model.Exit.
##
## By id and not by square: an Exit carries the id of the DOOR prop standing in it, so the
## renderer, the DM and this lookup all address one thing. Clicking the floor square a door
## happens to stand on is a move, and reads like one.
func exit_by_id(exit_id: String) -> Dictionary:
	for e in scene.get("exits", []):
		if String(e.get("id", "")) == exit_id:
			return e
	return {}


## Queued with the narration, in arrival order, and released when the voice reaches it.
##
## A hit point bar that empties while the attack die is still in the air has answered the
## question the die was asking — and one that empties twenty seconds before the narrator says
## the blow was struck is worse still. Both are the same bug: the world moving on a different
## clock from the voice describing it.
##
## When nothing is queued this runs on the spot, which is every move and every reveal the player
## makes for themselves — so click-to-move keeps its 100ms budget.
func apply_diffs(list: Array) -> void:
	Clock.mark(func() -> void: _apply_now(list))


func _apply_now(list: Array) -> void:
	if scene.is_empty():
		return

	# Which side of a mode flip this batch crossed, decided in the loop and acted on after it:
	# the ceremony needs the CombatView, and that arrives in the CombatChanged diff sitting
	# behind the ModeChanged one.
	var opened := false
	var closed := false
	var announcements: Array[Callable] = []

	for diff in list:
		match String(diff["kind"]):
			"EntityAdded":
				var added: Dictionary = diff["entity"]
				var existing := entity(String(added["id"]))
				if existing.is_empty():
					scene["entities"].append(added)
					announcements.append(func() -> void: entity_added.emit(added))
				else:
					# Idempotent by id. A replaced goblin is a debug respawn, not a lid coming
					# off a second time.
					scene["entities"][_index_of_entity(String(added["id"]))] = added
					announcements.append(func() -> void: scene_changed.emit())

			"EntityRemoved":
				var at := _index_of_entity(String(diff["entityId"]))
				if at >= 0:
					scene["entities"].remove_at(at)

			"EntityMoved":
				var moving := entity(String(diff["entityId"]))
				if not moving.is_empty():
					var from := Vector2i(int(diff["fromX"]), int(diff["fromY"]))
					var to := Vector2i(int(diff["x"]), int(diff["y"]))
					moving["x"] = to.x
					moving["y"] = to.y
					var who := String(diff["entityId"])
					announcements.append(func() -> void: entity_moved.emit(who, from, to))

			"StatChanged":
				if String(diff["stat"]) == "hp":
					var hurt := entity(String(diff["entityId"]))
					if not hurt.is_empty():
						hurt["hp"] = int(diff["to"])
						if int(diff["to"]) <= 0 and int(diff["from"]) > 0:
							var who := String(diff["entityId"])
							announcements.append(func() -> void: entity_died.emit(who))

			"ModeChanged":
				var next := String(diff["mode"])
				if mode != next:
					opened = next == "COMBAT"
					closed = next == "EXPLORATION"
				mode = next

			"PropRevealed":
				var revealed: Dictionary = diff["prop"]
				var at_prop := _index_of_prop(String(revealed["id"]))
				if at_prop >= 0:
					scene["props"][at_prop] = revealed
				else:
					scene["props"].append(revealed)
					announcements.append(func() -> void: prop_revealed.emit(revealed))

			"CombatChanged":
				# Replaced wholesale, never merged. The server sends the entire legal picture
				# each time precisely so the client has no chance to hold a half-updated one.
				scene["combat"] = diff["combat"]

	_settle_combat(opened, closed)   # Task 8
	scene_changed.emit()
	if opened or closed:
		mode_changed.emit(mode)
	for announce in announcements:
		announce.call()


func _index_of_entity(id: String) -> int:
	var entities: Array = scene.get("entities", [])
	for i in entities.size():
		if entities[i]["id"] == id:
			return i
	return -1


func _index_of_prop(id: String) -> int:
	var props: Array = scene.get("props", [])
	for i in props.size():
		if props[i]["id"] == id:
			return i
	return -1


# ---- The transcript, the rolls, and the fight

const CEREMONY_MS := 1200

signal transcript_changed()
signal roll_thrown(result: Dictionary)
signal strike(actor_id: String, target_id: String, connected: bool, at: int)
signal combat_changed()
signal combat_opened(order_size: int)
signal errored(message: String)
signal started_changed()

## Prose and rolls in one list, so the log preserves the order things actually happened in —
## the dice, then the narration that commits to them. Each entry is either
## { "kind": "prose", "speakerId": String, "text": String } or
## { "kind": "roll", "result": Dictionary }.
var transcript: Array[Dictionary] = []
var rolls: Array[Dictionary] = []

## True while the DM is mid-turn. Drives the thinking indicator and the input lockout.
var awaiting_dm := false

## The roll the tray is currently throwing, and when it arrived. Null when nothing is in flight.
var active_roll = null
## When the tray began fading, in Time.get_ticks_msec() terms. Null while it is still held.
var dice_dismiss_at = null

## The fight's chrome, or the last fight's while it fades. Separate from scene.combat because
## the chrome outlives the fight by design: the bar has to still have names and initiative
## totals to draw while it is dissolving, and by then the server has said the fight is over.
## { "view": Dictionary, "opened_at": int, "closing_at": int or null }
var combat_beat = null

var error_message := ""


func _reset_talk() -> void:
	transcript = []
	rolls = []
	awaiting_dm = false
	active_roll = null
	dice_dismiss_at = null
	combat_beat = null
	error_message = ""
	Clock.silence_now()
	transcript_changed.emit()
	started_changed.emit()


## One way while a session runs. A dropped socket reconnects to a session already under way; it
## does not put the title back up, and the server will not narrate the opening twice.
##
## The DM has the floor from this moment, not from the moment its first token lands. Those are
## about 700ms apart, and in that window the debug bar was live and the input box was open —
## long enough to start a fight underneath the opening narration.
func set_started() -> void:
	started = true
	awaiting_dm = true
	started_changed.emit()


## Narration arrives a sentence at a time. Consecutive sentences from the same speaker extend
## the current paragraph, so the transcript reads as prose rather than as a list of fragments.
##
## The transcript update is the queue's callback rather than something that happens now: the
## voice paces the text, so the player reads at the speed the DM is talking instead of racing
## twenty seconds ahead of it.
func append_narration(segment: Dictionary) -> void:
	Clock.speak(segment, func() -> void:
		# The first word of narration is the tray's cue to leave.
		if active_roll != null and dice_dismiss_at == null:
			dismiss_dice()

		var last: Dictionary = transcript[-1] if not transcript.is_empty() else {}
		if awaiting_dm and last.get("kind", "") == "prose" \
				and last.get("speakerId", "") == segment["speakerId"]:
			last["text"] = _join_prose(String(last["text"]), String(segment["text"]))
		else:
			transcript.append({"kind": "prose", "speakerId": String(segment["speakerId"]),
				"text": String(segment["text"])})
			awaiting_dm = true
		transcript_changed.emit())


## Behind the queue: clearing this early would end the thinking indicator while lines were still
## appearing, and break the paragraph merging above.
func end_narration() -> void:
	Clock.mark(func() -> void:
		awaiting_dm = false
		transcript_changed.emit())


func say_as_player(text: String) -> void:
	# A new turn drops the rest of the last one, but lets the sentence in the air finish.
	Clock.silence()
	transcript.append({"kind": "prose", "speakerId": "player", "text": text})
	awaiting_dm = true
	transcript_changed.emit()


## Every roll reaches the log; only dramatic ones get thrown.
##
## Queued like everything else, so "the dice decide, then the DM speaks" is true by construction
## rather than by luck. A dramatic roll holds the queue for as long as it is in the air, which
## is what stops narration that commits to a result from arriving ahead of the die showing it.
func add_roll(result: Dictionary) -> void:
	var dramatic := Tumble.is_dramatic(result)
	var request: Dictionary = result["request"]

	# Holding the whole queue rather than the swing alone is what keeps the order true: the hit
	# point bar, the damage line and the blow are all consequences of this roll, and none of
	# them may arrive before the player has read what the roll said.
	var beat := Tumble.IMPACT_BEAT_MS if request["purpose"] == "ATTACK" else 0
	var airtime := (Tumble.reveal_at(result["faces"].size()) + beat) if dramatic else 0

	var throw_it := func() -> void:
		rolls.append(result)
		if dramatic:
			active_roll = {"result": result, "started_at": Time.get_ticks_msec()}
			dice_dismiss_at = null
			roll_thrown.emit(result)

	# The log is a record, and records lag. Appending it as the die is thrown would print the
	# total in the sidebar while it was still in the air, which spoils the throw.
	var settle := func() -> void:
		transcript.append({"kind": "roll", "result": result})
		transcript_changed.emit()
		# Released here rather than on arrival so the swing plays when the die answers, not when
		# the server decided.
		if request["purpose"] == "ATTACK" and request["targetId"] != null:
			var hit: bool = result["outcome"] == "HIT" or result["outcome"] == "CRIT"
			strike.emit(String(request["actorId"]), String(request["targetId"]), hit,
				Time.get_ticks_msec())
			# The blow is the tray's cue to leave, exactly as narration is out of combat.
			if dice_dismiss_at == null:
				dismiss_dice()

	if dramatic:
		Clock.hold(airtime, throw_it)
		Clock.mark(settle)
	else:
		Clock.mark(func() -> void:
			throw_it.call()
			settle.call())


func dismiss_dice() -> void:
	dice_dismiss_at = Time.get_ticks_msec()


## Errors bypass the queue: a stuck turn must never be hidden behind a die or a sentence.
func set_error(message: String) -> void:
	# An error is the one case worth cutting mid-word for.
	Clock.silence_now()
	error_message = message
	awaiting_dm = false
	errored.emit(message)


## A socket that drops and reconnects during a fight rejoins one already in progress. Backdated
## past the ceremony so the drums do not announce something that happened minutes ago.
func _adopt_combat_from_scene() -> void:
	var view = scene.get("combat", null)
	if view == null:
		combat_beat = null
		return
	combat_beat = {"view": view, "opened_at": Time.get_ticks_msec() - CEREMONY_MS,
		"closing_at": null}
	combat_changed.emit()


## The combat chrome after a batch of diffs. Not pure, and deliberately so — this is the first
## point at which both facts the opening beat needs are known: that the mode flipped, and who is
## in the initiative order.
func _settle_combat(opened: bool, closed: bool) -> void:
	var now := Time.get_ticks_msec()
	var view = scene.get("combat", null)

	if opened and view != null:
		# Everything downstream waits behind the ceremony: the DM's first line about the fight,
		# the creature's opening move, and every consequence of it. The same gate the dice use,
		# for the same reason — a beat the narrator talks over is not a beat.
		Clock.hold(CEREMONY_MS, func() -> void: pass)
		combat_beat = {"view": view, "opened_at": now, "closing_at": null}
		combat_opened.emit(view["order"].size())
		combat_changed.emit()
		return

	if closed:
		# The retained view is the last live one. `view` is already null by now, and the bar has
		# to keep drawing names and totals all the way through its dissolve.
		if combat_beat != null and combat_beat["closing_at"] == null:
			combat_beat["closing_at"] = now
			combat_changed.emit()
		return

	if view == null:
		return

	# An ordinary update — the turn passing, movement spent, someone going down. The backdated
	# fallback covers a CombatChanged arriving without an opening, which is what a mid-fight
	# reconnect looks like if the scene has not landed yet.
	if combat_beat != null:
		combat_beat["view"] = view
	else:
		combat_beat = {"view": view, "opened_at": now - CEREMONY_MS, "closing_at": null}
	combat_changed.emit()


## Segments arrive pre-trimmed of nothing, so join with exactly one space.
func _join_prose(existing: String, addition: String) -> String:
	var left := existing.strip_edges(false, true)
	var right := addition.strip_edges(true, false)
	if left.is_empty():
		return right
	if right.is_empty():
		return left
	return "%s %s" % [left, right]
