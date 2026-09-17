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
const TOKEN_SCENE := preload("res://world/tokens/token.tscn")
const MarkerGlyph := preload("res://world/marker.gd")

## Player-controlled tokens sit on this layer so the party's OmniLight can light them
## without they themselves shadowing it. Membership is isPlayerControlled, never "fighter".
const PARTY_RENDER_LAYER := 2
const PARTY_LIGHT_Y := 0.95
const PARTY_LOOK := {
	"FULL": {"range": 6.0, "energy": 2.6, "color": Color(1.00, 0.72, 0.40), "flicker": 0.03},
	"LOW": {"range": 4.6, "energy": 2.3, "color": Color(1.00, 0.60, 0.24), "flicker": 0.06},
	"GUTTERING": {"range": 3.4, "energy": 2.0, "color": Color(1.00, 0.50, 0.19), "flicker": 0.12},
	"FAILING": {"range": 2.3, "energy": 1.6, "color": Color(1.00, 0.40, 0.14), "flicker": 0.22},
}

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
	Table.prop_removed.connect(_on_prop_removed)
	Table.marker_placed.connect(_on_marker_placed)
	Table.mode_changed.connect(_on_mode_changed)
	Table.entity_added.connect(_on_entity_added)
	Table.entity_moved.connect(_on_entity_moved)
	Table.entity_died.connect(_on_entity_died)
	Table.strike.connect(_on_strike)
	Table.room_lighting_changed.connect(_on_room_lighting_changed)
	_on_scene_changed()
	_fit_world_viewport()
	_update_party_light()


func _process(_delta: float) -> void:
	_fit_world_viewport()
	_update_party_light()


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
	return Grid.to_world(x, y, _size_of(room_id), _origins.get(room_id, Vector3.ZERO))


## The exact inverse of grid_to_world, for the same room.
##
## Defaults to the room the party is in, which is what every caller holding a world-space point
## off the current board — a token's position, an overlay quad — actually means. It has to take
## the origin: read against a room standing anywhere but the entrance, an origin-centred inverse
## answers with a square that is off the board, and a click that resolves to nowhere is dropped
## in silence.
func world_to_grid(point: Vector3, room_id: String = "") -> Vector2i:
	var id := _room_id if room_id.is_empty() else room_id
	return Grid.to_square(point, _size_of(id), _origins.get(id, Vector3.ZERO))


## Whose floor a world-space point stands on — "" for a point on no floor at all.
##
## The current room answers first, because it is the only one whose answer is ever acted on and
## the only one that can be missing from the origin table (`_covers`).
func room_at(point: Vector3) -> String:
	if _covers(_room_id, point):
		return _room_id
	for id in _origins:
		var room_id := String(id)
		if room_id != _room_id and _covers(room_id, point):
			return room_id
	return ""


## Through _size_of, so a current room the server has somehow not placed is still clickable at
## the origin — the same guess grid_to_world makes. Refusing it here would leave a room drawn
## and every click on it dropped without a word.
func _covers(room_id: String, point: Vector3) -> bool:
	if room_id.is_empty():
		return false
	var size := _size_of(room_id)
	var at := world_to_grid(point, room_id)
	return at.x >= 0 and at.y >= 0 and at.x < size.x and at.y < size.y


## A room the client has not been told about is drawn at the current room's size, so that a diff
## naming one puts a token somewhere visibly wrong rather than at NaN. grid_to_world and its
## inverse have to make that guess identically or they stop being inverses.
func _size_of(room_id: String) -> Vector2i:
	return _sizes.get(room_id, Vector2i(_room_width(), _room_height()))


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
		_rebuild_markers()
		_rebuild_tokens()
		_follow_party()
		if rig:
			rig.settle(Table.mode)
		_refresh_overlay()
		return
	_sync_tokens()
	_follow_party()
	_refresh_overlay()


## Last, so highlights are painted through the room just taken — see overlay.gd's _ready.
func _refresh_overlay() -> void:
	var overlay := get_node_or_null("Overlay")
	if overlay != null and overlay.has_method("refresh"):
		overlay.refresh()


