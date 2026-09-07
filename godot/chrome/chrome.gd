extends CanvasLayer

## The frame around the room. Owns no game facts (invariant #3) — World, Chrome and SFX all
## subscribe to Table.
##
## Sfx is an autoload, not a child. The lid is hung off a hostile arriving, which in M0 is the
## only way anything appears. If a second spawn point is ever added, that becomes a property of
## the prop instead.


func _ready() -> void:
	Table.entity_moved.connect(func(_id, from, to) -> void:
		Sfx.footsteps(maxi(absi(to.x - from.x), absi(to.y - from.y))))
	Table.prop_revealed.connect(func(_p) -> void: Sfx.revealed())
	# The lid is hung off a hostile arriving, which in M0 is the only way anything appears.
	# If a second spawn point is ever added, this becomes a property of the prop instead.
	Table.entity_added.connect(func(e) -> void:
		if not e["isPlayerControlled"]:
			Sfx.lid_opens())
	# Initiative is rolled as a batch and never reaches the tray, so the sting is the only thing
	# announcing the fight until the bar arrives behind it.
	Table.combat_opened.connect(func(count: int) -> void:
		Sfx.combat_begins()
		Sfx.initiative_set(count))
	Table.strike.connect(func(_a, _t, hit: bool, _at) -> void: Sfx.swing(hit))
	Table.entity_died.connect(func(_id) -> void: Sfx.fell())

	# A server that is not up is a developer's loop, not a dead end — it is a Gradle process in
	# a terminal that gets restarted a dozen times an evening. The banner clears itself when
	# health comes back. Godot never starts a server and never kills one.
	Net.connected.connect(func() -> void: $Overlay/Banner.visible = false)
	Net.disconnected.connect(func(_reason: String) -> void:
		$Overlay/Banner.text = "Start the DM:  cd server && ./gradlew run"
		$Overlay/Banner.visible = true)
	$Overlay/Banner.text = "Start the DM:  cd server && ./gradlew run"
	$Overlay/Banner.visible = not Table.connected
	# World sits in a SubViewport. Clicks hit this Control. Viewport.gui_input
	# already made `event.position` local to WorldView — which is SubViewport
	# pixels while stretch is on, whether or not the playfield fills the window.
	%WorldView.gui_input.connect(_on_world_gui_input)


func _on_world_gui_input(event: InputEvent) -> void:
	var world := get_node_or_null("%WorldView/SubViewport/World")
	if world == null or not world.has_method("handle_pointer"):
		return
	if not (event is InputEventMouse):
		return
	# Already Control-local. Do not run viewport_from_window here.
	var viewport_pos: Vector2 = (event as InputEventMouse).position
	if event is InputEventMouseMotion:
		world.handle_pointer(viewport_pos, false)
		return
	if event is InputEventMouseButton:
		var button := event as InputEventMouseButton
		if button.button_index == MOUSE_BUTTON_LEFT:
			world.handle_pointer(viewport_pos, button.pressed)


func _unhandled_input(event: InputEvent) -> void:
	# Q/E live on the window viewport so the LineEdit can keep them when it has focus.
	# The camera itself sits inside a SubViewport and would otherwise never see a key.
	if not (event is InputEventKey):
		return
	var key := event as InputEventKey
	if not key.pressed or key.echo:
		return
	if key.keycode != KEY_Q and key.keycode != KEY_E:
		return
	var world := get_node_or_null("%WorldView/SubViewport/World")
	if world == null or world.rig == null:
		return
	world.rig.handle_rotate_keys(key)
	get_viewport().set_input_as_handled()
