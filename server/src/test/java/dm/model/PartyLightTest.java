package dm.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * emberdelve-4h9.12: PartyLight is a closed five-value fold of LIGHT filled. Spec §7a.
 */
class PartyLightTest {

    @Test
    @DisplayName("of maps LIGHT filled onto the five LIGHT-table names")
    void ofMapsFilled() {
        assertEquals(PartyLight.FULL, PartyLight.of(0));
        assertEquals(PartyLight.FULL, PartyLight.of(1));
        assertEquals(PartyLight.LOW, PartyLight.of(2));
        assertEquals(PartyLight.LOW, PartyLight.of(3));
        assertEquals(PartyLight.GUTTERING, PartyLight.of(4));
        assertEquals(PartyLight.FAILING, PartyLight.of(5));
        assertEquals(PartyLight.OUT, PartyLight.of(6));
    }
}
