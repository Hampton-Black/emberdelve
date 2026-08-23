class_name CameraRig
extends Camera3D

## Four fixed isometric corners, 90 degrees apart, snapped. Never free orbit.
##
## Free orbit would break the look: the isometric read and grid picking only hold
## at these four angles, because they are the angles that resolve tile edges onto the same
## screen-space slopes every frame.
##
## Camera facing is view state, not a game fact — it does not live in Table.

const DISTANCE := 30.0
## atan(1 / sqrt(2)). The true isometric elevation.
const ELEVATION := 0.6154797086703873
const ROTATION_STEP := PI / 2.0
const ROTATION_SECONDS := 0.55

const EXPLORATION_HALF_HEIGHT := 5.4
const COMBAT_MAX_HALF_HEIGHT := 10.0
const FRAMING_SECONDS := 1.1
const FOLLOW_SECONDS := 0.34
## How far the exploration camera may push past the floor's edge to keep the party in shot.
## Not a follow deadzone — without it, clamp-to-room quietly cancels the follow.
const FOLLOW_SLACK := 1.4
const ROOM_MARGIN := 0.7

const WALL_UNIT := 1.0

var corner := 0
var half_height := EXPLORATION_HALF_HEIGHT

var _azimuth := PI / 4.0
var _azimuth_from := PI / 4.0
var _azimuth_to := PI / 4.0
var _rotation_t := 1.0

var _framing := "EXPLORATION"
var _framing_t := 1.0
var _framing_from := EXPLORATION_HALF_HEIGHT
var _focus := Vector3.ZERO
var _focus_from := Vector3.ZERO
var _follow_point := Vector3.ZERO


func _ready() -> void:
	projection = PROJECTION_ORTHOGONAL
	keep_aspect = KEEP_HEIGHT
	near = 0.1
	far = 200.0
	current = true
	_apply_camera()


func rotate_by(steps: int) -> void:
	# Start from the target, not the current angle, so rapid presses queue rather than fight.
	_azimuth_from = _azimuth
	_azimuth_to += float(steps) * ROTATION_STEP
	_rotation_t = 0.0
	corner = posmod(corner + steps, 4)


func frame(mode: String) -> void:
	if _framing == mode:
		return
	_framing = mode
	_framing_from = half_height
	_focus_from = _focus
	_framing_t = 0.0


func follow(point: Vector3) -> void:
	_follow_point = Vector3(point.x, 0.0, point.z)


## Drop onto a framing with no ease — a reconnect that lands mid-fight, or first mount.
func settle(mode: String) -> void:
	_framing = mode
	_framing_t = 1.0
	var target := _framing_target()
	half_height = target["half_height"]
	_focus = target["focus"]
	_framing_from = half_height
	_focus_from = _focus
	_apply_camera()


func _unhandled_input(event: InputEvent) -> void:
	# Keys arrive on the window viewport. Inside a SubViewport this node never sees them;
	# Chrome forwards Q/E in that case. Isolation tests parent us to the root, and those do.
	if get_viewport() is SubViewport:
		return
	_handle_rotate_keys(event)


func handle_rotate_keys(event: InputEvent) -> void:
	_handle_rotate_keys(event)


func _handle_rotate_keys(event: InputEvent) -> void:
	if event is InputEventKey and event.pressed and not event.echo:
		if event.keycode == KEY_Q:
			rotate_by(-1)
			get_viewport().set_input_as_handled()
		elif event.keycode == KEY_E:
			rotate_by(1)
			get_viewport().set_input_as_handled()


func _process(delta: float) -> void:
	_advance_rotation(delta)
	_advance_camera(delta)


func _advance_rotation(delta: float) -> void:
	if _rotation_t >= 1.0:
		return
	_rotation_t = minf(_rotation_t + delta / ROTATION_SECONDS, 1.0)
	var eased := _ease_in_out_cubic(_rotation_t)
	_azimuth = _azimuth_from + (_azimuth_to - _azimuth_from) * eased


func _advance_camera(delta: float) -> void:
	var target := _framing_target()
	if _framing_t < 1.0:
		_framing_t = minf(_framing_t + delta / FRAMING_SECONDS, 1.0)
		var eased := _ease_in_out_cubic(_framing_t)
		half_height = _framing_from + (target["half_height"] - _framing_from) * eased
		_focus = _focus_from.lerp(target["focus"], eased)
	else:
		half_height = target["half_height"]
		_focus = _focus.lerp(target["focus"], 1.0 - exp(-delta / FOLLOW_SECONDS))
	_apply_camera()


