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
 * Runs one DM turn: assembles context, streams the model, applies whatever tools it asks for,
 * and feeds the engine's answers back so the narration describes what actually happened.
 *
 * <p>Context is the full transcript with no compaction (shortcut #11) — M0 sessions are five
 * minutes long, so the cheapest correct thing is to send everything.
 */
public final class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);

    /** Initial call plus tool round-trips. Bounded so a confused model cannot loop forever. */
    private static final int MAX_ROUNDS = 4;

    /** §7: reject, let it retry once, then take the tools away and let it narrate. */
    private static final int REJECTIONS_BEFORE_DEGRADING = 2;

    private final DmClient client;
    private final GameEngine engine;
    private final ToolDispatcher dispatcher;
    private final String systemPrompt;

    /** The running conversation. System message aside, this only ever grows in M0. */
    private final List<DmClient.ChatMessage> history = new ArrayList<>();

    public DmService(DmClient client, GameEngine engine, String systemPrompt) {
        this.client = client;
        this.engine = engine;
        this.dispatcher = new ToolDispatcher(engine);
        this.systemPrompt = systemPrompt;
    }

    public String modelId() {
        return client.modelId();
    }

    /**
     * Handles a free-text turn. Blocking by design — each turn runs on its own virtual thread,
     * so the streaming orchestration reads like ordinary sequential code.
     */
    public void handleFreeText(String actorId, String text, TurnSink sink) {
        engine.repo().append(Event.action(actorId, "said: " + text));
        history.add(DmClient.ChatMessage.user(text));

        var narrated = new StringBuilder();
        var parser = new NarrationParser(liveSpeakers(), segment -> {
            narrated.append(segment.text());
            engine.repo().append(Event.narration(segment.speakerId(), segment.text()));
            sink.narration(segment);
        });

        long started = System.nanoTime();
        var firstTokenAt = new long[]{0};
        var failed = new boolean[]{false};

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

        int rejections = 0;
        int toolCallsApplied = 0;

        for (int round = 0; round < MAX_ROUNDS && !failed[0]; round++) {
            // Tools are rebuilt each round: revealing a prop removes it from the legal set.
            var tools = rejections >= REJECTIONS_BEFORE_DEGRADING
                    ? null
                    : ToolSchema.forTurn(engine);

            var result = client.streamTurn(conversation(), tools, listener);
            if (failed[0]) {
                break;
            }

            if (!result.wantsTools()) {
                history.add(DmClient.ChatMessage.assistant(result.text()));
                break;
            }

            history.add(DmClient.ChatMessage.assistantToolCalls(result.text(), result.toolCalls()));

            for (var call : result.toolCalls()) {
                var outcome = dispatcher.dispatch(call);

                if (outcome.ok()) {
                    toolCallsApplied++;
                } else {
                    rejections++;
                    log.warn("tool rejected: {}({}) -> {}",
                            call.name(), call.argumentsJson(), outcome.message());
                }

                if (!outcome.diffs().isEmpty()) {
                    sink.diffs(outcome.diffs());
                }
                outcome.roll().ifPresent(sink::roll);

                history.add(DmClient.ChatMessage.toolResult(call.id(), outcome.message()));
            }
        }

        parser.finish();

        if (!failed[0]) {
            // The <800ms budget is a gate, so measure it every turn rather than guessing.
            log.info("turn: first token {}ms, total {}ms, {} chars, {} tools applied, {} rejected",
                    firstTokenAt[0] == 0 ? -1 : (firstTokenAt[0] - started) / 1_000_000,
                    (System.nanoTime() - started) / 1_000_000,
                    narrated.length(),
                    toolCallsApplied,
                    rejections);
            sink.complete();
        }
    }

    /** System message is rebuilt each turn so the model always sees current world state. */
    private List<DmClient.ChatMessage> conversation() {
        var messages = new ArrayList<DmClient.ChatMessage>();
        messages.add(DmClient.ChatMessage.system(systemPrompt + "\n\n" + worldState()));
        messages.addAll(history);
        return messages;
    }

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
            sb.append("Do not mention these unless the player finds them. ")
                    .append("If they search in roughly the right place, call `reveal_prop`.\n\n");
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
