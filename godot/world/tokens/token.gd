class_name Token
extends Node3D

## A KayKit figure on a base, addressed by the entity's id. The server already decided
## the square; this node only shows it.

const IMPACT_SECONDS := 0.22
const DRAIN_SECONDS := 0.34
const BAR_WIDTH := 0.72
const BAR_HEIGHT := 0.085
const CROSSFADE_SECONDS := 0.2
# Just enough to read the figure in the dark between braziers. 0.22 was a floodlight.
const SELF_LIT := 0.05
## Same layer World.PARTY_RENDER_LAYER uses. Party VisualInstance3Ds sit here so the
## carried torch lights them without they themselves shadowing it.
const PARTY_RENDER_LAYER := 2
const TORCH_LIT := "res://world/kits/kaykit_dungeon/torch_lit.gltf"
const TORCH_OUT := "res://world/kits/kaykit_dungeon/torch.gltf"

const MODELS := {
	"fighter": {
		"path": "res://world/kits/characters/kaykit_adventurers/Knight.glb",
		"height": 0.8,
	},
	"goblin": {
		"path": "res://world/kits/characters/kaykit_skeletons/Skeleton_Warrior.glb",
		"height": 0.68,
	},
	"brute": {
		"path": "res://world/kits/characters/kaykit_skeletons/Skeleton_Warrior.glb",
		"height": 0.68,
	},
}

const CLIPS := {
	"idle": {
		"path": "res://world/kits/characters/kaykit_animations/Rig_Medium_General.glb",
		"clip": "Idle_A", "loop": true,
	},
	"walk": {
		"path": "res://world/kits/characters/kaykit_animations/Rig_Medium_MovementBasic.glb",
		"clip": "Walking_A", "loop": true,
	},
	"attack-melee-right": {
		"path": "res://world/kits/characters/kaykit_animations/Rig_Medium_CombatMelee.glb",
		"clip": "Melee_1H_Attack_Chop", "loop": false,
	},
	"die": {
		"path": "res://world/kits/characters/kaykit_animations/Rig_Medium_General.glb",
		"clip": "Death_A", "loop": false,
	},
}

const BASE_COLOR := {
	"fighter": Color(0.839216, 0.788235, 0.658824),
	"goblin": Color(0.560784, 0.290196, 0.239216),
	"brute": Color(0.560784, 0.290196, 0.239216),
}

const FALLBACK := Color(0.8, 0.8, 0.8)
const ALLY := Color(0.498039, 0.682353, 0.337255)
const FOE := Color(0.721569, 0.227451, 0.188235)

static var _library: AnimationLibrary

var hp := 0
var max_hp := 1
var shown_hp := 0.0
var _drain_from := 0.0
var _drain_t := 1.0
var _drain_delay := 0.0
var _dying := 0.0
var _bar_wanted := false
var _hostile := true
var dead := false
var _player: AnimationPlayer
var _current := ""
var _tween: Tween
var _fill: MeshInstance3D
var _fill_mat: StandardMaterial3D
var _height := 0.8
var _torch_attach: BoneAttachment3D
var _torch_out := false


func configure(entity: Dictionary) -> void:
	name = String(entity.get("id", name))
	hp = int(entity.get("hp", 0))
	max_hp = int(entity.get("maxHp", 1))
	shown_hp = float(hp)
	_hostile = not bool(entity.get("isPlayerControlled", false))
	dead = hp <= 0
	_paint_base(String(entity.get("kind", "")))
	_fill = get_node_or_null("Bar/Fill") as MeshInstance3D
	_ensure_bar_materials()
	_draw_bar()
	_mount_figure(String(entity.get("kind", "")))
	_play("idle")
	if dead:
		_drop(true)
	# Three-quarter view: facing the default camera corner, like a figure on a table.
	face_towards(1.0, 1.0)


func slide_to(square: Vector2i) -> void:
	var world := _host_world()
	if world == null:
		return
	var to: Vector3 = world.grid_to_world(world.current_room_id(), square.x, square.y)
	var from := position
	if from.distance_squared_to(to) < 1e-6:
		return
	face_towards(to.x - from.x, to.z - from.z)
	var here: Vector2i = world.world_to_grid(from)
	var squares := maxi(absi(square.x - here.x), absi(square.y - here.y))
	_play("walk")
	if _tween:
		_tween.kill()
	_tween = create_tween()
	_tween.set_ease(Tween.EASE_OUT)
	_tween.set_trans(Tween.TRANS_CUBIC)
	_tween.tween_property(self, "position", to, Sfx.move_seconds(squares))
	_tween.finished.connect(func() -> void:
		if not dead:
			_play("idle"))


func swing() -> void:
	if _player == null or not _player.has_animation("attack-melee-right"):
		return
	_player.stop()
	_player.play("attack-melee-right", CROSSFADE_SECONDS * 0.25)
	_current = "attack-melee-right"


