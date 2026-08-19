package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.PartyMember;
import dm.model.Prop;
import dm.model.SceneState;
import dm.repo.GameRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative state. Applies actions and emits diffs; the client renders what it is told
 * and computes nothing (invariant #1).
 */
public final class GameEngine {

    private static final String ROOM_ID = "crypt";

    private final ContentLoader content;
    private final GameRepository repo;
    private final DiceRoller dice;
    private final RoomDefinition room;

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice) {
        this.content = content;
        this.repo = repo;
        this.dice = dice;
        this.room = content.room(ROOM_ID);
    }

    /** Spawn the party at the room's start positions. M0's party has exactly one member. */
    public void start() {
        var fighter = content.entity("fighter");
        var starts = room.startPositions().party();
        var members = new ArrayList<PartyMember>();

        for (int i = 0; i < starts.size(); i++) {
            var at = starts.get(i);
            // One member in M0, but the loop is the point — see invariant #2.
            String entityId = starts.size() == 1 ? fighter.id() : fighter.id() + "-" + i;
            repo.put(fighter.spawn(entityId, at.x(), at.y()));
            members.add(new PartyMember(entityId));
        }
        repo.setParty(List.copyOf(members));
        repo.setMode(Mode.EXPLORATION);
    }

    public RoomDefinition room() {
        return room;
    }

    public DiceRoller dice() {
        return dice;
    }

    public GameRepository repo() {
        return repo;
    }

    /**
     * The scene as the client is allowed to see it: hidden props are omitted entirely until
     * revealed, so a curious player cannot read the secrets out of a websocket frame.
     */
    public SceneState scene() {
        var revealed = repo.revealedPropIds();

        List<Prop> visible = room.props().stream()
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .map(p -> new Prop(p.id(), p.type(), p.x(), p.y(), p.rotation(), false))
                .toList();

        var entities = repo.entities().stream()
                .sorted(java.util.Comparator.comparing(Entity::id))
                .map(Entity::toView)
                .toList();

        return new SceneState(room.roomId(), room.width(), room.height(),
                room.floorType(), room.wallType(), visible, entities, room.lighting());
    }

    public Mode mode() {
        return repo.mode();
    }

    // ---- Mutations. Each returns the diffs the client needs to catch up. ----

    public List<Diff> moveTo(String actorId, int x, int y) {
        var entity = repo.find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));

        if (!isInBounds(x, y)) {
            throw new IllegalArgumentException("Off the grid: " + x + "," + y);
        }

        repo.put(entity.movedTo(x, y));
        repo.append(Event.action(actorId, "moved to " + x + "," + y));
        return List.of(new Diff.EntityMoved(actorId, entity.x(), entity.y(), x, y));
    }

    public List<Diff> revealProp(String propId) {
        var definition = room.prop(propId);

        if (repo.revealedPropIds().contains(propId)) {
            return List.of();
        }
        repo.reveal(propId);
        repo.append(new Event.PropRevealedEvent(Instant.now(), propId));
        return List.of(new Diff.PropRevealed(definition.toProp().revealed()));
    }

    public List<Diff> spawnGoblin(int x, int y) {
        var definition = content.entity("goblin");
        var goblin = definition.spawn(definition.id(), x, y);

        repo.put(goblin);
        repo.append(Event.action(goblin.id(), "appeared at " + x + "," + y));
        return List.of(new Diff.EntityAdded(goblin.toView()));
    }

    /** Where the goblin comes out of the sarcophagus, if the DM does not name a square. */
    public RoomDefinition.Point defaultGoblinSpawn() {
        return room.startPositions().goblinSpawn();
    }

    public List<Diff> setMode(Mode mode) {
        if (repo.mode() == mode) {
            return List.of();
        }
        repo.setMode(mode);
        repo.append(new Event.ModeEntered(Instant.now(), mode));
        return List.of(new Diff.ModeChanged(mode));
    }

    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < room.width() && y >= 0 && y < room.height();
    }
}
