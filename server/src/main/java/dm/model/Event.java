package dm.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dm.generate.Dressing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The append-only spine. Roll results are logged here with their faces and outcome
 * (invariant #6) — replay reads results, it never re-rolls from a seed.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Event.SessionStarted.class, name = "session_started"),
        @JsonSubTypes.Type(value = Event.PlayerSaid.class, name = "player_said"),
        @JsonSubTypes.Type(value = Event.ToolCallIssued.class, name = "tool_call_issued"),
        @JsonSubTypes.Type(value = Event.NarrationLogged.class, name = "narration_logged"),
        @JsonSubTypes.Type(value = Event.PartySpawned.class, name = "party_spawned"),
        @JsonSubTypes.Type(value = Event.EntitySpawned.class, name = "entity_spawned"),
        @JsonSubTypes.Type(value = Event.EntityMoved.class, name = "entity_moved"),
        @JsonSubTypes.Type(value = Event.AttackResolved.class, name = "attack_resolved"),
        @JsonSubTypes.Type(value = Event.CheckResolved.class, name = "check_resolved"),
        @JsonSubTypes.Type(value = Event.PropRevealed.class, name = "prop_revealed"),
        @JsonSubTypes.Type(value = Event.RoomDressed.class, name = "room_dressed"),
        @JsonSubTypes.Type(value = Event.CombatStarted.class, name = "combat_started"),
        @JsonSubTypes.Type(value = Event.TurnAdvanced.class, name = "turn_advanced"),
        @JsonSubTypes.Type(value = Event.CombatEnded.class, name = "combat_ended"),
        @JsonSubTypes.Type(value = Event.ModeEntered.class, name = "mode_entered"),
        @JsonSubTypes.Type(value = Event.FactAsserted.class, name = "fact_asserted"),
})
public sealed interface Event {

    /**
     * Bumped whenever a recorded log stops being readable by this build. Old logs are refused,
     * never upgraded — spec §3. Discarding one is free; an upgrader is a tax paid forever.
     *
     * <p>2 (M3): entities carry a {@code roomId}, so every {@code party_spawned} and
     * {@code entity_spawned} line written at schema 1 describes an entity standing nowhere.
     */
    int SCHEMA_VERSION = 2;

    Instant at();

    record NarrationLogged(Instant at, String speakerId, String text) implements Event {}

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

    static NarrationLogged narration(String speakerId, String text) {
        return new NarrationLogged(Instant.now(), speakerId, text);
    }
}
