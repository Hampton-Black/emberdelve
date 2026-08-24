package dm.ai;

import dm.model.Anchor;
import dm.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    }
}
