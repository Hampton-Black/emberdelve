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
var _http: HTTPRequest
## Keyed by speakerId and text, so priming a line and then speaking it share one request.
var _ready_audio: Dictionary = {}
var _in_flight_key := ""
var _in_flight_line: Dictionary = {}
var _wanted := ""
var _falling_back := false


func _init(fallback: VoiceBackend, host: Node) -> void:
	_fallback = fallback

	_player = AudioStreamPlayer.new()
	_player.bus = "Voice"
	host.add_child(_player)
	_player.finished.connect(_on_played)

	_http = HTTPRequest.new()
	host.add_child(_http)
	_http.request_completed.connect(_on_fetched)

	_fallback.finished.connect(func() -> void:
		if _falling_back:
			_falling_back = false
			finished.emit())


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
	_wanted = ""
	_player.stop()
	_fallback.stop()


func _fetch(line: Dictionary) -> void:
	var key := key_of(line)
	if _ready_audio.has(key) or _in_flight_key == key or _in_flight_key != "":
		return
	_in_flight_key = key
	_in_flight_line = line
	var err := _http.request(
		Link.tts_url(),
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		JSON.stringify({"speakerId": line["speakerId"], "text": line["text"]}))
	if err != OK:
		_in_flight_key = ""
		if key == _wanted:
			_give_up(line)


func _on_fetched(_result: int, code: int, _headers: PackedStringArray,
		body: PackedByteArray) -> void:
	var key := _in_flight_key
	var line := _in_flight_line
	_in_flight_key = ""
	_in_flight_line = {}

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
