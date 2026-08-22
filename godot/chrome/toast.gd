extends Label

const NO_DM := "No DM configured. Set VENICE_API_KEY in .env and restart the server."


func _ready() -> void:
	visible = false
	Table.errored.connect(_show)
	Net.hello.connect(func(_demo: bool, _voice: bool, has_dm: bool) -> void:
		if not has_dm:
			# A mute crypt with no explanation reads as a crash. Everything short of narration
			# still works on this path, which is what the debug bar is for.
			_show(NO_DM))


func _show(message: String) -> void:
	text = message
	visible = true
	var timer := get_tree().create_timer(6.0)
	await timer.timeout
	visible = false
