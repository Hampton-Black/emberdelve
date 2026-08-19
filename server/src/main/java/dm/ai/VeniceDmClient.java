package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Streaming chat against Venice's OpenAI-compatible endpoint.
 *
 * <p>Hand-rolled over {@link HttpClient} rather than pulled from an SDK: the wire format is
 * stable, SSE parsing is short, and M0 wants full control of the path that owns the &lt;800ms
 * first-token budget.
 */
public final class VeniceDmClient implements DmClient {

    private static final Logger log = LoggerFactory.getLogger(VeniceDmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DONE = "[DONE]";

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public VeniceDmClient(Config config) {
        this.apiKey = config.require("VENICE_API_KEY");
        this.baseUrl = trimTrailingSlash(config.get("VENICE_BASE_URL", "https://api.venice.ai/api/v1"));
        this.model = config.get("DM_MODEL", "claude-opus-5");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        log.info("DmClient -> {} model={}", baseUrl, model);
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public void streamTurn(List<ChatMessage> conversation, DmListener listener) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body(conversation)))
                    .build();

            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() / 100 != 2) {
                String detail = response.body().limit(20).reduce("", (a, b) -> a + b);
                throw new IllegalStateException(
                        "Venice returned " + response.statusCode() + ": " + detail);
            }

            consume(response.body(), listener);
            listener.onComplete();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(e);
        } catch (Exception e) {
            log.error("DM turn failed", e);
            listener.onError(e);
        }
    }

    /** Parses the SSE stream, forwarding each content delta the instant it arrives. */
    private void consume(Stream<String> lines, DmListener listener) {
        lines.forEach(line -> {
            if (!line.startsWith("data:")) {
                return;
            }
            String payload = line.substring(5).strip();
            if (payload.isEmpty() || DONE.equals(payload)) {
                return;
            }
            try {
                JsonNode delta = MAPPER.readTree(payload)
                        .path("choices").path(0).path("delta");

                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    listener.onTextDelta(text);
                }
            } catch (Exception e) {
                // A malformed chunk should not abort a turn that is otherwise streaming fine.
                log.warn("skipping unparseable stream chunk", e);
            }
        });
    }

    private String body(List<ChatMessage> conversation) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("max_tokens", 800);
        root.put("temperature", 0.85);

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : conversation) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }

        // Venice-specific: no system-prompt injection, and do not persist the conversation.
        ObjectNode venice = root.putObject("venice_parameters");
        venice.put("include_venice_system_prompt", false);

        return root.toString();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
