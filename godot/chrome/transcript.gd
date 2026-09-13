extends RichTextLabel

## The DM's own record. Rebuilt from Table, never appended to directly.
##
## Prose and rolls live in one list so the log preserves the order things actually happened in —
## the dice, then the narration that commits to them.
##
## The colours come from one table so a creature reads the same whether it is speaking or
## rolling, and the roll wording comes from Tumble.caption so the tray and the log cannot drift.

const SPEAKER_COLOR := {
	"narrator": Color("cfc4ae"),
	"goblin": Color("8fae72"),
	"fighter": Color("c9b083"),
	"player": Color("7fa8d0"),
}


func _ready() -> void:
	Table.transcript_changed.connect(_redraw)
	_redraw()


func _redraw() -> void:
	var out := PackedStringArray()
	for entry in Table.transcript:
		if entry["kind"] == "prose":
			var who := String(entry["speakerId"])
			var tint: Color = SPEAKER_COLOR.get(
				Casting.kind_for(who), SPEAKER_COLOR["narrator"])
			out.append("[color=#%s]%s[/color]" % [tint.to_html(false), entry["text"]])
		else:
			out.append(_roll_line(entry["result"]))
	text = "\n\n".join(out)


## The roll log names who rolled. Names come from scene.entities — the server is the authority
## on what a creature is called — and the tone colour comes from Tumble so a success is never
## two different greens.
func _roll_line(result: Dictionary) -> String:
	var cap := Tumble.caption(result)
	var actor := Table.entity(String(result["request"]["actorId"]))
	var who := String(actor.get("name", result["request"]["actorId"]))
	var tone: Color = Tumble.TONE_COLOR[cap["tone"]]
	var target := "  %s" % cap["target"] if cap["target"] != "" else ""
	var outcome := "  [b]%s[/b]" % cap["outcome"] if cap["outcome"] != "" else ""
	return "[color=#%s]%s  %s%s  %s%s[/color]" % [
		tone.to_html(false), who, cap["label"], target, cap["arithmetic"], outcome]
