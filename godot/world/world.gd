class_name World
extends Node3D

## The 3D world, created once (invariant #4). Game facts live in Table (invariant #3).
##
## Grid conversion is the contract props and tokens hang off. A room is centred on its own origin
## in the world frame the server anchors at the entrance; +y on the grid is north, which is -Z in
## the world — the same mapping as `toWorld` in the old Three.js client (`assets.ts`).
##
## Every room the party has stood in stays drawn. Crossing a threshold moves the camera, not the
## dungeon: the origin table is never cleared and no room is ever re-centred, so the room behind
## you is still where you left it. How much of each one is drawn is `Room.level_for`.

const CameraRigScript := preload("res://world/camera_rig.gd")
const PROP_TABLE := preload("res://world/prop_table.tres")
const TOKEN_SCENE := preload("res://world/tokens/token.tscn")

var rig: CameraRigScript
var _room_id := ""
## Where each room sits in world space, and how big it is — absolute positions in the server's
## world frame, not offsets from wherever the party happens to be. Invariant #4 is untouched:
## this is one scene with several rectangles in it, not several scenes.
var _origins: Dictionary = {}
var _sizes: Dictionary = {}


func _ready() -> void:
	rig = get_node_or_null("Camera3D") as CameraRigScript
	Table.scene_changed.connect(_on_scene_changed)
	Table.prop_revealed.connect(_on_prop_revealed)
	Table.mode_changed.connect(_on_mode_changed)
	Table.entity_added.connect(_on_entity_added)
	Table.entity_moved.connect(_on_entity_moved)
	Table.entity_died.connect(_on_entity_died)
	Table.strike.connect(_on_strike)
	_on_scene_changed()
	_fit_world_viewport()


func _process(_delta: float) -> void:
	_fit_world_viewport()


func register_room(room_id: String, size: Vector2i, origin: Vector3) -> void:
	_sizes[room_id] = size
	_origins[room_id] = origin


func room_origin(room_id: String) -> Vector3:
	return _origins.get(room_id, Vector3.ZERO)


func current_room_id() -> String:
	return _room_id


## A square's centre in world space.
##
## Takes the room because there is more than one now. A room the client has not been told about
## falls back to the current one rather than to NaN — a diff naming an unknown room is a bug
## worth seeing as a token in the wrong place, not as a token that has vanished.
func grid_to_world(room_id: String, x: int, y: int) -> Vector3:
	var size: Vector2i = _sizes.get(room_id, Vector2i(_room_width(), _room_height()))
	var origin: Vector3 = _origins.get(room_id, Vector3.ZERO)
	return origin + Vector3(
		x - float(size.x) / 2.0 + 0.5,
		0.0,
		-(y - float(size.y) / 2.0 + 0.5),
	)


## The inverse, for the room the party is in — which is the only room anything is picked in.
## A neighbour is scenery until you walk into it, and clicking one is not a move the server
## would accept.
func world_to_grid(point: Vector3) -> Vector2i:
	var width := float(_room_width())
	var height := float(_room_height())
	return Vector2i(
		roundi(point.x + width / 2.0 - 0.5),
		roundi(-point.z + height / 2.0 - 0.5),
	)


func _on_scene_changed() -> void:
	var room_id := String(Table.scene.get("roomId", ""))
	_register_rooms()
	# A new room (a crossing, hello, reconnect, restart into a different id) snaps. The same room
	# emitting scene_changed is a token moving or a lid opening — follow, do not settle,
	# or the combat ceremony's pull-back never plays. Props rebuild on roomId only;
	# a reveal is Table.prop_revealed, not a second pass over the list.
	if room_id != _room_id:
		_room_id = room_id
		_rebuild_rooms()
		_rebuild_props()
		_rebuild_tokens()
		_follow_party()
		if rig:
			rig.settle(Table.mode)
		return
	_sync_tokens()
	_follow_party()


