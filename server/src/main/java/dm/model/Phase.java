package dm.model;

/**
 * Which model made a tool call.
 *
 * <p>Recorded because a replayed log otherwise cannot tell the mechanics pass from the reconcile
 * pass, and "which model did this" is the first question asked of any session that went strange —
 * m0-evaluation.md asks it on nearly every page.
 */
public enum Phase { MECHANICS, RECONCILE }
