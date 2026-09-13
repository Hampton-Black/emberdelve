package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Diff;
import dm.model.Event;
import dm.model.MarkerTag;
import dm.state.EventLog;
import dm.wire.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.6: a reconcile-only marker is a fact the player can click.
 * Spec §8f. Grid coordinates never reach the projection of that fact (pa8).
 */
class PlaceMarkerTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final Pattern COORD = Pattern.compile("\\(\\d+,\\d+\\)");
    private static final String SCORCH_TEXT = "The flagstones here are blackened, as if a fire sat too long.";

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static List<String> toolNames(com.fasterxml.jackson.databind.node.ArrayNode tools) {
        return tools.findValuesAsText("name");
    }

    private static JsonNode functionNamed(JsonNode tools, String name) {
        for (var tool : tools) {
            if (name.equals(tool.path("function").path("name").asText())) {
                return tool.get("function");
            }
        }
        fail("schema did not offer " + name);
        return null;
    }

    private static String section(String text, String heading) {
        int start = text.indexOf(heading);
        assertTrue(start >= 0, "missing " + heading + " in:\n" + text);
        int next = text.indexOf("\n## ", start + heading.length());
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    private static DmService dm(GameEngine engine) {
        return new DmService(new ScriptedDmClient(), new ScriptedDmClient("x"), engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
    }

    @Test
    @DisplayName("SCHEMA_VERSION stays 3")
    void schemaVersionStays3() {
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("place_marker is offered to reconcile with the closed tag enum, and to nothing else")
    void placeMarkerIsReconcileOnlyWithClosedTags() {
        var fixture = DispatcherFixture.inCrypt();
        assertTrue(ToolSchema.allowedInReconcile(ToolSchema.PLACE_MARKER));
        assertFalse(toolNames(ToolSchema.forTurn(fixture.engine())).contains(ToolSchema.PLACE_MARKER),
                "mechanics does not place glyphs; a false positive there would still be cheap, but the narrator holds the square");
        assertTrue(toolNames(ToolSchema.forReconcile(fixture.engine())).contains(ToolSchema.PLACE_MARKER));

        var fn = functionNamed(ToolSchema.forReconcile(fixture.engine()), ToolSchema.PLACE_MARKER);
        assertTrue(fn.path("strict").asBoolean());
        var tags = new HashSet<String>();
        fn.path("parameters").path("properties").path("tag").path("enum")
                .forEach(n -> tags.add(n.asText()));
        assertEquals(Set.of("SCORCH", "SIGIL", "TRACKS"), tags);

        var required = new HashSet<String>();
        fn.path("parameters").path("required").forEach(n -> required.add(n.asText()));
        assertTrue(required.containsAll(Set.of("tag", "x", "y", "text")));
    }

    @Test
    @DisplayName("an unknown tag is rejected server-side")
    void unknownTagIsRejected() {
        var fixture = DispatcherFixture.inCrypt();
        var result = fixture.dispatch(ToolSchema.PLACE_MARKER, """
                {"tag":"HOTDOG","x":4,"y":5,"text":"An immense steaming hotdog."}""");
        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
        assertTrue(result.message().toLowerCase().contains("tag"), result.message());
        assertTrue(fixture.engine().log().events().stream().noneMatch(Event.MarkerPlaced.class::isInstance));
    }

    @Test
    @DisplayName("an out-of-bounds square is rejected server-side")
    void outOfBoundsSquareIsRejected() {
        var fixture = DispatcherFixture.inCrypt();
        var result = fixture.dispatch(ToolSchema.PLACE_MARKER, """
                {"tag":"SIGIL","x":99,"y":99,"text":"A scratched sigil."}""");
        assertFalse(result.ok());
        assertTrue(result.message().startsWith("REJECTED:"), result.message());
        assertTrue(result.message().toLowerCase().contains("grid"), result.message());
    }

    @Test
    @DisplayName("place_marker folds into the log and the scene without shipping the text")
    void placeFoldsAndShipsGlyphWithoutText() {
        var log = new EventLog();
        var engine = started(log);
        var dispatcher = new ToolDispatcher(engine);

        var result = dispatcher.dispatch(new DmClient.ToolCall(
                "1", ToolSchema.PLACE_MARKER,
                "{\"tag\":\"SCORCH\",\"x\":9,\"y\":6,\"text\":" + Json.MAPPER.valueToTree(SCORCH_TEXT) + "}"));

        assertTrue(result.ok(), result.message());
        var placed = log.events().stream()
                .filter(Event.MarkerPlaced.class::isInstance)
                .map(Event.MarkerPlaced.class::cast)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertEquals(MarkerTag.SCORCH, placed.tag());
        assertEquals(9, placed.x());
        assertEquals(6, placed.y());
        assertEquals("crypt", placed.roomId());
        assertEquals(SCORCH_TEXT, placed.text());

        var scene = engine.scene();
        assertEquals(1, scene.markers().size());
        var view = scene.markers().getFirst();
        assertEquals(placed.id(), view.id());
        assertEquals(MarkerTag.SCORCH, view.tag());
        assertEquals(9, view.x());
        assertEquals(6, view.y());
        assertTrue(result.diffs().stream().anyMatch(Diff.MarkerPlaced.class::isInstance));
        assertFalse(Json.MAPPER.valueToTree(scene.markers()).toString().contains("blackened"),
                "free-form text never reaches the renderer");
    }

    @Test
    @DisplayName("inspecting a marker puts tag and text in Established, never its square")
    void inspectDoesNotLeakCoordinates() {
        var log = new EventLog();
        var engine = started(log);
        var dispatcher = new ToolDispatcher(engine);
        dispatcher.dispatch(new DmClient.ToolCall(
                "1", ToolSchema.PLACE_MARKER,
                "{\"tag\":\"SCORCH\",\"x\":9,\"y\":6,\"text\":" + Json.MAPPER.valueToTree(SCORCH_TEXT) + "}"));
        var markerId = engine.scene().markers().getFirst().id();

        engine.inspectMarker(markerId);

        assertTrue(log.events().stream().anyMatch(Event.MarkerInspected.class::isInstance));

        var projection = dm(engine).worldState(false);
        var established = section(projection, "## Established");
        assertTrue(established.contains("SCORCH"), established);
        assertTrue(established.contains(SCORCH_TEXT), established);
        assertFalse(COORD.matcher(established).find(),
                "a visible marker that prints (9,6) will be read aloud:\n" + established);
    }
}
