package dm.model;

/**
 * The DM picks a band, never a number (M0 shortcut #6). Free-form DCs mean the same task is
 * DC 17 in one room and DC 13 an hour later, and difficulty stops meaning anything.
 */
public enum Difficulty {
    TRIVIAL(5),
    EASY(10),
    MEDIUM(15),
    HARD(20),
    VERY_HARD(25);

    private final int dc;

    Difficulty(int dc) {
        this.dc = dc;
    }

    public int dc() {
        return dc;
    }

    /**
     * The band a recorded DC came from. Replay reads a number back out of a log and has to put
     * it into the closed set it left as — invariant #7, doing its job on the way back in.
     */
    public static Difficulty ofDc(int dc) {
        for (var difficulty : values()) {
            if (difficulty.dc == dc) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException(
                "no difficulty band has DC " + dc + " — the log was written by another build");
    }
}
