package dm.ai;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.wire.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraversalToolsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static List<String> toolNames(com.fasterxml.jackson.databind.node.ArrayNode tools) {
        return tools.findValuesAsText("name");
    }

    // ---- Schema ----

    @Test
    @DisplayName("the mechanics phase is offered use_exit; reconcile is not")
    void useExitIsMechanicsOnly() {
        var engine = started(new EventLog());

        assertTrue(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.USE_EXIT));
        assertFalse(toolNames(ToolSchema.forReconcile(engine)).contains(ToolSchema.USE_EXIT));
        assertFalse(ToolSchema.allowedInReconcile(ToolSchema.USE_EXIT),
                "m2-evaluation §8: a spoken aside must not be able to move the party");
    }

    @Test
    @DisplayName("move_entity is offered in both phases")
    void moveEntityIsOfferedInBoth() {
        var engine = started(new EventLog());

        assertTrue(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.MOVE_ENTITY));
        assertTrue(toolNames(ToolSchema.forReconcile(engine)).contains(ToolSchema.MOVE_ENTITY));
        assertTrue(ToolSchema.allowedInReconcile(ToolSchema.MOVE_ENTITY));
    }

    @Test
    @DisplayName("use_exit is not offered during a fight")
    void useExitIsWithdrawnInCombat() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        assertFalse(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.USE_EXIT),
                "crossExit refuses it, so offering it is a wasted round trip and a rejection "
                        + "that counts towards degrading the turn");
    }

    @Test
    @DisplayName("the exit enum is this room's exits, by id")
    void exitEnumIsClosed() {
        var engine = started(new EventLog());

        var useExit = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.USE_EXIT))
                .findFirst().orElseThrow();
        var values = useExit.path("parameters").path("properties")
                .path("exit_id").path("enum");

        assertEquals(1, values.size());
        assertEquals("door-north", values.get(0).asText());
    }

    @Test
    @DisplayName("a dead entity is not offered as something to move or to roll for")
    void deadActorsAreNotOffered() {
        var log = new EventLog();
        // Fighter initiative 20, goblin 1, then a hit that kills: the same shape
        // ReplayRunnerTest uses to stage a real fight rather than a synthetic event.
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(20, 1, 20, 8, 20, 8),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.moveTo("fighter", 6, 5, new CombatSink.Buffer());
        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        while (engine.state().find("goblin").orElseThrow().isAlive()) {
            engine.combat().attack("fighter", "goblin", sink);
        }

        var tools = ToolSchema.forTurn(engine);
        for (var toolName : List.of(ToolSchema.ROLL_CHECK, ToolSchema.MOVE_ENTITY)) {
            var tool = tools.findParents("name").stream()
                    .filter(n -> n.path("name").asText().equals(toolName))
                    .findFirst().orElseThrow();
            var actorEnum = tool.path("parameters").path("properties")
                    .path("actor_id").path("enum");
            for (var id : actorEnum) {
                assertNotEquals("goblin", id.asText(),
                        "a dead goblin offered as an actor is a rejection at dispatch every time");
            }
        }
    }

    // ---- Dispatch ----

    @Test
    @DisplayName("use_exit takes the party through")
    void useExitCrosses() {
        var log = new EventLog();
        var engine = started(log);

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.USE_EXIT, "{\"exit_id\":\"door-north\"}"));

        assertTrue(result.ok(), result.message());
        assertEquals("gallery", engine.state().roomId());
        assertTrue(log.events().stream().anyMatch(Event.PartyMoved.class::isInstance));
        assertTrue(result.replacesScene());
        assertTrue(result.diffs().isEmpty());
    }

    @Test
    @DisplayName("use_exit on an exit that is not here is rejected, not thrown")
    void useExitRejectsUnknown() {
        var engine = started(new EventLog());

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.USE_EXIT, "{\"exit_id\":\"door-west\"}"));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
    }

    @Test
    @DisplayName("move_entity moves a token and emits the diff the client needs")
    void moveEntityMoves() {
        var log = new EventLog();
        var engine = started(log);

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY, "{\"actor_id\":\"fighter\",\"x\":3,\"y\":5}"));

        assertTrue(result.ok(), result.message());
        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals(3, fighter.x());
        assertEquals(5, fighter.y());
        assertTrue(result.diffs().stream().anyMatch(dm.model.Diff.EntityMoved.class::isInstance));
    }

    @Test
    @DisplayName("move_entity onto something solid is rejected with a reason")
    void moveEntityRefusesObstruction() {
        var engine = started(new EventLog());
        var sarcophagus = CONTENT.room("crypt").prop("sarcophagus");

        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY,
                "{\"actor_id\":\"fighter\",\"x\":%d,\"y\":%d}"
                        .formatted(sarcophagus.x(), sarcophagus.y())));

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
    }

    @Test
    @DisplayName("in a fight move_entity obeys the turn and the budget, because it is the same call")
    void moveEntityInheritsCombatRules() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        // Whoever is not active cannot be moved: CombatEngine.moveTo opens with requireActive.
        String inactive = engine.combat().activeId().equals("fighter") ? "goblin" : "fighter";
        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.MOVE_ENTITY,
                "{\"actor_id\":\"" + inactive + "\",\"x\":5,\"y\":5}"));

        assertFalse(result.ok(), "the DM does not get to play someone else's turn");
    }
}
