extends Node

## Where the server is. The only address in this client.
##
## Every URL the game uses — the socket, speech, health — derives from one setting. There is no
## second constant and no port arithmetic anywhere else in the project. That is what keeps a
## hosted DM a configuration change rather than a rewrite, and it costs one file.
##
## Godot never starts the server and never stops it. It is a Gradle process in a terminal
## (`cd server && ./gradlew run`) that gets restarted a dozen times an evening, and the client's
## job is to reattach quietly when it comes back.

const DEFAULT := "http://127.0.0.1:7070"
const SETTING := "EMBERDELVE_SERVER"

var _base: String = ""


func _ready() -> void:
	_base = normalise(OS.get_environment(SETTING))
	print("[link] server at ", _base)


## Trim to a bare origin. Empty means the default.
static func normalise(raw: String) -> String:
	var value := raw.strip_edges()
	if value.is_empty():
		value = DEFAULT
	while value.ends_with("/"):
		value = value.substr(0, value.length() - 1)
	return value


## The socket URL for a given base. `wss` for `https`, so a hosted server needs no code change.
static func ws_from(base: String) -> String:
	if base.begins_with("https://"):
		return "wss://" + base.substr(8) + "/ws"
	if base.begins_with("http://"):
		return "ws://" + base.substr(7) + "/ws"
	return base + "/ws"


func base_url() -> String:
	# Tests instantiate this class without _ready having run.
	if _base.is_empty():
		_base = normalise(OS.get_environment(SETTING))
	return _base


func ws_url() -> String:
	return ws_from(base_url())


func health_url() -> String:
	return base_url() + "/health"


func tts_url() -> String:
	return base_url() + "/tts"
