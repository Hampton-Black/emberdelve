package dm.model;

import java.util.List;

/**
 * Everything the client needs to draw a combat turn — including the squares it may move to and
 * the creatures it may attack.
 *
 * <p>Shipping the legal sets rather than the inputs to compute them is invariant #1 taken
 * seriously. The client knows nothing about speed, difficult terrain, reach or blocking props;
 * it highlights what it is handed. When those rules grow in M1, no client code changes.
 */
public record CombatView(
        List<Combatant> order,
        String activeId,
        int round,
        /** Squares of movement the active combatant has left. Shown, never used to compute. */
        int movementRemaining,
        boolean actionAvailable,
        List<Square> legalMoves,
        List<String> legalTargets
) {
}
