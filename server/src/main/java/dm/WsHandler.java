package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmService;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.model.Difficulty;
import dm.model.Entity;
import dm.model.Diff;
import dm.model.Outcome;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Whether an enemy turn is already running.
     *
     * <p>Two paths hand the fight over now — a player action, and a DM turn that called
     * {@code start_combat} — and on a quick turn both can be true at the same moment. Whoever
     * claims this owns the turn; the other returns.
     */
    private final AtomicBoolean automatic = new AtomicBoolean();

    private final GameEngine engine;
    private final DmService dm;
    private final boolean demoMode;
    private final boolean voice;
    private final SessionGuard guard = new SessionGuard();

    public WsHandler(GameEngine engine, DmService dm, boolean demoMode, boolean voice) {
        this.engine = engine;
        this.dm = dm;
        this.demoMode = demoMode;
        this.voice = voice;
    }

    public void register(WsConfig ws) {
        ws.onConnect(ctx -> {
            if (!guard.claim(ctx.sessionId())) {
                // Not an error the client should retry. Say which one is already attached to,
                // because the usual cause is a browser tab left open beside the Godot editor.
                log.warn("refusing a second client: {}", ctx.sessionId());
                ctx.closeSession(4001, "Another client is already at this table.");
                return;
            }
            ctx.enableAutomaticPings();
            log.info("client connected: {}", ctx.sessionId());
            send(ctx, new ServerMessage.Hello(demoMode, voice, dm != null));
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

        ws.onClose(ctx -> {
            guard.release(ctx.sessionId());
            log.info("client disconnected: {}", ctx.sessionId());
        });

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

            // A room change replaces everything, so it answers with a whole Scene rather than
            // diffs. The client already rebuilds props and tokens when roomId changes.
            case "enterExit" -> {
                engine.crossExit(message.path("exitId").asText(""));
                send(ctx, new ServerMessage.Scene(engine.scene()));
            }

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
                } else {
                    // The client holds its input and its debug bar from the moment the player
                    // clicks through the title, on the assumption that an opening is coming. With
                    // no key configured one never does, and without this the hold never lifts.
                    send(ctx, new ServerMessage.NarrationEnd());
                }
            }

            // A new session in the same process. The client reloads once the fresh scene lands,
            // which is also how it gets a title screen and a real click to unlock audio again.
            case "restart" -> {
                engine.restart();
                if (dm != null) {
                    dm.reset();
                }
                log.info("session restarted");
                send(ctx, new ServerMessage.Scene(engine.scene()));
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
     * room for a thread hop. What happens after them is {@link #handOff}'s problem.
     */
    private void act(WsContext ctx, Consumer<CombatSink> action) {
        var mine = new Beats(ctx);
        action.accept(mine);

        // The player's own swing is narrated only when it is worth stopping for. Every ordinary
        // hit would put combat on a five-second-per-click clock, and design doc §7's "dramatic
        // beats only" exists precisely to stop that.
        if (mine.dramatic) {
            narrate(ctx, mine.facts);
        }

        handOff(ctx);
    }

    /**
     * Gives the fight to whoever is next, when that is not the player.
     *
     * <p>Called from both the player-action path and the end of a DM turn. It used to live only
     * on the first of those, which meant a fight the DM started — rather than one the player
     * walked into — simply stopped if the creature won initiative: nothing on the server would
     * ever run its turn, and the player was left looking at a HUD waiting on a goblin that had
     * no reason to move.
     *
     * <p>The turn goes to a virtual thread because it spends real seconds on pacing and must not
     * hold the socket while it does.
     */
    private void handOff(WsContext ctx) {
        if (!engine.combat().isActive() || engine.combat().isPlayerTurn()) {
            return;
        }
        if (!automatic.compareAndSet(false, true)) {
            return;
        }

        // Read before the turn runs. Every combatant acts once per round, so a creature's
        // first turn is always in round 1 — but a turn that closes the round leaves the
        // counter reading 2 by the time it is over.
        int round = engine.combat().view().round();

        turns.submit(() -> {
            var theirs = new Beats(ctx);
            try {
                engine.combat().runAutomaticTurns(theirs, WsHandler::beat);
            } catch (Exception e) {
                log.error("enemy turn failed", e);
                send(ctx, new ServerMessage.Error("The goblin froze: " + e.getMessage()));
                return;
            } finally {
                automatic.set(false);
            }

            // The enemy's whole turn as one call — move and swing together, never one call
            // each. The client is still animating it, which is the cover the prose model
            // needs, exactly as the dice cover an exploration turn.
            //
            // Its first turn is always narrated: that is where the creature gets a voice and a
            // shape, and it is the half of T11 that actually lands. After that, only the beats
            // worth stopping for. Narrating every turn meant 226 characters of prose for a
            // swing that missed, every time — six straight misses became six paragraphs about
            // nothing happening, and a narrator filling silence is worse than the silence.
            if (round == 1 || theirs.dramatic) {
                narrate(ctx, theirs.facts);
            }
        });
    }

    private void narrate(WsContext ctx, List<String> facts) {
        if (dm == null || facts.isEmpty()) {
            return;
        }
        turns.submit(() -> dm.narrateCombat(facts, turnSink(ctx)));
    }

    /**
     * Sends what a combat action produced, in the order it produced it, and keeps the plain-language
     * facts for the narrator.
     *
     * <p>{@code dramatic} is decided structurally rather than by reading the sentences: a natural
     * 20 or 1, or something reaching zero hit points. Sniffing the prose for the word "critical"
     * would break the moment anyone reworded a beat.
     */
    private final class Beats implements CombatSink {

        private final WsContext ctx;
        private final List<String> facts = new java.util.ArrayList<>();
        private boolean dramatic;

        Beats(WsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void diffs(List<Diff> diffs) {
            for (Diff diff : diffs) {
                if (diff instanceof Diff.StatChanged stat
                        && "hp".equals(stat.stat()) && stat.to() <= 0) {
                    dramatic = true;
                }
            }
            sendDiffs(ctx, diffs);
        }

        @Override
        public void roll(dm.model.RollResult result) {
            if (result.outcome() == Outcome.CRIT || result.outcome() == Outcome.CRIT_FAIL) {
                dramatic = true;
            }
            send(ctx, new ServerMessage.Roll(result));
        }

        @Override
        public void beat(String fact) {
            facts.add(fact);
        }
    }

    private static void beat() {
        try {
            Thread.sleep(BEAT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        // Same reason the engine refuses a move: a dead fighter has no turns left to take, and
        // a DM asked to narrate one would invent a living player to narrate it for.
        if (engine.state().find(actorId).filter(Entity::isAlive).isEmpty()) {
            send(ctx, new ServerMessage.Error("Roderick is dead. Descend again to start a new session."));
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
                // A DM turn can start a fight, and whatever it started the fight against may go
                // first. This is the only place that would ever notice.
                handOff(ctx);
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
