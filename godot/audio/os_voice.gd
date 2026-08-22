class_name OsVoice
extends VoiceBackend

## The machine's own synthesiser. Always built, because it is also the per-line fallback.
##
## Two Godot facts underneath this. `DisplayServer.tts_*` does nothing at all unless
## Project Settings > Audio > General > Text to Speech is on; it is off by default and the
## failure is silence, not an error. And the completion callback is [b]global[/b], not per
## utterance — one callback for the whole process, carrying an id — so the "exactly one
## finished per speak" contract needs the id tracked here rather than captured per call.

var _utterance_id := 0
var _in_flight := -1
var _voices: Array = []


func _init() -> void:
	if not DisplayServer.has_feature(DisplayServer.FEATURE_TEXT_TO_SPEECH):
		push_warning("[voice] no OS text-to-speech on this platform")
		return
	_voices = DisplayServer.tts_get_voices()
	DisplayServer.tts_set_utterance_callback(
		DisplayServer.TTS_UTTERANCE_ENDED, _on_utterance_done)
	DisplayServer.tts_set_utterance_callback(
		DisplayServer.TTS_UTTERANCE_CANCELED, _on_utterance_done)
	# An error settles the line exactly like an end or a cancel — the contract's third
	# settling event, and the TypeScript wires `utterance.onerror` to the same finish.
	# Godot 4.7.2 has no TTS_UTTERANCE_ERROR constant — its fourth utterance event is
	# BOUNDARY, word boundaries, which must never settle a line — so the error event is
	# registered where the engine has it, by name.
	if ClassDB.class_has_integer_constant("DisplayServer", "TTS_UTTERANCE_ERROR"):
		DisplayServer.tts_set_utterance_callback(
			ClassDB.class_get_integer_constant("DisplayServer", "TTS_UTTERANCE_ERROR"),
			_on_utterance_done)


func speak(line: Dictionary) -> void:
	if _voices.is_empty():
		finished.emit.call_deferred()
		return

	var cast := Casting.for_speaker(String(line["speakerId"]))
	_utterance_id += 1
	_in_flight = _utterance_id
	DisplayServer.tts_speak(
		String(line["text"]),
		_pick(cast["prefer"]),
		50,                          # volume
		float(cast["pitch"]),
		float(cast["rate"]),
		_utterance_id,
		true)                        # interrupt: one line at a time, always


func stop() -> void:
	# Settle directly rather than trusting the CANCELED callback: it is process-wide and
	# delivered asynchronously, and a backend swap re-registers it to the new instance
	# microseconds later, so a line settled only there would never settle at all. Deferred,
	# so a swap's drain resumes after the new backend is installed. The callback is now the
	# backstop — a late CANCELED is ignored by the id check. The TypeScript's stop() calls
	# `settle?.()` directly for the same reason.
	DisplayServer.tts_stop()
	if _in_flight != -1:
		_in_flight = -1
		finished.emit.call_deferred()


func _on_utterance_done(id: int) -> void:
	if id != _in_flight:
		return
	_in_flight = -1
	finished.emit()


func _pick(prefer: Array) -> String:
	for wanted in prefer:
		for voice in _voices:
			if String(voice["name"]).begins_with(String(wanted)):
				return String(voice["id"])
	for voice in _voices:
		if String(voice["language"]).begins_with("en"):
			return String(voice["id"])
	return String(_voices[0]["id"])
