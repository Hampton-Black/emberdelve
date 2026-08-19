package dm.ai;

import dm.content.RoomDefinition;
import dm.engine.GameEngine;
import dm.model.Event;
import dm.model.NarrationSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs one DM turn: assembles context, streams the model, splits narration by speaker, and
 * pushes segments out as they complete.
 *
 * <p>Context is the full transcript with no compaction (shortcut #11) — M0 sessions are five
 * minutes long, so the cheapest correct thing is to send everything.
 */
public final class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);

    private final DmClient client;
    private final GameEngine engine;
    private final String systemPrompt;

    /** The running conversation. Rebuilt system message aside, this only ever grows in M0. */
    private final List<DmClient.ChatMessage> history = new ArrayList<>();

    public DmService(DmClient client, GameEngine engine, String systemPrompt) {
        this.client = client;
        this.engine = engine;
        this.systemPrompt = systemPrompt;
    }

    public String modelId() {
        return client.modelId();
    }

    /**
     * Handles a free-text turn. Blocking by design — each session runs on its own virtual
     * thread, so the streaming orchestration reads like ordinary sequential code.
     */
    public void handleFreeText(
            String actorId,
            String text,
            Consumer<NarrationSegment> onSegment,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        engine.repo().append(Event.action(actorId, "said: " + text));
        history.add(DmClient.ChatMessage.user(text));

        var reply = new StringBuilder();
        var parser = new NarrationParser(liveSpeakers(), segment -> {
            reply.append(segment.text());
            engine.repo().append(Event.narration(segment.speakerId(), segment.text()));
            onSegment.accept(segment);
        });

        long started = System.nanoTime();
        var firstTokenAt = new long[]{0};

        client.streamTurn(conversation(), new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
                if (firstTokenAt[0] == 0) {
                    firstTokenAt[0] = System.nanoTime();
                }
                parser.accept(delta);
            }

            @Override
            public void onComplete() {
                parser.finish();
                history.add(DmClient.ChatMessage.assistant(reply.toString()));

                // The <800ms budget is a gate, so measure it every turn rather than guessing.
                log.info("turn complete: first token {}ms, total {}ms, {} chars",
                        (firstTokenAt[0] - started) / 1_000_000,
                        (System.nanoTime() - started) / 1_000_000,
                        reply.length());
                onComplete.run();
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
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
                sb.append("- `").append(prop.id()).append("` — ")
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
                sb.append("- `").append(prop.id()).append("` — ").append(prop.revealHint())
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
                .append(". Mode: ").append(engine.mode()).append(".\n");

        return sb.toString();
    }

    /** Speaker ids the narration parser will honour — everything else falls back to narrator. */
    private Set<String> liveSpeakers() {
        return engine.repo().entities().stream()
                .map(e -> e.id().toLowerCase())
                .collect(Collectors.toSet());
    }
}
