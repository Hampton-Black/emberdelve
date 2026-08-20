package dm.engine;

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
import dm.repo.GameRepository;

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

/**
 * Initiative, movement and one melee attack. That is the whole of combat in M0 (§4), and the
 * anti-goal in §12 — "do not build a rules engine" — is the reason there is no reach, no cover,
 * no opportunity attack and no condition anywhere below.
 *
 * <p>Every rule that decides what is <em>allowed</em> lives here and is shipped to the client as
 * a finished answer: {@link CombatView#legalMoves()} and {@link CombatView#legalTargets()}. The
 * client highlights squares; it never works out which ones.
 *
 * <p>Turn state is held in fields rather than in the repository. A fight does not survive a
 * restart and is not meant to — M0 has no save (§12).
 */
public final class CombatEngine {

    private final GameRepository repo;
    private final DiceRoller dice;
    private final RoomDefinition room;

    private List<Combatant> order = List.of();
    private int turnIndex;
    private int round;
    private int movementRemaining;
    private boolean actionAvailable;
    private boolean active;

    public CombatEngine(GameRepository repo, DiceRoller dice, RoomDefinition room) {
        this.repo = repo;
        this.dice = dice;
        this.room = room;
    }

    public boolean isActive() {
        return active;
    }

    /** Null when no fight is running — the shape {@link Diff.CombatChanged} carries. */
    public CombatView view() {
        if (!active) {
            return null;
        }
        var actor = activeEntity();
        return new CombatView(
                order,
                actor.map(Entity::id).orElse(""),
                round,
                movementRemaining,
                actionAvailable,
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
        if (active || isOver()) {
            return;
        }

        var rolled = new ArrayList<Combatant>();
        for (var entity : livingEntities()) {
            RollResult result = dice.roll(
                    RollRequest.initiative(entity.id(), entity.initiativeModifier()));
            repo.append(Event.roll(result));
            sink.roll(result);
            rolled.add(new Combatant(entity.id(), entity.name(), result.total(),
                    entity.isPlayerControlled()));
        }

        rolled.sort(Comparator
                .comparingInt(Combatant::initiative).reversed()
                .thenComparing(c -> -modifierOf(c.entityId()))
                .thenComparing(c -> c.isPlayerControlled() ? 0 : 1)
                .thenComparing(Combatant::entityId));

        order = List.copyOf(rolled);
        turnIndex = 0;
        round = 1;
        active = true;
        repo.setMode(Mode.COMBAT);
        beginTurn();

        repo.append(new Event.ModeEntered(Instant.now(), Mode.COMBAT));
        sink.diffs(List.of(new Diff.ModeChanged(Mode.COMBAT), new Diff.CombatChanged(view())));
    }

    private void end(CombatSink sink) {
        active = false;
        order = List.of();
        repo.setMode(Mode.EXPLORATION);
        repo.append(new Event.ModeEntered(Instant.now(), Mode.EXPLORATION));
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
        if (spent == null || spent > movementRemaining) {
            throw new IllegalArgumentException(
                    actor.name() + " cannot reach (" + x + "," + y + ") this turn");
        }

        movementRemaining -= spent;
        repo.put(actor.movedTo(x, y));
        repo.append(Event.action(actorId, "moved to " + x + "," + y));

        sink.diffs(List.of(
                new Diff.EntityMoved(actorId, actor.x(), actor.y(), x, y),
                new Diff.CombatChanged(view())));
    }

    public void attack(String actorId, String targetId, CombatSink sink) {
        var actor = requireActive(actorId);

        if (!actionAvailable) {
            throw new IllegalArgumentException(actor.name() + " has already acted this turn");
        }
        var target = repo.find(targetId).orElseThrow(
                () -> new IllegalArgumentException("No such entity: " + targetId));
        if (!legalTargets(actor).contains(targetId)) {
            throw new IllegalArgumentException(
                    target.name() + " is not in reach of " + actor.name());
        }

        actionAvailable = false;
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
     */
    private void resolveAttack(Entity actor, Entity target, CombatSink sink) {
        RollResult attack = dice.roll(
                RollRequest.attack(actor.id(), target.id(), actor.toHit(), target.ac()));
        repo.append(Event.roll(attack));
        sink.roll(attack);

        if (attack.outcome() == Outcome.MISS || attack.outcome() == Outcome.CRIT_FAIL) {
            repo.append(Event.action(actor.id(), "missed " + target.name()));
            sink.beat(attack.outcome() == Outcome.CRIT_FAIL
                    ? actor.name() + " swings at " + target.name() + " and fumbles badly."
                    : actor.name() + " swings at " + target.name() + " and misses.");
            return;
        }

        String damageDice = attack.isCrit() ? doubled(actor.damageDice()) : actor.damageDice();
        RollResult damage = dice.roll(
                RollRequest.damage(actor.id(), damageDice, actor.damageModifier()));
        repo.append(Event.roll(damage));
        sink.roll(damage);

        var hurt = target.damaged(damage.total());
        repo.put(hurt);
        repo.append(Event.action(actor.id(),
                "hit " + target.name() + " for " + damage.total()));

        sink.beat((attack.isCrit()
                ? "%s lands a critical hit on %s for %d damage."
                : "%s hits %s for %d damage.")
                .formatted(actor.name(), target.name(), damage.total()));

        var diffs = new ArrayList<Diff>();
        diffs.add(new Diff.StatChanged(target.id(), "hp", target.hp(), hurt.hp()));
        sink.diffs(diffs);

        if (!hurt.isAlive()) {
            repo.append(Event.action(target.id(), "died"));
            sink.beat(target.name() + " is killed by the blow.");
        } else {
            // The narrator is told the shape of the wound, not the arithmetic — dm.md forbids
            // stating hit points aloud, and a fraction invites the model to read it out.
            sink.beat("%s is now %s.".formatted(target.name(), condition(hurt)));
        }
    }

    /** How hurt something looks, for prose. Never a number: see the caller. */
    private static String condition(Entity entity) {
        double left = (double) entity.hp() / Math.max(entity.maxHp(), 1);
        if (left > 0.7) return "barely marked";
        if (left > 0.4) return "wounded";
        if (left > 0.15) return "badly hurt";
        return "on the edge of death";
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
        while (active && ++guard <= order.size() + 1) {
            var actor = activeEntity().orElse(null);
            if (actor == null || actor.isPlayerControlled()) {
                return;
            }
            GoblinAi.takeTurn(this, actor, sink, beat);
            if (active) {
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
        return repo.find(actor.id()).orElseThrow();
    }

    void swing(Entity actor, Entity target, CombatSink sink) {
        attack(actor.id(), target.id(), sink);
    }

    int movementRemaining() {
        return movementRemaining;
    }

    boolean actionAvailable() {
        return actionAvailable;
    }

    // ---- Turn bookkeeping ----

    private void beginTurn() {
        activeEntity().ifPresent(entity -> {
            movementRemaining = entity.speedSquares();
            actionAvailable = true;
        });
    }

    private void advanceTurn() {
        if (!active) {
            return;
        }
        // Bounded: with everything dead the fight would already have ended.
        for (int i = 0; i < order.size(); i++) {
            turnIndex++;
            if (turnIndex >= order.size()) {
                turnIndex = 0;
                round++;
            }
            if (activeEntity().isPresent()) {
                beginTurn();
                return;
            }
        }
    }

    /** The combatant whose turn it is, or empty if that combatant has since died. */
    private Optional<Entity> activeEntity() {
        if (!active || order.isEmpty()) {
            return Optional.empty();
        }
        return repo.find(order.get(turnIndex).entityId()).filter(Entity::isAlive);
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
        return repo.find(entityId).map(Entity::initiativeModifier).orElse(0);
    }

    // ---- Legality ----

    /** Everything adjacent, alive, and on the other side. */
    private List<String> legalTargets(Entity actor) {
        if (!actionAvailable) {
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
                .filter(e -> e.getValue() > 0 && e.getValue() <= movementRemaining)
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
        return repo.entities().stream()
                .filter(Entity::isAlive)
                .sorted(Comparator.comparing(Entity::id))
                .toList();
    }
}
