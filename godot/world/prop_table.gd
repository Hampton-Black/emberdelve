@tool
class_name PropTable
extends Resource

## PropType → scene path. Promoting a prop is one table entry, and demoting it — because a
## model reads badly at 480x270, say — is deleting one. Neither touches the scene schema.
##
## This is the `MESH_PROPS` seam from `client/src/scene/props.ts`. Appearance, not a
## second type, picks the kit mesh; this table is the mechanical type itself.
##
## DOOR is absent on purpose, and it is the demotion this comment describes. A door is not an
## object standing in a square, it is the wall segment the square backs onto — KayKit's
## `wall_doorway` arrives with the door already hung in it, and `room.gd` places one wherever
## the server says there is an exit. A DOOR prop drawn on top of that was a second door in the
## same hole. The prop still exists server-side, because the DM and the Exit both address it by
## id; it simply has no mesh of its own.

@export var scenes: Dictionary = {}


func scene_for(type: String) -> PackedScene:
	var path := String(scenes.get(type, ""))
	if path.is_empty() or not ResourceLoader.exists(path):
		return null
	return load(path) as PackedScene
