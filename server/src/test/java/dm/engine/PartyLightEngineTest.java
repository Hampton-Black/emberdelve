package dm.engine;

import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.Consumable;
import dm.model.Diff;
import dm.model.Event;
import dm.model.PartyLight;
import dm.state.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.12: the party's light is derived from LIGHT filled, shipped as PartyLight,
 * never as a radius or a segment count. Spec §7a §10.
 */
class PartyLightEngineTest {

    private EventLog log;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        log = new EventLog();
        engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());
        engine.start();
    }

    @Test
    @DisplayName("the scene starts FULL and SCHEMA_VERSION stays 3")
    void sceneStartsFull() {
        assertEquals(PartyLight.FULL, engine.scene().partyLight());
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("filling LIGHT to 2 yields LOW and a PartyLightChanged; 0 to 1 stays FULL")
    void fillToLowEmitsLevelChangeOnly() {
        var first = engine.tickClock(ClockId.LIGHT);
        assertEquals(PartyLight.FULL, engine.scene().partyLight());
        assertTrue(first.stream().noneMatch(Diff.PartyLightChanged.class::isInstance),
                "0→1 stays FULL; the level did not flip");

        var second = engine.tickClock(ClockId.LIGHT);
        assertEquals(PartyLight.LOW, engine.scene().partyLight());
        assertTrue(second.stream().anyMatch(d -> d instanceof Diff.PartyLightChanged c
                && c.partyLight() == PartyLight.LOW));
        assertTrue(second.stream().noneMatch(d -> jsonLooksLikeARadius(d)),
                "never a radius in world units on the wire");
    }

    @Test
    @DisplayName("filling LIGHT to 6 yields OUT")
    void fillToOut() {
        List<Diff> last = List.of();
        for (int i = 0; i < 6; i++) {
            last = engine.tickClock(ClockId.LIGHT);
        }
        assertEquals(PartyLight.OUT, engine.scene().partyLight());
        assertTrue(last.stream().anyMatch(d -> d instanceof Diff.PartyLightChanged c
                && c.partyLight() == PartyLight.OUT));
    }

    @Test
    @DisplayName("spending a torch from LOW returns FULL and a PartyLightChanged")
    void torchSpendRecoversToFull() {
        engine.tickClock(ClockId.LIGHT);
        engine.tickClock(ClockId.LIGHT);
        assertEquals(PartyLight.LOW, engine.scene().partyLight());

        var diffs = engine.useItem("fighter", Consumable.TORCH);

        assertEquals(PartyLight.FULL, engine.scene().partyLight());
        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.PartyLightChanged c
                && c.partyLight() == PartyLight.FULL));
    }

    @Test
    @DisplayName("resetLight from FAILING returns FULL without announcing the way down")
    void resetLightFromFailing() {
        for (int i = 0; i < 5; i++) {
            engine.tickClock(ClockId.LIGHT);
        }
        assertEquals(PartyLight.FAILING, engine.scene().partyLight());

        var diffs = engine.resetLight();

        assertEquals(PartyLight.FULL, engine.scene().partyLight());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.PartyLightChanged c
                && c.partyLight() == PartyLight.FULL));
        var fired = log.events().stream()
                .filter(Event.ConsequenceFired.class::isInstance)
                .map(Event.ConsequenceFired.class::cast)
                .map(Event.ConsequenceFired::id)
                .toList();
        assertFalse(fired.contains(dm.model.ConsequenceId.LIGHT_OUT));
    }

    @Test
    @DisplayName("LIGHT_OUT is generic while spares remain, and names the last torch at count 0")
    void lightOutLastTorchSentence() {
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.LIGHT);
        }
        var withSpares = engine.directives().drain();
        assertNotNull(withSpares);
        assertTrue(withSpares.contains("The torch has gone out."), withSpares);
        assertFalse(withSpares.contains("your last torch"),
                "two spares remain; the last-torch sentence is a lie");

        engine.useItem("fighter", Consumable.TORCH);
        engine.tickClock(ClockId.LIGHT);
        engine.useItem("fighter", Consumable.TORCH);
        assertEquals(0, engine.state().consumableCount(Consumable.TORCH));
        engine.directives().drain();

        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.LIGHT);
        }
        var noneLeft = engine.directives().drain();
        assertNotNull(noneLeft);
        assertTrue(noneLeft.contains("The torch has gone out."), noneLeft);
        assertTrue(noneLeft.contains("your last torch"), noneLeft);
    }

    private static boolean jsonLooksLikeARadius(Diff d) {
        var json = dm.wire.Json.MAPPER.valueToTree(d);
        return json.has("range") || json.has("radius") || json.has("metres") || json.has("meters");
    }
}
