@tool
class_name Grid
extends Object

## Square centre in world space, and its inverse. Extracted so World, Room, and the
## editor preview share one formula — a self-centred room is origin ZERO; a neighbour
## is the origin the server registered. world.gd must not be @tool, so the preview
## cannot call through a World instance.

static func to_world(x: int, y: int, size: Vector2i, origin: Vector3 = Vector3.ZERO) -> Vector3:
	return origin + Vector3(
		x - float(size.x) / 2.0 + 0.5,
		0.0,
		-(y - float(size.y) / 2.0 + 0.5),
	)


static func to_square(point: Vector3, size: Vector2i, origin: Vector3 = Vector3.ZERO) -> Vector2i:
	return Vector2i(
		roundi(point.x - origin.x + float(size.x) / 2.0 - 0.5),
		roundi(origin.z - point.z + float(size.y) / 2.0 - 0.5),
	)
