package dm.engine;

import dm.ai.BeatRenderer;
import dm.content.RoomDefinition;
import dm.model.Combatant;
import dm.model.CombatView;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Square;
import dm.state.EventLog;
import dm.state.WorldState;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Initiative, movement and one melee attack. That is the whole of combat in M0 (§4), and the
 * anti-goal in §12 — "do not build a rules engine" — is the reason there is no reach, no cover,
 * no opportunity attack and no condition anywhere below.
 *
 * <p>Every rule that decides what is <em>allowed</em> lives here and is shipped to the client as
 * a finished answer: {@link CombatView#legalMoves()} and {@link CombatView#legalTargets()}. The
 * client highlights squares; it never works out which ones.
 *
 * <p>Turn state is folded from the log rather than held in fields. A fight does not survive a
 * restart and is not meant to — M0 has no save (§12).
 */
public final class CombatEngine {

    private final EventLog log;
    private final DiceRoller dice;
    private final Supplier<RoomDefinition> currentRoom;
    private final Consumer<CombatSink> onFightStart;

    public CombatEngine(EventLog log, DiceRoller dice, Supplier<RoomDefinition> currentRoom) {
        this(log, dice, currentRoom, sink -> { });
    }

    public CombatEngine(EventLog log, DiceRoller dice, Supplier<RoomDefinition> currentRoom,
                        Consumer<CombatSink> onFightStart) {
        this.log = log;
        this.dice = dice;
        this.currentRoom = currentRoom;
        this.onFightStart = onFightStart;
    }

    /**
     * The room this fight is being fought in. A supplier rather than a value because the party
     * can now be somewhere else than where the engine was constructed, and terrain that lagged
     * behind would let a fighter walk through a pillar that is in a different room.
     */
    public RoomDefinition terrain() {
        return currentRoom.get();
    }

    WorldState state() {
        return log.state();
    }

    /** True while a fight is running. Folded, not held. */
    public boolean isActive() {
        return state().combat().isPresent();
    }

    /** Drop the fight without ending it in fiction. Only a new session should call this. */
    public void reset() {
        // Folded. {@link GameEngine#restart} clears the log, which drops the fight.
    }

    /** Null when no fight is running — the shape {@link Diff.CombatChanged} carries. */
    public CombatView view() {
        var combat = state().combat().orElse(null);
        if (combat == null) {
            return null;
        }
        var actor = activeEntity();
        return new CombatView(
                combat.order(),
                actor.map(Entity::id).orElse(""),
                combat.round(),
                combat.movementRemaining(),
                combat.actionAvailable(),
                actor.map(this::legalMoves).orElse(List.of()),
                actor.map(this::legalTargets).orElse(List.of()));
    }

    public String activeId() {
        return activeEntity().map(Entity::id).orElse("");
    }

    /** Whether the fight is waiting on a human. False also when no fight is running. */
    public boolean isPlayerTurn() {
        return activeEntity().map(Entity::isPlayerControlled).orElse(false);
    }

    // ---- Starting and ending ----

    /**
     * Rolls initiative for everything alive and puts the fight in motion.
     *
     * <p>Ties go to the higher DEX and then to the player, because a tie broken by map iteration
     * order is a tie broken by accident.
     */
    public void start(CombatSink sink) {
        // A fight with only one side in it would begin and never end: nothing would ever
        // satisfy isOver(), and the turn would sit on a combatant with nobody to attack.
        if (isActive() || isOver()) {
            return;
        }

        onFightStart.accept(sink);

        var rolled = new ArrayList<Combatant>();
        var initiativeRolls = new HashMap<String, RollResult>();
        for (var entity : livingEntities()) {
            RollResult result = dice.roll(
                    RollRequest.initiative(entity.id(), entity.initiativeModifier()));
            initiativeRolls.put(entity.id(), result);
            sink.roll(result);
            rolled.add(new Combatant(entity.id(), entity.name(), result.total(),
                    entity.isPlayerControlled()));
        }

        rolled.sort(Comparator
                .comparingInt(Combatant::initiative).reversed()
                .thenComparing(c -> -modifierOf(c.entityId()))
                .thenComparing(c -> c.isPlayerControlled() ? 0 : 1)
                .thenComparing(Combatant::entityId));

        var order = List.copyOf(rolled);
        log.append(new Event.CombatStarted(Instant.now(), order,
                rolled.stream().map(c -> initiativeRolls.get(c.entityId())).toList()));
        sink.diffs(List.of(new Diff.ModeChanged(Mode.COMBAT), new Diff.CombatChanged(view())));
    }

    private void end(CombatSink sink) {
        log.append(new Event.CombatEnded(Instant.now()));
        sink.diffs(List.of(new Diff.ModeChanged(Mode.EXPLORATION), new Diff.CombatChanged(null)));
    }

    /** True once one side has nobody left standing. */
    private boolean isOver() {
        boolean players = livingEntities().stream().anyMatch(Entity::isPlayerControlled);
        boolean hostiles = livingEntities().stream().anyMatch(e -> !e.isPlayerControlled());
        return !players || !hostiles;
    }

    // ---- The player's turn ----

    public void moveTo(String actorId, int x, int y, CombatSink sink) {
        var actor = requireActive(actorId);
        var destination = new Square(x, y);

        Map<Square, Integer> cost = reachable(actor);
        Integer spent = cost.get(destination);
        if (spent == null || spent > movementRemaining()) {
            throw new IllegalArgumentException(
                    actor.name() + " cannot reach (" + x + "," + y + ") this turn");
        }

        log.append(new Event.EntityMoved(Instant.now(), actorId,
                actor.x(), actor.y(), x, y, spent));

        sink.diffs(List.of(
                new Diff.EntityMoved(actorId, actor.x(), actor.y(), x, y),
                new Diff.CombatChanged(view())));
    }

    public void attack(String actorId, String targetId, CombatSink sink) {
        var actor = requireActive(actorId);

        if (!actionAvailable()) {
            throw new IllegalArgumentException(actor.name() + " has already acted this turn");
        }
        var target = state().find(targetId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + targetId));
        if (!legalTargets(actor).contains(targetId)) {
            throw new IllegalArgumentException(
                    target.name() + " is not in reach of " + actor.name());
        }

        resolveAttack(actor, target, sink);

        if (isOver()) {
            end(sink);
        } else {
            sink.diffs(List.of(new Diff.CombatChanged(view())));
        }
    }

    /**
     * The forty lines §12 allows. {@code d20 + toHit} against AC, natural 20 doubles the damage
     * dice, natural 1 misses regardless — no crit tables, no resistances, no fumble effects.
     *
     * <p>One event, whole. Split into a damage and a death, the beat handed to the narrator loses
     * its attacker — and m0-evaluation.md §4.4 is a record of what that costs: a killing blow
     * narrated backwards, with the player's hit points draining under a scene describing the
     * opposite.
     */
    private void resolveAttack(Entity actor, Entity target, CombatSink sink) {
        RollResult attack = dice.roll(
                RollRequest.attack(actor.id(), target.id(), actor.toHit(), target.ac()));
        sink.roll(attack);

        boolean missed = attack.outcome() == Outcome.MISS
                || attack.outcome() == Outcome.CRIT_FAIL;

        if (missed) {
            log.append(new Event.AttackResolved(Instant.now(), actor.id(), target.id(),
                    attack, Optional.empty(), 0, false, false));
            sink.beat(BeatRenderer.render(log.state(), lastAttack()));
            return;
        }

        String damageDice = attack.isCrit() ? doubled(actor.damageDice()) : actor.damageDice();
        RollResult damage = dice.roll(
                RollRequest.damage(actor.id(), damageDice, actor.damageModifier()));
        sink.roll(damage);

        var hurt = target.damaged(damage.total());

        log.append(new Event.AttackResolved(Instant.now(), actor.id(), target.id(),
                attack, Optional.of(damage), damage.total(), true, !hurt.isAlive()));

        sink.diffs(List.of(new Diff.StatChanged(target.id(), "hp", target.hp(), hurt.hp())));
        sink.beat(BeatRenderer.render(log.state(), lastAttack()));
    }

    /** The attack just appended. Rendered from the log so the beat and the board cannot disagree. */
    private Event.AttackResolved lastAttack() {
        var events = log.events();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof Event.AttackResolved attack) {
                return attack;
            }
        }
        throw new IllegalStateException("no attack to render");
    }

    /** {@code 1d8} becomes {@code 2d8}. The modifier is deliberately not doubled, as in 5e. */
    private static String doubled(String expression) {
        var expr = DiceExpr.parse(expression);
        return (expr.count() * 2) + "d" + expr.sides();
    }

    public void endTurn(String actorId, CombatSink sink) {
        requireActive(actorId);
        advanceTurn();
        sink.diffs(List.of(new Diff.CombatChanged(view())));
    }

    // ---- The goblin's turn ----

    /**
     * Runs every consecutive non-player turn, ending each one.
     *
     * <p>{@code beat} is the pause between a creature closing the distance and swinging. It is a
     * parameter because it is a directorial choice, not a rule: the caller that owns a socket
     * spends real time on it, and a test spends none.
     */
    public void runAutomaticTurns(CombatSink sink, Runnable beat) {
        int guard = 0;
        int roster = state().combat().map(c -> c.order().size()).orElse(0);
        while (isActive() && ++guard <= roster + 1) {
            var actor = activeEntity().orElse(null);
            if (actor == null || actor.isPlayerControlled()) {
                return;
            }
            GoblinAi.takeTurn(this, actor, sink, beat);
            if (isActive()) {
                advanceTurn();
                sink.diffs(List.of(new Diff.CombatChanged(view())));
            }
        }
    }

    /**
     * What {@link GoblinAi} is allowed to ask for. Package-private: the AI is not a client,
     * and it goes through the same legality checks the player's clicks do.
     *
     * @return the actor at its new square — entities are immutable, so the caller's copy is stale
     */
    Entity step(Entity actor, Square to, CombatSink sink) {
        moveTo(actor.id(), to.x(), to.y(), sink);
        return state().find(actor.id()).orElseThrow();
    }

    void swing(Entity actor, Entity target, CombatSink sink) {
        attack(actor.id(), target.id(), sink);
    }

    int movementRemaining() {
        return state().combat().map(c -> c.movementRemaining()).orElse(0);
    }

    boolean actionAvailable() {
        return state().combat().map(c -> c.actionAvailable()).orElse(false);
    }

    // ---- Turn bookkeeping ----

    private void advanceTurn() {
        var combat = state().combat().orElse(null);
        if (combat == null) {
            return;
        }
        var order = combat.order();
        int turnIndex = indexOf(order, combat.activeId());
        int round = combat.round();
        // Bounded: with everything dead the fight would already have ended.
        for (int i = 0; i < order.size(); i++) {
            turnIndex++;
            if (turnIndex >= order.size()) {
                turnIndex = 0;
                round++;
            }
            String nextId = order.get(turnIndex).entityId();
            if (state().find(nextId).filter(Entity::isAlive).isPresent()) {
                log.append(new Event.TurnAdvanced(Instant.now(), nextId, round));
                return;
            }
        }
    }

    private static int indexOf(List<Combatant> order, String entityId) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).entityId().equals(entityId)) {
                return i;
            }
        }
        return 0;
    }

    /** The combatant whose turn it is, or empty if that combatant has since died. */
    private Optional<Entity> activeEntity() {
        return state().combat()
                .flatMap(c -> state().find(c.activeId()))
                .filter(Entity::isAlive);
    }

    private Entity requireActive(String actorId) {
        var actor = activeEntity().orElseThrow(
                () -> new IllegalArgumentException("Nobody is taking a turn"));
        if (!actor.id().equals(actorId)) {
            throw new IllegalArgumentException("It is " + actor.name() + "'s turn, not " + actorId);
        }
        return actor;
    }

    private int modifierOf(String entityId) {
        return state().find(entityId).map(Entity::initiativeModifier).orElse(0);
    }

    // ---- Legality ----

    /** Everything adjacent, alive, and on the other side. */
    private List<String> legalTargets(Entity actor) {
        if (!actionAvailable()) {
            return List.of();
        }
        return livingEntities().stream()
                .filter(e -> e.isPlayerControlled() != actor.isPlayerControlled())
                .filter(actor::isAdjacentTo)
                .map(Entity::id)
                .toList();
    }

    private List<Square> legalMoves(Entity actor) {
        return reachable(actor).entrySet().stream()
                .filter(e -> e.getValue() > 0 && e.getValue() <= movementRemaining())
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingInt(Square::y).thenComparingInt(Square::x))
                .toList();
    }

    /**
     * Breadth-first over passable squares, out to the actor's full speed.
     *
     * <p>Eight neighbours at one square each: 5e's simplified diagonal, matching the Chebyshev
     * distance {@link Entity#isAdjacentTo} already uses. Two different distance metrics in one
     * combat system is how "why can it hit me from there" bugs start.
     */
    Map<Square, Integer> reachable(Entity actor) {
        var start = new Square(actor.x(), actor.y());
        var cost = new HashMap<Square, Integer>();
        var queue = new ArrayDeque<Square>();

        cost.put(start, 0);
        queue.add(start);

        var occupied = new HashSet<Square>();
        for (var other : livingEntities()) {
            if (!other.id().equals(actor.id())) {
                occupied.add(new Square(other.x(), other.y()));
            }
        }

        while (!queue.isEmpty()) {
            var square = queue.removeFirst();
            int here = cost.get(square);
            if (here >= actor.speedSquares()) {
                continue;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    var next = new Square(square.x() + dx, square.y() + dy);
                    if (cost.containsKey(next) || !isPassable(next, occupied)) {
                        continue;
                    }
                    cost.put(next, here + 1);
                    queue.add(next);
                }
            }
        }

        cost.remove(start);
        return cost;
    }

    private boolean isPassable(Square square, Set<Square> occupied) {
        var room = currentRoom.get();
        return square.x() >= 0 && square.x() < room.width()
                && square.y() >= 0 && square.y() < room.height()
                && !room.isObstructed(square.x(), square.y())
                && !occupied.contains(square);
    }

    /**
     * Sorted by id, not by whatever order the repository's map happens to hand back. Initiative
     * is rolled in this order, so an unsorted one would make {@code --demo} deal its scripted
     * faces to a different combatant on different runs.
     */
    List<Entity> livingEntities() {
        return state().entitiesHere().stream()
                .filter(Entity::isAlive)
                .sorted(Comparator.comparing(Entity::id))
                .toList();
    }
}
