package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The seam the design doc asks for: "Hosted API, behind an interface. Swap to local is config,
 * not code." The model id is a config string and never a literal.
 */
public interface DmClient {

    /**
     * Streams one DM turn. Text deltas reach the listener as the model produces them — the
     * &lt;800ms first-token budget depends on this never buffering a whole response.
     *
     * @param tools tool definitions legal for this turn, rebuilt from live state
     * @return the assembled text and any tool calls the model wants applied
     */
    TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools, DmListener listener);

    /** What the model is currently configured to be, for the debug HUD. */
    String modelId();

    /**
     * Round-trip a trivial request, in milliseconds. Negative means it failed.
     *
     * <p>Exists because a Venice model can silently degrade from ~600ms to ~66s with full quota
     * remaining, and the symptom in the app is indistinguishable from "my code is slow".
     */
    long ping();

    /** One tool invocation the model is asking for. Arguments stay raw until validated. */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /** Everything one round-trip produced. */
    record TurnResult(String text, List<ToolCall> toolCalls) {

        public boolean wantsTools() {
            return !toolCalls.isEmpty();
        }
    }

    /**
     * One turn of conversation in the OpenAI-compatible wire format. Fields not relevant to a
     * given role are null — the client omits them when serialising.
     */
    record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, List.of(), null);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, List.of(), null);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, List.of(), null);
        }

        /** The assistant turn that requested tools. Must precede its results in the history. */
        public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls) {
            return new ChatMessage("assistant", content, calls, null);
        }

        /** What the engine decided, fed back so the model narrates what actually happened. */
        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, List.of(), toolCallId);
        }
    }

    interface DmListener {

        /** A fragment of narration. May be a partial word. */
        void onTextDelta(String delta);

        /** No recovery in M0 — the caller surfaces a visible toast (shortcut #13). */
        void onError(Throwable error);
    }
}
