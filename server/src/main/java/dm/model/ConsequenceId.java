package dm.model;

/**
 * One enum, one firing path. A sign is a consequence whose bundle is empty (ADR-0012).
 */
public enum ConsequenceId {
    LIGHT_LOW,
    LIGHT_GUTTERING,
    LIGHT_FAILING,
    LIGHT_OUT,
    SOMETHING_STIRRED,
    THE_FLAME_LEANS,
    IT_IS_CLOSE,
    PATROL_ARRIVES,
    SOMETHING_WANDERS_IN,
    IT_PASSES_BY,
    DRAWN_BY_THE_NOISE
}