## Where every room the server has named stands, and how big it is.
##
## Absolute, and never cleared. A room keeps the place it was first given for the life of the
## session, so walking out of the crypt leaves the crypt where it was rather than sliding the
## dungeon under the party.
func _register_rooms() -> void:
	for view in Table.scene.get("rooms", []):
		var room_id := String(view.get("roomId", ""))
		if room_id.is_empty():
			continue
		register_room(room_id,
			Vector2i(int(view.get("width", 0)), int(view.get("height", 0))),
			Vector3(float(view.get("originX", 0.0)), 0.0, float(view.get("originZ", 0.0))))


## Every room the server has told us about, each drawn at the level `Room.level_for` decides.
##
## The room the party is in is the scene's own `Room` node, because that is the one thing on the
## board that can be acted on — picking scans `Room/Walls`, and a click has to mean the room they
## are standing in. Everything else goes under `Neighbours`: rooms already walked through, drawn
## DIM, and rooms only ever glimpsed through a doorway, drawn BLACK. One build path for all of
## them (`Room.build`), so the wall rule cannot come out differently on either side of a door.
func _rebuild_rooms() -> void:
	var here := get_node_or_null("Room") as Room
	if here != null:
		# Through the policy like everything else, rather than hardcoding LIT here. One decision
		# site is the whole point of `level_for`; two is how they drift.
		here.build(Table.room(), Room.level_for(Table.room(), _room_id))

	var holder := get_node_or_null("Neighbours")
	if holder == null:
		holder = Node3D.new()
		holder.name = "Neighbours"
		add_child(holder)
	# Out of the tree before it is freed, and freed now rather than at the end of the frame: the
	# room being rebuilt is very often one that was already drawn, and a node still holding the
	# name gets the newcomer renamed to `crypt2` — which is a room drawn twice, silently.
	for child in holder.get_children():
		holder.remove_child(child)
		child.free()

	for view in Table.scene.get("rooms", []):
		var room_id := String(view.get("roomId", ""))
		if room_id.is_empty() or room_id == _room_id:
			continue
		var node := Room.new()
		node.name = room_id
		holder.add_child(node)
		# Its own exits go in as openings — the doorway you are looking through has to be a hole
		# from both sides, or the far room is a rectangle of unbroken stone behind an open door.
		node.build(view, Room.level_for(view, _room_id))


## A secret coming out is always a secret in the room the party is standing in.
##
## Diff.PropRevealed carries no roomId and does not need one: `reveal_prop` is only ever offered
## the current room's hidden props, so the diff channel is current-room-scoped by convention.
func _on_prop_revealed(prop: Dictionary) -> void:
	_instance_prop(prop, _room_id)


## Everything standing on every floor the player can see, room by room.
##
## A room you have left keeps its furniture, and the secrets you found in it stay found — the
## server ships a visited room's visible props, so "as you left it" is not something the client
## has to remember.
##
## A room drawn BLACK is furnished by nobody. The server already withholds an unvisited room's
## props (`RoomView`), so this is the same policy asked twice — worth asking, because the failure
## it guards is a secret drawn on the floor of a room the player has never walked into, and the
## props holder is nowhere `_darken` can reach.
func _rebuild_props() -> void:
	var holder := get_node_or_null("Props") as Node3D
	if holder == null:
		return
	for child in holder.get_children():
		holder.remove_child(child)
		child.free()
	if Table.scene.is_empty():
		return
	for view in Table.scene.get("rooms", []):
		if Room.level_for(view, _room_id) == Room.Level.BLACK:
			continue
		var room_id := String(view.get("roomId", ""))
		for prop in view.get("props", []):
			_instance_prop(prop, room_id)


## Props hang under `Props/<room id>`, not `Props/<prop id>`.
##
## A prop id is only unique within its room — PropRef is room-qualified for exactly that reason,
## and PropPlacer calls every room's first pillar `pillar-0`. Keyed on the bare id, the second
## room's pillar finds the name taken and returns here quietly, which is a prop that never
## appears: this project's characteristic silent failure.
func _instance_prop(prop: Dictionary, room_id: String) -> void:
	var holder := _props_of(room_id)
	if holder == null:
		return
	var id := String(prop.get("id", ""))
	if id.is_empty() or holder.get_node_or_null(NodePath(id)) != null:
		return
	if bool(prop.get("hidden", false)):
		return
	var packed: PackedScene = PROP_TABLE.scene_for(String(prop.get("type", "")))
	if packed == null:
		return
	var node := packed.instantiate() as Node3D
	if node == null:
		return
	node.name = id
	node.position = grid_to_world(room_id, int(prop.get("x", 0)), int(prop.get("y", 0)))
	node.rotation.y = deg_to_rad(float(prop.get("rotation", 0.0)))
	holder.add_child(node)
	if node.has_method("configure"):
		node.configure(prop, room_id)


