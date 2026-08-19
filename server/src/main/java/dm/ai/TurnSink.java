package dm.ai;

import dm.model.Diff;
import dm.model.NarrationSegment;
import dm.model.RollResult;

import java.util.List;

/** Everything one DM turn can push at the client. */
public interface TurnSink {

    void narration(NarrationSegment segment);

    void diffs(List<Diff> diffs);

    void roll(RollResult result);

    void complete();

    void error(Throwable error);
}
