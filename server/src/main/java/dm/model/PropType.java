package dm.model;

/** Closed set. The tileset defines the enum — the model can never name a prop that has no mesh. */
public enum PropType {
    SARCOPHAGUS,
    BRAZIER,
    PILLAR,
    RUBBLE,
    ALCOVE,
    DOOR,
    CHEST;

    /**
     * Whether a creature can stand on this prop's square. Derived from the type rather than
     * authored per prop: the tileset already decides whether a thing is a solid object or a
     * feature of a wall, and a per-prop flag would only be a chance to disagree with the mesh.
     */
    public boolean blocksMovement() {
        return switch (this) {
            case SARCOPHAGUS, BRAZIER, PILLAR, RUBBLE, CHEST -> true;
            // Both sit in a wall: the alcove is a recess, and the door is a slab in the face
            // of one. Standing in the doorway is legal.
            case ALCOVE, DOOR -> false;
        };
    }
}