func _framing_target() -> Dictionary:
	var hh := _combat_half_height() if _framing == "COMBAT" else EXPLORATION_HALF_HEIGHT
	var wanted := Vector3.ZERO if _framing == "COMBAT" else _follow_point
	return {"half_height": hh, "focus": _clamp_to_room(wanted, hh)}


func _combat_half_height() -> float:
	var vp := _vp_size()
	var right := global_transform.basis.x
	var up := global_transform.basis.y
	var extent := _room_extent()
	var x := extent.x
	var z := extent.y
	var half_span_right := 0.0
	var half_span_up := 0.0
	for cx in [-x, x]:
		for cz in [-z, z]:
			for cy in [0.0, WALL_UNIT]:
				var c := Vector3(cx, cy, cz)
				half_span_right = maxf(half_span_right, absf(c.dot(right)))
				half_span_up = maxf(half_span_up, absf(c.dot(up)))
	var fitted := maxf(half_span_up, (half_span_right * vp.y) / maxf(vp.x, 1.0)) + ROOM_MARGIN
	return minf(fitted, COMBAT_MAX_HALF_HEIGHT)


func _clamp_to_room(focus: Vector3, p_half_height: float) -> Vector3:
	var vp := _vp_size()
	var half_width := (p_half_height * vp.x) / maxf(vp.y, 1.0)
	var extent := _room_extent()
	var x := extent.x
	var z := extent.y
	var corners: Array[Vector3] = [
		Vector3(-x, 0.0, -z),
		Vector3(x, 0.0, -z),
		Vector3(x, 0.0, z),
		Vector3(-x, 0.0, z),
	]
	var clamped := focus
	clamped = _clamp_axis(clamped, focus, global_transform.basis.x, half_width, corners)
	clamped = _clamp_axis(clamped, focus, global_transform.basis.y, p_half_height, corners)
	return clamped


func _clamp_axis(
		clamped: Vector3,
		focus: Vector3,
		axis: Vector3,
		half: float,
		corners: Array[Vector3],
) -> Vector3:
	var low := INF
	var high := -INF
	for c in corners:
		var span := c.dot(axis)
		low = minf(low, span)
		high = maxf(high, span)
	var at := focus.dot(axis)
	var want: float
	if high - low <= half * 2.0:
		want = (low + high) / 2.0
	else:
		want = clampf(at, low + half - FOLLOW_SLACK, high - half + FOLLOW_SLACK)
	return clamped + axis * (want - at)


func _apply_camera() -> void:
	var offset := Vector3(
		DISTANCE * cos(ELEVATION) * sin(_azimuth),
		DISTANCE * sin(ELEVATION),
		DISTANCE * cos(ELEVATION) * cos(_azimuth),
	)
	# Aim once at the raw focus so the camera's own basis is available to snap against.
	global_position = _focus + offset
	if is_inside_tree():
		look_at(_focus)
		force_update_transform()
	var aimed := _snap_focus(_focus)
	global_position = aimed + offset
	if is_inside_tree():
		look_at(aimed)
	size = half_height * 2.0


## Quantises the focus point to whole low-resolution pixels.
##
## Nothing about a camera that translates smoothly ever shows this at native resolution. At
## 960px with nearest-neighbour upscaling, a camera that moves by a fraction of a low-res pixel
## resamples the entire frame, and every edge in the room crawls. The focus is snapped so the
## camera can only ever move in exact pixel steps, which is what keeps the art still underneath
## it.
func _snap_focus(focus: Vector3) -> Vector3:
	if not World.PIXEL_LOOK:
		return focus
	var rows := maxf(1.0, roundf(get_viewport().size.y))
	var unit := (half_height * 2.0) / rows
	var right := global_transform.basis.x
	var up := global_transform.basis.y
	var along := roundf(focus.dot(right) / unit) * unit
	var above := roundf(focus.dot(up) / unit) * unit
	var forward := focus - right * focus.dot(right) - up * focus.dot(up)
	return forward + right * along + up * above


func _room_extent() -> Vector2:
	# Live from Table — width/height are scene facts, not view state.
	return Vector2(
		float(int(Table.scene.get("width", 12))) / 2.0,
		float(int(Table.scene.get("height", 12))) / 2.0,
	)


func _vp_size() -> Vector2:
	var vp := get_viewport()
	if vp == null:
		return Vector2(960.0, 540.0)
	var s := Vector2(vp.size)
	if s.x < 1.0 or s.y < 1.0:
		return Vector2(960.0, 540.0)
	return s


func _ease_in_out_cubic(t: float) -> float:
	if t < 0.5:
		return 4.0 * t * t * t
	return 1.0 - pow(-2.0 * t + 2.0, 3.0) / 2.0
