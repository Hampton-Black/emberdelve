@tool
extends EditorPlugin

## Dock for emberdelve-4h9.10. Game run does not load editor plugins. Nothing here is
## on the shipped path.

const Preview := preload("res://dev/preview_room.gd")

var _dock: Control
var _pick: OptionButton
var _status: Label
var _size := Vector2i.ZERO
var _room_id := ""
var _seen: Dictionary = {}
var _loading := false


func _enter_tree() -> void:
	_dock = _make_dock()
	add_control_to_dock(DOCK_SLOT_LEFT_UL, _dock)
	set_process(true)


func _exit_tree() -> void:
	set_process(false)
	remove_control_from_docks(_dock)
	_dock.free()
	_dock = null


func _make_dock() -> Control:
	var box := VBoxContainer.new()
	box.name = "RoomPreviewDock"
	var title := Label.new()
	title.text = "Room Preview"
	box.add_child(title)
	var row := HBoxContainer.new()
	_pick = OptionButton.new()
	_pick.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_refresh_rooms()
	_pick.item_selected.connect(_on_pick)
	row.add_child(_pick)
	var load_btn := Button.new()
	load_btn.text = "Load"
	load_btn.pressed.connect(_load)
	row.add_child(load_btn)
	box.add_child(row)
	var hint := Label.new()
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hint.text = "Open a blank 3D scene, never world.tscn. Turn off Preview Sun and Preview Environment before judging how a mesh looks."
	box.add_child(hint)
	_status = Label.new()
	_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	box.add_child(_status)
	return box


func _refresh_rooms() -> void:
	_pick.clear()
	for id in Preview.list_rooms():
		_pick.add_item(id)
	var want := Preview.picked_room()
	for i in _pick.item_count:
		if _pick.get_item_text(i) == want:
			_pick.select(i)
			break


func _on_pick(index: int) -> void:
	Preview.last_room = _pick.get_item_text(index)


func _load() -> void:
	var root := EditorInterface.get_edited_scene_root()
	var room_id := Preview.picked_room()
	if _pick.selected >= 0:
		room_id = _pick.get_item_text(_pick.selected)
	Preview.last_room = room_id
	_loading = true
	var report: Dictionary = Preview.build_into(root, room_id)
	if report.has("error"):
		_status.text = String(report["error"])
		push_error("preview_room: %s" % report["error"])
		_room_id = ""
		_seen.clear()
		_loading = false
		return
	_room_id = room_id
	_size = Vector2i(int(report["width"]), int(report["height"]))
	_seen.clear()
	_capture(root)
	_status.text = "%s — %dx%d, %d floor, %d walls, %d of %d props" % [
		report["roomId"], report["width"], report["height"],
		report["floor"], report["walls"], report["props"], report["propsTotal"],
	]
	if not (report["skipped"] as Array).is_empty():
		_status.text += "\nno mesh for %s" % ", ".join(report["skipped"])
	_loading = false


func _capture(root: Node) -> void:
	var props := root.get_node_or_null("RoomPreview/Props") as Node3D
	if props == null:
		return
	for child in props.get_children():
		var node := child as Node3D
		if node == null:
			continue
		var square := Grid.to_square(node.position, _size)
		var rot := posmod(roundi(rad_to_deg(node.rotation.y)), 360)
		_seen[String(node.name)] = {"x": square.x, "y": square.y, "rotation": rot}


func _process(_dt: float) -> void:
	if _loading or _room_id.is_empty() or _size == Vector2i.ZERO:
		return
	var root := EditorInterface.get_edited_scene_root()
	if root == null or root is World:
		return
	var props := root.get_node_or_null("RoomPreview/Props") as Node3D
	if props == null:
		return
	for child in props.get_children():
		var node := child as Node3D
		if node == null:
			continue
		var square := Grid.to_square(node.position, _size)
		var rot := posmod(roundi(rad_to_deg(node.rotation.y)), 360)
		var snap := Grid.to_world(square.x, square.y, _size)
		if not node.position.is_equal_approx(snap):
			node.position = snap
		if absf(node.rotation.x) > 0.0001 or absf(node.rotation.z) > 0.0001 \
				or not is_equal_approx(node.rotation.y, deg_to_rad(float(rot))):
			node.rotation = Vector3(0.0, deg_to_rad(float(rot)), 0.0)
		var was: Dictionary = _seen.get(String(node.name), {})
		if was.get("x") == square.x and was.get("y") == square.y \
				and was.get("rotation") == rot:
			continue
		_seen[String(node.name)] = {"x": square.x, "y": square.y, "rotation": rot}
		if was.is_empty():
			continue
		var err := Preview.write_room_prop(_room_id, String(node.name),
			square.x, square.y, rot)
		if err.is_empty():
			_status.text = "wrote %s → (%d, %d) r%d" % [node.name, square.x, square.y, rot]
		else:
			_status.text = err
