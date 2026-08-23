extends GutTest

## Tokens spawn from Table, slide rather than physics, and keep a body. Game facts stay in Table.

const FIGHTER := {
	"id": "fighter", "kind": "fighter", "name": "Roderick", "x": 2, "y": 2,
	"hp": 12, "maxHp": 12, "isPlayerControlled": true,
}
const GOBLIN := {
	"id": "goblin", "kind": "goblin", "name": "Vessk", "x": 8, "y": 6,
	"hp": 7, "maxHp": 7, "isPlayerControlled": false,
}

const CRYPT := {
	"roomId": "crypt", "width": 12, "height": 12,
	"floorType": "STONE", "wallType": "CARVED", "lighting": "TORCHLIT",
	"mode": "EXPLORATION", "combat": null,
	"props": [],
	"entities": [FIGHTER],
}

const CLIPS: Array[String] = ["idle", "walk", "attack-melee-right", "die"]
const GREEN := Color(0x7f / 255.0, 0xae / 255.0, 0x56 / 255.0)
const RED := Color(0xb8 / 255.0, 0x3a / 255.0, 0x30 / 255.0)


func before_each() -> void:
	Table.reset()
	Table.set_scene(CRYPT.duplicate(true))


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var node: Node3D = packed.instantiate()
	add_child_autofree(node)
	return node


func _tokens(world: Node) -> Node3D:
	return world.get_node_or_null("Tokens") as Node3D


func _token(world: Node, id: String) -> Node3D:
	var holder := _tokens(world)
	if holder == null:
		return null
	return holder.get_node_or_null(id) as Node3D


func _fill(token: Node) -> MeshInstance3D:
	if token == null:
		return null
	return token.get_node_or_null("Bar/Fill") as MeshInstance3D


func _player(token: Node) -> AnimationPlayer:
	if token == null:
		return null
	return token.find_child("AnimationPlayer", true, false) as AnimationPlayer


func test_kaykit_character_kits_are_cherry_picked_per_atlas() -> void:
	assert_true(ResourceLoader.exists("res://world/kits/characters/kaykit_adventurers/Knight.glb"),
		"Knight.glb")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_adventurers/knight_texture.png"), "knight atlas")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_skeletons/Skeleton_Warrior.glb"), "Skeleton_Warrior.glb")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_skeletons/skeleton_texture.png"), "skeleton atlas")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_animations/Rig_Medium_General.glb"), "General")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_animations/Rig_Medium_MovementBasic.glb"),
		"MovementBasic")
	assert_true(ResourceLoader.exists(
		"res://world/kits/characters/kaykit_animations/Rig_Medium_CombatMelee.glb"), "CombatMelee")
	assert_false(DirAccess.dir_exists_absolute("res://world/kits/characters/graveyard"),
		"do not copy Kenney graveyard")
	assert_false(DirAccess.dir_exists_absolute("res://world/kits/characters/mini"),
		"do not copy Kenney mini")


func test_the_world_spawns_every_entity_from_the_scene() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), GOBLIN.duplicate()]
	Table.set_scene(fighting)
	var world := _world_tree()
	var holder := _tokens(world)
	assert_not_null(holder, "World/Tokens")
	if holder == null:
		return
	assert_eq(holder.get_child_count(), 2, "fighter and goblin from scene.entities")
	var fighter := _token(world, "fighter")
	var goblin := _token(world, "goblin")
	assert_not_null(fighter, "addressed by entity.id, not a singleton")
	assert_not_null(goblin, "goblin")
	if fighter == null or goblin == null:
		return
	assert_eq(fighter.position, world.grid_to_world(2, 2))
	assert_eq(goblin.position, world.grid_to_world(8, 6))


func test_entity_added_instances_a_token() -> void:
	var world := _world_tree()
	assert_null(_token(world, "goblin"), "goblin is not in the opening scene")
	Table.entity_added.emit(GOBLIN.duplicate())
	var goblin := _token(world, "goblin")
	assert_not_null(goblin, "entity_added spawns the goblin")
	if goblin == null:
		return
	assert_eq(goblin.position, world.grid_to_world(8, 6))