func _props_of(room_id: String) -> Node3D:
	var holder := get_node_or_null("Props") as Node3D
	if holder == null or room_id.is_empty():
		return null
	var room := holder.get_node_or_null(NodePath(room_id)) as Node3D
	if room == null:
		room = Node3D.new()
		room.name = room_id
		holder.add_child(room)
	return room


func _on_mode_changed(mode: String) -> void:
	if rig:
		rig.frame(mode)
	_set_bars_visible(mode == "COMBAT")


func _rebuild_tokens() -> void:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return
	for child in holder.get_children():
		holder.remove_child(child)
		child.free()
	if Table.scene.is_empty():
		return
	for entity in Table.scene.get("entities", []):
		_instance_token(entity)


func _sync_tokens() -> void:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return
	var live: Dictionary = {}
	for entity in Table.scene.get("entities", []):
		var id := String(entity.get("id", ""))
		live[id] = true
		var token := holder.get_node_or_null(NodePath(id)) as Token
		if token == null:
			_instance_token(entity)
			continue
		token.set_hp(int(entity.get("hp", 0)), int(entity.get("maxHp", 1)))
		var want := Vector2i(int(entity.get("x", 0)), int(entity.get("y", 0)))
		if world_to_grid(token.position) != want:
			token.slide_to(want)
	for child in holder.get_children():
		if not live.has(String(child.name)):
			holder.remove_child(child)
			child.free()


func _instance_token(entity: Dictionary) -> void:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return
	var id := String(entity.get("id", ""))
	if id.is_empty() or holder.get_node_or_null(NodePath(id)) != null:
		return
	var token := TOKEN_SCENE.instantiate() as Token
	if token == null:
		return
	token.name = id
	token.position = grid_to_world(_room_id, int(entity.get("x", 0)), int(entity.get("y", 0)))
	holder.add_child(token)
	token.configure(entity)
	token.set_bar_visible(Table.mode == "COMBAT")


func _on_entity_added(entity: Dictionary) -> void:
	_instance_token(entity)


func _on_entity_moved(entity_id: String, _from: Vector2i, to: Vector2i) -> void:
	var token := _token_node(entity_id)
	if token:
		token.slide_to(to)


func _on_entity_died(entity_id: String) -> void:
	var token := _token_node(entity_id)
	if token:
		token.die()


func _on_strike(actor_id: String, target_id: String, _connected: bool, _at: int) -> void:
	var actor := _token_node(actor_id)
	if actor == null:
		return
	var target := _token_node(target_id)
	if target:
		var delta: Vector3 = target.global_position - actor.global_position
		actor.face_towards(delta.x, delta.z)
	actor.swing()


func _set_bars_visible(in_combat: bool) -> void:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return
	for child in holder.get_children():
		if child is Token:
			(child as Token).set_bar_visible(in_combat)


func _token_node(entity_id: String) -> Token:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return null
	return holder.get_node_or_null(NodePath(entity_id)) as Token


func _follow_party() -> void:
	if rig == null:
		return
	var centre := Vector3.ZERO
	var count := 0
	for e in Table.scene.get("entities", []):
		if bool(e.get("isPlayerControlled", false)):
			centre += grid_to_world(_room_id, int(e["x"]), int(e["y"]))
			count += 1
	if count == 0:
		return
	rig.follow(centre / float(count))


