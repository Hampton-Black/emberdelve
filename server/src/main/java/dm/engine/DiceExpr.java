package dm.engine;

/**
 * The smallest possible parse of "NdM". Notation parsing is the least interesting part of dice
 * (design doc §7) — this exists only because the content files have to say "1d8" somehow.
 */
record DiceExpr(int count, int sides) {

    static DiceExpr parse(String expr) {
        var parts = expr.toLowerCase().trim().split("d");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Bad dice expression: " + expr);
        }
        try {
            return new DiceExpr(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad dice expression: " + expr, e);
        }
    }
}
