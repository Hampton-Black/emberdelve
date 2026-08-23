class_name PropTable
extends Resource

## PropType → scene path. Promoting a prop is one table entry, and demoting it — because a
## model reads badly at 480x270, say — is deleting one. Neither touches the scene schema.
##
## This is the `MESH_PROPS` seam from `client/src/scene/props.ts`. Variants inside a type
## (which column, which door arch) stay on the mesh-prop scene; this table is the type itself.

@export var scenes: Dictionary = {}


func scene_for(type: String) -> PackedScene:
	var path := String(scenes.get(type, ""))
	if path.is_empty() or not ResourceLoader.exists(path):
		return null
	return load(path) as PackedScene
