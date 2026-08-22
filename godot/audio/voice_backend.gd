class_name VoiceBackend
extends RefCounted

## The voice seam.
##
## Implementations: the HTTP MP3 from this project's server, and the OS synthesiser (Task 11).
## Both satisfy this contract, so Clock never learns which one is speaking.

## Emitted exactly once per [method speak] — on end, on error, or on cancel. Never twice, never
## zero times, and [b]never synchronously inside speak[/b]: the caller awaits this signal, and
## one emitted before it has reached the await stalls every line behind it forever. Emit with
## [code]finished.emit.call_deferred()[/code] if the answer is already known.
signal finished

## Say one line. Must always lead to exactly one [signal finished].
func speak(_line: Dictionary) -> void:
	finished.emit.call_deferred()

## Cut the line in the air, settling it. For errors only — Clock decides when this is right.
func stop() -> void:
	pass

## Start preparing a line that is coming but is not being spoken yet. A hint, not an
## instruction; a backend with nothing to prepare leaves this alone.
func prime(_line: Dictionary) -> void:
	pass
