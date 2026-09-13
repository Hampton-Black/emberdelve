package dm.model;

import dm.ai.ToolSchema;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.7: a closed appearance picks the mesh. The model never sees the names.
 */
class AppearanceTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("barrel and chest are both CONTAINER appearances, and both block")
    void barrelAndChestAreContainerLooks() {
        assertEquals(PropType.CONTAINER, Appearances.spec("chest").type());
        assertEquals(PropType.CONTAINER, Appearances.spec("barrel").type());
        assertTrue(PropType.CONTAINER.blocksMovement());
        assertTrue(Appearances.isKnown(PropType.CONTAINER, "chest"));
        assertTrue(Appearances.isKnown(PropType.CONTAINER, "barrel"));
    }

    @Test
    @DisplayName("every closed appearance names a kit mesh that exists on disk")
    void everyAppearanceHasAMesh() {
        var missing = Appearances.all().stream()
                .filter(spec -> !Files.isRegularFile(spec.meshFile()))
                .map(spec -> spec.id() + " → " + spec.meshFile())
                .toList();
        assertEquals(java.util.List.of(), missing,
                "a missing appearance must fail this test, not skip");
    }

    @Test
    @DisplayName("an unknown appearance is rejected, not silently dropped")
    void unknownAppearanceIsRejected() {
        assertFalse(Appearances.isKnown(PropType.CONTAINER, "no_such_mesh"));
        assertThrows(IllegalArgumentException.class, () -> Appearances.spec("no_such_mesh"));
    }

    @Test
    @DisplayName("the reliquary is a takeable CONTAINER that looks like a chest")
    void reliquaryIsContainerChest() {
        var reliquary = CONTENT.room("chapel").prop("reliquary");
        assertEquals(PropType.CONTAINER, reliquary.type());
        assertEquals("chest", reliquary.appearance());
        assertEquals(java.util.List.of("take"), reliquary.actions());
        assertTrue(reliquary.type().blocksMovement());

        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        engine.crossExit("door-north");
        engine.crossExit("door-north");
        var onBoard = engine.scene().currentRoom().props().stream()
                .filter(p -> p.id().equals("reliquary"))
                .findFirst()
                .orElseThrow();
        assertEquals("chest", onBoard.appearance());
        assertEquals(java.util.List.of("take"), onBoard.actions());
    }

    @Test
    @DisplayName("the tool schema never lists appearance names or PropType values")
    void appearanceIsNotInToolSchema() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        engine.crossExit("door-north");
        engine.crossExit("door-north");
        var schema = ToolSchema.forTurn(engine).toString()
                + ToolSchema.forReconcile(engine).toString();
        assertFalse(schema.contains("appearance"), schema);
        for (var spec : Appearances.all()) {
            assertFalse(schema.contains("\"" + spec.id() + "\""),
                    "tool schema leaked appearance '" + spec.id() + "'");
        }
        for (var type : PropType.values()) {
            assertFalse(schema.contains("\"" + type.name() + "\""),
                    "tool schema leaked PropType " + type);
        }
        var take = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.TAKE_PROP))
                .findFirst()
                .orElseThrow();
        var ids = take.path("parameters").path("properties").path("prop_id").path("enum");
        assertEquals(1, ids.size());
        assertEquals("reliquary", ids.get(0).asText());
    }

    @Test
    @DisplayName("SCHEMA_VERSION stays 3")
    void schemaVersionUnchanged() {
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("the crypt spends at least two kits and around twenty props")
    void cryptSpendsTheKits() {
        var crypt = CONTENT.room("crypt");
        assertTrue(crypt.props().size() >= 20, "crypt props: " + crypt.props().size());
        var kits = crypt.props().stream()
                .map(p -> p.appearance())
                .filter(a -> a != null && !a.isBlank())
                .map(a -> Appearances.spec(a).kit())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(kits.size() >= 2, "kits used: " + kits);
    }

    @Test
    @DisplayName("a Prop on the wire carries the appearance the server chose")
    void propShipsAppearance() throws Exception {
        var prop = new Prop("cask", PropType.CONTAINER, 1, 2, 0, false,
                java.util.List.<String>of(), "barrel");
        var json = dm.wire.Json.MAPPER.writeValueAsString(prop);
        assertTrue(json.contains("\"appearance\":\"barrel\""), json);
        var back = dm.wire.Json.MAPPER.readValue(json, Prop.class);
        assertEquals("barrel", back.appearance());
        assertEquals(PropType.CONTAINER, back.type());
    }

    @Test
    @DisplayName("kits directory used by the catalog is the Godot kits folder")
    void kitRootIsTheGodotKits() {
        Path root = Appearances.kitRoot();
        assertTrue(Files.isDirectory(root.resolve("dungeon_props")), root.toString());
        assertTrue(Files.isDirectory(root.resolve("ruins")), root.toString());
        assertTrue(Files.isDirectory(root.resolve("kaykit_halloween")), root.toString());
        assertTrue(Files.isDirectory(root.resolve("kaykit_dungeon")), root.toString());
    }
}
