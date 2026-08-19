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
}
