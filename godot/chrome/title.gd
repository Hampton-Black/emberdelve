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
	gui_input.connect(_on_click)
	visible = not Table.started


func _on_click(event: InputEvent) -> void:
	if not (event is InputEventMouseButton and event.pressed):
		return
	if Table.started:
		return
	# Order matters and is the whole mechanism: take the floor, then ask for the opening. The
	# DM has it from this moment, not from the moment its first token lands — those are about
	# 700ms apart, and in that window the input box and the debug bar were live.
	Table.set_started()
	Net.begin()
