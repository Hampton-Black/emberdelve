package dm.model;

/**
 * How much light the party is casting. A pure function of LIGHT {@code filled} — never stored
 * beside the clock, never a radius, never a segment count. Spec §7a.
 *
 * <p>The five names are the LIGHT table's own, so what the DM narrates and what the player sees
 * are the same five words. Level-triggered: a torch at {@code FAILING} returns {@code FULL}
 * without announcing the thresholds on the way down.
 */
public enum PartyLight {
    FULL,
    LOW,
    GUTTERING,
    FAILING,
    OUT;

    public static PartyLight of(int filled) {
        if (filled <= 1) {
            return FULL;
        }
        if (filled <= 3) {
            return LOW;
        }
        if (filled == 4) {
            return GUTTERING;
        }
        if (filled == 5) {
            return FAILING;
        }
        return OUT;
    }
}
