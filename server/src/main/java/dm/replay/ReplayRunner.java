package dm.replay;

import dm.content.ContentLoader;
import dm.engine.ClockDraw;
import dm.engine.ClockTables;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedClockDraw;
import dm.model.ClockId;
import dm.model.Combatant;
import dm.model.Consumable;
import dm.model.ConsequenceId;
import dm.model.Diff;
import dm.model.Event;
import dm.model.RollResult;
import dm.state.EventLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-executes a recorded session against a fresh engine and compares what came out.
 *
 * <p>The model is not stubbed so much as absent: its decisions are already in the file, as the
 * inputs it produced. What is under test is everything downstream of them.
 */
public final class ReplayRunner {

    public record Result(boolean matched, int compared, String firstDivergence) {
    }

    private static final CombatSink SILENT = new CombatSink() {
        @Override public void diffs(List<Diff> diffs) { }
        @Override public void roll(RollResult result) { }
        @Override public void beat(String text) { }
    };

    private ReplayRunner() {
    }

    public static Result replay(Path jsonl) {
        var recorded = EventLog.load(jsonl).events();

        var dice = new ReplayDiceRoller(recorded.stream()
                .flatMap(ReplayRunner::rollsIn)
                .toList());

        var content = new ContentLoader();
        var log = new EventLog();
        var engine = new GameEngine(content, log, dice, roomsIn(content, recorded),
                drawIn(recorded));

        var skipBundle = new HashSet<Class<? extends Event>>();
        for (var event : recorded) {
            if (event instanceof Event.ConsequenceFired fired) {
                skipBundle = new HashSet<>(bundleInputs(fired.id()));
            }
            if (!skipBundle.isEmpty() && skipBundle.remove(event.getClass())) {
                continue;
            }
            switch (event) {
                case Event.PartySpawned ignored -> engine.start();
                case Event.EntitySpawned e -> engine.spawnGoblin(e.entity().x(), e.entity().y());
                case Event.PropRevealed e -> engine.revealProp(e.propId());
                case Event.CombatStarted ignored -> engine.combat().start(SILENT);
                case Event.EntityMoved e -> move(engine, e);
                case Event.AttackResolved e ->
                        engine.combat().attack(e.actorId(), e.targetId(), SILENT);
                case Event.CheckResolved e ->
                        engine.rollCheck(e.actorId(), e.skill().orElseThrow(),
                                dm.model.Difficulty.ofDc(e.dc()));
                case Event.ItemUsed e -> engine.useItem(e.actorId(), e.item());
                // TurnAdvanced names the incoming combatant. Ending that id would require
                // the engine to end a turn that has not started. End whoever is active now;
                // compare checks the event the engine emits.
                case Event.TurnAdvanced ignored -> {
                    if (engine.combat().isActive()) {
                        engine.combat().endTurn(engine.combat().activeId(), SILENT);
                    }
                }
                // The exit id is recorded, so replay walks the same door rather than inferring
                // one from the destination — a room with two ways into it would otherwise be a
                // coin flip that diverges on the landing square.
                case Event.PartyMoved e -> engine.crossExit(e.throughExitId());
                // Everything else is an input the engine does not act on, or an outcome the
                // engine produces for itself. Replaying either would double it.
                default -> { }
            }
        }

        return compare(recorded, log.events());
    }

    /**
     * The rooms a recorded session visited, entrance first.
     *
     * <p>Read out of the log rather than passed in, so replaying a file needs nothing but the
     * file. {@code RoomDressed} is emitted on first entry to every room, in entry order, which
     * makes it the one event that names them all in the right order.
     */
    /** Inputs the trigger already re-emitted when this consequence's fill fired (ADR-0012). */
    private static Set<Class<? extends Event>> bundleInputs(ConsequenceId id) {
        if (ClockTables.bundleIsEmpty(id)) {
            return Set.of();
        }
        return switch (id) {
            case SOMETHING_WANDERS_IN -> Set.of(Event.EntitySpawned.class);
            case PATROL_ARRIVES -> Set.of(Event.EntitySpawned.class, Event.CombatStarted.class);
            case DRAWN_BY_THE_NOISE -> Set.of(Event.EntityMoved.class, Event.CombatStarted.class);
            default -> Set.of();
        };
    }

