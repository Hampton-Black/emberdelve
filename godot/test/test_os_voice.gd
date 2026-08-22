extends GutTest

## Headless has no OS TTS (`FEATURE_TEXT_TO_SPEECH` is false), so the engine never fires
## the utterance callbacks here and the registrations themselves cannot be exercised. What
## can be pinned is the handler they all route to — ENDED, CANCELED and ERROR share
## `_on_utterance_done` — and its exactly-once settling.

var finished_count := 0


func before_each() -> void:
	finished_count = 0


func test_an_utterance_done_settles_the_in_flight_line_exactly_once() -> void:
	var voice := OsVoice.new()
	voice.finished.connect(func() -> void: finished_count += 1)
	voice._in_flight = 7
	voice._on_utterance_done(8)  # some other utterance — ignored
	voice._on_utterance_done(7)  # ours: end, cancel and error all land here
	voice._on_utterance_done(7)  # a second event for the same utterance is ignored
	assert_eq(finished_count, 1)


func test_stop_settles_the_line_directly_without_waiting_for_the_callback() -> void:
	# The CANCELED callback is process-wide and asynchronous, and a backend swap
	# re-registers it to the new instance — a line settled only there would never settle.
	# So stop() settles the line itself, deferred; the callback is the backstop.
	var voice := OsVoice.new()
	voice.finished.connect(func() -> void: finished_count += 1)
	voice._in_flight = 3  # a line in the air, as if the engine had taken it
	voice.stop()
	assert_eq(finished_count, 0, "the settle is deferred, never synchronous inside stop")
	await wait_process_frames(2)
	assert_eq(finished_count, 1, "stop settles the line itself, no engine callback needed")
	voice.stop()                 # a second stop has nothing to settle
	voice._on_utterance_done(3)  # a late CANCELED for the same utterance
	await wait_process_frames(2)
	assert_eq(finished_count, 1, "no re-emit from a second stop or a late callback")
