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
    @DisplayName("the dump prints north-up: the goblin row lands above the party row")
    void printsNorthUp() {
        var room = new RoomGenerator(new ContentLoader()).generate("crypt", 3);

        var grid = RoomDumper.dump(room).lines()
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .collect(java.util.stream.Collectors.joining("\n"));

        // Larger y is north, and the goblin always spawns in the far half of the room — so in
        // a north-up dump its row prints before the party's, matching the board on screen.
        assertTrue(grid.indexOf('g') < grid.indexOf('@'),
                "goblin should print above the party\n" + grid);
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
