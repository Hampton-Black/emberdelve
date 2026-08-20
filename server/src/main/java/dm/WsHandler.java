package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmService;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.model.Difficulty;
import dm.model.Diff;
import dm.model.Skill;
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
import java.util.function.Consumer;

/**
 * One session, one connection (M0). Everything the client is told goes through here.
 */
public final class WsHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);

    /** One virtual thread per turn, so a streaming DM call never blocks the socket. */
    private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The pause between a creature closing the distance and swinging.
     *
     * <p>This is the server spending real time on pacing, which looks like a layering violation
     * and is not: the beat between "it reaches you" and "it hits you" is the DM's, not the
     * renderer's. It happens to be long enough to cover the client's move animation, which is
     * the longest a slide can take.
     */
    private static final long BEAT_MS = 750;

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
            // Deliberately does NOT open the scene. See the `begin` case below.
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

            case "moveTo" -> act(ctx, sink -> engine.moveTo(
                    message.path("actorId").asText(),
                    message.path("x").asInt(),
                    message.path("y").asInt(),
                    sink));

            case "attack" -> act(ctx, sink -> engine.combat().attack(
                    message.path("actorId").asText(),
                    message.path("targetId").asText(),
                    sink));

            case "endTurn" -> act(ctx, sink ->
                    engine.combat().endTurn(message.path("actorId").asText(), sink));

            // T5 debug hooks. These exist to prove diffs render without a model in the path,
            // and are replaced by real tool dispatch in T7.
            case "debugReveal" -> sendDiffs(ctx, engine.revealProp(message.path("propId").asText()));

            case "debugSpawnGoblin" -> {
                var at = engine.defaultGoblinSpawn();
                sendDiffs(ctx, engine.spawnGoblin(at.x(), at.y()));
            }

            case "debugSetMode" -> sendDiffs(ctx,
                    engine.setMode(Mode.valueOf(message.path("mode").asText())));

            // §2 step 2: the room describes itself before the player types anything.
            //
            // Asked for by the client rather than pushed on connect. A socket opening is not a
            // player arriving: browsers refuse to play audio until someone has clicked something,
            // so narrating at connect meant the opening was spoken to a page that could not make
            // a sound, and the transcript — which is paced by the voice — desynchronised from it.
            // The client sends this from inside the click that starts the game.
            case "begin" -> {
                if (dm != null) {
                    turns.submit(() -> dm.openScene(turnSink(ctx), false));
                }
            }

            case "debugScene" -> send(ctx, new ServerMessage.Scene(engine.scene()));

            // Combat without a model in the path, for the same reason debugRoll exists.
            case "debugStartCombat" -> act(ctx, sink -> engine.combat().start(sink));

            // Re-runs the opening past its once-per-session guard, for tuning it without a restart.
            case "debugOpen" -> {
                if (dm != null) {
                    turns.submit(() -> dm.openScene(turnSink(ctx), true));
                }
            }

            // A real roll down the real path, with no model in it. Exists so the dice tray's
            // feel can be tuned in a tight loop, and so the client works without an API key.
            case "debugRoll" -> send(ctx, new ServerMessage.Roll(engine.rollCheck(
                    message.path("actorId").asText("fighter"),
                    Skill.valueOf(message.path("skill").asText("PERCEPTION")),
                    Difficulty.valueOf(message.path("difficulty").asText("MEDIUM")))));

            default -> throw new IllegalArgumentException("Unknown message type: " + type);
        }
    }

    /**
     * Runs one player action and then hands the fight to whoever is next.
     *
     * <p>The player's own consequences are sent synchronously — the click-to-move budget has no
     * room for a thread hop — and the goblin's turn goes to a virtual thread, because it spends
     * real seconds on pacing and must not hold the socket while it does.
     */
    private void act(WsContext ctx, Consumer<CombatSink> action) {
        var sink = sink(ctx);
        action.accept(sink);

        if (engine.combat().isActive() && !engine.combat().isPlayerTurn()) {
            turns.submit(() -> {
                try {
                    engine.combat().runAutomaticTurns(sink, WsHandler::beat);
                } catch (Exception e) {
                    log.error("enemy turn failed", e);
                    send(ctx, new ServerMessage.Error("The goblin froze: " + e.getMessage()));
                }
            });
        }
    }

    private static void beat() {
        try {
            Thread.sleep(BEAT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends what a combat action produced, in the order it produced it. */
    private CombatSink sink(WsContext ctx) {
        return new CombatSink() {
            @Override
            public void diffs(List<Diff> diffs) {
                sendDiffs(ctx, diffs);
            }

            @Override
            public void roll(dm.model.RollResult result) {
                send(ctx, new ServerMessage.Roll(result));
            }
        };
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
        turns.submit(() -> dm.handleFreeText(actorId, text, turnSink(ctx)));
    }

    /** Where a DM turn's narration, diffs and dice go. */
    private dm.ai.TurnSink turnSink(WsContext ctx) {
        return new dm.ai.TurnSink() {
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
        };
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
