package dm.repo;

import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.PartyMember;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M0 has no save. A new game is {@link #clear} and a fresh spawn, not a file (§12).
 */
public final class InMemoryGameRepository implements GameRepository {

    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<List<PartyMember>> party = new AtomicReference<>(List.of());
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.EXPLORATION);

    @Override
    public void clear() {
        entities.clear();
        revealed.clear();
        events.clear();
        party.set(List.of());
        mode.set(Mode.EXPLORATION);
    }

    @Override
    public Optional<Entity> find(String entityId) {
        return Optional.ofNullable(entities.get(entityId));
    }

    @Override
    public List<Entity> entities() {
        return List.copyOf(entities.values());
    }

    @Override
    public void put(Entity entity) {
        entities.put(entity.id(), entity);
    }

    @Override
    public void remove(String entityId) {
        entities.remove(entityId);
    }

    @Override
    public List<PartyMember> party() {
        return party.get();
    }

    @Override
    public void setParty(List<PartyMember> members) {
        party.set(List.copyOf(members));
    }

    @Override
    public Mode mode() {
        return mode.get();
    }

    @Override
    public void setMode(Mode newMode) {
        mode.set(newMode);
    }

    @Override
    public Set<String> revealedPropIds() {
        return Set.copyOf(revealed);
    }

    @Override
    public void reveal(String propId) {
        revealed.add(propId);
    }

    @Override
    public void append(Event event) {
        events.add(event);
    }

    @Override
    public List<Event> events() {
        return List.copyOf(events);
    }
}
