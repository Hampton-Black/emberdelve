package dm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dm.engine.GameEngine;
import dm.model.Diff;
import dm.model.Mode;
import dm.wire.ServerMessage;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * One session, one connection (M0). Everything the client is told goes through here.
 */
public final class WsHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GameEngine engine;
    private final boolean demoMode;

    public WsHandler(GameEngine engine, boolean demoMode) {
        this.engine = engine;
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
