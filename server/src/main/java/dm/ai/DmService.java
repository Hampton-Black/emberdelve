package dm.ai;

import dm.content.RoomDefinition;
import dm.engine.GameEngine;
import dm.model.Combatant;
import dm.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs one DM turn as two phases against two different models.
 *
 * <p><b>Phase 1 — mechanics, fast model.</b> Decides tool calls and nothing else; any prose it
 * emits is discarded. Loops until it stops asking for tools, seeing each engine result before
 * choosing the next call. This is the phase the first-feedback budget applies to, because it is
 * what puts dice on the table.
 *
 * <p><b>Phase 2 — narration, strong model.</b> Writes the prose, with the engine's actual results
 * as context. It runs <em>while the dice are still animating</em>, which is what buys it the time
 * to be good rather than merely fast.
 *
 * <p>Narration stays with exactly one model, so the tone cannot drift between turns — the failure
 * {@code ai-dm-system-design.md} §10 warns is audible immediately.
 *
 * <p>Context is the full transcript with no compaction (shortcut #11).
 */
public final class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);

    /** Bounded so a confused model cannot spin through tool rounds forever. */
    private static final int MAX_TOOL_ROUNDS = 4;

    /**
     * What the DM is asked at the top of the session. Phrased as a beat to narrate rather than
     * as a request for a room description, because asking a model to "describe the room" gets
     * an estate-agent listing of its contents.
     */
    private static final String OPENING = "The party has just come through the entrance and is "
            + "standing inside. Open the session: what they walk into.";

    /**
     * The directive for a combat beat. Shorter than the standing three-sentence rule on purpose:
     * a fight is a rally, and a narrator who writes a paragraph between swings stops the rally.
     */
    private static final String COMBAT_BEAT = "Narrate this moment of the fight. "
            + "One or two sentences. Do not say whose turn it is. "
            + "Describe only what the facts state — no swing they do not mention, "
            + "no wound they do not report.";

    /** §7: reject, let it retry once, then take the tools away. */
    private static final int REJECTIONS_BEFORE_DEGRADING = 2;

    private final DmClient toolClient;
    private final DmClient proseClient;
    private final GameEngine engine;
    private final ToolDispatcher dispatcher;
    private final String toolPrompt;
    private final String prosePrompt;
    private final TurnMetrics metrics = new TurnMetrics();

    /** The durable conversation: what the player said, and what the narrator said back. */
    private final List<DmClient.ChatMessage> history = new ArrayList<>();

    /** The room is described once per session, not once per reconnect. */
    private final java.util.concurrent.atomic.AtomicBoolean opened =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * One narration at a time, across every path that produces any.
     *
     * <p>Two narrations in flight interleave their sentences into nonsense, and the transcript is
     * a single ordered list with nowhere to put a second voice. Who waits and who gives up is not
     * symmetric: narration the <em>player</em> asked for waits its turn, because dropping it would
     * silently swallow something they typed; narration the <em>engine</em> generated gives up,
     * because a swing narrated ten seconds late is worse than a swing narrated not at all.
     */
    private final java.util.concurrent.locks.ReentrantLock narrating =
            new java.util.concurrent.locks.ReentrantLock();

    public DmService(DmClient toolClient, DmClient proseClient, GameEngine engine,
                     String toolPrompt, String prosePrompt) {
        this.toolClient = toolClient;
        this.proseClient = proseClient;
        this.engine = engine;
        this.dispatcher = new ToolDispatcher(engine);
        this.toolPrompt = toolPrompt;
        this.prosePrompt = prosePrompt;
    }

    public String modelId() {
        return toolClient.modelId() + " + " + proseClient.modelId();
    }

    /** Ping both endpoints in the background and shout if either is unhealthy. */
    public void warmCheck() {
        Thread.ofVirtual().name("warm-check").start(() -> {
            check("tools", toolClient);
            check("prose", proseClient);
        });
    }

    private static void check(String role, DmClient client) {
        long ms = client.ping();
        if (ms < 0) {
            log.error("{} model '{}' is NOT RESPONDING", role, client.modelId());
        } else if (ms > 5_000) {
            log.error("{} model '{}' answered a 4-token request in {}ms. It is cold or "
                            + "overloaded — every turn will be unusable. Switch models.",
                    role, client.modelId(), ms);
        } else if (ms > 2_000) {
            log.warn("{} model '{}' is sluggish: {}ms for a 4-token request",
                    role, client.modelId(), ms);
        } else {
            log.info("{} model '{}' healthy: {}ms", role, client.modelId(), ms);
        }
    }

    /**
     * Narrates the room unprompted, before the player has typed anything (§2 step 2).
     *
     * <p>Prose only — there is nothing to adjudicate yet, so the mechanics model is not consulted
     * and the whole turn is one streaming call. Runs at most once per process: the client
     * reconnects on every dropped socket and on every dev-server reload, and a DM that
     * re-describes the room each time would be a bug that reads as a haunting.
     *
     * @param force ignores the once-per-session guard, for iterating on the opening by hand
     */
    public void openScene(TurnSink sink, boolean force) {
        if (!opened.compareAndSet(false, true) && !force) {
            return;
        }

        var failed = new boolean[]{false};
        long started = System.nanoTime();

        narrating.lock();
        try {
            var prose = runProsePhase(OPENING, List.of(), sink, failed);
            if (failed[0]) {
                return;
            }

            // The instruction itself stays out of the history: it is stage direction, and replaying
            // it every turn would have the model treating it as something the player said.
            history.add(DmClient.ChatMessage.assistant(prose.text()));

            log.info("OPENING  first token {}ms  total {}ms  {} chars",
                    prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                    prose.text().length());
        } finally {
            narrating.unlock();
        }
        sink.complete();
    }

    /**
     * Narrates a moment of combat: the enemy's whole turn as one call, or a kill, or a crit.
     *
     * <p>Prose only, like the opening — the engine has already decided everything and there is
     * nothing to adjudicate. The facts arrive as plain sentences from {@code CombatSink.beat};
     * this turns them into something worth hearing and never the other way round.
     *
     * <p><b>One at a time.</b> A second beat arriving mid-narration is dropped rather than queued.
     * The player can click faster than the prose model can write, and two overlapping calls would
     * interleave their sentences into nonsense — while a queue would narrate a swing that landed
     * ten seconds ago. Silence is the better failure.
     *
     * @return whether it ran
     */
    public boolean narrateCombat(List<String> facts, TurnSink sink) {
        if (facts.isEmpty()) {
            return false;
        }
        if (!narrating.tryLock()) {
            log.info("dropped a combat beat, the DM is already speaking: {}", facts);
            return false;
        }

        try {
            var failed = new boolean[]{false};
            long started = System.nanoTime();
            var prose = runProsePhase(COMBAT_BEAT, facts, sink, failed);
            if (failed[0]) {
                return false;
            }

            // Only the reply is kept. The directive is stage direction, and replaying it each
            // turn would have the model treating it as something the player said.
            history.add(DmClient.ChatMessage.assistant(prose.text()));

            log.info("COMBAT   first token {}ms  total {}ms  {} chars  <- {}",
                    prose.firstTokenMs(), (System.nanoTime() - started) / 1_000_000,
                    prose.text().length(), facts);
            sink.complete();
            return true;
        } finally {
            narrating.unlock();
        }
    }

    /**
     * Handles a free-text turn. Blocking by design — each turn runs on its own virtual thread,
     * so the two-phase orchestration reads like ordinary sequential code.
     */
    public void handleFreeText(String actorId, String text, TurnSink sink) {
        engine.repo().append(Event.action(actorId, "said: " + text));

        // Waits rather than gives up. The tool phase runs inside the lock too: it puts dice on
        // the table, and dice landing under someone else's narration is the same collision.
        narrating.lock();
        try {
            handleFreeTextLocked(actorId, text, sink);
        } finally {
            narrating.unlock();
        }
    }

    private void handleFreeTextLocked(String actorId, String text, TurnSink sink) {
        var failed = new boolean[]{false};

        long toolStart = System.nanoTime();
        var mechanics = runToolPhase(text, sink, failed);
        long toolPhaseMs = (System.nanoTime() - toolStart) / 1_000_000;

        if (failed[0]) {
            return;
        }

        long proseStart = System.nanoTime();
        var prose = runProsePhase(text, mechanics.results(), sink, failed);
        long proseMs = (System.nanoTime() - proseStart) / 1_000_000;

        if (failed[0]) {
            return;
        }

        history.add(DmClient.ChatMessage.user(text));
        history.add(DmClient.ChatMessage.assistant(prose.text()));

        metrics.record(new TurnMetrics.TurnShape(
                mechanics.calls(), mechanics.applied(), mechanics.rejected(),
                mechanics.firstFeedbackMs(), toolPhaseMs, proseMs, prose.firstTokenMs()));

        sink.complete();
    }

    // ---- Phase 1: mechanics ----

    private record Mechanics(List<String> results, int calls, int applied, int rejected,
                             long firstFeedbackMs) {
    }

    private Mechanics runToolPhase(String text, TurnSink sink, boolean[] failed) {
        var results = new ArrayList<String>();
        var localRounds = new ArrayList<DmClient.ChatMessage>();
        long phaseStart = System.nanoTime();
        long firstFeedback = -1;
        int calls = 0;
        int applied = 0;
        int rejected = 0;

        // Prose from this phase is thrown away; a separate model does the writing.
        var discard = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onError(Throwable error) {
                failed[0] = true;
                sink.error(error);
            }
        };

        for (int round = 0; round < MAX_TOOL_ROUNDS && !failed[0]; round++) {
            // Rebuilt each round: revealing a prop removes it from the legal set.
            var tools = rejected >= REJECTIONS_BEFORE_DEGRADING
                    ? null
                    : ToolSchema.forTurn(engine);
            if (tools == null) {
                break;
            }

            var conversation = new ArrayList<DmClient.ChatMessage>();
            conversation.add(DmClient.ChatMessage.system(toolPrompt + "\n\n" + worldState()));
            conversation.addAll(history);
            conversation.add(DmClient.ChatMessage.user(text));
            conversation.addAll(localRounds);

            var result = toolClient.streamTurn(conversation, tools, discard);
            if (failed[0] || !result.wantsTools()) {
                break;
            }

            localRounds.add(
                    DmClient.ChatMessage.assistantToolCalls(result.text(), result.toolCalls()));

            for (var call : result.toolCalls()) {
                calls++;
                var outcome = dispatcher.dispatch(call);

                if (outcome.ok()) {
                    applied++;
                    results.add(outcome.message());
                } else {
                    rejected++;
                    log.warn("tool rejected: {}({}) -> {}",
                            call.name(), call.argumentsJson(), outcome.message());
                }

                boolean visible = !outcome.diffs().isEmpty() || !outcome.rolls().isEmpty();
                if (visible && firstFeedback < 0) {
                    firstFeedback = (System.nanoTime() - phaseStart) / 1_000_000;
                }

                if (!outcome.diffs().isEmpty()) {
                    sink.diffs(outcome.diffs());
                }
                // Pushing the rolls here is what starts the dice animating, which is the whole
                // reason the prose model is allowed to be slow.
                outcome.rolls().forEach(sink::roll);

                localRounds.add(DmClient.ChatMessage.toolResult(call.id(), outcome.message()));
            }
        }

        return new Mechanics(List.copyOf(results), calls, applied, rejected, firstFeedback);
    }

    // ---- Phase 2: narration ----

    private record Prose(String text, long firstTokenMs) {
    }

    private Prose runProsePhase(String text, List<String> mechanics, TurnSink sink,
                                boolean[] failed) {
        var narrated = new StringBuilder();
        var parser = new NarrationParser(liveSpeakers(), segment -> {
            narrated.append(segment.text());
            engine.repo().append(Event.narration(segment.speakerId(), segment.text()));
            sink.narration(segment);
        });

        long started = System.nanoTime();
        var firstTokenAt = new long[]{0};

        var listener = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
                if (firstTokenAt[0] == 0) {
                    firstTokenAt[0] = System.nanoTime();
                }
                parser.accept(delta);
            }

            @Override
            public void onError(Throwable error) {
                failed[0] = true;
                sink.error(error);
            }
        };

        var conversation = new ArrayList<DmClient.ChatMessage>();
        // World state is recomputed here: phase 1 may have spawned or revealed things.
        conversation.add(DmClient.ChatMessage.system(prosePrompt + "\n\n" + worldState()));
        conversation.addAll(history);
        conversation.add(DmClient.ChatMessage.user(text));

        if (!mechanics.isEmpty()) {
            // The engine's rulings, not the model's tool calls — the prose model never made
            // those, and replaying them as tool_calls confuses models that did not emit them.
            // Deliberately plain prose with no brackets, headings or bullets. An earlier
            // version wrapped this in [square brackets] and the model started emitting its own
            // bracketed stage directions, which the voice then read aloud. Models imitate the
            // shape of what you send them.
            conversation.add(DmClient.ChatMessage.user(
                    "The engine has already resolved this action. Narrate the following as "
                            + "something that has happened.\n\n"
                            + String.join("\n", mechanics)));
        }

        // No tools in this phase: narration only.
        proseClient.streamTurn(conversation, null, listener);
        parser.finish();

        long firstTokenMs = firstTokenAt[0] == 0 ? -1 : (firstTokenAt[0] - started) / 1_000_000;
        return new Prose(narrated.toString(), firstTokenMs);
    }

    // ---- Context ----

    private String worldState() {
        RoomDefinition room = engine.room();
        var revealed = engine.repo().revealedPropIds();
        var sb = new StringBuilder();

        sb.append("# Current state\n\n");
        sb.append("## Room: ").append(room.name()).append("\n\n");
        sb.append(room.dmNotes().overview()).append("\n\n");
        sb.append(room.dmNotes().sensory()).append("\n\n");

        sb.append("## What the player can see\n\n");
        for (var prop : room.props()) {
            if (!prop.hidden() || revealed.contains(prop.id())) {
                sb.append("- `").append(prop.id()).append("` at (")
                        .append(prop.x()).append(",").append(prop.y()).append(") — ")
                        .append(prop.description()).append("\n");
            }
        }

        var hidden = room.props().stream()
                .filter(p -> p.hidden() && !revealed.contains(p.id()))
                .toList();
        if (!hidden.isEmpty()) {
            sb.append("\n## Hidden — the player cannot see these yet\n\n");
            sb.append("Do not mention these unless the player finds them.\n\n");
            for (var prop : hidden) {
                sb.append("- `").append(prop.id()).append("` at (")
                        .append(prop.x()).append(",").append(prop.y()).append(") — ")
                        .append(prop.revealHint())
                        .append(" Once revealed: ").append(prop.description()).append("\n");
            }
        }

        sb.append("\n## Secrets you know and the player does not\n\n");
        // A secret stops being one the moment it happens. This note promises a goblin that is
        // still in the sarcophagus and will come out fighting — true until it does, and after
        // that a contradiction handed to the model every turn, under a heading telling it this
        // is something the player has not seen yet. Observed re-introducing Vessk as a fresh
        // menace under the lid several turns after the player had killed him.
        //
        // The door note is not like this and stays: "the north door never opens" is a standing
        // constraint rather than a pending beat, and it is exactly as true on the last turn as
        // on the first.
        if (engine.repo().find("goblin").isEmpty()) {
            sb.append(room.dmNotes().theSarcophagus()).append("\n\n");
        }
        sb.append(room.dmNotes().theDoor()).append("\n\n");

        sb.append("## Entities present\n\n");
        for (var entity : engine.repo().entities()) {
            sb.append("- `").append(entity.id()).append("` — ").append(entity.name())
                    .append(", ").append(entity.hp()).append("/").append(entity.maxHp())
                    .append(" hp, at (").append(entity.x()).append(",").append(entity.y())
                    .append(")").append(entity.isPlayerControlled() ? " [the player]" : "")
                    .append(entity.isAlive() ? "" : " [dead]")
                    // What they look like and what they are holding. Props have carried their
                    // description here from the start and creatures never did, so the narrator
                    // was writing fights between two entities it knew nothing about beyond a
                    // name — observed giving the fighter's killing blow a scimitar, which is
                    // the goblin's kind of weapon and in fact nobody's: the fighter carries a
                    // longsword and the goblin a notched shortsword, and both facts were
                    // sitting unread in the content files.
                    .append(". ").append(engine.content().entity(entity.kind()).description())
                    .append("\n");
        }

        var combat = engine.combat();
        if (combat.isActive()) {
            var view = combat.view();
            sb.append("\n## The fight\n\n");
            sb.append("Round ").append(view.round()).append(". Initiative order: ")
                    .append(view.order().stream().map(Combatant::name)
                            .collect(Collectors.joining(", ")))
                    .append(".\n");
            sb.append("It is `").append(view.activeId()).append("`'s turn.\n");
            sb.append("Never narrate a turn that has not happened, and never state the order, "
                    + "the round number, or anyone's hit points. Those are on screen.\n");
        }

        sb.append("\nThe grid is ").append(room.width()).append("x").append(room.height())
                .append(", x eastward and y northward. Mode: ").append(engine.mode()).append(".\n");

        return sb.toString();
    }

    /** Speaker ids the narration parser will honour — everything else falls back to narrator. */
    private Set<String> liveSpeakers() {
        return engine.repo().entities().stream()
                .map(e -> e.id().toLowerCase())
                .collect(Collectors.toSet());
    }
}
