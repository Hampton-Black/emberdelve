package dm.ai;

import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.4: {@code take_prop} is mechanics-only, closed over takeable ids.
 */
class TakePropToolsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static List<String> toolNames(com.fasterxml.jackson.databind.node.ArrayNode tools) {
        return tools.findValuesAsText("name");
    }

    @Test
    @DisplayName("take_prop is offered in mechanics with this room's takeable ids, not in reconcile")
    void takePropIsMechanicsOnly() {
        var engine = started();

        assertTrue(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.TAKE_PROP));
        assertFalse(toolNames(ToolSchema.forReconcile(engine)).contains(ToolSchema.TAKE_PROP));
        assertFalse(ToolSchema.allowedInReconcile(ToolSchema.TAKE_PROP));

        var take = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.TAKE_PROP))
                .findFirst().orElseThrow();
        var values = take.path("parameters").path("properties").path("prop_id").path("enum");
        assertEquals(1, values.size());
        assertEquals("reliquary", values.get(0).asText());
    }

    @Test
    @DisplayName("take_prop is omitted when nothing is left to take")
    void takePropOmittedWhenNothingToTake() {
        var engine = started();
        engine.takeProp("reliquary");

        assertFalse(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.TAKE_PROP));

        engine.crossExit("door-north");
        assertFalse(toolNames(ToolSchema.forTurn(engine)).contains(ToolSchema.TAKE_PROP),
                "the gallery has nothing takeable");
    }

    @Test
    @DisplayName("take_prop takes the reliquary; unknown ids are rejected")
    void dispatcherTakesAndRejects() {
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        var dispatcher = new ToolDispatcher(engine);

        var taken = dispatcher.dispatch(new DmClient.ToolCall(
                "1", ToolSchema.TAKE_PROP, "{\"prop_id\":\"reliquary\"}"));
        assertTrue(taken.ok(), taken.message());
        assertTrue(engine.state().holdingObjective());
        assertTrue(log.events().stream().anyMatch(Event.ObjectiveTaken.class::isInstance));

        var unknown = dispatcher.dispatch(new DmClient.ToolCall(
                "2", ToolSchema.TAKE_PROP, "{\"prop_id\":\"sarcophagus\"}"));
        assertFalse(unknown.ok());
        assertTrue(unknown.message().startsWith("REJECTED:"), unknown.message());
    }

    @Test
    @DisplayName("the projection says held once the objective is taken")
    void projectionSaysHeld() {
        var engine = started();
        assertTrue(DmService.theParty(engine.state()).contains("- objective: not yet found"));

        engine.takeProp("reliquary");

        var party = DmService.theParty(engine.state());
        assertTrue(party.contains("- objective: held"), party);
        assertFalse(party.contains("- objective: not yet found"), party);
    }
}