func set_hp(next_hp: int, next_max: int) -> void:
	if next_hp == hp and next_max == max_hp:
		return
	var hurt := next_hp < hp
	_drain_from = shown_hp
	_drain_t = 0.0
	_drain_delay = IMPACT_SECONDS if hurt else 0.0
	hp = next_hp
	max_hp = next_max
	if next_hp <= 0 and not dead:
		dead = true
		_dying = IMPACT_SECONDS if hurt else 0.0
		if _dying <= 0.0:
			_drop()
	elif next_hp > 0 and dead:
		dead = false
		_dying = 0.0
		shown_hp = float(next_hp)
		_drain_t = 1.0
		_current = ""
		_play("idle")
		_draw_bar()
	_refresh_bar()


func die() -> void:
	if dead and _dying <= 0.0 and _current == "die":
		return
	if hp > 0:
		set_hp(0, max_hp)
		return
	if not dead:
		dead = true
		_dying = IMPACT_SECONDS
		if _dying <= 0.0:
			_drop()


func set_bar_visible(in_combat: bool) -> void:
	_bar_wanted = in_combat
	_refresh_bar()


func face_towards(dx: float, dz: float) -> void:
	if is_zero_approx(dx) and is_zero_approx(dz):
		return
	var pivot := get_node_or_null("Pivot") as Node3D
	if pivot:
		pivot.rotation.y = atan2(dx, dz)


func _process(delta: float) -> void:
	if _dying > 0.0:
		_dying -= delta
		if _dying <= 0.0:
			_drop()
	if _drain_delay > 0.0:
		_drain_delay -= delta
	elif _drain_t < 1.0:
		_drain_t = minf(_drain_t + delta / DRAIN_SECONDS, 1.0)
		var eased := 1.0 - pow(1.0 - _drain_t, 3.0)
		shown_hp = _drain_from + (float(hp) - _drain_from) * eased
		_draw_bar()
	var bar := get_node_or_null("Bar") as Node3D
	var cam := get_viewport().get_camera_3d() if get_viewport() else null
	if bar and cam:
		bar.global_basis = Basis(cam.global_basis)


func _on_animation_finished(anim_name: StringName) -> void:
	if String(anim_name).ends_with("attack-melee-right") and not dead:
		_current = ""
		_play("idle")


func _play(clip: String) -> void:
	if _player == null or not _player.has_animation(clip):
		return
	if _current == clip:
		return
	_player.play(clip, CROSSFADE_SECONDS)
	_current = clip


func _drop(seek_end := false) -> void:
	_dying = 0.0
	_current = ""
	_play("die")
	if seek_end and _player and _player.has_animation("die"):
		var anim: Animation = _player.get_animation("die")
		_player.seek(anim.length)
	_refresh_bar()


func _mount_figure(kind: String) -> void:
	var figure := get_node_or_null("Pivot/Figure") as Node3D
	if figure == null:
		return
	var spec: Dictionary = MODELS.get(kind, {})
	_height = float(spec.get("height", 1.15))
	var bar := get_node_or_null("Bar") as Node3D
	if bar:
		bar.position.y = _height + 0.3
	if spec.is_empty() or not ResourceLoader.exists(String(spec["path"])):
		_placeholder(figure)
		return
	var packed: PackedScene = load(String(spec["path"]))
	if packed == null:
		_placeholder(figure)
		return
	var model: Node3D = packed.instantiate() as Node3D
	if model == null:
		_placeholder(figure)
		return
	figure.add_child(model)
	_fit(model, _height)
	_self_lit(model)
	if not _hostile:
		_mount_carried_torch(model)
		_mark_party_layer(self)
	_player = _bind_clips(model)


func _bind_clips(model: Node3D) -> AnimationPlayer:
	var player := AnimationPlayer.new()
	player.name = "AnimationPlayer"
	model.add_child(player)
	player.root_node = NodePath("..")
	player.add_animation_library("", _clip_library())
	if not player.animation_finished.is_connected(_on_animation_finished):
		player.animation_finished.connect(_on_animation_finished)
	return player


static func _clip_library() -> AnimationLibrary:
	if _library != null:
		return _library
	_library = AnimationLibrary.new()
	for game_name in CLIPS:
		var spec: Dictionary = CLIPS[game_name]
		var anim := _steal(String(spec["path"]), String(spec["clip"]))
		if anim == null:
			continue
		anim.loop_mode = Animation.LOOP_LINEAR if bool(spec["loop"]) else Animation.LOOP_NONE
		_library.add_animation(String(game_name), anim)
	return _library


static func _steal(from_path: String, clip: String) -> Animation:
	if not ResourceLoader.exists(from_path):
		return null
	var packed: PackedScene = load(from_path)
	if packed == null:
		return null
	var root: Node = packed.instantiate()
	var player := root.find_child("AnimationPlayer", true, false) as AnimationPlayer
	var copy: Animation = null
	if player and player.has_animation(clip):
		copy = player.get_animation(clip).duplicate(true) as Animation
	root.free()
	return copy


func _fit(model: Node3D, height: float) -> void:
	model.scale = Vector3.ONE
	model.position = Vector3.ZERO
	var box := MeshProp.aabb_of(model)
	var s := height / maxf(box.size.y, 1e-6)
	model.scale = Vector3.ONE * s
	model.position.y = -box.position.y * s


