package dm.repo;

import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.PartyMember;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The seam Postgres slides behind in M5. M0's implementation is a {@code ConcurrentHashMap}
 * (shortcut #7) and the event log is a {@code List} (shortcut #8) — but callers must not
 * know that.
 */
public interface GameRepository {

    Optional<Entity> find(String entityId);

    List<Entity> entities();

    void put(Entity entity);

    void remove(String entityId);

    /** Always a list, never a singleton (invariant #2). */
    List<PartyMember> party();

    void setParty(List<PartyMember> party);

    Mode mode();

    void setMode(Mode mode);

    Set<String> revealedPropIds();

    void reveal(String propId);

    void append(Event event);

    List<Event> events();
}