func test_a_move_slides_over_sfx_move_seconds() -> void:
	var world := _world_tree()
	var fighter := _token(world, "fighter")
	assert_not_null(fighter, "fighter")
	if fighter == null:
		return
	assert_true(fighter.has_method("slide_to"), "slide_to(square)")
	if not fighter.has_method("slide_to"):
		return
	var from: Vector3 = fighter.position
	var dest: Vector3 = world.grid_to_world(4, 2)
	assert_true(FileAccess.get_file_as_string("res://world/tokens/token.gd").contains("Sfx.move_seconds"),
		"duration is Sfx.move_seconds, not a second copy of the formula")
	assert_false(FileAccess.get_file_as_string("res://world/tokens/token.gd").contains("0.18 +"),
		"do not duplicate the 0.18 + squares * 0.07 formula")
	Table.entity_moved.emit("fighter", Vector2i(2, 2), Vector2i(4, 2))
	await wait_process_frames(2)
	assert_ne(fighter.position, dest, "a slide, not a teleport")
	assert_ne(fighter.position, from, "the token has left the origin square")
	var over: float = Sfx.move_seconds(2)
	await wait_seconds(over + 0.05)
	assert_eq(fighter.position, dest)
	assert_eq(fighter.get_class(), "Node3D", "no CharacterBody3D — the server already approved the move")


func test_a_dead_body_stays_on_the_board() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), GOBLIN.duplicate()]
	Table.set_scene(fighting)
	var world := _world_tree()
	var goblin := _token(world, "goblin")
	assert_not_null(goblin, "goblin")
	if goblin == null:
		return
	var kept: int = goblin.get_instance_id()
	Table.entity_died.emit("goblin")
	var after := _token(world, "goblin")
	assert_not_null(after, "death leaves a body")
	if after == null:
		return
	assert_eq(after.get_instance_id(), kept, "the same node stays; hide-or-free would mint a gap")
	assert_true(after.visible, "the body is not hidden")


func test_health_bar_colour_is_allegiance_and_length_is_hp() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), GOBLIN.duplicate()]
	Table.set_scene(fighting)
	var world := _world_tree()
	var fighter := _token(world, "fighter")
	var goblin := _token(world, "goblin")
	assert_not_null(fighter, "fighter")
	assert_not_null(goblin, "goblin")
	if fighter == null or goblin == null:
		return
	var ally := _fill(fighter)
	var foe := _fill(goblin)
	assert_not_null(ally, "fighter Bar/Fill")
	assert_not_null(foe, "goblin Bar/Fill")
	if ally == null or foe == null:
		return
	var ally_mat := ally.get_active_material(0) as BaseMaterial3D
	var foe_mat := foe.get_active_material(0) as BaseMaterial3D
	assert_not_null(ally_mat, "fighter fill material")
	assert_not_null(foe_mat, "goblin fill material")
	if ally_mat == null or foe_mat == null:
		return
	assert_almost_eq(ally_mat.albedo_color.r, GREEN.r, 0.02, "player-controlled is green")
	assert_almost_eq(ally_mat.albedo_color.g, GREEN.g, 0.02)
	assert_almost_eq(foe_mat.albedo_color.r, RED.r, 0.02, "hostile is red")
	assert_almost_eq(foe_mat.albedo_color.g, RED.g, 0.02)
	assert_true(fighter.has_method("set_hp"), "set_hp")
	if not fighter.has_method("set_hp"):
		return
	# Drain waits IMPACT_SECONDS then takes DRAIN_SECONDS — length is health, not a snap.
	fighter.call("set_hp", 6, 12)
	assert_almost_eq(ally.scale.x, 1.0, 0.001, "the bar has not started emptying before the blow")
	await wait_seconds(0.22 + 0.34 + 0.05)
	assert_almost_eq(ally.scale.x, 0.5, 0.02, "length is hp/max")
	assert_almost_eq(foe.scale.x, 1.0, 0.001)


func test_the_four_clips_exist_under_the_names_the_token_plays() -> void:
	var world := _world_tree()
	var fighter := _token(world, "fighter")
	assert_not_null(fighter, "fighter")
	if fighter == null:
		return
	var player := _player(fighter)
	assert_not_null(player, "AnimationPlayer on the token")
	if player == null:
		return
	var names := player.get_animation_list()
	for clip in CLIPS:
		var found := false
		for listed in names:
			if String(listed) == clip or String(listed).ends_with("/" + clip):
				found = true
				break
		assert_true(found, "clip '%s' is on the player as %s" % [clip, names])
	# Spawned idle, not mid-swing. A goblin that chops the moment it appears is a bug.
	var current := String(player.current_animation)
	assert_true(current.ends_with("idle") or current == "idle",
		"spawn is idle, not a swing: %s" % current)