## ALERT moved this room's fires. Rebuild only its Torches and re-apply lighting — not the
## roomId-guarded full rebuild, which would kill the combat pull-back (comment above).
func _on_room_lighting_changed(room_id: String) -> void:
	var view: Dictionary = Table.room_by_id(room_id)
	if view.is_empty():
		return
	var node := _node_for_room(room_id)
	if node != null:
		node.rebuild_fires(view, _room_id)
	_snap_brazier_fires(room_id)
	var lighting := get_node_or_null("Room/Lighting")
	if lighting != null and lighting.has_method("apply"):
		lighting.apply()


## Snap every BRAZIER in this room to the room's current fires preset — without rebuilding props.
func _snap_brazier_fires(room_id: String) -> void:
	var holder := _props_of(room_id)
	if holder == null:
		return
	for child in holder.get_children():
		if child.has_method("apply_fires"):
			child.apply_fires(room_id)


func _node_for_room(room_id: String) -> Room:
	if room_id == _room_id:
		return get_node_or_null("Room") as Room
	return get_node_or_null("Neighbours/%s" % room_id) as Room


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
		# Told where the party is, not how bright to be. `Room.build` puts that through
		# `level_for` like every other room — one decision site is the whole point of it, and
		# two is how they drift.
		here.build(Table.room(), _room_id)

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
		node.build(view, _room_id)


## A secret coming out is always a secret in the room the party is standing in.
##
## Diff.PropRevealed carries no roomId and does not need one: `reveal_prop` is only ever offered
## the current room's hidden props, so the diff channel is current-room-scoped by convention.
func _on_prop_revealed(prop: Dictionary) -> void:
	_instance_prop(prop, _room_id)


func _on_prop_removed(prop_id: String) -> void:
	var holder := get_node_or_null("Props/%s" % _room_id)
	if holder == null:
		return
	var node := holder.get_node_or_null(NodePath(prop_id))
	if node != null:
		holder.remove_child(node)
		node.free()


func _on_marker_placed(marker: Dictionary) -> void:
	_instance_marker(marker)


func _rebuild_markers() -> void:
	var holder := get_node_or_null("Markers") as Node3D
	if holder == null:
		return
	for child in holder.get_children():
		holder.remove_child(child)
		child.free()
	if Table.scene.is_empty():
		return
	for marker in Table.scene.get("markers", []):
		_instance_marker(marker)


func _instance_marker(marker: Dictionary) -> void:
	var holder := _markers_of(_room_id)
	if holder == null:
		return
	var id := String(marker.get("id", ""))
	if id.is_empty() or holder.get_node_or_null(NodePath(id)) != null:
		return
	var glyph := MarkerGlyph.new()
	glyph.name = id
	glyph.position = grid_to_world(_room_id, int(marker.get("x", 0)), int(marker.get("y", 0)))
	holder.add_child(glyph)
	glyph.configure(marker)


func _markers_of(room_id: String) -> Node3D:
	var holder := get_node_or_null("Markers") as Node3D
	if holder == null or room_id.is_empty():
		return null
	var room := holder.get_node_or_null(NodePath(room_id)) as Node3D
	if room == null:
		room = Node3D.new()
		room.name = room_id
		holder.add_child(room)
	return room


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
	PropPlace.into(holder, prop, room_id,
		grid_to_world(room_id, int(prop.get("x", 0)), int(prop.get("y", 0))))


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


## One OmniLight3D, sibling of Tokens. Positioned each frame at the centroid of living
## player-controlled tokens. Cannot live under Room (MAX_TORCH_LIGHTS), on a token
## (_rebuild_tokens frees them), or on the hand bone (the chop swings). Spec §7a.
func _ensure_party_light() -> OmniLight3D:
	var light := get_node_or_null("PartyLight") as OmniLight3D
	if light != null:
		return light
	light = OmniLight3D.new()
	light.name = "PartyLight"
	light.omni_attenuation = 2.0
	light.shadow_enabled = true
	light.shadow_caster_mask = 0xFFFFF & ~(1 << (PARTY_RENDER_LAYER - 1))
	add_child(light)
	return light


