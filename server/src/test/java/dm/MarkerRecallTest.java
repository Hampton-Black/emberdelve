package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmClient;
import dm.ai.DmService;
import dm.ai.ToolDispatcher;
import dm.ai.ToolSchema;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.wire.Json;
import dm.wire.ServerMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-5md: a marker is something the DM already said. Clicking it is recall — one prose
 * call about what is established — never a turn with checks and spawns behind it.
 *
 * <p>The greed run clicked an emptied niche's SIGIL four times; three of those rolled a check and
 * one spawned the goblin that made the gallery's only costly fight.
 */
class MarkerRecallTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final String SIGIL_TEXT = "A scratched ward above the niche, its lines half filled with soot.";

    @Test
    @DisplayName("clicking a marker twice rolls nothing, spawns nothing, and never asks the tools model")
    void clickingAMarkerIsRecallNotATurn() throws Exception {
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(2),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        new ToolDispatcher(engine).dispatch(new DmClient.ToolCall("1", ToolSchema.PLACE_MARKER,
                "{\"tag\":\"SIGIL\",\"x\":9,\"y\":6,\"text\":"
                        + Json.MAPPER.valueToTree(SIGIL_TEXT) + "}"));
        String markerId = engine.scene().markers().getFirst().id();

        var tools = new TrippingToolsClient();
        var prose = new ScriptedDmClient("The ward is as it was.", "Still only soot and scratches.");
        var dm = new DmService(tools, prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
        var outbound = new CopyOnWriteArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, dm, outbound::add);
        int eventsBefore = log.events().size();

        var click = Json.MAPPER.readTree(
                "{\"type\":\"useProp\",\"action\":\"inspect\",\"propId\":\"" + markerId + "\"}");
        handler.handleMessage(click);
        awaitNarrationEnds(outbound, 1);
        handler.handleMessage(click);
        awaitNarrationEnds(outbound, 2);

        var after = log.events().subList(eventsBefore, log.events().size());
        assertTrue(after.stream().noneMatch(Event.CheckResolved.class::isInstance), after.toString());
        assertTrue(after.stream().noneMatch(Event.EntitySpawned.class::isInstance), after.toString());
        assertTrue(after.stream().noneMatch(Event.MarkerPlaced.class::isInstance), after.toString());
        assertTrue(after.stream().noneMatch(Event.FactAsserted.class::isInstance), after.toString());
        assertEquals(0, tools.calls, "neither the mechanics pass nor the reconcile pass runs");

        assertEquals(2, prose.conversations().size(), "one prose call per click");
        assertEquals(2, after.stream().filter(Event.MarkerInspected.class::isInstance).count());
        var blob = prose.conversations().getLast().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(blob.contains(SIGIL_TEXT), "the model is told what the marker says:\n" + blob);
        assertFalse(blob.contains("Narrate what is different now"),
                "a recall is not a retry; asking for something different invents it:\n" + blob);
    }

    private static void awaitNarrationEnds(List<ServerMessage> outbound, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (outbound.stream().filter(ServerMessage.NarrationEnd.class::isInstance).count() >= count) {
                return;
            }
            Thread.sleep(15);
        }
        fail("timed out waiting for NarrationEnd #" + count + ": " + outbound);
    }

    /** Offers a check and a spawn every time it is asked, so any call at all shows up as dice or a goblin. */
    private static final class TrippingToolsClient implements DmClient {
        volatile int calls;

        @Override
        public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                     DmListener listener) {
            calls++;
            var toolCalls = new ArrayList<ToolCall>();
            toolCalls.add(new ToolCall("c" + calls, ToolSchema.ROLL_CHECK,
                    "{\"actor_id\":\"fighter\",\"skill\":\"perception\",\"difficulty\":\"medium\"}"));
            toolCalls.add(new ToolCall("s" + calls, ToolSchema.SPAWN_ENTITY,
                    "{\"kind\":\"goblin\",\"x\":5,\"y\":7}"));
            return new TurnResult("", toolCalls);
        }

        @Override
        public String modelId() {
            return "tripwire";
        }

        @Override
        public long ping() {
            return 0;
        }
    }
}
