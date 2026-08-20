package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dm.wire.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String DONE = "[DONE]";

    /** Roughly the size of a real DM system prompt, so the warm-check exercises the same path. */
    private static final String PING_PADDING =
            ("You are a health check. Reply with the single word OK and nothing else. "
                    + "The following text is padding to approximate a real prompt. ").repeat(40);

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public VeniceDmClient(Config config, String model, Duration timeout) {
        this.apiKey = config.require("VENICE_API_KEY");
        this.baseUrl = trimTrailingSlash(config.get("VENICE_BASE_URL", "https://api.venice.ai/api/v1"));
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        log.info("DmClient -> {} model={} timeout={}s", baseUrl, model, timeout.toSeconds());
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public long ping() {
        // Deliberately not a trivial request. A 4-token ping reported this endpoint healthy at
        // 621ms and it then took 34s on a real turn — large prompts and small ones do not
        // degrade together, so the check has to look like the thing it is checking.
        var body = Json.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 8);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", PING_PADDING);
        messages.addObject().put("role", "user").put("content", "Reply with the word OK.");

        long started = System.nanoTime();
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2
                    ? (System.nanoTime() - started) / 1_000_000
                    : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                 DmListener listener) {
        var text = new StringBuilder();
        var calls = new ToolCallAccumulator();

        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body(conversation, tools)))
                    .build();

            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() / 100 != 2) {
                String detail = response.body().limit(20).reduce("", (a, b) -> a + b);
                throw new IllegalStateException(
                        "Venice returned " + response.statusCode() + ": " + detail);
            }

            consume(response.body(), text, calls, listener);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(e);
        } catch (java.net.http.HttpTimeoutException e) {
            // Observed in practice: a Venice model can degrade from ~600ms to ~50s with full
            // quota remaining. Fail fast rather than hanging the turn behind a dead endpoint.
            log.error("model '{}' did not respond within {}s", model, timeout.toSeconds());
            listener.onError(new IllegalStateException(
                    "Model '" + model + "' timed out after " + timeout.toSeconds()
                            + "s. It may be cold or overloaded — try another DM_MODEL_*.", e));
        } catch (Exception e) {
            log.error("DM turn failed", e);
            listener.onError(e);
        }

        return new TurnResult(text.toString(), calls.toList());
    }

    /** Parses the SSE stream, forwarding each content delta the instant it arrives. */
    private void consume(Stream<String> lines, StringBuilder text,
                         ToolCallAccumulator calls, DmListener listener) {
        lines.forEach(line -> {
            if (!line.startsWith("data:")) {
                return;
            }
            String payload = line.substring(5).strip();
            if (payload.isEmpty() || DONE.equals(payload)) {
                return;
            }
            try {
                JsonNode delta = Json.MAPPER.readTree(payload).path("choices").path(0).path("delta");

                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    text.append(content);
                    listener.onTextDelta(content);
                }

                // Tool call arguments stream in fragments and must be reassembled by index.
                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    toolCalls.forEach(calls::accept);
                }
            } catch (Exception e) {
                // A malformed chunk should not abort a turn that is otherwise streaming fine.
                log.warn("skipping unparseable stream chunk", e);
            }
        });
    }

    private String body(List<ChatMessage> conversation, JsonNode tools) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);
        root.put("max_tokens", 800);
        root.put("temperature", 0.85);

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : conversation) {
            messages.add(serialize(message));
        }

        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            root.set("tools", tools);
            root.put("tool_choice", "auto");
        }

        // Venice-specific: no system-prompt injection.
        root.putObject("venice_parameters").put("include_venice_system_prompt", false);

        return root.toString();
    }

    private ObjectNode serialize(ChatMessage message) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("role", message.role());

        // An assistant turn carrying tool calls sends content: null, not "".
        if (message.content() == null || message.content().isBlank()) {
            node.putNull("content");
        } else {
            node.put("content", message.content());
        }

        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }

        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode entry = calls.addObject();
                entry.put("id", call.id());
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.argumentsJson());
            }
        }
        return node;
    }

    /**
     * Reassembles streamed tool calls. The name arrives once, the arguments arrive as JSON
     * fragments across many chunks, and both are keyed by an index rather than an id.
     */
    private static final class ToolCallAccumulator {

        private record Partial(String id, String name, StringBuilder arguments) {
        }

        private final Map<Integer, Partial> byIndex = new LinkedHashMap<>();

        void accept(JsonNode node) {
            int index = node.path("index").asInt(0);
            Partial partial = byIndex.computeIfAbsent(index,
                    i -> new Partial(null, null, new StringBuilder()));

            String id = node.path("id").asText(null);
            String name = node.path("function").path("name").asText(null);
            String arguments = node.path("function").path("arguments").asText("");

            if (id != null || name != null) {
                partial = new Partial(
                        id != null ? id : partial.id(),
                        name != null ? name : partial.name(),
                        partial.arguments());
                byIndex.put(index, partial);
            }
            if (!arguments.isEmpty()) {
                partial.arguments().append(arguments);
            }
        }

        List<ToolCall> toList() {
            var out = new ArrayList<ToolCall>();
            byIndex.forEach((index, partial) -> {
                if (partial.name() != null) {
                    out.add(new ToolCall(
                            partial.id() != null ? partial.id() : "call_" + index,
                            partial.name(),
                            partial.arguments().toString()));
                }
            });
            return List.copyOf(out);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
