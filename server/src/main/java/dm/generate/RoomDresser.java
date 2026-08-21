package dm.generate;

import dm.ai.DmClient;
import dm.model.Prop;
import dm.wire.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one LLM call in generation. Decides what a room is; never decides what is in it.
 *
 * <p>The division is the point. The generator has already placed every object, so the model
 * cannot conjure a prop the renderer has no mesh for or the engine has no obstruction rule for
 * — it can only describe what it is handed. That is invariant #7 with prop ids as the closed
 * set, and it is aimed at a failure measured during M0: a tool-reliable model that invented
 * "a small silver disc, half-buried in ash" and a trail of footprints, neither of which existed.
 *
 * <p>A dressing that cannot be parsed falls back rather than throwing. An undressed room is
 * playable and dull; no room at all is a crash on the way into a door.
 */
public final class RoomDresser {

    private static final Logger log = LoggerFactory.getLogger(RoomDresser.class);

    private final DmClient client;
    private final String prompt;

    public RoomDresser(DmClient client, String prompt) {
        this.client = client;
        this.prompt = prompt;
    }

    public Dressing dress(GeneratedRoom room) {
        var conversation = List.of(
                DmClient.ChatMessage.system(prompt),
                DmClient.ChatMessage.user(describe(room)));

        // Both methods are abstract on DmListener, so neither can be omitted. Dressing does not
        // stream to anyone — the room is not on screen yet — so the deltas are dropped and an
        // error falls through to the catch below.
        var listener = new DmClient.DmListener() {
            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onError(Throwable error) {
                log.warn("dress pass stream error: {}", error.toString());
            }
        };

        String reply;
        try {
            reply = client.streamTurn(conversation, null, listener).text();
        } catch (RuntimeException e) {
            log.warn("dress pass failed for {}: {}", room.roomId(), e.toString());
            return fallback();
        }
        return parse(reply, room);
    }

    /** What the model is allowed to know: shape, surfaces, and the ids it may describe. */
    private static String describe(GeneratedRoom room) {
        String props = room.props().stream()
                .map(p -> "- " + p.id() + " (" + p.type().name().toLowerCase() + ")")
                .collect(Collectors.joining("\n"));

        return """
                The room is %d by %d squares. The floor is %s, the walls are %s, and it is %s.

                Objects in it, by id — describe these and only these:
                %s
                """.formatted(
                room.shape().width(), room.shape().height(),
                room.shape().floorType().name().toLowerCase().replace('_', ' '),
                room.shape().wallType().name().toLowerCase(),
                room.shape().lighting().name().toLowerCase(),
                props);
    }

    private Dressing parse(String reply, GeneratedRoom room) {
        // Models wrap JSON in a fence however firmly you ask them not to. M0 learned this the
        // hard way when a prompt's own example fence came back as narration and was read aloud.
        String json = reply.strip()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("```$", "")
                .strip();

        try {
            var node = Json.MAPPER.readTree(json);
            Set<String> known = room.props().stream().map(Prop::id).collect(Collectors.toSet());

            var descriptions = new LinkedHashMap<String, String>();
            var props = node.path("props");
            props.fieldNames().forEachRemaining(id -> {
                if (known.contains(id)) {
                    descriptions.put(id, props.get(id).asText());
                } else {
                    log.warn("dress pass invented a prop id, dropped: {}", id);
                }
            });

            String name = node.path("name").asText("");
            if (name.isBlank()) {
                return fallback();
            }
            return new Dressing(
                    name,
                    node.path("overview").asText(""),
                    node.path("sensory").asText(""),
                    Map.copyOf(descriptions));
        } catch (Exception e) {
            log.warn("unparseable dressing for {}: {}", room.roomId(), e.toString());
            return fallback();
        }
    }

    private static Dressing fallback() {
        return new Dressing(
                "An Unnamed Chamber",
                "A stone room, silent and unremarkable.",
                "Cold air and the smell of dust.",
                Map.of());
    }
}
