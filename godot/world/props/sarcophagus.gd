extends Node3D

## Lid skull from the dungeon kit. Fitted at runtime from its bounding box the way
## `instanceProp` does — a literal scale is wrong the moment the kit changes.

const MeshPropScript := preload("res://world/props/mesh_prop.gd")
const SKULL := "dungeon/Skull"
const SKULL_HEIGHT := 0.15
const SKULL_FOOTPRINT := 0.22
const BONE := Color(0.768627, 0.733333, 0.643137)


func _ready() -> void:
	var mount := get_node_or_null("Lid/Skull") as Node3D
	if mount == null:
		return
	var skull: Node3D = MeshPropScript.make(SKULL, SKULL_HEIGHT, SKULL_FOOTPRINT)
	if skull == null:
		return
	MeshPropScript.paint(skull, BONE)
	mount.add_child(skull)
