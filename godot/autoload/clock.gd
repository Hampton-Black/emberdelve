extends Node

## The ordered audio queue, and the clock the rest of the game runs on.
##
## Narration streams a sentence at a time, and two sentences talking over each other is the
## single worst thing a spoken DM can do. Lines are spoken strictly in arrival order, one at a
## time, and a new turn drops whatever is still queued from the last one.
##
## It also [b]paces the text[/b]. The model streams a whole turn in about three seconds and the
## voice takes twenty to say it, so text that appears on arrival has the player speed-reading
## ahead of the narrator. Each line reveals itself as it starts being spoken.
##
## It is also the clock for [b]everything else the DM does[/b]. Narration, dice, hit point
## changes and mode switches all queue here in arrival order and are released when the voice
## reaches them. Before this existed, only the transcript was paced by the voice and the rest of
## the world ran on timers — invisible while the browser's own synthesiser read, and obvious the
## moment a real voice was three times slower: the goblin was landing blows twenty seconds
## before the narrator got round to saying it had appeared.
##
## Ported from client/src/audio/narration.ts.

var _backend: VoiceBackend = null
var _enabled := true

## Backends swapped out while their deferred `finished` was still pending. Held for two
## frames: a RefCounted freed before the flush takes the drain's await with it.
var _retiring: Array[VoiceBackend] = []

## Each entry: { "line": Dictionary or null, "reveal": Callable, "hold_ms": int }
var _pending: Array[Dictionary] = []
var _draining := false

## Bumped by [method silence]. The drain loop checks it before taking another line, which is how
## a cancelled turn stops without leaving a half-spoken sentence in the pipe.
var _generation := 0


func _ready() -> void:
	# Chosen from what the server says it can do, not guessed at here: whether a real voice
	# exists is a fact about the server's configuration. The OS synthesiser is always built,
	# because it is also the per-line fallback.
	Net.hello.connect(func(_demo: bool, has_voice: bool, _dm: bool) -> void:
		# Settle before building: a new OsVoice overwrites the process-wide TTS utterance
		# callback, which would strand the old one's line in the air.
		_settle_backend()
		var os_voice := OsVoice.new()
		use_backend(HttpVoice.new(os_voice, self) if has_voice else os_voice))


func use_backend(backend: VoiceBackend) -> void:
	_settle_backend()
	_backend = backend


## Swap safety, in order: stop settles the old backend's line in the air — with that, the
## drain parked on `await finished` resumes — then its nodes come off the tree, and only
## then may the new backend go in.
func _settle_backend() -> void:
	if _backend == null:
		return
	var old := _backend
	_backend = null
	old.stop()
	old.teardown()
	_retire(old)


## Keep a swapped-out backend referenced until its deferred `finished` has flushed.
func _retire(backend: VoiceBackend) -> void:
	_retiring.append(backend)
	await get_tree().process_frame
	await get_tree().process_frame
	_retiring.erase(backend)


func set_enabled(on: bool) -> void:
	_enabled = on
	if not on:
		silence()


func is_enabled() -> bool:
	return _enabled


## Queue a line. [param reveal] runs when the voice reaches it.
## An empty line is a marker, not silence to sit through.
func speak(line: Dictionary, reveal: Callable) -> void:
	if String(line.get("text", "")).strip_edges().is_empty():
		mark(reveal)
		return
	_pending.append({"line": line, "reveal": reveal, "hold_ms": 0})
	_drain.call_deferred()


## Queue a callback with no speech, so it lands in sequence rather than ahead of the voice.
##
## Deliberately still queued when the voice is off. The queue is the ordering, not the audio:
## turning the narrator off must change how long things take, never what order they happen in.
func mark(reveal: Callable) -> void:
	_pending.append({"line": null, "reveal": reveal, "hold_ms": 0})
	_drain.call_deferred()


## Queue a callback and then hold the queue open for a while afterwards.
##
## For a die: it takes the floor for as long as it is in the air, and the narration that commits
## to its result must not arrive before it lands. The hold is a timer rather than a signal from
## the tray, because if the tray never mounts the queue must still move on.
func hold(ms: int, reveal: Callable) -> void:
	_pending.append({"line": null, "reveal": reveal, "hold_ms": ms})
	_drain.call_deferred()


## Drop everything still queued, letting the line already in the air finish.
##
## Cutting a voice off mid-word is jarring in a way that cutting between sentences is not, and
## the player starting their next turn is not an emergency. A person interrupted finishes their
## sentence; so does this.
##
## Dropped lines are still revealed. The player interrupted the [i]speech[/i], not the record —
## losing text from the transcript because nobody got round to saying it would be a bug.
func silence() -> void:
	_generation += 1
	var dropped := _pending.duplicate()
	_pending.clear()
	for utterance in dropped:
		utterance["reveal"].call()


## Stop dead, mid-word. For errors, where continuing to talk would be worse than the cut.
func silence_now() -> void:
	silence()
	if _backend != null:
		_backend.stop()


## Kicked with call_deferred, never directly: a burst of lines arriving in one frame must all
## be queued before the first is popped, or the prime-ahead below has nothing to find and the
## first line of a turn is never primed. (The TypeScript starts the drain synchronously and its
## first-of-burst prime is silently lost; the port's own tests pin the deferred start.)
func _drain() -> void:
	if _draining:
		return
	_draining = true

	var mine := _generation
	if _backend == null:
		_backend = VoiceBackend.new()

	while not _pending.is_empty() and mine == _generation:
		var utterance: Dictionary = _pending.pop_front()
		utterance["reveal"].call()

		# Start the next line's synthesis before waiting on this one, so a remote voice spends
		# its round trip during playback instead of in the gap after it. One ahead only.
		for queued in _pending:
			if queued["line"] != null:
				_backend.prime(queued["line"])
				break

		# `_enabled` decides whether it is spoken, never whether it is queued.
		if utterance["line"] != null and _enabled:
			_backend.speak(utterance["line"])
			await _backend.finished

		if utterance["hold_ms"] > 0:
			# `ignore_time_scale` last: a paused or slowed tree must not stall the dice gate.
			await get_tree().create_timer(
					utterance["hold_ms"] / 1000.0, true, false, true).timeout

	_draining = false

	# The line the TypeScript gets from `finally`, written out by hand because GDScript has no
	# such thing. A silence() during the await above leaves the next turn's lines queued behind
	# a loop that has already given up on them; without this they are never spoken at all.
	if not _pending.is_empty():
		_drain()
