package dm.engine;

import dm.model.ClockId;
import dm.model.ConsequenceId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tables hang off the kind, not the {@link dm.model.Clock} record. Replay never reads them
 * (spec §6g): the fold sees {@code ClockTicked} / {@code ConsequenceFired} only.
 */
public final class ClockTables {

    private ClockTables() {
    }

    public static final Map<Integer, ConsequenceId> LIGHT_SIGNS = Map.of(
            2, ConsequenceId.LIGHT_LOW,
            4, ConsequenceId.LIGHT_GUTTERING,
            5, ConsequenceId.LIGHT_FAILING);

    public static final Map<Integer, ConsequenceId> ALERT_SIGNS = Map.of(
            2, ConsequenceId.SOMETHING_STIRRED,
            4, ConsequenceId.THE_FLAME_LEANS,
            5, ConsequenceId.IT_IS_CLOSE);

    public static final ConsequenceId LIGHT_FILL = ConsequenceId.LIGHT_OUT;

    public static final List<ConsequenceId> ALERT_FILL = List.of(
            ConsequenceId.PATROL_ARRIVES,
            ConsequenceId.SOMETHING_WANDERS_IN,
            ConsequenceId.IT_PASSES_BY,
            ConsequenceId.DRAWN_BY_THE_NOISE);

    private static final Set<ConsequenceId> EMPTY_BUNDLES = Set.of(
            ConsequenceId.LIGHT_LOW,
            ConsequenceId.LIGHT_GUTTERING,
            ConsequenceId.LIGHT_FAILING,
            ConsequenceId.LIGHT_OUT,
            ConsequenceId.SOMETHING_STIRRED,
            ConsequenceId.THE_FLAME_LEANS,
            ConsequenceId.IT_IS_CLOSE,
            ConsequenceId.IT_PASSES_BY);

    public static Map<Integer, ConsequenceId> signs(ClockId id) {
        return id == ClockId.LIGHT ? LIGHT_SIGNS : ALERT_SIGNS;
    }

    public static boolean bundleIsEmpty(ConsequenceId id) {
        return EMPTY_BUNDLES.contains(id);
    }

    /** Party-scoped clause when a torch relights the pool. Spec §8d. */
    public static final String TORCH_RELIT =
            "The torch catches; the ring of light spreads again.";

    /** Party-scoped clause when a potion is drunk. No number: the bar already shows it. */
    public static final String POTION_DRUNK =
            "A healing draught has just been drunk. A sentence — how it lands, never how much.";

    /** Room-scoped clause when the party takes something, carrying what it is. */
    public static String taken(String description, boolean objective) {
        return "The party has just taken this: " + description
                + (objective ? " It is what they came for." : "")
                + " A sentence — it is in hand now.";
    }

    /** Room-scoped clause when the party rests. Spec §8d. */
    public static final String REST =
            "The party has stopped to rest and catch their breath. A sentence or two — what "
                    + "the pause costs in this room, not a description from scratch.";

    /**
     * {@code LIGHT_OUT} as the narrator hears it. The last-torch sentence is supply, not a
     * sixth {@code PartyLight} value — spec §7a.
     */
    public static String lightOutClause(int remainingTorches) {
        if (remainingTorches <= 0) {
            return clause(ConsequenceId.LIGHT_OUT) + " — your last torch, when none are left.";
        }
        return clause(ConsequenceId.LIGHT_OUT);
    }

    /** Narrator clause for a sign or {@code LIGHT_OUT}. Spec §6c "About". */
    public static String clause(ConsequenceId id) {
        return switch (id) {
            case LIGHT_LOW -> "The ring of light has drawn in.";
            case LIGHT_GUTTERING -> "The flame stutters; the shadows swing.";
            case LIGHT_FAILING -> "Light at arm's length. One more and it is gone.";
            case LIGHT_OUT -> "The torch has gone out.";
            case SOMETHING_STIRRED -> "Far off, something moved and went quiet.";
            case THE_FLAME_LEANS -> "A draught, from somewhere that was shut.";
            case IT_IS_CLOSE -> "Near enough to hear. It knows roughly where you are.";
            case SOMETHING_WANDERS_IN -> "Something has wandered into this room.";
            case PATROL_ARRIVES -> "Something has come in, and it is not backing down.";
            case IT_PASSES_BY, DRAWN_BY_THE_NOISE -> "";
        };
    }
}
