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
    @DisplayName("ruins and halloween kits load as palettes of the new types")
    void extraKitsLoad() {
        var loader = new ContentLoader();
        var ruins = loader.kit("ruins");
        var halloween = loader.kit("halloween");

        assertEquals("ruins", ruins.kitId());
        assertEquals("halloween", halloween.kitId());
        assertTrue(ruins.props().stream().anyMatch(p -> p.type() == PropType.STATUE));
        assertTrue(halloween.props().stream().anyMatch(p -> p.type() == PropType.REMAINS));
        for (var kit : java.util.List.of(ruins, halloween)) {
            for (var entry : kit.props()) {
                assertNotNull(entry.type());
            }
        }
    }
}
