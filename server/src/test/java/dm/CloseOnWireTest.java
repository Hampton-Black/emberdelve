package dm;

import dm.ai.DmService;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.model.Ending;
import dm.state.EventLog;
import dm.wire.Json;
import dm.wire.ServerMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.5: extract and PARTY_LOST replace arrival / combat narration with the close.
 */
class CloseOnWireTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("no-key extract still sends NarrationEnd so the hold lifts")
    void extractWithoutDmSendsNarrationEnd() throws Exception {
        var engine = engine(10);
        var outbound = new CopyOnWriteArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, null, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree(
                "{\"type\":\"enterExit\",\"exitId\":\"stair-south\"}"));

        assertEquals(Ending.EXTRACTED_WITHOUT, engine.state().ending().orElseThrow());
        assertTrue(outbound.stream().anyMatch(ServerMessage.Scene.class::isInstance));
        assertTrue(outbound.stream().anyMatch(ServerMessage.NarrationEnd.class::isInstance),
                "without a DM the extract hold must lift");
    }

    @Test
    @DisplayName("extract with a DM calls close, not arrival")
    void extractWithDmCallsCloseNotArrival() throws Exception {
        var engine = engine(10);
        var prose = new ScriptedDmClient("They walk out into the air.");
        var dm = dm(engine, prose);
        var outbound = new CopyOnWriteArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, dm, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree(
                "{\"type\":\"enterExit\",\"exitId\":\"stair-south\"}"));

        await(outbound, ServerMessage.NarrationEnd.class);
        assertEquals(1, prose.conversations().size(), "one close, not an arrival on top");
        var blob = blob(prose);
        assertTrue(blob.contains("The delve has ended. Two or three sentences."), blob);
        assertFalse(blob.contains("The party has just come through into this room."), blob);
        assertFalse(blob.contains("The party has come back into a room they have been"), blob);
    }

    @Test
    @DisplayName("PARTY_LOST automatic turn calls close, not narrateCombat")
    void partyLostAutomaticTurnCallsClose() throws Exception {
        var log = new EventLog();
        var engine = engine(log, 1, 20, 19, 4);
        engine.spawnGoblin(6, 6);
        var fighter = engine.state().find("fighter").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), fighter.withHp(1)));

        var prose = new ScriptedDmClient("He falls.");
        var dm = dm(engine, prose);
        var outbound = new CopyOnWriteArrayList<ServerMessage>();
        var handler = WsHandler.forTest(engine, dm, outbound::add);

        handler.handleMessage(Json.MAPPER.readTree("{\"type\":\"debugStartCombat\"}"));

        await(outbound, ServerMessage.NarrationEnd.class);
        assertEquals(Ending.PARTY_LOST, engine.state().ending().orElseThrow());
        assertEquals(1, prose.conversations().size());
        var blob = blob(prose);
        assertTrue(blob.contains("The delve has ended. Two or three sentences."), blob);
        assertFalse(blob.contains("Narrate this moment of the fight"), blob);
    }

    private static String blob(ScriptedDmClient prose) {
        return prose.conversations().stream()
                .flatMap(List::stream)
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static void await(List<ServerMessage> outbound, Class<?> type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (outbound) {
                if (outbound.stream().anyMatch(type::isInstance)) {
                    return;
                }
            }
            Thread.sleep(15);
        }
        fail("timed out waiting for " + type.getSimpleName() + ": " + outbound);
    }

    private static GameEngine engine(Integer... faces) {
        return engine(new EventLog(), faces);
    }

    private static GameEngine engine(EventLog log, Integer... faces) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(faces),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static DmService dm(GameEngine engine, ScriptedDmClient prose) {
        return new DmService(new ScriptedDmClient(), prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
    }
}
