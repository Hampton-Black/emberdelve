extends GutTest

## No server and no OS voice headless, so nothing here ever hears audio. What can be pinned
## is the settling contract: a line waiting on the server is still settled by stop(), and a
## prime never occupies the fetch the spoken line needs.

## A fallback that counts stops and holds its line until told.
class CountingVoice extends VoiceBackend:
	var stopped := 0
	var spoken: Array[String] = []
	var _speaking := false

	func speak(line: Dictionary) -> void:
		spoken.append(line["text"])
		_speaking = true

	func stop() -> void:
		stopped += 1
		if _speaking:
			_speaking = false
			finished.emit.call_deferred()


var fallback: CountingVoice
var voice: HttpVoice
var finished_count := 0
var _on_finished: Callable


func before_each() -> void:
	fallback = CountingVoice.new()
	voice = HttpVoice.new(fallback, self)
	finished_count = 0
	_on_finished = func() -> void: finished_count += 1
	voice.finished.connect(_on_finished)


func after_each() -> void:
	voice.finished.disconnect(_on_finished)
	voice.stop()
	voice.teardown()
	await wait_process_frames(2)


func test_stop_settles_a_line_waiting_on_the_server() -> void:
	# The request is in flight and nothing headless will answer it. stop() must still
	# settle the line: AudioStreamPlayer.stop() emits nothing, and a drain parked on
	# `await finished` never resumes. The TypeScript holds `settle` for exactly this.
	voice.speak({"speakerId": "narrator", "text": "a line nothing will answer"})
	voice.stop()
	await wait_process_frames(3)
	assert_eq(finished_count, 1, "exactly one finished, from the stop")
	assert_eq(fallback.stopped, 1, "the fallback is stopped too, as the TypeScript does")


func test_stop_with_nothing_in_the_air_emits_nothing() -> void:
	voice.stop()
	await wait_process_frames(3)
	assert_eq(finished_count, 0, "one finished per speak, never one per stop")


func test_a_prime_never_blocks_the_spoken_lines_own_fetch() -> void:
	# Clock's drain primes the next line and then speaks the current one. A single
	# in-flight slot lets the prime occupy it and the speak never fetches — the line
	# never plays and never finishes. One request per line, like the TypeScript's map.
	var line_one := {"speakerId": "narrator", "text": "line one"}
	var line_two := {"speakerId": "narrator", "text": "line two"}
	voice.prime(line_two)
	voice.speak(line_one)
	assert_true(voice._requests.has(HttpVoice.key_of(line_one)),
		"the spoken line fetches its own bytes")
	assert_true(voice._requests.has(HttpVoice.key_of(line_two)),
		"the primed line keeps its own request")


func test_speaking_a_primed_line_joins_its_request() -> void:
	var line := {"speakerId": "narrator", "text": "coming next"}
	voice.prime(line)
	voice.speak(line)
	assert_eq(voice._requests.size(), 1, "prime and speak share one request")


func test_a_torn_down_voice_is_collected() -> void:
	# The fallback signals back into the voice while the voice holds the fallback: without
	# a weak link that is a RefCounted cycle, and every swapped-out backend would leak.
	var v := HttpVoice.new(CountingVoice.new(), self)
	var weak: WeakRef = weakref(v)
	var p: AudioStreamPlayer = v._player
	v.stop()
	v.teardown()
	v = null
	await wait_process_frames(3)
	assert_false(is_instance_valid(p), "the player node is freed")
	assert_null(weak.get_ref(), "no reference cycle keeps the voice alive")
