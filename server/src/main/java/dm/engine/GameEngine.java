package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.Prop;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Skill;
import dm.model.Difficulty;
import dm.model.SceneState;
import dm.state.EventLog;
import dm.state.WorldState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative state. Applies actions and emits diffs; the client renders what it is told
 * and computes nothing (invariant #1).
 */
public final class GameEngine {

    private static final String ROOM_ID = "crypt";

    private final ContentLoader content;
    private final EventLog log;
    private final DiceRoller dice;
    private final RoomDefinition room;
    private final CombatEngine combat;

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice) {
        this(content, log, dice, content.room(ROOM_ID));
    }

    public GameEngine(ContentLoader content, EventLog log, DiceRoller dice,
                      RoomDefinition room) {
        this.content = content;
        this.log = log;
        this.dice = dice;
        this.room = room;
        this.combat = new CombatEngine(log, dice, room);
    }

    /** The log. The only way anything in this engine writes. */
    public EventLog log() {
        return log;
    }

    /** The world as it stands. A value — hold it for as long as you need one answer. */
    public WorldState state() {
        return log.state();
    }

    /** Spawn the party at the room's start positions. M0's party has exactly one member. */
    public void start() {
        var fighter = content.entity("fighter");
        var starts = room.startPositions().party();
        var members = new ArrayList<Entity>();

        for (int i = 0; i < starts.size(); i++) {
            var at = starts.get(i);
            // One member in M0, but the loop is the point — see invariant #2.
            String entityId = starts.size() == 1 ? fighter.id() : fighter.id() + "-" + i;
            members.add(fighter.spawn(entityId, at.x(), at.y()));
        }

        log.append(new Event.PartySpawned(Instant.now(), List.copyOf(members)));
        log.append(new Event.ModeEntered(Instant.now(), Mode.EXPLORATION));
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

    public RoomDefinition room() {
        return room;
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
        var revealed = state().revealedPropIds();

        List<Prop> visible = room.props().stream()
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .map(p -> new Prop(p.id(), p.type(), p.x(), p.y(), p.rotation(), false))
                .toList();

        var entities = state().entities().values().stream()
                .sorted(java.util.Comparator.comparing(Entity::id))
                .map(Entity::toView)
                .toList();

        return new SceneState(room.roomId(), room.width(), room.height(),
                room.floorType(), room.wallType(), visible, entities, room.lighting(),
                state().mode(), combat.view());
    }

    public Mode mode() {
        return state().mode();
    }

    // ---- Mutations. Each returns the diffs the client needs to catch up. ----

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
        if (room.isObstructed(x, y)) {
            throw new IllegalArgumentException("Something solid is already at " + x + "," + y);
        }
        boolean occupied = state().entities().values().stream()
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
        var definition = room.prop(propId);

        if (state().revealedPropIds().contains(propId)) {
            return List.of();
        }
        log.append(new Event.PropRevealed(Instant.now(), ROOM_ID, propId));
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

        var goblin = definition.spawn(definition.id(), x, y);
        log.append(new Event.EntitySpawned(Instant.now(), goblin));
        return List.of(new Diff.EntityAdded(goblin.toView()));
    }

    /** Where the goblin comes out of the sarcophagus, if the DM does not name a square. */
    public RoomDefinition.Point defaultGoblinSpawn() {
        return room.startPositions().goblinSpawn();
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
        return x >= 0 && x < room.width() && y >= 0 && y < room.height();
    }
}
