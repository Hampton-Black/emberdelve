package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dm.model.Anchor;
import dm.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §9. The description is free-form and only ever enters the prompt. The handle — where the
 * thing is — goes through a closed enum, which is what leaves invariant #7 intact.
 */
class AssertFactTest {

    @Test
    @DisplayName("an ambient assertion becomes a fact in the room the party is in")
    void ambientAssertion() {
        var fixture = DispatcherFixture.inCrypt();

        var result = fixture.dispatch("assert_fact", """
                {"text":"The air tastes of old iron.","anchor":"ambient"}""");

        assertTrue(result.ok(), result.message());
        var asserted = fixture.lastEvent(Event.FactAsserted.class);
        assertEquals("The air tastes of old iron.", asserted.text());
        assertEquals("crypt", asserted.roomId());
        assertInstanceOf(Anchor.Ambient.class, asserted.anchor());
    }

    @Test
    @DisplayName("a square anchor is refused when the square is not on the grid")
    void squareMustBeOnTheGrid() {
        var fixture = DispatcherFixture.inCrypt();

        var result = fixture.dispatch("assert_fact", """
                {"text":"A sigil, scratched deep.","anchor":"at_square","x":99,"y":99}""");

        assertFalse(result.ok());
        assertTrue(result.message().toLowerCase().contains("grid"));
    }

    @Test
    @DisplayName("an attachment is refused when there is nothing there to attach to")
    void attachmentMustExist() {
        var fixture = DispatcherFixture.inCrypt();

        var result = fixture.dispatch("assert_fact", """
                {"text":"A signet ring on its finger.","anchor":"on","target_id":"nobody"}""");

        assertFalse(result.ok());
        assertTrue(result.message().contains("nobody"));
    }

    @Test
    @DisplayName("assert_fact is offered to the reconcile pass and to nothing else")
    void onlyOnReconcile() {
        var fixture = DispatcherFixture.inCrypt();
        assertTrue(ToolSchema.allowedInReconcile(ToolSchema.ASSERT_FACT));
        assertFalse(ToolSchema.forTurn(fixture.engine()).toString().contains(ToolSchema.ASSERT_FACT),
                "the mechanics model adjudicates; it does not get to write the world's prose");
        assertTrue(ToolSchema.forReconcile(fixture.engine()).toString().contains(ToolSchema.ASSERT_FACT),
                "reconcile is the pass that writes down what the narrator already said");
    }

    @Test
    @DisplayName("assert_fact's strict schema requires every property, with null for the optional ones")
    void strictSchemaRequiresEveryProperty() {
        var fn = assertFactFunction(ToolSchema.forReconcile(DispatcherFixture.inCrypt().engine()));
        assertTrue(fn.path("strict").asBoolean());

        var params = fn.get("parameters");
        var required = new HashSet<String>();
        params.get("required").forEach(n -> required.add(n.asText()));
        var names = new HashSet<String>();
        params.get("properties").fieldNames().forEachRemaining(names::add);

        assertEquals(names, required, "strict:true forbids a property that is not required");
        assertTrue(required.containsAll(Set.of("text", "anchor", "x", "y", "target_id")));
        assertTrue(isNullUnion(params.get("properties").get("x"), "integer"));
        assertTrue(isNullUnion(params.get("properties").get("y"), "integer"));
        assertTrue(isNullUnion(params.get("properties").get("target_id"), "string"));
    }

    private static JsonNode assertFactFunction(JsonNode tools) {
        for (var tool : tools) {
            if (ToolSchema.ASSERT_FACT.equals(tool.path("function").path("name").asText())) {
                return tool.get("function");
            }
        }
        fail("reconcile schema did not offer assert_fact");
        return null;
    }

    private static boolean isNullUnion(JsonNode prop, String jsonType) {
        var type = prop.get("type");
        if (type == null || !type.isArray()) {
            return false;
        }
        var types = new HashSet<String>();
        type.forEach(n -> types.add(n.asText()));
        return types.equals(Set.of(jsonType, "null"));
    }
}
