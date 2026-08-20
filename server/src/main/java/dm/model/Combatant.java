package dm.model;

/** One entry in the initiative order, with the roll that put it there. */
public record Combatant(String entityId, String name, int initiative, boolean isPlayerControlled) {
}
