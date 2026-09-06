package dm.model;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityRoomTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("a spawned entity remembers which room it was spawned into")
    void spawnCarriesTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        assertEquals("gallery", goblin.roomId());
        assertEquals(4, goblin.x());
        assertEquals(4, goblin.y());
    }

    @Test
    @DisplayName("walking across a room does not change which room it is")
    void movedToKeepsTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        var moved = goblin.movedTo(7, 2);

        assertEquals("gallery", moved.roomId());
        assertEquals(7, moved.x());
        assertEquals(2, moved.y());
    }

    @Test
    @DisplayName("movedToRoom changes the room and the square together")
    void movedToRoomChangesBoth() {
        var fighter = CONTENT.entity("fighter").spawn("fighter", "crypt", 6, 1);

        var arrived = fighter.movedToRoom("gallery", 6, 1);

        assertEquals("gallery", arrived.roomId());
        assertEquals(6, arrived.x());
        // Everything else is untouched — a doorway is not a heal.
        assertEquals(fighter.hp(), arrived.hp());
        assertEquals(fighter.skillModifiers(), arrived.skillModifiers());
    }

    @Test
    @DisplayName("damage and healing leave the room alone")
    void withHpKeepsTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        assertEquals("gallery", goblin.damaged(3).roomId());
        assertEquals("gallery", goblin.withHp(1).roomId());
    }

    @Test
    @DisplayName("the client is never told which room an entity is in — it only gets one room's")
    void viewDoesNotCarryTheRoom() {
        var goblin = CONTENT.entity("goblin").spawn("goblin", "gallery", 4, 4);

        // scene() only ever sends the current room's occupants, so the field would be noise
        // on the wire and a second place for the client to disagree with the server.
        var json = dm.wire.Json.MAPPER.valueToTree(goblin.toView()).toString();
        assertFalse(json.contains("roomId"), json);
    }
}
