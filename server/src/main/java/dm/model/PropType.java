package dm.model;

/** Closed set. The tileset defines the enum — the model can never name a prop that has no mesh. */
public enum PropType {
    SARCOPHAGUS,
    BRAZIER,
    PILLAR,
    RUBBLE,
    ALCOVE,
    DOOR,
    CONTAINER,
    STATUE,
    FURNITURE,
    REMAINS,
    SCENERY;

    /**
     * Whether a creature can stand on this prop's square. Derived from the type rather than
     * authored per prop: the tileset already decides whether a thing is a solid object or a
     * feature of a wall, and a per-prop flag would only be a chance to disagree with the mesh.
     * Appearance never changes this — a barrel and a chest are both {@link #CONTAINER}.
     */
    public boolean blocksMovement() {
        return switch (this) {
            case SARCOPHAGUS, BRAZIER, PILLAR, RUBBLE, CONTAINER, STATUE, FURNITURE, REMAINS -> true;
            // The alcove is a recess, the door is a slab in a wall face, and scenery is dress
            // that does not occupy the square. Standing there is legal.
            case ALCOVE, DOOR, SCENERY -> false;
        };
    }
}
