package dm.generate;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dress pass, with no network in it.
 *
 * <p>Invariant #7 says no free-form string from the model reaches the engine. Prop ids are the
 * closed set here: the generator has already decided what is in the room, and the model may
 * only describe what it is handed. A model that invents "silver-disc-0" gets it dropped —
 * `qwen3-next-80b` invented a small silver disc and a trail of footprints during M0, which is
 * the exact behaviour this rejects.
 */
class RoomDresserTest {

    private static final String PROMPT = new ContentLoader().prompt("dress-room");

    private static GeneratedRoom room() {
        return new RoomGenerator(new ContentLoader()).generate("crypt", 5);
    }

    @Test
    @DisplayName("a well-formed reply becomes a Dressing")
    void parsesReply() {
        var target = room();
        var firstProp = target.props().get(0).id();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber where the walls sweat cold water.",
                  "sensory": "Dripping. The smell of wet stone and old iron.",
                  "props": { "%s": "Slick with condensation." }
                }
                """.formatted(firstProp);

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertEquals("The Weeping Vault", dressing.name());
        assertTrue(dressing.overview().contains("burial chamber"));
        assertEquals("Slick with condensation.", dressing.propDescriptions().get(firstProp));
    }

    @Test
    @DisplayName("a prop id the generator never placed is dropped")
    void rejectsInventedPropIds() {
        var target = room();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber.",
                  "sensory": "Dripping.",
                  "props": { "silver-disc-0": "A small silver disc, half-buried in ash." }
                }
                """;

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertFalse(dressing.propDescriptions().containsKey("silver-disc-0"),
                "the model invented a prop and it survived validation");
        assertTrue(dressing.propDescriptions().isEmpty());
    }

    @Test
    @DisplayName("a reply wrapped in a markdown fence still parses")
    void toleratesCodeFences() {
        var target = room();
        var json = """
                ```json
                {
                  "name": "The Ossuary",
                  "overview": "Bones stacked to the ceiling.",
                  "sensory": "Dust.",
                  "props": {}
                }
                ```
                """;

        var dressing = new RoomDresser(new ScriptedDmClient(json), PROMPT).dress(target);

        assertEquals("The Ossuary", dressing.name());
    }

    @Test
    @DisplayName("the dresser is told where each prop stands")
    void sendsPropPositions() {
        var target = room();
        var client = new ScriptedDmClient("""
                { "name": "The Weeping Vault", "overview": "A burial chamber.",
                  "sensory": "Dripping.", "props": {} }
                """);

        new RoomDresser(client, PROMPT).dress(target);

        var user = client.conversations().get(0).stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst().orElseThrow().content();
        for (var prop : target.props()) {
            var expected = "- " + prop.id() + " (" + prop.type().name().toLowerCase()
                    + ") at " + prop.x() + "," + prop.y();
            assertTrue(user.contains(expected),
                    "the dresser was not told where things stand, missing: " + expected);
        }
    }

    @Test
    @DisplayName("an unparseable reply falls back rather than killing the room")
    void fallsBackOnGarbage() {
        var target = room();

        var dressing = new RoomDresser(new ScriptedDmClient("I'm sorry, I can't do that."), PROMPT)
                .dress(target);

        assertNotNull(dressing.name());
        assertFalse(dressing.name().isBlank());
        assertTrue(dressing.propDescriptions().isEmpty());
    }
}
