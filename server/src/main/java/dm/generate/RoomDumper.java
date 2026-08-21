package dm.generate;

import dm.model.PropType;
import dm.model.Square;

/**
 * A generated room as text.
 *
 * <p>Exists for the same reason {@code debug → roll d20} does: the fastest loop wins, and
 * judging whether a room reads as a place should not cost a browser reload. Generation quality
 * is a property of what is in the room, and that is legible from a grid of characters.
 */
public final class RoomDumper {

    private RoomDumper() {
    }

    public static String dump(GeneratedRoom room) {
        var out = new StringBuilder();
        out.append(room.roomId())
                .append("  ").append(room.shape().width()).append('x').append(room.shape().height())
                .append("  ").append(room.shape().floorType())
                .append(" / ").append(room.shape().wallType())
                .append(" / ").append(room.shape().lighting())
                .append('\n');

        // North-up: larger y is north on the board and in worldState, so the far row prints
        // first. Printed y=0-first the dump reads as a mirror of the screen — the party at
        // the top, the goblin at the bottom.
        for (int y = room.shape().height() - 1; y >= 0; y--) {
            out.append('|');
            for (int x = 0; x < room.shape().width(); x++) {
                out.append(glyphAt(room, x, y));
            }
            out.append("|\n");
        }

        out.append("@ party  g goblin  S sarcophagus  b brazier  | pillar  % rubble  a alcove\n");
        return out.toString();
    }

    private static char glyphAt(GeneratedRoom room, int x, int y) {
        var square = new Square(x, y);
        if (square.equals(room.partyStart())) {
            return '@';
        }
        if (square.equals(room.goblinSpawn())) {
            return 'g';
        }
        return room.props().stream()
                .filter(p -> p.x() == x && p.y() == y)
                .findFirst()
                .map(p -> glyphFor(p.type()))
                .orElse('.');
    }

    private static char glyphFor(PropType type) {
        return switch (type) {
            case SARCOPHAGUS -> 'S';
            case BRAZIER -> 'b';
            case PILLAR -> '|';
            case RUBBLE -> '%';
            case ALCOVE -> 'a';
            case DOOR -> '+';
        };
    }
}
