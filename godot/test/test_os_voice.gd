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
