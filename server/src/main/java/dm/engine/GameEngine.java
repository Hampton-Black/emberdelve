package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.Outcome;
import dm.model.PartyMember;
import dm.model.Prop;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Skill;
import dm.model.Difficulty;
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
    private final CombatEngine combat;

    /** See {@link #consecutiveFailedChecks()}. Session state, like the fight's turn order. */
    private int consecutiveFailedChecks;

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice) {
        this(content, repo, dice, content.room(ROOM_ID));
    }

    public GameEngine(ContentLoader content, GameRepository repo, DiceRoller dice,
                      RoomDefinition room) {
        this.content = content;
        this.repo = repo;
        this.dice = dice;
        this.room = room;
        this.combat = new CombatEngine(repo, dice, room);
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
        repo.clear();
        consecutiveFailedChecks = 0;
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

    public GameRepository repo() {
        return repo;
    }

    public CombatEngine combat() {
        return combat;
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
                room.floorType(), room.wallType(), visible, entities, room.lighting(),
                repo.mode(), combat.view());
    }

    public Mode mode() {
        return repo.mode();
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

        var entity = repo.find(actorId).orElseThrow(
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
        boolean occupied = repo.entities().stream()
                .anyMatch(e -> e.isAlive() && !e.id().equals(actorId) && e.x() == x && e.y() == y);
        if (occupied) {
            throw new IllegalArgumentException("Someone is standing at " + x + "," + y);
        }

        repo.put(entity.movedTo(x, y));
        repo.append(Event.action(actorId, "moved to " + x + "," + y));
        sink.diffs(List.of(new Diff.EntityMoved(actorId, entity.x(), entity.y(), x, y)));
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

    /**
     * Puts the goblin on the grid. There is room for exactly one.
     *
     * <p>The entity id is the definition's, which is the constant {@code "goblin"}, and
     * {@code repo.put} is keyed by id — so a second spawn never added a second creature, it
     * overwrote the first. Overwriting a <em>dead</em> one is a resurrection: back at full hp,
     * back on the board, and rolling initiative in the next fight as though nothing had
     * happened. Vessk came back from a fight he had lost.
     *
     * <p>Refusing is the honest fix rather than the convenient one. Handing each spawn its own
     * id would let a second goblin exist, but the id is load-bearing in two places outside the
     * engine — {@code TtsClient} picks the goblin's voice by it, and {@code DmService} decides
     * which sarcophagus note to show by whether an entity called "goblin" exists — so a
     * "goblin-2" would be narrated in the narrator's voice. One goblin is what this build
     * actually supports, and it should say so instead of corrupting itself quietly.
     */
    public List<Diff> spawnGoblin(int x, int y) {
        var definition = content.entity("goblin");

        var existing = repo.find(definition.id());
        if (existing.isPresent()) {
            throw new IllegalArgumentException(existing.get().isAlive()
                    ? existing.get().name() + " is already on the grid."
                    : existing.get().name() + " is dead. A spawn does not raise the dead.");
        }

        var goblin = definition.spawn(definition.id(), x, y);
        repo.put(goblin);
        repo.append(Event.action(goblin.id(), "appeared at " + x + "," + y));
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
        if (repo.mode() == mode || mode == Mode.COMBAT) {
            return List.of();
        }
        repo.setMode(mode);
        repo.append(new Event.ModeEntered(Instant.now(), mode));
        return List.of(new Diff.ModeChanged(mode));
    }

    /**
     * Rolls a skill check and logs it (invariant #6). The only path to one: the tool dispatcher
     * and the debug hook both come through here, so tuning the dice tray against a debug roll
     * tunes the real thing rather than a lookalike.
     */
    public RollResult rollCheck(String actorId, Skill skill, Difficulty difficulty) {
        var actor = repo.find(actorId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + actorId));

        var result = dice.roll(
                RollRequest.skillCheck(actorId, skill, actor.skillModifier(skill), difficulty));
        repo.append(Event.roll(result));

        // Deliberately not keyed by skill. Shoving the lid, failing, then searching the wall and
        // failing again is not two unrelated attempts — it is a player who is stuck, and being
        // stuck is the thing worth reacting to.
        if (result.outcome() == Outcome.SUCCESS || result.outcome() == Outcome.CRIT) {
            consecutiveFailedChecks = 0;
        } else {
            consecutiveFailedChecks++;
        }
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
        return consecutiveFailedChecks;
    }

    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < room.width() && y >= 0 && y < room.height();
    }
}
