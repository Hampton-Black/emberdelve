package dm.engine;

import dm.ai.BeatRenderer;
import dm.content.ContentLoader;
import dm.content.Dressings;
import dm.content.RoomDefinition;
import dm.model.ClockId;
import dm.model.Consumable;
import dm.model.ConsequenceId;
import dm.model.Diff;
import dm.model.Directive;
import dm.model.DirectiveRail;
import dm.model.Ending;
import dm.model.EndingReport;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Exit;
import dm.model.MarkerTag;
import dm.model.Mode;
import dm.model.ObjectiveFate;
import dm.model.PartyLight;
import dm.model.Appearances;
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
import java.util.UUID;

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
    private final ClockDraw draw;
    private final DirectiveRail directives;
    private List<Diff> pendingDiffs = List.of();

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice) {
        this(content, log, dice, Rooms.authored(content, "crypt"), ClockDraw.random());
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, RoomDefinition room) {
        this(content, log, dice, Rooms.of(room), ClockDraw.random());
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, Rooms rooms) {
        this(content, log, dice, rooms, ClockDraw.random());
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice, Rooms rooms,
                      ClockDraw draw) {
        this.content = content;
        this.log = log;
        this.dice = dice;
        this.rooms = rooms;
        this.draw = draw;
        if (!WorldState.EMPTY.roomId().equals(rooms.first().roomId())) {
            throw new IllegalArgumentException(
                    "The entrance must be '" + WorldState.EMPTY.roomId() + "' until the fold's "
                            + "starting room is configurable, got '" + rooms.first().roomId() + "'");
        }
        this.combat = new CombatEngine(log, dice, this::room, this::onFightStart,
                this::canRest, this::withCanRestIfChanged, this::endingReport);
        this.directives = new DirectiveRail(() -> this.combat.isActive());
    }

    public DirectiveRail directives() {
        return directives;
    }

    /**
     * Spend a torch: LIGHT returns to empty. Signs fire on crossing upward only, so this
     * must not announce a threshold on the way down. Hook for emberdelve-4h9.2 / 4h9.12.
     */
    public List<Diff> resetLight() {
        var before = partyLight();
        log.append(new Event.ClockTicked(Instant.now(), ClockId.LIGHT, 0));
        var after = partyLight();
        if (after == before) {
            return List.of();
        }
        return List.of(new Diff.PartyLightChanged(after));
    }

    /** Hook for emberdelve-4h9.11: a rest ticks both clocks. */
    public List<Diff> restTick() {
        var diffs = new ArrayList<Diff>();
        diffs.addAll(tickClock(ClockId.LIGHT));
        diffs.addAll(tickClock(ClockId.ALERT));
        return diffs;
    }

    /** Advance one clock one segment. Rest, crossings and a natural 1 all come through here. */
    public List<Diff> tickClock(ClockId id) {
        var clock = state().clock(id);
        int previous = clock.filled();
        if (id == ClockId.LIGHT && previous >= clock.segments()) {
            return List.of();
        }
        boolean torchBefore = id == ClockId.LIGHT && canSpendTorch();
        var lightBefore = id == ClockId.LIGHT ? partyLight() : null;
        int next = Math.min(previous + 1, clock.segments());
        log.append(new Event.ClockTicked(Instant.now(), id, next));
        var diffs = new ArrayList<Diff>();
        var signs = ClockTables.signs(id);
        for (int segment = previous + 1; segment <= next && segment < clock.segments(); segment++) {
            var sign = signs.get(segment);
            if (sign != null) {
                fire(id, sign);
            }
        }
        if (next >= clock.segments()) {
            diffs.addAll(fireFill(id));
        }
        if (id == ClockId.LIGHT && canSpendTorch() != torchBefore) {
            diffs.add(consumablesDiff());
        }
        if (id == ClockId.LIGHT) {
            var lightAfter = partyLight();
            if (lightAfter != lightBefore) {
                diffs.add(new Diff.PartyLightChanged(lightAfter));
            }
        }
        return diffs;
    }

    public List<Diff> takePendingDiffs() {
        var diffs = pendingDiffs;
        pendingDiffs = List.of();
        return diffs;
    }

    private void onFightStart(CombatSink sink) {
        sink.diffs(tickClock(ClockId.ALERT));
    }

    private List<Diff> fireFill(ClockId id) {
        var diffs = new ArrayList<Diff>();
        if (id == ClockId.LIGHT) {
            fire(id, ClockTables.LIGHT_FILL);
            diffs.addAll(tickClock(ClockId.ALERT));
            return diffs;
        }
        var options = alertFillOptions();
        var drawn = options.isEmpty()
                ? ConsequenceId.IT_PASSES_BY
                : draw.pick(options);
        log.append(new Event.ConsequenceFired(Instant.now(), id, drawn));
        diffs.addAll(moveFires());
        switch (drawn) {
            case SOMETHING_WANDERS_IN -> {
                var clause = ClockTables.clause(drawn);
                if (!clause.isBlank()) {
                    directives.latch(Directive.aboutRoom(state().roomId(), clause));
                }
                diffs.addAll(spawnGoblinHere());
            }
            case PATROL_ARRIVES -> {
                diffs.addAll(spawnGoblinHere());
                var buffer = new CombatSink.Buffer();
                combat.start(buffer);
                diffs.addAll(buffer.collectedDiffs());
            }
            case DRAWN_BY_THE_NOISE -> {
                boolean canRestBefore = canRest();
                hostileElsewhere().ifPresent(hostile -> {
                    var at = defaultGoblinSpawn();
                    log.append(new Event.EntityMoved(Instant.now(), hostile.id(),
                            hostile.x(), hostile.y(), at.x(), at.y(), 0));
                    state().find(hostile.id()).ifPresent(moved ->
                            diffs.add(new Diff.EntityAdded(moved.toView())));
                    var buffer = new CombatSink.Buffer();
                    combat.start(buffer);
                    diffs.addAll(buffer.collectedDiffs());
                });
                addCanRestIfChanged(diffs, canRestBefore);
            }
            default -> { }
        }
        return diffs;
    }

    private List<ConsequenceId> alertFillOptions() {
        var options = new ArrayList<>(ClockTables.ALERT_FILL);
        if (kindHere("goblin", true)) {
            options.remove(ConsequenceId.PATROL_ARRIVES);
            options.remove(ConsequenceId.SOMETHING_WANDERS_IN);
        }
        if (hostileElsewhere().isEmpty()) {
            options.remove(ConsequenceId.DRAWN_BY_THE_NOISE);
        }
        if (options.isEmpty()) {
            options.add(ConsequenceId.IT_PASSES_BY);
        }
        return options;
    }

    private Optional<Entity> hostileElsewhere() {
        return state().living().stream()
                .filter(e -> !e.isPlayerControlled())
                .filter(e -> !e.roomId().equals(state().roomId()))
                .findFirst();
    }

    private List<Diff> spawnGoblinHere() {
        var at = defaultGoblinSpawn();
        return spawnGoblin(at.x(), at.y());
    }

    private List<Diff> moveFires() {
        var structure = rooms.structure(state().roomId());
        if (structure.fires() == null) {
            return List.of();
        }
        if (state().lightingIn(structure.roomId()).isPresent()) {
            return List.of();
        }
        var from = structure.lighting();
        var to = structure.fires().to();
        log.append(new Event.RoomLightingChanged(Instant.now(), structure.roomId(), from, to));
        var moved = structure.fires().moved();
        if (moved != null && !moved.isBlank()) {
            directives.latch(Directive.aboutRoom(structure.roomId(), moved));
        }
        return List.of(new Diff.RoomLightingChanged(structure.roomId(), to));
    }

    private void fire(ClockId clock, ConsequenceId id) {
        log.append(new Event.ConsequenceFired(Instant.now(), clock, id));
        var clause = id == ConsequenceId.LIGHT_OUT
                ? ClockTables.lightOutClause(state().consumableCount(Consumable.TORCH))
                : ClockTables.clause(id);
        if (!clause.isBlank()) {
            directives.latch(Directive.aboutParty(clause));
        }
    }

    /** Folded from LIGHT filled. Never stored. Spec §7a. */
    private PartyLight partyLight() {
        return PartyLight.of(state().clock(ClockId.LIGHT).filled());
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
        log.append(new Event.ConsumablesGranted(Instant.now(),
                WorldState.STARTING_CONSUMABLES));
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
                combat.view(), state().consumableCount(Consumable.POTION),
                state().consumableCount(Consumable.TORCH),
                state().consumableCount(Consumable.ROPE), canSpendTorch(), canRest(),
                partyLight(), state().holdingObjective(),
                state().ending().isPresent() ? endingReport() : null,
                state().markersHere().stream().map(dm.model.Marker::toView).toList());
    }

    /**
     * Sentence parts and ledger for the current ending. Folded from the log; never a second
     * write path. Spec §9, §10.
     */
    EndingReport endingReport() {
        var ending = state().ending().orElseThrow();
        var names = state().party().stream()
                .map(member -> state().find(member.entityId()))
                .flatMap(Optional::stream)
                .map(Entity::name)
                .toList();
        int potionsBrought = 0;
        int torchesBrought = 0;
        int potionsUsed = 0;
        int torchesUsed = 0;
        int fights = 0;
        for (var event : log.events()) {
            switch (event) {
                case Event.ConsumablesGranted granted -> {
                    potionsBrought += granted.counts().getOrDefault(Consumable.POTION, 0);
                    torchesBrought += granted.counts().getOrDefault(Consumable.TORCH, 0);
                }
                case Event.ItemUsed used when used.item() == Consumable.POTION -> potionsUsed++;
                case Event.ItemUsed used when used.item() == Consumable.TORCH -> torchesUsed++;
                case Event.CombatStarted ignored -> fights++;
                default -> { }
            }
        }
        String hurt = null;
        if (ending != Ending.PARTY_LOST) {
            hurt = state().party().stream()
                    .map(member -> state().find(member.entityId()))
                    .flatMap(Optional::stream)
                    .filter(Entity::isAlive)
                    .findFirst()
                    .map(BeatRenderer::condition)
                    .orElse(null);
        }
        var fate = switch (ending) {
            case EXTRACTED_WITH_OBJECTIVE -> ObjectiveFate.CARRIED_OUT;
            case EXTRACTED_WITHOUT -> ObjectiveFate.LEFT_WHERE_IT_LAY;
            case PARTY_LOST -> state().holdingObjective()
                    ? ObjectiveFate.FELL_WITH_HIM
                    : ObjectiveFate.LEFT_WHERE_IT_LAY;
        };
        return new EndingReport(ending, names, room().name(), EndingReport.OBJECTIVE_NAME,
                state().visitedRoomIds().size(), rooms.size(),
                potionsUsed, potionsBrought, torchesUsed, torchesBrought, fights, fate, hurt);
    }

    /**
     * Take a takeable prop. The click and {@code take_prop} both come through here.
     * Spec §4c, §8f.
     */
    public List<Diff> takeProp(String propId) {
        requireOpen();
        if (combat.isActive()) {
            throw new IllegalArgumentException("You cannot take that in the middle of a fight.");
        }
        var definition = room().prop(propId);
        var prop = definition.toProp();
        if (prop.hidden() && !state().revealedHere().contains(propId)) {
            throw new IllegalArgumentException("'" + propId + "' is not visible");
        }
        if (!prop.actions().contains("take")) {
            throw new IllegalArgumentException("'" + propId + "' cannot be taken");
        }
        if (state().takenHere().contains(propId)) {
            throw new IllegalArgumentException("'" + propId + "' has already been taken");
        }
        boolean objective = EndingReport.OBJECTIVE_NAME.equals(propId);
        if (objective) {
            log.append(new Event.ObjectiveTaken(Instant.now(), state().roomId(), propId));
        } else {
            log.append(new Event.PropTaken(Instant.now(), state().roomId(), propId));
        }
        return List.of(new Diff.PropRemoved(propId, state().holdingObjective()));
    }

    /** Exploration-only; no living hostile in this room. Spec §5b. */
    public boolean canRest() {
        if (combat.isActive()) {
            return false;
        }
        return state().entitiesHere().stream()
                .noneMatch(e -> e.isAlive() && !e.isPlayerControlled());
    }

    private List<Diff> withCanRestIfChanged(List<Diff> diffs, boolean canRestBefore) {
        if (canRest() == canRestBefore) {
            return diffs;
        }
        var out = new ArrayList<>(diffs);
        out.add(new Diff.CanRestChanged(canRest()));
        return out;
    }

    private void addCanRestIfChanged(List<Diff> diffs, boolean canRestBefore) {
        if (canRest() != canRestBefore) {
            diffs.add(new Diff.CanRestChanged(canRest()));
        }
    }

    private boolean canSpendTorch() {
        return state().mode() != Mode.COMBAT
                && state().consumableCount(Consumable.TORCH) > 0
                && state().clock(ClockId.LIGHT).filled() > 0;
    }

    private Diff consumablesDiff() {
        return new Diff.ConsumablesChanged(
                state().consumableCount(Consumable.POTION),
                state().consumableCount(Consumable.TORCH),
                state().consumableCount(Consumable.ROPE),
                canSpendTorch());
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
                structure.wallType(),
                state().lightingIn(roomId).orElse(structure.lighting()),
                props, structure.exits(),
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

    /**
     * Whether a solid prop on the authored floor plan still blocks this square.
     * Taken props are gone from play even though the authored list still lists them.
     */
    static boolean obstructs(RoomDefinition room, Set<String> taken, int x, int y) {
        return room.props().stream()
                .anyMatch(p -> p.x() == x && p.y() == y
                        && p.type().blocksMovement()
                        && !taken.contains(p.id()));
    }

    /** A room's props as the client may see them: hidden ones withheld unless revealed there. */
    private List<Prop> visiblePropsOf(RoomDefinition def, Set<String> revealed) {
        var taken = state().takenIn(def.roomId());
        return def.props().stream()
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .filter(p -> !taken.contains(p.id()))
                .map(p -> new Prop(p.id(), p.type(), p.x(), p.y(), p.rotation(), false, p.actions(),
                        Appearances.orDefault(p.type(), p.appearance())))
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
        requireOpen();
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

        if (exit.wayOut()) {
            var ending = state().holdingObjective()
                    ? dm.model.Ending.EXTRACTED_WITH_OBJECTIVE
                    : dm.model.Ending.EXTRACTED_WITHOUT;
            log.append(new Event.DelveEnded(Instant.now(), ending, exitId));
            directives.clear();
            return List.of(new Diff.DelveEnded(endingReport()));
        }

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

        var diffs = new ArrayList<Diff>();
        diffs.addAll(tickClock(ClockId.LIGHT));
        diffs.addAll(tickClock(ClockId.ALERT));
        // A room change replaces everything, which is what a fresh Scene is for. The caller
        // sends one; clock diffs still land in the log and on a Scene that roomView() reads.
        return diffs;
    }

    /**
     * In combat this is the same call the goblin makes, spending the same movement and checked
     * against the same reachable set. Out of combat there is nothing to spend, so a move is
     * whatever the player clicked.
     */
    public void moveTo(String actorId, int x, int y, CombatSink sink) {
        requireOpen();
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
        if (obstructs(room(), state().takenHere(), x, y)) {
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
        return revealProp(propId, true);
    }

    /**
     * {@code releaseContained} is the live path. Replay applies {@link Event.PropRevealed}
     * without minting a creature — {@link Event.EntitySpawned} already named who came out.
     */
    public List<Diff> revealProp(String propId, boolean releaseContained) {
        requireOpen();
        var definition = room().prop(propId);
        var diffs = new ArrayList<Diff>();
        if (!state().revealedHere().contains(propId)) {
            log.append(new Event.PropRevealed(Instant.now(), state().roomId(), propId));
            diffs.add(new Diff.PropRevealed(definition.toProp().revealed()));
        }
        if (releaseContained) {
            diffs.addAll(spawnContained(definition));
        }
        return diffs;
    }

    /**
     * A reconcile-placed glyph. Invalid tag and out-of-bounds are the dispatcher's to refuse;
     * this still bounds-checks so a click path cannot invent a square.
     */
    public List<Diff> placeMarker(MarkerTag tag, int x, int y, String text) {
        requireOpen();
        if (!isInBounds(x, y)) {
            throw new IllegalArgumentException("(" + x + "," + y + ") is off the grid");
        }
        String id = "marker-" + UUID.randomUUID().toString().substring(0, 8);
        log.append(new Event.MarkerPlaced(Instant.now(), id, state().roomId(), tag, x, y, text));
        return List.of(new Diff.MarkerPlaced(new dm.model.MarkerView(id, tag, x, y)));
    }

    /**
     * The player pointed at a marker. Records which one; the next prompt carries tag + text,
     * never a grid coordinate.
     */
    public dm.model.Marker inspectMarker(String markerId) {
        requireOpen();
        var marker = state().markersHere().stream()
                .filter(m -> m.id().equals(markerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "there is no marker '" + markerId + "' here"));
        log.append(new Event.MarkerInspected(Instant.now(), markerId));
        return marker;
    }

    /**
     * Puts a hostile on the grid under a unique id ({@code goblin}, {@code goblin-2},
     * {@code brute}, …). Kind, not id, keys voice and mesh. A dead occupant of {@code goblin}
     * stays dead; the next spawn is a different creature.
     */
    public List<Diff> spawnGoblin(int x, int y) {
        return spawnHostile("goblin", x, y);
    }

    public List<Diff> spawnHostile(String kind, int x, int y) {
        requireOpen();
        boolean canRestBefore = canRest();
        var definition = content.entity(kind);
        var spawned = definition.spawn(nextEntityId(kind), room().roomId(), x, y);
        log.append(new Event.EntitySpawned(Instant.now(), spawned));
        var diffs = new ArrayList<Diff>();
        diffs.add(new Diff.EntityAdded(spawned.toView()));
        addCanRestIfChanged(diffs, canRestBefore);
        return diffs;
    }

    /**
     * Replay path: the log already named the creature. Do not mint a fresh goblin from
     * content — that would ignore a recorded brute and restat an old fight.
     */
    public List<Diff> spawnRecorded(Entity entity) {
        requireOpen();
        boolean canRestBefore = canRest();
        log.append(new Event.EntitySpawned(Instant.now(), entity));
        var diffs = new ArrayList<Diff>();
        diffs.add(new Diff.EntityAdded(entity.toView()));
        addCanRestIfChanged(diffs, canRestBefore);
        return diffs;
    }

    /**
     * Debug start-combat's pack: two goblins and a brute, so a fight has composition with
     * no model in the path. Leaves an already-present hostile alone.
     */
    public List<Diff> ensureDebugHostiles() {
        requireOpen();
        boolean hostileHere = state().entitiesHere().stream()
                .anyMatch(e -> e.isAlive() && !e.isPlayerControlled());
        if (hostileHere) {
            return List.of();
        }
        var diffs = new ArrayList<Diff>();
        diffs.addAll(spawnHostile("goblin", openSquare().x(), openSquare().y()));
        diffs.addAll(spawnHostile("goblin", openSquare().x(), openSquare().y()));
        diffs.addAll(spawnHostile("brute", openSquare().x(), openSquare().y()));
        return diffs;
    }

    private List<Diff> spawnContained(RoomDefinition.PropDefinition prop) {
        var kind = prop.contains();
        if (kind == null || kind.isBlank() || kindHere(kind, false)) {
            return List.of();
        }
        var at = defaultGoblinSpawn();
        if (!squareFree(at.x(), at.y())) {
            at = openSquare();
        }
        return spawnHostile(kind, at.x(), at.y());
    }

    private boolean kindHere(String kind, boolean livingOnly) {
        return state().entitiesHere().stream()
                .anyMatch(e -> kind.equals(e.kind()) && (!livingOnly || e.isAlive()));
    }

    private String nextEntityId(String kind) {
        if (state().find(kind).isEmpty()) {
            return kind;
        }
        int n = 2;
        while (state().find(kind + "-" + n).isPresent()) {
            n++;
        }
        return kind + "-" + n;
    }

    private RoomDefinition.Point openSquare() {
        var prefer = defaultGoblinSpawn();
        int width = room().width();
        int height = room().height();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = (prefer.x() + x) % width;
                int py = (prefer.y() + y) % height;
                if (squareFree(px, py)) {
                    return new RoomDefinition.Point(px, py);
                }
            }
        }
        throw new IllegalStateException("no open square to spawn on");
    }

    private boolean squareFree(int x, int y) {
        if (!isInBounds(x, y) || obstructs(room(), state().takenHere(), x, y)) {
            return false;
        }
        return state().entitiesHere().stream()
                .noneMatch(e -> e.isAlive() && e.x() == x && e.y() == y);
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
        requireOpen();
        var actor = state().find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));

        var result = dice.roll(
                RollRequest.skillCheck(actorId, skill, actor.skillModifier(skill), difficulty));
        log.append(new Event.CheckResolved(Instant.now(), actorId, Optional.of(skill),
                difficulty.dc(), result, result.outcome()));
        pendingDiffs = result.natural() == 1 ? tickClock(ClockId.LIGHT) : List.of();
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

    private void requireOpen() {
        if (state().ending().isPresent()) {
            throw new IllegalArgumentException("The delve is over.");
        }
    }

    /**
     * Spend a potion or a torch. Exploration-only; the client may also send {@code useItem}
     * with no model in the path. Spec §5b.
     */
    public List<Diff> useItem(String actorId, Consumable item) {
        requireOpen();
        if (combat.isActive()) {
            throw new IllegalArgumentException("You cannot use that in the middle of a fight.");
        }
        if (item == Consumable.ROPE) {
            throw new IllegalArgumentException("Rope has no use yet.");
        }
        int count = state().consumableCount(item);
        if (count <= 0) {
            throw new IllegalArgumentException("You have no " + item.name().toLowerCase() + " left.");
        }
        if (item == Consumable.TORCH && state().clock(ClockId.LIGHT).filled() == 0) {
            throw new IllegalArgumentException(
                    "The torch is already full — spending one would reset nothing.");
        }
        var actor = state().find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));
        if (!actor.isAlive()) {
            throw new IllegalArgumentException(actor.name() + " is dead.");
        }

        int hpFrom = actor.hp();
        int remaining = count - 1;
        log.append(new Event.ItemUsed(Instant.now(), actorId, item, remaining));

        var diffs = new ArrayList<Diff>();
        if (item == Consumable.POTION) {
            int hpTo = state().find(actorId).orElseThrow().hp();
            diffs.add(new Diff.StatChanged(actorId, "hp", hpFrom, hpTo));
        }
        if (item == Consumable.TORCH) {
            diffs.addAll(resetLight());
            directives.latch(Directive.aboutParty(ClockTables.TORCH_RELIT));
        }
        diffs.add(consumablesDiff());
        return diffs;
    }

    /**
     * Catch breath: four hit points, both clocks tick once. Exploration-only; refused with a
     * living hostile in the room, including WARY. Spec §5b.
     */
    public List<Diff> rest(String actorId) {
        requireOpen();
        if (combat.isActive()) {
            throw new IllegalArgumentException("You cannot rest in the middle of a fight.");
        }
        boolean hostileHere = state().entitiesHere().stream()
                .anyMatch(e -> e.isAlive() && !e.isPlayerControlled());
        if (hostileHere) {
            throw new IllegalArgumentException(
                    "You cannot rest while something hostile is in the room.");
        }
        var actor = state().find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));
        if (!actor.isAlive()) {
            throw new IllegalArgumentException(actor.name() + " is dead.");
        }

        int hpFrom = actor.hp();
        int hpTo = Math.min(actor.maxHp(), hpFrom + 4);
        log.append(new Event.Rested(Instant.now(), actorId, hpTo));
        directives.latch(Directive.aboutRoom(state().roomId(), ClockTables.REST));

        var diffs = new ArrayList<Diff>();
        if (hpTo != hpFrom) {
            diffs.add(new Diff.StatChanged(actorId, "hp", hpFrom, hpTo));
        }
        diffs.addAll(restTick());
        return diffs;
    }
}
