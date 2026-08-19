package dm.ai;

import java.util.List;

/**
 * The seam the design doc asks for: "Hosted API, behind an interface. Swap to local is config,
 * not code." The model id is a config string and never a literal.
 */
public interface DmClient {

    /**
     * Streams one DM turn. Text deltas arrive as the model produces them — the &lt;800ms
     * first-token budget depends on this never buffering a whole response.
     */
    void streamTurn(List<ChatMessage> conversation, DmListener listener);

    /** What the model is currently configured to be, for the debug HUD. */
    String modelId();

    /** One turn of conversation as the OpenAI-compatible wire format expects it. */
    record ChatMessage(String role, String content) {

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    interface DmListener {

        /** A fragment of narration. May be a partial word. */
        void onTextDelta(String delta);

        /** The turn finished cleanly. */
        void onComplete();

        /** No recovery in M0 — the caller surfaces a visible toast (shortcut #13). */
        void onError(Throwable error);
    }
}
