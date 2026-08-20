package dm.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Running latency and shape stats for the session.
 *
 * <p>Exists to answer a specific open question: how often do turns actually involve dice? The
 * split-model design only pays off on tool turns, because dice cover the prose latency. If most
 * real play turns out to be tool-free, the no-tool wait matters much more than it currently
 * looks and the design should change. Decide that with the number, at T13.
 */
public final class TurnMetrics {

    private static final Logger log = LoggerFactory.getLogger(TurnMetrics.class);

    private final AtomicInteger turns = new AtomicInteger();
    private final AtomicInteger turnsWithTools = new AtomicInteger();
    private final AtomicInteger toolsApplied = new AtomicInteger();
    private final AtomicInteger toolsRejected = new AtomicInteger();
    private final AtomicLong totalToolPhaseMs = new AtomicLong();
    private final AtomicLong totalProseMs = new AtomicLong();

    public void record(TurnShape shape) {
        turns.incrementAndGet();
        if (shape.toolCalls() > 0) {
            turnsWithTools.incrementAndGet();
        }
        toolsApplied.addAndGet(shape.applied());
        toolsRejected.addAndGet(shape.rejected());
        totalToolPhaseMs.addAndGet(shape.toolPhaseMs());
        totalProseMs.addAndGet(shape.proseMs());

        // firstFeedbackMs is the number that matters: it is when the player sees dice, not
        // when the model finished deciding. Total phase time is diagnostic, not the budget.
        log.info("turn: FIRST FEEDBACK {}ms | tools {}ms ({} calls, {} rejected) | "
                        + "prose {}ms first-token {}ms | total {}ms | "
                        + "session: {}/{} turns used dice",
                shape.firstFeedbackMs() < 0 ? "none" : shape.firstFeedbackMs(),
                shape.toolPhaseMs(), shape.toolCalls(), shape.rejected(),
                shape.proseMs(), shape.proseFirstTokenMs(),
                shape.toolPhaseMs() + shape.proseMs(),
                turnsWithTools.get(), turns.get());
    }

    private long average(AtomicLong total) {
        int count = turns.get();
        return count == 0 ? 0 : total.get() / count;
    }

    /** One turn's measurements. */
    public record TurnShape(
            int toolCalls,
            int applied,
            int rejected,
            /** When the player first sees anything happen — dice, a reveal, a spawn. */
            long firstFeedbackMs,
            long toolPhaseMs,
            long proseMs,
            long proseFirstTokenMs
    ) {
    }
}
