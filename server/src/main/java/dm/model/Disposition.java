package dm.model;

/**
 * How a meeting starts. A consequence, or an authored occupant, brings something in; this
 * decides whether the meeting is already a fight. Spec §6d.
 *
 * <p>Fire-time, not an entity field: the same goblin is {@code WARY} walking in and {@code HOSTILE}
 * once someone swings. Two values, because an unexercised seam is a claim rather than a behaviour.
 */
public enum Disposition {
    /** On the board in exploration. A swing starts a fight through {@code start_combat}. */
    WARY,
    /** Initiative is rolled as they arrive. */
    HOSTILE
}