    private static Rooms roomsIn(ContentLoader content, List<Event> recorded) {
        var ids = recorded.stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .map(Event.RoomDressed::roomId)
                .distinct()
                .toList();
        return ids.isEmpty()
                ? Rooms.authored(content, "crypt")
                : Rooms.authored(content, ids.toArray(String[]::new));
    }

    /**
     * Reconstruct the ALERT fill draws from recorded {@code ConsequenceFired} ids, so a replay
     * that fills ALERT does not consume scripted combat faces and does not re-roll the table.
     */
    private static ClockDraw drawIn(List<Event> recorded) {
        var fills = recorded.stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .filter(e -> e.clock() == ClockId.ALERT)
                .map(Event.ConsequenceFired::id)
                .filter(ClockTables.ALERT_FILL::contains)
                .toList();
        return fills.isEmpty() ? ClockDraw.random() : new ScriptedClockDraw(fills);
    }

    private static void move(GameEngine engine, Event.EntityMoved e) {
        if (engine.state().combat().isPresent()) {
            engine.combat().moveTo(e.entityId(), e.x(), e.y(), SILENT);
        } else {
            engine.moveTo(e.entityId(), e.x(), e.y(), SILENT);
        }
    }

    private static java.util.stream.Stream<RollResult> rollsIn(Event event) {
        return switch (event) {
            case Event.AttackResolved e -> java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(e.attack()), e.damage().stream());
            case Event.CheckResolved e -> java.util.stream.Stream.of(e.roll());
            case Event.CombatStarted e -> initiativeInAskOrder(e);
            default -> java.util.stream.Stream.empty();
        };
    }

    /**
     * The log stores initiative in turn order; the engine asks in livingEntities() order
     * (entity id). A goblin-first fight must not falsely diverge.
     */
    private static java.util.stream.Stream<RollResult> initiativeInAskOrder(Event.CombatStarted e) {
        var byId = new HashMap<String, RollResult>();
        for (int i = 0; i < e.order().size(); i++) {
            byId.put(e.order().get(i).entityId(), e.initiative().get(i));
        }
        return e.order().stream()
                .sorted(Comparator.comparing(Combatant::entityId))
                .map(c -> byId.get(c.entityId()));
    }

    /**
     * Compares the two streams, ignoring timestamps and the input events the engine never
     * re-emits. A divergence is reported by index and by both sides, because "they differ" is
     * not a debugging aid.
     */
    private static Result compare(List<Event> recorded, List<Event> produced) {
        var expected = skipInputs(recorded);
        var actual = skipInputs(produced);

        int limit = Math.min(expected.size(), actual.size());
        for (int i = 0; i < limit; i++) {
            if (!sameFact(expected.get(i), actual.get(i))) {
                return new Result(false, i,
                        "index %d:%n  recorded %s%n  produced %s"
                                .formatted(i, expected.get(i), actual.get(i)));
            }
        }
        if (expected.size() != actual.size()) {
            return new Result(false, limit,
                    "the session recorded %d events and the replay produced %d"
                            .formatted(expected.size(), actual.size()));
        }
        return new Result(true, expected.size(), null);
    }

    /**
     * RoomDressed is produced by {@code start()} now. Skipping it only on the recorded side
     * makes a replay diverge the moment the engine writes one.
     */
    private static List<Event> skipInputs(List<Event> events) {
        var kept = new ArrayList<Event>();
        for (var event : events) {
            switch (event) {
                case Event.SessionStarted ignored -> { }
                case Event.PlayerSaid ignored -> { }
                case Event.ToolCallIssued ignored -> { }
                case Event.NarrationLogged ignored -> { }
                case Event.RoomDressed ignored -> { }
                case Event.ConsumablesGranted ignored -> { }
                case Event.FactAsserted ignored -> { }
                default -> kept.add(event);
            }
        }
        return kept;
    }

    /** Equality with the clock taken out of it: two runs never share a timestamp. */
    private static boolean sameFact(Event a, Event b) {
        return a.getClass() == b.getClass() && a.toString().replaceAll("at=[^,)]+", "")
                .equals(b.toString().replaceAll("at=[^,)]+", ""));
    }
}
