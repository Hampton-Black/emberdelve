package dm.generate;

import dm.content.ContentLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomDumperTest {

    @Test
    @DisplayName("the dump shows the party, the spawn and every prop")
    void showsTheRoom() {
        var room = new RoomGenerator(new ContentLoader()).generate("crypt", 3);

        var dump = RoomDumper.dump(room);
        var grid = dump.lines()
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertTrue(grid.contains("@"), "no party marker\n" + dump);
        assertTrue(grid.contains("g"), "no goblin spawn marker\n" + dump);
        assertTrue(grid.contains("S"), "no sarcophagus\n" + dump);
        assertTrue(dump.contains(room.roomId()), "no room id in the header\n" + dump);
    }

    @Test
    @DisplayName("the grid is exactly as wide and tall as the room")
    void gridMatchesDimensions() {
        var room = new RoomGenerator(new ContentLoader()).generate("crypt", 8);

        var gridLines = RoomDumper.dump(room).lines()
                .filter(line -> line.startsWith("|"))
                .toList();

        assertEquals(room.shape().height(), gridLines.size());
        for (var line : gridLines) {
            // A leading and trailing wall character bracket each row.
            assertEquals(room.shape().width() + 2, line.length(), line);
        }
    }
}
