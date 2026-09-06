package dm.replay;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.model.Combatant;
import dm.model.Diff;
import dm.model.Event;
import dm.model.RollResult;
import dm.state.EventLog;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

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

        var log = new EventLog();
        var engine = new GameEngine(new ContentLoader(), log, dice);

        for (var event : recorded) {
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
                // TurnAdvanced names the incoming combatant. Ending that id would require
                // the engine to end a turn that has not started. End whoever is active now;
                // compare checks the event the engine emits.
                case Event.TurnAdvanced ignored -> {
                    if (engine.combat().isActive()) {
                        engine.combat().endTurn(engine.combat().activeId(), SILENT);
                    }
                }
                // Everything else is an input the engine does not act on, or an outcome the
                // engine produces for itself. Replaying either would double it.
                default -> { }
            }
        }

        return compare(recorded, log.events());
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
