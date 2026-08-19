package dm.engine;

import dm.model.Advantage;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared roll assembly and outcome adjudication. Subclasses supply raw die faces and nothing else,
 * so the scripted and random rollers cannot disagree about what a 20 means.
 */
abstract class AbstractDiceRoller implements DiceRoller {

    /** Returns a value in [1, sides]. */
    protected abstract int rollDie(int sides);

    @Override
    public RollResult roll(RollRequest request) {
        var expr = DiceExpr.parse(request.dice());
        var faces = new ArrayList<Integer>();
        int counted;

        boolean picksOneOfTwo = request.advantage() != Advantage.NORMAL && expr.count() == 1;
        if (picksOneOfTwo) {
            int a = rollDie(expr.sides());
            int b = rollDie(expr.sides());
            int kept = request.advantage() == Advantage.ADVANTAGE ? Math.max(a, b) : Math.min(a, b);
            // Kept die goes first so natural() stays meaningful; the discard is retained for the UI.
            faces.add(kept);
            faces.add(kept == a ? b : a);
            counted = kept;
        } else {
            for (int i = 0; i < expr.count(); i++) {
                faces.add(rollDie(expr.sides()));
            }
            counted = faces.stream().mapToInt(Integer::intValue).sum();
        }

        int total = counted + request.modifier();
        return new RollResult(request, List.copyOf(faces), total,
                adjudicate(request, faces.getFirst(), total));
    }

    private Outcome adjudicate(RollRequest request, int natural, int total) {
        return switch (request.purpose()) {
            case ATTACK -> {
                if (natural == 20) yield Outcome.CRIT;
                if (natural == 1) yield Outcome.CRIT_FAIL;
                int ac = request.dc().orElseThrow(() ->
                        new IllegalArgumentException("Attack roll needs the target's AC in dc()"));
                yield total >= ac ? Outcome.HIT : Outcome.MISS;
            }
            case SKILL_CHECK, SAVE -> {
                int dc = request.dc().orElseThrow(() ->
                        new IllegalArgumentException(request.purpose() + " needs a dc()"));
                yield total >= dc ? Outcome.SUCCESS : Outcome.FAILURE;
            }
            case DAMAGE, INITIATIVE -> Outcome.SUCCESS;
        };
    }
}
