extends Control

## A dead player character used to be only a dropped token: combat ended, the mode pill flipped
## back, and nothing said what had happened. With the input box refusing text and the board
## refusing clicks, that reads as a crash rather than a death.


func _ready() -> void:
	visible = false
	Table.scene_changed.connect(_check)
	$Restart.pressed.connect(_restart)
	_check()


func _check() -> void:
	var me := Table.entity("fighter")
	visible = Table.started and not me.is_empty() and int(me["hp"]) <= 0


func _restart() -> void:
	# Net stays up. Godot does not quit and does not restart the server — that was the browser's
	# location.reload, which Godot does not have and does not need.
	Table.expect_restart()
	Net.restart()
	# Table.reset() runs when the fresh scene lands, not here: resetting first would clear the
	# board while the old session's last diffs were still arriving.
