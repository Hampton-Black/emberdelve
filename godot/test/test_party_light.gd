extends GutTest
const SceneFixtures := preload("res://test/scene_fixtures.gd")

## emberdelve-4h9.12: the party's OmniLight3D pool, carried torch mesh, DARK silhouette,
## and party-only token glow. Spec §7a §7b.

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
	"partyLight": "FULL",
	"props": [],
	"entities": [FIGHTER],
}


func before_each() -> void:
	Table.reset()
	Table.set_scene(SceneFixtures.scene(CRYPT))


func _world_tree() -> Node3D:
	var packed: PackedScene = load("res://world/world.tscn")
	assert_not_null(packed, "world.tscn")
	if packed == null:
		return Node3D.new()
	var node: Node3D = packed.instantiate()
	add_child_autofree(node)
	return node


func _pool(world: Node) -> OmniLight3D:
	return world.get_node_or_null("PartyLight") as OmniLight3D


func test_the_pool_is_a_world_sibling_of_tokens_not_under_the_room() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	var light := _pool(world)
	assert_not_null(light, "World/PartyLight")
	if light == null:
		return
	assert_eq(light.get_parent(), world, "owned by World, not by a token or a Room")
	assert_null(world.get_node_or_null("Room/PartyLight"),
		"must not live under Room — that would spend MAX_TORCH_LIGHTS")
	assert_eq(light.omni_attenuation, 2.0)
	assert_true(light.shadow_enabled, "the torch casts shadows")
	assert_eq(light.shadow_caster_mask & (1 << 1), 0,
		"shadow_caster_mask excludes the party render layer")


func test_full_pool_range_and_energy() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	var light := _pool(world)
	assert_not_null(light, "World/PartyLight")
	if light == null:
		return
	assert_true(light.visible)
	assert_almost_eq(light.omni_range, 6.0, 0.001)
	assert_almost_eq(light.light_energy, 2.6, 0.09, "FULL energy 2.6, flicker ±3%")
	assert_almost_eq(light.light_color.r, 1.00, 0.001)
	assert_almost_eq(light.light_color.g, 0.72, 0.001)
	assert_almost_eq(light.light_color.b, 0.40, 0.001)


func test_party_light_changed_shrinks_the_pool() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	Table.apply_diffs([{"kind": "PartyLightChanged", "partyLight": "LOW"}])
	await wait_process_frames(2)
	var light := _pool(world)
	assert_not_null(light, "World/PartyLight")
	if light == null:
		return
	assert_almost_eq(light.omni_range, 4.6, 0.001)
	assert_almost_eq(light.light_energy, 2.3, 0.15, "LOW energy 2.3, flicker ±6%")

	Table.apply_diffs([{"kind": "PartyLightChanged", "partyLight": "OUT"}])
	await wait_process_frames(2)
	assert_false(light.visible, "OUT turns the pool off")


func test_the_pool_hangs_above_living_player_tokens() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	var fighter := world.get_node_or_null("Tokens/fighter") as Node3D
	var light := _pool(world)
	assert_not_null(fighter, "fighter")
	assert_not_null(light, "World/PartyLight")
	if fighter == null or light == null:
		return
	assert_almost_eq(light.global_position.x, fighter.global_position.x, 0.05)
	assert_almost_eq(light.global_position.z, fighter.global_position.z, 0.05)
	assert_almost_eq(light.global_position.y, fighter.global_position.y + 0.95, 0.05)


func test_hostile_figure_has_no_emission_and_the_party_does() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), GOBLIN.duplicate()]
	Table.set_scene(SceneFixtures.scene(fighting))
	var world := _world_tree()
	await wait_process_frames(2)
	var fighter := world.get_node_or_null("Tokens/fighter")
	var goblin := world.get_node_or_null("Tokens/goblin")
	assert_not_null(fighter, "fighter")
	assert_not_null(goblin, "goblin")
	if fighter == null or goblin == null:
		return
	assert_gt(_emission_count(fighter), 0, "party tokens keep the faint albedo glow")
	assert_eq(_emission_count(goblin), 0, "hostiles show where the pool reaches them")


func test_brute_figure_has_no_self_lit_either() -> void:
	var fighting := CRYPT.duplicate(true)
	fighting["entities"] = [FIGHTER.duplicate(), {
		"id": "brute", "kind": "brute", "name": "Brakk", "x": 5, "y": 5,
		"hp": 16, "maxHp": 16, "isPlayerControlled": false,
	}]
	Table.set_scene(SceneFixtures.scene(fighting))
	var world := _world_tree()
	await wait_process_frames(2)
	var brute := world.get_node_or_null("Tokens/brute")
	assert_not_null(brute, "brute")
	if brute == null:
		return
	assert_eq(_emission_count(brute), 0, "hostiles still carry no glow of their own")


func test_env_dark_keeps_torchlit_colour_at_its_own_energy() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	var dark_node := world.get_node_or_null("Room/Lighting/DARK/WorldEnvironment") as WorldEnvironment
	assert_not_null(dark_node, "EnvDark")
	if dark_node == null:
		return
	var env: Environment = null
	if dark_node.has_meta("authored"):
		env = dark_node.get_meta("authored") as Environment
	else:
		env = dark_node.environment
	assert_not_null(env, "DARK environment")
	if env == null:
		return
	assert_almost_eq(env.ambient_light_color.r, 0.235294, 0.001)
	assert_almost_eq(env.ambient_light_color.g, 0.227451, 0.001)
	assert_almost_eq(env.ambient_light_color.b, 0.321569, 0.001)
	assert_almost_eq(env.ambient_light_energy, 0.35, 0.001)


func test_the_fighter_holds_a_torch_that_is_not_a_pick_target() -> void:
	var world := _world_tree()
	await wait_process_frames(2)
	var fighter := world.get_node_or_null("Tokens/fighter")
	assert_not_null(fighter, "fighter")
	if fighter == null:
		return
	var torch := fighter.find_child("CarriedTorch", true, false)
	assert_not_null(torch, "BoneAttachment3D CarriedTorch on handslot.l")
	if torch == null:
		return
	assert_true(torch is BoneAttachment3D)
	assert_eq((torch as BoneAttachment3D).bone_name, "handslot.l")
	assert_gt(torch.get_child_count(), 0, "a mesh is in the hand")
	assert_false(torch.has_meta("exit_id"), "the carried torch is never a pick target")
	assert_eq(world.get_node_or_null("Room/Walls").find_child("CarriedTorch", true, false), null,
		"not parented under Walls, where _target_under looks")

	Table.apply_diffs([{"kind": "PartyLightChanged", "partyLight": "OUT"}])
	await wait_process_frames(2)
	assert_not_null(fighter.find_child("CarriedTorch", true, false),
		"OUT swaps the mesh; the torch never comes off")


func _emission_count(token: Node) -> int:
	var figure := token.get_node_or_null("Pivot/Figure")
	if figure == null:
		return 0
	var n := 0
	for mesh in figure.find_children("*", "MeshInstance3D", true, false):
		var inst := mesh as MeshInstance3D
		var count: int = inst.mesh.get_surface_count() if inst.mesh else 0
		for i in count:
			var mat: Material = inst.get_active_material(i)
			if mat is BaseMaterial3D and (mat as BaseMaterial3D).emission_enabled \
					and (mat as BaseMaterial3D).emission_energy_multiplier > 0.0:
				n += 1
	return n
