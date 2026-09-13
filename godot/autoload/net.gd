extends Node

## One connection for the whole game. The server is authoritative (invariant #1) — everything
## arriving here is applied as told, never recomputed.
##
## Does not apply diffs, speak, or draw. It turns bytes into signals and back.
##
## WebSocketPeer has no signals of its own, so it is polled from _process. That is the Godot
## idiom and it is also what keeps the 100ms click-to-move budget: a click sends on the same
## frame it was made.

signal hello(demo_mode: bool, voice: bool, dm: bool)
signal scene(state: Dictionary)
signal diffs(list: Array)
signal narration(segment: Dictionary)
signal narration_end()
signal roll(result: Dictionary)
signal error(message: String)
signal connected()
signal disconnected(reason: String)

const RECONNECT_DELAY_SECONDS := 1.0

var _socket := WebSocketPeer.new()
var _state := WebSocketPeer.STATE_CLOSED
var _retry_at := 0.0
var _want_connection := false


func _ready() -> void:
	# A test run must never reach a real server. Autoloads are ready before any test script
	# runs, so this cannot be a flag a test sets — it has to be read off the command line.
	# A test that wants the socket calls open() itself.
	if started_for_tests():
		return
	open()


## True when this process was started to run the suite. GUT is launched as
## `-s addons/gut/gut_cmdln.gd`, and the engine leaves that path on the command line.
static func started_for_tests() -> bool:
	for arg in OS.get_cmdline_args():
		if arg.ends_with("gut_cmdln.gd"):
			return true
	return false


func open() -> void:
	_want_connection = true
	_retry_at = 0.0


func _process(_delta: float) -> void:
	if not _want_connection:
		return

	if _state == WebSocketPeer.STATE_CLOSED:
		# The server is a terminal process that gets restarted a dozen times an evening. Quietly
		# reattaching is the loop a developer actually lives in; a modal that has to be dismissed
		# every time is not.
		var now := Time.get_ticks_msec() / 1000.0
		if now < _retry_at:
			return
		_retry_at = now + RECONNECT_DELAY_SECONDS
		var err := _socket.connect_to_url(Link.ws_url())
		if err != OK:
			return

	_socket.poll()
	var next := _socket.get_ready_state()

	if next != _state:
		if next == WebSocketPeer.STATE_OPEN:
			connected.emit()
		elif next == WebSocketPeer.STATE_CLOSED and _state != WebSocketPeer.STATE_CLOSED:
			var reason := _socket.get_close_reason()
			push_warning("[net] socket closed: %d %s" % [_socket.get_close_code(), reason])
			disconnected.emit(reason)
		_state = next

	while _socket.get_ready_state() == WebSocketPeer.STATE_OPEN \
			and _socket.get_available_packet_count() > 0:
		receive(_socket.get_packet().get_string_from_utf8())


## Turn one raw frame into a signal. Public so tests can push fixtures without a server.
func receive(raw: String) -> void:
	var parsed = JSON.parse_string(raw)
	if typeof(parsed) != TYPE_DICTIONARY:
		push_error("[net] unparseable server message: " + raw)
		return
	dispatch(parsed)


func dispatch(message: Dictionary) -> void:
	match String(message.get("type", "")):
		"hello":
			# `get` with a default, because the field is additive and an older server predates
			# it. Never `if message.dm:` — that is also false for an explicit false.
			hello.emit(
				bool(message.get("demoMode", false)),
				bool(message.get("voice", false)),
				bool(message.get("dm", false)))
		"scene":
			scene.emit(message["scene"])
		"diffs":
			# One signal per batch. Table applies the whole list, then emits once.
			diffs.emit(message["diffs"])
		"narration":
			narration.emit(message["segment"])
		"narrationEnd":
			narration_end.emit()
		"roll":
			roll.emit(message["result"])
		"error":
			error.emit(String(message.get("message", "")))
		_:
			# A message type this client does not know is not a crash. The server and the two
			# clients drift by exactly one field at a time during the bridge.
			pass


## Every payload handed to [method send], oldest first. Chrome tests watch this
## because send() itself is silent when the socket is closed and a different
## silent when it is open — neither is a thing the overlay should have to know.
var outbound: Array[Dictionary] = []


func send(message: Dictionary) -> void:
	outbound.append(message)
	if _socket.get_ready_state() != WebSocketPeer.STATE_OPEN:
		push_warning("[net] dropped message, socket not open: " + str(message))
		return
	_socket.send_text(JSON.stringify(message))


# ---- The client messages, named rather than spelled out at every call site.

func begin() -> void:
	send({"type": "begin"})

func restart() -> void:
	send({"type": "restart"})

func free_text(actor_id: String, text: String) -> void:
	send({"type": "freeText", "actorId": actor_id, "text": text})

func move_to(actor_id: String, x: int, y: int) -> void:
	send({"type": "moveTo", "actorId": actor_id, "x": x, "y": y})

func enter_exit(exit_id: String) -> void:
	send({"type": "enterExit", "exitId": exit_id})

func attack(actor_id: String, target_id: String) -> void:
	send({"type": "attack", "actorId": actor_id, "targetId": target_id})

func end_turn(actor_id: String) -> void:
	send({"type": "endTurn", "actorId": actor_id})

func use_item(actor_id: String, item: String) -> void:
	send({"type": "useItem", "actorId": actor_id, "item": item})

func rest(actor_id: String) -> void:
	send({"type": "rest", "actorId": actor_id})

## The seven debug messages. They exist so the feel can be tuned with no model, no key and no
## latency in the path — see Task 13.
func debug(message: Dictionary) -> void:
	send(message)
