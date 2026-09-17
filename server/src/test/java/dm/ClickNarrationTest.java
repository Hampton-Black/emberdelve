package dm;

import dm.ai.DmService;
import dm.content.ContentLoader;
import dm.engine.ClockTables;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.ClockId;
import dm.state.EventLog;
import dm.wire.Json;
import dm.wire.ServerMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-kac: the bar and the take click exist so those actions cannot drop, and they should
 * still be heard. Each click is one prose call carrying its own clause, the way rest already is,
 * with no mechanics model in the path.
 *
 * <p>Reverses spec §8d's "a player action that ticks a clock narrates": play found a potion
 * drunk in silence, and a reliquary vanishing without a word, to read as nothing having happened.
 */
class ClickNarrationTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("a clicked potion is heard, and the tools model is never asked")
    void potionClickNarrates() throws Exception {
        var engine = engine();
        var fighter = engine.state().find("fighter").orElseThrow();
        // Hurt, so the potion has something to do; the click path does not care either way.
        engine.log().append(new dm.model.Event.EntitySpawned(java.time.Instant.now(),
                fighter.withHp(10)));
        var wire = wire(engine, "The draught goes down warm.");

        wire.send("{\"type\":\"useItem\",\"actorId\":\"fighter\",\"item\":\"potion\"}");

        wire.awaitNarrationEnd();
        assertEquals(1, wire.prose.conversations().size());
        assertTrue(wire.directive().contains(ClockTables.POTION_DRUNK), wire.directive());
        assertTrue(wire.tools.conversations().isEmpty());
        assertTrue(wire.outbound.stream().anyMatch(ServerMessage.Narration.class::isInstance));
    }

    @Test
    @DisplayName("a clicked torch is heard now, not on whatever narrates next")
    void torchClickNarrates() throws Exception {
        var engine = engine();
        engine.tickClock(ClockId.LIGHT);
        var wire = wire(engine, "The new torch takes.");

        wire.send("{\"type\":\"useItem\",\"actorId\":\"fighter\",\"item\":\"torch\"}");

        wire.awaitNarrationEnd();
        assertEquals(1, wire.prose.conversations().size());
        assertTrue(wire.directive().contains(ClockTables.TORCH_RELIT), wire.directive());
        assertNull(engine.directives().drain(), "the relight clause was spent on the click");
        assertTrue(wire.tools.conversations().isEmpty());
    }

    @Test
    @DisplayName("a clicked take says what was taken")
    void takeClickNarrates() throws Exception {
        var engine = engine();
        engine.crossExit("door-north");
        engine.crossExit("door-north");
        engine.directives().clear();
        var wire = wire(engine, "The reliquary comes away in your hands.");

        wire.send("{\"type\":\"useProp\",\"propId\":\"reliquary\",\"action\":\"take\"}");

        wire.awaitNarrationEnd();
        assertTrue(engine.state().holdingObjective());
        assertEquals(1, wire.prose.conversations().size());
        var directive = wire.directive();
        assertTrue(directive.contains("iron-bound reliquary"),
                "the narrator is told which thing: " + directive);
        assertTrue(wire.tools.conversations().isEmpty());
    }

    @Test
    @DisplayName("a refused click says nothing")
    void refusedClickIsSilent() throws Exception {
        var engine = engine();
        var wire = wire(engine, "should never be said");

        // LIGHT is full, so a torch would reset nothing and is refused.
        wire.send("{\"type\":\"useItem\",\"actorId\":\"fighter\",\"item\":\"torch\"}");
        Thread.sleep(150);

        assertTrue(wire.prose.conversations().isEmpty());
    }

    private static GameEngine engine() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        return engine;
    }

    private static Wire wire(GameEngine engine, String reply) {
        var tools = new ScriptedDmClient();
        var prose = new ScriptedDmClient(reply);
        var dm = new DmService(tools, prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
        var outbound = new CopyOnWriteArrayList<ServerMessage>();
        return new Wire(WsHandler.forTest(engine, dm, outbound::add), tools, prose, outbound);
    }

    private record Wire(WsHandler handler, ScriptedDmClient tools, ScriptedDmClient prose,
                        List<ServerMessage> outbound) {

        void send(String json) {
            try {
                handler.handleMessage(Json.MAPPER.readTree(json));
            } catch (Exception e) {
                // A refused action surfaces as an error message on the wire, not a throw.
            }
        }

        String directive() {
            return prose.conversations().getLast().stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(dm.ai.DmClient.ChatMessage::content)
                    .filter(c -> c != null && c.contains("Three sentences at most."))
                    .findFirst()
                    .orElseThrow();
        }

        void awaitNarrationEnd() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (outbound.stream().anyMatch(ServerMessage.NarrationEnd.class::isInstance)) {
                    return;
                }
                Thread.sleep(15);
            }
            fail("timed out waiting for NarrationEnd: " + outbound);
        }
    }
}
