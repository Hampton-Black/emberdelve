extends Node3D

## A recess cut into the wall. Local +Z is into the wall; the face it mounts on is
## WALL_FACE — half a square in, less half the wall's depth. That figure belongs to
## the wall kit, which is why it is computed here rather than baked.

const WALL_DEPTH := 0.14
const WALL_FACE := 0.5 - WALL_DEPTH / 2.0
const ALCOVE_HEIGHT := 0.73


func _ready() -> void:
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
