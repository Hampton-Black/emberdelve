extends GutTest

## emberdelve-4h9.7: the server ships appearance; the client maps it to a kit mesh.
## A missing appearance must fail here, not skip.

const APPEARANCES := "res://world/appearances.gd"
const MESH_PROP := "res://world/props/mesh_prop.gd"
const CATALOG_REL := "../server/src/main/resources/content/appearances.json"


func _script(path: String):
	assert_true(ResourceLoader.exists(path), path)
	if not ResourceLoader.exists(path):
		return null
	return load(path)


func _has(script, method: String) -> bool:
	if script == null:
		return false
	for m in script.get_script_method_list():
		if String(m.name) == method:
			return true
	assert_true(false, "%s is missing %s" % [script.resource_path, method])
	return false


func test_catalog_is_the_same_file_the_server_ships() -> void:
	var Appear = _script(APPEARANCES)
	if not _has(Appear, "catalog_path"):
		return
	var expected := ProjectSettings.globalize_path("res://") \
		.path_join(CATALOG_REL).simplify_path()
	assert_eq(String(Appear.catalog_path()).trim_suffix("/"), expected.trim_suffix("/"))
	assert_true(FileAccess.file_exists(Appear.catalog_path()),
		"appearances.json must be on disk")


func test_every_catalog_appearance_has_a_mesh_on_disk() -> void:
	var Appear = _script(APPEARANCES)
	if not _has(Appear, "all") or not _has(Appear, "res_path"):
		return
	var missing: Array[String] = []
	for spec in Appear.all():
		if String(spec.get("kit", "")).is_empty():
			continue
		var path := String(Appear.res_path(spec))
		if path.is_empty() or not ResourceLoader.exists(path):
			missing.append("%s → %s" % [spec.get("id", "?"), path])
	assert_eq(missing, [] as Array[String],
		"a missing appearance must fail this test, not skip: " + ", ".join(missing))


func test_unknown_appearance_is_empty_not_a_silent_fallback() -> void:
	var Appear = _script(APPEARANCES)
	if not _has(Appear, "spec_for"):
		return
	var spec: Dictionary = Appear.spec_for("no_such_mesh")
	assert_true(spec.is_empty(), "unknown appearance must not invent a mesh")


func test_chest_and_barrel_are_container_looks_with_real_meshes() -> void:
	var Appear = _script(APPEARANCES)
	if not _has(Appear, "spec_for") or not _has(Appear, "res_path"):
		return
	var chest: Dictionary = Appear.spec_for("chest")
	var barrel: Dictionary = Appear.spec_for("barrel")
	assert_eq(String(chest.get("type", "")), "CONTAINER")
	assert_eq(String(barrel.get("type", "")), "CONTAINER")
	assert_true(ResourceLoader.exists(String(Appear.res_path(chest))), Appear.res_path(chest))
	assert_true(ResourceLoader.exists(String(Appear.res_path(barrel))), Appear.res_path(barrel))


func test_configure_uses_the_shipped_appearance_not_a_hash() -> void:
	var MeshPropScript = _script(MESH_PROP)
	if MeshPropScript == null:
		return
	var node: Node3D = MeshPropScript.new()
	add_child_autofree(node)
	assert_true(node.has_method("configure"), "MeshProp.configure")
	if not node.has_method("configure"):
		return
	node.configure({"id": "cask", "type": "CONTAINER", "appearance": "barrel"}, "crypt")
	assert_eq(String(node.get_meta("mesh_path", "")), "dungeon/Barrel",
		"the server's appearance picks the mesh; the client does not hash one")
	assert_gt(node.get_child_count(), 0, "the barrel mesh was instanced")


func test_unknown_appearance_does_not_instance_a_placeholder() -> void:
	var MeshPropScript = _script(MESH_PROP)
	if MeshPropScript == null:
		return
	var node: Node3D = MeshPropScript.new()
	add_child_autofree(node)
	node.configure({"id": "ghost", "type": "CONTAINER", "appearance": "no_such_mesh"}, "crypt")
	assert_eq(node.get_child_count(), 0,
		"a missing appearance must not draw a silent placeholder")
	assert_eq(String(node.get_meta("mesh_path", "")), "")
