@tool
extends Node3D

## Lid skull from Halloween Bits. Fitted at runtime from its bounding box the way
## `instanceProp` does — a literal scale is wrong the moment the kit changes.
##
## @tool so the preview draws it the way the game does; see `_editing_myself`.

const MeshPropScript := preload("res://world/props/mesh_prop.gd")
const SKULL := "kaykit_halloween/skull"
const SKULL_HEIGHT := 0.15
const SKULL_FOOTPRINT := 0.22
const BONE := Color(0.768627, 0.733333, 0.643137)


## True while this node is the scene open in the editor, rather than an instance of it built by
## editor tooling. `_ready` below writes to transforms that belong to the .tscn, so running it on
## the scene being edited would bake computed positions into the authored file on the next save.
## Building an instance somewhere else — which is all the preview does — is safe.
func _editing_myself() -> bool:
	if not Engine.is_editor_hint():
		return false
	var tree := get_tree()
	return tree != null and tree.edited_scene_root == self


func _ready() -> void:
	if _editing_myself():
		return
	var mount := get_node_or_null("Lid/Skull") as Node3D
	if mount == null:
		return
	var skull: Node3D = MeshPropScript.make(SKULL, SKULL_HEIGHT, SKULL_FOOTPRINT)
	if skull == null:
		return
	MeshPropScript.paint(skull, BONE)
	mount.add_child(skull)
