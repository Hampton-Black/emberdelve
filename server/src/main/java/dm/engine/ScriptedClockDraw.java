package dm.engine;

import dm.model.ConsequenceId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Returns a fixed sequence of ALERT fill ids. Tests, and replay reconstructed from the log. */
public final class ScriptedClockDraw implements ClockDraw {

    private final Deque<ConsequenceId> remaining = new ArrayDeque<>();

    public ScriptedClockDraw(ConsequenceId... ids) {
        this(List.of(ids));
    }

    public ScriptedClockDraw(List<ConsequenceId> ids) {
        remaining.addAll(ids);
    }

    @Override
    public ConsequenceId pick(List<ConsequenceId> options) {
        if (options.isEmpty()) {
            return ConsequenceId.IT_PASSES_BY;
        }
        if (!remaining.isEmpty()) {
            var wanted = remaining.removeFirst();
            if (options.contains(wanted)) {
                return wanted;
            }
        }
        return options.getFirst();
    }
}
