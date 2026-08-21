package dm.content;

import dm.model.FloorType;
import dm.model.PropType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kit is the closed set the generator draws from. If a prop type reaches the generator
 * that has no mesh, the room renders with a hole in it — so the kit is validated at load.
 */
class KitDefinitionTest {

    @Test
    @DisplayName("the crypt kit loads and exposes its palette")
    void loads() {
        var kit = new ContentLoader().kit("crypt");

        assertEquals("crypt", kit.kitId());
        assertTrue(kit.floors().contains(FloorType.CRACKED_STONE));
        assertTrue(kit.size().min() >= 8, "a room smaller than 8 squares is not a fight");
        assertTrue(kit.size().max() <= 20);
    }

    @Test
    @DisplayName("every prop entry names a type the renderer has a mesh for")
    void propTypesAreClosed() {
        var kit = new ContentLoader().kit("crypt");

        assertFalse(kit.props().isEmpty());
        for (var entry : kit.props()) {
            assertNotNull(entry.type());
            assertTrue(entry.minCount() >= 0);
            assertTrue(entry.maxCount() >= entry.minCount());
        }
    }

    @Test
    @DisplayName("the kit carries a sarcophagus, because M0's script needs one")
    void carriesSarcophagus() {
        var kit = new ContentLoader().kit("crypt");

        assertTrue(kit.props().stream().anyMatch(p -> p.type() == PropType.SARCOPHAGUS));
    }
}
