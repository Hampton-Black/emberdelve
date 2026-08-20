package dm.ai;

import dm.content.RoomDefinition;
import dm.engine.GameEngine;
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
     * Handles a free-text turn. Blocking by design — each turn runs on its own virtual thread,
     * so the two-phase orchestration reads like ordinary sequential code.
     */
    public void handleFreeText(String actorId, String text, TurnSink sink) {
        engine.repo().append(Event.action(actorId, "said: " + text));

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

                boolean visible = !outcome.diffs().isEmpty() || outcome.roll().isPresent();
                if (visible && firstFeedback < 0) {
                    firstFeedback = (System.nanoTime() - phaseStart) / 1_000_000;
                }

                if (!outcome.diffs().isEmpty()) {
                    sink.diffs(outcome.diffs());
                }
                // Pushing the roll here is what starts the dice animating, which is the whole
                // reason the prose model is allowed to be slow.
                outcome.roll().ifPresent(sink::roll);

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
        sb.append(room.dmNotes().theSarcophagus()).append("\n\n");
        sb.append(room.dmNotes().theDoor()).append("\n\n");

        sb.append("## Entities present\n\n");
        for (var entity : engine.repo().entities()) {
            sb.append("- `").append(entity.id()).append("` — ").append(entity.name())
                    .append(", ").append(entity.hp()).append("/").append(entity.maxHp())
                    .append(" hp, at (").append(entity.x()).append(",").append(entity.y())
                    .append(")").append(entity.isPlayerControlled() ? " [the player]" : "")
                    .append("\n");
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
