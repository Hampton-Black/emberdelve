package dm.engine;

import dm.content.ContentLoader;
import dm.content.Dressings;
import dm.content.RoomDefinition;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Exit;
import dm.model.Mode;
import dm.model.Prop;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Skill;
import dm.model.Difficulty;
import dm.model.RoomView;
import dm.model.SceneState;
import dm.model.Square;
import dm.model.WallSegment;
import dm.state.EventLog;
import dm.state.WorldState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative state. Applies actions and emits diffs; the client renders what it is told
 * and computes nothing (invariant #1).
 */
public final class GameEngine {

    private final ContentLoader content;
    private final EventLog log;
    private final DiceRoller dice;
    private final Rooms rooms;
    private final CombatEngine combat;

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice) {
        this(content, log, dice, Rooms.authored(content, "crypt"));
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, RoomDefinition room) {
        this(content, log, dice, Rooms.of(room));
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, Rooms rooms) {
        this.content = content;
        this.log = log;
        this.dice = dice;
        this.rooms = rooms;
        if (!WorldState.EMPTY.roomId().equals(rooms.first().roomId())) {
            throw new IllegalArgumentException(
                    "The entrance must be '" + WorldState.EMPTY.roomId() + "' until the fold's "
                            + "starting room is configurable, got '" + rooms.first().roomId() + "'");
        }
        this.combat = new CombatEngine(log, dice, this::room);
    }

    /** The log. The only way anything in this engine writes. */
    public EventLog log() {
        return log;
    }

    /** The world as it stands. A value — hold it for as long as you need one answer. */
    public WorldState state() {
        return log.state();
    }

    /** Spawn the party at the entrance room's start positions. M3's party has one member. */
    public void start() {
        var entrance = rooms.first();
        var fighter = content.entity("fighter");
        var starts = entrance.startPositions().party();
        var members = new ArrayList<Entity>();

        for (int i = 0; i < starts.size(); i++) {
            var at = starts.get(i);
            // One member in M3, but the loop is the point — see invariant #2.
            String entityId = starts.size() == 1 ? fighter.id() : fighter.id() + "-" + i;
            members.add(fighter.spawn(entityId, entrance.roomId(), at.x(), at.y()));
        }

        // The party is in the entrance before it is spawned there: PartySpawned does not move
        // anyone, and WorldState.EMPTY names "crypt". Recording the dressing first also means
        // the opening narration sees the room already dressed.
        recordDressing(entrance.roomId());
        log.append(new Event.PartySpawned(Instant.now(), List.copyOf(members)));
        log.append(new Event.ModeEntered(Instant.now(), Mode.EXPLORATION));
    }

    /**
     * Write down what a room looks like, the first time anyone stands in it.
     *
     * <p>Authored rooms go through this exactly as generated ones will — spec §5b. The prose is
     * already on disk, so the event is redundant today and is the point: it means the fold, the
     * compose and the replay of a dressed room are all exercised now, with no model in the path,
     * rather than for the first time in the generator plan.
     */
    private void recordDressing(String roomId) {
        if (state().dressingOf(roomId).isPresent()) {
            return;
        }
        log.append(new Event.RoomDressed(Instant.now(), roomId,
                Dressings.of(rooms.structure(roomId))));
    }

    /**
     * Wipe the session and lay the room out again.
     *
     * <p>Not save/load, which §12 rules out — there is nothing to load. It is the same thing
     * restarting the process does, reached without restarting the process, which matters because
     * the goblin is genuinely dangerous and a playtest that ends in death should not also end in
     * a terminal.
     */
    public void restart() {
        combat.reset();
        Event.SessionStarted header = log.events().stream()
                .filter(Event.SessionStarted.class::isInstance)
                .map(Event.SessionStarted.class::cast)
                .findFirst()
                .orElse(null);
        log.clear();
        if (header != null) {
            log.append(new Event.SessionStarted(Instant.now(), header.schemaVersion(),
                    header.seed(), header.toolModel(), header.proseModel()));
        }
        start();
    }

    /**
     * The content files, for anything that needs what an entity <em>is</em> rather than where it
     * stands — the narrator needs to know the fighter carries a longsword, and {@link Entity}
     * deliberately carries only what the rules use.
     */
    public ContentLoader content() {
        return content;
    }

    /** The room the party is standing in, structure and dressing composed. */
    public RoomDefinition room() {
        return room(state().roomId());
    }

    /**
     * Any room, dressed with whatever the log is holding for it.
     *
     * <p>Composed on read rather than cached. Spec §5b: a cache would not survive
     * {@code restart()} and could not be rebuilt by a replay that has no model.
     */
    public RoomDefinition room(String roomId) {
        var structure = rooms.structure(roomId);
        return state().dressingOf(roomId)
                .map(dressing -> Dressings.applyTo(structure, dressing))
                .orElse(structure);
    }

    public Rooms rooms() {
        return rooms;
    }

    public DiceRoller dice() {
        return dice;
    }

    public CombatEngine combat() {
        return combat;
    }

    /**
     * The scene as the client is allowed to see it: hidden props are omitted entirely until
     * revealed, so a curious player cannot read the secrets out of a websocket frame.
     */
    public SceneState scene() {
        var here = room();
        var visible = visiblePropsOf(here, state().revealedHere());

        var entities = state().entitiesHere().stream()
                .sorted(java.util.Comparator.comparing(Entity::id))
                .map(Entity::toView)
                .toList();

        // Off the visible list, not off here.isObstructed(): that consults hidden props too, and
        // a red square over a secret is a worse bug than no hint at all.
        var blocked = visible.stream()
                .filter(p -> p.type().blocksMovement())
                .map(p -> new Square(p.x(), p.y()))
                .toList();

        var known = knownRoomIds(here);
        var roomViews = known.stream().map(this::roomView).toList();

        return new SceneState(here.roomId(), roomViews, entities, blocked, state().mode(),
                combat.view());
    }

    /**
     * Every room the client is told exists: rooms the party has stood in, plus rooms visible
     * through one of the current room's own exits — spec §8b. Not transitive: a room two doors
     * away is not offered just because the door in front of it is.
     *
     * <p>A room reachable only through a one-way exit has no {@link Rooms#origins() origin} to
     * place it at, so it is left out here exactly as {@link Rooms} itself leaves it out rather
     * than guess at a position. M3's authored content has no such exit.
     */
    private Set<String> knownRoomIds(RoomDefinition here) {
        var origins = rooms.origins();
        var known = new LinkedHashSet<String>();
        for (var roomId : state().visitedRoomIds()) {
            if (origins.containsKey(roomId)) {
                known.add(roomId);
            }
        }
        for (var exit : here.exits()) {
            if (rooms.has(exit.toRoomId()) && origins.containsKey(exit.toRoomId())) {
                known.add(exit.toRoomId());
            }
        }
        return known;
    }

    /**
     * One room's whole picture, current or not — decision 6: current and non-current rooms are
     * built by the same code, because {@link Rooms#coveredWalls()} decides the shared-wall owner
     * by BFS distance rather than by which room the party is standing in.
     */
    private RoomView roomView(String roomId) {
        var structure = rooms.structure(roomId);
        var visited = state().hasVisited(roomId);
        // Geometry only when unvisited — the DM was never told this room exists, so a furnished
        // neighbour would be a room on screen the narrator can and will contradict.
        var props = visited ? visiblePropsOf(structure, state().revealedIn(roomId)) : List.<Prop>of();
        var origin = rooms.origins().get(roomId);

        return new RoomView(roomId, structure.width(), structure.height(), structure.floorType(),
                structure.wallType(), structure.lighting(), props, structure.exits(),
                coveredWallsOf(roomId), origin.x(), origin.z(), visited);
    }

    /**
     * The segments this room leaves to a room nearer the entrance, in a fixed order.
     *
     * <p>{@link Rooms#coveredWalls()} answers in a {@code Set} because the rule is about
     * membership. The wire wants a sequence, and an unordered one would put different bytes on
     * it for the same session — so it is sorted here, at the one place a set becomes a list,
     * rather than left to whoever reads it.
     */
    private List<WallSegment> coveredWallsOf(String roomId) {
        return rooms.coveredWalls().getOrDefault(roomId, Set.<WallSegment>of()).stream()
                .sorted(java.util.Comparator.comparing(WallSegment::direction)
                        .thenComparingInt(WallSegment::x)
                        .thenComparingInt(WallSegment::y))
                .toList();
    }

    /** A room's props as the client may see them: hidden ones withheld unless revealed there. */
    private List<Prop> visiblePropsOf(RoomDefinition def, Set<String> revealed) {
        return def.props().stream()
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .map(p -> new Prop(p.id(), p.type(), p.x(), p.y(), p.rotation(), false))
                .toList();
    }

    public Mode mode() {
        return state().mode();
    }

    // ---- Mutations. Each returns the diffs the client needs to catch up. ----

    /**
     * Take the party through a door.
     *
     * <p>The whole party, always — spec §6a. {@code PartyMoved} carries a list of movers so that
     * splitting is a policy change rather than a schema bump, but M3's policy is that the list is
     * everyone still standing.
     *
     * <p>No adjacency requirement. Crossing implies walking to the door, everyone lands on
     * {@link Exit#inward}, and where anyone stood beforehand has no consequence — a rule here
     * would only ever produce a rejection the player finds annoying.
     */
    public List<Diff> crossExit(String exitId) {
        if (combat.isActive()) {
            // Fleeing is a rules milestone. Without this the fight's initiative order survives
            // in a room nobody is standing in.
            throw new IllegalArgumentException(
                    "You cannot leave in the middle of a fight.");
        }

        var here = room();
        var exit = here.exits().stream()
                .filter(e -> e.id().equals(exitId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No exit '" + exitId + "' in " + here.roomId()));

        if (!rooms.has(exit.toRoomId())) {
            throw new IllegalArgumentException(
                    "'" + exitId + "' leads to " + exit.toRoomId() + ", which is not in this "
                            + "session");
        }

        var destination = rooms.structure(exit.toRoomId());
        var arrival = destination.exits().stream()
                .filter(e -> e.toRoomId().equals(here.roomId()))
                .findFirst()
                .map(back -> back.inward(destination.width(), destination.height()))
                .orElseGet(() -> {
                    // A one-way exit is legal and has no answering door to land beside, so the
                    // destination's authored party start is the fallback.
                    var at = destination.startPositions().party().getFirst();
                    return new Square(at.x(), at.y());
                });

        var movers = state().party().stream()
                .map(member -> state().find(member.entityId()))
                .flatMap(Optional::stream)
                .filter(Entity::isAlive)
                .map(Entity::id)
                .toList();

        // Dressed before the move, so anything reading state() after this call finds a room
        // that already knows what it looks like.
        recordDressing(exit.toRoomId());
        log.append(new Event.PartyMoved(Instant.now(), movers, here.roomId(),
                exit.toRoomId(), exitId, arrival.x(), arrival.y()));

        // A room change replaces everything, which is what a fresh Scene is for. The caller
        // sends one; there is no diff small enough to be worth inventing. Spec §9.
        return List.of();
    }

    /**
     * In combat this is the same call the goblin makes, spending the same movement and checked
     * against the same reachable set. Out of combat there is nothing to spend, so a move is
     * whatever the player clicked.
     */
    public void moveTo(String actorId, int x, int y, CombatSink sink) {
        if (combat.isActive()) {
            combat.moveTo(actorId, x, y, sink);
            return;
        }

        var entity = state().find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));

        // Out of combat nothing else was checking this. A fighter who lost the fight was still
        // free to walk around the room he had just died in, because the only aliveness test here
        // was on the square being moved into rather than on whoever was moving.
        if (!entity.isAlive()) {
            throw new IllegalArgumentException(entity.name() + " is dead.");
        }

        if (!isInBounds(x, y)) {
            throw new IllegalArgumentException("Off the grid: " + x + "," + y);
        }
        if (room().isObstructed(x, y)) {
            throw new IllegalArgumentException("Something solid is already at " + x + "," + y);
        }
        boolean occupied = state().entitiesHere().stream()
                .anyMatch(e -> e.isAlive() && !e.id().equals(actorId) && e.x() == x && e.y() == y);
        if (occupied) {
            throw new IllegalArgumentException("Someone is standing at " + x + "," + y);
        }

        // Zero movement spent: outside a fight there is no budget to spend. The field exists so
        // a fight's remaining movement folds out of the log instead of living in a field.
        log.append(new Event.EntityMoved(Instant.now(), actorId,
                entity.x(), entity.y(), x, y, 0));
        sink.diffs(List.of(new Diff.EntityMoved(actorId, entity.x(), entity.y(), x, y)));
    }

    public List<Diff> revealProp(String propId) {
        var definition = room().prop(propId);

        if (state().revealedHere().contains(propId)) {
            return List.of();
        }
        log.append(new Event.PropRevealed(Instant.now(), state().roomId(), propId));
        return List.of(new Diff.PropRevealed(definition.toProp().revealed()));
    }

    /**
     * Puts the goblin on the grid. There is room for exactly one.
     *
     * <p>The entity id is the definition's, which is the constant {@code "goblin"}, and
     * the world is keyed by id — so a second spawn never added a second creature, it
     * overwrote the first. Overwriting a <em>dead</em> one is a resurrection: back at full hp,
     * back on the board, and rolling initiative in the next fight as though nothing had
     * happened. Vessk came back from a fight he had lost.
     *
     * <p>Refusing is the honest fix rather than the convenient one. Handing each spawn its own
     * id would let a second goblin exist, but the id is still load-bearing outside the engine —
     * {@code DmService} decides which sarcophagus note to show by whether an entity called
     * "goblin" exists. {@code TtsClient} keys the voice on kind, so a "goblin-2" would sound
     * like Vessk and still fail to open the lid. One goblin is what this build actually
     * supports, and it should say so instead of corrupting itself quietly.
     */
    public List<Diff> spawnGoblin(int x, int y) {
        var definition = content.entity("goblin");

        var existing = state().find(definition.id());
        if (existing.isPresent()) {
            throw new IllegalArgumentException(existing.get().isAlive()
                    ? existing.get().name() + " is already on the grid."
                    : existing.get().name() + " is dead. A spawn does not raise the dead.");
        }

        var goblin = definition.spawn(definition.id(), room().roomId(), x, y);
        log.append(new Event.EntitySpawned(Instant.now(), goblin));
        return List.of(new Diff.EntityAdded(goblin.toView()));
    }

    /** Where the goblin comes out of the sarcophagus, if the DM does not name a square. */
    public RoomDefinition.Point defaultGoblinSpawn() {
        return room().startPositions().goblinSpawn();
    }

    /**
     * Entering {@link Mode#COMBAT} goes through {@link CombatEngine#start} instead — a mode flag
     * without an initiative order is a UI that says COMBAT while nobody has a turn.
     */
    public List<Diff> setMode(Mode mode) {
        if (state().mode() == mode || mode == Mode.COMBAT) {
            return List.of();
        }
        log.append(new Event.ModeEntered(Instant.now(), mode));
        return List.of(new Diff.ModeChanged(mode));
    }

    /**
     * Rolls a skill check and logs it (invariant #6). The only path to one: the tool dispatcher
     * and the debug hook both come through here, so tuning the dice tray against a debug roll
     * tunes the real thing rather than a lookalike.
     */
    public RollResult rollCheck(String actorId, Skill skill, Difficulty difficulty) {
        var actor = state().find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));

        var result = dice.roll(
                RollRequest.skillCheck(actorId, skill, actor.skillModifier(skill), difficulty));
        log.append(new Event.CheckResolved(Instant.now(), actorId, Optional.of(skill),
                difficulty.dc(), result, result.outcome()));
        return result;
    }

    /**
     * How many checks have failed in a row, with no success since.
     *
     * <p>Handed to the DM as a fact so escalation cannot drift. Whether the model does anything
     * with it is its own decision; whether it is true is not. Reported rather than acted on here
     * — the engine has no opinion about what a stuck player deserves.
     */
    public int consecutiveFailedChecks() {
        return state().consecutiveFailedChecks();
    }

    public boolean isInBounds(int x, int y) {
        var room = room();
        return x >= 0 && x < room.width() && y >= 0 && y < room.height();
    }
}
