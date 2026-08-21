package dm.generate;

import dm.ScriptedDmClient;
import dm.ai.DmService;
import dm.ai.TurnSink;
import dm.content.ContentLoader;
import dm.model.Diff;
import dm.model.NarrationSegment;
import dm.model.RollResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    @DisplayName("a generated room never hands the word 'null' to the DM")
    void generatedRoomPromptContainsNoNulls() {
        // A generated room has no crypt-specific notes — theSarcophagus, theSarcophagusOpened
        // and theDoor are Java null — and StringBuilder.append((String) null) writes the word
        // "null" into the prompt. Every turn. Twice.
        var dresser = new RoomDresser(new ScriptedDmClient("""
                { "name": "The Weeping Vault", "overview": "A burial chamber.",
                  "sensory": "Dripping.", "props": {} }
                """), CONTENT.prompt("dress-room"));
        var room = RoomSource.generated(CONTENT, dresser, "crypt", 21);

        var repo = new dm.repo.InMemoryGameRepository();
        var engine = new dm.engine.GameEngine(CONTENT, repo, new dm.engine.RandomDiceRoller(),
                room);
        engine.start();

        // A non-blank prose reply keeps the reconcile phase running, so all three prompt
        // assemblies — mechanics, prose, reconcile — are exercised in one turn.
        var tools = new ScriptedDmClient();
        var prose = new ScriptedDmClient("The room answers in dripping silence.");
        var dm = new DmService(tools, prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));

        dm.handleFreeText("fighter", "I hold the lantern up and look around", new NoopSink());

        for (var client : List.of(tools, prose)) {
            for (var conversation : client.conversations()) {
                for (var message : conversation) {
                    assertTrue(message.content() == null || !message.content().contains("null"),
                            "the DM was handed the word 'null': " + message.content());
                    // The dresser may skip props; an undescribed one must reach the DM as a
                    // bare entry, not as stage direction read out as if it were prose.
                    assertTrue(message.content() == null
                                    || !message.content().contains("not been dressed"),
                            "the DM was handed stage direction as a prop description: "
                                    + message.content());
                }
            }
        }
    }

    @Test
    @DisplayName("a generated room with no secret notes gets no Secrets heading")
    void generatedRoomPromptOmitsEmptySecrets() {
        // The crypt's sarcophagus and door notes are Java null on a generated room, and an
        // empty secrets block is an invitation to invent one — so the heading goes too.
        var dresser = new RoomDresser(new ScriptedDmClient("""
                { "name": "The Weeping Vault", "overview": "A burial chamber.",
                  "sensory": "Dripping.", "props": {} }
                """), CONTENT.prompt("dress-room"));
        var room = RoomSource.generated(CONTENT, dresser, "crypt", 21);

        var repo = new dm.repo.InMemoryGameRepository();
        var engine = new dm.engine.GameEngine(CONTENT, repo, new dm.engine.RandomDiceRoller(),
                room);
        engine.start();

        var tools = new ScriptedDmClient();
        var prose = new ScriptedDmClient("The room answers in dripping silence.");
        var dm = new DmService(tools, prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));

        dm.handleFreeText("fighter", "I hold the lantern up and look around", new NoopSink());

        for (var client : List.of(tools, prose)) {
            for (var conversation : client.conversations()) {
                for (var message : conversation) {
                    assertTrue(message.content() == null
                                    || !message.content().contains("Secrets you know"),
                            "an empty Secrets block invites the model to invent one: "
                                    + message.content());
                }
            }
        }
    }

    private static final class NoopSink implements TurnSink {
        @Override
        public void narration(NarrationSegment segment) {
        }

        @Override
        public void diffs(List<Diff> diffs) {
        }

        @Override
        public void roll(RollResult result) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Throwable error) {
            fail(error);
        }
    }
}
