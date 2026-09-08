@tool
extends Node3D

## A recess cut into the wall. Local +Z is into the wall; the face it mounts on is
## WALL_FACE — half a square in, less half the wall's depth. That figure belongs to
## the wall kit, which is why it is computed here rather than baked.
##
## @tool so the preview draws it the way the game does; see `_editing_myself`.

const WALL_FACE := 0.5 - Room.WALL_DEPTH / 2.0
const ALCOVE_HEIGHT := 0.73


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
	var inner := get_node_or_null("Inner") as Node3D
	if inner == null:
		return
	inner.scale = Vector3.ONE * ALCOVE_HEIGHT
	var face := WALL_FACE / ALCOVE_HEIGHT
	_at(inner.get_node_or_null("Back"), 0.0, 0.56, face - 0.02)
	_at(inner.get_node_or_null("JambLeft"), -0.35, 0.56, face - 0.085)
	_at(inner.get_node_or_null("JambRight"), 0.35, 0.56, face - 0.085)
	_at(inner.get_node_or_null("Lintel"), 0.0, 0.93, face - 0.09)
	_at(inner.get_node_or_null("Sill"), 0.0, 0.2, face - 0.1)
	_at(inner.get_node_or_null("Lamp"), 0.0, 0.31, face - 0.11)
	_at(inner.get_node_or_null("Glow"), 0.0, 0.45, face - 0.14)


func _at(node: Node3D, x: float, y: float, z: float) -> void:
	if node == null:
		return
	node.position = Vector3(x, y, z)
