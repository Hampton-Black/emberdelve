package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A {@link DmClient} that returns canned replies and never opens a socket.
 *
 * <p>The same argument as {@code ScriptedDiceRoller}: a test that fails should mean the code
 * changed, not that a model had an opinion or a provider had a bad minute.
 */
public final class ScriptedDmClient implements DmClient {

    private final Deque<String> replies = new ArrayDeque<>();
    private final List<List<ChatMessage>> conversations = new ArrayList<>();

    public ScriptedDmClient(String... responses) {
        replies.addAll(List.of(responses));
    }

    /** Every conversation this client was handed, in the order they arrived. */
    public List<List<ChatMessage>> conversations() {
        return List.copyOf(conversations);
    }

    @Override
    public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                 DmListener listener) {
        conversations.add(List.copyOf(conversation));
        String reply = replies.isEmpty() ? "" : replies.poll();
        listener.onTextDelta(reply);
        return new TurnResult(reply, List.of());
    }

    @Override
    public String modelId() {
        return "scripted";
    }

    @Override
    public long ping() {
        return 0;
    }
}
