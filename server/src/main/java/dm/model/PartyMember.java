package dm.model;

/**
 * A party slot. M0's party is a {@code List<PartyMember>} containing exactly one member —
 * never a singleton field (invariant #2). This record exists so that adding a second member
 * later is a content change rather than a refactor.
 */
public record PartyMember(String entityId) {
}
