package dm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.7: PropType stays mechanical. Appearance never changes blocking.
 */
class PropTypeTest {

    @Test
    @DisplayName("a CONTAINER blocks whether it looks like a barrel or a chest")
    void containerBlocksRegardlessOfLook() {
        var container = PropType.valueOf("CONTAINER");
        assertTrue(container.blocksMovement());
    }

    @Test
    @DisplayName("STATUE, FURNITURE and REMAINS block; SCENERY does not")
    void newMechanicalTypes() {
        assertTrue(PropType.valueOf("STATUE").blocksMovement());
        assertTrue(PropType.valueOf("FURNITURE").blocksMovement());
        assertTrue(PropType.valueOf("REMAINS").blocksMovement());
        assertFalse(PropType.valueOf("SCENERY").blocksMovement());
    }

    @Test
    @DisplayName("CHEST is folded into CONTAINER — it is not a type")
    void chestIsGone() {
        assertThrows(IllegalArgumentException.class, () -> PropType.valueOf("CHEST"));
        assertTrue(PropType.valueOf("CONTAINER").blocksMovement());
    }

    @Test
    @DisplayName("existing wall features stay walkable; the rest of the old set still blocks")
    void existingTypesUnchanged() {
        assertTrue(PropType.SARCOPHAGUS.blocksMovement());
        assertTrue(PropType.BRAZIER.blocksMovement());
        assertTrue(PropType.PILLAR.blocksMovement());
        assertTrue(PropType.RUBBLE.blocksMovement());
        assertFalse(PropType.ALCOVE.blocksMovement());
        assertFalse(PropType.DOOR.blocksMovement());
    }
}