func _fit_world_viewport() -> void:
	var vp := get_viewport()
	if vp == null or not (vp is SubViewport):
		return
	var host := vp.get_parent() as SubViewportContainer
	if host == null:
		return
	var win := host.get_viewport().get_visible_rect().size
	if win.x < 1.0 or win.y < 1.0:
		return
	if host.anchor_right != 1.0 or host.anchor_bottom != 1.0 \
			or not host.scale.is_equal_approx(Vector2.ONE):
		host.set_anchors_preset(Control.PRESET_FULL_RECT)
		host.offset_left = 0.0
		host.offset_top = 0.0
		host.offset_right = 0.0
		host.offset_bottom = 0.0
		host.position = Vector2.ZERO
		host.scale = Vector2.ONE
	host.stretch = true
	host.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	vp.snap_2d_transforms_to_pixel = false


func _unhandled_input(event: InputEvent) -> void:
	# SubViewportContainer.gui_input already pushed this event into the
	# SubViewport *and* Chrome already handled it in Control-local pixels.
	# Converting again (window → viewport) on the same motion parks the hover
	# a board-width away while the click — committed first — still looks right.
	if get_viewport() is SubViewport:
		return
	if event is InputEventMouseMotion:
		_route_pointer(event as InputEventMouse, false)
		return
	if event is InputEventMouseButton:
		var mouse := event as InputEventMouseButton
		if mouse.pressed and mouse.button_index == MOUSE_BUTTON_LEFT:
			_route_pointer(mouse, true)


## Window pixels → SubViewport pixels. WorldView fills the window 1:1; this
## still converts through the container so a scaled or offset host cannot lie.
static func viewport_from_host(host: SubViewportContainer, window_pos: Vector2) -> Vector2:
	if host == null:
		return window_pos
	return host.get_global_transform_with_canvas().affine_inverse() * window_pos


func viewport_from_window(window_pos: Vector2) -> Vector2:
	var vp := get_viewport()
	if vp == null or not (vp is SubViewport):
		return window_pos
	return viewport_from_host(vp.get_parent() as SubViewportContainer, window_pos)


## What is under the pointer: a figure if the ray touches one, otherwise the
## floor square it lands on. Tokens first — an isometric ray through a figure's
## head meets the floor a square or two behind it.
func pick_at(viewport_pos: Vector2) -> Dictionary:
	var nothing := {"entity_id": "", "target_id": "", "square": null}
	var cam := rig
	if cam == null:
		cam = get_node_or_null("Camera3D") as CameraRigScript
	if cam == null:
		return nothing
	var origin := cam.project_ray_origin(viewport_pos)
	var dir := cam.project_ray_normal(viewport_pos)
	if dir.is_zero_approx():
		return nothing

	var best_id := ""
	var best_t := INF
	var holder := get_node_or_null("Tokens") as Node3D
	if holder != null:
		for child in holder.get_children():
			var token := child as Token
			if token == null or token.dead:
				continue
			var t := _ray_aabb_t(origin, dir, _token_aabb(token))
			if t < best_t:
				best_t = t
				best_id = String(token.name)
	if not best_id.is_empty():
		var entity := Table.entity(best_id)
		var square: Variant = world_to_grid(holder.get_node(NodePath(best_id)).position)
		if not entity.is_empty():
			square = Vector2i(int(entity["x"]), int(entity["y"]))
		return {"entity_id": best_id, "target_id": "", "square": square}

	# The square under the ray is still worked out when something is hit, and it is still what
	# a move commits to.
	var floor_square: Variant = null
	var floor_t := INF
	var hit: Variant = Plane(Vector3.UP, 0.0).intersects_ray(origin, dir)
	if hit != null:
		var at := world_to_grid(hit)
		if at.x >= 0 and at.y >= 0 and at.x < _room_width() and at.y < _room_height():
			floor_square = at
			floor_t = origin.distance_to(hit)

	return {"entity_id": "", "target_id": _target_under(origin, dir, floor_t),
		"square": floor_square}


