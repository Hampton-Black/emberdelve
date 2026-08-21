package dm;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ai.DmClient;

import java.util.ArrayDeque;
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

    public ScriptedDmClient(String... responses) {
        replies.addAll(List.of(responses));
    }

    @Override
    public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                 DmListener listener) {
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
