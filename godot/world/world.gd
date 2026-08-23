class_name World
extends Node3D

## The 3D world, created once (invariant #4). Game facts live in Table (invariant #3).
##
## Grid conversion is the contract later tasks hang props and tokens off. The room is centred
## on the origin; +y on the grid is north, which is -Z in the world — the same mapping as
## `toWorld` in `client/src/scene/assets.ts`.

const TARGET_WIDTH := 480
const CameraRigScript := preload("res://world/camera_rig.gd")

var rig: CameraRigScript


func _ready() -> void:
	rig = get_node_or_null("Camera3D") as CameraRigScript
	Table.scene_changed.connect(_on_scene_changed)
	Table.mode_changed.connect(_on_mode_changed)
	_on_scene_changed()
	if rig != null:
		rig.settle(Table.mode)
	_fit_pixel_viewport()


func _process(_delta: float) -> void:
	_fit_pixel_viewport()


func grid_to_world(x: int, y: int) -> Vector3:
	var width := float(_room_width())
	var height := float(_room_height())
	return Vector3(
		x - width / 2.0 + 0.5,
		0.0,
		-(y - height / 2.0 + 0.5),
	)


func world_to_grid(point: Vector3) -> Vector2i:
	var width := float(_room_width())
	var height := float(_room_height())
	return Vector2i(
		roundi(point.x + width / 2.0 - 0.5),
		roundi(-point.z + height / 2.0 - 0.5),
	)


func _on_scene_changed() -> void:
	if rig == null:
		return
	_follow_party()


func _on_mode_changed(mode: String) -> void:
	if rig == null:
		return
	rig.frame(mode)


func _follow_party() -> void:
	var centre := Vector3.ZERO
	var count := 0
	for e in Table.scene.get("entities", []):
		if bool(e.get("isPlayerControlled", false)):
			centre += grid_to_world(int(e["x"]), int(e["y"]))
			count += 1
	if count == 0:
		return
	rig.follow(centre / float(count))


func _fit_pixel_viewport() -> void:
	var vp := get_viewport()
	if vp == null or not (vp is SubViewport):
		return
	var host := vp.get_parent() as SubViewportContainer
	if host == null:
		return
	# stretch=true copies the container's unscaled size onto the SubViewport and refuses a
	# manual size. Keep the container 480 wide and scale it to the window so the world is
	# pixelated and the overlay chrome, a sibling, is not.
	var win := host.get_viewport().get_visible_rect().size
	if win.x < 1.0 or win.y < 1.0:
		return
	var pixel_h := maxi(1, int(round(float(TARGET_WIDTH) * win.y / win.x)))
	var zoom := win.x / float(TARGET_WIDTH)
	var pixel := Vector2(float(TARGET_WIDTH), float(pixel_h))
	if host.anchor_right != host.anchor_left or host.size != pixel \
			or not host.scale.is_equal_approx(Vector2(zoom, zoom)):
		host.set_anchors_preset(Control.PRESET_TOP_LEFT)
		host.position = Vector2.ZERO
		host.size = pixel
		host.scale = Vector2(zoom, zoom)
	host.stretch = true
	host.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST


func _room_width() -> int:
	return int(Table.scene.get("width", 12))


func _room_height() -> int:
	return int(Table.scene.get("height", 12))
