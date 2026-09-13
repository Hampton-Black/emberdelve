class_name Casting
extends RefCounted

## A small closed table, not a voice picker.
##
## `prefer` is matched by name prefix against whatever the machine has, so this degrades to
## pitch and rate alone on a box without these voices rather than falling silent. The macOS
## names are the good ones — Daniel is a measured British male, Ralph is croaky enough to be a
## goblin without any pitch shifting at all.

const TABLE := {
	"narrator": {"prefer": ["Daniel", "Alex"], "pitch": 0.95, "rate": 0.96},
	"goblin": {"prefer": ["Ralph", "Bahh", "Trinoids"], "pitch": 1.25, "rate": 1.06},
	"fighter": {"prefer": ["Reed", "Rocko", "Fred"], "pitch": 1.0, "rate": 1.0},
}

const DEFAULT := {"prefer": [], "pitch": 1.0, "rate": 1.0}


static func for_speaker(speaker_id: String) -> Dictionary:
	return TABLE.get(kind_for(speaker_id), DEFAULT)


## Parser speaker ids are entity ids. Voice is keyed on kind — goblin-2 is still a goblin,
## and the brute shares the hostile voice.
static func kind_for(speaker_id: String) -> String:
	if speaker_id == "narrator" or speaker_id == "player":
		return speaker_id
	var kind := String(Table.entity(speaker_id).get("kind", ""))
	if kind.is_empty():
		return speaker_id
	if kind == "brute":
		return "goblin"
	return kind
