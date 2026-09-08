extends SceneTree

## Throwaway: proves preview_room.build_into works before anyone is asked to run it in the
## editor. Builds into a DETACHED node, so _ready never fires — which is exactly what the
## editor does to a non-@tool script like world.gd.
##
##   /Applications/Godot.app/Contents/MacOS/Godot --headless --path . -s dev/_spike_check.gd

func _initialize() -> void:
	var script := load("res://dev/preview_room.gd")
	var root := Node3D.new()
	var report: Dictionary = script.build_into(root, "crypt")
	print("REPORT: ", report)
	if report.has("error"):
		root.free()
		quit(1)
		return
	var ok: bool = report["floor"] > 0 and report["walls"] > 0 and report["props"] > 0
	root.free()
	print("VERDICT: ", "PASS" if ok else "FAIL")
	quit(0 if ok else 1)
