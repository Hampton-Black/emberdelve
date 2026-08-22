class_name HttpVoice
extends VoiceBackend

## Asks this project's server for audio. The key never reaches the client — the server holds it
## and Godot fetches bytes.
##
## Speech is HTTP rather than the websocket on purpose: a minute of narration is a few hundred
## kilobytes, and pushing that down the same socket the diffs use would put it behind — or in
## front of — a click-to-move that has a 100ms budget.
##
## Anything but a 200 means "use the other voice". A failed line is a worse voice, never a
## silent turn and never a stalled queue.

var _fallback: VoiceBackend
var _player: AudioStreamPlayer
var _host: Node
## Keyed by speakerId and text, so priming a line and then speaking it share one request.
var _ready_audio: Dictionary = {}
## One HTTPRequest per in-flight line, keyed the same way. Clock's drain primes the next
## line and then speaks the current one, so a single slot would let a prime occupy it and
## the speak never fetch — the line never plays and never finishes. Per-key requests are
## what the TypeScript's `pending` promise map buys: a speak always fetches or joins its
## own line's request, and a prime never blocks it.
var _requests: Dictionary = {}
var _wanted := ""
var _falling_back := false
var _on_fallback_finished: Callable


func _init(fallback: VoiceBackend, host: Node) -> void:
	_fallback = fallback
	_host = host

	_player = AudioStreamPlayer.new()
	_player.bus = "Voice"
	host.add_child(_player)
	_player.finished.connect(_on_played)

	# The lambda is stored on this instance, so capturing `self` strongly would be a
	# RefCounted self-cycle that is never collected. Go through a weakref.
	var self_ref: WeakRef = weakref(self)
	_on_fallback_finished = func() -> void:
		var me: HttpVoice = self_ref.get_ref()
		if me != null and me._falling_back:
			me._falling_back = false
			me.finished.emit()
	_fallback.finished.connect(_on_fallback_finished)


static func key_of(line: Dictionary) -> String:
	return "%s\u0000%s" % [line["speakerId"], line["text"]]


## Start preparing a line that is coming but is not being spoken yet. Exactly one ahead: far
## enough that synthesis finishes while the previous line plays, near enough that an interrupted
## turn wastes at most one line of a metered API.
func prime(line: Dictionary) -> void:
	_fetch(line)


func speak(line: Dictionary) -> void:
	_wanted = key_of(line)
	if _ready_audio.has(_wanted):
		_play(_ready_audio[_wanted], line)
		return
	_fetch(line)


func stop() -> void:
	# stop settles the line — a parked drain never resumes. AudioStreamPlayer.stop() does
	# not emit `finished` in Godot 4, so a line in the air is settled here, exactly once,
	# deferred. The TypeScript holds `settle` for exactly this.
	for req: HTTPRequest in _requests.values():
		req.cancel_request()
		req.queue_free()
	_requests.clear()
	_player.stop()
	_fallback.stop()
	if _wanted != "":
		_wanted = ""
		finished.emit.call_deferred()


## Free the nodes this backend put on its host. Called on a backend swap, after stop().
func teardown() -> void:
	# A torn-down backend stops hearing its fallback here. (The lambda itself captures this
	# instance only weakly — see _init — so the pair stays collectable either way.)
	if _fallback.finished.is_connected(_on_fallback_finished):
		_fallback.finished.disconnect(_on_fallback_finished)
	for req: HTTPRequest in _requests.values():
		req.cancel_request()
		req.queue_free()
	_requests.clear()
	_player.queue_free()


func _fetch(line: Dictionary) -> void:
	var key := key_of(line)
	if _ready_audio.has(key) or _requests.has(key):
		return
	var req := HTTPRequest.new()
	_host.add_child(req)
	_requests[key] = req
	req.request_completed.connect(_on_fetched.bind(key, line))
	var err := req.request(
		Link.tts_url(),
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		JSON.stringify({"speakerId": line["speakerId"], "text": line["text"]}))
	if err != OK:
		_requests.erase(key)
		req.queue_free()
		if key == _wanted:
			_give_up(line)


func _on_fetched(_result: int, code: int, _headers: PackedStringArray,
		body: PackedByteArray, key: String, line: Dictionary) -> void:
	var req: HTTPRequest = _requests.get(key)
	_requests.erase(key)
	if req != null:
		req.queue_free()

	if code != 200 or body.is_empty():
		# 502 is the server saying the voice refused; anything else non-OK is the server itself.
		# Both mean the same thing here.
		if key == _wanted:
			_give_up(line)
		return

	var stream := AudioStreamMP3.load_from_buffer(body)
	_ready_audio[key] = stream
	if key == _wanted:
		_play(stream, line)


func _play(stream: AudioStreamMP3, line: Dictionary) -> void:
	_ready_audio.erase(key_of(line))
	_player.stream = stream
	_player.play()


func _on_played() -> void:
	if _wanted != "":
		_wanted = ""
		finished.emit()


func _give_up(line: Dictionary) -> void:
	_falling_back = true
	_wanted = ""
	_fallback.speak(line)
