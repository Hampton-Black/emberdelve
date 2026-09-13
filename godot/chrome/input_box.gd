extends LineEdit

## Enter sends. Locked while the DM has the floor — a second turn typed underneath the first is
## a state the server has to drop as a collision.

const ACTOR := "fighter"   # M0's only party member; addressed by id, never assumed to be alone


func _ready() -> void:
	text_submitted.connect(_send)
	Table.transcript_changed.connect(_relock)
	Table.started_changed.connect(_relock)
	Table.scene_changed.connect(_relock)
	Net.connected.connect(_relock)
	Net.disconnected.connect(func(_reason: String) -> void: _relock())
	_relock()


func _send(typed: String) -> void:
	var line := typed.strip_edges()
	if line.is_empty() or not Table.connected or Table.awaiting_dm:
		return
	clear()
	# Local echo first, so the keypress is acknowledged inside 100ms with no model in the path.
	Table.say_as_player(line)
	Net.free_text(ACTOR, line)


func _relock() -> void:
	var over := Table.scene.get("ending") is Dictionary
	visible = not over
	editable = Table.connected and Table.started and not Table.awaiting_dm and not over
	placeholder_text = "The DM is speaking." if Table.awaiting_dm else "What do you do?"