func test_a_strike_swings_and_impact_delays_the_drop() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), GOBLIN.duplicate()]
	Table.set_scene(fighting)
	var world := _world_tree()
	var fighter := _token(world, "fighter")
	var goblin := _token(world, "goblin")
	assert_not_null(fighter, "fighter")
	assert_not_null(goblin, "goblin")
	if fighter == null or goblin == null:
		return
	assert_true(fighter.has_method("swing"), "swing()")
	assert_true(goblin.has_method("die"), "die()")
	if not fighter.has_method("swing") or not goblin.has_method("die"):
		return
	Table.strike.emit("fighter", "goblin", true, Time.get_ticks_msec())
	await wait_process_frames(2)
	var actor_player := _player(fighter)
	assert_not_null(actor_player, "fighter AnimationPlayer")
	if actor_player:
		var current := String(actor_player.current_animation)
		assert_true(current.ends_with("attack-melee-right") or current.contains("attack"),
			"strike plays the chop, not spawn: %s" % current)
	goblin.call("set_hp", 0, 7)
	var dying_player := _player(goblin)
	assert_not_null(dying_player, "goblin AnimationPlayer")
	if dying_player == null:
		return
	var before := String(dying_player.current_animation)
	assert_false(before.ends_with("die") or before.contains("/die"),
		"the drop waits for IMPACT_SECONDS; got %s" % before)
	await wait_seconds(0.22 + 0.05)
	var after := String(dying_player.current_animation)
	assert_true(after.ends_with("die") or after.contains("/die"),
		"the body drops after the blow: %s" % after)


func test_idle_and_chop_drive_the_skeleton_not_just_the_player() -> void:
	# Clips that sit on the AnimationPlayer but never bind produce a T-pose that
	# the name-list test cannot see. Advance the mixer and require a bone to move.
	var world := _world_tree()
	var fighter := _token(world, "fighter")
	assert_not_null(fighter, "fighter")
	if fighter == null:
		return
	var player := _player(fighter)
	var skel := fighter.find_child("Skeleton3D", true, false) as Skeleton3D
	assert_not_null(player, "AnimationPlayer")
	assert_not_null(skel, "Skeleton3D")
	if player == null or skel == null:
		return
	var hips := skel.find_bone("hips")
	assert_gt(hips, -1, "hips bone")
	player.play("idle")
	var idle_before := skel.get_bone_pose(hips)
	player.advance(0.2)
	assert_false(skel.get_bone_pose(hips).is_equal_approx(idle_before),
		"Idle_A must pose the Knight rig")
	var arm := skel.find_bone("upperarm.r")
	assert_gt(arm, -1, "upperarm.r")
	player.play("attack-melee-right")
	var chop_before := skel.get_bone_pose(arm)
	player.advance(0.12)
	assert_false(skel.get_bone_pose(arm).is_equal_approx(chop_before),
		"Melee_1H_Attack_Chop must pose the Knight rig")
	# Same four-clip library on the goblin — the warrior is the same Rig_Medium.
	Table.entity_added.emit(GOBLIN.duplicate())
	var goblin := _token(world, "goblin")
	assert_not_null(goblin, "goblin")
	if goblin == null:
		return
	var gplayer := _player(goblin)
	var gskel := goblin.find_child("Skeleton3D", true, false) as Skeleton3D
	assert_not_null(gplayer, "goblin AnimationPlayer")
	assert_not_null(gskel, "goblin Skeleton3D")
	if gplayer == null or gskel == null:
		return
	var ghips := gskel.find_bone("hips")
	gplayer.play("idle")
	var ghost_before := gskel.get_bone_pose(ghips)
	gplayer.advance(0.2)
	assert_false(gskel.get_bone_pose(ghips).is_equal_approx(ghost_before),
		"Idle_A must pose the Skeleton_Warrior rig")


func test_a_creature_revives_when_hit_points_return() -> void:
	var fighting := CRYPT.duplicate(true)
	var corpse := GOBLIN.duplicate()
	corpse["hp"] = 0
	fighting["entities"] = [FIGHTER.duplicate(), corpse]
	Table.set_scene(fighting)
	var world := _world_tree()
	var goblin := _token(world, "goblin")
	assert_not_null(goblin, "born-dead goblin still has a token")
	if goblin == null:
		return
	assert_true(goblin.has_method("set_hp"), "set_hp")
	if not goblin.has_method("set_hp"):
		return
	goblin.call("set_hp", 7, 7)
	await wait_process_frames(2)
	var player := _player(goblin)
	assert_not_null(player, "AnimationPlayer")
	if player == null:
		return
	var current := String(player.current_animation)
	assert_true(current.ends_with("idle") or current == "idle",
		"hp > 0 stands the body back up: %s" % current)
