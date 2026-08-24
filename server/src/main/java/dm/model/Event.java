package dm.model;

import dm.generate.Dressing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The append-only spine. Roll results are logged here with their faces and outcome
 * (invariant #6) — replay reads results, it never re-rolls from a seed.
 */
public sealed interface Event {

    Instant at();

    record RollLogged(Instant at, RollResult result) implements Event {}

    record ActionTaken(Instant at, String actorId, String description) implements Event {}

    record NarrationLogged(Instant at, String speakerId, String text) implements Event {}

    record PropRevealedEvent(Instant at, String propId) implements Event {}

    record ModeEntered(Instant at, Mode mode) implements Event {}

    // ---- Session ----

    /**
     * Always the first line of a log. The version so a stale log is refused rather than migrated;
     * the seed and the models because six weeks from now, reading a session back, "which model
     * wrote this" is the first question.
     */
    record SessionStarted(Instant at, int schemaVersion, long seed,
                          String toolModel, String proseModel) implements Event {}

    // ---- Inputs: fine-grained, and inert in the fold ----

    record PlayerSaid(Instant at, String actorId, String text) implements Event {}

    /** Every call the model made, whether or not the dispatcher accepted it. */
    record ToolCallIssued(Instant at, Phase phase, String name, String argumentsJson,
                          boolean accepted, String message) implements Event {}

    // ---- Outcomes: coarse, and folded to state ----

    record PartySpawned(Instant at, List<Entity> members) implements Event {}

    record EntitySpawned(Instant at, Entity entity) implements Event {}

    /**
     * {@code movementSpent} is zero outside combat. Carried so a fight's remaining movement is a
     * fold over the log rather than a field in {@code CombatEngine} that nothing records.
     */
    record EntityMoved(Instant at, String entityId, int fromX, int fromY, int x, int y,
                       int movementSpent) implements Event {}

    /**
     * One attack, whole. Coarse on purpose: split into damage and death, the beat handed to the
     * narrator loses its attacker, and m0-evaluation.md §4.4 is what that costs.
     */
    record AttackResolved(Instant at, String actorId, String targetId, RollResult attack,
                          Optional<RollResult> damage, int damageDealt,
                          boolean hit, boolean killed) implements Event {}

    record CheckResolved(Instant at, String actorId, Optional<Skill> skill, int dc,
                         RollResult roll, Outcome outcome) implements Event {}

    record PropRevealed(Instant at, String roomId, String propId) implements Event {}

    /** The dress pass is not reproducible from the seed, so it is recorded. Spec §4b. */
    record RoomDressed(Instant at, String roomId, Dressing dressing) implements Event {}

    record CombatStarted(Instant at, List<Combatant> order,
                         List<RollResult> initiative) implements Event {}

    record TurnAdvanced(Instant at, String activeId, int round) implements Event {}

    record CombatEnded(Instant at) implements Event {}

    record FactAsserted(Instant at, String id, String roomId, String text,
                        Anchor anchor) implements Event {}

    static RollLogged roll(RollResult result) {
        return new RollLogged(Instant.now(), result);
    }

    static ActionTaken action(String actorId, String description) {
        return new ActionTaken(Instant.now(), actorId, description);
    }

    static NarrationLogged narration(String speakerId, String text) {
        return new NarrationLogged(Instant.now(), speakerId, text);
    }
}
