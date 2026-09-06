package dm.engine;

import dm.content.ContentLoader;
import dm.model.Square;
import dm.state.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the board is allowed to tell the player about where they cannot walk.
 *
 * <p>The client owns no movement rule (invariant #1), so a square it draws as solid is a square
 * the server said was solid. These tests pin the two halves of that: the set agrees with the
 * refusal the engine would actually give, and it says nothing about anything hidden.
 */
class BlockedSquaresTest {

    private GameEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GameEngine(new ContentLoader(), new EventLog(), new RandomDiceRoller());
        engine.start();
    }

    @Test
    @DisplayName("a square the board draws as solid is one the engine would refuse")
    void blockedMatchesTheRefusal() {
        var scene = engine.scene();
        assertFalse(scene.blocked().isEmpty(), "the crypt has solid furniture in it");

        for (var square : scene.blocked()) {
            var boom = assertThrows(IllegalArgumentException.class,
                    () -> engine.moveTo("fighter", square.x(), square.y(), new CombatSink.Buffer()),
                    "drawn solid at " + square + " but the engine allowed the move");
            assertTrue(boom.getMessage().contains("solid"), boom.getMessage());
        }
    }

    @Test
    @DisplayName("an alcove is a prop you can walk into, and is not drawn solid")
    void notEveryPropBlocks() {
        // The reason this cannot be read off the prop list on the client. The crypt's alcove is
        // hidden at the start, so reveal it first and check it still is not in the set.
        engine.revealProp("alcove");
        var scene = engine.scene();

        assertTrue(scene.currentRoom().props().stream().anyMatch(p -> p.id().equals("alcove")),
                "the alcove is on the board once revealed");
        assertFalse(scene.blocked().contains(new Square(9, 6)),
                "an alcove is a recess, not a wall");
    }

    @Test
    @DisplayName("nothing hidden is given away by a square drawn solid")
    void hiddenPropsAreNotAdvertised() {
        var visible = engine.scene().currentRoom().props().stream()
                .map(p -> new Square(p.x(), p.y())).toList();

        // Every blocked square belongs to something the player has been shown. A hidden prop that
        // blocked would otherwise put a marker on the board exactly where the secret is.
        for (var square : engine.scene().blocked()) {
            assertTrue(visible.contains(square), "blocked square with nothing visible on it: " + square);
        }
    }

    @Test
    @DisplayName("the doorway is not drawn solid — it is the one square you are meant to use")
    void doorsAreNotBlocked() {
        var door = engine.scene().currentRoom().exits().getFirst();
        assertFalse(engine.scene().blocked().contains(new Square(door.x(), door.y())));
    }
}
