package dm.model;

/**
 * A prop id qualified by the room it is in.
 *
 * <p>Prop ids are unique within a room and nowhere else: {@code PropPlacer} names its output
 * {@code pillar-0}, {@code brazier-1}, and every generated room in a dungeon produces the same
 * names. A flat set of revealed ids therefore marks a prop found in a room the party has never
 * entered, and {@code ToolSchema} then stops offering {@code reveal_prop} for it — a secret that
 * silently cannot be found.
 *
 * <p>A value record in a set, matching how {@code Square} is already used as a key.
 */
public record PropRef(String roomId, String propId) {
}
