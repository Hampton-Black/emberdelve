@tool
class_name PropPlace
extends Object

## One instancing path for a prop node. World and the editor preview both call `into`.
## Hidden, duplicates, and "is this the current room" stay with the caller — the preview
## draws secrets on purpose; the game does not. DOOR has no mesh: `scene_for` returns
## null and this returns null, which is correct rather than a missing prop.

const PROP_TABLE := preload("res://world/prop_table.tres")


static func into(holder: Node, prop: Dictionary, room_id: String, at: Vector3) -> Node3D:
	var packed: PackedScene = PROP_TABLE.scene_for(String(prop.get("type", "")))
	if packed == null:
		return null
	var node := packed.instantiate() as Node3D
	if node == null:
		return null
	var id := String(prop.get("id", ""))
	if not id.is_empty():
		node.name = id
	node.position = at
	node.rotation.y = deg_to_rad(float(prop.get("rotation", 0.0)))
	holder.add_child(node)
	if node.has_method("configure"):
		node.configure(prop, room_id)
	return node
