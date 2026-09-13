@tool
extends Object

## Closed kit looks. Same file the server loads from the classpath. The server
## ships an appearance id; this table is how the client finds the mesh. The
## client does not pick one (invariant #1).

const CATALOG_REL := "../server/src/main/resources/content/appearances.json"

static var _cache: Dictionary = {}
static var _loaded := false


static func catalog_path() -> String:
	return ProjectSettings.globalize_path("res://").path_join(CATALOG_REL).simplify_path()


static func all() -> Array:
	_ensure()
	var out: Array = []
	for id in _cache:
		out.append(_with_id(id))
	return out


static func spec_for(id: String) -> Dictionary:
	_ensure()
	if id.is_empty() or not _cache.has(id):
		return {}
	return _with_id(id)


static func of_type(type: String) -> PackedStringArray:
	var ids: PackedStringArray = PackedStringArray()
	for spec in all():
		if String(spec.get("type", "")) == type:
			ids.append(String(spec["id"]))
	return ids


static func default_for(type: String) -> Dictionary:
	var ids := of_type(type)
	if ids.is_empty():
		return {}
	return spec_for(ids[0])


static func res_path(spec: Dictionary) -> String:
	var kit := String(spec.get("kit", ""))
	var file := String(spec.get("file", ""))
	if kit.is_empty() or file.is_empty():
		return ""
	if kit.begins_with("kaykit_"):
		return "res://world/kits/%s/%s.gltf" % [kit, file]
	return "res://world/kits/%s/%s.glb" % [kit, file]


static func mesh_path(spec: Dictionary) -> String:
	var kit := String(spec.get("kit", ""))
	var file := String(spec.get("file", ""))
	if kit.is_empty() or file.is_empty():
		return ""
	var prefix := "dungeon" if kit == "dungeon_props" else kit
	return "%s/%s" % [prefix, file]


static func _with_id(id: String) -> Dictionary:
	var spec: Dictionary = (_cache[id] as Dictionary).duplicate()
	spec["id"] = id
	return spec


static func _ensure() -> void:
	if _loaded:
		return
	_loaded = true
	var path := catalog_path()
	if not FileAccess.file_exists(path):
		push_error("appearances: no catalog at %s" % path)
		return
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(path))
	if typeof(parsed) != TYPE_DICTIONARY:
		push_error("appearances: %s is not a JSON object" % path)
		return
	_cache = parsed
