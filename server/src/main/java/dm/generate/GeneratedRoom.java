package dm.generate;

import dm.content.RoomDefinition;
import dm.model.Prop;
import dm.model.Square;

import java.util.List;

/**
 * A generated room, and its adapter to the hand-authored shape the engine already reads.
 *
 * <p>Adapting rather than replacing is deliberate. {@code GameEngine}, {@code CombatEngine} and
 * the whole client speak {@link RoomDefinition}; a generated room that arrives as one is a
 * milestone that changes the generator and nothing else. The alternative — teaching every
 * consumer about a second room type — would spend M1's budget on plumbing.
 *
 * @param partyStart  where the fighter stands when the session opens
 * @param goblinSpawn where a hostile arrives, when one does
 */
public record GeneratedRoom(
        String roomId,
        RoomShape shape,
        List<Prop> props,
        Square partyStart,
        Square goblinSpawn
) {

    /** Replaced by {@code RoomDresser} before a player ever reads it. */
    private static final String UNDRESSED = "This room has not been dressed yet.";

    public RoomDefinition toRoomDefinition() {
        var definitions = props.stream()
                .map(p -> new RoomDefinition.PropDefinition(
                        p.id(), p.type(), p.x(), p.y(), p.rotation(), p.hidden(),
                        UNDRESSED, null, null, p.actions()))
                .toList();

        return new RoomDefinition(
                roomId,
                "An Unnamed Chamber",
                shape.width(),
                shape.height(),
                shape.floorType(),
                shape.wallType(),
                shape.lighting(),
                definitions,
                List.of(),
                new RoomDefinition.StartPositions(
                        List.of(new RoomDefinition.Point(partyStart.x(), partyStart.y())),
                        new RoomDefinition.Point(goblinSpawn.x(), goblinSpawn.y())),
                new RoomDefinition.DmNotes(UNDRESSED, UNDRESSED, null, null, null));
    }

    /** The same room, with a dress pass's prose written into it. */
    public RoomDefinition toRoomDefinition(Dressing dressing) {
        return dm.content.Dressings.applyTo(toRoomDefinition(), dressing);
    }
}
