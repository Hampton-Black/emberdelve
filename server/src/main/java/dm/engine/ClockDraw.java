package dm.engine;

import dm.model.ConsequenceId;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks an ALERT fill entry. Separate from {@link DiceRoller}: consuming scripted combat
 * faces for a table draw would desync {@code --demo}.
 */
@FunctionalInterface
public interface ClockDraw {

    ConsequenceId pick(List<ConsequenceId> options);

    static ClockDraw random() {
        return options -> options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }
}
