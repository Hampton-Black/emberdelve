package dm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dm.ai.DmService;
import dm.engine.GameEngine;
import dm.model.Diff;
import dm.model.Mode;
import dm.wire.ServerMessage;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One session, one connection (M0). Everything the client is told goes through here.
 */
public final class WsHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One virtual thread per turn, so a streaming DM call never blocks the socket. */
    private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

    private final GameEngine engine;
    private final DmService dm;
    private final boolean demoMode;

    public WsHandler(GameEngine engine, DmService dm, boolean demoMode) {
        this.engine = engine;
        this.dm = dm;
        this.demoMode = demoMode;
    }

    public void register(WsConfig ws) {
        ws.onConnect(ctx -> {
            ctx.enableAutomaticPings();
            log.info("client connected: {}", ctx.sessionId());
            send(ctx, new ServerMessage.Hello(demoMode));
            send(ctx, new ServerMessage.Scene(engine.scene()));
        });

        ws.onMessage(ctx -> {
            try {
                handle(ctx, MAPPER.readTree(ctx.message()));
            } catch (Exception e) {
                // No error recovery in M0 — log it and surface a visible toast (shortcut #13).
                log.error("failed to handle message: {}", ctx.message(), e);
                send(ctx, new ServerMessage.Error(String.valueOf(e.getMessage())));
            }
        });

        ws.onClose(ctx -> log.info("client disconnected: {}", ctx.sessionId()));

        ws.onError(ctx -> log.error("ws error", ctx.error()));
    }

    private void handle(WsContext ctx, JsonNode message) {
        String type = message.path("type").asText();

        switch (type) {
            case "freeText" -> freeText(ctx,
                    message.path("actorId").asText(),
                    message.path("text").asText());

            case "moveTo" -> sendDiffs(ctx, engine.moveTo(
                    message.path("actorId").asText(),
                    message.path("x").asInt(),
                    message.path("y").asInt()));

            // T5 debug hooks. These exist to prove diffs render without a model in the path,
            // and are replaced by real tool dispatch in T7.
            case "debugReveal" -> sendDiffs(ctx, engine.revealProp(message.path("propId").asText()));

            case "debugSpawnGoblin" -> {
                var at = engine.defaultGoblinSpawn();
                sendDiffs(ctx, engine.spawnGoblin(at.x(), at.y()));
            }

            case "debugSetMode" -> sendDiffs(ctx,
                    engine.setMode(Mode.valueOf(message.path("mode").asText())));

            case "debugScene" -> send(ctx, new ServerMessage.Scene(engine.scene()));

            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        }
    }

    private void freeText(WsContext ctx, String actorId, String text) {
        if (dm == null) {
            send(ctx, new ServerMessage.Error(
                    "No DM configured. Set VENICE_API_KEY in .env and restart the server."));
            return;
        }
        if (text.isBlank()) {
            return;
        }

        // Off the socket thread: this call streams for seconds.
        turns.submit(() -> dm.handleFreeText(
                actorId,
                text,
                segment -> send(ctx, new ServerMessage.Narration(segment)),
                () -> send(ctx, new ServerMessage.NarrationEnd()),
                error -> send(ctx, new ServerMessage.Error(
                        "The DM stumbled: " + error.getMessage()))));
    }

    private void sendDiffs(WsContext ctx, List<Diff> diffs) {
        if (diffs.isEmpty()) {
            return;
        }
        send(ctx, new ServerMessage.Diffs(diffs));
    }

    private void send(WsContext ctx, ServerMessage message) {
        try {
            ctx.send(MAPPER.writeValueAsString(message));
        } catch (Exception e) {
            log.error("failed to serialize outbound message", e);
        }
    }
}
