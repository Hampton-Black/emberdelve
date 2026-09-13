package dm;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.6: click {@code useProp} inspect returns the marker to the DM without a grid.
 */
class UsePropInspectTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final String TEXT = "The flagstones here are blackened, as if a fire sat too long.";

    @Test
    @DisplayName("useProp inspect records the marker and starts a turn with no (9,6)")
    void usePropInspectsWithoutCoordinates() throws Exception {
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.PLACE_MARKER,
                "{\"tag\":\"SCORCH\",\"x\":9,\"y\":6,\"text\":" + Json.MAPPER.valueToTree(TEXT) + "}"));
        var markerId = engine.scene().markers().getFirst().id();

        var prose = new ScriptedDmClient("Ash, still warm.");
        var dm = new DmService(new ScriptedDmClient(), prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
        var outbound = new ArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, dm, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree(
                "{\"type\":\"useProp\",\"propId\":\"" + markerId + "\",\"action\":\"inspect\"}"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline
                && log.events().stream().noneMatch(Event.MarkerInspected.class::isInstance)) {
            Thread.sleep(15);
        }
        assertTrue(log.events().stream().anyMatch(Event.MarkerInspected.class::isInstance));

        while (System.nanoTime() < deadline && prose.conversations().isEmpty()) {
            Thread.sleep(15);
        }
        assertFalse(prose.conversations().isEmpty(), "clicking starts a DM turn");
        String blob = prose.conversations().stream()
                .flatMap(List::stream)
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(blob.contains(TEXT), blob);
        assertTrue(blob.contains("SCORCH"), blob);

        int establishedAt = blob.indexOf("## Established");
        assertTrue(establishedAt >= 0, blob);
        int nextHead = blob.indexOf("\n## ", establishedAt + 14);
        String established = nextHead < 0 ? blob.substring(establishedAt) : blob.substring(establishedAt, nextHead);
        assertFalse(established.contains("(9,6)"),
                "a visible marker that prints (9,6) will be read aloud:\n" + established);

        String said = prose.conversations().stream()
                .flatMap(List::stream)
                .filter(m -> "user".equals(m.role()))
                .map(m -> m.content() == null ? "" : m.content())
                .filter(c -> c.contains("SCORCH") || c.contains(TEXT))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(said.isBlank(), "the inspect line should reach the prose model");
        assertFalse(said.contains("(9,6)"), said);
    }
}
