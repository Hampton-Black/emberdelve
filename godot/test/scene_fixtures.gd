## Wraps a fixture's flat, pre-xgg.3 shape — every room fact (width, height, floorType,
## wallType, lighting, props, exits) at the top level, beside roomId/mode/entities/combat/
## blocked — into the wire shape `GameEngine.scene()` actually sends: those room facts nested
## one level down, in the `rooms` list `Table.room()` reads.
##
## Fixtures across the client suite keep the flat shape because it reads and mutates more
## plainly (`crypt["floorType"] = "TILED"`); this is the one seam that turns it into what
## `Table.set_scene()` receives, so wire-shape churn stays here rather than in every test.

const ROOM_FIELDS := ["width", "height", "floorType", "wallType", "lighting", "props", "exits"]
const ROOM_DEFAULTS := {"props": [], "exits": []}
const ROOM_META_FIELDS := ["originX", "originZ", "visited"]


static func scene(flat: Dictionary) -> Dictionary:
	var out := flat.duplicate(true)
	var room := {"roomId": String(out.get("roomId", ""))}
	room["originX"] = float(out.get("originX", 0.0))
	room["originZ"] = float(out.get("originZ", 0.0))
	room["visited"] = bool(out.get("visited", true))
	for field in ROOM_FIELDS:
		if out.has(field):
			room[field] = out[field]
		elif ROOM_DEFAULTS.has(field):
			room[field] = ROOM_DEFAULTS[field]
	for field in ROOM_FIELDS:
		out.erase(field)
	for field in ROOM_META_FIELDS:
		out.erase(field)
	out["rooms"] = [room]
	return out
