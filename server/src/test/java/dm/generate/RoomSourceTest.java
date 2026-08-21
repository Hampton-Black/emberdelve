package dm.generate;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomSourceTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("the authored source still loads the crypt, unchanged")
    void authoredStillWorks() {
        var room = RoomSource.authored(CONTENT, "crypt");

        assertEquals("crypt", room.roomId());
        assertEquals("The Ashen Crypt", room.name());
    }

    @Test
    @DisplayName("a dressed generated room carries the model's name and prose")
    void generatedCarriesDressing() {
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber where the walls sweat.",
                  "sensory": "Dripping water.",
                  "props": {}
                }
                """;
        var dresser = new RoomDresser(new ScriptedDmClient(json), CONTENT.prompt("dress-room"));

        var room = RoomSource.generated(CONTENT, dresser, "crypt", 12);

        assertEquals("The Weeping Vault", room.name());
        assertTrue(room.dmNotes().overview().contains("burial chamber"));
        assertTrue(room.dmNotes().sensory().contains("Dripping"));
    }

    @Test
    @DisplayName("a described prop carries its description into the DM's notes")
    void propDescriptionsLand() {
        var generated = new RoomGenerator(CONTENT).generate("crypt", 12);
        var firstProp = generated.props().get(0).id();
        var json = """
                {
                  "name": "The Weeping Vault",
                  "overview": "A burial chamber.",
                  "sensory": "Dripping.",
                  "props": { "%s": "Slick with condensation." }
                }
                """.formatted(firstProp);
        var dresser = new RoomDresser(new ScriptedDmClient(json), CONTENT.prompt("dress-room"));

        var room = RoomSource.generated(CONTENT, dresser, "crypt", 12);

        assertEquals("Slick with condensation.", room.prop(firstProp).description());
    }

    @Test
    @DisplayName("the engine starts on a generated room and puts the fighter on the grid")
    void engineStartsOnGeneratedRoom() {
        var dresser = new RoomDresser(new ScriptedDmClient(""), CONTENT.prompt("dress-room"));
        var room = RoomSource.generated(CONTENT, dresser, "crypt", 21);

        var repo = new dm.repo.InMemoryGameRepository();
        var engine = new dm.engine.GameEngine(CONTENT, repo, new dm.engine.RandomDiceRoller(), room);
        engine.start();

        var scene = engine.scene();
        assertEquals(room.roomId(), scene.roomId());
        assertEquals(1, scene.entities().size());
        assertFalse(room.isObstructed(scene.entities().get(0).x(), scene.entities().get(0).y()),
                "the fighter started inside something solid");
    }
}
