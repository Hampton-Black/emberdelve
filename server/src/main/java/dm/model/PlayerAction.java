package dm.model;

/**
 * Everything the client is allowed to ask for. Every variant carries an {@code actorId} —
 * there is no singleton player, even though M0's party has exactly one member (invariant #2).
 */
public sealed interface PlayerAction {

    String actorId();

    /** Free text works in both modes. In combat it reaches the DM without consuming the turn. */
    record FreeText(String actorId, String text) implements PlayerAction {}

    record MoveTo(String actorId, int x, int y) implements PlayerAction {}

    record AttackTarget(String actorId, String targetId) implements PlayerAction {}

    record EndTurn(String actorId) implements PlayerAction {}
}
