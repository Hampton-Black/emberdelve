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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * emberdelve-4h9.4: click {@code useProp} with action take hits {@code GameEngine.takeProp}.
 */
class UsePropTakeTest {

    @Test
    @DisplayName("useProp take holds the reliquary and ships a diff")
    void usePropTakes() throws Exception {
        var engine = new GameEngine(new ContentLoader(), new EventLog(),
                new ScriptedDiceRoller(10),
                Rooms.authored(new ContentLoader(), "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        engine.crossExit("door-north");
        engine.crossExit("door-north");
        var outbound = new ArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, null, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree(
                "{\"type\":\"useProp\",\"propId\":\"reliquary\",\"action\":\"take\"}"));

        assertTrue(engine.state().holdingObjective());
        assertTrue(outbound.stream().anyMatch(ServerMessage.Diffs.class::isInstance));
    }
}
