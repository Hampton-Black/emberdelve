package dm.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Returns a fixed sequence of die faces. Used by tests, and by {@code --demo} so a tuning pass
 * on audio or lighting is not confounded by a natural 3.
 *
 * <p>When the script runs dry it repeats from the start rather than throwing — a demo that
 * outlives its script should keep playing.
 */
public final class ScriptedDiceRoller extends AbstractDiceRoller {

    private final List<Integer> script;
    private final Deque<Integer> remaining = new ArrayDeque<>();

    public ScriptedDiceRoller(Integer... faces) {
        this(List.of(faces));
    }

    public ScriptedDiceRoller(List<Integer> faces) {
        if (faces.isEmpty()) {
            throw new IllegalArgumentException("Scripted roller needs at least one face");
        }
        this.script = List.copyOf(faces);
        this.remaining.addAll(script);
    }

    @Override
    protected int rollDie(int sides) {
        if (remaining.isEmpty()) {
            remaining.addAll(script);
        }
        int face = remaining.removeFirst();
        return Math.clamp(face, 1, sides);
    }
}
