extends Control

## The click that starts the session.
##
## The opening is asked for by the client, not pushed on connect: a socket opening is not a
## player arriving. In the browser this click also unlocked audio; Godot has no such gate, but
## the click stays the trigger for two other reasons. The server's opening is guarded
## once-per-session and only `begin` asks past the guard, and restart clears that guard — so
## the title is also how the second session gets narrated.
##
## The room is already built underneath this by the time it is shown. Connecting at boot rather
## than at the click is what lets the crypt exist before anyone speaks.


func _ready() -> void:
	Table.started_changed.connect(func() -> void: visible = not Table.started)
	# Table mirrors `connected` but has no signal of its own; Net is what actually flips.
	Net.connected.connect(_refresh)
	Net.disconnected.connect(func(_reason: String) -> void: _refresh())
	gui_input.connect(_on_click)
	visible = not Table.started
	_refresh()


func _refresh() -> void:
	# The TS button is `disabled={!connected}` and the browser dims it. There is no button
	# here — the whole overlay is the click — so the title itself goes dim until the socket is up.
	modulate.a = 1.0 if Table.connected else 0.45


func _on_click(event: InputEvent) -> void:
	if not (event is InputEventMouseButton and event.pressed):
		return
	# A click with the socket down would hide the title forever and drop `begin` on the floor;
	# the opening is once-per-session, so that silence is the rest of the game.
	if Table.started or not Table.connected:
		return
	# Order matters and is the whole mechanism: take the floor, then ask for the opening. The
	# DM has it from this moment, not from the moment its first token lands — those are about
	# 700ms apart, and in that window the input box and the debug bar were live.
	Table.set_started()
	Net.begin()