## The doorway under the pointer, if one is nearer than the floor. "" otherwise.
##
## Only things that can be acted on are pick targets, and today that is exactly the doorways.
## Scenery is not: an earlier cut made every prop a target on the theory that the seam would be
## useful later, and it broke the door on the first real room. Picking takes the nearest hit, so
## `brazier-east` — a 1x1 box around a narrow bowl, standing between the camera and the north
## wall — swallowed the click, had no intent to offer, and left the player standing still. A
## thing with nothing behind it must not shadow a thing that has something behind it. When props
## gain actions they are added here deliberately, ranked against each other, not on spec.
##
## A doorway is addressed by the id of the exit in it, and an Exit carries the id of the DOOR
## prop in its square (dm.model.Exit), so the id means the same thing everywhere.
##
## `floor_t` is what keeps the door from stealing ordinary moves: every floor square in the room
## is nearer to the camera than the wall behind it, so the doorway only wins when the ray leaves
## the room without touching floor — which is what pointing at a door in a wall looks like.
func _target_under(origin: Vector3, dir: Vector3, floor_t: float) -> String:
	var walls := get_node_or_null("Room/Walls") as Node3D
	if walls == null:
		return ""
	var best_id := ""
	var best_t := floor_t
	for child in walls.get_children():
		if not (child is Node3D) or not child.has_meta("exit_id"):
			continue
		var box := _visual_aabb(child as Node3D)
		if box.size == Vector3.ZERO:
			continue
		var t := _ray_aabb_t(origin, dir, box)
		if t < best_t:
			best_t = t
			best_id = String(child.get_meta("exit_id"))
	return best_id


func _visual_aabb(node: Node3D) -> AABB:
	var boxes: Array[AABB] = []
	for mesh in _visuals(node):
		boxes.append(mesh.global_transform * mesh.get_aabb())
	if boxes.is_empty():
		return AABB()
	var acc := boxes[0]
	for i in range(1, boxes.size()):
		acc = acc.merge(boxes[i])
	return acc


func _visuals(root: Node) -> Array[VisualInstance3D]:
	var found: Array[VisualInstance3D] = []
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is VisualInstance3D:
			found.append(node as VisualInstance3D)
		for child in node.get_children():
			stack.append(child)
	return found


func _route_pointer(mouse: InputEventMouse, clicked: bool) -> void:
	handle_pointer(viewport_from_window(mouse.global_position), clicked)


## Viewport-local pointer. Chrome forwards WorldView.gui_input here because
## World._unhandled_input never sees a click that a Control already consumed —
## the same SubViewport isolation that made Q/E a Chrome concern.
func handle_pointer(viewport_pos: Vector2, clicked: bool) -> void:
	var overlay := get_node_or_null("Overlay")
	if overlay == null or not overlay.has_method("intent") or not overlay.has_method("commit"):
		return
	var picked := pick_at(viewport_pos)
	var action: Dictionary = overlay.intent(
		String(picked.get("entity_id", "")),
		picked.get("square", null),
		String(picked.get("target_id", "")),
	)
	var hover_square: Variant = action.get("square", null) if not action.is_empty() else null
	var hover_kind := String(action.get("kind", "")) if not action.is_empty() else ""
	if clicked:
		overlay.commit(action)
		overlay.set_hover(hover_square, hover_kind)
		if get_viewport():
			get_viewport().set_input_as_handled()
	else:
		overlay.set_hover(hover_square, hover_kind)


func _token_aabb(token: Token) -> AABB:
	var p := token.global_position
	return AABB(Vector3(p.x - 0.4, 0.0, p.z - 0.4), Vector3(0.8, 1.2, 0.8))


func _ray_aabb_t(origin: Vector3, dir: Vector3, aabb: AABB) -> float:
	var min_p := aabb.position
	var max_p := aabb.end
	var tmin := 0.0
	var tmax := 500.0
	for i in 3:
		var o: float = origin[i]
		var d: float = dir[i]
		if absf(d) < 1e-8:
			if o < min_p[i] or o > max_p[i]:
				return INF
			continue
		var t1: float = (min_p[i] - o) / d
		var t2: float = (max_p[i] - o) / d
		if t1 > t2:
			var tmp := t1
			t1 = t2
			t2 = tmp
		tmin = maxf(tmin, t1)
		tmax = minf(tmax, t2)
		if tmin > tmax:
			return INF
	return tmin


func _room_width() -> int:
	return int(Table.room().get("width", 12))


func _room_height() -> int:
	return int(Table.room().get("height", 12))