func _update_party_light() -> void:
	var light := _ensure_party_light()
	var level := String(Table.scene.get("partyLight", "FULL"))
	_sync_carried_torches(level)
	if Table.scene.is_empty() or level == "OUT":
		light.visible = false
		return
	var look: Dictionary = PARTY_LOOK.get(level, PARTY_LOOK["FULL"])
	light.visible = true
	light.omni_range = float(look["range"])
	light.light_color = look["color"] as Color
	var flicker := float(look["flicker"])
	var wave := 1.0 + flicker * sin(float(Time.get_ticks_msec()) * 0.012)
	light.light_energy = float(look["energy"]) * wave
	var centre: Variant = _living_party_centroid()
	if centre != null:
		light.global_position = (centre as Vector3) + Vector3(0.0, PARTY_LIGHT_Y, 0.0)


func _living_party_centroid() -> Variant:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return null
	var acc := Vector3.ZERO
	var n := 0
	for child in holder.get_children():
		var token := child as Token
		if token == null or token.dead:
			continue
		var entity: Dictionary = Table.entity(String(token.name))
		if entity.is_empty() or not bool(entity.get("isPlayerControlled", false)):
			continue
		acc += token.global_position
		n += 1
	if n == 0:
		return null
	return acc / float(n)


func _sync_carried_torches(level: String) -> void:
	var holder := get_node_or_null("Tokens") as Node3D
	if holder == null:
		return
	for child in holder.get_children():
		if child is Token:
			(child as Token).sync_carried_torch(level)


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


## Window pixels → SubViewport pixels. Converts through the host container so an
## inset playfield cannot lie the way a 1:1 window-sized view used to get away with.
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
	# Which room the ray landed in, not merely whether it landed. A room the party has left is
	# still drawn and can still be pointed at, and read against the current room its floor
	# resolves to a perfectly plausible square in a room nobody clicked — the party walks
	# somewhere no one pointed at. Visible is not addressable.
	#
	# Dropped here rather than sent for the server to refuse, which is the opposite of what a
	# blocked square does. The two questions are different: whether a move is legal is the
	# server's (invariant #1), but which room the pointer is over is a fact about this frame
	# and this camera, and there is no square in the current room to name.
	if hit != null and room_at(hit) == _room_id:
		floor_square = world_to_grid(hit)
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
##
## With a neighbour drawn, that ray now lands on something: the row of the far room just past
## the opening. The doorway still wins there, and that is the decision — you are looking through
## an open door at the floor beyond it, and the door is the only thing on that ray anyone can
## act on. It is the sole thing a click on a room the party is not in can do, and it does the
## one crossing the server would accept anyway. `test_no_camera_corner_makes_a_room_you_are_not
## _in_clickable` sweeps all four corners over that whole floor to keep it the only thing.
func _target_under(origin: Vector3, dir: Vector3, floor_t: float) -> String:
	var best_id := ""
	var best_t := floor_t

	var walls := get_node_or_null("Room/Walls") as Node3D
	if walls != null:
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

	var props := get_node_or_null("Props/%s" % _room_id) as Node3D
	if props != null:
		for child in props.get_children():
			if not (child is Node3D):
				continue
			var prop := Table.prop(String(child.name))
			var actions: Variant = prop.get("actions", [])
			if actions.is_empty():
				continue
			var box := _visual_aabb(child as Node3D)
			if box.size == Vector3.ZERO:
				continue
			var t := _ray_aabb_t(origin, dir, box)
			if t < best_t:
				best_t = t
				best_id = String(child.name)

	var markers := get_node_or_null("Markers/%s" % _room_id) as Node3D
	if markers != null:
		for child in markers.get_children():
			if not (child is Node3D):
				continue
			var box := _visual_aabb(child as Node3D)
			if box.size == Vector3.ZERO:
				continue
			var t := _ray_aabb_t(origin, dir, box)
			if t < best_t:
				best_t = t
				best_id = String(child.name)

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
