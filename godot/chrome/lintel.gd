extends Label

## The room's name on the lintel. Reads Table; holds no facts of its own (invariant #3).
## Captions are a local table keyed by roomId — the wire does not ship a display name.

const CAPTION := {
	"crypt": "THE CRYPT",
	"gallery": "THE LONG GALLERY",
}


func _ready() -> void:
	Table.scene_changed.connect(_refresh)
	_refresh()


func _refresh() -> void:
	var id := String(Table.scene.get("roomId", ""))
	text = String(CAPTION.get(id, ""))
