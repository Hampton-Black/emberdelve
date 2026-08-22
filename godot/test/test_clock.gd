extends GutTest

## A backend that finishes when told, so the tests can hold a line in the air on purpose.
class HeldVoice extends VoiceBackend:
	var spoken: Array[String] = []
	var primed: Array[String] = []
	var stopped := 0
	var _speaking := false

	func speak(line: Dictionary) -> void:
		spoken.append(line["text"])
		_speaking = true

	func prime(line: Dictionary) -> void:
		primed.append(line["text"])

	func stop() -> void:
		stopped += 1
		release()

	## Let the line in the air finish.
	func release() -> void:
		if _speaking:
			_speaking = false
			finished.emit.call_deferred()


var voice: HeldVoice
var log: Array[String]


func before_each() -> void:
	log = []
	# Settle the previous test before swapping anything out from under it. silence_now() must
	# reach the voice that still has a line in the air, and that voice must still be referenced
	# when its deferred `finished` flushes — a RefCounted is freed the moment the last reference
	# goes, and a freed backend's emission never reaches the drain awaiting it, leaving
	# `_draining` stuck true for every test after. Two frames guarantees a flush lands while
	# `voice` still holds the old backend, whatever point in the frame this runs at.
	Clock.silence_now()
	await get_tree().process_frame
	await get_tree().process_frame
	voice = HeldVoice.new()
	Clock.use_backend(voice)
	Clock.set_enabled(true)


func after_each() -> void:
	# Same settle as before_each, at script end: Task 9's hello handler swaps Clock's backend,
	# and a HeldVoice still in the air would be freed before its deferred finished flushes.
	Clock.silence_now()
	await get_tree().process_frame
	await get_tree().process_frame


func _note(what: String) -> Callable:
	return func() -> void: log.append(what)


func test_marks_run_in_arrival_order() -> void:
	Clock.mark(_note("one"))
	Clock.mark(_note("two"))
	Clock.mark(_note("three"))
	await wait_frames(3)
	assert_eq(log, ["one", "two", "three"])


func test_a_line_reveals_when_the_voice_reaches_it_not_when_it_arrives() -> void:
	Clock.speak({"speakerId": "narrator", "text": "first"}, _note("first"))
	Clock.speak({"speakerId": "narrator", "text": "second"}, _note("second"))
	await wait_frames(2)
	# The first line is still in the air; the second must not have revealed.
	assert_eq(log, ["first"])
	voice.release()
	await wait_frames(3)
	assert_eq(log, ["first", "second"])


func test_a_hold_keeps_the_floor_for_its_whole_duration() -> void:
	Clock.hold(300, _note("dice"))
	Clock.mark(_note("narration"))
	await wait_frames(3)
	assert_eq(log, ["dice"], "narration must not arrive while the die is in the air")
	await wait_seconds(0.4)
	assert_eq(log, ["dice", "narration"])


func test_silence_drops_the_queue_but_still_reveals_every_dropped_line() -> void:
	# Interrupting stops the speech, not the record. Text that vanished from the transcript
	# because nobody got round to saying it would be a bug.
	Clock.speak({"speakerId": "narrator", "text": "in the air"}, _note("in the air"))
	await wait_frames(2)
	Clock.speak({"speakerId": "narrator", "text": "dropped"}, _note("dropped"))
	Clock.speak({"speakerId": "narrator", "text": "also dropped"}, _note("also dropped"))
	Clock.silence()
	assert_eq(log, ["in the air", "dropped", "also dropped"])
	assert_eq(voice.spoken, ["in the air"], "dropped lines are revealed, never spoken")


func test_silence_lets_the_line_in_the_air_finish() -> void:
	Clock.speak({"speakerId": "narrator", "text": "mid-sentence"}, _note("mid-sentence"))
	await wait_frames(2)
	Clock.silence()
	assert_eq(voice.stopped, 0, "cutting a voice mid-word is for errors only")


func test_silence_now_cuts_dead() -> void:
	Clock.speak({"speakerId": "narrator", "text": "mid-sentence"}, _note("mid-sentence"))
	await wait_frames(2)
	Clock.silence_now()
	assert_eq(voice.stopped, 1)


func test_a_turn_queued_during_an_interruption_is_still_spoken() -> void:
	# The regression this whole test file exists for. A silence() landing while a line is in
	# the air leaves the next turn's lines behind a drain loop that has already given up on
	# them. In TypeScript a `finally` re-enters the loop; GDScript has no finally, so the
	# re-entry is written by hand — and without it, the first turn after every interruption is
	# silently lost.
	Clock.speak({"speakerId": "narrator", "text": "old turn"}, _note("old turn"))
	await wait_frames(2)
	Clock.silence()
	Clock.speak({"speakerId": "narrator", "text": "new turn"}, _note("new turn"))
	voice.release()
	await wait_frames(4)
	assert_has(voice.spoken, "new turn", "the new turn must still reach the voice")


func test_with_the_voice_off_lines_still_queue_and_still_reveal() -> void:
	# `enabled` decides whether it is spoken, never whether it is queued: turning the narrator
	# off changes how long things take, never what order they happen in.
	Clock.set_enabled(false)
	Clock.speak({"speakerId": "narrator", "text": "one"}, _note("one"))
	Clock.mark(_note("two"))
	Clock.speak({"speakerId": "narrator", "text": "three"}, _note("three"))
	await wait_frames(4)
	assert_eq(log, ["one", "two", "three"])
	assert_eq(voice.spoken, [], "nothing is said with the narrator off")


func test_holds_are_honoured_with_the_voice_off() -> void:
	# The animation runs either way, so the die still takes the floor.
	Clock.set_enabled(false)
	Clock.hold(300, _note("dice"))
	Clock.mark(_note("after"))
	await wait_frames(3)
	assert_eq(log, ["dice"])
	await wait_seconds(0.4)
	assert_eq(log, ["dice", "after"])


func test_an_empty_line_is_a_marker_not_a_silence_to_sit_through() -> void:
	Clock.speak({"speakerId": "narrator", "text": "   "}, _note("blank"))
	Clock.mark(_note("after"))
	await wait_frames(3)
	assert_eq(log, ["blank", "after"])
	assert_eq(voice.spoken, [])


func test_exactly_one_line_is_primed_ahead() -> void:
	# Far enough that synthesis finishes while the previous line plays, near enough that an
	# interrupted turn wastes at most one line of a metered API.
	Clock.speak({"speakerId": "narrator", "text": "one"}, _note("one"))
	Clock.speak({"speakerId": "narrator", "text": "two"}, _note("two"))
	Clock.speak({"speakerId": "narrator", "text": "three"}, _note("three"))
	await wait_frames(2)
	assert_eq(voice.primed, ["two"])
