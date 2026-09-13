extends HBoxContainer

## A real roll down the real path, with no model in it — and the same for a whole fight.
##
## Locked while the DM has the floor. Starting a fight halfway through the opening sentence is a
## state the game cannot otherwise reach, and it produced exactly the collision the lock refuses.

const BUTTONS := [
	["roll d20", {"type": "debugRoll", "actorId": "fighter", "skill": "PERCEPTION",
		"difficulty": "MEDIUM"}],
	["reveal", {"type": "debugReveal", "propId": "alcove"}],
	["spawn goblin", {"type": "debugSpawnGoblin"}],
	["start combat", {"type": "debugStartCombat"}],
	["combat mode", {"type": "debugSetMode", "mode": "COMBAT"}],
	["explore mode", {"type": "debugSetMode", "mode": "EXPLORATION"}],
	["re-open", {"type": "debugOpen"}],
	["resend scene", {"type": "debugScene"}],
]


func _ready() -> void:
	for pair in BUTTONS:
		var button := Button.new()
		button.text = pair[0]
		var message: Dictionary = pair[1]
		# bind copies the payload now. A loop-captured lambda would send the last
		# message from every button — GDScript closures close over the variable.
		button.pressed.connect(_send.bind(message))
		add_child(button)
	Table.transcript_changed.connect(_relock)
	Table.started_changed.connect(_relock)
	Table.scene_changed.connect(_relock)
	_relock()


func _send(message: Dictionary) -> void:
	Net.debug(message)


func _relock() -> void:
	visible = not (Table.scene.get("ending") is Dictionary)
	for button in get_children():
		(button as Button).disabled = Table.awaiting_dm


## F12 writes the current frame to a file. Not a nicety: Phase B's work is judged by comparing
## against the browser client at the same camera corner, and a frame on disk is something a
## reviewer — human or otherwise — can actually open. It also survives the session, which a
## glance at a running window does not.
func _unhandled_key_input(event: InputEvent) -> void:
	if not (event is InputEventKey and event.pressed and event.keycode == KEY_F12):
		return
	var image := get_viewport().get_texture().get_image()
	var path := "user://shot-%d.png" % Time.get_ticks_msec()
	image.save_png(path)
	print("[debug] wrote ", ProjectSettings.globalize_path(path))
