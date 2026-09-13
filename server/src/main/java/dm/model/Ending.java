package dm.model;

/**
 * How a delve finished. Descriptive, never a verdict: only {@link #PARTY_LOST} is losing.
 * Spec §4, §4b.
 */
public enum Ending {
    EXTRACTED_WITH_OBJECTIVE,
    EXTRACTED_WITHOUT,
    PARTY_LOST
}
