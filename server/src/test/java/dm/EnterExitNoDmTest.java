package dm;

import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.state.EventLog;
import dm.wire.Json;
import dm.wire.ServerMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * emberdelve-xgg.12 review fix: a no-key crossing sets {@code awaiting_dm} on the client, so the
 * server must still send {@link ServerMessage.NarrationEnd} when there is no DM to narrate with.
 */
class EnterExitNoDmTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("enterExit with no DM still sends NarrationEnd so the client hold lifts")
    void enterExitWithoutDmSendsNarrationEnd() throws Exception {
        var engine = engine();
        var outbound = new ArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, null, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree(
                "{\"type\":\"enterExit\",\"exitId\":\"door-north\"}"));

        assertEquals("gallery", engine.state().roomId());
        assertTrue(outbound.stream().anyMatch(ServerMessage.Scene.class::isInstance),
                "crossing still replaces the scene");
        assertTrue(outbound.stream().anyMatch(ServerMessage.NarrationEnd.class::isInstance),
                "without a DM the arrival hold must lift the way begin does");
    }

    private static GameEngine engine() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }
}
