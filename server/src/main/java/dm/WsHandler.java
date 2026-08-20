package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmService;
import dm.engine.GameEngine;
import dm.model.Diff;
import dm.model.Mode;
import dm.wire.Json;
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
                handle(ctx, Json.MAPPER.readTree(ctx.message()));
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
        turns.submit(() -> dm.handleFreeText(actorId, text, new dm.ai.TurnSink() {
            @Override
            public void narration(dm.model.NarrationSegment segment) {
                send(ctx, new ServerMessage.Narration(segment));
            }

            @Override
            public void diffs(List<Diff> diffs) {
                sendDiffs(ctx, diffs);
            }

            @Override
            public void roll(dm.model.RollResult result) {
                send(ctx, new ServerMessage.Roll(result));
            }

            @Override
            public void complete() {
                send(ctx, new ServerMessage.NarrationEnd());
            }

            @Override
            public void error(Throwable error) {
                send(ctx, new ServerMessage.Error("The DM stumbled: " + error.getMessage()));
            }
        }));
    }

    private void sendDiffs(WsContext ctx, List<Diff> diffs) {
        if (diffs.isEmpty()) {
            return;
        }
        send(ctx, new ServerMessage.Diffs(diffs));
    }

    private void send(WsContext ctx, ServerMessage message) {
        try {
            ctx.send(Json.MAPPER.writeValueAsString(message));
        } catch (Exception e) {
            // Covers send failures too — a turn that outlives its socket lands here.
            log.error("could not deliver {} to client", message.getClass().getSimpleName(), e);
        }
    }
}
