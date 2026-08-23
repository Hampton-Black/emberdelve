extends Node3D

## Three editor-authored groups, one per LightingPreset. Table picks which is visible.
## Ambient and background live here; wall-torch OmniLights are generated in `room.gd`
## because they follow the room's size. No DirectionalLight3D — a sun undoes the crypt.

func _ready() -> void:
	Table.scene_changed.connect(apply)
	apply()


func apply() -> void:
	var preset := String(Table.scene.get("lighting", "TORCHLIT"))
	if preset.is_empty():
		preset = "TORCHLIT"
	for child in get_children():
		var on := child.name == preset
		child.visible = on
		_set_environment_active(child, on)


func _set_environment_active(group: Node, on: bool) -> void:
	var env_node := group.get_node_or_null("WorldEnvironment") as WorldEnvironment
	if env_node == null:
		return
	if not env_node.has_meta("authored"):
		env_node.set_meta("authored", env_node.environment)
	env_node.environment = env_node.get_meta("authored") if on else null
