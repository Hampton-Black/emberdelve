package dm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * {@code faces} is a list and must stay one (invariant #5). The UI animates each die onto its own
 * value, and crits key off the natural d20 rather than the total.
 */
public record RollResult(
        RollRequest request,
        List<Integer> faces,
        int total,
        Outcome outcome
) {
    /** The natural d20, for crit detection. Meaningless on multi-die damage rolls. */
    public int natural() {
        return faces.isEmpty() ? 0 : faces.getFirst();
    }

    @JsonIgnore
    public boolean isCrit() {
        return outcome == Outcome.CRIT;
    }
}
