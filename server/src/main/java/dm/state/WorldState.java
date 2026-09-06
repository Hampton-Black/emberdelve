package dm.state;

import dm.model.Combatant;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Fact;
import dm.model.Mode;
import dm.model.Outcome;
import dm.model.PartyMember;
import dm.model.PropRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The world, as a value.
 *
 * <p>Produced only by folding the log — spec §5a. There is no setter and no other write path, so
 * a state change that forgot to emit an event does not happen quietly, it does not happen at all.
 *
 * <p>Immutable because a turn reads state at three separate moments (the mechanics pass, the
 * prose pass, the reconcile pass) and that sequencing was previously implicit in when each call
 * happened to run. As a value it becomes an argument someone passes.
 */
public record WorldState(
        String roomId,
        Map<String, Entity> entities,
        List<PartyMember> party,
        Mode mode,
        Set<PropRef> revealedProps,
        Optional<CombatRecord> combat,
        List<Fact> facts,
        int consecutiveFailedChecks
) {

    /** Spec §8: a backstop against a model that asserts something every single turn. */
    public static final int MAX_FACTS_PER_ROOM = 30;

    public static final WorldState EMPTY = new WorldState(
            "crypt", Map.of(), List.of(), Mode.EXPLORATION, Set.of(),
            Optional.empty(), List.of(), 0);

    public WorldState {
        entities = Map.copyOf(entities);
        party = List.copyOf(party);
        revealedProps = Set.copyOf(revealedProps);
        facts = List.copyOf(facts);
    }

    public static WorldState fold(List<Event> events) {
        var state = EMPTY;
        for (var event : events) {
            state = state.apply(event);
        }
        return state;
    }

    public Optional<Entity> find(String id) {
        return Optional.ofNullable(entities.get(id));
    }

    public List<Entity> living() {
        return entities.values().stream().filter(Entity::isAlive).toList();
    }

    /** Facts asserted in the room the party is standing in. Spec §8: room-scoped. */
    public List<Fact> factsHere() {
        return facts.stream().filter(f -> f.roomId().equals(roomId)).toList();
    }

    /**
     * The whole of the write path.
     *
     * <p>Input events — what the player typed, what the model called, what was narrated — return
     * {@code this}. They are in the log for the corpus and for replay, and they are not state.
     */
    public WorldState apply(Event event) {
        return switch (event) {
            case Event.PartySpawned e -> withParty(e.members());
            case Event.EntitySpawned e -> withEntity(e.entity());
            case Event.EntityMoved e -> moved(e);
            case Event.AttackResolved e -> attacked(e);
            case Event.CheckResolved e -> checked(e);
            case Event.PropRevealed e -> revealed(new PropRef(e.roomId(), e.propId()));
            case Event.CombatStarted e -> combatStarted(e.order());
            case Event.TurnAdvanced e -> turnAdvanced(e);
            case Event.CombatEnded ignored -> combatEnded();
            case Event.ModeEntered e -> withMode(e.mode());
            case Event.FactAsserted e -> asserted(e);
            // Inputs, session framing, and the dress pass, which is content rather than state.
            case Event.SessionStarted ignored -> this;
            case Event.PlayerSaid ignored -> this;
            case Event.ToolCallIssued ignored -> this;
            case Event.NarrationLogged ignored -> this;
            case Event.RoomDressed ignored -> this;
        };
    }

    // ---- The cases ----

    private WorldState withParty(List<Entity> members) {
        var next = new LinkedHashMap<>(entities);
        members.forEach(m -> next.put(m.id(), m));
        return copy(next, members.stream().map(m -> new PartyMember(m.id())).toList(),
                mode, revealedProps, combat, facts, consecutiveFailedChecks);
    }

    private WorldState withEntity(Entity entity) {
        var next = new LinkedHashMap<>(entities);
        next.put(entity.id(), entity);
        return copy(next, party, mode, revealedProps, combat, facts, consecutiveFailedChecks);
    }

    private WorldState moved(Event.EntityMoved e) {
        var entity = entities.get(e.entityId());
        if (entity == null) {
            return this;
        }
        var next = new LinkedHashMap<>(entities);
        next.put(e.entityId(), entity.movedTo(e.x(), e.y()));
        return copy(next, party, mode, revealedProps,
                combat.map(c -> c.spendMovement(e.movementSpent())),
                facts, consecutiveFailedChecks);
    }

    private WorldState attacked(Event.AttackResolved e) {
        var next = new LinkedHashMap<>(entities);
        var target = entities.get(e.targetId());
        if (target != null && e.damageDealt() > 0) {
            next.put(e.targetId(), target.damaged(e.damageDealt()));
        }
        return copy(next, party, mode, revealedProps,
                combat.map(CombatRecord::spendAction), facts, consecutiveFailedChecks);
    }

    private WorldState checked(Event.CheckResolved e) {
        // Deliberately not keyed by skill, exactly as GameEngine had it: shoving a lid, failing,
        // then searching a wall and failing again is one player who is stuck.
        boolean succeeded = e.outcome() == Outcome.SUCCESS || e.outcome() == Outcome.CRIT;
        return copy(entities, party, mode, revealedProps, combat, facts,
                succeeded ? 0 : consecutiveFailedChecks + 1);
    }

    private WorldState revealed(PropRef ref) {
        var next = new LinkedHashSet<>(revealedProps);
        next.add(ref);
        return copy(entities, party, mode, next, combat, facts, consecutiveFailedChecks);
    }

    /**
     * Prop ids revealed in the room the party is standing in — the shape every caller actually
     * wants, since a room's props are addressed by bare id everywhere else.
     */
    public Set<String> revealedHere() {
        return revealedProps.stream()
                .filter(ref -> ref.roomId().equals(roomId))
                .map(PropRef::propId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private WorldState combatStarted(List<Combatant> order) {
        String activeId = order.getFirst().entityId();
        var record = new CombatRecord(order, activeId, 1, speedOf(activeId), true);
        return copy(entities, party, Mode.COMBAT, revealedProps,
                Optional.of(record), facts, consecutiveFailedChecks);
    }

    private WorldState turnAdvanced(Event.TurnAdvanced e) {
        return copy(entities, party, mode, revealedProps,
                combat.map(c -> c.beginTurn(e.activeId(), e.round(), speedOf(e.activeId()))),
                facts, consecutiveFailedChecks);
    }

    private WorldState combatEnded() {
        return copy(entities, party, Mode.EXPLORATION, revealedProps,
                Optional.empty(), facts, consecutiveFailedChecks);
    }

    private WorldState withMode(Mode newMode) {
        return copy(entities, party, newMode, revealedProps, combat, facts,
                consecutiveFailedChecks);
    }

    private WorldState asserted(Event.FactAsserted e) {
        var next = new ArrayList<>(facts);
        next.add(new Fact(e.id(), e.roomId(), e.text(), e.anchor()));
        // Oldest out, and only from this room's share, so a busy room cannot evict a quiet one.
        long here = next.stream().filter(f -> f.roomId().equals(e.roomId())).count();
        if (here > MAX_FACTS_PER_ROOM) {
            next.stream()
                    .filter(f -> f.roomId().equals(e.roomId()))
                    .findFirst()
                    .ifPresent(next::remove);
        }
        return copy(entities, party, mode, revealedProps, combat, next,
                consecutiveFailedChecks);
    }

    private int speedOf(String entityId) {
        return find(entityId).map(Entity::speedSquares).orElse(0);
    }

    private WorldState copy(Map<String, Entity> newEntities, List<PartyMember> newParty,
                            Mode newMode, Set<PropRef> newRevealed,
                            Optional<CombatRecord> newCombat, List<Fact> newFacts,
                            int newFailedChecks) {
        return new WorldState(roomId, newEntities, newParty, newMode, newRevealed,
                newCombat, newFacts, newFailedChecks);
    }
}
