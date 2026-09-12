package dm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectiveRailTest {

    @Test
    @DisplayName("drain spends every clause in order, joined by a space")
    void drainIsOrdered() {
        var rail = new DirectiveRail(() -> false);
        rail.latch(Directive.aboutParty("first"));
        rail.latch(Directive.aboutParty("second"));
        rail.latch(Directive.aboutRoom("crypt", "arrival"));

        assertEquals("first second arrival", rail.drain());
        assertNull(rail.drain(), "a spent rail is empty");
    }

    @Test
    @DisplayName("a second crossing drops the previous room's arrival and keeps party signs")
    void crossingDropsOtherRoomsKeepsParty() {
        var rail = new DirectiveRail(() -> false);
        rail.latch(Directive.aboutParty("The ring of light has drawn in."));
        rail.crossedInto("gallery", "first arrival");
        rail.latch(Directive.aboutRoom("gallery", "a fire line"));

        rail.crossedInto("crypt", "return arrival");

        var drained = rail.drain();
        assertTrue(drained.startsWith("The ring of light has drawn in."), drained);
        assertTrue(drained.contains("return arrival"), drained);
        assertFalse(drained.contains("first arrival"), drained);
        assertFalse(drained.contains("a fire line"), drained);
    }

    @Test
    @DisplayName("engine-generated clauses are dropped when a fight is already running")
    void dropsEngineGeneratedWhileFighting() {
        var fighting = new boolean[]{true};
        var rail = new DirectiveRail(() -> fighting[0]);
        rail.latch(Directive.aboutParty("too late"));
        assertNull(rail.drain());

        fighting[0] = false;
        rail.latch(Directive.aboutParty("in time"));
        fighting[0] = true;
        rail.crossedInto("crypt", "the arrival");
        var drained = rail.drain();
        assertTrue(drained.contains("in time"), drained);
        assertTrue(drained.contains("the arrival"),
                "arrival left by the crossing that started the fight is kept");
    }
}
