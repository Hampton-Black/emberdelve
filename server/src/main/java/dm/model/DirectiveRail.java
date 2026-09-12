package dm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Ordered, scoped pending clauses for the next prose phase. Replaces the single
 * {@code pendingArrival} string so a later writer cannot silently overwrite a sign.
 */
public final class DirectiveRail {

    private final List<Directive> entries = new ArrayList<>();
    private final BooleanSupplier fightRunning;

    public DirectiveRail(BooleanSupplier fightRunning) {
        this.fightRunning = fightRunning;
    }

    /**
     * Leave a clause on the rail. Engine-generated narration gives up when a fight is already
     * running; a clause left by the action that started the fight is latched first.
     */
    public void latch(Directive directive, boolean engineGenerated) {
        if (engineGenerated && fightRunning.getAsBoolean()) {
            return;
        }
        entries.add(directive);
    }

    public void latch(Directive directive) {
        latch(directive, true);
    }

    /**
     * A crossing: drop directives about any room but the one entered, keep the party's, then
     * append the arrival. Arrival is the player's action, so it is not dropped for a fight
     * this crossing just started.
     */
    public void crossedInto(String roomId, String arrivalClause) {
        entries.removeIf(d -> d.isAboutRoom() && !roomId.equals(d.roomId()));
        latch(Directive.aboutRoom(roomId, arrivalClause), false);
    }

    /** Spend every remaining clause, in order, as one joined string. Empty rail is {@code null}. */
    public String drain() {
        if (entries.isEmpty()) {
            return null;
        }
        String text = entries.stream().map(Directive::clause).collect(Collectors.joining(" "));
        entries.clear();
        return text;
    }

    public void clear() {
        entries.clear();
    }

    public List<Directive> snapshot() {
        return List.copyOf(entries);
    }
}
