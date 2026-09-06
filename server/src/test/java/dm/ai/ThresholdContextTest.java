package dm.ai;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7a: the player can see there is a door and cannot see what is behind it. A destination
 * id in the prompt is a string the narrator can read aloud — m2-evaluation §8's grid-coordinate
 * finding in a different costume.
 */
class ThresholdContextTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("ways out name the wall, never the room on the other side")
    void waysOutNameDirections() {
        var block = DmService.waysOut(CONTENT.room("crypt"));

        assertTrue(block.contains("north"), block);
        assertFalse(block.contains("gallery"), "the destination is not the player's to know");
        assertFalse(block.contains("door-north"), "nor is the id");
    }

    @Test
    @DisplayName("a room with no way out says so rather than printing an empty heading")
    void noExitsNoHeading() {
        var sealed = new dm.content.RoomDefinition(
                "sealed", "A Sealed Room", 4, 4,
                dm.model.FloorType.STONE, dm.model.WallType.STONE,
                dm.model.LightingPreset.DARK,
                java.util.List.of(), java.util.List.of(),
                new dm.content.RoomDefinition.StartPositions(
                        java.util.List.of(new dm.content.RoomDefinition.Point(1, 1)),
                        new dm.content.RoomDefinition.Point(2, 2)),
                new dm.content.RoomDefinition.DmNotes("o", "s", null, null, null));

        // An empty "## Ways out" heading is an invitation to invent one — the same reason
        // ToolSchema does not offer reveal_prop with an empty enum.
        assertEquals("", DmService.waysOut(sealed));
    }

    @Test
    @DisplayName("the arrival directive forks on whether the party has been here before")
    void arrivalForksOnVisits() {
        assertNotEquals(DmService.ARRIVAL_FIRST, DmService.ARRIVAL_RETURN);
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("been here"),
            DmService.ARRIVAL_RETURN);
        // The window will have dropped the first visit; "do not rebuild it from scratch" is the
        // whole point of telling it. Spec §7b.
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("not")
                        && DmService.ARRIVAL_RETURN.toLowerCase().contains("again"),
                DmService.ARRIVAL_RETURN);
    }

    @Test
    @DisplayName("the threshold line says a seam happened, and names neither room's contents")
    void thresholdMarkerMarksTheSeam() {
        var line = DmService.thresholdMarker("The Ashen Crypt", "The Long Gallery");

        assertTrue(line.contains("The Ashen Crypt"), line);
        assertTrue(line.contains("The Long Gallery"), line);
        // Bleed is the failure: room 1's tallies written onto room 2's stone, because the
        // six-turn window is still full of room 1. Spec §7b.
        assertTrue(line.toLowerCase().contains("different room")
                        || line.toLowerCase().contains("somewhere else"), line);
    }
}
