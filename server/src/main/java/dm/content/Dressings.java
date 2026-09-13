package dm.content;

import dm.generate.Dressing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seam between a room's structure and its prose.
 *
 * <p>Spec §5a splits a room three ways — structure, dressing, secrets. Structure can always be
 * made again for free. Dressing cannot, when a model wrote it, so it is folded out of the log.
 * Secrets are authored and belong to neither.
 *
 * <p>An authored room already <em>contains</em> a {@link Dressing}; it is simply spread across
 * {@code name}, two {@code dmNotes} fields and every prop's description. Extracting it means both
 * sources go through one compose path, and M3 exercises that path with content that needs no
 * network — so the generator plan changes only where a {@code Dressing} comes from.
 */
public final class Dressings {

    private Dressings() {
    }

    /** The dressing an authored room was written with. */
    public static Dressing of(RoomDefinition room) {
        var descriptions = new LinkedHashMap<String, String>();
        for (var prop : room.props()) {
            if (prop.description() != null && !prop.description().isBlank()) {
                descriptions.put(prop.id(), prop.description());
            }
        }
        return new Dressing(
                room.name(),
                room.dmNotes().overview(),
                room.dmNotes().sensory(),
                Map.copyOf(descriptions));
    }

    /**
     * The same room wearing different prose.
     *
     * <p>A prop the dressing skipped gets an empty description rather than keeping the one it
     * had. Holding the old text would mean a room that is half one dressing and half another,
     * and {@code DmService.worldState} already omits the description tail when it is blank — a
     * prop with nothing to say is still listed, because it is still on the board.
     *
     * <p>Secrets pass through untouched. No model writes them and this must not drop them.
     */
    public static RoomDefinition applyTo(RoomDefinition room, Dressing dressing) {
        List<RoomDefinition.PropDefinition> props = room.props().stream()
                .map(p -> new RoomDefinition.PropDefinition(
                        p.id(), p.type(), p.x(), p.y(), p.rotation(), p.hidden(),
                        dressing.propDescriptions().getOrDefault(p.id(), ""),
                        p.revealHint(), p.contains(), p.actions()))
                .toList();

        return new RoomDefinition(
                room.roomId(),
                dressing.name(),
                room.width(),
                room.height(),
                room.floorType(),
                room.wallType(),
                room.lighting(),
                props,
                room.exits(),
                room.startPositions(),
                new RoomDefinition.DmNotes(
                        dressing.overview(),
                        dressing.sensory(),
                        room.dmNotes().theSarcophagus(),
                        room.dmNotes().theSarcophagusOpened(),
                        room.dmNotes().theDoor()),
                room.fires());
    }
}
