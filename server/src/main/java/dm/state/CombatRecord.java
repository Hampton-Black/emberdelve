package dm.state;

import dm.model.Combatant;

import java.util.List;

/**
 * The durable half of a fight — everything a replayed log must be able to rebuild.
 *
 * <p>Not a {@link dm.model.CombatView}. The view carries legal moves and legal targets, which are
 * computed from the rules and must not be folded: a rules change would then be invisible to every
 * session recorded before it. This is what happened; the view is what it means.
 */
public record CombatRecord(
        List<Combatant> order,
        String activeId,
        int round,
        int movementRemaining,
        boolean actionAvailable
) {
    public CombatRecord {
        order = List.copyOf(order);
    }

    public CombatRecord spendMovement(int squares) {
        return new CombatRecord(order, activeId, round,
                Math.max(0, movementRemaining - squares), actionAvailable);
    }

    public CombatRecord spendAction() {
        return new CombatRecord(order, activeId, round, movementRemaining, false);
    }

    public CombatRecord beginTurn(String nextId, int newRound, int speedSquares) {
        return new CombatRecord(order, nextId, newRound, speedSquares, true);
    }
}
