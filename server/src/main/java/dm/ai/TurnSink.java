package dm.ai;

import dm.model.Diff;
import dm.model.NarrationSegment;
import dm.model.RollResult;
import dm.model.SceneState;

import java.util.List;

/** Everything one DM turn can push at the client. */
public interface TurnSink {

    void narration(NarrationSegment segment);

    void diffs(List<Diff> diffs);

    void roll(RollResult result);

    default void scene(SceneState scene) {
    }

    void complete();

    void error(Throwable error);
}