func _self_lit(root: Node) -> void:
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		for child in node.get_children():
			stack.append(child)
		if not (node is MeshInstance3D):
			continue
		var mesh := node as MeshInstance3D
		mesh.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
		mesh.gi_mode = GeometryInstance3D.GI_MODE_DISABLED
		var count := mesh.mesh.get_surface_count() if mesh.mesh else 0
		for i in count:
			var src := mesh.get_active_material(i)
			if not (src is BaseMaterial3D):
				continue
			var mat := (src as BaseMaterial3D).duplicate() as BaseMaterial3D
			mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR
			if _hostile:
				mat.emission_enabled = false
			else:
				mat.emission_enabled = true
				mat.emission = Color.WHITE
				mat.emission_energy_multiplier = SELF_LIT
				if mat.albedo_texture:
					mat.emission_texture = mat.albedo_texture
			mesh.set_surface_override_material(i, mat)


func _mount_carried_torch(model: Node3D) -> void:
	var skel := model.find_child("Skeleton3D", true, false) as Skeleton3D
	if skel == null or skel.find_bone("handslot.l") < 0:
		return
	var attach := BoneAttachment3D.new()
	attach.name = "CarriedTorch"
	attach.bone_name = "handslot.l"
	skel.add_child(attach)
	_torch_attach = attach
	sync_carried_torch(String(Table.scene.get("partyLight", "FULL")))


## Lit mesh while the pool burns; unlit at OUT. Never comes off. Spec §7a.
func sync_carried_torch(party_light: String) -> void:
	if _torch_attach == null:
		return
	var want_out := party_light == "OUT"
	if _torch_attach.get_child_count() > 0 and _torch_out == want_out:
		return
	_torch_out = want_out
	for child in _torch_attach.get_children():
		_torch_attach.remove_child(child)
		child.free()
	var path := TORCH_OUT if want_out else TORCH_LIT
	if not ResourceLoader.exists(path):
		return
	var packed: PackedScene = load(path)
	if packed == null:
		return
	var mesh_root := packed.instantiate() as Node3D
	if mesh_root == null:
		return
	mesh_root.name = "Mesh"
	# Dungeon kit is a 4-unit module; the Knight is already height-fitted. A handheld
	# stick, not a wall sconce.
	mesh_root.scale = Vector3.ONE * 0.35
	_torch_attach.add_child(mesh_root)
	_mark_party_layer(_torch_attach)


func _mark_party_layer(root: Node) -> void:
	if _hostile:
		return
	var stack: Array[Node] = [root]
	while not stack.is_empty():
		var node: Node = stack.pop_back()
		if node is VisualInstance3D:
			var vis := node as VisualInstance3D
			vis.set_layer_mask_value(1, false)
			vis.set_layer_mask_value(PARTY_RENDER_LAYER, true)
		for child in node.get_children():
			stack.append(child)


func _placeholder(figure: Node3D) -> void:
	var body := MeshInstance3D.new()
	var capsule := CapsuleMesh.new()
	capsule.radius = 0.22
	capsule.height = 0.72
	body.mesh = capsule
	body.position.y = 0.67
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.541176, 0.541176, 0.541176)
	body.material_override = mat
	figure.add_child(body)


func _paint_base(kind: String) -> void:
	var base := get_node_or_null("Pivot/Base") as MeshInstance3D
	if base == null:
		return
	var mat := StandardMaterial3D.new()
	mat.albedo_color = BASE_COLOR.get(kind, FALLBACK)
	mat.roughness = 0.7
	mat.metallic = 0.2
	mat.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR
	base.material_override = mat
	base.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON


func _ensure_bar_materials() -> void:
	var backing := get_node_or_null("Bar/Backing") as MeshInstance3D
	if backing:
		var back := StandardMaterial3D.new()
		back.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		back.albedo_color = Color(0.078431, 0.07451, 0.101961, 0.85)
		back.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		back.no_depth_test = true
		back.render_priority = 10
		backing.material_override = back
		backing.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	if _fill:
		_fill_mat = StandardMaterial3D.new()
		_fill_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		_fill_mat.no_depth_test = true
		_fill_mat.render_priority = 10
		_fill.material_override = _fill_mat
		_fill.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


func _refresh_bar() -> void:
	var bar := get_node_or_null("Bar") as Node3D
	if bar == null:
		return
	var down := dead and _dying <= 0.0
	bar.visible = not down and max_hp > 0 and (_bar_wanted or hp < max_hp)


func _draw_bar() -> void:
	if _fill == null or _fill_mat == null:
		return
	var fraction := clampf(shown_hp / float(maxi(max_hp, 1)), 0.0, 1.0)
	_fill.scale.x = fraction
	_fill.position.x = -(BAR_WIDTH / 2.0) * (1.0 - fraction)
	_fill_mat.albedo_color = FOE if _hostile else ALLY


func _host_world() -> World:
	var node: Node = get_parent()
	while node:
		if node is World:
			return node as World
		node = node.get_parent()
	return null
