package dm.engine;

import dm.ai.DmService;
import dm.engine.ClockTables;
import dm.ai.ToolDispatcher;
import dm.ai.ToolSchema;
import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.ConsequenceId;
import dm.model.Diff;
import dm.model.Directive;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.11: rest restores four hit points and ticks both clocks.
 */
class RestTest {

    private EventLog log;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        log = new EventLog();
        engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());
        engine.start();
    }

    private <T extends Event> List<T> eventsOf(Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void damageFighterTo(int hp) {
        int damage = engine.state().find("fighter").orElseThrow().hp() - hp;
        var attackRoll = new dm.model.RollResult(
                dm.model.RollRequest.attack("goblin", "fighter", 4, 16),
                List.of(18), 22, dm.model.Outcome.HIT);
        var damageRoll = new dm.model.RollResult(
                dm.model.RollRequest.damage("goblin", "1d6", 2), List.of(damage), damage + 2,
                dm.model.Outcome.HIT);
        log.append(new Event.AttackResolved(java.time.Instant.now(), "goblin", "fighter",
                attackRoll, java.util.Optional.of(damageRoll), damage, true, false));
    }

    @Test
    @DisplayName("an empty crypt rest restores four hit points and ticks both clocks")
    void restHealsAndTicksClocks() {
        damageFighterTo(10);

        var diffs = engine.rest("fighter");

        assertEquals(14, engine.state().find("fighter").orElseThrow().hp());
        assertEquals(1, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled());
        var rested = eventsOf(Event.Rested.class).getLast();
        assertEquals("fighter", rested.actorId());
        assertEquals(14, rested.hpAfter());
        assertTrue(diffs.stream().anyMatch(Diff.StatChanged.class::isInstance));
    }

    @Test
    @DisplayName("rest at full hit points still ticks clocks and restores zero")
    void restAtFullHpStillTicks() {
        assertEquals(20, engine.state().find("fighter").orElseThrow().hp());

        engine.rest("fighter");

        assertEquals(20, engine.state().find("fighter").orElseThrow().hp());
        assertEquals(1, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().clock(ClockId.ALERT).filled());
    }

    @Test
    @DisplayName("rest is refused with a living hostile in the room")
    void restRefusedWithHostile() {
        engine.spawnGoblin(6, 6);

        assertThrows(IllegalArgumentException.class, () -> engine.rest("fighter"));
        assertTrue(eventsOf(Event.Rested.class).isEmpty());
    }

    @Test
    @DisplayName("rest is legal again after crossing away from a hostile")
    void restLegalAfterLeavingHostile() {
        var rooms = Rooms.authored(new ContentLoader(), "crypt", "gallery");
        engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller(), rooms);
        engine.start();
        engine.spawnGoblin(6, 6);
        assertThrows(IllegalArgumentException.class, () -> engine.rest("fighter"));

        engine.crossExit("door-north");

        assertDoesNotThrow(() -> engine.rest("fighter"));
    }

    @Test
    @DisplayName("rest is refused in combat")
    void restRefusedInCombat() {
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        assertThrows(IllegalArgumentException.class, () -> engine.rest("fighter"));
    }

    @Test
    @DisplayName("rest is mechanics-only and omitted when illegal")
    void toolSchema() {
        var names = ToolSchema.forTurn(engine).findValuesAsText("name");
        assertTrue(names.contains(ToolSchema.REST));
        assertFalse(ToolSchema.forReconcile(engine).findValuesAsText("name")
                .contains(ToolSchema.REST));
        assertFalse(ToolSchema.allowedInReconcile(ToolSchema.REST));

        engine.spawnGoblin(6, 6);
        assertFalse(ToolSchema.forTurn(engine).findValuesAsText("name").contains(ToolSchema.REST));
    }

    @Test
    @DisplayName("the dispatcher rejects illegal rest calls")
    void dispatcherRejects() {
        var dispatcher = new ToolDispatcher(engine);
        engine.spawnGoblin(6, 6);

        var rejected = dispatcher.dispatch(new dm.ai.DmClient.ToolCall(
                "test", ToolSchema.REST, "{\"actor_id\":\"fighter\"}"));
        assertFalse(rejected.ok());
        assertTrue(rejected.message().contains("REJECTED"));
    }

    @Test
    @DisplayName("rest latches a room-scoped directive and party signs from clock ticks")
    void restLatchesDirectiveAndSigns() {
        for (int i = 0; i < 4; i++) {
            engine.tickClock(ClockId.LIGHT);
        }

        engine.rest("fighter");

        var waiting = engine.directives().snapshot();
        assertTrue(waiting.stream().anyMatch(d -> d.about() == Directive.About.ROOM
                && d.clause().equals(ClockTables.REST)), waiting.toString());
    }

    @Test
    @DisplayName("a rest that fills ALERT with IT_PASSES_BY leaves narration on the rail")
    void restAlertFillLeavesRailForNarration() {
        var engine = new GameEngine(new ContentLoader(), new EventLog(),
                new RandomDiceRoller(), Rooms.authored(new ContentLoader(), "crypt"),
                new ScriptedClockDraw(ConsequenceId.IT_PASSES_BY));
        engine.start();
        for (int i = 0; i < 5; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        engine.rest("fighter");

        var drained = engine.directives().drain();
        assertNotNull(drained);
        assertTrue(drained.contains(ClockTables.REST), drained);
    }

    @Test
    @DisplayName("scene carries canRest without segment counts")
    void sceneState() {
        assertTrue(engine.scene().canRest());

        engine.spawnGoblin(6, 6);
        assertFalse(engine.scene().canRest());
    }

    @Test
    @DisplayName("spawnGoblin diffs carry CanRestChanged when a hostile walks in")
    void spawnGoblinGreysRest() {
        var diffs = engine.spawnGoblin(6, 6);

        assertFalse(engine.scene().canRest());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.CanRestChanged cc
                && !cc.canRest()));
    }

    @Test
    @DisplayName("a client rest drains the rail into one prose call")
    void restNarratesViaScriptedDm() {
        var prose = new ScriptedDmClient("You catch your breath against the cold stone.");
        var service = new DmService(new ScriptedDmClient(), prose, engine,
                new ContentLoader().prompt("dm-tools"),
                new ContentLoader().prompt("dm"),
                new ContentLoader().prompt("dm-reconcile"));

        engine.rest("fighter");

        service.narrateRest(new dm.ai.TurnSink() {
            @Override public void narration(dm.model.NarrationSegment segment) {}
            @Override public void diffs(List<Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() {}
        });

        var directive = prose.conversations().getLast().stream()
                .filter(m -> "user".equals(m.role()))
                .map(dm.ai.DmClient.ChatMessage::content)
                .filter(c -> c != null && c.contains("Three sentences at most."))
                .findFirst()
                .orElseThrow();
        assertTrue(directive.contains(ClockTables.REST), directive);
    }
}
